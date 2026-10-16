package app.atlas

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.CiScreens
import app.atlas.nav.Dest
import app.atlas.nav.Links
import java.io.File

/**
 * The one activity: edge to edge, a splash screen until the saved map view is read, the app's navigation, and the ways
 * in: `geo:` links, openstreetmap.org links, shared text, and CI's `atlas.screen` / `atlas.ci` extras.
 */
class MainActivity : ComponentActivity() {
    private var navigator: BackStackNavigator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val services = services
        val intent = intent

        // CI: list the screens it can open, and leave.
        if (intent.getStringExtra(EXTRA_SCREEN) == SCREEN_LIST) {
            writeScreenList()
            finish()
            return
        }
        if (intent.getBooleanExtra(EXTRA_CI, false)) {
            services.ciDemo = true
            services.useDemoLocation(Services.WESTERKERK)
        }
        // The bench shortcut: CI's home frame (the bundled Westerkerk) on the phone, with the phone's own location
        if (intent.getBooleanExtra(EXTRA_BENCH, false)) services.ciDemo = true
        // CI's debug screen: the SVG filter probes through the GPU's AGSL path (the map view's FilterProbesScreen)
        if (intent.getStringExtra(EXTRA_SCREEN) == SCREEN_FILTERS) {
            setContent { app.atlas.mapview.FilterProbesScreen() }
            return
        }
        // CI's other debug screen: what the phone's OpenGL ES offers (logcat gets it too)
        if (intent.getStringExtra(EXTRA_SCREEN) == SCREEN_GLCAPS) {
            setContent { app.atlas.mapview.GlCapsScreen() }
            return
        }
        offerBenchShortcuts()

        val camera = services.startCamera
        splash.setKeepOnScreenCondition { !camera.isCompleted }
        enableEdgeToEdge()
        val start = if (savedInstanceState == null) stackFor(intent) else null
        setContent {
            val nav = rememberSaveable(saver = BackStackNavigator.Saver) { BackStackNavigator(start ?: listOf(Dest.Home())) }
            SideEffect { navigator = nav }
            AtlasRoot(services, nav)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Already running: write the list, and stay where the user is.
        if (intent.getStringExtra(EXTRA_SCREEN) == SCREEN_LIST) {
            writeScreenList()
            return
        }
        if (intent.getBooleanExtra(EXTRA_CI, false)) {
            services.ciDemo = true
            services.useDemoLocation(Services.WESTERKERK)
        }
        if (intent.getBooleanExtra(EXTRA_BENCH, false)) services.ciDemo = true
        if (intent.getStringExtra(EXTRA_SCREEN) == SCREEN_FILTERS) {
            setContent { app.atlas.mapview.FilterProbesScreen() }
            return
        }
        // CI's other debug screen: what the phone's OpenGL ES offers (logcat gets it too)
        if (intent.getStringExtra(EXTRA_SCREEN) == SCREEN_GLCAPS) {
            setContent { app.atlas.mapview.GlCapsScreen() }
            return
        }
        stackFor(intent)?.let { navigator?.reset(it) }
    }

    private fun writeScreenList() = File(filesDir, CI_SCREENS_FILE).writeText(screenList())

    /**
     * Debug and bench builds ([BuildConfig.TIMINGS]): two launcher shortcuts (a long press on the icon), so a phone is read
     * without adb. One opens CI's home frame, the Westerkerk with each frame's timings; the other the device and GL facts.
     * Each starts a fresh task, so the home's camera is the demo's even when the app was open elsewhere. Their labels are
     * for the developer only (release builds have none), hence not translated.
     */
    private fun offerBenchShortcuts() {
        if (!BuildConfig.TIMINGS) return
        val app = applicationContext
        Thread({
            runCatching {
                val shortcuts = app.getSystemService(ShortcutManager::class.java) ?: return@runCatching
                if (shortcuts.dynamicShortcuts.map { it.id }.toSet() == setOf(SHORTCUT_TIMINGS, SHORTCUT_GLCAPS)) return@runCatching
                fun open(screen: String, bench: Boolean) = Intent(Intent.ACTION_MAIN)
                    .setClass(app, MainActivity::class.java)
                    .putExtra(EXTRA_SCREEN, screen)
                    .putExtra(EXTRA_BENCH, bench)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                val icon = Icon.createWithResource(app, R.mipmap.ic_launcher)
                shortcuts.setDynamicShortcuts(
                    listOf(
                        ShortcutInfo.Builder(app, SHORTCUT_TIMINGS).setShortLabel("Timings").setLongLabel("Westerkerk timings")
                            .setIcon(icon).setIntent(open("home", bench = true)).setRank(0).build(),
                        ShortcutInfo.Builder(app, SHORTCUT_GLCAPS).setShortLabel("GL facts").setLongLabel("Device and GL facts")
                            .setIcon(icon).setIntent(open(SCREEN_GLCAPS, bench = false)).setRank(1).build(),
                    ),
                )
            }
        }, "atlas-shortcuts").apply { isDaemon = true }.start()
    }

    companion object {
        /** CI: the screen to open, by its name in [CiScreens] (or a route); "list" writes the names and finishes. */
        const val EXTRA_SCREEN = "atlas.screen"

        /** CI: true puts the app at the demo location (the Westerkerk) instead of the phone's GPS. */
        const val EXTRA_CI = "atlas.ci"
        const val SCREEN_LIST = "list"

        /** CI: the map view's SVG filter probes through AGSL, the GPU path, against the CPU's. */
        const val SCREEN_FILTERS = "filters"

        /** CI: the device facts and the OpenGL ES capabilities (not in [screenList]: the bench run opens it by name). */
        const val SCREEN_GLCAPS = "glcaps"

        /** The bench shortcut: true opens the home on CI's demo camera (the Westerkerk) but keeps the phone's location. */
        const val EXTRA_BENCH = "atlas.bench"
        private const val SHORTCUT_TIMINGS = "bench-timings"
        private const val SHORTCUT_GLCAPS = "bench-glcaps"
        const val CI_SCREENS_FILE = "ci_screens.txt"

        /** Every screen CI opens: the app's ([CiScreens]) and the map view's filter probes. */
        fun screenList(): String = CiScreens.list() + " " + SCREEN_FILTERS

        /** The back stack an intent asks for, or null to keep what is there. */
        fun stackFor(intent: Intent): List<Dest>? {
            intent.getStringExtra(EXTRA_SCREEN)?.let { name -> return CiScreens.stackFor(name) }
            return when (intent.action) {
                Intent.ACTION_VIEW -> intent.dataString?.let { Links.forUri(it) }
                Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Links.forText(it) }
                else -> null
            }
        }
    }
}
