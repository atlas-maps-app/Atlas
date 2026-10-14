package app.atlas.mapview

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import app.atlas.engine.Extras
import app.atlas.engine.Hit
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.TileId
import app.atlas.geo.UnitSystem
import app.atlas.model.Place
import app.atlas.tiles.TileSource
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

/** Content padding in pixels: left, top, right, bottom. */
data class Insets(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f)

/**
 * The map view's engine room, one per [AtlasMap]: tiles to scenes ([ScenePipeline]), scenes to frames on a thread of
 * its own ([FrameLoop]), frames and overlays to the canvas, gestures to camera moves, and the camera's animations
 * (fly-to, zoom steps, flings, the snap to north, following the phone).
 *
 * Everything here runs on the main thread except what the pipeline and the frame loop do on theirs.
 */
internal class MapController(val state: MapState, private val scope: CoroutineScope) : GestureListener, RememberObserver {
    private val main = Handler(Looper.getMainLooper())
    private val typefaces = LabelTypefaces()
    val cache = SceneCache(SceneCache.defaultBudget())
    val pipeline = ScenePipeline(cache, onChange = { loop.invalidate() })
    val loop: FrameLoop = FrameLoop(AndroidTextMeasurer(typefaces), pipeline, onFrame = { main.post(published) })
    private val drawer = FrameDrawer(typefaces)

    /** Bumped on the main thread whenever a new frame is ready; the view's draw reads it. */
    val frameTick = mutableIntStateOf(0)
    var style by mutableStateOf(MapStyle())
    var overlays by mutableStateOf(MapOverlays())
    var debug by mutableStateOf(false)
    var tape by mutableStateOf<Tape?>(null)
    var size by mutableStateOf(0 to 0)
    var insets by mutableStateOf(Insets())

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
    private var touching = false
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var lastWritten: CameraPosition? = null
    private var lastWrittenByUser = false
    private var lastReported: CameraPosition? = null
    private var lastLook: ResolvedLook? = null
    private var lastFix: LatLon? = null
    private var course: Double? = null
    private var lastSubmitted: CameraPosition? = null
    private var lastViewport: Viewport? = null
    private var lastSubmittedLook: ResolvedLook? = null
    private var lastSubmittedStyle: MapStyle? = null
    private var lastLookCell = 0L
    private var lastLookStyle: MapStyle? = null
    @Volatile
    private var warming = false
    private val warmNames = HashSet<String>()
    private val warmLogos = HashSet<String>()

    // Debug figures.
    private var drawMillis = 0.0
    private var frameMillis = 0.0
    private var fps = 0.0
    private var lastFrameNumber = 0L
    private var lastFrameAt = 0L
    private val debugText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; color = Color.WHITE }
    private val debugPanel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB0141619.toInt() }
    private val debugRect = RectF()

    private val published = Runnable {
        frameTick.intValue++
        val f = loop.latest
        val loading = f != null && f.loading > 0
        if (state.loading != loading) state.loading = loading
    }

    /** The view as the camera sees it: its size, density and the principal point the padding and look ahead move. */
    fun viewport(): Viewport {
        val (w, h) = size
        val i = insets
        val inner = max(0f, h - i.top - i.bottom)
        return Viewport(w, h, density, offsetY = ((i.top - i.bottom) / 2 + lookAhead * LOOK_AHEAD * inner).toDouble())
    }

    fun attach() {
        state.controller = this
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

    /** Stops the frame thread and every load; idempotent. */
    private fun close() {
        if (closed) return
        closed = true
        loop.close()
        pipeline.close()
    }

    override fun onRemembered() {}

    override fun onForgotten() = close()

    /** Composition was dropped before it applied: the threads started for it must not linger. */
    override fun onAbandoned() = close()

    fun setSource(tiles: TileSource?) {
        if (pipeline.setSource(tiles)) loop.invalidate()
    }

    fun setExtras(extras: (suspend (TileId) -> Extras?)?) {
        pipeline.extras = extras
    }

    /** True when nothing is loading, being made, animating or waiting to be drawn: the picture is final. */
    val settled: Boolean
        get() {
            val f = loop.latest ?: return false
            return pipeline.pending == 0 && !loop.busy && !f.fading && f.loading == 0 && animation?.isActive != true &&
                !warming && f.number == lastFrameNumber
        }

    /**
     * Asks for a frame of the current camera, look and style; reports the camera when it moved. With no fixed
     * [MapState.time] the light follows the clock, checked whenever the camera or the phone's position changes.
     */
    fun update() {
        val v = viewport()
        if (v.isEmpty) return
        val cam = state.camera
        val s = style
        val time = state.time ?: Instant.now()
        // The look changes with the light's grid cell or the style, not with every move of the camera.
        val cell = Looks.cell(time, cam.target)
        val look = lastLook?.takeIf { cell == lastLookCell && s == lastLookStyle } ?: Looks.resolve(s, time, cam.target).also {
            lastLook = it
            lastLookCell = cell
            lastLookStyle = s
        }
        // A frame only when something it is made of changed (a new fix alone checks the light, nothing more).
        if (cam != lastSubmitted || v != lastViewport || look !== lastSubmittedLook || s != lastSubmittedStyle) {
            lastSubmitted = cam
            lastViewport = v
            lastSubmittedLook = look
            lastSubmittedStyle = s
            loop.submit(RenderInput(CameraOps.camera(cam, v), look, Looks.frameStyle(s, cam.zoom)))
        }
        val bounds = CameraOps.visibleBounds(cam, v)
        if (bounds != state.visibleBounds) state.visibleBounds = bounds
        if (cam != lastReported) {
            val byUser = cam == lastWritten && lastWrittenByUser
            lastReported = cam
            onCameraMove(cam, byUser)
        }
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

    // -------------------------------------------------------------------------------------------------------------
    // Drawing.

    private fun painter(): OverlayPainter = painter ?: OverlayPainter(density).also { painter = it }

    /** Draws the newest frame and the overlays over it. */
    fun draw(canvas: Canvas) {
        val t0 = System.nanoTime()
        val f = loop.latest
        val night = f?.input?.look?.night ?: false
        canvas.drawColor(f?.input?.look?.look?.theme?.earth ?: if (night) 0xFF1B2230.toInt() else DAY_EARTH)
        if (f == null) return
        drawer.drawGeometry(canvas, f.frame)
        val g = GroundView(f.camera)
        val p = painter()
        val ov = overlays
        p.layoutMarkers(g, ov.markers)
        p.drawGround(canvas, g, ov)
        drawer.drawLabels(canvas, f.frame)
        drawer.drawPois(canvas, f.frame, Looks.POI_SIZE_DP * density, 1.5f * density, p.markerBoxes, p.markerCount, if (night) NIGHT_RING else Color.WHITE)
        p.drawMarkers(canvas)
        ov.location?.let { p.drawLocation(canvas, g, it) }
        tape?.let { p.drawTape(canvas, g, it, units, Locale.getDefault()) }
        val t1 = System.nanoTime()
        drawMillis = drawMillis * 0.8 + (t1 - t0) / 1e6 * 0.2
        frameMillis = frameMillis * 0.8 + f.renderNanos / 1e6 * 0.2
        if (f.number != lastFrameNumber) {
            if (lastFrameAt > 0) {
                val dt = (t1 - lastFrameAt) / 1e9
                if (dt in 0.001..1.0) fps = fps * 0.8 + (1 / dt) * 0.2
            }
            lastFrameAt = t1
            lastFrameNumber = f.number
        }
        if (debug) drawDebug(canvas, f, p)
        loop.drawn(f.number)
    }

    private fun drawDebug(canvas: Canvas, f: DrawnFrame, p: OverlayPainter) {
        val s = f.frame.stats
        val c = f.camera
        val lines = arrayOf(
            "frame %.1f ms make · %.1f ms draw · %.0f fps%s".format(
                Locale.ROOT, frameMillis, drawMillis, fps, if (loop.failures > 0) " · ${loop.failures} failed" else "",
            ),
            "tiles %d wanted · %d drawn · %d loading".format(Locale.ROOT, f.wanted, f.drawnTiles, f.loading),
            "cache %d scenes · %.0f MB · %d built".format(Locale.ROOT, cache.size, cache.bytes / 1048576.0, pipeline.built),
            "vertices %,d · triangles %,d · batches %d".format(Locale.ROOT, s.vertices + p.routeVertices, s.triangles, s.batches),
            "labels %d · pois %d · markers %d/%d".format(Locale.ROOT, s.labels, s.pois, p.markerCount, overlays.markers.size),
            "z %.2f · bearing %.0f° · pitch %.0f°".format(Locale.ROOT, c.zoom, c.bearing, c.pitch),
        )
        debugText.textSize = 11f * density
        val lh = 14f * density
        val pad = 8f * density
        var w = 0f
        for (l in lines) w = max(w, debugText.measureText(l))
        val left = insets.left + pad
        val top = insets.top + pad
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
        val f = loop.latest
        val cam = f?.camera ?: CameraOps.camera(state.camera, v)
        val at = cam.unproject(x.toDouble(), y.toDouble()) ?: return
        val hit = f?.let { loop.hitTest(it.frame, x, y) }
        onTap(hit?.let { featureHit(it, at) } ?: MapHit.Empty(at))
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

    private fun featureHit(hit: Hit, at: LatLon): MapHit = when (hit) {
        is Hit.Poi -> {
            val st = PoiStyles.of(hit.poi)
            val tags = LinkedHashMap<String, String>()
            st.key?.let { tags[it] = st.value }
            hit.poi.name?.let { tags["name"] = it }
            MapHit.OnFeature(Place(hit.poi.osmId, hit.location, tags), hit.location)
        }
        is Hit.Building -> {
            val info = hit.info
            val tags = LinkedHashMap<String, String>()
            tags["building"] = info.kind.ifEmpty { "yes" }
            if (info.height > 0f) tags["height"] = "%.1f".format(Locale.ROOT, info.height)
            if (info.minHeight > 0f) tags["min_height"] = "%.1f".format(Locale.ROOT, info.minHeight)
            tags["roof:shape"] = info.roof.tag
            MapHit.OnFeature(Place(info.osmId, hit.location, tags, BBox.of(hit.footprint)), at)
        }
    }

    companion object {
        const val DAY_EARTH = 0xFFECE9E3.toInt()
        const val NIGHT_RING = 0xFF2A3140.toInt()
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
