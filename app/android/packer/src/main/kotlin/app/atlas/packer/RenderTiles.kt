package app.atlas.packer

import app.atlas.render.data.PackManifest
import app.atlas.render.data.PackReader
import app.atlas.render.live.EngineVersion
import app.atlas.render.live.PackLibrary
import app.atlas.render.py.PyCompat
import app.atlas.rendertiles.compile.SceneToTiles
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.StyleVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import kotlin.system.exitProcess

/**
 * `packer render-tiles`: render packs (format 3, plan-vector D2 and Appendix A) made by the port's own loader, stage P
 * of the vector engine.
 *
 * `--bundled` makes the app's bundled set: the Westerkerk's living map from the app's bundled element pack, and the
 * canal ring from `packs-renders`' grachten.atlas (fetched once into the packs folder, checked against its SHA-256 as
 * `packs.json` publishes it), with their index `bundled.json`. Gradle builds them into the APK's assets
 * (`:app:generateBundledTiles`); they are not committed.
 *
 * `--renders <packs.json> <out-dir>` makes the render pack of every region a release lists (packs-renders, plan-vector
 * K20a), and the list again with each render pack beside its element pack. A region whose scene SceneToTiles refuses
 * ([refusal]: a feature the format carries only from a later package, or a field with no column yet) gets none and is
 * named with SceneToTiles' own reason, so the region builds as soon as the format carries what it holds.
 */
object RenderTiles {
    const val USAGE = """packer render-tiles --bundled <out-dir> [--repo DIR] [--packs DIR] [--work DIR] [--quiet]
      The app's bundled render tiles: amsterdam.atlas (the Westerkerk's living map, from the app's bundled pack) and
      grachten.atlas (the canal ring, from packs-renders' grachten.atlas in --packs, by default <repo>/cache/packs/regions,
      downloaded from the release when it is not there and checked against its SHA-256), and bundled.json, their index.
      --repo is the repo root (default: the current directory); --work is where the engine unpacks the packs (default: a
      temporary folder, deleted after). --quiet prints warnings only.
  packer render-tiles --renders <region.atlas> <out.atlas> [--work DIR] [--quiet]
      One region's render pack from its element pack.
  packer render-tiles --renders <packs.json> <out-dir> [--packs DIR] [--only a,b] [--work DIR] [--quiet]
      Every region of a release's list (packs-renders' packs.json): its element pack from --packs (default: the list's
      folder), downloaded from the list's URL when not there and used only when its size and SHA-256 are the list's; its
      render pack <id>.render.atlas in <out-dir>; and <out-dir>/packs.json, the list with each render pack beside its
      element pack (kind "render": size, SHA-256, URL in the same release, payload, engine and style, and the SHA-256 of
      the element pack it was made from). A region the render tiles cannot carry yet gets no render pack and is named
      with SceneToTiles' reason ("render_gap"); any other failure fails the run. --only builds the regions named and
      keeps the other entries as they are."""

    /** An element pack: where a release publishes it, its identity there, and its file name. */
    class Source(val id: String, val url: String, val sha256: String, val bytes: Long, val file: String = "$id.atlas")

    /** The canal ring's element pack, as packs-renders' `packs.json` lists it (published 14 October 2026). */
    val CANAL_RING = Source(
        "grachten", "https://github.com/atlas-maps-app/Atlas/releases/download/packs-renders/grachten.atlas",
        "3ec8629e2d3f5708a604ada1592b4bc4a5dd722e69c2aaefa1cbafe5811593ec", 5_190_931L,
    )

    /** The app's bundled element pack of the Westerkerk, in the repo. */
    const val WESTERKERK = "app/android/app/src/main/assets/packs/amsterdam.atlas"

    /** The bundled set's index. */
    const val INDEX = "bundled.json"

    /** The list `--renders` writes into its output folder. */
    const val LIST = "packs.json"

    /** One pack made: its id, file, size, SHA-256, the element pack it came from, and the engine and style it was made with. */
    class Made(
        val id: String,
        val file: File,
        val bytes: Long,
        val sha256: String,
        val source: File,
        val sourceSha256: String,
        val profile: String,
        val levels: List<Int>,
        val chunks: Int,
        val engine: String,
        val style: String,
    )

    /** One region of a release's list: its render pack, or why it has none ([gap]); [elementBytes] is its element pack's size. */
    class Outcome(val id: String, val made: Made?, val gap: String?, val elementBytes: Long) {
        /** The render pack's size as a share of the element pack's, in per cent; NaN without one. */
        val share: Double get() = if (made == null || elementBytes <= 0) Double.NaN else 100.0 * made.bytes / elementBytes
    }

    /** What [renders] wrote: the list (also in `<out>/packs.json`), and the outcome of each region it built, in the list's order. */
    class Listed(val list: JsonObject, val outcomes: List<Outcome>)

    /** `packer render-tiles …` as its own program (Gradle runs it so): [args] without the command word. */
    @JvmStatic
    fun main(args: Array<String>) {
        val code = try {
            run(listOf("render-tiles") + args, ::println)
        } catch (e: UsageException) {
            System.err.println(e.message)
            System.err.println()
            System.err.println(USAGE)
            2
        } catch (e: Exception) {
            System.err.println("packer render-tiles: ${e.message ?: e}")
            1
        }
        exitProcess(code)
    }

    /** Runs `render-tiles …` ([args] start with the command word); [out] gets the report lines. Returns the exit code. */
    fun run(args: List<String>, out: (String) -> Unit): Int {
        if (args.firstOrNull() != "render-tiles") throw UsageException("usage: $USAGE")
        val positional = ArrayList<String>()
        val options = LinkedHashMap<String, String?>()
        var i = 1
        while (i < args.size) {
            val s = args[i]
            if (s.startsWith("--")) {
                if (s in VALUED) {
                    if (i + 1 >= args.size) throw UsageException("$s needs a value")
                    options[s] = args[++i]
                } else options[s] = null
            } else positional += s
            i++
        }
        val unknown = options.keys - setOf("--bundled", "--renders", "--repo", "--packs", "--work", "--only", "--quiet")
        if (unknown.isNotEmpty()) throw UsageException("unknown option ${unknown.joinToString()}; usage: $USAGE")
        val quiet = "--quiet" in options
        val log: (String) -> Unit = if (quiet) { _ -> } else out
        val work = options["--work"]?.let(::File)
        when {
            "--bundled" in options && "--renders" !in options -> {
                if (positional.size != 1) throw UsageException("render-tiles --bundled takes the output folder; usage: $USAGE")
                if ("--only" in options) throw UsageException("--only is for a list of regions; usage: $USAGE")
                val repo = File(options["--repo"] ?: ".").absoluteFile
                if (!File(repo, WESTERKERK).isFile) throw UsageException("$repo is not the repo root (no $WESTERKERK)")
                val packs = options["--packs"]?.let(::File) ?: File(repo, "cache/packs/regions")
                val made = bundled(repo, packs, File(positional[0]), work, log)
                log("${made.size} render packs, ${made.sumOf { it.bytes }} bytes; engine ${EngineVersion.ID}, style ${StyleVersion.ID}")
            }
            "--renders" in options && "--bundled" !in options -> {
                if (positional.size != 2) throw UsageException("render-tiles --renders takes a region's pack or a list, and the output; usage: $USAGE")
                if ("--repo" in options) throw UsageException("--repo is for --bundled; usage: $USAGE")
                val src = File(positional[0])
                if (!src.isFile) throw UsageException("no pack or list $src")
                if (src.name.endsWith(".json")) {
                    val only = options["--only"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                    val packs = options["--packs"]?.let(::File) ?: src.absoluteFile.parentFile
                    val l = renders(src, File(positional[1]), packs, work, only, log = log)
                    val built = l.outcomes.count { it.made != null }
                    log("${l.outcomes.size} regions: $built render packs, ${l.outcomes.size - built} without one; engine ${EngineVersion.ID}, style ${StyleVersion.ID}")
                } else {
                    if ("--only" in options || "--packs" in options) throw UsageException("--only and --packs are for a list of regions; usage: $USAGE")
                    val m = one(src, File(positional[1]), work, log)
                    log("${m.file.path}: ${m.bytes} bytes, ${m.chunks} tiles, ${m.profile}")
                }
            }
            else -> throw UsageException("usage: $USAGE")
        }
        return 0
    }

    /** The bundled set from the repo's Westerkerk pack and the canal ring's (fetched into [packs]), into [out] with its index. */
    fun bundled(repo: File, packs: File, out: File, work: File? = null, log: (String) -> Unit = {}): List<Made> {
        out.mkdirs()
        out.listFiles { f -> f.name.endsWith(".atlas") || f.name == INDEX }?.forEach { it.delete() }
        val sources = listOf(File(repo, WESTERKERK), fetch(CANAL_RING, packs, log))
        val made = sources.map { src ->
            val id = PackReader.open(src).use { it.manifest.id }
            one(src, File(out, "$id.atlas"), work, log)
        }
        File(out, INDEX).writeText(Manifest.pretty(index(made)) + "\n")
        return made
    }

    /** One render pack of [pack] into [file]. */
    fun one(pack: File, file: File, work: File? = null, log: (String) -> Unit = {}): Made {
        val dir = work ?: Files.createTempDirectory("atlas-rtiles").toFile()
        val minorBefore = PyCompat.minor
        // the port as the phone's renders ran it: CPython 3.9's last bits
        PyCompat.minor = 9
        try {
            val p = SceneToTiles.pack(pack, file, dir, log = log)
            return Made(
                p.head.region, file, file.length(), sha256(file), pack, p.head.source, p.head.profile.label, p.head.levels.toList(),
                p.written.chunks.size, p.head.engineVersion, p.head.styleVersion,
            )
        } finally {
            PyCompat.minor = minorBefore
            if (work == null) dir.deleteRecursively()
        }
    }

    /**
     * The render pack of every region of the release list [listFile] into [out], with the list again in `<out>/packs.json`
     * (see [USAGE]). Each element pack comes from [packs], or from its URL into [packs], and is used only when its size
     * and SHA-256 are the list's. A region SceneToTiles refuses ([refusal]) gets no render pack and its reason as
     * `render_gap`; any other failure is thrown. With [only], the other regions' entries stay as they are. The list's
     * `generated` becomes [now] when anything in it changed, else stays.
     */
    fun renders(listFile: File, out: File, packs: File = listFile.absoluteFile.parentFile, work: File? = null, only: Set<String>? = null, now: Instant = Instant.now(), log: (String) -> Unit = {}): Listed {
        val root = Json.parseToJsonElement(listFile.readText()) as? JsonObject ?: throw IOException("${listFile.name} is not a JSON object")
        val entries = (root["packs"] as? JsonArray ?: throw IOException("${listFile.name} lists no packs")).map {
            it as? JsonObject ?: throw IOException("${listFile.name} lists a pack that is not an object")
        }
        val ids = entries.map { e ->
            val id = e.str("id") ?: throw IOException("${listFile.name} lists a pack without an id")
            if (!ID.matches(id)) throw IOException("${listFile.name} lists the pack '$id': an id is lower-case letters, digits and dashes")
            id
        }
        val twice = ids.groupBy { it }.filterValues { it.size > 1 }.keys
        if (twice.isNotEmpty()) throw IOException("${listFile.name} lists ${twice.joinToString()} twice")
        if (only != null) {
            val missing = only - ids.toSet()
            if (missing.isNotEmpty()) throw UsageException("--only names regions ${listFile.name} does not list: ${missing.sorted().joinToString()}")
        }
        out.mkdirs()
        val outcomes = ArrayList<Outcome>()
        val written = entries.mapIndexed { i, e ->
            if (only != null && ids[i] !in only) return@mapIndexed e
            val (o, elementUrl) = region(e, ids[i], out, packs, work, log)
            outcomes += o
            withRender(e, o, elementUrl)
        }
        var list = JsonObject(root + ("packs" to JsonArray(written)))
        if (JsonObject(list - GENERATED) != JsonObject(root - GENERATED)) list = JsonObject(list + (GENERATED to JsonPrimitive(now.toString())))
        File(out, LIST).writeText(Manifest.pretty(list) + "\n")
        return Listed(list, outcomes)
    }

    /**
     * SceneToTiles' reason when [x] (or one of its causes) is its refusal of a scene the render tiles cannot carry yet: a
     * feature the format carries only from a later package, or a field the schema has no column for. Null for anything
     * else, which is a failure.
     */
    fun refusal(x: Throwable): String? {
        var t: Throwable? = x
        var depth = 0
        while (t != null && depth++ < 16) {
            val m = t.message
            if (m != null && (t is UnsupportedOperationException && CANNOT_CARRY in m || t is IllegalStateException && NO_COLUMN.containsMatchIn(m))) return m
            t = t.cause
        }
        return null
    }

    /** [e]'s render pack built into [out] (its outcome) and its element pack's URL. */
    private fun region(e: JsonObject, id: String, out: File, packs: File, work: File?, log: (String) -> Unit): Pair<Outcome, String?> {
        val files = (e["files"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val atlas = files.firstOrNull { it.str("kind") == "atlas" }
        val file = File(out, "$id${PackLibrary.RENDER_SUFFIX}")
        if (atlas == null) {
            file.delete()
            log("$id: no render pack: the list gives it no element pack")
            return Outcome(id, null, "the list gives no element pack", -1) to null
        }
        if (atlas["parts"] != null) throw IOException("$id: its element pack comes in parts, which render-tiles does not join")
        val url = atlas.str("url") ?: throw IOException("$id: the list gives no URL for its element pack")
        val sha = atlas.str("sha256")?.lowercase() ?: throw IOException("$id: the list gives no SHA-256 for its element pack")
        val bytes = atlas.str("bytes")?.toLongOrNull() ?: throw IOException("$id: the list gives no size for its element pack")
        val name = atlas.str("name") ?: "$id.atlas"
        if (!FILE.matches(name)) throw IOException("$id: its element pack's name '$name' is not a plain file name")
        val element = fetch(Source(id, url, sha, bytes, name), packs, log)
        file.delete()
        val made = try {
            one(element, file, work, log)
        } catch (x: Exception) {
            file.delete()
            val why = refusal(x) ?: throw IllegalStateException("$id: ${x.message ?: x}", x)
            log("$id: no render pack: $why")
            return Outcome(id, null, why, bytes) to url
        }
        if (made.id != id) throw IllegalStateException("$id: its element pack is the region ${made.id}")
        if (made.sourceSha256 != sha) throw IllegalStateException("$id: the render pack was made from ${made.sourceSha256}, not the list's $sha")
        val o = Outcome(id, made, null, bytes)
        log("%s: %s, %d bytes (%.1f %% of its element pack), %d tiles, %s".format(java.util.Locale.ROOT, id, file.name, made.bytes, o.share, made.chunks, made.profile))
        return o to url
    }

    /** [e] with its render file as [o] made it (replacing any earlier one) or, without one, `render_gap`'s reason. */
    private fun withRender(e: JsonObject, o: Outcome, elementUrl: String?): JsonObject {
        val files = (e["files"] as? JsonArray).orEmpty().filter { (it as? JsonObject)?.str("kind") != PackLibrary.RENDER_KIND }
        val m = LinkedHashMap<String, JsonElement>(e)
        val made = o.made
        m["files"] = JsonArray(if (made != null && elementUrl != null) files + renderFile(made, elementUrl) else files)
        m.remove(PackLibrary.RENDER_GAP)
        o.gap?.let { m[PackLibrary.RENDER_GAP] = JsonPrimitive(it) }
        return JsonObject(m)
    }

    /** A render pack's file in a release list: published beside its element pack (at [elementUrl]) in the same release. */
    private fun renderFile(m: Made, elementUrl: String): JsonObject = buildJsonObject {
        put("kind", PackLibrary.RENDER_KIND)
        put("name", m.file.name)
        put("bytes", m.bytes)
        put("sha256", m.sha256)
        put("url", URI(elementUrl).resolve(m.file.name).toString())
        put("format", PackManifest.FORMAT)
        put("version", Format.VERSION)
        put("layout", Format.LAYOUT)
        put("payload", Format.PAYLOAD)
        put("profile", m.profile)
        putJsonArray("levels") { for (z in m.levels) add(z) }
        put("chunks", m.chunks)
        put("engine", m.engine)
        put("style", m.style)
        put("source_sha256", m.sourceSha256)
    }

    /**
     * [source]'s element pack in [dir]: the file there when its size and SHA-256 are the published ones, else downloaded
     * over https from the release (through a temporary file) and checked. A file whose hash differs is never used.
     * Two runs may fetch into the same [dir] at once (Gradle runs `:app`'s and `:mapcorecheck`'s bundled tiles in
     * parallel, Android run 39325199622): each downloads into a temporary file of its own and moves it into place
     * atomically, so neither sees the other's half-written file.
     */
    fun fetch(source: Source, dir: File, log: (String) -> Unit = {}): File {
        val file = File(dir, source.file)
        if (file.isFile && file.length() == source.bytes && sha256(file) == source.sha256) return file
        if (!source.url.startsWith("https://")) throw IOException("${source.url}: an element pack comes over https only")
        dir.mkdirs()
        val tmp = File.createTempFile(".${source.file}.", ".part", dir)
        log("downloading ${source.url}")
        var url = URI(source.url).toURL()
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IOException("${source.url}: a redirect without a location")
                conn.disconnect()
                url = url.toURI().resolve(loc).toURL()
                if (url.protocol != "https") throw IOException("${source.url}: a redirect away from https")
                if (++hops > 5) throw IOException("${source.url}: too many redirects")
                continue
            }
            if (code != 200) throw IOException("${source.url}: HTTP $code")
            break
        }
        try {
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        val got = sha256(tmp)
        if (tmp.length() != source.bytes || got != source.sha256) {
            tmp.delete()
            throw IOException("${source.url}: ${tmp.length()} bytes of SHA-256 $got, not the published ${source.bytes} bytes of ${source.sha256}")
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return file
    }

    private fun index(made: List<Made>) = buildJsonObject {
        put("format", Format.VERSION)
        put("payload", Format.PAYLOAD)
        put("engine", EngineVersion.ID)
        put("style", StyleVersion.ID)
        putJsonArray("packs") {
            for (m in made) addJsonObject {
                put("id", m.id)
                put("file", m.file.name)
                put("bytes", m.bytes)
                put("sha256", m.sha256)
                put("profile", m.profile)
                putJsonArray("levels") { for (z in m.levels) add(z) }
                put("chunks", m.chunks)
                putJsonObject("source") {
                    put("file", m.source.name)
                    put("sha256", m.sourceSha256)
                }
            }
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val b = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(b)
                if (n < 0) break
                md.update(b, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private val VALUED = setOf("--repo", "--packs", "--work", "--only")

    /** SceneToTiles' words when a scene holds what the format carries only from a later package. */
    private const val CANNOT_CARRY = "render tiles cannot carry"

    /** SceneToTiles' words when a record holds a field the schema has no column for. */
    private val NO_COLUMN = Regex("""the field \S+ \(\S+\) has no column in the \S+ layer""")

    private const val GENERATED = "generated"
    private val ID = Regex("[a-z0-9][a-z0-9-]*")
    private val FILE = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
}
