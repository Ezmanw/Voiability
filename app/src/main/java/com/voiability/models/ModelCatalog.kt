package com.voiability.models

enum class ModelKind { SPEECH, POLISH, VAD }

data class ModelInfo(
    val id: String,
    val kind: ModelKind,
    val name: String,
    val tagline: String,
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    /** Rough RAM needed while loaded, used to steer recommendations. */
    val ramMb: Int,
    val englishOnly: Boolean = false,
    /** 1 (fastest) .. 5 (slowest) and 1 (basic) .. 5 (best), shown as little meters in the UI. */
    val speed: Int,
    val quality: Int,
    val imported: Boolean = false,
) {
    val sizeLabel: String
        get() = if (sizeBytes >= 1_000_000_000) "%.1f GB".format(sizeBytes / 1e9) else "${sizeBytes / 1_000_000} MB"
}

object ModelCatalog {
    private const val WHISPER = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    val vad = ModelInfo(
        id = "silero-vad", kind = ModelKind.VAD, name = "Silero VAD", tagline = "Detects speech, skips silence",
        url = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin",
        fileName = "ggml-silero-v6.2.0.bin", sizeBytes = 885_098, ramMb = 10, speed = 1, quality = 5,
    )

    val speech = listOf(
        ModelInfo(
            "whisper-tiny-en", ModelKind.SPEECH, "Whisper Tiny", "Instant, good for short messages",
            WHISPER + "ggml-tiny.en-q5_1.bin", "ggml-tiny.en-q5_1.bin", 32_166_155, 150,
            englishOnly = true, speed = 1, quality = 2,
        ),
        ModelInfo(
            "whisper-base-en", ModelKind.SPEECH, "Whisper Base", "Fast and accurate for everyday use",
            WHISPER + "ggml-base.en-q5_1.bin", "ggml-base.en-q5_1.bin", 59_721_011, 250,
            englishOnly = true, speed = 2, quality = 3,
        ),
        ModelInfo(
            "whisper-small-en", ModelKind.SPEECH, "Whisper Small", "Sharp accuracy, still snappy on flagships",
            WHISPER + "ggml-small.en-q5_1.bin", "ggml-small.en-q5_1.bin", 190_098_681, 600,
            englishOnly = true, speed = 3, quality = 4,
        ),
        ModelInfo(
            "whisper-small", ModelKind.SPEECH, "Whisper Small · Multilingual", "99 languages",
            WHISPER + "ggml-small-q5_1.bin", "ggml-small-q5_1.bin", 190_085_487, 600,
            speed = 3, quality = 4,
        ),
        ModelInfo(
            "whisper-medium-en", ModelKind.SPEECH, "Whisper Medium", "Very accurate English, heavier",
            WHISPER + "ggml-medium.en-q5_0.bin", "ggml-medium.en-q5_0.bin", 539_225_533, 1400,
            englishOnly = true, speed = 5, quality = 5,
        ),
        ModelInfo(
            "whisper-medium", ModelKind.SPEECH, "Whisper Medium · Multilingual", "99 languages, heavier",
            WHISPER + "ggml-medium-q5_0.bin", "ggml-medium-q5_0.bin", 539_212_467, 1400,
            speed = 5, quality = 4,
        ),
        ModelInfo(
            "whisper-turbo", ModelKind.SPEECH, "Whisper Large v3 Turbo", "Best accuracy, any language",
            WHISPER + "ggml-large-v3-turbo-q5_0.bin", "ggml-large-v3-turbo-q5_0.bin", 574_041_195, 1500,
            speed = 4, quality = 5,
        ),
    )

    val polish = listOf(
        ModelInfo(
            "gemma3-1b", ModelKind.POLISH, "Gemma 3 1B", "Lightning fast cleanup",
            "https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
            "gemma-3-1b-it-Q4_K_M.gguf", 806_058_240, 1200, speed = 1, quality = 2,
        ),
        ModelInfo(
            "qwen25-1.5b", ModelKind.POLISH, "Qwen 2.5 1.5B", "Fast, follows styles well",
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "qwen2.5-1.5b-instruct-q4_k_m.gguf", 1_117_320_736, 1600, speed = 2, quality = 3,
        ),
        ModelInfo(
            "llama32-3b", ModelKind.POLISH, "Llama 3.2 3B", "Natural phrasing",
            "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            "Llama-3.2-3B-Instruct-Q4_K_M.gguf", 2_019_377_696, 2800, speed = 3, quality = 4,
        ),
        ModelInfo(
            "qwen3-4b", ModelKind.POLISH, "Qwen3 4B Instruct", "Best rewrites, for 12 GB+ phones",
            "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
            "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", 2_497_281_120, 3300, speed = 4, quality = 5,
        ),
    )

    val all = speech + polish + vad

    fun byId(id: String): ModelInfo? = all.firstOrNull { it.id == id }

    /** Picks a speech + polish pair that fits the phone's memory. */
    fun recommend(totalRamGb: Double): Pair<ModelInfo, ModelInfo?> = when {
        // The 4B polisher is nicer but roughly 3x slower; snappy wins by default.
        totalRamGb >= 7.5 -> speech.first { it.id == "whisper-small-en" } to polish.first { it.id == "qwen25-1.5b" }
        totalRamGb >= 5.5 -> speech.first { it.id == "whisper-base-en" } to polish.first { it.id == "gemma3-1b" }
        else -> speech.first { it.id == "whisper-base-en" } to null
    }
}
