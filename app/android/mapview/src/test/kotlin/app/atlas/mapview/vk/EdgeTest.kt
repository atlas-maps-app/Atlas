package app.atlas.mapview.vk

import app.atlas.geo.BBox
import app.atlas.mapcore.MapCore
import app.atlas.mapview.CountryTiles
import app.atlas.mapview.MapEngine
import app.atlas.mapview.vk.MapPlace.DRAWN
import app.atlas.mapview.vk.MapPlace.NEAR
import app.atlas.mapview.vk.MapPlace.WINDOWS
import app.atlas.mapview.vk.MapPlace.WORLD
import app.atlas.tiles.WorldIndex
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.time.Instant

/**
 * The edge of the windows on the phone (the user, 30 October, on alpha 21: today's map there "looks different then the
 * engine", "the dark green is a different green"): the core draws every place (ENGINE.md step 3; today's map left the
 * phone on 5 November). Where a place's window has not come, its camera names the window nearest the place, so it draws
 * the windows it has around it (its own cover), and the map says what the windows are doing: taking the place from the
 * country's tiles, getting its data, building it, waiting, or that it is not on the phone offline ([MapPlace.WINDOWS]);
 * beyond the windows' reach, that a nearer view shows the map ([MapPlace.NEAR]); beyond the levels' reach, that the
 * world is on its way ([MapPlace.WORLD]).
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
        MapEngine.countryHolds = null
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

    private fun inCountry(lat: Double, lon: Double) = MapEngine.levelsAt(lat, lon)

    @Test
    fun beyondTheLastWindowWithinTheCountryTheCoreDrawsItsOwnCoverWhileTheWindowComes() {
        val list = twoWindows()
        MapEngine.countries = listOf(netherlands)
        // the Plantage, east of the last window: no window holds it, the country's tiles do
        val lat = 52.3662
        val lon = 4.9150
        assertNull(list.choose(lat, lon))
        assertFalse(list.covers(lat, lon, 650.0))
        for (distance in listOf(100.0, 650.0, 2_300.0, PackList.CORE_REACH, PackList.LEVELS_REACH)) {
            // the windows' place: the core's own cover, and the windows' line
            assertEquals("at $distance m", WINDOWS, MapPlace.of(list, ::inCountry, lat, lon, distance))
        }
        // the camera names the window nearest the place, which the core draws with its frame cell's pack: its own cover
        assertEquals("window/14/8415/5383", list.named(lat, lon)?.id)
        // on a window, as before: its own
        assertEquals("window/14/8414/5383", list.named(52.37, 4.88)?.id)
        assertEquals(DRAWN, MapPlace.of(list, ::inCountry, 52.37, 4.88, 430.0))
    }

    @Test
    fun beyondTheReachOfWhatIsOnThePhoneTheMapSaysWhyItIsEmpty() {
        val list = twoWindows()
        MapEngine.countries = listOf(netherlands)
        // within the country the core draws its frame cells' levels beyond the windows' reach (ENGINE.md one pack per
        // country, step 7): none of them on the phone yet, they come as windows do; zoomed out past their reach, the
        // world's place
        assertEquals(WINDOWS, MapPlace.of(list, ::inCountry, 52.37, 4.88, PackList.CORE_REACH + 1))
        assertEquals(WORLD, MapPlace.of(list, ::inCountry, 52.3662, 4.9150, PackList.LEVELS_REACH + 1))
        assertEquals(WORLD, MapPlace.of(list, ::inCountry, 52.37, 4.88, PackList.LEVELS_REACH + 1))
        // the country's tiles of another core only (published before this core's): its windows are built on the phone
        // and no level comes, so the windows' reach is the cover's, and a nearer view shows the map beyond it
        MapEngine.countries = listOf(netherlands.copy(levels = false))
        assertEquals(WINDOWS, MapPlace.of(list, ::inCountry, 52.3662, 4.9150, PackList.CORE_REACH))
        assertEquals(NEAR, MapPlace.of(list, ::inCountry, 52.37, 4.88, PackList.CORE_REACH + 1))
        MapEngine.countries = listOf(netherlands)
        // Lille, south of the Netherlands' box (CI's away script): no window, no country's tiles; its window comes as any
        assertEquals(WINDOWS, MapPlace.of(list, ::inCountry, 50.6292, 3.0573, 430.0))
        assertEquals(NEAR, MapPlace.of(list, ::inCountry, 50.6292, 3.0573, PackList.CORE_REACH + 1))
        // before the published list is read, no country is known: the windows alone decide, and beyond their reach a
        // level's tile on the phone that holds the target
        MapEngine.countries = emptyList()
        assertEquals(WINDOWS, MapPlace.of(list, ::inCountry, 52.3662, 4.9150, 650.0))
        assertEquals(DRAWN, MapPlace.of(list, ::inCountry, 52.37, 4.88, 650.0))
        assertEquals(NEAR, MapPlace.of(list, ::inCountry, 52.37, 4.88, PackList.CORE_REACH + 1))
        // the L13 tile that holds the target, as the tiles give it (13/4207/2692: 4.878° to 4.922° east, 52.349° to
        // 52.376° north)
        window(4207, z = 13, y = 2692, west = 4.878, wide = 0.044, south = 52.349, north = 52.376)
        list.load()
        assertEquals(DRAWN, MapPlace.of(list, ::inCountry, 52.37, 4.88, PackList.CORE_REACH + 1))
        assertEquals(WORLD, MapPlace.of(list, ::inCountry, 52.37, 4.88, PackList.LEVELS_REACH + 1))
        // with nothing listed, the camera names no pack
        assertNull(PackList(windows = { File(dir, "none") }).also { it.load() }.named(52.37, 4.88))
    }

    @Test
    fun withTheWorldOnThePhoneTheCoreDrawsEveryCameraBeyondTheLevelsReach() {
        // the world's folder as WorldFetch keeps it: the index (one fine pack) and the globe (ENGINE.md step 6); the pack
        // is listed once its head is in the world's cache, as a remote pack of its SHA-256 and length
        val world = File(dir, "world").apply { mkdirs() }
        val sha = ByteArray(WorldIndex.HASH_BYTES) { it.toByte() }
        val fine = WorldIndex.Entry(6, 33, 21, 9, 11, 0, 20, 80, 1, IntArray(4), sha)
        File(world, "world.idx").outputStream().use { WorldIndex(Instant.EPOCH, listOf(WorldIndex.Part("world-mid.000", 100)), listOf(fine)).write(it) }
        File(world, PackList.WORLD_GLOBE).writeBytes(ByteArray(10))
        var held = false
        val asked = ArrayList<ByteArray>()
        val list = PackList(windows = { windows }, world = { world }, worldHeld = { ids -> asked += ids; BooleanArray(ids.size) { held } })
        list.load()
        // the globe alone while the pack's head is not on the phone
        assertEquals(listOf("world-globe 4"), list.worldRefs.map { "${it.id} ${it.kind}" })
        assertArrayEquals(sha, asked.single())
        held = true
        val revision = list.revision
        list.load()
        assertTrue(list.revision > revision)
        assertTrue(list.hasWorld)
        assertEquals(
            listOf("world/6/33/21 3 0 80", "world-globe 4"),
            list.worldRefs.map { r -> if (r.kind == MapCore.PackRef.WORLD_MID) "${r.id} ${r.kind} ${r.offset} ${r.length}" else "${r.id} ${r.kind}" },
        )
        val ref = list.refs().first { it.id == "world/6/33/21" }
        assertEquals("", ref.path)
        assertArrayEquals(sha, ref.identity)
        // beyond the levels' reach the core draws every camera, in a country or not, over land or the open sea
        assertEquals(DRAWN, MapPlace.of(list, ::inCountry, 50.6292, 3.0573, PackList.LEVELS_REACH + 1))
        assertEquals(DRAWN, MapPlace.of(list, ::inCountry, 40.0, -30.0, 1_000_000.0))
        // within it, as before: no window at Lille, a nearer view shows it
        assertEquals(NEAR, MapPlace.of(list, ::inCountry, 50.6292, 3.0573, PackList.LEVELS_REACH))
        // the world is no pack a camera names; beyond the levels' reach it draws, so no window is on its way there
        assertNull(list.named(50.6292, 3.0573))
        assertTrue(list.worldDraws(PackList.LEVELS_REACH + 1))
        assertFalse(list.worldDraws(PackList.LEVELS_REACH))
        // where no country's levels are (Lille: outside the Netherlands' box) the core draws the world under its cover
        // from every distance, so beyond the windows' reach the world draws the camera where its index has the place's
        // fine pack (here Mainz, in block 6/33/21, its head on the phone or not), and nowhere else
        assertTrue(list.worldDraws(50.0, 8.27, PackList.CORE_REACH + 1, under = true))
        assertEquals(DRAWN, MapPlace.of(list, ::inCountry, 50.0, 8.27, PackList.CORE_REACH + 1, under = true))
        assertFalse(list.worldDraws(50.0, 8.27, PackList.CORE_REACH, under = true))
        assertEquals(WINDOWS, MapPlace.of(list, ::inCountry, 50.0, 8.27, PackList.CORE_REACH, under = true))
        assertFalse(list.worldDraws(50.6292, 3.0573, PackList.CORE_REACH + 1, under = true))
        assertEquals(NEAR, MapPlace.of(list, ::inCountry, 50.6292, 3.0573, PackList.LEVELS_REACH, under = true))
        // and where a country's levels are, beyond their reach alone
        assertFalse(list.worldDraws(50.0, 8.27, PackList.LEVELS_REACH, under = false))
        assertTrue(list.worldDraws(50.0, 8.27, PackList.LEVELS_REACH + 1, under = false))
        // a new version's index names the pack by another SHA-256: listed anew once that head is on the phone
        val again = WorldIndex.Entry(6, 33, 21, 9, 11, 0, 20, 80, 1, IntArray(4), ByteArray(WorldIndex.HASH_BYTES) { 7 })
        File(world, "world.idx").outputStream().use { WorldIndex(Instant.EPOCH, listOf(WorldIndex.Part("world-mid.000", 100)), listOf(again)).write(it) }
        val before = list.revision
        list.load()
        assertTrue(list.revision > before)
        assertArrayEquals(again.sha256, list.refs().first { it.id == "world/6/33/21" }.identity)
        // with no head in the cache and without the globe, the world is gone
        held = false
        File(world, PackList.WORLD_GLOBE).delete()
        list.load()
        assertFalse(list.hasWorld)
        assertFalse(list.worldDraws(PackList.LEVELS_REACH + 1))
        assertEquals(WORLD, MapPlace.of(list, ::inCountry, 50.6292, 3.0573, PackList.LEVELS_REACH + 1))
        assertFalse(list.worldDraws(50.0, 8.27, PackList.CORE_REACH + 1, under = true))
        assertEquals(NEAR, MapPlace.of(list, ::inCountry, 50.0, 8.27, PackList.CORE_REACH + 1, under = true))
    }

    @Test
    fun theTerrainsPacksAreListedOnceTheirHeadsAreOnThePhoneApartFromTheMap() {
        // the terrain's folder as TerrainFetch keeps it (ENGINE.md part 6, step 2): two regions' indexes, Amsterdam's
        // L9 block in each (a border's block two regions hold), and terrain.json beside them; a pack is listed once its
        // head is in the world's cache, as a remote terrain pack of its SHA-256 and length, its id the core's
        val terrain = File(dir, "terrain").apply { mkdirs() }
        val nl = ByteArray(WorldIndex.HASH_BYTES) { 1 }
        val de = ByteArray(WorldIndex.HASH_BYTES) { 2 }
        fun index(region: String, vararg entries: WorldIndex.Entry) =
            File(terrain, "$region-terrain.idx").outputStream().use { WorldIndex(Instant.EPOCH, listOf(WorldIndex.Part("$region-terrain.000", 1_000)), entries.toList()).write(it) }
        index("netherlands", WorldIndex.Entry(9, 262, 168, 12, 14, 0, 0, 400, 21, IntArray(4), nl), WorldIndex.Entry(9, 263, 168, 12, 14, 0, 400, 300, 21, IntArray(4), ByteArray(32) { 3 }))
        index("germany", WorldIndex.Entry(9, 262, 168, 12, 14, 0, 100, 500, 21, IntArray(4), de))
        File(terrain, "terrain.json").writeText("{}")
        File(terrain, "netherlands-terrain.idx.download").writeBytes(ByteArray(10))
        val held = HashSet<List<Byte>>()
        val list = PackList(windows = { windows }, worldHeld = { ids -> BooleanArray(ids.size) { ids[it].toList() in held } }, terrain = { terrain })
        list.load()
        assertEquals(emptyList<MapCore.PackRef>(), list.terrainRefs)
        held += nl.toList()
        held += de.toList()
        val revision = list.revision
        list.load()
        assertTrue(list.revision > revision)
        assertEquals(
            listOf("terrain/germany-terrain/9/262/168 5 0 500", "terrain/netherlands-terrain/9/262/168 5 0 400"),
            list.terrainRefs.map { "${it.id} ${it.kind} ${it.offset} ${it.length}" },
        )
        val ref = list.refs().first { it.id == "terrain/netherlands-terrain/9/262/168" }
        assertEquals("", ref.path)
        assertArrayEquals(nl, ref.identity)
        // the terrain is no world, no pack a camera names, and covers nothing
        assertFalse(list.hasWorld)
        assertNull(list.named(52.37, 4.88))
        assertFalse(list.covers(52.37, 4.88, 1_000.0))
        // the same heads again: nothing new for the core; a region's index gone (a new release that no longer names it)
        val same = list.revision
        list.load()
        assertEquals(same, list.revision)
        File(terrain, "germany-terrain.idx").delete()
        list.load()
        assertTrue(list.revision > same)
        assertEquals(listOf("terrain/netherlands-terrain/9/262/168"), list.terrainRefs.map { it.id })
    }

    @Test
    fun withinACountrysBoxBeyondItsBorderNoLevelsAreOnceItsTilesIndexSaysSo() {
        val antwerp = doubleArrayOf(51.2194, 4.4025)
        val westerkerk = doubleArrayOf(52.3747, 4.8838)
        MapEngine.countries = listOf(netherlands)
        // the box alone, until the country's tiles' index is on the phone: Antwerp counts as the Netherlands', its
        // levels on their way out to their reach, and no world under the cover
        MapEngine.countryHolds = MapEngine.CountryHolds { _, _, _ -> null }
        assertEquals(netherlands, MapEngine.levelsAt(antwerp[0], antwerp[1]))
        assertFalse(MapEngine.worldUnder(antwerp[0], antwerp[1]))
        // the index lists no window there (the country's data ends at its border, here drawn at 51.3° north)
        MapEngine.countryHolds = MapEngine.CountryHolds { c, lat, _ -> c.id == "netherlands" && lat > 51.3 }
        assertNull(MapEngine.levelsAt(antwerp[0], antwerp[1]))
        assertTrue(MapEngine.worldUnder(antwerp[0], antwerp[1]))
        assertEquals("the box still names the country", netherlands, MapEngine.countryAt(antwerp[0], antwerp[1]))
        assertEquals(netherlands, MapEngine.levelsAt(westerkerk[0], westerkerk[1]))
        assertFalse(MapEngine.worldUnder(westerkerk[0], westerkerk[1]))
        // so at Antwerp the windows' reach is the cover's, as anywhere outside the countries
        val list = twoWindows()
        assertEquals(WINDOWS, MapPlace.of(list, ::inCountry, antwerp[0], antwerp[1], PackList.CORE_REACH))
        assertEquals(NEAR, MapPlace.of(list, ::inCountry, antwerp[0], antwerp[1], PackList.CORE_REACH + 1))
        assertEquals(WINDOWS, MapPlace.of(list, ::inCountry, westerkerk[0], 4.9150, PackList.CORE_REACH + 1))
        // a country without levels for this core has none anywhere, whatever its index lists
        MapEngine.countries = listOf(netherlands.copy(levels = false))
        assertTrue(MapEngine.worldUnder(westerkerk[0], westerkerk[1]))
        // and where two countries' boxes hold a place, the one whose tiles hold it has its levels
        val belgium = CountryTiles("belgium", "Belgium", BBox(2.5, 49.4, 6.5, 51.6), levels = true)
        MapEngine.countries = listOf(netherlands, belgium)
        MapEngine.countryHolds = MapEngine.CountryHolds { c, lat, _ -> if (c.id == "netherlands") lat > 51.3 else lat <= 51.3 }
        assertEquals(belgium, MapEngine.levelsAt(antwerp[0], antwerp[1]))
        assertFalse(MapEngine.worldUnder(antwerp[0], antwerp[1]))
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
