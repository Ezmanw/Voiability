package com.voiability

import com.voiability.data.Style
import com.voiability.data.Styles
import com.voiability.dictation.Polisher
import com.voiability.dictation.TextCleaner
import com.voiability.engine.LlamaModel
import com.voiability.engine.WhisperModel
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * End-to-end check of the native engine and prompts on a desktop build of libvoiengine.
 * Runs only when VOI_MODELS points at a folder with base.en.bin, vad.bin and qwen15.gguf.
 */
class HostPipelineTest {
    private val dir = System.getenv("VOI_MODELS")?.let(::File)

    private fun wav(file: File): FloatArray {
        val bytes = file.readBytes()
        var i = 12
        while (i < bytes.size - 8) {
            val id = String(bytes, i, 4)
            val size = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val buf = ByteBuffer.wrap(bytes, i + 8, size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                return FloatArray(buf.remaining()) { buf.get(it) / 32768f }
            }
            i += 8 + size
        }
        error("no data chunk")
    }

    @Test fun whisper() {
        assumeTrue(dir != null)
        val model = WhisperModel.load(File(dir, "base.en.bin"))!!
        val audio = wav(File(System.getenv("VOI_WAV")!!))
        repeat(2) {
            val t0 = System.currentTimeMillis()
            val text = model.transcribe(audio, vad = File(dir, "vad.bin"), threads = 4)
            println("WHISPER (${System.currentTimeMillis() - t0} ms): $text")
            println("CLEANED: " + TextCleaner.clean(text, true, true, emptyList()))
        }
        // Silence must not produce text.
        val silent = model.transcribe(FloatArray(16000 * 3), vad = File(dir, "vad.bin"), threads = 4)
        println("SILENCE: '$silent'")
        model.close()
    }

    @Test fun polish() {
        assumeTrue(dir != null)
        val llm = LlamaModel.load(File(dir, "qwen15.gguf"))!!
        val custom = Style("c", "Chill", "🧊", "make it really casual no capitals, like texting a friend", lowercase = true, dropFinalPeriod = true)
        val inputs = listOf(
            "Um, so I was thinking, uh, we could grab dinner on Friday. No wait, Saturday. And maybe invite Sam too.",
            "What's the weather going to be like tomorrow in London?",
            "Hey, can you send me the report by end of day? Thanks.",
            "okay so for the launch we need to finalize the pricing page then uh email the beta users and also fix the login bug",
        )
        val styles = Styles.builtIn.filter { it.useLlm } + custom
        for (style in styles) {
            for (input in inputs.take(if (style.id == Styles.NOTES) 4 else 3)) {
                val cleaned = TextCleaner.clean(input, true, true, emptyList())
                val t0 = System.currentTimeMillis()
                val out = llm.chat(Polisher.messages(cleaned, style, "WhatsApp", emptyList()), Polisher.maxTokens(cleaned))
                val ms = System.currentTimeMillis() - t0
                val unwrapped = TextCleaner.unwrapModelOutput(out ?: "")
                val final = TextCleaner.applyStyle(if (Polisher.looksSane(cleaned, unwrapped)) unwrapped else cleaned, style)
                println("STYLE ${style.name} (${ms} ms) :: $input\n=> " + final.replace("\n", " / "))
            }
        }
        llm.close()
    }
}
