// render/living.py: the living map, an OSM-shaped neighbourhood rendered with a real sun. Every visible thing comes
// from a feature with OpenStreetMap-style tags. Coordinates: metres, x = east, z = south, y = up. Ported line by line.
package app.atlas.render

import app.atlas.render.City.Camera
import app.atlas.render.City.P
import app.atlas.render.City.area
import app.atlas.render.City.clamp
import app.atlas.render.City.hull2
import app.atlas.render.City.hx
import app.atlas.render.City.mix
import app.atlas.render.City.mulc
import app.atlas.render.City.rect
import app.atlas.render.City.ribbon
import app.atlas.render.City.tohex
import app.atlas.render.City.vcross
import app.atlas.render.City.vdot
import app.atlas.render.City.vnorm
import app.atlas.render.City.vsub
import app.atlas.render.py.Dict
import app.atlas.render.py.PyFmt
import app.atlas.render.py.PyHash
import app.atlas.render.py.PyMath
import app.atlas.render.py.PyRandom
import app.atlas.render.py.dictOf
import app.atlas.render.py.flag
import app.atlas.render.py.int
import app.atlas.render.py.num
import app.atlas.render.py.numOrNull
import app.atlas.render.py.pySortBy
import app.atlas.render.py.str
import app.atlas.render.py.strOrNull
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * One feature of the living map: a kind, a geometry and OSM-style tags, as living.py's `{'kind', 'geom', 'tags'}`.
 * The geometry is the Python tuple: numbers as Doubles, strings, or [V2] points (a list of them for a polygon).
 */
class Feature(val kind: String, val geom: List<Any?>, val tags: Dict) {
    /** `geom[i]` as a float. */
    fun n(i: Int): Double = (geom[i] as Number).toDouble()

    /** `geom[i]` as a point. */
    fun p(i: Int): V2 = geom[i] as V2

    /** The geometry as a polygon (a list of points). */
    @Suppress("UNCHECKED_CAST")
    fun poly(): List<V2> = geom as List<V2>
}

/** The living map, ported from render/living.py. */
object Living {
    const val LAT = 40.717
    const val LON = -73.958

    // ------------------------------------------------------------------ sun

    // render/living.py:19 sun_position
    /** The sun's azimuth and elevation in degrees at a UTC time, at the living map's latitude and longitude. */
    fun sunPosition(tUtc: LocalDateTime): Pair<Double, Double> {
        // (t_utc - datetime(2000, 1, 1, 12)).total_seconds(): whole seconds, exact as a float
        val jd = ChronoUnit.SECONDS.between(LocalDateTime.of(2000, 1, 1, 12, 0), tUtc).toDouble() / 86400
        val L = PyMath.mod(280.46 + 0.9856474 * jd, 360.0)
        val g = PyMath.radians(PyMath.mod(357.528 + 0.9856003 * jd, 360.0))
        val lam = PyMath.radians(L + 1.915 * PyMath.sin(g) + 0.020 * PyMath.sin(2 * g))
        val eps = PyMath.radians(23.439 - 0.0000004 * jd)
        val ra = PyMath.atan2(PyMath.cos(eps) * PyMath.sin(lam), PyMath.cos(lam))
        val dec = PyMath.asin(PyMath.sin(eps) * PyMath.sin(lam))
        val gmst = PyMath.mod(280.46061837 + 360.98564736629 * jd, 360.0)
        val ha = PyMath.radians(PyMath.mod(gmst + LON, 360.0)) - ra
        val la = PyMath.radians(LAT)
        val el = PyMath.asin(PyMath.sin(la) * PyMath.sin(dec) + PyMath.cos(la) * PyMath.cos(dec) * PyMath.cos(ha))
        val az = PyMath.atan2(-PyMath.sin(ha), PyMath.tan(dec) * PyMath.cos(la) - PyMath.sin(la) * PyMath.cos(ha))
        return PyMath.mod(PyMath.degrees(az), 360.0) to PyMath.degrees(el)
    }

    // render/living.py:34 sun_vector
    /** The unit vector toward the sun. */
    fun sunVector(az: Double, el: Double): V3 {
        val a = PyMath.radians(az); val e = PyMath.radians(el)
        return vnorm(V3(PyMath.sin(a) * PyMath.cos(e), PyMath.sin(e), -PyMath.cos(a) * PyMath.cos(e)))
    }

    // ------------------------------------------------------------------ opening hours (tiny subset)

    // render/living.py:39 open_at
    /** Open at hh:mm for "24/7" or comma-separated "hh:mm-hh:mm" ranges (a range may run past midnight). */
    fun openAt(oh: String, hhmm: List<Int>): Boolean {
        if (oh == "24/7") return true
        for (part in oh.split(',')) {
            val ab = PyRe.strip(part).split('-')
            if (ab.size != 2) throw IllegalArgumentException("not enough values to unpack")
            val a = ab[0]; val b = ab[1]
            val ta = PyRe.int(a.substring(0, 2)) * 60 + PyRe.int(a.substring(3))
            var tb = PyRe.int(b.substring(0, 2)) * 60 + PyRe.int(b.substring(3))
            val t = hhmm[0] * 60 + hhmm[1]
            if (tb <= ta) tb += 24 * 60
            if ((ta <= t && t < tb) || (ta <= t + 24 * 60 && t + 24 * 60 < tb)) return true
        }
        return false
    }

    // ------------------------------------------------------------------ the neighbourhood, as OSM-shaped features

    /** Avenues run north-south (x = centre). */
    val AV: LinkedHashMap<String, Double> = linkedMapOf("Wythe Avenue" to 0.0, "Berry Street" to 110.0)

    /** Streets run east-west (z = centre). */
    val ST: LinkedHashMap<String, Double> = linkedMapOf("North 8th Street" to -90.0, "North 7th Street" to 0.0, "North 6th Street" to 90.0)
    const val ROAD_HALF_AV = 7.5
    const val WALK_AV = 4.0
    const val ROAD_HALF_ST = 5.5
    const val WALK_ST = 3.5
    const val X_MIN = -130.0
    const val X_MAX = 250.0
    const val Z_MIN = -200.0
    const val Z_MAX = 170.0

    val BRICKS = listOf("#A4523B", "#8C4634", "#B5634A", "#7E3E2E", "#9A5A43", "#6F4A3A", "#C98E6E", "#D6C3A1", "#B9A58A")
    val CARS = listOf("#D8DDE4", "#3A3F46", "#B8322A", "#2E5BFF", "#F4F4F2", "#6B7282", "#1E3A5F", "#C9A36A", "#E8E8E4", "#2A2D33", "#4F7A5A")

    // render/living.py:59 yard_tags
    fun yardTags(rg: PyRandom): Dict {
        val r = rg.random()
        if (r < 0.55) return dictOf("leisure" to "garden", "surface" to "grass")
        if (r < 0.8) return dictOf("surface" to "paving_stones")
        return dictOf("surface" to "wood")
    }

    /** What [buildScene] makes: the features, the blocks (x0, x1, z0, z1) and the pocket park's block. */
    class SceneData(val F: List<Feature>, val blocks: List<DoubleArray>, val park: DoubleArray)

    private fun overlapsAny(fr: DoubleArray, special: List<DoubleArray>): Boolean =
        special.any { s -> !(fr[1] <= s[0] || s[1] <= fr[0] || fr[3] <= s[2] || s[3] <= fr[2]) }

    private val NS_WIDTHS = listOf(6.0, 6.5, 7.0, 7.5, 8.0, 9.0, 10.0, 12.0)
    private val ROOF_NS = listOf("#BFC2C6", "#B3B6BA", "#C9CCD0", "#D8D6D0", "#4B4A4E", "#5A5856", "#6A6662")
    private val ROOF_EW = listOf("#BFC2C6", "#B3B6BA", "#D8D6D0", "#4B4A4E", "#5A5856")

    // render/living.py:65 build_scene
    /** The neighbourhood around Wythe Av and North 7th St as OSM-shaped features, drawn from Random(seed). */
    fun buildScene(seed: Int = 4): SceneData {
        val rg = PyRandom(seed)
        val F = ArrayList<Feature>()
        fun add(kind: String, geom: List<Any?>, tags: Dict): Feature {
            val f = Feature(kind, geom, tags); F.add(f); return f
        }
        // ---- streets
        for ((name, x) in AV) {
            add("road", listOf("ns", x, ROAD_HALF_AV, WALK_AV), dictOf(
                "highway" to (if (x == 0.0) "secondary" else "residential"), "name" to name,
                "surface" to "asphalt", "lanes" to "2", "sidewalk" to "both", "lit" to "yes",
                "cycleway_right" to (if (x == 0.0) "lane" else "no"), "parking:lane:both" to "parallel", "maxspeed" to "25 mph",
            ))
        }
        for ((name, z) in ST) {
            add("road", listOf("ew", z, ROAD_HALF_ST, WALK_ST), dictOf(
                "highway" to "residential", "name" to name,
                "surface" to (if (name == "North 7th Street") "sett" else "asphalt"), "lanes" to "1", "oneway" to "yes", "sidewalk" to "both", "lit" to "yes",
                "parking:lane:both" to "parallel", "maxspeed" to "25 mph",
            ))
        }
        // ---- blocks
        val xs = listOf(X_MIN) + AV.values.sorted() + listOf(X_MAX)
        val zs = listOf(Z_MIN) + ST.values.sorted() + listOf(Z_MAX)
        val blocks = ArrayList<DoubleArray>()
        for (i in 0 until xs.size - 1) {
            for (j in 0 until zs.size - 1) {
                val x0 = xs[i] + (if (xs[i] in AV.values) ROAD_HALF_AV + WALK_AV else 0.0)
                val x1 = xs[i + 1] - (if (xs[i + 1] in AV.values) ROAD_HALF_AV + WALK_AV else 0.0)
                val z0 = zs[j] + (if (zs[j] in ST.values) ROAD_HALF_ST + WALK_ST else 0.0)
                val z1 = zs[j + 1] - (if (zs[j + 1] in ST.values) ROAD_HALF_ST + WALK_ST else 0.0)
                blocks.add(doubleArrayOf(x0, x1, z0, z1))
            }
        }
        for (b in blocks) add("lot", rect(b[0], b[1], b[2], b[3]), dictOf("landuse" to "residential"))
        // the pocket park: west of Wythe, south of North 7th
        val park = blocks.first { b -> b[1] <= 0 && b[2] >= 0 && b[3] <= 90 }
        add("park", rect(park[0], park[1], park[2], park[3]), dictOf("leisure" to "park", "name" to "Kent Pocket Park"))
        // ---- buildings: perimeter blocks of row houses, special buildings at the corner
        val special = ArrayList<DoubleArray>()
        fun bld(x0: Double, x1: Double, z0: Double, z1: Double, front: String, vararg tags: Pair<String, Any?>): Feature {
            val t = dictOf("front" to front); t.putAll(tags)
            val f = add("building", listOf(x0, x1, z0, z1), t); special.add(doubleArrayOf(x0, x1, z0, z1)); return f
        }
        // NE corner: the bodega, 4 storeys, green awning, open 24/7
        bld(11.5, 24.5, -24.0, -9.5, "S", "building" to "apartments", "building:levels" to "4", "building:material" to "brick", "building:colour" to "#8C4634",
            "roof:shape" to "flat", "shop" to "convenience", "name" to "Kent Deli", "opening_hours" to "24/7", "awning" to "#2E7D4A", "fire_escape" to "S")
        // SE corner: new glass building with a cafe and a solar roof
        bld(11.5, 44.0, 9.5, 36.0, "W", "building" to "apartments", "building:levels" to "8", "building:material" to "glass", "building:colour" to "#7F9DB8",
            "roof:shape" to "flat", "amenity" to "cafe", "name" to "Lume Café", "opening_hours" to "07:00-19:00", "roof_solar" to "yes", "start_date" to "2019")
        // NW corner (river side): old warehouse, now lofts, with a water tank and a roof garden; bar downstairs
        bld(-58.0, -11.5, -46.0, -9.5, "S", "building" to "apartments", "building:levels" to "6", "building:material" to "brick", "building:colour" to "#9A5A43",
            "roof:shape" to "flat", "amenity" to "bar", "name" to "The Gilded Owl", "opening_hours" to "17:00-02:00", "awning" to "#1E2A44",
            "man_made:roof" to "water_tank", "roof_garden" to "yes", "historic" to "warehouse", "start_date" to "1911")
        // a clapboard house with a gabled roof, mid-block on North 7th, east
        bld(64.0, 72.0, -24.0, -9.5, "S", "building" to "house", "building:levels" to "2", "building:material" to "wood", "building:colour" to "#9CC0D6",
            "roof:shape" to "gabled", "roof:colour" to "#5A5F66", "roof:orientation" to "across")
        // a school on the far side of the park block
        bld(-106.0, -64.0, 44.0, 81.5, "E", "building" to "school", "building:levels" to "3", "building:material" to "brick", "building:colour" to "#B5634A",
            "roof:shape" to "flat", "name" to "PS 17")
        // row houses around every block edge
        for (blk in blocks) {
            val x0 = blk[0]; val x1 = blk[1]; val z0 = blk[2]; val z1 = blk[3]
            if (blk.contentEquals(park)) continue
            val depth = 15.0
            for (side in listOf("S", "N", "W", "E")) {
                if (side == "N" || side == "S") {
                    val zb = if (side == "S") doubleArrayOf(z1 - depth, z1) else doubleArrayOf(z0, z0 + depth)
                    var x = x0 + depth
                    while (x < x1 - depth - 4) {
                        val w = rg.choice(NS_WIDTHS)
                        val xe = PyMath.min(x + w, x1 - depth)
                        val fr = doubleArrayOf(x, xe, zb[0], zb[1])
                        x = xe
                        if (overlapsAny(fr, special)) continue
                        val lv = if (Math.abs(zb[0]) < 130) rg.choice(listOf(3, 3, 4, 4, 4, 5, 3, 2)) else rg.choice(listOf(3, 4, 5, 6))
                        val tags = dictOf(
                            "building" to (if (lv >= 4) "apartments" else "house"), "building:levels" to lv.toString(), "building:material" to "brick",
                            "building:colour" to rg.choice(BRICKS), "roof:shape" to "flat",
                        )
                        tags["roof:colour"] = rg.choice(ROOF_NS)
                        tags["start_date"] = rg.choice(listOf(1899, 1905, 1910, 1922, 1931, 2008, 2014)).toString()
                        if (lv >= 4 && (tags["start_date"] as String).toInt() < 1940 && rg.random() < 0.85) tags["fire_escape"] = side
                        if (rg.random() < 0.12) tags["man_made:roof"] = "water_tank"
                        if (rg.random() < 0.08) tags["roof_solar"] = "yes"
                        if (rg.random() < 0.18 && lv >= 3) {
                            tags["shop"] = rg.choice(listOf("books", "bakery", "clothes", "hardware", "florist"))
                            tags["opening_hours"] = rg.choice(listOf("10:00-19:00", "08:00-18:00", "11:00-21:00"))
                            tags["awning"] = rg.choice(listOf("#B8322A", "#1E3A5F", "#2E7D4A", "#C9A36A", "#6B2E4A"))
                        }
                        add("building", fr.toList(), dictOf("front" to side).also { it.putAll(tags) })
                        val zm = (z0 + z1) / 2
                        val yard = if (side == "S") listOf(fr[0], fr[1], zm + 0.2, fr[2]) else listOf(fr[0], fr[1], fr[3], zm - 0.2)
                        add("yard", yard, yardTags(rg))
                    }
                } else {
                    val xb = if (side == "W") doubleArrayOf(x0, x0 + depth) else doubleArrayOf(x1 - depth, x1)
                    var z = z0
                    while (z < z1 - 3) {
                        val w = rg.choice(listOf(6, 7, 8, 9, 10))
                        var ze = PyMath.min(z + w, z1)
                        if (z1 - ze < 5) ze = z1
                        val fr = doubleArrayOf(xb[0], xb[1], z, ze)
                        z = ze
                        if (overlapsAny(fr, special)) continue
                        val lv = rg.choice(listOf(3, 4, 4, 5, 3))
                        val tags = dictOf(
                            "building" to (if (lv >= 4) "apartments" else "house"), "building:levels" to lv.toString(), "building:material" to "brick",
                            "building:colour" to rg.choice(BRICKS), "roof:shape" to "flat",
                        )
                        tags["roof:colour"] = rg.choice(ROOF_EW)
                        tags["start_date"] = rg.choice(listOf(1899, 1905, 1910, 1922, 1931, 2011)).toString()
                        if (lv >= 4 && (tags["start_date"] as String).toInt() < 1940 && rg.random() < 0.8) tags["fire_escape"] = side
                        if (rg.random() < 0.1) tags["man_made:roof"] = "water_tank"
                        if (rg.random() < 0.15 && lv >= 3) {
                            tags["shop"] = rg.choice(listOf("books", "bakery", "clothes", "hairdresser", "wine"))
                            tags["opening_hours"] = rg.choice(listOf("10:00-19:00", "12:00-22:00", "09:00-17:00"))
                            tags["awning"] = rg.choice(listOf("#B8322A", "#1E3A5F", "#2E7D4A", "#C9A36A"))
                        }
                        add("building", fr.toList(), dictOf("front" to side).also { it.putAll(tags) })
                        val xm = (x0 + x1) / 2
                        val za = PyMath.max(fr[2], z0 + depth); val zb2 = PyMath.min(fr[3], z1 - depth)
                        if (zb2 - za > 1) {
                            val yard = if (side == "W") listOf(fr[1], xm - 0.2, za, zb2) else listOf(xm + 0.2, fr[0], za, zb2)
                            add("yard", yard, yardTags(rg))
                        }
                    }
                }
            }
        }
        // backyard trees: tree of heaven and mulberry, the Brooklyn backyard classics
        for (f in F.toList()) {
            if (f.kind == "yard" && f.tags["leisure"] == "garden" && rg.random() < 0.5) {
                val x0 = f.n(0); val x1 = f.n(1); val z0 = f.n(2); val z1 = f.n(3)
                if (x1 - x0 > 3 && z1 - z0 > 3) {
                    val gx = (x0 + x1) / 2 + rg.uniform(-1.0, 1.0); val gz = (z0 + z1) / 2 + rg.uniform(-1.0, 1.0)
                    val genus = rg.choice(listOf("Ailanthus", "Morus", "Ailanthus", "Acer"))
                    val height = PyMath.round(rg.uniform(7.0, 13.0)).toString()
                    val crown = PyMath.round(rg.uniform(4.0, 7.0)).toString()
                    add("tree", listOf(gx, gz), dictOf("natural" to "tree", "genus" to genus, "leaf_cycle" to "deciduous", "height" to height, "diameter_crown" to crown))
                }
            }
        }
        // ---- trees along sidewalks
        fun treesAlong(p0: V2, p1: V2, step: Double) {
            val n = PyMath.int(PyMath.hypot(p1[0] - p0[0], p1[1] - p0[1]) / step).toInt()
            for (k in 1 until n) {
                val t = k.toDouble() / n
                val x = p0[0] + (p1[0] - p0[0]) * t; val z = p0[1] + (p1[1] - p0[1]) * t
                if (rg.random() < 0.18) continue
                val genus = rg.choice(listOf("Platanus", "Platanus", "Gleditsia", "Tilia", "Ginkgo", "Pyrus", "Quercus", "Platanus"))
                val gx = x + rg.uniform(-.4, .4); val gz = z + rg.uniform(-.4, .4)
                val height = PyMath.round(rg.uniform(8.0, 17.0)).toString()
                val crown = PyMath.round(rg.uniform(5.0, 9.0)).toString()
                add("tree", listOf(gx, gz), dictOf("natural" to "tree", "genus" to genus, "leaf_cycle" to "deciduous", "height" to height, "diameter_crown" to crown))
            }
        }
        for ((_, x) in AV) {
            for (s in intArrayOf(-1, 1)) {
                val xx = x + s * (ROAD_HALF_AV + 1.3)
                for ((za, zb) in listOf(Z_MIN to -98.0, -80.0 to -12.0, 12.0 to 80.0, 98.0 to Z_MAX)) {
                    treesAlong(V2(xx, za + 6), V2(xx, zb - 6), 9.0)
                }
            }
        }
        for ((_, z) in ST) {
            for (s in intArrayOf(-1, 1)) {
                val zz = z + s * (ROAD_HALF_ST + 1.2)
                for ((xa, xb) in listOf(X_MIN to -12.0, 12.0 to 98.0, 122.0 to X_MAX)) {
                    treesAlong(V2(xa + 6, zz), V2(xb - 6, zz), 9.0)
                }
            }
        }
        // park trees, benches, paths, playground
        val px0 = park[0]; val px1 = park[1]; val pz0 = park[2]; val pz1 = park[3]
        for (q in 0 until 26) {
            val x = rg.uniform(px0 + 5, px1 - 5); val z = rg.uniform(pz0 + 5, pz1 - 8)
            if (Math.abs(z - (pz0 + pz1) / 2) < 4 || Math.abs(x - (px0 + px1) / 2) < 4) continue
            if (-44 < x && x < -16 && 16 < z && z < 38) continue
            val genus = rg.choice(listOf("Quercus", "Acer", "Platanus", "Betula"))
            val height = PyMath.round(rg.uniform(10.0, 18.0)).toString()
            val crown = PyMath.round(rg.uniform(7.0, 12.0)).toString()
            add("tree", listOf(x, z), dictOf("natural" to "tree", "genus" to genus, "leaf_cycle" to "deciduous", "height" to height, "diameter_crown" to crown))
        }
        add("path", listOf(V2(px0, (pz0 + pz1) / 2), V2(px1, (pz0 + pz1) / 2)), dictOf("highway" to "footway", "surface" to "paving_stones"))
        add("path", listOf(V2((px0 + px1) / 2, pz0), V2((px0 + px1) / 2, pz1)), dictOf("highway" to "footway", "surface" to "paving_stones"))
        for (k in 0 until 4) {
            add("bench", listOf((px0 + px1) / 2 + 3.5, pz0 + 10 + k * 7, "z"), dictOf("amenity" to "bench", "backrest" to "yes"))
        }
        add("playground", rect(-40.0, -20.0, 20.0, 34.0), dictOf("leisure" to "playground", "surface" to "rubber"))
        add("fountain", listOf(-(px1 - px0) / 2 + px1 - 4.5, (pz0 + pz1) / 2 - 4.5), dictOf("amenity" to "drinking_water", "bottle" to "yes", "seasonal" to "no"))
        for (k in 0 until 5) {
            val x = rg.uniform(px0 + 20, px1 - 8); val z = rg.uniform(pz0 + 8, pz1 - 20)
            add("person_park", listOf(x, z), dictOf())
        }
        // ---- street furniture
        for ((_, x) in AV) {
            for (s in intArrayOf(-1, 1)) {
                val xx = x + s * (ROAD_HALF_AV + 0.5)
                var z = Z_MIN.toInt() + 8
                while (z < Z_MAX.toInt()) {
                    if (!(Math.abs(z) < 10 || Math.abs(z - 90) < 10 || Math.abs(z + 90) < 10)) {
                        add("lamp", listOf(xx, (z + (if (s > 0) 0 else 14)).toDouble(), (-s).toDouble()),
                            dictOf("highway" to "street_lamp", "lamp_mount" to "bent_mast", "light_colour" to "orange"))
                    }
                    z += 28
                }
            }
        }
        for ((_, z) in ST) {
            for (s in intArrayOf(-1, 1)) {
                val zz = z + s * (ROAD_HALF_ST + 0.5)
                var x = X_MIN.toInt() + 10
                while (x < X_MAX.toInt()) {
                    if (!(Math.abs(x) < 12 || Math.abs(x - 110) < 12)) {
                        add("lamp", listOf((x + (if (s > 0) 0 else 15)).toDouble(), zz, (-s * 2).toDouble()),
                            dictOf("highway" to "street_lamp", "lamp_mount" to "bent_mast", "light_colour" to "orange"))
                    }
                    x += 30
                }
            }
        }
        for ((x, z) in listOf(8.2 to -30.0, -8.2 to 30.0, 40.0 to -6.2, -40.0 to 6.2, 102.0 to 40.0, 8.2 to 60.0)) {
            add("hydrant", listOf(x, z), dictOf("emergency" to "fire_hydrant", "fire_hydrant:type" to "pillar", "colour" to "red"))
        }
        add("subway", listOf(14.5, -9 + 2.2), dictOf("railway" to "subway_entrance", "name" to "Bedford Av (L)", "wheelchair" to "no", "level" to "0"))
        add("bus_stop", listOf(9.6, 48.0), dictOf("highway" to "bus_stop", "shelter" to "yes", "bench" to "yes", "route_ref" to "B32"))
        for ((x, z) in listOf(-9.6 to -40.0, 30.0 to 6.8, 60.0 to -6.9)) {
            add("bike_rack", listOf(x, z), dictOf("amenity" to "bicycle_parking", "bicycle_parking" to "stands", "capacity" to "6"))
        }
        // ---- crossings (ladder markings) at every intersection leg
        for (ax in AV.values) {
            for (sz in ST.values) {
                add("crossing", listOf("ns", ax, sz), dictOf("highway" to "crossing", "crossing" to "marked", "crossing:markings" to "ladder"))
            }
        }
        return SceneData(F, blocks, park)
    }

    // ------------------------------------------------------------------ themes
    val GOLDEN: Map<String, Any?> = dictOf(
        "name" to "golden", "hhmm" to listOf(17, 20), "night" to false,
        "sky_top" to "#86A7D6", "sky_hor" to "#F6D9B8", "haze" to "#F3DCC0", "haze_max" to 0.55,
        "sun" to "#FFD9A6", "sun_k" to 1.0, "amb" to "#8FA0C8", "amb_k" to 0.62,
        "asphalt" to "#7A736D", "asphalt_sun" to "#8C8074", "sett" to "#8A7C70", "walk" to "#DCCFBC", "walk_sun" to "#E6D3B5", "curb" to "#F2E8D8",
        "lawn" to "#8FAE68", "lawn_sun" to "#B0B865", "lot" to "#A89A82", "path" to "#E2D5C0", "lane_y" to "#E8C04A", "lane_w" to "#F2EEE6", "bike" to "#4E9A5C",
        "yard_grass" to "#8CA462", "yard_pave" to "#BDAE96", "yard_wood" to "#A98460", "fence" to "#8A6E52",
        "shadow" to "#27305A", "shadow_op" to 0.42, "tree" to "#6A8A46", "tree_sun" to "#C8B864", "tree_dark" to "#34502E", "autumn" to listOf("#C98A2E", "#D9A441", "#B8562E"),
        "roof" to "#6B6560", "roof_sun" to "#A08B76", "glass_sky" to "#9FB6D2", "glass_sun" to "#F2C99A", "win" to "#566779", "win_sun" to "#C9D7E4", "frame" to "#EDE6D8",
    )
    val NIGHT: Map<String, Any?> = dictOf(
        "name" to "night", "hhmm" to listOf(21, 30), "night" to true,
        "sky_top" to "#050814", "sky_hor" to "#1C2340", "haze" to "#161D36", "haze_max" to 0.6,
        "sun" to "#000000", "sun_k" to 0.0, "amb" to "#39456B", "amb_k" to 0.5,
        "asphalt" to "#15192A", "asphalt_sun" to "#15192A", "sett" to "#1A1C28", "walk" to "#232838", "walk_sun" to "#232838", "curb" to "#343B52",
        "lawn" to "#14231E", "lawn_sun" to "#14231E", "lot" to "#141824", "path" to "#262B3B", "lane_y" to "#6E6232", "lane_w" to "#4A4F62", "bike" to "#1F3A2A",
        "yard_grass" to "#121E1A", "yard_pave" to "#1A1D28", "yard_wood" to "#1C1A1E", "fence" to "#262A36",
        "shadow" to "#000000", "shadow_op" to 0.0, "tree" to "#10201C", "tree_sun" to "#10201C", "tree_dark" to "#0A1512", "autumn" to listOf("#2A2618", "#2E2A1A", "#2A1C14"),
        "roof" to "#1A1E2C", "roof_sun" to "#1A1E2C", "glass_sky" to "#1B2440", "glass_sun" to "#1B2440", "win" to "#1E2436", "win_sun" to "#1E2436", "frame" to "#3A3F52",
    )
    val NOON: Map<String, Any?> = LinkedHashMap(GOLDEN).also {
        it.putAll(dictOf(
            "name" to "noon", "hhmm" to listOf(12, 0), "sky_top" to "#7FA6DA", "sky_hor" to "#DCE7F2", "haze" to "#DDE6EE", "haze_max" to 0.5,
            "sun" to "#FFF3DE", "sun_k" to 0.92, "amb" to "#9AAAD0", "amb_k" to 0.64, "shadow" to "#2B3560", "shadow_op" to 0.36,
            "glass_sky" to "#A9C2DE", "glass_sun" to "#E8EEF4", "tree_sun" to "#B9C06E",
        ))
    }

    // ------------------------------------------------------------------ renderer

    /** A building's box for the sun's rays: footprint, height and the feature. */
    class Box(val x0: Double, val x1: Double, val z0: Double, val z1: Double, val h: Double, val f: Feature)

    // render/living.py:261 Scene
    /**
     * What the draw functions see: the features, the theme, the camera, the sun vector and the buildings' boxes.
     * [sunlit] is the module function `sunlit` as this scene sees it: real.py replaces `living.sunlit` with its own
     * ray march, and its scenes carry that replacement here instead of patching a global.
     */
    class Scene(val F: List<Feature>, val th: Map<String, Any?>, val cam: Camera, val L: V3, val boxes: List<Box>) {
        constructor(F: List<Feature>, th: Map<String, Any?>, cam: Camera, L: V3) : this(F, th, cam, L, boxesOf(F))

        var sunlit: (Scene, V3, Any?) -> Boolean = { s, p, skip -> Living.sunlit(s, p, skip) }

        /** `sunlit(scene, p, skip)` through this scene's [sunlit]. */
        fun lit(p: V3, skip: Any? = null): Boolean = sunlit(this, p, skip)

        /** Real.kt's draw state: real.py's `Sc` with its renderer's settings. */
        var roofK: Double = 1.0

        companion object {
            fun boxesOf(F: List<Feature>): List<Box> {
                val boxes = ArrayList<Box>()
                for (f in F) {
                    if (f.kind == "building") boxes.add(Box(f.n(0), f.n(1), f.n(2), f.n(3), heightOf(f.tags), f))
                }
                return boxes
            }
        }
    }

    // render/living.py:270 height_of
    fun heightOf(t: Map<String, Any?>): Double {
        val lv = PyFmt.str(t["building:levels"] ?: 3).let { java.lang.Double.parseDouble(PyRe.asciiDigits(PyRe.strip(it))) }
        return lv * 3.2 + (if (t["roof:shape"] == "flat") 1.0 else 0.0)
    }

    // render/living.py:275 sunlit
    /** Ray from p toward the sun; true if nothing blocks it. */
    fun sunlit(scene: Scene, p: V3, skip: Any? = null): Boolean {
        val L = scene.L
        if (L[1] <= 0) return false
        val o = DoubleArray(3); val dd = DoubleArray(3); val lo = DoubleArray(3); val hi = DoubleArray(3)
        for (b in scene.boxes) {
            if (b.f === skip) continue
            var tmin = 1e-3; var tmax = 1e9
            o[0] = p[0]; dd[0] = L[0]; lo[0] = b.x0; hi[0] = b.x1
            o[1] = p[1]; dd[1] = L[1]; lo[1] = 0.0; hi[1] = b.h
            o[2] = p[2]; dd[2] = L[2]; lo[2] = b.z0; hi[2] = b.z1
            for (k in 0 until 3) {
                if (Math.abs(dd[k]) < 1e-9) {
                    if (o[k] < lo[k] || o[k] > hi[k]) {
                        tmin = 1e9; break
                    }
                } else {
                    var ta = (lo[k] - o[k]) / dd[k]; var tb = (hi[k] - o[k]) / dd[k]
                    if (ta > tb) {
                        val s = ta; ta = tb; tb = s
                    }
                    tmin = PyMath.max(tmin, ta); tmax = PyMath.min(tmax, tb)
                    if (tmin > tmax) break
                }
            }
            if (tmin <= tmax && tmin < 400) return false
        }
        return true
    }

    // render/living.py:293 light
    /** Colour a surface with normal n: warm sun if lit, cool sky light always. */
    fun light(th: Map<String, Any?>, base: V3, n: V3, lit: Boolean): V3 {
        val L = th["_L"] as V3
        val ambK = th.num("amb_k")
        val amb = mix(mulc(base, ambK), mulc(hx(th.str("amb")), ambK * 0.55), 0.35)
        if (th.flag("night")) return mulc(mix(base, hx(th.str("amb")), 0.55), 0.34)
        val lam = if (lit) PyMath.max(0.0, vdot(n, L)) else 0.0
        val sky = 0.18 + 0.12 * PyMath.max(0.0, n[1])
        val sunK = th.num("sun_k"); val sun = hx(th.str("sun"))
        return V3(
            amb[0] + base[0] * (sky) + base[0] * lam * 1.05 * sunK * sun[0] / 255,
            amb[1] + base[1] * (sky) + base[1] * lam * 1.05 * sunK * sun[1] / 255,
            amb[2] + base[2] * (sky) + base[2] * lam * 1.05 * sunK * sun[2] / 255,
        )
    }

    private val ZERO = V3(0.0, 0.0, 0.0)

    // render/living.py:304 quad_at
    /** The wall quad between u0 and u1 along a-b, from y0 to y1, pushed off along nrm by off. */
    fun quadAt(a: V2, b: V2, y0: Double, y1: Double, u0: Double, u1: Double, off: Double = 0.0, nrm: V3 = ZERO): List<V3> {
        val ax = a[0] + (b[0] - a[0]) * u0 + nrm[0] * off; val az = a[1] + (b[1] - a[1]) * u0 + nrm[2] * off
        val bx = a[0] + (b[0] - a[0]) * u1 + nrm[0] * off; val bz = a[1] + (b[1] - a[1]) * u1 + nrm[2] * off
        return listOf(V3(ax, y0, az), V3(bx, y0, bz), V3(bx, y1, bz), V3(ax, y1, az))
    }

    // render/living.py:309 ground_text
    /** Text painted on the ground (or a wall): exact perspective at the anchor, affine around it. */
    fun groundText(
        cam: Camera, text: String, p0: V3, u: V3, v: V3, size: Double, fill: String, op: Double = 1.0, weight: Int = 800,
        ls: Double = 0.18, anchor: String = "middle",
    ): String {
        val S0 = cam.proj(p0)
        val Sx = cam.proj(V3(p0[0] + u[0], p0[1] + u[1], p0[2] + u[2]))
        val Sy = cam.proj(V3(p0[0] + v[0], p0[1] + v[1], p0[2] + v[2]))
        if (S0 == null || Sx == null || Sy == null) return ""
        val a = Sx[0] - S0[0]; val b = Sx[1] - S0[1]
        val c = Sy[0] - S0[0]; val d = Sy[1] - S0[1]
        if (Math.abs(a * d - b * c) < 0.02) return ""
        return PyFmt.pf(
            "<text transform=\"matrix(%.3f %.3f %.3f %.3f %.1f %.1f)\" font-family=\"Geist, Helvetica, sans-serif\" font-size=\"%.2f\" font-weight=\"%d\" " +
                "letter-spacing=\"%.2f\" text-anchor=\"%s\" fill=\"%s\" opacity=\"%.2f\">%s</text>",
            a, b, c, d, S0[0], S0[1], size, weight, ls, anchor, fill, op, text,
        )
    }

    /** What [render] returns: the SVG, the anchors for UI callouts, and the camera. */
    class Rendered(val svg: String, val anchors: LinkedHashMap<String, Any?>, val cam: Camera)

    private class Item(val d: Double, val f: Feature)

    private fun seg(p0: V2, p1: V2): String = PyFmt.pf("M%.1f %.1fL%.1f %.1f", p0[0], p0[1], p1[0], p1[1])

    // render/living.py:321 render
    fun render(th0: Map<String, Any?>, cfg: Map<String, Any?>, F: List<Feature>, blocks: List<DoubleArray>, park: DoubleArray): Rendered {
        val W = cfg.int("W"); val H = cfg.int("H")
        val cam = Camera(v3(cfg["target"]), cfg.num("yaw"), cfg.num("pitch"), cfg.num("dist"), cfg.num("fov"), W.toDouble(), H.toDouble(),
            cx = cfg.numOrNull("cx"), cy = cfg.numOrNull("cy"))
        @Suppress("UNCHECKED_CAST")
        val hhmm = th0["hhmm"] as List<Int>
        val tUtc = if (hhmm[0] + 4 < 24) LocalDateTime.of(2026, 9, 30, hhmm[0] + 4, hhmm[1]) else LocalDateTime.of(2026, 10, 1, hhmm[0] + 4 - 24, hhmm[1])
        val (az, el) = sunPosition(tUtc)
        val L = if (el > 0) sunVector(az, el) else V3(0.0, -1.0, 0.0)
        val th = LinkedHashMap(th0); th["_L"] = L
        val scene = Scene(F, th, cam, L)
        val rg = PyRandom(9)
        val ground = ArrayList<String>(); val markings = ArrayList<String>(); val shadows = ArrayList<String>(); val items = ArrayList<Item>()
        val glow = ArrayList<String>(); val labels = ArrayList<String>(); val anchors = LinkedHashMap<String, Any?>()
        val defs = ArrayList<String>()
        val night = th.flag("night")
        // sky
        val hy = cam.horizonY()
        defs.add(PyFmt.pf("<linearGradient id=\"sky\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"%.0f\" x2=\"0\" y2=\"%.0f\"><stop offset=\"0\" stop-color=\"%s\"/><stop offset=\"1\" stop-color=\"%s\"/></linearGradient>",
            hy - 300, hy, th["sky_top"], th["sky_hor"]))
        ground.add(PyFmt.pf("<rect width=\"%d\" height=\"%d\" fill=\"url(#sky)\"/>", W, H))
        fun gpoly(pts: List<V2>, fill: Any?, y: Double = 0.0, extra: String = ""): String {
            val sp = cam.poly(pts.map { V3(it.x, y, it.y) }, margin = 600.0)
            if (sp != null && area(sp) > 0.2) return PyFmt.pf("<path d=\"%s\" fill=\"%s\"%s/>", P(sp), fill, extra)
            return ""
        }
        // base: whole area as sidewalk
        ground.add(gpoly(rect(X_MIN - 400, X_MAX + 400, Z_MIN - 600, Z_MAX + 200), th["walk"]))
        // roads
        for (f in F) {
            if (f.kind != "road") continue
            val t = f.tags; val o = f.geom[0] as String; val c = f.n(1); val half = f.n(2)
            val col = if (t["surface"] == "sett") th["sett"] else th["asphalt"]
            val pts = if (o == "ns") rect(c - half, c + half, Z_MIN - 600, Z_MAX + 200) else rect(X_MIN - 400, X_MAX + 400, c - half, c + half)
            ground.add(gpoly(pts, col))
        }
        // cobbles on North 7th west of Wythe: grout lines across and a few along
        if (!night) {
            val segs = StringBuilder()
            val lo = PyMath.int((X_MIN - 60) * 2.2).toInt(); val hiX = PyMath.int(-ROAD_HALF_AV * 2.2).toInt()
            for (x10 in lo until hiX) {
                val x = x10 / 2.2
                val p0 = cam.proj(V3(x, 0.01, -ROAD_HALF_ST)); val p1 = cam.proj(V3(x, 0.01, ROAD_HALF_ST))
                if (p0 != null && p1 != null && -20 < p0[0] && p0[0] < W + 20 && -20 < p0[1] && p0[1] < H + 40) segs.append(seg(p0, p1))
            }
            for (zz in doubleArrayOf(-4.4, -3.3, -2.2, -1.1, 0.0, 1.1, 2.2, 3.3, 4.4)) {
                val p0 = cam.proj(V3(X_MIN - 60, 0.01, zz)); val p1 = cam.proj(V3(-ROAD_HALF_AV, 0.01, zz))
                if (p0 != null && p1 != null) segs.append(seg(p0, p1))
            }
            markings.add(PyFmt.pf("<path d=\"%s\" stroke=\"#4E463F\" stroke-width=\".45\" opacity=\".55\" fill=\"none\"/>", segs.toString()))
        }
        // lots, park, playground, paths
        for (f in F) {
            if (f.kind == "lot") ground.add(gpoly(f.poly(), th["lot"]))
        }
        val fence = StringBuilder()
        for (f in F) {
            if (f.kind != "yard") continue
            val t = f.tags; val x0 = f.n(0); val x1 = f.n(1); val z0 = f.n(2); val z1 = f.n(3)
            val col = if (t["surface"] == "grass") th["yard_grass"] else if (t["surface"] == "wood") th["yard_wood"] else th["yard_pave"]
            ground.add(gpoly(rect(x0, x1, z0, z1), col, 0.01))
            val sp = cam.poly(rect(x0, x1, z0, z1).map { V3(it.x, 0.02, it.y) })
            if (sp != null) fence.append(P(sp))
        }
        if (fence.isNotEmpty()) ground.add(PyFmt.pf("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\".7\" opacity=\".75\"/>", fence.toString(), th["fence"]))
        for (f in F) {
            if (f.kind == "park") ground.add(gpoly(f.poly(), th["lawn"]))
            if (f.kind == "playground") ground.add(gpoly(f.poly(), if (!night) "#D98E5A" else "#2A2230"))
        }
        for (f in F) {
            if (f.kind == "path") ground.add(gpoly(ribbon(f.poly(), 3.2), th["path"], 0.02))
        }
        // sidewalk slab joints + curbs + tree pits + lane markings
        val joint = StringBuilder(); val curb = StringBuilder()
        for (f in F) {
            if (f.kind != "road") continue
            val t = f.tags; val o = f.geom[0] as String; val c = f.n(1); val half = f.n(2); val walk = f.n(3)
            for (s in intArrayOf(-1, 1)) {
                val a: V2?; val b: V2?
                if (o == "ns") {
                    a = cam.proj(V3(c + s * half, 0.03, Z_MIN - 300)); b = cam.proj(V3(c + s * half, 0.03, Z_MAX))
                } else {
                    a = cam.proj(V3(X_MIN - 300, 0.03, c + s * half)); b = cam.proj(V3(X_MAX + 300, 0.03, c + s * half))
                }
                if (a != null && b != null) curb.append(seg(a, b))
                if (!night) {
                    if (o == "ns") {
                        var z = Z_MIN.toInt()
                        while (z < Z_MAX.toInt()) {
                            val p0 = cam.proj(V3(c + s * half, 0.02, z.toDouble())); val p1 = cam.proj(V3(c + s * (half + walk), 0.02, z.toDouble()))
                            if (p0 != null && p1 != null && 0 < p0[1] && p0[1] < H) joint.append(seg(p0, p1))
                            z += 2
                        }
                    } else {
                        var x = X_MIN.toInt()
                        while (x < X_MAX.toInt()) {
                            val p0 = cam.proj(V3(x.toDouble(), 0.02, c + s * half)); val p1 = cam.proj(V3(x.toDouble(), 0.02, c + s * (half + walk)))
                            if (p0 != null && p1 != null && 0 < p0[1] && p0[1] < H) joint.append(seg(p0, p1))
                            x += 2
                        }
                    }
                }
            }
            // centre line / bike lane
            if (o == "ns") {
                if (t["lanes"] == "2") {
                    for (dx in doubleArrayOf(-0.12, 0.12)) {
                        val a = cam.proj(V3(c + dx, 0.04, Z_MIN - 300)); val b = cam.proj(V3(c + dx, 0.04, Z_MAX))
                        if (a != null && b != null) {
                            markings.add(PyFmt.pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"%s\" stroke-width=\"%.2f\" fill=\"none\"/>", a[0], a[1], b[0], b[1], th["lane_y"], 0.9))
                        }
                    }
                }
                if (t["cycleway_right"] == "lane") {
                    markings.add(gpoly(rect(c + half - 3.8, c + half - 2.2, Z_MIN - 300, Z_MAX), th["bike"], 0.03, " opacity=\".85\""))
                    var z = Z_MIN.toInt()
                    while (z < Z_MAX.toInt()) {
                        if (!(Math.abs(z) < 14 || Math.abs(z - 90) < 14 || Math.abs(z + 90) < 14)) {
                            markings.add(groundText(cam, "⚲", V3(c + half - 3.0, 0.05, z.toDouble()), V3(0.0, 0.0, -1.0), V3(1.0, 0.0, 0.0), 1.2, "#FFFFFF", 0.0))
                        }
                        z += 30
                    }
                }
            }
        }
        if (joint.isNotEmpty()) markings.add(PyFmt.pf("<path d=\"%s\" stroke=\"#9F968A\" stroke-width=\".4\" opacity=\".45\" fill=\"none\"/>", joint.toString()))
        if (curb.isNotEmpty()) markings.add(PyFmt.pf("<path d=\"%s\" stroke=\"%s\" stroke-width=\"1.1\" fill=\"none\"/>", curb.toString(), th["curb"]))
        // crossings: ladder stripes across each leg of the Wythe / North 7th intersection and neighbours
        for (f in F) {
            if (f.kind != "crossing") continue
            val ax = f.n(1); val sz = f.n(2)
            val stripes = ArrayList<List<V2>>()
            for (leg in listOf("n", "s", "e", "w")) {
                if (leg == "n" || leg == "s") {
                    val zc = sz + (if (leg == "n") -(ROAD_HALF_ST + 2.4) else ROAD_HALF_ST + 2.4)
                    for (k in -6..6) {
                        val x = ax + k * 1.15
                        stripes.add(rect(x - 0.35, x + 0.35, zc - 1.6, zc + 1.6))
                    }
                } else {
                    val xc = ax + (if (leg == "e") (ROAD_HALF_AV + 2.4) else -(ROAD_HALF_AV + 2.4))
                    for (k in -4..4) {
                        val z = sz + k * 1.15
                        stripes.add(rect(xc - 1.6, xc + 1.6, z - 0.35, z + 0.35))
                    }
                }
            }
            val ps = StringBuilder()
            for (r in stripes) {
                val sp = cam.poly(r.map { V3(it.x, 0.05, it.y) })
                if (sp != null) ps.append(P(sp))
            }
            markings.add(PyFmt.pf("<path d=\"%s\" fill=\"%s\" opacity=\"%.2f\"/>", ps.toString(), "#F4F1EA", if (night) 0.2 else 0.88))
        }
        // street names painted on the road
        for (f in F) {
            if (f.kind != "road") continue
            val t = f.tags; val o = f.geom[0] as String; val c = f.n(1)
            val nm = t.str("name").uppercase(java.util.Locale.ROOT).replace("NORTH ", "N ").replace("STREET", "ST").replace("AVENUE", "AV")
            if (o == "ew") {
                for (x in intArrayOf(38, -40, 160)) {
                    labels.add(groundText(cam, nm, V3(x.toDouble(), 0.06, c + 1.6), V3(1.0, 0.0, 0.0), V3(0.0, 0.0, 1.0), 2.4, "#F4F1EA", if (night) 0.18 else 0.62))
                }
            } else {
                for (z in intArrayOf(-45, 45, -130)) {
                    labels.add(groundText(cam, nm, V3(c - 2.6, 0.06, z.toDouble()), V3(0.0, 0.0, -1.0), V3(1.0, 0.0, 0.0), 2.4, "#F4F1EA", if (night) 0.18 else 0.62))
                }
            }
        }
        // ---------------------------------------------------------- shadows on the ground
        fun shoff(y: Double): V2 = V2(-L[0] / L[1] * y, -L[2] / L[1] * y)
        if (!night && L[1] > 0) {
            val shp = StringBuilder()
            for (b in scene.boxes) {
                val foot = rect(b.x0, b.x1, b.z0, b.z1)
                val o = shoff(b.h)
                val pts = foot + foot.map { V2(it.x + o[0], it.y + o[1]) }
                val hl = hull2(pts)
                val sp = cam.poly(hl.map { V3(it.x, 0.08, it.y) }, margin = 800.0)
                if (sp != null) shp.append(P(sp))
            }
            for (f in F) {
                if (f.kind == "tree") {
                    val x = f.n(0); val z = f.n(1); val t = f.tags; val h = t.str("height").toDouble(); val r = t.str("diameter_crown").toDouble() / 2
                    val o = shoff(h * 0.72)
                    val pts = (0 until 16).map { k ->
                        val a = k * PyMath.PI / 8
                        V3(x + o[0] + r * PyMath.cos(a) * 1.0 + (o[0] * 0.18) * PyMath.pow(PyMath.cos(a), 2.0), 0.08, z + o[1] + r * PyMath.sin(a))
                    }
                    var sp = cam.poly(pts)
                    if (sp != null) shp.append(P(sp))
                    val o2 = shoff(h * 0.45)
                    val trunk = ribbon(listOf(V2(x, z), V2(x + o2[0], z + o2[1])), 0.35)
                    sp = cam.poly(trunk.map { V3(it.x, 0.08, it.y) })
                    if (sp != null) shp.append(P(sp))
                }
                if (f.kind == "lamp") {
                    val x = f.n(0); val z = f.n(1); val o = shoff(8.5)
                    val pole = ribbon(listOf(V2(x, z), V2(x + o[0], z + o[1])), 0.22)
                    val sp = cam.poly(pole.map { V3(it.x, 0.08, it.y) })
                    if (sp != null) shp.append(P(sp))
                }
            }
            shadows.add(PyFmt.pf("<path d=\"%s\" fill=\"%s\" opacity=\"%.2f\"/>", shp.toString(), th["shadow"], th["shadow_op"]))
        }
        // ---------------------------------------------------------- objects
        for (f in F) {
            val k = f.kind
            if (k == "building") {
                val x0 = f.n(0); val x1 = f.n(1); val z0 = f.n(2); val z1 = f.n(3)
                val c = cam.cam(V3((x0 + x1) / 2, 4.0, (z0 + z1) / 2))
                if (c[2] < 2) continue
                items.add(Item(c[2], f))
            } else if (k in DRAWN) {
                val c = cam.cam(V3(f.n(0), 1.0, f.n(1)))
                if (c[2] < 2) continue
                items.add(Item(c[2], f))
            }
        }
        for (f in F) {
            if (f.kind == "playground") {
                val g = f.poly(); val x0 = g[0][0]; val x1 = g[2][0]; val z0 = g[0][1]; val z1 = g[2][1]
                val c = cam.cam(V3((x0 + x1) / 2, 1.0, (z0 + z1) / 2))
                if (c[2] > 2) items.add(Item(c[2], f))
            }
        }
        // parked cars from parking:lane=parallel (placed, not mapped)
        val cars = ArrayList<Triple<Double, Double, String>>()
        for (f in F) {
            if (f.kind != "road") continue
            val o = f.geom[0] as String; val c = f.n(1); val half = f.n(2)
            for (s in intArrayOf(-1, 1)) {
                if (o == "ns") {
                    var lane = c + s * (half - 1.1)
                    if (f.tags["cycleway_right"] == "lane" && s > 0) lane = c + s * (half - 0.9)
                    var z = Z_MIN
                    while (z < Z_MAX) {
                        z += rg.uniform(5.2, 9.0)
                        if (ST.values.any { sz -> Math.abs(z - sz) < 14 } || rg.random() < 0.22) continue
                        cars.add(Triple(lane, z, "ns"))
                    }
                } else {
                    val lane = c + s * (half - 1.1)
                    var x = X_MIN
                    while (x < X_MAX) {
                        x += rg.uniform(5.2, 9.0)
                        if (AV.values.any { ax -> Math.abs(x - ax) < 16 } || rg.random() < 0.22) continue
                        cars.add(Triple(x, lane, "ew"))
                    }
                }
            }
        }
        for ((x, z, o) in cars) {
            val c = cam.cam(V3(x, 1.0, z))
            if (c[2] < 2) continue
            items.add(Item(c[2], Feature("car", listOf(x, z, o), dictOf("_col" to rg.choice(CARS)))))
        }
        // people
        for (k in 0 until 34) {
            val side = rg.choice(listOf("av", "st"))
            val x: Double; val z: Double
            if (side == "av") {
                val ax = rg.choice(AV.values.toList()); val s = rg.choice(listOf(-1, 1))
                x = ax + s * (ROAD_HALF_AV + rg.uniform(1.8, 3.4)); z = rg.uniform(-120.0, 120.0)
            } else {
                val sz = rg.choice(ST.values.toList()); val s = rg.choice(listOf(-1, 1))
                x = rg.uniform(-100.0, 200.0); z = sz + s * (ROAD_HALF_ST + rg.uniform(1.6, 3.0))
            }
            val c = cam.cam(V3(x, 1.0, z))
            if (c[2] < 2) continue
            items.add(Item(c[2], Feature("person", listOf(x, z), dictOf("_col" to rg.choice(PEOPLE)))))
        }
        items.pySortBy { -it.d }
        val out = ArrayList<String>()
        for (it in items) {
            val d = it.d; val f = it.f
            when (f.kind) {
                "building" -> out.addAll(drawBuilding(scene, f, d, glow, anchors))
                "tree" -> out.addAll(drawTree(scene, f, d, rg))
                "lamp" -> out.addAll(drawLamp(scene, f, d, glow))
                "hydrant" -> out.addAll(drawPost(scene, f, 0.85, 0.28, if (!night) "#C0392B" else "#4A1F1C", "#E8C04A"))
                "bench" -> out.addAll(drawBench(scene, f))
                "car" -> out.addAll(drawCar(scene, f))
                "person" -> out.addAll(drawPerson(scene, f))
                "subway" -> out.addAll(drawSubway(scene, f, glow, anchors))
                "bus_stop" -> out.addAll(drawShelter(scene, f, glow))
                "bike_rack" -> out.addAll(drawRack(scene, f))
                "fountain" -> out.addAll(drawPost(scene, f, 0.95, 0.35, if (!night) "#5A6B7A" else "#1A2230", if (!night) "#7FC3FF" else "#2A4A6A"))
                "person_park" -> out.addAll(drawPerson(scene, Feature("person_park", f.geom, dictOf("_col" to rg.choice(PARK_PEOPLE)))))
                "playground" -> out.addAll(drawPlayground(scene, f))
            }
        }
        // night light pools on the ground
        val pools = ArrayList<String>()
        if (night) {
            defs.add("<radialGradient id=\"pool\"><stop offset=\"0\" stop-color=\"#FFB45E\" stop-opacity=\".55\"/><stop offset=\".5\" stop-color=\"#FFA848\" stop-opacity=\".18\"/><stop offset=\"1\" stop-color=\"#FFA848\" stop-opacity=\"0\"/></radialGradient>")
            defs.add("<radialGradient id=\"lampglow\"><stop offset=\"0\" stop-color=\"#FFF1D2\" stop-opacity=\".95\"/><stop offset=\".12\" stop-color=\"#FFD9A0\" stop-opacity=\".55\"/><stop offset=\".45\" stop-color=\"#FFC27A\" stop-opacity=\".12\"/><stop offset=\"1\" stop-color=\"#FFC27A\" stop-opacity=\"0\"/></radialGradient>")
            defs.add("<radialGradient id=\"shopglow\"><stop offset=\"0\" stop-color=\"#FFD39A\" stop-opacity=\".45\"/><stop offset=\"1\" stop-color=\"#FFD39A\" stop-opacity=\"0\"/></radialGradient>")
            for (f in F) {
                if (f.kind != "lamp") continue
                val x = f.n(0); val z = f.n(1); val dirn = f.n(2)
                val lx = x + 1.6 * (if (Math.abs(dirn) == 1.0) dirn else 0.0)
                val lz = z + 1.6 * (if (Math.abs(dirn) == 2.0) dirn / 2 else 0.0)
                val c = cam.cam(V3(lx, 0.0, lz))
                if (c[2] < 2) continue
                val p = cam.pc(c)
                if (!(-80 < p[0] && p[0] < W + 80 && -80 < p[1] && p[1] < H + 80)) continue
                val rx = 11 * cam.focal / c[2]
                val q = cam.proj(V3(lx, 0.0, lz + 11)) ?: p
                val ry = PyMath.max(0.6, Math.abs(q[1] - p[1]))
                pools.add(PyFmt.pf("<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"url(#pool)\"/>", p[0], p[1], rx, ry))
            }
        }
        // haze
        val stops = StringBuilder()
        val top = PyMath.max(0.0, hy)
        for (k in 0 until 17) {
            val sy = top + (H - top) * k / 16
            val gd = cam.groundDepth(sy + 0.01)
            val a = if (gd == null) th.num("haze_max") else th.num("haze_max") * PyMath.pow(clamp((gd - 140) / 620), 1.2)
            stops.append(PyFmt.pf("<stop offset=\"%.3f\" stop-color=\"%s\" stop-opacity=\"%.3f\"/>", k / 16.0, th["haze"], a))
        }
        defs.add(PyFmt.pf("<linearGradient id=\"haze\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"%.1f\" x2=\"0\" y2=\"%d\">%s</linearGradient>", top, H, stops.toString()))
        var haze = PyFmt.pf("<rect y=\"%.1f\" width=\"%d\" height=\"%.1f\" fill=\"url(#haze)\"/>", top, W, H - top)
        if (th["name"] == "golden") {
            defs.add("<linearGradient id=\"wash\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\".35\"><stop offset=\"0\" stop-color=\"#FFB25E\" stop-opacity=\".30\"/><stop offset=\".45\" stop-color=\"#FFC98A\" stop-opacity=\".08\"/><stop offset=\"1\" stop-color=\"#FFC98A\" stop-opacity=\"0\"/></linearGradient>")
            haze += PyFmt.pf("<rect width=\"%d\" height=\"%d\" fill=\"url(#wash)\" style=\"mix-blend-mode: soft-light\"/><rect width=\"%d\" height=\"%d\" fill=\"url(#wash)\" opacity=\".5\"/>", W, H, W, H)
        }
        val svg = listOf(
            PyFmt.pf("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\">", W, H, W, H),
            PyFmt.pf("<defs><clipPath id=\"vb\"><rect width=\"%d\" height=\"%d\"/></clipPath>%s</defs><g clip-path=\"url(#vb)\">", W, H, defs.joinToString("")),
            ground.joinToString(""), markings.joinToString(""), labels.joinToString(""), shadows.joinToString(""), pools.joinToString(""),
            "<g stroke-linejoin=\"round\">", out.joinToString(""), "</g>", glow.joinToString(""), haze, "</g></svg>",
        )
        // anchors for UI callouts
        for (f in F) {
            val t = f.tags
            if (f.kind == "tree" && "tree_pick" !in anchors) {
                val x = f.n(0); val z = f.n(1)
                if (20 < x && x < 60 && -9 < z && z < -6) {
                    val q = cam.proj(V3(x, t.str("height").toDouble() * 0.8, z))
                    if (q != null && 40 < q[0] && q[0] < W - 40 && 150 < q[1] && q[1] < H - 250) anchors["tree_pick"] = listOf(PyMath.round(q[0], 1), PyMath.round(q[1], 1), t)
                }
            }
            if (f.kind == "lamp" && "lamp_pick" !in anchors) {
                val x = f.n(0); val z = f.n(1)
                val q = cam.proj(V3(x, 8.5, z))
                if (q != null && 60 < q[0] && q[0] < W - 60 && 300 < q[1] && q[1] < H - 280 && 20 < x && x < 100) anchors["lamp_pick"] = listOf(PyMath.round(q[0], 1), PyMath.round(q[1], 1), t)
            }
            if (f.kind == "hydrant" && "hydrant_pick" !in anchors) {
                val x = f.n(0); val z = f.n(1)
                val q = cam.proj(V3(x, 0.9, z))
                if (q != null && 40 < q[0] && q[0] < W - 40 && 200 < q[1] && q[1] < H - 200) anchors["hydrant_pick"] = listOf(PyMath.round(q[0], 1), PyMath.round(q[1], 1), t)
            }
            if (f.kind == "road" && t["name"] == "North 7th Street") {
                val q = cam.proj(V3(-30.0, 0.0, 0.0))
                if (q != null) anchors["sett_pick"] = listOf(PyMath.round(q[0], 1), PyMath.round(q[1], 1), t)
            }
            if (f.kind == "crossing" && f.n(1) == 0.0 && f.n(2) == 0.0) {
                val q = cam.proj(V3(0.0, 0.0, -(ROAD_HALF_ST + 2.4)))
                if (q != null) anchors["crossing_pick"] = listOf(PyMath.round(q[0], 1), PyMath.round(q[1], 1), t)
            }
        }
        fun pick(name: String, kinds: Set<String>, want: V2, y: Double = 1.0, test: (Feature) -> Boolean = { true }) {
            var bestD = 0.0; var bestQ: V2? = null; var bestF: Feature? = null
            for (f in F) {
                if (f.kind !in kinds || !test(f)) continue
                val x: Double; val z: Double; val yy: Double
                if (f.kind == "building") {
                    x = (f.n(0) + f.n(1)) / 2; z = (f.n(2) + f.n(3)) / 2; yy = heightOf(f.tags) + 0.5
                } else if (f.kind == "park" || f.kind == "playground") {
                    val g = f.poly()
                    x = (g[0][0] + g[2][0]) / 2; z = (g[0][1] + g[2][1]) / 2; yy = y
                } else {
                    x = f.n(0); z = f.n(1)
                    val h = f.tags["height"]?.let { PyRe.strip(it as String).toDouble() } ?: y
                    yy = h * (if (f.kind == "tree") 0.75 else 1.0)
                }
                val q = cam.proj(V3(x, yy, z))
                if (q == null || !(30 < q[0] && q[0] < W - 30 && 120 < q[1] && q[1] < 612)) continue
                val dd = PyMath.hypot(q[0] - want[0], q[1] - want[1])
                if (bestQ == null || dd < bestD) {
                    bestD = dd; bestQ = q; bestF = f
                }
            }
            if (bestQ != null) anchors[name] = listOf(PyMath.round(bestQ[0], 1), PyMath.round(bestQ[1], 1), bestF!!.tags)
        }
        pick("tree_pick", setOf("tree"), V2(140.0, 596.0))
        pick("lamp_pick", setOf("lamp"), V2(170.0, 600.0), y = 8.4)
        pick("tank_pick", setOf("building"), V2(60.0, 300.0), test = { f -> f.tags["man_made:roof"] == "water_tank" })
        pick("solar_pick", setOf("building"), V2(360.0, 640.0), test = { f -> f.tags["roof_solar"] == "yes" })
        pick("play_pick", setOf("playground"), V2(60.0, 660.0), y = 3.0)
        val q = cam.proj(V3(-13.0, 0.05, 1.5))
        if (q != null) anchors["sett_pick"] = listOf(PyMath.round(q[0], 1), PyMath.round(q[1], 1), F.first { f -> f.kind == "road" && f.tags["name"] == "North 7th Street" }.tags)
        anchors["sun"] = dictOf("azimuth" to PyMath.round(az, 1), "elevation" to PyMath.round(el, 1))
        return Rendered(svg.joinToString(""), anchors, cam)
    }

    private val DRAWN = setOf("tree", "lamp", "hydrant", "bench", "subway", "bus_stop", "bike_rack", "fountain", "person_park")
    private val PEOPLE = listOf("#1E3A5F", "#B8322A", "#2A2D33", "#C9A36A", "#6B7282", "#E8E8E4", "#4F7A5A", "#7B4DFF")
    private val PARK_PEOPLE = listOf("#B8322A", "#1E3A5F", "#E8E8E4", "#7B4DFF", "#C9A36A")

    // ------------------------------------------------------------------ drawing helpers

    // render/living.py:631 face_path
    /** A filled face, or (null, null) when it is off screen or smaller than 0.15 px². */
    fun facePath(cam: Camera, q: List<V3>, fill: String, extra: String = ""): Pair<String?, List<V2>?> {
        val sp = cam.poly(q, margin = 200.0)
        if (sp == null || area(sp) < 0.15) return null to null
        return PyFmt.pf("<path d=\"%s\" fill=\"%s\"%s/>", P(sp), fill, extra) to sp
    }

    private fun stroke3(col: String): String = PyFmt.pf(" stroke=\"%s\" stroke-width=\".3\"", col)

    private fun pyMinX(sp: List<V2>): Double = City.pyMinOf(sp.map { it.x })
    private fun pyMaxX(sp: List<V2>): Double = City.pyMaxOf(sp.map { it.x })
    private fun pyMinY(sp: List<V2>): Double = City.pyMinOf(sp.map { it.y })
    private fun pyMaxY(sp: List<V2>): Double = City.pyMaxOf(sp.map { it.y })

    // render/living.py:636 draw_building
    fun drawBuilding(scene: Scene, f: Feature, d: Double, glow: MutableList<String>, anchors: MutableMap<String, Any?>): List<String> {
        val cam = scene.cam; val th = scene.th; val L = scene.L
        val t = f.tags; val x0 = f.n(0); val x1 = f.n(1); val z0 = f.n(2); val z1 = f.n(3)
        val night = th.flag("night")
        val lv = PyRe.int(PyFmt.str(t["building:levels"] ?: 3)); val fh = 3.2
        val h = heightOf(t)
        val mat = t["building:material"] ?: "brick"
        val base = hx(t["building:colour"] as String? ?: "#9A5A43")
        val out = ArrayList<String>()
        val foot = rect(x0, x1, z0, z1)
        val cx = (x0 + x1) / 2; val cz = (z0 + z1) / 2
        val gable = t["roof:shape"] == "gabled"
        val wallTop = if (!gable) h else lv * 3.0
        val rgb = PyRandom(PyHash.hashTuple(listOf(x0, z0)) and 0xffffL)
        @Suppress("UNCHECKED_CAST")
        val hhmm = th["hhmm"] as List<Int>
        for (i in 0 until 4) {
            val a = foot[i]; val b = foot[(i + 1) % 4]
            val ex = b[0] - a[0]; val ez = b[1] - a[1]
            var nrm = vnorm(V3(ez, 0.0, -ex))
            val mid = V2((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
            if ((mid[0] - cx) * nrm[0] + (mid[1] - cz) * nrm[2] < 0) nrm = V3(-nrm[0], 0.0, -nrm[2])
            if (vdot(nrm, vsub(cam.C, V3(mid[0], h / 2, mid[1]))) <= 0) continue
            val side = if (nrm[2] > 0.5) "S" else if (nrm[2] < -0.5) "N" else if (nrm[0] > 0.5) "E" else "W"
            val elen = PyMath.hypot(ex, ez)
            // facade in sun/shade cells: rows per level, columns ~4 m
            val ncol = Math.max(1, PyMath.int(elen / 4).toInt())
            for (r in 0 until Math.max(1, PyMath.int(wallTop / fh + 0.5).toInt())) {
                val ya = r * fh; val yb = PyMath.min(wallTop, (r + 1) * fh)
                if (ya >= wallTop) break
                for (cidx in 0 until ncol) {
                    val u0 = cidx.toDouble() / ncol; val u1 = (cidx + 1).toDouble() / ncol
                    val pc = V3(a[0] + (b[0] - a[0]) * (u0 + u1) / 2 + nrm[0] * 0.05, (ya + yb) / 2, a[1] + (b[1] - a[1]) * (u0 + u1) / 2 + nrm[2] * 0.05)
                    val lit = (!night) && vdot(nrm, L) > 0 && scene.lit(pc, f)
                    var col: V3
                    if (mat == "glass") {
                        val refl = if (!night) mix(hx(th.str("glass_sky")), hx(th.str("sky_hor")), clamp(1 - (ya / PyMath.max(1.0, wallTop)))) else hx(th.str("glass_sky"))
                        col = light(th, mulc(refl, 0.8), nrm, lit)
                        if (!night) col = mix(col, if (lit) hx(th.str("glass_sun")) else refl, (if (lit) 0.6 else 0.45) * rgb.uniform(0.55, 1.1))
                        col = mulc(col, rgb.uniform(0.9, 1.05))
                    } else {
                        col = light(th, mulc(base, 1 + rgb.uniform(-0.02, 0.02)), nrm, lit)
                    }
                    val hc = tohex(col)
                    val (s, _) = facePath(cam, quadAt(a, b, ya, yb, u0, u1), hc, stroke3(hc))
                    if (s != null) out.add(s)
                }
            }
            // windows + storefront + details
            val near = d < 420
            val isFront = t["front"] == side
            val shop = isFront && ("shop" in t || "amenity" in t)
            val openNow = shop && openAt(t["opening_hours"] as String? ?: "00:00-00:01", hhmm)
            val wcols = if (mat != "glass") Math.max(1, PyMath.int((elen - 1) / 2.6).toInt()) else Math.max(1, PyMath.int(elen / 1.6).toInt())
            for (r in 0 until lv) {
                if (r == 0 && shop) {
                    // storefront glass
                    val gl = if (openNow && night) "#FFD9A0" else (if (openNow) "#F7D8A8" else th.str("win"))
                    val (s, sp) = facePath(cam, quadAt(a, b, 0.35, 2.9, 0.08, 0.92, 0.06, nrm), gl, PyFmt.pf(" stroke=\"%s\" stroke-width=\".6\"", th["frame"]))
                    if (s != null) out.add(s)
                    if (openNow && night && sp != null) {
                        out.add(PyFmt.pf("<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"url(#shopglow)\"/>",
                            (pyMinX(sp) + pyMaxX(sp)) / 2, pyMaxY(sp), (pyMaxX(sp) - pyMinX(sp)) * 0.8, (pyMaxY(sp) - pyMinY(sp)) * 0.9))
                    }
                    // awning: sloped quad sticking out 1.4 m
                    val aw = t["awning"] as String?
                    if (!aw.isNullOrEmpty()) {
                        val q = quadAt(a, b, 3.35, 3.35, 0.06, 0.94, 0.05, nrm)
                        val q2 = listOf(q[0], q[1], V3(q[1][0] + nrm[0] * 1.5, 2.8, q[1][2] + nrm[2] * 1.5), V3(q[0][0] + nrm[0] * 1.5, 2.8, q[0][2] + nrm[2] * 1.5))
                        val acol = hx(aw)
                        val litA = (!night) && scene.lit(V3((q[0][0] + q[1][0]) / 2 + nrm[0], 3.1, (q[0][2] + q[1][2]) / 2 + nrm[2]), f)
                        val c2 = light(th, acol, vnorm(V3(nrm[0], 1.0, nrm[2])), litA)
                        val (s1, _) = facePath(cam, q2, tohex(c2))
                        if (s1 != null) out.add(s1)
                        val (s2, _) = facePath(cam, listOf(q2[3], q2[2], V3(q2[2][0], 2.45, q2[2][2]), V3(q2[3][0], 2.45, q2[3][2])), tohex(mulc(c2, 0.82)))
                        if (s2 != null) out.add(s2)
                    }
                    // sign band with the name, painted onto the wall
                    val nm = t["name"] as String?
                    if (!nm.isNullOrEmpty() && near) {
                        val q = quadAt(a, b, 3.5, 4.4, 0.15, 0.85, 0.05, nrm)
                        val (s3, sp3) = facePath(cam, q, if (!night) "#14161B" else "#0B0D14")
                        if (s3 != null) {
                            out.add(s3)
                            var u = vnorm(V3(b[0] - a[0], 0.0, b[1] - a[1]))
                            if (vdot(vcross(u, V3(0.0, -1.0, 0.0)), nrm) < 0) u = V3(-u[0], 0.0, -u[2])
                            val mx = (a[0] + b[0]) / 2 + nrm[0] * 0.08; val mz = (a[1] + b[1]) / 2 + nrm[2] * 0.08
                            out.add(groundText(cam, nm.uppercase(java.util.Locale.ROOT), V3(mx, 3.7, mz), u, V3(0.0, -1.0, 0.0), 0.62, if (night && openNow) "#FFE7A3" else "#F4EFE4", 1.0, 800, 0.08))
                            val sp = sp3!!
                            val at = { listOf(PyMath.round((pyMinX(sp) + pyMaxX(sp)) / 2, 1), PyMath.round(pyMinY(sp), 1), t) }
                            if ("name" in t && t["shop"] == "convenience") anchors["shop_pick"] = at()
                            if (t["amenity"] == "cafe") anchors["cafe_pick"] = at()
                            if (t["amenity"] == "bar") anchors["bar_pick"] = at()
                        }
                    }
                    continue
                }
                if (!near && r > 0 && d > 700) continue
                val ya = r * fh + 0.9; val yb = ya + (if (mat != "glass") 1.7 else 2.4)
                if (gable && r >= lv) continue
                for (ci in 0 until wcols) {
                    val u0 = (0.5 + ci * (elen - 1) / wcols + 0.35) / elen
                    val u1 = u0 + ((elen - 1) / wcols - 0.7) / elen
                    if (u1 <= u0) continue
                    if (night) {
                        val litRoom = rgb.random() < (if (t["building"] == "apartments" || t["building"] == "house") 0.5 else 0.25)
                        if (litRoom) {
                            val col = rgb.choice(LIT_ROOMS)
                            val (s, _) = facePath(cam, quadAt(a, b, ya, yb, u0, u1, 0.04, nrm), col, PyFmt.pf(" opacity=\"%.2f\"", rgb.choice(ROOM_OPS)))
                            if (s != null) out.add(s)
                        }
                        continue
                    }
                    if (mat == "glass") {
                        val (s, _) = facePath(cam, quadAt(a, b, r * fh, r * fh + 3.2, u0 - 0.006, u0, 0.04, nrm), "#E9EEF4", " opacity=\".6\"")
                        if (s != null) out.add(s)
                        if (ci == 0) {
                            val (s2, _) = facePath(cam, quadAt(a, b, r * fh + 3.0, r * fh + 3.2, 0.0, 1.0, 0.04, nrm), "#DDE4EC", " opacity=\".7\"")
                            if (s2 != null) out.add(s2)
                        }
                        continue
                    }
                    val pc = V3(a[0] + (b[0] - a[0]) * (u0 + u1) / 2 + nrm[0] * 0.1, (ya + yb) / 2, a[1] + (b[1] - a[1]) * (u0 + u1) / 2 + nrm[2] * 0.1)
                    val wl = (!night) && vdot(nrm, L) > 0 && scene.lit(pc, f)
                    val (s, _) = facePath(cam, quadAt(a, b, ya - 0.12, yb + 0.12, u0 - 0.012, u1 + 0.012, 0.03, nrm), th.str("frame"), " opacity=\".9\"")
                    if (s != null) out.add(s)
                    val (s2, _) = facePath(cam, quadAt(a, b, ya, yb, u0, u1, 0.04, nrm), if (wl) th.str("win_sun") else th.str("win"))
                    if (s2 != null) out.add(s2)
                }
            }
            // cornice for masonry, parapet line
            if (mat == "brick" && !gable) {
                val (s, _) = facePath(cam, quadAt(a, b, h - 0.7, h, -0.02, 1.02, 0.35, nrm), tohex(mulc(light(th, base, V3(0.0, 0.4, 0.0), !night), 1.06)))
                if (s != null) out.add(s)
            }
            // fire escape: platforms + ladders, drawn in near-black ironwork
            if (t["fire_escape"] == side && near && elen > 6) {
                val iron = if (!night) "#26272B" else "#0B0C10"
                val segs = StringBuilder()
                for (r in 1 until lv) {
                    val y = r * fh + 0.2
                    val ua = 0.15; val ub = 0.62
                    val pa = quadAt(a, b, y, y, ua, ub, 1.1, nrm)
                    val p1 = cam.proj(pa[0]); val p2 = cam.proj(pa[1])
                    val pa2 = quadAt(a, b, y + 1.0, y + 1.0, ua, ub, 1.1, nrm)
                    val p3 = cam.proj(pa2[0]); val p4 = cam.proj(pa2[1])
                    if (p1 != null && p2 != null && p3 != null && p4 != null) {
                        segs.append(PyFmt.pf("M%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1f",
                            p1[0], p1[1], p2[0], p2[1], p3[0], p3[1], p4[0], p4[1], p1[0], p1[1], p3[0], p3[1], p2[0], p2[1], p4[0], p4[1]))
                    }
                    val la = quadAt(a, b, y, y + fh, if (r % 2 != 0) ua + 0.05 else ub - 0.12, if (r % 2 != 0) ub - 0.12 else ua + 0.05, 0.6, nrm)
                    val q1 = cam.proj(la[0]); val q2 = cam.proj(la[2])
                    if (q1 != null && q2 != null) segs.append(seg(q1, q2))
                }
                if (segs.isNotEmpty()) out.add(PyFmt.pf("<path d=\"%s\" stroke=\"%s\" stroke-width=\"%.2f\" fill=\"none\" opacity=\".85\"/>", segs.toString(), iron, PyMath.max(0.5, 60 / d)))
            }
        }
        // roof
        if (gable) {
            val ry = lv * 3.0; val apex = ry + 3.4
            val rcol = hx(t["roof:colour"] as String? ?: "#5A5F66")
            val planes: List<Pair<List<V3>, V3>>
            val gables: List<List<V3>>
            if (t["roof:orientation"] == "across") {
                planes = listOf(
                    listOf(V3(x0, ry, z0), V3(x1, ry, z0), V3(x1, apex, cz), V3(x0, apex, cz)) to V3(0.0, 0.7, -0.7),
                    listOf(V3(x0, ry, z1), V3(x1, ry, z1), V3(x1, apex, cz), V3(x0, apex, cz)) to V3(0.0, 0.7, 0.7),
                )
                gables = listOf(listOf(V3(x0, ry, z0), V3(x0, ry, z1), V3(x0, apex, cz)), listOf(V3(x1, ry, z0), V3(x1, ry, z1), V3(x1, apex, cz)))
            } else {
                planes = listOf(
                    listOf(V3(x0, ry, z0), V3(x0, ry, z1), V3(cx, apex, z1), V3(cx, apex, z0)) to V3(-0.7, 0.7, 0.0),
                    listOf(V3(x1, ry, z0), V3(x1, ry, z1), V3(cx, apex, z1), V3(cx, apex, z0)) to V3(0.7, 0.7, 0.0),
                )
                gables = listOf(listOf(V3(x0, ry, z0), V3(x1, ry, z0), V3(cx, apex, z0)), listOf(V3(x0, ry, z1), V3(x1, ry, z1), V3(cx, apex, z1)))
            }
            for (tri in gables) {
                var n = vnorm(vcross(vsub(tri[1], tri[0]), vsub(tri[2], tri[0])))
                val cen = centre(tri, 3)
                if (vdot(n, vsub(cam.C, cen)) < 0) n = V3(-n[0], -n[1], -n[2])
                if (vdot(n, vsub(cam.C, cen)) <= 0) continue
                val (s, _) = facePath(cam, tri, tohex(light(th, base, n, (!night) && vdot(n, L) > 0)))
                if (s != null) out.add(s)
            }
            for ((q, n0) in planes) {
                val n = vnorm(n0)
                val cen = centre(q, 4)
                if (vdot(n, vsub(cam.C, cen)) <= 0) continue
                val (s, _) = facePath(cam, q, tohex(light(th, rcol, n, (!night) && vdot(n, L) > 0)))
                if (s != null) out.add(s)
            }
        } else if (cam.C[1] > h) {
            val rcol = hx(t["roof:colour"] as String? ?: th.str("roof"))
            val litR = (!night) && scene.lit(V3(cx, h + 0.05, cz), f)
            var (s, _) = facePath(cam, foot.map { V3(it.x, h, it.y) }, tohex(light(th, mix(rcol, hx(th.str("roof_sun")), if (litR) 0.25 else 0.0), V3(0.0, 1.0, 0.0), litR)))
            if (s != null) out.add(s)
            val inset = rect(x0 + 0.5, x1 - 0.5, z0 + 0.5, z1 - 0.5)
            s = facePath(cam, inset.map { V3(it.x, h - 0.02, it.y) }, tohex(mulc(light(th, rcol, V3(0.0, 1.0, 0.0), litR), 0.9))).first
            if (s != null) out.add(s)
            val rg = PyRandom(PyHash.hashTuple(listOf("roof", x0, z0)) and 0xffffL)
            if (t["roof_garden"] == "yes") {
                s = facePath(cam, rect(x0 + 3, x1 - 3, z0 + 3, z0 + 12).map { V3(it.x, h + 0.05, it.y) }, if (!night) "#B98A5E" else "#2A2230").first
                if (s != null) out.add(s)
                for (k in 0 until PyMath.int((x1 - x0 - 6) / 3).toInt()) {
                    val px = x0 + 3 + k * 3
                    out.addAll(box(scene, px, px + 2.4, h, h + 0.7, z0 + 12.4, z0 + 13.4, hx("#7A5A40")))
                    val pc = cam.proj(V3(px + 1.2, h + 1.1, z0 + 12.9))
                    if (pc != null) out.add(PyFmt.pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\"/>", pc[0], pc[1], 0.8 * cam.focal / d, if (!night) "#5E8A48" else "#0E1A16"))
                }
            }
            if (t["roof_solar"] == "yes") {
                for (k in 0 until 4) {
                    for (m in 0 until 2) {
                        val px0 = x0 + 2 + k * (x1 - x0 - 4) / 4; val pz0 = z0 + 2 + m * (z1 - z0 - 4) / 2
                        val q = listOf(
                            V3(px0 + 0.3, h + 0.4, pz0 + 0.3), V3(px0 + (x1 - x0 - 4) / 4 - 0.3, h + 0.4, pz0 + 0.3),
                            V3(px0 + (x1 - x0 - 4) / 4 - 0.3, h + 1.1, pz0 + (z1 - z0 - 4) / 2 - 0.3), V3(px0 + 0.3, h + 1.1, pz0 + (z1 - z0 - 4) / 2 - 0.3),
                        )
                        s = facePath(cam, q, if (!night) "#1F3358" else "#0B1224", " stroke=\"#6A86B8\" stroke-width=\".35\"").first
                        if (s != null) out.add(s)
                    }
                }
            }
            if (t["building"] == "apartments" && mat != "glass" && d < 600 && (x1 - x0) > 5 && (z1 - z0) > 5) {
                val rb = PyRandom(PyHash.hashTuple(listOf("bk", x0, z0)) and 0xffffL)
                val bx = x0 + (x1 - x0 - 2.4) * rb.uniform(0.15, 0.85); val bz = z0 + (z1 - z0 - 3.0) * rb.uniform(0.15, 0.85)
                out.addAll(box(scene, bx, bx + 2.4, h, h + 2.6, bz, bz + 3.0, mix(mulc(base, 0.8), hx("#8C8A86"), 0.55)))
            }
            if (t["man_made:roof"] == "water_tank" && d < 520) {
                val wx = x0 + (x1 - x0) * rg.uniform(0.3, 0.7); val wz = z0 + (z1 - z0) * rg.uniform(0.3, 0.7)
                out.addAll(drawTank(scene, wx, wz, h, d, f))
            }
        }
        return out
    }

    private val LIT_ROOMS = listOf("#FFD08A", "#FFC56F", "#FFE2AE", "#FFB85C", "#E8EEFF")
    private val ROOM_OPS = listOf(0.55, 0.7, 0.85, 0.95)

    /** `tuple(sum(p[i] for p in q) / n for i in range(3))`. */
    private fun centre(q: List<V3>, n: Int): V3 =
        V3(PyMath.sumOf(q) { it.x } / n, PyMath.sumOf(q) { it.y } / n, PyMath.sumOf(q) { it.z } / n)

    // render/living.py:825 draw_tank
    /** A wooden water tank on legs on a roof at height h. */
    fun drawTank(scene: Scene, x: Double, z: Double, h: Double, d: Double, @Suppress("UNUSED_PARAMETER") f: Feature?): List<String> {
        val cam = scene.cam; val th = scene.th
        val out = ArrayList<String>()
        val night = th.flag("night")
        val wood = if (!night) hx("#8A6A4E") else hx("#2A2420")
        val legs = StringBuilder()
        for ((lx, lz) in listOf(-1.3 to -1.3, 1.3 to -1.3, 1.3 to 1.3, -1.3 to 1.3)) {
            val p0 = cam.proj(V3(x + lx, h, z + lz)); val p1 = cam.proj(V3(x + lx, h + 3, z + lz))
            if (p0 != null && p1 != null) legs.append(seg(p0, p1))
        }
        out.add(PyFmt.pf("<path d=\"%s\" stroke=\"#3A3430\" stroke-width=\"%.2f\" fill=\"none\"/>", legs.toString(), PyMath.max(0.5, 40 / d)))
        val y0 = h + 3; val y1 = h + 7.2; val r = 2.1
        val segN = 14
        val ring = (0 until segN).map { k -> V2(x + r * PyMath.cos(2 * PyMath.PI * k / segN), z + r * PyMath.sin(2 * PyMath.PI * k / segN)) }
        for (k in 0 until segN) {
            val a = ring[k]; val b = ring[(k + 1) % segN]
            val n = vnorm(V3((a[0] + b[0]) / 2 - x, 0.0, (a[1] + b[1]) / 2 - z))
            if (vdot(n, vsub(cam.C, V3((a[0] + b[0]) / 2, (y0 + y1) / 2, (a[1] + b[1]) / 2))) <= 0) continue
            val lit = (!night) && vdot(n, scene.L) > 0
            val col = tohex(light(th, wood, n, lit))
            val (s, _) = facePath(cam, listOf(V3(a[0], y0, a[1]), V3(b[0], y0, b[1]), V3(b[0], y1, b[1]), V3(a[0], y1, a[1])), col, stroke3(col))
            if (s != null) out.add(s)
        }
        // hoops
        val hoops = StringBuilder()
        for (yy in doubleArrayOf(y0 + 1.0, y0 + 2.4, y0 + 3.6)) {
            val pts = (0 until 25).map { k -> cam.proj(V3(x + (r + 0.05) * PyMath.cos(2 * PyMath.PI * k / 24), yy, z + (r + 0.05) * PyMath.sin(2 * PyMath.PI * k / 24))) }
            val vis = ArrayList<V2>()
            for (k in 0 until 25) {
                val p = pts[k] ?: continue
                if (vdot(vnorm(V3(PyMath.cos(2 * PyMath.PI * k / 24), 0.0, PyMath.sin(2 * PyMath.PI * k / 24))), vsub(cam.C, V3(x, yy, z))) > 0) vis.add(p)
            }
            if (vis.size > 1) hoops.append("M").append(vis.joinToString("L") { PyFmt.pf("%.1f %.1f", it.x, it.y) })
        }
        if (hoops.isNotEmpty()) out.add(PyFmt.pf("<path d=\"%s\" stroke=\"#2E2A26\" stroke-width=\".5\" fill=\"none\" opacity=\".7\"/>", hoops.toString()))
        val apex = V3(x, y1 + 1.6, z)
        for (k in 0 until segN) {
            val a = ring[k]; val b = ring[(k + 1) % segN]
            val tri = listOf(V3(a[0], y1, a[1]), V3(b[0], y1, b[1]), apex)
            var n = vnorm(vcross(vsub(tri[1], tri[0]), vsub(tri[2], tri[0])))
            if (n[1] < 0) n = V3(-n[0], -n[1], -n[2])
            val cen = centre(tri, 3)
            if (vdot(n, vsub(cam.C, cen)) <= 0) continue
            val (s, _) = facePath(cam, tri, tohex(light(th, if (!night) hx("#5A4A3E") else hx("#201C1A"), n, (!night) && vdot(n, scene.L) > 0)))
            if (s != null) out.add(s)
        }
        return out
    }

    // render/living.py:865 draw_tree
    /** A tree: pit, trunk and a crown of blobs lit from the sun's side. */
    fun drawTree(scene: Scene, f: Feature, d: Double, @Suppress("UNUSED_PARAMETER") rg: PyRandom?): List<String> {
        val cam = scene.cam; val th = scene.th
        val x = f.n(0); val z = f.n(1); val t = f.tags
        val h = PyRe.strip(t.str("height")).toDouble(); val r = PyRe.strip(t.str("diameter_crown")).toDouble() / 2
        val night = th.flag("night")
        val out = ArrayList<String>()
        val p0 = cam.proj(V3(x, 0.0, z)); val p1 = cam.proj(V3(x, h * 0.5, z))
        if (p0 == null || p1 == null) return out
        if (!(-40 < p0[0] && p0[0] < cam.W + 40 && -140 < p0[1] && p0[1] < cam.H + 60)) return out
        val pit = rect(x - 0.9, x + 0.9, z - 0.9, z + 0.9)
        val sp = cam.poly(pit.map { V3(it.x, 0.03, it.y) })
        if (sp != null && !night) out.add(PyFmt.pf("<path d=\"%s\" fill=\"#6E5A44\" opacity=\".7\"/>", P(sp)))
        out.add(PyFmt.pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"%s\" stroke-width=\"%.2f\" stroke-linecap=\"round\"/>",
            p0[0], p0[1], p1[0], p1[1], if (!night) "#4A3A2E" else "#0C100E", PyMath.max(0.6, 0.32 * cam.focal / d)))
        val rrg = PyRandom(PyHash.hashTuple(listOf(PyMath.round(x, 1), PyMath.round(z, 1))) and 0xffffffL)
        val turn = rrg.random()
        val genus = t["genus"]
        var base = hx(th.str("tree"))
        @Suppress("UNCHECKED_CAST")
        val autumn = th["autumn"] as List<String>
        if (!night) {
            if (genus == "Gleditsia" && turn < 0.6) base = mix(base, hx(autumn[1]), 0.6)
            else if (genus == "Ginkgo" && turn < 0.35) base = mix(base, hx("#D9C04A"), 0.5)
            else if (genus == "Pyrus" && turn < 0.35) base = mix(base, hx(autumn[2]), 0.4)
            else if ((genus == "Acer" || genus == "Morus") && turn < 0.4) base = mix(base, hx(autumn[0]), 0.45)
            else if (genus == "Ailanthus") base = mix(base, hx("#9AAE52"), 0.4)
            base = mulc(base, rrg.uniform(0.93, 1.06))
        }
        val L = scene.L
        val n = if (d < 260) 14 else (if (d < 650) 8 else 5)
        val blobs = ArrayList<DoubleArray>()
        for (k in 0 until n) {
            val ang = rrg.uniform(0.0, 2 * PyMath.PI); val rad = r * PyMath.sqrt(rrg.random()) * 0.75
            val bx = x + rad * PyMath.cos(ang); val bz = z + rad * PyMath.sin(ang)
            val by = h * 0.66 + rrg.uniform(-0.35, 0.35) * r * (1 - rad / r * 0.6)
            val br = r * rrg.uniform(0.3, 0.46)
            val c = cam.cam(V3(bx, by, bz))
            if (c[2] < 1) continue
            blobs.add(doubleArrayOf(c[2], bx, by, bz, br))
        }
        blobs.pySortBy { -it[0] }
        val litCrown = (!night) && scene.lit(V3(x + L[0] * r, h * 0.7 + L[1] * r, z + L[2] * r))
        val dark = light(th, hx(th.str("tree_dark")), V3(0.0, 1.0, 0.0), false)
        val midc = if (!night) mulc(light(th, base, vnorm(V3(L[0], 0.7, L[2])), litCrown), 0.86) else dark
        val hic = mix(midc, hx(th.str("tree_sun")), 0.38)
        val under = StringBuilder(); val top = StringBuilder(); val hi = StringBuilder()
        for (bl in blobs) {
            val dd = bl[0]; val bx = bl[1]; val by = bl[2]; val bz = bl[3]; val br = bl[4]
            val p = cam.proj(V3(bx, by, bz))!!
            val rr = br * cam.focal / dd
            under.append(PyFmt.pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\"/>", p[0], p[1] + rr * 0.1, rr))
            if (night) continue
            // which side of this blob faces the sun: shift toward the sun's screen direction
            val sx = cam.proj(V3(bx + L[0] * br, by + L[1] * br, bz + L[2] * br)) ?: continue
            val vx = sx[0] - p[0]; val vy = sx[1] - p[1]
            top.append(PyFmt.pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\"/>", p[0] + vx * 0.28, p[1] + vy * 0.28 - rr * 0.08, rr * 0.8))
            if (litCrown && rr > 1.6 && (by - h * 0.66) > -0.1 * r) {
                hi.append(PyFmt.pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\"/>", p[0] + vx * 0.55, p[1] + vy * 0.55 - rr * 0.15, rr * 0.38))
            }
        }
        out.add(PyFmt.pf("<g fill=\"%s\">%s</g>", tohex(dark), under.toString()))
        if (top.isNotEmpty()) out.add(PyFmt.pf("<g fill=\"%s\">%s</g>", tohex(midc), top.toString()))
        if (hi.isNotEmpty()) out.add(PyFmt.pf("<g fill=\"%s\" opacity=\".85\">%s</g>", tohex(hic), hi.toString()))
        return out
    }

    // render/living.py:923 draw_lamp
    /** A bent-mast street lamp; at night with its glow. */
    fun drawLamp(scene: Scene, f: Feature, d: Double, @Suppress("UNUSED_PARAMETER") glow: MutableList<String>?): List<String> {
        val cam = scene.cam; val th = scene.th
        val x = f.n(0); val z = f.n(1); val dirn = f.n(2)
        val ax = if (Math.abs(dirn) == 1.0) dirn else 0.0
        val azn = if (Math.abs(dirn) == 2.0) dirn / 2 else 0.0
        val p0 = cam.proj(V3(x, 0.0, z)); val p1 = cam.proj(V3(x, 8.2, z))
        val p2 = cam.proj(V3(x + ax * 1.6, 8.5, z + azn * 1.6))
        if (p0 == null || p1 == null || p2 == null) return emptyList()
        val night = th.flag("night")
        val out = arrayListOf(PyFmt.pf("<path d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1f\" stroke=\"%s\" stroke-width=\"%.2f\" fill=\"none\"/>",
            p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], if (!night) "#3A3F46" else "#1A1E28", PyMath.max(0.5, 18 / d)))
        val rr = PyMath.max(0.8, 0.45 * cam.focal / d)
        out.add(PyFmt.pf("<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"%s\"/>", p2[0], p2[1] + rr * 0.3, rr * 1.4, rr * 0.6, if (!night) "#2A2D33" else "#FFE9C2"))
        if (night) out.add(PyFmt.pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"url(#lampglow)\"/>", p2[0], p2[1], rr * 8))
        return out
    }

    // render/living.py:938 draw_post
    /** A post of height h and radius r with a cap: a hydrant, a fountain, a bin, a bus stop sign. */
    fun drawPost(scene: Scene, f: Feature, h: Double, r: Double, col: String, cap: String): List<String> {
        val cam = scene.cam
        val x = f.n(0); val z = f.n(1)
        val p0 = cam.proj(V3(x, 0.0, z)); val p1 = cam.proj(V3(x, h, z))
        if (p0 == null || p1 == null) return emptyList()
        val d = cam.depth(V3(x, h, z)); val w = PyMath.max(1.0, 2 * r * cam.focal / d)
        return listOf(
            PyFmt.pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"%s\" stroke-width=\"%.2f\" stroke-linecap=\"round\"/>", p0[0], p0[1], p1[0], p1[1], col, w),
            PyFmt.pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.2f\" fill=\"%s\"/>", p1[0], p1[1], w * 0.55, cap),
        )
    }

    // render/living.py:947 box
    /** An axis-aligned box, its visible faces lit by the sun. */
    fun box(scene: Scene, x0: Double, x1: Double, y0: Double, y1: Double, z0: Double, z1: Double, col: V3, litOk: Boolean = true): List<String> {
        val cam = scene.cam; val th = scene.th; val L = scene.L
        val out = ArrayList<String>()
        val faces = listOf(
            V3(0.0, 0.0, -1.0) to listOf(V3(x0, y0, z0), V3(x1, y0, z0), V3(x1, y1, z0), V3(x0, y1, z0)),
            V3(0.0, 0.0, 1.0) to listOf(V3(x0, y0, z1), V3(x1, y0, z1), V3(x1, y1, z1), V3(x0, y1, z1)),
            V3(-1.0, 0.0, 0.0) to listOf(V3(x0, y0, z0), V3(x0, y0, z1), V3(x0, y1, z1), V3(x0, y1, z0)),
            V3(1.0, 0.0, 0.0) to listOf(V3(x1, y0, z0), V3(x1, y0, z1), V3(x1, y1, z1), V3(x1, y1, z0)),
            V3(0.0, 1.0, 0.0) to listOf(V3(x0, y1, z0), V3(x1, y1, z0), V3(x1, y1, z1), V3(x0, y1, z1)),
        )
        val night = th.flag("night")
        for ((n, q) in faces) {
            val cen = centre(q, 4)
            if (vdot(n, vsub(cam.C, cen)) <= 0) continue
            val lit = litOk && !night && vdot(n, L) > 0 && scene.lit(V3(cen[0] + n[0] * 0.05, cen[1] + n[1] * 0.05, cen[2] + n[2] * 0.05))
            val (s, _) = facePath(cam, q, tohex(light(th, col, n, lit)))
            if (s != null) out.add(s)
        }
        return out
    }

    // render/living.py:961 draw_car
    /** A parked car: body and cabin boxes along the street ("ns" or "ew"). */
    fun drawCar(scene: Scene, f: Feature): List<String> {
        val x = f.n(0); val z = f.n(1); val o = f.geom[2] as String; val col = hx(f.tags.str("_col"))
        val L = 4.4; val W = 1.8
        val a: DoubleArray; val c: DoubleArray
        if (o == "ns") {
            a = doubleArrayOf(x - W / 2, x + W / 2, z - L / 2, z + L / 2); c = doubleArrayOf(x - 0.8, x + 0.8, z - 1.2, z + 1.0)
        } else {
            a = doubleArrayOf(x - L / 2, x + L / 2, z - W / 2, z + W / 2); c = doubleArrayOf(x - 1.2, x + 1.0, z - 0.8, z + 0.8)
        }
        val out = ArrayList(box(scene, a[0], a[1], 0.25, 1.0, a[2], a[3], col))
        out += box(scene, c[0], c[1], 1.0, 1.5, c[2], c[3], if (!scene.th.flag("night")) mix(hx("#2A3140"), col, 0.25) else hx("#0A0D16"))
        return out
    }

    // render/living.py:970 draw_person
    /** A person: a stroke for the body, a dot for the head, and a shadow by day. */
    fun drawPerson(scene: Scene, f: Feature): List<String> {
        val cam = scene.cam; val th = scene.th
        val x = f.n(0); val z = f.n(1)
        val p0 = cam.proj(V3(x, 0.0, z)); val p1 = cam.proj(V3(x, 1.45, z)); val ph = cam.proj(V3(x, 1.7, z))
        if (p0 == null || p1 == null || ph == null) return emptyList()
        val d = cam.depth(V3(x, 1.0, z)); val w = PyMath.max(0.9, 0.5 * cam.focal / d)
        val night = th.flag("night")
        val col = if (!night) f.tags.str("_col") else "#0E1220"
        val out = ArrayList<String>()
        if (!night) {
            val L = th["_L"] as V3
            val s = cam.proj(V3(x - L[0] / L[1] * 1.7, 0.0, z - L[2] / L[1] * 1.7))
            if (s != null) {
                out.add(PyFmt.pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"%s\" stroke-width=\"%.2f\" opacity=\"%.2f\" stroke-linecap=\"round\"/>",
                    p0[0], p0[1], s[0], s[1], th["shadow"], w, th.num("shadow_op") + 0.1))
            }
        }
        out.add(PyFmt.pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"%s\" stroke-width=\"%.2f\" stroke-linecap=\"round\"/>", p0[0], p0[1], p1[0], p1[1], col, w))
        out.add(PyFmt.pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.2f\" fill=\"%s\"/>", ph[0], ph[1], w * 0.55, if (!night) "#E2B48E" else "#1A1A22"))
        return out
    }

    // render/living.py:986 draw_playground
    /** A climbing frame with a slide, and a swing set. */
    fun drawPlayground(scene: Scene, f: Feature): List<String> {
        val g = f.poly(); val x0 = g[0][0]; val x1 = g[2][0]; val z0 = g[0][1]; val z1 = g[2][1]
        val th = scene.th
        val out = ArrayList<String>()
        // climbing frame with a slide, and a swing set
        out += box(scene, x0 + 4, x0 + 8, 1.4, 1.6, z0 + 4, z0 + 8, hx("#E8C04A"))
        for ((px, pz) in listOf(V2(x0 + 4, z0 + 4), V2(x0 + 8, z0 + 4), V2(x0 + 8, z0 + 8), V2(x0 + 4, z0 + 8))) {
            out += box(scene, px - 0.1, px + 0.1, 0.0, 2.6, pz - 0.1, pz + 0.1, hx("#2E5BFF"))
        }
        out += box(scene, x0 + 3.2, x0 + 8.8, 2.6, 3.0, z0 + 3.2, z0 + 8.8, hx("#B8322A"))
        val cam = scene.cam
        val a = cam.proj(V3(x0 + 8, 1.5, z0 + 6)); val b = cam.proj(V3(x0 + 12, 0.1, z0 + 6))
        if (a != null && b != null) {
            out.add(PyFmt.pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"#2EA86A\" stroke-width=\"%.1f\" stroke-linecap=\"round\"/>",
                a[0], a[1], b[0], b[1], PyMath.max(1.2, 1.1 * cam.focal / cam.depth(V3(x0 + 10, 1.0, z0 + 6)))))
        }
        val segs = StringBuilder()
        for (pz in doubleArrayOf(z1 - 3, z1 - 9)) {
            for (px in doubleArrayOf(x1 - 7, x1 - 2)) {
                val p0 = cam.proj(V3(px, 0.0, pz)); val p1 = cam.proj(V3(px + (if (px < x1 - 4) 0.8 else -0.8), 2.6, pz))
                if (p0 != null && p1 != null) segs.append(seg(p0, p1))
            }
        }
        val t0 = cam.proj(V3(x1 - 6.2, 2.6, z1 - 3)); val t1 = cam.proj(V3(x1 - 2.8, 2.6, z1 - 3))
        val t2 = cam.proj(V3(x1 - 6.2, 2.6, z1 - 9)); val t3 = cam.proj(V3(x1 - 2.8, 2.6, z1 - 9))
        for ((u, v) in listOf(t0 to t2, t1 to t3)) {
            if (u != null && v != null) segs.append(seg(u, v))
        }
        if (segs.isNotEmpty()) out.add(PyFmt.pf("<path d=\"%s\" stroke=\"%s\" stroke-width=\"1\" fill=\"none\"/>", segs.toString(), if (!th.flag("night")) "#3A3F46" else "#161A24"))
        return out
    }

    // render/living.py:1010 draw_bench
    /** A bench along z ("z") or along x. */
    fun drawBench(scene: Scene, f: Feature): List<String> {
        val x = f.n(0); val z = f.n(1); val o = f.geom[2]
        val col = hx("#6E4B3A")
        if (o == "z") return box(scene, x - 0.25, x + 0.25, 0.4, 0.5, z - 0.9, z + 0.9, col) + box(scene, x + 0.2, x + 0.3, 0.5, 0.95, z - 0.9, z + 0.9, col)
        return box(scene, x - 0.9, x + 0.9, 0.4, 0.5, z - 0.25, z + 0.25, col)
    }

    // render/living.py:1016 draw_rack
    /** A bicycle rack: three hoops. */
    fun drawRack(scene: Scene, f: Feature): List<String> {
        val cam = scene.cam
        val x = f.n(0); val z = f.n(1)
        val segs = StringBuilder()
        for (k in 0 until 3) {
            val xx = x - 1.2 + k * 1.2
            val pts = (0 until 9).map { i -> val a = i * PyMath.PI / 8; cam.proj(V3(xx + 0.4 * PyMath.cos(a), 0.9 * PyMath.sin(a), z)) }
            if (pts.all { it != null }) segs.append("M").append(pts.joinToString("L") { PyFmt.pf("%.1f %.1f", it!!.x, it.y) })
        }
        return if (segs.isNotEmpty()) listOf(PyFmt.pf("<path d=\"%s\" stroke=\"#6B7282\" stroke-width=\"1\" fill=\"none\"/>", segs.toString())) else emptyList()
    }

    // render/living.py:1026 draw_subway
    /** A subway entrance: the stair opening, railings and the green globes. */
    fun drawSubway(scene: Scene, f: Feature, @Suppress("UNUSED_PARAMETER") glow: MutableList<String>?, anchors: MutableMap<String, Any?>): List<String> {
        val cam = scene.cam; val th = scene.th
        val x = f.n(0); val z = f.n(1)
        val out = ArrayList<String>()
        val sp = cam.poly(rect(x - 1.1, x + 1.1, z, z + 3.6).map { V3(it.x, 0.04, it.y) })
        if (sp != null) out.add(PyFmt.pf("<path d=\"%s\" fill=\"#1A1C22\"/>", P(sp)))
        val rail = StringBuilder()
        for (xx in doubleArrayOf(x - 1.15, x + 1.15)) {
            val a = cam.proj(V3(xx, 1.0, z)); val b = cam.proj(V3(xx, 1.0, z + 3.6))
            val c = cam.proj(V3(xx, 0.0, z)); val d2 = cam.proj(V3(xx, 0.0, z + 3.6))
            if (a != null && b != null && c != null && d2 != null) {
                rail.append(PyFmt.pf("M%.1f %.1fL%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1f", c[0], c[1], a[0], a[1], b[0], b[1], b[0], b[1], d2[0], d2[1]))
            }
        }
        out.add(PyFmt.pf("<path d=\"%s\" stroke=\"#1F5A3A\" stroke-width=\"1.1\" fill=\"none\"/>", rail.toString()))
        val night = th.flag("night")
        for (xx in doubleArrayOf(x - 1.15, x + 1.15)) {
            val g0 = cam.proj(V3(xx, 1.0, z + 3.6)); val g = cam.proj(V3(xx, 2.1, z + 3.6))
            if (g0 != null && g != null) {
                out.add(PyFmt.pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"#1F5A3A\" stroke-width=\"1\"/>", g0[0], g0[1], g[0], g[1]))
                out.add(PyFmt.pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"1.6\" fill=\"%s\"/>", g[0], g[1], if (night) "#5BE08A" else "#3FA86A"))
                if (night) out.add(PyFmt.pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"7\" fill=\"#5BE08A\" opacity=\".25\"/>", g[0], g[1]))
            }
        }
        val q = cam.proj(V3(x, 2.4, z + 3.6))
        if (q != null) anchors["subway_pick"] = listOf(PyMath.round(q[0], 1), PyMath.round(q[1], 1), f.tags)
        return out
    }

    // render/living.py:1048 draw_shelter
    /** A bus shelter: roof, posts and a lit panel. */
    fun drawShelter(scene: Scene, f: Feature, @Suppress("UNUSED_PARAMETER") glow: MutableList<String>?): List<String> {
        val cam = scene.cam; val th = scene.th
        val x = f.n(0); val z = f.n(1)
        val out = ArrayList<String>()
        out += box(scene, x - 0.8, x + 0.8, 2.4, 2.55, z - 2.2, z + 2.2, hx("#3A3F46"))
        val fr = StringBuilder()
        for ((px, pz) in listOf(V2(x - 0.8, z - 2.2), V2(x + 0.8, z - 2.2), V2(x + 0.8, z + 2.2), V2(x - 0.8, z + 2.2))) {
            val a = cam.proj(V3(px, 0.0, pz)); val b = cam.proj(V3(px, 2.4, pz))
            if (a != null && b != null) fr.append(seg(a, b))
        }
        out.add(PyFmt.pf("<path d=\"%s\" stroke=\"#3A3F46\" stroke-width=\"1\"/>", fr.toString()))
        val night = th.flag("night")
        val (s, _) = facePath(cam, listOf(V3(x + 0.8, 0.3, z - 2.1), V3(x + 0.8, 0.3, z - 0.4), V3(x + 0.8, 2.2, z - 0.4), V3(x + 0.8, 2.2, z - 2.1)),
            if (night) "#FFF4DE" else "#DCE8F2", " opacity=\".85\"")
        if (s != null) out.add(s)
        return out
    }
}
