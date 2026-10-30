package app.atlas.render.data

import app.atlas.geo.BBox
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.zip.GZIPInputStream

/** Posts a form and hands back the response body. The phone may supply OkHttp; [HttpUrlPost] needs nothing. */
fun interface HttpPost {
    /** Throws [IOException] for a response that is not a 200. The caller closes the stream. */
    fun post(url: String, form: Map<String, String>, userAgent: String): InputStream
}

/** [HttpPost] on the JDK's own HttpURLConnection (also on Android). */
class HttpUrlPost(private val readTimeoutMs: Int = 330_000) : HttpPost {
    override fun post(url: String, form: Map<String, String>, userAgent: String): InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 30_000
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("User-Agent", userAgent)
        c.setRequestProperty("Accept-Encoding", "gzip")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        val body = form.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        if (code != 200) {
            val msg = runCatching { c.errorStream?.readBytes()?.toString(Charsets.UTF_8)?.take(200) }.getOrNull()
            c.disconnect()
            throw IOException("$url answered $code${if (msg.isNullOrBlank()) "" else ": $msg"}")
        }
        val stream = c.inputStream
        return if (c.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(stream, 1 shl 16) else stream
    }
}

/** What an Overpass fetch gave: the elements merged by id, in Overpass's order, and the extract's header. */
class OverpassResult(val header: JsonObject, val elements: List<OsmElement>) {
    fun source(): ElementSource = ListElementSource(elements, header)
}

/**
 * Fetches a box from Overpass with the query of `fetch/regions.py` (every tagged node, every way, multipolygon
 * relations, national parks and protected areas, `out geom`), tries the same hosts in turn, and splits a box Overpass
 * cannot answer whole into halves merged by id: what the grachten extract needed, done by hand then.
 *
 * The phone's windows ([window], [windowDiff]) share what the hosts did lately: the host that answered last is asked
 * first, one whose data was behind after the others, and one that failed rests ([REST_MS], twice as long after each
 * failure in a row, [REST_CAP_MS] at most), so the next window does not ask it again; where every host rests, a window
 * fails at once without asking any. On 27 October 2026 every public server failed for an hour, and each window the
 * phone wanted asked each of them in turn.
 */
class OverpassClient(
    private val http: HttpPost = HttpUrlPost(),
    private val hosts: List<String> = HOSTS,
    private val userAgent: String = USER_AGENT,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit = {},
    /** Boxes bigger than this (square degrees) are split before the first request. */
    private val maxArea: Double = MAX_AREA,
    /** Seconds to wait after a failed attempt, times the attempt number. */
    private val backoffSeconds: Long = 15,
    /** Monotonic milliseconds, for the hosts' rests; tests pass their own. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** A host that failed a window lately: until when it rests ([clock]'s time), and how often it failed in a row. */
    private class Rest(val until: Long, val times: Int)

    private val rests = HashMap<String, Rest>()

    /** The host whose answer a window took last: asked first. */
    private var lastGood: String? = null

    /** The hosts whose data was behind lately: asked after the others. */
    private val behind = HashSet<String>()

    /**
     * The hosts a window asks, in turn: the one that answered last first, then the others in their order, those whose
     * data was behind lately last; none that rests. Throws where every host rests.
     */
    private fun windowHosts(): List<String> = synchronized(rests) {
        val now = clock()
        val all = hosts.distinct()
        val awake = all.filter { (rests[it]?.until ?: Long.MIN_VALUE) <= now }
        if (awake.isEmpty()) {
            val next = all.minOf { rests.getValue(it).until }
            throw IOException("every Overpass server failed lately; the first is asked again in ${(next - now + 999) / 1000} s")
        }
        awake.sortedBy { h -> if (h == lastGood) 0 else if (h in behind) 2 else 1 }
    }

    /** [host] failed a window's request: it rests, longer after each failure in a row. */
    private fun failed(host: String) = synchronized(rests) {
        val times = (rests[host]?.times ?: 0) + 1
        rests[host] = Rest(clock() + minOf(REST_MS shl (times - 1).coerceAtMost(10), REST_CAP_MS), times)
        if (lastGood == host) lastGood = null
    }

    /** [host] answered a window whole: it rests no more, and is asked first where its data was [fresh], else last. */
    private fun answered(host: String, fresh: Boolean) = synchronized(rests) {
        rests.remove(host)
        if (fresh) {
            behind.remove(host)
            lastGood = host
        } else {
            behind.add(host)
            if (lastGood == host) lastGood = null
        }
    }

    /** The Overpass QL of `fetch/regions.py overpass()` for a box. */
    fun query(b: BBox): String =
        "[out:json][timeout:240][bbox:${num(b.south)},${num(b.west)},${num(b.north)},${num(b.east)}];" +
            "(node(if:count_tags()>0);way;relation[type=multipolygon];relation[boundary~\"^(national_park|protected_area)$\"];);out geom;"

    private fun num(v: Double): String = java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString()

    /**
     * The QL of a window's data (ENGINE.md step 3: the windows the phone builds): [query]'s elements of [b], but with the
     * box on each statement and none on `out geom`, so a relation's members come whole (with a global box, Overpass
     * leaves out the geometry of the members beyond it).
     */
    fun windowQuery(b: BBox): String = "[out:json][timeout:240];" + windowStatements(b)

    /**
     * The QL of a window's changes since [since] (an ISO date; ENGINE.md step 3: a window built again after a week):
     * Overpass's augmented diff of [windowQuery] between that date and now. Overpass runs the query at both dates and
     * prints each element whose output differs, as it was and as it is, with its points (`out geom`), so a way whose
     * node moved is among them. In XML: Overpass's JSON prints no actions.
     */
    fun windowDiffQuery(b: BBox, since: String): String = "[out:xml][timeout:240][adiff:\"$since\"];" + windowStatements(b)

    private fun windowStatements(b: BBox): String {
        val box = "${num(b.south)},${num(b.west)},${num(b.north)},${num(b.east)}"
        return "(node($box)(if:count_tags()>0);way($box);relation[type=multipolygon]($box);" +
            "relation[boundary~\"^(national_park|protected_area)$\"]($box););out geom;"
    }

    /**
     * Overpass's JSON for a window's box ([windowQuery]), streamed into [into] as it comes (a dense window's answer is
     * tens of MB, which never pass through the app's heap), from the first host that answers whole with data from
     * [freshAfter] on, each host not resting tried once in turn, without a pause (a query that timed out part-way
     * answers 200 with a remark, and is tried again elsewhere; an answer without the data's date is not Overpass's
     * JSON). A host whose data is older is passed over while another may answer (on 22 October 2026 one of them answered
     * with data from June); where none does, the newest answer that came is taken. Throws where none came.
     */
    fun window(b: BBox, into: File, freshAfter: Instant? = null) {
        var lastError: Exception? = null
        val failures = ArrayList<String>()
        val older = File(into.path + ".older")
        var olderDate: Instant? = null
        older.delete()
        try {
            for (host in windowHosts()) {
                val date = try {
                    http.post("$host/api/interpreter", mapOf("data" to windowQuery(b)), userAgent).use { input ->
                        into.outputStream().buffered(1 shl 16).use { input.copyTo(it, 1 shl 16) }
                    }
                    val tail = String(tailOf(into, 4096), Charsets.UTF_8)
                    if (tail.contains("\"remark\"") && tail.contains("runtime error", ignoreCase = true)) throw IOException("Overpass ran out of time or memory")
                    dataDate(headOf(into, 2048)) ?: throw IOException("an answer without the data's date: ${String(headOf(into, 120), Charsets.UTF_8).trim()}")
                } catch (e: IOException) {
                    lastError = e
                    failures += "$host: ${e.message ?: e.javaClass.simpleName}"
                    failed(host)
                    log("retry after $host: ${e.message}")
                    continue
                }
                val fresh = freshAfter == null || !date.isBefore(freshAfter)
                answered(host, fresh)
                if (fresh) return
                log("$host's data is from $date, before $freshAfter")
                if (olderDate == null || date.isAfter(olderDate)) {
                    if (!into.renameTo(older)) throw IOException("$into could not be set aside")
                    olderDate = date
                }
            }
            if (olderDate != null && older.renameTo(into)) return
            throw IOException("Overpass failed for the window $b: ${failures.joinToString("; ")}", lastError)
        } finally {
            older.delete()
        }
    }

    /**
     * Overpass's changes to a window's data since [since] ([windowDiffQuery]), its XML as it came, from the first host
     * that answers whole with data not older than [notBefore] (the kept data's own date: a host behind it knows nothing
     * newer), each host not resting tried once in turn, as [window] asks them. A remark (the query failed or ran out of
     * time, or the host keeps no history of the data) is tried again elsewhere, without resting the host: its whole
     * windows may still come.
     */
    fun windowDiff(b: BBox, since: String, notBefore: Instant): ByteArray {
        var lastError: Exception? = null
        for (host in windowHosts()) {
            val bytes = try {
                http.post("$host/api/interpreter", mapOf("data" to windowDiffQuery(b, since)), userAgent).use { it.readBytes() }
            } catch (e: IOException) {
                lastError = e
                failed(host)
                log("retry after $host: ${e.message}")
                continue
            }
            val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8)
            val tail = String(bytes, maxOf(0, bytes.size - 4096), minOf(bytes.size, 4096), Charsets.UTF_8)
            val remark = sequenceOf(head, tail).firstNotNullOfOrNull { t ->
                t.substringAfter("<remark>", "").takeIf { it.isNotEmpty() }?.substringBefore("</remark>")?.trim()
            }
            val date = dataDate(bytes)
            lastError = when {
                remark != null -> IOException("Overpass remark: ${remark.take(200)}")
                // neither a remark nor the data's date: not Overpass's answer (an error page), so the host rests
                date == null -> IOException("$host's answer has no date: ${head.take(120).trim()}").also { failed(host) }
                !date.isBefore(notBefore) -> {
                    answered(host, fresh = true)
                    return bytes
                }
                else -> {
                    answered(host, fresh = false)
                    IOException("$host's data is from $date, behind the window's $notBefore")
                }
            }
            log("retry after $host: ${lastError.message}")
        }
        throw IOException("Overpass gave no changes for the window $b: ${lastError?.message}", lastError)
    }

    fun fetch(box: BBox): OverpassResult {
        val parts = ArrayList<OverpassResult>()
        gather(box, parts)
        return merge(parts)
    }

    private fun gather(box: BBox, into: MutableList<OverpassResult>) {
        if (box.width * box.height > maxArea) {
            split(box).forEach { gather(it, into) }
            return
        }
        var lastError: Exception? = null
        for ((attempt, host) in hosts.withIndex()) {
            try {
                into += request(host, box)
                return
            } catch (e: IOException) {
                lastError = e
                log("retry after $host: ${e.message}")
            } catch (e: PackFormatException) {
                lastError = e
                log("retry after $host: ${e.message}")
            }
            pause(backoffSeconds * 1000 * (attempt + 1))
        }
        // the whole box failed on every host: Overpass could not answer it whole, so ask for halves
        if (box.width > MIN_SIDE || box.height > MIN_SIDE) {
            log("splitting $box")
            split(box).forEach { gather(it, into) }
            return
        }
        throw IOException("Overpass failed for $box: ${lastError?.message}", lastError)
    }

    private fun split(b: BBox): List<BBox> =
        if (b.width >= b.height) {
            val m = (b.west + b.east) / 2
            listOf(BBox(b.west, b.south, m, b.north), BBox(m, b.south, b.east, b.north))
        } else {
            val m = (b.south + b.north) / 2
            listOf(BBox(b.west, b.south, b.east, m), BBox(b.west, m, b.east, b.north))
        }

    private fun request(host: String, box: BBox): OverpassResult {
        val els = ArrayList<OsmElement>()
        val header = http.post("$host/api/interpreter", mapOf("data" to query(box)), userAgent).use { input ->
            OverpassJson.read(OverpassJson.pull(input)) { els += it }
        }
        // a query that timed out part-way answers 200 with what it had and says so in a remark
        val remark = (header["remark"] as? JsonPrimitive)?.content
        if (remark != null && remark.contains("runtime error", ignoreCase = true)) throw IOException("Overpass remark: $remark")
        return OverpassResult(header, els)
    }

    companion object {
        /**
         * The hosts `fetch/regions.py` asks, in its order. overpass.kumi.systems, private.coffee's older name for the same
         * machine, went on 30 October: it asked that one server twice.
         */
        val HOSTS = listOf("https://overpass-api.de", "https://overpass.private.coffee", "https://overpass-api.de")
        const val USER_AGENT = "Atlas/1 (offline maps on OpenStreetMap; +https://github.com/atlas-maps-app/Atlas)"

        private val DATE = Regex("""(?:"timestamp_osm_base"\s*:\s*"|osm_base=")(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ)"""")

        /** The first [n] bytes of [file] (fewer where it is shorter). */
        fun headOf(file: File, n: Int): ByteArray = file.inputStream().use { input ->
            val out = ByteArray(n)
            var k = 0
            while (k < n) {
                val r = input.read(out, k, n - k)
                if (r < 0) break
                k += r
            }
            out.copyOf(k)
        }

        /** The last [n] bytes of [file] (fewer where it is shorter). */
        fun tailOf(file: File, n: Int): ByteArray = java.io.RandomAccessFile(file, "r").use { f ->
            val k = minOf(f.length(), n.toLong()).toInt()
            f.seek(f.length() - k)
            ByteArray(k).also { f.readFully(it) }
        }

        /** The date of an answer's data: its JSON's `osm3s.timestamp_osm_base`, its XML's `meta osm_base`; null for none. */
        fun dataDate(answer: ByteArray): Instant? =
            DATE.find(String(answer, 0, minOf(answer.size, 2048), Charsets.UTF_8))?.let { runCatching { Instant.parse(it.groupValues[1]) }.getOrNull() }

        /** How long a host rests after it failed a window, the first time in a row: a minute. */
        const val REST_MS = 60_000L

        /** The longest a host rests, however often it failed in a row: half an hour. */
        const val REST_CAP_MS = 30 * 60_000L

        /** 0.001 square degrees: the ring of the canal district (0.0009) answered whole, the whole box (0.0017) did not. */
        const val MAX_AREA = 0.001
        private const val MIN_SIDE = 0.004

        /**
         * The parts merged by (type, id) into Overpass's order, nodes then ways then relations, each ascending. An element
         * seen twice keeps its first copy; a relation's members without geometry there take it from a later copy.
         */
        fun merge(parts: List<OverpassResult>): OverpassResult {
            val byKey = LinkedHashMap<Long, OsmElement>()
            fun key(e: OsmElement) = (e.id shl 2) or e.type.ordinal.toLong()
            for (p in parts) for (e in p.elements) {
                val k = key(e)
                val old = byKey[k]
                byKey[k] = if (old == null) e else fillMembers(old, e)
            }
            val sorted = byKey.values.sortedWith(OsmElement.CANONICAL)
            return OverpassResult(mergeHeader(parts), sorted)
        }

        private fun fillMembers(a: OsmElement, b: OsmElement): OsmElement {
            val ma = a.members
            val mb = b.members
            if (ma == null || mb == null || ma.size != mb.size) return a
            var changed = false
            val out = ArrayList<Member>(ma.size)
            for (i in ma.indices) {
                val x = ma[i]
                val y = mb[i]
                if (x.ref == y.ref && x.type == y.type && ((x.geometry == null && y.geometry != null) || (!x.hasPosition && y.hasPosition))) {
                    out += Member(x.type, x.ref, x.role, if (x.hasPosition) x.lat else y.lat, if (x.hasPosition) x.lon else y.lon, x.geometry ?: y.geometry)
                    changed = true
                } else out += x
            }
            if (!changed) return a
            // the box of the relation grows with the geometry it gained
            var minLat = Double.POSITIVE_INFINITY; var minLon = minLat; var maxLat = Double.NEGATIVE_INFINITY; var maxLon = maxLat
            fun add(la: Double, lo: Double) { if (la < minLat) minLat = la; if (la > maxLat) maxLat = la; if (lo < minLon) minLon = lo; if (lo > maxLon) maxLon = lo }
            for (m in out) {
                if (m.hasPosition) add(m.lat, m.lon)
                m.geometry?.let { g -> for (i in 0 until g.size) if (!g.isNull(i)) add(g.lat[i], g.lon[i]) }
            }
            val bounds = if (minLat <= maxLat) Bounds(minLat, minLon, maxLat, maxLon) else a.bounds
            return OsmElement(a.type, a.id, a.lat, a.lon, a.tags, bounds, a.nodes, a.geometry, out)
        }

        private fun mergeHeader(parts: List<OverpassResult>): JsonObject {
            val first = parts.firstOrNull()?.header ?: return JsonObject(emptyMap())
            val times = parts.mapNotNull { (((it.header["osm3s"] as? JsonObject)?.get("timestamp_osm_base")) as? JsonPrimitive)?.content }
            val oldest = times.minOrNull() ?: return first
            val m = LinkedHashMap<String, JsonElement>(first)
            val osm3s = LinkedHashMap<String, JsonElement>((first["osm3s"] as? JsonObject) ?: JsonObject(emptyMap()))
            osm3s["timestamp_osm_base"] = JsonPrimitive(oldest)
            m["osm3s"] = JsonObject(osm3s)
            return JsonObject(m)
        }
    }
}
