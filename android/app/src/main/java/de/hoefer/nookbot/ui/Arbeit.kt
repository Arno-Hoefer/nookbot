package de.hoefer.nookbot.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Arbeitsordner (gemeinsam für alle Bots, liegt auf dem Pi) ──────

@Composable
fun ArbeitScreen(vm: NookViewModel) {
    val list by vm.arbeit.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach { vm.arbeitHoch(it) } }
    var loeschen by remember { mutableStateOf<String?>(null) }
    val fmt = remember { SimpleDateFormat("EE dd.MM. HH:mm", Locale.GERMANY) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Arbeitsordner", { vm.back() }) { MainButton("+ Datei") { pick.launch(arrayOf("*/*")) } }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item {
                Hint("Hier legen die Bots Diagramme, Tabellen und Seitenbilder ab – und hier holen sie Dateien, die du für sie hinlegst. " +
                    "Sag dem Bot einfach den Dateinamen.", Modifier.padding(bottom = 10.dp))
            }
            if (list.isEmpty()) item { Hint("Noch leer.", Modifier.padding(vertical = 8.dp)) }
            items(list, key = { it.name }) { f ->
                Card(Modifier.padding(vertical = 5.dp), onClick = { vm.arbeitOeffnen(f.name) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (f.bild) ArbeitBild(vm, f.name, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), ContentScale.Crop)
                        else Text("📄", fontSize = 26.sp, modifier = Modifier.width(56.dp))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(f.name, color = P.fg, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(groesse(f.groesse) + " · " + fmt.format(Date(f.ts)) +
                                when (f.bot) { "" -> ""; "app" -> " · von dir"; else -> " · von ${vm.bot(f.bot)?.name ?: f.bot}" },
                                color = P.mut, fontSize = 12.sp)
                        }
                        TextButton({ loeschen = f.name }) { Text("✕", color = P.mut) }
                    }
                }
            }
        }
    }
    loeschen?.let { n ->
        AlertDialog(onDismissRequest = { loeschen = null }, containerColor = P.card,
            title = { Text("Datei löschen?", color = P.fg) }, text = { Text(n, color = P.fg2) },
            confirmButton = { TextButton({ vm.arbeitLoeschen(n); loeschen = null }) { Text("Löschen", color = A.accent) } },
            dismissButton = { TextButton({ loeschen = null }) { Text("Abbrechen", color = P.fg2) } })
    }
}

private fun groesse(n: Long): String = if (n > 1_000_000) "%.1f MB".format(n / 1e6) else "${n / 1024 + 1} KB"

/** Vorschaubild aus dem Arbeitsordner (lädt einmal, liegt dann im Cache des Handys). */
@Composable
fun ArbeitBild(vm: NookViewModel, name: String, modifier: Modifier, scale: ContentScale = ContentScale.Fit) {
    var bild by remember(name) { mutableStateOf<ImageBitmap?>(null) }
    var fehler by remember(name) { mutableStateOf(false) }
    LaunchedEffect(name) {
        runCatching {
            val b = vm.arbeitBytes(name)
            // große Bilder verkleinert dekodieren – ein Seitenbild ist bis 6000 px hoch
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(b, 0, b.size, o)
            val opt = BitmapFactory.Options().apply { inSampleSize = maxOf(1, o.outWidth / 1080) }
            BitmapFactory.decodeByteArray(b, 0, b.size, opt)?.asImageBitmap()
        }.onSuccess { bild = it; fehler = it == null }.onFailure { fehler = true }
    }
    when {
        bild != null -> Image(bild!!, name, modifier, contentScale = scale)
        fehler -> Text("(Bild nicht mehr da)", color = P.mut, fontSize = 12.sp, modifier = Modifier.padding(6.dp))
        else -> Box(modifier.background(P.field))
    }
}

/** Karte im Chat für eine neue Datei im Arbeitsordner – Bilder gleich sichtbar, Tippen öffnet. */
@Composable
fun DateiKarte(vm: NookViewModel, name: String) {
    val bild = Regex("(?i)\\.(png|jpe?g|gif|webp)$").containsMatchIn(name)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).padding(end = 28.dp).clip(RoundedCornerShape(14.dp)).background(P.card)
        .border(1.dp, A.green.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).clickable { vm.arbeitOeffnen(name) }.padding(10.dp)) {
        if (bild) ArbeitBild(vm, name, Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(10.dp)))
        Text("📎 $name", color = P.fg2, fontSize = 13.sp, modifier = Modifier.padding(top = if (bild) 6.dp else 0.dp))
    }
}
