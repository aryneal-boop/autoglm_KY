package com.example.autoglm.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

// 霓虹色调色板
object NeonColors {
    val bgDark = Color(0xFF0A0E14)
    val bgDarkSecondary = Color(0xFF1A212F)
    val neonCyan = Color(0xFF00FFCC)
    val neonBlue = Color(0xFF2EC7FF)
    val neonPurple = Color(0xFF8B5CFF)
    val neonPink = Color(0xFFFF3D8D)
    val textPrimary = Color.White.copy(alpha = 0.9f)
    val textSecondary = Color.White.copy(alpha = 0.6f)
    val textHint = Color.White.copy(alpha = 0.4f)
    val divider = Color.White.copy(alpha = 0.13f)
}

/**
 * 边框纹理流动按钮（沉浸式慢速）
 * @param durationBase 动画基础时长，默认 4400ms（慢速沉浸感）
 */
@Composable
fun GlowButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = 12.dp,
    borderWidth: Dp = 1.5.dp,
    durationBase: Int = 4400,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val phaseSeed = remember { Random.nextFloat() }
    val durationMs = remember { durationBase + Random.nextInt(0, durationBase / 2) }
    val infinite = rememberInfiniteTransition(label = "glow")
    val flowRaw by infinite.animateFloat(
        initialValue = phaseSeed,
        targetValue = phaseSeed + 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "flow"
    )
    val flow = flowRaw - phaseSeed

    val bg = if (enabled) NeonColors.bgDark.copy(alpha = 0.35f) else NeonColors.bgDark.copy(alpha = 0.18f)
    val fg = if (enabled) NeonColors.textPrimary else NeonColors.textHint

    Box(
        modifier = modifier
            .drawWithCache {
                val r = cornerRadius.toPx()
                val stroke = Stroke(width = borderWidth.toPx())
                val colors = listOf(
                    NeonColors.neonCyan.copy(alpha = 0.08f),
                    NeonColors.neonCyan.copy(alpha = 0.55f),
                    NeonColors.neonBlue.copy(alpha = 0.35f),
                    NeonColors.neonPurple.copy(alpha = 0.30f),
                    NeonColors.neonPink.copy(alpha = 0.28f),
                    NeonColors.neonCyan.copy(alpha = 0.08f),
                )
                onDrawBehind {
                    drawRoundRect(
                        color = bg,
                        cornerRadius = CornerRadius(r, r),
                    )

                    val period = (size.width + size.height) * 0.9f
                    val shift = period * flow
                    val moving = Brush.linearGradient(
                        colors = colors,
                        start = Offset(-period + shift, -period + shift),
                        end = Offset(shift, shift),
                        tileMode = TileMode.Mirror,
                    )
                    drawRoundRect(
                        brush = moving,
                        cornerRadius = CornerRadius(r, r),
                        style = stroke,
                        blendMode = BlendMode.Screen,
                    )
                }
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        MaterialTheme {
            CompositionLocalProvider(LocalContentColor provides fg) {
                content()
            }
        }
    }
}

/**
 * 边框纹理流动面板（用于聊天气泡等）
 * @param durationBase 动画基础时长，默认 5500ms（更慢更沉浸）
 */
@Composable
fun GlowPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 10.dp,
    borderWidth: Dp = 1.dp,
    bgAlpha: Float = 0.25f,
    durationBase: Int = 5500,
    content: @Composable () -> Unit,
) {
    val phaseSeed = remember { Random.nextFloat() }
    val durationMs = remember { durationBase + Random.nextInt(0, durationBase / 2) }
    val infinite = rememberInfiniteTransition(label = "panel-glow")
    val flowRaw by infinite.animateFloat(
        initialValue = phaseSeed,
        targetValue = phaseSeed + 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "flow"
    )
    val flow = flowRaw - phaseSeed

    Box(
        modifier = modifier
            .drawWithCache {
                val r = cornerRadius.toPx()
                val stroke = Stroke(width = borderWidth.toPx())
                val colors = listOf(
                    NeonColors.neonCyan.copy(alpha = 0.06f),
                    NeonColors.neonCyan.copy(alpha = 0.40f),
                    NeonColors.neonBlue.copy(alpha = 0.25f),
                    NeonColors.neonPurple.copy(alpha = 0.22f),
                    NeonColors.neonPink.copy(alpha = 0.20f),
                    NeonColors.neonCyan.copy(alpha = 0.06f),
                )
                onDrawBehind {
                    drawRoundRect(
                        color = NeonColors.bgDark.copy(alpha = bgAlpha),
                        cornerRadius = CornerRadius(r, r),
                    )

                    val period = (size.width + size.height) * 0.9f
                    val shift = period * flow
                    val moving = Brush.linearGradient(
                        colors = colors,
                        start = Offset(-period + shift, -period + shift),
                        end = Offset(shift, shift),
                        tileMode = TileMode.Mirror,
                    )
                    drawRoundRect(
                        brush = moving,
                        cornerRadius = CornerRadius(r, r),
                        style = stroke,
                        blendMode = BlendMode.Screen,
                    )
                }
            }
            .padding(12.dp),
        contentAlignment = Alignment.TopStart
    ) {
        content()
    }
}
