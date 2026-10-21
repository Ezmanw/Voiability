package com.voiability.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.BubbleChart
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voiability.VoiApp
import com.voiability.data.Settings
import com.voiability.ui.Page
import com.voiability.ui.SystemIntents
import com.voiability.ui.components.Group
import com.voiability.ui.components.SectionTitle
import com.voiability.ui.components.SettingRow
import com.voiability.ui.components.SwitchRow
import com.voiability.ui.rememberSystemStatus
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(padding: PaddingValues, onOpen: (Page) -> Unit) {
    val graph = VoiApp.graph
    val settings by graph.settings.settings.collectAsState()
    val system = rememberSystemStatus()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun update(t: (Settings) -> Settings) = scope.launch { graph.settings.update(t) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text("Settings", style = MaterialTheme.typography.displaySmall)
            }
        }
        item { SectionTitle("Where you dictate") }
        item {
            Group {
                SettingRow(
                    "Floating bubble",
                    if (system.bubbleService) "Size, transparency, liquid edge" else "Off · needs Accessibility to type for you",
                    Icons.Rounded.BubbleChart,
                    onClick = { if (system.bubbleService) onOpen(Page.BUBBLE) else SystemIntents.accessibility(context) },
                )
                SettingRow(
                    "Voiability keyboard",
                    when {
                        system.keyboardSelected -> "Active"
                        system.keyboardEnabled -> "Enabled · tap to switch"
                        else -> "Off · tap to enable"
                    },
                    Icons.Rounded.Keyboard,
                    onClick = { if (system.keyboardEnabled) SystemIntents.pickKeyboard(context) else SystemIntents.keyboards(context) },
                )
                if (system.bubbleService) {
                    SettingRow("Accessibility settings", "Turn the bubble service off", Icons.Rounded.Accessibility, onClick = { SystemIntents.accessibility(context) })
                }
            }
        }
        item { SectionTitle("Dictation") }
        item {
            Group {
                SwitchRow("Live preview", "See words as you speak", Icons.Rounded.Visibility, settings.livePreview) { v -> update { it.copy(livePreview = v) } }
                AutoStopRow(settings.autoStopSeconds) { v -> update { it.copy(autoStopSeconds = v) } }
                SwitchRow("Remove filler words", "um, uh, erm…", Icons.Rounded.CleaningServices, settings.removeFillers) { v -> update { it.copy(removeFillers = v) } }
                SwitchRow("Spoken commands", "“new line”, “new paragraph”, “scratch that”", Icons.Rounded.RecordVoiceOver, settings.spokenCommands) { v -> update { it.copy(spokenCommands = v) } }
                SwitchRow("Haptics", null, Icons.Rounded.Vibration, settings.haptics) { v -> update { it.copy(haptics = v) } }
                SettingRow("Dictionary", "${settings.dictionary.size} words · names, jargon, spellings", Icons.AutoMirrored.Rounded.MenuBook, onClick = { onOpen(Page.DICTIONARY) })
            }
        }
        item { SectionTitle("History") }
        item {
            Group {
                SwitchRow("Keep history", "Stored only on this phone", Icons.Rounded.History, settings.keepHistory) { v -> update { it.copy(keepHistory = v) } }
                SettingRow("View history", null, Icons.Rounded.KeyboardVoice, onClick = { onOpen(Page.HISTORY) })
            }
        }
        item { SectionTitle("Privacy") }
        item {
            Group {
                SettingRow(
                    "Everything stays on your phone",
                    "Speech recognition and AI polish run on this device's CPU. The internet is only used to download models you pick. No accounts, no analytics.",
                    Icons.Rounded.PrivacyTip,
                )
            }
        }
        item {
            Text(
                "Voiability 1.0 · whisper.cpp + llama.cpp",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun AutoStopRow(seconds: Float, onPick: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = listOf(0f to "Off · tap to stop", 1.5f to "After 1.5 s of silence", 3f to "After 3 s of silence", 5f to "After 5 s of silence")
    Box {
        SettingRow("Auto-stop", options.firstOrNull { it.first == seconds }?.second ?: "Off", Icons.Rounded.Timer, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(v) }) }
        }
    }
}
