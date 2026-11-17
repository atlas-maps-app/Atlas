package app.atlas.tiles

import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * A window's pack from its country's tiles (ENGINE.md step 3, one pack per country: the phone takes a place's tiles by
 * a range and builds only what the pack lacks): the published packs ([packs], packs.json's) that have tiles, whose box
 * holds the window and whose tiles the phone's own core built, newest first; from the first whose index lists the
 * window, its pack, read by HTTP range from the pack's tiles file, held to the index's SHA-256 and with its style written
 * back whole from the country's ([CountryStyle]), or that it holds nothing to draw.
 *
 * Another core's tiles are never taken: a window's pack is what the core of [TilesIndex.version] builds, and another
 * stage P or style builds other tiles. packs.json names each index's version ([PackFile.coreVersion]), so an index of
 * another core is not even downloaded, and where a country publishes the tiles of two cores (the new one's and the one
 * before's, [Pack.tileSets]) the phone takes its own core's. Each index is downloaded whole once, held to packs.json's SHA-256, and kept in
 * [dir] beside the windows' data indexes ([WindowPacks]; one per pack, the one of an older release removed), so a
 * window costs one range of its own bytes. One instance per app; its calls block, on the windows' own thread.
 *
 * A country's tiles the phone keeps whole ([local]: an offline region's map, the user's download of the country) are
 * read there instead, the index and each window's bytes from the files, held to the same SHA-256s, so the map needs no
 * network where they reach; a file that does not read, or whose bytes are not the ones the index names, gives way to
 * the published one.
 *
 * A frame cell's levels' tiles come the same way (ENGINE.md one pack per country, step 7: its L13 and L12 tiles, which
 * the core derives from the cell's windows and draws beyond the windows' reach): each one a pack of its own, listed in
 * the index by its level, taken where the core asks for it ([window] with its level).
 */
class WindowTiles(
    /** The published packs: the catalog's list (kept for offline), or throws where there is none. */
    packs: () -> List<Pack>,
    /** Where the packs' indexes are kept. */
    dir: File,
    client: OkHttpClient = Http.client,
    clock: () -> Long = System::currentTimeMillis,
    /** A country's tiles of a set kept whole on the phone ([Local]), or null where the phone keeps none of them. */
    private val local: (Pack, TileSet) -> Local? = { _, _ -> null },
) {
    /** A set of a country's tiles on the phone: its [tiles] and their [index], as packs.json publishes them. */
    class Local(val tiles: File, val index: File)

    /**
     * A window's pack from a country's tiles: its [bytes] as the core reads them, none where it holds nothing to draw
     * (the open sea, a lake's middle), built from the windows' data of [date], from pack [pack].
     */
    class Tiled(val bytes: ByteArray, val date: Instant, val pack: String) {
        /** Whether the window holds anything to draw. */
        val drawn: Boolean get() = bytes.isNotEmpty()
    }

    private val reads = PackReads(packs, dir, client, clock)

    /** The indexes read, by pack id: the index's SHA-256 and the index. */
    private val indexes = HashMap<String, Pair<String, TilesIndex>>()

    /**
     * The indexes read, by pack id, for [holds]: read without [indexOf]'s lock, which a download holds for as long as it
     * takes.
     */
    @Volatile
    private var known: Map<String, TilesIndex> = emptyMap()

    /**
     * Window ([x], [y]) of level [z]'s pack, or a frame cell's level's tile ([z] 13 or 12), as a core of window version
     * [version] (MapCore.Window.version: its stage P's and its style's) builds it, from the newest country's tiles that
     * hold it; null where none holds it (no pack with tiles of that version holds its place or lists it, or no list of
     * packs to be had). A country whose index or bytes did not come whole, or were not the ones published, is passed
     * over for the next that holds the window (two countries' tiles meet at a border): IOException, the first
     * country's, only where none of them gave it.
     */
    fun window(z: Int, x: Int, y: Int, version: String): Tiled? {
        if (z != LEVEL && z !in TilesIndex.LEVELS) return null
        val list = reads.list() ?: return null
        val holding = list.filter { PackReads.holds(it, z, x, y) }.mapNotNull { p -> p.tilesOf(version)?.let { p to it } }
        var failed: IOException? = null
        for ((pack, set) in holding.sortedByDescending { it.first.osmTime ?: Instant.EPOCH }) {
            try {
                return windowOf(pack, set, z, x, y, version) ?: continue
            } catch (e: IOException) {
                val first = failed
                if (first == null) failed = e else first.addSuppressed(e)
            }
        }
        failed?.let { throw it }
        return null
    }

    /** Window or level's tile [z]/[x]/[y] from [pack]'s tiles [set]; null where they are another core's or do not list it. */
    private fun windowOf(pack: Pack, set: TileSet, z: Int, x: Int, y: Int, version: String): Tiled? {
        val kept = local(pack, set)
        val index = indexOf(pack, set, kept)
        if (index.level != LEVEL || index.version != version) return null
        val e = index.find(z, x, y) ?: return null
        if (!e.drawn) return Tiled(ByteArray(0), index.date, pack.id)
        val bytes = kept?.let { k -> readKept(k.tiles, set.tiles, e) } ?: reads.range(set.tiles, e.offset, e.length)
        if (!PackReads.sha256(bytes).contentEquals(e.sha256)) {
            throw IOException("window $z/$x/$y of pack ${pack.id}: not the tiles its index names")
        }
        val whole = try {
            CountryStyle.unpack(bytes, index.style)
        } catch (x: IllegalArgumentException) {
            throw IOException("window $z/$x/$y of pack ${pack.id}: ${x.message}", x)
        }
        return Tiled(whole, index.date, pack.id)
    }

    /**
     * Whether pack [id]'s tiles of core version [version] hold the place at [lat], [lon]: its index lists the place's
     * window, with a pack or with nothing to draw (the country's windows' data reaches it), or a level's tile over it
     * (a frame cell's L13 or L12 tile, which reaches a little beyond its windows); where a country's box alone says
     * little: the Netherlands' holds Antwerp and Aachen, which its tiles do not. Null where the pack's index of that
     * version is not read yet ([index] reads it). Never blocks and allocates nothing: the map asks at each camera.
     */
    fun holds(id: String, version: String, lat: Double, lon: Double): Boolean? {
        val index = known[id] ?: return null
        if (index.level != LEVEL || index.version != version) return null
        val n = 1 shl LEVEL
        val la = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
        val x = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val y = floor((1.0 - ln(tan(la) + 1.0 / cos(la)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        if (index.lists(LEVEL, x, y)) return true
        for (i in 0 until TilesIndex.LEVELS.size) {
            val z = TilesIndex.LEVELS[i]
            if (index.lists(z, x shr (LEVEL - z), y shr (LEVEL - z))) return true
        }
        return false
    }

    /**
     * Reads pack [id]'s tiles' index of core version [version] (from the phone's copy, else downloaded and kept), so
     * that [holds] answers for it: how many tiles it lists; null where no list of packs is to be had or the pack has no
     * tiles of that version. Blocks; throws IOException where the index did not come whole.
     */
    fun index(id: String, version: String): Int? {
        val pack = reads.list()?.firstOrNull { it.id == id } ?: return null
        val set = pack.tilesOf(version) ?: return null
        return indexOf(pack, set, local(pack, set)).size
    }

    /**
     * The packs whose tiles of core version [version], as packs.json publishes them now, the phone keeps whole ([local]),
     * by id: a window taken from them is read there again, with no network. Empty where no list of packs is to be had.
     */
    fun keptPacks(version: String): Set<String> {
        val list = reads.list() ?: return emptySet()
        return list.filter { p -> p.tilesOf(version)?.let { local(p, it) } != null }.mapTo(HashSet()) { it.id }
    }

    /**
     * Window entry [e]'s bytes from the tiles kept on the phone ([file], a copy of [published]); null where the file is
     * not the one published (its size) or does not read, or the bytes are not the ones the index names.
     */
    private fun readKept(file: File, published: PackFile, e: TilesIndex.Entry): ByteArray? = try {
        RandomAccessFile(file, "r").use { f ->
            if (f.length() != published.bytes) return null
            val b = ByteArray(e.length)
            f.seek(e.offset)
            f.readFully(b)
            b.takeIf { PackReads.sha256(it).contentEquals(e.sha256) }
        }
    } catch (_: IOException) {
        null
    }

    /** [pack]'s tiles index of [set], from memory, from the phone's copy of the country ([kept]), the folder, else downloaded. */
    @Synchronized
    private fun indexOf(pack: Pack, set: TileSet, kept: Local? = null): TilesIndex {
        val file = set.index
        val sha = file.sha256?.lowercase() ?: throw IOException("pack ${pack.id}: its tiles' index names no SHA-256")
        indexes[pack.id]?.takeIf { it.first == sha }?.let { return it.second }
        val read = try {
            val own = kept?.index?.takeIf { it.isFile && it.length() == file.bytes }?.let { f -> runCatching { f.readBytes() }.getOrNull() }
                ?.takeIf { PackReads.hex(it) == sha }
            TilesIndex.read(own ?: reads.index(pack, file, INDEX_SUFFIX))
        } catch (e: IllegalArgumentException) {
            throw IOException("pack ${pack.id}: its tiles' index does not read: ${e.message}", e)
        }
        // an index of other tiles than the file packs.json publishes beside it would name other bytes
        val tiles = set.tiles.bytes
        if (read.tilesBytes != tiles) throw IOException("pack ${pack.id}: its tiles' index is of ${read.tilesBytes} bytes of tiles, its tiles are $tiles")
        indexes[pack.id] = sha to read
        known = known + (pack.id to read)
        return read
    }

    companion object {
        /** The level of the phone's windows (load::window_level). */
        const val LEVEL = PackReads.LEVEL

        /**
         * How a pack's tiles index is kept in the folder, after the pack's id and its SHA-256's first digits: apart from
         * its windows' data index ([WindowPacks]), which the same folder keeps.
         */
        private const val INDEX_SUFFIX = ".tiles.idx"

        /** Web Mercator's last latitude. */
        private const val MAX_LAT = 85.0511287798066
    }
}
