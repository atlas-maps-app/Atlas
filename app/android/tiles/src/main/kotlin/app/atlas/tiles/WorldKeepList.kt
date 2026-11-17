package app.atlas.tiles

/**
 * The world's tiles a kept country draws offline (ENGINE.md step 11): `world-keep.json`, an asset of the world's dated
 * release beside its parts, index and globe, made by world.yml's refs job (`packer world-keep`). For each region with
 * windows it lists every world tile a view of the region may draw from 18 km out to the globe: the packs of the
 * mid-zoom base that hold them, each by its entry in `world.idx` (its block's z, x, y), and each pack's tiles to keep
 * by level. Keeping a country fetches each listed pack's head and its listed tiles' chunks ([bytes] in all) and holds
 * them out of the world cache's LRU while the country is kept.
 *
 * The file is tied to its world by the index's SHA-256 ([indexSha256]): one whose index is not the phone's world's is
 * another world's, and is not used. A release made before the keep has none, and keeps no country.
 */
class WorldKeepList(val release: String?, val indexBytes: Long, val indexSha256: String, val countries: List<Country>) {
    /**
     * A region's keep: its [id] (regions.json's and packs.json's: a kept region finds its world by its own id), what
     * keeping it fetches ([bytes]: the heads and the kept tiles' chunks as stored), how many [tiles], and its [packs].
     */
    class Country(val id: String, val name: String, val bytes: Long, val tiles: Int, val packs: List<Pack>)

    /**
     * A pack of the keep: world.idx's entry of the block ([z], [x], [y]), its head's bytes ([head]: the pack but its
     * chunks, as [WorldFetch.head] fetches it), what keeping it fetches ([bytes]: its head and its tiles' chunks), and
     * its [tiles] to keep, each a level and its x and y.
     */
    class Pack(val z: Int, val x: Int, val y: Int, val head: Long, val bytes: Long, val tiles: List<Tile>)

    /** A tile to keep. */
    data class Tile(val z: Int, val x: Int, val y: Int)

    /** The keep of region [id], or null where the list has none (a region without windows, or one newer than the world). */
    fun country(id: String): Country? = countries.firstOrNull { it.id == id }

    companion object {
        /** The file's name beside the release's other files. */
        const val NAME = "world-keep.json"

        /** The format this reader takes. */
        const val FORMAT = 1

        /** Reads world-keep.json; throws [TileFormatException] or [IllegalArgumentException] where it is not one. */
        fun read(text: String): WorldKeepList {
            val o = Json.parseObject(text)
            require(o.int("format") == FORMAT) { "world-keep.json of format ${o["format"]}" }
            val index = requireNotNull(o.obj("index")) { "world-keep.json without its index" }
            val sha = index.str("sha256")?.lowercase()
            require(sha != null && SHA256.matches(sha)) { "world-keep.json's index without its SHA-256" }
            val countries = requireNotNull(o.list("countries")) { "world-keep.json without its countries" }.map { c ->
                @Suppress("UNCHECKED_CAST")
                val country = requireNotNull(c as? Map<String, Any?>) { "world-keep.json: a country is not an object" }
                val id = requireNotNull(country.str("id")) { "world-keep.json: a country without its id" }
                val packs = requireNotNull(country.list("packs")) { "world-keep.json: $id without its packs" }.map { p ->
                    @Suppress("UNCHECKED_CAST")
                    val pack = requireNotNull(p as? Map<String, Any?>) { "world-keep.json: a pack of $id is not an object" }
                    val z = requireNotNull(pack.int("z")) { "world-keep.json: a pack of $id without its block" }
                    val x = requireNotNull(pack.int("x")) { "world-keep.json: a pack of $id without its block" }
                    val y = requireNotNull(pack.int("y")) { "world-keep.json: a pack of $id without its block" }
                    val tiles = ArrayList<Tile>()
                    for ((level, list) in requireNotNull(pack.obj("tiles")) { "world-keep.json: pack $z/$x/$y of $id without its tiles" }) {
                        val tz = requireNotNull(level.toIntOrNull()) { "world-keep.json: pack $z/$x/$y of $id: no level $level" }
                        val flat = requireNotNull(list as? List<*>) { "world-keep.json: pack $z/$x/$y of $id: level $level is no list" }
                        require(flat.size % 2 == 0 && tz in 0..MAX_LEVEL) { "world-keep.json: pack $z/$x/$y of $id: level $level's tiles" }
                        for (i in flat.indices step 2) {
                            val tx = (flat[i] as? Number)?.toInt()
                            val ty = (flat[i + 1] as? Number)?.toInt()
                            require(tx != null && ty != null && tx in 0 until (1 shl tz) && ty in 0 until (1 shl tz)) {
                                "world-keep.json: pack $z/$x/$y of $id: a tile of level $level"
                            }
                            tiles += Tile(tz, tx, ty)
                        }
                    }
                    Pack(z, x, y, pack.long("head") ?: 0L, pack.long("bytes") ?: 0L, tiles)
                }
                Country(id, country.str("name") ?: id, country.long("bytes") ?: packs.sumOf { it.bytes }, country.int("tiles") ?: packs.sumOf { it.tiles.size }, packs)
            }
            return WorldKeepList(o.str("release"), index.long("bytes") ?: 0L, sha, countries)
        }

        private val SHA256 = Regex("[0-9a-f]{64}")

        /** No tile is finer than this (the list's are the mid-zoom base's, L6 to L11). */
        private const val MAX_LEVEL = 24
    }
}
