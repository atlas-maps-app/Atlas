package app.atlas.mapview.vk

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.atlas.mapcore.CameraState
import app.atlas.mapcore.FrameRecords
import app.atlas.mapcore.MapCore
import app.atlas.mapcore.state.LabelBlock
import app.atlas.mapcore.state.MarkerBlock
import app.atlas.mapcore.state.ThemeBlock
import app.atlas.mapview.AskedBuilding
import app.atlas.mapview.EngineFrames
import app.atlas.mapview.LabelPainter
import app.atlas.mapview.LabelSprites
import app.atlas.mapview.MapEngine
import app.atlas.mapview.MapLabel
import app.atlas.mapview.MapOverlays
import app.atlas.mapview.MapPick
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerPainter
import app.atlas.mapview.MarkerSprites
import app.atlas.mapview.OverlayPainter
import app.atlas.mapview.RouteChips
import app.atlas.model.OsmType
import app.atlas.render.live.Moment
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * One map's side of the core (plan-vector K11): the camera it hands the core at each vsync ([source], written into
 * [camera]'s buffer), the packs it hands over ([bind], [handPacks]), the moment ([moment]), the transitional rule's
 * state ([transition]), what the core said of the frames it presented (complete or not), the first frame's line, and
 * the debug panel's numbers ([panelLine]). The map (`MapController`) makes one while `MapEngine.core` is ready and
 * draws today's map over the core as [transition] says.
 *
 * One map draws through the core at a time (the core has one window): the one bound last ([bind]); a map that goes
 * turns the core's map off only if it is still that one ([close]).
 */
internal class CoreMap(
    val core: MapCore,
    val packs: PackList,
    /** The core's window is a TextureView (the debug switch `atlas.vk.host texture`), else a SurfaceView. */
    val texture: Boolean,
    /** Run on the main thread when what the map draws changed: a presented frame's completeness, the first picture. */
    private val onChange: Runnable,
) {
    val camera = CameraState()
    val transition = Transition { Log.i(EngineFrames.TAG, it) }

    /** Whether the last frame the core presented was complete. */
    @Volatile
    var complete = false
        private set

    /** The newest publication of the camera drawn into a complete frame (-1 for none). */
    @Volatile
    var completeRevision = -1L
        private set

    /** When the core presented its first complete frame (SystemClock.uptimeMillis), -1 until it has. */
    @Volatile
    var firstCompleteAt = -1L
        private set

    /** Whether the moment handed over is a night one (the paper over the core at the start takes the night's). */
    @Volatile
    var night = false
        private set

    private val main = Handler(Looper.getMainLooper())

    /** Why the core turned the map off for the session (the device lost three times in a row), or null while it is on. */
    @Volatile
    var offReason: String? = null
        private set

    /** The render thread's upcalls: the camera at each vsync, and each frame presented (allocation-free). */
    val source = MapCameraSource(
        { _, done, revision ->
            val was = complete
            complete = done
            if (done) {
                completeRevision = revision
                if (firstCompleteAt < 0) firstCompleteAt = SystemClock.uptimeMillis()
            }
            if (done != was) main.post(onChange)
        },
        onOff = { reason ->
            offReason = reason
            main.post(onChange)
        },
    )

    private val theme = ThemeBlock.allocate()
    private var lastMoment: Moment? = null
    private var lastZone: ZoneId? = null
    private var packsRevision = -1
    private val records = FrameRecords.allocate(RECORDS)
    private val intervals = DoubleArray(WINDOW)
    private var intervalCount = 0
    private var intervalNext = 0
    private var lastPresentedNs = 0L
    private var lastGpuMs = 0.0
    private var lastEndNs = 0L
    private var lastStats = ""
    private var statsCall: Future<String>? = null

    /** This map has left the screen ([close]; under [switch]). */
    private var gone = false

    /**
     * Hands the core this map's packs, then turns its map on (off the main thread: the packs' manifests are read and
     * the core opens each); this map draws through the core from now on. "" or why the core cannot draw the map.
     */
    fun bind(): String {
        val said = handPacks()
        // The owner and the core's switch change together: a map going ([close]) never turns off a map just bound, and
        // a map gone before its bind came this far never turns the core's map on.
        val on = synchronized(switch) {
            if (gone) return ""
            owner = this
            core.screenEffects(MapEngine.haze, MapEngine.vignette, MapEngine.grain)
            core.setPacing(MapEngine.paced)
            core.map(true, texture)
        }
        val packs = said.lines().filter { it.isNotBlank() }.joinToString(", ")
        Log.i(EngineFrames.TAG, "vk map on (${if (texture) "texture" else "surface"}): ${packs.ifEmpty { "no packs" }}" + (if (on.isEmpty()) "" else "; $on"))
        return on
    }

    /** Hands the core the packs as listed now, if they changed since the last time (off the main thread). */
    fun handPacks(): String {
        packs.load()
        if (packs.revision == packsRevision) return ""
        packsRevision = packs.revision
        return core.packs(packs.refs())
    }

    /**
     * The moment the core lights the map by (CoreMoment's block for [moment] in the pack's [zone]), handed over when it
     * changed (off the main thread). "" or why it was not.
     */
    fun moment(moment: Moment, zone: ZoneId, daylight: Boolean): String {
        if (moment == lastMoment && zone == lastZone) return ""
        val why = runCatching {
            CoreMoment.write(theme, moment, zone, daylight)
            core.moment(theme)
        }.getOrElse { "the moment could not be made: ${it.message ?: it}" }
        if (why.isEmpty()) {
            lastMoment = moment
            lastZone = zone
            night = theme.getInt(ThemeBlock.FLAGS_AT) and ThemeBlock.NIGHT != 0
        } else {
            Log.w(EngineFrames.TAG, "vk moment: $why")
        }
        return why
    }

    /** The ids the core knows this map's labels by: stable while the map lives (a label keeps its place by its id). */
    private val labelIds = HashMap<String, Int>()
    private val labelLock = Any()
    private var labelsRevision = 0L

    /** The sprites' maker for the painter of the last hand-over, which keeps the labels it drew (under [labelLock]). */
    private var sprites: LabelSprites? = null

    /**
     * The labels the core draws for this map from now on: [labels] as sprites drawn by [painter] in [face] (none when
     * empty), at their own size and at the band's top, never smaller than [least] of their own size, unless a later
     * hand-over ([revision], counted by the caller) went already. Off the main thread. "" or why the core refused them.
     */
    fun labels(labels: List<MapLabel>, face: android.graphics.Typeface?, painter: LabelPainter, least: Float, revision: Long): String =
        synchronized(labelLock) {
            if (revision <= labelsRevision) return ""
            labelsRevision = revision
            if (face != null) painter.typeface = face
            val maker = sprites?.takeIf { it.painter === painter } ?: LabelSprites(painter).also { sprites = it }
            val block = LabelBlock.write(labels.map { label ->
                val s = maker.sprite(label, labelIds.getOrPut(label.id) { labelIds.size + 1 })
                val points = DoubleArray(2 * s.points.size) { i -> if (i % 2 == 0) s.points[i / 2].lat else s.points[i / 2].lon }
                LabelBlock.Sprite(s.id, s.priority, s.half, s.pad, s.textHeight, s.baseline, s.own, s.large, points)
            })
            core.labels(block, revision, least)
        }

    private val markerLock = Any()
    private var markersRevision = 0L

    /** The sprites' maker for the painter of the last hand-over, which keeps the looks it drew (under [markerLock]). */
    private var markerSprites: MarkerSprites? = null

    /**
     * The markers the core draws for this map from now on: [markers] as sprites drawn by [painter] (none when empty),
     * unless a later hand-over ([revision], counted by the caller) went already. Off the main thread. "" or why the
     * core refused them.
     */
    fun markers(markers: List<Marker>, painter: MarkerPainter, revision: Long): String =
        synchronized(markerLock) {
            if (revision <= markersRevision) return ""
            markersRevision = revision
            val maker = markerSprites?.takeIf { it.painter === painter } ?: MarkerSprites(painter).also { markerSprites = it }
            val block = if (markers.isEmpty()) MarkerBlock.write(painter.density, emptyList(), emptyList(), emptyList()) else maker.block(markers)
            core.markers(block, revision)
        }

    private val routeLock = Any()
    private var routesRevision = 0L

    /** The chips' maker for the painter of the last hand-over, which keeps the chips it drew (under [routeLock]). */
    private var routeChips: RouteChips? = null

    /**
     * The routes and areas the core draws for this map from now on: those of [overlays] (none when it has none), their
     * chips drawn by [painter], unless a later hand-over ([revision], counted by the caller) went already. Off the main
     * thread. "" or why the core refused them.
     */
    fun routes(overlays: MapOverlays, painter: OverlayPainter, revision: Long): String =
        synchronized(routeLock) {
            if (revision <= routesRevision) return ""
            routesRevision = revision
            val maker = routeChips?.takeIf { it.painter === painter } ?: RouteChips(painter).also { routeChips = it }
            core.routes(maker.block(overlays), revision)
        }

    /**
     * The building under the view's device point ([x], [y]) as the core's last frame drew it, or null (none there, or no
     * map). Off the main thread: it waits for the render thread.
     */
    fun pick(x: Float, y: Float): MapPick? = MapPick.parse(core.pick(x, y))

    private val askLock = Any()
    private var askedRevision = 0L

    /**
     * The building the core draws as asked from now on (none: no question open), unless a later hand-over ([revision],
     * counted by the caller) went already. Off the main thread.
     */
    fun ask(asked: AskedBuilding?, revision: Long) {
        synchronized(askLock) {
            if (revision <= askedRevision) return
            askedRevision = revision
            if (asked == null) {
                core.ask(false, 0L, 0, false)
            } else {
                core.ask(asked.id.type == OsmType.RELATION, asked.id.id, asked.wall, asked.hatched)
            }
        }
    }

    /** This map leaves the screen: the core's map goes off if this map was the one drawing (off the main thread). */
    fun close() {
        synchronized(switch) {
            gone = true
            if (owner !== this) return
            owner = null
            core.map(false)
        }
    }

    /**
     * The panel's lines (§8), one string with a line each:
     * - `vk p50 <ms> p95 <ms> gpu <ms> tiles <n> graphics <MB> native <MB>`: the presented intervals over the last
     *   [WINDOW] frames of motion, the GPU's last time, the resident tiles, the core's graphics memory, the native heap;
     * - `frames <n> last <s> ago worst <ms> gpu <ms> offscreen <ms> skipped <n>`: the frames drawn since the last call
     *   (about a second), how long ago the last one ended, the slowest's time on the render thread and on the GPU (the
     *   offscreen passes, which hold the decide stages, apart), and those the swapchain skipped;
     * - the core's health ([MapCore.health]): how long the frame drawn now has run, the failed frames, the devices lost,
     *   the calls with nothing to draw and why, then the last failure's error.
     *
     * Off the main thread. The records and the health never wait for the render thread; the stats do, so while a frame
     * is stuck the first line's tiles and graphics stay as they last were (after [STATS_WAIT_MS]) and the others go on.
     */
    fun panelLine(): String {
        val n = core.records(records)
        var worstMs = 0.0
        var worstGpuMs = 0.0
        var worstOffscreenMs = 0.0
        var skipped = 0
        for (i in 0 until n) {
            val o = i * FrameRecords.SIZE
            val flags = records.getInt(o + FrameRecords.FLAGS_AT)
            val end = records.getLong(o + FrameRecords.END_AT)
            val at = if (flags and FrameRecords.PRESENTED != 0) records.getLong(o + FrameRecords.PRESENTED_AT) else end
            worstMs = maxOf(worstMs, (end - records.getLong(o + FrameRecords.START_AT)) / 1e6)
            lastEndNs = end
            if (flags and FrameRecords.SKIPPED != 0) skipped++
            if (flags and FrameRecords.GPU != 0) {
                lastGpuMs = records.getFloat(o + FrameRecords.GPU_MS_AT).toDouble()
                worstGpuMs = maxOf(worstGpuMs, lastGpuMs)
                worstOffscreenMs = maxOf(worstOffscreenMs, records.getFloat(o + FrameRecords.GPU_OFFSCREEN_MS_AT).toDouble())
            }
            if (lastPresentedNs != 0L) {
                val ms = (at - lastPresentedNs) / 1e6
                // A gap longer than a few frames is the map at rest between two motions, not an interval.
                if (ms in 0.0..GAP_MS) {
                    intervals[intervalNext] = ms
                    intervalNext = (intervalNext + 1) % WINDOW
                    if (intervalCount < WINDOW) intervalCount++
                }
            }
            lastPresentedNs = at
        }
        val stats = statsSoon()
        val graphics = Regex("graphics ([0-9.]+) MB").find(stats)?.groupValues?.get(1) ?: "-"
        // the resident tiles, their heaps' MB of the budget, and those waiting for room (a full heap)
        val tiles = Regex("vk tiles ([0-9]+) resident ([0-9.]+)(?: of ([0-9]+))?(?: build [0-9.]+ waiting ([0-9]+))?").find(stats)?.let {
            val (n, mb, budget, waiting) = it.destructured
            "$n ($mb${if (budget.isNotEmpty()) "/$budget" else ""} MB${if (waiting.isNotEmpty() && waiting != "0") ", $waiting waiting" else ""})"
        } ?: "-"
        val native = Debug.getNativeHeapAllocatedSize() / 1e6
        val first = String.format(
            Locale.ROOT, "vk p50 %s p95 %s gpu %.1f tiles %s graphics %s native %.0f",
            percentile(50.0), percentile(95.0), lastGpuMs, tiles, graphics, native,
        )
        val ago = if (lastEndNs == 0L) "-" else String.format(Locale.ROOT, "%.1f s", (System.nanoTime() - lastEndNs) / 1e9)
        val frames = String.format(
            Locale.ROOT, "frames %d last %s ago worst %.0f ms gpu %.1f offscreen %.1f skipped %d",
            n, ago, worstMs, worstGpuMs, worstOffscreenMs, skipped,
        )
        return (listOf(first, frames) + core.health().lines()).joinToString("\n") { it.take(PANEL_CHARS) }
    }

    /**
     * The core's stats for [panelLine], or the last ones where the render thread does not answer within
     * [STATS_WAIT_MS] (a stuck frame): one call at a time, so such a frame holds the panel's one thread alone.
     */
    private fun statsSoon(): String {
        val call = statsCall?.takeUnless { it.isDone } ?: statsRunner.submit<String> { core.stats() }.also { statsCall = it }
        return try {
            call.get(STATS_WAIT_MS, TimeUnit.MILLISECONDS).also { lastStats = it }
        } catch (_: TimeoutException) {
            lastStats
        }
    }

    private fun percentile(p: Double): String {
        if (intervalCount == 0) return "-"
        val sorted = intervals.copyOf(intervalCount).also { it.sort() }
        val rank = kotlin.math.ceil(p / 100.0 * intervalCount).toInt().coerceIn(1, intervalCount)
        return String.format(Locale.ROOT, "%.1f", sorted[rank - 1])
    }

    companion object {
        /** The map drawing through the core now (under [switch]). */
        private var owner: CoreMap? = null

        /** Held while the owner and the core's map switch change. */
        private val switch = Any()

        private const val RECORDS = 256
        private const val WINDOW = 240
        private const val GAP_MS = 100.0

        /** How long the panel waits for the core's stats, which run on the render thread (statsSoon). */
        private const val STATS_WAIT_MS = 200L

        /** The panel's widest line: the rest of a long error is cut. */
        private const val PANEL_CHARS = 100

        /** The thread that asks for the stats, one call at a time (statsSoon). */
        private val statsRunner = Executors.newSingleThreadExecutor { Thread(it, "atlas-panel-stats").apply { isDaemon = true } }
    }
}
