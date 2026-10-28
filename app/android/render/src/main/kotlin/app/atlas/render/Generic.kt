// render/generic.py: generic looks for buildings whose colour or material is not mapped. Real OSM values
// (building:material, building:colour, roof:material, roof:colour), chosen from what is mapped about the building
// itself and the land use it stands in, for the country it is in, or from a building tradition
// (render/traditions/<id>.json). Every value set here is marked guessed, with the reason. Ported line by line.
package app.atlas.render

import app.atlas.render.py.Dict
import app.atlas.render.py.GridMap
import app.atlas.render.py.PyFmt.pf
import app.atlas.render.py.PyJson
import app.atlas.render.py.PyMath
import app.atlas.render.py.PyRandom
import app.atlas.render.py.pyNum
import app.atlas.render.py.truthy
import java.io.File
import java.math.BigInteger

/**
 * The generic looks of render/generic.py. Buildings are Python-like dicts ([Dict]): `tags`, `poly` (a list of
 * [V2]), `h`, `year`, `id`, `roof`, `roof_h`, `levels`, `src`, `guessed`, … Numbers keep Python's int and float
 * types (Int or Long for int, Double for float), so the values written back are the ones Python writes.
 */
object Generic {
    val HOME: Set<String> = linkedSetOf("house", "detached", "semidetached_house", "terrace", "bungalow", "residential", "apartments", "dormitory", "farm")

    /** A kind of building: its building=* values and its other tag test. */
    class Kind(val kind: String, val values: Set<String>, val test: (Map<String, Any?>) -> Boolean)

    private fun s(vararg v: String): Set<String> = linkedSetOf(*v)

    val KINDS: List<Kind> = listOf(
        Kind("worship", s("church", "chapel", "cathedral", "mosque", "synagogue", "temple", "religious", "shrine")) { t -> t["amenity"] == "place_of_worship" },
        Kind("terminal", s("terminal", "airport_terminal", "transportation", "train_station")) { t -> t["aeroway"] == "terminal" || t["public_transport"] == "station" },
        Kind("tower", s("tower")) { t -> t["man_made"] == "tower" || t["aeroway"] == "control_tower" },
        Kind("tank", s("storage_tank", "silo")) { t -> t["man_made"] in setOf("storage_tank", "silo") },
        Kind("hangar", s("hangar")) { false },
        Kind("greenhouse", s("greenhouse", "glasshouse")) { false },
        Kind("parking", s("parking")) { t -> t["amenity"] == "parking" },
        Kind("bunker", s("bunker")) { t -> t["military"] == "bunker" },
        Kind("canopy", s("roof", "carport")) { false },
        Kind("kiosk", s("kiosk")) { false },
        Kind("caravan", s("static_caravan")) { false },
        Kind("shed", s("shed", "garage", "garages", "hut", "cabin", "farm_auxiliary", "barn", "stable", "cowshed", "boathouse")) { false },
        Kind("utility", s("service", "transformer_tower", "substation", "pumping_station")) { t -> t["power"] == "substation" },
        Kind("industrial", s("industrial", "warehouse", "factory", "manufacture", "storage", "depot")) { t -> truthy(t["industrial"]) },
        Kind("school", s("school", "university", "college", "kindergarten")) { t -> t["amenity"] in setOf("school", "university", "college", "kindergarten") },
        Kind("hospital", s("hospital")) { t -> t["amenity"] in setOf("hospital", "clinic") },
        Kind("hotel", s("hotel")) { t -> t["tourism"] == "hotel" },
        Kind("sports", s("sports_hall", "sports_centre", "stadium", "grandstand", "riding_hall")) { t -> t["leisure"] in setOf("sports_centre", "sports_hall", "stadium") },
        Kind("retail", s("retail", "supermarket", "commercial_retail")) { t -> truthy(t["shop"]) },
        Kind("office", s("office", "commercial", "government", "civic", "public", "townhall", "bank", "courthouse", "police", "fire_station")) { t -> truthy(t["office"]) },
        Kind("monument", s("castle", "palace", "museum")) { t -> t["tourism"] == "museum" || t["historic"] in setOf("castle", "palace", "building", "monument") },
        Kind("home", HOME) { false },
    )
    val LANDUSE_KIND: Map<String, String> = linkedMapOf(
        "industrial" to "industrial", "port" to "industrial", "railway" to "industrial", "commercial" to "office", "retail" to "retail",
        "residential" to "home", "farmyard" to "shed", "education" to "school", "religious" to "worship", "military" to "bunker", "static_caravan" to "caravan",
    )

    // one plain value per kind of wall and roof (as building:colour / roof:colour would be mapped)
    val WALL: Map<String, Pair<String, String>> = linkedMapOf(
        "brick_old" to ("brick" to "#74402F"), "brick" to ("brick" to "#8E5A46"), "brick_light" to ("brick" to "#A27A62"), "brick_yellow" to ("brick" to "#C2A274"),
        "limestone" to ("limestone" to "#D8CCB2"), "plaster" to ("plaster" to "#E6DFD1"), "concrete" to ("concrete" to "#BEBAB1"), "concrete_dark" to ("concrete" to "#A3A09A"),
        "glass" to ("glass" to "#93ABBC"), "metal_silver" to ("metal" to "#C3C7CA"), "metal_grey" to ("metal" to "#9AA1A7"), "metal_white" to ("metal" to "#E1E2DF"),
        "metal_anthracite" to ("metal" to "#5B6066"), "wood" to ("wood" to "#7A624C"), "metal_green" to ("metal" to "#4C5E52"),
    )
    val ROOF: Map<String, Pair<String, String>> = linkedMapOf(
        "tiles_red" to ("roof_tiles" to "#9E5038"), "tiles_dark" to ("roof_tiles" to "#3F4246"), "slate" to ("slate" to "#535C66"), "zinc" to ("metal" to "#737E88"),
        "lead" to ("metal" to "#7A8187"), "metal_light" to ("metal" to "#A4A9AD"), "metal_grey" to ("metal" to "#8F969B"), "bitumen" to ("tar_paper" to "#56585C"),
        "gravel" to ("gravel" to "#9C988F"), "concrete" to ("concrete" to "#A7A49E"), "glass" to ("glass" to "#C3D3DA"),
    )

    // render/generic.py RED_TILES
    /** New looks: the share of homes' guessed gabled roofs in the canal ring pack's red tiles (about one in four, 28 Oct 2026). */
    const val RED_TILES = 0.25

    /** Paris stone is not one tone: the guessed building:colour of an unmapped limestone front is one of these, per building. */
    val STONE_FR = listOf("#DCD0B6", "#D3C4A6", "#E2D8C3", "#CDC1A8", "#D8C8AA", "#C8BCA3", "#E0D2B5", "#D6CCB9")
    val NO_WIN: Set<String> = s("industrial", "hangar", "tank", "canopy", "shed", "parking", "greenhouse", "bunker", "caravan", "kiosk", "terminal")
    val SLOPED: List<String> = listOf("gabled", "hipped", "pyramidal", "skillion", "gambrel", "mansard", "half-hipped", "round", "dome", "onion", "saltbox")

    // ---------------------------------------------------------------- Python values

    @Suppress("UNCHECKED_CAST")
    private fun tagsOf(b: Map<String, Any?>): Map<String, Any?> = b["tags"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun polyOf(b: Map<String, Any?>): List<V2> = (b["poly"] as List<Any?>).let { p -> if (p.all { it is V2 }) p as List<V2> else v2s(p) }

    /** Python `a or b`. */
    private fun or(a: Any?, b: Any?): Any? = if (truthy(a)) a else b

    /** `d.get(k, default)`. */
    private fun Map<String, Any?>.getOr(k: String, default: Any?): Any? = if (containsKey(k)) this[k] else default

    private fun isInt(v: Any?): Boolean = v is Int || v is Long || v is BigInteger || v is Boolean

    private fun long(v: Any?): Long = when (v) {
        is Boolean -> if (v) 1L else 0L
        is Number -> if (v is Double || v is Float) throw IllegalArgumentException("not an int: $v") else v.toLong()
        else -> throw IllegalArgumentException("not an int: $v")
    }

    private fun d(v: Any?): Double = if (v is Boolean) (if (v) 1.0 else 0.0) else pyNum(v)

    /** Python `a + b` on numbers: int if both are ints. */
    private fun add(a: Any?, b: Any?): Any = if (isInt(a) && isInt(b)) long(a) + long(b) else d(a) + d(b)

    /** Python `a - b` on numbers. */
    private fun sub(a: Any?, b: Any?): Any = if (isInt(a) && isInt(b)) long(a) - long(b) else d(a) - d(b)

    /** Python `a * b` on numbers. */
    private fun mul(a: Any?, b: Any?): Any = if (isInt(a) && isInt(b)) long(a) * long(b) else d(a) * d(b)

    /** Python `a < b` on numbers (None fails as in Python). */
    private fun lt(a: Any?, b: Any?): Boolean = if (isInt(a) && isInt(b)) long(a) < long(b) else d(a) < d(b)

    /** Python `max(a, b)`: a unless b is greater. */
    private fun pmax(a: Any?, b: Any?): Any? = if (lt(a, b)) b else a

    /** Python `min(a, b)`: a unless b is smaller. */
    private fun pmin(a: Any?, b: Any?): Any? = if (lt(b, a)) b else a

    /** Python `round(x, 1)`: an int stays the int. */
    private fun round1(v: Any?): Any? = if (isInt(v)) v else PyMath.round(d(v), 1)

    /** Python truthiness of a year (None and 0 are false). */
    private fun yrT(yr: Any?): Boolean = truthy(yr)

    /** `d.get(k)` for a key that may be None. */
    private fun Map<String, String>.at(k: String?): String? = if (k == null) null else this[k]

    private fun absArea(poly: List<V2>): Double = Math.abs(Real.signedArea(poly))

    // render/generic.py:54 kind_of
    /** (kind, reason) for a building from its own tags, else the land use around it, its size, or the region's usual kind. */
    fun kindOf(t: Map<String, Any?>, landuse: String?, region: String?, foot: Double = 0.0, h: Any? = null): Pair<String, String> {
        val bt = or(or(t["building"], t["building:part"]), "yes") as String
        for (kd in KINDS) {
            if (bt in kd.values || kd.test(t)) {
                return kd.kind to (if (bt in kd.values) "building=$bt" else (REASON_KEYS.firstOrNull { truthy(t[it]) }?.let { "$it=${t[it]}" } ?: "its tags"))
            }
        }
        if (landuse != null && landuse in LANDUSE_KIND) return LANDUSE_KIND.getValue(landuse) to "landuse=$landuse around it"
        if (foot > 2500 && lt(or(h, 0), 20)) return "industrial" to "its large, low footprint"
        val default = mapOf("paris" to "home", "port" to "industrial", "schiphol" to "office", "dunes" to "home")[region] ?: "home"
        return default to "what most buildings here are"
    }

    private val REASON_KEYS = listOf("amenity", "aeroway", "man_made", "tourism", "office", "shop", "historic", "industrial", "power", "leisure", "public_transport", "military")

    /** One building's generic look: wall and roof keys of [WALL] and [ROOF], its kind and the reason. */
    data class Look(val wall: String, val roof: String, val kind: String, val why: String)

    // render/generic.py:64 look
    /** (wall key, roof key, kind, reason) for one building; FR for Paris, NL elsewhere. */
    fun look(b: Map<String, Any?>, region: String?, landuse: String? = null): Look {
        val t = tagsOf(b); val yr = b["year"]; val h = d(or(b["h"], 0))
        val lv = Real.num(t["building:levels"]).let { if (it != null && it != 0.0) it else (if (h != 0.0) h / 3.1 else 2.0) }
        var (kind, why) = kindOf(t, landuse, region, absArea(polyOf(b)), b["h"])
        if (kind == "tower" && (t["tower:type"] in setOf("bell_tower", "church") || truthy(t["historic"]) || truthy(t["heritage"]) || (yrT(yr) && lt(yr, 1900)))) {
            kind = if (t["tower:type"] == "bell_tower" || t["building:part"] == "church") "worship" else "monument"
        }
        val sloped = or(b["roof"], t["roof:shape"]) in SLOPED
        val shape = t["roof:shape"]
        val tall = h >= 28 || lv >= 9
        val fr = region == "paris"
        var wall: String; var roof: String
        if (fr) {
            when {
                kind in setOf("worship", "monument") -> {
                    wall = "limestone"; roof = if (kind == "worship") "lead" else "slate"
                }
                kind == "home" -> {
                    wall = if (yrT(yr) && !lt(yr, 1950)) "concrete" else (if (yrT(yr) && lt(yr, 1830)) "plaster" else "limestone")
                    roof = if (!(yrT(yr) && !lt(yr, 1950))) "zinc" else "gravel"
                }
                kind in setOf("office", "hotel", "hospital") -> {
                    wall = if (yrT(yr) && !lt(yr, 1965)) "glass" else "limestone"; roof = if (wall == "limestone") "zinc" else "gravel"
                }
                kind == "retail" -> {
                    wall = if ((yrT(yr) && !lt(yr, 1960)) || lv <= 2) "glass" else "limestone"; roof = if (lv <= 2) "glass" else "zinc"
                }
                kind == "school" -> { wall = "brick_light"; roof = "slate" }
                kind == "kiosk" -> { wall = "metal_green"; roof = "zinc" }
                kind == "canopy" -> { wall = "metal_grey"; roof = "glass" }
                kind in setOf("industrial", "utility", "shed") -> { wall = "plaster"; roof = "zinc" }
                else -> { wall = "limestone"; roof = "zinc" }
            }
            if (shape in setOf("gabled", "hipped") && roof == "zinc" && yrT(yr) && lt(yr, 1800)) roof = "slate"
        } else {
            val old = yr != null && lt(yr, 1940); val post = yr != null && !lt(yr, 1975)
            when (kind) {
                "home" -> {
                    wall = if (t["building"] == "apartments" && (lv >= 5 || (yrT(yr) && !lt(yr, 1955) && lt(yr, 1985)))) (if (!post) "concrete" else "brick_light")
                    else (if (old) "brick_old" else (if (post) "brick_light" else "brick"))
                    roof = if (sloped) (if (old || !post) "tiles_red" else "tiles_dark") else "bitumen"
                }
                "worship" -> { wall = "brick_old"; roof = "slate" }
                "monument" -> { wall = "brick_old"; roof = if (sloped) "slate" else "bitumen" }
                "office", "hotel", "hospital" -> {
                    wall = if (yrT(yr) && lt(yr, 1960)) (if (old) "brick_old" else "brick") // an office of its era, before curtain walls
                    else if (tall || (post && lv >= 4) || (yr == null && lv >= 6) || (region == "schiphol" && h >= 12)) "glass"
                    else (if (!post && region != "schiphol") "brick" else "concrete")
                    roof = if (!sloped) "gravel" else "tiles_dark"
                }
                "retail" -> { wall = if (!old) "concrete" else "brick"; roof = "bitumen" }
                "school" -> { wall = if (!old) "brick_light" else "brick"; roof = if (!sloped) "bitumen" else "tiles_red" }
                "terminal" -> { wall = "glass"; roof = "metal_light" }
                "hangar" -> { wall = "metal_silver"; roof = "metal_light" }
                "industrial" -> {
                    wall = if (!old) (if (Math.floorMod(long(b["id"]), 3L) != 0L) "metal_grey" else "metal_anthracite") else "brick"
                    roof = if (!old) "metal_light" else "tiles_red"
                }
                "tank" -> { wall = "metal_white"; roof = "metal_light" }
                "tower" -> { wall = "concrete"; roof = "concrete" }
                "parking" -> { wall = "concrete_dark"; roof = "concrete" }
                "greenhouse" -> { wall = "glass"; roof = "glass" }
                "bunker" -> { wall = "concrete_dark"; roof = "concrete" }
                "caravan" -> { wall = "metal_white"; roof = "metal_light" }
                "kiosk" -> { wall = "wood"; roof = "bitumen" }
                "canopy" -> { wall = "metal_grey"; roof = "metal_grey" }
                "shed" -> { wall = "wood"; roof = if (sloped) "tiles_dark" else "bitumen" }
                "utility" -> { wall = "brick"; roof = if (!sloped) "bitumen" else "tiles_red" }
                "sports" -> { wall = "metal_grey"; roof = "metal_light" }
                else -> { wall = "brick"; roof = "bitumen" }
            }
        }
        return Look(wall, roof, kind, why)
    }

    // render/generic.py:118 jitter
    /** The drawn colour: the guessed value, a little lighter or darker per building. */
    fun jitter(hexcol: String, seed: Any?, k: Double = 0.05): String {
        val r = PyRandom(seed); val f = 1 + r.uniform(-k, k); val w = r.uniform(-k, k) * 0.4
        val c = intArrayOf(hexcol.substring(1, 3).toInt(16), hexcol.substring(3, 5).toInt(16), hexcol.substring(5, 7).toInt(16))
        val o = IntArray(3) { i ->
            val v = c[i]
            val x = v * f + (if (i == 0) 12 * w else if (i == 2) -8 * w else 0.0)
            Math.min(255L, Math.max(0L, PyMath.int(x))).toInt()
        }
        return pf("#%02X%02X%02X", o[0], o[1], o[2])
    }

    // a mapped material with no mapped colour is drawn as the renderer always drew it (amsterdam.MATERIAL)
    val MAT_DRAW: Map<String, String> = linkedMapOf(
        "brick" to "#9C5A45", "stone" to "#CFC6B4", "copper" to "#5E9E86", "glass" to "#A9C2D6", "concrete" to "#BDBAB2", "wood" to "#9C7A55",
        "roof_tiles" to "#A5553C", "slate" to "#56606A", "metal" to "#9AA3AB", "plaster" to "#E6E0D4", "sandstone" to "#D8C49A",
    )
    val MAT_COL: Map<String, String> = linkedMapOf(
        "brick" to "#8E5A46", "limestone" to "#D8CCB2", "sandstone" to "#D2BE94", "stone" to "#C9C0AE", "plaster" to "#E6DFD1", "concrete" to "#BEBAB1",
        "glass" to "#93ABBC", "mirrored_glass" to "#8EA6B8", "metal" to "#A9AFB4", "steel" to "#A9AFB4", "wood" to "#7A624C", "timber_framing" to "#D9D0BE", "cement_block" to "#B3AFA6",
    )
    val RMAT_COL: Map<String, String> = linkedMapOf(
        "roof_tiles" to "#9E5038", "slate" to "#535C66", "metal" to "#8F969B", "tar_paper" to "#56585C", "gravel" to "#A9A59D", "concrete" to "#A7A49E",
        "glass" to "#C3D3DA", "copper" to "#5E9E86", "grass" to "#7C9A5A", "plants" to "#7C9A5A", "thatch" to "#8E7A52", "eternit" to "#8C8A86", "zinc" to "#8C969E", "lead" to "#7A8187",
    )

    /** Colour words as OSM mappers write them (CSS names), to the colour they mean. */
    val NAMED: Map<String, String> = linkedMapOf(
        "white" to "#FFFFFF", "black" to "#2A2B2E", "grey" to "#808080", "gray" to "#808080", "lightgrey" to "#D3D3D3", "lightgray" to "#D3D3D3", "darkgrey" to "#A9A9A9",
        "darkgray" to "#A9A9A9", "dimgray" to "#696969", "dimgrey" to "#696969", "silver" to "#C0C0C0", "gainsboro" to "#DCDCDC", "whitesmoke" to "#F5F5F5", "slategray" to "#708090",
        "slategrey" to "#708090", "red" to "#B03A2E", "darkred" to "#8B0000", "firebrick" to "#B22222", "indianred" to "#CD5C5C", "brown" to "#8B5A3C", "maroon" to "#800000",
        "saddlebrown" to "#8B4513", "sienna" to "#A0522D", "peru" to "#CD853F", "sandybrown" to "#F4A460", "burlywood" to "#DEB887", "tan" to "#D2B48C", "wheat" to "#F5DEB3",
        "beige" to "#E8E0C8", "ivory" to "#FFFFF0", "linen" to "#FAF0E6", "antiquewhite" to "#FAEBD7", "cream" to "#F3EBD3", "khaki" to "#C3B091", "yellow" to "#E6C840",
        "gold" to "#D4AF37", "orange" to "#E07B39", "coral" to "#FF7F50", "salmon" to "#FA8072", "tomato" to "#E5533C", "pink" to "#E8A0B0", "rosybrown" to "#BC8F8F",
        "green" to "#4F7A3A", "darkgreen" to "#2F5233", "olive" to "#808000", "blue" to "#3A5F9A", "navy" to "#1F2E5A", "lightblue" to "#ADD8E6", "skyblue" to "#87CEEB",
        "teal" to "#2F7F7F", "purple" to "#6A4A8A", "darkslategray" to "#2F4F4F", "darkslategrey" to "#2F4F4F", "mahogany" to "#7B3A2A", "burgundy" to "#7A2533",
        "ochre" to "#C8913F", "lightyellow" to "#F2E8B6", "lightgreen" to "#B5D3A8", "lightpink" to "#EBCBCB", "pink" to "#E8B4BE", "terracotta" to "#B5643F",
    )

    /** `(v or '').strip().lower().replace(' ', '').replace('_', '')`. */
    private fun colourWord(v: String?): String = PyRe.strip(v ?: "").lowercase().replace(" ", "").replace("_", "")

    // render/generic.py:146 paint
    /** A colour word as the paint on a wall: the CSS colour a little toward white, as house paint is (hex values stay exact). */
    fun paint(v: String?, k: Double = 0.16): String? {
        val w = colourWord(v)
        val h = colourHex(v)
        if (h == null || w.startsWith("#") || w in setOf("white", "black")) return h
        val c = intArrayOf(h.substring(1, 3).toInt(16), h.substring(3, 5).toInt(16), h.substring(5, 7).toInt(16)); val t = intArrayOf(244, 240, 232)
        val o = IntArray(3) { i -> PyMath.int(c[i] + (t[i] - c[i]) * k).toInt() }
        return pf("#%02X%02X%02X", o[0], o[1], o[2])
    }

    private val HEX6 = Regex("#[0-9a-f]{6}")
    private val HEX3 = Regex("#[0-9a-f]{3}")

    // render/generic.py:155 colour_hex
    /** A mapped colour as #RRGGBB: hex as written (#abc too), or a CSS colour name; null when not understood. */
    fun colourHex(v0: String?): String? {
        val v = colourWord(v0)
        if (HEX6.matches(v)) return v.uppercase()
        if (HEX3.matches(v)) return ("#" + v.substring(1).map { "$it$it" }.joinToString("")).uppercase()
        return NAMED[v]
    }

    // render/generic.py:162 neighbour_levels
    /** Neither height nor building:levels mapped: the median levels of the mapped buildings within r metres (guessed), where there are three or more. */
    fun neighbourLevels(S: Map<String, Any?>, r: Double = 80.0): Int {
        class Mapped(val x: Double, val z: Double, val lv: Any?)
        val grid = GridMap<ArrayList<Mapped>>()
        fun key(i: Long, j: Long): Long = GridMap.key(i, j)
        @Suppress("UNCHECKED_CAST")
        val buildings = S["buildings"] as List<Dict>
        for (b in buildings) {
            val t_ = tagsOf(b)
            if (t_["man_made"] == "tower" || t_["building"] in setOf("tower", "church", "cathedral", "chapel", "bell_tower", "campanile", "transformer_tower")) continue // not like a house
            if (b["src"] in setOf("levels", "height") && !lt(b.getOr("levels", 0), 1)) {
                val (x, z) = Real.centroid(polyOf(b))
                grid.getOrPut(key(PyMath.int(PyMath.floorDiv(x, r)), PyMath.int(PyMath.floorDiv(z, r)))) { ArrayList() }.add(Mapped(x, z, b["levels"]))
            }
        }
        var n = 0
        for (b in buildings) {
            @Suppress("UNCHECKED_CAST")
            val guessed = (b.getOr("guessed", emptyMap<String, Any?>()) as Map<String, Any?>)
            if (truthy(b["src"]) || !truthy(guessed["height"]) || tagsOf(b)["building"] in setOf("roof", "kiosk", "shed", "garage", "hut", "houseboat")) continue
            val (x, z) = Real.centroid(polyOf(b)); val i = PyMath.int(PyMath.floorDiv(x, r)); val j = PyMath.int(PyMath.floorDiv(z, r))
            val near = ArrayList<Any?>()
            for (di in -1..1) for (dj in -1..1) {
                for (m in grid[key(i + di, j + dj)] ?: continue) if (PyMath.hypot(m.x - x, m.z - z) < r) near.add(m.lv)
            }
            val sorted = near.sortedWith { a, c -> if (lt(a, c)) -1 else if (lt(c, a)) 1 else 0 }
            if (sorted.size < 3) continue
            val lv = sorted[sorted.size / 2]
            b["levels"] = lv; b["h"] = d(lv) * 3.0 + d(or(b["roof_h"], 0))
            @Suppress("UNCHECKED_CAST")
            (b["guessed"] as Dict)["building:levels"] = true
            b["lv_why"] = "the buildings around it"; n += 1
        }
        return n
    }

    // render/generic.py:181 paris_roof
    /**
     * A Paris stone front of four to nine floors, built before 1940 or of unknown age, with no roof shape mapped: roof:shape=mansard (guessed),
     * one roof storey of zinc with dormers, above its mapped levels.
     */
    fun parisRoof(b: Dict, kind: String, wall: String, g: Dict): Boolean {
        val t = tagsOf(b); val yr = b["year"]
        if ("building:part" in t || !truthy(g["roof:shape"]) || b["roof"] !in setOf(null, "flat")) return false
        if (kind !in setOf("home", "office", "hotel", "retail", "hospital", "school", "monument") || wall !in setOf("limestone", "plaster")) return false
        if (yrT(yr) && !(!lt(yr, 1760) && lt(yr, 1940))) return false
        val lvs = b.getOr("levels", 0)
        if (!(!lt(lvs, 4) && !lt(9, lvs)) || absArea(polyOf(b)) < 80) return false
        val rl = Real.num(t["roof:levels"])
        b["roof"] = "mansard"; b["roof_h"] = if (rl != null && rl != 0.0) rl * 3.0 else 3.4; g["roof:height"] = true
        if (b["src"] == "height") {
            if (!truthy(t["building:levels"])) b["levels"] = Math.max(1L, PyMath.round((d(b["h"]) - d(b["roof_h"])) / 3.1))
        } else {
            b["h"] = d(b["levels"]) * 3.0 + d(b["roof_h"])
        }
        return true
    }

    // ---------------------------------------------------------------- building traditions
    // A tradition is a short data file (render/traditions/<id>.json): the walls, roofs, windows and details a building culture typically has, by kind
    // and era. Every value it gives is a real OSM value, guessed and marked; what is mapped always wins. Details with no tag (shutters, chimneys,
    // eaves, timber boards) are generic details of the tradition, drawn as windows are.

    val INDUSTRIAL_LIKE: Set<String> = s("industrial", "hangar", "tank", "canopy", "parking", "greenhouse", "bunker", "caravan", "kiosk", "terminal", "utility", "sports")
    val SAYS_KIND: Set<String> = s("home", "office", "hotel", "retail", "school")

    /**
     * Where traditions are read from: a folder holding `<id>.json` (the repo's render/traditions in tests), or, when
     * null, the module's resources (`/traditions/<id>.json`).
     */
    @Volatile
    var tradDir: File? = null

    // render/generic.py:206 tradition
    /** The tradition [tid] as Python's `json.load` gives it, or null when [tid] is null or empty. */
    @Suppress("UNCHECKED_CAST")
    fun tradition(tid: String?): Dict? {
        if (tid.isNullOrEmpty()) return null
        val dir = tradDir
        val text = if (dir != null) {
            File(dir, "$tid.json").readText(Charsets.UTF_8)
        } else {
            (Generic::class.java.getResourceAsStream("/traditions/$tid.json") ?: throw java.io.FileNotFoundException("traditions/$tid.json"))
                .use { it.readBytes().toString(Charsets.UTF_8) }
        }
        return PyJson.loads(text) as Dict
    }

    // render/generic.py:210 pick
    /** One of [value..., weight] by weight, the same every time for the same building. */
    fun pick(options: List<*>, seed: Any?): Any? {
        val weights = options.map { d((it as List<*>).last()) }
        val r = PyRandom(seed).uniform(0.0, PyMath.sum(weights.toDoubleArray())); var acc: Any? = 0
        for (o0 in options) {
            val o = o0 as List<*>
            acc = add(acc, o.last())
            if (r <= d(acc)) return if (o.size > 2) o.subList(0, o.size - 1) else o[0]
        }
        val o = options.last() as List<*>
        return if (o.size > 2) o.subList(0, o.size - 1) else o[0]
    }

    // render/generic.py:219 era
    /** An entry that differs by age: {"before:1830": ..., "after:1950": ..., "default": ...}, the first that fits; a list or plain entry as it is. */
    fun era(entry: Any?, yr: Any?): Any? {
        if (entry !is Map<*, *> || "shapes" in entry || "material" in entry) return entry
        for ((k0, v) in entry) {
            val k = k0 as String
            if (k.startsWith("before:") && yrT(yr) && lt(yr, PyRe.int(k.substring(7)))) return v
            if (k.startsWith("after:") && yrT(yr) && !lt(yr, PyRe.int(k.substring(6)))) return v
        }
        return entry["default"]
    }

    // render/generic.py:228 trad_entry
    fun tradEntry(table: Map<String, Any?>, btype: String, kind: String, modern: Boolean, yr: Any? = null): Any? {
        for (k in (if (modern) listOf("modern") else emptyList()) + listOf(btype, kind, "default")) {
            if (k in table) {
                val v = era(table[k], yr)
                if (v != null) return v
            }
        }
        return null
    }

    // render/generic.py:236 fits
    /** Does a rule's "when" hold for this building? kind, building, material, year {from, to, unknown}, levels [min, max], area [min, max], narrow, min_h. */
    fun fits(`when`: Map<String, Any?>, b: Map<String, Any?>, kind: String, md: Any?, short: Double): Boolean {
        val t = tagsOf(b); val yr = b["year"]; val bt = or(t["building"], "yes")
        if ("kind" in `when` && kind !in (`when`["kind"] as List<*>)) return false
        if ("building" in `when` && bt !in (`when`["building"] as List<*>)) return false
        if ("material" in `when` && md !in (`when`["material"] as List<*>)) return false
        if ("year" in `when`) {
            @Suppress("UNCHECKED_CAST")
            val y = `when`["year"] as Map<String, Any?>
            if (yr == null) {
                if (!truthy(y.getOr("unknown", false))) return false
            } else if (lt(yr, y.getOr("from", -9999)) || !lt(yr, y.getOr("to", 9999))) return false
        }
        if ("levels" in `when`) {
            val lv = `when`["levels"] as List<*>; val bl = b.getOr("levels", 0)
            if (!(!lt(bl, lv[0]) && !lt(lv[1], bl))) return false
        }
        val ar = absArea(polyOf(b))
        if ("area" in `when`) {
            val a = `when`["area"] as List<*>
            if (!(!lt(ar, a[0]) && !lt(a[1], ar))) return false
        }
        if ("narrow" in `when` && !lt(short, `when`["narrow"])) return false
        if ("min_h" in `when` && !lt(`when`["min_h"], or(b["h"], 0))) return false
        return true
    }

    // render/generic.py:255 trad_look
    /** The tradition's look for one building whose walls or roof are not mapped. False where the tradition says nothing about its kind. */
    @Suppress("UNCHECKED_CAST")
    fun tradLook(b: Dict, P: Map<String, Any?>, kind: String, why: String, g: Dict, @Suppress("UNUSED_PARAMETER") region: String?): Boolean {
        val t = tagsOf(b); val bt = or(or(t["building"], t["building:part"]), "yes") as String; val yr = b["year"]
        val modern = yrT(yr) && !lt(yr, P.getOr("modern_from", 1950))
        val seed = b.getOr("parent_id", b["id"])
        val pWalls = P["walls"] as Map<String, Any?>
        val walls = tradEntry(pWalls, bt, kind, modern, yr)
        if (walls == null || (kind in INDUSTRIAL_LIKE && kind !in pWalls && bt !in pWalls)) return false
        val part = "building:part" in t
        val poly = polyOf(b); val n = poly.size
        val edges = DoubleArray(n) { i -> PyMath.dist(doubleArrayOf(poly[i].x, poly[i].y), doubleArrayOf(poly[(i + 1) % n].x, poly[(i + 1) % n].y)) }
        edges.sort()
        val longest = edges[n - 1]
        val short = PyMath.min(longest, PyMath.max(3.0, absArea(poly) / PyMath.max(longest, 1.0))) // the footprint's width across its longest side
        val floorH = P["floor_h"]
        // height: the tradition's usual number of floors, where neither height nor levels is mapped and no mapped neighbours say otherwise
        if (truthy(g["height"]) && !truthy(b["lv_why"]) && !part) {
            val heights = P.getOr("heights", emptyMap<String, Any?>()) as Map<String, Any?>
            if (kind in heights && (kind != "tower" || absArea(poly) <= 160)) { // a tower: its typical height in this tradition, as a guess
                b["h"] = heights[kind]; b["levels"] = Math.max(1L, PyMath.int(d(b["h"]) / d(floorH))); b["lv_why"] = "its tradition"
            } else {
                val levels = P["levels"] as Map<String, Any?>
                val dflt = levels.getValue("default")
                val lv = levels.getOr(bt, levels.getOr(kind, dflt))
                b["levels"] = lv; b["h"] = add(mul(lv, floorH), or(b["roof_h"], 0)); g["building:levels"] = true; b["lv_why"] = "its tradition"
            }
        }
        val mat = t["building:material"] as String?; val col = t["building:colour"] as String?
        val rmat = t["roof:material"] as String?; val rcol = t["roof:colour"] as String?
        val wl = pick(walls as List<*>, add(mul(seed, 13), 3)) as List<*>
        val wm = wl[0] as String; val wc = wl[1] as String
        val md = or(mat, wm) as String
        // roof: a rule of the tradition (a Paris mansard, a canal house's gable), else a shape from its roofs, with its pitch
        val pRoof = P["roof"] as Map<String, Any?>
        var rf = (or(tradEntry(pRoof, bt, kind, modern, yr), null) ?: era(pRoof["default"], yr)) as Map<String, Any?>
        var rule: Map<String, Any?>? = null
        if (truthy(g["roof:shape"]) && !part) {
            for (r_ in P.getOr("rules", emptyList<Any?>()) as List<Map<String, Any?>>) {
                if (fits(r_["when"] as Map<String, Any?>, b, kind, md, short)) {
                    rule = r_["roof"] as Map<String, Any?>; break
                }
            }
            if (truthy(rule)) {
                val merged = LinkedHashMap(rf)
                for ((k, v) in rule!!) if (k in setOf("material", "colours", "pitch")) merged[k] = v
                rf = merged
            }
            val shape = if (truthy(rule)) rule!!["shape"] else pick(rf["shapes"] as List<*>, add(mul(seed, 7), 1))
            val oldRh = or(b["roof_h"], 0)
            b["roof"] = shape
            val ruleOr = rule ?: emptyMap()
            if (shape in setOf("gabled", "hipped")) {
                val rng = ruleOr.getOr("roof_h_range", listOf(1.4, 7.0)) as List<*>
                val lo = rng[0]; val hi = rng[1]
                b["roof_h"] = round1(pmax(lo, pmin(hi, d(rf.getOr("pitch", 0.5)) * short / 2))); g["roof:height"] = true
                if (truthy(ruleOr["orient"])) {
                    b["orient"] = rule!!["orient"]
                } else if (truthy(rf["terrace_across"]) && short < 8 && longest > 1.4 * short) {
                    b["orient"] = "across"; g["roof:orientation"] = true // ridge along the street
                } else {
                    b["orient"] = or(b["orient"], "along")
                }
            } else if (shape == "skillion") {
                b["roof_h"] = round1(pmax(0.8, pmin(3.0, d(rf.getOr("pitch", 0.3)) * short * 0.5))); g["roof:height"] = true; g["roof:direction"] = true
            } else if (shape == "mansard") {
                val rl = Real.num(t["roof:levels"])
                b["roof_h"] = if (rl != null && rl != 0.0) rl * d(floorH) else ruleOr.getOr("roof_h", 3.4); g["roof:height"] = true
            } else {
                b["roof_h"] = null
            }
            val rh = or(b["roof_h"], 0)
            if (b["src"] == "height") {
                if (!truthy(t["building:levels"])) b["levels"] = Math.max(1L, PyMath.round(d(sub(b["h"], rh)) / d(floorH)))
            } else {
                b["h"] = if (truthy(g["height"]) || b["src"] == "levels") add(mul(b["levels"], floorH), rh) else add(sub(b["h"], oldRh), rh)
            }
        }
        val rm = rf["material"]; val rc = PyRandom(add(mul(seed, 17), 5)).choice(rf["colours"] as List<*>) as String
        b["kind"] = kind
        b["mat_draw"] = md
        if (!truthy(mat)) g["building:material"] = true
        if (truthy(col)) {
            b["col"] = col; val ch = paint(col); b["col_draw"] = or(ch, or(MAT_DRAW.at(mat), MAT_COL[or(mat, wm) as String] ?: wc))
        } else if (truthy(mat)) {
            b["col"] = or(MAT_DRAW.at(mat), MAT_COL.at(mat) ?: wc); b["col_draw"] = b["col"]; g["building:colour"] = true
        } else {
            b["col"] = wc; b["col_draw"] = jitter(wc, b["id"], 0.035); g["building:colour"] = true
        }
        b["rmat_draw"] = or(rmat, rm)
        if (!truthy(rmat)) g["roof:material"] = true
        if (truthy(rcol)) {
            b["rcol"] = rcol; val ch = colourHex(rcol); b["rcol_draw"] = or(ch, or(MAT_DRAW.at(rmat), RMAT_COL[or(rmat, rm) as String] ?: rc))
        } else if (truthy(rmat)) {
            b["rcol"] = or(MAT_DRAW.at(rmat), RMAT_COL.at(rmat) ?: rc); b["rcol_draw"] = b["rcol"]; g["roof:colour"] = true
        } else {
            b["rcol"] = rc; b["rcol_draw"] = jitter(rc, add(b["id"], 7), 0.05); g["roof:colour"] = true
        }
        // the tradition's details, generic (no tag gives them): windows, shutters, eaves, boards, a stucco ground floor, a cornice, chimneys
        val rnd = PyRandom(add(mul(seed, 31), 11))
        val W_ = P["windows"] as Map<String, Any?>?; val Sh = P["shutters"] as Map<String, Any?>?; val Bd = P["boards"] as Map<String, Any?>?
        val BB = P["base_band"] as Map<String, Any?>?; val Ch = P["chimneys"] as Map<String, Any?>?
        val homeish = kind in SAYS_KIND || bt in setOf("apartments", "house", "terrace", "residential", "detached", "semidetached_house")
        // a detail of old buildings only: {"before": 1800} (unknown age counts as not old), or simply true
        fun before(v: Any?): Boolean = if (v !is Map<*, *>) truthy(v) else (yrT(yr) && lt(yr, v["before"]))
        val win = if (truthy(W_) && !modern && (kind in (W_!!["kinds"] as List<*>) || bt in (W_["kinds"] as List<*>) || "default" in (W_["kinds"] as List<*>)) &&
            kind !in INDUSTRIAL_LIKE && ("materials" !in W_ || md in (W_["materials"] as List<*>))
        ) W_ else null
        val trad: Dict = LinkedHashMap()
        trad["win"] = win
        trad["shutter"] = if (truthy(Sh) && !modern && kind in (Sh!!["kinds"] as List<*>) && rnd.random() < d(Sh["share"])) rnd.choice(Sh["colours"] as List<*>) else null
        trad["eaves"] = if (b["roof"] in setOf("gabled", "hipped")) P.getOr("eaves", 0) else 0
        trad["boards"] = if (truthy(Bd) && md in (Bd!!["materials"] as List<*>)) Bd else null
        trad["band"] = if (truthy(BB) && md in (BB!!["materials"] as List<*>) && !modern && homeish) BB else null
        trad["cornice"] = before(P["cornice"]) && !modern && homeish
        trad["hoist"] = before(P["hoist"]) && b["roof"] == "gabled"
        trad["chimney"] = if (truthy(Ch) && !part && homeish && !modern && rnd.random() < d(Ch!!["share"])) Ch else null
        trad["crenel"] = truthy(P["towers_crenellated"]) && kind == "tower"
        b["trad"] = trad
        b["no_win"] = kind in NO_WIN || md == "glass"
        val label = if (homeish) (P.getOr("says_by", emptyMap<String, Any?>()) as Map<String, Any?>).getOr(md, P["says"]) else "${kindLabel(kind)} in the ${P["name"]} tradition"
        b["look_why"] = if (LOOK_KEYS.any { truthy(g[it]) }) "$label · $why" else null
        if (truthy(rule) && b["roof"] == "mansard") b["roof_why"] = "mansard"
        return true
    }

    private val LOOK_KEYS = listOf("building:material", "building:colour", "roof:material", "roof:colour", "roof:shape")

    // render/generic.py:345 party_walls
    /** Walls shared with a neighbour (a terrace's, a Paris block's): edges lying along another building's edge. Chimney stacks stand on them. */
    fun partyWalls(S: Map<String, Any?>, tol: Double = 0.8) {
        val cell = 12.0
        class Edge(val k: Int, val a: V2, val c: V2)
        val grid = GridMap<ArrayList<Edge>>()
        fun key(i: Long, j: Long): Long = GridMap.key(i, j)
        @Suppress("UNCHECKED_CAST")
        val bs = (S["buildings"] as List<Dict>).filter { !truthy(it["hidden"]) }
        fun dist(a: V2, c: V2): Double = PyMath.dist(doubleArrayOf(a.x, a.y), doubleArrayOf(c.x, c.y))
        for ((k, b) in bs.withIndex()) {
            val p = polyOf(b)
            for (i in p.indices) {
                val a = p[i]; val c = p[(i + 1) % p.size]
                if (dist(a, c) < 1.5) continue
                val m0 = (a[0] + c[0]) / 2; val m1 = (a[1] + c[1]) / 2
                grid.getOrPut(key(PyMath.int(PyMath.floorDiv(m0, cell)), PyMath.int(PyMath.floorDiv(m1, cell)))) { ArrayList() }.add(Edge(k, a, c))
            }
        }
        for ((k, b) in bs.withIndex()) {
            val p = polyOf(b); val out = ArrayList<Pair<V2, V2>>()
            for (i in p.indices) {
                val a = p[i]; val c = p[(i + 1) % p.size]; val L = dist(a, c)
                if (L < 1.5) continue
                val m0 = (a[0] + c[0]) / 2; val m1 = (a[1] + c[1]) / 2; val ux = (c[0] - a[0]) / L; val uz = (c[1] - a[1]) / L
                val gx = PyMath.int(PyMath.floorDiv(m0, cell)); val gz = PyMath.int(PyMath.floorDiv(m1, cell)); var hit = false
                search@ for (dx in -1..1) {
                    for (dz in -1..1) {
                        for (e in grid[key(gx + dx, gz + dz)] ?: continue) {
                            if (e.k == k) continue
                            val L2 = dist(e.a, e.c); val vx = (e.c[0] - e.a[0]) / L2; val vz = (e.c[1] - e.a[1]) / L2
                            if (Math.abs(ux * vz - uz * vx) > 0.15) continue
                            val tt = (m0 - e.a[0]) * vx + (m1 - e.a[1]) * vz
                            if (-0.5 < tt && tt < L2 + 0.5 && Math.abs((m0 - e.a[0]) * vz - (m1 - e.a[1]) * vx) < tol) {
                                hit = true; break@search
                            }
                        }
                    }
                }
                if (hit) out.add(a to c)
            }
            b["party"] = out
        }
    }

    // render/generic.py:375 apply
    /**
     * Fills every building's missing look from its own tags; what was guessed, and why, is kept for the tap panel.
     * [keepHomes]: homes keep the varied brick and plaster already guessed for them (Amsterdam's canal houses) and only gain a roof material.
     */
    @Suppress("UNCHECKED_CAST")
    fun apply(S: Map<String, Any?>, region: String?, landuseAt: ((Dict) -> String?)? = null, keepHomes: Boolean = false) {
        val P = tradition(S["tradition"] as String?)
        if (region == "paris" || truthy(P)) neighbourLevels(S)
        if (truthy(P) && truthy(P!!["chimneys"]) && (P["chimneys"] as Map<String, Any?>)["kind"] == "party") partyWalls(S)
        for (b in (S["buildings"] as List<Dict>) + (S["parts"] as List<Dict>)) {
            val t = tagsOf(b)
            val g = (if ("guessed" in b) b["guessed"] else LinkedHashMap<String, Any?>().also { b["guessed"] = it }) as Dict
            if (truthy(P) && t["building"] == "houseboat") continue // a houseboat keeps its own generic look (drawn as a boat)
            if (keepHomes && !(truthy(t["building:material"]) || truthy(t["building:colour"]))) {
                val lu = landuseAt?.invoke(b)
                var (kind, why) = kindOf(t, lu, region, absArea(polyOf(b)), b["h"])
                val townhouse = kind in setOf("office", "retail", "hotel", "school") && (lt(or(b["year"], 9999), 1900) || absArea(polyOf(b)) < 300)
                if (townhouse) why = "an old town house, $why" // a canal house used as a shop or office keeps the canal houses' brick
                if (kind == "home" || townhouse) {
                    b["kind"] = kind
                    if (!truthy(t["roof:material"])) {
                        b["rmat_draw"] = if (b["roof"] in SLOPED) "roof_tiles" else "tar_paper"; g["roof:material"] = true
                    } else {
                        b["rmat_draw"] = t["roof:material"]
                    }
                    b["look_why"] = "${kindLabel(kind)} · $why"
                    if (b["roof"] == "gabled" && truthy(g["roof:colour"]) && PyRandom(add(mul(b["id"], 31), 5)).random() < RED_TILES) {
                        val rc = ROOF.getValue("tiles_red").second // the canal ring pack's red
                        b["rcol"] = rc; b["rcol_draw"] = jitter(rc, add(b["id"], 7), 0.06)
                    }
                    continue
                }
            }
            for (k in listOf("building:colour", "building:material", "roof:colour")) g.remove(k) // this replaces the region-wide palette guess
            val lu = landuseAt?.invoke(b)
            if (truthy(P)) {
                val (kind, why) = kindOf(t, lu, region, absArea(polyOf(b)), b["h"])
                if (tradLook(b, P!!, kind, why, g, region)) continue
            }
            val lk = look(b, region, lu)
            var roof = lk.roof; val kind = lk.kind; val why = lk.why
            val wm = WALL.getValue(lk.wall).first; var wc = WALL.getValue(lk.wall).second
            var rm = ROOF.getValue(roof).first; var rc = ROOF.getValue(roof).second
            if (region == "paris") {
                if (lk.wall == "limestone") wc = STONE_FR[Math.floorMod(long(b.getOr("parent_id", b["id"])), STONE_FR.size.toLong()).toInt()]
                if (parisRoof(b, kind, lk.wall, g)) {
                    roof = "zinc"; rm = ROOF.getValue(roof).first; rc = ROOF.getValue(roof).second
                }
            }
            val mat = t["building:material"] as String?; val col = t["building:colour"] as String?
            val rmat = t["roof:material"] as String?; val rcol = t["roof:colour"] as String?
            b["kind"] = kind
            b["mat_draw"] = or(mat, wm)
            if (!truthy(mat)) g["building:material"] = true
            if (truthy(col)) { // a mapped colour is drawn exactly as mapped
                b["col"] = col; val ch = colourHex(col)
                b["col_draw"] = or(ch, or(MAT_DRAW.at(mat), MAT_COL[or(mat, wm) as String] ?: wc))
            } else if (truthy(mat)) { // a mapped material, colour not mapped: drawn as the renderer always drew that material
                b["col"] = or(MAT_DRAW.at(mat), MAT_COL.at(mat) ?: wc); b["col_draw"] = b["col"]; g["building:colour"] = true
            } else {
                b["col"] = wc; b["col_draw"] = jitter(wc, b["id"]); g["building:colour"] = true
            }
            b["rmat_draw"] = or(rmat, rm)
            if (!truthy(rmat)) g["roof:material"] = true
            if (truthy(rcol)) {
                b["rcol"] = rcol; val ch = colourHex(rcol)
                b["rcol_draw"] = or(ch, or(MAT_DRAW.at(rmat), RMAT_COL[or(rmat, rm) as String] ?: rc))
            } else if (truthy(rmat)) {
                b["rcol"] = or(MAT_DRAW.at(rmat), RMAT_COL.at(rmat) ?: rc); b["rcol_draw"] = b["rcol"]; g["roof:colour"] = true
            } else {
                b["rcol"] = rc; b["rcol_draw"] = jitter(rc, add(b["id"], 7), 0.06); g["roof:colour"] = true
            }
            b["no_win"] = kind in NO_WIN || b["mat_draw"] == "glass" // halls, tanks and sheds show no window rows; glass walls are drawn as curtain walls
            b["look_why"] = if (truthy(g["building:material"]) || truthy(g["building:colour"]) || truthy(g["roof:material"]) || truthy(g["roof:colour"])) "${kindLabel(kind)} · $why" else null
        }
    }

    private val KIND_LABEL: Map<String, String> = linkedMapOf(
        "home" to "a home", "worship" to "a place of worship", "monument" to "a historic building", "office" to "an office or public building", "retail" to "a shop building",
        "school" to "a school", "hospital" to "a hospital", "hotel" to "a hotel", "industrial" to "an industrial hall", "hangar" to "a hangar", "terminal" to "a terminal",
        "tower" to "a tower", "tank" to "a storage tank", "parking" to "a parking garage", "greenhouse" to "a greenhouse", "bunker" to "a bunker", "canopy" to "a canopy",
        "kiosk" to "a kiosk", "caravan" to "a static caravan", "shed" to "a shed or garage", "utility" to "a service building", "sports" to "a sports hall",
    )

    // render/generic.py:431 kind_label
    fun kindLabel(k: String?): String = KIND_LABEL[k] ?: "a building"
}
