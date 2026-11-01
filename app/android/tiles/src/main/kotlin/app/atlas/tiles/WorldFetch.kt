package app.atlas.tiles

import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * The world's files on the phone (ENGINE.md step 5, the part of step 6's fetch that world-test needs): `world.idx` and
 * the globe at the first start, then the part holding a block's packs when a view first enters the block, each whole,
 * into the world's folder ([dir]: the kept folder's `world/`, which PackList lists for the core). A file is written
 * beside its place (`<name>.download`) and moved there only once it is complete and checked: the index read, the globe
 * as long as its answer said, a part as long as the index says with every pack in it of the SHA-256 the index names.
 * So the core never lists a file half written, nor a pack it would refuse. Whole files until step 6, which takes a
 * block's packs by ranges into the world's own cache (world-test's parts are 9.1 and 73.2 MB). Every request says only
 * who is asking ([Http.USER_AGENT]). Blocking: call it off the main thread, one call at a time.
 */
class WorldFetch(
    private val dir: File,
    private val base: String = WORLD_TEST,
    client: OkHttpClient = Http.client,
) {
    // a part takes minutes on a phone's network: no limit on the whole call, only on each wait for bytes; nor through
    // the app's HTTP cache, which would keep a second copy of each part (PackDownloader's reasons)
    private val client = client.newBuilder().cache(null).callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /** The index on the phone, once [start] found or fetched it; null before. */
    @Volatile
    var index: WorldIndex? = null
        private set

    /** World.idx and the globe, each fetched where the folder lacks it (the first start). Throws where one cannot be had. */
    @Synchronized
    fun start() {
        dir.mkdirs()
        val idx = File(dir, INDEX)
        if (!idx.isFile) fetch(INDEX, idx) { WorldIndex.read(it.readBytes()) }
        index = WorldIndex.read(idx.readBytes())
        val globe = File(dir, GLOBE)
        if (!globe.isFile) fetch(GLOBE, globe) {}
    }

    /**
     * The parts holding the packs of the place at [lat], [lon]: its L6 tile's fine pack and its L3 tile's coarse pack,
     * each part fetched where the folder lacks it. Returns the parts fetched: none where the folder holds them, where no
     * pack holds the place (open sea, or beyond the blocks built), or before [start]. Throws where one cannot be had.
     */
    @Synchronized
    fun enter(lat: Double, lon: Double): List<String> {
        val index = index ?: return emptyList()
        return missing(dir, index, lat, lon).map { number ->
            val part = index.parts[number]
            fetch(part.name, File(dir, part.name)) { checkPart(it, number, index) }
            part.name
        }
    }

    /** Fetches [name] of the release into [target] by way of `<target>.download`, moved into place once [check] passes. */
    private fun fetch(name: String, target: File, check: (File) -> Unit) {
        val partial = File(target.path + ".download")
        try {
            partial.delete()
            client.newCall(Http.request(base + name).header("Accept-Encoding", "identity").build()).execute().use { r ->
                if (r.code != 200) throw HttpStatusException(r.code, "HTTP ${r.code} fetching $name")
                val declared = r.body.contentLength()
                partial.outputStream().use { out -> r.body.byteStream().copyTo(out, BUFFER) }
                if (declared >= 0 && partial.length() != declared) throw IOException("$name: ${partial.length()} bytes of $declared")
            }
            check(partial)
            if (!partial.renameTo(target)) throw IOException("$name: not moved into place")
        } finally {
            partial.delete()
        }
    }

    /** Part [number] in [f]: as long as [index] says, and every pack in it of the SHA-256 the index names. */
    private fun checkPart(f: File, number: Int, index: WorldIndex) {
        val part = index.parts[number]
        if (f.length() != part.bytes) throw IOException("${part.name}: ${f.length()} bytes, ${part.bytes} in the index")
        val buf = ByteArray(BUFFER)
        RandomAccessFile(f, "r").use { file ->
            for (e in index.entries) {
                if (e.part != number) continue
                val digest = MessageDigest.getInstance("SHA-256")
                file.seek(e.offset)
                var left = e.length
                while (left > 0) {
                    val n = file.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("${part.name}: pack ${e.z}/${e.x}/${e.y} cut short")
                    digest.update(buf, 0, n)
                    left -= n
                }
                if (!digest.digest().contentEquals(e.sha256)) {
                    throw PackDownloader.ChecksumException("${part.name}: pack ${e.z}/${e.x}/${e.y} is not the one the index names")
                }
            }
        }
    }

    companion object {
        /** The world-test release (ENGINE.md step 5's step 3: the Netherlands' world packs and the globe). */
        const val WORLD_TEST = "https://github.com/atlas-maps-app/Atlas/releases/download/world-test/"

        /** The index's file and the globe's (the core's tiles/WorldIndex.hpp, PackList's). */
        const val INDEX = "world.idx"
        const val GLOBE = "world-globe.atlas"

        private const val BUFFER = 1 shl 16

        /**
         * The parts of [index] holding the packs of the place at [lat], [lon] (its L6 tile's fine pack, its L3 tile's
         * coarse pack) that [dir] lacks, by number, fine first: a part is moved into place only once whole and checked,
         * so one as long as the index says is on the phone. None where no pack holds the place.
         */
        fun missing(dir: File, index: WorldIndex, lat: Double, lon: Double): List<Int> =
            listOf(WorldIndex.FINE_BLOCK, WorldIndex.COARSE_BLOCK).mapNotNull { level ->
                val (x, y) = tileOf(lat, lon, level)
                index.find(level, x, y)?.part
            }.distinct().filter { File(dir, index.parts[it].name).length() != index.parts[it].bytes }

        /** Web Mercator's tile at [level] holding [lat], [lon] (clamped to the tiles' 85.05°). */
        fun tileOf(lat: Double, lon: Double, level: Int): Pair<Int, Int> {
            val n = 1 shl level
            val la = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
            val x = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            val y = floor((1.0 - ln(tan(la) + 1.0 / cos(la)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
            return x to y
        }

        private const val MAX_LAT = 85.0511287798066
    }
}
