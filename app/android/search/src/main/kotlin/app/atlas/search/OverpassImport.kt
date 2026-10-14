package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmType
import app.atlas.providers.JsonStream
import app.atlas.providers.OverpassElement
import java.io.Reader

/**
 * Streams Overpass JSON into a [StoreWriter] element by element, so a city's extract never sits in memory. Reads the
 * standard answer (`{"elements": [...]}`) and also files that keep several element lists side by side
 * (`{"post": [...], "toilets": [...]}`): any top-level array of objects is read as elements.
 */
object OverpassImport {
    /** Reads every element of [reader] into [writer]; returns how many elements it read. */
    fun read(reader: Reader, writer: StoreWriter): Long = read(reader) { writer.add(it) }

    /** Calls [onElement] for every element of [reader]; returns how many it read. */
    fun read(reader: Reader, onElement: (OverpassElement) -> Unit): Long {
        var count = 0L
        val js = JsonStream(reader)
        js.beginObject()
        while (js.hasNext()) {
            js.nextName()
            if (js.peek() != JsonStream.Token.BEGIN_ARRAY) {
                js.skipValue()
                continue
            }
            js.beginArray()
            while (js.hasNext()) {
                if (js.peek() != JsonStream.Token.BEGIN_OBJECT) {
                    js.skipValue()
                    continue
                }
                element(js)?.let { onElement(it); count++ }
            }
            js.endArray()
        }
        js.endObject()
        return count
    }

    private fun element(js: JsonStream): OverpassElement? {
        var type: OsmType? = null
        var id = 0L
        var lat = Double.NaN
        var lon = Double.NaN
        var tags: Map<String, String> = emptyMap()
        var center: LatLon? = null
        var bounds: BBox? = null
        var geometryBox: BBox? = null
        var version = 0
        var timestamp: String? = null
        js.beginObject()
        while (js.hasNext()) {
            when (js.nextName()) {
                "type" -> type = when (js.nextScalarOrNull()) {
                    "node" -> OsmType.NODE
                    "way" -> OsmType.WAY
                    "relation" -> OsmType.RELATION
                    else -> null
                }
                "id" -> id = js.nextLong()
                "lat" -> lat = js.nextDoubleOrNaN()
                "lon" -> lon = js.nextDoubleOrNaN()
                "tags" -> tags = if (js.peek() == JsonStream.Token.BEGIN_OBJECT) js.readStringMap() else { js.skipValue(); emptyMap() }
                "center" -> center = latLon(js)
                "bounds" -> bounds = box(js)
                "geometry" -> geometryBox = geometry(js)
                // with `out meta`: when it was last edited, for "newer:" searches offline
                "timestamp" -> timestamp = js.nextScalarOrNull()
                "version" -> version = js.nextLong().toInt()
                else -> js.skipValue()
            }
        }
        js.endObject()
        val t = type ?: return null
        return OverpassElement(t, id, tags, lat, lon, center, bounds ?: geometryBox, version = version, timestamp = timestamp)
    }

    private fun latLon(js: JsonStream): LatLon? {
        if (js.peek() != JsonStream.Token.BEGIN_OBJECT) { js.skipValue(); return null }
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

    private fun box(js: JsonStream): BBox? {
        if (js.peek() != JsonStream.Token.BEGIN_OBJECT) { js.skipValue(); return null }
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

    /** The box of an `out geom` point list, without keeping the points. */
    private fun geometry(js: JsonStream): BBox? {
        if (js.peek() != JsonStream.Token.BEGIN_ARRAY) { js.skipValue(); return null }
        var s = Double.POSITIVE_INFINITY
        var w = Double.POSITIVE_INFINITY
        var n = Double.NEGATIVE_INFINITY
        var e = Double.NEGATIVE_INFINITY
        js.beginArray()
        while (js.hasNext()) {
            val p = latLon(js) ?: continue
            if (p.lat < s) s = p.lat
            if (p.lat > n) n = p.lat
            if (p.lon < w) w = p.lon
            if (p.lon > e) e = p.lon
        }
        js.endArray()
        return if (s.isInfinite()) null else BBox(w, s, e, n)
    }
}
