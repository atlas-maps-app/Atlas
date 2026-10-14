// render/real.py: the living map from real data. The helpers other modules import (coordinates, num, opening hours,
// polygon geometry) come first; then the loader, the joins, the sunlight ray march, the renderer and its draw functions.
package app.atlas.render

import app.atlas.render.City.Camera
import app.atlas.render.City.P
import app.atlas.render.City.area
import app.atlas.render.City.clamp
import app.atlas.render.py.Dict
import app.atlas.render.py.PyFmt
import app.atlas.render.py.PyHash
import app.atlas.render.py.PyJson
import app.atlas.render.py.PyMath
import app.atlas.render.py.PyRandom
import app.atlas.render.py.dictOf
import app.atlas.render.py.flag
import app.atlas.render.py.int
import app.atlas.render.py.num
import app.atlas.render.py.numOrNull
import app.atlas.render.py.pyNum
import app.atlas.render.py.pySortBy
import app.atlas.render.py.str
import app.atlas.render.py.truthy
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.regex.Matcher
import java.util.regex.Pattern

/** The real-data map's helpers, ported line by line from render/real.py. */
object Real {
    const val LAT0 = 40.719521
    const val LON0 = -73.960434 // Wythe Av x North 7th St (OSM node 42480055)
    val KX = 111320 * PyMath.cos(PyMath.radians(LAT0))
    const val KZ = 110540

    // render/real.py:14 XZ
    fun XZ(lat: Double, lon: Double): V2 = V2((lon - LON0) * KX, -(lat - LAT0) * KZ)

    // render/real.py:15 num
    /** The leading number of a tag value ("12 m" -> 12.0), or [default]. */
    fun num(v: Any?, default: Double? = null): Double? {
        if (v == null) return default
        val s = PyFmt.str(v)
        // re.match(r'\s*(-?\d+(?:\.\d+)?)', s)
        var i = 0
        while (i < s.length && PyRe.isSpace(s[i])) i++
        val start = i
        if (i < s.length && s[i] == '-') i++
        val d0 = i
        while (i < s.length && Character.isDigit(s[i])) i++
        if (i == d0) return default
        if (i + 1 < s.length && s[i] == '.' && Character.isDigit(s[i + 1])) {
            i++
            while (i < s.length && Character.isDigit(s[i])) i++
        }
        return java.lang.Double.parseDouble(PyRe.asciiDigits(s.substring(start, i)))
    }

    // ------------------------------------------------------------------ opening_hours (common subset)
    val DAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

    // render/real.py:22 _days
    fun _days(sel: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (part in PyRe.split(COMMA, PyRe.strip(sel))) {
            if ('-' in part) {
                val ab = part.split('-')
                if (ab.size != 2) throw IllegalArgumentException("too many values to unpack (expected 2)")
                val i = DAYS.indexOf(ab[0]); val j = DAYS.indexOf(ab[1])
                if (i < 0 || j < 0) throw IllegalArgumentException("is not in list")
                var k = i
                while (true) {
                    out.add(DAYS[k])
                    if (k == j) break
                    k = (k + 1) % 7
                }
            } else if (part in DAYS) {
                out.add(part)
            }
        }
        return out
    }

    private val S = PyRe.WS

    /** One rule: an optional day selector, then hours, "off" or "closed". */
    val RULE: Pattern = PyRe.compile(
        "((?:Mo|Tu|We|Th|Fr|Sa|Su)(?:-(?:Mo|Tu|We|Th|Fr|Sa|Su))?(?:$S*,$S*(?:Mo|Tu|We|Th|Fr|Sa|Su)(?:-(?:Mo|Tu|We|Th|Fr|Sa|Su))?)*)?" +
            "$S*((?:\\d{1,2}:\\d{2}$S*-$S*\\d{1,2}:\\d{2}(?:$S*,$S*\\d{1,2}:\\d{2}$S*-$S*\\d{1,2}:\\d{2})*)|off|closed)",
    )
    val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val COMMA = PyRe.compile("$S*,$S*")
    private val DATED = PyRe.compile("(?:(\\d{4})$S+)?(${MONTHS.joinToString("|")})$S+(\\d{1,2})\\b(.*)")
    private val PH = PyRe.compile(",?\\bPH\\b")

    // render/real.py:36 open_at
    /**
     * True / False, or null when the string is not understood. Date-specific rules apply only on their date.
     * Hours that run past midnight belong to the day they start on: Th 10:00-01:00 is open at 00:30 on Friday.
     */
    fun openAt(oh0: String?, day: String, hhmm: IntArray, date: IntArray = intArrayOf(2026, 9, 30)): Boolean? {
        if (oh0.isNullOrEmpty()) return null
        val oh = PyRe.strip(oh0)
        if (oh == "24/7") return true
        val t = hhmm[0] * 60 + hhmm[1]
        val (today, anyRule) = _dayState(oh, day, t, date, false)
        if (!anyRule) return null
        val i = DAYS.indexOf(day)
        if (i < 0) throw IllegalArgumentException("'$day' is not in list")
        val prev = LocalDate.of(date[0], date[1], date[2]).minusDays(1)
        val (spill, _) = _dayState(oh, DAYS[Math.floorMod(i - 1, 7)], t + 24 * 60, intArrayOf(prev.year, prev.monthValue, prev.dayOfMonth), true)
        return today == true || spill == true
    }

    // render/real.py:49 _day_state
    /** The state of one day's last matching rule at minute [t] of that day (t past 24:00 with spill: only the wrapped part). */
    fun _dayState(oh0: String, day: String, t: Int, date: IntArray, spill: Boolean): Pair<Boolean?, Boolean> {
        var state: Boolean? = null; var anyRule = false
        val kept = ArrayList<String>()
        for (part in oh0.split(';')) {
            val p = PyRe.strip(part)
            val m = DATED.matcher(p)
            if (m.lookingAt()) {
                val y = m.group(1); val mon = MONTHS.indexOf(m.group(2)) + 1; val dd = PyRe.int(m.group(3)); val rest = m.group(4)
                if ((y == null || PyRe.int(y) == date[0]) && mon == date[1] && dd == date[2]) {
                    kept.add(day + " " + PyRe.strip(rest))
                }
                continue
            }
            kept.add(PH.matcher(p).replaceAll(""))
        }
        val oh = kept.joinToString("; ")
        val m: Matcher = RULE.matcher(oh)
        while (m.find()) {
            anyRule = true
            val sel = m.group(1); val times = m.group(2)
            val days = if (!sel.isNullOrEmpty()) _days(sel) else DAYS.toSet()
            if (day !in days) continue
            if (times == "off" || times == "closed") {
                state = false; continue
            }
            var st = false
            for (rng in PyRe.split(COMMA, times)) {
                val ab = rng.split('-').map { PyRe.strip(it) }
                if (ab.size != 2) throw IllegalArgumentException("too many values to unpack (expected 2)")
                val a = ab[0]; val b = ab[1]
                val ta = PyRe.int(a.substring(0, a.indexOf(':'))) * 60 + PyRe.int(a.substring(a.length - 2))
                var tb = PyRe.int(b.substring(0, b.indexOf(':'))) * 60 + PyRe.int(b.substring(b.length - 2))
                if (tb <= ta) tb += 24 * 60
                if (ta <= t && t < tb && (!spill || tb > 24 * 60)) st = true
            }
            state = st
        }
        return state to anyRule
    }

    // ------------------------------------------------------------------ geometry helpers

    // render/real.py:79 signed_area
    fun signedArea(poly: List<V2>): Double {
        var s = 0.0
        for (i in poly.indices) {
            val (x1, z1) = poly[i]; val (x2, z2) = poly[(i + 1) % poly.size]
            s += x1 * z2 - x2 * z1
        }
        return s / 2
    }

    // render/real.py:85 pip
    fun pip(x: Double, z: Double, poly: List<V2>): Boolean {
        var ins = false; val n = poly.size; var j = n - 1
        for (i in 0 until n) {
            val (xi, zi) = poly[i]; val (xj, zj) = poly[j]
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi + 1e-12) + xi) ins = !ins
            j = i
        }
        return ins
    }

    // render/real.py:92 centroid
    fun centroid(poly: List<V2>): V2 = V2(PyMath.sumOf(poly) { it.x } / poly.size, PyMath.sumOf(poly) { it.y } / poly.size)

    // render/real.py:94 seg_dist
    /** The distance from (px, pz) to the segment a-b, and the position along it (0..1). */
    fun segDist(px: Double, pz: Double, a: V2, b: V2): Pair<Double, Double> {
        val (ax, az) = a; val (bx, bz) = b
        val dx = bx - ax; val dz = bz - az
        val L2 = (dx * dx + dz * dz).let { if (it == 0.0) 1e-9 else it }
        val t = City.clamp(((px - ax) * dx + (pz - az) * dz) / L2)
        val qx = ax + dx * t; val qz = az + dz * t
        return PyMath.hypot(px - qx, pz - qz) to t
    }

    // ------------------------------------------------------------------ load and join (stage 2)

    val BRICKS = listOf("#A4523B", "#8C4634", "#B5634A", "#7E3E2E", "#9A5A43", "#6F4A3A", "#A8604A", "#8E5040")
    val BUFF = listOf("#C9A882", "#B9A58A", "#D6C3A1", "#A89684", "#C8B494")
    val PANEL = listOf("#8C939B", "#C8C2B6", "#5F666E", "#A7AEB6")

    /** Late-September leaf colour per genus, from green (0) to fully turned. */
    val GENUS_COL: LinkedHashMap<String, Pair<String, Double>> = linkedMapOf(
        "Gleditsia" to ("#C9B04A" to 0.55), "Gymnocladus" to ("#D2B452" to 0.5), "Zelkova" to ("#B9853E" to 0.25), "Prunus" to ("#C0603A" to 0.35),
        "Liquidambar" to ("#A2433A" to 0.25), "Tilia" to ("#C9B45A" to 0.3), "Platanus" to ("#B9A85A" to 0.15), "Quercus" to ("#8F7A3A" to 0.1),
        "Pyrus" to ("#9A5A3A" to 0.12), "Ginkgo" to ("#D9C04A" to 0.08), "Acer" to ("#C8702E" to 0.35), "Ulmus" to ("#B9A04A" to 0.2),
        "Styphnolobium" to ("#B9B052" to 0.15), "Cercis" to ("#C0A040" to 0.2), "Malus" to ("#B06A3A" to 0.2), "Populus" to ("#D0B84A" to 0.3),
    )

    /**
     * What [Shadows] needs of a building: its footprint and height, the bounding box it fills in, and the roof shape
     * that [Shadows.top] reads (amsterdam.py's buildings carry `roof_h`, `roof` and `orient`; real.py's do not).
     */
    interface Prism {
        val poly: List<V2>
        val h: Double
        /** `b['bb']`: (min x, max x, min z, max z), set by [Shadows]. */
        var bb: DoubleArray?
        /** `b.get('roof_h')`. */
        val roofH: Double? get() = null
        /** `b.get('roof')`. */
        val roof: String? get() = null
        /** `b.get('orient')`. */
        val orient: String? get() = null
        /** `b['_rf']`: the roof frame [Shadows.top] caches. */
        var rf: DoubleArray?
    }

    /** A shop node joined to a building edge: the edge, the position along it (0..1) and the node's tags. */
    class Shop(val edge: Int, val u: Double, val tags: Map<String, Any?>)

    /** A building from OSM: footprint (counter-clockwise), height, levels, year, material, colour, and what was guessed. */
    class Building(
        override val poly: List<V2>, override val h: Double, val levels: Int, val year: Int?, val mat: String?, val col: String,
        val tags: Map<String, Any?>, val guessed: LinkedHashMap<String, Boolean>, val shops: MutableList<Shop>, val id: Long,
    ) : Prism {
        override var bb: DoubleArray? = null
        override var rf: DoubleArray? = null

        /** The edge facing the street, from real.py's `__main__` ([fronts]); null when no road is near. */
        var front: Int? = null
    }

    /** A road or path: its points, width, tags and highway class ("path" for every footway). */
    class Road(val pts: List<V2>, val w: Double, val tags: Map<String, Any?>, val hw: String)

    /** A footway=crossing way. */
    class Crossing(val pts: List<V2>, val tags: Map<String, Any?>)

    /** An area: park, garden, parking, water, plaza, … */
    class Area(val poly: List<V2>, val kind: String, val tags: Map<String, Any?>)

    /** A tagged node. */
    class Node(val x: Double, val z: Double, val tags: Map<String, Any?>, val id: Long)

    /** A tree from OSM or the street tree census. */
    class Tree(
        val x: Double, val z: Double, val genus: String?, val species: String?, val dbh: Double?, val source: String,
        val tags: Map<String, Any?>, val common: String? = null,
    )

    /** A shop or café node. */
    class Poi(val x: Double, val z: Double, val tags: Map<String, Any?>)

    /** real.py's `S`: everything [load] reads. */
    class RealData {
        val buildings = ArrayList<Building>()
        val roads = ArrayList<Road>()
        val crossings = ArrayList<Crossing>()
        val areas = ArrayList<Area>()
        val water = ArrayList<List<V2>>()
        val nodes = ArrayList<Node>()
        val trees = ArrayList<Tree>()
        val pois = ArrayList<Poi>()
    }

    private fun same(a: V2, b: V2): Boolean = a.x == b.x && a.y == b.y

    @Suppress("UNCHECKED_CAST")
    private fun m(v: Any?): Map<String, Any?> = v as Map<String, Any?>

    private fun xzOf(g: Any?): V2 = m(g).let { XZ(pyNum(it["lat"]), pyNum(it["lon"])) }

    private fun isDigit(s: String): Boolean = s.isNotEmpty() && s.all { Character.isDigit(it) }

    private val DRIVE_W = mapOf("secondary" to 12.5, "primary" to 14.0, "tertiary" to 11.5, "residential" to 10.0, "unclassified" to 9.0, "living_street" to 6.5, "service" to 4.5)
    private val AREA_LEISURE = setOf("park", "garden", "dog_park", "playground", "pitch", "swimming_pool", "sports_centre")
    private val AREA_LANDUSE = setOf("grass", "greenfield", "brownfield", "construction", "flowerbed")
    private val AREA_NATURAL = setOf("water", "scrub", "wood", "sand")
    private val POI_AMENITY = setOf("cafe", "restaurant", "bar", "pub", "fast_food", "ice_cream", "pharmacy", "bank")

    // render/real.py:112 load
    /** Reads `raw.json` (Overpass with geometry), `bldg.json` (years built) and `trees2015.json` (street tree census) from [osm]. */
    fun load(osm: java.io.File): RealData {
        @Suppress("UNCHECKED_CAST")
        val raw = m(PyJson.load(java.io.File(osm, "raw.json")))["elements"] as List<Any?>
        val years = HashMap<Any?, Int>()
        for (b0 in PyJson.load(java.io.File(osm, "bldg.json")) as List<*>) {
            val b = m(b0)
            val cy = b["construction_year"]
            if (truthy(cy) && isDigit(cy as String) && PyRe.int(cy) > 1700) years[b["bin"]] = PyRe.int(cy)
        }
        val census = (PyJson.load(java.io.File(osm, "trees2015.json")) as List<*>).map { m(it) }.filter { it["status"] == "Alive" }
        val S = RealData()
        val rgc = PyRandom(5)
        fun ringsOf(e: Map<String, Any?>): List<List<V2>> {
            if (e["type"] == "way" && "geometry" in e) return listOf((e["geometry"] as List<*>).map { xzOf(it) })
            if (e["type"] == "relation") {
                return (e["members"] as List<*>? ?: emptyList<Any?>()).map { m(it) }
                    .filter { it["role"] == "outer" && truthy(it["geometry"]) }
                    .map { mm -> (mm["geometry"] as List<*>).map { xzOf(it) } }
            }
            return emptyList()
        }
        for (e0 in raw) {
            val e = m(e0)
            val t0 = e["tags"]
            if (!truthy(t0)) continue
            val t = m(t0)
            if ("building" in t && t["building"] != "no" && (e["type"] == "way" || e["type"] == "relation")) {
                for (ring0 in ringsOf(e)) {
                    var ring = ring0
                    if (ring.size < 4) continue
                    if (same(ring[0], ring[ring.size - 1])) ring = ring.subList(0, ring.size - 1)
                    if (signedArea(ring) < 0) ring = ring.asReversed().toList()
                    if (Math.abs(signedArea(ring)) < 6) continue
                    var h = num(t["height"])
                    var lv: Double? = num(t["building:levels"])
                    val g = LinkedHashMap<String, Boolean>()
                    if (h == null) {
                        h = (if (lv != null && lv != 0.0) lv else 3.0) * 3.2; g["height"] = true
                    }
                    if (lv == null) {
                        lv = Math.max(1L, PyMath.round(h / 3.2)).toDouble(); g["levels"] = true
                    }
                    val yr = years[t["nycdoitt:bin"]]
                    var mat = t["building:material"] as String?; var col = t["building:colour"] as String?
                    if (col.isNullOrEmpty()) {
                        g["colour"] = true
                        if (mat == "glass" || (yr != null && yr >= 2000 && rgc.random() < 0.5)) {
                            mat = if (!mat.isNullOrEmpty()) mat else "glass"; col = "#8FA7C4"
                        } else if (yr != null && yr >= 2000) {
                            col = rgc.choice(PANEL); mat = if (!mat.isNullOrEmpty()) mat else "panel"
                        } else if (yr != null && yr >= 1945) {
                            col = rgc.choice(BUFF); mat = if (!mat.isNullOrEmpty()) mat else "brick"
                        } else {
                            col = rgc.choice(BRICKS); mat = if (!mat.isNullOrEmpty()) mat else "brick"
                        }
                    }
                    if (!truthy(t["building:material"])) g["material"] = true
                    S.buildings.add(Building(ring, h, PyMath.int(lv).toInt(), yr, mat, col, t, g, ArrayList(), (e["id"] as Number).toLong()))
                }
                continue
            }
            if (e["type"] == "way" && "highway" in t && "geometry" in e) {
                val pts = (e["geometry"] as List<*>).map { xzOf(it) }
                val hw = t["highway"] as String
                if (t["footway"] == "crossing" || t["path"] == "crossing") {
                    S.crossings.add(Crossing(pts, t))
                } else if (hw in DRIVE_W) {
                    var w = num(t["width"])
                    if (w == null) {
                        w = DRIVE_W.getValue(hw)
                        if (hw == "service" && (t["service"] == "parking_aisle" || t["service"] == "driveway")) w = 3.5
                    }
                    S.roads.add(Road(pts, w, t, hw))
                } else if (hw == "pedestrian" && (t["area"] == "yes" || same(pts[0], pts[pts.size - 1]))) {
                    S.areas.add(Area(pts.subList(0, pts.size - 1), "plaza", t))
                } else if (hw in setOf("footway", "path", "cycleway", "pedestrian", "steps") && t["footway"] != "sidewalk") {
                    S.roads.add(Road(pts, if (hw != "pedestrian") 2.2 else 5.0, t, "path"))
                }
                continue
            }
            if (t["natural"] == "coastline" && "geometry" in e) {
                S.water.add((e["geometry"] as List<*>).map { xzOf(it) })
                continue
            }
            var kind: String? = null
            if (t["leisure"] in AREA_LEISURE || t["landuse"] in AREA_LANDUSE || t["amenity"] == "parking" || t["natural"] in AREA_NATURAL) {
                kind = listOf(t["leisure"], t["landuse"], t["amenity"], t["natural"]).firstOrNull { truthy(it) } as String?
                    ?: t["natural"] as String?
            }
            if (!kind.isNullOrEmpty() && (e["type"] == "way" || e["type"] == "relation") && t["water"] != "river") {
                for (ring in ringsOf(e)) {
                    if (ring.size >= 4) S.areas.add(Area(if (same(ring[0], ring[ring.size - 1])) ring.subList(0, ring.size - 1) else ring, kind, t))
                }
                continue
            }
            if (e["type"] == "node") {
                val (x, z) = XZ(pyNum(e["lat"]), pyNum(e["lon"]))
                S.nodes.add(Node(x, z, t, (e["id"] as Number).toLong()))
                if (t["natural"] == "tree") S.trees.add(Tree(x, z, t["genus"] as String?, null, null, "osm", t))
                if ("shop" in t || t["amenity"] in POI_AMENITY) S.pois.add(Poi(x, z, t))
            }
        }
        for (c in census) {
            val (x, z) = XZ(PyRe.strip(c["latitude"] as String).toDouble(), PyRe.strip(c["longitude"] as String).toDouble())
            val latin = (c["spc_latin"] as String?).let { if (it.isNullOrEmpty()) "" else it }
            S.trees.add(Tree(x, z, if (latin.isNotEmpty()) latin.split(' ')[0] else null, latin, num(c["tree_dbh"], 8.0), "census",
                dictOf("tree_id" to c["tree_id"]), common = c["spc_common"] as String?))
        }
        return S
    }

    // render/real.py:747 __main__ (the street-front pass)
    /** Which facade faces the street: the edge nearest any drive road, as real.py's `__main__` sets `front`. */
    fun fronts(S: RealData) {
        val drive = S.roads.filter { it.hw != "path" }
        for (b in S.buildings) {
            var bestD = 0.0; var bestI = -1
            val n = b.poly.size
            val (cx, cz) = centroid(b.poly)
            for (i in 0 until n) {
                val mx = (b.poly[i][0] + b.poly[(i + 1) % n][0]) / 2; val mz = (b.poly[i][1] + b.poly[(i + 1) % n][1]) / 2
                for (r in drive) {
                    if (Math.abs(r.pts[0][0] - cx) > 400 && Math.abs(r.pts[r.pts.size - 1][0] - cx) > 400) continue
                    for (k in 0 until r.pts.size - 1) {
                        val dd = segDist(mx, mz, r.pts[k], r.pts[k + 1]).first
                        if (bestI < 0 || dd < bestD) {
                            bestD = dd; bestI = i
                        }
                    }
                }
            }
            b.front = if (bestI >= 0) bestI else null
        }
    }

    // render/real.py:190 attach_shops
    /** Joins every shop node to the nearest building edge that faces a street. */
    fun attachShops(S: RealData) {
        val drive = S.roads.filter { it.hw != "path" && it.hw != "service" }
        fun streetD(mx: Double, mz: Double): Double {
            var best = 1e9
            for (r in drive) {
                if (City.pyMinOf(r.pts.map { Math.abs(it[0] - mx) }) > 300) continue
                for (k in 0 until r.pts.size - 1) {
                    val dd = segDist(mx, mz, r.pts[k], r.pts[k + 1]).first
                    best = PyMath.min(best, dd)
                }
            }
            return best
        }
        for (p in S.pois) {
            var bestS = 0.0; var bestB: Building? = null; var bestI = 0; var bestT = 0.0
            for (b in S.buildings) {
                val (cx, cz) = centroid(b.poly)
                if (Math.abs(cx - p.x) > 60 || Math.abs(cz - p.z) > 60) continue
                for (i in b.poly.indices) {
                    val a = b.poly[i]; val bb = b.poly[(i + 1) % b.poly.size]
                    val (d, t) = segDist(p.x, p.z, a, bb)
                    if (d > 16) continue
                    val score = d + (if (streetD((a[0] + bb[0]) / 2, (a[1] + bb[1]) / 2) < 12) 0 else 25)
                    if (bestB == null || score < bestS) {
                        bestS = score; bestB = b; bestI = i; bestT = t
                    }
                }
            }
            if (bestB != null && bestS < 30) bestB.shops.add(Shop(bestI, bestT, p.tags))
        }
    }

    private fun dist(a: V2, b: V2): Double = PyMath.dist(doubleArrayOf(a.x, a.y), doubleArrayOf(b.x, b.y))

    // render/real.py:214 water_polygon
    /** The coastlines chained into one line and closed far to its right, where the water is; null without coastline. */
    fun waterPolygon(S: RealData): List<V2>? = waterPolygon(S.water)

    /** [waterPolygon] of the coastline pieces themselves. */
    fun waterPolygon(water: List<List<V2>>): List<V2>? {
        if (water.isEmpty()) return null
        val lines = water.mapTo(ArrayList()) { it.toList() }
        var chain = lines.removeAt(0)
        var changed = true
        while (lines.isNotEmpty() && changed) {
            changed = false
            for (k in lines.indices) {
                val l = lines[k]
                if (dist(chain[chain.size - 1], l[0]) < 1) {
                    chain = chain + l.subList(1, l.size); lines.removeAt(k); changed = true; break
                }
                if (dist(l[l.size - 1], chain[0]) < 1) {
                    chain = l.subList(0, l.size - 1) + chain; lines.removeAt(k); changed = true; break
                }
            }
        }
        // water is on the right of the coastline direction: close it far to that side
        val a = chain[0]; val b = chain[chain.size - 1]
        val dx = b[0] - a[0]; val dz = b[1] - a[1]
        val L = PyMath.hypot(dx, dz).let { if (it == 0.0) 1.0 else it }
        val rx = -dz / L * 3000; val rz = dx / L * 3000
        return chain + listOf(V2(b[0] + rx, b[1] + rz), V2(a[0] + rx, a[1] + rz))
    }

    // ------------------------------------------------------------------ sunlight: ray march over a grid of prisms

    // render/real.py:232 Shadows
    /** Sunlight over prisms: a ray march toward the sun [L] through a 16 m grid of the buildings' bounding boxes. */
    class Shadows(buildings: List<Prism>, val L: V3) {
        val cell = 16.0
        private val grid = HashMap<Long, ArrayList<Prism>>()
        val maxh: Double

        init {
            maxh = City.pyMaxOf(buildings.map { it.h })
            for (b in buildings) {
                val xs = b.poly.map { it[0] }; val zs = b.poly.map { it[1] }
                val bb = doubleArrayOf(City.pyMinOf(xs), City.pyMaxOf(xs), City.pyMinOf(zs), City.pyMaxOf(zs))
                b.bb = bb
                for (i in PyMath.floorInt(bb[0] / cell)..PyMath.floorInt(bb[1] / cell)) {
                    for (j in PyMath.floorInt(bb[2] / cell)..PyMath.floorInt(bb[3] / cell)) {
                        grid.getOrPut(key(i, j)) { ArrayList() }.add(b)
                    }
                }
            }
        }

        private fun key(i: Long, j: Long): Long = (i shl 32) xor (j and 0xffffffffL)

        private fun at(x: Double, z: Double): List<Prism>? = grid[key(PyMath.floorInt(x / cell), PyMath.floorInt(z / cell))]

        // render/real.py:243 Shadows.lit
        /** True when the ray from [p] toward the sun leaves every building, within [reach] metres. */
        fun lit(p: V3, skip: Prism? = null, reach: Double = 220.0): Boolean {
            if (L[1] <= 0) return false
            val tmax = PyMath.min(reach, (maxh - p[1]) / L[1])
            var t = 0.8
            while (t < tmax) {
                val x = p[0] + L[0] * t; val y = p[1] + L[1] * t; val z = p[2] + L[2] * t
                val cellList = at(x, z)
                if (cellList != null) {
                    for (b in cellList) {
                        if (b === skip || y > b.h) continue
                        val bb = b.bb!!
                        if (bb[0] <= x && x <= bb[1] && bb[2] <= z && z <= bb[3] && pip(x, z, b.poly)) return false
                    }
                }
                t += 1.6
            }
            return true
        }

        // render/real.py:256 Shadows.top
        /** Height of a building's upper surface over (x, z): the ridge falls away to the eaves on a gabled or hipped roof. */
        fun top(b: Prism, x: Double, z: Double): Double {
            val rh = b.roofH ?: 0.0
            if (rh == 0.0 || b.roof !in ROOFED) return b.h
            var f = b.rf
            if (f == null) {
                val poly = b.poly; val n = poly.size
                var i0 = 0; var best = dist(poly[0], poly[1 % n])
                for (i in 1 until n) {
                    val dd = dist(poly[i], poly[(i + 1) % n])
                    if (dd > best) {
                        best = dd; i0 = i
                    }
                }
                val a = poly[i0]; val c = poly[(i0 + 1) % n]; val L = dist(a, c).let { if (it == 0.0) 1.0 else it }
                var ux = (c[0] - a[0]) / L; var uz = (c[1] - a[1]) / L
                if (b.orient == "across") {
                    val t = ux; ux = -uz; uz = t
                }
                val cx = PyMath.sumOf(poly) { it[0] } / n; val cz = PyMath.sumOf(poly) { it[1] } / n
                val wa = City.pyMaxOf(poly.map { p -> Math.abs((p[0] - cx) * ux + (p[1] - cz) * uz) }).let { if (it == 0.0) 1.0 else it }
                val wp = City.pyMaxOf(poly.map { p -> Math.abs(-(p[0] - cx) * uz + (p[1] - cz) * ux) }).let { if (it == 0.0) 1.0 else it }
                f = doubleArrayOf(cx, cz, ux, uz, wa, wp)
                b.rf = f
            }
            val cx = f[0]; val cz = f[1]; val ux = f[2]; val uz = f[3]; val wa = f[4]; val wp = f[5]
            val va = Math.abs((x - cx) * ux + (z - cz) * uz) / wa; val vp = Math.abs(-(x - cx) * uz + (z - cz) * ux) / wp
            val k = if (b.roof == "pyramidal") PyMath.max(vp, va) else vp
            return b.h - rh * PyMath.min(1.0, k)
        }

        // render/real.py:276 Shadows.shadow_height
        /** Up to what height a wall at (x, z) is in shadow: the highest of (surface height - sun climb) along the ray toward the sun. */
        fun shadowHeight(x: Double, z: Double, skip: Prism? = null, step: Double = 0.45): Double {
            if (L[1] <= 0) return 1e9
            val hl = PyMath.hypot(L[0], L[2]).let { if (it == 0.0) 1e-6 else it }
            val tmax = maxh / L[1]; var best = -1e9; var t = 0.25 / hl
            while (t < tmax) {
                val px = x + L[0] * t; val pz = z + L[2] * t; val climb = L[1] * t
                val cellList = at(px, pz)
                if (cellList != null) {
                    for (b in cellList) {
                        if (b === skip || b.h - climb <= best) continue
                        val bb = b.bb!!
                        if (bb[0] <= px && px <= bb[1] && bb[2] <= pz && pz <= bb[3] && pip(px, pz, b.poly)) {
                            val c = top(b, px, pz) - climb
                            if (c > best) best = c
                        }
                    }
                }
                t += step / hl
            }
            return best
        }

        private companion object {
            val ROOFED = setOf("gabled", "hipped", "pyramidal", "quadruple_saltbox")
        }
    }

    // ------------------------------------------------------------------ themes (reuse the living palettes)

    // render/real.py:291 theme
    /** "noon", "golden", "night" or "truth" (golden light, the guessed things shown as guessed). */
    fun theme(name: String): Dict {
        val base = when (name) {
            "noon" -> Living.NOON
            "golden" -> Living.GOLDEN
            "night" -> Living.NIGHT
            "truth" -> Living.GOLDEN
            else -> throw NoSuchElementException(name)
        }
        val th = LinkedHashMap(base)
        th["truth"] = name == "truth"
        if (name == "truth") th["name"] = "truth"
        return th
    }

    // ------------------------------------------------------------------ render

    /** What [render] returns: the SVG, the camera, and the sun and crossing count (`az`, `el`, `crossings`). */
    class Rendered(val svg: String, val cam: Camera, val info: LinkedHashMap<String, Any?>)

    /**
     * What [renderSplit] returns: the layer behind the split building ([base]), the layer in front of it with the haze
     * ([front]), and [variant], which draws a building alone at the split depth (in truth colours when asked).
     */
    class Split(val base: String, val front: String, val variant: (Building, Boolean) -> String, val cam: Camera, val depth: Double?)

    private class Item(val d: Double, val k: String, val o: Any)

    private fun ints(v: Any?): List<Int> = when (v) {
        is IntArray -> v.toList()
        is List<*> -> v.map { (it as Number).toInt() }
        else -> throw IllegalArgumentException("not a tuple of ints: $v")
    }

    private fun lineOf(cam: Camera, line: List<V2>, y: Double): String? {
        val pts = line.mapNotNull { cam.proj(V3(it.x, y, it.y)) }
        if (pts.size > 1) return "M" + pts.joinToString("L") { PyFmt.pf("%.1f %.1f", it.x, it.y) }
        return null
    }

    private val FURN = setOf("street_lamp", "fire_hydrant", "bench", "bicycle_parking", "bicycle_rental", "subway_entrance", "waste_basket", "drinking_water", "bus_stop", "picnic_table")
    private val AREA_ORDER = listOf(
        "park", "grass", "garden", "greenfield", "brownfield", "construction", "parking", "plaza", "sports_centre", "pitch", "dog_park", "playground",
        "swimming_pool", "water", "scrub", "wood", "sand", "flowerbed",
    )

    // render/real.py:299 render
    /** The real map in theme [th] through the camera [cfg], over the water polygon [water]. */
    fun render(S: RealData, th: Map<String, Any?>, cfg: Map<String, Any?>, water: List<V2>?): Rendered =
        renderImpl(S, th, cfg, water, null) as Rendered

    /** real.py's `render(..., split=b)`: the layers behind and in front of building [split], and its variants. */
    fun renderSplit(S: RealData, th: Map<String, Any?>, cfg: Map<String, Any?>, water: List<V2>?, split: Building): Split =
        renderImpl(S, th, cfg, water, split) as Split

    private fun renderImpl(S: RealData, th0: Map<String, Any?>, cfg: Map<String, Any?>, water: List<V2>?, split: Building?): Any {
        // real.py keeps cfg['roof_k'] in a module global for draw_building; here the scene carries it
        val roofK = cfg.num("roof_k", 1.0)
        val W = cfg.int("W"); val H = cfg.int("H")
        val cam = Camera(v3(cfg["target"]), cfg.num("yaw"), cfg.num("pitch"), cfg.num("dist"), cfg.num("fov"), W.toDouble(), H.toDouble(),
            cx = cfg.numOrNull("cx"), cy = cfg.numOrNull("cy"))
        val hhmm = ints(th0["hhmm"])
        val date = th0["date"]?.let { ints(it) } ?: listOf(2026, 9, 30)
        val tUtc = LocalDateTime.of(date[0], date[1], date[2], hhmm[0], hhmm[1]).plusHours(4)
        val (az, el) = Living.sunPosition(tUtc)
        val L = if (el > 0) Living.sunVector(az, el) else V3(0.0, -1.0, 0.0)
        val th = LinkedHashMap(th0); th["_L"] = L
        val night = th.flag("night"); val truth = th.flag("truth")
        val sh = Shadows(S.buildings, L)
        val sc = Living.Scene(emptyList(), th, cam, L, emptyList())
        sc.roofK = roofK
        // real.py: LV.sunlit = lambda scene, p, skip=None: (not night) and sh.lit(p, skip=None)
        sc.sunlit = { _, p, _ -> (!night) && sh.lit(p, null) }
        val defs = ArrayList<String>(); val ground = ArrayList<String>(); val marks = ArrayList<String>(); val shadowPaths = ArrayList<String>()
        val items = ArrayList<Item>()
        fun gp(pts: List<V2>, fill: Any?, y: Double = 0.0, extra: String = "", margin: Double = 900.0): String {
            val sp = cam.poly(pts.map { V3(it.x, y, it.y) }, margin = margin)
            if (sp != null && area(sp) > 0.3) return PyFmt.pf("<path d=\"%s\" fill=\"%s\"%s/>", P(sp), fill, extra)
            return ""
        }
        val hy = cam.horizonY()
        defs.add(PyFmt.pf("<linearGradient id=\"sky\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"%.0f\" x2=\"0\" y2=\"%.0f\"><stop offset=\"0\" stop-color=\"%s\"/><stop offset=\"1\" stop-color=\"%s\"/></linearGradient>",
            hy - 300, hy, th["sky_top"], th["sky_hor"]))
        ground.add(PyFmt.pf("<rect width=\"%d\" height=\"%d\" fill=\"url(#sky)\"/>", W, H))
        val yard = if (!truth) th["lot"] else "#E6E1D6"
        ground.add(gp(City.rect(-2500.0, 2500.0, -2500.0, 2500.0), yard, margin = 5000.0))
        if (!water.isNullOrEmpty()) ground.add(gp(water, if ("water" in th) th["water"] else (if (!night) "#9DC2E2" else "#070B16"), 0.0, margin = 5000.0))
        // areas
        val areaCol: Map<String, Any?> = mapOf(
            "park" to th["lawn"], "garden" to th["yard_grass"], "grass" to th["yard_grass"], "dog_park" to th["yard_pave"], "playground" to (if (!night) "#D98E5A" else "#2A2230"),
            "pitch" to (if (!night) "#6FA25A" else "#14231E"), "swimming_pool" to (if (!night) "#7FC3E6" else "#0A1422"), "parking" to th["asphalt"], "plaza" to th["path"],
            "construction" to (if (!night) "#B9A07A" else "#1E1C1A"), "brownfield" to (if (!night) "#B7A88A" else "#1C1B1A"), "greenfield" to th["yard_grass"], "sports_centre" to th["yard_pave"],
            "water" to (if (!night) "#9DC2E2" else "#070B16"), "scrub" to th["yard_grass"], "wood" to th["tree_dark"], "sand" to "#E6D8B0", "flowerbed" to "#8CA462",
        )
        for (k in AREA_ORDER) {
            for (a in S.areas) {
                if (a.kind == k) ground.add(gp(a.poly, if (k in areaCol) areaCol[k] else th["lawn"], 0.01))
            }
        }
        // roads: sidewalk band, then carriageway
        val drive = S.roads.filter { it.hw != "path" }
        for (r in drive) {
            if (r.hw == "service") continue
            ground.add(gp(City.ribbon(r.pts, r.w / 2 + 4.6), th["walk"], 0.02))
        }
        for (r in S.roads) {
            if (r.hw == "path") ground.add(gp(City.ribbon(r.pts, r.w / 2), th["path"], 0.025))
        }
        for (r in drive) {
            val surf = r.tags["surface"]
            var col = if (surf == "sett" || surf == "cobblestone" || surf == "unhewn_cobblestone") th["sett"] else th["asphalt"]
            if (truth) col = if (truthy(surf)) "#8A8F98" else "#B9BCC2"
            ground.add(gp(City.ribbon(r.pts, r.w / 2), col, 0.03))
        }
        // curbs + lane lines + bike lanes
        val curb = StringBuilder(); val yel = StringBuilder()
        for (r in drive) {
            if (r.hw == "service") continue
            val n = r.pts.size
            for (side in intArrayOf(-1, 1)) {
                val off = City.ribbon(r.pts, r.w / 2)
                val line = if (side < 0) off.subList(0, n) else off.subList(n, off.size).asReversed()
                lineOf(cam, line, 0.04)?.let { curb.append(it) }
            }
            val t = r.tags
            if ((r.hw == "secondary" || r.hw == "primary" || r.hw == "tertiary") && t["oneway"] != "yes") {
                for (dx in doubleArrayOf(-0.12, 0.12)) {
                    val off = City.ribbon(r.pts, Math.abs(dx))
                    val line = if (dx < 0) off.subList(0, n) else off.subList(n, off.size).asReversed()
                    lineOf(cam, line, 0.05)?.let { yel.append(it) }
                }
            }
            for ((side, key) in listOf(1 to "cycleway:right", -1 to "cycleway:left", 0 to "cycleway:both")) {
                val v0 = t[key]
                val v = if (truthy(v0)) v0 else (if (key == "cycleway:right") t["cycleway"] else null)
                if (v == "lane" || v == "track" || v == "separate") {
                    val sides = if (side == 0) intArrayOf(1, -1) else intArrayOf(side)
                    for (sd in sides) {
                        val inner = City.ribbon(r.pts, r.w / 2 - 2.2); val outer = City.ribbon(r.pts, r.w / 2 - 0.6)
                        val li = if (sd > 0) inner.subList(n, inner.size).asReversed() else inner.subList(0, n)
                        val lo = if (sd > 0) outer.subList(n, outer.size).asReversed() else outer.subList(0, n)
                        val band = li + lo.asReversed()
                        marks.add(gp(band, th["bike"], 0.045, " opacity=\".9\""))
                    }
                }
            }
        }
        marks.add(PyFmt.pf("<path d=\"%s\" stroke=\"%s\" stroke-width=\"1.1\" fill=\"none\"/>", curb.toString(), th["curb"]))
        if (yel.isNotEmpty()) marks.add(PyFmt.pf("<path d=\"%s\" stroke=\"%s\" stroke-width=\".9\" fill=\"none\"/>", yel.toString(), th["lane_y"]))
        // crossings from footway=crossing ways, painted as tagged
        val stripePs = StringBuilder(); val linePs = StringBuilder(); var nCross = 0
        for (c in S.crossings) {
            val t = c.tags
            val mk0 = t["crossing:markings"]
            val mk = if (truthy(mk0)) mk0 else (if (t["crossing"] in setOf("marked", "zebra", "traffic_signals", "uncontrolled")) "zebra" else null)
            if (mk == null || mk == "no") continue
            nCross++
            for (i in 0 until c.pts.size - 1) {
                val a = c.pts[i]; val b = c.pts[i + 1]
                val Lg = dist(a, b)
                if (Lg < 0.5) continue
                val ux = (b[0] - a[0]) / Lg; val uz = (b[1] - a[1]) / Lg
                val px = -uz; val pz = ux
                var k = 0.4
                while (k < Lg - 0.3) {
                    val cx = a[0] + ux * k; val cz = a[1] + uz * k
                    val q = listOf(
                        V2(cx - ux * 0.3 - px * 1.6, cz - uz * 0.3 - pz * 1.6), V2(cx + ux * 0.3 - px * 1.6, cz + uz * 0.3 - pz * 1.6),
                        V2(cx + ux * 0.3 + px * 1.6, cz + uz * 0.3 + pz * 1.6), V2(cx - ux * 0.3 + px * 1.6, cz - uz * 0.3 + pz * 1.6),
                    )
                    val sp = cam.poly(q.map { V3(it.x, 0.06, it.y) })
                    if (sp != null && area(sp) > 0.15) stripePs.append(P(sp))
                    k += 1.2
                }
                if (mk == "ladder" || mk == "lines" || mk == "ladder:skewed") {
                    for (s in doubleArrayOf(-1.75, 1.75)) {
                        val p0 = cam.proj(V3(a[0] + px * s, 0.06, a[1] + pz * s)); val p1 = cam.proj(V3(b[0] + px * s, 0.06, b[1] + pz * s))
                        if (p0 != null && p1 != null) linePs.append(PyFmt.pf("M%.1f %.1fL%.1f %.1f", p0[0], p0[1], p1[0], p1[1]))
                    }
                }
            }
        }
        val op = if (night) 0.2 else 0.9
        marks.add(PyFmt.pf("<path d=\"%s\" fill=\"#F4F1EA\" opacity=\"%.2f\"/>", stripePs.toString(), op))
        if (linePs.isNotEmpty()) marks.add(PyFmt.pf("<path d=\"%s\" stroke=\"#F4F1EA\" stroke-width=\"1.1\" opacity=\"%.2f\" fill=\"none\"/>", linePs.toString(), op))
        // ---------------------------------------------------------- shadows (group opacity makes the union even)
        val RAD = cfg.numOrNull("radius"); val target = v3(cfg["target"]); val TX = target[0]; val TZ = target[2]
        fun far(x: Double, z: Double): Boolean = RAD != null && PyMath.hypot(x - TX, z - TZ) > RAD + 60
        if (!night && L[1] > 0) {
            fun o(y: Double): V2 = V2(-L[0] / L[1] * y, -L[2] / L[1] * y)
            for (b in S.buildings) {
                val (cx, cz) = centroid(b.poly)
                if (far(cx, cz) || cam.depth(V3(cx, 0.0, cz)) < -80) continue
                val off = o(b.h)
                val poly = b.poly
                var sp = cam.poly(poly.map { V3(it.x + off[0], 0.08, it.y + off[1]) }, margin = 900.0)
                if (sp != null) shadowPaths.add(P(sp))
                for (i in poly.indices) {
                    val a = poly[i]; val bb = poly[(i + 1) % poly.size]
                    val q = listOf(V3(a[0], 0.08, a[1]), V3(bb[0], 0.08, bb[1]), V3(bb[0] + off[0], 0.08, bb[1] + off[1]), V3(a[0] + off[0], 0.08, a[1] + off[1]))
                    sp = cam.poly(q, margin = 900.0)
                    if (sp != null && area(sp) > 0.3) shadowPaths.add(P(sp))
                }
            }
            for (tr in S.trees) {
                val (hgt, rad) = treeSize(tr)
                val off = o(hgt * 0.72)
                val pts = (0 until 16).map { k ->
                    val a = k * PyMath.PI / 8
                    V3(tr.x + off[0] + rad * PyMath.cos(a), 0.08, tr.z + off[1] + rad * PyMath.sin(a))
                }
                val sp = cam.poly(pts)
                if (sp != null) shadowPaths.add(P(sp))
            }
        }
        // ---------------------------------------------------------- objects in depth order
        for (b in S.buildings) {
            val (cx, cz) = centroid(b.poly)
            if (far(cx, cz)) continue
            val c = cam.cam(V3(cx, PyMath.min(8.0, b.h / 2), cz))
            if (c[2] < 2 || c[2] > cfg.num("max_depth", 1400.0)) continue
            val p = cam.pc(c)
            if (!(-500 < p[0] && p[0] < W + 500 && -600 < p[1] && p[1] < H + 400)) continue
            items.add(Item(c[2], "b", b))
        }
        for (tr in S.trees) {
            if (far(tr.x, tr.z)) continue
            val c = cam.cam(V3(tr.x, 1.0, tr.z))
            if (c[2] < 2 || c[2] > cfg.num("max_depth", 1200.0)) continue
            items.add(Item(c[2], "t", tr))
        }
        for (n in S.nodes) {
            val t = n.tags
            val k = if (t["highway"] == "street_lamp" || t["highway"] == "bus_stop") t["highway"]
            else if (t["emergency"] == "fire_hydrant") t["emergency"]
            else if (t["amenity"] in FURN) t["amenity"]
            else if (t["railway"] == "subway_entrance") t["railway"]
            else if (t["leisure"] == "picnic_table") t["leisure"]
            else null
            if (!truthy(k)) continue
            val c = cam.cam(V3(n.x, 1.0, n.z))
            if (c[2] < 2 || c[2] > cfg.num("max_depth", 900.0)) continue
            items.add(Item(c[2], k as String, n))
        }
        // no cars or people: they are not OpenStreetMap tags, so they are not drawn
        items.pySortBy { -it.d }
        val out = ArrayList<String>()
        var cut: Int? = null; var splitD: Double? = null
        for (it in items) {
            val d = it.d; val k = it.k
            if (split != null && k == "b" && it.o === split) {
                cut = out.size; splitD = d; continue
            }
            when (k) {
                "b" -> out.addAll(drawBuilding(sc, sh, it.o as Building, d, th))
                "t" -> out.addAll(drawTree(sc, it.o as Tree, d, th))
                else -> {
                    val o = it.o as Node
                    val at = Feature("", listOf(o.x, o.z), dictOf())
                    when (k) {
                        "street_lamp" -> {
                            val lampDir = nearestRoadDir(drive, o.x, o.z)
                            out.addAll(drawLamp(sc, o, d, lampDir, th))
                        }
                        "fire_hydrant" -> out.addAll(Living.drawPost(sc, at, 0.85, 0.28, if (!night) "#C0392B" else "#4A1F1C", "#E8C04A"))
                        "bench", "picnic_table" -> out.addAll(Living.box(sc, o.x - 0.8, o.x + 0.8, 0.4, 0.5, o.z - 0.25, o.z + 0.25, if (!night) City.hx("#6E4B3A") else City.hx("#1A1614")))
                        "waste_basket" -> out.addAll(Living.drawPost(sc, at, 0.9, 0.3, if (!night) "#2F5A3A" else "#10181A", "#1F3A28"))
                        "drinking_water" -> out.addAll(Living.drawPost(sc, at, 0.95, 0.3, "#5A6B7A", if (!night) "#7FC3FF" else "#2A4A6A"))
                        "bicycle_parking" -> out.addAll(Living.drawRack(sc, at))
                        "bicycle_rental" -> out.addAll(drawDock(sc, o, drive, th))
                        "subway_entrance" -> out.addAll(Living.drawSubway(sc, Feature("", listOf(o.x, o.z), LinkedHashMap(o.tags)), null, LinkedHashMap()))
                        "bus_stop" -> out.addAll(Living.drawPost(sc, at, 2.4, 0.12, "#3A3F46", "#2E5BFF"))
                    }
                }
            }
        }
        // night pools
        val pools = ArrayList<String>()
        if (night) {
            defs.add("<radialGradient id=\"pool\"><stop offset=\"0\" stop-color=\"#FFB45E\" stop-opacity=\".55\"/><stop offset=\".5\" stop-color=\"#FFA848\" stop-opacity=\".18\"/><stop offset=\"1\" stop-color=\"#FFA848\" stop-opacity=\"0\"/></radialGradient>")
            defs.add("<radialGradient id=\"lampglow\"><stop offset=\"0\" stop-color=\"#FFF1D2\" stop-opacity=\".95\"/><stop offset=\".12\" stop-color=\"#FFD9A0\" stop-opacity=\".55\"/><stop offset=\".45\" stop-color=\"#FFC27A\" stop-opacity=\".12\"/><stop offset=\"1\" stop-color=\"#FFC27A\" stop-opacity=\"0\"/></radialGradient>")
            defs.add("<radialGradient id=\"shopglow\"><stop offset=\"0\" stop-color=\"#FFD39A\" stop-opacity=\".5\"/><stop offset=\"1\" stop-color=\"#FFD39A\" stop-opacity=\"0\"/></radialGradient>")
            defs.add("<filter id=\"streetglow\" x=\"-20%\" y=\"-20%\" width=\"140%\" height=\"140%\"><feGaussianBlur stdDeviation=\"6\"/></filter>")
            val glowPs = StringBuilder()
            for (r in drive) {
                if (r.hw == "service") continue
                val sp = cam.poly(City.ribbon(r.pts, r.w / 2 + 2).map { V3(it.x, 0.05, it.y) }, margin = 600.0)
                if (sp != null && area(sp) > 1) glowPs.append(P(sp))
            }
            pools.add(PyFmt.pf("<path d=\"%s\" fill=\"#FFB25E\" opacity=\".22\" filter=\"url(#streetglow)\"/>", glowPs.toString()))
            for (n in S.nodes) {
                if (n.tags["highway"] != "street_lamp") continue
                val c = cam.cam(V3(n.x, 0.0, n.z))
                if (c[2] < 2) continue
                val p = cam.pc(c)
                if (!(-80 < p[0] && p[0] < W + 80 && -80 < p[1] && p[1] < H + 80)) continue
                val rx = 12 * cam.focal / c[2]
                val q = cam.proj(V3(n.x, 0.0, n.z + 12)) ?: p
                pools.add(PyFmt.pf("<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"url(#pool)\"/>", p[0], p[1], rx, PyMath.max(0.6, Math.abs(q[1] - p[1]))))
            }
        }
        // haze
        val stops = StringBuilder(); val top = PyMath.max(0.0, hy)
        val haze0 = cfg.num("haze0", 160.0); val haze1 = cfg.num("haze1", 700.0)
        for (k in 0 until 17) {
            val sy = top + (H - top) * k / 16
            val gd = cam.groundDepth(sy + 0.01)
            val a = if (gd == null) th.num("haze_max") else th.num("haze_max") * PyMath.pow(clamp((gd - haze0) / haze1), 1.2)
            stops.append(PyFmt.pf("<stop offset=\"%.3f\" stop-color=\"%s\" stop-opacity=\"%.3f\"/>", k / 16.0, th["haze"], a))
        }
        defs.add(PyFmt.pf("<linearGradient id=\"haze\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"%.1f\" x2=\"0\" y2=\"%d\">%s</linearGradient>", top, H, stops.toString()))
        var haze = PyFmt.pf("<rect y=\"%.1f\" width=\"%d\" height=\"%.1f\" fill=\"url(#haze)\"/>", top, W, H - top)
        if (th["name"] == "golden") {
            defs.add("<linearGradient id=\"wash\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\".35\"><stop offset=\"0\" stop-color=\"#FFB25E\" stop-opacity=\".28\"/><stop offset=\".45\" stop-color=\"#FFC98A\" stop-opacity=\".07\"/><stop offset=\"1\" stop-color=\"#FFC98A\" stop-opacity=\"0\"/></linearGradient>")
            haze += PyFmt.pf("<rect width=\"%d\" height=\"%d\" fill=\"url(#wash)\"/>", W, H)
        }
        val shadowG = if (shadowPaths.isNotEmpty()) {
            PyFmt.pf("<g opacity=\"%.2f\" fill=\"%s\">%s</g>", th["shadow_op"], th["shadow"], shadowPaths.joinToString("") { "<path d=\"$it\"/>" })
        } else ""
        val head = listOf(
            PyFmt.pf("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\">", W, H, W, H),
            PyFmt.pf("<defs><clipPath id=\"vb\"><rect width=\"%d\" height=\"%d\"/></clipPath>%s</defs><g clip-path=\"url(#vb)\">", W, H, defs.joinToString("")),
        )
        if (split != null) {
            val c = cut ?: out.size
            val base = (head + listOf(ground.joinToString(""), marks.joinToString(""), shadowG, pools.joinToString(""), "<g stroke-linejoin=\"round\">",
                out.subList(0, c).joinToString(""), "</g></g></svg>")).joinToString("")
            val front = (head + listOf("<g stroke-linejoin=\"round\">", out.subList(c, out.size).joinToString(""), "</g>", haze, "</g></svg>")).joinToString("")
            val d = splitD
            val variant = { bv: Building, tr: Boolean ->
                val th2 = LinkedHashMap(th); th2["truth"] = tr
                val body = drawBuilding(sc, sh, bv, d ?: throw IllegalStateException("the split building is not drawn: no depth"), th2).joinToString("")
                PyFmt.pf("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\" style=\"position: absolute; left: 0; top: 0\" aria-hidden=\"true\"><g stroke-linejoin=\"round\">%s</g></svg>",
                    W, H, W, H, body)
            }
            return Split(base, front, variant, cam, splitD)
        }
        val svg = head + listOf(ground.joinToString(""), marks.joinToString(""), shadowG, pools.joinToString(""), "<g stroke-linejoin=\"round\">",
            out.joinToString(""), "</g>", haze, "</g></svg>")
        return Rendered(svg.joinToString(""), cam, linkedMapOf("az" to az, "el" to el, "crossings" to nCross))
    }

    // render/real.py:518 nearest_road_dir
    /** The unit vector from (x, z) toward the nearest drive road: where a lamp's arm points. */
    fun nearestRoadDir(drive: List<Road>, x: Double, z: Double): V2 {
        var bestD = 0.0; var bestA: V2? = null; var bestB: V2? = null
        for (r in drive) {
            for (i in 0 until r.pts.size - 1) {
                val d = segDist(x, z, r.pts[i], r.pts[i + 1]).first
                if (bestA == null || d < bestD) {
                    bestD = d; bestA = r.pts[i]; bestB = r.pts[i + 1]
                }
            }
        }
        if (bestA == null) return V2(1.0, 0.0)
        val a = bestA; val b = bestB!!
        // arm points from the pole toward the road
        val L = dist(a, b).let { if (it == 0.0) 1.0 else it }
        val ux = (b[0] - a[0]) / L; val uz = (b[1] - a[1]) / L
        var nx = -uz; var nz = ux
        val mx = (a[0] + b[0]) / 2; val mz = (a[1] + b[1]) / 2
        if ((mx - x) * nx + (mz - z) * nz < 0) {
            nx = -nx; nz = -nz
        }
        return V2(nx, nz)
    }

    // render/real.py:534 tree_size
    /** Height and crown radius from the trunk diameter (inches), 10 when unknown. */
    fun treeSize(tr: Tree): Pair<Double, Double> {
        val dbh = tr.dbh.let { if (it == null || it == 0.0) 10.0 else it }
        return PyMath.min(22.0, 5.0 + 0.5 * dbh) to PyMath.min(13.0, 1.5 + 0.3 * dbh) / 2 + 1.0
    }

    // render/real.py:538 draw_tree
    /** A tree through living.draw_tree, its leaves turned by genus in late September. */
    fun drawTree(sc: Living.Scene, tr: Tree, d: Double, th: Map<String, Any?>): List<String> {
        val (hgt, rad) = treeSize(tr)
        val f = Feature("tree", listOf(tr.x, tr.z), dictOf("height" to PyFmt.str(hgt), "diameter_crown" to PyFmt.str(rad * 2), "genus" to null))
        val gc = GENUS_COL[tr.genus]
        val th2 = LinkedHashMap(th)
        if (gc != null && !th.flag("night") && th["season"] != "summer") {
            val rr = PyRandom(PyHash.hashTuple(listOf(PyMath.round(tr.x, 1), PyMath.round(tr.z, 1))) and 0xffffL)
            th2["tree"] = City.tohex(City.mix(City.hx(th.str("tree")), City.hx(gc.first), clamp(gc.second * rr.uniform(0.6, 1.5))))
        }
        if (th.flag("truth") && tr.source == "census") th2["tree"] = "#7FA86A"
        val sc2 = Living.Scene(emptyList(), th2, sc.cam, sc.L, emptyList())
        sc2.sunlit = sc.sunlit; sc2.roofK = sc.roofK
        return Living.drawTree(sc2, f, d, null)
    }

    // render/real.py:550 draw_lamp
    /** A street lamp with its arm toward the road ([dirn]). */
    fun drawLamp(sc: Living.Scene, n: Node, d: Double, dirn: V2, th: Map<String, Any?>): List<String> {
        val cam = sc.cam
        val x = n.x; val z = n.z
        val p0 = cam.proj(V3(x, 0.0, z)); val p1 = cam.proj(V3(x, 8.2, z))
        val p2 = cam.proj(V3(x + dirn[0] * 1.8, 8.5, z + dirn[1] * 1.8))
        if (p0 == null || p1 == null || p2 == null) return emptyList()
        val night = th.flag("night")
        val out = arrayListOf(PyFmt.pf("<path d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1f\" stroke=\"%s\" stroke-width=\"%.2f\" fill=\"none\"/>",
            p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], if (!night) "#3A3F46" else "#1A1E28", PyMath.max(0.5, 18 / d)))
        val rr = PyMath.max(0.8, 0.45 * cam.focal / d)
        out.add(PyFmt.pf("<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"%s\"/>", p2[0], p2[1] + rr * 0.3, rr * 1.4, rr * 0.6, if (!night) "#2A2D33" else "#FFE9C2"))
        if (night) out.add(PyFmt.pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"url(#lampglow)\"/>", p2[0], p2[1], rr * 8))
        return out
    }

    // render/real.py:562 draw_car
    /** A car at (x, z) heading (ux, uz): an oriented 4.4 x 1.8 body with a cabin on top. */
    fun drawCar(sc: Living.Scene, x: Double, z: Double, ux: Double, uz: Double, col: String): List<String> {
        val cam = sc.cam
        val out = ArrayList<String>()
        val L = sc.th["_L"] as V3
        fun obox(l: Double, w: Double, y0: Double, y1: Double, c: V3): List<String> {
            val px = -uz; val pz = ux
            val corners = listOf(
                V2(x + ux * l / 2 + px * w / 2, z + uz * l / 2 + pz * w / 2), V2(x - ux * l / 2 + px * w / 2, z - uz * l / 2 + pz * w / 2),
                V2(x - ux * l / 2 - px * w / 2, z - uz * l / 2 - pz * w / 2), V2(x + ux * l / 2 - px * w / 2, z + uz * l / 2 - pz * w / 2),
            )
            val res = ArrayList<String>()
            for (i in 0 until 4) {
                val a = corners[i]; val b = corners[(i + 1) % 4]
                val nrm = City.vnorm(V3((a[0] + b[0]) / 2 - x, 0.0, (a[1] + b[1]) / 2 - z))
                if (City.vdot(nrm, City.vsub(cam.C, V3((a[0] + b[0]) / 2, (y0 + y1) / 2, (a[1] + b[1]) / 2))) <= 0) continue
                val (s, _) = Living.facePath(cam, listOf(V3(a[0], y0, a[1]), V3(b[0], y0, b[1]), V3(b[0], y1, b[1]), V3(a[0], y1, a[1])),
                    City.tohex(Living.light(sc.th, c, nrm, L[1] > 0 && City.vdot(nrm, L) > 0)))
                if (s != null) res.add(s)
            }
            val (s, _) = Living.facePath(cam, corners.map { V3(it.x, y1, it.y) }, City.tohex(Living.light(sc.th, c, V3(0.0, 1.0, 0.0), L[1] > 0)))
            if (s != null) res.add(s)
            return res
        }
        val night = sc.th.flag("night")
        val c = if (!night) City.hx(col) else City.mulc(City.hx(col), 0.25)
        out += obox(4.4, 1.8, 0.25, 1.0, c)
        out += obox(2.4, 1.6, 1.0, 1.5, if (!night) City.mix(City.hx("#2A3140"), c, 0.25) else City.hx("#0A0D16"))
        return out
    }

    // render/real.py:585 draw_dock
    /** A bike-share dock: a row of docks along the road, one per three bikes of capacity (4 to 12). */
    fun drawDock(sc: Living.Scene, n: Node, drive: List<Road>, th: Map<String, Any?>): List<String> {
        val cam = sc.cam
        val dirn = nearestRoadDir(drive, n.x, n.z)
        val ux = -dirn[1]; val uz = dirn[0]
        val out = ArrayList<String>()
        val cap = PyMath.int(num(n.tags["capacity"], 20.0)!!).toInt()
        val kN = Math.min(12, Math.max(4, Math.floorDiv(cap, 3)))
        val segs = StringBuilder()
        for (k in 0 until kN) {
            val bx = n.x + ux * (k - kN / 2.0) * 0.9; val bz = n.z + uz * (k - kN / 2.0) * 0.9
            val a = cam.proj(V3(bx - dirn[0] * 0.8, 0.5, bz - dirn[1] * 0.8)); val b = cam.proj(V3(bx + dirn[0] * 0.8, 0.5, bz + dirn[1] * 0.8))
            if (a != null && b != null) segs.append(PyFmt.pf("M%.1f %.1fL%.1f %.1f", a[0], a[1], b[0], b[1]))
        }
        if (segs.isNotEmpty()) {
            out.add(PyFmt.pf("<path d=\"%s\" stroke=\"%s\" stroke-width=\"%.1f\" stroke-linecap=\"round\" fill=\"none\"/>",
                segs.toString(), if (!th.flag("night")) "#2E6FD8" else "#14285A", PyMath.max(1.0, 0.5 * cam.focal / cam.depth(V3(n.x, 0.5, n.z)))))
        }
        return out
    }

    private val ROOFS = listOf("#C9C6C0", "#B7B4AE", "#A6A39D", "#5A5856", "#6E6B66", "#D8D5CE", "#8E8A84")
    private val LIT_ROOMS = listOf("#FFD08A", "#FFC56F", "#FFE2AE", "#FFB85C", "#E8EEFF")
    private val ROOM_OPS = listOf(0.55, 0.7, 0.85, 0.95)

    // render/real.py:601 draw_building
    /** A real building: lit facade cells, storefronts from its shop nodes, windows from its levels, cornice, fire escapes and roof. */
    fun drawBuilding(sc: Living.Scene, sh: Shadows, b: Building, d: Double, th: Map<String, Any?>): List<String> {
        val cam = sc.cam; val L = sc.L
        val night = th.flag("night"); val truth = th.flag("truth")
        val poly = b.poly; val h = b.h
        val out = ArrayList<String>()
        val n = poly.size
        val g = b.guessed
        val gColour = g["colour"] == true
        val clay = City.hx("#EDE8DE")
        val base = if (!(truth && gColour)) City.hx(b.col) else clay
        val glass = b.mat == "glass" && !(truth && gColour)
        val rgb = PyRandom(b.id and 0xffffL)
        val lv = b.levels; val fh = h / Math.max(1, lv)
        val near = d < 460
        val hhmm = ints(th["hhmm"]).toIntArray()
        for (i in 0 until n) {
            val a = poly[i]; val bb = poly[(i + 1) % n]
            val ex = bb[0] - a[0]; val ez = bb[1] - a[1]
            val elen = PyMath.hypot(ex, ez)
            if (elen < 0.4) continue
            val nrm = City.vnorm(V3(ez, 0.0, -ex)) // outward for CCW rings in (x, z) with z south
            if (City.vdot(nrm, City.vsub(cam.C, V3((a[0] + bb[0]) / 2, h / 2, (a[1] + bb[1]) / 2))) <= 0) continue
            val ncol = if (d < 700) Math.max(1, PyMath.int(elen / 5).toInt()) else Math.max(1, PyMath.int(elen / 16).toInt())
            val nrow = if (d < 700) Math.max(1, Math.min(lv, 12)) else Math.max(1, Math.min(lv, 2))
            val rh = h / nrow
            val facing = City.vdot(nrm, L) > 0 && !night
            for (r in 0 until nrow) {
                for (ci in 0 until ncol) {
                    val u0 = ci.toDouble() / ncol; val u1 = (ci + 1).toDouble() / ncol
                    val ya = r * rh; val yb = (r + 1) * rh
                    var lit = false
                    if (facing) {
                        val pc = V3(a[0] + ex * (u0 + u1) / 2 + nrm[0] * 0.15, (ya + yb) / 2, a[1] + ez * (u0 + u1) / 2 + nrm[2] * 0.15)
                        lit = sh.lit(pc, skip = b)
                    }
                    var col: V3
                    if (glass) {
                        val refl = if (!night) City.mix(City.hx(th.str("glass_sky")), City.hx(th.str("sky_hor")), clamp(1 - ya / PyMath.max(1.0, h))) else City.hx(th.str("glass_sky"))
                        col = Living.light(th, City.mulc(refl, 0.8), nrm, lit)
                        if (!night) col = City.mix(col, if (lit) City.hx(th.str("glass_sun")) else refl, (if (lit) 0.6 else 0.45) * rgb.uniform(0.55, 1.1))
                    } else {
                        col = Living.light(th, City.mulc(base, 1 + rgb.uniform(-0.02, 0.02)), nrm, lit)
                    }
                    val hc = City.tohex(col)
                    val (s, _) = Living.facePath(cam, Living.quadAt(a, bb, ya, yb, u0, u1), hc, PyFmt.pf(" stroke=\"%s\" stroke-width=\".3\"", hc))
                    if (s != null) out.add(s)
                }
            }
            if (truth && gColour) {
                val (s, _) = Living.facePath(cam, Living.quadAt(a, bb, 0.0, h, 0.0, 1.0), "none", " stroke=\"#9A958C\" stroke-width=\".5\"")
                if (s != null) out.add(s)
                continue
            }
            // storefronts from real shop nodes on this edge
            val shops = b.shops.filter { it.edge == i }
            for (s_ in shops) {
                val oh = s_.tags["opening_hours"] as String?
                val st = openAt(oh, "We", hhmm)
                val uc = clamp(s_.u, 0.12, 0.88); val half = PyMath.min(0.4, 3.2 / elen)
                val glow = st == true
                val gl = if (truth) (if (!oh.isNullOrEmpty()) "#7FC3A0" else "#E0DCD2")
                else if (glow && night) "#FFD9A0" else if (glow) "#F6DDA8" else if (st == false) "#8C9098" else th.str("win")
                val (s, sp) = Living.facePath(cam, Living.quadAt(a, bb, 0.35, 3.0, uc - half, uc + half, 0.06, nrm), gl, PyFmt.pf(" stroke=\"%s\" stroke-width=\".6\"", th["frame"]))
                if (s != null) {
                    out.add(s)
                    if (st == false && !truth) {
                        val gates = StringBuilder()
                        for (yy in doubleArrayOf(0.8, 1.3, 1.8, 2.3)) {
                            val q = Living.quadAt(a, bb, yy, yy, uc - half, uc + half, 0.07, nrm)
                            val p0 = cam.proj(q[0]); val p1 = cam.proj(q[1])
                            if (p0 != null && p1 != null) gates.append(PyFmt.pf("M%.1f %.1fL%.1f %.1f", p0[0], p0[1], p1[0], p1[1]))
                        }
                        if (gates.isNotEmpty()) out.add(PyFmt.pf("<path d=\"%s\" stroke=\"%s\" stroke-width=\".5\" opacity=\".7\"/>", gates.toString(), if (!night) "#5A5E66" else "#20242C"))
                    }
                    if (glow && night && sp != null) {
                        val xs = sp.map { it.x }; val ys = sp.map { it.y }
                        out.add(PyFmt.pf("<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"url(#shopglow)\"/>",
                            (City.pyMinOf(xs) + City.pyMaxOf(xs)) / 2, City.pyMaxOf(ys), (City.pyMaxOf(xs) - City.pyMinOf(xs)) * 0.9, (City.pyMaxOf(ys) - City.pyMinOf(ys)) * 1.1))
                    }
                }
                val (s2, _) = Living.facePath(cam, Living.quadAt(a, bb, 3.1, 3.7, uc - half, uc + half, 0.07, nrm), if (!night) "#1E2A36" else "#0B0F16")
                if (s2 != null) out.add(s2)
            }
            // windows from levels
            if (near && elen > 2.5) {
                val wcols = if (!glass) Math.max(1, PyMath.int((elen - 1) / 2.8).toInt()) else Math.max(1, PyMath.int(elen / 1.6).toInt())
                for (r in 0 until Math.min(lv, 30)) {
                    if (r == 0 && shops.isNotEmpty()) continue
                    val ya = r * fh + fh * 0.28; val yb = ya + fh * (if (!glass) 0.5 else 0.9)
                    if (yb > h - 0.4) break
                    for (ci in 0 until wcols) {
                        val u0 = (0.5 + ci * (elen - 1) / wcols + 0.35) / elen
                        val u1 = u0 + ((elen - 1) / wcols - 0.7) / elen
                        if (u1 <= u0) continue
                        if (night) {
                            if (rgb.random() < 0.42) {
                                val col = rgb.choice(LIT_ROOMS)
                                val (s, _) = Living.facePath(cam, Living.quadAt(a, bb, ya, yb, u0, u1, 0.04, nrm), col, PyFmt.pf(" opacity=\"%.2f\"", rgb.choice(ROOM_OPS)))
                                if (s != null) out.add(s)
                            }
                            continue
                        }
                        if (glass) {
                            val (s, _) = Living.facePath(cam, Living.quadAt(a, bb, r * fh, r * fh + fh, u0 - 0.006, u0, 0.04, nrm), "#E9EEF4", " opacity=\".55\"")
                            if (s != null) out.add(s)
                            continue
                        }
                        val (s, _) = Living.facePath(cam, Living.quadAt(a, bb, ya - 0.12, yb + 0.12, u0 - 0.012, u1 + 0.012, 0.03, nrm), th.str("frame"), " opacity=\".9\"")
                        if (s != null) out.add(s)
                        val wl = facing && sh.lit(V3(a[0] + ex * (u0 + u1) / 2 + nrm[0] * 0.2, (ya + yb) / 2, a[1] + ez * (u0 + u1) / 2 + nrm[2] * 0.2), skip = b)
                        val (s2, _) = Living.facePath(cam, Living.quadAt(a, bb, ya, yb, u0, u1, 0.04, nrm), if (wl) th.str("win_sun") else th.str("win"))
                        if (s2 != null) out.add(s2)
                    }
                }
                // cornice on old brick
                if (b.mat == "brick" && (b.year ?: 1900) < 1945) {
                    val (s, _) = Living.facePath(cam, Living.quadAt(a, bb, h - 0.6, h, -0.02, 1.02, 0.3, nrm), City.tohex(City.mulc(Living.light(th, base, V3(0.0, 0.4, 0.0), !night), 1.06)))
                    if (s != null) out.add(s)
                }
                // fire escapes: guessed, old walk-ups, street side
                if ((b.year ?: 2000) < 1940 && lv >= 4 && elen > 6 && near && b.front == i) {
                    val iron = if (!night) "#26272B" else "#0B0C10"
                    val segs = StringBuilder()
                    for (r in 1 until lv) {
                        val y = r * fh + 0.2
                        val pa = Living.quadAt(a, bb, y, y, 0.18, 0.62, 1.1, nrm); val pb = Living.quadAt(a, bb, y + 1.0, y + 1.0, 0.18, 0.62, 1.1, nrm)
                        val p1 = cam.proj(pa[0]); val p2 = cam.proj(pa[1]); val p3 = cam.proj(pb[0]); val p4 = cam.proj(pb[1])
                        if (p1 != null && p2 != null && p3 != null && p4 != null) {
                            segs.append(PyFmt.pf("M%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1f",
                                p1[0], p1[1], p2[0], p2[1], p3[0], p3[1], p4[0], p4[1], p1[0], p1[1], p3[0], p3[1], p2[0], p2[1], p4[0], p4[1]))
                        }
                        val la = Living.quadAt(a, bb, y, y + fh, if (r % 2 != 0) 0.23 else 0.5, if (r % 2 != 0) 0.5 else 0.23, 0.6, nrm)
                        val q1 = cam.proj(la[0]); val q2 = cam.proj(la[2])
                        if (q1 != null && q2 != null) segs.append(PyFmt.pf("M%.1f %.1fL%.1f %.1f", q1[0], q1[1], q2[0], q2[1]))
                    }
                    if (segs.isNotEmpty()) out.add(PyFmt.pf("<path d=\"%s\" stroke=\"%s\" stroke-width=\"%.2f\" fill=\"none\" opacity=\".85\"/>", segs.toString(), iron, PyMath.max(0.5, 60 / d)))
                }
            }
        }
        // roof
        if (cam.C[1] > h) {
            val rc = b.tags["roof:colour"] as String?
            val rcol = if (!truth) City.hx(if (!rc.isNullOrEmpty()) rc else ROOFS[Math.floorMod(b.id, ROOFS.size.toLong()).toInt()]) else City.hx("#F4F1EA")
            val cen = centroid(poly)
            val litR = (!night) && sh.lit(V3(cen[0], h + 0.05, cen[1]), skip = b)
            var s = Living.facePath(cam, poly.map { V3(it.x, h, it.y) },
                City.tohex(City.mulc(Living.light(th, City.mix(rcol, City.hx(th.str("roof_sun")), if (litR) 0.2 else 0.0), V3(0.0, 1.0, 0.0), litR), sc.roofK)),
                PyFmt.pf(" stroke=\"%s\" stroke-width=\".5\"", if (truth) "#9A958C" else City.tohex(City.mulc(rcol, 0.8)))).first
            if (s != null) out.add(s)
            if (!truth && near) {
                // parapet line just inside the edge
                val (cx, cz) = centroid(poly)
                val inset = poly.map { V2(it.x + (cx - it.x) * 0.06, it.y + (cz - it.y) * 0.06) }
                s = Living.facePath(cam, inset.map { V3(it.x, h + 0.01, it.y) }, "none", PyFmt.pf(" stroke=\"%s\" stroke-width=\".6\" opacity=\".6\"", City.tohex(City.mulc(rcol, 0.72)))).first
                if (s != null) out.add(s)
                val ar = Math.abs(signedArea(poly))
                val rr = PyRandom(b.id)
                var units = if (lv >= 5) 1 else 0
                if (ar > 700) units += 2
                if (ar > 2000) units += 2
                for (k in 0 until units) {
                    val ux = rr.uniform(0.25, 0.75); val uz = rr.uniform(0.25, 0.75)
                    val xs = poly.map { it.x }; val zs = poly.map { it.y }
                    val px = City.pyMinOf(xs) + (City.pyMaxOf(xs) - City.pyMinOf(xs)) * ux; val pz = City.pyMinOf(zs) + (City.pyMaxOf(zs) - City.pyMinOf(zs)) * uz
                    if (!pip(px, pz, poly)) continue
                    val w = rr.uniform(1.6, 3.2); val dd = rr.uniform(1.6, 3.2); val hh = rr.uniform(1.0, 2.6)
                    out.addAll(Living.box(sc, px - w / 2, px + w / 2, h, h + hh, pz - dd / 2, pz + dd / 2,
                        if (k == 0 && lv >= 5) City.mix(City.mulc(base, 0.8), City.hx("#8C8A86"), 0.6) else City.hx("#B9BCC0")))
                }
            }
        }
        return out
    }

    // render/real.py:742 street_bearing
    /** The compass bearing of a named street, from its south end to its north end. */
    fun streetBearing(S: RealData, name: String): Double {
        val pts = S.roads.filter { it.tags["name"] == name }.flatMap { it.pts }
        var a = pts[0]; var b = pts[0]
        for (p in pts) {
            if (p[1] < a[1]) a = p
            if (p[1] > b[1]) b = p
        }
        return PyMath.mod(PyMath.degrees(PyMath.atan2(a[0] - b[0], -(a[1] - b[1]))), 360.0)
    }

    private val DROP = listOf("name", "brand", "brand:wikidata", "brand:wikipedia", "website", "phone", "contact:", "addr:", "operator", "wikidata", "wikipedia",
        "check_date", "survey", "source", "email", "image", "official_name", "alt_name", "old_name")

    private fun clean(t: Map<String, Any?>): Dict {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in t) if (DROP.none { k.startsWith(it) }) out[k] = v
        return out
    }

    // render/real.py:778 __main__ (the anchors and stats)
    /**
     * The callout anchors real.py's `__main__` picks for camera [cfg] (one shop, subway, crossing, tree, building, hydrant,
     * bikes, lane and bench, each nearest its spot on screen and at least 62 px from the others), and the data stats.
     */
    fun mainAnchors(S: RealData, cfg: Map<String, Any?>): Dict {
        val cam = Camera(v3(cfg["target"]), cfg.num("yaw"), cfg.num("pitch"), cfg.num("dist"), cfg.num("fov"), cfg.num("W"), cfg.num("H"),
            cx = cfg.numOrNull("cx"), cy = cfg.numOrNull("cy"))
        class Cand(val p3: V3, val tags: Map<String, Any?>, val info: Map<String, Any?>?)
        val cands = LinkedHashMap<String, ArrayList<Cand>>()
        fun add(k: String, c: Cand) = cands.getOrPut(k) { ArrayList() }.add(c)
        for (n in S.nodes) {
            val t = n.tags
            val kind = if (t["railway"] == "subway_entrance") "subway" else if (t["emergency"] == "fire_hydrant") "hydrant"
            else if (t["amenity"] == "bicycle_rental" || t["amenity"] == "bicycle_parking") "bikes"
            else if (t["amenity"] == "bench" || t["amenity"] == "waste_basket" || t["amenity"] == "drinking_water") "bench" else null
            if (kind != null) add(kind, Cand(V3(n.x, 1.0, n.z), clean(t), null))
        }
        for (b in S.buildings) {
            val (cx, cz) = centroid(b.poly)
            val tags = clean(b.tags)
            if (b.year != null && b.year != 0) tags["construction_year (NYC open data)"] = b.year.toString()
            add("building", Cand(V3(cx, b.h + 0.5, cz), tags, dictOf("h" to b.h, "year" to b.year, "levels" to b.levels, "guessed" to b.guessed.keys.toList())))
            for (s in b.shops) {
                val a = b.poly[s.edge]; val bb = b.poly[(s.edge + 1) % b.poly.size]
                val px = a[0] + (bb[0] - a[0]) * s.u; val pz = a[1] + (bb[1] - a[1]) * s.u
                val oh = s.tags["opening_hours"] as String?
                if (!oh.isNullOrEmpty()) {
                    add("shop", Cand(V3(px, 3.2, pz), clean(s.tags), dictOf(
                        "noon" to openAt(oh, "We", intArrayOf(12, 0)), "golden" to openAt(oh, "We", intArrayOf(17, 20)), "night" to openAt(oh, "We", intArrayOf(21, 30)),
                    )))
                }
            }
        }
        for (tr in S.trees) {
            if (tr.source == "census") {
                val (hgt, rad) = treeSize(tr)
                add("tree", Cand(V3(tr.x, hgt * 0.72, tr.z), dictOf("species" to tr.species, "common" to tr.common, "trunk_dbh_in" to PyMath.int(tr.dbh ?: 0.0).toString()),
                    dictOf("h" to PyMath.round(hgt), "crown" to PyMath.round(rad * 2))))
            }
        }
        for (c in S.crossings) {
            val t = c.tags
            if (t["crossing:markings"] == "zebra" || t["crossing:markings"] == "ladder") {
                val m = c.pts[c.pts.size / 2]
                add("crossing", Cand(V3(m[0], 0.1, m[1]), clean(t), null))
            }
        }
        for (r in S.roads) {
            val t = r.tags
            if (t["cycleway:left"] == "lane" || t["cycleway:right"] == "lane" || t["cycleway"] == "lane") {
                val m = r.pts[r.pts.size / 2]
                add("lane", Cand(V3(m[0], 0.1, m[1]), clean(t), null))
            }
        }
        val want = listOf("shop" to V2(330.0, 400.0), "subway" to V2(300.0, 520.0), "crossing" to V2(190.0, 520.0), "tree" to V2(110.0, 470.0),
            "building" to V2(160.0, 190.0), "hydrant" to V2(250.0, 330.0), "bikes" to V2(70.0, 600.0), "lane" to V2(120.0, 300.0), "bench" to V2(340.0, 250.0))
        val chosen = LinkedHashMap<String, Any?>()
        val chosenAt = ArrayList<List<Double>>()
        val W = cfg.num("W")
        for ((kind, spot) in want) {
            var bestD = 0.0; var bestQ: V2? = null; var bestC: Cand? = null
            for (c in cands[kind] ?: emptyList<Cand>()) {
                val q = cam.proj(c.p3)
                if (q == null || !(34 < q[0] && q[0] < W - 34 && 130 < q[1] && q[1] < 628)) continue
                if (chosenAt.any { at -> PyMath.hypot(q[0] - at[0], q[1] - at[1]) < 62 }) continue
                val dd = PyMath.hypot(q[0] - spot[0], q[1] - spot[1])
                if (bestQ == null || dd < bestD) {
                    bestD = dd; bestQ = q; bestC = c
                }
            }
            if (bestQ != null) {
                val at = listOf(PyMath.round(bestQ[0], 1), PyMath.round(bestQ[1], 1))
                chosen[kind] = dictOf("at" to at, "tags" to bestC!!.tags, "info" to bestC.info)
                chosenAt.add(at)
            }
        }
        val st = dictOf(
            "buildings" to S.buildings.size, "height" to S.buildings.count { it.guessed["height"] != true }, "year" to S.buildings.count { it.year != null && it.year != 0 },
            "colour" to S.buildings.count { it.guessed["colour"] != true }, "levels" to S.buildings.count { it.guessed["levels"] != true },
            "trees_osm" to S.trees.count { it.source == "osm" }, "trees_census" to S.trees.count { it.source == "census" },
            "shops" to S.pois.size, "shops_hours" to S.pois.count { "opening_hours" in it.tags },
            "crossings" to S.crossings.count { it.tags["crossing:markings"] != null && it.tags["crossing:markings"] != "no" },
            "lamps" to S.nodes.count { it.tags["highway"] == "street_lamp" }, "racks" to S.nodes.count { it.tags["amenity"] == "bicycle_parking" },
            "benches" to S.nodes.count { it.tags["amenity"] == "bench" }, "hydrants" to S.nodes.count { it.tags["emergency"] == "fire_hydrant" },
        )
        return dictOf("anchors" to chosen, "stats" to st)
    }
}

/** Python's `re` and `str` behaviour where Java's differs: whitespace, Unicode digits, `strip`, `split`. */
object PyRe {
    /** Python's `\s` for str patterns (the characters `str.isspace()` accepts). */
    const val WS = "[\\t\\n\\x0B\\f\\r\\x1C-\\x1F \\x{85}\\x{A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}]"

    /** A pattern with Python's semantics: Unicode `\d` and `\b`, `.` excluding only newline. Write `\s` as [WS]. */
    fun compile(p: String): Pattern = Pattern.compile(p, Pattern.UNICODE_CHARACTER_CLASS or Pattern.UNIX_LINES)

    /** `str.isspace()` for one character. */
    fun isSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u0085', ' ', ' ', ' ', ' ', ' ', ' ', '　' -> true
        else -> c in '\u001C'..'\u001F' || c in ' '..' '
    }

    /** `s.strip()`. */
    fun strip(s: String): String {
        var a = 0; var b = s.length
        while (a < b && isSpace(s[a])) a++
        while (b > a && isSpace(s[b - 1])) b--
        return s.substring(a, b)
    }

    /** `re.split(p, s)` for a pattern without groups. */
    fun split(p: Pattern, s: String): List<String> {
        val out = ArrayList<String>()
        val m = p.matcher(s)
        var last = 0
        while (m.find()) {
            if (m.end() == m.start() && m.start() == last && last != 0) continue
            out.add(s.substring(last, m.start()))
            last = m.end()
        }
        out.add(s.substring(last))
        return out
    }

    /** Unicode decimal digits as ASCII, as Python's `int()` and `float()` read them. */
    fun asciiDigits(s: String): String {
        var plain = true
        for (c in s) if (c > '\u007f') plain = false
        if (plain) return s
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c > '\u007f' && Character.isDigit(c)) ('0' + Character.digit(c, 10)) else c)
        return sb.toString()
    }

    /** `int(s)` for a decimal string. */
    fun int(s: String): Int = asciiDigits(strip(s)).toInt()
}
