package com.example.timescapedemo

import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Glassmorphism-inspired background that renders a floating translucent panel with
 * subtle lighting, refraction streaks, and a softened blur bloom. The accent color is
 * sampled from the card background to gently echo the user wallpaper.
 */
@Composable
fun LiquidGlassCardBackground(
    modifier: Modifier = Modifier,
    accentColor: Color,
    cornerRadius: Dp = 22.dp,
    blurRadius: Dp = 26.dp,
    borderColor: Color = Color.White.copy(alpha = 0.55f),
    backgroundTint: Color = Color.White.copy(alpha = 0.1f)
) {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val density = LocalDensity.current
    val blurPx = remember(blurRadius, density) { with(density) { blurRadius.toPx() } }
    val radiusPx = remember(cornerRadius, density) { with(density) { cornerRadius.toPx() } }
    val borderWidthPx = remember(density) { with(density) { 1.4.dp.toPx() } }

    Box(
        modifier = modifier.graphicsLayer {
            shadowElevation = 28f
            this.shape = shape
            clip = false
            ambientShadowColor = Color(0x33000000)
            spotShadowColor = Color(0x55000000)
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.shape = shape
                    clip = true
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        this.shape = shape
                        clip = true
                        renderEffect = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                    }
                    .background(backgroundTint)
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val luminousAccent = Color.White.copy(alpha = 0.45f).compositeOver(accentColor.copy(alpha = 0.2f))
                val baseGradient = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.7f),
                        luminousAccent,
                        Color.White.copy(alpha = 0.55f)
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )

                drawRoundRect(
                    brush = baseGradient,
                    cornerRadius = CornerRadius(radiusPx, radiusPx)
                )

                val highlightRadius = min(size.width, size.height) * 0.9f
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.75f), Color.Transparent),
                        center = Offset(size.width * 0.35f, size.height * 0.2f),
                        radius = highlightRadius
                    ),
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                    blendMode = BlendMode.Screen
                )

                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.18f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.22f)
                        ),
                        start = Offset(size.width * 0.1f, size.height * 0.95f),
                        end = Offset(size.width * 0.9f, size.height * 0.05f)
                    ),
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                    alpha = 1f
                )

                drawRoundRect(
                    color = borderColor,
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                    style = Stroke(width = borderWidthPx)
                )
            }
        }
    }
}
