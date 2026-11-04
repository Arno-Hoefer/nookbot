package de.hoefer.nookbot.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Letzter Stand vom Pi auf dem Handy: Startseite und Chats erscheinen sofort, der Pi liefert danach nur nach.
 * Liegt im app-eigenen Speicher (kein Backup, andere Apps kommen nicht heran).
 */
class Zwischenspeicher(ctx: Context) {
    private val dir = File(ctx.noBackupFilesDir, "zwischenspeicher").apply { mkdirs() }

    fun lesen(name: String): JSONObject? = runCatching { JSONObject(File(dir, "$name.json").readText()) }.getOrNull()

    fun schreiben(name: String, o: JSONObject) {
        runCatching {
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(File(dir, "$name.json"))
        }
        aufraeumen()
    }

    fun loeschen(name: String) { File(dir, "$name.json").delete() }

    /** Höchstens 40 Chats behalten, die ältesten fliegen raus. */
    private fun aufraeumen() {
        val chats = dir.listFiles { f -> f.name.startsWith("chat-") }?.sortedByDescending { it.lastModified() } ?: return
        chats.drop(40).forEach { it.delete() }
    }
}
