package app.atlas.likeness

import app.atlas.mapview.svg.SvgFe
import app.atlas.mapview.svg.SvgParser
import app.atlas.mapview.svg.Turbulence
import app.atlas.mapview.svg.SvgUnits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * A phone's VK check asset (D16, K12): the VK check cases drawn by the oracle at the phone's own size and density (from
 * its VK facts), with their owner maps and label lists, the noise fields' hashes per case, the gates, keyed by
 * `EngineVersion.ID`, `StyleVersion` and the case files' hash, zipped as `vkcheck-<model>-<key>.zip` for the release.
 * The phone never draws a reference: it compares its own frames with these.
 */
object PhoneRefs {
    /** What one asset holds. */
    class Asset(val file: File, val key: String, val cases: List<String>, val bytes: Long, val millis: Long)

    /** Builds the asset of [phone] into [out]; [only] keeps the VK check cases whose names contain one of its parts. */
    fun build(phone: FrameSize, out: File, only: String? = null): Asset {
        val t0 = System.nanoTime()
        val all = CaseSet.load(phone = phone)
        val keep = only?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val vk = all.cases.filter { it.phone == phone.name && (keep == null || keep.any { k -> it.name.contains(k) }) }
        require(vk.isNotEmpty()) { "no VK check cases for ${phone.name}" }
        val set = CaseSet(vk, emptyList(), listOf(phone), all.hash, "vkcheck")
        val r = References.build(set)
        val entries = r.set.entries()
        val fields = LinkedHashMap<String, Any?>()
        for (c in vk) fields[c.name] = fieldsOf(c)
        val key = r.set.key
        val name = "vkcheck-${slugOf(phone.name)}-$key.zip"
        out.mkdirs()
        val zip = File(out, name)
        ZipOutputStream(zip.outputStream().buffered(1 shl 16)).use { z ->
            fun put(path: String, f: File) { z.putNextEntry(ZipEntry(path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            fun putText(path: String, s: String) { z.putNextEntry(ZipEntry(path)); z.write(s.toByteArray()); z.closeEntry() }
            val cases = ArrayList<Any?>()
            for (c in vk) {
                val e = entries[c.name] ?: error("no reference for ${c.name}")
                for (p in e["files"]!!.jsonObject.values) put(p.jsonPrimitive.content, File(r.set.dir, p.jsonPrimitive.content))
                cases.add(e)
            }
            val thresholds = File(Likeness.repo, "app/android/mapcorecheck/src/test/resources/likeness/thresholds.json")
            put("thresholds.json", thresholds)
            val manifest = linkedMapOf(
                "format" to 1, "key" to key, "engine" to r.set.manifest()["engine"], "style" to Likeness.style, "cases" to all.hash,
                "phone" to mapOf("model" to phone.name, "w" to phone.wPx, "h" to phone.hPx, "density" to phone.density, "frameW" to phone.frameW, "frameH" to phone.frameH),
                "platform" to "phone:${phone.name}",
                "gates" to "thresholds.json: lavapipe-x86_64's gates with phoneRule applied, until the coordinator records this model's own",
                "twin" to mapOf("status" to "absent", "about" to "the twin's primitive-stream hashes per case come with the twin (K7) and VK check (K12)"),
                "fields" to fields,
                "entries" to cases.map { e -> e },
            )
            putText("manifest.json", Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), jsonOf(manifest) as kotlinx.serialization.json.JsonObject) + "\n")
        }
        return Asset(zip, key, vk.map { it.name }, zip.length(), (System.nanoTime() - t0) / 1_000_000)
    }

    /**
     * The noise fields a case's render filters with, at its frame's size and density: every `feTurbulence` of a filter
     * over the whole frame (userSpaceOnUse, as `mat`, `grain`, `ripple` and the glints are, and the cloud shadows'
     * `cloudsh`, a bounding-box filter on a rect over the frame, amsterdam.py:945), evaluated as WebKit samples it
     * (`Turbulence.fillWebKit`: filter pixel (x, y) at the user point ((x + 1)/d, (y + 1)/d)), all four channels
     * unpremultiplied, hashed as R, G, B, A bytes row by row (SHA-256). The core's CPU path (K17a) makes the same bytes;
     * VK check compares its hash with this.
     */
    fun fieldsOf(c: Case): Map<String, Any?> {
        val made = Oracle.render(c)
        val size = c.size
        val seen = LinkedHashMap<String, Pair<String, SvgFe.Turbulence>>()
        for (text in made.layers) {
            val doc = SvgParser.parse(text)
            for (f in doc.filters.values) {
                if (f.units != SvgUnits.USER_SPACE && f.id != "cloudsh") continue
                for ((k, p) in f.primitives.withIndex()) {
                    if (p !is SvgFe.Turbulence) continue
                    val key = "seed=${p.seed} base=${p.baseX},${p.baseY} octaves=${p.octaves} fractal=${p.fractal} stitch=${p.stitch}"
                    if (seen.values.any { it.first == key }) continue
                    seen["${f.id}/$k"] = key to p
                }
            }
        }
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        try {
            val jobs = seen.map { (name, kp) -> name to pool.submit(Callable { fieldHash(kp.first, kp.second, size) }) }
            return jobs.associate { (name, f) -> name to f.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun fieldHash(params: String, p: SvgFe.Turbulence, size: FrameSize): Map<String, Any?> {
        // a field per job: Turbulence.of keeps an unsynchronised table
        val t = Turbulence(p.seed)
        val inv = (1.0 / size.density).toFloat()
        val w = size.wPx; val h = size.hPx
        val md = MessageDigest.getInstance("SHA-256")
        val row = IntArray(w)
        val bytes = ByteArray(w * 4)
        val t0 = System.nanoTime()
        for (y in 0 until h) {
            t.fillWebKit(0, y, w, 1, inv, inv, p.baseX, p.baseY, p.octaves, p.fractal, 15, row)
            for (x in 0 until w) {
                val v = row[x]
                bytes[x * 4] = (v shr 16).toByte(); bytes[x * 4 + 1] = (v shr 8).toByte(); bytes[x * 4 + 2] = v.toByte(); bytes[x * 4 + 3] = (v ushr 24).toByte()
            }
            md.update(bytes)
        }
        return mapOf("params" to params, "w" to w, "h" to h, "scale" to inv.toDouble(), "sha256" to md.digest().joinToString("") { "%02x".format(it) },
            "millis" to ((System.nanoTime() - t0) / 1e6).roundToInt())
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PhoneRefsTest {
    /**
     * K4's acceptance: a `vkcheck-<model>` asset built for a given size and density. The phone comes from
     * `-Datlas.likeness.phone=<model>:<w>x<h>[@<density>]` (CI's asset job, from the owner's VK facts); without one, a
     * 1080 × 2340 phone at 2.75 (another size and density than CI's) with the Westerkerk's two VK check cases.
     */
    @Test
    fun `a VK check asset for a phone's own size and density`() {
        val given = Likeness.phone
        val phone = given ?: FrameSize.phone("Test phone 1080x2340:1080x2340@2.75")
        val out = System.getProperty("atlas.likeness.asset")?.let { File(it) } ?: File(Likeness.refs, "assets")
        val a = PhoneRefs.build(phone, out, if (given == null) "home" else null)
        println("vkcheck asset ${a.file.name}: ${a.cases.size} cases, %.1f MB, %.1f s".format(a.bytes / 1e6, a.millis / 1000.0))
        ZipFile(a.file).use { z ->
            val m = Json.parseToJsonElement(z.getInputStream(z.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8)).jsonObject
            val p = m["phone"]!!.jsonObject
            assertEquals(phone.wPx, p["w"]!!.jsonPrimitive.content.toInt())
            assertEquals(phone.density, p["density"]!!.jsonPrimitive.content.toDouble(), 0.0)
            val entries = m["entries"]!!.jsonArray
            assertEquals(a.cases.size, entries.size)
            for (e in entries) {
                val o = e.jsonObject
                assertEquals(phone.wPx, o["size"]!!.jsonObject["w"]!!.jsonPrimitive.content.toInt())
                for (f in o["files"]!!.jsonObject.values) assertTrue("${f.jsonPrimitive.content} in the asset", z.getEntry(f.jsonPrimitive.content) != null)
                val img = javax.imageio.ImageIO.read(z.getInputStream(z.getEntry(o["files"]!!.jsonObject["ref"]!!.jsonPrimitive.content)))
                assertEquals(phone.wPx, img.width); assertEquals(phone.hPx, img.height)
            }
            val fields = m["fields"]!!.jsonObject
            assertTrue("every case has its fields' hashes", fields.size == a.cases.size && fields.values.all { it.jsonObject.isNotEmpty() })
            println("  fields: " + fields.entries.first().value.jsonObject.entries.joinToString { (k, v) -> "$k ${v.jsonObject["sha256"]!!.jsonPrimitive.content.take(12)}" })
            assertTrue(z.getEntry("thresholds.json") != null)
        }
    }
}
