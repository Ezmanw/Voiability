package com.voiability.engine

/** Receives raw UTF-8 bytes as the model generates them. Return false to stop generation. */
fun interface TokenSink {
    fun onBytes(bytes: ByteArray): Boolean
}

internal object NativeLoader {
    init {
        System.loadLibrary("voiengine")
    }

    fun ensure() = Unit
}

internal object WhisperNative {
    init { NativeLoader.ensure() }

    external fun init(modelPath: String): Long
    external fun free(handle: Long)
    external fun abort(handle: Long)
    external fun isMultilingual(handle: Long): Boolean
    external fun transcribe(
        handle: Long,
        samples: FloatArray,
        nSamples: Int,
        language: String,
        prompt: String,
        vadModel: String?,
        threads: Int,
        fast: Boolean,
    ): ByteArray?
}

internal object LlamaNative {
    init { NativeLoader.ensure() }

    external fun init(modelPath: String, nCtx: Int, threads: Int, threadsBatch: Int): Long
    external fun free(handle: Long)
    external fun abort(handle: Long)
    external fun generate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        sink: TokenSink,
    ): Int
}
