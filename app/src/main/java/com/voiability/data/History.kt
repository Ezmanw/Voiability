package com.voiability.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HistoryItem(
    val id: Long,
    val time: Long,
    val text: String,
    val raw: String,
    val styleId: String,
    val app: String? = null,
    val audioMs: Long = 0,
    val sttMs: Long = 0,
    val llmMs: Long = 0,
)

/** Small on-device history, stored as a JSON file in no-backup storage. */
class HistoryStore(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.noBackupFilesDir, "history.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(HistoryItem.serializer())
    private val _items = MutableStateFlow<List<HistoryItem>>(emptyList())
    val items: StateFlow<List<HistoryItem>> = _items.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val loaded = runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())
            _items.update { current -> (current + loaded).distinctBy { it.id } }
        }
    }

    fun add(item: HistoryItem, retentionDays: Int) {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        _items.update { (listOf(item) + it).filter { h -> h.time >= cutoff }.take(MAX) }
        persist()
    }

    fun delete(id: Long) {
        _items.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    fun clear() {
        _items.value = emptyList()
        persist()
    }

    private fun persist() {
        val snapshot = _items.value
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(file.parentFile, "history.json.tmp")
                tmp.writeText(json.encodeToString(serializer, snapshot))
                tmp.renameTo(file)
            }
        }
    }

    private companion object {
        const val MAX = 500
    }
}
