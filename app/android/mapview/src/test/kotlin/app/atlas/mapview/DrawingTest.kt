package app.atlas.mapview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import app.atlas.engine.Batch
import app.atlas.engine.LabelStyle
import app.atlas.engine.Look
import app.atlas.engine.Renderer
import app.atlas.engine.SceneBuilder
import app.atlas.engine.TileCover
import app.atlas.engine.TileScene
import app.atlas.geo.LatLon
import app.atlas.geo.TileId
import app.atlas.tiles.TileStack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/** Real pixels through Robolectric's native graphics: icons, fonts, engine frames and route ribbons on a Bitmap. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DrawingTest {
    private fun bitmap(w: Int = 824, h: Int = 1830) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    private fun save(b: Bitmap, name: String): File {
        val f = File(Fixtures.shots, "$name.png")
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    private fun near(a: Int, b: Int, tolerance: Int = 12) =
        abs(Color.red(a) - Color.red(b)) <= tolerance && abs(Color.green(a) - Color.green(b)) <= tolerance && abs(Color.blue(a) - Color.blue(b)) <= tolerance

    @Test
    fun symbolsResolveAndBecomePaths() {
        assertEquals("maki-restaurant", Icons.resolveId("restaurant"))
        assertEquals("pinhead-charging_station", Icons.resolveId("charging_station"))
        assertEquals("ui-walk", Icons.resolveId("walk"))
        assertEquals("maki-cafe", Icons.resolveId("maki-cafe"))
        assertNull(Icons.resolveId("no-such-symbol-anywhere"))
        // The boards' own café cup stands in for Maki's: drawn in strokes.
        val cafe = Icons.shape("cafe")!!
        assertEquals("place.cafe", cafe.icon.id)
        val bounds = RectF()
        (cafe.fills + cafe.strokes).first().computeBounds(bounds, true)
        assertTrue(bounds.width() > 5f && bounds.height() > 5f)
        // Drawn white on black, it marks pixels inside its box only.
        val b = bitmap(64, 64)
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        cafe.draw(c, 32f, 32f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        var lit = 0
        for (y in 0 until 64) for (x in 0 until 64) if (Color.red(b.getPixel(x, y)) > 128) {
            lit++
            assertTrue(x in 11..53 && y in 11..53)
        }
        assertTrue("lit $lit", lit > 150)
        // Stroked UI symbols draw too.
        assertTrue(Icons.shape("ui-walk")!!.strokes.isNotEmpty())
    }

    @Test
    fun brandsHaveMarks() {
        assertNull(Icons.loadedBrand("albertheijn"))
        val ah = Icons.brand("albertheijn")
        assertNotNull(Icons.loadedBrand("albertheijn"))
        assertNotNull(ah)
        assertNotNull(ah!!.shape)
        val byName = Icons.brand("Albert Heijn")
        assertNotNull(byName)
        assertNull(Icons.brand("no brand of this name, surely"))
    }

    @Test
    fun theMeasurerMeasuresRealFonts() {
        val m = AndroidTextMeasurer()
        val serif = m.width("Jordaan", LabelStyle.SERIF_ITALIC, 30f)
        val sans = m.width("Jordaan", LabelStyle.SANS, 30f)
        assertTrue(serif > 60f && sans > 60f)
        assertTrue(m.width("Westerkerk", LabelStyle.SANS, 30f) > m.width("Kerk", LabelStyle.SANS, 30f))
        val adv = FloatArray(10)
        m.advances("Prinsengr.", LabelStyle.SANS_MEDIUM, 24f, adv)
        assertEquals(m.width("Prinsengr.", LabelStyle.SANS_MEDIUM, 24f), adv.sum(), 1.5f)
        assertEquals(0f, m.width("", LabelStyle.SANS, 24f), 0f)
    }

    @Test
    fun pinNamesStayOnScreen() {
        val painter = MarkerPainter(2f).apply { viewWidth = 824f }
        val m = Marker("o", LatLon(52.37, 4.88), "restaurant", label = "Osteria da Fortunata")
        val box = FloatArray(4)
        // Near the right edge the name goes left of the pin, whole.
        painter.labelBox(m, 790f, 600f, box, 0)
        assertTrue("box ${box.toList()}", box[2] <= 790f && box[0] >= 0f)
        // Elsewhere it stays on the right, as the boards show.
        painter.labelBox(m, 200f, 600f, box, 0)
        assertTrue("box ${box.toList()}", box[0] > 200f && box[2] <= 824f)
        // Drawn where its box says: ink left of the pin at the edge, none to its right.
        val b = bitmap(824, 200)
        val c = Canvas(b)
        c.drawColor(Color.MAGENTA)
        painter.draw(c, m, 790f, 150f, 0)
        painter.labelBox(m, 790f, 150f, box, 0)
        var ink = 0
        for (y in box[1].toInt() until box[3].toInt()) for (x in box[0].toInt() until box[2].toInt()) {
            if (Color.red(b.getPixel(x, y)) < 60 && Color.blue(b.getPixel(x, y)) < 60) ink++
        }
        assertTrue("ink $ink", ink > 50)
    }

    @Test
    fun anEngineFrameDrawsWithItsShadowLayerAndLabels() {
        val stack = TileStack(FixtureTiles())
        val camera = CameraOps.camera(CameraPosition(Fixtures.westermarkt, 16.3, -20.0, 50.0), Viewport(824, 1830, 2f))
        val cover = TileCover.cover(camera)
        val scenes = HashMap<TileId, TileScene>()
        runBlocking {
            for (id in cover) {
                val hit = stack.bestTile(id) ?: continue
                scenes[id] = SceneBuilder.build(hit.decode(), id, Look.DAY, null, hit.source).also { PoiStyles.warm(it.pois) }
            }
        }
        val renderer = Renderer(AndroidTextMeasurer())
        val frame = renderer.frame(camera, TileCover.plan(cover) { scenes[it] }, 0L, Looks.frameStyle(MapStyle(), camera.zoom))
        assertTrue(frame.batches.any { (it as Batch.Triangles).layer != 0 })
        assertTrue(frame.labels.isNotEmpty())
        val b = bitmap()
        val c = Canvas(b)
        c.drawColor(Color.MAGENTA)
        val drawer = FrameDrawer()
        drawer.drawGeometry(c, frame)
        drawer.drawLabels(c, frame)
        drawer.drawPois(c, frame, 40f, 3f, FloatArray(0), 0)
        assertEquals(frame.stats.vertices, drawer.lastVertices)
        save(b, "drawing_engine_frame")
        // The triangles cover the whole view: no background left.
        var magenta = 0
        for (y in 0 until b.height step 7) for (x in 0 until b.width step 7) if (b.getPixel(x, y) == Color.MAGENTA) magenta++
        assertTrue("$magenta background samples", magenta < 20)
        // Water (the canals) shows in its theme colour somewhere.
        val water = Look.DAY.theme.water
        var wet = 0
        for (y in 0 until b.height step 5) for (x in 0 until b.width step 5) if (near(b.getPixel(x, y), water, 30)) wet++
        assertTrue("water samples $wet", wet > 50)
    }

    @Test
    fun routesLieOnTheGroundWithACasingAndAFadedPast() {
        val camera = CameraOps.camera(CameraPosition(LatLon(52.3746, 4.8826), 16.0), Viewport(824, 1830, 2f))
        val g = GroundView(camera)
        // A straight route north to south through the target; the first half travelled.
        val a = camera.unproject(412.0, 200.0)!!
        val z = camera.unproject(412.0, 1630.0)!!
        val route = RouteLine("r", listOf(a, z), colour = 0xFF2E5BFF, widthDp = 8f, travelled = app.atlas.geo.Geo.distance(a, z) / 2)
        val painter = OverlayPainter(2f)
        val b = bitmap()
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        painter.drawGround(c, g, MapOverlays(routes = listOf(route)))
        save(b, "drawing_route")
        val blue = 0xFF2E5BFF.toInt()
        // Unfaded below the middle, faded above, white casing at the edge, nothing beside it.
        assertTrue(near(b.getPixel(412, 1200), blue))
        val past = b.getPixel(412, 500)
        assertTrue(!near(past, blue) && Color.blue(past) > 150)
        assertTrue(near(b.getPixel(412 + 8 + 1, 1200), Color.WHITE, 40))
        assertEquals(Color.BLACK, b.getPixel(412 + 30, 1200))
        assertTrue(painter.routeVertices > 0)
    }

    @Test
    fun aRouteBehindTheCameraIsCutAtTheNearPlane() {
        val camera = CameraOps.camera(CameraPosition(LatLon(52.3746, 4.8826), 17.0, 0.0, 70.0), Viewport(824, 1830, 2f))
        val g = GroundView(camera)
        // From far behind the camera to far ahead of it.
        val route = RouteLine("r", listOf(LatLon(52.30, 4.8826), LatLon(52.3746, 4.8826), LatLon(52.40, 4.8826)), dashed = true)
        val painter = OverlayPainter(2f)
        val b = bitmap()
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        painter.drawGround(c, g, MapOverlays(routes = listOf(route), areas = listOf(AreaOverlay("a", LatLon(52.3746, 4.8826), 150.0))))
        save(b, "drawing_route_pitched")
        var lit = 0
        for (y in 0 until b.height step 4) if (b.getPixel(412, y) != Color.BLACK) lit++
        assertTrue("lit $lit", lit > 100)
    }
}
