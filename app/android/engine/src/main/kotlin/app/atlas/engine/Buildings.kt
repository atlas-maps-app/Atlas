package app.atlas.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Roof shapes the engine draws (OSM `roof:shape` values). */
enum class RoofShape(val tag: String) {
    FLAT("flat"), GABLED("gabled"), HIPPED("hipped"), PYRAMIDAL("pyramidal"), MANSARD("mansard"), SKILLION("skillion");

    val sloped: Boolean get() = this != FLAT

    companion object {
        fun of(tag: String?): RoofShape? = when (tag) {
            "flat" -> FLAT
            "gabled", "gambrel", "round", "saltbox" -> GABLED
            "hipped", "half-hipped", "quadruple_saltbox" -> HIPPED
            "pyramidal", "dome", "onion", "cone" -> PYRAMIDAL
            "mansard" -> MANSARD
            "skillion" -> SKILLION
            else -> null
        }
    }
}

/** A building as it comes from the tile: its footprint in metres and the few values vector tiles carry. */
class RawBuilding(
    /** Outer ring first (positive area), then holes; no closing points. */
    val rings: List<DoubleArray>,
    /** Per ring, per edge: true where the edge is a tile cut, not a wall. Null when no edge is. */
    val cut: List<BooleanArray>?,
    val height: Double?,
    val minHeight: Double?,
    val colour: String?,
    val featureId: Long,
    /** A kind from what stands inside or around it (a church's POI, the land use), when known. */
    val kindHint: String?,
    /** Stable per building whichever scene reads it: from its source tile and first vertex in tile units. */
    val seed: Long,
)

/**
 * A building ready to draw: footprint, heights, roof and colours, every value either mapped or guessed the way the
 * place's [Tradition] says. [guessed] names the OSM tags that were guessed, for the "why does it look like this" panel.
 */
class Building(
    val rings: List<DoubleArray>,
    val cut: List<BooleanArray>?,
    val centroidX: Double,
    val centroidY: Double,
    val area: Double,
    val height: Double,
    val minHeight: Double,
    val roof: RoofShape,
    val roofHeight: Double,
    /** Unit vector of the ridge (or the skillion's downhill direction). */
    val axisX: Double,
    val axisY: Double,
    val wallColour: Int,
    val roofColour: Int,
    val wallMaterial: String,
    val roofMaterial: String,
    val kind: String,
    val year: Int?,
    val levels: Int,
    val cornice: Boolean,
    val hoist: Boolean,
    val chimney: ChimneyStyle?,
    val windows: WindowStyle?,
    val seed: Long,
    val featureId: Long,
    val guessed: Set<String>,
    val why: String,
) {
    val wallTop: Double get() = height - roofHeight
    val outer: DoubleArray get() = rings[0]
}

/** Guesses what OSM leaves out of a [RawBuilding], from the tradition of its place and the theme's palettes. */
class BuildingGuesser(private val tradition: Tradition, private val theme: Theme) {
    /** A height of exactly this with no minimum is the tiles' stand-in for "not mapped". */
    private val defaultTileHeight = 5.0

    fun resolve(raw: RawBuilding): Building? {
        val outer = raw.rings[0]
        if (outer.size < 6) return null
        var area = abs(Rings.area(outer))
        for (k in 1 until raw.rings.size) area -= abs(Rings.area(raw.rings[k]))
        if (area < (if (raw.cut != null) SceneBuilder.MIN_PIECE else SceneBuilder.MIN_AREA)) return null
        val c = Rings.centroid(outer)
        val li = Rings.longestEdge(outer)
        val longest = Rings.edgeLength(outer, li)
        val n = outer.size / 2
        val ax0 = (outer[2 * ((li + 1) % n)] - outer[2 * li]) / longest
        val ay0 = (outer[2 * ((li + 1) % n) + 1] - outer[2 * li + 1]) / longest
        val ext = Rings.extent(outer, c[0], c[1], ax0, ay0)
        val boxLen = ext[1] - ext[0]
        val boxWid = ext[3] - ext[2]
        val rectangularity = if (boxLen * boxWid > 0) area / (boxLen * boxWid) else 0.0
        val short = min(boxWid, min(longest, max(3.0, area / longest)))
        val seed = raw.seed
        val guessed = HashSet<String>()
        val minH = (raw.minHeight ?: 0.0).coerceAtLeast(0.0)
        val part = minH > 0.5
        val heightKnown = raw.height != null && raw.height > minH &&
            !(raw.height == defaultTileHeight && minH == 0.0 && area >= 30.0)
        val h0 = if (heightKnown) raw.height else null

        var kind = raw.kindHint ?: if (area > 2500 && (h0 ?: 10.0) < 20) "industrial" else "home"
        if (!part && area < 160 && (h0 ?: 0.0) > 30) kind = "tower"
        val building = "yes"
        val homeish = kind in HOMEISH

        // Age: in an old town, small narrow rectangular fronts are taken for old houses.
        var year: Int? = null
        if (tradition.oldTownRule && homeish && !part && n <= 10 && rectangularity >= 0.72 && short < 11.5 && area <= 420 &&
            (h0 == null || h0 in 6.0..32.0)
        ) {
            year = PRESUMED_OLD_YEAR
            guessed += "start_date"
        }
        val modern = year != null && year >= tradition.modernFrom

        // Walls.
        val walls = tradition.wallsFor(building, kind, year)
        val wallPick = walls?.let { pick(it, seed * 13 + 3) }
        val wallMaterial = wallPick?.material ?: "brick"
        val mapped = Colours.paint(raw.colour)
        val palette = theme.wallPalette
        val wall = when {
            palette != null -> palette[Hash(seed * 5 + 1).index(palette.size)]
            mapped != null -> mapped
            wallPick != null -> { guessed += "building:colour"; jitter(wallPick.colour, seed, 0.035) }
            else -> { guessed += "building:colour"; Colours.parse("#E4E0D8") }
        }

        // Height and storeys.
        val floor = tradition.floorHeight
        var levels = if (h0 != null) max(1, ((h0 - minH) / floor).roundToInt()) else {
            guessed += "height"; guessed += "building:levels"
            val base = tradition.heights[kind]?.let { max(1, (it / floor).roundToInt()) } ?: tradition.levelsFor(building, kind)
            // Neighbouring houses rarely match: a storey more or less on a third of them.
            val r = Hash(seed * 11 + 5).next()
            if (homeish && base >= 2) base + (if (r < 0.17) -1 else if (r > 0.83) 1 else 0) else base
        }
        if (part && h0 == null) levels = max(1, levels)

        // Roof.
        val rf = tradition.roofFor(building, kind, year) ?: tradition.roofFor(building, "default", year)
        val hForRule = h0 ?: (levels * floor)
        val rule = tradition.rules.firstOrNull { it.fits(kind, building, wallMaterial, year, levels, area, short, hForRule) }
        guessed += "roof:shape"
        var shape = when {
            kind == "tower" && part -> RoofShape.PYRAMIDAL
            rule != null -> RoofShape.of(rule.shape) ?: RoofShape.FLAT
            rf != null -> RoofShape.of(pick(rf.shapes, seed * 7 + 1)) ?: RoofShape.FLAT
            else -> RoofShape.FLAT
        }
        // A spire on the slim top stage of a tower.
        if (part && area < 90 && h0 != null && h0 - minH > 8 && h0 > 30) shape = RoofShape.PYRAMIDAL
        shape = guard(shape, raw, n, rectangularity, short, area)
        val pitch = rule?.pitch ?: rf?.pitch
        var roofH = when (shape) {
            RoofShape.FLAT -> 0.0
            RoofShape.GABLED, RoofShape.HIPPED -> {
                val range = rule?.roofHeightRange ?: 1.4..7.0
                round1(((pitch ?: 0.5) * short / 2).coerceIn(range.start, range.endInclusive))
            }
            RoofShape.MANSARD -> rule?.roofHeight ?: 3.4
            RoofShape.SKILLION -> round1(((pitch ?: 0.3) * short * 0.5).coerceIn(0.8, 3.0))
            RoofShape.PYRAMIDAL -> if (part) min((h0 ?: (minH + 12)) - minH, 3.0 * kotlin.math.sqrt(area)) * 0.55 else min(hForRule * 0.35, 3.5)
        }
        var height = h0 ?: (minH + levels * floor + roofH)
        // Keep at least one storey of wall under the roof.
        val room = height - minH - (if (part) 0.0 else 2.6)
        if (roofH > room) roofH = max(0.0, room)
        if (roofH < 0.8 && shape != RoofShape.PYRAMIDAL) { shape = RoofShape.FLAT; roofH = 0.0 }
        if (height <= minH + 0.5) height = minH + 3.0

        // Roof colour.
        val roofColours = rule?.colours?.takeIf { it.isNotEmpty() } ?: rf?.colours?.takeIf { it.isNotEmpty() }
        val roofMaterial = rule?.material ?: rf?.material ?: "tar_paper"
        val roofPalette = theme.roofPalette
        val roofColour = when {
            roofPalette != null -> roofPalette[Hash(seed * 3 + 2).index(roofPalette.size)]
            shape == RoofShape.FLAT && mapped != null && raw.colour?.startsWith("#") == false -> Colours.mix(mapped, Colours.parse("#7E7A74"), 0.6f)
            roofColours != null -> jitter(roofColours[Hash(seed * 17 + 5).index(roofColours.size)], seed + 7, 0.05)
            else -> jitter(Colours.parse("#7E7A74"), seed + 7, 0.05)
        }.also { guessed += "roof:colour" }

        // Axis: ridges run along the longest wall (or across, for a terrace that says so).
        var axX = ax0
        var axY = ay0
        val across = (rule?.orient == "across") ||
            (rule == null && rf?.terraceAcross == true && short < 8 && longest > 1.4 * short)
        if (across) { val t = axX; axX = -axY; axY = t }
        if (shape == RoofShape.SKILLION) {
            // Downhill toward the outside of the longest wall.
            axX = ay0; axY = -ax0
        }

        val details = !modern && homeish && !part
        val rnd = Hash(seed * 31 + 11)
        val chimney = tradition.chimneys?.takeIf {
            details && (shape == RoofShape.GABLED || shape == RoofShape.HIPPED) && rnd.next() < it.share
        }
        val cornice = details && tradition.corniceBefore != null && year != null && year < tradition.corniceBefore && height - minH > 5
        val hoist = tradition.hoistBefore != null && year != null && year < tradition.hoistBefore && shape == RoofShape.GABLED
        val windows = tradition.windows?.takeIf { w ->
            !modern && (w.kinds == null || kind in w.kinds || "default" in w.kinds) && (w.materials == null || wallMaterial in w.materials)
        }
        val why = buildString {
            append(if (homeish) tradition.name else "$kind in the ${tradition.name} tradition")
            if (year != null && "start_date" in guessed) append(" · its narrow front reads as an old house")
            if (h0 == null) append(" · storeys from the tradition")
        }
        return Building(
            rings = raw.rings, cut = raw.cut, centroidX = c[0], centroidY = c[1], area = area,
            height = height, minHeight = minH, roof = shape, roofHeight = roofH, axisX = axX, axisY = axY,
            wallColour = wall, roofColour = roofColour, wallMaterial = wallMaterial, roofMaterial = roofMaterial,
            kind = kind, year = year, levels = levels, cornice = cornice, hoist = hoist, chimney = chimney, windows = windows,
            seed = seed, featureId = raw.featureId, guessed = guessed, why = why,
        )
    }

    /** Sloped roofs only on footprints they can sit on: compact, near-rectangular, without courtyards or cuts. */
    private fun guard(shape: RoofShape, raw: RawBuilding, n: Int, rectangularity: Double, short: Double, area: Double): RoofShape {
        if (shape == RoofShape.FLAT) return shape
        val cut = raw.cut?.any { e -> e.any { it } } == true
        if (cut || raw.rings.size > 1) return RoofShape.FLAT
        return when (shape) {
            RoofShape.MANSARD -> if (rectangularity >= 0.55 && area in 60.0..4000.0) shape else RoofShape.FLAT
            RoofShape.PYRAMIDAL -> if (n <= 16 && rectangularity >= 0.6 && area <= 900) shape else RoofShape.FLAT
            else -> if (n <= 12 && rectangularity >= 0.7 && short <= 22 && area <= 1400) shape else RoofShape.FLAT
        }
    }

    companion object {
        /** The year presumed for an old-looking house in an old town ("guessed start_date"): before cornices went out of fashion. */
        const val PRESUMED_OLD_YEAR = 1780

        val HOMEISH = setOf("home", "office", "hotel", "retail", "school")

        private fun round1(v: Double) = (v * 10).roundToInt() / 10.0

        /** One of weighted options, the same every time for the same seed. */
        fun pick(options: List<WallOption>, seed: Long): WallOption {
            val total = options.sumOf { it.weight }
            val r = Hash(seed).next() * total
            var acc = 0.0
            for (o in options) {
                acc += o.weight
                if (r <= acc) return o
            }
            return options.last()
        }

        fun pick(shapes: List<Pair<String, Double>>, seed: Long): String {
            val total = shapes.sumOf { it.second }
            val r = Hash(seed).next() * total
            var acc = 0.0
            for ((s, w) in shapes) {
                acc += w
                if (r <= acc) return s
            }
            return shapes.last().first
        }

        /** The guessed colour, a little lighter or darker and warmer or cooler per building. */
        fun jitter(colour: Int, seed: Long, k: Double): Int {
            val r = Hash(seed)
            val f = 1 + r.range(-k, k)
            val w = r.range(-k, k) * 0.4
            return Colours.rgb(
                (Colours.red(colour) * f + 12 * w).toInt(),
                (Colours.green(colour) * f).toInt(),
                (Colours.blue(colour) * f - 8 * w).toInt(),
            )
        }

        /** Kind of a building from the land use under it (OpenMapTiles classes). */
        fun kindOfLanduse(cls: String?): String? = when (cls) {
            "industrial", "railway", "garages", "quarry", "port" -> "industrial"
            "commercial" -> "office"
            "retail" -> "retail"
            "school", "university", "college", "kindergarten", "education" -> "school"
            "hospital" -> "hospital"
            "military" -> "bunker"
            else -> null
        }

        /** Kind of a building from a point of interest inside it (OpenMapTiles `poi` class), for the few that change its look. */
        fun kindOfPoi(cls: String?, subclass: String?): String? = when (cls) {
            "place_of_worship" -> "worship"
            "lodging" -> if (subclass == "hotel") "hotel" else null
            "school", "college" -> "school"
            "hospital" -> if (subclass == "hospital") "hospital" else null
            "museum", "castle" -> "monument"
            "town_hall" -> "office"
            else -> null
        }
    }
}
