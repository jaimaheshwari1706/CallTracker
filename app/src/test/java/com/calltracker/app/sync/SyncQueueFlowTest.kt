package com.calltracker.app.sync

import com.calltracker.app.database.FakeCallDao
import com.calltracker.app.database.SyncStatus
import com.calltracker.app.database.sampleCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue flow the brief asks to verify, using the real [SyncStateMachine],
 * the real [LocalLoopbackSyncClient], the real payload mapping and the real
 * DAO helper logic (via FakeCallDao). WorkManager itself is not involved: what
 * CallSyncWorker.doWork() does per run is reproduced by [runOneSyncPass], which
 * mirrors its body step for step.
 *
 *   stored call -> PENDING -> sync pass -> LocalLoopbackSyncClient -> SYNCED
 *   PENDING -> simulated failures -> FAILED -> requeue -> SYNCED
 */
class SyncQueueFlowTest {

    /** A stand-in backend that fails a fixed number of times, then succeeds. */
    private class FlakySyncClient(private var failuresLeft: Int, private val permanent: Boolean = false) : CallSyncClient {
        var calls = 0
        override suspend fun sync(deviceId: String, batch: List<CallSyncPayload>): SyncOutcome {
            calls++
            if (failuresLeft > 0) {
                failuresLeft--
                return if (permanent) SyncOutcome.PERMANENT_FAILURE else SyncOutcome.TRANSIENT_FAILURE
            }
            return SyncOutcome.SUCCESS
        }
    }

    /** Mirrors CallSyncWorker.doWork() minus WorkManager and diagnostics. Returns true if a retry is wanted. */
    private suspend fun runOneSyncPass(dao: FakeCallDao, client: CallSyncClient): Boolean {
        val pending = dao.getPendingBatch(50)
        if (pending.isEmpty()) return false
        val outcome = try {
            client.sync("poc-device", pending.map { it.toSyncPayload() })
        } catch (e: Exception) {
            SyncOutcome.TRANSIENT_FAILURE
        }
        val worstAttempts = pending.maxOf { it.syncAttempts }
        val next = SyncStateMachine.next(pending.first().syncStatus, outcome, worstAttempts)
        dao.applySyncResult(pending.map { it.id }, next, System.currentTimeMillis())
        return SyncStateMachine.shouldRetry(next)
    }

    @Test
    fun `a stored call starts PENDING and the loopback client moves it to SYNCED`() = runBlocking {
        val dao = FakeCallDao()
        assertTrue(dao.inTransaction { dao.recordAccepted(sampleCall("1")) })
        assertEquals(SyncStatus.PENDING, dao.byDeviceCallId("1")!!.syncStatus)
        assertEquals(1, dao.getPendingBatch(50).size)

        val retry = runOneSyncPass(dao, LocalLoopbackSyncClient())

        assertFalse(retry)
        val call = dao.byDeviceCallId("1")!!
        assertEquals(SyncStatus.SYNCED, call.syncStatus)
        assertEquals(1, call.syncAttempts)
        assertTrue(call.lastSyncAttemptAtEpochMs != null)
        assertEquals(0, dao.getPendingBatch(50).size)
    }

    @Test
    fun `a synced call is not re-sent by a later pass`() = runBlocking {
        val dao = FakeCallDao()
        dao.inTransaction { dao.recordAccepted(sampleCall("1")) }
        val client = FlakySyncClient(failuresLeft = 0)
        runOneSyncPass(dao, client)
        runOneSyncPass(dao, client)
        runOneSyncPass(dao, client)
        assertEquals(1, client.calls)
        assertEquals(SyncStatus.SYNCED, dao.byDeviceCallId("1")!!.syncStatus)
    }

    @Test
    fun `transient failures keep the call PENDING and request a retry`() = runBlocking {
        val dao = FakeCallDao()
        dao.inTransaction { dao.recordAccepted(sampleCall("1")) }
        val client = FlakySyncClient(failuresLeft = 2)

        assertTrue(runOneSyncPass(dao, client))   // attempt 1 fails -> PENDING, retry
        assertEquals(SyncStatus.PENDING, dao.byDeviceCallId("1")!!.syncStatus)
        assertTrue(runOneSyncPass(dao, client))   // attempt 2 fails -> PENDING, retry
        assertFalse(runOneSyncPass(dao, client))  // attempt 3 succeeds -> SYNCED
        assertEquals(SyncStatus.SYNCED, dao.byDeviceCallId("1")!!.syncStatus)
        assertEquals(3, dao.byDeviceCallId("1")!!.syncAttempts)
    }

    @Test
    fun `exhausted transient failures end in FAILED, requeue restores PENDING, next success SYNCs`() = runBlocking {
        val dao = FakeCallDao()
        dao.inTransaction { dao.recordAccepted(sampleCall("1")) }
        val alwaysFailing = FlakySyncClient(failuresLeft = Int.MAX_VALUE)

        var passes = 0
        while (runOneSyncPass(dao, alwaysFailing)) {
            passes++
            assertTrue("runaway retry loop", passes < 50)
        }
        val failed = dao.byDeviceCallId("1")!!
        assertEquals(SyncStatus.FAILED, failed.syncStatus)
        assertEquals(SyncStateMachine.MAX_SYNC_ATTEMPTS, failed.syncAttempts)
        // FAILED is not in the pending batch, so the worker leaves it alone.
        assertEquals(0, dao.getPendingBatch(50).size)

        // Tester presses "Retry failed".
        assertEquals(1, dao.requeueFailed())
        assertEquals(SyncStatus.PENDING, dao.byDeviceCallId("1")!!.syncStatus)
        assertEquals(0, dao.byDeviceCallId("1")!!.syncAttempts)

        // Backend (loopback) now accepts it.
        assertFalse(runOneSyncPass(dao, LocalLoopbackSyncClient()))
        assertEquals(SyncStatus.SYNCED, dao.byDeviceCallId("1")!!.syncStatus)
    }

    @Test
    fun `a permanent failure goes straight to FAILED without burning the retry budget`() = runBlocking {
        val dao = FakeCallDao()
        dao.inTransaction { dao.recordAccepted(sampleCall("1")) }
        val client = FlakySyncClient(failuresLeft = 1, permanent = true)

        assertFalse(runOneSyncPass(dao, client))
        val call = dao.byDeviceCallId("1")!!
        assertEquals(SyncStatus.FAILED, call.syncStatus)
        assertEquals(1, call.syncAttempts)
    }

    @Test
    fun `a client that throws is treated as a transient failure`() = runBlocking {
        val dao = FakeCallDao()
        dao.inTransaction { dao.recordAccepted(sampleCall("1")) }
        val throwing = object : CallSyncClient {
            override suspend fun sync(deviceId: String, batch: List<CallSyncPayload>): SyncOutcome =
                throw IllegalStateException("no network")
        }
        assertTrue(runOneSyncPass(dao, throwing))
        assertEquals(SyncStatus.PENDING, dao.byDeviceCallId("1")!!.syncStatus)
    }

    @Test
    fun `the batch sent to the client carries no contact name and only accepted calls`() = runBlocking {
        val dao = FakeCallDao()
        dao.inTransaction { dao.recordAccepted(sampleCall("1").copy(contactName = "Someone Private")) }
        dao.inTransaction { dao.recordExcluded("2") }
        var captured: List<CallSyncPayload> = emptyList()
        val spy = object : CallSyncClient {
            override suspend fun sync(deviceId: String, batch: List<CallSyncPayload>): SyncOutcome {
                captured = batch
                return SyncOutcome.SUCCESS
            }
        }
        runOneSyncPass(dao, spy)
        assertEquals(1, captured.size)
        assertEquals("1", captured[0].deviceCallId)
        assertFalse(captured.toString().contains("Someone Private"))
    }
}
