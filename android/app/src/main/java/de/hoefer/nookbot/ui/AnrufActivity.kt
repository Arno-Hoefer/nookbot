package de.hoefer.nookbot.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hoefer.nookbot.Anruf

/**
 * Anruf-Bildschirm – erscheint auch über dem Sperrbildschirm. Klingeln: Annehmen/Ablehnen.
 * Angenommen: der Sprachmodus im Anruf – der Bot liest zuerst sein Ergebnis vor, dann hört er zu.
 */
class AnrufActivity : ComponentActivity() {
    private val vm: NookViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        if (Anruf.aktuell == null) { finish(); return }
        handle(intent)
        Anruf.aktuell?.sid?.takeIf { it.isNotBlank() }?.let { vm.openChat(it) }
        setContent {
            val theme by vm.theme.collectAsState()
            val zustand by Anruf.zustand.collectAsState()
            val bots by vm.bots.collectAsState()
            val info = Anruf.aktuell
            LaunchedEffect(zustand) { if (zustand == Anruf.Zustand.Frei) finish() }
            NookTheme(theme) {
                if (info == null) return@NookTheme
                val b = bots.firstOrNull { it.id == info.bot }
                // Bots erst laden (Stimme!) – höchstens 4 Sekunden warten, dann zur Not mit Thorsten
                var warten by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(4000); warten = false }
                if (zustand == Anruf.Zustand.Laeuft && b == null && warten) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0D)), contentAlignment = Alignment.Center) {
                        Text("verbinde …", color = Color(0xFFA9A8AE), fontSize = 16.sp)
                    }
                    return@NookTheme
                }
                when (zustand) {
                    Anruf.Zustand.Klingelt -> Klingeln(info.name, b, { Anruf.annehmen(this) }) { Anruf.ende(this) }
                    // Ruft man selbst an, meldet sich der Bot wie am Telefon (fester Satz, kostet kein Modell – und kommt danach aus dem Zwischenspeicher)
                    Anruf.Zustand.Laeuft -> Sprachmodus(vm, info.sid, b, titel = "Anruf",
                        erst = info.text.takeIf { it.isNotBlank() } ?: "Hallo, hier ist ${info.name}. Was kann ich für dich tun?",
                        extra = { TonWahl() }) { Anruf.ende(this) }
                    Anruf.Zustand.Frei -> {}
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.action == ANNEHMEN && Anruf.zustand.value == Anruf.Zustand.Klingelt) Anruf.annehmen(this)
    }

    companion object { const val ANNEHMEN = "de.hoefer.nookbot.ANNEHMEN" }
}

/** Ton-Ausgabe im Gespräch: Lautsprecher, Hörmuschel, Headset, Uhr … */
@Composable
private fun TonWahl() {
    val ziele by Anruf.ziele.collectAsState()
    val ziel by Anruf.ziel.collectAsState()
    if (ziele.size < 2) return
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
        ziele.forEach { z ->
            val an = ziel?.identifier == z.identifier
            val zeichen = when {
                z.type == androidx.core.telecom.CallEndpointCompat.TYPE_SPEAKER -> "🔊"
                z.type == androidx.core.telecom.CallEndpointCompat.TYPE_EARPIECE -> "📱"
                Anruf.istUhr(z.name) -> "⌚"
                else -> "🎧"
            }
            Text("$zeichen ${z.name.toString().take(18)}", color = if (an) Color(0xFF0B0B0D) else Color(0xFFD6D5DA), fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .background(if (an) Color(0xFFF3F2EF) else Color(0xFF1C1C1F)).clickable { Anruf.tonNach(z) }
                    .padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
}

@Composable
private fun Klingeln(name: String, b: de.hoefer.nookbot.data.Bot?, onAnnehmen: () -> Unit, onAblehnen: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0D)).systemBarsPadding().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        BotFigure(b, 120.dp)
        Text(name, color = Color(0xFFF3F2EF), fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 24.dp))
        Text("ruft an …", color = Color(0xFFA9A8AE), fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.weight(1.4f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Knopf("Ablehnen", A.accent, "✕", onAblehnen)
            Knopf("Annehmen", Color(0xFF2FBF71), "📞", onAnnehmen)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Knopf(text: String, farbe: Color, zeichen: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(76.dp).clip(CircleShape).background(farbe).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Text(zeichen, fontSize = 30.sp, color = Color.White)
        }
        Text(text, color = Color(0xFFD6D5DA), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
    }
}
