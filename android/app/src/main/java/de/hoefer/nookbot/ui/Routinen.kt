package de.hoefer.nookbot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hoefer.nookbot.data.Routine
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Routinen als „Zeitung“: je Routine ein Artikel mit dem letzten Ergebnis. Gelesenes verschwindet bis zum nächsten Stand.

/** „Heute 09:02“, „Gestern 19:00“, sonst „Mo 16.11. 07:00“. */
fun standText(ms: Long): String {
    if (ms <= 0) return ""
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val h = Calendar.getInstance()
    val uhr = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(ms))
    val tage = ((h.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis -
        c.clone().let { it as Calendar }.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis) / 86_400_000L)
    return when (tage) {
        0L -> "Heute $uhr"
        1L -> "Gestern $uhr"
        else -> SimpleDateFormat("EE dd.MM. HH:mm", Locale.GERMANY).format(Date(ms))
    }
}

@Composable
fun RoutinenScreen(vm: NookViewModel) {
    val routinen by vm.routinen.collectAsState()
    val busy by vm.busy.collectAsState()
    var anordnen by rememberSaveable { mutableStateOf(false) }
    var gelesenAuf by rememberSaveable { mutableStateOf(false) }
    val neu = routinen.filter { !it.gelesen }
    val gelesen = routinen.filter { it.gelesen }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.Default.Menu, "Menü") { vm.go(Route.Mehr) }
            Spacer(Modifier.weight(1f))
            Text("Routinen", color = P.fg, fontSize = 24.sp, fontFamily = Serif, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            RoundIcon(Icons.Default.List, if (anordnen) "Fertig" else "Anordnen") { anordnen = !anordnen }
            Spacer(Modifier.width(10.dp))
            RoundIcon(Icons.Default.Add, "Neue Routine") { vm.go(Route.RoutineEdit(null)) }
        }
        if (anordnen) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) {
                item { Hint("Reihenfolge der Artikel – oben steht, was dir am wichtigsten ist.", Modifier.padding(bottom = 8.dp)) }
                items(routinen, key = { it.id }) { r ->
                    val i = routinen.indexOf(r)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(P.card).border(1.dp, P.line, RoundedCornerShape(16.dp))
                        .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, color = P.fg, fontSize = 16.sp, fontFamily = Serif, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(r.beschreibung, color = P.mut, fontSize = 12.sp, maxLines = 1)
                        }
                        TextButton({ vm.routinenSchieben(r.id, -1) }, enabled = i > 0) { Text("↑", color = if (i > 0) P.fg else P.line, fontSize = 20.sp) }
                        TextButton({ vm.routinenSchieben(r.id, 1) }, enabled = i < routinen.size - 1) { Text("↓", color = if (i < routinen.size - 1) P.fg else P.line, fontSize = 20.sp) }
                    }
                }
            }
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (routinen.isEmpty()) item {
                Card(Modifier.padding(vertical = 6.dp)) {
                    Text("Deine Zeitung, von ${vm.bots.value.firstOrNull { it.kapitaen }?.name ?: "Reacher"} geschrieben", color = P.fg, fontSize = 19.sp, fontFamily = Serif)
                    Text("Eine Routine erledigt einen Auftrag nach Zeitplan – Slack prüfen, Mails zusammenfassen, eine Kennzahl abfragen. " +
                        "Ihr Ergebnis steht dann hier, mit Uhrzeit. Sag es einfach im Chat: „schau jeden Werktag um 8 und 12 in Slack“.",
                        color = P.fg2, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.size(12.dp))
                    MainButton("Routine anlegen") { vm.go(Route.RoutineEdit(null)) }
                }
            }
            if (routinen.isNotEmpty() && neu.isEmpty()) item {
                Text("Alles gelesen.", color = P.fg, fontSize = 19.sp, fontFamily = Serif, modifier = Modifier.padding(start = 4.dp, top = 14.dp))
                Hint("Neue Stände erscheinen hier, sobald eine Routine gelaufen ist.", Modifier.padding(start = 4.dp, top = 2.dp, bottom = 10.dp))
            }
            items(neu, key = { it.id }) { r -> Artikel(vm, r, r.sitzung in busy || r.laeuft) }
            if (gelesen.isNotEmpty()) item(key = "gelesen") {
                Row(Modifier.fillMaxWidth().clickable { gelesenAuf = !gelesenAuf }.padding(horizontal = 4.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("GELESEN · ${gelesen.size}", color = P.mut, fontSize = 11.sp, letterSpacing = 0.8.sp, modifier = Modifier.weight(1f))
                    Text(if (gelesenAuf) "ausblenden" else "zeigen", color = P.fg2, fontSize = 13.sp)
                }
            }
            if (gelesenAuf) items(gelesen, key = { "g" + it.id }) { r -> Artikel(vm, r, r.sitzung in busy || r.laeuft) }
            item { Spacer(Modifier.height(8.dp)) }
        }
        Leiste(vm, Tab.ROUTINEN)
    }
}

@Composable
private fun Artikel(vm: NookViewModel, r: Routine, laeuft: Boolean) {
    var auf by remember(r.ts) { mutableStateOf(false) }
    var menue by remember { mutableStateOf(false) }
    val lang = r.text.length > 420 || r.text.count { it == '\n' } > 7
    Card(Modifier.padding(vertical = 6.dp), onClick = if (lang) ({ auf = !auf }) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (r.hatErgebnis) "AKTUALISIERT · ${standText(r.ts).uppercase(Locale.GERMANY)}" else "NOCH KEIN ERGEBNIS"),
                color = P.mut, fontSize = 11.sp, letterSpacing = 0.8.sp, modifier = Modifier.weight(1f))
            if (laeuft) {
                CircularProgressIndicator(Modifier.size(12.dp), color = A.green, strokeWidth = 1.5.dp)
                Text(" läuft", color = A.green, fontSize = 11.sp)
            }
            Box {
                IconButton({ menue = true }, Modifier.size(28.dp)) { Icon(Icons.Default.MoreVert, "Mehr", tint = P.mut, modifier = Modifier.size(18.dp)) }
                DropdownMenu(menue, { menue = false }, containerColor = P.card) {
                    DropdownMenuItem({ Text("Ändern", color = P.fg) }, { menue = false; vm.go(Route.RoutineEdit(r.id)) })
                    DropdownMenuItem({ Text("Verlauf ansehen", color = P.fg) }, { menue = false; if (r.sitzung.isNotBlank()) vm.openChat(r.sitzung) })
                    DropdownMenuItem({ Text("Nach oben", color = P.fg) }, { menue = false; vm.routinenSchieben(r.id, -100) })
                    DropdownMenuItem({ Text(if (r.aktiv) "Pausieren" else "Wieder einschalten", color = P.fg) }, { menue = false; vm.routineAktiv(r.id, !r.aktiv) })
                }
            }
        }
        Text(r.name, color = P.fg, fontSize = 22.sp, lineHeight = 27.sp, fontFamily = Serif, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(r.beschreibung + (if (!r.aktiv) " · pausiert" else ""), color = P.mut, fontSize = 12.sp, modifier = Modifier.weight(1f, fill = false))
            if (r.freigabe) Box(Modifier.padding(start = 8.dp)) { Badge("FREIGABE", A.amber) }
        }
        when {
            r.fehler.isNotBlank() && r.text.isBlank() -> Text(r.fehler, color = A.accent, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
            r.text.isNotBlank() -> SelectionContainer {
                Text(markdown(r.text, P.fg2), color = P.fg, fontSize = 15.sp, lineHeight = 22.sp, maxLines = if (auf || !lang) Int.MAX_VALUE else 8,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            }
            !r.hatErgebnis -> Text("Läuft zum ersten Mal ${if (r.aktiv) "laut Zeitplan" else "erst, wenn du sie einschaltest"} – oder jetzt von Hand.",
                color = P.fg2, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        }
        if (lang) Text(if (auf) "weniger" else "weiterlesen", color = P.fg2, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (r.hatErgebnis) SideButton(if (r.gelesen) "Wieder zeigen" else "Gelesen") { vm.routineGelesen(r.id, !r.gelesen) }
            SideButton(if (laeuft) "läuft …" else "Jetzt ausführen") { if (!laeuft) vm.runRoutine(r.id) }
            if (r.verlauf.isNotEmpty()) Text("${r.verlauf.size} frühere", color = P.mut, fontSize = 12.sp, modifier = Modifier.clickable { if (r.sitzung.isNotBlank()) vm.openChat(r.sitzung) })
        }
    }
}

@Composable
fun RoutineEditScreen(vm: NookViewModel, id: String?) {
    val routinen by vm.routinen.collectAsState()
    val bots by vm.bots.collectAsState()
    val r = routinen.firstOrNull { it.id == id }
    var name by remember { mutableStateOf(r?.name ?: "") }
    var zeiten by remember { mutableStateOf(r?.zeiten?.takeIf { it.isNotEmpty() } ?: listOf("07:45")) }
    var neueZeit by remember { mutableStateOf("") }
    var tage by remember { mutableStateOf(r?.tage?.toSet() ?: setOf(1, 2, 3, 4, 5)) }
    var einmalig by remember { mutableStateOf(r?.einmalig ?: false) }
    var eigen by remember { mutableStateOf(r != null && r.zeiten.isEmpty()) }
    var cron by remember { mutableStateOf(r?.cron?.takeIf { r.zeiten.isEmpty() } ?: "") }
    var datum by remember { mutableStateOf(r?.datum?.takeIf { it.isNotBlank() } ?: SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date(System.currentTimeMillis() + 86_400_000))) }
    val datumOk = !einmalig || Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(datum)
    var auftrag by remember { mutableStateOf(r?.auftrag ?: "") }
    var anruf by remember { mutableStateOf(r?.anruf ?: false) }
    val zeitRe = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    val minutenGleich = zeiten.map { it.takeLast(2) }.toSet().size <= 1
    val zeitOk = if (eigen) cron.trim().split(Regex("\\s+")).size == 5 else zeiten.isNotEmpty() && zeiten.all { zeitRe.matches(it) } && minutenGleich
    Column(Modifier.fillMaxSize()) {
        TopBar(if (r == null) "Neue Routine" else r.name, { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Field("Name – wird die Überschrift des Artikels", name) { name = it }
            Section("Wann?")
            Row {
                Chip("Wiederkehrend", !einmalig && !eigen) { einmalig = false; eigen = false }
                Chip("Einmalig", einmalig) { einmalig = true; eigen = false }
                Chip("Cron", eigen) { eigen = true; einmalig = false }
            }
            if (eigen) {
                Field("Minute Stunde Tag Monat Wochentag", cron, mono = true) { cron = it }
                Hint("Beispiele: „0 8,12,16 * * 1-5“ = Mo–Fr um 8, 12 und 16 Uhr · „*/30 8-18 * * *“ = halbstündlich von 8 bis 18 Uhr · Wochentag 0 oder 7 = Sonntag.")
            } else {
                if (einmalig) Field("Datum (JJJJ-MM-TT)", datum, modifier = Modifier.width(200.dp)) { datum = it.take(10) }
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    zeiten.sorted().forEach { z -> Chip("$z  ✕", true) { if (zeiten.size > 1) zeiten = zeiten - z } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field("Weitere Uhrzeit (HH:MM)", neueZeit, modifier = Modifier.width(200.dp)) { neueZeit = it.take(5) }
                    Spacer(Modifier.width(10.dp))
                    SideButton("Dazu") { if (zeitRe.matches(neueZeit) && neueZeit !in zeiten) { zeiten = zeiten + neueZeit; neueZeit = "" } }
                }
                if (!minutenGleich) Hint("Mehrere Uhrzeiten brauchen dieselbe Minute (8:00, 12:00) – sonst „Cron“ nehmen.")
                if (!einmalig) FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TAGE.forEachIndexed { i, t -> Chip(t, (i + 1) in tage) { tage = if ((i + 1) in tage) tage - (i + 1) else tage + (i + 1) } }
                }
            }
            if (!datumOk) Hint("Datum im Format 2026-12-24")
            Section("Auftrag", "So, als würdest du ihn ${bots.firstOrNull { it.kapitaen }?.name ?: "Reacher"} schreiben – z. B. „Prüfe Slack auf Nachrichten, " +
                "die meine Antwort brauchen. Kurz, in zwei Gruppen.“ Das Ergebnis erscheint als Artikel unter Routinen.")
            Field("Was soll passieren?", auftrag, lines = 8) { auftrag = it }
            AnrufSchalter(anruf) { anruf = it }
            Row(Modifier.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                MainButton("Speichern", enabled = name.isNotBlank() && auftrag.isNotBlank() && zeitOk && datumOk && (einmalig || eigen || tage.isNotEmpty())) {
                    val kap = bots.firstOrNull { it.kapitaen }?.id ?: r?.bot ?: ""
                    val neu = Routine(r?.id ?: "", name.trim(), kap, auftrag.trim(), if (eigen) emptyList() else zeiten.sorted(), tage.sorted(),
                        r?.aktiv ?: true, r?.letzterLauf ?: 0, if (einmalig) datum else "", anruf, cron = if (eigen) cron.trim() else "")
                    vm.saveRoutinen(if (r == null) routinen + neu else routinen.map { if (it.id == r.id) neu else it }) { vm.back() }
                }
                Spacer(Modifier.width(10.dp))
                if (r != null) SideButton("Jetzt ausführen") { vm.runRoutine(r.id) }
            }
            if (r != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Aktiv", color = P.fg, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(r.aktiv, { vm.routineAktiv(r.id, it) }, colors = SwitchDefaults.colors(checkedTrackColor = A.accent))
                }
                TextButton({ vm.saveRoutinen(routinen.filterNot { it.id == r.id }) { vm.back() } }) { Text("Routine löschen", color = A.accent) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
