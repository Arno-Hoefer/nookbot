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
    val erinnerungen = MutableStateFlow<List<Erinnerung>>(emptyList())
    val ablaeufe = MutableStateFlow<List<Ablauf>>(emptyList())
    val gedaechtnis = MutableStateFlow<List<Merk>>(emptyList())
    val datenbanken = MutableStateFlow<List<Datenbank>>(emptyList())
    val wissenGlobal = MutableStateFlow<List<WissenDatei>>(emptyList())
    val modelle = MutableStateFlow<List<String>>(emptyList())
    val claudeModelle = MutableStateFlow<List<String>>(emptyList())
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
            is Route.BotEdit -> loadModelle()
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
            "bots", "routinen", "erinnerungen", "ablaeufe", "gedaechtnis" -> io { loadStateNow() }
        }
    }

    fun onForeground(fg: Boolean) {
        foreground = fg
        AppState.foreground = fg
        if (fg) {
            if (stream?.isActive != true && settings.configured) connect()
            refreshFreigaben()
        }
    }

    private fun io(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        try { block() } catch (e: Exception) { toast.value = e.message ?: "Fehler" }
    }

    // ── Unterhaltungen ──

    fun openChat(sid: String) {
        msgs.value = emptyList()
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
        if (text.isBlank()) return
        busy.value = busy.value + sid
        io { api.post("/api/sessions/$sid/senden", JSONObject().put("text", text.trim())) }
    }

    fun deleteSession(sid: String) = io {
        api.delete("/api/sessions/$sid")
        withContext(Dispatchers.Main) { sitzungen.value = sitzungen.value.filterNot { it.id == sid } }
    }

    fun startRunde(frage: String, teilnehmer: List<String>, kapitaen: String) = io {
        val s = Sitzung.from(api.post("/api/runden", JSONObject().put("frage", frage).put("bots", JSONArray(teilnehmer)).put("kapitaen", kapitaen)))
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
    fun claudeStand(usd: Double?) = io {
        claude.value = api.post("/api/claude/stand", JSONObject().put("usd", usd ?: JSONObject.NULL))
        toast.value = if (usd == null) "Guthabenstand entfernt" else "Guthabenstand gespeichert"
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
