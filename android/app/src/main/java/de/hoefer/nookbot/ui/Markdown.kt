package de.hoefer.nookbot.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Bot-Antworten kommen als Markdown. Klein gehalten (wie in Orga): fett, kursiv, `Code`, Aufzählungen, Überschriften –
 * dazu Tabellen: Zeilen „| a | b |“ werden zu „a · b“, die Trennzeile |---| fällt weg, die Kopfzeile ist fett.
 */
fun markdown(text: String, code: Color = Color.Unspecified): AnnotatedString = buildAnnotatedString {
    var tableRow = 0
    var first = true
    for (raw in text.trim().lines()) {
        var line = raw.trimEnd()
        val t = line.trim()
        if (t.startsWith("|") && t.endsWith("|")) {
            if (Regex("^\\|[\\s:|-]+\\|$").matches(t)) continue
            if (!first) append("\n")
            first = false
            val cells = t.trim('|').split("|").map { it.trim() }
            if (tableRow == 0) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inline(cells.joinToString("  ·  "), code) }
            else inline(cells.joinToString("  ·  "), code)
            tableRow++
            continue
        }
        tableRow = 0
        if (!first) append("\n")
        first = false
        val heading = Regex("^#{1,6}\\s+").find(line)
        if (heading != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(line.substring(heading.range.last + 1).replace("**", ""), code) }
            continue
        }
        Regex("^(\\s*)[*\\-+]\\s+").find(line)?.let { m ->
            append("  ".repeat(m.groupValues[1].length / 2) + "• "); line = line.substring(m.range.last + 1)
        }
        inline(line, code)
    }
}

private fun AnnotatedString.Builder.inline(s: String, code: Color) {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end > i + 2) { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.substring(i + 2, end)) }; i = end + 2 }
                else { i += 2 }
            }
            s[i] == '*' && i + 1 < s.length && s[i + 1] != ' ' -> {
                val end = s.indexOf('*', i + 1)
                if (end > i + 1) { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }; i = end + 1 }
                else { append(s[i]); i++ }
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = code)) { append(s.substring(i + 1, end)) }; i = end + 1 }
                else i++
            }
            else -> { append(s[i]); i++ }
        }
    }
}
