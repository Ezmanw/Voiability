package com.voiability.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.voiability.data.BubbleColor
import com.voiability.data.BubbleLook
import com.voiability.data.ListenAnim
import com.voiability.data.Spinner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.PhotoSizeSelectSmall
import androidx.compose.material.icons.rounded.Water
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.voiability.VoiApp
import com.voiability.data.BubbleSettings
import com.voiability.data.DictionaryEntry
import com.voiability.ui.components.BubblePreview
import com.voiability.ui.components.Group
import com.voiability.ui.components.SectionTitle
import com.voiability.ui.components.SwitchRow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { actions() },
                scrollBehavior = scroll,
            )
        },
        content = content,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BubbleScreen(onBack: () -> Unit) {
    val graph = VoiApp.graph
    val settings by graph.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val b = settings.bubble
    // Sliders update locally while dragging and persist on release, so the live bubble doesn't thrash.
    var size by remember { mutableFloatStateOf(b.sizeDp) }
    var opacity by remember { mutableFloatStateOf(b.idleOpacity) }
    fun save(t: (BubbleSettings) -> BubbleSettings) = scope.launch { graph.settings.update { it.copy(bubble = t(it.bubble)) } }

    DetailScaffold("Bubble", onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Box(Modifier.fillMaxWidth().height(200.dp)) {
                        BubblePreview(b.copy(sizeDp = size, idleOpacity = opacity), Modifier.fillMaxSize(), side = if (b.rightSide) 1 else -1)
                        Text(
                            "Preview cycles through resting, listening and working",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).fillMaxWidth(0.6f),
                        )
                    }
                }
            }
            item {
                Group(Modifier.padding(top = 12.dp)) {
                    SwitchRow("Show bubble", null, null, b.enabled) { v -> save { it.copy(enabled = v) } }
                    SwitchRow("Only while typing", "Appears when a keyboard opens", Icons.Rounded.Keyboard, b.onlyWhileTyping) { v -> save { it.copy(onlyWhileTyping = v) } }
                }
            }
            item { SectionTitle("Look") }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BubbleLook.entries.forEachIndexed { i, look ->
                        SegmentedButton(
                            selected = b.look == look,
                            onClick = { save { it.copy(look = look) } },
                            shape = SegmentedButtonDefaults.itemShape(i, BubbleLook.entries.size),
                        ) { Text(if (look == BubbleLook.FLAT) "Flat" else "Liquid") }
                    }
                }
            }
            if (b.look == BubbleLook.LIQUID) item {
                Group(Modifier.padding(top = 8.dp)) {
                    SwitchRow("Melt into the edge", "Stretches and snaps as you drag it", Icons.Rounded.Water, b.liquid) { v -> save { it.copy(liquid = v) } }
                }
            }
            item { SectionTitle("Colour") }
            item {
                val scheme = MaterialTheme.colorScheme
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    BubbleColor.entries.forEach { role ->
                        val (bg, fg) = when (role) {
                            BubbleColor.PRIMARY -> scheme.primary to scheme.onPrimary
                            BubbleColor.SECONDARY -> scheme.secondary to scheme.onSecondary
                            BubbleColor.TERTIARY -> scheme.tertiary to scheme.onTertiary
                            BubbleColor.NEUTRAL -> scheme.surfaceVariant to scheme.onSurfaceVariant
                            BubbleColor.INVERSE -> scheme.inverseSurface to scheme.inverseOnSurface
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                onClick = { save { it.copy(color = role) } },
                                shape = CircleShape, color = bg,
                                border = if (b.color == role) BorderStroke(3.dp, scheme.onSurface) else null,
                                modifier = Modifier.size(52.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (b.color == role) Icon(Icons.Rounded.Check, null, tint = fg)
                                }
                            }
                            Text(role.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            item { SectionTitle("While listening") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                    ListenAnim.entries.forEach { a ->
                        FilterChip(selected = b.listenAnim == a, onClick = { save { it.copy(listenAnim = a) } }, label = { Text(a.label) })
                    }
                }
            }
            item { SectionTitle("While working") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                    Spinner.entries.forEach { sp ->
                        FilterChip(selected = b.spinner == sp, onClick = { save { it.copy(spinner = sp) } }, label = { Text(sp.label) })
                    }
                }
            }
            item { SectionTitle("Size · ${size.toInt()} dp") }
            item {
                Group {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PhotoSizeSelectSmall, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Slider(
                            value = size, onValueChange = { size = it }, valueRange = 40f..88f, steps = 11,
                            onValueChangeFinished = { save { it.copy(sizeDp = size) } },
                        )
                    }
                }
            }
            item { SectionTitle("Opacity when resting · ${(opacity * 100).toInt()}%") }
            item {
                Group {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Opacity, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Slider(
                            value = opacity, onValueChange = { opacity = it }, valueRange = 0.2f..1f,
                            onValueChangeFinished = { save { it.copy(idleOpacity = opacity) } },
                        )
                    }
                }
            }
            item {
                Text(
                    "Tap the bubble to start and stop. Hold it to talk, let go to type. Drag it anywhere and it snaps back to the nearest edge. Drag it onto the ✕ to hide it until the next time a keyboard opens.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
fun DictionaryScreen(onBack: () -> Unit) {
    val graph = VoiApp.graph
    val settings by graph.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var word by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }

    DetailScaffold("Dictionary", onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Teach Voiability names and words it keeps getting wrong. They're used as spelling hints for speech recognition and the AI.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            item {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(word, { word = it }, label = { Text("Word, e.g. Voiability") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(alias, { alias = it }, label = { Text("Often heard as (optional), e.g. voy ability") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(
                            enabled = word.isNotBlank(),
                            onClick = {
                                val entry = DictionaryEntry(word.trim(), alias.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                                scope.launch { graph.settings.update { s -> s.copy(dictionary = s.dictionary.filterNot { it.word.equals(entry.word, true) } + entry) } }
                                word = ""; alias = ""
                            },
                        ) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Add") }
                    }
                }
            }
            items(settings.dictionary, key = { it.word }) { entry ->
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.word, style = MaterialTheme.typography.bodyLarge)
                            if (entry.soundsLike.isNotEmpty()) {
                                Text("from “${entry.soundsLike.joinToString("”, “")}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { scope.launch { graph.settings.update { s -> s.copy(dictionary = s.dictionary - entry) } } }) {
                            Icon(Icons.Rounded.Close, "Remove")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val graph = VoiApp.graph
    val items by graph.history.items.collectAsState()
    val settings by graph.settings.settings.collectAsState()
    val clipboard = LocalClipboardManager.current
    var showRaw by remember { mutableStateOf<Long?>(null) }

    DetailScaffold("History", onBack, actions = {
        if (items.isNotEmpty()) IconButton(onClick = { graph.history.clear() }) { Icon(Icons.Rounded.DeleteSweep, "Clear all") }
    }) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Nothing yet. Your dictations show up here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    Surface(
                        onClick = { showRaw = if (showRaw == item.id) null else item.id },
                        shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text(item.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                IconButton(onClick = { clipboard.setText(AnnotatedString(item.text)) }) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                            }
                            if (showRaw == item.id && item.raw != item.text) {
                                Text("You said: ${item.raw}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(end = 12.dp, bottom = 4.dp))
                            }
                            val style = settings.style(item.styleId)
                            Text(
                                listOfNotNull(
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.time)),
                                    item.app, settings.label(style),
                                    "%.1fs speech + %.1fs AI".format(item.sttMs / 1000.0, item.llmMs / 1000.0),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
