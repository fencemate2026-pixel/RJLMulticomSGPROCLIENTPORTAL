package com.example.rjlmulticomsg_proclientportal.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.rjlmulticomsg_proclientportal.data.repo.PortalRepository
import com.example.rjlmulticomsg_proclientportal.domain.model.SessionState
import com.example.rjlmulticomsg_proclientportal.domain.model.UserRole
import com.example.rjlmulticomsg_proclientportal.ui.components.MulticomPrimaryButton
import com.example.rjlmulticomsg_proclientportal.ui.components.MulticomTopBar
import com.example.rjlmulticomsg_proclientportal.ui.components.SectionCard
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomPageBg
import com.example.rjlmulticomsg_proclientportal.ui.theme.TextMuted
import kotlinx.coroutines.launch

@Composable
fun MessagesScreen(
    repository: PortalRepository,
    session: SessionState,
    onBack: () -> Unit
) {
    val accountId = session.account?.id.orEmpty()
    val callers by repository.observeGsmCallers(accountId).collectAsState(initial = emptyList())
    val activeCallers = callers.filter { it.isAuthorisedNow() }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var message by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Send through the gate SIM?") },
            text = {
                Text(
                    "Queue this message for ${selected.size} recipient" +
                        if (selected.size == 1) "? The SIM7600 will send it from its SIM card."
                        else "s? The SIM7600 will send each SMS from its SIM card."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        sending = true
                        repository.sendSms(selected.toList(), message).fold(
                            onSuccess = {
                                snackbar.showSnackbar(
                                    "${it.queued} SMS ${if (it.queued == 1) "is" else "are"} queued" +
                                        if (it.skipped > 0) " · ${it.skipped} skipped" else ""
                                )
                                selected = emptySet()
                                message = ""
                            },
                            onFailure = {
                                snackbar.showSnackbar(it.message ?: "SMS could not be queued")
                            }
                        )
                        sending = false
                    }
                }) { Text("Queue SMS") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Cancel") }
            }
        )
    }

    Column(Modifier.fillMaxSize().background(MulticomPageBg)) {
        MulticomTopBar(
            title = "Messages",
            deviceName = session.account?.siteName,
            onBack = onBack
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (session.user?.role != UserRole.OWNER) {
                SectionCard("Owner access") {
                    Text("Only the property owner can send messages.", color = TextMuted)
                }
            } else {
                SectionCard("Message") {
                    OutlinedTextField(
                        value = message,
                        onValueChange = { if (it.length <= 160) message = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        label = { Text("SMS text") },
                        supportingText = { Text("${message.length}/160 characters") }
                    )
                    Text(
                        "Messages are sent by the SIM7600, so the controller must be online and registered on the mobile network.",
                        color = TextMuted,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                SectionCard("Recipients") {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = activeCallers.isNotEmpty() &&
                                selected.size == activeCallers.size,
                            onCheckedChange = { checked ->
                                selected = if (checked) activeCallers.map { it.id }.toSet()
                                else emptySet()
                            }
                        )
                        Text("Select all active callers", fontWeight = FontWeight.Bold)
                    }
                    activeCallers.forEach { caller ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = caller.id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + caller.id
                                    else selected - caller.id
                                }
                            )
                            Column {
                                Text(caller.displayName, fontWeight = FontWeight.SemiBold)
                                Text(caller.displayPhone, color = TextMuted)
                            }
                        }
                    }
                    if (activeCallers.isEmpty()) {
                        Text("No active authorised callers are available.", color = TextMuted)
                    }
                }

                MulticomPrimaryButton(
                    label = if (sending) "Queuing…" else
                        "Send SMS to ${selected.size} ${if (selected.size == 1) "person" else "people"}",
                    enabled = !sending && selected.isNotEmpty() && message.isNotBlank(),
                    onClick = { confirm = true }
                )
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        )
    }
}
