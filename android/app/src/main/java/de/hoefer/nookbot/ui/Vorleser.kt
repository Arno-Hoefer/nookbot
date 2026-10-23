package de.hoefer.nookbot.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Vorlesen Satz für Satz – mit einer Pi-Stimme (Piper, „pi:…“, der nächste Satz wird schon geholt, während der aktuelle läuft)
 * oder einer Android-Stimme („handy:<Name>“). Namen spricht das Aussprache-Wörterbuch richtig aus (auf dem Pi bzw. hier).
 * Fällt der Pi aus, liest Android weiter.
 */
class Vorleser(private val ctx: Context, private val vm: NookViewModel) {
    @Volatile private var bereit = false
    @Volatile private var abbruch = false
    private var fertig: ((Boolean) -> Unit)? = null
    private var player: MediaPlayer? = null
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.GERMANY
                bereit = true
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { fertig?.invoke(true) }
            @Deprecated("alt") override fun onError(id: String?) { fertig?.invoke(false) }
            override fun onStop(id: String?, interrupted: Boolean) { fertig?.invoke(false) }
        })
    }

    /** Deutsche Android-Stimmen (Name → Beschreibung); „Netz“-Stimmen von Google klingen natürlicher, brauchen aber Internet. */
    suspend fun handyStimmen(): List<Pair<String, String>> {
        withTimeoutOrNull(3000) { while (!bereit) delay(100) } ?: return emptyList()
        return tts.voices.orEmpty().filter { it.locale.language == "de" }.sortedBy { it.name }.map { v ->
            v.name to (v.name.substringAfter("de-de-x-").substringBefore("-").uppercase() +
                (if (v.isNetworkConnectionRequired) " · Netz" else " · offline") + " · Qualität ${v.quality}")
        }
    }

    suspend fun sprechen(text: String, stimme: String, onSatz: (String) -> Unit = {}) {
        abbruch = false
        val saetze = text.split(Regex("(?<=[.!?:;])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        // Pi- und Cloud-Stimmen (pi:…, el:…) kommen vom Pi, nur handy:… spricht Android selbst
        if (!stimme.startsWith("handy:")) piSprechen(saetze, stimme, onSatz) else saetze.forEach { s ->
            if (abbruch) return
            onSatz(s)
            if (!handySprechen(ausspracheHier(s), stimme.removePrefix("handy:"))) return
        }
    }

    private suspend fun piSprechen(saetze: List<String>, stimme: String, onSatz: (String) -> Unit) = coroutineScope {
        fun holen(i: Int): Deferred<ByteArray?> = async(Dispatchers.IO) { runCatching { vm.piSprechen(saetze[i], stimme) }.getOrNull() }
        var naechster: Deferred<ByteArray?>? = if (saetze.isNotEmpty()) holen(0) else null
        for (i in saetze.indices) {
            if (abbruch) return@coroutineScope
            val wav = naechster?.await()
            naechster = if (i + 1 < saetze.size) holen(i + 1) else null
            onSatz(saetze[i])
            val weiter = if (wav != null && wav.size > 44) abspielen(wav, i) else handySprechen(ausspracheHier(saetze[i]), "")
            de.hoefer.nookbot.Anruf.protokoll("Satz ${i + 1}/${saetze.size}: ${if (wav != null && wav.size > 44) "Pi ${wav.size / 1024} KB" else "Handy-Stimme"} → ${if (weiter) "gespielt" else "abgebrochen/Fehler"}")
            if (!weiter) { naechster?.cancel(); return@coroutineScope }
        }
    }

    private suspend fun abspielen(wav: ByteArray, i: Int): Boolean {
        val f = File(ctx.cacheDir, "satz-${i % 3}.wav")
        withContext(Dispatchers.IO) { f.writeBytes(wav) }
        return suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer()
            player = mp
            fun ende(ok: Boolean) { runCatching { mp.release() }; if (player === mp) player = null; if (cont.isActive) cont.resume(ok) }
            fertig = { ok -> fertig = null; ende(ok) }
            runCatching {
                // Im Anruf läuft der Ton als Telefonat – andere Arten schaltet Android im Gespräch leise oder stumm
                val imAnruf = de.hoefer.nookbot.Anruf.zustand.value == de.hoefer.nookbot.Anruf.Zustand.Laeuft
                mp.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(if (imAnruf) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                mp.setDataSource(f.absolutePath)
                mp.setOnCompletionListener { ende(true) }
                mp.setOnErrorListener { _, _, _ -> ende(false); true }
                mp.prepare()
                mp.start()
            }.onFailure { ende(false) }
            cont.invokeOnCancellation { runCatching { mp.stop(); mp.release() } }
        }
    }

    private suspend fun handySprechen(satz: String, name: String): Boolean {
        withTimeoutOrNull(3000) { while (!bereit) delay(100) } ?: return false
        if (name.isNotBlank()) tts.voices.orEmpty().firstOrNull { it.name == name }?.let { tts.voice = it }
        val imAnruf = de.hoefer.nookbot.Anruf.zustand.value == de.hoefer.nookbot.Anruf.Zustand.Laeuft
        tts.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(if (imAnruf) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        return suspendCancellableCoroutine { cont ->
            fertig = { ok -> fertig = null; if (cont.isActive) cont.resume(ok) }
            tts.speak(satz, TextToSpeech.QUEUE_FLUSH, null, "s${System.nanoTime()}")
            cont.invokeOnCancellation { tts.stop() }
        }
    }

    /** Aussprache-Wörterbuch für Android-Stimmen (die Pi-Stimmen ersetzen auf dem Pi). */
    private fun ausspracheHier(t: String): String {
        val a = vm.stimmen.value?.optJSONObject("aussprache") ?: return t
        var s = t
        a.keys().asSequence().sortedByDescending { it.length }.forEach { w ->
            s = s.replace(Regex("(?<![\\w-])" + Regex.escape(w) + "(?![\\w-])", RegexOption.IGNORE_CASE), a.optString(w))
        }
        return s
    }

    fun stopp() {
        abbruch = true
        tts.stop()
        runCatching { player?.stop() }
        fertig?.invoke(false)
    }

    fun close() { stopp(); runCatching { tts.shutdown() } }
}
