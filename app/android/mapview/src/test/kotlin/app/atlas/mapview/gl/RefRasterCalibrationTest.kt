package app.atlas.mapview.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.atlas.anchor.Anchor
import app.atlas.anchor.AnchorCases
import app.atlas.anchor.AnchorCompiler
import app.atlas.anchor.RefMetrics
import app.atlas.anchor.RefRaster
import app.atlas.anchor.Tile
import app.atlas.anchor.TileSource
import app.atlas.mapview.svg.SvgCanvas
import app.atlas.mapview.svg.SvgDocument
import app.atlas.mapview.svg.SvgFonts
import app.atlas.mapview.svg.SvgGroup
import app.atlas.mapview.svg.SvgNode
import app.atlas.mapview.svg.SvgParser
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.LiveView
import app.atlas.render.live.Moment
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The likeness gates calibrated before the first GL run (plan-gpu D8, "Calibration"): RefRaster, the GPU's rules
 * emulated in Java, against the exact raster of Android's own Skia ([SvgCanvas] at the density, no view-box fit,
 * cropped) with filters off on both sides (generator-only elements left out, other filters drawn as absent), over D8's
 * P1 and rest cases. Each gate's threshold becomes the tighter of its provisional value and the calibrated one (the
 * worst case's metric) plus the GL allowance: MAD + 0.10, within-2 − 1.0 point, p99 + 2, block + 1.0, flat misses × 3.
 *
 * With `-Datlas.calibrate=true` (or `ATLAS_CALIBRATE=true`) it runs every case and rewrites
 * `anchor/src/test/resources/likeness.json`; otherwise it runs the smoke cases, checks that they still measure as
 * recorded there, and says which gates the GPU's rules meet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RefRasterCalibrationTest {
    private val calibrate = (System.getProperty("atlas.calibrate") ?: System.getenv("ATLAS_CALIBRATE")) == "true"
    private val repo = AnchorCases.repo
    private val likeness = File(repo, "app/android/anchor/src/test/resources/likeness.json")
    private val fonts: SvgFonts by lazy { SvgFonts.fromDirectory(File(repo, "app/android/mapview/src/main/assets/svgfonts")) }

    /** A gate's numbers: MAD, within-2 share, p99, worst block, flat misses (shares as fractions); NaN where it sets none. */
    data class Limits(val mad: Double, val within2: Double, val p99: Double, val block: Double, val flat: Double) {
        private fun f(v: Double, fmt: String) = if (v.isNaN()) "null" else String.format(Locale.ROOT, fmt, v)
        fun json() = "{\"mad\": ${f(mad, "%.4f")}, \"within2\": ${f(within2, "%.5f")}, \"p99\": ${f(p99, "%.0f")}, \"block\": ${f(block, "%.3f")}, \"flat\": ${f(flat, "%.8f")}}"

        /** The tighter of this provisional limit and the calibrated value [c] plus the GL allowance, where this one sets a limit. */
        fun calibrated(c: Limits): Limits {
            fun lo(p: Double, v: Double) = if (p.isNaN()) Double.NaN else min(p, v)
            return Limits(lo(mad, c.mad + 0.10), if (within2.isNaN()) Double.NaN else max(within2, c.within2 - 0.010), lo(p99, c.p99 + 2), lo(block, c.block + 1.0), lo(flat, c.flat * 3))
        }

        fun holds(m: RefMetrics) = (mad.isNaN() || m.mad <= mad) && (within2.isNaN() || m.within2 >= within2) && (p99.isNaN() || m.p99 <= p99) &&
            (block.isNaN() || m.block <= block) && (flat.isNaN() || m.flatMisses <= flat)
    }

    /** The gates of D8 this calibration covers, with their provisional thresholds (NaN: the gate sets none). */
    enum class Gate(val provisional: Limits) {
        P1(Limits(0.35, 0.970, 8.0, 3.0, Double.NaN)),
        REST_WESTERKERK(Limits(0.45, 0.968, 8.0, 3.0, Double.NaN)),
        REST_CANAL(Limits(0.65, 0.935, 12.0, 3.5, Double.NaN)),
        STRUCTURAL_WESTERKERK(Limits(0.35, Double.NaN, Double.NaN, Double.NaN, 0.00001)),
        STRUCTURAL_CANAL(Limits(0.55, Double.NaN, Double.NaN, Double.NaN, 0.00001)),
        CEILING(Limits(1.0, 0.90, 16.0, 6.0, 0.00005)),
    }

    /** One case: a render at a size, the gates it counts in. */
    class Case(val name: String, val canal: Boolean, val size: AnchorCases.Size, val view: (LiveRegion) -> LiveView, val moment: Moment, val gates: Set<Gate>, val smoke: Boolean)

    private fun named(m: String) = Moment.Named(m)
    private val live = Moment.Live(Instant.parse("2026-06-21T10:00:00Z"), null)

    private fun cases(): List<Case> {
        val out = ArrayList<Case>()
        val s1 = AnchorCases.Size.S1
        val rest = setOf(Gate.REST_WESTERKERK, Gate.STRUCTURAL_WESTERKERK, Gate.CEILING)
        // the Westerkerk home: four moments at S1 (P1 and rest), the live moment, and S2 and S3
        for (m in AnchorCases.MOMENTS) out.add(Case("home $m S1", false, s1, { AnchorCases.homeView(it, s1.frameHeight) }, named(m), rest + Gate.P1, m == "sunny" || m == "night"))
        out.add(Case("home live S1", false, s1, { AnchorCases.homeView(it, s1.frameHeight) }, live, rest, false))
        for (size in listOf(AnchorCases.Size.S2, AnchorCases.Size.S3)) {
            for (m in AnchorCases.MOMENTS) out.add(Case("home $m $size", false, size, { AnchorCases.homeView(it, size.frameHeight) }, named(m), rest, false))
            out.add(Case("home live $size", false, size, { AnchorCases.homeView(it, size.frameHeight) }, live, rest, false))
        }
        // the sweeps: 12 cameras × sunny and night (P1), 24 × four moments (rest) at S1, 8 × sunny and night at S2 and S3
        for (k in 0 until 24) for (m in AnchorCases.MOMENTS) {
            val p1 = k < 12 && (m == "sunny" || m == "night")
            out.add(Case("sweep $k $m S1", false, s1, { AnchorCases.sweep(24, it.bbox!!, s1.frameHeight)[k] }, named(m), if (p1) rest + Gate.P1 else rest, k < 2 && m == "sunny"))
        }
        for (size in listOf(AnchorCases.Size.S2, AnchorCases.Size.S3)) for (k in 0 until 8) for (m in listOf("sunny", "night")) {
            out.add(Case("sweep $k $m $size", false, size, { AnchorCases.sweep(8, it.bbox!!, size.frameHeight)[k] }, named(m), rest, false))
        }
        // the canal ring: the Main board × four moments, 8 cameras × sunny and night, at S1
        val canal = setOf(Gate.REST_CANAL, Gate.STRUCTURAL_CANAL, Gate.CEILING)
        for (m in AnchorCases.MOMENTS) out.add(Case("Main board $m S1", true, s1, { AnchorCases.boardView(it, s1.frameHeight) }, named(m), canal, m == "sunny"))
        for (k in 0 until 8) for (m in listOf("sunny", "night")) {
            out.add(Case("canal sweep $k $m S1", true, s1, { AnchorCases.sweep(8, it.bbox!!, s1.frameHeight)[k] }, named(m), canal, false))
        }
        return out
    }

    /** [doc] without its generator-only elements: what filters off draws (they would be black rects otherwise). */
    private fun withoutGenerators(doc: SvgDocument): SvgDocument {
        fun prune(n: SvgNode): SvgNode? {
            val f = n.filter
            if (f != null && AnchorCompiler.generatorOnly(f)) return null
            if (n !is SvgGroup) return n
            val g = SvgGroup(n.children.mapNotNull { prune(it) })
            g.transform = n.transform; g.opacity = n.opacity; g.filter = n.filter; g.clip = n.clip; g.blend = n.blend
            return g
        }
        val root = prune(doc.root) as SvgGroup
        return SvgDocument(doc.width, doc.height, doc.viewBox, root, doc.path, doc.gradients, doc.patterns, doc.filters, doc.clips, doc.styles)
    }

    /** The exact raster with filters off at density [d], [w] × [h] pixels, drawn at the density with no view-box fit. */
    private fun reference(layers: List<String>, d: Double, w: Int, h: Int): IntArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        for (text in layers) {
            val doc = withoutGenerators(SvgParser.parse(text))
            val vb = doc.viewBox
            // the view box at exactly the density: draw()'s fit then scales by d (within a float's rounding)
            SvgCanvas(doc, fonts, SvgCanvas.FilterMode.NONE, 1, null).draw(c, (vb[2] * d).toFloat(), (vb[3] * d).toFloat())
        }
        return SvgCanvas.premultipliedPixels(bmp).also { bmp.recycle() }
    }

    /** Text and pattern tiles drawn by the device's own Skia, as the GPU path will make them. */
    inner class SkiaTiles : TileSource {
        override fun text(anchor: Anchor, i: Int, density: Double): Tile? {
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
            if (b.isEmpty) return null
            val x0 = floor(b.left).toInt() - 2; val y0 = floor(b.top).toInt() - 2
            val w = ceil(b.right).toInt() + 2 - x0; val h = ceil(b.bottom).toInt() + 2 - y0
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            cv.translate(-x0.toFloat(), -y0.toFloat())
            cv.concat(m)
            cv.drawText(t.text, t.x, t.y, p)
            return Tile(x0, y0, w, h, SvgCanvas.premultipliedPixels(bmp)).also { bmp.recycle() }
        }

        override fun pattern(anchor: Anchor, i: Int, pxPerUnit: Double): Tile? {
            val doc = SvgParser.parse(anchor.patterns[i].svg)
            val tw = max(1, ceil(doc.width * pxPerUnit - 1e-3).toInt()); val th = max(1, ceil(doc.height * pxPerUnit - 1e-3).toInt())
            val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            SvgCanvas(doc, fonts, SvgCanvas.FilterMode.NONE, 1, null).draw(Canvas(bmp), tw.toFloat(), th.toFloat())
            return Tile(0, 0, tw, th, SvgCanvas.premultipliedPixels(bmp)).also { bmp.recycle() }
        }
    }

    /** One case measured: RefRaster against the exact raster, filters off. */
    private fun measure(c: Case): RefMetrics? {
        val region = (if (c.canal) AnchorCases.grachten() else AnchorCases.westerkerk()) ?: return null
        val made = try {
            AnchorCases.render(region, c.view(region), c.moment)
        } catch (e: RuntimeException) {
            // the engine's own failure (not the anchor's): reported, and the case left out
            println("calibration ${c.name}: the render failed: $e")
            failedRenders.add(c.name)
            return null
        }
        val anchor = AnchorCases.anchor(made)
        val d = c.size.density
        val gpu = RefRaster(anchor, d, c.size.wPx, c.size.hPx, SkiaTiles()).render()
        val ref = reference(made.layers, d, c.size.wPx, c.size.hPx)
        val m = RefMetrics.of(gpu, ref, c.size.wPx, c.size.hPx)
        val (flat, edges) = split(gpu, ref, c.size.wPx, c.size.hPx)
        println("calibration ${c.name}: $m; MAD over flat pixels ${String.format(Locale.ROOT, "%.3f", flat)} (arithmetic), over the rest ${String.format(Locale.ROOT, "%.3f", edges)} (coverage)")
        splits.add(flat to edges)
        if (System.getenv("ATLAS_CALIBRATE_LAYERS") == "true") {
            val src = made.source
            val g = app.atlas.anchor.AnchorSource(src.camera, src.cfg, src.ground, emptyList(), made.tape, null, null, src.meta)
            val ga = AnchorCompiler.compile(g)
            val gg = RefRaster(ga, d, c.size.wPx, c.size.hPx, SkiaTiles()).render()
            val gr = reference(made.layers.take(1), d, c.size.wPx, c.size.hPx)
            println("  ground only: ${RefMetrics.of(gg, gr, c.size.wPx, c.size.hPx)}")
        }
        if (System.getenv("ATLAS_CALIBRATE_QUANTISED") == "true") {
            val q = RefRaster(anchor, d, c.size.wPx, c.size.hPx, SkiaTiles(), quantise = true).render()
            println("  with the paint rounded to 8 bits first: ${RefMetrics.of(q, ref, c.size.wPx, c.size.hPx)}")
        }
        if (System.getenv("ATLAS_CALIBRATE_SHOTS") == "true") {
            shots(c.name, gpu, ref, c.size.wPx, c.size.hPx)
            val w = c.size.wPx; val h = c.size.hPx
            for (b in 0 until 12) {
                val y0 = h * b / 12; val y1 = h * (b + 1) / 12
                var bias = 0L; var sum = 0L; var n = 0
                val chan = LongArray(3)
                for (i in y0 * w until y1 * w) for ((ci, sh) in intArrayOf(16, 8, 0).withIndex()) {
                    val dd = ((gpu[i] ushr sh) and 0xFF) - ((ref[i] ushr sh) and 0xFF); bias += dd; sum += Math.abs(dd); n++; chan[ci] += dd.toLong()
                }
                println(String.format(Locale.ROOT, "  band %d: MAD %.3f bias %.3f (r %.3f g %.3f b %.3f)", b, sum.toDouble() / n, bias.toDouble() / n, chan[0] * 3.0 / n, chan[1] * 3.0 / n, chan[2] * 3.0 / n))
            }
        }
        return m
    }

    private val splits = ArrayList<Pair<Double, Double>>()
    private val failedRenders = ArrayList<String>()

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
            for (q in 0 until 24 step 8) d += Math.abs(((a[i] ushr q) and 0xFF) - ((b[i] ushr q) and 0xFF))
            if (flat) { fs += d; fn += 3 } else { es += d; en += 3 }
        }
        return (if (fn > 0) fs.toDouble() / fn else 0.0) to (if (en > 0) es.toDouble() / en else 0.0)
    }

    /** The pair and their difference (×8) as PNGs, for a look while calibrating. */
    private fun shots(name: String, gpu: IntArray, ref: IntArray, w: Int, h: Int) {
        val dir = File(System.getProperty("atlas.shots") ?: return, "calibration").also { it.mkdirs() }
        val diff = IntArray(w * h) { i ->
            var m = 0
            for (s in 0 until 24 step 8) m = max(m, Math.abs(((gpu[i] ushr s) and 0xFF) - ((ref[i] ushr s) and 0xFF)))
            val v = min(255, m * 8)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        for ((tag, px) in listOf("gpu" to gpu, "ref" to ref, "diff" to diff)) {
            val b = SvgCanvas.bitmapOfPremultiplied(px, w, h)
            File(dir, "${name.replace(' ', '_')}_$tag.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            b.recycle()
        }
    }

    @Test
    fun `RefRaster against the exact raster, filters off, over D8's cases`() {
        AnchorCases.setUp()
        val all = cases()
        val run = if (calibrate) all else all.filter { it.smoke }
        val measured = ArrayList<Pair<Case, RefMetrics>>()
        for (c in run) measure(c)?.let { measured.add(c to it) }
        assertTrue("cases measured", measured.isNotEmpty())
        if (calibrate) {
            write(measured)
            return
        }
        // check: every smoke case still measures as calibrated (RefRaster, the compiler and the reference unchanged), with
        // room for Skia's own arithmetic, which differs between ARM and x86 builds; and say which gates the GPU's rules meet
        assertTrue("likeness.json is there (run with -Datlas.calibrate=true to make it)", likeness.isFile)
        val recorded = readCases()
        val thresholds = read()
        // Skia's 8-bit blending rounds differently on ARM (exact) and x86 (approximate) builds: another machine gets more room
        val arch = Regex("\"arch\": \"([^\"]+)\"").find(likeness.readText())?.groupValues?.get(1)
        val k = if (arch == System.getProperty("os.arch")) 1.0 else 2.5
        for ((c, m) in measured) {
            val r = recorded[c.name]
            assertTrue("${c.name} is in likeness.json", r != null)
            r!!
            assertTrue("${c.name}: MAD ${m.mad} as calibrated (${r.mad})", m.mad <= r.mad + 0.15 * k)
            assertTrue("${c.name}: within-2 ${m.within2} as calibrated (${r.within2})", m.within2 >= r.within2 - 0.015 * k)
            assertTrue("${c.name}: p99 ${m.p99} as calibrated (${r.p99})", m.p99 <= r.p99 + 3 * k)
            assertTrue("${c.name}: block ${m.block} as calibrated (${r.block})", m.block <= r.block + 1.5 * k)
            assertTrue("${c.name}: flat misses ${m.flatMisses} as calibrated (${r.flat})", m.flatMisses <= r.flat * 3 * k + 1e-5)
            for (g in c.gates) thresholds[g]?.let { t -> println("  ${c.name}: the ${g.name} gate ${t} ${if (t.holds(m)) "holds" else "does not hold"} for the GPU's rules") }
        }
    }

    /** The cases likeness.json recorded, by name. */
    private fun readCases(): Map<String, Limits> {
        val out = HashMap<String, Limits>()
        val re = Regex("\\{\"name\": \"([^\"]+)\", \"gates\": \\[[^\\]]*\\], \"mad\": ([0-9.eE-]+), \"within2\": ([0-9.eE-]+), \"p99\": (\\d+), \"block\": ([0-9.eE-]+), \"flat\": ([0-9.eE-]+)\\}")
        for (m in re.findAll(likeness.readText())) {
            out[m.groupValues[1]] = Limits(m.groupValues[2].toDouble(), m.groupValues[3].toDouble(), m.groupValues[4].toDouble(), m.groupValues[5].toDouble(), m.groupValues[6].toDouble())
        }
        return out
    }

    private fun worst(ms: List<RefMetrics>) = Limits(ms.maxOf { it.mad }, ms.minOf { it.within2 }, ms.maxOf { it.p99 }.toDouble(), ms.maxOf { it.block }, ms.maxOf { it.flatMisses })

    private fun write(measured: List<Pair<Case, RefMetrics>>) {
        val sb = StringBuilder()
        sb.append("{\n  \"about\": \"plan-gpu D8 calibration: RefRaster (the GPU's rules in Java) against SvgCanvas at the density, filters off on both sides; ")
            .append("threshold = the tighter of provisional and calibrated + allowance. Written by RefRasterCalibrationTest with -Datlas.calibrate=true.\",\n")
        sb.append("  \"arch\": \"${System.getProperty("os.arch")}\",\n")
        sb.append("  \"renders that failed\": [${failedRenders.joinToString(", ") { "\"$it\"" }}],\n")
        sb.append("  \"allowance\": {\"mad\": 0.10, \"within2\": -0.010, \"p99\": 2, \"block\": 1.0, \"flat\": \"x3\"},\n  \"gates\": {\n")
        val gates = Gate.entries.filter { g -> measured.any { g in it.first.gates } }
        for ((n, g) in gates.withIndex()) {
            val ms = measured.filter { g in it.first.gates }.map { it.second }
            val cal = worst(ms)
            sb.append("    \"${g.name}\": {\"cases\": ${ms.size}, \"provisional\": ${g.provisional.json()}, \"calibrated\": ${cal.json()}, \"threshold\": ${g.provisional.calibrated(cal).json()}}")
            sb.append(if (n < gates.size - 1) ",\n" else "\n")
        }
        sb.append("  },\n  \"cases\": [\n")
        for ((n, p) in measured.withIndex()) {
            val (c, m) = p
            sb.append(String.format(Locale.ROOT, "    {\"name\": \"%s\", \"gates\": [%s], \"mad\": %.4f, \"within2\": %.5f, \"p99\": %d, \"block\": %.3f, \"flat\": %.8f}",
                c.name, c.gates.joinToString(", ") { "\"${it.name}\"" }, m.mad, m.within2, m.p99, m.block, m.flatMisses))
            sb.append(if (n < measured.size - 1) ",\n" else "\n")
        }
        sb.append("  ]\n}\n")
        val fl = splits.map { it.first }.sorted(); val ed = splits.map { it.second }.sorted()
        sb.insert(sb.indexOf("\"gates\""), String.format(Locale.ROOT, "\"split\": {\"about\": \"median MAD over flat pixels (arithmetic) and over the rest (coverage)\", \"flat\": %.3f, \"edges\": %.3f},\n  ", fl[fl.size / 2], ed[ed.size / 2]))
        likeness.parentFile.mkdirs()
        likeness.writeText(sb.toString())
        println("wrote ${likeness.path}: ${measured.size} cases")
        for (g in gates) {
            val ms = measured.filter { g in it.first.gates }.map { it.second }
            println("gate ${g.name}: ${ms.size} cases, calibrated ${worst(ms)}, threshold ${g.provisional.calibrated(worst(ms))}")
        }
    }

    /** The thresholds likeness.json holds, by gate. */
    private fun read(): Map<Gate, Limits> {
        val text = likeness.readText()
        val out = HashMap<Gate, Limits>()
        val num = "([0-9.eE-]+|null)"
        val re = Regex("\"(\\w+)\": \\{\"cases\": \\d+, \"provisional\": \\{[^}]*\\}, \"calibrated\": \\{[^}]*\\}, \"threshold\": \\{\"mad\": $num, \"within2\": $num, \"p99\": $num, \"block\": $num, \"flat\": $num\\}")
        fun v(s: String) = if (s == "null") Double.NaN else s.toDouble()
        for (m in re.findAll(text)) {
            val g = Gate.entries.firstOrNull { it.name == m.groupValues[1] } ?: continue
            out[g] = Limits(v(m.groupValues[2]), v(m.groupValues[3]), v(m.groupValues[4]), v(m.groupValues[5]), v(m.groupValues[6]))
        }
        return out
    }
}
