package app.atlas.likeness

import app.atlas.geo.BBox
import app.atlas.render.live.LiveCamera
import app.atlas.render.live.LiveView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Random
import kotlin.math.ceil

/** A frame: [wPx] × [hPx] device pixels at [density] device pixels per CSS px. */
data class FrameSize(val name: String, val wPx: Int, val hPx: Int, val density: Double) {
    /** The engine frame's width, CSS px: ⌈W / d⌉ with a 10⁻⁶ tolerance (plan-gpu D3). */
    val frameW: Int get() = ceil(wPx / density - 1e-6).toInt()

    /** The engine frame's height, CSS px: ⌈H / d⌉ with a 10⁻⁶ tolerance. */
    val frameH: Int get() = ceil(hPx / density - 1e-6).toInt()

    companion object {
        /** A panel of [w] × [h] device pixels at the map's `BoardScale` density: the map is always 412 dp wide. */
        fun board(name: String, w: Int, h: Int): FrameSize = FrameSize(name, w, h, w / 412.0)

        /** D16's sizes: S1 1080 × 2400, S2 1080 × 2340, S3 1440 × 3120, S4 1344 × 2992, each at its `BoardScale` density. */
        val NAMED: Map<String, FrameSize> = listOf(board("S1", 1080, 2400), board("S2", 1080, 2340), board("S3", 1440, 3120), board("S4", 1344, 2992))
            .associateBy { it.name }

        /**
         * A phone from `<model>:<w>x<h>` or `<model>:<w>x<h>@<density>` (the density defaults to `BoardScale`'s), as the
         * VK facts paste gives its screen.
         */
        fun phone(spec: String): FrameSize {
            val colon = spec.lastIndexOf(':')
            require(colon > 0) { "a phone is <model>:<w>x<h>[@<density>], not '$spec'" }
            val model = spec.substring(0, colon).trim()
            val rest = spec.substring(colon + 1).trim()
            val at = rest.indexOf('@')
            val dims = (if (at >= 0) rest.substring(0, at) else rest).split('x', 'X', '×').map { it.trim().toInt() }
            require(dims.size == 2 && dims[0] in 200..8000 && dims[1] in 200..8000) { "bad phone size in '$spec'" }
            val d = if (at >= 0) rest.substring(at + 1).trim().toDouble() else dims[0] / 412.0
            require(d in 0.5..8.0) { "bad density in '$spec'" }
            return FrameSize(model, dims[0], dims[1], d)
        }
    }
}

/** How a case is drawn: filters off on both sides (structural), everything on (full), or one effect on its probe. */
enum class Mode { STRUCTURAL, FULL, EFFECT }

/** Where a case looks. */
sealed interface CameraSpec {
    /** A view of the pack: `home` (the living map's own camera) or one of the region's views by its knot name. */
    data class View(val name: String) : CameraSpec

    /** D16's sweep: camera [k] of [of], drawn with [seed] within the pack's box. */
    data class Sweep(val k: Int, val of: Int, val seed: Long) : CameraSpec

    /**
     * Step [step] of [steps] of a scripted path: `zoom` (150 m to 8 km), `orbit` (360°) or `tilt` (map pitch 0 to 70°).
     * A case file names the path without a step; it stands for all of them.
     */
    data class Path(val path: String, val step: Int, val steps: Int) : CameraSpec

    /** A camera given outright. */
    data class Explicit(val lat: Double, val lon: Double, val distance: Double, val bearing: Double, val pitch: Double, val cyDp: Double?) : CameraSpec
}

/** Which light: one of the engine's named moments, or an instant lit by a forecast. */
sealed interface MomentSpec {
    data class Named(val name: String) : MomentSpec

    /**
     * The live moment at [instant] (ISO, with its offset or `Z`; without one, the region's local time), lit by
     * [forecast]: `recorded` for the engine's own record of Open-Meteo's answer for the Westerkerk (30 September to
     * 2 October 2026, `data/wx/ams.json`), or null for the overcast stand-in.
     */
    data class Live(val instant: String, val forecast: String?) : MomentSpec
}

/** An effect alone on a probe scene, built from [from]'s own filter definitions. */
data class ProbeSpec(val effect: String, val from: String, val haze: Double?, val vignette: Boolean)

/** One likeness case, as a case file writes it (plan-vector Appendix B), its size resolved. */
class Case(
    val name: String,
    /** The case file it came from. */
    val file: String,
    val pack: String?,
    val packSha256: String?,
    val camera: CameraSpec?,
    val moment: MomentSpec?,
    val probe: ProbeSpec?,
    val size: FrameSize,
    val mode: Mode,
    /** Engine mode: every per-camera rule of the renders, the radius cut included (the port's own render). */
    val engine: Boolean,
    val gates: List<String>,
    val tier: String,
    /** One of the draws VK check makes on a phone, at the phone's own size. */
    val vkcheck: Boolean,
    /** The feature ladder is measured on this case. */
    val ladder: Boolean,
    /** For a full-mode case: its structural twin, which FULL measures against. */
    val structural: String?,
    /** A phone this case was expanded for, or null. */
    val phone: String?,
    /** The scripted path this case is a step of (D16's continuity), or null. */
    val path: String? = null,
) {
    /** The gate class of the case's pack: the Westerkerk's living map, or the canal ring's and the regions'. */
    val klass: String get() = if (pack == "bundled") "westerkerk" else "canal"

    /** A file-name form of the name. */
    val slug: String get() = slugOf(name)

    override fun toString() = name
}

/** A planted failure (D16): [kind] planted on the stub's frame of case [base]; [needs] names the package it waits for. */
class Plant(val name: String, val base: String, val kind: String, val expect: String, val tier: String, val needs: String?, val params: JsonObject) {
    fun double(key: String, default: Double): Double = params[key]?.jsonPrimitive?.doubleOrNull ?: default
    fun int(key: String, default: Int): Int = params[key]?.jsonPrimitive?.intOrNull ?: default
    val slug: String get() = slugOf(name)
}

/** The case files of a tier, with the phones they are expanded for and their hash (references are keyed by it). */
class CaseSet(val cases: List<Case>, val planted: List<Plant>, val phones: List<FrameSize>, val hash: String, val tierName: String = "all") {
    fun case(name: String): Case = cases.firstOrNull { it.name == name } ?: throw IllegalArgumentException("no case '$name'")

    /**
     * The cases of [tier]: smoke runs its own; full runs every case. [only] keeps names containing one of its parts;
     * [shard] (k, n) the names that hash to k modulo n. The cases they need come along: a full case's structural twin, a
     * probe's source, a plant's base.
     */
    fun tier(tier: String, only: String? = null, shard: Pair<Int, Int>? = null): CaseSet {
        require(tier == "smoke" || tier == "full") { "the tiers are smoke and full, not '$tier'" }
        val keep = only?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        fun named(name: String) = keep == null || keep.any { name.contains(it) }
        fun inShard(name: String) = shard == null || Math.floorMod(name.hashCode(), shard.second) == shard.first
        fun kept(name: String) = named(name) && inShard(name)
        fun inTier(t: String) = tier == "full" || t == "smoke"
        val cs = cases.filter { inTier(it.tier) && kept(it.name) }
        val names = cs.map { it.name }.toSet()
        // a full-mode case brings its structural twin; a probe, the case it takes its filters from; a plant, its base
        val needed = LinkedHashSet<String>()
        for (c in cs) { c.structural?.let { needed.add(it) }; c.probe?.from?.let { needed.add(it) } }
        // a plant goes with its base (its shard), or by its own name
        val ps = planted.filter { inTier(it.tier) && inShard(it.base) && (named(it.base) || named(it.name)) }
        for (p in ps) if (p.needs == null) needed.add(p.base)
        val extra = cases.filter { it.name in needed && it.name !in names }
        return CaseSet(cs + extra, ps, phones, hash, tier)
    }

    companion object {
        /** The case files: `app/android/mapcorecheck/src/test/resources/likeness/cases` (`CASES/cases/`). */
        fun dir(repo: File = Likeness.repo): File = File(repo, "app/android/mapcorecheck/src/test/resources/likeness/cases")

        /** Reads every case file in [dir], expanding the VK check cases for each phone (the files' and [phone]). */
        fun load(dir: File = dir(), phone: FrameSize? = Likeness.phone): CaseSet {
            val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()
            require(files.isNotEmpty()) { "no case files in $dir" }
            val md = MessageDigest.getInstance("SHA-256")
            val cases = ArrayList<Case>()
            val planted = ArrayList<Plant>()
            val phones = ArrayList<FrameSize>()
            for (f in files) {
                val bytes = f.readBytes()
                md.update(f.name.toByteArray()); md.update(0); md.update(bytes); md.update(0)
                val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                root["phones"]?.jsonArray?.forEach { p ->
                    val o = p.jsonObject
                    val w = o.int("w"); val h = o.int("h")
                    phones.add(FrameSize(o.str("model"), w, h, o["density"]?.jsonPrimitive?.doubleOrNull ?: (w / 412.0)))
                }
                root["cases"]?.jsonArray?.forEach { e ->
                    val c = parseCase(e.jsonObject, f.name)
                    val cam = c.camera
                    if (cam is CameraSpec.Path && cam.step < 0) {
                        // a path without a step: each of its steps, named "<path> 00" …
                        for (k in 0 until cam.steps) cases.add(Case(String.format(java.util.Locale.ROOT, "%s %02d", c.name, k), c.file, c.pack, c.packSha256, cam.copy(step = k), c.moment, c.probe, c.size,
                            c.mode, c.engine, c.gates, c.tier, false, false, null, null, c.name))
                    } else {
                        cases.add(c)
                    }
                }
                root["planted"]?.jsonArray?.forEach { p ->
                    val o = p.jsonObject
                    planted.add(Plant(o.str("name"), o.str("base"), o.str("plant"), o.str("expect"), o.str("tier"), o["needs"]?.jsonPrimitive?.content, o))
                }
            }
            if (phone != null) {
                // hashed whenever given (as :mapcorecheck keys it), listed once
                if (phones.none { it == phone }) phones.add(phone)
                md.update("phone:${phone.name}:${phone.wPx}x${phone.hPx}@${phone.density}".toByteArray())
            }
            val names = HashSet<String>()
            for (c in cases) require(names.add(c.name)) { "two cases are named '${c.name}'" }
            // the phone's size in the tiers: each VK check case again at each phone's own size and density
            val expanded = ArrayList(cases)
            for (p in phones) for (c in cases.filter { it.vkcheck && it.phone == null }) {
                val name = c.name.replace(Regex("\\s+S\\d$"), "") + " @" + p.name
                expanded.add(Case(name, c.file, c.pack, c.packSha256, c.camera, c.moment, c.probe, p, c.mode, c.engine, c.gates, c.tier, true, false, null, p.name))
            }
            for (p in planted) require(p.base in names) { "plant '${p.name}' stands on '${p.base}', which is no case" }
            // each case's and plant's files live under its slug: two names may not share one
            val slugs = HashMap<String, String>()
            for (n in expanded.map { it.name } + planted.map { it.name }) {
                val other = slugs.put(slugOf(n), n)
                require(other == null) { "'$n' and '$other' would share the folder ${slugOf(n)}" }
            }
            val hash = md.digest().joinToString("") { "%02x".format(it) }.substring(0, 16)
            return CaseSet(expanded, planted, phones, hash)
        }

        private fun parseCase(o: JsonObject, file: String): Case {
            val name = o.str("name")
            val sizeEl = o["size"] ?: throw IllegalArgumentException("case '$name' has no size")
            val size = when {
                sizeEl is JsonPrimitive -> FrameSize.NAMED[sizeEl.content] ?: throw IllegalArgumentException("case '$name': no size '${sizeEl.content}'")
                else -> sizeEl.jsonObject.let { s -> FrameSize(s["name"]?.jsonPrimitive?.content ?: "custom", s.int("w"), s.int("h"), s["density"]?.jsonPrimitive?.doubleOrNull ?: (s.int("w") / 412.0)) }
            }
            val mode = when (val m = o["mode"]?.jsonPrimitive?.content ?: "structural") {
                "structural" -> Mode.STRUCTURAL
                "full" -> Mode.FULL
                "effect" -> Mode.EFFECT
                else -> throw IllegalArgumentException("case '$name': no mode '$m'")
            }
            val probe = o["probe"]?.jsonObject?.let { p ->
                ProbeSpec(p.str("effect"), p.str("from"), p["haze"]?.jsonPrimitive?.doubleOrNull, p["vignette"]?.jsonPrimitive?.booleanOrNull == true)
            }
            val scene = o["scene"]?.jsonObject
            val camera = o["camera"]?.jsonObject?.let { c ->
                when {
                    c["view"] != null -> CameraSpec.View(c.str("view"))
                    c["sweep"] != null -> CameraSpec.Sweep(c.int("sweep"), c["of"]?.jsonPrimitive?.intOrNull ?: 24, c["seed"]?.jsonPrimitive?.longOrNull ?: 9L)
                    c["path"] != null -> CameraSpec.Path(c.str("path"), c["step"]?.jsonPrimitive?.intOrNull ?: -1, c["steps"]?.jsonPrimitive?.intOrNull ?: 60)
                    else -> CameraSpec.Explicit(c.dbl("lat"), c.dbl("lon"), c.dbl("distance"), c.dbl("bearing"), c.dbl("pitch"), c["cy"]?.jsonPrimitive?.doubleOrNull)
                }
            }
            val moment = o["moment"]?.jsonObject?.let { m ->
                when {
                    m["named"] != null -> MomentSpec.Named(m.str("named"))
                    m["live"] != null -> MomentSpec.Live(m.str("live"), m["forecast"]?.jsonPrimitive?.content)
                    else -> throw IllegalArgumentException("case '$name': a moment is named or live")
                }
            }
            require(probe != null || (scene != null && camera != null && moment != null)) { "case '$name' needs a scene, a camera and a moment, or a probe" }
            return Case(
                name, file, scene?.str("pack"), scene?.get("sha256")?.jsonPrimitive?.content, camera, moment, probe, size, mode,
                o["engine"]?.jsonPrimitive?.booleanOrNull ?: true, o["gates"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                o["tier"]?.jsonPrimitive?.content ?: "full", o["vkcheck"]?.jsonPrimitive?.booleanOrNull == true,
                o["ladder"]?.jsonPrimitive?.booleanOrNull == true, o["structural"]?.jsonPrimitive?.content, null,
            )
        }

        private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.content ?: throw IllegalArgumentException("missing '$k' in $this")
        private fun JsonObject.int(k: String): Int = (this[k] as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("missing integer '$k' in $this")
        private fun JsonObject.dbl(k: String): Double = (this[k] as? JsonPrimitive)?.doubleOrNull ?: throw IllegalArgumentException("missing number '$k' in $this")
    }
}

/** The cameras a case names, as the map's views. */
object Cameras {
    /** The Westerkerk home (amsterdam.py's main camera) on a 412 × [height] frame: 10 m west, 30 m north, yaw 52°, 38° down, 430 m. */
    fun home(lat: (Double, Double) -> Double, lon: (Double, Double) -> Double, height: Int): LiveView {
        val la = lat(-10.0, -30.0); val lo = lon(-10.0, -30.0)
        return LiveView(la, lo, LiveCamera.zoom(la, 430.0, height.toDouble()), 52.0, 52.0, 412, height, 440.0)
    }

    /**
     * D16's sweep: [n] cameras drawn with [seed] within [box]: the target uniform in the box, the distance log-uniform
     * from 91 m to 4 km (every zoom level as likely), the bearing 0–360°, the map pitch 0–70°, the principal point at
     * 440 or 457.5 dp. StrictMath, so every machine draws the same cameras.
     */
    fun sweep(n: Int, box: BBox, height: Int, seed: Long): List<LiveView> {
        val r = Random(seed)
        return List(n) {
            val lat = box.south + r.nextDouble() * box.height
            val lon = box.west + r.nextDouble() * box.width
            val dist = 91.0 * StrictMath.pow(4000.0 / 91.0, r.nextDouble())
            val bearing = r.nextDouble() * 360.0
            val pitch = r.nextDouble() * 70.0
            val cy = if (r.nextBoolean()) 440.0 else 457.5
            LiveView(lat, lon, LiveCamera.zoom(lat, dist, height.toDouble()), bearing, pitch, 412, height, cy)
        }
    }

    /**
     * The scripted paths of D16 around [home], [steps] steps: `zoom` from 150 m to 8 km (log-even), `orbit` once round
     * the target, `tilt` from map pitch 0 to 70°.
     */
    fun path(path: String, step: Int, steps: Int, home: LiveView): LiveView {
        require(step in 0 until steps) { "step $step of $steps" }
        val t = if (steps == 1) 0.0 else step / (steps - 1.0)
        val h = home.heightDp.toDouble()
        return when (path) {
            "zoom" -> home.copy(zoom = LiveCamera.zoom(home.lat, 150.0 * StrictMath.pow(8000.0 / 150.0, t), h))
            "orbit" -> home.copy(bearing = (home.bearing + 360.0 * step / steps) % 360.0)
            "tilt" -> home.copy(pitch = 70.0 * t)
            else -> throw IllegalArgumentException("no path '$path' (zoom, orbit, tilt)")
        }
    }
}

/** A name as a file name: lower case, letters, digits and hyphens. */
fun slugOf(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

/** A JSON value of a Kotlin value: numbers, strings, booleans, lists, maps and nulls. */
fun jsonOf(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Int -> JsonPrimitive(v)
    is Long -> JsonPrimitive(v)
    is Double -> if (v.isFinite()) JsonPrimitive(v) else JsonNull
    is Float -> if (v.isFinite()) JsonPrimitive(v.toDouble()) else JsonNull
    is Number -> JsonPrimitive(v.toDouble())
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to jsonOf(x) })
    is Iterable<*> -> JsonArray(v.map { jsonOf(it) })
    is IntArray -> JsonArray(v.map { JsonPrimitive(it) })
    is DoubleArray -> JsonArray(v.map { jsonOf(it) })
    else -> throw IllegalArgumentException("no JSON for ${v::class}")
}

class CasesTest {
    @Test
    fun `the case files read, every name once, every plant on a case, the sizes at their BoardScale density`() {
        val set = CaseSet.load(phone = null)
        assertTrue("cases", set.cases.size >= 15)
        val s1 = FrameSize.NAMED.getValue("S1")
        assertEquals(412, s1.frameW); assertEquals(916, s1.frameH)
        assertEquals(893, FrameSize.NAMED.getValue("S2").frameH)
        assertEquals(1440 / 412.0, FrameSize.NAMED.getValue("S3").density, 0.0)
        assertEquals(3.4951, FrameSize.NAMED.getValue("S3").density, 5e-5)
        assertEquals(3.2621, FrameSize.NAMED.getValue("S4").density, 5e-5)
        val smoke = set.tier("smoke")
        assertTrue("the smoke tier keeps the bases of its plants", smoke.planted.filter { it.needs == null }.all { p -> smoke.cases.any { it.name == p.base } })
        assertTrue("every full-mode case names its structural twin", set.cases.filter { it.mode == Mode.FULL }.all { it.structural != null })
        assertEquals("the hash is the files'", set.hash, CaseSet.load(phone = null).hash)
    }

    @Test
    fun `a phone from its VK facts size, and the VK check cases again at its size`() {
        val p = FrameSize.phone("Google Pixel 7a:1080x2400")
        assertEquals(1080 / 412.0, p.density, 0.0)
        assertEquals(FrameSize("Acme X", 1440, 3200, 3.5), FrameSize.phone("Acme X:1440x3200@3.5"))
        val set = CaseSet.load(phone = p)
        val vk = set.cases.filter { it.phone == p.name }
        assertEquals(set.cases.count { it.vkcheck && it.phone == null }, vk.size)
        assertTrue(vk.all { it.size == p && it.name.endsWith("@Google Pixel 7a") })
        assertTrue("the phone changes the hash", set.hash != CaseSet.load(phone = null).hash)
    }

    @Test
    fun `a scripted path stands for each of its steps, in order`() {
        val zoom = CaseSet.load(phone = null).cases.filter { it.path == "path zoom" }
        assertEquals(60, zoom.size)
        assertEquals("path zoom 00", zoom.first().name); assertEquals("path zoom 59", zoom.last().name)
        assertEquals((0 until 60).toList(), zoom.map { (it.camera as CameraSpec.Path).step })
        assertTrue(zoom.all { it.tier == "full" && "CONTINUITY" in it.gates })
    }

    @Test
    fun `the sweep is the same on every machine and stays in its ranges`() {
        val box = BBox(4.87, 52.37, 4.89, 52.38)
        val a = Cameras.sweep(24, box, 916, 9)
        assertEquals(a, Cameras.sweep(24, box, 916, 9))
        for (v in a) {
            assertTrue(v.lat in 52.37..52.38 && v.lon in 4.87..4.89)
            assertTrue(v.distance in 90.0..4001.0)
            assertTrue(v.pitch in 0.0..70.0 && v.bearing in 0.0..360.0)
            assertTrue(v.cyDp == 440.0 || v.cyDp == 457.5)
        }
    }
}
