package com.calltracker.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calltracker.app.database.CallEntity
import com.calltracker.app.database.ExcludedNumberEntity
import com.calltracker.app.diagnostics.DeviceInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen order is deliberate: explanation -> per-permission rationale ->
 * permission request -> diagnostics. The ask is never a bare
 * "Allow call log access" with no context.
 */
@Composable
fun ConsentScreen(onAccept: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Before we start", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(
            "This is a technical proof of concept for company-issued phones. " +
                "It exists to test whether business call activity can be captured " +
                "reliably, not to ship as a product."
        )
        Spacer(Modifier.height(16.dp))
        Text("What is collected", fontWeight = FontWeight.Bold)
        Text("The phone number, whether the call was incoming or outgoing, what happened to it, when it started, and how long it lasted.")
        Spacer(Modifier.height(12.dp))
        Text("What is never collected", fontWeight = FontWeight.Bold)
        Text("Call audio. This app does not record calls and requests no microphone permission.")
        Spacer(Modifier.height(12.dp))
        Text("Personal calls", fontWeight = FontWeight.Bold)
        Text("Numbers on the excluded list are discarded on this device before anything is stored or queued. They are never uploaded.")
        Spacer(Modifier.height(12.dp))
        Text("Where the data goes", fontWeight = FontWeight.Bold)
        Text("Nowhere, in this build. There is no server. Everything stays in a local database on this phone.")
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAccept) { Text("I understand, continue") }
    }
}

data class PermissionRow(
    val name: String,
    val title: String,
    val why: String,
    val required: Boolean,
    val granted: Boolean
)

@Composable
fun PermissionRequestScreen(
    permissions: List<PermissionRow>,
    onRequestRequired: () -> Unit,
    onRequestOptional: () -> Unit,
    onOpenSettings: () -> Unit,
    onSkipOptional: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Permissions", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Each permission below is listed with the exact thing it is used for.")
        Spacer(Modifier.height(16.dp))

        permissions.forEach { p ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.title, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(if (p.granted) "GRANTED" else if (p.required) "REQUIRED" else "OPTIONAL")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(p.why, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequestRequired, modifier = Modifier.fillMaxWidth()) {
            Text("Grant required permissions")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRequestOptional, modifier = Modifier.fillMaxWidth()) {
            Text("Grant optional permissions")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSkipOptional, modifier = Modifier.fillMaxWidth()) {
            Text("Continue without optional permissions")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Open app settings")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "If a permission was permanently denied, Android will not show the " +
                "dialog again - use app settings.",
            fontSize = 12.sp
        )
    }
}

@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    device: DeviceInfo,
    callLogGranted: Boolean,
    phoneStateGranted: Boolean,
    contactsGranted: Boolean,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    onRescan: () -> Unit,
    onSyncNow: () -> Unit,
    onRetryFailed: () -> Unit,
    onBatterySettings: () -> Unit,
    onClearLog: () -> Unit,
    onAddExcludedNumber: (String) -> Unit,
    onRemoveExcludedNumber: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Text("CallTracker diagnostics", style = MaterialTheme.typography.headlineSmall)
        }

        item { SectionHeader("Permissions") }
        item { StatusRow("Call log (READ_CALL_LOG)", grant(callLogGranted)) }
        item { StatusRow("Phone state (READ_PHONE_STATE)", grant(phoneStateGranted)) }
        item { StatusRow("Contacts (READ_CONTACTS, optional)", grant(contactsGranted)) }

        item { SectionHeader("Monitoring") }
        item {
            StatusRow(
                "Monitoring",
                if (state.runtime.monitoringActive) "ACTIVE" else "INACTIVE"
            )
        }
        item { StatusRow("Started", timeOrDash(state.runtime.monitoringStartedAt)) }
        item {
            StatusRow(
                "Last ContentObserver event",
                timeOrDash(state.runtime.lastObserverEventAt) +
                    " (" + state.runtime.observerEventCount + " total)"
            )
        }
        item {
            StatusRow(
                "Last TelephonyCallback state",
                if (state.runtime.lastTelephonyState.isEmpty()) "-"
                else state.runtime.lastTelephonyState + " at " +
                    timeOrDash(state.runtime.lastTelephonyStateAt)
            )
        }
        item { StatusRow("Highest call-log id scanned", state.runtime.lastProcessedCallLogId.toString()) }
        if (state.runtime.lastForegroundTimeoutAt > 0L) {
            item {
                StatusRow(
                    "Foreground service timed out",
                    timeOrDash(state.runtime.lastForegroundTimeoutAt)
                )
            }
        }

        item { SectionHeader("Calls") }
        item { StatusRow("Captured", state.capturedCount.toString()) }
        item { StatusRow("Excluded by privacy filter", state.excludedCount.toString()) }
        item { StatusRow("Pending sync", state.pendingCount.toString()) }
        item { StatusRow("Synced", state.syncedCount.toString()) }
        item { StatusRow("Failed", state.failedCount.toString()) }
        item { StatusRow("Last captured call", timeOrDash(state.lastCapturedAt)) }
        item {
            StatusRow(
                "Last sync",
                timeOrDash(state.runtime.lastSyncAt) +
                    if (state.runtime.lastSyncResult.isEmpty()) "" else "  " + state.runtime.lastSyncResult
            )
        }

        item { SectionHeader("Device") }
        item { StatusRow("Manufacturer", device.manufacturer) }
        item { StatusRow("Model", device.model) }
        item { StatusRow("Android", device.androidLabel) }
        item { StatusRow("App version", device.appVersionName + " (" + device.appVersionCode + ")") }
        item {
            StatusRow(
                "Battery optimization",
                if (device.ignoringBatteryOptimizations) "EXEMPTED" else "OPTIMIZED"
            )
        }
        item {
            StatusRow(
                "Background restricted",
                if (device.backgroundRestricted) "YES (capture will stop)" else "NO"
            )
        }
        item {
            StatusRow("Active SIMs", device.activeSimCount?.toString() ?: "unknown")
        }
        device.oemBackgroundNote?.let { note ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("OEM background policy", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(note, fontSize = 12.sp)
                        Text(
                            "This must be granted by hand. The app does not attempt to bypass it.",
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        item { SectionHeader("Actions") }
        item {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStartMonitoring) { Text("Start") }
                    OutlinedButton(onClick = onStopMonitoring) { Text("Stop") }
                    OutlinedButton(onClick = onRescan) { Text("Rescan") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSyncNow) { Text("Sync now") }
                    OutlinedButton(onClick = onRetryFailed) { Text("Retry failed") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBatterySettings) { Text("Battery settings") }
                    OutlinedButton(onClick = onClearLog) { Text("Clear log") }
                }
            }
        }

        item { SectionHeader("Privacy exclusions") }
        item {
            // Deliberately the smallest thing that makes the privacy filter
            // testable on a physical device: one field and a list. This is NOT
            // the employee/admin settings experience a real deployment needs -
            // see README, known limitations.
            ExcludedNumberEditor(
                excluded = state.excludedNumbers,
                onAdd = onAddExcludedNumber,
                onRemove = onRemoveExcludedNumber
            )
        }

        item { SectionHeader("Recent captured calls") }
        if (state.recentCalls.isEmpty()) {
            item { Text("None yet.", fontSize = 13.sp) }
        } else {
            // Keys are namespaced: call ids and event ids are independent
            // autoincrement sequences and would otherwise collide in this
            // single LazyColumn.
            items(state.recentCalls, key = { "call-" + it.id }) { call -> CallRow(call) }
        }

        item { SectionHeader("Event log") }
        if (state.events.isEmpty()) {
            item { Text("Empty.", fontSize = 13.sp) }
        } else {
            items(state.events, key = { "event-" + it.id }) { event ->
                Text(
                    clock(event.atEpochMs) + "  " + event.type.padEnd(18) + " " + event.detail,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun ExcludedNumberEditor(
    excluded: List<ExcludedNumberEntity>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Numbers listed here are discarded before anything is stored or queued.",
            fontSize = 12.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Personal number") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        onAdd(input.trim())
                        input = ""
                    }
                },
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("Exclude") }
        }
        Spacer(Modifier.height(6.dp))
        if (excluded.isEmpty()) {
            Text("No numbers excluded.", fontSize = 12.sp)
        } else {
            excluded.forEach { entry ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.normalizedNumber,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { onRemove(entry.normalizedNumber) }) {
                        Text("Remove", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CallRow(call: CallEntity) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(
                call.normalizedNumber + (call.contactName?.let { "  ($it)" } ?: ""),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Text(
                call.direction.name + " / " + call.status.name +
                    "  " + call.durationSeconds + "s  " + call.syncStatus.name,
                fontSize = 12.sp
            )
            Text(
                "callLogId=" + call.deviceCallId +
                    "  started=" + clock(call.startedAtEpochMs) +
                    "  ended=" + clock(call.endedAtEpochMs) +
                    "  sim=" + (call.simSlot?.toString() ?: "unknown"),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Column {
        Spacer(Modifier.height(10.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Divider()
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

private fun grant(granted: Boolean) = if (granted) "GRANTED" else "DENIED"

private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
private val stampFormat = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US)

private fun clock(epochMs: Long): String =
    if (epochMs <= 0L) "--:--:--" else clockFormat.format(Date(epochMs))

private fun timeOrDash(epochMs: Long): String =
    if (epochMs <= 0L) "-" else stampFormat.format(Date(epochMs))
