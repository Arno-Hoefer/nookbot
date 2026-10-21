package de.hoefer.nookbot

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import de.hoefer.nookbot.data.Settings
import de.hoefer.nookbot.net.Api
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Absturzberichte: Java-Abstürze schreibt der Handler sofort in eine Datei; native (z. B. in whisper.cpp) meldet Android
 * beim nächsten Start (ApplicationExitInfo). Beides geht dann an den Pi (/api/absturz) – ohne Chat-Inhalte.
 */
class NookApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val alt = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                File(filesDir, "absturz.txt").writeText("Thread ${t.name}\n" + e.stackTraceToString().take(12_000))
            }
            alt?.uncaughtException(t, e)
        }
    }
}

object Absturz {
    /** Seit dem letzten Mal: eigene Datei + von Android gemeldete Abstürze/Abbrüche einsammeln und an den Pi schicken. */
    fun melden(ctx: Context) {
        val prefs = ctx.getSharedPreferences("nookbot", Context.MODE_PRIVATE)
        val seit = prefs.getLong("absturzSeit", 0L)
        val berichte = JSONArray()
        val f = File(ctx.filesDir, "absturz.txt")
        if (f.exists()) {
            berichte.put(JSONObject().put("art", "java").put("text", f.readText()))
            f.delete()
        }
        var neuester = seit
        runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            for (x in am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)) {
                if (x.timestamp <= seit) continue
                neuester = maxOf(neuester, x.timestamp)
                if (x.reason !in setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                        ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED)) continue
                val o = JSONObject().put("art", when (x.reason) {
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "nativ"; ApplicationExitInfo.REASON_ANR -> "anr"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "speicher"; ApplicationExitInfo.REASON_SIGNALED -> "signal"; else -> "java"
                }).put("zeit", x.timestamp).put("beschreibung", x.description ?: "").put("pss_kb", x.pss).put("rss_kb", x.rss)
                // Bei nativen Abstürzen liefert Android den Tombstone (Protobuf) – die lesbaren Teile (Signal, Funktionsnamen) reichen
                if (x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || x.reason == ApplicationExitInfo.REASON_ANR) {
                    runCatching { x.traceInputStream?.use { s -> o.put("spur", lesbar(s.readBytes())) } }
                }
                berichte.put(o)
            }
        }
        if (berichte.length() == 0) { prefs.edit().putLong("absturzSeit", neuester).apply(); return }
        val s = Settings(ctx)
        val info = JSONObject().put("app", BuildConfig.VERSION_NAME).put("geraet", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("cpu", runCatching { File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") } ?: "" }.getOrDefault(""))
            .put("modelle", JSONArray(File(ctx.filesDir, "models").listFiles()?.map { "${it.name} ${it.length()}" } ?: emptyList<String>()))
        runCatching {
            Api({ s.serverUrl }, { s.chatToken }).post("/api/absturz", info.put("berichte", berichte))
            prefs.edit().putLong("absturzSeit", neuester).apply()
        }
    }

    /** Aus Binärdaten die lesbaren Zeichenketten (≥ 4 Zeichen) – wie „strings“. */
    private fun lesbar(b: ByteArray): String {
        val sb = StringBuilder()
        val cur = StringBuilder()
        for (x in b) {
            val c = x.toInt() and 0xff
            if (c in 32..126) cur.append(c.toChar()) else { if (cur.length >= 4) sb.append(cur).append('\n'); cur.clear() }
            if (sb.length > 20_000) break
        }
        return sb.toString()
    }
}
