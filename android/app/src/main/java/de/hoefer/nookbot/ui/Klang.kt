package de.hoefer.nookbot.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/** Kleine, weiche Töne für Sprachmodus und Anruf – selbst erzeugt (Sinus mit Ein-/Ausblenden), keine Tondateien. */
object Klang {
    private const val RATE = 22050

    /** Annehmen: zwei aufsteigende Töne. */
    fun an() = spielen(listOf(660.0 to 110, 990.0 to 160), 0.22f)

    /** Auflegen: zwei absteigende Töne. */
    fun aus() = spielen(listOf(990.0 to 110, 660.0 to 160), 0.22f)

    /** „Ich höre“: ein ganz kurzer, leiser Ton. */
    fun hoeren() = spielen(listOf(1180.0 to 70), 0.12f)

    private fun spielen(toene: List<Pair<Double, Int>>, laut: Float) {
        Thread {
            runCatching {
                val samples = toene.flatMap { (hz, ms) ->
                    val n = RATE * ms / 1000
                    (0 until n).map { i ->
                        val huelle = minOf(1.0, i / (RATE * 0.012), (n - i) / (RATE * 0.04))  // weich rein, langsam raus
                        (sin(2 * PI * hz * i / RATE) * huelle * laut * Short.MAX_VALUE).toInt().toShort()
                    }
                }.toShortArray()
                val imAnruf = de.hoefer.nookbot.Anruf.zustand.value == de.hoefer.nookbot.Anruf.Zustand.Laeuft
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(if (imAnruf) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
                t.write(samples, 0, samples.size)
                t.play()
                Thread.sleep(toene.sumOf { it.second }.toLong() + 120)
                t.release()
            }
        }.start()
    }
}
