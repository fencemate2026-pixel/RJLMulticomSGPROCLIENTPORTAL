package com.example.rjlmulticomsg_proclientportal.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rjlmulticomsg_proclientportal.data.repo.PortalRepository
import com.example.rjlmulticomsg_proclientportal.domain.model.GateSystem
import com.example.rjlmulticomsg_proclientportal.domain.model.SessionState
import com.example.rjlmulticomsg_proclientportal.ui.components.MulticomMenuButton
import com.example.rjlmulticomsg_proclientportal.ui.components.MulticomPage
import com.example.rjlmulticomsg_proclientportal.ui.components.MulticomPrimaryButton
import com.example.rjlmulticomsg_proclientportal.ui.components.MulticomTopBar
import com.example.rjlmulticomsg_proclientportal.ui.components.formatDeviceTelephone
import com.example.rjlmulticomsg_proclientportal.ui.components.maskPhone
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomCardBorder
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomPageBg
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomRed
import com.example.rjlmulticomsg_proclientportal.ui.theme.TextDark
import com.example.rjlmulticomsg_proclientportal.ui.theme.TextMuted
import kotlinx.coroutines.launch

private enum class Step { System, Site, GsmRules }

private data class DraftCaller(val name: String, val phone: String)

/**
 * Multicom Classic–style setup after login.
 * Device telephone / portal stay RJL-provisioned (shown read-only).
 */
@Composable
fun ClientOnboardingScreen(
    repository: PortalRepository,
    session: SessionState,
    onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val account = session.account

    var step by remember { mutableStateOf(Step.System) }
    var gateSystem by remember { mutableStateOf<GateSystem?>(null) }
    var siteName by remember { mutableStateOf(account?.siteName.orEmpty()) }
    var address by remember { mutableStateOf(account?.address.orEmpty()) }
    var notes by remember { mutableStateOf(account?.notes.orEmpty()) }
    var allowAnyCaller by remember { mutableStateOf(false) }
    val callers = remember { mutableStateListOf<DraftCaller>() }
    var callerName by remember { mutableStateOf("") }
    var callerPhone by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun finish() {
        val system = gateSystem ?: return
        saving = true
        error = null
        scope.launch {
            val result = repository.completeOnboarding(
                gateSystem = system,
                siteName = siteName,
                address = address,
                notes = notes,
                gsmAllowAnyCaller = allowAnyCaller,
                gsmCallers = callers.map { it.name to it.phone }
            )
            saving = false
            result.onSuccess { onDone() }
                .onFailure { error = it.message ?: "Could not save setup" }
        }
    }

    val title = when (step) {
        Step.System -> "Programming"
        Step.Site -> "Device"
        Step.GsmRules -> "Residents"
    }
    val deviceTel = formatDeviceTelephone(account?.gsmNumber.orEmpty())

    // Phone back walks setup steps; first step still allows system back (exit).
    BackHandler(enabled = step != Step.System) {
        when (step) {
            Step.Site -> {
                step = Step.System
                error = null
            }
            Step.GsmRules -> {
                step = Step.Site
                error = null
            }
            Step.System -> Unit
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MulticomPageBg)
    ) {
        MulticomTopBar(
            title = title,
            deviceTelephone = when {
                gateSystem == GateSystem.GSM || step == Step.GsmRules -> deviceTel
                gateSystem == GateSystem.WIFI -> account?.portalBaseUrl
                else -> deviceTel.takeIf { it != "—" }
            },
            deviceName = "Multicom",
            onBack = when (step) {
                Step.System -> null
                Step.Site -> {{ step = Step.System; error = null }}
                Step.GsmRules -> {{ step = Step.Site; error = null }}
            }
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            MulticomPage {
                when (step) {
                    Step.System -> {
                        Text(
                            "Select your Multicom system",
                            color = TextDark,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Text(
                            "RJL installs either Wi‑Fi / Tailscale or GSM (missed call).",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                        MulticomMenuButton(
                            label = "Wi‑Fi / Portal",
                            icon = Icons.Default.Wifi,
                            subtitle = "Open over Tailscale or LAN",
                            onClick = { gateSystem = GateSystem.WIFI; error = null }
                        )
                        MulticomMenuButton(
                            label = "GSM Multicom",
                            icon = Icons.Default.Call,
                            subtitle = "Missed-call open · free call",
                            onClick = { gateSystem = GateSystem.GSM; error = null }
                        )
                        if (gateSystem != null) {
                            Text(
                                "Selected: ${if (gateSystem == GateSystem.WIFI) "Wi‑Fi" else "GSM"}",
                                color = MulticomRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        if (error != null) ErrorLine(error!!)
                        MulticomPrimaryButton("Continue") {
                            if (gateSystem == null) {
                                error = "Choose Wi‑Fi or GSM."
                                return@MulticomPrimaryButton
                            }
                            error = null
                            step = Step.Site
                        }
                    }

                    Step.Site -> {
                        Text(
                            "Property details you control. Connection numbers are set by RJL.",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                        WhiteField(
                            value = siteName,
                            onChange = { siteName = it },
                            label = "Site / property name"
                        )
                        WhiteField(
                            value = address,
                            onChange = { address = it },
                            label = "Site address",
                            singleLine = false
                        )
                        WhiteField(
                            value = notes,
                            onChange = { notes = it },
                            label = "Notes (optional)",
                            singleLine = false
                        )

                        InfoBox(
                            title = "Installed by RJL (locked)",
                            lines = when (gateSystem) {
                                GateSystem.WIFI -> listOf(
                                    "Portal: ${account?.portalBaseUrl?.ifBlank { "Contact RJL" }}"
                                )
                                GateSystem.GSM -> listOf(
                                    "Device Telephone Number: ${maskPhone(account?.gsmNumber.orEmpty()).ifBlank { "Contact RJL" }}"
                                )
                                null -> emptyList()
                            }
                        )

                        if (error != null) ErrorLine(error!!)
                        MulticomPrimaryButton(
                            if (gateSystem == GateSystem.GSM) "Continue" else if (saving) "Saving…" else "Finish"
                        ) {
                            if (siteName.isBlank()) {
                                error = "Enter a site name."
                                return@MulticomPrimaryButton
                            }
                            if (address.isBlank()) {
                                error = "Enter the site address."
                                return@MulticomPrimaryButton
                            }
                            error = null
                            if (gateSystem == GateSystem.GSM) step = Step.GsmRules else finish()
                        }
                    }

                    Step.GsmRules -> {
                        Text(
                            "GSM whitelist — who may open the gate by phone?",
                            color = TextDark,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Text(
                            "Only numbers on this whitelist open the gate. The ESP32 rejects " +
                                "unknown, disabled, or expired callers. Carrier charges may still apply.",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                        Text(
                            "Add authorised caller",
                            color = TextDark,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        WhiteField(callerName, { callerName = it }, "Name")
                        WhiteField(callerPhone, { callerPhone = it }, "Mobile number")
                        MulticomPrimaryButton("Add caller") {
                            val n = callerName.trim()
                            when (
                                val r = com.example.rjlmulticomsg_proclientportal.domain.phone
                                    .PhoneNumberNormalizer.normalize(callerPhone)
                            ) {
                                is com.example.rjlmulticomsg_proclientportal.domain.phone
                                    .PhoneNumberNormalizer.Result.Invalid -> {
                                    error = "Mobile number: ${r.reason}"
                                    return@MulticomPrimaryButton
                                }
                                is com.example.rjlmulticomsg_proclientportal.domain.phone
                                    .PhoneNumberNormalizer.Result.Valid -> {
                                    if (n.isBlank()) {
                                        error = "Enter a name."
                                        return@MulticomPrimaryButton
                                    }
                                    if (callers.any {
                                            com.example.rjlmulticomsg_proclientportal.domain.phone
                                                .PhoneNumberNormalizer.normalize(it.phone)
                                                .let { nr ->
                                                    nr is com.example.rjlmulticomsg_proclientportal
                                                        .domain.phone.PhoneNumberNormalizer.Result.Valid &&
                                                        nr.e164 == r.e164
                                                }
                                        }
                                    ) {
                                        error = "That number is already listed."
                                        return@MulticomPrimaryButton
                                    }
                                    callers.add(DraftCaller(n, r.e164))
                                    callerName = ""
                                    callerPhone = ""
                                    error = null
                                }
                            }
                        }
                        callers.forEachIndexed { index, c ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White)
                                    .border(1.dp, MulticomCardBorder, RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        com.example.rjlmulticomsg_proclientportal.domain.phone
                                            .PhoneNumberNormalizer.formatDisplay(c.phone),
                                        color = TextMuted,
                                        fontSize = 12.sp
                                    )
                                }
                                Text(
                                    "Remove",
                                    color = MulticomRed,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { callers.removeAt(index) }
                                )
                            }
                        }

                        if (error != null) ErrorLine(error!!)
                        MulticomPrimaryButton(if (saving) "Saving…" else "Finish setup") {
                            allowAnyCaller = false
                            if (callers.isEmpty()) {
                                error = "Add at least one authorised caller."
                            } else {
                                finish()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WhiteField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
    )
}

@Composable
private fun InfoBox(title: String, lines: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(1.dp, MulticomCardBorder, RoundedCornerShape(8.dp))
            .padding(14.dp)
    ) {
        Text(title, color = MulticomRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        lines.forEach {
            Text(it, color = TextDark, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun SelectRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MulticomRed.copy(alpha = 0.08f) else Color.White)
            .border(
                1.5.dp,
                if (selected) MulticomRed else MulticomCardBorder,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (selected) "●  $label" else "○  $label",
            color = TextDark,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, color = MulticomRed, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
}
