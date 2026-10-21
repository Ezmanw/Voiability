package com.voiability

import android.app.Application
import android.content.ComponentCallbacks2
import com.voiability.data.HistoryStore
import com.voiability.data.SettingsRepository
import com.voiability.dictation.DictationController
import com.voiability.dictation.EngineHost
import com.voiability.models.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide singletons. The app, keyboard and bubble all share one loaded engine. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsRepository(app, scope)
    val models = ModelManager(app, scope)
    val history = HistoryStore(app, scope)
    val engines = EngineHost(models, settings, scope)
    val dictation = DictationController(app, settings, engines, history, scope)
}

class VoiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Give back the big polish model first when the system is under pressure.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            graph.engines.releasePolisher()
        }
    }

    companion object {
        lateinit var graph: AppGraph
            private set
    }
}
