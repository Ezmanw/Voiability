package com.voiability.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.voiability.VoiApp
import com.voiability.ui.screens.BubbleScreen
import com.voiability.ui.screens.DictionaryScreen
import com.voiability.ui.screens.HistoryScreen
import com.voiability.ui.screens.HomeScreen
import com.voiability.ui.screens.ModelsScreen
import com.voiability.ui.screens.OnboardingScreen
import com.voiability.ui.screens.SettingsScreen
import com.voiability.ui.screens.StylesScreen
import com.voiability.ui.theme.VoiTheme

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    STYLES("Styles", Icons.Rounded.AutoAwesome),
    MODELS("Models", Icons.Rounded.Memory),
    SETTINGS("Settings", Icons.Rounded.Tune),
}

enum class Page { BUBBLE, DICTIONARY, HISTORY }

/** Simple navigation: four tabs plus a back stack of detail pages. */
class Nav {
    var tab by mutableStateOf(Tab.HOME)
    val stack = mutableStateListOf<Page>()
    fun open(page: Page) { stack.add(page) }
    fun back() { stack.removeLastOrNull() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        VoiApp.graph.engines.warmUp()
        setContent {
            VoiTheme {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                    Root()
                }
            }
        }
    }
}

@Composable
private fun Root() {
    val settings by VoiApp.graph.settings.settings.collectAsState()
    AnimatedContent(
        targetState = settings.onboarded,
        transitionSpec = { (fadeIn(tween(500)) + scaleIn(initialScale = 0.96f)) togetherWith fadeOut(tween(250)) },
        label = "root",
    ) { onboarded ->
        if (onboarded) MainShell() else OnboardingScreen()
    }
}

@Composable
private fun MainShell() {
    val nav = remember { Nav() }
    var savedTab by rememberSaveable { mutableStateOf(Tab.HOME) }
    nav.tab = savedTab
    BackHandler(enabled = nav.stack.isNotEmpty() || savedTab != Tab.HOME) {
        if (nav.stack.isNotEmpty()) nav.back() else savedTab = Tab.HOME
    }
    val page = nav.stack.lastOrNull()
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState != null && (initialState == null || nav.stack.size > 1)
            if (forward) {
                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 6 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 6 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
            }
        },
        label = "pages",
    ) { current ->
        when (current) {
            Page.BUBBLE -> BubbleScreen(onBack = nav::back)
            Page.DICTIONARY -> DictionaryScreen(onBack = nav::back)
            Page.HISTORY -> HistoryScreen(onBack = nav::back)
            null -> Scaffold(
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = savedTab == tab,
                                onClick = { savedTab = tab },
                                icon = { Icon(tab.icon, null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                AnimatedContent(
                    targetState = savedTab,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                    label = "tabs",
                ) { tab ->
                    when (tab) {
                        Tab.HOME -> HomeScreen(padding, onOpen = nav::open, onTab = { savedTab = it })
                        Tab.STYLES -> StylesScreen(padding)
                        Tab.MODELS -> ModelsScreen(padding)
                        Tab.SETTINGS -> SettingsScreen(padding, onOpen = nav::open)
                    }
                }
            }
        }
    }
}
