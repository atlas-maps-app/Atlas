package app.atlas.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.atlas.places.BoardSymbols
import app.atlas.places.OpenSpan
import app.atlas.places.OpenState
import app.atlas.testing.shotsDir
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalTime
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * The kit's components against the boards' own elements, drawn by the board runtime from the generated boards'
 * numbers (`El`, `Tx`, `Sy`): the Design board's components (back button, status chip, filter chips, place row, main
 * action, bottom sheet), the Brands board's segmented control and the Place board's opening-hours dial. Each pair is
 * written to `kit_board/<name>.png`, the board left and the kit right, and their mean colour difference must stay
 * small.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class KitBoardTest {
    private val out = File(shotsDir, "kit_board").also { it.mkdirs() }

    private fun frame(w: Float, h: Float, bg: Long, content: @Composable BoxScope.() -> Unit): @Composable () -> Unit = {
        Box(Modifier.requiredSize(w.dp, h.dp).background(Color(bg.toInt())), content = content)
    }

    /** Renders [board] and [kit] in frames of the same size, writes the pair and returns the mean |Δ| of R, G and B. */
    private fun pair(name: String, w: Float, h: Float, bg: Long, board: @Composable BoxScope.() -> Unit, kit: @Composable BoxScope.() -> Unit): Double {
        val a = File(out, "${name}_board.png")
        val b = File(out, "${name}_kit.png")
        captureRoboImage(a.absolutePath, content = frame(w, h, bg, board))
        captureRoboImage(b.absolutePath, content = frame(w, h, bg, kit))
        val ia = ImageIO.read(a)
        val ib = ImageIO.read(b)
        val iw = minOf(ia.width, ib.width)
        val ih = minOf(ia.height, ib.height)
        val side = BufferedImage(iw * 2 + 8, ih, BufferedImage.TYPE_INT_RGB)
        var sum = 0L
        for (y in 0 until ih) for (x in 0 until iw) {
            val p = ia.getRGB(x, y)
            val q = ib.getRGB(x, y)
            side.setRGB(x, y, p)
            side.setRGB(iw + 8 + x, y, q)
            sum += abs(((p shr 16) and 255) - ((q shr 16) and 255)) + abs(((p shr 8) and 255) - ((q shr 8) and 255)) + abs((p and 255) - (q and 255))
        }
        for (y in 0 until ih) for (x in iw until iw + 8) side.setRGB(x, y, 0xFF00FF)
        ImageIO.write(side, "png", File(out, "$name.png"))
        a.delete()
        b.delete()
        val mean = sum / (3.0 * iw * ih)
        println(String.format(Locale.ROOT, "kit_board %-10s mean |Δ| %.2f", name, mean))
        return mean
    }

    @Test
    fun theDesignBoardsComponents() {
        val mean = pair(
            "design", 412f, 420f, 0xFFFFFFFF,
            board = {
                // Design.dc.html's "Components used somewhere", each moved by a whole offset onto a 412 px frame.
                El(12f, 12f, 44f, 44f, bg = 0xFFFFFFFF, r = CssCorners(22f, 22f, 22f, 22f), shadows = listOf(CssShadow(0f, 0f, 0f, 1f, 0xFFE6E1D6)))
                Sy("ui.back", 24f, 24f, 20f, 20f, tint = 0xFF14161B)
                El(70f, 17f, 62.89f, 34f, bg = 0xFFE1F2EA, r = CssCorners(17f, 17f, 17f, 17f))
                Tx("status", 82f, 26.25f, 38.89f, 15.5f, "Geist, system-ui, sans-serif", 12f, weight = 800, colour = 0xFF12663A, lineHeight = 15.5f)
                El(12f, 70f, 83.36f, 34f, bg = 0xFF14161B, r = CssCorners(17f, 17f, 17f, 17f))
                Tx("Open now", 24f, 79.25f, 59.36f, 15.5f, "Geist, system-ui, sans-serif", 12f, weight = 800, colour = 0xFFFFFFFF, lineHeight = 15.5f)
                El(101.36f, 70f, 50.61f, 34f, bg = 0xFFFFFFFF, r = CssCorners(17f, 17f, 17f, 17f))
                Tx("Free", 113.36f, 79.25f, 26.61f, 15.5f, "Geist, system-ui, sans-serif", 12f, weight = 800, lineHeight = 15.5f)
                El(157.97f, 70f, 82.32f, 34f, bg = 0xFFFFFFFF, r = CssCorners(17f, 17f, 17f, 17f))
                Tx("Step-free", 169.97f, 79.25f, 58.32f, 15.5f, "Geist, system-ui, sans-serif", 12f, weight = 800, lineHeight = 15.5f)
                El(12f, 118f, 388f, 64f, bg = 0xFFFFFFFF, r = CssCorners(18f, 18f, 18f, 18f), shadows = listOf(CssShadow(0f, 0f, 0f, 1f, 0xFFE6E1D6)))
                El(24f, 129f, 42f, 42f, bg = 0xFFB8461A, r = CssCorners(12f, 12f, 12f, 12f))
                Sy("place.cafe.2", 34f, 139f, 22f, 22f, tint = 0xFFFFFFFF)
                Tx("Place name", 78f, 131.5f, 86.67f, 19.5f, "Geist, system-ui, sans-serif", 15f, weight = 800, lineHeight = 19.5f)
                Tx("what matters · open until 22:00", 78f, 153f, 171.88f, 15.5f, "Geist, system-ui, sans-serif", 12f, colour = 0xFF5A5F6C, lineHeight = 15.5f)
                Tx("4′", 373.48f, 140.25f, 14.52f, 19.5f, "Geist, system-ui, sans-serif", 15f, weight = 900, lineHeight = 19.5f)
                El(12f, 196f, 388f, 56f, bg = 0xFF14161B, r = CssCorners(28f, 28f, 28f, 28f))
                Tx("Main action", 162.3f, 214.25f, 87.41f, 19.5f, "Geist, system-ui, sans-serif", 15f, weight = 800, colour = 0xFFFFFFFF, lineHeight = 19.5f)
                El(0f, 282f, 412f, 138f, bg = 0xFFFFFFFF, r = CssCorners(30f, 30f, 0f, 0f), shadows = listOf(CssShadow(0f, -8f, 24f, 0f, 0x2414161B)))
                El(186f, 292f, 40f, 5f, bg = 0xFFD5D8DE, r = CssCorners(3f, 3f, 3f, 3f))
            },
            kit = {
                BackButton({}, Modifier.offset(12.dp, 12.dp))
                Chip("status", Modifier.offset(70.dp, 17.dp), background = Color(0xFFE1F2EA), textColour = Color(0xFF12663A))
                Row(Modifier.offset(12.dp, 70.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToggleChip("Open now", true, {})
                    ToggleChip("Free", false, {})
                    ToggleChip("Step-free", false, {})
                }
                PlaceRow(
                    "Place name", Modifier.offset(12.dp, 118.dp).requiredWidth(388.dp), symbol = "place.cafe.2", colour = Color(0xFFB8461A),
                    meta = "what matters · open until 22:00", trailing = "4′",
                )
                PrimaryButton("Main action", {}, Modifier.offset(12.dp, 196.dp).requiredWidth(388.dp))
                Box(Modifier.offset(0.dp, 282.dp).size(412.dp, 138.dp)) {
                    Sheet(rememberSheetState(SheetStop.PEEK, setOf(SheetStop.PEEK)), peekHeight = 138.dp, fullTopGap = 0.dp) {}
                }
            },
        )
        assertTrue("the kit's components are the Design board's: mean |Δ| $mean", mean < 4.0)
    }

    @Test
    fun theSegmentedPillIsTheBrandsBoards() {
        val mean = pair(
            "segmented", 412f, 76f, 0xFFF2F1EC,
            board = {
                El(16f, 10f, 380f, 56f, bg = 0xFFE4E2DA, r = CssCorners(24f, 24f, 24f, 24f))
                El(20f, 14f, 160.41f, 48f, bg = 0xFF14161B, r = CssCorners(20f, 20f, 20f, 20f))
                Tx("Chains 84", 77.76f, 29f, 66.89f, 18f, "Geist, sans-serif", 14f, weight = 600, colour = 0xFFFFFFFF, lineHeight = 18f, tnum = true)
                Sy("ui.link", 55.76f, 30f, 16f, 16f, tint = 0xFFFFFFFF)
                Tx("Independent 212", 242.18f, 29f, 114.05f, 18f, "Geist, sans-serif", 14f, weight = 600, lineHeight = 18f, tnum = true)
                Sy("fact.independent", 220.18f, 30f, 16f, 16f, tint = 0xFF14161B)
            },
            kit = {
                SegmentedPill(
                    listOf(Segment("Chains", "ui.link", 84), Segment("Independent", "fact.independent", 212)), 0, {},
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                )
            },
        )
        assertTrue("the segmented pill is the Brands board's: mean |Δ| $mean", mean < 3.0)
    }

    @Test
    fun theClockDialIsThePlaceBoards() {
        // The board's dial is one of its drawings (board_art.json), on the owner's machine only.
        assumeTrue("the boards' drawings are on the owner's machine only", BoardSymbols.has("art.225e6931932c"))
        val spans = listOf(OpenSpan(12 * 60, 15 * 60, OpenState.OPEN), OpenSpan(17 * 60 + 30, 23 * 60, OpenState.OPEN))
        val mean = pair(
            "dial", 148f, 148f, 0xFFFBFAF7,
            board = { Sy("art.225e6931932c", 20f, 20f, 108f, 108f, tint = 0xFF14161B) },
            kit = { ClockDial(spans, LocalTime.of(19, 2), Modifier.offset(20.dp, 20.dp)) },
        )
        assertTrue("the clock dial is the Place board's: mean |Δ| $mean", mean < 3.0)
    }
}
