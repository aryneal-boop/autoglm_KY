package com.example.autoglm.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.sin

@androidx.compose.runtime.Composable
fun NeonLiquidBackground(
    modifier: Modifier = Modifier,
    hueShiftDegrees: Float = 0f,
) {
    val infinite = rememberInfiniteTransition(label = "liquid")
    val t by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20_000, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "t"
    )

    val baseA = NeonColors.bgDark
    val baseB = NeonColors.bgDarkSecondary

    val blobColors = remember(hueShiftDegrees) {
        listOf(
            shiftHue(NeonColors.neonBlue, hueShiftDegrees),
            shiftHue(NeonColors.neonPurple, hueShiftDegrees * 0.8f),
            shiftHue(NeonColors.neonCyan, hueShiftDegrees * 0.6f),
            shiftHue(NeonColors.neonPink, hueShiftDegrees * 0.4f),
        )
    }

    Box(
        modifier = modifier.drawWithCache {
            val w = size.width
            val h = size.height

            val bg = Brush.linearGradient(
                colors = listOf(baseA, baseB),
                start = Offset(0f, 0f),
                end = Offset(w, h),
            )

            val blobs = List(6) { i ->
                val s = (i + 1) * 0.13f
                val r = (kotlin.math.min(w, h) * (0.22f + i * 0.045f)).coerceAtMost(kotlin.math.min(w, h) * 0.55f)
                Triple(s, r, blobColors[i % blobColors.size])
            }

            onDrawBehind {
                drawRect(bg)

                val time = t
                for ((index, blob) in blobs.withIndex()) {
                    val speed = blob.first
                    val radius = blob.second
                    val color = blob.third

                    val phase = time * (6.2831853f) * (0.35f + speed)
                    val px = w * (0.5f + 0.28f * cos((phase + index.toFloat()).toDouble()).toFloat())
                    val py = h * (0.5f + 0.24f * sin((phase * 1.15f + index.toFloat() * 0.7f).toDouble()).toFloat())

                    val brush = Brush.radialGradient(
                        colors = listOf(
                            color.copy(alpha = 0.55f),
                            color.copy(alpha = 0.18f),
                            Color.Transparent,
                        ),
                        center = Offset(px, py),
                        radius = radius,
                    )

                    drawRect(
                        brush = brush,
                        topLeft = Offset(0f, 0f),
                        size = Size(w, h),
                        blendMode = BlendMode.Screen,
                    )
                }
            }
        }
    )
}

private fun shiftHue(color: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[0] = (hsv[0] + degrees) % 360f
    val argb = android.graphics.Color.HSVToColor(
        (color.alpha * 255f).toInt().coerceIn(0, 255),
        hsv
    )
    return Color(argb)
}
