package de.hoefer.nookbot.net

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Eine wartende oder entschiedene Schreibaktion des Bots (home-mcp, freigaben.json). */
data class Freigabe(
    val id: String, val tool: String, val text: String, val status: String,
    val erstellt: Long, val ergebnis: String?,
) {
    val offen: Boolean get() = status == "offen"
    val titel: String get() = when (tool) {
        "termin_anlegen" -> "Termin eintragen"
        "termin_gaeste" -> "Gäste einladen"
        "entwurf_anlegen" -> "Mail-Entwurf anlegen"
        "termine_anlegen" -> "Mehrere Termine eintragen"
        "sheets_schreiben" -> "Tabelle ändern"
        "sheets_anhaengen" -> "Zeilen an Tabelle anhängen"
        "sheet_anlegen" -> "Neue Tabelle"
        "doc_anlegen" -> "Neues Dokument"
        "doc_anhaengen" -> "Dokument ergänzen"
        else -> tool
    }
    val statusText: String get() = when (status) {
        "offen" -> "wartet auf dich"
        "laeuft" -> "wird ausgeführt …"
        "ausgefuehrt" -> "ausgeführt"
        "abgelehnt" -> "abgelehnt"
        "fehler" -> "Fehler"
        "abgelaufen" -> "abgelaufen"
        else -> status
    }

    companion object {
        fun from(o: JSONObject) = Freigabe(
            o.optString("id"), o.optString("tool"), o.optString("text"), o.optString("status"),
            (o.optDouble("erstellt", 0.0) * 1000).toLong(), o.optString("ergebnis").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}

/** Freigabe-Schnittstelle von home-mcp – nur im Tailnet, nur mit dem App-Schlüssel (Art „freigabe“). */
object FreigabeApi {
    fun list(base: String, key: String): List<Freigabe> {
        val o = call("GET", "$base/app/freigaben", key, null)
        val a = o.getJSONArray("freigaben")
        return (0 until a.length()).map { Freigabe.from(a.getJSONObject(it)) }
    }

    fun decide(base: String, key: String, id: String, ja: Boolean): Freigabe =
        Freigabe.from(call("POST", "$base/app/freigaben/$id", key, JSONObject().put("entscheidung", if (ja) "ja" else "nein")))

    private fun call(method: String, url: String, key: String, body: JSONObject?): JSONObject {
        if (key.isBlank()) throw IOException("App-Schlüssel fehlt (Einstellungen)")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 90_000 // Ausführen kann dauern (Google)
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401) throw IOException("App-Schlüssel abgelehnt")
            if (code == 403) throw IOException("Nur im Tailnet erreichbar – ist Tailscale an?")
            if (code >= 400) throw IOException(runCatching { JSONObject(text).optString("error") }.getOrNull() ?: "HTTP $code")
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }
}
