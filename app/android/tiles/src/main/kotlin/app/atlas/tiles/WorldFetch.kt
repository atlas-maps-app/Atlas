package app.atlas.tiles

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * The world's files on the phone (ENGINE.md step 6, "Fetch and keep"): `world.json` from the release `world-latest` at
 * most once a day, then the index and the globe of the release it names at the first start and at each new version,
 * into the world's folder ([dir]: the kept folder's `world/`, which PackList lists for the core). A file is written
 * beside its place (`<name>.download`) and moved there once it is as long as world.json says and of the SHA-256 it
 * names, so the core never lists a file half written. A new version drops the old one: its index and globe replaced,
 * and [drop] called (the app empties the world's cache of heads and chunks). The packs themselves come by ranges as the
 * map's views want them ([head], [range]: mapview's WorldReads, into the core's cache). Every request says only who is
 * asking ([Http.USER_AGENT]). Blocking: call it off the main thread, one [start] at a time.
 */
class WorldFetch(
    private val dir: File,
    private val latest: String = WORLD_LATEST,
    client: OkHttpClient = Http.client,
    private val now: () -> Long = System::currentTimeMillis,
    private val drop: () -> Unit = {},
) {
    // the globe takes a while on a phone's network: no limit on the whole call, only on each wait for bytes; nor through
    // the app's HTTP cache, which would keep a second copy (PackDownloader's reasons)
    private val client = releaseClient(client)

    // the scheme world.json came on (https): its release's files must come on it too
    private val scheme = latest.substringBefore("://")

    /** A release of the world as world.json names it: its tag, where its files are, its index and globe. */
    class Release(val tag: String, val url: String, val index: File, val globe: File) {
        /** One of its files as world.json lists it: name, bytes and SHA-256 (hex). */
        class File(val name: String, val bytes: Long, val sha256: String)
    }

    /** The world on the phone once [start] found or fetched it: its release and its index. */
    class World(val release: Release, val index: WorldIndex)

    /** The world on the phone, once [start] found or fetched it; null before. */
    @Volatile
    var world: World? = null
        private set

    /** The index on the phone, once [start] found or fetched it; null before. */
    val index: WorldIndex? get() = world?.index

    /**
     * The tiles of the world on the phone that its kept countries keep (ENGINE.md step 11: the release's
     * `world-keep.json`, [WorldKeepList]), once [start] found or fetched it; null before, and for a world whose release
     * has none.
     */
    @Volatile
    var keep: WorldKeepList? = null
        private set

    /** Whether world.json was asked for a day ago or more (or never, or the clock went back): [start] asks again. */
    fun due(): Boolean {
        val asked = runCatching { File(dir, ASKED).readText().trim().toLong() }.getOrNull() ?: return true
        val t = now()
        return t - asked >= DAY_MS || t < asked
    }

    /**
     * world.json, asked for where it is [due] (else the phone's), then the index and the globe of the release it names,
     * each fetched where the phone has another release's or none; a new release drops the old one ([drop]). Returns
     * whether the world on the phone changed (the maps take their packs again). Throws where world.json, the index or
     * the globe cannot be had and the phone has no whole world to go on with.
     */
    @Synchronized
    fun start(): Boolean {
        dir.mkdirs()
        val json = File(dir, JSON)
        if (due() || !json.isFile) {
            val asked = now()
            val fetched = runCatching {
                fetch(latest + JSON, json, -1, null) { readRelease(it.readText(), scheme) }
                File(dir, ASKED).writeText("$asked\n")
            }
            val phone = world ?: onPhone()
            if (fetched.isFailure && phone != null) {
                // the phone's world goes on until world-latest answers
                val changed = world == null
                world = phone
                return changed
            }
            fetched.getOrThrow()
        }
        val release = readRelease(json.readText(), scheme)
        val kept = runCatching { File(dir, RELEASE).readText().trim() }.getOrNull()
        val idx = File(dir, INDEX)
        val globe = File(dir, GLOBE)
        val same = kept == release.tag && idx.length() == release.index.bytes && globe.length() == release.globe.bytes
        if (!same) {
            // the new release's index and globe beside the old ones, then both moved in: the old version dropped
            val newIdx = File(dir, "$INDEX.new")
            val newGlobe = File(dir, "$GLOBE.new")
            try {
                fetch(release.url + release.index.name, newIdx, release.index.bytes, release.index.sha256) { WorldIndex.read(it.readBytes()) }
                fetch(release.url + release.globe.name, newGlobe, release.globe.bytes, release.globe.sha256) {}
                File(dir, RELEASE).delete()
                idx.delete()
                globe.delete()
                if (!newIdx.renameTo(idx) || !newGlobe.renameTo(globe)) throw IOException("the world's $INDEX and $GLOBE not moved into place")
                File(dir, RELEASE).writeText(release.tag + "\n")
            } finally {
                newIdx.delete()
                newGlobe.delete()
            }
            if (kept != null && kept != release.tag) drop()
        }
        val before = world
        val now = World(release, WorldIndex.read(idx.readBytes()))
        world = now
        val listBefore = keep
        val list = keepOf(release)
        keep = list
        return before == null || before.release.tag != now.release.tag || !same || listBefore?.indexSha256 != list?.indexSha256
    }

    /**
     * The keep list of [release]: the phone's where it is that release's (its index's SHA-256), else fetched from the
     * release at most once a day (a release made before the keep has none, and a later run of world.yml's refs job
     * adds it). Null where the release has none or it did not come: the world goes on without it.
     */
    private fun keepOf(release: Release): WorldKeepList? {
        val file = File(dir, WorldKeepList.NAME)
        fun held() = runCatching { WorldKeepList.read(file.readText()) }.getOrNull()?.takeIf { it.indexSha256 == release.index.sha256 }
        held()?.let { return it }
        val marker = File(dir, KEEP_ASKED)
        val asked = runCatching { marker.readText().trim().split(' ') }.getOrNull()
        val t = now()
        val at = asked?.getOrNull(1)?.toLongOrNull()
        if (asked?.getOrNull(0) == release.tag && at != null && t - at < DAY_MS && t >= at) return null
        try {
            fetch(release.url + WorldKeepList.NAME, file, -1, null) { f ->
                if (WorldKeepList.read(f.readText()).indexSha256 != release.index.sha256) throw IOException("${WorldKeepList.NAME} is another world's")
            }
        } catch (_: Exception) {
            // none for this release (404), or it did not come: asked again a day on
            file.delete()
        }
        runCatching { marker.writeText("${release.tag} $t\n") }
        return held()
    }

    /** The world as the phone keeps it (world.json, and its release's index and globe), or null where it is not whole. */
    private fun onPhone(): World? = runCatching {
        val release = readRelease(File(dir, JSON).readText(), scheme)
        val idx = File(dir, INDEX)
        val whole = File(dir, RELEASE).readText().trim() == release.tag && idx.length() == release.index.bytes &&
            File(dir, GLOBE).length() == release.globe.bytes
        if (whole) {
            keep = runCatching { WorldKeepList.read(File(dir, WorldKeepList.NAME).readText()) }.getOrNull()?.takeIf { it.indexSha256 == release.index.sha256 }
            World(release, WorldIndex.read(idx.readBytes()))
        } else {
            null
        }
    }.getOrNull()

    /**
     * The head of pack [e] of the world on the phone (ENGINE.md step 6): its bytes but its `chunks` section, as ranges
     * (each its offset in the pack and its bytes), which the core keeps in the world's cache. Its first [HEAD_FIRST]
     * bytes, then what of the head lies past them, a request each: the rest of its directory where it runs past them,
     * the rest before its chunks, and the sections after them (one request for a pack no longer than [HEAD_FIRST]).
     */
    fun head(e: WorldIndex.Entry): List<Pair<Long, ByteArray>> = headOf(e.length) { offset, length -> range(e, offset, length) }

    /** Bytes [offset] to [offset] + [length] of pack [e] of the world on the phone: one range of its part. */
    fun range(e: WorldIndex.Entry, offset: Long, length: Int): ByteArray {
        val w = world ?: throw IOException("the world is not on the phone yet")
        require(offset >= 0 && length > 0 && offset + length <= e.length) { "bytes $offset to ${offset + length} of a pack of ${e.length}" }
        return rangeOf(client, w.release.url + w.index.parts[e.part].name, e.offset + offset, length)
    }

    /**
     * The world's files for the life of the process: [start] once the phone is [online], then again each day (world.json
     * at most once a day). What failed is tried again once the phone is online: [firstWait] on, doubling to
     * [longestWait] while it fails, or at once where the phone loses its network and comes back meanwhile (a phone's
     * network goes now and then: on 1 November CI's emulator moved from its Wi-Fi to its mobile network as the world was
     * asked for). [said] gets the `vk world` lines (CI's world phase reads them), [landed] is called as the world on the
     * phone changes. Blocking calls: run it off the main thread. Never returns.
     */
    suspend fun follow(
        online: StateFlow<Boolean>,
        said: (String) -> Unit,
        landed: () -> Unit,
        firstWait: Long = FIRST_WAIT_MS,
        longestWait: Long = LONGEST_WAIT_MS,
    ): Nothing {
        var wait = firstWait
        while (true) {
            online.first { it }
            val started = runCatching { start() }
            val w = world
            if (started.isSuccess && w != null) {
                if (started.getOrThrow()) {
                    said("$INDEX (${w.index.entries.size} packs) and $GLOBE of ${w.release.tag} on the phone")
                    landed()
                }
                wait = firstWait
                // the next look at world.json once a day has passed since the last
                while (!due()) delay(DUE_CHECK_MS)
                continue
            }
            said("not on the phone yet: ${started.exceptionOrNull()?.message}")
            withTimeoutOrNull(wait) {
                online.first { !it }
                online.first { it }
            }
            wait = (wait * 2).coerceAtMost(longestWait)
        }
    }

    /**
     * Fetches [url] into [target] by way of `<target>.download`, moved into place once it is [bytes] long (-1: as its
     * answer said) with the SHA-256 [sha256] (null: any) and [check] passes.
     */
    private fun fetch(url: String, target: File, bytes: Long, sha256: String?, check: (File) -> Unit) = fetchFile(client, url, target, bytes, sha256, JSON, check)

    companion object {
        /**
         * [client] for a release's files: no limit on the whole call, only on each wait for bytes; nor through the app's
         * HTTP cache, which would keep a second copy (PackDownloader's reasons).
         */
        internal fun releaseClient(client: OkHttpClient): OkHttpClient =
            client.newBuilder().cache(null).callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

        /**
         * The head of a pack of [length] bytes, by [read] (a range of the pack: its offset and length): its first
         * [HEAD_FIRST] bytes, then what of the head lies past them, a request each ([head]'s).
         */
        internal fun headOf(length: Long, read: (Long, Int) -> ByteArray): List<Pair<Long, ByteArray>> {
            var first = read(0, minOf(length, HEAD_FIRST.toLong()).toInt())
            val need = directoryEnd(first)
            if (need > first.size) first += read(first.size.toLong(), need - first.size)
            val (start, end) = chunksOf(first, length)
            val before = if (start <= first.size) first.copyOf(start.toInt()) else first + read(first.size.toLong(), (start - first.size).toInt())
            if (end >= length) return listOf(0L to before)
            val after = if (first.size >= length) first.copyOfRange(end.toInt(), length.toInt()) else read(end, (length - end).toInt())
            return listOf(0L to before, end to after)
        }

        /** Bytes [a] to [a] + [length] of the file at [url], by one range request: refused unless it answers 206 from [a]. */
        internal fun rangeOf(client: OkHttpClient, url: String, a: Long, length: Int): ByteArray {
            val req = Http.request(url).header("Accept-Encoding", "identity").header("Range", "bytes=$a-${a + length - 1}")
            client.newCall(req.build()).execute().use { r ->
                if (r.code != 206 || PackDownloader.rangeStart(r.header("Content-Range")) != a) {
                    throw HttpStatusException(r.code, "HTTP ${r.code} (${r.header("Content-Range")}) for bytes $a to ${a + length} of $url")
                }
                val out = ByteArray(length)
                var done = 0
                val source = r.body.source()
                while (done < length) {
                    val n = source.read(out, done, length - done)
                    if (n < 0) throw IOException("$url: bytes $a to ${a + length} cut short at ${a + done}")
                    done += n
                }
                return out
            }
        }

        /**
         * Fetches [url] into [target] by way of `<target>.download`, moved into place once it is [bytes] long (-1: as its
         * answer said) with the SHA-256 [sha256] (null: any) and [check] passes; [list] names the list that names it.
         */
        internal fun fetchFile(client: OkHttpClient, url: String, target: File, bytes: Long, sha256: String?, list: String, check: (File) -> Unit) {
            val partial = File(target.path + ".download")
            try {
                partial.delete()
                client.newCall(Http.request(url).header("Accept-Encoding", "identity").build()).execute().use { r ->
                    if (r.code != 200) throw HttpStatusException(r.code, "HTTP ${r.code} fetching $url")
                    val declared = r.body.contentLength()
                    val digest = MessageDigest.getInstance("SHA-256")
                    partial.outputStream().use { out ->
                        val source = r.body.byteStream()
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            val n = source.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                    val want = if (bytes >= 0) bytes else declared
                    if (want >= 0 && partial.length() != want) throw IOException("$url: ${partial.length()} bytes of $want")
                    if (sha256 != null && hex(digest.digest()) != sha256) {
                        throw PackDownloader.ChecksumException("$url is not the file $list names")
                    }
                }
                check(partial)
                target.delete()
                if (!partial.renameTo(target)) throw IOException("$url: not moved into place")
            } finally {
                partial.delete()
            }
        }

        /** The release world.yml's publish points at the newest world (ENGINE.md step 5): its world.json alone. */
        const val WORLD_LATEST = "https://github.com/atlas-maps-app/Atlas/releases/download/world-latest/"

        /** world.json, the index's file and the globe's (the core's tiles/WorldIndex.hpp, PackList's). */
        const val JSON = "world.json"
        const val INDEX = "world.idx"
        const val GLOBE = "world-globe.atlas"

        /** The release the index and the globe on the phone are of, and when world.json was last asked for. */
        private const val RELEASE = "world.release"
        private const val ASKED = "world.asked"

        /** The release the keep list was last asked for, and when. */
        private const val KEEP_ASKED = "world-keep.asked"

        private const val BUFFER = 1 shl 16

        /** A day: world.json is asked for at most once in it. */
        const val DAY_MS = 24 * 3_600_000L
        /** How often [follow] looks whether world.json is [due]. */
        internal const val DUE_CHECK_MS = 3_600_000L

        /** A head's first request: the container's header, its manifest and directory, and most of what follows. */
        const val HEAD_FIRST = 64 shl 10

        /** [follow]'s first wait after a failure, and its longest. */
        const val FIRST_WAIT_MS = 15_000L
        const val LONGEST_WAIT_MS = 600_000L

        /** The container's header: `ATLASPCK`, a zero byte, the format, two zero bytes, the manifest's and directory's lengths (big-endian u32). */
        private const val HEADER = 20
        private const val MAGIC = "ATLASPCK"

        /** A directory entry after its name: offset, stored and raw lengths (big-endian u64), CRC-32 (u32) and method. */
        private const val ENTRY = 29

        /** world.json's release: its tag, where its files are (on [scheme], world-latest's), its index and globe. */
        fun readRelease(text: String, scheme: String = "https"): Release {
            val o = Json.parseObject(text)
            require(o.int("format") == 1) { "world.json of format ${o["format"]}" }
            fun file(name: String): Release.File {
                val f = requireNotNull(o.obj(name)) { "world.json without its $name" }
                val sha = f.str("sha256")?.lowercase()
                require(sha != null && SHA256.matches(sha)) { "world.json's $name without its SHA-256" }
                val bytes = f.long("bytes")
                require(bytes != null && bytes > 0) { "world.json's $name without its length" }
                return Release.File(requireNotNull(f.str("name")) { "world.json's $name without its name" }, bytes, sha)
            }
            val tag = requireNotNull(o.str("release")) { "world.json without its release" }
            val url = requireNotNull(o.str("url")) { "world.json without its files' place" }.let { if (it.endsWith("/")) it else "$it/" }
            require(url.startsWith("$scheme://")) { "world.json's files not on $scheme: $url" }
            return Release(tag, url, file("index"), file("globe"))
        }

        private val SHA256 = Regex("[0-9a-f]{64}")

        /** Where a pack's manifest and directory end, by its first bytes [first] (its header's lengths). */
        fun directoryEnd(first: ByteArray): Int {
            require(first.size >= HEADER && String(first, 0, MAGIC.length, Charsets.US_ASCII) == MAGIC) { "not an Atlas pack" }
            val b = ByteBuffer.wrap(first)
            val end = HEADER + (b.getInt(12).toLong() and 0xFFFFFFFFL) + (b.getInt(16).toLong() and 0xFFFFFFFFL)
            require(end <= Int.MAX_VALUE) { "a pack's directory of $end bytes" }
            return end.toInt()
        }

        /** The `chunks` section of a pack of [length] bytes whose first bytes, through its directory, are [first]: its start and end. */
        fun chunksOf(first: ByteArray, length: Long): Pair<Long, Long> {
            val end = directoryEnd(first)
            require(first.size >= end) { "a pack's directory runs past the bytes read" }
            val b = ByteBuffer.wrap(first)
            var at = HEADER + b.getInt(12)
            while (at < end) {
                var n = 0
                var shift = 0
                while (true) {
                    require(at < end && shift < 35) { "a pack's directory cut short" }
                    val c = first[at++].toInt() and 0xFF
                    n = n or ((c and 0x7F) shl shift)
                    if (c and 0x80 == 0) break
                    shift += 7
                }
                require(n <= end - at - ENTRY) { "a section's name runs past the directory" }
                val name = String(first, at, n, Charsets.UTF_8)
                at += n
                val offset = b.getLong(at)
                val stored = b.getLong(at + 8)
                at += ENTRY
                if (name == "chunks") {
                    require(offset >= end && stored >= 0 && offset + stored <= length) { "a pack's chunks lie past it" }
                    return offset to offset + stored
                }
            }
            throw IOException("a pack without chunks")
        }

        /** The packs of [index] holding the place at [lat], [lon]: its L6 tile's fine pack and its L3 tile's coarse pack, where it has them. */
        fun packsAt(index: WorldIndex, lat: Double, lon: Double): List<WorldIndex.Entry> =
            listOf(WorldIndex.FINE_BLOCK, WorldIndex.COARSE_BLOCK).mapNotNull { level ->
                val (x, y) = tileOf(lat, lon, level)
                index.find(level, x, y)
            }

        /** The store's id of a world pack (the core's tiles::world_pack_id): `world/<z>/<x>/<y>`, its block. */
        fun idOf(e: WorldIndex.Entry): String = "world/${e.z}/${e.x}/${e.y}"

        /** Web Mercator's tile at [level] holding [lat], [lon] (clamped to the tiles' 85.05°). */
        fun tileOf(lat: Double, lon: Double, level: Int): Pair<Int, Int> {
            val n = 1 shl level
            val la = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
            val x = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            val y = floor((1.0 - ln(tan(la) + 1.0 / cos(la)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
            return x to y
        }

        private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

        private const val MAX_LAT = 85.0511287798066
    }
}
