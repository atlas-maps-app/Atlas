package app.atlas.tiles

import app.atlas.geo.TileId

/** A decoded Mapbox Vector Tile: its layers by name. Layers and features decode lazily, on first use. */
class VectorTile(val layers: Map<String, TileLayer>) {
    operator fun get(name: String): TileLayer? = layers[name]
    val layerNames: Set<String> get() = layers.keys

    /** Features in every layer (decodes every layer's feature index). */
    val featureCount: Int get() = layers.values.sumOf { it.size }

    override fun toString(): String = "VectorTile(${layers.keys.joinToString()})"

    companion object {
        val EMPTY = VectorTile(emptyMap())
    }
}

/**
 * One layer of a vector tile: [name], coordinate [extent] and [features].
 * Keys and values are shared by its features, as the tile stores them.
 */
class TileLayer internal constructor(
    val name: String,
    val extent: Int,
    val version: Int,
    internal val buf: ByteArray,
    keys: Array<String>,
    private val valueRanges: IntArray,
    private val featureRanges: IntArray,
) {
    /** The layer's key table. */
    val keys: List<String> = keys.asList()
    private val keyArray = keys
    private val keyIndex: HashMap<String, Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        HashMap<String, Int>(keys.size * 2).also { m -> for (i in keys.indices) m.putIfAbsent(keys[i], i) }
    }
    private val valueTable: Array<Any?> by lazy(LazyThreadSafetyMode.PUBLICATION) { decodeValues() }

    /** The layer's value table: String, Long, Double or Boolean (null for a malformed value). */
    val values: List<Any?> get() = valueTable.asList()
    val valueCount: Int get() = valueRanges.size / 2

    val features: List<TileFeature> by lazy(LazyThreadSafetyMode.PUBLICATION) { decodeFeatures() }
    val size: Int get() = features.size

    operator fun get(i: Int): TileFeature = features[i]

    /** The index of [key] in the key table, or -1. */
    fun keyIndex(key: String): Int = keyIndex[key] ?: -1

    fun key(i: Int): String = keyArray[i]
    fun value(i: Int): Any? = valueTable[i]

    /** Converts this layer's tile units to the map for a tile [tile]. */
    fun projection(tile: TileId) = TileProjection(tile, extent)

    private fun decodeValues(): Array<Any?> {
        val out = arrayOfNulls<Any>(valueRanges.size / 2)
        val r = ProtoReader(buf, 0, 0)
        for (i in out.indices) {
            r.reset(valueRanges[2 * i], valueRanges[2 * i + 1])
            var v: Any? = null
            while (r.next()) {
                v = when (r.field) {
                    1 -> if (r.wireType == ProtoReader.WIRE_LENGTH) r.string() else { r.skip(); v }
                    2 -> if (r.wireType == ProtoReader.WIRE_FIXED32) r.float().toDouble() else { r.skip(); v }
                    3 -> if (r.wireType == ProtoReader.WIRE_FIXED64) r.double() else { r.skip(); v }
                    4, 5 -> if (r.wireType == ProtoReader.WIRE_VARINT) r.varint() else { r.skip(); v }
                    6 -> if (r.wireType == ProtoReader.WIRE_VARINT) r.sint64() else { r.skip(); v }
                    7 -> if (r.wireType == ProtoReader.WIRE_VARINT) r.bool() else { r.skip(); v }
                    else -> { r.skip(); v }
                }
            }
            out[i] = v
        }
        return out
    }

    private fun decodeFeatures(): List<TileFeature> {
        val out = ArrayList<TileFeature>(featureRanges.size / 2)
        val r = ProtoReader(buf, 0, 0)
        for (f in 0 until featureRanges.size / 2) {
            r.reset(featureRanges[2 * f], featureRanges[2 * f + 1])
            var id = 0L
            var hasId = false
            var type = 0
            var tagsStart = 0
            var tagsEnd = 0
            var geomStart = 0
            var geomEnd = 0
            while (r.next()) {
                when (r.field) {
                    1 -> if (r.wireType == ProtoReader.WIRE_VARINT) { id = r.varint(); hasId = true } else r.skip()
                    2 -> if (r.wireType == ProtoReader.WIRE_LENGTH) {
                        val n = r.length(); tagsStart = r.pos; tagsEnd = tagsStart + n; r.pos = tagsEnd
                    } else r.skip()
                    3 -> if (r.wireType == ProtoReader.WIRE_VARINT) type = r.varint32() else r.skip()
                    4 -> if (r.wireType == ProtoReader.WIRE_LENGTH) {
                        val n = r.length(); geomStart = r.pos; geomEnd = geomStart + n; r.pos = geomEnd
                    } else r.skip()
                    else -> r.skip()
                }
            }
            val gt = when (type) {
                1 -> GeomType.POINT
                2 -> GeomType.LINE
                3 -> GeomType.POLYGON
                else -> null // UNKNOWN geometry: the spec lets decoders ignore it.
            } ?: continue
            out += TileFeature(this, id, hasId, gt, tagsStart, tagsEnd, geomStart, geomEnd)
        }
        return out
    }

    override fun toString(): String = "TileLayer($name, extent=$extent, features=${featureRanges.size / 2})"
}

/**
 * One feature of a [TileLayer]: [id], geometry [type], [geometry] and [props].
 * Its geometry and tags decode on first use and are kept.
 */
class TileFeature internal constructor(
    val layer: TileLayer,
    val id: Long,
    /** False when the tile gave the feature no id ([id] is then 0). */
    val hasId: Boolean,
    val type: GeomType,
    private val tagsStart: Int,
    private val tagsEnd: Int,
    private val geomStart: Int,
    private val geomEnd: Int,
) {
    // Volatile so a feature decoded on one thread is safely seen whole on another (an IntArray has no final-field guarantee).
    @Volatile
    private var geom: TileGeometry? = null

    @Volatile
    private var tagPairs: IntArray? = null

    @Volatile
    private var propsView: TileProps? = null

    val geometry: TileGeometry
        get() = geom ?: MvtGeometry.decode(layer.buf, geomStart, geomEnd, type).also { geom = it }

    /**
     * Key and value indices into the layer's tables, in pairs. Invalid pairs (an index out of range, or a malformed value)
     * and repeats of a key are dropped, so each key appears once, as [get] reads it.
     */
    val tags: IntArray
        get() = tagPairs ?: decodeTags().also { tagPairs = it }

    /** The feature's properties: a light map over the layer's shared key and value tables. */
    val props: TileProps
        get() = propsView ?: TileProps(layer, tags).also { propsView = it }

    /** The value of property [key], or null. Faster than going through [props]. */
    operator fun get(key: String): Any? {
        val k = layer.keyIndex(key)
        if (k < 0) return null
        val t = tags
        var i = 0
        while (i < t.size) {
            if (t[i] == k) return layer.value(t[i + 1])
            i += 2
        }
        return null
    }

    fun string(key: String): String? = get(key) as? String
    fun long(key: String): Long? = (get(key) as? Number)?.toLong()
    fun int(key: String): Int? = (get(key) as? Number)?.toInt()
    fun double(key: String): Double? = (get(key) as? Number)?.toDouble()
    fun bool(key: String): Boolean? = get(key) as? Boolean

    private fun decodeTags(): IntArray {
        if (tagsEnd <= tagsStart) return NO_TAGS
        val r = ProtoReader(layer.buf, tagsStart, tagsEnd)
        var count = 0
        for (i in tagsStart until tagsEnd) if (layer.buf[i] >= 0) count++
        val raw = IntArray(count)
        for (i in 0 until count) raw[i] = r.varint32()
        // Compact in place: keep valid pairs whose key has not been seen in this feature (features hold a few dozen tags).
        var o = 0
        var i = 0
        while (i + 1 < raw.size) {
            val k = raw[i]
            val v = raw[i + 1]
            if (isValidPair(k, v) && !hasKey(raw, o, k)) { raw[o++] = k; raw[o++] = v }
            i += 2
        }
        return if (o == raw.size) raw else raw.copyOf(o)
    }

    private fun isValidPair(k: Int, v: Int): Boolean =
        k >= 0 && k < layer.keys.size && v >= 0 && v < layer.valueCount && layer.value(v) != null

    private fun hasKey(pairs: IntArray, end: Int, k: Int): Boolean {
        var j = 0
        while (j < end) {
            if (pairs[j] == k) return true
            j += 2
        }
        return false
    }

    override fun toString(): String = "TileFeature(${layer.name}#$id $type ${props})"

    private companion object {
        val NO_TAGS = IntArray(0)
    }
}

/** A feature's properties as a read-only map, backed by the layer's tables (no copies). */
class TileProps internal constructor(private val layer: TileLayer, private val tags: IntArray) : AbstractMap<String, Any>() {
    override val size: Int get() = tags.size / 2

    override fun get(key: String): Any? {
        val k = layer.keyIndex(key)
        if (k < 0) return null
        var i = 0
        while (i < tags.size) {
            if (tags[i] == k) return layer.value(tags[i + 1])
            i += 2
        }
        return null
    }

    override fun containsKey(key: String): Boolean = get(key) != null

    /** A view over the tag pairs, which hold each key once and no null values, so it agrees with [size] and [get]. */
    override val entries: Set<Map.Entry<String, Any>>
        get() = object : AbstractSet<Map.Entry<String, Any>>() {
            override val size: Int get() = tags.size / 2

            override fun iterator(): Iterator<Map.Entry<String, Any>> = object : Iterator<Map.Entry<String, Any>> {
                private var i = 0

                override fun hasNext(): Boolean = i < tags.size

                override fun next(): Map.Entry<String, Any> {
                    if (i >= tags.size) throw NoSuchElementException()
                    val e = java.util.AbstractMap.SimpleImmutableEntry(layer.key(tags[i]), layer.value(tags[i + 1])!!)
                    i += 2
                    return e
                }
            }
        }

    fun string(key: String): String? = get(key) as? String
    fun long(key: String): Long? = (get(key) as? Number)?.toLong()
    fun double(key: String): Double? = (get(key) as? Number)?.toDouble()
}
