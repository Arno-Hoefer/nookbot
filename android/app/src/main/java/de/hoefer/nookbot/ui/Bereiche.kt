package de.hoefer.nookbot.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hoefer.nookbot.BuildConfig
import de.hoefer.nookbot.data.Abfrage
import de.hoefer.nookbot.data.Datenbank
import de.hoefer.nookbot.data.Routine
import java.util.zip.ZipInputStream

// ── Menü ──────────────────────────────────────────────────────────

@Composable
fun MehrScreen(vm: NookViewModel) {
    val fr by vm.freigaben.collectAsState()
    val bots by vm.bots.collectAsState()
    val routinen by vm.routinen.collectAsState()
    val g by vm.guthaben.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar("Menü", { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Entry("🤖", "Bots", "${bots.size} im Team – Name, Modell, Persönlichkeit, Fokus, Wissen") { }
            bots.forEach { b ->
                Row(Modifier.fillMaxWidth().clickable { vm.go(Route.BotEdit(b.id)) }.padding(start = 40.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BotFigure(b, 30.dp)
                    Text(b.name, color = P.fg, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp))
                    Text("  ${b.rolle} · ${b.modell}", color = P.mut, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth().clickable { vm.go(Route.BotEdit(null)) }.padding(start = 40.dp, top = 6.dp, bottom = 10.dp)) {
                Text("+ Neuer Bot", color = A.accent, fontSize = 15.sp)
            }
            Entry("✅", "Freigaben", "${fr.count { it.offen }} warten") { vm.go(Route.Freigaben) }
            Entry("👥", "Neue Runde", "Mehrere Bots zu einer Frage, der Kapitän fasst zusammen") { vm.go(Route.RundeNeu) }
            Entry("⏰", "Routinen", "${routinen.count { it.aktiv }} aktiv – Aufgaben, die ein Bot zu einer Zeit erledigt") { vm.go(Route.Routinen) }
            Entry("🔔", "Erinnerungen", "${vm.erinnerungen.collectAsState().value.count { !it.erledigt }} offen – Hinweise zur Uhrzeit, auch von den Bots gestellt") {
                vm.go(Route.Erinnerungen)
            }
            val abl by vm.ablaeufe.collectAsState()
            val ged by vm.gedaechtnis.collectAsState()
            Entry("📋", "Abläufe", abl.count { it.entwurf }.let { e -> "${abl.count { !it.entwurf }} gespeichert" + (if (e > 0) " · $e Entwurf" else "") +
                " – aus Gesprächen gelernt" }) { vm.go(Route.Ablaeufe) }
            Entry("🧠", "Gedächtnis", "${ged.size} Einträge – was sich die Bots über dich merken") { vm.go(Route.Gedaechtnis) }
            Entry("🗂️", "Ältere Unterhaltungen", "Archiv nach „Neu beginnen“ und frühere Chats") { vm.go(Route.Archiv) }
            Entry("🗄️", "Datenbanken", "Beschreibung, Schema-Notizen, feste Abfragen") { vm.go(Route.Datenbanken) }
            Entry("📚", "Wissen", "Dateien für alle Bots") { vm.go(Route.Wissen) }
            Entry("🔐", "Zugriffe", "Was die Bots sehen dürfen") { vm.go(Route.Zugriffe) }
            Entry("⚙️", "Einstellungen", g?.let { "Ollama: ${"%.2f".format(it.rest)} $ von ${"%.0f".format(it.gesamt)} $ übrig" } ?: "Verbindung, Design, Benachrichtigungen") {
                vm.go(Route.Settings)
            }
        }
    }
}

@Composable
private fun Entry(icon: String, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 22.sp, modifier = Modifier.width(40.dp))
        Column {
            Text(title, color = P.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = P.mut, fontSize = 13.sp)
        }
    }
}

// ── Freigaben ─────────────────────────────────────────────────────

@Composable
fun FreigabenScreen(vm: NookViewModel) {
    val fr by vm.freigaben.collectAsState()
    val err by vm.freigabeFehler.collectAsState()
    val busy by vm.freigabeBusy.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar("Freigaben", { vm.back() })
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            err?.let { item { Text("⚠ $it", color = A.accent, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) } }
            if (fr.isEmpty() && err == null) item {
                Hint("Nichts zu entscheiden. Wenn ein Bot etwas eintragen oder entwerfen will, erscheint es hier.", Modifier.padding(vertical = 16.dp))
            }
            items(fr, key = { it.id }) { f ->
                Text(whenText(f.erstellt), color = P.mut, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                FreigabeCard(f, f.id in busy) { ja -> vm.decide(f, ja) }
            }
        }
    }
}

// ── Runde ─────────────────────────────────────────────────────────

@Composable
fun RundeNeuScreen(vm: NookViewModel) {
    val bots by vm.bots.collectAsState()
    var frage by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf(bots.map { it.id }.toSet()) }
    var kap by remember { mutableStateOf(bots.firstOrNull { it.kapitaen }?.id ?: "") }
    Column(Modifier.fillMaxSize()) {
        TopBar("Neue Runde", { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Hint("Jeder ausgewählte Bot antwortet aus seiner Rolle, danach fasst der Kapitän zusammen: wo Einigkeit, wo nicht, was er empfiehlt.")
            Field("Frage an die Runde", frage, lines = 5) { frage = it }
            Section("Wer ist dabei?")
            bots.forEach { b ->
                Row(Modifier.fillMaxWidth().clickable { sel = if (b.id in sel) sel - b.id else sel + b.id }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(b.id in sel, { sel = if (it) sel + b.id else sel - b.id })
                    BotFigure(b, 30.dp)
                    Text("${b.name}", color = P.fg, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
                    Text("  ${b.rolle}", color = P.mut, fontSize = 13.sp)
                }
            }
            Section("Wer fasst zusammen?")
            Row { bots.forEach { b -> Chip(b.name, kap == b.id) { kap = b.id } } }
            Row(Modifier.padding(vertical = 20.dp)) {
                MainButton("Runde starten", enabled = frage.isNotBlank() && sel.size >= 2) { vm.startRunde(frage, sel.toList(), kap) }
            }
            if (sel.size < 2) Hint("Mindestens zwei Bots auswählen.")
        }
    }
}

// ── Routinen ──────────────────────────────────────────────────────

@Composable
fun RoutinenScreen(vm: NookViewModel) {
    val routinen by vm.routinen.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar("Routinen", { vm.back() }) { MainButton("+ Neu") { vm.go(Route.RoutineEdit(null)) } }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item { Hint("Feste Aufträge mit Zeitplan – z. B. „Mo–Fr 7:45: Was steht heute an, was ist in Slack für mich los?“. " +
                "Das Ergebnis landet in einer eigenen Unterhaltung, dazu eine Benachrichtigung.", Modifier.padding(bottom = 10.dp)) }
            if (routinen.isEmpty()) item { Hint("Noch keine Routinen.", Modifier.padding(vertical = 8.dp)) }
            items(routinen, key = { it.id }) { r ->
                val b = vm.bot(r.bot)
                Card(Modifier.padding(vertical = 5.dp), onClick = { vm.go(Route.RoutineEdit(r.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BotFigure(b, 34.dp)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(r.name, color = P.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text((if (r.einmalig) "einmalig ${datumText(r.datum)} ${r.zeit}" else "${r.zeit} · ${tageText(r.tage)}") +
                                " · ${b?.name ?: r.bot}" + (if (r.sitzung.isNotBlank()) " · im Chat" else ""), color = P.mut, fontSize = 12.sp)
                        }
                        Switch(r.aktiv, { on -> vm.saveRoutinen(routinen.map { if (it.id == r.id) it.copy(aktiv = on) else it }) },
                            colors = SwitchDefaults.colors(checkedTrackColor = A.accent))
                    }
                }
            }
        }
    }
}

fun datumText(d: String): String = runCatching { d.substring(8, 10) + "." + d.substring(5, 7) + "." }.getOrDefault(d)

fun tageText(t: List<Int>): String = when (t.sorted()) {
    listOf(1, 2, 3, 4, 5, 6, 7) -> "täglich"
    listOf(1, 2, 3, 4, 5) -> "Mo–Fr"
    listOf(6, 7) -> "Wochenende"
    else -> t.sorted().joinToString(", ") { TAGE[it - 1] }
}

@Composable
fun RoutineEditScreen(vm: NookViewModel, id: String?) {
    val routinen by vm.routinen.collectAsState()
    val bots by vm.bots.collectAsState()
    val r = routinen.firstOrNull { it.id == id }
    var name by remember { mutableStateOf(r?.name ?: "") }
    var bot by remember { mutableStateOf(r?.bot ?: bots.firstOrNull { it.kapitaen }?.id ?: "") }
    var zeit by remember { mutableStateOf(r?.zeit ?: "07:45") }
    var tage by remember { mutableStateOf(r?.tage?.toSet() ?: setOf(1, 2, 3, 4, 5)) }
    var einmalig by remember { mutableStateOf(r?.einmalig ?: false) }
    var datum by remember { mutableStateOf(r?.datum?.takeIf { it.isNotBlank() } ?: java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.GERMANY).format(java.util.Date(System.currentTimeMillis() + 86_400_000))) }
    val datumOk = !einmalig || Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(datum)
    var auftrag by remember { mutableStateOf(r?.auftrag ?: "") }
    val zeitOk = Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(zeit)
    Column(Modifier.fillMaxSize()) {
        TopBar(if (r == null) "Neue Routine" else r.name, { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Field("Name", name) { name = it }
            Section("Wer?")
            Row { bots.forEach { b -> Chip(b.name, bot == b.id) { bot = b.id } } }
            Section("Wann?")
            Row { Chip("Wiederkehrend", !einmalig) { einmalig = false }; Chip("Einmalig", einmalig) { einmalig = true } }
            Row {
                if (einmalig) Field("Datum (JJJJ-MM-TT)", datum, modifier = Modifier.width(180.dp).padding(end = 10.dp)) { datum = it.take(10) }
                Field("Uhrzeit (HH:MM)", zeit, modifier = Modifier.width(160.dp)) { zeit = it.take(5) }
            }
            if (!zeitOk || !datumOk) Hint("Format 2026-10-20 und 07:45")
            if (!einmalig) Row(Modifier.padding(top = 6.dp)) {
                TAGE.forEachIndexed { i, t -> Chip(t, (i + 1) in tage) { tage = if ((i + 1) in tage) tage - (i + 1) else tage + (i + 1) } }
            }
            if (r?.sitzung?.isNotBlank() == true) Hint("Das Ergebnis kommt in die Unterhaltung, aus der die Routine angelegt wurde.", Modifier.padding(top = 6.dp))
            Section("Auftrag", "So, als würdest du ihn dem Bot schreiben. Er bekommt Datum und Uhrzeit dazu.")
            Field("z. B. Was steht heute in meinem Kalender, und was ist in Slack für mich los? Kurz, als Liste.", auftrag, lines = 8) { auftrag = it }
            Row(Modifier.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                MainButton("Speichern", enabled = name.isNotBlank() && auftrag.isNotBlank() && zeitOk && datumOk && (einmalig || tage.isNotEmpty()) && bot.isNotBlank()) {
                    val neu = Routine(r?.id ?: "", name, bot, auftrag, zeit, tage.sorted(), if (einmalig) true else r?.aktiv ?: true, r?.letzterLauf ?: 0,
                        if (einmalig) datum else "", r?.sitzung ?: "")
                    vm.saveRoutinen(if (r == null) routinen + neu else routinen.map { if (it.id == r.id) neu else it }) { vm.back() }
                }
                Spacer(Modifier.width(10.dp))
                if (r != null) SideButton("Jetzt ausführen") { vm.runRoutine(r.id) }
            }
            if (r != null) TextButton({ vm.saveRoutinen(routinen.filterNot { it.id == r.id }) { vm.back() } }) { Text("Routine löschen", color = A.accent) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Datenbanken ───────────────────────────────────────────────────

@Composable
fun DatenbankenScreen(vm: NookViewModel) {
    val dbs by vm.datenbanken.collectAsState()
    val ctx = LocalContext.current
    var importText by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            if (bytes.size > 2_000_000) error("Datei zu groß")
            // Echo-Vorlage als Zip (vorlage.json darin) oder JSON
            if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                ZipInputStream(bytes.inputStream()).use { z ->
                    generateSequence { z.nextEntry }.firstOrNull { it.name.endsWith("vorlage.json") } ?: error("Keine vorlage.json im Zip")
                    z.readBytes().decodeToString()
                }
            } else bytes.decodeToString()
        }.onSuccess { importText = it }.onFailure { vm.say(it.message) }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Datenbank.listJson(dbs).toString(2).toByteArray()) }
        }.onSuccess { vm.say("Exportiert") }.onFailure { vm.say(it.message) }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Datenbanken", { vm.back() }) { MainButton("+ Neu") { vm.saveDatenbanken(dbs + Datenbank("Neue Datenbank", "heroku", "", "", emptyList())) { vm.go(Route.DatenbankEdit(dbs.size)) } } }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item {
                Hint("Hier legst du fest, was die Bots über deine Datenbanken wissen: wofür sie da sind, Hinweise zum Schema und feste Abfragen. " +
                    "Bots mit Zugriffen bekommen das als Wissen und arbeiten die Abfragen ab – sie passen sie an, bauen aber nicht selbst neue.",
                    Modifier.padding(bottom = 8.dp))
                Row(Modifier.padding(bottom = 8.dp)) {
                    SideButton("Importieren") { pick.launch(arrayOf("application/json", "application/zip", "application/octet-stream", "*/*")) }
                    Spacer(Modifier.width(8.dp))
                    SideButton("Exportieren") { export.launch("nookbot-datenbanken.json") }
                }
            }
            if (dbs.isEmpty()) item { Hint("Noch keine Datenbank beschrieben. Tipp: die Echo-Vorlage „Kennzahlen“ importieren.", Modifier.padding(vertical = 8.dp)) }
            items(dbs.indices.toList()) { i ->
                val d = dbs[i]
                Card(Modifier.padding(vertical = 5.dp), onClick = { vm.go(Route.DatenbankEdit(i)) }) {
                    Text(d.titel.ifBlank { d.name }, color = P.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("home-mcp: ${d.name} · ${d.abfragen.size} Abfragen", color = P.mut, fontSize = 12.sp)
                    if (d.beschreibung.isNotBlank()) Text(d.beschreibung, color = P.fg2, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
    importText?.let { t ->
        var dbName by remember { mutableStateOf("heroku") }
        AlertDialog(onDismissRequest = { importText = null }, containerColor = P.card,
            title = { Text("Importieren", color = P.fg) },
            text = {
                Column {
                    Text("Echo-Vorlagen: ihre SQL-Quellen werden zu Abfragen. Bei welcher Datenbank in home-mcp laufen sie?", color = P.fg2, fontSize = 14.sp)
                    Field("Name in home-mcp", dbName) { dbName = it }
                }
            },
            confirmButton = {
                TextButton({
                    runCatching { Datenbank.import(t, dbName.trim()) }.onSuccess { neu ->
                        vm.saveDatenbanken(dbs + neu)
                        vm.say("${neu.sumOf { it.abfragen.size }} Abfragen importiert")
                    }.onFailure { vm.say(it.message) }
                    importText = null
                }) { Text("Importieren", color = A.accent) }
            },
            dismissButton = { TextButton({ importText = null }) { Text("Abbrechen", color = P.fg2) } })
    }
}

@Composable
fun DatenbankEditScreen(vm: NookViewModel, index: Int) {
    val dbs by vm.datenbanken.collectAsState()
    val d = dbs.getOrNull(index) ?: run { Column { TopBar("Datenbank", { vm.back() }) }; return }
    var titel by remember(index) { mutableStateOf(d.titel) }
    var name by remember(index) { mutableStateOf(d.name) }
    var beschreibung by remember(index) { mutableStateOf(d.beschreibung) }
    var notizen by remember(index) { mutableStateOf(d.notizen) }
    var abfragen by remember(index) { mutableStateOf(d.abfragen) }
    var offen by remember { mutableStateOf<Int?>(null) }
    var schema by remember { mutableStateOf<String?>(null) }
    var loeschen by remember { mutableStateOf(false) }

    fun save(back: Boolean) = vm.saveDatenbanken(dbs.mapIndexed { i, x -> if (i == index) Datenbank(titel, name.trim(), beschreibung, notizen, abfragen) else x }) {
        if (back) vm.back()
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(titel.ifBlank { "Datenbank" }, { vm.back() }, sub = "${abfragen.size} Abfragen")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Field("Titel (z. B. Analytics)", titel) { titel = it }
            Field("Name in home-mcp (z. B. heroku)", name) { name = it }
            Section("Beschreibung", "Wofür ist die Datenbank da, was steckt in den wichtigsten Tabellen?")
            Field("Beschreibung", beschreibung, lines = 6) { beschreibung = it }
            Section("Schema-Notizen", "Spaltenbedeutungen, typische Verknüpfungen, Fallstricke (z. B. Zeitzonen, Testkonten ausschließen).")
            Field("Notizen", notizen, lines = 10) { notizen = it }
            SideButton("Schema vom Pi laden") { vm.loadSchema(name) { schema = it } }

            Section("Feste Abfragen", "Parameter schreibst du im SQL als :name und hier als „name=Standardwert, …“.")
            abfragen.forEachIndexed { i, q ->
                Card(Modifier.padding(vertical = 5.dp), onClick = { offen = if (offen == i) null else i }) {
                    Text(q.name.ifBlank { "Neue Abfrage" }, color = P.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (offen != i) Text(q.zweck, color = P.mut, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (offen == i) {
                        Field("Name", q.name) { v -> abfragen = abfragen.mapIndexed { j, x -> if (j == i) x.copy(name = v) else x } }
                        Field("Zweck – wann passt sie?", q.zweck, lines = 4) { v -> abfragen = abfragen.mapIndexed { j, x -> if (j == i) x.copy(zweck = v) else x } }
                        Field("Parameter (von=current_date - 7, …)", q.parameter) { v -> abfragen = abfragen.mapIndexed { j, x -> if (j == i) x.copy(parameter = v) else x } }
                        Field("SQL", q.sql, lines = 18, mono = true) { v -> abfragen = abfragen.mapIndexed { j, x -> if (j == i) x.copy(sql = v) else x } }
                        TextButton({ abfragen = abfragen.filterIndexed { j, _ -> j != i }; offen = null }) { Text("Abfrage entfernen", color = A.accent) }
                    }
                }
            }
            SideButton("+ Abfrage") { abfragen = abfragen + Abfrage("", "", "", ""); offen = abfragen.size - 1 }
            Row(Modifier.padding(vertical = 20.dp)) { MainButton("Speichern") { save(true) } }
            TextButton({ loeschen = true }) { Text("Datenbank-Beschreibung löschen", color = A.accent) }
            Spacer(Modifier.height(24.dp))
        }
    }
    schema?.let { t ->
        AlertDialog(onDismissRequest = { schema = null }, containerColor = P.card,
            title = { Text("Schema „$name“", color = P.fg) },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { SelectionContainer { Text(t, color = P.fg2, fontSize = 11.sp, fontFamily = FontFamily.Monospace) } } },
            confirmButton = { TextButton({ notizen = (notizen.trim() + "\n\n" + t.take(20000)).trim(); schema = null }) { Text("In Notizen übernehmen", color = A.accent) } },
            dismissButton = { TextButton({ schema = null }) { Text("Schließen", color = P.fg2) } })
    }
    if (loeschen) AlertDialog(onDismissRequest = { loeschen = false }, containerColor = P.card,
        title = { Text("„${titel}“ löschen?", color = P.fg) },
        text = { Text("Beschreibung und Abfragen werden entfernt. Die Datenbank selbst bleibt unberührt.", color = P.fg2) },
        confirmButton = { TextButton({ loeschen = false; vm.saveDatenbanken(dbs.filterIndexed { i, _ -> i != index }) { vm.back() } }) { Text("Löschen", color = A.accent) } },
        dismissButton = { TextButton({ loeschen = false }) { Text("Abbrechen", color = P.fg2) } })
}

// ── Wissen (global) ───────────────────────────────────────────────

@Composable
fun WissenScreen(vm: NookViewModel) {
    val files by vm.wissenGlobal.collectAsState()
    val bots by vm.bots.collectAsState()
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) uploadWissenDatei(ctx, vm, "global", uri)
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Wissen", { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Hint("Textdateien (.md, .txt, .csv, .json, .sql) oder PDFs (der Pi zieht den Text heraus), die alle Bots nachlesen können – z. B. Infos über dich, Abläufe, Definitionen. " +
                "Max. 300 KB je Datei. Wissen nur für einen Bot: in seinen Einstellungen.")
            Section("Für alle Bots")
            WissenList(files) { vm.deleteWissen("global", it) }
            SideButton("Datei hinzufügen") { pick.launch(WISSEN_MIME) }
            Section("Nur für einzelne Bots")
            bots.forEach { b ->
                Row(Modifier.fillMaxWidth().clickable { vm.go(Route.BotEdit(b.id)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BotFigure(b, 28.dp)
                    Text(b.name, color = P.fg, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
                    Text("${b.wissen.size} Dateien", color = P.mut, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Zugriffe ──────────────────────────────────────────────────────

@Composable
fun ZugriffeScreen(vm: NookViewModel) {
    val z by vm.zugriffe.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar("Zugriffe", { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Hint("Bots mit Zugriffen arbeiten über home-mcp mit dem Bot „nookbot“. Was der darf, legst du auf der Verwaltungsseite fest " +
                "(ssh -L 8791:127.0.0.1:8791 raspi → http://127.0.0.1:8791): Konten, Kalender „nur Hauptkalender“, Slack-Kanäle, Datenbanken. " +
                "Schreiben geht immer nur über deine Freigabe.")
            if (z == null) Hint("Lade …", Modifier.padding(top = 12.dp))
            z?.let { o ->
                Section("Bots")
                val bs = o.optJSONArray("bots")
                for (i in 0 until (bs?.length() ?: 0)) {
                    val b = bs!!.getJSONObject(i)
                    Text("${b.optString("name")}: " + if (b.optBoolean("zugriffe")) "mit Zugriffen" else "nur Web + Wissen", color = P.fg, fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 2.dp))
                }
                Section("Konten und Quellen")
                Text(o.optString("konten"), color = P.fg2, fontSize = 13.sp)
                Section("Werkzeuge")
                val w = o.optJSONArray("werkzeuge")
                val names = (0 until (w?.length() ?: 0)).map { w!!.getString(it) }
                val gruppen = names.groupBy {
                    when {
                        it.startsWith("gitlab") -> "GitLab (lesen)"; it.startsWith("db_") -> "Datenbank (lesen)"; it.startsWith("slack") -> "Slack (lesen)"
                        it in listOf("termine", "kalender_liste") -> "Kalender lesen"; it.startsWith("termin_") -> "Kalender schreiben (mit Freigabe)"
                        it == "entwurf_anlegen" -> "Mail-Entwürfe (mit Freigabe)"; it.startsWith("mail") || it.startsWith("thread") -> "Mail lesen"
                        else -> "Sonstiges"
                    }
                }
                gruppen.forEach { (g, l) ->
                    Text("✓ $g", color = P.fg, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                    Text(l.joinToString(", "), color = P.mut, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Einstellungen ─────────────────────────────────────────────────

@Composable
fun SettingsScreen(vm: NookViewModel) {
    val s = vm.settings
    val theme by vm.theme.collectAsState()
    val g by vm.guthaben.collectAsState()
    val listen by vm.listen.collectAsState()
    var server by remember { mutableStateOf(s.serverUrl) }
    var mcp by remember { mutableStateOf(s.mcpUrl) }
    var token by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Einstellungen", { if (s.configured) vm.back() })
        Column(Modifier.padding(horizontal = 20.dp)) {
            Section("Design")
            Row {
                Chip("System", theme == "system") { vm.setTheme("system") }
                Chip("Hell", theme == "hell") { vm.setTheme("hell") }
                Chip("Dunkel", theme == "dunkel") { vm.setTheme("dunkel") }
            }

            Section("Schriftgröße")
            val scale by vm.textScale.collectAsState()
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf("Klein" to 0.95f, "Normal" to 1.0f, "Groß" to 1.1f, "Größer" to 1.2f, "Sehr groß" to 1.35f).forEach { (n, f) ->
                    Chip(n, kotlin.math.abs(scale - f) < 0.01f) { vm.setTextScale(f) }
                }
            }

            Section("Sprachnotizen", "Werden auf dem Handy in Text umgewandelt (whisper.cpp wie in Echo) – das Audio verlässt das Handy nicht.")
            val ctx = LocalContext.current
            val fortschritt by vm.modellFortschritt.collectAsState()
            de.hoefer.nookbot.whisper.WhisperModel.entries.forEach { m ->
                val da = remember(fortschritt) { de.hoefer.nookbot.whisper.WhisperModels.isInstalled(ctx, m) }
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.label, color = P.fg, fontSize = 14.sp)
                        Text(m.hint, color = P.mut, fontSize = 12.sp)
                    }
                    if (da) Text("✓ geladen", color = A.green, fontSize = 13.sp)
                    else if (fortschritt != null) Text("${((fortschritt ?: 0f) * 100).toInt()} %", color = P.fg2, fontSize = 13.sp)
                    else SideButton("Laden") { vm.modellLaden(m) }
                }
            }

            g?.let {
                Section("Ollama Pro")
                Text("${"%.2f".format(it.rest)} $ von ${"%.0f".format(it.gesamt)} $ übrig (${it.genutztProzent} % genutzt) · neu ab ${it.bis}", color = P.fg2, fontSize = 14.sp)
            }
            ClaudeBereich(vm)
            GesundheitBereich(vm)
            AusspracheBereich(vm)

            Section("Pi (NookBot-Adapter)")
            Field("Adresse", server) { server = it }
            Field(if (s.chatToken.isNotBlank()) "Chat-Token (gespeichert – leer lassen zum Behalten)" else "Chat-Token (nb_…)", token, secret = true) { token = it }
            Section("Freigaben (home-mcp)")
            Field("Adresse", mcp) { mcp = it }
            Field(if (s.appKey.isNotBlank()) "App-Schlüssel (gespeichert – leer lassen zum Behalten)" else "App-Schlüssel (hmk_…, Art „für die App“)", key, secret = true) { key = it }
            Hint("Beide Schlüssel liegen mit dem Android-Keystore verschlüsselt auf dem Gerät. Erreichbar ist alles nur im Tailnet – Tailscale muss an sein.",
                Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 16.dp)) {
                MainButton("Speichern") {
                    vm.saveSettings(server, token.ifBlank { null }, mcp, key.ifBlank { null })
                    token = ""; key = ""
                    if (vm.settings.configured && vm.stack.value.size == 1) vm.home()
                }
                Spacer(Modifier.width(10.dp))
                SideButton(if (testing) "Teste …" else "Verbindung testen") {
                    vm.saveSettings(server, token.ifBlank { null }, mcp, key.ifBlank { null })
                    token = ""; key = ""; testing = true; result = null
                    vm.test { result = it; testing = false }
                }
            }
            result?.let { Text(it, color = P.fg2, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }

            Section("Benachrichtigungen", "NookBot schaut alle ~5 Minuten beim Pi nach (im Tiefschlaf des Handys etwas seltener): " +
                "Antwort fertig, Freigabe wartet, Routine oder Runde fertig. Keine Dauerverbindung, kaum Akku.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Im Hintergrund nachsehen", color = P.fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(listen, { vm.setListen(it) }, colors = SwitchDefaults.colors(checkedTrackColor = A.accent))
            }
            if (listen) SideButton("Test-Benachrichtigung") { vm.testPush() }

            Text("NookBot ${BuildConfig.VERSION_NAME}", color = P.mut, fontSize = 12.sp, modifier = Modifier.padding(top = 28.dp, bottom = 24.dp))
        }
    }
}

// ── Erinnerungen ──────────────────────────────────────────────────

@Composable
fun ErinnerungenScreen(vm: NookViewModel) {
    val list by vm.erinnerungen.collectAsState()
    var edit by remember { mutableStateOf<de.hoefer.nookbot.data.Erinnerung?>(null) }
    var neu by remember { mutableStateOf(false) }
    val offen = list.filter { !it.erledigt }
    Column(Modifier.fillMaxSize()) {
        TopBar("Erinnerungen", { vm.back() }) { MainButton("+ Neu") { neu = true } }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item {
                Hint("Ein Hinweis zur Uhrzeit – das Handy meldet sich pünktlich. Die Bots stellen sie auch selbst („Erinnere mich morgen um 9 an …“). " +
                    "Soll ein Bot zu einer Zeit etwas erledigen, ist das eine Routine.", Modifier.padding(bottom = 10.dp))
            }
            if (offen.isEmpty()) item { Hint("Keine offenen Erinnerungen.", Modifier.padding(vertical = 8.dp)) }
            items(offen, key = { it.id }) { e ->
                Card(Modifier.padding(vertical = 5.dp), onClick = { edit = e }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔔", fontSize = 18.sp)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(e.text, color = P.fg, fontSize = 15.sp)
                            Text(e.wann.replace("T", " · ").let { w -> datumText(w.take(10)) + w.drop(10) } +
                                (if (e.wiederholungText.isNotBlank()) " · ${e.wiederholungText}" else "") + " · ${vm.bot(e.bot)?.name ?: ""}",
                                color = P.mut, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
    if (neu || edit != null) ErinnerungDialog(edit, onClose = { neu = false; edit = null }, onDelete = { e ->
        vm.saveErinnerungen(list.filterNot { it.id == e.id }); neu = false; edit = null
    }) { e ->
        vm.saveErinnerungen(if (list.any { it.id == e.id && e.id.isNotBlank() }) list.map { if (it.id == e.id) e else it } else list + e)
        neu = false; edit = null
    }
}

@Composable
private fun ErinnerungDialog(e: de.hoefer.nookbot.data.Erinnerung?, onClose: () -> Unit, onDelete: (de.hoefer.nookbot.data.Erinnerung) -> Unit,
                             onSave: (de.hoefer.nookbot.data.Erinnerung) -> Unit) {
    val morgen = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.GERMANY).format(java.util.Date(System.currentTimeMillis() + 86_400_000))
    var text by remember { mutableStateOf(e?.text ?: "") }
    var datum by remember { mutableStateOf(e?.wann?.take(10) ?: morgen) }
    var zeit by remember { mutableStateOf(e?.wann?.drop(11) ?: "09:00") }
    var w by remember { mutableStateOf(e?.wiederholung ?: "") }
    val ok = text.isNotBlank() && Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(datum) && Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(zeit)
    AlertDialog(onDismissRequest = onClose, containerColor = P.card,
        title = { Text(if (e == null) "Neue Erinnerung" else "Erinnerung", color = P.fg) },
        text = {
            Column {
                Field("Woran?", text, lines = 3) { text = it }
                Row {
                    Field("Datum (JJJJ-MM-TT)", datum, modifier = Modifier.weight(1f).padding(end = 8.dp)) { datum = it.take(10) }
                    Field("Uhrzeit", zeit, modifier = Modifier.width(110.dp)) { zeit = it.take(5) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
                    listOf("" to "einmalig", "taeglich" to "täglich", "werktags" to "werktags", "woechentlich" to "wöchentlich", "monatlich" to "monatlich")
                        .forEach { (k, n) -> Chip(n, w == k) { w = k } }
                }
                if (e != null) TextButton({ onDelete(e) }) { Text("Löschen", color = A.accent) }
            }
        },
        confirmButton = {
            TextButton({ onSave(de.hoefer.nookbot.data.Erinnerung(e?.id ?: "", text.trim(), "${datum}T$zeit", w, e?.bot ?: "", false)) }, enabled = ok) {
                Text("Speichern", color = A.accent)
            }
        },
        dismissButton = { TextButton(onClose) { Text("Abbrechen", color = P.fg2) } })
}

// ── Ältere Unterhaltungen ─────────────────────────────────────────

@Composable
fun ArchivScreen(vm: NookViewModel) {
    val sitz by vm.sitzungen.collectAsState()
    val bots by vm.bots.collectAsState()
    val haupt = bots.map { it.sitzung }.toSet()
    val alt = sitz.filter { (it.art == "chat" || it.art == "archiv" || it.art == "routine" || it.art == "auftrag") && it.id !in haupt }
    Column(Modifier.fillMaxSize()) {
        TopBar("Ältere Unterhaltungen", { vm.back() })
        LazyColumn(Modifier.weight(1f)) {
            if (alt.isEmpty()) item { Hint("Nichts im Archiv.", Modifier.padding(20.dp)) }
            items(alt, key = { it.id }) { s ->
                Row(Modifier.fillMaxWidth().clickable { vm.openChat(s.id) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BotFigure(vm.bot(s.bot), 38.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(s.titel.ifBlank { vm.bot(s.bot)?.name ?: "Unterhaltung" }, color = P.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((if (s.art == "auftrag") "Aufträge aus dem Team · " else "") + s.letzte, color = P.fg2, fontSize = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    Text(whenText(s.aktualisiert), color = P.mut, fontSize = 12.sp)
                }
            }
        }
    }
}


// ── Abläufe ───────────────────────────────────────────────────────

@Composable
fun AblaeufeScreen(vm: NookViewModel) {
    val abl by vm.ablaeufe.collectAsState()
    var aendern by remember { mutableStateOf<de.hoefer.nookbot.data.Ablauf?>(null) }
    var planen by remember { mutableStateOf<de.hoefer.nookbot.data.Ablauf?>(null) }
    var loeschen by remember { mutableStateOf<de.hoefer.nookbot.data.Ablauf?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Abläufe", { vm.back() })
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item { Hint("Im Chat ⋮ → „Als Ablauf speichern“ oder dem Bot sagen „mach das ab jetzt immer so“. Erst nach deinem OK wird ein Ablauf " +
                "zum Skill – dann reicht „Mach den Wochenbericht“, oder du planst ihn als Routine.", Modifier.padding(bottom = 10.dp)) }
            if (abl.isEmpty()) item { Hint("Noch keine Abläufe.", Modifier.padding(vertical = 8.dp)) }
            items(abl.sortedWith(compareBy({ !it.entwurf }, { it.name })), key = { it.id }) { a ->
                Card(Modifier.padding(vertical = 5.dp), border = if (a.entwurf) A.amber.copy(alpha = 0.6f) else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Badge(if (a.entwurf) "ENTWURF" else "AKTIV", if (a.entwurf) A.amber else A.green)
                        Text(a.name, color = P.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp).weight(1f))
                        Text(if (a.fuer == "global") "alle Bots" else vm.bot(a.fuer)?.name ?: a.fuer, color = P.mut, fontSize = 12.sp)
                    }
                    if (a.beschreibung.isNotBlank()) Text(a.beschreibung, color = P.fg2, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    Text(markdown(a.schritte, P.fg2), color = P.fg, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 8, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        if (a.entwurf) MainButton("Speichern") { vm.ablaufAendern(a.id, org.json.JSONObject().put("status", "aktiv"), "Ablauf gespeichert") }
                        else { MainButton("Jetzt") { vm.ablaufJetzt(a.id) }; Spacer(Modifier.width(8.dp)); SideButton("Planen") { planen = a } }
                        Spacer(Modifier.width(8.dp))
                        SideButton("Ändern") { aendern = a }
                        TextButton({ loeschen = a }) { Text("Löschen", color = P.mut) }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    aendern?.let { a -> AblaufDialog(vm, a) { aendern = null } }
    planen?.let { a ->
        var zeit by remember(a.id) { mutableStateOf("08:00") }
        var tage by remember(a.id) { mutableStateOf(setOf(1)) }
        val ok = Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(zeit) && tage.isNotEmpty()
        AlertDialog(onDismissRequest = { planen = null }, containerColor = P.card,
            title = { Text("„${a.name}“ planen", color = P.fg) },
            text = {
                Column {
                    Field("Uhrzeit (HH:MM)", zeit, modifier = Modifier.width(160.dp)) { zeit = it.take(5) }
                    Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState())) {
                        TAGE.forEachIndexed { i, t -> Chip(t, (i + 1) in tage) { tage = if ((i + 1) in tage) tage - (i + 1) else tage + (i + 1) } }
                    }
                    Hint("Wird eine Routine – das Ergebnis kommt in die Unterhaltung des Bots.")
                }
            },
            confirmButton = { TextButton({ vm.ablaufPlanen(a.id, zeit, tage.sorted()) { planen = null } }, enabled = ok) { Text("Als Routine anlegen", color = A.accent) } },
            dismissButton = { TextButton({ planen = null }) { Text("Abbrechen", color = P.fg2) } })
    }
    loeschen?.let { a ->
        AlertDialog(onDismissRequest = { loeschen = null }, containerColor = P.card, title = { Text("„${a.name}“ löschen?", color = P.fg) },
            text = { Text("Die Bots kennen den Ablauf danach nicht mehr. Routinen, die ihn nennen, bleiben – bitte dort mit löschen.", color = P.fg2) },
            confirmButton = { TextButton({ vm.ablaufLoeschen(a.id); loeschen = null }) { Text("Löschen", color = A.accent) } },
            dismissButton = { TextButton({ loeschen = null }) { Text("Abbrechen", color = P.fg2) } })
    }
}

// ── Gedächtnis ────────────────────────────────────────────────────

@Composable
fun GedaechtnisScreen(vm: NookViewModel) {
    val ged by vm.gedaechtnis.collectAsState()
    val bots by vm.bots.collectAsState()
    var neu by remember { mutableStateOf("") }
    var fuer by remember { mutableStateOf("global") }
    var bearbeiten by remember { mutableStateOf<de.hoefer.nookbot.data.Merk?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Gedächtnis", { vm.back() })
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item {
                Hint("Was sich die Bots dauerhaft merken. Sie merken sich nur, was du selbst sagst – nie etwas aus Mails, Slack oder Webseiten. " +
                    "Tippen zum Ändern.", Modifier.padding(bottom = 8.dp))
                Field("Neu, z. B. „Zahlen immer mit Vorjahresvergleich“", neu, lines = 2) { neu = it }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("Alle Bots", fuer == "global") { fuer = "global" }
                    bots.forEach { b -> Chip(b.name, fuer == b.id) { fuer = b.id } }
                }
                MainButton("Merken", enabled = neu.isNotBlank()) { vm.merken(neu, fuer); neu = "" }
            }
            val gruppen = listOf("global" to "Über dich – alle Bots") + bots.map { it.id to it.name }
            gruppen.forEach { (id, titel) ->
                val l = ged.filter { it.fuer == id }
                item(key = "h-$id") { Section(titel + if (l.isEmpty()) " · leer" else "") }
                items(l, key = { it.id }) { e ->
                    Row(Modifier.fillMaxWidth().clickable { bearbeiten = e }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.text, color = P.fg, fontSize = 14.sp)
                            Text((if (e.von == "app") "von dir" else "von ${vm.bot(e.von)?.name ?: e.von}") + (if (e.quelle == "direkt") " · selbst notiert" else "") +
                                " · " + whenText(e.ts), color = P.mut, fontSize = 11.sp)
                        }
                        TextButton({ vm.vergessen(e.id) }) { Text("✕", color = P.mut) }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    bearbeiten?.let { e ->
        var t by remember(e.id) { mutableStateOf(e.text) }
        AlertDialog(onDismissRequest = { bearbeiten = null }, containerColor = P.card, title = { Text("Eintrag ändern", color = P.fg) },
            text = { Field("Text", t, lines = 4) { t = it } },
            confirmButton = { TextButton({ vm.gedaechtnisAendern(e, t); bearbeiten = null }, enabled = t.isNotBlank()) { Text("Speichern", color = A.accent) } },
            dismissButton = { TextButton({ bearbeiten = null }) { Text("Abbrechen", color = P.fg2) } })
    }
}


// ── Claude (Anthropic) in den Einstellungen ───────────────────────

@Composable
private fun ClaudeBereich(vm: NookViewModel) {
    val c by vm.claude.collectAsState()
    Section("Claude (Anthropic)")
    val o = c
    if (o == null || !o.optBoolean("eingerichtet")) {
        Hint("Nicht eingerichtet. Auf dem Pi: sudo bash 7-claude.sh (fragt den API-Schlüssel verdeckt ab). Danach ist Claude je Bot als Modell wählbar.")
        return
    }
    val st = o.optJSONObject("stand")
    val auf = o.optJSONObject("aufladung")
    val admin = o.optBoolean("kosten")
    val heute = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.GERMANY).format(java.util.Date()) }
    var start by remember(o) { mutableStateOf(st?.optDouble("usd")?.let { "%.2f".format(java.util.Locale.US, it) } ?: "") }
    var datum by remember(o) { mutableStateOf(st?.optString("datum")?.takeIf { it.isNotBlank() } ?: heute) }
    var aufUsd by remember(o) { mutableStateOf(auf?.optDouble("usd")?.let { "%.2f".format(java.util.Locale.US, it) } ?: "") }
    var aufTag by remember(o) { mutableStateOf(auf?.optInt("tag")?.takeIf { it > 0 }?.toString() ?: "") }
    var bearbeiten by remember { mutableStateOf(false) }

    if (o.has("rest_usd")) Text("ca. ${"%.2f".format(o.optDouble("rest_usd"))} $ übrig", color = A.green, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    if (o.has("rest_usd")) Text("Start ${"%.2f".format(st?.optDouble("usd") ?: 0.0)} $ am ${datumText(st?.optString("datum") ?: "")}" +
        (if (o.optDouble("aufgeladen_usd", 0.0) > 0) " + ${"%.2f".format(o.optDouble("aufgeladen_usd"))} $ aufgeladen" else "") +
        " − ${"%.2f".format(o.optDouble("seit_stand_usd"))} $ verbraucht", color = P.fg2, fontSize = 13.sp)
    if (admin) Text("Diesen Monat laut Anthropic: ${"%.2f".format(o.optDouble("monat_usd", 0.0))} $", color = P.fg2, fontSize = 13.sp)
    Text("NookBot-Verbrauch: heute ${"%.2f".format(o.optDouble("eigen_heute_usd", 0.0))} $ · diesen Monat ${"%.2f".format(o.optDouble("eigen_monat_usd", 0.0))} $",
        color = P.fg2, fontSize = 13.sp)
    if (auf != null && auf.optDouble("usd", 0.0) > 0) Text("Aufladung: ${"%.2f".format(auf.optDouble("usd"))} $ jeden Monat am ${auf.optInt("tag")}." +
        (o.optString("naechste_aufladung").takeIf { it.isNotBlank() }?.let { " · nächste am ${datumText(it)}" } ?: ""), color = P.fg2, fontSize = 13.sp)

    if (!bearbeiten) {
        TextButton({ bearbeiten = true }) { Text(if (st == null) "Guthaben und Aufladung eintragen" else "Guthaben/Aufladung ändern", color = A.accent) }
        Hint(if (admin) "Ausgaben laut Anthropic (Admin-Schlüssel)." else "Gezählt wird, was die Bots über den Pi an Claude schicken (Listenpreise). " +
            "Nutzt du denselben Schlüssel woanders, fehlt das hier – dann ab und zu den Startbetrag neu eintragen.")
        return
    }
    Hint("Startbetrag = Guthaben laut Console (Billing) an einem Tag. Ab dann zieht der Pi den Verbrauch ab und rechnet jede Aufladung dazu.")
    Row {
        Field("Startbetrag ($)", start, number = true, modifier = Modifier.width(150.dp).padding(end = 8.dp)) { start = it.replace(',', '.') }
        Field("am (JJJJ-MM-TT)", datum, modifier = Modifier.width(170.dp)) { datum = it.take(10) }
    }
    Row {
        Field("Aufladung ($)", aufUsd, number = true, modifier = Modifier.width(150.dp).padding(end = 8.dp)) { aufUsd = it.replace(',', '.') }
        Field("am Tag (1–31)", aufTag, number = true, modifier = Modifier.width(170.dp)) { aufTag = it.filter { ch -> ch.isDigit() }.take(2) }
    }
    Hint("Aufladung leer lassen, wenn nichts automatisch dazukommt.")
    val datumOk = Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(datum)
    Row(Modifier.padding(top = 6.dp)) {
        MainButton("Speichern", enabled = start.toDoubleOrNull() != null && datumOk && (aufUsd.isBlank() || (aufUsd.toDoubleOrNull() != null && aufTag.toIntOrNull() in 1..31))) {
            vm.claudeStand(start.toDouble(), datum, aufUsd.toDoubleOrNull(), aufTag.toIntOrNull())
            bearbeiten = false
        }
        Spacer(Modifier.width(8.dp))
        SideButton("Abbrechen") { bearbeiten = false }
    }
}


// ── Gesundheit (Health Connect) in den Einstellungen ──────────────

@Composable
private fun GesundheitBereich(vm: NookViewModel) {
    val g by vm.gesundheit.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.gesundheitLaden() }
    val anfrage = rememberLauncherForActivityResult(androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()) { darf ->
        vm.gesundheitErlaubt(darf)
    }
    Section("Gesundheit (Health Connect)", "Liest Schritte, Schlaf, Puls, Training, Gewicht usw. – alles, was Uhr, Fitbit, Samsung Health & Co. in " +
        "Health Connect schreiben. Zum Pi gehen nur Tageswerte (nur NookBot, nur im Tailnet). Welche Bots sie sehen, stellst du je Bot unter Zugriffe ein.")
    val o = g ?: run { Hint("prüfe …"); return }
    if (!o.optBoolean("verfuegbar")) {
        Hint("Health Connect ist auf diesem Handy nicht verfügbar (ab Android 14 eingebaut, davor als App aus dem Play Store).")
        return
    }
    val erlaubt = o.optInt("erlaubt")
    val pi = o.optJSONObject("pi")
    Text(if (erlaubt == 0) "Noch kein Zugriff erlaubt." else "$erlaubt Datenarten erlaubt" + if (o.optBoolean("hintergrund")) " · Abgleich 2× täglich im Hintergrund" else " · Abgleich beim Öffnen/auf Knopfdruck",
        color = P.fg, fontSize = 14.sp)
    if (o.optLong("stand") > 0) Text("Zuletzt abgeglichen: " + java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(java.util.Date(o.optLong("stand"))),
        color = P.fg2, fontSize = 13.sp)
    if (pi != null && pi.optInt("tage") > 0) {
        Text("Auf dem Pi: ${pi.optInt("tage")} Tage (${datumText(pi.optString("von"))} – ${datumText(pi.optString("bis"))})", color = P.fg2, fontSize = 13.sp)
        val q = pi.optJSONObject("quellen")
        if (q != null && q.length() > 0) Text("Quellen: " + q.keys().asSequence().sortedByDescending { q.optInt(it) }.joinToString(", ") { quelleName(it) },
            color = P.fg2, fontSize = 13.sp)
    }
    Row(Modifier.padding(top = 8.dp)) {
        MainButton(if (erlaubt == 0) "Zugriff erlauben" else "Zugriffe ändern") { anfrage.launch(de.hoefer.nookbot.Gesundheit.BERECHTIGUNGEN) }
        Spacer(Modifier.width(8.dp))
        if (erlaubt > 0) SideButton("Jetzt abgleichen") { vm.gesundheitAbgleichen() }
    }
    Hint("Im Health-Connect-Dialog am besten „Alle zulassen“ – plus „Im Hintergrund“ und „Verlauf“, dann gleicht NookBot selbst ab und holt beim ersten Mal 90 Tage.",
        Modifier.padding(top = 6.dp))
}

/** Bekannte Apps lesbar benennen – sonst der Paketname. */
fun quelleName(p: String): String = when {
    p.contains("egym", true) -> "EGYM"
    p == "com.google.android.apps.fitness" -> "Google Fit"
    p.startsWith("com.fitbit") -> "Fitbit"
    p.startsWith("com.sec.android.app.shealth") -> "Samsung Health"
    p.startsWith("com.garmin") -> "Garmin"
    p.startsWith("com.ouraring") -> "Oura"
    p.startsWith("com.withings") -> "Withings"
    p.startsWith("com.google.android.apps.healthdata") -> "Health Connect"
    p == "android" || p.isBlank() -> "Handy"
    else -> p
}


// ── Aussprache-Wörterbuch ─────────────────────────────────────────

@Composable
private fun AusspracheBereich(vm: NookViewModel) {
    val st by vm.stimmen.collectAsState()
    val a = st?.optJSONObject("aussprache")
    var liste by remember(a?.toString()) { mutableStateOf(a?.keys()?.asSequence()?.map { it to a.optString(it) }?.sortedBy { it.first.lowercase() }?.toList() ?: emptyList()) }
    var wort by remember { mutableStateOf("") }
    var laut by remember { mutableStateOf("") }
    var offen by remember { mutableStateOf(false) }
    Section("Aussprache", "Wie Namen im Sprachmodus gesprochen werden – „Reacher“ → „Rietscher“. Gilt für alle Stimmen.")
    if (a == null) { Hint("lade …"); return }
    TextButton({ offen = !offen }) { Text(if (offen) "Liste zuklappen" else "${liste.size} Einträge ansehen/ändern", color = A.accent) }
    if (!offen) return
    liste.forEach { (w, l) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(w, color = P.fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("→ $l", color = P.fg2, fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton({ liste = liste.filterNot { it.first == w }; vm.ausspracheSpeichern(liste.toMap()) }) { Text("✕", color = P.mut) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Field("Wort", wort, modifier = Modifier.weight(1f).padding(end = 6.dp)) { wort = it }
        Field("so sprechen", laut, modifier = Modifier.weight(1f)) { laut = it }
    }
    SideButton("Hinzufügen") {
        if (wort.isNotBlank() && laut.isNotBlank()) { liste = liste.filterNot { it.first.equals(wort.trim(), true) } + (wort.trim() to laut.trim()); vm.ausspracheSpeichern(liste.toMap()); wort = ""; laut = "" }
    }
    Hint("Tipp: so schreiben, wie man es auf Deutsch lesen würde – „Släck“, „Klohd“, „Git-Läb“. In der Bot-Einstellung mit „▶ Probe“ anhören.")
}
