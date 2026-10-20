package de.hoefer.nookbot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import de.hoefer.nookbot.data.Bot
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val FORMEN = listOf("kapsel", "kreis", "wolke", "dreieck", "quadrat")

/** Bot-Figur aus dem Design: Körper in Form und Farbe des Bots, zwei Augen. */
@Composable
fun BotFigure(size: Dp, form: String = "kapsel", color: Color = A.accent) {
    val eye = P.bg
    Canvas(Modifier.size(size, size * 0.8f)) {
        val w = this.size.width
        val h = this.size.height
        when (form) {
            "kreis" -> drawCircle(color, h * 0.42f, Offset(w * 0.5f, h * 0.5f))
            "wolke" -> {
                drawCircle(color, h * 0.26f, Offset(w * 0.36f, h * 0.52f)); drawCircle(color, h * 0.28f, Offset(w * 0.6f, h * 0.42f))
                drawCircle(color, h * 0.24f, Offset(w * 0.64f, h * 0.66f)); drawCircle(color, h * 0.22f, Offset(w * 0.4f, h * 0.7f))
            }
            "dreieck" -> rotate(-8f) {
                drawPath(Path().apply { moveTo(w * 0.24f, h * 0.1f); lineTo(w * 0.24f, h * 0.9f); lineTo(w * 0.86f, h * 0.5f); close() }, color)
            }
            "quadrat" -> rotate(12f) {
                drawRoundRect(color, Offset(w * 0.22f, h * 0.1f), Size(w * 0.56f, h * 0.8f), CornerRadius(h * 0.18f))
            }
            else -> rotate(-10f) { drawRoundRect(color, Offset(w * 0.08f, h * 0.14f), Size(w * 0.84f, h * 0.72f), CornerRadius(h * 0.36f)) }
        }
        eyes(w, h, eye)
    }
}

private fun DrawScope.eyes(w: Float, h: Float, c: Color) = rotate(-18f) {
    drawRoundRect(c, Offset(w * 0.40f, h * 0.30f), Size(w * 0.07f, h * 0.22f), CornerRadius(w * 0.035f))
    drawRoundRect(c, Offset(w * 0.57f, h * 0.27f), Size(w * 0.07f, h * 0.22f), CornerRadius(w * 0.035f))
}

@Composable
fun BotFigure(b: Bot?, size: Dp) = BotFigure(size, b?.form ?: "kapsel", b?.let { Color(it.farbe) } ?: A.accent)

@Composable
fun RoundIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(P.field).border(1.dp, P.line, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = P.fg, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit, sub: String? = null, right: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", onBack)
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, color = P.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, color = P.mut, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        right()
    }
}

@Composable
fun Section(t: String, hint: String? = null) {
    Column(Modifier.padding(top = 20.dp, bottom = 6.dp)) {
        Text(t, color = P.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        hint?.let { Text(it, color = P.mut, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
    }
}

@Composable
fun Field(label: String, value: String, secret: Boolean = false, lines: Int = 1, mono: Boolean = false, number: Boolean = false,
          modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label, fontSize = 12.sp) }, singleLine = lines == 1, minLines = if (lines > 1) minOf(3, lines) else 1,
        maxLines = if (lines > 1) lines else 1,
        textStyle = LocalTextStyleMono(mono),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier.padding(vertical = 4.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = P.fg, unfocusedTextColor = P.fg, focusedBorderColor = A.accent,
            unfocusedBorderColor = P.line, focusedLabelColor = P.fg2, unfocusedLabelColor = P.mut, cursorColor = A.accent))
}

@Composable
private fun LocalTextStyleMono(mono: Boolean) =
    if (mono) androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 13.sp)
    else androidx.compose.material3.LocalTextStyle.current

@Composable
fun Chip(text: String, selected: Boolean, color: Color? = null, onClick: () -> Unit) {
    Text(text, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) P.bg else P.fg2,
        modifier = Modifier.padding(end = 8.dp, bottom = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) (color ?: P.fg) else P.field).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
fun MainButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = A.accent, contentColor = Color.White)) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SideButton(text: String, color: Color? = null, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(14.dp)) { Text(text, color = color ?: P.fg) }
}

@Composable
fun Card(modifier: Modifier = Modifier, border: Color? = null, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.card).border(1.dp, border ?: P.line, RoundedCornerShape(16.dp))
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(14.dp), content = content)
}

@Composable
fun Hint(t: String, modifier: Modifier = Modifier) = Text(t, color = P.mut, fontSize = 12.sp, lineHeight = 17.sp, modifier = modifier)

fun whenText(ts: Long): String {
    if (ts <= 0) return ""
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    val sameDay = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
    return SimpleDateFormat(if (sameDay) "HH:mm" else "dd.MM.", Locale.GERMANY).format(Date(ts))
}

val TAGE = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
