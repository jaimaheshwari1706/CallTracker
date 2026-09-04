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

        if (!DeviceInfoProvider.hasCallLogPermission(applicationContext)) {
            diagnostics.log(DiagnosticEvents.PERMISSION, "catch-up skipped: READ_CALL_LOG denied")
            return Result.success()
        }

        val result = CallCaptureEngine(applicationContext).scan(ScanReason.CATCH_UP)
        if (result.stored > 0) {
            CallSyncScheduler.requestSync(applicationContext)
        }

        // If the service is not running (OEM kill, FGS timeout), try to bring it
        // back. This runs while WorkManager holds a wakelock for us, which is a
        // context the platform permits an FGS start from. If the platform still
        // refuses, the service records that and this worker keeps carrying
        // capture on its own - we do not retry in a loop to force it.
        if (!diagnostics.monitoringActive) {
            runCatching { CallMonitorService.start(applicationContext) }
                .onFailure {
                    diagnostics.log(
                        DiagnosticEvents.ERROR,
                        "catch-up could not restart service: " + it.javaClass.simpleName
                    )
                }
        }

        return Result.success()
    }
}
