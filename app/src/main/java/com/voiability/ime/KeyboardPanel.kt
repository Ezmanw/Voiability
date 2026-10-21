package com.voiability.ime

import android.view.inputmethod.EditorInfo
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voiability.VoiApp
import com.voiability.data.Style
import com.voiability.dictation.DictationState
import com.voiability.ui.components.MicVisual
import com.voiability.ui.components.MorphingMic
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun KeyboardPanel(
    pickedStyle: Style?,
    editorAction: Int,
    onPickStyle: (Style?) -> Unit,
    onMic: () -> Unit,
    onMicHoldStart: () -> Unit,
    onMicHoldEnd: () -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onCommit: (String) -> Unit,
    onEnter: () -> Unit,
    onUndo: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val graph = VoiApp.graph
    val state by graph.dictation.state.collectAsState()
    val level by graph.dictation.level.collectAsState()
    val settings by graph.settings.settings.collectAsState()

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 6.dp)) {
            // Style chips
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = pickedStyle == null,
                    onClick = { onPickStyle(null) },
                    label = { Text("Auto") },
                    leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(FilterChipDefaults.IconSize)) },
                )
                settings.allStyles.forEach { style ->
                    FilterChip(
                        selected = pickedStyle?.id == style.id,
                        onClick = { onPickStyle(style) },
                        label = { Text("${style.emoji} ${style.name}") },
                    )
                }
            }

            // Live caption
            val caption = when (val s = state) {
                is DictationState.Listening -> s.partial.ifBlank { if (level > 0.1f) "Listening…" else "Speak now" }
                is DictationState.Transcribing -> s.partial.ifBlank { "Transcribing…" }
                is DictationState.Polishing -> s.text.ifBlank { "${s.style.emoji} Polishing…" }
                is DictationState.Done -> s.text
                is DictationState.Error -> s.message
                DictationState.Idle -> "Tap to talk · hold for push-to-talk"
            }
            AnimatedContent(
                targetState = caption,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "caption",
                modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 20.dp),
            ) { text ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state is DictationState.Idle) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyButton(Icons.Rounded.Keyboard, "Switch keyboard", onSwitchKeyboard)
                    KeyButton(Icons.Rounded.Settings, "Voiability settings", onOpenApp)
                }
                MorphingMic(
                    visual = state.toVisual(),
                    level = level,
                    onTap = onMic,
                    onHoldStart = onMicHoldStart,
                    onHoldEnd = onMicHoldEnd,
                    size = 92.dp,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RepeatingKey(Icons.AutoMirrored.Rounded.Backspace, "Backspace", onBackspace)
                    KeyButton(Icons.AutoMirrored.Rounded.Undo, "Undo dictation", onUndo)
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Wide(",", Modifier.width(52.dp)) { onCommit(",") }
                Surface(
                    onClick = onSpace,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SpaceBar, "Space") }
                }
                Wide(".", Modifier.width(52.dp)) { onCommit(".") }
                FilledTonalIconButton(onClick = onEnter, modifier = Modifier.size(width = 64.dp, height = 48.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(
                        when (editorAction) {
                            EditorInfo.IME_ACTION_SEND -> Icons.AutoMirrored.Rounded.Send
                            EditorInfo.IME_ACTION_SEARCH -> Icons.Rounded.Search
                            EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> Icons.Rounded.Done
                            else -> Icons.AutoMirrored.Rounded.KeyboardReturn
                        },
                        "Enter",
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun Wide(label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.height(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun KeyButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }, modifier = Modifier.size(52.dp)) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A key that repeats while held, like a real backspace. */
@Composable
private fun RepeatingKey(icon: ImageVector, label: String, onRepeat: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .size(52.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onRepeat()
                    val job = scope.launch {
                        delay(400)
                        var interval = 90L
                        while (true) {
                            onRepeat()
                            delay(interval)
                            interval = (interval * 0.92).toLong().coerceAtLeast(35)
                        }
                    }
                    waitForUpOrCancellation()
                    job.cancel()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface)
    }
}

fun DictationState.toVisual(): MicVisual = when (this) {
    is DictationState.Listening -> MicVisual.LISTENING
    is DictationState.Transcribing, is DictationState.Polishing -> MicVisual.WORKING
    is DictationState.Done -> MicVisual.DONE
    is DictationState.Error -> MicVisual.ERROR
    DictationState.Idle -> MicVisual.IDLE
}
