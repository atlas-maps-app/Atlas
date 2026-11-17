package app.atlas.ui.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import app.atlas.geo.LatLon
import app.atlas.testing.shotsDir
import app.atlas.ui.local.astro.OrthoView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

/**
 * The Globe board's Earth as the app draws it, for the engine's globe check (ENGINE.md step 5, "The globe": the
 * engine's frame at the board's camera and instant against `drawEarth` rasterised by Robolectric).
 *
 * `drawEarth` is drawn over the Globe screen's night and its backdrop (`drawBackdrop`: the board's stars and its
 * Milky Way band, which the core draws behind its Earth too), in the board's 412 x 915 frame at one pixel a unit, at a
 * fixed instant and view, without the station and with "you" behind the Earth, and held to the picture `:mapcorecheck`
 * keeps (`likeness/globe/board.png`, with `board.txt` saying what it is of): the core's GlobeCheckTest draws the same
 * Earth at the same instant and compares its frame with that picture. A deliberate change to the board's Earth is
 * recorded with `ATLAS_GLOBE_RECORD=1` in the environment; the globe check then holds the core to the new picture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GlobeBoardEarthTest {
    @Test
    fun theBoardsEarthIsThePictureTheGlobeCheckKeeps() {
        val data = CosmosData.of(ApplicationProvider.getApplicationContext<Context>())
        val image = ImageBitmap(WIDTH, HEIGHT)
        val view = OrthoView(LON, TILT)
        // "you" on the far side of the Earth: not drawn
        val you = LatLon(-TILT, LON + 180.0 - 360.0)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(WIDTH.toFloat(), HEIGHT.toFloat())) {
            // GlobeScreen's night behind the pictures (its Night, and the glow about the frame's middle)
            drawRect(NIGHT)
            drawRect(Brush.radialGradient(0f to Color(0xFF0C1428), 0.7f to NIGHT, center = Offset(size.width * 0.5f, size.height * 0.45f), radius = max(size.width, size.height) * 0.75f))
            // the stars and the Milky Way's band behind everything, as GlobeScreen draws them
            drawBackdrop(data)
            drawEarth(data, EarthFrame(view, INSTANT, you, null, 0f))
        }
        val drawn = image.asAndroidBitmap()
        File(shotsDir, "globe_board_earth.png").let { f -> FileOutputStream(f).use { drawn.compress(Bitmap.CompressFormat.PNG, 100, it) } }

        val dir = File(System.getProperty("atlas.repo") ?: error("atlas.repo is not set"), "app/android/mapcorecheck/src/test/resources/likeness/globe")
        val kept = File(dir, "board.png")
        val about = File(dir, "board.txt")
        val text = "instant=$INSTANT\nlon=$LON\ntilt=$TILT\nwidth=$WIDTH\nheight=$HEIGHT\ncx=206\ncy=420\nradius=150\n"
        if (System.getenv("ATLAS_GLOBE_RECORD") == "1") {
            dir.mkdirs()
            FileOutputStream(kept).use { drawn.compress(Bitmap.CompressFormat.PNG, 100, it) }
            about.writeText(text)
            println("globe board: recorded $kept")
            return
        }
        assertTrue("the globe check's picture of the board's Earth is missing: $kept (record it with ATLAS_GLOBE_RECORD=1)", kept.isFile && about.isFile)
        assertEquals("what the kept picture is of", text, about.readText())
        val want = BitmapFactory.decodeFile(kept.path)
        assertEquals(WIDTH, want.width)
        assertEquals(HEIGHT, want.height)
        // the same picture: Skia's rasteriser may differ by a level at an edge from one platform to the next
        var sum = 0L
        var most = 0
        var far = 0
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val a = drawn.getPixel(x, y)
                val b = want.getPixel(x, y)
                var worst = 0
                for (shift in intArrayOf(16, 8, 0)) {
                    val d = abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
                    sum += d
                    worst = max(worst, d)
                }
                most = max(most, worst)
                if (worst > 8) far++
            }
        }
        val mean = sum / (3.0 * WIDTH * HEIGHT)
        println("globe board: mean difference %.4f levels from the kept picture, the most %d, %d pixels over 8".format(mean, most, far))
        assertTrue(
            "the board's Earth is no longer the picture the globe check keeps (mean $mean levels, $far pixels over 8; this run's: " +
                "${File(shotsDir, "globe_board_earth.png")}). A deliberate change is recorded with ATLAS_GLOBE_RECORD=1",
            mean <= 0.25 && far <= WIDTH * HEIGHT / 200,
        )
    }

    private companion object {
        const val WIDTH = 412
        const val HEIGHT = 915
        /** The view: the board's tilt, over Africa and Europe; an afternoon in November, the terminator across the disc. */
        const val LON = 10.0
        const val TILT = 20.0
        val INSTANT: Instant = Instant.parse("2026-11-11T17:00:00Z")
        val NIGHT = Color(0xFF05070D)
    }
}
