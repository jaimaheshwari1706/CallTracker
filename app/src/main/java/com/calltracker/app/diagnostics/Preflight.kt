package com.calltracker.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.calltracker.app.call.CallMonitorService
import com.calltracker.app.call.SimResolver
import com.calltracker.app.database.AppDatabase
import com.calltracker.app.sync.CallSyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PreflightStatus { PASS, WARNING, FAIL }

/**
 * What a tester can do about a non-PASS check. Every action navigates to a
 * system screen or triggers an existing app flow; none of them request a
 * permission silently — the diagnostics screen only ever *points* at the
 * permission flow, it never launches a permission dialog on its own.
 */
enum class PreflightAction {
    OPEN_APP_SETTINGS,
    OPEN_NOTIFICATION_SETTINGS,
    OPEN_BATTERY_SETTINGS,
    START_MONITORING,
    ADD_EXCLUDED_NUMBER
}

data class PreflightCheck(
    val name: String,
    val status: PreflightStatus,
    /** One line: what was observed. */
    val detail: String,
    /** Why it matters / what to do. Shown for WARNING and FAIL. */
    val why: String? = null,
    val action: PreflightAction? = null
)

/**
 * The pre-flight gate for a physical-device session.
 *
 * Each check reports what is observable from inside the app right now. A full
 * set of PASSes means the pipeline is *wired*; it does not mean background
 * capture will survive this OEM's battery manager — nothing observable from
 * inside the process can prove that. That is precisely why the battery and OEM
 * rows are WARNINGs with "must be validated" wording rather than PASS/FAIL.
 */
object PreflightChecker {

    suspend fun run(context: Context): List<PreflightCheck> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val store = DiagnosticsStore(app)
        val device = DeviceInfoProvider.collect(app)
        val checks = ArrayList<PreflightCheck>()

        // --- permissions ---

        checks += permissionCheck(
            name = "READ_CALL_LOG",
            granted = DeviceInfoProvider.hasCallLogPermission(app),
            required = true,
            why = "Required. Without it the call log cannot be queried and the " +
                "ContentObserver cannot be registered — nothing is captured."
        )
        checks += permissionCheck(
            name = "READ_PHONE_STATE",
            granted = DeviceInfoProvider.hasPhoneStatePermission(app),
            required = true,
            why = "Required. Provides the TelephonyCallback nudge and the SIM " +
                "subscription lookup."
        )
        checks += permissionCheck(
            name = "READ_CONTACTS (optional)",
            granted = DeviceInfoProvider.hasContactsPermission(app),
            required = false,
            why = "Optional. Only used to show a contact name next to a captured call " +
                "on this screen. Capture is unaffected when denied."
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = DeviceInfoProvider.hasNotificationPermission(app)
            checks += PreflightCheck(
                name = "POST_NOTIFICATIONS",
                status = if (granted) PreflightStatus.PASS else PreflightStatus.WARNING,
                detail = if (granted) "granted" else "denied",
                why = if (granted) null else
                    "The foreground service still runs, but its notification is hidden, " +
                        "so you cannot see from the shade whether monitoring is alive.",
                action = if (granted) null else PreflightAction.OPEN_NOTIFICATION_SETTINGS
            )
        }

        // --- monitoring service ---

        val serviceRunning = isServiceRunning(app)
        val flagActive = store.monitoringActive
        checks += when {
            serviceRunning && flagActive -> PreflightCheck(
                "Monitoring service", PreflightStatus.PASS,
                "running since " + Format.stamp(store.monitoringStartedAt)
            )
            serviceRunning && !flagActive -> PreflightCheck(
                "Monitoring service", PreflightStatus.WARNING,
                "process reports running but monitoringActive=false",
                "The service may have failed startForeground(). Check the event log for ERROR where=startForeground."
            )
            !serviceRunning && flagActive -> PreflightCheck(
                "Monitoring service", PreflightStatus.FAIL,
                "flag says ACTIVE but no service is running",
                "The process was killed without onDestroy() — typically an OEM battery manager or a force stop. " +
                    "This is exactly the finding the OEM matrix wants; record it, then restart.",
                PreflightAction.START_MONITORING
            )
            else -> PreflightCheck(
                "Monitoring service", PreflightStatus.FAIL,
                "not running",
                "Nothing is watching the call log. Start monitoring.",
                PreflightAction.START_MONITORING
            )
        }

        checks += registrationCheck(
            "ContentObserver", serviceRunning, store.observerRegistered,
            "The source-of-truth trigger. Without it a call is only found by the " +
                "15-minute catch-up worker."
        )
        checks += registrationCheck(
            "TelephonyCallback", serviceRunning, store.telephonyRegistered,
            "The early-nudge signal. Capture still works without it (the observer " +
                "is authoritative) but the event log will lack OFFHOOK/IDLE lines."
        )

        // --- storage ---

        checks += runCatching {
            val db = AppDatabase.get(app)
            val open = db.openHelper.readableDatabase.isOpen
            val version = db.openHelper.readableDatabase.version
            if (open) {
                PreflightCheck("Room database", PreflightStatus.PASS, "open, schema v$version")
            } else {
                PreflightCheck("Room database", PreflightStatus.FAIL, "not open", "Calls cannot be persisted.")
            }
        }.getOrElse { e ->
            PreflightCheck(
                "Room database", PreflightStatus.FAIL,
                e.javaClass.simpleName + ": " + (e.message ?: ""),
                "The database failed to open. Clearing app data will recreate it (and lose captured POC data)."
            )
        }

        // --- WorkManager ---

        checks += runCatching {
            val infos = WorkManager.getInstance(app)
                .getWorkInfosForUniqueWork(CallSyncScheduler.CATCH_UP_WORK)
                .get()
            val live = infos.firstOrNull { !it.state.isFinished }
            when {
                live == null -> PreflightCheck(
                    "Catch-up worker", PreflightStatus.WARNING,
                    "not scheduled",
                    "Without the periodic catch-up, a call made while the service is dead is never recovered. " +
                        "It is scheduled when monitoring starts.",
                    PreflightAction.START_MONITORING
                )
                live.state == WorkInfo.State.ENQUEUED || live.state == WorkInfo.State.RUNNING ->
                    PreflightCheck(
                        "Catch-up worker", PreflightStatus.PASS,
                        live.state.name + ", last run " + Format.stamp(store.lastCatchUpRunAt)
                    )
                else -> PreflightCheck(
                    "Catch-up worker", PreflightStatus.WARNING,
                    live.state.name,
                    "Unexpected WorkManager state; check the event log for WORKER_* lines."
                )
            }
        }.getOrElse { e ->
            PreflightCheck(
                "Catch-up worker", PreflightStatus.FAIL,
                e.javaClass.simpleName, "WorkManager could not be queried."
            )
        }

        // --- privacy ---

        checks += runCatching {
            val count = AppDatabase.get(app).excludedNumberDao().getAllExcluded().size
            if (count > 0) {
                PreflightCheck("Exclusion list", PreflightStatus.PASS, "$count number(s) excluded")
            } else {
                PreflightCheck(
                    "Exclusion list", PreflightStatus.WARNING,
                    "empty",
                    "The privacy filter cannot be exercised until at least one number is excluded. " +
                        "Add the second test phone before Phase 6.",
                    PreflightAction.ADD_EXCLUDED_NUMBER
                )
            }
        }.getOrElse { e ->
            PreflightCheck("Exclusion list", PreflightStatus.FAIL, e.javaClass.simpleName)
        }

        // --- device ---

        checks += PreflightCheck("Device", PreflightStatus.PASS, device.deviceLabel)
        checks += PreflightCheck(
            "Android", PreflightStatus.PASS, device.androidLabel,
            why = if (device.sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
                "Android 15+: the dataSync foreground service is time-limited (~6h/24h) and " +
                    "cannot be started from BOOT_COMPLETED. Expect SERVICE_TIMEOUT lines in a long session."
            else null
        )
        checks += PreflightCheck("App version", PreflightStatus.PASS,
            device.appVersionName + " (" + device.appVersionCode + ")")

        checks += if (device.backgroundRestricted) {
            PreflightCheck(
                "Background restriction", PreflightStatus.FAIL,
                "RESTRICTED by user",
                "Android will not let the foreground service run. Set app battery usage to Unrestricted/Optimized.",
                PreflightAction.OPEN_APP_SETTINGS
            )
        } else {
            PreflightCheck("Background restriction", PreflightStatus.PASS, "not restricted")
        }

        checks += if (device.ignoringBatteryOptimizations) {
            PreflightCheck(
                "Battery optimization", PreflightStatus.PASS,
                "exempted",
                "Exemption is granted. This still does not guarantee background reliability on this OEM — validate it."
            )
        } else {
            PreflightCheck(
                "Battery optimization", PreflightStatus.WARNING,
                "enabled (not exempted)",
                "Background reliability must be validated. Run the background phases once like this, " +
                    "then grant the exemption and run them again, and record both.",
                PreflightAction.OPEN_BATTERY_SETTINGS
            )
        }

        device.oemBackgroundNote?.let { note ->
            checks += PreflightCheck(
                "OEM background policy", PreflightStatus.WARNING,
                "manual opt-in may be required",
                note + " The app does not attempt to bypass this; grant it by hand and note it in the matrix."
            )
        }

        // --- SIM ---

        val simCount = device.activeSimCount
        checks += when {
            !DeviceInfoProvider.hasPhoneStatePermission(app) -> PreflightCheck(
                "SIM / subscriptions", PreflightStatus.WARNING,
                "unavailable (READ_PHONE_STATE denied)",
                "SIM slot resolution needs READ_PHONE_STATE."
            )
            simCount == null -> PreflightCheck(
                "SIM / subscriptions", PreflightStatus.WARNING,
                "platform would not report", "SimResolver will record UNAVAILABLE for every call."
            )
            simCount == 0 -> PreflightCheck(
                "SIM / subscriptions", PreflightStatus.WARNING,
                "no active SIM", "Real calls are not possible on this device as configured."
            )
            simCount == 1 -> PreflightCheck(
                "SIM / subscriptions", PreflightStatus.PASS,
                "1 active SIM — slot is unambiguous",
                "Dual-SIM mapping cannot be tested on this device."
            )
            else -> PreflightCheck(
                "SIM / subscriptions", PreflightStatus.WARNING,
                "$simCount active SIMs",
                "Dual-SIM. PHONE_ACCOUNT_ID mapping is unverified on this OEM; watch the SIM_RESOLUTION lines " +
                    "and record whether result=RESOLVED or UNRESOLVED per call."
            )
        }

        checks
    }

    private fun permissionCheck(name: String, granted: Boolean, required: Boolean, why: String) =
        PreflightCheck(
            name = name,
            status = when {
                granted -> PreflightStatus.PASS
                required -> PreflightStatus.FAIL
                else -> PreflightStatus.WARNING
            },
            detail = if (granted) "granted" else "denied",
            why = if (granted) null else why,
            action = if (granted) null else PreflightAction.OPEN_APP_SETTINGS
        )

    private fun registrationCheck(
        name: String,
        serviceRunning: Boolean,
        flag: Boolean,
        why: String
    ) = when {
        serviceRunning && flag -> PreflightCheck(name, PreflightStatus.PASS, "registered")
        serviceRunning && !flag -> PreflightCheck(
            name, PreflightStatus.FAIL, "service running but not registered",
            why + " Check the event log for ERROR where=register*.", PreflightAction.OPEN_APP_SETTINGS
        )
        else -> PreflightCheck(
            name, PreflightStatus.FAIL, "not registered (service not running)",
            why, PreflightAction.START_MONITORING
        )
    }

    /**
     * getRunningServices() is deprecated, but since Android O it returns only
     * the caller's own services — which is exactly the one thing we want to
     * know. It is the only supported way to ask "is my service alive?" without
     * binding to it.
     */
    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context): Boolean = runCatching {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val target = CallMonitorService::class.java.name
        am.getRunningServices(Int.MAX_VALUE).any { it.service.className == target }
    }.getOrDefault(false)

    /** Overall gate: any FAIL means "do not start the test calls yet". */
    fun overall(checks: List<PreflightCheck>): PreflightStatus = when {
        checks.any { it.status == PreflightStatus.FAIL } -> PreflightStatus.FAIL
        checks.any { it.status == PreflightStatus.WARNING } -> PreflightStatus.WARNING
        else -> PreflightStatus.PASS
    }
}

/** Shared time formatting so the pre-flight and the screen agree. */
object Format {
    private val stampFormat = java.text.SimpleDateFormat("dd MMM HH:mm:ss", java.util.Locale.US)
    private val clockFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    fun stamp(epochMs: Long): String =
        if (epochMs <= 0L) "-" else synchronized(stampFormat) { stampFormat.format(java.util.Date(epochMs)) }

    fun clock(epochMs: Long): String =
        if (epochMs <= 0L) "--:--:--" else synchronized(clockFormat) { clockFormat.format(java.util.Date(epochMs)) }
}
