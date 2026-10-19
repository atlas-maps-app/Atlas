package app.atlas.mapview.vk

import app.atlas.mapcore.state.CameraWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The map's camera for the core (plan-vector T9, K11): the main thread's samples evaluated at the render thread's own
 * vsync (between the two, a frame beyond, never further), the rest flag once nothing moves, nothing written when
 * nothing changed, and no allocation on the render thread's side.
 */
class MapCameraSourceTest {
    private val out: ByteBuffer = ByteBuffer.allocateDirect(CameraWriter.SIZE).order(ByteOrder.LITTLE_ENDIAN)

    private fun samples(
        revision: Long,
        t0: Long,
        t1: Long = t0,
        lat0: Double = 52.37,
        lat1: Double = lat0,
        bearing0: Double = 10.0,
        bearing1: Double = bearing0,
        distance0: Double = 430.0,
        distance1: Double = distance0,
        rest: Boolean = true,
        draw: Boolean = true,
    ) = MapCameraSource.Samples(
        revision = revision, t0 = t0, lat0 = lat0, lon0 = 4.88, distance0 = distance0, bearing0 = bearing0, pitch0 = 52.0, principalY0 = 440.0,
        t1 = t1, lat1 = lat1, lon1 = 4.88, distance1 = distance1, bearing1 = bearing1, pitch1 = 52.0, principalY1 = 440.0,
        principalXDp = 206.0, widthPx = 1080, heightPx = 2400, widthDp = 412, heightDp = 916, density = 1080.0 / 412.0,
        rest = rest, follow = false, pack = 2L, profile = CameraWriter.PROFILE_REGION, draw = draw,
    )

    @Test
    fun aStillCameraIsWrittenOnceWithTheRestFlagThenNothing() {
        val s = MapCameraSource()
        assertFalse("nothing published yet", s.camera(1_000L, out))
        s.publish(samples(1, t0 = 1_000L))
        assertTrue(s.camera(2_000L, out))
        assertEquals(CameraWriter.REST, out.getInt(CameraWriter.FLAGS_AT) and CameraWriter.REST)
        assertEquals(52.37, out.getDouble(CameraWriter.LATITUDE_AT), 0.0)
        assertEquals(430.0, out.getDouble(CameraWriter.DISTANCE_AT), 0.0)
        assertEquals(412, out.getInt(CameraWriter.WIDTH_DP_AT))
        assertEquals(916, out.getInt(CameraWriter.HEIGHT_DP_AT))
        assertEquals(2L, out.getLong(CameraWriter.PACK_ID_AT))
        assertEquals(CameraWriter.PROFILE_REGION, out.getInt(CameraWriter.PROFILE_AT))
        assertFalse("the same camera again: no frame", s.camera(3_000L, out))
        s.chromeMoved()
        assertTrue("the chrome's shapes moved: one frame", s.camera(4_000L, out))
        assertFalse(s.camera(5_000L, out))
    }

    @Test
    fun anAnimationIsEvaluatedAtTheVsyncAFrameBeyondItsSamplesAndNoFurther() {
        val s = MapCameraSource()
        val frame = 16_000_000L
        s.publish(samples(1, t0 = 0L, t1 = frame, lat0 = 52.0, lat1 = 52.1, bearing0 = 350.0, bearing1 = 10.0, distance0 = 400.0, distance1 = 1600.0, rest = false))
        // At the first sample, the second, half way, and a frame beyond the second.
        assertTrue(s.camera(0L, out))
        assertEquals(52.0, out.getDouble(CameraWriter.LATITUDE_AT), 1e-12)
        assertEquals(0, out.getInt(CameraWriter.FLAGS_AT) and CameraWriter.REST)
        assertTrue(s.camera(frame / 2, out))
        assertEquals(52.05, out.getDouble(CameraWriter.LATITUDE_AT), 1e-9)
        // The bearing the shorter way round (350° to 10° through north), the distance evenly in its logarithm.
        assertEquals(0.0, out.getDouble(CameraWriter.BEARING_AT), 1e-6)
        assertEquals(800.0, out.getDouble(CameraWriter.DISTANCE_AT), 1e-6)
        assertTrue(s.camera(frame, out))
        assertEquals(52.1, out.getDouble(CameraWriter.LATITUDE_AT), 1e-9)
        assertTrue(s.camera(2 * frame, out))
        assertEquals(52.2, out.getDouble(CameraWriter.LATITUDE_AT), 1e-9)
        // A main thread that stopped publishing: the camera stays where a frame beyond took it, and one more frame
        // ends the motion, then nothing.
        assertTrue(s.camera(5 * frame, out))
        assertEquals(52.2, out.getDouble(CameraWriter.LATITUDE_AT), 1e-9)
        assertFalse(s.camera(6 * frame, out))
    }

    @Test
    fun theCoreDrawsNothingWhileTodaysMapCoversIt() {
        val s = MapCameraSource()
        s.publish(samples(1, t0 = 0L, draw = false))
        assertFalse(s.camera(1L, out))
        s.publish(samples(2, t0 = 2L, draw = true))
        assertTrue(s.camera(3L, out))
    }

    @Test
    fun theRenderThreadsSideAllocatesNothing() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return
        if (!bean.isThreadAllocatedMemorySupported) return
        bean.isThreadAllocatedMemoryEnabled = true
        var revision = 1L
        val frame = 16_666_667L
        val presented = MapCameraSource.Presented { _, _, _ -> }
        val s = MapCameraSource(presented)
        // Warm up (the JIT, the classes), then measure many vsyncs of an animation and of rest.
        val list = (0 until 64).map { samples(revision++, t0 = it * frame, t1 = (it + 1) * frame, lat0 = 52.0 + it * 1e-4, lat1 = 52.0 + (it + 1) * 1e-4, rest = false) }
        repeat(20_000) { i ->
            s.publish(list[i % list.size])
            s.camera(i * frame, out)
            s.presented(i.toLong(), i * frame, true)
        }
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        for (i in 0 until 100_000) {
            if (i % 2 == 0) s.publish(list[i % list.size]) // a reference swap: the main thread's samples, made already
            s.camera(i * frame, out)
            s.presented(i.toLong(), i * frame, i % 3 == 0)
        }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        println("MapCameraSourceTest: the render thread's upcalls allocated $allocated bytes over 100 000 vsyncs")
        // A few hundred bytes of the JVM's own bookkeeping at most; one boxed value a call would be megabytes.
        assertTrue("the render thread's upcalls allocated $allocated bytes over 100 000 vsyncs", allocated < 64 * 1024)
    }

    @Test
    fun turnsAreShortestAndBearingsNormalised() {
        assertEquals(20.0, MapCameraSource.shortestTurn(350.0, 10.0), 1e-12)
        assertEquals(-20.0, MapCameraSource.shortestTurn(10.0, 350.0), 1e-12)
        assertEquals(180.0, MapCameraSource.shortestTurn(0.0, 180.0), 1e-12)
        assertEquals(359.0, MapCameraSource.normalised(-1.0), 1e-12)
        assertEquals(0.0, MapCameraSource.normalised(360.0), 1e-12)
    }
}
