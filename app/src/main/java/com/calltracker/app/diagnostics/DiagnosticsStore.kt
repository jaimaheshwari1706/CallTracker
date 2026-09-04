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
 * Scalar runtime state for the diagnostics screen (counts come from Room).
 *
 * SharedPreferences rather than Room because these values are written from the
 * service on every ContentObserver tick and read by the UI; a tiny key/value
 * store that survives process death is exactly the right shape, and it keeps
 * "was monitoring running when the OEM killed us?" answerable after a restart.
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

    /** Set when Android 15+ ends a dataSync foreground service at its 6h budget. */
    var lastForegroundTimeoutAt: Long
        get() = prefs.getLong(KEY_FGS_TIMEOUT_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_FGS_TIMEOUT_AT, value).apply()

    // --- signal 1: telephony ---

    // Read-only outside this class: both values are written together by
    // recordTelephonyState so the state and its timestamp can never disagree.
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

    // --- scan watermark ---

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

    // --- sync ---

    val lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC_AT, 0L)

    val lastSyncResult: String
        get() = prefs.getString(KEY_LAST_SYNC_RESULT, "") ?: ""

    fun recordSync(result: String, atEpochMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_LAST_SYNC_RESULT, result)
            .putLong(KEY_LAST_SYNC_AT, atEpochMs)
            .apply()
    }

    // --- consent (so the explanation screen is shown once, not every launch) ---

    var consentAcknowledged: Boolean
        get() = prefs.getBoolean(KEY_CONSENT, false)
        set(value) = prefs.edit().putBoolean(KEY_CONSENT, value).apply()

    // --- event log ---

    /**
     * Appends one line to the on-device log. [detail] must never contain a full
     * phone number — use maskForDiagnostics().
     */
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
        lastForegroundTimeoutAt = lastForegroundTimeoutAt,
        lastTelephonyState = lastTelephonyState,
        lastTelephonyStateAt = lastTelephonyStateAt,
        lastObserverEventAt = lastObserverEventAt,
        observerEventCount = observerEventCount,
        lastProcessedCallLogId = lastProcessedCallLogId,
        lastSyncAt = lastSyncAt,
        lastSyncResult = lastSyncResult
    )

    companion object {
        private const val PREFS_NAME = "call_tracker_diagnostics"
        private const val KEY_MONITORING_ACTIVE = "monitoring_active"
        private const val KEY_MONITORING_STARTED_AT = "monitoring_started_at"
        private const val KEY_FGS_TIMEOUT_AT = "fgs_timeout_at"
        private const val KEY_TELEPHONY_STATE = "telephony_state"
        private const val KEY_TELEPHONY_STATE_AT = "telephony_state_at"
        private const val KEY_OBSERVER_AT = "observer_at"
        private const val KEY_OBSERVER_COUNT = "observer_count"
        private const val KEY_LAST_ID = "last_processed_call_log_id"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_LAST_SYNC_RESULT = "last_sync_result"
        private const val KEY_CONSENT = "consent_acknowledged"

        private const val MAX_EVENTS = 1000
        private const val TRIM_EVERY = 50
    }
}

data class DiagnosticsSnapshot(
    val monitoringActive: Boolean = false,
    val monitoringStartedAt: Long = 0L,
    val lastForegroundTimeoutAt: Long = 0L,
    val lastTelephonyState: String = "",
    val lastTelephonyStateAt: Long = 0L,
    val lastObserverEventAt: Long = 0L,
    val observerEventCount: Int = 0,
    val lastProcessedCallLogId: Long = 0L,
    val lastSyncAt: Long = 0L,
    val lastSyncResult: String = ""
)
