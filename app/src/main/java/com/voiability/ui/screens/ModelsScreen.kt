package com.voiability.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Recommend
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.voiability.VoiApp
import com.voiability.models.ModelCatalog
import com.voiability.models.ModelInfo
import com.voiability.models.ModelKind
import com.voiability.models.ModelState
import com.voiability.ui.components.DotMeter
import com.voiability.ui.components.Group
import com.voiability.ui.components.SectionTitle
import com.voiability.ui.components.SwitchRow
import com.voiability.ui.components.formatBytes
import com.voiability.ui.rememberSystemStatus
import kotlinx.coroutines.launch

private val languages = listOf(
    "auto" to "Detect automatically", "en" to "English", "es" to "Español", "fr" to "Français", "de" to "Deutsch",
    "it" to "Italiano", "pt" to "Português", "nl" to "Nederlands", "pl" to "Polski", "tr" to "Türkçe",
    "ru" to "Русский", "uk" to "Українська", "ja" to "日本語", "ko" to "한국어", "zh" to "中文", "hi" to "हिन्दी", "ar" to "العربية",
)

@Composable
fun ModelsScreen(padding: PaddingValues) {
    val graph = VoiApp.graph
    val settings by graph.settings.settings.collectAsState()
    val states by graph.models.states.collectAsState()
    val system = rememberSystemStatus()
    val scope = rememberCoroutineScope()
    val (recSpeech, recPolish) = remember(system.totalRamGb) { ModelCatalog.recommend(system.totalRamGb) }

    fun select(model: ModelInfo) = scope.launch {
        graph.settings.update {
            if (model.kind == ModelKind.SPEECH) it.copy(sttModelId = model.id) else it.copy(llmModelId = model.id, polish = true)
        }
        graph.engines.warmUp()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text("Models", style = MaterialTheme.typography.displaySmall)
                Text(
                    "They run entirely on this phone. Your voice never leaves it.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Recommend, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Best for your phone", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text(
                            "%.0f GB RAM · ${recSpeech.name}${recPolish?.let { " + ${it.name}" } ?: ""}".format(system.totalRamGb),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    val missing = listOfNotNull(recSpeech, recPolish).filter { states[it.id] !is ModelState.Ready && states[it.id] !is ModelState.Downloading }
                    if (missing.isNotEmpty()) {
                        Button(onClick = { missing.forEach { m -> graph.models.download(m) { ok -> if (ok) select(m) } } }) {
                            Text(formatBytes(missing.sumOf { it.sizeBytes }))
                        }
                    }
                }
            }
        }

        item { SectionTitle("Speech to text") }
        items(ModelCatalog.speech, key = { it.id }) { model ->
            ModelCard(model, states[model.id] ?: ModelState.Missing, selected = settings.sttModelId == model.id, recommended = model == recSpeech, onSelect = { select(model) })
        }
        val sttModel = ModelCatalog.byId(settings.sttModelId)
        if (sttModel != null && !sttModel.englishOnly) {
            item { LanguagePicker(settings.language) { code -> scope.launch { graph.settings.update { it.copy(language = code) } } } }
        }

        item { SectionTitle("AI polish") }
        item {
            Group {
                SwitchRow(
                    "Polish with AI", "Removes rambling, applies your style. Turn off for raw speed.",
                    Icons.Rounded.AutoAwesome, checked = settings.polish,
                ) { on -> scope.launch { graph.settings.update { it.copy(polish = on) } } }
            }
        }
        items(ModelCatalog.polish, key = { it.id }) { model ->
            ModelCard(model, states[model.id] ?: ModelState.Missing, selected = settings.llmModelId == model.id, recommended = model == recPolish, onSelect = { select(model) })
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModelCard(model: ModelInfo, state: ModelState, selected: Boolean, recommended: Boolean, onSelect: () -> Unit) {
    val graph = VoiApp.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val ok = graph.models.import(model, uri)
            Toast.makeText(context, if (ok) "Imported ${model.name}" else "That file doesn't look like a ${if (model.kind == ModelKind.POLISH) ".gguf" else "ggml .bin"} model", Toast.LENGTH_SHORT).show()
        }
    }
    Surface(
        onClick = { if (state == ModelState.Ready) onSelect() },
        shape = RoundedCornerShape(if (selected) 32.dp else 24.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.name, style = MaterialTheme.typography.titleMedium)
                        if (recommended) {
                            Spacer(Modifier.width(8.dp))
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiary) {
                                Text("Recommended", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiary)
                            }
                        }
                    }
                    Text(model.tagline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (state) {
                    ModelState.Ready -> if (selected) Icon(Icons.Rounded.CheckCircle, "In use", tint = MaterialTheme.colorScheme.primary)
                    else FilledTonalButton(onClick = onSelect) { Text("Use") }
                    is ModelState.Downloading -> TextButton(onClick = { graph.models.cancel(model) }) { Text("Cancel") }
                    else -> Button(onClick = { graph.models.download(model) { ok -> if (ok) onSelect() } }) {
                        Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(6.dp)); Text(model.sizeLabel)
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Import from file…") }, onClick = { menu = false; importer.launch(arrayOf("*/*")) })
                        if (state == ModelState.Ready) {
                            DropdownMenuItem(text = { Text("Delete (${model.sizeLabel})") }, onClick = { menu = false; graph.models.delete(model) })
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                DotMeter("Speed", model.speed, invert = true)
                DotMeter("Quality", model.quality)
                if (model.kind == ModelKind.SPEECH) {
                    Text(
                        if (model.englishOnly) "English" else "99 languages",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AnimatedVisibility(state is ModelState.Downloading) {
                val d = state as? ModelState.Downloading
                Column(Modifier.padding(top = 12.dp)) {
                    LinearWavyProgressIndicator(progress = { d?.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${((d?.progress ?: 0f) * 100).toInt()}% · ${formatBytes(d?.bytesPerSec ?: 0)}/s",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state is ModelState.Failed) {
                Text(state.message, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun LanguagePicker(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Group {
        Box {
            com.voiability.ui.components.SettingRow(
                "Language", languages.firstOrNull { it.first == current }?.second ?: current, Icons.Rounded.Translate,
                onClick = { open = true },
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                languages.forEach { (code, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(code) })
                }
            }
        }
    }
}
