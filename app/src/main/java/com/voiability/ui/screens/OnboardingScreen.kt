package com.voiability.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BubbleChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voiability.VoiApp
import com.voiability.data.BubbleLook
import com.voiability.data.BubbleSettings
import com.voiability.data.ListenAnim
import com.voiability.models.ModelCatalog
import com.voiability.models.ModelInfo
import com.voiability.models.ModelState
import com.voiability.ui.SystemIntents
import com.voiability.ui.components.BubblePreview
import com.voiability.ui.components.Pill
import com.voiability.ui.components.formatBytes
import com.voiability.ui.rememberSystemStatus
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen() {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val graph = VoiApp.graph
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // Progress dots
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
            repeat(4) { i ->
                Surface(
                    shape = CircleShape,
                    color = if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.padding(4.dp).size(width = if (i == step) 24.dp else 8.dp, height = 8.dp),
                ) {}
            }
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                if (targetState > initialState) (slideInHorizontally { it / 2 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
                else (slideInHorizontally { -it / 2 } + fadeIn()) togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
            },
            modifier = Modifier.weight(1f),
            label = "onboarding",
        ) { s ->
            when (s) {
                0 -> Welcome { step = 1 }
                1 -> Permissions(onNext = { step = 2 })
                2 -> ModelsStep(onNext = { step = 3 })
                else -> SurfacesStep(onDone = { scope.launch { graph.settings.update { it.copy(onboarded = true) } } })
            }
        }
    }
}

@Composable
private fun StepLayout(
    title: String,
    body: String,
    hero: @Composable () -> Unit,
    primary: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean = true,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    content: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            hero()
            Spacer(Modifier.height(24.dp))
            Text(title, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            content()
        }
        Button(onClick = onPrimary, enabled = primaryEnabled, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(primary, style = MaterialTheme.typography.titleMedium)
        }
        if (secondary != null) {
            TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) { Text(secondary) }
        } else Spacer(Modifier.height(48.dp))
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    StepLayout(
        title = "Talk. It types.",
        body = "Speak naturally in any app and get clean, polished text. Everything — speech recognition and AI — runs on your phone.",
        hero = {
            Box(Modifier.fillMaxWidth().height(220.dp)) {
                BubblePreview(
                    BubbleSettings(sizeDp = 110f, idleOpacity = 1f, look = BubbleLook.LIQUID, listenAnim = ListenAnim.BLOB),
                    Modifier.fillMaxSize(), side = 0,
                )
            }
        },
        primary = "Get started",
        onPrimary = onNext,
    ) {
        Pill("No account · no cloud · no tracking", Icons.Rounded.Lock)
    }
}

@Composable
private fun Permissions(onNext: () -> Unit) {
    val system = rememberSystemStatus()
    var asked by rememberSaveable { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked++ }
    val context = LocalContext.current
    StepLayout(
        title = "Let it hear you",
        body = "Voiability only listens while the mic is lit up, and audio is processed and discarded on-device.",
        hero = { HeroIcon(Icons.Rounded.Mic) },
        primary = if (system.mic) "Continue" else "Allow microphone",
        onPrimary = {
            if (system.mic) onNext()
            else if (asked >= 2) SystemIntents.appDetails(context)
            else launcher.launch(
                if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                else arrayOf(Manifest.permission.RECORD_AUDIO)
            )
        },
        secondary = if (system.mic) null else "Skip for now",
        onSecondary = onNext,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModelsStep(onNext: () -> Unit) {
    val graph = VoiApp.graph
    val system = rememberSystemStatus()
    val states by graph.models.states.collectAsState()
    val scope = rememberCoroutineScope()
    val (speech, polish) = remember(system.totalRamGb) { ModelCatalog.recommend(system.totalRamGb) }
    val picks = listOfNotNull(speech, polish)
    val ready = picks.all { states[it.id] == ModelState.Ready }
    val downloading = picks.any { states[it.id] is ModelState.Downloading }

    fun choose() = scope.launch {
        graph.settings.update { it.copy(sttModelId = speech.id, llmModelId = polish?.id.orEmpty(), polish = polish != null) }
    }

    StepLayout(
        title = "Download the brains",
        body = "Picked for your phone (%.0f GB RAM). One-time download, then it works in airplane mode.".format(system.totalRamGb),
        hero = { HeroIcon(Icons.Rounded.Lock) },
        primary = when {
            ready -> "Continue"
            downloading -> "Continue while it downloads"
            else -> "Download ${formatBytes(picks.sumOf { it.sizeBytes })}"
        },
        onPrimary = {
            choose()
            if (ready || downloading) onNext()
            else picks.forEach { graph.models.download(it) }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            picks.forEach { ModelProgressRow(it, states[it.id] ?: ModelState.Missing) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModelProgressRow(model: ModelInfo, state: ModelState) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.titleMedium)
                    Text("${model.tagline} · ${model.sizeLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state == ModelState.Ready) Icon(Icons.Rounded.CheckCircle, "Ready", tint = MaterialTheme.colorScheme.primary)
            }
            if (state is ModelState.Downloading) {
                Spacer(Modifier.height(10.dp))
                LinearWavyProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            }
            if (state is ModelState.Failed) {
                Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SurfacesStep(onDone: () -> Unit) {
    val system = rememberSystemStatus()
    val context = LocalContext.current
    StepLayout(
        title = "Use it everywhere",
        body = "Pick one or both. You can change this any time.",
        hero = { HeroIcon(Icons.Rounded.BubbleChart) },
        primary = "Start dictating",
        onPrimary = onDone,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SurfaceOption(
                Icons.Rounded.BubbleChart, "Floating bubble",
                "Appears next to your usual keyboard. Needs Accessibility so it can type the text for you — it reads nothing else.",
                done = system.bubbleService, action = "Turn on",
            ) { SystemIntents.accessibility(context) }
            SurfaceOption(
                Icons.Rounded.Keyboard, "Voiability keyboard",
                "A mic-first keyboard with style chips. Switch to it whenever you want to talk.",
                done = system.keyboardEnabled, action = "Enable",
            ) { SystemIntents.keyboards(context) }
        }
    }
}

@Composable
private fun SurfaceOption(icon: ImageVector, title: String, body: String, done: Boolean, action: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = if (done) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            if (done) Icon(Icons.Rounded.CheckCircle, "On", tint = MaterialTheme.colorScheme.primary)
            else FilledTonalButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun HeroIcon(icon: ImageVector) {
    Surface(shape = RoundedCornerShape(40.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(128.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
