package app.atlas.mapview.vk

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.mapcore.MapCore
import app.atlas.mapcore.state.CameraWriter
import app.atlas.render.live.RegionFrame
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
import java.time.ZoneId

/**
 * The packs the core draws from (plan-vector D3, K11; ENGINE.md step 3, one pack per country): the windows on the phone,
 * each listed with its box, profile, frame and number, and handed to the core by its file; the pack a camera draws, the
 * window whose box holds the target, whatever the distance; what is not listed: a window that holds nothing to draw,
 * one of another version, and any other file in the windows' folder; and the canal ring CI puts on its emulator, listed
 * beside the windows with its views.
 */
class PackListTest {
    private val dir: File = Files.createTempDirectory("packlist").toFile()
    private val windows = File(dir, "windows").apply { mkdirs() }

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    /**
     * A render pack as far as the list reads it: D1's header and a format-3 manifest (each view at yaw 52, pitch 44),
     * with its tiles' count where [chunks] gives it, and its engine and style versions where [version] does.
     */
    private fun renderPack(id: String, profile: String, west: Double, south: Double, east: Double, north: Double, views: Map<String, Int> = emptyMap(), tz: String? = "Europe/Amsterdam", chunks: Int? = null, version: String? = null, origin: LatLon = LatLon((south + north) / 2, (west + east) / 2)): ByteArray {
        val v = views.entries.joinToString(",") { (k, d) -> """"$k":{"target":[-177,0,-521],"yaw":52,"pitch":44,"dist":$d,"cy":440}""" }
        val versions = version?.split(' ')?.let { (engine, style) -> """"engine_version":"$engine","style_version":"$style",""" } ?: ""
        val manifest = """{"format":"atlas-pack","version":3,"layout":"render-tiles","payload":1,"id":"$id","profile":"$profile",
            |"bbox":{"west":$west,"south":$south,"east":$east,"north":$north},"origin":{"lat":${origin.lat},"lon":${origin.lon}},
            |${tz?.let { """"tz":"$it",""" } ?: ""}${chunks?.let { """"chunks":$it,""" } ?: ""}$versions"config":{"views":{$v}}}""".trimMargin().toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ATLASPCK".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 3, 0, 0))
        out.write(ByteBuffer.allocate(8).putInt(manifest.size).putInt(0).array())
        out.write(manifest)
        out.write(ByteArray(48) { it.toByte() })
        return out.toByteArray()
    }

    /** The frame cell's origin the core gives each of its windows (L10 14/8414's cell, about the canal ring). */
    private val cell = LatLon(52.3667, 4.8516)

    /** Window 14/[x]/5383's box, as the test draws it: 0.02° wide, the next one east of it. */
    private fun boxOf(x: Int): BBox {
        val west = 4.87 + (x - 8414) * 0.02
        return BBox(west, 52.36, west + 0.02, 52.38)
    }

    /** Window 14/[x]/5383 as the core writes it: its tile's box, its frame cell's origin, profile region, no views. */
    private fun window(x: Int, chunks: Int? = 1, version: String? = null, tz: String? = "Europe/Amsterdam"): File {
        val b = boxOf(x)
        val f = File(windows, "14-$x-5383.atlas")
        f.writeBytes(renderPack("window", "region", b.west, b.south, b.east, b.north, chunks = chunks, version = version, tz = tz, origin = cell))
        return f
    }

    @Test
    fun theWindowsAreListedWithTheirBoxesProfilesFramesAndNumbers() {
        window(8414)
        window(8415)
        val list = PackList(windows = { windows })
        assertFalse(list.loaded)
        val packs = list.load()
        assertTrue(list.loaded)
        assertEquals(listOf("window/14/8414/5383", "window/14/8415/5383"), packs.map { it.id })
        assertEquals(listOf(1L, 2L), packs.map { it.number })
        assertEquals(listOf(MapCore.PackRef.WINDOW, MapCore.PackRef.WINDOW), packs.map { it.kind })
        assertEquals(listOf(CameraWriter.PROFILE_REGION, CameraWriter.PROFILE_REGION), packs.map { it.profile })
        // a frame cell's windows are drawn in its frame
        assertEquals(listOf(RegionFrame.region(cell), RegionFrame.region(cell)), packs.map { it.frame })
        assertEquals(ZoneId.of("Europe/Amsterdam"), packs[0].zone)
        assertTrue(packs[0].views.isEmpty())
        assertEquals(boxOf(8414), packs[0].box)
        assertEquals(1, list.revision)
        list.load()
        assertEquals("the same packs: no new revision", 1, list.revision)
        // a window taken away keeps its number for the process; one that comes back has it again
        File(windows, "14-8414-5383.atlas").delete()
        assertEquals(listOf(2L), list.load().map { it.number })
        window(8414)
        assertEquals(listOf(1L, 2L), list.load().map { it.number })
    }

    @Test
    fun thePackDrawnIsTheWindowWhoseBoxHoldsTheTargetWhateverTheDistance() {
        window(8414)
        window(8415)
        val list = PackList(windows = { windows })
        list.load()
        // nothing is chosen by the distance: close up or from afar, the window holding the target
        for (distance in listOf(100.0, 430.0, 2_300.0, 8_000.0)) {
            assertEquals("window/14/8414/5383", list.choose(52.37, 4.88)?.id)
            assertTrue(list.covers(52.37, 4.88, distance))
        }
        assertEquals("window/14/8415/5383", list.choose(52.37, 4.90)?.id)
        // no window there: none, and the place is today's map's
        assertNull(list.choose(52.0907, 5.1214))
        assertFalse(list.covers(52.0907, 5.1214, 430.0))
        // beyond the cover's reach, the core has no tiles for the camera
        assertFalse(list.covers(52.37, 4.88, PackList.CORE_REACH * 2))
        // whether the core has anything to show in a view: the ground seen meets a window's box
        assertTrue(list.meets(BBox(4.88, 52.37, 4.95, 52.40)))
        assertFalse(list.meets(BBox(5.10, 52.08, 5.14, 52.10)))
    }

    @Test
    fun theCoreIsHandedTheWindowsFilesWithTheirTimesAndSizes() {
        val file = window(8414)
        file.setLastModified(1_700_000_000_000L)
        val list = PackList(windows = { windows })
        list.load()
        val ref = list.refs().single()
        assertEquals("window/14/8414/5383", ref.id)
        assertEquals(1L, ref.number)
        assertEquals(MapCore.PackRef.WINDOW, ref.kind)
        assertEquals(file.path, ref.path)
        assertEquals(-1, ref.fd)
        assertEquals(1_700_000_000_000L, ref.offset)
        assertEquals(file.length(), ref.length)
    }

    @Test
    fun theProfilesAreNumberedAsTheCameraState() {
        assertEquals(CameraWriter.PROFILE_LIVING, PackList.profileOf("living"))
        assertEquals(CameraWriter.PROFILE_REGION, PackList.profileOf("region"))
        assertEquals(CameraWriter.PROFILE_WORLD_BASE, PackList.profileOf("world-base"))
        assertEquals(CameraWriter.PROFILE_REGION, PackList.profileOf(null))
    }

    @Test
    fun aWindowWrittenAgainAtItsPathIsListedAgainWithItsTimeAndSize() {
        val file = window(8414)
        file.setLastModified(1_700_000_000_000L)
        val list = PackList(windows = { windows })
        list.load()
        val first = list.revision
        assertEquals(listOf("window/14/8414/5383"), list.packs.map { it.id })
        // listed again unchanged: the same list
        list.load()
        assertEquals(first, list.revision)
        // built again, or taken from the tiles: new bytes at the same path, listed anew
        window(8414, tz = "Europe/Paris")
        file.setLastModified(1_700_604_800_000L)
        list.load()
        assertTrue(list.revision > first)
        assertEquals(1_700_604_800_000L, list.refs().single().offset)
    }

    @Test
    fun aWindowThatHoldsNothingToDrawIsNotListedAndItsPlaceIsTodaysMaps() {
        // the open sea far from a coast, the middle of a field: the core would hold no tile there; a window that holds
        // its tile is listed, and so is a pack whose manifest does not count its tiles
        window(8414, chunks = 0)
        window(8415, chunks = 1)
        window(8416, chunks = null)
        val list = PackList(windows = { windows })
        list.load()
        assertEquals(listOf("window/14/8415/5383", "window/14/8416/5383"), list.packs.map { it.id })
        assertFalse(list.covers(52.37, 4.88, 400.0))
        assertTrue(list.covers(52.37, 4.90, 400.0))
    }

    @Test
    fun aWindowOfAnotherVersionIsNotListed() {
        // windows built at another style version (an earlier APK's), or before windows named theirs, are not listed, so
        // a frame cell's windows are of one version; where the version is not known, all list
        window(8414, version = "core-p1 s1.1-aaaa")
        window(8415, version = "core-p1 s1.1-bbbb")
        window(8416, version = null)
        var version: String? = "core-p1 s1.1-bbbb"
        val list = PackList(windows = { windows }, windowVersion = { version })
        list.load()
        assertEquals(listOf("window/14/8415/5383"), list.packs.map { it.id })
        // built at the version: listed
        window(8414, version = "core-p1 s1.1-bbbb")
        list.load()
        assertEquals(listOf("window/14/8414/5383", "window/14/8415/5383"), list.packs.map { it.id })
        version = null
        list.load()
        assertEquals(3, list.packs.size)
    }

    @Test
    fun nothingButTheWindowsIsListed() {
        window(8414)
        // a window's data, the mark of a window taken from the tiles, a pack being written, and a render pack that is no
        // window's, all in the windows' folder
        File(windows, "14-8414-5383.json.gz").writeBytes(ByteArray(10))
        File(windows, "14-8415-5383.tiles").writeText("2026-10-26T20:21:24Z netherlands\n")
        File(windows, "14-8416-5383.atlas.part").writeBytes(renderPack("window", "region", 4.91, 52.36, 4.93, 52.38, chunks = 1))
        File(windows, "grachten.atlas").writeBytes(renderPack("grachten", "region", 4.86, 52.35, 4.93, 52.39, mapOf("home" to 2300), chunks = 9))
        val list = PackList(windows = { windows })
        assertEquals(listOf("window/14/8414/5383"), list.load().map { it.id })
        // no folder: nothing
        assertTrue(PackList(windows = { null }).load().isEmpty())
        assertTrue(PackList().load().isEmpty())
    }

    @Test
    fun theRenderPacksCIPutsOnTheDeviceAreListedBesideTheWindows() {
        val pushed = File(dir, "atlas").apply { mkdirs() }
        val canal = File(pushed, "grachten.atlas")
        canal.writeBytes(renderPack("grachten", "region", 4.86, 52.35, 4.93, 52.39, mapOf("home" to 2300, "close" to 900), chunks = 23))
        // a file that is no pack is left out
        val broken = File(pushed, "notes.atlas").apply { writeText("not a pack") }
        val list = PackList(windows = { windows }, bench = { listOf(canal, broken) })
        val packs = list.load()
        assertEquals(listOf("grachten"), packs.map { it.id })
        val ring = packs.single()
        assertEquals(MapCore.PackRef.RENDER, ring.kind)
        assertEquals(CameraWriter.PROFILE_REGION, ring.profile)
        assertEquals(RegionFrame.region(LatLon((52.35 + 52.39) / 2, (4.86 + 4.93) / 2)), ring.frame)
        // its views as its manifest frames them: VK bench's board is the canal ring's `home`
        assertEquals(listOf("home", "close"), ring.views.keys.toList())
        val home = ring.views.getValue("home")
        assertEquals(listOf(-177.0, -521.0, 52.0, 44.0, 2300.0, 440.0), listOf(home.x, home.z, home.yaw, home.pitch, home.distance, home.cy))
        assertEquals(canal.path, list.refs().single().path)
        // the canal ring draws where it alone holds the target, at every distance; a window where one does
        assertEquals("grachten", list.choose(52.37, 4.88)?.id)
        assertTrue(list.covers(52.37, 4.88, 8_000.0))
        window(8414)
        list.load()
        assertEquals("window/14/8414/5383", list.choose(52.37, 4.88)?.id)
        assertEquals("grachten", list.choose(52.37, 4.92)?.id)
    }

    @Test
    fun aWindowsFileNamesItsWindow() {
        assertEquals(MapCore.Window(14, 8414, 5384), Windows.windowOf(File("14-8414-5384.atlas")))
        assertEquals("window/14/8414/5384", Windows.windowOf(File("14-8414-5384.atlas"))?.id)
        assertEquals(null, Windows.windowOf(File("14-8414-5384.atlas.part")))
        assertEquals(null, Windows.windowOf(File("14-8414-5384.tiles")))
        assertEquals(null, Windows.windowOf(File("notes.txt")))
        assertEquals(MapCore.Window(14, 8414, 5384), MapCore.Window.of((14L shl 48) or (8414L shl 24) or 5384L))
    }
}
