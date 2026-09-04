package com.calltracker.app.call

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Duplicate detection.
 *
 * The scenarios below are the four ways this POC can be told about the same
 * call more than once, listed in the brief:
 *   - ContentObserver.onChange fires several times for one call
 *   - the telephony IDLE nudge triggers a scan on top of those
 *   - the app restarts and re-scans
 *   - a sync retry re-runs
 *
 * All four resolve to the same question: has this CallLog._ID already been
 * decided on? These tests pin the rule. The database-level guarantee (UNIQUE
 * index on calls.deviceCallId, PRIMARY KEY on processed_call_log_rows) is the
 * second layer and needs an instrumented test on a device to exercise.
 */
class DeduplicationTest {

    @Test
    fun `a new allowed row is stored`() {
        assertEquals(
            CaptureAction.STORE,
            CapturePipeline.decide(alreadyProcessed = false, privacyDecision = PrivacyDecision.ALLOW)
        )
    }

    @Test
    fun `a repeated notification for the same row is skipped`() {
        assertEquals(
            CaptureAction.SKIP_DUPLICATE,
            CapturePipeline.decide(alreadyProcessed = true, privacyDecision = PrivacyDecision.ALLOW)
        )
    }

    @Test
    fun `an excluded row is discarded, not stored`() {
        assertEquals(
            CaptureAction.DISCARD_EXCLUDED,
            CapturePipeline.decide(false, PrivacyDecision.EXCLUDED)
        )
    }

    @Test
    fun `a repeated notification for an excluded row is not re-counted`() {
        // The duplicate check wins over the privacy result. Without this, one
        // excluded call would inflate the "calls excluded" counter on every
        // observer callback and on every app restart.
        assertEquals(
            CaptureAction.SKIP_DUPLICATE,
            CapturePipeline.decide(alreadyProcessed = true, privacyDecision = PrivacyDecision.EXCLUDED)
        )
    }

    @Test
    fun `a withheld-number row is stored once and skipped afterwards`() {
        assertEquals(
            CaptureAction.STORE,
            CapturePipeline.decide(false, PrivacyDecision.ALLOW_UNRESOLVED_NUMBER)
        )
        assertEquals(
            CaptureAction.SKIP_DUPLICATE,
            CapturePipeline.decide(true, PrivacyDecision.ALLOW_UNRESOLVED_NUMBER)
        )
    }

    @Test
    fun `one call notified five times produces exactly one store`() {
        // Simulates an observer burst against a ledger of processed ids.
        val processed = mutableSetOf<Long>()
        val callLogId = 1234L
        val actions = (1..5).map {
            val action = CapturePipeline.decide(
                alreadyProcessed = callLogId in processed,
                privacyDecision = PrivacyDecision.ALLOW
            )
            if (action != CaptureAction.SKIP_DUPLICATE) processed.add(callLogId)
            action
        }
        assertEquals(1, actions.count { it == CaptureAction.STORE })
        assertEquals(4, actions.count { it == CaptureAction.SKIP_DUPLICATE })
    }

    @Test
    fun `a restart re-scan of already-seen rows stores nothing new`() {
        val processed = mutableSetOf(101L, 102L, 103L)
        val rescanned = listOf(101L, 102L, 103L)
        val stored = rescanned.count {
            CapturePipeline.decide(it in processed, PrivacyDecision.ALLOW) == CaptureAction.STORE
        }
        assertEquals(0, stored)
    }

    @Test
    fun `a catch-up scan stores only the rows that appeared while we were dead`() {
        val processed = mutableSetOf(101L, 102L)
        val catchUpRows = listOf(101L, 102L, 103L, 104L)
        val actions = catchUpRows.map { id ->
            val action = CapturePipeline.decide(id in processed, PrivacyDecision.ALLOW)
            if (action != CaptureAction.SKIP_DUPLICATE) processed.add(id)
            action
        }
        assertEquals(2, actions.count { it == CaptureAction.STORE })
    }

    @Test
    fun `the watermark only ever moves forward`() {
        assertEquals(120L, CapturePipeline.nextWatermark(100L, listOf(110L, 120L)))
        // A catch-up scan reads older rows; that must not rewind the watermark.
        assertEquals(120L, CapturePipeline.nextWatermark(120L, listOf(80L, 90L)))
    }

    @Test
    fun `an empty scan leaves the watermark untouched`() {
        assertEquals(120L, CapturePipeline.nextWatermark(120L, emptyList()))
        assertEquals(0L, CapturePipeline.nextWatermark(0L, emptyList()))
    }
}
