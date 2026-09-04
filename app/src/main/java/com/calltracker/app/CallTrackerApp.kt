package com.calltracker.app

import android.app.Application
import com.calltracker.app.diagnostics.DeviceInfoProvider
import com.calltracker.app.sync.CallSyncScheduler

class CallTrackerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // The periodic catch-up scan is the safety net behind the foreground
        // service. Arming it here means it survives the service being killed,
        // and it is a no-op (ExistingPeriodicWorkPolicy.KEEP) once scheduled.
        // It self-skips when READ_CALL_LOG has not been granted yet.
        if (DeviceInfoProvider.hasCallLogPermission(this)) {
            CallSyncScheduler.schedulePeriodicCatchUp(this)
        }
    }
}
