package app.atlas.packer

import app.atlas.geo.BBox
import app.atlas.packer.pbf.PbfReader
import app.atlas.render.data.OverpassClient
import app.atlas.render.data.PackReader
import app.atlas.render.data.PackWriter
import app.atlas.render.data.RegionBuilder
import app.atlas.render.data.RegionConfig
import app.atlas.search.PlaceStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """packer: Atlas's offline packs from OpenStreetMap extracts

  packer places <in.osm.pbf> <out.db> [--name NAME] [--langs en,nl,de,fr] [--bbox W,S,E,N]
                [--edit-times] [--threads N] [--no-gzip] [--keep-db] [--no-vacuum]
      Builds the place store :search reads, writes <out.db>.gz (unless --no-gzip) and the
      sidecar <out>.json that `manifest` reads.

  packer manifest <dir> <release-url-base> [--regions regions.json] [--previous packs.json] [--out FILE]
                  [--split-bytes N]
      Writes <id>.pack.json for each region with files in <dir> and the merged packs.json
      (default <dir>/packs.json). Files over N bytes (default 2000000000, under GitHub's 2 GiB
      limit for release assets) are cut into parts <name>.000, .001…

  packer info <in.osm.pbf> [--count]
      Prints the file's header and, with --count, what it holds.

  packer renders <out-dir> [--only a,b] [--repo DIR]
      The renders' own data as .atlas packs: every data/regions/<name>/raw.json with its places.py entry, dem*.json and
      weather, and Amsterdam's data/ams/raw.json. --repo is the repo root (default: the current directory).

  packer region <W,S,E,N> <name> [--out FILE] [--id ID] [--country XX]
      Builds a .atlas pack from Overpass with fetch/regions.py's query (a big box is split, the parts merged by id).

  packer pbf <in.osm.pbf> <W,S,E,N> [--name NAME] [--out FILE] [--id ID] [--country XX]
      Builds the same pack from a Geofabrik extract: the elements Overpass would give for the box.
  packer pbf <in.osm.pbf> [W,S,E,N] --tiled [--name NAME] [--id ID] [--out FILE] [--country XX] [--work DIR] [--threads N] [--memory MiB]
      The same as a tiled pack (format 2); without a box, the whole extract.

  packer packinfo <file.atlas>
      Prints a pack's manifest, sections and sizes.

  ${Anchors.USAGE}

  ${RenderTiles.USAGE}"""

/** The command line: `places`, `manifest`, `info`, and the region packs: `renders`, `region`, `pbf`, `packinfo`. */
fun main(args: Array<String>) {
    val code = try {
        run(args.toList(), ::println)
    } catch (e: UsageException) {
        System.err.println(e.message)
        System.err.println()
        System.err.println(USAGE)
        2
    } catch (e: Exception) {
        System.err.println("packer: ${e.message ?: e}")
        1
    }
    exitProcess(code)
}

/** Thrown for a bad command line. */
class UsageException(message: String) : IllegalArgumentException(message)

/** Runs one command; [out] gets its report lines. Returns the exit code. */
fun run(args: List<String>, out: (String) -> Unit): Int {
    val a = Args(args)
    when (a.command) {
        "places" -> {
            val (pbf, db) = a.files(2)
            val options = PackOptions(
                langs = a.value("--langs")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: PlaceStore.DEFAULT_LANGS,
                clip = a.value("--bbox")?.let(::parseBox),
                editTimes = a.flag("--edit-times"),
                threads = a.value("--threads")?.let { it.toIntOrNull()?.takeIf { n -> n in 1..64 } ?: throw UsageException("bad --threads '$it': want 1 to 64") }
                    ?: PbfReader.defaultThreads(),
            )
            val name = a.value("--name")
            val gzip = !a.flag("--no-gzip")
            val keep = a.flag("--keep-db")
            val vacuum = !a.flag("--no-vacuum")
            a.done()
            val pack = PlacesPackBuilder.build(File(pbf), File(db), options, name, gzip, keep, vacuum) { out("  $it") }
            val s = pack.stats
            out("${pack.file.name}: ${s.places} places, ${s.addresses} address points, ${s.trees} trees from ${s.nodes} nodes, ${s.ways} ways, ${s.relations} relations in ${s.millis / 1000} s")
            out("  ${pack.rawBytes shr 20} MiB store, ${pack.file.length() shr 20} MiB file; OSM data of ${s.header.replicationTime ?: "unknown date"}")
            if (s.unlocated > 0 || s.missingNodes > 0) out("  ${s.unlocated} kept elements without a position, ${s.missingNodes} node references outside the extract")
        }
        "manifest" -> {
            val (dir, base) = a.files(2)
            val regions = a.value("--regions")?.let { Manifest.readRegions(File(it)) }
            val previous = a.value("--previous")?.let(::File)?.takeIf { it.isFile && it.length() > 0 }?.let { Json.parseToJsonElement(it.readText()).jsonObject }
            val target = a.value("--out")?.let(::File) ?: File(dir, "packs.json")
            val split = a.value("--split-bytes")?.let { it.toLongOrNull()?.takeIf { n -> n > 0 } ?: throw UsageException("bad --split-bytes '$it'") }
                ?: Manifest.SPLIT_BYTES
            a.done()
            val d = File(dir)
            if (!d.isDirectory) throw UsageException("$dir is not a directory")
            val list = Manifest.build(d, base, regions, previous, splitBytes = split)
            target.writeText(Manifest.pretty(list) + "\n")
            out("${target.path}: ${(list["packs"] as kotlinx.serialization.json.JsonArray).size} packs")
        }
        "info" -> {
            val (pbf) = a.files(1)
            val count = a.flag("--count")
            a.done()
            val reader = PbfReader(File(pbf))
            val h = reader.header
            val info = buildJsonObject {
                put("file", File(pbf).name)
                put("bytes", File(pbf).length())
                h.bbox?.let { b -> putJsonArray("bbox") { for (v in Manifest.corners(b)) add(v) } }
                h.replicationTime?.let { put("osm_timestamp", it.toString()) }
                h.replicationSequence?.let { put("osm_sequence", it) }
                h.writingProgram?.let { put("writer", it) }
                putJsonArray("required_features") { for (f in h.requiredFeatures) add(f) }
                putJsonArray("optional_features") { for (f in h.optionalFeatures) add(f) }
                if (count) {
                    val c = reader.count()
                    put("nodes", c.nodes)
                    put("ways", c.ways)
                    put("relations", c.relations)
                    put("tagged_nodes", c.taggedNodes)
                    put("way_nodes", c.wayRefs)
                    put("members", c.members)
                    put("tags", c.tags)
                    put("max_node_id", c.maxNodeId)
                    put("max_way_id", c.maxWayId)
                    put("max_relation_id", c.maxRelationId)
                    put("sorted", c.sorted)
                    if (c.lastEdit > 0) put("last_edit", java.time.Instant.ofEpochMilli(c.lastEdit).toString())
                    c.nodeBox?.let { b -> putJsonArray("node_bbox") { for (v in Manifest.corners(b)) add(v) } }
                }
            }
            out(Manifest.pretty(info))
        }
        "renders" -> {
            val (dir) = a.files(1)
            val only = a.value("--only")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            val repo = File(a.value("--repo") ?: ".").absoluteFile
            a.done()
            if (!File(repo, "data/regions/places.py").isFile) throw UsageException("$repo is not the repo root (no data/regions/places.py)")
            val results = Renders.build(repo, File(dir), only) { out(it) }
            out("${results.size} packs, ${results.sumOf { it.stats.bytes } shr 10} KiB in all")
        }
        "region" -> {
            val (box, name) = a.files(2)
            val bbox = parseBox(box)
            val id = a.value("--id") ?: RegionBuilder.slug(name)
            val file = File(a.value("--out") ?: "$id.atlas")
            val country = a.value("--country")
            a.done()
            val stats = RegionBuilder.buildFromOverpass(OverpassClient(log = { out("  $it") }), id, name, bbox, file, country)
            out("${file.path}: ${stats.elements} elements (${stats.nodes} nodes, ${stats.ways} ways, ${stats.relations} relations), ${file.length() shr 10} KiB in ${stats.millis} ms")
        }
        "pbf" -> {
            if ("--tiled" in args) return TiledPack.run(args, out)
            val (pbf, box) = a.files(2)
            val bbox = parseBox(box)
            val name = a.value("--name") ?: File(pbf).name.removeSuffix(".osm.pbf").removeSuffix(".pbf")
            val id = a.value("--id") ?: RegionBuilder.slug(name)
            val file = File(a.value("--out") ?: "$id.atlas")
            val country = a.value("--country")
            a.done()
            val extract = PbfRegion.extract(File(pbf), bbox) { out("  $it") }
            val spec = RegionBuilder.spec(id, RegionConfig.forBox(name, bbox, country), extract.source(), listOf(File(pbf).name + " (Geofabrik, OpenStreetMap contributors, ODbL 1.0)"))
            val stats = PackWriter.write(spec, file)
            out("${file.path}: ${stats.elements} elements (${stats.nodes} nodes, ${stats.ways} ways, ${stats.relations} relations), ${file.length() shr 10} KiB in ${stats.millis} ms")
        }
        "anchors" -> return Anchors.run(args, out)
        "render-tiles" -> return RenderTiles.run(args, out)
        "packinfo" -> {
            val (path) = a.files(1)
            a.done()
            PackReader.open(File(path)).use { r ->
                out(r.manifest.pretty())
                out("size: ${r.size} bytes, ${r.chunkCount} chunks, sections: ${r.sectionNames.joinToString()}")
                val bad = r.verify()
                out(if (bad.isEmpty()) "checksums: all sections intact" else "checksums: damaged sections ${bad.joinToString()}")
            }
        }
        null, "help", "--help", "-h" -> {
            out(USAGE)
            return if (a.command == null) 2 else 0
        }
        else -> throw UsageException("unknown command '${a.command}'")
    }
    return 0
}

/** "W,S,E,N" in degrees. */
fun parseBox(s: String): BBox {
    val v = s.split(',').map { it.trim().toDoubleOrNull() ?: throw UsageException("bad box '$s': want W,S,E,N in degrees") }
    if (v.size != 4 || v[0] >= v[2] || v[1] >= v[3] || v[1] < -90 || v[3] > 90 || v[0] < -180 || v[2] > 180) throw UsageException("bad box '$s': want W,S,E,N in degrees")
    return BBox(v[0], v[1], v[2], v[3])
}

/** A small parser: the command, positional files, `--key value` options and `--flag`s; unknown options are errors. */
private class Args(args: List<String>) {
    val command: String? = args.firstOrNull()
    private val positional = ArrayList<String>()
    private val options = LinkedHashMap<String, String?>()
    private val read = HashSet<String>()

    init {
        var i = 1
        while (i < args.size) {
            val s = args[i]
            if (s.startsWith("--")) {
                val eq = s.indexOf('=')
                if (eq > 0) options[s.substring(0, eq)] = s.substring(eq + 1)
                else if (s in VALUED) {
                    if (i + 1 >= args.size) throw UsageException("$s needs a value")
                    options[s] = args[++i]
                } else options[s] = null
            } else positional += s
            i++
        }
    }

    fun files(n: Int): List<String> {
        if (positional.size != n) throw UsageException("$command takes $n file arguments, got ${positional.size}")
        return positional
    }

    fun value(key: String): String? {
        read += key
        return options[key]
    }

    fun flag(key: String): Boolean {
        read += key
        return key in options
    }

    fun done() {
        val unknown = options.keys - read
        if (unknown.isNotEmpty()) throw UsageException("unknown option ${unknown.joinToString()}")
    }

    companion object {
        val VALUED = setOf("--name", "--langs", "--bbox", "--threads", "--regions", "--previous", "--out", "--split-bytes", "--only", "--repo", "--country", "--id")
    }
}
