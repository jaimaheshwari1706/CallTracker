package com.calltracker.app.call

import com.calltracker.app.database.CallDirection
import com.calltracker.app.database.CallStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Direction, status and duration mapping.
 *
 * The TYPE values are written as literals rather than CallLog.Calls constants so
 * the test asserts against the numbers the provider actually stores, and so a
 * future refactor of the mapper cannot quietly move with it.
 */
class CallLogMappingTest {

    private companion object {
        const val INCOMING = 1
        const val OUTGOING = 2
        const val MISSED = 3
        const val VOICEMAIL = 4
        const val REJECTED = 5
        const val BLOCKED = 6
        const val ANSWERED_EXTERNALLY = 7
    }

    @Test
    fun `direction separates who dialled from what happened`() {
        assertEquals(CallDirection.OUTGOING, mapDirection(OUTGOING))
        assertEquals(CallDirection.INCOMING, mapDirection(INCOMING))
        // A missed or rejected call is still an INCOMING call.
        assertEquals(CallDirection.INCOMING, mapDirection(MISSED))
        assertEquals(CallDirection.INCOMING, mapDirection(REJECTED))
        assertEquals(CallDirection.INCOMING, mapDirection(BLOCKED))
        assertEquals(CallDirection.INCOMING, mapDirection(VOICEMAIL))
        assertEquals(CallDirection.INCOMING, mapDirection(ANSWERED_EXTERNALLY))
    }

    @Test
    fun `an unrecognised type is UNKNOWN rather than guessed`() {
        assertEquals(CallDirection.UNKNOWN, mapDirection(99))
        assertEquals(CallStatus.UNKNOWN, mapStatus(99, 30))
    }

    @Test
    fun `connected calls are ANSWERED and zero-duration calls are not`() {
        assertEquals(CallStatus.ANSWERED, mapStatus(INCOMING, 42))
        assertEquals(CallStatus.ANSWERED, mapStatus(OUTGOING, 1))
        assertEquals(CallStatus.NOT_CONNECTED, mapStatus(OUTGOING, 0))
        assertEquals(CallStatus.NOT_CONNECTED, mapStatus(INCOMING, 0))
    }

    @Test
    fun `unanswered incoming types map to their own status`() {
        assertEquals(CallStatus.MISSED, mapStatus(MISSED, 0))
        assertEquals(CallStatus.REJECTED, mapStatus(REJECTED, 0))
        assertEquals(CallStatus.BLOCKED, mapStatus(BLOCKED, 0))
        assertEquals(CallStatus.VOICEMAIL, mapStatus(VOICEMAIL, 12))
        assertEquals(CallStatus.ANSWERED_ELSEWHERE, mapStatus(ANSWERED_EXTERNALLY, 0))
    }

    @Test
    fun `negative durations reported by some OEM builds are clamped`() {
        assertEquals(0, sanitizeDuration(-1))
        assertEquals(0, sanitizeDuration(0))
        assertEquals(90, sanitizeDuration(90))
    }

    @Test
    fun `ended time is start plus duration in milliseconds`() {
        val start = 1_700_000_000_000L
        assertEquals(start + 90_000L, endedAtEpochMs(start, 90))
    }

    @Test
    fun `a call that never connected ends when it started`() {
        val start = 1_700_000_000_000L
        assertEquals(start, endedAtEpochMs(start, 0))
        assertEquals(start, endedAtEpochMs(start, -5))
    }

    @Test
    fun `a long call does not overflow the millisecond arithmetic`() {
        val start = 1_700_000_000_000L
        val fourHours = 4 * 60 * 60
        assertEquals(start + 14_400_000L, endedAtEpochMs(start, fourHours))
    }

    // ------------------------------------------------------------------
    // Representative rows, exactly as the three baseline test calls in the
    // physical-device plan should come back from CallLog.Calls. Each row is
    // run through the same three functions CallCaptureEngine uses.
    // ------------------------------------------------------------------

    private data class Expected(val direction: CallDirection, val status: CallStatus, val endOffsetMs: Long)

    private fun mapRow(row: CallLogRow) = Triple(
        mapDirection(row.type),
        mapStatus(row.type, sanitizeDuration(row.durationSeconds)),
        endedAtEpochMs(row.dateEpochMs, row.durationSeconds) - row.dateEpochMs
    )

    private fun row(type: Int, duration: Int) = CallLogRow(
        id = 1, number = "+919876543210", type = type, dateEpochMs = 1_700_000_000_000L,
        durationSeconds = duration, phoneAccountId = null, cachedName = null
    )

    @Test
    fun `Phase 2 - outgoing answered call, 41 seconds`() {
        val (d, s, end) = mapRow(row(OUTGOING, 41))
        assertEquals(Expected(CallDirection.OUTGOING, CallStatus.ANSWERED, 41_000L), Expected(d, s, end))
    }

    @Test
    fun `Phase 3 - incoming answered call, 30 seconds`() {
        val (d, s, end) = mapRow(row(INCOMING, 30))
        assertEquals(Expected(CallDirection.INCOMING, CallStatus.ANSWERED, 30_000L), Expected(d, s, end))
    }

    @Test
    fun `Phase 4 - missed call has zero duration and ends when it started`() {
        val (d, s, end) = mapRow(row(MISSED, 0))
        assertEquals(Expected(CallDirection.INCOMING, CallStatus.MISSED, 0L), Expected(d, s, end))
    }

    @Test
    fun `outgoing call the other side never picked up is NOT_CONNECTED, not MISSED`() {
        val (d, s, _) = mapRow(row(OUTGOING, 0))
        assertEquals(CallDirection.OUTGOING, d)
        assertEquals(CallStatus.NOT_CONNECTED, s)
    }

    @Test
    fun `rejected and blocked calls are incoming with their own status`() {
        assertEquals(CallDirection.INCOMING to CallStatus.REJECTED, mapRow(row(REJECTED, 0)).let { it.first to it.second })
        assertEquals(CallDirection.INCOMING to CallStatus.BLOCKED, mapRow(row(BLOCKED, 0)).let { it.first to it.second })
    }

    @Test
    fun `an OEM writing a negative duration yields a zero-length NOT_CONNECTED call`() {
        val (d, s, end) = mapRow(row(OUTGOING, -3))
        assertEquals(CallDirection.OUTGOING, d)
        assertEquals(CallStatus.NOT_CONNECTED, s)
        assertEquals(0L, end)
    }

    @Test
    fun `an unknown provider type never becomes INCOMING or OUTGOING by accident`() {
        val (d, s, _) = mapRow(row(42, 10))
        assertEquals(CallDirection.UNKNOWN, d)
        assertEquals(CallStatus.UNKNOWN, s)
    }
}
