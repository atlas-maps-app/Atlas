package app.atlas.render.live

import app.atlas.render.Amsterdam
import app.atlas.render.Regions
import app.atlas.render.data.PackReader
import app.atlas.render.py.PyCompat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The live map's engine against the engine run as the renders ran it: the same region from Atlas's own pack, under a
 * camera from the map's terms (target, zoom, bearing, pitch, padding), must give the same SVG byte for byte.
 *
 * The Westerkerk's living map runs everywhere (its pack is the app's bundled asset, its extract is in git); the canal
 * ring's board (Main) needs the grachten pack and extract, which live on the owner's machine only.
 */
class LiveEngineTest {
    companion object {
        val repo = File(System.getProperty("atlas.repo") ?: "../../..").canonicalFile
        val bundled = File(repo, "app/android/app/src/main/assets/packs/amsterdam.atlas")
        val grachtenPack = File(repo, "cache/packs/regions/grachten.atlas")
        val grachtenDir = File(repo, "data/regions/grachten")

        @JvmStatic
        @BeforeClass
        fun setUp() {
            PyCompat.minor = 9
            LiveEngine.workDir = Files.createTempDirectory("live").toFile()
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            LiveEngine.forget()
            LiveEngine.workDir.deleteRecursively()
        }

        fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** The map view of the Main board: the grachten home camera in the map's terms. */
        fun boardView(frame: RegionFrame): LiveView {
            val lat = frame.lat(-177.0, -521.0)
            val lon = frame.lon(-177.0, -521.0)
            return LiveView(lat, lon, LiveCamera.zoom(lat, 2300.0, 915.0), 52.0, 46.0, 412, 915, 440.0)
        }
    }

    @Test
    fun `the living map from the bundled pack equals the living map from its extract`() {
        assumeTrue("the bundled pack", bundled.isFile)
        val region = PackReader.open(bundled).use { LiveEngine.open(it, "bundled") }
        assertEquals(LiveRegion.Kind.LIVING, region.kind)
        // amsterdam.py's main camera, in the map's terms
        val f = region.frame
        val lat = f.lat(-10.0, -30.0)
        val lon = f.lon(-10.0, -30.0)
        val view = LiveView(lat, lon, LiveCamera.zoom(lat, 430.0, 915.0), 52.0, 52.0, 412, 915, 440.0)
        val t0 = System.nanoTime()
        val live = LiveEngine.render(region, view, Moment.Named("sunny"))!!
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals("amsterdam", live.view)
        // the engine as amsterdam.py's main runs it, from data/ams/raw.json
        Regions.resetPlace()
        val S = Amsterdam.load(File(repo, "data/ams/raw.json"))
        val cfg = LinkedHashMap(ViewLadder.livingView())
        val ref = Amsterdam.render(S, "sunny", cfg, null, layer = "split")
        assertEquals("ground", md5(ref.svg), md5(live.g))
        assertEquals("objects", md5(ref.objs!![0]), md5(live.o))
        assertEquals(ref.objs!!.getOrNull(1)?.let(::md5), live.o2?.let(::md5))
        println("LIVE living map: load ${live.loadMillis} ms, render ${live.renderMillis} ms (all $ms ms), ${live.g.length + live.o.length} chars, scene ${LiveEngine.sceneBytes / 1_000_000} MB")
    }

    @Test
    fun `a kept scene renders any camera as a fresh load does`() {
        assumeTrue("the bundled pack", bundled.isFile)
        val region = PackReader.open(bundled).use { LiveEngine.open(it, "bundled") }
        val f = region.frame
        fun at(x: Double, z: Double, zoom: Double, bearing: Double, pitch: Double): LiveView {
            val lat = f.lat(x, z)
            val lon = f.lon(x, z)
            return LiveView(lat, lon, zoom, bearing, pitch, 412, 915, 440.0)
        }
        val a = at(40.0, -60.0, 17.2, 20.0, 50.0)
        val b = at(-150.0, 120.0, 16.6, 300.0, 35.0)
        LiveEngine.forget()
        val first = LiveEngine.render(region, a, Moment.Named("grey"))!!
        val other = LiveEngine.render(region, b, Moment.Named("sunny"))!!
        val again = LiveEngine.render(region, a, Moment.Named("grey"))!!
        assertNull(first.view)
        assertNotEquals(md5(first.g), md5(other.g))
        assertEquals(md5(first.g), md5(again.g))
        assertEquals(md5(first.o), md5(again.o))
        // and a fresh load gives it too
        LiveEngine.forget()
        val fresh = LiveEngine.render(region, a, Moment.Named("grey"))!!
        assertEquals(md5(first.g), md5(fresh.g))
        assertEquals(md5(first.o), md5(fresh.o))
        assertEquals(1, LiveEngine.sceneCount)
    }

    @Test
    fun `the Main board from the grachten pack is byte-identical to today's Python`() {
        assumeTrue("the grachten pack and extract are local only", grachtenPack.isFile && File(grachtenDir, "raw.json").isFile)
        LiveEngine.forget()
        val region = PackReader.open(grachtenPack).use { LiveEngine.open(it, "${grachtenPack.length()}") }
        val view = boardView(region.frame)
        val rt = Runtime.getRuntime()
        val t0 = System.nanoTime()
        val live = LiveEngine.render(region, view, Moment.Named("sunny"))!!
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals("grachten_home", live.view)
        assertEquals(0.7, live.tolerance, 0.0)
        // a fresh run of today's Python (RegionsTest's oracle), when it is newer than the scripts
        val oracle = File(repo, "cache/oracle/regions/grachten_home.json")
        val newest = (File(repo, "render").listFiles { x -> x.name.endsWith(".py") }!!.toList() + File(repo, "data/regions/places.py")).maxOf { it.lastModified() }
        if (oracle.isFile && oracle.lastModified() > newest) {
            val files = Json.parseToJsonElement(oracle.readText()).jsonObject["files"] as JsonObject
            for ((layer, svg) in listOf("g" to live.g, "o" to live.o, "o2" to live.o2!!)) {
                val want = files["grachten_home__sunny__$layer.svg"]!!.jsonObject["md5"]!!.jsonPrimitive.content
                assertEquals("grachten_home sunny $layer against today's Python", want, md5(svg))
            }
            println("LIVE board: g, o, o2 equal today's Python (cache/oracle/regions/grachten_home.json)")
        }
        // the engine as regions.py runs it, from the renders' own folder
        Regions.root = File(repo, "data/regions")
        Regions.parkRoot = File(repo, "data/park")
        val S = Regions.load("grachten_home")
        val r = Regions.render(S, "sunny", Regions.cfgFor("grachten_home"))
        assertEquals(md5(r.g), md5(live.g))
        assertEquals(md5(r.o), md5(live.o))
        assertEquals(md5(r.o2!!), md5(live.o2!!))
        val board = File(repo, "cache/render/grachten_home_sunny_g.svg")
        if (board.isFile) println("LIVE board: g ${if (md5(board.readText()) == md5(live.g)) "equals" else "differs from"} the 9 October render on the board")
        println("LIVE board: load ${live.loadMillis} ms, render ${live.renderMillis} ms (all $ms ms); scene ${LiveEngine.sceneBytes / 1_000_000} MB; heap max ${rt.maxMemory() / 1_000_000} MB")
    }

    @Test
    fun `the canal ring between its views renders at a band, and the band scene is reused`() {
        assumeTrue("the grachten pack is local only", grachtenPack.isFile)
        val region = PackReader.open(grachtenPack).use { LiveEngine.open(it, "${grachtenPack.length()}") }
        val f = region.frame
        val lat = f.lat(-300.0, -400.0)
        val lon = f.lon(-300.0, -400.0)
        val v = LiveView(lat, lon, LiveCamera.zoom(lat, 1900.0, 915.0), 40.0, 45.0, 412, 915, 440.0)
        val a = LiveEngine.render(region, v, Moment.Named("grey"))!!
        assertNull(a.view)
        assertEquals(0.6, a.tolerance, 0.0)
        val b = LiveEngine.render(region, v.copy(bearing = 60.0), Moment.Named("grey"))!!
        assertTrue("the band scene is kept", b.loadMillis < 100)
        assertTrue(LiveEngine.sceneCount <= LiveEngine.MAX_SCENES)
        println("LIVE band 0.6: load ${a.loadMillis} ms, render ${a.renderMillis} ms; again ${b.loadMillis} ms load, ${b.renderMillis} ms render; scenes ${LiveEngine.sceneCount}, ${LiveEngine.sceneBytes / 1_000_000} MB")
    }
}
