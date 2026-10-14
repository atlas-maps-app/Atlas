package app.atlas.render.data

/** The three kinds of OpenStreetMap element, in the order Overpass lists them. */
enum class ElementType(val json: String) {
    NODE("node"), WAY("way"), RELATION("relation");

    companion object {
        fun of(json: String): ElementType = when (json) {
            "node" -> NODE
            "way" -> WAY
            "relation" -> RELATION
            else -> throw PackFormatException("unknown element type '$json'")
        }
    }
}

/**
 * A run of positions (a way's `geometry`, or a relation member's): latitudes and longitudes in degrees. A point the
 * extract left out (Overpass writes `null` outside the query box) is NaN in both arrays, so a loader that skips
 * `None` points of the Python (`if g`) tests [isNull].
 */
class Points(val lat: DoubleArray, val lon: DoubleArray) {
    init {
        require(lat.size == lon.size) { "latitudes and longitudes differ in number" }
    }

    val size: Int get() = lat.size

    fun isNull(i: Int): Boolean = lat[i].isNaN()

    val hasNulls: Boolean get() = lat.any { it.isNaN() }

    override fun equals(other: Any?): Boolean = other is Points && lat.contentEquals(other.lat) && lon.contentEquals(other.lon)

    override fun hashCode(): Int = 31 * lat.contentHashCode() + lon.contentHashCode()

    override fun toString(): String = "Points($size)"
}

/** The box an Overpass way or relation carries as `bounds`. */
data class Bounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

/**
 * A relation member as `out geom` lists it: a way carries its [geometry], a node its position, a relation neither.
 * [lat] and [lon] are NaN when the member has no position of its own.
 */
class Member(
    val type: ElementType,
    val ref: Long,
    val role: String,
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    val geometry: Points? = null,
) {
    val hasPosition: Boolean get() = !lat.isNaN()

    override fun equals(other: Any?): Boolean = other is Member && type == other.type && ref == other.ref && role == other.role &&
        lat.toRawBits() == other.lat.toRawBits() && lon.toRawBits() == other.lon.toRawBits() && geometry == other.geometry

    override fun hashCode(): Int = (type.ordinal * 31 + ref.hashCode()) * 31 + role.hashCode()

    override fun toString(): String = "Member($type $ref $role)"
}

/**
 * One element of an Overpass extract (`out geom`), as the Python engine reads the JSON: the same fields, in the same
 * order, with the same values. A node has [lat] and [lon] (NaN for a way or relation); a way has [bounds], [nodes] and
 * [geometry]; a relation has [bounds] and [members]. A field the extract did not write is null, and [tags] is null for
 * an element without a `tags` key. [tags] keeps the extract's key order.
 */
class OsmElement(
    val type: ElementType,
    val id: Long,
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    val tags: Map<String, String>? = null,
    val bounds: Bounds? = null,
    val nodes: LongArray? = null,
    val geometry: Points? = null,
    val members: List<Member>? = null,
) {
    /** `e.get('tags') or {}` of the Python. */
    val tagsOrEmpty: Map<String, String> get() = tags ?: emptyMap()

    /** The value of tag [key], or null. */
    fun tag(key: String): String? = tags?.get(key)

    /** The extract's own text for this element: Overpass JSON with the keys in the order Overpass writes them. */
    fun toJson(): String = StringBuilder().also { JsonText.element(it, this) }.toString()

    /** Same fields, same values, same key order, same tag order. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OsmElement) return false
        if (type != other.type || id != other.id) return false
        if (lat.toRawBits() != other.lat.toRawBits() || lon.toRawBits() != other.lon.toRawBits()) return false
        if (bounds != other.bounds) return false
        if (!nodes.contentEqualsNullable(other.nodes) || geometry != other.geometry || members != other.members) return false
        val a = tags
        val b = other.tags
        if (a == null || b == null) return a == null && b == null
        if (a.size != b.size) return false
        val ia = a.entries.iterator()
        val ib = b.entries.iterator()
        while (ia.hasNext()) {
            val x = ia.next()
            val y = ib.next()
            if (x.key != y.key || x.value != y.value) return false
        }
        return true
    }

    override fun hashCode(): Int = (type.ordinal * 31 + id.hashCode()) * 31 + (tags?.size ?: -1)

    override fun toString(): String = "${type.json} $id"

    private fun LongArray?.contentEqualsNullable(o: LongArray?): Boolean = if (this == null || o == null) this == null && o == null else contentEquals(o)

    companion object {
        /** `(type rank, id)`: the order Overpass lists elements in. */
        val CANONICAL: Comparator<OsmElement> = compareBy<OsmElement>({ it.type.ordinal }, { it.id })
    }
}

/**
 * Where an engine loader gets its elements: the same list the Python reads from `raw.json`, whatever the container
 * (an Overpass JSON file, a `.atlas` pack, an Overpass answer in memory).
 */
interface ElementSource {
    /** The extract's top-level fields other than `elements` (`version`, `generator`, `osm3s`, and any remark). */
    val header: kotlinx.serialization.json.JsonObject

    /** Every element, in the extract's order. Each call starts again from the first. */
    fun elements(): Sequence<OsmElement>

    /** The number of elements when it is known without reading them. */
    val elementCount: Int? get() = null

    /** The OSM data timestamp (`osm3s.timestamp_osm_base`), or null. */
    val dataTime: String?
        get() = ((header["osm3s"] as? kotlinx.serialization.json.JsonObject)?.get("timestamp_osm_base") as? kotlinx.serialization.json.JsonPrimitive)?.content
}

/** An [ElementSource] over a list already in memory. */
class ListElementSource(
    private val list: List<OsmElement>,
    override val header: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
) : ElementSource {
    override fun elements(): Sequence<OsmElement> = list.asSequence()
    override val elementCount: Int get() = list.size
}
