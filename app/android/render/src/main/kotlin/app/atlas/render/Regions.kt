// render/regions.py: beyond Amsterdam, the same renderer and the same rules (mapped tags only, generic shapes marked) for other
// landscapes: airports, Paris, the port, the dunes, hill towns, a theme park, a zoo. Each region is loaded into the living map's
// scene (amsterdam.py's S) and drawn by Amsterdam.render. Ported line by line; data/regions/places.py is the resource
// regions/places.json.
package app.atlas.render

import app.atlas.render.City.P
import app.atlas.render.City.hx
import app.atlas.render.City.mix
import app.atlas.render.City.mulc
import app.atlas.render.City.ribbon
import app.atlas.render.City.tohex
import app.atlas.render.City.vdot
import app.atlas.render.City.vsub
import app.atlas.render.Real.centroid
import app.atlas.render.Real.pip
import app.atlas.render.Real.signedArea
import app.atlas.render.data.RawSource
import app.atlas.render.live.WorldTape
import app.atlas.render.py.CrMath
import app.atlas.render.py.Dict
import app.atlas.render.py.PyFmt.pf
import app.atlas.render.py.PyJson
import app.atlas.render.py.PyMath
import app.atlas.render.py.dictOf
import app.atlas.render.py.pyNum
import app.atlas.render.py.pySortBy
import app.atlas.render.py.truthy
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections
import java.util.IdentityHashMap

/** render/regions.py. */
@Suppress("UNCHECKED_CAST")
object Regions {
    // ------------------------------------------------------------------ where the data is (regions.py: ROOT and its neighbours)
    /** `data/regions`: one folder per place (raw.json, region.json, wx.json, dem.json, dem5.json, sea.json). */
    var root: File = File("data/regions")

    /** `data/park`: a theme park's ride profiles (the JSON files in `<park>/pf`). */
    var parkRoot: File = File("data/park")

    /**
     * A test's planting of a park's or a zoo's woods in place of woods.py's about the view loaded (null: the renders'
     * own): the engine plants them once for the region (rendertiles' PlantedWoods), which L1 gives the port to draw.
     */
    var woodsPlanting: ((Dict) -> List<Dict>)? = null

    /** `cache/render`: where the street renderer wrote its cameras (`<name>_cfg.json`), read by [cfgFor] for street-level views. */
    var streetCfgDir: File = File("cache/render")

    // ------------------------------------------------------------------ region set-up
    /** data/regions/places.py's PLACES, from the resource regions/places.json (tuples are lists). */
    val PLACES: Map<String, Dict> by lazy {
        PyJson.loads(Regions::class.java.getResourceAsStream("/regions/places.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Dict>
    }

    /** Every view's camera: '' is the overview (`<name>`), any other key renders as `<name>_<key>`. [cfgFor] adds aliases to it. */
    val CAMS: LinkedHashMap<String, Dict> by lazy {
        val m = LinkedHashMap<String, Dict>()
        for ((k, p) in PLACES) for ((v, c) in (p["views"] as Map<String, Dict>?) ?: emptyMap()) m[k + (if (v.isNotEmpty()) "_$v" else "")] = LinkedHashMap(c)
        m
    }

    /** Regions drawn on their real ground shape. */
    val TERRAIN = setOf("dunes", "sangimignano")

    /** Of those, towns: squares and parks in the ground, trees and lamps kept, every building down to the slope. */
    val TOWNS = setOf("sangimignano")

    /** Flat drawing, with the real relief shown as light and shade. */
    val RELIEF = setOf("port", "schiphol", "bergen")

    /** Metres: footprint detail below this is not visible at the zoom shown. */
    val GENERALISE: Map<String, Double> = linkedMapOf(
        "grachten" to 0.8, "grachten_home" to 0.7, "grachten_eat" to 0.6, "paris" to 0.9, "schiphol" to 0.6, "port" to 0.6, "dunes" to 0.5, "paris_close" to 0.3,
        "schiphol_close" to 0.4, "port_close" to 0.4, "paris_walk" to 0.0, "nottinghill_close" to 0.15, "sangimignano_close" to 0.2, "bergen_close" to 0.2,
    )

    /** Metres from the camera: power lines and their pylons are drawn whole up to NEAR, fade out by FAR (far views stay calm). */
    const val POWER_NEAR = 1200.0
    const val POWER_FAR = 2000.0

    /** Region-wide generic looks for guessed colours: old brick, plaster, new. */
    val PALETTE: Map<String, Triple<List<String>, List<String>, List<String>>> = linkedMapOf(
        "paris" to Triple(listOf("#D9CDB4", "#E2D7C1", "#D4C6AA", "#DDD2BC", "#CFC2A6"), listOf("#E8E0D0", "#EEE7D9"), listOf("#CFC8BC", "#C8C2B8", "#D8D2C6")),
        "schiphol" to Triple(listOf("#9C8E80", "#8F8780"), listOf("#E6E2DA"), listOf("#B8B6B1", "#A9A7A2", "#C4C2BD", "#9FA3A8", "#8E949A")),
        "port" to Triple(listOf("#9C8E80"), listOf("#E6E2DA"), listOf("#B8B6B1", "#A9A7A2", "#C4C2BD", "#9FA3A8", "#7E8C96", "#B9B1A2")),
    )

    // ------------------------------------------------------------------ small helpers
    /** Python `pts[::step]`. */
    fun <T> every(pts: List<T>, step: Int): List<T> {
        if (step == 1) return pts
        val out = ArrayList<T>((pts.size + step - 1) / step)
        var i = 0
        while (i < pts.size) { out.add(pts[i]); i += step }
        return out
    }

    /** `'M' + 'L'.join('%.1f %.1f' % r for r in run)`. */
    fun runD(run: List<V2>): String {
        val sb = StringBuilder(run.size * 14 + 1).append('M')
        for ((i, r) in run.withIndex()) {
            if (i > 0) sb.append('L')
            app.atlas.render.py.PyFmt.appendFixed(sb, r.x, 1).append(' ')
            app.atlas.render.py.PyFmt.appendFixed(sb, r.y, 1)
        }
        return sb.toString()
    }

    private fun d(v: Any?): Double = pyNum(v)
    private fun tagsOf(e: Map<String, Any?>): Map<String, Any?> = (e["tags"] as Map<String, Any?>?) ?: emptyMap()
    private fun dicts(v: Any?): MutableList<Dict> = v as MutableList<Dict>
    private fun rings(v: Any?): MutableList<List<V2>> = v as MutableList<List<V2>>
    private fun dist(a: V2, b: V2): Double = Amsterdam.dist(a, b)
    private fun hypot(x: Double, y: Double): Double = PyMath.hypot(x, y)
    private fun pmax(a: Double, b: Double): Double = PyMath.max(a, b)
    private fun pmin(a: Double, b: Double): Double = PyMath.min(a, b)
    private fun r1(x: Double): Double = PyMath.round(x, 1) + 0.0
    private fun absArea(r: List<V2>): Double = Math.abs(signedArea(r))
    private fun same(a: V2, b: V2): Boolean = a.x == b.x && a.y == b.y

    /** `re.match(r'\s*(\d{4})', s)`: the year a start_date begins with. */
    private fun leadingYear(s: String): Int? {
        var i = 0
        while (i < s.length && PyRe.isSpace(s[i])) i++
        if (i + 4 > s.length) return null
        for (k in i until i + 4) if (!Character.isDigit(s[k])) return null
        return PyRe.asciiDigits(s.substring(i, i + 4)).toInt()
    }

    /** `statistics.median`. */
    fun median(xs: List<Double>): Double {
        require(xs.isNotEmpty()) { "no median for empty data" }
        val s = xs.sortedWith { a, b -> java.lang.Double.compare(a + 0.0, b + 0.0) }
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    // ------------------------------------------------------------------ amsterdam.py's globals as a fresh process has them
    /**
     * Puts amsterdam.py's module globals back as they are at import: the place, the weather, the palettes, the water and quays, the
     * moments. regions.py runs one region per process; this lets one process load region after region.
     */
    fun resetPlace(daylight: Boolean = false) {
        Amsterdam.LAT0 = 52.3746; Amsterdam.LON0 = 4.8840
        Amsterdam.KX = 111320 * PyMath.cos(PyMath.radians(Amsterdam.LAT0)); Amsterdam.KZ = 110540.0
        Amsterdam.TZ_H = 2
        Amsterdam.WXD = (PyJson.loads(Amsterdam::class.java.getResourceAsStream("/wx/ams.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>)["hourly"] as Map<String, Any?>
        Amsterdam.BRICK_OLD.clear(); Amsterdam.BRICK_OLD.addAll(listOf("#6E3B2E", "#7A4434", "#5C3A30", "#8A5040", "#6A4A3C", "#7E5242"))
        Amsterdam.PLASTER.clear(); Amsterdam.PLASTER.addAll(listOf("#E8E2D4", "#EFE9DC", "#D9D2C2"))
        Amsterdam.BRICK_NEW.clear(); Amsterdam.BRICK_NEW.addAll(listOf("#9C6A52", "#8F7F74", "#B08A6A", "#8C8A86", "#A9876E"))
        Amsterdam.WATER_Y = -1.5; Amsterdam.QUAY_COL = "#6E5546"; Amsterdam.QUAY_COURSE = 0.0625; Amsterdam.TONE = false; Amsterdam.WIN_STYLE = null
        Amsterdam.resetThemes(daylight)
    }

    // render/regions.py:30 simplify
    /** Douglas-Peucker on a closed ring: a corner within [tol] metres of a straight wall is dropped. */
    fun simplify(pts: List<V2>, tol: Double): List<V2> {
        if (pts.size < 6) return pts
        fun dp(seg: List<V2>): List<V2> {
            if (seg.size < 3) return listOf(seg[0])
            val x0 = seg[0].x; val z0 = seg[0].y; val x1 = seg[seg.size - 1].x; val z1 = seg[seg.size - 1].y
            val dx = x1 - x0; val dz = z1 - z0; val L = hypot(dx, dz).let { if (it == 0.0) 1e-9 else it }
            var dmax = 0.0; var k = 0
            for (i in 1 until seg.size - 1) {
                val dd = Math.abs((seg[i].x - x0) * dz - (seg[i].y - z0) * dx) / L
                if (dd > dmax) { dmax = dd; k = i }
            }
            if (dmax <= tol) return listOf(seg[0])
            return dp(seg.subList(0, k + 1)) + dp(seg.subList(k, seg.size))
        }
        var iFar = 0; var best = dist(pts[0], pts[0])
        for (i in pts.indices) { val dd = dist(pts[0], pts[i]); if (dd > best) { best = dd; iFar = i } }
        val ring = dp(pts.subList(0, iFar + 1)) + dp(pts.subList(iFar, pts.size) + listOf(pts[0]))
        return if (ring.size >= 3 && Math.abs(signedArea(ring)) > 2) ring else pts
    }

    // render/regions.py:46 configure
    /** Moves the living map to a region: origin, weather, time zone, moments, palette. Returns region.json. */
    fun configure(name: String, daylight: Boolean = false): Dict {
        val R = PyJson.load(File(File(root, name), "region.json")) as Dict
        val origin = R["origin"] as List<Any?>
        Amsterdam.LAT0 = d(origin[0]); Amsterdam.LON0 = d(origin[1])
        // math.cos here is the platform libm; the correctly rounded value equals it for every place (StrictMath is a bit off at San Gimignano)
        Amsterdam.KX = 111320 * CrMath.cos(PyMath.radians(Amsterdam.LAT0))
        Amsterdam.WXD = (PyJson.load(File(File(root, name), "wx.json")) as Map<String, Any?>)["hourly"] as Map<String, Any?>
        val off = ZoneId.of((R["tz"] as String?) ?: "Europe/Amsterdam").rules.getOffset(LocalDateTime.of(2026, 10, 1, 12, 0)).totalSeconds
        Amsterdam.TZ_H = Math.floorDiv(off, 3600)
        Amsterdam.THEMES["grey"] = Amsterdam.weatherTheme("grey", listOf(2026, 10, 1), listOf(13, 30), daylight)
        Amsterdam.THEMES["sunny"] = Amsterdam.weatherTheme("sunny", listOf(2026, 10, 1), listOf(16, 0), daylight)
        Amsterdam.THEMES["wet"] = Amsterdam.weatherTheme("wet", listOf(2026, 9, 30), listOf(21, 30), daylight)
        for ((k, v) in (R["moments"] as Map<String, Any?>?) ?: emptyMap()) { // a region's own moments (Efteling: the recorded Saturday of 26 September, 14:00)
            val (d_, hm_) = v as List<List<Any?>>
            Amsterdam.THEMES[k] = Amsterdam.weatherTheme(k, d_.map { (it as Number).toInt() }, hm_.map { (it as Number).toInt() }, daylight)
        }
        val pal = PALETTE[name]
        if (pal != null) {
            Amsterdam.BRICK_OLD.clear(); Amsterdam.BRICK_OLD.addAll(pal.first)
            Amsterdam.PLASTER.clear(); Amsterdam.PLASTER.addAll(pal.second)
            Amsterdam.BRICK_NEW.clear(); Amsterdam.BRICK_NEW.addAll(pal.third)
        }
        return R
    }

    // ------------------------------------------------------------------ geometry helpers
    // render/regions.py:63 ll
    private fun ll(g: Map<String, Any?>): V2 = Amsterdam.XZ(d(g["lat"]), d(g["lon"]))

    private fun geomOf(e: Map<String, Any?>): List<Map<String, Any?>?>? = e["geometry"] as List<Map<String, Any?>?>?

    /** A (lat, lon) pair, compared as Python compares tuples of floats. */
    private class LL(val lat: Double, val lon: Double) {
        override fun equals(other: Any?): Boolean = other is LL && other.lat == lat && other.lon == lon
        override fun hashCode(): Int = (lat + 0.0).hashCode() * 31 + (lon + 0.0).hashCode()
    }

    // render/regions.py:65 join_rings
    /** Outer or inner member ways of a multipolygon, joined end to end into closed rings of (lat, lon). */
    private fun joinRings(ways: List<List<Map<String, Any?>?>?>): List<List<LL>> {
        val segs = ArrayList<List<LL>>()
        for (w in ways) {
            if (w.isNullOrEmpty()) continue
            val s = w.filter { truthy(it) }.map { LL(d(it!!["lat"]), d(it["lon"])) }
            if (s.size > 1) segs.add(s)
        }
        val rings = ArrayList<List<LL>>()
        while (segs.isNotEmpty()) {
            var cur = segs.removeAt(0)
            var changed = true
            while (cur[0] != cur[cur.size - 1] && changed) {
                changed = false
                for ((i, s) in segs.withIndex()) {
                    cur = if (s[0] == cur[cur.size - 1]) cur + s.subList(1, s.size)
                    else if (s[s.size - 1] == cur[cur.size - 1]) cur + s.asReversed().subList(1, s.size)
                    else if (s[s.size - 1] == cur[0]) s + cur.subList(1, cur.size)
                    else if (s[0] == cur[0]) s.asReversed() + cur.subList(1, cur.size)
                    else continue
                    segs.removeAt(i); changed = true; break
                }
            }
            if (cur.size > 3 && cur[0] == cur[cur.size - 1]) rings.add(cur)
        }
        return rings
    }

    // render/regions.py:85 rings_of
    /** A closed way, or a relation's outer (or inner) rings, in local metres. */
    fun ringsOf(e: Map<String, Any?>, role: String = "outer"): List<List<V2>> {
        if (e["type"] == "way" && truthy(e["geometry"])) {
            val g = geomOf(e)!!
            if (g.size > 3 && d(g[0]!!["lat"]) == d(g[g.size - 1]!!["lat"]) && d(g[0]!!["lon"]) == d(g[g.size - 1]!!["lon"])) return listOf(g.map { ll(it!!) })
            return emptyList()
        }
        if (e["type"] == "relation") {
            val roles = if (role == "inner") setOf("inner") else setOf("outer", "")
            val ways = ((e["members"] as List<Map<String, Any?>>?) ?: emptyList()).filter { it["type"] == "way" && ((it["role"] ?: "outer") as String) in roles }
                .map { it["geometry"] as List<Map<String, Any?>?>? }
            return joinRings(ways).map { r -> r.map { Amsterdam.XZ(it.lat, it.lon) } }
        }
        return emptyList()
    }

    // render/regions.py:95 lines_of
    fun linesOf(e: Map<String, Any?>): List<List<V2>> {
        if (e["type"] == "way" && truthy(e["geometry"])) return listOf(geomOf(e)!!.filter { truthy(it) }.map { ll(it!!) })
        if (e["type"] == "relation") return ((e["members"] as List<Map<String, Any?>>?) ?: emptyList()).filter { it["type"] == "way" && truthy(it["geometry"]) }
            .map { m -> (m["geometry"] as List<Map<String, Any?>?>).filter { truthy(it) }.map { ll(it!!) } }
        return emptyList()
    }

    private class Hit(val t: Double, val p: LL)

    // render/regions.py:100 sea_from_coast
    /** OSM maps the sea as its coastline (land on the left). Closes the coastline along the edge of the extract into water polygons. */
    fun seaFromCoast(coastWays: List<List<Map<String, Any?>?>>, bbox: List<Double>): List<List<V2>> {
        val s = bbox[0] - 0.06; val w = bbox[1] - 0.1; val n = bbox[2] + 0.06; val e = bbox[3] + 0.1 // well beyond the view
        var segs = joinOpen(coastWays.map { c -> c.filter { truthy(it) }.map { LL(d(it!!["lat"]), d(it["lon"])) } })
        fun comp(p: LL, k: Int): Double = if (k == 0) p.lat else p.lon
        fun extend(p: LL, q: LL): LL? {
            // from p, straight on away from q, until the box edge
            val dla = p.lat - q.lat; val dlo = p.lon - q.lon; var best: Hit? = null
            for ((k, lim) in listOf(0 to s, 0 to n, 1 to w, 1 to e)) {
                val dk = if (k == 0) dla else dlo
                if (Math.abs(dk) < 1e-12) continue
                val t = (lim - comp(p, k)) / dk
                if (t <= 0) continue
                val r = LL(p.lat + dla * t, p.lon + dlo * t)
                if (s - 1e-9 <= r.lat && r.lat <= n + 1e-9 && w - 1e-9 <= r.lon && r.lon <= e + 1e-9 && (best == null || t < best.t)) best = Hit(t, r)
            }
            return best?.p
        }
        val out0 = ArrayList<List<LL>>()
        for (c0 in segs) {
            var c = c0
            if (c.size > 1 && c[0] != c[c.size - 1]) {
                if (s < c[0].lat && c[0].lat < n && w < c[0].lon && c[0].lon < e) {
                    val r = extend(c[0], c[1]); c = (if (r != null) listOf(r) else emptyList()) + c
                }
                if (s < c[c.size - 1].lat && c[c.size - 1].lat < n && w < c[c.size - 1].lon && c[c.size - 1].lon < e) {
                    val r = extend(c[c.size - 1], c[c.size - 2]); c = c + (if (r != null) listOf(r) else emptyList())
                }
            }
            out0.add(c)
        }
        segs = out0
        // clip every chain to the box, keeping the pieces that run inside it
        fun inside(p: LL): Boolean = s <= p.lat && p.lat <= n && w <= p.lon && p.lon <= e
        fun cross(a: LL, b: LL): LL? {
            var best: Hit? = null
            for ((k, lim) in listOf(0 to s, 0 to n, 1 to w, 1 to e)) {
                if ((comp(a, k) - lim) * (comp(b, k) - lim) < 0) {
                    val t = (lim - comp(a, k)) / (comp(b, k) - comp(a, k)); val q = LL(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                    val qc = LL(pmin(pmax(q.lat, s), n), pmin(pmax(q.lon, w), e))
                    if (inside(qc) && (best == null || t < best.t)) best = Hit(t, LL(pmin(pmax(q.lat, s), n), pmin(pmax(q.lon, w), e)))
                }
            }
            return best?.p
        }
        val pieces0 = ArrayList<List<LL>>()
        for (c in segs) {
            var cur = ArrayList<LL>()
            for (i in c.indices) {
                val p = c[i]
                if (inside(p)) {
                    if (cur.isEmpty() && i > 0) { val q = cross(c[i - 1], p); if (q != null) cur.add(q) }
                    cur.add(p)
                } else if (cur.isNotEmpty()) {
                    val q = cross(c[i - 1], p)
                    if (q != null) cur.add(q)
                    pieces0.add(cur); cur = ArrayList()
                }
            }
            if (cur.isNotEmpty()) pieces0.add(cur)
        }
        val pieces = pieces0.filter { it.size > 1 }
        if (pieces.isEmpty()) return emptyList()
        // walk the box edge clockwise (lat up, lon right) from each exit to the next entry: water stays on the right
        fun perim(p: LL): Double { // clockwise position along the box edge, starting at the north-west corner
            val la = p.lat; val lo = p.lon; val W_ = e - w; val H_ = n - s
            if (Math.abs(la - n) < 1e-9) return lo - w
            if (Math.abs(lo - e) < 1e-9) return W_ + (n - la)
            if (Math.abs(la - s) < 1e-9) return W_ + H_ + (e - lo)
            return 2 * W_ + H_ + (la - s)
        }
        val total = 2 * ((e - w) + (n - s))
        val cpos = listOf(LL(n, w), LL(n, e), LL(s, e), LL(s, w)).map { perim(it) to it }
        val openP = pieces.filter { !(it.size > 3 && it[0] == it[it.size - 1]) }
        val polys = ArrayList<List<V2>>()
        val used = BooleanArray(openP.size)
        for (i0 in openP.indices) {
            if (used[i0]) continue
            val poly = ArrayList<LL>(); var i = i0
            for (q in 0 until openP.size + 1) {
                used[i] = true; poly.addAll(openP[i])
                val x = perim(openP[i][openP[i].size - 1])
                // the next entry clockwise from this exit
                var dj = 0.0; var j = -1
                for (jj in openP.indices) {
                    val dd = PyMath.mod(perim(openP[jj][0]) - x, total)
                    if (j < 0 || dd < dj) { dj = dd; j = jj }
                }
                val sortedC = cpos.toMutableList(); sortedC.pySortBy { PyMath.mod(it.first - x, total) }
                for ((cp, c) in sortedC) if (PyMath.mod(cp - x, total) < dj) poly.add(c)
                if (j == i0) break
                i = j
            }
            if (poly.size > 2) polys.add(poly.map { Amsterdam.XZ(it.lat, it.lon) })
        }
        return polys
    }

    // render/regions.py:177 join_open
    private fun joinOpen(chains0: List<List<LL>>): List<List<LL>> {
        val chains = chains0.filter { it.size > 1 }.toMutableList(); val out = ArrayList<List<LL>>()
        while (chains.isNotEmpty()) {
            var cur = chains.removeAt(0); var changed = true
            while (changed) {
                changed = false
                for ((i, c) in chains.withIndex()) {
                    cur = if (c[0] == cur[cur.size - 1]) cur + c.subList(1, c.size)
                    else if (c[c.size - 1] == cur[0]) c + cur.subList(1, cur.size)
                    else continue
                    chains.removeAt(i); changed = true; break
                }
            }
            out.add(cur)
        }
        return out
    }

    // ------------------------------------------------------------------ land cover
    /** kind: (colour, draw order). Generic tones for mapped land cover; the shape is the mapped shape. */
    val LAND: LinkedHashMap<String, Pair<String, Int>> = linkedMapOf(
        "farmland" to ("#D8D4AC" to 0), "residential" to ("#E4DFD6" to 0), "industrial" to ("#D3CEC6" to 0), "port" to ("#C3C1BB" to 1), "commercial" to ("#E0D8D0" to 0),
        "retail" to ("#E4D7D0" to 0), "construction" to ("#D9CDB9" to 0), "railway" to ("#D2CCC3" to 0), "military" to ("#D6D2C6" to 0), "brownfield" to ("#D7CDBC" to 0),
        "greenfield" to ("#CBD3A8" to 0), "education" to ("#E2DBCF" to 0), "static_caravan" to ("#DCD5C8" to 1), "farmyard" to ("#D9CFB8" to 1),
        "meadow" to ("#C2D19B" to 1), "grass" to ("#B8CC96" to 2), "grassland" to ("#BFCF9C" to 2), "village_green" to ("#B8CC96" to 2), "recreation_ground" to ("#B8CC96" to 2),
        "park" to ("#B2CA90" to 2), "garden" to ("#B5CC92" to 2), "cemetery" to ("#B4C2A0" to 2), "allotments" to ("#C7CE9A" to 2), "orchard" to ("#B6C98C" to 2),
        "golf_course" to ("#B1CD8E" to 2), "heath" to ("#BCAB8C" to 2), "scrub" to ("#9CB084" to 3), "forest" to ("#7E9B6B" to 3), "wood" to ("#7E9B6B" to 3), "wetland" to ("#A6BBAB" to 3),
        "sand" to ("#E4D6B3" to 4), "beach" to ("#EDE1C1" to 4), "dune" to ("#E4D6B3" to 4), "bare_rock" to ("#B1ACA2" to 4), "stone" to ("#ADA89F" to 4), "scree" to ("#B5B0A6" to 4),
        "apron" to ("#C7C5C0" to 5), "parking" to ("#C9C6BE" to 5), "pedestrian" to ("#D6CDBE" to 5), "playground" to ("#D9B48A" to 5), "pitch" to ("#A3C28C" to 5), "track" to ("#C98F74" to 5), "flowerbed" to ("#B9C48F" to 5),
    )

    // render/regions.py:202 land_kind
    fun landKind(t: Map<String, Any?>): String? {
        if (t["aeroway"] == "apron") return "apron"
        if (t["amenity"] == "parking" && t["parking"] != "underground") return "parking"
        if (t["leisure"] in setOf("pitch", "park", "garden", "golf_course", "recreation_ground", "track")) return t["leisure"] as String
        if (t["industrial"] == "port" || t["landuse"] == "port") return "port" // a terminal's yard: paved
        if (t["natural"] in LAND) return t["natural"] as String
        if (t["landuse"] in LAND) return t["landuse"] as String
        return null
    }

    // ------------------------------------------------------------------ load
    // render/regions.py:212 base_of
    fun baseOf(view: String): String = view.split('_')[0]

    private fun pts(e: Map<String, Any?>): List<V2> = geomOf(e)!!.map { ll(it!!) }

    /** An identity set, as Python's set of `id(ring)`. */
    private fun <T> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())

    // render/regions.py:215 load
    /**
     * A region's scene for a view (`paris_close` is Paris, seen from its close camera): Amsterdam.load on its extract, then what only
     * regions draw (land cover, runways, big roads, rails, power, cranes, masts, turbines, the sea from its coastline, terrain or
     * relief from the survey, a park's coasters and woods), with `under`, `over` and `items` for Amsterdam.render.
     * [opts]: only `daylight` matters here (amsterdam.py's ATLAS_DAYLIGHT, read by its moments).
     */
    fun load(view: String, opts: RenderOptions = RenderOptions()): Dict {
        val name = baseOf(view) // 'paris_close' is Paris, seen from its close camera
        resetPlace(opts.daylight)
        val R = configure(name, opts.daylight)
        Amsterdam.WATER_Y = -1.5; Amsterdam.QUAY_COL = "#6E5546"; Amsterdam.QUAY_COURSE = 0.0625 // the generic levels, before this region measures its own
        val rawPath = File(File(root, name), "raw.json")
        val S = Amsterdam.load(rawPath)
        val raw = RawSource.elements(rawPath)
        val naturalShore = identitySet<Any?>()
        S["land"] = ArrayList<Dict>(); S["aero"] = ArrayList<Dict>(); S["big"] = ArrayList<Dict>(); S["rail"] = ArrayList<Dict>(); S["power"] = ArrayList<Dict>()
        S["towers"] = LinkedHashMap<Long, Pair<V2, Double?>>(); S["turbines"] = ArrayList<Dict>(); S["cranes"] = ArrayList<Dict>(); S["marine"] = ArrayList<Dict>(); S["park"] = ArrayList<Dict>()
        S["natural_shore"] = naturalShore; S["region"] = name; S["R"] = R; S["view"] = view
        S["tradition"] = R["tradition"] // the building tradition its unmapped looks come from (render/traditions)
        val buildings = dicts(S["buildings"]); val parts = dicts(S["parts"])
        relationBuildings(raw, buildings, parts)
        // roof shapes this renderer does not draw are drawn as the closest one it does (the tag itself is kept and shown)
        val CLOSEST = mapOf("gambrel" to "gabled", "dome" to "pyramidal", "onion" to "pyramidal", "round" to "gabled", "half-hipped" to "hipped", "saltbox" to "gabled", "cone" to "pyramidal")
        for (b in buildings + parts) {
            val roof = b["roof"]
            if (roof in CLOSEST || roof == "mansard") { // a mansard is drawn as itself
                b["roof"] = CLOSEST[roof] ?: roof
                if (!truthy(b["roof_h"]) && truthy(b["h"])) {
                    val rl = Real.num(tagsOf(b)["roof:levels"])
                    b["roof_h"] = pmin(d(b["h"]) * 0.35, if (rl != null && rl != 0.0) rl * 2.8 else 3.5)
                    (b.getOrPut("guessed") { LinkedHashMap<String, Any?>() } as Dict)["roof:height"] = true
                }
            }
        }
        val tol = GENERALISE[view] ?: GENERALISE[view.replace("_detail", "").replace("_kit", "")] ?: GENERALISE[name] ?: 0.0
        if (tol != 0.0) {
            for (b in buildings + parts) b["poly"] = simplify(Amsterdam.polyOf(b), tol)
            for (a in dicts(S["areas"])) a["poly"] = simplify(Amsterdam.polyOf(a), tol)
        }
        val coast = ArrayList<List<Map<String, Any?>?>>(); val bays = ArrayList<Map<String, Any?>>()
        val quays = name == "paris" || name == "port"
        val land = dicts(S["land"])
        regionFeatures(S, raw, name, land, coast)
        // rivers and lakes mapped as multipolygons: outer rings joined from their ways, islands kept as holes
        val water = rings(S["water"])
        val seen = HashSet<Triple<Double, Double, Int>>()
        for (r in water) seen.add(Triple(r1(r[0].x), r1(r[0].y), r.size))
        val holes = ArrayList<List<V2>>(); S["water_holes"] = holes
        for (e in raw) {
            val t = tagsOf(e)
            if (e["type"] == "relation" && (t["natural"] == "water" || t["waterway"] in setOf("riverbank", "dock", "canal"))) {
                for (r in ringsOf(e)) if (Triple(r1(r[0].x), r1(r[0].y), r.size) !in seen) water.add(r)
                holes.addAll(ringsOf(e, "inner"))
            }
            if (t["natural"] in setOf("bay", "strait") && e["type"] in setOf("relation", "way")) {
                bays.add(e) // a named piece of the sea: water by definition, used where the extract holds no coastline to close
            }
        }
        for (r in water) if (!quays) naturalShore.add(r)
        var seas_ = seaFromCoast(coast, (R["bbox"] as List<Any?>).map { d(it) })
        if (seas_.isNotEmpty() && buildings.isNotEmpty()) {
            // a coastline closed round the wrong side covers the town: no sea holds more than a few buildings
            val cents = every(buildings, Math.max(1, buildings.size / 400)).map { centroid(Amsterdam.polyOf(it)) }
            seas_ = seas_.filter { poly -> cents.count { c -> pip(c.x, c.y, poly) } <= pmax(2.0, 0.03 * cents.size) }
        }
        for (poly in seas_) {
            water.add(poly)
            if (name != "bergen") naturalShore.add(poly) // Bergen's harbour is quays all round
        }
        if (seas_.isEmpty() && bays.isNotEmpty()) {
            for (e in bays) for (r in ringsOf(e)) if (absArea(r) > 2000) water.add(r) // a harbour's edges are quays, drawn as such
        }
        if (name == "bergen") { Amsterdam.QUAY_COL = "#7F796E"; Amsterdam.QUAY_COURSE = 0.45 } // Bergen's quays are cut stone
        land.sortWith { a, b ->
            val oa = LAND.getValue(a["kind"] as String).second; val ob = LAND.getValue(b["kind"] as String).second
            if (oa != ob) oa.compareTo(ob) else app.atlas.render.py.pyCompare(-absArea(Amsterdam.polyOf(a)), -absArea(Amsterdam.polyOf(b)))
        }
        // each building's generic look from its own tags and the land use it stands in (real OSM values, marked guessed)
        class LU(val k: String, val poly: List<V2>, val bb: DoubleArray)
        val lu = land.filter { it["kind"] in Generic.LANDUSE_KIND }.map { a ->
            val p = Amsterdam.polyOf(a)
            LU(a["kind"] as String, p, doubleArrayOf(City.pyMinOf(p.map { it.x }), City.pyMaxOf(p.map { it.x }), City.pyMinOf(p.map { it.y }), City.pyMaxOf(p.map { it.y })))
        }
        val landuseAt: (Dict) -> String? = { b ->
            val c = centroid(Amsterdam.polyOf(b))
            lu.firstOrNull { it.bb[0] <= c.x && c.x <= it.bb[1] && it.bb[2] <= c.y && c.y <= it.bb[3] && pip(c.x, c.y, it.poly) }?.k
        }
        // each building:part belongs to the outline it stands in: it takes that building's look, and a church's parts get church windows
        val outl = buildings.map { b ->
            b to ((b["_bb"] as DoubleArray?) ?: Amsterdam.polyOf(b).let { p -> doubleArrayOf(City.pyMinOf(p.map { it.x }), City.pyMaxOf(p.map { it.x }), City.pyMinOf(p.map { it.y }), City.pyMaxOf(p.map { it.y })) })
        }
        for (p_ in parts) {
            val c = centroid(Amsterdam.polyOf(p_))
            for ((b, bb) in outl) {
                if (bb[0] <= c.x && c.x <= bb[1] && bb[2] <= c.y && c.y <= bb[3] && pip(c.x, c.y, Amsterdam.polyOf(b))) {
                    p_["parent_id"] = b["id"]
                    val bt = tagsOf(b)
                    if (bt["building"] in setOf("church", "cathedral", "chapel") || bt["amenity"] == "place_of_worship") p_["in_church"] = true
                    break
                }
            }
        }
        Generic.apply(S, name, landuseAt)
        val demPath = File(File(root, name), "dem.json")
        drapeTerrain(S, name, view, demPath, water, naturalShore, buildings, parts, land)
        if (truthy(S["_terrain"])) {
            S["under"] = { cam: City.Camera, th: Dict, far: (Double, Double) -> Boolean, defs: MutableList<String> ->
                Relief.draw(S, cam, th, far, defs, cfgFor(view), LAND, S["_lines"] as List<Terrain.Line>)
            } as Amsterdam.SceneLayer
        } else {
            S["under"] = { cam: City.Camera, th: Dict, far: (Double, Double) -> Boolean, defs: MutableList<String> -> drawUnder(S, cam, th, far, defs) } as Amsterdam.SceneLayer
        }
        flatHeights(S, name, view, demPath, water, naturalShore)
        Amsterdam.QUAY_COL = mapOf("paris" to "#B8AD96", "port" to "#8C8A85", "schiphol" to "#8C8A85")[name] ?: Amsterdam.QUAY_COL
        Amsterdam.QUAY_COURSE = when (name) { "paris" -> 0.45; "port", "schiphol" -> null; else -> Amsterdam.QUAY_COURSE } // Paris quays are cut stone
        Amsterdam.WIN_STYLE = if (name == "paris") "paris" else null
        Amsterdam.TONE = true
        val light = Woods.Light { th, b, n, l -> Amsterdam.light(th, b, n, l) }
        if (truthy(R["park"])) {
            // a theme park: its coaster tracks standing up, each ride's top height from its published profile (park/<name>/pf)
            val hts = LinkedHashMap<String, Double>(); val kinds = LinkedHashMap<String, String>()
            val files = File(File(parkRoot, R["park"] as String), "pf").listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()
            for (f in files) {
                val a_ = PyJson.load(f) as Map<String, Any?>; val rp = (a_["rideProfile"] as Map<String, Any?>?) ?: emptyMap()
                val stats = (rp["stats"] as Map<String, Any?>?) ?: emptyMap()
                if (truthy(stats["heightM"])) hts[a_["name"] as String] = pyFloat(stats["heightM"])
                if ("wooden-coaster" in ((rp["types"] as List<Any?>?) ?: emptyList())) kinds[a_["name"] as String] = "wood"
            }
            S["coasters"] = Coasters.load(raw, Amsterdam::XZ, buildings + parts, hts, kinds)
            val cv = cfgFor(view); val tg = cv["target"] as List<Any?>
            S["woods"] = woodsPlanting?.invoke(S) ?: Woods.plant(S, d(tg[0]), d(tg[2]), d(cv["radius"])) // its woods: generic trees inside the mapped outlines, off everything mapped in them
        }
        if (truthy(R["zoo"])) {
            // a zoo: its planted woods and shrubberies, the same generic trees inside the mapped outlines (the mapped trees stay as they are)
            val cv = cfgFor(view); val tg = cv["target"] as List<Any?>
            S["woods"] = woodsPlanting?.invoke(S) ?: Woods.plant(S, d(tg[0]), d(tg[2]), d(cv["radius"]))
        }
        S["over"] = { cam: City.Camera, th: Dict, far: (Double, Double) -> Boolean, defs: MutableList<String> -> drawOver(S, cam, th, far, defs, light) } as Amsterdam.SceneLayer
        S["items"] = { cam: City.Camera, th: Dict, far: (Double, Double) -> Boolean, defs: MutableList<String> -> drawItems(S, cam, th, far, defs) } as Amsterdam.SceneItems
        S["no_facades"] = true
        return S
    }

    // render/regions.py:215 load: buildings mapped as multipolygon relations (Notre-Dame, the Louvre), outer ways joined into rings
    private fun relationBuildings(raw: List<Map<String, Any?>>, buildings: MutableList<Dict>, parts: MutableList<Dict>) {
        val have = HashSet<V2>()
        for (b in buildings + parts) { val p0 = Amsterdam.polyOf(b)[0]; have.add(V2(r1(p0.x), r1(p0.y))) }
        val newB = ArrayList<Dict>(); val newP = ArrayList<Dict>()
        for (e in raw) {
            val t = tagsOf(e)
            if (e["type"] != "relation" || !(truthy(t["building"]) || truthy(t["building:part"])) || t["building"] == "no") continue
            for (r0 in ringsOf(e)) {
                var r = if (dist(r0[0], r0[r0.size - 1]) < 0.5) r0.subList(0, r0.size - 1).toList() else r0
                if (signedArea(r) < 0) r = r.asReversed().toList()
                if (absArea(r) < 3 || V2(r1(r[0].x), r1(r[0].y)) in have) continue
                var h = Real.num(t["height"]); val lv = Real.num(t["building:levels"])
                if (h == null && lv != null) h = lv * 3.0 + (Real.num(t["roof:levels"]) ?: 0.0) * 2.0
                val y = leadingYear((t["start_date"] as String?) ?: "")
                val rec: Dict = dictOf("poly" to r, "h" to h, "min" to Real.num(t["min_height"], 0.0), "src" to (if (truthy(t["height"])) "height" else (if (lv != null && lv != 0.0) "levels" else null)),
                    "year" to y, "tags" to t, "id" to e["id"], "roof" to t["roof:shape"], "roof_h" to Real.num(t["roof:height"]), "orient" to t["roof:orientation"],
                    "mat" to t["building:material"], "rmat" to t["roof:material"], "col" to t["building:colour"], "rcol" to t["roof:colour"])
                rec["otype"] = "relation"
                (if (truthy(t["building:part"])) newP else newB).add(rec)
            }
        }
        if (newB.isNotEmpty() || newP.isNotEmpty()) {
            Amsterdam.guessTags(dictOf("buildings" to newB, "parts" to newP, "trees" to ArrayList<Dict>()))
            for (b in newB) {
                val cb = centroid(Amsterdam.polyOf(b)); val bp = Amsterdam.polyOf(b)
                b["hidden"] = (parts + newP).any { p ->
                    val cp = centroid(Amsterdam.polyOf(p))
                    Math.abs(cp.x - cb.x) < 120 && Math.abs(cp.y - cb.y) < 120 && pip(cp.x, cp.y, bp)
                }
            }
            for (b in newB + newP) {
                val poly = Amsterdam.polyOf(b); val xs = poly.map { it.x }; val zs = poly.map { it.y }
                b["_bb"] = doubleArrayOf(City.pyMinOf(xs), City.pyMaxOf(xs), City.pyMinOf(zs), City.pyMaxOf(zs))
            }
            buildings.addAll(newB); parts.addAll(newP)
        }
    }

    // render/regions.py:215 load: what only regions draw, from the extract (land cover, runways, big roads, rails, power, marine works,
    // cranes, masts, gantries, turbines, towers, parks), and the coastline's ways
    @Suppress("UNCHECKED_CAST")
    private fun regionFeatures(S: Dict, raw: List<Map<String, Any?>>, name: String, land: MutableList<Dict>, coast: MutableList<List<Map<String, Any?>?>>) {
        for (e in raw) {
            val t = tagsOf(e)
            if (t.isEmpty()) continue
            val type = e["type"]; val geo = truthy(e["geometry"])
            if (t["natural"] == "coastline" && type == "way") { coast.add(geomOf(e)!!); continue }
            if (t["boundary"] == "national_park" || (t["boundary"] == "protected_area" && name == "dunes")) {
                for (r in ringsOf(e)) dicts(S["park"]).add(dictOf("ring" to r, "tags" to t))
                continue
            }
            val k = landKind(t)
            if (k != null && type != "node" && !truthy(t["building"])) {
                for (r in ringsOf(e)) if (absArea(r) > 4) land.add(dictOf("poly" to r, "kind" to k, "tags" to t, "id" to e["id"]))
                continue
            }
            if (type == "way" && t["aeroway"] in setOf("runway", "taxiway", "taxilane", "parking_position", "stopway") && geo) {
                dicts(S["aero"]).add(dictOf("pts" to pts(e), "kind" to t["aeroway"], "tags" to t, "id" to e["id"])); continue
            }
            if (type == "way" && t["highway"] in setOf("motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary_link") && geo) {
                if (t["tunnel"] in setOf("yes", "building_passage") || (Real.num(t["layer"]) ?: 0.0) < 0) continue // underground: not drawn
                dicts(S["big"]).add(dictOf("pts" to pts(e), "tags" to t, "hw" to t["highway"], "layer" to (Real.num(t["layer"]) ?: 0), "id" to e["id"])); continue
            }
            if (type == "way" && (t["railway"] in setOf("rail", "light_rail", "subway", "narrow_gauge") || t["man_made"] == "crane_rail" || t["railway"] == "crane") && geo) {
                if (t["tunnel"] in setOf("yes", "building_passage") || (Real.num(t["layer"]) ?: 0.0) < 0) continue
                dicts(S["rail"]).add(dictOf("pts" to pts(e), "tags" to t, "crane" to (t["man_made"] == "crane_rail" || t["railway"] == "crane"))); continue
            }
            if (type == "way" && t["power"] in setOf("line", "minor_line") && geo) {
                dicts(S["power"]).add(dictOf("pts" to pts(e), "tags" to t)); continue
            }
            if (t["man_made"] in setOf("breakwater", "groyne", "pier", "quay") && type == "way" && geo) {
                val g = geomOf(e)!!
                dicts(S["marine"]).add(dictOf("pts" to pts(e), "kind" to t["man_made"], "tags" to t, "closed" to (g[0] == g[g.size - 1]))); continue
            }
            if (t["man_made"] == "crane") {
                if (type == "node") {
                    val xz = Amsterdam.XZ(d(e["lat"]), d(e["lon"]))
                    dicts(S["cranes"]).add(dictOf("x" to xz.x, "z" to xz.y, "tags" to t, "id" to e["id"], "poly" to null))
                } else if (geo) {
                    val p = pts(e)
                    dicts(S["cranes"]).add(dictOf("x" to PyMath.sumOf(p) { it.x } / p.size, "z" to PyMath.sumOf(p) { it.y } / p.size, "tags" to t, "id" to e["id"], "poly" to p))
                }
                continue
            }
            if (t["man_made"] == "mast" && type == "node") {
                val xz = Amsterdam.XZ(d(e["lat"]), d(e["lon"]))
                (S.getOrPut("masts") { ArrayList<Dict>() } as MutableList<Dict>).add(dictOf("x" to xz.x, "z" to xz.y, "tags" to t, "id" to e["id"], "h" to Real.num(t["height"], null))); continue
            }
            if (t["man_made"] in setOf("gantry", "goods_conveyor") && type == "way" && geo && t["location"] !in setOf("underground", "underwater")) {
                (S.getOrPut(if (t["man_made"] == "gantry") "gantries" else "conveyors") { ArrayList<Dict>() } as MutableList<Dict>)
                    .add(dictOf("pts" to pts(e), "tags" to t, "id" to e["id"], "h" to Real.num(t["height"], null))); continue
            }
            if (type == "node" && t["power"] == "generator" && t["generator:source"] == "wind") {
                val xz = Amsterdam.XZ(d(e["lat"]), d(e["lon"]))
                dicts(S["turbines"]).add(dictOf("x" to xz.x, "z" to xz.y, "tags" to t, "id" to e["id"], "h" to Real.num(t["height"], null), "rotor" to Real.num(t["rotor:diameter"], null))); continue
            }
            if (type == "node" && t["man_made"] == "tower" && truthy(Real.num(t["height"]))) {
                val xz = Amsterdam.XZ(d(e["lat"]), d(e["lon"]))
                (S.getOrPut("mtowers") { ArrayList<Dict>() } as MutableList<Dict>).add(dictOf("x" to xz.x, "z" to xz.y, "h" to Real.num(t["height"]), "tags" to t, "id" to e["id"])); continue
            }
            if (type == "node" && t["power"] in setOf("tower", "pole")) {
                (S["towers"] as MutableMap<Long, Pair<V2, Double?>>)[(e["id"] as Number).toLong()] = Amsterdam.XZ(d(e["lat"]), d(e["lon"])) to Real.num(t["height"], null)
            }
        }
    }

    // render/regions.py:215 load: the ground's real shape (AHN, not OSM), with land cover, water and paths draped over it
    @Suppress("UNCHECKED_CAST")
    private fun drapeTerrain(S: Dict, name: String, view: String, demPath: File, water: List<List<V2>>, naturalShore: Set<Any?>, buildings: List<Dict>,
                             parts: List<Dict>, land: MutableList<Dict>) {
        if (name in TERRAIN && demPath.exists()) {
            // the ground's real shape (AHN, not OSM): land cover, water and paths draped over it, buildings standing at their ground height
            val fine = File(File(root, name), "dem5.json")
            val dem = Terrain.DEM(if (fine.exists()) fine else demPath); S["dem"] = dem
            val seas = water.filter { r -> naturalShore.contains(r) && absArea(r) > 5e6 }
            val lakes = water.filter { r -> seas.none { it === r } }
            S["_lakes"] = lakes; S["_seas"] = seas
            val town = name in TOWNS; S["_town"] = town
            var ext: DoubleArray? = null
            if (town) {
                val cv = cfgFor(view); val tg = cv["target"] as List<Any?>; val TX_ = d(tg[0]); val TZ_ = d(tg[2]); val GR_ = d(cv["ground_r"] ?: 1500)
                ext = doubleArrayOf(TX_ - GR_, TX_ + GR_, TZ_ - GR_, TZ_ + GR_)
                val AK = mapOf("pedestrian" to "pedestrian", "square" to "pedestrian", "memorial" to "pedestrian", "bridge" to "pedestrian", "park" to "park", "garden" to "garden",
                    "grass" to "grass", "playground" to "playground", "parking" to "parking")
                for (a in dicts(S["areas"])) {
                    val ak = AK[a["kind"]]
                    if (ak != null && Amsterdam.polyOf(a).size >= 3) land.add(dictOf("poly" to a["poly"], "kind" to ak, "tags" to ((a["tags"] as Map<String, Any?>?)?.takeIf { it.isNotEmpty() } ?: LinkedHashMap<String, Any?>())))
                }
            }
            val grid = Relief.build(dem, lakes, seas, land, LAND, extent = ext) // heights filled under buildings and water, cover on a 2.5 m grid
            S["_grid"] = grid
            val LW: Map<String, Pair<Double, String>> = linkedMapOf(
                "motorway" to (12.0 to "#8E8C88"), "trunk" to (10.0 to "#8E8C88"), "primary" to (8.0 to "#9A9894"), "secondary" to (7.0 to "#A29F9A"), "tertiary" to (6.0 to "#ABA7A0"),
                "residential" to (5.0 to "#B9B4AA"), "unclassified" to (4.5 to "#B9B4AA"), "service" to (3.0 to "#C2BDB3"), "living_street" to (4.0 to "#C2BDB3"),
                "cycleway" to (2.4 to "#C98A7A"), "footway" to (1.6 to "#E3D6BC"), "path" to (1.4 to "#E3D6BC"), "track" to (2.5 to "#D7C7A8"), "bridleway" to (2.0 to "#D7C7A8"),
                "steps" to (1.6 to "#E3D6BC"), "pedestrian" to (3.0 to "#D8D1C4"),
            )
            val lines = ArrayList<Terrain.Line>()
            for (r in dicts(S["roads"])) {
                var (w, col) = LW[r["hw"]] ?: (2.0 to "#D8D1C4")
                val rt = tagsOf(r)
                if (town) col = Amsterdam.SURF[rt["surface"]] ?: col // a town's streets in their mapped surface
                lines.add(Terrain.Line(Amsterdam.ptsOf(r), if (r["wguess"] == false || town) d(r["w"]) else w, col, false, town && rt["lit"] == "yes"))
            }
            for (r in dicts(S["big"])) {
                val (w, col) = LW[(r["hw"] as String).replace("_link", "")] ?: (8.0 to "#9A9894"); lines.add(Terrain.Line(Amsterdam.ptsOf(r), w, col, false))
            }
            for (r in dicts(S["rail"])) lines.add(Terrain.Line(Amsterdam.ptsOf(r), 3.0, "#7A746C", false))
            for (p_ in dicts(S["park"])) lines.add(Terrain.Line(p_["ring"] as List<V2>, 3.0, "#2F6B3E", true))
            for (b in buildings + parts) {
                val poly = Amsterdam.polyOf(b); val c = centroid(poly)
                val base = grid.h(c.x, c.y)
                if (town && d(b["min"]) < 0.5) { // on a slope the walls reach down to the lowest corner, as a hill town's houses do
                    val low = City.pyMinOf(poly.map { grid.h(it.x, it.y) }); b["min"] = low - 0.4; b["_gy"] = low
                } else {
                    b["min"] = d(b["min"]) + base
                }
                b["h"] = (if (truthy(b["h"])) d(b["h"]) else 0.0) + base
            }
            S["_lines"] = lines
            if (town) for (n in dicts(S["trees"]) + dicts(S["nodes"])) n["gy"] = grid.h(d(n["x"]), d(n["z"]))
            S["areas"] = ArrayList<Dict>(); S["roads"] = ArrayList<Dict>(); S["water"] = ArrayList<List<V2>>(); S["big"] = ArrayList<Dict>(); S["rail"] = ArrayList<Dict>(); S["park"] = ArrayList<Dict>()
            if (!town) S["trees"] = ArrayList<Dict>()
            S["no_shadows"] = !town // a town's buildings and trees cast shadows on their own ground
            S["_terrain"] = true
        }
    }

    // render/regions.py:215 load: where the drawing stays flat, how far each water body lies below its banks, and the relief
    @Suppress("UNCHECKED_CAST")
    private fun flatHeights(S: Dict, name: String, view: String, demPath: File, water: List<List<V2>>, naturalShore: Set<Any?>) {
        if (demPath.exists() && !truthy(S["_terrain"])) {
            // the ground's real heights where the drawing stays flat (the streets are the drawing's level 0):
            // how far each water body lies below its banks, and the relief as light and shade
            val dem = Terrain.DEM(demPath); S["dem"] = dem
            val cfg = cfgFor(view); val tg = cfg["target"] as List<Any?>; val TX = d(tg[0]); val TZ = d(tg[2]); val RAD = d(cfg["radius"])
            val wat0 = rings(S["water"])
            fun banks(r: List<V2>, outD: DoubleArray = doubleArrayOf(30.0, 35.0, 40.0), inD: Double = 30.0): Pair<List<Double>, List<Double>> {
                val landV = ArrayList<Double>(); val wat = ArrayList<Double>()
                for (i in r.indices) {
                    val a = r[i]; val b = r[(i + 1) % r.size]
                    if (hypot(a[0] - TX, a[1] - TZ) > RAD * 1.2) continue
                    val ex = b[0] - a[0]; val ez = b[1] - a[1]; val Lg = hypot(ex, ez)
                    if (Lg < 3) continue
                    var nx = ez / Lg; var nz = -ex / Lg; val mx = (a[0] + b[0]) / 2; val mz = (a[1] + b[1]) / 2
                    if (!pip(mx + nx * 2, mz + nz * 2, r)) { nx = -nx; nz = -nz }
                    for (dd in outD) {
                        val v = dem.hn(mx - nx * dd, mz - nz * dd)
                        if (!v.isNaN() && !wat0.any { o -> o !== r && Math.abs(o[0][0] - mx) < 3000 && pip(mx - nx * dd, mz - nz * dd, o) }) landV.add(v)
                    }
                    val w = dem.hn(mx + nx * inD, mz + nz * inD)
                    if (!w.isNaN()) wat.add(w)
                }
                return landV to wat
            }
            val lv = IdentityHashMap<Any?, Any?>(); var info: Dict = LinkedHashMap()
            val big = wat0.filter { r -> !naturalShore.contains(r) && 1e5 < absArea(r) && absArea(r) < 5e7 }
            if (name == "paris") {
                // IGN surveys the river surface too: the Seine's level against the street level of its quays
                for (r in big) {
                    val (landV, wat) = banks(r)
                    if (landV.size > 20 && wat.size > 10) {
                        val wl = wat.sortedWith { a, b -> java.lang.Double.compare(a + 0.0, b + 0.0) }[wat.size / 10]; val ll_ = median(landV)
                        lv[r] = pmax(-9.0, pmin(-0.6, wl - ll_)); info = dictOf("water" to PyMath.round(wl, 1), "bank" to PyMath.round(ll_, 1))
                    }
                }
                if (lv.isNotEmpty()) Amsterdam.WATER_Y = City.pyMinOf(lv.values.map { d(it) })
            }
            if (name == "port") {
                // AHN has no ground over water: the quays' height from AHN, the water's from the real tide at each moment
                val landV = ArrayList<Double>()
                for (r in big) landV.addAll(banks(r).first)
                val sea = (PyJson.load(File(File(root, name), "sea.json")) as Map<String, Any?>)["hourly"] as Map<String, Any?>
                val ll_ = median(landV)
                fun tide(date: List<Int>, hhmm: List<Int>): Double {
                    val t0 = pf("%04d-%02d-%02dT%02d:00", date[0], date[1], date[2], hhmm[0]); val i = (sea["time"] as List<String>).indexOf(t0)
                    require(i >= 0) { "no sea level for $t0" }
                    val lvl = sea["sea_level_height_msl"] as List<Any?>
                    val v0 = d(lvl[i]); val v1 = d(lvl[i + 1])
                    return v0 + (v1 - v0) * hhmm[1] / 60
                }
                val tides = LinkedHashMap<String, Double>()
                for (k in listOf("sunny", "grey", "wet")) {
                    val th = Amsterdam.THEMES.getValue(k)
                    tides[k] = PyMath.round(tide((th["date"] as List<Any?>).map { (it as Number).toInt() }, (th["hhmm"] as List<Any?>).map { (it as Number).toInt() }), 2)
                }
                S["_tide"] = tides
                S["_tidal"] = water.filter { absArea(it) > 1e5 }
                info = dictOf("bank" to PyMath.round(ll_, 1), "tide" to tides)
                S["_bank"] = ll_
            }
            for (r in water) { // basins, ponds and fountains too small for the survey: a shallow generic drop
                if (!lv.containsKey(r) && absArea(r) < 1e5) lv[r] = -0.6
            }
            S["water_y"] = lv; S["_water_level"] = info
            val hs = ArrayList<Double>()
            for (i in -30..30) for (j in -30..30) {
                if (hypot(i.toDouble(), j.toDouble()) * 80 < RAD) { val v = dem.hn(TX + i * 80, TZ + j * 80); if (!v.isNaN()) hs.add(v) }
            }
            S["_base"] = median(hs)
            S["_lakes"] = ArrayList(water)
            S["_relief"] = name in RELIEF
            var fine = File(File(root, name), "dem5.json")
            if (name in RELIEF && !fine.exists()) fine = demPath // no finer survey: the one we have
            if (name in RELIEF && fine.exists()) {
                // the relief from the 5 m survey; the coarse one still measures the water levels above
                val GR_ = d(cfg["ground_r"] ?: 1500) * 1.2
                S["_flat"] = Relief.buildFlat(Terrain.DEM(fine), water, d(S["_base"]), extent = doubleArrayOf(TX - GR_, TX + GR_, TZ - GR_, TZ + GR_))
            }
        }
    }

    /** Python `float(v)` of a JSON number or numeric string. */
    private fun pyFloat(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().replace("_", "").toDouble()
        else -> throw IllegalArgumentException("float() of $v")
    }

    /** Sets the tide of moment [k] (`S['_tide']`) on the water, as regions.py's main loop does before each render of the port. */
    fun setTide(S: Dict, k: String) {
        val tides = S["_tide"] as Map<String, Double>? ?: return
        if (tides.isEmpty()) return
        val y = pmax(-9.0, pmin(-0.6, tides.getValue(k) - d(S["_bank"]))); Amsterdam.WATER_Y = y
        val lv = S["water_y"] as MutableMap<Any?, Any?>
        for (r in S["_tidal"] as List<List<V2>>) lv[r] = y
    }

    // ------------------------------------------------------------------ drawing: on the ground
    // render/regions.py:481 night_c
    fun nightC(th: Map<String, Any?>, c: String, k: Double = 0.2): String = if (th["night"] == true) tohex(mulc(hx(c), k)) else c

    // render/regions.py:483 gpoly
    fun gpoly(cam: City.Camera, pts: List<V2>, y: Double = 0.0, margin: Double = 600.0): String {
        val sp = cam.poly(pts.map { V3(it.x, y, it.y) }, margin = margin)
        return if (sp != null && City.area(sp) > 0.3) P(sp) else ""
    }

    // render/regions.py:487 draw_under
    /** Land cover below the water, the canopy of the woods, and (relief regions) the relief image. */
    fun drawUnder(S: Dict, cam: City.Camera, th: Dict, far: (Double, Double) -> Boolean, defs: MutableList<String>): String {
        val out = arrayListOf("<g filter=\"url(#mat)\">")
        val groups = LinkedHashMap<String, MutableList<String>>()
        val land = dicts(S["land"])
        for (a in land) {
            WorldTape.owner(a)
            val dd = gpoly(cam, Amsterdam.polyOf(a), 0.01)
            if (dd.isNotEmpty()) groups.getOrPut(a["kind"] as String) { ArrayList() }.add(dd)
        }
        WorldTape.owner(null)
        for ((kind, ds) in groups) out.add(pf("<path d=\"%s\" fill=\"%s\"/>", ds.joinToString(""), nightC(th, LAND.getValue(kind).first)))
        out.add("</g>")
        // forest and scrub seen from above: a canopy of crowns, generic, inside the mapped outline
        val canopy = land.filter { it["kind"] in setOf("forest", "wood", "scrub") }
        if (canopy.isNotEmpty() && th["night"] != true) {
            defs.add("<filter id=\"canopy\" x=\"0\" y=\"0\" width=\"100%\" height=\"100%\"><feTurbulence type=\"fractalNoise\" baseFrequency=\".55\" numOctaves=\"2\" seed=\"17\" result=\"n\"/>" +
                "<feColorMatrix in=\"n\" type=\"matrix\" values=\"0 0 0 0 .16  0 0 0 0 .24  0 0 0 0 .12  -3.4 0 0 0 2.1\"/>" +
                "<feComposite in2=\"SourceGraphic\" operator=\"in\"/></filter>")
            val ds = canopy.map { WorldTape.owner(it); gpoly(cam, Amsterdam.polyOf(it), 0.02) }
            WorldTape.owner(null)
            out.add(pf("<path d=\"%s\" fill=\"#000\" filter=\"url(#canopy)\" opacity=\".55\"/>", ds.joinToString("")))
        }
        if (truthy(S["_relief"])) {
            val png = Relief.shadeFlat(S, cam, th, cfgFor((S["view"] ?: S["region"]) as String))
            if (png != null) th["_raster"] = Raster("png", png, null) // its own file on the board, above the ground, under what stands on it
        }
        return out.joinToString("")
    }

    // render/regions.py:512 offset_line
    /** A polyline moved sideways by [off] metres (left positive). */
    fun offsetLine(pts: List<V2>, off: Double): List<V2> {
        val out = ArrayList<V2>(pts.size)
        for (i in pts.indices) {
            val p = pts[i]; val a = pts[Math.max(0, i - 1)]; val b = pts[Math.min(pts.size - 1, i + 1)]
            val dx = b[0] - a[0]; val dz = b[1] - a[1]; val L = hypot(dx, dz).let { if (it == 0.0) 1.0 else it }
            out.add(V2(p[0] - dz / L * off, p[1] + dx / L * off))
        }
        return out
    }

    // render/regions.py:521 line_d
    fun lineD(cam: City.Camera, pts: List<V2>, y: Double): String {
        var run = ArrayList<V2>(); val out = StringBuilder()
        for (k in 0..pts.size) {
            val q = if (k < pts.size) cam.proj(V3(pts[k].x, y, pts[k].y)) else null
            if (q != null) { run.add(q); continue }
            if (run.size > 1) out.append(runD(run))
            run = ArrayList()
        }
        return out.toString()
    }

    // render/regions.py:530 dashes
    /** World-space dashes along a polyline: [on] metres painted, [off] metres gap, measured along the whole line. */
    fun dashes(cam: City.Camera, pts: List<V2>, y: Double, on: Double, off: Double): String {
        val out = StringBuilder(); var s0 = 0.0; val period = on + off
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]; val L = dist(a, b)
            if (L < 1e-6) continue
            var k = Math.floor(s0 / period) * period // dash starts at multiples of the period, in line distance
            while (k < s0 + L) {
                val d0 = pmax(k, s0); val d1 = pmin(k + on, s0 + L)
                if (d1 > d0) {
                    val f0 = (d0 - s0) / L; val f1 = (d1 - s0) / L
                    val p0 = cam.proj(V3(a[0] + (b[0] - a[0]) * f0, y, a[1] + (b[1] - a[1]) * f0)); val p1 = cam.proj(V3(a[0] + (b[0] - a[0]) * f1, y, a[1] + (b[1] - a[1]) * f1))
                    if (p0 != null && p1 != null) out.append(pf("M%.1f %.1fL%.1f %.1f", p0[0], p0[1], p1[0], p1[1]))
                }
                k += period
            }
            s0 += L
        }
        return out.toString()
    }

    // render/regions.py:547 px
    fun px(cam: City.Camera, w: Double, p: V2): Double {
        val c = cam.cam(V3(p[0], 0.0, p[1]))
        return pmax(0.25, w * cam.focal / pmax(c[2], 1.0))
    }

    // render/regions.py:551 draw_over
    /** On the ground above the water: rails, big roads with their lanes, runways and taxiways, marine works, a park's tracks and woods, a national park's edge. */
    fun drawOver(S: Dict, cam: City.Camera, th: Dict, far: (Double, Double) -> Boolean, defs: MutableList<String>, light: Woods.Light): String {
        val night = th["night"] == true; val out = ArrayList<String>()
        val near: (List<V2>) -> Boolean = { pts -> (every(pts, Math.max(1, pts.size / 8)) + listOf(pts[pts.size - 1])).any { !far(it.x, it.y) } }
        // rail: ballast and two rails
        for (r in dicts(S["rail"])) {
            val rp = Amsterdam.ptsOf(r); val crane = r["crane"] == true
            if (!near(rp)) continue
            WorldTape.owner(r)
            val dd = gpoly(cam, ribbon(rp, if (!crane) 1.6 else 0.5), 0.04)
            if (dd.isNotEmpty() && !crane) out.add(pf("<path d=\"%s\" fill=\"%s\"/>", dd, nightC(th, "#A0978B")))
            val rl = lineD(cam, offsetLine(rp, 0.72), 0.08) + lineD(cam, offsetLine(rp, -0.72), 0.08)
            if (rl.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\".7\"/>", rl, if (!night) "#5E5A56" else "#1A1C22"))
        }
        // big roads: asphalt by lanes, white edge lines, dashed lane lines (from the lanes tag)
        for (r in dicts(S["big"]).pySortedBy0 { d(it["layer"]) }) {
            val rp = Amsterdam.ptsOf(r)
            if (!near(rp)) continue
            WorldTape.owner(r)
            val t = tagsOf(r); val hw = r["hw"] as String; val layer = d(r["layer"])
            val lanes = PyMath.int(Real.num(t["lanes"], if (hw.endsWith("link")) 1.0 else 2.0)!!).toInt()
            val motorway = hw == "motorway"
            val w = Real.num(t["width"], lanes * 3.5 + (if (motorway) 3.0 else 1.0))!!
            val y = 0.06 + pmax(0.0, layer) * 0.5
            val dd = gpoly(cam, ribbon(rp, w / 2), y)
            if (dd.isEmpty()) continue
            out.add(pf("<path d=\"%s\" fill=\"%s\"/>", dd, nightC(th, if (!night) "#77777A" else "#22252E", 1.0)))
            val mk = if (!night) "#F2F1EC" else "#5A5E6A"
            val edge = lineD(cam, offsetLine(rp, w / 2 - 0.5), y + 0.01) + lineD(cam, offsetLine(rp, -(w / 2 - 0.5)), y + 0.01)
            val sw = px(cam, 0.18, rp[rp.size / 2])
            if (sw > 0.3) out.add(pf("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.2f\"/>", edge, mk, sw))
            if (lanes > 1 && sw > 0.3) {
                val lw = (w - (if (motorway) 3.0 else 1.0)) / lanes; val x0 = -(w / 2) + (if (motorway) 2.0 else 0.5)
                val ds = (1 until lanes).joinToString("") { i -> dashes(cam, offsetLine(rp, x0 + lw * i), y + 0.01, 3.0, 9.0) }
                if (ds.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.2f\"/>", ds, mk, sw))
            }
        }
        // runways and taxiways: pavement by width, markings from aeroway, ref and width
        val ORDER = mapOf("parking_position" to 3, "taxilane" to 1, "taxiway" to 1, "stopway" to 0, "runway" to 2)
        for (a in dicts(S["aero"]).pySortedBy0 { ORDER.getValue(it["kind"] as String).toDouble() }) {
            val ap = Amsterdam.ptsOf(a)
            if (!near(ap)) continue
            WorldTape.owner(a)
            val t = tagsOf(a); val kind = a["kind"] as String
            if (kind == "runway") {
                val w = Real.num(t["width"], 45.0)!!
                val dd = gpoly(cam, ribbon(ap, w / 2), 0.07)
                if (dd.isEmpty()) continue
                out.add(pf("<path d=\"%s\" fill=\"%s\"/>", dd, nightC(th, if (!night) "#5F6064" else "#1E2128", 1.0)))
                val mk = if (!night) "#F4F3EE" else "#4A4F5C"
                val sw = px(cam, 0.9, ap[ap.size / 2])
                val cl = dashes(cam, ap, 0.09, 30.0, 20.0)
                if (cl.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.2f\"/>", cl, mk, sw))
                out.add(runwayEnds(cam, ap, w, (t["ref"] as String?)?.takeIf { it.isNotEmpty() } ?: "", mk, night))
            } else if (kind == "taxiway" || kind == "taxilane" || kind == "stopway") {
                val w = Real.num(t["width"], if (kind == "taxiway") 23.0 else 15.0)!!
                val dd = gpoly(cam, ribbon(ap, w / 2), 0.065)
                if (dd.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"%s\"/>", dd, nightC(th, if (!night) "#8C8C8D" else "#20232A", 1.0)))
                val cl = lineD(cam, ap, 0.08)
                if (cl.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.2f\"/>", cl, if (!night) "#E2BC3C" else "#8A7A3A", px(cam, 0.3, ap[ap.size / 2])))
            } else { // a stand's lead-in line
                val cl = lineD(cam, ap, 0.08)
                if (cl.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.2f\"/>", cl, if (!night) "#E2BC3C" else "#8A7A3A", px(cam, 0.25, ap[0])))
            }
        }
        // breakwaters, groynes, piers: stone or timber strips on the water's edge
        for (m in dicts(S["marine"])) {
            val mp = Amsterdam.ptsOf(m)
            if (!near(mp)) continue
            WorldTape.owner(m)
            val kind = m["kind"] as String
            val wdt = mapOf("breakwater" to 9.0, "groyne" to 4.0, "pier" to 5.0, "quay" to 3.0).getValue(kind)
            val col = mapOf("breakwater" to "#9E9A92", "groyne" to "#8E8A84", "pier" to "#A89A86", "quay" to "#B4AEA4").getValue(kind)
            val dd = gpoly(cam, if (m["closed"] == true) mp else ribbon(mp, wdt / 2), 0.3)
            if (dd.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"%s\"/>", dd, nightC(th, col)))
        }
        WorldTape.owner(null)
        if (truthy(S["coasters"]) || truthy(S["woods"])) { // a theme park's tracks, and a park's or a zoo's planted woods
            var its: List<Amsterdam.Item> = emptyList(); var shs: List<Pair<String, Double>> = emptyList()
            if (truthy(S["coasters"])) {
                val r = Coasters.draw(S["coasters"] as List<Coasters.Track>, cam, th, far, v3(th["_L"]), light); its = r.first; shs = r.second
            }
            S["_coaster_items"] = its
            if (truthy(S["woods"])) {
                val (wits, wsh) = Woods.draw(dicts(S["woods"]), cam, th, far, light)
                S["_coaster_items"] = its + wits
                if (wsh.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"%s\" opacity=\"%.2f\"/>", wsh.joinToString(""), th["shadow"], th["shadow_op"]))
            }
            for ((d_, w_) in shs) out.add(pf("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.2f\" stroke-linecap=\"round\" opacity=\"%.2f\"/>", d_, th["shadow"], pmax(0.5, w_), d(th["shadow_op"]) * 0.9))
        }
        // a national park's edge: a quiet dashed line on the ground, as on a walking map
        for (p in dicts(S["park"])) {
            WorldTape.owner(p)
            val dd = lineD(cam, p["ring"] as List<V2>, 0.15)
            if (dd.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"2.2\" stroke-dasharray=\"7 5\" opacity=\".85\"/>", dd, if (!night) "#2F6B3E" else "#4C8A5A"))
        }
        WorldTape.owner(null)
        return out.joinToString("")
    }

    /** `sorted(lst, key=key)`. */
    private inline fun <T> List<T>.pySortedBy0(crossinline key: (T) -> Double): List<T> {
        val out = toMutableList(); out.pySortBy(key); return out
    }

    /** `re.match(r'\d+', s)`: the leading digits, or null. */
    private fun leadingDigits(s: String): String? {
        var i = 0
        while (i < s.length && Character.isDigit(s[i])) i++
        return if (i > 0) s.substring(0, i) else null
    }

    // render/regions.py:628 runway_ends
    /** Threshold bars and the designation at each end: the numbers come from the runway's ref (e.g. 18C/36C). */
    fun runwayEnds(cam: City.Camera, pts: List<V2>, w: Double, ref: String, mk: String, night: Boolean): String {
        val out = StringBuilder()
        val names: List<String?> = if ('/' in ref) ref.split('/').map { PyRe.strip(it) } else listOf(null, null)
        for (end in 0..1) {
            val p0 = if (end == 0) pts[0] else pts[pts.size - 1]; val p1 = if (end == 0) pts[1] else pts[pts.size - 2]
            val dx = p1[0] - p0[0]; val dz = p1[1] - p0[1]; val L = hypot(dx, dz).let { if (it == 0.0) 1.0 else it }; val ux = dx / L; val uz = dz / L; val nx = -uz; val nz = ux
            val bars = StringBuilder()
            val nb = 8; val bw = 1.8; val span = w - 6
            for (i in 0 until nb) {
                for (sgn in intArrayOf(-1, 1)) {
                    val o = sgn * (3 + i * (span / 2 - 3) / nb)
                    val q = listOf(
                        V2(p0[0] + ux * 6 + nx * (o - bw / 2), p0[1] + uz * 6 + nz * (o - bw / 2)), V2(p0[0] + ux * 36 + nx * (o - bw / 2), p0[1] + uz * 36 + nz * (o - bw / 2)),
                        V2(p0[0] + ux * 36 + nx * (o + bw / 2), p0[1] + uz * 36 + nz * (o + bw / 2)), V2(p0[0] + ux * 6 + nx * (o + bw / 2), p0[1] + uz * 6 + nz * (o + bw / 2)),
                    )
                    val dd = gpoly(cam, q, 0.09, margin = 200.0)
                    if (dd.isNotEmpty()) bars.append(dd)
                }
            }
            if (bars.isNotEmpty()) out.append(pf("<path d=\"%s\" fill=\"%s\"/>", bars.toString(), mk))
            // which name belongs to this end: the one whose heading matches the direction of travel from it
            val hd = PyMath.mod(PyMath.degrees(PyMath.atan2(ux, -uz)) + 360, 360.0)
            var nm: String? = null
            for (s_ in names) {
                if (!s_.isNullOrEmpty()) {
                    val dg = leadingDigits(s_) ?: continue
                    if (Math.abs(PyMath.mod((PyRe.asciiDigits(dg).toBigInteger().toDouble() * 10 - hd) + 180, 360.0) - 180) < 30) nm = s_
                }
            }
            if (nm != null) {
                val txt = Living.groundText(cam, nm, V3(p0[0] + ux * 60, 0.09, p0[1] + uz * 60), V3(nx, 0.0, nz), V3(ux, 0.0, uz), 18.0, mk, 1.0, 800, 0.05)
                if (txt.isNotEmpty()) out.append(txt)
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ drawing: things standing up
    // render/regions.py:657 draw_items
    /** Towers, wind turbines, cranes, masts, gantries and conveyors, a park's tracks and woods, power lines: items sorted with the rest. */
    fun drawItems(S: Dict, cam: City.Camera, th: Dict, far: (Double, Double) -> Boolean, defs: MutableList<String>): List<Amsterdam.Item> {
        val night = th["night"] == true; val L = v3(th["_L"]); val items = ArrayList<Amsterdam.Item>()
        drawTowers(S, cam, th, far, defs, items)
        drawTurbines(S, cam, th, far, items)
        // cranes (man_made=crane): solid painted steel, height and colour generic as neither is mapped here.
        // On the quay edge, the ship-to-shore shape: a portal with a boom out over the water; in the yard, a portal gantry over the stack.
        val steel = if (!night) hx("#7D8993") else hx("#1A1E24")
        fun box(c0: V2, u: V2, v: V2, hu: Double, hv: Double, y0: Double, y1: Double, col: V3 = steel): String {
            val cx = c0.x; val cz = c0.y; val ux_ = u.x; val uz_ = u.y; val vx_ = v.x; val vz_ = v.y
            fun P8(su: Int, sv: Int, y: Double): V3 = V3(cx + ux_ * su * hu + vx_ * sv * hv, y, cz + uz_ * su * hu + vz_ * sv * hv)
            val faces = listOf(
                V3(ux_, 0.0, uz_) to listOf(P8(1, -1, y0), P8(1, 1, y0), P8(1, 1, y1), P8(1, -1, y1)), V3(-ux_, 0.0, -uz_) to listOf(P8(-1, 1, y0), P8(-1, -1, y0), P8(-1, -1, y1), P8(-1, 1, y1)),
                V3(vx_, 0.0, vz_) to listOf(P8(1, 1, y0), P8(-1, 1, y0), P8(-1, 1, y1), P8(1, 1, y1)), V3(-vx_, 0.0, -vz_) to listOf(P8(-1, -1, y0), P8(1, -1, y0), P8(1, -1, y1), P8(-1, -1, y1)),
                V3(0.0, 1.0, 0.0) to listOf(P8(-1, -1, y1), P8(1, -1, y1), P8(1, 1, y1), P8(-1, 1, y1)),
            )
            val out = StringBuilder()
            for ((n3, q) in faces) {
                val m = V3(PyMath.sumOf(q) { it.x } / 4, PyMath.sumOf(q) { it.y } / 4, PyMath.sumOf(q) { it.z } / 4)
                if (vdot(n3, vsub(cam.C, m)) <= 0) continue
                val sp = cam.poly(q, margin = 200.0)
                if (sp == null || City.area(sp) < 0.05) continue
                val colr = tohex(Amsterdam.light(th, col, n3, !night && vdot(n3, L) > 0))
                out.append(pf("<path d=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\".25\"/>", P(sp), colr, colr))
            }
            return out.toString()
        }
        class QE(val a: V2, val b: V2, val nx: Double, val nz: Double)
        val cranes = dicts(S["cranes"])
        if (cranes.isNotEmpty() && "_qedges" !in S) S["_qedges"] = Amsterdam.waterEdges(S).map { QE(it.a, it.b, it.nx, it.nz) }
        fun toWater(x: Double, z: Double): Pair<Double, V2?> {
            var bd = 1e9; var bn: V2? = null
            for (e in S["_qedges"] as List<QE>) {
                val ux_ = e.b[0] - e.a[0]; val uz_ = e.b[1] - e.a[1]; val L2 = (ux_ * ux_ + uz_ * uz_).let { if (it == 0.0) 1.0 else it }
                val t = pmax(0.0, pmin(1.0, ((x - e.a[0]) * ux_ + (z - e.a[1]) * uz_) / L2)); val dd = hypot(e.a[0] + ux_ * t - x, e.a[1] + uz_ * t - z)
                if (dd < bd) { bd = dd; bn = V2(e.nx, e.nz) }
            }
            return bd to bn
        }
        val rails = dicts(S["rail"]).filter { it["crane"] == true }
        class Part(val x: Double, val z: Double, val svg: String)
        fun body(parts: List<Part>, H: Double): String = parts.pySortedBy0 { -cam.depth(V3(it.x, H / 2, it.z)) }.joinToString("") { it.svg }
        for (cr in cranes) {
            val x = d(cr["x"]); val z = d(cr["z"])
            if (far(x, z)) continue
            WorldTape.owner(cr)
            val c = cam.cam(V3(x, 15.0, z))
            if (c[2] < 5) continue
            if (cam.focal / c[2] < 0.6) continue // too far to show as more than a speck
            val (dw, wn) = toWater(x, z)
            val parts = ArrayList<Part>()
            val tags = tagsOf(cr)
            if (cr["poly"] == null && dw < 60 && wn != null) {
                // ship-to-shore: portal legs either side of the quay rail, the boom from behind the portal out over the water
                var w = wn
                if (!Amsterdam.onWater(S, x + w[0] * (dw + 25), z + w[1] * (dw + 25)) && Amsterdam.onWater(S, x - w[0] * (dw + 25), z - w[1] * (dw + 25))) {
                    w = V2(-w[0], -w[1]) // the boom reaches over the side where the water is
                }
                val q = V2(-w[1], w[0]); val H = Real.num(tags["height"], 44.0)!!; val g = 9.0; val sp_ = 8.0
                for (sw in intArrayOf(-1, 1)) for (sq in intArrayOf(-1, 1)) {
                    val cc = V2(x + w[0] * sw * g + q[0] * sq * sp_, z + w[1] * sw * g + q[1] * sq * sp_)
                    parts.add(Part(cc.x, cc.y, box(cc, w, q, 0.8, 0.8, 0.0, H)))
                }
                for (sq in intArrayOf(-1, 1)) { // sill beams along the boom axis, at the foot and under the boom
                    for (y in doubleArrayOf(9.0, H - 3.0)) {
                        val cc = V2(x + q[0] * sq * sp_, z + q[1] * sq * sp_); parts.add(Part(cc.x, cc.y, box(cc, w, q, g, 0.6, y - 1.0, y)))
                    }
                }
                val back = 22.0; val out_ = dw + 52.0
                val mid = (back * -1 + out_) / 2
                val bc = V2(x + w[0] * mid, z + w[1] * mid)
                parts.add(Part(bc.x, bc.y, box(bc, w, q, (out_ + back) / 2, 2.0, H - 1.0, H + 2.4))) // the boom
                val mh = V2(x - w[0] * 14, z - w[1] * 14)
                parts.add(Part(mh.x, mh.y, box(mh, w, q, 6.0, 4.5, H + 2.4, H + 7.0, if (!night) hx("#C9CCCF") else hx("#20242A")))) // machinery house
                for (sq in intArrayOf(-1, 1)) { // the A-frame above the portal
                    val ap = V2(x + w[0] * 3 + q[0] * sq * sp_ * 0.6, z + w[1] * 3 + q[1] * sq * sp_ * 0.6)
                    parts.add(Part(ap.x, ap.y, box(ap, w, q, 0.7, 0.7, H + 2.4, H + 20.0)))
                }
                val top = cam.proj(V3(x + w[0] * 3, H + 20.0, z + w[1] * 3)); val tip = cam.proj(V3(x + w[0] * out_, H + 2.4, z + w[1] * out_))
                val tail = cam.proj(V3(x - w[0] * back, H + 2.4, z - w[1] * back))
                val stays = listOf(tip, tail).filter { top != null && it != null }.joinToString("") { e -> pf("M%.1f %.1fL%.1f %.1f", top!![0], top[1], e!![0], e[1]) }
                val sw_ = pmax(0.5, 0.35 * cam.focal / c[2])
                items.add(Amsterdam.noted(Amsterdam.Item(c[2], "svg", body(parts, H) + (if (stays.isNotEmpty()) pf("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.2f\"/>", stays, tohex(steel), sw_) else "")),
                    cam, "crane", cr, x, z, x, 15.0, z))
                continue
            }
            // a yard gantry over the stack: legs at the corners, girders along the span, end beams, a trolley on top
            val H = Real.num(tags["height"], 26.0)!!
            var ux: Double; var uz: Double; var hu: Double; var hv: Double
            val poly = cr["poly"] as List<V2>?
            if (poly != null && poly.size >= 4) {
                val pts = if (same(poly[0], poly[poly.size - 1])) poly.subList(0, poly.size - 1) else poly
                var e0 = 0; var bd = dist(pts[0], pts[1 % pts.size])
                for (i in pts.indices) { val dd = dist(pts[i], pts[(i + 1) % pts.size]); if (dd > bd) { bd = dd; e0 = i } }
                val a = pts[e0]; val b = pts[(e0 + 1) % pts.size]; ux = b[0] - a[0]; uz = b[1] - a[1]
                val Lg = hypot(ux, uz).let { if (it == 0.0) 1.0 else it }; ux /= Lg; uz /= Lg
                val pu = pts.map { (it[0] - x) * ux + (it[1] - z) * uz }; val pv = pts.map { -(it[0] - x) * uz + (it[1] - z) * ux }
                hu = pmax(4.0, (City.pyMaxOf(pu) - City.pyMinOf(pu)) / 2); hv = pmax(3.0, (City.pyMaxOf(pv) - City.pyMinOf(pv)) / 2)
            } else {
                ux = 1.0; uz = 0.0; var best = 1e9
                for (r in rails) {
                    val rp = Amsterdam.ptsOf(r)
                    for (i in 0 until rp.size - 1) {
                        val p = rp[i]; val q_ = rp[i + 1]
                        val dd = hypot((p[0] + q_[0]) / 2 - x, (p[1] + q_[1]) / 2 - z)
                        if (dd < best) {
                            best = dd; val L_ = dist(p, q_).let { if (it == 0.0) 1.0 else it }; ux = (q_[0] - p[0]) / L_; uz = (q_[1] - p[1]) / L_
                        }
                    }
                }
                hu = 9.0; hv = 14.0
            }
            var u = V2(ux, uz); var v = V2(-uz, ux)
            if (hu > 2.5 * hv) {
                // an outline much longer than wide is the crane's runway along its rails: one portal spans it, a few metres deep;
                // where along the runway it stands is not mapped, so it is drawn at the middle
                val t_ = u; u = v; v = t_; ux = u.x; uz = u.y; hu = hv; hv = 4.0
            }
            for (su in intArrayOf(-1, 1)) for (sv in intArrayOf(-1, 1)) {
                val cc = V2(x + ux * su * (hu - 0.6) + v[0] * sv * (hv - 0.6), z + uz * su * (hu - 0.6) + v[1] * sv * (hv - 0.6))
                parts.add(Part(cc.x, cc.y, box(cc, u, v, 0.6, 0.6, 0.0, H)))
            }
            for (sv in intArrayOf(-1, 1)) {
                val cc = V2(x + v[0] * sv * (hv - 0.6), z + v[1] * sv * (hv - 0.6)); parts.add(Part(cc.x, cc.y, box(cc, u, v, hu, 0.6, H - 2.0, H)))
            }
            for (su in intArrayOf(-1, 1)) {
                val cc = V2(x + ux * su * (hu - 0.6), z + uz * su * (hu - 0.6)); parts.add(Part(cc.x, cc.y, box(cc, u, v, 0.6, hv, H - 2.0, H)))
            }
            parts.add(Part(x, z, box(V2(x, z), u, v, 2.2, pmin(hv, 3.0), H, H + 2.6, if (!night) hx("#C9CCCF") else hx("#20242A"))))
            items.add(Amsterdam.noted(Amsterdam.Item(c[2], "svg", body(parts, H)), cam, "crane", cr, x, z, x, 15.0, z))
        }
        // masts: a slim steel shaft; a lighting mast carries a frame of floodlights, lit after dark (height generic where not mapped)
        val masts = (S["masts"] as List<Dict>?) ?: emptyList()
        for (m in masts) {
            val x = d(m["x"]); val z = d(m["z"])
            if (far(x, z)) continue
            WorldTape.owner(m)
            val lighting = tagsOf(m)["tower:type"] == "lighting"
            val H = if (truthy(m["h"])) d(m["h"]) else (if (lighting) 30.0 else 25.0)
            val c = cam.cam(V3(x, H / 2, z))
            if (c[2] < 5 || cam.focal / c[2] < 0.25) continue
            val u = V2(cam.r[0], cam.r[2]); val v = V2(-u[1], u[0])
            var g = box(V2(x, z), u, v, 0.45, 0.45, 0.0, H)
            if (lighting) {
                g += box(V2(x, z), u, v, 1.7, 0.6, H, H + 1.3, if (!night) hx("#5E646B") else hx("#15181E"))
                if (night) {
                    val hp = cam.proj(V3(x, H, z))
                    if (hp != null) {
                        val r_ = pmax(2.0, 9.0 * cam.focal / c[2])
                        g += pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"url(#mastglow)\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"#FFF4DC\"/>", hp[0], hp[1], r_, hp[0], hp[1], pmax(0.6, 1.2 * cam.focal / c[2]))
                    }
                }
            }
            items.add(Amsterdam.noted(Amsterdam.Item(c[2], "svg", g), cam, "mast", m, x, z, x, H / 2, z))
        }
        if (night && masts.any { tagsOf(it)["tower:type"] == "lighting" }) {
            defs.add("<radialGradient id=\"mastglow\"><stop offset=\"0\" stop-color=\"#FFE9C2\" stop-opacity=\".75\"/><stop offset=\".35\" stop-color=\"#FFD9A0\" stop-opacity=\".22\"/><stop offset=\"1\" stop-color=\"#FFD9A0\" stop-opacity=\"0\"/></radialGradient>")
        }
        // gantries over a road (man_made=gantry): a portal of two legs and a beam; conveyors (man_made=goods_conveyor): a closed gallery on legs.
        // Their heights are generic where not mapped.
        for ((kind, coll) in listOf("gantry" to ((S["gantries"] as List<Dict>?) ?: emptyList()), "conveyor" to ((S["conveyors"] as List<Dict>?) ?: emptyList()))) {
            for (gt in coll) {
                val pts = Amsterdam.ptsOf(gt)
                if (pts.all { far(it.x, it.y) }) continue
                WorldTape.owner(gt)
                val H = if (truthy(gt["h"])) d(gt["h"]) else (if (kind == "gantry") 7.0 else 6.0)
                for (i in 0 until pts.size - 1) {
                    val a = pts[i]; val b = pts[i + 1]; val Lg = dist(a, b)
                    if (Lg < 0.5 || (far(a.x, a.y) && far(b.x, b.y))) continue
                    val u = V2((b[0] - a[0]) / Lg, (b[1] - a[1]) / Lg); val v = V2(-u[1], u[0]); val mid = V2((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
                    val c = cam.cam(V3(mid[0], H, mid[1]))
                    if (c[2] < 5 || cam.focal / c[2] < 0.3) continue
                    val parts = ArrayList<Part>()
                    if (kind == "gantry") {
                        for (e_ in listOf(if (i == 0) a else null, if (i == pts.size - 2) b else null)) {
                            if (e_ != null) parts.add(Part(e_.x, e_.y, box(e_, u, v, 0.35, 0.35, 0.0, H)))
                        }
                        parts.add(Part(mid.x, mid.y, box(mid, u, v, Lg / 2, 0.45, H - 0.9, H)))
                    } else {
                        val nl = Math.max(1, PyMath.int(Lg / 22).toInt())
                        for (k in 0..nl) {
                            val f = k.toDouble() / nl; val lp = V2(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)
                            parts.add(Part(lp.x, lp.y, box(lp, u, v, 0.4, 1.3, 0.0, H - 2.2)))
                        }
                        parts.add(Part(mid.x, mid.y, box(mid, u, v, Lg / 2, 1.4, H - 2.2, H, if (!night) hx("#B4B1A9") else hx("#1C1E22"))))
                    }
                    items.add(Amsterdam.noted(Amsterdam.Item(c[2], "svg", body(parts, H)), cam, kind, gt, a.x, a.y, mid[0], H, mid[1], fx1 = b.x, fz1 = b.y))
                }
            }
        }
        WorldTape.owner(null)
        (S.remove("_coaster_items") as List<Amsterdam.Item>?)?.let { items.addAll(it) } // coaster tracks and their posts, drawn in draw_over's pass
        // power lines: towers with three conductors between them (tower height generic where not mapped)
        for (pl in dicts(S["power"])) {
            val pts = Amsterdam.ptsOf(pl)
            if (pts.size < 2 || !pts.any { !far(it.x, it.y) }) continue
            WorldTape.owner(pl)
            val H = 38.0
            for (i in 0 until pts.size - 1) {
                val a = pts[i]; val b = pts[i + 1]
                if (far(a.x, a.y) && far(b.x, b.y)) continue
                val c = cam.cam(V3((a[0] + b[0]) / 2, H, (a[1] + b[1]) / 2))
                if (c[2] < 5) continue
                val fade = if (c[2] <= POWER_NEAR) 1.0 else (POWER_FAR - c[2]) / (POWER_FAR - POWER_NEAR)
                if (fade <= 0) continue
                val s = cam.focal / c[2]; val segs = StringBuilder()
                for (off in intArrayOf(-6, 0, 6)) {
                    val dx = b[0] - a[0]; val dz = b[1] - a[1]; val Lg = hypot(dx, dz).let { if (it == 0.0) 1.0 else it }; val nx = -dz / Lg * off; val nz = dx / Lg * off
                    val q = doubleArrayOf(0.0, .25, .5, .75, 1.0).map { f -> cam.proj(V3(a[0] + dx * f + nx, H - 4 * PyMath.sin(Math.PI * f) * pmin(1.0, Lg / 300) * 8 / 8, a[1] + dz * f + nz)) }
                    if (q.all { it != null }) segs.append(runD(q.map { it!! }))
                }
                val p0 = cam.proj(V3(a[0], 0.0, a[1])); val p1 = cam.proj(V3(a[0], H + 2, a[1]))
                val tw = if (p0 != null && p1 != null) pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"%s\" stroke-width=\"%.2f\"/>", p0[0], p0[1], p1[0], p1[1], if (!night) "#6E747C" else "#1A1E26", pmax(0.5, 1.4 * s)) else ""
                val body = tw + (if (segs.isNotEmpty()) pf("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\".45\" opacity=\".8\"/>", segs.toString(), if (!night) "#4A4F57" else "#1A1E26") else "")
                items.add(Amsterdam.noted(Amsterdam.Item(c[2], "svg", if (fade >= 1) body else pf("<g opacity=\"%.2f\">%s</g>", fade, body)),
                    cam, "power", pl, a.x, a.y, (a[0] + b[0]) / 2, H, (a[1] + b[1]) / 2, fx1 = b.x, fz1 = b.y))
            }
        }
        WorldTape.owner(null)
        return items
    }

    // render/regions.py:657 draw_items: towers mapped as points with a height (split out of drawItems, so it stays compilable)
    private fun drawTowers(S: Dict, cam: City.Camera, th: Dict, far: (Double, Double) -> Boolean, defs: MutableList<String>, items: MutableList<Amsterdam.Item>) {
        val night = th["night"] == true; val L = v3(th["_L"])
        // towers mapped as points with a height: a round concrete shaft; an airport or observation tower carries a glazed cab
        val sx_ = cam.r[0]; val sz_ = cam.r[2]
        for (tw in (S["mtowers"] as List<Dict>?) ?: emptyList()) {
            val x = d(tw["x"]); val z = d(tw["z"])
            if (far(x, z)) continue
            WorldTape.owner(tw)
            val t = tagsOf(tw); val H = d(tw["h"]); val cab = t["aeroway"] == "tower" || t["tower:type"] in setOf("observation", "control")
            val c = cam.cam(V3(x, H / 2, z))
            if (c[2] < 5) continue
            val rs = if (cab) 3.2 else pmax(1.2, H / 30)
            fun band(y0: Double, y1: Double, r: Double, col: String): String {
                val q = listOf(V3(x - sx_ * r, y0, z - sz_ * r), V3(x + sx_ * r, y0, z + sz_ * r), V3(x + sx_ * r, y1, z + sz_ * r), V3(x - sx_ * r, y1, z - sz_ * r))
                val sp = cam.poly(q, margin = 200.0) ?: return ""
                val gid = pf("tw%d_%d", PyMath.mod((tw["id"] as Number).toLong(), 100000L), PyMath.int(y0))
                val sh = if (!night) th["shadow"] as String else "#05060A"
                defs.add(pf("<linearGradient id=\"%s\" x1=\"0\" x2=\"1\" y1=\"0\" y2=\"0\"><stop offset=\"0\" stop-color=\"%s\"/><stop offset=\"%.2f\" stop-color=\"%s\"/><stop offset=\"1\" stop-color=\"%s\"/></linearGradient>",
                    gid, tohex(mix(hx(col), hx(sh), 0.35)), 0.35 + 0.3 * (if (L[0] * sx_ + L[2] * sz_ > 0) 1 else 0), col, tohex(mix(hx(col), hx(sh), 0.5))))
                return pf("<path d=\"%s\" fill=\"url(#%s)\"/>", P(sp), gid)
            }
            val shaft = tohex(Amsterdam.light(th, hx("#C9C6BE"), V3(0.0, 0.0, 1.0), !night))
            val parts = arrayListOf(band(0.0, H - (if (cab) 14 else 0), rs, shaft))
            if (cab) {
                parts.add(band(H - 14, H - 11, rs * 2.3, shaft))
                parts.add(band(H - 11, H - 4.5, rs * 2.6, if (!night) "#7F98AA" else "#1A2230"))
                parts.add(band(H - 4.5, H - 3.2, rs * 2.7, tohex(Amsterdam.light(th, hx("#E2E0DA"), V3(0.0, 1.0, 0.0), !night))))
                parts.add(band(H - 3.2, H, 0.5, "#8C9096"))
            }
            items.add(Amsterdam.noted(Amsterdam.Item(c[2], "svg", parts.joinToString("")), cam, "tower", tw, x, z, x, H / 2, z))
        }
        WorldTape.owner(null)
    }

    // render/regions.py:657 draw_items: wind turbines (split out of drawItems, so it stays compilable)
    private fun drawTurbines(S: Dict, cam: City.Camera, th: Dict, far: (Double, Double) -> Boolean, items: MutableList<Amsterdam.Item>) {
        val night = th["night"] == true
        // wind turbines: tower, nacelle, three blades; the rotor faces the hour's real wind
        val wd = PyMath.radians(d((th["wx"] as Map<String, Any?>)["wind_dir"]))
        val fx = PyMath.sin(wd); val fz = -PyMath.cos(wd) // toward where the wind comes from
        for (tb in dicts(S["turbines"])) {
            val x = d(tb["x"]); val z = d(tb["z"])
            if (far(x, z)) continue
            WorldTape.owner(tb)
            val H = if (truthy(tb["h"])) d(tb["h"]) else 100.0; val Rr = (if (truthy(tb["rotor"])) d(tb["rotor"]) else 100.0) / 2
            val c = cam.cam(V3(x, H / 2, z))
            if (c[2] < 5) continue
            val s = cam.focal / c[2]
            val base = cam.proj(V3(x, 0.0, z)); val top = cam.proj(V3(x, H, z))
            if (base == null || top == null) continue
            val g = StringBuilder(pf("<path d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fZ\" fill=\"%s\"/>", base[0] - 2.2 * s, base[1], base[0] + 2.2 * s, base[1], top[0] + 1.2 * s, top[1], top[0] - 1.2 * s, top[1],
                if (!night) "#E9ECEE" else "#3A3F4C"))
            val hub = V3(x + fx * 2, H, z + fz * 2)
            val rx = -fz; val rz = fx // the rotor plane: across the wind, and up
            val blades = StringBuilder()
            for (k in 0 until 3) {
                val a = PyMath.radians((25 + 120 * k).toDouble())
                val tip = V3(hub[0] + rx * PyMath.cos(a) * Rr, H + PyMath.sin(a) * Rr, hub[2] + rz * PyMath.cos(a) * Rr)
                val mid = V3(hub[0] + rx * PyMath.cos(a + 0.06) * Rr * 0.3, H + PyMath.sin(a + 0.06) * Rr * 0.3, hub[2] + rz * PyMath.cos(a + 0.06) * Rr * 0.3)
                val ph = cam.proj(hub); val pt = cam.proj(tip); val pm = cam.proj(mid)
                if (ph != null && pt != null && pm != null) blades.append(pf("M%.1f %.1fL%.1f %.1fL%.1f %.1fZ", ph[0], ph[1], pm[0], pm[1], pt[0], pt[1]))
            }
            if (blades.isNotEmpty()) g.append(pf("<path d=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\".4\"/>", blades.toString(), if (!night) "#F2F4F5" else "#454B58", if (!night) "#C9CDD1" else "#2A2E38"))
            val ph = cam.proj(hub)
            if (ph != null) g.append(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\"/>", ph[0], ph[1], pmax(0.8, 2.2 * s), if (!night) "#DADDE0" else "#3A3F4C"))
            if (night && ph != null) g.append(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"#FF3B30\" opacity=\".9\"/>", ph[0], ph[1] - pmax(1.0, 3 * s), pmax(0.8, 1.2 * s)))
            items.add(Amsterdam.noted(Amsterdam.Item(c[2], "svg", g.toString()), cam, "turbine", tb, x, z, x, H / 2, z))
        }
        WorldTape.owner(null)
    }

    // render/regions.py:876 view_moments
    /**
     * What a view holds, counted from the mapped data in view, and the real sun and weather of each moment (regions.py writes it
     * as `<name>_moments.json`): `heading`, `counts`, and `wet`, `grey`, `sunny`.
     */
    fun viewMoments(S: Dict, name: String, cfg: Map<String, Any?>): Dict {
        val tg = cfg["target"] as List<Any?>; val TX = d(tg[0]); val TZ = d(tg[2]); val RAD = d(cfg["radius"])
        val inv: (Double, Double) -> Boolean = { x, z -> hypot(x - TX, z - TZ) <= RAD }
        val anyin: (List<V2>) -> Boolean = { pts -> pts.any { inv(it.x, it.y) } }
        val raw = (PyJson.load(File(File(root, baseOf(name)), "raw.json")) as Map<String, Any?>)["elements"] as List<Map<String, Any?>>
        fun cnt(pred: (Map<String, Any?>) -> Boolean): Int {
            var n = 0
            for (e in raw) {
                val t = tagsOf(e)
                if (!pred(t)) continue
                if (e["type"] == "node") { val p = Amsterdam.XZ(d(e["lat"]), d(e["lon"])); if (inv(p.x, p.y)) n++ }
                else if (truthy(e["geometry"])) { if (anyin(geomOf(e)!!.filter { truthy(it) }.map { ll(it!!) })) n++ }
                else if (e["type"] == "relation") n++
            }
            return n
        }
        fun tr(v: Any?): Boolean = truthy(v)
        val runways = dicts(S["aero"]).filter { a -> a["kind"] == "runway" && tr(tagsOf(a)["ref"]) && anyin(Amsterdam.ptsOf(a)) }.map { tagsOf(it)["ref"] as String }.toSortedSet().toList()
        val C: Dict = dictOf(
            "runways" to runways.size, "runway_refs" to runways, "gates" to cnt { it["aeroway"] == "gate" }, "stands" to cnt { it["aeroway"] == "parking_position" },
            "lanes" to cnt { it["highway"] in setOf("motorway", "motorway_link") && tr(it["lanes"]) }, "lights" to cnt { it["aeroway"] == "navigationaid" },
            "heights" to cnt { (tr(it["building"]) || tr(it["building:part"])) && tr(it["height"]) }, "buildings" to cnt { tr(it["building"]) && it["building"] != "no" },
            "roofs" to cnt { tr(it["roof:shape"]) }, "trees" to cnt { it["natural"] == "tree" }, "shops" to cnt { "shop" in it },
            "cranes" to cnt { it["man_made"] == "crane" }, "tanks" to cnt { it["man_made"] == "storage_tank" }, "turbines" to cnt { it["generator:source"] == "wind" },
            "forest" to cnt { it["landuse"] == "forest" || it["natural"] == "wood" }, "sand" to cnt { it["natural"] in setOf("sand", "beach", "dune") },
            "cycleways" to cnt { it["highway"] == "cycleway" }, "paths" to cnt { it["highway"] in setOf("path", "footway", "bridleway") }, "lakes" to cnt { it["natural"] == "water" },
        )
        // whose walls and roofs are mapped, and whose look is generic for their type
        val vis = dicts(S["buildings"]).filter { b -> !tr(b["hidden"]) && centroid(Amsterdam.polyOf(b)).let { inv(it.x, it.y) } }
        C["looks_total"] = vis.size
        C["looks_mapped"] = vis.count { b -> val t = tagsOf(b); tr(t["building:material"]) || tr(t["building:colour"]) || tr(t["roof:colour"]) || tr(t["roof:material"]) }
        C["looks_guessed"] = (C["looks_total"] as Int) - (C["looks_mapped"] as Int)
        C["towers"] = cnt { it["man_made"] == "tower" && (tr(it["building"]) || tr(it["building:part"])) }
        C["levels"] = cnt { tr(it["building"]) && tr(it["building:levels"]) }
        C["colours"] = cnt { tr(it["building"]) && (tr(it["building:colour"]) || tr(it["building:material"])) }
        C["lamps"] = cnt { it["highway"] == "street_lamp" }
        C["lit"] = cnt { tr(it["highway"]) && it["lit"] == "yes" }
        C["mansards"] = vis.count { b -> b["roof"] == "mansard" && tr(((b["guessed"] as Map<String, Any?>?) ?: emptyMap())["roof:shape"]) } // guessed for Paris stone fronts
        C["masts"] = ((S["masts"] as List<Dict>?) ?: emptyList()).count { m -> tagsOf(m)["tower:type"] == "lighting" && inv(d(m["x"]), d(m["z"])) }
        C["conveyors"] = ((S["conveyors"] as List<Dict>?) ?: emptyList()).count { g -> anyin(Amsterdam.ptsOf(g)) }
        val tw = ((S["mtowers"] as List<Dict>?) ?: emptyList()).filter { t_ -> tagsOf(t_)["aeroway"] == "tower" && inv(d(t_["x"]), d(t_["z"])) }
        if (tw.isNotEmpty()) C["tower"] = pf("%g", PyMath.round(d(tw[0]["h"])))
        if (tr(S["_water_level"])) {
            val W_ = S["_water_level"] as Map<String, Any?>; C["water"] = W_
            if ("water" in W_) C["drop"] = pf("%.1f", d(W_["bank"]) - d(W_["water"])) // IGN: the river surface against its quays
            if ("tide" in W_) C["drop_at"] = LinkedHashMap((W_["tide"] as Map<String, Any?>).mapValues { (_, v) -> pf("%.1f", d(W_["bank"]) - d(v)) }) // AHN quays against the hour's tide
        }
        if (tr(S["dem"])) {
            val grid = S["_grid"] as Relief.Grid?; val dem = S["dem"] as Terrain.DEM
            val k = PyMath.int(RAD / 60).toInt(); var top = Double.NaN
            for (i in -k until k) for (j in -k until k) {
                if (hypot(i.toDouble(), j.toDouble()) * 60 <= RAD) {
                    val v = if (grid != null) grid.h(TX + i * 60, TZ + j * 60) else dem.h(TX + i * 60, TZ + j * 60)
                    if (top.isNaN() || v > top) top = v
                }
            }
            C["top"] = PyMath.round(top)
        }
        val M: Dict = dictOf("heading" to PyMath.mod(d(cfg["yaw"]), 360.0).let { y -> if (cfg["yaw"] is Double) y else (y.toLong().toInt() as Any) }, "counts" to C)
        for (k in listOf("wet", "grey", "sunny")) {
            val th = Amsterdam.THEMES.getValue(k); val hm = (th["hhmm"] as List<Any?>).map { (it as Number).toInt() }; val dt = (th["date"] as List<Any?>).map { (it as Number).toInt() }
            val (az, el) = Amsterdam.sunPosition(LocalDateTime.of(dt[0], dt[1], dt[2], hm[0], hm[1]).minusHours(Amsterdam.TZ_H.toLong()))
            M[k] = dictOf("az" to az, "el" to el, "wx" to th["wx"], "night" to th["night"], "wet" to (th["wet"] ?: false))
        }
        return M
    }

    // ------------------------------------------------------------------ cameras
    // render/regions.py:930 cfg_for
    /** The camera of a view, as Amsterdam.render takes it: the view's own fields, the frame size, and the haze from the camera's distance. */
    fun cfgFor(name: String): Dict {
        if (name !in CAMS && name.endsWith("_detail") && name.dropLast(7) in CAMS) CAMS[name] = LinkedHashMap(CAMS.getValue(name.dropLast(7))) // the second pass: the same camera
        if (name !in CAMS && name.endsWith("_kit") && name.dropLast(4) in CAMS) CAMS[name] = LinkedHashMap(CAMS.getValue(name.dropLast(4))) // the kit and profile pass: the same camera
        if (name !in CAMS) { // a street-level view: its camera as the street renderer wrote it
            val p_ = File(streetCfgDir, "${name}_cfg.json")
            if (p_.exists()) CAMS[name] = LinkedHashMap((PyJson.load(p_) as Map<String, Any?>).filterKeys { it != "W" && it != "H" })
        }
        val c: Dict = LinkedHashMap(CAMS[name] ?: throw NoSuchElementException(name))
        c["W"] = 412; c["H"] = 915; c["no_fin"] = true; c["obj_split"] = 1900000
        if ("haze0" !in c) c["haze0"] = d(c["dist"]) * 0.85
        if ("haze1" !in c) c["haze1"] = d(c["dist"]) * 2.2 // the air thickens with the camera's own distance
        return c
    }

    /** The layers of one render as regions.py's main loop writes them: g, o, o2 (or null), and the ground image t (or null). */
    class Layers(val g: String, val o: String, val o2: String?, val t: Raster?, val info: Dict)

    /** regions.py's main loop for one moment: the tide of the moment (the port), then Amsterdam.render(layer='split'). */
    fun render(S: Dict, moment: String, cfg: Map<String, Any?>, opts: RenderOptions = RenderOptions()): Layers {
        setTide(S, moment)
        val r = Amsterdam.render(S, moment, cfg, null, layer = "split", opts = opts)
        val objs = r.objs!!
        return Layers(r.svg, objs[0], if (objs.size > 1) objs[1] else null, r.info["raster"] as Raster?, r.info)
    }
}
