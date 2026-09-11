package com.calltracker.app.diagnostics

import android.content.Context
import android.content.SharedPreferences
import com.calltracker.app.database.AppDatabase
import com.calltracker.app.database.DiagnosticEventEntity
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Scalar runtime state for the diagnostics screen (counts of stored/excluded
 * calls come from Room; the counters here are things Room does not record,
 * like "how many times did the observer fire").
 *
 * SharedPreferences rather than Room because these values are written from the
 * service on every ContentObserver tick and read by the UI; a tiny key/value
 * store that survives process death is exactly the right shape, and it keeps
 * "was monitoring running when the OEM killed us?" answerable after a restart.
 *
 * Two kinds of state live here and must not be confused:
 *
 *  - Diagnostic counters (observer count, duplicates seen, sync counts, last-x
 *    timestamps). Safe to reset; [resetDiagnosticCounters] does exactly that.
 *  - Pipeline state (the scan watermark, consent). NOT reset by any diagnostic
 *    action. Losing the watermark is harmless for correctness but would make a
 *    tester think the incremental path re-read history.
 */
class DiagnosticsStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- monitoring lifecycle ---

    var monitoringActive: Boolean
        get() = prefs.getBoolean(KEY_MONITORING_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_MONITORING_ACTIVE, value).apply()

    var monitoringStartedAt: Long
        get() = prefs.getLong(KEY_MONITORING_STARTED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_MONITORING_STARTED_AT, value).apply()

    /**
     * True between a successful registerContentObserver() and onDestroy().
     * Written by the service only. If the process is killed the flag stays
     * true, which is why the pre-flight also checks whether the service is
     * actually running and reports a stale flag as a WARNING.
     */
    var observerRegistered: Boolean
        get() = prefs.getBoolean(KEY_OBSERVER_REGISTERED, false)
        set(value) = prefs.edit().putBoolean(KEY_OBSERVER_REGISTERED, value).apply()

    /** Same contract as [observerRegistered], for the telephony listener. */
    var telephonyRegistered: Boolean
        get() = prefs.getBoolean(KEY_TELEPHONY_REGISTERED, false)
        set(value) = prefs.edit().putBoolean(KEY_TELEPHONY_REGISTERED, value).apply()

    /** Set when Android 15+ ends a dataSync foreground service at its 6h budget. */
    var lastForegroundTimeoutAt: Long
        get() = prefs.getLong(KEY_FGS_TIMEOUT_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_FGS_TIMEOUT_AT, value).apply()

    // --- signal 1: telephony ---

    val lastTelephonyState: String
        get() = prefs.getString(KEY_TELEPHONY_STATE, "") ?: ""

    val lastTelephonyStateAt: Long
        get() = prefs.getLong(KEY_TELEPHONY_STATE_AT, 0L)

    fun recordTelephonyState(state: String, atEpochMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_TELEPHONY_STATE, state)
            .putLong(KEY_TELEPHONY_STATE_AT, atEpochMs)
            .apply()
    }

    // --- signal 2: content observer ---

    val lastObserverEventAt: Long
        get() = prefs.getLong(KEY_OBSERVER_AT, 0L)

    val observerEventCount: Int
        get() = prefs.getInt(KEY_OBSERVER_COUNT, 0)

    fun recordObserverEvent(atEpochMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_OBSERVER_AT, atEpochMs)
            .putInt(KEY_OBSERVER_COUNT, observerEventCount + 1)
            .apply()
    }

    // --- scans ---

    val lastScanAt: Long
        get() = prefs.getLong(KEY_LAST_SCAN_AT, 0L)

    val lastScanReason: String
        get() = prefs.getString(KEY_LAST_SCAN_REASON, "") ?: ""

    val lastScanSummary: String
        get() = prefs.getString(KEY_LAST_SCAN_SUMMARY, "") ?: ""

    val duplicateCount: Int
        get() = prefs.getInt(KEY_DUPLICATE_COUNT, 0)

    fun recordScan(
        reason: String,
        summary: String,
        duplicates: Int,
        atEpochMs: Long = System.currentTimeMillis()
    ) {
        prefs.edit()
            .putLong(KEY_LAST_SCAN_AT, atEpochMs)
            .putString(KEY_LAST_SCAN_REASON, reason)
            .putString(KEY_LAST_SCAN_SUMMARY, summary)
            .putInt(KEY_DUPLICATE_COUNT, duplicateCount + duplicates)
            .apply()
    }

    // --- scan watermark (pipeline state, not a diagnostic counter) ---

    /**
     * Highest CallLog._ID the incremental scan has already covered.
     *
     * This is a performance hint ONLY. Correctness comes from the
     * processed_call_log_rows table; see CallDao. If this value is wrong or
     * lost, the catch-up scan still finds the rows and the unique constraints
     * still prevent duplicates.
     */
    var lastProcessedCallLogId: Long
        get() = prefs.getLong(KEY_LAST_ID, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ID, value).apply()

    // --- workers ---

    val lastCatchUpRunAt: Long
        get() = prefs.getLong(KEY_LAST_CATCH_UP_AT, 0L)

    fun recordCatchUpRun(atEpochMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_CATCH_UP_AT, atEpochMs).apply()
    }

    // --- sync ---

    val lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC_AT, 0L)

    val lastSyncResult: String
        get() = prefs.getString(KEY_LAST_SYNC_RESULT, "") ?: ""

    val syncSucceededCount: Int
        get() = prefs.getInt(KEY_SYNC_OK_COUNT, 0)

    val syncFailedCount: Int
        get() = prefs.getInt(KEY_SYNC_FAIL_COUNT, 0)

    fun recordSync(result: String, succeeded: Boolean?, atEpochMs: Long = System.currentTimeMillis()) {
        val edit = prefs.edit()
            .putString(KEY_LAST_SYNC_RESULT, result)
            .putLong(KEY_LAST_SYNC_AT, atEpochMs)
        when (succeeded) {
            true -> edit.putInt(KEY_SYNC_OK_COUNT, syncSucceededCount + 1)
            false -> edit.putInt(KEY_SYNC_FAIL_COUNT, syncFailedCount + 1)
            null -> Unit // "nothing pending" is neither a success nor a failure
        }
        edit.apply()
    }

    // --- consent (pipeline state; never reset by diagnostics) ---

    var consentAcknowledged: Boolean
        get() = prefs.getBoolean(KEY_CONSENT, false)
        set(value) = prefs.edit().putBoolean(KEY_CONSENT, value).apply()

    // --- reset ---

    /**
     * Clears the diagnostic counters and last-seen timestamps ONLY.
     *
     * Does not touch: the scan watermark, consent, the `calls` table, the
     * processed-row ledger, the exclusion list. Clearing diagnostics must never
     * change what the pipeline does next.
     */
    fun resetDiagnosticCounters() {
        prefs.edit()
            .remove(KEY_OBSERVER_AT)
            .remove(KEY_OBSERVER_COUNT)
            .remove(KEY_TELEPHONY_STATE)
            .remove(KEY_TELEPHONY_STATE_AT)
            .remove(KEY_LAST_SCAN_AT)
            .remove(KEY_LAST_SCAN_REASON)
            .remove(KEY_LAST_SCAN_SUMMARY)
            .remove(KEY_DUPLICATE_COUNT)
            .remove(KEY_LAST_CATCH_UP_AT)
            .remove(KEY_LAST_SYNC_AT)
            .remove(KEY_LAST_SYNC_RESULT)
            .remove(KEY_SYNC_OK_COUNT)
            .remove(KEY_SYNC_FAIL_COUNT)
            .remove(KEY_FGS_TIMEOUT_AT)
            .apply()
    }

    // --- event log ---

    /**
     * Appends one structured line to the on-device log.
     *
     * Pairs are rendered as `key=value` separated by spaces; values are
     * whitespace-collapsed so a line always splits back into its pairs. Null
     * values are dropped. Nothing here masks — callers must pass numbers through
     * maskForDiagnostics()/fingerprintForDiagnostics() before they get here.
     */
    suspend fun event(type: String, vararg fields: Pair<String, Any?>) {
        log(type, formatFields(*fields))
    }

    /** Convenience for the ERROR type: records the exception class and message. */
    suspend fun error(where: String, e: Throwable, vararg fields: Pair<String, Any?>) {
        event(
            DiagnosticEvents.ERROR,
            "where" to where,
            "exception" to e.javaClass.simpleName,
            "message" to (e.message ?: ""),
            *fields
        )
    }

    /** Raw append. Prefer [event]. */
    suspend fun log(type: String, detail: String) {
        val dao = AppDatabase.get(appContext).diagnosticEventDao()
        dao.insert(
            DiagnosticEventEntity(
                atEpochMs = System.currentTimeMillis(),
                type = type,
                detail = detail
            )
        )
        // Cheap enough at POC volumes; keeps the log from growing unbounded on a
        // device left running for a multi-day OEM soak test.
        if ((++writesSinceTrim) >= TRIM_EVERY) {
            writesSinceTrim = 0
            dao.trimTo(MAX_EVENTS)
        }
    }

    private var writesSinceTrim = 0

    /** Emits a fresh snapshot whenever any scalar above changes. */
    fun snapshots(): Flow<DiagnosticsSnapshot> = callbackFlow {
        fun emit() { trySend(snapshot()) }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> emit() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        emit()
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    fun snapshot() = DiagnosticsSnapshot(
        monitoringActive = monitoringActive,
        monitoringStartedAt = monitoringStartedAt,
        observerRegistered = observerRegistered,
        telephonyRegistered = telephonyRegistered,
        lastForegroundTimeoutAt = lastForegroundTimeoutAt,
        lastTelephonyState = lastTelephonyState,
        lastTelephonyStateAt = lastTelephonyStateAt,
        lastObserverEventAt = lastObserverEventAt,
        observerEventCount = observerEventCount,
        lastScanAt = lastScanAt,
        lastScanReason = lastScanReason,
        lastScanSummary = lastScanSummary,
        duplicateCount = duplicateCount,
        lastProcessedCallLogId = lastProcessedCallLogId,
        lastCatchUpRunAt = lastCatchUpRunAt,
        lastSyncAt = lastSyncAt,
        lastSyncResult = lastSyncResult,
        syncSucceededCount = syncSucceededCount,
        syncFailedCount = syncFailedCount
    )

    companion object {
        private const val PREFS_NAME = "call_tracker_diagnostics"
        private const val KEY_MONITORING_ACTIVE = "monitoring_active"
        private const val KEY_MONITORING_STARTED_AT = "monitoring_started_at"
        private const val KEY_OBSERVER_REGISTERED = "observer_registered"
        private const val KEY_TELEPHONY_REGISTERED = "telephony_registered"
        private const val KEY_FGS_TIMEOUT_AT = "fgs_timeout_at"
        private const val KEY_TELEPHONY_STATE = "telephony_state"
        private const val KEY_TELEPHONY_STATE_AT = "telephony_state_at"
        private const val KEY_OBSERVER_AT = "observer_at"
        private const val KEY_OBSERVER_COUNT = "observer_count"
        private const val KEY_LAST_SCAN_AT = "last_scan_at"
        private const val KEY_LAST_SCAN_REASON = "last_scan_reason"
        private const val KEY_LAST_SCAN_SUMMARY = "last_scan_summary"
        private const val KEY_DUPLICATE_COUNT = "duplicate_count"
        private const val KEY_LAST_ID = "last_processed_call_log_id"
        private const val KEY_LAST_CATCH_UP_AT = "last_catch_up_at"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_LAST_SYNC_RESULT = "last_sync_result"
        private const val KEY_SYNC_OK_COUNT = "sync_ok_count"
        private const val KEY_SYNC_FAIL_COUNT = "sync_fail_count"
        private const val KEY_CONSENT = "consent_acknowledged"

        private const val MAX_EVENTS = 1000
        private const val TRIM_EVERY = 50

        /**
         * Pure so it can be unit-tested. Exposed for the tests only; production
         * code goes through [event].
         */
        fun formatFields(vararg fields: Pair<String, Any?>): String =
            fields.asSequence()
                .filter { it.second != null }
                .joinToString(" ") { (k, v) ->
                    k + "=" + v.toString().trim().replace(Regex("\\s+"), "_")
                }
    }
}

data class DiagnosticsSnapshot(
    val monitoringActive: Boolean = false,
    val monitoringStartedAt: Long = 0L,
    val observerRegistered: Boolean = false,
    val telephonyRegistered: Boolean = false,
    val lastForegroundTimeoutAt: Long = 0L,
    val lastTelephonyState: String = "",
    val lastTelephonyStateAt: Long = 0L,
    val lastObserverEventAt: Long = 0L,
    val observerEventCount: Int = 0,
    val lastScanAt: Long = 0L,
    val lastScanReason: String = "",
    val lastScanSummary: String = "",
    val duplicateCount: Int = 0,
    val lastProcessedCallLogId: Long = 0L,
    val lastCatchUpRunAt: Long = 0L,
    val lastSyncAt: Long = 0L,
    val lastSyncResult: String = "",
    val syncSucceededCount: Int = 0,
    val syncFailedCount: Int = 0
)
