package com.calltracker.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calltracker.app.database.CallEntity
import com.calltracker.app.database.DiagnosticEventEntity
import com.calltracker.app.database.ExcludedNumberEntity
import com.calltracker.app.diagnostics.DeviceInfo
import com.calltracker.app.diagnostics.Format
import com.calltracker.app.diagnostics.PreflightAction
import com.calltracker.app.diagnostics.PreflightCheck
import com.calltracker.app.diagnostics.PreflightChecker
import com.calltracker.app.diagnostics.PreflightStatus

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
    preflight: List<PreflightCheck>,
    callLogGranted: Boolean,
    phoneStateGranted: Boolean,
    contactsGranted: Boolean,
    notificationsGranted: Boolean,
    onRerunPreflight: () -> Unit,
    onPreflightAction: (PreflightAction) -> Unit,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    onRescan: () -> Unit,
    onSyncNow: () -> Unit,
    onRetryFailed: () -> Unit,
    onBatterySettings: () -> Unit,
    onClearDiagnostics: () -> Unit,
    onAddExcludedNumber: (String) -> Unit,
    onRemoveExcludedNumber: (String) -> Unit,
    onDryRunPrivacy: (String) -> Unit
) {
    val overall = PreflightChecker.overall(preflight)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Text("CallTracker diagnostics", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Technical POC. PASS here means the pipeline is wired on this device, " +
                    "not that background capture is proven.",
                fontSize = 12.sp
            )
        }

        // ------------------------------------------------------------------
        // Pre-flight
        // ------------------------------------------------------------------
        item { SectionHeader("Pre-flight") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(overall)
                Spacer(Modifier.padding(horizontal = 6.dp))
                Text(
                    when (overall) {
                        PreflightStatus.PASS -> "All checks passed. Ready for Phase 2."
                        PreflightStatus.WARNING -> "Ready with warnings. Read them before testing background phases."
                        PreflightStatus.FAIL -> "Not ready. Fix the FAIL rows before making test calls."
                    },
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onRerunPreflight) { Text("Re-run", fontSize = 12.sp) }
            }
        }
        if (preflight.isEmpty()) {
            item { Text("Running checks...", fontSize = 13.sp) }
        } else {
            items(preflight, key = { "preflight-" + it.name }) { check ->
                PreflightRow(check, onPreflightAction)
            }
        }

        // ------------------------------------------------------------------
        // Permissions (raw)
        // ------------------------------------------------------------------
        item { SectionHeader("Permissions") }
        item { StatusRow("Call log (READ_CALL_LOG)", grant(callLogGranted)) }
        item { StatusRow("Phone state (READ_PHONE_STATE)", grant(phoneStateGranted)) }
        item { StatusRow("Contacts (READ_CONTACTS, optional)", grant(contactsGranted)) }
        item { StatusRow("Notifications (POST_NOTIFICATIONS)", grant(notificationsGranted)) }

        // ------------------------------------------------------------------
        // Monitoring
        // ------------------------------------------------------------------
        item { SectionHeader("Monitoring") }
        item {
            StatusRow(
                "Monitoring",
                if (state.runtime.monitoringActive) "ACTIVE" else "INACTIVE"
            )
        }
        item { StatusRow("Last started", timeOrDash(state.runtime.monitoringStartedAt)) }
        item {
            StatusRow(
                "ContentObserver registered",
                if (state.runtime.observerRegistered) "YES" else "NO"
            )
        }
        item {
            StatusRow(
                "TelephonyCallback registered",
                if (state.runtime.telephonyRegistered) "YES" else "NO"
            )
        }
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
        item {
            StatusRow(
                "Last scan",
                timeOrDash(state.runtime.lastScanAt) +
                    if (state.runtime.lastScanReason.isEmpty()) "" else "  " + state.runtime.lastScanReason
            )
        }
        if (state.runtime.lastScanSummary.isNotEmpty()) {
            item { StatusRow("Last scan result", state.runtime.lastScanSummary) }
        }
        item { StatusRow("Highest call-log id scanned", state.runtime.lastProcessedCallLogId.toString()) }
        item { StatusRow("Last catch-up worker run", timeOrDash(state.runtime.lastCatchUpRunAt)) }
        if (state.runtime.lastForegroundTimeoutAt > 0L) {
            item {
                StatusRow(
                    "Foreground service timed out",
                    timeOrDash(state.runtime.lastForegroundTimeoutAt)
                )
            }
        }

        // ------------------------------------------------------------------
        // Calls
        // ------------------------------------------------------------------
        item { SectionHeader("Calls") }
        item { StatusRow("Stored", state.capturedCount.toString()) }
        item { StatusRow("Excluded by privacy filter", state.excludedCount.toString()) }
        item { StatusRow("Duplicates detected", state.runtime.duplicateCount.toString()) }
        item { StatusRow("Pending sync", state.pendingCount.toString()) }
        item { StatusRow("Synced", state.syncedCount.toString()) }
        item { StatusRow("Failed", state.failedCount.toString()) }
        item { StatusRow("Sync runs succeeded", state.runtime.syncSucceededCount.toString()) }
        item { StatusRow("Sync runs failed", state.runtime.syncFailedCount.toString()) }
        item { StatusRow("Last stored call", timeOrDash(state.lastCapturedAt)) }
        item {
            StatusRow(
                "Last sync",
                timeOrDash(state.runtime.lastSyncAt) +
                    if (state.runtime.lastSyncResult.isEmpty()) "" else "  " + state.runtime.lastSyncResult
            )
        }

        // ------------------------------------------------------------------
        // Last call trace
        // ------------------------------------------------------------------
        item { SectionHeader("Last call trace") }
        if (state.lastCallTrace.isEmpty()) {
            item {
                Text(
                    "No call has been evaluated yet. After a call, every event carrying " +
                        "that call-log id appears here in order.",
                    fontSize = 12.sp
                )
            }
        } else {
            item {
                Text(
                    "callLogId=" + state.lastCallTraceId + " — " + state.lastCallTrace.size + " events",
                    fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
            items(state.lastCallTrace, key = { "trace-" + it.id }) { event ->
                EventLine(event)
            }
        }

        // ------------------------------------------------------------------
        // Device
        // ------------------------------------------------------------------
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

        // ------------------------------------------------------------------
        // Actions
        // ------------------------------------------------------------------
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
                    OutlinedButton(onClick = onClearDiagnostics) { Text("Clear diagnostics") }
                }
                Text(
                    "Clear diagnostics empties the event log and resets the counters above. " +
                        "It never touches stored calls, the processed-row ledger, the scan " +
                        "watermark, or the exclusion list.",
                    fontSize = 11.sp
                )
            }
        }

        // ------------------------------------------------------------------
        // Privacy
        // ------------------------------------------------------------------
        item { SectionHeader("Privacy exclusions") }
        item {
            // Deliberately the smallest thing that makes the privacy filter
            // testable on a physical device: one field and a list. This is NOT
            // the employee/admin settings experience a real deployment needs -
            // see README, known limitations.
            ExcludedNumberEditor(
                excluded = state.excludedNumbers,
                onAdd = onAddExcludedNumber,
                onRemove = onRemoveExcludedNumber,
                onDryRun = onDryRunPrivacy
            )
        }

        // ------------------------------------------------------------------
        // Recent calls + full event log
        // ------------------------------------------------------------------
        item { SectionHeader("Recent stored calls") }
        if (state.recentCalls.isEmpty()) {
            item { Text("None yet.", fontSize = 13.sp) }
        } else {
            // Keys are namespaced: call ids and event ids are independent
            // autoincrement sequences and would otherwise collide in this
            // single LazyColumn.
            items(state.recentCalls, key = { "call-" + it.id }) { call -> CallRow(call) }
        }

        item { SectionHeader("Event log (newest first)") }
        if (state.events.isEmpty()) {
            item { Text("Empty.", fontSize = 13.sp) }
        } else {
            items(state.events, key = { "event-" + it.id }) { event -> EventLine(event) }
        }
    }
}

@Composable
private fun PreflightRow(check: PreflightCheck, onAction: (PreflightAction) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(check.status)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(check.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(check.detail, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            check.why?.let { Text(it, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            check.action?.let { action ->
                OutlinedButton(onClick = { onAction(action) }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        when (action) {
                            PreflightAction.OPEN_APP_SETTINGS -> "Open app settings"
                            PreflightAction.OPEN_NOTIFICATION_SETTINGS -> "Open notification settings"
                            PreflightAction.OPEN_BATTERY_SETTINGS -> "Battery settings"
                            PreflightAction.START_MONITORING -> "Start monitoring"
                            PreflightAction.ADD_EXCLUDED_NUMBER -> "See Privacy exclusions below"
                        },
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: PreflightStatus) {
    val (label, color) = when (status) {
        PreflightStatus.PASS -> "PASS" to Color(0xFF2E7D32)
        PreflightStatus.WARNING -> "WARN" to Color(0xFFEF6C00)
        PreflightStatus.FAIL -> "FAIL" to Color(0xFFC62828)
    }
    Text(
        label,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun EventLine(event: DiagnosticEventEntity) {
    Text(
        clock(event.atEpochMs) + "  " + event.type.padEnd(24) + " " + event.detail,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp
    )
}

@Composable
private fun ExcludedNumberEditor(
    excluded: List<ExcludedNumberEntity>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDryRun: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Numbers listed here are discarded before anything is stored or queued.",
            fontSize = 12.sp
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Phone number") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        onAdd(input.trim())
                        input = ""
                    }
                }
            ) { Text("Exclude") }
            OutlinedButton(
                onClick = { if (input.isNotBlank()) onDryRun(input.trim()) }
            ) { Text("Dry-run check") }
        }
        Text(
            "Dry-run runs the typed number through normalize -> privacy filter only and " +
                "writes a SIMULATED line to the event log. It stores nothing and is not a call.",
            fontSize = 11.sp
        )
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

private fun clock(epochMs: Long): String = Format.clock(epochMs)

private fun timeOrDash(epochMs: Long): String = Format.stamp(epochMs)
