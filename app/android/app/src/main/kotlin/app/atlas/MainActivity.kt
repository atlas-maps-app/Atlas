package app.atlas

import android.content.Intent
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
        if (intent.getBooleanExtra(EXTRA_CI, false)) services.useDemoLocation(Services.WESTERKERK)

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
        if (intent.getBooleanExtra(EXTRA_CI, false)) services.useDemoLocation(Services.WESTERKERK)
        stackFor(intent)?.let { navigator?.reset(it) }
    }

    private fun writeScreenList() = File(filesDir, CI_SCREENS_FILE).writeText(CiScreens.list())

    companion object {
        /** CI: the screen to open, by its name in [CiScreens] (or a route); "list" writes the names and finishes. */
        const val EXTRA_SCREEN = "atlas.screen"

        /** CI: true puts the app at the demo location (the Westerkerk) instead of the phone's GPS. */
        const val EXTRA_CI = "atlas.ci"
        const val SCREEN_LIST = "list"
        const val CI_SCREENS_FILE = "ci_screens.txt"

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
