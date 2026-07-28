package com.example.rjlmulticomsg_proclientportal.ui.modules

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rjlmulticomsg_proclientportal.data.repo.PortalRepository
import com.example.rjlmulticomsg_proclientportal.domain.model.GsmCaller
import com.example.rjlmulticomsg_proclientportal.domain.model.GsmCallerRole
import com.example.rjlmulticomsg_proclientportal.domain.model.GsmCallerStatus
import com.example.rjlmulticomsg_proclientportal.domain.model.SessionState
import com.example.rjlmulticomsg_proclientportal.domain.model.UserRole
import com.example.rjlmulticomsg_proclientportal.domain.model.label
import com.example.rjlmulticomsg_proclientportal.domain.phone.PhoneNumberNormalizer
import com.example.rjlmulticomsg_proclientportal.ui.components.EmptyHint
import com.example.rjlmulticomsg_proclientportal.ui.components.MulticomPrimaryButton
import com.example.rjlmulticomsg_proclientportal.ui.components.MulticomTopBar
import com.example.rjlmulticomsg_proclientportal.ui.components.SectionCard
import com.example.rjlmulticomsg_proclientportal.ui.components.formatDeviceTelephone
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomPageBg
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomRed
import com.example.rjlmulticomsg_proclientportal.ui.theme.TextMuted
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GsmModuleScreen(
    repository: PortalRepository,
    session: SessionState,
    onBack: () -> Unit,
    onSchedules: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var loading by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var showRemoteConfirm by remember { mutableStateOf(false) }
    var remoteTestLoading by remember { mutableStateOf(false) }
    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GsmCaller?>(null) }
    var confirmDelete by remember { mutableStateOf<GsmCaller?>(null) }
    var confirmDisable by remember { mutableStateOf<GsmCaller?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var gateHeld by remember { mutableStateOf(false) }
    var showHoldConfirm by remember { mutableStateOf(false) }
    var holdLoading by remember { mutableStateOf(false) }

    val isOwner = session.user?.role == UserRole.OWNER
    val accountId = session.account?.id.orEmpty()
    val callers by repository.observeGsmCallers(accountId).collectAsState(initial = emptyList())
    val devices by repository.observeGsmDevices(accountId).collectAsState(initial = emptyList())
    val callLogs by repository.observeGsmCallLogs(accountId).collectAsState(initial = emptyList())
    val meta by repository.observeWhitelistMeta(accountId).collectAsState(initial = null)
    var ownCaller by remember { mutableStateOf<GsmCaller?>(null) }

    LaunchedEffect(accountId, session.user?.id) {
        ownCaller = repository.getOwnGsmCaller()
        repository.syncGsmFromCloud(accountId)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (pending) {
            pending = false
            showConfirm = true
        }
    }

    fun requestOpen() {
        if (!repository.gsmHasCallPermission(context)) {
            pending = true
            launcher.launch(arrayOf(Manifest.permission.CALL_PHONE))
            return
        }
        showConfirm = true
    }

    fun doOpen() {
        scope.launch {
            loading = true
            val r = repository.openGsmGate(context)
            loading = false
            snackbar.showSnackbar(r.message)
        }
    }

    fun requestHoldGate() {
        if (!repository.gsmHasCallPermission(context)) {
            pending = true
            launcher.launch(arrayOf(Manifest.permission.CALL_PHONE))
            return
        }
        showHoldConfirm = true
    }

    fun doHoldGate() {
        scope.launch {
            holdLoading = true
            val r = repository.openGsmGate(context)
            holdLoading = false
            if (r.success) {
                gateHeld = true
                snackbar.showSnackbar("Gate relay held open. Tap 'Release gate' to close.")
            } else {
                snackbar.showSnackbar(r.message)
            }
        }
    }

    fun releaseGate() {
        scope.launch {
            holdLoading = true
            val r = repository.openGsmGate(context)
            holdLoading = false
            gateHeld = false
            snackbar.showSnackbar("Gate relay released.")
        }
    }

    if (showHoldConfirm) {
        AlertDialog(
            onDismissRequest = { showHoldConfirm = false },
            title = { Text("Hold gate open?") },
            text = {
                Column {
                    Text(
                        "Hold the relay open until you release it. Call " +
                            "${session.account?.siteName?.ifBlank { "this property" }} " +
                            "gate controller and hold the relay on."
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Use the 'Release gate' button to close the relay when done.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showHoldConfirm = false
                    doHoldGate()
                }) { Text("Hold open") }
            },
            dismissButton = {
                TextButton(onClick = { showHoldConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Open gate by GSM?") },
            text = {
                Column {
                    Text(
                        "Call ${session.account?.siteName?.ifBlank { "this property" }} " +
                            "gate controller?"
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The gate controller should reject the call immediately. " +
                            "Carrier call charges may still depend on your mobile provider.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    doOpen()
                }) { Text("Call now") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showRemoteConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoteConfirm = false },
            title = { Text("Run remote gate test?") },
            text = {
                Text(
                    "The cloud will securely queue one relay pulse for the on-site controller. " +
                        "The gate may open. The command expires after 5 minutes and the controller " +
                        "must be online. No computer is required at the property."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemoteConfirm = false
                    scope.launch {
                        remoteTestLoading = true
                        repository.requestRemoteGateTest().fold(
                            onSuccess = {
                                snackbar.showSnackbar(
                                    "Remote test queued. Watch Controller status and Status logs for confirmation."
                                )
                            },
                            onFailure = {
                                snackbar.showSnackbar(it.message ?: "Remote test could not be queued")
                            }
                        )
                        remoteTestLoading = false
                    }
                }) { Text("Queue remote test") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete caller?") },
            text = { Text("Remove ${c.displayName} from the GSM whitelist? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repository.deleteGsmCaller(c.id, c.displayName)
                            .onSuccess {
                                snackbar.showSnackbar(
                                    "Removed from cloud. Waiting for the controller to confirm sync."
                                )
                            }
                            .onFailure { snackbar.showSnackbar(it.message ?: "Delete failed") }
                        confirmDelete = null
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }

    confirmDisable?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDisable = null },
            title = { Text(if (c.enabled) "Disable caller?" else "Enable caller?") },
            text = {
                Text(
                    if (c.enabled) "Disable ${c.displayName}? They will not open the gate."
                    else "Enable ${c.displayName}?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repository.setGsmCallerEnabled(c.id, !c.enabled)
                            .onSuccess {
                                snackbar.showSnackbar(
                                    "Saved to cloud. Waiting for controller confirmation."
                                )
                            }
                            .onFailure { snackbar.showSnackbar(it.message ?: "Failed") }
                        confirmDisable = null
                    }
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisable = null }) { Text("Cancel") }
            }
        )
    }

    if (showForm) {
        CallerFormDialog(
            initial = editing,
            submitting = submitting,
            onDismiss = { showForm = false; editing = null },
            onSave = { form ->
                scope.launch {
                    submitting = true
                    val result = if (editing == null) {
                        repository.addGsmCaller(form)
                    } else {
                        repository.updateGsmCaller(editing!!.id, form)
                    }
                    submitting = false
                    result.onSuccess {
                        showForm = false
                        val wasNew = editing == null
                        editing = null
                        snackbar.showSnackbar(
                            if (wasNew) {
                                "Saved to cloud. The controller will sync and send the welcome SMS."
                            } else {
                                "Updated in cloud. Waiting for controller confirmation."
                            }
                        )
                    }.onFailure {
                        snackbar.showSnackbar(it.message ?: "Could not save")
                    }
                }
            }
        )
    }

    val primaryDevice = devices.firstOrNull()
    val deviceOffline = primaryDevice?.isOffline() != false && devices.isNotEmpty()
    val pendingSync = meta?.pendingRefresh == true || callers.any { it.pendingSync }

    // Phone back: close form/dialogs first, then leave the module.
    BackHandler {
        when {
            confirmDelete != null -> confirmDelete = null
            confirmDisable != null -> confirmDisable = null
            showConfirm -> showConfirm = false
            showHoldConfirm -> showHoldConfirm = false
            showRemoteConfirm -> showRemoteConfirm = false
            showForm -> {
                showForm = false
                editing = null
            }
            else -> onBack()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MulticomPageBg)
    ) {
        val gateSim = session.account?.gsmNumberE164
            ?: session.account?.gsmNumber.orEmpty()
        val gateSimDisplay = gateSim.takeIf { it.isNotBlank() }?.let {
            PhoneNumberNormalizer.formatDisplay(it)
        } ?: "Not set — RJL provisions this on the SIM7600 DTU"

        MulticomTopBar(
            title = "Authorised callers",
            deviceTelephone = formatDeviceTelephone(gateSim),
            deviceName = session.account?.siteName?.ifBlank { "Multicom SG-PRO" } ?: "Multicom SG-PRO",
            onBack = {
                if (showForm) {
                    showForm = false
                    editing = null
                } else {
                    onBack()
                }
            }
        )
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (session.dataStale) {
                    SectionCard("Offline cache") {
                        Text(
                            "Showing cached data. Some information may be out of date.",
                            color = MulticomRed,
                            fontSize = 12.sp
                        )
                    }
                }

                SectionCard("Operational notice") {
                    Text(
                        "This function will not be operational until 29 July 2026.",
                        color = MulticomRed,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        "Setup, lists, and bench testing may continue. Live sliding-gate access starts on that date.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                // Commercial model: clients call this number; RJL manages the whitelist here.
                SectionCard("Gate SIM (ESP32 + SIM7600)") {
                    Text(
                        "Commercial clients do not need the app. They call this Multicom number; " +
                            "the ESP32 hangs up immediately and opens the gate if their mobile is authorised.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Device Telephone Number", color = TextMuted, fontSize = 11.sp)
                    Text(
                        gateSimDisplay,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        "Set by RJL on the site controller. Not editable by clients.",
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (isOwner) {
                        Spacer(Modifier.height(10.dp))
                        MulticomPrimaryButton(
                            label = if (loading) "Calling…" else "Test open (call gate SIM)",
                            onClick = { requestOpen() },
                            enabled = !loading && gateSim.isNotBlank()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showRemoteConfirm = true },
                            enabled = !remoteTestLoading && devices.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (remoteTestLoading) "Queuing…" else "Remote controller test")
                        }
                        Text(
                            "Runs from this app through the cloud and the on-site controller; " +
                                "you do not need to be at the computer.",
                            color = TextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        if (gateHeld) {
                            Button(
                                onClick = { releaseGate() },
                                enabled = !holdLoading,
                                colors = ButtonDefaults.buttonColors(containerColor = MulticomRed),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (holdLoading) "Releasing…" else "Release gate")
                            }
                            Text(
                                "Gate relay is currently held open. Tap to release.",
                                color = MulticomRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        } else {
                            OutlinedButton(
                                onClick = { requestHoldGate() },
                                enabled = !holdLoading && gateSim.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (holdLoading) "Activating…" else "Hold gate open")
                            }
                            Text(
                                "Activates the relay until you tap 'Release gate'. Use for extended access.",
                                color = TextMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                SectionCard(
                    "Everyone on the list (${callers.count { it.isAuthorisedNow() }} active / ${callers.size} total)"
                ) {
                    Text(
                        "Add or delete here — the site ESP32 + SIM7600 pulls the list automatically " +
                            "(no Arduino reflash). New people get a welcome SMS from the gate SIM.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    if (isOwner) {
                        Button(
                            onClick = { editing = null; showForm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MulticomRed),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Add person to list") }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (callers.isEmpty()) {
                        EmptyHint(
                            if (isOwner) "No one on the list yet. Add authorised mobiles below."
                            else "No callers visible."
                        )
                    } else {
                        val visible = if (isOwner) callers else callers.filter {
                            it.linkedUserId == session.user?.id
                        }
                        if (visible.isEmpty()) {
                            EmptyHint("You are not on this site’s authorised list.")
                        }
                        visible.forEach { c ->
                            CallerRow(
                                caller = c,
                                isOwner = isOwner,
                                onEdit = { editing = c; showForm = true },
                                onToggle = { confirmDisable = c },
                                onDelete = { confirmDelete = c }
                            )
                        }
                    }
                }

                if (isOwner && callers.isNotEmpty()) {
                    SectionCard("Auto-sync status") {
                        Text(
                            "Normal path: portal → Firestore → ESP32 polls whitelist → " +
                                "SIM7600 SMS for new numbers. Manual sketch export is only a fallback " +
                                "if the controller is offline.",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        val export = remember(callers) {
                            buildEsp32AuthorizedArray(callers)
                        }
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("esp32_whitelist", export))
                                scope.launch {
                                    snackbar.showSnackbar("Fallback list copied (offline use only)")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Copy offline fallback list") }
                    }
                }

                SectionCard("Controller status") {
                    if (devices.isEmpty()) {
                        EmptyHint("No GSM controller reported yet.")
                    } else {
                        devices.forEach { d ->
                            Text(d.deviceName.ifBlank { d.deviceId }, fontWeight = FontWeight.SemiBold)
                            Text(
                                buildString {
                                    append(if (d.isOffline()) "Device offline" else "Online")
                                    append(" · FW ${d.firmwareVersion.ifBlank { "—" }}")
                                    append(" · WL v${d.whitelistVersion}")
                                    d.signalStrength?.let { append(" · signal $it") }
                                },
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                            val cloudVersion = session.account?.whitelistVersion ?: 0L
                            Text(
                                if (d.whitelistVersion >= cloudVersion) {
                                    "Applied to controller · cloud v$cloudVersion"
                                } else if (d.isOffline()) {
                                    "Controller offline · cloud v$cloudVersion pending"
                                } else {
                                    "Waiting for controller · cloud v$cloudVersion"
                                },
                                color = if (d.whitelistVersion >= cloudVersion) {
                                    Color(0xFF18794E)
                                } else {
                                    MulticomRed
                                },
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            )
                            if (d.lastSyncAt > 0) {
                                Text(
                                    "Last sync: ${formatTs(d.lastSyncAt)}",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                            d.lastError?.takeIf { it.isNotBlank() }?.let {
                                Text("Last error: $it", color = MulticomRed, fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    if (deviceOffline) {
                        StatusChip("Device offline")
                    }
                    if (pendingSync) {
                        StatusChip("Whitelist pending sync")
                    }
                    meta?.let {
                        Text(
                            "Whitelist version ${it.version}" +
                                if (it.lastServerSyncAt > 0) " · synced ${formatTs(it.lastServerSyncAt)}" else "",
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    }
                    if (isOwner) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    repository.requestWhitelistRefresh()
                                        .onSuccess { snackbar.showSnackbar("Refresh requested") }
                                        .onFailure { snackbar.showSnackbar(it.message ?: "Failed") }
                                    repository.syncGsmFromCloud(accountId)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Force whitelist refresh") }
                    }
                }

                if (isOwner) {
                    SectionCard("GSM call history") {
                        if (callLogs.isEmpty()) {
                            EmptyHint("No controller call events yet.")
                        } else {
                            callLogs.take(30).forEach { log ->
                                Column(Modifier.padding(vertical = 4.dp)) {
                                    Text(
                                        "${if (log.authorised) "✓" else "✗"} " +
                                                (log.matchedCallerName ?: maskDisplay(log.callerNumberE164)),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        buildString {
                                            append(if (log.relayTriggered) "Relay triggered" else "No relay")
                                            if (log.rejectionReason.isNotBlank()) {
                                                append(" · ${log.rejectionReason}")
                                            }
                                            if (log.receivedAt > 0) append(" · ${formatTs(log.receivedAt)}")
                                        },
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                } else {
                    SectionCard("Your recent actions") {
                        Text(
                            "See the Status tab for your portal action log. " +
                                "Relay results appear only when the controller reports them.",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                }

                SectionCard("Schedules") {
                    Button(
                        onClick = onSchedules,
                        colors = ButtonDefaults.buttonColors(containerColor = MulticomRed),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Manage schedules") }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun StatusChip(label: String) {
    FilterChip(
        selected = true,
        onClick = {},
        label = { Text(label, fontSize = 11.sp) },
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

@Composable
private fun CallerRow(
    caller: GsmCaller,
    isOwner: Boolean,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val status = caller.status()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(caller.displayName, fontWeight = FontWeight.SemiBold)
                Text(caller.displayPhone, color = TextMuted, fontSize = 12.sp)
                Text(
                    "${status.label()} · ${caller.role.name}" +
                        if (caller.pendingSync) " · pending sync" else "",
                    color = if (status == GsmCallerStatus.ACTIVE) TextMuted else MulticomRed,
                    fontSize = 11.sp
                )
            }
            if (isOwner) {
                Column(horizontalAlignment = Alignment.End) {
                    OutlinedButton(onClick = onEdit) { Text("Edit") }
                    TextButton(onClick = onToggle) {
                        Text(if (caller.enabled) "Disable" else "Enable")
                    }
                    TextButton(onClick = onDelete) { Text("Delete", color = MulticomRed) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallerFormDialog(
    initial: GsmCaller?,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onSave: (PortalRepository.CallerForm) -> Unit
) {
    var name by remember { mutableStateOf(initial?.displayName.orEmpty()) }
    var phone by remember {
        mutableStateOf(initial?.phoneNumberE164 ?: "")
    }
    var role by remember { mutableStateOf(initial?.role ?: GsmCallerRole.MEMBER) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }
    var validFromText by remember {
        mutableStateOf(initial?.validFrom?.let { formatTs(it) }.orEmpty())
    }
    var validUntilText by remember {
        mutableStateOf(initial?.validUntil?.let { formatTs(it) }.orEmpty())
    }
    var roleExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(if (initial == null) "Add caller" else "Edit caller") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 120) name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { if (it.length <= 32) phone = it },
                    label = { Text("Mobile number") },
                    singleLine = true,
                    supportingText = {
                        val preview = when (val r = PhoneNumberNormalizer.normalize(phone)) {
                            is PhoneNumberNormalizer.Result.Valid ->
                                PhoneNumberNormalizer.formatDisplay(r.e164)
                            is PhoneNumberNormalizer.Result.Invalid ->
                                if (phone.isBlank()) "" else r.reason
                        }
                        Text(preview, fontSize = 11.sp)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                ExposedDropdownMenuBox(
                    expanded = roleExpanded,
                    onExpandedChange = { roleExpanded = it }
                ) {
                    OutlinedTextField(
                        value = role.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Role") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(roleExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = roleExpanded,
                        onDismissRequest = { roleExpanded = false }
                    ) {
                        GsmCallerRole.entries.forEach { r ->
                            DropdownMenuItem(
                                text = { Text(r.name) },
                                onClick = { role = r; roleExpanded = false }
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(
                    value = validFromText,
                    onValueChange = { validFromText = it },
                    label = { Text("Valid from (optional, yyyy-MM-dd HH:mm)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = validUntilText,
                    onValueChange = { validUntilText = it },
                    label = { Text("Valid until (optional, yyyy-MM-dd HH:mm)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { if (it.length <= 500) notes = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = MulticomRed, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting,
                onClick = {
                    val from = parseOptionalTs(validFromText)
                    val until = parseOptionalTs(validUntilText)
                    if (validFromText.isNotBlank() && from == null) {
                        error = "Invalid valid-from date"
                        return@TextButton
                    }
                    if (validUntilText.isNotBlank() && until == null) {
                        error = "Invalid valid-until date"
                        return@TextButton
                    }
                    onSave(
                        PortalRepository.CallerForm(
                            displayName = name,
                            phoneRaw = phone,
                            role = role,
                            enabled = enabled,
                            validFrom = from,
                            validUntil = until,
                            notes = notes,
                            linkedUserId = initial?.linkedUserId
                        )
                    )
                }
            ) { Text(if (submitting) "Saving…" else "Save") }
        },
        dismissButton = {
            TextButton(enabled = !submitting, onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatTs(ms: Long): String {
    if (ms <= 0) return "—"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}

private fun parseOptionalTs(raw: String): Long? {
    if (raw.isBlank()) return null
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(raw.trim())?.time
    } catch (_: Exception) {
        null
    }
}

private fun maskDisplay(e164: String): String =
    if (e164 == "WITHHELD") "WITHHELD" else PhoneNumberNormalizer.maskForLog(e164)

/** Matches ESP32 sketch AUTHORIZED_NUMBERS[] (active E.164 only). */
private fun buildEsp32AuthorizedArray(callers: List<GsmCaller>): String {
    val active = callers
        .filter { it.isAuthorisedNow() }
        .map { it.phoneNumberE164 }
        .distinct()
        .sorted()
    if (active.isEmpty()) {
        return "const char *AUTHORIZED_NUMBERS[] = {\n  // none\n};"
    }
    val body = active.joinToString(",\n") { "  \"$it\"" }
    return "const char *AUTHORIZED_NUMBERS[] = {\n$body\n};"
}
