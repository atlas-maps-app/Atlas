package app.atlas.mapview

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.UnitSystem
import app.atlas.mapcore.MapCore
import app.atlas.mapcore.state.CameraWriter
import app.atlas.mapview.svg.SvgFonts
import app.atlas.mapview.vk.CoreMap
import app.atlas.mapview.vk.MapCameraSource
import app.atlas.mapview.vk.MapPlace
import app.atlas.mapview.vk.PackList
import app.atlas.mapview.vk.Windows
import app.atlas.render.live.LiveView
import app.atlas.render.live.Moment
import app.atlas.render.live.RegionFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Content padding in pixels: left, top, right, bottom. */
data class Insets(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f)

/**
 * The map view's engine room, one per [AtlasMap]: the camera to the core (plan-vector K11, [core]: MapEngine.core
 * ready), which draws every place under all of this from the camera this publishes for each vsync ([publishCamera]:
 * during an animation, the animation a frame on too); the overlays the core does not draw, on top (projected with the
 * core's camera, in the drawn pack's own frame); gestures to camera moves, and the camera's animations (fly-to, zoom
 * steps, flings, the snap to north, following the phone).
 *
 * Until the core's first picture is complete its paper covers the map ([CoreMap.cover]). Where the core has no tiles
 * at the camera's place it draws its own cover, the windows it has around it, and the map says why ([MapPlace]): the
 * windows on their way, the way to the map, the world's on its way. Until 5 November today's map (the renderer's port,
 * rasterised on the canvas) drew the places the core had no tiles for; it has left the phone (ENGINE.md step 3).
 *
 * Everything here runs on the main thread.
 */
internal class MapController(initial: MapState, private val scope: CoroutineScope) : GestureListener, RememberObserver {
    private val main = Handler(Looper.getMainLooper())

    /** The camera drawn: the state the map began with, or a screen's own one since ([take]). */
    var state: MapState = initial
        private set

    /** The engine's typefaces, set by the view from its assets. */
    var fonts: SvgFonts? = null

    /** Android's text size, a fraction of the default (Configuration.fontScale): the labels' readable floor follows it. */
    var fontScale = 1f

    /** The labels' face (the boards' Instrument Serif italic), given by the app. */
    var labelTypeface: android.graphics.Typeface? = null
    private val engineChanged: () -> Unit = {
        main.post(repack)
        // what the core's camera carries may have changed with it (the parts the app's diagnostics leave out,
        // MapEngine.leftOut): published again, at rest too, so the core draws it at once rather than at the next touch
        main.post(republish)
        // the countries whose tiles the core draws came to be known (or changed): the map looks again at what the core
        // has at the camera's place, at rest too, so its line is the place's at once rather than at the next touch
        if (MapEngine.countries !== placedCountries) main.post(placeAgain)
        // the user's Tone or palette changed (the Map look): the core's theme is sent again with them, and drawn at once,
        // at rest too
        if (lookChanged()) main.post(retone)
    }

    /** The moment sent again where the user's Tone or palette changed since it was last sent ([sendMoment]). */
    private val retone = Runnable { if (!closed && lookChanged()) update() }

    /** Whether the user's Tone or palette differs from what the core's theme was last sent with. */
    private fun lookChanged() = MapEngine.tone != sentTone || MapEngine.palette != sentPalette

    /** The core's camera published again where what it carries changed (nothing otherwise: the map stays at rest). */
    private val republish = Runnable { if (!closed) publishCamera() }

    /** The countries the map last looked at the camera's place with ([update]); written on the main thread. */
    @Volatile
    private var placedCountries: List<CountryTiles> = emptyList()

    private val placeAgain = Runnable {
        if (!closed && MapEngine.countries !== placedCountries) update()
    }

    /** Bumped on the main thread whenever there is something new to draw; the view's draw reads it. */
    val frameTick = mutableIntStateOf(0)

    /**
     * The canvas's own state changed (ENGINE.md gain 12): whether it draws anything over the core's picture now, which
     * every draw reads; only a draw that draws something reads [frameTick], so where the core draws the map alone (its
     * labels, markers and puck, nothing on the ground) the window over it is not drawn again at each frame.
     */
    val canvasTick = mutableIntStateOf(0)
    var style by mutableStateOf(MapStyle())
    var overlays by mutableStateOf(MapOverlays())
    var debug by mutableStateOf(false)
    var tape by mutableStateOf<Tape?>(null)

    /** The tilt readout while it shows ([TiltReadout]): from a tilt's start to the end of its fade, the canvas draws it. */
    var readout by mutableStateOf<TiltReadoutShown?>(null)
        private set
    var size by mutableStateOf(0 to 0)
    var insets by mutableStateOf(Insets())

    /** The ground frame of the pack the core draws: gestures and overlays use the core's own. */
    var frame by mutableStateOf<RegionFrame?>(null)
        private set

    /** 0..1: how far the navigation view's look ahead has moved the target down the screen. */
    var lookAhead by mutableFloatStateOf(0f)

    var density = 1f
        set(value) {
            if (value != field) {
                field = value
                painter = null
            }
        }

    /** The units of the measuring tape. */
    var units: UnitSystem = UnitSystem.forLocale(Locale.getDefault())

    var onTap: (MapHit) -> Unit = {}
    var onLongPress: (LatLon) -> Unit = {}

    /**
     * A long press on a building where the core draws the map: the building under the finger as the core drew it (its
     * pick, scene/Pick.hpp) in place of [onLongPress]; null to take [onLongPress] alone.
     */
    var onLongPressBuilding: ((MapPick) -> Unit)? = null

    /**
     * A tap on a building where the core draws the map: the building under the finger as the core drew it and where the
     * finger was (view px), in place of [onTap]'s empty hit; null to take [onTap] alone. A marker takes its tap first.
     */
    var onTapBuilding: ((MapPick, Offset) -> Unit)? = null

    /**
     * A tap on the ground where the core draws the map: what the finger's point shows of it as the core drew it (the
     * slot that paints it) and where the finger was (view px), in place of [onTap]'s empty hit; null to take [onTap]
     * alone. A building before it takes the tap ([onTapBuilding]).
     */
    var onTapGround: ((GroundPick, Offset) -> Unit)? = null
    var onCameraMove: (CameraPosition, Boolean) -> Unit = { _, _ -> }

    /** Plays one of Android's haptic feedback constants on the map's view (the view sets it): the tilt's stops. */
    var haptic: (Int) -> Unit = {}

    val gestures = GestureMachine(GestureConfig.forDensity(1f), this)

    /** The overlays' painter, made at the first draw (and again when the density changes). */
    var painter: OverlayPainter? = null
        private set
    private var animation: Job? = null
    private var lookAheadJob: Job? = null
    private var tapeJob: Job? = null

    // The tilt readout: its linger and fade, its painter, where the tilting fingers are.
    private var readoutJob: Job? = null
    private var readoutPainter: TiltReadoutPainter? = null
    private val tiltFingers = FloatArray(6)

    /** The pitch the fingers' tilt began at (a cancelled one goes back to it); null while no fingers tilt. */
    private var tiltFrom: Double? = null

    /** The user tilted while the map followed the course: it keeps their tilt rather than navigation's. */
    private var courseTilted = false
    private var touching = false
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var lastWritten: CameraPosition? = null
    private var lastWrittenByUser = false
    private var lastReported: CameraPosition? = null
    private var lastFix: LatLon? = null
    private var course: Double? = null

    /**
     * The core's side of this map (K11), which draws every place. Null: no core, while it starts or where none runs in
     * this process (the paper and the overlays alone).
     */
    var core by mutableStateOf<CoreMap?>(null)
        private set

    /**
     * The app runs the core (MapEngine.core starting or ready): until this map's [core] is made, it waits for it with
     * the paper alone (no "Loading").
     */
    var coreExpected = false
        set(value) {
            if (value == field) return
            field = value
            // No core after all (none in this process), or one on its way: the map says what it is.
            if (core == null && !closed) noteStatus()
        }

    /** T2's honest state's reason while the phone cannot draw the map (MapEngine.core is off): no map is drawn. Null otherwise. */
    var honest: String? = null
        set(value) {
            if (value == field) return
            field = value
            noteHonest()
            canvasTick.intValue++
        }

    /** Why the core, though running, could not make this map's frame (its map refused): T2's honest state too. */
    var coreRefused by mutableStateOf<String?>(null)
        private set

    /** The honest state's reason, whichever says it: the app's core state, or this map's refusal. */
    val honestReason: String? get() = honest ?: coreRefused

    private fun noteHonest() {
        val why = honestReason ?: return
        if (state.status != MapStatus.NoData(why)) state.status = MapStatus.NoData(why)
        if (state.loading) state.loading = false
    }

    // The core's camera as last published.
    private var cameraRevision = 0L
    private var publishedCamera: CameraPosition? = null
    private var publishedViewport: Viewport? = null
    private var publishedRest = false
    private var publishedLocation: UserLocation? = null
    private var publishedOff = 0L
    private var publishedUnder = false

    /** Whether the core draws the location's puck now (the canvas does not): its camera carries the location. */
    private var coreLocation = false

    /** What the core has at the camera's place, as [update] last found it. */
    private var place = MapPlace.DRAWN

    /** The camera's publication whose complete picture the start's paper waits for: the first once the packs are known (-1 until then). */
    private var startRevision = -1L
    private var coverJob: Job? = null
    private var sentMoment: Moment? = null
    private var sentPlace: LitPlace? = null

    /** The user's Tone (MapEngine.tone) the core's theme was last sent with. */
    @Volatile
    private var sentTone = 1f

    /** The user's palette (MapEngine.palette) the core's theme was last sent with. */
    @Volatile
    private var sentPalette = MapEngine.palette

    /** The labels last handed to the core: the list (by identity), its face, the density, whether the core draws them. */
    private class SentLabels(val labels: List<MapLabel>, val face: android.graphics.Typeface?, val density: Float, val draws: Boolean)
    private var sentLabels: SentLabels? = null
    private var labelsRevision = 0L

    /** Whether the core draws the labels now (the canvas does not); the painter its sprites are drawn with. */
    private var coreLabels = false
    private var spritePainter: LabelPainter? = null

    /** The markers last handed to the core: the list, the density, whether the core draws them, by night or day. */
    private class SentMarkers(val markers: List<Marker>, val density: Float, val draws: Boolean, val night: Boolean)
    private var sentMarkers: SentMarkers? = null
    private var markersRevision = 0L

    /** Whether the core draws the markers now (the canvas does not: from when the core has them); their painter. */
    private var coreMarkers = false
    private var markerPainter: MarkerPainter? = null

    /** The building last asked about to the core (none: no question open). */
    private var sentAsked: AskedBuilding? = null
    private var askedSent = false
    private var askedRevision = 0L

    /** The routes and areas last handed to the core: the lists, the density, whether the core draws them. */
    private class SentRoutes(val routes: List<RouteLine>, val areas: List<AreaOverlay>, val density: Float, val draws: Boolean)
    private var sentRoutes: SentRoutes? = null
    private var routesRevision = 0L

    /**
     * Whether the core draws the routes and areas now (the canvas does not: from when the core has them), so what lies
     * over them (the markers, the puck) is the core's too; the painter of their chips.
     */
    private var coreRoutes = false
    private var routePainter: OverlayPainter? = null
    private var movedAt = 0L
    private var panelLine = ""
    private var panelJob: Job? = null

    @Volatile
    private var warming = false
    private val warmNames = HashSet<String>()
    private val warmLogos = HashSet<String>()

    // Debug figures.
    private val debugText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; color = Color.WHITE }
    private val debugPanel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB0141619.toInt() }
    private val debugRect = RectF()
    private val statusText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val statusPanel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD0141619.toInt() }

    /** The core said something new of its frames (a picture complete, or no longer): the map looks again. */
    private val coreChanged = Runnable {
        if (closed) return@Runnable
        // The core turned the map off for the session (the device lost three times): T2's honest state from now on.
        core?.offReason?.let { if (coreRefused == null) coreRefused = "the core turned the map off: $it" }
        frameTick.intValue++
        logFirstFrame()
        stepCover()
        noteStatus()
    }

    /** The camera came to rest (an animation ended, the fingers lifted): the core's last frame snaps (D4). */
    private val settle = Runnable { if (!closed) publishCamera(force = true) }

    /**
     * The packs on the phone may have changed (a window taken from its country's tiles or built, the world's packs come):
     * the core is handed them again off the main thread, and where they changed the map draws what it can draw now,
     * looks again at what it has at the camera's place, and the windows' next step is asked at once, at rest too, while
     * the core wants more (a pack came, so the steps make progress). Where the list is the same, only the next step,
     * once its half second is over.
     */
    private val repack = Runnable {
        val c = core ?: return@Runnable
        if (closed) return@Runnable
        scope.launch {
            // what changed, and how many stayed (thousands of windows on a phone after a while), off the main thread
            val changed = withContext(Dispatchers.Default) {
                val lines = runCatching { c.handPacks() }.getOrDefault("").lines().filter { it.isNotBlank() }
                val kept = lines.count { it.endsWith(" kept") }
                lines.filterNot { it.endsWith(" kept") } + listOfNotNull("$kept kept".takeIf { kept > 0 })
            }
            if (core !== c || closed) return@launch
            if (changed.isEmpty()) {
                windowsLater()
                return@launch
            }
            Log.i(MapEngine.TAG, "vk packs again: ${changed.joinToString(", ")}")
            publishCamera(force = true)
            windowCheckedAt = 0L
            update()
        }
    }

    /** When the map last asked the core which windows it wants (SystemClock.uptimeMillis). */
    private var windowCheckedAt = 0L

    /**
     * The windows' next step, asked once the last one's half second is over, whatever the camera does: the last one
     * worked on a tile or a window but wrote no pack the core takes (one with nothing to draw, a miss), so there may be
     * more to do, and at rest no camera asks again (it waited for a touch or the minute's tick). Main thread.
     */
    private fun windowsLater() {
        main.removeCallbacks(windowsAgain)
        main.postDelayed(windowsAgain, (windowCheckedAt + WINDOWS_CHECK_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    private val windowsAgain = Runnable { if (!closed) update() }

    /** The ground frame gestures and overlays take: the pack the core draws, kept while the camera names none. */
    private fun noteFrame() {
        val want = drawnPack()?.frame ?: return
        if (frame != want) frame = want
    }

    /** The pack the core draws at the camera now (the one the camera names, PackList.named), or null. */
    private fun drawnPack(): PackList.Pack? {
        val c = core ?: return null
        val v = viewport()
        if (v.isEmpty) return null
        val lv = liveView(state.camera, v)
        return c.packs.named(lv.lat, lv.lon)
    }

    /**
     * What the map says of its picture: the core's, complete and shown; or where it has no tiles at the camera's place,
     * why ([placeStatus]). With no core in this process, that none runs.
     */
    private fun noteStatus() {
        // T2's honest state says what the map is, whatever the core says of its frames.
        if (honestReason != null) return noteHonest()
        val c = core
        val status = when {
            c == null -> if (coreExpected) MapStatus.Loading(null) else MapStatus.NoData(NO_CORE)
            else -> placeStatus() ?: if (c.complete && c.cover.started) MapStatus.Ready else MapStatus.Loading(null)
        }
        if (state.status != status) state.status = status
        val loading = status is MapStatus.Loading || status is MapStatus.Downloading
        if (state.loading != loading) state.loading = loading
    }

    /**
     * What the map is where the core has no tiles at the camera's place ([place]): within the windows' reach what they
     * do ([windowStatus]), or no map data where none will come; beyond it the way to the map; beyond the levels' reach
     * the world on its way, or to come with the first connection. Null where the core draws the place.
     */
    private fun placeStatus(): MapStatus? = when (place) {
        MapPlace.DRAWN -> null
        MapPlace.WINDOWS -> windowStatus() ?: if (MapEngine.windows() == null) MapStatus.NoData(NO_DATA) else null
        MapPlace.NEAR -> MapStatus.NoData(ZOOM_IN)
        MapPlace.WORLD -> if (MapEngine.online()) MapStatus.Downloading(WORLD, -1, -1) else MapStatus.NoData(WORLD_OFFLINE)
    }

    /**
     * What the map is where the core draws a place whose window has not come yet ([MapPlace.WINDOWS]): not on the phone,
     * offline; on its way while the windows take it from its country's tiles, fetch its data or build it; null otherwise.
     */
    private fun windowStatus(): MapStatus? {
        if (place != MapPlace.WINDOWS) return null
        if (!MapEngine.online()) return MapStatus.NoData("offline, and this area is not on the phone yet")
        val target = state.camera.target
        val where = MapEngine.countryAt(target.lat, target.lon)?.name ?: "this area"
        return when (val s = MapEngine.windowsNow?.status) {
            is Windows.Status.Fetching -> MapStatus.Downloading(where, -1, -1, building = !s.tiles)
            is Windows.Status.Building -> MapStatus.Downloading(where, -1, -1, building = true)
            else -> null
        }
    }

    /** The view as the camera sees it: its size, density, the principal point the padding and look ahead move, the region's frame. */
    fun viewport(): Viewport {
        val (w, h) = size
        val i = insets
        val inner = max(0f, h - i.top - i.bottom)
        return Viewport(w, h, density, offsetY = ((i.top - i.bottom) / 2 + lookAhead * LOOK_AHEAD * inner).toDouble(), frame = frame)
    }

    fun attach() {
        MapEngine.register(engineChanged)
        hook(state)
    }

    /**
     * Draws [next] from now on, in place of the state drawn: a screen's map with a camera of its own, over the one map
     * (the user, 1 November: "There is too much re loading the map"). Nothing is rebuilt: the core's map, its surface and
     * tiles stay; the state left keeps its camera for when it is drawn again, and the new camera is
     * drawn at once.
     */
    fun take(next: MapState) {
        val old = state
        if (next === old) return
        stopAnimation()
        unhook(old)
        state = next
        if (closed) return
        hook(next)
        // a camera neither the user's fingers nor this map wrote, told to the screen as such ([update])
        lastWritten = null
        lastReported = null
        update()
        noteStatus()
    }

    /** [s]'s camera moves through this map: its animations, fits and projections. */
    private fun hook(s: MapState) {
        s.controller = this
        // Callers may be on any dispatcher; the camera's animations start on the main thread, where [s] is still the
        // state drawn (one handed over meanwhile jumps, as it would with no map: [MapState.animateTo], [MapState.fit]).
        s.animator = { target, ms ->
            await(withContext(Dispatchers.Main.immediate) { if (state === s) flyTo(target, ms, byUser = false) else jumped(s, target) })
        }
        s.fitter = { box, pad, within ->
            await(withContext(Dispatchers.Main.immediate) { if (state === s) fit(box, pad, within) else jumped(s, s.camera.copy(target = box.center)) })
        }
        s.projector = object : MapState.Projector {
            override fun toLatLon(x: Float, y: Float): LatLon? {
                val v = viewport()
                return if (v.isEmpty) null else CameraOps.groundAt(state.camera, v, x, y)
            }

            override fun toScreen(p: LatLon): Pair<Float, Float>? {
                val v = viewport()
                if (v.isEmpty) return null
                val s = CameraOps.camera(state.camera, v).project(p) ?: return null
                return s.x to s.y
            }

            override fun place(from: CameraPosition, anchor: LatLon, x: Float, y: Float): CameraPosition? {
                val v = viewport()
                return if (v.isEmpty) null else CameraOps.place(CameraOps.clamp(from), v, anchor, x, y)
            }
        }
    }

    fun detach() {
        stopAnimation()
        unhook(state)
        close()
    }

    /** [s]'s camera set to [to] at once, outside this map: a call that began before [s] was handed over ([take]). */
    private fun jumped(s: MapState, to: CameraPosition): Job {
        s.camera = to
        return Job().apply { complete() }
    }

    /** [s] no longer moves through this map: set, its camera jumps. */
    private fun unhook(s: MapState) {
        if (s.controller !== this) return
        s.controller = null
        s.animator = null
        s.fitter = null
        s.projector = null
    }

    private var closed = false

    /** Stops the frame thread without waiting for it (a frame being made stops at its next check); idempotent. */
    private fun close() {
        if (closed) return
        closed = true
        MapEngine.unregister(engineChanged)
        main.removeCallbacks(coreChanged)
        main.removeCallbacks(settle)
        main.removeCallbacks(windowsAgain)
        core?.let(::closeCore)
        core = null
    }

    // -------------------------------------------------------------------------------------------------------------
    // The core (K11).

    /**
     * Draws through [mapCore] from now on, from the packs [packs], into a TextureView where [texture] (else a
     * SurfaceView); null for either: no core again (the paper and the overlays alone). The core is handed its packs and
     * the moment, and its map turned on, off the main thread; until its first picture is complete, the paper covers it
     * (no "Loading").
     */
    fun useCore(mapCore: MapCore?, packs: PackList?, texture: Boolean) {
        val old = core
        if (mapCore == null || packs == null || closed) {
            if (old != null) {
                core = null
                sentLabels = null
                coreLabels = false
                sentMarkers = null
                coreMarkers = false
                sentRoutes = null
                coreRoutes = false
                sentAsked = null
                askedSent = false
                coreLocation = false
                canvasTick.intValue++
                closeCore(old)
                noteChrome()
                update()
                noteStatus()
            }
            return
        }
        if (old != null && old.core === mapCore && old.packs === packs && old.texture == texture) return
        old?.let(::closeCore)
        val c = CoreMap(mapCore, packs, texture, coreChanged)
        core = c
        canvasTick.intValue++
        place = MapPlace.DRAWN
        startRevision = -1L
        sentMoment = null
        sentPlace = null
        sentTone = MapEngine.tone
        sentPalette = MapEngine.palette
        sentLabels = null
        coreLabels = false
        sentMarkers = null
        coreMarkers = false
        sentRoutes = null
        coreRoutes = false
        sentAsked = null
        askedSent = false
        coreLocation = false
        publishedCamera = null
        noteChrome()
        scope.launch {
            val refused = withContext(Dispatchers.Default) { c.bind() }
            if (core !== c) return@launch
            if (refused.isNotEmpty()) {
                // A core that cannot make the map's frame: T2's honest state.
                coreRefused = "the core could not draw the map: $refused"
                noteHonest()
                return@launch
            }
            // The packs are known now: the camera names the pack drawn, so the picture the start's paper waits for is one
            // of that pack.
            publishCamera(force = true)
            update()
            noteFrame()
        }
        update()
    }

    /**
     * The chrome's shapes over the map (K11b's ChromeShapes, LocalChromeShapes): written as the core's keep-outs at each
     * vsync, and frosted by the core only while its picture is the one seen on its SurfaceView ([noteChrome]).
     */
    var chrome: app.atlas.mapview.vk.ChromeShapes? = null
        set(value) {
            field = value
            noteChrome()
        }

    /**
     * The core's picture must follow the chrome's shapes (ChromeShapes.onChange): the glass under a frosted shape that
     * moved, or the keep-outs alone come to rest. The core draws a frame with them where they are.
     */
    fun chromeMoved() {
        val c = core ?: return
        c.source.chromeMoved()
        c.core.requestFrame()
    }

    /** Tells the chrome's shapes whether the core frosts under them, and the core's camera where to find them. */
    private fun noteChrome() {
        val c = core
        c?.source?.chrome = chrome
        val coreSeen = c != null && !c.texture && c.cover.canvas < 0.5f
        chrome?.let { if (it.coreDraws != coreSeen) it.coreDraws = coreSeen }
    }

    /** The core's map goes off if [c] drew it (off the main thread: it waits for the core's render thread). */
    private fun closeCore(c: CoreMap) {
        Thread({ runCatching { c.close() } }, "atlas-core-map-off").apply { isDaemon = true }.start()
    }

    /**
     * Publishes the core's camera ([CoreMap.source]): the camera now and, during an animation, the same animation a frame
     * on ([next] at [nextNs]), with the view and the pack the core draws; and asks the core for a frame. Nothing when
     * nothing changed since the last publication, unless [force] (the rest flag after a motion).
     */
    private fun publishCamera(next: CameraPosition? = null, nowNs: Long = System.nanoTime(), nextNs: Long = 0L, force: Boolean = false) {
        val c = core ?: return
        val v = viewport()
        if (v.isEmpty) return
        val cam = state.camera
        val rest = !touching && animation?.isActive != true && lookAheadJob?.isActive != true
        // the location's puck once the core's picture shows with nothing over it (the start's paper gone), as its labels,
        // and nothing on the ground that the canvas draws over the core's picture (a route ribbon, an area: until the
        // core has them)
        val location = overlays.location.takeIf { c.cover.started && (!overlays.onGround || coreRoutes) }
        // the parts the core leaves out: the style's trees and soft shadows where its layers have none, and what the app's
        // diagnostics switch off (MapEngine.leftOut)
        val off = (if (MapLayer.TREES in style.layers) 0L else MapPart.TREES.bit) or
            (if (MapLayer.SHADOWS in style.layers) 0L else MapPart.BUILDING_SHADOWS.bit) or MapEngine.leftOut
        val lv = liveView(cam, v)
        // where no country's levels are, the world under the core's cover from every distance
        val under = MapEngine.worldUnder(lv.lat, lv.lon)
        if (!force && next == null && cam == publishedCamera && v == publishedViewport && rest == publishedRest &&
            location == publishedLocation && off == publishedOff && under == publishedUnder
        ) {
            return
        }
        val n = if (next != null && nextNs > nowNs) liveView(next, v) else null
        // the window whose box holds the target, whatever the distance: zooming hands nothing over; where none does yet,
        // the nearest, so the core draws the windows it has around the place
        val pack = c.packs.named(lv.lat, lv.lon)
        c.source.publish(
            MapCameraSource.Samples(
                revision = ++cameraRevision,
                t0 = nowNs, lat0 = lv.lat, lon0 = lv.lon, distance0 = lv.distance, bearing0 = lv.bearing, pitch0 = lv.pitch, principalY0 = lv.cyDp,
                t1 = if (n != null) nextNs else nowNs, lat1 = n?.lat ?: lv.lat, lon1 = n?.lon ?: lv.lon, distance1 = n?.distance ?: lv.distance,
                bearing1 = n?.bearing ?: lv.bearing, pitch1 = n?.pitch ?: lv.pitch, principalY1 = n?.cyDp ?: lv.cyDp,
                principalXDp = lv.widthDp / 2.0, widthPx = v.width, heightPx = v.height, widthDp = lv.widthDp, heightDp = lv.heightDp,
                density = v.density.toDouble(), rest = rest, follow = state.follow != Follow.NONE,
                pack = pack?.number ?: 0L, profile = pack?.profile ?: CameraWriter.PROFILE_NONE, draw = true,
                location = location?.let { MapCameraSource.Location(it.at.lat, it.at.lon, it.accuracyM.toFloat(), it.heading?.toFloat()) },
                off = off, worldUnder = under,
            ),
        )
        publishedCamera = cam
        publishedOff = off
        publishedUnder = under
        publishedViewport = v
        publishedRest = rest
        publishedLocation = location
        if (coreLocation != (location != null)) {
            coreLocation = location != null
            canvasTick.intValue++
        }
        movedAt = SystemClock.uptimeMillis()
        c.core.requestFrame()
        if (debug) startPanel()
    }

    /**
     * The start's paper over the core ([CoreMap.cover]), stepped each frame from the core's first complete picture until
     * it has faded out; nothing covers the core's picture after.
     */
    private fun stepCover() {
        val c = core ?: return
        if (!advanceCover(c) || coverJob?.isActive == true) return
        coverJob = scope.launch {
            while (core === c) {
                withFrameNanos { }
                frameTick.intValue++
                if (!advanceCover(c)) break
            }
            // the core's picture shows alone: the puck goes to the core with the camera, and the status says so
            publishCamera(force = true)
            noteFrame()
            noteStatus()
        }
    }

    /** One step of the start's paper; true while it still covers the core. */
    private fun advanceCover(c: CoreMap): Boolean {
        if (c.cover.started) return false
        val coreReady = startRevision >= 0 && c.complete && c.completeRevision >= startRevision
        val before = c.cover.canvas
        val due = c.cover.update(SystemClock.uptimeMillis(), coreReady)
        if (c.cover.canvas != before) canvasTick.intValue++
        if (c.cover.started) Log.i(MapEngine.TAG, "vk cover gone: the core draws ${drawnPack()?.id ?: "its own cover"} alone")
        noteFrame()
        noteChrome()
        return due
    }

    /** The core's first complete picture since the app began: `vk first frame <ms> first|warm` (Appendix C), once. */
    private fun logFirstFrame() {
        val c = core ?: return
        val at = c.firstCompleteAt
        if (at < 0 || !MapEngine.firstFrameLogged.compareAndSet(false, true)) return
        val from = MapEngine.startedAt.takeIf { it > 0 } ?: android.os.Process.getStartUptimeMillis()
        Log.i(MapEngine.TAG, "vk first frame ${at - from} ${if (MapEngine.firstLaunch) "first" else "warm"}")
    }

    /**
     * The labels: the core draws them once its picture shows with nothing over it (fixed where they are on the map,
     * growing and shrinking with it, ENGINE.md design point 8), the canvas over the start's paper until then; handed
     * over off the main thread when that, the labels or their face change.
     */
    private fun sendLabels(c: CoreMap) {
        // what the world's names are drawn with (ENGINE.md step 5, "Names"): the core asks for them as its frames show them
        val nameFace = labelTypeface ?: MapEngine.nameTypeface
        val globeFace = MapEngine.globeTypeface
        val floor = READABLE_SP * fontScale
        val look = c.names.look
        if (look == null || look.density != density || look.face !== nameFace || look.globeFace !== globeFace || look.floorDp != floor) {
            c.names.look = app.atlas.mapview.vk.CoreNames.Look(density, nameFace, globeFace, floor)
        }
        val draws = c.cover.started
        val labels = if (draws) overlays.labels else emptyList()
        val face = labelTypeface
        val sent = sentLabels
        if (sent != null && sent.labels === labels && sent.face === face && sent.density == density && sent.draws == draws) return
        sentLabels = SentLabels(labels, face, density, draws)
        if (coreLabels != draws) {
            coreLabels = draws
            canvasTick.intValue++
        }
        val revision = ++labelsRevision
        val painter = spritePainter?.takeIf { sent?.density == density } ?: LabelPainter(density).also { spritePainter = it }
        val least = READABLE_SP * fontScale / painter.smallestSize
        scope.launch(Dispatchers.Default) {
            val why = c.labels(labels, face, painter, least, revision)
            if (why.isNotEmpty()) Log.w(MapEngine.TAG, "vk labels: $why")
        }
    }

    /**
     * The markers: the core draws them once its picture shows with nothing over it and nothing on the ground the canvas
     * draws over its picture (a route, an area: the canvas draws those, and the markers over them, until the core draws
     * them), so they move with the map in the same frame; the canvas until then. Handed over off the main thread when
     * that, the markers, the density or the night (a closed place's look, [Marker.faded]) change; the canvas lets go of
     * them once the core has them, so none are missing between.
     */
    private fun sendMarkers(c: CoreMap) {
        val draws = c.cover.started && (!overlays.onGround || coreRoutes)
        val markers = if (draws) overlays.markers else emptyList()
        val night = c.night
        val sent = sentMarkers
        if (sent != null && sent.draws == draws && sent.density == density && sent.night == night) {
            if (sent.markers === markers) return
            // an equal list made anew (a recomposition): kept, so the next frames compare it by identity
            if (sent.markers == markers) {
                sentMarkers = SentMarkers(markers, density, draws, night)
                return
            }
        }
        sentMarkers = SentMarkers(markers, density, draws, night)
        if (!draws && coreMarkers) {
            coreMarkers = false
            canvasTick.intValue++
        }
        val revision = ++markersRevision
        val painter = markerPainter?.takeIf { it.density == density } ?: MarkerPainter(density).also {
            it.sans = markerSans
            markerPainter = it
        }
        scope.launch(Dispatchers.Default) {
            val why = c.markers(markers, painter, revision, night)
            if (why.isNotEmpty()) Log.w(MapEngine.TAG, "vk markers: $why")
            main.post {
                if (core !== c || revision != markersRevision) return@post
                coreMarkers = draws && why.isEmpty()
                frameTick.intValue++
                canvasTick.intValue++
            }
        }
    }

    /**
     * The routes and areas: the core draws them once its picture shows with nothing over it, on the street in the same
     * frame as the map, under its labels, markers and puck; the canvas until then. Handed over off the main thread when
     * that, the routes, the areas or the density change; the canvas lets go of them once the core has them, and the
     * markers and the puck follow them into the core.
     */
    private fun sendRoutes(c: CoreMap) {
        val draws = c.cover.started
        val ov = overlays
        val routes = if (draws) ov.routes else emptyList()
        val areas = if (draws) ov.areas else emptyList()
        val sent = sentRoutes
        if (sent != null && sent.draws == draws && sent.density == density) {
            if (sent.routes === routes && sent.areas === areas) return
            // equal lists made anew (a recomposition): kept, so the next frames compare them by identity
            if (sent.routes == routes && sent.areas == areas) {
                sentRoutes = SentRoutes(routes, areas, density, draws)
                return
            }
        }
        sentRoutes = SentRoutes(routes, areas, density, draws)
        if (!draws && coreRoutes) {
            coreRoutes = false
            canvasTick.intValue++
            publishCamera()
        }
        val revision = ++routesRevision
        val painter = routePainter?.takeIf { it.density == density } ?: OverlayPainter(density).also { routePainter = it }
        scope.launch(Dispatchers.Default) {
            val why = c.routes(MapOverlays(routes = routes, areas = areas), painter, revision)
            if (why.isNotEmpty()) Log.w(MapEngine.TAG, "vk routes: $why")
            main.post {
                if (core !== c || revision != routesRevision) return@post
                val now = draws && why.isEmpty()
                if (now != coreRoutes) {
                    coreRoutes = now
                    canvasTick.intValue++
                    // the puck follows the routes into the core, or back
                    publishCamera()
                }
                frameTick.intValue++
            }
        }
    }

    /**
     * The building the app asks about (FillIn's question), handed over off the main thread when it changed: the core
     * draws it plain in its flat colour, hatched while the question is open.
     */
    private fun sendAsked(c: CoreMap) {
        val asked = overlays.asked
        if (askedSent && asked == sentAsked) return
        sentAsked = asked
        askedSent = true
        val revision = ++askedRevision
        scope.launch(Dispatchers.Default) { runCatching { c.ask(asked, revision) } }
    }

    /** Where the moment lights the map, and the clock it goes by there. */
    private data class LitPlace(val lat: Double, val lon: Double, val zone: java.time.ZoneId)

    /**
     * The place the moment lights now, and its clock (the engine lead's and the reviewer's "the moment's place", 11
     * November): the sun stands where it stands over the place the map draws, not over the bundled packs'.
     * - Where a render pack holds the camera's target, its own origin, as its render is lit (the canal ring's is the
     *   Westerkerk, so its pictures stay the renders').
     * - Elsewhere the target itself, to a tenth of a degree: at most 8 km off, a fifteenth of a degree of the sun, where
     *   its shadows step by half a degree, so the block is made again only as the camera moves on, a window's or none.
     * The clock is the place's own zone: the forecast's where one is to hand (it holds for any place), else the zone of
     * the pack that holds the target (a window's is its country's, a guess by a table), else, off every pack, the
     * sun's own hour by the longitude. Never the nearest pack's, which may be a country away.
     */
    private fun litPlace(c: CoreMap): LitPlace? {
        val v = viewport()
        if (v.isEmpty) return null
        val lv = liveView(state.camera, v)
        val held = c.packs.choose(lv.lat, lv.lon)
        val render = held?.takeIf { it.kind == app.atlas.mapcore.MapCore.PackRef.RENDER }
        val lat = render?.frame?.lat0 ?: (Math.round(lv.lat * PLACE_STEPS) / PLACE_STEPS).coerceIn(-90.0, 90.0)
        val lon = render?.frame?.lon0 ?: (Math.round(lv.lon * PLACE_STEPS) / PLACE_STEPS).coerceIn(-180.0, 180.0)
        val zone = MapEngine.weather?.zone(LatLon(lv.lat, lv.lon)) ?: held?.zone
            ?: java.time.ZoneOffset.ofHours(Math.round(lv.lon / 15.0).toInt().coerceIn(-12, 14))
        return LitPlace(lat, lon, zone)
    }

    /**
     * The moment the core lights the map by, handed over off the main thread when it changed, or the place it lights
     * or its clock did ([litPlace]), or the user's Tone or palette (MapEngine.tone, MapEngine.palette). A moment's own
     * step is drawn with the next frame (Bridge's moment() asks Kotlin's camera, which at rest has not moved); a new
     * Tone or palette is the user's, so once the core has the theme the camera is published again and the map draws it
     * at once, at rest too.
     */
    private fun sendMoment(c: CoreMap, moment: Moment) {
        val tone = MapEngine.tone
        val palette = MapEngine.palette
        val place = litPlace(c) ?: return
        if (moment == sentMoment && tone == sentTone && palette == sentPalette && place == sentPlace) return
        val retoned = tone != sentTone || palette != sentPalette
        sentMoment = moment
        sentTone = tone
        sentPalette = palette
        sentPlace = place
        val daylight = MapEngine.DAYLIGHT
        scope.launch(Dispatchers.Default) {
            c.moment(moment, place.zone, daylight, place.lat, place.lon)
            if (retoned) main.post { if (core === c && !closed) publishCamera(force = true) }
        }
    }

    /** The debug panel's core line, refreshed every second while the map moves (it asks the core: off the main thread). */
    private fun startPanel() {
        if (panelJob?.isActive == true) return
        val c = core ?: return
        panelJob = scope.launch {
            while (core === c && debug && SystemClock.uptimeMillis() - movedAt < PANEL_LINGER_MS) {
                panelLine = withContext(Dispatchers.Default) { runCatching { c.panelLine() }.getOrDefault("") }
                frameTick.intValue++
                delay(PANEL_MS)
            }
            frameTick.intValue++
        }
    }

    override fun onRemembered() {}

    override fun onForgotten() = close()

    /** Composition was dropped before it applied: the thread started for it must not linger. */
    override fun onAbandoned() = close()

    /**
     * True when nothing is loading, fading, animating or on its way: the picture is final. Where the core has no tiles at
     * the camera's place, once nothing comes for it (no window under way, the way to the map said).
     */
    val settled: Boolean
        get() {
            if (animation?.isActive == true || warming) return false
            val c = core ?: return !coreExpected
            if (coverJob?.isActive == true) return false
            return when (place) {
                MapPlace.DRAWN -> c.complete && c.cover.started
                else -> !state.loading
            }
        }

    /** The engine's view of [cam] in [v]: the screen in dp (whole, as the engine's frame), the principal point in those dp. */
    fun liveView(cam: CameraPosition, v: Viewport): LiveView {
        val wDp = (v.width / v.density).roundToInt().coerceAtLeast(1)
        val hDp = (v.height / v.density).roundToInt().coerceAtLeast(1)
        val principalY = v.height / 2.0 + v.offsetY
        return LiveView(cam.target.lat, cam.target.lon, cam.zoom, cam.bearing, cam.pitch, wDp, hDp, principalY * hDp / v.height)
    }

    /** The moment to draw for [style] at [time]: the live one (the clock and the forecast) unless the style fixes one. */
    private fun momentOf(s: MapStyle, time: Instant?, at: LatLon): Moment = when (s.theme) {
        Theme.DAY -> Moment.Named("sunny")
        Theme.NIGHT -> Moment.Named("night")
        Theme.AUTO, Theme.CHART -> Moment.Live(quantise(time ?: Instant.now()), MapEngine.weather?.hours(at))
    }

    /**
     * Hands the core the current camera and moment, and looks at what it has at the camera's place ([MapPlace]); reports the
     * camera when it moved. With no fixed [MapState.time] the light follows the clock, in steps of [MOMENT_STEP_S].
     */
    fun update() {
        val v = viewport()
        if (v.isEmpty) return
        val cam = state.camera
        val s = style
        val view = liveView(cam, v)
        val moment = momentOf(s, state.time, cam.target)
        val c = core
        if (c != null && c.packs.loaded) {
            placedCountries = MapEngine.countries
            val under = MapEngine.worldUnder(view.lat, view.lon)
            val at = MapPlace.of(c.packs, MapEngine::countryAt, view.lat, view.lon, view.distance, under)
            if (at != place) {
                place = at
                canvasTick.intValue++
                noteStatus()
            }
            // The windows the core wants (ENGINE.md step 3): the nearest taken from its country's tiles or built in the
            // background, one at a time, and the core handed it when it is; asked twice a second, and after a step
            // that wrote a pack at once ([repack]), after one that worked on a tile and wrote none at its half second's
            // end ([windowsLater]), until a step finds nothing to do.
            val now = SystemClock.uptimeMillis()
            if (now - windowCheckedAt >= WINDOWS_CHECK_MS) {
                windowCheckedAt = now
                MapEngine.windows()?.let { w ->
                    scope.launch(Dispatchers.IO) {
                        val before = w.worked
                        if (runCatching { w.step(c.core) }.getOrDefault(false)) {
                            main.post(repack)
                        } else if (w.worked != before) {
                            main.post { if (!closed) windowsLater() }
                        }
                    }
                }
                // the world's packs the view wants (ENGINE.md step 6): a block's head, then its tiles' chunks by ranges
                MapEngine.worldReads?.let { r ->
                    scope.launch(Dispatchers.IO) {
                        if (runCatching { r.step(c.core) }.getOrDefault(false)) main.post(repack)
                    }
                }
                // what the windows do now is what the map is, where the place's window has not come yet
                if (place == MapPlace.WINDOWS) noteStatus()
            }
        }
        if (c != null) {
            publishCamera()
            // the start's paper waits for a complete picture of the first camera published once the packs are known
            if (startRevision < 0 && c.packs.loaded) startRevision = cameraRevision
            sendMoment(c, moment)
            stepCover()
        }
        val bounds = CameraOps.visibleBounds(cam, v)
        if (bounds != state.visibleBounds) state.visibleBounds = bounds
        if (cam != lastReported) {
            val byUser = cam == lastWritten && lastWrittenByUser
            lastReported = cam
            onCameraMove(cam, byUser)
        }
        frameTick.intValue++
    }

    /**
     * Loads the symbols and brand marks [markers] draw with, off the main thread (the sets are files read on first
     * use), then redraws: drawing only uses what is loaded.
     */
    suspend fun warm(markers: List<Marker>) {
        warmNames.clear()
        warmLogos.clear()
        warmNames += OverlayPainter.WALK
        MarkerPainter.symbolsOf(markers, warmNames, warmLogos)
        if (Icons.ready(warmNames, warmLogos)) return
        val names = ArrayList(warmNames)
        val logos = ArrayList(warmLogos)
        warming = true
        try {
            withContext(Dispatchers.Default) { Icons.warm(names, logos) }
            frameTick.intValue++
        } finally {
            warming = false
        }
    }

    private fun painter(): OverlayPainter = painter ?: OverlayPainter(density).also { p ->
        p.markers.sans = markerSans
        painter = p
    }

    /** The markers' text in the boards' Geist, from the app's fonts. */
    private val markerSans: (Int) -> Typeface = { w -> (fonts ?: SvgFonts.system()).typeface("Geist", w, false) }

    /** The tilt readout's painter, made at its first draw (and again when the density changes); its figures in Geist too. */
    private fun tiltReadoutPainter(): TiltReadoutPainter =
        readoutPainter?.takeIf { it.density == density } ?: TiltReadoutPainter(density, markerSans).also { readoutPainter = it }

    /**
     * Whether the canvas draws nothing over the core's picture now (ENGINE.md gain 12, [CanvasWork.idle]). It reads
     * [debug], [tape], [readout] and [overlays], whose changes draw the canvas again; what else it reads says it changed
     * through [canvasTick].
     */
    fun canvasIdle(): Boolean {
        val c = core ?: return false
        return CanvasWork.idle(
            debug = debug, tape = tape != null, readout = readout != null, honest = honestReason != null,
            cover = c.cover.canvas, overlays = overlays, coreRoutes = coreRoutes, coreLabels = coreLabels,
            coreMarkers = coreMarkers, coreLocation = coreLocation, waits = place != MapPlace.DRAWN,
        )
    }

    /**
     * What a draw hands the core even where the canvas draws nothing: the routes and areas, the labels and the markers
     * when they changed.
     */
    fun handOver() {
        val c = core ?: return
        sendRoutes(c)
        sendLabels(c)
        sendMarkers(c)
        sendAsked(c)
    }

    /**
     * Draws the start's paper over the core while it covers it ([CoreMap.cover]: the paper alone, until the core's first
     * picture is complete), and the overlays the core does not draw over all of it.
     */
    fun draw(canvas: Canvas) {
        val v = viewport()
        val c = core
        val cover = c?.cover?.canvas ?: 1f
        val night = c?.night == true
        if (cover > 0f) {
            val layered = cover < 1f && !v.isEmpty
            if (layered) canvas.saveLayerAlpha(0f, 0f, v.width.toFloat(), v.height.toFloat(), (cover * 255f).roundToInt())
            canvas.drawColor(if (night) NIGHT_EARTH else DAY_EARTH)
            if (layered) canvas.restore()
        }
        if (!v.isEmpty) {
            val g = GroundView(CameraOps.camera(state.camera, v))
            val pt = painter()
            val ov = overlays
            pt.layoutMarkers(g, ov.markers)
            c?.let(::sendRoutes)
            if (!coreRoutes) pt.drawGround(canvas, g, ov)
            labelTypeface?.let { pt.labels.typeface = it }
            c?.let(::sendLabels)
            c?.let(::sendMarkers)
            c?.let(::sendAsked)
            if (!coreLabels) pt.drawLabels(canvas, g, ov)
            pt.markers.night = night
            if (!coreMarkers) pt.drawMarkers(canvas)
            if (!coreLocation) ov.location?.let { pt.drawLocation(canvas, g, it) }
            tape?.let { pt.drawTape(canvas, g, it, units, Locale.getDefault()) }
            readout?.let { tiltReadoutPainter().draw(canvas, it, state.camera.pitch, CameraOps.maxPitch(state.camera.zoom), night) }
            drawStatus(canvas)
            // the core's line while the map moves and a few seconds after; a finished map is the board's, with nothing over it
            if (debug && c != null && panelLine.isNotEmpty() && SystemClock.uptimeMillis() - movedAt < PANEL_LINGER_MS) drawDebug(canvas, pt)
        }
    }

    /**
     * The map's line where the core has no tiles at the camera's place ([placeLine]), at the top of the map; none where
     * it draws the place (its tiles are on the phone: no "Loading").
     */
    private fun drawStatus(canvas: Canvas) {
        val text = placeLine() ?: return
        statusText.textSize = 13f * density
        val w = statusText.measureText(text) + 24f * density
        val h = 30f * density
        val cx = size.first / 2f
        val top = insets.top + 12f * density
        debugRect.set(cx - w / 2, top, cx + w / 2, top + h)
        canvas.drawRoundRect(debugRect, h / 2, h / 2, statusPanel)
        canvas.drawText(text, cx, top + h / 2 + statusText.textSize * 0.35f, statusText)
    }

    /**
     * What the map says where the core has no tiles at the camera's place ([place]): within the windows' reach what they
     * do ([windowsText]), or that no map data comes here (no windows in this process); beyond it the way to the map;
     * beyond the levels' reach the world on its way, or to come with the first connection. Null where the core draws the
     * place, the windows rest, or no core draws yet (the paper alone while it starts).
     */
    private fun placeLine(): String? {
        if (core == null || honestReason != null) return null
        return when (place) {
            MapPlace.DRAWN -> null
            MapPlace.WINDOWS -> windowsText() ?: if (MapEngine.windows() == null) NO_DATA else null
            MapPlace.NEAR -> ZOOM_IN
            MapPlace.WORLD -> if (MapEngine.online()) "$WORLD…" else WORLD_OFFLINE
        }
    }

    /**
     * Where the core has no tiles yet, what its windows are doing (ENGINE.md step 3, Windows.line), which says why the
     * place is empty. Null where the windows rest.
     */
    private fun windowsText(): String? {
        if (core == null) return null
        if (!MapEngine.online()) return Windows.line(online = false, status = null, remainingMs = 0L, country = null)
        val w = MapEngine.windowsNow ?: return null
        val s = w.status
        // while the windows work or wait, the canvas draws again now and then: the status stays true, and the windows'
        // next step (asked from the draw) comes though the map lies still
        if (s != Windows.Status.Idle && !windowsTickPosted) {
            windowsTickPosted = true
            main.postDelayed({
                windowsTickPosted = false
                canvasTick.intValue++
            }, if (s is Windows.Status.Waiting) WINDOWS_WAIT_TICK_MS else WINDOWS_WORK_TICK_MS)
        }
        val target = state.camera.target
        return Windows.line(
            true, s, (s as? Windows.Status.Waiting)?.let(w::remaining) ?: 0L, MapEngine.countryAt(target.lat, target.lon)?.name,
            overpassOff = s is Windows.Status.Waiting && MapEngine.overpassOff(),
        )
    }

    private var windowsTickPosted = false

    /** The debug panel: the core's line (§8), the pack it draws, the markers and the camera. */
    private fun drawDebug(canvas: Canvas, p: OverlayPainter) {
        val c = state.camera
        val lines = ArrayList<String>(8)
        lines += panelLine.lines()
        lines += "core %s · %s".format(Locale.ROOT, drawnPack()?.id ?: "none here", place.name.lowercase(Locale.ROOT))
        lines += "markers %d/%d · vertices %d".format(Locale.ROOT, p.markerCount, overlays.markers.size, p.routeVertices)
        lines += "z %.2f · bearing %.0f° · pitch %.0f°".format(Locale.ROOT, c.zoom, c.bearing, c.pitch)
        debugText.textSize = 11f * density
        val lh = 14f * density
        val pad = 8f * density
        var w = 0f
        for (l in lines) w = max(w, debugText.measureText(l))
        val left = insets.left + pad
        // Below the boards' top-left pills (the home's weather, moments and needs reach 190 px down).
        val top = insets.top + 200f * density
        debugRect.set(left, top, left + w + 2 * pad, top + lines.size * lh + 2 * pad - 3f * density)
        canvas.drawRoundRect(debugRect, 8f * density, 8f * density, debugPanel)
        for ((i, l) in lines.withIndex()) canvas.drawText(l, left + pad, top + pad + (i + 1) * lh - 4f * density, debugText)
    }

    // -------------------------------------------------------------------------------------------------------------
    // Camera writes and animations.

    /**
     * Moves the camera to [p]. Under the core, the camera is published for its render thread at once: an animation's
     * step passes the camera a frame on ([next], at [nextNs]; [nowNs] is the step's frame time), so the core draws each
     * vsync's own camera.
     */
    private fun write(p: CameraPosition, byUser: Boolean, next: CameraPosition? = null, nowNs: Long = 0L, nextNs: Long = 0L) {
        val c = CameraOps.clamp(p)
        lastWritten = c
        lastWrittenByUser = byUser
        state.camera = c
        if (core != null) publishCamera(next?.let(CameraOps::clamp), if (nowNs > 0L) nowNs else System.nanoTime(), nextNs)
    }

    private fun stopAnimation() {
        animation?.cancel()
        animation = null
    }

    private suspend fun await(job: Job) {
        try {
            job.join()
        } catch (e: CancellationException) {
            job.cancel()
            throw e
        }
    }

    /**
     * Runs [frame] for t from 0 to 1 over [durationMs], one camera per screen frame; under the core each step also
     * passes the animation a frame on, and its end lets the camera rest.
     */
    private fun animate(durationMs: Long, byUser: Boolean, frame: (Double) -> CameraPosition): Job {
        stopAnimation()
        val job = scope.launch {
            var start = -1L
            var last = -1L
            var period = FRAME_NS
            while (true) {
                val now = withFrameNanos { it }
                if (start < 0) start = now
                if (last >= 0) period = (now - last).coerceIn(MIN_FRAME_NS, MAX_FRAME_NS)
                last = now
                val t = if (durationMs <= 0) 1.0 else ((now - start) / 1e6 / durationMs).coerceIn(0.0, 1.0)
                val next = if (core != null && t < 1.0) frame(((now + period - start) / 1e6 / durationMs).coerceIn(0.0, 1.0)) else null
                write(frame(t), byUser, next, now, now + period)
                if (t >= 1.0) break
            }
        }
        job.invokeOnCompletion { main.post(settle) }
        animation = job
        return job
    }

    /** Flies to [target] over [durationMs]: zooming out and in for long jumps. */
    fun flyTo(target: CameraPosition, durationMs: Int, byUser: Boolean): Job {
        val v = viewport()
        if (v.isEmpty || durationMs <= 0) {
            stopAnimation()
            write(target, byUser)
            return Job().apply { complete() }
        }
        val fly = FlyTo(state.camera, CameraOps.clamp(target), max(v.width, v.height) / density.toDouble())
        return animate(durationMs.toLong(), byUser) { fly.at(it) }
    }

    /** Fits [box] inside the content padding, or inside [within] (screen px) where given, plus [paddingPx]. */
    fun fit(box: BBox, paddingPx: Int, within: androidx.compose.ui.geometry.Rect? = null): Job {
        val v = viewport()
        if (v.isEmpty) {
            write(state.camera.copy(target = box.center), false)
            return Job().apply { complete() }
        }
        val i = within?.let { Insets(it.left, it.top, v.width - it.right, v.height - it.bottom) } ?: insets
        val pad = floatArrayOf(i.left + paddingPx, i.top + paddingPx, i.right + paddingPx, i.bottom + paddingPx)
        return flyTo(CameraOps.fit(state.camera, v, box, pad), FIT_MS, byUser = false)
    }

    private fun anchorX(x: Float) = if (state.follow != Follow.NONE) size.first / 2f else x
    private fun anchorY(y: Float) = if (state.follow != Follow.NONE) CameraOps.camera(state.camera, viewport()).principalY.toFloat() else y

    private fun zoomStep(x: Float, y: Float, zoomIn: Boolean) {
        val v = viewport()
        val start = state.camera
        val ax = anchorX(x)
        val ay = anchorY(y)
        val dz = if (zoomIn) 1.0 else -1.0
        animate(ZOOM_STEP_MS, byUser = true) { t -> CameraOps.zoomAround(start, v, ax, ay, dz * FlyTo.ease(t)) }
    }

    private fun fling(vx: Float, vy: Float) {
        stopAnimation()
        val x = lastPanX
        val y = lastPanY
        val speed = hypot(vx, vy)
        // Where it lands (ENGINE.md gain 26): the decay's whole path is known now, so the core builds the tiles there
        // while the map is on its way.
        val c = core
        if (c != null && speed > FLING_STOP_DP * density) {
            val tEnd = min(FLING_MAX_S, ln(speed / (FLING_STOP_DP * density)) / FLING_DECAY)
            val fEnd = (1 - exp(-FLING_DECAY * tEnd)) / FLING_DECAY
            val v = viewport()
            if (!v.isEmpty) {
                val end = liveView(CameraOps.pan(state.camera, v, x, y, (x + vx * fEnd).toFloat(), (y + vy * fEnd).toFloat()), v)
                c.core.landing(end.lat, end.lon, end.bearing, end.pitch, end.distance)
            }
        }
        animation = scope.launch {
            var start = -1L
            var last = -1L
            var period = FRAME_NS
            var doneX = 0.0
            var doneY = 0.0
            while (true) {
                val now = withFrameNanos { it }
                if (start < 0) { start = now; last = now; continue }
                period = (now - last).coerceIn(MIN_FRAME_NS, MAX_FRAME_NS)
                last = now
                val t = (now - start) / 1e9
                val f = (1 - exp(-FLING_DECAY * t)) / FLING_DECAY
                val dx = vx * f
                val dy = vy * f
                val v = viewport()
                val cam = CameraOps.pan(state.camera, v, x, y, (x + dx - doneX).toFloat(), (y + dy - doneY).toFloat())
                // Under the core, the fling a frame on as well (the core draws each vsync's own camera).
                val next = if (core == null) null else {
                    val fn = (1 - exp(-FLING_DECAY * (t + period / 1e9))) / FLING_DECAY
                    CameraOps.pan(cam, v, x, y, (x + vx * fn - dx).toFloat(), (y + vy * fn - dy).toFloat())
                }
                write(cam, true, next, now, now + period)
                doneX = dx
                doneY = dy
                if (speed * exp(-FLING_DECAY * t) < FLING_STOP_DP * density || t > FLING_MAX_S) break
            }
        }.also {
            it.invokeOnCompletion {
                c?.core?.noLanding()
                main.post(settle)
            }
        }
    }

    private fun snapNorth() {
        val b = state.camera.bearing
        val delta = CameraOps.turn(b, 0.0)
        if (delta == 0.0 || abs(delta) > SNAP_DEGREES) return
        val v = viewport()
        val start = state.camera
        val cx = v.width / 2f
        val cy = CameraOps.camera(start, v).principalY.toFloat()
        animate(SNAP_MS, byUser = true) { t -> CameraOps.rotateAround(start, v, cx, cy, -delta * FlyTo.ease(t)) }
    }

    /**
     * Follows the phone as [MapState.follow] says, called when the follow mode or the location changes: POSITION keeps
     * it centred, HEADING also turns the map with the compass, COURSE turns it with the way the phone moves (the
     * compass until it does) and tilts for navigation, the phone low on the screen to show the road ahead.
     */
    fun followUpdate() {
        val mode = state.follow
        if (mode != Follow.COURSE) courseTilted = false
        setLookAhead(mode == Follow.COURSE)
        val loc = overlays.location ?: return
        // The course is the way the phone moves: from fix to fix once it has gone further than the fixes wander (their
        // accuracy), and not while it stands still, when the wander alone would spin the map.
        val last = lastFix
        val still = loc.speed?.let { it < COURSE_MIN_SPEED } ?: false
        val step = loc.accuracyM.coerceIn(COURSE_MIN_STEP_M, COURSE_MAX_STEP_M)
        if (last == null) {
            lastFix = loc.at
        } else if (!still && Geo.distance(last, loc.at) >= step) {
            course = Geo.bearing(last, loc.at)
            lastFix = loc.at
        }
        if (mode == Follow.NONE || touching) return
        val cur = state.camera
        val target = when (mode) {
            Follow.POSITION -> cur.copy(target = loc.at)
            Follow.HEADING -> cur.copy(target = loc.at, bearing = loc.heading ?: cur.bearing)
            // navigation's tilt, unless the user set their own with their fingers while following
            Follow.COURSE -> cur.copy(
                target = loc.at, bearing = course ?: loc.heading ?: cur.bearing,
                pitch = if (courseTilted) cur.pitch else max(cur.pitch, COURSE_PITCH), zoom = max(cur.zoom, COURSE_MIN_ZOOM),
            )
            Follow.NONE -> return
        }
        val v = viewport()
        val far = v.isEmpty || Geo.distance(cur.target, loc.at) > 3 * CameraOps.metresPerDp(cur) * max(v.width, v.height) / density
        if (far) {
            flyTo(target, FOLLOW_FLY_MS, byUser = false)
        } else {
            val start = cur
            animate(FOLLOW_GLIDE_MS, byUser = false) { t -> CameraOps.lerp(start, target, 1 - (1 - t) * (1 - t)) }
        }
    }

    private fun setLookAhead(on: Boolean) {
        val goal = if (on) 1f else 0f
        if (lookAhead == goal && lookAheadJob?.isActive != true) return
        lookAheadJob?.cancel()
        val from = lookAhead
        lookAheadJob = scope.launch {
            var start = -1L
            while (true) {
                val now = withFrameNanos { it }
                if (start < 0) start = now
                val t = ((now - start) / 1e6 / LOOK_AHEAD_MS).coerceIn(0.0, 1.0)
                lookAhead = from + (goal - from) * FlyTo.ease(t).toFloat()
                if (t >= 1.0) break
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Gestures.

    override fun onGestureStart() {
        touching = true
        stopAnimation()
    }

    override fun onPan(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (state.follow != Follow.NONE) state.follow = Follow.NONE
        write(CameraOps.pan(state.camera, viewport(), x0, y0, x1, y1), true)
        lastPanX = x1
        lastPanY = y1
    }

    override fun onTransform(x0: Float, y0: Float, x1: Float, y1: Float, scale: Float, rotationDeg: Float) {
        val v = viewport()
        val p = state.camera
        if (state.follow != Follow.NONE) {
            // Following: zoom and turn around the phone, which stays put.
            if (rotationDeg != 0f && state.follow != Follow.POSITION) state.follow = Follow.POSITION
            val cx = anchorX(x1)
            val cy = anchorY(y1)
            write(CameraOps.transform(p, v, cx, cy, cx, cy, scale.toDouble(), rotationDeg.toDouble()), true)
        } else {
            write(CameraOps.transform(p, v, x0, y0, x1, y1, scale.toDouble(), rotationDeg.toDouble()), true)
        }
        lastPanX = x1
        lastPanY = y1
    }

    override fun onTiltStart(fingers: Int) {
        // the tape gives way to the tilt at once
        tapeJob?.cancel()
        tape = null
        tiltFrom = state.camera.pitch
        if (state.follow == Follow.COURSE) courseTilted = true
        readoutJob?.cancel()
        if (readout == null) readoutPainter?.reset()
        showReadout(Paint.Align.CENTER)
    }

    /**
     * The fingers' move tilts by [TILT_PER_DP] a dp, straight and at once, nothing smoothed; reaching a stop (flat, or the
     * zoom's steepest) ticks softly under them.
     */
    override fun onTilt(dy: Float) {
        val before = state.camera.pitch
        write(CameraOps.tilt(state.camera, -dy / density * TILT_PER_DP), true)
        if (TiltReadout.reached(before, state.camera.pitch, CameraOps.maxPitch(state.camera.zoom))) haptic(TiltReadout.STOP_FEEL)
        showReadout(readout?.align ?: Paint.Align.CENTER)
    }

    override fun onTiltEnd(cancelled: Boolean) {
        val from = tiltFrom ?: return
        tiltFrom = null
        if (cancelled) {
            // The system took the touches (an OEM's three-finger screenshot): the tilt was not meant for the map.
            write(state.camera.copy(pitch = from), true)
        } else if (state.camera.pitch >= MIN_CHOSEN_TILT) {
            state.chosenTilt = state.camera.pitch
        }
        hideReadout()
    }

    /** The readout where the tilting fingers are now ([TiltReadout.place]), standing as [prefer] says if it can. */
    private fun showReadout(prefer: Paint.Align) {
        val (w, h) = size
        val n = gestures.fingers(tiltFingers)
        if (n == 0) return
        val at = FloatArray(2)
        val align = TiltReadout.place(tiltFingers, n, w, h, density, insets, prefer, at)
        readout = TiltReadoutShown(at[0], at[1], align)
    }

    /** Once the fingers lift, the readout stays [TiltReadout.LINGER_MS] where it was, then fades. */
    private fun hideReadout() {
        val r = readout ?: return
        readoutJob?.cancel()
        readoutJob = scope.launch {
            delay(TiltReadout.LINGER_MS)
            var start = -1L
            while (true) {
                val now = withFrameNanos { it }
                if (start < 0) start = now
                val k = ((now - start) / 1e6 / TiltReadout.FADE_MS).coerceIn(0.0, 1.0)
                readout = TiltReadoutShown(r.x, r.y, r.align, (1 - k).toFloat())
                if (k >= 1.0) break
            }
            readout = null
        }
    }

    override fun onZoomStep(x: Float, y: Float, zoomIn: Boolean) = zoomStep(x, y, zoomIn)

    override fun onDragZoom(x: Float, y: Float, dy: Float) {
        write(CameraOps.zoomAround(state.camera, viewport(), anchorX(x), anchorY(y), dy / (density * DRAG_ZOOM_DP)), true)
    }

    override fun onFling(vx: Float, vy: Float) {
        if (state.follow == Follow.NONE) fling(vx, vy)
    }

    override fun onTap(x: Float, y: Float) {
        if (tape != null) {
            tapeJob?.cancel()
            tape = null
            return
        }
        val v = viewport()
        if (v.isEmpty) return
        // the markers laid out where the camera is now: a canvas that draws nothing has not laid them out since it moved
        painter().layoutMarkers(GroundView(CameraOps.camera(state.camera, v)), overlays.markers)
        painter?.markerAt(x, y)?.let {
            onTap(MapHit.OnMarker(it, it.at))
            return
        }
        val at = CameraOps.groundAt(state.camera, v, x, y)
        val c = core
        val building = onTapBuilding
        val ground = onTapGround
        // the building under the finger as the core drew it, where it draws the map (as a long press finds it), else
        // the ground it shows there: a building stands before the ground it hides
        if (c != null && (building != null || ground != null) && c.cover.started) {
            scope.launch {
                val picked = withContext(Dispatchers.Default) { runCatching { c.pick(x, y) }.getOrNull() }
                if (core !== c || closed) return@launch
                if (picked != null) {
                    if (building != null) building(picked, Offset(x, y)) else at?.let { onTap(MapHit.Empty(it)) }
                    return@launch
                }
                val shown = if (ground != null) withContext(Dispatchers.Default) { runCatching { c.pickGround(x, y) }.getOrNull() } else null
                if (core !== c || closed) return@launch
                if (ground != null && shown != null) ground(shown, Offset(x, y)) else at?.let { onTap(MapHit.Empty(it)) }
            }
            return
        }
        at?.let { onTap(MapHit.Empty(it)) }
    }

    override fun onLongPress(x: Float, y: Float) {
        val v = viewport()
        if (v.isEmpty) return
        val at = CameraOps.groundAt(state.camera, v, x, y)
        val c = core
        val building = onLongPressBuilding
        // the building under the finger as the core drew it, where it draws the map: the first one the ray meets, which
        // may stand before the street point under the finger
        if (c != null && building != null && c.cover.started) {
            scope.launch {
                val picked = withContext(Dispatchers.Default) { runCatching { c.pick(x, y) }.getOrNull() }
                if (core !== c || closed) return@launch
                if (picked != null) building(picked) else at?.let { onLongPress(it) }
            }
            return
        }
        at?.let { onLongPress(it) }
    }

    override fun onMeasure(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (!overlays.measure) return
        tapeJob?.cancel()
        tape = Tape(x0, y0, x1, y1)
    }

    override fun onMeasureEnd() {
        val t = tape ?: return
        tapeJob?.cancel()
        tapeJob = scope.launch {
            // Long enough to read once the fingers are off it, then a short fade.
            delay(TAPE_LINGER_MS)
            var start = -1L
            while (true) {
                val now = withFrameNanos { it }
                if (start < 0) start = now
                val k = ((now - start) / 1e6 / TAPE_FADE_MS).coerceIn(0.0, 1.0)
                tape = Tape(t.x0, t.y0, t.x1, t.y1, (1 - k).toFloat())
                if (k >= 1.0) break
            }
            tape = null
        }
    }

    override fun onGestureEnd(rotated: Boolean) {
        touching = false
        if (rotated && animation?.isActive != true) snapNorth()
        // The fingers are off: unless an animation goes on, the camera rests (the core's last frame snaps).
        main.post(settle)
    }

    companion object {
        /**
         * The map's lines where the core has no tiles at the camera's place: no windows come here (none in this process),
         * the map nearer (the user, 30 October, of the reason only the debug overlay showed: "Nowhere its shows zoom
         * in?"), the world on its way (ENGINE.md step 5: "Getting the world map…") or not before the first connection.
         * [NO_CORE]: the status where no core runs in this process (the JVM's tests).
         */
        const val NO_DATA = "No map data here"
        const val ZOOM_IN = "Zoom in to see the map"
        const val WORLD = "Getting the world map"
        const val WORLD_OFFLINE = "The world map comes with the first connection"
        const val NO_CORE = "No map core in this process"

        const val DAY_EARTH = 0xFFE4E0D8.toInt()
        const val NIGHT_EARTH = 0xFF0B0F1A.toInt()

        /** The least text size a label draws at, sp: Android's text size scales it (the core's readable floor). */
        const val READABLE_SP = 12f

        /** The steps a degree of the place the moment lights is taken in, off the render packs ([litPlace]): tenths. */
        const val PLACE_STEPS = 10.0

        /** The live moment moves on in steps of this many seconds (each step is a new frame). */
        const val MOMENT_STEP_S = 300L

        /** [t] rounded down to a [MOMENT_STEP_S] step. */
        fun quantise(t: Instant): Instant = Instant.ofEpochSecond(t.epochSecond - Math.floorMod(t.epochSecond, MOMENT_STEP_S))

        /** A screen frame at 60 Hz, and the range a measured one is taken in (ns): an animation's next step's time. */
        const val FRAME_NS = 16_666_667L
        const val MIN_FRAME_NS = 4_000_000L
        const val MAX_FRAME_NS = 50_000_000L

        /** The core's panel line refreshes this often while the map moves, and lingers this long after (ms). */
        const val PANEL_MS = 1_000L
        const val PANEL_LINGER_MS = 5_000L

        /** How often the map asks the core which windows it wants, ms. */
        const val WINDOWS_CHECK_MS = 500L

        /** How often the canvas draws again while the windows work (the status, and their next step), and while they wait. */
        const val WINDOWS_WORK_TICK_MS = 1_000L
        const val WINDOWS_WAIT_TICK_MS = 30_000L

        const val FIT_MS = 650
        const val ZOOM_STEP_MS = 280L
        const val SNAP_MS = 220L
        const val SNAP_DEGREES = 7.0

        /**
         * Degrees of tilt a dp the fingers move, the same at every speed: flat to 70° in 437 dp, about half a phone's
         * map (923 dp tall on the Pixel, 915 on the boards), one deliberate drag; a degree about every millimetre (6 dp).
         * Half the 0.32 before it, which crossed the range in a quarter of the screen.
         */
        const val TILT_PER_DP = 0.16

        /** A tilt the fingers leave the map at, from this many degrees, is the one the 3D button brings back. */
        const val MIN_CHOSEN_TILT = 10.0
        const val DRAG_ZOOM_DP = 110.0
        const val FLING_DECAY = 3.2
        const val FLING_STOP_DP = 18f
        const val FLING_MAX_S = 2.5
        const val COURSE_PITCH = 58.0
        const val COURSE_MIN_ZOOM = 16.0
        const val COURSE_MIN_STEP_M = 4.0
        /** Fixes this inaccurate still make a course after this many metres (a tunnel's fixes would otherwise never). */
        const val COURSE_MAX_STEP_M = 25.0
        /** Below this speed (m/s), when the fix says it, the phone is standing still. */
        const val COURSE_MIN_SPEED = 0.6
        const val FOLLOW_GLIDE_MS = 900L
        const val FOLLOW_FLY_MS = 1200
        const val LOOK_AHEAD = 0.26f
        const val LOOK_AHEAD_MS = 600.0
        const val TAPE_LINGER_MS = 2500L
        const val TAPE_FADE_MS = 300.0
    }
}
