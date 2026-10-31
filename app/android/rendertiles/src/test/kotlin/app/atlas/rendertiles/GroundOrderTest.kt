package app.atlas.rendertiles

import app.atlas.render.Amsterdam
import app.atlas.render.City
import app.atlas.render.Regions
import app.atlas.render.V2
import app.atlas.render.V3
import app.atlas.render.live.LiveView
import app.atlas.render.py.Dict
import app.atlas.rendertiles.model.Feature
import app.atlas.rendertiles.model.Geom
import app.atlas.rendertiles.model.Grid
import app.atlas.rendertiles.model.GroundKey
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.Reach
import app.atlas.rendertiles.model.RenderHead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Land cover's order per frame, and the chunks a frame needs (plan-vector D6, K7), at the boards' views and at 60 random
 * live cameras over each bundled region:
 * - the land group drawn from the decoded tiles by [GroundKey.LAND]'s rule (whole rings; each kind ranked by the first
 *   of its rings `gpoly` keeps at that camera) is the port's own `draw_under` output, byte for byte; the static order of
 *   the first payload (each kind by its first ring in the whole sorted list) would not have been, at some of them;
 * - taking only the chunks whose reach meets the view, with the margin the renders cull by, finds every land ring, area
 *   and road the port draws there, among them those stored in a tile the screen does not show.
 */
class GroundOrderTest {
    private class Cam(val name: String, val cfg: Dict) {
        val cam: City.Camera = Amsterdam.cameraOf(cfg)
    }

    private class Decoded(val f: Feature, val chunk: Int)

    @Suppress("UNCHECKED_CAST")
    private fun dicts(S: Dict, key: String): List<Dict> = (S[key] as List<Dict>?) ?: emptyList()

    /** The region's own views, then [n] live cameras placed by the engine's ladder at random over the pack's box. */
    private fun cameras(b: Packs.Built, region: app.atlas.render.live.LiveRegion, n: Int, seed: Int): List<Cam> {
        val out = region.ladder.knots.map { Cam(it.name, region.ladder.exact(it, 412, 915)) }.toMutableList()
        val box = b.reader.manifest.bbox
        val r = Random(seed)
        repeat(n) { k ->
            val v = LiveView(r.nextDouble(box.south, box.north), r.nextDouble(box.west, box.east), r.nextDouble(14.5, 17.5), r.nextDouble(0.0, 360.0), r.nextDouble(0.0, 60.0), 412, 915)
            out += Cam("live $k", region.ladder.place(region.frame, v).cfg)
        }
        return out
    }

    /** The ground quad of [box] (south, west, north, east on the grid) at height [y] in [head]'s frame. */
    private fun quad(box: IntArray, head: RenderHead, y: Double): List<V3> {
        val w = Grid.x(box[1], head.lon0, head.kx)
        val e = Grid.x(box[3], head.lon0, head.kx)
        val n = Grid.z(box[2], head.lat0, head.kz)
        val s = Grid.z(box[0], head.lat0, head.kz)
        return listOf(V3(w, y, n), V3(e, y, n), V3(e, y, s), V3(w, y, s))
    }

    /** Whether [cam] keeps the ground quad of [box] at height [y] with [margin] (`Camera.poly`'s cull): a ring inside it may show only then. */
    private fun meets(cam: City.Camera, box: IntArray, head: RenderHead, y: Double, margin: Double): Boolean = cam.poly(quad(box, head, y), margin = margin) != null

    /** The port's `gp` and `gpoly`: the whole ring at [y], near-clipped, not culled at [margin], over [min] px². */
    private fun drawn(cam: City.Camera, pts: List<V2>, y: Double, margin: Double, min: Double): Boolean {
        val sp = cam.poly(pts.map { V3(it.x, y, it.y) }, margin = margin) ?: return false
        return City.area(sp) > min
    }

    @Test
    fun `land is drawn in the frame's order from whole rings, and reach finds every chunk a frame needs`() {
        val sets = listOf("westerkerk" to Packs.westerkerkPack, "grachten" to Packs.grachtenPack).filter { it.second.isFile }
        assumeTrue(sets.isNotEmpty())
        for ((name, file) in sets) {
            val b = Packs.build(file, name)
            val head = b.reader.head
            val region = Packs.region(file)
            val scene = Packs.scene(region)
            scene.state.restore()
            val S = scene.S
            fun metres(g: Geom): List<V2> = (0 until g.size).map { V2(Grid.x(g.lon[it], head.lon0, head.kx), Grid.z(g.lat[it], head.lat0, head.kz)) }
            // the decoded L14 features with their chunk
            val land = ArrayList<Decoded>()
            val areas = ArrayList<Decoded>()
            val roads = ArrayList<Decoded>()
            for ((ci, c) in b.reader.chunks.withIndex()) {
                if (c.z != 14) continue
                val t = b.reader.decode(c)
                for (f in t.layers[Layer.GROUND].orEmpty()) (if (((f["source"] as Int?) ?: 0) == 1) land else areas) += Decoded(f, ci)
                for (f in t.layers[Layer.ROAD].orEmpty()) roads += Decoded(f, ci)
            }
            land.sortBy { (it.f["index"] as Long?) ?: 0L }
            assertEquals("$name: every land ring once", dicts(S, "land").size, land.size)
            val reach: List<Reach> = b.reader.reach
            // the first payload's static rank: each kind by its first ring in the whole sorted list
            val staticRank = LinkedHashMap<String, Int>().also { m -> for (a in dicts(S, "land")) m.getOrPut(a["kind"] as String) { m.size } }
            val th: Dict = linkedMapOf("night" to false)
            var differs = 0
            var byReachOnly = 0
            var drawnLand = 0
            val cams = cameras(b, region, 60, name.hashCode())
            for (k in cams) {
                val cam = k.cam
                // the port's own land group (the living map has none: `render` draws land cover only where S['under'] does)
                val portLand = if (S["under"] == null) "<g filter=\"url(#mat)\"></g>" else Regions.drawUnder(S, cam, th, { _, _ -> false }, ArrayList()).let { it.substring(0, it.indexOf("</g>") + 4) }
                // the chunks the frame needs, by their reach alone
                val need = BooleanArray(reach.size) { meets(cam, reach[it].box, head, 0.01, 600.0) }
                val groups = LinkedHashMap<String, MutableList<String>>()
                for (d in land) {
                    if (!need[d.chunk]) continue
                    val dd = Regions.gpoly(cam, metres(d.f["ring"] as Geom), 0.01)
                    if (dd.isNotEmpty()) groups.getOrPut(d.f["kind"] as String) { ArrayList() }.add(dd)
                }
                val tiles = StringBuilder("<g filter=\"url(#mat)\">")
                for ((kind, ds) in groups) {
                    // draw_under's fill: a lawn in its area's colour, the other kinds in LAND's
                    val fill = if (kind in Regions.LAWN_KINDS) Regions.nightC(th, Regions.LAWN, 0.18) else Regions.nightC(th, Regions.LAND.getValue(kind).first)
                    tiles.append("<path d=\"").append(ds.joinToString("")).append("\" fill=\"").append(fill).append("\"/>")
                }
                tiles.append("</g>")
                assertEquals("$name ${k.name}: the land group from the tiles", portLand, tiles.toString())
                drawnLand += groups.values.sumOf { it.size }
                if (groups.keys.toList() != groups.keys.sortedBy { staticRank.getValue(it) }) differs++
                // every ring, area and road the port draws lies in a chunk the frame asks for
                fun check(what: String, list: List<Decoded>, y: (Feature) -> Double, margin: Double, min: Double, shape: (Feature) -> List<V2>) {
                    for (d in list) {
                        val yy = y(d.f)
                        if (!drawn(cam, shape(d.f), yy, margin, min)) continue
                        assertTrue("$name ${k.name}: $what ${d.f["index"]} is drawn, its chunk not asked for", meets(cam, reach[d.chunk].box, head, yy, margin))
                        val c = b.reader.chunks[d.chunk]
                        if (!meets(cam, Grid.tileBox(c.z, c.x, c.y), head, yy, margin)) byReachOnly++
                    }
                }
                check("land", land, { 0.01 }, 600.0, 0.3) { metres(it["ring"] as Geom) }
                check("area", areas, { 0.02 }, 900.0, 0.2) { metres(it["ring"] as Geom) }
                check("road", roads, { if (it["bridge"] == true) 0.45 else 0.05 }, 900.0, 0.2) { City.ribbon(metres(it["line"] as Geom), ((it["w"] as Double?) ?: 0.0) / 2) }
            }
            println(
                "GROUND $name: ${cams.size} cameras (${region.ladder.knots.size} views, ${cams.size - region.ladder.knots.size} live): the land group from the tiles " +
                    "is draw_under's at every one ($drawnLand rings drawn); the static kind order would differ at $differs; $byReachOnly drawn rings and " +
                    "roads lie in chunks only their reach brings in",
            )
        }
    }
}
