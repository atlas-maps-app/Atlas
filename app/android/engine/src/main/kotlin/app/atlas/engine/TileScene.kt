package app.atlas.engine

import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.geo.TileId
import app.atlas.model.OsmId
import app.atlas.model.Place

/**
 * Everything the renderer needs of one tile, built once off the main thread by [SceneBuilder]: geometry in metres
 * relative to the tile's north-west corner ([originX], [originY] in unit Mercator; x east, y south, z up), already
 * triangulated and coloured for its [look].
 */
class TileScene(
    val id: TileId,
    /** The tile whose data it was built from: [id] itself, or an ancestor when overzoomed. */
    val source: TileId,
    val look: Look,
    val originX: Double,
    val originY: Double,
    /** Metres per unit Mercator at the tile's centre: its own scale. */
    val metresPerUnit: Double,
    /** The tile's side in metres. */
    val size: Float,
    /** The ground in draw order: land, water, quays, rails, roads, bridges. */
    val ground: FaceMesh,
    /** Cast shadows: opaque triangles, one face per caster, drawn as one translucent layer. */
    val shadows: FaceMesh,
    val objects: ObjectSet,
    val trees: TreeSet,
    val labels: List<LabelCandidate>,
    val pois: List<PoiCandidate>,
    val buildings: List<BuildingInfo>,
    val stats: SceneStats,
    /** Where wind streaks catch the light on open water. */
    val glints: GlintSet = GlintSet.EMPTY,
) {
    /** Unit Mercator of a scene point. */
    fun unitX(x: Float): Double = originX + x / metresPerUnit
    fun unitY(y: Float): Double = originY + y / metresPerUnit

    fun latLon(x: Float, y: Float): LatLon = LatLon(Mercator.lat(unitY(y)), Mercator.lon(unitX(x)))

    /** Roughly how much memory the scene holds, in bytes: for a scene cache's budget. */
    val bytes: Long by lazy {
        var b = ground.bytes + shadows.bytes + objects.mesh.bytes + 4L * (objects.faceStart.size + objects.centre.size + objects.radius.size + objects.ref.size)
        b += trees.count * 33L + glints.count * 16L
        for (l in labels) b += 96L + 2L * l.text.length + 4L * (l.line?.size ?: 0)
        b += pois.size * 96L
        for (i in buildings) b += 128L + 4L * i.footprint.size
        b
    }

    override fun toString() = "TileScene($id from $source, ${stats})"
}

/** Standing objects (buildings) as faces grouped per object, with a sort point and bounding sphere each. */
class ObjectSet(
    val mesh: FaceMesh,
    val count: Int,
    /** First face of each object; `count + 1` entries. */
    val faceStart: IntArray,
    /** Sort point of each object (centroid at half height), x, y, z. */
    val centre: FloatArray,
    val radius: FloatArray,
    /** Index into [TileScene.buildings]. */
    val ref: IntArray,
) {
    companion object {
        val EMPTY = ObjectSet(FaceMesh.EMPTY, 0, IntArray(1), FloatArray(0), FloatArray(0), IntArray(0))
    }
}

/**
 * Wind streaks on open water: seeds anchored to the world at the water's level ([z]), each drawn per frame as a soft
 * light streak lying across the screen, as ripples catch the sky. A streak reaches at most [reach] metres either side
 * of its seed (so it never leaves the water or crosses a bridge); [size] (0..1) picks its length and thickness.
 */
class GlintSet(val count: Int, val x: FloatArray, val y: FloatArray, val z: Float, val reach: FloatArray, val size: FloatArray) {
    companion object {
        val EMPTY = GlintSet(0, FloatArray(0), FloatArray(0), 0f, FloatArray(0), FloatArray(0))
    }
}

/** Trees: drawn per frame as round crowns facing the camera, shaded toward the sun. */
class TreeSet(
    val count: Int,
    val x: FloatArray,
    val y: FloatArray,
    /** Ground height under the tree. */
    val z: FloatArray,
    val height: FloatArray,
    /** Crown radius in metres. */
    val radius: FloatArray,
    val dark: IntArray,
    val mid: IntArray,
    val light: IntArray,
    val trunk: Int,
    /** True for trees that come from OSM (natural=tree), false for the ones planted to fill a wood or park. */
    val mapped: BooleanArray,
    /** One tone for a crown only a few pixels across, between [dark] and [mid]. */
    val far: IntArray = mid,
) {
    companion object {
        val EMPTY = TreeSet(0, FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), IntArray(0), IntArray(0), IntArray(0), 0, BooleanArray(0))
    }
}

/** Typefaces labels use; the view maps them to real fonts. */
enum class LabelStyle { SERIF_ITALIC, SANS, SANS_MEDIUM }

/** What a label names. */
enum class LabelKind { PLACE, WATER, ROAD, NUMBER }

/**
 * A label that may be shown: at a point ([x], [y], [z] in scene metres) or along a [line] (x, y pairs). [size] is in
 * density-independent pixels. Lower [priority] wins collisions. Shown between [minZoom] and [maxZoom].
 */
class LabelCandidate(
    val text: String,
    val kind: LabelKind,
    val style: LabelStyle,
    val size: Float,
    val colour: Int,
    val halo: Int,
    val priority: Float,
    val minZoom: Float,
    val maxZoom: Float,
    val x: Float,
    val y: Float,
    val z: Float = 0f,
    val line: FloatArray? = null,
    /** Extra space between letters, as a fraction of the size. */
    val letterSpacing: Float = 0f,
    /** Stable across frames and scenes, for fading. */
    val key: Long,
)

/** A point of interest from the tile's `poi` layer, for the view's symbols. */
class PoiCandidate(
    val x: Float,
    val y: Float,
    val cls: String,
    val subclass: String,
    val name: String?,
    val rank: Int,
    val osmId: OsmId?,
    val featureId: Long,
)

/** What a tap on a building can tell: its ids, heights, roof, colours and which of them were guessed. */
class BuildingInfo(
    val featureId: Long,
    val osmId: OsmId?,
    val height: Float,
    val minHeight: Float,
    val roof: RoofShape,
    val kind: String,
    val wallColour: Int,
    val roofColour: Int,
    val guessed: Set<String>,
    val why: String,
    /** Outer ring in scene metres, x, y pairs. */
    val footprint: FloatArray,
)

/** What went into a scene, for benchmarks and the debug overlay. */
data class SceneStats(
    val buildings: Int,
    val groundVertices: Int,
    val objectVertices: Int,
    val shadowVertices: Int,
    val trees: Int,
    val labels: Int,
    val pois: Int,
    val buildMillis: Double,
) {
    override fun toString() =
        "buildings=$buildings ground=$groundVertices objects=$objectVertices shadows=$shadowVertices trees=$trees labels=$labels pois=$pois " +
            "in ${"%.1f".format(buildMillis)} ms"
}

/**
 * What the app adds to the tiles: real trees from its place store (`natural=tree` places with `height`,
 * `diameter_crown`, `leaf_type`, `genus`) and tree rows (`natural=tree_row` lines).
 */
class Extras(
    val trees: List<Place> = emptyList(),
    val treeRows: List<TreeRow> = emptyList(),
)

/** A `natural=tree_row`: trees every [spacing] metres along [line]. */
class TreeRow(val line: List<LatLon>, val tags: Map<String, String> = emptyMap(), val spacing: Double = 9.0)
