package com.voiability.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.voiability.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class BubbleMode { IDLE, LISTENING, PROCESSING, DONE, ERROR }

/**
 * Draws the bubble as a signed-distance metaball field: the bubble, voice-reactive satellite
 * blobs, gooey "anchors" that rise out of the screen edges, and the dismiss target all melt
 * into each other with a smooth-min. Everything is in screen coordinates; [originX]/[originY]
 * say where this view's window sits on screen.
 */
open class LiquidBubbleView(context: Context) : View(context) {

    // Geometry, in screen pixels.
    var ballX = 0f
    var ballY = 0f
    var radius = 0f
    var scale = 1f
    var originX = 0f
    var originY = 0f
    var screenWidth = 0f
    /** 0..1: how strongly each screen edge reaches for the ball. */
    var leftPull = 0f
    var rightPull = 0f
    var liquid = true

    var targetX = 0f
    var targetY = 0f
    var targetRadius = 0f
    /** 0 = hidden, 1 = fully shown. */
    var targetAmount = 0f
    var targetHot = false

    var mode = BubbleMode.IDLE
    var level = 0f
    var bodyAlpha = 1f
    /** Animation clock in seconds. */
    var time = 0f

    private val density = resources.displayMetrics.density
    private val shader: Any? = if (Build.VERSION.SDK_INT >= 33) RuntimeShader(SHADER) else null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()

    private val mic = ContextCompat.getDrawable(context, R.drawable.ic_mic)!!.mutate()
    private val check = ContextCompat.getDrawable(context, R.drawable.ic_check)!!.mutate()
    private val alert = ContextCompat.getDrawable(context, R.drawable.ic_alert)!!.mutate()
    private val close = ContextCompat.getDrawable(context, R.drawable.ic_close)!!.mutate()

    private val primary = sysColor(android.R.color.system_accent1_500, 0xFF6750A4.toInt())
    private val primaryDeep = sysColor(android.R.color.system_accent1_700, 0xFF4F378B.toInt())
    private val tertiary = sysColor(android.R.color.system_accent3_400, 0xFF7D5260.toInt())
    private val tertiaryDeep = sysColor(android.R.color.system_accent3_600, 0xFF633B48.toInt())
    private val success = 0xFF2E9E6A.toInt()
    private val error = 0xFFDC362E.toInt()
    private val targetIdle = 0xFF5F5F66.toInt()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun sysColor(id: Int, fallback: Int): Int =
        if (Build.VERSION.SDK_INT >= 31) context.getColor(id) else fallback

    private fun bodyColors(): Pair<Int, Int> = when (mode) {
        BubbleMode.IDLE -> primary to primaryDeep
        BubbleMode.LISTENING -> tertiary to primary
        BubbleMode.PROCESSING -> primary to tertiaryDeep
        BubbleMode.DONE -> success to ColorUtils.blendARGB(success, Color.BLACK, 0.25f)
        BubbleMode.ERROR -> error to ColorUtils.blendARGB(error, Color.BLACK, 0.25f)
    }

    /** Satellite blobs as (x, y, r) relative to the ball; they make the bubble wobble with your voice. */
    private fun satellite(i: Int, r: Float): FloatArray = when (mode) {
        BubbleMode.LISTENING -> {
            val wobble = 0.5f + 0.5f * sin(time * (3.1f + i * 0.7f) + i * 1.7f)
            val angle = time * (0.8f + i * 0.25f) + i * (2f * PI.toFloat() / 3f)
            val dist = r * (0.42f + 0.38f * level * (0.6f + 0.4f * wobble))
            val sr = r * (0.34f + 0.3f * level * wobble)
            floatArrayOf(cos(angle) * dist, sin(angle) * dist, sr)
        }
        BubbleMode.PROCESSING -> {
            val angle = time * 4.2f + i * (2f * PI.toFloat() / 3f)
            val dist = r * (1.12f + 0.06f * sin(time * 6f + i))
            floatArrayOf(cos(angle) * dist, sin(angle) * dist, r * 0.2f)
        }
        else -> floatArrayOf(0f, 0f, 0f)
    }

    override fun onDraw(canvas: Canvas) {
        val r = radius * scale * if (mode == BubbleMode.LISTENING) 1f + 0.1f * level else 1f
        if (r <= 0f) return
        val bx = ballX - originX
        val by = ballY - originY
        val (colA, colB) = bodyColors()

        if (Build.VERSION.SDK_INT >= 33) {
            drawLiquid(canvas, bx, by, r, colA, colB)
        } else {
            drawFallback(canvas, bx, by, r, colA)
        }
        drawIcon(canvas, bx, by, r)
    }

    @RequiresApi(33)
    @SuppressLint("NewApi")
    private fun drawLiquid(canvas: Canvas, bx: Float, by: Float, r: Float, colA: Int, colB: Int) {
        val s = shader as RuntimeShader
        s.setFloatUniform("ball", bx, by)
        s.setFloatUniform("rad", r)
        val lp = if (liquid) leftPull else 0f
        val rp = if (liquid) rightPull else 0f
        s.setFloatUniform("edges", -originX, screenWidth - originX, lp, rp)
        s.setFloatUniform(
            "target", targetX - originX, targetY - originY, targetRadius,
            if (liquid || targetAmount > 0f) targetAmount else 0f,
        )
        for (i in 0..2) {
            val sat = satellite(i, r)
            s.setFloatUniform("sat$i", bx + sat[0], by + sat[1], sat[2], 0f)
        }
        s.setFloatUniform("goo", r * if (liquid) 1.05f else 0.35f)
        s.setColorUniform("colA", colA)
        s.setColorUniform("colB", colB)
        s.setColorUniform("colT", if (targetHot) error else targetIdle)
        s.setFloatUniform("alpha", bodyAlpha)
        s.setFloatUniform("density", density)
        paint.shader = s
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private fun drawFallback(canvas: Canvas, bx: Float, by: Float, r: Float, colA: Int) {
        if (targetAmount > 0f) {
            paint.color = if (targetHot) error else targetIdle
            paint.alpha = (255 * targetAmount).toInt()
            canvas.drawCircle(targetX - originX, targetY - originY, targetRadius * targetAmount, paint)
        }
        paint.shader = null
        paint.color = Color.BLACK
        paint.alpha = (60 * bodyAlpha).toInt()
        canvas.drawCircle(bx, by + 2.5f * density, r + density, paint)
        paint.color = colA
        paint.alpha = (255 * bodyAlpha).toInt()
        canvas.drawCircle(bx, by, r, paint)
        for (i in 0..2) {
            val sat = satellite(i, r)
            if (sat[2] > 0f) canvas.drawCircle(bx + sat[0], by + sat[1], sat[2], paint)
        }
    }

    private fun drawIcon(canvas: Canvas, bx: Float, by: Float, r: Float) {
        val alpha = (255 * bodyAlpha).toInt()
        if (targetAmount > 0.05f) {
            val tr = targetRadius * 0.42f * targetAmount
            close.setTint(Color.WHITE)
            close.alpha = (255 * targetAmount).toInt()
            close.setBounds((targetX - originX - tr).toInt(), (targetY - originY - tr).toInt(), (targetX - originX + tr).toInt(), (targetY - originY + tr).toInt())
            close.draw(canvas)
        }
        when (mode) {
            BubbleMode.LISTENING -> {
                // A little live equalizer.
                iconPaint.color = Color.WHITE
                iconPaint.alpha = alpha
                val w = r * 0.13f
                val gap = r * 0.09f
                val total = 4 * w + 3 * gap
                for (i in 0 until 4) {
                    val phase = sin(time * (9f + i * 2.3f) + i * 1.3f) * 0.5f + 0.5f
                    val h = r * (0.18f + 0.62f * min(1f, level * 1.25f) * (0.45f + 0.55f * phase))
                    val x = bx - total / 2 + i * (w + gap)
                    bar.set(x, by - h / 2, x + w, by + h / 2)
                    canvas.drawRoundRect(bar, w / 2, w / 2, iconPaint)
                }
            }
            else -> {
                val icon = when (mode) {
                    BubbleMode.DONE -> check
                    BubbleMode.ERROR -> alert
                    else -> mic
                }
                val size = r * 0.95f
                icon.setTint(Color.WHITE)
                icon.alpha = if (mode == BubbleMode.PROCESSING) (alpha * 0.55f).toInt() else alpha
                icon.setBounds((bx - size / 2).toInt(), (by - size / 2).toInt(), (bx + size / 2).toInt(), (by + size / 2).toInt())
                icon.draw(canvas)
            }
        }
    }

    /** True while something on screen is moving and the view needs a new frame every vsync. */
    val isAnimating: Boolean get() = mode == BubbleMode.LISTENING || mode == BubbleMode.PROCESSING

    companion object {
        /** Bubble radius never gets drawn larger than this multiple of [radius]; used to size windows. */
        const val MAX_EXTENT = 1.6f

        private val SHADER = """
            uniform float2 ball;
            uniform float rad;
            uniform float4 edges;   // left edge x, right edge x, left pull, right pull
            uniform float4 target;  // x, y, r, amount
            uniform float4 sat0;
            uniform float4 sat1;
            uniform float4 sat2;
            uniform float goo;
            layout(color) uniform half4 colA;
            layout(color) uniform half4 colB;
            layout(color) uniform half4 colT;
            uniform float alpha;
            uniform float density;

            float smin(float a, float b, float k) {
                float h = max(k - abs(a - b), 0.0) / k;
                return min(a, b) - h * h * k * 0.25;
            }

            float circle(float2 p, float2 c, float r) { return length(p - c) - r; }

            float body(float2 p) {
                float d = circle(p, ball, rad);
                float ks = rad * 0.9;
                if (sat0.z > 0.0) d = smin(d, circle(p, sat0.xy, sat0.z), ks);
                if (sat1.z > 0.0) d = smin(d, circle(p, sat1.xy, sat1.z), ks);
                if (sat2.z > 0.0) d = smin(d, circle(p, sat2.xy, sat2.z), ks);
                // Big blobs hidden past each screen edge; they rise to meet the bubble.
                float re = rad * 1.9;
                if (edges.z > 0.001) d = smin(d, circle(p, float2(edges.x - re + edges.z * rad * 0.42, ball.y), re), goo * edges.z);
                if (edges.w > 0.001) d = smin(d, circle(p, float2(edges.y + re - edges.w * rad * 0.42, ball.y), re), goo * edges.w);
                return d;
            }

            float targetField(float2 p) {
                if (target.w < 0.001) return 1e5;
                return circle(p, target.xy, target.z * target.w);
            }

            half4 main(float2 p) {
                float db = body(p);
                float dt = targetField(p);
                float k = goo * 1.3;
                float d = smin(db, dt, k);

                // Shadow: the same shape, nudged down and blurred.
                float2 off = float2(0.0, 2.5 * density);
                float ds = smin(body(p - off), targetField(p - off), k);
                float sh = (1.0 - smoothstep(-3.0 * density, 10.0 * density, ds)) * 0.26;

                float a = clamp(0.5 - d, 0.0, 1.0);
                float g = clamp((p.y - ball.y + rad) / (2.0 * rad), 0.0, 1.0);
                half4 bodyCol = mix(colA, colB, g);
                // Subtle top light so it reads as a droplet, not a sticker.
                float hl = 1.0 - smoothstep(0.0, rad * 0.9, length(p - (ball + float2(-0.25, -0.45) * rad)));
                bodyCol.rgb += half3(0.10 * hl);
                float wT = clamp(0.5 + (db - dt) / (k + 1.0), 0.0, 1.0);
                half4 col = mix(bodyCol, colT, wT);
                col.a = 1.0;

                half4 outc = half4(col.rgb * a, a);
                outc += half4(0.0, 0.0, 0.0, sh * (1.0 - a));
                return outc * alpha;
            }
        """.trimIndent()
    }
}
