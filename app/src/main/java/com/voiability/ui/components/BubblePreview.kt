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
import com.voiability.data.BubbleLook
import com.voiability.data.BubbleSettings
import kotlin.math.abs
import kotlin.math.sin

/** The overlay renderer, positioned inside its own bounds instead of on screen. */
private class PreviewBubbleView(context: Context) : LiquidBubbleView(context) {
    /** -1 = melted into the left edge, 0 = floating in the middle, 1 = melted into the right edge. */
    var side = 1
    /** Flat bubbles float a little away from the edge, liquid ones touch it. */
    var flatGap = false
    private val gap get() = (if (flatGap) 8f else 3f) * resources.displayMetrics.density

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
 * The real bubble renderer embedded in the app. It loops through resting, listening (with a
 * fake voice), working and done so every animation setting can be seen.
 */
@Composable
fun BubblePreview(
    bubble: BubbleSettings,
    modifier: Modifier = Modifier,
    side: Int = 1,
    alive: Boolean = true,
) {
    val holder = remember { arrayOfNulls<PreviewBubbleView>(1) }
    LaunchedEffect(alive, bubble.idleOpacity) {
        val start = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val t = (now - start) / 1e9f
                holder[0]?.let { v ->
                    v.time = t
                    val phase = t % 8f
                    v.mode = when {
                        !alive || phase < 1.5f -> BubbleMode.IDLE
                        phase < 4.8f -> BubbleMode.LISTENING
                        phase < 7.2f -> BubbleMode.PROCESSING
                        else -> BubbleMode.DONE
                    }
                    // Syllable-ish loudness: fast bursts inside slower phrases.
                    val speech = (0.5f + 0.5f * sin(t * 2.1f)) * (0.55f + 0.45f * abs(sin(t * 9.3f)))
                    v.level = if (v.mode == BubbleMode.LISTENING) speech.coerceIn(0f, 1f) else 0f
                    v.bodyAlpha = if (v.mode == BubbleMode.IDLE) bubble.idleOpacity else 1f
                    v.invalidate()
                }
            }
        }
    }
    AndroidView(
        factory = { ctx -> PreviewBubbleView(ctx).also { holder[0] = it } },
        modifier = modifier,
        update = { v ->
            v.radius = bubble.sizeDp / 2f * v.resources.displayMetrics.density
            v.liquid = bubble.liquid
            v.look = bubble.look
            v.listenAnim = bubble.listenAnim
            v.spinner = bubble.spinner
            if (v.colorRole != bubble.color) v.colorRole = bubble.color
            v.flatGap = bubble.look != BubbleLook.LIQUID
            v.side = side
            v.place()
        },
    )
}
