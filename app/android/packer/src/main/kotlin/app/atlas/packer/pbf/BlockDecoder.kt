package app.atlas.packer.pbf

import app.atlas.tiles.ProtoReader

/**
 * Decodes inflated `PrimitiveBlock`s into [OsmHandler] calls: dense and plain nodes, ways and relations, with the
 * block's granularity, offsets and date granularity applied. Groups of kinds not in [kinds] are skipped unread, and a
 * block with none of them is not even given its string table.
 */
internal class BlockDecoder(private val handler: OsmHandler, private val kinds: Int) {
    private val node = PbfNode()
    private val way = PbfWay()
    private val relation = PbfRelation()
    private var strings = arrayOfNulls<String>(1024)
    private var table: Array<String> = emptyArray()
    private var groupStarts = IntArray(8)
    private var groupEnds = IntArray(8)
    private var groups = 0
    private val keyIds = IntList()
    private val valueIds = IntList()

    private var granularity = 100L
    private var latOffset = 0L
    private var lonOffset = 0L
    private var dateGranularity = 1000L

    fun decode(data: ByteArray) {
        val r = ProtoReader(data)
        var tableStart = -1
        var tableEnd = -1
        granularity = 100
        latOffset = 0
        lonOffset = 0
        dateGranularity = 1000
        groups = 0
        while (r.next()) when (r.field) {
            1 -> { val n = r.length(); tableStart = r.pos; tableEnd = tableStart + n; r.pos = tableEnd }
            2 -> { val n = r.length(); addGroup(r.pos, r.pos + n); r.pos += n }
            17 -> granularity = r.int32().toLong()
            18 -> dateGranularity = r.int32().toLong()
            19 -> latOffset = r.int64()
            20 -> lonOffset = r.int64()
            else -> r.skip()
        }
        var wanted = false
        for (g in 0 until groups) if (kindOf(data, groupStarts[g], groupEnds[g]) and kinds != 0) wanted = true
        if (!wanted) return
        readStrings(data, tableStart, tableEnd)
        for (g in 0 until groups) {
            val start = groupStarts[g]
            val end = groupEnds[g]
            if (kindOf(data, start, end) and kinds == 0) continue
            group(data, start, end)
        }
    }

    private fun addGroup(start: Int, end: Int) {
        if (groups == groupStarts.size) {
            groupStarts = groupStarts.copyOf(groups * 2)
            groupEnds = groupEnds.copyOf(groups * 2)
        }
        groupStarts[groups] = start
        groupEnds[groups] = end
        groups++
    }

    /** The kind a `PrimitiveGroup` holds, from its first field (a group holds one kind only). */
    private fun kindOf(data: ByteArray, start: Int, end: Int): Int {
        if (start >= end) return 0
        val r = ProtoReader(data, start, end)
        r.next()
        return when (r.field) {
            1, 2 -> PbfReader.NODES
            3 -> PbfReader.WAYS
            4 -> PbfReader.RELATIONS
            else -> 0
        }
    }

    private fun readStrings(data: ByteArray, start: Int, end: Int) {
        var n = 0
        if (start >= 0) {
            val r = ProtoReader(data, start, end)
            while (r.next()) {
                if (r.field != 1) { r.skip(); continue }
                if (n == strings.size) strings = strings.copyOf(n * 2)
                strings[n++] = r.string()
            }
        }
        val s = strings
        table = Array(n) { s[it]!! }
        node.tags.strings = table
        way.tags.strings = table
        relation.tags.strings = table
    }

    private fun group(data: ByteArray, start: Int, end: Int) {
        val r = ProtoReader(data, start, end)
        while (r.next()) {
            if (r.wireType != ProtoReader.WIRE_LENGTH) { r.skip(); continue }
            val n = r.length()
            val s = r.pos
            r.pos = s + n
            when (r.field) {
                1 -> plainNode(data, s, s + n)
                2 -> dense(data, s, s + n)
                3 -> way(data, s, s + n)
                4 -> relation(data, s, s + n)
                else -> {}
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- nodes

    private fun dense(data: ByteArray, start: Int, end: Int) {
        val r = ProtoReader(data, start, end)
        var ids: ProtoReader? = null
        var lats: ProtoReader? = null
        var lons: ProtoReader? = null
        var kv: ProtoReader? = null
        var versions: ProtoReader? = null
        var times: ProtoReader? = null
        while (r.next()) {
            if (r.wireType != ProtoReader.WIRE_LENGTH) { r.skip(); continue }
            val n = r.length()
            val s = r.pos
            r.pos = s + n
            when (r.field) {
                1 -> ids = ProtoReader(data, s, s + n)
                8 -> lats = ProtoReader(data, s, s + n)
                9 -> lons = ProtoReader(data, s, s + n)
                10 -> kv = ProtoReader(data, s, s + n)
                5 -> {
                    val info = ProtoReader(data, s, s + n)
                    while (info.next()) {
                        if (info.wireType != ProtoReader.WIRE_LENGTH) { info.skip(); continue }
                        val m = info.length()
                        val p = info.pos
                        info.pos = p + m
                        when (info.field) {
                            1 -> versions = ProtoReader(data, p, p + m)
                            2 -> times = ProtoReader(data, p, p + m)
                        }
                    }
                }
            }
        }
        if (ids == null) return
        if (lats == null || lons == null) throw PbfFormatException("dense nodes without coordinates")
        var id = 0L
        var lat = 0L
        var lon = 0L
        var time = 0L
        val tags = node.tags
        while (ids.hasMore) {
            id += ids.sint64()
            if (!lats.hasMore || !lons.hasMore) throw PbfFormatException("dense nodes with fewer coordinates than ids")
            lat += lats.sint64()
            lon += lons.sint64()
            node.version = if (versions != null && versions.hasMore) versions.int32() else 0
            if (times != null && times.hasMore) time += times.sint64()
            tags.clear()
            if (kv != null) {
                while (kv.hasMore) {
                    val k = kv.int32()
                    if (k == 0) break
                    if (!kv.hasMore) throw PbfFormatException("dense node $id has a key without a value")
                    tags.add(k, kv.int32())
                }
            }
            node.id = id
            node.latNano = latOffset + granularity * lat
            node.lonNano = lonOffset + granularity * lon
            node.timestampMillis = time * dateGranularity
            handler.node(node)
        }
    }

    private fun plainNode(data: ByteArray, start: Int, end: Int) {
        val r = ProtoReader(data, start, end)
        node.tags.clear()
        node.version = 0
        node.timestampMillis = 0
        keyIds.clear()
        valueIds.clear()
        var lat = 0L
        var lon = 0L
        var id = 0L
        while (r.next()) when (r.field) {
            1 -> id = r.sint64()
            2 -> repeated(r) { keyIds.add(it.toInt()) }
            3 -> repeated(r) { valueIds.add(it.toInt()) }
            4 -> info(r, node)
            8 -> lat = r.sint64()
            9 -> lon = r.sint64()
            else -> r.skip()
        }
        tags(node)
        node.id = id
        node.latNano = latOffset + granularity * lat
        node.lonNano = lonOffset + granularity * lon
        handler.node(node)
    }

    // ------------------------------------------------------------------------------------------------- ways

    private fun way(data: ByteArray, start: Int, end: Int) {
        val r = ProtoReader(data, start, end)
        way.tags.clear()
        way.version = 0
        way.timestampMillis = 0
        way.refCount = 0
        way.clearLocations()
        keyIds.clear()
        valueIds.clear()
        var ref = 0L
        // LocationsOnWays: lat and lon per ref, delta coded, in the block's granularity and offsets
        var lat = 0L
        var lon = 0L
        var lats = 0
        var lons = 0
        while (r.next()) when (r.field) {
            1 -> way.id = r.int64()
            2 -> repeated(r) { keyIds.add(it.toInt()) }
            3 -> repeated(r) { valueIds.add(it.toInt()) }
            4 -> info(r, way)
            8 -> repeated(r, signed = true) { ref += it; way.addRef(ref) }
            9 -> repeated(r, signed = true) { lat += it; way.setLocation(lats++, latOffset + granularity * lat, isLat = true) }
            10 -> repeated(r, signed = true) { lon += it; way.setLocation(lons++, lonOffset + granularity * lon, isLat = false) }
            else -> r.skip()
        }
        if ((lats > 0 || lons > 0) && (lats != way.refCount || lons != way.refCount)) {
            throw PbfFormatException("way ${way.id} has ${way.refCount} nodes, $lats latitudes and $lons longitudes")
        }
        way.locationsRead(lats, lons)
        tags(way)
        handler.way(way)
    }

    // ------------------------------------------------------------------------------------------------- relations

    private fun relation(data: ByteArray, start: Int, end: Int) {
        val r = ProtoReader(data, start, end)
        val rel = relation
        rel.tags.clear()
        rel.version = 0
        rel.timestampMillis = 0
        keyIds.clear()
        valueIds.clear()
        var roles = 0
        var ids = 0
        var types = 0
        var memberId = 0L
        while (r.next()) when (r.field) {
            1 -> rel.id = r.int64()
            2 -> repeated(r) { keyIds.add(it.toInt()) }
            3 -> repeated(r) { valueIds.add(it.toInt()) }
            4 -> info(r, rel)
            8 -> repeated(r) { v -> rel.ensure(roles + 1); rel.roles[roles++] = v.toInt() }
            9 -> repeated(r, signed = true) { v -> memberId += v; rel.ensure(ids + 1); rel.ids[ids++] = memberId }
            10 -> repeated(r) { v -> rel.ensure(types + 1); rel.types[types++] = v.toByte() }
            else -> r.skip()
        }
        if (roles != ids || types != ids) throw PbfFormatException("relation ${rel.id} has $ids members, $roles roles and $types types")
        for (i in 0 until ids) {
            if (rel.roles[i] !in table.indices) throw PbfFormatException("relation ${rel.id} has a role past the string table")
            if (rel.types[i].toInt() !in 0..2) throw PbfFormatException("relation ${rel.id} has a member of type ${rel.types[i]}")
        }
        rel.memberCount = ids
        tags(rel)
        handler.relation(rel)
    }

    // ------------------------------------------------------------------------------------------------- shared

    private fun info(r: ProtoReader, e: PbfElement) {
        val old = r.pushLimit(r.length())
        while (r.next()) when (r.field) {
            1 -> e.version = r.int32()
            2 -> e.timestampMillis = r.int64() * dateGranularity
            else -> r.skip()
        }
        r.popLimit(old)
    }

    private fun tags(e: PbfElement) {
        if (keyIds.size != valueIds.size) throw PbfFormatException("element ${e.id} has ${keyIds.size} keys and ${valueIds.size} values")
        for (i in 0 until keyIds.size) e.tags.add(keyIds[i], valueIds[i])
    }

    /** Calls [block] with each value of a repeated varint field, packed or not, zigzag-decoded when [signed]. */
    private inline fun repeated(r: ProtoReader, signed: Boolean = false, block: (Long) -> Unit) {
        if (r.wireType == ProtoReader.WIRE_LENGTH) {
            val old = r.pushLimit(r.length())
            while (r.hasMore) block(if (signed) r.sint64() else r.varint())
            r.popLimit(old)
        } else {
            block(if (signed) r.sint64() else r.varint())
        }
    }
}

/** A growable int array, reused element after element. */
internal class IntList {
    private var a = IntArray(32)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int): Int = a[i]

    fun clear() {
        size = 0
    }
}
