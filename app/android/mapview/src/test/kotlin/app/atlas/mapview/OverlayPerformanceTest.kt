package app.atlas.mapview

import android.graphics.Bitmap
import android.graphics.Canvas
import app.atlas.geo.LatLon
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * The overlays' cost per frame with a heavy load: a thousand markers and a 10,000-point route, laid out and drawn
 * on a software canvas (slower than a phone's GPU). Times are printed; the bounds only catch something pathological.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class OverlayPerformanceTest {
    @Test
    fun aThousandMarkersAndALongRoute() {
        val camera = CameraOps.camera(CameraPosition(Fixtures.westermarkt, 15.0, 30.0, 45.0), Viewport(824, 1830, 2f))
        val g = GroundView(camera)
        val styles = MarkerStyle.entries
        val markers = List(1000) { i ->
            val a = i * 2.399963
            val r = 0.012 * Math.sqrt(i / 1000.0)
            Marker("m$i", LatLon(52.3746 + r * sin(a), 4.8826 + r * 1.6 * cos(a)), "cafe", style = styles[i % styles.size], value = "4.$i", count = 5, priority = i % 7)
        }
        val route = RouteLine("r", List(10_000) { i ->
            val t = i / 10_000.0
            LatLon(52.36 + 0.03 * t + 0.002 * sin(t * 80), 4.86 + 0.05 * t + 0.002 * cos(t * 60))
        }, travelled = 2000.0, label = "12′")
        Icons.warm(listOf("cafe"))
        val overlays = MapOverlays(markers = markers, routes = listOf(route))
        val painter = OverlayPainter(2f)
        val canvas = Canvas(Bitmap.createBitmap(824, 1830, Bitmap.Config.ARGB_8888))
        val layout = LongArray(30)
        val draw = LongArray(30)
        for (k in layout.indices) {
            val t0 = System.nanoTime()
            painter.layoutMarkers(g, markers)
            val t1 = System.nanoTime()
            painter.drawGround(canvas, g, overlays)
            painter.drawMarkers(canvas)
            val t2 = System.nanoTime()
            layout[k] = t1 - t0
            draw[k] = t2 - t1
        }
        layout.sort(); draw.sort()
        println(
            "overlays: layout median %.2f ms, draw median %.2f ms; %d of %d markers placed, %d route vertices".format(
                layout[15] / 1e6, draw[15] / 1e6, painter.layout.placedCount, markers.size, painter.routeVertices,
            ),
        )
        assertTrue(painter.layout.placedCount in 20 until 1000)
        assertTrue(painter.routeVertices > 100)
        assertTrue(layout[15] < 50_000_000)
    }
}
