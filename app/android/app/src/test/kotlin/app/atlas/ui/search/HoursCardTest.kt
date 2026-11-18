package app.atlas.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.atlas.search.DaySpread
import app.atlas.search.HoursAnswer
import app.atlas.testing.shotsDir
import app.atlas.ui.kit.AtlasTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.LocalDateTime

/**
 * The hours card where the places state no closing time (`18:00+`; the app maintainer, 18 November 2026, batch 4 B10):
 * where every place was so, it said "None of them say their hours", and a day so among days that close lost its bar,
 * the axis ending before it. Such a day shows from when most open there, with no end. Each look to `hours_<name>.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class HoursCardTest {
    @get:Rule
    val compose = createComposeRule()

    private fun h(hours: Int) = hours * 60

    /** A Wednesday at noon. */
    private val now = LocalDateTime.of(2026, 11, 18, 12, 0)

    private fun noEnd(d: DayOfWeek) = DaySpread(d, 2, h(18), null, h(18), null)

    private fun show(days: List<DaySpread>, name: String) {
        compose.setContent {
            AtlasTheme(dark = false) {
                Box(Modifier.width(412.dp).padding(16.dp)) { HoursCard(HoursAnswer(days, List(24) { 0 }, 2, 0, emptyList()), now) }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(shotsDir, "hours_$name.png").absolutePath)
    }

    /** How many of the line's pixels are the open green, full or faded, as its bar draws it and nothing else of it does. */
    private fun greenIn(line: String): Int {
        val px = compose.onNodeWithContentDescription(line).captureToImage().toPixelMap()
        var n = 0
        for (y in 0 until px.height) for (x in 0 until px.width) {
            val c = px[x, y]
            if (c.green - c.red > 0.1f && c.green - c.blue > 0.06f) n++
        }
        return n
    }

    /** Bars that all open at 18:00 and state no end: the week reads 18:00+ with its bar, on an axis past the opening. */
    @Test
    fun aWeekOfOpenEndsShowsFromWhenMostOpen() {
        show(DayOfWeek.entries.map(::noEnd), "open_ends")
        compose.onAllNodesWithText("None of them say their hours").assertCountEquals(0)
        assertTrue(greenIn("Mon–Sun 18:00+") > 40)
        compose.onAllNodesWithText("18:00").assertCountEquals(1)
        compose.onAllNodesWithText("21:00").assertCountEquals(1)
    }

    /** Weekdays that close, a weekend of open ends: the weekend's line keeps a bar, the axis reaches past its opening. */
    @Test
    fun aDayOfOpenEndsAmongStatedOnesKeepsItsBar() {
        show(DayOfWeek.entries.map { d ->
            when (d) {
                FRIDAY, SATURDAY -> noEnd(d)
                SUNDAY -> DaySpread(d, 0, null, null, null, null)
                else -> DaySpread(d, 2, h(9), h(17), h(9), h(17))
            }
        }, "open_ends_weekend")
        assertTrue(greenIn("Mon–Thu 9:00–17:00") > 40)
        assertTrue(greenIn("Fri–Sat 18:00+") > 40)
        assertTrue(greenIn("Sun closed") == 0)
        compose.onAllNodesWithText("18:00").assertCountEquals(1)
    }
}
