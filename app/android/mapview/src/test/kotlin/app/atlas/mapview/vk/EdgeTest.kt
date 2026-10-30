package app.atlas.mapview.vk

import app.atlas.geo.BBox
import app.atlas.mapview.CountryTiles
import app.atlas.mapview.MapController
import app.atlas.mapview.MapEngine
import app.atlas.mapview.vk.Transition.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

/**
 * The edge of the windows on the phone (the user, 30 October, on alpha 21: today's map there "looks different then the
 * engine", "the dark green is a different green"): within a country whose tiles the core draws, a place whose window
 * has not come is never handed to today's map. The core keeps it, its camera naming the window nearest the place, so it
 * draws the windows it has around it (its own cover), and the map says what the windows are doing: taking the place
 * from the country's tiles, getting its data, building it, waiting, or that it is not on the phone offline. Beyond the
 * core's reach, and outside such a country where no window is on the phone, today's map draws as before, in the core's
 * light where that is still in the country.
 */
class EdgeTest {
    private val dir: File = Files.createTempDirectory("edge").toFile()
    private val windows = File(dir, "windows").apply { mkdirs() }

    /**
     * The Netherlands as packs-latest lists its tiles (packs-20261029): its pack's box, its tiles this core's, so they
     * hold its frame cells' levels.
     */
    private val netherlands = CountryTiles("netherlands", "Netherlands", BBox(2.9361, 50.7475, 7.2305, 54.0179), levels = true)

    @After
    fun clean() {
        dir.deleteRecursively()
        MapEngine.countries = emptyList()
    }

    /**
     * Window 14/[x]/5383 as the core writes it (PackListTest's): its box 0.02° wide from 4.87° east, no views; or a
     * frame cell's level's tile [z]/[x]/[y] with the box given.
     */
    private fun window(
        x: Int,
        z: Int = 14,
        y: Int = 5383,
        west: Double = 4.87 + (x - 8414) * 0.02,
        wide: Double = 0.02,
        south: Double = 52.36,
        north: Double = 52.38,
    ) {
        val manifest = """{"format":"atlas-pack","version":3,"layout":"render-tiles","payload":1,"id":"window","profile":"region",
            |"bbox":{"west":$west,"south":$south,"east":${west + wide},"north":$north},"origin":{"lat":52.3667,"lon":4.8516},
            |"tz":"Europe/Amsterdam","chunks":1,"config":{"views":{}}}""".trimMargin().toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ATLASPCK".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 3, 0, 0))
        out.write(ByteBuffer.allocate(8).putInt(manifest.size).putInt(0).array())
        out.write(manifest)
        out.write(ByteArray(48) { it.toByte() })
        File(windows, "$z-$x-$y.atlas").writeBytes(out.toByteArray())
    }

    /** The two windows of the canal ring's south (4.87° to 4.91° east), as on a phone that has taken nothing else yet. */
    private fun twoWindows(): PackList {
        window(8414)
        window(8415)
        return PackList(windows = { windows }).also { it.load() }
    }

    private fun inCountry(lat: Double, lon: Double) = MapEngine.countryAt(lat, lon)

    @Test
    fun beyondTheLastWindowWithinTheCountryTheCoreKeepsThePlaceAndTodaysMapIsNotAsked() {
        val list = twoWindows()
        MapEngine.countries = listOf(netherlands)
        // the Plantage, east of the last window: no window holds it, the country's tiles do
        val lat = 52.3662
        val lon = 4.9150
        assertNull(list.choose(lat, lon))
        assertFalse(list.covers(lat, lon, 650.0))
        for (distance in listOf(100.0, 650.0, 2_300.0, PackList.CORE_REACH, PackList.LEVELS_REACH)) {
            // the core's path, so MapController asks today's map for no frame here and draws none of its last
            assertEquals("at $distance m", Path.CORE, Transition.pathOf(list, ::inCountry, lat, lon, distance))
        }
        // the camera names the window nearest the place, which the core draws with its frame cell's pack: its own cover
        assertEquals("window/14/8415/5383", list.named(lat, lon)?.id)
        // on a window, as before: its own
        assertEquals("window/14/8414/5383", list.named(52.37, 4.88)?.id)
        assertEquals(Path.CORE, Transition.pathOf(list, ::inCountry, 52.37, 4.88, 430.0))
    }

    @Test
    fun todaysMapStillDrawsBeyondTheCoresReachAndOutsideTheCountriesWhereNoWindowIs() {
        val list = twoWindows()
        MapEngine.countries = listOf(netherlands)
        // within the country the core draws its frame cells' levels beyond the windows' reach (ENGINE.md one pack per
        // country, step 7); zoomed out past their reach, today's map
        assertEquals(Path.CORE, Transition.pathOf(list, ::inCountry, 52.37, 4.88, PackList.CORE_REACH + 1))
        assertEquals(Path.CANVAS, Transition.pathOf(list, ::inCountry, 52.3662, 4.9150, PackList.LEVELS_REACH + 1))
        assertEquals(Path.CANVAS, Transition.pathOf(list, ::inCountry, 52.37, 4.88, PackList.LEVELS_REACH + 1))
        // the country's tiles of another core only (published before this core's): its windows are built on the phone
        // and no level comes, so the core keeps the windows' reach and today's map draws beyond it
        MapEngine.countries = listOf(netherlands.copy(levels = false))
        assertEquals(Path.CORE, Transition.pathOf(list, ::inCountry, 52.3662, 4.9150, PackList.CORE_REACH))
        assertEquals(Path.CANVAS, Transition.pathOf(list, ::inCountry, 52.37, 4.88, PackList.CORE_REACH + 1))
        MapEngine.countries = listOf(netherlands)
        // where it has nothing to draw there (it takes no region in such a country), the map says to zoom in
        assertEquals("Zoom in to see the map", MapController.noDataLine(52.37, 4.88))
        // Lille, south of the Netherlands' box (CI's transitional script): no window, no country's tiles
        assertEquals(Path.CANVAS, Transition.pathOf(list, ::inCountry, 50.6292, 3.0573, 430.0))
        assertEquals("No map data here", MapController.noDataLine(50.6292, 3.0573))
        // before the published list is read, no country is known: the windows alone decide, as until now, and beyond
        // their reach a level's tile on the phone that holds the target
        MapEngine.countries = emptyList()
        assertEquals("No map data here", MapController.noDataLine(52.37, 4.88))
        assertEquals(Path.CANVAS, Transition.pathOf(list, ::inCountry, 52.3662, 4.9150, 650.0))
        assertEquals(Path.CORE, Transition.pathOf(list, ::inCountry, 52.37, 4.88, 650.0))
        assertEquals(Path.CANVAS, Transition.pathOf(list, ::inCountry, 52.37, 4.88, PackList.CORE_REACH + 1))
        // the L13 tile that holds the target, as the tiles give it (13/4207/2692: 4.878° to 4.922° east, 52.349° to
        // 52.376° north)
        window(4207, z = 13, y = 2692, west = 4.878, wide = 0.044, south = 52.349, north = 52.376)
        list.load()
        assertEquals(Path.CORE, Transition.pathOf(list, ::inCountry, 52.37, 4.88, PackList.CORE_REACH + 1))
        assertEquals(Path.CANVAS, Transition.pathOf(list, ::inCountry, 52.37, 4.88, PackList.LEVELS_REACH + 1))
        // with nothing listed, the camera names no pack
        assertNull(PackList(windows = { File(dir, "none") }).also { it.load() }.named(52.37, 4.88))
    }

    @Test
    fun theCountryOfAPlaceIsThePublishedPackWhoseBoxHoldsIt() {
        MapEngine.countries = listOf(netherlands)
        assertEquals(netherlands, MapEngine.countryAt(52.3747, 4.8838)) // the Westerkerk
        assertEquals(netherlands, MapEngine.countryAt(50.8514, 5.6910)) // Maastricht, in the box's south-east
        assertNull(MapEngine.countryAt(50.6292, 3.0573)) // Lille
        assertNull(MapEngine.countryAt(48.8566, 2.3522)) // Paris
    }

    @Test
    fun whereTodaysMapStillDrawsInTheCountryItTakesTheCoresLight() {
        // beyond the core's reach in the Netherlands: TONE whatever the region's profile, as the core lights every pack there
        MapEngine.countries = listOf(netherlands)
        assertTrue(MapEngine.coreLight(52.366, 4.917)) // Artis's region
        assertTrue(MapEngine.coreLight(52.3746, 4.8840)) // the Westerkerk's, the living map's
        // outside it, and before the published list is read, the renders' own light
        assertFalse(MapEngine.coreLight(48.858, 2.343)) // Paris's region
        MapEngine.countries = emptyList()
        assertFalse(MapEngine.coreLight(52.366, 4.917))
    }

    @Test
    fun whereThePlacesWindowHasNotComeTheMapSaysWhatTheWindowsAreDoing() {
        val since = 1_000L
        assertEquals("Getting this area from the Netherlands map…", Windows.line(true, Windows.Status.Fetching(since, tiles = true), 0L, "Netherlands"))
        assertEquals("Getting this area's map…", Windows.line(true, Windows.Status.Fetching(since, tiles = true), 0L, null))
        assertEquals("Getting this area from OpenStreetMap…", Windows.line(true, Windows.Status.Fetching(since), 0L, "Netherlands"))
        assertEquals("Building the map here…", Windows.line(true, Windows.Status.Building(since), 0L, "Netherlands"))
        assertEquals(
            "OpenStreetMap's servers are busy · trying again in 3 min",
            Windows.line(true, Windows.Status.Waiting(since + 150_000L, refused = false), 150_000L, "Netherlands"),
        )
        assertEquals("This area's data could not be built", Windows.line(true, Windows.Status.Waiting(since, refused = true), 0L, null))
        // with Overpass switched off in the sources, no server is waited for: no pack holds the place
        assertEquals(
            "No pack holds this area, and Overpass is switched off",
            Windows.line(true, Windows.Status.Waiting(since + 150_000L, refused = false), 150_000L, "Netherlands", overpassOff = true),
        )
        assertEquals("This area's data could not be built", Windows.line(true, Windows.Status.Waiting(since, refused = true), 0L, null, overpassOff = true))
        assertEquals("Offline · this area is not on the phone yet", Windows.line(false, null, 0L, "Netherlands"))
        assertEquals("Offline · this area is not on the phone yet", Windows.line(false, Windows.Status.Idle, 0L, null))
        // resting windows: nothing to say
        assertNull(Windows.line(true, Windows.Status.Idle, 0L, "Netherlands"))
        assertNull(Windows.line(true, null, 0L, "Netherlands"))
    }
}
