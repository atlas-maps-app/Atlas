package app.atlas.render.live

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.render.data.OverpassClient
import app.atlas.render.data.PackReader
import app.atlas.render.data.RegionBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.io.InputStream
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
 * Thread-safe; the network and the disk are only touched by [refresh], [download], [install] and [build].
 */
class PackLibrary(
    val dir: File,
    private val manifestUrls: List<String> = listOf(RENDERS),
    private val http: HttpGet = UrlGet(),
) {
    /** A published pack: where it is and what it covers. */
    data class Entry(val id: String, val name: String, val bbox: BBox, val url: String, val bytes: Long, val sha256: String?, val release: String?)

    /** A pack on the phone. */
    data class Local(val id: String, val file: File, val bbox: BBox, val stamp: String, val built: Boolean)

    private val lock = Any()
    private var entries: List<Entry> = emptyList()
    private var locals: List<Local>? = null

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
        val texts = ArrayList<String>()
        for (u in manifestUrls) {
            try {
                val (input, _) = http.get(u)
                val text = input.use { it.readBytes().toString(Charsets.UTF_8) }
                for (e in parse(text)) all.putIfAbsent(e.id, e)
                texts += text
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

    /**
     * Downloads [e] into the library, verifying its size and SHA-256 before it replaces anything. [progress] gets bytes
     * read and the total (−1 when unknown).
     */
    fun download(e: Entry, progress: (Long, Long) -> Unit = { _, _ -> }): Local {
        val tmp = File(dir, ".${e.id}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val (input, length) = http.get(e.url)
        val total = if (length > 0) length else e.bytes
        var read = 0L
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
        if (e.bytes > 0 && read != e.bytes) {
            tmp.delete()
            throw IOException("${e.id}: ${read} bytes came, the manifest says ${e.bytes}")
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (e.sha256 != null && !sha.equals(e.sha256, ignoreCase = true)) {
            tmp.delete()
            throw IOException("${e.id}: the file's SHA-256 is $sha, the manifest says ${e.sha256}")
        }
        return place(e.id, tmp, built = false)
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

    /** Forgets the pack [id] (its file too). */
    fun remove(id: String) = synchronized(lock) {
        File(dir, "$id$EXT").delete()
        File(dir, "$id$BUILT").delete()
        locals = null
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

    companion object {
        /** The renders' packs: the regions the boards were drawn from. */
        const val RENDERS = "https://github.com/atlas-maps-app/Atlas/releases/download/packs-renders/packs.json"
        const val EXT = ".atlas"
        private const val BUILT = ".built"
        private const val MANIFEST = "packs.json"

        fun buildId(c: LatLon): String = "here-%.3f-%.3f".format(java.util.Locale.ROOT, c.lat, c.lon).replace('.', 'p').replace("--", "-m")

        /** The packs of a release manifest (`packs.json`: bbox as west, south, east, north; the `atlas` file of each). */
        fun parse(text: String): List<Entry> {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
            val packs = root["packs"] as? JsonArray ?: return emptyList()
            return packs.mapNotNull { p ->
                val o = p as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val b = (o["bbox"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }?.takeIf { it.size == 4 } ?: return@mapNotNull null
                val file = (o["files"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.firstOrNull { (it["kind"] as? JsonPrimitive)?.content == "atlas" } ?: return@mapNotNull null
                val url = (file["url"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                Entry(
                    id, (o["name"] as? JsonPrimitive)?.content ?: id, BBox(b[0], b[1], b[2], b[3]), url,
                    (file["bytes"] as? JsonPrimitive)?.content?.toLongOrNull() ?: -1, (file["sha256"] as? JsonPrimitive)?.content,
                    (o["release"] as? JsonPrimitive)?.content,
                )
            }
        }

        private fun toJson(e: Entry): JsonObject = JsonObject(
            mapOf(
                "id" to JsonPrimitive(e.id), "name" to JsonPrimitive(e.name),
                "bbox" to JsonArray(listOf(e.bbox.west, e.bbox.south, e.bbox.east, e.bbox.north).map { JsonPrimitive(it) }),
                "release" to JsonPrimitive(e.release),
                "files" to JsonArray(listOf(JsonObject(mapOf(
                    "kind" to JsonPrimitive("atlas"), "name" to JsonPrimitive("${e.id}$EXT"), "bytes" to JsonPrimitive(e.bytes),
                    "sha256" to JsonPrimitive(e.sha256), "url" to JsonPrimitive(e.url),
                )))),
            ),
        )
    }
}
