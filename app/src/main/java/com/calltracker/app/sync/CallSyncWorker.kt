package com.calltracker.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.calltracker.app.database.AppDatabase
import com.calltracker.app.diagnostics.DiagnosticEvents
import com.calltracker.app.diagnostics.DiagnosticsStore

/**
 * Drains the local PENDING queue through [CallSyncClient].
 *
 * There is no backend. The client is [LocalLoopbackSyncClient], so this worker
 * exercises the queue -> attempt -> state-transition path end to end while the
 * device is offline. That is the point: the POC must be validatable without a
 * server, and the app must behave correctly with no network at all.
 */
class CallSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    // Swap for a RetrofitCallSyncClient when a backend exists. Nothing else
    // in the app needs to change.
    private val client: CallSyncClient = LocalLoopbackSyncClient()

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val diagnostics = DiagnosticsStore(applicationContext)
        val dao = db.callDao()

        val pending = dao.getPendingBatch(limit = BATCH_SIZE)
        if (pending.isEmpty()) {
            diagnostics.recordSync("nothing pending")
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val outcome = try {
            client.sync(deviceId(), pending.map { it.toSyncPayload() })
        } catch (e: Exception) {
            SyncOutcome.TRANSIENT_FAILURE
        }

        // All calls in a batch share an attempt count closely enough for the POC;
        // the transition itself is decided per stored attempt count.
        val worstAttempts = pending.maxOf { it.syncAttempts }
        val nextStatus = SyncStateMachine.next(
            current = pending.first().syncStatus,
            outcome = outcome,
            attemptsSoFar = worstAttempts
        )

        dao.applySyncResult(pending.map { it.id }, nextStatus, now)

        diagnostics.recordSync("$outcome -> $nextStatus (${pending.size} calls)", now)
        diagnostics.log(
            DiagnosticEvents.SYNC,
            "batch=" + pending.size + " outcome=" + outcome + " status=" + nextStatus
        )

        return if (SyncStateMachine.shouldRetry(nextStatus)) Result.retry() else Result.success()
    }

    /**
     * Placeholder device identity. A real deployment registers the device with
     * the backend and stores the assigned id; there is no backend here, so this
     * is only shaped like the eventual field.
     */
    private fun deviceId(): String = "poc-device"

    companion object {
        private const val BATCH_SIZE = 50
    }
}
