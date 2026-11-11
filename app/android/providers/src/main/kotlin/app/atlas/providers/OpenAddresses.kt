package app.atlas.providers

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.util.Locale
import kotlin.math.ceil
import kotlin.time.Duration.Companion.seconds

/**
 * OpenAddresses (openaddresses.io): the address points governments, regions and counties publish, gathered source by
 * source, each under its own licence. Atlas reads them for a region it takes offline, to add the house numbers
 * OpenStreetMap does not have yet (the search module's AddressFill).
 *
 * The batch service says, for free and without an account, which sources cover a point and each source's last good
 * run: its licence and the tiles it made of the run's points for its own map, a PMTiles archive on Cloudflare's storage
 * that anyone may read and caches keep. Atlas reads those tiles by their bytes, only the ones a region needs. A run's
 * whole file (and the collections) are for signed-in accounts, as those downloads are what the project pays for: Atlas
 * asks for none. A source that names no licence is left out.
 *
 * The finest tiles (zoom 14) hold every point with every field, except in the densest blocks, where the tile maker
 * thinned them (on 5 November 2026 central Amsterdam's tile lacked Prinsengracht 263); there OpenStreetMap is mostly
 * complete already.
 */
class OpenAddresses(private val http: Http, private val api: String = API) : Provider {
    override val info = INFO

    override fun state() = ProviderState.READY

    /**
     * A source's last good run: its name ("fr/75/statewide"), its licence, the credit it asks for (or the name of who
     * publishes it), the run's box (null when it gave none) and its tiles.
     */
    data class Source(
        val name: String,
        val job: Long,
        val licence: String,
        val credit: String,
        val bounds: BBox?,
        val tiles: String,
        val points: Long,
    )

    /** One address point of a tile, as its source gave it. */
    class Point(
        val lat: Double,
        val lon: Double,
        val number: String,
        val street: String?,
        val unit: String?,
        val city: String?,
        val district: String?,
        val postcode: String?,
    )

    /**
     * The sources whose points may lie in [box], with a licence and tiles to read: asked at points across it (its
     * corners, its middle, and about every [PROBE_DEGREES] between), less those whose run's box misses it. Throws
     * [IOException] when the service cannot be asked.
     */
    suspend fun sources(box: BBox): List<Source> {
        val jobs = LinkedHashMap<Long, String>()
        for (p in probes(box)) {
            val list = http.json("$api/data?point=${f(p.lon)},${f(p.lat)}") as? JsonArray ?: continue
            for (e in list) {
                val o = e as? JsonObject ?: continue
                if (o.text("layer") != "addresses") continue
                if ((o["output"] as? JsonObject)?.flag("pmtiles") != true) continue
                val job = (o["job"] as? JsonPrimitive)?.longOrNull ?: continue
                jobs.putIfAbsent(job, o.text("source") ?: "")
            }
        }
        val out = ArrayList<Source>()
        for ((job, name) in jobs) {
            val j = http.json("$api/job/$job") as? JsonObject ?: continue
            source(j, job, name)?.takeIf { s -> s.bounds == null || s.bounds.intersects(box) }?.let { out += it }
        }
        return out.sortedBy { it.name }
    }

    /** The tiles of [source], opened: their header and root directory read. */
    suspend fun tiles(source: Source): PmTiles = PmTiles.open { offset, length -> range(source.tiles, offset, length) }

    /**
     * [length] bytes of [url] from [offset]: a Range request that must be answered with those bytes, never the whole
     * file (a server that ignores the range is refused before its body is read).
     */
    suspend fun range(url: String, offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length > 0) { "no bytes $offset+$length" }
        val req = http.request(url).header("Accept", "*/*").header("Range", "bytes=$offset-${offset + length - 1}").build()
        return http.exchange(req, timeout = 120.seconds, stall = 30.seconds) { r ->
            if (r.code != 206) throw HttpException(r.code, url, if (r.code == 200) "the whole file came instead of the bytes asked for" else r.errorBody())
            val from = r.header("Content-Range")?.removePrefix("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull()
            if (from != offset) throw IOException("bytes from $offset of $url came as ${r.header("Content-Range")}")
            ByteArray(length).also { r.body.source().readFully(it) }
        }
    }

    companion object {
        const val ID = "openaddresses"
        const val API = "https://batch.openaddresses.io/api"

        /** What a place from a source carries in its `extra`: the credit the source asks for, and its licence. */
        const val CREDIT = "openaddresses:credit"
        const val LICENCE = "openaddresses:licence"

        /** The tiles' host (the service's public storage), besides the service's own. */
        const val TILES_HOST = "v2.openaddresses.io"

        val INFO = ProviderInfo(
            id = ID,
            name = "OpenAddresses",
            licence = "Each source's own: CC0, CC BY, Licence Ouverte…",
            attribution = "Addresses: OpenAddresses (openaddresses.io) and each source, credited with its addresses",
            url = "https://openaddresses.io",
            note = "The house numbers governments publish, for those OpenStreetMap does not have yet: read with a region " +
                "you download, from the tiles of OpenAddresses' map, only where OpenStreetMap has fewer numbers.",
            hosts = listOf("batch.openaddresses.io", TILES_HOST),
        )

        /** About how far apart [sources] asks across a box: a county is found in the corners and middles of a state. */
        const val PROBE_DEGREES = 0.5

        /** The attributes of a tile's points Atlas reads, in [Point]'s order. */
        private val KEYS = listOf("number", "street", "unit", "city", "district", "postcode")

        /** The points [sources] asks at: the corners and the middle of [box], and a grid between them for a big box. */
        internal fun probes(box: BBox): List<LatLon> {
            val cols = (ceil(box.width / PROBE_DEGREES).toInt() + 1).coerceIn(2, 5)
            val rows = (ceil(box.height / PROBE_DEGREES).toInt() + 1).coerceIn(2, 5)
            val out = LinkedHashSet<LatLon>()
            for (r in 0 until rows) for (c in 0 until cols) {
                out += LatLon(box.south + box.height * r / (rows - 1), box.west + box.width * c / (cols - 1))
            }
            out += box.center
            return out.toList()
        }

        /** A run as [sources] keeps it, from the service's job: null without tiles or a licence. */
        internal fun source(j: JsonObject, job: Long, name: String): Source? {
            val tiles = j.text("pmtiles_url") ?: return null
            val sourceName = j.text("source_name") ?: name
            val (licence, credit) = licence(j["license"], sourceName) ?: return null
            return Source(sourceName, job, licence, credit, bounds(j["bounds"]), tiles, (j["count"] as? JsonPrimitive)?.longOrNull ?: 0)
        }

        /**
         * A source's licence and credit from its `license`: an object with the licence's name or address and maybe the
         * credit it asks for ("attribution name"), else the source's website or name; or a licence's name alone. Null
         * for none (false, or nothing named).
         */
        internal fun licence(e: JsonElement?, source: String): Pair<String, String>? = when (e) {
            is JsonPrimitive -> e.contentOrNull?.trim()?.takeIf { e.isString && it.isNotEmpty() }?.let { it to source }
            is JsonObject -> {
                val text = e.text("text") ?: e.text("url")
                text?.let { it to (e.text("attribution name") ?: e.text("website")?.let(::hostOf) ?: source) }
            }
            else -> null
        }

        /** The box of a run's GeoJSON polygon (or multipolygon). */
        internal fun bounds(e: JsonElement?): BBox? {
            val coords = (e as? JsonObject)?.get("coordinates") ?: return null
            var w = Double.MAX_VALUE
            var s = Double.MAX_VALUE
            var east = -Double.MAX_VALUE
            var n = -Double.MAX_VALUE
            fun walk(x: JsonElement) {
                val a = x as? JsonArray ?: return
                val lon = (a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull
                val lat = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull
                if (a.size >= 2 && lon != null && lat != null) {
                    w = minOf(w, lon); east = maxOf(east, lon); s = minOf(s, lat); n = maxOf(n, lat)
                } else {
                    a.forEach(::walk)
                }
            }
            walk(coords)
            return if (w <= east && s <= n) BBox(w, s, east, n) else null
        }

        /**
         * The address points of tile [z]/[x]/[y] ([tile] unpacked): those in the tile itself (not its buffer, which its
         * neighbours hold), with a number.
         */
        fun points(tile: ByteArray, z: Int, x: Int, y: Int): List<Point> {
            val out = ArrayList<Point>()
            val n = (1 shl z).toDouble()
            for (layer in VectorTilePoints.read(tile, KEYS)) {
                val e = layer.extent.toDouble()
                val v = layer.values
                for (i in 0 until layer.size) {
                    val px = layer.x[i]
                    val py = layer.y[i]
                    if (px < 0 || py < 0 || px >= layer.extent || py >= layer.extent) continue
                    val number = v[0][i]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    out += Point(
                        lat = Mercator.lat((y + py / e) / n), lon = Mercator.lon((x + px / e) / n), number = number,
                        street = v[1][i].blankless(), unit = v[2][i].blankless(), city = v[3][i].blankless(),
                        district = v[4][i].blankless(), postcode = v[5][i].blankless(),
                    )
                }
            }
            return out
        }

        /**
         * A name as a street sign shows it: one written in capitals ("VIA DEL CAMPO", "RUE DE LA PAIX") in lower case
         * with capitals beginning its words, the small linking words left small ("Via del Campo"); any other as it came,
         * spaces tidied.
         */
        fun tidy(name: String): String {
            val s = name.trim().replace(SPACES, " ")
            if (s.any { it.isLowerCase() } || s.count { it.isLetter() } < 2) return s
            return s.lowercase(Locale.ROOT).split(' ').withIndex().joinToString(" ") { (i, w) -> word(w, first = i == 0) }
        }

        private fun word(w: String, first: Boolean): String {
            // "d'italie" is "d'Italie"; "saint-germain-des-prés" is "Saint-Germain-des-Prés"
            val apostrophe = w.indexOfAny(charArrayOf('\'', '’'))
            if (apostrophe in 1..2 && apostrophe < w.length - 1) {
                val head = w.substring(0, apostrophe + 1)
                val rest = word(w.substring(apostrophe + 1), first = false)
                return (if (first) head.cap() else head) + rest
            }
            if (!first && w in SMALL) return w
            return w.split('-').withIndex().joinToString("-") { (i, part) -> if (i > 0 && part in SMALL) part else part.cap() }
        }

        private fun String.cap(): String = replaceFirstChar { it.titlecase(Locale.ROOT) }

        /** Linking words left small inside a name: French, Italian, Spanish, Portuguese, Dutch, German, English. */
        private val SMALL = setOf(
            "de", "du", "des", "la", "le", "les", "et", "en", "sur", "sous", "aux", "au", "à", "lès", "di", "da", "del",
            "dei", "degli", "della", "delle", "dello", "dal", "dalla", "in", "ai", "alla", "alle", "allo", "agli", "sul",
            "sulla", "sui", "nel", "nella", "e", "y", "do", "dos", "das", "van", "der", "den", "het", "ten", "ter", "op",
            "aan", "von", "zu", "zum", "zur", "am", "im", "an", "auf", "of", "the", "and",
        )

        private val SPACES = Regex("\\s+")

        private fun String?.blankless(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

        private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()

        private fun f(v: Double) = String.format(Locale.ROOT, "%.5f", v)
    }
}
