package app.atlas.tiles

import app.atlas.geo.BBox
import app.atlas.geo.TileId

/** Where vector tiles come from: an offline pack, the disk cache or the network. */
interface TileSource : AutoCloseable {
    val minZoom: Int
    val maxZoom: Int
    val attribution: String

    /** The area the source covers; it has no tiles outside it. */
    val bounds: BBox get() = BBox.WORLD

    /**
     * The raw (decompressed) MVT bytes of [id], or null when the source has no such tile.
     * An empty array is an empty tile, which is a hit.
     * @throws java.io.IOException when the tile cannot be read now (no network, a server error); a later call may succeed.
     */
    suspend fun tile(id: TileId): ByteArray?

    override fun close() {}
}

/** The answer of a network tile service to one request. */
sealed interface FetchResult {
    /** The tile as it came: [data] is gzip-compressed when [gzipped]. [version] is the data release it belongs to. */
    class Fetched(val data: ByteArray, val gzipped: Boolean, val etag: String?, val version: String) : FetchResult {
        /** The raw MVT bytes. */
        fun raw(): ByteArray = if (gzipped) Gzip.decompress(data) else data

        /** The bytes to store: gzip-compressed. */
        fun stored(): ByteArray = if (gzipped) data else Gzip.compress(data)
    }

    /** The tile has not changed since the given ETag. */
    data object NotModified : FetchResult

    /** The service has no such tile. */
    data object Missing : FetchResult
}

/** A network tile service as downloads and the cache use it: version-aware and conditional. */
interface TileFetcher {
    val minZoom: Int
    val maxZoom: Int
    val attribution: String

    /** The data release the service serves now; it changes when new tiles are published. */
    suspend fun version(): String

    /** Fetches [id], asking only for changes when [etag] is given. Retries transient failures itself. */
    suspend fun fetch(id: TileId, etag: String? = null): FetchResult

    /** The ids of the layers its tiles hold, when it says (for a pack's metadata). */
    suspend fun vectorLayers(): List<String> = emptyList()
}

/** Packs a tile id into one Long (zoom ≤ 29) for compact sets and maps. */
internal object TileKey {
    fun pack(id: TileId): Long = pack(id.z, id.x, id.y)
    fun pack(z: Int, x: Int, y: Int): Long = (z.toLong() shl 58) or (x.toLong() shl 29) or y.toLong()
    fun unpack(k: Long): TileId = TileId((k ushr 58).toInt(), ((k ushr 29) and MASK).toInt(), (k and MASK).toInt())
    private const val MASK = (1L shl 29) - 1
}

/** True when [id] is a real tile: x and y within its zoom's grid. */
internal fun TileId.isValid(): Boolean = z in 0..29 && x >= 0 && y >= 0 && x < (1 shl z) && y < (1 shl z)
