package app.atlas.packer

import app.atlas.render.data.PackReader
import app.atlas.render.live.EngineVersion
import app.atlas.render.py.PyCompat
import app.atlas.rendertiles.compile.SceneToTiles
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.StyleVersion
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
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * `packer render-tiles`: render packs (format 3, plan-vector D2 and Appendix A) made by the port's own loader, stage P
 * of the vector engine.
 *
 * `--bundled` makes the app's bundled set: the Westerkerk's living map from the app's bundled element pack, and the
 * canal ring from `packs-renders`' grachten.atlas (fetched once into the packs folder, checked against its SHA-256 as
 * `packs.json` publishes it), with their index `bundled.json`. Gradle builds them into the APK's assets
 * (`:app:generateBundledTiles`); they are not committed.
 */
object RenderTiles {
    const val USAGE = """packer render-tiles --bundled <out-dir> [--repo DIR] [--packs DIR] [--work DIR] [--quiet]
      The app's bundled render tiles: amsterdam.atlas (the Westerkerk's living map, from the app's bundled pack) and
      grachten.atlas (the canal ring, from packs-renders' grachten.atlas in --packs, by default <repo>/cache/packs/regions,
      downloaded from the release when it is not there and checked against its SHA-256), and bundled.json, their index.
      --repo is the repo root (default: the current directory); --work is where the engine unpacks the packs (default: a
      temporary folder, deleted after). --quiet prints warnings only.
  packer render-tiles --renders <region.atlas> <out.atlas> [--work DIR] [--quiet]
      One region's render pack from its element pack."""

    /** An element pack the bundled set is made from: where packs-renders publishes it, and its identity there. */
    class Source(val id: String, val url: String, val sha256: String, val bytes: Long) {
        val file: String get() = "$id.atlas"
    }

    /** The canal ring's element pack, as packs-renders' `packs.json` lists it (published 14 October 2026). */
    val CANAL_RING = Source(
        "grachten", "https://github.com/atlas-maps-app/Atlas/releases/download/packs-renders/grachten.atlas",
        "3ec8629e2d3f5708a604ada1592b4bc4a5dd722e69c2aaefa1cbafe5811593ec", 5_190_931L,
    )

    /** The app's bundled element pack of the Westerkerk, in the repo. */
    const val WESTERKERK = "app/android/app/src/main/assets/packs/amsterdam.atlas"

    /** The bundled set's index. */
    const val INDEX = "bundled.json"

    /** One pack made: its id, file, size, SHA-256, and the element pack it came from. */
    class Made(val id: String, val file: File, val bytes: Long, val sha256: String, val source: File, val sourceSha256: String, val profile: String, val levels: List<Int>, val chunks: Int)

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
        val unknown = options.keys - setOf("--bundled", "--renders", "--repo", "--packs", "--work", "--quiet")
        if (unknown.isNotEmpty()) throw UsageException("unknown option ${unknown.joinToString()}; usage: $USAGE")
        val quiet = "--quiet" in options
        val log: (String) -> Unit = if (quiet) { _ -> } else out
        val work = options["--work"]?.let(::File)
        when {
            "--bundled" in options && "--renders" !in options -> {
                if (positional.size != 1) throw UsageException("render-tiles --bundled takes the output folder; usage: $USAGE")
                val repo = File(options["--repo"] ?: ".").absoluteFile
                if (!File(repo, WESTERKERK).isFile) throw UsageException("$repo is not the repo root (no $WESTERKERK)")
                val packs = options["--packs"]?.let(::File) ?: File(repo, "cache/packs/regions")
                val made = bundled(repo, packs, File(positional[0]), work, log)
                log("${made.size} render packs, ${made.sumOf { it.bytes }} bytes; engine ${EngineVersion.ID}, style ${StyleVersion.ID}")
            }
            "--renders" in options && "--bundled" !in options -> {
                if (positional.size != 2) throw UsageException("render-tiles --renders takes the region's pack and the output file; usage: $USAGE")
                val pack = File(positional[0])
                if (!pack.isFile) throw UsageException("no pack $pack")
                val m = one(pack, File(positional[1]), work, log)
                log("${m.file.path}: ${m.bytes} bytes, ${m.chunks} tiles, ${m.profile}")
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
            return Made(p.head.region, file, file.length(), sha256(file), pack, p.head.source, p.head.profile.label, p.head.levels.toList(), p.written.chunks.size)
        } finally {
            PyCompat.minor = minorBefore
            if (work == null) dir.deleteRecursively()
        }
    }

    /**
     * [source]'s element pack in [dir]: the file there when its SHA-256 is the published one, else downloaded from the
     * release (through a temporary file) and checked. A file whose hash differs is never used.
     */
    fun fetch(source: Source, dir: File, log: (String) -> Unit = {}): File {
        val file = File(dir, source.file)
        if (file.isFile && file.length() == source.bytes && sha256(file) == source.sha256) return file
        dir.mkdirs()
        val tmp = File(dir, ".${source.file}.part")
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
        } finally {
            conn.disconnect()
        }
        val got = sha256(tmp)
        if (tmp.length() != source.bytes || got != source.sha256) {
            tmp.delete()
            throw IOException("${source.url}: ${tmp.length()} bytes of SHA-256 $got, not the published ${source.bytes} bytes of ${source.sha256}")
        }
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) throw IOException("cannot move $tmp to $file")
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

    private val VALUED = setOf("--repo", "--packs", "--work")
}
