package app.atlas.mapcorecheck.likeness

import app.atlas.render.live.EngineVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * The likeness harness's candidate side (plan-vector D16, K4): reads the reference sets `:likeness` wrote, takes the
 * candidates (the core's host build on lavapipe, or the stub), measures them with D16's metrics and judges them by the
 * gates of `thresholds.json` for the platform and the stage.
 */
object Likeness {
    val repo: File get() = File(System.getProperty("atlas.repo") ?: "../..").canonicalFile

    /** The case files and the thresholds (`CASES/`). */
    val cases: File get() = File(repo, "app/android/mapcorecheck/src/test/resources/likeness")

    /** Where `:likeness` keeps reference sets. */
    val refs: File get() = System.getProperty("atlas.likeness.refs")?.takeIf { it.isNotBlank() }?.let { File(it) } ?: File(repo, "cache/likeness/refs")

    val tier: String get() = System.getProperty("atlas.likeness.tier")?.takeIf { it.isNotBlank() } ?: "smoke"
    val only: String? get() = System.getProperty("atlas.likeness.only")?.takeIf { it.isNotBlank() }

    /** The thresholds file: the case files' own, or `-Datlas.likeness.thresholds` (a proposal under test). */
    val thresholdsFile: File get() = System.getProperty("atlas.likeness.thresholds")?.takeIf { it.isNotBlank() }?.let { File(it) } ?: File(cases, "thresholds.json")

    /** Where the report goes. */
    val report: File get() = System.getProperty("atlas.likeness.report")?.let { File(it) } ?: File(repo, "cache/likeness/report")

    /** The commit measured (CI passes `github.sha`), for the ratchet's `commit`. */
    val commit: String? get() = System.getProperty("atlas.likeness.commit")?.takeIf { it.isNotBlank() }

    /** The packer's StyleVersion, as `:likeness` was given it. */
    val style: String get() = System.getProperty("atlas.likeness.style")?.takeIf { it.isNotBlank() } ?: "unversioned"

    /**
     * The platform the candidate draws on: `-Datlas.likeness.platform` (CI: lavapipe-x86_64, lavapipe-arm64), else
     * this machine's lavapipe.
     */
    val platform: String get() = System.getProperty("atlas.likeness.platform")?.takeIf { it.isNotBlank() } ?: when {
        System.getProperty("os.name").lowercase().contains("mac") -> "lavapipe-macos"
        System.getProperty("os.arch") in setOf("aarch64", "arm64") -> "lavapipe-arm64"
        else -> "lavapipe-x86_64"
    }

    /** The case files' hash, as `:likeness` keys its sets (the same algorithm as its `CaseSet.load`). */
    fun casesHash(phone: String? = System.getProperty("atlas.likeness.phone")?.takeIf { it.isNotBlank() }): String {
        val files = File(cases, "cases").listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()
        val md = MessageDigest.getInstance("SHA-256")
        for (f in files) { md.update(f.name.toByteArray()); md.update(0); md.update(f.readBytes()); md.update(0) }
        if (phone != null) {
            val p = phoneSpec(phone)
            md.update("phone:${p.first}:${p.second}x${p.third}@${p.fourth}".toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun phoneSpec(spec: String): Quad<String, Int, Int, Double> {
        val colon = spec.lastIndexOf(':')
        val rest = spec.substring(colon + 1).trim()
        val at = rest.indexOf('@')
        val dims = (if (at >= 0) rest.substring(0, at) else rest).split('x', 'X', '×').map { it.trim().toInt() }
        val d = if (at >= 0) rest.substring(at + 1).trim().toDouble() else dims[0] / 412.0
        return Quad(spec.substring(0, colon).trim(), dims[0], dims[1], d)
    }

    /** The key the current port, style and case files ask for. */
    val key: String get() = "${EngineVersion.ID}-$style-${casesHash()}"

    fun json(file: File): JsonObject = Json.parseToJsonElement(file.readText()).jsonObject
}

/** A frame read from a reference set: opaque ARGB or owner indices, [w] × [h]. */
class Frame(val w: Int, val h: Int, val px: IntArray) {
    companion object {
        fun read(file: File): Frame {
            val img = ImageIO.read(file) ?: throw IllegalStateException("not a PNG: $file")
            val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
            for (i in px.indices) px[i] = px[i] or (0xFF shl 24)
            return Frame(img.width, img.height, px)
        }

        fun readIndex(file: File): Frame {
            val f = read(file)
            for (i in f.px.indices) f.px[i] = f.px[i] and 0xFFFFFF
            return f
        }
    }
}

/** An owner of pixels: an OSM element ([type] n, w, r, a for an area, o), its class and kind, its reference pixels. */
data class Owner(val type: String, val id: Long, val klass: String, val kind: String, val pixels: Int) {
    val tag: String get() = type + id
}

/** A text drawn, as `:likeness` lists them. */
data class Label(val text: String, val cx: Double, val cy: Double, val angle: Double, val size: Double, val layer: String, val visible: Boolean)

/** An owner map: per pixel the index of its owner (1-based) in [owners], 0 for none. */
class OwnerFrame(val frame: Frame, val owners: List<Owner>)

/** One case of a reference set, as its manifest describes it, with lazy access to its files. */
class RefEntry(val set: RefSetReader, val name: String, val o: JsonObject) {
    val slug: String get() = o.str("slug")
    val mode: String get() = o.str("mode")
    val tier: String get() = o.str("tier")
    val klass: String get() = o.str("klass")
    val gates: List<String> get() = o["gates"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
    val structural: String? get() = o.strOrNull("structural")
    val phone: String? get() = o.strOrNull("phone")
    val isProbe: Boolean get() = o["probe"] != null && o["probe"] !is JsonNull
    val isSweep: Boolean get() = name.contains("sweep")

    /** The scripted path this case is a step of (D16's continuity), and which step; null and −1 otherwise. */
    val pathName: String? get() = (o["path"] as? JsonObject)?.strOrNull("name")
    val pathStep: Int get() = (o["path"] as? JsonObject)?.int("step") ?: -1
    val w: Int get() = o["size"]!!.jsonObject.int("w")
    val h: Int get() = o["size"]!!.jsonObject.int("h")
    val density: Double get() = o["size"]!!.jsonObject.dbl("density")
    val view: JsonObject? get() = o["view"] as? JsonObject
    val moment: JsonObject? get() = o["moment"] as? JsonObject
    fun file(key: String): File? = o["files"]?.jsonObject?.get(key)?.jsonPrimitive?.content?.let { File(set.dir, it) }

    val ref: Frame by lazy { readRef() }

    /** The reference frame read again, not kept (a path's 60 frames would not fit). */
    fun readRef(): Frame = Frame.read(file("ref")!!)
    val owners: OwnerFrame? by lazy { file("owners")?.let { OwnerFrame(Frame.readIndex(it), readOwners(file("ownerTable")!!)) } }
    val labels: List<Label>? by lazy { file("labels")?.let { readLabels(it) } }

    companion object {
        fun readOwners(f: File): List<Owner> = Likeness.json(f)["owners"]!!.jsonArray.map { e ->
            val a = e.jsonArray
            Owner(a[0].jsonPrimitive.content, a[1].jsonPrimitive.content.toLong(), a[2].jsonPrimitive.content, a[3].jsonPrimitive.content, a[4].jsonPrimitive.content.toInt())
        }

        fun readLabels(f: File): List<Label> = Likeness.json(f)["labels"]!!.jsonArray.map { e ->
            val m = e.jsonObject
            Label(m.str("text"), m.dbl("cx"), m.dbl("cy"), m.dbl("angle"), m.dbl("size"), m.str("layer"), (m["visible"] as? JsonPrimitive)?.booleanOrNull ?: true)
        }
    }
}

/** A planted frame of the stub (D16's planted failures), as the manifest describes it. */
class PlantEntry(val set: RefSetReader, val name: String, val o: JsonObject) {
    val base: String get() = o.str("base")
    val kind: String get() = o.str("kind")
    val expect: String get() = o.str("expect")
    val tier: String get() = o.str("tier")
    val detail: String get() = o.str("detail")
    private fun file(key: String) = File(set.dir, o["files"]!!.jsonObject[key]!!.jsonPrimitive.content)
    val frame: Frame by lazy { Frame.read(file("frame")) }
    val owners: OwnerFrame by lazy { OwnerFrame(Frame.readIndex(file("owners")), RefEntry.readOwners(file("ownerTable"))) }
    val labels: List<Label> by lazy { RefEntry.readLabels(file("labels")) }
}

/** A reference set on disk, as `:likeness` writes it (`<refs>/<key>/manifest.json` and its files). */
class RefSetReader(val dir: File) {
    val manifest: JsonObject = Likeness.json(File(dir, "manifest.json"))
    val key: String get() = manifest.str("key")
    val entries: Map<String, RefEntry> = manifest["entries"]?.jsonObject?.mapValues { (k, v) -> RefEntry(this, k, v.jsonObject) } ?: emptyMap()
    val planted: Map<String, PlantEntry> = manifest["planted"]?.jsonObject?.mapValues { (k, v) -> PlantEntry(this, k, v.jsonObject) } ?: emptyMap()
    val ladder: JsonObject get() = manifest["ladder"]?.jsonObject ?: JsonObject(emptyMap())
    /** The shard of the tier the set holds (`k/n`: CI's nightly full tier runs in two), or null for the whole tier. */
    val shard: String? get() = manifest.strOrNull("shard")

    companion object {
        /**
         * The set for [key] under [root]. When `:likeness` has not made it, the test is skipped (a plain `test` run with
         * no references), or fails clearly where references are required (`-Datlas.likeness.require=true`, as CI's
         * likeness step runs).
         */
        fun open(root: File = Likeness.refs, key: String = Likeness.key): RefSetReader {
            val d = File(root, key)
            if (!File(d, "manifest.json").isFile) {
                val why = "no reference set $key in $root: run :likeness:testDebugUnitTest (ReferencesTest) first, with the same -Datlas.likeness.refs and tier" +
                    (root.listFiles()?.map { it.name }?.let { "; the sets there: $it" } ?: "")
                check(System.getProperty("atlas.likeness.require") != "true") { why }
                Assume.assumeTrue(why, false)
            }
            return RefSetReader(d)
        }
    }
}

/**
 * One metric's limit in thresholds.json: its fixed value, the one in force, the last measured and its commit, the
 * history, and where it sits in the file ([path], for the ratchet).
 */
class Limit(
    val metric: String, val bound: String, val fixed: Double, val threshold: Double, val measured: Double?, val commit: String?, val history: JsonArray,
    val path: List<String>,
) {
    /** Whether [v] meets this limit. */
    fun holds(v: Double): Boolean = if (bound == "min") v >= threshold else v <= threshold
}

/** thresholds.json, read: the stage, the metrics' directions, each platform's gates per stage. */
class Thresholds(val json: JsonObject) {
    val stage: String get() = System.getProperty("atlas.likeness.stage")?.takeIf { it.isNotBlank() } ?: json.str("stage")
    val stages: List<String> = json["stages"]!!.jsonArray.map { it.jsonPrimitive.content }
    val bounds: Map<String, String> = json["metrics"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }

    /** The platform whose gates [name] uses: itself, or the one its `sameAs` names. */
    fun resolve(name: String): String {
        val p = json["platforms"]!!.jsonObject[name] ?: throw IllegalArgumentException("thresholds.json has no platform '$name'")
        return p.jsonObject.strOrNull("sameAs")?.let { resolve(it) } ?: name
    }

    /** A platform's object, following `sameAs`. */
    fun platform(name: String): JsonObject = json["platforms"]!!.jsonObject[resolve(name)]!!.jsonObject

    fun role(name: String): String = json["platforms"]!!.jsonObject[name]?.jsonObject?.strOrNull("role") ?: "guidance"

    /**
     * The limits of [gate] on [platform] at [stage] for a case named [case] of class [klass]: the case's own entry where
     * the stage has one, else its class's, else the stage's. Empty when the gate sets nothing at that stage.
     */
    fun limits(platform: String, gate: String, stage: String, case: String?, klass: String?): List<Limit> {
        val pn = resolve(platform)
        val g = platform(pn)["gates"]?.jsonObject?.get(gate)?.jsonObject ?: return emptyList()
        val st = g["stages"]?.jsonObject?.get(stage)?.jsonObject ?: return emptyList()
        val base = listOf("platforms", pn, "gates", gate, "stages", stage)
        val chosen = LinkedHashMap<String, Pair<JsonObject, List<String>>>()
        for ((k, v) in st) if (v is JsonObject && "fixed" in v) chosen[k] = v to base + k
        klass?.let { st["classes"]?.jsonObject?.get(it)?.jsonObject }?.forEach { (k, v) -> chosen[k] = v.jsonObject to base + listOf("classes", klass, k) }
        case?.let { st["cases"]?.jsonObject?.get(it)?.jsonObject }?.forEach { (k, v) -> chosen[k] = v.jsonObject to base + listOf("cases", case, k) }
        return chosen.map { (m, vp) -> limitOf(m, vp.first, vp.second) }
    }

    fun limitOf(metric: String, v: JsonObject, path: List<String>): Limit = Limit(
        metric, bounds[metric] ?: throw IllegalStateException("thresholds.json does not say which way '$metric' bounds"),
        v.dbl("fixed"), v.dbl("threshold"), (v["measured"] as? JsonPrimitive)?.doubleOrNull, v.strOrNull("commit"), v["history"]?.jsonArray ?: JsonArray(emptyList()), path,
    )

    /** The stage a platform judges at: its own (`stub`: A4), else the file's. */
    fun stageOf(platform: String): String = json["platforms"]!!.jsonObject[platform]?.jsonObject?.strOrNull("stage") ?: stage

    companion object {
        fun read(file: File = Likeness.thresholdsFile): Thresholds = Thresholds(Likeness.json(file))
    }
}

/** One metric judged: the gate, its limit, the value measured (NaN: not measured), and the outcome. */
class Judged(val gate: String, val limit: Limit?, val value: Double, val note: String = "") {
    /** PASS, FAIL, or NONE when the candidate gave nothing to measure. */
    val outcome: String get() = when {
        limit == null -> "NONE"
        value.isNaN() -> "FAIL"
        limit.holds(value) -> "PASS"
        else -> "FAIL"
    }

    override fun toString() = "$gate.${limit?.metric ?: "-"} %s %s %s".format(fmt(value), if (limit == null) "" else (if (limit.bound == "min") "≥" else "≤") + " " + fmt(limit.threshold), outcome)

    private fun fmt(v: Double) = if (v.isNaN()) "n/a" else if (abs(v) < 0.01 && v != 0.0) "%.6f".format(java.util.Locale.ROOT, v) else "%.3f".format(java.util.Locale.ROOT, v)
    private fun abs(v: Double) = kotlin.math.abs(v)
}

/**
 * What one case measured, metric by metric (D16's names); [na] holds the metrics that do not apply to it (flat misses
 * outside structural mode, owners where the reference has no building to measure), which its gates pass over. A metric
 * that applies and was not measured (the candidate gave nothing) fails.
 */
class Measured(val case: String) {
    val values = LinkedHashMap<String, Double>()
    val na = HashSet<String>()
    val notes = ArrayList<String>()
    operator fun set(k: String, v: Double) { values[k] = v }
    operator fun get(k: String): Double = values[k] ?: Double.NaN
}

/** Judges measured cases by thresholds.json's gates, for one platform at one stage. */
class Judge(val t: Thresholds, val platform: String, val stage: String = t.stageOf(platform)) {
    /** The gates measured by other packages' tests (L0, L1, L2: K3, K3b, K7, K12) and not by the pixels here. */
    private val elsewhere = setOf("L0", "L1", "L2")

    /** The gates judged over a whole path or a tier's sweeps, not per case. */
    private val aggregates = setOf("CONTINUITY", "SWEEP_W", "SWEEP_C")

    /** The judged metrics of [gates] for case [case] of class [klass] with [m]; aggregate gates (sweeps) are left to [aggregate]. */
    fun case(case: String, klass: String, gates: List<String>, m: Measured): List<Judged> {
        val out = ArrayList<Judged>()
        for (g in gates) {
            if (g in elsewhere || g in aggregates) continue
            for (l in t.limits(platform, g, stage, case, klass)) if (l.metric !in m.na) out.add(Judged(g, l, m[l.metric]))
        }
        return out
    }

    /** A path's CONTINUITY gate, with what the path measured ([m]: popsOverPort, and the rest not applicable or measured). */
    fun path(name: String, m: Measured): List<Judged> =
        t.limits(platform, "CONTINUITY", stage, null, null).filter { it.metric !in m.na }.map { Judged("CONTINUITY", it, m[it.metric], name) }

    /** The sweeps' gates: the median and p90 of the frame MAD over the tier's sweep cases of each class. */
    fun aggregate(sweeps: Map<String, List<Double>>): List<Judged> {
        val out = ArrayList<Judged>()
        for ((gate, mads) in sweeps) {
            if (mads.isEmpty()) continue
            val s = mads.sorted()
            val median = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
            val p90 = s[kotlin.math.ceil(0.9 * s.size).toInt().coerceIn(1, s.size) - 1]
            for (l in t.limits(platform, gate, stage, null, null)) {
                out.add(Judged(gate, l, if (l.metric == "medianMad") median else if (l.metric == "p90Mad") p90 else Double.NaN, "${mads.size} sweeps"))
            }
        }
        return out
    }
}

/** JSON helpers. */
fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.content ?: throw IllegalArgumentException("missing '$k'")
fun JsonObject.strOrNull(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonObject.int(k: String): Int = (this[k] as JsonPrimitive).content.toInt()
fun JsonObject.dbl(k: String): Double = (this[k] as JsonPrimitive).doubleOrNull ?: throw IllegalArgumentException("'$k' is not a number")

/** A JSON value of plain Kotlin values. */
fun jsonOf(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Int -> JsonPrimitive(v)
    is Long -> JsonPrimitive(v)
    is Double -> if (v.isFinite()) JsonPrimitive(v) else JsonNull
    is Number -> JsonPrimitive(v.toDouble())
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to jsonOf(x) })
    is Iterable<*> -> JsonArray(v.map { jsonOf(it) })
    else -> throw IllegalArgumentException("no JSON for ${v::class}")
}
