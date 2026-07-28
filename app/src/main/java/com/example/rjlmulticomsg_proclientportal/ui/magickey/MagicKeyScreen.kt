package com.example.rjlmulticomsg_proclientportal.ui.magickey

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.rjlmulticomsg_proclientportal.ui.components.MulticomPage
import com.example.rjlmulticomsg_proclientportal.ui.components.MulticomPrimaryButton
import com.example.rjlmulticomsg_proclientportal.ui.components.MulticomTopBar
import com.example.rjlmulticomsg_proclientportal.ui.components.SectionCard
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomPageBg
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomRed
import com.example.rjlmulticomsg_proclientportal.ui.theme.OpenGreenDark
import com.example.rjlmulticomsg_proclientportal.ui.theme.TextMuted

@Composable
fun MagicKeyScreen(
    onBack: () -> Unit,
    viewModel: MagicKeyViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    var key by remember { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    val loading = state == MagicKeyUiState.Loading

    Column(Modifier.fillMaxSize().background(MulticomPageBg)) {
        MulticomTopBar(title = "Magic Key Access", deviceName = "RJL", onBack = onBack)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            MulticomPage {
                Text(
                    "RJL Magic Keys",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    "Enter the six-digit key supplied through the secure RJL Commercial client portal.",
                    color = TextMuted
                )

                SectionCard("Verify access") {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { newValue ->
                            key = newValue.filter(Char::isDigit).take(6)
                        },
                        enabled = !loading,
                        label = { Text("Six-digit Magic Key") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation =
                            if (visible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { visible = !visible }) {
                                Icon(
                                    if (visible) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    if (visible) "Hide key" else "Show key"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(14.dp))

                    MulticomPrimaryButton(
                        label = if (loading) "Verifying…" else "Verify Access",
                        enabled = !loading && key.length == 6,
                        onClick = { viewModel.verify(key) }
                    )

                    if (loading) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(color = MulticomRed)
                        }
                    }
                }

                when (val result = state) {
                    is MagicKeyUiState.Granted -> SectionCard("Access granted") {
                        Text(
                            "Access granted",
                            color = OpenGreenDark,
                            fontWeight = FontWeight.Bold
                        )
                        Text("Property: ${result.result.site ?: "337 Settlement Road, Thomastown"}")
                        Text("Tenant: ${result.result.tenantId ?: "—"}")
                        Text("Valid until: ${result.result.expiresAt ?: "—"}")
                    }

                    is MagicKeyUiState.Error -> SectionCard("Verification result") {
                        Text(
                            result.message,
                            color = MulticomRed,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    else -> Unit
                }
            }
        }
    }
}
