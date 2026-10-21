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
import app.atlas.mapcore.state.ThemeBlock
import app.atlas.mapview.EngineFrames
import app.atlas.mapview.LabelPainter
import app.atlas.mapview.LabelSprites
import app.atlas.mapview.MapEngine
import app.atlas.mapview.MapLabel
import app.atlas.render.live.Moment
import java.time.ZoneId
import java.util.Locale

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
            core.painterOrder(MapEngine.painter)
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
        val (refs, open) = packs.refs()
        try {
            return core.packs(refs)
        } finally {
            for (r in open) runCatching { r.close() }
        }
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

    /**
     * The labels the core draws for this map from now on: [labels] as sprites drawn by [painter] in [face] (none when
     * empty), never smaller than [least] of their own size, unless a later hand-over ([revision], counted by the caller)
     * went already. Off the main thread. "" or why the core refused them.
     */
    fun labels(labels: List<MapLabel>, face: android.graphics.Typeface?, painter: LabelPainter, least: Float, revision: Long): String =
        synchronized(labelLock) {
            if (revision <= labelsRevision) return ""
            labelsRevision = revision
            if (face != null) painter.typeface = face
            val sprites = LabelSprites(painter)
            val block = LabelBlock.write(labels.map { label ->
                val s = sprites.sprite(label, labelIds.getOrPut(label.id) { labelIds.size + 1 })
                val points = DoubleArray(2 * s.points.size) { i -> if (i % 2 == 0) s.points[i / 2].lat else s.points[i / 2].lon }
                LabelBlock.Sprite(s.id, s.priority, s.half, s.pad, s.textHeight, s.baseline, s.width, s.height, s.anchorX, s.anchorY, points, s.pixels)
            })
            core.labels(block, revision, least)
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
     * The panel's line (§8): `vk p50 <ms> p95 <ms> gpu <ms> tiles <n> graphics <MB> native <MB>`, the presented
     * intervals over the last [WINDOW] frames of motion, the GPU's last time, the resident tiles, the core's graphics
     * memory and the native heap. Off the main thread: it asks the core.
     */
    fun panelLine(): String {
        val n = core.records(records)
        for (i in 0 until n) {
            val o = i * FrameRecords.SIZE
            val flags = records.getInt(o + FrameRecords.FLAGS_AT)
            val at = if (flags and FrameRecords.PRESENTED != 0) records.getLong(o + FrameRecords.PRESENTED_AT) else records.getLong(o + FrameRecords.END_AT)
            if (flags and FrameRecords.GPU != 0) lastGpuMs = records.getFloat(o + FrameRecords.GPU_MS_AT).toDouble()
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
        val stats = core.stats()
        val graphics = Regex("graphics ([0-9.]+) MB").find(stats)?.groupValues?.get(1) ?: "-"
        val tiles = Regex("vk tiles ([0-9]+) resident ([0-9.]+)").find(stats)?.let { "${it.groupValues[1]} (${it.groupValues[2]} MB)" } ?: "-"
        val native = Debug.getNativeHeapAllocatedSize() / 1e6
        return String.format(
            Locale.ROOT, "vk p50 %s p95 %s gpu %.1f tiles %s graphics %s native %.0f",
            percentile(50.0), percentile(95.0), lastGpuMs, tiles, graphics, native,
        )
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
    }
}
