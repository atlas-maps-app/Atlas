package app.atlas.mapview.vk

import app.atlas.mapcore.FrameSource
import app.atlas.mapcore.state.CameraWriter
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.ln

/**
 * The map's camera for the core (plan-vector T9, D4, D5.8; K11): at each paced vsync, on the core's render thread, the
 * gesture camera for that vsync written into the core's camera state, allocating nothing.
 *
 * The main thread publishes what it knows as one immutable [Samples] ([publish]): the camera at its own frame's time
 * and, while an animation runs (a fling, a fly-to, a zoom step, the follow glide), the same animation evaluated a frame
 * later. The render thread reads the newest and evaluates it at its own vsync: between the two, or up to one frame
 * beyond the second (an animation's camera is smooth over a frame), never further. So each frame shows the camera of
 * its own vsync, whichever of the two threads wakes first on the same vsync (the judder G2 measured with a camera
 * handed over a frame late, D18). A gesture publishes the camera of each event, with nothing beyond it.
 *
 * It answers false (nothing to draw, the core's frame loop rests) when nothing changed since the frame drawn last: no
 * new publication, no animation under way, the chrome's shapes still. The frame after the camera comes to rest carries
 * the rest flag, so the core snaps it (D4). The chrome's keep-out shapes ([chrome], K11b's ChromeShapes) are written
 * after the frame's size, as they read its density.
 */
internal class MapCameraSource(
    /** Told on the render thread when a frame was presented, complete or not; it must allocate nothing. */
    private val onPresented: Presented = Presented { _, _, _ -> },
    /** Told on the render thread, once, when the core turned the map off for the session, with why. */
    private val onOff: (String) -> Unit = {},
) : FrameSource {
    /** A frame presented: its number, whether its picture was complete, and the publication it drew (no boxing). */
    fun interface Presented {
        fun presented(frame: Long, complete: Boolean, revision: Long)
    }

    /** One publication of the main thread, swapped whole: the camera at [t0] and, moving, at [t1] (ns, CLOCK_MONOTONIC). */
    class Samples(
        val revision: Long,
        val t0: Long,
        val lat0: Double,
        val lon0: Double,
        val distance0: Double,
        val bearing0: Double,
        val pitch0: Double,
        val principalY0: Double,
        val t1: Long,
        val lat1: Double,
        val lon1: Double,
        val distance1: Double,
        val bearing1: Double,
        val pitch1: Double,
        val principalY1: Double,
        val principalXDp: Double,
        val widthPx: Int,
        val heightPx: Int,
        /** The frame in whole dp, rounded as today's map rounds them (MapController.liveView). */
        val widthDp: Int,
        val heightDp: Int,
        val density: Double,
        /** No gesture and no animation: the frame may snap. */
        val rest: Boolean,
        val follow: Boolean,
        /** The pack drawn (PackList's number) and its profile (CameraWriter.PROFILE_*), 0 for the world's own choice. */
        val pack: Long,
        val profile: Int,
        /** Whether the core draws at all now (the transitional rule hides it under today's map otherwise). */
        val draw: Boolean,
    ) {
        /** Whether it carries motion beyond [t0]. */
        val moving: Boolean get() = t1 > t0
    }

    @Volatile
    private var samples: Samples? = null

    /** K11b's shapes of the chrome over the map; null without them (CameraWriter.keepOuts(0)). */
    @Volatile
    var chrome: ChromeShapes? = null

    /** Bumped when the chrome's shapes moved ([chromeMoved]): the next vsync draws even with the camera still. */
    @Volatile
    private var chromeRevision = 0

    // The render thread's.
    private var sequence = 0L
    private var drawnRevision = -1L
    private var drawnChrome = -1
    private var drawnMoving = false
    private var lastPresentedRevision = -1L

    /** Publishes the main thread's newest camera (allocated there, read here without a lock). */
    fun publish(s: Samples) {
        samples = s
    }

    /** The chrome's shapes moved: the next frame writes them. */
    fun chromeMoved() {
        chromeRevision++
    }

    /** The newest publication (the main thread's own view of it). */
    val published: Samples? get() = samples

    override fun camera(vsyncNanos: Long, out: ByteBuffer): Boolean {
        val s = samples ?: return false
        if (!s.draw || s.widthPx <= 0 || s.heightPx <= 0 || s.density <= 0.0) return false
        // An animation's samples carry it one frame on, and are not read further than a frame past their second sample:
        // a main thread that stopped publishing (a cancelled animation) leaves the camera where it got to.
        val span = s.t1 - s.t0
        val moving = s.moving && vsyncNanos - s.t1 <= span + STALE_SLACK_NS
        val chromeNow = chromeRevision
        if (s.revision == drawnRevision && !moving && !drawnMoving && chromeNow == drawnChrome) return false
        val u = if (s.moving) ((vsyncNanos - s.t0).toDouble() / span.toDouble()).coerceIn(0.0, 2.0) else 0.0
        val lat = s.lat0 + (s.lat1 - s.lat0) * u
        val lon = s.lon0 + (s.lon1 - s.lon0) * u
        // The distance moves evenly in its logarithm (as the zoom does), the bearing the shorter way round.
        val distance = if (s.distance0 == s.distance1 || !s.moving) s.distance0 else exp(ln(s.distance0) + (ln(s.distance1) - ln(s.distance0)) * u)
        val turn = shortestTurn(s.bearing0, s.bearing1)
        val bearing = s.bearing0 + turn * u
        val pitch = s.pitch0 + (s.pitch1 - s.pitch0) * u
        val principalY = s.principalY0 + (s.principalY1 - s.principalY0) * u
        var flags = 0
        if (s.rest && !moving) flags = flags or CameraWriter.REST
        if (s.follow) flags = flags or CameraWriter.FOLLOW
        CameraWriter.header(out, ++sequence, flags)
        CameraWriter.view(out, lat, lon, normalised(bearing), pitch, distance, s.principalXDp, principalY)
        CameraWriter.frame(out, s.widthPx, s.heightPx, s.widthDp, s.heightDp, s.density)
        CameraWriter.pack(out, s.pack, s.profile)
        val shapes = chrome
        if (shapes != null) shapes.write(out, vsyncNanos) else CameraWriter.keepOuts(out, 0)
        drawnRevision = s.revision
        drawnChrome = chromeNow
        drawnMoving = moving
        return true
    }

    override fun presented(frame: Long, nanos: Long, complete: Boolean) {
        lastPresentedRevision = drawnRevision
        onPresented.presented(frame, complete, lastPresentedRevision)
    }

    override fun off(reason: String) = onOff(reason)

    companion object {
        /** How far past an animation's second sample the camera may still be carried, beyond one frame of it. */
        const val STALE_SLACK_NS = 4_000_000L

        /** The shorter signed turn from [from] to [to] degrees, in (-180, 180]. */
        fun shortestTurn(from: Double, to: Double): Double {
            var d = (to - from) % 360.0
            if (d > 180.0) d -= 360.0
            if (d <= -180.0) d += 360.0
            return d
        }

        /** [b] in [0, 360). */
        fun normalised(b: Double): Double {
            val r = b % 360.0
            return if (r < 0.0) r + 360.0 else r
        }
    }
}
