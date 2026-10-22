package com.voiability

import com.voiability.data.Settings
import com.voiability.data.Styles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test fun deletedBuiltInsAreHidden() {
        val s = Settings(hiddenStyles = setOf(Styles.HYPE))
        assertFalse(s.allStyles.any { it.id == Styles.HYPE })
        assertEquals(Styles.builtIn.size - 1, s.allStyles.size)
    }

    @Test fun editedBuiltInReplacesOriginalInPlace() {
        val edited = Styles.builtIn.first { it.id == Styles.CASUAL }.copy(instruction = "no caps fr")
        val s = Settings(customStyles = listOf(edited))
        assertEquals("no caps fr", s.style(Styles.CASUAL).instruction)
        assertEquals(Styles.builtIn.size, s.allStyles.size)
        assertEquals(Styles.builtIn.indexOfFirst { it.id == Styles.CASUAL }, s.allStyles.indexOfFirst { it.id == Styles.CASUAL })
    }

    @Test fun perAppToggle() {
        val on = Settings(defaultStyleId = Styles.PRO)
        assertEquals(Styles.CASUAL, on.styleFor("com.whatsapp").id)
        assertEquals(Styles.PRO, on.copy(perAppStyles = false).styleFor("com.whatsapp").id)
    }

    @Test fun neverEmpty() {
        val s = Settings(hiddenStyles = Styles.builtIn.map { it.id }.toSet())
        assertTrue(s.allStyles.isNotEmpty())
    }

    @Test fun emojiLabels() {
        val clean = Styles.builtIn.first()
        assertEquals("✨ Clean", Settings().label(clean))
        assertEquals("Clean", Settings(showStyleEmoji = false).label(clean))
        assertEquals("Clean", Settings().label(clean.copy(emoji = "")))
    }
}
