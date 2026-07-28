package com.example.rjlmulticomsg_proclientportal.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rjlmulticomsg_proclientportal.data.repo.PortalRepository
import com.example.rjlmulticomsg_proclientportal.domain.model.ModuleType
import com.example.rjlmulticomsg_proclientportal.domain.model.SessionState
import com.example.rjlmulticomsg_proclientportal.ui.components.ModuleChip
import com.example.rjlmulticomsg_proclientportal.ui.components.PortalHeader
import com.example.rjlmulticomsg_proclientportal.ui.components.RjlLogoWordmark
import com.example.rjlmulticomsg_proclientportal.ui.components.SectionCard
import com.example.rjlmulticomsg_proclientportal.ui.components.maskPhone
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomRed
import com.example.rjlmulticomsg_proclientportal.ui.theme.TextMuted
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    repository: PortalRepository,
    session: SessionState,
    onSignedOut: () -> Unit,
    onModulesSaved: () -> Unit,
    onOpenHelp: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var selected by remember {
        mutableStateOf(session.enabledModules.toSet())
    }
    var currentPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var confirmPw by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        PortalHeader(
            title = "Settings",
            subtitle = session.user?.email
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard("Your property (set by RJL)") {
                Text("Site: ${session.account?.siteName.orEmpty()}", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                val loc = listOfNotNull(
                    session.account?.address?.takeIf { it.isNotBlank() },
                    session.account?.region?.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                if (loc.isNotBlank()) {
                    Text(loc, color = TextMuted, fontSize = 12.sp)
                }
                Text("Portal / Tailscale: ${session.account?.portalBaseUrl.orEmpty()}", color = TextMuted, fontSize = 12.sp)
                Text("GSM number: ${maskPhone(session.account?.gsmNumber.orEmpty())}", color = TextMuted, fontSize = 12.sp)
                Text(
                    if (repository.firebaseAvailable) "Auth: Firebase ready (au.com.rjl.onlineportal)"
                    else "Auth: local only",
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            SectionCard("Help") {
                Text(
                    "Ask Grok how to open the gate, manage GSM callers, modules, people, and more.",
                    color = TextMuted,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onOpenHelp,
                    colors = ButtonDefaults.buttonColors(containerColor = MulticomRed),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Ask Grok (AI assistant)", fontWeight = FontWeight.Bold)
                }
            }

            SectionCard("Modules on this phone") {
                Text(
                    "Show only the equipment you have at this property.",
                    color = TextMuted,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                ModuleType.entries.forEach { module ->
                    ModuleChip(
                        module = module,
                        selected = module in selected,
                        onClick = {
                            selected = if (module in selected) selected - module else selected + module
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Button(
                    onClick = {
                        if (selected.isEmpty()) {
                            error = "Keep at least one module enabled."
                            return@Button
                        }
                        scope.launch {
                            repository.saveModules(selected)
                            message = "Modules updated."
                            error = null
                            onModulesSaved()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MulticomRed),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save modules", fontWeight = FontWeight.Bold)
                }
            }

            SectionCard("Change password") {
                OutlinedTextField(
                    value = currentPw,
                    onValueChange = { currentPw = it },
                    label = { Text("Current password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newPw,
                    onValueChange = { newPw = it },
                    label = { Text("New password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmPw,
                    onValueChange = { confirmPw = it },
                    label = { Text("Confirm new password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (newPw != confirmPw) {
                            error = "New passwords do not match."
                            return@Button
                        }
                        scope.launch {
                            repository.changePassword(currentPw, newPw)
                                .onSuccess {
                                    message = "Password changed."
                                    error = null
                                    currentPw = ""
                                    newPw = ""
                                    confirmPw = ""
                                }
                                .onFailure {
                                    error = it.message
                                    message = null
                                }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MulticomRed),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Update password", fontWeight = FontWeight.Bold)
                }
            }

            if (error != null) Text(error!!, color = MulticomRed)
            if (message != null) Text(message!!, color = TextMuted)

            OutlinedButton(
                onClick = {
                    scope.launch {
                        repository.logout()
                        onSignedOut()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign out", color = MulticomRed, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RjlLogoWordmark(height = 40.dp)
                Text(
                    text = "Client Portal · RJL Commercial",
                    color = TextMuted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
