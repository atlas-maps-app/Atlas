package app.atlas.mapview.vk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import app.atlas.mapview.VkFactsLines
import org.junit.Test

/** VK bench's plan and arithmetic (plan-vector K1): the paths, the matrix, the statistics and the report lines. */
class VkBenchTest {
    @Test
    fun thePathsMoveTheCameraAsScripted() {
        val c = VkBench.Camera()
        VkBench.cameraAt(VkBench.Path.ORBIT, VkBench.View.BOARD, 7.5, 30.0, c)
        assertEquals(18.0 + 90.0, c.bearing, 1e-9)
        assertEquals(46.0, c.pitch, 0.0)
        VkBench.cameraAt(VkBench.Path.TILT, VkBench.View.BOARD, 15.0, 30.0, c)
        assertEquals(70.0, c.pitch, 1e-9) // half way: the steepest pitch
        VkBench.cameraAt(VkBench.Path.TILT, VkBench.View.BOARD, 0.0, 30.0, c)
        assertEquals(20.0, c.pitch, 1e-9)
        VkBench.cameraAt(VkBench.Path.PINCH, VkBench.View.BOARD, 15.0, 30.0, c)
        assertEquals(6000.0, c.distance, 1e-6)
        VkBench.cameraAt(VkBench.Path.PINCH, VkBench.View.BOARD, 0.0, 30.0, c)
        assertEquals(600.0, c.distance, 1e-9)
        VkBench.cameraAt(VkBench.Path.PAN, VkBench.View.BOARD, 7.5, 30.0, c)
        assertTrue(c.latitude != VkBench.ORIGIN_LATITUDE && c.longitude != VkBench.ORIGIN_LONGITUDE)
        VkBench.cameraAt(VkBench.Path.STILL, VkBench.View.STEEP, 0.0, 30.0, c)
        assertEquals(70.0, c.pitch, 0.0)
        assertEquals(2300.0, c.distance, 0.0)
    }

    @Test
    fun theMatrixHoldsWhatTheDeviceHas() {
        // lavapipe: C and 1x, both routes, not a tiler: C has resolve and direct in the render pass, resolve dynamic.
        val lavapipe = VkBench.matrix(setOf(2, 4), dynamic = true, tiler = false).map { it.name }
        assertEquals(
            listOf(
                "C renderpass onepass resolve", "C renderpass composite resolve", "C renderpass composite direct",
                "C dynamic onepass resolve", "C dynamic composite resolve",
                "1x renderpass onepass resolve", "1x renderpass composite direct", "1x dynamic onepass resolve", "1x dynamic composite direct",
            ),
            lavapipe,
        )
        // A Mali with lazily allocated memory and 2x, a render pass only: no direct target on a tiler.
        val mali = VkBench.matrix(setOf(0, 1, 2, 4), dynamic = false, tiler = true).map { it.name }
        assertEquals(8, mali.size)
        assertFalse(mali.any { it.contains("direct") && !it.startsWith("1x") })
        assertTrue(mali.first().startsWith("A "))
    }

    @Test
    fun theStatisticsAreTheUsualOnes() {
        val v = doubleArrayOf(5.0, 1.0, 3.0, 2.0, 4.0)
        assertEquals(3.0, VkBench.percentile(v, 5, 50.0), 0.0)
        assertEquals(5.0, VkBench.percentile(v, 5, 95.0), 0.0)
        assertEquals(1.0, VkBench.percentile(v, 5, 0.0), 0.0)
        assertTrue(VkBench.percentile(v, 0, 50.0).isNaN())
        assertEquals(2.0, VkBench.slope(doubleArrayOf(0.0, 1.0, 2.0), doubleArrayOf(1.0, 3.0, 5.0), 3), 1e-12)
        assertTrue(VkBench.slope(doubleArrayOf(1.0), doubleArrayOf(1.0), 1).isNaN())
    }

    @Test
    fun aSummaryCountsDropsAndFragments() {
        val presented = doubleArrayOf(16.7, 16.6, 33.4, 16.7, 50.0)
        val s = VkBench.summarise(
            presented, presented.size, "display-timing",
            doubleArrayOf(8.0, 9.0, 10.0), 3, doubleArrayOf(1.0, 2.0), doubleArrayOf(0.5, 0.6), doubleArrayOf(0.2, 0.3), 2,
            doubleArrayOf(), 0, 5L, 2, 1000L, 263, 677, 1000.0 / 60.0,
        )
        assertEquals(3, s.dropped) // one at 33 ms (one vsync missed), two at 50 ms
        assertEquals(16.7, s.presentedP50, 0.0)
        assertEquals(50.0, s.presentedMax, 0.0)
        assertEquals(2.5, s.fse, 1e-12) // 5k fragments over two frames of 1,000 pixels: 2.5 full screens a frame
        assertTrue(s.hwuiGpuP50.isNaN())
        val line = VkBench.line("board C renderpass composite direct", s, 120.5, 30.25)
        assertTrue(line, line.startsWith("vkbench board C renderpass composite direct: p50 16.70 p95 50.00 max 50.00 dropped 3 gpu 9.00"))
        assertTrue(line.contains("graphics 120.5 native 30.3"))
        assertTrue(line.contains("presented-by display-timing"))
    }

    @Test
    fun theDecisionsReadTheNumbers() {
        assertTrue(VkBench.onepassVerdict(6.5, 5.0).contains("ONEPASS is to be built"))
        assertTrue(VkBench.onepassVerdict(5.4, 5.0).contains("no ONEPASS"))
        assertTrue(VkBench.onepassVerdict(Double.NaN, 5.0).startsWith("unknown"))
        assertTrue(VkBench.onTime(1_000_000_000L, 1_004_000_000L, 16_666_667L))
        assertFalse(VkBench.onTime(1_000_000_000L, 1_016_000_000L, 16_666_667L))
        assertFalse(VkBench.onTime(1_000_000_000L, 0L, 16_666_667L))
        val soak = VkBench.soakLine(doubleArrayOf(0.0, 5.0, 7.5, 10.0), doubleArrayOf(0.3, 0.40, 0.41, 0.42), 4, 50.0, 300.0, false, 16.7)
        assertTrue(soak, soak.startsWith("vkbench soak: thermal slope 0.0040/min"))
        assertTrue(soak.contains("charge 50.0 mAh"))
        assertTrue(VkBench.soakLine(doubleArrayOf(0.0), doubleArrayOf(0.3), 1, 0.0, Double.NaN, true, 16.7).contains("plugged in"))
    }

    @Test
    fun theDeqpLevelReadsAsADate() {
        assertEquals("2024-03-01", VkFactsLines.dEqpDate((2024 shl 16) or (3 shl 8) or 1))
        assertEquals("2022-03-01", VkFactsLines.dEqpDate(0x07E60301))
    }

    @Test
    fun thePillsMoveWithinTheirStrip() {
        assertEquals(0f, pillShift(0.0), 1e-6f)
        assertEquals(40f, pillShift(0.5), 1e-4f)
        val boxes = pillBoxes(412f, 915f, 10f)
        assertEquals(2, boxes.size)
        assertEquals(26f, boxes[0][0], 0f)
        assertEquals(915f - 48f, boxes[1][3], 0f)
    }
}
