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
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.UnitSystem
import app.atlas.mapcore.MapCore
import app.atlas.mapcore.state.CameraWriter
import app.atlas.mapview.svg.SvgCamera
import app.atlas.mapview.svg.SvgFonts
import app.atlas.mapview.vk.CoreMap
import app.atlas.mapview.vk.MapCameraSource
import app.atlas.mapview.vk.PackList
import app.atlas.mapview.vk.Transition
import app.atlas.mapview.vk.Windows
import app.atlas.render.live.LiveCamera
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
 * The map view's engine room, one per [AtlasMap]: the camera to the 1:1 engine's exact frames on a thread of their own
 * ([EngineFrames]), the newest frame warped to the camera at once and cross-faded into the next, the overlays on top
 * (projected with the engine's camera, in the region's own frame), gestures to camera moves, and the camera's animations
 * (fly-to, zoom steps, flings, the snap to north, following the phone).
 *
 * With the core (plan-vector K11, [core]: MapEngine.core ready), the core draws the map under all of it, from the
 * camera this publishes for each vsync ([publishCamera]: during an animation, the animation a frame on too), and today's
 * map is drawn over it only where the transitional rule wants it ([CoreMap.transition]): its frames are asked for only
 * there, so at the core's places no scene of today's map is ever loaded.
 *
 * Everything here runs on the main thread except what the frame thread does.
 */
internal class MapController(val state: MapState, private val scope: CoroutineScope) : GestureListener, RememberObserver {
    private val main = Handler(Looper.getMainLooper())

    /** The engine's typefaces, set by the view from its assets. */
    var fonts: SvgFonts? = null

    /** Android's text size, a fraction of the default (Configuration.fontScale): the labels' readable floor follows it. */
    var fontScale = 1f

    /** The labels' face (the boards' Instrument Serif italic), given by the app. */
    var labelTypeface: android.graphics.Typeface? = null
    val frames: EngineFrames = EngineFrames(fonts = { fonts ?: SvgFonts.system() }, onChange = { main.post(published) })
    private val engineChanged: () -> Unit = {
        frames.invalidate()
        main.post(repack)
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

    /** The ground frame of the region drawn: gestures and overlays use the engine's own. */
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
    private var fadeJob: Job? = null

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
    private var lastRequest: EngineFrames.Request? = null

    /** The core's side of this map (K11): the core draws under today's map. Null: today's map alone, as before. */
    var core by mutableStateOf<CoreMap?>(null)
        private set

    /**
     * The app runs the core (MapEngine.core starting or ready): until this map's [core] is made, it waits for it with
     * the paper alone, today's map not asked (no scene of it loaded where the core will draw, no "Loading").
     */
    var coreExpected = false
        set(value) {
            if (value == field) return
            field = value
            // No core after all (none in this process): today's map is asked for this map's frame now.
            if (!value && core == null && !closed) {
                lastRequest = null
                update()
            }
        }

    /**
     * T2's honest state's reason while the phone cannot draw the map (MapEngine.core is off): no map is drawn and
     * today's map is not asked either (it is no fallback, T2). Null otherwise.
     */
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
        lastRequest = null
        if (state.status != MapStatus.NoData(why)) state.status = MapStatus.NoData(why)
        if (state.loading) state.loading = false
    }

    // The core's camera as last published, and the transitional rule's inputs.
    private var cameraRevision = 0L
    private var publishedCamera: CameraPosition? = null
    private var publishedViewport: Viewport? = null
    private var publishedRest = false
    private var publishedDraw = false
    private var publishedLocation: UserLocation? = null
    private var publishedOff = 0L

    /** Whether the core draws the location's puck now (the canvas does not): its camera carries the location. */
    private var coreLocation = false
    private var coreWanted: Transition.Path? = null
    private var wantRevision = 0L
    private var canvasFrameAtWant = 0L
    private var transitionJob: Job? = null
    private var sentMoment: Moment? = null

    /** The labels last handed to the core: the list (by identity), its face, the density, whether the core draws them. */
    private class SentLabels(val labels: List<MapLabel>, val face: android.graphics.Typeface?, val density: Float, val draws: Boolean)
    private var sentLabels: SentLabels? = null
    private var labelsRevision = 0L

    /** Whether the core draws the labels now (the canvas does not); the painter its sprites are drawn with. */
    private var coreLabels = false
    private var spritePainter: LabelPainter? = null

    /** The markers last handed to the core: the list, the density, whether the core draws them. */
    private class SentMarkers(val markers: List<Marker>, val density: Float, val draws: Boolean)
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
    private var idleJob: Job? = null

    @Volatile
    private var warming = false
    private val warmNames = HashSet<String>()
    private val warmLogos = HashSet<String>()

    // The frame shown and the one it fades from.
    private var shown: EngineFrame? = null
    private var previous: EngineFrame? = null
    private var fade = 1f
    private var lastDrawnNumber = 0L

    // Debug figures.
    private var drawMillis = 0.0
    private val debugText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; color = Color.WHITE }
    private val debugPanel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB0141619.toInt() }
    private val debugRect = RectF()
    private val statusText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val statusPanel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD0141619.toInt() }

    private val published = Runnable {
        // posted before the close: a closed map takes nothing more from its frames (the state may have a new map)
        if (closed) return@Runnable
        frameTick.intValue++
        val f = frames.latest
        if (f != null && f !== shown) {
            previous = shown
            shown = f
            startFade()
        }
        noteFrame()
        noteStatus()
        stepTransition()
    }

    /** The core said something new of its frames (a picture complete, or no longer): the map looks again. */
    private val coreChanged = Runnable {
        if (closed) return@Runnable
        // The core turned the map off for the session (the device lost three times): T2's honest state from now on.
        core?.offReason?.let { if (coreRefused == null) coreRefused = "the core turned the map off: $it" }
        frameTick.intValue++
        logFirstFrame()
        stepTransition()
        noteStatus()
    }

    /** The camera came to rest (an animation ended, the fingers lifted): the core's last frame snaps (D4). */
    private val settle = Runnable { if (!closed) publishCamera(force = true) }

    /**
     * The packs on the phone may have changed (a window taken from its country's tiles or built): the core is handed
     * them again off the main thread, and where they changed the map draws what it can draw now, the transitional rule
     * asked again. Nothing when the list is the same.
     */
    private val repack = Runnable {
        val c = core ?: return@Runnable
        if (closed) return@Runnable
        scope.launch {
            val said = withContext(Dispatchers.Default) { runCatching { c.handPacks() }.getOrDefault("") }
            if (core !== c || closed || said.isEmpty()) return@launch
            Log.i(EngineFrames.TAG, "vk packs again: ${said.lines().filter { it.isNotBlank() }.joinToString(", ")}")
            publishCamera(force = true)
            update()
        }
    }

    /** When the map last asked the core which windows it wants (SystemClock.uptimeMillis). */
    private var windowCheckedAt = 0L

    /**
     * The ground frame gestures and overlays take: the region today's map draws, or, where the core's picture is the
     * one seen, the pack the core draws.
     */
    private fun noteFrame() {
        val c = core
        val want = if (c == null || c.transition.canvas >= 0.5f) frames.frame else drawnPack()?.frame ?: frames.frame
        if (frame != want) frame = want
    }

    /** The pack the core draws at the camera now (PackList's choice), or null. */
    private fun drawnPack(): PackList.Pack? {
        val c = core ?: return null
        val v = viewport()
        if (v.isEmpty) return null
        val lv = liveView(state.camera, v)
        return c.packs.choose(lv.lat, lv.lon)
    }

    /** What the map says of its picture: the core's (complete and shown) where it draws, else today's map's. */
    private fun noteStatus() {
        // T2's honest state says what the map is, whatever the core or today's map says of their frames.
        if (honestReason != null) return noteHonest()
        val c = core
        val coreDraws = (c == null && coreExpected) || (c != null && coreWanted != Transition.Path.CANVAS)
        val status = if (coreDraws) {
            if (c != null && c.complete && c.transition.canvas == 0f) MapStatus.Ready else MapStatus.Loading(null)
        } else {
            frames.status
        }
        if (state.status != status) state.status = status
        val loading = if (coreDraws) status !is MapStatus.Ready else status !is MapStatus.Ready && status !is MapStatus.NoData || frames.busy
        if (state.loading != loading) state.loading = loading
    }

    /** The view as the camera sees it: its size, density, the principal point the padding and look ahead move, the region's frame. */
    fun viewport(): Viewport {
        val (w, h) = size
        val i = insets
        val inner = max(0f, h - i.top - i.bottom)
        return Viewport(w, h, density, offsetY = ((i.top - i.bottom) / 2 + lookAhead * LOOK_AHEAD * inner).toDouble(), frame = frame)
    }

    fun attach() {
        state.controller = this
        MapEngine.register(engineChanged)
        // Callers may be on any dispatcher; the camera's animations start on the main thread.
        state.animator = { target, ms -> await(withContext(Dispatchers.Main.immediate) { flyTo(target, ms, byUser = false) }) }
        state.fitter = { box, pad -> await(withContext(Dispatchers.Main.immediate) { fit(box, pad) }) }
        state.projector = object : MapState.Projector {
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
        }
    }

    fun detach() {
        main.removeCallbacks(published)
        stopAnimation()
        if (state.controller === this) {
            state.controller = null
            state.animator = null
            state.fitter = null
            state.projector = null
        }
        close()
    }

    private var closed = false

    /** Stops the frame thread without waiting for it (a frame being made stops at its next check); idempotent. */
    private fun close() {
        if (closed) return
        closed = true
        MapEngine.unregister(engineChanged)
        frames.close()
        main.removeCallbacks(published)
        main.removeCallbacks(coreChanged)
        main.removeCallbacks(settle)
        core?.let(::closeCore)
        core = null
    }

    // -------------------------------------------------------------------------------------------------------------
    // The core (K11).

    /**
     * Draws through [mapCore] from now on, from the packs [packs], into a TextureView where [texture] (else a
     * SurfaceView); null for either: today's map alone again. The core is handed its packs and the moment, and its map
     * turned on, off the main thread; until its first picture is complete, today's map's paper covers it (no
     * "Loading").
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
                lastRequest = null
                update()
            }
            return
        }
        if (old != null && old.core === mapCore && old.packs === packs && old.texture == texture) return
        old?.let(::closeCore)
        val c = CoreMap(mapCore, packs, texture, coreChanged)
        core = c
        canvasTick.intValue++
        coreWanted = null
        sentMoment = null
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
                // A core that cannot make the map's frame: T2's honest state, never today's map in its place.
                coreRefused = "the core could not draw the map: $refused"
                noteHonest()
                return@launch
            }
            // The packs are known now: the camera names the pack drawn, then the rule decides the place, so the picture
            // it waits for is one of that pack.
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

    /** The chrome's shapes over the map moved: the core draws a frame with them where they are. */
    fun chromeMoved() {
        val c = core ?: return
        c.source.chromeMoved()
        c.core.requestFrame()
    }

    /** Tells the chrome's shapes whether the core frosts under them, and the core's camera where to find them. */
    private fun noteChrome() {
        val c = core
        c?.source?.chrome = chrome
        val coreSeen = c != null && !c.texture && c.transition.canvas < 0.5f
        chrome?.let { if (it.coreDraws != coreSeen) it.coreDraws = coreSeen }
    }

    /** The core's map goes off if [c] drew it (off the main thread: it waits for the core's render thread). */
    private fun closeCore(c: CoreMap) {
        Thread({ runCatching { c.close() } }, "atlas-core-map-off").apply { isDaemon = true }.start()
    }

    /**
     * Publishes the core's camera ([CoreMap.source]): the camera now and, during an animation, the same animation a frame
     * on ([next] at [nextNs]), with the view, the pack the core draws and whether it draws at all; and asks the core for
     * a frame. Nothing when nothing changed since the last publication, unless [force] (the rest flag after a motion).
     */
    private fun publishCamera(next: CameraPosition? = null, nowNs: Long = System.nanoTime(), nextNs: Long = 0L, force: Boolean = false) {
        val c = core ?: return
        val v = viewport()
        if (v.isEmpty) return
        val cam = state.camera
        val rest = !touching && animation?.isActive != true && lookAheadJob?.isActive != true
        // Where today's map is wanted and none of the core's packs reaches the ground in view (a jump away from them),
        // the core holds its last picture until today's map has its own: a frame of nothing would be a blank one.
        val holds = coreWanted == Transition.Path.CANVAS && CameraOps.visibleBounds(cam, v)?.let { !c.packs.meets(it) } != false
        val draw = !c.transition.coreHidden && !holds
        // the location's puck where the core draws the map with nothing of today's map over it, as its labels, and nothing
        // on the ground that the canvas draws over the core's picture (a route ribbon, an area: until the core has them)
        val location = overlays.location.takeIf {
            draw && coreWanted == Transition.Path.CORE && c.transition.canvas == 0f && (!overlays.onGround || coreRoutes)
        }
        // the parts the core leaves out: the style's trees and soft shadows where its layers have none, and what the app's
        // diagnostics switch off (MapEngine.leftOut)
        val off = (if (MapLayer.TREES in style.layers) 0L else MapPart.TREES.bit) or
            (if (MapLayer.SHADOWS in style.layers) 0L else MapPart.BUILDING_SHADOWS.bit) or MapEngine.leftOut
        if (!force && next == null && cam == publishedCamera && v == publishedViewport && rest == publishedRest && draw == publishedDraw &&
            location == publishedLocation && off == publishedOff
        ) {
            return
        }
        val lv = liveView(cam, v)
        val n = if (next != null && nextNs > nowNs) liveView(next, v) else null
        // the window whose box holds the target, whatever the distance: zooming hands nothing over
        val pack = c.packs.choose(lv.lat, lv.lon)
        c.source.publish(
            MapCameraSource.Samples(
                revision = ++cameraRevision,
                t0 = nowNs, lat0 = lv.lat, lon0 = lv.lon, distance0 = lv.distance, bearing0 = lv.bearing, pitch0 = lv.pitch, principalY0 = lv.cyDp,
                t1 = if (n != null) nextNs else nowNs, lat1 = n?.lat ?: lv.lat, lon1 = n?.lon ?: lv.lon, distance1 = n?.distance ?: lv.distance,
                bearing1 = n?.bearing ?: lv.bearing, pitch1 = n?.pitch ?: lv.pitch, principalY1 = n?.cyDp ?: lv.cyDp,
                principalXDp = lv.widthDp / 2.0, widthPx = v.width, heightPx = v.height, widthDp = lv.widthDp, heightDp = lv.heightDp,
                density = v.density.toDouble(), rest = rest, follow = state.follow != Follow.NONE,
                pack = pack?.number ?: 0L, profile = pack?.profile ?: CameraWriter.PROFILE_NONE, draw = draw,
                location = location?.let { MapCameraSource.Location(it.at.lat, it.at.lon, it.accuracyM.toFloat(), it.heading?.toFloat()) },
                off = off,
            ),
        )
        publishedCamera = cam
        publishedOff = off
        publishedViewport = v
        publishedRest = rest
        publishedDraw = draw
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
     * The transitional rule now: the core where it has tiles, today's map elsewhere ([CoreMap.transition]), stepped
     * each frame while a switch waits for its picture or fades.
     */
    private fun stepTransition() {
        val c = core ?: return
        if (!advanceTransition(c) || transitionJob?.isActive == true) return
        transitionJob = scope.launch {
            while (core === c) {
                withFrameNanos { }
                frameTick.intValue++
                if (!advanceTransition(c)) break
            }
            // Settled: whether the core draws at all may have changed (covered whole by today's map, or not).
            publishCamera()
            noteFrame()
            noteStatus()
            if (c.transition.canvasHidden && shown != null) scheduleIdle(c)
        }
    }

    /**
     * Where the core has drawn alone for [IDLE_MS], today's map lets go of its frame and the engine's scenes (the Java
     * heap the core's places do not need, §3); they come back with today's map's next place.
     */
    private fun scheduleIdle(c: CoreMap) {
        if (idleJob?.isActive == true) return
        idleJob = scope.launch {
            delay(IDLE_MS)
            if (core !== c || !c.transition.canvasHidden) return@launch
            frames.idle()
            shown = null
            previous = null
            lastRequest = null
        }
    }

    /** One step of the rule; true while it is still under way. */
    private fun advanceTransition(c: CoreMap): Boolean {
        val wanted = coreWanted ?: return false
        val coreReady = c.complete && c.completeRevision >= wantRevision
        val cover = c.transition.canvas
        val due = c.transition.update(SystemClock.uptimeMillis(), wanted, coreReady, canvasReady(), whereDrawn(wanted))
        if (c.transition.canvas != cover) canvasTick.intValue++
        // Whether the core draws may change mid-way (no longer covered whole): it hears at once.
        if (publishedDraw != !c.transition.coreHidden) publishCamera()
        noteFrame()
        noteChrome()
        return due
    }

    /**
     * Today's map has its picture here: a frame made since the switch to it began, or its own honest state (no data
     * here, a download under way), once it has finished with the newest request.
     */
    private fun canvasReady(): Boolean {
        if (frames.busy) return false
        return when (frames.status) {
            is MapStatus.Ready -> (shown?.number ?: 0L) > canvasFrameAtWant
            is MapStatus.NoData, is MapStatus.Downloading -> true
            is MapStatus.Loading -> false
        }
    }

    /** Where the path [wanted] draws, for the log: today's map's region, or the core's pack. */
    private fun whereDrawn(wanted: Transition.Path): String = when (wanted) {
        Transition.Path.CANVAS -> shown?.region ?: (frames.status as? MapStatus.Downloading)?.region ?: "nowhere (no data here)"
        Transition.Path.CORE -> drawnPack()?.id ?: "-"
    }

    /** The core's first complete picture since the app began: `vk first frame <ms> first|warm` (Appendix C), once. */
    private fun logFirstFrame() {
        val c = core ?: return
        val at = c.firstCompleteAt
        if (at < 0 || !MapEngine.firstFrameLogged.compareAndSet(false, true)) return
        val from = MapEngine.startedAt.takeIf { it > 0 } ?: android.os.Process.getStartUptimeMillis()
        Log.i(EngineFrames.TAG, "vk first frame ${at - from} ${if (MapEngine.firstLaunch) "first" else "warm"}")
    }

    /**
     * The labels: the core draws them once its picture shows with nothing of today's map over it (fixed where they are
     * on the map, growing and shrinking with it, ENGINE.md design point 8), today's map's canvas until then; handed over
     * off the main thread when that, the labels or their face change.
     */
    private fun sendLabels(c: CoreMap) {
        val draws = coreWanted == Transition.Path.CORE && c.transition.canvas == 0f
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
            if (why.isNotEmpty()) Log.w(EngineFrames.TAG, "vk labels: $why")
        }
    }

    /**
     * The markers: the core draws them where it draws the map with nothing of today's map over it and nothing on the
     * ground the canvas draws over its picture (a route, an area: the canvas draws those, and the markers over them,
     * until the core draws them), so they move with the map in the same frame; today's map's canvas elsewhere. Handed
     * over off the main thread when that, the markers or the density change; the canvas lets go of them once the core
     * has them, so none are missing between.
     */
    private fun sendMarkers(c: CoreMap) {
        val draws = coreWanted == Transition.Path.CORE && c.transition.canvas == 0f && (!overlays.onGround || coreRoutes)
        val markers = if (draws) overlays.markers else emptyList()
        val sent = sentMarkers
        if (sent != null && sent.draws == draws && sent.density == density) {
            if (sent.markers === markers) return
            // an equal list made anew (a recomposition): kept, so the next frames compare it by identity
            if (sent.markers == markers) {
                sentMarkers = SentMarkers(markers, density, draws)
                return
            }
        }
        sentMarkers = SentMarkers(markers, density, draws)
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
            val why = c.markers(markers, painter, revision)
            if (why.isNotEmpty()) Log.w(EngineFrames.TAG, "vk markers: $why")
            main.post {
                if (core !== c || revision != markersRevision) return@post
                coreMarkers = draws && why.isEmpty()
                frameTick.intValue++
                canvasTick.intValue++
            }
        }
    }

    /**
     * The routes and areas: the core draws them where it draws the map with nothing of today's map over it, on the
     * street in the same frame as the map, under its labels, markers and puck; today's map's canvas elsewhere. Handed over
     * off the main thread when that, the routes, the areas or the density change; the canvas lets go of them once the
     * core has them, and the markers and the puck follow them into the core.
     */
    private fun sendRoutes(c: CoreMap) {
        val draws = coreWanted == Transition.Path.CORE && c.transition.canvas == 0f
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
            if (why.isNotEmpty()) Log.w(EngineFrames.TAG, "vk routes: $why")
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

    /** The moment the core lights the map by, handed over off the main thread when it changed. */
    private fun sendMoment(c: CoreMap, moment: Moment) {
        if (moment == sentMoment) return
        sentMoment = moment
        val zone = drawnPack()?.zone ?: java.time.ZoneId.of("Europe/Amsterdam")
        val daylight = MapEngine.options.daylight
        scope.launch(Dispatchers.Default) { c.moment(moment, zone, daylight) }
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

    /** True when nothing is loading, being made, fading, animating or waiting to be drawn: the picture is final. */
    val settled: Boolean
        get() {
            val c = core
            if (c != null) {
                if (transitionJob?.isActive == true || animation?.isActive == true || warming) return false
                // Where the core draws, its picture complete and shown is the final one.
                if (coreWanted == Transition.Path.CORE) return c.complete && c.transition.canvas == 0f
            }
            if (frames.busy || fade < 1f || animation?.isActive == true || warming) return false
            val status = frames.status
            if (status is MapStatus.Loading || status is MapStatus.Downloading) return false
            val f = frames.latest
            return f == null || f.number == lastDrawnNumber
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
     * Asks for a frame of the current camera, style and moment; reports the camera when it moved. With no fixed
     * [MapState.time] the light follows the clock, in steps of [MOMENT_STEP_S]. Under the core, today's map is asked
     * only where the transitional rule wants it, and the core gets the camera and the moment.
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
            // The transitional rule's place: the core where it has tiles for this camera, today's map elsewhere.
            val wanted = if (c.packs.covers(view.lat, view.lon, view.distance)) Transition.Path.CORE else Transition.Path.CANVAS
            if (wanted != coreWanted) {
                coreWanted = wanted
                canvasTick.intValue++
                if (wanted == Transition.Path.CANVAS) {
                    // Today's map makes a frame for this place, whatever it made before.
                    lastRequest = null
                    canvasFrameAtWant = shown?.number ?: 0L
                }
            }
            // The windows the core wants (ENGINE.md step 3): the nearest taken from its country's tiles or built in the
            // background, one at a time, and the core handed it when it is; asked twice a second.
            val now = SystemClock.uptimeMillis()
            if (now - windowCheckedAt >= WINDOWS_CHECK_MS) {
                windowCheckedAt = now
                MapEngine.windows()?.let { w ->
                    scope.launch(Dispatchers.IO) {
                        if (runCatching { w.step(c.core) }.getOrDefault(false)) main.post(repack)
                    }
                }
            }
        }
        if (c != null) {
            publishCamera()
            if (coreWanted == Transition.Path.CORE && c.transition.wanted != Transition.Path.CORE) wantRevision = cameraRevision
            sendMoment(c, moment)
            stepTransition()
        }
        // Today's map alone, or where the rule wants it; never while the core is on its way, nor in T2's honest state.
        if (honestReason == null && ((c == null && !coreExpected) || coreWanted == Transition.Path.CANVAS)) {
            val req = EngineFrames.Request(view, moment, density, MapEngine.options, moment == Moment.Named("night"))
            if (req != lastRequest) {
                lastRequest = req
                frames.submit(req)
            }
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

    private fun startFade() {
        fadeJob?.cancel()
        if (previous == null) {
            fade = 1f
            return
        }
        fade = 0f
        fadeJob = scope.launch {
            var start = -1L
            while (true) {
                val now = withFrameNanos { it }
                if (start < 0) start = now
                fade = ((now - start) / 1e6 / FADE_MS).coerceIn(0.0, 1.0).toFloat()
                frameTick.intValue++
                if (fade >= 1f) break
            }
            previous = null
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Drawing.

    private fun painter(): OverlayPainter = painter ?: OverlayPainter(density).also { p ->
        p.markers.sans = markerSans
        painter = p
    }

    /** The markers' text in the boards' Geist, from the fonts the engine's frames use. */
    private val markerSans: (Int) -> Typeface = { w -> (fonts ?: SvgFonts.system()).typeface("Geist", w, false) }

    /** The tilt readout's painter, made at its first draw (and again when the density changes); its figures in Geist too. */
    private fun tiltReadoutPainter(): TiltReadoutPainter =
        readoutPainter?.takeIf { it.density == density } ?: TiltReadoutPainter(density, markerSans).also { readoutPainter = it }

    /** The engine camera of the current view, in the frame of [f] and at its ground level, in CSS pixels. */
    private fun cameraFor(f: EngineFrame, v: Viewport): SvgCamera {
        val lv = liveView(state.camera, v)
        val r = f.frame
        return SvgCamera(
            r.x(lv.lat, lv.lon), f.camera.targetY, r.z(lv.lat, lv.lon), LiveCamera.bearing(lv.bearing), LiveCamera.enginePitch(lv.pitch),
            lv.distance, LiveCamera.FOV, lv.widthDp.toDouble(), lv.heightDp.toDouble(), cx = lv.widthDp / 2.0, cy = lv.cyDp,
        )
    }

    /**
     * Whether the canvas draws nothing over the core's picture now (ENGINE.md gain 12, [CanvasWork.idle]). It reads
     * [debug], [tape], [readout] and [overlays], whose changes draw the canvas again; what else it reads says it changed
     * through [canvasTick].
     */
    fun canvasIdle(): Boolean {
        val c = core ?: return false
        return CanvasWork.idle(
            debug = debug, tape = tape != null, readout = readout != null, honest = honestReason != null, core = coreWanted == Transition.Path.CORE,
            cover = c.transition.canvas, overlays = overlays, coreRoutes = coreRoutes, coreLabels = coreLabels,
            coreMarkers = coreMarkers, coreLocation = coreLocation,
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
     * Draws the newest frame (warped to the camera, cross-faded from the one before) and the overlays over it. Under the
     * core, today's map covers the core's picture only as much as the transitional rule says (at the start its paper
     * alone, until the core's first picture is complete).
     */
    fun draw(canvas: Canvas) {
        val t0 = System.nanoTime()
        val v = viewport()
        val f = shown
        val c = core
        val cover = c?.transition?.canvas ?: 1f
        val night = if (f != null) f.moment == "night" else c?.night == true
        if (cover > 0f) {
            val layered = cover < 1f && !v.isEmpty
            if (layered) canvas.saveLayerAlpha(0f, 0f, v.width.toFloat(), v.height.toFloat(), (cover * 255f).roundToInt())
            canvas.drawColor(if (night) NIGHT_EARTH else DAY_EARTH)
            if (f != null && !v.isEmpty) {
                val lv = liveView(state.camera, v)
                canvas.save()
                canvas.scale(v.width / lv.widthDp.toFloat(), v.height / lv.heightDp.toFloat())
                val p = previous
                if (p != null && fade < 1f && p.frame == f.frame) p.draw(canvas, cameraFor(p, v))
                f.draw(canvas, cameraFor(f, v), if (p != null && p.frame == f.frame) fade else 1f)
                canvas.restore()
            }
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
            if (!coreMarkers) pt.drawMarkers(canvas)
            if (!coreLocation) ov.location?.let { pt.drawLocation(canvas, g, it) }
            tape?.let { pt.drawTape(canvas, g, it, units, Locale.getDefault()) }
            readout?.let { tiltReadoutPainter().draw(canvas, it, state.camera.pitch, CameraOps.maxPitch(state.camera.zoom), night) }
            drawStatus(canvas)
            // the timings show while the map is loading or working; a finished map is the board's, with nothing over it
            // (the core's line while the map moves and a few seconds after)
            val canvasPanel = debug && ((c == null && !coreExpected) || coreWanted == Transition.Path.CANVAS) &&
                (f == null || frames.busy || frames.status !is MapStatus.Ready)
            val corePanel = debug && c != null && panelLine.isNotEmpty() && SystemClock.uptimeMillis() - movedAt < PANEL_LINGER_MS
            if (canvasPanel || corePanel) drawDebug(canvas, f, pt, canvasPanel, corePanel)
        }
        val t1 = System.nanoTime()
        drawMillis = drawMillis * 0.8 + (t1 - t0) / 1e6 * 0.2
        if (f != null && f.number != lastDrawnNumber) {
            lastDrawnNumber = f.number
            Log.i(EngineFrames.TAG, "frame ${f.number} drawn in %.2f ms (a warp of it: the same or less)".format(Locale.ROOT, (t1 - t0) / 1e6))
        }
    }

    /** "Loading the map…", with the seconds once it takes a while. */
    private fun loadingText(): String {
        val since = frames.workSince
        val secs = if (since == 0L) 0L else (frames.now() - since) / 1000
        return if (secs < 3) "Loading the map…" else "Loading the map… %d s".format(Locale.getDefault(), secs)
    }

    /**
     * The map's honest state, when it is not simply the picture: loading, downloading with its size, no data here.
     * Where the core draws, none: its tiles are on the phone (no "Loading").
     */
    private fun drawStatus(canvas: Canvas) {
        if ((core == null && coreExpected) || (core != null && coreWanted != Transition.Path.CANVAS)) return
        val text = windowsText() ?: when (val s = frames.status) {
            is MapStatus.Ready -> return
            is MapStatus.Loading -> if (shown == null) loadingText() else return
            is MapStatus.Downloading -> if (s.building) "Fetching this area from OpenStreetMap…"
            else if (s.bytes > 0) "Downloading map data · %.1f of %.1f MB".format(Locale.getDefault(), s.done / 1e6, s.bytes / 1e6)
            else "Downloading map data…"
            is MapStatus.NoData -> if (shown == null) "No map data here" else return
        }
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
     * Where the core has no tiles yet, what its windows are doing (ENGINE.md step 3), which says why the place is empty:
     * today's map builds no area of its own there any more. Null where the windows rest.
     */
    private fun windowsText(): String? {
        if (core == null) return null
        if (!MapEngine.online()) return "Offline · this area is not on the phone yet"
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
        return when (s) {
            Windows.Status.Idle -> null
            is Windows.Status.Fetching -> "Getting this area from OpenStreetMap…"
            is Windows.Status.Building -> "Building the map here…"
            is Windows.Status.Waiting ->
                if (s.refused) {
                    "This area's data could not be built"
                } else if (MapEngine.overpassOff()) {
                    "No pack holds this area, and Overpass is switched off"
                } else {
                    val minutes = ((w.remaining(s) + 59_999L) / 60_000L).coerceAtLeast(1L)
                    "OpenStreetMap's servers are busy · trying again in %d min".format(Locale.getDefault(), minutes)
                }
        }
    }

    private var windowsTickPosted = false

    private fun drawDebug(canvas: Canvas, f: EngineFrame?, p: OverlayPainter, canvasLines: Boolean = true, coreLines: Boolean = false) {
        val c = state.camera
        val t = f?.timings
        val lines = ArrayList<String>(10)
        if (coreLines) {
            // The core's line (§8), and what the transitional rule shows.
            lines += panelLine.lines()
            val k = core
            if (k != null) lines += "core %s · today's map %.0f%%".format(Locale.ROOT, drawnPack()?.id ?: "none here", k.transition.canvas * 100f)
        }
        if (canvasLines) {
            lines += if (t == null) "no frame yet" else "frame %d: load %d · render %d · parse %d · raster %d ms".format(Locale.ROOT, f.number, t.load, t.render, t.parse, t.raster)
            lines += frames.stage.let { st ->
                if (st.isEmpty()) "idle" else "now: %s%s · %d s".format(Locale.ROOT, st, frames.stageRegion?.let { " $it" } ?: "", (frames.now() - frames.stageSince) / 1000)
            }
            lines += when (val s = frames.status) {
                is MapStatus.NoData -> "status: ${s.reason}".take(90)
                is MapStatus.Downloading -> "status: ${if (s.building) "building" else "downloading"} ${s.region} ${s.done / 1000} of ${s.bytes / 1000} KB"
                else -> frames.lastError?.let { "last error: $it".take(90) } ?: "status: ${s::class.simpleName}"
            }
            lines += "draw %.1f ms%s".format(Locale.ROOT, drawMillis, if (frames.failures > 0) " · ${frames.failures} failed" else "")
            lines += if (f == null) "-" else "%s · %s · %s · band %.2f m".format(Locale.ROOT, f.region, f.view ?: "between views", f.moment, f.tolerance)
        }
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

    /** Fits [box] inside the content padding plus [paddingPx]. */
    fun fit(box: BBox, paddingPx: Int): Job {
        val v = viewport()
        if (v.isEmpty) {
            write(state.camera.copy(target = box.center), false)
            return Job().apply { complete() }
        }
        val i = insets
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
        val at = CameraOps.groundAt(state.camera, v, x, y) ?: return
        onTap(MapHit.Empty(at))
    }

    override fun onLongPress(x: Float, y: Float) {
        val v = viewport()
        if (v.isEmpty) return
        val at = CameraOps.groundAt(state.camera, v, x, y)
        val c = core
        val building = onLongPressBuilding
        // the building under the finger as the core drew it, where it draws the map: the first one the ray meets, which
        // may stand before the street point under the finger
        if (c != null && building != null && coreWanted == Transition.Path.CORE && c.transition.canvas == 0f) {
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
        const val DAY_EARTH = 0xFFE4E0D8.toInt()
        const val NIGHT_EARTH = 0xFF0B0F1A.toInt()

        /** How long a new exact frame takes to fade in over the last, ms. */
        const val FADE_MS = 180.0

        /** The least text size a label draws at, sp: Android's text size scales it (the core's readable floor). */
        const val READABLE_SP = 12f

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

        /** How long the core draws alone before today's map lets go of its frame and scenes (ms). */
        const val IDLE_MS = 10_000L

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
