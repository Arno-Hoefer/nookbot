package de.hoefer.nookbot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hoefer.nookbot.data.LiveKarte
import de.hoefer.nookbot.data.PlanPunkt
import de.hoefer.nookbot.data.Sitzung

// Projekte, Pläne und Live-Karten in der App (Server: projekte.py).

/** Oben im Chat: angepinnte Live-Karten und der Plan. */
@Composable
fun KopfKarten(vm: NookViewModel) {
    val karten by vm.karten.collectAsState()
    val plan by vm.plan.collectAsState()
    val projekt by vm.projekt.collectAsState()
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        projekt?.optString("ziel")?.takeIf { it.isNotBlank() }?.let { Text(it, color = P.fg2, fontSize = 14.sp, lineHeight = 20.sp) }
        karten.forEach { LiveKarteView(vm, it) }
        if (plan.isNotEmpty()) PlanKarte(vm, plan)
    }
}

@Composable
private fun LiveKarteView(vm: NookViewModel, k: LiveKarte) {
    var weg by remember { mutableStateOf(false) }
    if (weg) AlertDialog(onDismissRequest = { weg = false }, containerColor = P.card,
        title = { Text("Karte abnehmen?", color = P.fg) },
        text = { Text("„${k.titel}“ verschwindet oben aus dem Chat. Der Bot kann sie jederzeit neu anpinnen.", color = P.fg2) },
        confirmButton = { TextButton({ weg = false; vm.karteWeg(k) }) { Text("Abnehmen", color = A.accent) } },
        dismissButton = { TextButton({ weg = false }) { Text("Behalten", color = P.fg2) } })
    Card {
        Text(k.titel.uppercase(), color = P.mut, fontSize = 11.sp, letterSpacing = 0.8.sp)
        if (k.inhalt.isNotBlank()) Text(markdown(k.inhalt, P.fg2), color = P.fg, fontSize = 19.sp, lineHeight = 24.sp, fontFamily = Serif, modifier = Modifier.padding(top = 4.dp))
        if (k.werte.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            k.werte.forEach { (l, w) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(P.bg).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(l, color = P.mut, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(w, color = P.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("aktualisiert ${uhrzeit(k.aktualisiert)}", color = P.mut, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("abnehmen", color = P.mut, fontSize = 12.sp, modifier = Modifier.clickable { weg = true }.padding(4.dp))
        }
    }
}

@Composable
private fun PlanKarte(vm: NookViewModel, plan: List<PlanPunkt>) {
    var auf by remember { mutableStateOf(plan.size <= 8) }
    val erledigt = plan.count { it.status == "erledigt" }
    val dich = plan.count { it.status == "braucht_dich" }
    val beob = plan.count { it.status == "beobachten" }
    Card {
        Row(Modifier.fillMaxWidth().clickable { auf = !auf }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Plan", color = P.fg, fontSize = 21.sp, fontFamily = Serif)
                Text(listOfNotNull("${plan.size} Punkte", "$erledigt erledigt", if (beob > 0) "$beob im Blick" else null, if (dich > 0) "$dich braucht dich" else null)
                    .joinToString(" · "), color = if (dich > 0) A.amber else P.mut, fontSize = 13.sp)
            }
            Text(if (auf) "▾" else "▸", color = P.mut, fontSize = 16.sp)
        }
        // Fortschritt als dünner Balken
        Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(P.field)) {
            Box(Modifier.fillMaxWidth(erledigt.toFloat() / plan.size).height(6.dp).clip(RoundedCornerShape(3.dp)).background(A.green))
        }
        if (auf) Column(Modifier.padding(top = 6.dp)) {
            // Was dich braucht zuerst, Erledigtes zuletzt
            plan.sortedBy { when (it.status) { "braucht_dich" -> 0; "offen" -> 1; "beobachten" -> 2; else -> 3 } }.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { vm.planHaken(p) }.padding(vertical = 7.dp)) {
                    val fertig = p.status == "erledigt"
                    Box(Modifier.padding(top = 1.dp).size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (fertig) P.ink else Color.Transparent)
                        .border(1.5.dp, if (fertig) P.ink else if (p.status == "braucht_dich") A.amber else P.mut, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                        if (fertig) Icon(Icons.Default.Check, null, tint = P.onInk, modifier = Modifier.size(14.dp))
                    }
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(p.text, color = if (fertig) P.mut else P.fg, fontSize = 15.sp, textDecoration = if (fertig) TextDecoration.LineThrough else null)
                        val unter = listOfNotNull(
                            when (p.status) { "braucht_dich" -> "braucht dich"; "beobachten" -> "behalte ich im Blick"; else -> null },
                            p.faellig.takeIf { it.isNotBlank() }?.let { "bis " + it.substring(8, 10) + "." + it.substring(5, 7) + "." },
                            p.notiz.takeIf { it.isNotBlank() })
                        if (unter.isNotEmpty()) Text(unter.joinToString(" · "), color = if (p.status == "braucht_dich") A.amber else P.mut, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** Im Bot-Menü: Projekte mit Statuszeile, Stand des Plans und „+ Neues Projekt“. */
@Composable
fun ProjekteListe(vm: NookViewModel, projekte: List<Sitzung>) {
    var neu by remember { mutableStateOf(false) }
    if (neu) {
        var titel by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { neu = false }, containerColor = P.card,
            title = { Text("Neues Projekt", color = P.fg, fontFamily = Serif) },
            text = {
                Column {
                    Text("Ein Thema über Wochen – eigene Unterhaltung mit dem Kapitän, Statuszeile und Plan. Erzähl ihm danach, worum es geht.",
                        color = P.fg2, fontSize = 14.sp)
                    Field("Titel, z. B. „Umzug März“", titel) { titel = it }
                }
            },
            confirmButton = { TextButton({ neu = false; vm.projektAnlegen(titel) }, enabled = titel.isNotBlank()) { Text("Anlegen", color = P.fg) } },
            dismissButton = { TextButton({ neu = false }) { Text("Abbrechen", color = P.fg2) } })
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text("Projekte", color = P.mut, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        projekte.forEach { s ->
            Row(Modifier.fillMaxWidth().clickable { vm.openChat(s.id) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(P.card).border(1.dp, P.line, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Text(s.titel.take(1).uppercase(), color = P.fg, fontSize = 16.sp, fontFamily = Serif)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(s.titel, color = P.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.status.ifBlank { s.plan.ifBlank { "noch kein Plan" } }, color = P.mut, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (s.brauchtDich > 0) Box(Modifier.size(20.dp).clip(CircleShape).background(A.accent), contentAlignment = Alignment.Center) {
                    Text("${s.brauchtDich}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text("＋ Neues Projekt", color = P.fg, fontSize = 15.sp, modifier = Modifier.clickable { neu = true }.padding(vertical = 10.dp))
        Spacer(Modifier.size(4.dp))
    }
}
