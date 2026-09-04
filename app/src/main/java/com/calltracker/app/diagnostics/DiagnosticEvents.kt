package com.calltracker.app.diagnostics

/**
 * The vocabulary of the on-device event log. Kept as constants so the log is
 * greppable and so the OEM matrix can be filled in by reading it top to bottom:
 *
 *   14:31:02  TELEPHONY          OFFHOOK
 *   14:34:18  TELEPHONY          IDLE
 *   14:34:19  CALL_LOG_CHANGED   selfChange=false
 *   14:34:19  CALL_LOG_READ      id=1234 type=2 dur=41s
 *   14:34:19  PRIVACY_CHECK      PASSED ******3210
 *   14:34:19  CALL_SAVED         id=1234 OUTGOING/ANSWERED
 */
object DiagnosticEvents {
    const val SERVICE_STARTED = "SERVICE_STARTED"
    const val SERVICE_STOPPED = "SERVICE_STOPPED"
    const val SERVICE_TIMEOUT = "SERVICE_TIMEOUT"
    const val BOOT = "BOOT"
    const val PERMISSION = "PERMISSION"
    const val TELEPHONY = "TELEPHONY"
    const val CALL_LOG_CHANGED = "CALL_LOG_CHANGED"
    const val SCAN = "SCAN"
    const val CALL_LOG_READ = "CALL_LOG_READ"
    const val DUPLICATE_SKIPPED = "DUPLICATE_SKIPPED"
    const val PRIVACY_CHECK = "PRIVACY_CHECK"
    const val CALL_SAVED = "CALL_SAVED"
    const val SIM = "SIM"
    const val SYNC = "SYNC"
    const val ERROR = "ERROR"
}
