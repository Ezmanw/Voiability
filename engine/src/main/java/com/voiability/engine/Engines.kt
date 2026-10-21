package com.voiability.engine

import java.io.ByteArrayOutputStream
import java.io.File

/** Picks thread counts that keep the big cores busy without starving the UI thread. */
object CpuInfo {
    val cores: Int = Runtime.getRuntime().availableProcessors()

    /** Decode-heavy work is memory bound; a couple of cores less is just as fast and cooler. */
    val genThreads: Int = (cores - 2).coerceIn(2, 6)
    val batchThreads: Int = cores.coerceIn(2, 8)

    /** The engine is built for ARMv8.2+ with int8 matmul (Snapdragon 8 Gen 1 and newer). */
    val isSupported: Boolean by lazy {
        runCatching {
            val features = File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }.orEmpty()
            "asimddp" in features && "i8mm" in features
        }.getOrDefault(true)
    }
}

/** Speech-to-text. Not thread safe: callers serialize access. */
class WhisperModel private constructor(private var handle: Long, val path: String) : AutoCloseable {

    val isMultilingual: Boolean get() = handle != 0L && WhisperNative.isMultilingual(handle)

    fun transcribe(
        samples: FloatArray,
        count: Int = samples.size,
        language: String = "en",
        prompt: String = "",
        /** Silero VAD model; when given, silence is skipped (faster, and no "thank you" hallucinations). */
        vad: File? = null,
        fast: Boolean = false,
        threads: Int = CpuInfo.batchThreads,
    ): String {
        check(handle != 0L) { "model closed" }
        val bytes = WhisperNative.transcribe(handle, samples, count, language, prompt, vad?.takeIf { it.exists() }?.absolutePath, threads, fast)
        return bytes?.toString(Charsets.UTF_8).orEmpty()
    }

    /** Stops an in-flight [transcribe] as soon as possible. Safe to call from any thread. */
    fun abort() {
        if (handle != 0L) WhisperNative.abort(handle)
    }

    override fun close() {
        if (handle != 0L) WhisperNative.free(handle)
        handle = 0L
    }

    companion object {
        fun load(model: File): WhisperModel? {
            val h = WhisperNative.init(model.absolutePath)
            return if (h == 0L) null else WhisperModel(h, model.absolutePath)
        }
    }
}

data class ChatMessage(val role: String, val content: String)

/** Small instruct LLM used to polish transcripts. Not thread safe: callers serialize access. */
class LlamaModel private constructor(private var handle: Long, val path: String) : AutoCloseable {

    /** Generates a reply, calling [onText] with the full text so far after every token. */
    fun chat(messages: List<ChatMessage>, maxTokens: Int, onText: (String) -> Boolean = { true }): String? {
        check(handle != 0L) { "model closed" }
        val buffer = ByteArrayOutputStream()
        var text = ""
        val n = LlamaNative.generate(
            handle,
            messages.map { it.role }.toTypedArray(),
            messages.map { it.content }.toTypedArray(),
            maxTokens,
        ) { bytes ->
            buffer.write(bytes)
            text = buffer.toByteArray().decodeCompleteUtf8()
            onText(text)
        }
        return if (n < 0) null else buffer.toByteArray().toString(Charsets.UTF_8)
    }

    fun abort() {
        if (handle != 0L) LlamaNative.abort(handle)
    }

    override fun close() {
        if (handle != 0L) LlamaNative.free(handle)
        handle = 0L
    }

    companion object {
        fun load(model: File, contextSize: Int = 4096): LlamaModel? {
            val h = LlamaNative.init(model.absolutePath, contextSize, CpuInfo.genThreads, CpuInfo.batchThreads)
            return if (h == 0L) null else LlamaModel(h, model.absolutePath)
        }
    }
}

/** Decodes UTF-8, leaving off a trailing incomplete multi-byte sequence. */
internal fun ByteArray.decodeCompleteUtf8(): String {
    var end = size
    var i = size - 1
    var back = 0
    while (i >= 0 && back < 4) {
        val b = this[i].toInt() and 0xFF
        if (b and 0xC0 != 0x80) {
            val need = when {
                b and 0x80 == 0 -> 1
                b and 0xE0 == 0xC0 -> 2
                b and 0xF0 == 0xE0 -> 3
                else -> 4
            }
            if (size - i < need) end = i
            break
        }
        i--; back++
    }
    return String(this, 0, end, Charsets.UTF_8)
}
