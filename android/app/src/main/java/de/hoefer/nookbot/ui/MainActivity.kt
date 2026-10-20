package de.hoefer.nookbot.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    private val vm: NookViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!vm.back()) { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        handle(intent)
        de.hoefer.nookbot.Benachrichtigung.planen(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            val toast by vm.toast.collectAsState()
            val theme by vm.theme.collectAsState()
            val stack by vm.stack.collectAsState()
            LaunchedEffect(toast) {
                toast?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show(); vm.say(null) }
            }
            LaunchedEffect(stack) { de.hoefer.nookbot.AppState.openChat = (stack.lastOrNull() as? Route.Chat)?.sid }
            NookTheme(theme) {
                val view = LocalView.current
                val dark = P.dark
                SideEffect {
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                val scale by vm.textScale.collectAsState()
                val d = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale * scale)) {
                Surface(Modifier.fillMaxSize(), color = P.bg) {
                    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                        when (val r = stack.last()) {
                            Route.Home -> HomeScreen(vm)
                            is Route.Chat -> ChatScreen(vm, r.sid)
                            is Route.BotEdit -> BotScreen(vm, r.id)
                            Route.RundeNeu -> RundeNeuScreen(vm)
                            Route.Freigaben -> FreigabenScreen(vm)
                            Route.Datenbanken -> DatenbankenScreen(vm)
                            is Route.DatenbankEdit -> DatenbankEditScreen(vm, r.index)
                            Route.Routinen -> RoutinenScreen(vm)
                            is Route.RoutineEdit -> RoutineEditScreen(vm, r.id)
                            Route.Wissen -> WissenScreen(vm)
                            Route.Zugriffe -> ZugriffeScreen(vm)
                            Route.Mehr -> MehrScreen(vm)
                            Route.Settings -> SettingsScreen(vm)
                            Route.Erinnerungen -> ErinnerungenScreen(vm)
                            Route.Archiv -> ArchivScreen(vm)
                            Route.Ablaeufe -> AblaeufeScreen(vm)
                            Route.Gedaechtnis -> GedaechtnisScreen(vm)
                        }
                    }
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** nookbot://chat/<id> – aus Benachrichtigungen (ntfy) direkt in die Unterhaltung. */
    private fun handle(i: Intent?) {
        if (i?.getStringExtra("ziel") == "erinnerungen") { vm.go(Route.Erinnerungen); return }
        val u = i?.data ?: return
        if (u.scheme == "nookbot" && u.host == "chat") u.lastPathSegment?.let { vm.openChat(it) }
    }

    override fun onResume() {
        super.onResume()
        vm.onForeground(true)
    }

    override fun onPause() {
        vm.onForeground(false)
        super.onPause()
    }
}
