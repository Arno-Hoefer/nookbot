package de.hoefer.nookbot.whisper

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Nimmt direkt im Whisper-Format auf (16 kHz, mono, PCM16 als WAV) – keine Umwandlung nötig.
 * ~1,9 MB pro Minute.
 */
class WavRecorder(private val file: File, private val onLevel: (Float) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null
    var samplesWritten = 0L
        private set

    @SuppressLint("MissingPermission") // Wird vor dem Start in der UI geprüft.
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Mikrofon nicht verfügbar" }
        running = true
        thread = Thread {
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                out.write(ByteArray(44)) // Platz für den Header
                val buf = ShortArray(RATE / 10) // 100 ms
                val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                record.startRecording()
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    bytes.clear()
                    var sum = 0.0
                    for (i in 0 until n) {
                        bytes.putShort(buf[i])
                        sum += buf[i] * buf[i].toDouble()
                    }
                    out.write(bytes.array(), 0, n * 2)
                    samplesWritten += n
                    // RMS → 0..1, leicht angehoben, damit normales Sprechen sichtbar ausschlägt
                    onLevel((sqrt(sum / n) / 6000.0).coerceIn(0.0, 1.0).toFloat())
                }
                record.stop()
                record.release()
                writeHeader(out, samplesWritten)
            }
        }.apply { name = "wav-recorder"; start() }
    }

    fun stop() {
        running = false
        thread?.join(3000)
        thread = null
    }

    private fun writeHeader(out: RandomAccessFile, samples: Long) {
        val dataLen = (samples * 2).toInt()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataLen); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataLen)
        }
        out.seek(0)
        out.write(h.array())
    }

    companion object {
        const val RATE = 16_000
    }
}
