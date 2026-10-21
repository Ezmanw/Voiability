package com.voiability.dictation

import com.voiability.data.DictionaryEntry
import com.voiability.data.Style

/** Fast, deterministic cleanup that runs on every transcript, with or without the LLM. */
object TextCleaner {

    private val artifacts = Regex(
        """\[(?:BLANK_AUDIO|MUSIC|Music|music|NOISE|noise|SOUND|silence|inaudible)[^\]]*]|\((?:[^)]*(?:music|applause|laughs?|laughter|sighs?|silence|inaudible|noise|coughs?|clears throat)[^)]*)\)|\*[^*]*(?:music|laughs|sighs)[^*]*\*""",
        RegexOption.IGNORE_CASE,
    )

    /** Things Whisper likes to "hear" in silence or noise. Dropped only when they are the whole output. */
    private val hallucinations = setOf(
        "thank you.", "thanks for watching!", "thank you for watching.", "thanks for watching.",
        "you", "bye.", "bye!", "subtitles by the amara.org community", ".", "okay.",
    )

    private val fillers = Regex("""(?:,\s*)?(?<![\w'])(?:u+m+|u+h+m*|e+r+m+|e+r+|a+h+|h+m+|m+h*m+)(?![\w']),?""", RegexOption.IGNORE_CASE)

    private val commands = listOf(
        Regex(""",?\s*\bnew paragraph\b[,.]?\s*""", RegexOption.IGNORE_CASE) to "\n\n",
        Regex(""",?\s*\bnew ?line\b[,.]?\s*""", RegexOption.IGNORE_CASE) to "\n",
        Regex("""\s*\bquestion mark\b[,.]?""", RegexOption.IGNORE_CASE) to "?",
        Regex("""\s*\bexclamation (?:mark|point)\b[,.]?""", RegexOption.IGNORE_CASE) to "!",
        Regex("""\s*\bfull stop\b[,.]?""", RegexOption.IGNORE_CASE) to ".",
        Regex("""\s*\bsemicolon\b[,.]?""", RegexOption.IGNORE_CASE) to ";",
    )

    private val scratchThat = Regex("""[^.!?\n]*[.!?]?\s*\b(?:scratch that|delete that)\b[,.!]?\s*""", RegexOption.IGNORE_CASE)

    fun isHallucination(text: String): Boolean = text.trim().lowercase() in hallucinations

    fun clean(
        raw: String,
        removeFillers: Boolean,
        spokenCommands: Boolean,
        dictionary: List<DictionaryEntry>,
    ): String {
        var t = raw.replace(artifacts, " ")
        if (removeFillers) t = t.replace(fillers, "")
        t = t.replace(scratchThat, " ")
        if (spokenCommands) commands.forEach { (re, rep) -> t = t.replace(re, rep) }
        dictionary.forEach { entry ->
            entry.soundsLike.filter { it.isNotBlank() }.forEach { alias ->
                t = t.replace(Regex("""(?<![\w])${Regex.escape(alias.trim())}(?![\w])""", RegexOption.IGNORE_CASE), entry.word)
            }
            // Fix the casing of words Whisper already got right.
            t = t.replace(Regex("""(?<![\w])${Regex.escape(entry.word)}(?![\w])""", RegexOption.IGNORE_CASE), entry.word)
        }
        return tidy(t)
    }

    /** Normalizes whitespace and punctuation spacing, capitalizes sentence starts. */
    fun tidy(text: String): String {
        var t = text
            .replace(Regex("""[ \t]+"""), " ")
            .replace(Regex(""" *\n *"""), "\n")
            .replace(Regex("""\s+([,.!?;:])"""), "$1")
            .replace(Regex("""([,;:])(?=[^\s\d])"""), "$1 ")
            .replace(Regex(""",\s*([.!?])"""), "$1")
            .replace(Regex("""^[\s,.;:]+"""), "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        // Capitalize the first letter and letters after sentence ends.
        t = t.replace(Regex("""(^|[.!?]\s+|\n)(\p{Ll})""")) { it.groupValues[1] + it.groupValues[2].uppercase() }
        return t
    }

    /** Applies a style's hard rules, which win over anything the LLM produced. */
    fun applyStyle(text: String, style: Style): String {
        var t = text.trim()
        if (style.lowercase) t = t.lowercase()
        if (style.dropFinalPeriod) t = t.trimEnd().removeSuffix(".").trimEnd()
        return t
    }

    /** Strips wrapper noise small models sometimes add around their answer. */
    fun unwrapModelOutput(text: String): String {
        var t = text.trim()
            .removePrefix("<transcript>").removeSuffix("</transcript>")
            .replace(Regex("""^(?:here(?:'s| is) (?:the )?(?:polished|cleaned|rewritten|final)[^:\n]*:\s*)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:polished|output|text|result)\s*:\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '“' && t.last() == '”'))) {
            t = t.substring(1, t.length - 1).trim()
        }
        return t
    }
}
