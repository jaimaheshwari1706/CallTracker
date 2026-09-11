package com.calltracker.app.call

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.calltracker.app.diagnostics.DeviceInfoProvider
import com.calltracker.app.diagnostics.DiagnosticEvents
import com.calltracker.app.diagnostics.DiagnosticsStore
import com.calltracker.app.sync.CallSyncScheduler

/**
 * Re-scans the call log without a watermark.
 *
 * This is what makes background capture testable rather than hopeful. The
 * foreground service plus ContentObserver is the fast path; this worker is the
 * path that still works when:
 *
 *  - an OEM battery manager killed the process,
 *  - Android 15 ended the dataSync foreground service at its time budget,
 *  - the app was swiped out of recents,
 *  - a reboot happened and the foreground service could not be started.
 *
 * It relies entirely on the same idempotency guarantees as the observer path,
 * so running it repeatedly cannot create duplicates.
 */
class CallLogCatchUpWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val diagnostics = DiagnosticsStore(applicationContext)
        diagnostics.recordCatchUpRun()
        diagnostics.event(
            DiagnosticEvents.WORKER_STARTED,
            "worker" to DiagnosticEvents.WORKER_CATCH_UP, "attempt" to runAttemptCount
        )

        if (!DeviceInfoProvider.hasCallLogPermission(applicationContext)) {
            diagnostics.event(
                DiagnosticEvents.WORKER_FINISHED,
                "worker" to DiagnosticEvents.WORKER_CATCH_UP,
                "result" to "SKIPPED", "reason" to "READ_CALL_LOG denied"
            )
            return Result.success()
        }

        val result = CallCaptureEngine(applicationContext).scan(ScanReason.CATCH_UP)
        if (result.stored > 0) {
            CallSyncScheduler.requestSync(applicationContext)
            diagnostics.event(
                DiagnosticEvents.CALL_SYNC_QUEUED,
                "reason" to ScanReason.CATCH_UP, "stored" to result.stored
            )
        }

        // If the service is not running (OEM kill, FGS timeout), try to bring it
        // back. This runs while WorkManager holds a wakelock for us, which is a
        // context the platform permits an FGS start from. If the platform still
        // refuses, the service records that and this worker keeps carrying
        // capture on its own - we do not retry in a loop to force it.
        var serviceRestart = "not needed"
        if (!diagnostics.monitoringActive) {
            serviceRestart = runCatching { CallMonitorService.start(applicationContext) }
                .fold(
                    onSuccess = { "requested" },
                    onFailure = { e ->
                        diagnostics.error("CallLogCatchUpWorker.restartService", e)
                        "refused:" + e.javaClass.simpleName
                    }
                )
        }

        diagnostics.event(
            DiagnosticEvents.WORKER_FINISHED,
            "worker" to DiagnosticEvents.WORKER_CATCH_UP,
            "result" to (result.error ?: "OK"),
            "rows" to result.rowsExamined,
            "stored" to result.stored,
            "excluded" to result.excluded,
            "duplicates" to result.duplicates,
            "serviceRestart" to serviceRestart
        )
        return Result.success()
    }
}
