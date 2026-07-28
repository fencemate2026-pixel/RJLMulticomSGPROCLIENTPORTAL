package com.example.rjlmulticomsg_proclientportal.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rjlmulticomsg_proclientportal.R
import com.example.rjlmulticomsg_proclientportal.domain.model.ModuleType
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomRed
import com.example.rjlmulticomsg_proclientportal.ui.theme.Navy
import com.example.rjlmulticomsg_proclientportal.ui.theme.OpenGreen
import com.example.rjlmulticomsg_proclientportal.ui.theme.OpenGreenDark
import com.example.rjlmulticomsg_proclientportal.ui.theme.TextMuted

/** Compact RJL Multicom badge used in headers and chrome. */
@Composable
fun RjlLogoMark(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    Image(
        painter = painterResource(R.drawable.rjl_logo),
        contentDescription = "RJL Multicom SG-PRO",
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size)
    )
}

/** Full RJL Multicom SG-PRO logo for login / intro branding. */
@Composable
fun RjlLogoWordmark(
    modifier: Modifier = Modifier,
    height: Dp = 96.dp
) {
    Image(
        painter = painterResource(R.drawable.rjl_logo),
        contentDescription = "RJL Multicom SG-PRO",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .height(height)
            .fillMaxWidth()
    )
}

@Composable
fun PortalHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(listOf(Navy, Color(0xFF21407A)))
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        if (onBack != null) {
            Text(
                text = "← Back",
                color = Color(0xFFB9C4D6),
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(bottom = 6.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            RjlLogoMark(size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = Color(0xFFB9C4D6),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .height(3.dp)
                .background(MulticomRed)
        )
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title.uppercase(),
                color = MulticomRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            content()
        }
    }
}

@Composable
fun OpenGateButton(
    label: String = "Open Gate",
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = OpenGreenDark,
            contentColor = Color.White,
            disabledContainerColor = OpenGreenDark.copy(alpha = 0.4f)
        )
    ) {
        Text(
            text = if (loading) "Opening…" else "⬆  $label",
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp
        )
    }
}

@Composable
fun ModuleChip(
    module: ModuleType,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) MulticomRed else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(
                width = 1.dp,
                color = if (selected) MulticomRed else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (selected) Color.White else TextMuted)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(module.label, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                when (module) {
                    ModuleType.WIFI -> "Remote open via Tailscale / portal"
                    ModuleType.GSM -> "Free missed-call open"
                    ModuleType.RFID -> "Fobs & cards"
                    ModuleType.LPR -> "Number plates"
                },
                color = if (selected) Color.White.copy(alpha = 0.85f) else TextMuted,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun StatusPill(text: String, positive: Boolean = false) {
    Text(
        text = text,
        color = if (positive) OpenGreenDark else MulticomRed,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (positive) OpenGreen.copy(alpha = 0.15f)
                else MulticomRed.copy(alpha = 0.12f)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
fun EmptyHint(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 13.sp,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
fun DayChips(
    selected: Set<Int>,
    onToggle: (Int) -> Unit
) {
    val labels = listOf("M", "T", "W", "T", "F", "S", "S")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            val on = index in selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (on) MulticomRed.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .border(
                        1.dp,
                        if (on) MulticomRed else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onToggle(index) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = if (on) MulticomRed else TextMuted
                )
            }
        }
    }
}

fun maskPhone(number: String): String {
    val digits = number.filter { it.isDigit() }
    if (digits.length <= 4) return "••••"
    return "••••" + digits.takeLast(4)
}

fun formatDayList(days: List<Int>): String {
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    if (days.isEmpty()) return "No days"
    if (days.size == 7) return "Every day"
    return days.sorted().joinToString(", ") { names.getOrElse(it) { "?" } }
}
