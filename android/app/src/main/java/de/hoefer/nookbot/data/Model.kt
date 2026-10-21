package de.hoefer.nookbot.data

import org.json.JSONArray
import org.json.JSONObject

/** Ein Bot (ein PicoClaw-Prozess auf dem Pi). */
data class Bot(
    val id: String, val name: String, val rolle: String, val farbe: Long, val form: String, val kapitaen: Boolean,
    val zugriffe: Boolean, val persoenlichkeit: String, val fokus: String, val modell: String, val laeuft: Boolean,
    val wissen: List<WissenDatei>, val sitzung: String = "", val merken: Boolean = true,
    /** Zugriffe im Einzelnen (vom Adapter): mail/kalender/drive = Konten, slack…, gitlab, db, statistik, pakete; „alle“ = alles */
    val rechte: JSONObject = JSONObject(),
) {
    companion object {
        fun from(o: JSONObject) = Bot(
            o.optString("id"), o.optString("name"), o.optString("rolle"), parseColor(o.optString("farbe")), o.optString("form", "kreis"),
            o.optBoolean("kapitaen"), o.optBoolean("zugriffe"), o.optString("persoenlichkeit"), o.optString("fokus"),
            o.optString("modell"), o.optBoolean("laeuft"), WissenDatei.list(o.optJSONArray("wissen")), o.optString("sitzung"),
            o.optBoolean("merken", true), o.optJSONObject("rechte") ?: JSONObject(),
        )
    }
}

data class WissenDatei(val name: String, val groesse: Long) {
    companion object {
        fun list(a: JSONArray?): List<WissenDatei> =
            if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }.map { WissenDatei(it.optString("name"), it.optLong("groesse")) }
    }
}

fun parseColor(s: String): Long = runCatching { 0xFF000000 or s.removePrefix("#").toLong(16) }.getOrDefault(0xFFFF3B4E)

fun colorHex(c: Long): String = "#%06X".format(c and 0xFFFFFF)

/** Eine Unterhaltung (Chat mit einem Bot, Runde, Routine oder Aufträge vom Kapitän). */
data class Sitzung(
    val id: String, val bot: String, val art: String, val titel: String, val aktualisiert: Long, val letzte: String,
    val laeuft: Boolean, val ungelesen: Int, val teilnehmer: List<String> = emptyList(),
) {
    companion object {
        fun from(o: JSONObject) = Sitzung(
            o.optString("id"), o.optString("bot"), o.optString("art", "chat"), o.optString("titel"),
            (o.optDouble("aktualisiert", 0.0) * 1000).toLong(), o.optString("letzte"), o.optBoolean("laeuft"), o.optInt("ungelesen"),
            o.optJSONArray("teilnehmer")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        )
    }
}

/** Erinnerung: nur ein Hinweis zur Uhrzeit (das Handy weckt per Alarm). wann = "YYYY-MM-DDTHH:MM". */
data class Erinnerung(val id: String, val text: String, val wann: String, val wiederholung: String, val bot: String, val erledigt: Boolean) {
    fun json(): JSONObject = JSONObject().put("id", id).put("text", text).put("wann", wann).put("wiederholung", wiederholung)
        .put("bot", bot).put("erledigt", erledigt)

    /** Zeitpunkt in ms (lokale Zeit), oder null bei kaputtem Format */
    val millis: Long? get() = runCatching { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.GERMANY).parse(wann)!!.time }.getOrNull()

    val wiederholungText: String get() = when (wiederholung) {
        "taeglich" -> "täglich"; "werktags" -> "werktags"; "woechentlich" -> "wöchentlich"; "monatlich" -> "monatlich"; else -> ""
    }

    companion object {
        fun from(o: JSONObject) = Erinnerung(o.optString("id"), o.optString("text"), o.optString("wann"), o.optString("wiederholung"),
            o.optString("bot"), o.optBoolean("erledigt"))

        fun list(a: JSONArray?): List<Erinnerung> = if (a == null) emptyList() else (0 until a.length()).map { from(a.getJSONObject(it)) }
    }
}

/**
 * Eine Nachricht. role: user, bot, thought (Denkschritte), tool (Werkzeuge, Namen kommagetrennt), error,
 * info (Karte vom Adapter – art: gedaechtnis | ablauf | auftraege).
 */
data class Msg(
    val id: String, val role: String, val text: String, val ts: Long, val bot: String?, val zusammenfassung: Boolean = false,
    val art: String = "", val ref: String = "", val status: String = "", val titel: String = "", val zurueck: Boolean = false,
    val ziel: String = "", val teile: List<Teilauftrag> = emptyList(),
) {
    companion object {
        fun from(o: JSONObject) = Msg(
            o.optString("id"), o.optString("role"), o.optString("text"), (o.optDouble("ts", 0.0) * 1000).toLong(),
            o.optString("bot").takeIf { it.isNotBlank() }, o.optBoolean("zusammenfassung"),
            o.optString("art"), o.optString("ref"), o.optString("status"), o.optString("titel"), o.optBoolean("zurueck"),
            o.optString("ziel"), o.optJSONArray("teile")?.let { a -> (0 until a.length()).map { Teilauftrag.from(a.getJSONObject(it)) } } ?: emptyList(),
        )
    }
}

/** Ein Teil von „Aufträge verteilen“: welcher Bot, was, Stand, in welcher (internen) Unterhaltung. */
data class Teilauftrag(val bot: String, val name: String, val auftrag: String, val status: String, val sitzung: String) {
    companion object {
        fun from(o: JSONObject) = Teilauftrag(o.optString("bot"), o.optString("name"), o.optString("auftrag"), o.optString("status"), o.optString("sitzung"))
    }
}

/** Ein Ablauf: aus einem Gespräch gelernt, nach dem OK ein Skill für die Bots. fuer = "global" oder Bot-id. */
data class Ablauf(val id: String, val name: String, val beschreibung: String, val schritte: String, val fuer: String, val status: String,
                  val von: String, val erstellt: Long) {
    val entwurf: Boolean get() = status != "aktiv"

    companion object {
        fun from(o: JSONObject) = Ablauf(o.optString("id"), o.optString("name"), o.optString("beschreibung"), o.optString("schritte"),
            o.optString("fuer", "global"), o.optString("status"), o.optString("von"), (o.optDouble("erstellt", 0.0) * 1000).toLong())

        fun list(a: JSONArray?): List<Ablauf> = if (a == null) emptyList() else (0 until a.length()).map { from(a.getJSONObject(it)) }
    }
}

/** Ein Gedächtnis-Eintrag. fuer = "global" (alle Bots) oder Bot-id; von = Bot-id oder "app". */
data class Merk(val id: String, val text: String, val fuer: String, val von: String, val quelle: String, val ts: Long) {
    fun json(): JSONObject = JSONObject().put("id", id).put("text", text).put("fuer", fuer).put("von", von).put("quelle", quelle).put("ts", ts / 1000.0)

    companion object {
        fun from(o: JSONObject) = Merk(o.optString("id"), o.optString("text"), o.optString("fuer", "global"), o.optString("von"),
            o.optString("quelle"), (o.optDouble("ts", 0.0) * 1000).toLong())

        fun list(a: JSONArray?): List<Merk> = if (a == null) emptyList() else (0 until a.length()).map { from(a.getJSONObject(it)) }
    }
}

data class Routine(
    val id: String, val name: String, val bot: String, val auftrag: String, val zeit: String, val tage: List<Int>,
    val aktiv: Boolean, val letzterLauf: Long, val datum: String = "", val sitzung: String = "",
) {
    /** einmalig an einem Datum (statt wiederkehrend) */
    val einmalig: Boolean get() = datum.isNotBlank()

    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("bot", bot).put("auftrag", auftrag).put("zeit", zeit)
        .put("tage", JSONArray(tage)).put("aktiv", aktiv).put("letzter_lauf", letzterLauf / 1000.0).put("datum", datum).put("sitzung", sitzung)

    companion object {
        fun from(o: JSONObject) = Routine(
            o.optString("id"), o.optString("name"), o.optString("bot"), o.optString("auftrag"), o.optString("zeit", "08:00"),
            o.optJSONArray("tage")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: listOf(1, 2, 3, 4, 5),
            o.optBoolean("aktiv", true), (o.optDouble("letzter_lauf", 0.0) * 1000).toLong(), o.optString("datum"), o.optString("sitzung"),
        )
    }
}

/** Datenbank-Bibliothek: lebt in der App, wird zum Pi gespiegelt (Skill „datenbanken“ für Bots mit Zugriffen). */
data class Abfrage(val name: String, val zweck: String, val parameter: String, val sql: String) {
    /** parameter als „von=current_date - 7, land=DE“ */
    fun json(): JSONObject = JSONObject().put("name", name).put("zweck", zweck).put("sql", sql).put("parameter", JSONArray().apply {
        parameter.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { p ->
            put(JSONObject().put("name", p.substringBefore("=").trim().removePrefix(":")).put("standard", p.substringAfter("=", "").trim()))
        }
    })

    companion object {
        fun from(o: JSONObject) = Abfrage(
            o.optString("name"), o.optString("zweck"),
            o.optJSONArray("parameter")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.joinToString(", ") { p ->
                    p.optString("name") + (p.optString("standard").takeIf { it.isNotBlank() }?.let { "=$it" } ?: "")
                }
            } ?: "",
            o.optString("sql"),
        )
    }
}

data class Datenbank(val titel: String, val name: String, val beschreibung: String, val notizen: String, val abfragen: List<Abfrage>) {
    fun json(): JSONObject = JSONObject().put("titel", titel).put("name", name).put("beschreibung", beschreibung).put("notizen", notizen)
        .put("abfragen", JSONArray().apply { abfragen.forEach { put(it.json()) } })

    companion object {
        fun from(o: JSONObject) = Datenbank(
            o.optString("titel"), o.optString("name"), o.optString("beschreibung"), o.optString("notizen"),
            o.optJSONArray("abfragen")?.let { a -> (0 until a.length()).map { Abfrage.from(a.getJSONObject(it)) } } ?: emptyList(),
        )

        fun listFrom(o: JSONObject): List<Datenbank> =
            o.optJSONArray("datenbanken")?.let { a -> (0 until a.length()).map { from(a.getJSONObject(it)) } } ?: emptyList()

        fun listJson(l: List<Datenbank>): JSONObject = JSONObject().put("datenbanken", JSONArray().apply { l.forEach { put(it.json()) } })

        /**
         * Import: eigene Exportdatei ({"datenbanken": …}) oder eine Echo-Lage-Vorlage (vorlage.json):
         * deren SQL-Quellen werden zu Abfragen, gruppiert nach Verbindung.
         */
        fun import(text: String, dbName: String = "heroku"): List<Datenbank> {
            val o = JSONObject(text)
            if (o.has("datenbanken")) return listFrom(o)
            val q = o.optJSONArray("quellen") ?: throw IllegalArgumentException("Weder NookBot-Export noch Echo-Vorlage")
            val byConn = linkedMapOf<String, MutableList<Abfrage>>()
            for (i in 0 until q.length()) {
                val s = q.getJSONObject(i)
                if (s.optString("art") != "sql") continue
                val b = s.optString("beschreibung")
                byConn.getOrPut(s.optString("verbindung", "Datenbank")) { mutableListOf() } += Abfrage(
                    name = b.substringBefore(" · ").ifBlank { s.optString("id") } + (b.substringAfter(" · ", "").takeIf { it.isNotBlank() }?.let { " – " + it.substringBefore(",").take(60) } ?: ""),
                    zweck = b, parameter = "", sql = s.optString("sql"),
                )
            }
            if (byConn.isEmpty()) throw IllegalArgumentException("Keine SQL-Quellen in der Vorlage")
            return byConn.map { (conn, qs) ->
                Datenbank(conn, dbName, "Importiert aus der Echo-Vorlage „${o.optString("name")}“.", "", qs)
            }
        }
    }
}

/** Was ein Werkzeug tut – für die kleine Zeile im Chat. MCP-Werkzeuge heißen z. B. „mcp_home_termine“. */
private val TOOL_TEXT = linkedMapOf(
    "termin_anlegen" to "Termin zur Freigabe vorgelegt", "termin_gaeste" to "Einladung zur Freigabe vorgelegt",
    "entwurf_anlegen" to "Mail-Entwurf zur Freigabe vorgelegt", "freigabe_status" to "Freigabe nachgesehen",
    "kalender_liste" to "Kalender aufgelistet", "termine" to "Kalender gelesen", "mails_suchen" to "Mails durchsucht",
    "mail_lesen" to "Mail gelesen", "thread_lesen" to "Mail-Verlauf gelesen", "konten" to "Zugänge nachgesehen",
    "slack_fuer_mich" to "Slack: was dich betrifft", "slack_suche" to "Slack durchsucht", "slack_verlauf" to "Slack-Kanal gelesen",
    "slack_thread" to "Slack-Thread gelesen", "slack_kanaele" to "Slack-Kanäle aufgelistet",
    "db_abfrage" to "Datenbank abgefragt", "db_schema" to "Datenbank-Schema angesehen",
    "gitlab_" to "GitLab gelesen", "web_search" to "Im Web gesucht", "web_fetch" to "Webseite gelesen",
    "bot_fragen" to "Anderen Bot gefragt", "bots_liste" to "Team angesehen",
    "erinnerung_anlegen" to "Erinnerung gestellt", "erinnerungen_liste" to "Erinnerungen angesehen", "erinnerung_loeschen" to "Erinnerung gelöscht",
    "bot_anlegen" to "Neuen Bot angelegt", "bot_aendern" to "Bot angepasst",
    "routine_anlegen" to "Routine angelegt", "routinen_liste" to "Routinen angesehen", "routine_loeschen" to "Routine gelöscht",
    "read_file" to "Wissen/Notiz gelesen", "write_file" to "Notiz geschrieben", "edit_file" to "Notiz geändert", "append_file" to "Notiz ergänzt",
    "seite_lesen" to "Seite gelesen", "paket_hinzufuegen" to "Paket eingetragen", "pakete" to "Pakete angesehen",
    "merken" to "Etwas gemerkt", "vergessen" to "Etwas vergessen", "ablauf_vorschlagen" to "Ablauf vorgeschlagen",
    "auftraege_verteilen" to "Aufträge verteilt", "drive_suche" to "Drive durchsucht", "drive_lesen" to "Datei aus Drive gelesen",
    "sheets_lesen" to "Tabelle gelesen", "sheets_schreiben" to "Tabelle ändern – zur Freigabe", "sheets_anhaengen" to "Zeilen anhängen – zur Freigabe",
    "sheet_anlegen" to "Neue Tabelle – zur Freigabe", "doc_anlegen" to "Neues Dokument – zur Freigabe", "doc_anhaengen" to "Dokument ergänzen – zur Freigabe",
    "statistik" to "Besucherstatistik gelesen",
    "list_dir" to "Ordner angesehen", "spawn" to "Teilaufgabe gestartet", "subagent" to "Teilaufgabe gestartet", "cron" to "Zeitplan bearbeitet",
)

fun toolLabel(name: String): String {
    val n = name.trim().removePrefix("mcp_").removePrefix("home_").removePrefix("nookbot_")
    return TOOL_TEXT.entries.firstOrNull { n == it.key || n.endsWith("_" + it.key) || (it.key.endsWith("_") && n.contains(it.key)) }?.value ?: n
}

fun toolLine(text: String): String = text.split(",").map { toolLabel(it) }.filter { it.isNotBlank() }.distinct().joinToString(" · ")

private val FREIGABE_ID = Regex("""\bF-[0-9a-f]{6}\b""")

/** Freigabe-Ids, die in einem Text vorkommen (home-mcp antwortet mit „Wartet auf Freigabe F-ab12cd“). */
fun freigabeIds(text: String): List<String> = FREIGABE_ID.findAll(text).map { it.value }.distinct().toList()

/** Ollama-Guthaben (GET https://ollama.com/api/balance über den Adapter). */
data class Guthaben(val rest: Double, val gesamt: Double, val bis: String) {
    val genutztProzent: Int get() = if (gesamt <= 0) 0 else (100 * (1 - rest / gesamt)).toInt().coerceIn(0, 100)

    companion object {
        fun from(o: JSONObject): Guthaben? {
            val inc = o.optJSONObject("included") ?: return null
            val bought = o.optJSONObject("purchased")?.optDouble("balance_usd", 0.0) ?: 0.0
            return Guthaben(inc.optDouble("balance_usd", 0.0) + bought, inc.optDouble("allowance_usd", 0.0),
                inc.optJSONObject("period")?.optString("until")?.take(10) ?: "")
        }
    }
}
