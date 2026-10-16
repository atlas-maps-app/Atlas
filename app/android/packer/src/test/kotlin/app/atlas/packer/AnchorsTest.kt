package app.atlas.packer

import app.atlas.anchor.AnchorCodec
import app.atlas.anchor.AnchorKind
import app.atlas.render.Raster
import app.atlas.render.live.EngineVersion
import app.atlas.render.live.LiveRender
import app.atlas.render.live.RegionFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The app's bundled anchors (plan-gpu G4), made twice from the bundled Westerkerk pack: the two builds are byte for
 * byte the same; every anchor is the engine's render at its camera, kind and moment, with the engine's version and the
 * pack's hash; home anchors are at most 2.5 MB and decode in at most 50 ms, the set at most 25 MB; and a render the
 * world tape changed is refused.
 */
class AnchorsTest {
    companion object {
        private val repo = File(System.getProperty("atlas.repo") ?: error("atlas.repo is not set"))
        private val pack = File(repo, "app/android/app/src/main/assets/packs/amsterdam.atlas")

        /** A slower machine (CI) gets this much more time for the speed gate. */
        private val SLACK: Double = if (System.getenv("CI") == "true") 3.0 else 1.0

        private lateinit var dirA: File
        private lateinit var dirB: File
        private lateinit var first: Anchors.Result
        private lateinit var second: Anchors.Result

        @BeforeClass
        @JvmStatic
        fun buildTwice() {
            dirA = Files.createTempDirectory("anchors-a").toFile()
            dirB = Files.createTempDirectory("anchors-b").toFile()
            // a stale file from an older set is cleared away by the build
            File(dirA, "westerkerk-old-916.anchor").writeText("old")
            first = Anchors.bundled(pack, dirA) { println(it) }
            // the second build opens the pack afresh (a new work folder, a fresh scene load), as a second Gradle run does
            second = Anchors.bundled(pack, dirB)
            println("two builds: %.1f s and %.1f s".format(first.millis / 1e3, second.millis / 1e3))
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            if (::dirA.isInitialized) dirA.deleteRecursively()
            if (::dirB.isInitialized) dirB.deleteRecursively()
        }

        private fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun twoBuildsGiveByteIdenticalAssets() {
        val a = dirA.listFiles()!!.map { it.name }.sorted()
        val b = dirB.listFiles()!!.map { it.name }.sorted()
        assertEquals(a, b)
        // the twelve anchors and their index, and nothing else (the stale file is gone)
        assertEquals((Anchors.BUNDLED.map { it.file } + Anchors.INDEX).sorted(), a)
        for (n in a) assertArrayEquals(n, File(dirA, n).readBytes(), File(dirB, n).readBytes())
        assertTrue(first.warnings.isEmpty() && second.warnings.isEmpty())
    }

    @Test
    fun theSetIsTheHomeAtFourMomentsAndTheBackdropAtTwoOnBothFrames() {
        val names = Anchors.BUNDLED.map { it.file }
        assertEquals(12, names.size)
        for (h in listOf(916, 893)) {
            for (m in listOf("sunny", "grey", "wet", "night")) assertTrue("westerkerk-$m-$h.anchor" in names)
            for (m in listOf("sunny", "night")) assertTrue("westerkerk-far-$m-$h.anchor" in names)
        }
        val index = Json.parseToJsonElement(File(dirA, Anchors.INDEX).readText()) as JsonObject
        assertEquals(EngineVersion.ID, index["engine"]!!.jsonPrimitive.content)
        assertEquals(AnchorCodec.VERSION, index["format"]!!.jsonPrimitive.content.toInt())
        assertEquals(sha256(pack.readBytes()), (index["pack"] as JsonObject)["sha256"]!!.jsonPrimitive.content)
        val listed = index["anchors"] as JsonArray
        assertEquals(names, listed.map { (it as JsonObject)["file"]!!.jsonPrimitive.content })
        for (e in listed) {
            val o = e as JsonObject
            val f = File(dirA, o["file"]!!.jsonPrimitive.content)
            assertEquals(f.name, f.length(), o["bytes"]!!.jsonPrimitive.content.toLong())
            assertEquals(f.name, sha256(f.readBytes()), o["sha256"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun homeAssetsAreSmallAndDecodeFastAndTheSetFits() {
        var total = 0L
        for (m in first.made) {
            total += m.bytes
            if (m.spec.role != Anchors.Spec.HOME) continue
            assertTrue("${m.spec.file}: ${m.bytes} bytes", m.bytes <= 2_500_000)
            val bytes = m.file.readBytes()
            repeat(5) { AnchorCodec.decode(bytes) }
            val times = LongArray(7) {
                val t0 = System.nanoTime()
                AnchorCodec.decode(bytes)
                System.nanoTime() - t0
            }.sorted()
            val median = times[times.size / 2] / 1e6
            println("%s: %.2f MB, decoded in %.1f ms (median of 7)".format(m.spec.file, m.bytes / 1e6, median))
            assertTrue("${m.spec.file} decodes in $median ms", median <= 50.0 * SLACK)
        }
        println("the set: %.2f MB".format(total / 1e6))
        assertTrue("the set is $total bytes", total <= 25_000_000)
    }

    @Test
    fun eachAnchorIsItsCamerasKindMomentAndVersions() {
        val packSha = MessageDigest.getInstance("SHA-256").digest(pack.readBytes())
        for (m in first.made) {
            val s = m.spec
            val a = AnchorCodec.decode(m.file.readBytes())
            val h = a.head
            val cam = h.camera
            assertEquals(s.file, EngineVersion.ID, a.engineVersion)
            assertArrayEquals(s.file, packSha, a.sourceHash)
            assertEquals(s.file, s.kind, h.kind)
            assertEquals(s.file, "amsterdam", h.regionId)
            assertEquals(s.file, s.moment, h.moment)
            assertEquals(s.file, 412.0, cam.width, 0.0)
            assertEquals(s.file, s.height.toDouble(), cam.height, 0.0)
            // amsterdam.py's main camera: 10 m west and 30 m north of the Westerkerk, yaw 52°, 38° down, principal point 440
            assertEquals(s.file, -10.0, cam.targetX, 1e-9)
            assertEquals(s.file, -30.0, cam.targetZ, 1e-9)
            assertEquals(s.file, 52.0, cam.yaw, 1e-9)
            assertEquals(s.file, 38.0, cam.pitch, 1e-9)
            assertEquals(s.file, 440.0, cam.cy, 1e-9)
            if (s.role == Anchors.Spec.HOME) {
                assertEquals(s.file, AnchorKind.BUNDLED, h.kind)
                // the home snaps to the living map's own view: the board's camera, exactly
                assertEquals(s.file, "amsterdam", m.view)
                assertEquals(s.file, 430.0, cam.dist, 0.0)
                assertEquals(s.file, 380.0, h.radius, 0.0)
            } else {
                assertEquals(s.file, AnchorKind.BACKDROP, h.kind)
                assertNull(s.file, m.view)
                assertEquals(s.file, 1300.0, cam.dist, 1e-6)
                // the ladder stands the radius back with the distance: the backdrop holds the buildings beyond the home's
                assertTrue(s.file, h.radius > 1000.0)
            }
            assertTrue(s.file, a.points.size > 50_000 && a.prims.size > 50_000 && a.items.size > 1_000)
        }
    }

    @Test
    fun theHomeRendersAreTheEngineVersionsFixedOutputs() {
        // at 412 × 916 the home is EngineVersion's fixed Westerkerk render, so the bundled set checks the version too
        val homes = first.made.filter { it.spec.role == Anchors.Spec.HOME && it.spec.height == 916 }
        assertEquals(4, homes.size)
        for (m in homes) assertEquals(m.spec.file, EngineVersion.OUTPUTS["westerkerk-${m.spec.moment}"], m.renderMd5)
    }

    @Test
    fun aRenderTheTapeChangedIsRefused() {
        fun render(g: String, raster: Raster? = null) = LiveRender(
            LinkedHashMap(), "amsterdam", "sunny", "sunny", g, "<svg>o</svg>", null, raster, 0.0, 0, 0, RegionFrame.living(),
        )
        val ok = Anchors.checkTape(render("<svg>g</svg>"), render("<svg>g</svg>"), "same")
        assertEquals(EngineVersion.md5(listOf("<svg>g</svg>", "<svg>o</svg>", "")), ok)
        val e = assertThrows(IllegalArgumentException::class.java) { Anchors.checkTape(render("<svg>g</svg>"), render("<svg>h</svg>"), "x.anchor") }
        assertTrue(e.message!!, e.message!!.contains("x.anchor") && e.message!!.contains("refused"))
        val r1 = Raster("jpg", byteArrayOf(1, 2, 3), null)
        val r2 = Raster("jpg", byteArrayOf(1, 2, 4), null)
        assertEquals(ok, Anchors.checkTape(render("<svg>g</svg>", r1), render("<svg>g</svg>", Raster("jpg", byteArrayOf(1, 2, 3), null)), "same raster"))
        assertThrows(IllegalArgumentException::class.java) { Anchors.checkTape(render("<svg>g</svg>", r1), render("<svg>g</svg>", r2), "raster") }
        assertThrows(IllegalArgumentException::class.java) { Anchors.checkTape(render("<svg>g</svg>", r1), render("<svg>g</svg>"), "no raster") }
    }

    @Test
    fun aHomeWhoseRenderIsNotTheDeclaredOutputIsReportedStale() {
        val sunny916 = Anchors.BUNDLED.first { it.file == "westerkerk-sunny-916.anchor" }
        val declared = EngineVersion.OUTPUTS.getValue("westerkerk-sunny")
        assertNull(Anchors.stale("amsterdam", sunny916, "amsterdam", declared))
        val w = Anchors.stale("amsterdam", sunny916, "amsterdam", "0".repeat(32))
        assertTrue(w, w!!.contains("westerkerk-sunny") && w.contains(declared) && w.contains("stale"))
        // renders that are not fixed outputs are not checked: the other frame, the backdrop, another region
        assertNull(Anchors.stale("amsterdam", Anchors.BUNDLED.first { it.file == "westerkerk-sunny-893.anchor" }, "amsterdam", "0".repeat(32)))
        assertNull(Anchors.stale("amsterdam", Anchors.BUNDLED.first { it.file == "westerkerk-far-sunny-916.anchor" }, null, "0".repeat(32)))
        assertNull(Anchors.stale("grachten", sunny916, "amsterdam", "0".repeat(32)))
    }

    @Test
    fun theAnchorFormatIsTheOneTheEngineVersionNames() {
        assertEquals(AnchorCodec.VERSION, EngineVersion.ANCHOR_FORMAT)
        assertTrue(EngineVersion.ID.startsWith("a${AnchorCodec.VERSION}-"))
    }

    @Test
    fun theCommandLine() {
        assertThrows(UsageException::class.java) { Anchors.run(listOf("anchors"), {}) }
        val g13 = assertThrows(UsageException::class.java) { Anchors.run(listOf("anchors", "--renders"), {}) }
        assertTrue(g13.message!!, g13.message!!.contains("G13"))
        assertThrows(UsageException::class.java) { Anchors.run(listOf("anchors", "--bundled", File(dirA, "none.atlas").path, dirA.path), {}) }
        assertThrows(UsageException::class.java) { Anchors.run(listOf("anchors", "--bundled", pack.path), {}) }
        assertThrows(UsageException::class.java) { Anchors.run(listOf("anchors", "--bundled", "--fast", pack.path, dirA.path), {}) }
        assertThrows(UsageException::class.java) { Anchors.run(listOf("anchors", "--bundled", pack.path, dirA.path, "--work"), {}) }
    }
}
