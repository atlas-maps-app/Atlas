package app.atlas.packer.world

import app.atlas.packer.UsageException
import app.atlas.rendertiles.codec.Format3Writer
import app.atlas.tiles.WorldIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.format.DateTimeFormatter

/**
 * `packer world-assemble`: a world built in regions (ENGINE.md step 5's world.yml, a job a region) put together. Each
 * region hands on its index (its fine packs, in the parts it named), its coarse tiles ([CoarseTiles]) and its numbers
 * ([WorldTiles.STATS]); the coarse pack of each L3 block is made of every region's coarse tiles there, as one run of all
 * the regions' blocks makes it ([WorldTiles.coarsePacks]), and the index names every pack, the regions' parts as they
 * named them. Every region must be of the same run: the same data's date, sources and inputs' hash, no block twice.
 */
object WorldAssemble {
    const val USAGE = """packer world-assemble <out-dir> <region>... [--release TAG --url BASE --globe FILE]
      A world built in regions put together (world.yml): each <region> is the path its files share before their endings,
      <region>.idx (its index, `packer world`'s world.idx), <region>.coarse (its coarse tiles, --coarse-tiles) and
      <region>.stats.json (its world-stats.json). The coarse packs of every region's coarse tiles go into
      <out-dir>/world-coarse.000, …, the index of every pack into world.idx and the numbers added up into world-stats.json.
      With --release, world.json too: the release's tag and the URL its files are under (--url), the index's and the
      globe's (--globe) size and SHA-256, the parts and the sources."""

    /** The file a region's coarse tiles, index and numbers are handed on in, after the region's own path. */
    const val INDEX = ".idx"
    const val COARSE = ".coarse"
    const val STATS = ".stats.json"

    fun run(args: List<String>, out: (String) -> Unit): Int {
        if (args.firstOrNull() != "world-assemble") throw UsageException("usage: $USAGE")
        val positional = ArrayList<String>()
        val options = LinkedHashMap<String, String>()
        var i = 1
        while (i < args.size) {
            val s = args[i]
            if (s.startsWith("--")) {
                if (s !in VALUED) throw UsageException("unknown option $s; usage: $USAGE")
                if (i + 1 >= args.size) throw UsageException("$s needs a value")
                options[s] = args[++i]
            } else {
                positional += s
            }
            i++
        }
        if (positional.size < 2) throw UsageException("world-assemble takes the output folder and at least one region; usage: $USAGE")
        val release = options["--release"]
        if ((release == null) != (options["--url"] == null) || (release == null) != (options["--globe"] == null)) {
            throw UsageException("--release, --url and --globe go together; usage: $USAGE")
        }
        val globe = options["--globe"]?.let(::File)
        if (globe != null && !globe.isFile) throw UsageException("no globe $globe")
        val regions = positional.drop(1).map(::File)
        for (r in regions) for (ending in listOf(INDEX, COARSE, STATS)) {
            if (!File(r.path + ending).isFile) throw UsageException("no ${r.path + ending}")
        }
        val built = assemble(File(positional[0]), regions, out)
        if (release != null) File(positional[0], WORLD_JSON).writeText(worldJson(built, release, options.getValue("--url"), File(positional[0], "world.idx"), globe!!))
        return 0
    }

    private val VALUED = setOf("--release", "--url", "--globe")

    /** The release's description of its world, which world-latest holds too (ENGINE.md step 5's fetch). */
    const val WORLD_JSON = "world.json"

    /** What [assemble] made: the index, the sources every pack names and the numbers added up. */
    class Assembled(val index: WorldIndex, val facts: WorldTiles.Facts, val stats: JsonObject)

    fun assemble(outDir: File, regions: List<File>, log: (String) -> Unit): Assembled {
        outDir.mkdirs()
        val parts = ArrayList<WorldIndex.Part>()
        val entries = ArrayList<WorldIndex.Entry>()
        val coarse = LinkedHashMap<Pair<Int, Int>, MutableList<Format3Writer.Encoded>>()
        val built = LinkedHashMap<Pair<Int, Int>, String>()
        var facts: WorldTiles.Facts? = null
        var date: java.time.Instant? = null
        val stats = ArrayList<JsonObject>()
        for (r in regions) {
            val name = r.name
            val index = WorldIndex.read(File(r.path + INDEX).readBytes())
            val tiles = CoarseTiles.read(File(r.path + COARSE))
            if (date == null) date = index.date
            require(index.date == date) { "$name's data is of ${index.date}, the first region's of $date: not one run" }
            // the index's date is the extract's replication time, which world.json and the app's fetch go by
            require(index.date != java.time.Instant.EPOCH) { "$name's data has no date: its extract's header gives no replication time" }
            val f = tiles.facts
            val first = facts ?: f.also { facts = it }
            require(f.dataTime == first.dataTime && f.sources == first.sources && f.source == first.source) {
                "$name's packs were made from ${f.sources} (${f.source}, ${f.dataTime}), the first region's from ${first.sources} (${first.source}, ${first.dataTime}): not one run"
            }
            for (b in tiles.blocks) {
                val other = built.put(b, name)
                require(other == null) { "L6 block ${b.first}/${b.second} in $other and $name" }
            }
            val mine = tiles.blocks.toSet()
            val firstPart = parts.size
            for (p in index.parts) {
                require(parts.none { it.name == p.name }) { "part ${p.name} in two regions" }
                parts += p
            }
            for (e in index.entries) {
                require(e.z == WorldIndex.FINE_BLOCK && (e.x to e.y) in mine) { "$name's index holds pack ${e.z}/${e.x}/${e.y}, not one of its fine packs" }
                entries += WorldIndex.Entry(e.z, e.x, e.y, e.firstLevel, e.lastLevel, firstPart + e.part, e.offset, e.length, e.tiles, e.box, e.sha256)
            }
            for (t in tiles.tiles) {
                require(t.level in WorldTiles.COARSE) { "$name's coarse tiles hold ${t.level}/${t.x}/${t.y}" }
                val block = (t.x shr (t.level - WorldIndex.FINE_BLOCK)) to (t.y shr (t.level - WorldIndex.FINE_BLOCK))
                require(block in mine) { "$name's coarse tile ${t.level}/${t.x}/${t.y} is of block ${block.first}/${block.second}, which it did not build" }
                coarse.getOrPut((t.x shr (t.level - WorldIndex.COARSE_BLOCK)) to (t.y shr (t.level - WorldIndex.COARSE_BLOCK))) { ArrayList() } += t
            }
            stats += Json.parseToJsonElement(File(r.path + STATS).readText()).jsonObject
            log("$name: ${tiles.blocks.size} blocks, ${index.entries.size} fine packs in ${index.parts.joinToString { "${it.name} (${it.bytes} bytes)" }}, ${tiles.tiles.size} coarse tiles")
        }
        val run = facts!!
        val coarseLevels = java.util.TreeMap<Int, LongArray>() // tiles, stored, raw
        val packs = WorldTiles.coarsePacks(coarse, built.keys, run, File(outDir, "scratch-coarse")) { w ->
            for (ch in w.chunks) coarseLevels.getOrPut(ch.z) { LongArray(3) }.let { it[0]++; it[1] += ch.stored.toLong(); it[2] += ch.raw.toLong() }
        }
        val coarseEntries = WorldPack.parts(outDir, WorldTiles.COARSE_PARTS, packs, parts.size, parts)
        File(outDir, "scratch-coarse").deleteRecursively()
        require(parts.size <= 256) { "${parts.size} parts, more than an index entry's part (u8) can name" }
        val index = WorldIndex(date!!, parts, entries + coarseEntries)
        File(outDir, "world.idx").outputStream().use { index.write(it) }
        val total = addUp(stats, coarseLevels, regions.map { it.name })
        File(outDir, WorldTiles.STATS).writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), total) + "\n")
        for ((z, s) in total.getValue("levels").jsonObject) {
            val o = s.jsonObject
            log("L$z: ${o.getValue("tiles")} tiles, ${o.getValue("features")} features, ${o.getValue("stored")} bytes stored (${o.getValue("raw")} raw)")
        }
        log("world: ${index.entries.size} packs (${entries.size} fine, ${coarseEntries.size} coarse) of ${built.size} L6 blocks in ${parts.size} parts, ${parts.sumOf { it.bytes }} bytes; the data of ${DateTimeFormatter.ISO_INSTANT.format(date)}")
        return Assembled(index, run, total)
    }

    /**
     * The regions' numbers added up: each level's tiles, features and bytes (the coarse levels' bytes stored as
     * [coarse] counted them, once their packs were made here), the blocks, each region's seconds and the cut's checks.
     */
    private fun addUp(stats: List<JsonObject>, coarse: Map<Int, LongArray>, names: List<String>): JsonObject {
        val levels = java.util.TreeMap<Int, LongArray>() // tiles, features, stored, raw
        for (s in stats) for ((z, v) in s.getValue("levels").jsonObject) {
            val o = v.jsonObject
            val a = levels.getOrPut(z.toInt()) { LongArray(4) }
            a[0] += o.getValue("tiles").jsonPrimitive.long
            a[1] += o.getValue("features").jsonPrimitive.long
            a[2] += o.getValue("stored").jsonPrimitive.long
            a[3] += o.getValue("raw").jsonPrimitive.long
        }
        for ((z, c) in coarse) {
            val a = levels.getOrPut(z) { LongArray(4) }
            require(a[0] == c[0]) { "L$z: the regions counted ${a[0]} tiles, their packs hold ${c[0]}" }
            a[2] = c[1]
        }
        return buildJsonObject {
            put("blocks", stats.sumOf { it.getValue("blocks").jsonPrimitive.int })
            putJsonObject("levels") {
                for ((z, a) in levels) putJsonObject("$z") { put("tiles", a[0]); put("features", a[1]); put("stored", a[2]); put("raw", a[3]) }
            }
            put("worst_cover", stats.maxOf { it.getValue("worst_cover").jsonPrimitive.double })
            put("rings_outside", stats.sumOf { it.getValue("rings_outside").jsonPrimitive.int })
            put("mended", stats.sumOf { it.getValue("mended").jsonPrimitive.int })
            putJsonObject("regions") {
                for ((i, s) in stats.withIndex()) putJsonObject(names[i]) {
                    put("blocks", s.getValue("blocks"))
                    put("seconds", s.getValue("seconds"))
                    put("coarser", s.getValue("coarser"))
                }
            }
        }
    }

    /**
     * world.json (ENGINE.md step 5's fetch: the app reads it from world-latest at most once a day): the release and the
     * URL its files are under, the data's date, the index's and the globe's size and SHA-256, the parts, the packs and the
     * sources, with the licences' words.
     */
    fun worldJson(a: Assembled, release: String, url: String, index: File, globe: File): String {
        // the planet's world is OpenStreetMap's own planet file's, an area's world Geofabrik's extract's
        val from = if (a.facts.sources.any { it.contains(": planet-") }) "From OpenStreetMap's planet file." else "Extracts by Geofabrik."
        val o = buildJsonObject {
            put("format", 1)
            put("release", release)
            put("url", if (url.endsWith("/")) url else "$url/")
            put("date", DateTimeFormatter.ISO_INSTANT.format(a.index.date))
            put("profile", WorldTiles.PROFILE_VERSION)
            putJsonObject("index") { put("name", index.name); put("bytes", index.length()); put("sha256", WorldPack.sha256(index)) }
            putJsonObject("globe") { put("name", globe.name); put("bytes", globe.length()); put("sha256", WorldPack.sha256(globe)) }
            putJsonArray("parts") {
                for (p in a.index.parts) add(buildJsonObject { put("name", p.name); put("bytes", p.bytes) })
            }
            put("packs", a.index.entries.size)
            put("bytes", a.index.parts.sumOf { it.bytes } + index.length() + globe.length())
            putJsonArray("sources") { for (s in a.facts.sources) add(s) }
            put("licence", "Map data © OpenStreetMap contributors, under the Open Database Licence (ODbL 1.0); the world packs are derived databases offered under the ODbL. $from Land polygons by osmdata.openstreetmap.de. The globe made with Natural Earth.")
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), o) + "\n"
    }
}
