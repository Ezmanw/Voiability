package com.voiability.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BubbleChart
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voiability.VoiApp
import com.voiability.data.Style
import com.voiability.dictation.DictationState
import com.voiability.dictation.TextSink
import com.voiability.ime.toVisual
import com.voiability.ui.Page
import com.voiability.ui.SystemIntents
import com.voiability.ui.Tab
import com.voiability.ui.components.MorphingMic
import com.voiability.ui.components.Pill
import com.voiability.ui.rememberSystemStatus
import com.voiability.overlay.VoiAccessibilityService

@Composable
fun HomeScreen(padding: PaddingValues, onOpen: (Page) -> Unit, onTab: (Tab) -> Unit) {
    val graph = VoiApp.graph
    val context = LocalContext.current
    val settings by graph.settings.settings.collectAsState()
    val state by graph.dictation.state.collectAsState()
    val level by graph.dictation.level.collectAsState()
    val engine by graph.engines.status.collectAsState()
    val history by graph.history.items.collectAsState()
    val modelStates by graph.models.states.collectAsState()
    val system = rememberSystemStatus()
    val clipboard = LocalClipboardManager.current

    var field by remember { mutableStateOf(TextFieldValue("")) }
    var tryStyle by remember { mutableStateOf<Style?>(null) }
    val sink = remember {
        object : TextSink {
            override val packageName: String? = null
            override val appLabel: String? = null
            override fun insert(text: String) {
                val v = field
                val before = v.text.substring(0, v.selection.start)
                val after = v.text.substring(v.selection.end)
                val piece = VoiAccessibilityService.smartSpacing(before, text, after)
                field = TextFieldValue(before + piece + after, TextRange(before.length + piece.length))
            }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text("Voiability", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("100% on-device", Icons.Rounded.Lock)
                    val ready = engine.speechReady
                    Pill(
                        when {
                            engine.loadingSpeech -> "Warming up…"
                            ready && engine.polishReady -> "Ready · AI polish on"
                            ready -> "Ready"
                            else -> "Standby"
                        },
                        Icons.Rounded.Bolt,
                        color = if (ready) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (ready) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Warnings that block dictation
        val noModel = settings.sttModelId.isEmpty() || modelStates[settings.sttModelId] !is com.voiability.models.ModelState.Ready
        if (!system.mic || noModel || engine.error != null) {
            item {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            when {
                                engine.error != null -> engine.error!!
                                !system.mic -> "Voiability needs the microphone to hear you."
                                else -> "Download a speech model to start dictating."
                            },
                            Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        if (!system.mic) TextButton(onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow") }
                        else if (noModel) TextButton(onClick = { onTab(Tab.MODELS) }) { Text("Models") }
                    }
                }
            }
        }

        // Try it
        item {
            Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(20.dp).animateContentSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Try it", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (field.text.isNotEmpty()) {
                            IconButton(onClick = { clipboard.setText(AnnotatedString(field.text)) }) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                            TextButton(onClick = { field = TextFieldValue("") }) { Text("Clear") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = field,
                        onValueChange = { field = it },
                        placeholder = { Text("Tap the mic and say something like “um so lunch tomorrow at noon, no wait, one”") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        settings.allStyles.forEach { style ->
                            val selected = (tryStyle ?: settings.style(settings.defaultStyleId)).id == style.id
                            FilterChip(selected = selected, onClick = { tryStyle = style }, label = { Text("${style.emoji} ${style.name}") })
                        }
                    }
                    val caption = when (val s = state) {
                        is DictationState.Listening -> s.partial
                        is DictationState.Transcribing -> s.partial.ifBlank { "Transcribing…" }
                        is DictationState.Polishing -> s.text.ifBlank { "${s.style.emoji} Polishing…" }
                        is DictationState.Error -> s.message
                        else -> ""
                    }
                    AnimatedVisibility(caption.isNotEmpty()) {
                        Text(
                            caption, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    MorphingMic(
                        visual = state.toVisual(),
                        level = level,
                        onTap = {
                            if (!system.mic) micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            else graph.dictation.toggle(sink, tryStyle ?: settings.style(settings.defaultStyleId))
                        },
                        onHoldStart = { graph.dictation.start(sink, tryStyle ?: settings.style(settings.defaultStyleId)) },
                        onHoldEnd = { graph.dictation.stop() },
                    )
                }
            }
        }

        // Where to use it
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                UseCard(
                    title = "Bubble",
                    body = if (system.bubbleService) "Floats beside any keyboard" else "Dictate over any app",
                    icon = Icons.Rounded.BubbleChart,
                    on = system.bubbleService && settings.bubble.enabled,
                    action = if (system.bubbleService) "Customize" else "Turn on",
                    modifier = Modifier.weight(1f),
                ) { if (system.bubbleService) onOpen(Page.BUBBLE) else SystemIntents.accessibility(context) }
                UseCard(
                    title = "Keyboard",
                    body = if (system.keyboardSelected) "In use right now" else "A mic-first keyboard",
                    icon = Icons.Rounded.Keyboard,
                    on = system.keyboardEnabled,
                    action = when {
                        !system.keyboardEnabled -> "Enable"
                        !system.keyboardSelected -> "Switch to it"
                        else -> "Change"
                    },
                    modifier = Modifier.weight(1f),
                ) { if (!system.keyboardEnabled) SystemIntents.keyboards(context) else SystemIntents.pickKeyboard(context) }
            }
        }

        // Stats
        if (history.isNotEmpty()) {
            item {
                val words = history.sumOf { it.text.split(Regex("\\s+")).count { w -> w.isNotBlank() } }
                val avgMs = history.take(20).map { it.sttMs + it.llmMs }.average().toLong()
                // Typing ~40 wpm vs speaking ~150 wpm.
                val savedMin = words / 40.0 - history.sumOf { it.audioMs } / 60_000.0
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("$words", "words dictated", Icons.Rounded.Mic, Modifier.weight(1f))
                    Stat("%.1fs".format(avgMs / 1000.0), "avg. processing", Icons.Rounded.Bolt, Modifier.weight(1f))
                    Stat("${savedMin.coerceAtLeast(0.0).toInt()}m", "time saved", Icons.Rounded.Timer, Modifier.weight(1f))
                }
            }
            item {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onOpen(Page.HISTORY) }) { Text("See all") }
                        }
                        history.take(3).forEach { item ->
                            Surface(
                                onClick = { clipboard.setText(AnnotatedString(item.text)) },
                                color = Color.Transparent,
                            ) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                                    Text(item.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        listOfNotNull(item.app, settings.style(item.styleId).let { "${it.emoji} ${it.name}" }).joinToString(" · "),
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UseCard(
    title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, action: String,
    modifier: Modifier, onClick: () -> Unit,
) {
    Surface(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(28.dp),
        color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(18.dp)) {
            Icon(icon, null, tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2)
            Spacer(Modifier.height(10.dp))
            if (on) FilledTonalButton(onClick = onClick) { Text(action) } else Button(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
