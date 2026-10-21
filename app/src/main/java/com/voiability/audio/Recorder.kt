package com.voiability.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 16 kHz mono float capture on a dedicated high-priority thread. Samples accumulate in memory
 * (a minute of audio is under 4 MB) so live previews can re-read everything heard so far.
 */
class Recorder {
    private val _level = MutableStateFlow(0f)
    /** Smoothed loudness 0..1, for waveforms and blob wobble. */
    val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile private var running = false
    private var thread: Thread? = null
    private val lock = Any()
    private var buffer = FloatArray(SAMPLE_RATE * 30)
    private var size = 0

    /** Milliseconds since the last frame that sounded like speech. */
    @Volatile var silentForMs: Long = 0
        private set
    @Volatile var heardSpeech: Boolean = false
        private set
    @Volatile var peak: Float = 0f
        private set

    val sampleCount: Int get() = synchronized(lock) { size }
    val durationMs: Long get() = sampleCount * 1000L / SAMPLE_RATE

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
                max(minBuf, SAMPLE_RATE / 5 * 4),
            )
        }.getOrNull() ?: return false
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        synchronized(lock) { size = 0 }
        silentForMs = 0
        heardSpeech = false
        peak = 0f
        running = true
        record.startRecording()
        thread = Thread({ loop(record) }, "voi-mic").apply { start() }
        return true
    }

    private fun loop(record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frame = FloatArray(SAMPLE_RATE / 50) // 20 ms
        var smooth = 0f
        try {
            while (running) {
                val n = record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                var sum = 0f
                for (i in 0 until n) sum += frame[i] * frame[i]
                val rms = sqrt(sum / n)
                peak = max(peak, rms)
                // Map roughly -55 dBFS..-15 dBFS onto 0..1.
                val db = 20 * log10(rms + 1e-7f)
                val target = ((db + 55f) / 40f).coerceIn(0f, 1f)
                smooth += (target - smooth) * if (target > smooth) 0.5f else 0.15f
                _level.value = smooth
                if (target > 0.32f) {
                    silentForMs = 0
                    heardSpeech = true
                } else {
                    silentForMs += n * 1000L / SAMPLE_RATE
                }
                synchronized(lock) {
                    if (size + n > buffer.size) buffer = buffer.copyOf(buffer.size * 2)
                    System.arraycopy(frame, 0, buffer, size, n)
                    size += n
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            _level.value = 0f
        }
    }

    /** Copy of everything recorded so far. */
    fun snapshot(): FloatArray = synchronized(lock) { buffer.copyOf(size) }

    /** Stops capture and returns all samples. */
    fun stop(): FloatArray {
        running = false
        thread?.join(500)
        thread = null
        return snapshot()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
