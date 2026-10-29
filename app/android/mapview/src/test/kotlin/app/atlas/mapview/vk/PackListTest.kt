package app.atlas.mapview.vk

import app.atlas.geo.LatLon
import app.atlas.mapcore.MapCore
import app.atlas.mapcore.state.CameraWriter
import app.atlas.render.live.HttpGet
import app.atlas.render.live.PackLibrary
import app.atlas.render.live.RegionFrame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.time.ZoneId

/**
 * The packs the core draws from (plan-vector D3, K11): the APK's bundled render packs by their index, the library's
 * downloaded ones (newer, so they replace a bundled pack of the same region), the pack a camera draws chosen as the live
 * map has always chosen its region (by its views' distances), the core's list of them, and a first visit's render pack
 * fetched once through K20a's seam.
 */
class PackListTest {
    private val dir: File = Files.createTempDirectory("packlist").toFile()
    private val westerkerk = LatLon(52.3746, 4.8840)

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    /**
     * A render pack as far as the lists read it: D1's header and a format-3 manifest (each view at yaw 52, pitch 44),
     * with its tiles' count where [chunks] gives it, and its engine and style versions where [version] does.
     */
    private fun renderPack(id: String, profile: String, west: Double, south: Double, east: Double, north: Double, views: Map<String, Int> = emptyMap(), tz: String? = "Europe/Amsterdam", chunks: Int? = null, version: String? = null): ByteArray {
        val v = views.entries.joinToString(",") { (k, d) -> """"$k":{"target":[-177,0,-521],"yaw":52,"pitch":44,"dist":$d,"cy":440}""" }
        val versions = version?.split(' ')?.let { (engine, style) -> """"engine_version":"$engine","style_version":"$style",""" } ?: ""
        val manifest = """{"format":"atlas-pack","version":3,"layout":"render-tiles","payload":1,"id":"$id","profile":"$profile",
            |"bbox":{"west":$west,"south":$south,"east":$east,"north":$north},"origin":{"lat":${(south + north) / 2},"lon":${(west + east) / 2}},
            |${tz?.let { """"tz":"$it",""" } ?: ""}${chunks?.let { """"chunks":$it,""" } ?: ""}$versions"config":{"views":{$v}}}""".trimMargin().toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ATLASPCK".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 3, 0, 0))
        out.write(ByteBuffer.allocate(8).putInt(manifest.size).putInt(0).array())
        out.write(manifest)
        out.write(ByteArray(48) { it.toByte() })
        return out.toByteArray()
    }

    /** The APK's tiles/ folder, as a directory, with fake descriptors whose closing is counted. */
    private inner class Assets(private val tiles: File) : PackList.BundledSource {
        var closed = 0
        override fun open(name: String): InputStream? = File(tiles, name).takeIf { it.isFile }?.inputStream()
        override fun range(name: String): PackList.Range? {
            val f = File(tiles, name).takeIf { it.isFile } ?: return null
            return PackList.Range(100 + name.length, 0L, f.length()) { closed++ }
        }
    }

    private fun bundledTiles(): File {
        val tiles = File(dir, "tiles").apply { mkdirs() }
        File(tiles, "amsterdam.atlas").writeBytes(renderPack("amsterdam", "living", 4.876, 52.371, 4.893, 52.378))
        File(tiles, "grachten.atlas").writeBytes(renderPack("grachten", "region", 4.86, 52.35, 4.93, 52.39, mapOf("home" to 2300, "close" to 900)))
        File(tiles, PackList.INDEX).writeText(
            """{"format":3,"payload":1,"packs":[{"id":"amsterdam","file":"amsterdam.atlas","profile":"living"},
              |{"id":"grachten","file":"grachten.atlas","profile":"region"}]}""".trimMargin(),
        )
        return tiles
    }

    @Test
    fun theBundledPacksAreListedWithTheirBoxesProfilesFramesAndNumbers() {
        val list = PackList(Assets(bundledTiles()), library = null)
        val packs = list.load()
        assertEquals(listOf("amsterdam", "grachten"), packs.map { it.id })
        assertEquals(listOf(1L, 2L), packs.map { it.number })
        assertEquals(listOf(CameraWriter.PROFILE_LIVING, CameraWriter.PROFILE_REGION), packs.map { it.profile })
        assertEquals(listOf(MapCore.PackRef.BUNDLED, MapCore.PackRef.BUNDLED), packs.map { it.kind })
        assertEquals(RegionFrame.living(), packs[0].frame)
        assertEquals(RegionFrame.region(LatLon((52.35 + 52.39) / 2, (4.86 + 4.93) / 2)), packs[1].frame)
        assertEquals(ZoneId.of("Europe/Amsterdam"), packs[1].zone)
        // A region's views as its manifest frames them (the Main board is the canal ring's `home`); the living map has none.
        assertEquals(listOf("home", "close"), packs[1].views.keys.toList())
        val home = packs[1].views.getValue("home")
        assertEquals(listOf(-177.0, -521.0, 52.0, 44.0, 2300.0, 440.0), listOf(home.x, home.z, home.yaw, home.pitch, home.distance, home.cy))
        assertTrue(packs[0].views.isEmpty())
        assertEquals(1, list.revision)
        list.load()
        assertEquals("the same packs: no new revision", 1, list.revision)
    }

    @Test
    fun thePackDrawnStaysUntilAnotherFitsClearlyBetterAndWhileTheCameraMoves() {
        val list = PackList(Assets(bundledTiles()), library = null)
        list.load()
        val (lat, lon) = westerkerk.lat to westerkerk.lon
        // the living map's 430 m and the canal ring's close view of 900 m fit alike at 622 m: past it the plain choice
        // is the canal ring
        assertEquals("grachten", list.choose(lat, lon, 650.0)?.id)
        assertEquals("amsterdam", list.choose(lat, lon, 600.0)?.id)
        // the pack drawn now stays within the margin either way, and gives way beyond it (723 m out, 535 m in)
        assertEquals("amsterdam", list.choose(lat, lon, 650.0, held = "amsterdam")?.id)
        assertEquals("amsterdam", list.choose(lat, lon, 715.0, held = "amsterdam")?.id)
        assertEquals("grachten", list.choose(lat, lon, 735.0, held = "amsterdam")?.id)
        assertEquals("grachten", list.choose(lat, lon, 545.0, held = "grachten")?.id)
        assertEquals("amsterdam", list.choose(lat, lon, 525.0, held = "grachten")?.id)
        // while the camera moves, whatever the distance, as long as its box holds the target
        assertEquals("grachten", list.choose(lat, lon, 100.0, held = "grachten", holdFit = Double.POSITIVE_INFINITY)?.id)
        assertEquals("amsterdam", list.choose(lat, lon, 5_000.0, held = "amsterdam", holdFit = Double.POSITIVE_INFINITY)?.id)
        // a pack whose box no longer holds the target, or one not listed, holds nothing
        assertEquals("grachten", list.choose(52.36, 4.90, 430.0, held = "amsterdam", holdFit = Double.POSITIVE_INFINITY)?.id)
        assertEquals("grachten", list.choose(lat, lon, 650.0, held = "gone")?.id)
    }

    @Test
    fun thePackDrawnIsTheOneWhoseViewsFitTheDistanceAsTheLiveMapChooses() {
        val list = PackList(Assets(bundledTiles()), library = null)
        list.load()
        // Close up at the Westerkerk the living map (its view stands 430 m back), from 2 km the canal ring.
        assertEquals("amsterdam", list.choose(westerkerk.lat, westerkerk.lon, 430.0)?.id)
        assertEquals("grachten", list.choose(westerkerk.lat, westerkerk.lon, 2_300.0)?.id)
        // Outside the Westerkerk's box only the canal ring holds the target; outside both, nothing.
        assertEquals("grachten", list.choose(52.36, 4.90, 430.0)?.id)
        assertNull(list.choose(52.0907, 5.1214, 430.0))
        // The transitional rule's place: a pack holds it, and it is within the cover's reach.
        assertTrue(list.covers(westerkerk.lat, westerkerk.lon, 2_300.0))
        assertFalse(list.covers(westerkerk.lat, westerkerk.lon, PackList.CORE_REACH * 2))
        assertFalse(list.covers(52.0907, 5.1214, 430.0))
        // Whether the core has anything to show in a view: the ground seen meets a pack's box.
        assertTrue(list.meets(app.atlas.geo.BBox(4.92, 52.38, 4.95, 52.40)))
        assertFalse(list.meets(app.atlas.geo.BBox(5.10, 52.08, 5.14, 52.10)))
    }

    @Test
    fun theCoreIsHandedFilesAndTheAPKsRanges() {
        val assets = Assets(bundledTiles())
        val lib = PackLibrary(File(dir, "maps"), emptyList())
        lib.renderDir.mkdirs()
        // A downloaded pack of the canal ring: newer than the APK's, so it replaces it.
        File(lib.renderDir, "grachten.atlas").writeBytes(renderPack("grachten", "region", 4.86, 52.35, 4.93, 52.39, mapOf("grachten_home" to 2300)))
        val list = PackList(assets, lib)
        list.load()
        val (refs, open) = list.refs()
        assertEquals(listOf("amsterdam", "grachten"), refs.map { it.id })
        val bundled = refs[0]
        assertEquals(MapCore.PackRef.BUNDLED, bundled.kind)
        assertEquals("", bundled.path)
        assertTrue(bundled.fd >= 0 && bundled.length > 0)
        val downloaded = refs[1]
        assertEquals(MapCore.PackRef.RENDER, downloaded.kind)
        assertEquals(File(lib.renderDir, "grachten.atlas").path, downloaded.path)
        assertEquals(-1, downloaded.fd)
        assertEquals(2L, downloaded.number)
        assertEquals(1, open.size)
        open.forEach { it.close() }
        assertEquals(1, assets.closed)
    }

    @Test
    fun aFirstVisitsRenderPackIsFetchedOnceThroughTheLibrary() {
        val paris = renderPack("paris", "region", 2.30, 48.84, 2.37, 48.88, mapOf("paris_home" to 1800), tz = "Europe/Paris")
        val sha = MessageDigest.getInstance("SHA-256").digest(paris).joinToString("") { "%02x".format(it) }
        val manifest = """{"format":1,"packs":[{"id":"paris","name":"Paris","bbox":[2.30,48.84,2.37,48.88],"release":"packs-renders",
            |"files":[{"kind":"atlas","name":"paris.atlas","bytes":10,"sha256":"aa","url":"https://packs/paris.atlas"},
            |{"kind":"render","name":"paris.render.atlas","bytes":${paris.size},"sha256":"$sha","url":"https://packs/paris.render.atlas",
            |"format":"atlas-pack","version":3,"layout":"render-tiles","payload":1,"engine":"e","style":"s","source_sha256":"aa"}]}]}""".trimMargin()
        val asked = ArrayList<String>()
        val http = HttpGet { url ->
            asked += url
            val body = when (url) {
                "https://packs/packs.json" -> manifest.toByteArray()
                "https://packs/paris.render.atlas" -> paris
                else -> throw java.io.IOException("$url: 404")
            }
            body.inputStream() to body.size.toLong()
        }
        val lib = PackLibrary(File(dir, "maps"), listOf("https://packs/packs.json"), http)
        lib.refresh()
        var fetched = 0
        val list = PackList(null, lib, online = { true }, worker = { it.run() })
        list.load()
        assertTrue(list.packs.isEmpty())
        list.fetchAt(LatLon(48.86, 2.34)) { fetched++ }
        assertEquals(1, fetched)
        assertEquals(listOf("paris"), list.packs.map { it.id })
        assertEquals(ZoneId.of("Europe/Paris"), list.packs.single().zone)
        assertEquals("paris", list.choose(48.86, 2.34, 1_800.0)?.id)
        // On the phone now: nothing more to fetch.
        list.fetchAt(LatLon(48.86, 2.34)) { fetched++ }
        assertEquals(1, fetched)
        assertEquals(1, asked.count { it.endsWith("paris.render.atlas") })
        // Offline nothing is asked.
        val offline = PackList(null, lib, online = { false }, worker = { it.run() })
        offline.fetchAt(LatLon(48.86, 2.34)) { fetched++ }
        assertEquals(1, fetched)
    }

    @Test
    fun theFitIsTheLiveMapsAndProfilesAreNumberedAsTheCameraState() {
        assertEquals(0.0, PackList.fit(doubleArrayOf(430.0, 2300.0), 430.0), 0.0)
        assertEquals(kotlin.math.ln(2.0), PackList.fit(doubleArrayOf(1000.0), 2000.0), 1e-12)
        assertEquals(CameraWriter.PROFILE_LIVING, PackList.profileOf("living"))
        assertEquals(CameraWriter.PROFILE_REGION, PackList.profileOf("region"))
        assertEquals(CameraWriter.PROFILE_WORLD_BASE, PackList.profileOf("world-base"))
        assertEquals(CameraWriter.PROFILE_REGION, PackList.profileOf(null))
    }

    @Test
    fun aWindowBuiltAgainAtItsPathIsListedAgainWithItsTimeAndSize() {
        val windows = File(dir, "windows").apply { mkdirs() }
        val file = File(windows, "14-8414-5383.atlas")
        file.writeBytes(renderPack("window", "region", 4.87, 52.36, 4.90, 52.38))
        file.setLastModified(1_700_000_000_000L)
        val list = PackList(null, null, windows = { windows })
        list.load()
        val first = list.revision
        assertEquals(listOf("window/14/8414/5383"), list.packs.map { it.id })
        assertEquals(MapCore.PackRef.WINDOW, list.packs.single().kind)
        val (refs, _) = list.refs()
        assertEquals(file.path, refs.single().path)
        assertEquals(1_700_000_000_000L, refs.single().offset)
        assertEquals(file.length(), refs.single().length)
        // listed again unchanged: the same list
        list.load()
        assertEquals(first, list.revision)
        // built again: new bytes at the same path, listed anew
        file.writeBytes(renderPack("window", "region", 4.87, 52.36, 4.90, 52.38, tz = "Europe/Paris"))
        file.setLastModified(1_700_604_800_000L)
        list.load()
        assertTrue(list.revision > first)
        assertEquals(1_700_604_800_000L, list.refs().first.single().offset)
    }

    @Test
    fun aWindowThatHoldsNothingToDrawIsNotListedAndItsPlaceIsTodaysMaps() {
        // the open sea far from a coast, the middle of a field: the core would hold no tile there; a window that holds
        // its tile is listed, and so is a pack whose manifest does not count its tiles
        val windows = File(dir, "windows").apply { mkdirs() }
        File(windows, "14-8414-5383.atlas").writeBytes(renderPack("window", "region", 4.87, 52.36, 4.89, 52.38, chunks = 0))
        File(windows, "14-8415-5383.atlas").writeBytes(renderPack("window", "region", 4.89, 52.36, 4.91, 52.38, chunks = 1))
        File(windows, "14-8416-5383.atlas").writeBytes(renderPack("window", "region", 4.91, 52.36, 4.93, 52.38))
        val list = PackList(null, null, windows = { windows })
        list.load()
        assertEquals(listOf("window/14/8415/5383", "window/14/8416/5383"), list.packs.map { it.id })
        assertFalse(list.covers(52.37, 4.88, 400.0))
        assertTrue(list.covers(52.37, 4.90, 400.0))
    }

    @Test
    fun aWindowOfAnotherVersionIsNotListed() {
        // windows built at another style version (an earlier APK's), or before windows named theirs, are not listed, so
        // a frame cell's windows are of one version; where the version is not known, all list
        val windows = File(dir, "windows").apply { mkdirs() }
        File(windows, "14-8414-5383.atlas").writeBytes(renderPack("window", "region", 4.87, 52.36, 4.89, 52.38, chunks = 1, version = "core-p1 s1.1-aaaa"))
        File(windows, "14-8415-5383.atlas").writeBytes(renderPack("window", "region", 4.89, 52.36, 4.91, 52.38, chunks = 1, version = "core-p1 s1.1-bbbb"))
        File(windows, "14-8416-5383.atlas").writeBytes(renderPack("window", "region", 4.91, 52.36, 4.93, 52.38, chunks = 1))
        var version: String? = "core-p1 s1.1-bbbb"
        val list = PackList(null, null, windows = { windows }, windowVersion = { version })
        list.load()
        assertEquals(listOf("window/14/8415/5383"), list.packs.map { it.id })
        // built at the version: listed
        File(windows, "14-8414-5383.atlas").writeBytes(renderPack("window", "region", 4.87, 52.36, 4.89, 52.38, chunks = 1, version = "core-p1 s1.1-bbbb"))
        list.load()
        assertEquals(listOf("window/14/8414/5383", "window/14/8415/5383"), list.packs.map { it.id })
        version = null
        list.load()
        assertEquals(3, list.packs.size)
    }

    @Test
    fun aWindowsFileNamesItsWindow() {
        assertEquals(app.atlas.mapcore.MapCore.Window(14, 8414, 5384), Windows.windowOf(java.io.File("14-8414-5384.atlas")))
        assertEquals("window/14/8414/5384", Windows.windowOf(java.io.File("14-8414-5384.atlas"))?.id)
        assertEquals(null, Windows.windowOf(java.io.File("14-8414-5384.atlas.part")))
        assertEquals(null, Windows.windowOf(java.io.File("notes.txt")))
        assertEquals(
            app.atlas.mapcore.MapCore.Window(14, 8414, 5384),
            app.atlas.mapcore.MapCore.Window.of((14L shl 48) or (8414L shl 24) or 5384L),
        )
    }
}

