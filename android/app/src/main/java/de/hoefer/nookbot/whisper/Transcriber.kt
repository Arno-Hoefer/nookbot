package de.hoefer.nookbot.whisper

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Transkribiert eine WAV-Datei (16 kHz, mono, PCM16) in Stücken von 10 Minuten,
 * damit auch lange Aufnahmen wenig Speicher brauchen.
 */
class Transcriber(private val modelFile: File) {

    fun transcribe(wav: File, language: String, onProgress: (Int) -> Unit, isCancelled: () -> Boolean): String {
        if (!WhisperLib.isSupportedCpu()) {
            throw IOException("Dieses Handy ist für die lokale Transkription zu alt (CPU ohne FP16/DotProd).")
        }
        val ctx = WhisperLib.initContext(modelFile.absolutePath)
        if (ctx == 0L) throw IOException("Sprachmodell konnte nicht geladen werden – in den Einstellungen neu laden.")
        try {
            RandomAccessFile(wav, "r").use { raf ->
                val totalSamples = ((raf.length() - WAV_HEADER) / 2).toInt()
                if (totalSamples < SAMPLE_RATE / 2) return ""
                val parts = mutableListOf<String>()
                var offset = 0
                val threads = min(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
                while (offset < totalSamples) {
                    val count = min(CHUNK_SAMPLES, totalSamples - offset)
                    val samples = readChunk(raf, offset, count)
                    val base = offset
                    val listener = object : WhisperLib.ProgressListener {
                        override fun onProgress(percent: Int) =
                            onProgress(((base + count.toLong() * percent / 100) * 100 / totalSamples).toInt())
                        override fun isCancelled() = isCancelled()
                    }
                    val bytes = WhisperLib.transcribe(ctx, samples, language, threads, listener)
                        ?: if (isCancelled()) throw IOException("Abgebrochen") else throw IOException("Transkription fehlgeschlagen.")
                    parts += stripNonSpeech(String(bytes, Charsets.UTF_8))
                    offset += count
                }
                onProgress(100)
                return parts.filter { it.isNotBlank() }.joinToString("\n\n")
            }
        } finally {
            WhisperLib.freeContext(ctx)
        }
    }

    private fun readChunk(raf: RandomAccessFile, offset: Int, count: Int): FloatArray {
        val bytes = ByteArray(count * 2)
        raf.seek(WAV_HEADER + offset.toLong() * 2)
        raf.readFully(bytes)
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(count) { shorts.get(it) / 32768f }
    }

    /** Whisper schreibt bei Stille gern „[Musik]“ oder „(Applaus)“ – solche Marker entfernen. */
    private fun stripNonSpeech(text: String) =
        text.replace(Regex("""[\[(\*][^\])\*]{1,30}[\])\*]"""), "").replace(Regex(" {2,}"), " ").trim()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val WAV_HEADER = 44L
        private const val CHUNK_SAMPLES = SAMPLE_RATE * 60 * 10
    }
}
