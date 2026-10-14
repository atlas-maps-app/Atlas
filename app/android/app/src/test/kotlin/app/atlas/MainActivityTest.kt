package app.atlas

import android.content.Intent
import android.net.Uri
import app.atlas.nav.CiScreens
import app.atlas.nav.Dest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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
        assertEquals(CiScreens.list(), file.readText())
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
        assertEquals(Dest.WhatsHere(52.37452, 4.88377), MainActivity.stackFor(geo)!!.last())
        val osm = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/way/99205257"))
        assertEquals(Dest.Place(osmId = "w99205257"), MainActivity.stackFor(osm)!!.last())
        val text = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "52.3745, 4.8838")
        assertEquals(Dest.WhatsHere(52.3745, 4.8838), MainActivity.stackFor(text)!!.last())
        assertNull(MainActivity.stackFor(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun ciListWhileRunningWritesTheListAndStays() {
        val start = Intent(Intent.ACTION_MAIN).putExtra(MainActivity.EXTRA_SCREEN, "home")
        val controller = Robolectric.buildActivity(MainActivity::class.java, start).create().start().resume()
        val activity = controller.get()
        val file = File(activity.filesDir, MainActivity.CI_SCREENS_FILE).apply { delete() }
        controller.newIntent(Intent(Intent.ACTION_MAIN).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_LIST))
        assertEquals(CiScreens.list(), file.readText())
        assertTrue(!activity.isFinishing)
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
