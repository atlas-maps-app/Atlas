package app.atlas.tiles

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.time.Instant

/** One piece of a file published in parts (GitHub release assets must stay under 2 GiB). */
data class PackPart(val url: String, val bytes: Long, val sha256: String?)

/**
 * One file of a pack: the region pack (`.atlas`: the map's and the features' OpenStreetMap elements), its places (a
 * gzipped place store), its windows' data and their index (the data the phone builds its windows from, read by HTTP
 * range: [WindowsIndex]), or its tiles and their index (each window's pack as the core builds it from that data, the
 * country's map, read by HTTP range: [TilesIndex]).
 */
data class PackFile(
    /** [ATLAS], [PLACES], [WINDOWS], [WINDOWS_INDEX], [TILES] or [TILES_INDEX]. */
    val kind: String,
    val name: String,
    /** Bytes to download: the whole file, all parts together. */
    val bytes: Long,
    /** The whole file's SHA-256, lower-case hex. */
    val sha256: String?,
    /** Where to fetch it: one URL, or parts to join in order. */
    val parts: List<PackPart>,
    /** "gzip" for a store, which is unpacked after download; null when the file is used as it comes. */
    val compression: String? = null,
    /** Bytes on the phone once unpacked (the store), when known. */
    val rawBytes: Long? = null,
    /** The store's schema version. */
    val schema: String? = null,
    /**
     * A tiles index's core version (TilesIndex.version: the windows' stage P and style), read before it is downloaded;
     * on the tiles beside it too, since lists of 30 October on.
     */
    val coreVersion: String? = null,
) {
    /** The URL of a file published whole. */
    val url: String get() = parts.first().url
    val isSplit: Boolean get() = parts.size > 1

    /** Bytes it takes on the phone. */
    val diskBytes: Long get() = rawBytes ?: bytes

    companion object {
        const val ATLAS = "atlas"
        const val PLACES = "places"

        /** The windows' data: each window's Overpass answer, gzipped, one after another (published in parts over 2 GB). */
        const val WINDOWS = "windows"

        /** Where each window's data lies in the [WINDOWS] file ([WindowsIndex]), downloaded whole. */
        const val WINDOWS_INDEX = "windows-index"

        /** The tiles: each window's pack as the core builds it from the [WINDOWS] data, one after another (in parts over 2 GB). */
        const val TILES = "tiles"

        /** Where each window's pack lies in the [TILES] file ([TilesIndex]), downloaded whole. */
        const val TILES_INDEX = "tiles-index"
    }
}

/** A region Atlas offers as a ready-made offline pack: its region pack, places and windows' data, built on GitHub from a Geofabrik extract. */
data class Pack(
    val id: String,
    val name: String,
    /** ISO 3166-1 alpha-2, when the region is in one country. */
    val country: String?,
    val bbox: BBox,
    /** When the OpenStreetMap data was current (ISO 8601), when known. */
    val osmTimestamp: String?,
    /** The GitHub release the files belong to ("packs-20261101"). */
    val release: String?,
    val files: List<PackFile>,
) {
    val places: PackFile? get() = files.firstOrNull { it.kind == PackFile.PLACES }

    /** The windows' data, when the pack has them with their index. */
    val windows: PackFile? get() = files.firstOrNull { it.kind == PackFile.WINDOWS }?.takeIf { windowsIndex != null }

    /** Where each window's data lies in [windows]. */
    val windowsIndex: PackFile? get() = files.firstOrNull { it.kind == PackFile.WINDOWS_INDEX }

    /**
     * The country's tiles with their index, a set for each core version the list publishes: the k-th tiles file with the
     * k-th index, the newest last. Since a republish keeps the core version before beside the new one (`packer
     * manifest`), so that a phone whose core is a version behind still finds tiles it builds, a list may hold two; a
     * phone takes the set its own core built ([tilesOf]). A tiles file that names a core version other than its index's
     * pairs with none.
     */
    val tileSets: List<TileSet>
        get() = files.filter { it.kind == PackFile.TILES }.zip(files.filter { it.kind == PackFile.TILES_INDEX }, ::TileSet)
            .filter { it.tiles.coreVersion == null || it.tiles.coreVersion == it.index.coreVersion }

    /** The tiles the core of window version [version] builds (MapCore.Window.version), if the pack has them. */
    fun tilesOf(version: String): TileSet? = tileSets.firstOrNull { it.index.coreVersion == version }

    /** The files a phone of the newest core takes: all but the tiles kept for a core before it. */
    private val current: List<PackFile>
        get() = tileSets.dropLast(1).flatMap { listOf(it.tiles, it.index) }.let { older -> files.filter { f -> older.none { it === f } } }

    /** Bytes to download for the whole pack. */
    val downloadBytes: Long get() = current.sumOf { it.bytes }

    /** Bytes the pack takes on the phone once unpacked. */
    val diskBytes: Long get() = current.sumOf { it.diskBytes }

    val osmTime: Instant? get() = osmTimestamp?.let { runCatching { Instant.parse(it) }.getOrNull() }

    fun covers(p: LatLon): Boolean = p in bbox
}

/** A country's tiles ([tiles]: each window's pack as the core builds it) and where each window lies in them ([index], with its core version). */
data class TileSet(val tiles: PackFile, val index: PackFile)

/** The published list: when it was made, the attribution its data needs, and the packs. */
data class PackList(val generated: String?, val attribution: String?, val packs: List<Pack>)

/**
 * The offline packs Atlas builds and publishes on GitHub (`.github/workflows/packs.yml`): it fetches `packs.json` from
 * the fixed `packs-latest` release, keeps the last good copy in [cacheFile] for offline use, and gives the app each
 * pack's download URLs and sizes. A pack's tiles can also be read in place, by HTTP range: GitHub answers a release
 * download with a redirect to its storage host, which OkHttp follows with the Range header kept.
 */
class PackCatalog(
    val url: String = DEFAULT_URL,
    private val client: OkHttpClient = Http.client,
    /** Where the last list fetched is kept, so packs can be listed offline. */
    private val cacheFile: File? = null,
    /** How long a list fetched is used before asking again. */
    private val maxAgeMillis: Long = 6 * 3_600_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var cached: PackList? = null
    private var fetchedAt = 0L

    /** The list: from memory while fresh, else from GitHub, else (offline) the last copy kept. */
    suspend fun list(refresh: Boolean = false): PackList = lock.withLock {
        val now = clock()
        cached?.let { if (!refresh && now - fetchedAt < maxAgeMillis) return@withLock it }
        try {
            val r = client.newCall(Http.request(url).header("Accept", "application/json").build()).read(maxBytes = MAX_LIST)
            if (!r.isSuccessful) throw HttpStatusException(r.code, "HTTP ${r.code} fetching the pack list")
            val text = String(r.body, Charsets.UTF_8)
            val list = parse(text)
            cacheFile?.let { f -> runCatching { writeAtomically(f, text) } }
            cached = list
            fetchedAt = now
            list
        } catch (e: IOException) {
            cached ?: cacheFile?.takeIf { it.isFile }?.let { f -> runCatching { parse(f.readText()) }.getOrNull() }?.also { cached = it }
                ?: throw e
        }
    }

    suspend fun packs(refresh: Boolean = false): List<Pack> = list(refresh).packs

    suspend fun pack(id: String): Pack? = packs().firstOrNull { it.id == id }

    /** The packs whose box holds [p], the smallest region first. */
    suspend fun covering(p: LatLon): List<Pack> = packs().filter { it.covers(p) }.sortedBy { it.bbox.width * it.bbox.height }

    companion object {
        const val DEFAULT_URL = "https://github.com/atlas-maps-app/Atlas/releases/download/packs-latest/packs.json"

        /** The list format this reader knows; a newer one is still read, field by field. */
        const val FORMAT = 1

        private const val MAX_LIST = 4L shl 20

        /** Parses `packs.json`; packs without an id, a box or files are left out. */
        fun parse(text: String): PackList {
            val root = Json.parseObject(text)
            val packs = root.list("packs").orEmpty().mapNotNull { e ->
                @Suppress("UNCHECKED_CAST")
                (e as? Map<String, Any?>)?.let(::pack)
            }
            return PackList(root.str("generated"), root.str("attribution"), packs)
        }

        @Suppress("UNCHECKED_CAST")
        private fun pack(m: Map<String, Any?>): Pack? {
            val id = m.str("id") ?: return null
            val b = m.list("bbox")?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 4 } ?: return null
            val files = m.list("files").orEmpty().mapNotNull { (it as? Map<String, Any?>)?.let(::file) }
            if (files.isEmpty()) return null
            return Pack(
                id = id,
                name = m.str("name") ?: id,
                country = m.str("country"),
                bbox = BBox(b[0], b[1], b[2], b[3]),
                osmTimestamp = m.str("osm_timestamp"),
                release = m.str("release"),
                files = files,
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun file(m: Map<String, Any?>): PackFile? {
            val kind = m.str("kind") ?: return null
            val bytes = m.long("bytes") ?: return null
            val parts = m.list("parts")?.mapNotNull { p ->
                (p as? Map<String, Any?>)?.let { pm ->
                    val u = pm.str("url") ?: return@let null
                    PackPart(u, pm.long("bytes") ?: return@let null, pm.str("sha256"))
                }
            }?.takeIf { it.isNotEmpty() }
                ?: m.str("url")?.let { listOf(PackPart(it, bytes, m.str("sha256"))) }
                ?: return null
            if (parts.sumOf { it.bytes } != bytes) return null
            return PackFile(
                kind = kind,
                name = m.str("name") ?: parts.first().url.substringAfterLast('/'),
                bytes = bytes,
                sha256 = m.str("sha256"),
                parts = parts,
                compression = m.str("compression"),
                rawBytes = m.long("raw_bytes"),
                schema = m["schema"]?.toString(),
                coreVersion = m.str("core_version"),
            )
        }

        private fun writeAtomically(f: File, text: String) {
            f.absoluteFile.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }
}
