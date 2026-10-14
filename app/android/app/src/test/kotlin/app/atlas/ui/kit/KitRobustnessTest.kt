package app.atlas.ui.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** The kit takes what feature screens may hand it on a bad day (a 0/0 share, a missing reading) without crashing. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class KitRobustnessTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun instrumentsTakeNotANumber() {
        compose.setContent {
            AtlasTheme(dark = false) {
                Column {
                    // A share of nothing (0 / 0) and a battery that has not reported.
                    ProgressRing(Float.NaN)
                    BatteryGauge(Float.NaN, rangeKm = Double.NaN, contentDescription = "battery")
                    ElevationProfile(doubleArrayOf(1.0, Double.NaN, 3.0), progress = Float.NaN)
                    ProgressRing(Float.POSITIVE_INFINITY)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("battery").assertExists()
    }

    /** A frame clock that steps 16 ms a frame and notes where the sheet is after each. */
    private class Frames(private val state: SheetState) : MonotonicFrameClock {
        private var t = 0L
        val seen = ArrayList<Float>()

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            t += 16_000_000L
            return onFrame(t).also { seen += state.offset }
        }
    }

    @Test
    fun aFlingThatEndsAtTheTopDoesNotBounceTheSheet() = runBlocking {
        val s = SheetState(SheetStop.FULL)
        s.updateAnchors(mapOf(SheetStop.HIDDEN to 2000f, SheetStop.PEEK to 1800f, SheetStop.HALF to 1000f, SheetStop.FULL to 150f), 2000f)
        val frames = Frames(s)
        // A list inside the full sheet flung to its end hands the rest of the fling to the sheet, which is already there.
        withContext(frames) { s.settle(-4_000f) }
        assertEquals(SheetStop.FULL, s.stop)
        assertTrue("moved: ${frames.seen.take(10)}", frames.seen.all { abs(it - 150f) < 0.5f })
        // A fling down from the top still goes to the next rest.
        withContext(frames) { s.settle(4_000f) }
        assertEquals(SheetStop.HALF, s.stop)
        assertEquals(1000f, s.offset, 0.5f)
    }
}
