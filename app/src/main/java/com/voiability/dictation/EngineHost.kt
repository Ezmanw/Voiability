package com.voiability.dictation

import android.util.Log
import com.voiability.data.SettingsRepository
import com.voiability.engine.CpuInfo
import com.voiability.engine.LlamaModel
import com.voiability.engine.WhisperModel
import com.voiability.models.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

data class EngineStatus(
    val speechReady: Boolean = false,
    val polishReady: Boolean = false,
    val loadingSpeech: Boolean = false,
    val loadingPolish: Boolean = false,
    val error: String? = null,
)

/**
 * Owns the native models. Each runs on its own single thread, which both serializes access
 * (the native handles are not thread safe) and lets speech and polish overlap.
 */
class EngineHost(
    private val models: ModelManager,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    val speechThread = Executors.newSingleThreadExecutor { Thread(it, "voi-stt").apply { priority = Thread.MAX_PRIORITY } }
        .asCoroutineDispatcher()
    val polishThread = Executors.newSingleThreadExecutor { Thread(it, "voi-llm") }.asCoroutineDispatcher()

    private var whisper: WhisperModel? = null
    private var llama: LlamaModel? = null

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    init {
        // Swap models as soon as the user picks different ones.
        scope.launch {
            settings.settings.map { it.sttModelId to it.llmModelId }.distinctUntilChanged().collect { (stt, llm) ->
                withContext(speechThread) {
                    if (whisper != null && whisper?.path != models.fileFor(stt)?.absolutePath) {
                        whisper?.close(); whisper = null
                        _status.update { it.copy(speechReady = false) }
                    }
                }
                withContext(polishThread) {
                    if (llama != null && llama?.path != models.fileFor(llm)?.absolutePath) {
                        llama?.close(); llama = null
                        _status.update { it.copy(polishReady = false) }
                    }
                }
            }
        }
    }

    /** Loads (or returns the already loaded) speech model. Must be called on [speechThread]. */
    fun speechOnThread(): WhisperModel? {
        whisper?.let { return it }
        if (!CpuInfo.isSupported) {
            _status.update { it.copy(error = "This phone's CPU is too old for Voiability's engine.") }
            return null
        }
        val file = models.fileFor(settings.current.sttModelId) ?: return null
        _status.update { it.copy(loadingSpeech = true) }
        val t0 = System.currentTimeMillis()
        whisper = WhisperModel.load(file)
        Log.i(TAG, "speech model loaded in ${System.currentTimeMillis() - t0} ms")
        _status.update { it.copy(loadingSpeech = false, speechReady = whisper != null, error = if (whisper == null) "Couldn't load the speech model" else null) }
        return whisper
    }

    /** Loads (or returns) the polish model. Must be called on [polishThread]. */
    fun polisherOnThread(): LlamaModel? {
        llama?.let { return it }
        if (!CpuInfo.isSupported) return null
        val file = models.fileFor(settings.current.llmModelId) ?: return null
        _status.update { it.copy(loadingPolish = true) }
        val t0 = System.currentTimeMillis()
        llama = LlamaModel.load(file)
        Log.i(TAG, "polish model loaded in ${System.currentTimeMillis() - t0} ms")
        _status.update { it.copy(loadingPolish = false, polishReady = llama != null) }
        // Pre-fill the prompt cache with the default style's system prompt.
        llama?.let { model ->
            val s = settings.current
            runCatching { model.chat(Polisher.messages("hi", s.style(s.defaultStyleId), null, s.dictionary), maxTokens = 1) }
        }
        return llama
    }

    /** Loads everything in the background so the first dictation feels instant. */
    fun warmUp() {
        scope.launch(speechThread) { speechOnThread() }
        if (settings.current.polish && settings.current.llmModelId.isNotEmpty()) {
            scope.launch(polishThread) { polisherOnThread() }
        }
    }

    val vadFile get() = models.vadFile

    fun abortSpeech() = whisper?.abort()
    fun abortPolish() = llama?.abort()

    fun releasePolisher() {
        llama?.abort()
        scope.launch(polishThread) {
            llama?.close(); llama = null
            _status.update { it.copy(polishReady = false) }
        }
    }

    val isPolisherLoaded: Boolean get() = llama != null

    private companion object {
        const val TAG = "EngineHost"
    }
}
