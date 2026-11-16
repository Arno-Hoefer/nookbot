package de.hoefer.nookbot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

// Leiste unten (Chat · Projekte · Routinen · Aufgaben) und die Seite „Aufgaben“: was auf dich wartet, was gerade läuft, was heute geplant ist.

/** Sprachmodus-Symbol: fünf Striche wie eine Tonwelle. */
@Composable
fun Wellen(farbe: Color, groesse: Int = 20) {
    Canvas(Modifier.size(groesse.dp)) {
        val w = size.width
        val hoehen = listOf(0.2f, 0.45f, 0.8f, 0.45f, 0.2f)
        hoehen.forEachIndexed { i, h ->
            val x = w * (0.1f + i * 0.2f)
            drawLine(farbe, androidx.compose.ui.geometry.Offset(x, size.height * (0.5f - h / 2)), androidx.compose.ui.geometry.Offset(x, size.height * (0.5f + h / 2)),
                strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        }
    }
}

/** Mikrofon als Strichsymbol (statt Emoji). */
@Composable
fun Mikro(farbe: Color, groesse: Int = 20) = Canvas(Modifier.size(groesse.dp)) {
    val s = size.width
    val st = androidx.compose.ui.graphics.drawscope.Stroke(s * 0.08f, cap = StrokeCap.Round)
    drawRoundRect(farbe, topLeft = androidx.compose.ui.geometry.Offset(s * 0.36f, s * 0.1f), size = androidx.compose.ui.geometry.Size(s * 0.28f, s * 0.48f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.14f), style = st)
    drawArc(farbe, 0f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(s * 0.2f, s * 0.22f), size = androidx.compose.ui.geometry.Size(s * 0.6f, s * 0.5f), style = st)
    drawLine(farbe, androidx.compose.ui.geometry.Offset(s * 0.5f, s * 0.72f), androidx.compose.ui.geometry.Offset(s * 0.5f, s * 0.9f), s * 0.08f, StrokeCap.Round)
}

enum class Tab { CHAT, PROJEKTE, ROUTINEN, AUFGABEN }

@Composable
fun Leiste(vm: NookViewModel, aktiv: Tab) {
    val fr by vm.freigaben.collectAsState()
    val offen = fr.count { it.offen }
    val rout by vm.routinen.collectAsState()
    val neu = rout.count { it.hatErgebnis && !it.gelesen }
    Box(Modifier.fillMaxWidth().padding(bottom = 10.dp, top = 2.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.clip(RoundedCornerShape(30.dp)).background(P.card).border(1.dp, P.line, RoundedCornerShape(30.dp)).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LeistenKnopf("Chat", aktiv == Tab.CHAT, { Sprechblase(it) }) { vm.zumKapitaen() }
            LeistenKnopf("Projekte", aktiv == Tab.PROJEKTE, { Ordner(it) }) { vm.home() }
            LeistenKnopf("Routinen", aktiv == Tab.ROUTINEN, { Zeitung(it) }, punkt = neu > 0) { vm.zuRoutinen() }
            LeistenKnopf("Aufgaben", aktiv == Tab.AUFGABEN, { Haken(it) }, punkt = offen > 0) { vm.zuAufgaben() }
        }
    }
}

@Composable
private fun LeistenKnopf(text: String, an: Boolean, symbol: @Composable (Color) -> Unit, punkt: Boolean = false, onClick: () -> Unit) {
    val farbe = if (an) P.fg else P.mut
    Box {
        Column(Modifier.width(80.dp).clip(RoundedCornerShape(24.dp)).background(if (an) P.field else Color.Transparent).clickable(onClick = onClick)
            .padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            symbol(farbe)
            Text(text, color = farbe, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        if (punkt) Box(Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 20.dp).size(8.dp).clip(CircleShape).background(A.accent))
    }
}

@Composable
private fun Sprechblase(c: Color) = Canvas(Modifier.size(20.dp)) {
    val s = size.width
    drawCircle(c, radius = s * 0.36f, center = androidx.compose.ui.geometry.Offset(s * 0.5f, s * 0.48f), style = androidx.compose.ui.graphics.drawscope.Stroke(s * 0.08f))
    drawLine(c, androidx.compose.ui.geometry.Offset(s * 0.26f, s * 0.74f), androidx.compose.ui.geometry.Offset(s * 0.16f, s * 0.9f), s * 0.08f, StrokeCap.Round)
}

@Composable
private fun Haken(c: Color) = Canvas(Modifier.size(20.dp)) {
    val s = size.width
    val st = s * 0.08f
    for (y in listOf(0.28f, 0.62f)) {
        drawLine(c, androidx.compose.ui.geometry.Offset(s * 0.12f, s * y), androidx.compose.ui.geometry.Offset(s * 0.2f, s * (y + 0.08f)), st, StrokeCap.Round)
        drawLine(c, androidx.compose.ui.geometry.Offset(s * 0.2f, s * (y + 0.08f)), androidx.compose.ui.geometry.Offset(s * 0.34f, s * (y - 0.08f)), st, StrokeCap.Round)
        drawLine(c, androidx.compose.ui.geometry.Offset(s * 0.48f, s * y), androidx.compose.ui.geometry.Offset(s * 0.88f, s * y), st, StrokeCap.Round)
    }
}

@Composable
private fun Ordner(c: Color) = Canvas(Modifier.size(20.dp)) {
    val s = size.width
    val st = androidx.compose.ui.graphics.drawscope.Stroke(s * 0.08f, join = androidx.compose.ui.graphics.StrokeJoin.Round)
    val p = androidx.compose.ui.graphics.Path().apply {
        moveTo(s * 0.1f, s * 0.25f); lineTo(s * 0.4f, s * 0.25f); lineTo(s * 0.5f, s * 0.35f); lineTo(s * 0.9f, s * 0.35f)
        lineTo(s * 0.9f, s * 0.8f); lineTo(s * 0.1f, s * 0.8f); close()
    }
    drawPath(p, c, style = st)
}

@Composable
private fun Zeitung(c: Color) = Canvas(Modifier.size(20.dp)) {
    val s = size.width
    val st = s * 0.08f
    drawRoundRect(c, topLeft = androidx.compose.ui.geometry.Offset(s * 0.12f, s * 0.15f), size = androidx.compose.ui.geometry.Size(s * 0.76f, s * 0.7f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.08f), style = androidx.compose.ui.graphics.drawscope.Stroke(st))
    drawLine(c, androidx.compose.ui.geometry.Offset(s * 0.26f, s * 0.33f), androidx.compose.ui.geometry.Offset(s * 0.74f, s * 0.33f), st * 1.4f, StrokeCap.Round)
    for (y in listOf(0.5f, 0.66f)) drawLine(c, androidx.compose.ui.geometry.Offset(s * 0.26f, s * y), androidx.compose.ui.geometry.Offset(s * 0.74f, s * y), st, StrokeCap.Round)
}

// ── Aufgaben ─────────────────────────────────────────────────────

@Composable
fun AufgabenScreen(vm: NookViewModel) {
    val fr by vm.freigaben.collectAsState()
    val busy by vm.busy.collectAsState()
    val sitz by vm.sitzungen.collectAsState()
    val rout by vm.routinen.collectAsState()
    var reiter by remember { mutableIntStateOf(0) }
    val offen = fr.filter { it.offen }
    val laufend = sitz.filter { it.id in busy }
    val heuteWt = Calendar.getInstance().get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 7 else it - 1 }
    val heute = rout.filter { r -> r.aktiv && (r.tage.isEmpty() || heuteWt in r.tage) && !r.einmalig }.sortedBy { it.zeit }
    val geplant = rout.filter { it.aktiv && it.einmalig }.sortedBy { it.datum + it.zeit }

    Column(Modifier.fillMaxSize()) {
        val bots by vm.bots.collectAsState()
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(androidx.compose.material.icons.Icons.Default.Menu, "Alle Bots") { vm.home() }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(Modifier.size(68.dp).clip(CircleShape).background(P.card).border(1.dp, P.line, CircleShape).clickable { vm.zumKapitaen() },
                    contentAlignment = Alignment.Center) { BotFigure(bots.firstOrNull { it.kapitaen }, 40.dp) }
            }
            RoundIcon(androidx.compose.material.icons.Icons.Default.Settings, "Einstellungen") { vm.go(Route.Settings) }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
            listOf("Aktiv", "Geplant").forEachIndexed { i, t ->
                Text(t, fontFamily = Serif, fontSize = 30.sp, fontWeight = FontWeight.Medium, color = if (reiter == i) P.fg else P.mut.copy(alpha = 0.6f),
                    modifier = Modifier.padding(end = 18.dp).clickable { reiter = i })
            }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            if (reiter == 0) {
                item { Gruppe("Wartet auf dich · ${offen.size}") }
                if (offen.isEmpty()) item { Hint("Nichts offen.", Modifier.padding(bottom = 12.dp)) }
                offen.forEach { f -> item(key = "f" + f.id) {
                    Eintrag(f.titel, listOf("Freigeben" to A.amber, "${whenText(f.erstellt)} · ${f.text.lineSequence().firstOrNull()?.take(60) ?: ""}" to P.mut)) { vm.go(Route.Freigaben) }
                } }
                item { Gruppe("Läuft gerade · ${laufend.size}") }
                if (laufend.isEmpty()) item { Hint("Gerade arbeitet kein Bot.", Modifier.padding(bottom = 12.dp)) }
                laufend.forEach { s -> item(key = "l" + s.id) {
                    val b = vm.bot(s.bot)
                    Row(Modifier.fillMaxWidth().clickable { vm.openChat(s.id) }.padding(vertical = 8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp).padding(top = 2.dp), color = b?.let { Color(it.farbe) } ?: A.accent, strokeWidth = 2.5.dp)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(s.titel.ifBlank { b?.name ?: "Bot" }, color = P.fg, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${b?.name ?: ""} · arbeitet", color = P.mut, fontSize = 13.sp)
                        }
                    }
                } }
                item { Gruppe("Heute · ${heute.size}") }
                if (heute.isEmpty()) item { Hint("Heute keine Routinen.", Modifier.padding(bottom = 12.dp)) }
                heute.forEach { r -> item(key = "r" + r.id) {
                    val gelaufen = r.letzterLauf > 0 && whenText(r.letzterLauf).contains(':')
                    RoutineZeile(r.name, "${vm.bot(r.bot)?.name ?: ""} · ${r.zeit}" + if (gelaufen) " · erledigt" else "", gelaufen) { vm.go(Route.RoutineEdit(r.id)) }
                } }
            } else {
                item { Gruppe("Einmal geplant · ${geplant.size}") }
                geplant.forEach { r -> item(key = "g" + r.id) { RoutineZeile(r.name, "${vm.bot(r.bot)?.name ?: ""} · ${r.datum} ${r.zeit}", false) { vm.go(Route.RoutineEdit(r.id)) } } }
                item { Gruppe("Jede Woche · ${rout.count { it.aktiv && !it.einmalig }}") }
                rout.filter { it.aktiv && !it.einmalig }.sortedBy { it.zeit }.forEach { r -> item(key = "w" + r.id) {
                    RoutineZeile(r.name, "${vm.bot(r.bot)?.name ?: ""} · ${r.zeit} · " + if (r.tage.isEmpty()) "täglich" else r.tage.sorted().joinToString(" ") { TAGE[it - 1] }, false) { vm.go(Route.RoutineEdit(r.id)) }
                } }
                item { Text("Alle Routinen und Auslöser →", color = P.fg2, fontSize = 14.sp, modifier = Modifier.padding(vertical = 14.dp).clickable { vm.go(Route.Routinen) }) }
            }
        }
        Leiste(vm, Tab.AUFGABEN)
    }
}

@Composable
private fun Gruppe(t: String) = Text(t, color = P.mut, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))

@Composable
private fun Eintrag(titel: String, zeile: List<Pair<String, Color>>, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Box(Modifier.padding(top = 2.dp).size(20.dp).clip(RoundedCornerShape(6.dp)).border(1.5.dp, P.mut, RoundedCornerShape(6.dp)))
        Column(Modifier.padding(start = 12.dp)) {
            Text(titel, color = P.fg, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Row { zeile.forEachIndexed { i, (t, c) -> Text((if (i > 0) " · " else "") + t, color = c, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        }
    }
}

@Composable
private fun RoutineZeile(titel: String, unter: String, fertig: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Box(Modifier.padding(top = 2.dp).size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (fertig) P.ink else Color.Transparent)
            .border(1.5.dp, if (fertig) P.ink else P.mut, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            if (fertig) Icon(androidx.compose.material.icons.Icons.Default.Check, null, tint = P.onInk, modifier = Modifier.size(14.dp))
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(titel, color = if (fertig) P.mut else P.fg, fontSize = 16.sp, fontWeight = FontWeight.Medium, textDecoration = if (fertig) TextDecoration.LineThrough else null)
            Text(unter, color = P.mut, fontSize = 13.sp)
        }
    }
}
