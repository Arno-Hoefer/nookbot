package de.hoefer.nookbot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hoefer.nookbot.data.AktEintrag
import de.hoefer.nookbot.data.Ausloeser
import de.hoefer.nookbot.data.toolLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Auslöser ──────────────────────────────────────────────────────

private val SUCHE_HILFE = mapOf(
    "mail" to "Gmail-Suche, z. B. from:schule@beispiel.de oder subject:Rechnung",
    "slack" to "Slack-Suche, z. B. in:#support dringend",
    "webseite" to "Adresse (https://…) – meldet, wenn sich der Text ändert",
    "paket" to "",
)

private fun zeit(ms: Long): String = if (ms <= 0) "–" else SimpleDateFormat("EE dd.MM. HH:mm", Locale.GERMANY).format(Date(ms))

@Composable
fun AusloeserScreen(vm: NookViewModel) {
    val list by vm.ausloeser.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar("Auslöser", { vm.back() }) { MainButton("+ Neu") { vm.go(Route.AusloeserEdit(null)) } }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item {
                Hint("Wenn etwas passiert, arbeitet ein Bot: neue Mail zu einer Suche, neue Slack-Nachricht, eine Webseite ändert sich, ein Paket hat einen neuen Status. " +
                    "Geprüft wird auf dem Pi ohne KI – erst wenn etwas Neues da ist, bekommt der Bot einen Auftrag mit allen neuen Treffern.", Modifier.padding(bottom = 10.dp))
            }
            if (list.isEmpty()) item { Hint("Noch keine Auslöser. Oder per Chat: „Sag mir Bescheid, wenn eine Mail von der Schule kommt.“", Modifier.padding(vertical = 8.dp)) }
            items(list, key = { it.id }) { a ->
                val b = vm.bot(a.bot)
                Card(Modifier.padding(vertical = 5.dp), border = if (a.fehler.isNotBlank()) A.accent.copy(alpha = 0.6f) else null,
                    onClick = { vm.go(Route.AusloeserEdit(a.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BotFigure(b, 34.dp)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(a.name, color = P.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("${Ausloeser.QUELLEN[a.quelle] ?: a.quelle} · ${b?.name ?: a.bot} · alle ${a.intervall} min", color = P.mut, fontSize = 12.sp)
                            if (a.suche.isNotBlank()) Text(a.suche, color = P.fg2, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text("geprüft ${zeit(a.letztePruefung)} · ausgelöst ${zeit(a.letzterTreffer)}", color = P.mut, fontSize = 12.sp)
                            if (a.fehler.isNotBlank()) Text("⚠ ${a.fehler}", color = A.accent, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(a.aktiv, { on -> vm.saveAusloeser(list.map { if (it.id == a.id) it.copy(aktiv = on) else it }) },
                            colors = SwitchDefaults.colors(checkedTrackColor = A.accent))
                    }
                }
            }
        }
    }
}

@Composable
fun AusloeserEditScreen(vm: NookViewModel, id: String?) {
    val list by vm.ausloeser.collectAsState()
    val bots by vm.bots.collectAsState()
    val a = list.firstOrNull { it.id == id }
    var name by remember { mutableStateOf(a?.name ?: "") }
    var quelle by remember { mutableStateOf(a?.quelle ?: "mail") }
    var bot by remember { mutableStateOf(a?.bot ?: bots.firstOrNull { it.kapitaen }?.id ?: "") }
    var suche by remember { mutableStateOf(a?.suche ?: "") }
    var konto by remember { mutableStateOf(a?.konto ?: "") }
    var auftrag by remember { mutableStateOf(a?.auftrag ?: "") }
    var intervall by remember { mutableStateOf((a?.intervall ?: 15).toString()) }
    var maxTag by remember { mutableStateOf((a?.maxProTag ?: 6).toString()) }
    var anruf by remember { mutableStateOf(a?.anruf ?: false) }
    val sucheOk = when (quelle) {
        "paket" -> true
        "webseite" -> Regex("^https?://\\S+").matches(suche.trim())
        else -> suche.isNotBlank()
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(if (a == null) "Neuer Auslöser" else a.name, { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Field("Name", name) { name = it }
            Section("Wenn …")
            Row { Ausloeser.QUELLEN.forEach { (k, v) -> Chip(v.substringAfter(" "), quelle == k) { quelle = k } } }
            if (quelle != "paket") Field(SUCHE_HILFE[quelle] ?: "Suche", suche, mono = true) { suche = it }
            else Hint("Meldet jeden neuen Status eines Pakets aus Home (der Bot braucht den Zugriff „Pakete“).", Modifier.padding(top = 6.dp))
            if (quelle == "mail") Field("Konto (leer = alle, die der Bot sehen darf)", konto) { konto = it }
            Section("… dann arbeitet")
            androidx.compose.foundation.layout.FlowRow { bots.forEach { b -> Chip(b.name, bot == b.id) { bot = b.id } } }
            Section("Auftrag", "Der Bot bekommt alle neuen Treffer dazu. Ist nichts davon wichtig, antwortet er nur kurz.")
            Field("z. B. Fasse die Mail in 2 Sätzen zusammen und sag, ob ich etwas tun muss.", auftrag, lines = 6) { auftrag = it }
            Row {
                Field("Prüfen alle (min)", intervall, number = true, modifier = Modifier.width(170.dp).padding(end = 10.dp)) { intervall = it.filter(Char::isDigit).take(4) }
                Field("Max. pro Tag", maxTag, number = true, modifier = Modifier.width(170.dp)) { maxTag = it.filter(Char::isDigit).take(2) }
            }
            AnrufSchalter(anruf) { anruf = it }
            Hint("Beim ersten Prüfen merkt sich der Pi nur den Stand – ab dann zählt nur Neues. Mindestens 5 Minuten.", Modifier.padding(top = 6.dp))
            if (a?.fehler?.isNotBlank() == true) Text("⚠ ${a.fehler}", color = A.accent, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                MainButton("Speichern", enabled = name.isNotBlank() && auftrag.isNotBlank() && sucheOk && bot.isNotBlank()) {
                    val neu = (a ?: Ausloeser("", "", "", "", "", "", "", true, 15, 6)).copy(
                        name = name.trim(), quelle = quelle, bot = bot, suche = if (quelle == "paket") "" else suche.trim(),
                        konto = if (quelle == "mail") konto.trim() else "", auftrag = auftrag.trim(),
                        intervall = (intervall.toIntOrNull() ?: 15).coerceIn(5, 1440), maxProTag = (maxTag.toIntOrNull() ?: 6).coerceIn(1, 50), anruf = anruf)
                    vm.saveAusloeser(if (a == null) list + neu else list.map { if (it.id == a.id) neu else it }) { vm.back() }
                }
                Spacer(Modifier.width(10.dp))
                if (a != null) SideButton("Jetzt prüfen") { vm.pruefeAusloeser(a.id) }
            }
            if (a != null) TextButton({ vm.saveAusloeser(list.filterNot { it.id == a.id }) { vm.back() } }) { Text("Auslöser löschen", color = A.accent) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** „Als Anruf“: nur wenn ausdrücklich gewollt – der Bot ruft an und liest sein Ergebnis vor. */
@Composable
fun AnrufSchalter(an: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("📞 Als Anruf", color = P.fg, fontSize = 14.sp)
            Text("Das Handy klingelt, der Bot liest sein Ergebnis vor und hört dann zu.", color = P.mut, fontSize = 12.sp)
        }
        Switch(an, onChange, colors = SwitchDefaults.colors(checkedTrackColor = A.accent))
    }
}

// ── Aktivität ─────────────────────────────────────────────────────

private val ART_ICON = mapOf("anruf" to "📞", "werkzeug" to "🔧", "routine" to "⏰", "ausloeser" to "⚡", "freigabe" to "✅", "runde" to "👥")

@Composable
fun AktivitaetScreen(vm: NookViewModel) {
    val list by vm.aktivitaet.collectAsState()
    val filter by vm.aktFilter.collectAsState()
    val bots by vm.bots.collectAsState()
    val (tage, botFilter, hinter) = filter
    fun setze(t: Int = tage, b: String = botFilter, h: Boolean = hinter) { vm.aktFilter.value = Triple(t, b, h); vm.loadAktivitaet() }
    val tagFmt = remember { SimpleDateFormat("EEEE, dd.MM.", Locale.GERMANY) }
    val zurueck = remember(list) { list.filter { it.was == "Zurückgenommen" }.map { it.detail.substringBefore(" ") }.toSet() }
    var zuruecknehmen by remember { mutableStateOf<String?>(null) }
    zuruecknehmen?.let { fid ->
        AlertDialog(onDismissRequest = { zuruecknehmen = null }, containerColor = P.card,
            title = { Text("Zurücknehmen?", color = P.fg) },
            text = { Text("Termine werden ohne Absage gelöscht, Entwürfe gelöscht. Tabellen und Dokumente gehen nicht automatisch.", color = P.fg2) },
            confirmButton = { TextButton({ vm.zuruecknehmen(fid); zuruecknehmen = null }) { Text("Zurücknehmen", color = A.accent) } },
            dismissButton = { TextButton({ zuruecknehmen = null }) { Text("Abbrechen", color = P.fg2) } })
    }
    val uhr = remember { SimpleDateFormat("HH:mm", Locale.GERMANY) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Aktivität", { vm.back() }, sub = "was die Bots getan haben")
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item {
                Row { Chip("Heute + gestern", tage == 1) { setze(t = 1) }; Chip("7 Tage", tage == 7) { setze(t = 7) }; Chip("30 Tage", tage == 30) { setze(t = 30) } }
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 4.dp)) {
                    Chip("Alle Bots", botFilter.isBlank()) { setze(b = "") }
                    bots.forEach { b -> Chip(b.name, botFilter == b.id) { setze(b = b.id) } }
                }
                Row(Modifier.padding(top = 4.dp)) { Chip("Nur im Hintergrund", hinter) { setze(h = !hinter) } }
            }
            if (list.isEmpty()) item { Hint("Nichts in diesem Zeitraum.", Modifier.padding(vertical = 12.dp)) }
            var tag = ""
            list.forEach { e ->
                val t = tagFmt.format(Date(e.ts))
                if (t != tag) {
                    tag = t
                    item(key = "tag-$t") { Text(t, color = P.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)) }
                }
                item { AktZeile(e, uhr.format(Date(e.ts)), vm.bot(e.bot)?.name ?: e.bot, e.ref.takeIf { it.isNotBlank() && it !in zurueck }) { zuruecknehmen = it } }
            }
        }
    }
}

@Composable
private fun AktZeile(e: AktEintrag, uhr: String, botName: String, ref: String?, onZurueck: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(uhr, color = P.mut, fontSize = 13.sp, modifier = Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            Text("${ART_ICON[e.art] ?: "·"} $botName · ${if (e.art == "werkzeug") toolLabel(e.was) else e.was}" +
                (if (e.hintergrund) " · im Hintergrund" else "") + (if (!e.ok) " · fehlgeschlagen" else ""),
                color = if (e.ok) P.fg else A.accent, fontSize = 14.sp)
            if (e.detail.isNotBlank()) Text(e.detail, color = P.mut, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (ref != null) TextButton({ onZurueck(ref) }, contentPadding = PaddingValues(0.dp)) { Text("↩ Rückgängig", color = A.accent, fontSize = 13.sp) }
        }
    }
    HorizontalDivider(color = P.line)
}
