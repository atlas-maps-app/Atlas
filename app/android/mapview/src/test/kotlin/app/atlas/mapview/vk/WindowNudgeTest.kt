package app.atlas.mapview.vk

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import app.atlas.mapview.EngineFrames
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration

/**
 * [WindowNudge]: a window view with no window asks its Compose view for a layout pass each second, from each attach
 * until the window comes, and says so at 1 s and every 10 s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowNudgeTest {
    /** The Compose view's stand-in: a view beside the window view that counts the layout passes asked of it. */
    private class Counting(context: Context) : View(context) {
        var asked = 0

        override fun requestLayout() {
            asked++
            super.requestLayout()
        }
    }

    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val frame = FrameLayout(activity).also { activity.setContentView(it) }
    private val root = Counting(activity).also { frame.addView(it) }
    private val window = View(activity)
    private val nudge = WindowNudge(window, root, "surface")

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** The layout passes asked for in [ms] from now. */
    private fun askedIn(ms: Long): Int {
        val before = root.asked
        idle(ms)
        return root.asked - before
    }

    private fun lines() = ShadowLog.getLogsForTag(EngineFrames.TAG).map { it.msg }.filter { it.startsWith("vk window: no ") }

    @Test
    fun aViewWithNoWindowAsksEachSecondUntilItComes() {
        assertEquals("nothing before the view is attached", 0, askedIn(3_000))
        frame.addView(window)
        idle(0)
        assertEquals(3, askedIn(3_500))
        assertEquals(listOf("vk window: no surface window after 1 s; a layout pass asked for"), lines())
        assertEquals(7, askedIn(7_000))
        assertEquals(
            listOf(
                "vk window: no surface window after 1 s; a layout pass asked for",
                "vk window: no surface window after 10 s; a layout pass asked for",
            ),
            lines(),
        )
        nudge.came()
        assertEquals("the window came", 0, askedIn(5_000))
    }

    @Test
    fun eachAttachWithNoWindowAsksAgainAndADetachStops() {
        frame.addView(window)
        idle(0)
        assertEquals(2, askedIn(2_500))
        frame.removeView(window)
        assertEquals("detached", 0, askedIn(5_000))
        frame.addView(window)
        idle(0)
        assertEquals("attached again, the seconds from 0", 1, askedIn(1_500))
        nudge.came()
        frame.removeView(window)
        frame.addView(window)
        assertEquals("attached again with its window", 0, askedIn(3_000))
        nudge.went()
        frame.removeView(window)
        frame.addView(window)
        idle(0)
        assertEquals("its window went with the detach", 2, askedIn(2_500))
        assertEquals(
            listOf(
                "vk window: no surface window after 1 s; a layout pass asked for",
                "vk window: no surface window after 1 s; a layout pass asked for",
                "vk window: no surface window after 1 s; a layout pass asked for",
            ),
            lines(),
        )
    }
}
