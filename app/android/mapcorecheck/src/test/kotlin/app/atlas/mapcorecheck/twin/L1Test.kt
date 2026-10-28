package app.atlas.mapcorecheck.twin

import app.atlas.anchor.AnchorCases
import app.atlas.geo.BBox
import app.atlas.mapcore.state.CameraWriter
import app.atlas.mapcore.state.ThemeBlock
import app.atlas.mapcorecheck.frame.FrameHost
import app.atlas.mapview.svg.SvgParser
import app.atlas.render.Amsterdam
import app.atlas.render.Regions
import app.atlas.render.data.PackReader
import app.atlas.render.live.EngineVersion
import app.atlas.render.live.LiveCamera
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.LiveView
import app.atlas.render.live.Moment
import app.atlas.render.py.Dict
import app.atlas.rendertiles.compile.SceneToTiles
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
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
 * Cases: the Westerkerk home and the Main board at S1 (sunny and wet), and the smoke tier's eight sweep cameras; on the
 * Mac, where their element packs are (CI has the canal ring's alone), the port's and Schiphol's close views by day and
 * at night and their overviews by day, both sides without the regions' height survey, which the tiles cannot carry yet
 * (SceneToTiles.withoutSurvey: the port draws them with the generic water levels, no tides and no relief), from render
 * packs the core's stage P cuts here.
 */
class L1Test {
    private val tiles = File(System.getProperty("atlas.tiles") ?: error("atlas.tiles names no folder of bundled packs"))

    /**
     * Differences found and not yet resolved, each named with where it is followed (ENGINE.md): by case and layer, the
     * twin's extra elements' keys as L1Diff reports them, which pass as known (and are printed) while nothing else
     * differs there. None today: the one there was (OUDEZIJDS VOORBURGWAL at the canal ring's first sweep camera) was
     * the key's, a matrix entry the port prints as -0.000 (L1Diff's transform keys drop a printed zero's sign now).
     */
    private val known: Map<String, Set<String>> = mapOf()

    /**
     * Matches out of the port's order that are known, named in ENGINE.md: by case and layer, `<key> passes <key>` as
     * L1Diff counts them. Schiphol's close view: regions.py draws each land kind as one path, in the order of its first
     * ring drawn, and draws every ring that reaches within 600 px of the frame, while the cover draws the tiles within
     * 40 px; a grass ring 40 to 600 px beyond the frame, in a tile the cover leaves out, puts the port's grass before its
     * park (the cover at the renders' 900 px matches it, at two to three times the tiles drawn).
     */
    private val knownOrder: Map<String, Set<String>> = mapOf(
        "schiphol_close sunny S1 ground" to setOf("path; fill #ffb2ca90 1.000; stroke none passes path; fill #ffb8cc96 1.000; stroke none"),
        "schiphol_close night S1 ground" to setOf("path; fill #ff24281d 1.000; stroke none passes path; fill #ff25291e 1.000; stroke none"),
    )

    private class Case(val name: String, val pack: String, val file: () -> File, val region: () -> LiveRegion?, val view: (LiveRegion) -> LiveView, val moment: String)

    /** The regions beyond the bundled packs, their views and moments. */
    private val regions = listOf(
        "port" to listOf("port_close" to "sunny", "port_close" to "night", "port" to "sunny"),
        "schiphol" to listOf("schiphol_close" to "sunny", "schiphol_close" to "night", "schiphol" to "sunny"),
        "efteling" to listOf("efteling" to "sunny", "efteling" to "night", "efteling_joris" to "sunny"),
        "artis" to listOf("artis" to "sunny", "artis_gorilla" to "sunny", "artis_gorilla" to "night"),
    )

    /** The regions whose survey the tiles cannot carry yet: drawn without it, by both sides. */
    private val surveyed = setOf("port", "schiphol")
    private val work: File by lazy { Files.createTempDirectory("atlas-l1-regions").toFile().also { d -> Runtime.getRuntime().addShutdownHook(Thread { d.deleteRecursively() }) } }
    private val made = HashMap<String, Pair<File, LiveRegion>>()
    private val wholeWoods = HashMap<String, List<Dict>>()

    /** Region [id]'s render pack, cut by the core's stage P (or the packer, as the same bytes) without its survey, and its region for the port, alike. */
    @Synchronized
    private fun surveyless(id: String, element: File): Pair<File, LiveRegion> = made.getOrPut(id) {
        AnchorCases.setUp()
        val tool = System.getProperty("atlas.stagep")?.let(::File)?.takeIf { it.canExecute() }
        val pack = File(work, "$id.atlas")
        // without its survey where the tiles cannot carry it; a park's or a zoo's from its pack itself (its rides' profiles)
        val survey = id !in surveyed
        SceneToTiles.pack(element, pack, File(work, "engine-$id"), core = tool, survey = survey) { println("L1 $id: $it") }
        pack to PackReader.open(element).use {
            if (survey) LiveEngine.open(it, "${element.length()}") else LiveEngine.open(SceneToTiles.withoutSurvey(it), "${element.length()} without its survey")
        }
    }

    private fun cases(): List<Case> {
        val w = File(tiles, "amsterdam.atlas")
        val g = File(tiles, "grachten.atlas")
        val out = arrayListOf(
            Case("home sunny S1", "bundled", { w }, { AnchorCases.westerkerk() }, { r -> AnchorCases.homeView(r, 916) }, "sunny"),
            Case("home wet S1", "bundled", { w }, { AnchorCases.westerkerk() }, { r -> AnchorCases.homeView(r, 916) }, "wet"),
            // the golden moment: its wash over the haze (ENGINE.md step 4, the composite's steps)
            Case("home golden S1", "bundled", { w }, { AnchorCases.westerkerk() }, { r -> AnchorCases.homeView(r, 916) }, "golden"),
            // the doors and their numbers (ENGINE.md step 4, the facades), which draw with weather within 75 m, their plates
            // within 45 m: a row of facades faced squarely from 50 m (25 m up) by day and on the wet night, and from the map's
            // nearest zoom at the gestures' steepest tilt, where the doors show and their plates lie below the frame
            Case("doors sunny S1", "bundled", { w }, { AnchorCases.westerkerk() }, { r -> doorsView(r, 82.21656426574002, -192.11612399270084, 60.0, 50.0) }, "sunny"),
            Case("doors wet S1", "bundled", { w }, { AnchorCases.westerkerk() }, { r -> doorsView(r, 82.21656426574002, -192.11612399270084, 60.0, 50.0) }, "wet"),
            Case("doors far sunny S1", "bundled", { w }, { AnchorCases.westerkerk() }, { r -> doorsView(r, 98.86787550282801, -182.6922473097533, 70.0, 91.0) }, "sunny"),
        )
        if (g.isFile) {
            out += Case("board sunny S1", "grachten", { g }, { AnchorCases.grachten() }, { r -> knot(r, "grachten_home", 916) }, "sunny")
        }
        for (k in 0 until 4) {
            val own = if (k % 2 == 0) "sunny" else "night"
            out += Case("sweep $k $own S1", "bundled", { w }, { AnchorCases.westerkerk() }, { r -> sweep(24, r.bbox!!, 916, 9)[k] }, own)
            if (g.isFile) out += Case("canal sweep $k $own S1", "grachten", { g }, { AnchorCases.grachten() }, { r -> sweep(12, r.bbox!!, 916, 9)[k] }, own)
        }
        for ((id, views) in regions) {
            val element = File(AnchorCases.repo, "cache/packs/regions/$id.atlas")
            if (!element.isFile) continue
            for ((view, moment) in views) {
                out += Case("$view $moment S1", id, { surveyless(id, element).first }, { surveyless(id, element).second }, { r -> knot(r, view, 916) }, moment)
            }
        }
        return out
    }

    /**
     * The Westerkerk's densest row of doors (115 within 40 m about its door at (70.16, -198.94), the walls facing west-north-west),
     * looked at squarely: the target ([x], [z]) behind it, the map's [pitch], [dist] metres back.
     */
    private fun doorsView(r: LiveRegion, x: Double, z: Double, pitch: Double, dist: Double, height: Int = 916): LiveView {
        val lat = r.frame.lat(x, z)
        val lon = r.frame.lon(x, z)
        return LiveView(lat, lon, LiveCamera.zoom(lat, dist, height.toDouble()), LiveCamera.bearing(119.50786615381202), pitch, 412, height, 440.0)
    }

    /**
     * Whether an element of [key] is a door's (amsterdam.py's facade_details): its path, stroked in the outline's colour, its
     * stoop's, its plate's rect or its number's text, by day or at night.
     */
    private fun doorKey(key: String): Boolean = "#ffe8e2d6" in key || "Georgia" in key ||
        listOf("path; fill #ff9c9488 ", "path; fill #ff22232a ", "rect; fill #fff2eee4 ", "rect; fill #ff3a3a40 ").any { key.startsWith(it) }

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
        // a park's or a zoo's woods as the engine plants them, once for the region: the whole region's, which its pack
        // carries (the renders plant about each view, in the scene loaded for it)
        val woods = wholeWoods.getOrPut(region.id) {
            Regions.woodsPlanting = null
            @Suppress("UNCHECKED_CAST")
            (LiveEngine.loadScene(region).S["woods"] as List<Dict>?) ?: emptyList()
        }
        Regions.woodsPlanting = { _ -> woods }
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
            val bytes = FrameHost.twin(arrayOf("${case.pack}=${case.file().path}"), case.pack, camera(case.view(region)), theme, false)
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
                if (layer == TwinCodec.OBJECTS) {
                    // the doors: every one of the port's in the view the twin's, and the door cases show some
                    val inView = portElements.count { doorKey(it.key) } - r.outsideByKey.filterKeys { doorKey(it) }.values.sum()
                    val missingDoors = r.missing.filterKeys { doorKey(it) }
                    println("  doors: $inView of the port's door elements in the view, ${missingDoors.values.sum()} missing")
                    if (missingDoors.isNotEmpty()) failed += "${case.name}: door elements the twin does not draw: $missingDoors"
                    if (case.name.startsWith("doors") && inView == 0) failed += "${case.name}: the port draws no door in the view"
                }
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
                val allowed = known["${case.name} $name"].orEmpty()
                for (k in r.extra.keys.filter { it in allowed }) println("  known: $k (ENGINE.md, step 2)")
                val extra = r.extra.filterKeys { it !in allowed }
                if (extra.isNotEmpty()) failed += "${case.name} $name: elements the twin draws that the port does not: $extra"
                if (r.differs > 0) failed += "${case.name} $name: matched elements whose vertices differ: ${r.firstDiffering}"
                val order = knownOrder["${case.name} $name"].orEmpty()
                for (k in r.outOfOrderByKeys.keys.filter { it in order }) println("  known out of order: $k (ENGINE.md, the cover's margin)")
                if (r.outOfOrderByKeys.keys.any { it !in order }) failed += "${case.name} $name: elements out of the port's order: ${r.firstOutOfOrder}"
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
        val bytes = FrameHost.twin(arrayOf("bundled=${case.file().path}"), "bundled", camera(case.view(region)), theme, false)
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
