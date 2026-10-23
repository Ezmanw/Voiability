package com.voiability.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import com.voiability.R
import com.voiability.data.BubbleColor
import com.voiability.data.BubbleLook
import com.voiability.data.ListenAnim
import com.voiability.data.Spinner
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class BubbleMode { IDLE, LISTENING, PROCESSING, DONE, ERROR }

/**
 * Draws the dictation bubble. Two looks:
 *  - FLAT: a solid Material You disc with an elevation shadow.
 *  - LIQUID: a signed-distance metaball field where the bubble, voice-reactive blobs, gooey
 *    "anchors" rising out of the screen edges and the dismiss target melt together.
 * On top go the state animations (waveform, pulse, spinners) in the on-colour.
 *
 * Geometry is in screen pixels; [originX]/[originY] say where this view sits on screen.
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
    /** 0..1: how strongly each screen edge reaches for the ball (Liquid look). */
    var leftPull = 0f
    var rightPull = 0f
    /** Metaball rendering (gooey edges and merging); with the Flat look it stays solid-coloured. */
    var liquid = true
    /**
     * Read the real on-screen position at draw time instead of trusting [originX]/[originY].
     * Window moves and resizes land a frame apart from our own updates; without this the
     * bubble flashes to the wrong spot while its window switches between docked and free.
     */
    var trackWindowPosition = false

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

    var look = BubbleLook.FLAT
    var listenAnim = ListenAnim.WAVE
    var spinner = Spinner.MORPH
    var colorRole = BubbleColor.PRIMARY
        set(value) {
            field = value
            refreshColors()
        }

    private val density = resources.displayMetrics.density
    private var shader: Any? = if (Build.VERSION.SDK_INT >= 33) {
        runCatching { RuntimeShader(SHADER) }.onFailure { Log.e(TAG, "shader failed", it) }.getOrNull()
    } else null
    /** Consecutive failed shader frames; only a persistent failure disables the shader. */
    private var shaderFailures = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val loc = IntArray(2)
    private val path = Path()
    private val matrix = Matrix()

    private val mic = ContextCompat.getDrawable(context, R.drawable.ic_mic)!!.mutate()
    private val check = ContextCompat.getDrawable(context, R.drawable.ic_check)!!.mutate()
    private val alert = ContextCompat.getDrawable(context, R.drawable.ic_alert)!!.mutate()
    private val close = ContextCompat.getDrawable(context, R.drawable.ic_close)!!.mutate()

    private var base = 0
    private var onBase = 0
    private var errorColor = 0
    private var onError = 0
    private val targetIdle = 0xFF5F5F66.toInt()

    // Recent loudness, newest first, sampled ~every 40 ms for the waveform.
    private val history = FloatArray(16)
    private var lastSample = 0f

    init {
        refreshColors()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        refreshColors()
    }

    /** Resolves Material You tones for the chosen role, light or dark. */
    @SuppressLint("InlinedApi")
    private fun refreshColors() {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        fun c(id: Int, fallback: Int): Int = if (Build.VERSION.SDK_INT >= 31) context.getColor(id) else fallback
        val (b, o) = when (colorRole) {
            BubbleColor.PRIMARY -> if (dark) c(android.R.color.system_accent1_200, 0xFFCABEFF.toInt()) to c(android.R.color.system_accent1_800, 0xFF35159A.toInt())
            else c(android.R.color.system_accent1_600, 0xFF5B3FD9.toInt()) to Color.WHITE
            BubbleColor.SECONDARY -> if (dark) c(android.R.color.system_accent2_200, 0xFFC9C3DC.toInt()) to c(android.R.color.system_accent2_800, 0xFF312E41.toInt())
            else c(android.R.color.system_accent2_600, 0xFF5E5C71.toInt()) to Color.WHITE
            BubbleColor.TERTIARY -> if (dark) c(android.R.color.system_accent3_200, 0xFFFFB0C9.toInt()) to c(android.R.color.system_accent3_800, 0xFF5E1136.toInt())
            else c(android.R.color.system_accent3_600, 0xFF9C2E63.toInt()) to Color.WHITE
            BubbleColor.NEUTRAL -> if (dark) c(android.R.color.system_neutral2_700, 0xFF48454E.toInt()) to c(android.R.color.system_neutral2_50, 0xFFF4EFF4.toInt())
            else c(android.R.color.system_neutral2_100, 0xFFE7E0EC.toInt()) to c(android.R.color.system_neutral2_800, 0xFF322F37.toInt())
            BubbleColor.INVERSE -> if (dark) c(android.R.color.system_neutral1_100, 0xFFE6E1E5.toInt()) to c(android.R.color.system_neutral1_900, 0xFF1C1B1F.toInt())
            else c(android.R.color.system_neutral1_800, 0xFF313033.toInt()) to c(android.R.color.system_neutral1_50, 0xFFF4EFF4.toInt())
        }
        base = b
        onBase = o
        errorColor = if (dark) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt()
        onError = if (dark) 0xFF601410.toInt() else Color.WHITE
        invalidate()
    }

    /** True while something on screen is moving and the view needs a new frame every vsync. */
    val isAnimating: Boolean get() = mode == BubbleMode.LISTENING || mode == BubbleMode.PROCESSING

    override fun onDraw(canvas: Canvas) {
        if (trackWindowPosition) {
            getLocationOnScreen(loc)
            originX = loc[0].toFloat()
            originY = loc[1].toFloat()
        }
        if (time - lastSample > 0.04f || time < lastSample) {
            System.arraycopy(history, 0, history, 1, history.size - 1)
            history[0] = level
            lastSample = time
        }
        val listening = mode == BubbleMode.LISTENING
        val r = radius * scale * if (listening) 1f + 0.07f * level else 1f
        if (r <= 0f) return
        val bx = ballX - originX
        val by = ballY - originY
        val body = if (mode == BubbleMode.ERROR) errorColor else base
        val on = if (mode == BubbleMode.ERROR) onError else onBase

        drawTarget(canvas)
        if (listening && listenAnim == ListenAnim.PULSE) {
            fill.color = body
            fill.alpha = (70 * bodyAlpha).toInt()
            canvas.drawCircle(bx, by, r * (1.1f + 0.38f * level), fill)
            fill.alpha = (40 * bodyAlpha).toInt()
            canvas.drawCircle(bx, by, r * (1.18f + 0.55f * level), fill)
        }

        // Runtime shaders only work on the GPU; a software-drawn frame (screenshots, some system
        // snapshots) just gets the flat disc. A failed frame falls back for that frame only, so
        // one hiccup can't switch metaballs off for good.
        val liquidOk = usesShader && canvas.isHardwareAccelerated &&
            runCatching { drawLiquid(canvas, bx, by, r, body) }
                .onSuccess { shaderFailures = 0 }
                .onFailure { e ->
                    paint.shader = null
                    if (++shaderFailures == 1 || shaderFailures % 120 == 0) Log.e(TAG, "shader draw failed ($shaderFailures)", e)
                    if (shaderFailures >= MAX_SHADER_FAILURES) shader = null
                }
                .isSuccess
        if (!liquidOk) drawFlat(canvas, bx, by, r, body)
        drawContent(canvas, bx, by, r, on)
    }

    /** The metaball shader draws the body for the Liquid look, and for Flat when metaballs are on. */
    private val usesShader: Boolean
        get() = Build.VERSION.SDK_INT >= 33 && shader != null && (look == BubbleLook.LIQUID || liquid)

    /** Voice-reactive satellite blobs as (x, y, r) relative to the ball. */
    private fun satellite(i: Int, r: Float): FloatArray {
        if (mode != BubbleMode.LISTENING || listenAnim != ListenAnim.BLOB) return floatArrayOf(0f, 0f, 0f)
        val wobble = 0.5f + 0.5f * sin(time * (3.1f + i * 0.7f) + i * 1.7f)
        val angle = time * (0.8f + i * 0.25f) + i * (2f * PI.toFloat() / 3f)
        val dist = r * (0.35f + 0.6f * level * (0.6f + 0.4f * wobble))
        val sr = r * (0.3f + 0.32f * level * wobble)
        return floatArrayOf(cos(angle) * dist, sin(angle) * dist, sr)
    }

    private fun drawFlat(canvas: Canvas, bx: Float, by: Float, r: Float, body: Int) {
        // Material elevation: a soft key shadow, faded with the bubble.
        fill.color = Color.TRANSPARENT
        fill.setShadowLayer(4f * density, 0f, 1.5f * density, ColorUtils.setAlphaComponent(Color.BLACK, (70 * bodyAlpha * bodyAlpha).toInt()))
        fill.color = body
        fill.alpha = (255 * bodyAlpha).toInt()
        canvas.drawCircle(bx, by, r, fill)
        fill.clearShadowLayer()
        for (i in 0..2) {
            val s = satellite(i, r)
            if (s[2] > 0f) canvas.drawCircle(bx + s[0], by + s[1], s[2], fill)
        }
    }

    @RequiresApi(33)
    @SuppressLint("NewApi")
    private fun drawLiquid(canvas: Canvas, bx: Float, by: Float, r: Float, body: Int) {
        val s = shader as RuntimeShader
        s.setFloatUniform("ball", bx, by)
        s.setFloatUniform("rad", r)
        val lp = if (liquid) leftPull else 0f
        val rp = if (liquid) rightPull else 0f
        s.setFloatUniform("edges", -originX, screenWidth - originX, lp, rp)
        s.setFloatUniform("target", targetX - originX, targetY - originY, targetRadius, targetAmount)
        for (i in 0..2) {
            val sat = satellite(i, r)
            s.setFloatUniform("sat$i", bx + sat[0], by + sat[1], sat[2], 0f)
        }
        s.setFloatUniform("goo", r * if (liquid) 1.05f else 0.35f)
        // Flat stays one solid tone; Liquid gets a gentle top-to-bottom gradient.
        val flat = look == BubbleLook.FLAT
        s.setColorUniform("colA", if (flat) body else ColorUtils.blendARGB(body, Color.WHITE, 0.08f))
        s.setColorUniform("colB", if (flat) body else ColorUtils.blendARGB(body, Color.BLACK, 0.18f))
        s.setColorUniform("colT", if (targetHot) errorColor else targetIdle)
        s.setFloatUniform("alpha", bodyAlpha)
        s.setFloatUniform("density", density)
        paint.shader = s
        // Shade only around the bubble (plus the edges and target it's merging with): the
        // overlay window is full-screen and most of it is empty.
        val pad = r * MAX_EXTENT + 14f * density
        rect.set(bx - pad, by - pad, bx + pad, by + pad)
        if (lp > 0.001f) rect.left = 0f
        if (rp > 0.001f) rect.right = width.toFloat()
        if (targetAmount > 0.001f) {
            val tp = targetRadius + r * 1.5f + 14f * density
            rect.union(targetX - originX - tp, targetY - originY - tp, targetX - originX + tp, targetY - originY + tp)
        }
        canvas.drawRect(rect, paint)
    }

    private fun drawTarget(canvas: Canvas) {
        if (targetAmount <= 0.01f) return
        val tx = targetX - originX
        val ty = targetY - originY
        // The liquid shader draws the target itself so it can melt with the bubble.
        if (!usesShader) {
            fill.color = if (targetHot) errorColor else targetIdle
            fill.alpha = (230 * targetAmount).toInt()
            canvas.drawCircle(tx, ty, targetRadius * targetAmount * if (targetHot) 1.15f else 1f, fill)
        }
        val tr = targetRadius * 0.42f * targetAmount
        close.setTint(Color.WHITE)
        close.alpha = (255 * targetAmount).toInt()
        close.setBounds((tx - tr).toInt(), (ty - tr).toInt(), (tx + tr).toInt(), (ty + tr).toInt())
        // Drawn again after the body so it sits on top of the liquid target.
        if (!usesShader) close.draw(canvas)
    }

    private fun drawContent(canvas: Canvas, bx: Float, by: Float, r: Float, on: Int) {
        if (usesShader && targetAmount > 0.01f) close.draw(canvas)
        val alpha = (255 * bodyAlpha.coerceAtLeast(0.6f)).toInt()
        when (mode) {
            BubbleMode.LISTENING -> when (listenAnim) {
                ListenAnim.WAVE -> drawWave(canvas, bx, by, r, on, alpha)
                else -> drawIcon(canvas, mic, bx, by, r, on, alpha)
            }
            BubbleMode.PROCESSING -> drawSpinner(canvas, bx, by, r, on, alpha)
            BubbleMode.DONE -> drawIcon(canvas, check, bx, by, r, on, alpha)
            BubbleMode.ERROR -> drawIcon(canvas, alert, bx, by, r, on, alpha)
            BubbleMode.IDLE -> drawIcon(canvas, mic, bx, by, r, on, alpha)
        }
    }

    private fun drawIcon(canvas: Canvas, icon: android.graphics.drawable.Drawable, bx: Float, by: Float, r: Float, on: Int, alpha: Int) {
        val size = r * 0.9f
        icon.setTint(on)
        icon.alpha = alpha
        icon.setBounds((bx - size / 2).toInt(), (by - size / 2).toInt(), (bx + size / 2).toInt(), (by + size / 2).toInt())
        icon.draw(canvas)
    }

    /** Five bars, newest loudness in the middle rippling outwards, like a voice recorder. */
    private fun drawWave(canvas: Canvas, bx: Float, by: Float, r: Float, on: Int, alpha: Int) {
        fill.color = on
        fill.alpha = alpha
        val w = r * 0.14f
        val gap = r * 0.1f
        val ages = intArrayOf(4, 2, 0, 2, 4)
        val total = 5 * w + 4 * gap
        for (i in 0 until 5) {
            val l = history[ages[i]] * if (i == 2) 1f else if (i == 1 || i == 3) 0.85f else 0.65f
            val h = r * (0.16f + 0.78f * min(1f, l * 1.15f))
            val x = bx - total / 2 + i * (w + gap)
            rect.set(x, by - h / 2, x + w, by + h / 2)
            canvas.drawRoundRect(rect, w / 2, w / 2, fill)
        }
    }

    private fun drawSpinner(canvas: Canvas, bx: Float, by: Float, r: Float, on: Int, alpha: Int) {
        val t = time
        when (spinner) {
            Spinner.ARC -> {
                // Material's indeterminate circular indicator: a stretching, rotating arc.
                stroke.color = on
                stroke.alpha = alpha
                stroke.strokeWidth = r * 0.12f
                val rr = r * 0.46f
                rect.set(bx - rr, by - rr, bx + rr, by + rr)
                val phase = t * 1.6f
                val sweep = 30f + 250f * (0.5f + 0.5f * sin(phase * PI.toFloat()))
                val start = t * 330f + 140f * (phase - sin(phase * PI.toFloat()) / PI.toFloat())
                canvas.drawArc(rect, start % 360f, sweep, false, stroke)
            }
            Spinner.MORPH -> {
                // Material 3 Expressive loading indicator: shapes melting into each other while spinning.
                val cycle = t / 0.7f
                val i = cycle.toInt() % MORPHS.size
                val f = cycle - cycle.toInt()
                val eased = if (f < 0.5f) 4 * f * f * f else 1 - (-2 * f + 2).let { it * it * it } / 2
                path.rewind()
                MORPHS[i].toPath(eased, path)
                val s = r * 1.05f
                matrix.reset()
                matrix.setScale(s, s)
                matrix.postTranslate(-s / 2, -s / 2)
                matrix.postRotate(t * 150f + i * 72f)
                matrix.postTranslate(bx, by)
                path.transform(matrix)
                fill.color = on
                fill.alpha = alpha
                canvas.drawPath(path, fill)
            }
            Spinner.DOTS -> {
                fill.color = on
                fill.alpha = alpha
                for (k in 0..2) {
                    val a = t * 5.2f + k * (2f * PI.toFloat() / 3f)
                    val d = r * 0.36f
                    val dr = r * 0.12f * (0.75f + 0.35f * sin(t * 7f + k * 2f))
                    canvas.drawCircle(bx + cos(a) * d, by + sin(a) * d, dr, fill)
                }
            }
            Spinner.RIPPLE -> {
                stroke.color = on
                stroke.strokeWidth = r * 0.08f
                for (k in 0..1) {
                    val p = ((t * 0.9f) + k * 0.5f) % 1f
                    stroke.alpha = (alpha * (1f - p)).toInt()
                    canvas.drawCircle(bx, by, r * (0.15f + 0.6f * p), stroke)
                }
                fill.color = on
                fill.alpha = alpha
                canvas.drawCircle(bx, by, r * (0.13f + 0.03f * abs(sin(t * 4f))), fill)
            }
        }
    }

    companion object {
        private const val TAG = "LiquidBubble"

        /** Bubble never gets drawn larger than this multiple of [radius]; used to size windows. */
        const val MAX_EXTENT = 1.8f
        private const val MAX_SHADER_FAILURES = 600

        private val SHAPES = listOf(
            RoundedPolygon.star(9, innerRadius = 0.8f, rounding = CornerRounding(0.3f), innerRounding = CornerRounding(0.3f)),
            RoundedPolygon(5, rounding = CornerRounding(0.35f)),
            RoundedPolygon.star(8, innerRadius = 0.6f, rounding = CornerRounding(0.25f), innerRounding = CornerRounding(0.3f)),
            RoundedPolygon(4, rounding = CornerRounding(0.45f)),
            RoundedPolygon.star(12, innerRadius = 0.86f, rounding = CornerRounding(0.2f), innerRounding = CornerRounding(0.2f)),
        ).map { it.normalized() }
        private val MORPHS = SHAPES.indices.map { Morph(SHAPES[it], SHAPES[(it + 1) % SHAPES.size]) }

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
                if (edges.z > 0.001) d = smin(d, circle(p, float2(edges.x - re + edges.z * rad * 0.5, ball.y), re), goo * (0.55 + 0.45 * edges.z));
                if (edges.w > 0.001) d = smin(d, circle(p, float2(edges.y + re - edges.w * rad * 0.5, ball.y), re), goo * (0.55 + 0.45 * edges.w));
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
                float2 off = float2(0.0, 2.0 * density);
                float ds = smin(body(p - off), targetField(p - off), k);
                float sh = (1.0 - smoothstep(-3.0 * density, 8.0 * density, ds)) * 0.22;

                float a = clamp(0.5 - d, 0.0, 1.0);
                float g = clamp((p.y - ball.y + rad) / (2.0 * rad), 0.0, 1.0);
                float3 bodyCol = mix(float3(colA.rgb), float3(colB.rgb), g);
                float wT = clamp(0.5 + (db - dt) / (k + 1.0), 0.0, 1.0);
                float3 col = mix(bodyCol, float3(colT.rgb), wT);

                float4 outc = float4(col * a, a) + float4(0.0, 0.0, 0.0, sh * (1.0 - a));
                return half4(outc * alpha);
            }
        """.trimIndent()
    }
}
