package com.example.rjlmulticomsg_proclientportal.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rjlmulticomsg_proclientportal.data.repo.PortalRepository
import com.example.rjlmulticomsg_proclientportal.ui.components.EmptyHint
import com.example.rjlmulticomsg_proclientportal.ui.components.PortalHeader
import com.example.rjlmulticomsg_proclientportal.ui.components.StatusPill
import com.example.rjlmulticomsg_proclientportal.ui.theme.TextMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActionLogScreen(
    repository: PortalRepository,
    accountId: String,
    onBack: (() -> Unit)? = null
) {
    val logs by repository.observeLogs(accountId).collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize()) {
        PortalHeader(
            title = "Action log",
            subtitle = "Every open, change and login from this property",
            onBack = onBack
        )
        if (logs.isEmpty()) {
            Column(Modifier.padding(16.dp)) {
                EmptyHint("No actions recorded yet.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(logs, key = { it.id }) { entry ->
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(entry.action, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            StatusPill(
                                if (entry.success) "OK" else "FAIL",
                                positive = entry.success
                            )
                        }
                        Text(entry.detail, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        Text(
                            "${entry.userEmail} · ${formatTs(entry.timestamp)}",
                            color = TextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun formatTs(ms: Long): String =
    SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.getDefault()).format(Date(ms))
