package app.atlas.mapview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.atlas.engine.Camera
import app.atlas.engine.TileCover
import app.atlas.geo.Sun
import app.atlas.geo.UnitSystem
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The map alone at the Main board's camera ([HomeView]), 824 × 1830 px at density 2, on the fixture tiles with the
 * view's real trees and the board's sunny moment; then compared with the board's own map picture when it is present
 * (on the owner's machine): side by side, blended, in 8 × 16 crop pairs and as mean colours per kind of surface. The
 * results land in `build/outputs/roborazzi/board/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class BoardTest {
    @get:Rule
    val compose = createComposeRule()

    private val out: File get() = File(Fixtures.shots, "board").also { it.mkdirs() }

    @Test
    fun theFixtureHoldsEveryTileTheHomeCameraNeeds() {
        val c = CameraOps.camera(HomeView.camera, Viewport(824, 1830, 2f, offsetY = -35.0))
        val cover = TileCover.cover(c, 0, 18)
        val data = cover.map { if (it.z > 14) it.ancestor(14) else it }.toSortedSet(compareBy({ it.z }, { it.x }, { it.y }))
        val missing = data.filter { !File(Fixtures.testdata, "omt/${it.z}/${it.x}/${it.y}.pbf").isFile }
        File(out, "cover.txt").writeText(data.joinToString("\n") { "${it.z}/${it.x}/${it.y}" } + "\n")
        assertTrue("missing fixture tiles: $missing", missing.isEmpty())
    }

    @Test
    fun theHomeCameraIsTheBoards() {
        val c: Camera = CameraOps.camera(HomeView.camera, Viewport(824, 1830, 2f, offsetY = -35.0))
        // The board's eye stands 2300 m from the target, which sits 440 dp down the screen.
        assertTrue(kotlin.math.abs(c.distance - 2300.0) < 1.0)
        val p = c.project(HomeView.TARGET)!!
        assertTrue(kotlin.math.abs(p.y - 880f) < 0.5f && kotlin.math.abs(p.x - 412f) < 0.5f)
        // The board's sunny moment: the sun in the south-west, a quarter of the way up.
        val sun = Sun.position(BoardData.sunny, HomeView.TARGET.lat, HomeView.TARGET.lon)
        assertTrue(kotlin.math.abs(sun.azimuthDeg - 222.5) < 1.0 && kotlin.math.abs(sun.altitudeDeg - 25.9) < 1.0)
    }

    @Test
    fun renderAndCompare() {
        val state = MapState(HomeView.camera).apply { time = BoardData.sunny }
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                AtlasMap(
                    state, FixtureTiles(), Modifier.fillMaxSize(), HomeView.style, MapOverlays(measure = false), HomeView.padding(),
                    units = UnitSystem.METRIC, extras = { BoardData.trees },
                )
            }
        }
        compose.settleMap(state)
        val ours = File(out, "ours.png")
        compose.onRoot().captureRoboImage(ours.absolutePath)
        assertTrue(ours.isFile && ours.length() > 100_000)
        assumeTrue("the board's map picture is on the owner's machine only", BoardData.reference.isFile)
        val board = BoardCompare.read(BoardData.reference)
        val mine = BoardCompare.read(ours)
        BoardCompare.sideBySide(board, mine, File(out, "side_by_side.png"))
        BoardCompare.blend(board, mine, File(out, "blend.png"))
        BoardCompare.pairs(board, mine, out)
        val report = BoardCompare.regions(board, mine) + "\nmean |luma| difference %.1f\n\n".format(BoardCompare.meanAbsDiff(board, mine)) +
            BoardCompare.cells(board, mine)
        File(out, "regions.txt").writeText(report)
        println(report.lineSequence().take(16).joinToString("\n"))
    }
}
