package de.hoefer.nookbot.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Schnittstelle zum NookBot-Adapter auf dem Pi (JSON + Server-Sent Events, nur im Tailnet). */
class Api(private val base: () -> String, private val token: () -> String) {
    private val json = "application/json; charset=utf-8".toMediaType()

    fun get(path: String): JSONObject = call(Request.Builder().url(base() + path).get())
    fun post(path: String, body: JSONObject = JSONObject()): JSONObject = call(Request.Builder().url(base() + path).post(body.toString().toRequestBody(json)))
    fun put(path: String, body: JSONObject): JSONObject = call(Request.Builder().url(base() + path).put(body.toString().toRequestBody(json)))
    fun delete(path: String): JSONObject = call(Request.Builder().url(base() + path).delete())

    /** POST, Antwort als Bytes (z. B. Sprachausgabe als WAV). */
    fun postBytes(path: String, body: JSONObject): ByteArray {
        if (token().isBlank()) throw IOException("Chat-Token fehlt (Einstellungen)")
        val req = Request.Builder().url(base() + path).post(body.toString().toRequestBody(json)).header("Authorization", "Bearer ${token()}").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException(runCatching { JSONObject(r.body?.string() ?: "").optString("error") }.getOrNull() ?: "HTTP ${r.code}")
            return r.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun call(b: Request.Builder): JSONObject {
        if (token().isBlank()) throw IOException("Chat-Token fehlt (Einstellungen)")
        client.newCall(b.header("Authorization", "Bearer ${token()}").build()).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (r.code == 401) throw IOException("Chat-Token abgelehnt")
            if (!r.isSuccessful) throw IOException(runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP ${r.code}")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    /**
     * Ereignisstrom lesen, bis er abbricht. onEvent bekommt jedes Ereignis; die Funktion kehrt zurück, wenn die
     * Verbindung endet (der Aufrufer verbindet dann neu, mit dem letzten seq).
     */
    fun events(since: Long, onOpen: () -> Unit, onEvent: (JSONObject) -> Unit) {
        val req = Request.Builder().url(base() + "/api/events?since=$since").header("Authorization", "Bearer ${token()}")
            .header("Accept", "text/event-stream").build()
        streamClient.newCall(req).execute().use { r ->
            if (r.code == 401) throw IOException("Chat-Token abgelehnt")
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            onOpen()
            val src = r.body?.source() ?: return
            val data = StringBuilder()
            while (!src.exhausted()) {
                val line = src.readUtf8Line() ?: break
                when {
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                    line.isEmpty() && data.isNotEmpty() -> {
                        runCatching { JSONObject(data.toString()) }.getOrNull()?.let(onEvent)
                        data.clear()
                    }
                }
            }
        }
    }

    companion object {
        val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
        // Der Adapter schickt alle 45 s ein Lebenszeichen – 100 s ohne Daten heißt: Verbindung tot
        private val streamClient: OkHttpClient = client.newBuilder().readTimeout(100, TimeUnit.SECONDS).build()
    }
}
