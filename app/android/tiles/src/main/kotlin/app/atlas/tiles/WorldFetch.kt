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
 *
 * Where the phone keeps a country's world ([hold], ENGINE.md step 11: the world on the phone lists a keep, and a
 * country is kept by its tiles), a new version does not drop the old one: it waits beside it ([next]: its world.json,
 * index, globe and keep list under `<name>.next`, which the core does not list), the phone's world going on as it was,
 * until the app holds the new one's keep whole in the world's cache and takes it ([adopt]). So a phone that goes
 * offline at any moment draws a kept country's world: the old one's until then, the new one's after.
 */
class WorldFetch(
    private val dir: File,
    private val latest: String = WORLD_LATEST,
    client: OkHttpClient = Http.client,
    private val now: () -> Long = System::currentTimeMillis,
    private val drop: () -> Unit = {},
    private val hold: () -> Boolean = { false },
) {
    // the globe takes a while on a phone's network: no limit on the whole call, only on each wait for bytes; nor through
    // the app's HTTP cache, which would keep a second copy (PackDownloader's reasons)
    private val client = client.newBuilder().cache(null).callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

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

    /**
     * The newer world that waits beside the phone's until its kept countries' world is whole ([hold], [adopt]): its
     * release and its index, both on the phone; null where none waits.
     */
    @Volatile
    var next: World? = null
        private set

    /** The keep list of [next], where its release has one and it came; null else (asked for again a day on). */
    @Volatile
    var nextKeep: WorldKeepList? = null
        private set

    /** Whether world.json was asked for a day ago or more (or never, or the clock went back): [start] asks again. */
    fun due(): Boolean {
        val asked = runCatching { File(dir, ASKED).readText().trim().toLong() }.getOrNull() ?: return true
        val t = now()
        return t - asked >= DAY_MS || t < asked
    }

    /**
     * world.json, asked for where it is [due] (else the phone's), then the index and the globe of the release it names,
     * each fetched where the phone has another release's or none. A new release drops the old one ([drop]), or, where
     * the old one's keep is held ([hold]), waits beside it ([next]) for [adopt]. Returns whether the world on the
     * phone, its keep list, or the world that waits changed (the maps take their packs again, and the app looks at the
     * kept countries' world). Throws where world.json, the index or the globe cannot be had: the phone's world, where
     * it has a whole one, goes on meanwhile ([world] is set before).
     */
    @Synchronized
    fun start(): Boolean {
        dir.mkdirs()
        val worldBefore = world
        val keepBefore = keep
        val nextBefore = next
        val nextKeepBefore = nextKeep
        val tagBefore = runCatching { File(dir, RELEASE).readText().trim() }.getOrNull()
        // world-latest's world.json lands beside the phone's (`world.json.next`): the phone's own stays whole with its
        // index and globe until a new release's are on the phone too
        val newest = File(dir, JSON + NEXT)
        var phone = onPhone()
        if (due() || (phone == null && !newest.isFile)) {
            val asked = now()
            val fetched = runCatching {
                fetch(latest + JSON, newest, -1, null) { readRelease(it.readText(), scheme) }
                File(dir, ASKED).writeText("$asked\n")
            }
            // the phone's world goes on until world-latest answers
            if (fetched.isFailure && phone == null && !newest.isFile) fetched.getOrThrow()
        }
        val release = runCatching { readRelease(newest.readText(), scheme) }.getOrNull()
        var replaced = false
        var failed: Throwable? = null
        if (release == null) {
            unstage()
        } else if (phone != null && sameWorld(phone.release, release)) {
            // world-latest names the phone's world still: its world.json as it is now, and nothing waits
            val json = File(dir, JSON)
            if (!newest.renameTo(json)) {
                json.delete()
                newest.renameTo(json)
            }
            unstage()
            phone = onPhone() ?: phone
        } else {
            val list = phone?.let { listOnPhone(it.release, File(dir, WorldKeepList.NAME)) }
            if (phone != null && list != null && hold()) {
                // the old world's keep is held: the new one waits beside it until its own is whole (adopt)
                try {
                    next = stage(release)
                    nextKeep = keepOf(release, File(dir, WorldKeepList.NAME + NEXT))
                } catch (e: Exception) {
                    next = null
                    nextKeep = null
                    failed = e
                }
            } else {
                // the new release's index and globe beside the old ones, then both moved in: the old version dropped
                try {
                    stage(release)
                    moveIn()
                    replaced = true
                    phone = onPhone()
                    if (tagBefore != null && tagBefore != release.tag) drop()
                } catch (e: Exception) {
                    failed = e
                }
                next = null
                nextKeep = null
            }
        }
        val mine = phone ?: throw (failed ?: IOException("the world is not whole on the phone"))
        // the same world goes on as the object the maps hold
        if (worldBefore == null || !sameWorld(worldBefore.release, mine.release) || worldBefore.release.url != mine.release.url || replaced) world = mine
        keep = keepOf(mine.release, File(dir, WorldKeepList.NAME))
        failed?.let { throw it }
        return worldBefore == null || worldBefore.release.tag != mine.release.tag || replaced || keepBefore?.indexSha256 != keep?.indexSha256 ||
            nextBefore?.release?.tag != next?.release?.tag || nextKeepBefore?.indexSha256 != nextKeep?.indexSha256
    }

    /**
     * Takes the world that waits ([next]) as the phone's: its world.json, index, globe and keep list moved into
     * place, [world] and [keep] its own from now on. The app calls it once the world's cache holds the new one's keep
     * whole (mapview's WorldKeeper), or where no country's world is kept any more; the old one's heads and chunks are
     * the caller's to let go ([drop] is not called). False where none waits. Throws where the files do not move.
     */
    @Synchronized
    fun adopt(): Boolean {
        val staged = next ?: return false
        val list = nextKeep
        moveIn()
        world = staged
        keep = list
        next = null
        nextKeep = null
        return true
    }

    /** Whether [a] and [b] are one world: the same release, index and globe. */
    private fun sameWorld(a: Release, b: Release): Boolean =
        a.tag == b.tag && a.index.bytes == b.index.bytes && a.index.sha256 == b.index.sha256 && a.globe.bytes == b.globe.bytes && a.globe.sha256 == b.globe.sha256

    /**
     * [release]'s index and globe on the phone beside the world's own (`world.idx.next`, `world-globe.atlas.next`),
     * fetched where they are not there whole (`world.release.next` names the release they are of, written after
     * both); its world.json is `world.json.next`.
     */
    private fun stage(release: Release): World {
        val idx = File(dir, INDEX + NEXT)
        val globe = File(dir, GLOBE + NEXT)
        val tag = File(dir, RELEASE + NEXT)
        val whole = runCatching { tag.readText().trim() }.getOrNull() == release.tag && idx.length() == release.index.bytes && globe.length() == release.globe.bytes
        if (!whole) {
            tag.delete()
            fetch(release.url + release.index.name, idx, release.index.bytes, release.index.sha256) { WorldIndex.read(it.readBytes()) }
            fetch(release.url + release.globe.name, globe, release.globe.bytes, release.globe.sha256) {}
            tag.writeText(release.tag + "\n")
        }
        return World(release, WorldIndex.read(idx.readBytes()))
    }

    /**
     * The staged world's files moved into place: `world.release` goes first and comes back last, so a move that stops
     * halfway leaves no world that reads as whole (the next [start] fetches it again).
     */
    private fun moveIn() {
        val tag = File(dir, RELEASE + NEXT).readText().trim()
        File(dir, RELEASE).delete()
        for (name in listOf(JSON, INDEX, GLOBE)) {
            val to = File(dir, name)
            to.delete()
            if (!File(dir, name + NEXT).renameTo(to)) throw IOException("the world's $name not moved into place")
        }
        // the keep list is its world's: the new one's where it came, none else
        val list = File(dir, WorldKeepList.NAME)
        list.delete()
        File(dir, WorldKeepList.NAME + NEXT).renameTo(list)
        File(dir, RELEASE).writeText(tag + "\n")
        File(dir, RELEASE + NEXT).delete()
    }

    /** Nothing waits beside the phone's world: the files of a world that did are deleted. */
    private fun unstage() {
        for (name in listOf(JSON, INDEX, GLOBE, RELEASE, WorldKeepList.NAME)) File(dir, name + NEXT).delete()
        next = null
        nextKeep = null
    }

    /** The keep list in [file] where it is [release]'s (its index's SHA-256), else null. */
    private fun listOnPhone(release: Release, file: File): WorldKeepList? =
        runCatching { WorldKeepList.read(file.readText()) }.getOrNull()?.takeIf { it.indexSha256 == release.index.sha256 }

    /**
     * The keep list of [release] in [file]: the phone's where it is that release's (its index's SHA-256), else fetched
     * from the release at most once a day (a release made before the keep has none, and a later run of world.yml's
     * refs job adds it). Null where the release has none or it did not come: the world goes on without it.
     */
    private fun keepOf(release: Release, file: File): WorldKeepList? {
        listOnPhone(release, file)?.let { return it }
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
        return listOnPhone(release, file)
    }

    /** The world as the phone keeps it (world.json, and its release's index and globe), or null where it is not whole. */
    private fun onPhone(): World? = runCatching {
        val release = readRelease(File(dir, JSON).readText(), scheme)
        val idx = File(dir, INDEX)
        val whole = File(dir, RELEASE).readText().trim() == release.tag && idx.length() == release.index.bytes &&
            File(dir, GLOBE).length() == release.globe.bytes
        if (whole) World(release, WorldIndex.read(idx.readBytes())) else null
    }.getOrNull()

    /**
     * The head of pack [e] of the world on the phone (ENGINE.md step 6): its bytes but its `chunks` section, as ranges
     * (each its offset in the pack and its bytes), which the core keeps in the world's cache. Its first [HEAD_FIRST]
     * bytes, then what of the head lies past them, a request each: the rest of its directory where it runs past them,
     * the rest before its chunks, and the sections after them (one request for a pack no longer than [HEAD_FIRST]). With
     * [of], a pack of that world (the one that waits, [next]).
     */
    fun head(e: WorldIndex.Entry, of: World? = null): List<Pair<Long, ByteArray>> {
        var first = range(e, 0, minOf(e.length, HEAD_FIRST.toLong()).toInt(), of)
        val need = directoryEnd(first)
        if (need > first.size) first += range(e, first.size.toLong(), need - first.size, of)
        val (start, end) = chunksOf(first, e.length)
        val before = if (start <= first.size) first.copyOf(start.toInt()) else first + range(e, first.size.toLong(), (start - first.size).toInt(), of)
        if (end >= e.length) return listOf(0L to before)
        val after = if (first.size >= e.length) first.copyOfRange(end.toInt(), e.length.toInt()) else range(e, end, (e.length - end).toInt(), of)
        return listOf(0L to before, end to after)
    }

    /**
     * Bytes [offset] to [offset] + [length] of pack [e] of the world on the phone, or of the world [of] where one is
     * given (the one that waits, [next]): one range of its part.
     */
    fun range(e: WorldIndex.Entry, offset: Long, length: Int, of: World? = null): ByteArray {
        val w = of ?: world ?: throw IOException("the world is not on the phone yet")
        require(offset >= 0 && length > 0 && offset + length <= e.length) { "bytes $offset to ${offset + length} of a pack of ${e.length}" }
        val url = w.release.url + w.index.parts[e.part].name
        val a = e.offset + offset
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
            val had = world != null
            val started = runCatching { start() }
            val w = world
            if (started.isSuccess && w != null) {
                if (started.getOrThrow()) {
                    said("$INDEX (${w.index.entries.size} packs) and $GLOBE of ${w.release.tag} on the phone")
                    next?.let { n ->
                        said("${n.release.tag} waits beside it: the kept countries' world of ${w.release.tag} stays until the new one's is whole" + if (nextKeep == null) " (it lists none yet)" else "")
                    }
                    landed()
                }
                wait = firstWait
                // the next look at world.json once a day has passed since the last
                while (!due()) delay(DUE_CHECK_MS)
                continue
            }
            if (w != null) {
                // the phone's world goes on while a new one's files do not come
                said("${w.release.tag} goes on, the newest not on the phone yet: ${started.exceptionOrNull()?.message}")
                if (!had) landed()
            } else {
                said("not on the phone yet: ${started.exceptionOrNull()?.message}")
            }
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
    private fun fetch(url: String, target: File, bytes: Long, sha256: String?, check: (File) -> Unit) {
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
                    throw PackDownloader.ChecksumException("$url is not the file world.json names")
                }
            }
            check(partial)
            target.delete()
            if (!partial.renameTo(target)) throw IOException("$url: not moved into place")
        } finally {
            partial.delete()
        }
    }

    companion object {
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

        /** The ending of the files of the world that waits beside the phone's ([next]), and of world-latest's world.json as it lands. */
        private const val NEXT = ".next"

        /** Chunks closer than this in a pack are fetched in one range with what lies between (kept too), up to [MAX_RANGE] bytes. */
        const val MERGE_GAP = 32L shl 10
        const val MAX_RANGE = 4L shl 20

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
