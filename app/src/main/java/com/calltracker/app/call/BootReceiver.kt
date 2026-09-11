package com.calltracker.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.calltracker.app.diagnostics.DeviceInfoProvider
import com.calltracker.app.diagnostics.DiagnosticEvents
import com.calltracker.app.diagnostics.DiagnosticsStore
import com.calltracker.app.sync.CallSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts monitoring after a reboot.
 *
 * Two things are deliberately not assumed here:
 *
 *  1. That starting a foreground service from BOOT_COMPLETED will succeed.
 *     Android 15 restricts which foreground-service types may be started from
 *     BOOT_COMPLETED, and dataSync is among the restricted ones. The start is
 *     therefore attempted inside runCatching and the failure is recorded rather
 *     than crashing the receiver.
 *
 *  2. That the service is the only way to recover. A catch-up work request is
 *     always enqueued, and the periodic catch-up is re-armed, so calls made
 *     before the app is next opened are still captured even when the boot-time
 *     service start is refused.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != QUICKBOOT_POWERON) return

        val appContext = context.applicationContext
        val diagnostics = DiagnosticsStore(appContext)

        // The service died with the reboot regardless of what the flag said.
        diagnostics.monitoringActive = false

        // Always re-arm the WorkManager safety net first: it is the part that
        // does not depend on a permission-sensitive foreground start.
        CallSyncScheduler.schedulePeriodicCatchUp(appContext)
        CallSyncScheduler.requestImmediateCatchUp(appContext)

        val hasPermissions = DeviceInfoProvider.hasCallLogPermission(appContext)
        val startError = if (hasPermissions) {
            runCatching { CallMonitorService.start(appContext) }
                .exceptionOrNull()?.javaClass?.simpleName
        } else {
            "permissions not granted"
        }

        // goAsync() is not used: this is a single short insert and the receiver
        // may be torn down first. Losing one diagnostic line after a reboot is
        // acceptable; blocking the boot broadcast is not.
        CoroutineScope(Dispatchers.IO).launch {
            diagnostics.event(
                DiagnosticEvents.BOOT_RECEIVED,
                "action" to action,
                "catchUp" to "scheduled",
                "serviceStart" to (startError ?: "requested")
            )
        }
    }

    private companion object {
        const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
