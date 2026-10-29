package app.atlas.mapview

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.atlas.geo.LatLon
import app.atlas.mapcore.CoreConfig
import app.atlas.mapcore.MapCore
import app.atlas.mapcore.UserEdit
import app.atlas.mapview.svg.SvgFonts
import app.atlas.mapview.vk.PackList
import app.atlas.mapview.vk.VkHost
import app.atlas.mapview.svg.TurbulenceCache
import app.atlas.render.JpegEncoder
import app.atlas.render.Relief
import app.atlas.render.RenderOptions
import app.atlas.render.live.LiveRegions
import app.atlas.render.live.WxHour
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** The forecast the live moment is lit by, for a place: what is to hand now, without waiting for the network. */
fun interface MapWeather {
    fun hours(at: LatLon): List<WxHour>?
}

/** What draws the maps (plan-vector K11, T2): the app's Vulkan core where it has tiles, today's map alone, or neither. */
sealed interface CoreState {
    /** Today's map alone: no core in this process (the JVM's tests, previews). */
    data object Canvas : CoreState

    /** The core is starting (its device and pipeline cache, behind the crash guard). */
    data object Starting : CoreState

    /** The core draws the maps where it has tiles, today's map elsewhere (the transitional rule, until A5). */
    class Ready(val core: MapCore) : CoreState

    /** T2's honest state instead of a map: the phone's graphics driver cannot draw it ([reason]); the rest works. */
    data class Off(val reason: String) : CoreState
}

/**
 * The map engine's process-wide wiring, set once by the app: where region data comes from ([regions]), the forecast
 * for the live moment ([weather]), the engine's flags ([options]). Every [AtlasMap] draws through it. Changes reach the
 * maps on screen through [invalidate].
 */
object MapEngine {
    /** The regions the maps draw; null draws "no map data" (tests that only exercise gestures). */
    @Volatile
    var regions: LiveRegions? = null
        set(value) {
            field = value
            invalidate()
        }

    @Volatile
    var weather: MapWeather? = null

    /**
     * The engine's flags (amsterdam.py's ATLAS_AO, ATLAS_DAYLIGHT, …): the renders' defaults, but for the Look settings'
     * haze, vignette and grain, off until the app's settings say otherwise ([screenEffects]), and the cloud shadows, off:
     * today's map warps its last frame under the finger, and they would ride on it ([RenderOptions.cloudShadows]).
     */
    @Volatile
    var options: RenderOptions = RenderOptions(haze = false, vignette = false, grain = false, cloudShadows = false)

    /**
     * Largest raster scale (device pixels per dp) for exact frames; the screen's density when lower. The frames are drawn
     * at the screen's own pixels, as sharp as the boards' vectors: a phone 1080 px wide shows the 412 dp board at 2.62
     * device pixels per dp, and a frame drawn at 2 and stretched there was visibly soft beside the board.
     */
    @Volatile
    var maxRasterScale: Float = 4f

    /**
     * What draws the maps (K11): today's map alone ([CoreState.Canvas]) until the app starts the core ([startCore]);
     * then the core under today's map where it has tiles, or T2's honest state where the phone cannot run it. Compose
     * state: the maps on screen follow it.
     */
    var core: CoreState by mutableStateOf<CoreState>(CoreState.Canvas)
        private set

    /** The core's window: a SurfaceView (D5.1), or the TextureView where the debug switch `atlas.vk.host` asks. */
    @Volatile
    var host: VkHost = VkHost.SURFACE

    /**
     * The painter switch (the app's Look settings' "True depth" chip, off; and VK facts' "Buildings" button in debug and
     * bench builds): what stands up is drawn in the renderer's painter's order (whole buildings by one point each, which
     * makes some melt into each other: a drawing's look) instead of the core's true depth. Each map binding hands it to
     * the core; [painterOrder] hands it to the core at once.
     */
    @Volatile
    var painter: Boolean = false
        private set

    /** Sets the painter switch, and hands it to the running core (off the main thread). */
    fun painterOrder(on: Boolean) {
        painter = on
        (core as? CoreState.Ready)?.core?.painterOrder(on)
    }

    /**
     * The screen's haze, vignette and grain (the app's Look settings; off by default, as each costs frame time: the user,
     * 27 October; on, the renders' look): off, the core draws the map without them, its own colours there and less work
     * a pixel. Each map binding hands them to the
     * core; [screenEffects] hands them to the core at once, and to today's map (the renderer's port, where the core has
     * no tiles) through [options], whose frames are drawn again.
     */
    @Volatile
    var haze: Boolean = false
        private set

    @Volatile
    var vignette: Boolean = false
        private set

    @Volatile
    var grain: Boolean = false
        private set

    /**
     * The parts of the picture the app's diagnostics leave out of the core's frames (MapPart's bits, the camera state's
     * parts): every layer and effect, each switched in a debug build's Look settings, so the map's panel shows on the
     * phone what each one costs (ENGINE.md, "On the user's phone first"). Each map's frames carry them with its own
     * layers' (MapController.publishCamera); [leaveOut] draws the maps again.
     */
    @Volatile
    var leftOut: Long = 0L
        private set

    /**
     * Sets [leftOut] to [parts] and draws every map again with it; today's map (the renderer's port, where the core has no
     * tiles) leaves out the rain and the golden wash with them, which its screen layers draw ([options]).
     */
    fun leaveOut(parts: Set<MapPart>) {
        val off = MapParts.bits(parts)
        val o = options
        val rain = MapPart.RAIN !in parts
        val wash = MapPart.GOLDEN_WASH !in parts
        if (o.rain != rain || o.wash != wash) options = o.copy(rain = rain, wash = wash)
        if (off == leftOut && o.rain == rain && o.wash == wash) return
        leftOut = off
        invalidate()
    }

    /**
     * A debug build's frame pacing: each frame presented at a timeline its recent work can make, as alpha 15 drew
     * (`CP/frame/Pacing.hpp`), instead of as soon as it is ready. Handed to the core as it starts (CoreMap) and by [pacing].
     */
    @Volatile
    var paced: Boolean = false
        private set

    /** Sets [paced] and hands it to the running core (any thread). */
    fun pacing(on: Boolean) {
        paced = on
        (core as? CoreState.Ready)?.core?.setPacing(on)
    }

    /** Sets the screen's haze, vignette and paper grain, and hands them to the running core (off the main thread). */
    fun screenEffects(haze: Boolean, vignette: Boolean, grain: Boolean) {
        this.haze = haze
        this.vignette = vignette
        this.grain = grain
        (core as? CoreState.Ready)?.core?.screenEffects(haze, vignette, grain)
        val o = options
        if (o.haze != haze || o.vignette != vignette || o.grain != grain) {
            options = o.copy(haze = haze, vignette = vignette, grain = grain)
            invalidate()
        }
    }

    /** When the app's first screen began (SystemClock.uptimeMillis): the core's first-frame line counts from it. */
    @Volatile
    var startedAt: Long = 0

    /** Whether this launch found no pipeline cache on disk (a first launch: the first-frame line's `first`). */
    @Volatile
    var firstLaunch: Boolean = true
        private set

    /** How the core was started (the honest state's VK facts read through it), null until [startCore]. */
    @Volatile
    var coreConfig: CoreConfig? = null
        private set

    /** Whether the maps may use the network now (a first visit's render pack, K20a). */
    @Volatile
    var online: () -> Boolean = { false }

    /** The core's first-frame line is written once a process (`vk first frame`, Appendix C). */
    internal val firstFrameLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Debug and bench builds: a scripted run CI asked for (`atlas.vk.script`), read by the screen that runs it. */
    @Volatile
    var script: String? = null

    @Volatile
    private var packList: PackList? = null

    private var coreStarted = false

    /**
     * Starts the process's core with [config] on a thread of its own (T8: with the first map screen, behind the crash
     * guard), once, and readies its text from [assets] there before the maps draw with it ([TextRounding.install]: the
     * kerning rule and the bundled fonts the ground's text draws with). [off] (the debug switch `atlas.vk.off`, or null)
     * shows T2's honest state instead, as a phone that cannot run the core does.
     */
    fun startCore(config: CoreConfig, off: String? = null, assets: AssetManager? = null) {
        synchronized(this) {
            if (coreStarted) return
            coreStarted = true
        }
        coreConfig = config
        firstLaunch = !File(config.cacheDir, "vk/pipelines.bin").isFile
        if (off != null) {
            core = CoreState.Off(off)
            return
        }
        core = CoreState.Starting
        Thread({
            val made = MapCore.shared(config)
            // The core's text, once, before the maps draw with it: the canal names and the runways' designators are
            // drawn from these fonts. Until 27 October only VK facts loaded them, so the app's map drew none.
            if (made != null && assets != null) runCatching { TextRounding.install(assets) }
            core = when {
                made != null -> CoreState.Ready(made)
                // No core's library in this process at all (the JVM's tests): today's map alone, as without a start.
                !MapCore.present() -> CoreState.Canvas
                else -> CoreState.Off(MapCore.lastError())
            }
        }, "atlas-core-start").apply { isDaemon = true }.start()
    }

    /** Sets what draws the maps without starting a core (the JVM's tests of the core's states). */
    internal fun coreStateForTests(s: CoreState) {
        core = s
    }

    /** The packs the core draws from: the APK's bundled render packs and the regions' library's (made once). */
    fun packs(assets: AssetManager): PackList = packList ?: synchronized(this) {
        packList ?: PackList(
            AssetPacks(assets), regions?.library, online = { online() }, worker = packWorker, windows = { windowsDir() },
            windowVersion = { runCatching { MapCore.Window.version() }.getOrNull() },
        )
            .also { packList = it }
    }

    /**
     * Overpass's JSON for a window's box (south, west, north, east), with the window's query (ENGINE.md step 3),
     * streamed into the file given; the app sets it with its own servers. Null: the phone builds no windows.
     */
    @Volatile
    var windowData: ((DoubleArray, File) -> Unit)? = null

    /**
     * Overpass's changes to a window's data since a date (an augmented diff of the window's query, its XML), from a
     * server whose data is not older than the second date (ENGINE.md step 3: a window built again after a week); the
     * app sets it with its own servers. Null: an aged window is fetched whole again.
     */
    @Volatile
    var windowDiff: ((DoubleArray, String, String) -> ByteArray)? = null

    /**
     * A window's data from Atlas's own packs, newer than the date given where one is (ENGINE.md step 3: the windows'
     * first source, before Overpass); the app sets it with the published packs. Null: Overpass alone.
     */
    @Volatile
    var windowPacks: ((app.atlas.mapcore.MapCore.Window, java.time.Instant?) -> app.atlas.mapview.vk.Windows.Packed?)? = null

    /**
     * A window's pack from its country's tiles, as the phone's own core builds it (ENGINE.md step 3, one pack per
     * country: taken before any data, where the phone has none of the window); the app sets it with the published
     * packs. Null: every window is built from its data.
     */
    @Volatile
    var windowTiles: ((app.atlas.mapcore.MapCore.Window) -> app.atlas.mapview.vk.Windows.Tiled?)? = null

    /**
     * The user's own edits to OpenStreetMap the windows draw until the map's data carries them (ENGINE.md step 6),
     * pending or sent, oldest first; the app sets it with its EditQueue. Read on the windows' background thread.
     */
    @Volatile
    var windowEdits: () -> List<UserEdit> = { emptyList() }

    /**
     * The user edited what stands at ([lat], [lon]), or took an edit back: the windows whose data reaches the place are
     * built again with [windowEdits] before anything else, so the map draws the edit within seconds.
     */
    fun edited(lat: Double, lon: Double) {
        windows()?.edited(lat, lon)
    }

    /** Where the windows the phone built are kept: the engine's cache's `windows/`; null without a cache. */
    fun windowsDir(): File? = cacheDir?.let { File(it, "windows") }

    @Volatile
    private var windowsBuilder: app.atlas.mapview.vk.Windows? = null

    /** The phone's windows as they are, without making them (null before the first was asked for): the map's status reads them. */
    val windowsNow: app.atlas.mapview.vk.Windows? get() = windowsBuilder

    /** Whether the phone builds windows now (the app's: not in CI's demo run, which asks no Overpass server for them). */
    @Volatile
    var windowsAllowed: () -> Boolean = { true }

    /** The windows the phone builds, online, once the app gave a cache and their data's source; else null. */
    fun windows(): app.atlas.mapview.vk.Windows? {
        if (!online() || !windowsAllowed()) return null
        val dir = windowsDir() ?: return null
        val data = windowData ?: return null
        return windowsBuilder ?: synchronized(this) {
            windowsBuilder ?: app.atlas.mapview.vk.Windows(dir, data, windowDiff, edits = { windowEdits() }, packs = windowPacks, tiles = windowTiles)
                .also { windowsBuilder = it }
        }
    }

    /**
     * Reads the packs' list ahead of the first map, off the main thread, so that the home's first look knows where the
     * core has tiles without reading them itself (the app calls it as it starts the core).
     */
    fun warmPacks(assets: AssetManager) {
        val list = packs(assets)
        if (!list.loaded) packWorker.execute { runCatching { list.load() } }
    }

    private val packWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "atlas-core-packs").apply { isDaemon = true } }

    /** The APK's `tiles/` assets, stored uncompressed: read, and mapped by the core where they lie. */
    private class AssetPacks(private val assets: AssetManager) : PackList.BundledSource {
        override fun open(name: String): java.io.InputStream? = try {
            assets.open("tiles/$name")
        } catch (_: java.io.IOException) {
            null
        }

        override fun range(name: String): PackList.Range? = try {
            val fd = assets.openFd("tiles/$name")
            PackList.Range(fd.parcelFileDescriptor.fd, fd.startOffset, fd.length, fd)
        } catch (_: java.io.IOException) {
            null
        }
    }

    @Volatile
    private var fonts: SvgFonts? = null

    /** Where the engine keeps what it computes once for many frames and launches (the filters' noise, in `noise/`); null keeps nothing. */
    @Volatile
    var cacheDir: File? = null
        private set

    /**
     * Keeps the CPU filters' noise in [dir]`/noise` from now on ([TurbulenceCache]: mapped files, at most about 60 MB
     * held and 160 MB on disk), and warms it up in the background for a full-screen map on this device ([warmUp] with the
     * display's size). The app calls it once at start; the same directory again does nothing.
     */
    fun useCacheDir(dir: File, warm: Boolean = true) {
        synchronized(this) {
            if (cacheDir == dir) return
            cacheDir = dir
            // the cache before (if any) is let go, not closed: a frame being made may still read it
            TurbulenceCache.shared = TurbulenceCache(File(dir, "noise"))
        }
        if (!warm) return
        val m = runCatching { android.content.res.Resources.getSystem().displayMetrics }.getOrNull() ?: return
        if (m.density <= 0f || m.widthPixels <= 0 || m.heightPixels <= 0) return
        // the map's frame is the view's size in whole dp (MapController.liveView)
        warmUp(Math.round(m.widthPixels / m.density).toFloat(), Math.round(m.heightPixels / m.density).toFloat(), m.density)
    }

    /** Keeps nothing again (tests: the next test class must not write into a deleted directory). */
    internal fun forgetCacheDir() = synchronized(this) {
        cacheDir = null
        TurbulenceCache.shared = null
    }

    /**
     * Readies the noise of the screen-wide filters (paper grain, material grain, cloud shadows, water) for frames of
     * [widthDp] × [heightDp] at [density], on a background thread: the fields kept from earlier launches are mapped and
     * their tiles checked before the first frame needs them. A first launch has nothing kept yet; its first frame makes
     * the noise and keeps it. Returns the thread (null without a cache).
     */
    fun warmUp(widthDp: Float, heightDp: Float, density: Float): Thread? {
        val cache = TurbulenceCache.shared ?: return null
        val scale = minOf(density, maxRasterScale)
        val s = EngineFrame.filterScale(widthDp, heightDp, scale)
        val w = EngineFrame.pixels(widthDp, scale)
        val h = EngineFrame.pixels(heightDp, scale)
        val t = Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            try {
                cache.warmUp(w, h, s, s)
            } catch (e: Exception) {
                // a damaged cache is no reason to stop: frames make their noise as they go
                android.util.Log.w(EngineFrames.TAG, "noise warm-up failed: $e")
            }
        }, "atlas-noise-warmup")
        t.isDaemon = true
        t.start()
        return t
    }

    private val maps = CopyOnWriteArrayList<() -> Unit>()

    init {
        // relief.py writes the lit ground as a JPEG through macOS's sips; on the phone, Android's encoder at the same quality
        Relief.jpeg = JpegEncoder { png ->
            val b = BitmapFactory.decodeByteArray(png, 0, png.size) ?: error("the relief image did not decode")
            ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 80, it); b.recycle() }.toByteArray()
        }
    }

    /** The engine's typefaces, from this library's assets. */
    fun fonts(assets: AssetManager): SvgFonts = fonts ?: synchronized(this) { fonts ?: SvgFonts.fromAssets(assets).also { fonts = it } }

    /** Uses [f] for the engine's text (tests read the fonts from the source tree). */
    internal fun useFonts(f: SvgFonts) {
        fonts = f
    }

    /** Asks every map on screen for a new frame: region data, the forecast or the options changed. */
    fun invalidate() {
        for (m in maps) m()
    }

    internal fun register(onChange: () -> Unit) {
        maps += onChange
    }

    internal fun unregister(onChange: () -> Unit) {
        maps -= onChange
    }
}
