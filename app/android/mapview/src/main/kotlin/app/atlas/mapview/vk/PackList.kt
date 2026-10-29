package app.atlas.mapview.vk

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.mapcore.MapCore
import app.atlas.mapcore.state.CameraWriter
import app.atlas.render.live.PackLibrary
import app.atlas.render.live.RegionFrame
import app.atlas.render.py.PyJson
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId

/**
 * The packs the core draws from (plan-vector D3 "Packs on the phone", K11; ENGINE.md step 3, one pack per country): the
 * windows on the phone ([Windows]: an L14 place each, taken from its country's tiles or built on the phone from its
 * data), which the core takes into their frame cells' packs. Nothing else draws the map: the APK carries none, and no
 * region's render pack is fetched or listed, so zooming and panning never switch the data, the cut or the look.
 *
 * Per camera it names the pack the frame draws ([choose]): the window whose box holds the target, which the core draws
 * with its frame cell's pack (D3: one profile a frame). The transitional rule asks it whether the core has tiles at a
 * place ([covers]). Thread-safe: the list is replaced whole ([load], on a background thread).
 */
class PackList(
    /** The folder of the windows on the phone ([Windows]), or null for none. */
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
        /** MapCore.PackRef's kind: WINDOW. */
        val kind: Int,
        val box: BBox,
        /** Whose rules draw it, as the camera state numbers them (CameraWriter.PROFILE_*). */
        val profile: Int,
        /** The ground frame its tiles are drawn in, which the overlays and gestures take while it is drawn. */
        val frame: RegionFrame,
        /** Its region's time zone: the live moment's clock. */
        val zone: ZoneId,
        /** Its region's views by name (`home` for `grachten_home`, "" for the region's own), as its manifest gives them. */
        val views: Map<String, ViewCamera>,
        val file: File,
        /** Its file's time and size as listed: a file written again at the same path, a window's, is new bytes. */
        val time: Long = file.lastModified(),
        val size: Long = file.length(),
    ) {
        override fun toString() = "Pack($id #$number)"
    }

    /**
     * One of a region's views as the renders frame it (its manifest's `config.views`): the target ([x], [z] metres east
     * and south of the pack's origin, in its [frame][Pack.frame]), the [yaw] and the renders' [pitch] (degrees from the
     * horizon), the eye's [distance] (metres) and the principal point's height ([cy], dp; NaN for the frame's middle).
     */
    class ViewCamera(val x: Double, val z: Double, val yaw: Double, val pitch: Double, val distance: Double, val cy: Double)

    /**
     * What the list needs of a render pack's manifest, and its views; [chunks], how many tiles it holds, and [version],
     * `<engine version> <style version>`, where the manifest says (null where it does not).
     */
    internal class Head(
        val box: BBox,
        val origin: LatLon,
        val zone: String?,
        val profile: String?,
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

    /** The number a pack's id keeps for the process. */
    @Synchronized
    private fun numberOf(id: String): Long = numbers.getOrPut(id) { numbers.size + 1L }

    /** Reads the packs again (off the main thread: every pack's manifest is read). Returns the list. */
    @Synchronized
    fun load(): List<Pack> {
        val out = ArrayList<Pack>()
        // the windows on the phone: each its own pack, which the core takes into its frame cell's; not one that holds
        // nothing to draw (the open sea far from a coast, the middle of a field), where the core would have no tile, so
        // the place is today's map's (Windows does not fetch such a window again for a week); nor one of another
        // version than a window built now (another stage P or style: an earlier APK built it), so a frame cell's
        // windows are of one version
        val version = windowVersion()
        windows()?.listFiles()?.sortedBy { it.name }?.forEach { file ->
            val w = Windows.windowOf(file) ?: return@forEach
            val head = runCatching { file.inputStream().use { readHead(it) } }.getOrNull() ?: return@forEach
            if (head.chunks == 0 || (version != null && head.version != version)) return@forEach
            out += packOf(w.id, MapCore.PackRef.WINDOW, head, file)
        }
        if (out.map { listOf(it.id, it.file, it.time, it.size) } != packs.map { listOf(it.id, it.file, it.time, it.size) }) {
            packs = out
            revision++
        }
        loaded = true
        return out
    }

    private fun packOf(id: String, kind: Int, h: Head, file: File): Pack {
        val profile = profileOf(h.profile)
        return Pack(
            id, numberOf(id), kind, h.box, profile,
            if (profile == CameraWriter.PROFILE_LIVING) RegionFrame.living() else RegionFrame.region(h.origin),
            runCatching { ZoneId.of(h.zone ?: AMSTERDAM) }.getOrDefault(ZoneId.of(AMSTERDAM)),
            h.views, file,
        )
    }

    /**
     * The core's list of the packs, each with its file's path, time and size as listed: the core opens a file written
     * again at the same path anew.
     */
    fun refs(): List<MapCore.PackRef> =
        packs.map { p -> MapCore.PackRef(p.id, p.number, p.kind, path = p.file.path, offset = p.time, length = p.size) }

    /**
     * The pack the frame draws at a camera whose target is [lat], [lon]: the smallest whose box holds the target, the
     * first listed on a tie (a window's: the core draws it with its frame cell's pack); null where none holds it. No
     * pack is chosen by the camera's distance: zooming hands nothing over.
     */
    fun choose(lat: Double, lon: Double): Pack? {
        var best: Pack? = null
        for (p in packs) {
            val b = p.box
            if (lat < b.south || lat > b.north || lon < b.west || lon > b.east) continue
            if (best == null || area(b) < area(best.box)) best = p
        }
        return best
    }

    /**
     * Whether the core has tiles for a camera on [lat], [lon], [distance] metres from it (the transitional rule's
     * place): a pack holds the target, and the target is within the cover's reach ([CORE_REACH]).
     */
    fun covers(lat: Double, lon: Double, distance: Double): Boolean = distance <= CORE_REACH && choose(lat, lon) != null

    /** Whether a pack's box meets [view] (the ground a camera sees): the core has something to show there. */
    fun meets(view: BBox): Boolean = packs.any { p ->
        val b = p.box
        b.west <= view.east && view.west <= b.east && b.south <= view.north && view.south <= b.north
    }

    companion object {
        /** How far from the eye the core's cover reaches its target, metres: three quarters of the world's far plane. */
        const val CORE_REACH = 9_000.0

        private const val AMSTERDAM = "Europe/Amsterdam"

        /** The container's header: `ATLASPCK`, a zero byte, the version, two zero bytes, the manifest's and directory's lengths. */
        private const val HEADER = 20
        private const val MAX_MANIFEST = 4 shl 20

        /**
         * What the list needs of a render pack (format 3), from the start of its bytes: D1's container header, then its
         * JSON manifest ([PackLibrary.renderHead] reads the same from a file): the box, the origin, the time zone, the
         * profile and its views, its tiles' count and its version.
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
            val config = ((m["config"] as? Map<*, *>)?.get("views") as? Map<*, *>).orEmpty()
            val cameras = LinkedHashMap<String, ViewCamera>()
            for ((k, v) in config) {
                val name = k as? String ?: continue
                cameraOf(v)?.let { cameras[name] = it }
            }
            val engine = m["engine_version"] as? String
            val style = m["style_version"] as? String
            val version = if (engine != null && style != null) "$engine $style" else null
            return Head(box, origin, m["tz"] as? String, m["profile"] as? String, cameras, (m["chunks"] as? Number)?.toInt(), version)
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

        private fun area(b: BBox): Double = b.width.coerceAtLeast(0.0) * b.height.coerceAtLeast(0.0)
    }
}
