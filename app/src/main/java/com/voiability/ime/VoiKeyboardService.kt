package com.voiability.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.voiability.VoiApp
import com.voiability.data.Style
import com.voiability.dictation.DictationState
import com.voiability.dictation.TextSink
import com.voiability.overlay.VoiAccessibilityService
import com.voiability.ui.MainActivity
import com.voiability.ui.theme.VoiTheme

/** A minimal voice-first keyboard: one big mic, style chips, and the few keys you still need. */
class VoiKeyboardService : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    /** Style picked on the keyboard for this session; null = automatic per app. */
    private val pickedStyle = mutableStateOf<Style?>(null)
    private val editorAction = mutableStateOf(EditorInfo.IME_ACTION_UNSPECIFIED)
    private val graph get() = VoiApp.graph

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateInputView(): View {
        window.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(this@VoiKeyboardService)
            setViewTreeViewModelStoreOwner(this@VoiKeyboardService)
            setViewTreeSavedStateRegistryOwner(this@VoiKeyboardService)
            setContent {
                VoiTheme {
                    KeyboardPanel(
                        pickedStyle = pickedStyle.value,
                        editorAction = editorAction.value,
                        onPickStyle = { pickedStyle.value = it },
                        onMic = ::onMic,
                        onMicHoldStart = { if (!graph.dictation.state.value.isBusy) graph.dictation.start(sink(), pickedStyle.value) },
                        onMicHoldEnd = { graph.dictation.stop() },
                        onBackspace = ::backspace,
                        onSpace = { currentInputConnection?.commitText(" ", 1) },
                        onCommit = { currentInputConnection?.commitText(it, 1) },
                        onEnter = ::enter,
                        onUndo = ::undo,
                        onSwitchKeyboard = ::switchKeyboard,
                        onOpenApp = {
                            startActivity(Intent(this@VoiKeyboardService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                    )
                }
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorAction.value = info?.let {
            if (it.inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE != 0) EditorInfo.IME_ACTION_NONE
            else it.imeOptions and EditorInfo.IME_MASK_ACTION
        } ?: EditorInfo.IME_ACTION_UNSPECIFIED
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        graph.engines.warmUp()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (graph.dictation.state.value is DictationState.Listening) graph.dictation.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }

    private fun onMic() {
        val d = graph.dictation
        when {
            d.state.value is DictationState.Listening -> d.stop()
            d.state.value.isBusy -> Unit
            else -> d.start(sink(), pickedStyle.value)
        }
    }

    private fun sink(): TextSink {
        val pkg = currentInputEditorInfo?.packageName
        val label = pkg?.let {
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull()
        }
        return object : TextSink {
            override val packageName: String? = pkg
            override val appLabel: String? = label
            override fun insert(text: String) {
                val ic = currentInputConnection ?: return
                val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
                val after = ic.getTextAfterCursor(1, 0)?.toString().orEmpty()
                ic.commitText(VoiAccessibilityService.smartSpacing(before, text, after), 1)
            }
        }
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        val action = editorAction.value
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun undo() {
        val ic = currentInputConnection ?: return
        val last = graph.dictation.lastInserted ?: return
        val before = ic.getTextBeforeCursor(last.length + 2, 0)?.toString() ?: return
        val trimmed = before.trimEnd()
        if (trimmed.endsWith(last)) {
            ic.deleteSurroundingText(before.length - trimmed.length + last.length, 0)
        }
    }

    private fun switchKeyboard() {
        if (Build.VERSION.SDK_INT >= 28 && switchToPreviousInputMethod()) return
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
    }
}
