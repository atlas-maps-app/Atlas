package app.atlas.ui.search

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.atlas.places.OpeningHours
import app.atlas.places.OpeningHoursContext
import app.atlas.testing.shotsDir
import app.atlas.ui.kit.AtlasTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * A bar's week as its own page shows it, a row a calendar day as its bar (the reviewer, 17 November, finding 6): hours
 * that end in "+" give the times they state and a "+" where the open end begins, and a night's open end never fills the
 * next day. The week from a Monday, to `find_week_open_end*.png`; the shot is written before the words are checked, so
 * a run of older code leaves its picture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class OpenEndWeekTest {
    @get:Rule
    val compose = createComposeRule()

    private val monday = LocalDate.of(2026, 11, 16)
    private val amsterdam = OpeningHoursContext.forPlace("NL", ZoneId.of("Europe/Amsterdam"), 52.374, 4.884)

    private fun week(raw: String, shot: String) {
        compose.setContent { AtlasTheme(dark = false) { WeekTableDialog(OpeningHours.parse(raw), raw, monday, amsterdam, "NL") {} } }
        compose.waitForIdle()
        val f = File(shotsDir, "find_$shot.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 1000)
    }

    private fun shown(words: String): Int = compose.onAllNodes(hasText(words)).fetchSemanticsNodes().size

    /** Closed on Monday: Monday has only Sunday's night, to 2:00 and perhaps later; Tuesday opens at 16:00. */
    @Test
    fun aBarClosedOnMondayHasOnlySundaysNightOnMonday() {
        week("Tu-Su 16:00-02:00+", "week_open_end")
        assertEquals("Mon", 1, shown("0:00–2:00+"))
        assertEquals("Tue", 1, shown("16:00–24:00"))
        assertEquals("Wed to Sun", 5, shown("0:00–2:00+, 16:00–24:00"))
    }

    /** Till midnight and perhaps later: each day from midnight with no time stated, then 18:00 to 24:00; never around the clock. */
    @Test
    fun hoursToMidnightAndLaterAreNeverOpenAroundTheClock() {
        week("Mo-Su 18:00-24:00+", "week_open_end_midnight")
        assertEquals(7, shown("0:00+, 18:00–24:00"))
    }
}
