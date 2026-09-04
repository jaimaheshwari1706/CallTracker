package com.calltracker.app.call

/** What the pipeline decided to do with one call-log row. */
enum class CaptureAction {
    /** New row, privacy check passed: persist it and queue it for sync. */
    STORE,

    /** New row, on the exclusion list: record the id only, never the number. */
    DISCARD_EXCLUDED,

    /** Already decided on in a previous notification, restart or retry. */
    SKIP_DUPLICATE
}

/**
 * The ordering rule of the capture pipeline, as a pure function.
 *
 * Two properties are pinned here because both are easy to get subtly wrong and
 * neither is visible from reading the service:
 *
 *  1. The duplicate check comes BEFORE the privacy check. The ContentObserver
 *     fires several times per call; if the order were reversed, one excluded
 *     personal call would be counted as several exclusions and would be
 *     re-evaluated on every restart.
 *
 *  2. An excluded row is still marked as processed. Otherwise it would be
 *     re-examined forever, and the "calls excluded" counter would climb on its
 *     own with no new calls.
 */
object CapturePipeline {

    fun decide(alreadyProcessed: Boolean, privacyDecision: PrivacyDecision): CaptureAction = when {
        alreadyProcessed -> CaptureAction.SKIP_DUPLICATE
        privacyDecision == PrivacyDecision.EXCLUDED -> CaptureAction.DISCARD_EXCLUDED
        else -> CaptureAction.STORE
    }

    /**
     * The incremental-scan watermark only ever moves forward, and only past rows
     * this scan actually looked at.
     *
     * It is a performance hint, not the deduplication mechanism — the
     * processed-marker table is. Keeping it monotonic matters anyway: a
     * watermark that went backwards would re-read rows (harmless), and one that
     * jumped ahead of an unexamined row would skip it until the next catch-up
     * scan (not harmless).
     */
    fun nextWatermark(current: Long, examinedRowIds: List<Long>): Long =
        (examinedRowIds.maxOrNull() ?: current).coerceAtLeast(current)
}
