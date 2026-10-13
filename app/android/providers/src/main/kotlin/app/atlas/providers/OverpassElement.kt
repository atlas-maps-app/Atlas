package app.atlas.providers

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import java.io.Reader

/** One member of a relation as Overpass returns it; [geometry] is filled for `out geom`. */
class OverpassMember(val type: OsmType, val ref: Long, val role: String, val lat: Double = Double.NaN, val lon: Double = Double.NaN, val geometry: DoubleArray? = null)

/**
 * One element of an Overpass JSON answer, in whatever shape the `out` statement asked for:
 * nodes carry [lat]/[lon]; ways and relations carry a [center] (`out center`), [bounds] (`out bb`/`out geom`),
 * [nodes] (`out body`) and [geometry] as interleaved lat, lon pairs (`out geom`; NaN where a node fell outside the clip).
 */
class OverpassElement(
    val type: OsmType,
    val id: Long,
    val tags: Map<String, String>,
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    val center: LatLon? = null,
    val bounds: BBox? = null,
    val nodes: LongArray? = null,
    val geometry: DoubleArray? = null,
    val members: List<OverpassMember>? = null,
    /** With `out meta`: the version number and the time of the last edit (ISO 8601). */
    val version: Int = 0,
    val timestamp: String? = null,
) {
    val osmId: OsmId get() = OsmId(type, id)

    /** The geometry's points, skipping clipped-away ones. */
    fun points(): List<LatLon> {
        val g = geometry ?: return if (lat.isNaN()) emptyList() else listOf(LatLon(lat, lon))
        val out = ArrayList<LatLon>(g.size / 2)
        var i = 0
        while (i < g.size) {
            if (!g[i].isNaN()) out.add(LatLon(g[i], g[i + 1]))
            i += 2
        }
        return out
    }

    /** The box around the element: its bounds, or one computed from its geometry or members. */
    fun box(): BBox? {
        bounds?.let { return it }
        geometry?.let { return boxOf(it) }
        val mem = members ?: return null
        var b: BBox? = null
        for (m in mem) {
            val mb = if (m.geometry != null) boxOf(m.geometry) else if (!m.lat.isNaN()) BBox(m.lon, m.lat, m.lon, m.lat) else null
            if (mb != null) b = b?.union(mb) ?: mb
        }
        return b
    }

    /** Where to pin it: the node itself, else Overpass's centre, else the middle of its box. */
    fun location(): LatLon? = when {
        !lat.isNaN() -> LatLon(lat, lon)
        center != null -> center
        else -> box()?.center
    }

    /** The element as a core [Place], or null if it has no tags or no position. */
    fun toPlace(): Place? {
        if (tags.isEmpty()) return null
        val at = location() ?: return null
        return Place(osmId, at, tags, if (type == OsmType.NODE) null else box())
    }

    private fun boxOf(g: DoubleArray): BBox? {
        var s = Double.POSITIVE_INFINITY
        var w = Double.POSITIVE_INFINITY
        var n = Double.NEGATIVE_INFINITY
        var e = Double.NEGATIVE_INFINITY
        var i = 0
        while (i < g.size) {
            val la = g[i]
            val lo = g[i + 1]
            if (!la.isNaN()) {
                if (la < s) s = la
                if (la > n) n = la
                if (lo < w) w = lo
                if (lo > e) e = lo
            }
            i += 2
        }
        return if (s.isInfinite()) null else BBox(w, s, e, n)
    }
}

/** A way with its tags and line, for checks along a route (height, weight, width limits; bridges; locks). */
data class OsmWay(val id: Long, val tags: Map<String, String>, val points: List<LatLon>, val nodes: LongArray? = null) {
    val osmId: OsmId get() = OsmId(OsmType.WAY, id)

    override fun equals(other: Any?) = other is OsmWay && other.id == id && other.tags == tags && other.points == points
    override fun hashCode() = id.hashCode()
}

/** The envelope of an Overpass answer: when the data was current, and the server's remark (a runtime error, often). */
data class OverpassMeta(val timestamp: String?, val remark: String?, val generator: String?) {
    /** True when Overpass reported a runtime error (timeout, out of memory): the elements are then incomplete. */
    val failed: Boolean get() = remark?.contains("runtime error", ignoreCase = true) == true
}

/** A whole Overpass answer held in memory. */
class OverpassResult(val elements: List<OverpassElement>, val meta: OverpassMeta) {
    /** Every tagged element with a position as a [Place]. */
    fun places(): List<Place> = elements.mapNotNull { it.toPlace() }

    /**
     * Every way with its line: from `out geom` directly, or rebuilt from the node coordinates when the answer
     * was `out body` plus its nodes (`>; out skel qt;`).
     */
    fun ways(): List<OsmWay> {
        var nodeIndex: HashMap<Long, LatLon>? = null
        return elements.filter { it.type == OsmType.WAY }.map { w ->
            val pts = if (w.geometry != null) w.points() else {
                val idx = nodeIndex ?: HashMap<Long, LatLon>().also { m ->
                    for (e in elements) if (e.type == OsmType.NODE && !e.lat.isNaN()) m[e.id] = LatLon(e.lat, e.lon)
                    nodeIndex = m
                }
                w.nodes?.let { ns -> ArrayList<LatLon>(ns.size).also { out -> for (n in ns) idx[n]?.let(out::add) } } ?: emptyList()
            }
            OsmWay(w.id, w.tags, pts, w.nodes)
        }
    }
}

/** Reads Overpass JSON as a stream, element by element, so even a whole city never sits in memory as text. */
object OverpassParser {
    /** Parses [reader] fully into an [OverpassResult]. */
    fun parse(reader: Reader): OverpassResult {
        val list = ArrayList<OverpassElement>()
        val meta = stream(reader) { list.add(it) }
        return OverpassResult(list, meta)
    }

    /** Calls [onElement] for each element in document order and returns the envelope. */
    fun stream(reader: Reader, onElement: (OverpassElement) -> Unit): OverpassMeta {
        var timestamp: String? = null
        var remark: String? = null
        var generator: String? = null
        JsonStream(reader).use { js ->
            js.beginObject()
            while (js.hasNext()) {
                when (js.nextName()) {
                    "elements" -> {
                        js.beginArray()
                        while (js.hasNext()) readElement(js)?.let(onElement)
                        js.endArray()
                    }
                    "remark" -> remark = js.nextScalarOrNull()
                    "generator" -> generator = js.nextScalarOrNull()
                    "osm3s" -> {
                        js.beginObject()
                        while (js.hasNext()) if (js.nextName() == "timestamp_osm_base") timestamp = js.nextScalarOrNull() else js.skipValue()
                        js.endObject()
                    }
                    else -> js.skipValue()
                }
            }
            js.endObject()
        }
        return OverpassMeta(timestamp, remark, generator)
    }

    private fun typeOf(s: String?): OsmType? = when (s) {
        "node" -> OsmType.NODE
        "way" -> OsmType.WAY
        "relation" -> OsmType.RELATION
        else -> null
    }

    private fun readElement(js: JsonStream): OverpassElement? {
        var type: OsmType? = null
        var id = 0L
        var lat = Double.NaN
        var lon = Double.NaN
        var tags: Map<String, String> = emptyMap()
        var center: LatLon? = null
        var bounds: BBox? = null
        var nodes: LongArray? = null
        var geometry: DoubleArray? = null
        var members: List<OverpassMember>? = null
        var version = 0
        var timestamp: String? = null
        js.beginObject()
        while (js.hasNext()) {
            when (js.nextName()) {
                "type" -> type = typeOf(js.nextScalarOrNull())
                "id" -> id = js.nextLong()
                "lat" -> lat = js.nextDoubleOrNaN()
                "lon" -> lon = js.nextDoubleOrNaN()
                "tags" -> tags = js.readStringMap()
                "center" -> center = readLatLon(js)
                "bounds" -> bounds = readBounds(js)
                "nodes" -> nodes = readLongs(js)
                "geometry" -> geometry = readGeometry(js)
                "members" -> members = readMembers(js)
                "version" -> version = js.nextLong().toInt()
                "timestamp" -> timestamp = js.nextScalarOrNull()
                else -> js.skipValue()
            }
        }
        js.endObject()
        val t = type ?: return null // "area" and other derived elements are not OSM elements
        return OverpassElement(t, id, tags, lat, lon, center, bounds, nodes, geometry, members, version, timestamp)
    }

    private fun readLatLon(js: JsonStream): LatLon? {
        var lat = Double.NaN
        var lon = Double.NaN
        js.beginObject()
        while (js.hasNext()) when (js.nextName()) {
            "lat" -> lat = js.nextDoubleOrNaN()
            "lon" -> lon = js.nextDoubleOrNaN()
            else -> js.skipValue()
        }
        js.endObject()
        return if (lat.isNaN() || lon.isNaN()) null else LatLon(lat, lon)
    }

    private fun readBounds(js: JsonStream): BBox? {
        var s = Double.NaN
        var w = Double.NaN
        var n = Double.NaN
        var e = Double.NaN
        js.beginObject()
        while (js.hasNext()) when (js.nextName()) {
            "minlat" -> s = js.nextDoubleOrNaN()
            "minlon" -> w = js.nextDoubleOrNaN()
            "maxlat" -> n = js.nextDoubleOrNaN()
            "maxlon" -> e = js.nextDoubleOrNaN()
            else -> js.skipValue()
        }
        js.endObject()
        return if (s.isNaN() || w.isNaN() || n.isNaN() || e.isNaN()) null else BBox(w, s, e, n)
    }

    private fun readLongs(js: JsonStream): LongArray {
        var a = LongArray(16)
        var n = 0
        js.beginArray()
        while (js.hasNext()) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = js.nextLong()
        }
        js.endArray()
        return a.copyOf(n)
    }

    /** `[{"lat":…,"lon":…}, null, …]` as interleaved lat, lon with NaN for nulls. */
    private fun readGeometry(js: JsonStream): DoubleArray {
        var a = DoubleArray(32)
        var n = 0
        js.beginArray()
        while (js.hasNext()) {
            if (n + 2 > a.size) a = a.copyOf(a.size * 2)
            val p = if (js.peek() == JsonStream.Token.NULL) { js.nextNull(); null } else readLatLon(js)
            a[n++] = p?.lat ?: Double.NaN
            a[n++] = p?.lon ?: Double.NaN
        }
        js.endArray()
        return a.copyOf(n)
    }

    private fun readMembers(js: JsonStream): List<OverpassMember> {
        val out = ArrayList<OverpassMember>()
        js.beginArray()
        while (js.hasNext()) {
            var type: OsmType? = null
            var ref = 0L
            var role = ""
            var lat = Double.NaN
            var lon = Double.NaN
            var geometry: DoubleArray? = null
            js.beginObject()
            while (js.hasNext()) when (js.nextName()) {
                "type" -> type = typeOf(js.nextScalarOrNull())
                "ref" -> ref = js.nextLong()
                "role" -> role = js.nextScalarOrNull() ?: ""
                "lat" -> lat = js.nextDoubleOrNaN()
                "lon" -> lon = js.nextDoubleOrNaN()
                "geometry" -> geometry = readGeometry(js)
                else -> js.skipValue()
            }
            js.endObject()
            if (type != null) out.add(OverpassMember(type, ref, role, lat, lon, geometry))
        }
        js.endArray()
        return out
    }
}
