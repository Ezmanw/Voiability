package com.voiability.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DictionaryEntry(
    /** What the user wants typed, e.g. "Voiability". Also fed to Whisper as a spelling hint. */
    val word: String,
    /** Optional mis-hearings that should become [word], e.g. "voy ability". */
    val soundsLike: List<String> = emptyList(),
)

/** Flat is a plain Material disc; Liquid is the metaball renderer that melts into the screen edge. */
enum class BubbleLook { FLAT, LIQUID }

/** Which tonal palette of the wallpaper (Material You) colours the bubble. */
enum class BubbleColor(val label: String) { PRIMARY("Primary"), SECONDARY("Secondary"), TERTIARY("Tertiary"), NEUTRAL("Neutral"), INVERSE("Inverse") }

/** What the bubble shows while it hears you. */
enum class ListenAnim(val label: String) { WAVE("Waveform"), PULSE("Pulse ring"), BLOB("Blobs") }

/** What the bubble shows while it's working on your text. */
enum class Spinner(val label: String) { ARC("Arc"), MORPH("Morphing shape"), DOTS("Orbiting dots"), RIPPLE("Ripple") }

@Serializable
data class BubbleSettings(
    val enabled: Boolean = true,
    val sizeDp: Float = 56f,
    val idleOpacity: Float = 0.92f,
    val look: BubbleLook = BubbleLook.FLAT,
    val color: BubbleColor = BubbleColor.PRIMARY,
    val listenAnim: ListenAnim = ListenAnim.WAVE,
    val spinner: Spinner = Spinner.MORPH,
    /** Gooey metaball merge with the screen edge (Liquid look only). */
    val liquid: Boolean = true,
    val onlyWhileTyping: Boolean = true,
    /** Docked side: false = left, true = right. */
    val rightSide: Boolean = true,
    /** Vertical position as a fraction of the screen height. */
    val yFraction: Float = 0.42f,
)

@Serializable
data class Settings(
    val onboarded: Boolean = false,
    val sttModelId: String = "",
    val llmModelId: String = "",
    val polish: Boolean = true,
    /** Whisper language code, or "auto". English-only models ignore this. */
    val language: String = "en",
    val defaultStyleId: String = Styles.CLEAN,
    /** package name -> style id. */
    val appStyles: Map<String, String> = Styles.defaultAppStyles,
    val customStyles: List<Style> = emptyList(),
    val dictionary: List<DictionaryEntry> = emptyList(),
    val bubble: BubbleSettings = BubbleSettings(),
    val livePreview: Boolean = true,
    val haptics: Boolean = true,
    val removeFillers: Boolean = true,
    val spokenCommands: Boolean = true,
    /** Stop automatically after this many seconds of silence (0 = never). */
    val autoStopSeconds: Float = 0f,
    val keepHistory: Boolean = true,
    val historyDays: Int = 7,
    /** Off = always use the default style, whatever app you're in. */
    val perAppStyles: Boolean = true,
    val showStyleEmoji: Boolean = true,
    /** Built-in styles the user deleted. */
    val hiddenStyles: Set<String> = emptySet(),
) {
    /**
     * Built-ins (minus deleted ones, with any user edits applied) followed by custom styles.
     * An edited built-in is stored in [customStyles] under the built-in's id.
     */
    val allStyles: List<Style>
        get() {
            val overrides = customStyles.associateBy { it.id }
            val builtIns = Styles.builtIn.filter { it.id !in hiddenStyles }.map { b -> overrides[b.id]?.copy(builtIn = true) ?: b }
            val builtInIds = Styles.builtIn.map { it.id }.toSet()
            return (builtIns + customStyles.filter { it.id !in builtInIds }).ifEmpty { listOf(Styles.builtIn.first()) }
        }

    fun style(id: String?): Style = allStyles.firstOrNull { it.id == id } ?: allStyles.first()

    fun styleFor(packageName: String?): Style =
        style(packageName?.takeIf { perAppStyles }?.let { appStyles[it] } ?: defaultStyleId)

    /** "✨ Clean", or just "Clean" when emoji are turned off or the style has none. */
    fun label(style: Style): String =
        if (showStyleEmoji && style.emoji.isNotBlank()) "${style.emoji} ${style.name}" else style.name
}

private val Context.dataStore by preferencesDataStore("voiability")

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private val key = stringPreferencesKey("settings")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun decode(raw: String?): Settings =
        raw?.let { runCatching { json.decodeFromString<Settings>(it) }.getOrNull() } ?: Settings()

    // Read once synchronously so services that start cold see real values immediately.
    private val initial: Settings = runBlocking { decode(context.dataStore.data.first()[key]) }

    val settings: StateFlow<Settings> = context.dataStore.data
        .map { decode(it[key]) }
        .stateIn(scope, SharingStarted.Eagerly, initial)

    val current: Settings get() = settings.value

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(Settings.serializer(), transform(decode(prefs[key])))
        }
    }
}
