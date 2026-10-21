package com.voiability.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class MicVisual { IDLE, LISTENING, WORKING, DONE, ERROR }

private val circleShape = RoundedPolygon.circle(numVertices = 9).normalized()
private val cookieShape = RoundedPolygon.star(
    numVerticesPerRadius = 9, innerRadius = 0.82f, rounding = CornerRounding(0.32f), innerRounding = CornerRounding(0.32f),
).normalized()
private val burstShape = RoundedPolygon.star(
    numVerticesPerRadius = 9, innerRadius = 0.62f, rounding = CornerRounding(0.18f), innerRounding = CornerRounding(0.22f),
).normalized()
private val idleToListen = Morph(circleShape, cookieShape)
private val listenToBurst = Morph(cookieShape, burstShape)

/**
 * The hero mic button. Morphs circle -> 9-sided cookie while listening (breathing with your
 * voice) and spins while working. Tap to toggle, hold for push-to-talk.
 */
@Composable
fun MorphingMic(
    visual: MicVisual,
    level: Float,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }

    val morph by animateFloatAsState(
        if (visual == MicVisual.LISTENING || visual == MicVisual.WORKING) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), label = "morph",
    )
    val burst by animateFloatAsState(
        if (visual == MicVisual.LISTENING) (level * 1.2f).coerceIn(0f, 1f) else 0f,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "burst",
    )
    val pressScale by animateFloatAsState(
        if (pressed) 0.9f else 1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium), label = "press",
    )
    val container by animateColorAsState(
        when (visual) {
            MicVisual.IDLE -> scheme.primary
            MicVisual.LISTENING -> scheme.tertiary
            MicVisual.WORKING -> scheme.secondary
            MicVisual.DONE -> Color(0xFF2E9E6A)
            MicVisual.ERROR -> scheme.error
        },
        tween(350, easing = FastOutSlowInEasing), label = "color",
    )
    val content = when (visual) {
        MicVisual.LISTENING -> scheme.onTertiary
        MicVisual.WORKING -> scheme.onSecondary
        MicVisual.ERROR -> scheme.onError
        MicVisual.DONE -> Color.White
        MicVisual.IDLE -> scheme.onPrimary
    }
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (visual == MicVisual.WORKING) 1400 else 9000, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    val halo by spin.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "halo",
    )

    Box(
        modifier
            .size(size * 1.5f)
            .semantics { contentDescription = if (visual == MicVisual.LISTENING) "Stop dictation" else "Start dictation" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    var held = false
                    val holdJob: Job = scope.launch {
                        delay(320)
                        held = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onHoldStart()
                    }
                    waitForUpOrCancellation()
                    pressed = false
                    holdJob.cancel()
                    if (held) onHoldEnd() else {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTap()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val base = size.toPx() / 2f
            val center = this.center
            // Expanding ripple rings while listening.
            if (visual == MicVisual.LISTENING) {
                for (i in 0..1) {
                    val t = (halo + i * 0.5f) % 1f
                    drawCircle(
                        container.copy(alpha = (1f - t) * 0.25f * (0.4f + level)),
                        radius = base * (1f + t * 0.45f), center = center,
                    )
                }
            }
            val path = android.graphics.Path()
            if (burst > 0.01f) listenToBurst.toPath(burst * 0.6f, path) else idleToListen.toPath(morph, path)
            val compose = path.asComposePath()
            val r = base * pressScale * (1f + 0.08f * burst)
            withTransform({
                translate(center.x - r, center.y - r)
                rotate(if (morph > 0.01f) angle else 0f, pivot = androidx.compose.ui.geometry.Offset(r, r))
            }) {
                val m = Matrix().apply { scale(r * 2f, r * 2f) }
                compose.transform(m)
                drawPath(compose, container)
            }
        }
        Icon(
            imageVector = when (visual) {
                MicVisual.DONE -> Icons.Rounded.Check
                MicVisual.ERROR -> Icons.Rounded.ErrorOutline
                else -> Icons.Rounded.Mic
            },
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(size * 0.38f).scale(pressScale),
        )
    }
}
