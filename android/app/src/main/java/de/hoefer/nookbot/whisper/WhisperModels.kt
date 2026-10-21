package de.hoefer.nookbot.whisper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

enum class WhisperModel(val id: String, val label: String, val hint: String, val fileName: String, val sizeBytes: Long) {
    BASE("base", "Schnell", "60 MB · für ältere Handys, Deutsch ordentlich", "ggml-base-q5_1.bin", 59_707_625),
    SMALL("small", "Genau", "190 MB · deutlich besseres Deutsch, empfohlen", "ggml-small-q5_1.bin", 190_085_487);

    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id } ?: SMALL
    }
}

/** Sprachmodelle liegen im privaten App-Speicher; Download einmalig, mit Fortsetzen nach Abbruch. */
object WhisperModels {
    private fun dir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }
    fun file(context: Context, m: WhisperModel) = File(dir(context), m.fileName)
    /** Für den Chat: lieber das schnelle Modell, sonst was da ist. */
    fun installedPreferred(context: Context): WhisperModel? =
        listOf(WhisperModel.BASE, WhisperModel.SMALL).firstOrNull { isInstalled(context, it) }

    fun isInstalled(context: Context, m: WhisperModel) = file(context, m).let { it.exists() && it.length() == m.sizeBytes }
    fun delete(context: Context, m: WhisperModel) {
        file(context, m).delete()
        File(dir(context), m.fileName + ".part").delete()
    }

    suspend fun download(context: Context, m: WhisperModel, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val target = file(context, m)
        val part = File(dir(context), m.fileName + ".part")
        var have = if (part.exists()) part.length() else 0L
        if (have > m.sizeBytes) { part.delete(); have = 0L }  // kaputter Rest (z. B. zwei Downloads gleichzeitig)

        var conn = open(m.url, have)
        // Huggingface leitet auf ein CDN weiter; HttpURLConnection folgt Weiterleitungen nicht über Hosts hinweg.
        var hops = 0
        while (conn.responseCode in 300..399 && hops++ < 5) {
            val next = URL(conn.url, conn.getHeaderField("Location")).toString()
            conn.disconnect()
            conn = open(next, have)
        }
        try {
            when (conn.responseCode) {
                206 -> Unit
                200 -> { have = 0; part.delete() }
                416 -> Unit // schon vollständig
                else -> throw IOException("Download fehlgeschlagen (HTTP ${conn.responseCode})")
            }
            if (conn.responseCode != 416) {
                conn.inputStream.use { input ->
                    FileOutputStream(part, have > 0).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = have
                        var lastReport = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport > 1_000_000) {
                                lastReport = done
                                onProgress(done.toFloat() / m.sizeBytes)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (part.length() != m.sizeBytes) throw IOException("Download unvollständig – bitte erneut starten.")
        if (!part.renameTo(target)) throw IOException("Modell konnte nicht gespeichert werden.")
        onProgress(1f)
    }

    private fun open(url: String, from: Long) = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 60_000
        if (from > 0) setRequestProperty("Range", "bytes=$from-")
    }
}
