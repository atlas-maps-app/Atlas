package app.atlas.mapcorecheck.buildings

import app.atlas.anchor.AnchorCases
import app.atlas.geo.BBox
import app.atlas.render.Amsterdam
import app.atlas.render.City
import app.atlas.render.V3
import app.atlas.render.live.EngineVersion
import app.atlas.render.live.LiveCamera
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.LiveView
import app.atlas.render.live.Moment
import app.atlas.render.live.WorldTape
import app.atlas.rendertiles.model.Keep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.concurrent.withLock

/**
 * Stage T of the buildings in the core's host build (`CP/jni/TestBuild.cpp`, plan-vector K10), loaded from
 * `-Datlas.mapcore.host`.
 */
internal object BuildHost {
    /** The layout version of these entry points; a library of another is refused. */
    const val ABI = 1

    init {
        val path = System.getProperty("atlas.mapcore.host") ?: error("atlas.mapcore.host names no host build of the core")
        require(File(path).isFile) { "no host build of the core at $path" }
        System.load(path)
        check(abi() == ABI) { "the host build's stage-T entry points are version ${abi()}, these tests' $ABI" }
    }

    @JvmStatic external fun abi(): Int

    /**
     * Stage T of every tile of the pack at [path] (its finest zoom) at ladder [level]: a record per primitive (an n-gon's
     * triangles but) and per n-gon, each `what` (0 a primitive, 1 an n-gon), the building's OSM id, its info (bit 0 a
     * part, bit 1 a relation's id, bits 8-15 its kind, bits 16-31 its flags), its face's base colour (bit 24: lit by day),
     * its normal's bits, its corners' count and their x, y, z bits; null when the pack cannot be read.
     */
    @JvmStatic external fun prims(path: String, level: Int): LongArray?
}

/**
 * Stage T held to the port (plan-vector L0 (c), K10): every polygon the port's renderer gives `face()` for a building or a
 * part at the boards' views (the Westerkerk home at its four moments, the Main board, the smoke tier's sweeps) is one of
 * the core's primitives or n-gons of that building, its corners equal within 10⁻⁶ m in the port's order, its fill the
 * port's own light of the core's face (base colour and normal) lit or unlit, and its stroke the one the core's flags
 * give it (a cell's same-colour .3 px edge, a roof's .25 or .45 px edge, a cabin roof's .4 px, or none).
 *
 * The port's calls come through the test-only tap on `Amsterdam.face`, and the building each one draws from the world
 * tape (the item being drawn and its owner); the core's records from the bundled render packs (`-Datlas.tiles`) at the
 * level the render loaded. Hatched and scaffolded walls (a quest's, a construction's: `url(#…)` fills) are not stage T's.
 */
class StageTParityTest {
    private val tiles = File(System.getProperty("atlas.tiles") ?: error("atlas.tiles names no folder of bundled packs"))
    private val westerkerkPack = File(tiles, "amsterdam.atlas")
    private val grachtenPack = File(tiles, "grachten.atlas")

    /** One render of the port: the region, the view, the moment. */
    private class Case(val name: String, val pack: File, val region: () -> LiveRegion?, val view: (LiveRegion) -> LiveView, val moment: String)

    /** One face() call: the tape's item being drawn and its owner then, the points' x, y, z, the fill and the extra attributes. */
    private class Call(val item: Int, val owner: Any?, val q: DoubleArray, val fill: String, val extra: String)

    /** The port's render: its calls, the tape, the theme drawn and the level loaded. */
    private class Rendered(val calls: List<Call>, val tape: WorldTape, val th: Map<String, Any?>, val level: Int)

    /** The world tape's owner of what is being drawn (its private field: the tape has no reader for it). */
    private val currentObject = WorldTape::class.java.getDeclaredField("currentObject").apply { isAccessible = true }

    private fun render(case: Case, region: LiveRegion): Rendered {
        val view = case.view(region)
        val calls = ArrayList<Call>()
        val tape = WorldTape()
        return LiveEngine.lock.withLock {
            Amsterdam.faceTap = Amsterdam.FaceTap { q, fill, extra ->
                val t = WorldTape.active()
                if (t != null) {
                    val p = DoubleArray(q.size * 3)
                    for ((i, v) in q.withIndex()) {
                        p[3 * i] = v.x; p[3 * i + 1] = v.y; p[3 * i + 2] = v.z
                    }
                    calls += Call(t.items.size, currentObject.get(t), p, fill, extra)
                }
            }
            val r = try {
                WorldTape.recording(tape) { LiveEngine.render(region, view, Moment.Named(case.moment)) } ?: error("${case.name}: the render was cancelled")
            } finally {
                Amsterdam.faceTap = null
            }
            val level = Keep.indexOf(r.tolerance)
            check(level >= 0) { "${case.name}: the render loaded at ${r.tolerance} m, which is not on the keep ladder" }
            // the theme as the render drew with it: its copy of the moment's, with the sun it computed (no eye-level
            // haze: no case looks within 10° of the horizon)
            val th = LinkedHashMap(Amsterdam.THEMES.getValue(r.themeKey))
            th["_L"] = Amsterdam.sunOf(th).third
            th["_hz"] = 0.0
            Rendered(calls, tape, th, level)
        }
    }

    /** The core's records of a pack at a level, by building (OSM id, relation, part). */
    private class Core(val words: LongArray) {
        val byBuilding = HashMap<Triple<Long, Boolean, Boolean>, MutableList<Int>>()
        var records = 0

        init {
            var at = 0
            while (at < words.size) {
                val info = words[at + 2]
                byBuilding.getOrPut(Triple(words[at + 1], (info and 2L) != 0L, (info and 1L) != 0L)) { ArrayList() } += at
                records++
                at += 8 + 3 * words[at + 7].toInt()
            }
        }

        fun kind(at: Int): Int = ((words[at + 2] shr 8) and 0xFF).toInt()
        fun flags(at: Int): Int = ((words[at + 2] shr 16) and 0xFFFF).toInt()
        fun corners(at: Int): Int = words[at + 7].toInt()
        fun coord(at: Int, k: Int): Double = Double.fromBits(words[at + 8 + k])
        fun base(at: Int): V3 {
            val b = words[at + 3]
            return V3(((b shr 16) and 0xFF).toDouble(), ((b shr 8) and 0xFF).toDouble(), (b and 0xFF).toDouble())
        }
        fun byDay(at: Int): Boolean = (words[at + 3] and (1L shl 24)) != 0L
        fun normal(at: Int): V3 = V3(Double.fromBits(words[at + 4]), Double.fromBits(words[at + 5]), Double.fromBits(words[at + 6]))
    }

    private val cores = HashMap<Pair<String, Int>, Core>()

    private fun core(pack: File, level: Int): Core = cores.getOrPut(pack.path to level) {
        Core(BuildHost.prims(pack.path, level) ?: error("the core cannot read ${pack.path}"))
    }

    private fun cases(): List<Case> {
        val out = ArrayList<Case>()
        val w = { AnchorCases.westerkerk() }
        val g = { AnchorCases.grachten() }
        // EngineVersion's fixed renders: the Westerkerk at its four moments and the Main board
        for (f in EngineVersion.FIXED) {
            if (f.region == "sangimignano") continue
            val pack = if (f.region == "grachten") grachtenPack else westerkerkPack
            out += Case(f.name, pack, if (f.region == "grachten") g else w, { r -> f.liveView(r) }, f.moment)
        }
        // the smoke tier's home and board cameras (CASES/cases/smoke.json at S1, 412 × 916)
        out += Case("home sunny S1", westerkerkPack, w, { r -> AnchorCases.homeView(r, 916) }, "sunny")
        out += Case("board sunny S1", grachtenPack, g, { r -> knot(r, "grachten_home", 916) }, "sunny")
        // the smoke tier's eight sweep cameras at their own moments
        for (k in 0 until 4) {
            val own = if (k % 2 == 0) "sunny" else "night"
            out += Case("sweep $k $own S1", westerkerkPack, w, { r -> sweep(24, r.bbox!!, 916, 9)[k] }, own)
            out += Case("canal sweep $k $own S1", grachtenPack, g, { r -> sweep(12, r.bbox!!, 916, 9)[k] }, own)
        }
        return out
    }

    @Test
    fun everyBuildingFaceOfTheBoardsViewsIsStageTs() {
        assumeTrue("the canal ring's pack is not on this machine", AnchorCases.grachtenPack.isFile)
        var total = 0
        var totalMatched = 0
        val kinds = java.util.TreeMap<String, Int>()
        for (case in cases()) {
            val region = case.region() ?: error("${case.name}: no region")
            val r = render(case, region)
            val core = core(case.pack, r.level)
            val night = r.th["night"] == true
            var building = 0; var matched = 0; var hatched = 0; var unmatched = 0; var fills = 0; var edges = 0
            var worst = 0.0
            var exact = 0
            val firstUnmatched = ArrayList<String>()
            val firstFill = ArrayList<String>()
            val firstEdge = ArrayList<String>()
            for (c in r.calls) {
                // a building's or a part's own face: the item being drawn is one, and the tape's owner then is its
                if (c.item >= r.tape.items.size) continue
                val kind = r.tape.items.kind(c.item)
                if (kind != "building" && kind != "part") continue
                val owner = r.tape.ownerObject(r.tape.items.owner(c.item))
                if (owner == null || c.owner !== owner) continue
                building++
                if (c.fill.startsWith("url(")) {
                    hatched++
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                val b = owner as Map<String, Any?>
                val key = Triple((b["id"] as Number).toLong(), b["otype"] == "relation", kind == "part")
                val n = c.q.size / 3
                var found = -1
                var best = Double.MAX_VALUE
                for (at in core.byBuilding[key] ?: emptyList()) {
                    if (core.corners(at) != n) continue
                    var d = 0.0
                    for (k in 0 until 3 * n) d = maxOf(d, Math.abs(core.coord(at, k) - c.q[k]))
                    if (d < best) { best = d; found = at }
                }
                if (found < 0 || best > 1e-6) {
                    unmatched++
                    if (firstUnmatched.size < 6) {
                        firstUnmatched += "${key.first}${if (key.second) "r" else "w"}${if (key.third) " part" else ""} $n corners " +
                            "(${c.q.take(3).joinToString(", ")} …) fill ${c.fill}${c.extra}" + (if (found >= 0) ", nearest $best m off" else ", none of its")
                    }
                    continue
                }
                matched++
                worst = maxOf(worst, best)
                if (best == 0.0) exact++
                kinds.merge(KINDS.getOrElse(core.kind(found)) { "?" }, 1, Int::plus)
                // the fill: the port's light of the core's face, lit (by its sun bit, or by day) or not
                val lit = City.tohex(Amsterdam.light(r.th, core.base(found), core.normal(found), if (core.byDay(found)) !night else true))
                val unlit = City.tohex(Amsterdam.light(r.th, core.base(found), core.normal(found), false))
                val fillOk = if (core.byDay(found)) c.fill == lit else c.fill == lit || c.fill == unlit
                if (!fillOk) {
                    fills++
                    if (firstFill.size < 6) firstFill += "${key.first} ${KINDS.getOrElse(core.kind(found)) { "?" }}: fill ${c.fill}, the core's light $lit / $unlit"
                }
                // its stroke as the core's flags say the port strokes it: a cell's same-colour .3 px edge, a roof's .25
                // or .45 px edge in the roofs' edge colour, a cabin roof's .4 px, or none
                val flags = core.flags(found)
                val edgeColour = if (night) "#2E3446" else "#6F695F"
                val want = when {
                    (flags and EDGE) != 0 -> " stroke=\"${c.fill}\" stroke-width=\".3\""
                    (flags and STROKE_FINE) != 0 -> " stroke=\"$edgeColour\" stroke-width=\".25\""
                    (flags and STROKE_WIDE) != 0 -> " stroke=\"$edgeColour\" stroke-width=\".45\""
                    (flags and STROKE_CABIN) != 0 -> " stroke=\"#2A2C30\" stroke-width=\".4\""
                    else -> ""
                }
                if (c.extra != want) {
                    edges++
                    if (firstEdge.size < 6) firstEdge += "${key.first} ${KINDS.getOrElse(core.kind(found)) { "?" }}: the core strokes it '$want', the port '${c.extra}'"
                }
            }
            total += building; totalMatched += matched
            println("stage T ${case.name} (level ${r.level}, ${core.records} core records): $building building faces of the port, $matched matched " +
                "($exact bit for bit, the worst ${worst} m off), $hatched hatched, $unmatched unmatched, $fills fills and $edges edges differing")
            if (firstUnmatched.isNotEmpty()) println("  unmatched: ${firstUnmatched.joinToString("; ")}")
            if (firstFill.isNotEmpty()) println("  fills: ${firstFill.joinToString("; ")}")
            if (firstEdge.isNotEmpty()) println("  edges: ${firstEdge.joinToString("; ")}")
            assertEquals("${case.name}: the port's building faces stage T does not build, the first: $firstUnmatched", 0, unmatched)
            assertEquals("${case.name}: fills that are not the port's light of the core's face, the first: $firstFill", 0, fills)
            assertEquals("${case.name}: edges where the core does not draw one, or none where it does, the first: $firstEdge", 0, edges)
        }
        for ((k, v) in kinds) println("stage T kind $k: $v faces matched")
        println("stage T: $totalMatched of $total building faces of ${cases().size} renders matched")
        assertTrue("the boards' views draw building faces", totalMatched > 50_000)
    }

    private companion object {
        /** geometry/Walls.hpp's PrimKind, by number. */
        val KINDS = listOf(
            "cell", "skillion_wall", "hull_wall", "cabin_wall", "top", "slope", "gable", "hip", "pyramid", "skillion", "mansard",
            "mansard_top", "deck", "cabin_roof",
        )

        /** geometry/Walls.hpp's prim_flag: the cells' same-colour edge, and the roofs' thin strokes. */
        const val EDGE = 16
        const val STROKE_FINE = 128
        const val STROKE_WIDE = 256
        const val STROKE_CABIN = 512

        /** A region view's camera on a 412 × [height] frame: the ladder's knot [name], which the camera snaps onto. */
        fun knot(r: LiveRegion, name: String, height: Int): LiveView {
            val k = r.ladder.knots.firstOrNull { it.name == name } ?: error("region ${r.id} has no view $name")
            return EngineVersion.knotView(r.frame, k, 412, height)
        }

        /** D16's sweep as :likeness's Cameras.sweep draws it (StrictMath and java.util.Random, the same on every machine). */
        fun sweep(n: Int, box: BBox, height: Int, seed: Long): List<LiveView> {
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
    }
}
