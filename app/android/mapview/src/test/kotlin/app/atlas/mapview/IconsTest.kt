package app.atlas.mapview

import android.graphics.Bitmap
import android.graphics.Canvas
import app.atlas.geo.LatLon
import app.atlas.places.Kinds
import app.atlas.places.Symbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every kind's symbol draws on the map, a board drawing named by its board id ("market.deli") as an open set's
 * ("maki-bakery") does: the map had resolved only ids with a dash, so a deli's pin, a sauna's, a guest house's and some
 * 70 kinds' more stood blank (6 November).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class IconsTest {
    @Test
    fun everyKindsSymbolDrawsOnTheMap() {
        val blank = Kinds.lines().filter { Symbols.has(it.symbol) && Icons.shape(it.symbol) == null }
        assertEquals(emptyList<String>(), blank.map { "${it.kind} ${it.symbol}" })
        // a deli's pin has its symbol in white on its disc
        val density = 2f
        val deli = Marker("deli", LatLon(52.37, 4.88), "market.deli", 0xFF1E7A46)
        Icons.warm(listOf(deli.symbol))
        val b = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        MarkerPainter(density).draw(Canvas(b), deli, 50f, 50f, 0)
        val r = (7 * density).toInt()
        var white = 0
        for (y in 50 - r..50 + r) for (x in 50 - r..50 + r) {
            val p = b.getPixel(x, y)
            if ((p shr 16 and 0xFF) > 200 && (p shr 8 and 0xFF) > 200 && (p and 0xFF) > 200) white++
        }
        b.recycle()
        assertTrue("$white white pixels in the deli's disc", white > 20)
    }
}
