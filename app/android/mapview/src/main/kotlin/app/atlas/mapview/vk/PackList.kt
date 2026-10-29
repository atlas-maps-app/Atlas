package app.atlas.mapview.vk

import android.util.Log
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.mapcore.MapCore
import app.atlas.mapcore.state.CameraWriter
import app.atlas.mapview.EngineFrames
import app.atlas.render.live.PackLibrary
import app.atlas.render.live.RegionFrame
import app.atlas.render.live.ViewLadder
import app.atlas.render.py.PyJson
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

/**
 * The packs the core draws from (plan-vector D3 "Packs on the phone", K11): the render packs the APK carries
 * (`tiles/bundled.json`, stored uncompressed, so the core maps each where it lies in the APK), the render packs the
 * library keeps beside the element packs ([PackLibrary.localRenders]), the windows the phone built ([Windows]), and,
 * online, a region's published render pack fetched on the first visit to its box ([fetchAt]: K20a's
 * [PackLibrary.rendersAt] and [PackLibrary.downloadRender]).
 *
 * Per camera it names the pack the frame draws ([choose]), as the live map has always chosen its region
 * ([app.atlas.render.live.LiveRegions]): of the packs whose box holds the target, the one whose own views best fit the
 * camera's distance (|ln(distance / a view's)|), the smaller box on a tie; the frame takes that pack's profile (D3: one
 * profile a frame). The transitional rule asks it whether the core has tiles at a place ([covers]). Thread-safe: the
 * list is replaced whole ([load], on a background thread).
 */
class PackList(
    /** The APK's bundled render packs; null for none (the JVM's tests). */
    private val bundled: BundledSource?,
    /** The packs on the phone and the published ones; null for none. */
    private val library: PackLibrary?,
    /** Whether the map may use the network now (a first visit's render pack). */
    private val online: () -> Boolean = { false },
    /** Where downloads run. */
    private val worker: Executor? = null,
    /** The folder of the windows the phone built (ENGINE.md step 3: [Windows]), or null for none. */
    private val windows: () -> File? = { null },
    /**
     * The version of a window built now ([MapCore.Window.version]), or null where it is not known (the JVM's tests): a
     * window of another version (an earlier APK built it) is not listed.
     */
    private val windowVersion: () -> String? = { null },
) {
    /** One pack the core can draw. */
    class Pack internal constructor(
        val id: String,
        /** Its number for this process: the camera state names the pack drawn by it (MapCore.PackRef.number). */
        val number: Long,
        /** MapCore.PackRef's kind: BUNDLED or RENDER. */
        val kind: Int,
        val box: BBox,
        /** Whose rules draw it, as the camera state numbers them (CameraWriter.PROFILE_*). */
        val profile: Int,
        /** The ground frame its tiles are drawn in, which the overlays and gestures take while it is drawn. */
        val frame: RegionFrame,
        /** Its region's time zone: the live moment's clock. */
        val zone: ZoneId,
        /** Its views' eye distances (metres), for the choice by distance. */
        val distances: DoubleArray,
        /** Its region's views by name (`home` for `grachten_home`, "" for the region's own), as its manifest gives them. */
        val views: Map<String, ViewCamera>,
        /** A downloaded pack's file, or a bundled pack's asset name. */
        val file: File?,
        val asset: String?,
        /** Its file's time and size as listed (0 for an asset): a file built again at the same path, a window's, is new bytes. */
        val time: Long = file?.lastModified() ?: 0L,
        val size: Long = file?.length() ?: 0L,
    ) {
        override fun toString() = "Pack($id #$number)"
    }

    /**
     * One of a region's views as the renders frame it (its manifest's `config.views`): the target ([x], [z] metres east
     * and south of the pack's origin, in its [frame][Pack.frame]), the [yaw] and the renders' [pitch] (degrees from the
     * horizon), the eye's [distance] (metres) and the principal point's height ([cy], dp; NaN for the frame's middle).
     */
    class ViewCamera(val x: Double, val z: Double, val yaw: Double, val pitch: Double, val distance: Double, val cy: Double)

    /** The APK's bundled render packs: its index and each pack's bytes. */
    interface BundledSource {
        /** The asset `tiles/<name>`, or null when it is not there. */
        fun open(name: String): InputStream?

        /** The asset `tiles/<name>` as an open descriptor's range (it must be stored uncompressed), or null. */
        fun range(name: String): Range?
    }

    /** A range of an open file (an APK asset), closed once the core has its own descriptor. */
    class Range(val fd: Int, val offset: Long, val length: Long, private val closer: AutoCloseable) : AutoCloseable {
        override fun close() = closer.close()
    }

    /**
     * What the choice needs of a render pack's manifest, and its views; [chunks], how many tiles it holds, and
     * [version], `<engine version> <style version>`, where the manifest says (null where it does not).
     */
    internal class Head(
        val box: BBox,
        val origin: LatLon,
        val zone: String?,
        val profile: String?,
        val distances: DoubleArray,
        val views: Map<String, ViewCamera> = emptyMap(),
        val chunks: Int? = null,
        val version: String? = null,
    )

    @Volatile
    var packs: List<Pack> = emptyList()
        private set

    /** Bumped each time [load] changes the list: the core is handed the packs again. */
    @Volatile
    var revision: Int = 0
        private set

    /** Whether the packs have been read once ([load]): until then nothing is known of where the core has tiles. */
    @Volatile
    var loaded: Boolean = false
        private set

    private val numbers = HashMap<String, Long>()
    private val fetching = ConcurrentHashMap<String, Long>()

    /** The number a pack's id keeps for the process. */
    @Synchronized
    private fun numberOf(id: String): Long = numbers.getOrPut(id) { numbers.size + 1L }

    /**
     * Reads the packs again (off the main thread: the bundled index and every pack's manifest are read): the bundled
     * ones first, then those downloaded (a downloaded pack of a bundled id replaces it: it is newer). Returns the list.
     */
    @Synchronized
    fun load(): List<Pack> {
        val out = LinkedHashMap<String, Pack>()
        bundled?.let { src ->
            for (b in bundledIndex(src)) {
                val head = runCatching { src.open(b.file)?.use { readHead(it) } }.getOrNull() ?: continue
                out[b.id] = packOf(b.id, MapCore.PackRef.BUNDLED, head, null, b.file)
            }
        }
        library?.let { lib ->
            for (r in runCatching { lib.localRenders() }.getOrDefault(emptyList())) {
                val head = runCatching { r.file.inputStream().use { readHead(it) } }.getOrNull() ?: continue
                out.remove(r.id)
                out[r.id] = packOf(r.id, MapCore.PackRef.RENDER, head, r.file, null)
            }
        }
        // the windows the phone built: each its own pack, which the core takes into its frame cell's; not one that holds
        // nothing to draw (the open sea far from a coast, the middle of a field), where the core would have no tile, so
        // the place is today's map's (Windows does not fetch such a window again for a week); nor one of another
        // version than a window built now (another stage P or style: an earlier APK built it), so a frame cell's
        // windows are of one version
        val version = windowVersion()
        windows()?.listFiles()?.sortedBy { it.name }?.forEach { file ->
            val w = Windows.windowOf(file) ?: return@forEach
            val head = runCatching { file.inputStream().use { readHead(it) } }.getOrNull() ?: return@forEach
            if (head.chunks == 0 || (version != null && head.version != version)) return@forEach
            out[w.id] = packOf(w.id, MapCore.PackRef.WINDOW, head, file, null)
        }
        val list = out.values.toList()
        if (list.map { listOf(it.id, it.file, it.asset, it.time, it.size) } != packs.map { listOf(it.id, it.file, it.asset, it.time, it.size) }) {
            packs = list
            revision++
        }
        loaded = true
        return list
    }

    private fun packOf(id: String, kind: Int, h: Head, file: File?, asset: String?): Pack {
        val profile = profileOf(h.profile)
        return Pack(
            id, numberOf(id), kind, h.box, profile,
            if (profile == CameraWriter.PROFILE_LIVING) RegionFrame.living() else RegionFrame.region(h.origin),
            runCatching { ZoneId.of(h.zone ?: AMSTERDAM) }.getOrDefault(ZoneId.of(AMSTERDAM)),
            h.distances, h.views, file, asset,
        )
    }

    /**
     * The core's list of the packs, each with its file or its APK range; the ranges are the caller's to close once the
     * core has them ([MapCore.packs] keeps its own descriptors).
     */
    fun refs(): Pair<List<MapCore.PackRef>, List<AutoCloseable>> {
        val refs = ArrayList<MapCore.PackRef>()
        val open = ArrayList<AutoCloseable>()
        for (p in packs) {
            val file = p.file
            val asset = p.asset
            if (file != null) {
                // a file's time and size as listed: the core opens a file built again at the same path anew
                refs += MapCore.PackRef(p.id, p.number, p.kind, path = file.path, offset = p.time, length = p.size)
            } else if (asset != null) {
                val r = runCatching { bundled?.range(asset) }.getOrNull() ?: continue
                open += r
                refs += MapCore.PackRef(p.id, p.number, p.kind, fd = r.fd, offset = r.offset, length = r.length)
            }
        }
        return refs to open
    }

    /**
     * The pack the frame draws at a camera on [lat], [lon], [distance] metres from it: of the packs whose box holds
     * the target, the one whose views best fit the distance, the smaller box on a tie; null where none holds it.
     *
     * The pack drawn now ([held], its id) stays while its box holds the target and no other fits the distance better
     * by more than [holdFit]. Each switch has the core build the new pack's tiles and show its looks, so without the
     * margin a pinch about the distance where two packs fit alike (the canal ring's views and the windows' around
     * 830 m) flipped the map between them, and between their pictures (the user, 28 October: "it bounces back and
     * forth").
     */
    fun choose(lat: Double, lon: Double, distance: Double, held: String? = null, holdFit: Double = HOLD_FIT): Pack? {
        var best: Pack? = null
        var bestFit = Double.MAX_VALUE
        var kept: Pack? = null
        var keptFit = Double.MAX_VALUE
        for (p in packs) {
            val b = p.box
            if (lat < b.south || lat > b.north || lon < b.west || lon > b.east) continue
            val fit = fit(p.distances, distance)
            if (held != null && p.id == held) {
                kept = p
                keptFit = fit
            }
            val tie = abs(fit - bestFit) <= FIT_EPSILON
            if (best == null || (!tie && fit < bestFit) || (tie && area(b) < area(best.box))) {
                best = p
                bestFit = min(fit, bestFit)
            }
        }
        return if (kept != null && keptFit <= bestFit + holdFit) kept else best
    }

    /**
     * Whether the core has tiles for a camera on [lat], [lon], [distance] metres from it (the transitional rule's
     * place): a pack holds the target, and the target is within the cover's reach ([CORE_REACH]).
     */
    fun covers(lat: Double, lon: Double, distance: Double): Boolean = distance <= CORE_REACH && choose(lat, lon, distance) != null

    /** Whether a pack's box meets [view] (the ground a camera sees): the core has something to show there. */
    fun meets(view: BBox): Boolean = packs.any { p ->
        val b = p.box
        b.west <= view.east && view.west <= b.east && b.south <= view.north && view.south <= b.north
    }

    /**
     * Online, a published render pack for [at] that the phone does not have yet is downloaded once in the background
     * (K20a's seam), and [onFetched] is told when it is on the phone and listed. A failure waits [RETRY_MS] before the
     * next try.
     */
    fun fetchAt(at: LatLon, onFetched: () -> Unit) {
        val lib = library ?: return
        val run = worker ?: return
        if (!online()) return
        val wanted = runCatching { lib.rendersAt(at).second }.getOrDefault(emptyList())
        for (e in wanted) {
            val now = System.currentTimeMillis()
            val last = fetching[e.id]
            if (last != null && (last == IN_PROGRESS || now - last < RETRY_MS)) continue
            fetching[e.id] = IN_PROGRESS
            run.execute {
                try {
                    lib.downloadRender(e)
                    fetching.remove(e.id)
                    load()
                    onFetched()
                } catch (x: Exception) {
                    fetching[e.id] = System.currentTimeMillis()
                    Log.w(EngineFrames.TAG, "vk packs: the render pack of ${e.id} did not come: ${x.message}")
                }
            }
        }
    }

    /** One entry of `tiles/bundled.json`. */
    internal class BundledEntry(val id: String, val file: String)

    companion object {
        /** The bundled render packs' index (written by `packer render-tiles --bundled`). */
        const val INDEX = "bundled.json"

        /** How far from the eye the core's cover reaches its target, metres: three quarters of the world's far plane. */
        const val CORE_REACH = 9_000.0

        /**
         * How much better another pack must fit the distance than the pack drawn now ([choose]'s |ln| of the
         * distances) to take its place: about a third, so the canal ring gives way to the windows at about 710 m and
         * takes the map again at about 960 m, where without it both switched at 830 m.
         */
        const val HOLD_FIT = 0.3

        private const val AMSTERDAM = "Europe/Amsterdam"
        private const val FIT_EPSILON = 1e-9
        private const val RETRY_MS = 60_000L
        private const val IN_PROGRESS = -1L

        /** The container's header: `ATLASPCK`, a zero byte, the version, two zero bytes, the manifest's and directory's lengths. */
        private const val HEADER = 20
        private const val MAX_MANIFEST = 4 shl 20

        internal fun bundledIndex(src: BundledSource): List<BundledEntry> {
            val text = runCatching { src.open(INDEX)?.use { it.readBytes().decodeToString() } }.getOrNull() ?: return emptyList()
            val root = runCatching { PyJson.loads(text) as? Map<*, *> }.getOrNull() ?: return emptyList()
            val list = root["packs"] as? List<*> ?: return emptyList()
            return list.mapNotNull { p ->
                val o = p as? Map<*, *> ?: return@mapNotNull null
                val id = o["id"] as? String ?: return@mapNotNull null
                BundledEntry(id, o["file"] as? String ?: "$id.atlas")
            }
        }

        /**
         * What the choice needs of a render pack (format 3), from the start of its bytes: D1's container header, then
         * its JSON manifest ([PackLibrary.renderHead] reads the same from a file): the box, the origin, the time zone,
         * the profile and its views' distances, its tiles' count and its version.
         */
        internal fun readHead(input: InputStream): Head {
            val d = DataInputStream(input)
            val head = ByteArray(HEADER)
            d.readFully(head)
            if (String(head, 0, 8, Charsets.US_ASCII) != "ATLASPCK") throw IOException("not an Atlas pack")
            if (head[9].toInt() != PackLibrary.RENDER_VERSION) throw IOException("format ${head[9]}, not a render pack")
            val length = ((head[12].toInt() and 0xFF) shl 24) or ((head[13].toInt() and 0xFF) shl 16) or
                ((head[14].toInt() and 0xFF) shl 8) or (head[15].toInt() and 0xFF)
            if (length <= 0 || length > MAX_MANIFEST) throw IOException("a manifest of $length bytes")
            val bytes = ByteArray(length)
            d.readFully(bytes)
            val m = PyJson.loads(bytes.decodeToString()) as? Map<*, *> ?: throw IOException("the manifest is not an object")
            if (m["layout"] != PackLibrary.RENDER_LAYOUT) throw IOException("the manifest is not a render pack's")
            val b = m["bbox"] as? Map<*, *> ?: throw IOException("the manifest has no bbox")
            val box = BBox(num(b["west"]), num(b["south"]), num(b["east"]), num(b["north"]))
            val o = m["origin"] as? Map<*, *>
            val origin = if (o != null) LatLon(num(o["lat"]), num(o["lon"])) else box.center
            val profile = m["profile"] as? String
            val config = ((m["config"] as? Map<*, *>)?.get("views") as? Map<*, *>).orEmpty()
            val views = config.values.mapNotNull { v -> ((v as? Map<*, *>)?.get("dist") as? Number)?.toDouble() }
            val distances = if (profile != "living" && views.isNotEmpty()) views.toDoubleArray()
            else doubleArrayOf((ViewLadder.livingView()["dist"] as Number).toDouble())
            val cameras = LinkedHashMap<String, ViewCamera>()
            for ((k, v) in config) {
                val name = k as? String ?: continue
                cameraOf(v)?.let { cameras[name] = it }
            }
            val engine = m["engine_version"] as? String
            val style = m["style_version"] as? String
            val version = if (engine != null && style != null) "$engine $style" else null
            return Head(box, origin, m["tz"] as? String, profile, distances, cameras, (m["chunks"] as? Number)?.toInt(), version)
        }

        private fun num(v: Any?): Double = (v as? Number)?.toDouble() ?: throw IOException("a number is missing in the manifest")

        /** A view of a manifest's `config.views` as [ViewCamera], or null where it lacks a target, yaw, pitch or distance. */
        private fun cameraOf(v: Any?): ViewCamera? {
            val c = v as? Map<*, *> ?: return null
            val t = (c["target"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: return null } ?: return null
            if (t.size != 3) return null
            val yaw = (c["yaw"] as? Number)?.toDouble() ?: return null
            val pitch = (c["pitch"] as? Number)?.toDouble() ?: return null
            val dist = (c["dist"] as? Number)?.toDouble() ?: return null
            return ViewCamera(t[0], t[2], yaw, pitch, dist, (c["cy"] as? Number)?.toDouble() ?: Double.NaN)
        }

        /** The camera state's number for a manifest's profile label (`living`, `region`, …). */
        internal fun profileOf(label: String?): Int = when (label) {
            "living" -> CameraWriter.PROFILE_LIVING
            "williamsburg" -> CameraWriter.PROFILE_WILLIAMSBURG
            "world-base" -> CameraWriter.PROFILE_WORLD_BASE
            else -> CameraWriter.PROFILE_REGION
        }

        /** |ln(distance / the nearest view's)|: how well a pack's views fit a camera's distance (LiveRegions' fit). */
        internal fun fit(distances: DoubleArray, distance: Double): Double {
            var best = Double.MAX_VALUE
            val d = distance.coerceAtLeast(1.0)
            for (v in distances) best = min(best, abs(ln(d / v)))
            return best
        }

        private fun area(b: BBox): Double = b.width.coerceAtLeast(0.0) * b.height.coerceAtLeast(0.0)
    }
}
