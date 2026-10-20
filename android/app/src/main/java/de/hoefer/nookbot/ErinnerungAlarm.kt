package de.hoefer.nookbot

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import de.hoefer.nookbot.data.Erinnerung
import de.hoefer.nookbot.data.Settings
import de.hoefer.nookbot.net.Api
import de.hoefer.nookbot.ui.MainActivity
import org.json.JSONArray

/**
 * Erinnerungen pünktlich auf dem Handy: die Liste kommt vom Pi (Bots legen sie an), die App stellt dafür
 * Wecker (AlarmManager). Beim Klingeln: Benachrichtigung + dem Pi melden (einmalige → erledigt, wiederkehrende → nächster Termin).
 */
object ErinnerungAlarm {
    private const val CH = "erinnerungen"
    private const val MAX = 40

    /** Wecker für alle offenen Erinnerungen stellen (alte ersetzen). Merkt sich die Liste für den Neustart des Handys. */
    fun planen(ctx: Context, list: List<Erinnerung>) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val prefs = ctx.getSharedPreferences("nookbot", Context.MODE_PRIVATE)
        // alte Wecker weg
        prefs.getString("erinnerungIds", "")!!.split(",").filter { it.isNotBlank() }.forEach { am.cancel(pi(ctx, it, "")) }
        val jetzt = System.currentTimeMillis()
        val offen = list.filter { !it.erledigt && (it.millis ?: 0) > jetzt - 60_000 }.sortedBy { it.millis }.take(MAX)
        offen.forEach { e ->
            val t = maxOf(e.millis ?: return@forEach, jetzt + 2_000)
            val p = pi(ctx, e.id, e.text)
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p)
        }
        prefs.edit().putString("erinnerungIds", offen.joinToString(",") { it.id })
            .putString("erinnerungen", JSONArray().apply { list.forEach { put(it.json()) } }.toString()).apply()
    }

    /** Nach Neustart des Handys: aus der gemerkten Liste neu stellen. */
    fun neuStellen(ctx: Context) {
        val raw = ctx.getSharedPreferences("nookbot", Context.MODE_PRIVATE).getString("erinnerungen", null) ?: return
        runCatching { planen(ctx, Erinnerung.list(JSONArray(raw))) }
    }

    /** Liste vom Pi holen und Wecker stellen (beim Nachsehen alle paar Minuten). */
    fun abgleichen(ctx: Context) {
        val s = Settings(ctx)
        if (!s.configured) return
        val api = Api({ s.serverUrl }, { s.chatToken })
        planen(ctx, Erinnerung.list(api.get("/api/erinnerungen").optJSONArray("erinnerungen")))
    }

    private fun pi(ctx: Context, id: String, text: String) = PendingIntent.getBroadcast(ctx, id.hashCode(),
        Intent(ctx, ErinnerungReceiver::class.java).putExtra("id", id).putExtra("text", text),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun zeigen(ctx: Context, id: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Erinnerungen", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 7, Intent(ctx, MainActivity::class.java).putExtra("ziel", "erinnerungen"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(id.hashCode(), Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Erinnerung")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setColor(0xFFFF3B4E.toInt())
            .build())
    }
}

class ErinnerungReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        ErinnerungAlarm.zeigen(ctx, id, intent.getStringExtra("text") ?: "")
        val app = ctx.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val s = Settings(app)
                val r = Api({ s.serverUrl }, { s.chatToken }).post("/api/erinnerungen/$id/gezeigt")
                ErinnerungAlarm.planen(app, Erinnerung.list(r.optJSONArray("erinnerungen")))
            } catch (_: Exception) {
                // Pi gerade nicht erreichbar: beim nächsten Abgleich wird nachgezogen
            } finally {
                pending.finish()
            }
        }.start()
    }
}
