package de.hoefer.nookbot.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hoefer.nookbot.data.WissenDatei
import de.hoefer.nookbot.data.colorHex
import org.json.JSONObject

val WISSEN_MIME = arrayOf("*/*")

/** Wissen hochladen: jede Datei – der Pi wandelt Dokumente in Text um, bei Fotos zählt der erkannte Text. */
fun uploadWissenDatei(ctx: Context, vm: NookViewModel, scope: String, uri: Uri) {
    vm.wissenDatei(scope, uri)
}

/** Textdatei vom Gerät lesen (für Wissen und Import). Name + Inhalt, oder Fehlermeldung. */
fun readTextFile(ctx: Context, uri: Uri, max: Int = 300_000): Result<Pair<String, String>> = runCatching {
    var name = "datei.txt"
    var size = -1L
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
        }
    }
    if (size > max) error("Datei zu groß (max. ${max / 1000} KB)")
    val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.also { if (it.size > max) error("Datei zu groß") }.decodeToString()
    name to text
}

@Composable
fun BotScreen(vm: NookViewModel, id: String?) {
    val bots by vm.bots.collectAsState()
    val modelle by vm.modelle.collectAsState()
    val claudeModelle by vm.claudeModelle.collectAsState()
    val b = bots.firstOrNull { it.id == id }
    val neu = id == null
    var name by remember(b?.id) { mutableStateOf(b?.name ?: "") }
    var rolle by remember(b?.id) { mutableStateOf(b?.rolle ?: "") }
    var farbe by remember(b?.id) { mutableStateOf(b?.farbe ?: A.bots[1]) }
    var form by remember(b?.id) { mutableStateOf(b?.form ?: "kreis") }
    var modell by remember(b?.id) { mutableStateOf(b?.modell ?: "glm-5.3") }
    var persoenlichkeit by remember(b?.id) { mutableStateOf(b?.persoenlichkeit ?: "") }
    var fokus by remember(b?.id) { mutableStateOf(b?.fokus ?: "") }
    var zugriffe by remember(b?.id) { mutableStateOf(b?.zugriffe ?: false) }
    var merken by remember(b?.id) { mutableStateOf(b?.merken ?: true) }
    val gedaechtnis by vm.gedaechtnis.collectAsState()
    var modellMenu by remember { mutableStateOf(false) }
    var loeschen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && b != null) uploadWissenDatei(ctx, vm, b.id, uri)
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(if (neu) "Neuer Bot" else b?.name ?: "Bot", { vm.back() }, sub = if (b?.kapitaen == true) "Kapitän" else b?.rolle)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) { BotFigure(110.dp, form, Color(farbe)) }

            Field("Name", name) { name = it }
            Field("Rolle (kurz, z. B. Recherche, Skeptiker, Analyst)", rolle) { rolle = it }

            Section("Aussehen")
            Row(Modifier.fillMaxWidth()) {
                A.bots.forEach { c ->
                    Box(Modifier.padding(end = 8.dp).size(30.dp).clip(CircleShape).background(Color(c))
                        .border(3.dp, if (c == farbe) P.fg else Color.Transparent, CircleShape).clickable { farbe = c })
                }
            }
            Row(Modifier.padding(top = 10.dp)) {
                FORMEN.forEach { f ->
                    Box(Modifier.padding(end = 8.dp).clip(RoundedCornerShape(12.dp)).background(if (f == form) P.chip else P.field)
                        .border(1.dp, if (f == form) P.fg2 else P.line, RoundedCornerShape(12.dp)).clickable { form = f }.padding(6.dp)) {
                        BotFigure(40.dp, f, Color(farbe))
                    }
                }
            }

            Section("Modell", "Ollama Pro (Pauschale) oder Claude (nach Verbrauch, eigener Schlüssel auf dem Pi). " +
                "Größere Modelle denken gründlicher, „flash“/Haiku sind schneller und günstiger.")
            Box {
                OutlinedButton(onClick = { modellMenu = true; if (modelle.isEmpty()) vm.loadModelle() }, shape = RoundedCornerShape(12.dp)) {
                    Text(modell.ifBlank { "Modell wählen" } + "  ▾", color = P.fg)
                }
                DropdownMenu(modellMenu, { modellMenu = false }, containerColor = P.card, modifier = Modifier.heightIn(max = 380.dp)) {
                    if (modelle.isEmpty() && claudeModelle.isEmpty()) DropdownMenuItem({ Text("lade …", color = P.mut) }, {})
                    if (claudeModelle.isNotEmpty()) {
                        DropdownMenuItem({ Text("Claude (Anthropic)", color = P.mut, fontSize = 12.sp) }, {}, enabled = false)
                        claudeModelle.forEach { m -> DropdownMenuItem({ Text(m, color = P.fg) }, { modell = m; modellMenu = false }) }
                        DropdownMenuItem({ Text("Ollama", color = P.mut, fontSize = 12.sp) }, {}, enabled = false)
                    }
                    modelle.forEach { m -> DropdownMenuItem({ Text(m, color = P.fg) }, { modell = m; modellMenu = false }) }
                }
            }

            Section("Persönlichkeit", "Ton und Art – wie er klingt und auftritt.")
            Field("z. B. ruhig, gründlich, direkt; nüchtern und kritisch …", persoenlichkeit, lines = 6) { persoenlichkeit = it }
            Section("Fokus", "Wofür er zuständig ist und worauf er achtet. Der Kapitän sieht das und gibt passende Aufträge weiter.")
            Field("z. B. Recherche zu Produkten und Preisen, immer mit Quellen …", fokus, lines = 6) { fokus = it }

            if (b?.kapitaen != true) {
                Section("Zugriffe")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Kalender, Mail, Slack, GitLab, Datenbank", color = P.fg, fontSize = 14.sp)
                        Hint("Über home-mcp mit den Rechten des Bots „nookbot“. Schreiben nur mit deiner Freigabe. Aus: nur Websuche und Wissen.")
                    }
                    Switch(zugriffe, { zugriffe = it }, colors = SwitchDefaults.colors(checkedTrackColor = A.accent))
                }
            }

            Section("Gedächtnis", "Was sich der Bot dauerhaft merkt – Vorlieben, Arbeitsweisen, Fakten über dich. Nur aus deinen eigenen Nachrichten.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Darf sich selbst etwas merken", color = P.fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(merken, { merken = it }, colors = SwitchDefaults.colors(checkedTrackColor = A.accent))
            }
            if (b != null) {
                val eigene = gedaechtnis.filter { it.fuer == b.id }
                if (eigene.isEmpty()) Hint("Noch nichts gemerkt.", Modifier.padding(vertical = 4.dp))
                eigene.forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🧠", fontSize = 14.sp)
                        Text(e.text, color = P.fg, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
                        TextButton({ vm.vergessen(e.id) }) { Text("✕", color = P.mut) }
                    }
                }
                Hint("Was alle Bots wissen sollen: Menü → Gedächtnis.")
            }

            if (b != null) {
                Section("Eigenes Wissen", "Dateien nur für ${b.name}. Globales Wissen für alle: Menü → Wissen.")
                WissenList(b.wissen, onDelete = { vm.deleteWissen(b.id, it) })
                SideButton("Datei hinzufügen") { pick.launch(WISSEN_MIME) }
            }

            Row(Modifier.padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                MainButton(if (saving) "Speichere …" else if (neu) "Bot anlegen" else "Speichern", enabled = name.isNotBlank() && !saving) {
                    saving = true
                    val d = JSONObject().put("name", name).put("rolle", rolle).put("farbe", colorHex(farbe)).put("form", form).put("modell", modell)
                        .put("persoenlichkeit", persoenlichkeit).put("fokus", fokus).put("zugriffe", zugriffe).put("merken", merken)
                    vm.saveBot(id, d) { saving = false; vm.back() }
                }
                Spacer(Modifier.width(10.dp))
                if (b != null) SideButton("Chat") { vm.newChat(b.id) }
            }
            Hint("Nach dem Speichern startet der Bot kurz neu (ein paar Sekunden).")
            if (b != null && !b.kapitaen) TextButton({ loeschen = true }, modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)) {
                Text("Bot löschen", color = A.accent)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (loeschen && b != null) AlertDialog(onDismissRequest = { loeschen = false }, containerColor = P.card,
        title = { Text("${b.name} löschen?", color = P.fg) },
        text = { Text("Der Bot wird gestoppt, seine Dateien landen im Papierkorb-Ordner auf dem Pi.", color = P.fg2) },
        confirmButton = { TextButton({ loeschen = false; vm.deleteBot(b.id) }) { Text("Löschen", color = A.accent) } },
        dismissButton = { TextButton({ loeschen = false }) { Text("Abbrechen", color = P.fg2) } })
}

@Composable
fun WissenList(files: List<WissenDatei>, onDelete: (String) -> Unit) {
    var del by remember { mutableStateOf<String?>(null) }
    if (files.isEmpty()) Hint("Noch keine Dateien.", Modifier.padding(vertical = 4.dp))
    files.forEach { f ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📄", fontSize = 16.sp)
            Text(f.name, color = P.fg, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
            Text("${(f.groesse + 999) / 1000} KB", color = P.mut, fontSize = 12.sp)
            TextButton({ del = f.name }) { Text("✕", color = P.mut) }
        }
    }
    del?.let { n ->
        AlertDialog(onDismissRequest = { del = null }, containerColor = P.card, title = { Text("„$n“ entfernen?", color = P.fg) },
            confirmButton = { TextButton({ onDelete(n); del = null }) { Text("Entfernen", color = A.accent) } },
            dismissButton = { TextButton({ del = null }) { Text("Abbrechen", color = P.fg2) } })
    }
}
