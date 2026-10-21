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

@Serializable
data class BubbleSettings(
    val enabled: Boolean = true,
    val sizeDp: Float = 58f,
    val idleOpacity: Float = 0.92f,
    /** Gooey metaball merge with the screen edge. */
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
) {
    val allStyles: List<Style> get() = Styles.builtIn + customStyles

    fun style(id: String?): Style = allStyles.firstOrNull { it.id == id } ?: Styles.builtIn.first()

    fun styleFor(packageName: String?): Style =
        style(packageName?.let { appStyles[it] } ?: defaultStyleId)
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
