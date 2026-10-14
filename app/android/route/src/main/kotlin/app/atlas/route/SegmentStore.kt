package app.atlas.route

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import btools.mapaccess.PhysicalFile
import btools.mapaccess.Rd5DiffManager
import btools.mapaccess.Rd5DiffTool
import btools.util.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.floor

/** A segment file: its name ("E0_N50.rd5"), size in bytes and, when known, when it was built (Unix ms). */
data class SegmentTile(val name: String, val bytes: Long, val modified: Long = 0)

/** How far a download or update has come. [total] is −1 while unknown. */
data class SegmentProgress(val tile: String, val stage: Stage, val bytes: Long, val total: Long) {
    enum class Stage { CHECKING, DOWNLOADING, APPLYING_DIFF, VERIFYING, UP_TO_DATE, DONE }

    val fraction: Double get() = if (total <= 0) 0.0 else (bytes.toDouble() / total).coerceIn(0.0, 1.0)
}

/** A lookups.dat version: segments must be built with the same major version as the lookups the profiles use. */
data class LookupVersion(val major: Int, val minor: Int)

/** Local and published lookups versions; when the majors differ every segment file must be downloaded again. */
data class LookupsCheck(val local: LookupVersion?, val remote: LookupVersion) {
    val changed: Boolean get() = local == null || local.major != remote.major
}

/**
 * Manages BRouter's `.rd5` routing data in [dir]: which 5°×5° tiles an area needs, what is installed, what the server has,
 * resumable throttled downloads (HTTP Range, below 16 Mbit/s like the official app), daily diffs and the lookups version.
 * Nothing is hosted by Atlas: files come from brouter.de.
 */
class SegmentStore(
    val dir: File,
    private val client: OkHttpClient = defaultClient(),
    val baseUrl: String = SEGMENTS_URL,
    val profilesUrl: String = PROFILES_URL,
    /** Download speed cap in bytes per second; the default stays under 16 Mbit/s. */
    val maxBytesPerSecond: Long = 1_900_000,
) {
    fun file(tile: String): File = File(dir, normalise(tile))

    fun has(tile: String): Boolean = file(tile).isFile

    /** The installed segment files, by name. */
    fun installed(): List<SegmentTile> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".rd5") } ?: emptyArray())
            .map { SegmentTile(it.name, it.length(), it.lastModified()) }
            .sortedBy { it.name }

    fun missing(tiles: Collection<String>): List<String> = tiles.map(::normalise).distinct().filter { !has(it) }

    fun delete(tile: String): Boolean = file(tile).delete()

    /** The size of [tile] on the server, by a HEAD request; null when it has none (no data there). */
    suspend fun remoteSize(tile: String): Long? = withContext(Dispatchers.IO) {
        val req = request(baseUrl + normalise(tile)).head().build()
        client.newCall(req).execute().use { r ->
            when {
                r.code == 404 -> null
                !r.isSuccessful -> throw IOException("HEAD ${req.url} returned ${r.code}")
                else -> r.header("Content-Length")?.toLongOrNull()
            }
        }
    }

    /** Every tile the server offers, from its directory index. */
    suspend fun remoteIndex(): List<SegmentTile> = withContext(Dispatchers.IO) {
        val req = request(baseUrl).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GET ${req.url} returned ${r.code}")
            parseIndex(r.body.string())
        }
    }

    /**
     * Downloads [tile], resuming a partial download, throttled to [maxBytesPerSecond]. With [verify] the file's checksums
     * are checked before it replaces the old one.
     */
    fun download(tile: String, verify: Boolean = true): Flow<SegmentProgress> = flow {
        val name = normalise(tile)
        val part = File(dir, "$name.part")
        fetchResumable(baseUrl + name, part, name)
        install(name, part, verify)
        val size = file(name).length()
        emit(SegmentProgress(name, SegmentProgress.Stage.DONE, size, size))
    }.flowOn(Dispatchers.IO)

    /**
     * Brings [tile] up to date: with the daily diff for the local file's MD5 when the server has one (an empty diff means it is
     * current), else with a full download. A missing tile is downloaded.
     */
    fun update(tile: String, verify: Boolean = true): Flow<SegmentProgress> = flow {
        val name = normalise(tile)
        val local = file(name)
        if (!local.isFile) {
            val part = File(dir, "$name.part")
            fetchResumable(baseUrl + name, part, name)
            install(name, part, verify)
            emit(SegmentProgress(name, SegmentProgress.Stage.DONE, local.length(), local.length()))
            return@flow
        }
        emit(SegmentProgress(name, SegmentProgress.Stage.CHECKING, 0, -1))
        val md5 = Rd5DiffManager.getMD5(local)
        val diffUrl = baseUrl + "diff/" + name.removeSuffix(".rd5") + "/" + md5 + ".df5"
        val head = client.newCall(request(diffUrl).head().build()).execute().use { r -> r.code to r.header("Content-Length")?.toLongOrNull() }
        when {
            head.first == 200 && head.second == 0L -> {
                emit(SegmentProgress(name, SegmentProgress.Stage.UP_TO_DATE, local.length(), local.length()))
            }
            head.first == 200 -> {
                val diff = File(dir, "$name.df5")
                diff.delete()
                fetchResumable(diffUrl, diff, name)
                if (diff.length() == 0L) {
                    // the server did not say the length up front, but the diff is empty: nothing changed
                    diff.delete()
                    emit(SegmentProgress(name, SegmentProgress.Stage.UP_TO_DATE, local.length(), local.length()))
                    return@flow
                }
                val out = File(dir, "$name.part")
                emit(SegmentProgress(name, SegmentProgress.Stage.APPLYING_DIFF, 0, 100))
                val context = currentCoroutineContext()
                try {
                    Rd5DiffTool.recoverFromDelta(local, diff, out, object : ProgressListener {
                        override fun updateProgress(task: String, progress: Int) = Unit
                        override fun isCanceled(): Boolean = !context.isActive
                    })
                } finally {
                    diff.delete()
                }
                currentCoroutineContext().ensureActive()
                install(name, out, verify)
                emit(SegmentProgress(name, SegmentProgress.Stage.DONE, local.length(), local.length()))
            }
            head.first == 404 -> {
                // the local file is too old for a diff (diffs are kept for about a week)
                val part = File(dir, "$name.part")
                part.delete()
                fetchResumable(baseUrl + name, part, name)
                install(name, part, verify)
                emit(SegmentProgress(name, SegmentProgress.Stage.DONE, local.length(), local.length()))
            }
            else -> throw IOException("HEAD $diffUrl returned ${head.first}")
        }
    }.flowOn(Dispatchers.IO)

    /** Compares [local] lookups.dat with the published one (only its first bytes are fetched). */
    suspend fun lookupsCheck(local: File): LookupsCheck = withContext(Dispatchers.IO) {
        val req = request(profilesUrl + "lookups.dat").header("Range", "bytes=0-255").build()
        val remote = client.newCall(req).execute().use { r ->
            if (r.code != 200 && r.code != 206) throw IOException("GET ${req.url} returned ${r.code}")
            lookupVersion(r.body.string()) ?: throw IOException("no version in ${req.url}")
        }
        LookupsCheck(if (local.isFile) lookupVersion(local.readText(Charsets.UTF_8).take(512)) else null, remote)
    }

    /** Downloads the published lookups.dat to [target]. */
    suspend fun downloadLookups(target: File) = withContext(Dispatchers.IO) {
        val req = request(profilesUrl + "lookups.dat").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GET ${req.url} returned ${r.code}")
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.outputStream().use { out -> r.body.byteStream().copyTo(out) }
            if (lookupVersion(tmp.readText(Charsets.UTF_8).take(512)) == null) {
                tmp.delete(); throw IOException("downloaded lookups.dat has no version")
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("cannot write $target")
            }
        }
    }

    /** The lookups version an installed segment file was built with (−1 when unreadable). */
    fun segmentVersion(tile: String): Int = PhysicalFile.checkVersionIntegrity(file(tile))

    // ------------------------------------------------------------------------------------------------ downloading

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", USER_AGENT)

    /** GETs [url] into [target], continuing a partial file with a Range request when the server's ETag still matches. */
    private suspend fun FlowCollector<SegmentProgress>.fetchResumable(url: String, target: File, tile: String) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val etagFile = File(target.parentFile, target.name + ".etag")
        var have = if (target.isFile) target.length() else 0L
        val etag = if (have > 0 && etagFile.isFile) etagFile.readText().trim() else null
        if (have > 0 && etag == null) {
            target.delete(); have = 0
        }
        val builder = request(url)
        if (have > 0) builder.header("Range", "bytes=$have-").header("If-Range", etag!!)
        val call = client.newCall(builder.build())
        emit(SegmentProgress(tile, SegmentProgress.Stage.DOWNLOADING, have, -1))
        val response = call.execute()
        try {
            response.use { r -> receive(call, r, target, etagFile, have, tile) }
        } finally {
            call.cancel()
        }
        etagFile.delete()
    }

    private suspend fun FlowCollector<SegmentProgress>.receive(call: Call, r: Response, target: File, etagFile: File, have: Long, tile: String) {
        val append = when (r.code) {
            206 -> true
            200 -> false
            416 -> {
                // the part is already complete
                emit(SegmentProgress(tile, SegmentProgress.Stage.DOWNLOADING, have, have)); return
            }
            else -> throw IOException("GET ${r.request.url} returned ${r.code}")
        }
        val start = if (append) have else 0L
        val length = r.header("Content-Length")?.toLongOrNull() ?: -1L
        val total = if (length >= 0) start + length else -1L
        r.header("ETag")?.let { etagFile.writeText(it) }
        RandomAccessFile(target, "rw").use { raf ->
            if (!append) raf.setLength(0)
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            var done = start
            var received = 0L
            val t0 = System.nanoTime()
            var lastEmit = 0L
            val input = r.body.byteStream()
            while (true) {
                if (!currentCoroutineContext().isActive) {
                    call.cancel(); currentCoroutineContext().ensureActive()
                }
                val n = input.read(buf)
                if (n < 0) break
                raf.write(buf, 0, n)
                done += n
                received += n
                // throttle: never get ahead of maxBytesPerSecond
                val dueMs = received * 1000 / maxBytesPerSecond
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                if (dueMs > elapsedMs) delay(dueMs - elapsedMs)
                if (done - lastEmit >= 256 * 1024) {
                    lastEmit = done
                    emit(SegmentProgress(tile, SegmentProgress.Stage.DOWNLOADING, done, total))
                }
            }
            if (total >= 0 && done != total) throw IOException("download of ${r.request.url} ended at $done of $total bytes")
            emit(SegmentProgress(tile, SegmentProgress.Stage.DOWNLOADING, done, if (total >= 0) total else done))
        }
    }

    /** Checks [part] and moves it into place as [name]. */
    private suspend fun FlowCollector<SegmentProgress>.install(name: String, part: File, verify: Boolean) {
        if (verify) {
            emit(SegmentProgress(name, SegmentProgress.Stage.VERIFYING, part.length(), part.length()))
            val error = try {
                PhysicalFile.checkFileIntegrity(part)
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            if (error != null) {
                part.delete()
                throw IOException("$name is corrupt: $error")
            }
        }
        // a rename replaces the old file in one step, so a route starting meanwhile never finds the tile missing;
        // only where the file system refuses (Windows) is the old one deleted first
        val target = file(name)
        if (part.renameTo(target)) return
        if (target.exists() && !target.delete()) throw IOException("cannot replace $target")
        if (!part.renameTo(target)) throw IOException("cannot move $part to $target")
    }

    companion object {
        const val USER_AGENT = "Atlas/0.1 (+https://github.com/atlas-maps-app/Atlas)"
        const val SEGMENTS_URL = "https://brouter.de/brouter/segments4/"
        const val PROFILES_URL = "https://brouter.de/brouter/profiles2/"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /** The tile holding a point: the south-west corner floored to 5°, e.g. Amsterdam → "E0_N50.rd5". */
        fun tileName(lat: Double, lon: Double): String {
            val lon5 = (floor(lon / 5.0) * 5).toInt().coerceIn(-180, 175)
            val lat5 = (floor(lat / 5.0) * 5).toInt().coerceIn(-90, 85)
            val ew = if (lon5 < 0) "W${-lon5}" else "E$lon5"
            val ns = if (lat5 < 0) "S${-lat5}" else "N$lat5"
            return "${ew}_$ns.rd5"
        }

        fun tileName(p: LatLon): String = tileName(p.lat, p.lon)

        /** The area a tile covers. */
        fun tileBox(name: String): BBox {
            val m = TILE.matchEntire(normalise(name)) ?: throw IllegalArgumentException("not a segment tile: $name")
            val lon = m.groupValues[2].toInt() * if (m.groupValues[1] == "W") -1 else 1
            val lat = m.groupValues[4].toInt() * if (m.groupValues[3] == "S") -1 else 1
            return BBox(lon.toDouble(), lat.toDouble(), lon + 5.0, lat + 5.0)
        }

        /** Every tile touching [box]. */
        fun tilesIn(box: BBox): List<String> {
            val out = ArrayList<String>()
            val w = floor(box.west / 5.0).toInt()
            val e = floor((box.east - 1e-9) / 5.0).toInt().coerceAtLeast(w)
            val s = floor(box.south / 5.0).toInt()
            val n = floor((box.north - 1e-9) / 5.0).toInt().coerceAtLeast(s)
            for (x in w..e) for (y in s..n) out += tileName(y * 5.0 + 2.5, x * 5.0 + 2.5)
            return out.distinct()
        }

        /** The tiles holding the waypoints themselves. */
        fun tilesForWaypoints(points: List<LatLon>): List<String> = points.map { tileName(it) }.distinct()

        /** The tiles within [bufferMetres] of the straight lines through [points]. */
        fun tilesForCorridor(points: List<LatLon>, bufferMetres: Double): List<String> {
            val out = LinkedHashSet<String>()
            for (i in points.indices) {
                out += tilesIn(BBox.around(points[i], bufferMetres))
                if (i == 0) continue
                val a = points[i - 1]
                val b = points[i]
                val d = Geo.distance(a, b)
                val steps = (d / 2000.0).toInt()
                for (k in 1..steps) out += tilesIn(BBox.around(Geo.interpolate(a, b, k.toDouble() / (steps + 1)), bufferMetres))
            }
            return out.toList()
        }

        /** Tiles listed in an nginx directory index of segments4. */
        fun parseIndex(html: String): List<SegmentTile> {
            val fmt = java.text.SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale.ENGLISH).apply { timeZone = java.util.TimeZone.getTimeZone("CET") }
            return INDEX_LINE.findAll(html).map { m ->
                val date = runCatching { fmt.parse(m.groupValues[2])!!.time }.getOrDefault(0L)
                SegmentTile(m.groupValues[1], m.groupValues[3].toLong(), date)
            }.toList()
        }

        /** The version in the header of a lookups.dat ("---lookupversion:11", "---minorversion:2"). */
        fun lookupVersion(text: String): LookupVersion? {
            val major = Regex("""---lookupversion:(\d+)""").find(text)?.groupValues?.get(1)?.toInt() ?: return null
            val minor = Regex("""---minorversion:(\d+)""").find(text)?.groupValues?.get(1)?.toInt() ?: 0
            return LookupVersion(major, minor)
        }

        private fun normalise(tile: String) = if (tile.endsWith(".rd5")) tile else "$tile.rd5"

        private val TILE = Regex("""([EW])(\d+)_([NS])(\d+)\.rd5""")
        private val INDEX_LINE = Regex("""<a href="([EW]\d+_[NS]\d+\.rd5)">[^<]*</a>\s+(\d{2}-\w{3}-\d{4} \d{2}:\d{2})\s+(\d+)""")
    }
}
