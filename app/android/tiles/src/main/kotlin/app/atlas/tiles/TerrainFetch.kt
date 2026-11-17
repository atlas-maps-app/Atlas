package app.atlas.tiles

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * The terrain's files on the phone (ENGINE.md part 6, step 2), as [WorldFetch] keeps the world's: `terrain.json` from
 * the release `terrain-latest` at most once a day ([TerrainList]), and the index of each region whose box the map's view
 * reaches ([take]), into the terrain's folder ([dir]: the kept folder's `terrain/`, which PackList lists for the core).
 * A file is written beside its place (`<name>.download`) and moved there once it is as long as terrain.json says and of
 * the SHA-256 it names, so the core never lists a file half written. A new release drops the old one's files: the
 * indexes the phone kept are replaced by the new release's (fetched beside them first, so the old ones go on where the
 * new ones cannot be had), those of a region it no longer names deleted, and [drop] called. The packs (one an L9 tile,
 * its heights at L12 to L14) come by ranges as the map's views want them ([head], [range]: mapview's TerrainReads, into
 * the world's cache). Every request says only who is asking ([Http.USER_AGENT]). Blocking: call it off the main thread.
 */
class TerrainFetch(
    private val dir: File,
    private val latest: String = TERRAIN_LATEST,
    client: OkHttpClient = Http.client,
    private val now: () -> Long = System::currentTimeMillis,
    private val drop: () -> Unit = {},
) {
    private val client = WorldFetch.releaseClient(client)

    // the scheme terrain.json came on (https): its release's files must come on it too
    private val scheme = latest.substringBefore("://")

    /** The terrain on the phone: its release's list, and the indexes of its regions the phone keeps, by region id. */
    class Terrain(val list: TerrainList, val indexes: Map<String, WorldIndex>)

    /** One pack of a region's index: its [region], and its [entry] (its L9 block, its range in its part, its SHA-256). */
    class Pack(val region: TerrainList.Region, val entry: WorldIndex.Entry) {
        /** The store's id of the pack (the core's tiles::terrain_pack_id): `terrain/<index name without .idx>/<z>/<x>/<y>`. */
        val id: String get() = idOf(region.name, entry)
    }

    /** The terrain on the phone once [start] found or fetched it; null before. */
    @Volatile
    var terrain: Terrain? = null
        private set

    /** The list on the phone, once [start] found or fetched it; null before. */
    val list: TerrainList? get() = terrain?.list

    // each region's index asked for and not had, by region and release, and when: not again within INDEX_AGAIN_MS
    private val asked = HashMap<String, Long>()

    /** Whether terrain.json was asked for a day ago or more (or never, or the clock went back): [start] asks again. */
    fun due(): Boolean {
        val at = runCatching { File(dir, ASKED).readText().trim().toLong() }.getOrNull() ?: return true
        val t = now()
        return t - at >= WorldFetch.DAY_MS || t < at
    }

    /**
     * terrain.json, asked for where it is [due] (else the phone's); on a new release, the new release's index of each
     * region the phone kept, all fetched before the old ones are replaced ([drop] then called). Returns whether the
     * terrain on the phone changed (the maps take their packs again). Throws where terrain.json, or a kept region's new
     * index, cannot be had and the phone has no terrain to go on with.
     */
    @Synchronized
    fun start(): Boolean {
        dir.mkdirs()
        val json = File(dir, JSON)
        if (due() || !json.isFile) {
            val at = now()
            val fetched = runCatching {
                WorldFetch.fetchFile(client, latest + JSON, json, -1, null, JSON) { TerrainList.read(it.readText(), scheme) }
                File(dir, ASKED).writeText("$at\n")
            }
            val phone = terrain ?: onPhone()
            if (fetched.isFailure && phone != null) {
                // the phone's terrain goes on until terrain-latest answers
                val changed = terrain == null
                terrain = phone
                return changed
            }
            fetched.getOrThrow()
        }
        val list = TerrainList.read(json.readText(), scheme)
        val kept = runCatching { File(dir, RELEASE).readText().trim() }.getOrNull()
        val before = terrain
        if (kept != list.release) {
            // the kept regions' indexes of the new release beside the old ones, then all moved in: the old release dropped
            val keptIds = indexFiles().map { it.name.removeSuffix(TerrainList.INDEX_SUFFIX) }
            val again = keptIds.mapNotNull { list.region(it) }
            val fresh = again.map { r -> r to File(dir, r.index.name + ".new") }
            try {
                for ((r, f) in fresh) WorldFetch.fetchFile(client, list.url + r.index.name, f, r.index.bytes, r.index.sha256, JSON) { WorldIndex.read(it.readBytes()) }
                File(dir, RELEASE).delete()
                for (f in indexFiles()) f.delete()
                for ((r, f) in fresh) if (!f.renameTo(File(dir, r.index.name))) throw IOException("the terrain's ${r.index.name} not moved into place")
                File(dir, RELEASE).writeText(list.release + "\n")
            } finally {
                for ((_, f) in fresh) f.delete()
            }
            synchronized(asked) { asked.clear() }
            if (kept != null) drop()
        }
        val now = Terrain(list, readIndexes(list))
        terrain = now
        return before == null || before.list.release != now.list.release || before.indexes.keys != now.indexes.keys
    }

    /** The terrain as the phone keeps it (terrain.json of the release kept, and its indexes), or null where there is none. */
    private fun onPhone(): Terrain? = runCatching {
        val list = TerrainList.read(File(dir, JSON).readText(), scheme)
        if (File(dir, RELEASE).readText().trim() == list.release) Terrain(list, readIndexes(list)) else null
    }.getOrNull()

    /** The indexes of [list]'s regions the phone keeps whole (as long as the list says; the SHA-256 was checked as it came). */
    private fun readIndexes(list: TerrainList): Map<String, WorldIndex> {
        val out = LinkedHashMap<String, WorldIndex>()
        for (r in list.regions) {
            val f = File(dir, r.index.name)
            if (f.length() != r.index.bytes) continue
            runCatching { WorldIndex.read(f.readBytes()) }.getOrNull()?.let { out[r.id] = it }
        }
        return out
    }

    private fun indexFiles(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(TerrainList.INDEX_SUFFIX) }.orEmpty().toList()

    /**
     * The indexes of the regions whose boxes meet the L[BLOCK] [blocks] (each its column and row), fetched and kept
     * where the phone has none of this release: an index asked for and not had is not asked for again within
     * [INDEX_AGAIN_MS]. Returns the regions whose index came (the maps then take their packs again); [said] gets a line
     * for each index asked for. Nothing before [start] found the list.
     */
    fun take(blocks: List<Pair<Int, Int>>, said: (String) -> Unit = {}): List<String> {
        val t = terrain ?: return emptyList()
        val came = ArrayList<String>()
        for (r in t.list.regions) {
            if (r.id in t.indexes || blocks.none { (x, y) -> meets(r, x, y) }) continue
            val key = "${r.id}@${t.list.release}"
            synchronized(asked) {
                val at = asked[key]
                if (at != null && now() - at in 0 until INDEX_AGAIN_MS) return@synchronized null
                asked[key] = now()
                key
            } ?: continue
            val got = runCatching { fetchIndex(t.list, r) }
            if (got.isSuccess) {
                came += r.id
                said("${r.index.name} (${got.getOrThrow().entries.size} packs) of ${t.list.release} on the phone")
            } else {
                said("${r.index.name} not on the phone yet: ${got.exceptionOrNull()?.message}")
            }
        }
        return came
    }

    @Synchronized
    private fun fetchIndex(list: TerrainList, r: TerrainList.Region): WorldIndex {
        val t = terrain ?: throw IOException("the terrain's list is not on the phone yet")
        if (t.list.release != list.release) throw IOException("${list.release} is not the terrain's release on the phone")
        t.indexes[r.id]?.let { return it }
        val f = File(dir, r.index.name)
        WorldFetch.fetchFile(client, list.url + r.index.name, f, r.index.bytes, r.index.sha256, JSON) { WorldIndex.read(it.readBytes()) }
        val index = WorldIndex.read(f.readBytes())
        terrain = Terrain(t.list, t.indexes + (r.id to index))
        return index
    }

    /**
     * The packs of the indexes on the phone for the L[BLOCK] [blocks] (each its column and row), in their order (a
     * block two regions hold has a pack of each). None before [start] found the list, nor for a region whose index
     * has not come ([take]).
     */
    fun packsAt(blocks: List<Pair<Int, Int>>): List<Pack> {
        val t = terrain ?: return emptyList()
        val out = ArrayList<Pack>()
        for ((x, y) in blocks) {
            for (r in t.list.regions) {
                val e = t.indexes[r.id]?.find(BLOCK, x, y) ?: continue
                out += Pack(r, e)
            }
        }
        return out
    }

    /**
     * The head of pack [p] (ENGINE.md step 6's, as a world pack's: [WorldFetch.head]): its bytes but its `chunks`
     * section, as ranges (each its offset in the pack and its bytes), which the core keeps in the world's cache.
     */
    fun head(p: Pack): List<Pair<Long, ByteArray>> = WorldFetch.headOf(p.entry.length) { offset, length -> range(p, offset, length) }

    /** Bytes [offset] to [offset] + [length] of pack [p]: one range of its part, as [WorldFetch.range]'s. */
    fun range(p: Pack, offset: Long, length: Int): ByteArray {
        val t = terrain ?: throw IOException("the terrain is not on the phone yet")
        val index = t.indexes[p.region.id] ?: throw IOException("${p.region.index.name} is not on the phone yet")
        val e = p.entry
        require(offset >= 0 && length > 0 && offset + length <= e.length) { "bytes $offset to ${offset + length} of a pack of ${e.length}" }
        require(e.part in index.parts.indices) { "a pack in part ${e.part} of ${index.parts.size}" }
        return WorldFetch.rangeOf(client, t.list.url + index.parts[e.part].name, e.offset + offset, length)
    }

    /**
     * The short names of the sources of [sourceIds] (the core's MapCore.terrainSources: the registry's ids), as the
     * map's credit line shows them: each once, [COPERNICUS] first where it is among them, the others in their order.
     * An id the list does not name is left out; none before [start] found the list.
     */
    fun creditsOf(sourceIds: List<String>): List<String> {
        val list = list ?: return emptyList()
        val names = sourceIds.mapNotNull { list.source(it)?.name }.distinct()
        return names.filter { it == COPERNICUS } + names.filter { it != COPERNICUS }
    }

    /**
     * The terrain's files for the life of the process, as [WorldFetch.follow]: [start] once the phone is [online], then
     * again each day (terrain.json at most once a day), what failed tried again once the phone is online, [firstWait]
     * on, doubling to [longestWait], or at once where the phone loses its network and comes back meanwhile. [said] gets
     * the `vk terrain` lines, [landed] is called as the terrain on the phone changes. Blocking calls: run it off the
     * main thread. Never returns.
     */
    suspend fun follow(
        online: StateFlow<Boolean>,
        said: (String) -> Unit,
        landed: () -> Unit,
        firstWait: Long = WorldFetch.FIRST_WAIT_MS,
        longestWait: Long = WorldFetch.LONGEST_WAIT_MS,
    ): Nothing {
        var wait = firstWait
        while (true) {
            online.first { it }
            val started = runCatching { start() }
            val t = terrain
            if (started.isSuccess && t != null) {
                if (started.getOrThrow()) {
                    said("$JSON of ${t.list.release} (${t.list.regions.size} regions, ${t.indexes.size} indexes kept) on the phone")
                    landed()
                }
                wait = firstWait
                while (!due()) delay(WorldFetch.DUE_CHECK_MS)
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

    companion object {
        /** The release the engine lead's publish points at the newest terrain: its terrain.json alone. */
        const val TERRAIN_LATEST = "https://github.com/atlas-maps-app/Atlas/releases/download/terrain-latest/"

        /** terrain.json, the release its kept indexes are of, and when terrain.json was last asked for. */
        const val JSON = "terrain.json"
        private const val RELEASE = "terrain.release"
        private const val ASKED = "terrain.asked"

        /** A terrain pack holds one tile of this level (the packer's HeightsBuild.BLOCK). */
        const val BLOCK = 9

        /** The world source's short name, first on the map's credit line. */
        const val COPERNICUS = "Copernicus"

        /** A region's index asked for and not had is not asked for again within this. */
        const val INDEX_AGAIN_MS = 60_000L

        /** The store's id of a terrain pack of the index [name] (without `.idx`; the core's tiles::terrain_pack_id). */
        fun idOf(name: String, e: WorldIndex.Entry): String = "terrain/$name/${e.z}/${e.x}/${e.y}"

        /** Whether L[BLOCK] tile ([x], [y]) meets [r]'s box. */
        fun meets(r: TerrainList.Region, x: Int, y: Int): Boolean {
            val n = (1 shl BLOCK).toDouble()
            fun lat(row: Int): Double = atan(sinh(PI * (1 - 2 * row / n))) * 180.0 / PI
            return r.meets(lat(y + 1), x / n * 360.0 - 180.0, lat(y), (x + 1) / n * 360.0 - 180.0)
        }
    }
}
