package com.example.rjlmulticomsg_proclientportal.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rjlmulticomsg_proclientportal.security.AppLockManager
import com.example.rjlmulticomsg_proclientportal.security.AppLockState
import com.example.rjlmulticomsg_proclientportal.security.PinVerifyResult
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomRed

@Composable
fun PinGateScreen(
    lockManager: AppLockManager,
    displayName: String,
    onRequireLogin: () -> Unit
) {
    val lockState = lockManager.state.value
    var pin by remember(lockState) { mutableStateOf("") }
    var firstPin by remember(lockState) { mutableStateOf<String?>(null) }
    var error by remember(lockState) { mutableStateOf<String?>(null) }

    fun submit(candidate: String) {
        if (candidate.length != AppLockManager.PIN_LENGTH) return
        if (lockState == AppLockState.NEEDS_SETUP) {
            if (firstPin == null) {
                firstPin = candidate
                pin = ""
                error = null
            } else if (candidate == firstPin) {
                if (!lockManager.setupPin(candidate)) {
                    error = "PIN setup failed. Sign in again."
                    onRequireLogin()
                }
            } else {
                firstPin = null
                pin = ""
                error = "PINs did not match. Start again."
            }
            return
        }

        when (lockManager.verifyPin(candidate)) {
            PinVerifyResult.SUCCESS -> Unit
            PinVerifyResult.INCORRECT -> {
                pin = ""
                val remaining = AppLockManager.MAX_ATTEMPTS - lockManager.failedAttempts
                error = "Incorrect PIN. $remaining attempt${if (remaining == 1) "" else "s"} left."
            }
            PinVerifyResult.REQUIRE_LOGIN -> {
                pin = ""
                error = "Too many incorrect attempts. Sign in again."
                onRequireLogin()
            }
        }
    }

    LaunchedEffect(pin) {
        if (pin.length == AppLockManager.PIN_LENGTH) submit(pin)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07101D))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = MulticomRed.copy(alpha = 0.16f),
                modifier = Modifier.size(78.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = MulticomRed,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = when {
                    lockState == AppLockState.LOCKED -> "Enter your PIN"
                    firstPin == null -> "Create a 4-digit PIN"
                    else -> "Confirm your PIN"
                },
                color = Color.White,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    lockState == AppLockState.LOCKED ->
                        "Unlock SG-PRO for ${displayName.ifBlank { "this account" }}"
                    firstPin == null ->
                        "You’ll use this PIN whenever the app opens."
                    else -> "Enter the same four digits again."
                },
                color = Color(0xFFB5C0CF),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(AppLockManager.PIN_LENGTH) { index ->
                    Surface(
                        shape = CircleShape,
                        color = if (index < pin.length) MulticomRed else Color(0xFF263345),
                        modifier = Modifier.size(18.dp)
                    ) {}
                }
            }
            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(
                    it,
                    color = Color(0xFFFF7474),
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9")
                ).forEach { row ->
                    PinKeyRow(row) { digit ->
                        if (pin.length < AppLockManager.PIN_LENGTH) {
                            pin += digit
                            error = null
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.size(68.dp))
                    PinKey("0") {
                        if (pin.length < AppLockManager.PIN_LENGTH) {
                            pin += "0"
                            error = null
                        }
                    }
                    IconButton(
                        onClick = {
                            if (pin.isNotEmpty()) pin = pin.dropLast(1)
                            error = null
                        },
                        modifier = Modifier.size(68.dp)
                    ) {
                        Icon(
                            Icons.Default.Backspace,
                            contentDescription = "Delete digit",
                            tint = Color.White
                        )
                    }
                }
            }
            if (lockState == AppLockState.LOCKED) {
                Spacer(Modifier.height(18.dp))
                TextButton(
                    onClick = {
                        lockManager.forgetPin()
                        onRequireLogin()
                    }
                ) {
                    Text("Forgot PIN? Sign in again", color = MulticomRed)
                }
            }
        }
    }
}

@Composable
private fun PinKeyRow(values: List<String>, onDigit: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        values.forEach { value -> PinKey(value) { onDigit(value) } }
    }
}

@Composable
private fun PinKey(value: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color(0xFF152235),
        modifier = Modifier.size(68.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                value,
                color = Color.White,
                fontSize = 25.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
