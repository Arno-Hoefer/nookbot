package de.hoefer.nookbot

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import de.hoefer.nookbot.data.Settings
import de.hoefer.nookbot.net.Api
import de.hoefer.nookbot.ui.MainActivity
import org.json.JSONObject

/** Was die App gerade zeigt – damit keine Benachrichtigung für die offene Unterhaltung kommt. */
object AppState {
    @Volatile var foreground = false
    @Volatile var openChat: String? = null
}

/**
 * Benachrichtigungen ohne Dauerverbindung: alle ~5 Minuten fragt die App den Adapter nach neuen Meldungen
 * (Antwort fertig, Freigabe wartet, Routine/Runde fertig). Im Android-Tiefschlaf kann der Abstand länger werden.
 */
class Benachrichtigung : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val pending = goAsync()
        Thread {
            try { pruefen(app) } catch (_: Exception) { }
            try { ErinnerungAlarm.abgleichen(app) } catch (_: Exception) { } finally {
                planen(app)
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val ABSTAND_MS = 5 * 60_000L
        const val CH_ANTWORT = "antworten"
        const val CH_FREIGABE = "freigaben"

        fun planen(ctx: Context) {
            val s = Settings(ctx)
            if (!s.configured || !s.listen) return
            ctx.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + ABSTAND_MS, alarm(ctx))
        }

        fun stoppen(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).cancel(alarm(ctx))

        private fun alarm(ctx: Context) = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, Benachrichtigung::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun pruefen(ctx: Context) {
            val s = Settings(ctx)
            if (!s.configured || !s.listen) return
            val prefs = ctx.getSharedPreferences("nookbot", Context.MODE_PRIVATE)
            val api = Api({ s.serverUrl }, { s.chatToken })
            val seit = prefs.getLong("notifyLast", -1)
            val r = api.get("/api/notify?since=${maxOf(seit, 0)}")
            val last = r.optLong("last")
            // Erster Lauf (oder Adapter neu aufgesetzt): nur merken, nichts Altes zeigen
            if (seit >= 0 && last >= seit) {
                val items = r.optJSONArray("items")
                for (i in 0 until (items?.length() ?: 0)) show(ctx, items!!.getJSONObject(i))
            }
            prefs.edit().putLong("notifyLast", last).apply()
        }

        private fun show(ctx: Context, ev: JSONObject) {
            val sid = ev.optString("s").takeIf { it.isNotBlank() && it != "null" }
            val high = ev.optString("prio") == "high"
            // App offen: nur Wichtiges (Freigaben, Test), und nie für die gerade offene Unterhaltung
            if (AppState.foreground && (!high || (sid != null && sid == AppState.openChat))) return
            channels(ctx)
            val open = PendingIntent.getActivity(ctx, (sid ?: "home").hashCode(),
                Intent(Intent.ACTION_VIEW, Uri.parse("nookbot://chat/${sid ?: ""}"), ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val text = ev.optString("text").replace("**", "")
            val n = Notification.Builder(ctx, if (high) CH_FREIGABE else CH_ANTWORT)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(ev.optString("title"))
                .setContentText(text.lineSequence().firstOrNull { it.isNotBlank() } ?: "")
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setColor(0xFFFF3B4E.toInt())
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify((sid ?: ev.optString("title")).hashCode(), n)
        }

        private fun channels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_ANTWORT, "Antworten und Routinen", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CH_FREIGABE, "Freigaben", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Ein Bot möchte etwas eintragen oder entwerfen"
            })
        }
    }
}

/** Nach Neustart des Handys oder App-Update wieder planen. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            Benachrichtigung.planen(ctx)
            ErinnerungAlarm.neuStellen(ctx)
        }
    }
}
