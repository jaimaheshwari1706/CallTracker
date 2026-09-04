package com.calltracker.app.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.calltracker.app.MainActivity
import com.calltracker.app.diagnostics.DeviceInfoProvider
import com.calltracker.app.diagnostics.DiagnosticEvents
import com.calltracker.app.diagnostics.DiagnosticsStore
import com.calltracker.app.sync.CallSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Two independent signals feed this service, deliberately not just one:
 *
 *  1. TelephonyCallback.CallStateListener - tells us the phone's call state
 *     changed. Fast, but the call-log row is usually NOT written yet at the
 *     instant CALL_STATE_IDLE fires. This signal is never treated as proof that
 *     a new call-log row exists; it only asks the engine to look sooner.
 *
 *  2. ContentObserver on CallLog.Calls.CONTENT_URI - tells us the call-log
 *     provider itself changed. This is the source of truth trigger.
 *
 * Everything captured goes through PrivacyFilter before persistence and
 * de-dupes against CallLog._ID. See CallCaptureEngine.
 */
class CallMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var diagnostics: DiagnosticsStore
    private lateinit var engine: CallCaptureEngine

    private var contentObserver: ContentObserver? = null
    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: Any? = null // TelephonyCallback (31+) or PhoneStateListener

    /**
     * Scan requests are funnelled through a CONFLATED channel consumed by a
     * single coroutine. The call log notifies several times per call; without
     * conflation we would spin up a coroutine per notification and hammer the
     * provider. With it, a burst collapses into one scan, and a request that
     * arrives mid-scan queues exactly one follow-up.
     */
    private val scanRequests = Channel<ScanReason>(Channel.CONFLATED)

    override fun onCreate() {
        super.onCreate()
        diagnostics = DiagnosticsStore(this)
        engine = CallCaptureEngine(this)

        if (!startForegroundSafely()) return

        diagnostics.monitoringActive = true
        diagnostics.monitoringStartedAt = System.currentTimeMillis()

        startScanConsumer()
        registerCallStateWatcher()
        registerContentObserver()

        serviceScope.launch {
            diagnostics.log(DiagnosticEvents.SERVICE_STARTED, "monitoring started")
            logPermissionState()
            // Covers whatever happened while the process was not running:
            // OEM kill, reboot, force stop, first install.
            engine.scan(ScanReason.CATCH_UP)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESCAN) {
            scanRequests.trySend(ScanReason.MANUAL)
        }
        // START_STICKY asks the OS to restart this service if it is killed.
        // Necessary on aggressive OEMs but NOT sufficient on its own - the
        // battery-optimization exemption and the periodic WorkManager catch-up
        // matter more. See README, background behaviour.
        return START_STICKY
    }

    // --- foreground service ---

    private fun startForegroundSafely(): Boolean {
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
            )
            true
        } catch (e: Exception) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException when a
            // background start is not permitted, and Android 14+ can reject a
            // type/permission mismatch. Both are legitimate OS decisions. We
            // record them and let the periodic WorkManager catch-up carry the
            // capture instead of trying to defeat the restriction.
            serviceScope.launch {
                diagnostics.log(
                    DiagnosticEvents.ERROR,
                    "startForeground refused: " + e.javaClass.simpleName
                )
            }
            diagnostics.monitoringActive = false
            stopSelf()
            false
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "call_tracking"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Call tracking", NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Shown while the POC is watching the call log."
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Call tracking active")
            .setContentText("Watching the call log. Audio is never recorded.")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    /**
     * Android 15 caps a dataSync foreground service at roughly 6 hours per 24h.
     * When the budget runs out the system calls this and the service must stop.
     *
     * This is a real, documented limitation of the current design and one of the
     * things the OEM matrix must record. We log it, stop cleanly, and rely on
     * the periodic catch-up worker until the app is next opened.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        diagnostics.lastForegroundTimeoutAt = System.currentTimeMillis()
        serviceScope.launch {
            diagnostics.log(
                DiagnosticEvents.SERVICE_TIMEOUT,
                "Android 15 dataSync FGS time limit reached; falling back to periodic catch-up"
            )
        }
        stopSelf()
    }

    // --- signal 1: phone state ---

    private fun registerCallStateWatcher() {
        if (!DeviceInfoProvider.hasPhoneStatePermission(this)) {
            serviceScope.launch {
                diagnostics.log(
                    DiagnosticEvents.PERMISSION,
                    "READ_PHONE_STATE denied - telephony signal unavailable"
                )
            }
            return
        }

        val tm = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager ?: return
        telephonyManager = tm

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : android.telephony.TelephonyCallback(),
                    android.telephony.TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onTelephonyState(state)
                }
                telephonyCallback = callback
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(this), callback)
            } else {
                // Documented compatibility reason: TelephonyCallback does not exist
                // before API 31 and minSdk is 26. This branch is never taken on
                // Android 12+, which is the whole target range of the OEM matrix.
                @Suppress("DEPRECATION")
                val listener = object : android.telephony.PhoneStateListener() {
                    @Suppress("DEPRECATION")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) =
                        onTelephonyState(state)
                }
                telephonyCallback = listener
                @Suppress("DEPRECATION")
                tm.listen(listener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (e: SecurityException) {
            serviceScope.launch {
                diagnostics.log(DiagnosticEvents.ERROR, "telephony listen refused: " + e.javaClass.simpleName)
            }
        }
    }

    private fun onTelephonyState(state: Int) {
        val label = when (state) {
            TelephonyManager.CALL_STATE_IDLE -> "IDLE"
            TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            else -> "UNKNOWN($state)"
        }
        diagnostics.recordTelephonyState(label)
        serviceScope.launch { diagnostics.log(DiagnosticEvents.TELEPHONY, label) }

        // OFFHOOK means a call is in progress - there is nothing in the call log
        // to read yet, so it is recorded for the matrix and nothing more.
        // IDLE means a call just ended, which is a good moment to look. It is
        // still only a nudge: the call-log row may not exist yet, so the scan is
        // delayed and the ContentObserver remains the authoritative trigger.
        if (state == TelephonyManager.CALL_STATE_IDLE) {
            serviceScope.launch {
                delay(IDLE_SETTLE_MS)
                scanRequests.trySend(ScanReason.CALL_STATE_IDLE)
            }
        }
    }

    // --- signal 2: the call_log content provider itself ---

    private fun registerContentObserver() {
        if (!DeviceInfoProvider.hasCallLogPermission(this)) {
            serviceScope.launch {
                diagnostics.log(
                    DiagnosticEvents.PERMISSION,
                    "READ_CALL_LOG denied - observer not registered"
                )
            }
            return
        }

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                diagnostics.recordObserverEvent()
                serviceScope.launch {
                    diagnostics.log(DiagnosticEvents.CALL_LOG_CHANGED, "selfChange=" + selfChange)
                }
                scanRequests.trySend(ScanReason.CALL_LOG_CHANGED)
            }
        }
        contentObserver = observer
        try {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
        } catch (e: SecurityException) {
            contentObserver = null
            serviceScope.launch {
                diagnostics.log(DiagnosticEvents.ERROR, "observer registration refused")
            }
        }
    }

    // --- serialized scan consumer ---

    private fun startScanConsumer() {
        serviceScope.launch {
            for (reason in scanRequests) {
                val result = engine.scan(reason)
                if (result.stored > 0) {
                    CallSyncScheduler.requestSync(applicationContext)
                }
            }
        }
    }

    private suspend fun logPermissionState() {
        diagnostics.log(
            DiagnosticEvents.PERMISSION,
            "callLog=" + DeviceInfoProvider.hasCallLogPermission(this).grantLabel() +
                " phoneState=" + DeviceInfoProvider.hasPhoneStatePermission(this).grantLabel() +
                " contacts=" + DeviceInfoProvider.hasContactsPermission(this).grantLabel()
        )
    }

    private fun Boolean.grantLabel() = if (this) "GRANTED" else "DENIED"

    override fun onDestroy() {
        super.onDestroy()
        contentObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        unregisterTelephony()
        diagnostics.monitoringActive = false
        // Fire-and-forget on a scope that outlives serviceScope.cancel().
        CoroutineScope(Dispatchers.IO).launch {
            DiagnosticsStore(applicationContext)
                .log(DiagnosticEvents.SERVICE_STOPPED, "monitoring stopped")
        }
        scanRequests.close()
        serviceScope.cancel()
    }

    private fun unregisterTelephony() {
        val tm = telephonyManager ?: return
        val cb = telephonyCallback ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                cb is android.telephony.TelephonyCallback
            ) {
                tm.unregisterTelephonyCallback(cb)
            } else if (cb is android.telephony.PhoneStateListener) {
                @Suppress("DEPRECATION")
                tm.listen(cb, android.telephony.PhoneStateListener.LISTEN_NONE)
            }
        }
        telephonyCallback = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1001

        /**
         * How long to wait after CALL_STATE_IDLE before looking at the call log.
         * The provider write is asynchronous and the delay is a heuristic, which
         * is exactly why this path is a nudge and the ContentObserver is the
         * trigger we rely on.
         */
        private const val IDLE_SETTLE_MS = 1500L

        const val ACTION_RESCAN = "com.calltracker.app.action.RESCAN"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, CallMonitorService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallMonitorService::class.java))
        }

        fun requestRescan(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallMonitorService::class.java).setAction(ACTION_RESCAN)
            )
        }
    }
}
