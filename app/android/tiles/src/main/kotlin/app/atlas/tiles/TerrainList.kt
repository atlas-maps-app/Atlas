package app.atlas.tiles

/**
 * A terrain release as its list names it (ENGINE.md part 6, step 2: `terrain.json` of format 1, which the release
 * `terrain-latest` carries alone and `packer heights-list` writes): its [release] tag, where its files are ([url]), the
 * [sources] its regions draw from, and each region's index with the box its packs make together. A region's packs lie
 * in the parts its index names, fetched from [url] by range (TerrainFetch).
 */
class TerrainList(val release: String, val url: String, val sources: List<Source>, val regions: List<Region>) {
    /** A source: its registry id, its short [name] for the map's credit line ("Copernicus", "AHN") and its credit. */
    class Source(val id: String, val name: String, val credit: String)

    /** A region: its id, the box its packs make together ([south], [west], [north], [east], degrees) and its index. */
    class Region(val id: String, val south: Double, val west: Double, val north: Double, val east: Double, val index: Index) {
        /** The index's file name without its ending (`netherlands-terrain`): the core's terrain packs' ids name it. */
        val name: String get() = index.name.removeSuffix(INDEX_SUFFIX_EXT)

        /** Whether the box meets the one of [south], [west], [north], [east] (degrees). */
        fun meets(south: Double, west: Double, north: Double, east: Double): Boolean =
            this.west <= east && west <= this.east && this.south <= north && south <= this.north
    }

    /** A region's index as the list names it: its file name, bytes and SHA-256 (hex). */
    class Index(val name: String, val bytes: Long, val sha256: String)

    /** The source of [id], or null where the list names none. */
    fun source(id: String): Source? = sources.firstOrNull { it.id == id }

    /** The region of [id], or null where the list names none. */
    fun region(id: String): Region? = regions.firstOrNull { it.id == id }

    companion object {
        const val FORMAT = 1

        /** A region's index's file: `<region>-terrain.idx`. */
        const val INDEX_SUFFIX = "-terrain.idx"
        private const val INDEX_SUFFIX_EXT = ".idx"

        private val SHA256 = Regex("[0-9a-f]{64}")
        private val ID = Regex("[a-z0-9-]+")

        /**
         * terrain.json's release: refused where its format is not [FORMAT], its files are not on [scheme] (the list's
         * own), or a region's index is not named `<region>-terrain.idx` (a file the phone keeps under that name), nor
         * of a length and a SHA-256.
         */
        fun read(text: String, scheme: String = "https"): TerrainList {
            val o = Json.parseObject(text)
            require(o.int("format") == FORMAT) { "terrain.json of format ${o["format"]}" }
            val tag = requireNotNull(o.str("release")) { "terrain.json without its release" }
            val url = requireNotNull(o.str("url")) { "terrain.json without its files' place" }.let { if (it.endsWith("/")) it else "$it/" }
            require(url.startsWith("$scheme://")) { "terrain.json's files not on $scheme: $url" }
            val sources = o.list("sources").orEmpty().map { e ->
                @Suppress("UNCHECKED_CAST")
                val s = requireNotNull(e as? Map<String, Any?>) { "terrain.json's source is not an object" }
                val id = requireNotNull(s.str("id")) { "terrain.json's source without its id" }
                Source(id, requireNotNull(s.str("name")) { "terrain.json's source $id without its name" }, s.str("credit").orEmpty())
            }
            val regions = o.list("regions").orEmpty().map { e ->
                @Suppress("UNCHECKED_CAST")
                val r = requireNotNull(e as? Map<String, Any?>) { "terrain.json's region is not an object" }
                val id = requireNotNull(r.str("id")) { "terrain.json's region without its id" }
                require(ID.matches(id)) { "terrain.json's region '$id'" }
                val box = r.list("box")?.map { (it as? Number)?.toDouble() }
                require(box != null && box.size == 4 && box.all { it != null }) { "terrain.json's $id without its box" }
                val (s, w, n, ea) = box.map { it!! }
                require(s <= n && w <= ea) { "terrain.json's $id has a box of $box" }
                val i = requireNotNull(r.obj("index")) { "terrain.json's $id without its index" }
                val name = i.str("name")
                require(name == id + INDEX_SUFFIX) { "terrain.json's $id has an index '$name'" }
                val bytes = i.long("bytes")
                require(bytes != null && bytes > 0) { "terrain.json's $id index without its length" }
                val sha = i.str("sha256")?.lowercase()
                require(sha != null && SHA256.matches(sha)) { "terrain.json's $id index without its SHA-256" }
                Region(id, s, w, n, ea, Index(name, bytes, sha))
            }
            require(regions.map { it.id }.toSet().size == regions.size) { "terrain.json names a region twice" }
            return TerrainList(tag, url, sources, regions)
        }
    }
}
