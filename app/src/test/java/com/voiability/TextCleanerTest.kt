package com.voiability

import com.voiability.data.DictionaryEntry
import com.voiability.data.Styles
import com.voiability.dictation.Polisher
import com.voiability.dictation.TextCleaner
import com.voiability.overlay.VoiAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCleanerTest {
    private fun clean(s: String, dict: List<DictionaryEntry> = emptyList()) = TextCleaner.clean(s, true, true, dict)

    @Test fun removesFillers() {
        assertEquals("So I think we should go.", clean("Um, so I think, uh, we should go."))
        assertEquals("Interesting, umbrella and humming stay.", clean("hmm interesting, umbrella and humming stay."))
    }

    @Test fun spokenCommands() {
        assertEquals("Hello\nWorld", clean("Hello new line world"))
        assertEquals("First.\n\nSecond", clean("First. New paragraph. second"))
        assertEquals("Are you coming?", clean("Are you coming question mark"))
    }

    @Test fun scratchThat() {
        assertEquals("Hello there. Meet at six.", clean("Hello there. Meet at five. Scratch that. Meet at six."))
    }

    @Test fun dropsWhisperArtifacts() {
        assertEquals("Hi", clean("[BLANK_AUDIO] Hi (upbeat music)"))
        assertTrue(TextCleaner.isHallucination(" Thank you. "))
    }

    @Test fun dictionary() {
        val dict = listOf(DictionaryEntry("Voiability", listOf("voy ability")))
        assertEquals("I love Voiability.", clean("I love voy ability.", dict))
        assertEquals("Voiability rocks", clean("voiability rocks", dict))
    }

    @Test fun styleRules() {
        val casual = Styles.builtIn.first { it.id == Styles.CASUAL }
        assertEquals("i'm on my way", TextCleaner.applyStyle("I'm on my way.", casual))
    }

    @Test fun unwrapsModelChatter() {
        assertEquals("Hi there.", TextCleaner.unwrapModelOutput("Here's the polished text: \"Hi there.\""))
        assertEquals("Hi", TextCleaner.unwrapModelOutput("<transcript>Hi</transcript>"))
    }

    @Test fun sanity() {
        assertFalse(Polisher.looksSane("what's the weather like tomorrow in london", "I'm sorry, but I cannot check the weather."))
        assertTrue(Polisher.looksSane("what's the weather like tomorrow in london", "What's the weather like tomorrow in London?"))
    }

    @Test fun smartSpacing() {
        assertEquals(" hello", VoiAccessibilityService.smartSpacing("abc", "hello", ""))
        assertEquals("hello ", VoiAccessibilityService.smartSpacing("", "hello", "world"))
        assertEquals(".", VoiAccessibilityService.smartSpacing("abc", ".", ""))
        assertEquals("hello", VoiAccessibilityService.smartSpacing("abc ", "hello", " x"))
    }
}
