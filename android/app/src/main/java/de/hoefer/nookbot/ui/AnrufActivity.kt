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
                when (zustand) {
                    Anruf.Zustand.Klingelt -> Klingeln(info.name, b, { Anruf.annehmen(this) }) { Anruf.ende(this) }
                    // Ruft man selbst an, meldet sich der Bot wie am Telefon (fester Satz, kostet kein Modell – und kommt danach aus dem Zwischenspeicher)
                    Anruf.Zustand.Laeuft -> Sprachmodus(vm, info.sid, b, titel = "Anruf",
                        erst = info.text.takeIf { it.isNotBlank() } ?: "Hallo, hier ist ${info.name}. Was kann ich für dich tun?") { Anruf.ende(this) }
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
