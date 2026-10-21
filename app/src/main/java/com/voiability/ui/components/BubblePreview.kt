package com.voiability.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.voiability.overlay.BubbleMode
import com.voiability.overlay.LiquidBubbleView
import kotlin.math.sin

/** The overlay renderer, positioned inside its own bounds instead of on screen. */
private class PreviewBubbleView(context: Context) : LiquidBubbleView(context) {
    /** -1 = melted into the left edge, 0 = floating in the middle, 1 = melted into the right edge. */
    var side = 1
    private val gap = 3f * resources.displayMetrics.density

    fun place() {
        val w = width.toFloat()
        originX = 0f
        originY = 0f
        screenWidth = w
        ballY = height / 2f
        ballX = when (side) {
            1 -> w - radius - gap
            -1 -> radius + gap
            else -> w / 2f
        }
        leftPull = if (side == -1) 1f else 0f
        rightPull = if (side == 1) 1f else 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        place()
    }
}

/**
 * The real bubble renderer embedded in the app, gently "talking" so the liquid effect shows.
 */
@Composable
fun BubblePreview(
    sizeDp: Float,
    opacity: Float,
    liquid: Boolean,
    modifier: Modifier = Modifier,
    side: Int = 1,
    alive: Boolean = true,
) {
    val holder = remember { arrayOfNulls<PreviewBubbleView>(1) }
    LaunchedEffect(alive, opacity) {
        val start = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val t = (now - start) / 1e9f
                holder[0]?.let { v ->
                    v.time = t
                    // Talk for a bit, rest for a bit.
                    val talking = alive && (t % 6f) < 3.2f
                    v.mode = if (talking) BubbleMode.LISTENING else BubbleMode.IDLE
                    v.level = if (talking) (0.5f + 0.4f * sin(t * 7f) * sin(t * 2.3f)).coerceIn(0f, 1f) else 0f
                    v.bodyAlpha = if (talking) 1f else opacity
                    v.invalidate()
                }
            }
        }
    }
    AndroidView(
        factory = { ctx -> PreviewBubbleView(ctx).also { holder[0] = it } },
        modifier = modifier,
        update = { v ->
            v.radius = sizeDp / 2f * v.resources.displayMetrics.density
            v.liquid = liquid
            v.side = side
            v.bodyAlpha = opacity
            v.place()
        },
    )
}
