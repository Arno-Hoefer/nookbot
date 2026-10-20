package de.hoefer.nookbot.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hoefer.nookbot.data.Msg
import de.hoefer.nookbot.data.Sitzung
import de.hoefer.nookbot.data.freigabeIds
import de.hoefer.nookbot.data.toolLine
import de.hoefer.nookbot.net.Freigabe

// ── Start ─────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: NookViewModel) {
    val bots by vm.bots.collectAsState()
    val sitz by vm.sitzungen.collectAsState()
    val fr by vm.freigaben.collectAsState()
    val busy by vm.busy.collectAsState()
    val online by vm.online.collectAsState()
    val fehler by vm.fehler.collectAsState()
    val guthaben by vm.guthaben.collectAsState()
    var plus by remember { mutableStateOf(false) }
    var loeschen by remember { mutableStateOf<Sitzung?>(null) }
    var suchen by rememberSaveable { mutableStateOf(false) }
    var q by rememberSaveable { mutableStateOf("") }
    val treffer by vm.treffer.collectAsState()
    val kap = bots.firstOrNull { it.kapitaen }
    val offen = fr.count { it.offen }
    val kapSitz = sitz.firstOrNull { it.id == kap?.sitzung }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.Default.Menu, "Menü") { vm.go(Route.Mehr) }
            Spacer(Modifier.weight(1f))
            Text("NookBot", color = P.fg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            RoundIcon(Icons.Default.Search, "Suchen") { suchen = !suchen; if (!suchen) { q = ""; vm.suche("") } }
            Spacer(Modifier.width(10.dp))
            Box {
                RoundIcon(Icons.Default.Add, "Neu") { plus = true }
                DropdownMenu(plus, { plus = false }, containerColor = P.card) {
                    DropdownMenuItem({ Text("Neuer Bot", color = P.fg) }, { plus = false; vm.go(Route.BotEdit(null)) })
                    if (bots.size >= 2) DropdownMenuItem({ Text("Neue Runde", color = P.fg) }, { plus = false; vm.go(Route.RundeNeu) })
                    kap?.let { k -> DropdownMenuItem({ Text("Bot von ${k.name} anlegen lassen", color = P.fg) }, { plus = false; vm.openBot(k.id) }) }
                }
            }
        }

        if (suchen) {
            Column(Modifier.weight(1f)) {
                Field("In allen Unterhaltungen suchen", q, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) { q = it; vm.suche(it) }
                LazyColumn(Modifier.weight(1f)) {
                    if (q.length >= 2 && treffer.isEmpty()) item { Hint("Nichts gefunden.", Modifier.padding(20.dp)) }
                    items(treffer) { t ->
                        Row(Modifier.fillMaxWidth().clickable { suchen = false; vm.openChat(t.optString("s")) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                            BotFigure(vm.bot(t.optString("bot")), 36.dp)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(vm.bot(t.optString("bot"))?.name ?: t.optString("titel"), color = P.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(t.optString("auszug"), color = P.fg2, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            return@Column
        }

        // Kapitän groß
        Column(Modifier.fillMaxWidth().clickable { kap?.let { vm.openBot(it.id) } }.padding(top = 14.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            BotFigure(kap, 112.dp)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(kap?.name ?: "…", color = P.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("  ${kap?.rolle ?: ""}", color = P.mut, fontSize = 13.sp)
                if ((kapSitz?.ungelesen ?: 0) > 0) Box(Modifier.padding(start = 8.dp).size(9.dp).clip(CircleShape).background(A.blue))
            }
            Text(when {
                kapSitz?.id in busy -> "arbeitet …"
                !kapSitz?.letzte.isNullOrBlank() -> vorschau(kapSitz!!.letzte)
                else -> "Tippen zum Schreiben"
            }, color = if (kapSitz?.id in busy) A.green else P.mut, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp, start = 30.dp, end = 30.dp))
        }

        if (offen > 0) {
            Card(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), border = A.amber.copy(alpha = 0.5f), onClick = { vm.go(Route.Freigaben) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge("FREIGABE", A.amber)
                    Text(if (offen == 1) "1 Aktion wartet auf dich" else "$offen Aktionen warten auf dich", color = P.fg, fontSize = 14.sp,
                        modifier = Modifier.padding(start = 10.dp))
                }
            }
        }

        // Liste: je Bot eine Zeile (seine Unterhaltung) + Runden – zuletzt aktiv zuerst
        data class Eintrag(val key: String, val sitzung: Sitzung?, val bot: de.hoefer.nookbot.data.Bot?, val runde: Boolean, val zeit: Long)
        val eintraege = (bots.filter { !it.kapitaen }.map { b -> sitz.firstOrNull { it.id == b.sitzung }.let { s -> Eintrag("b-" + b.id, s, b, false, s?.aktualisiert ?: 0) } } +
            sitz.filter { it.art == "runde" }.map { Eintrag("r-" + it.id, it, vm.bot(it.bot), true, it.aktualisiert) }).sortedByDescending { it.zeit }
        LazyColumn(Modifier.weight(1f)) {
            if (eintraege.isEmpty()) item {
                Hint("Noch keine weiteren Bots. Beschreib ${kap?.name ?: "dem Kapitän"}, was ein neuer Bot können soll – er legt ihn an. Oder „+“ → Neuer Bot.",
                    Modifier.padding(20.dp))
            }
            items(eintraege, key = { it.key }) { e ->
                val s = e.sitzung
                Row(Modifier.fillMaxWidth().combinedClickable(
                    onClick = { if (e.runde) s?.let { vm.openChat(it.id) } else e.bot?.let { vm.openBot(it.id) } },
                    onLongClick = { if (e.runde) loeschen = s else e.bot?.let { vm.go(Route.BotEdit(it.id)) } })
                    .padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (e.runde) Box(Modifier.size(46.dp)) {
                        Box(Modifier.align(Alignment.TopStart)) { BotFigure(e.bot, 30.dp) }
                        Box(Modifier.align(Alignment.BottomEnd)) { BotFigure(vm.bot(s?.teilnehmer?.firstOrNull { it != s.bot } ?: ""), 28.dp) }
                    } else BotFigure(e.bot, 46.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (e.runde) (s?.titel ?: "Runde") else e.bot?.name ?: "", color = P.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Tag(if (e.runde) "Runde" else e.bot?.rolle?.ifBlank { "Bot" } ?: "Bot")
                            Spacer(Modifier.weight(1f))
                            Text(whenText(e.zeit), color = P.mut, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                            val laeuft = s?.id in busy
                            Text(if (laeuft) "arbeitet …" else s?.letzte?.ifBlank { null }?.let { vorschau(it) } ?: "Noch nichts geschrieben", color = if (laeuft) A.green else P.fg2,
                                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if ((s?.ungelesen ?: 0) > 0) Box(Modifier.padding(start = 8.dp).size(9.dp).clip(CircleShape).background(A.blue))
                        }
                    }
                }
            }
        }

        Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(P.card).border(1.dp, P.chip, RoundedCornerShape(16.dp)).clickable { vm.go(Route.Settings) }
            .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) A.green else A.accent))
            Text(if (online) "Pi verbunden" + (guthaben?.let { " · Ollama ${it.genutztProzent} % genutzt" } ?: "") else (fehler ?: "verbinde …"),
                color = P.fg2, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Tailnet", color = P.mut, fontSize = 13.sp)
        }
    }

    loeschen?.let { s ->
        AlertDialog(onDismissRequest = { loeschen = null }, containerColor = P.card,
            title = { Text("Runde löschen?", color = P.fg) },
            text = { Text("„${s.titel}“ wird gelöscht – auch auf dem Pi.", color = P.fg2) },
            confirmButton = { TextButton({ vm.deleteSession(s.id); loeschen = null }) { Text("Löschen", color = A.accent) } },
            dismissButton = { TextButton({ loeschen = null }) { Text("Abbrechen", color = P.fg2) } })
    }
}

/** Eine Zeile Vorschau ohne Markdown-Zeichen (fett, Tabellen, Überschriften). */
fun vorschau(t: String): String = markdown(t).text.replace(Regex("\\s+"), " ").trim()

@Composable
fun Badge(t: String, c: Color) = Text(t, color = Color(0xFF0E0E10), fontSize = 11.sp, fontWeight = FontWeight.Bold,
    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c).padding(horizontal = 7.dp, vertical = 3.dp))

@Composable
fun Tag(t: String) = Text(t, color = P.fg2, fontSize = 11.sp,
    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp)).background(P.chip).padding(horizontal = 7.dp, vertical = 2.dp))

// ── Chat ──────────────────────────────────────────────────────────

@Composable
fun ChatScreen(vm: NookViewModel, sid: String) {
    val msgs by vm.msgs.collectAsState()
    val busySet by vm.busy.collectAsState()
    val sitz by vm.sitzungen.collectAsState()
    val fr by vm.freigaben.collectAsState()
    val frBusy by vm.freigabeBusy.collectAsState()
    val online by vm.online.collectAsState()
    val bots by vm.bots.collectAsState()
    var input by rememberSaveable(sid) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var neu by remember { mutableStateOf(false) }
    var anhang by remember { mutableStateOf(false) }
    var notiz by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val wissenPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val bid = sitz.firstOrNull { it.id == sid }?.bot
        if (uri != null && bid != null) uploadWissenDatei(ctx, vm, bid, uri)
    }
    val list = rememberLazyListState()
    val s = sitz.firstOrNull { it.id == sid }
    val b = vm.bot(s?.bot)
    val busy = sid in busySet
    val runde = s?.art == "runde"
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { input = (input + " " + it).trim() }
        }
    }

    LaunchedEffect(msgs.size, msgs.lastOrNull()?.text?.length, busy) {
        if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") { vm.back() }
            Row(Modifier.weight(1f).clickable(enabled = b != null && !runde) { vm.go(Route.BotEdit(b?.id)) }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(start = 12.dp)) { BotFigure(b, 34.dp) }
                Column(Modifier.padding(start = 10.dp)) {
                    Text(if (runde) "Runde" else b?.name ?: "…", color = P.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (!online) A.accent else if (busy) A.amber else A.green))
                        Text(when {
                            !online -> "Pi nicht erreichbar"
                            runde -> "Kapitän ${b?.name ?: ""} fasst zusammen"
                            else -> "${b?.modell ?: ""} · auf dem Pi"
                        }, color = P.mut, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            if (!runde && b != null) Box {
                RoundIcon(Icons.Default.MoreVert, "Mehr") { menu = true }
                DropdownMenu(menu, { menu = false }, containerColor = P.card) {
                    DropdownMenuItem({ Text("Einstellungen von ${b.name}", color = P.fg) }, { menu = false; vm.go(Route.BotEdit(b.id)) })
                    DropdownMenuItem({ Text("Neu beginnen …", color = P.fg) }, { menu = false; neu = true })
                }
            }
        }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = list) {
            if (msgs.isEmpty() && !busy) item {
                Hint(if (b?.zugriffe == true) "Worum geht’s? ${b.name} kann in Kalender, Mail, Slack, GitLab und die Datenbank schauen und im Web suchen. " +
                    "Eintragen und Entwürfe gehen nur mit deiner Freigabe." else "Worum geht’s? ${b?.name ?: "Der Bot"} kann im Web suchen und sein Wissen nutzen.",
                    Modifier.padding(vertical = 20.dp))
            }
            // Name über einer Bot-Nachricht, wenn ein anderer Bot spricht als vorher (Runden, Aufträge)
            val names = HashSet<String>()
            var lastBot: String? = null
            msgs.forEach { m ->
                if (m.role == "user") lastBot = null
                else if (m.role == "bot") {
                    if ((runde || (m.bot != null && m.bot != s?.bot)) && m.bot != lastBot) names += m.id
                    lastBot = m.bot
                }
            }
            items(msgs, key = { it.id }) { m ->
                MessageView(m, if (m.id in names || m.zusammenfassung) bots.firstOrNull { it.id == m.bot } else null)
                if (m.role == "bot") freigabeIds(m.text).forEach { id ->
                    fr.firstOrNull { it.id == id }?.let { f -> FreigabeCard(f, f.id in frBusy, onChange = { w -> vm.aendern(f, w) }) { ja -> vm.decide(f, ja) } }
                }
            }
            item {
                if (busy) Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(3) { Box(Modifier.padding(end = 4.dp).size(6.dp).clip(CircleShape).background(P.mut)) }
                    Text(if (runde) "Die Runde läuft …" else "${b?.name ?: "Bot"} arbeitet …", color = P.mut, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        if (!runde) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Box {
                Box(Modifier.size(52.dp).clip(CircleShape).background(P.field).clickable { anhang = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, "Anhängen", tint = P.fg, modifier = Modifier.size(22.dp))
                }
                DropdownMenu(anhang, { anhang = false }, containerColor = P.card) {
                    DropdownMenuItem({ Text("🎙  Sprachnotiz aufnehmen", color = P.fg) }, { anhang = false; notiz = true })
                    DropdownMenuItem({ Text("📄  Datei als Wissen für ${b?.name ?: "den Bot"}", color = P.fg) }, { anhang = false; wissenPick.launch(WISSEN_MIME) })
                }
            }
            Spacer(Modifier.width(8.dp))
            TextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("Nachricht an ${b?.name ?: "Bot"}", color = P.mut) },
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(24.dp)),
                colors = TextFieldDefaults.colors(focusedContainerColor = P.field, unfocusedContainerColor = P.field,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = P.fg, unfocusedTextColor = P.fg, cursorColor = A.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                trailingIcon = {
                    Text("🎙", fontSize = 18.sp, modifier = Modifier.clip(CircleShape).clickable {
                        runCatching {
                            speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE"))
                        }.onFailure { vm.say("Spracheingabe nicht verfügbar") }
                    }.padding(8.dp))
                },
                maxLines = 6,
            )
            Spacer(Modifier.width(8.dp))
            val can = input.isNotBlank() && online
            Box(Modifier.size(52.dp).clip(CircleShape).background(if (can) A.accent else P.field)
                .clickable(enabled = can) { vm.send(input); input = "" }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.Send, "Senden", tint = if (can) Color.White else P.mut, modifier = Modifier.size(22.dp))
            }
        }
    }
    ChatDialoge(vm, b, neu, notiz, { neu = false }, { notiz = false })
}

@Composable
private fun ChatDialoge(vm: NookViewModel, b: de.hoefer.nookbot.data.Bot?, neu: Boolean, notiz: Boolean, onNeu: () -> Unit, onNotiz: () -> Unit) {
    if (neu && b != null) AlertDialog(onDismissRequest = onNeu, containerColor = P.card,
        title = { Text("Mit ${b.name} neu beginnen?", color = P.fg) },
        text = { Text("Die bisherige Unterhaltung wandert ins Archiv (Menü → Ältere Unterhaltungen). ${b.name} startet ohne den bisherigen Verlauf – sein Wissen bleibt.", color = P.fg2) },
        confirmButton = { TextButton({ onNeu(); vm.neuBeginnen(b.id) }) { Text("Neu beginnen", color = A.accent) } },
        dismissButton = { TextButton(onNeu) { Text("Abbrechen", color = P.fg2) } })
    if (notiz) NotizDialog(vm, b, onNotiz)
}

@Composable
private fun MessageView(m: Msg, from: de.hoefer.nookbot.data.Bot?) {
    when (m.role) {
        "user" -> Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.End) {
            SelectionContainer {
                Text(m.text, color = P.fg, fontSize = 15.sp, modifier = Modifier.widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).background(P.chip).padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        "thought" -> {
            var open by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { open = !open }) {
                Text(if (open) "Gedanken ▾" else "Gedanken ▸", color = P.mut, fontSize = 12.sp)
                if (open) Text(m.text, color = P.mut, fontSize = 13.sp, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 2.dp))
            }
        }
        "tool" -> Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Check, null, tint = A.green, modifier = Modifier.size(14.dp))
            Text(toolLine(m.text), color = P.fg2, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
        }
        "error" -> Text("⚠ ${m.text}", color = A.accent, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
        else -> if (m.text.isNotBlank()) Column(Modifier.padding(vertical = 8.dp)) {
            if (from != null || m.zusammenfassung) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                BotFigure(from, 22.dp)
                Text((from?.name ?: "") + if (m.zusammenfassung) " · Zusammenfassung" else "", color = P.fg2, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
            }
            SelectionContainer { Text(markdown(m.text, P.fg2), color = P.fg, fontSize = 15.sp, lineHeight = 21.sp) }
        }
    }
}

@Composable
fun FreigabeCard(f: Freigabe, busy: Boolean, onChange: ((String) -> Unit)? = null, onDecide: (Boolean) -> Unit) {
    var aendern by remember { mutableStateOf(false) }
    var wunsch by remember { mutableStateOf("") }
    if (aendern && onChange != null) AlertDialog(onDismissRequest = { aendern = false }, containerColor = P.card,
        title = { Text("Was soll anders sein?", color = P.fg) },
        text = { Column { Text(f.text.lineSequence().first(), color = P.fg2, fontSize = 13.sp); Field("z. B. erst 15:00, ohne Ben, anderer Ort …", wunsch, lines = 4) { wunsch = it } } },
        confirmButton = { TextButton({ aendern = false; onChange(wunsch); wunsch = "" }, enabled = wunsch.isNotBlank()) { Text("An den Bot", color = A.accent) } },
        dismissButton = { TextButton({ aendern = false }) { Text("Abbrechen", color = P.fg2) } })
    Card(Modifier.padding(vertical = 8.dp), border = if (f.offen) A.amber.copy(alpha = 0.6f) else P.line) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge("FREIGABE", if (f.offen) A.amber else P.mut)
            Text(f.titel, color = P.fg2, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
            Text(f.id, color = P.mut, fontSize = 11.sp)
        }
        Text(f.text, color = P.fg, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 10.dp), maxLines = 14, overflow = TextOverflow.Ellipsis)
        if (f.offen) {
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                MainButton(if (busy) "…" else "Ausführen", enabled = !busy) { onDecide(true) }
                Spacer(Modifier.width(10.dp))
                if (onChange != null) { SideButton("Ändern") { aendern = true }; Spacer(Modifier.width(10.dp)) }
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(P.field).clickable(enabled = !busy) { onDecide(false) },
                    contentAlignment = Alignment.Center) { Icon(Icons.Default.Close, "Ablehnen", tint = P.fg2, modifier = Modifier.size(18.dp)) }
            }
        } else {
            val c = when (f.status) { "ausgefuehrt" -> A.green; "fehler" -> A.accent; else -> P.mut }
            Text(f.statusText + (f.ergebnis?.let { " – " + it.take(200) } ?: ""), color = c, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
