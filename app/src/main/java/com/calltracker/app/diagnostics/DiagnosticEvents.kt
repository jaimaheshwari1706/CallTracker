package com.calltracker.app.diagnostics

/**
 * The vocabulary of the on-device event log.
 *
 * Every event is one row in `diagnostic_events`: a timestamp, one of the type
 * constants below, and a detail string of space-separated `key=value` pairs
 * built by [DiagnosticsStore.event]. Keeping metadata inside the existing
 * detail column means the schema stays at version 2 — no migration for a
 * logging change — while still being greppable and machine-splittable.
 *
 * Common keys: callLogId, result, reason, durationS, direction, status, sim,
 * num (masked), fp (fingerprint), rows, exception, message.
 *
 * A real outgoing call reads top-to-bottom like this:
 *
 *   14:31:02 TELEPHONY_STATE          state=OFFHOOK
 *   14:34:18 TELEPHONY_STATE          state=IDLE
 *   14:34:19 CALL_LOG_CHANGE_DETECTED selfChange=false
 *   14:34:19 SCAN_STARTED             reason=CALL_LOG_CHANGED after=1233 limit=50
 *   14:34:19 CALL_LOG_ROWS_FOUND      reason=CALL_LOG_CHANGED rows=1
 *   14:34:19 CALL_ROW_EVALUATED       callLogId=1234 type=2 durationS=41 num=********3210 fp=9f2c1a7b
 *   14:34:19 CALL_ALLOWED             callLogId=1234 result=ALLOW
 *   14:34:19 SIM_RESOLUTION           callLogId=1234 result=RESOLVED slot=0 subId=1
 *   14:34:19 CALL_STORED              callLogId=1234 direction=OUTGOING status=ANSWERED durationS=41
 *   14:34:19 CALL_SYNC_QUEUED         reason=CALL_LOG_CHANGED stored=1
 *   14:34:19 SCAN_FINISHED            reason=CALL_LOG_CHANGED rows=1 stored=1 excluded=0 duplicates=0
 *   14:34:20 SYNC_STARTED             batch=1
 *   14:34:20 SYNC_SUCCEEDED           batch=1 status=SYNCED
 *
 * Phone numbers never appear in full — see PhoneNumbers.maskForDiagnostics
 * and PhoneNumbers.fingerprintForDiagnostics.
 */
object DiagnosticEvents {
    // --- service / process lifecycle ---
    const val SERVICE_STARTED = "SERVICE_STARTED"
    const val SERVICE_STOPPED = "SERVICE_STOPPED"
    const val SERVICE_TIMEOUT = "SERVICE_TIMEOUT"
    const val BOOT_RECEIVED = "BOOT_RECEIVED"
    const val WORKER_STARTED = "WORKER_STARTED"
    const val WORKER_FINISHED = "WORKER_FINISHED"
    const val PERMISSION_CHANGED = "PERMISSION_CHANGED"

    // --- signals ---
    const val TELEPHONY_STATE = "TELEPHONY_STATE"
    const val CALL_LOG_CHANGE_DETECTED = "CALL_LOG_CHANGE_DETECTED"

    // --- capture pipeline ---
    const val SCAN_STARTED = "SCAN_STARTED"
    const val CALL_LOG_ROWS_FOUND = "CALL_LOG_ROWS_FOUND"
    const val CALL_ROW_EVALUATED = "CALL_ROW_EVALUATED"
    const val CALL_ALLOWED = "CALL_ALLOWED"
    const val CALL_EXCLUDED_PRIVACY = "CALL_EXCLUDED_PRIVACY"
    const val CALL_DUPLICATE = "CALL_DUPLICATE"
    const val SIM_RESOLUTION = "SIM_RESOLUTION"
    const val CALL_STORED = "CALL_STORED"
    const val CALL_SYNC_QUEUED = "CALL_SYNC_QUEUED"
    const val SCAN_FINISHED = "SCAN_FINISHED"

    // --- sync queue ---
    const val SYNC_STARTED = "SYNC_STARTED"
    const val SYNC_SUCCEEDED = "SYNC_SUCCEEDED"
    const val SYNC_FAILED = "SYNC_FAILED"

    // --- developer dry-run; never a real call ---
    const val SIMULATED = "SIMULATED"

    const val ERROR = "ERROR"

    /** Value of the `worker=` key, so WORKER_STARTED/FINISHED lines are unambiguous. */
    const val WORKER_CATCH_UP = "CallLogCatchUpWorker"
    const val WORKER_SYNC = "CallSyncWorker"
}
