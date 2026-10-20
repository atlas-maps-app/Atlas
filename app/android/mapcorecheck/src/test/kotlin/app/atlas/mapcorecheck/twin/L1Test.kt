package app.atlas.mapcorecheck.twin

import app.atlas.anchor.AnchorCases
import app.atlas.geo.BBox
import app.atlas.mapcore.state.CameraWriter
import app.atlas.mapcore.state.ThemeBlock
import app.atlas.mapcorecheck.frame.FrameHost
import app.atlas.mapview.svg.SvgParser
import app.atlas.render.Amsterdam
import app.atlas.render.live.EngineVersion
import app.atlas.render.live.LiveCamera
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.LiveView
import app.atlas.render.live.Moment
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.Random
import kotlin.concurrent.withLock

/**
 * L1, primitive parity (plan-vector D16; K7's framework): at a case's camera, the twin's stream (every registered
 * pass's emit, `CP/frame/FrameBuilder.cpp`'s emit over the world of the case's render pack) against the port's own SVG
 * at the same camera, element by element in the view (L1Diff). The landed features must match: every element the twin
 * emits in the view matches one of the port's (fills in pieces included, listed by key), exact or one print step
 * apart, and none passes an element it overlaps out of the port's order; the port's elements the twin does not draw
 * yet (the features of the packages to come) are listed by key, with counts, and those outside the view are counted.
 *
 * Cases: the Westerkerk home and the Main board at S1 (sunny and wet), and the smoke tier's eight sweep cameras.
 */
class L1Test {
    private val tiles = File(System.getProperty("atlas.tiles") ?: error("atlas.tiles names no folder of bundled packs"))

    private class Case(val name: String, val pack: String, val file: File, val region: () -> LiveRegion?, val view: (LiveRegion) -> LiveView, val moment: String)

    private fun cases(): List<Case> {
        val w = File(tiles, "amsterdam.atlas")
        val g = File(tiles, "grachten.atlas")
        val out = arrayListOf(
            Case("home sunny S1", "bundled", w, { AnchorCases.westerkerk() }, { r -> AnchorCases.homeView(r, 916) }, "sunny"),
            Case("home wet S1", "bundled", w, { AnchorCases.westerkerk() }, { r -> AnchorCases.homeView(r, 916) }, "wet"),
        )
        if (g.isFile) {
            out += Case("board sunny S1", "grachten", g, { AnchorCases.grachten() }, { r -> knot(r, "grachten_home", 916) }, "sunny")
        }
        for (k in 0 until 4) {
            val own = if (k % 2 == 0) "sunny" else "night"
            out += Case("sweep $k $own S1", "bundled", w, { AnchorCases.westerkerk() }, { r -> sweep(24, r.bbox!!, 916, 9)[k] }, own)
            if (g.isFile) out += Case("canal sweep $k $own S1", "grachten", g, { AnchorCases.grachten() }, { r -> sweep(12, r.bbox!!, 916, 9)[k] }, own)
        }
        return out
    }

    private fun knot(r: LiveRegion, name: String, height: Int): LiveView {
        val k = r.ladder.knots.firstOrNull { it.name == name } ?: error("region ${r.id} has no view $name")
        return EngineVersion.knotView(r.frame, k, 412, height)
    }

    private fun sweep(n: Int, box: BBox, height: Int, seed: Long): List<LiveView> {
        val r = Random(seed)
        return List(n) {
            val lat = box.south + r.nextDouble() * box.height
            val lon = box.west + r.nextDouble() * box.width
            val dist = 91.0 * StrictMath.pow(4000.0 / 91.0, r.nextDouble())
            val bearing = r.nextDouble() * 360.0
            val pitch = r.nextDouble() * 70.0
            val cy = if (r.nextBoolean()) 440.0 else 457.5
            LiveView(lat, lon, LiveCamera.zoom(lat, dist, height.toDouble()), bearing, pitch, 412, height, cy)
        }
    }

    /** The camera state the map writes for [v] on S1 (1080 px wide), at rest in engine mode. */
    private fun camera(v: LiveView): ByteBuffer {
        val density = 1080 / 412.0
        val b = CameraWriter.allocate()
        CameraWriter.header(b, 1L, CameraWriter.REST or CameraWriter.ENGINE_MODE)
        CameraWriter.view(b, v.lat, v.lon, v.bearing, v.pitch, v.distance, v.widthDp / 2.0, v.cyDp)
        CameraWriter.frame(b, Math.round(v.widthDp * density).toInt(), Math.round(v.heightDp * density).toInt(), v.widthDp, v.heightDp, density)
        CameraWriter.pack(b, 0L, CameraWriter.PROFILE_NONE)
        CameraWriter.revisions(b, 0, 0, 0)
        CameraWriter.keepOuts(b, 0)
        return b
    }

    /** The port's render of [case] (its layers parsed) and the theme block of the moment it drew. */
    private fun port(case: Case, region: LiveRegion): Pair<List<List<app.atlas.mapview.svg.SvgDocument>>, ByteBuffer> = LiveEngine.lock.withLock {
        val r = LiveEngine.render(region, case.view(region), Moment.Named(case.moment)) ?: error("${case.name}: the render was cancelled")
        val th = Amsterdam.THEMES.getValue(r.themeKey)
        val (az, el, sun) = Amsterdam.sunOf(th)
        val b = ThemeBlock.allocate()
        ThemeBlock.write(b, th, doubleArrayOf(sun.x, sun.y, sun.z), az, el, r.themeKey == "golden", Amsterdam.TONE)
        val ground = listOf(SvgParser.parse(r.g))
        val objects = listOfNotNull(r.o, r.o2).map { SvgParser.parse(it) }
        listOf(ground, objects) to b
    }

    @Test
    fun theTwinsElementsAreThePortsAtEveryCase() {
        var matched = 0
        var cases = 0
        val failed = ArrayList<String>() // every case's failures, so one run shows them all
        for (case in cases()) {
            val region = case.region() ?: continue
            val (docs, theme) = port(case, region)
            val bytes = FrameHost.twin(arrayOf("${case.pack}=${case.file.path}"), case.pack, camera(case.view(region)), theme, false)
                ?: throw AssertionError("${case.name}: the twin failed: ${FrameHost.lastError()}")
            val twin = TwinCodec.decode(bytes)
            println("L1 ${case.name}: ${FrameHost.lastNotes()}")
            val view = docs[0].first().viewBox
            val spread = L1Diff.filterSpread(docs.flatten())
            for ((layer, name) in listOf(TwinCodec.GROUND to "ground", TwinCodec.OBJECTS to "objects")) {
                val portElements = L1Diff.portElements(docs[if (layer == TwinCodec.GROUND) 0 else 1], structural = true)
                val twinElements = L1Diff.twinElements(twin, layer, spread)
                // what stands up: its slivers (faces at the renders' area floor, or seen edge-on) counted apart, at most two
                // in a thousand
                val r = L1Diff.diff("${case.name} $name", portElements, twinElements, view, slivers = layer == TwinCodec.OBJECTS)
                println(r.summary())
                for ((k, n) in r.missing.entries.sortedByDescending { it.value }.take(8)) println("  missing $n × $k")
                for ((k, n) in r.extra.entries.sortedByDescending { it.value }.take(8)) println("  extra $n × $k")
                for ((k, n) in r.piecesByKey.entries.sortedByDescending { it.value }.take(8)) println("  in pieces $n × $k")
                for (d in r.firstDiffering) println("  differs: $d")
                for (d in r.firstExtra) println("  extra: $d")
                for (d in r.firstSlivers) println("  sliver: $d")
                if (r.extra.isNotEmpty()) for (d in r.firstMissing) println("  missing: $d")
                for (d in r.firstOutOfOrder) println("  out of order: $d")
                // Every element the twin draws in the view is the port's, in its order where it overlaps another; vertices
                // exact or one print step apart.
                if (r.extra.isNotEmpty()) failed += "${case.name} $name: elements the twin draws that the port does not: ${r.extra}"
                if (r.differs > 0) failed += "${case.name} $name: matched elements whose vertices differ: ${r.firstDiffering}"
                if (r.outOfOrder > 0) failed += "${case.name} $name: elements out of the port's order: ${r.firstOutOfOrder}"
                if (r.slivers > maxOf(4, r.twin / 500)) failed += "${case.name} $name: ${r.slivers} slivers, over two in a thousand: ${r.firstSlivers}"
                matched += r.matched
            }
            cases++
        }
        println("L1: $cases cases, $matched elements of the twin matched")
        assertTrue(cases >= 2)
        assertTrue("L1 failed in ${failed.size} places:\n${failed.joinToString("\n")}", failed.isEmpty())
    }

    @Test
    fun theStreamRoundTrips() {
        val region = AnchorCases.westerkerk()
        val case = cases().first()
        val (_, theme) = port(case, region)
        val bytes = FrameHost.twin(arrayOf("bundled=${case.file.path}"), "bundled", camera(case.view(region)), theme, false)
            ?: throw AssertionError("the twin failed: ${FrameHost.lastError()}")
        val doc = TwinCodec.decode(bytes)
        // the frame's root clip and group, then the composite's haze at least
        assertTrue(doc.clips.any { it.id == "vb" })
        assertTrue(doc.groups.isNotEmpty())
        assertTrue("the twin draws the haze", doc.gradients.any { it.id == "haze" })
        val bad = bytes.copyOf(bytes.size - 3)
        val refused = runCatching { TwinCodec.decode(bad) }.exceptionOrNull()
        assertTrue("a stream cut short is refused", refused != null)
    }
}
