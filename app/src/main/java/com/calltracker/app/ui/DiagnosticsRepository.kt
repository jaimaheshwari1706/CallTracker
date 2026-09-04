package com.calltracker.app.ui

import android.content.Context
import com.calltracker.app.database.AppDatabase
import com.calltracker.app.database.CallEntity
import com.calltracker.app.database.DiagnosticEventEntity
import com.calltracker.app.database.ExcludedNumberEntity
import com.calltracker.app.diagnostics.DiagnosticsSnapshot
import com.calltracker.app.diagnostics.DiagnosticsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Everything the diagnostics screen renders, in one snapshot. */
data class DiagnosticsUiState(
    val runtime: DiagnosticsSnapshot = DiagnosticsSnapshot(),
    val capturedCount: Int = 0,
    val pendingCount: Int = 0,
    val syncedCount: Int = 0,
    val failedCount: Int = 0,
    val excludedCount: Int = 0,
    val lastCapturedAt: Long = 0L,
    val recentCalls: List<CallEntity> = emptyList(),
    val excludedNumbers: List<ExcludedNumberEntity> = emptyList(),
    val events: List<DiagnosticEventEntity> = emptyList()
)

/**
 * Joins the Room counters with the SharedPreferences runtime state. Kept out of
 * the composable so the screen stays a pure rendering of one value.
 */
class DiagnosticsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    private val store = DiagnosticsStore(appContext)

    private val counts: Flow<Counts> = combine(
        db.callDao().observeCapturedCount(),
        db.callDao().observePendingCount(),
        db.callDao().observeSyncedCount(),
        db.callDao().observeFailedCount(),
        db.callDao().observeExcludedCount()
    ) { captured, pending, synced, failed, excluded ->
        Counts(captured, pending, synced, failed, excluded)
    }

    private val details: Flow<Details> = combine(
        store.snapshots(),
        db.callDao().observeLastCapturedAt(),
        db.callDao().observeRecentCalls(RECENT_CALLS),
        db.excludedNumberDao().observeAll(),
        db.diagnosticEventDao().observeRecent(RECENT_EVENTS)
    ) { runtime, lastCaptured, calls, excludedNumbers, events ->
        Details(runtime, lastCaptured ?: 0L, calls, excludedNumbers, events)
    }

    val state: Flow<DiagnosticsUiState> = combine(counts, details) { c, d ->
        DiagnosticsUiState(
            runtime = d.runtime,
            capturedCount = c.captured,
            pendingCount = c.pending,
            syncedCount = c.synced,
            failedCount = c.failed,
            excludedCount = c.excluded,
            lastCapturedAt = d.lastCapturedAt,
            recentCalls = d.recentCalls,
            excludedNumbers = d.excludedNumbers,
            events = d.events
        )
    }

    private data class Counts(
        val captured: Int,
        val pending: Int,
        val synced: Int,
        val failed: Int,
        val excluded: Int
    )

    private data class Details(
        val runtime: DiagnosticsSnapshot,
        val lastCapturedAt: Long,
        val recentCalls: List<CallEntity>,
        val excludedNumbers: List<ExcludedNumberEntity>,
        val events: List<DiagnosticEventEntity>
    )

    companion object {
        private const val RECENT_CALLS = 25
        private const val RECENT_EVENTS = 200
    }
}
