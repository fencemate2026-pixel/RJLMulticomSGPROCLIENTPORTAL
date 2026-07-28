package com.example.rjlmulticomsg_proclientportal.ui.modules

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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rjlmulticomsg_proclientportal.data.repo.PortalRepository
import com.example.rjlmulticomsg_proclientportal.domain.model.SessionState
import com.example.rjlmulticomsg_proclientportal.ui.components.EmptyHint
import com.example.rjlmulticomsg_proclientportal.ui.components.OpenGateButton
import com.example.rjlmulticomsg_proclientportal.ui.components.PortalHeader
import com.example.rjlmulticomsg_proclientportal.ui.components.SectionCard
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomRed
import com.example.rjlmulticomsg_proclientportal.ui.theme.TextMuted
import kotlinx.coroutines.launch

@Composable
fun WifiModuleScreen(
    repository: PortalRepository,
    session: SessionState,
    onBack: () -> Unit,
    onSchedules: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var loading by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        PortalHeader("Wi‑Fi Module", "Tailscale remote open", onBack)
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionCard("Open gate") {
                    Text(
                        "Sends a pulse open to your site portal over Tailscale.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    Text(
                        session.account?.portalBaseUrl.orEmpty(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    OpenGateButton(loading = loading) {
                        scope.launch {
                            loading = true
                            val r = repository.openWifiGate()
                            loading = false
                            snackbar.showSnackbar(r.message)
                        }
                    }
                }
                SectionCard("Schedules") {
                    Text("Set cleaner / visitor windows for this property.", color = TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onSchedules,
                        colors = ButtonDefaults.buttonColors(containerColor = MulticomRed),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Manage schedules") }
                }
            }
            SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
        }
    }
}

@Composable
fun RfidModuleScreen(
    repository: PortalRepository,
    accountId: String,
    onBack: () -> Unit
) {
    val tags by repository.observeRfid(accountId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        PortalHeader("RFID Module", "Fobs & cards for this property", onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "List is stored on your account. Hardware sync arrives when the site bridge is connected.",
                color = TextMuted,
                fontSize = 12.sp
            )
            SectionCard("Registered tags") {
                if (tags.isEmpty()) EmptyHint("No RFID tags yet.")
                tags.forEach { tag ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(tag.label, fontWeight = FontWeight.Bold)
                            Text(tag.tagCode, color = TextMuted, fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = {
                            scope.launch { repository.deleteRfid(tag.id, tag.label) }
                        }) { Text("Remove", color = MulticomRed) }
                    }
                }
            }
            SectionCard("Add tag") {
                OutlinedTextField(label, { label = it }, label = { Text("Label") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(code, { code = it }, label = { Text("Tag / fob code") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (label.isNotBlank() && code.isNotBlank()) {
                            scope.launch {
                                repository.addRfid(label, code)
                                label = ""
                                code = ""
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MulticomRed),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add RFID tag") }
            }
        }
    }
}

@Composable
fun LprModuleScreen(
    repository: PortalRepository,
    accountId: String,
    onBack: () -> Unit
) {
    val plates by repository.observeLpr(accountId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    var plate by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        PortalHeader("License Plate Recognition", "Authorised plates", onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "List is stored on your account. Camera / LPR hardware sync is a future bridge step.",
                color = TextMuted,
                fontSize = 12.sp
            )
            SectionCard("Authorised plates") {
                if (plates.isEmpty()) EmptyHint("No plates yet.")
                plates.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(item.plate, fontWeight = FontWeight.Bold)
                            Text(item.label, color = TextMuted, fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = {
                            scope.launch { repository.deleteLpr(item.id, item.plate) }
                        }) { Text("Remove", color = MulticomRed) }
                    }
                }
            }
            SectionCard("Add plate") {
                OutlinedTextField(label, { label = it }, label = { Text("Label (e.g. Mum's car)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(plate, { plate = it }, label = { Text("Plate number") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (label.isNotBlank() && plate.isNotBlank()) {
                            scope.launch {
                                repository.addLpr(label, plate)
                                label = ""
                                plate = ""
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MulticomRed),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add plate") }
            }
        }
    }
}
