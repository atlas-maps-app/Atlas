package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How each emblem of the symbols table is drawn (the owner, 10 November, of the toilet's figures: "Their heads dont sit
 * straight", then of every symbol: "These kinds of defects"). Each is rasterised as the app draws it ([Java2DIcons])
 * and measured: where its ink sits in its frame and whether any falls outside it, how far it is from mirroring itself,
 * whether a figure's head sits over its body, and its strokes' widths. Writes emblems.tsv and sheets of what stands out
 * into the test task's folder, to judge by eye, and holds what the sweep mended: the toilets' people stand straight, every
 * emblem sits on its frame's middle and inside it, and the lopsided emblems that are drawn to mirror themselves do.
 *
 * Then, of every symbol, "Every symbol that has defects, looks bad or doesnt really resemble what it is can be re built?"
 * and "There are probably more faults with other icons across the app": on every symbol the app draws (the kinds', the
 * categories' and each the app's code names), at a fine raster, the faults a pin shows: pinholes, parts that almost
 * touch, hairlines and ring heads, and an emblem off its frame's middle. Each fault test holds the list of drawings still
 * to mend ([hold]), which the rebuild (custom_icons.txt, board_symbol_names.py) empties; and the Go screen's modes read
 * apart.
 */
class EmblemShapeTest {
    private val out = File(System.getProperty("atlas.out") ?: System.getProperty("java.io.tmpdir"))

    /** An emblem rasterised: its ink [a] (alpha 0..1) on a [w] square, its frame [side] wide at [margin] from each edge. */
    private class Raster(val a: FloatArray, val w: Int, val side: Int, val margin: Int)

    private fun raster(icon: Icon, side: Int, margin: Int = side / 8): Raster {
        val w = side + 2 * margin
        val img = BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        Java2DIcons.draw(g, icon.copy(texts = emptyList(), shadows = emptyList()), margin.toFloat(), margin.toFloat(), side.toFloat(), Color.BLACK)
        g.dispose()
        val px = img.getRGB(0, 0, w, w, null, 0, w)
        return Raster(FloatArray(w * w) { (px[it] ushr 24) / 255f }, w, side, margin)
    }

    /**
     * What one emblem measures, lengths as fractions of its frame: [box] its ink's left, top, right and bottom; [outside]
     * the share of its ink beyond the frame; [dx], [dy] its ink box's centre from the frame's; [axis] its best mirror
     * axis from the frame's centre and [asym] the share of its ink that does not mirror about it ([asymFrame] about the
     * frame's centre; 0 mirrors exactly); [head] the farthest a round part sits beside the middle of the part under it,
     * [headsDy] how far heads of a size differ in height; [widths] its strokes' widths in viewBox units; [mx], [my] its
     * centre of mass from the frame's centre; [parts] how far each of its big parts is from mirroring itself.
     */
    private class Measure(
        val id: String, val icon: Icon, val box: FloatArray, val outside: Float, val dx: Float, val dy: Float,
        val axis: Float, val asym: Float, val asymFrame: Float, val head: Float?, val headsDy: Float?, val widths: List<Float>,
        val mx: Float = 0f, val my: Float = 0f, val parts: List<Float> = emptyList(),
    ) {
        val thinPx: Float? get() = widths.minOrNull()?.let { it * 14f / max(icon.viewBox.width, icon.viewBox.height) }
    }

    /** [id]'s [Measure]; without [withParts], no parts, heads or parts' mirroring (they take a finer raster). */
    private fun measure(id: String, icon: Icon, withParts: Boolean = true): Measure {
        val r = raster(icon, 240)
        val w = r.w; val m = r.margin; val s = r.side
        var l = w; var t = w; var rt = -1; var b = -1
        var sum = 0.0; var beyond = 0.0; var sx = 0.0; var sy = 0.0
        for (y in 0 until w) for (x in 0 until w) {
            val v = r.a[y * w + x]
            if (v <= 0f) continue
            sum += v; sx += v * (x + 0.5); sy += v * (y + 0.5)
            if (x < m || y < m || x >= m + s || y >= m + s) beyond += v
            if (v >= 0.5f) { l = min(l, x); t = min(t, y); rt = max(rt, x); b = max(b, y) }
        }
        if (rt < 0) return Measure(id, icon, floatArrayOf(0f, 0f, 0f, 0f), 0f, 0f, 0f, 0f, 1f, 1f, null, null, widths(icon))
        val box = floatArrayOf((l - m) / s.toFloat(), (t - m) / s.toFloat(), (rt + 1f - m) / s, (b + 1f - m) / s)
        val dx = ((l + rt + 1) / 2f - (m + s / 2f)) / s
        val dy = ((t + b + 1) / 2f - (m + s / 2f)) / s
        // the best axis near the ink's centre of mass, in half pixels, coarse then fine
        val c0 = (2 * sx / sum).roundToInt()
        var best = c0; var bestAsym = asym(r, c0, t, b)
        for (c in c0 - 24..c0 + 24 step 2) asym(r, c, t, b).let { if (it < bestAsym) { bestAsym = it; best = c } }
        for (c in best - 1..best + 1) asym(r, c, t, b).let { if (it < bestAsym) { bestAsym = it; best = c } }
        val frameAxis = 2 * m + s
        val ps = if (withParts) parts(raster(icon, 480)) else null
        val (head, headsDy) = ps?.let { heads(it) } ?: (null to null)
        return Measure(
            id, icon, box, (beyond / sum).toFloat(), dx, dy, (best - frameAxis) / 2f / s, bestAsym, asym(r, frameAxis, t, b),
            head, headsDy, widths(icon), ((sx / sum - (m + s / 2.0)) / s).toFloat(), ((sy / sum - (m + s / 2.0)) / s).toFloat(),
            ps?.let { bigParts(it).map { p -> p.second } } ?: emptyList(),
        )
    }

    /** The share of the ink in rows [t]..[b] that does not mirror about the axis at [c2] half pixels. */
    private fun asym(r: Raster, c2: Int, t: Int, b: Int): Float {
        var d = 0.0; var s = 0.0
        val w = r.w
        for (y in t..b) {
            val row = y * w
            for (x in 0 until w) {
                val v = r.a[row + x]
                val xm = c2 - x - 1
                val u = if (xm in 0 until w) r.a[row + xm] else 0f
                d += abs(v - u); s += v
            }
        }
        return if (s == 0.0) 1f else (d / (2 * s)).toFloat()
    }

    /** How far [part] is from mirroring itself about its own best axis: the share of its pixels without a twin. */
    private fun partAsym(ps: Parts, part: Part): Float {
        val w = ps.r.w
        fun at(x: Int, y: Int) = x in 0 until w && ps.label[y * w + x] == part.id
        val c0 = (2 * part.cx).roundToInt()
        var best = 1f
        for (c2 in c0 - 6..c0 + 6) {
            var d = 0
            for (y in part.t..part.b) for (x in part.l..part.r) if (at(x, y) && !at(c2 - x - 1, y)) d++
            best = min(best, d.toFloat() / part.area)
        }
        return best
    }

    /** The parts of a tenth of the ink or more, each with how far it is from mirroring itself. */
    private fun bigParts(ps: Parts): List<Pair<Part, Float>> {
        val total = ps.all.sumOf { it.area }
        return ps.all.filter { it.area >= total * 0.1 }.map { it to partAsym(ps, it) }
    }

    private fun widths(icon: Icon): List<Float> =
        icon.paths.filter { it.stroke }.map { ((it.strokeWidth ?: icon.strokeWidth) * 20f).roundToInt() / 20f }.distinct().sorted()

    /** A part of the ink: its box, its area in pixels and its centre. */
    private class Part(val id: Int, var l: Int, var t: Int, var r: Int, var b: Int, var area: Int = 0, var sx: Long = 0, var sy: Long = 0) {
        val cx get() = sx.toFloat() / area + 0.5f
        val cy get() = sy.toFloat() / area + 0.5f
        val width get() = r - l + 1
        val height get() = b - t + 1
    }

    /**
     * Heads over bodies: a round part (a disc or a ring, as wide as high, a fifth of the ink or less) with a part under
     * it, near and overlapping it, is a head on a body; how far its centre sits from the middle of the body's top (its
     * rows as deep as the head), the farthest of them, and how far apart in height heads of one size sit.
     */
    private class Parts(val r: Raster, val label: IntArray, val all: List<Part>)

    /** The ink's parts: its pieces that touch nowhere (alpha half or more, four neighbours). */
    private fun parts(r: Raster): Parts {
        val w = r.w
        val label = IntArray(w * w) { -1 }
        val parts = ArrayList<Part>()
        val queue = IntArray(w * w)
        for (start in 0 until w * w) {
            if (r.a[start] < 0.5f || label[start] >= 0) continue
            val p = Part(parts.size, w, w, -1, -1)
            var head = 0; var tail = 0
            queue[tail++] = start; label[start] = p.id
            while (head < tail) {
                val i = queue[head++]
                val x = i % w; val y = i / w
                p.area++; p.sx += x; p.sy += y
                p.l = min(p.l, x); p.r = max(p.r, x); p.t = min(p.t, y); p.b = max(p.b, y)
                for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < w - 1) i + w else -1)) {
                    if (j >= 0 && r.a[j] >= 0.5f && label[j] < 0) { label[j] = p.id; queue[tail++] = j }
                }
            }
            parts += p
        }
        return Parts(r, label, parts)
    }

    private fun heads(r: Raster): Pair<Float?, Float?> = heads(parts(r))

    /** The round parts of [ps] that may be heads: as wide as high, a disc or a ring, a fifth of the ink or less. */
    private fun roundParts(ps: Parts): List<Part> {
        val total = ps.all.sumOf { it.area }
        return ps.all.filter { p ->
            val ratio = p.width.toFloat() / p.height
            val fill = p.area.toFloat() / (p.width * p.height)
            p.area > total * 0.004 && p.area <= total * 0.2 && ratio in 0.8f..1.25f && fill in 0.55f..0.9f && p.width < ps.r.side * 0.4f
        }
    }

    /** The body under the round part [h] of [ps]: the nearest bigger part below it that it overlaps, if one is near. */
    private fun bodyUnder(ps: Parts, h: Part): Part? =
        ps.all.filter { it !== h && it.t > h.b && it.t - h.b < ps.r.side * 0.2f && it.l <= h.r && it.r >= h.l && it.area > h.area }.minByOrNull { it.t }

    private fun heads(ps: Parts): Pair<Float?, Float?> {
        val r = ps.r; val w = r.w; val label = ps.label
        val heads = roundParts(ps)
        var worst: Float? = null
        for (h in heads) {
            val body = bodyUnder(ps, h) ?: continue
            var sx = 0L; var n = 0
            for (y in body.t until min(body.b + 1, body.t + h.height)) for (x in body.l..body.r) if (label[y * w + x] == body.id) { sx += x; n++ }
            if (n == 0) continue
            val off = (h.cx - (sx.toFloat() / n + 0.5f)) / r.side
            if (worst == null || abs(off) > abs(worst)) worst = off
        }
        var dy: Float? = null
        for (i in heads.indices) for (j in i + 1 until heads.size) {
            val p = heads[i]; val q = heads[j]
            if (p.area.toFloat() / q.area !in 0.8f..1.25f || abs(p.cy - q.cy) > r.side * 0.1f) continue
            val d = abs(p.cy - q.cy) / r.side
            if (dy == null || d > dy) dy = d
        }
        return worst to dy
    }

    /** Every symbol the table draws, with its kinds' first family's colour. */
    private fun emblems(): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (line in Kinds.lines()) {
            if (line.symbol in out) continue
            val colour = Presets.byId(line.kind)?.let { Symbols.familyOf(Kinds.sample(it)) }?.colour ?: Palette.SERVICE
            out[line.symbol] = colour
        }
        // the toilets the app draws besides the table's: the Charge, Pump and Needs screens' own
        for (id in listOf("need.toilet", "need.toilet.2", "need.toilet.3", "need.toilet.4", "need.toilet.5")) out.putIfAbsent(id, Palette.SERVICE)
        return out
    }

    /**
     * Every symbol the app draws (the owner, 10 November: "There are probably more faults with other icons across the
     * app"): the table's, the search's categories', and every id the app's own code names that a symbol answers to, its
     * string literals under each module's src/main/kotlin (the boards' copies under app/src/test are left out).
     */
    private val drawn: Map<String, Int> by lazy {
        val out = LinkedHashMap(emblems())
        for (c in Categories.all()) if (Symbols.icon(c.symbol) != null) out.putIfAbsent(c.symbol, Palette.SERVICE)
        val android = File(System.getProperty("atlas.repo") ?: "../../..", "app/android")
        val literal = Regex("\"([a-z][a-z0-9]*(?:[.-][a-z0-9_]+)+)\"")
        val modules = android.listFiles()?.sortedBy { it.name }.orEmpty()
        for (module in modules) {
            val src = File(module, "src/main/kotlin").takeIf { it.isDirectory } ?: continue
            for (f in src.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }) {
                for (m in literal.findAll(f.readText())) m.groupValues[1].let { if (Symbols.icon(it) != null) out.putIfAbsent(it, Palette.SERVICE) }
            }
        }
        out
    }

    /**
     * The toilets' people stand straight (the owner, 10 November: "Their heads dont sit straight"). In every toilet the
     * app draws (the map's, the search's and a place's facts: maki-toilet, ui-toilet; the Charge, Pump and Needs
     * screens'), each head sits over the middle of its body, both heads at one height, and each figure mirrors itself.
     */
    @Test
    fun aToiletsPeopleStandStraight() {
        for (id in TOILETS) {
            val ps = parts(raster(Symbols.icon(id)!!, 480))
            val (head, dy) = heads(ps)
            assertNotNull("$id: no head over a body", head)
            assertTrue("$id: a head sits %.1f %% of the frame beside its body".format(head!! * 100), abs(head) < 0.005f)
            assertTrue("$id: its heads are %.1f %% of the frame apart in height".format((dy ?: 1f) * 100), dy != null && dy < 0.003f)
            val figures = bigParts(ps).filter { (p, _) -> p.height > ps.r.side * 0.4f && p.width > ps.r.side * 0.1f }
            assertEquals("$id: its figures", 2, figures.size)
            for ((p, asym) in figures) assertTrue(
                "$id: the figure at %.0f %% of the frame is %.1f %% off mirroring itself".format((p.cx - ps.r.margin) * 100f / ps.r.side, asym * 100), asym < 0.01f,
            )
        }
    }

    /**
     * Every symbol the app draws sits on its frame's middle, so on the middle of a pin's disc and of a row's tile, and
     * keeps its ink inside the frame (the sweep after the toilet's heads, the owner: "These kinds of defects"). Where a
     * symbol's box and its weight sit off the middle the same way, the lesser of the two is under 4 % of the frame, a
     * unit of a 24 grid (0.6 px in a pin, 0.9 px in a row). The drawings that sat off have their frames moved onto them
     * (board_symbol_names.FRAMES, tables.ICON_FRAMES), Atlas's own are drawn in the middle (custom_icons.txt) and the
     * dentist's tooth by its mend (tables.ICON_MENDS; the boards' own by board_symbol_names.MENDS).
     */
    @Test
    fun everySymbolSitsOnItsFramesMiddle() {
        val measures = drawn.keys.mapNotNull { id -> Symbols.icon(id)?.let { measure(id, it, withParts = false) } }
        assertTrue("measured ${measures.size}", measures.size > 600)
        val off = measures.filter { off(it) >= 0.04f }
        hold("off their frame's middle", off.associate { it.id to "box %+.3f %+.3f, weight %+.3f %+.3f".format(it.dx, it.dy, it.mx, it.my) }, OFF_CENTRE_LEFT)
        val out = measures.filter { it.outside > 0.005f }
        assertTrue("ink out of the frame: " + out.joinToString { "%s %.1f %%".format(it.id, it.outside * 100) }, out.isEmpty())
    }

    /**
     * The emblems drawn to mirror themselves do, about their frame's middle (the sweep after the toilet's heads): the
     * dentist's tooth, whose crown dipped a unit beside its axis, and the antenna, whose right inner wave stood a quarter
     * unit nearer the mast than its left one, so the gaps beside the mast were uneven.
     */
    @Test
    fun symmetricEmblemsMirror() {
        for (id in SYMMETRIC) {
            val e = measure(id, Symbols.icon(id)!!, withParts = false)
            assertTrue("$id: %.2f %% of its ink does not mirror about its frame's middle".format(e.asymFrame * 100), e.asymFrame < 0.006f)
        }
        // the fountain's jets, above its basin's water: the right one fell short of the left one's mirror
        val r = raster(Symbols.icon("temaki-fountain")!!, 240)
        val jets = asym(r, 2 * r.margin + r.side, r.margin, r.margin + (r.side * 0.72f).toInt())
        assertTrue("temaki-fountain: %.2f %% of its jets' ink does not mirror about its frame's middle".format(jets * 100), jets < 0.01f)
    }

    /**
     * No symbol the app draws has a pinhole: a hole in its ink under a unit of a 24 frame across ([PINHOLE]), which a pin
     * draws as a speck and a place's header as a dot, so the symbol is another at each size. The toilet's heads were
     * rings round a pinhole, as the walker's and the wheelchair users' were; so are a few wheels, a train's lamps and a
     * pizza's slices of salami. A head or a hub is a solid dot, a ring keeps a hole that reads as one. Slivers where two
     * strokes meet ([SLIVER]) show at no size; pictures (the High Street's signs) are drawn large and have their own rules.
     */
    @Test
    fun noSymbolHasAPinhole() {
        val faults = LinkedHashMap<String, String>()
        for (id in drawn.keys) pinholes(Symbols.icon(id)!!).takeIf { it.isNotEmpty() }?.let { s -> faults[id] = s.map { it.size / UNIT }.sorted().joinToString(" ") { "%.2f".format(it) } }
        hold("pinholes, each hole's radius in units of a 24 frame", faults, PINHOLES_LEFT)
    }

    /**
     * No two parts of a symbol the app draws almost touch: they are joined, or at least a quarter of a unit of a 24 grid
     * apart ([NEAR]). Nearer, they run together in a pin and a crack shows between them at a place's header: a fish's eye
     * 0.02 from its outline, a loaf's cuts 0.11 from its crust, a mushroom's spot from its cap, the refresh arrow's head
     * from its arc. A head's neck, a unit, and the open sets' own gaps pass.
     */
    @Test
    fun noPartsAlmostTouch() {
        val faults = LinkedHashMap<String, String>()
        for (id in drawn.keys) nearParts(Symbols.icon(id)!!).takeIf { it.isNotEmpty() }?.let { s -> faults[id] = s.map { it.size / UNIT }.sorted().joinToString(" ") { "%.2f".format(it) } }
        hold("parts that almost touch, each gap in units of a 24 frame", faults, NEAR_LEFT)
    }

    /**
     * No symbol the app draws has a hairline: ink that runs thinner than 2 % of its frame ([HAIRLINE], half a unit of a 24
     * grid) for longer than a unit, which fades or breaks in a pin: an ATM's banknote, a newspaper machine's papers.
     */
    @Test
    fun noSymbolHasAHairline() {
        val faults = LinkedHashMap<String, String>()
        for (id in drawn.keys) hairlines(Symbols.icon(id)!!).takeIf { it.isNotEmpty() }?.let { s -> faults[id] = s.map { it.size / UNIT }.sorted().joinToString(" ") { "%.1f".format(it) } }
        hold("hairlines, each run's length in units of a 24 frame", faults, HAIRLINES_LEFT)
    }

    /**
     * A figure's head is a solid dot (the owner, 10 November, of the walker: "And the walking symbol has a defect"): the
     * walker's, the wheelchair users' and the children's heads, like the toilet's, were rings round a hole of 0.6 to
     * 1.05 units of a 24 grid, a speck in a pin and a hole at a place's header, and most touched their bodies. In every
     * symbol the app draws but the pictures, a head apart from its body has no hole, and in the people drawn in strokes
     * ([FIGURES]) no head has one, apart or not. A profile's outline head, its hole wider than [HEAD_HOLE], passes.
     */
    @Test
    fun aFiguresHeadIsSolid() {
        for (id in FIGURES) assertNotNull("$id, in FIGURES, is no symbol", Symbols.icon(id))
        val faults = LinkedHashMap<String, String>()
        for (id in drawn.keys) ringHeads(Symbols.icon(id)!!, id in FIGURES).takeIf { it.isNotEmpty() }?.let { s -> faults[id] = s.map { it.size / UNIT }.sorted().joinToString(" ") { "%.2f".format(it) } }
        hold("heads that are rings, each hole's radius in units of a 24 frame", faults, RING_HEADS_LEFT)
    }

    /**
     * The Go board's modes read apart where they sit side by side (the owner, of the bicycles: "Some bike symbols have
     * defects?"): the walker, the bicycle, the cargo bike and the motorbike score under [LookAlikes.ALIKE] against each
     * other. The e-bike is the bicycle with a bolt, on purpose; its bolt tells it.
     */
    @Test
    fun theGoModesReadApart() {
        val modes = listOf("mode.walk.2", "park.bike", "mode.cargo-bike", "mode.moto")
        val looks = modes.associateWith { LookAlikes.look(Symbols.icon(it)!!) }
        for (i in modes.indices) for (j in i + 1 until modes.size) {
            val s = LookAlikes.score(looks.getValue(modes[i]), looks.getValue(modes[j]))
            assertTrue("${modes[i]} and ${modes[j]} read alike: %.2f".format(s), s < LookAlikes.ALIKE)
        }
        val bike = Symbols.icon("park.bike")!!; val ebike = Symbols.icon("mode.ebike")!!
        assertTrue("the e-bike draws more than the bicycle: its bolt", ebike.paths.size > bike.paths.size)
    }

    /** [icon]'s holes smaller than [PINHOLE] and no sliver ([SLIVER]), at [FINE]; none in a picture. */
    private fun pinholes(icon: Icon): List<Spot> =
        if (icon.coloured) emptyList() else holes(raster(icon, FINE)).filter { it.size >= SLIVER * UNIT && it.size < PINHOLE * UNIT }

    /** Where [icon]'s parts come nearer each other than [NEAR], at [FINE]; none in a picture. */
    private fun nearParts(icon: Icon): List<Spot> = if (icon.coloured) emptyList() else gaps(parts(raster(icon, FINE)), NEAR * UNIT)

    /**
     * The holes in the heads of [icon]'s figures, at [FINE]; none in a picture. In any drawing, a round part apart from
     * the rest, under 30 % of the frame wide, over a body, is a head (a window over a machine is no head), and a hole in
     * it smaller than half its width is a fault (a snowflake in a window is no ring). A head that touches its body, as
     * the wheelchair users' did, is no part of its own, so in a [figure] a head is also found by its hole: a round hole
     * no bigger than [HEAD_HOLE], ringed all round by ink, free above, with ink under it within a fifth of the frame.
     * Only in a figure, since an anchor's ring, a key's bow and a pin's eye are drawn just so.
     */
    private fun ringHeads(icon: Icon, figure: Boolean): List<Spot> {
        if (icon.coloured) return emptyList()
        val r = raster(icon, FINE)
        val ps = parts(r)
        val apart = roundParts(ps).filter { it.width < r.side * 0.3f && bodyUnder(ps, it) != null }
        fun ink(x: Float, y: Float): Boolean {
            val i = x.toInt(); val j = y.toInt()
            return i in 0 until r.w && j in 0 until r.w && r.a[j * r.w + i] >= 0.5f
        }
        /** The share of the points on a circle of [radius] round ([cx], [cy]) that are ink, from angle [from] to [to] (y down). */
        fun around(cx: Float, cy: Float, radius: Float, from: Double = 0.0, to: Double = 2 * Math.PI): Float {
            val n = 96
            return (0 until n).count { k -> val t = from + (to - from) * k / n; ink(cx + radius * kotlin.math.cos(t).toFloat(), cy + radius * kotlin.math.sin(t).toFloat()) } / n.toFloat()
        }
        fun ringedHead(h: Spot, cx: Float, cy: Float): Boolean {
            if (h.size < SLIVER * UNIT || h.size > HEAD_HOLE * UNIT) return false
            if (around(cx, cy, h.size + 0.75f * UNIT) <= 0.9f) return false
            if (around(cx, cy, h.size + 2.75f * UNIT, Math.PI, 2 * Math.PI) >= 0.25f) return false
            val top = cy + h.size + 2.75f * UNIT; val half = (h.size + UNIT).toInt()
            return (0..(r.side * 0.2f).toInt() step 2).any { d -> (-half..half step 2).any { e -> ink(cx + e, top + d) } }
        }
        return holes(r).filter { h ->
            val cx = h.x * r.side + r.margin; val cy = h.y * r.side + r.margin
            apart.any { cx.toInt() in it.l..it.r && cy.toInt() in it.t..it.b && h.size < it.width / 2f } || (figure && ringedHead(h, cx, cy))
        }
    }

    /** Where [icon]'s ink runs thinner than [HAIRLINE] for longer than a unit, at [FINE]; none in a picture. */
    private fun hairlines(icon: Icon): List<Spot> =
        if (icon.coloured) emptyList() else thinRuns(raster(icon, FINE), HAIRLINE * UNIT).filter { it.size > UNIT }

    /**
     * A fault found in a raster: where it is, as fractions of the frame from its top left, and how big, in the raster's
     * pixels (a hole's radius, a gap's width, a hairline's length).
     */
    private class Spot(val x: Float, val y: Float, val size: Float)

    /**
     * Holds a test's [faults] (each drawing's id and what it measures) to its list of those still [left] to mend: no
     * drawing off the list may fail, and each on it must still fail, so that a mended one leaves the list. The lists
     * empty as the rebuild goes on (the owner, 10 November: "Every symbol that has defects, looks bad or doesnt really
     * resemble what it is can be re built?").
     */
    private fun hold(what: String, faults: Map<String, String>, left: Set<String>) {
        val fresh = faults.filterKeys { it !in left }
        assertTrue("$what: " + fresh.entries.joinToString { "${it.key} (${it.value})" }, fresh.isEmpty())
        val gone = left.filter { it !in faults }
        assertTrue("no longer $what, so off its list of those left: " + gone.joinToString(), gone.isEmpty())
    }

    /**
     * The holes in [r]'s ink: its background pieces (alpha under half, four neighbours) that reach no edge, each with the
     * radius of a disc of its area.
     */
    private fun holes(r: Raster): List<Spot> {
        val w = r.w
        val seen = BooleanArray(w * w)
        val queue = IntArray(w * w)
        val out = ArrayList<Spot>()
        for (start in 0 until w * w) {
            if (r.a[start] >= 0.5f || seen[start]) continue
            var head = 0; var tail = 0; var edge = false
            queue[tail++] = start; seen[start] = true
            while (head < tail) {
                val i = queue[head++]
                val x = i % w; val y = i / w
                if (x == 0 || y == 0 || x == w - 1 || y == w - 1) edge = true
                for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < w - 1) i + w else -1)) {
                    if (j >= 0 && !seen[j] && r.a[j] < 0.5f) { seen[j] = true; queue[tail++] = j }
                }
            }
            if (edge) continue
            var sx = 0L; var sy = 0L
            for (k in 0 until tail) { sx += queue[k] % w; sy += queue[k] / w }
            out += Spot((sx.toFloat() / tail + 0.5f - r.margin) / r.side, (sy.toFloat() / tail + 0.5f - r.margin) / r.side, kotlin.math.sqrt(tail / Math.PI).toFloat())
        }
        return out
    }

    /**
     * The narrowest gap between each two parts of [ps] that come within [limit] pixels of each other (parts of 40 pixels
     * or more; a speck of antialiasing is no part), in pixels between their edges, where it is narrowest.
     */
    private fun gaps(ps: Parts, limit: Float): List<Spot> {
        val w = ps.r.w
        val big = ps.all.filter { it.area >= 40 }.map { it.id }.toSet()
        val cell = max(1, limit.toInt() + 2)
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(cx: Int, cy: Int) = cx.toLong() * 100_003L + cy
        val edge = ArrayList<Int>()
        for (i in 0 until w * w) {
            val p = ps.label[i]
            if (p < 0 || p !in big) continue
            val x = i % w; val y = i / w
            val inner = x > 0 && x < w - 1 && y > 0 && y < w - 1 && ps.label[i - 1] == p && ps.label[i + 1] == p && ps.label[i - w] == p && ps.label[i + w] == p
            if (inner) continue
            edge += i
            grid.getOrPut(key(x / cell, y / cell)) { ArrayList() } += i
        }
        val best = HashMap<Long, Spot>()
        for (i in edge) {
            val p = ps.label[i]; val x = i % w; val y = i / w
            for (gx in x / cell - 1..x / cell + 1) for (gy in y / cell - 1..y / cell + 1) {
                for (j in grid[key(gx, gy)] ?: continue) {
                    val q = ps.label[j]
                    if (q <= p) continue
                    val dx = (j % w - x).toFloat(); val dy = (j / w - y).toFloat()
                    val gap = max(0f, kotlin.math.sqrt(dx * dx + dy * dy) - 1f)
                    if (gap >= limit) continue
                    val k = p.toLong() * 100_003L + q
                    if (gap < (best[k]?.size ?: Float.MAX_VALUE)) {
                        best[k] = Spot(((x + j % w) / 2f + 0.5f - ps.r.margin) / ps.r.side, ((y + j / w) / 2f + 0.5f - ps.r.margin) / ps.r.side, gap)
                    }
                }
            }
        }
        return best.values.toList()
    }

    /**
     * Where [r]'s ink runs thinner than [thin] pixels: the ridge of its ink (each pixel as far from the background as
     * any of its neighbours) where twice that distance is under [thin], in runs of touching ridge pixels, each run's
     * length in pixels and where it runs. A shape's corner thins to nothing over a few pixels; a hairline runs on.
     */
    private fun thinRuns(r: Raster, thin: Float): List<Spot> {
        val w = r.w
        val d = distances(r)
        val ridge = BooleanArray(w * w)
        for (y in 1 until w - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (d[i] <= 0.0 || 2 * kotlin.math.sqrt(d[i]) >= thin) continue
            var top = true
            for (dy in -1..1) for (dx in -1..1) if (d[i + dy * w + dx] > d[i]) top = false
            ridge[i] = top
        }
        val seen = BooleanArray(w * w)
        val queue = IntArray(w * w)
        val out = ArrayList<Spot>()
        for (start in 0 until w * w) {
            if (!ridge[start] || seen[start]) continue
            var head = 0; var tail = 0
            var l = w; var t = w; var rt = -1; var b = -1
            queue[tail++] = start; seen[start] = true
            while (head < tail) {
                val i = queue[head++]
                val x = i % w; val y = i / w
                l = min(l, x); rt = max(rt, x); t = min(t, y); b = max(b, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val j = i + dy * w + dx
                    if (j in 0 until w * w && ridge[j] && !seen[j]) { seen[j] = true; queue[tail++] = j }
                }
            }
            val length = kotlin.math.sqrt(((rt - l + 1) * (rt - l + 1) + (b - t + 1) * (b - t + 1)).toFloat())
            out += Spot(((l + rt + 1) / 2f - r.margin) / r.side, ((t + b + 1) / 2f - r.margin) / r.side, length)
        }
        return out
    }

    /** Each ink pixel's squared distance to the nearest background pixel (alpha under half), exactly, 0 on the background. */
    private fun distances(r: Raster): DoubleArray {
        val w = r.w
        val inf = 1e20
        val f = DoubleArray(w * w) { if (r.a[it] >= 0.5f) inf else 0.0 }
        val line = DoubleArray(w); val out = DoubleArray(w); val v = IntArray(w); val z = DoubleArray(w + 1)
        fun pass(n: Int) {
            var k = 0
            v[0] = 0; z[0] = -inf; z[1] = inf
            for (q in 1 until n) {
                var s: Double
                while (true) {
                    s = ((line[q] + q.toDouble() * q) - (line[v[k]] + v[k].toDouble() * v[k])) / (2.0 * q - 2.0 * v[k])
                    if (s <= z[k] && k > 0) k-- else break
                }
                if (s <= z[k]) { v[k] = q; z[k] = -inf; z[k + 1] = inf; continue }
                k++; v[k] = q; z[k] = s; z[k + 1] = inf
            }
            k = 0
            for (q in 0 until n) {
                while (z[k + 1] < q) k++
                out[q] = (q - v[k]).toDouble() * (q - v[k]) + line[v[k]]
            }
        }
        for (x in 0 until w) {
            for (y in 0 until w) line[y] = f[y * w + x]
            pass(w)
            for (y in 0 until w) f[y * w + x] = out[y]
        }
        for (y in 0 until w) {
            for (x in 0 until w) line[x] = f[y * w + x]
            pass(w)
            for (x in 0 until w) f[y * w + x] = out[x]
        }
        return f
    }

    @Test
    fun sheets() {
        val colours = drawn
        val measures = colours.keys.mapNotNull { id -> Symbols.icon(id)?.let { measure(id, it) } }
        assertTrue("measured ${measures.size}", measures.size > 300)
        File(out, "emblems.tsv").printWriter().use { w ->
            w.println("id\tboard\tvb\tleft\ttop\tright\tbottom\toutside\tdx\tdy\taxis\tasym\tasymFrame\thead\theadsDy\twidths\tthinPx\tmx\tmy\tparts")
            for (e in measures.sortedBy { it.id }) w.println(
                listOf(e.id, Symbols.boardId(e.id) ?: "", "%s %s".format(e.icon.viewBox.width, e.icon.viewBox.height)).joinToString("\t") + "\t" +
                    (e.box.map { "%.3f".format(it) } + listOf(e.outside, e.dx, e.dy, e.axis, e.asym, e.asymFrame).map { "%.4f".format(it) } +
                        listOf(e.head?.let { "%.4f".format(it) } ?: "", e.headsDy?.let { "%.4f".format(it) } ?: "", e.widths.joinToString(" "), e.thinPx?.let { "%.2f".format(it) } ?: "", "%.4f".format(e.mx), "%.4f".format(e.my), e.parts.joinToString(" ") { "%.3f".format(it) })).joinToString("\t"),
            )
        }
        fun sheet(name: String, title: String, picked: List<Pair<Measure, String>>, mirror: Boolean = false) =
            draw(picked, colours, title, File(out, "emblems-$name.png"), mirror)
        sheet("outside", "Ink beyond the frame", measures.filter { it.outside > 0.001f }.sortedByDescending { it.outside }
            .map { it to "%.1f %% outside".format(it.outside * 100) })
        sheet("offcentre", "Off the frame's centre, by its box and its weight", measures.filter { off(it) > 0.03f }.sortedByDescending { off(it) }
            .map { it to "box %+.3f %+.3f · mass %+.3f %+.3f".format(it.dx, it.dy, it.mx, it.my) })
        sheet("lopsided", "Nearly mirrored", measures.filter { it.asym in 0.006f..0.08f }.sortedBy { it.asym }
            .map { it to "asym %.3f axis %+.3f".format(it.asym, it.axis) }, mirror = true)
        sheet("parts", "A part nearly mirroring itself", measures.filter { e -> e.parts.any { it in 0.012f..0.08f } }.sortedBy { e -> e.parts.filter { it >= 0.012f }.minOrNull() }
            .map { it to "parts " + it.parts.joinToString(" ") { p -> "%.3f".format(p) } })
        sheet("heads", "A head beside its body, heads at two heights", measures.filter { abs(it.head ?: 0f) > 0.012f || (it.headsDy ?: 0f) > 0.004f }
            .sortedByDescending { abs(it.head ?: 0f) }.map { it to "head %+.3f dy %.3f".format(it.head ?: 0f, it.headsDy ?: 0f) })
        sheet("strokes", "Strokes of two weights, or thin at a pin", measures.filter { (it.widths.size > 1 && it.widths.last() / it.widths.first() > 1.3f) || (it.thinPx ?: 9f) < 0.75f }
            .sortedBy { it.thinPx ?: 9f }.map { it to "w ${it.widths.joinToString(" ")} · %.2f px".format(it.thinPx ?: 0f) })
        // the faults the tests look for, in every symbol the app draws, each ringed red where it is
        fun faults(name: String, title: String, find: (Icon) -> List<Spot>, worst: (List<Spot>) -> Float, note: (List<Spot>) -> String) {
            val found = drawn.keys.mapNotNull { id -> Symbols.icon(id)?.let { icon -> find(icon).takeIf { it.isNotEmpty() }?.let { Triple(id, icon, it) } } }
                .sortedBy { worst(it.third) }
            draw(found.map { (id, icon, spots) -> measure(id, icon, withParts = false) to note(spots) }, drawn, title, File(out, "emblems-$name.png"), false,
                found.associate { it.first to it.third })
        }
        faults("pinholes", "Holes too small to read as holes (radius in units of 24)", { pinholes(it) }, { s -> s.minOf { it.size } },
            { s -> "r " + s.map { it.size / UNIT }.sorted().take(6).joinToString(" ") { "%.2f".format(it) } })
        faults("near", "Parts that almost touch (gap in units of 24)", { nearParts(it) }, { s -> s.minOf { it.size } },
            { s -> "gap " + s.map { it.size / UNIT }.sorted().take(6).joinToString(" ") { "%.2f".format(it) } })
        faults("hairlines", "Hairlines (run's length in units of 24)", { hairlines(it) }, { s -> -s.maxOf { it.size } },
            { s -> "run " + s.map { it.size / UNIT }.sortedDescending().take(6).joinToString(" ") { "%.1f".format(it) } })
    }

    /** A sheet: each emblem large with the frame's centre (red) and its own axis (blue) over it, then at a pin's, a chip's and a row's size. */
    private fun draw(picked: List<Pair<Measure, String>>, colours: Map<String, Int>, title: String, file: File, mirror: Boolean, marks: Map<String, List<Spot>> = emptyMap()) {
        val cols = 8; val cw = 210; val ch = 270; val top = 70
        val rows = max(1, (picked.size + cols - 1) / cols)
        val img = BufferedImage(cols * cw + 40, top + rows * ch + 20, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0xF4F2EE); g.fillRect(0, 0, img.width, img.height)
        g.color = Color(0x1B1B1B); g.font = Font(Font.SANS_SERIF, Font.BOLD, 26)
        g.drawString("$title · ${picked.size}", 20, 44)
        for ((i, pair) in picked.withIndex()) {
            val (e, note) = pair
            val x = 20 + (i % cols) * cw; val y = top + (i / cols) * ch
            val big = 150f; val bx = x + (cw - big) / 2f; val by = y.toFloat()
            g.color = Color.WHITE; g.fill(Rectangle2D.Float(bx, by, big, big))
            g.color = Color(0xD8D4CC); g.stroke = BasicStroke(1f); g.draw(Rectangle2D.Float(bx, by, big, big))
            Java2DIcons.draw(g, e.icon, bx, by, big, Color(0x1B1B1B))
            if (mirror) g.drawImage(unmirrored(e, big.toInt()), bx.toInt(), by.toInt(), null)
            g.stroke = BasicStroke(1f)
            g.color = Color(0xE5, 0x39, 0x35, 150); g.draw(Line2D.Float(bx + big / 2, by, bx + big / 2, by + big))
            g.color = Color(0x1E, 0x6F, 0xE8, 170); g.draw(Line2D.Float(bx + big / 2 + e.axis * big, by, bx + big / 2 + e.axis * big, by + big))
            g.color = Color(0xE5, 0x39, 0x35, 210); g.stroke = BasicStroke(2f)
            for (s in marks[e.id].orEmpty()) g.draw(Ellipse2D.Float(bx + s.x * big - 9f, by + s.y * big - 9f, 18f, 18f))
            g.stroke = BasicStroke(1f)
            val c = Color(colours[e.id] ?: Palette.SERVICE)
            val ry = by + big + 8
            g.color = c; g.fill(Ellipse2D.Float(x + 18f, ry, 26f, 26f)); Java2DIcons.draw(g, e.icon, x + 24f, ry + 6f, 14f, Color.WHITE)
            g.color = c; g.fill(Ellipse2D.Float(x + 56f, ry - 7f, 40f, 40f)); Java2DIcons.draw(g, e.icon, x + 67f, ry + 4f, 18f, Color.WHITE)
            Java2DIcons.draw(g, e.icon, x + 108f, ry + 2f, 22f, c)
            Java2DIcons.draw(g, e.icon, x + 140f, ry - 5f, 36f, c)
            g.color = Color(0x1B1B1B); g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
            g.drawString(e.id.take(30), x + 6, (ry + 50).toInt())
            g.color = Color(0x5A5F6C); g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
            g.drawString(note, x + 6, (ry + 65).toInt())
            (Symbols.boardId(e.id)?.takeIf { it != e.id })?.let { g.drawString("= $it", x + 6, (ry + 79).toInt()) }
        }
        g.dispose()
        ImageIO.write(img, "png", file)
    }

    /** Where [e] differs from its mirror about its best axis: red where its ink has no twin, blue where its twin has no ink. */
    private fun unmirrored(e: Measure, side: Int): BufferedImage {
        val r = raster(e.icon, side, 0)
        val img = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        val c2 = (2 * (side / 2f + e.axis * side)).roundToInt()
        for (y in 0 until side) for (x in 0 until side) {
            val v = r.a[y * side + x]
            val xm = c2 - x - 1
            val u = if (xm in 0 until side) r.a[y * side + xm] else 0f
            if (v - u > 0.4f) img.setRGB(x, y, 0xC0E53935.toInt()) else if (u - v > 0.4f) img.setRGB(x, y, 0x901E6FE8.toInt())
        }
        return img
    }

    private companion object {
        /** Every toilet the app draws: the table's (the map, the search, a place's facts) and the Charge, Pump and Needs screens'. */
        val TOILETS = listOf("maki-toilet", "ui-toilet", "need.toilet", "need.toilet.2", "need.toilet.3", "need.toilet.4", "need.toilet.5")

        /**
         * The people drawn in strokes, whose heads may touch their bodies ([aFiguresHeadIsSolid]): the walkers, the
         * wheelchair users, the families, children and babies, the profiles, the toilets, the yoga figure, the dog walker,
         * the cyclist, the ski school's and the swimmer. A figure drawn anew joins them.
         */
        val FIGURES = TOILETS + listOf(
            "mode.walk", "mode.walk.2", "mode.walk.3", "mode.walk.4", "mode.walk.5", "ui-walk", "fact.walker",
            "fact.wheelchair", "fact.wheelchair.2", "fact.wheelchair.3", "ui-wheelchair", "ui-accessibility", "fact.step-free",
            "park.family", "fact.kids", "fact.kids.2", "fact.baby", "fact.baby.2", "ui-baby", "fact.person", "ui-person",
            "fact.queue", "ui-user", "ui-users", "ui.add-person", "place.yoga", "wild.dogs-on-lead", "maki-racetrack-cycling",
            "ski.school", "weather.swim",
        )

        /**
         * Emblems drawn to mirror themselves that the sweep found lopsided and mended: the dentist's tooth, Maki's own since
         * every kind of place is on a filled symbol (10 November; the boards' place.dentist was mended alike), the antenna.
         */
        val SYMMETRIC = listOf("maki-dentist", "temaki-antenna")

        /** The raster the faults are looked for in: a frame of 480 pixels, so 20 to a unit of a 24 grid ([UNIT]). */
        const val FINE = 480
        const val UNIT = FINE / 24f

        /**
         * The smallest hole that reads as one, as a radius in units of a 24 frame: a unit across, 1.6 pixels in a pin on a
         * phone. A smaller one is a speck there and a dot at a place's header, so the drawing changes with its size.
         */
        const val PINHOLE = 0.5f

        /** A hole smaller than this, as a radius in units of a 24 frame, is a sliver where two strokes meet, which no size shows. */
        const val SLIVER = 0.1f

        /**
         * The narrowest gap between two parts, in units of a 24 frame: nearer, they run together at every size but the
         * largest, where a crack shows. The open sets' own gaps, 0.4 units and up, pass.
         */
        const val NEAR = 0.25f

        /** The thinnest ink that holds in a pin, in units of a 24 frame. */
        const val HAIRLINE = 0.48f

        /**
         * The largest hole in a figure's head that is a fault, as a radius in units of a 24 frame: a bigger one is an
         * outline's, a profile's head, and reads as a hole at every size. The heads mended had holes of 0.6 to 1.05.
         */
        const val HEAD_HOLE = 1.25f

        // What the tests found that is still to be mended or drawn anew, the rebuild's next batches (PROGRESS.md), each
        // list held by its test: a drawing mended leaves its list, and no other may join it. Aliases of one drawing are
        // listed each (pinhead-fish, market.fish and ui-fish are one fish). The park page's own (nature.*) wait on the
        // owner's answer about the page.

        /** Off their frame's middle, by their box and their weight alike ([everySymbolSitsOnItsFramesMiddle]). */
        val OFF_CENTRE_LEFT = setOf("nature.birds", "nature.brook", "nature.heron", "nature.spring", "nature.summit")

        /** With a hole too small to read as one ([noSymbolHasAPinhole]). */
        val PINHOLES_LEFT = setOf(
            "maki-dog-park", "temaki-hearing_aid", "temaki-plumber",
        )

        /** With parts that almost touch ([noPartsAlmostTouch]). */
        val NEAR_LEFT = setOf(
            "pinhead-flag_checkered", "pinhead-gavel_with_sound_block", "temaki-binoculars",
            "temaki-kitchen_sink", "temaki-military", "temaki-money_hand", "temaki-vending_ice",
            "weather.partly-cloudy.2",
        )

        /** With hairlines ([noSymbolHasAHairline]). */
        val HAIRLINES_LEFT = setOf("temaki-vending_newspaper")

        /** Figures whose heads are rings ([aFiguresHeadIsSolid]): the dog walker, on the park page, which waits. */
        val RING_HEADS_LEFT = setOf("wild.dogs-on-lead")
    }

    /** How far [e] sits off its frame's centre, where its box and its weight agree: the lesser of the two, on the axis it is most. */
    private fun off(e: Measure): Float {
        fun agree(a: Float, b: Float) = if (a * b > 0f) min(abs(a), abs(b)) else 0f
        return max(agree(e.dx, e.mx), agree(e.dy, e.my))
    }
}
