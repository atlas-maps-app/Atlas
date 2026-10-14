package app.atlas.engine

import app.atlas.tiles.GeomType
import app.atlas.tiles.TileFeature
import app.atlas.tiles.VectorTile
import app.atlas.tiles.clip

/**
 * Label and point-of-interest candidates of a scene: place names by rank (serif italic, like "Jordaan"), water names,
 * street names along their lines, house numbers close up, and the `poi` layer for the view's symbols. Points are kept
 * only inside the scene's own square, lines are cut to it, so neighbouring tiles never repeat a label.
 */
internal class LabelReader(
    private val frame: TileFrame,
    private val theme: Theme,
    /** The scene's water polygons (outer ring, holes), to size lake names by. */
    private val water: List<List<DoubleArray>> = emptyList(),
) {
    val labels = ArrayList<LabelCandidate>()
    val pois = ArrayList<PoiCandidate>()
    private val size = frame.size.toFloat()

    fun read(tile: VectorTile) {
        places(tile)
        waterNames(tile)
        streets(tile)
        if (frame.zoom >= 18) numbers(tile)
        if (frame.zoom >= 15) pois(tile)
        labels.sortBy { it.priority }
    }

    private inline fun points(tile: VectorTile, layerName: String, block: (TileFeature, Float, Float) -> Unit) {
        val layer = tile[layerName] ?: return
        for (f in layer.features) {
            if (f.type != GeomType.POINT) continue
            val g = f.geometry
            for (i in 0 until g.pointCount) {
                val x = frame.x(g.x(i).toDouble(), layer.extent).toFloat()
                val y = frame.y(g.y(i).toDouble(), layer.extent).toFloat()
                if (x < 0f || y < 0f || x >= size || y >= size) continue
                block(f, x, y)
            }
        }
    }

    private fun name(f: TileFeature): String? = (f.string("name") ?: f.string("name:latin"))?.trim()?.takeIf { it.isNotEmpty() }

    private fun key(text: String, kind: LabelKind, x: Float, y: Float): Long {
        val ux = frame.id.unitX + x / frame.metresPerUnit
        val uy = frame.id.unitY + y / frame.metresPerUnit
        return Hash.mix(text.hashCode().toLong() * 31 + kind.ordinal) xor Hash.ofPoint(Math.floor(ux * (1 shl 18)), Math.floor(uy * (1 shl 18)), 1)
    }

    /**
     * The key of a name along lines: the same in every scene and at every zoom, so a street keeps its fade when the view
     * crosses into other tiles or another zoom level. Its pieces, and other streets of its name, share one fade.
     */
    private fun lineKey(text: String, kind: LabelKind): Long = Hash.mix(text.hashCode().toLong() * 31 + kind.ordinal) xor LINE_SALT

    private fun places(tile: VectorTile) {
        points(tile, "place") { f, x, y ->
            val text = name(f) ?: return@points
            val rank = (f.int("rank") ?: 10).toFloat()
            val capital = f.int("capital")
            val spec = when (f.string("class")) {
                "continent" -> Spec(LabelStyle.SANS_MEDIUM, 15f, 0f, 0f, 3f)
                "country" -> Spec(LabelStyle.SANS_MEDIUM, 15f, 1f, 2f, 7f)
                "state", "province" -> Spec(LabelStyle.SANS, 12.5f, 3f, 5f, 9f)
                "city" -> Spec(LabelStyle.SERIF_ITALIC, if (capital == 2) 20f else 18f, 4f, 5f, 14.6f)
                "town" -> Spec(LabelStyle.SERIF_ITALIC, 15.5f, 6f, 8.5f, 15.5f)
                "village" -> Spec(LabelStyle.SERIF_ITALIC, 14f, 8f, 11f, 16.5f)
                "hamlet" -> Spec(LabelStyle.SERIF_ITALIC, 12.5f, 11f, 13f, 17.5f)
                "borough", "suburb" -> Spec(LabelStyle.SERIF_ITALIC, 15f, 9f, 11.5f, 15.6f)
                "quarter" -> Spec(LabelStyle.SERIF_ITALIC, 15f, 12f, 13.6f, 17.6f)
                "neighbourhood" -> Spec(LabelStyle.SERIF_ITALIC, 13f, 14f, 15.2f, 18.6f)
                "island" -> Spec(LabelStyle.SERIF_ITALIC, 13f, 13f, 12.5f, 17.6f)
                else -> null
            } ?: return@points
            labels += LabelCandidate(
                text, LabelKind.PLACE, spec.style, spec.size, theme.placeLabel, theme.halo, spec.priority + rank / 100f,
                spec.minZoom, spec.maxZoom, x, y, 0f, null, 0.02f, key(text, LabelKind.PLACE, x, y),
            )
        }
    }

    private class Spec(val style: LabelStyle, val size: Float, val priority: Float, val minZoom: Float, val maxZoom: Float)

    private fun waterNames(tile: VectorTile) {
        points(tile, "water_name") { f, x, y ->
            val text = name(f) ?: return@points
            val (size, prio, minZ) = when (f.string("class")) {
                "ocean" -> Triple(19f, 2f, 0f)
                "sea" -> Triple(17f, 3f, 3f)
                "bay", "strait" -> Triple(15f, 6f, 8f)
                else -> Triple(15f, 10f, lakeZoom(x, y, text, 15f, 12.5f))
            }
            labels += LabelCandidate(
                text, LabelKind.WATER, LabelStyle.SERIF_ITALIC, size, theme.waterLabel, theme.halo, prio + (if (text.length <= 3) -0.5f else 0f),
                minZ, 24f, x, y, GroundBuilder.WATER_Z, null, 0.04f, key(text, LabelKind.WATER, x, y),
            )
        }
        // Lakes and bays far out come as lines across them, for names that follow their shape.
        lines(tile, "water_name", minPartMetres = 120.0) { f, xy ->
            val text = name(f) ?: return@lines
            val (size, prio, minZ) = when (f.string("class")) {
                "ocean", "sea" -> Triple(17f, 3f, 0f)
                "bay", "strait" -> Triple(15f, 6f, 6f)
                else -> Triple(14.5f, 9f, 8f)
            }
            labels += LabelCandidate(
                text, LabelKind.WATER, LabelStyle.SERIF_ITALIC, size, theme.waterLabel, theme.halo, prio, minZ, 24f,
                xy[0], xy[1], GroundBuilder.WATER_Z, xy, 0.06f, lineKey(text, LabelKind.WATER),
            )
        }
        lines(tile, "waterway", minPartMetres = 60.0) { f, xy ->
            val text = name(f) ?: return@lines
            if (f.string("brunnel") == "tunnel") return@lines
            val (minZ, size) = if (f.string("class") == "river") 14f to 13f else 16.9f to 12f
            labels += LabelCandidate(
                text, LabelKind.WATER, LabelStyle.SERIF_ITALIC, size, theme.waterLabel, theme.halo, 16f, minZ, 24f,
                xy[0], xy[1], GroundBuilder.WATER_Z, xy, 0.06f, lineKey(text, LabelKind.WATER),
            )
        }
    }

    /**
     * The zoom from which a lake's name fits its water: the side of the polygon holding the label at least
     * [LAKE_FIT] times the name's width (and [LAKE_MIN_DP] wide), never before [default]. A pond or a fountain in a
     * square keeps its name for the closest views; without a polygon here, [default] stands.
     */
    private fun lakeZoom(x: Float, y: Float, text: String, size: Float, default: Float): Float {
        var area = 0.0
        for (poly in water) {
            if (!Rings.contains(poly, x.toDouble(), y.toDouble())) continue
            var a = kotlin.math.abs(Rings.area(poly[0]))
            for (k in 1 until poly.size) a -= kotlin.math.abs(Rings.area(poly[k]))
            area = kotlin.math.max(area, a)
        }
        if (area <= 0.0) return default
        val sideDp = kotlin.math.sqrt(area) / frame.metresPerDp
        val needDp = kotlin.math.max(LAKE_MIN_DP, LAKE_FIT * ApproximateMeasurer.width(text, LabelStyle.SERIF_ITALIC, size))
        val z = frame.zoom + kotlin.math.log2(needDp / sideDp)
        return kotlin.math.max(default, z.toFloat())
    }

    private fun streets(tile: VectorTile) {
        lines(tile, "transportation_name", minPartMetres = 30.0) { f, xy ->
            val text = name(f) ?: f.string("ref") ?: return@lines
            val cls = f.string("class") ?: return@lines
            val (minZ, size, prio) = when (cls) {
                "motorway", "trunk" -> Triple(13.5f, 12f, 20f)
                "primary" -> Triple(15.5f, 12f, 21f)
                "secondary", "tertiary" -> Triple(16.6f, 11.5f, 22f)
                "minor", "busway" -> Triple(17.2f, 11f, 23f)
                "service", "track" -> Triple(17.8f, 10.5f, 25f)
                "path" -> Triple(18.2f, 10.5f, 26f)
                else -> return@lines
            }
            labels += LabelCandidate(
                text, LabelKind.ROAD, LabelStyle.SANS, size, theme.roadLabel, theme.halo, prio, minZ, 24f,
                xy[0], xy[1], 0.1f, xy, 0f, lineKey(text, LabelKind.ROAD),
            )
        }
    }

    private fun numbers(tile: VectorTile) {
        points(tile, "housenumber") { f, x, y ->
            val text = f.string("housenumber")?.split(';')?.let { if (it.size > 1) "${it.first()}–${it.last()}" else it[0] } ?: return@points
            labels += LabelCandidate(
                text, LabelKind.NUMBER, LabelStyle.SANS, 9.5f, theme.numberLabel, theme.halo, 40f, 18.4f, 24f, x, y, 0f, null, 0f,
                key(text, LabelKind.NUMBER, x, y),
            )
        }
    }

    private fun pois(tile: VectorTile) {
        val maxRank = when {
            frame.zoom >= 18 -> Int.MAX_VALUE
            frame.zoom >= 17 -> 8
            frame.zoom >= 16 -> 3
            else -> 1
        }
        points(tile, "poi") { f, x, y ->
            val cls = f.string("class") ?: return@points
            if (cls in NOT_PLACES) return@points
            val rank = f.int("rank") ?: 99
            if (rank > maxRank) return@points
            if ((f.int("layer") ?: 0) < 0 || f.int("indoor") == 1) return@points
            pois += PoiCandidate(x, y, cls, f.string("subclass") ?: cls, name(f), rank, TileFrame.osmId(f.id), f.id)
        }
        pois.sortBy { it.rank }
    }

    /** Named lines cut to the scene square, each piece long enough to carry a label. */
    private inline fun lines(tile: VectorTile, layerName: String, minPartMetres: Double, block: (TileFeature, FloatArray) -> Unit) {
        val layer = tile[layerName] ?: return
        val rect = frame.sourceRect(layer.extent, 0.0)
        for (f in layer.features) {
            if (f.type != GeomType.LINE) continue
            if (f.string("name") == null && f.string("ref") == null) continue
            val g = f.geometry.clip(rect[0], rect[1], rect[2], rect[3])
            for (p in 0 until g.partCount) {
                val xy = frame.line(g, p, layer.extent)
                if (Ribbon.length(xy, xy.size / 2) < minPartMetres) continue
                block(f, xy)
            }
        }
    }

    companion object {
        /** A lake's name shows once its water is this many times the name's width across, and at least [LAKE_MIN_DP]. */
        const val LAKE_FIT = 1.1
        const val LAKE_MIN_DP = 60.0

        private const val LINE_SALT = 0x11E5_A17L

        /** OpenMapTiles poi classes that are street furniture, not places. */
        val NOT_PLACES = setOf(
            "waste_basket", "bollard", "gate", "lift_gate", "cycle_barrier", "block", "kissing_gate", "stile", "recycling",
            "bench", "vending_machine", "border_control", "toll_booth", "sally_port", "swing_gate", "turnstile",
        )
    }
}
