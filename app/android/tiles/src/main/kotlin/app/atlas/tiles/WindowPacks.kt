package app.atlas.tiles

import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp

/**
 * A window's data from Atlas's own packs (ENGINE.md step 3: the phone's first source for a window's data, before
 * Overpass): the published packs ([packs], packs.json's) whose box holds the window and that have windows' data, newest
 * first; from the first whose index lists the window, its bytes, read by HTTP range from the pack's windows file and
 * held to the index's SHA-256.
 *
 * Each pack's index ([WindowsIndex]) is downloaded whole once, held to packs.json's SHA-256, and kept in [dir] (one per
 * pack, the one of an older release removed), so a window costs one range of its own bytes. An index of another level
 * or window query than the phone's is not used. One instance per app; its calls block, on the windows' own thread.
 */
class WindowPacks(
    /** The published packs: the catalog's list (kept for offline), or throws where there is none. */
    private val packs: () -> List<Pack>,
    /** Where the packs' indexes are kept. */
    private val dir: File,
    private val client: OkHttpClient = Http.client,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A window's data: Overpass's answer for its box as of [date], gzipped, from pack [pack]. */
    class Data(val gz: ByteArray, val date: Instant, val pack: String)

    /** The indexes read, by pack id: the index's SHA-256 and the index. */
    private val indexes = HashMap<String, Pair<String, WindowsIndex>>()

    /** When the list of packs last could not be had: it is not asked for again within [LIST_RETRY_MS]. */
    @Volatile
    private var listFailed = Long.MIN_VALUE

    /**
     * Window ([x], [y]) of level [z]'s data from the newest pack that holds it, of data newer than [newerThan] where one
     * is given; null where no pack holds it (none newer, or no list of packs to be had). Throws IOException where a pack
     * holds it but its bytes did not come whole, or were not the ones published.
     */
    fun window(z: Int, x: Int, y: Int, newerThan: Instant?): Data? {
        if (z != LEVEL) return null
        val now = clock()
        if (listFailed != Long.MIN_VALUE && now - listFailed < LIST_RETRY_MS) return null
        val list = try {
            packs()
        } catch (_: Exception) {
            // no list to be had (offline with none kept, or none published yet): no pack holds it, and every window
            // asks GitHub again only after a while
            listFailed = now
            return null
        }
        listFailed = Long.MIN_VALUE
        val lat = tileLat(y + 0.5)
        val lon = (x + 0.5) / (1 shl LEVEL) * 360.0 - 180.0
        val holding = list.filter { it.windows != null && it.bbox.west <= lon && lon <= it.bbox.east && it.bbox.south <= lat && lat <= it.bbox.north }
        for (pack in holding.sortedByDescending { it.osmTime ?: Instant.EPOCH }) {
            val index = indexOf(pack) ?: continue
            if (newerThan != null && !index.date.isAfter(newerThan)) continue
            val e = index.find(x, y) ?: continue
            val bytes = read(pack.windows!!, e.offset, e.length)
            if (!MessageDigest.getInstance("SHA-256").digest(bytes).contentEquals(e.sha256)) {
                throw IOException("window $z/$x/$y of pack ${pack.id}: not the bytes its index names")
            }
            return Data(bytes, index.date, pack.id)
        }
        return null
    }

    /** [pack]'s index, from memory, from [dir], else downloaded; null where it is not one for the phone's windows. */
    @Synchronized
    private fun indexOf(pack: Pack): WindowsIndex? {
        val file = pack.windowsIndex ?: return null
        val sha = file.sha256?.lowercase() ?: throw IOException("pack ${pack.id}: its windows' index names no SHA-256")
        val index = indexes[pack.id]?.takeIf { it.first == sha }?.second ?: run {
            val kept = File(dir, "${pack.id}-${sha.take(16)}.idx")
            val bytes = kept.takeIf { it.isFile }?.readBytes()?.takeIf { hex(it) == sha } ?: download(file, sha).also { b ->
                dir.mkdirs()
                // the index of the pack's older release, if any
                val older = Regex(Regex.escape(pack.id) + "-[0-9a-f]{16}\\.idx")
                dir.listFiles { f -> older.matches(f.name) }.orEmpty().forEach { it.delete() }
                val part = File(kept.path + ".part")
                part.writeBytes(b)
                if (!part.renameTo(kept)) part.delete()
            }
            val read = try {
                WindowsIndex.read(bytes)
            } catch (e: IllegalArgumentException) {
                throw IOException("pack ${pack.id}: its windows' index does not read: ${e.message}", e)
            }
            indexes[pack.id] = sha to read
            read
        }
        return index.takeIf { it.level == LEVEL && it.query == WindowsIndex.QUERY }
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
    private fun read(file: PackFile, offset: Long, length: Int): ByteArray {
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

        /** How long no list of packs to be had keeps a window from asking for it again. */
        const val LIST_RETRY_MS = 10 * 60 * 1000L

        /** The largest index read: a region of the world's every L14 tile on land would stay under it. */
        private const val MAX_INDEX = 64L shl 20

        private fun hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        /** The latitude of the Web Mercator row [row] (fractional) of level [LEVEL]. */
        private fun tileLat(row: Double): Double {
            val n = PI * (1.0 - 2.0 * row / (1 shl LEVEL))
            return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
        }
    }
}
