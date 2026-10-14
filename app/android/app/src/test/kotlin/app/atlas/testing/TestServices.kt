package app.atlas.testing

import android.content.Context
import org.robolectric.RuntimeEnvironment
import app.atlas.Services
import app.atlas.ServicesConfig
import app.atlas.search.PlaceStore
import app.atlas.tiles.TileStack
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * A [Services] for tests and screenshots: its own temporary folder, no network, the fixture tiles of the Westerkerk,
 * and the demo location there. Feature tests build theirs with this and add what they need.
 */
fun testServices(
    context: Context = RuntimeEnvironment.getApplication(),
    root: File = Files.createTempDirectory("atlas-test").toFile(),
    demo: Boolean = true,
    /** Attach the place store of `data/ams/raw.json` (the canal ring around the Westerkerk, every tag). */
    places: Boolean = false,
    /** The app's "now": by default a fixed autumn evening in Amsterdam, so screenshots come out the same each run. */
    clock: Clock = Fixtures.evening,
): Services = Services(
    context,
    ServicesConfig(
        root = root,
        demoLocation = if (demo) Services.WESTERKERK else null,
        offline = true,
        tiles = TileStack(listOf(FixtureTiles())),
        placeStores = if (places) listOf(Fixtures.amsStore) else emptyList(),
        clock = clock,
    ),
)

/** Test data shared by every test in the run. */
object Fixtures {
    /**
     * Tuesday 13 October 2026, 19:30 in Amsterdam (17:30 UTC): just after sunset, dinner time, and the moment the
     * providers' forecast fixture was fetched.
     */
    val evening: Clock = Clock.fixed(Instant.parse("2026-10-13T17:30:00Z"), ZoneId.of("Europe/Amsterdam"))

    /** The repository's root (`atlas.repo`). */
    val repo: File get() = File(System.getProperty("atlas.repo") ?: "../..")

    /**
     * Tests that open it must run under Robolectric (`@RunWith(RobolectricTestRunner::class)`): SQLite's native library
     * can belong to one class loader only, and the screen tests already load it in Robolectric's.
     *
     * The Overpass extract around the Westerkerk (`data/ams/raw.json`, 8.7 MB, full tags) imported once per test run
     * into a temporary place store, through the store's own import.
     */
    val amsStore: PlaceStore by lazy {
        val file = File.createTempFile("ams", ".db").apply { delete(); deleteOnExit() }
        PlaceStore.open(file).also { it.importOverpass(File(repo, "data/ams/raw.json"), regionName = "Amsterdam") }
    }
}

/**
 * Keeps a real Open-Meteo forecast (the providers' fixture) on the test phone for the Westerkerk, as if fetched now (by the app's clock),
 * so screens that show the weather offline have one. Returns false when the fixture is not there.
 */
fun Services.keepFixtureForecast(): Boolean {
    val repo = System.getProperty("atlas.repo") ?: return false
    val fixture = File(repo, "app/android/providers/src/test/resources/fixtures/meteo_forecast.json").takeIf { it.isFile } ?: return false
    val dir = File(dirs.cache, "providers/weather").apply { mkdirs() }
    val at = Services.WESTERKERK
    val name = String.format(java.util.Locale.ROOT, "forecast_%.2f_%.2f.json", at.lat, at.lon)
    File(dir, name).writeText("${clock.instant().epochSecond}\n${fixture.readText()}")
    return true
}
