package app.atlas.tiles

/**
 * Mapbox Vector Tile 2.1 decoding.
 *
 * [decode] reads only the tile's layer index and key tables; features, values, tags and geometry decode lazily,
 * when first touched, straight from the tile's bytes.
 */
object Mvt {
    /**
     * Decodes [bytes], raw or gzip-compressed. When [only] is given, other layers are skipped.
     * @throws TileFormatException on malformed data.
     */
    fun decode(bytes: ByteArray, only: Set<String>? = null): VectorTile {
        val buf = Gzip.unwrap(bytes)
        if (buf.isEmpty()) return VectorTile.EMPTY
        val r = ProtoReader(buf)
        val layers = LinkedHashMap<String, TileLayer>()
        while (r.next()) {
            if (r.field == 3 && r.wireType == ProtoReader.WIRE_LENGTH) {
                val old = r.pushLimit(r.length())
                val layer = readLayer(r, buf, only)
                r.popLimit(old)
                // Layer names are unique by the spec; a repeated name keeps the first.
                if (layer != null) layers.putIfAbsent(layer.name, layer)
            } else {
                r.skip()
            }
        }
        return VectorTile(layers)
    }

    private fun readLayer(r: ProtoReader, buf: ByteArray, only: Set<String>?): TileLayer? {
        var name: String? = null
        var extent = 4096
        var version = 1
        val keys = ArrayList<String>()
        var values = IntArray(64)
        var nValues = 0
        var features = IntArray(256)
        var nFeatures = 0
        while (r.next()) {
            when (r.field) {
                1 -> {
                    name = r.string()
                    if (only != null && name !in only) return null
                }
                2 -> if (r.wireType == ProtoReader.WIRE_LENGTH) {
                    val n = r.length()
                    if (nFeatures + 2 > features.size) features = features.copyOf(features.size * 2)
                    features[nFeatures++] = r.pos
                    features[nFeatures++] = r.pos + n
                    r.pos += n
                } else r.skip()
                3 -> if (r.wireType == ProtoReader.WIRE_LENGTH) keys += r.string() else r.skip()
                4 -> if (r.wireType == ProtoReader.WIRE_LENGTH) {
                    val n = r.length()
                    if (nValues + 2 > values.size) values = values.copyOf(values.size * 2)
                    values[nValues++] = r.pos
                    values[nValues++] = r.pos + n
                    r.pos += n
                } else r.skip()
                5 -> if (r.wireType == ProtoReader.WIRE_VARINT) extent = r.varint32() else r.skip()
                15 -> if (r.wireType == ProtoReader.WIRE_VARINT) version = r.varint32() else r.skip()
                else -> r.skip()
            }
        }
        if (name == null) throw TileFormatException("layer without a name")
        if (extent <= 0) throw TileFormatException("layer $name has extent $extent")
        return TileLayer(name, extent, version, buf, keys.toTypedArray(), values.copyOf(nValues), features.copyOf(nFeatures))
    }
}

/** Decodes MVT geometry command streams into [TileGeometry]. */
internal object MvtGeometry {
    private const val MOVE_TO = 1
    private const val LINE_TO = 2
    private const val CLOSE_PATH = 7

    private class Scratch {
        var coords = IntArray(4096)
        var parts = IntArray(256)
        var areas = LongArray(256)
        var polygons = IntArray(64)

        fun ensureCoords(n: Int) {
            if (n > coords.size) coords = coords.copyOf(maxOf(n, coords.size * 2))
        }

        fun ensureParts(n: Int) {
            if (n > parts.size) {
                parts = parts.copyOf(maxOf(n, parts.size * 2))
                areas = areas.copyOf(parts.size)
            }
        }
    }

    private val scratch = ThreadLocal.withInitial { Scratch() }

    fun decode(buf: ByteArray, start: Int, end: Int, type: GeomType): TileGeometry {
        if (end <= start) return TileGeometry.empty(type)
        val s = scratch.get()
        // Every point takes at least two bytes and every ring closure adds one point per three bytes at most.
        s.ensureCoords(end - start + (end - start) / 3 + 8)
        s.ensureParts((end - start) / 3 + 2)
        val r = ProtoReader(buf, start, end)
        val c = s.coords
        var n = 0 // ints written to coords
        var nParts = 0 // parts finished
        var partStart = -1 // coords offset of the open part, or -1
        var x = 0
        var y = 0
        val polygon = type == GeomType.POLYGON
        while (r.hasMore) {
            val cmd = r.varint32()
            val id = cmd and 7
            val count = cmd ushr 3
            when (id) {
                MOVE_TO -> for (k in 0 until count) {
                    x += ProtoReader.zigzag(r.varint32())
                    y += ProtoReader.zigzag(r.varint32())
                    if (type != GeomType.POINT) {
                        if (partStart >= 0) nParts = finishPart(s, partStart, n, nParts, polygon).also { n = it.ushr(32).toInt() }.toInt()
                        partStart = n
                    }
                    c[n++] = x
                    c[n++] = y
                }
                LINE_TO -> {
                    if (partStart < 0 && type != GeomType.POINT) throw TileFormatException("LineTo before MoveTo")
                    for (k in 0 until count) {
                        x += ProtoReader.zigzag(r.varint32())
                        y += ProtoReader.zigzag(r.varint32())
                        c[n++] = x
                        c[n++] = y
                    }
                }
                CLOSE_PATH -> {
                    if (partStart < 0) throw TileFormatException("ClosePath before MoveTo")
                    // Close the ring explicitly unless it already ends on its first point.
                    if (n - partStart >= 4 && (c[n - 2] != c[partStart] || c[n - 1] != c[partStart + 1])) {
                        c[n++] = c[partStart]
                        c[n++] = c[partStart + 1]
                    }
                    if (polygon) {
                        nParts = finishPart(s, partStart, n, nParts, true).also { n = it.ushr(32).toInt() }.toInt()
                        partStart = -1
                    }
                }
                else -> throw TileFormatException("unknown geometry command $id")
            }
        }
        if (type == GeomType.POINT) {
            return if (n == 0) TileGeometry.empty(type) else TileGeometry(type, c.copyOf(n), intArrayOf(0, n / 2), NO_POLYGONS)
        }
        if (partStart >= 0) nParts = finishPart(s, partStart, n, nParts, polygon).also { n = it.ushr(32).toInt() }.toInt()
        if (nParts == 0) return TileGeometry.empty(type)
        val partOut = IntArray(nParts + 1)
        for (p in 0 until nParts) partOut[p] = s.parts[p] / 2
        partOut[nParts] = n / 2
        val polys = if (polygon) classify(s, nParts) else NO_POLYGONS
        return TileGeometry(type, c.copyOf(n), partOut, polys)
    }

    /**
     * Ends the part that starts at coords offset [start] and runs to [end]: keeps it when it is a valid line or ring,
     * drops it otherwise. Returns the new coords length in the high 32 bits and the new part count in the low.
     */
    private fun finishPart(s: Scratch, start: Int, end: Int, nParts: Int, polygon: Boolean): Long {
        val c = s.coords
        var e = end
        if (polygon) {
            // A ring left open (malformed, or followed straight by MoveTo) is closed here.
            if (e - start >= 6 && (c[e - 2] != c[start] || c[e - 1] != c[start + 1])) {
                c[e++] = c[start]
                c[e++] = c[start + 1]
            }
            if (e - start < 8) return pack(start, nParts)
            var a = 0L
            var i = start
            while (i < e - 2) {
                a += c[i].toLong() * c[i + 3] - c[i + 2].toLong() * c[i + 1]
                i += 2
            }
            if (a == 0L) return pack(start, nParts) // A ring without area is invalid; the spec lets decoders drop it.
            s.ensureParts(nParts + 2)
            s.parts[nParts] = start
            s.areas[nParts] = a
            return pack(e, nParts + 1)
        }
        if (e - start < 4) return pack(start, nParts)
        s.ensureParts(nParts + 2)
        s.parts[nParts] = start
        return pack(e, nParts + 1)
    }

    private fun pack(n: Int, parts: Int): Long = (n.toLong() shl 32) or (parts.toLong() and 0xffffffffL)

    /**
     * Groups rings into polygons: a ring winding like the first ring starts a polygon, one winding against it is a hole
     * of the current polygon. In valid 2.x tiles the first ring is exterior with positive area; taking its sign also
     * reads 1.x tiles, which wound their rings either way.
     */
    private fun classify(s: Scratch, nParts: Int): IntArray {
        if (s.polygons.size < nParts + 1) s.polygons = IntArray(maxOf(nParts + 1, s.polygons.size * 2))
        val out = s.polygons
        var nPoly = 0
        val exteriorNegative = s.areas[0] < 0
        for (p in 0 until nParts) {
            if ((s.areas[p] < 0) == exteriorNegative) out[nPoly++] = p
        }
        out[nPoly] = nParts
        return out.copyOf(nPoly + 1)
    }

    private val NO_POLYGONS = IntArray(0)
}
