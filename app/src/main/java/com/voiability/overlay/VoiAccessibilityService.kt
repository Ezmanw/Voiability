package com.voiability.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.voiability.VoiApp
import com.voiability.dictation.DictationState
import com.voiability.dictation.TextSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hosts the floating bubble. Accessibility is used for exactly two things: noticing when a
 * keyboard is on screen, and typing the finished text into the focused field.
 */
class VoiAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var bubble: Bubble
    private lateinit var pill: TranscriptPill
    private val graph get() = VoiApp.graph

    /** Hidden by dragging onto the X; comes back the next time a keyboard opens. */
    private var snoozed = false
    private var keyboardWasVisible = false
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = Runnable { updateVisibility() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        pill = TranscriptPill(this, type)
        bubble = Bubble(this, type, object : Bubble.Callbacks {
            override fun onTap() {
                val d = graph.dictation
                when {
                    d.state.value is DictationState.Listening -> d.stop()
                    d.state.value.isBusy -> Unit
                    else -> d.start(sink())
                }
            }
            override fun onHoldStart() = graph.dictation.start(sink())
            override fun onHoldEnd() = graph.dictation.stop()
            override fun onDismissed() {
                snoozed = true
                graph.dictation.cancel()
                pill.hide()
            }
            override fun onMoved(rightSide: Boolean, yFraction: Float) {
                scope.launch { graph.settings.update { it.copy(bubble = it.bubble.copy(rightSide = rightSide, yFraction = yFraction)) } }
            }
            override val haptics: Boolean get() = graph.settings.current.haptics
        })
        bubble.apply(graph.settings.current.bubble)

        scope.launch {
            graph.settings.settings.map { it.bubble }.distinctUntilChanged().collect {
                bubble.apply(it)
                updateVisibility()
            }
        }
        scope.launch {
            graph.dictation.state.collect { state ->
                bubble.setMode(
                    when (state) {
                        is DictationState.Listening -> BubbleMode.LISTENING
                        is DictationState.Transcribing, is DictationState.Polishing -> BubbleMode.PROCESSING
                        is DictationState.Done -> BubbleMode.DONE
                        is DictationState.Error -> BubbleMode.ERROR
                        DictationState.Idle -> BubbleMode.IDLE
                    }
                )
                val caption = when (state) {
                    is DictationState.Listening -> state.partial.ifBlank { null }
                    is DictationState.Transcribing -> state.partial.ifBlank { null }
                    is DictationState.Polishing -> state.text.ifBlank { null }?.let { t -> graph.settings.current.let { if (it.showStyleEmoji && state.style.emoji.isNotBlank()) "${state.style.emoji} $t" else t } }
                    is DictationState.Error -> state.message
                    else -> null
                }
                if (caption != null && bubble.isShown) {
                    val (x, y, r) = bubble.anchor
                    pill.show(caption, x, y, r, bubble.dockedRight, resources.displayMetrics.widthPixels)
                } else pill.hide()
                if (!state.isBusy) updateVisibility()
            }
        }
        scope.launch { graph.dictation.level.collect { bubble.setLevel(it) } }
        updateVisibility()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::bubble.isInitialized) return
        // Coalesce bursts of events into one check. (Not view.post: the bubble may be detached.)
        handler.removeCallbacks(updateRunnable)
        handler.postDelayed(updateRunnable, 60)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        if (::bubble.isInitialized) bubble.hide()
        if (::pill.isInitialized) pill.hide()
        super.onDestroy()
    }

    private fun keyboardBounds(): Rect? = runCatching {
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let { w ->
            Rect().also { w.getBoundsInScreen(it) }
        }
    }.getOrNull()

    private fun ourKeyboardActive(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true

    private fun updateVisibility() {
        val s = graph.settings.current
        val busy = graph.dictation.state.value.isBusy
        val kb = keyboardBounds()
        val keyboardVisible = kb != null
        if (keyboardVisible && !keyboardWasVisible) snoozed = false
        keyboardWasVisible = keyboardVisible

        val activePkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        val inOwnApp = activePkg == packageName
        val focusedEditable = !keyboardVisible && s.bubble.onlyWhileTyping &&
            runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true }.getOrDefault(false)

        val want = s.onboarded && s.bubble.enabled && !snoozed && !inOwnApp && !ourKeyboardActive() &&
            (!s.bubble.onlyWhileTyping || keyboardVisible || focusedEditable)

        bubble.setKeyboardTop(kb?.top)
        when {
            want || busy -> bubble.show()
            else -> bubble.hide()
        }
    }

    // ---- inserting text ------------------------------------------------------------------

    private fun sink(): TextSink {
        val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        val label = pkg?.let {
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull()
        }
        return object : TextSink {
            override val packageName: String? = pkg
            override val appLabel: String? = label
            override fun insert(text: String) = insertIntoFocused(text)
        }
    }

    private fun insertIntoFocused(text: String) {
        val node = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (node == null || !node.isEditable) {
            copyFallback(text)
            return
        }
        val showingHint = node.isShowingHintText
        val existing = if (showingHint) "" else node.text?.toString().orEmpty()
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > existing.length || end > existing.length) {
            start = existing.length; end = existing.length
        }
        if (start > end) start = end.also { end = start }
        val before = existing.substring(0, start)
        val after = existing.substring(end)
        val piece = smartSpacing(before, text, after)
        val updated = before + piece + after

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            val caret = before.length + piece.length
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
                },
            )
            return
        }
        // Some apps (custom editors, web views) ignore SET_TEXT but accept a paste.
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Voiability", piece))
        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) copyFallback(text)
    }

    private fun copyFallback(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Voiability", text))
        Toast.makeText(this, "Copied — paste it anywhere", Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** Adds a space before/after the dictated text when it would otherwise glue onto a word. */
        fun smartSpacing(before: String, text: String, after: String): String {
            var t = text
            val prev = before.lastOrNull()
            if (prev != null && !prev.isWhitespace() && t.firstOrNull()?.let { it.isLetterOrDigit() || it == '"' || it == '(' } == true) t = " $t"
            val next = after.firstOrNull()
            if (next != null && !next.isWhitespace() && next.isLetterOrDigit()) t = "$t "
            return t
        }

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { it.startsWith(context.packageName + "/") }
        }
    }
}
