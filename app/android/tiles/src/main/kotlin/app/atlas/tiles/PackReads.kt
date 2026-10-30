package app.atlas.tiles

import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp

/**
 * How the phone reads Atlas's packs in place (ENGINE.md step 3), for [WindowPacks] (a window's data) and [WindowTiles]
 * (a window's pack from its country's tiles): the published list, which is not asked for again for a while where none
 * is to be had; a pack's index, downloaded whole once, held to packs.json's SHA-256 and kept in [dir] so it works
 * offline; and a range of a file published in parts, one range of each part it spans. GitHub answers a release
 * download with a redirect to its storage host, which OkHttp follows with the Range header kept. Its calls block.
 */
internal class PackReads(
    /** The published packs: the catalog's list (kept for offline), or throws where there is none. */
    private val packs: () -> List<Pack>,
    /** Where the packs' indexes are kept. */
    private val dir: File,
    client: OkHttpClient,
    private val clock: () -> Long,
) {
    // a dense window's data or pack is megabytes, a country's index more: no limit on the whole call, which the app's
    // shared client sets at 60 s, only on each wait for bytes, so a slow line still takes them, not the public servers
    private val client = client.newBuilder().callTimeout(0, TimeUnit.SECONDS).build()

    /** When the list of packs last could not be had: it is not asked for again within [LIST_RETRY_MS]. */
    @Volatile
    private var listFailed = Long.MIN_VALUE

    /**
     * The published packs; null where there is no list to be had (offline with none kept, or none published yet), and
     * then, for [LIST_RETRY_MS], without asking again: every window would ask GitHub otherwise.
     */
    fun list(): List<Pack>? {
        val now = clock()
        if (listFailed != Long.MIN_VALUE && now - listFailed < LIST_RETRY_MS) return null
        val list = try {
            packs()
        } catch (_: Exception) {
            listFailed = now
            return null
        }
        listFailed = Long.MIN_VALUE
        return list
    }

    /**
     * The index [file] of [pack], whole: the copy kept in [dir] (`<pack>-<its SHA-256's first 16 digits><suffix>`)
     * where it is the one published, else downloaded, held to packs.json's SHA-256, and kept there in place of the copy
     * of the pack's older release. [suffix] tells one kind of index from another.
     */
    fun index(pack: Pack, file: PackFile, suffix: String): ByteArray {
        val sha = file.sha256?.lowercase() ?: throw IOException("pack ${pack.id}: its ${file.name} names no SHA-256")
        val kept = File(dir, "${pack.id}-${sha.take(16)}$suffix")
        kept.takeIf { it.isFile }?.readBytes()?.takeIf { hex(it) == sha }?.let { return it }
        val bytes = download(file, sha)
        dir.mkdirs()
        // the index of the pack's older release, if any
        val older = Regex(Regex.escape(pack.id) + "-[0-9a-f]{16}" + Regex.escape(suffix))
        dir.listFiles { f -> older.matches(f.name) }.orEmpty().forEach { it.delete() }
        val part = File(kept.path + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(kept)) part.delete()
        return bytes
    }

    /** The index [file], whole, held to [sha]. */
    private fun download(file: PackFile, sha: String): ByteArray {
        if (file.bytes > MAX_INDEX) throw IOException("${file.name}: an index of ${file.bytes} bytes")
        val bytes = ByteArray(file.bytes.toInt())
        var at = 0
        for (p in file.parts) {
            get(p.url, null).use { r ->
                if (r.code != 200) throw HttpStatusException(r.code, "HTTP ${r.code} fetching ${p.url}")
                at += fill(r.body.source(), bytes, at, p.bytes.toInt(), p.url)
            }
        }
        if (hex(bytes) != sha) throw IOException("${file.name}: not the index published")
        return bytes
    }

    /** Bytes [offset] to [offset] + [length] of [file], a range of each of its parts they lie in. */
    fun range(file: PackFile, offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var filled = 0
        var start = 0L
        for (p in file.parts) {
            val end = start + p.bytes
            val from = offset + filled
            if (filled < length && from < end) {
                val take = minOf(end - from, (length - filled).toLong()).toInt()
                val a = from - start
                get(p.url, "bytes=$a-${a + take - 1}").use { r ->
                    // a range from where it was asked, or the whole part where that is the range; never a big part whole
                    val whole = r.code == 200 && a == 0L && take.toLong() == p.bytes
                    if (!whole && (r.code != 206 || PackDownloader.rangeStart(r.header("Content-Range")) != a)) {
                        throw HttpStatusException(r.code, "HTTP ${r.code} (${r.header("Content-Range")}) for bytes $a to ${a + take} of ${p.url}")
                    }
                    filled += fill(r.body.source(), out, filled, take, p.url)
                }
            }
            start = end
        }
        if (filled != length) throw IOException("${file.name}: bytes $offset to ${offset + length} beyond its ${file.bytes}")
        return out
    }

    private fun get(url: String, range: String?): okhttp3.Response {
        val req = Http.request(url).header("Accept-Encoding", "identity")
        if (range != null) req.header("Range", range)
        return client.newCall(req.build()).execute()
    }

    /** Reads exactly [n] bytes of [source] into [into] at [at]: [n]; throws where they end sooner. */
    private fun fill(source: okio.BufferedSource, into: ByteArray, at: Int, n: Int, url: String): Int {
        var done = 0
        while (done < n) {
            val r = source.read(into, at + done, n - done)
            if (r < 0) throw IOException("$url: ended after $done of $n bytes")
            done += r
        }
        return n
    }

    companion object {
        /** The level of the phone's windows (load::window_level). */
        const val LEVEL = 14

        /**
         * How long no list of packs to be had keeps a window from asking for it again: half a minute, so a phone whose
         * network was not up yet as the map began takes its country's tiles as soon as it is, not ten minutes on (until
         * 30 October), building every window from its data in between. The catalog keeps the list it has.
         */
        const val LIST_RETRY_MS = 30 * 1000L

        /** The largest index read: a region of the world's every L14 tile on land would stay under it. */
        private const val MAX_INDEX = 64L shl 20

        fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

        /** [b]'s SHA-256, lower-case hex. */
        fun hex(b: ByteArray): String = sha256(b).joinToString("") { "%02x".format(it) }

        /** Whether [pack]'s box holds the middle of window ([x], [y]) of level [LEVEL]. */
        fun holds(pack: Pack, x: Int, y: Int): Boolean {
            val lat = tileLat(y + 0.5, LEVEL)
            val lon = (x + 0.5) / (1 shl LEVEL) * 360.0 - 180.0
            return pack.bbox.west <= lon && lon <= pack.bbox.east && pack.bbox.south <= lat && lat <= pack.bbox.north
        }

        /**
         * Whether [pack]'s box may hold tile ([z], [x], [y]): a window's middle ([holds]); a coarser level's tile, any of
         * it (a frame cell's coarse and far tiles reach over a country's border and its coast).
         */
        fun holds(pack: Pack, z: Int, x: Int, y: Int): Boolean {
            if (z == LEVEL) return holds(pack, x, y)
            val north = tileLat(y.toDouble(), z)
            val south = tileLat(y + 1.0, z)
            val west = x.toDouble() / (1 shl z) * 360.0 - 180.0
            val east = (x + 1.0) / (1 shl z) * 360.0 - 180.0
            return west <= pack.bbox.east && pack.bbox.west <= east && south <= pack.bbox.north && pack.bbox.south <= north
        }

        /** The latitude of the Web Mercator row [row] (fractional) of level [z]. */
        private fun tileLat(row: Double, z: Int): Double {
            val n = PI * (1.0 - 2.0 * row / (1 shl z))
            return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
        }
    }
}
