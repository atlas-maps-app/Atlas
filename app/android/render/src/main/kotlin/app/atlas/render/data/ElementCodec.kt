package app.atlas.render.data

/** Counts the strings and tag pairs of a pack, so the frequent ones go into shared tables. */
internal class DictionaryBuilder {
    private val ids = HashMap<String, Int>(1 shl 16)
    private val names = ArrayList<String>()
    private var counts = IntArray(1 shl 12)
    private val pairCounts = HashMap<Long, IntArray>(1 shl 14)

    private fun intern(s: String): Int {
        val existing = ids[s]
        if (existing != null) return existing
        val id = names.size
        names += s
        ids[s] = id
        if (id == counts.size) counts = counts.copyOf(id * 2)
        return id
    }

    fun string(s: String) {
        counts[intern(s)]++
    }

    fun tag(k: String, v: String) {
        val ki = intern(k)
        val vi = intern(v)
        counts[ki]++
        counts[vi]++
        val key = (ki.toLong() shl 32) or vi.toLong()
        val c = pairCounts[key]
        if (c == null) pairCounts[key] = intArrayOf(1) else c[0]++
    }

    /** Strings used at least twice, most used first; pairs used at least twice, most used first. */
    fun build(): Dictionary {
        val order = names.indices.filter { counts[it] >= 2 }.sortedWith(compareBy({ -counts[it] }, { it }))
        val index = IntArray(names.size) { -1 }
        for ((i, id) in order.withIndex()) index[id] = i
        val strings = Array(order.size) { names[order[it]] }
        val pairs = pairCounts.entries.filter { it.value[0] >= 2 }.sortedWith(compareBy({ -it.value[0] }, { it.key }))
        val pk = IntArray(pairs.size)
        val pv = IntArray(pairs.size)
        for ((i, e) in pairs.withIndex()) {
            pk[i] = index[(e.key ushr 32).toInt()]
            pv[i] = index[(e.key and 0xFFFFFFFFL).toInt()]
        }
        return Dictionary(strings, pk, pv)
    }
}

/** The shared string table and tag-pair table of a pack. */
internal class Dictionary(val strings: Array<String>, val pairKey: IntArray, val pairValue: IntArray) {
    private val stringIndex: HashMap<String, Int> by lazy { HashMap<String, Int>(strings.size * 2).also { m -> for ((i, s) in strings.withIndex()) m[s] = i } }
    private val pairIndex: HashMap<Long, Int> by lazy {
        HashMap<Long, Int>(pairKey.size * 2).also { m -> for (i in pairKey.indices) m[(pairKey[i].toLong() shl 32) or pairValue[i].toLong()] = i }
    }

    fun indexOf(s: String): Int = stringIndex[s] ?: -1

    fun pairOf(key: Int, value: Int): Int = pairIndex[(key.toLong() shl 32) or value.toLong()] ?: -1

    fun writeStrings(sink: ByteSink) {
        sink.varInt(strings.size)
        for (s in strings) sink.string(s)
    }

    fun writePairs(sink: ByteSink) {
        sink.varInt(pairKey.size)
        for (i in pairKey.indices) {
            sink.varInt(pairKey[i])
            sink.varInt(pairValue[i])
        }
    }

    companion object {
        fun read(strings: ByteSource, pairs: ByteSource): Dictionary {
            val n = strings.varInt()
            val s = Array(n) { strings.string() }
            val m = pairs.varInt()
            val k = IntArray(m)
            val v = IntArray(m)
            for (i in 0 until m) {
                k[i] = pairs.varInt()
                v[i] = pairs.varInt()
            }
            return Dictionary(s, k, v)
        }
    }
}

/** The 1e-7 degree integers the pack stores coordinates as (or finer, when an extract needs it). */
internal class Scale(val digits: Int) {
    val factor: Double = POW10[digits]

    fun fixed(v: Double): Long = Math.round(v * factor)

    fun degrees(f: Long): Double = f / factor

    /** True when [v] survives the round trip to an integer and back, bit for bit. */
    fun exact(v: Double): Boolean = !v.isNaN() && degrees(fixed(v)) == v && !(v == 0.0 && 1.0 / v < 0)

    companion object {
        private val POW10 = doubleArrayOf(1.0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9)
        const val MIN_DIGITS = 7
        const val MAX_DIGITS = 9

        /** The fewest decimal places (7 to 9) at which every coordinate of [values] is exact, or -1. */
        fun digitsFor(values: DoubleArray, from: Int = MIN_DIGITS): Int {
            var d = from
            for (v in values) {
                if (v.isNaN()) continue
                while (d <= MAX_DIGITS && !Scale(d).exact(v)) d++
                if (d > MAX_DIGITS) return -1
            }
            return d
        }
    }
}

private const val TYPE_MASK = 3
private const val F_TAGS = 4
private const val F_ODD_BOUNDS = 8
private const val F_A = 16
private const val F_B = 32
private const val F_NULLS = 64
private const val F_SAME = 128

/** Min and max fixed-point corners of the positions in an element. */
internal class BoxAcc(var minLat: Long = Long.MAX_VALUE, var minLon: Long = Long.MAX_VALUE, var maxLat: Long = Long.MIN_VALUE, var maxLon: Long = Long.MIN_VALUE) {
    fun add(lat: Long, lon: Long) {
        if (lat < minLat) minLat = lat
        if (lat > maxLat) maxLat = lat
        if (lon < minLon) minLon = lon
        if (lon > maxLon) maxLon = lon
    }

    val empty: Boolean get() = minLat == Long.MAX_VALUE
}

/** Writes elements into a chunk. One encoder per chunk; deltas restart with each. */
internal class ElementEncoder(private val dict: Dictionary, private val scale: Scale) {
    private var lastId = 0L
    private var lastLat = 0L
    private var lastLon = 0L
    private var lastNode = 0L
    private var lastRef = 0L
    private val acc = BoxAcc()

    fun reset() {
        lastId = 0; lastLat = 0; lastLon = 0; lastNode = 0; lastRef = 0
    }

    /** The corners of what [e] covers, derived from its positions (no corners for an element without any). */
    fun derive(e: OsmElement, into: BoxAcc = acc.also { it.minLat = Long.MAX_VALUE; it.minLon = Long.MAX_VALUE; it.maxLat = Long.MIN_VALUE; it.maxLon = Long.MIN_VALUE }): BoxAcc {
        if (!e.lat.isNaN()) into.add(scale.fixed(e.lat), scale.fixed(e.lon))
        e.geometry?.let { addPoints(it, into) }
        e.members?.forEach { m ->
            if (m.hasPosition) into.add(scale.fixed(m.lat), scale.fixed(m.lon))
            m.geometry?.let { addPoints(it, into) }
        }
        return into
    }

    private fun addPoints(p: Points, into: BoxAcc) {
        for (i in 0 until p.size) if (!p.isNull(i)) into.add(scale.fixed(p.lat[i]), scale.fixed(p.lon[i]))
    }

    fun encode(e: OsmElement, sink: ByteSink) {
        val type = e.type
        if (type != ElementType.NODE && (!e.lat.isNaN() || !e.lon.isNaN())) throw PackFormatException("${e.type.json} ${e.id} has a position of its own")
        if (type == ElementType.NODE && (e.bounds != null || e.nodes != null || e.geometry != null || e.members != null)) throw PackFormatException("node ${e.id} has fields a node does not")
        if (type == ElementType.WAY && e.members != null) throw PackFormatException("way ${e.id} has members")
        if (type == ElementType.RELATION && (e.nodes != null || e.geometry != null)) throw PackFormatException("relation ${e.id} has nodes or a geometry")
        if (type == ElementType.NODE && e.lat.isNaN() != e.lon.isNaN()) throw PackFormatException("node ${e.id} has half a position")

        var head = type.ordinal
        if (e.tags != null) head = head or F_TAGS
        // bounds are implied when they equal the box of the element's own positions
        val derived = derive(e)
        val implied = if (derived.empty) null else Bounds(scale.degrees(derived.minLat), scale.degrees(derived.minLon), scale.degrees(derived.maxLat), scale.degrees(derived.maxLon))
        val oddBounds = type != ElementType.NODE && !sameBounds(e.bounds, implied)
        if (oddBounds) head = head or F_ODD_BOUNDS
        var same = false
        var nulls = false
        when (type) {
            ElementType.NODE -> if (!e.lat.isNaN()) head = head or F_A
            ElementType.WAY -> {
                if (e.nodes != null) head = head or F_A
                val g = e.geometry
                if (g != null) {
                    head = head or F_B
                    if (g.hasNulls) {
                        nulls = true
                        head = head or F_NULLS
                    }
                    if (e.nodes != null && e.nodes.size == g.size) {
                        same = true
                        head = head or F_SAME
                    }
                }
            }
            ElementType.RELATION -> if (e.members != null) head = head or F_A
        }
        sink.byte(head)
        sink.zigzag(e.id - lastId)
        lastId = e.id
        if (type == ElementType.NODE) {
            if (!e.lat.isNaN()) position(sink, e.lat, e.lon)
        } else if (oddBounds) {
            val b = e.bounds
            if (b == null) sink.byte(0)
            else {
                sink.byte(1)
                val minLat = scale.fixed(b.minLat)
                val minLon = scale.fixed(b.minLon)
                sink.zigzag(minLat - lastLat)
                sink.zigzag(minLon - lastLon)
                sink.zigzag(scale.fixed(b.maxLat) - minLat)
                sink.zigzag(scale.fixed(b.maxLon) - minLon)
                lastLat = minLat
                lastLon = minLon
            }
        }
        if (type == ElementType.WAY) {
            val ns = e.nodes
            if (ns != null) {
                sink.varInt(ns.size)
                for (n in ns) {
                    sink.zigzag(n - lastNode)
                    lastNode = n
                }
            }
            e.geometry?.let { points(sink, it, nulls, countKnown = same) }
        } else if (type == ElementType.RELATION) {
            e.members?.let { members(sink, it) }
        }
        e.tags?.let { tags(sink, it) }
    }

    private fun sameBounds(a: Bounds?, b: Bounds?): Boolean =
        if (a == null || b == null) a == null && b == null
        else a.minLat.toRawBits() == b.minLat.toRawBits() && a.minLon.toRawBits() == b.minLon.toRawBits() &&
            a.maxLat.toRawBits() == b.maxLat.toRawBits() && a.maxLon.toRawBits() == b.maxLon.toRawBits()

    private fun position(sink: ByteSink, lat: Double, lon: Double) {
        val la = scale.fixed(lat)
        val lo = scale.fixed(lon)
        sink.zigzag(la - lastLat)
        sink.zigzag(lo - lastLon)
        lastLat = la
        lastLon = lo
    }

    private fun points(sink: ByteSink, p: Points, nulls: Boolean, countKnown: Boolean) {
        if (!countKnown) sink.varInt(p.size)
        if (nulls) {
            var n = 0
            for (i in 0 until p.size) if (p.isNull(i)) n++
            sink.varInt(n)
            var prev = -1
            for (i in 0 until p.size) if (p.isNull(i)) {
                sink.varInt(i - prev - 1)
                prev = i
            }
        }
        for (i in 0 until p.size) if (!p.isNull(i)) position(sink, p.lat[i], p.lon[i])
    }

    private fun members(sink: ByteSink, ms: List<Member>) {
        sink.varInt(ms.size)
        for (m in ms) {
            val g = m.geometry
            val nulls = g != null && g.hasNulls
            var head = m.type.ordinal
            if (m.hasPosition) head = head or 4
            if (g != null) head = head or 8
            if (nulls) head = head or 16
            sink.byte(head)
            sink.zigzag(m.ref - lastRef)
            lastRef = m.ref
            string(sink, m.role)
            if (m.hasPosition) position(sink, m.lat, m.lon)
            if (g != null) points(sink, g, nulls, countKnown = false)
        }
    }

    private fun tags(sink: ByteSink, t: Map<String, String>) {
        sink.varInt(t.size)
        for ((k, v) in t) {
            val ki = dict.indexOf(k)
            val vi = dict.indexOf(v)
            val pair = if (ki >= 0 && vi >= 0) dict.pairOf(ki, vi) else -1
            if (pair >= 0) sink.varInt(pair shl 1)
            else {
                sink.varInt(1)
                string(sink, k)
                string(sink, v)
            }
        }
    }

    private fun string(sink: ByteSink, s: String) {
        val i = dict.indexOf(s)
        if (i >= 0) sink.varInt(i shl 1)
        else {
            val b = s.toByteArray(Charsets.UTF_8)
            sink.varInt((b.size shl 1) or 1)
            sink.bytes(b)
        }
    }
}

/** Reads the elements of a chunk back. */
internal class ElementDecoder(private val dict: Dictionary, private val scale: Scale) {
    private var lastId = 0L
    private var lastLat = 0L
    private var lastLon = 0L
    private var lastNode = 0L
    private var lastRef = 0L

    /** All the elements of one inflated chunk, in the order they were written. */
    fun decodeChunk(raw: ByteArray, length: Int = raw.size): Array<OsmElement> {
        val src = ByteSource(raw, 0, length)
        lastId = 0; lastLat = 0; lastLon = 0; lastNode = 0; lastRef = 0
        val n = src.varInt()
        return Array(n) { element(src) }
    }

    private fun element(src: ByteSource): OsmElement {
        val head = src.byte()
        val type = ElementType.entries[head and TYPE_MASK]
        val id = lastId + src.zigzag()
        lastId = id
        var lat = Double.NaN
        var lon = Double.NaN
        var bounds: Bounds? = null
        var oddBounds = false
        var nodes: LongArray? = null
        var geometry: Points? = null
        var members: List<Member>? = null
        if (type == ElementType.NODE) {
            if (head and F_A != 0) {
                lastLat += src.zigzag()
                lastLon += src.zigzag()
                lat = scale.degrees(lastLat)
                lon = scale.degrees(lastLon)
            }
        } else if (head and F_ODD_BOUNDS != 0) {
            oddBounds = true
            if (src.byte() == 1) {
                val minLat = lastLat + src.zigzag()
                val minLon = lastLon + src.zigzag()
                val maxLat = minLat + src.zigzag()
                val maxLon = minLon + src.zigzag()
                lastLat = minLat
                lastLon = minLon
                bounds = Bounds(scale.degrees(minLat), scale.degrees(minLon), scale.degrees(maxLat), scale.degrees(maxLon))
            }
        }
        if (type == ElementType.WAY) {
            var count = -1
            if (head and F_A != 0) {
                count = src.varInt()
                val ns = LongArray(count)
                for (i in 0 until count) {
                    lastNode += src.zigzag()
                    ns[i] = lastNode
                }
                nodes = ns
            }
            if (head and F_B != 0) geometry = points(src, if (head and F_SAME != 0) count else -1, head and F_NULLS != 0)
        } else if (type == ElementType.RELATION && head and F_A != 0) {
            members = members(src)
        }
        val tags = if (head and F_TAGS != 0) tags(src) else null
        if (!oddBounds && type != ElementType.NODE) bounds = implied(geometry, members)
        return OsmElement(type, id, lat, lon, tags, bounds, nodes, geometry, members)
    }

    private fun implied(g: Points?, ms: List<Member>?): Bounds? {
        var minLat = Double.POSITIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        var any = false
        fun add(la: Double, lo: Double) {
            any = true
            if (la < minLat) minLat = la
            if (la > maxLat) maxLat = la
            if (lo < minLon) minLon = lo
            if (lo > maxLon) maxLon = lo
        }
        fun addAll(p: Points) {
            for (i in 0 until p.size) if (!p.isNull(i)) add(p.lat[i], p.lon[i])
        }
        g?.let(::addAll)
        ms?.forEach { m ->
            if (m.hasPosition) add(m.lat, m.lon)
            m.geometry?.let(::addAll)
        }
        return if (any) Bounds(minLat, minLon, maxLat, maxLon) else null
    }

    private fun points(src: ByteSource, known: Int, nulls: Boolean): Points {
        val n = if (known >= 0) known else src.varInt()
        val la = DoubleArray(n)
        val lo = DoubleArray(n)
        var nullAt = -1
        var nullsLeft = 0
        if (nulls) {
            nullsLeft = src.varInt()
            nullAt = if (nullsLeft > 0) src.varInt() else -1
        }
        for (i in 0 until n) {
            if (i == nullAt) {
                la[i] = Double.NaN
                lo[i] = Double.NaN
                nullsLeft--
                nullAt = if (nullsLeft > 0) i + 1 + src.varInt() else -1
                continue
            }
            lastLat += src.zigzag()
            lastLon += src.zigzag()
            la[i] = scale.degrees(lastLat)
            lo[i] = scale.degrees(lastLon)
        }
        return Points(la, lo)
    }

    private fun members(src: ByteSource): List<Member> {
        val n = src.varInt()
        val out = ArrayList<Member>(n)
        repeat(n) {
            val head = src.byte()
            val ref = lastRef + src.zigzag()
            lastRef = ref
            val role = string(src)
            var lat = Double.NaN
            var lon = Double.NaN
            if (head and 4 != 0) {
                lastLat += src.zigzag()
                lastLon += src.zigzag()
                lat = scale.degrees(lastLat)
                lon = scale.degrees(lastLon)
            }
            val g = if (head and 8 != 0) points(src, -1, head and 16 != 0) else null
            out += Member(ElementType.entries[head and TYPE_MASK], ref, role, lat, lon, g)
        }
        return out
    }

    private fun tags(src: ByteSource): Map<String, String> {
        val n = src.varInt()
        val t = LinkedHashMap<String, String>(if (n < 3) 4 else n * 4 / 3 + 1)
        repeat(n) {
            val r = src.varInt()
            if (r and 1 == 0) {
                val p = r ushr 1
                t[dict.strings[dict.pairKey[p]]] = dict.strings[dict.pairValue[p]]
            } else {
                val k = string(src)
                t[k] = string(src)
            }
        }
        return t
    }

    private fun string(src: ByteSource): String {
        val r = src.varInt()
        if (r and 1 == 0) return dict.strings[r ushr 1]
        val len = r ushr 1
        val s = String(src.bytes(len), Charsets.UTF_8)
        return s
    }
}
