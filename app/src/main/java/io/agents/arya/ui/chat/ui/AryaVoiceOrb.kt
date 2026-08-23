package io.agents.arya.ui.chat.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import io.agents.arya.ui.theme.AryaDimens
import io.agents.arya.ui.theme.LocalAryaPalette

@Composable
fun AryaVoiceOrb(
    listening: Boolean,
    enabled: Boolean = true,
    hero: Boolean = false,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onHoldCancel: () -> Unit = onHoldEnd,
    modifier: Modifier = Modifier,
    size: Dp = if (hero) AryaDimens.orbHero else AryaDimens.orb,
) {
    var pressed by remember { mutableStateOf(false) }
    val palette = LocalAryaPalette.current
    val pulse = rememberInfiniteTransition(label = "orb")
    val scale by pulse.animateFloat(
        initialValue = 0.92f,
        targetValue = if (listening) 1.08f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (listening) 900 else 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb-scale",
    )
    val glow by pulse.animateFloat(
        initialValue = 0.25f,
        targetValue = if (listening) 0.7f else 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb-glow",
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(if (pressed) 0.94f else 1f)
            .pointerInput(enabled, listening) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        val downAt = System.currentTimeMillis()
                        pressed = true
                        onHoldStart()
                        val released = tryAwaitRelease()
                        val heldMs = System.currentTimeMillis() - downAt
                        pressed = false
                        if (!released || heldMs < 300L) {
                            onHoldCancel()
                            if (released) onTap()
                        } else {
                            onHoldEnd()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val r = this.size.minDimension / 2f
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            drawCircle(
                color = palette.orbMid.copy(alpha = glow * 0.35f),
                radius = r * scale,
                center = c,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        palette.orbInner,
                        palette.orbMid,
                        palette.orbOuter,
                        Color.Transparent,
                    ),
                    center = c,
                    radius = r * 0.92f * if (listening) scale else 1f,
                ),
                radius = r * 0.82f,
                center = c,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(c.x - r * 0.18f, c.y - r * 0.22f),
                    radius = r * 0.45f,
                ),
                radius = r * 0.82f,
                center = c,
            )
        }
    }
}
