package app.atlas.likeness

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.PathParser
import app.atlas.anchor.AnchorCompiler
import app.atlas.anchor.RefMetrics
import app.atlas.mapview.svg.SvgCanvas
import app.atlas.mapview.svg.SvgDocument
import app.atlas.mapview.svg.SvgGroup
import app.atlas.mapview.svg.SvgNode
import app.atlas.mapview.svg.SvgParser
import app.atlas.mapview.svg.TurbulenceCache
import app.atlas.render.live.EngineVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PNG files of frames: opaque RGB (8 bits a channel) for pictures, and owner maps with each pixel's owner index as its
 * 24-bit RGB. Written by a small encoder (adaptive None/Sub/Up row filters, deflate level 6) that is several times faster
 * than ImageIO's; read by ImageIO.
 */
object Png {
    /** Pixels read back: [w] × [h], opaque ARGB (or indices). */
    class Image(val w: Int, val h: Int, val px: IntArray)

    /** Writes opaque ARGB [argb] ([w] × [h]) as an RGB PNG. */
    fun write(file: File, argb: IntArray, w: Int, h: Int) = encode(file, argb, w, h)

    /** Writes owner indices [idx] (below 2²⁴) as an RGB PNG. */
    fun writeIndex(file: File, idx: IntArray, w: Int, h: Int) {
        for (v in idx) require(v in 0 until (1 shl 24)) { "owner index $v does not fit 24 bits" }
        encode(file, idx, w, h)
    }

    fun read(file: File): Image {
        val img = ImageIO.read(file) ?: throw IllegalStateException("not a PNG: $file")
        val w = img.width; val h = img.height
        val px = img.getRGB(0, 0, w, h, null, 0, w)
        for (i in px.indices) px[i] = px[i] or (0xFF shl 24)
        return Image(w, h, px)
    }

    fun readIndex(file: File): Image {
        val im = read(file)
        for (i in im.px.indices) im.px[i] = im.px[i] and 0xFFFFFF
        return Image(im.w, im.h, im.px)
    }

    private fun encode(file: File, px: IntArray, w: Int, h: Int) {
        require(px.size == w * h) { "${px.size} pixels for $w × $h" }
        file.parentFile?.mkdirs()
        val stride = w * 3
        val raw = ByteArray(stride)
        val prev = ByteArray(stride)
        val cand = Array(3) { ByteArray(stride) }
        val idat = ByteArrayOutputStream(w * h)
        val def = Deflater(6)
        DeflaterOutputStream(idat, def, 1 shl 16).use { z ->
            for (y in 0 until h) {
                var o = 0
                for (x in 0 until w) {
                    val p = px[y * w + x]
                    raw[o] = (p shr 16).toByte(); raw[o + 1] = (p shr 8).toByte(); raw[o + 2] = p.toByte(); o += 3
                }
                // the row filter with the smallest sum of absolute values: None, Sub, Up
                var best = 0; var bestSum = Long.MAX_VALUE
                for (f in 0 until 3) {
                    val out = cand[f]
                    var sum = 0L
                    for (i in 0 until stride) {
                        val v = when (f) {
                            0 -> raw[i].toInt()
                            1 -> raw[i] - (if (i >= 3) raw[i - 3] else 0)
                            else -> raw[i] - (if (y > 0) prev[i] else 0)
                        }
                        out[i] = v.toByte()
                        sum += abs(out[i].toInt())
                    }
                    if (sum < bestSum) { bestSum = sum; best = f }
                }
                z.write(if (best == 0) 0 else if (best == 1) 1 else 2)
                z.write(cand[best], 0, stride)
                System.arraycopy(raw, 0, prev, 0, stride)
            }
        }
        def.end()
        DataOutputStream(file.outputStream().buffered(1 shl 16)).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            val ihdr = ByteArrayOutputStream().also { b ->
                DataOutputStream(b).apply { writeInt(w); writeInt(h); writeByte(8); writeByte(2); writeByte(0); writeByte(0); writeByte(0) }
            }.toByteArray()
            chunk(out, "IHDR", ihdr)
            chunk(out, "IDAT", idat.toByteArray())
            chunk(out, "IEND", ByteArray(0))
        }
    }

    private fun chunk(out: DataOutputStream, type: String, data: ByteArray) {
        out.writeInt(data.size)
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(t); out.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        out.writeInt(crc.value.toInt())
    }
}

/**
 * Frames of the port's layers as plan-gpu's `drawScaled` makes them: the canvas scaled by the case's density with no
 * view-box fit, cropped to the panel's pixels, on white; the relief image between the ground and the objects. Structural
 * mode draws no filter (`FilterMode.NONE`) and leaves out what only a filter makes (generator-only elements, which would
 * otherwise be black rectangles: plan-gpu R1-2); full mode runs every filter exactly on the CPU (`SvgFilterRaster`).
 */
object Frames {
    /**
     * Held around every use of Android's graphics (bitmaps, canvases, paths, text): Robolectric's native graphics are
     * not safe from two threads at once (two workers rastering S3 frames crashed the JVM in `copyPixelsToBuffer`).
     * The workers still map owners and write files beside it.
     */
    val skia = Any()

    /** The noise of the full frames' filters, kept across cases in memory: the same at every case of one size. */
    private val noise = TurbulenceCache(null)

    /** [layers] (ground first) at [size] in [mode], the frame moved by [shiftX] device px; opaque ARGB, `wPx × hPx`. */
    fun draw(layers: List<String>, relief: app.atlas.render.Raster?, size: FrameSize, mode: Mode, shiftX: Double = 0.0): IntArray =
        drawDocs(layers.map { doc(it, mode) }, relief, size, mode, shiftX)

    /** A layer's text parsed for [mode]: in structural mode, without its generator-only elements. */
    fun doc(text: String, mode: Mode): SvgDocument {
        val d = SvgParser.parse(text)
        return if (mode == Mode.STRUCTURAL) withoutGenerators(d) else d
    }

    fun drawDocs(docs: List<SvgDocument>, relief: app.atlas.render.Raster?, size: FrameSize, mode: Mode, shiftX: Double = 0.0): IntArray {
        synchronized(skia) { return drawLocked(docs, relief, size, mode, shiftX) }
    }

    private fun drawLocked(docs: List<SvgDocument>, relief: app.atlas.render.Raster?, size: FrameSize, mode: Mode, shiftX: Double): IntArray {
        val bmp = Bitmap.createBitmap(size.wPx, size.hPx, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            if (shiftX != 0.0) c.translate(shiftX.toFloat(), 0f)
            val d = size.density.toFloat()
            c.scale(d, d)
            val filters = if (mode == Mode.STRUCTURAL) SvgCanvas.FilterMode.NONE else SvgCanvas.FilterMode.CPU
            for ((i, doc) in docs.withIndex()) {
                val vb = doc.viewBox
                // the view box drawn at exactly 1 CSS px a unit inside the scaled canvas: no fit, no centring
                SvgCanvas(doc, Likeness.fonts, filters, if (mode == Mode.STRUCTURAL) 1 else SvgCanvas.DEFAULT_BANDS, if (mode == Mode.STRUCTURAL) null else noise)
                    .draw(c, vb[2], vb[3])
                if (i == 0 && relief != null) drawRelief(c, relief, doc.width, doc.height)
            }
            val px = SvgCanvas.premultipliedPixels(bmp)
            for (i in px.indices) px[i] = px[i] or (0xFF shl 24)
            return px
        } finally {
            bmp.recycle()
        }
    }

    /** The relief image (regions.py's `t`) over the frame, inside its outline when it has one, bilinear (as the oracle). */
    private fun drawRelief(c: Canvas, r: app.atlas.render.Raster, w: Float, h: Float) {
        val img = BitmapFactory.decodeByteArray(r.data, 0, r.data.size) ?: throw IllegalStateException("the relief image does not decode")
        c.save()
        r.clip?.let { c.clipPath(PathParser.createPathFromPathData(it)) }
        c.drawBitmap(img, null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
        img.recycle()
    }

    /** [doc] without the elements only a generator-only filter draws (what filters off leaves out, D16's structural mode). */
    fun withoutGenerators(doc: SvgDocument): SvgDocument {
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
}

/**
 * The effects alone on probe scenes (D16's effects set), each built from a render's own definitions (its `mat`, `grain`,
 * `litglow` filters and `vig` gradient, taken from [ProbeSpec.from]'s text, not retyped) over simple shapes, and drawn
 * full (`SvgFilterRaster`). The probe's SVG is kept beside its reference, so the core's effect packages draw the same.
 */
object Probes {
    private val PALETTE = listOf("#B8714A", "#9C5B3E", "#D8C9A8", "#7A6A5C", "#C9B79A", "#8C3F2E", "#E2DCCF", "#5E5A54")

    /** The probe SVG of [spec] on a [w] × [h] CSS px frame, its definitions taken from [source] (a render's layers). */
    fun svg(spec: ProbeSpec, source: List<String>, w: Int, h: Int): String {
        val all = source.joinToString("")
        fun def(prefix: String): String {
            val r = SvgEdit.element(all, prefix) ?: throw IllegalStateException("the probe's source has no ${prefix.trim()}…")
            return all.substring(r.first, r.last + 1)
        }
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $w $h\" width=\"$w\" height=\"$h\"><defs><clipPath id=\"vb\"><rect width=\"$w\" height=\"$h\"/></clipPath>")
        when (spec.effect) {
            "mat" -> sb.append(def("<filter id=\"mat\""))
            "grain" -> sb.append(def("<filter id=\"grain\""))
            "screen" -> { sb.append(def("<filter id=\"litglow\"")); if (spec.vignette) sb.append(def("<radialGradient id=\"vig\"")) }
            else -> throw IllegalArgumentException("no probe for '${spec.effect}' (mat, grain, screen)")
        }
        sb.append("</defs><g clip-path=\"url(#vb)\">")
        val r = java.util.Random(4)
        when (spec.effect) {
            "mat" -> {
                // walls and roofs as quads of every size on a pale ground: mat's texture inside, its thinning at the silhouettes
                sb.append("<rect width=\"$w\" height=\"$h\" fill=\"#E4DDD0\"/><g stroke-linejoin=\"round\" filter=\"url(#mat)\">")
                for (i in 0 until 60) sb.append(quad(r, w, h, PALETTE[i % PALETTE.size]))
                sb.append("</g>")
            }
            "grain" -> {
                // flat light and dark halves and a ramp: the overlay grain shows on each
                sb.append("<rect width=\"$w\" height=\"${h / 3}\" fill=\"#EAE4D8\"/><rect y=\"${h / 3}\" width=\"$w\" height=\"${h / 3}\" fill=\"#3A3F4A\"/>")
                sb.append("<rect y=\"${2 * h / 3}\" width=\"$w\" height=\"${h - 2 * h / 3}\" fill=\"#8C8578\"/>")
                sb.append("<rect width=\"$w\" height=\"$h\" filter=\"url(#grain)\" opacity=\".09\" style=\"mix-blend-mode:overlay\"/>")
            }
            else -> {
                // lit streets glowing on a night ground, under a haze of a fixed alpha or under the vignette
                sb.append("<rect width=\"$w\" height=\"$h\" fill=\"#1C2029\"/>")
                for (i in 0 until 12) sb.append(quad(r, w, h, "#2A2E38"))
                sb.append("<g fill=\"none\" stroke=\"#FFB866\" stroke-linecap=\"round\" stroke-linejoin=\"round\" opacity=\".3\" filter=\"url(#litglow)\" style=\"mix-blend-mode:screen\">")
                for (i in 0 until 14) {
                    val x0 = r.nextDouble() * w; val y0 = r.nextDouble() * h
                    sb.append(PyFmtProbe.path(x0, y0, x0 + (r.nextDouble() - 0.5) * 240, y0 + (r.nextDouble() - 0.5) * 240, 3 + r.nextDouble() * 9))
                }
                sb.append("</g>")
                spec.haze?.let { a -> sb.append("<rect width=\"$w\" height=\"$h\" fill=\"#5A6478\" fill-opacity=\"$a\"/>") }
                if (spec.vignette) sb.append("<rect width=\"$w\" height=\"$h\" fill=\"url(#vig)\"/>")
            }
        }
        sb.append("</g></svg>")
        return sb.toString()
    }

    private fun quad(r: java.util.Random, w: Int, h: Int, fill: String): String {
        val s = 6 + r.nextDouble() * 54
        val cx = r.nextDouble() * w; val cy = r.nextDouble() * h
        val a = r.nextDouble() * Math.PI
        val ca = Math.cos(a); val sa = Math.sin(a)
        val k = 0.5 + r.nextDouble()
        val pts = listOf(-s to -s * k, s to -s * k, s to s * k, -s to s * k).map { (x, y) -> (cx + x * ca - y * sa) to (cy + x * sa + y * ca) }
        return "<path d=\"M" + pts.joinToString("L") { "%.1f %.1f".format(java.util.Locale.ROOT, it.first, it.second) } + "Z\" fill=\"$fill\"/>"
    }

    /** Path text in the engine's own print (`%.1f`). */
    private object PyFmtProbe {
        fun path(x0: Double, y0: Double, x1: Double, y1: Double, wd: Double): String =
            "<path d=\"M%.1f %.1fL%.1f %.1f\" stroke-width=\"%.1f\"/>".format(java.util.Locale.ROOT, x0, y0, x1, y1, wd)
    }
}

/**
 * The stub's planted frames (D16's planted failures that need no engine): the oracle draws the case again with one fault
 * planted, standing in for an engine that makes it. Each must fail the stub's gates; the unplanted stub must pass them.
 */
object Plants {
    /** A planted frame, its owner map and labels where the plant changed them, and what was planted. */
    class Planted(val frame: IntArray, val owners: OwnerMap?, val labels: List<Label>?, val detail: String)

    fun plant(p: Plant, made: Oracle.Made, base: Built): Planted {
        val size = base.case.size
        val layers = made.layers
        val f = Features(made)
        fun draw(ls: List<String>, shift: Double = 0.0) = Frames.draw(ls, made.render.raster, size, base.case.mode, shift)
        return when (p.kind) {
            "none" -> Planted(base.ref, null, null, "the stub's frame as the oracle draws it")
            "paint" -> {
                val levels = p.int("levels", 8)
                // the fill most of the objects' elements use
                val counts = HashMap<String, Int>()
                val re = Regex("fill=\"(#[0-9A-Fa-f]{6})\"")
                for ((li, sc) in f.scans.withIndex()) if (sc.objectsStart >= 0) for (m in re.findAll(layers[li].subSequence(sc.objectsStart, sc.objectsEnd))) counts.merge(m.groupValues[1], 1, Int::plus)
                val (colour, n) = counts.maxBy { it.value }.toPair()
                val c = Integer.parseInt(colour.substring(1), 16)
                fun ch(v: Int) = if (v + levels <= 255) v + levels else v - levels
                val moved = "#%02X%02X%02X".format(ch(c shr 16 and 0xFF), ch(c shr 8 and 0xFF), ch(c and 0xFF))
                val edited = layers.mapIndexed { li, s ->
                    val sc = f.scans[li]
                    if (sc.objectsStart < 0) s else s.substring(0, sc.objectsStart) + s.substring(sc.objectsStart, sc.objectsEnd).replace("fill=\"$colour\"", "fill=\"$moved\"") + s.substring(sc.objectsEnd)
                }
                Planted(draw(edited), null, null, "the objects' commonest fill $colour ($n elements) drawn as $moved, $levels levels off")
            }
            "shift" -> {
                val px = p.double("px", 0.25)
                Planted(draw(layers, px), OwnerMap.of(made, size, shiftX = px), LabelRefs.of(layers, size, px), "the whole frame drawn $px px to the right")
            }
            "drops" -> {
                val count = p.int("count", 20)
                val limit = p.double("underCssPx2", 60.0) * size.density * size.density
                val small = base.owners.owners.withIndex().filter { (_, o) -> o.pixels in 20 until limit.toInt() && o.klass in setOf("building", "part", "tree", "furniture", "facade", "other") }
                    .sortedByDescending { it.value.pixels }.take(count)
                val keys = small.map { (_, o) -> o.type to o.id }.toSet()
                val items = f.table
                val dropped = (0 until items.size).filter { k -> made.tape.ownerObject(items.owner[k])?.let { AnchorCompiler.osmOwner(it) } in keys }
                val cuts = dropped.flatMap { f.itemCuts(it) }
                val edited = SvgEdit.apply(layers, cuts)
                val table = withoutItems(items, dropped)
                Planted(draw(edited), OwnerMap.of(made, size, edited, table), LabelRefs.of(edited, size),
                    "${small.size} owners under ${p.double("underCssPx2", 60.0)} CSS px² dropped (${small.joinToString(" ") { it.value.tag + ":" + it.value.pixels }})")
            }
            "swap" -> {
                val items = f.table
                val boxes = ownerBoxes(base.owners)
                val keyOf = HashMap<Pair<Byte, Long>, Int>()
                base.owners.owners.forEachIndexed { i, o -> keyOf[o.type to o.id] = i }
                var pick = -1
                for (k in 0 until items.size - 1) {
                    if (items.count[k] == 0 || items.count[k + 1] == 0) continue
                    if (items.kind[k] !in BUILT || items.kind[k + 1] !in BUILT) continue
                    val a = made.tape.ownerObject(items.owner[k])?.let { keyOf[AnchorCompiler.osmOwner(it)] } ?: continue
                    val b = made.tape.ownerObject(items.owner[k + 1])?.let { keyOf[AnchorCompiler.osmOwner(it)] } ?: continue
                    if (a == b || base.owners.owners[a].pixels < 300 || base.owners.owners[b].pixels < 300) continue
                    val ca = f.itemCut(k) ?: continue; val cb = f.itemCut(k + 1) ?: continue
                    if (ca.layer != cb.layer || ca.range.last + 1 != cb.range.first) continue
                    if (overlap(boxes[a], boxes[b]) < 0.3) continue
                    pick = k; break
                }
                check(pick >= 0) { "no two overlapping neighbours in painter's order to swap" }
                val ca = f.itemCut(pick)!!; val cb = f.itemCut(pick + 1)!!
                val s = layers[ca.layer]
                val swapped = s.substring(0, ca.range.first) + s.substring(cb.range.first, cb.range.last + 1) + s.substring(ca.range.first, ca.range.last + 1) + s.substring(cb.range.last + 1)
                val edited = layers.toMutableList().also { it[ca.layer] = swapped }
                val table = swap(items, pick)
                Planted(draw(edited), OwnerMap.of(made, size, edited, table), null, "items $pick and ${pick + 1} (${items.kind[pick]}, ${items.kind[pick + 1]}) drawn in each other's place")
            }
            "shadows" -> {
                check(f.shadows.isNotEmpty()) { "the case draws no sun shadows" }
                Planted(draw(SvgEdit.apply(layers, f.shadows)), null, null, "the sun shadows left out")
            }
            "label" -> {
                // the first name on the ground whose ink lies wholly inside the frame (the renders also write names off it)
                val ground = base.labels.filter { it.layer == "ground" }
                check(ground.size == f.groundTexts.size) { "${ground.size} names on the ground, ${f.groundTexts.size} text elements outside the objects" }
                val k = ground.indexOfFirst { it.x0 >= 0 && it.y0 >= 0 && it.x1 < size.wPx && it.y1 < size.hPx }
                check(k >= 0) { "the case shows no whole name on the ground" }
                val t = f.groundTexts[k]
                val s = layers[t.layer]
                val tag = s.substring(t.range.first, SvgScan.tagEnd(s, t.range.first))
                val m = Regex("matrix\\(([^)]*)\\)").find(tag) ?: throw IllegalStateException("the ground text has no matrix: $tag")
                val v = m.groupValues[1].trim().split(Regex("[ ,]+")).map { it.toDouble() }
                val len = Math.hypot(v[0], v[1])
                val moved = "matrix(%.3f %.3f %.3f %.3f %.1f %.1f)".format(java.util.Locale.ROOT, v[0], v[1], v[2], v[3], v[4] + 15 * v[0] / len, v[5] + 15 * v[1] / len)
                val at = t.range.first + m.range.first
                val edited = layers.toMutableList().also { it[t.layer] = s.substring(0, at) + moved + s.substring(at + m.value.length) }
                Planted(draw(edited), null, LabelRefs.of(edited, size), "${ground[k].text} moved 15 CSS px along its line")
            }
            "blackrect" -> {
                // the first element under a generator-only filter, its filter taken away: what a GPU path without the filter draws
                for ((li, s) in layers.withIndex()) {
                    val doc = SvgParser.parse(s)
                    val gen = doc.filters.values.filter { AnchorCompiler.generatorOnly(it) }.map { it.id }.toSet()
                    val m = Regex(" filter=\"url\\(#([^)]+)\\)\"").findAll(s).firstOrNull { it.groupValues[1] in gen } ?: continue
                    val edited = layers.toMutableList().also { it[li] = s.substring(0, m.range.first) + s.substring(m.range.last + 1) }
                    return Planted(draw(edited), null, null, "the element under the generator-only filter #${m.groupValues[1]} drawn without it")
                }
                throw IllegalStateException("the case draws nothing under a generator-only filter")
            }
            else -> throw IllegalArgumentException("plant '${p.kind}' needs ${p.needs ?: "an engine"}")
        }
    }

    private val BUILT = setOf("building", "part")

    /** Each owner's pixel box (x0, y0, x1, y1), by index − 1. */
    private fun ownerBoxes(m: OwnerMap): Array<IntArray> {
        val b = Array(m.owners.size) { intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, -1, -1) }
        for (y in 0 until m.h) for (x in 0 until m.w) {
            val i = m.index[y * m.w + x]
            if (i == 0) continue
            val r = b[i - 1]
            if (x < r[0]) r[0] = x; if (y < r[1]) r[1] = y; if (x > r[2]) r[2] = x; if (y > r[3]) r[3] = y
        }
        return b
    }

    /** The share of the smaller box that the two boxes share. */
    private fun overlap(a: IntArray, b: IntArray): Double {
        val w = min(a[2], b[2]) - max(a[0], b[0]) + 1; val h = min(a[3], b[3]) - max(a[1], b[1]) + 1
        if (w <= 0 || h <= 0) return 0.0
        val sa = (a[2] - a[0] + 1).toDouble() * (a[3] - a[1] + 1); val sb = (b[2] - b[0] + 1).toDouble() * (b[3] - b[1] + 1)
        return w.toDouble() * h / min(sa, sb)
    }

    /** [t] without items [drop], the characters after each dropped item moved up. */
    private fun withoutItems(t: ItemTable, drop: List<Int>): ItemTable {
        val gone = drop.toSet()
        val keep = (0 until t.size).filter { it !in gone }
        val first = IntArray(keep.size)
        var removed = 0; var di = 0
        val sortedDrop = drop.sorted()
        for ((n, k) in keep.withIndex()) {
            while (di < sortedDrop.size && sortedDrop[di] < k) { removed += t.count[sortedDrop[di]]; di++ }
            first[n] = t.first[k] - removed
        }
        return ItemTable(IntArray(keep.size) { t.owner[keep[it]] }, Array(keep.size) { t.kind[keep[it]] }, first, IntArray(keep.size) { t.count[keep[it]] })
    }

    /** [t] with items [k] and k + 1 (adjacent in the text) in each other's place. */
    private fun swap(t: ItemTable, k: Int): ItemTable {
        val owner = t.owner.copyOf(); val kind = t.kind.copyOf(); val first = t.first.copyOf(); val count = t.count.copyOf()
        owner[k] = t.owner[k + 1]; owner[k + 1] = t.owner[k]
        kind[k] = t.kind[k + 1]; kind[k + 1] = t.kind[k]
        count[k] = t.count[k + 1]; count[k + 1] = t.count[k]
        first[k] = t.first[k]; first[k + 1] = t.first[k] + t.count[k + 1]
        return ItemTable(owner, kind, first, count)
    }
}

/** One case's reference as it is built: the frame, its owner map and labels (none for a path's frames), and what it took. */
class Built(val case: Case, val ref: IntArray, private val ownerMap: OwnerMap?, private val labelList: List<Label>?, val made: Oracle.Made?) {
    val owners: OwnerMap get() = ownerMap ?: throw IllegalStateException("${case.name} has no owner map")
    val labels: List<Label> get() = labelList ?: throw IllegalStateException("${case.name} has no label list")
}

/**
 * A reference set on disk (`<refs>/<key>/`), keyed by `EngineVersion.ID`, `StyleVersion` and the case files' hash: a
 * `manifest.json` and per case `cases/<slug>/` with `ref.png`, `owners.png` (each pixel's owner index), `owners.json`
 * (the owners: type, OSM id, class, kind, pixels), `labels.json`, and for a probe its `probe.svg`; the stub's planted
 * frames under `planted/<slug>/`. `:mapcorecheck` reads it; the phone's VK check asset packs it.
 */
class RefSet(val root: File, val key: String) {
    val dir: File get() = File(root, key)
    val manifestFile: File get() = File(dir, "manifest.json")

    /** The manifest read back, or an empty one. */
    fun manifest(): JsonObject = if (manifestFile.isFile) Json.parseToJsonElement(manifestFile.readText()).jsonObject else JsonObject(emptyMap())

    /** The entries already built, by case name, whose files are all on disk. */
    fun entries(): Map<String, JsonObject> {
        val m = manifest()["entries"]?.jsonObject ?: return emptyMap()
        return m.mapValues { it.value.jsonObject }.filter { (_, e) -> e["files"]?.jsonObject?.values?.all { File(dir, it.jsonPrimitive.content).isFile } == true }
    }

    fun planted(): Map<String, JsonObject> {
        val m = manifest()["planted"]?.jsonObject ?: return emptyMap()
        return m.mapValues { it.value.jsonObject }.filter { (_, e) -> e["files"]?.jsonObject?.values?.all { File(dir, it.jsonPrimitive.content).isFile } == true }
    }

    companion object {
        fun keyOf(set: CaseSet): String = "${Likeness.version}-${set.hash}"
        private val pretty = Json { prettyPrint = true }
        fun write(file: File, v: Any?) { file.parentFile?.mkdirs(); file.writeText(pretty.encodeToString(JsonObject.serializer(), jsonOf(v) as JsonObject) + "\n") }
    }
}

/** Builds (or restores) the references of a tier. */
object References {
    /** What a build did: the cases built and restored, the plants, the ladder, and the wall time. */
    class Result(val set: RefSet, val built: List<String>, val restored: List<String>, val planted: List<String>, val millis: Long, val stages: Map<String, Long>)

    /**
     * Builds every case of [cases] not in [root]'s set for their key yet: the port renders one case at a time (its module
     * state is shared) while [workers] threads raster, map owners, place labels and write the files of the previous ones.
     */
    fun build(cases: CaseSet, root: File = Likeness.refs, workers: Int = 2): Result {
        val set = RefSet(root, RefSet.keyOf(cases))
        val t0 = System.nanoTime()
        val have = set.entries()
        val havePlanted = set.planted()
        val todo = cases.cases.filter { it.name !in have }
        val plantsTodo = cases.planted.filter { it.needs == null && it.name !in havePlanted }
        val entries = LinkedHashMap<String, Any?>(); for ((k, v) in have) entries[k] = v
        val planted = LinkedHashMap<String, Any?>(); for ((k, v) in havePlanted) planted[k] = v
        val ladder = LinkedHashMap<String, Any?>(); set.manifest()["ladder"]?.jsonObject?.forEach { (k, v) -> ladder[k] = v }
        val stages = HashMap<String, Long>()
        fun stage(name: String, ms: Long) = synchronized(stages) { stages.merge(name, ms, Long::plus) }
        val pool = Executors.newFixedThreadPool(workers) { r -> Thread(r, "likeness-ref").also { it.isDaemon = true } }
        val room = Semaphore(workers + 1)
        val futures = ArrayList<Future<*>>()
        // render order: by pack, then the scene band (distance), so each scene loads once
        val order = todo.filter { it.probe == null }.sortedWith(compareBy({ it.pack }, { distanceKey(it) }, { it.name }))
        val probesTodo = todo.filter { it.probe != null }
        val sources = HashMap<String, List<String>>()
        try {
            for (case in order) {
                room.acquire()
                val tr = System.nanoTime()
                val made = try { Oracle.render(case) } catch (e: Throwable) { room.release(); throw e }
                stage("render", (System.nanoTime() - tr) / 1_000_000)
                if (probesTodo.any { it.probe!!.from == case.name }) sources[case.name] = made.layers
                val plantsHere = plantsTodo.filter { it.base == case.name }
                val wantLadder = case.ladder && case.name !in ladder
                futures.add(pool.submit {
                    try {
                        val built = buildCase(set, case, made, ::stage)
                        synchronized(entries) { entries[case.name] = built.second }
                        for (p in plantsHere) {
                            val tp = System.nanoTime()
                            val pl = Plants.plant(p, made, built.first)
                            val e = writePlant(set, p, pl, built.first)
                            synchronized(planted) { planted[p.name] = e }
                            stage("plants", (System.nanoTime() - tp) / 1_000_000)
                        }
                        if (wantLadder) {
                            val tl = System.nanoTime()
                            val steps = Ladder.measure(made, case.size, built.first.ref)
                            val dm = Ladder.DM1[case.klass]
                            synchronized(ladder) {
                                ladder[case.name] = mapOf("klass" to case.klass, "steps" to steps.map { it.json() },
                                    "dm1" to dm?.map { mapOf("mad" to it.first, "over2" to it.second) }, "ratios" to Ladder.ratios(case.klass, steps))
                            }
                            stage("ladder", (System.nanoTime() - tl) / 1_000_000)
                        }
                    } finally {
                        room.release()
                    }
                })
            }
            for (f in futures) f.get()
            // the probes: drawn from the definitions of the renders they name (rendered again when restored from the set)
            for (case in probesTodo) {
                val from = case.probe!!.from
                val src = sources[from] ?: Oracle.render(cases.case(from)).layers
                val tp = System.nanoTime()
                entries[case.name] = buildProbe(set, case, src)
                stage("probes", (System.nanoTime() - tp) / 1_000_000)
            }
        } finally {
            pool.shutdownNow()
        }
        val millis = (System.nanoTime() - t0) / 1_000_000
        val m = set.manifest()
        val timing = LinkedHashMap<String, Any?>(); m["timing"]?.jsonObject?.forEach { (k, v) -> timing[k] = v }
        if (todo.isNotEmpty() || plantsTodo.isNotEmpty()) {
            timing[cases.tierName] = mapOf("cold" to have.isEmpty(), "built" to todo.size, "restored" to have.size, "millis" to millis, "stages" to stages, "arch" to System.getProperty("os.arch"),
                "cores" to Runtime.getRuntime().availableProcessors())
        }
        RefSet.write(set.manifestFile, linkedMapOf(
            "format" to 1, "key" to set.key, "engine" to EngineVersion.ID, "style" to Likeness.style, "cases" to cases.hash,
            "shard" to Likeness.shard?.let { (k, n) -> "$k/$n" },
            "oracle" to "the port's layers drawn by SvgCanvas (Android's Skia in Robolectric) at each case's density: canvas.scale(d), no view-box fit, cropped; structural without filters and without generator-only elements",
            "arch" to System.getProperty("os.arch"), "entries" to entries, "planted" to planted, "ladder" to ladder, "timing" to timing,
        ))
        return Result(set, todo.map { it.name }, have.keys.toList(), plantsTodo.map { it.name }, millis, stages)
    }

    /** A sort key that groups cases of one scene band together: views first, then by distance. */
    private fun distanceKey(c: Case): Double = when (val cam = c.camera) {
        is CameraSpec.View -> 0.0
        is CameraSpec.Sweep -> 1.0 + cam.k
        is CameraSpec.Path -> 1000.0 + cam.step
        else -> 1e6
    }

    /** Builds one case's files; returns the built reference and its manifest entry. */
    private fun buildCase(set: RefSet, case: Case, made: Oracle.Made, stage: (String, Long) -> Unit): Pair<Built, Map<String, Any?>> {
        val size = case.size
        val dir = File(set.dir, "cases/${case.slug}")
        val t1 = System.nanoTime()
        val ref = Frames.draw(made.layers, made.render.raster, size, case.mode)
        val t2 = System.nanoTime()
        // a path's frames are judged by continuity and the ceiling only: no owners, no labels
        val frameOnly = case.path != null
        val owners = if (frameOnly) null else OwnerMap.of(made, size)
        val t3 = System.nanoTime()
        val labels = if (frameOnly) null else LabelRefs.of(made.layers, size)
        val t4 = System.nanoTime()
        Png.write(File(dir, "ref.png"), ref, size.wPx, size.hPx)
        val files = linkedMapOf("ref" to "cases/${case.slug}/ref.png")
        if (owners != null && labels != null) {
            Png.writeIndex(File(dir, "owners.png"), owners.index, size.wPx, size.hPx)
            writeOwners(File(dir, "owners.json"), owners)
            RefSet.write(File(dir, "labels.json"), mapOf("labels" to labels.map { it.json() }))
            files["owners"] = "cases/${case.slug}/owners.png"; files["ownerTable"] = "cases/${case.slug}/owners.json"; files["labels"] = "cases/${case.slug}/labels.json"
        }
        val t5 = System.nanoTime()
        stage("raster", (t2 - t1) / 1_000_000); stage("owners", (t3 - t2) / 1_000_000); stage("labels", (t4 - t3) / 1_000_000); stage("write", (t5 - t4) / 1_000_000)
        val v = made.view
        val entry = linkedMapOf(
            "slug" to case.slug, "file" to case.file, "tier" to case.tier, "mode" to case.mode.name.lowercase(), "engine" to case.engine, "gates" to case.gates,
            "klass" to case.klass, "vkcheck" to case.vkcheck, "phone" to case.phone, "pack" to case.pack, "packSha256" to case.packSha256,
            "structural" to case.structural,
            "path" to (case.camera as? CameraSpec.Path)?.takeIf { case.path != null }?.let { mapOf("name" to case.path, "step" to it.step, "steps" to it.steps) },
            "size" to mapOf("name" to size.name, "w" to size.wPx, "h" to size.hPx, "density" to size.density, "frameW" to size.frameW, "frameH" to size.frameH),
            "view" to mapOf("lat" to v.lat, "lon" to v.lon, "zoom" to v.zoom, "bearing" to v.bearing, "pitch" to v.pitch, "widthDp" to v.widthDp, "heightDp" to v.heightDp, "cyDp" to v.cyDp, "distance" to v.distance),
            "moment" to when (val mo = case.moment) { is MomentSpec.Named -> mapOf("named" to mo.name); is MomentSpec.Live -> mapOf("live" to mo.instant, "forecast" to mo.forecast); null -> null },
            "render" to mapOf("view" to made.render.view, "theme" to made.render.themeKey, "moment" to made.render.moment, "tolerance" to made.render.tolerance,
                "millis" to made.millis, "relief" to (made.render.raster != null), "layers" to made.layers.size, "chars" to made.layers.sumOf { it.length }, "md5" to EngineVersion.md5(made.render),
                "tapeEntries" to made.tape.size, "items" to made.tape.items.size),
            "files" to files,
            "sha256" to files.mapValues { (_, f) -> Oracle.sha256(File(set.dir, f)) },
            "stats" to if (owners == null || labels == null) emptyMap<String, Any?>() else mapOf(
                "owners" to owners.owners.size, "owned" to owners.owned.toDouble() / owners.index.size, "buildings" to owners.share("building"), "parts" to owners.share("part"),
                "labels" to labels.size, "groundLabels" to labels.count { it.layer == "ground" }),
            "millis" to mapOf("raster" to (t2 - t1) / 1_000_000, "owners" to (t3 - t2) / 1_000_000, "labels" to (t4 - t3) / 1_000_000, "write" to (t5 - t4) / 1_000_000),
        )
        return Built(case, ref, owners, labels, made) to entry
    }

    private fun writeOwners(file: File, m: OwnerMap) {
        RefSet.write(file, mapOf("about" to "owners by index − 1: [OSM type (n w r a), id, class, kind, reference pixels]",
            "owners" to m.owners.map { listOf(OwnerMap.typeLetter(it.type), it.id, it.klass, it.kind, it.pixels) }))
    }

    private fun writePlant(set: RefSet, p: Plant, pl: Plants.Planted, base: Built): Map<String, Any?> {
        val dir = File(set.dir, "planted/${p.slug}")
        val size = base.case.size
        val files = LinkedHashMap<String, String>()
        Png.write(File(dir, "frame.png"), pl.frame, size.wPx, size.hPx); files["frame"] = "planted/${p.slug}/frame.png"
        val baseDir = "cases/${base.case.slug}"
        if (pl.owners != null) {
            Png.writeIndex(File(dir, "owners.png"), pl.owners.index, size.wPx, size.hPx); writeOwners(File(dir, "owners.json"), pl.owners)
            files["owners"] = "planted/${p.slug}/owners.png"; files["ownerTable"] = "planted/${p.slug}/owners.json"
        } else {
            files["owners"] = "$baseDir/owners.png"; files["ownerTable"] = "$baseDir/owners.json"
        }
        if (pl.labels != null) {
            RefSet.write(File(dir, "labels.json"), mapOf("labels" to pl.labels.map { it.json() })); files["labels"] = "planted/${p.slug}/labels.json"
        } else {
            files["labels"] = "$baseDir/labels.json"
        }
        val m = RefMetrics.of(pl.frame, base.ref, size.wPx, size.hPx)
        return linkedMapOf("base" to p.base, "kind" to p.kind, "expect" to p.expect, "tier" to p.tier, "detail" to pl.detail, "files" to files,
            "against" to mapOf("mad" to m.mad, "within2" to m.within2, "p99" to m.p99, "block" to m.block, "flat" to m.flatMisses))
    }

    private fun buildProbe(set: RefSet, case: Case, source: List<String>): Map<String, Any?> {
        val size = case.size
        val dir = File(set.dir, "cases/${case.slug}")
        val svg = Probes.svg(case.probe!!, source, size.frameW, size.frameH)
        dir.mkdirs()
        File(dir, "probe.svg").writeText(svg)
        val t0 = System.nanoTime()
        val ref = Frames.draw(listOf(svg), null, size, Mode.EFFECT)
        val ms = (System.nanoTime() - t0) / 1_000_000
        Png.write(File(dir, "ref.png"), ref, size.wPx, size.hPx)
        // the same probe with its effect off: how much the effect itself weighs
        val off = Frames.draw(listOf(svg), null, size, Mode.STRUCTURAL)
        val weight = RefMetrics.of(off, ref, size.wPx, size.hPx)
        return linkedMapOf(
            "slug" to case.slug, "file" to case.file, "tier" to case.tier, "mode" to "effect", "gates" to case.gates, "klass" to "probe",
            "probe" to mapOf("effect" to case.probe.effect, "from" to case.probe.from, "haze" to case.probe.haze, "vignette" to case.probe.vignette),
            "size" to mapOf("name" to size.name, "w" to size.wPx, "h" to size.hPx, "density" to size.density, "frameW" to size.frameW, "frameH" to size.frameH),
            "files" to mapOf("ref" to "cases/${case.slug}/ref.png", "svg" to "cases/${case.slug}/probe.svg"),
            "sha256" to mapOf("ref" to Oracle.sha256(File(dir, "ref.png"))),
            "stats" to mapOf("effectWeight" to weight.mad), "millis" to mapOf("raster" to ms),
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ReferencesTest {
    /**
     * The tier's reference set (`-Datlas.likeness.tier`, smoke by default): built cold, or restored from the cache CI
     * keeps for the key. With `-Datlas.likeness.budget=<s>` a cold build must take no longer (CI: 240 s for smoke).
     */
    @Test
    fun `the tier's references, owner maps, label lists, planted frames and ladder`() {
        val cases = CaseSet.load().tier(Likeness.tier, Likeness.only, Likeness.shard)
        val r = References.build(cases)
        val entries = r.set.entries()
        println("likeness references ${r.set.key} (${Likeness.tier}): ${r.built.size} built, ${r.restored.size} restored, ${r.planted.size} plants, %.1f s".format(r.millis / 1000.0))
        println("  stages (ms, summed over workers): ${r.stages.toSortedMap()}")
        for (c in cases.cases) {
            val e = entries[c.name]
            assertTrue("the reference of '${c.name}' is in the set", e != null)
            val st = e!!["stats"]?.jsonObject
            val ms = e["millis"]?.jsonObject
            println("  %-28s %s %s".format(c.name, st?.entries?.joinToString(" ") { (k, v) -> "$k=${v.jsonPrimitive.content.take(6)}" } ?: "",
                ms?.entries?.joinToString(" ") { (k, v) -> "$k ${v.jsonPrimitive.content} ms" } ?: ""))
        }
        val planted = r.set.planted()
        for (p in cases.planted.filter { it.needs == null }) {
            val e = planted[p.name]
            assertTrue("the planted frame '${p.name}' is in the set", e != null)
            println("  %-28s %s".format(p.name, e!!["detail"]!!.jsonPrimitive.content))
        }
        System.getProperty("atlas.likeness.budget")?.toLongOrNull()?.let { budget ->
            if (r.restored.isEmpty()) assertTrue("a cold ${Likeness.tier} build took %.0f s, over the budget of $budget s".format(r.millis / 1000.0), r.millis <= budget * 1000)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FramesTest {
    @Test
    fun `drawScaled puts the frame at the density with no fit, and the PNG round trip is exact`() {
        val s1 = FrameSize.NAMED.getValue("S1")
        // a 1 CSS px wide black column at x = 100 on white: exactly pixels 262.1 to 264.8 at d = 2.6214
        val svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 412 916' width='412' height='916'><rect width='412' height='916' fill='#FFFFFF'/>" +
            "<rect x='100' y='0' width='1' height='916' fill='#000000'/></svg>"
        val px = Frames.draw(listOf(svg), null, s1, Mode.STRUCTURAL)
        val row = 1200 * s1.wPx
        assertEquals(0xFFFFFFFF.toInt(), px[row + 261])
        assertEquals(0xFF000000.toInt(), px[row + 263])
        assertTrue("pixel 262 is partly covered", (px[row + 262] and 0xFF) in 1..254)
        assertTrue("pixel 264 is partly covered", (px[row + 264] and 0xFF) in 1..254)
        val f = File.createTempFile("frame", ".png")
        try {
            Png.write(f, px, s1.wPx, s1.hPx)
            val back = Png.read(f)
            assertTrue(px.contentEquals(back.px))
        } finally {
            f.delete()
        }
    }
}
