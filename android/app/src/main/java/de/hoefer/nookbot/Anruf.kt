package de.hoefer.nookbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import de.hoefer.nookbot.data.Settings
import de.hoefer.nookbot.ui.AnrufActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Anrufe der Bots: Eine Routine oder ein Auslöser ist ausdrücklich „als Anruf“ eingestellt → der Pi reiht das Ergebnis ein,
 * das Handy klingelt (Vollbild über dem Sperrbildschirm), nach dem Annehmen liest der Bot vor und hört dann zu.
 * Während des Gesprächs ist es bei Android als eigener Anruf angemeldet (Telecom): Bluetooth/Auto, Halten bei echtem Anruf.
 */
object Anruf {
    data class Info(val id: Long, val bot: String, val name: String, val sid: String, val text: String, val titel: String, val eingehend: Boolean)

    enum class Zustand { Frei, Klingelt, Laeuft }

    /** Wohin der Ton im Gespräch geht (Lautsprecher, Hörmuschel, Headset, Uhr) – im Anruf-Bildschirm umschaltbar. */
    val ziele = MutableStateFlow<List<androidx.core.telecom.CallEndpointCompat>>(emptyList())
    val ziel = MutableStateFlow<androidx.core.telecom.CallEndpointCompat?>(null)
    fun istUhr(name: CharSequence) = Regex("(?i)watch|uhr|galaxy fit|fitbit").containsMatchIn(name)
    fun tonNach(z: androidx.core.telecom.CallEndpointCompat) {
        val c = control ?: return
        c.launch { protokoll("Ton gewechselt: ${z.name} (${c.requestEndpointChange(z)})") }
    }

    /** Kurzes Protokoll je Gespräch (Pegel, erkannter Text, Fehler – kein Ton) – geht beim Auflegen an den Pi. */
    private val zeilen = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val zeitFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY)
    fun protokoll(z: String) { if (zeilen.size < 200) zeilen.add(zeitFmt.format(java.util.Date()) + " " + z) }
    private fun protokollSenden(ctx: Context) {
        val z = synchronized(zeilen) { zeilen.toList().also { zeilen.clear() } }
        if (z.isEmpty()) return
        val s = Settings(ctx)
        Thread {
            runCatching {
                de.hoefer.nookbot.net.Api({ s.serverUrl }, { s.chatToken }).post("/api/diagnose", JSONObject().put("art", "anruf")
                    .put("app", BuildConfig.VERSION_NAME).put("geraet", Build.MODEL).put("zeilen", org.json.JSONArray(z)))
            }
        }.start()
    }

    val zustand = MutableStateFlow(Zustand.Frei)
    @Volatile var aktuell: Info? = null
    @Volatile internal var control: CallControlScope? = null

    const val CH_ANRUF = "anrufe"
    const val CH_WACHE = "erreichbar"
    const val CH_GESPRAECH = "gespraech"
    const val NOTIF_ANRUF = 7001
    const val NOTIF_WACHE = 7002
    private const val KLINGELN_MS = 45_000L

    /** Ein Anruf vom Pi (aus Wache, Ereignissen oder Meldungsabfrage). Jeder Anruf klingelt nur einmal. */
    fun eingang(ctx: Context, o: JSONObject) {
        val id = o.optLong("id")
        val prefs = ctx.getSharedPreferences("nookbot", Context.MODE_PRIVATE)
        synchronized(this) {
            if (id <= prefs.getLong("anrufGesehen", 0)) return
            prefs.edit().putLong("anrufGesehen", id).apply()
        }
        val info = Info(id, o.optString("bot"), o.optString("name", "Bot"), o.optString("s"), o.optString("text"), o.optString("titel"), true)
        // Älter als 10 Minuten oder schon ein Gespräch: nur als verpasster Anruf
        if (System.currentTimeMillis() / 1000.0 - o.optDouble("ts", 0.0) > 600 || zustand.value != Zustand.Frei) verpasst(ctx, info)
        else klingeln(ctx, info)
    }

    /** Merken, welche Anrufe es schon gab – beim ersten Start keine alten nachholen. */
    fun stand(ctx: Context, last: Long) {
        val prefs = ctx.getSharedPreferences("nookbot", Context.MODE_PRIVATE)
        if (!prefs.contains("anrufGesehen")) prefs.edit().putLong("anrufGesehen", last).apply()
    }

    fun klingeln(ctx: Context, info: Info) {
        aktuell = info
        zustand.value = Zustand.Klingelt
        kanaele(ctx)
        val voll = PendingIntent.getActivity(ctx, 1, Intent(ctx, AnrufActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val annehmen = PendingIntent.getActivity(ctx, 2, Intent(ctx, AnrufActivity::class.java).setAction(AnrufActivity.ANNEHMEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val ablehnen = PendingIntent.getBroadcast(ctx, 3, Intent(ctx, AnrufAblehnen::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(ctx, CH_ANRUF)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("${info.name} ruft an")
            .setContentText(info.titel.ifBlank { "NookBot" })
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(voll, true)
            .setContentIntent(voll)
            .setOngoing(true)
            .setColor(0xFFFF3B4E.toInt())
        if (Build.VERSION.SDK_INT >= 31) {
            b.setStyle(Notification.CallStyle.forIncomingCall(Person.Builder().setName(info.name).setImportant(true).build(), ablehnen, annehmen))
        } else {
            b.addAction(Notification.Action.Builder(null, "Ablehnen", ablehnen).build())
            b.addAction(Notification.Action.Builder(null, "Annehmen", annehmen).build())
        }
        val n = b.build().apply { flags = flags or Notification.FLAG_INSISTENT }  // klingelt, bis jemand reagiert
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ANRUF, n)
        CoroutineScope(Dispatchers.Main).launch {
            delay(KLINGELN_MS)
            if (aktuell?.id == info.id && zustand.value == Zustand.Klingelt) { ende(ctx); verpasst(ctx, info) }
        }
    }

    /** Selbst anrufen (Knopf im Chat): kein Klingeln, gleich das Gespräch. */
    fun anrufen(ctx: Context, bot: String, name: String, sid: String) {
        aktuell = Info(0, bot, name, sid, "", "", false)
        zustand.value = Zustand.Klingelt
        ctx.startActivity(Intent(ctx, AnrufActivity::class.java).setAction(AnrufActivity.ANNEHMEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Angenommen (aus dem Anruf-Bildschirm, also sichtbar): Dienst fürs Gespräch starten. */
    fun annehmen(ctx: Context) {
        if (aktuell == null) return
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ANRUF)
        zustand.value = Zustand.Laeuft
        ctx.startForegroundService(Intent(ctx, AnrufDienst::class.java).setAction(AnrufDienst.GESPRAECH))
    }

    fun ende(ctx: Context) {
        protokoll("aufgelegt")
        protokollSenden(ctx)
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ANRUF)
        if (zustand.value == Zustand.Laeuft) ctx.startService(Intent(ctx, AnrufDienst::class.java).setAction(AnrufDienst.AUFLEGEN))
        zustand.value = Zustand.Frei
        aktuell = null
    }

    private fun verpasst(ctx: Context, info: Info) {
        kanaele(ctx)
        val open = PendingIntent.getActivity(ctx, info.sid.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse("nookbot://chat/${info.sid}"), ctx, de.hoefer.nookbot.ui.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = info.text.replace("**", "")
        ctx.getSystemService(NotificationManager::class.java).notify(("verpasst" + info.id).hashCode(), Notification.Builder(ctx, Benachrichtigung.CH_ANTWORT)
            .setSmallIcon(R.drawable.ic_notify).setContentTitle("Verpasster Anruf: ${info.name}")
            .setContentText(text.lineSequence().firstOrNull { it.isNotBlank() } ?: info.titel)
            .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).setColor(0xFFFF3B4E.toInt()).build())
    }

    fun kanaele(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ANRUF, "Anrufe der Bots", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Ein Bot ruft an (nur bei Routinen und Auslösern, die als Anruf eingestellt sind)"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        // Android verlangt für ein laufendes Gespräch eine sichtbare Anruf-Benachrichtigung (ohne Ton)
        nm.createNotificationChannel(NotificationChannel(CH_GESPRAECH, "Laufendes Gespräch", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_WACHE, "Erreichbar für Anrufe", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Hält die Verbindung zum Pi, damit Anrufe sofort ankommen"
            setShowBadge(false)
        })
    }

    /** Ab Android 14 muss der Vollbild-Anruf erlaubt sein (sonst klingelt es nur als Benachrichtigung). */
    fun vollbildErlaubt(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
}

/** „Ablehnen“ in der Anruf-Benachrichtigung. */
class AnrufAblehnen : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Anruf.ende(ctx)
}

/**
 * Vordergrund-Dienst für zweierlei:
 * – ERREICHBAR: hält eine Abfrage beim Pi offen (/api/anrufe/warten), damit Anrufe sofort klingeln (wenn in den Einstellungen an).
 * – GESPRAECH: das laufende Gespräch, bei Android als eigener Anruf angemeldet (Mikrofon auch bei aus­geschaltetem Bildschirm).
 */
class AnrufDienst : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wache = false
    private var gespraech = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Anruf.kanaele(this)
        when (intent?.action) {
            GESPRAECH -> { gespraech = true; vordergrund(); telecom() }
            AUFLEGEN -> {
                gespraech = false
                scope.launch { runCatching { Anruf.control?.disconnect(DisconnectCause(DisconnectCause.LOCAL)) }; Anruf.control = null }
                if (wache) vordergrund() else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
            STOPP -> { wache = false; if (!gespraech) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
            else -> if (!wache) { wache = true; vordergrund(); warten() }
        }
        return START_STICKY
    }

    private fun vordergrund() {
        val info = Anruf.aktuell
        val n = if (gespraech && info != null) {
            val auflegen = PendingIntent.getBroadcast(this, 4, Intent(this, AnrufAblehnen::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val zeigen = PendingIntent.getActivity(this, 5, Intent(this, AnrufActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            Notification.Builder(this, Anruf.CH_GESPRAECH).setSmallIcon(R.drawable.ic_notify).setContentTitle("Gespräch mit ${info.name}")
                .setContentIntent(zeigen).setOngoing(true).setCategory(Notification.CATEGORY_CALL).apply {
                    if (Build.VERSION.SDK_INT >= 31) style = Notification.CallStyle.forOngoingCall(Person.Builder().setName(info.name).build(), auflegen)
                    else addAction(Notification.Action.Builder(null, "Auflegen", auflegen).build())
                }.build()
        } else Notification.Builder(this, Anruf.CH_WACHE).setSmallIcon(R.drawable.ic_notify).setContentTitle("Erreichbar für Anrufe der Bots").build()
        var typ = 0
        if (gespraech) typ = typ or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        if (wache && Build.VERSION.SDK_INT >= 34) typ = typ or ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        if (typ == 0 && Build.VERSION.SDK_INT >= 34) typ = ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        startForeground(Anruf.NOTIF_WACHE, n, typ)
    }

    /** Bei Android als Anruf anmelden – Ton über Headset/Auto, ein echter Anruf hält unser Gespräch an. */
    private fun telecom() = scope.launch {
        val info = Anruf.aktuell ?: return@launch
        runCatching {
            val cm = CallsManager(this@AnrufDienst)
            cm.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
            // Angenommen ist schon (im Anruf-Bildschirm) – bei Android direkt als laufendes Gespräch anmelden
            cm.addCall(CallAttributesCompat(info.name, Uri.parse("nookbot:${info.bot}"), CallAttributesCompat.DIRECTION_OUTGOING,
                CallAttributesCompat.CALL_TYPE_AUDIO_CALL, CallAttributesCompat.SUPPORTS_SET_INACTIVE),
                onAnswer = { }, onDisconnect = { if (Anruf.zustand.value == Anruf.Zustand.Laeuft) Anruf.ende(this@AnrufDienst) },
                onSetActive = { }, onSetInactive = { }) {
                Anruf.control = this
                vordergrund()  // Anruf-Benachrichtigung nach dem Anmelden noch einmal – Telecom wartet darauf
                // Ton auf den Lautsprecher (Android nimmt bei Anrufen sonst die Hörmuschel) – außer Headset oder Auto sind verbunden
                // Uhren (Pixel Watch) melden sich auch als Bluetooth-Gerät – die nimmt die Automatik nicht, wählbar bleiben sie
                launch {
                    val ziele = availableEndpoints.first()
                    val bt = ziele.filter { it.type == androidx.core.telecom.CallEndpointCompat.TYPE_BLUETOOTH && !Anruf.istUhr(it.name) }
                    val ziel = ziele.firstOrNull { it.type == androidx.core.telecom.CallEndpointCompat.TYPE_WIRED_HEADSET } ?: bt.firstOrNull()
                        ?: ziele.firstOrNull { it.type == androidx.core.telecom.CallEndpointCompat.TYPE_SPEAKER }
                    if (ziel != null) Anruf.protokoll("Ton: ${ziel.name} (${requestEndpointChange(ziel)})")
                }
                launch { availableEndpoints.collect { Anruf.ziele.value = it } }
                launch { currentCallEndpoint.collect { Anruf.ziel.value = it } }
                launch {
                    android.util.Log.i("NookBot", "Telecom angemeldet, setze aktiv")
                    val r = runCatching { setActive() }
                    android.util.Log.i("NookBot", "Telecom aktiv: ${r.getOrNull()} ${r.exceptionOrNull()}")
                    Anruf.protokoll("Telecom aktiv: ${r.getOrNull() ?: r.exceptionOrNull()}")
                }
            }
        }.onFailure { android.util.Log.w("NookBot", "Telecom", it); Anruf.protokoll("Telecom-Fehler: $it") }  // ohne Telecom geht das Gespräch trotzdem
    }

    private fun warten() = scope.launch {
        val s = Settings(this@AnrufDienst)
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(75, TimeUnit.SECONDS).build()
        val prefs = getSharedPreferences("nookbot", MODE_PRIVATE)
        while (isActive && wache) {
            try {
                val seit = prefs.getLong("anrufGesehen", -1)
                val req = Request.Builder().url(s.serverUrl.trimEnd('/') + "/api/anrufe/warten?seit=${maxOf(seit, 0)}&sekunden=50")
                    .header("Authorization", "Bearer ${s.chatToken}").build()
                val o = client.newCall(req).execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); JSONObject(r.body?.string() ?: "{}") }
                if (seit < 0) { Anruf.stand(this@AnrufDienst, o.optLong("last")); continue }
                val a = o.optJSONArray("anrufe")
                for (i in 0 until (a?.length() ?: 0)) kotlinx.coroutines.withContext(Dispatchers.Main) { Anruf.eingang(this@AnrufDienst, a!!.getJSONObject(i)) }
            } catch (_: Exception) {
                delay(30_000)  // Pi oder Netz weg – später wieder
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val GESPRAECH = "gespraech"
        const val AUFLEGEN = "auflegen"
        const val STOPP = "stopp"

        fun erreichbar(ctx: Context, an: Boolean) {
            val s = Settings(ctx)
            if (an && s.configured) ctx.startForegroundService(Intent(ctx, AnrufDienst::class.java))
            else ctx.startService(Intent(ctx, AnrufDienst::class.java).setAction(STOPP))
        }
    }
}
