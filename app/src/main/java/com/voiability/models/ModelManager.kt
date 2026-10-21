package com.voiability.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val progress: Float, val bytesPerSec: Long) : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Downloads, imports and deletes model files. Downloads resume from `.part` files, so a
 * dropped connection or killed process never restarts a 2 GB download from zero.
 */
class ModelManager(private val context: Context, private val scope: CoroutineScope) {
    val dir: File = File(context.noBackupFilesDir, "models").apply { mkdirs() }

    private val _states = MutableStateFlow(ModelCatalog.all.associate { it.id to stateOf(it) })
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    fun file(model: ModelInfo): File = File(dir, model.fileName)

    fun fileFor(id: String): File? = ModelCatalog.byId(id)?.let(::file)?.takeIf { it.exists() }

    fun isReady(id: String): Boolean = _states.value[id] == ModelState.Ready

    val vadFile: File? get() = fileFor(ModelCatalog.vad.id)

    val anyDownloading: Boolean get() = _states.value.values.any { it is ModelState.Downloading }

    private fun stateOf(model: ModelInfo): ModelState =
        if (file(model).exists()) ModelState.Ready else ModelState.Missing

    private fun set(id: String, state: ModelState) = _states.update { it + (id to state) }

    fun download(model: ModelInfo, onDone: (Boolean) -> Unit = {}) {
        if (jobs[model.id]?.isActive == true || isReady(model.id)) return
        DownloadService.start(context)
        // Every speech model needs the tiny VAD model to skip silence.
        if (model.kind == ModelKind.SPEECH) download(ModelCatalog.vad)
        set(model.id, ModelState.Downloading(0f, 0))
        jobs[model.id] = scope.launch(Dispatchers.IO) {
            val ok = runCatching { fetch(model) }
                .onFailure { set(model.id, ModelState.Failed(it.message ?: "Download failed")) }
                .isSuccess
            if (ok) set(model.id, ModelState.Ready)
            jobs.remove(model.id)
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    fun cancel(model: ModelInfo) {
        jobs.remove(model.id)?.cancel()
        set(model.id, stateOf(model))
    }

    fun delete(model: ModelInfo) {
        cancel(model)
        file(model).delete()
        File(dir, model.fileName + ".part").delete()
        set(model.id, ModelState.Missing)
    }

    private suspend fun fetch(model: ModelInfo) = withContext(Dispatchers.IO) {
        val target = file(model)
        val part = File(dir, model.fileName + ".part")
        var url = URL(model.url)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Voiability/1.0")
                if (part.exists() && part.length() > 0) setRequestProperty("Range", "bytes=${part.length()}-")
            }
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 8) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect()
                continue
            }
            break
        }
        val resumed = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (!resumed) part.delete()
        if (conn.responseCode !in 200..299) error("Server returned ${conn.responseCode}")

        val total = if (resumed) part.length() + conn.contentLengthLong else conn.contentLengthLong
        var done = part.length()
        var windowStart = System.nanoTime()
        var windowBytes = 0L
        var speed = 0L
        conn.inputStream.use { input ->
            FileOutputStream(part, resumed).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    windowBytes += n
                    val now = System.nanoTime()
                    if (now - windowStart > 400_000_000) {
                        speed = windowBytes * 1_000_000_000 / (now - windowStart)
                        windowStart = now
                        windowBytes = 0
                        val p = if (total > 0) done.toFloat() / total else 0f
                        set(model.id, ModelState.Downloading(p, speed))
                    }
                }
            }
        }
        if (total > 0 && part.length() != total) error("Download incomplete")
        if (!part.renameTo(target)) error("Could not save model")
    }

    /** Copies a user-picked model file (e.g. a custom fine-tune) over a catalog slot. */
    suspend fun import(model: ModelInfo, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }.orEmpty()
            val ok = when (model.kind) {
                ModelKind.POLISH -> name.endsWith(".gguf", true)
                else -> name.endsWith(".bin", true)
            }
            if (!ok) return@runCatching false
            val tmp = File(dir, model.fileName + ".import")
            context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            tmp.renameTo(file(model)).also { if (it) set(model.id, ModelState.Ready) }
        }.getOrDefault(false)
    }
}
