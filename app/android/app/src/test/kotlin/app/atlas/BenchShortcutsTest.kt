package app.atlas

import android.content.Intent
import android.content.pm.ShortcutManager
import app.atlas.nav.Dest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** The debug and bench builds' launcher shortcuts, for reading a phone without adb: the Westerkerk timings, VK facts, VK bench and the GL facts. */
@RunWith(RobolectricTestRunner::class)
class BenchShortcutsTest {
    @Test
    fun timingsBuildsOfferTheTimingsVulkanAndGlShortcuts() {
        // Release builds (their unit tests run too) have neither the overlay nor the shortcuts.
        assumeTrue(BuildConfig.TIMINGS)
        val activity = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN)).create().get()
        val shortcuts = activity.getSystemService(ShortcutManager::class.java)
        val end = System.currentTimeMillis() + 10_000
        while (shortcuts.dynamicShortcuts.size < 4 && System.currentTimeMillis() < end) Thread.sleep(20)
        val byId = shortcuts.dynamicShortcuts.associateBy { it.id }
        assertEquals(setOf("bench-timings", "bench-glcaps", "bench-vkfacts", "bench-vkbench"), byId.keys)

        val timings = byId.getValue("bench-timings").intent!!
        assertEquals("home", timings.getStringExtra(MainActivity.EXTRA_SCREEN))
        assertTrue(timings.getBooleanExtra(MainActivity.EXTRA_BENCH, false))
        assertTrue(timings.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
        assertEquals(listOf<Dest>(Dest.Home()), MainActivity.stackFor(timings))

        val gl = byId.getValue("bench-glcaps").intent!!
        assertEquals(MainActivity.SCREEN_GLCAPS, gl.getStringExtra(MainActivity.EXTRA_SCREEN))
        assertFalse(gl.getBooleanExtra(MainActivity.EXTRA_BENCH, true))

        // VK facts and VK bench (plan-vector A0): the core's own screens, without the demo camera.
        val vkFacts = byId.getValue("bench-vkfacts").intent!!
        assertEquals(MainActivity.SCREEN_VKFACTS, vkFacts.getStringExtra(MainActivity.EXTRA_SCREEN))
        assertFalse(vkFacts.getBooleanExtra(MainActivity.EXTRA_BENCH, true))
        val vkBench = byId.getValue("bench-vkbench").intent!!
        assertEquals(MainActivity.SCREEN_VKBENCH, vkBench.getStringExtra(MainActivity.EXTRA_SCREEN))
        assertFalse(vkBench.getBooleanExtra(MainActivity.EXTRA_BENCH, true))
    }

    @Test
    fun theBenchShortcutOpensTheDemoCameraButKeepsThePhonesLocation() {
        val intent = Intent(Intent.ACTION_MAIN).putExtra(MainActivity.EXTRA_SCREEN, "home").putExtra(MainActivity.EXTRA_BENCH, true)
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).create().start().resume().get()
        assertTrue(activity.services.ciDemo)
        assertFalse(activity.services.location.isDemo)
    }
}
