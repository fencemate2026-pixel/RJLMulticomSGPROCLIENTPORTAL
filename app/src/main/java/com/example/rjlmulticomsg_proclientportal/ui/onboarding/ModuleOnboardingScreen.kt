package com.example.rjlmulticomsg_proclientportal.ui.onboarding

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.rjlmulticomsg_proclientportal.domain.model.ModuleType
import com.example.rjlmulticomsg_proclientportal.ui.components.ModuleChip
import com.example.rjlmulticomsg_proclientportal.ui.components.PortalHeader
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomRed
import kotlinx.coroutines.launch

@Composable
fun ModuleOnboardingScreen(
    repository: PortalRepository,
    initial: Set<ModuleType> = emptySet(),
    title: String = "Your modules",
    subtitle: String = "Select the equipment installed at your property",
    confirmLabel: String = "Continue",
    onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        PortalHeader(title = title, subtitle = subtitle)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Choose one or more. You can change this anytime in Settings.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            ModuleType.entries.forEach { module ->
                ModuleChip(
                    module = module,
                    selected = module in selected,
                    onClick = {
                        selected = if (module in selected) selected - module else selected + module
                    }
                )
            }
            if (error != null) {
                Text(error!!, color = MulticomRed, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (selected.isEmpty()) {
                        error = "Select at least one module to continue."
                        return@Button
                    }
                    saving = true
                    error = null
                    scope.launch {
                        repository.saveModules(selected)
                        saving = false
                        onDone()
                    }
                },
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MulticomRed)
            ) {
                Text(if (saving) "Saving…" else confirmLabel, fontWeight = FontWeight.Bold)
            }
        }
    }
}
