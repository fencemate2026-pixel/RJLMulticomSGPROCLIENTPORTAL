package com.example.rjlmulticomsg_proclientportal.ui.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rjlmulticomsg_proclientportal.data.repo.PortalRepository
import com.example.rjlmulticomsg_proclientportal.domain.model.SessionState
import com.example.rjlmulticomsg_proclientportal.domain.model.UserRole
import androidx.compose.foundation.background
import com.example.rjlmulticomsg_proclientportal.ui.components.EmptyHint
import com.example.rjlmulticomsg_proclientportal.ui.components.MulticomPrimaryButton
import com.example.rjlmulticomsg_proclientportal.ui.components.MulticomTopBar
import com.example.rjlmulticomsg_proclientportal.ui.components.SectionCard
import com.example.rjlmulticomsg_proclientportal.ui.components.StatusPill
import com.example.rjlmulticomsg_proclientportal.ui.components.formatDeviceTelephone
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomPageBg
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomRed
import com.example.rjlmulticomsg_proclientportal.ui.theme.TextMuted
import kotlinx.coroutines.launch

@Composable
fun PeopleScreen(
    repository: PortalRepository,
    session: SessionState,
    onBack: (() -> Unit)? = null
) {
    val accountId = session.account?.id.orEmpty()
    val people by repository.observePeople(accountId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val isOwner = session.isOwner

    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MulticomPageBg)
    ) {
        MulticomTopBar(
            title = "Residents",
            deviceTelephone = formatDeviceTelephone(session.account?.gsmNumber.orEmpty())
                .takeIf { session.account?.gsmNumber?.isNotBlank() == true },
            deviceName = session.account?.siteName?.ifBlank { "Multicom" } ?: "Multicom",
            onBack = onBack
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard("People on this property") {
                if (people.isEmpty()) {
                    EmptyHint("No users.")
                } else {
                    people.forEach { user ->
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(user.displayName, fontWeight = FontWeight.Bold)
                                    Text(user.email, color = TextMuted, fontSize = 12.sp)
                                }
                                StatusPill(
                                    user.role.name,
                                    positive = user.role == UserRole.OWNER
                                )
                            }
                            if (isOwner && user.role != UserRole.OWNER) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        repository.removeMember(user.id)
                                            .onSuccess {
                                                message = "Removed ${user.email}"
                                                error = null
                                            }
                                            .onFailure {
                                                error = it.message
                                            }
                                    }
                                }) {
                                    Text("Remove access", color = MulticomRed)
                                }
                            }
                        }
                    }
                }
            }

            if (isOwner) {
                SectionCard("Add family or friend") {
                    Text(
                        "They get their own username and password, linked to the same property and modules as you.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Full name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Username / Email") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Temporary password") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    if (error != null) {
                        Text(error!!, color = MulticomRed, modifier = Modifier.padding(top = 8.dp))
                    }
                    if (message != null) {
                        Text(message!!, color = TextMuted, modifier = Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                repository.addFamilyMember(name, email, password)
                                    .onSuccess {
                                        message = "Added ${it.email}. Share the temporary password with them."
                                        error = null
                                        name = ""
                                        email = ""
                                        password = ""
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
                        Text("Create access", fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Text(
                    "Only the property owner can add or remove family and friends.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        }
    }
}
