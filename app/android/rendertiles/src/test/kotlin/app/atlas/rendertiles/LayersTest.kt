package app.atlas.rendertiles

import app.atlas.render.Amsterdam
import app.atlas.render.PoiStyle
import app.atlas.render.V2
import app.atlas.render.live.LiveScene
import app.atlas.render.py.Dict
import app.atlas.render.py.PyMath
import app.atlas.render.py.pyNum
import app.atlas.render.py.truthy
import app.atlas.rendertiles.compile.Clip
import app.atlas.rendertiles.compile.GroundKeys
import app.atlas.rendertiles.model.Feature
import app.atlas.rendertiles.model.Geom
import app.atlas.rendertiles.model.Grid
import app.atlas.rendertiles.model.GridPoint
import app.atlas.rendertiles.model.GroundKey
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.RenderHead
import app.atlas.rendertiles.model.RenderTile
import app.atlas.rendertiles.model.Schema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigInteger
import java.util.IdentityHashMap

/**
 * The layers besides buildings, decoded from the bundled packs, against the scene they were derived from: roads, lines,
 * trees, things, items, labels, land cover and areas (whole), the water (whose clipped pieces fill their ring to the
 * rounding of the crossings and turn as it turns), the quay edges (`water_edges` rebuilt from the tiles, bit for bit)
 * and the ground keys; and every tile decoded equal to what the packer wrote. The land's per-frame order is
 * [GroundOrderTest]'s.
 */
class LayersTest {
    private class Pack(val name: String, val built: Packs.Built, val scene: LiveScene, val tiles: List<RenderTile>) {
        val head: RenderHead get() = built.reader.head
        fun l14(layer: Layer): List<Feature> = tiles.filter { it.level == 14 }.flatMap { it.layers[layer].orEmpty() }
        fun x(q: Int) = Grid.x(q, head.lon0, head.kx)
        fun z(q: Int) = Grid.z(q, head.lat0, head.kz)
        fun metres(g: Geom): List<V2> = (0 until g.size).map { V2(x(g.lon[it]), z(g.lat[it])) }
        fun at(p: GridPoint) = V2(x(p.lon), z(p.lat))
    }

    private fun packs(): List<Pack> = listOfNotNull(
        Packs.westerkerkPack.takeIf { it.isFile }?.let { Pack("westerkerk", Packs.build(it, "westerkerk"), Packs.scene(Packs.region(it)), Packs.build(it, "westerkerk").reader.tiles()) },
        Packs.grachtenPack.takeIf { it.isFile }?.let { Pack("grachten", Packs.build(it, "grachten"), Packs.scene(Packs.region(it)), Packs.build(it, "grachten").reader.tiles()) },
    )

    @Suppress("UNCHECKED_CAST")
    private fun dicts(S: Dict, key: String): List<Dict> = (S[key] as List<Dict>?) ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun tags(d: Map<String, Any?>): Map<String, Any?> = (d["tags"] as Map<String, Any?>?) ?: emptyMap()

    private fun int(v: Any?): Long? = (v as Number?)?.toLong()

    private fun byIndex(fs: List<Feature>): Map<Long, Feature> = fs.associateBy { (it["index"] as Long?) ?: 0L }

    @Test
    fun `every tile decodes to what the packer wrote`() {
        assumeTrue(Packs.westerkerkPack.isFile)
        for ((name, file) in listOf("westerkerk" to Packs.westerkerkPack, "grachten" to Packs.grachtenPack)) {
            if (!file.isFile) continue
            val b = Packs.build(file, name)
            val written = b.packed.tiles.associateBy { Triple(it.level, it.x, it.y) }
            val decoded = b.reader.tiles()
            assertEquals(written.size, decoded.size)
            var features = 0
            for (t in decoded) {
                val w = written.getValue(Triple(t.level, t.x, t.y))
                assertEquals("$name ${t.level}/${t.x}/${t.y} layers", w.layers.keys, t.layers.keys)
                for ((layer, list) in w.layers) {
                    val got = t.layers.getValue(layer)
                    assertEquals("$name ${t.level}/${t.x}/${t.y} ${layer.label}", list.size, got.size)
                    for (i in list.indices) assertEquals("$name ${t.level}/${t.x}/${t.y} ${layer.label} $i", list[i], got[i])
                    features += list.size
                }
                assertEquals("$name ${t.level}/${t.x}/${t.y} cells", w.cells, t.cells)
                // the canonical text the core's reader is held to (L0 b) reads the same from either
                assertEquals(CanonicalDump.of(w), CanonicalDump.of(t))
                println("LAYERS $name ${t.level}/${t.x}/${t.y}: canonical ${CanonicalDump.hash(t)}")
            }
            // the reach section: the union of each tile's cell boxes and its highest top, in the chunk table's order
            assertEquals("$name reach", b.written.reach, b.reader.reach)
            for ((c, r) in b.reader.chunks.zip(b.reader.reach)) {
                val t = Grid.tileBox(c.z, c.x, c.y)
                assertTrue("$name ${c.z}/${c.x}/${c.y} reaches its own tile", r.south <= t[2] && r.north >= t[0] && r.west <= t[3] && r.east >= t[1])
            }
            println("LAYERS $name: ${decoded.size} tiles, $features features decoded equal to the packer's; reach ${b.reader.reach.joinToString { "${it.topCm / 100} m" }}")
        }
    }

    @Test
    fun `roads lines trees and items equal the scene`() {
        for (p in packs()) {
            val S = p.scene.S
            val roads = byIndex(p.l14(Layer.ROAD))
            val roadOrder = listOf("path", "footway", "steps", "cycleway", "service", "pedestrian", "living_street", "unclassified", "residential", "busway", "tertiary", "secondary")
            val roadRank = dicts(S, "roads").withIndex().sortedWith(compareBy({ roadOrder.indexOf(it.value["hw"]) }, { it.index })).withIndex().associate { (rank, r) -> r.index to rank }
            var marked = 0
            for ((i, r) in dicts(S, "roads").withIndex()) {
                val f = roads[i.toLong()] ?: error("${p.name} road $i missing")
                @Suppress("UNCHECKED_CAST")
                assertEquals("${p.name} road $i line", r["pts"] as List<V2>, p.metres(f["line"] as Geom))
                assertEquals(int(r["id"]), f["id"])
                assertEquals(r["w"], f["w"]); assertEquals(r["hw"], f["hw"]); assertEquals((r["layer"] as Number?)?.toDouble(), f["layer"])
                assertEquals(r["bridge"], f["bridge"]); assertEquals(r["wguess"], f["wguess"])
                for (k in Schema.RENDER_TAGS.getValue(Layer.ROAD)) assertEquals("${p.name} road $i $k", tags(r)[k], f[Schema.tag(k)])
                assertEquals("${p.name} road $i ground key", GroundKeys.road(r["hw"] as String, i), f["ground_key"])
                assertEquals(GroundKey.ROAD, GroundKey.stage(f["ground_key"] as Long))
                // the road loop's marks: bridges and setts, in the roads' drawing order
                val marks = r["bridge"] == true || tags(r)["surface"] == "sett"
                val want = if (marks) GroundKey.of(GroundKey.MARKS, GroundKey.MARKS_ROAD, roadRank.getValue(i)) else 0L
                assertEquals("${p.name} road $i marks key", want, (f["marks_key"] as Long?) ?: 0L)
                if (marks) marked++
            }
            val lines = p.l14(Layer.LINE)
            fun line(source: Int) = lines.filter { ((it["source"] as Int?) ?: 0) == source }.associateBy { (it["index"] as Long?) ?: 0L }
            @Suppress("UNCHECKED_CAST")
            for ((i, rl) in ((S["rails"] as List<List<V2>>?) ?: emptyList()).withIndex()) {
                val f = line(0).getValue(i.toLong())
                assertEquals("${p.name} tram $i", rl, p.metres(f["line"] as Geom))
                assertEquals(GroundKey.of(GroundKey.MARKS, GroundKey.MARKS_TRAM, i), f["ground_key"])
            }
            for ((source, key) in listOf(1 to "rail", 2 to "big", 3 to "aero", 4 to "power", 5 to "marine")) {
                val got = line(source)
                val list = dicts(S, key)
                assertEquals("${p.name} $key", list.size, got.size)
                for ((i, d) in list.withIndex()) {
                    @Suppress("UNCHECKED_CAST")
                    val f = got.getValue(i.toLong())
                    assertEquals("${p.name} $key $i", d["pts"] as List<V2>, p.metres(f["line"] as Geom))
                    // a big road's layer raises it 0.5 m a layer: a typed column, numerically the scene's
                    if (key == "big") assertEquals("${p.name} big $i layer", pyNum(d["layer"]), f["layer"])
                    for (k in d.keys) assertTrue("${p.name} $key $i carries $k", k in setOf("pts", "id", "kind", "hw", "crane", "closed", "h", "layer", "tags"))
                }
            }
            val trees = byIndex(p.l14(Layer.TREE))
            val nodeIndex = IdentityHashMap<Any, Int>().also { m -> dicts(S, "nodes").forEachIndexed { i, n -> m[n] = i } }
            for ((i, t) in dicts(S, "trees").withIndex()) {
                val f = trees.getValue(i.toLong())
                assertEquals(V2(t["x"] as Double, t["z"] as Double), p.at(f["pos"] as GridPoint))
                assertEquals(t["h"], f["h"]); assertEquals(t["crown"], f["crown"]); assertEquals(int(t["id"]), f["id"])
                assertEquals(nodeIndex[t]!!.toLong(), f["node"])
                @Suppress("UNCHECKED_CAST")
                assertEquals((t["guessed"] as Map<String, Any?>).keys.toList(), f["guessed"])
                for (k in Schema.RENDER_TAGS.getValue(Layer.TREE)) assertEquals(tags(t)[k], f[Schema.tag(k)])
            }
            val items = p.l14(Layer.ITEM)
            for ((source, key) in listOf(0 to "cranes", 1 to "masts", 2 to "mtowers", 4 to "turbines")) {
                val got = items.filter { ((it["source"] as Int?) ?: 0) == source }.associateBy { (it["index"] as Long?) ?: 0L }
                for ((i, d) in dicts(S, key).withIndex()) {
                    val f = got.getValue(i.toLong())
                    // a mapped node on the grid itself; a way's centre off it, kept exactly in x and z
                    val exact = V2(d["x"] as Double, d["z"] as Double) == p.at(f["pos"] as GridPoint)
                    if (exact) assertTrue(f["x"] == null && f["z"] == null) else { assertEquals(d["x"], f["x"]); assertEquals(d["z"], f["z"]) }
                    assertEquals(int(d["id"]), f["id"]); assertEquals(d["h"], f["h"])
                    assertEquals(tags(d), f["tags"] ?: emptyMap<String, String>())
                }
            }
            println("LAYERS ${p.name}: ${roads.size} roads ($marked with marks), ${lines.size} lines, ${trees.size} trees, ${items.size} items equal the scene")
        }
    }

    @Test
    fun `things are the nodes the renders draw, with their values`() {
        for (p in packs()) {
            val S = p.scene.S
            val facades = !truthy(S["no_facades"])
            val pois = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).also { s -> s.addAll(dicts(S, "pois")) }
            val trees = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).also { s -> s.addAll(dicts(S, "trees")) }
            val things = byIndex(p.l14(Layer.THING))
            // the buildings facades go on, as facade_details picks them
            val blds = dicts(S, "buildings").filter { !truthy(it["hidden"]) && truthy(it["h"]) && tags(it)["building"] != "houseboat" }
            val bIndex = IdentityHashMap<Any, Int>().also { m -> dicts(S, "buildings").forEachIndexed { i, b -> m[b] = i } }
            // facade_details' rule: the first of its buildings whose box and ring hold the node
            @Suppress("UNCHECKED_CAST")
            fun facadeOf(x: Double, z: Double): Int? = blds.firstOrNull { b -> val poly = b["poly"] as List<V2>; poly.minOf { it.x } <= x && x <= poly.maxOf { it.x } && poly.minOf { it.y } <= z && z <= poly.maxOf { it.y } && app.atlas.render.Real.pip(x, z, poly) }?.let { bIndex[it] }
            var drawn = 0
            var doors = 0
            var walls = 0
            for ((i, n) in dicts(S, "nodes").withIndex()) {
                if (n in trees) continue
                val t = tags(n)
                val st = n["style"] as PoiStyle.Style?
                val always = t["highway"] == "street_lamp" || t["amenity"] in setOf("bench", "bicycle_parking", "waste_basket") || t["barrier"] in setOf("bollard", "block") || n in pois
                val door = facades && (truthy(t["addr:housenumber"]) || (st != null && st.cat != "service"))
                val building = if (door) facadeOf(n["x"] as Double, n["z"] as Double) else null
                val facade = building != null
                val f = things[i.toLong()]
                assertEquals("${p.name} node $i (${n["id"]}) is a thing", always || facade, f != null)
                if (f == null) continue
                drawn++
                assertEquals(V2(n["x"] as Double, n["z"] as Double), p.at(f["pos"] as GridPoint))
                assertEquals(int(n["id"]), f["id"])
                assertEquals(n in pois, f["poi"] == true)
                assertEquals(st?.cat, f["style.cat"]); assertEquals(st?.col, f["style.col"]); assertEquals(st?.kind, f["style.kind"])
                assertEquals(st?.brand, f["style.brand"]); assertEquals(st?.bg, f["style.bg"]); assertEquals(st?.fg, f["style.fg"])
                for (k in Schema.RENDER_TAGS.getValue(Layer.THING)) assertEquals(t[k], f[Schema.tag(k)])
                val num = t["addr:housenumber"] as String?
                assertEquals(truthy(num), f["door"] == true)
                val lead = num?.let { s -> s.takeWhile { Character.isDigit(it) }.takeIf { it.isNotEmpty() }?.let { java.math.BigInteger(app.atlas.render.PyRe.asciiDigits(it)).toLong() } }
                assertEquals("${p.name} node $i door number of $num", lead, f["door_number"])
                assertEquals("${p.name} node $i's facade", building?.toLong(), f["building"])
                if (building != null) doors++
                // the street wall it opens onto: from its corner `wall` the building's ring gives the port's point on it
                // and its direction, to the bit (street_wall's arithmetic for that wall)
                val x = n["x"] as Double
                val z = n["z"] as Double
                val wall = building?.let { Amsterdam.streetWall(S, dicts(S, "buildings")[it], x, z) }
                assertEquals("${p.name} node $i's street wall", wall != null, f["wall"] != null)
                if (wall != null) {
                    @Suppress("UNCHECKED_CAST")
                    val poly = dicts(S, "buildings")[building!!]["poly"] as List<V2>
                    val k = (f["wall"] as Long).toInt()
                    val a = poly[k]
                    val c = poly[(k + 1) % poly.size]
                    val ux = c.x - a.x
                    val uz = c.y - a.y
                    val l2 = ux * ux + uz * uz
                    val tt = PyMath.max(0.15, PyMath.min(0.85, ((x - a.x) * ux + (z - a.y) * uz) / l2))
                    val l = PyMath.sqrt(l2)
                    val got = doubleArrayOf(a.x + ux * tt, a.y + uz * tt, ux / l, uz / l)
                    assertTrue("${p.name} node $i's street wall from corner $k", (0 until 4).all { got[it].toRawBits() == wall[it].toRawBits() })
                    walls++
                }
            }
            assertEquals(drawn, things.size)
            println("LAYERS ${p.name}: $drawn things are exactly the nodes the renders draw (facades ${if (facades) "on" else "off"}), $doors on the facade of their building, $walls with its street wall")
        }
    }

    @Test
    fun `labels are the canal candidates in source order and the place nodes`() {
        for (p in packs()) {
            val S = p.scene.S
            val labels = p.l14(Layer.LABEL)
            val canals = labels.filter { ((it["source"] as Int?) ?: 0) == 0 }.associateBy { (it["index"] as Long?) ?: 0L }
            var n = 0
            for ((i, lb) in dicts(S, "labels").withIndex()) {
                @Suppress("UNCHECKED_CAST")
                val pts = lb["pts"] as List<V2>
                val f = canals[i.toLong()]
                if (lb["kind"] != "canal" || pts.size < 2) { assertTrue(f == null); continue }
                assertEquals(lb["name"], f!!["name"])
                assertEquals(pts[pts.size / 2], p.at(f["pos"] as GridPoint))
                assertEquals(pts[pts.size / 2 - 1], p.at(f["prev"] as GridPoint))
                assertEquals(pts.size.toLong(), f["count"])
                assertEquals(GroundKey.of(GroundKey.MARKS, GroundKey.MARKS_CANAL, i), f["ground_key"])
                n++
            }
            val places = labels.count { it["source"] == 1 }
            assertEquals(dicts(S, "nodes").count { tags(it)["place"] is String }, places)
            println("LAYERS ${p.name}: $n canal name candidates, $places place nodes")
        }
    }

    /** Twice the signed area of a ring on the grid, exactly. */
    private fun area2(lat: IntArray, lon: IntArray): BigInteger {
        var s = BigInteger.ZERO
        for (i in lat.indices) {
            val j = (i + 1) % lat.size
            s = s.add(BigInteger.valueOf(lon[i].toLong()).multiply(BigInteger.valueOf(lat[j].toLong())).subtract(BigInteger.valueOf(lon[j].toLong()).multiply(BigInteger.valueOf(lat[i].toLong()))))
        }
        return s
    }

    @Test
    fun `land cover and areas are the scene's, whole`() {
        for (p in packs()) {
            val S = p.scene.S
            val ground = p.l14(Layer.GROUND)
            for ((source, key) in listOf(0 to "areas", 1 to "land")) {
                val got = ground.filter { ((it["source"] as Int?) ?: 0) == source }
                val list = dicts(S, key)
                assertEquals("${p.name} $key", list.size, got.size)
                val by = got.associateBy { (it["index"] as Long?) ?: 0L }
                for ((i, a) in list.withIndex()) {
                    val f = by.getValue(i.toLong())
                    @Suppress("UNCHECKED_CAST")
                    assertEquals("${p.name} $key $i ring", a["poly"] as List<V2>, p.metres(f["ring"] as Geom))
                    assertEquals(a["kind"], f["kind"]); assertEquals(int(a["id"]), f["id"])
                    for (k in Schema.RENDER_TAGS.getValue(Layer.GROUND)) assertEquals(tags(a)[k], f[Schema.tag(k)])
                    val want = if (source == 0) GroundKeys.area(i) else GroundKey.of(GroundKey.LAND, 0, i)
                    assertEquals("${p.name} $key $i ground key", want, f["ground_key"])
                }
            }
            assertTrue("${p.name}: areas and land cover are their own lists", dicts(S, "land").isEmpty() || dicts(S, "areas") !== dicts(S, "land"))
            fun kinds(key: String) = dicts(S, key).groupingBy { it["kind"] as String }.eachCount().entries.sortedByDescending { it.value }.take(4).joinToString { "${it.key} ${it.value}" }
            println("LAYERS ${p.name}: ${dicts(S, "areas").size} areas (${kinds("areas")}) and ${dicts(S, "land").size} land cover rings (${kinds("land")}) equal the scene, whole")
        }
    }

    @Test
    fun `clipped water fills its rings to the crossings' rounding, each piece turning as its ring, and the quays are water_edges`() {
        for (p in packs()) {
            val S = p.scene.S
            for (level in listOf(14)) {
                val tiles = p.tiles.filter { it.level == level }
                // pieces by ring: their areas add up to the ring's, to the rounding of the crossings (half a grid unit along
                // an edge); each piece turns the way its ring turns
                fun check(rings: List<List<V2>>, hole: Boolean, what: String) {
                    val pieces = tiles.flatMap { t -> t.layers[Layer.WATER].orEmpty().filter { (it["hole"] == true) == hole } }.groupBy { (it["index"] as Long?) ?: 0L }
                    var worst = 0.0
                    var n = 0
                    for ((i, r) in rings.withIndex()) {
                        val ps = pieces[i.toLong()] ?: error("${p.name} $what $i has no piece")
                        val lat = IntArray(r.size) { Grid.latOf(r[it].y, p.head.lat0, p.head.kz)!! }
                        val lon = IntArray(r.size) { Grid.lonOf(r[it].x, p.head.lon0, p.head.kx)!! }
                        val whole = Clip.area2(lat, lon)
                        var sum = BigInteger.ZERO
                        for (f in ps) {
                            val g = f["ring"] as Geom
                            val a2 = Clip.area2(g.lat, g.lon)
                            assertEquals("${p.name} $what $i: a piece turns as its ring", whole.signum(), a2.signum())
                            sum = sum.add(a2)
                            n++
                        }
                        val err = sum.subtract(whole).abs().toDouble()
                        val bound = r.size.toDouble() * 4e5 // a crossing moves by at most half a unit along an edge
                        assertTrue("${p.name} $what $i: pieces fill $sum of $whole", err <= bound + whole.abs().toDouble() * 1e-9)
                        worst = maxOf(worst, err / maxOf(whole.abs().toDouble(), 1.0))
                    }
                    println("LAYERS ${p.name} L$level: ${rings.size} $what rings filled by their $n pieces, each turning as its ring (worst relative difference %.2e)".format(worst))
                }
                @Suppress("UNCHECKED_CAST")
                check((S["water"] as List<List<V2>>?) ?: emptyList(), false, "water")
                @Suppress("UNCHECKED_CAST")
                check((S["water_holes"] as List<List<V2>>?) ?: emptyList(), true, "island")
                // every piece inside its tile
                for (t in tiles) for (f in t.layers[Layer.WATER].orEmpty()) {
                    val g = f["ring"] as Geom
                    val b = t.box
                    for (k in 0 until g.size) assertTrue("${p.name} ${t.level}/${t.x}/${t.y} water ${f["index"]} leaves its tile", g.lat[k] in b[0]..b[2] && g.lon[k] in b[1]..b[3])
                }
            }
            // the quays: water_edges rebuilt from the tiles, every edge's ends, normal and ring
            p.scene.state.restore()
            val edges = Amsterdam.waterEdges(S)
            val quays = p.l14(Layer.QUAY).sortedBy { (it["index"] as Long?) ?: 0L }
            assertEquals("${p.name} quay edges", edges.size, quays.size)
            @Suppress("UNCHECKED_CAST")
            val water = (S["water"] as List<List<V2>>?) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val holes = (S["water_holes"] as List<List<V2>>?) ?: emptyList()
            for ((k, e) in edges.withIndex()) {
                val f = quays[k]
                assertEquals(k.toLong(), (f["index"] as Long?) ?: 0L)
                val g = f["edge"] as Geom
                val a = V2(p.x(g.lon[0]), p.z(g.lat[0]))
                val b = V2(p.x(g.lon[1]), p.z(g.lat[1]))
                assertEquals(e.a, a); assertEquals(e.b, b)
                val ex = b.x - a.x
                val ez = b.y - a.y
                val lg = PyMath.hypot(ex, ez)
                val s = if (f["flip"] == true) -1.0 else 1.0
                assertEquals("${p.name} quay $k nx", e.nx.toRawBits(), (s * (ez / lg)).toRawBits())
                assertEquals("${p.name} quay $k nz", e.nz.toRawBits(), (s * (-ex / lg)).toRawBits())
                val own = if (f["hole"] == true) holes[((f["ring"] as Long?) ?: 0L).toInt()] else water[((f["ring"] as Long?) ?: 0L).toInt()]
                val owner = (f["owner"] as Long?)?.let { water[it.toInt()] } ?: own
                assertTrue("${p.name} quay $k counts as its ring", e.r === owner)
            }
            println("LAYERS ${p.name}: ${edges.size} quay edges equal water_edges, bit for bit")
        }
    }
}
