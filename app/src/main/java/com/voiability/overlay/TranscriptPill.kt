package com.voiability.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.TextView

/** A floating, non-touchable caption next to the bubble showing the live transcript. */
class TranscriptPill(context: Context, windowType: Int) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private val surface = sys(context, if (dark) android.R.color.system_neutral1_800 else android.R.color.system_neutral1_50, if (dark) 0xFF2B2930.toInt() else 0xFFF7F2FA.toInt())
    private val onSurface = sys(context, if (dark) android.R.color.system_neutral1_50 else android.R.color.system_neutral1_900, if (dark) 0xFFE6E0E9.toInt() else 0xFF1D1B20.toInt())

    private val text = TextView(context).apply {
        setTextColor(onSurface)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.START
        val h = (16 * density).toInt(); val v = (11 * density).toInt()
        setPadding(h, v, h, v)
        background = GradientDrawable().apply {
            cornerRadius = 22 * density
            setColor(surface)
        }
        elevation = 6 * density
        alpha = 0f
        scaleX = 0.92f; scaleY = 0.92f
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        title = "Voiability caption"
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }

    private var added = false
    private var visible = false
    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)

    fun show(message: String, anchorX: Float, anchorY: Float, radius: Float, rightSide: Boolean, screenWidth: Int) {
        text.text = message
        text.maxWidth = (screenWidth - radius * 2 - 56 * density).toInt()
        params.gravity = Gravity.TOP or if (rightSide) Gravity.END else Gravity.START
        params.x = (radius * 2 + 14 * density).toInt()
        params.y = (anchorY - radius * 0.9f).toInt()
        if (!added) {
            runCatching { wm.addView(text, params) }.onSuccess { added = true }
        } else {
            runCatching { wm.updateViewLayout(text, params) }
        }
        if (!visible) {
            visible = true
            text.pivotX = if (rightSide) text.width.toFloat() else 0f
            text.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(ease).start()
        }
    }

    fun hide() {
        if (!visible) return
        visible = false
        text.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(180).setInterpolator(ease)
            .withEndAction { if (!visible && added) { runCatching { wm.removeView(text) }; added = false } }
            .start()
    }

    private fun sys(context: Context, id: Int, fallback: Int) =
        if (Build.VERSION.SDK_INT >= 31) context.getColor(id) else fallback
}
