package com.example.rjlmulticomsg_proclientportal.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.example.rjlmulticomsg_proclientportal.ui.theme.NeonBlue
import com.example.rjlmulticomsg_proclientportal.ui.theme.NeonBlueBright
import com.example.rjlmulticomsg_proclientportal.ui.theme.NeonBlueDeep
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen rotating radar — expanding range rings + sweep like locking onto reception.
 */
@Composable
fun ReceptionRadarBackground(
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    /** How large the radar is relative to the shorter screen edge (0.5–1.2). */
    scale: Float = 0.95f
) {
    val i = intensity.coerceIn(0f, 1f)
    val radar = rememberInfiniteTransition(label = "receptionRadar")

    val wave1 by radar.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave1"
    )
    val wave2 by radar.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave2"
    )
    val wave3 by radar.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave3"
    )
    val sweepAngle by radar.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )
    // Subtle ping blips that “find signal”
    val blipPhase by radar.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "blip"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxR = size.minDimension * 0.5f * scale.coerceIn(0.4f, 1.3f)
        val center = Offset(cx, cy)

        // Soft reception glow under the disc
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    NeonBlue.copy(alpha = 0.22f * i),
                    NeonBlueDeep.copy(alpha = 0.08f * i),
                    Color.Transparent
                ),
                center = center,
                radius = maxR * 0.72f
            ),
            radius = maxR * 0.72f,
            center = center
        )

        // Static range rings
        for (n in 1..5) {
            val r = maxR * (n / 5.2f)
            drawCircle(
                color = NeonBlue.copy(alpha = (0.10f + n * 0.015f) * i),
                radius = r,
                center = center,
                style = Stroke(width = 1.5f)
            )
        }

        // Crosshair
        drawLine(
            color = NeonBlue.copy(alpha = 0.18f * i),
            start = Offset(cx - maxR * 0.95f, cy),
            end = Offset(cx + maxR * 0.95f, cy),
            strokeWidth = 1.2f
        )
        drawLine(
            color = NeonBlue.copy(alpha = 0.18f * i),
            start = Offset(cx, cy - maxR * 0.95f),
            end = Offset(cx, cy + maxR * 0.95f),
            strokeWidth = 1.2f
        )

        // Expanding “searching for signal” rings
        fun pulseRing(t: Float) {
            val r = maxR * (0.08f + t * 0.92f)
            val a = ((1f - t) * 0.62f * i).coerceIn(0f, 1f)
            drawCircle(
                color = NeonBlueBright.copy(alpha = a),
                radius = r,
                center = center,
                style = Stroke(width = 3.2f * (1f - t * 0.5f))
            )
            drawCircle(
                color = NeonBlue.copy(alpha = a * 0.32f),
                radius = r + 10f,
                center = center,
                style = Stroke(width = 8f * (1f - t))
            )
        }
        pulseRing(wave1)
        pulseRing(wave2)
        pulseRing(wave3)

        // Rotating sweep wedge (reception beam)
        rotate(degrees = sweepAngle, pivot = center) {
            val sweepLen = maxR * 0.96f
            for (deg in 0..56 step 2) {
                val rad = Math.toRadians(deg.toDouble() - 28.0)
                val fade = 1f - (deg / 56f)
                val ex = cx + sweepLen * cos(rad).toFloat()
                val ey = cy + sweepLen * sin(rad).toFloat()
                drawLine(
                    color = NeonBlueBright.copy(alpha = 0.10f * fade * i),
                    start = center,
                    end = Offset(ex, ey),
                    strokeWidth = 3.4f,
                    cap = StrokeCap.Round
                )
            }
            drawLine(
                color = NeonBlueBright.copy(alpha = 0.75f * i),
                start = center,
                end = Offset(cx + sweepLen, cy),
                strokeWidth = 2.8f,
                cap = StrokeCap.Round
            )
        }

        // Signal blips (fixed polar positions that pulse as the sweep “hits” them)
        val blips = listOf(
            38f to 0.42f,
            125f to 0.68f,
            210f to 0.55f,
            295f to 0.78f,
            340f to 0.35f
        )
        blips.forEachIndexed { index, (angleDeg, dist) ->
            val angle = Math.toRadians(angleDeg.toDouble())
            val bx = cx + maxR * dist * cos(angle).toFloat()
            val by = cy + maxR * dist * sin(angle).toFloat()
            val phase = ((blipPhase + index * 0.17f) % 1f)
            val pulse = (sin(phase * Math.PI * 2).toFloat() * 0.5f + 0.5f)
            val a = (0.25f + 0.55f * pulse) * i
            drawCircle(
                color = NeonBlueBright.copy(alpha = a),
                radius = 4.5f + 3f * pulse,
                center = Offset(bx, by)
            )
            drawCircle(
                color = NeonBlue.copy(alpha = a * 0.35f),
                radius = 12f + 6f * pulse,
                center = Offset(bx, by),
                style = Stroke(width = 1.6f)
            )
        }
    }
}
