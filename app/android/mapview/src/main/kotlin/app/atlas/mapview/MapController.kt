package app.atlas.mapview

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
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
import app.atlas.mapview.svg.SvgCamera
import app.atlas.mapview.svg.SvgFonts
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
import kotlin.math.max
import kotlin.math.roundToInt

/** Content padding in pixels: left, top, right, bottom. */
data class Insets(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f)

/**
 * The map view's engine room, one per [AtlasMap]: the camera to the 1:1 engine's exact frames on a thread of their own
 * ([EngineFrames]), the newest frame warped to the camera at once and cross-faded into the next, the overlays on top
 * (projected with the engine's camera, in the region's own frame), gestures to camera moves, and the camera's animations
 * (fly-to, zoom steps, flings, the snap to north, following the phone).
 *
 * Everything here runs on the main thread except what the frame thread does.
 */
internal class MapController(val state: MapState, private val scope: CoroutineScope) : GestureListener, RememberObserver {
    private val main = Handler(Looper.getMainLooper())

    /** The engine's typefaces, set by the view from its assets. */
    var fonts: SvgFonts? = null

    /** The labels' face (the boards' Instrument Serif italic), given by the app. */
    var labelTypeface: android.graphics.Typeface? = null
    val frames: EngineFrames = EngineFrames(fonts = { fonts ?: SvgFonts.system() }, onChange = { main.post(published) })
    private val engineChanged: () -> Unit = { frames.invalidate() }

    /** Bumped on the main thread whenever there is something new to draw; the view's draw reads it. */
    val frameTick = mutableIntStateOf(0)
    var style by mutableStateOf(MapStyle())
    var overlays by mutableStateOf(MapOverlays())
    var debug by mutableStateOf(false)
    var tape by mutableStateOf<Tape?>(null)
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
    var onCameraMove: (CameraPosition, Boolean) -> Unit = { _, _ -> }

    val gestures = GestureMachine(GestureConfig.forDensity(1f), this)

    /** The overlays' painter, made at the first draw (and again when the density changes). */
    var painter: OverlayPainter? = null
        private set
    private var animation: Job? = null
    private var lookAheadJob: Job? = null
    private var tapeJob: Job? = null
    private var fadeJob: Job? = null
    private var touching = false
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var lastWritten: CameraPosition? = null
    private var lastWrittenByUser = false
    private var lastReported: CameraPosition? = null
    private var lastFix: LatLon? = null
    private var course: Double? = null
    private var lastRequest: EngineFrames.Request? = null

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
        frameTick.intValue++
        val f = frames.latest
        if (frame != frames.frame) frame = frames.frame
        if (f != null && f !== shown) {
            previous = shown
            shown = f
            startFade()
        }
        val status = frames.status
        if (state.status != status) state.status = status
        val loading = status !is MapStatus.Ready && status !is MapStatus.NoData || frames.busy
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

    /** Stops the frame thread; idempotent. */
    private fun close() {
        if (closed) return
        closed = true
        MapEngine.unregister(engineChanged)
        frames.close()
    }

    override fun onRemembered() {}

    override fun onForgotten() = close()

    /** Composition was dropped before it applied: the thread started for it must not linger. */
    override fun onAbandoned() = close()

    /** True when nothing is loading, being made, fading, animating or waiting to be drawn: the picture is final. */
    val settled: Boolean
        get() {
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
        Theme.GRAY -> Moment.Named("grey")
        Theme.AUTO, Theme.CHART -> Moment.Live(quantise(time ?: Instant.now()), MapEngine.weather?.hours(at))
    }

    /**
     * Asks for a frame of the current camera, style and moment; reports the camera when it moved. With no fixed
     * [MapState.time] the light follows the clock, in steps of [MOMENT_STEP_S].
     */
    fun update() {
        val v = viewport()
        if (v.isEmpty) return
        val cam = state.camera
        val s = style
        val view = liveView(cam, v)
        val moment = momentOf(s, state.time, cam.target)
        val req = EngineFrames.Request(view, moment, density, MapEngine.options, moment == Moment.Named("night"))
        if (req != lastRequest) {
            lastRequest = req
            frames.submit(req)
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
        // the markers' text in the boards' Geist, from the fonts the engine's frames use
        p.markers.sans = { w -> (fonts ?: SvgFonts.system()).typeface("Geist", w, false) }
        painter = p
    }

    /** The engine camera of the current view, in the frame of [f] and at its ground level, in CSS pixels. */
    private fun cameraFor(f: EngineFrame, v: Viewport): SvgCamera {
        val lv = liveView(state.camera, v)
        val r = f.frame
        return SvgCamera(
            r.x(lv.lat, lv.lon), f.camera.targetY, r.z(lv.lat, lv.lon), LiveCamera.bearing(lv.bearing), LiveCamera.enginePitch(lv.pitch),
            lv.distance, LiveCamera.FOV, lv.widthDp.toDouble(), lv.heightDp.toDouble(), cx = lv.widthDp / 2.0, cy = lv.cyDp,
        )
    }

    /** Draws the newest frame (warped to the camera, cross-faded from the one before) and the overlays over it. */
    fun draw(canvas: Canvas) {
        val t0 = System.nanoTime()
        val v = viewport()
        val f = shown
        canvas.drawColor(if (f?.moment == "night") NIGHT_EARTH else DAY_EARTH)
        if (f != null && !v.isEmpty) {
            val lv = liveView(state.camera, v)
            canvas.save()
            canvas.scale(v.width / lv.widthDp.toFloat(), v.height / lv.heightDp.toFloat())
            val p = previous
            if (p != null && fade < 1f && p.frame == f.frame) p.draw(canvas, cameraFor(p, v))
            f.draw(canvas, cameraFor(f, v), if (p != null && p.frame == f.frame) fade else 1f)
            canvas.restore()
        }
        if (!v.isEmpty) {
            val g = GroundView(CameraOps.camera(state.camera, v))
            val pt = painter()
            val ov = overlays
            pt.layoutMarkers(g, ov.markers)
            pt.drawGround(canvas, g, ov)
            labelTypeface?.let { pt.labels.typeface = it }
            pt.drawLabels(canvas, g, ov)
            pt.drawMarkers(canvas)
            ov.location?.let { pt.drawLocation(canvas, g, it) }
            tape?.let { pt.drawTape(canvas, g, it, units, Locale.getDefault()) }
            drawStatus(canvas)
            // the timings show while the map is loading or working; a finished map is the board's, with nothing over it
            if (debug && (f == null || frames.busy || frames.status !is MapStatus.Ready)) drawDebug(canvas, f, pt)
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

    /** The map's honest state, when it is not simply the picture: loading, downloading with its size, no data here. */
    private fun drawStatus(canvas: Canvas) {
        val text = when (val s = frames.status) {
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

    private fun drawDebug(canvas: Canvas, f: EngineFrame?, p: OverlayPainter) {
        val c = state.camera
        val t = f?.timings
        val lines = arrayOf(
            if (t == null) "no frame yet" else "frame %d: load %d · render %d · parse %d · raster %d ms".format(Locale.ROOT, f.number, t.load, t.render, t.parse, t.raster),
            frames.stage.let { st ->
                if (st.isEmpty()) "idle" else "now: %s%s · %d s".format(Locale.ROOT, st, frames.stageRegion?.let { " $it" } ?: "", (frames.now() - frames.stageSince) / 1000)
            },
            when (val s = frames.status) {
                is MapStatus.NoData -> "status: ${s.reason}".take(90)
                is MapStatus.Downloading -> "status: ${if (s.building) "building" else "downloading"} ${s.region} ${s.done / 1000} of ${s.bytes / 1000} KB"
                else -> frames.lastError?.let { "last error: $it".take(90) } ?: "status: ${s::class.simpleName}"
            },
            "draw %.1f ms%s".format(Locale.ROOT, drawMillis, if (frames.failures > 0) " · ${frames.failures} failed" else ""),
            if (f == null) "-" else "%s · %s · %s · band %.2f m".format(Locale.ROOT, f.region, f.view ?: "between views", f.moment, f.tolerance),
            "markers %d/%d · vertices %d".format(Locale.ROOT, p.markerCount, overlays.markers.size, p.routeVertices),
            "z %.2f · bearing %.0f° · pitch %.0f°".format(Locale.ROOT, c.zoom, c.bearing, c.pitch),
        )
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

    private fun write(p: CameraPosition, byUser: Boolean) {
        val c = CameraOps.clamp(p)
        lastWritten = c
        lastWrittenByUser = byUser
        state.camera = c
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

    /** Runs [frame] for t from 0 to 1 over [durationMs], one camera per screen frame. */
    private fun animate(durationMs: Long, byUser: Boolean, frame: (Double) -> CameraPosition): Job {
        stopAnimation()
        val job = scope.launch {
            var start = -1L
            while (true) {
                val now = withFrameNanos { it }
                if (start < 0) start = now
                val t = if (durationMs <= 0) 1.0 else ((now - start) / 1e6 / durationMs).coerceIn(0.0, 1.0)
                write(frame(t), byUser)
                if (t >= 1.0) break
            }
        }
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
        animation = scope.launch {
            var start = -1L
            var doneX = 0.0
            var doneY = 0.0
            while (true) {
                val now = withFrameNanos { it }
                if (start < 0) { start = now; continue }
                val t = (now - start) / 1e9
                val f = (1 - exp(-FLING_DECAY * t)) / FLING_DECAY
                val dx = vx * f
                val dy = vy * f
                write(CameraOps.pan(state.camera, viewport(), x, y, (x + dx - doneX).toFloat(), (y + dy - doneY).toFloat()), true)
                doneX = dx
                doneY = dy
                if (speed * exp(-FLING_DECAY * t) < FLING_STOP_DP * density || t > FLING_MAX_S) break
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
            Follow.COURSE -> cur.copy(
                target = loc.at, bearing = course ?: loc.heading ?: cur.bearing,
                pitch = max(cur.pitch, COURSE_PITCH), zoom = max(cur.zoom, COURSE_MIN_ZOOM),
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

    override fun onTilt(dy: Float) {
        write(CameraOps.tilt(state.camera, -dy / density * TILT_PER_DP), true)
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
        CameraOps.groundAt(state.camera, v, x, y)?.let { onLongPress(it) }
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
    }

    companion object {
        const val DAY_EARTH = 0xFFE4E0D8.toInt()
        const val NIGHT_EARTH = 0xFF0B0F1A.toInt()

        /** How long a new exact frame takes to fade in over the last, ms. */
        const val FADE_MS = 180.0

        /** The live moment moves on in steps of this many seconds (each step is a new frame). */
        const val MOMENT_STEP_S = 300L

        /** [t] rounded down to a [MOMENT_STEP_S] step. */
        fun quantise(t: Instant): Instant = Instant.ofEpochSecond(t.epochSecond - Math.floorMod(t.epochSecond, MOMENT_STEP_S))

        const val FIT_MS = 650
        const val ZOOM_STEP_MS = 280L
        const val SNAP_MS = 220L
        const val SNAP_DEGREES = 7.0
        const val TILT_PER_DP = 0.32
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
