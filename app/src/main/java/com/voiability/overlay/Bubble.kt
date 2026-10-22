package com.voiability.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.voiability.data.BubbleLook
import com.voiability.data.BubbleSettings
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The floating dictation bubble: window management, touch, and spring physics.
 *
 * Two windows:
 *  - a full-screen, touch-through window that draws everything. It never moves or resizes
 *    while animating: Android applies a window's new position and its new contents in
 *    separate steps, so moving a drawing window makes its old frame flash in the wrong place.
 *  - a small invisible window over the bubble that only catches touches. It draws nothing,
 *    so moving it can't glitch. A gesture keeps going to it even when the finger leaves it.
 */
@SuppressLint("ClickableViewAccessibility")
class Bubble(
    private val context: Context,
    private val windowType: Int,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onTap()
        fun onHoldStart()
        fun onHoldEnd()
        fun onDismissed()
        fun onMoved(rightSide: Boolean, xFraction: Float, yFraction: Float)
        val haptics: Boolean
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    val view = LiquidBubbleView(context)
    private val touchView = View(context)
    private val params = overlayParams("Voiability bubble", touchable = false)
    private val touchParams = overlayParams("Voiability bubble touch", touchable = true)

    private fun overlayParams(name: String, touchable: Boolean) = WindowManager.LayoutParams(
        0, 0, windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
        }
        title = name
    }

    var isShown = false
        private set

    private var settings = BubbleSettings()
    private var r = 29f * density
    private var screenW = 0f
    private var screenH = 0f
    private var keyboardTop: Int? = null

    // Physics state, screen pixels.
    private var x = 0f
    private var y = 0f
    private var vx = 0f
    private var vy = 0f
    private var tx = 0f
    private var ty = 0f
    private var springing = false
    private var scale = 0f
    private var scaleTarget = 1f
    private var scaleV = 0f
    private var targetAmount = 0f
    private var targetGoal = 0f
    private var removeWhenGone = false
    private var dismissing = false

    private var lastFrame = 0L
    private var frameScheduled = false
    private val startTime = SystemClock.uptimeMillis()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    /** Liquid melts into the edge; Flat floats a little away from it like a FAB. */
    private val gap get() = if (settings.look == BubbleLook.LIQUID) 3f * density else 8f * density

    init {
        view.trackWindowPosition = true
        touchView.setOnTouchListener { _, e -> onTouch(e) }
    }

    // ---- public API ---------------------------------------------------------------------

    fun apply(newSettings: BubbleSettings) {
        val sizeChanged = newSettings.sizeDp != settings.sizeDp
        settings = newSettings
        r = newSettings.sizeDp / 2f * density
        view.radius = r
        view.liquid = newSettings.liquid
        view.look = newSettings.look
        view.listenAnim = newSettings.listenAnim
        view.spinner = newSettings.spinner
        if (view.colorRole != newSettings.color) view.colorRole = newSettings.color
        refreshAlpha()
        if (isShown && !dragging && !springing && !dismissing && (sizeChanged || !removeWhenGone)) dock(animate = false)
    }

    fun setKeyboardTop(top: Int?) {
        if (keyboardTop == top) return
        keyboardTop = top
        if (isShown && !dragging && !dismissing) {
            dockPosition()
            startSpring()
        }
    }

    fun show() {
        if (isShown) {
            // Changed our mind halfway through hiding: grow back instead.
            if (removeWhenGone && !dismissing) {
                removeWhenGone = false
                scaleTarget = 1f
                kick()
            }
            return
        }
        measureScreen()
        dockPosition()
        x = tx; y = ty
        scale = 0f
        scaleTarget = 1f
        removeWhenGone = false
        dismissing = false
        layoutWindow()
        placeTouch()
        runCatching { wm.addView(view, params) }.onFailure { return }
        runCatching { wm.addView(touchView, touchParams) }
        isShown = true
        kick()
    }

    fun hide() {
        if (!isShown) return
        scaleTarget = 0f
        removeWhenGone = true
        kick()
    }

    fun setMode(mode: BubbleMode) {
        if (view.mode == mode) return
        view.mode = mode
        refreshAlpha()
        // A little "boing" on every state change.
        scaleV += when (mode) {
            BubbleMode.LISTENING -> 6f
            BubbleMode.DONE -> 5f
            BubbleMode.ERROR -> -4f
            else -> 2f
        }
        kick()
    }

    fun setLevel(level: Float) {
        view.level = level
        if (view.mode == BubbleMode.LISTENING) kick()
    }

    /** Call after rotation or a display change. */
    fun onScreenChanged() {
        if (!isShown) return
        layoutWindow()
        dock(animate = false)
    }

    /** Where the bubble sits, for positioning the transcript pill. */
    val anchor: Triple<Float, Float, Float> get() = Triple(x, y, r)
    val screenWidth: Float get() = screenW

    // ---- layout -------------------------------------------------------------------------

    private fun measureScreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b: Rect = wm.maximumWindowMetrics.bounds
            screenW = b.width().toFloat(); screenH = b.height().toFloat()
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            screenW = dm.widthPixels.toFloat(); screenH = dm.heightPixels.toFloat()
        }
        view.screenWidth = screenW
    }

    private fun minY() = r * 2f + 24f * density
    private fun maxY(): Float {
        val kb = keyboardTop
        return if (kb != null && kb > 0) kb - r * 1.7f - 6f * density else screenH - r * 2f - 48f * density
    }

    private fun dockPosition() {
        tx = when {
            !settings.snapToEdge -> (settings.xFraction * screenW).coerceIn(r + gap, screenW - r - gap)
            settings.rightSide -> screenW - r - gap
            else -> r + gap
        }
        ty = (settings.yFraction * screenH).coerceIn(minY(), max(minY(), maxY()))
    }

    private fun dock(animate: Boolean) {
        dockPosition()
        if (!animate) {
            x = tx; y = ty; vx = 0f; vy = 0f
            placeTouch()
        } else startSpring()
        update()
        kick()
    }

    /** Sizes the drawing window to the whole screen. Only needed when the screen itself changes. */
    private fun layoutWindow() {
        measureScreen()
        params.x = 0; params.y = 0
        params.width = screenW.toInt(); params.height = screenH.toInt()
        view.originX = 0f; view.originY = 0f
        if (isShown) runCatching { wm.updateViewLayout(view, params) }
    }

    /** Moves the invisible touch target over the bubble's resting spot. */
    private fun placeTouch() {
        val size = (r * 2.7f).toInt()
        touchParams.width = size
        touchParams.height = size
        touchParams.x = (x - size / 2f).toInt()
        touchParams.y = (y - size / 2f).toInt()
        touchParams.flags = if (dismissing) touchParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else touchParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (isShown) runCatching { wm.updateViewLayout(touchView, touchParams) }
    }

    private fun refreshAlpha() {
        view.bodyAlpha = if (view.mode == BubbleMode.IDLE && !dragging) settings.idleOpacity else 1f
    }

    // ---- touch --------------------------------------------------------------------------

    private var downX = 0f
    private var downY = 0f
    private var grabDx = 0f
    private var grabDy = 0f
    private var dragging = false
    private var holding = false
    private var tracker: VelocityTracker? = null
    private var hot = false
    private val holdRunnable = Runnable {
        if (!dragging) {
            holding = true
            haptic(HapticFeedbackConstants.LONG_PRESS)
            callbacks.onHoldStart()
        }
    }

    private fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (hypot(e.rawX - x, e.rawY - y) > r * 1.35f || dismissing) return false
                downX = e.rawX; downY = e.rawY
                grabDx = x - e.rawX; grabDy = y - e.rawY
                dragging = false; holding = false
                tracker = VelocityTracker.obtain().also { track(it, e) }
                scaleTarget = 0.9f
                touchView.postDelayed(holdRunnable, HOLD_MS)
                haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                kick()
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.let { track(it, e) }
                if (!dragging && !holding && hypot(e.rawX - downX, e.rawY - downY) > touchSlop) {
                    dragging = true
                    touchView.removeCallbacks(holdRunnable)
                    scaleTarget = 1.08f
                    springing = false
                    refreshAlpha()
                }
                if (dragging) {
                    var nx = e.rawX + grabDx
                    var ny = e.rawY + grabDy
                    targetGoal = 1f
                    val (cx, cy) = targetCenter()
                    val d = hypot(nx - cx, ny - cy)
                    val nowHot = d < MAGNET_DP * density
                    if (nowHot != hot) {
                        hot = nowHot
                        haptic(if (Build.VERSION.SDK_INT >= 34) { if (hot) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE } else HapticFeedbackConstants.CLOCK_TICK)
                    }
                    if (hot) {
                        // Get sucked towards the target.
                        val pull = 1f - d / (MAGNET_DP * density)
                        nx += (cx - nx) * (0.35f + 0.5f * pull)
                        ny += (cy - ny) * (0.35f + 0.5f * pull)
                    }
                    x = nx; y = ny
                    kick()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchView.removeCallbacks(holdRunnable)
                tracker?.let { track(it, e) }
                tracker?.computeCurrentVelocity(1000)
                val maxV = MAX_FLING_DP * density
                val fvx = (tracker?.xVelocity ?: 0f).coerceIn(-maxV, maxV)
                val fvy = (tracker?.yVelocity ?: 0f).coerceIn(-maxV, maxV)
                tracker?.recycle(); tracker = null
                scaleTarget = 1f
                when {
                    dragging -> release(fvx, fvy)
                    holding -> callbacks.onHoldEnd()
                    e.actionMasked == MotionEvent.ACTION_UP -> callbacks.onTap()
                }
                dragging = false
                holding = false
                refreshAlpha()
                kick()
            }
        }
        return true
    }

    /** Feeds the tracker screen coordinates, so velocity never depends on where the touch window sits. */
    private fun track(tracker: VelocityTracker, e: MotionEvent) {
        val copy = MotionEvent.obtain(e)
        copy.setLocation(e.rawX, e.rawY)
        tracker.addMovement(copy)
        copy.recycle()
    }

    private fun targetCenter(): Pair<Float, Float> = screenW / 2f to screenH - 120f * density

    private fun release(fvx: Float, fvy: Float) {
        targetGoal = 0f
        if (hot) {
            hot = false
            dismissing = true
            placeTouch()
            val (cx, cy) = targetCenter()
            tx = cx; ty = cy
            vx = 0f; vy = 0f
            scaleTarget = 0f
            removeWhenGone = true
            haptic(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
            targetGoal = 1f
            touchView.postDelayed({ targetGoal = 0f; kick() }, 220)
            startSpring()
            callbacks.onDismissed()
            return
        }
        val flingSide = when {
            fvx > FLING_DP * density -> true
            fvx < -FLING_DP * density -> false
            else -> x > screenW / 2f
        }
        // A little inertia, so a flick carries it a bit further before it settles.
        val restX = x + fvx * 0.08f
        settings = settings.copy(
            rightSide = if (settings.snapToEdge) flingSide else restX > screenW / 2f,
            xFraction = (restX / screenW).coerceIn(0f, 1f),
            yFraction = ((y + fvy * 0.08f) / screenH).coerceIn(0.05f, 0.95f),
        )
        dockPosition()
        settings = settings.copy(xFraction = tx / screenW, yFraction = ty / screenH)
        callbacks.onMoved(settings.rightSide, settings.xFraction, settings.yFraction)
        vx = fvx; vy = fvy
        startSpring()
    }

    private fun startSpring() {
        springing = true
        kick()
    }

    private fun haptic(constant: Int) {
        if (callbacks.haptics) touchView.performHapticFeedback(constant)
    }

    // ---- animation loop -----------------------------------------------------------------

    private val frameCallback = Choreographer.FrameCallback { frameNanos -> frame(frameNanos) }

    private fun kick() {
        if (frameScheduled) return
        frameScheduled = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun frame(nanos: Long) {
        frameScheduled = false
        val dt = if (lastFrame == 0L) 1f / 120f else min((nanos - lastFrame) / 1e9f, 1f / 30f)
        lastFrame = nanos
        var moving = false

        if (springing && !dragging) {
            val k = SPRING_K
            val c = 2f * SPRING_ZETA * sqrt(k)
            vx += (-k * (x - tx) - c * vx) * dt
            vy += (-k * (y - ty) - c * vy) * dt
            x += vx * dt
            y += vy * dt
            if (abs(x - tx) < 0.5f && abs(y - ty) < 0.5f && abs(vx) < 20f && abs(vy) < 20f) {
                x = tx; y = ty; vx = 0f; vy = 0f
                springing = false
                if (!removeWhenGone) placeTouch()
            } else moving = true
        }

        // Scale spring (bouncier than position).
        val ks = 600f
        val cs = 2f * 0.45f * sqrt(ks)
        scaleV += (-ks * (scale - scaleTarget) - cs * scaleV) * dt
        scale += scaleV * dt
        if (abs(scale - scaleTarget) > 0.002f || abs(scaleV) > 0.01f) moving = true else { scale = scaleTarget; scaleV = 0f }

        targetAmount += (targetGoal - targetAmount) * min(1f, dt * 14f)
        if (abs(targetAmount - targetGoal) > 0.003f) moving = true else targetAmount = targetGoal

        update()

        if (removeWhenGone && scale <= 0.02f && scaleTarget == 0f && targetAmount < 0.01f) {
            runCatching { wm.removeView(view) }
            runCatching { wm.removeView(touchView) }
            isShown = false
            removeWhenGone = false
            dismissing = false
            springing = false
            return
        }
        if (moving || dragging || view.isAnimating) kick()
    }

    private fun update() {
        view.ballX = x
        view.ballY = y
        view.scale = max(0f, scale)
        view.time = (SystemClock.uptimeMillis() - startTime) / 1000f
        val reach = r * 2.4f
        view.leftPull = 1f - smooth((x - r) / reach)
        view.rightPull = 1f - smooth((screenW - x - r) / reach)
        val (cx, cy) = targetCenter()
        view.targetX = cx
        view.targetY = cy
        view.targetRadius = 30f * density
        view.targetAmount = targetAmount
        view.targetHot = hot || dismissing
        view.invalidate()
    }

    private fun smooth(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    private companion object {
        const val HOLD_MS = 320L
        const val MAGNET_DP = 90f
        const val FLING_DP = 900f
        const val MAX_FLING_DP = 3500f
        const val SPRING_K = 260f
        const val SPRING_ZETA = 0.62f
    }
}
