package com.calltracker.app.call

import com.calltracker.app.database.FakeCallDao
import com.calltracker.app.database.ProcessingOutcome
import com.calltracker.app.database.sampleCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deduplication through the REAL CallDao transactional helpers, run against an
 * in-memory fake of the abstract Room methods (see FakeCallDao for exactly what
 * is real and what is modelled).
 *
 * Scenarios from the brief:
 *   A. same CallLog id processed twice
 *   B. two scan triggers arrive almost simultaneously
 *   C. same call seen by the ContentObserver path and the catch-up worker
 *   D. excluded call seen more than once
 *   E. allowed call seen more than once
 *
 * Expected result in every case: exactly one final processing outcome.
 */
class DeduplicationLedgerTest {

    // ---------------------------------------------------------------- A / E

    @Test
    fun `A - same call-log id recorded twice stores one row and reports the second as duplicate`() = runBlocking {
        val dao = FakeCallDao()
        val first = dao.inTransaction { dao.recordAccepted(sampleCall("1234")) }
        val second = dao.inTransaction { dao.recordAccepted(sampleCall("1234")) }

        assertTrue(first)
        assertFalse(second)
        assertEquals(1, dao.storedCount())
        assertEquals(ProcessingOutcome.STORED, dao.ledger["1234"]?.outcome)
    }

    @Test
    fun `E - allowed call seen five times is stored exactly once`() = runBlocking {
        val dao = FakeCallDao()
        val results = (1..5).map { dao.inTransaction { dao.recordAccepted(sampleCall("42")) } }
        assertEquals(listOf(true, false, false, false, false), results)
        assertEquals(1, dao.storedCount())
    }

    // ---------------------------------------------------------------- D

    @Test
    fun `D - excluded call seen three times is recorded once and never stored`() = runBlocking {
        val dao = FakeCallDao()
        val results = (1..3).map { dao.inTransaction { dao.recordExcluded("77") } }

        assertEquals(listOf(true, false, false), results)
        assertEquals(0, dao.storedCount())
        assertEquals(1, dao.excludedCount())
        // The number is not in the ledger row - only the id and the outcome.
        assertEquals("77", dao.ledger["77"]?.deviceCallId)
        assertEquals(ProcessingOutcome.EXCLUDED_PRIVACY, dao.ledger["77"]?.outcome)
    }

    @Test
    fun `D - an excluded call cannot later be stored by a second scan`() = runBlocking {
        val dao = FakeCallDao()
        // First scan: on the exclusion list.
        assertTrue(dao.inTransaction { dao.recordExcluded("500") })
        // Second scan (e.g. after the number was removed from the list): the
        // ledger says this row was already decided, so it is a duplicate, not a store.
        assertFalse(dao.inTransaction { dao.recordAccepted(sampleCall("500")) })
        assertNull(dao.byDeviceCallId("500"))
        assertEquals(ProcessingOutcome.EXCLUDED_PRIVACY, dao.ledger["500"]?.outcome)
    }

    // ---------------------------------------------------------------- B

    @Test
    fun `B - fifty near-simultaneous transactions for one row yield exactly one store`() = runBlocking {
        val dao = FakeCallDao()
        val outcomes = coroutineScope {
            (1..50).map {
                async(Dispatchers.Default) {
                    dao.inTransaction { dao.recordAccepted(sampleCall("9001")) }
                }
            }.awaitAll()
        }
        assertEquals(1, outcomes.count { it })
        assertEquals(49, outcomes.count { !it })
        assertEquals(1, dao.storedCount())
    }

    @Test
    fun `B - without transaction serialization the uniqueness layer still yields one row`() = runBlocking {
        // Deliberately NO inTransaction: this models the worst case where the
        // check-then-insert interleaves. Layer 1 (putIfAbsent == UNIQUE index)
        // must still guarantee a single stored row, even though more than one
        // caller may see `true` from the ledger check.
        val dao = FakeCallDao()
        coroutineScope {
            repeat(50) {
                launch(Dispatchers.Default) { dao.recordAccepted(sampleCall("9002")) }
            }
        }
        assertEquals(1, dao.storedCount())
        assertEquals(1, dao.ledger.size)
    }

    // ---------------------------------------------------------------- C

    @Test
    fun `C - observer path and catch-up worker racing on the same rows store each row once`() = runBlocking {
        val dao = FakeCallDao()
        // The observer scan sees rows 100..104 (incremental); the catch-up
        // worker re-reads 90..104 (no watermark). Both run under the same
        // process-wide mutex in production; here that mutex is modelled by
        // SCAN_MUTEX below, and each row still goes through the DAO transaction.
        val scanMutex = Mutex()
        val observerRows = (100L..104L).toList()
        val catchUpRows = (90L..104L).toList()

        suspend fun scan(rows: List<Long>): Int = scanMutex.withLock {
            rows.count { id -> dao.inTransaction { dao.recordAccepted(sampleCall(id.toString())) } }
        }

        val (storedByObserver, storedByCatchUp) = coroutineScope {
            val a = async(Dispatchers.Default) { scan(observerRows) }
            val b = async(Dispatchers.Default) { scan(catchUpRows) }
            a.await() to b.await()
        }

        // 15 distinct rows exist; the two scans between them stored each once.
        assertEquals(15, storedByObserver + storedByCatchUp)
        assertEquals(15, dao.storedCount())
        for (id in 90L..104L) assertNotNull(dao.byDeviceCallId(id.toString()))
    }

    // ---------------------------------------------------------------- watermark interplay

    @Test
    fun `a lost watermark does not cause duplicates - the ledger is the source of truth`() = runBlocking {
        val dao = FakeCallDao()
        // First run: watermark advances to 3.
        for (id in 1L..3L) dao.inTransaction { dao.recordAccepted(sampleCall(id.toString())) }
        var watermark = CapturePipeline.nextWatermark(0L, listOf(1L, 2L, 3L))
        assertEquals(3L, watermark)

        // "App data restored / prefs wiped": watermark is 0 again, rows 1..3 re-read.
        watermark = 0L
        val reStored = (1L..3L).count { id -> dao.inTransaction { dao.recordAccepted(sampleCall(id.toString())) } }
        assertEquals(0, reStored)
        assertEquals(3, dao.storedCount())
        assertEquals(3L, CapturePipeline.nextWatermark(watermark, listOf(1L, 2L, 3L)))
    }
}
