package app.atlas.render.live

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.render.data.ByteSource
import app.atlas.render.data.OverpassClient
import app.atlas.render.data.PackFormatException
import app.atlas.render.data.PackManifest
import app.atlas.render.data.PackReader
import app.atlas.render.data.PackWriter
import app.atlas.render.data.RegionBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Opens a URL for reading: its body, and its length when known. The phone supplies OkHttp; [UrlGet] needs nothing. */
fun interface HttpGet {
    /** Throws [IOException] for anything but a 200. The caller closes the stream. */
    fun get(url: String): Pair<InputStream, Long>
}

/** [HttpGet] on the JDK's own HttpURLConnection (also on Android). */
class UrlGet(private val userAgent: String = "Atlas (https://github.com/atlas-maps-app/Atlas)") : HttpGet {
    override fun get(url: String): Pair<InputStream, Long> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 30_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", userAgent)
        val code = c.responseCode
        if (code != 200) {
            c.disconnect()
            throw IOException("$url answered $code")
        }
        return c.inputStream to c.contentLengthLong
    }
}

/**
 * The region packs on the phone and the ones published: the release manifests (`packs.json` of `packs-renders`, later
 * `packs-latest`), the `.atlas` files kept in [dir] (downloaded, verified by SHA-256, or installed from the app's own
 * assets), and packs built on the phone from Overpass for places no published pack covers.
 *
 * Beside a region's element pack the manifest may list its **render pack** (format 3, plan-vector D2 and Appendix A: the
 * tiles the core draws, made by `packer render-tiles`). The library keeps render packs in [renderDir], apart from the
 * element packs, and with `renders` on fetches a region's render pack with its element pack on the first visit
 * ([download]); [rendersAt] and [downloadRender] serve the core's own choice of packs. A region without a render pack
 * (none published, one this build's core does not read, or one made from another element pack) is normal: the map
 * draws there as it does today, from the element pack.
 *
 * Thread-safe; the network and the disk are only touched by [refresh], [download], [downloadRender], [install] and
 * [build].
 */
class PackLibrary(
    val dir: File,
    private val manifestUrls: List<String> = listOf(RENDERS),
    private val http: HttpGet = UrlGet(),
    /** Whether [download] fetches a region's render pack with its element pack, when one is published. */
    private val renders: Boolean = true,
    /** The render payload versions this build's core reads: [RENDER_PAYLOAD], the one `CP/tiles` decodes. */
    private val readsPayload: (Int) -> Boolean = { it == RENDER_PAYLOAD },
    /**
     * The render packs the app carries in its assets (`tiles/bundled.json`), id to SHA-256: a published render pack equal
     * to a bundled one is never fetched.
     */
    private val bundledRenders: Map<String, String> = emptyMap(),
) {
    /** A published pack: where it is and what it covers; [render] is its region's render pack, or [renderGap] says why it has none. */
    data class Entry(
        val id: String,
        val name: String,
        val bbox: BBox,
        val url: String,
        val bytes: Long,
        val sha256: String?,
        val release: String?,
        val render: Render? = null,
        val renderGap: String? = null,
    )

    /**
     * A published render pack: its file, size and SHA-256, the [payload] version of its tiles, the engine and style it was
     * made with, and the SHA-256 of the element pack it was made from ([source]).
     */
    data class Render(val name: String, val url: String, val bytes: Long, val sha256: String?, val payload: Int, val engine: String?, val style: String?, val source: String?)

    /** A pack on the phone. */
    data class Local(val id: String, val file: File, val bbox: BBox, val stamp: String, val built: Boolean)

    /** A render pack on the phone: its region, file, box, payload version and SHA-256. */
    data class LocalRender(val id: String, val file: File, val bbox: BBox, val payload: Int, val sha256: String)

    /** What a render pack's container says of it: its region, box and payload version. */
    class RenderHead(val id: String, val bbox: BBox, val payload: Int)

    private val lock = Any()
    private var entries: List<Entry> = emptyList()
    private var locals: List<Local>? = null
    private var localRenders: List<LocalRender>? = null
    private val renderFailures = HashMap<String, String>()

    /** Where the render packs are kept: `dir/render/<id>.atlas`. */
    val renderDir: File = File(dir, RENDER_DIR)

    init {
        dir.mkdirs()
        // the last manifest read stands until a new one comes: the map works offline
        File(dir, MANIFEST).takeIf { it.isFile }?.let { f -> runCatching { entries = parse(f.readText()) } }
    }

    /** The published packs as last read. */
    val published: List<Entry> get() = synchronized(lock) { entries }

    /** Reads the manifests again from the network and keeps them. Returns the packs they list; throws when none answered. */
    fun refresh(): List<Entry> {
        val all = LinkedHashMap<String, Entry>()
        var failure: IOException? = null
        var any = false
        for (u in manifestUrls) {
            try {
                val (input, _) = http.get(u)
                val text = input.use { it.readBytes().toString(Charsets.UTF_8) }
                for (e in parse(text)) all.putIfAbsent(e.id, e)
                any = true
            } catch (e: IOException) {
                failure = e
            }
        }
        if (!any) throw failure ?: IOException("no manifest")
        val list = all.values.toList()
        synchronized(lock) { entries = list }
        // keep the merged list in the manifest's own shape
        File(dir, MANIFEST).writeText(Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("format" to JsonPrimitive(1), "packs" to JsonArray(list.map { toJson(it) })))))
        return list
    }

    /** The packs on the phone. */
    fun local(): List<Local> = synchronized(lock) {
        locals ?: scan().also { locals = it }
    }

    /** The pack on the phone with [id], or null. */
    fun local(id: String): Local? = local().firstOrNull { it.id == id }

    /** The published pack with [id], or null. */
    fun entry(id: String): Entry? = published.firstOrNull { it.id == id }

    /** Packs whose box holds [p]: on the phone, and published but not yet here. */
    fun at(p: LatLon): Pair<List<Local>, List<Entry>> {
        val here = local().filter { p in it.bbox }
        val ids = here.map { it.id }.toSet()
        return here to published.filter { p in it.bbox && it.id !in ids }
    }

    /** The render packs on the phone that this build's core reads. */
    fun localRenders(): List<LocalRender> = synchronized(lock) {
        localRenders ?: scanRenders().also { localRenders = it }
    }

    /** The render pack on the phone for the region [id] that this build's core reads, or null. */
    fun localRender(id: String): LocalRender? = localRenders().firstOrNull { it.id == id }

    /**
     * [e]'s published render pack when the phone should fetch it: null when the region has none, when its payload is not
     * one this build's core reads, when the app carries the same pack, or when the phone has it already.
     */
    fun renderToFetch(e: Entry): Render? {
        val r = e.render ?: return null
        if (!readsPayload(r.payload)) return null
        if (r.sha256 != null && bundledRenders[e.id]?.equals(r.sha256, ignoreCase = true) == true) return null
        val here = localRender(e.id)
        if (here != null && (r.sha256 == null || here.sha256.equals(r.sha256, ignoreCase = true))) return null
        return r
    }

    /**
     * Render packs whose box holds [p]: on the phone (those this build's core reads), and published ones to fetch
     * ([renderToFetch]: not here yet, or newer than the copy here). Both are empty where no render pack reaches, and the
     * map draws there as today.
     */
    fun rendersAt(p: LatLon): Pair<List<LocalRender>, List<Entry>> {
        val here = localRenders().filter { p in it.bbox }
        return here to published.filter { p in it.bbox && renderToFetch(it) != null }
    }

    /** Why the last fetch of [id]'s render pack beside its element pack failed, or null. */
    fun renderFailure(id: String): String? = synchronized(lock) { renderFailures[id] }

    /**
     * Downloads [e] into the library, verifying its size and SHA-256 before it replaces anything. With `renders` on, the
     * region's render pack follows ([renderToFetch]); one that fails leaves the element pack in place, its reason in
     * [renderFailure]. [progress] gets bytes read and the total of both (−1 when unknown).
     */
    fun download(e: Entry, progress: (Long, Long) -> Unit = { _, _ -> }): Local {
        val render = if (renders) renderToFetch(e) else null
        val extra = render?.bytes?.takeIf { it > 0 } ?: 0L
        val tmp = File(dir, ".${e.id}.part")
        var whole = 0L
        fetch(e.id, e.url, e.bytes, e.sha256, tmp) { done, total ->
            if (total > 0) whole = total
            progress(done, if (total > 0) total + extra else -1)
        }
        val local = place(e.id, tmp, built = false)
        if (render != null) {
            val failure = try {
                downloadRender(e, render) { done, _ -> progress(whole + done, whole + extra) }
                null
            } catch (x: IOException) {
                x.message
            } catch (x: PackFormatException) {
                x.message
            }
            synchronized(lock) { if (failure == null) renderFailures.remove(e.id) else renderFailures[e.id] = "the render pack of ${e.id}: $failure" }
        }
        return local
    }

    /**
     * Downloads [render] (by default [e]'s published render pack) into [renderDir], verifying its size and SHA-256, and
     * that it is a render pack of [e]'s region that this build's core reads, before it replaces anything.
     */
    fun downloadRender(e: Entry, render: Render? = e.render, progress: (Long, Long) -> Unit = { _, _ -> }): LocalRender {
        val r = render ?: throw IOException("${e.id} has no render pack published${e.renderGap?.let { ": $it" } ?: ""}")
        if (!readsPayload(r.payload)) throw IOException("${e.id}'s render pack has payload ${r.payload}, which this build does not read")
        renderDir.mkdirs()
        val tmp = File.createTempFile(".${e.id}.", ".part", renderDir)
        val sha: String
        val head: RenderHead
        try {
            sha = fetch(e.id, r.url, r.bytes, r.sha256, tmp, progress)
            head = renderHead(tmp)
            if (head.id != e.id) throw PackFormatException("the file is the render pack of ${head.id}, not of ${e.id}")
            if (!readsPayload(head.payload)) throw PackFormatException("the file has payload ${head.payload}, which this build does not read")
        } catch (x: Exception) {
            tmp.delete()
            throw x
        }
        return synchronized(lock) {
            val file = File(renderDir, "${e.id}$EXT")
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            localRenders = null
            LocalRender(e.id, file, head.bbox, head.payload, sha)
        }
    }

    /** Installs a pack from [input] (the app's bundled one) unless the library already has [id]. */
    fun install(id: String, input: () -> InputStream): Local {
        local(id)?.let { return it }
        val tmp = File(dir, ".$id.part")
        input().use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 16) } }
        return place(id, tmp, built = false)
    }

    /**
     * Builds a pack for [box] on the phone from Overpass (the query of the renders' extracts) with a configuration
     * made from the box alone, and keeps it. The id is `here-<lat>-<lon>` of the box's centre, so one place is built once.
     */
    fun build(client: OverpassClient, box: BBox, name: String = "Here"): Local {
        val c = box.center
        val id = buildId(c)
        local(id)?.let { return it }
        val tmp = File(dir, ".$id.part")
        RegionBuilder.buildFromOverpass(client, id, name, box, tmp)
        return place(id, tmp, built = true)
    }

    /** Forgets the pack [id] (its file too, and its render pack's). */
    fun remove(id: String) = synchronized(lock) {
        File(dir, "$id$EXT").delete()
        File(dir, "$id$BUILT").delete()
        File(renderDir, "$id$EXT").delete()
        locals = null
        localRenders = null
    }

    /**
     * Reads [url] into [tmp], checking its length against [bytes] (when known) and its SHA-256 against [sha256] (when
     * given); a file that fails is deleted. Returns its SHA-256.
     */
    private fun fetch(id: String, url: String, bytes: Long, sha256: String?, tmp: File, progress: (Long, Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val (input, length) = http.get(url)
        val total = if (length > 0) length else bytes
        var read = 0L
        try {
            input.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        progress(read, total)
                    }
                }
            }
        } catch (x: IOException) {
            tmp.delete()
            throw x
        }
        if (bytes > 0 && read != bytes) {
            tmp.delete()
            throw IOException("$id: $read bytes came, the manifest says $bytes")
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha256 != null && !sha.equals(sha256, ignoreCase = true)) {
            tmp.delete()
            throw IOException("$id: the file's SHA-256 is $sha, the manifest says $sha256")
        }
        return sha
    }

    private fun place(id: String, tmp: File, built: Boolean): Local = synchronized(lock) {
        // open it once: a broken file never replaces a good one
        val bbox = PackReader.open(tmp).use { it.manifest.bbox }
        val file = File(dir, "$id$EXT")
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        if (built) File(dir, "$id$BUILT").writeText("1") else File(dir, "$id$BUILT").delete()
        locals = null
        local(id)!!.also { require(it.bbox == bbox) }
    }

    private fun scan(): List<Local> = dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
        runCatching {
            val m = PackReader.open(f).use { it.manifest }
            Local(m.id, f, m.bbox, "${f.length()}-${f.lastModified()}", File(dir, "${m.id}$BUILT").isFile)
        }.getOrNull()
    }

    private fun scanRenders(): List<LocalRender> = renderDir.listFiles { f -> f.isFile && f.name.endsWith(EXT) }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
        runCatching {
            val h = renderHead(f)
            if (f.name != "${h.id}$EXT" || !readsPayload(h.payload)) null else LocalRender(h.id, f, h.bbox, h.payload, sha256(f))
        }.getOrNull()
    }

    companion object {
        /** The renders' packs: the regions the boards were drawn from. */
        const val RENDERS = "https://github.com/atlas-maps-app/Atlas/releases/download/packs-renders/packs.json"
        const val EXT = ".atlas"
        private const val BUILT = ".built"
        private const val MANIFEST = "packs.json"

        /** The render packs' folder in the library. */
        const val RENDER_DIR = "render"

        /** A render pack's file name in a release: `<id>.render.atlas`, beside the element pack `<id>.atlas`. */
        const val RENDER_SUFFIX = ".render.atlas"

        /** A render pack's file kind in `packs.json`. */
        const val RENDER_KIND = "render"

        /** Why a region has no render pack, in its `packs.json` entry. */
        const val RENDER_GAP = "render_gap"

        /** The container format of render packs (rendertiles' `Format.VERSION`). */
        const val RENDER_VERSION = 3

        /** The layout a render pack's manifest names (rendertiles' `Format.LAYOUT`). */
        const val RENDER_LAYOUT = "render-tiles"

        /** The render tiles' payload version the core reads (rendertiles' `Format.PAYLOAD`; `CP/tiles` refuses others). */
        const val RENDER_PAYLOAD = 1

        /** The largest manifest a render pack's header may announce. */
        private const val MAX_MANIFEST = 4 shl 20

        fun buildId(c: LatLon): String = "here-%.3f-%.3f".format(java.util.Locale.ROOT, c.lat, c.lon).replace('.', 'p').replace("--", "-m")

        /**
         * The packs of a release manifest (`packs.json`: bbox as west, south, east, north; the `atlas` file of each; its
         * `render` file when one is published and was made from that `atlas` file, else `render_gap`'s reason).
         */
        fun parse(text: String): List<Entry> {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
            val packs = root["packs"] as? JsonArray ?: return emptyList()
            return packs.mapNotNull { p ->
                val o = p as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                val b = (o["bbox"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }?.takeIf { it.size == 4 } ?: return@mapNotNull null
                val files = (o["files"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                val file = files.firstOrNull { it.str("kind") == "atlas" } ?: return@mapNotNull null
                val url = file.str("url") ?: return@mapNotNull null
                val sha = file.str("sha256")
                var gap = o.str(RENDER_GAP)
                var render = files.firstOrNull { it.str("kind") == RENDER_KIND }?.let(::renderFile)
                if (render?.source != null && sha != null && !render.source.equals(sha, ignoreCase = true)) {
                    gap = "its render pack was made from another element pack"
                    render = null
                }
                Entry(id, o.str("name") ?: id, BBox(b[0], b[1], b[2], b[3]), url, file.str("bytes")?.toLongOrNull() ?: -1, sha, o.str("release"), render, if (render == null) gap else null)
            }
        }

        /** A `render` file of `packs.json`, or null when it is not a format-3 render pack with a URL and a payload version. */
        private fun renderFile(r: JsonObject): Render? {
            if (r.str("format") != PackManifest.FORMAT || r.str("version")?.toIntOrNull() != RENDER_VERSION || r.str("layout") != RENDER_LAYOUT) return null
            val url = r.str("url") ?: return null
            val payload = r.str("payload")?.toIntOrNull() ?: return null
            return Render(r.str("name") ?: url.substringAfterLast('/'), url, r.str("bytes")?.toLongOrNull() ?: -1, r.str("sha256"), payload, r.str("engine"), r.str("style"), r.str("source_sha256"))
        }

        /**
         * A render pack's identity from its container (D1's header, format 3) and manifest. Refuses another format, a
         * manifest that is not a render pack's, and one that does not fit the file.
         */
        fun renderHead(f: File): RenderHead = RandomAccessFile(f, "r").use { raf ->
            if (raf.length() < PackWriter.HEADER) throw PackFormatException("${f.name} is too short for a pack")
            val h = ByteArray(PackWriter.HEADER)
            raf.readFully(h)
            val s = ByteSource(h)
            if (String(s.bytes(8), Charsets.US_ASCII) != PackWriter.MAGIC) throw PackFormatException("${f.name} is not an Atlas pack")
            s.byte()
            val version = s.byte()
            if (version != RENDER_VERSION) throw PackFormatException("${f.name} is format $version, not a render pack (format $RENDER_VERSION)")
            s.byte()
            s.byte()
            val length = s.u32()
            if (length <= 0 || length > MAX_MANIFEST || PackWriter.HEADER + length > raf.length()) throw PackFormatException("${f.name}'s manifest does not fit the file")
            val m = ByteArray(length.toInt())
            raf.readFully(m)
            val manifest = PackManifest.parse(m.toString(Charsets.UTF_8))
            if (manifest.version != RENDER_VERSION || manifest.layout != RENDER_LAYOUT) throw PackFormatException("${f.name}'s manifest is not a render pack's")
            val payload = manifest.json.str("payload")?.toIntOrNull() ?: throw PackFormatException("${f.name}'s manifest has no payload version")
            RenderHead(manifest.id, manifest.bbox, payload)
        }

        private fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s ->
                val b = ByteArray(1 shl 16)
                while (true) {
                    val n = s.read(b)
                    if (n < 0) break
                    md.update(b, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

        private fun toJson(e: Entry): JsonObject {
            val files = ArrayList<JsonElement>()
            files += JsonObject(
                mapOf(
                    "kind" to JsonPrimitive("atlas"), "name" to JsonPrimitive("${e.id}$EXT"), "bytes" to JsonPrimitive(e.bytes),
                    "sha256" to JsonPrimitive(e.sha256), "url" to JsonPrimitive(e.url),
                ),
            )
            e.render?.let { r ->
                files += JsonObject(
                    mapOf(
                        "kind" to JsonPrimitive(RENDER_KIND), "name" to JsonPrimitive(r.name), "bytes" to JsonPrimitive(r.bytes),
                        "sha256" to JsonPrimitive(r.sha256), "url" to JsonPrimitive(r.url), "format" to JsonPrimitive(PackManifest.FORMAT),
                        "version" to JsonPrimitive(RENDER_VERSION), "layout" to JsonPrimitive(RENDER_LAYOUT), "payload" to JsonPrimitive(r.payload),
                        "engine" to JsonPrimitive(r.engine), "style" to JsonPrimitive(r.style), "source_sha256" to JsonPrimitive(r.source),
                    ),
                )
            }
            val fields = linkedMapOf<String, JsonElement>(
                "id" to JsonPrimitive(e.id), "name" to JsonPrimitive(e.name),
                "bbox" to JsonArray(listOf(e.bbox.west, e.bbox.south, e.bbox.east, e.bbox.north).map { JsonPrimitive(it) }),
                "release" to JsonPrimitive(e.release),
                "files" to JsonArray(files),
            )
            e.renderGap?.let { fields[RENDER_GAP] = JsonPrimitive(it) }
            return JsonObject(fields)
        }
    }
}
