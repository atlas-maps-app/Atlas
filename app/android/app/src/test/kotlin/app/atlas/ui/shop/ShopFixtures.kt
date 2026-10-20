package app.atlas.ui.shop

import android.content.Context
import app.atlas.Services
import app.atlas.ServicesConfig
import app.atlas.search.PlaceStore
import app.atlas.testing.Fixtures
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.file.Files
import java.time.Clock

/**
 * The shop screens' test data: an Overpass extract of every shop, errand, water point, toilet and bin within 1.6 km of
 * the Westerkerk (`shop/westerkerk_shops.json`, OpenStreetMap, ODbL, fetched 15 October 2026), imported once per run into
 * a temporary place store.
 */
object ShopFixtures {
    /** Tests that open it must run under Robolectric, like the shell's Amsterdam store. */
    val store: PlaceStore by lazy {
        val file = File.createTempFile("shop", ".db").apply { delete(); deleteOnExit() }
        val json = File.createTempFile("shop", ".json").apply { deleteOnExit() }
        ShopFixtures::class.java.getResourceAsStream("/shop/westerkerk_shops.json")!!.use { input -> json.outputStream().use { input.copyTo(it) } }
        PlaceStore.open(file).also { it.importOverpass(json, regionName = "Amsterdam") }
    }

    /** Services offline at the Westerkerk with the shop store on the phone, at [clock] (the shell's Tuesday evening). */
    fun services(
        clock: Clock = Fixtures.evening,
        places: Boolean = true,
        context: Context = RuntimeEnvironment.getApplication(),
    ): Services = Services(
        context,
        ServicesConfig(
            root = Files.createTempDirectory("atlas-shop").toFile(),
            demoLocation = Services.WESTERKERK,
            offline = true,
            mapEngine = false,
            placeStores = if (places) listOf(store) else emptyList(),
            clock = clock,
        ),
    )
}
