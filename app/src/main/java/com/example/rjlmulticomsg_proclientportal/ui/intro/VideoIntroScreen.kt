package com.example.rjlmulticomsg_proclientportal.ui.intro

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rjlmulticomsg_proclientportal.R
import com.example.rjlmulticomsg_proclientportal.ui.components.ReceptionRadarBackground
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomRed
import com.example.rjlmulticomsg_proclientportal.ui.theme.NeonBlue
import com.example.rjlmulticomsg_proclientportal.ui.theme.NeonBlueBright
import com.example.rjlmulticomsg_proclientportal.ui.theme.NeonBlueDeep
import kotlinx.coroutines.delay

/**
 * Cold-start intro layout (not a video):
 * black screen · rotating reception radar · Multicom logo · neon CLIENT PORTAL.
 */
@Composable
fun VideoIntroScreen(
    onFinished: () -> Unit
) {
    var phase by remember { mutableStateOf(IntroPhase.LogoIn) }
    var exiting by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    fun complete() {
        if (!finished) {
            finished = true
            onFinished()
        }
    }

    val logoAlpha by animateFloatAsState(
        targetValue = if (exiting) 0f else 1f,
        animationSpec = tween(800),
        label = "logoAlpha"
    )
    val logoScale by animateFloatAsState(
        targetValue = when {
            exiting -> 1.04f
            phase.ordinal >= IntroPhase.TitleIn.ordinal -> 1f
            else -> 0.88f
        },
        animationSpec = tween(1000),
        label = "logoScale"
    )
    val titleAlpha by animateFloatAsState(
        targetValue = when {
            exiting -> 0f
            phase.ordinal >= IntroPhase.TitleIn.ordinal -> 1f
            else -> 0f
        },
        animationSpec = tween(500),
        label = "titleAlpha"
    )
    val rootAlpha by animateFloatAsState(
        targetValue = if (exiting) 0f else 1f,
        animationSpec = tween(450),
        label = "rootAlpha"
    )

    val flash = rememberInfiniteTransition(label = "neonFlash")
    val neonPulse by flash.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "neonPulse"
    )
    val neonGlow by flash.animateFloat(
        initialValue = 16f,
        targetValue = 42f,
        animationSpec = infiniteRepeatable(
            animation = tween(360, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "neonGlow"
    )

    LaunchedEffect(Unit) {
        phase = IntroPhase.LogoIn
        delay(900)
        phase = IntroPhase.TitleIn
        delay(2600)
        phase = IntroPhase.Hold
        delay(300)
        exiting = true
    }

    LaunchedEffect(exiting) {
        if (exiting) {
            delay(480)
            complete()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(rootAlpha)
            .background(Color(0xFF050505))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                if (!exiting && !finished) exiting = true
            }
    ) {
        // Rotating radar (layout animation — not a video file)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(logoAlpha * 0.95f),
            contentAlignment = Alignment.Center
        ) {
            ReceptionRadarBackground(
                intensity = logoAlpha,
                scale = 1.05f
            )
        }

        // Multicom SG-PRO logo — dead center
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(logoAlpha),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.rjl_logo),
                contentDescription = "RJL Multicom SG-PRO",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(280.dp)
                    .scale(logoScale)
            )
        }

        // Captions lower half — logo stays centered
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .padding(bottom = 96.dp)
                .alpha(titleAlpha)
        ) {
            NeonClientPortalTitle(
                alpha = neonPulse,
                glowRadius = neonGlow
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "SG‑PRO  ·  ACCESS CONTROL",
                color = MulticomRed.copy(alpha = 0.95f),
                fontSize = 12.sp,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text(
            text = "Tap to skip",
            color = Color(0xFF64748B).copy(alpha = 0.85f * rootAlpha),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp)
        )
    }
}

@Composable
private fun NeonClientPortalTitle(
    alpha: Float,
    glowRadius: Float
) {
    val a = alpha.coerceIn(0f, 1f)
    val base = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 4.sp,
        textAlign = TextAlign.Center
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "CLIENT PORTAL",
            style = base.copy(
                color = NeonBlueDeep.copy(alpha = a * 0.55f),
                shadow = Shadow(
                    color = NeonBlueDeep.copy(alpha = a * 0.85f),
                    offset = Offset.Zero,
                    blurRadius = glowRadius * 1.8f
                )
            ),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Text(
            text = "CLIENT PORTAL",
            style = base.copy(
                color = NeonBlue.copy(alpha = a * 0.9f),
                shadow = Shadow(
                    color = NeonBlue.copy(alpha = a),
                    offset = Offset.Zero,
                    blurRadius = glowRadius
                )
            ),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Text(
            text = "CLIENT PORTAL",
            style = base.copy(
                color = NeonBlueBright.copy(alpha = a),
                shadow = Shadow(
                    color = NeonBlueBright.copy(alpha = a * 0.9f),
                    offset = Offset.Zero,
                    blurRadius = glowRadius * 0.5f
                )
            ),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}

private enum class IntroPhase {
    LogoIn,
    TitleIn,
    Hold
}
