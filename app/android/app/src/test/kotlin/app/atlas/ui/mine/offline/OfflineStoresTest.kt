package app.atlas.ui.mine.offline

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.atlas.Services
import app.atlas.ServicesConfig
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.testing.Fixtures
import app.atlas.testing.TempFiles
import app.atlas.testing.settle
import app.atlas.testing.shotsDir
import app.atlas.ui.mine.MineFixtures
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Offline board over the app's own store sync (no test stores): a region whose places this Atlas cannot read says so
 * on its card and takes them again, and such a store of no region shows, to be deleted (`mine_offline_unreadable.png`,
 * and `mine_offline_unreadable_nl.png` on a narrow phone in Dutch, whose names are the longest).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class OfflineStoresTest {
    @get:Rule
    val compose = createComposeRule()

    private val queued = CopyOnWriteArrayList<String>()

    /** A phone whose Haarlem has the fixture's file of nothing for places, beside a store an older Atlas left of no region. */
    private fun phone(): Services {
        val s = Services(
            RuntimeEnvironment.getApplication(),
            ServicesConfig(root = TempFiles.dir("atlas-unreadable"), demoLocation = Services.WESTERKERK, offline = true, mapEngine = false, clock = Fixtures.evening),
        )
        MineFixtures.region(s, "Haarlem", MineFixtures.HAARLEM, mapMb = 120, placesMb = 24, at = s.clock.instant().minus(Duration.ofHours(2)))
        File(s.dirs.places, "lisboa.db").writeText("not a database, though it is named as one")
        s.mine.useScheduler(object : RegionScheduler {
            override fun schedule(id: String, wifiOnly: Boolean, update: Boolean) { queued += id }
            override fun cancel(id: String) {}
        })
        s.mine.useDeps(MineFixtures.offlineDeps(s, s.clock.instant()))
        s.mine.engine.refresh()
        return s
    }

    /** Shows the Offline board until both stores say they cannot be read ([chip] in the phone's language), and captures it. */
    private fun show(s: Services, chip: String, shot: String) {
        compose.setContent { AtlasRoot(s, BackStackNavigator(listOf(Dest.Home(), Dest.Offline)), dark = false) }
        compose.settle(30_000)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(chip).fetchSemanticsNodes().size == 2 }
        repeat(4) { compose.waitForIdle(); Thread.sleep(120) }
        val f = File(shotsDir, "$shot.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.length() > 1000)
    }

    @Test
    fun aStoreThisAtlasCannotReadIsShownAndTakenAgain() {
        val s = phone()
        show(s, "places unreadable", "mine_offline_unreadable")
        compose.onNodeWithText("Its places file is damaged", substring = true).assertExists()
        compose.onNodeWithText("lisboa").assertExists()

        // the card's button takes the places again: the file goes, the region waits for its download
        compose.onNodeWithText("Download again").performScrollTo().performClick()
        compose.waitUntil(10_000) { queued.toList() == listOf("haarlem") && compose.onAllNodesWithText("places unreadable").fetchSemanticsNodes().size == 1 }
        assertFalse(s.mine.files.places("haarlem").exists())

        // the store of no region is deleted from its own card
        compose.onAllNodesWithText("Delete").onLast().performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("lisboa").fetchSemanticsNodes().isEmpty() }
        assertFalse(File(s.dirs.places, "lisboa.db").exists())
        assertTrue(s.unreadableStores.value.isEmpty())
    }

    @Test
    @Config(qualifiers = "nl-w360dp-h800dp-xhdpi")
    fun inDutchOnANarrowPhoneTheCardsButtonsKeepTheirWords() {
        show(phone(), "plaatsen onleesbaar", "mine_offline_unreadable_nl")
        // each button as wide as its words: the second goes under the first when the two do not fit side by side
        val again = compose.onNodeWithText("Opnieuw downloaden").fetchSemanticsNode().boundsInRoot
        val delete = compose.onAllNodesWithText("Verwijderen").fetchSemanticsNodes().first().boundsInRoot
        val edge = compose.onRoot().fetchSemanticsNode().boundsInRoot.right - with(compose.density) { 16.dp.toPx() }
        assertTrue("$again $delete within $edge", again.right <= edge && delete.right <= edge && (delete.left >= again.right || delete.top >= again.bottom))
    }
}
