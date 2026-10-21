package com.voiability.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.voiability.VoiApp
import com.voiability.data.Style
import com.voiability.data.Styles
import com.voiability.ui.components.Group
import com.voiability.ui.components.SectionTitle
import com.voiability.ui.components.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StylesScreen(padding: PaddingValues) {
    val graph = VoiApp.graph
    val settings by graph.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Style?>(null) }
    var pickingApp by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Text("Styles", style = MaterialTheme.typography.displaySmall)
                    Text(
                        "How your words come out. Tap to make one the default, or teach it your own voice.",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(settings.allStyles, key = { it.id }) { style ->
                StyleCard(
                    style = style,
                    isDefault = settings.defaultStyleId == style.id,
                    onClick = { scope.launch { graph.settings.update { it.copy(defaultStyleId = style.id) } } },
                    onEdit = { editing = style },
                )
            }
            item { SectionTitle("Per-app styles") }
            item {
                Group {
                    val pm = LocalContext.current.packageManager
                    settings.appStyles.entries.sortedBy { appLabel(pm, it.key) }.forEach { (pkg, styleId) ->
                        AppStyleRow(pkg, settings.style(styleId), settings.allStyles,
                            onPick = { s -> scope.launch { graph.settings.update { it.copy(appStyles = it.appStyles + (pkg to s.id)) } } },
                            onRemove = { scope.launch { graph.settings.update { it.copy(appStyles = it.appStyles - pkg) } } },
                        )
                    }
                    TextButton(onClick = { pickingApp = true }, modifier = Modifier.padding(start = 8.dp)) {
                        Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Add an app")
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = Styles.newCustom() },
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text("New style") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = padding.calculateBottomPadding() + 20.dp),
        )
    }

    editing?.let { style ->
        StyleEditorSheet(
            initial = style,
            onDismiss = { editing = null },
            onSave = { saved ->
                scope.launch {
                    graph.settings.update { s ->
                        if (saved.builtIn) s
                        else s.copy(customStyles = s.customStyles.filterNot { it.id == saved.id } + saved)
                    }
                }
                editing = null
            },
            onDelete = if (!style.builtIn && settings.customStyles.any { it.id == style.id }) {
                {
                    scope.launch {
                        graph.settings.update { s ->
                            s.copy(
                                customStyles = s.customStyles.filterNot { it.id == style.id },
                                defaultStyleId = if (s.defaultStyleId == style.id) Styles.CLEAN else s.defaultStyleId,
                                appStyles = s.appStyles.filterValues { it != style.id },
                            )
                        }
                    }
                    editing = null
                }
            } else null,
        )
    }

    if (pickingApp) {
        AppPickerSheet(onDismiss = { pickingApp = false }) { pkg ->
            pickingApp = false
            scope.launch { graph.settings.update { it.copy(appStyles = it.appStyles + (pkg to it.defaultStyleId)) } }
        }
    }
}

@Composable
private fun StyleCard(style: Style, isDefault: Boolean, onClick: () -> Unit, onEdit: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(if (isDefault) 32.dp else 24.dp),
        color = if (isDefault) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(style.emoji, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(style.name, style = MaterialTheme.typography.titleMedium)
                    if (isDefault) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Rounded.CheckCircle, "Default", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    style.instruction.ifBlank { "No instructions yet" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (style.exampleOut.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "“${style.exampleOut.lineSequence().first()}”", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "Edit or test") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StyleEditorSheet(initial: Style, onDismiss: () -> Unit, onSave: (Style) -> Unit, onDelete: (() -> Unit)?) {
    val graph = VoiApp.graph
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var style by remember { mutableStateOf(initial) }
    var sample by remember { mutableStateOf("um so yeah i think we should uh grab dinner on friday no wait saturday and maybe invite Sam too") }
    var result by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val readOnly = initial.builtIn

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (readOnly) "${style.emoji} ${style.name}" else "Edit style", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete") }
            }
            if (!readOnly) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = style.emoji, onValueChange = { style = style.copy(emoji = it.take(4)) },
                        label = { Text("Emoji") }, singleLine = true, modifier = Modifier.width(90.dp),
                    )
                    OutlinedTextField(
                        value = style.name, onValueChange = { style = style.copy(name = it.take(24)) },
                        label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = style.instruction,
                    onValueChange = { style = style.copy(instruction = it.take(600)) },
                    label = { Text("How should it sound?") },
                    placeholder = { Text("e.g. make it really casual, no capitals, a bit sarcastic, use british spelling") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "This goes straight into the on-device AI's instructions. Switches below are always enforced, even if the AI forgets.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(style.instruction, style = MaterialTheme.typography.bodyLarge)
            }
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column {
                    SwitchRow("all lowercase", "no capitals, ever", checked = style.lowercase) { if (!readOnly) style = style.copy(lowercase = it) }
                    SwitchRow("No period at the end", "reads more like a text", checked = style.dropFinalPeriod) { if (!readOnly) style = style.copy(dropFinalPeriod = it) }
                    SwitchRow("AI rewrite", "Off = just remove fillers, fast", checked = style.useLlm) { if (!readOnly) style = style.copy(useLlm = it) }
                }
            }

            Text("Test it", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = sample, onValueChange = { sample = it }, minLines = 2,
                label = { Text("Pretend you said…") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                enabled = !running && sample.isNotBlank(),
                onClick = {
                    running = true
                    scope.launch {
                        result = runCatching { graph.dictation.previewStyle(sample, style) }.getOrElse { "Couldn't run: ${it.message}" }
                        running = false
                    }
                },
            ) {
                Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Run on device")
            }
            AnimatedVisibility(running) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            result?.let {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(it, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            if (!readOnly) {
                Button(
                    onClick = { onSave(style) },
                    enabled = style.name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Save style") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AppStyleRow(pkg: String, style: Style, styles: List<Style>, onPick: (Style) -> Unit, onRemove: () -> Unit) {
    val pm = LocalContext.current.packageManager
    var menu by remember { mutableStateOf(false) }
    val icon = remember(pkg) { runCatching { pm.getApplicationIcon(pkg) }.getOrNull() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon)
        Spacer(Modifier.width(14.dp))
        Text(appLabel(pm, pkg), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            TextButton(onClick = { menu = true }) { Text("${style.emoji} ${style.name}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                styles.forEach { s ->
                    DropdownMenuItem(text = { Text("${s.emoji} ${s.name}") }, onClick = { menu = false; onPick(s) })
                }
            }
        }
        IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun AppIcon(icon: Drawable?) {
    val bmp = remember(icon) { icon?.toBitmap(96, 96)?.asImageBitmap() }
    if (bmp != null) Image(bmp, null, Modifier.size(36.dp))
    else Icon(Icons.Rounded.Apps, null, Modifier.size(36.dp))
}

private data class AppEntry(val pkg: String, val label: String, val icon: Drawable?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != context.packageName }
                .map { AppEntry(it, appLabel(pm, it), runCatching { pm.getApplicationIcon(it) }.getOrNull()) }
                .sortedBy { it.label.lowercase() }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, label = { Text("Search apps") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        val list = apps
        if (list == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
        } else {
            LazyColumn(Modifier.fillMaxWidth().height(480.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(list.filter { query.isBlank() || it.label.contains(query, true) }, key = { it.pkg }) { app ->
                    Surface(onClick = { onPick(app.pkg) }, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(app.icon)
                            Spacer(Modifier.width(14.dp))
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

private fun appLabel(pm: PackageManager, pkg: String): String =
    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
