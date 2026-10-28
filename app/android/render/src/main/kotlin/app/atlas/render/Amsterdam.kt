// render/amsterdam.py: the honest living map of Amsterdam. Draws only what OpenStreetMap says: the water and its quays,
// the sky from the real weather, buildings (and their traditions, facades, windows, roofs), shopfronts and doors, trees,
// lamps and street furniture. Ported line by line; the module's globals stay mutable, as regions.py changes them.
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
import app.atlas.render.data.RawSource
import app.atlas.render.live.ItemNote
import app.atlas.render.live.ScreenLayers
import app.atlas.render.live.WorldTape
import app.atlas.render.Real.centroid
import app.atlas.render.Real.pip
import app.atlas.render.Real.signedArea
import app.atlas.render.py.Dict
import app.atlas.render.py.GridMap
import app.atlas.render.py.PyFmt.pf
import app.atlas.render.py.PyJson
import app.atlas.render.py.PyMath
import app.atlas.render.py.PyRandom
import app.atlas.render.py.dictOf
import app.atlas.render.py.pyNum
import app.atlas.render.py.pySortBy
import app.atlas.render.py.truthy
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.IdentityHashMap

/**
 * The env flags amsterdam.py reads (`ATLAS_KIT`, `ATLAS_LOOKS`, `ATLAS_AO`, `ATLAS_DAYLIGHT`), passed in instead of read
 * from the process. Each defaults as the Python does when the variable is unset.
 */
data class RenderOptions(
    /** ATLAS_KIT=1: Amsterdam from its tradition profile and the shared kit (in [Amsterdam.load]). */
    val kit: Boolean = false,
    /** ATLAS_LOOKS=1: looks from each building's tags (in [Amsterdam.load]). */
    val looks: Boolean = false,
    /** ATLAS_AO=1: a soft dark seam where walls meet the ground (in [Amsterdam.render]). */
    val ao: Boolean = false,
    /** ATLAS_DAYLIGHT=1: light that falls with the sun's height towards night (in [Amsterdam.weatherTheme] and [Amsterdam.render]). */
    val daylight: Boolean = false,
    /**
     * The app's Look settings for today's map (MapEngine.screenEffects): the haze, the vignette and the paper grain, each
     * drawn or not (in [Amsterdam.render]'s screen layers). The renders draw all three.
     */
    val haze: Boolean = true,
    val vignette: Boolean = true,
    val grain: Boolean = true,
    /**
     * wfx's cloud shadows (in [Amsterdam.render]'s screen layers), which the renders draw. Today's map leaves them out
     * (MapEngine.options): its frame is one bitmap a gesture warps until the next exact frame, so shadows fixed to the
     * screen would ride on it under the finger and jump back (the user, 28 October: "Its wrong that you move the shadows
     * with your finger"). The core draws them, fixed to the screen.
     */
    val cloudShadows: Boolean = true,
)

/** The living map of render/amsterdam.py. Buildings, roads, nodes and the scene `S` are Python-like dicts, as Generic and Closeup read them. */
object Amsterdam {
    // ------------------------------------------------------------------ constant sets (a set literal in a loop is built anew each time)
    /** Waterway=* values mapped as water areas. */
    private val WATERWAYS = setOf("canal", "riverbank", "dock")
    /** Streets whose names are written along them. */
    private val NAMED_STREETS = setOf("residential", "tertiary", "pedestrian", "living_street", "unclassified")
    /** Leisure=* drawn as green. */
    private val GREEN_LEISURE = setOf("park", "garden", "playground")
    /** Amenity=* drawn as shops. */
    private val SHOP_AMENITIES = setOf("restaurant", "cafe", "bar", "pub", "fast_food", "ice_cream", "pharmacy")
    /** Tourism=* drawn as shops. */
    private val SHOP_TOURISM = setOf("museum", "hotel", "gallery")
    /** Building=* never given a guessed gable. */
    private val NEVER_GABLED = setOf("church", "houseboat", "industrial", "roof", "shed")
    /** Roof shapes that take a guessed roof height. */
    private val ROOFS_WITH_HEIGHT = setOf("gabled", "hipped", "pyramidal", "skillion", "quadruple_saltbox", "mansard")
    /** Roof shapes whose height the reflections take off the walls. */
    private val ROOFS_REFLECTED = setOf("gabled", "hipped", "skillion", "quadruple_saltbox", "pyramidal")
    /** Bridge=* values that are no bridge. */
    private val NO_BRIDGE = setOf(null, "no")
    /** Areas drawn in their mapped surface. */
    private val SURFACED_AREAS = setOf("pedestrian", "square", "parking")
    /** Amenity=* drawn as street furniture. */
    private val FURNITURE = setOf("bench", "bicycle_parking", "waste_basket")
    /** Barrier=* drawn as bollards. */
    private val BOLLARDS = setOf("bollard", "block")
    /** Building kinds clad in sheet metal. */
    private val METAL_SHEDS = setOf("industrial", "hangar", "sports", "shed", "terminal", "canopy", null)
    /** Materials of stone fronts. */
    private val STONE_FRONTS = setOf("limestone", "stone", "plaster")
    /** Building kinds with stone fronts. */
    private val STONE_FRONT_KINDS = setOf("home", "office", "hotel")
    /** Roof shapes drawn pitched. */
    private val PITCHED = setOf("gabled", "pyramidal", "hipped", "skillion", "mansard")
    /** Building=* of churches. */
    private val CHURCHES = setOf("church", "cathedral", "chapel")
    /** Building=* of warehouses. */
    private val WAREHOUSES = setOf("industrial", "warehouse", "service")
    /** Building=* with a shop floor. */
    private val SHOP_FLOORS = setOf("retail", "commercial")
    /** Roof shapes with a ridge. */
    private val RIDGED = setOf("gabled", "hipped")
    /** Materials of Paris fronts. */
    private val PARIS_FRONTS = setOf("limestone", "plaster", "stone", "sandstone")
    /** Roof materials laid in tiles. */
    private val TILED = setOf("roof_tiles", "slate")
    /** Shop categories with an awning. */
    private val AWNING_CATS = setOf("food", "grocery", "stay")
    /** Outdoor_seating=* values with tables outside. */
    private val OUTDOOR_SEATING = setOf("yes", "sidewalk", "pedestrian_zone", "limited")

    // ------------------------------------------------------------------ the place (regions.py moves it)
    var LAT0 = 52.3746
    var LON0 = 4.8840
    var KX = 111320 * PyMath.cos(PyMath.radians(LAT0))
    var KZ = 110540.0

    // render/amsterdam.py:22 XZ
    fun XZ(lat: Double, lon: Double): V2 = V2((lon - LON0) * KX, -(lat - LAT0) * KZ)

    /** The place's clock against UTC on these dates (CEST here; each region sets its own). */
    var TZ_H = 2

    // render/amsterdam.py:25 sun_position
    /** The sun's azimuth and elevation in degrees at [tUtc], over the place drawn (or [lat], [lon]). */
    fun sunPosition(tUtc: LocalDateTime, lat0: Double? = null, lon0: Double? = null): Pair<Double, Double> {
        val lat = lat0 ?: LAT0; val lon = lon0 ?: LON0
        val jd = java.time.temporal.ChronoUnit.SECONDS.between(LocalDateTime.of(2000, 1, 1, 12, 0), tUtc).toDouble() / 86400
        val L = PyMath.mod(280.46 + 0.9856474 * jd, 360.0)
        val g = PyMath.radians(PyMath.mod(357.528 + 0.9856003 * jd, 360.0))
        val lam = PyMath.radians(L + 1.915 * PyMath.sin(g) + 0.020 * PyMath.sin(2 * g))
        val eps = PyMath.radians(23.439 - 0.0000004 * jd)
        val ra = PyMath.atan2(PyMath.cos(eps) * PyMath.sin(lam), PyMath.cos(lam))
        val dec = PyMath.asin(PyMath.sin(eps) * PyMath.sin(lam))
        val gmst = PyMath.mod(280.46061837 + 360.98564736629 * jd, 360.0)
        val ha = PyMath.radians(PyMath.mod(gmst + lon, 360.0)) - ra
        val la = PyMath.radians(lat)
        val el = PyMath.asin(PyMath.sin(la) * PyMath.sin(dec) + PyMath.cos(la) * PyMath.cos(dec) * PyMath.cos(ha))
        val az = PyMath.atan2(-PyMath.sin(ha), PyMath.tan(dec) * PyMath.cos(la) - PyMath.sin(la) * PyMath.cos(ha))
        return PyMath.mod(PyMath.degrees(az), 360.0) to PyMath.degrees(el)
    }

    val MATERIAL: Map<String, String> = linkedMapOf(
        "brick" to "#9C5A45", "stone" to "#CFC6B4", "copper" to "#5E9E86", "glass" to "#A9C2D6", "concrete" to "#BDBAB2", "wood" to "#9C7A55",
        "roof_tiles" to "#A5553C", "slate" to "#56606A", "metal" to "#9AA3AB", "plaster" to "#E6E0D4", "sandstone" to "#D8C49A",
    )
    /** Neutral: the surface exists, its look is not mapped. */
    const val CLAY = "#E4E0D8"
    val YEAR_RAMP: List<Pair<Int, String>> = listOf(1600 to "#5B2A86", 1700 to "#8E3B8F", 1800 to "#C4507A", 1900 to "#E8805A", 1950 to "#F2B45A", 2000 to "#F6E27A")

    // render/amsterdam.py:46 year_colour
    fun yearColour(y: Int?): String? {
        if (y == null) return null
        for (i in 0 until YEAR_RAMP.size - 1) {
            val (y0, c0) = YEAR_RAMP[i]; val (y1, c1) = YEAR_RAMP[i + 1]
            if (y <= y1) return tohex(mix(hx(c0), hx(c1), clamp((y - y0).toDouble() / (y1 - y0))))
        }
        return YEAR_RAMP.last().second
    }

    /** Where `load()` reads Amsterdam when no path is given (amsterdam.py: `../data/ams/raw.json`). */
    var amsRaw: File = File("data/ams/raw.json")

    // ------------------------------------------------------------------ small Python-like helpers
    @Suppress("UNCHECKED_CAST")
    internal fun tagsOf(o: Map<String, Any?>): Map<String, Any?> = o["tags"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    internal fun polyOf(o: Map<String, Any?>): List<V2> = (o["poly"] as List<Any?>).let { p -> if (p.all { it is V2 }) p as List<V2> else v2s(p) }

    @Suppress("UNCHECKED_CAST")
    internal fun ptsOf(o: Map<String, Any?>): List<V2> = (o["pts"] as List<Any?>).let { p -> if (p.all { it is V2 }) p as List<V2> else v2s(p) }

    @Suppress("UNCHECKED_CAST")
    private fun dicts(v: Any?): List<Dict> = (v as List<Dict>?) ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun rings(v: Any?): List<List<V2>> = (v as List<List<V2>>?) ?: emptyList()

    private fun d(v: Any?): Double = pyNum(v)
    private fun s(v: Any?): String? = v as String?
    internal fun dist(a: V2, b: V2): Double = PyMath.dist(doubleArrayOf(a.x, a.y), doubleArrayOf(b.x, b.y))
    private fun dist3(a: V3, b: V3): Double = PyMath.dist(doubleArrayOf(a.x, a.y, a.z), doubleArrayOf(b.x, b.y, b.z))
    private fun hypot(x: Double, y: Double): Double = PyMath.hypot(x, y)
    private fun pmax(a: Double, b: Double): Double = PyMath.max(a, b)
    private fun pmin(a: Double, b: Double): Double = PyMath.min(a, b)
    private fun neg(v: V3): V3 = V3(-v.x, -v.y, -v.z)

    /** `tuple(sum(p[k] for p in q) / len(q) for k in range(3))`. */
    private fun cen(q: List<V3>): V3 = V3(PyMath.sumOf(q) { it.x } / q.size, PyMath.sumOf(q) { it.y } / q.size, PyMath.sumOf(q) { it.z } / q.size)

    /** Python `len(s)`: code points, not UTF-16 units. */
    private fun plen(s: String): Int = s.codePointCount(0, s.length)

    /** `max(range(n), key=lambda i: dist(poly[i], poly[i + 1]))`: the first longest side. */
    private fun longestFirst(poly: List<V2>): Int {
        val n = poly.size; var best = 0; var bd = dist(poly[0], poly[1 % n])
        for (i in 1 until n) {
            val dd = dist(poly[i], poly[(i + 1) % n])
            if (dd > bd) { bd = dd; best = i }
        }
        return best
    }

    /** `max((dist(poly[i], poly[i + 1]), i) for i in range(n))[1]`: the longest side, the last of equals. */
    private fun longestLast(poly: List<V2>): Int {
        val n = poly.size; var best = 0; var bd = dist(poly[0], poly[1 % n])
        for (i in 1 until n) {
            val dd = dist(poly[i], poly[(i + 1) % n])
            if (dd >= bd) { bd = dd; best = i }
        }
        return best
    }

    private val WEEK = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

    @Suppress("UNCHECKED_CAST")
    private fun ints(v: Any?): IntArray = when (v) {
        is IntArray -> v
        is List<*> -> IntArray(v.size) { (v[it] as Number).toInt() }
        else -> throw IllegalArgumentException("not a tuple of ints: $v")
    }

    /** `['Mo', …][datetime.date(*date).weekday()]`. */
    private fun dayOf(date: Any?): String { val t = ints(date); return WEEK[LocalDate.of(t[0], t[1], t[2]).dayOfWeek.value - 1] }

    /** `open_at(oh, day, th['hhmm'], date=th['date']) is True`. */
    private fun openNow(oh: String, th: Map<String, Any?>): Boolean = Real.openAt(oh, dayOf(th["date"]), ints(th["hhmm"]), ints(th["date"])) == true

    /** `re.match(r'\s*(\d{4})', s)` read as an int, or null. */
    private fun leadingYear(s: String): Int? {
        var i = 0
        while (i < s.length && PyRe.isSpace(s[i])) i++
        if (i + 4 > s.length) return null
        for (k in i until i + 4) if (!Character.isDigit(s[k])) return null
        return PyRe.asciiDigits(s.substring(i, i + 4)).toInt()
    }

    /** `re.match(r'\d+', s)` read as an int, or null. */
    private fun leadingInt(s: String): Long? {
        var i = 0
        while (i < s.length && Character.isDigit(s[i])) i++
        if (i == 0) return null
        return java.math.BigInteger(PyRe.asciiDigits(s.substring(0, i))).toLong()
    }

    // ------------------------------------------------------------------ the shadows of the buildings, on their dicts
    /** A building dict as [Real.Prism] for [Real.Shadows]: `bb` and `_rf` are written into the dict, as the Python stores them. */
    class DictPrism(val d: Dict) : Real.Prism {
        override val poly: List<V2> = polyOf(d)
        override val h: Double = pyNum(d["h"])
        override var bb: DoubleArray?
            get() = d["bb"] as DoubleArray?
            set(v) { d["bb"] = v }
        override val roofH: Double? get() = (d["roof_h"] as Number?)?.toDouble()
        override val roof: String? get() = d["roof"] as String?
        override val orient: String? get() = d["orient"] as String?
        override var rf: DoubleArray?
            get() = d["_rf"] as DoubleArray?
            set(v) { d["_rf"] = v }
    }

    /** `Shadows(solids, L)` over building dicts: `skip` is a dict, compared by identity as Python's `is`. */
    class Shade private constructor(private val prisms: IdentityHashMap<Dict, DictPrism>, val shadows: Real.Shadows) {
        constructor(solids: List<Dict>, L: V3) : this(Grid(solids), L)
        private constructor(g: Grid, L: V3) : this(g.prisms, Real.Shadows(g.index, L))

        /** The sun-free half: a prism per solid, and the shadows' grid over them. */
        class Grid(solids: List<Dict>) {
            val prisms = IdentityHashMap<Dict, DictPrism>()
            val index = Real.Shadows.Index(solids.map { b -> DictPrism(b).also { prisms[b] = it } })
        }

        companion object {
            /** `Shadows(solids, L)` for the scene [S]: the grid is kept for the scene while the solids stay as they were. */
            fun of(S: Any, solids: List<Dict>, L: V3): Shade {
                val g = Real.ShadowIndexes.get(S,
                    { Array(solids.size * 3) { k -> val b = solids[k / 3]; when (k % 3) { 0 -> b; 1 -> b["poly"]; else -> b["bb"] } } },
                    { DoubleArray(solids.size) { pyNum(solids[it]["h"]) } }) { Grid(solids) }
                return Shade(g, L)
            }

            /**
             * Test-only: when set, hears every answer of [lit] (the vector engine's sun parity, `MT/sun/SunParityTest`,
             * plan-vector K8). The app never sets it; unset, [lit] pays one null check and nothing else. Set it only around
             * a render, on the thread that renders.
             */
            @JvmField
            var tap: Tap? = null
        }

        /** What [tap] hears: one answer of [lit], its point, the building the march leaves out, and the answer. */
        fun interface Tap {
            fun answer(p: V3, skip: Dict?, lit: Boolean)
        }

        /** `sh.lit(p, skip=b)`. */
        fun lit(p: V3, skip: Dict? = null): Boolean {
            val lit = shadows.lit(p, skip?.let { prisms[it] })
            tap?.answer(p, skip, lit)
            return lit
        }

        /** `sh.shadow_height(x, z, skip=b)`. */
        fun shadowHeight(x: Double, z: Double, skip: Dict? = null): Double = shadows.shadowHeight(x, z, skip?.let { prisms[it] })
    }

    // render/amsterdam.py:597 LiftCam
    /** The camera, for one thing standing on real terrain: every point it is given is raised by the ground's height there. */
    class LiftCam(val base: Camera, val dy: Double) :
        Camera(base.target, base.yaw, base.pitch, base.dist, base.fov, base.W, base.H, base.cx, base.cy, base.near) {
        override fun cam(p: V3): V3 = base.cam(V3(p.x, p.y + dy, p.z))
    }

    // render/amsterdam.py:607 lift
    fun lift(cam: Camera, o: Any?): Camera = if (o is Map<*, *> && truthy(o["gy"])) LiftCam(cam, pyNum(o["gy"])) else cam

    /**
     * Test-only: when set, hears every [face] call's world points, fill and extra attributes before the camera takes them
     * (the vector engine's stage-T parity, `MT/buildings/StageTParityTest`, plan-vector K10). The app never sets it; unset,
     * [face] pays one null check and nothing else. Set it only around a render, on the thread that renders.
     */
    @JvmField
    var faceTap: FaceTap? = null

    /** What [faceTap] hears: one [face] call's points, its fill and its extra attributes, as given. */
    fun interface FaceTap {
        fun face(q: List<V3>, fill: String, extra: String)
    }

    // render/amsterdam.py:610 face
    fun face(cam: Camera, q: List<V3>, fill: String, extra: String = ""): String {
        faceTap?.face(q, fill, extra)
        val sp = cam.poly(q, margin = 300.0)
        if (sp == null || area(sp) < 0.12) return ""
        return pf("<path d=\"%s\" fill=\"%s\"%s/>", P(sp), fill, extra)
    }

    // ------------------------------------------------------------------ load
    // render/amsterdam.py:55 load
    /** The scene from an Overpass extract (the Westerkerk one when [rawPath] is null): buildings, parts, water, roads, rails, areas, trees, nodes, places, labels. */
    @Suppress("UNCHECKED_CAST")
    fun load(rawPath: File? = null, opts: RenderOptions = RenderOptions()): Dict {
        val raw = RawSource.elements(rawPath ?: amsRaw)
        val buildings = ArrayList<Dict>(); val parts = ArrayList<Dict>(); val water = ArrayList<List<V2>>(); val roads = ArrayList<Dict>()
        val rails = ArrayList<List<V2>>(); val areas = ArrayList<Dict>(); val trees = ArrayList<Dict>(); val nodes = ArrayList<Dict>()
        val pois = ArrayList<Dict>(); val labels = ArrayList<Dict>()
        val S: Dict = dictOf("buildings" to buildings, "parts" to parts, "water" to water, "roads" to roads, "rails" to rails, "areas" to areas,
            "trees" to trees, "nodes" to nodes, "pois" to pois, "labels" to labels)
        fun geom(g: Any?): List<V2> = (g as List<Map<String, Any?>>).map { XZ(d(it["lat"]), d(it["lon"])) }
        fun rings(e: Map<String, Any?>): List<List<V2>> {
            if (e["type"] == "way" && "geometry" in e) return listOf(geom(e["geometry"]))
            if (e["type"] == "relation") {
                return ((e["members"] as List<Map<String, Any?>>?) ?: emptyList()).filter { it["role"] == "outer" && truthy(it["geometry"]) }.map { geom(it["geometry"]) }
            }
            return emptyList()
        }
        fun closed(r: List<V2>): Boolean = r.size > 3 && dist(r[0], r[r.size - 1]) < 0.5
        fun clean(r0: List<V2>): List<V2> {
            val r = if (closed(r0)) r0.subList(0, r0.size - 1).toList() else r0
            return if (signedArea(r) > 0) r else r.asReversed().toList()
        }
        val W = linkedMapOf("tertiary" to 7.5, "secondary" to 9.0, "residential" to 5.5, "unclassified" to 5.0, "living_street" to 4.5, "pedestrian" to 5.0,
            "busway" to 6.0, "service" to 3.5, "cycleway" to 2.4, "footway" to 2.0, "path" to 1.6, "steps" to 2.0)
        for (e in raw) {
            val t = e["tags"] as Map<String, Any?>?
            if (t.isNullOrEmpty()) continue
            val type = e["type"]
            val id = (e["id"] as Number).toLong()
            if (("building" in t || "building:part" in t) && type != "node" && t["building"] != "no") {
                for (r0 in rings(e)) {
                    if (!closed(r0) || Math.abs(signedArea(r0)) < 3) continue
                    val r = clean(r0)
                    var h = Real.num(t["height"]); val lv = Real.num(t["building:levels"])
                    val src = if (h != null) "height" else (if (lv != null) "levels" else null)
                    if (h == null && lv != null) h = lv * 3.0 + Real.num(t["roof:levels"], 0.0)!! * 2.0
                    val y = leadingYear((t["start_date"] as String?) ?: "")
                    val rec: Dict = dictOf("poly" to r, "h" to h, "min" to Real.num(t["min_height"], 0.0), "src" to src, "year" to y, "tags" to t, "id" to id,
                        "roof" to t["roof:shape"], "roof_h" to Real.num(t["roof:height"]), "orient" to t["roof:orientation"],
                        "mat" to t["building:material"], "rmat" to t["roof:material"], "col" to t["building:colour"], "rcol" to t["roof:colour"])
                    (if ("building:part" in t) parts else buildings).add(rec)
                }
                continue
            }
            if ((t["natural"] == "water" || t["waterway"] in WATERWAYS) && type != "node") {
                for (r in rings(e)) if (closed(r)) water.add(clean(r))
                if (truthy(t["name"]) && t["waterway"] == "canal" && type == "way") {
                    val g = if (e["geometry"] != null) geom(e["geometry"]) else emptyList()
                    if (!closed(if (g.isNotEmpty()) g else listOf(V2(0.0, 0.0)))) labels.add(dictOf("pts" to geom(e["geometry"]), "name" to t["name"], "kind" to "canal"))
                }
                continue
            }
            if (type == "way" && "highway" in t && "geometry" in e) {
                val pts = geom(e["geometry"])
                val hw = t["highway"] as String
                val w0 = W[hw] ?: continue
                if (pts[0] == pts[pts.size - 1] && (t["area"] == "yes" || hw == "pedestrian")) {
                    areas.add(dictOf("poly" to (if (pts.size > 3) clean(pts + listOf(pts[0])) else pts), "kind" to "pedestrian", "tags" to t))
                } else {
                    val w = Real.num(t["width"], w0)!!
                    roads.add(dictOf("pts" to pts, "w" to w, "hw" to hw, "tags" to t, "bridge" to truthy(t["bridge"]), "layer" to Real.num(t["layer"], 0.0), "id" to id,
                        "wguess" to !truthy(t["width"])))
                    if (truthy(t["name"]) && hw in NAMED_STREETS) {
                        labels.add(dictOf("pts" to pts, "name" to t["name"], "kind" to "street"))
                    }
                }
                continue
            }
            if (type == "way" && t["railway"] == "tram" && "geometry" in e) {
                rails.add(geom(e["geometry"])); continue
            }
            if (type != "node" && (t["leisure"] in GREEN_LEISURE || t["landuse"] == "grass" || t["man_made"] == "bridge" ||
                    t["amenity"] == "parking" || t["place"] == "square" || t["historic"] == "memorial")
            ) {
                for (r in rings(e)) {
                    if (closed(r)) {
                        val kind = listOf(t["leisure"], t["landuse"], t["man_made"], t["amenity"], t["place"]).firstOrNull { truthy(it) } ?: "memorial"
                        areas.add(dictOf("poly" to clean(r), "kind" to kind, "tags" to t))
                    }
                }
                continue
            }
            if (type == "node") {
                val xz = XZ(d(e["lat"]), d(e["lon"]))
                val n: Dict = dictOf("x" to xz.x, "z" to xz.y, "tags" to t, "id" to id)
                if (t["natural"] == "tree") trees.add(n)
                nodes.add(n)
                if ("shop" in t || t["amenity"] in SHOP_AMENITIES ||
                    t["tourism"] in SHOP_TOURISM
                ) pois.add(n)
            }
        }
        guessTags(S)
        if ((opts.kit || opts.looks) && rawPath == null) {
            // the tradition (render/traditions/nl_amsterdam.json) and the shared kit, or looks from each building's tags
            val lu = areas.filter { truthy(it["tags"]) && (tagsOf(it)["landuse"] as? String)?.let { k -> k in Generic.LANDUSE_KIND } == true && polyOf(it).size > 2 }
                .map { tagsOf(it)["landuse"] as String to polyOf(it) }
            val landuseAt: (Dict) -> String? = { b ->
                val c = centroid(polyOf(b))
                lu.firstOrNull { (_, poly) -> pip(c.x, c.y, poly) }?.first
            }
            if (opts.kit) {
                S["tradition"] = "nl_amsterdam"
                Generic.apply(S, "ams", landuseAt)
            } else {
                Generic.apply(S, "ams", landuseAt, keepHomes = true)
            }
        }
        val grid = GridMap<MutableList<Dict>>()
        fun key(gx: Long, gz: Long): Long = GridMap.key(gx, gz)
        for (b in buildings + parts) {
            val poly = polyOf(b)
            val xs = poly.map { it.x }; val zs = poly.map { it.y }
            val bb = doubleArrayOf(City.pyMinOf(xs), City.pyMaxOf(xs), City.pyMinOf(zs), City.pyMaxOf(zs))
            b["_bb"] = bb
            for (gx in PyMath.floorDiv(bb[0], 40.0).toLong()..PyMath.floorDiv(bb[1], 40.0).toLong()) {
                for (gz in PyMath.floorDiv(bb[2], 40.0).toLong()..PyMath.floorDiv(bb[3], 40.0).toLong()) grid.getOrPut(key(gx, gz)) { ArrayList() }.add(b)
            }
        }
        for (n in nodes) {
            val st = PoiStyle.style(tagsOf(n)) ?: continue
            n["style"] = st
            val x = d(n["x"]); val z = d(n["z"])
            for (b in grid[key(PyMath.floorDiv(x, 40.0).toLong(), PyMath.floorDiv(z, 40.0).toLong())] ?: emptyList<Dict>()) {
                val bb = b["_bb"] as DoubleArray
                if (bb[0] <= x && x <= bb[1] && bb[2] <= z && z <= bb[3] && "building:part" !in tagsOf(b) && pip(x, z, polyOf(b))) {
                    (b.getOrPut("places") { ArrayList<Dict>() } as MutableList<Dict>).add(n); n["_b"] = b; break
                }
            }
        }
        // an outline with parts inside is represented by its parts
        val pc = parts.map { centroid(polyOf(it)) }
        for (b in buildings) {
            val bc = centroid(polyOf(b)); val poly = polyOf(b)
            b["hidden"] = pc.any { c -> Math.abs(c.x - bc.x) < 80 && Math.abs(c.y - bc.y) < 80 && pip(c.x, c.y, poly) }
        }
        return S
    }

    val BRICK_OLD: MutableList<String> = mutableListOf("#6E3B2E", "#7A4434", "#5C3A30", "#8A5040", "#6A4A3C", "#7E5242")
    val PLASTER: MutableList<String> = mutableListOf("#E8E2D4", "#EFE9DC", "#D9D2C2")
    val BRICK_NEW: MutableList<String> = mutableListOf("#9C6A52", "#8F7F74", "#B08A6A", "#8C8A86", "#A9876E")

    // render/amsterdam.py:158 guess_tags
    /** Generic values for missing OSM tags, recorded per object as guessed. */
    fun guessTags(S: Dict) {
        for (b in dicts(S["buildings"]) + dicts(S["parts"])) {
            val g = LinkedHashMap<String, Any?>()
            val rnd = PyRandom(b["id"])
            val poly = polyOf(b); val t = tagsOf(b)
            val edges = (poly.indices).map { i -> dist(poly[i], poly[(i + 1) % poly.size]) }.toMutableList().also { it.pySortBy { v -> v } }
            val narrow = if (edges.size >= 3) edges[0] < 11 else false
            val old = ((b["year"] as Number?)?.toInt().takeIf { it != null && it != 0 } ?: 1900) < 1900
            if (b["h"] == null) {
                g["height"] = true
                b["h"] = if (t["building"] != "houseboat") 10.0 else 2.5
            }
            if ("building:part" !in t) {
                if (t["building:levels"] == null) g["building:levels"] = true
                if (!truthy(b["roof"])) {
                    g["roof:shape"] = true
                    b["roof"] = if (old && narrow && t["building"] !in NEVER_GABLED && d(b["h"]) > 8) "gabled" else "flat"
                    if (b["roof"] == "gabled") {
                        b["roof_h"] = pmin(5.0, pmax(3.0, edges[0] * 0.55)); g["roof:height"] = true
                        b["orient"] = "along"
                    }
                }
                if (!truthy(b["col"]) && !truthy(b["mat"])) {
                    g["building:colour"] = true
                    val r = rnd.random()
                    b["col"] = if (t["building"] == "houseboat") rnd.choice(listOf("#2F4A3A", "#3A4A6A", "#6A3A2E", "#E8E2D4"))
                    else if (old) (if (r < 0.18) rnd.choice(PLASTER) else rnd.choice(BRICK_OLD))
                    else rnd.choice(BRICK_NEW)
                    b["mat"] = null
                    if (t["building"] != "houseboat") { // the colour comes with a material: brick, or plaster for the pale ones
                        b["mat_draw"] = if (b["col"] in PLASTER) "plaster" else "brick"; g["building:material"] = true
                    }
                }
                if (!truthy(b["rcol"]) && !truthy(b["rmat"])) {
                    g["roof:colour"] = true
                    b["rcol"] = if (b["roof"] == "gabled") rnd.choice(listOf("#4A3E3A", "#56504C", "#3E4248")) else rnd.choice(listOf("#7E7A74", "#8C8882", "#6E6A66"))
                }
            }
            if (b["roof"] in ROOFS_WITH_HEIGHT && !truthy(b["roof_h"])) {
                val rl = Real.num(t["roof:levels"])
                b["roof_h"] = pmin(d(b["h"]) * 0.4, if (rl != null && rl != 0.0) rl * 2.8 else pmax(2.0, pmin(5.0, edges[0] * 0.45))); g["roof:height"] = true
            }
            if (b["roof"] == "skillion" && !truthy(t["roof:direction"])) g["roof:direction"] = true
            b["levels"] = if (truthy(t["building:levels"])) PyMath.int(Real.num(t["building:levels"])!!).toInt()
            else Math.max(1L, PyMath.int(PyMath.round((d(b["h"]) - ((b["roof_h"] as Number?)?.toDouble().takeIf { it != null && it != 0.0 } ?: 0.0)) / 3.1).toDouble())).toInt()
            b["guessed"] = g
            if (truthy(b["mat"]) && !truthy(b["mat_draw"])) b["mat_draw"] = b["mat"]
        }
        for (n in dicts(S["trees"])) {
            val t = tagsOf(n)
            n["guessed"] = LinkedHashMap<String, Any?>().also { g -> for (k in listOf("height", "diameter_crown", "leaf_type")) if (k !in t) g[k] = true }
            n["h"] = Real.num(t["height"], 12.0); n["crown"] = Real.num(t["diameter_crown"], 7.0)
        }
    }

    // ------------------------------------------------------------------ themes and the real weather
    private fun theme(vararg p: Pair<String, Any?>): Dict = dictOf(*p)

    /** Canal water sits below the quay; a generic detail, as no tag gives water level here. */
    var WATER_Y = -1.5
    /** Amsterdam's brick quays; other regions set their own. */
    var QUAY_COL = "#6E5546"
    /** The quay wall's courses up close: brick here, stone blocks in Paris, none (null) for concrete. */
    var QUAY_COURSE: Double? = 0.0625
    /** A soft shoulder on highlights, so pale stone in sun stays cream instead of clipping to white. */
    var TONE = false
    /** 'paris': stone fronts get tall French windows in bays of about 2.9 m, dark glass. */
    var WIN_STYLE: String? = null

    /** Open-Meteo's hourly model for the place (data/wx/ams.json for Westermarkt; regions set their own). */
    @Suppress("UNCHECKED_CAST")
    var WXD: Map<String, Any?> = (PyJson.loads(
        Amsterdam::class.java.getResourceAsStream("/wx/ams.json")!!.use { it.readBytes().toString(Charsets.UTF_8) },
    ) as Map<String, Any?>)["hourly"] as Map<String, Any?>

    // render/amsterdam.py:218 wx_at
    /** The weather of the hour, interpolated to the minute. */
    @Suppress("UNCHECKED_CAST")
    fun wxAt(date: List<Int>, hh: Int, mm: Int): Dict {
        val time = WXD["time"] as List<String>
        val i = time.indexOf(pf("%04d-%02d-%02dT%02d:00", date[0], date[1], date[2], hh))
        require(i >= 0) { "no weather for $date $hh:00" }
        val j = Math.min(i + 1, time.size - 1); val f = mm / 60.0
        fun g(k: String): Double { val v = WXD[k] as List<Any?>; return d(v[i]) * (1 - f) + d(v[j]) * f }
        val n = if (f < 0.5) i else j
        return dictOf("cloud" to g("cloud_cover") / 100, "low" to g("cloud_cover_low") / 100, "rain" to g("precipitation"), "wind" to g("wind_speed_10m"),
            "gust" to g("wind_gusts_10m"), "wind_dir" to (WXD["wind_direction_10m"] as List<Any?>)[n], "temp" to g("temperature_2m"), "pressure" to g("pressure_msl"),
            "vis" to g("visibility"), "hum" to g("relative_humidity_2m"), "code" to (WXD["weather_code"] as List<Any?>)[n])
    }

    // render/amsterdam.py:224 weather_theme
    /** A moment's light, sky, water and rain from the real weather (overcast, sun between clouds, or night). */
    fun weatherTheme(name: String, date: List<Int>, hhmm: List<Int>, daylight: Boolean = false): Dict {
        val w = wxAt(date, hhmm[0], hhmm[1])
        val (_, el) = sunPosition(LocalDateTime.of(date[0], date[1], date[2], hhmm[0], hhmm[1]).minusHours(TZ_H.toLong()))
        val night = el < -2
        val th = theme("name" to name, "date" to date, "hhmm" to hhmm, "night" to night, "wx" to w)
        val cloud = d(w["cloud"])
        if (night) {
            val wet = d(w["rain"]) > 0.05
            th.putAll(theme("sky_top" to "#050814", "sky_hor" to "#1C2340", "haze" to "#1A2238", "haze_max" to 0.5, "sun" to "#000000", "sun_k" to 0.0, "amb" to "#3A4668", "amb_k" to 0.5,
                "ground" to (if (wet) "#0D1018" else "#12151F"), "road" to (if (wet) "#131824" else "#1B1F2C"), "water" to "#070C14", "shadow" to "#000", "shadow_op" to 0.0,
                "water_top" to "#131B2A", "water_bot" to "#03060B"))
        } else if (cloud >= 0.85) { // overcast: light from the whole sky, shadows soft and faint
            th.putAll(theme("sky_top" to "#9EA8B4", "sky_hor" to "#C9CED4", "haze" to "#CDD3DA", "haze_max" to 0.4, "sun" to "#FFF6EA", "sun_k" to 0.14, "amb" to "#B4BCC8", "amb_k" to 0.84, "sky_k" to 1.7,
                "ground" to "#C9C4BA", "road" to "#BBB5AB", "water" to "#6E7C7C", "shadow" to "#353B4C", "shadow_op" to 0.16, "shadow_blur" to 2.6,
                "water_top" to "#AEB7BC", "water_bot" to "#4B5A57"))
        } else { // sun between clouds: hard shadows, and the clouds' own shadows drifting over
            th.putAll(theme("sky_top" to "#7FA6DA", "sky_hor" to "#DCE7F2", "haze" to "#DFE6EE", "haze_max" to 0.22, "sun" to "#FFEBCB", "sun_k" to 1.0, "amb" to "#93A6CC", "amb_k" to 0.6,
                "ground" to "#D6CFC2", "road" to "#C9C1B4", "water" to "#4F7590", "shadow" to "#27305A", "shadow_op" to 0.32, "shadow_blur" to 0.6,
                "water_top" to "#A6BFD4", "water_bot" to "#2D506C", "cloud_shadows" to (if (cloud > 0.15) cloud else 0)))
        }
        th["wet"] = d(w["rain"]) > 0.05
        th["haze_max"] = d(th["haze_max"]) * (if (d(w["vis"]) < 20000) 1.2 else 0.85)
        if (daylight && !night && el < 8) {
            // the light of the hour falls with the sun: from 8 degrees down to the start of night everything dims and cools
            val f = PyMath.pow(pmin(1.0, pmax(0.0, (8 - el) / 10.0)), 1.3)
            val dusk = linkedMapOf("sky_top" to "#1E2A48", "sky_hor" to "#5A6584", "haze" to "#4A5470", "amb" to "#56607E", "ground" to "#565660", "road" to "#4C4D57",
                "water" to "#1E2A3C", "water_top" to "#3C4860", "water_bot" to "#101824")
            for ((k, v) in dusk) if (k in th) th[k] = tohex(mix(hx(th[k] as String), hx(v), f))
            th["amb_k"] = d(th["amb_k"]) * (1 - 0.45 * f); th["sun_k"] = d(th["sun_k"]) * (1 - 0.8 * f)
            if ("sky_k" in th) th["sky_k"] = d(th["sky_k"]) * (1 - 0.55 * f)
            th["shadow_op"] = d(th["shadow_op"]) * (1 - 0.6 * f)
            th["_dusk"] = PyMath.round(f, 2)
        }
        return th
    }

    // render/amsterdam.py:202 THEMES
    /** The moments: three fixed ones, and grey, sunny and wet from the real weather (regions replace those and add their own). */
    val THEMES: LinkedHashMap<String, Dict> = LinkedHashMap()

    /** Builds [THEMES] afresh, as amsterdam.py does at import (with ATLAS_DAYLIGHT as [daylight]). */
    fun resetThemes(daylight: Boolean = false) {
        THEMES.clear()
        THEMES["golden"] = theme("name" to "golden", "date" to listOf(2026, 9, 30), "hhmm" to listOf(17, 20), "night" to false, "sky_top" to "#86A7D6", "sky_hor" to "#F6D9B8",
            "haze" to "#F3DCC0", "haze_max" to 0.3, "sun" to "#FFD9A6", "sun_k" to 1.0, "amb" to "#8FA0C8", "amb_k" to 0.62, "ground" to "#E9E4DA", "road" to "#CFC8BC",
            "water" to "#7FA7C7", "shadow" to "#27305A", "shadow_op" to 0.34)
        THEMES["noon"] = theme("name" to "noon", "date" to listOf(2026, 9, 30), "hhmm" to listOf(13, 30), "night" to false, "sky_top" to "#7FA6DA", "sky_hor" to "#DCE7F2",
            "haze" to "#DDE6EE", "haze_max" to 0.4, "sun" to "#FFF3DE", "sun_k" to 0.92, "amb" to "#9AAAD0", "amb_k" to 0.64, "ground" to "#ECE8E0", "road" to "#D2CCC2",
            "water" to "#84AED0", "shadow" to "#2B3560", "shadow_op" to 0.3)
        THEMES["night"] = theme("name" to "night", "date" to listOf(2026, 9, 30), "hhmm" to listOf(21, 30), "night" to true, "sky_top" to "#050814", "sky_hor" to "#1C2340",
            "haze" to "#161D36", "haze_max" to 0.5, "sun" to "#000000", "sun_k" to 0.0, "amb" to "#39456B", "amb_k" to 0.5, "ground" to "#12151F", "road" to "#1B1F2C",
            "water" to "#0B1422", "shadow" to "#000", "shadow_op" to 0.0)
        THEMES["grey"] = weatherTheme("grey", listOf(2026, 10, 1), listOf(13, 30), daylight)
        THEMES["sunny"] = weatherTheme("sunny", listOf(2026, 10, 1), listOf(16, 0), daylight)
        THEMES["wet"] = weatherTheme("wet", listOf(2026, 9, 30), listOf(21, 30), daylight)
    }

    init {
        resetThemes()
    }

    val SURF: Map<String, String> = linkedMapOf(
        "paving_stones" to "#C9B9A6", "sett" to "#A89A88", "asphalt" to "#9B9A99", "concrete" to "#C4C2BC", "wood" to "#A88A62", "bricks" to "#B98870", "cobblestone" to "#A89A88",
    )

    // render/amsterdam.py:261 light
    /** A colour lit by the theme: ambient, the sky from above, and the sun where [lit]; at night the ambient alone; hazed by `_hz`. */
    fun light(th: Map<String, Any?>, base: V3, n: V3, lit: Boolean): V3 {
        val L = v3(th["_L"])
        val hz = th["_hz"]
        if (th["night"] == true) {
            val c = mulc(mix(base, hx(th["amb"] as String), 0.55), 0.4 + 0.08 * pmax(0.0, n[1]))
            return if (truthy(hz)) mix(c, hx(th["haze"] as String), d(hz)) else c
        }
        val ambK = d(th["amb_k"])
        val amb = mix(mulc(base, ambK), mulc(hx(th["amb"] as String), ambK * 0.55), if (TONE) 0.26 else 0.35)
        val lam = if (lit) pmax(0.0, vdot(n, L)) else 0.0
        val sky = (0.18 + 0.12 * pmax(0.0, n[1])) * (if ("sky_k" in th) d(th["sky_k"]) else 1.0)
        val sunK = d(th["sun_k"]); val sun = hx(th["sun"] as String)
        var c = V3(
            amb[0] + base[0] * sky + base[0] * lam * 1.05 * sunK * sun[0] / 255,
            amb[1] + base[1] * sky + base[1] * lam * 1.05 * sunK * sun[1] / 255,
            amb[2] + base[2] * sky + base[2] * lam * 1.05 * sunK * sun[2] / 255,
        )
        if (TONE) {
            fun t(v: Double) = if (v < 205) v else 205 + 50 * (1 - PyMath.exp(-(v - 205) / 50))
            c = V3(t(c.x), t(c.y), t(c.z))
        }
        return if (truthy(hz)) mix(c, hx(th["haze"] as String), d(hz)) else c
    }

    /** [light] as Closeup takes it. */
    val lightFn: LightFn = { th, base, n, lit -> light(th, base, n, lit) }

    /** Per-object aerial perspective at eye level, from the hour's visibility. */
    var AIR_HAZE = true

    // render/amsterdam.py:274 haze_at
    /** Aerial perspective at eye level: how much air lies between you and a thing [dd] metres away, from the real visibility of the hour. */
    fun hazeAt(th: Map<String, Any?>, dd: Double): Double {
        if (!truthy(th["_eye"]) || !truthy(th["wx"]) || !AIR_HAZE) return 0.0
        @Suppress("UNCHECKED_CAST") val wx = th["wx"] as Map<String, Any?>
        val ve = pmax(800.0, (if ("vis" in wx) d(wx["vis"]) else 20000.0) / 8)
        return pmin(0.75, 1 - PyMath.exp(-3.912 * pmax(0.0, dd - 15) / ve))
    }

    // render/amsterdam.py:280 hzc
    fun hzc(th: Map<String, Any?>, col: String): String = if (truthy(th["_hz"])) tohex(mix(hx(col), hx(th["haze"] as String), d(th["_hz"]))) else col

    /** Relief-lit clouds at height: switched off, the soft flat clouds read better. */
    var CLOUDS_LIT = false

    // render/amsterdam.py:284 sky_layer: ScreenLayers.Frame.skyLayer, from the render's ScreenLayers.of

    /** `Camera(cfg['target'], …, cx=cfg.get('cx'), cy=cfg.get('cy'))`. */
    fun cameraOf(cfg: Map<String, Any?>): Camera = Camera(
        v3(cfg["target"]), d(cfg["yaw"]), d(cfg["pitch"]), d(cfg["dist"]), d(cfg["fov"]), d(cfg["W"]), d(cfg["H"]),
        cx = (cfg["cx"] as Number?)?.toDouble(), cy = (cfg["cy"] as Number?)?.toDouble(),
    )

    /** `(az, el)` of a theme's moment, and the sun vector render() uses (`_L`): toward the sun, or straight down at night. */
    fun sunOf(th: Map<String, Any?>): Triple<Double, Double, V3> {
        val hm = ints(th["hhmm"]); val dt = ints(th["date"])
        val (az, el) = sunPosition(LocalDateTime.of(dt[0], dt[1], dt[2], hm[0], hm[1]).minusHours(TZ_H.toLong()))
        return Triple(az, el, if (el > 0) Living.sunVector(az, el) else V3(0.0, -1.0, 0.0))
    }

    // render/amsterdam.py:321 render_sky
    /** The sky for an eye-level view, 2W wide: gradient, clouds at height (tiling every W px), and at night the city's glow on the cloud. */
    fun renderSky(thn: String, cfg: Map<String, Any?>): String {
        val th = LinkedHashMap(THEMES.getValue(thn)); val W = d(cfg["W"]); val H = d(cfg["H"])
        th["_eye"] = true
        val cam = cameraOf(cfg)
        th["_L"] = sunOf(th).third
        val hy = cam.horizonY(); val defs = ArrayList<String>()
        defs.add(ScreenLayers.skyDef(hy, th["sky_top"], th["sky_hor"]))
        val body = arrayListOf(ScreenLayers.skyRect(2 * W, H), Closeup.skyClouds(cam, th, W, hy, defs, cam.C[1], tile = true))
        if (th["night"] == true) {
            defs.add(ScreenLayers.cityGlowDef(hy))
            body.add(pf("<rect width=\"%d\" height=\"%.1f\" fill=\"url(#cityglow)\"/>", 2 * W, pmin(H, hy + 2)))
        }
        return pf("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\"><defs>%s</defs>%s</svg>", 2 * W, H, 2 * W, H, defs.joinToString(""), body.joinToString(""))
    }

    // ------------------------------------------------------------------ water
    /** One water edge: from [a] to [b], [nx], [nz] the unit normal into the water, [r] the water ring it belongs to (an island's outer water). */
    class Edge(val a: V2, val b: V2, val nx: Double, val nz: Double, val r: Any)

    @Suppress("UNCHECKED_CAST")
    private fun idMap(v: Any?): Map<Any?, Any?> = (v as Map<Any?, Any?>?) ?: emptyMap()

    // render/amsterdam.py:337 water_edges
    /**
     * Every water edge with the unit normal that points into the water. Islands (holes in a river's outline, `water_holes`) have quays too,
     * facing out into the water around them. `natural_shore` (rings without a quay) and `_hole_of` are identity maps, as Python's `id(r)` keys.
     */
    fun waterEdges(S: Map<String, Any?>): List<Edge> {
        val out = EdgeList()
        val water = rings(S["water"])
        val shore = S["natural_shore"] as Collection<*>?
        val holeOf = idMap(S["_hole_of"])
        val all = water.map { it to false } + rings(S["water_holes"]).map { it to true }
        for ((r, hole) in all) {
            if (!hole && shore != null && shore.isNotEmpty() && shore.any { it === r }) continue // beaches and lake shores: no quay wall
            val n = r.size
            val last = if (r[0].x == r[n - 1].x && r[0].y == r[n - 1].y) n - 1 else n
            for (i in 0 until last) {
                val a = r[i]; val b = r[(i + 1) % n]
                val ex = b[0] - a[0]; val ez = b[1] - a[1]; val Lg = hypot(ex, ez)
                if (Lg < 0.4) continue
                var nx = ez / Lg; var nz = -ex / Lg
                val mx = (a[0] + b[0]) / 2; val mz = (a[1] + b[1]) / 2
                if (pip(mx + nx * 0.4, mz + nz * 0.4, r) == hole) { nx = -nx; nz = -nz } // into the water: inside a river, outside an island
                if (hole) {
                    if (water.any { o -> o !== r && Math.abs(o[0][0] - mx) < 4000 && !pip(mx - nx * 1.5, mz - nz * 1.5, r) && pip(mx - nx * 1.5, mz - nz * 1.5, o) }) continue
                    out.add(Edge(a, b, nx, nz, holeOf.entries.firstOrNull { it.key === r }?.value ?: r)); continue
                }
                if (shore != null && water.any { o -> o !== r && pip(mx - nx * 1.5, mz - nz * 1.5, o) }) continue // water on both sides: no wall
                out.add(Edge(a, b, nx, nz, r))
            }
        }
        return out
    }

    /**
     * The water's edges as [waterEdges] returns them, with a grid of [EdgeGrid.CELL] m cells over their boxes, built on the first
     * [nearWater] question (and again if the list has changed since), so a question reads only the edges near its points.
     */
    class EdgeList : ArrayList<Edge>() {
        private var grid: EdgeGrid? = null
        private var gridAt = -1

        /** The grid over the edges as they are now. */
        fun grid(): EdgeGrid {
            val g = grid
            if (g != null && gridAt == modCount) return g
            return EdgeGrid(this).also { grid = it; gridAt = modCount }
        }

        // ArrayList.set leaves modCount as it was: a replaced edge must drop the grid too
        override fun set(index: Int, element: Edge): Edge = super.set(index, element).also { grid = null }
    }

    /** Edges by the grid cells their boxes cover; an edge whose box covers more than [MAX_CELLS] cells is kept apart and always read. */
    class EdgeGrid(edges: List<Edge>) {
        private val all = ArrayList(edges)
        private val cells = GridMap<ArrayList<Edge>>(edges.size)
        private val big = ArrayList<Edge>()

        init {
            for (e in all) {
                val i0 = cellOf(pmin(e.a[0], e.b[0])); val i1 = cellOf(pmax(e.a[0], e.b[0]))
                val j0 = cellOf(pmin(e.a[1], e.b[1])); val j1 = cellOf(pmax(e.a[1], e.b[1]))
                if ((i1 - i0 + 1) * (j1 - j0 + 1) > MAX_CELLS) { big.add(e); continue }
                for (i in i0..i1) for (j in j0..j1) cells.getOrPut(GridMap.key(i, j)) { ArrayList() }.add(e)
            }
        }

        /**
         * True when [test] holds for an edge whose box meets x in [x0, x1] and z in [z0, z1], widened by [SLACK]: every edge the
         * box test in [nearWater] lets through is read (some more than once, which an any() does not mind). A box over more cells
         * than there are edges reads every edge instead.
         */
        inline fun any(x0: Double, x1: Double, z0: Double, z1: Double, test: (Edge) -> Boolean): Boolean {
            val i0 = cellOf(x0 - SLACK); val i1 = cellOf(x1 + SLACK); val j0 = cellOf(z0 - SLACK); val j1 = cellOf(z1 + SLACK)
            if (i1 >= i0 && j1 >= j0 && (i1 - i0 + 1) * (j1 - j0 + 1) > maxOf(MAX_CELLS.toLong(), allEdges().size.toLong())) {
                val es = allEdges()
                for (k in es.indices) if (test(es[k])) return true
                return false
            }
            val bs = bigEdges()
            for (k in bs.indices) if (test(bs[k])) return true
            for (i in i0..i1) for (j in j0..j1) {
                val list = cellAt(i, j) ?: continue
                for (k in list.indices) if (test(list[k])) return true
            }
            return false
        }

        /** Every edge, in the list's order. */
        fun allEdges(): List<Edge> = all

        /** The edges kept apart. */
        fun bigEdges(): List<Edge> = big

        /** The edges listed in cell (i, j), or null. */
        fun cellAt(i: Long, j: Long): List<Edge>? = cells[GridMap.key(i, j)]

        companion object {
            /** The cell side, metres. */
            const val CELL = 64.0

            /** An edge covering more cells than this is read on every question. */
            const val MAX_CELLS = 64

            /** Metres added around a question's box: far more than the rounding of the box's own arithmetic. */
            const val SLACK = 1.0

            /** The cell holding coordinate [v]; values far outside any region fall into the end cells. */
            fun cellOf(v: Double): Long = Math.floor(v / CELL).coerceIn(-1e9, 1e9).toLong()
        }
    }

    // render/amsterdam.py:359 near_water
    /** True when one of [pts] lies within [dd] metres of a water edge (an any(): which edge answers does not matter). */
    fun nearWater(edges: List<Edge>, pts: List<V2>, dd: Double): Boolean {
        var px0 = pts[0].x; var px1 = pts[0].x; var pz0 = pts[0].y; var pz1 = pts[0].y
        for (p in pts) {
            if (p.x > px1) px1 = p.x
            if (p.x < px0) px0 = p.x
            if (p.y > pz1) pz1 = p.y
            if (p.y < pz0) pz0 = p.y
        }
        val test = { e: Edge -> near(e, pts, dd, px0, px1, pz0, pz1) }
        if (edges is EdgeList && !(px0 - dd).isNaN() && !(px1 + dd).isNaN() && !(pz0 - dd).isNaN() && !(pz1 + dd).isNaN()) {
            return edges.grid().any(px0 - dd, px1 + dd, pz0 - dd, pz1 + dd, test)
        }
        for (e in edges) if (test(e)) return true
        return false
    }

    /** near_water's test of one edge: its box widened by [dd] meets the points' box, and a point is nearer than [dd]. */
    private fun near(e: Edge, pts: List<V2>, dd: Double, px0: Double, px1: Double, pz0: Double, pz1: Double): Boolean {
        val a = e.a; val b = e.b
        if (pmin(a[0], b[0]) - dd > px1 || pmax(a[0], b[0]) + dd < px0) return false
        if (pmin(a[1], b[1]) - dd > pz1 || pmax(a[1], b[1]) + dd < pz0) return false
        for (p in pts) {
            val ux = b[0] - a[0]; val uz = b[1] - a[1]; val L2 = (ux * ux + uz * uz).let { if (it == 0.0) 1.0 else it }
            val t = pmax(0.0, pmin(1.0, ((p[0] - a[0]) * ux + (p[1] - a[1]) * uz) / L2))
            if (hypot(a[0] + ux * t - p[0], a[1] + uz * t - p[1]) < dd) return true
        }
        return false
    }

    // render/amsterdam.py:369 on_water
    fun onWater(S: Map<String, Any?>, x: Double, z: Double): Boolean = rings(S["water"]).any { pip(x, z, it) }

    /** `lvl.get(id(r), default)`: a level keyed by the ring itself (compared by identity). */
    private fun levelOf(lvl: Map<Any?, Any?>, r: Any?, default: Double): Double {
        for ((k, v) in lvl) if (k === r) return d(v)
        return default
    }

    // render/amsterdam.py:372 reflections
    /** The scene mirrored in the water plane: facades, gables, window rows and trees beside the canals. */
    fun reflections(S: Map<String, Any?>, cam: Camera, th: Dict, sh: Shade, far: (Double, Double) -> Boolean, edges: List<Edge>): List<String> {
        val WY = WATER_Y; val night = th["night"] == true; val L = v3(th["_L"]); val out = ArrayList<Pair<Double, String>>()
        for (b in dicts(S["buildings"]) + dicts(S["parts"])) {
            if (truthy(b["hidden"]) || !truthy(b["h"])) continue
            val poly = polyOf(b); val t = tagsOf(b)
            val c = centroid(poly); val cx = c.x; val cz = c.y
            if (far(cx, cz) || !nearWater(edges, poly, 16.0)) continue
            val boat = t["building"] == "houseboat"
            val y0 = if (boat) WY + 0.3 else d(b["min"]); val h = if (boat) (d(b["h"]) + WY + 0.3) else d(b["h"])
            val rh = if (b["roof"] in ROOFS_REFLECTED && truthy(b["roof_h"])) d(b["roof_h"]) else 0.0
            val top = h - rh
            val base = surfaceColour(b, null); val n = poly.size
            val dk = if (night) 0.5 else (if (truthy(th["sky_k"])) 0.62 else 0.74) // facades read darker than the bright sky the water mirrors
            val c3 = cam.cam(V3(cx, 2 * WY - (y0 + h) / 2, cz))
            if (c3[2] < 2) continue
            th["_hz"] = hazeAt(th, c3[2])
            val wins = ArrayList<String>()
            for (i in 0 until n) {
                val a = poly[i]; val bb = poly[(i + 1) % n]
                val ex = bb[0] - a[0]; val ez = bb[1] - a[1]; val elen = hypot(ex, ez)
                if (elen < 0.3) continue
                val nrm = vnorm(V3(ez, 0.0, -ex))
                val mid = V3((a[0] + bb[0]) / 2, 2 * WY - (y0 + top) / 2, (a[1] + bb[1]) / 2)
                if (vdot(nrm, vsub(cam.C, mid)) <= 0) continue
                val lit = !night && vdot(nrm, L) > 0 && sh.lit(V3((a[0] + bb[0]) / 2 + nrm[0] * .2, (y0 + top) / 2, (a[1] + bb[1]) / 2 + nrm[2] * .2), skip = b)
                val col = tohex(mulc(light(th, base, nrm, lit), dk))
                val q = listOf(V3(a[0], 2 * WY - y0, a[1]), V3(bb[0], 2 * WY - y0, bb[1]), V3(bb[0], 2 * WY - top, bb[1]), V3(a[0], 2 * WY - top, a[1]))
                out.add(c3[2] to face(cam, q, col, pf(" stroke=\"%s\" stroke-width=\".3\"", col)))
                if (rh != 0.0 && b["roof"] == "gabled") { // a gable end mirrors as a downward triangle
                    val lx = longestLast(poly)
                    val ra = poly[lx]; val rb = poly[(lx + 1) % n]
                    var rx = rb[0] - ra[0]; var rz = rb[1] - ra[1]; val rl = hypot(rx, rz).let { if (it == 0.0) 1.0 else it }
                    if (b["orient"] == "across") { val t0 = rx; rx = -rz; rz = t0 }
                    if (Math.abs(ex * rx + ez * rz) / (elen * rl) < 0.35) {
                        val ap = V3(mid[0], 2 * WY - h, mid[2])
                        out.add(c3[2] - 0.01 to face(cam, listOf(q[3], q[2], ap), col))
                    }
                }
                if (!boat && "building:part" !in t && elen > 2.2 && c3[2] < 520) {
                    val lv = (b["levels"] as Number).toInt(); val fh = (top - y0) / Math.max(1, lv)
                    for (r_ in 0 until lv) {
                        val ya = y0 + r_ * fh + fh * 0.3; val yb = ya + fh * 0.48
                        val sp = cam.poly(Living.quadAt(a, bb, 2 * WY - yb, 2 * WY - ya, 0.08, 0.92, 0.04, nrm), margin = 300.0)
                        if (sp != null && area(sp) > 0.3) wins.add(P(sp))
                    }
                }
            }
            if (wins.isNotEmpty()) out.add(c3[2] - 0.02 to pf("<path d=\"%s\" fill=\"%s\" opacity=\".55\"/>", wins.joinToString(""), if (!night) "#2B3440" else "#05070C"))
        }
        for (t in dicts(S["trees"])) {
            val tx = d(t["x"]); val tz = d(t["z"])
            if (far(tx, tz) || !nearWater(edges, listOf(V2(tx, tz)), 9.0)) continue
            val c = cam.cam(V3(tx, 2 * WY - d(t["h"]) * 0.68, tz))
            if (c[2] < 2) continue
            if (c[2] < 30) continue
            val p = cam.pc(c); val rr = d(t["crown"]) / 2 * 0.9 * cam.focal / c[2]
            val col = if (!night) "#3E5A36" else "#0A0F0D"
            val rnd = PyRandom(t["id"])
            val sb = StringBuilder()
            repeat(5) {
                val x = p[0] + rnd.uniform(-.5, .5) * rr; val y = p[1] + rnd.uniform(-.4, .4) * rr; val r = rr * rnd.uniform(.45, .7)
                sb.append(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\"/>", x, y, r))
            }
            out.add(c[2] to pf("<g fill=\"%s\" filter=\"url(#leaf)\">%s</g>", col, sb.toString()))
        }
        out.pySortBy { -it.first }
        val res = out.mapTo(ArrayList()) { it.second }
        if (night) { // the lamps and the lit streets, as long streaks on the dark water
            val rnd = PyRandom(5)
            fun streak(x: Double, yl: Double, z: Double, k: Double) {
                val mx = x; val my = 2 * WY - yl; val mz = z
                val C = cam.C; val dy = my - C[1]
                if (dy >= 0) return
                val t = (WY - C[1]) / dy
                val sx = C[0] + (mx - C[0]) * t; val sz = C[2] + (mz - C[2]) * t
                if (!onWater(S, sx, sz)) return
                val c = cam.cam(V3(mx, my, mz))
                if (c[2] < 2) return
                val p = cam.pc(c); val s = cam.focal / c[2]
                if (!(-20 < p[0] && p[0] < cam.W + 20 && -40 < p[1] && p[1] < cam.H + 40)) return
                val j = rnd.random()
                val cx = p[0] + rnd.uniform(-1.0, 1.0) * s; val cy = p[1] + (3 + 3 * j) * s
                val rx = pmax(0.5, rnd.uniform(0.35, 0.8) * s); val ry = pmax(2.5, (4 + 6 * j) * s); val op = k * rnd.uniform(0.45, 1.0)
                res.add(pf("<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.2f\" ry=\"%.1f\" fill=\"url(#streak)\" opacity=\"%.2f\"/>", cx, cy, rx, ry, op))
            }
            for (nd in dicts(S["nodes"])) {
                if (tagsOf(nd)["highway"] == "street_lamp" && !far(d(nd["x"]), d(nd["z"]))) streak(d(nd["x"]), 4.5, d(nd["z"]), 0.95)
            }
            for (r in dicts(S["roads"])) {
                if (tagsOf(r)["lit"] != "yes") continue
                val pts = ptsOf(r)
                for (i in 0 until pts.size - 1) {
                    val a = pts[i]; val b = pts[i + 1]; val Lg = dist(a, b); var k = 4.0
                    while (k < Lg) {
                        val x = a[0] + (b[0] - a[0]) * k / Lg; val z = a[1] + (b[1] - a[1]) * k / Lg
                        if (!far(x, z) && nearWater(edges, listOf(V2(x, z)), 12.0)) streak(x, 5.5, z, 0.7)
                        k += 13.0 + PyMath.mod(k * 7.3, 6.0)
                    }
                }
            }
        }
        return res
    }

    private class Deck(val pts: List<V2>, val hw: Double?, val bb: DoubleArray)

    // render/amsterdam.py:454 water_layer
    /** The water (clipped to the canals below the quays), its reflections, ripples, glints and drizzle; and the quay walls. Returns (water, quays). */
    @Suppress("UNCHECKED_CAST")
    fun waterLayer(
        S: Map<String, Any?>, cam: Camera, th: Dict, sh: Shade, far: (Double, Double) -> Boolean, W: Double, H: Double, defs: MutableList<String>, edges: List<Edge>,
    ): Pair<String, String> {
        var WY = WATER_Y; val night = th["night"] == true; val w = th["wx"] as Map<String, Any?>; val L = v3(th["_L"])
        val r0 = ArrayList<String>(); val r1 = ArrayList<String>()
        fun wound(sp: List<V2>, hole: Boolean): List<V2> {
            // nonzero winding: every water area turns one way, every island the other, so overlaps stay water and islands stay land
            val n = sp.size
            val a2 = PyMath.sumOf(0 until n) { i -> sp[i][0] * sp[(i + 1) % n][1] - sp[(i + 1) % n][0] * sp[i][1] }
            return if ((a2 > 0) != hole) sp else sp.asReversed()
        }
        val water = rings(S["water"])
        val holes = rings(S["water_holes"])
        val lvl = idMap(S["water_y"])
        fun wyOf(r: List<V2>, hole: Boolean): Double {
            if (!hole) return levelOf(lvl, r, WY)
            for (o in water) { // an island sits at the level of the water around it
                if (lvl.keys.any { it === o } && pip(r[0][0], r[0][1], o)) return levelOf(lvl, o, WY)
            }
            return WY
        }
        for ((r, hole) in water.map { it to false } + holes.map { it to true }) {
            val a = cam.poly(r.map { V3(it.x, 0.0, it.y) }, margin = 900.0); val wy = wyOf(r, hole)
            val b = cam.poly(r.map { V3(it.x, wy, it.y) }, margin = 900.0)
            if (a != null && area(a) > 0.2) r0.add(P(wound(a, hole)))
            if (b != null && area(b) > 0.2) r1.add(P(wound(b, hole)))
        }
        if (r1.isEmpty()) return "" to ""
        val r0s = "<path d=\"" + r0.joinToString("") + "\"/>"; val r1s = "<path d=\"" + r1.joinToString("") + "\"/>"
        // the visible surface: below the quay on the far bank, cut by the quay edge on the near bank
        defs.add("<clipPath id=\"w0\">$r0s</clipPath><clipPath id=\"wclip\" clip-path=\"url(#w0)\">$r1s</clipPath>")
        val hy = cam.horizonY()
        defs.add(pf("<linearGradient id=\"wgrad\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"%d\" x2=\"0\" y2=\"%d\"><stop offset=\"0\" stop-color=\"%s\"/><stop offset=\"1\" stop-color=\"%s\"/></linearGradient>",
            pmax(0.0, hy), H, th["water_top"], th["water_bot"]))
        val sc = 0.8 + d(w["wind"]) * 0.17 // ripple strength from the real wind speed
        defs.add(pf("<filter id=\"ripple\" filterUnits=\"userSpaceOnUse\" x=\"0\" y=\"0\" width=\"%d\" height=\"%d\"><feTurbulence type=\"fractalNoise\" baseFrequency=\"0.012 0.26\" numOctaves=\"2\" seed=\"11\" result=\"n\"/><feDisplacementMap in=\"SourceGraphic\" in2=\"n\" scale=\"%.1f\" xChannelSelector=\"R\" yChannelSelector=\"G\"/><feGaussianBlur stdDeviation=\"0.1 0.45\"/></filter>", W, H, sc))
        defs.add("<radialGradient id=\"streak\"><stop offset=\"0\" stop-color=\"#FFE3AE\" stop-opacity=\".95\"/><stop offset=\".5\" stop-color=\"#FFC27A\" stop-opacity=\".45\"/><stop offset=\"1\" stop-color=\"#FFB060\" stop-opacity=\"0\"/></radialGradient>")
        val out = arrayListOf("<g clip-path=\"url(#wclip)\">", pf("<rect width=\"%d\" height=\"%d\" fill=\"url(#wgrad)\"/>", W, H))
        out.add(pf("<g filter=\"url(#ripple)\" opacity=\"%.2f\">%s</g>", if (night) 0.95 else 0.92, reflections(S, cam, th, sh, far, edges).joinToString("")))
        th["_hz"] = 0.0
        out.add(pf("<rect width=\"%d\" height=\"%d\" fill=\"%s\" opacity=\"%.2f\"/>", W, H, th["water"], if (!night) 0.12 else 0.04))
        // wind ripples: sky-coloured glints where the noise peaks, stretched along the water
        val rip = pmin(0.4, 0.08 + d(w["wind"]) * 0.012)
        val gl = if (!night) doubleArrayOf(1.0, 1.0, 1.0) else doubleArrayOf(0.55, 0.62, 0.8)
        val eh = cam.C[1] - WY // the eye's height above the water
        val eye = cam.f[1] > -0.25 && 0 < hy && hy < H
        val gbands = ArrayList<Triple<Double, Double, Double?>>()
        if (eye) {
            // at eye level ripples obey perspective: long and broad near you, thin lines toward the horizon
            var z = 4.0; var yb = H
            while (yb > hy + 1) {
                val z1 = z * 1.45; val y0 = if (z1 < 3000) hy + cam.focal * eh / z1 else hy
                val y1 = pmin(H, hy + cam.focal * eh / z)
                if (y1 > y0 + 0.5) gbands.add(Triple(pmax(hy, y0), y1, z * 1.2))
                yb = y0; z = z1
                if (z > 2500) break
            }
        } else {
            gbands.add(Triple(0.0, H, null))
        }
        for ((i, band) in gbands.withIndex()) {
            val (y0, y1, zz) = band
            val fx: Double; val fy: Double
            if (zz == null) {
                fx = 0.02; fy = 0.3
            } else {
                fx = pmin(0.08, pmax(0.003, zz / (cam.focal * 1.4)))
                fy = pmin(0.7, pmax(0.012, zz * zz / (cam.focal * eh * 0.3)))
            }
            defs.add(pf("<filter id=\"glint%d\" filterUnits=\"userSpaceOnUse\" x=\"0\" y=\"%.0f\" width=\"%d\" height=\"%.0f\"><feTurbulence type=\"fractalNoise\" baseFrequency=\"%.4f %.4f\" numOctaves=\"2\" seed=\"%d\"/><feColorMatrix type=\"matrix\" values=\"0 0 0 0 %.2f  0 0 0 0 %.2f  0 0 0 0 %.2f  11 0 0 0 -6.9\"/></filter>",
                i, y0 - 2, W, y1 - y0 + 4, fx, fy, 5 + i, gl[0], gl[1], gl[2]))
            out.add(pf("<rect y=\"%.1f\" width=\"%d\" height=\"%.1f\" filter=\"url(#glint%d)\" opacity=\"%.2f\"/>", y0, W, y1 - y0, i, if (!night) rip else 0.18))
        }
        if (!night && L[1] > 0 && !truthy(th["sky_k"])) {
            val C = cam.C; val t = (WY - C[1]) / (-L[1])
            val X = V3(C[0] + L[0] * t, WY, C[2] + L[2] * t); val p = cam.proj(X)
            if (p != null && -60 < p[0] && p[0] < W + 60 && p[1] < H) {
                val rnd = PyRandom(17); val sp = StringBuilder()
                repeat(260) {
                    val g = Math.abs(rnd.gauss(0.0, 1.0))
                    val y = p[1] + g * (H - p[1]) * 0.35 * PyMath.pow(rnd.random(), 0.5)
                    val spread = 6 + (y - p[1]) * 0.22
                    val x = p[0] + rnd.gauss(0.0, spread)
                    val ww = rnd.uniform(1.2, 4.5) * (1 + (y - p[1]) / 300)
                    sp.append(pf("<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.2f\" opacity=\"%.2f\"/>", x, y, ww, pmax(0.3, ww * 0.18), rnd.uniform(0.35, 1.0)))
                }
                out.add("<g fill=\"#FFF7E2\" style=\"mix-blend-mode:screen\">$sp</g>")
            }
        }
        if (truthy(th["wet"])) { // drizzle on the canal: rings scattered at random, not in a grid
            val rnd = PyRandom(9)
            val radii = List(28) { rnd.uniform(0.8, 2.4) }
            val rings = StringBuilder()
            for (r in radii) {
                val cx = rnd.uniform(0.0, 173.0); val cy = rnd.uniform(0.0, 97.0)
                rings.append(pf("<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.2f\" fill=\"none\" stroke=\"#A9B8D8\" stroke-width=\".4\" opacity=\"%.2f\"/>", cx, cy, r, r * 0.36, rnd.uniform(0.4, 1.0)))
            }
            defs.add("<pattern id=\"rings\" width=\"173\" height=\"97\" patternUnits=\"userSpaceOnUse\">$rings</pattern>")
            if (eye) {
                for ((i, band) in gbands.withIndex()) {
                    val (y0, y1, zz0) = band; val zz = zz0!!
                    val k = pmin(4.0, pmax(0.25, 14.0 / zz)); val f = pmin(1.0, (eh / zz) / 0.36)
                    if (k * f < 0.08) continue
                    defs.add(pf("<pattern id=\"rings%d\" href=\"#rings\" patternTransform=\"translate(%d 0) scale(%.3f %.3f)\"/>", i, 37 * i, k, k * f))
                    out.add(pf("<rect y=\"%.1f\" width=\"%d\" height=\"%.1f\" fill=\"url(#rings%d)\" opacity=\".55\"/>", y0, W, y1 - y0, i))
                }
            } else {
                out.add(pf("<rect width=\"%d\" height=\"%d\" fill=\"url(#rings)\" opacity=\".55\"/>", W, H))
            }
        }
        out.add("</g>")
        // quay walls: every water edge whose wall faces the camera, brick down to a dark waterline; none under a bridge's deck
        val decks = ArrayList<Deck>()
        for (r in dicts(S["roads"])) {
            if (!truthy(r["bridge"])) continue
            val pts = ptsOf(r); val rw = d(r["w"])
            decks.add(Deck(pts, rw / 2 + 0.8, doubleArrayOf(City.pyMinOf(pts.map { it.x }) - rw, City.pyMaxOf(pts.map { it.x }) + rw,
                City.pyMinOf(pts.map { it.y }) - rw, City.pyMaxOf(pts.map { it.y }) + rw)))
        }
        for (a in dicts(S["areas"])) {
            val at = (a["tags"] as Map<String, Any?>?) ?: emptyMap()
            if (a["kind"] == "bridge" || at["man_made"] == "bridge" || at["bridge"] !in NO_BRIDGE) {
                val poly = polyOf(a); val xs = poly.map { it.x }; val zs = poly.map { it.y }
                decks.add(Deck(poly, null, doubleArrayOf(City.pyMinOf(xs) - 1, City.pyMaxOf(xs) + 1, City.pyMinOf(zs) - 1, City.pyMaxOf(zs) + 1)))
            }
        }
        fun underDeck(x: Double, z: Double): Boolean {
            for (dk in decks) {
                val bb = dk.bb
                if (dk.hw == null) {
                    if (bb[0] <= x && x <= bb[1] && bb[2] <= z && z <= bb[3] && pip(x, z, dk.pts)) return true
                    continue
                }
                if (!(bb[0] <= x && x <= bb[1] && bb[2] <= z && z <= bb[3])) continue
                val pts = dk.pts
                for (i in 0 until pts.size - 1) {
                    val (ax, az) = pts[i]; val (bx, bz) = pts[i + 1]; val ux = bx - ax; val uz = bz - az
                    val L2 = (ux * ux + uz * uz).let { if (it == 0.0) 1.0 else it }
                    val t = pmax(0.0, pmin(1.0, ((x - ax) * ux + (z - az) * uz) / L2))
                    if (hypot(ax + ux * t - x, az + uz * t - z) < dk.hw) return true
                }
            }
            return false
        }
        val pieces = ArrayList<Edge>()
        for (e in edges) {
            val a = e.a; val b = e.b
            val mx = (a[0] + b[0]) / 2; val mz = (a[1] + b[1]) / 2
            if (far(mx, mz)) continue
            val el = dist(a, b)
            if (decks.isNotEmpty() && decks.any { val bb = it.bb; bb[0] - el <= mx && mx <= bb[1] + el && bb[2] - el <= mz && mz <= bb[3] + el }) {
                val k = Math.max(1, (el / 2.0).toInt()); var run: V2? = null
                for (j in 0 until k) {
                    val u0 = j.toDouble() / k; val u1 = (j + 1).toDouble() / k
                    if (underDeck(a[0] + (b[0] - a[0]) * (u0 + u1) / 2, a[1] + (b[1] - a[1]) * (u0 + u1) / 2)) {
                        if (run != null) {
                            pieces.add(Edge(run, V2(a[0] + (b[0] - a[0]) * u0, a[1] + (b[1] - a[1]) * u0), e.nx, e.nz, e.r)); run = null
                        }
                    } else if (run == null) run = V2(a[0] + (b[0] - a[0]) * u0, a[1] + (b[1] - a[1]) * u0)
                }
                if (run != null) pieces.add(Edge(run, b, e.nx, e.nz, e.r))
            } else pieces.add(e)
        }
        val quay = ArrayList<String>()
        for (e in pieces) {
            val a = e.a; val b = e.b; val nx = e.nx; val nz = e.nz
            val mx = (a[0] + b[0]) / 2; val mz = (a[1] + b[1]) / 2
            WY = levelOf(lvl, e.r, WATER_Y)
            val n3 = V3(nx, 0.0, nz)
            if (vdot(n3, vsub(cam.C, V3(mx, WY / 2, mz))) <= 0) continue
            val lit = !night && vdot(n3, L) > 0 && sh.lit(V3(mx + nx * .3, WY / 2, mz + nz * .3))
            th["_hz"] = hazeAt(th, cam.depth(V3(mx, 0.0, mz)))
            val col = tohex(light(th, hx(QUAY_COL), n3, lit))
            var f1 = face(cam, listOf(V3(a[0], WY + 0.35, a[1]), V3(b[0], WY + 0.35, b[1]), V3(b[0], 0.0, b[1]), V3(a[0], 0.0, a[1])), col, pf(" stroke=\"%s\" stroke-width=\".3\"", col))
            if (TONE && f1.isNotEmpty() && !night && vdot(n3, L) > 0 && WY < -2) {
                // a tall quay wall in sun: in pieces, each lit or in the shadow of what stands across the water and behind it
                val el = dist(a, b); val k = Math.max(1L, PyMath.int(PyMath.ceil(el / 5.0))).toInt(); val nb = 3
                val qsLit = ArrayList<String>(); val qsDark = ArrayList<String>()
                for (j in 0 until k) {
                    val pa = V2(a[0] + (b[0] - a[0]) * j / k, a[1] + (b[1] - a[1]) * j / k); val pb = V2(a[0] + (b[0] - a[0]) * (j + 1) / k, a[1] + (b[1] - a[1]) * (j + 1) / k)
                    for (m in 0 until nb) {
                        val y0 = WY + 0.35 - (WY + 0.35) * m / nb; val y1 = WY + 0.35 - (WY + 0.35) * (m + 1) / nb
                        val lt = sh.lit(V3((pa[0] + pb[0]) / 2 + nx * .3, (y0 + y1) / 2, (pa[1] + pb[1]) / 2 + nz * .3))
                        val sp = cam.poly(listOf(V3(pa[0], y0, pa[1]), V3(pb[0], y0, pb[1]), V3(pb[0], y1, pb[1]), V3(pa[0], y1, pa[1])), margin = 300.0)
                        if (sp != null && area(sp) > 0.03) (if (lt) qsLit else qsDark).add(P(sp))
                    }
                }
                val cl = tohex(light(th, hx(QUAY_COL), n3, true)); val cd = tohex(light(th, hx(QUAY_COL), n3, false))
                val sb = StringBuilder()
                if (qsLit.isNotEmpty()) sb.append(pf("<path d=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\".3\"/>", qsLit.joinToString(""), cl, cl))
                if (qsDark.isNotEmpty()) sb.append(pf("<path d=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\".3\"/>", qsDark.joinToString(""), cd, cd))
                f1 = sb.toString()
            }
            val f2 = face(cam, listOf(V3(a[0], WY, a[1]), V3(b[0], WY, b[1]), V3(b[0], WY + 0.35, b[1]), V3(a[0], WY + 0.35, a[1])), if (!night) "#2C2A22" else "#06070A")
            val f3 = face(cam, listOf(V3(a[0], -0.18, a[1]), V3(b[0], -0.18, b[1]), V3(b[0], 0.02, b[1]), V3(a[0], 0.02, a[1])), tohex(light(th, hx("#B8B0A2"), n3, lit)))
            var crs = ""
            if (cam.f[1] > -0.25 && truthy(th["wx"]) && !night && f1.isNotEmpty()) { // at eye level the quay wall shows its brick courses
                val el = dist(a, b); val qc = QUAY_COURSE
                if (el > 0.2 && qc != null && qc != 0.0) crs = Closeup.coursing(cam, a, b, b[0] - a[0], b[1] - a[1], el, n3, WY + 0.38, -0.2, qc).joinToString("")
            }
            quay.add(f1 + crs + f2 + f3)
        }
        th["_hz"] = 0.0
        return out.joinToString("") to quay.joinToString("")
    }

    // ------------------------------------------------------------------ render
    /** A thing to draw, sorted by depth: a building ("b"), a tree, a lamp, a bench, … with its dict, or ready SVG ("svg") as a String. */
    class Item(val d: Double, val k: String, val o: Any?) {
        /** What its maker knew of it, kept only while the render is taped (see [noted]). */
        @JvmField var note: ItemNote? = null
    }

    /**
     * [item], with an [ItemNote] for the world tape when [cam] records into one: its [kind], its [owner], the point ([fx],
     * [fz]) its radius cut tested (and a second, ([fx1], [fz1]), that keeps it too), and its sort point ([sx], [sy], [sz])
     * whose camera depth plus [off] is the item's depth. Without a tape it is [item] as it was.
     */
    internal fun noted(
        item: Item, cam: Camera, kind: String, owner: Any?, fx: Double, fz: Double, sx: Double, sy: Double, sz: Double,
        off: Double = 0.0, fx1: Double = fx, fz1: Double = fz,
    ): Item {
        if (cam.tape != null) item.note = ItemNote(kind, owner, fx, fz, fx1, fz1, sx, sy, sz, off)
        return item
    }

    /** `S['under']`, `S['over']`: other regions' land cover below the water, or runways, lanes and rails on the ground. */
    typealias SceneLayer = (Camera, Dict, (Double, Double) -> Boolean, MutableList<String>) -> String

    /** `S['items']`: other regions' cranes, tanks, turbines and masts, as items sorted with everything else. */
    typealias SceneItems = (Camera, Dict, (Double, Double) -> Boolean, MutableList<String>) -> List<Item>

    /**
     * What [render] returns: the whole SVG, or for `layer = "split"` the ground in [svg] and what stands on it in [objs]
     * (one image, or the far half and the near half when `cfg['obj_split']` is exceeded); the camera, and the info dict.
     */
    class Rendered(val svg: String, val objs: List<String>?, val cam: Camera, val info: Dict)

    /** What [renderSplit] returns: everything behind one building, everything in front of it, and the building itself drawn on demand. */
    class Quest(val base: String, val front: String, val variant: (Dict, String?) -> String, val cam: Camera, val depth: Double?, val az: Double, val el: Double)

    private val AREA: Map<String, String> = linkedMapOf(
        "park" to "#A9C38A", "garden" to "#A9C38A", "grass" to "#A9C38A", "playground" to "#D9B48A", "pedestrian" to "#DCD3C4", "square" to "#DCD3C4", "memorial" to "#E3B3C0",
        "parking" to "#C9C4BA", "bridge" to "#CFC6B6",
    )
    private val ROAD_ORDER = listOf("path", "footway", "steps", "cycleway", "service", "pedestrian", "living_street", "unclassified", "residential", "busway", "tertiary", "secondary")
    private val SURF_COLOUR: Map<String, String> = linkedMapOf("red" to "#C0665A", "grey" to "#9B9A99", "gray" to "#9B9A99", "black" to "#5A5A5A", "green" to "#6F9A6A", "blue" to "#6A86B0")
    private val STROKE_W = Regex("stroke-width=\"([\\d.]+)\"")

    // render/amsterdam.py:615 render
    /** The living map: `layer = "front"` leaves the sky transparent (the board slides a separate sky behind it); `"split"` gives the ground and the objects apart. */
    fun render(S: Dict, thn: String, cfg: Map<String, Any?>, lens: String? = null, layer: String? = null, opts: RenderOptions = RenderOptions()): Rendered =
        renderImpl(S, thn, cfg, lens, null, layer, opts) as Rendered

    // render/amsterdam.py:615 render (split=building)
    /** Three layers for the fill-in quest: everything behind the building [split], the building itself (one per answer), everything in front. */
    fun renderSplit(S: Dict, thn: String, cfg: Map<String, Any?>, lens: String?, split: Dict, opts: RenderOptions = RenderOptions()): Quest =
        renderImpl(S, thn, cfg, lens, split, null, opts) as Quest

    @Suppress("UNCHECKED_CAST")
    private fun renderImpl(S: Dict, thn: String, cfg: Map<String, Any?>, lens: String?, split: Dict?, layer: String?, opts: RenderOptions): Any {
        val th: Dict = LinkedHashMap(THEMES.getValue(thn)); val W = d(cfg["W"]); val H = d(cfg["H"]); th["_win_d"] = cfg["win_d"] ?: 390
        val pitch = if ("pitch" in cfg) d(cfg["pitch"]) else 90.0
        th["_eye"] = truthy(th["wx"]) && pitch < 10
        val cam = cameraOf(cfg)
        val (az, el, L) = sunOf(th)
        th["_L"] = L; val night = th["night"] == true
        val wx = truthy(th["wx"])
        // the app's Look settings' grain off: the regions' and the terrain's land draw without the materials' grain too
        if (!opts.grain) th["_no_grain"] = true
        val solids = dicts(S["buildings"]).filter { !truthy(it["hidden"]) && truthy(it["h"]) } + dicts(S["parts"]).filter { truthy(it["h"]) }
        val sh = Shade.of(S, solids, L)
        val RAD = if ("radius" in cfg) d(cfg["radius"]) else 1e9; val target = v3(cfg["target"]); val TX = target[0]; val TZ = target[2]
        val far: (Double, Double) -> Boolean = { x, z -> hypot(x - TX, z - TZ) > RAD }
        val defs = ArrayList<String>(); val ground = ArrayList<String>(); val marks = ArrayList<String>(); val shadows = ArrayList<String>()
        val items = ArrayList<Item>(); val glow = ArrayList<String>()
        val hy = cam.horizonY()
        // the sky, the haze, wfx and fin: fixed to the screen, from the camera, the theme and cfg alone
        val screen = ScreenLayers.of(
            cam, th, cfg, thn, layer, drawHaze = opts.haze, drawVignette = opts.vignette, drawGrain = opts.grain,
            drawCloudShadows = opts.cloudShadows,
        )
        if (wx) {
            defs.add("<filter id=\"foliage\" x=\"-25%\" y=\"-25%\" width=\"150%\" height=\"150%\"><feTurbulence type=\"fractalNoise\" baseFrequency=\".55\" numOctaves=\"2\" seed=\"4\" result=\"n\"/><feDisplacementMap in=\"SourceGraphic\" in2=\"n\" scale=\"7\" xChannelSelector=\"R\" yChannelSelector=\"G\" result=\"d\"/><feTurbulence type=\"fractalNoise\" baseFrequency=\"1.4\" numOctaves=\"2\" seed=\"9\" result=\"m\"/><feColorMatrix in=\"m\" type=\"matrix\" values=\"0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  -3.2 0 0 0 2.35\" result=\"mask\"/><feComposite in=\"d\" in2=\"mask\" operator=\"in\" result=\"leaves\"/><feColorMatrix in=\"m\" type=\"matrix\" values=\".6 .6 .6 0 0  .6 .6 .6 0 0  .6 .6 .6 0 0  0 0 0 0 1\" result=\"tone\"/><feComposite in=\"leaves\" in2=\"tone\" operator=\"arithmetic\" k1=\".9\" k2=\".55\" k3=\"0\" k4=\"0\" result=\"t\"/><feComposite in=\"t\" in2=\"leaves\" operator=\"in\"/></filter>")
            defs.add("<filter id=\"leaf\" x=\"-15%\" y=\"-15%\" width=\"130%\" height=\"130%\"><feTurbulence type=\"fractalNoise\" baseFrequency=\".55\" numOctaves=\"2\" seed=\"3\" result=\"n\"/><feDisplacementMap in=\"SourceGraphic\" in2=\"n\" scale=\"2.6\" xChannelSelector=\"R\" yChannelSelector=\"G\"/></filter>")
            // brick, plaster and slate are never one flat tone: a fine streaky variation, stronger along the courses than across them
            defs.add(pf("<filter id=\"mat\" filterUnits=\"userSpaceOnUse\" x=\"0\" y=\"0\" width=\"%d\" height=\"%d\"><feTurbulence type=\"fractalNoise\" baseFrequency=\".16 .75\" numOctaves=\"2\" seed=\"13\"/><feColorMatrix type=\"matrix\" values=\".33 .33 .33 0 0  .33 .33 .33 0 0  .33 .33 .33 0 0  0 0 0 0 1\" result=\"g\"/><feComposite in=\"SourceGraphic\" in2=\"g\" operator=\"arithmetic\" k1=\".2\" k2=\".9\" k3=\"0\" k4=\"0\" result=\"t\"/><feComposite in=\"t\" in2=\"SourceAlpha\" operator=\"in\"/></filter>", W, H))
            defs.add(Closeup.crownFilter())
            defs.add("<pattern id=\"scaf\" width=\"9\" height=\"9\" patternUnits=\"userSpaceOnUse\"><path d=\"M0 0h9M0 0v9\" stroke=\"#8A8F96\" stroke-width=\".7\"/><path d=\"M0 9L9 0\" stroke=\"#8A8F96\" stroke-width=\".4\"/></pattern>")
            defs.addAll(screen.finDefs())
        }
        if (wx && !night) {
            val (s0, s1) = if (truthy(th["sky_k"])) "#C7CDD5" to "#59626E" else "#B7CBE0" to "#3E4C5E"
            defs.add(pf("<linearGradient id=\"glass\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"%s\"/><stop offset=\"1\" stop-color=\"%s\"/></linearGradient>", s0, s1))
            defs.add("<linearGradient id=\"glassLit\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#E8EEF4\"/><stop offset=\"1\" stop-color=\"#8496AA\"/></linearGradient>")
        }
        defs.add(screen.skyDef())
        if (layer != "front") ground.add(screen.skyRect())
        if (wx && hy > 4) ground.add(screen.skyLayer(cam, th, defs))
        val GR = if ("ground_r" in cfg) d(cfg["ground_r"]) else 1500.0
        ground.add(groundPath(cam, rect(-GR, GR, -GR, GR), th["ground"] as String))
        (S["under"] as SceneLayer?)?.let { ground.add(it(cam, th, far, defs)) } // other regions: land cover below the water
        var quaySvg = ""
        if (wx) {
            val edges = waterEdges(S)
            val (wsvg, q) = waterLayer(S, cam, th, sh, far, W, H, defs, edges)
            quaySvg = q
            ground.add(wsvg)
        } else {
            for (r in rings(S["water"])) ground.add(groundPath(cam, r, th["water"] as String, 0.0))
        }
        // the materials' grain, unless the app's Look settings turn the grain off
        val grain = wx && opts.grain
        if (grain) ground.add("<g filter=\"url(#mat)\">") // pavers and setts are never one flat tone either
        for (a in dicts(S["areas"])) {
            WorldTape.owner(a)
            var c = AREA[a["kind"]] ?: "#DCD3C4"
            val sf = ((a["tags"] as Map<String, Any?>?) ?: emptyMap())["surface"] as? String
            if (a["kind"] in SURFACED_AREAS && sf != null && truthy(SURF[sf]) && TONE) c = SURF.getValue(tagsOf(a)["surface"] as String) // a square in its mapped surface
            if (night) c = tohex(mulc(hx(c), 0.18))
            ground.add(groundPath(cam, polyOf(a), c, 0.02))
        }
        WorldTape.owner(null)
        // roads in their mapped material; at eye level, pavements where sidewalk=* is mapped, and the joints of each material
        val eye = wx && pitch < 10
        th["_eye"] = eye
        var paveOver = ""
        if (eye) {
            defs.add(Closeup.patternDefs(night))
            val sideCol: (String?) -> String = { sf ->
                var c = mix(hx(SURF[sf] ?: "#C4C0B8"), hx("#BFBAB2"), 0.55)
                if (truthy(th["sky_k"])) c = mulc(c, 0.93)
                tohex(if (night) mulc(c, 0.2) else c)
            }
            val (pu, po) = Closeup.paving(S, cam, th, far, sideCol)
            paveOver = po
            ground.add(pu)
        }
        val roads = dicts(S["roads"])
        drawRoads(S, cam, th, cfg, roads, wx, night, eye, ground, marks, items)
        if (paveOver.isNotEmpty()) ground.add(paveOver)
        (S["over"] as SceneLayer?)?.let { ground.add(it(cam, th, far, defs)) } // other regions: runways, motorway lanes, rails on the ground
        if (grain) ground.add("</g>")
        groundMarks(S, cam, th, solids, L, far, wx, night, pitch, marks, shadows)
        gatherItems(S, cam, th, cfg, wx, pitch, far, defs, items)
        val drawn = drawObjects(items, split, cam, th, sh, lens)
        val out = drawn.out; val cut = drawn.cut; val splitD = drawn.splitD
        // night light: mapped lamps, lit=yes roads, open places
        val pools = ArrayList<String>()
        if (night) nightLight(S, cam, th, roads, wx, eye, far, opts, defs, pools, glow)
        val counts = lensCounts(S, cam, lens, roads, far, W, H, pools, glow)
        val haze = screen.hazeAndWash(defs)
        val (shadowG, aoG) = overlays(cam, th, cfg, solids, shadows, far, wx, night, opts, defs)
        val wfx = screen.wfx(defs)
        val head = listOf(
            pf("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\">", W, H, W, H),
            pf("<defs><clipPath id=\"vb\"><rect width=\"%d\" height=\"%d\"/></clipPath>%s</defs><g clip-path=\"url(#vb)\">", W, H, defs.joinToString("")),
        )
        fun cat(parts: List<String>): String = java.lang.String.join("", parts) // sized once, not grown and copied as a builder is
        if (split != null) {
            // three layers for the fill-in quest: everything behind the building, the building itself (one per answer), everything in front
            val c = cut ?: out.size
            val base = cat(head + listOf(cat(ground), cat(marks), shadowG, aoG, quaySvg, cat(pools), "<g stroke-linejoin=\"round\">", cat(out.subList(0, c)), "</g></g></svg>"))
            val front = cat(head + listOf("<g stroke-linejoin=\"round\">", cat(out.subList(c, out.size)), "</g>", cat(glow), wfx, haze, "</g></svg>"))
            val sd = splitD
            val variant: (Dict, String?) -> String = { bv, lens_ ->
                val body = drawBuilding(cam, th, sh, bv, sd ?: throw IllegalStateException("the split building is not in view"), lens_).joinToString("")
                val dfs = if (truthy(bv["_hatch"])) "<defs><pattern id=\"hatchq\" width=\"7\" height=\"7\" patternUnits=\"userSpaceOnUse\" patternTransform=\"rotate(45)\"><path d=\"M0 0V7\" stroke=\"#8C857A\" stroke-width=\"2.2\"/></pattern></defs>" else ""
                pf("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\" style=\"position: absolute; left: 0; top: 0\" aria-hidden=\"true\">%s<g stroke-linejoin=\"round\">%s</g></svg>", W, H, W, H, dfs, body)
            }
            return Quest(base, front, variant, cam, splitD, az, el)
        }
        val fin = screen.fin()
        val mat = if (grain) " filter=\"url(#mat)\"" else ""
        val above = (th["_above"] as String?) ?: ""
        fun info(raster: Boolean): Dict {
            val i = dictOf("az" to az, "el" to el, "counts" to counts, "doors" to (th["_doors"] ?: 0), "shops" to (th["_shops"] ?: 0), "open" to (th["_open"] ?: 0), "brands" to (th["_brands"] ?: 0))
            if (raster) i["raster"] = th["_raster"]
            return i
        }
        if (layer == "split") {
            // two stacked images for dense views: the ground, then everything standing on it (each under the size limit)
            val up = truthy(S["_town"]) // a town on its terrain: the ground image lies between the layers, so shadows and seams go above it
            val base = cat(head + listOf(cat(ground), cat(marks), if (up) "" else shadowG, if (up) "" else aoG, quaySvg, cat(pools), "</g></svg>"))
            // a ground image of its own (terrain, relief) stands between these two on the board; what lies along the ground above it opens the second
            var objs: List<String> = listOf(cat(head + listOf(above, if (up) shadowG + aoG else "", "<g stroke-linejoin=\"round\"$mat>", cat(out), "</g>", cat(glow), wfx, haze, fin, "</g></svg>")))
            val limit = cfg["obj_split"]
            if (truthy(limit) && plen(objs[0]) > d(limit)) {
                // too much for one image: the far half and the near half, stacked in that order, so nothing changes in what covers what
                val tot = out.sumOf { plen(it).toLong() }; var acc = 0L; var k = 0
                while (k < out.size && acc < tot / 2.0) { acc += plen(out[k]); k++ }
                val far_ = cat(head + listOf(above, if (up) shadowG + aoG else "", "<g stroke-linejoin=\"round\"$mat>", cat(out.subList(0, k)), "</g></g></svg>"))
                val near_ = cat(head + listOf("<g stroke-linejoin=\"round\"$mat>", cat(out.subList(k, out.size)), "</g>", cat(glow), wfx, haze, fin, "</g></svg>"))
                objs = listOf(far_, near_)
            }
            return Rendered(base, objs, cam, info(true))
        }
        val svg = head + listOf(cat(ground), cat(marks), shadowG, aoG, quaySvg, cat(pools), above, "<g stroke-linejoin=\"round\"$mat>", cat(out), "</g>", cat(glow), wfx, haze, fin, "</g></svg>")
        return Rendered(cat(svg), null, cam, info(false))
    }

    /** `render`'s ground paths: a polygon on the ground at height [y], drawn when it covers more than a fifth of a pixel. */
    private fun groundPath(cam: Camera, pts: List<V2>, fill: String, y: Double = 0.0, extra: String = ""): String {
        val sp = cam.poly(pts.map { V3(it.x, y, it.y) }, margin = 900.0)
        return if (sp != null && area(sp) > 0.2) pf("<path d=\"%s\" fill=\"%s\"%s/>", P(sp), fill, extra) else ""
    }

    // render/amsterdam.py:615 render: the roads, in their mapped material, and the bridges' railings and setts
    @Suppress("UNCHECKED_CAST")
    private fun drawRoads(S: Dict, cam: Camera, th: Dict, cfg: Map<String, Any?>, roads: List<Dict>, wx: Boolean, night: Boolean, eye: Boolean,
                          ground: MutableList<String>, marks: MutableList<String>, items: MutableList<Item>) {
        for (hw in ROAD_ORDER) {
            for (r in roads) {
                if (r["hw"] != hw) continue
                WorldTape.owner(r)
                val rt = tagsOf(r); val pts = ptsOf(r); val rw = d(r["w"]); val bridge = truthy(r["bridge"])
                val surf = rt["surface"] as String?
                var col = SURF[surf] ?: (th["road"] as String)
                val sc = rt["surface:colour"]
                if (truthy(sc)) col = SURF_COLOUR[sc as String] ?: col
                else if (hw == "cycleway") col = "#C98A7A" // guessed surface:colour=red, the Dutch default
                if (night) col = tohex(mulc(hx(col), 0.2))
                val y = 0.05 + (if (bridge) 0.4 else 0.0)
                ground.add(groundPath(cam, ribbon(pts, rw / 2), col, y))
                if (bridge && wx) {
                    for (s_ in intArrayOf(-1, 1)) {
                        val off = ribbon(pts, rw / 2 + 0.15); val n = pts.size
                        val line = if (s_ < 0) off.subList(0, n) else off.subList(n, off.size).asReversed()
                        items.addAll(railing(cam, th, line, y + 0.02, r))
                    }
                } else if (bridge && !night) {
                    for (s_ in intArrayOf(-1, 1)) {
                        val off = ribbon(pts, rw / 2 + 0.3); val n = pts.size
                        val line = if (s_ < 0) off.subList(0, n) else off.subList(n, off.size).asReversed()
                        val pp = line.mapNotNull { cam.proj(V3(it.x, 1.1, it.y)) }
                        if (pp.size > 1) marks.add(pf("<path d=\"M%s\" stroke=\"#5A4E44\" stroke-width=\"1.2\" fill=\"none\"/>", pp.joinToString("L") { pf("%.1f %.1f", it.x, it.y) }))
                    }
                }
                if (surf == "sett" && !night && !eye && cam.depth(V3(pts[0][0], 0.0, pts[0][1])) < pmin(500.0, if ("sett_d" in cfg) d(cfg["sett_d"]) else 500.0)) {
                    val segs = ArrayList<String>()
                    for (i in 0 until pts.size - 1) {
                        val a = pts[i]; val b = pts[i + 1]
                        val Lg = dist(a, b); val l1 = if (Lg == 0.0) 1.0 else Lg
                        val ux = (b[0] - a[0]) / l1; val uz = (b[1] - a[1]) / l1
                        var k = 0.0
                        while (k < Lg) {
                            val cx = a[0] + ux * k; val cz = a[1] + uz * k
                            val p0 = cam.proj(V3(cx + uz * rw / 2, y + 0.01, cz - ux * rw / 2)); val p1 = cam.proj(V3(cx - uz * rw / 2, y + 0.01, cz + ux * rw / 2))
                            if (p0 != null && p1 != null) segs.add(pf("M%.1f %.1fL%.1f %.1f", p0[0], p0[1], p1[0], p1[1]))
                            k += 0.6
                        }
                    }
                    if (segs.isNotEmpty()) marks.add(pf("<path d=\"%s\" stroke=\"#7E7264\" stroke-width=\".4\" opacity=\".6\" fill=\"none\"/>", segs.joinToString("")))
                }
            }
        }
        WorldTape.owner(null)
    }

    // render/amsterdam.py:615 render: tram tracks, canal names, and the shadows of buildings and trees on the ground
    @Suppress("UNCHECKED_CAST")
    private fun groundMarks(S: Dict, cam: Camera, th: Dict, solids: List<Dict>, L: V3, far: (Double, Double) -> Boolean, wx: Boolean, night: Boolean,
                            pitch: Double, marks: MutableList<String>, shadows: MutableList<String>) {
        // tram tracks
        for (rl in rings(S["rails"])) {
            for (off in doubleArrayOf(-0.72, 0.72)) {
                val o = ribbon(rl, Math.abs(off)); val n = rl.size
                val line = if (off < 0) o.subList(0, n) else o.subList(n, o.size).asReversed()
                val pp = line.mapNotNull { cam.proj(V3(it.x, 0.08, it.y)) }
                if (pp.size > 1) marks.add(pf("<path d=\"M%s\" stroke=\"%s\" stroke-width=\".9\" fill=\"none\"/>", pp.joinToString("L") { pf("%.1f %.1f", it.x, it.y) }, if (!night) "#6E6A66" else "#2A2E3A"))
            }
        }
        // crossings (projected, drawn as nothing)
        // canal names (real names) on the water, from above only
        if (!night && pitch > 20) {
            val seen = HashSet<String>()
            for (lb in dicts(S["labels"])) {
                val name = lb["name"] as String
                if (lb["kind"] != "canal" || name in seen) continue
                val pts = ptsOf(lb)
                val mid = pts[pts.size / 2]
                if (far(mid[0], mid[1]) || pts.size < 2) continue
                val a = pts[pts.size / 2 - 1]; val b = pts[pts.size / 2]
                var ux = b[0] - a[0]; var uz = b[1] - a[1]; val Lg = hypot(ux, uz).let { if (it == 0.0) 1.0 else it }; ux /= Lg; uz /= Lg
                val u = V3(ux, 0.0, uz)
                val sv = Living.groundText(cam, name.uppercase(), V3(mid[0], if (wx) (WATER_Y + 0.05) else 0.1, mid[1]), u, V3(-uz, 0.0, ux), 3.2, "#EAF2FA", 0.85, 700, 0.3)
                if (sv.isNotEmpty()) { marks.add(sv); seen.add(name) }
            }
        }
        // shadows from mapped heights only
        if (!night && L[1] > 0 && !truthy(S["no_shadows"])) {
            fun o(y: Double) = V2(-L[0] / L[1] * y, -L[2] / L[1] * y)
            for (b in solids) {
                val poly = polyOf(b)
                val c = centroid(poly)
                if (far(c.x, c.y)) continue
                val top = d(b["h"]); val bot = d(b["min"])
                var off1 = o(top)
                if (b["_gy"] != null) { // on real terrain: from the building's own ground, onto it
                    val gy = d(b["_gy"]); off1 = o(top - gy)
                    val hull = hull2(poly + poly.map { V2(it.x + off1[0], it.y + off1[1]) })
                    val sp = cam.poly(hull.map { V3(it.x, gy + 0.1, it.y) }, margin = 900.0)
                    if (sp != null) shadows.add(P(sp))
                    continue
                }
                if (bot > 0) {
                    val sp = cam.poly(poly.map { V3(it.x + off1[0], 0.1, it.y + off1[1]) }, margin = 900.0)
                    if (sp != null) shadows.add(P(sp))
                    continue
                }
                // a prism's shadow: the hull of its footprint and its offset top (exact for convex footprints)
                val hull = hull2(poly + poly.map { V2(it.x + off1[0], it.y + off1[1]) })
                val sp = cam.poly(hull.map { V3(it.x, 0.1, it.y) }, margin = 900.0)
                if (sp != null) shadows.add(P(sp))
            }
        }
        if (!night && L[1] > 0 && !truthy(S["no_shadows"])) {
            fun o(y: Double) = V2(-L[0] / L[1] * y, -L[2] / L[1] * y)
            for (n in dicts(S["trees"])) {
                val nx = d(n["x"]); val nz = d(n["z"])
                if (far(nx, nz)) continue
                val off = o(d(n["h"]) * 0.7); val r = d(n["crown"]) / 2
                val gy = if ("gy" in n) d(n["gy"]) else 0.0
                val sp = cam.poly((0 until 16).map { k -> val a = k * Math.PI / 8; V3(nx + off[0] + r * PyMath.cos(a), gy + 0.1, nz + off[1] + r * PyMath.sin(a)) })
                if (sp != null) shadows.add(P(sp))
            }
        }
    }

    // render/amsterdam.py:615 render: the objects in view (buildings, parts, trees and street furniture, a region's own, facade details)
    @Suppress("UNCHECKED_CAST")
    private fun gatherItems(S: Dict, cam: Camera, th: Dict, cfg: Map<String, Any?>, wx: Boolean, pitch: Double, far: (Double, Double) -> Boolean,
                            defs: MutableList<String>, items: MutableList<Item>) {
        // objects
        for (b in dicts(S["buildings"])) {
            if (truthy(b["hidden"])) continue
            val c = centroid(polyOf(b))
            if (far(c.x, c.y)) continue
            val sy = (if (truthy(b["h"])) d(b["h"]) else 0.0) / 2
            val cc = cam.cam(V3(c.x, sy, c.y))
            if (cc[2] < 2) continue
            items.add(noted(Item(cc[2], "b", b), cam, "building", b, c.x, c.y, c.x, sy, c.y))
        }
        for (p in dicts(S["parts"])) {
            val c = centroid(polyOf(p))
            if (far(c.x, c.y)) continue
            val sy = (d(p["min"]) + (if (truthy(p["h"])) d(p["h"]) else 0.0)) / 2
            val cc = cam.cam(V3(c.x, sy, c.y))
            if (cc[2] < 2) continue
            val off = -(0.01 * d(p["min"]))
            items.add(noted(Item(cc[2] + off, "b", p), cam, "part", p, c.x, c.y, c.x, sy, c.y, off))
        }
        val furniturePx = if ("furniture_px" in cfg) d(cfg["furniture_px"]) else 1.6
        for (n in dicts(S["nodes"])) {
            val t = tagsOf(n)
            val k = if (t["natural"] == "tree") "tree"
            else if (t["highway"] == "street_lamp") t["highway"] as String
            else if (t["amenity"] in FURNITURE) t["amenity"] as String
            else if (t["barrier"] in BOLLARDS) "bollard" else null
            val nx = d(n["x"]); val nz = d(n["z"])
            if (k == null || far(nx, nz)) continue
            val c = cam.cam(V3(nx, 1.0, nz))
            if (c[2] < (if (pitch < 10) 3.0 else 2.0)) continue // at eye level, nothing right in your face
            if (k != "tree" && cam.focal / c[2] < furniturePx) continue // street furniture only a pixel or two tall is left out
            items.add(noted(Item(c[2], k, n), cam, k, n, nx, nz, nx, 1.0, nz))
        }
        (S["items"] as SceneItems?)?.let { items.addAll(it(cam, th, far, defs)) } // other regions: cranes, tanks, turbines, masts
        if (wx && !truthy(S["no_facades"])) {
            for (it in facadeDetails(S, cam, th, far)) {
                val hz = hazeAt(th, it.d)
                items.add(if (hz > 0.04 && it.k == "svg") Item(it.d, it.k, pf("<g opacity=\"%.2f\">%s</g>", 1 - 0.8 * hz, it.o)).also { h -> h.note = it.note } else it)
            }
        }
    }

    /** The objects drawn far to near, and where the quest's building [Drawn.cut] them (its depth [Drawn.splitD]). */
    private class Drawn(val out: ArrayList<String>, val cut: Int?, val splitD: Double?)

    // render/amsterdam.py:615 render: every object, far to near (painter's order); the quest's building is left out, its place kept
    @Suppress("UNCHECKED_CAST")
    private fun drawObjects(items: MutableList<Item>, split: Dict?, cam: Camera, th: Dict, sh: Shade, lens: String?): Drawn {
        items.pySortBy { -it.d }
        val out = ArrayList<String>(); var cut: Int? = null; var splitD: Double? = null
        val tape = cam.tape
        for (it in items) {
            val o = it.o
            if (split != null && it.k == "b" && o === split) {
                cut = out.size; splitD = it.d; continue
            }
            val from = out.size
            val note = it.note
            val owner = if (note != null) note.owner else if (o is String) null else o
            tape?.setOwner(owner)
            when (it.k) {
                "b" -> out.addAll(drawBuilding(cam, th, sh, o as Dict, it.d, lens))
                "tree" -> out.addAll(drawTree3d(lift(cam, o), th, sh, o as Dict, it.d, lens))
                "street_lamp" -> out.addAll(drawLamp(lift(cam, o), th, o as Dict, it.d))
                "bench" -> out.add(drawBench(lift(cam, o), th, o as Dict))
                "waste_basket" -> { val n = o as Dict; out.add(drawBox(lift(cam, o), th, d(n["x"]), d(n["z"]), 0.42, 0.42, 0.95, "#2F4A3C")) }
                "bollard" -> out.add(drawBollard(lift(cam, o), th, o as Dict, it.d))
                "bicycle_parking" -> out.add(drawRack(lift(cam, o), th, o as Dict))
                "svg" -> out.add(o as String)
            }
            tape?.item(note?.kind ?: it.k, owner, it.d, note, out, from)
        }
        tape?.setOwner(null)
        return Drawn(out, cut, splitD)
    }

    // render/amsterdam.py:615 render: night light (mapped lamps, lit=yes roads, open places)
    @Suppress("UNCHECKED_CAST")
    private fun nightLight(S: Dict, cam: Camera, th: Dict, roads: List<Dict>, wx: Boolean, eye: Boolean, far: (Double, Double) -> Boolean, opts: RenderOptions,
                           defs: MutableList<String>, pools: MutableList<String>, glow: MutableList<String>) {
        defs.add("<radialGradient id=\"pool\"><stop offset=\"0\" stop-color=\"#FFB45E\" stop-opacity=\".55\"/><stop offset=\".5\" stop-color=\"#FFA848\" stop-opacity=\".18\"/><stop offset=\"1\" stop-color=\"#FFA848\" stop-opacity=\"0\"/></radialGradient>")
        defs.add("<radialGradient id=\"open\"><stop offset=\"0\" stop-color=\"#FFE2A8\" stop-opacity=\".95\"/><stop offset=\".3\" stop-color=\"#FFC878\" stop-opacity=\".45\"/><stop offset=\"1\" stop-color=\"#FFC878\" stop-opacity=\"0\"/></radialGradient>")
        defs.add("<filter id=\"litglow\" x=\"-20%\" y=\"-20%\" width=\"140%\" height=\"140%\"><feGaussianBlur stdDeviation=\"2.6\"/></filter>")
        defs.add("<radialGradient id=\"openpool\"><stop offset=\"0\" stop-color=\"#FFD89A\" stop-opacity=\".75\"/><stop offset=\".45\" stop-color=\"#FFC070\" stop-opacity=\".28\"/><stop offset=\"1\" stop-color=\"#FFB060\" stop-opacity=\"0\"/></radialGradient>")
        defs.add("<radialGradient id=\"halo\"><stop offset=\"0\" stop-color=\"#FFE9C0\" stop-opacity=\".7\"/><stop offset=\".35\" stop-color=\"#FFC98A\" stop-opacity=\".22\"/><stop offset=\"1\" stop-color=\"#FFB060\" stop-opacity=\"0\"/></radialGradient>")
        if (!wx) defs.add("<radialGradient id=\"streak\"><stop offset=\"0\" stop-color=\"#FFE3AE\" stop-opacity=\".95\"/><stop offset=\"1\" stop-color=\"#FFB060\" stop-opacity=\"0\"/></radialGradient>")
        // lit=yes streets: stroked centrelines, width from the street's own width at its depth
        val lit = ArrayList<String>()
        for (r in roads) {
            if (tagsOf(r)["lit"] != "yes") continue
            val pts = ptsOf(r); val rw = d(r["w"])
            if (eye) {
                val gw = rw / 2 + 4.0; val segs = ArrayList<String>()
                for (i in 0 until pts.size - 1) { // one convex quad per segment, so near-plane clipping stays exact
                    val (ax, az) = pts[i]; val (bx, bz) = pts[i + 1]; val Lq = hypot(bx - ax, bz - az).let { if (it == 0.0) 1.0 else it }
                    val nx = -(bz - az) / Lq * gw; val nz = (bx - ax) / Lq * gw
                    val sp = cam.poly(listOf(V3(ax + nx, 0.06, az + nz), V3(bx + nx, 0.06, bz + nz), V3(bx - nx, 0.06, bz - nz), V3(ax - nx, 0.06, az - nz)), margin = 300.0)
                    if (sp != null) segs.add(P(sp))
                }
                if (segs.isNotEmpty()) pools.add(pf("<path d=\"%s\" fill=\"#FFB866\" opacity=\"%s\" filter=\"url(#litglow)\" style=\"mix-blend-mode:screen\"/>", segs.joinToString(""), if (truthy(th["wet"])) ".05" else ".035"))
                continue
            }
            val run = ArrayList<Pair<V2, Double>>()
            for (k in 0..pts.size) {
                val pt = if (k < pts.size) pts[k] else null
                val c = if (pt != null && !far(pt.x, pt.y)) cam.cam(V3(pt.x, 0.06, pt.y)) else null
                if (c != null && c[2] > 2) { run.add(cam.pc(c) to c[2]); continue }
                if (run.size > 1) {
                    val wpx = (rw + (if (wx) 1.0 else 3.0)) * cam.focal / (PyMath.sumOf(run) { it.second } / run.size)
                    lit.add(pf("<path d=\"M%s\" stroke-width=\"%.1f\"/>", run.joinToString("L") { pf("%.1f %.1f", it.first.x, it.first.y) }, pmax(0.8, wpx)))
                }
                run.clear()
            }
        }
        pools.add(pf("<g fill=\"none\" stroke=\"#FFB866\" stroke-linecap=\"round\" stroke-linejoin=\"round\" opacity=\"%s\" filter=\"url(#litglow)\" style=\"mix-blend-mode:screen\">%s</g>", if (wx) ".3" else ".4", lit.joinToString("")))
        if (truthy(th["wet"])) {
            val core = lit.map { x -> STROKE_W.replace(x) { m -> pf("stroke-width=\"%.1f\"", m.groupValues[1].toDouble() * 0.3) } }
            pools.add(pf("<g fill=\"none\" stroke=\"#FFE2B0\" stroke-linecap=\"round\" stroke-linejoin=\"round\" opacity=\".22\" style=\"mix-blend-mode:screen\">%s</g>", core.joinToString("")))
        }
        for (n in dicts(S["nodes"])) {
            val nx = d(n["x"]); val nz = d(n["z"])
            if (tagsOf(n)["highway"] != "street_lamp" || far(nx, nz)) continue
            val cm = lift(cam, n)
            val c = cm.cam(V3(nx, 0.0, nz))
            if (c[2] < 2) continue
            val p = cam.pc(c); val rx = 10 * cam.focal / c[2]
            val q = cm.proj(V3(nx, 0.0, nz + 10)) ?: p
            pools.add(pf("<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"url(#pool)\"/>", p[0], p[1], rx, pmax(0.6, Math.abs(q[1] - p[1]))))
        }
        for (n in dicts(S["pois"])) {
            val nx = d(n["x"]); val nz = d(n["z"])
            val oh = tagsOf(n)["opening_hours"] as String?
            if (far(nx, nz) || oh.isNullOrEmpty() || !openNow(oh, th)) continue
            val c = cam.cam(V3(nx, 1.5, nz))
            if (c[2] < 2) continue
            val p = cam.pc(c); var r = pmax(3.0, 5 * cam.focal / c[2])
            if (opts.daylight) {
                val g0 = cam.cam(V3(nx, 0.05, nz)); val pg = cam.pc(g0); val rg = 11 * cam.focal / g0[2]
                val qg = cam.proj(V3(nx, 0.05, nz + 11)) ?: pg
                pools.add(pf("<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"url(#openpool)\"/>", pg[0], pg[1], rg, pmax(1.0, Math.abs(qg[1] - pg[1]))))
                r = pmax(4.0, 8 * cam.focal / c[2])
            }
            glow.add(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"url(#open)\"/>", p[0], p[1], r))
        }
    }

    // render/amsterdam.py:615 render: the tag lens, and what it counts in view
    @Suppress("UNCHECKED_CAST")
    private fun lensCounts(S: Dict, cam: Camera, lens: String?, roads: List<Dict>, far: (Double, Double) -> Boolean, W: Double, H: Double,
                           pools: MutableList<String>, glow: MutableList<String>): LinkedHashMap<String, Any?> {
        val lensk = if (lens != null && lens.startsWith("tag:")) lens.substring(4) else null
        val counts = LinkedHashMap<String, Any?>()
        fun count(k: String) { counts[k] = ((counts[k] as Int?) ?: 0) + 1 }
        fun onscreen(q: V2?): Boolean = q != null && 0 <= q[0] && q[0] <= W && 0 <= q[1] && q[1] <= H
        if (lensk in ROAD_KEYS) {
            for (r in roads) {
                val st = tagState(r, lensk!!, "road"); val run = ArrayList<Pair<V2, Double>>(); var seen = false
                val pts = ptsOf(r); val rw = d(r["w"])
                for (k in 0..pts.size) {
                    val pt = if (k < pts.size) pts[k] else null
                    val c = if (pt != null && !far(pt.x, pt.y)) cam.cam(V3(pt.x, 0.08, pt.y)) else null
                    if (c != null && c[2] > 2) { run.add(cam.pc(c) to c[2]); continue }
                    if (run.size > 1) {
                        seen = seen || run.any { onscreen(it.first) }
                        val wpx = (rw + 1.0) * cam.focal / (PyMath.sumOf(run) { it.second } / run.size)
                        pools.add(pf("<path d=\"M%s\" stroke=\"%s\" stroke-width=\"%.1f\" stroke-linecap=\"round\" stroke-linejoin=\"round\" fill=\"none\" opacity=\".92\"/>",
                            run.joinToString("L") { pf("%.1f %.1f", it.first.x, it.first.y) }, LENS_COL[st], pmax(1.0, wpx)))
                    }
                    run.clear()
                }
                if (seen) count(st)
            }
        }
        if (lensk in POI_KEYS) {
            for (n in dicts(S["pois"])) {
                if (far(d(n["x"]), d(n["z"]))) continue
                val q = cam.proj(V3(d(n["x"]), 1.5, d(n["z"])))
                if (!onscreen(q)) continue
                val st = tagState(n, lensk!!, "poi"); count(st)
                glow.add(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"4.4\" fill=\"%s\" stroke=\"#FFFFFF\" stroke-width=\"1.5\"/>", q!![0], q[1], LENS_COL[st]))
            }
        }
        if (lensk in BUILDING_KEYS) {
            for (b in dicts(S["buildings"]).filter { !truthy(it["hidden"]) } + dicts(S["parts"])) {
                val c = centroid(polyOf(b))
                if (far(c.x, c.y) || !onscreen(cam.proj(V3(c.x, if (truthy(b["h"])) d(b["h"]) else 0.0, c.y)))) continue
                val st = tagState(b, lensk!!, "building")
                if (st != "na") count(st)
            }
        }
        if (lensk in TREE_KEYS) {
            for (n in dicts(S["trees"])) {
                if (far(d(n["x"]), d(n["z"])) || !onscreen(cam.proj(V3(d(n["x"]), 4.0, d(n["z"]))))) continue
                count(tagState(n, lensk!!, "tree"))
            }
        }
        return counts
    }

    // render/amsterdam.py:615 render: the shadow group and the footprints' seams (ATLAS_AO); the haze, the wash, the cloud
    // shadows and the drizzle are ScreenLayers'
    private fun overlays(cam: Camera, th: Dict, cfg: Map<String, Any?>, solids: List<Dict>, shadows: List<String>, far: (Double, Double) -> Boolean,
                         wx: Boolean, night: Boolean, opts: RenderOptions, defs: MutableList<String>): Pair<String, String> {
        var sfx = ""
        if (shadows.isNotEmpty() && truthy(th["shadow_blur"])) {
            defs.add(pf("<filter id=\"sblur\" x=\"-10%%\" y=\"-10%%\" width=\"120%%\" height=\"120%%\"><feGaussianBlur stdDeviation=\"%.1f\"/></filter>", th["shadow_blur"]))
            sfx = " filter=\"url(#sblur)\""
        }
        val shadowG = if (shadows.isNotEmpty()) pf("<g opacity=\"%.2f\" fill=\"%s\"%s>%s</g>", th["shadow_op"], th["shadow"], sfx, shadows.joinToString("") { "<path d=\"$it\"/>" }) else ""
        var aoG = ""
        // where walls meet the ground the sky is half hidden: a soft dark seam at every footprint (ATLAS_AO=1)
        if (wx && opts.ao) {
            val foot = ArrayList<String>()
            for (b in (if (!truthy(cfg["no_ao"])) solids else emptyList())) {
                if ((d(b["min"]) > 0.5 && b["_gy"] == null) || tagsOf(b)["building"] == "houseboat") continue
                val poly = polyOf(b); val c = centroid(poly)
                if (far(c.x, c.y)) continue
                val gy = if ("_gy" in b) d(b["_gy"]) else 0.0
                val sp = cam.poly(poly.map { V3(it.x, gy + 0.05, it.y) }, margin = 200.0)
                if (sp != null && area(sp) > 1) foot.add(P(sp))
            }
            defs.add("<filter id=\"aob\" x=\"-5%\" y=\"-5%\" width=\"110%\" height=\"110%\"><feGaussianBlur stdDeviation=\"2.4\"/></filter>")
            aoG = if (foot.isNotEmpty()) pf("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"5.5\" opacity=\"%.2f\" filter=\"url(#aob)\"/><path d=\"%s\" fill=\"none\" stroke=\"#14161E\" stroke-width=\"1.6\" opacity=\"%.2f\"/>",
                foot.joinToString(""), "#1A1E2C", if (truthy(th["sky_k"])) 0.62 else (if (night) 0.3 else 0.48), foot.joinToString(""), if (!night) 0.55 else 0.3) else ""
        }
        return shadowG to aoG
    }

    // ------------------------------------------------------------------ the lenses
    val LENS_COL: Map<String, String> = linkedMapOf("mapped" to "#5DB27E", "gap" to "#F0A24E", "na" to "#ECE8E1")
    val BUILDING_KEYS = listOf("height", "start_date", "roof:shape", "building:colour", "building:levels")
    val TREE_KEYS = listOf("leaf_type", "tree_height")
    val ROAD_KEYS = listOf("lit", "surface")
    val POI_KEYS = listOf("opening_hours", "wheelchair")
    val GAP_KEYS = listOf("height", "start_date", "roof:shape", "building:colour", "building:levels", "leaf_type", "lit", "surface", "opening_hours", "wheelchair")

    @Suppress("UNCHECKED_CAST")
    private fun guessedOf(o: Map<String, Any?>): Map<String, Any?> = (o["guessed"] as Map<String, Any?>?) ?: emptyMap()

    // render/amsterdam.py:990 tag_state
    /** "mapped", "gap" (missing, so a guess or nothing is drawn) or "na" (the key does not apply to this object). */
    fun tagState(o: Map<String, Any?>, key: String, kind: String): String {
        val t = tagsOf(o)
        if (kind == "building") {
            if (key !in BUILDING_KEYS) return "na"
            val part = "building:part" in t
            val g = guessedOf(o)
            when (key) {
                "height" -> return if (truthy(g["height"])) "gap" else "mapped"
                "start_date" -> return if (part) "na" else (if (truthy(o["year"])) "mapped" else "gap")
                "roof:shape" -> return if (truthy(g["roof:shape"])) "gap" else "mapped"
                "building:colour" -> return if (truthy(t["building:colour"]) || truthy(t["building:material"])) "mapped" else "gap"
                "building:levels" -> return if (part) "na" else (if (truthy(t["building:levels"])) "mapped" else "gap")
            }
        }
        if (kind == "tree") {
            if (key !in TREE_KEYS) return "na"
            return if (truthy(t[if (key == "leaf_type") "leaf_type" else "height"])) "mapped" else "gap"
        }
        if (kind == "road") {
            if (key !in ROAD_KEYS) return "na"
            return if (truthy(t[key])) "mapped" else "gap"
        }
        if (kind == "poi") {
            if (key !in POI_KEYS) return "na"
            return if (truthy(t[key])) "mapped" else "gap"
        }
        return "na"
    }

    // render/amsterdam.py:1012 surface_colour
    /** A wall's (or the roof's) base colour: drawn, mapped, from the material, or clay; or the lens's colour. */
    fun surfaceColour(b: Map<String, Any?>, lens: String?, which: String = "wall"): V3 {
        if (lens != null && lens.startsWith("tag:")) return hx(LENS_COL.getValue(tagState(b, lens.substring(4), "building")))
        if (lens == "truth") {
            val g = guessedOf(b)
            if (which == "roof" && truthy(g["roof:colour"])) return hx("#FFFFFF")
            if (which == "wall" && truthy(g["building:colour"])) return hx("#FFFFFF")
        }
        if (lens == "year") {
            val c = yearColour((b["year"] as Number?)?.toInt())
            return if (c != null) hx(c) else hx("#FFFFFF")
        }
        if (which == "roof") {
            if (truthy(b["rcol_draw"])) return hx(b["rcol_draw"] as String)
            val rcol = b["rcol"] as String?
            if (truthy(rcol)) return if (rcol!!.startsWith("#")) hx(rcol) else hx(MATERIAL[rcol] ?: CLAY)
            if (truthy(b["rmat"])) return hx(MATERIAL[b["rmat"] as String] ?: CLAY)
        }
        if (truthy(b["col_draw"])) return hx(b["col_draw"] as String)
        val col = b["col"] as String?
        if (truthy(col) && col!!.startsWith("#")) return hx(col)
        if (truthy(b["mat"])) return hx(MATERIAL[b["mat"] as String] ?: CLAY)
        return hx(CLAY)
    }

    val COMPASS: Map<String, Double> = LinkedHashMap<String, Double>().also { m ->
        "N NNE NE ENE E ESE SE SSE S SSW SW WSW W WNW NW NNW".split(" ").forEachIndexed { i, k -> m[k] = i * 22.5 }
    }

    // render/amsterdam.py:1032 roof_dir
    /** Downhill direction of a skillion roof: roof:direction when mapped, else out through the longest wall (guessed). */
    fun roofDir(b: Map<String, Any?>): V2 {
        val v = PyRe.strip((tagsOf(b)["roof:direction"] as String?) ?: "").uppercase()
        val deg = COMPASS[v] ?: (if (v.isNotEmpty()) Real.num(v) else null)
        if (deg != null) { val r = PyMath.radians(deg); return V2(PyMath.sin(r), -PyMath.cos(r)) }
        val poly = polyOf(b); val n = poly.size
        val i = longestLast(poly)
        val a = poly[i]; val c = poly[(i + 1) % n]; val ce = centroid(poly)
        var nx = c[1] - a[1]; var nz = -(c[0] - a[0]); val Ln = hypot(nx, nz).let { if (it == 0.0) 1.0 else it }; nx /= Ln; nz /= Ln
        if (nx * ((a[0] + c[0]) / 2 - ce.x) + nz * ((a[1] + c[1]) / 2 - ce.y) < 0) { nx = -nx; nz = -nz }
        return V2(nx, nz)
    }

    // render/amsterdam.py:1044 draw_houseboat
    /**
     * building=houseboat: only the outline is mapped. A generic hull at the waterline, a deck, and a set-back cabin
     * with a row of windows; its height and colours stay guessed (white in What is mapped).
     */
    fun drawHouseboat(cam: Camera, th: Dict, sh: Shade, b: Dict, dd: Double, lens: String?): List<String> {
        val night = th["night"] == true; val L = v3(th["_L"]); val WY = WATER_Y; val out = ArrayList<String>()
        val poly = polyOf(b); val ce = centroid(poly); val cx = ce.x; val cz = ce.y
        val lo = WY - 0.15; val deck = WY + 0.7
        val top = pmax(deck + 1.6, WY + 0.3 + d(b["h"]))
        val cab = poly.map { V2(cx + (it.x - cx) * 0.84, cz + (it.y - cz) * 0.8) }
        val roof = poly.map { V2(cx + (it.x - cx) * 0.88, cz + (it.y - cz) * 0.86) }
        val cabCol = surfaceColour(b, lens); val hull = if (lens != "truth") hx("#23272B") else hx("#FFFFFF")
        fun walls(pp: List<V2>, y0: Double, y1: Double, base: V3, win: Boolean = false): List<String> {
            val res = ArrayList<String>()
            val m = pp.size
            for (i in 0 until m) {
                val a = pp[i]; val c = pp[(i + 1) % m]
                val ex = c[0] - a[0]; val ez = c[1] - a[1]; val elen = hypot(ex, ez)
                if (elen < 0.2) continue
                val nrm = vnorm(V3(ez, 0.0, -ex))
                if (vdot(nrm, vsub(cam.C, V3((a[0] + c[0]) / 2, (y0 + y1) / 2, (a[1] + c[1]) / 2))) <= 0) continue
                val lit = !night && vdot(nrm, L) > 0 && sh.lit(V3((a[0] + c[0]) / 2 + nrm[0] * .2, (y0 + y1) / 2, (a[1] + c[1]) / 2 + nrm[2] * .2), skip = b)
                val col = tohex(light(th, base, nrm, lit))
                res.add(face(cam, listOf(V3(a[0], y0, a[1]), V3(c[0], y0, c[1]), V3(c[0], y1, c[1]), V3(a[0], y1, a[1])), col, pf(" stroke=\"%s\" stroke-width=\".3\"", col)))
                if (win && lens == null && dd < 520 && elen > 1.6) {
                    val k = Math.max(1, (elen / 1.7).toInt()); val wq = ArrayList<String>()
                    for (j in 0 until k) {
                        val u0 = (j + 0.22) / k; val u1 = (j + 0.78) / k
                        val sp = cam.poly(Living.quadAt(a, c, y0 + 0.35, y0 + 1.0, u0, u1, 0.03, nrm), margin = 200.0)
                        if (sp != null && area(sp) > 0.2) wq.add(P(sp))
                    }
                    if (wq.isNotEmpty()) res.add(pf("<path d=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\".5\"/>", wq.joinToString(""), if (!night) "url(#glass)" else "#10141E", if (!night) "#E8E4DA" else "#2A2E38"))
                }
            }
            return res
        }
        out.addAll(walls(poly, lo, deck, hull))
        out.add(face(cam, poly.map { V3(it.x, deck, it.y) }, tohex(light(th, if (lens == null) hx("#6E5E4C") else hx("#FFFFFF"), V3(0.0, 1.0, 0.0), !night))))
        out.addAll(walls(cab, deck, top, cabCol, true))
        out.add(face(cam, roof.map { V3(it.x, top, it.y) }, tohex(light(th, surfaceColour(b, lens, "roof"), V3(0.0, 1.0, 0.0), !night)), " stroke=\"#2A2C30\" stroke-width=\".4\""))
        return out
    }

    // render/amsterdam.py:1080 material_face
    /**
     * What a wall's material shows from a distance (building:material, mapped or guessed): a glass curtain wall's floors and mullions,
     * the ribs of metal cladding, the balconies of a tall limestone front, the open decks of a parking garage.
     */
    fun materialFace(cam: Camera, th: Dict, b: Dict, a: V2, bb: V2, ex: Double, ez: Double, elen: Double, nrm: V3, y0: Double, top: Double, dd: Double, base: V3, night: Boolean): List<String> {
        val md = b["mat_draw"]; val kind = b["kind"]; val out = ArrayList<String>()
        val ppm = cam.focal / pmax(dd, 1.0) // pixels per metre at this distance
        val lv = Math.max(1, (b["levels"] as Number).toInt()); val fh = (top - y0) / lv
        fun line(u0: Double, y_0: Double, u1: Double, y_1: Double, off: Double = 0.03): String {
            val p0 = cam.proj(V3(a[0] + ex * u0 + nrm[0] * off, y_0, a[1] + ez * u0 + nrm[2] * off)); val p1 = cam.proj(V3(a[0] + ex * u1 + nrm[0] * off, y_1, a[1] + ez * u1 + nrm[2] * off))
            return if (p0 != null && p1 != null) pf("M%.1f %.1fL%.1f %.1f", p0[0], p0[1], p1[0], p1[1]) else ""
        }
        if (md == "glass") {
            val sp = cam.poly(Living.quadAt(a, bb, y0 + (if (y0 > 0.5) 0.0 else 0.4), top, 0.0, 1.0, 0.02, nrm), margin = 300.0)
            if (sp != null && area(sp) > 1) out.add(pf("<path d=\"%s\" fill=\"%s\" opacity=\"%.2f\"/>", P(sp), if (!night) "url(#glass)" else "#0E1420", if (!night) 0.55 else 0.8))
            val segs = if (fh * ppm > 2.4) (1 until lv).mapTo(ArrayList()) { r -> line(0.0, y0 + r * fh, 1.0, y0 + r * fh) } else ArrayList()
            val step = if (1.5 * ppm >= 3.2) 1.5 else (if (3.0 * ppm >= 3.2) 3.0 else null)
            if (step != null) {
                val n = (elen / step).toInt()
                if (n > 1) for (k in 1 until n) segs.add(line(k.toDouble() / n, y0, k.toDouble() / n, top))
            }
            if (segs.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\".55\" opacity=\".7\"/>", segs.joinToString(""), if (!night) "#E4ECF0" else "#2A3240"))
        } else if (md == "metal" && kind in METAL_SHEDS) {
            val step = if (ppm >= 2.6) 1.0 else (if (ppm >= 1.4) 2.0 else null)
            if (step != null && !night) {
                val n = (elen / step).toInt(); val segs = (1 until n).map { k -> line(k.toDouble() / n, y0 + 0.3, k.toDouble() / n, top - 0.15) }
                if (segs.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\".5\" opacity=\".38\"/>", segs.joinToString(""), tohex(mulc(base, 0.62))))
            }
        } else if (md in STONE_FRONTS && lv >= 5 && kind in STONE_FRONT_KINDS && fh * ppm > 2.2) {
            // the continuous iron balconies of a tall stone front, at the second floor and below the top
            val rows = listOf(2) + (if (lv >= 6) listOf(lv - 1) else emptyList())
            val segs = rows.map { r -> line(0.02, y0 + r * fh + 0.12, 0.98, y0 + r * fh + 0.12, 0.25) }
            out.add(pf("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.2f\" opacity=\".7\"/>", segs.joinToString(""), if (!night) "#2E3036" else "#0C0D10", pmax(0.6, 0.14 * ppm)))
        } else if (kind == "parking" && fh * ppm > 2.0) {
            for (r in 0 until lv) {
                val sp = cam.poly(Living.quadAt(a, bb, y0 + r * fh + 0.95, y0 + (r + 1) * fh - 0.1, 0.03, 0.97, 0.03, nrm), margin = 300.0)
                if (sp != null && area(sp) > 0.4) out.add(pf("<path d=\"%s\" fill=\"%s\" opacity=\".62\"/>", P(sp), if (!night) "#2C2F35" else "#090A0D"))
            }
        }
        return out
    }

    // render/amsterdam.py:1114 draw_building
    /** One building or part, seen from the camera: walls, windows and their traditions, the roof; hazed by its distance at eye level. */
    fun drawBuilding(cam: Camera, th: Dict, sh: Shade, b: Dict, dd: Double, lens: String?): List<String> {
        th["_hz"] = if (lens == null) hazeAt(th, dd) else 0.0
        try {
            return drawBuildingImpl(cam, th, sh, b, dd, lens)
        } finally {
            th["_hz"] = 0.0
        }
    }

    private class Win {
        val fr = ArrayList<String>(); val dark = ArrayList<String>(); val lit = ArrayList<String>(); val rev = ArrayList<String>(); val sill = ArrayList<String>()
    }

    // render/amsterdam.py:1119 _draw_building
    @Suppress("UNCHECKED_CAST")
    private fun drawBuildingImpl(cam: Camera, th: Dict, sh: Shade, b: Dict, dd: Double, lens: String?): List<String> {
        val tags = tagsOf(b); val wx = truthy(th["wx"])
        if (wx && tags["building"] == "houseboat" && (lens == null || lens == "truth")) return drawHouseboat(cam, th, sh, b, dd, lens)
        val night = th["night"] == true; val L = v3(th["_L"])
        val poly = polyOf(b)
        val out = ArrayList<String>()
        var h = d(b["h"]); var y0 = d(b["min"])
        if (wx && tags["building"] == "houseboat") { y0 = WATER_Y + 0.3; h = h + WATER_Y + 0.3 } // afloat, below the quay
        val g = guessedOf(b)
        var roof = if (!(lens == "truth" && truthy(g["roof:shape"]))) b["roof"] as String? else "flat"
        if (roof == "quadruple_saltbox") roof = "hipped" // closest shape this renderer draws
        val rh = if (roof in PITCHED && truthy(b["roof_h"])) d(b["roof_h"]) else 0.0
        val wallTop = h - rh
        val btype = tags["building"] as String?; val yr = (b["year"] as Number?)?.toInt() ?: 0
        val modern = yr >= 1960 && btype !in CHURCHES
        val warehouse = btype in WAREHOUSES
        val places = (b["places"] as List<Dict>?) ?: emptyList()
        val church = btype in CHURCHES || tags["building:part"] == "church" || truthy(b["in_church"])
        val shopfloor = wx && lens == null && (places.isNotEmpty() || btype in SHOP_FLOORS)
        var openHere = false; val plinth = ArrayList<String>(); val cornice = ArrayList<String>()
        if (night && places.isNotEmpty()) {
            openHere = places.any { p -> val oh = tagsOf(p)["opening_hours"] as String?; !oh.isNullOrEmpty() && openNow(oh, th) }
        }
        var dirv = V2(0.0, 0.0); var p0_ = 0.0; var span = 1.0
        if (roof == "skillion" && rh != 0.0) {
            dirv = roofDir(b)
            val pr = poly.map { it[0] * dirv[0] + it[1] * dirv[1] }; p0_ = City.pyMinOf(pr); span = pmax(0.5, City.pyMaxOf(pr) - p0_)
        }
        fun skTop(p: V2): Double = h - rh * ((p[0] * dirv[0] + p[1] * dirv[1]) - p0_) / span
        val base = surfaceColour(b, lens)
        val n = poly.size
        val TD = if (lens == null && wx) b["trad"] as Map<String, Any?>? else null
        val td = truthy(TD)
        val ridgeU = if (td && roof in RIDGED && rh != 0.0) tradRidge(b, poly) else null
        val winD = d(th["_win_d"] ?: 390)
        for (i in 0 until n) {
            val a = poly[i]; val bb = poly[(i + 1) % n]
            val ex = bb[0] - a[0]; val ez = bb[1] - a[1]
            val elen = hypot(ex, ez)
            if (elen < 0.3) continue
            val nrm = vnorm(V3(ez, 0.0, -ex))
            if (vdot(nrm, vsub(cam.C, V3((a[0] + bb[0]) / 2, (y0 + wallTop) / 2, (a[1] + bb[1]) / 2))) <= 0) continue
            val ncol = if (dd < 460) Math.max(1, (elen / 9).toInt()) else 1
            val nrow = if (dd < 460) Math.max(1, Math.min(3, ((wallTop - y0) / 7).toInt())) else 1
            val facing = vdot(nrm, L) > 0 && !night
            val exact = truthy(th["_eye"]) && facing && lens == null && dd < 110 && elen > 0.6
            var prof: ArrayList<Pair<Double, Double>>? = null
            if (exact) {
                // up close the shadow of the street's other side is a sharp line across the wall, traced from the real sun
                val colL = tohex(light(th, base, nrm, true)); val colD = tohex(light(th, base, nrm, false))
                out.add(face(cam, Living.quadAt(a, bb, y0, wallTop, 0.0, 1.0), colL, pf(" stroke=\"%s\" stroke-width=\".3\"", colL)))
                val ns = Math.max(2, (elen / 0.35).toInt()); prof = ArrayList()
                for (k in 0..ns) {
                    val u = 0.02 + 0.96 * k / ns
                    val hs = sh.shadowHeight(a[0] + ex * u + nrm[0] * 0.15, a[1] + ez * u + nrm[2] * 0.15, skip = b)
                    prof.add(k.toDouble() / ns to pmin(wallTop, pmax(y0, hs)))
                }
                val strips = ArrayList<String>()
                for (k in 0 until ns) {
                    val (u0, h0) = prof[k]; val (u1, h1) = prof[k + 1]
                    if (h0 <= y0 + 0.01 && h1 <= y0 + 0.01) continue
                    val q = listOf(
                        V3(a[0] + ex * u0 + nrm[0] * .01, y0, a[1] + ez * u0 + nrm[2] * .01), V3(a[0] + ex * u1 + nrm[0] * .01, y0, a[1] + ez * u1 + nrm[2] * .01),
                        V3(a[0] + ex * u1 + nrm[0] * .01, h1, a[1] + ez * u1 + nrm[2] * .01), V3(a[0] + ex * u0 + nrm[0] * .01, h0, a[1] + ez * u0 + nrm[2] * .01),
                    )
                    val sp = cam.poly(q, margin = 300.0)
                    if (sp != null) strips.add(P(sp))
                }
                if (strips.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"%s\"/>", strips.joinToString(""), colD))
            }
            for (r_ in 0 until (if (!exact) nrow else 0)) {
                val ya = y0 + (wallTop - y0) * r_ / nrow; val yb = y0 + (wallTop - y0) * (r_ + 1) / nrow
                for (c_ in 0 until ncol) {
                    val u0 = c_.toDouble() / ncol; val u1 = (c_ + 1).toDouble() / ncol
                    val lit = facing && sh.lit(V3(a[0] + ex * (u0 + u1) / 2 + nrm[0] * 0.15, (ya + yb) / 2, a[1] + ez * (u0 + u1) / 2 + nrm[2] * 0.15), skip = b)
                    val col = tohex(light(th, base, nrm, lit))
                    out.add(face(cam, Living.quadAt(a, bb, ya, yb, u0, u1), col, pf(" stroke=\"%s\" stroke-width=\".3\"", col)))
                }
            }
            if (td && !("building:part" in tags && !church)) {
                out.addAll(tradWall(cam, th, sh, b, TD!!, a, bb, ex, ez, elen, nrm, y0, wallTop, dd, facing, night, ridgeU, roof))
            }
            if (truthy(th["_eye"]) && wx && lens == null && b["mat_draw"] == "brick" && dd < 45 && !night) {
                out.addAll(Closeup.coursing(cam, a, bb, ex, ez, elen, nrm, y0 + (if (y0 <= 0.5) 0.6 else 0.0), wallTop))
            }
            if (lens == "truth" && truthy(b["_hatch"]) && truthy(g["building:colour"])) {
                out.add(face(cam, Living.quadAt(a, bb, y0, wallTop, 0.0, 1.0), "url(#hatchq)")) // quest: colour unknown, hatched so it never reads as painted white
            }
            if (roof == "skillion" && rh != 0.0) {
                val ta = skTop(a); val tb = skTop(bb)
                if (pmax(ta, tb) > wallTop + 0.05) {
                    val q = listOf(V3(a[0], wallTop, a[1]), V3(bb[0], wallTop, bb[1]), V3(bb[0], tb, bb[1]), V3(a[0], ta, a[1]))
                    val lc = facing && sh.lit(V3((a[0] + bb[0]) / 2 + nrm[0] * .15, (wallTop + (ta + tb) / 2) / 2, (a[1] + bb[1]) / 2 + nrm[2] * .15), skip = b)
                    val col = tohex(light(th, base, nrm, lc)); out.add(face(cam, q, col, pf(" stroke=\"%s\" stroke-width=\".3\"", col)))
                }
            }
            val part = "building:part" in tags && !church
            if (wx && lens == null && !part && dd < 760) {
                if (y0 <= 0.5) { // a dark stone plinth: the building stands on the street, not above it
                    val sp = cam.poly(Living.quadAt(a, bb, y0, y0 + (if (dd < 300) 0.6 else 0.9), 0.0, 1.0, 0.02, nrm), margin = 200.0)
                    if (sp != null && area(sp) > 0.15) plinth.add(P(sp))
                }
                if (((yr != 0 && yr < 1800) || roof == "mansard" || (td && truthy(TD!!["cornice"]))) && wallTop - y0 > 5 && dd < 520) { // a white cornice
                    val sp = cam.poly(Living.quadAt(a, bb, wallTop - 0.42, wallTop, 0.0, 1.0, 0.05, nrm), margin = 200.0)
                    if (sp != null && area(sp) > 0.15) cornice.add(P(sp))
                }
                if (shopfloor && elen > 2.4 && y0 <= 0.5) { // a glazed ground floor where shops, cafés and hotels are mapped
                    val gq = cam.poly(Living.quadAt(a, bb, y0 + 0.65, y0 + 2.95, 0.05, 0.95, 0.03, nrm), margin = 300.0)
                    if (gq != null && area(gq) > 0.4) {
                        val hz = (th["_hz"] as Number?)?.toDouble() ?: 0.0
                        out.add(pf("<path d=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\".8\"%s/>", P(gq), if (night) (if (openHere) "#FFD08C" else "#0E1119") else "url(#glass)",
                            hzc(th, if (!night) "#2A2D30" else "#30343E"), if (hz > 0.04 && !(night && openHere)) pf(" fill-opacity=\"%.2f\"", 1 - 0.85 * d(th["_hz"])) else ""))
                    }
                }
                if (btype == "construction") out.add(face(cam, Living.quadAt(a, bb, y0, wallTop, 0.0, 1.0, 0.3, nrm), "url(#scaf)"))
            }
            if ((lens == null || lens == "year") && wx && !part && elen > 2.0 && dd < winD * 1.35) {
                out.addAll(materialFace(cam, th, b, a, bb, ex, ez, elen, nrm, y0, wallTop, dd, base, night))
            }
            // windows: a generic rendering of building:levels (guessed or mapped); tall canal-house windows
            if (church && dd < 520 && (lens == null || lens == "year") && wx) {
                out.addAll(Closeup.churchWindows(cam, th, a, bb, ex, ez, elen, nrm, y0, wallTop, dd, tohex(light(th, mulc(base, 0.55), nrm, false)), night))
            } else if (dd < winD && (lens == null || lens == "year") && elen > 2.2 && !part && btype != "houseboat" && !truthy(b["no_win"])) {
                windows(cam, th, sh, b, a, bb, ex, ez, elen, nrm, y0, wallTop, dd, lens, night, wx, modern, td, TD, shopfloor, facing, prof, base, warehouse, out)
            }
        }
        if (plinth.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"%s\"/>", plinth.joinToString(""), hzc(th, if (!night) "#47443F" else "#121318")))
        if (cornice.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"%s\"/>", cornice.joinToString(""), hzc(th, if (!night) "#E6E1D6" else "#3A3C44")))
        val edge = if (!night) "#6F695F" else "#2E3446"
        val rbase = surfaceColour(b, lens, "roof")
        if (roof == "pyramidal" && rh != 0.0) {
            val ce = centroid(poly); val apex = V3(ce.x, h, ce.y)
            for (i in 0 until n) {
                val a = poly[i]; val bb = poly[(i + 1) % n]
                val tri = listOf(V3(a[0], wallTop, a[1]), V3(bb[0], wallTop, bb[1]), apex)
                var nr = vnorm(vcross(vsub(tri[1], tri[0]), vsub(tri[2], tri[0])))
                if (nr[1] < 0) nr = neg(nr)
                val c = cen(tri)
                if (vdot(nr, vsub(cam.C, c)) <= 0) continue
                out.add(face(cam, tri, tohex(light(th, rbase, nr, !night && vdot(nr, L) > 0 && sh.lit(c, skip = b)))))
            }
        } else if (roof in RIDGED && rh != 0.0 && td) {
            out.addAll(tradRoof(cam, th, sh, b, TD!!, poly, wallTop, h, rh, roof!!, rbase, base, edge, dd, night, ridgeU!!))
        } else if (roof in RIDGED && rh != 0.0) {
            out.addAll(gableRoof(cam, th, sh, b, poly, wallTop, h, rh, roof, rbase, base, edge, L, night, wx, lens, yr, dd))
        } else if (roof == "skillion" && rh != 0.0) {
            val s_ = rh / span; val nr = vnorm(V3(dirv[0] * s_, 1.0, dirv[1] * s_))
            val ce = centroid(poly); val c = V3(ce.x, h - rh / 2, ce.y)
            if (vdot(nr, vsub(cam.C, c)) > 0) {
                val lr = !night && vdot(nr, L) > 0 && sh.lit(V3(ce.x, h - rh / 2 + 0.1, ce.y), skip = b)
                out.add(face(cam, poly.map { V3(it.x, skTop(it), it.y) }, tohex(light(th, rbase, nr, lr)), pf(" stroke=\"%s\" stroke-width=\".45\"", edge)))
            }
        } else if (roof == "mansard" && rh != 0.0) {
            out.addAll(mansard(cam, th, sh, b, poly, wallTop, h, rh, rbase, edge, dd, lens, night))
        } else if (cam.C[1] > h) {
            val ce = centroid(poly)
            val litR = !night && sh.lit(V3(ce.x, h + 0.05, ce.y), skip = b)
            out.add(face(cam, poly.map { V3(it.x, h, it.y) }, tohex(light(th, rbase, V3(0.0, 1.0, 0.0), litR)), pf(" stroke=\"%s\" stroke-width=\".45\"", edge)))
        }
        if (td && truthy(TD!!["chimney"]) && dd < winD * 1.2) {
            out.addAll(tradChimneys(cam, th, sh, b, TD, poly, wallTop, h, rh, roof, ridgeU, night))
        }
        return out
    }

    // render/amsterdam.py:1119 _draw_building: the windows of one wall (building:levels, guessed or mapped), deep and barred close up
    @Suppress("UNCHECKED_CAST")
    private fun windows(
        cam: Camera, th: Dict, sh: Shade, b: Dict, a: V2, bb: V2, ex: Double, ez: Double, elen: Double, nrm: V3, y0: Double, wallTop: Double, dd: Double,
        lens: String?, night: Boolean, wx: Boolean, modern: Boolean, td: Boolean, TD: Map<String, Any?>?, shopfloor: Boolean, facing: Boolean,
        prof: List<Pair<Double, Double>>?, base: V3, warehouse: Boolean, out: MutableList<String>,
    ) {
        val win = Win()
        val lv = (b["levels"] as Number).toInt(); val fh = (wallTop - y0) / Math.max(1, lv)
        val deep = wx && lens == null && dd < 160 // close up, a window is a hole in the wall: reveal, recessed glass, sill
        val paris = WIN_STYLE == "paris" && !modern && b["mat_draw"] in PARIS_FRONTS
        var wcols = Math.max(1, (elen / (if (paris) 2.9 else if (modern) 3.4 else 2.1)).toInt())
        val TW = if (td) TD!!["win"] as Map<String, Any?>? else null; val shut = if (td) TD!!["shutter"] as String? else null
        val tw = truthy(TW); val leaves = ArrayList<String>()
        if (tw) wcols = Math.max(1, (elen / d(TW!!["bay"])).toInt())
        for (r_ in (if (shopfloor) 1 else 0) until lv) {
            var ya = y0 + r_ * fh + fh * (if (r_ != 0) 0.22 else 0.3); var yb = ya + fh * (if (r_ != 0) (if (modern) 0.62 else 0.58) else 0.5)
            if (paris) { ya = y0 + r_ * fh + fh * 0.13; yb = ya + fh * 0.73 } // French windows, nearly floor to ceiling
            if (tw) { ya = y0 + r_ * fh + fh * d(TW!!["top"]); yb = ya + fh * d(TW["h"]) }
            for (c_ in 0 until wcols) {
                var u0 = (c_ + (if (modern) 0.1 else if (paris) 0.31 else 0.28)) / wcols; var u1 = (c_ + (if (modern) 0.9 else if (paris) 0.69 else 0.72)) / wcols
                if (tw) { u0 = (c_ + 0.5 - d(TW!!["w"]) / 2) / wcols; u1 = (c_ + 0.5 + d(TW["w"]) / 2) / wcols }
                if ((truthy(shut) && r_ > 0) || (truthy(shut) && !shopfloor && r_ == 0)) {
                    val lw = (u1 - u0) * 0.52
                    for ((s0, s1) in listOf((u0 - lw) to (u0 - 0.004), (u1 + 0.004) to (u1 + lw))) {
                        val lq = cam.poly(Living.quadAt(a, bb, ya, yb, s0, s1, 0.07, nrm), margin = 300.0)
                        if (lq != null && area(lq) > 0.12) leaves.add(P(lq))
                    }
                }
                val wl: Boolean
                if (prof != null) {
                    val um = (u0 + u1) / 2; val k = Math.min(prof.size - 2, (um * (prof.size - 1)).toInt()); val f = um * (prof.size - 1) - k
                    wl = (ya + yb) / 2 > prof[k].second * (1 - f) + prof[k + 1].second * f
                } else wl = facing && sh.lit(V3(a[0] + ex * (u0 + u1) / 2 + nrm[0] * 0.2, (ya + yb) / 2, a[1] + ez * (u0 + u1) / 2 + nrm[2] * 0.2), skip = b)
                var sp = cam.poly(Living.quadAt(a, bb, ya, yb, u0, u1, 0.04, nrm), margin = 300.0)
                var rec = 0.04
                if (deep && sp != null && area(sp) > 70) {
                    win.rev.add(P(sp)); rec = -0.16
                    sp = cam.poly(Living.quadAt(a, bb, ya, yb, u0, u1, rec, nrm), margin = 300.0)
                    val du = 0.07 / elen
                    val sl = cam.poly(Living.quadAt(a, bb, ya - 0.11, ya, u0 - du, u1 + du, 0.07, nrm), margin = 300.0)
                    if (sl != null) win.sill.add(P(sl))
                }
                if (sp != null && area(sp) > 0.25) (if (wl) win.lit else win.dark).add(P(sp))
                if (sp != null && dd < 140 && area(sp) > 30) { // close up, the sash bars of a canal-house window
                    val q4 = Living.quadAt(a, bb, ya, yb, u0, u1, rec + 0.01, nrm)
                    val m1 = cam.proj(V3((q4[0][0] + q4[1][0]) / 2, (q4[0][1] + q4[1][1]) / 2, (q4[0][2] + q4[1][2]) / 2))
                    val m2 = cam.proj(V3((q4[2][0] + q4[3][0]) / 2, (q4[2][1] + q4[3][1]) / 2, (q4[2][2] + q4[3][2]) / 2))
                    val h1 = cam.proj(V3(q4[0][0] + (q4[3][0] - q4[0][0]) * 0.42, q4[0][1] + (q4[3][1] - q4[0][1]) * 0.42, q4[0][2] + (q4[3][2] - q4[0][2]) * 0.42))
                    val h2 = cam.proj(V3(q4[1][0] + (q4[2][0] - q4[1][0]) * 0.42, q4[1][1] + (q4[2][1] - q4[1][1]) * 0.42, q4[1][2] + (q4[2][2] - q4[1][2]) * 0.42))
                    if (m1 != null && m2 != null && h1 != null && h2 != null) win.fr.add(pf("M%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1f", m1[0], m1[1], m2[0], m2[1], h1[0], h1[1], h2[0], h2[1]))
                }
            }
        }
        var fr = if (!night) "#F2EEE6" else "#3A3F4E"
        var gd: String; var gl: String
        if (wx && !night) { gd = "url(#glass)"; gl = "url(#glassLit)" } else { gd = if (!night) "#2E3440" else "#141824"; gl = "#9FB2C4" }
        if (warehouse && wx) { gd = if (!night) "#2C4A3B" else "#0E1612"; gl = gd } // warehouse shutters, not glass
        if (paris && !night) gl = gd // tall panes in a pale stone front read dark, sun or not
        if (tw) {
            fr = if (!night) TW!!["frame"] as String else "#3A3F4E"
            if (TW!!["glass"] == "dark" && !night) gl = gd
        }
        if (leaves.isNotEmpty()) { // open shutters either side of each window, in the paint of the house
            out.add(pf("<path d=\"%s\" fill=\"%s\"/>", leaves.joinToString(""), tohex(light(th, hx(shut!!), nrm, facing && !night))))
        }
        val fw = if (deep) pmax(0.7, 0.07 * cam.focal / pmax(dd, 1.0)) else 0.7
        val hz = (th["_hz"] as Number?)?.toDouble() ?: 0.0
        val go = if (hz > 0.04) pf(" fill-opacity=\"%.2f\"", 1 - 0.85 * hz) else ""
        fr = hzc(th, fr)
        if (win.rev.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"%s\"/>", win.rev.joinToString(""), tohex(light(th, mulc(base, 0.55), nrm, false))))
        if (win.dark.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%.1f\"%s/>", win.dark.joinToString(""), gd, fr, fw, go))
        if (win.lit.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%.1f\"%s/>", win.lit.joinToString(""), gl, fr, fw, go))
        if (win.fr.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.1f\"/>", win.fr.joinToString(""), fr,
            if (deep) pmax(0.7, 0.045 * cam.focal / pmax(dd, 1.0)) else pmax(0.7, 9 / pmax(dd, 1.0))))
        if (win.sill.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"%s\"/>", win.sill.joinToString(""), hzc(th, if (!night) "#DAD5CA" else "#2E323E")))
    }

    // render/amsterdam.py:1119 _draw_building: a gabled or hipped roof without a tradition, its gable walls first so the roof covers any notch
    private fun gableRoof(
        cam: Camera, th: Dict, sh: Shade, b: Dict, poly: List<V2>, wallTop: Double, h: Double, rh: Double, roof: String?, rbase: V3, base: V3, edge: String,
        L: V3, night: Boolean, wx: Boolean, lens: String?, yr: Int, dd: Double,
    ): List<String> {
        val n = poly.size
        // ridge along the longest side unless roof:orientation=across; a hipped roof pulls the ridge in at both ends
        val i0 = longestLast(poly)
        val a0 = poly[i0]; val b0 = poly[(i0 + 1) % n]
        var ux = b0[0] - a0[0]; var uz = b0[1] - a0[1]; val Lg = hypot(ux, uz); ux /= Lg; uz /= Lg
        if (b["orient"] == "across") { val t = ux; ux = -uz; uz = t }
        val ce = centroid(poly); val cx = ce.x; val cz = ce.y
        val proj = poly.map { (it[0] - cx) * ux + (it[1] - cz) * uz }
        val s0 = City.pyMinOf(proj); val s1 = City.pyMaxOf(proj)
        val gab = ArrayList<String>(); val slopes = ArrayList<String>() // gable walls first, so the roof covers any hidden notch
        for (i in 0 until n) {
            val a = poly[i]; val bb = poly[(i + 1) % n]
            val pa = V3(a[0], wallTop, a[1]); val pb = V3(bb[0], wallTop, bb[1])
            // project each eave point onto the ridge line
            val inset = if (roof == "hipped") pmin(rh, (s1 - s0) / 2) else 0.0
            fun onRidge(p: V3): V3 {
                val sv = pmin(s1 - inset, pmax(s0 + inset, (p[0] - cx) * ux + (p[2] - cz) * uz))
                return V3(cx + ux * sv, h, cz + uz * sv)
            }
            val qa = onRidge(pa); val qb = onRidge(pb)
            if (dist3(qa, qb) < 0.3 && roof == "hipped") {
                val tri = listOf(pa, pb, qa)
                var nr = vnorm(vcross(vsub(tri[1], tri[0]), vsub(tri[2], tri[0])))
                if (nr[1] < 0) nr = neg(nr)
                val c = cen(tri)
                if (vdot(nr, vsub(cam.C, c)) <= 0) continue
                slopes.add(face(cam, tri, tohex(light(th, rbase, nr, !night && vdot(nr, L) > 0 && sh.lit(c, skip = b))), pf(" stroke=\"%s\" stroke-width=\".25\"", edge)))
                continue
            }
            if (dist3(qa, qb) < 0.3) {
                // gable end: a vertical wall triangle in the wall material
                val tri = listOf(pa, pb, V3((qa[0] + qb[0]) / 2, h, (qa[2] + qb[2]) / 2))
                val nr = vnorm(V3(bb[1] - a[1], 0.0, -(bb[0] - a[0])))
                val c = cen(tri)
                if (vdot(nr, vsub(cam.C, c)) <= 0) continue
                if (wx && lens == null && yr != 0 && yr < 1850 && dd < 260) { // the hoist beam under an old gable
                    val ap = tri[2]; val hb0 = cam.proj(V3(ap[0], h - rh * 0.25, ap[2])); val hb1 = cam.proj(V3(ap[0] + nr[0] * 0.7, h - rh * 0.25, ap[2] + nr[2] * 0.7))
                    if (hb0 != null && hb1 != null) slopes.add(pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"#2A221C\" stroke-width=\"%.2f\" stroke-linecap=\"round\"/>", hb0[0], hb0[1], hb1[0], hb1[1], pmax(0.5, 0.12 * cam.focal / dd)))
                }
                gab.add(face(cam, tri, tohex(light(th, base, nr, !night && vdot(nr, L) > 0 && sh.lit(V3(c[0] + nr[0] * .2, c[1], c[2] + nr[2] * .2), skip = b))), pf(" stroke=\"%s\" stroke-width=\".25\"", edge)))
                continue
            }
            val q = listOf(pa, pb, qb, qa)
            var nr = vnorm(vcross(vsub(q[1], q[0]), vsub(q[3], q[0])))
            if (nr[1] < 0) nr = neg(nr)
            val c = cen(q)
            if (vdot(nr, vsub(cam.C, c)) <= 0) continue
            slopes.add(face(cam, q, tohex(light(th, rbase, nr, !night && vdot(nr, L) > 0 && sh.lit(c, skip = b))), pf(" stroke=\"%s\" stroke-width=\".25\"", edge)))
        }
        return gab + slopes
    }

    // ------------------------------------------------------------------ the kit: details a building tradition gives (render/traditions)
    // render/amsterdam.py:1354 trad_ridge
    /** The ridge's direction: along the longest side, across it where roof:orientation=across (mapped or guessed). */
    fun tradRidge(b: Map<String, Any?>, poly: List<V2>): V2 {
        val n = poly.size; val i0 = longestFirst(poly)
        val a = poly[i0]; val c = poly[(i0 + 1) % n]; var ux = c[0] - a[0]; var uz = c[1] - a[1]; val Lg = hypot(ux, uz).let { if (it == 0.0) 1.0 else it }; ux /= Lg; uz /= Lg
        return if (b["orient"] == "across") V2(-uz, ux) else V2(ux, uz)
    }

    /** The mitred corners of a ring whose sides moved [dl] along their normals (`s` picks the side), capped at 2.6 dl. */
    private fun mitre(poly: List<V2>, dl: Double, inward: Boolean): List<V2> {
        val n = poly.size; val i0 = longestFirst(poly)
        val a0 = poly[i0]; val c0 = poly[(i0 + 1) % n]; val L0 = dist(a0, c0).let { if (it == 0.0) 1.0 else it }
        val s = if (pip((a0[0] + c0[0]) / 2 - (c0[1] - a0[1]) / L0 * 0.05, (a0[1] + c0[1]) / 2 + (c0[0] - a0[0]) / L0 * 0.05, poly)) 1 else -1
        val lines = ArrayList<DoubleArray>(n)
        for (i in 0 until n) {
            val a = poly[i]; val c = poly[(i + 1) % n]; val ex = c[0] - a[0]; val ez = c[1] - a[1]; val Le = hypot(ex, ez).let { if (it == 0.0) 1.0 else it }
            if (inward) {
                val nx = -ez / Le * s; val nz = ex / Le * s
                lines.add(doubleArrayOf(a[0] + nx * dl, a[1] + nz * dl, ex / Le, ez / Le))
            } else {
                lines.add(doubleArrayOf(a[0] + ez / Le * s * dl, a[1] - ex / Le * s * dl, ex / Le, ez / Le))
            }
        }
        val out = ArrayList<V2>(n)
        for (i in 0 until n) {
            val l1 = lines[(i - 1 + n) % n]; val l2 = lines[i]
            val p1x = l1[0]; val p1z = l1[1]; val d1x = l1[2]; val d1z = l1[3]; val p2x = l2[0]; val p2z = l2[1]; val d2x = l2[2]; val d2z = l2[3]
            val den = d1x * d2z - d1z * d2x
            var x: Double; var z: Double
            if (Math.abs(den) < 1e-4) { x = p2x; z = p2z } else {
                val t = ((p2x - p1x) * d2z - (p2z - p1z) * d2x) / den; x = p1x + d1x * t; z = p1z + d1z * t
            }
            val vx = x - poly[i][0]; val vz = z - poly[i][1]; val m = hypot(vx, vz)
            if (m > 2.6 * dl) { x = poly[i][0] + vx / m * 2.6 * dl; z = poly[i][1] + vz / m * 2.6 * dl }
            out.add(V2(x, z))
        }
        return out
    }

    /** True when some side of [out] runs against its side in [poly] (the ring turned over). */
    private fun turned(poly: List<V2>, out: List<V2>): Boolean {
        val n = poly.size
        for (i in 0 until n) {
            val e0x = poly[(i + 1) % n][0] - poly[i][0]; val e0z = poly[(i + 1) % n][1] - poly[i][1]
            val e1x = out[(i + 1) % n][0] - out[i][0]; val e1z = out[(i + 1) % n][1] - out[i][1]
            if (e0x * e1x + e0z * e1z < 0) return true
        }
        return false
    }

    // render/amsterdam.py:1360 offset_ring
    /** The ring moved [dl] metres outward (eaves, verges), or null where a side would turn over. */
    fun offsetRing(poly: List<V2>, dl: Double): List<V2>? {
        val out = mitre(poly, dl, false)
        return if (turned(poly, out)) null else out
    }

    // render/amsterdam.py:1384 box3
    /** A small lit box (a chimney stack, a pot): centre [c0], axis [u], half sizes, from [y0] to [y1]. */
    fun box3(cam: Camera, th: Dict, sh: Shade, b: Dict, c0: V2, u: V2, hu: Double, hv: Double, y0: Double, y1: Double, col: V3, night: Boolean): List<String> {
        val L = v3(th["_L"]); val cx = c0.x; val cz = c0.y; val ux = u.x; val uz = u.y; val vx = -uz; val vz = ux
        fun p8(su: Int, sv: Int, y: Double) = V3(cx + ux * su * hu + vx * sv * hv, y, cz + uz * su * hu + vz * sv * hv)
        val faces = listOf(
            V3(ux, 0.0, uz) to listOf(p8(1, -1, y0), p8(1, 1, y0), p8(1, 1, y1), p8(1, -1, y1)),
            V3(-ux, 0.0, -uz) to listOf(p8(-1, 1, y0), p8(-1, -1, y0), p8(-1, -1, y1), p8(-1, 1, y1)),
            V3(vx, 0.0, vz) to listOf(p8(1, 1, y0), p8(-1, 1, y0), p8(-1, 1, y1), p8(1, 1, y1)),
            V3(-vx, 0.0, -vz) to listOf(p8(-1, -1, y0), p8(1, -1, y0), p8(1, -1, y1), p8(-1, -1, y1)),
            V3(0.0, 1.0, 0.0) to listOf(p8(-1, -1, y1), p8(1, -1, y1), p8(1, 1, y1), p8(-1, 1, y1)),
        )
        val out = ArrayList<String>()
        for ((n3, q) in faces) {
            val m = cen(q)
            if (vdot(n3, vsub(cam.C, m)) <= 0) continue
            val lit = !night && vdot(n3, L) > 0 && sh.lit(V3(m[0] + n3[0] * .2, m[1] + n3[1] * .2, m[2] + n3[2] * .2), skip = b)
            val c = tohex(light(th, col, n3, lit)); val f = face(cam, q, c, pf(" stroke=\"%s\" stroke-width=\".25\"", c))
            if (f.isNotEmpty()) out.add(f)
        }
        return out
    }

    // render/amsterdam.py:1400 trad_wall
    /** On one wall: a stucco ground floor under brick (London), timber boards and white corner boards (Norway), the shadow of the eaves. */
    @Suppress("UNCHECKED_CAST")
    fun tradWall(
        cam: Camera, th: Dict, sh: Shade, b: Dict, TD: Map<String, Any?>, a: V2, bb: V2, ex: Double, ez: Double, elen: Double, nrm: V3, y0: Double, wallTop: Double,
        dd: Double, facing: Boolean, night: Boolean, ridgeU: V2?, roof: String?,
    ): List<String> {
        val out = ArrayList<String>(); val ppm = cam.focal / pmax(dd, 1.0)
        val bd = TD["band"] as Map<String, Any?>?
        if (truthy(bd) && y0 <= (if ("_gy" in b) d(b["_gy"]) else 0.0) + 0.5) {
            val bh = pmin(d(bd!!["h"]), (wallTop - y0) * 0.45)
            val lt = facing && sh.lit(V3(a[0] + ex * .5 + nrm[0] * .2, y0 + bh / 2, a[1] + ez * .5 + nrm[2] * .2), skip = b)
            val f = face(cam, Living.quadAt(a, bb, y0, y0 + bh, 0.0, 1.0, 0.02, nrm), tohex(light(th, hx(bd["colour"] as String), nrm, lt)))
            if (f.isNotEmpty()) out.add(f)
        }
        val bo = TD["boards"] as Map<String, Any?>?
        if (truthy(bo)) {
            val spacing = d(bo!!["spacing"])
            if (ppm * spacing > 2.2 && !night) {
                val nb = (elen / spacing).toInt(); val segs = ArrayList<String>()
                for (k in 1 until nb) {
                    val u = k.toDouble() / nb
                    val p0 = cam.proj(V3(a[0] + ex * u + nrm[0] * .02, y0 + 0.25, a[1] + ez * u + nrm[2] * .02)); val p1 = cam.proj(V3(a[0] + ex * u + nrm[0] * .02, wallTop - 0.05, a[1] + ez * u + nrm[2] * .02))
                    if (p0 != null && p1 != null) segs.add(pf("M%.1f %.1fL%.1f %.1f", p0[0], p0[1], p1[0], p1[1]))
                }
                if (segs.isNotEmpty()) out.add(pf("<path d=\"%s\" stroke=\"%s\" stroke-width=\".45\" opacity=\".32\" fill=\"none\"/>", segs.joinToString(""),
                    tohex(mulc(hx((b["col_draw"] as String?)?.takeIf { it.isNotEmpty() } ?: "#888888"), 0.62))))
            }
            if (ppm * 0.16 > 0.5 && elen > 1.0) {
                val cw = 0.16 / elen; val c = tohex(light(th, hx(bo["corner"] as String), nrm, facing && !night))
                for ((u0, u1) in listOf(0.0 to cw, (1 - cw) to 1.0)) {
                    val f = face(cam, Living.quadAt(a, bb, y0, wallTop, u0, u1, 0.03, nrm), c)
                    if (f.isNotEmpty()) out.add(f)
                }
            }
        }
        val E = if (truthy(TD["eaves"])) d(TD["eaves"]) else 0.0
        if (E != 0.0 && !night && roof in RIDGED && (roof == "hipped" || (ridgeU != null && Math.abs(ex * ridgeU[0] + ez * ridgeU[1]) / pmax(elen, 1e-6) > 0.7))) {
            val f = face(cam, Living.quadAt(a, bb, wallTop - pmin(1.1, E * 1.7), wallTop, 0.0, 1.0, 0.025, nrm), "#141822", " opacity=\".26\"")
            if (f.isNotEmpty()) out.add(f)
        }
        return out
    }

    // render/amsterdam.py:1428 trad_roof
    /** A gabled or hipped roof with the tradition's eaves and verges overhanging the walls, tile courses up close, and a lit ridge. */
    fun tradRoof(
        cam: Camera, th: Dict, sh: Shade, b: Dict, TD: Map<String, Any?>, poly: List<V2>, wallTop: Double, h: Double, rh: Double, roof: String, rbase: V3, base: V3,
        edge: String, dd: Double, night: Boolean, ridgeU: V2,
    ): List<String> {
        val L = v3(th["_L"]); val n = poly.size; val ux = ridgeU.x; val uz = ridgeU.y; val ce = centroid(poly); val cx = ce.x; val cz = ce.y
        val hd = pmax(1.0, City.pyMaxOf(poly.map { p -> Math.abs(-(p[0] - cx) * uz + (p[1] - cz) * ux) }))
        val slope = rh / hd
        var E = if (truthy(TD["eaves"])) d(TD["eaves"]) else 0.0
        var ring = if (E != 0.0) offsetRing(poly, E) else null
        if (ring == null) { ring = poly; E = 0.0 }
        val gab = ArrayList<String>(); val slopes = ArrayList<String>(); val rows = ArrayList<String>()
        fun onRidgeF(pp: List<V2>): Pair<Double, Double> {
            val proj = pp.map { (it[0] - cx) * ux + (it[1] - cz) * uz }; return City.pyMinOf(proj) to City.pyMaxOf(proj)
        }
        // the gable ends, in the wall's own plane and material
        if (roof == "gabled") {
            val (s0, s1) = onRidgeF(poly)
            for (i in 0 until n) {
                val a = poly[i]; val bb = poly[(i + 1) % n]
                val pa = V3(a[0], wallTop, a[1]); val pb = V3(bb[0], wallTop, bb[1])
                val qa = pmin(s1, pmax(s0, (a[0] - cx) * ux + (a[1] - cz) * uz)); val qb = pmin(s1, pmax(s0, (bb[0] - cx) * ux + (bb[1] - cz) * uz))
                if (Math.abs(qa - qb) > 0.3) continue
                val apex = V3(cx + ux * (qa + qb) / 2, h, cz + uz * (qa + qb) / 2)
                var nr = vnorm(V3(bb[1] - a[1], 0.0, -(bb[0] - a[0])))
                if (pip((a[0] + bb[0]) / 2 + nr[0] * 0.05, (a[1] + bb[1]) / 2 + nr[2] * 0.05, poly)) nr = neg(nr)
                val tri = listOf(pa, pb, apex); val c = cen(tri)
                if (vdot(nr, vsub(cam.C, c)) <= 0) continue
                if (truthy(TD["hoist"]) && dd < 260) {
                    val hb0 = cam.proj(V3(apex[0], h - rh * 0.25, apex[2])); val hb1 = cam.proj(V3(apex[0] + nr[0] * 0.7, h - rh * 0.25, apex[2] + nr[2] * 0.7))
                    if (hb0 != null && hb1 != null) rows.add(pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"#2A221C\" stroke-width=\"%.2f\" stroke-linecap=\"round\"/>", hb0[0], hb0[1], hb1[0], hb1[1], pmax(0.5, 0.12 * cam.focal / dd)))
                }
                gab.add(face(cam, tri, tohex(light(th, base, nr, !night && vdot(nr, L) > 0 && sh.lit(V3(c[0] + nr[0] * .2, c[1], c[2] + nr[2] * .2), skip = b))), pf(" stroke=\"%s\" stroke-width=\".25\"", edge)))
            }
        }
        // the slopes, from the overhanging eaves up to the ridge
        val (s0, s1) = onRidgeF(ring); val ye = wallTop - E * slope
        val inset = if (roof == "hipped") pmin(rh + E, (s1 - s0) / 2) else 0.0
        val r0 = V3(cx + ux * (s0 + inset), h, cz + uz * (s0 + inset)); val r1 = V3(cx + ux * (s1 - inset), h, cz + uz * (s1 - inset))
        val tiles = b["rmat_draw"] in TILED && !night
        val rstep = if (b["rmat_draw"] == "roof_tiles") 0.33 else 0.26
        val ppm = cam.focal / pmax(dd, 1.0)
        val m = ring.size
        for (i in 0 until m) {
            val a = ring[i]; val bb = ring[(i + 1) % m]
            val pa = V3(a[0], ye, a[1]); val pb = V3(bb[0], ye, bb[1])
            fun onRidge(p: V3): V3 { val sv = pmin(s1 - inset, pmax(s0 + inset, (p[0] - cx) * ux + (p[2] - cz) * uz)); return V3(cx + ux * sv, h, cz + uz * sv) }
            val qa = onRidge(pa); val qb = onRidge(pb)
            val q: List<V3>
            if (dist3(qa, qb) < 0.3) {
                if (roof != "hipped") continue
                q = listOf(pa, pb, qa)
            } else q = listOf(pa, pb, qb, qa)
            var nr = vnorm(vcross(vsub(q[1], q[0]), vsub(q[q.size - 1], q[0])))
            if (nr[1] < 0) nr = neg(nr)
            val c = cen(q)
            if (vdot(nr, vsub(cam.C, c)) <= 0) continue
            val lit = !night && vdot(nr, L) > 0 && sh.lit(c, skip = b)
            slopes.add(face(cam, q, tohex(light(th, rbase, nr, lit)), pf(" stroke=\"%s\" stroke-width=\".25\"", edge)))
            if (tiles && ppm * rstep > 2.0) { // the courses of tiles or slates, parallel to the eaves
                val sl = dist3(pa, qa); val k = (sl / rstep).toInt(); val seg = ArrayList<String>()
                val qe = if (q.size == 4) qb else qa
                for (j in 1 until k) {
                    val t = j.toDouble() / k
                    val A = V3(pa[0] + (qa[0] - pa[0]) * t, pa[1] + (qa[1] - pa[1]) * t, pa[2] + (qa[2] - pa[2]) * t)
                    val B = V3(pb[0] + (qe[0] - pb[0]) * t, pb[1] + (qe[1] - pb[1]) * t, pb[2] + (qe[2] - pb[2]) * t)
                    val p0 = cam.proj(A); val p1 = cam.proj(B)
                    if (p0 != null && p1 != null) seg.add(pf("M%.1f %.1fL%.1f %.1f", p0[0], p0[1], p1[0], p1[1]))
                }
                if (seg.isNotEmpty()) rows.add(pf("<path d=\"%s\" stroke=\"%s\" stroke-width=\"%.2f\" opacity=\".3\" fill=\"none\"/>", seg.joinToString(""), tohex(mulc(hx(tohex(rbase)), 0.55)), pmax(0.35, ppm * 0.03)))
            }
        }
        val out = ArrayList<String>(gab + slopes + rows)
        if (!night && dist3(r0, r1) > 0.3) { // the ridge catches the light, as on a model
            val p0 = cam.proj(r0); val p1 = cam.proj(r1)
            if (p0 != null && p1 != null) out.add(pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"%s\" stroke-width=\"%.2f\" opacity=\".55\" stroke-linecap=\"round\"/>", p0[0], p0[1], p1[0], p1[1], hzc(th, "#F4EEE2"), pmax(0.45, ppm * 0.08)))
        }
        return out
    }

    // render/amsterdam.py:1495 trad_chimneys
    /** Chimney stacks as the tradition builds them: on the party walls with pots (London), one with a tile cap (Tuscany), one plain (Norway). */
    @Suppress("UNCHECKED_CAST")
    fun tradChimneys(
        cam: Camera, th: Dict, sh: Shade, b: Dict, TD: Map<String, Any?>, poly: List<V2>, wallTop: Double, h: Double, rh: Double, roof: String?, ridgeU: V2?, night: Boolean,
    ): List<String> {
        val C = TD["chimney"] as Map<String, Any?>; val rnd = PyRandom((b["id"] as Number).toLong() * 3 + 1); val out = ArrayList<String>()
        val col = hx((C["colour"] ?: "#9A8A78") as String); val ce = centroid(poly); val cx = ce.x; val cz = ce.y
        val kind = C["kind"]
        var ux: Double; var uz: Double
        if (ridgeU != null) { ux = ridgeU.x; uz = ridgeU.y } else {
            val n = poly.size; val i0 = longestFirst(poly); val a = poly[i0]; val c = poly[(i0 + 1) % n]
            val Lg = dist(a, c).let { if (it == 0.0) 1.0 else it }; ux = (c[0] - a[0]) / Lg; uz = (c[1] - a[1]) / Lg
            if (kind == "party") { val t = ux; ux = -uz; uz = t } // a flat-roofed terrace: stacks on the side walls
        }
        val proj = poly.map { (it[0] - cx) * ux + (it[1] - cz) * uz }; val s0 = City.pyMinOf(proj); val s1 = City.pyMaxOf(proj)
        val top = if (roof in RIDGED) h else wallTop
        val pots = hx((C["pots"] ?: "#B06A45") as String)
        val party = b["party"] as List<Pair<V2, V2>>?
        if (kind == "party" && !party.isNullOrEmpty()) {
            // on the walls shared with the neighbours, as a terrace and a Paris block have them: a stack astride each, its pots in a row
            val walls = party.toMutableList(); walls.pySortBy { -dist(it.first, it.second) }
            for ((a, c) in walls.take(2)) {
                val Lw = dist(a, c); val u = V2((c[0] - a[0]) / Lw, (c[1] - a[1]) / Lw); val m = V2((a[0] + c[0]) / 2, (a[1] + c[1]) / 2)
                val hu = pmin(1.2, Lw * 0.22)
                out.addAll(box3(cam, th, sh, b, m, u, hu, 0.32, top - 0.6, top + 1.2, col, night))
                for (k in doubleArrayOf(-0.6, 0.0, 0.6)) {
                    val pc = V2(m[0] + u[0] * hu * k, m[1] + u[1] * hu * k)
                    out.addAll(box3(cam, th, sh, b, pc, u, 0.09, 0.09, top + 1.2, top + 1.6, pots, night))
                }
            }
            return out
        }
        val spots: List<Double>; val hu: Double; val hv: Double; val rise: Double
        if (kind == "party") {
            spots = listOf(s0 + 0.35, s1 - 0.35); hu = 0.35; hv = 0.95; rise = 1.3
        } else if (kind == "capped") {
            spots = listOf(s0 + (s1 - s0) * rnd.uniform(0.25, 0.75)); hu = 0.32; hv = 0.32; rise = 1.0
        } else {
            spots = listOf(s0 + (s1 - s0) * rnd.uniform(0.3, 0.7)); hu = 0.28; hv = 0.28; rise = 0.9
        }
        for (sp in spots) {
            val c0 = V2(cx + ux * sp, cz + uz * sp)
            out.addAll(box3(cam, th, sh, b, c0, V2(ux, uz), hu, hv, top - 0.6, top + rise, col, night))
            if (kind == "party") { // the pots in a row along the stack
                for (k in doubleArrayOf(-0.55, 0.0, 0.55)) {
                    val pc = V2(c0[0] - uz * k, c0[1] + ux * k)
                    out.addAll(box3(cam, th, sh, b, pc, V2(ux, uz), 0.09, 0.09, top + rise, top + rise + 0.42, pots, night))
                }
            } else if (kind == "capped") {
                out.addAll(box3(cam, th, sh, b, c0, V2(ux, uz), hu + 0.12, hv + 0.12, top + rise, top + rise + 0.14, hx((C["cap"] ?: "#9E5A3C") as String), night))
            }
        }
        return out
    }

    // render/amsterdam.py:1533 inset_ring
    /** The ring moved [dl] metres inward along every side (mitres capped), or null where the shape is too thin or too notched for it. */
    fun insetRing(poly: List<V2>, dl: Double): List<V2>? {
        val out = mitre(poly, dl, true)
        val so = signedArea(out); val sp = signedArea(poly)
        if (so * sp <= 0 || Math.abs(so) < 0.2 * Math.abs(sp)) return null
        if (turned(poly, out)) return null // a side turned over: the inset crossed itself
        if (!out.all { pip(it.x, it.y, poly) }) return null
        return out
    }

    // render/amsterdam.py:1560 mansard
    /**
     * roof:shape=mansard: the steep lower slope (the brisis) set back about a third of the roof height from the cornice, dormers in it over the
     * window bays below, and the near-flat top (the terrasson). Where the footprint is too thin to set back, the slope stands upright.
     */
    fun mansard(cam: Camera, th: Dict, sh: Shade, b: Dict, poly: List<V2>, wallTop: Double, h: Double, rh: Double, rbase: V3, edge: String, dd: Double, lens: String?, night: Boolean): List<String> {
        val L = v3(th["_L"]); val out = ArrayList<String>(); val n = poly.size
        var ins: List<V2>? = null
        for (dl in doubleArrayOf(pmin(1.4, rh * 0.36), pmin(0.8, rh * 0.22))) {
            ins = insetRing(poly, dl)
            if (ins != null) break
        }
        if (ins == null) ins = poly.toList()
        val paris = WIN_STYLE == "paris"
        val dorm = truthy(th["wx"]) && (lens == null || lens == "year") && dd < d(th["_win_d"] ?: 390) * 1.1
        val fronts = ArrayList<String>(); val panes = ArrayList<String>(); val caps = ArrayList<String>(); var fcol: String? = null
        for (i in 0 until n) {
            val a = poly[i]; val bb = poly[(i + 1) % n]; val ia = ins[i]; val ib = ins[(i + 1) % n]
            val elen = dist(a, bb)
            if (elen < 0.3) continue
            val q = listOf(V3(a[0], wallTop, a[1]), V3(bb[0], wallTop, bb[1]), V3(ib[0], h, ib[1]), V3(ia[0], h, ia[1]))
            var nr = vnorm(vcross(vsub(q[1], q[0]), vsub(q[3], q[0])))
            if (nr[1] < 0) nr = neg(nr)
            if (Math.abs(nr[1]) < 1e-3) { // upright: face it outward
                nr = vnorm(V3(bb[1] - a[1], 0.0, -(bb[0] - a[0])))
                if (pip((a[0] + bb[0]) / 2 + nr[0] * 0.05, (a[1] + bb[1]) / 2 + nr[2] * 0.05, poly)) nr = neg(nr)
            }
            val c = cen(q)
            if (vdot(nr, vsub(cam.C, c)) <= 0) continue
            val lit = !night && vdot(nr, L) > 0 && sh.lit(V3(c[0] + nr[0] * .25, c[1] + nr[1] * .25, c[2] + nr[2] * .25), skip = b)
            val col = tohex(light(th, rbase, nr, lit))
            out.add(face(cam, q, col, pf(" stroke=\"%s\" stroke-width=\".25\"", edge)))
            if (b["rmat_draw"] == "metal" && !night && cam.focal * 0.55 / pmax(dd, 1.0) > 2.2 && elen > 1.2) {
                val ns = (elen / 0.55).toInt(); val sg = ArrayList<String>() // the standing seams of zinc, from the eaves to the top
                for (k in 1 until ns) {
                    val f = k.toDouble() / ns
                    val p0 = cam.proj(V3(a[0] + (bb[0] - a[0]) * f, wallTop, a[1] + (bb[1] - a[1]) * f)); val p1 = cam.proj(V3(ia[0] + (ib[0] - ia[0]) * f, h, ia[1] + (ib[1] - ia[1]) * f))
                    if (p0 != null && p1 != null) sg.add(pf("M%.1f %.1fL%.1f %.1f", p0[0], p0[1], p1[0], p1[1]))
                }
                if (sg.isNotEmpty()) out.add(pf("<path d=\"%s\" stroke=\"%s\" stroke-width=\".4\" opacity=\".35\" fill=\"none\"/>", sg.joinToString(""), tohex(mulc(hx(col), 0.7))))
            }
            if (!dorm || elen < 2.4 || rh < 2.4) continue
            // dormers: one over each window bay, their fronts upright in the slope a third of the way up
            val wn = if (Math.abs(nr[1]) < 0.999) vnorm(V3(nr[0], 0.0, nr[2])) else V3(0.0, 0.0, 1.0)
            val nd = Math.max(1, (elen / (if (paris) 2.9 else 2.4)).toInt())
            val f = 0.3; val yb = wallTop + f * rh; val yt = wallTop + pmin(rh - 0.3, f * rh + 1.75)
            val ox = (ia[0] - a[0]) * f; val oz = (ia[1] - a[1]) * f; val ox2 = (ib[0] - bb[0]) * f; val oz2 = (ib[1] - bb[1]) * f
            val A0 = V2(a[0] + ox, a[1] + oz); val B0 = V2(bb[0] + ox2, bb[1] + oz2)
            val ex = B0[0] - A0[0]; val ez = B0[1] - A0[1]
            val wlit = !night && vdot(wn, L) > 0 && sh.lit(V3(c[0] + wn[0] * .4, yt, c[2] + wn[2] * .4), skip = b)
            fcol = tohex(light(th, hx("#DDD8CE"), wn, wlit))
            for (k in 0 until nd) {
                val um = (k + 0.5) / nd; val hw = pmin(0.6, 0.32 * elen / nd) / pmax(elen, 0.1)
                val u0 = um - hw; val u1 = um + hw
                fun p3(u: Double, y: Double, o: Double = 0.02) = V3(A0[0] + ex * u + wn[0] * o, y, A0[1] + ez * u + wn[2] * o)
                val sp = cam.poly(listOf(p3(u0, yb), p3(u1, yb), p3(u1, yt), p3(u0, yt)), margin = 300.0)
                if (sp == null || area(sp) < 0.12) continue
                fronts.add(P(sp))
                val hp = cam.poly(listOf(p3(u0 - hw * 0.12, yt), p3(u1 + hw * 0.12, yt), p3(um, yt + 0.45)), margin = 300.0)
                if (hp != null) caps.add(P(hp))
                val gp = cam.poly(listOf(p3(u0 + hw * 0.4, yb + 0.12, 0.04), p3(u1 - hw * 0.4, yb + 0.12, 0.04), p3(u1 - hw * 0.4, yt - 0.22, 0.04), p3(u0 + hw * 0.4, yt - 0.22, 0.04)), margin = 300.0)
                if (gp != null && area(gp) > 0.08) panes.add(P(gp))
            }
        }
        if (cam.C[1] > h) {
            val ce = centroid(ins)
            val litR = !night && sh.lit(V3(ce.x, h + 0.05, ce.y), skip = b)
            out.add(face(cam, ins.map { V3(it.x, h, it.y) }, tohex(light(th, rbase, V3(0.0, 1.0, 0.0), litR)), pf(" stroke=\"%s\" stroke-width=\".45\"", edge)))
        }
        if (fronts.isNotEmpty()) {
            out.add(pf("<path d=\"%s\" fill=\"%s\"/>", fronts.joinToString(""), fcol))
            if (caps.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"%s\"/>", caps.joinToString(""), tohex(mulc(hx(fcol!!), 0.8))))
            if (panes.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"%s\"/>", panes.joinToString(""), hzc(th, if (!night) "#3A4350" else "#10141C")))
        }
        return out
    }

    // ------------------------------------------------------------------ trees, lamps, railings
    // render/amsterdam.py:1624 draw_tree3d
    /** A generic tree from the tags height and diameter_crown (guessed when not mapped); up close in the real weather, the tree of its genus. */
    fun drawTree3d(cam: Camera, th: Dict, sh: Shade, n: Dict, dd: Double, lens: String?): List<String> {
        val night = th["night"] == true; val L = v3(th["_L"])
        if (truthy(th["wx"]) && lens == null) {
            th["_hz"] = hazeAt(th, dd)
            try {
                return Closeup.treeClose(cam, th, { p -> sh.lit(p) }, n, dd, lightFn, null)
            } finally {
                th["_hz"] = 0.0
            }
        }
        val h = d(n["h"]); val r = d(n["crown"]) / 2; val nx = d(n["x"]); val nz = d(n["z"])
        val p0 = cam.proj(V3(nx, 0.0, nz)); val p1 = cam.proj(V3(nx, h * 0.55, nz))
        if (p0 == null || p1 == null) return emptyList()
        val out = arrayListOf(pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"%s\" stroke-width=\"%.2f\" stroke-linecap=\"round\"/>", p0[0], p0[1], p1[0], p1[1], if (!night) "#4A3A2E" else "#0C100E", pmax(0.6, 0.3 * cam.focal / dd)))
        val truthyT = lens == "truth" && truthy(n["guessed"])
        var base = if (!truthyT) hx(if (truthy(th["wx"])) "#4F6E37" else "#6E9450") else hx("#FFFFFF")
        if (lens != null && lens.startsWith("tag:")) base = hx(if (lens.substring(4) in TREE_KEYS) LENS_COL.getValue(tagState(n, lens.substring(4), "tree")) else "#DCE3D6")
        val rnd = PyRandom(n["id"])
        val blobs = ArrayList<DoubleArray>()
        val nb = if (dd < 160 && truthy(th["wx"])) 26 else (if (dd < 400) 9 else 5)
        for (k in 0 until nb) {
            val ang = rnd.uniform(0.0, 2 * Math.PI); val rad = r * PyMath.sqrt(rnd.random()) * (if (nb > 9) 0.8 else 0.7)
            val bx = nx + rad * PyMath.cos(ang); val bz = nz + rad * PyMath.sin(ang)
            val by = h * 0.68 + rnd.uniform(-0.3, 0.3) * r
            val c = cam.cam(V3(bx, by, bz))
            if (c[2] > 1) blobs.add(doubleArrayOf(c[2], bx, by, bz, r * (if (nb > 9) rnd.uniform(0.26, 0.4) else rnd.uniform(0.38, 0.55))))
        }
        blobs.pySortBy { -it[0] }
        val lit = !night && sh.lit(V3(nx + L[0] * r, h * 0.75 + L[1] * r, nz + L[2] * r))
        val dark = light(th, mulc(base, 0.6), V3(0.0, 1.0, 0.0), false); val mid = light(th, base, vnorm(V3(L[0], 0.7, L[2])), lit)
        val under = ArrayList<String>(); val top = ArrayList<String>()
        for (bl in blobs) {
            val bd = bl[0]; val bx = bl[1]; val by = bl[2]; val bz = bl[3]; val br = bl[4]
            val p = cam.proj(V3(bx, by, bz))!!; val rr = br * cam.focal / bd
            under.add(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\"/>", p[0], p[1], rr))
            val sx = if (!night) cam.proj(V3(bx + L[0] * br, by + L[1] * br, bz + L[2] * br)) else null
            if (sx != null) top.add(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\"/>", p[0] + (sx[0] - p[0]) * .3, p[1] + (sx[1] - p[1]) * .3, rr * 0.78))
        }
        val lf = if (truthy(th["wx"]) && dd < 700) (if (dd < 220) " filter=\"url(#foliage)\"" else " filter=\"url(#leaf)\"") else "" // leaves: ragged edges, light through gaps
        out.add(pf("<g fill=\"%s\"%s%s>%s</g>", tohex(dark), if (truthyT) " stroke=\"#B9B2A6\" stroke-width=\".4\"" else "", lf, under.joinToString("")))
        if (top.isNotEmpty()) out.add(pf("<g fill=\"%s\"%s>%s</g>", tohex(mid), lf, top.joinToString("")))
        return out
    }

    // render/amsterdam.py:1661 draw_tree_symbol
    /** Position mapped, size not: a flat crown symbol at a fixed 3 m radius. */
    fun drawTreeSymbol(cam: Camera, th: Map<String, Any?>, n: Map<String, Any?>): String {
        val pts = (0 until 16).map { k -> val a = k * Math.PI / 8; V3(d(n["x"]) + 3 * PyMath.cos(a), 0.3, d(n["z"]) + 3 * PyMath.sin(a)) }
        val sp = cam.poly(pts) ?: return ""
        val night = th["night"] == true
        return pf("<path d=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\".8\" opacity=\".92\"/>", P(sp), if (!night) "#7FA864" else "#1B2A22", if (!night) "#4E7A44" else "#10181A")
    }

    // render/amsterdam.py:1669 draw_lamp
    /** highway=street_lamp: a generic mast and lantern (no tag gives the model here). */
    fun drawLamp(cam: Camera, th: Map<String, Any?>, n: Map<String, Any?>, dd: Double): List<String> {
        val nx = d(n["x"]); val nz = d(n["z"])
        val p0 = cam.proj(V3(nx, 0.0, nz)); val p1 = cam.proj(V3(nx, 4.2, nz))
        if (p0 == null || p1 == null) return emptyList()
        val night = th["night"] == true; val s = cam.focal / dd
        val out = ArrayList<String>()
        if (night && truthy(th["wet"])) { // wet stones mirror the lamp as a long warm streak
            out.add(pf("<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.2f\" ry=\"%.1f\" fill=\"url(#streak)\" opacity=\".75\"/>", p0[0], p0[1] + 2.6 * s, pmax(0.5, 0.5 * s), pmax(2.5, 3.4 * s)))
        }
        out.add(pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"%s\" stroke-width=\"%.2f\" stroke-linecap=\"round\"/>", p0[0], p0[1], p1[0], p1[1], if (!night) "#2A2E33" else "#161A22", pmax(0.5, 0.13 * s)))
        val hw = pmax(0.6, 0.2 * s); val hh = pmax(0.9, 0.5 * s)
        val lamp = if (night) "#FFE7B8" else "#3B4148"
        out.add(pf("<path d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fZ\" fill=\"%s\"/>", p1[0] - hw, p1[1], p1[0] + hw, p1[1], p1[0] + hw * 0.7, p1[1] + hh, p1[0] - hw * 0.7, p1[1] + hh, lamp))
        out.add(pf("<path d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1fZ\" fill=\"%s\"/>", p1[0] - hw * 1.2, p1[1], p1[0] + hw * 1.2, p1[1], p1[0], p1[1] - hh * 0.6, if (!night) "#22262B" else "#2A2620"))
        if (!night && s > 1.2) out.add(pf("<path d=\"M%.1f %.1fv%.1f\" stroke=\"#E6ECF2\" stroke-width=\"%.2f\" opacity=\".7\"/>", p1[0] - hw * 0.4, p1[1] + hh * 0.2, hh * 0.6, pmax(0.3, 0.05 * s)))
        if (night) out.add(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"url(#halo)\"/>", p1[0], p1[1] + hh * 0.5, pmax(4.0, 2.6 * s)))
        return out
    }

    // render/amsterdam.py:1686 railing
    /** bridge=yes: a generic iron railing, posts and two rails, in short pieces so it sorts with everything else. */
    fun railing(cam: Camera, th: Map<String, Any?>, line: List<V2>, y0: Double, owner: Any? = null): List<Item> {
        val out = ArrayList<Item>(); val night = th["night"] == true; val col = if (!night) "#26292C" else "#0A0B0E"
        for (i in 0 until line.size - 1) {
            val a = line[i]; val b = line[i + 1]; val Lg = dist(a, b); var k = 0.0
            while (k < Lg) {
                val k2 = pmin(Lg, k + 2.0)
                val pa = V2(a[0] + (b[0] - a[0]) * k / Lg, a[1] + (b[1] - a[1]) * k / Lg); val pb = V2(a[0] + (b[0] - a[0]) * k2 / Lg, a[1] + (b[1] - a[1]) * k2 / Lg)
                val sx = (pa[0] + pb[0]) / 2; val sz = (pa[1] + pb[1]) / 2
                val c = cam.cam(V3(sx, y0 + 0.5, sz))
                if (c[2] < 0.8 || c[2] > 220) { k = k2; continue }
                val s = cam.focal / c[2]; val segs = ArrayList<String>()
                for (yy in doubleArrayOf(y0 + 1.0, y0 + 0.12)) {
                    val p0 = cam.proj(V3(pa[0], yy, pa[1])); val p1 = cam.proj(V3(pb[0], yy, pb[1]))
                    if (p0 != null && p1 != null) segs.add(pf("M%.1f %.1fL%.1f %.1f", p0[0], p0[1], p1[0], p1[1]))
                }
                val m = Math.max(1, ((k2 - k) / 0.32).toInt())
                for (j in 0..m) {
                    val f = j.toDouble() / m; val px = pa[0] + (pb[0] - pa[0]) * f; val pz = pa[1] + (pb[1] - pa[1]) * f
                    val p0 = cam.proj(V3(px, y0 + 0.12, pz)); val p1 = cam.proj(V3(px, y0 + 1.0, pz))
                    if (p0 != null && p1 != null) segs.add(pf("M%.1f %.1fL%.1f %.1f", p0[0], p0[1], p1[0], p1[1]))
                }
                if (segs.isNotEmpty()) {
                    val item = Item(c[2], "svg", pf("<path d=\"%s\" stroke=\"%s\" stroke-width=\"%.2f\" stroke-linecap=\"round\" fill=\"none\"/>", segs.joinToString(""), col, pmax(0.4, 0.045 * s)))
                    out.add(noted(item, cam, "railing", owner, Double.NaN, Double.NaN, sx, y0 + 0.5, sz)) // no radius cut: drawn with its road
                }
                k = k2
            }
        }
        return out
    }

    // ------------------------------------------------------------------ doors and shopfronts
    @Suppress("UNCHECKED_CAST")
    private fun roadGrid(S: Dict): GridMap<MutableList<Pair<V2, V2>>> {
        val got = S["_rg"]
        if (got != null) return got as GridMap<MutableList<Pair<V2, V2>>>
        // render/amsterdam.py:1709 road_grid
        val g = GridMap<MutableList<Pair<V2, V2>>>()
        for (r in dicts(S["roads"])) {
            val pts = ptsOf(r)
            for (i in 0 until pts.size - 1) {
                val a = pts[i]; val b = pts[i + 1]
                for (gx in PyMath.floorDiv(pmin(a[0], b[0]), 20.0).toLong() - 1 until PyMath.floorDiv(pmax(a[0], b[0]), 20.0).toLong() + 2) {
                    for (gz in PyMath.floorDiv(pmin(a[1], b[1]), 20.0).toLong() - 1 until PyMath.floorDiv(pmax(a[1], b[1]), 20.0).toLong() + 2) {
                        g.getOrPut(GridMap.key(gx, gz)) { ArrayList() }.add(a to b)
                    }
                }
            }
        }
        S["_rg"] = g
        return g
    }

    // render/amsterdam.py:1719 road_dist
    /** The distance from (x, z) to the nearest mapped road within its 20 m cell (99 when none). */
    fun roadDist(S: Dict, x: Double, z: Double): Double {
        var best = 99.0
        val gx = PyMath.floorDiv(x, 20.0).toLong(); val gz = PyMath.floorDiv(z, 20.0).toLong()
        for ((a, b) in roadGrid(S)[GridMap.key(gx, gz)] ?: emptyList()) {
            val ux = b[0] - a[0]; val uz = b[1] - a[1]; val L2 = (ux * ux + uz * uz).let { if (it == 0.0) 1.0 else it }
            val t = pmax(0.0, pmin(1.0, ((x - a[0]) * ux + (z - a[1]) * uz) / L2))
            best = pmin(best, hypot(a[0] + ux * t - x, a[1] + uz * t - z))
        }
        return best
    }

    // render/amsterdam.py:1727 street_wall
    /**
     * The wall of building [b] that a door or shop at (x, z) opens onto: near the point, and with a mapped street just outside it.
     * Returns (qx, qz, ux, uz): the point on the wall and the wall's direction, or null.
     */
    fun streetWall(S: Dict, b: Map<String, Any?>, x: Double, z: Double): DoubleArray? {
        val poly = polyOf(b); var best: DoubleArray? = null; val ce = centroid(poly); val cx = ce.x; val cz = ce.y
        for (i in poly.indices) {
            val a = poly[i]; val c = poly[(i + 1) % poly.size]
            val ux = c[0] - a[0]; val uz = c[1] - a[1]; val L2 = ux * ux + uz * uz
            if (L2 < 1.5) continue
            val L = PyMath.sqrt(L2); var nx = uz / L; var nz = -ux / L
            val mx = (a[0] + c[0]) / 2; val mz = (a[1] + c[1]) / 2
            if (nx * (mx - cx) + nz * (mz - cz) < 0) { nx = -nx; nz = -nz }
            val tt = pmax(0.15, pmin(0.85, ((x - a[0]) * ux + (z - a[1]) * uz) / L2))
            val qx = a[0] + ux * tt; val qz = a[1] + uz * tt
            val outD = roadDist(S, qx + nx * 3.5, qz + nz * 3.5)
            val score = hypot(qx - x, qz - z) + 1.5 * pmin(outD, 25.0) + (if (L < 2.5) 40 else 0)
            if (best == null || score < best[0]) best = doubleArrayOf(score, qx, qz, ux / L, uz / L)
        }
        return best?.copyOfRange(1, 5)
    }

    private class Door(
        val nums: MutableSet<Long>, var depth: Double = 0.0, var sv: String = "", var p: V2? = null, var d0: Double = 0.0, var drawn: Boolean = false,
        var note: ItemNote? = null,
    )

    // render/amsterdam.py:1745 facade_details
    /**
     * Close to the camera: a door for each mapped address, with its real number, and a shopfront for each mapped shop or café,
     * lit when its opening_hours say open. Door and window shapes are generic; position and number come from the tags.
     */
    fun facadeDetails(S: Dict, cam: Camera, th: Dict, far: (Double, Double) -> Boolean): List<Item> {
        val night = th["night"] == true; val out = ArrayList<Item>()
        val blds = dicts(S["buildings"]).filter { !truthy(it["hidden"]) && truthy(it["h"]) && tagsOf(it)["building"] != "houseboat" }
        val bboxes = blds.map { b -> val p = polyOf(b); doubleArrayOf(City.pyMinOf(p.map { it.x }), City.pyMaxOf(p.map { it.x }), City.pyMinOf(p.map { it.y }), City.pyMaxOf(p.map { it.y })) }
        val doors = LinkedHashMap<List<Long>, Door>() // one door per spot on a wall; several addresses (49, 49A, 49-1) share it
        for (n in dicts(S["nodes"])) {
            val t = tagsOf(n); val num = t["addr:housenumber"] as String?; val st = n["style"] as PoiStyle.Style?
            val biz = st != null && st.cat != "service"
            val nx = d(n["x"]); val nz = d(n["z"])
            if (!(truthy(num) || biz) || far(nx, nz)) continue
            WorldTape.owner(n) // a shopfront is its shop's, a door its address's
            val d0 = cam.depth(V3(nx, 1.0, nz))
            if (d0 < 2 || d0 > (if (!biz) 75.0 else 230.0)) continue
            var b: Dict? = null
            for ((k, cand) in blds.withIndex()) {
                val bb = bboxes[k]
                if (bb[0] <= nx && nx <= bb[1] && bb[2] <= nz && nz <= bb[3] && pip(nx, nz, polyOf(cand))) { b = cand; break }
            }
            if (b == null) continue
            val best = streetWall(S, b, nx, nz) ?: continue
            val qx = best[0]; val qz = best[1]; val ux = best[2]; val uz = best[3]
            val nrm = vnorm(V3(uz, 0.0, -ux))
            if (vdot(nrm, vsub(cam.C, V3(qx, 1.2, qz))) <= 0) continue // that wall faces away
            val ce = centroid(polyOf(b)); val sy = d(b["h"]) / 2; val cb = cam.cam(V3(ce.x, sy, ce.y))
            val depth = cb[2] - 0.03
            val o = 0.06
            fun quad(w: Double, y0: Double, y1: Double, off: Double = o) = listOf(
                V3(qx - ux * w / 2 + nrm[0] * off, y0, qz - uz * w / 2 + nrm[2] * off), V3(qx + ux * w / 2 + nrm[0] * off, y0, qz + uz * w / 2 + nrm[2] * off),
                V3(qx + ux * w / 2 + nrm[0] * off, y1, qz + uz * w / 2 + nrm[2] * off), V3(qx - ux * w / 2 + nrm[0] * off, y1, qz - uz * w / 2 + nrm[2] * off),
            )
            if (biz) {
                val oh = t["opening_hours"] as String?; val isOpen = !oh.isNullOrEmpty() && openNow(oh, th)
                val items = shopfront(cam, th, S, t, st!!, qx, qz, ux, uz, nrm, isOpen, depth, d0)
                if (cam.tape != null) {
                    for (it in items) { // the board at the building's depth; the blade and the tables at their own points
                        val sn = it.note ?: ItemNote("shopfront", n, nx, nz, nx, nz, ce.x, sy, ce.y, -0.03)
                        it.note = ItemNote(sn.kind, n, nx, nz, nx, nz, sn.sx, sn.sy, sn.sz, sn.off)
                    }
                }
                if (items.isNotEmpty()) {
                    out.addAll(items)
                    th["_shops"] = ((th["_shops"] as Int?) ?: 0) + 1; th["_open"] = ((th["_open"] as Int?) ?: 0) + (if (isOpen) 1 else 0)
                    th["_brands"] = ((th["_brands"] as Int?) ?: 0) + (if (truthy(st.brand)) 1 else 0)
                }
                continue
            }
            val key = listOf((b["id"] as Number).toLong(), PyMath.round(qx), PyMath.round(qz))
            val base = leadingInt(num ?: "")
            val have = doors[key]
            if (have != null) {
                if (base != null) have.nums.add(base)
                continue
            }
            val door = Door(if (base != null) linkedSetOf(base) else linkedSetOf())
            if (cam.tape != null) door.note = ItemNote("door", n, nx, nz, nx, nz, ce.x, sy, ce.y, -0.03)
            doors[key] = door
            var sv = face(cam, quad(1.15, 0.0, 2.45), listOf("#1F3A30", "#23262C", "#3B2220", "#1E2C3E", "#2E3B2A")[Math.floorMod((n["id"] as Number).toLong(), 5L).toInt()], " stroke=\"#E8E2D6\" stroke-width=\".5\"")
            sv += face(cam, quad(1.5, 0.0, 0.32, 0.6), if (!night) "#9C9488" else "#22232A") // the stoop
            val p = cam.proj(V3(qx + nrm[0] * o, 2.75, qz + nrm[2] * o))
            door.depth = depth; door.sv = sv; door.p = p; door.d0 = d0; door.drawn = true
        }
        for (door in doors.values) {
            if (!door.drawn) continue
            var sv = door.sv; val p = door.p; val d0 = door.d0; val nums = door.nums
            val num = if (nums.isNotEmpty()) (if (nums.size == 1) pf("%d", nums.min()) else pf("%d–%d", nums.min(), nums.max())) else ""
            if (p != null && d0 < 45 && num.isNotEmpty()) {
                val fs = pmax(3.0, 0.32 * cam.focal / d0)
                sv += pf("<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%.1f\" fill=\"%s\"/><text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" font-family=\"Georgia, serif\" font-size=\"%.1f\" fill=\"%s\">%s</text>",
                    p[0] - fs * (0.4 + 0.3 * plen(num)), p[1] - fs * 0.85, fs * (0.8 + 0.6 * plen(num)), fs * 1.15, fs * 0.15, if (!night) "#F2EEE4" else "#3A3A40", p[0], p[1] + fs * 0.08, fs,
                    if (!night) "#1E2A44" else "#B9C0D0", num)
            }
            if (sv.isNotEmpty()) { out.add(Item(door.depth, "svg", sv).also { it.note = door.note }); th["_doors"] = ((th["_doors"] as Int?) ?: 0) + 1 }
        }
        WorldTape.owner(null)
        return out
    }

    /** Python's `s[:k]` by code points. */
    private fun take(s: String, k: Int): String = if (plen(s) <= k) s else s.substring(0, s.offsetByCodePoints(0, k))

    // render/amsterdam.py:1800 shopfront
    /**
     * A mapped shop, café or hotel, seen from the street: glazing, an awning in its category colour, a sign with its
     * brand (brand=*) or its kind, and a terrace where outdoor_seating says so. Shapes are generic; what and where come from tags.
     */
    fun shopfront(
        cam: Camera, th: Dict, S: Dict, t: Map<String, Any?>, st: PoiStyle.Style, qx: Double, qz: Double, ux: Double, uz: Double, nrm: V3, isOpen: Boolean,
        depth: Double, d0: Double,
    ): List<Item> {
        val night = th["night"] == true; val out = ArrayList<Item>(); val s = cam.focal / pmax(d0, 1.0)
        fun p3(u: Double, y: Double, off: Double) = V3(qx + ux * u + nrm[0] * off, y, qz + uz * u + nrm[2] * off)
        fun quad(u0: Double, u1: Double, y0: Double, y1: Double, off: Double) = listOf(p3(u0, y0, off), p3(u1, y0, off), p3(u1, y1, off), p3(u0, y1, off))
        val W2 = 1.9
        val glass = if (night) (if (isOpen) "#FFD08C" else "#0B0D12") else (if (isOpen) "url(#glassLit)" else "url(#glass)")
        var sv = face(cam, quad(-W2, W2, 0.0, 2.85, 0.07), if (!night) "#2A2C2E" else "#0C0D10") // frame
        sv += face(cam, quad(-W2 + 0.12, W2 - 0.12, 0.12, 2.7, 0.08), glass)
        for (m in doubleArrayOf(-W2 / 3, W2 / 3)) { // mullions and a door
            val a = cam.proj(p3(m, 0.12, 0.09)); val b = cam.proj(p3(m, 2.7, 0.09))
            if (a != null && b != null) sv += pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"#2A2C2E\" stroke-width=\"%.2f\"/>", a[0], a[1], b[0], b[1], pmax(0.5, 0.06 * s))
        }
        val col = hx(st.col); val brand = st.brand?.takeIf { it.isNotEmpty() }
        if (st.cat in AWNING_CATS || brand != null) { // an awning, out over the pavement
            val aw = tohex(mulc(col, if (night) 0.5 else 0.95))
            sv += face(cam, listOf(p3(-W2 - 0.1, 3.0, 0.1), p3(W2 + 0.1, 3.0, 0.1), p3(W2 + 0.1, 2.55, 1.15), p3(-W2 - 0.1, 2.55, 1.15)), aw, pf(" stroke=\"%s\" stroke-width=\".4\"", tohex(mulc(col, 0.6))))
            if (st.cat == "food" && brand == null) {
                val stripes = ArrayList<String>()
                for (k in 0 until 7) {
                    val u0 = -W2 - 0.1 + (2 * W2 + 0.2) * (2 * k) / 14; val u1 = u0 + (2 * W2 + 0.2) / 14
                    val sp = cam.poly(listOf(p3(u0, 3.0, 0.11), p3(u1, 3.0, 0.11), p3(u1, 2.55, 1.16), p3(u0, 2.55, 1.16)), margin = 200.0)
                    if (sp != null) stripes.add(P(sp))
                }
                if (stripes.isNotEmpty()) sv += pf("<path d=\"%s\" fill=\"%s\" opacity=\".85\"/>", stripes.joinToString(""), if (!night) "#F4EFE6" else "#3A3A40")
            }
        }
        // the sign board: the brand in its colours, or the kind in the category colour
        var bg = st.bg?.takeIf { it.isNotEmpty() } ?: tohex(mulc(col, 0.8)); val fg = st.fg?.takeIf { it.isNotEmpty() } ?: "#FFFFFF"
        if (night && !isOpen) bg = tohex(mulc(hx(bg), 0.35))
        sv += face(cam, quad(-W2 + 0.2, W2 - 0.2, 3.08, 3.62, 0.09), bg, " stroke=\"#1E1F22\" stroke-width=\".4\"")
        val c = cam.proj(p3(0.0, 3.35, 0.1))
        val label = if (brand == null) st.kind.uppercase() else brand
        val bsp = cam.poly(quad(-W2 + 0.2, W2 - 0.2, 3.08, 3.62, 0.09), margin = 60.0)
        val bw = if (bsp != null) City.pyMaxOf(bsp.map { it.x }) - City.pyMinOf(bsp.map { it.x }) else 0.0
        val bh = if (bsp != null) City.pyMaxOf(bsp.map { it.y }) - City.pyMinOf(bsp.map { it.y }) else 0.0
        if (c != null && bsp != null && bw > bh * 1.6) { // only when the board is seen face-on enough to read
            val fs = PyMath.min(14.0, s * 0.4, bh * 0.5, bw * 0.9 / (plen(label) * 0.62))
            sv += pf("<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" font-family=\"Geist, Helvetica, sans-serif\" font-weight=\"800\" font-size=\"%.1f\" fill=\"%s\" letter-spacing=\"%.2f\">%s</text>",
                c[0], c[1] + fs * 0.35, fs, if (!(night && !isOpen)) fg else tohex(mulc(hx(fg), 0.5)), fs * 0.04, label.replace("&", "&amp;"))
        }
        if (night && isOpen) sv += pf("<g opacity=\".55\">%s</g>", face(cam, quad(-2.8, 2.8, 0.0, 0.05, 2.0), "#FFC27A"))
        if (sv.isNotEmpty()) out.add(Item(depth, "svg", sv))
        // a blade sign out over the pavement: what you read when you look along a street
        val bu = W2 + 0.35
        val blade = listOf(p3(bu, 2.75, 0.18), p3(bu, 2.75, 1.05), p3(bu, 3.55, 1.05), p3(bu, 3.55, 0.18))
        val bp = p3(bu, 3.15, 0.6)
        val cb = cam.cam(bp)
        if (cb[2] > 1.5) {
            var bsv = ""
            val br0 = cam.proj(p3(bu, 3.62, 0.05)); val br1 = cam.proj(p3(bu, 3.62, 1.1))
            if (br0 != null && br1 != null) bsv += pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"#1E1F22\" stroke-width=\"%.2f\"/>", br0[0], br0[1], br1[0], br1[1], pmax(0.4, 0.05 * cam.focal / cb[2]))
            bsv += face(cam, blade, bg, " stroke=\"#1E1F22\" stroke-width=\".5\"")
            val pc = cam.proj(p3(bu, 3.15, 0.6))
            val sp = cam.poly(blade, margin = 60.0)
            if (pc != null && sp != null) {
                val bw2 = City.pyMaxOf(sp.map { it.x }) - City.pyMinOf(sp.map { it.x }); val bh2 = City.pyMaxOf(sp.map { it.y }) - City.pyMinOf(sp.map { it.y })
                var txt = brand ?: st.kind
                var fs = PyMath.min(bh2 * 0.42, bw2 * 0.88 / (plen(txt) * 0.62), 15.0)
                if (bw2 < bh2 * 0.7 || fs > 2.4) {
                    if (bw2 < bh2 * 0.7) { // seen edge-on: the name runs down the blade, short form
                        txt = if (plen(txt) <= 9) txt else take(txt, 8) + "."
                        fs = pmin(bh2 / (plen(txt) * 0.72 + 0.6), 13.0)
                        bsv += pf("<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" font-family=\"Geist, Helvetica, sans-serif\" font-weight=\"800\" font-size=\"%.1f\" fill=\"%s\" transform=\"rotate(90 %.1f %.1f)\">%s</text>",
                            pc[0], pc[1] + fs * 0.35, fs, fg, pc[0], pc[1], txt.replace("&", "&amp;"))
                    } else if (fs > 2.4) {
                        bsv += pf("<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" font-family=\"Geist, Helvetica, sans-serif\" font-weight=\"800\" font-size=\"%.1f\" fill=\"%s\">%s</text>", pc[0], pc[1] + fs * 0.35, fs, fg, txt.replace("&", "&amp;"))
                    }
                }
            }
            out.add(noted(Item(cb[2], "svg", bsv), cam, "blade", null, Double.NaN, Double.NaN, bp.x, bp.y, bp.z))
        }
        // a terrace: outdoor_seating=yes puts tables and chairs on the pavement, and a parasol by day
        if (t["outdoor_seating"] in OUTDOOR_SEATING && st.cat == "food") {
            for ((k, spot) in listOf(-1.1 to 2.0, 1.1 to 2.0, 0.0 to 3.4).withIndex()) {
                val (du, off) = spot
                val x = qx + ux * du + nrm[0] * off; val z = qz + uz * du + nrm[2] * off
                if (onWater(S, x, z)) continue
                val cc = cam.cam(V3(x, 0.75, z))
                if (cc[2] < 1.5) continue
                val tbl = ArrayList<String>()
                val top = (0 until 12).map { i -> val a = i * Math.PI / 6; V3(x + 0.38 * PyMath.cos(a), 0.75, z + 0.38 * PyMath.sin(a)) }
                val l0 = cam.proj(V3(x, 0.0, z)); val l1 = cam.proj(V3(x, 0.75, z))
                val wood = if (!night) "#6B5442" else "#1A1714"
                if (l0 != null && l1 != null) tbl.add(pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"%s\" stroke-width=\"%.2f\"/>", l0[0], l0[1], l1[0], l1[1], wood, pmax(0.4, 0.06 * cam.focal / cc[2])))
                for (cu in doubleArrayOf(-0.6, 0.6)) { // two chairs either side
                    val chx = x + ux * cu; val chz = z + uz * cu
                    tbl.add(drawBox(cam, th, chx, chz, 0.42, 0.42, 0.46, "#3E3A36"))
                    val bk = face(cam, listOf(
                        V3(chx + ux * cu * 0.3 - 0.2 * nrm[0], 0.46, chz + uz * cu * 0.3 - 0.2 * nrm[2]), V3(chx + ux * cu * 0.3 + 0.2 * nrm[0], 0.46, chz + uz * cu * 0.3 + 0.2 * nrm[2]),
                        V3(chx + ux * cu * 0.3 + 0.2 * nrm[0], 0.9, chz + uz * cu * 0.3 + 0.2 * nrm[2]), V3(chx + ux * cu * 0.3 - 0.2 * nrm[0], 0.9, chz + uz * cu * 0.3 - 0.2 * nrm[2]),
                    ), if (!night) "#3E3A36" else "#121214")
                    tbl.add(bk)
                }
                tbl.add(face(cam, top, if (!night) "#E9E3D6" else "#2A2826", " stroke=\"#9B9182\" stroke-width=\".3\""))
                if (!night && k < 2) { // a parasol over the front tables
                    val p0 = cam.proj(V3(x, 0.75, z)); val p1 = cam.proj(V3(x, 2.35, z))
                    if (p0 != null && p1 != null) tbl.add(pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"#5A5048\" stroke-width=\"%.2f\"/>", p0[0], p0[1], p1[0], p1[1], pmax(0.4, 0.05 * cam.focal / cc[2])))
                    val ring = (0 until 12).map { i -> val a = i * Math.PI / 6; V3(x + 1.3 * PyMath.cos(a), 2.15, z + 1.3 * PyMath.sin(a)) }
                    val apex = V3(x, 2.65, z); val pcol = st.bg?.takeIf { it.isNotEmpty() } ?: tohex(mulc(col, 1.0))
                    val fan = StringBuilder()
                    for (i in 0 until 12) {
                        val tri = listOf(ring[i], ring[(i + 1) % 12], apex)
                        var nr = vnorm(vcross(vsub(tri[1], tri[0]), vsub(tri[2], tri[0])))
                        if (nr[1] < 0) nr = neg(nr)
                        val shade = if (vdot(nr, v3(th["_L"])) > 0) 1.0 else 0.78
                        fan.append(face(cam, tri, tohex(mulc(hx(if (i % 2 != 0) pcol else "#F4EFE6"), shade))))
                    }
                    tbl.add(fan.toString())
                }
                out.add(noted(Item(cc[2], "svg", tbl.joinToString("")), cam, "terrace", null, Double.NaN, Double.NaN, x, 0.75, z))
            }
        }
        return out
    }

    // ------------------------------------------------------------------ street furniture
    // render/amsterdam.py:1897 draw_bench
    fun drawBench(cam: Camera, th: Map<String, Any?>, n: Map<String, Any?>): String {
        val x = d(n["x"]); val z = d(n["z"]); val c = "#8A6A4C"
        return drawBox(cam, th, x, z, 1.8, 0.5, 0.45, c) + drawBox(cam, th, x, z + 0.22, 1.8, 0.08, 0.9, "#7A5C40")
    }

    // render/amsterdam.py:1901 draw_bollard
    /** barrier=bollard: a generic dark cast-iron post with a round head. */
    fun drawBollard(cam: Camera, th: Map<String, Any?>, n: Map<String, Any?>, dd: Double): String {
        val p0 = cam.proj(V3(d(n["x"]), 0.0, d(n["z"]))); val p1 = cam.proj(V3(d(n["x"]), 0.85, d(n["z"])))
        if (p0 == null || p1 == null) return ""
        val night = th["night"] == true
        val s = cam.focal / dd; val col = if (!night) "#4A2620" else "#140B0A"
        var out = pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"%s\" stroke-width=\"%.2f\" stroke-linecap=\"round\"/>", p0[0], p0[1], p1[0], p1[1], col, pmax(0.7, 0.2 * s))
        if (!night && s > 1) out += pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.2f\" fill=\"#B07A6A\" opacity=\".6\"/>", p1[0] - 0.03 * s, p1[1], pmax(0.2, 0.05 * s))
        return out
    }

    // render/amsterdam.py:1910 draw_box
    fun drawBox(cam: Camera, th: Map<String, Any?>, x: Double, z: Double, w: Double, dd: Double, h: Double, col: String): String {
        val q = listOf(V3(x - w / 2, h, z - dd / 2), V3(x + w / 2, h, z - dd / 2), V3(x + w / 2, h, z + dd / 2), V3(x - w / 2, h, z + dd / 2))
        val c = if (th["night"] != true) hx(col) else mulc(hx(col), 0.3)
        var s = face(cam, q, tohex(c))
        val p0 = cam.proj(V3(x, 0.0, z)); val p1 = cam.proj(V3(x, h, z))
        if (p0 != null && p1 != null) s = pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"%s\" stroke-width=\"1\"/>", p0[0], p0[1], p1[0], p1[1], tohex(mulc(c, 0.8))) + s
        return s
    }

    // render/amsterdam.py:1918 draw_rack
    fun drawRack(cam: Camera, th: Map<String, Any?>, n: Map<String, Any?>): String {
        val segs = ArrayList<String>()
        for (k in 0 until 3) {
            val xx = d(n["x"]) - 1.0 + k
            val pts = (0 until 9).map { i -> val a = i * Math.PI / 8; cam.proj(V3(xx + 0.35 * PyMath.cos(a), 0.8 * PyMath.sin(a), d(n["z"]))) }
            if (pts.all { it != null }) segs.add("M" + pts.joinToString("L") { pf("%.1f %.1f", it!!.x, it.y) })
        }
        return if (segs.isNotEmpty()) pf("<path d=\"%s\" stroke=\"%s\" stroke-width=\".9\" fill=\"none\"/>", segs.joinToString(""), if (th["night"] != true) "#6B7282" else "#2A2E3A") else ""
    }
}
