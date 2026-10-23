package de.hoefer.nookbot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.hoefer.nookbot.data.Bot
import de.hoefer.nookbot.data.toolLine
import de.hoefer.nookbot.whisper.Sprachaufnahme
import de.hoefer.nookbot.whisper.WhisperModel
import de.hoefer.nookbot.whisper.WhisperModels
import de.hoefer.nookbot.whisper.WhisperSitzung
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private enum class Phase { Laden, Hoert, Versteht, Denkt, Spricht, Stumm }

/**
 * Sprachmodus: hören → auf dem Handy in Text (whisper.cpp) → an den Bot → Antwort vorlesen (Android-Sprachausgabe) → weiter hören.
 * Alles landet als normaler Text im Chat. Der Kreis zeigt, wer gerade dran ist: grau-weiß = du, Bot-Farbe = der Bot.
 */
@Composable
fun Sprachmodus(vm: NookViewModel, sid: String, b: Bot?, erst: String? = null, titel: String = "Sprachmodus",
                extra: @Composable () -> Unit = {}, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val msgs by vm.msgs.collectAsState()
    val busy by vm.busy.collectAsState()
    val fortschritt by vm.modellFortschritt.collectAsState()
    var phase by remember { mutableStateOf(Phase.Laden) }
    var level by remember { mutableFloatStateOf(0f) }
    var zeile by remember { mutableStateOf("") }
    var stumm by remember { mutableStateOf(false) }
    var fehler by remember { mutableStateOf<String?>(null) }
    var schonGesagt by remember { mutableStateOf(false) }
    var hinweis by remember { mutableStateOf<String?>(null) }
    var spitze by remember { mutableFloatStateOf(0f) }
    var erlaubt by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> erlaubt = ok; if (!ok) fehler = "Ohne Mikrofon geht der Sprachmodus nicht." }
    val modell = remember(fortschritt) { WhisperModels.installedPreferred(ctx) }
    val aufnahme = remember { Sprachaufnahme { level = it; if (it > spitze) spitze = it } }
    val tts = remember { Vorleser(ctx, vm) }
    val botFarbe = b?.let { Color(it.farbe) } ?: A.accent
    val name = b?.name ?: "Bot"

    // Bildschirm anlassen, solange der Sprachmodus offen ist
    val view = LocalView.current
    LaunchedEffect(Unit) { vm.loadStimmen() }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false; aufnahme.abbrechen(); tts.close() }
    }
    LaunchedEffect(Unit) { if (!erlaubt) perm.launch(Manifest.permission.RECORD_AUDIO) }

    // Der Ablauf: hören → verstehen → senden → warten → vorlesen → von vorn
    LaunchedEffect(erlaubt, modell, stumm) {
        // Anruf: der Bot spricht zuerst (sein Ergebnis aus Routine oder Auslöser) – das geht auch ohne Spracherkennung
        if (erst != null && !schonGesagt && !stumm) {
            schonGesagt = true
            phase = Phase.Spricht
            tts.sprechen(sprechtext(erst), b?.stimme ?: "pi:thorsten-high") { zeile = it }
        }
        de.hoefer.nookbot.Anruf.protokoll("Start: Mikro ${if (erlaubt) "erlaubt" else "NICHT erlaubt"}, Modell ${modell?.name ?: "FEHLT"}, Stimme ${b?.stimme ?: "-"}")
        if (!erlaubt || modell == null || stumm) { if (stumm) phase = Phase.Stumm; return@LaunchedEffect }
        val w = runCatching { withContext(Dispatchers.IO) { WhisperSitzung(WhisperModels.file(ctx, modell)) } }
            .getOrElse { fehler = it.message; return@LaunchedEffect }
        try {
            while (isActive) {
                phase = Phase.Hoert
                zeile = ""
                spitze = 0f
                val samples = withContext(Dispatchers.IO) { runCatching { aufnahme.hoeren() }.getOrElse { fehler = it.message; de.hoefer.nookbot.Anruf.protokoll("Mikro-Fehler: ${it.message}"); null } } ?: break
                de.hoefer.nookbot.Anruf.protokoll("gehört: ${samples.size / 16000.0}s, Spitze ${"%.2f".format(spitze)}")
                if (samples.isEmpty()) { hinweis = "Nichts gehört – sprich etwas lauter."; continue }
                phase = Phase.Versteht
                val text = withContext(Dispatchers.Default) { w.text(samples) }
                de.hoefer.nookbot.Anruf.protokoll("erkannt: ${text.length} Zeichen")
                if (text.isBlank() || text.length < 2) { hinweis = "Nicht verstanden – bitte noch einmal."; continue }
                hinweis = null
                zeile = text
                val vorher = vm.msgs.value.size
                vm.send(text)
                de.hoefer.nookbot.Anruf.protokoll("an den Bot geschickt (Chat ${vm.current ?: "KEINER"})")
                phase = Phase.Denkt
                // warten, bis der Bot fertig ist (Adapter meldet busy=false), höchstens 10 Minuten
                withTimeoutOrNull(600_000) {
                    delay(800)
                    while (sid in vm.busy.value) delay(300)
                }
                val antwort = vm.msgs.value.drop(vorher).lastOrNull { it.role == "bot" && it.text.isNotBlank() }?.text ?: continue
                de.hoefer.nookbot.Anruf.protokoll("Antwort: ${antwort.length} Zeichen")
                phase = Phase.Spricht
                tts.sprechen(sprechtext(antwort), b?.stimme ?: "pi:thorsten-high") { zeile = it }
            }
        } finally {
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { w.close() }
        }
    }
    // Werkzeuge, die der Bot gerade benutzt, als kleine Zeile
    val werkzeug = if (phase == Phase.Denkt) msgs.lastOrNull()?.takeIf { it.role == "tool" }?.let { toolLine(it.text) } else null

    Dialog(onDismissRequest = { aufnahme.abbrechen(); onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0D)).systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BotFigure(b, 28.dp)
                Text("$titel · $name", color = Color(0xFFF3F2EF), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp).weight(1f))
                if (b?.sitzung == sid) Text("auf dem Pi", color = Color(0xFF8D8C93), fontSize = 12.sp)
            }
            extra()
            Spacer(Modifier.weight(1f))

            val du = phase == Phase.Hoert || phase == Phase.Versteht
            Kugel(farbe = if (du) Color(0xFFE8EEF8) else botFarbe, level = if (phase == Phase.Hoert) level else 0f,
                atmet = phase == Phase.Denkt || phase == Phase.Versteht || phase == Phase.Laden, spricht = phase == Phase.Spricht) {
                when (phase) {
                    Phase.Spricht -> tts.stopp()          // unterbrechen → gleich wieder zuhören
                    Phase.Hoert -> aufnahme.fertig()      // Satz sofort abschließen
                    else -> {}
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(when {
                fehler != null -> "Fehler"
                modell == null -> "Sprachmodell fehlt"
                du -> "Du"
                phase == Phase.Stumm -> "Mikro aus"
                else -> name
            }, color = Color(0xFFF3F2EF), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(when {
                fehler != null -> fehler ?: ""
                modell == null -> "Für die Spracherkennung auf dem Handy einmalig ein Modell laden:"
                phase == Phase.Laden -> "Starte …"
                phase == Phase.Hoert -> hinweis ?: "Ich höre zu – sprich einfach. Tippen schließt ab."
                phase == Phase.Versteht -> "Verstehe …"
                phase == Phase.Denkt -> "$name denkt nach …" + (werkzeug?.let { "\n$it" } ?: "")
                phase == Phase.Spricht -> "Tippen unterbricht"
                else -> "Tippe auf das Mikro, um weiterzusprechen."
            }, color = Color(0xFFA9A8AE), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            if (modell == null) Row(Modifier.padding(top = 14.dp)) {
                if (fortschritt != null) LinearProgressIndicator({ fortschritt ?: 0f }, Modifier.width(220.dp), color = A.accent)
                else WhisperModel.entries.forEach { m -> SideButton("${m.label} (${m.hint.substringBefore(" ·")})", Color(0xFFF3F2EF)) { vm.modellLaden(m) }; Spacer(Modifier.width(8.dp)) }
            }
            if (zeile.isNotBlank()) Text(zeile, color = Color(0xFFD6D5DA), fontSize = 16.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
                maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 18.dp).widthIn(max = 340.dp))
            Spacer(Modifier.weight(1f))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(if (stumm) A.accent.copy(alpha = 0.25f) else Color(0xFF1C1C1F))
                    .clickable { if (!stumm) { aufnahme.abbrechen(); tts.stopp() }; stumm = !stumm }, contentAlignment = Alignment.Center) {
                    Text(if (stumm) "🔇" else "🎙", fontSize = 24.sp)
                }
                Spacer(Modifier.width(40.dp))
                Box(Modifier.size(64.dp).clip(CircleShape).background(A.accent).clickable { aufnahme.abbrechen(); tts.stopp(); onClose() },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, "Beenden", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
            Text("Alles steht danach als Text im Chat.", color = Color(0xFF6E6D73), fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

/** Der Kreis: atmet beim Nachdenken, schlägt beim Zuhören mit der Lautstärke aus, wogt beim Sprechen. */
@Composable
private fun Kugel(farbe: Color, level: Float, atmet: Boolean, spricht: Boolean, onTap: () -> Unit) {
    val t = rememberInfiniteTransition(label = "kugel")
    val atem by t.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "atem")
    val welle by t.animateFloat(0.97f, 1.08f, infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse), label = "welle")
    val dreh by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "dreh")
    val pegel by animateFloatAsState(level, tween(90), label = "pegel")
    val s = when { spricht -> welle; atmet -> atem; else -> 1f + pegel * 0.28f }
    Box(Modifier.size(230.dp).scale(s).clip(CircleShape)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap)) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f), farbe, farbe.copy(alpha = 0.85f)),
                center = c + Offset(-size.width * 0.18f, -size.height * 0.2f), radius = size.width * 0.75f))
            // weiche, langsam drehende Wolke für etwas Tiefe
            val a = Math.toRadians(dreh.toDouble())
            val p = Offset(c.x + (size.width * 0.18f * Math.cos(a)).toFloat(), c.y + (size.height * 0.18f * Math.sin(a)).toFloat())
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), center = p, radius = size.width * 0.4f))
        }
    }
}

/** Markdown in sprechbaren Text: Tabellen, Code und Links nicht vorlesen, Zeichen weg, Länge begrenzen. */
fun sprechtext(md: String): String {
    var t = md.replace(Regex("```[\\s\\S]*?```"), " Den Code findest du im Chat. ")
    val zeilen = t.lines()
    val sb = StringBuilder()
    var tabelle = false
    for (z in zeilen) {
        if (z.trim().startsWith("|")) { if (!tabelle) sb.append("Die Tabelle steht im Chat.\n"); tabelle = true; continue }
        tabelle = false
        sb.append(z).append('\n')
    }
    t = sb.toString()
        .replace(Regex("\\[([^\\]]+)]\\((https?://[^)]+)\\)"), "$1")
        .replace(Regex("https?://\\S+"), "Link im Chat")
        .replace(Regex("\\bF-[0-9a-f]{6}\\b"), "Freigabe")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
        .replace(Regex("[*_`>#]"), "")
        .replace(Regex("\\n{2,}"), "\n")
        .trim()
    if (t.length > 1400) t = t.take(1400).substringBeforeLast(". ") + ". Den Rest findest du im Chat."
    return t
}

