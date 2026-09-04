package com.calltracker.app.sync

import com.calltracker.app.database.CallDirection
import com.calltracker.app.database.CallEntity
import com.calltracker.app.database.CallStatus
import com.calltracker.app.database.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStateMachineTest {

    @Test
    fun `a successful sync moves a pending call to synced`() {
        assertEquals(
            SyncStatus.SYNCED,
            SyncStateMachine.next(SyncStatus.PENDING, SyncOutcome.SUCCESS, attemptsSoFar = 0)
        )
    }

    @Test
    fun `a transient failure keeps the call pending so it retries`() {
        assertEquals(
            SyncStatus.PENDING,
            SyncStateMachine.next(SyncStatus.PENDING, SyncOutcome.TRANSIENT_FAILURE, 0)
        )
        assertTrue(SyncStateMachine.shouldRetry(SyncStatus.PENDING))
    }

    @Test
    fun `transient failures stop retrying once the attempt budget is spent`() {
        val lastAllowed = SyncStateMachine.MAX_SYNC_ATTEMPTS - 2
        assertEquals(
            SyncStatus.PENDING,
            SyncStateMachine.next(SyncStatus.PENDING, SyncOutcome.TRANSIENT_FAILURE, lastAllowed)
        )
        assertEquals(
            SyncStatus.FAILED,
            SyncStateMachine.next(
                SyncStatus.PENDING,
                SyncOutcome.TRANSIENT_FAILURE,
                SyncStateMachine.MAX_SYNC_ATTEMPTS - 1
            )
        )
    }

    @Test
    fun `a permanent failure fails immediately without burning retries`() {
        assertEquals(
            SyncStatus.FAILED,
            SyncStateMachine.next(SyncStatus.PENDING, SyncOutcome.PERMANENT_FAILURE, 0)
        )
        assertFalse(SyncStateMachine.shouldRetry(SyncStatus.FAILED))
    }

    @Test
    fun `synced is terminal so a duplicate retry cannot requeue a call`() {
        assertEquals(
            SyncStatus.SYNCED,
            SyncStateMachine.next(SyncStatus.SYNCED, SyncOutcome.TRANSIENT_FAILURE, 0)
        )
        assertEquals(
            SyncStatus.SYNCED,
            SyncStateMachine.next(SyncStatus.SYNCED, SyncOutcome.PERMANENT_FAILURE, 9)
        )
        assertFalse(SyncStateMachine.shouldRetry(SyncStatus.SYNCED))
    }

    @Test
    fun `a failed call can be requeued and then succeed`() {
        assertEquals(
            SyncStatus.SYNCED,
            SyncStateMachine.next(SyncStatus.FAILED, SyncOutcome.SUCCESS, 0)
        )
    }

    @Test
    fun `the sync payload carries no contact name`() {
        val call = CallEntity(
            id = 1,
            deviceCallId = "1234",
            phoneNumber = "+919876543210",
            normalizedNumber = "+919876543210",
            contactName = "A Private Person",
            direction = CallDirection.OUTGOING,
            status = CallStatus.ANSWERED,
            startedAtEpochMs = 1_700_000_000_000L,
            durationSeconds = 41,
            endedAtEpochMs = 1_700_000_041_000L
        )
        val payload = call.toSyncPayload()

        // Contact data is device-local and must not appear in an upload batch.
        assertFalse(payload.toString().contains("A Private Person"))
        assertEquals("1234", payload.deviceCallId)
        assertEquals(41, payload.durationSeconds)
        assertEquals(1_700_000_041_000L, payload.endedAtEpochMs)
        assertNull(payload.simSlot)
    }
}
