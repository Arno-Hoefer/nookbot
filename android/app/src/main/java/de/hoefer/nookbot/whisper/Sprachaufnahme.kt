package de.hoefer.nookbot.whisper

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.io.File
import java.io.IOException
import kotlin.math.sqrt

/**
 * Hört zu, bis jemand etwas gesagt hat und dann eine Pause macht (einfache Pegel-Erkennung, kein Netz).
 * Liefert die Samples ab kurz vor dem ersten Wort – direkt im Whisper-Format (16 kHz, mono, Float).
 */
class Sprachaufnahme(private val onLevel: (Float) -> Unit) {
    @Volatile private var stopp = false
    @Volatile private var jetztFertig = false

    /** Aufnahme beenden, ohne etwas zu liefern (Sprachmodus zu, Mikro stumm). */
    fun abbrechen() { stopp = true }

    /** Das Gesagte sofort abschließen (Tipp auf den Kreis), statt die Pause abzuwarten. */
    fun fertig() { jetztFertig = true }

    /** Blockiert, bis ein Satz zu Ende ist. null = abgebrochen. */
    @SuppressLint("MissingPermission") // Wird vor dem Start in der UI geprüft.
    fun hoeren(maxSekunden: Int = 90): FloatArray? {
        stopp = false
        jetztFertig = false
        val frame = RATE / 20 // 50 ms
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
        if (rec.state != AudioRecord.STATE_INITIALIZED) throw IOException("Mikrofon nicht verfügbar")
        val aec = runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } else null }.getOrNull()
        val ns = runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null }.getOrNull()
        val vorlauf = ArrayDeque<FloatArray>()  // ~0,5 s vor dem ersten Wort
        val gesagt = ArrayList<FloatArray>()
        val buf = ShortArray(frame)
        var rauschen = 0.0
        var kalibriert = 0
        var laut = 0
        var leise = 0
        var spricht = false
        var frames = 0
        try {
            rec.startRecording()
            while (!stopp) {
                val n = rec.read(buf, 0, frame)
                if (n <= 0) continue
                frames++
                val f = FloatArray(n) { buf[it] / 32768f }
                var sum = 0.0
                for (x in f) sum += x * x
                val rms = sqrt(sum / n)
                onLevel((rms * 12).coerceIn(0.0, 1.0).toFloat())
                // Grundrauschen der ersten halben Sekunde, danach langsam nachführen solange niemand spricht
                if (kalibriert < 10) { rauschen += rms / 10; kalibriert++; vorlauf.addLast(f); continue }
                val schwelle = maxOf(rauschen * 3.0, 0.012)
                if (!spricht) {
                    vorlauf.addLast(f)
                    if (vorlauf.size > 10) vorlauf.removeFirst()
                    if (rms > schwelle) laut++ else { laut = 0; rauschen = rauschen * 0.95 + rms * 0.05 }
                    if (laut >= 3) { spricht = true; gesagt.addAll(vorlauf); vorlauf.clear() }
                } else {
                    gesagt.add(f)
                    if (rms < schwelle * 0.8) leise++ else leise = 0
                    if (leise >= 26 || jetztFertig || gesagt.size >= maxSekunden * 20) break // ~1,3 s Pause
                }
                if (jetztFertig && !spricht) jetztFertig = false
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            aec?.release()
            ns?.release()
            onLevel(0f)
        }
        if (stopp || gesagt.size < 10) return if (stopp) null else FloatArray(0)
        val out = FloatArray(gesagt.sumOf { it.size })
        var i = 0
        for (f in gesagt) { f.copyInto(out, i); i += f.size }
        return out
    }

    companion object { const val RATE = 16_000 }
}

/** Whisper-Kontext, der für ein ganzes Sprachgespräch geladen bleibt (Laden dauert ~1 s). */
class WhisperSitzung(modelFile: File) : AutoCloseable {
    private val ctx: Long

    init {
        if (!WhisperLib.isSupportedCpu()) throw IOException("Dieses Handy ist für die lokale Spracherkennung zu alt.")
        ctx = WhisperLib.initContext(modelFile.absolutePath)
        if (ctx == 0L) throw IOException("Sprachmodell konnte nicht geladen werden – in den Einstellungen neu laden.")
    }

    fun text(samples: FloatArray): String {
        if (samples.size < RATE / 2) return ""
        val threads = minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
        val bytes = WhisperLib.transcribe(ctx, samples, "de", threads, null) ?: return ""
        return String(bytes, Charsets.UTF_8).replace(Regex("""[\[(\*][^\])\*]{1,30}[\])\*]"""), "").replace(Regex(" {2,}"), " ").trim()
    }

    override fun close() = WhisperLib.freeContext(ctx)

    private companion object { const val RATE = 16_000 }
}
