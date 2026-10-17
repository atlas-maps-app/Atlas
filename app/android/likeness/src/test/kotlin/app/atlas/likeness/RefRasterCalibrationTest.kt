package app.atlas.likeness

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.atlas.anchor.Anchor
import app.atlas.anchor.AnchorCases
import app.atlas.anchor.AnchorFilter
import app.atlas.anchor.Faces
import app.atlas.anchor.GroupFilter
import app.atlas.anchor.LayerKind
import app.atlas.anchor.PrimKind
import app.atlas.anchor.RefMetrics
import app.atlas.anchor.RefRaster
import app.atlas.anchor.RefRaster.Variant
import app.atlas.anchor.Tile
import app.atlas.anchor.TileSource
import app.atlas.mapview.svg.SvgCanvas
import app.atlas.mapview.svg.SvgDocument
import app.atlas.mapview.svg.SvgFilterRaster
import app.atlas.mapview.svg.SvgGroup
import app.atlas.mapview.svg.SvgNode
import app.atlas.mapview.svg.SvgParser
import app.atlas.mapview.svg.SvgUnits
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.LiveView
import app.atlas.render.live.Moment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.EnumMap
import java.util.Locale
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The coverage calibration (plan-vector D7, K6): RefRaster, the GPU's rules emulated, in each variant (hyb4,
 * msaa4-standard, msaa2-standard, all-analytic) against the exact raster of Android's own Skia (K4's [Frames]: the
 * port's layers at the case's density, no view-box fit, cropped), over G1's cases (plan-gpu D8: the Westerkerk home and
 * sweeps at S1–S3, the Main board and the canal ring's sweeps at S1), in two modes:
 * - **structural**: filters off on both sides (generator-only elements left out, other filters drawn as absent);
 * - **silhouettes**: the same with `mat` drawn on both sides (`amsterdam.py:635`), exactly as the renders run it, so its
 *   coverage rule (alpha min(1, 1.1·A)·A) thins each variant's own coverage A of its group: MSAA's quarters or halves,
 *   or the analytic coverage. RefRaster isolates the group and hands it to [MatFilter], the port's `SvgFilterRaster`.
 *
 * D7's rule decides: MSAA (msaa4-standard) stays unless its median MAD exceeds hyb4's by more than [MARGIN] in either
 * class, structural or at silhouettes (then FA: analytic faces, hyb4's); all-analytic is taken only when it wins by a
 * clear margin (its median more than [MARGIN] under the variant otherwise adopted, in both classes and both modes).
 * Configuration B's 2x is allowed when msaa2-standard is within [MARGIN] of msaa4-standard in both classes (D5.3).
 *
 * With `-Datlas.calibrate=true` (or `ATLAS_CALIBRATE=true`) it measures every case and rewrites
 * `anchor/src/test/resources/likeness.json` (`ATLAS_CALIBRATE_RECORDS=<file>` keeps each finished case, so a run that
 * stops resumes; `ATLAS_CALIBRATE_WORKERS` sets the cases measured at once). `ATLAS_CALIBRATE=arch` adds another
 * architecture's own numbers (the smoke and the boards' cases) beside the calibration's. Otherwise it measures the smoke
 * cases and checks that they still measure as recorded for this architecture (recording them first where none are),
 * and that the recorded decision follows from the recorded numbers.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RefRasterCalibrationTest {
    private val mode = System.getProperty("atlas.calibrate") ?: System.getenv("ATLAS_CALIBRATE")
    private val calibrate = mode == "true"

    /** `arch`: only this architecture's own numbers measured and kept in likeness.json, beside the calibration's. */
    private val recordArch = mode == "arch"
    private val likeness = File(AnchorCases.repo, "app/android/anchor/src/test/resources/likeness.json")

    /** Where a calibration writes (`ATLAS_CALIBRATE_OUT`, else likeness.json), and the cases it measures (`ATLAS_CALIBRATE_ONLY`: parts of names). */
    private val out = System.getenv("ATLAS_CALIBRATE_OUT")?.takeIf { it.isNotBlank() }?.let { File(it) } ?: likeness
    private val only = System.getenv("ATLAS_CALIBRATE_ONLY")?.takeIf { it.isNotBlank() }?.split(',')?.map { it.trim() }
    private val workers = (System.getenv("ATLAS_CALIBRATE_WORKERS")?.toIntOrNull() ?: 3).coerceIn(1, 8)

    /** One case: a render at a size, its class (the Westerkerk's or the canal ring's), and whether the check runs it. */
    class Case(val name: String, val canal: Boolean, val size: AnchorCases.Size, val view: (LiveRegion) -> LiveView, val moment: Moment, val smoke: Boolean) {
        val klass: String get() = if (canal) CANAL else WESTERKERK
    }

    /** The two modes of D7's calibration. */
    enum class CalMode(val key: String) { STRUCTURAL("structural"), SILHOUETTES("silhouettes") }

    /**
     * One variant's numbers in one mode: the frame metrics (D8's), and the MAD over the reference's flat pixels and over
     * the rest (structural), or over the pixels hyb4's objects group covers in part and their share (silhouettes).
     */
    class Numbers(
        val mad: Double, val within2: Double, val p99: Int, val block: Double, val flat: Double,
        val flatMad: Double = Double.NaN, val edgesMad: Double = Double.NaN,
        val silhouetteMad: Double = Double.NaN, val silhouetteShare: Double = Double.NaN,
    ) {
        constructor(m: RefMetrics, flatMad: Double = Double.NaN, edgesMad: Double = Double.NaN, silhouetteMad: Double = Double.NaN, silhouetteShare: Double = Double.NaN) :
            this(m.mad, m.within2, m.p99, m.block, m.flatMisses, flatMad, edgesMad, silhouetteMad, silhouetteShare)

        fun json(): String {
            val sb = StringBuilder(String.format(Locale.ROOT, "{\"mad\": %.4f, \"within2\": %.5f, \"p99\": %d, \"block\": %.3f, \"flat\": %.8f", mad, within2, p99, block, flat))
            if (!flatMad.isNaN()) sb.append(String.format(Locale.ROOT, ", \"flatMad\": %.4f, \"edgesMad\": %.4f", flatMad, edgesMad))
            if (!silhouetteMad.isNaN()) sb.append(String.format(Locale.ROOT, ", \"silhouetteMad\": %.4f, \"silhouetteShare\": %.5f", silhouetteMad, silhouetteShare))
            return sb.append('}').toString()
        }

        override fun toString() = String.format(Locale.ROOT, "MAD %.3f within2 %.2f%% p99 %d block %.2f flat %.4f%%", mad, within2 * 100, p99, block, flat * 100) +
            (if (flatMad.isNaN()) "" else String.format(Locale.ROOT, "; flat pixels %.3f, edges %.3f", flatMad, edgesMad)) +
            (if (silhouetteMad.isNaN()) "" else String.format(Locale.ROOT, "; silhouettes %.3f over %.2f%%", silhouetteMad, silhouetteShare * 100))

        companion object {
            fun parse(o: JsonObject): Numbers {
                fun d(k: String) = o[k]?.jsonPrimitive?.double ?: Double.NaN
                return Numbers(d("mad"), d("within2"), o["p99"]!!.jsonPrimitive.int, d("block"), d("flat"), d("flatMad"), d("edgesMad"), d("silhouetteMad"), d("silhouetteShare"))
            }
        }
    }

    /** A case measured: per mode, per variant, its numbers; [mat]: whether its render draws `mat` (the named night does not). */
    class Record(val name: String, val klass: String, val smoke: Boolean, val mat: Boolean, val numbers: Map<CalMode, Map<Variant, Numbers>>) {
        operator fun get(mode: CalMode, v: Variant): Numbers = numbers.getValue(mode).getValue(v)

        fun json(): String {
            val sb = StringBuilder()
            sb.append("{\"name\": \"").append(name).append("\", \"class\": \"").append(klass).append("\", \"smoke\": ").append(smoke).append(", \"mat\": ").append(mat)
            for (mode in CalMode.entries) {
                sb.append(", \"").append(mode.key).append("\": {")
                sb.append(Variant.entries.filter { it in numbers.getValue(mode) }.joinToString(", ") { v -> "\"${v.id}\": ${get(mode, v).json()}" })
                sb.append('}')
            }
            return sb.append('}').toString()
        }

        companion object {
            fun parse(o: JsonObject): Record {
                val numbers = EnumMap<CalMode, Map<Variant, Numbers>>(CalMode::class.java)
                for (mode in CalMode.entries) {
                    val m = o[mode.key]!!.jsonObject
                    numbers[mode] = Variant.entries.filter { m.containsKey(it.id) }.associateWith { v -> Numbers.parse(m[v.id]!!.jsonObject) }
                }
                return Record(o["name"]!!.jsonPrimitive.content, o["class"]!!.jsonPrimitive.content, o["smoke"]!!.jsonPrimitive.boolean, o["mat"]!!.jsonPrimitive.boolean, numbers)
            }
        }
    }

    private fun named(m: String) = Moment.Named(m)
    private val live = Moment.Live(Instant.parse("2026-06-21T10:00:00Z"), null)

    /** G1's cases (plan-gpu D8), with its names. */
    private fun cases(): List<Case> {
        val out = ArrayList<Case>()
        val s1 = AnchorCases.Size.S1
        // the Westerkerk home: four moments at S1, the live moment, and S2 and S3
        for (m in AnchorCases.MOMENTS) out.add(Case("home $m S1", false, s1, { AnchorCases.homeView(it, s1.frameHeight) }, named(m), m == "sunny" || m == "night"))
        out.add(Case("home live S1", false, s1, { AnchorCases.homeView(it, s1.frameHeight) }, live, false))
        for (size in listOf(AnchorCases.Size.S2, AnchorCases.Size.S3)) {
            for (m in AnchorCases.MOMENTS) out.add(Case("home $m $size", false, size, { AnchorCases.homeView(it, size.frameHeight) }, named(m), false))
            out.add(Case("home live $size", false, size, { AnchorCases.homeView(it, size.frameHeight) }, live, false))
        }
        // the sweeps: 24 cameras × four moments at S1, 8 × sunny and night at S2 and S3
        for (k in 0 until 24) for (m in AnchorCases.MOMENTS) {
            out.add(Case("sweep $k $m S1", false, s1, { AnchorCases.sweep(24, it.bbox!!, s1.frameHeight)[k] }, named(m), k < 2 && m == "sunny"))
        }
        for (size in listOf(AnchorCases.Size.S2, AnchorCases.Size.S3)) for (k in 0 until 8) for (m in listOf("sunny", "night")) {
            out.add(Case("sweep $k $m $size", false, size, { AnchorCases.sweep(8, it.bbox!!, size.frameHeight)[k] }, named(m), false))
        }
        // the canal ring: the Main board × four moments, 8 cameras × sunny and night, at S1
        for (m in AnchorCases.MOMENTS) out.add(Case("Main board $m S1", true, s1, { AnchorCases.boardView(it, s1.frameHeight) }, named(m), m == "sunny"))
        for (k in 0 until 8) for (m in listOf("sunny", "night")) {
            out.add(Case("canal sweep $k $m S1", true, s1, { AnchorCases.sweep(8, it.bbox!!, s1.frameHeight)[k] }, named(m), false))
        }
        return out
    }

    // ------------------------------------------------------------------ the oracle's side

    /**
     * The one thread this test uses Android's graphics on, also under [Frames.skia] (which K4's oracle holds):
     * Robolectric's native graphics are not safe from two threads at once (K4 saw crashes in `copyPixelsToBuffer`). The
     * references and the text and pattern tiles are drawn here, one at a time and always on the same thread; RefRaster
     * and the filters' own code are plain Kotlin and run on the workers.
     */
    private val skiaThread = Executors.newSingleThreadExecutor { r -> Thread(r, SKIA_THREAD).apply { isDaemon = true } }

    /** [block] on the Skia thread (inline when already there), its exception rethrown here. */
    private fun <T> skia(block: () -> T): T {
        if (Thread.currentThread().name == SKIA_THREAD) return synchronized(Frames.skia) { block() }
        val f = skiaThread.submit(Callable { synchronized(Frames.skia) { block() } })
        try {
            return f.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    @After
    fun stopSkia() {
        skiaThread.shutdownNow()
    }

    /** A layer's text drawn at silhouettes: without its generator-only elements, every filter but `mat` drawn as absent. */
    private fun matDoc(text: String): SvgDocument {
        val d = Frames.withoutGenerators(SvgParser.parse(text))
        fun visit(n: SvgNode) {
            if (n.filter != null && n.filter!!.id != MAT) n.filter = null
            if (n is SvgGroup) for (c in n.children) visit(c)
        }
        visit(d.root)
        return d
    }

    /**
     * `mat` on a group RefRaster drew alone, with the port's own filter code exactly as `SvgCanvas` runs it for the
     * reference: the filter region in user space, at the reference canvas's scale (the density as a float, K4's
     * [Frames]), the result over the group's box. When [silhouettes] is given, the objects layer's pixels the group
     * covers in part are marked in it (a frame-sized mask).
     */
    class MatFilter(density: Double, private val wPx: Int, private val silhouettes: BooleanArray? = null) : GroupFilter {
        private val scale = density.toFloat().toDouble()

        override fun applies(filter: AnchorFilter) = filter.filter.id == MAT

        override fun apply(filter: AnchorFilter, layer: LayerKind?, px: IntArray, x0: Int, y0: Int, w: Int, h: Int): IntArray {
            val f = filter.filter
            check(f.units == SvgUnits.USER_SPACE) { "mat's region is in user space" }
            if (silhouettes != null && layer == LayerKind.OBJECTS) {
                for (i in px.indices) if ((px[i] ushr 24) in 1..254) silhouettes[(y0 + i / w) * wPx + x0 + i % w] = true
            }
            val r = SvgFilterRaster(f, null)
            val region = DoubleArray(4) { f.region[it].toDouble() }
            val out = IntArray(w * h)
            if (!r.prepare(region, scale, scale, SvgFilterRaster.Rect(x0, y0, x0 + w, y0 + h))) return out
            val sr = r.sourceRect
            val src = IntArray(sr.width * sr.height)
            for (y in max(sr.top, y0) until min(sr.bottom, y0 + h)) for (x in max(sr.left, x0) until min(sr.right, x0 + w)) {
                src[(y - sr.top) * sr.width + (x - sr.left)] = px[(y - y0) * w + (x - x0)]
            }
            val o = r.outputRect
            val res = IntArray(o.width * o.height)
            r.run(src, res, 1)
            for (y in max(o.top, y0) until min(o.bottom, y0 + h)) for (x in max(o.left, x0) until min(o.right, x0 + w)) {
                out[(y - y0) * w + (x - x0)] = res[(y - o.top) * o.width + (x - o.left)]
            }
            return out
        }
    }

    /** Text and pattern tiles drawn by the device's own Skia (on the Skia thread), as the GPU path makes them; kept for the case. */
    inner class SkiaTiles : TileSource {
        private val texts = HashMap<Int, Tile?>()
        private val patterns = HashMap<Pair<Int, Double>, Tile?>()

        override fun text(anchor: Anchor, i: Int, density: Double): Tile? = texts.getOrPut(i) {
            skia {
                val fonts = Likeness.fonts
                val t = anchor.texts[i]
                val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
                p.typeface = fonts.typeface(t.family, t.weight, t.italic)
                p.fontVariationSettings = fonts.variation(t.family, t.weight)
                p.textSize = t.size
                p.letterSpacing = if (t.size > 0f) t.letterSpacing / t.size else 0f
                p.textAlign = when (t.anchor) { 1 -> Paint.Align.CENTER; 2 -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
                val c = anchor.paints.argb[t.paint]
                val a = ((c ushr 24) / 255f) * anchor.paints.opacity[t.paint]
                p.setColor(Color.pack(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, a))
                val m = Matrix()
                val tm = t.transform
                m.setValues(floatArrayOf(tm[0], tm[2], tm[4], tm[1], tm[3], tm[5], 0f, 0f, 1f))
                m.postScale(density.toFloat(), density.toFloat())
                val path = Path()
                p.getTextPath(t.text, 0, t.text.length, t.x, t.y, path)
                path.transform(m)
                val b = RectF()
                path.computeBounds(b, true)
                if (b.isEmpty) return@skia null
                val x0 = floor(b.left).toInt() - 2; val y0 = floor(b.top).toInt() - 2
                val w = ceil(b.right).toInt() + 2 - x0; val h = ceil(b.bottom).toInt() + 2 - y0
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val cv = Canvas(bmp)
                cv.translate(-x0.toFloat(), -y0.toFloat())
                cv.concat(m)
                cv.drawText(t.text, t.x, t.y, p)
                Tile(x0, y0, w, h, SvgCanvas.premultipliedPixels(bmp)).also { bmp.recycle() }
            }
        }

        override fun pattern(anchor: Anchor, i: Int, pxPerUnit: Double): Tile? = patterns.getOrPut(i to pxPerUnit) {
            skia {
                val doc = SvgParser.parse(anchor.patterns[i].svg)
                val tw = max(1, ceil(doc.width * pxPerUnit - 1e-3).toInt()); val th = max(1, ceil(doc.height * pxPerUnit - 1e-3).toInt())
                val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                SvgCanvas(doc, Likeness.fonts, SvgCanvas.FilterMode.NONE, 1, null).draw(Canvas(bmp), tw.toFloat(), th.toFloat())
                Tile(0, 0, tw, th, SvgCanvas.premultipliedPixels(bmp)).also { bmp.recycle() }
            }
        }
    }

    // ------------------------------------------------------------------ measuring

    /** The port's renders and the compiler, one at a time (the port's module state is shared). */
    private val port = Any()

    /** Renders that failed, with why. */
    private val failed = ConcurrentLinkedQueue<String>()

    /** Whether the heap can hold the canal ring at night (N13: about 7 GB). */
    private val nightHeap = Runtime.getRuntime().maxMemory() >= 6_800_000_000L

    /**
     * Whether every pack must be there (`-Datlas.likeness.require=true`, as K4's harness takes it, or CI, which fetches
     * the canal ring's pack before the likeness steps): a case skipped for want of its data once hid what CI measures.
     */
    private val requirePacks = System.getProperty("atlas.likeness.require") == "true" || System.getenv("CI") == "true"

    /**
     * One case measured in [variants] (hyb4 always among them: its coverage marks the silhouettes) and both modes, or null
     * when its pack is missing or its render failed.
     */
    private fun measure(c: Case, variants: List<Variant> = Variant.entries): Record? {
        require(Variant.HYB4 in variants) { "hyb4 marks the silhouettes" }
        val region = (if (c.canal) AnchorCases.grachten() else AnchorCases.westerkerk())
        if (region == null) {
            val why = "${c.name}: skipped: no grachten.atlas at ${AnchorCases.grachtenPack.path}"
            check(!requirePacks) { "$why, and every pack is expected here (CI, or -Datlas.likeness.require=true)" }
            println("calibration $why")
            failed.add(why)
            return null
        }
        if (c.canal && c.name.contains("night") && !nightHeap) {
            failed.add("${c.name}: not rendered: the canal ring at night needs about 7 GB of heap (N13; K18 gives it), this JVM has ${Runtime.getRuntime().maxMemory() / 1_000_000} MB")
            return null
        }
        val made: AnchorCases.Made
        val anchor: Anchor
        synchronized(port) {
            made = try {
                AnchorCases.render(region, c.view(region), c.moment)
            } catch (e: RuntimeException) {
                // the engine's own failure (not the anchor's): reported, and the case left out
                failed.add("${c.name}: the render failed: $e")
                return null
            }
            anchor = AnchorCases.anchor(made)
        }
        val size = FrameSize(c.size.name, c.size.wPx, c.size.hPx, c.size.density)
        val w = size.wPx; val h = size.hPx; val d = size.density
        val tiles = SkiaTiles()
        val numbers = EnumMap<CalMode, Map<Variant, Numbers>>(CalMode::class.java)
        val ref = skia { Frames.draw(made.layers, made.render.raster, size, Mode.STRUCTURAL) }
        numbers[CalMode.STRUCTURAL] = variants.associateWith { v ->
            val img = RefRaster(anchor, d, w, h, tiles, variant = v).render()
            val (flat, edges) = split(img, ref, w, h)
            Numbers(RefMetrics.of(img, ref, w, h), flatMad = flat, edgesMad = edges)
        }
        val matDocs = made.layers.map { matDoc(it) }
        val refMat = skia { Frames.drawDocs(matDocs, made.render.raster, size, Mode.FULL) }
        // hyb4 first: its analytic coverage of the objects group marks the silhouettes every variant is measured over
        val sil = BooleanArray(w * h)
        val order = listOf(Variant.HYB4) + variants.filter { it != Variant.HYB4 }
        val silhouettes = LinkedHashMap<Variant, Numbers>()
        for (v in order) {
            val img = RefRaster(anchor, d, w, h, tiles, variant = v, groupFilter = MatFilter(d, w, if (v == Variant.HYB4) sil else null)).render()
            val share = sil.count { it }.toDouble() / sil.size
            silhouettes[v] = Numbers(RefMetrics.of(img, refMat, w, h), silhouetteMad = RefMetrics.of(img, refMat, w, h, sil).mad, silhouetteShare = share)
        }
        numbers[CalMode.SILHOUETTES] = variants.associateWith { silhouettes.getValue(it) }
        val r = Record(c.name, c.klass, c.smoke, anchor.filters.any { it.filter.id == MAT }, numbers)
        println("calibration ${c.name}:")
        for (mode in CalMode.entries) for (v in variants) println("  ${mode.key} ${v.id}: ${r[mode, v]}")
        return r
    }

    /** [run] measured in [variants] on [workers] threads; [sink] gets each record as it is made. */
    private fun measureAll(run: List<Case>, variants: List<Variant> = Variant.entries, sink: (Record) -> Unit): List<Record> {
        if (run.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(min(workers, run.size)) { r -> Thread(r, "calibration").apply { isDaemon = true } }
        try {
            val futures = run.map { c -> pool.submit<Record?> { measure(c, variants)?.also(sink) } }
            return futures.mapNotNull { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * The MAD over the reference's flat pixels (3 × 3 range ≤ 2 in every channel: what differs there is arithmetic, how
     * colours blend and round) and over the rest (edges: coverage).
     */
    private fun split(a: IntArray, b: IntArray, w: Int, h: Int): Pair<Double, Double> {
        var fs = 0L; var fn = 0L; var es = 0L; var en = 0L
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            var flat = true
            var s = 0
            while (s < 24 && flat) {
                var lo = 255; var hi = 0
                for (dy in -1..1) for (dx in -1..1) { val v = (b[i + dy * w + dx] ushr s) and 0xFF; lo = min(lo, v); hi = max(hi, v) }
                if (hi - lo > 2) flat = false
                s += 8
            }
            var d = 0
            for (q in 0 until 24 step 8) d += abs(((a[i] ushr q) and 0xFF) - ((b[i] ushr q) and 0xFF))
            if (flat) { fs += d; fn += 3 } else { es += d; en += 3 }
        }
        return (if (fn > 0) fs.toDouble() / fn else 0.0) to (if (en > 0) es.toDouble() / en else 0.0)
    }

    // ------------------------------------------------------------------ the summaries, D7's rule and the gates

    /** A class's numbers of one variant in one mode: over its cases, the MAD's median, p90 and worst, and more. */
    class Summary(val cases: Int, val median: Double, val p90: Double, val worst: Double, val within2Median: Double, val within2P10: Double, val flatP90: Double, val second: Double, val third: Double)

    /** Per class, mode and variant, the [Summary] of [records]: at silhouettes, of the cases whose renders draw `mat`. */
    private fun summaries(records: List<Record>): Map<String, Map<CalMode, Map<Variant, Summary>>> =
        listOf(WESTERKERK, CANAL).associateWith { k ->
            val rs = records.filter { it.klass == k }
            CalMode.entries.associateWith { mode ->
                Variant.entries.associateWith { v ->
                    val ns = rs.filter { mode == CalMode.STRUCTURAL || it.mat }.map { it[mode, v] }
                    val mads = ns.map { it.mad }
                    val w2 = ns.map { it.within2 }
                    Summary(
                        ns.size, median(mads), index(mads, 0.9), mads.maxOrNull() ?: Double.NaN, median(w2), index(w2, 0.1), index(ns.map { it.flat }, 0.9),
                        if (mode == CalMode.STRUCTURAL) median(ns.map { it.flatMad }) else median(ns.map { it.silhouetteMad }),
                        if (mode == CalMode.STRUCTURAL) median(ns.map { it.edgesMad }) else median(ns.map { it.silhouetteShare }),
                    )
                }
            }
        }

    /** D7's rule applied to the class medians, with its numbers. */
    class Decision(
        /** Per class and mode: msaa4-standard's median MAD minus hyb4's. */
        val msaaOverHyb4: Map<String, Map<CalMode, Double>>,
        val msaaStays: Boolean,
        /** Per class and mode: the median MAD of the variant otherwise adopted minus all-analytic's. */
        val analyticUnder: Map<String, Map<CalMode, Double>>,
        val analyticWins: Boolean,
        val adopted: Variant,
        /** Per class and mode: msaa2-standard's median MAD minus msaa4-standard's. */
        val twoOverFour: Map<String, Map<CalMode, Double>>,
        /** Configuration B (2x) allowed: within [MARGIN] in both classes, structural. */
        val configurationB: Boolean,
        /** The same, at silhouettes too. */
        val configurationBAtSilhouettes: Boolean,
    )

    private fun decide(s: Map<String, Map<CalMode, Map<Variant, Summary>>>): Decision {
        fun med(k: String, m: CalMode, v: Variant) = s.getValue(k).getValue(m).getValue(v).median
        fun diff(a: Variant, b: Variant) = s.keys.associateWith { k -> CalMode.entries.associateWith { m -> med(k, m, a) - med(k, m, b) } }
        val over = diff(Variant.MSAA4_STANDARD, Variant.HYB4)
        val stays = over.values.all { it.values.all { d -> d <= MARGIN } }
        val base = if (stays) Variant.MSAA4_STANDARD else Variant.HYB4
        val under = diff(base, Variant.ALL_ANALYTIC)
        val wins = under.values.all { it.values.all { d -> d > MARGIN } }
        val two = diff(Variant.MSAA2_STANDARD, Variant.MSAA4_STANDARD)
        return Decision(over, stays, under, wins, if (wins) Variant.ALL_ANALYTIC else base, two,
            two.values.all { it.getValue(CalMode.STRUCTURAL) <= MARGIN }, two.values.all { it.values.all { d -> d <= MARGIN } })
    }

    // ------------------------------------------------------------------ the tests

    @Test
    fun `RefRaster's variants against the exact raster, structural and at silhouettes, over G1's cases`() {
        AnchorCases.setUp()
        val all = cases()
        when {
            calibrate -> calibrateAll(if (only == null) all else all.filter { c -> only.any { c.name.contains(it) } })
            recordArch -> recordArch(all)
            else -> checkSmoke(all)
        }
    }

    /** Measures every case (resuming from `ATLAS_CALIBRATE_RECORDS`) and writes likeness.json. */
    private fun calibrateAll(all: List<Case>) {
        val records = System.getenv("ATLAS_CALIBRATE_RECORDS")?.takeIf { it.isNotBlank() }?.let { File(it) }
        val done = LinkedHashMap<String, Record>()
        records?.takeIf { it.isFile }?.forEachLine { l -> if (l.isNotBlank()) Record.parse(Json.parseToJsonElement(l).jsonObject).let { done[it.name] = it } }
        if (done.isNotEmpty()) println("calibration: ${done.size} cases already measured in ${records!!.path}")
        val lock = Any()
        val made = measureAll(all.filter { it.name !in done }) { r -> records?.let { f -> synchronized(lock) { f.appendText(r.json() + "\n") } } }
        val byName = done + made.associateBy { it.name }
        // as likeness.json will hold them (rounded as written), so the file's summaries follow from its own numbers
        val measured = all.mapNotNull { byName[it.name] }.map { Record.parse(Json.parseToJsonElement(it.json()).jsonObject) }
        assertTrue("cases measured", measured.isNotEmpty())
        for (r in measured) if (r.mat && r[CalMode.SILHOUETTES, Variant.HYB4].silhouetteShare == 0.0) println("calibration ${r.name}: mat is drawn, but no silhouette was found")
        val failures = all.filter { it.name !in byName }.map { c -> failed.firstOrNull { it.startsWith("${c.name}: ") } ?: "${c.name}: not measured" }
        if (likeness.isFile && likeness.readText().contains("\"arches\"")) println("calibration: the other architectures' smoke numbers in ${likeness.path} were of the last calibration, and are left out: record them again (ATLAS_CALIBRATE=arch)")
        write(measured, failures, emptyMap())
    }

    /** The calibration as likeness.json holds it: its records, the renders that failed, and each other architecture's smoke records. */
    private class Recorded(val file: JsonObject) {
        val arch: String? = file["arch"]?.jsonPrimitive?.content
        val records: List<Record> = file["cases"]!!.jsonArray.map { Record.parse(it.jsonObject) }
        val failures: List<String> = file["renders that failed"]!!.jsonArray.map { it.jsonPrimitive.content }
        val arches: Map<String, List<Record>> = file["arches"]?.jsonObject?.mapValues { (_, a) -> a.jsonObject["cases"]!!.jsonArray.map { Record.parse(it.jsonObject) } } ?: emptyMap()
    }

    private fun recorded(): Recorded {
        assertTrue("likeness.json is there (run with -Datlas.calibrate=true to make it)", likeness.isFile)
        val file = Json.parseToJsonElement(likeness.readText()).jsonObject
        assertEquals("likeness.json is K6's format (run with -Datlas.calibrate=true to make it)", 2, file["format"]?.jsonPrimitive?.int)
        return Recorded(file)
    }

    /**
     * This architecture's own numbers for the smoke cases and the boards' cases (those thresholds.json gives values of
     * their own), in every variant and both modes, kept in likeness.json beside the calibration's (made on another
     * architecture): the check holds this one to them, and the gates block gives this architecture's values from them.
     */
    private fun recordArch(all: List<Case>) {
        val rec = recorded()
        assertTrue("the calibration itself was made on $ARCH: there is nothing to add", rec.arch != ARCH)
        record(all, rec, out)
    }

    /** Measures this architecture's own cases in every variant and both modes, and writes likeness.json with them to [to]. */
    private fun record(all: List<Case>, rec: Recorded, to: File): List<Record> {
        // as likeness.json will hold them (rounded as written)
        val measured = measureAll(all.filter { it.smoke || BOARD_CASE.matches(it.name) }) { }.map { Record.parse(Json.parseToJsonElement(it.json()).jsonObject) }
        assertTrue("cases measured", measured.isNotEmpty())
        write(rec.records, rec.failures, rec.arches + (ARCH to measured), recalibrated = false, to = to)
        for (r in measured) println("calibration record $ARCH: ${r.json()}")
        println("calibration: $ARCH's own numbers (${measured.size} cases) written with the calibration to ${to.path}")
        return measured
    }

    /**
     * The smoke cases measured again in hyb4 and the adopted variant (both modes), each held to this architecture's own
     * recorded numbers: the calibration's when it was made here, else the ones recorded for this architecture under
     * `arches` (Skia's arithmetic is the architecture's: CI's x86 Skia measured hyb4 0.18–0.36 MAD above the Mac's ARM one
     * on every smoke case, run 38953006189). An architecture with no numbers recorded yet has them measured and written,
     * with the calibration, to `ATLAS_CALIBRATE_OUT`, else on CI into the `likeness` artifact's folder
     * (`$GITHUB_WORKSPACE/likeness/likeness-<arch>.json`), ready to replace likeness.json; they are held only to a sanity
     * bound of the calibration's, [SANITY_MAD] and [SANITY_WITHIN2], which no Skia build's arithmetic comes near.
     */
    private fun checkSmoke(all: List<Case>) {
        val rec = recorded()
        val file = rec.file
        // the decision recorded follows from the recorded numbers
        val again = decide(summaries(rec.records))
        val dec = file["decision"]!!.jsonObject
        assertEquals("the adopted variant follows D7's rule from the recorded medians", again.adopted.id, dec["adopted"]!!.jsonPrimitive.content)
        assertEquals("configuration B's answer follows from the recorded medians", again.configurationB, dec["configurationB"]!!.jsonObject["allowed"]!!.jsonPrimitive.boolean)
        val checked = listOf(Variant.HYB4, again.adopted).distinct()
        val own = if (rec.arch == ARCH) rec.records else rec.arches[ARCH]
        if (own == null) {
            // not recorded here yet: recorded now, where CI keeps it, and held to the sanity bound only
            val to = System.getenv("ATLAS_CALIBRATE_OUT")?.takeIf { it.isNotBlank() }?.let { File(it) }
                ?: System.getenv("GITHUB_WORKSPACE")?.takeIf { it.isNotBlank() }?.let { File(it, "likeness/likeness-$ARCH.json") }
                ?: File(System.getProperty("java.io.tmpdir"), "likeness-$ARCH.json")
            println("calibration: $ARCH has no numbers of its own in likeness.json (calibrated on ${rec.arch}): measuring and recording them")
            val base = rec.records.associateBy { it.name }
            for (r in record(all, rec, to)) for (mode in CalMode.entries) for (v in checked) {
                val m = r[mode, v]; val c = base[r.name]?.get(mode, v) ?: continue
                val what = "${r.name}, ${mode.key}, ${v.id} on $ARCH (unrecorded; calibrated on ${rec.arch})"
                assertTrue("$what: MAD ${m.mad} within $SANITY_MAD of the calibration's ${c.mad}", m.mad <= c.mad + SANITY_MAD)
                assertTrue("$what: within-2 ${m.within2} within $SANITY_WITHIN2 of the calibration's ${c.within2}", m.within2 >= c.within2 - SANITY_WITHIN2)
            }
            return
        }
        println("calibration: checked against ${if (rec.arch == ARCH) "the calibration ($ARCH)" else "$ARCH's own numbers"}")
        val baseline = own.associateBy { it.name }
        val measured = measureAll(all.filter { it.smoke }, checked) { }
        assertTrue("cases measured", measured.isNotEmpty())
        for (r in measured) {
            val base = baseline[r.name]
            assertTrue("${r.name} is in likeness.json for $ARCH", base != null)
            for (mode in CalMode.entries) for (v in checked) {
                val m = r[mode, v]; val c = base!![mode, v]
                val what = "${r.name}, ${mode.key}, ${v.id}"
                assertTrue("$what: MAD ${m.mad} as recorded (${c.mad})", m.mad <= c.mad + 0.15)
                assertTrue("$what: within-2 ${m.within2} as recorded (${c.within2})", m.within2 >= c.within2 - 0.015)
                assertTrue("$what: p99 ${m.p99} as recorded (${c.p99})", m.p99 <= c.p99 + 3)
                assertTrue("$what: block ${m.block} as recorded (${c.block})", m.block <= c.block + 1.5)
                assertTrue("$what: flat misses ${m.flat} as recorded (${c.flat})", m.flat <= c.flat * 3 + 1e-5)
            }
        }
        println("calibration: ${measured.size} smoke cases measure as recorded on $ARCH; adopted ${again.adopted.id}, configuration B ${if (again.configurationB) "allowed" else "not allowed"}")
    }

    /**
     * likeness.json: the variants, the class summaries, D7's decision with its numbers, the gates' values, every case, and
     * other architectures' smoke numbers ([arches]).
     */
    private fun write(measured: List<Record>, failures: List<String>, arches: Map<String, List<Record>>, recalibrated: Boolean = true, to: File = out) {
        val s = summaries(measured)
        val dec = decide(s)
        fun f4(v: Double) = if (v.isNaN()) "null" else String.format(Locale.ROOT, "%.4f", v)
        fun f5(v: Double) = if (v.isNaN()) "null" else String.format(Locale.ROOT, "%.5f", v)
        fun f8(v: Double) = if (v.isNaN()) "null" else String.format(Locale.ROOT, "%.8f", v)
        fun q(t: String) = "\"" + t.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        fun perClassMode(m: Map<String, Map<CalMode, Double>>) = "{" + m.entries.joinToString(", ") { (k, v) -> "${q(k)}: {" + v.entries.joinToString(", ") { (mode, d) -> "${q(mode.key)}: ${f4(d)}" } + "}" } + "}"
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"about\": ").append(q("plan-vector K6 (D7): RefRaster, the GPU's coverage rules emulated, in four variants against Android's Skia (SvgCanvas through K4's Frames: the port's layers at the case's density, no view-box fit, cropped), over G1's cases (plan-gpu D8). Structural: filters off on both sides, generator-only elements left out. Silhouettes: the same with mat drawn on both sides as the renders run it, so its coverage rule thins each variant's own coverage of its group (RefRaster's GroupFilter running the port's SvgFilterRaster); silhouetteMad is the MAD over the pixels hyb4's objects group covers in part (silhouetteShare of the frame); the classes at silhouettes take the cases whose renders draw mat ('mat': true; the named night draws none). Written by RefRasterCalibrationTest with -Datlas.calibrate=true.")).append(",\n")
        sb.append("  \"format\": 2,\n")
        sb.append("  \"arch\": ").append(q(if (recalibrated) ARCH else Recorded(Json.parseToJsonElement(likeness.readText()).jsonObject).arch!!)).append(",\n")
        sb.append("  \"variants\": {\n")
        sb.append(Variant.entries.joinToString(",\n") { v -> "    ${q(v.id)}: ${q(VARIANT_ABOUT.getValue(v))}" }).append("\n  },\n")
        sb.append("  \"renders that failed\": [").append(failures.joinToString(", ") { q(it) }).append("],\n")
        // the classes
        sb.append("  \"classes\": {\n")
        sb.append(s.entries.joinToString(",\n") { (k, byMode) ->
            "    ${q(k)}: {\n" + byMode.entries.joinToString(",\n") { (mode, byVariant) ->
                "      ${q(mode.key)}: {\"cases\": ${byVariant.getValue(Variant.HYB4).cases},\n" + byVariant.entries.joinToString(",\n") { (v, x) ->
                    val extra = if (mode == CalMode.STRUCTURAL) "\"flatMadMedian\": ${f4(x.second)}, \"edgesMadMedian\": ${f4(x.third)}" else "\"silhouetteMadMedian\": ${f4(x.second)}, \"silhouetteShareMedian\": ${f5(x.third)}"
                    "        ${q(v.id)}: {\"median\": ${f4(x.median)}, \"p90\": ${f4(x.p90)}, \"worst\": ${f4(x.worst)}, \"within2Median\": ${f5(x.within2Median)}, \"within2P10\": ${f5(x.within2P10)}, \"flatP90\": ${f8(x.flatP90)}, $extra}"
                } + "\n      }"
            } + "}"
        }).append("\n  },\n")
        // D7's decision
        sb.append("  \"decision\": {\n")
        sb.append("    \"rule\": ").append(q("D7: MSAA (msaa4-standard) stays unless its median MAD exceeds hyb4's by more than $MARGIN in either class, structural or at silhouettes (else FA: analytic faces, as hyb4 draws them); all-analytic is taken only if it wins by a clear margin, its median more than $MARGIN under the variant otherwise adopted in both classes and both modes. Medians over each class's cases.")).append(",\n")
        sb.append("    \"msaa4OverHyb4\": ").append(perClassMode(dec.msaaOverHyb4)).append(",\n")
        sb.append("    \"msaaStays\": ").append(dec.msaaStays).append(",\n")
        sb.append("    \"otherwiseAdoptedOverAllAnalytic\": ").append(perClassMode(dec.analyticUnder)).append(",\n")
        sb.append("    \"allAnalyticWins\": ").append(dec.analyticWins).append(",\n")
        sb.append("    \"adopted\": ").append(q(dec.adopted.id)).append(",\n")
        sb.append("    \"configurationB\": {\"rule\": ").append(q("D5.3: 2x (configuration B) where msaa2-standard is within $MARGIN MAD of msaa4-standard in both classes; else configuration C (4x in ordinary memory)"))
            .append(", \"msaa2OverMsaa4\": ").append(perClassMode(dec.twoOverFour)).append(", \"allowed\": ").append(dec.configurationB)
            .append(", \"allowedAtSilhouettesToo\": ").append(dec.configurationBAtSilhouettes).append("}\n")
        sb.append("  },\n")
        // the gates' values for the adopted variant (D16's final column), and for configuration B's 2x
        sb.append("  \"gates\": {\n")
        sb.append("    \"about\": ").append(q("D16's final (A4) values from the adopted variant's structural numbers: a case's MAD + 0.10 + the lavapipe allowance 0.8, its within-2 share - 1 point - 2 points, its flat misses x 3; a class's values (for cases calibrated here by class only) from its p90 MAD, p10 within-2 and p90 flat misses (index floor(p (n - 1)) of the sorted values, as thresholds.json was first written); the sweeps' median gate the class's median MAD + 0.9, their p90 gate 1.0 above it (D16's spacing); a flat-miss gate never below 0.001 % (plan-gpu D8's structural gate), so a case measured with no flat miss at all does not get a gate of 0. 'adopted' is the calibration's architecture; another architecture's block (named as its os.arch reports it, e.g. amd64 on CI's x86 runners, when its numbers are recorded under 'arches') takes its own numbers for the cases it measured, and for each class the calibration's values moved by the median of what it measures differently there. A phone adds 0.4 MAD and takes 2 points of within-2 (thresholds.json's phoneRule).")).append(",\n")
        val boards = measured.filter { BOARD_CASE.matches(it.name) }
        for ((key, v) in listOf("adopted" to dec.adopted, "configurationB" to Variant.MSAA2_STANDARD)) {
            sb.append("    ").append(q(key)).append(": {\"variant\": ").append(q(v.id)).append(", \"classes\": {")
            sb.append(s.entries.joinToString(", ") { (k, byMode) ->
                val x = byMode.getValue(CalMode.STRUCTURAL).getValue(v)
                "${q(k)}: {\"mad\": ${f4(x.p90 + ALLOWANCE)}, \"within2\": ${f5(x.within2P10 - WITHIN2_ALLOWANCE)}, \"flat\": ${f8(flatGate(x.flatP90))}, \"sweepMedianMad\": ${f4(x.median + ALLOWANCE)}, \"sweepP90Mad\": ${f4(x.median + ALLOWANCE + 1.0)}}"
            })
            sb.append("}, \"cases\": {")
            sb.append(boards.joinToString(", ") { r ->
                val x = r[CalMode.STRUCTURAL, v]
                "${q(r.name)}: {\"mad\": ${f4(x.mad + ALLOWANCE)}, \"within2\": ${f5(x.within2 - WITHIN2_ALLOWANCE)}, \"flat\": ${f8(flatGate(x.flat))}}"
            })
            sb.append("}}").append(if (key == "adopted" || arches.isNotEmpty()) ",\n" else "\n")
        }
        // another architecture: its own numbers for the cases it measured; for the classes, the calibration's moved by
        // the median of what it measured differently (Skia's arithmetic is the architecture's; RefRaster's is not)
        for ((n, entry) in arches.entries.withIndex()) {
            val (a, rs) = entry
            val v = dec.adopted
            val calib = measured.associateBy { it.name }
            val both = rs.filter { it.name in calib && v in it.numbers.getValue(CalMode.STRUCTURAL) }
            // per class: the median of what this architecture measures differently, in the adopted variant, structural
            val dMad = s.keys.associateWith { k -> median(both.filter { it.klass == k }.map { it[CalMode.STRUCTURAL, v].mad - calib.getValue(it.name)[CalMode.STRUCTURAL, v].mad }) }
            val dW2 = s.keys.associateWith { k -> median(both.filter { it.klass == k }.map { it[CalMode.STRUCTURAL, v].within2 - calib.getValue(it.name)[CalMode.STRUCTURAL, v].within2 }) }
            // D7's differences over the same cases, there and on the calibration's architecture
            fun diffs(of: (Record) -> Record, x: Variant, y: Variant) = s.keys.associateWith { k ->
                CalMode.entries.associateWith { m ->
                    median(both.filter { it.klass == k && (m == CalMode.STRUCTURAL || it.mat) && x in it.numbers.getValue(m) && y in it.numbers.getValue(m) }.map { of(it)[m, x].mad - of(it)[m, y].mad })
                }
            }
            val all4 = Variant.entries.all { vv -> both.all { vv in it.numbers.getValue(CalMode.STRUCTURAL) } }
            sb.append("    ").append(q(a)).append(": {\"variant\": ").append(q(v.id)).append(", \"cases measured\": ").append(both.size)
                .append(", \"offsets\": {").append(s.keys.joinToString(", ") { k -> "${q(k)}: {\"mad\": ${f4(dMad.getValue(k))}, \"within2\": ${f5(dW2.getValue(k))}, \"cases\": ${both.count { it.klass == k }}}" }).append("}")
            if (all4) {
                sb.append(", \"msaa4OverHyb4\": {\"here\": ").append(perClassMode(diffs({ it }, Variant.MSAA4_STANDARD, Variant.HYB4)))
                    .append(", \"calibration\": ").append(perClassMode(diffs({ calib.getValue(it.name) }, Variant.MSAA4_STANDARD, Variant.HYB4))).append("}")
                sb.append(", \"msaa2OverMsaa4\": {\"here\": ").append(perClassMode(diffs({ it }, Variant.MSAA2_STANDARD, Variant.MSAA4_STANDARD)))
                    .append(", \"calibration\": ").append(perClassMode(diffs({ calib.getValue(it.name) }, Variant.MSAA2_STANDARD, Variant.MSAA4_STANDARD))).append("}")
            }
            sb.append(", \"classes\": {")
            sb.append(s.entries.joinToString(", ") { (k, byMode) ->
                val x = byMode.getValue(CalMode.STRUCTURAL).getValue(v)
                // no case of the class measured there: no values (null), rather than the calibration's taken for its
                val dm = dMad.getValue(k); val dw = dW2.getValue(k)
                "${q(k)}: {\"mad\": ${f4(x.p90 + ALLOWANCE + dm)}, \"within2\": ${f5(x.within2P10 - WITHIN2_ALLOWANCE + dw)}, \"flat\": ${f8(flatGate(x.flatP90))}, \"sweepMedianMad\": ${f4(x.median + ALLOWANCE + dm)}, \"sweepP90Mad\": ${f4(x.median + ALLOWANCE + dm + 1.0)}}"
            })
            sb.append("}, \"cases\": {")
            sb.append(rs.filter { BOARD_CASE.matches(it.name) && v in it.numbers.getValue(CalMode.STRUCTURAL) }.joinToString(", ") { r ->
                val x = r[CalMode.STRUCTURAL, v]
                "${q(r.name)}: {\"mad\": ${f4(x.mad + ALLOWANCE)}, \"within2\": ${f5(x.within2 - WITHIN2_ALLOWANCE)}, \"flat\": ${f8(flatGate(x.flat))}}"
            })
            sb.append("}}").append(if (n < arches.size - 1) ",\n" else "\n")
        }
        sb.append("  },\n")
        // every case
        sb.append("  \"cases\": [\n")
        sb.append(measured.joinToString(",\n") { "    " + it.json() }).append("\n  ]")
        // other architectures' own smoke numbers (Skia's arithmetic differs between its ARM and x86 builds)
        if (arches.isNotEmpty()) {
            sb.append(",\n  \"arches\": {\n")
            sb.append(arches.entries.joinToString(",\n") { (a, rs) ->
                "    ${q(a)}: {\"about\": ${q("the smoke cases and the boards' cases measured on $a against its own Skia, in every variant and both modes (ATLAS_CALIBRATE=arch); the check on $a holds them to these")}, \"cases\": [\n" +
                    rs.joinToString(",\n") { "      " + it.json() } + "\n    ]}"
            })
            sb.append("\n  }")
        }
        sb.append("\n}\n")
        to.absoluteFile.parentFile.mkdirs()
        to.writeText(sb.toString())
        // what was decided, and with what
        println("wrote ${to.path}: ${measured.size} cases, ${failures.size} not measured")
        for ((k, byMode) in s) for ((mode, byVariant) in byMode) for ((v, x) in byVariant) {
            println(String.format(Locale.ROOT, "  %s %s %s: %d cases, median %.3f, p90 %.3f, worst %.3f, within-2 median %.2f%%", k, mode.key, v.id, x.cases, x.median, x.p90, x.worst, x.within2Median * 100))
        }
        println("  D7: msaa4-standard over hyb4 ${dec.msaaOverHyb4} -> MSAA ${if (dec.msaaStays) "stays" else "goes (FA)"}; all-analytic ${if (dec.analyticWins) "wins" else "does not win"} (${dec.analyticUnder}); adopted ${dec.adopted.id}")
        println("  configuration B: msaa2-standard over msaa4-standard ${dec.twoOverFour} -> ${if (dec.configurationB) "allowed" else "not allowed"}")
    }

    // ------------------------------------------------------------------ the variants on drawn shapes

    private fun col(r: Random, alpha: Boolean): String {
        val c = "#%02X%02X%02X".format(Locale.ROOT, r.nextInt(256), r.nextInt(256), r.nextInt(256))
        return if (alpha && r.nextBoolean()) "$c\" fill-opacity=\"%.2f".format(Locale.ROOT, 0.2 + 0.7 * r.nextDouble()) else c
    }

    private fun svg(w: Int, h: Int, body: String) = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\">$body</svg>"

    /** The ink (darkness) of [px], summed over its pixels' red channels, in pixels of full black. */
    private fun ink(px: IntArray): Double = px.sumOf { 255 - ((it shr 16) and 0xFF) } / 255.0

    @Test
    fun `the variants sample at Vulkan's standard positions, and hyb4's interiors already sit at the 4x ones`() {
        assertArrayEquals(doubleArrayOf(0.375, 0.125, 0.875, 0.375, 0.125, 0.625, 0.625, 0.875), Variant.MSAA4_STANDARD.positions(), 0.0)
        assertArrayEquals(Faces.SAMPLES_4X, Variant.MSAA4_STANDARD.positions(), 0.0)
        assertArrayEquals(Faces.SAMPLES_4X, Variant.HYB4.positions(), 0.0)
        assertArrayEquals(doubleArrayOf(0.75, 0.75, 0.25, 0.25), Variant.MSAA2_STANDARD.positions(), 0.0)
        assertArrayEquals(doubleArrayOf(0.5, 0.5), Variant.ALL_ANALYTIC.positions(), 0.0)
        for (v in Variant.entries) assertEquals(v, Variant.of(v.id))
        assertEquals(listOf("hyb4", "msaa4-standard", "msaa2-standard", "all-analytic"), Variant.entries.map { it.id })
    }

    @Test
    fun `with MSAA the two passes with depth equal strict painter's order on 2,000 overlapping shapes`() {
        val r = Random(2000)
        val sb = StringBuilder()
        repeat(2000) {
            val x = r.nextDouble() * 280 + 10; val y = r.nextDouble() * 280 + 10; val s = 3 + r.nextDouble() * 40
            when (r.nextInt(6)) {
                0 -> sb.append("<path fill=\"${col(r, true)}\" d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fZ\"/>".format(Locale.ROOT, x, y, x + s, y + s * 0.2, x + s * 0.9, y + s, x - s * 0.1, y + s * 0.8))
                1 -> sb.append("<path fill=\"${col(r, true)}\" d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fZ\"/>".format(Locale.ROOT, x, y, x + s, y, x + s * 0.4, y + s * 0.4, x + s, y + s, x, y + s, x + s * 0.3, y + s * 0.5))
                2 -> sb.append("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"${col(r, true)}\"/>".format(Locale.ROOT, x, y, s / 3))
                3 -> sb.append("<path fill=\"none\" stroke=\"${col(r, false)}\" stroke-width=\"%.2f\" stroke-linejoin=\"%s\" d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1f\"/>".format(Locale.ROOT, 0.3 + r.nextDouble() * 3, listOf("round", "miter", "bevel")[r.nextInt(3)], x, y, x + s, y + s * 0.3, x + s * 0.2, y + s))
                4 -> sb.append("<g opacity=\".6\"><path fill=\"${col(r, false)}\" d=\"M%.1f %.1fh%.1fv%.1fh-%.1fZ\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"${col(r, false)}\"/></g>".format(Locale.ROOT, x, y, s, s, s, x + s, y + s, s / 2))
                else -> sb.append("<path fill=\"${col(r, true)}\" fill-rule=\"evenodd\" d=\"M%.1f %.1fh%.1fv%.1fh-%.1fZ M%.1f %.1fh%.1fv%.1fh-%.1fZ\"/>".format(Locale.ROOT, x, y, s, s, s, x + s * 0.25, y + s * 0.25, s * 0.5, s * 0.5, s * 0.5))
            }
        }
        val a = AnchorCases.synthetic(svg(300, 300, sb.toString()))
        val k = 2.625
        val n = (300 * k).toInt()
        for (v in listOf(Variant.MSAA4_STANDARD, Variant.MSAA2_STANDARD)) {
            val two = RefRaster(a, k, n, n, variant = v).render()
            val strict = RefRaster(a, k, n, n, twoPass = false, variant = v).render()
            assertArrayEquals("${v.id}: depth with two passes equals strict painter's order", strict, two)
        }
    }

    @Test
    fun `MSAA covers an area's samples, all-analytic and hyb4 its area`() {
        // a black rectangle on white with every edge between samples, at density 1
        val a = AnchorCases.synthetic(svg(40, 40, "<path fill=\"#000000\" d=\"M10.3 10.1L20.6 10.1L20.6 30.7L10.3 30.7Z\"/>"))
        val area = 10.3 * 20.6
        val cover = HashMap<Variant, IntArray>()
        for (v in Variant.entries) {
            val px = RefRaster(a, 1.0, 40, 40, variant = v).render()
            cover[v] = px
            val got = ink(px)
            // MSAA counts samples: each edge pixel's share is off by at most half a sample's step
            val tol = when (v) { Variant.MSAA2_STANDARD -> 0.06; Variant.MSAA4_STANDARD -> 0.03; else -> 0.005 }
            assertEquals("${v.id}: the rectangle's ink is its area", area, got, area * tol)
        }
        // the pixel at (10, 15) is covered from x 10.3: the 4x samples at x + 0.375, 0.875, 0.625 (3 of 4), the 2x ones at 0.75 (1 of 2)
        fun level(v: Variant, x: Int, y: Int) = 255 - ((cover.getValue(v)[y * 40 + x] shr 16) and 0xFF)
        // (the resolve rounds half up: 1 white sample of 4 is red (255 + 2) / 4 = 64, 1 of 2 is (255 + 1) / 2 = 128)
        assertEquals(191, level(Variant.MSAA4_STANDARD, 10, 15))
        assertEquals(127, level(Variant.MSAA2_STANDARD, 10, 15))
        // hyb4's and all-analytic's coverage at the pixel's centre: 0.5 + 0.2 of the pixel inside, white 255 × 0.3 rounded up
        assertEquals(178, level(Variant.HYB4, 10, 15))
        assertEquals(178, level(Variant.ALL_ANALYTIC, 10, 15))
    }

    @Test
    fun `under MSAA a fill cut into faces shares its samples out, with no seam along its cuts`() {
        // a translucent concave fill (cut into faces with internal edges) over white: its inside is one colour
        val a = AnchorCases.synthetic(svg(60, 60, "<path fill=\"#204080\" fill-opacity=\".5\" d=\"M5.2 5.3L50.4 5.3L28.1 27.6L50.7 50.2L5.1 50.6Z\"/>"))
        assertTrue("the fill is cut into faces", a.head.primCounts[PrimKind.FACE.toInt()] >= 2)
        for (v in listOf(Variant.MSAA4_STANDARD, Variant.MSAA2_STANDARD)) {
            val px = RefRaster(a, 1.0, 60, 60, variant = v).render()
            val inside = px[20 * 60 + 12]
            var interior = 0
            for (y in 7 until 49) for (x in 7 until 26) { interior++; assertEquals("${v.id}: pixel $x, $y", inside, px[y * 60 + x]) }
            assertTrue(interior > 500)
        }
    }

    @Test
    fun `MSAA draws strokes from 2 device px as ribbons over their samples, thinner ones as hyb4 does`() {
        val thin = AnchorCases.synthetic(svg(80, 80, "<path fill=\"none\" stroke=\"#000000\" stroke-width=\"1.5\" stroke-linejoin=\"round\" d=\"M10.2 20.4L60.7 22.9L40.3 60.1\"/><circle cx=\"30\" cy=\"40\" r=\"6\" fill=\"none\" stroke=\"#000000\" stroke-width=\"1.2\"/>"))
        assertArrayEquals("strokes under 2 px: G1's coverage in every variant", RefRaster(thin, 1.0, 80, 80).render(), RefRaster(thin, 1.0, 80, 80, variant = Variant.MSAA4_STANDARD).render())
        val wide = AnchorCases.synthetic(svg(80, 80, "<path fill=\"none\" stroke=\"#000000\" stroke-width=\"3\" stroke-linecap=\"butt\" d=\"M10.2 20.4L60.7 20.4\"/>"))
        val hyb = RefRaster(wide, 1.0, 80, 80).render()
        for (v in listOf(Variant.MSAA4_STANDARD, Variant.MSAA2_STANDARD)) {
            val px = RefRaster(wide, 1.0, 80, 80, variant = v).render()
            assertEquals("${v.id}: a 3 px stroke's ink is its area", 50.5 * 3, ink(px), 50.5 * 3 * 0.04)
            // every level is a whole number of samples
            val steps = if (v == Variant.MSAA4_STANDARD) setOf(0, 64, 127, 191, 255) else setOf(0, 127, 255)
            for (p in px) assertTrue("${v.id}: level ${255 - ((p shr 16) and 0xFF)} is a whole number of samples", (255 - ((p shr 16) and 0xFF)) in steps)
        }
        assertEquals("hyb4: the stroke's exact area", 50.5 * 3, ink(hyb), 50.5 * 3 * 0.01)
    }

    @Test
    fun `all-analytic measures stencilled fills and clips by their area`() {
        // two squares crossing in one path, even-odd: Earcut cannot cut it, so it goes through the stencil
        val body = "<path fill=\"#000000\" fill-rule=\"evenodd\" d=\"M10.3 10.2L30.6 10.2L30.6 30.7L10.3 30.7Z M20.1 20.4L40.8 20.4L40.8 40.5L20.1 40.5Z\"/>"
        val a = AnchorCases.synthetic(svg(50, 50, body))
        assertTrue("the fill is stencilled", a.head.primCounts[PrimKind.STENCIL.toInt()] >= 1)
        val sq1 = 20.3 * 20.5; val sq2 = 20.7 * 20.1; val overlap = (30.6 - 20.1) * (30.7 - 20.4)
        val area = sq1 + sq2 - 2 * overlap
        for (v in Variant.entries) {
            val tol = when (v) { Variant.ALL_ANALYTIC -> 0.015; Variant.MSAA2_STANDARD -> 0.07; else -> 0.04 }
            assertEquals("${v.id}: the even-odd fill's ink is its area", area, ink(RefRaster(a, 1.0, 50, 50, variant = v).render()), area * tol)
        }
        // a clip path: a rectangle cut by a circle (stencilled clip)
        val clipped = AnchorCases.synthetic(svg(50, 50, "<defs><clipPath id=\"c\"><circle cx=\"25.3\" cy=\"24.8\" r=\"12.4\"/></clipPath></defs><g clip-path=\"url(#c)\"><path fill=\"#000000\" d=\"M5.2 5.1L45.3 5.1L45.3 45.2L5.2 45.2Z\"/></g>"))
        val disc = Math.PI * 12.4 * 12.4
        assertEquals("all-analytic: the clipped ink is the clip's area", disc, ink(RefRaster(clipped, 1.0, 50, 50, variant = Variant.ALL_ANALYTIC).render()), disc * 0.015)
    }

    /** A recording group filter: the alpha levels of the group it was handed, the group returned as it is. */
    private class Levels : GroupFilter {
        val alphas = HashSet<Int>()
        override fun applies(filter: AnchorFilter) = filter.filter.id == "probe"
        override fun apply(filter: AnchorFilter, layer: LayerKind?, px: IntArray, x0: Int, y0: Int, w: Int, h: Int): IntArray {
            for (p in px) alphas.add(p ushr 24)
            return px
        }
    }

    @Test
    fun `a group filter is handed each variant's coverage of its group, in quarters, halves, or analytic`() {
        val body = "<defs><filter id=\"probe\" x=\"0\" y=\"0\" width=\"1\" height=\"1\"><feComposite in=\"SourceGraphic\" in2=\"SourceAlpha\" operator=\"in\"/></filter></defs>" +
            "<g filter=\"url(#probe)\"><circle cx=\"30.3\" cy=\"29.6\" r=\"17.2\" fill=\"#7A5C3E\"/><path fill=\"#3E5C7A\" d=\"M8.3 50.2L31.6 44.1L52.7 55.4Z\"/></g>"
        val a = AnchorCases.synthetic(svg(60, 60, body))
        val expected = mapOf(Variant.MSAA4_STANDARD to setOf(0, 64, 128, 191, 255), Variant.MSAA2_STANDARD to setOf(0, 128, 255))
        val plain = RefRaster(a, 1.0, 60, 60).render()
        for (v in Variant.entries) {
            val levels = Levels()
            val px = RefRaster(a, 1.0, 60, 60, variant = v, groupFilter = levels).render()
            assertTrue("${v.id}: the group was handed over", levels.alphas.isNotEmpty())
            expected[v]?.let { assertTrue("${v.id}: alphas ${levels.alphas.sorted()} are whole numbers of samples", it.containsAll(levels.alphas)) }
            if (v == Variant.HYB4 || v == Variant.ALL_ANALYTIC) assertTrue("${v.id}: an analytic coverage has many levels (${levels.alphas.size})", levels.alphas.size > 20)
            if (v == Variant.HYB4) assertTrue("hyb4: the group drawn alone and composited, as drawn in place but for rounding (${RefMetrics.of(px, plain, 60, 60)})", RefMetrics.of(px, plain, 60, 60).mad < 0.2)
        }
    }

    @Test
    fun `mat at silhouettes runs the port's own filter where the renders run it`() {
        // the renders' mat filter on a group of shapes: RefRaster with MatFilter against Skia's SvgCanvas running every filter
        val w = 160; val h = 120
        val defs = "<defs><filter id=\"mat\" filterUnits=\"userSpaceOnUse\" x=\"0\" y=\"0\" width=\"$w\" height=\"$h\"><feTurbulence type=\"fractalNoise\" baseFrequency=\".16 .75\" numOctaves=\"2\" seed=\"13\"/>" +
            "<feColorMatrix type=\"matrix\" values=\".33 .33 .33 0 0  .33 .33 .33 0 0  .33 .33 .33 0 0  0 0 0 0 1\" result=\"g\"/><feComposite in=\"SourceGraphic\" in2=\"g\" operator=\"arithmetic\" k1=\".2\" k2=\".9\" k3=\"0\" k4=\"0\" result=\"t\"/>" +
            "<feComposite in=\"t\" in2=\"SourceAlpha\" operator=\"in\"/></filter></defs>"
        val r = Random(7)
        val sb = StringBuilder("<rect width=\"$w\" height=\"$h\" fill=\"#D8C9A8\"/><g filter=\"url(#mat)\">")
        repeat(40) {
            val x = r.nextDouble() * 140 + 5; val y = r.nextDouble() * 100 + 5; val s = 4 + r.nextDouble() * 18
            sb.append("<path fill=\"${col(r, false)}\" d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fZ\"/>".format(Locale.ROOT, x, y, x + s, y + s * 0.3, x + s * 0.8, y + s, x - s * 0.2, y + s * 0.7))
        }
        sb.append("</g>")
        val text = svg(w, h, defs + sb)
        val d = 2.6214
        val size = FrameSize("probe", (w * d).toInt(), (h * d).toInt(), d)
        val ref = skia { Frames.drawDocs(listOf(SvgParser.parse(text)), null, size, Mode.FULL) }
        val structural = skia { Frames.drawDocs(listOf(SvgParser.parse(text)), null, size, Mode.STRUCTURAL) }
        val a = AnchorCases.synthetic(text)
        val sil = BooleanArray(size.wPx * size.hPx)
        val img = RefRaster(a, d, size.wPx, size.hPx, variant = Variant.HYB4, groupFilter = MatFilter(d, size.wPx, sil)).render()
        val plain = RefRaster(a, d, size.wPx, size.hPx).render()
        val withMat = RefMetrics.of(img, ref, size.wPx, size.hPx)
        val without = RefMetrics.of(plain, structural, size.wPx, size.hPx)
        val noise = RefMetrics.of(img, plain, size.wPx, size.hPx)
        println("mat probe: hyb4 with mat against Skia with mat $withMat; structural $without; RefRaster with mat against without $noise")
        // the noise lines up: mat on both sides measures as close as filters off on both (Skia's own arithmetic, which
        // is the architecture's, is in both); misplaced, its amplitude would show
        assertTrue("mat applied where the renders apply it: $withMat against structural $without", withMat.mad <= without.mad + 0.3)
        assertTrue("mat's noise is there to line up: $noise", noise.mad > 0.4)
    }

    companion object {
        private const val WESTERKERK = "westerkerk"
        private const val CANAL = "canal"
        private const val MAT = "mat"
        private const val SKIA_THREAD = "calibration-skia"

        /** The boards' cases thresholds.json gives values of their own (D16 row 3): the home and the Main board at S1. */
        private val BOARD_CASE = Regex("(home|Main board) (sunny|grey|wet|night) S1")

        /** This JVM's architecture: Skia's arithmetic differs between its ARM and x86 builds. */
        private val ARCH: String = System.getProperty("os.arch")

        /**
         * How far an architecture whose own numbers are not recorded yet may measure from the calibration's while they are
         * recorded: MAD and within-2 share. CI's x86 Skia measured 0.18–0.36 MAD above the Mac's and 1.2–5.3 points less
         * within 2 on the smoke cases (hyb4, run 38953006189); a frame drawn wrong is off by far more.
         */
        const val SANITY_MAD = 1.0
        const val SANITY_WITHIN2 = 0.10

        /** D7's margin, MAD: between MSAA and hyb4, between all-analytic and the rest, between 2x and 4x. */
        const val MARGIN = 0.15

        /** D16's final MAD allowance: the calibration's 0.10 and the lavapipe platform's 0.8 (N9). */
        const val ALLOWANCE = 0.10 + 0.8

        /** D16's final within-2 allowance: 1 point, and 2 of the platform's. */
        const val WITHIN2_ALLOWANCE = 0.01 + 0.02

        /** The lowest flat-miss gate: plan-gpu D8's structural 0.001 %, where calibrated × 3 would be 0. */
        const val FLAT_FLOOR = 0.00001

        /** D16's final flat-miss gate from a calibrated share: × 3, at least [FLAT_FLOOR]. */
        fun flatGate(calibrated: Double): Double = max(calibrated * 3, FLAT_FLOOR)

        private val VARIANT_ABOUT = mapOf(
            Variant.HYB4 to "G1's hyb4: 4x samples at the standard positions; FACE and ELL edges with analytic coverage at the pixel's centre, their opaque interiors at the samples; fills not cut into faces (STENCIL) at the samples; strokes up to 1 device px as Skia's hairlines, wider with their exact area",
            Variant.MSAA4_STANDARD to "4x MSAA at Vulkan's standard 4x positions (0.375, 0.125), (0.875, 0.375), (0.125, 0.625), (0.625, 0.875): every area, and strokes, dots and rings from 2 device px (ribbons at world width), cover their samples; thinner strokes as hyb4 (configurations A and C)",
            Variant.MSAA2_STANDARD to "the same at Vulkan's standard 2x positions (0.75, 0.75), (0.25, 0.25) (configuration B)",
            Variant.ALL_ANALYTIC to "no MSAA: one sample, painter's order; FACEs and ELLs with analytic coverage, stencilled fills and clips by their area over an 8 x 8 grid; strokes as hyb4 (configuration D, FA)",
        )

        /** The median of [v]: the middle value, or the mean of the two middle ones. */
        fun median(v: List<Double>): Double {
            if (v.isEmpty()) return Double.NaN
            val s = v.sorted(); val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
        }

        /** The value at fraction [p] of [v] sorted ascending: index ⌊p·(n − 1)⌋, as thresholds.json's class values were first taken. */
        fun index(v: List<Double>, p: Double): Double {
            if (v.isEmpty()) return Double.NaN
            val s = v.sorted()
            return s[floor(p * (s.size - 1)).toInt()]
        }
    }
}
