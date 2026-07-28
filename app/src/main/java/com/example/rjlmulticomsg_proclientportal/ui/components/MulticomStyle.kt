package com.example.rjlmulticomsg_proclientportal.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomBarGrey
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomCardBorder
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomPageBg
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomRed
import com.example.rjlmulticomsg_proclientportal.ui.theme.Navy
import com.example.rjlmulticomsg_proclientportal.ui.theme.TextDark
import com.example.rjlmulticomsg_proclientportal.ui.theme.TextMuted

/** Blue gradient used on every top header across the app. */
val HeaderBlueBrush = Brush.horizontalGradient(
    listOf(Navy, Color(0xFF21407A), Color(0xFF1A4A8C))
)

/**
 * Multicom menu layout with RJL blue header theme throughout the app.
 */
@Composable
fun MulticomTopBar(
    title: String,
    deviceTelephone: String? = null,
    deviceName: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HeaderBlueBrush)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (onBack != null) {
                Text(
                    text = "←",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(end = 10.dp)
                )
            }
            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f)
            )
            trailing?.invoke()
        }
        if (!deviceTelephone.isNullOrBlank() || !deviceName.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            if (!deviceTelephone.isNullOrBlank()) {
                Text(
                    text = "Device Telephone Number: $deviceTelephone",
                    color = Color(0xFFB9C4D6),
                    fontSize = 12.sp
                )
            }
            if (!deviceName.isNullOrBlank()) {
                Text(
                    text = "Device: $deviceName",
                    color = Color(0xFFB9C4D6),
                    fontSize = 12.sp
                )
            }
        }
        // Red accent bar under blue header (brand stripe)
        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .height(3.dp)
                .background(MulticomRed)
        )
    }
}

/** Large white menu row used on Multicom Programming / Home screens. */
@Composable
fun MulticomMenuButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .border(1.dp, MulticomCardBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MulticomRed,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                color = TextDark,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** Full-width red primary action (e.g. Add New User / Open Gate). */
@Composable
fun MulticomPrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MulticomRed,
            contentColor = Color.White,
            disabledContainerColor = MulticomRed.copy(alpha = 0.4f)
        )
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
fun MulticomPage(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MulticomPageBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
fun MulticomListRow(
    label: String,
    onClick: () -> Unit,
    trailing: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(1.dp, MulticomCardBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = TextDark,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
        if (!trailing.isNullOrBlank()) {
            Text(trailing, color = MulticomBarGrey, fontSize = 13.sp)
        }
    }
}

fun formatDeviceTelephone(raw: String): String {
    if (raw.isBlank()) return "—"
    return when (
        val r = com.example.rjlmulticomsg_proclientportal.domain.phone.PhoneNumberNormalizer
            .normalize(raw)
    ) {
        is com.example.rjlmulticomsg_proclientportal.domain.phone.PhoneNumberNormalizer.Result.Valid ->
            com.example.rjlmulticomsg_proclientportal.domain.phone.PhoneNumberNormalizer
                .formatDisplay(r.e164)
        is com.example.rjlmulticomsg_proclientportal.domain.phone.PhoneNumberNormalizer.Result.Invalid ->
            raw // show raw so RJL can spot invalid provisioned numbers
    }
}
