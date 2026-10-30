package app.atlas.providers

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.PointIndex
import app.atlas.model.OsmId
import app.atlas.model.Place
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Chargers held in memory as compact records with a k-d tree, answering [near] in microseconds: for the national
 * registers (NDW, Bundesnetzagentur) and offline packs. Positions are primitive arrays and each charger's tags stay UTF-8
 * bytes until a query returns it as a [Place]; measured on 30 October 2026, the Netherlands' 79,530 chargers took about
 * 24 MB of heap and 5.1 MB on disk, Germany's 116,443 about 33 MB and 3.6 MB.
 * Immutable once built; [write] and [read] keep it on disk between runs.
 */
class ChargerIndex private constructor(override val info: ProviderInfo, private val data: Data) : ChargerProvider {
    /** Builds an index of [places]. */
    constructor(places: List<Place>, info: ProviderInfo) : this(info, Builder(info).apply { places.forEach(::add) }.build().data)

    /** Positions as primitive arrays; each record's UTF-8 bytes run from offsets[i] to offsets[i + 1]. */
    private class Data(val lat: DoubleArray, val lon: DoubleArray, val records: ByteArray, val offsets: IntArray)

    /** Builds an index one charger at a time, so a whole country never sits in memory as [Place] objects. */
    class Builder(private val info: ProviderInfo) {
        private val lats = DoubleBuilder()
        private val lons = DoubleBuilder()
        private val buf = java.io.ByteArrayOutputStream(1 shl 16)
        private var offsets = IntArray(1024)

        val size: Int get() = lats.size

        fun add(place: Place) = addRecord(place.location.lat, place.location.lon, Encoded.record(place))

        internal fun addRecord(lat: Double, lon: Double, record: String) {
            if (size + 1 >= offsets.size) offsets = offsets.copyOf(offsets.size * 2)
            offsets[size] = buf.size()
            buf.write(record.toByteArray(Charsets.UTF_8))
            lats.add(lat)
            lons.add(lon)
        }

        private fun data(): Data {
            offsets[size] = buf.size()
            return Data(lats.toArray(), lons.toArray(), buf.toByteArray(), offsets.copyOf(size + 1))
        }

        fun build(): ChargerIndex = ChargerIndex(info, data())
    }

    private val lat = data.lat
    private val lon = data.lon
    private val records = data.records
    private val offsets = data.offsets

    val size: Int get() = lat.size

    private val index = PointIndex(lat.size).apply { for (i in lat.indices) add(lon[i], lat[i]) }.finish()

    // Built on first use: most sessions never ask for a status by id.
    private val byId: Map<String, Int> by lazy {
        HashMap<String, Int>(size * 2).also { m -> for (i in 0 until size) idAt(i)?.let { m[it] = i } }
    }

    override fun state() = ProviderState.READY

    override suspend fun near(center: LatLon, radiusM: Double, filters: ChargerFilters): List<Place> = nearNow(center, radiusM, filters)

    /** [near] without suspending: the index is in memory. */
    fun nearNow(center: LatLon, radiusM: Double, filters: ChargerFilters = ChargerFilters()): List<Place> {
        val box = BBox.around(center, radiusM)
        val hits = index.range(box.west, box.south, box.east, box.north)
        val out = ArrayList<Pair<Double, Place>>(hits.size)
        for (i in hits) {
            val d = Geo.distance(center.lat, center.lon, lat[i], lon[i])
            if (d > radiusM) continue
            val p = place(i)
            if (filters.accepts(p)) out.add(d to p)
        }
        out.sortBy { it.first }
        return out.take(filters.maxResults).map { it.second }
    }

    /** Every charger in [box]. */
    fun inBox(box: BBox): List<Place> = index.range(box.west, box.south, box.east, box.north).map(::place)

    /** The charger with this `charger:id`, or null. */
    operator fun get(id: String): Place? = byId[id]?.let(::place)

    /** Every charger, in the order they were added. */
    fun all(): List<Place> = List(size, ::place)

    override suspend fun status(ids: List<String>): Map<String, ChargerStatus> = ids.mapNotNull { id ->
        val p = get(id) ?: return@mapNotNull null
        statusOf(p)?.let { id to it }
    }.toMap()

    /** Writes the index as gzipped lines of tab-separated fields, readable by [read]. */
    fun write(out: OutputStream) {
        OutputStreamWriter(GZIPOutputStream(out), Charsets.UTF_8).buffered().use { w ->
            w.write(HEADER)
            w.write("\n")
            for (i in 0 until size) {
                w.write(lat[i].toString())
                w.write("\t")
                w.write(lon[i].toString())
                w.write("\t")
                w.write(String(records, offsets[i], offsets[i + 1] - offsets[i], Charsets.UTF_8))
                w.write("\n")
            }
        }
    }

    /** One record as a place: the bytes are decoded only now, when a query returns it. */
    private fun place(i: Int): Place {
        val fields = String(records, offsets[i], offsets[i + 1] - offsets[i], Charsets.UTF_8).split(FIELD)
        val id = fields[1].takeIf { it.isNotEmpty() }?.let(OsmId::parse)
        return Place(id, LatLon(lat[i], lon[i]), Encoded.map(fields[2]), source = fields[0], extra = Encoded.map(fields[3]))
    }

    private fun idAt(i: Int): String? {
        val s = String(records, offsets[i], offsets[i + 1] - offsets[i], Charsets.UTF_8)
        val extra = s.substring(s.lastIndexOf(FIELD) + 1)
        return Encoded.map(extra)["charger:id"]
    }

    /**
     * Compact text records: source, OSM id, tags and extra, with control characters as separators. The commonest keys
     * and values are written as two bytes ([CODE] and a printable index into [WORDS]), which halves a national register.
     */
    private object Encoded {
        // Append only: the index of each word is part of the file format of kept indexes.
        private val WORDS = listOf(
            "amenity", "charging_station", "name", "operator", "brand", "network", "website", "capacity", "opening_hours", "fee",
            "charge", "access", "addr:street", "addr:housenumber", "addr:postcode", "addr:city", "addr:country", "socket:type2",
            "socket:type2:output", "socket:type2_combo", "socket:type2_combo:output", "socket:chademo", "socket:chademo:output",
            "socket:type2_cable", "socket:type2_cable:output", Sockets.POWER_KW, "charger:id", "status", "status:available",
            "status:total", "status:updated", "status:type2", "status:type2_combo", "status:chademo", "status:type2_cable",
            "energy:green", "attribution", "licence", "url", "verified", "covered", "ref:EU:EVSE", "start_date",
            "authentication:membership_card", "authentication:app", "charging_station:output", "payment:credit_cards",
            "payment:debit_cards", "yes", "no", "available", "occupied", "operational", "out_of_order", "unknown", "planned",
            "24/7", "customers", "private", "11 kW", "22 kW", "50 kW", "17 kW", "0/1", "1/1", "0/2", "1/2", "2/2", "NL", "DE",
            "US", "TotalEnergies", "Vattenfall InCharge", "Shell Recharge", "Allego", "Equans", "Fastned",
            "Ladesäulenregister: Bundesnetzagentur.de, CC BY 4.0", "CC BY 4.0", "11", "22", "50", "1", "2", "3", "4", "6", "8",
        )
        private val CODES: Map<String, Char> = WORDS.withIndex().associate { (i, w) -> w to (FIRST + i) }
        private const val FIRST = '\u0021'

        init {
            check(WORDS.size <= 0x7E - 0x21 + 1) { "Too many words for one printable code" }
        }

        fun record(p: Place): String = buildString {
            append(clean(p.source)).append(FIELD).append(p.id?.toString() ?: "").append(FIELD)
            appendMap(p.tags)
            append(FIELD)
            appendMap(p.extra)
        }

        private fun StringBuilder.appendMap(m: Map<String, String>) {
            var first = true
            for ((k, v) in m) {
                if (!first) append(PAIR)
                first = false
                word(k)
                append(KV)
                word(v)
            }
        }

        private fun StringBuilder.word(w: String) {
            val code = CODES[w]
            if (code != null) append(CODE).append(code) else append(clean(w))
        }

        private fun unword(s: String): String = if (s.length == 2 && s[0] == CODE) WORDS[s[1] - FIRST] else s

        fun map(s: String): Map<String, String> {
            if (s.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in s.split(PAIR)) {
                val at = pair.indexOf(KV)
                if (at > 0) out[unword(pair.substring(0, at))] = unword(pair.substring(at + 1))
            }
            return out
        }

        private fun clean(s: String): String =
            if (s.none { it < ' ' }) s else s.map { if (it < ' ') ' ' else it }.joinToString("")
    }

    companion object {
        private const val FIELD = '\u001E'
        private const val PAIR = '\u001F'
        private const val KV = '\u001D'
        private const val CODE = '\u001C'
        private const val HEADER = "#atlas-chargers 2"

        /** Reads an index [write] wrote; [info] says whose data it is. */
        fun read(input: InputStream, info: ProviderInfo): ChargerIndex {
            val builder = Builder(info)
            BufferedReader(InputStreamReader(GZIPInputStream(input), Charsets.UTF_8), 1 shl 16).use { r ->
                val header = r.readLine()
                if (header != HEADER) throw java.io.IOException("Not an Atlas charger index: ${header?.take(40)}")
                while (true) {
                    val line = r.readLine() ?: break
                    val a = line.indexOf('\t')
                    val b = line.indexOf('\t', a + 1)
                    if (a < 0 || b < 0) continue
                    builder.addRecord(line.substring(0, a).toDouble(), line.substring(a + 1, b).toDouble(), line.substring(b + 1))
                }
            }
            return builder.build()
        }

        /** The status kept in a charger place's `extra`, as [ChargerStatus.toExtra] wrote it. */
        fun statusOf(p: Place): ChargerStatus? {
            val id = p.extra["charger:id"] ?: return null
            val state = ChargerStatus.State.entries.firstOrNull { it.tag == p.extra["status"] } ?: return null
            val sockets = p.extra.filterKeys { it.startsWith("status:") && it.count { c -> c == ':' } == 1 && it !in STATUS_KEYS }
                .mapNotNull { (k, v) ->
                    val parts = v.split('/')
                    val av = parts.getOrNull(0)?.toIntOrNull()
                    val total = parts.getOrNull(1)?.toIntOrNull()
                    if (parts.size == 2 && av != null && total != null) k.substringAfter(':') to (av to total) else null
                }.toMap()
            return ChargerStatus(
                id, state, p.extra["status:available"]?.toIntOrNull(), p.extra["status:total"]?.toIntOrNull(),
                p.extra["status:updated"]?.let { runCatching { Instant.parse(it) }.getOrNull() }, sockets,
            )
        }

        private val STATUS_KEYS = setOf("status:available", "status:total", "status:updated")
    }
}

/** A growable array of doubles, without boxing. */
internal class DoubleBuilder(capacity: Int = 1024) {
    private var a = DoubleArray(capacity)
    var size = 0
        private set

    fun add(v: Double) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    fun toArray(): DoubleArray = a.copyOf(size)
}
