package com.voiability.dictation

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.voiability.audio.Recorder
import com.voiability.data.HistoryItem
import com.voiability.data.HistoryStore
import com.voiability.data.SettingsRepository
import com.voiability.data.Style
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where finished text goes: the keyboard, the bubble's target field, or an in-app text box. */
interface TextSink {
    /** Package of the app being typed into, used to pick a style. */
    val packageName: String?
    val appLabel: String?
    fun insert(text: String)
}

sealed interface DictationState {
    data object Idle : DictationState
    data class Listening(val partial: String = "") : DictationState
    data class Transcribing(val partial: String = "") : DictationState
    data class Polishing(val text: String, val style: Style) : DictationState
    data class Done(val text: String) : DictationState
    data class Error(val message: String) : DictationState

    val isBusy: Boolean get() = this is Listening || this is Transcribing || this is Polishing
}

/**
 * The whole pipeline: mic -> Whisper (with live previews) -> rule cleanup -> LLM polish ->
 * style rules -> sink. One instance is shared by the keyboard, the bubble and the app.
 */
class DictationController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val engines: EngineHost,
    private val history: HistoryStore,
    private val scope: CoroutineScope,
) {
    private val recorder = Recorder()
    val level: StateFlow<Float> = recorder.level

    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private var sink: TextSink? = null
    private var styleOverride: Style? = null
    private var previewJob: Job? = null
    private var pipelineJob: Job? = null
    private var resetJob: Job? = null

    /** The last inserted text, so the keyboard can offer undo. */
    var lastInserted: String? = null
        private set

    val hasMicPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun toggle(target: TextSink, style: Style? = null) {
        if (_state.value is DictationState.Listening) stop() else start(target, style)
    }

    fun start(target: TextSink, style: Style? = null) {
        if (_state.value.isBusy) return
        val s = settings.current
        if (!hasMicPermission) {
            fail("Microphone permission needed. Open Voiability to allow it.")
            return
        }
        if (s.sttModelId.isEmpty() || engines.status.value.error != null && !engines.status.value.speechReady) {
            fail(engines.status.value.error ?: "Pick a speech model in Voiability first.")
            return
        }
        resetJob?.cancel()
        sink = target
        styleOverride = style
        if (!recorder.start()) {
            fail("Couldn't open the microphone.")
            return
        }
        _state.value = DictationState.Listening()
        engines.warmUp()
        if (s.livePreview) startPreviews()
        if (s.autoStopSeconds > 0f) watchSilence(s.autoStopSeconds)
    }

    /** Finish listening and produce text. */
    fun stop() {
        if (_state.value !is DictationState.Listening) return
        previewJob?.cancel()
        engines.abortSpeech()
        val audio = recorder.stop()
        val partial = (_state.value as? DictationState.Listening)?.partial.orEmpty()
        _state.value = DictationState.Transcribing(partial)
        pipelineJob = scope.launch { runPipeline(audio) }
    }

    /** Throw away everything. */
    fun cancel() {
        previewJob?.cancel()
        pipelineJob?.cancel()
        engines.abortSpeech()
        engines.abortPolish()
        recorder.stop()
        _state.value = DictationState.Idle
    }

    private fun startPreviews() {
        previewJob = scope.launch {
            delay(700)
            while (isActive && _state.value is DictationState.Listening) {
                val t0 = SystemClock.elapsedRealtime()
                val audio = recorder.snapshot()
                if (recorder.heardSpeech && audio.size > Recorder.SAMPLE_RATE / 2) {
                    val text = withContext(engines.speechThread) {
                        val model = engines.speechOnThread() ?: return@withContext null
                        // Only the last ~25 s, so previews stay quick on long dictations.
                        val from = (audio.size - Recorder.SAMPLE_RATE * 25).coerceAtLeast(0)
                        runCatching {
                            model.transcribe(
                                audio.copyOfRange(from, audio.size), language = language(model.isMultilingual),
                                fast = true,
                            )
                        }.getOrNull()
                    }
                    val st = _state.value
                    if (text != null && st is DictationState.Listening && isActive) {
                        _state.value = st.copy(partial = TextCleaner.tidy(text.replace(Regex("""\[[^\]]*]"""), "")))
                    }
                }
                delay((900 - (SystemClock.elapsedRealtime() - t0)).coerceAtLeast(150))
            }
        }
    }

    private fun watchSilence(seconds: Float) {
        scope.launch {
            while (_state.value is DictationState.Listening) {
                delay(200)
                if (recorder.heardSpeech && recorder.silentForMs > seconds * 1000) {
                    stop()
                    break
                }
            }
        }
    }

    private fun language(multilingual: Boolean): String =
        if (!multilingual) "en" else settings.current.language.ifBlank { "auto" }

    private suspend fun runPipeline(audio: FloatArray) {
        val s = settings.current
        val target = sink
        val audioMs = audio.size * 1000L / Recorder.SAMPLE_RATE
        if (audio.size < Recorder.SAMPLE_RATE / 4 || recorder.peak < 0.0008f) {
            finish(null, if (recorder.peak < 0.00005f && audio.isNotEmpty()) "No audio from the mic. Is another app using it?" else null)
            return
        }

        // 1. Speech to text
        val t0 = SystemClock.elapsedRealtime()
        val raw = withContext(engines.speechThread) {
            val model = engines.speechOnThread() ?: return@withContext null
            val prompt = s.dictionary.joinToString(", ") { it.word }.let { if (it.isEmpty()) "" else "$it." }
            runCatching { model.transcribe(audio, language = language(model.isMultilingual), prompt = prompt, vad = engines.vadFile) }
                .onFailure { Log.e(TAG, "transcribe failed", it) }
                .getOrNull()
        }
        val sttMs = SystemClock.elapsedRealtime() - t0
        if (raw == null) {
            finish(null, engines.status.value.error ?: "Speech model isn't ready yet.")
            return
        }
        if (raw.isBlank() || TextCleaner.isHallucination(raw)) {
            finish(null, null)
            return
        }

        // 2. Rules
        val cleaned = TextCleaner.clean(raw, s.removeFillers, s.spokenCommands, s.dictionary)
        if (cleaned.isBlank()) {
            finish(null, null)
            return
        }
        val style = styleOverride ?: s.styleFor(target?.packageName)

        // 3. LLM polish (streamed into the UI)
        val t1 = SystemClock.elapsedRealtime()
        var text = cleaned
        if (s.polish && style.useLlm && s.llmModelId.isNotEmpty()) {
            _state.value = DictationState.Polishing("", style)
            val polished = withContext(engines.polishThread) {
                val llm = engines.polisherOnThread() ?: return@withContext null
                val msgs = Polisher.messages(cleaned, style, target?.appLabel, s.dictionary)
                runCatching {
                    llm.chat(msgs, Polisher.maxTokens(cleaned)) { partial ->
                        _state.value = DictationState.Polishing(TextCleaner.unwrapModelOutput(partial), style)
                        true
                    }
                }.getOrNull()
            }
            val unwrapped = polished?.let(TextCleaner::unwrapModelOutput)
            if (unwrapped != null && Polisher.looksSane(cleaned, unwrapped)) text = unwrapped
            else Log.w(TAG, "polish rejected: $polished")
        }
        val llmMs = SystemClock.elapsedRealtime() - t1
        text = TextCleaner.applyStyle(text, style)

        Log.i(TAG, "audio=${audioMs}ms stt=${sttMs}ms llm=${llmMs}ms")
        if (s.keepHistory) {
            history.add(
                HistoryItem(
                    id = System.nanoTime(), time = System.currentTimeMillis(), text = text, raw = raw.trim(),
                    styleId = style.id, app = target?.appLabel, audioMs = audioMs, sttMs = sttMs, llmMs = llmMs,
                ),
                s.historyDays,
            )
        }
        finish(text, null)
    }

    /** Runs just the cleanup + polish steps on typed text, for trying out styles. */
    suspend fun previewStyle(sample: String, style: Style): String {
        val s = settings.current
        val cleaned = TextCleaner.clean(sample, s.removeFillers, s.spokenCommands, s.dictionary)
        if (!style.useLlm || s.llmModelId.isEmpty()) return TextCleaner.applyStyle(cleaned, style)
        val out = withContext(engines.polishThread) {
            val llm = engines.polisherOnThread() ?: return@withContext null
            runCatching { llm.chat(Polisher.messages(cleaned, style, null, s.dictionary), Polisher.maxTokens(cleaned)) }.getOrNull()
        }?.let(TextCleaner::unwrapModelOutput)
        return TextCleaner.applyStyle(if (out != null && Polisher.looksSane(cleaned, out)) out else cleaned, style)
    }

    private fun finish(text: String?, error: String?) {
        if (text != null) {
            runCatching { sink?.insert(text) }
            lastInserted = text
            _state.value = DictationState.Done(text)
        } else if (error != null) {
            _state.value = DictationState.Error(error)
        } else {
            _state.value = DictationState.Idle
        }
        resetJob = scope.launch {
            delay(if (error != null) 3000 else 1200)
            if (!_state.value.isBusy) _state.value = DictationState.Idle
        }
    }

    private fun fail(message: String) {
        _state.value = DictationState.Error(message)
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(3000)
            if (_state.value is DictationState.Error) _state.value = DictationState.Idle
        }
    }

    private companion object {
        const val TAG = "Dictation"
    }
}
