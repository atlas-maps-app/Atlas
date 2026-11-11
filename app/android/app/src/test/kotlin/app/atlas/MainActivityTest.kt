package app.atlas

import android.content.Intent
import android.net.Uri
import android.os.Looper
import app.atlas.nav.CiScreens
import app.atlas.nav.Dest
import app.atlas.nav.Tab
import app.atlas.services.SettingsKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/** The ways into the app: CI's extras, geo: and openstreetmap.org links, shared text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTest {
    @Test
    fun ciListWritesEveryScreenAndFinishes() {
        val intent = Intent(Intent.ACTION_MAIN).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_LIST)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create()
        val activity = controller.get()
        val file = File(activity.filesDir, MainActivity.CI_SCREENS_FILE)
        assertEquals(MainActivity.screenList(), file.readText())
        assertTrue(activity.isFinishing)
        for (name in file.readText().split(' ')) assertTrue(name, CiScreens.stackFor(name) != null)
    }

    @Test
    fun ciScreenOpensOnTopOfTheMap() {
        val i = Intent(Intent.ACTION_MAIN).putExtra(MainActivity.EXTRA_SCREEN, "place").putExtra(MainActivity.EXTRA_CI, true)
        assertEquals(listOf(Dest.Home(), CiScreens.screens["place"]), MainActivity.stackFor(i))
        assertEquals(listOf<Dest>(Dest.Home()), MainActivity.stackFor(Intent().putExtra(MainActivity.EXTRA_SCREEN, "home")))
        assertNull(MainActivity.stackFor(Intent().putExtra(MainActivity.EXTRA_SCREEN, "no-such-screen")))
    }

    @Test
    fun linksAndSharedText() {
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:52.37452,4.88377"))
        assertEquals(Dest.WhatsHere(52.37452, 4.88377, go = true), MainActivity.stackFor(geo)!!.last())
        val osm = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/way/99205257"))
        assertEquals(Dest.Place(osmId = "w99205257"), MainActivity.stackFor(osm)!!.last())
        val text = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "52.3745, 4.8838")
        assertEquals(Dest.WhatsHere(52.3745, 4.8838, go = true), MainActivity.stackFor(text)!!.last())
        assertNull(MainActivity.stackFor(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun ciListWhileRunningWritesTheListAndStays() {
        val start = Intent(Intent.ACTION_MAIN).putExtra(MainActivity.EXTRA_SCREEN, "home")
        val controller = Robolectric.buildActivity(MainActivity::class.java, start).create().start().resume()
        val activity = controller.get()
        val file = File(activity.filesDir, MainActivity.CI_SCREENS_FILE).apply { delete() }
        controller.newIntent(Intent(Intent.ACTION_MAIN).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_LIST))
        assertEquals(MainActivity.screenList(), file.readText())
        assertTrue(!activity.isFinishing)
    }

    @Test
    fun aLinkWhileRunningLandsOnTopOfTheScreensOpen() {
        val start = Intent(Intent.ACTION_MAIN).putExtra(MainActivity.EXTRA_SCREEN, "home")
        val controller = Robolectric.buildActivity(MainActivity::class.java, start).setup()
        val activity = controller.get()
        settle(activity) { true }
        val nav = activity.navigator!!
        nav.tab(Tab.MINE)
        nav.go(Dest.Settings())
        val was = settle(activity) { true }
        assertEquals(listOf(Dest.Home(), Dest.Mine, Dest.Settings()), was)

        // a point: its screen over the user's, which bring the point into the map; back returns to where the user was
        controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("geo:52.37452,4.88377")))
        assertEquals(was + Dest.WhatsHere(52.37452, 4.88377, go = true), settle(activity) { true })
        assertTrue(nav.back())
        assertEquals(was, settle(activity) { true })
        // the map under it pins nothing: the point leaves with the screen
        assertFalse(nav.pins(Dest.Home(52.37452, 4.88377, 17.0)))
        // an openstreetmap.org place and shared words land the same way
        controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/way/99205257")))
        assertEquals(was + Dest.Place(osmId = "w99205257"), settle(activity) { true })
        controller.newIntent(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Albert Heijn Westermarkt"))
        assertEquals(was + Dest.Place(osmId = "w99205257") + Dest.Search("Albert Heijn Westermarkt"), settle(activity) { true })
        assertTrue(nav.back())
        assertTrue(nav.back())

        // a map view is the Map home at its view over the user's screens, pinned until the map is moved away from it
        val view = Dest.Home(52.3745, 4.8838, 17.0)
        controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/#map=17/52.3745/4.8838")))
        assertEquals(was + view, settle(activity) { true })
        assertTrue(nav.pins(view))
        assertTrue(nav.back())
        assertEquals(was, settle(activity) { true })

        // CI's screens still replace the stack
        controller.newIntent(Intent(Intent.ACTION_MAIN).putExtra(MainActivity.EXTRA_SCREEN, "place"))
        assertEquals(listOf(Dest.Home(), CiScreens.screens["place"]), settle(activity) { true })
    }

    @Test
    fun aLinkOpensTheAppOnItsScreenOverTheMapAtItsPoint() {
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:52.37452,4.88377"))
        val point = Robolectric.buildActivity(MainActivity::class.java, geo).setup().get()
        val home = Dest.Home(52.37452, 4.88377, 17.0)
        assertEquals(listOf(home, Dest.WhatsHere(52.37452, 4.88377, go = true)), settle(point) { true })
        // the map under the link's screen starts at the point, but pins nothing: the point leaves with the screen
        assertFalse(point.navigator!!.pins(home))
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/#map=17/52.3745/4.8838"))
        val map = Robolectric.buildActivity(MainActivity::class.java, view).setup().get()
        assertEquals(listOf<Dest>(Dest.Home(52.3745, 4.8838, 17.0)), settle(map) { true })
        assertTrue(map.navigator!!.pins(Dest.Home(52.3745, 4.8838, 17.0)))
    }

    @Test
    fun theWelcomeOnceOverTheMap() {
        // the first plain launch: the welcome over the map, so that finishing it lands on the map
        val first = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN)).setup().get()
        assertEquals(listOf(Dest.Home(), Dest.Onboarding()), settle(first) { it.size > 1 })
        // shown is seen, though left unfinished: the next launch is the map's, and a link's never shows it
        val again = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN)).setup().get()
        assertEquals(listOf<Dest>(Dest.Home()), settle(again) { false })
        runBlocking { first.services.settings.set(SettingsKeys.ONBOARDED, false) }
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:52.37452,4.88377"))
        val link = Robolectric.buildActivity(MainActivity::class.java, geo).setup().get()
        assertEquals(Dest.WhatsHere(52.37452, 4.88377, go = true), settle(link) { false }.last())
    }

    /** [activity]'s screens once [done] says so, or after a second: the settings are read off the main thread. */
    private fun settle(activity: MainActivity, done: (List<Dest>) -> Boolean): List<Dest> {
        val end = System.currentTimeMillis() + 1_000
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            if (activity.navigator?.stack?.let(done) == true) break
            Thread.sleep(10)
        }
        return activity.navigator!!.stack.toList()
    }

    @Test
    fun ciModeStartsAtTheWesterkerk() {
        val intent = Intent(Intent.ACTION_MAIN).putExtra(MainActivity.EXTRA_SCREEN, "home").putExtra(MainActivity.EXTRA_CI, true)
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).create().start().resume().get()
        val services = activity.services
        assertTrue(services.location.isDemo)
        assertEquals(Services.WESTERKERK, services.location.last.value?.location)
    }
}
