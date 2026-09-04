package com.calltracker.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.calltracker.app.call.CallMonitorService
import com.calltracker.app.call.UNKNOWN_NUMBER
import com.calltracker.app.call.normalizePhoneNumber
import com.calltracker.app.database.AppDatabase
import com.calltracker.app.database.ExcludedNumberEntity
import com.calltracker.app.diagnostics.DeviceInfoProvider
import com.calltracker.app.diagnostics.DiagnosticsStore
import com.calltracker.app.sync.CallSyncScheduler
import com.calltracker.app.ui.ConsentScreen
import com.calltracker.app.ui.DiagnosticsRepository
import com.calltracker.app.ui.DiagnosticsScreen
import com.calltracker.app.ui.DiagnosticsUiState
import com.calltracker.app.ui.PermissionRequestScreen
import com.calltracker.app.ui.PermissionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Screen order is deliberate: privacy explanation -> permission rationale ->
 * permission request -> diagnostics.
 *
 * The diagnostics screen is the actual product of this POC. Everything the OEM
 * test matrix needs is on it, so a tester can fill in a row without adb.
 */
class MainActivity : ComponentActivity() {

    /**
     * Capture cannot work without these two, so they are requested together and
     * the app says so plainly.
     */
    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE
    )

    /**
     * Genuinely optional. READ_CONTACTS only puts a display name next to a
     * captured call on this screen; POST_NOTIFICATIONS only makes the
     * foreground-service notification visible on Android 13+. Denying either
     * changes nothing about capture.
     */
    private val optionalPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.READ_CONTACTS)
        }

    private lateinit var diagnostics: DiagnosticsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnostics = DiagnosticsStore(this)
        val repository = DiagnosticsRepository(this)

        setContent {
            MaterialTheme {
                var consented by remember { mutableStateOf(diagnostics.consentAcknowledged) }
                var optionalSkipped by remember { mutableStateOf(false) }

                // Permission results can also change outside the app (Settings,
                // or a revoke while backgrounded), so they are re-read on resume
                // rather than only after a request dialog.
                var permissionTick by remember { mutableStateOf(0) }
                ObserveResume { permissionTick++ }

                val callLogGranted = remember(permissionTick) {
                    DeviceInfoProvider.hasCallLogPermission(this)
                }
                val phoneStateGranted = remember(permissionTick) {
                    DeviceInfoProvider.hasPhoneStatePermission(this)
                }
                val contactsGranted = remember(permissionTick) {
                    DeviceInfoProvider.hasContactsPermission(this)
                }
                val device = remember(permissionTick) { DeviceInfoProvider.collect(this) }

                val requiredLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { _ ->
                    permissionTick++
                    if (DeviceInfoProvider.hasCallLogPermission(this)) startMonitoring()
                }

                // A denied optional permission must not trap the tester on this
                // screen, so answering the dialog at all counts as handled.
                val optionalLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { _ ->
                    permissionTick++
                    optionalSkipped = true
                }

                val state: DiagnosticsUiState by repository.state
                    .collectAsState(initial = DiagnosticsUiState())

                Surface(modifier = Modifier.fillMaxSize()) {
                    when {
                        !consented -> ConsentScreen(onAccept = {
                            diagnostics.consentAcknowledged = true
                            consented = true
                        })

                        !callLogGranted || !phoneStateGranted ||
                            (!contactsGranted && !optionalSkipped) ->
                            PermissionRequestScreen(
                                permissions = permissionRows(
                                    callLogGranted, phoneStateGranted, contactsGranted
                                ),
                                onRequestRequired = { requiredLauncher.launch(requiredPermissions) },
                                onRequestOptional = { optionalLauncher.launch(optionalPermissions) },
                                onOpenSettings = ::openAppSettings,
                                onSkipOptional = { optionalSkipped = true }
                            )

                        else -> DiagnosticsScreen(
                            state = state,
                            device = device,
                            callLogGranted = callLogGranted,
                            phoneStateGranted = phoneStateGranted,
                            contactsGranted = contactsGranted,
                            onStartMonitoring = ::startMonitoring,
                            onStopMonitoring = { CallMonitorService.stop(this) },
                            onRescan = { CallMonitorService.requestRescan(this) },
                            onSyncNow = { CallSyncScheduler.requestSync(this) },
                            onRetryFailed = ::retryFailed,
                            onBatterySettings = ::requestBatteryOptimizationExemption,
                            onClearLog = ::clearLog,
                            onAddExcludedNumber = ::addExcludedNumber,
                            onRemoveExcludedNumber = ::removeExcludedNumber
                        )
                    }
                }
            }
        }

        // If permissions were already granted from a previous run, resume
        // monitoring without waiting for the user to press anything.
        if (DeviceInfoProvider.hasCallLogPermission(this) && diagnostics.consentAcknowledged) {
            startMonitoring()
        }
    }

    private fun permissionRows(
        callLog: Boolean,
        phoneState: Boolean,
        contacts: Boolean
    ) = listOf(
        PermissionRow(
            name = Manifest.permission.READ_CALL_LOG,
            title = "Call log",
            why = "Reads the completed-call rows this POC is testing, and lets the app " +
                "watch CallLog.Calls.CONTENT_URI for changes. Without it there is " +
                "nothing to capture.",
            required = true,
            granted = callLog
        ),
        PermissionRow(
            name = Manifest.permission.READ_PHONE_STATE,
            title = "Phone state",
            why = "Receives call-state changes (ringing, in-call, ended) so the app knows " +
                "when to look at the call log, and asks the system which SIM was used.",
            required = true,
            granted = phoneState
        ),
        PermissionRow(
            name = Manifest.permission.READ_CONTACTS,
            title = "Contacts (optional)",
            why = "Only used to show a saved contact name next to a captured call on this " +
                "device, so test calls are easier to identify. The name is never uploaded. " +
                "Capture works exactly the same if you deny this.",
            required = false,
            granted = contacts
        )
    )

    private fun startMonitoring() {
        CallMonitorService.start(this)
        CallSyncScheduler.schedulePeriodicCatchUp(this)
    }

    private fun retryFailed() {
        lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.get(this@MainActivity).callDao().requeueFailed()
            CallSyncScheduler.requestSync(this@MainActivity)
        }
    }

    /**
     * Stores the number already normalized so the filter compares like with
     * like, whatever format the tester typed.
     */
    private fun addExcludedNumber(raw: String) {
        val normalized = normalizePhoneNumber(raw)
        if (normalized == UNKNOWN_NUMBER) return
        lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.get(this@MainActivity).excludedNumberDao()
                .exclude(ExcludedNumberEntity(normalizedNumber = normalized))
        }
    }

    private fun removeExcludedNumber(normalized: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.get(this@MainActivity).excludedNumberDao().removeByNumber(normalized)
        }
    }

    private fun clearLog() {
        lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.get(this@MainActivity).diagnosticEventDao().clear()
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }

    /**
     * Opens the battery-optimization exemption prompt so the OEM matrix can
     * compare an optimized run against an exempted one.
     *
     * Play Store policy treats this prompt as a red flag for consumer apps.
     * That is a distribution question, not a technical one, and this POC is not
     * distributed through the Play Store - see README.
     */
    private fun requestBatteryOptimizationExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }.onFailure {
            // Some OEM builds do not expose this activity. Fall back to the
            // general battery-optimization list rather than failing silently.
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}

@Composable
private fun ObserveResume(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
