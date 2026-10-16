package app.atlas.render.data

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.LocalFrame
import app.atlas.geo.PackedRTree
import app.atlas.geo.Shapes
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Elements that can be asked by box: a `.atlas` pack (v1 or v2) or an extract held in memory. */
interface ElementBoxes {
    /** The area whose elements it holds: an element meeting it is in the source. */
    val coverage: BBox

    /** Every element whose box meets [box] (edges touching count), each once. */
    fun query(box: BBox): List<OsmElement>
}

/** A `.atlas` pack as [ElementBoxes]: the box of its manifest, and its spatial index. */
class PackBoxes(val reader: PackReader) : ElementBoxes {
    override val coverage: BBox = reader.manifest.bbox

    override fun query(box: BBox): List<OsmElement> = reader.query(box)
}

/** Elements held in memory (an Overpass answer, a test extract) as [ElementBoxes], in an R-tree of their boxes. */
class MemoryBoxes(elements: Iterable<OsmElement>, coverage: BBox? = null) : ElementBoxes {
    private val boxed: Array<OsmElement>
    private val tree: PackedRTree
    override val coverage: BBox

    init {
        val list = ArrayList<OsmElement>()
        val boxes = ArrayList<BBox>()
        for (e in elements) {
            val b = PackFeatures.boxOf(e) ?: continue
            list += e
            boxes += b
        }
        boxed = list.toTypedArray()
        tree = PackedRTree(boxes.size)
        for (b in boxes) tree.add(b.west, b.south, b.east, b.north)
        tree.finish()
        this.coverage = coverage ?: boxes.reduceOrNull { a, b -> a.union(b) } ?: BBox(0.0, 0.0, 0.0, 0.0)
    }

    override fun query(box: BBox): List<OsmElement> {
        if (boxed.isEmpty()) return emptyList()
        val hits = tree.search(box.west, box.south, box.east, box.north)
        hits.sort()
        return List(hits.size) { boxed[hits[it]] }
    }
}

/** A named street or waterway as lines: a highway or waterway way, or a waterway relation's member ways. */
class StreetLine(val element: OsmElement, val name: String?, val ref: String?, val water: Boolean, val lines: List<Points>) {
    /** The label in [language] when mapped, else the name, else the ref ("A10"). */
    fun label(language: String? = null): String? = language?.let { element.tag("name:$it") } ?: name ?: ref
}

/**
 * An area's outline: closed rings as flat lon/lat pairs ([coords]), each ring starting at the point index in [starts];
 * [inner] marks the holes. [areaM2] is the outer rings' area less the holes'.
 */
class Outline(val element: OsmElement, val coords: DoubleArray, val starts: IntArray, val inner: BooleanArray, val areaM2: Double) {
    private val holeIndices: IntArray? = if (starts.size > 1) starts.copyOfRange(1, starts.size) else null

    /** True when [p] lies inside (even-odd over every ring; on an edge counts as inside). */
    fun contains(p: LatLon): Boolean = Shapes.contains(coords, holeIndices, p.lon, p.lat)

    /** A point well inside: the pole of inaccessibility of the largest outer ring. */
    fun label(): LatLon {
        var best = -1
        var bestArea = -1.0
        for (r in starts.indices) {
            if (inner[r]) continue
            val a = abs(ringArea(r))
            if (a > bestArea) { bestArea = a; best = r }
        }
        if (best < 0) best = 0
        val from = starts[best]
        val to = if (best + 1 < starts.size) starts[best + 1] else coords.size / 2
        val frame = LocalFrame(LatLon(coords[2 * from + 1], coords[2 * from]))
        val ring = DoubleArray((to - from) * 2)
        for (i in from until to) {
            ring[2 * (i - from)] = frame.x(coords[2 * i])
            ring[2 * (i - from) + 1] = frame.y(coords[2 * i + 1])
        }
        val p = Shapes.labelPoint(ring, null, 0.5)
        return frame.toLatLon(p[0], p[1])
    }

    private fun ringArea(r: Int): Double {
        val from = starts[r]
        val to = if (r + 1 < starts.size) starts[r + 1] else coords.size / 2
        return PackFeatures.ringAreaM2(coords, from, to)
    }
}

/** An element with an address (`addr:housenumber` with `addr:street` or `addr:place`), [distance] metres away (0 inside its outline). */
class AddressPoint(val element: OsmElement, val distance: Double, val outline: Outline?) {
    val street: String? get() = element.tag("addr:street") ?: element.tag("addr:place")
    val housenumber: String? get() = element.tag("addr:housenumber")

    /** Where the address stands: the node, or a point well inside its outline, else the middle of its box. */
    val at: LatLon by lazy { PackFeatures.pointOf(element, outline) ?: LatLon(Double.NaN, Double.NaN) }
}

/** A bridge over (or under) a line: the bridge's element, metres along the line, and the crossing point. */
class BridgeCrossing(val element: OsmElement, val along: Double, val at: LatLon)

/**
 * Atlas's own OpenStreetMap elements asked for the features rather than drawn: the elements in a box, the named
 * streets and canals with the street namer's along-travel scoring, the outlines holding a point, the nearest
 * addresses and the bridges a line passes. Over any number of [sources] (the bundled pack, the renders' packs, city and
 * country packs, packs built from Overpass); an element in several comes back once, from the first source holding it.
 * Thread-safe when the sources are (a [PackReader] is).
 */
class PackFeatures(val sources: List<ElementBoxes>) {
    /** True when a source covers [p]. */
    fun covers(p: LatLon): Boolean = sources.any { p in it.coverage }

    /** True when one source covers the whole of [box]. */
    fun covers(box: BBox): Boolean = sources.any { c -> c.coverage.let { box.west >= it.west && box.east <= it.east && box.south >= it.south && box.north <= it.north } }

    /** The elements whose box meets [box] that [accept] keeps, each once, in canonical order (nodes, ways, relations; by id). */
    fun elements(box: BBox, accept: (OsmElement) -> Boolean = { true }): List<OsmElement> {
        val out = ArrayList<OsmElement>()
        var seen: HashSet<Long>? = null
        var many = 0
        for (s in sources) {
            if (!s.coverage.intersects(box)) continue
            many++
            val found = s.query(box)
            if (many > 1 && seen == null) seen = out.mapTo(HashSet(out.size * 2)) { key(it) }
            for (e in found) {
                if (!accept(e)) continue
                if (seen != null && !seen.add(key(e))) continue
                out += e
            }
        }
        out.sortWith(OsmElement.CANONICAL)
        return out
    }

    /** The named (or numbered) streets and the named waterways meeting [box]. */
    fun streets(box: BBox): List<StreetLine> = elements(box, ::isStreet).mapNotNull(::streetOf)

    /**
     * The name of the street (or canal) at [at] within [withinM] metres: the one along [bearing] wins, a street that
     * merely crosses there only when nothing runs along (within [ALONG_DEG]); with no bearing, the nearest.
     */
    fun streetName(at: LatLon, bearing: Double?, withinM: Double = NEAR_M, language: String? = null): String? {
        val frame = LocalFrame(at)
        var best: String? = null
        var bestScore = Double.MAX_VALUE
        for (s in streets(BBox.around(at, withinM))) {
            val label = s.label(language) ?: continue
            for (line in s.lines) {
                for (i in 0 until line.size - 1) {
                    if (line.isNull(i) || line.isNull(i + 1)) continue
                    val ax = frame.x(line.lon[i]); val ay = frame.y(line.lat[i])
                    val bx = frame.x(line.lon[i + 1]); val by = frame.y(line.lat[i + 1])
                    val d = segmentDistance(ax, ay, bx, by)
                    if (d > withinM) continue
                    val score = if (bearing == null) d else {
                        val segBearing = Math.toDegrees(atan2(bx - ax, by - ay))
                        val diff = ((segBearing - bearing) % 180 + 180) % 180
                        val off = min(diff, 180 - diff)
                        d + if (off <= ALONG_DEG) 0.0 else withinM * 2
                    }
                    if (score < bestScore) {
                        bestScore = score
                        best = label
                    }
                }
            }
        }
        return best
    }

    /** The areas whose outline holds [at] that [accept] keeps (buildings, parks, squares, quarters), smallest first. */
    fun containing(at: LatLon, accept: (OsmElement) -> Boolean = { true }): List<Outline> {
        val out = ArrayList<Outline>()
        for (e in elements(BBox(at.lon, at.lat, at.lon, at.lat))) {
            if (!accept(e)) continue
            val o = outlineOf(e) ?: continue
            if (o.contains(at)) out += o
        }
        out.sortWith(compareBy<Outline> { it.areaM2 }.thenComparator { a, b -> OsmElement.CANONICAL.compare(a.element, b.element) })
        return out
    }

    /** The [n] addressed elements nearest [at] within [withinM] metres (0 inside their outline), nearest first. */
    fun addressesNear(at: LatLon, n: Int, withinM: Double = ADDRESS_M): List<AddressPoint> {
        if (n <= 0) return emptyList()
        val found = ArrayList<AddressPoint>()
        for (e in elements(BBox.around(at, withinM), ::isAddress)) {
            val outline = if (e.type == ElementType.NODE) null else outlineOf(e)
            val d = if (outline != null && outline.contains(at)) 0.0 else distance(e, at)
            if (d <= withinM) found += AddressPoint(e, d, outline)
        }
        found.sortWith(compareBy<AddressPoint> { it.distance }.thenComparator { a, b -> OsmElement.CANONICAL.compare(a.element, b.element) })
        return if (found.size > n) found.subList(0, n).toList() else found
    }

    /**
     * The address of the place at [at]: the building whose outline holds it, by its own address or (where addresses
     * are mapped as points inside buildings, as in the Netherlands) the nearest address point inside it; else the
     * nearest addressed element within [withinM]. Null when none is that near.
     */
    fun addressAt(at: LatLon, withinM: Double = ADDRESS_M): AddressPoint? {
        val building = containing(at) { e -> e.tag("building").let { it != null && it != "no" } }.firstOrNull()
        if (building != null) {
            if (isAddress(building.element)) return AddressPoint(building.element, 0.0, building)
            val b = building.element.let { boxOf(it) }
            if (b != null) {
                val inside = elements(b) { it.type == ElementType.NODE && isAddress(it) }
                    .filter { building.contains(LatLon(it.lat, it.lon)) }
                    .minWithOrNull(compareBy<OsmElement> { Geo.distance(at.lat, at.lon, it.lat, it.lon) }.then(OsmElement.CANONICAL))
                if (inside != null) return AddressPoint(inside, 0.0, null)
            }
        }
        return addressesNear(at, 1, withinM).firstOrNull()
    }

    /**
     * The bridges [line] passes (under, for a boat; over, for a road under a viaduct), in order along it: every way
     * tagged `bridge` (not `no`) that crosses it, and every `man_made=bridge` outline it passes through (once, at the
     * middle of its pass). Bridges mapped as several ways (a road and its pavements) come once per way.
     */
    fun bridges(line: List<LatLon>): List<BridgeCrossing> {
        val n = line.size
        if (n < 2) return emptyList()
        val frame = LocalFrame(line[0])
        val x = DoubleArray(n) { frame.x(line[it].lon) }
        val y = DoubleArray(n) { frame.y(line[it].lat) }
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + Geo.distance(line[i - 1], line[i])
        // Ask by stretches: a long route's whole box would hold a country.
        val seen = HashSet<Long>()
        val candidates = ArrayList<OsmElement>()
        var start = 0
        while (start < n - 1) {
            var end = start + 1
            while (end < n - 1 && cum[end] - cum[start] < STRETCH_M) end++
            var box = BBox(line[start].lon, line[start].lat, line[start].lon, line[start].lat)
            for (i in start + 1..end) box = box.extend(line[i])
            for (e in elements(box.buffer(2.0), ::isBridge)) if (seen.add(key(e))) candidates += e
            start = end
        }
        val out = ArrayList<BridgeCrossing>()
        val hits = ArrayList<Double>()
        for (e in candidates) {
            hits.clear()
            for (part in linesOf(e)) crossings(part, frame, x, y, cum, hits)
            if (hits.isEmpty()) continue
            hits.sort()
            if (isAreaBridge(e)) {
                // An outline is entered and left: one bridge at the middle of each pass.
                var i = 0
                while (i < hits.size) {
                    val a = if (i + 1 < hits.size) (hits[i] + hits[i + 1]) / 2 else hits[i]
                    out += BridgeCrossing(e, a, pointAlong(line, cum, a))
                    i += 2
                }
            } else {
                for (a in hits) out += BridgeCrossing(e, a, pointAlong(line, cum, a))
            }
        }
        out.sortWith(compareBy<BridgeCrossing> { it.along }.thenComparator { a, b -> OsmElement.CANONICAL.compare(a.element, b.element) })
        return out
    }

    private fun crossings(part: Points, frame: LocalFrame, x: DoubleArray, y: DoubleArray, cum: DoubleArray, into: MutableList<Double>) {
        for (k in 0 until part.size - 1) {
            if (part.isNull(k) || part.isNull(k + 1)) continue
            val qx1 = frame.x(part.lon[k]); val qy1 = frame.y(part.lat[k])
            val qx2 = frame.x(part.lon[k + 1]); val qy2 = frame.y(part.lat[k + 1])
            val minX = min(qx1, qx2); val maxX = max(qx1, qx2); val minY = min(qy1, qy2); val maxY = max(qy1, qy2)
            for (j in 0 until x.size - 1) {
                if (max(x[j], x[j + 1]) < minX || min(x[j], x[j + 1]) > maxX || max(y[j], y[j + 1]) < minY || min(y[j], y[j + 1]) > maxY) continue
                val t = intersect(x[j], y[j], x[j + 1], y[j + 1], qx1, qy1, qx2, qy2)
                if (t >= 0) into += cum[j] + t * (cum[j + 1] - cum[j])
            }
        }
    }

    companion object {
        /** How far a street may be from the point the street namer is asked about (metres). */
        const val NEAR_M = 25.0

        /** A street within this many degrees of the way of travel runs along it. */
        const val ALONG_DEG = 40.0

        /** How far an address may be from the point asked about (metres). */
        const val ADDRESS_M = 40.0

        private const val STRETCH_M = 500.0

        /** The OSM id of an element. */
        fun osmId(e: OsmElement): OsmId = OsmId(
            when (e.type) {
                ElementType.NODE -> OsmType.NODE
                ElementType.WAY -> OsmType.WAY
                ElementType.RELATION -> OsmType.RELATION
            },
            e.id,
        )

        /** The box of an element: a node's point, a way's or relation's bounds (else its geometry's box); null without a position. */
        fun boxOf(e: OsmElement): BBox? {
            if (e.type == ElementType.NODE) return if (e.lat.isNaN()) null else BBox(e.lon, e.lat, e.lon, e.lat)
            e.bounds?.let { return BBox(it.minLon, it.minLat, it.maxLon, it.maxLat) }
            var b: BBox? = null
            fun add(p: Points) {
                for (i in 0 until p.size) if (!p.isNull(i)) b = b?.extend(LatLon(p.lat[i], p.lon[i])) ?: BBox(p.lon[i], p.lat[i], p.lon[i], p.lat[i])
            }
            e.geometry?.let(::add)
            e.members?.forEach { m ->
                m.geometry?.let(::add)
                if (m.hasPosition) b = b?.extend(LatLon(m.lat, m.lon)) ?: BBox(m.lon, m.lat, m.lon, m.lat)
            }
            return b
        }

        /**
         * The element as a core [Place], the way the place store imports an Overpass extract: a node at its point, a way
         * or relation at the middle of its box, with the box. Every tag is kept. Null without tags or a position.
         */
        fun place(e: OsmElement): Place? {
            val tags = e.tags?.takeIf { it.isNotEmpty() } ?: return null
            val box = boxOf(e) ?: return null
            return if (e.type == ElementType.NODE) Place(osmId(e), LatLon(e.lat, e.lon), tags)
            else Place(osmId(e), box.center, tags, box)
        }

        /** Where an element stands: a node's point, a point well inside an area, else the middle of its box. */
        fun pointOf(e: OsmElement, outline: Outline? = if (e.type == ElementType.NODE) null else outlineOf(e)): LatLon? {
            if (e.type == ElementType.NODE) return if (e.lat.isNaN()) null else LatLon(e.lat, e.lon)
            outline?.let { return it.label() }
            return boxOf(e)?.center
        }

        /** Metres from [at] to the element: 0 inside its outline, else to its nearest edge, line or point. */
        fun distance(e: OsmElement, at: LatLon): Double {
            if (e.type == ElementType.NODE) return if (e.lat.isNaN()) Double.MAX_VALUE else Geo.distance(at.lat, at.lon, e.lat, e.lon)
            outlineOf(e)?.let { if (it.contains(at)) return 0.0 }
            val frame = LocalFrame(at)
            var best = Double.MAX_VALUE
            for (part in linesOf(e)) {
                if (part.size == 1 && !part.isNull(0)) best = min(best, Geo.distance(at.lat, at.lon, part.lat[0], part.lon[0]))
                for (i in 0 until part.size - 1) {
                    if (part.isNull(i) || part.isNull(i + 1)) continue
                    best = min(best, segmentDistance(frame.x(part.lon[i]), frame.y(part.lat[i]), frame.x(part.lon[i + 1]), frame.y(part.lat[i + 1])))
                }
            }
            e.members?.forEach { m -> if (m.geometry == null && m.hasPosition) best = min(best, Geo.distance(at.lat, at.lon, m.lat, m.lon)) }
            return best
        }

        /** The lines of an element: a way's geometry, or a relation's member ways' geometries. */
        fun linesOf(e: OsmElement): List<Points> = when (e.type) {
            ElementType.NODE -> emptyList()
            ElementType.WAY -> listOfNotNull(e.geometry)
            ElementType.RELATION -> e.members.orEmpty().mapNotNull { m -> m.geometry.takeIf { m.type == ElementType.WAY } }
        }

        /**
         * The outline of an area: a closed way that is an area by its tags (a building, a park; not a closed road or
         * fence unless `area=yes`), or a multipolygon (or boundary) relation whose member ways close into rings. Null
         * otherwise, and for a relation whose members the extract cut off.
         */
        fun outlineOf(e: OsmElement): Outline? = when (e.type) {
            ElementType.NODE -> null
            ElementType.WAY -> {
                val g = e.geometry
                if (g == null || g.size < 4 || g.hasNulls || !isAreaWay(e.tags) || g.lat[0] != g.lat[g.size - 1] || g.lon[0] != g.lon[g.size - 1]) null
                else outline(e, listOf(g), booleanArrayOf(false))
            }
            ElementType.RELATION -> {
                val t = e.tags?.get("type")
                if (t != "multipolygon" && t != "boundary") null else multipolygon(e)
            }
        }

        private fun multipolygon(e: OsmElement): Outline? {
            val outer = ArrayList<Points>()
            val inner = ArrayList<Points>()
            for (m in e.members.orEmpty()) {
                if (m.type != ElementType.WAY) continue
                if (m.role != "outer" && m.role != "inner" && m.role != "") continue
                val g = m.geometry ?: return null
                if (g.size < 2 || g.hasNulls) return null
                if (m.role == "inner") inner += g else outer += g
            }
            val outerRings = rings(outer) ?: return null
            val innerRings = rings(inner) ?: return null
            if (outerRings.isEmpty()) return null
            return outline(e, outerRings + innerRings, BooleanArray(outerRings.size + innerRings.size) { it >= outerRings.size })
        }

        /** Joins open ways end to end into closed rings; null when some do not close (members missing). */
        private fun rings(parts: List<Points>): List<Points>? {
            val done = ArrayList<Points>()
            val open = ArrayList<Points>()
            for (p in parts) if (closed(p)) done += p else open += p
            while (open.isNotEmpty()) {
                val ring = Run(open.removeAt(open.size - 1))
                while (!ring.closed) {
                    val k = open.indexOfFirst { ring.meets(it) }
                    if (k < 0) return null
                    ring.append(open.removeAt(k))
                }
                if (ring.size >= 4) done += ring.points()
            }
            return done
        }

        /** A run of ways being joined into a ring, in growable primitive arrays. */
        private class Run(first: Points) {
            private var lat = first.lat.copyOf(max(16, first.size * 2))
            private var lon = first.lon.copyOf(lat.size)
            var size = first.size
                private set

            val closed: Boolean get() = lat[0] == lat[size - 1] && lon[0] == lon[size - 1]

            /** True when [p] starts or ends where the run ends. */
            fun meets(p: Points): Boolean = (p.lat[0] == lat[size - 1] && p.lon[0] == lon[size - 1]) ||
                (p.lat[p.size - 1] == lat[size - 1] && p.lon[p.size - 1] == lon[size - 1])

            /** Adds [p] after the run's end (reversed when it ends there), without repeating the shared point. */
            fun append(p: Points) {
                val forward = p.lat[0] == lat[size - 1] && p.lon[0] == lon[size - 1]
                if (size + p.size > lat.size) {
                    lat = lat.copyOf(max(lat.size * 2, size + p.size))
                    lon = lon.copyOf(lat.size)
                }
                for (i in 1 until p.size) {
                    val j = if (forward) i else p.size - 1 - i
                    lat[size] = p.lat[j]
                    lon[size] = p.lon[j]
                    size++
                }
            }

            fun points(): Points = Points(lat.copyOf(size), lon.copyOf(size))
        }

        private fun closed(p: Points): Boolean = p.size >= 4 && p.lat[0] == p.lat[p.size - 1] && p.lon[0] == p.lon[p.size - 1]

        private fun outline(e: OsmElement, rings: List<Points>, inner: BooleanArray): Outline {
            var total = 0
            for (r in rings) total += r.size
            val coords = DoubleArray(total * 2)
            val starts = IntArray(rings.size)
            var at = 0
            var area = 0.0
            for ((k, r) in rings.withIndex()) {
                starts[k] = at
                for (i in 0 until r.size) {
                    coords[2 * (at + i)] = r.lon[i]
                    coords[2 * (at + i) + 1] = r.lat[i]
                }
                val a = abs(ringAreaM2(coords, at, at + r.size))
                area += if (inner[k]) -a else a
                at += r.size
            }
            return Outline(e, coords, starts, inner, max(area, 0.0))
        }

        /** The signed area (m²) of the ring of points [from] until [to] in flat lon/lat [coords]. */
        internal fun ringAreaM2(coords: DoubleArray, from: Int, to: Int): Double {
            if (to - from < 3) return 0.0
            val lat0 = coords[2 * from + 1]
            val kx = Geo.EARTH_RADIUS * Math.PI / 180 * cos(Math.toRadians(lat0))
            val ky = Geo.EARTH_RADIUS * Math.PI / 180
            val x0 = coords[2 * from]
            var s = 0.0
            for (i in from until to) {
                val j = if (i + 1 < to) i + 1 else from
                val xi = (coords[2 * i] - x0) * kx; val yi = (coords[2 * i + 1] - lat0) * ky
                val xj = (coords[2 * j] - x0) * kx; val yj = (coords[2 * j + 1] - lat0) * ky
                s += xi * yj - xj * yi
            }
            return s / 2
        }

        /** Tags that make a closed way an area. */
        fun isAreaWay(tags: Map<String, String>?): Boolean {
            if (tags.isNullOrEmpty()) return false
            tags["area"]?.let { return it != "no" }
            if ("highway" in tags || "barrier" in tags) return false
            tags["waterway"]?.let { return it in AREA_WATERWAYS }
            tags["railway"]?.let { if (it != "platform" && it != "station") return false }
            if (tags["natural"] in LINE_NATURAL || tags["man_made"] in LINE_MAN_MADE || tags["power"] == "line") return false
            return true
        }

        /** A street or waterway the namer knows: a highway line with a name or ref, or a named waterway line or relation. */
        fun isStreet(e: OsmElement): Boolean {
            val t = e.tags ?: return false
            return when (e.type) {
                ElementType.NODE -> false
                ElementType.WAY -> {
                    val h = t["highway"]
                    if (h != null) h !in NOT_STREETS && t["area"] != "yes" && ("name" in t || "ref" in t)
                    else t["waterway"]?.let { it !in AREA_WATERWAYS && "name" in t } ?: false
                }
                ElementType.RELATION -> "waterway" in t && "name" in t && t["type"] == "waterway"
            }
        }

        private fun streetOf(e: OsmElement): StreetLine? {
            val t = e.tags ?: return null
            val lines = linesOf(e).filter { it.size >= 2 }
            if (lines.isEmpty()) return null
            return StreetLine(e, t["name"], t["ref"], "highway" !in t, lines)
        }

        /** An element with a house number on a street or place. */
        fun isAddress(e: OsmElement): Boolean {
            val t = e.tags ?: return false
            return "addr:housenumber" in t && ("addr:street" in t || "addr:place" in t)
        }

        /** A bridge: a way tagged `bridge` (any value but `no`), or a `man_made=bridge` outline. */
        fun isBridge(e: OsmElement): Boolean {
            val t = e.tags ?: return false
            if (e.type == ElementType.NODE) return false
            if (isAreaBridge(e)) return true
            return e.type == ElementType.WAY && t["bridge"].let { it != null && it != "no" }
        }

        private fun isAreaBridge(e: OsmElement): Boolean = e.type != ElementType.NODE && e.tags?.get("man_made") == "bridge"

        /** The key of an element across sources. */
        fun key(e: OsmElement): Long = (e.id shl 2) or e.type.ordinal.toLong()

        /** Metres from the origin to the segment a–b. */
        private fun segmentDistance(ax: Double, ay: Double, bx: Double, by: Double): Double {
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val x = ax + t * dx
            val y = ay + t * dy
            return sqrt(x * x + y * y)
        }

        /** Where segment p (p1→p2) crosses segment q (q1→q2), as a fraction 0..1 of p; −1 when they do not cross. */
        fun intersect(p1x: Double, p1y: Double, p2x: Double, p2y: Double, q1x: Double, q1y: Double, q2x: Double, q2y: Double): Double {
            val rx = p2x - p1x; val ry = p2y - p1y
            val sx = q2x - q1x; val sy = q2y - q1y
            val d = rx * sy - ry * sx
            if (d == 0.0) return -1.0
            val qpx = q1x - p1x; val qpy = q1y - p1y
            val t = (qpx * sy - qpy * sx) / d
            val u = (qpx * ry - qpy * rx) / d
            return if (t in 0.0..1.0 && u in 0.0..1.0) t else -1.0
        }

        private fun pointAlong(line: List<LatLon>, cum: DoubleArray, along: Double): LatLon {
            var i = 1
            while (i < cum.size - 1 && cum[i] < along) i++
            val seg = cum[i] - cum[i - 1]
            val t = if (seg <= 0) 0.0 else ((along - cum[i - 1]) / seg).coerceIn(0.0, 1.0)
            return Geo.interpolate(line[i - 1], line[i], t)
        }

        private val AREA_WATERWAYS = setOf("riverbank", "dock", "boatyard", "fuel")
        private val LINE_NATURAL = setOf("coastline", "tree_row", "cliff", "ridge", "arete")
        private val LINE_MAN_MADE = setOf("embankment", "pipeline", "breakwater", "groyne", "cutline")

        /** Highways that are not streets to name: platforms, and roads not yet built or long gone. */
        private val NOT_STREETS = setOf("platform", "proposed", "construction", "abandoned", "disused", "razed")
    }
}
