package app.atlas.render.live

import java.security.MessageDigest

/**
 * The engine's output version (plan-gpu G4): what a render makes (a GPU anchor, a cached frame) is current only for the
 * version it was made with, so anchors and caches of another version are stale.
 *
 * The version follows what the engine draws, not its source: [OUTPUTS] holds the md5 of a fixed set of renders
 * ([FIXED]), which `EngineVersionTest` recomputes. A change to the port that leaves those renders alone keeps the
 * version; a change that alters one fails the test, which prints the new [OUTPUTS] to paste here (the coordinator does
 * that, nobody else). [ID] is made from [OUTPUTS] and the anchor file's format version.
 *
 * The fixed renders are region views the engine snaps to exactly (no camera maths of the platform's own in them):
 * the Westerkerk home at its four moments (the living map, `amsterdam.py`), the Main board (`grachten_home`) and San
 * Gimignano's close view in the rain (`regions.py`, terrain and a tradition profile), so both engines and every moment
 * are covered. A port change that alters only renders outside the set would not change the version.
 */
object EngineVersion {
    /** The `.anchor` file's format version: `AnchorCodec.VERSION` (the packer's `AnchorsTest` keeps the two equal). */
    const val ANCHOR_FORMAT = 1

    /** The md5 of each fixed render ([md5] of its layers), by [Fixed.name], as `EngineVersionTest` last computed them. */
    val OUTPUTS: Map<String, String> = linkedMapOf(
        "westerkerk-sunny" to "0da67374591c9b70f97937c601cb7217",
        "westerkerk-grey" to "8f012acd29fc1820929ce57735d2a2f1",
        "westerkerk-wet" to "f78a8ba17ff9fd7b2459ff60d18417b7",
        "westerkerk-night" to "be235ad49cc3f2edfbdbf7a2827c8736",
        "grachten_home-sunny" to "fa485d91d43514edec3c7d241864f4c7",
        "sangimignano_close-wet" to "66ba418212f0e8852c1202519915618f",
    )

    /** The version anchors and caches carry: `a<format>-` and 16 hex digits of a hash of [OUTPUTS]. */
    val ID: String = idOf(OUTPUTS, ANCHOR_FORMAT)

    /**
     * One fixed render: [region]'s own camera [view] (a [ViewLadder] knot's name: the region's id for its overview or
     * the living map, `<region>_<key>` for its other views) on a [width] × [height] frame, at the named [moment].
     */
    class Fixed(val name: String, val region: String, val view: String, val moment: String, val width: Int, val height: Int) {
        /** The map's view of [live] (this render's region, opened) that snaps to [view] on this frame. */
        fun liveView(live: LiveRegion): LiveView {
            val k = live.ladder.knots.firstOrNull { it.name == view } ?: error("region ${live.id} has no view $view")
            return knotView(live.frame, k, width, height)
        }
    }

    /** The fixed renders, in [OUTPUTS]' order. */
    val FIXED: List<Fixed> = listOf(
        Fixed("westerkerk-sunny", "amsterdam", "amsterdam", "sunny", 412, 916),
        Fixed("westerkerk-grey", "amsterdam", "amsterdam", "grey", 412, 916),
        Fixed("westerkerk-wet", "amsterdam", "amsterdam", "wet", 412, 916),
        Fixed("westerkerk-night", "amsterdam", "amsterdam", "night", 412, 916),
        Fixed("grachten_home-sunny", "grachten", "grachten_home", "sunny", 412, 915),
        Fixed("sangimignano_close-wet", "sangimignano", "sangimignano_close", "wet", 412, 915),
    )

    /**
     * The map's view standing on knot [k] of a region in [frame], on a [width] × [height] frame, [dist] metres from the
     * target (the knot's own distance by default, which snaps to it).
     */
    fun knotView(frame: RegionFrame, k: ViewLadder.Knot, width: Int, height: Int, dist: Double = k.dist): LiveView {
        val lat = frame.lat(k.target[0], k.target[2])
        val lon = frame.lon(k.target[0], k.target[2])
        val cy = if (k.cy.isNaN()) height / 2.0 else k.cy
        return LiveView(lat, lon, LiveCamera.zoom(lat, dist, height.toDouble()), LiveCamera.bearing(k.yaw), 90.0 - k.pitch, width, height, cy)
    }

    /** The md5 (hex) of a render's layers: `g`, `o` and `o2` (absent as empty), each as UTF-8 followed by a NUL byte. */
    fun md5(r: LiveRender): String = md5(listOf(r.g, r.o, r.o2 ?: ""))

    /** The md5 (hex) of [layers], each as UTF-8 followed by a NUL byte. */
    fun md5(layers: List<String>): String {
        val md = MessageDigest.getInstance("MD5")
        for (l in layers) {
            md.update(l.toByteArray(Charsets.UTF_8))
            md.update(0)
        }
        return hex(md.digest())
    }

    /** The version made from [outputs] and anchor format [format]: the names and values in name order, hashed. */
    fun idOf(outputs: Map<String, String>, format: Int): String {
        val md = MessageDigest.getInstance("SHA-256")
        for ((k, v) in outputs.toSortedMap()) {
            md.update(k.toByteArray(Charsets.UTF_8)); md.update(0)
            md.update(v.toByteArray(Charsets.UTF_8)); md.update(0)
        }
        return "a$format-" + hex(md.digest()).substring(0, 16)
    }

    /** The outputs in [computed] that differ from [stored], by name: the stored value (null if none) and the computed one. */
    fun changes(computed: Map<String, String>, stored: Map<String, String> = OUTPUTS): Map<String, Pair<String?, String>> {
        val out = LinkedHashMap<String, Pair<String?, String>>()
        for ((k, v) in computed) if (stored[k] != v) out[k] = stored[k] to v
        return out
    }

    /** The [OUTPUTS] declaration with [computed] in place of the stored values it has, to paste into this file. */
    fun literal(computed: Map<String, String>, stored: Map<String, String> = OUTPUTS): String = buildString {
        append("    val OUTPUTS: Map<String, String> = linkedMapOf(\n")
        val names = LinkedHashSet<String>().apply { addAll(FIXED.map { it.name }); addAll(stored.keys); addAll(computed.keys) }
        for (n in names) append("        \"").append(n).append("\" to \"").append(computed[n] ?: stored[n] ?: "").append("\",\n")
        append("    )\n")
    }

    private fun hex(b: ByteArray): String {
        val s = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            s.append(HEX[v ushr 4]).append(HEX[v and 15])
        }
        return s.toString()
    }

    private const val HEX = "0123456789abcdef"
}
