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
import app.atlas.mapcore.CoreConfig
import app.atlas.mapcore.MapCore
import app.atlas.mapview.vk.VkBench
import app.atlas.mapview.vk.VkBenchScreen
import app.atlas.mapview.vk.VkFactsScreen
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.CiScreens
import app.atlas.nav.Dest
import app.atlas.nav.Links
import java.io.File
import kotlinx.coroutines.launch

/**
 * The one activity: edge to edge, a splash screen until the saved map view is read, the app's navigation, and the ways
 * in: `geo:` links, openstreetmap.org links, shared text, and CI's `atlas.screen` / `atlas.ci` extras.
 */
class MainActivity : ComponentActivity() {
    private var navigator: BackStackNavigator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // The core's first-frame line counts from here (`vk first frame`, plan-vector §3).
        if (app.atlas.mapview.MapEngine.startedAt == 0L) app.atlas.mapview.MapEngine.startedAt = android.os.SystemClock.uptimeMillis()
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
        intent.getStringExtra(EXTRA_CI_THEME)?.let { t -> services.ciTheme = app.atlas.mapview.Theme.entries.firstOrNull { it.name == t } }
        // The bench shortcut: CI's home frame (the Westerkerk, from the bundled canal ring) on the phone, with the phone's own location
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
        // VK facts and VK bench (plan-vector A0): the phone's Vulkan, and the stand-in measured on it
        if (showVulkanScreen(intent)) return
        offerBenchShortcuts()
        // The map's core starts with the first map screen, on its own thread beside Compose's first frame (T8), behind
        // the crash guard; it draws the maps where it has tiles, today's map elsewhere (the transitional rule until A5),
        // and a phone that cannot run it shows T2's honest state in the map's place.
        startCore(this, intent)
        finishOsmSignIn(intent)
        app.atlas.mapview.MapEngine.online = { services.connectivity.online.value && !services.config.offline }

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
        intent.getStringExtra(EXTRA_CI_THEME)?.let { t -> services.ciTheme = app.atlas.mapview.Theme.entries.firstOrNull { it.name == t } }
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
        if (showVulkanScreen(intent)) return
        if (finishOsmSignIn(intent)) return
        stackFor(intent)?.let { navigator?.reset(it) }
    }

    /**
     * The browser's redirect back from signing in to OpenStreetMap ([app.atlas.services.OsmAccount.REDIRECT]): its code is
     * exchanged for the token off the main thread, and the user stays where they were. True when the intent was one.
     */
    private fun finishOsmSignIn(intent: Intent): Boolean {
        val uri = intent.dataString ?: return false
        if (intent.action != Intent.ACTION_VIEW || !uri.startsWith(app.atlas.services.OsmAccount.REDIRECT)) return false
        val services = services
        services.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { services.osmAccount.finish(uri) }
                .onFailure { android.util.Log.w("Atlas", "OpenStreetMap sign-in: ${it.message}") }
        }
        return true
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The pipeline cache goes back to disk when the app leaves the screen or memory is short (D5.6).
        MapCore.sharedOrNull()?.trimMemory(level)
    }

    /** VK facts or VK bench, when the intent asks for one; true when shown. */
    private fun showVulkanScreen(intent: Intent): Boolean {
        val screen = intent.getStringExtra(EXTRA_SCREEN)
        if (screen != SCREEN_VKFACTS && screen != SCREEN_VKBENCH) return false
        val config = coreConfig(this, intent)
        if (screen == SCREEN_VKFACTS) {
            // The crash guard's reset only in the debug and bench builds (D5.10: the owner's retry by hand).
            setContent { VkFactsScreen(config, guardReset = BuildConfig.TIMINGS) }
        } else {
            val length = when (intent.getStringExtra(EXTRA_VK_BENCH)) {
                "full" -> VkBench.Length.FULL
                "quick" -> VkBench.Length.QUICK
                "ci" -> VkBench.Length.CI
                "pills" -> VkBench.Length.PILLS
                else -> null // the owner picks Quick, Full or Pills on the screen
            }
            setContent { VkBenchScreen(config, length) }
        }
        return true
    }

    private fun writeScreenList() = File(filesDir, CI_SCREENS_FILE).writeText(screenList())

    /**
     * Debug and bench builds ([BuildConfig.TIMINGS]): launcher shortcuts (a long press on the icon), so a phone is read
     * without adb: CI's home frame (the Westerkerk with each frame's timings), VK facts, VK bench, and the GL facts.
     * Each starts a fresh task, so the home's camera is the demo's even when the app was open elsewhere. Their labels are
     * for the developer only (release builds have none), hence not translated.
     */
    private fun offerBenchShortcuts() {
        if (!BuildConfig.TIMINGS) return
        val app = applicationContext
        Thread({
            runCatching {
                val shortcuts = app.getSystemService(ShortcutManager::class.java) ?: return@runCatching
                if (shortcuts.dynamicShortcuts.map { it.id }.toSet() == setOf(SHORTCUT_TIMINGS, SHORTCUT_VKFACTS, SHORTCUT_VKBENCH, SHORTCUT_GLCAPS)) {
                    return@runCatching
                }
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
                        ShortcutInfo.Builder(app, SHORTCUT_VKFACTS).setShortLabel("VK facts").setLongLabel("Device and Vulkan facts")
                            .setIcon(icon).setIntent(open(SCREEN_VKFACTS, bench = false)).setRank(1).build(),
                        ShortcutInfo.Builder(app, SHORTCUT_VKBENCH).setShortLabel("VK bench").setLongLabel("Vulkan bench")
                            .setIcon(icon).setIntent(open(SCREEN_VKBENCH, bench = false)).setRank(2).build(),
                        ShortcutInfo.Builder(app, SHORTCUT_GLCAPS).setShortLabel("GL facts").setLongLabel("Device and GL facts")
                            .setIcon(icon).setIntent(open(SCREEN_GLCAPS, bench = false)).setRank(3).build(),
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

        /** CI: the home's map in this theme (a [app.atlas.mapview.Theme] name, `DAY`), whatever the live forecast says. */
        const val EXTRA_CI_THEME = "atlas.ci.theme"
        const val SCREEN_LIST = "list"

        /** CI: the map view's SVG filter probes through AGSL, the GPU path, against the CPU's. */
        const val SCREEN_FILTERS = "filters"

        /** CI: the device facts and the OpenGL ES capabilities (not in [screenList]: the bench run opens it by name). */
        const val SCREEN_GLCAPS = "glcaps"

        /** VK facts (plan-vector A0): the phone's Vulkan as the core reads it, with Copy (not in [screenList]). */
        const val SCREEN_VKFACTS = "vkfacts"

        /** VK bench (plan-vector A0): the stand-in measured on the phone's Vulkan (not in [screenList]). */
        const val SCREEN_VKBENCH = "vkbench"

        /** VK bench's length: "quick", "full" or "ci" (absent: the owner chooses on the screen). */
        const val EXTRA_VK_BENCH = "atlas.vk.bench"

        /**
         * Debug and bench builds: where the crash guard aborts on purpose (CoreConfig names them all): a stage as it
         * begins (instance, facts, device, cache, surface, frames, profiles, close); `pipelines` and `colour` at their
         * first pipeline, `pipelines:<name>` and `colour:<name>` at that pipeline or shader module
         * (`pipelines:composite`); `<stage>:release[:<kind>]` as the stage's first thing (of that kind) goes, in VK
         * bench's 10-bit colour stage a 10-bit one (`colour:release:frame`, `colour:release:swapchain`).
         */
        const val EXTRA_VK_PLANT = "atlas.vk.plant"

        /** Debug builds: the Khronos validation layer packaged in the APK, on. */
        const val EXTRA_VK_VALIDATION = "atlas.vk.validation"

        /** CI: a `vk frame` line per frame. */
        const val EXTRA_VK_CI = "atlas.vk.ci"
        private const val SHORTCUT_VKFACTS = "bench-vkfacts"
        private const val SHORTCUT_VKBENCH = "bench-vkbench"

        /** The core's options for this launch: the planted stage, validation and CI's log only in debug and bench builds. */
        fun coreConfig(context: android.content.Context, intent: Intent): CoreConfig = CoreConfig.of(
            context,
            plant = if (BuildConfig.TIMINGS) intent.getStringExtra(EXTRA_VK_PLANT).orEmpty() else "",
            validation = BuildConfig.TIMINGS && intent.getBooleanExtra(EXTRA_VK_VALIDATION, false),
            ciLog = BuildConfig.TIMINGS && intent.getBooleanExtra(EXTRA_VK_CI, false),
        )

        /**
         * Debug and bench builds: the map as a phone without usable Vulkan shows it, T2's honest state (plan-vector K11);
         * `--ez atlas.vk.off true`.
         */
        const val EXTRA_VK_OFF = "atlas.vk.off"

        /** Debug and bench builds: `texture` draws the map's core into a TextureView (HWUI frosts the pills, K11b). */
        const val EXTRA_VK_HOST = "atlas.vk.host"

        /** Debug and bench builds: a scripted run of the home's map for CI (`transition`: K11's switch and back). */
        const val EXTRA_VK_SCRIPT = "atlas.vk.script"

        /**
         * Starts the process's core on a thread of its own (once), through the maps' engine, which draws the maps with
         * it from then on ([app.atlas.mapview.MapEngine.startCore]); the debug switches above apply in debug and bench
         * builds.
         */
        fun startCore(context: android.content.Context, intent: Intent) {
            val config = coreConfig(context.applicationContext, intent)
            val engine = app.atlas.mapview.MapEngine
            if (BuildConfig.TIMINGS) {
                if (intent.getStringExtra(EXTRA_VK_HOST) == "texture") engine.host = app.atlas.mapview.vk.VkHost.TEXTURE
                intent.getStringExtra(EXTRA_VK_SCRIPT)?.let { engine.script = it }
            }
            val off = if (BuildConfig.TIMINGS && intent.getBooleanExtra(EXTRA_VK_OFF, false)) "the debug switch atlas.vk.off is on" else null
            engine.startCore(config, off, context.applicationContext.assets)
            if (off == null) engine.warmPacks()
        }

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
