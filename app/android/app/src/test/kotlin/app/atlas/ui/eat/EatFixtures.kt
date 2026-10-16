package app.atlas.ui.eat

import android.content.Context
import app.atlas.Services
import app.atlas.ServicesConfig
import app.atlas.search.PlaceStore
import app.atlas.testing.FixtureTiles
import app.atlas.testing.Fixtures
import app.atlas.tiles.TileStack
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.file.Files
import java.time.Clock

/**
 * The eat screens' test data: the Overpass extract of every place to eat and drink within 1.4 km of the Westermarkt
 * (`data/eat/raw.json`, OpenStreetMap, ODbL), imported once per run into a temporary place store.
 */
object EatFixtures {
    /** Tests that open it must run under Robolectric, like the shell's Amsterdam store. */
    val store: PlaceStore by lazy {
        val file = File.createTempFile("eat", ".db").apply { delete(); deleteOnExit() }
        PlaceStore.open(file).also { it.importOverpass(File(Fixtures.repo, "data/eat/raw.json"), regionName = "Amsterdam") }
    }

    /** Services offline at the Westerkerk with the eat store on the phone, at [clock] (the shell's Tuesday evening). */
    fun services(
        clock: Clock = Fixtures.evening,
        places: Boolean = true,
        context: Context = RuntimeEnvironment.getApplication(),
    ): Services = Services(
        context,
        ServicesConfig(
            root = Files.createTempDirectory("atlas-eat").toFile(),
            demoLocation = Services.WESTERKERK,
            offline = true,
            tiles = TileStack(listOf(FixtureTiles())),
            placeStores = if (places) listOf(store) else emptyList(),
            clock = clock,
        ),
    )

    /** Services offline in a world with no tiles and no places at all: a phone that has downloaded nothing. */
    fun emptyWorld(clock: Clock = Fixtures.evening, context: Context = RuntimeEnvironment.getApplication()): Services = Services(
        context,
        ServicesConfig(
            root = Files.createTempDirectory("atlas-eat-empty").toFile(),
            demoLocation = Services.WESTERKERK,
            offline = true,
            tiles = TileStack(emptyList()),
            placeStores = emptyList(),
            clock = clock,
        ),
    )
}
