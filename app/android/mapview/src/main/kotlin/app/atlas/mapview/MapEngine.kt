package app.atlas.mapview

import android.content.res.AssetManager
import android.os.Build
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.mapcore.CoreConfig
import app.atlas.mapcore.MapCore
import app.atlas.mapcore.UserEdit
import app.atlas.mapview.palette.Palette
import app.atlas.mapview.svg.SvgFonts
import app.atlas.mapview.vk.PackList
import app.atlas.mapview.vk.VkHost
import app.atlas.render.live.WxHour
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** The forecast the live moment is lit by, for a place: what is to hand now, without waiting for the network. */
fun interface MapWeather {
    fun hours(at: LatLon): List<WxHour>?

    /**
     * The time zone of the place the forecast at [at] is of, where one is to hand (the forecast's own, which holds for
     * any place on Earth); null where none is. The live moment's clock is in it: the hour the shops open by.
     */
    fun zone(at: LatLon): java.time.ZoneId? = null
}

/**
 * A country whose published tiles the core draws: its pack's [id] and [name] ("Netherlands"), and its pack's [box]. A
 * place is the country's where the box holds it: its windows come from the tiles, and a window the tiles do not list
 * (a border town over the line, which the box reaches) is built on the phone from its data, as everywhere. [levels]:
 * tiles of this phone's core are published, which hold each frame cell's levels (ENGINE.md one pack per country, step
 * 7), so the core draws the country to the levels' reach ([app.atlas.mapview.vk.PackList.LEVELS_REACH]); else only its
 * windows, to theirs.
 */
data class CountryTiles(val id: String, val name: String, val box: BBox, val levels: Boolean = false) {
    fun holds(lat: Double, lon: Double): Boolean = lat in box.south..box.north && lon in box.west..box.east
}

/** What draws the maps (plan-vector K11, T2): the app's Vulkan core, or T2's honest state. */
sealed interface CoreState {
    /** No core in this process (the JVM's tests, previews): the maps draw their paper and overlays alone. */
    data object None : CoreState

    /** The core is starting (its device and pipeline cache, behind the crash guard). */
    data object Starting : CoreState

    /** The core draws every map, every place (ENGINE.md step 3: today's map has left the phone). */
    class Ready(val core: MapCore) : CoreState

    /** T2's honest state instead of a map: the phone's graphics driver cannot draw it ([reason]); the rest works. */
    data class Off(val reason: String) : CoreState
}

/**
 * The map engine's process-wide wiring, set once by the app: the forecast for the live moment ([weather]), the core
 * ([core]), the windows' sources and the user's Look settings. Every [AtlasMap] draws through it. Changes reach the maps
 * on screen through [invalidate].
 */
object MapEngine {
    /** The maps' log tag: every line the map and the core's Kotlin side write goes to logcat under it. */
    const val TAG = "AtlasMap"

    @Volatile
    var weather: MapWeather? = null

    /**
     * The renders' dusk (amsterdam.py's ATLAS_DAYLIGHT, the port's RenderOptions.daylight) the core's moments are made
     * with: off, as the renders draw and the app always drew.
     */
    const val DAYLIGHT = false

    /**
     * What draws the maps (K11): nothing ([CoreState.None]) until the app starts the core ([startCore]); then the core,
     * or T2's honest state where the phone cannot run it. Compose state: the maps on screen follow it.
     */
    var core: CoreState by mutableStateOf<CoreState>(CoreState.None)
        private set

    /** The core's window: a SurfaceView (D5.1), or the TextureView where the debug switch `atlas.vk.host` asks. */
    @Volatile
    var host: VkHost = VkHost.SURFACE

    /**
     * The screen's haze, vignette and grain (the app's Look settings; off by default, as each costs frame time: the user,
     * 27 October; on, the renders' look): off, the core draws the map without them, its own colours there and less work
     * a pixel. Each map binding hands them to the core; [screenEffects] hands them to the running core at once.
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

    /** Sets [leftOut] to [parts] and draws every map again with it. */
    fun leaveOut(parts: Set<MapPart>) {
        val off = MapParts.bits(parts)
        if (off == leftOut) return
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

    /**
     * A debug build's switch of the screen's compression, on when unset: the swapchain's images with the driver's own
     * framebuffer compression (its default), or without it where the driver lets an app ask ([compressionSwitch]).
     * Off, the user's PowerVR showed none of its glitches in busy frames, but heavy frames jiggled more, so the
     * driver's is the default (the user, 2 November). Handed to the core as it starts ([startCore]) and by
     * [compression].
     */
    @Volatile
    var compressed: Boolean = true
        private set

    /** Sets [compressed] and hands it to the running core, which makes its swapchain again with it (off the main thread). */
    fun compression(on: Boolean) {
        compressed = on
        (core as? CoreState.Ready)?.core?.setCompression(on)
    }

    /** Whether the running core's driver obeys [compression]'s off (VK_EXT_image_compression_control_swapchain). */
    val compressionSwitch: Boolean get() = (core as? CoreState.Ready)?.core?.compressionSwitch == true

    /**
     * The Map look's Fast refresh, on when unset: the window's frames said to come at 120 a second while the map moves
     * and at 60 at rest, both with fixed-source compatibility, so that the display runs fast while the map moves and
     * keeps the platform's touch boost (ENGINE.md gain 11); off, at 60 with default compatibility throughout, the vote
     * of alphas up to 27, so that a rate switch's step in brightness can be told from the driver's flicker (the user, 3
     * November: "Give it a toggle?"). Handed to the core as it starts ([startCore]) and by [fastRefresh].
     */
    @Volatile
    var fastRefreshOn: Boolean = true
        private set

    /** Sets [fastRefreshOn] and hands it to the running core, whose window is told again at once (off the main thread). */
    fun fastRefresh(on: Boolean) {
        fastRefreshOn = on
        (core as? CoreState.Ready)?.core?.setFastRefresh(on)
    }

    /**
     * Whether the Fast refresh changes anything on [display]: a mode above 60 Hz there, on Android 11 or later, where a
     * window can say its frames' rate (ANativeWindow_setFrameRate). Elsewhere neither vote does anything.
     */
    fun fastRefreshSwitch(display: Display?): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && display?.supportedModes?.any { it.refreshRate > 61f } == true

    /**
     * The world's names' faces (ENGINE.md step 5, "Names"), from the app: the boards' Instrument Serif italic for a
     * city's, a town's and a village's name where a map is given no label face of its own, and the Globe board's Geist
     * (its variable font; the painter draws it at 700) for the names on the globe. Null until the app gives them: a serif italic and a bold sans meanwhile.
     */
    @Volatile
    var nameTypeface: android.graphics.Typeface? = null

    @Volatile
    var globeTypeface: android.graphics.Typeface? = null

    /**
     * The user's strength of TONE (the Map look's Tone: 0 off, 1 the renders', up to 2): the core lights by it from the
     * next moment it is handed (CoreMap.moment); [tone] sets it.
     */
    @Volatile
    var tone: Float = 1f
        private set

    /** Sets [tone] (clamped to 0-2) and draws every map again with it. */
    fun tone(strength: Float) {
        val s = strength.coerceIn(0f, 2f)
        if (s == tone) return
        tone = s
        invalidate()
    }

    /**
     * The user's palette (the Map look's presets and colours): the core takes its ground, streets, water and light from
     * the next moment it is handed (CoreMap.moment, PaletteTheme); today's, the default, changes nothing. [palette] sets it.
     */
    @Volatile
    var palette: Palette = Palette()
        private set

    /** Sets [palette] and draws every map again with it. */
    fun palette(p: Palette) {
        if (p == palette) return
        palette = p
        invalidate()
    }

    /** Sets the screen's haze, vignette and paper grain, and hands them to the running core (off the main thread). */
    fun screenEffects(haze: Boolean, vignette: Boolean, grain: Boolean) {
        this.haze = haze
        this.vignette = vignette
        this.grain = grain
        (core as? CoreState.Ready)?.core?.screenEffects(haze, vignette, grain)
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

    /** Whether the maps may use the network now (the windows' tiles and data). */
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
            // The screen's compression before the first window's swapchain is made, and the Fast refresh before the
            // first window is told its frames' rate, each again should its switch have moved before the core was ready
            // to take it.
            val handed = compressed
            val fast = fastRefreshOn
            made?.setCompression(handed)
            made?.setFastRefresh(fast)
            core = when {
                made != null -> CoreState.Ready(made)
                // No core's library in this process at all (the JVM's tests): no core, as without a start.
                !MapCore.present() -> CoreState.None
                else -> CoreState.Off(MapCore.lastError())
            }
            if (made != null && compressed != handed) made.setCompression(compressed)
            if (made != null && fastRefreshOn != fast) made.setFastRefresh(fastRefreshOn)
        }, "atlas-core-start").apply { isDaemon = true }.start()
    }

    /** Sets what draws the maps without starting a core (the JVM's tests of the core's states). */
    internal fun coreStateForTests(s: CoreState) {
        core = s
    }

    /**
     * The packs the core draws from: the windows on the phone, and in CI's runs the canal ring it puts there (made
     * once).
     */
    fun packs(): PackList = packList ?: synchronized(this) {
        packList ?: PackList(
            windows = { windowsDir() }, windowVersion = { runCatching { MapCore.Window.version() }.getOrNull() }, bench = { benchPacks() },
            world = { worldDir() },
            worldHeld = { ids -> worldCacheDir()?.let { MapCore.worldHeld(it.path, worldCacheBytes, ids) } ?: BooleanArray(ids.size) },
        )
            .also { packList = it }
    }

    /**
     * Debug and bench builds: render packs the map lists beside the windows, which CI puts on its emulator (the canal ring
     * its runs draw at the Westerkerk home: no APK carries a map, and CI's run builds no windows); the app sets it. None
     * on a phone.
     */
    @Volatile
    var benchPacks: () -> List<File> = { emptyList() }

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
     * A window's data from the element packs on the phone (ENGINE.md step 3: an offline region's map, a place's published
     * pack), for a window the phone has nothing of, before Overpass and offline too; the app sets it with its packs.
     * Null: none.
     */
    @Volatile
    var windowLocal: ((app.atlas.mapcore.MapCore.Window) -> app.atlas.mapview.vk.Windows.Packed?)? = null

    /** Whether the phone keeps element packs ([windowLocal]): offline, the windows take theirs too. */
    @Volatile
    var localKept: () -> Boolean = { false }

    /**
     * An element pack on its way to the phone for the places it holds (a place's published pack, fetched by the app on
     * a job of its own): its [name], its box in degrees, and [done] of its [bytes] (−1: not known).
     */
    class PackComing(val name: String, val south: Double, val west: Double, val north: Double, val east: Double, val done: Long, val bytes: Long) {
        fun holds(lat: Double, lon: Double): Boolean = lat in south..north && lon in west..east
    }

    /** The pack the app is fetching now for a place the windows found nothing of on the phone; null while it fetches none. */
    @Volatile
    var packComing: () -> PackComing? = { null }

    /**
     * The countries whose published tiles the core draws (ENGINE.md step 3, one pack per country), from Atlas's published
     * list as last read (packs-latest's, its copy kept for offline); the app sets it once the list is read. Within one
     * of them the windows come from the country's tiles and the core draws to the levels' reach where they are
     * published ([CountryTiles.levels]); while a place's window comes the core draws its own cover there, as anywhere,
     * and the windows say what they are doing ([countryAt]). Empty until the list is read.
     */
    @Volatile
    var countries: List<CountryTiles> = emptyList()

    /** The country whose tiles the core draws at [lat], [lon] ([countries]), or null. */
    fun countryAt(lat: Double, lon: Double): CountryTiles? = countries.firstOrNull { it.holds(lat, lon) }

    /** Whether a country's tiles hold a place, as their index says ([countryHolds]). */
    fun interface CountryHolds {
        /** Whether [country]'s tiles hold the place at [lat], [lon]; null where its tiles' index is not on the phone. */
        fun at(country: CountryTiles, lat: Double, lon: Double): Boolean?
    }

    /**
     * Which places within a country's box its tiles hold (ENGINE.md step 3): a box reaches beyond its country's border
     * (the Netherlands' holds Antwerp and Aachen), and the tiles' index lists the windows the country's data reaches.
     * The app sets it with the published packs (WindowTiles.holds). Asked at each camera on the main thread: it must
     * neither block nor allocate. Null: a country holds its whole box.
     */
    @Volatile
    var countryHolds: CountryHolds? = null

    /**
     * The country whose frame cells' levels the core draws at [lat], [lon], or null: a published country with levels
     * ([CountryTiles.levels]) whose box holds the place and whose tiles do ([countryHolds]; its whole box until its
     * tiles' index is on the phone).
     */
    fun levelsAt(lat: Double, lon: Double): CountryTiles? {
        val holds = countryHolds
        return countries.firstOrNull { it.levels && it.holds(lat, lon) && holds?.at(it, lat, lon) != false }
    }

    /**
     * Whether the place at [lat], [lon] has no country's levels: no published country whose tiles hold its frame cells'
     * levels holds it ([levelsAt]), so beyond its windows the core's cover has nothing there, and the world draws under
     * it from every distance (CameraWriter.WORLD_UNDER; ENGINE.md step 3). So it does within a country's box beyond its
     * border, once the country's tiles' index says so. False until the published list is read: nothing is known of the
     * countries before.
     */
    fun worldUnder(lat: Double, lon: Double): Boolean = countries.isNotEmpty() && levelsAt(lat, lon) == null

    /**
     * The user's own edits that OpenStreetMap accepted, which the windows draw until the map's data carries them
     * (ENGINE.md step 6), oldest first; the app sets it with its EditQueue's mapped edits. One still waiting, or
     * refused, is not drawn. Read on the windows' background thread.
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

    /** Where the windows the phone took or built are kept: [keptDir]'s `windows/`; null where nothing is kept. */
    fun windowsDir(): File? = keptDir?.let { File(it, "windows") }

    /** Where the world is kept (ENGINE.md step 6: world.json, world.idx and the globe): [keptDir]'s `world/`. */
    fun worldDir(): File? = keptDir?.let { File(it, "world") }

    /** The world's own cache of its packs' heads and chunks (ENGINE.md step 6, the core's LRU): [worldDir]'s `cache/`. */
    fun worldCacheDir(): File? = worldDir()?.let { File(it, "cache") }

    /** The world cache's budget in bytes (ENGINE.md step 6: 256 MB, which a setting raises); the app sets it. */
    @Volatile
    var worldCacheBytes: Long = WORLD_CACHE_BYTES

    /** 256 MB: the world cache's budget unless the user raised it. */
    const val WORLD_CACHE_BYTES = 256L shl 20

    /**
     * The world's packs by ranges as the maps' views want them (ENGINE.md step 6), once the app has its world's files
     * ([WorldReads]: each map asks it for a step twice a second, as for its windows); null before, and in CI's runs but
     * their world phase.
     */
    @Volatile
    var worldReads: app.atlas.mapview.vk.WorldReads? = null

    /**
     * The world the kept countries keep (ENGINE.md step 11: [app.atlas.mapview.vk.WorldKeeper]), once the app has its
     * world's files; null before, and in CI's runs but their world phase.
     */
    @Volatile
    var worldKeeper: app.atlas.mapview.vk.WorldKeeper? = null

    @Volatile
    private var windowsBuilder: app.atlas.mapview.vk.Windows? = null

    /** The phone's windows as they are, without making them (null before the first was asked for): the map's status reads them. */
    val windowsNow: app.atlas.mapview.vk.Windows? get() = windowsBuilder

    /** Whether the phone builds windows now (the app's: not in CI's demo run, which asks no Overpass server for them). */
    @Volatile
    var windowsAllowed: () -> Boolean = { true }

    /**
     * Whether the user switched Overpass off (the app's Settings · Data): the windows then come from Atlas's packs alone,
     * and the map says why a place no pack holds stays empty.
     */
    @Volatile
    var overpassOff: () -> Boolean = { false }

    /**
     * Whether the phone keeps a country's tiles whole (an offline region's map, the Maps board's download of the
     * country): the windows then come from them offline too. The app sets it.
     */
    @Volatile
    var tilesKept: () -> Boolean = { false }

    /**
     * The windows the phone builds, once the app gave a cache and their data's source: online, or offline where it keeps
     * a country's tiles or element packs ([tilesKept], [localKept]: the only windows it then takes); else null.
     */
    fun windows(): app.atlas.mapview.vk.Windows? {
        if (!windowsAllowed() || !online() && !tilesKept() && !localKept()) return null
        val dir = windowsDir() ?: return null
        val data = windowData ?: return null
        return windowsBuilder ?: synchronized(this) {
            windowsBuilder ?: app.atlas.mapview.vk.Windows(
                dir, data, windowDiff, edits = { windowEdits() }, packs = windowPacks, tiles = windowTiles, local = windowLocal,
                online = { online() },
                budget = app.atlas.mapview.vk.WindowsBudget(dir, keptPacks = { windowsKept() }),
            ).also { windowsBuilder = it }
        }
    }

    /**
     * The packs whose country's tiles the phone keeps whole and the windows read, by id (the Maps board's download of a
     * country): a window taken from them comes back from the phone at once, so the windows' budget lets those go first.
     * The app sets it.
     */
    @Volatile
    var windowsKept: () -> Set<String> = { emptySet() }

    @Volatile
    private var version: String? = null

    /**
     * The version of the windows this phone's core builds (MapCore.Window.version: its stage P's and its style's), whose
     * country's tiles the windows and the offline regions take; null where no core loads (the JVM's tests).
     */
    fun windowVersion(): String? = version ?: runCatching { MapCore.Window.version() }.getOrNull()?.also { version = it }

    /**
     * Reads the packs' list ahead of the first map, off the main thread, so that the home's first look knows where the
     * core has tiles without reading them itself (the app calls it as it starts the core).
     */
    fun warmPacks() {
        val list = packs()
        if (!list.loaded) packWorker.execute { runCatching { list.load() } }
    }

    private val packWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "atlas-core-packs").apply { isDaemon = true } }

    @Volatile
    private var fonts: SvgFonts? = null

    /**
     * Where the engine keeps what offline use depends on: the windows the phone took or built ([windowsDir]) and the
     * world ([worldDir]). The app gives its files, which Android never clears to free space; until 30 October the
     * windows lived in the cache, which it may clear. Null keeps no window.
     */
    @Volatile
    var keptDir: File? = null
        private set

    /** Keeps the windows under [dir] from now on. The app calls it once at start. */
    fun useKeptDir(dir: File) {
        keptDir = dir
    }

    /** Keeps nothing again (tests: the next test class must not write into a deleted directory). */
    internal fun forgetKeptDir() {
        keptDir = null
    }

    private val maps = CopyOnWriteArrayList<() -> Unit>()

    /** The engine's typefaces, from this library's assets. */
    fun fonts(assets: AssetManager): SvgFonts = fonts ?: synchronized(this) { fonts ?: SvgFonts.fromAssets(assets).also { fonts = it } }

    /** Uses [f] for the engine's text (tests read the fonts from the source tree). */
    internal fun useFonts(f: SvgFonts) {
        fonts = f
    }

    /** Asks every map on screen for a new frame: the packs, the forecast or the Look settings changed. */
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
