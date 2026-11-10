package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.geo.TileId
import app.atlas.places.Text
import app.atlas.providers.OpenAddresses
import app.atlas.providers.PmTiles
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import kotlin.math.cos

/**
 * Adds to a region's store the house numbers OpenStreetMap does not have, from OpenAddresses ([OpenAddresses]): the
 * sources that cover the region, their finest tiles in it, and of their points those whose number the store does not
 * hold yet on that street nearby ([Known]). Before the writer finishes, so the streets, statistics and vocabulary count
 * them.
 *
 * A tile is read only where the store has fewer numbers than the source: one where it holds about as many ([COMPLETE])
 * is passed over by the size of its bytes, so where OpenStreetMap is complete (the Netherlands, Norway) next to nothing
 * but the sources' directories is fetched. A tile is read only where the store holds something of its own on or beside
 * it, too: the Netherlands' box reaches Antwerp and Aachen, whose addresses would make the store seem to hold those
 * cities ([covers]). Tiles that lie together are read together, a megabyte or two at a time.
 *
 * Nothing it meets fails the region: a source that cannot be read is passed over, and a fill cut short keeps what it
 * added ([Stats.failure] says why). The store's "openaddresses" metadata then lists the sources its points came from,
 * with each one's licence and credit ([AddressCredits]).
 */
class AddressFill(private val addresses: OpenAddresses) {
    /**
     * What a fill did: the sources with tiles in the region, their tiles there, those read and those passed over as
     * complete, the bytes read, the points read in the region and those added; why it stopped short, if it did.
     */
    data class Stats(
        val sources: Int = 0,
        val tiles: Int = 0,
        val read: Int = 0,
        val complete: Int = 0,
        val bytes: Long = 0,
        val points: Int = 0,
        val added: Int = 0,
        val failure: String? = null,
    )

    private class Opened(val source: OpenAddresses.Source, val tiles: PmTiles, val spans: List<Pair<TileId, PmTiles.Span>>)

    /** Fills [writer]'s store with the numbers in [box] it lacks; [progress] hears tiles done of all. */
    suspend fun fill(box: BBox, writer: StoreWriter, progress: (done: Int, of: Int) -> Unit = { _, _ -> }): Stats {
        val store = writer.store
        val sources = try {
            addresses.sources(box)
        } catch (e: IOException) {
            return Stats(failure = why(e))
        }
        var failure: String? = null
        val counts = store.locked { Counts.of(store, Z) }
        val opened = ArrayList<Opened>()
        for (s in sources) {
            currentCoroutineContext().ensureActive()
            try {
                val tiles = addresses.tiles(s)
                val h = tiles.header
                if (h.tileType != PmTiles.MVT || h.maxZoom != Z) continue
                val area = intersection(box, s.bounds ?: box)?.let { intersection(it, h.bounds) } ?: continue
                val spans = ArrayList<Pair<TileId, PmTiles.Span>>()
                val nw = Mercator.tileAt(LatLon(area.north, area.west), Z)
                val se = Mercator.tileAt(LatLon(area.south, area.east), Z)
                for (t in counts.covered(nw, se)) tiles.span(t.z, t.x, t.y)?.let { spans += t to it }
                if (spans.isNotEmpty()) opened += Opened(s, tiles, spans.sortedBy { it.second.offset })
            } catch (e: IOException) {
                failure = "${s.name}: ${why(e)}"
            }
        }
        val total = opened.sumOf { it.spans.size }
        progress(0, total)
        val credits = AddressCredits.read(store).toMutableList()
        val known = Known(store)
        var done = 0
        var read = 0
        var complete = 0
        var bytes = 0L
        var points = 0
        var added = 0
        try {
            for (o in opened) {
                val index = credits.indexOfFirst { it.source == o.source.name }.takeIf { it >= 0 }
                    ?: credits.size.also { credits += AddressCredit(o.source.name, o.source.licence, o.source.credit) }
                var addedHere = 0
                // bytes per point: a little under what sources take, so a tile is passed over only once its sizes are known
                var sampleBytes = 0L
                var samplePoints = 0L
                var perPoint = PRIOR_BYTES_PER_POINT
                val batch = ArrayList<Pair<TileId, PmTiles.Span>>()
                suspend fun flush() {
                    if (batch.isEmpty()) return
                    val from = batch.first().second.offset
                    val length = (batch.last().second.let { it.offset + it.length } - from).toInt()
                    val got = fetch(o.source.tiles, from, length)
                    bytes += length
                    for ((t, span) in batch) {
                        val at = (span.offset - from).toInt()
                        val tile = o.tiles.unpack(got.copyOfRange(at, at + span.length))
                        val pts = OpenAddresses.points(tile, t.z, t.x, t.y)
                        sampleBytes += span.length
                        samplePoints += pts.size
                        if (samplePoints >= CALIBRATE_POINTS) perPoint = (sampleBytes.toDouble() / samplePoints).coerceAtLeast(MIN_BYTES_PER_POINT)
                        for (p in pts) {
                            if (p.lat < box.south || p.lat > box.north || p.lon < box.west || p.lon > box.east) continue
                            points++
                            val parts = parts(p) ?: continue
                            val lat = E7.of(p.lat)
                            val lon = E7.of(p.lon)
                            val street = Known.streetKey(parts.street)
                            if (known.has(parts.housenumber, street, lat, lon)) continue
                            if (!writer.addFilled(parts, lat, lon, index)) continue
                            known.add(parts.housenumber, street, lat, lon)
                            counts.add(t.x, t.y)
                            addedHere++
                        }
                        read++
                        done++
                        progress(done, total)
                    }
                    batch.clear()
                }
                for ((t, span) in o.spans) {
                    currentCoroutineContext().ensureActive()
                    if (counts.numbers(t.x, t.y) >= span.length / perPoint * COMPLETE) {
                        complete++
                        done++
                        progress(done, total)
                        continue
                    }
                    if (batch.isNotEmpty()) {
                        val start = batch.first().second.offset
                        val end = batch.last().second.let { it.offset + it.length }
                        if (span.offset - end > GAP || span.offset + span.length - start > BATCH_BYTES) flush()
                    }
                    batch += t to span
                }
                flush()
                added += addedHere
                credits[index] = credits[index].let { it.copy(points = it.points + addedHere) }
            }
        } catch (e: IOException) {
            failure = why(e)
        } finally {
            // the sources that gave points, with what each gave: the credits the points name by their place in the list
            if (credits.isNotEmpty()) store.locked { writer.putMeta(AddressCredits.META, AddressCredits.encode(credits)) }
        }
        return Stats(opened.size, total, read, complete, bytes, points, added, failure)
    }

    /** [length] bytes of [url] from [offset], asked again after a failure. */
    private suspend fun fetch(url: String, offset: Long, length: Int): ByteArray {
        var attempt = 0
        while (true) {
            try {
                return addresses.range(url, offset, length)
            } catch (e: IOException) {
                if (++attempt > RETRIES) throw e
            }
        }
    }

    companion object {
        /** The zoom of the sources' finest tiles, the only ones that hold every point with every field. */
        const val Z = 14

        /** A tile where the store holds this share of the numbers the source has there is passed over. */
        const val COMPLETE = 0.8

        /** What a source's points take in its tiles before any is read: a little under the least seen (21 to 30 bytes). */
        private const val PRIOR_BYTES_PER_POINT = 18.0
        private const val MIN_BYTES_PER_POINT = 8.0
        private const val CALIBRATE_POINTS = 200L

        /** Tiles this close in the archive are read in one request, up to [BATCH_BYTES] at once. */
        private const val GAP = 64L * 1024
        private const val BATCH_BYTES = 2L * 1024 * 1024

        private const val RETRIES = 2

        private fun why(e: IOException): String = e.message ?: e.javaClass.simpleName

        private fun intersection(a: BBox, b: BBox): BBox? {
            val w = maxOf(a.west, b.west)
            val s = maxOf(a.south, b.south)
            val e = minOf(a.east, b.east)
            val n = minOf(a.north, b.north)
            return if (w <= e && s <= n) BBox(w, s, e, n) else null
        }

        /**
         * A point as an address point: its number (at most [Addresses.MAX_HOUSENUMBER] long), its street tidied
         * ([OpenAddresses.tidy]) with its postcode and town, or, without a street, numbered in its town (`addr:place`).
         */
        internal fun parts(p: OpenAddresses.Point): AddressParts? {
            val number = p.number.trim().replace(SPACES, " ")
            if (number.isEmpty() || number.length > Addresses.MAX_HOUSENUMBER) return null
            val street = p.street?.let(OpenAddresses::tidy)?.takeIf { it.isNotEmpty() }
            val city = (p.city ?: p.district)?.let(OpenAddresses::tidy)?.takeIf { it.isNotEmpty() }
            val postcode = p.postcode?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                street != null -> AddressParts(street, Addresses.STREET, number, postcode, city, null)
                city != null -> AddressParts(city, Addresses.PLACE, number, postcode, null, null)
                else -> null
            }
        }

        private val SPACES = Regex("\\s+")
    }
}

/**
 * The store's things per tile of one zoom, counted once before a fill: its numbers (address points, and places with a
 * house number) and anything of its own at all (not filled in before). A fill adds its points as it goes.
 */
internal class Counts private constructor(private val z: Int, private val numbers: HashMap<Long, Int>, private val things: HashSet<Long>) {
    /** The numbers the store holds in tile [x], [y]. */
    fun numbers(x: Int, y: Int): Int = numbers[key(x, y)] ?: 0

    /**
     * The tiles from [nw] to [se] where the store holds something of its own, on the tile or one beside it: found from
     * what it holds, so a country's box (Norway's with Svalbard is 7 million tiles) costs no more than what is in it.
     */
    fun covered(nw: TileId, se: TileId): List<TileId> {
        val out = HashSet<Long>()
        for (k in things) {
            val x = (k shr 32).toInt()
            val y = k.toInt()
            for ((dx, dy) in BESIDE) {
                if (x + dx in nw.x..se.x && y + dy in nw.y..se.y) out += key(x + dx, y + dy)
            }
        }
        return out.sorted().map { TileId(z, (it shr 32).toInt(), it.toInt()) }
    }

    fun add(x: Int, y: Int) {
        numbers.merge(key(x, y), 1, Int::plus)
    }

    companion object {
        private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

        /** A tile and the four that share a side with it. */
        private val BESIDE = listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, 0 to 1)

        /** Counts [store]'s address points and places by their tile at [z] (under the store's lock). */
        fun of(store: PlaceStore, z: Int): Counts {
            val numbers = HashMap<Long, Int>()
            val things = HashSet<Long>()
            val n = (1 shl z).toDouble()
            fun keyAt(latE7: Long, lonE7: Long): Long {
                val x = (Mercator.x(E7.deg(lonE7)) * n).toInt().coerceIn(0, (1 shl z) - 1)
                val y = (Mercator.y(E7.deg(latE7)) * n).toInt().coerceIn(0, (1 shl z) - 1)
                return key(x, y)
            }
            store.stmt("SELECT s.cell, a.lat, a.lon, a.osm FROM address a JOIN street s ON s.id = a.street") { st ->
                while (st.step()) {
                    val cell = st.getLong(0)
                    val k = keyAt(Addresses.cellSouth(cell) + st.getLong(1), Addresses.cellWest(cell) + st.getLong(2))
                    numbers.merge(k, 1, Int::plus)
                    // a point filled in before is a number, not the store's own: a fill again does not reach out from it
                    if (st.isNull(3) || AddressCredits.sourceOf(st.getLong(3)) == null) things += k
                }
            }
            val hn = store.dict.findKey(Addresses.HOUSENUMBER)
            store.stmt("SELECT lat, lon, tags FROM place") { st ->
                while (st.step()) {
                    val k = keyAt(st.getLong(0), st.getLong(1))
                    things += k
                    if (hn >= 0 && TagBlob.hasKey(st.getBlob(2), hn)) numbers.merge(k, 1, Int::plus)
                }
            }
            return Counts(z, numbers, things)
        }
    }
}

/**
 * The numbers a store already holds near a point, for [AddressFill]: its address points and the places with a house
 * number, read cell by cell of the address grid ([Addresses.cellOf]) as the fill reaches them (the latest [CELLS] kept),
 * and the points the fill added, kept all along.
 *
 * A number is there when the same number (letters and digits alike: "263-H" is "263h") lies within [SAME_STREET_M] on
 * a street of the same name (one name the start of the other: "Main St" of "Main Street"), or within [SAME_PLACE_M]
 * whatever its street is called; a unit of a number ("263-2", "263/2", "12A-3") is there when its house is.
 */
internal class Known(private val store: PlaceStore) {
    private class Point(val street: String, val lat: Int, val lon: Int)

    private class Cell {
        val byNumber = HashMap<String, ArrayList<Point>>()

        fun add(number: String, street: String, lat: Int, lon: Int) {
            byNumber.getOrPut(numberKey(number)) { ArrayList(2) } += Point(street, lat, lon)
        }
    }

    /** The streets of each cell: their ids and names as [streetKey] writes them, read once. */
    private val streets: Map<Long, List<Pair<Long, String>>> = store.locked {
        val out = HashMap<Long, ArrayList<Pair<Long, String>>>()
        store.stmt("SELECT id, name, cell FROM street") { st ->
            while (st.step()) out.getOrPut(st.getLong(2)) { ArrayList() } += st.getLong(0) to streetKey(st.getText(1))
        }
        out
    }

    private val loaded = object : LinkedHashMap<Long, Cell>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Cell>?) = size > CELLS
    }
    private val added = HashMap<Long, Cell>()

    /** Whether [number] on [street] (a [streetKey]) at [lat], [lon] (1e-7 degree) is there already. */
    fun has(number: String, street: String, lat: Int, lon: Int): Boolean {
        val key = numberKey(number)
        val house = houseOf(number)?.let(::numberKey)
        for (c in cellsNear(lat, lon)) {
            for (cell in listOfNotNull(cell(c), added[c])) {
                if (cell.byNumber[key]?.any { near(it, street, lat, lon) } == true) return true
                if (house != null && cell.byNumber[house]?.any { near(it, street, lat, lon) } == true) return true
            }
        }
        return false
    }

    fun add(number: String, street: String, lat: Int, lon: Int) {
        added.getOrPut(Addresses.cellOf(lat, lon)) { Cell() }.add(number, street, lat, lon)
    }

    private fun near(p: Point, street: String, lat: Int, lon: Int): Boolean {
        val d = Geo.distance(E7.deg(p.lat), E7.deg(p.lon), E7.deg(lat), E7.deg(lon))
        return d <= SAME_PLACE_M || (d <= SAME_STREET_M && sameStreet(p.street, street))
    }

    /** The cells within [SAME_STREET_M] of a point: its own, and those beside it where it lies near their edge. */
    private fun cellsNear(lat: Int, lon: Int): Set<Long> {
        val dLat = (SAME_STREET_M / 111_320.0 * 1e7).toInt()
        val dLon = (dLat / cos(Math.toRadians(E7.deg(lat)).coerceIn(-1.5, 1.5))).toInt()
        val out = LinkedHashSet<Long>(4)
        for (a in intArrayOf(lat - dLat, lat + dLat)) for (b in intArrayOf(lon - dLon, lon + dLon)) out += Addresses.cellOf(a, b)
        return out
    }

    private fun cell(id: Long): Cell = loaded[id] ?: store.locked { read(id) }.also { loaded[id] = it }

    /** A cell's numbers: its streets' address points, and the places in its box with a house number. */
    private fun read(id: Long): Cell {
        val cell = Cell()
        val south = Addresses.cellSouth(id)
        val west = Addresses.cellWest(id)
        for ((street, name) in streets[id].orEmpty()) {
            store.stmt("SELECT hn, lat, lon FROM address WHERE street = ?1") { st ->
                st.bindLong(1, street)
                while (st.step()) for (n in st.getText(0).split(';')) cell.add(n.trim(), name, south + st.getLong(1).toInt(), west + st.getLong(2).toInt())
            }
        }
        val hn = store.dict.findKey(Addresses.HOUSENUMBER)
        if (hn >= 0) {
            val box = BBox(E7.deg(west), E7.deg(south), E7.deg(west + CELL_LON_E7), E7.deg(south + CELL_LAT_E7))
            store.stmt("SELECT p.lat, p.lon, p.tags FROM place_rt r JOIN place p ON p.id = r.id WHERE ${PlaceStore.RT_WHERE}") { st ->
                store.bindRt(st, box, 1)
                while (st.step()) {
                    val blob = st.getBlob(2)
                    if (!TagBlob.hasKey(blob, hn)) continue
                    val tags = TagBlob.decode(blob, store.dict)
                    val street = streetKey(tags["addr:street"] ?: tags["addr:place"] ?: "")
                    for (n in tags[Addresses.HOUSENUMBER].orEmpty().split(';')) cell.add(n.trim(), street, st.getLong(0).toInt(), st.getLong(1).toInt())
                }
            }
        }
        return cell
    }

    companion object {
        /** Any number this close is the same address, whatever its street is called. */
        const val SAME_PLACE_M = 30.0

        /** The same number this close on a street of the same name is the same address. */
        const val SAME_STREET_M = 300.0

        private const val CELLS = 16

        // the address grid's cell, as Addresses lays it out
        private const val CELL_LAT_E7 = 440_000
        private const val CELL_LON_E7 = 880_000

        /** A number as two compare: its letters and digits, in lower case ("263-H" and "263h" are one, "12 bis" and "12bis"). */
        fun numberKey(n: String): String = buildString(n.length) { for (c in n) if (c.isLetterOrDigit()) append(c.lowercaseChar()) }

        /** The house a number with a unit after a dash or slash is of: 263 for "263-H" and "263/2", 12A for "12A-3"; else null. */
        fun houseOf(n: String): String? = UNIT.matchEntire(n.trim())?.groupValues?.get(1)

        /** A street's name as two compare: folded, its letters and digits only ("Rue de l'Église" is "ruedeleglise"). */
        fun streetKey(name: String): String = buildString(name.length) { for (c in Text.fold(name)) if (c.isLetterOrDigit()) append(c) }

        /** Two street names ([streetKey]s) that name one street: the same, or the one the start of the other ("mainst"). */
        fun sameStreet(a: String, b: String): Boolean =
            a == b || (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a)))

        private val UNIT = Regex("""(\d+[A-Za-z]?)\s*[-/]\s*[0-9A-Za-z]+""")
    }
}
