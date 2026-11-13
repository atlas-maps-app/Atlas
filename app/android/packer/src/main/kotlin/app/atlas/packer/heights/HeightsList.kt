package app.atlas.packer.heights

import app.atlas.packer.UsageException
import app.atlas.packer.world.WorldPack
import app.atlas.tiles.WorldIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * `packer heights-list`: the list the app reads of a terrain release (ENGINE.md part 6, step 2), `terrain.json` of
 * format 1, which the release `terrain-latest` carries alone (as `world-latest` carries world.json): the release's tag
 * and where its files are, the sources its regions draw from with the short name the map's credit line shows and the
 * credit the licence screen shows, and each region's index (`<region>-terrain.idx`: its name, bytes and SHA-256) with
 * the box its packs' boxes make together (south, west, north, east, degrees). The packs' parts are named in each index
 * and fetched from the release's URL by range (the app's TerrainFetch).
 */
object HeightsList {
    const val USAGE = """packer heights-list <dir> <out-file> --release TAG --url URL [--registry FILE] [--repo DIR]
      The list the app reads of a terrain release (terrain.json): every <region>-terrain.idx in <dir>, each region one
      of the registry's (--registry, default app/android/packs/terrain-sources.json under --repo), its sources' short
      names and credits, its index's bytes and SHA-256 and its packs' box; --release the release's tag, --url where its
      files are (its download URL). A part an index names that lies in <dir> must be of the length it says."""

    /** The list's format, which the app reads (TerrainFetch); a later one it refuses. */
    const val FORMAT = 1

    /** An index's file name: `<region>-terrain.idx`. */
    const val INDEX_SUFFIX = "-terrain.idx"

    private val VALUED = setOf("--release", "--url", "--registry", "--repo")

    fun run(args: List<String>, out: (String) -> Unit): Int {
        if (args.firstOrNull() != "heights-list") throw UsageException("usage: $USAGE")
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
        if (positional.size != 2) throw UsageException("heights-list takes the indexes' folder and the list's file; usage: $USAGE")
        val release = options["--release"] ?: throw UsageException("heights-list needs --release; usage: $USAGE")
        val url = options["--url"] ?: throw UsageException("heights-list needs --url; usage: $USAGE")
        val repo = File(options["--repo"] ?: ".").absoluteFile
        val registry = TerrainSources.read(options["--registry"]?.let(::File) ?: File(repo, "app/android/packs/terrain-sources.json"))
        val dir = File(positional[0])
        if (!dir.isDirectory) throw UsageException("no folder $dir")
        val list = list(dir, registry, release, url, out)
        val target = File(positional[1])
        target.absoluteFile.parentFile?.mkdirs()
        target.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), list) + "\n")
        out("${target.path}: ${list.getValue("regions").let { (it as kotlinx.serialization.json.JsonArray).size }} regions of $release")
        return 0
    }

    /**
     * terrain.json for the indexes in [dir] (`*-terrain.idx`, by name), each region's sources from [registry]: the
     * release [release] whose files lie at [url]. Throws where an index names a region the registry builds no terrain
     * for, holds no pack, or a part beside it is not of the length it says.
     */
    fun list(dir: File, registry: TerrainSources, release: String, url: String, log: (String) -> Unit = {}): JsonObject {
        require(release.isNotBlank() && !release.contains('/')) { "a release tag of '$release'" }
        require(url.startsWith("https://") || url.startsWith("http://")) { "the release's files at '$url'" }
        val base = if (url.endsWith("/")) url else "$url/"
        val indexes = dir.listFiles { f -> f.isFile && f.name.endsWith(INDEX_SUFFIX) }.orEmpty().sortedBy { it.name }
        if (indexes.isEmpty()) throw UsageException("no *$INDEX_SUFFIX in $dir")
        class Region(val id: String, val file: File, val index: WorldIndex)
        val regions = indexes.map { f ->
            val id = f.name.removeSuffix(INDEX_SUFFIX)
            registry.region(id)
            val index = WorldIndex.read(f.readBytes())
            require(index.entries.isNotEmpty()) { "${f.name} holds no pack" }
            for (p in index.parts) {
                val part = File(dir, p.name)
                if (part.isFile) require(part.length() == p.bytes) { "${p.name} is ${part.length()} bytes, ${f.name} says ${p.bytes}" }
            }
            log("${f.name}: ${index.entries.size} packs in ${index.parts.size} parts (${index.parts.count { File(dir, it.name).isFile }} beside it)")
            Region(id, f, index)
        }
        // the sources the regions draw from, in the registry's order
        val used = regions.flatMap { registry.region(it.id).sources }.toSet()
        return buildJsonObject {
            put("format", FORMAT)
            put("release", release)
            put("url", base)
            putJsonArray("sources") {
                for (s in registry.sources) {
                    if (s.id !in used) continue
                    addJsonObject {
                        put("id", s.id)
                        put("name", s.short)
                        put("credit", s.credit)
                    }
                }
            }
            putJsonArray("regions") {
                for (r in regions) addJsonObject {
                    put("id", r.id)
                    val b = boxOf(r.index)
                    putJsonArray("box") { for (v in b) add(v) }
                    putJsonObject("index") {
                        put("name", r.file.name)
                        put("bytes", r.file.length())
                        put("sha256", WorldPack.sha256(r.file))
                    }
                }
            }
        }
    }

    /** The box [index]'s packs' boxes make together: south, west, north, east, degrees (their 1e-7° grid's). */
    fun boxOf(index: WorldIndex): DoubleArray {
        val e = index.entries
        return doubleArrayOf(e.minOf { it.box[0] } / 1e7, e.minOf { it.box[1] } / 1e7, e.maxOf { it.box[2] } / 1e7, e.maxOf { it.box[3] } / 1e7)
    }
}
