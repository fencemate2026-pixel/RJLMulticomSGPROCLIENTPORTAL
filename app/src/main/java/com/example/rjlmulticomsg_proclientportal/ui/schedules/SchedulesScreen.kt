package com.example.rjlmulticomsg_proclientportal.ui.schedules

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rjlmulticomsg_proclientportal.data.repo.PortalRepository
import com.example.rjlmulticomsg_proclientportal.domain.model.GateSchedule
import com.example.rjlmulticomsg_proclientportal.domain.model.ScheduleScope
import com.example.rjlmulticomsg_proclientportal.ui.components.DayChips
import com.example.rjlmulticomsg_proclientportal.ui.components.EmptyHint
import com.example.rjlmulticomsg_proclientportal.ui.components.PortalHeader
import com.example.rjlmulticomsg_proclientportal.ui.components.SectionCard
import com.example.rjlmulticomsg_proclientportal.ui.components.StatusPill
import com.example.rjlmulticomsg_proclientportal.ui.components.formatDayList
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomRed
import com.example.rjlmulticomsg_proclientportal.ui.theme.TextMuted
import kotlinx.coroutines.launch

@Composable
fun SchedulesScreen(
    repository: PortalRepository,
    accountId: String,
    onBack: () -> Unit
) {
    val schedules by repository.observeSchedules(accountId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var openTime by remember { mutableStateOf("09:00") }
    var closeTime by remember { mutableStateOf("17:00") }
    var days by remember { mutableStateOf(setOf(0, 1, 2, 3, 4)) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        PortalHeader(
            title = "Schedules",
            subtitle = "Timed access windows for cleaners, deliveries, etc.",
            onBack = onBack
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "These schedules are stored on your account. Hardware hold-open may also need to be set on the site portal — RJL can sync them.",
                color = TextMuted,
                fontSize = 12.sp
            )

            SectionCard("Existing schedules") {
                if (schedules.isEmpty()) {
                    EmptyHint("No schedules yet.")
                } else {
                    schedules.forEach { s ->
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(s.name, fontWeight = FontWeight.Bold)
                                StatusPill(
                                    if (s.enabled) "ON" else "OFF",
                                    positive = s.enabled
                                )
                            }
                            Text(
                                "${formatDayList(s.days)} · ${s.openTime}–${s.closeTime} · ${s.scope.name}",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        repository.toggleSchedule(s.id, !s.enabled, s.name)
                                    }
                                }) {
                                    Text(if (s.enabled) "Disable" else "Enable")
                                }
                                OutlinedButton(onClick = {
                                    scope.launch { repository.deleteSchedule(s.id, s.name) }
                                }) {
                                    Text("Remove", color = MulticomRed)
                                }
                            }
                        }
                    }
                }
            }

            SectionCard("Add schedule") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (e.g. Cleaners)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Text("Days", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                DayChips(selected = days) { d ->
                    days = if (d in days) days - d else days + d
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = openTime,
                        onValueChange = { openTime = it },
                        label = { Text("Open HH:mm") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = closeTime,
                        onValueChange = { closeTime = it },
                        label = { Text("Close HH:mm") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                if (error != null) {
                    Text(error!!, color = MulticomRed, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (name.isBlank() || days.isEmpty()) {
                            error = "Name and at least one day are required."
                            return@Button
                        }
                        if (!openTime.matches(Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")) ||
                            !closeTime.matches(Regex("^([01]?\\d|2[0-3]):[0-5]\\d$"))
                        ) {
                            error = "Times must be HH:mm (24h)."
                            return@Button
                        }
                        error = null
                        scope.launch {
                            repository.addSchedule(
                                GateSchedule(
                                    accountId = accountId,
                                    name = name.trim(),
                                    days = days.sorted(),
                                    openTime = openTime,
                                    closeTime = closeTime,
                                    scope = ScheduleScope.BOTH
                                )
                            )
                            name = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MulticomRed),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add schedule", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
