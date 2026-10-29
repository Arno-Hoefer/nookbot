package de.hoefer.nookbot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.hoefer.nookbot.AppState
import de.hoefer.nookbot.Benachrichtigung
import de.hoefer.nookbot.data.Ablauf
import de.hoefer.nookbot.data.Bot
import de.hoefer.nookbot.data.Merk
import de.hoefer.nookbot.data.Erinnerung
import de.hoefer.nookbot.ErinnerungAlarm
import de.hoefer.nookbot.data.Datenbank
import de.hoefer.nookbot.data.Guthaben
import de.hoefer.nookbot.data.Msg
import de.hoefer.nookbot.data.Routine
import de.hoefer.nookbot.data.Settings
import de.hoefer.nookbot.data.Sitzung
import de.hoefer.nookbot.data.WissenDatei
import de.hoefer.nookbot.data.freigabeIds
import de.hoefer.nookbot.net.Api
import de.hoefer.nookbot.net.Freigabe
import de.hoefer.nookbot.net.FreigabeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Wohin die App gerade zeigt. Zurück = vorheriger Eintrag im Stapel. */
sealed class Route {
    data object Home : Route()
    data class Chat(val sid: String) : Route()
    data class BotEdit(val id: String?) : Route()
    data object RundeNeu : Route()
    data object Freigaben : Route()
    data object Datenbanken : Route()
    data class DatenbankEdit(val index: Int) : Route()
    data object Routinen : Route()
    data object Ausloeser : Route()
    data class AusloeserEdit(val id: String?) : Route()
    data object Aktivitaet : Route()
    data object Arbeit : Route()
    data class RoutineEdit(val id: String?) : Route()
    data object Wissen : Route()
    data object Zugriffe : Route()
    data object Mehr : Route()
    data object Erinnerungen : Route()
    data object Archiv : Route()
    data object Ablaeufe : Route()
    data object Gedaechtnis : Route()
    data object Settings : Route()
}

class NookViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    private val api = Api({ settings.serverUrl }, { settings.chatToken })

    val stack = MutableStateFlow<List<Route>>(listOf(if (settings.configured) Route.Home else Route.Settings))
    val theme = MutableStateFlow(settings.theme)

    val online = MutableStateFlow(false)
    val fehler = MutableStateFlow<String?>(null)
    val bots = MutableStateFlow<List<Bot>>(emptyList())
    val sitzungen = MutableStateFlow<List<Sitzung>>(emptyList())
    val msgs = MutableStateFlow<List<Msg>>(emptyList())
    val busy = MutableStateFlow<Set<String>>(emptySet())
    val routinen = MutableStateFlow<List<Routine>>(emptyList())
    val ausloeser = MutableStateFlow<List<de.hoefer.nookbot.data.Ausloeser>>(emptyList())
    val aktivitaet = MutableStateFlow<List<de.hoefer.nookbot.data.AktEintrag>>(emptyList())
    val aktFilter = MutableStateFlow(Triple(1, "", false))  // Tage, Bot, nur Hintergrund
    val arbeit = MutableStateFlow<List<de.hoefer.nookbot.data.ArbeitDatei>>(emptyList())
    /** Regel-Katalog vom Pi: Werkzeug → Anzeigename, dazu die, die nie „frei“ sein dürfen, und das Tageslimit */
    val regelKatalog = MutableStateFlow<JSONObject?>(null)
    val erinnerungen = MutableStateFlow<List<Erinnerung>>(emptyList())
    val ablaeufe = MutableStateFlow<List<Ablauf>>(emptyList())
    val gedaechtnis = MutableStateFlow<List<Merk>>(emptyList())
    val datenbanken = MutableStateFlow<List<Datenbank>>(emptyList())
    val wissenGlobal = MutableStateFlow<List<WissenDatei>>(emptyList())
    val modelle = MutableStateFlow<List<String>>(emptyList())
    val claudeModelle = MutableStateFlow<List<String>>(emptyList())
    /** Pi-Stimmen und Aussprache-Wörterbuch (GET /api/stimmen) */
    val stimmen = MutableStateFlow<JSONObject?>(null)

    fun loadStimmen() = viewModelScope.launch(Dispatchers.IO) { runCatching { stimmen.value = api.get("/api/stimmen") } }

    fun ausspracheSpeichern(m: Map<String, String>) = io {
        api.put("/api/aussprache", JSONObject().put("aussprache", JSONObject(m as Map<*, *>)))
        stimmen.value = api.get("/api/stimmen")
        toast.value = "Aussprache gespeichert"
    }

    /** Ein Satz mit einer Pi-Stimme als WAV (für Sprachmodus und Hörprobe). */
    fun piSprechen(text: String, stimme: String): ByteArray = api.postBytes("/api/sprechen", JSONObject().put("text", text).put("stimme", stimme))

    /** Was der eine home-mcp-Schlüssel darf – daraus wählt man je Bot aus. */
    val rechteKatalog = MutableStateFlow<JSONObject?>(null)

    fun loadRechte(neu: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { rechteKatalog.value = api.get("/api/rechte" + if (neu) "?neu=1" else "") }
    }
    /** Claude auf dem Pi: eingerichtet, Ausgaben (mit Admin-Schlüssel), eingetragener Guthabenstand, „ca. übrig“. */
    val claude = MutableStateFlow<JSONObject?>(null)
    val guthaben = MutableStateFlow<Guthaben?>(null)
    val zugriffe = MutableStateFlow<JSONObject?>(null)
    val ntfy = MutableStateFlow(false)
    val freigaben = MutableStateFlow<List<Freigabe>>(emptyList())
    val freigabeFehler = MutableStateFlow<String?>(null)
    val freigabeBusy = MutableStateFlow<Set<String>>(emptySet())
    val toast = MutableStateFlow<String?>(null)

    private var seq = 0L
    private var stream: Job? = null
    private var poll: Job? = null
    private var foreground = true

    val current: String? get() = (stack.value.lastOrNull() as? Route.Chat)?.sid

    init {
        if (settings.configured) connect()
        viewModelScope.launch(Dispatchers.IO) { runCatching { de.hoefer.nookbot.Absturz.melden(getApplication()) } }
        refreshFreigaben()
    }

    // ── Navigation ──

    fun go(r: Route) {
        stack.value = stack.value + r
        when (r) {
            Route.Freigaben, Route.Home -> refreshFreigaben()
            Route.Datenbanken -> loadDatenbanken()
            Route.Wissen -> loadWissen()
            Route.Zugriffe -> loadZugriffe()
            is Route.BotEdit -> { loadModelle(); loadRechte(); loadStimmen(); io { val k = api.get("/api/regeln"); withContext(Dispatchers.Main) { regelKatalog.value = k } } }
            Route.Settings -> loadStimmen()
            Route.Aktivitaet -> loadAktivitaet()
            Route.Arbeit -> loadArbeit()
            else -> {}
        }
    }

    fun back(): Boolean {
        if (stack.value.size <= 1) return false
        val leaving = stack.value.last()
        stack.value = stack.value.dropLast(1)
        if (leaving is Route.Chat) msgs.value = emptyList()
        if (stack.value.last() is Route.Chat) loadSession((stack.value.last() as Route.Chat).sid)
        return true
    }

    fun home() { stack.value = listOf(Route.Home); refreshFreigaben() }

    fun say(s: String?) { toast.value = s }

    // ── Verbindung zum Adapter ──

    fun connect() {
        stream?.cancel()
        stream = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!foreground) { delay(1000); continue }
                try {
                    loadStateNow()
                    api.events(seq, onOpen = { online.value = true; fehler.value = null }) { ev -> viewModelScope.launch { apply(ev) } }
                } catch (e: Exception) {
                    online.value = false
                    fehler.value = e.message ?: "keine Verbindung"
                }
                delay(3000)
            }
        }
    }

    private suspend fun loadStateNow() {
        val st = api.get("/api/state")
        withContext(Dispatchers.Main) {
            seq = st.optLong("seq")
            bots.value = arr(st.optJSONArray("bots")).map { Bot.from(it) }
            sitzungen.value = arr(st.optJSONArray("sessions")).map { Sitzung.from(it) }.sortedByDescending { it.aktualisiert }
            busy.value = sitzungen.value.filter { it.laeuft }.map { it.id }.toSet()
            routinen.value = arr(st.optJSONArray("routinen")).map { Routine.from(it) }
            ausloeser.value = de.hoefer.nookbot.data.Ausloeser.list(st.optJSONArray("ausloeser"))
            erinnerungen.value = Erinnerung.list(st.optJSONArray("erinnerungen"))
            ablaeufe.value = Ablauf.list(st.optJSONArray("ablaeufe"))
            gedaechtnis.value = Merk.list(st.optJSONArray("gedaechtnis"))
            ErinnerungAlarm.planen(getApplication(), erinnerungen.value)
            ntfy.value = st.optBoolean("ntfy")
        }
        current?.let { loadSessionNow(it) }
        runCatching { guthaben.value = Guthaben.from(api.get("/api/guthaben")) }
        runCatching { claude.value = api.get("/api/claude") }
    }

    private fun arr(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }

    private fun apply(ev: JSONObject) {
        seq = maxOf(seq, ev.optLong("seq"))
        val sid = ev.optString("s")
        when (ev.optString("t")) {
            "reset" -> io { loadStateNow() }
            "msg" -> if (sid == current) {
                val m = Msg.from(ev.getJSONObject("m"))
                msgs.value = if (msgs.value.any { it.id == m.id }) msgs.value.map { if (it.id == m.id) m else it } else msgs.value + m
                if (m.role == "bot" && freigabeIds(m.text).isNotEmpty()) refreshFreigaben()
            }
            "del" -> if (sid == current) msgs.value = msgs.value.filterNot { it.id == ev.optString("id") }
            "busy" -> {
                busy.value = if (ev.optBoolean("v")) busy.value + sid else busy.value - sid
                if (!ev.optBoolean("v")) refreshFreigaben()
            }
            "session" -> {
                val s = Sitzung.from(ev.getJSONObject("s"))
                if (s.art != "intern") sitzungen.value = (listOf(s) + sitzungen.value.filterNot { it.id == s.id }).sortedByDescending { it.aktualisiert }
            }
            "session_del" -> sitzungen.value = sitzungen.value.filterNot { it.id == ev.optString("s") }
            "bots", "routinen", "erinnerungen", "ablaeufe", "gedaechtnis", "ausloeser" -> io { loadStateNow() }
            "aktivitaet" -> if (stack.value.lastOrNull() == Route.Aktivitaet) loadAktivitaet()
            "arbeit" -> if (stack.value.lastOrNull() == Route.Arbeit) loadArbeit()
            "anruf" -> ev.optJSONObject("a")?.let { de.hoefer.nookbot.Anruf.eingang(getApplication(), it) }
        }
    }

    fun onForeground(fg: Boolean) {
        foreground = fg
        AppState.foreground = fg
        if (fg) {
            if (stream?.isActive != true && settings.configured) connect()
            refreshFreigaben()
            // Gesundheitsdaten nebenbei auffrischen, wenn der letzte Abgleich über 6 Stunden her ist
            if (settings.configured && settings.gesundheitStand > 0 && System.currentTimeMillis() - settings.gesundheitStand > 6 * 3_600_000L) {
                viewModelScope.launch(Dispatchers.IO) { runCatching { de.hoefer.nookbot.Gesundheit.abgleichen(getApplication()) } }
            }
        }
    }

    private fun io(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        try { block() } catch (e: Exception) { toast.value = e.message ?: "Fehler" }
    }

    // ── Unterhaltungen ──

    fun openChat(sid: String) {
        msgs.value = emptyList()
        if (sid != current) anhaenge.value = emptyList()
        stack.value = stack.value.filterNot { it is Route.Chat } + Route.Chat(sid)
        loadSession(sid)
    }

    private fun loadSession(sid: String) = io { loadSessionNow(sid) }

    private suspend fun loadSessionNow(sid: String) {
        val s = api.get("/api/sessions/$sid")
        val list = arr(s.optJSONArray("msgs")).map { Msg.from(it) }
        withContext(Dispatchers.Main) {
            if (current == sid) msgs.value = list
            if (s.optBoolean("laeuft")) busy.value = busy.value + sid
        }
    }

    fun newChat(botId: String) = io {
        val s = api.post("/api/sessions", JSONObject().put("bot", botId))
        withContext(Dispatchers.Main) {
            val sz = Sitzung.from(s)
            sitzungen.value = listOf(sz) + sitzungen.value.filterNot { it.id == sz.id }
            openChat(sz.id)
        }
    }

    /** Ein Bot = eine Unterhaltung: Tipp auf den Bot öffnet genau die. */
    fun openBot(botId: String) {
        val sid = bot(botId)?.sitzung
        if (!sid.isNullOrBlank()) openChat(sid) else io { loadStateNow(); bot(botId)?.sitzung?.takeIf { it.isNotBlank() }?.let { withContext(Dispatchers.Main) { openChat(it) } } }
    }

    /** Neu beginnen: alte Unterhaltung wandert ins Archiv (Menü → Ältere Unterhaltungen). */
    fun neuBeginnen(botId: String) = io {
        val sid = api.post("/api/bots/$botId/neu").optString("sitzung")
        loadStateNow()
        withContext(Dispatchers.Main) { stack.value = listOf(Route.Home); openChat(sid) }
    }

    // ── Abläufe ──

    /** „Als Ablauf speichern“: der Bot fasst das Gespräch als Entwurf zusammen (Karte im Chat, erst nach OK ein Skill). */
    fun alsAblauf(sid: String, hinweis: String) = io {
        busy.value = busy.value + sid
        api.post("/api/sessions/$sid/ablauf", JSONObject().put("hinweis", hinweis.trim()))
    }

    fun ablaufAendern(id: String, d: JSONObject, msg: String = "Gespeichert") = io {
        api.put("/api/ablaeufe/$id", d)
        loadStateNow()
        toast.value = msg
    }

    fun ablaufLoeschen(id: String) = io { api.delete("/api/ablaeufe/$id"); loadStateNow() }

    fun ablaufJetzt(id: String) = io {
        val sid = api.post("/api/ablaeufe/$id/jetzt").optString("sitzung")
        withContext(Dispatchers.Main) { stack.value = listOf(Route.Home); openChat(sid) }
    }

    fun ablaufPlanen(id: String, zeit: String, tage: List<Int>, done: () -> Unit) = io {
        api.post("/api/ablaeufe/$id/planen", JSONObject().put("zeit", zeit).put("tage", JSONArray(tage)))
        loadStateNow()
        toast.value = "Routine angelegt"
        withContext(Dispatchers.Main) { done() }
    }

    // ── Gedächtnis ──

    fun merken(text: String, fuer: String) = io {
        api.post("/api/gedaechtnis", JSONObject().put("text", text.trim()).put("fuer", fuer))
        loadStateNow()
    }

    fun vergessen(id: String) = io {
        api.delete("/api/gedaechtnis/$id")
        loadStateNow()
        toast.value = "Vergessen"
    }

    fun gedaechtnisAendern(m: Merk, text: String) = io {
        val l = gedaechtnis.value.map { if (it.id == m.id) it.copy(text = text.trim()) else it }
        api.put("/api/gedaechtnis", JSONObject().put("eintraege", JSONArray().apply { l.forEach { put(it.json()) } }))
        loadStateNow()
    }

    /** Ergebnis eines Teilauftrags (interne Unterhaltung) als Text. */
    fun internText(sid: String, onText: (String) -> Unit) = io {
        val s = api.get("/api/sessions/$sid")
        val t = arr(s.optJSONArray("msgs")).filter { it.optString("role") == "bot" && it.optString("text").isNotBlank() }
            .joinToString("\n\n---\n\n") { it.optString("text") }
        withContext(Dispatchers.Main) { onText(t.ifBlank { "Noch keine Antwort." }) }
    }

    // ── Erinnerungen ──

    fun saveErinnerungen(list: List<Erinnerung>, done: () -> Unit = {}) = io {
        val r = api.put("/api/erinnerungen", JSONObject().put("erinnerungen", JSONArray().apply { list.forEach { put(it.json()) } }))
        val l = Erinnerung.list(r.optJSONArray("erinnerungen"))
        ErinnerungAlarm.planen(getApplication(), l)
        withContext(Dispatchers.Main) { erinnerungen.value = l; done() }
    }

    // ── Schriftgröße ──

    val textScale = MutableStateFlow(settings.textScale)
    fun setTextScale(f: Float) { settings.textScale = f; textScale.value = f }

    // ── Sprachnotiz: aufnehmen → lokal transkribieren → in den Chat (und optional als Wissen) ──

    val notizStatus = MutableStateFlow<String?>(null)
    val modellFortschritt = MutableStateFlow<Float?>(null)

    fun modellLaden(m: de.hoefer.nookbot.whisper.WhisperModel) = viewModelScope.launch {
        if (modellFortschritt.value != null) return@launch  // läuft schon – zwei Downloads in dieselbe Datei machen sie kaputt
        modellFortschritt.value = 0f
        runCatching { de.hoefer.nookbot.whisper.WhisperModels.download(getApplication(), m) { modellFortschritt.value = it } }
            .onSuccess { toast.value = "Sprachmodell geladen" }.onFailure { toast.value = it.message }
        modellFortschritt.value = null
    }

    fun transkribieren(wav: java.io.File, onText: (String) -> Unit) = viewModelScope.launch {
        val app = getApplication<Application>()
        val m = de.hoefer.nookbot.whisper.WhisperModels.installedPreferred(app)
        if (m == null) { toast.value = "Erst ein Sprachmodell laden (Einstellungen → Sprachnotizen)"; return@launch }
        notizStatus.value = "Transkribiere … 0 %"
        val r = withContext(Dispatchers.Default) {
            runCatching {
                de.hoefer.nookbot.whisper.Transcriber(de.hoefer.nookbot.whisper.WhisperModels.file(app, m))
                    .transcribe(wav, "de", { p -> notizStatus.value = "Transkribiere … $p %" }, { false })
            }
        }
        notizStatus.value = null
        wav.delete()  // Audio bleibt nicht liegen
        r.onSuccess { onText(it) }.onFailure { toast.value = it.message }
    }

    /** Notiz in den Chat schicken; optional zusätzlich als Wissensdatei für diesen Bot speichern. */
    fun notizSenden(text: String, dauer: String, alsWissen: Boolean) {
        val stamp = java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(java.util.Date())
        send("[Sprachnotiz $stamp · $dauer]\n\n${text.trim()}")
        val b = sitzungen.value.firstOrNull { it.id == current }?.bot ?: return
        if (alsWissen) {
            val datei = "notiz-" + java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.GERMANY).format(java.util.Date()) + ".md"
            uploadWissen(b, datei, "# Sprachnotiz vom $stamp ($dauer)\n\n${text.trim()}\n")
        }
    }

    fun send(text: String) {
        val sid = current ?: return
        val mit = anhaenge.value
        if (text.isBlank() && mit.isEmpty()) return
        val voll = (text.trim() + if (mit.isEmpty()) "" else "\n\n" + mit.joinToString("\n") { it.zeile }).trim()
        anhaenge.value = emptyList()
        busy.value = busy.value + sid
        io { api.post("/api/sessions/$sid/senden", JSONObject().put("text", voll)) }
    }

    // ── Anhänge (Fotos, Dateien) für die nächste Nachricht ──

    val anhaenge = MutableStateFlow<List<Anhang>>(emptyList())
    val anhangLaeuft = MutableStateFlow(false)

    fun anhaengen(uri: android.net.Uri, name: String? = null, danach: () -> Unit = {}) {
        val sid = current ?: return
        anhangLaeuft.value = true
        viewModelScope.launch {
            val r = runCatching {
                val d = Dateien.lesen(getApplication(), uri, name)
                val o = withContext(Dispatchers.IO) {
                    api.post("/api/sessions/$sid/anhang", JSONObject().put("name", d.name).put("b64", d.b64).put("ocr", d.ocr))
                }
                Anhang(o.optString("name"), o.optString("zeile"), o.optString("art") == "bild") to o.optString("hinweis")
            }
            anhangLaeuft.value = false
            danach()
            r.onSuccess { (a, hinweis) -> anhaenge.value = anhaenge.value + a; if (hinweis.isNotBlank()) toast.value = hinweis }
                .onFailure { toast.value = it.message ?: "Anhang fehlgeschlagen" }
        }
    }

    fun anhangWeg(a: Anhang) { anhaenge.value = anhaenge.value - a }

    /** Beliebige Datei als Wissen: Dokumente wandelt der Pi in Text um, bei Bildern zählt der auf dem Handy erkannte Text. */
    fun wissenDatei(scope: String, uri: android.net.Uri) = viewModelScope.launch {
        toast.value = "Datei wird gelesen …"
        runCatching {
            val d = Dateien.lesen(getApplication(), uri)
            withContext(Dispatchers.IO) { api.post("/api/wissen/$scope", JSONObject().put("name", d.name).put("b64", d.b64).put("ocr", d.ocr)) }
            d.name
        }.onSuccess { n -> io { loadStateNow(); loadWissen() }; toast.value = "„$n“ als Wissen gespeichert" }
            .onFailure { toast.value = it.message ?: "Fehlgeschlagen" }
    }

    fun deleteSession(sid: String) = io {
        api.delete("/api/sessions/$sid")
        withContext(Dispatchers.Main) { sitzungen.value = sitzungen.value.filterNot { it.id == sid } }
    }

    fun startRunde(frage: String, teilnehmer: List<String>, kapitaen: String, gespraech: Int = 0) = io {
        val s = Sitzung.from(api.post("/api/runden", JSONObject().put("frage", frage).put("bots", JSONArray(teilnehmer)).put("kapitaen", kapitaen)
            .put("gespraech", gespraech)))
        withContext(Dispatchers.Main) {
            sitzungen.value = listOf(s) + sitzungen.value.filterNot { it.id == s.id }
            stack.value = listOf(Route.Home)
            openChat(s.id)
        }
    }

    // ── Bots ──

    fun bot(id: String?): Bot? = bots.value.firstOrNull { it.id == id }

    fun saveBot(id: String?, data: JSONObject, done: () -> Unit) = io {
        val b = Bot.from(if (id == null) api.post("/api/bots", data) else api.put("/api/bots/$id", data))
        withContext(Dispatchers.Main) {
            bots.value = if (bots.value.any { it.id == b.id }) bots.value.map { if (it.id == b.id) b else it } else bots.value + b
            toast.value = if (id == null) "${b.name} ist da" else "Gespeichert – ${b.name} startet neu"
            done()
        }
    }

    fun deleteBot(id: String) = io {
        api.delete("/api/bots/$id")
        withContext(Dispatchers.Main) { bots.value = bots.value.filterNot { it.id == id }; home() }
    }

    fun loadModelle() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { api.get("/api/modelle") }.getOrNull()?.let { o ->
            val a = o.optJSONArray("modelle")
            val c = o.optJSONArray("claude")
            modelle.value = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
            claudeModelle.value = if (c == null) emptyList() else (0 until c.length()).map { c.getString(it) }
        }
    }

    // ── Wissen ──

    fun loadWissen() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { api.get("/api/wissen") }.getOrNull()?.let { w -> wissenGlobal.value = WissenDatei.list(w.optJSONArray("global")) }
    }

    /** Text-Datei oder PDF (b64) – PDFs zieht der Pi in Text um. */
    fun uploadWissen(scope: String, name: String, text: String, b64: String? = null) = io {
        if (b64 != null) toast.value = "PDF wird gelesen …"
        api.post("/api/wissen/$scope", JSONObject().put("name", name).apply { if (b64 != null) put("b64", b64) else put("text", text) })
        loadStateNow()
        loadWissen()
        toast.value = "„$name“ hinzugefügt"
    }

    fun deleteWissen(scope: String, name: String) = io {
        api.delete("/api/wissen/$scope/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20"))
        loadStateNow()
        loadWissen()
    }

    // ── Routinen ──

    fun saveRoutinen(list: List<Routine>, done: () -> Unit = {}) = io {
        val r = api.put("/api/routinen", JSONObject().put("routinen", JSONArray().apply { list.forEach { put(it.json()) } }))
        withContext(Dispatchers.Main) { routinen.value = arr(r.optJSONArray("routinen")).map { Routine.from(it) }; done() }
    }

    fun runRoutine(id: String) = io {
        val r = api.post("/api/routinen/$id/jetzt")
        withContext(Dispatchers.Main) { toast.value = "Routine läuft"; openChat(r.optString("session")) }
    }

    // ── Auslöser und Aktivität ──

    fun saveAusloeser(list: List<de.hoefer.nookbot.data.Ausloeser>, done: () -> Unit = {}) = io {
        val r = api.put("/api/ausloeser", JSONObject().put("ausloeser", JSONArray().apply { list.forEach { put(it.json()) } }))
        withContext(Dispatchers.Main) { ausloeser.value = de.hoefer.nookbot.data.Ausloeser.list(r.optJSONArray("ausloeser")); done() }
    }

    fun pruefeAusloeser(id: String) = io {
        val r = api.post("/api/ausloeser/$id/pruefen")
        withContext(Dispatchers.Main) {
            ausloeser.value = de.hoefer.nookbot.data.Ausloeser.list(r.optJSONArray("ausloeser"))
            toast.value = r.optString("ergebnis")
        }
    }

    // ── Anrufe ──

    val erreichbar = MutableStateFlow(settings.anrufErreichbar)

    fun setErreichbar(an: Boolean) {
        settings.anrufErreichbar = an
        erreichbar.value = an
        runCatching { de.hoefer.nookbot.AnrufDienst.erreichbar(getApplication(), an) }.onFailure { toast.value = it.message }
    }

    // ── Vorschläge der Bots (Ja → der Bot bekommt den Auftrag, Nein → kommt nicht wieder) ──
    fun vorschlagEntscheiden(id: String, ja: Boolean) = io {
        val o = api.post("/api/vorschlaege/$id", JSONObject().put("entscheidung", if (ja) "ja" else "nein"))
        toast.value = when {
            o.has("entfernt") -> if (ja) "${o.optInt("entfernt")} entfernt" else "Alles bleibt – kommt nicht wieder"
            ja -> "Angenommen – der Bot legt los"
            else -> "Abgelehnt – kommt nicht wieder"
        }
    }

    fun vorschlagEinstellungen(fertig: (Boolean, String) -> Unit) = io {
        val o = api.get("/api/vorschlaege").optJSONObject("einstellungen") ?: JSONObject()
        withContext(Dispatchers.Main) { fertig(o.optBoolean("aktiv", true), o.optString("zeit", "17:30")) }
    }

    fun vorschlagSetzen(aktiv: Boolean, zeit: String) = io { api.put("/api/vorschlaege", JSONObject().put("aktiv", aktiv).put("zeit", zeit)) }

    // ── Gedächtnis aufräumen (wöchentlich, als Vorschlag – das Ja in der Karte entfernt) ──
    fun aufraeumenEinstellungen(fertig: (Boolean, Int, String) -> Unit) = io {
        val o = api.get("/api/aufraeumen").optJSONObject("einstellungen") ?: JSONObject()
        withContext(Dispatchers.Main) { fertig(o.optBoolean("aktiv", true), o.optInt("tag", 7), o.optString("zeit", "18:00")) }
    }

    fun aufraeumenSetzen(aktiv: Boolean, tag: Int, zeit: String) = io {
        api.put("/api/aufraeumen", JSONObject().put("aktiv", aktiv).put("tag", tag).put("zeit", zeit))
    }

    fun aufraeumenJetzt() = io {
        api.post("/api/aufraeumen/jetzt")
        toast.value = "Sehe alle Bots durch – Vorschläge erscheinen im Chat des jeweiligen Bots"
    }

    fun durchsichtJetzt() = io {
        api.post("/api/vorschlaege/durchsicht")
        toast.value = "Durchsicht läuft – Vorschläge erscheinen im Chat des Kapitäns"
    }

    fun testAnruf(bot: String) = io { api.post("/api/anrufe/test", JSONObject().put("bot", bot)); withContext(Dispatchers.Main) { toast.value = "Gleich klingelt es …" } }

    fun loadArbeit() = io {
        val r = api.get("/api/arbeit")
        withContext(Dispatchers.Main) { arbeit.value = de.hoefer.nookbot.data.ArbeitDatei.list(r.optJSONArray("dateien")) }
    }

    fun arbeitLoeschen(name: String) = io {
        val r = api.delete("/api/arbeit/" + android.net.Uri.encode(name))
        withContext(Dispatchers.Main) { arbeit.value = de.hoefer.nookbot.data.ArbeitDatei.list(r.optJSONArray("dateien")) }
    }

    fun arbeitHoch(uri: android.net.Uri) = io {
        val ctx = getApplication<android.app.Application>()
        val name = Dateien.name(ctx, uri)
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        if (bytes.size > 50_000_000) error("Datei zu groß (max. 50 MB)")
        val r = api.post("/api/arbeit", JSONObject().put("name", name).put("b64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
        withContext(Dispatchers.Main) { arbeit.value = de.hoefer.nookbot.data.ArbeitDatei.list(r.optJSONArray("dateien")); toast.value = "$name liegt im Arbeitsordner" }
    }

    /** Bytes einer Datei aus dem Arbeitsordner (für Vorschaubilder im Chat), im Cache des Handys gehalten. */
    suspend fun arbeitBytes(name: String): ByteArray = withContext(Dispatchers.IO) {
        val f = java.io.File(java.io.File(getApplication<android.app.Application>().cacheDir, "arbeit").apply { mkdirs() }, name)
        if (!f.exists()) f.writeBytes(api.getBytes("/api/arbeit/" + android.net.Uri.encode(name)))
        f.readBytes()
    }

    /** Datei öffnen: herunterladen und an eine passende App geben (Bildbetrachter, Excel, PDF …). */
    fun arbeitOeffnen(name: String) = io {
        val ctx = getApplication<android.app.Application>()
        val f = java.io.File(java.io.File(ctx.cacheDir, "arbeit").apply { mkdirs() }, name)
        f.writeBytes(api.getBytes("/api/arbeit/" + android.net.Uri.encode(name)))
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".dateien", f)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        withContext(Dispatchers.Main) {
            runCatching { ctx.startActivity(android.content.Intent.createChooser(i, name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { toast.value = "Keine App zum Öffnen gefunden" }
        }
    }

    fun zuruecknehmen(fid: String) = io {
        api.post("/api/freigaben/$fid/rueckgaengig")
        withContext(Dispatchers.Main) { toast.value = "Zurückgenommen" }
        loadAktivitaet()
    }

    fun loadAktivitaet() = io {
        val (tage, bot, hinter) = aktFilter.value
        val r = api.get("/api/aktivitaet?tage=$tage&bot=${java.net.URLEncoder.encode(bot, "UTF-8")}" + if (hinter) "&hintergrund=1" else "")
        withContext(Dispatchers.Main) { aktivitaet.value = de.hoefer.nookbot.data.AktEintrag.list(r.optJSONArray("eintraege")) }
    }

    // ── Datenbanken ──

    fun loadDatenbanken() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { datenbanken.value = Datenbank.listFrom(api.get("/api/datenbanken")) }
    }

    fun saveDatenbanken(list: List<Datenbank>, done: () -> Unit = {}) = io {
        datenbanken.value = Datenbank.listFrom(api.put("/api/datenbanken", Datenbank.listJson(list)))
        withContext(Dispatchers.Main) { toast.value = "Gespeichert – Bots mit Zugriffen kennen jetzt die Abfragen"; done() }
    }

    fun loadSchema(db: String, onText: (String) -> Unit) = io {
        val t = api.get("/api/schema?db=" + java.net.URLEncoder.encode(db, "UTF-8")).optString("text")
        withContext(Dispatchers.Main) { onText(t) }
    }

    /** Guthabenstand aus der Claude Console eintragen – ab dann rechnet der Pi „ca. übrig“ mit den Ausgaben seit heute. */
    /** Startbetrag (Guthaben an einem Tag) und monatliche Aufladung – daraus rechnet der Pi „ca. übrig“. */
    fun claudeStand(usd: Double?, datum: String, aufUsd: Double?, aufTag: Int?) = io {
        claude.value = api.post("/api/claude/stand", JSONObject().put("usd", usd ?: JSONObject.NULL).put("datum", datum)
            .put("aufladung_usd", aufUsd ?: 0).put("aufladung_tag", aufTag ?: 0))
        toast.value = "Gespeichert"
    }

    /** Korrektur: „so viel steht gerade in der Console“ – der Pi zählt ab jetzt (nicht ab Tagesbeginn), Aufladung bleibt. */
    fun claudeKorrektur(usd: Double) = io {
        claude.value = api.post("/api/claude/stand", JSONObject().put("usd", usd).put("jetzt", true))
        toast.value = "Stand korrigiert"
    }

    // ── Gesundheit (Health Connect → Pi) ──

    /** verfuegbar, erlaubt (Anzahl Datenarten), hintergrund, letzter Abgleich, Stand auf dem Pi */
    val gesundheit = MutableStateFlow<JSONObject?>(null)

    fun gesundheitLaden() = viewModelScope.launch {
        val app = getApplication<Application>()
        val o = JSONObject().put("verfuegbar", de.hoefer.nookbot.Gesundheit.verfuegbar(app))
        if (o.getBoolean("verfuegbar")) runCatching {
            val darf = de.hoefer.nookbot.Gesundheit.erlaubt(app)
            o.put("erlaubt", darf.count { it.startsWith("android.permission.health.READ_") && !it.endsWith("BACKGROUND") && !it.endsWith("HISTORY") })
            o.put("hintergrund", darf.any { it.endsWith("READ_HEALTH_DATA_IN_BACKGROUND") })
            // Erlaubnis evtl. in den Android-Einstellungen erteilt → Hintergrund-Abgleich hier (nach)planen bzw. abbestellen
            if (o.getBoolean("hintergrund")) de.hoefer.nookbot.Gesundheit.planen(app) else de.hoefer.nookbot.Gesundheit.stoppen(app)
        }
        o.put("stand", settings.gesundheitStand)
        withContext(Dispatchers.IO) { runCatching { o.put("pi", api.get("/api/gesundheit/info")) } }
        gesundheit.value = o
    }

    fun gesundheitErlaubt(darf: Set<String>) {
        val app = getApplication<Application>()
        if (darf.any { it.endsWith("READ_HEALTH_DATA_IN_BACKGROUND") }) de.hoefer.nookbot.Gesundheit.planen(app)
        if (darf.any { it.startsWith("android.permission.health.READ_") }) gesundheitAbgleichen() else gesundheitLaden()
    }

    fun gesundheitAbgleichen() = viewModelScope.launch {
        toast.value = "Gesundheitsdaten werden gelesen …"
        val r = runCatching { withContext(Dispatchers.IO) { de.hoefer.nookbot.Gesundheit.abgleichen(getApplication()) } }
        toast.value = r.getOrElse { "Abgleich fehlgeschlagen: ${it.message}" }
        gesundheitLaden()
    }

    // ── Zugriffe, Guthaben, Benachrichtigung ──

    fun loadZugriffe() = io { zugriffe.value = api.get("/api/zugriffe") }

    fun testPush() = io {
        Benachrichtigung.pruefen(getApplication())  // Stand merken (erster Lauf zeigt nichts Altes)
        api.post("/api/test-push")
        Benachrichtigung.pruefen(getApplication())
        toast.value = "Test-Benachrichtigung geschickt"
    }

    // ── Suche über alle Unterhaltungen ──

    val treffer = MutableStateFlow<List<JSONObject>>(emptyList())
    private var suchJob: Job? = null

    fun suche(q: String) {
        suchJob?.cancel()
        if (q.trim().length < 2) { treffer.value = emptyList(); return }
        suchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            runCatching { api.get("/api/suche?q=" + java.net.URLEncoder.encode(q.trim(), "UTF-8")).optJSONArray("treffer") }
                .getOrNull()?.let { a -> treffer.value = arr(a) }
        }
    }

    // ── Freigaben (direkt bei home-mcp) ──

    fun refreshFreigaben() {
        if (settings.appKey.isBlank()) return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { FreigabeApi.list(settings.mcpUrl, settings.appKey) } }
            r.onSuccess { freigaben.value = it; freigabeFehler.value = null }.onFailure { freigabeFehler.value = it.message }
            poll?.cancel()
            if (freigaben.value.any { it.offen || it.status == "laeuft" } && foreground) {
                poll = viewModelScope.launch { delay(20_000); refreshFreigaben() }
            }
        }
    }

    /** „Ändern“: Vorschlag ablehnen und dem Bot sagen, was anders sein soll – er legt eine neue Freigabe an. */
    fun aendern(f: Freigabe, wunsch: String) {
        if (f.id in freigabeBusy.value || wunsch.isBlank()) return
        freigabeBusy.value = freigabeBusy.value + f.id
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { FreigabeApi.decide(settings.mcpUrl, settings.appKey, f.id, false) } }
            freigabeBusy.value = freigabeBusy.value - f.id
            r.onSuccess {
                send("Freigabe ${f.id} bitte ändern (die alte habe ich abgelehnt): ${wunsch.trim()}\nLeg danach eine neue Freigabe an.")
            }.onFailure { toast.value = it.message ?: "Fehler" }
            refreshFreigaben()
        }
    }

    fun decide(f: Freigabe, ja: Boolean) {
        if (f.id in freigabeBusy.value) return
        freigabeBusy.value = freigabeBusy.value + f.id
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { FreigabeApi.decide(settings.mcpUrl, settings.appKey, f.id, ja) } }
            freigabeBusy.value = freigabeBusy.value - f.id
            r.onSuccess { n ->
                freigaben.value = freigaben.value.map { if (it.id == n.id) n else it }
                toast.value = if (ja) (if (n.status == "ausgefuehrt") "Ausgeführt ✓" else "${n.statusText}: ${n.ergebnis ?: ""}") else "Abgelehnt"
                if (current != null && msgs.value.any { n.id in it.text }) {
                    send(if (ja) "Freigabe ${n.id}: erteilt – Status ${n.statusText}." else "Freigabe ${n.id}: abgelehnt.")
                }
            }.onFailure { toast.value = it.message ?: "Fehler" }
            refreshFreigaben()
        }
    }

    // ── Einstellungen ──

    fun setTheme(t: String) { settings.theme = t; theme.value = t }

    val listen = MutableStateFlow(settings.listen)

    fun setListen(on: Boolean) {
        settings.listen = on
        listen.value = on
        if (on) Benachrichtigung.planen(getApplication()) else Benachrichtigung.stoppen(getApplication())
    }

    fun saveSettings(server: String, token: String?, mcp: String, appKey: String?) {
        settings.serverUrl = server
        settings.mcpUrl = mcp
        token?.let { settings.chatToken = it }
        appKey?.let { settings.appKey = it }
        toast.value = "Gespeichert"
        Benachrichtigung.planen(getApplication())
        connect()
        refreshFreigaben()
    }

    fun test(onResult: (String) -> Unit) = viewModelScope.launch {
        val a = withContext(Dispatchers.IO) {
            runCatching { api.get("/api/state") }.fold({ "Pi ✓ verbunden (Adapter ${it.optString("version")}, ${it.optJSONArray("bots")?.length() ?: 0} Bots)" },
                { "Pi ✗ ${it.message} – Tailscale an?" })
        }
        val f = withContext(Dispatchers.IO) {
            runCatching { FreigabeApi.list(settings.mcpUrl, settings.appKey) }
                .fold({ "Freigaben ✓ (${it.count { x -> x.offen }} offen)" }, { "Freigaben ✗ ${it.message}" })
        }
        onResult("$a\n$f")
    }

    override fun onCleared() {
        stream?.cancel()
        super.onCleared()
    }
}
