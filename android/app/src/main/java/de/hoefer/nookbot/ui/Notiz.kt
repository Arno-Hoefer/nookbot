package de.hoefer.nookbot.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hoefer.nookbot.data.Bot
import de.hoefer.nookbot.whisper.WavRecorder
import de.hoefer.nookbot.whisper.WhisperModel
import de.hoefer.nookbot.whisper.WhisperModels
import kotlinx.coroutines.delay
import java.io.File

/**
 * Sprachnotiz: aufnehmen → auf dem Handy transkribieren (whisper.cpp, wie Echo; Audio verlässt das Handy nicht)
 * → Text prüfen → in den Chat, optional zusätzlich als Wissensdatei für den Bot.
 */
@Composable
fun NotizDialog(vm: NookViewModel, b: Bot?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var phase by remember { mutableStateOf("bereit") } // bereit → aufnahme → text
    var level by remember { mutableFloatStateOf(0f) }
    var sekunden by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var alsWissen by remember { mutableStateOf(false) }
    val status by vm.notizStatus.collectAsState()
    val fortschritt by vm.modellFortschritt.collectAsState()
    val wav = remember { File(ctx.cacheDir, "notiz.wav") }
    var rec by remember { mutableStateOf<WavRecorder?>(null) }
    val modell = remember(fortschritt) { WhisperModels.installedPreferred(ctx) }

    fun starten() {
        runCatching { WavRecorder(wav) { level = it }.also { it.start() } }
            .onSuccess { rec = it; sekunden = 0; phase = "aufnahme" }.onFailure { vm.say(it.message ?: "Mikrofon nicht verfügbar") }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) starten() else vm.say("Ohne Mikrofon keine Sprachnotiz") }
    LaunchedEffect(phase) { while (phase == "aufnahme") { delay(1000); sekunden++ } }
    DisposableEffect(Unit) { onDispose { rec?.stop(); rec = null } }
    val dauer = "%d:%02d min".format(sekunden / 60, sekunden % 60)

    AlertDialog(onDismissRequest = { if (phase != "aufnahme" && status == null) onClose() }, containerColor = P.card,
        title = { Text(when (phase) { "aufnahme" -> "Aufnahme läuft"; "text" -> "Notiz prüfen"; else -> "Sprachnotiz" }, color = P.fg) },
        text = {
            Column {
                when {
                    modell == null -> {
                        Text("Für die Umwandlung in Text braucht NookBot einmalig ein Sprachmodell (bleibt auf dem Handy):", color = P.fg2, fontSize = 14.sp)
                        if (fortschritt != null) {
                            LinearProgressIndicator({ fortschritt ?: 0f }, Modifier.fillMaxWidth().padding(top = 12.dp), color = A.accent)
                        } else Row(Modifier.padding(top = 12.dp)) {
                            WhisperModel.entries.forEach { m ->
                                SideButton("${m.label} (${m.hint.substringBefore(" ·")})") { vm.modellLaden(m) }
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                    }
                    phase == "bereit" -> Text("An ${b?.name ?: "den Bot"}: Tippe auf Aufnehmen und sprich. Danach siehst du den Text und kannst ihn korrigieren.",
                        color = P.fg2, fontSize = 14.sp)
                    phase == "aufnahme" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size((18 + level * 26).dp).clip(CircleShape).background(A.accent))
                        Text(dauer, color = P.fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp))
                    }
                    status != null -> Text(status ?: "", color = P.fg2, fontSize = 14.sp)
                    else -> {
                        Field("Transkript", text, lines = 10) { text = it }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(alsWissen, { alsWissen = it })
                            Text("Auch als Wissen für ${b?.name ?: "den Bot"} speichern", color = P.fg2, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                modell == null -> {}
                phase == "bereit" -> TextButton({
                    if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) starten()
                    else perm.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Aufnehmen", color = A.accent) }
                phase == "aufnahme" -> TextButton({
                    rec?.stop(); rec = null; phase = "text"
                    vm.transkribieren(wav) { text = it.ifBlank { "(nichts verstanden)" } }
                }) { Text("Fertig", color = A.accent) }
                status == null -> TextButton({ vm.notizSenden(text, dauer, alsWissen); onClose() }, enabled = text.isNotBlank()) {
                    Text("In den Chat", color = A.accent)
                }
            }
        },
        dismissButton = {
            if (status == null) TextButton({ rec?.stop(); rec = null; wav.delete(); onClose() }) { Text("Abbrechen", color = P.fg2) }
        })
}
