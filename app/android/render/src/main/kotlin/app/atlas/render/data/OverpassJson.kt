package app.atlas.render.data

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/** Reads Overpass JSON (`out geom`) element by element: the container `raw.json` extracts are in. */
object OverpassJson {
    /** Calls [each] for every element of the document in [pull], in file order, and returns the other top-level fields. */
    fun read(pull: JsonPull, each: (OsmElement) -> Unit): JsonObject {
        val header = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        pull.beginObject()
        while (pull.hasNext()) {
            val name = pull.nextName()
            if (name == "elements") {
                pull.beginArray()
                while (pull.hasNext()) each(element(pull))
                pull.endArray()
            } else header[name] = pull.nextJson()
        }
        pull.endObject()
        return JsonObject(header)
    }

    /** Only the top-level fields other than `elements`, skipping the elements without building them. */
    fun readHeader(pull: JsonPull): JsonObject {
        val header = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        pull.beginObject()
        while (pull.hasNext()) {
            val name = pull.nextName()
            if (name == "elements") pull.skipValue() else header[name] = pull.nextJson()
        }
        pull.endObject()
        return JsonObject(header)
    }

    fun pull(input: InputStream): JsonPull = JsonPull(InputStreamReader(input, Charsets.UTF_8))

    /** One element object. Fields this reader does not know are skipped. */
    fun element(p: JsonPull): OsmElement {
        var type: ElementType? = null
        var id = 0L
        var lat = Double.NaN
        var lon = Double.NaN
        var tags: LinkedHashMap<String, String>? = null
        var bounds: Bounds? = null
        var nodes: LongArray? = null
        var geometry: Points? = null
        var members: ArrayList<Member>? = null
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "type" -> type = ElementType.of(p.nextString())
                "id" -> id = p.nextLong()
                "lat" -> lat = p.nextDouble()
                "lon" -> lon = p.nextDouble()
                "tags" -> {
                    val t = LinkedHashMap<String, String>()
                    p.beginObject()
                    while (p.hasNext()) {
                        val k = p.nextName()
                        t[k] = p.nextString()
                    }
                    p.endObject()
                    tags = t
                }
                "bounds" -> bounds = bounds(p)
                "nodes" -> nodes = longs(p)
                "geometry" -> geometry = points(p)
                "members" -> {
                    val ms = ArrayList<Member>()
                    p.beginArray()
                    while (p.hasNext()) ms += member(p)
                    p.endArray()
                    members = ms
                }
                else -> p.skipValue()
            }
        }
        p.endObject()
        return OsmElement(type ?: throw PackFormatException("an element without a type"), id, lat, lon, tags, bounds, nodes, geometry, members)
    }

    private fun bounds(p: JsonPull): Bounds {
        var a = Double.NaN
        var b = Double.NaN
        var c = Double.NaN
        var d = Double.NaN
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "minlat" -> a = p.nextDouble()
                "minlon" -> b = p.nextDouble()
                "maxlat" -> c = p.nextDouble()
                "maxlon" -> d = p.nextDouble()
                else -> p.skipValue()
            }
        }
        p.endObject()
        return Bounds(a, b, c, d)
    }

    private fun longs(p: JsonPull): LongArray {
        var a = LongArray(16)
        var n = 0
        p.beginArray()
        while (p.hasNext()) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = p.nextLong()
        }
        p.endArray()
        return a.copyOf(n)
    }

    private fun points(p: JsonPull): Points {
        var la = DoubleArray(16)
        var lo = DoubleArray(16)
        var n = 0
        p.beginArray()
        while (p.hasNext()) {
            if (n == la.size) {
                la = la.copyOf(n * 2)
                lo = lo.copyOf(n * 2)
            }
            if (p.peek() == JsonToken.NULL) {
                p.nextNull()
                la[n] = Double.NaN
                lo[n] = Double.NaN
            } else {
                var x = Double.NaN
                var y = Double.NaN
                p.beginObject()
                while (p.hasNext()) {
                    when (p.nextName()) {
                        "lat" -> x = p.nextDouble()
                        "lon" -> y = p.nextDouble()
                        else -> p.skipValue()
                    }
                }
                p.endObject()
                la[n] = x
                lo[n] = y
            }
            n++
        }
        p.endArray()
        return Points(la.copyOf(n), lo.copyOf(n))
    }

    private fun member(p: JsonPull): Member {
        var type: ElementType? = null
        var ref = 0L
        var role = ""
        var lat = Double.NaN
        var lon = Double.NaN
        var geometry: Points? = null
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "type" -> type = ElementType.of(p.nextString())
                "ref" -> ref = p.nextLong()
                "role" -> role = p.nextString()
                "lat" -> lat = p.nextDouble()
                "lon" -> lon = p.nextDouble()
                "geometry" -> geometry = points(p)
                else -> p.skipValue()
            }
        }
        p.endObject()
        return Member(type ?: throw PackFormatException("a member without a type"), ref, role, lat, lon, geometry)
    }
}

/** An Overpass JSON file (`raw.json`) as an [ElementSource], streamed from disk each time. */
class OverpassJsonFile(val file: File) : ElementSource {
    override val header: JsonObject by lazy {
        file.inputStream().buffered().use { OverpassJson.readHeader(OverpassJson.pull(it)) }
    }

    override fun elements(): Sequence<OsmElement> = sequence {
        // one pull per iteration; elements are yielded as they are read, so nothing but the current one is held
        val input = file.inputStream().buffered(1 shl 16)
        try {
            val p = OverpassJson.pull(input)
            p.beginObject()
            while (p.hasNext()) {
                if (p.nextName() == "elements") {
                    p.beginArray()
                    while (p.hasNext()) yield(OverpassJson.element(p))
                    p.endArray()
                } else p.skipValue()
            }
        } finally {
            input.close()
        }
    }
}

/** Overpass JSON text (an answer held in memory) as an [ElementSource]. */
class OverpassJsonText(private val text: String) : ElementSource {
    override val header: JsonObject by lazy { OverpassJson.readHeader(JsonPull(java.io.StringReader(text))) }

    override fun elements(): Sequence<OsmElement> = sequence {
        val p = JsonPull(java.io.StringReader(text))
        p.beginObject()
        while (p.hasNext()) {
            if (p.nextName() == "elements") {
                p.beginArray()
                while (p.hasNext()) yield(OverpassJson.element(p))
                p.endArray()
            } else p.skipValue()
        }
    }
}
