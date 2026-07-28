package com.example.rjlmulticomsg_proclientportal.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.rjlmulticomsg_proclientportal.data.repo.PortalRepository
import com.example.rjlmulticomsg_proclientportal.domain.model.ModuleType
import com.example.rjlmulticomsg_proclientportal.domain.model.SessionState
import com.example.rjlmulticomsg_proclientportal.ui.components.MulticomMenuButton
import com.example.rjlmulticomsg_proclientportal.ui.components.MulticomPage
import com.example.rjlmulticomsg_proclientportal.ui.components.MulticomPrimaryButton
import com.example.rjlmulticomsg_proclientportal.ui.components.MulticomTopBar
import com.example.rjlmulticomsg_proclientportal.ui.components.formatDeviceTelephone
import com.example.rjlmulticomsg_proclientportal.ui.components.maskPhone
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomPageBg
import kotlinx.coroutines.launch

/**
 * Client home — Multicom Classic programming-app layout:
 * grey device header + large white menu cards + red primary action.
 */
@Composable
fun HomeScreen(
    repository: PortalRepository,
    session: SessionState,
    onOpenSchedules: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenWifi: () -> Unit,
    onOpenGsm: () -> Unit,
    onOpenRfid: () -> Unit,
    onOpenLpr: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
    onOpenMagicKey: () -> Unit = {},
    onOpenDeviceLocation: () -> Unit = {},
    onOpenMessages: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var opening by remember { mutableStateOf(false) }
    var pendingGsm by remember { mutableStateOf(false) }
    var showGsmConfirm by remember { mutableStateOf(false) }

    val enabled = session.enabledModules.toSet()
    val isGsm = ModuleType.GSM in enabled
    val isWifi = ModuleType.WIFI in enabled
    val siteName = session.account?.siteName?.ifBlank { "Multicom" } ?: "Multicom"
    val deviceTelLabel = if (isGsm) {
        formatDeviceTelephone(session.account?.gsmNumber.orEmpty())
    } else {
        session.account?.wifiHost?.ifBlank {
            session.account?.portalBaseUrl.orEmpty()
        }.orEmpty().ifBlank { "—" }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (pendingGsm) {
            pendingGsm = false
            showGsmConfirm = true
        }
    }

    fun openGate() {
        scope.launch {
            when {
                isGsm -> {
                    if (!repository.gsmHasCallPermission(context)) {
                        pendingGsm = true
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.CALL_PHONE)
                        )
                        return@launch
                    }
                    showGsmConfirm = true
                }
                isWifi -> {
                    opening = true
                    snackbar.showSnackbar(repository.openWifiGate().message)
                    opening = false
                }
                else -> snackbar.showSnackbar("No gate system configured.")
            }
        }
    }

    if (showGsmConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showGsmConfirm = false },
            title = { androidx.compose.material3.Text("Open gate by GSM?") },
            text = {
                androidx.compose.material3.Text(
                    "Call ${siteName}? The gate controller should reject the call immediately. " +
                        "Carrier call charges may still depend on your mobile provider."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showGsmConfirm = false
                    scope.launch {
                        opening = true
                        snackbar.showSnackbar(repository.openGsmGate(context).message)
                        opening = false
                    }
                }) { androidx.compose.material3.Text("Call now") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showGsmConfirm = false }) {
                    androidx.compose.material3.Text("Cancel")
                }
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MulticomPageBg)
    ) {
        MulticomTopBar(
            title = "Home",
            deviceTelephone = if (isGsm) deviceTelLabel else null,
            deviceName = siteName
        )

        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                MulticomPage {
                    // Primary open — Multicom-style red bar
                    MulticomPrimaryButton(
                        label = when {
                            opening -> "Opening…"
                            isGsm -> "Open Gate by GSM"
                            isWifi -> "Open Gate (Wi‑Fi)"
                            else -> "Open Gate"
                        },
                        onClick = { openGate() },
                        enabled = !opening && (isGsm || isWifi)
                    )

                    MulticomMenuButton(
                        label = "Home",
                        icon = Icons.Default.Home,
                        subtitle = session.account?.address?.ifBlank { null },
                        onClick = { /* already home */ }
                    )

                    if (isGsm) {
                        MulticomMenuButton(
                            label = "Messages",
                            icon = Icons.Default.Message,
                            subtitle = "Individual or bulk SMS from the gate SIM",
                            onClick = onOpenMessages
                        )
                        MulticomMenuButton(
                            label = "Authorised callers",
                            icon = Icons.Default.Call,
                            subtitle = "Live from 29 Jul 2026 · gate ${maskPhone(session.account?.gsmNumber.orEmpty())}",
                            onClick = onOpenGsm
                        )
                        MulticomMenuButton(
                            label = "Portal users",
                            icon = Icons.Default.People,
                            subtitle = "App logins (commercial sites often have none)",
                            onClick = onOpenPeople
                        )
                    }

                    if (isWifi) {
                        MulticomMenuButton(
                            label = "Wi‑Fi / Portal",
                            icon = Icons.Default.Wifi,
                            subtitle = session.account?.portalBaseUrl,
                            onClick = onOpenWifi
                        )
                        MulticomMenuButton(
                            label = "Residents",
                            icon = Icons.Default.People,
                            subtitle = "Family & friends on this property",
                            onClick = onOpenPeople
                        )
                    }

                    MulticomMenuButton(
                        label = "Schedules",
                        icon = Icons.Default.Schedule,
                        subtitle = "Cleaners, deliveries, auto windows",
                        onClick = onOpenSchedules
                    )

                    MulticomMenuButton(
                        label = "Ask Grok",
                        icon = Icons.Default.SupportAgent,
                        subtitle = "AI help on how to use this app",
                        onClick = onOpenHelp
                    )

                    MulticomMenuButton(
                        label = "Magic Key Access",
                        icon = Icons.Default.Key,
                        subtitle = "Verify an authorised Magic Key",
                        onClick = onOpenMagicKey
                    )

                    if (isGsm) {
                        MulticomMenuButton(
                            label = "Device location",
                            icon = Icons.Default.LocationOn,
                            subtitle = "Live SIM7600 GNSS · satellite map",
                            onClick = onOpenDeviceLocation
                        )
                    }

                    MulticomMenuButton(
                        label = "Status",
                        icon = Icons.AutoMirrored.Filled.List,
                        subtitle = "Action log for this property",
                        onClick = onOpenLogs
                    )

                    if (ModuleType.RFID in enabled) {
                        MulticomMenuButton(
                            label = "Visitor Prox IDs",
                            icon = Icons.Default.Lock,
                            subtitle = "Fobs & cards",
                            onClick = onOpenRfid
                        )
                    }
                    if (ModuleType.LPR in enabled) {
                        MulticomMenuButton(
                            label = "License plates",
                            icon = Icons.Default.Lock,
                            subtitle = "Authorised plates",
                            onClick = onOpenLpr
                        )
                    }

                    MulticomMenuButton(
                        label = "Programming",
                        icon = Icons.Default.Settings,
                        subtitle = "Modules & property details",
                        onClick = onOpenSettings
                    )

                    MulticomMenuButton(
                        label = "Settings",
                        icon = Icons.Default.Settings,
                        onClick = onOpenSettings
                    )
                }
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth()
            )
        }
    }
}
