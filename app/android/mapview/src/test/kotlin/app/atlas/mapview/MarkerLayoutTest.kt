package app.atlas.mapview

import app.atlas.geo.LatLon
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Marker collisions by priority, folding into clusters, and hit tests with touch-sized targets. */
class MarkerLayoutTest {
    private val metrics = MarkerMetrics(2f)
    private val layout = MarkerLayout()
    private val at = LatLon(52.37, 4.88)

    private fun run(markers: List<Marker>, xs: FloatArray, ys: FloatArray, visible: BooleanArray = BooleanArray(markers.size) { true }) {
        layout.layout(markers, xs, ys, visible, metrics, 824f, 1830f) { 40f }
    }

    private fun placed(): List<String> = (0 until layout.placedCount).map { layout.placed[it] }.map { it.toString() }

    @Test
    fun apartTheyAllShow() {
        val m = listOf(Marker("a", at, "cafe"), Marker("b", at, "bar"), Marker("c", at, "pub"))
        run(m, floatArrayOf(100f, 400f, 700f), floatArrayOf(300f, 300f, 300f))
        assertEquals(3, layout.placedCount)
    }

    @Test
    fun theHigherPriorityWinsAndIsDrawnLast() {
        val m = listOf(Marker("low", at, "cafe", priority = 1), Marker("high", at, "bar", priority = 5), Marker("mid", at, "pub", priority = 3))
        run(m, floatArrayOf(400f, 410f, 700f), floatArrayOf(500f, 505f, 500f))
        assertEquals(2, layout.placedCount)
        // Draw order: lowest first, so "high" ends on top.
        assertEquals(listOf("2", "1"), placed())
    }

    @Test
    fun theSelectedOneWinsWhateverItsPriority() {
        val m = listOf(Marker("a", at, "cafe", priority = 100), Marker("b", at, "bar", priority = -5, selected = true))
        run(m, floatArrayOf(400f, 405f), floatArrayOf(500f, 500f))
        assertEquals(listOf("1"), placed())
    }

    @Test
    fun dotsFoldIntoAClusterWithTheirCount() {
        val m = listOf(
            Marker("a", at, "cafe", style = MarkerStyle.DOT, priority = 3),
            Marker("b", at, "cafe", style = MarkerStyle.DOT),
            Marker("c", at, "cafe", style = MarkerStyle.CLUSTER, count = 4),
            Marker("d", at, "cafe", style = MarkerStyle.PIN, priority = 2),
            Marker("e", at, "cafe", style = MarkerStyle.DOT),
        )
        val xs = floatArrayOf(400f, 405f, 410f, 700f, 702f)
        val ys = floatArrayOf(500f, 500f, 502f, 900f, 880f)
        run(m, xs, ys)
        assertEquals(2, layout.placedCount)
        val counts = (0 until layout.placedCount).associate { layout.placed[it] to layout.clusterCount[it] }
        // "a" won and took "b" (1) and the cluster "c" (4): a bubble of six.
        assertEquals(6, counts[0])
        // "d" is a pin: it hides "e" and stays a pin.
        assertEquals(0, counts[3])
    }

    @Test
    fun hiddenOnesTakeNoRoom() {
        val m = listOf(Marker("a", at, "cafe", priority = 9), Marker("b", at, "bar"))
        run(m, floatArrayOf(400f, 402f), floatArrayOf(500f, 500f), booleanArrayOf(false, true))
        assertEquals(listOf("1"), placed())
    }

    @Test
    fun boxesFollowTheStyles() {
        val out = FloatArray(4)
        MarkerLayout.boxOf(Marker("p", at, "cafe"), 100f, 200f, metrics, { 0f }, out)
        // A pin sits on its place, as the Main board's: a 30 px disc centred on the anchor.
        assertArrayEquals(floatArrayOf(70f, 170f, 130f, 230f), out, 0.01f)
        // The chosen pin is the board's reservation pin: a 44 px disc on a 10 px stem over a 4 px shadow.
        MarkerLayout.boxOf(Marker("s", at, "cafe", selected = true), 100f, 200f, metrics, { 0f }, out)
        assertArrayEquals(floatArrayOf(56f, 200f - 4f - 20f - 88f, 144f, 204f), out, 0.01f)
        MarkerLayout.boxOf(Marker("d", at, "cafe", style = MarkerStyle.DOT), 100f, 200f, metrics, { 0f }, out)
        assertArrayEquals(floatArrayOf(80f, 180f, 120f, 220f), out, 0.01f)
        MarkerLayout.boxOf(Marker("c", at, "zap", style = MarkerStyle.CHIP, value = "150"), 100f, 200f, metrics, { 30f }, out)
        assertEquals(metrics.chipWidth(false, 30f), out[2] - out[0], 0.01f)
        // the charging point's 32 px pill, centred on its place
        assertEquals(232f, out[3], 0f)
        MarkerLayout.boxOf(Marker("v", at, "cafe", value = "4′"), 100f, 200f, metrics, { 30f }, out)
        assertTrue("the value chip hangs below the anchor", out[3] > 200f)
    }

    @Test
    fun hitsFindTheTopMostWithATouchSizedTarget() {
        val m = listOf(Marker("a", at, "cafe", style = MarkerStyle.DOT), Marker("b", at, "bar", style = MarkerStyle.DOT))
        run(m, floatArrayOf(200f, 600f), floatArrayOf(500f, 500f))
        assertEquals(0, layout.hit(200f, 500f, metrics))
        // A 20 dp dot still takes a tap 21 dp from its centre (44 dp target).
        assertEquals(1, layout.hit(600f + 42f, 500f, metrics))
        assertEquals(-1, layout.hit(400f, 500f, metrics))
    }

    @Test
    fun countLabelsStayShort() {
        assertEquals("7", MarkerPainter.countLabel(7))
        assertEquals("1.2k", MarkerPainter.countLabel(1234))
        assertEquals("15k", MarkerPainter.countLabel(15_400))
        // Never rounded up past what is there: 9,999 is not "10.0k".
        assertEquals("9.9k", MarkerPainter.countLabel(9_999))
        assertEquals("1k", MarkerPainter.countLabel(1_000))
        assertEquals("999k", MarkerPainter.countLabel(5_000_000))
    }
}
