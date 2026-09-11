package com.calltracker.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.calltracker.app.call.CallLogCatchUpWorker
import java.util.concurrent.TimeUnit

/**
 * All WorkManager scheduling in one place.
 *
 * Two jobs, doing different things:
 *
 *  - [requestSync] drains the local queue. Network-constrained, so with no
 *    connectivity it simply waits - the app keeps capturing regardless.
 *
 *  - [schedulePeriodicCatchUp] is the safety net for background capture. When an
 *    OEM kills the foreground service, or Android 15 ends the dataSync service
 *    at its time budget, no ContentObserver is registered and calls would be
 *    missed entirely. This periodic job re-scans the call log so those calls are
 *    still captured, just later. 15 minutes is WorkManager's floor for periodic
 *    work; capture latency after an OEM kill is therefore up to ~15 minutes,
 *    which is a limitation to record in the matrix, not a fix.
 */
object CallSyncScheduler {

    const val SYNC_WORK = "call_sync"
    const val CATCH_UP_WORK = "call_log_catch_up"

    fun requestSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<CallSyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(SYNC_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun schedulePeriodicCatchUp(context: Context) {
        val request = PeriodicWorkRequestBuilder<CallLogCatchUpWorker>(15, TimeUnit.MINUTES)
            // No constraints on purpose: capture must not depend on network,
            // charging or idle state.
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            CATCH_UP_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun requestImmediateCatchUp(context: Context) {
        val request = OneTimeWorkRequestBuilder<CallLogCatchUpWorker>().build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                CATCH_UP_WORK + "_now",
                ExistingWorkPolicy.REPLACE,
                request
            )
    }
}
