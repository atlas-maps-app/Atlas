package app.atlas.places

import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Whether the families' colours tell apart at a glance, as a pin is seen on the map, alone. Colour says a place's family
 * and nothing finer; its kind is told by its symbol (README decision 12), so what colour has to say it must say to
 * every eye. Distances are in OKLab, where the eye sees a step of about 0.02 side by side; colour blindness is simulated
 * (Machado, Oliveira and Fernandes 2009, at full strength) for its two commonest kinds, in about 6 and 2 of 100 men.
 * Every pair tells apart since the owner chose five colours moved a step or two from the Main board's (5 November): a
 * pair that comes closer fails. A pair kept close on purpose would go in [KNOWN], which fails too once it is apart.
 *
 * Draws into the test task's folder families.png: every family's pin as each eye sees it, by day and by night, and the
 * pairs too close. A palette to try before choosing it (`-Patlas.palette=<file>`, a line `family #RRGGBB` for each colour
 * it changes) is drawn the same way as families-trial.png, its verdict printed.
 */
class FamilyColoursTest {
    private val out = File(System.getProperty("atlas.out") ?: System.getProperty("java.io.tmpdir"))

    @Test
    fun theFamiliesTellApart() {
        val gaps = gaps(families())
        println(report(gaps))
        val close = gaps.filter { it.close }.mapTo(HashSet()) { it.key }
        val new = close - KNOWN
        val apart = KNOWN - close
        assertTrue("too close to tell apart at a glance: $new\n${report(gaps)}", new.isEmpty())
        assertTrue("apart now, take them off the known pairs: $apart", apart.isEmpty())
    }

    @Test
    fun sheet() {
        val today = families()
        draw(today, "Families", "the colours of categories.json", File(out, "families.png"))
        val sheet = File(out, "families-trial.png").apply { delete() }
        val file = System.getProperty("atlas.palette")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val ids = today.mapTo(HashSet()) { it.first }
        val changes = file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.associate { line ->
            val (id, hex) = line.split(Regex("\\s+"))
            assertTrue("${file.name}: $id is not a family (${ids.sorted()})", id in ids)
            id to (hex.removePrefix("#").toLong(16).toInt() or OPAQUE)
        }
        val trial = today.map { (id, colour) -> id to (changes[id] ?: colour) }
        val moved = today.filter { (id, colour) -> changes[id]?.let { it != colour } == true }
            .joinToString { (id, colour) -> "$id ${hex(colour)} → ${hex(changes.getValue(id))}" }
        val gaps = gaps(trial)
        val close = gaps.filter { it.close }
        draw(trial, "Families, trial", "${file.name}: $moved", sheet)
        println("trial palette ${file.name}: $moved\n" + report(gaps) +
            if (close.isEmpty()) "every pair tells apart for every eye" else "too close: ${close.map { it.key }}")
    }

    /**
     * A kind's own colour, its thing's (KindColours: the owner, 6 November, "Real colors look good"), must never mislead
     * about the family, which colour says everywhere else: for every eye it is not taken for another family's mark, open
     * or closed, nor for a closed place of its own family, and its own closed mark tells from its open one, which in turn
     * is not taken for another family's open mark. Within a family kinds may come close (coffee and chocolate are both
     * brown): their symbols tell them. Every kind the table names is a kind of a family, and its places wear it. Printed,
     * the other family each kind comes nearest. A table tried with `-Patlas.kindColours=<file>` is judged the same.
     */
    @Test
    fun theKindsTellApart() {
        val table = KindColours.all()
        if (System.getProperty("atlas.kindColours") == null) assertTrue("the app ships the colours of foods and drinks", table.isNotEmpty())
        val families = families()
        val faults = ArrayList<String>()
        for ((kind, colour) in table) {
            val preset = Presets.byId(kind) ?: error("$kind: no such kind")
            val family = Categories.of(Kinds.sample(preset))?.family ?: error("$kind: no kind of any family")
            if (KindColours.of(Kinds.sample(preset)) != colour) faults += "$kind: its places do not wear it"
            val closed = "$kind closed"
            val others = families.filter { it.first != family }
            val marks = listOf(kind to colour, closed to Palette.closed(colour)) + others + families.map { (f, c) -> "$f closed" to Palette.closed(c) }
            val other = others.mapTo(HashSet()) { it.first }
            // the kind beside every mark; its closed mark beside the other families' open ones (closed marks may come close)
            val gaps = gaps(marks).filter { it.a == kind || it.a == closed && it.b in other }
            val near = gaps.filter { it.a == kind && it.b in other }.minBy { it.distance / LEAST.getValue(it.eye) }
            println("$kind ${hex(colour)} ($family): nearest other family ${near.b}, ${near.eye} ${format(near.distance)}")
            faults += gaps.filter { it.close }.map { it.key }
        }
        assertTrue("kinds whose colour reads as another family's, or as closed: $faults", faults.isEmpty())
    }

    /**
     * A closed place's mark is its family's colour lighter ([Palette.closed]: the owner, 6 November, "Lighter for closed
     * is a good idea"). For every eye it must tell from its family's open mark, else closed would not show, and never come
     * as close to another family's open mark as two families may, else a closed place would be taken for an open one of
     * that family. Two closed marks of different families may come close: closed, they step back, their symbols telling
     * them; printed, the closest. Night's marks keep their colour (MarkerPainter.night), so the families' gaps hold there.
     */
    @Test
    fun aClosedPlaceStaysInItsFamily() {
        val open = families()
        val faults = ArrayList<String>()
        for ((family, colour) in open) {
            val closed = "$family closed"
            val gaps = gaps(listOf(closed to Palette.closed(colour)) + open).filter { it.a == closed || it.b == closed }
            val own = gaps.filter { it.a == family || it.b == family }.minBy { it.distance / LEAST.getValue(it.eye) }
            val other = gaps.filter { it.a != family && it.b != family }.minBy { it.distance / LEAST.getValue(it.eye) }
            println(
                "$family ${hex(colour)} closed ${hex(Palette.closed(colour))}: from its open ${own.eye} ${format(own.distance)}, " +
                    "nearest other ${other.a.takeUnless { it == closed } ?: other.b} ${other.eye} ${format(other.distance)}",
            )
            if (own.close) faults += "${own.key}: closed does not show"
            if (other.close) faults += "${other.key}: reads as the other family"
        }
        val both = gaps(open.map { (family, colour) -> family to Palette.closed(colour) }).sortedBy { it.distance / LEAST.getValue(it.eye) }.take(3)
        println("closest two closed: " + both.joinToString { "${it.eye} ${it.a}/${it.b} ${format(it.distance)}" })
        assertTrue("closed marks not lighter enough, or taken for another family: $faults", faults.isEmpty())
    }

    /** The families' colours as categories.json has them: the row's groups and the shelves, then the grey of the rest. */
    private fun families(): List<Pair<String, Int>> =
        PlaceFamily.entries.map { it.id to it.colour } + (CategoryTree.OSM to Categories.shelf(CategoryTree.OSM)!!.colour)

    /** Two families as an eye sees them, [distance] apart. */
    private class Gap(val eye: String, val a: String, val b: String, val distance: Double) {
        val close get() = distance < LEAST.getValue(eye)
        val key get() = "$eye $a/$b"
    }

    /** Every pair for every eye, the closest first within each eye. */
    private fun gaps(families: List<Pair<String, Int>>): List<Gap> = EYES.flatMap { (eye, matrix) ->
        val lab = families.associate { (id, colour) -> id to oklab(simulate(linear(colour), matrix)) }
        families.indices.flatMap { i -> (i + 1 until families.size).map { j -> families[i].first to families[j].first } }
            .map { (a, b) -> Gap(eye, a, b, distance(lab.getValue(a), lab.getValue(b))) }
            .sortedBy { it.distance }
    }

    private fun report(gaps: List<Gap>) = EYES.joinToString("") { (eye, _) ->
        "$eye, closest: " + gaps.filter { it.eye == eye }.take(5).joinToString { "${it.a}/${it.b} " + format(it.distance) } + "\n"
    }

    /** The sheet: a row of pins for each eye on the boards' paper, one by night on their ink, then the pairs too close. */
    private fun draw(families: List<Pair<String, Int>>, title: String, note: String, file: File) {
        val gaps = gaps(families)
        val close = gaps.filter { it.close }
        val perLine = (LABEL + families.size * CELL) / ITEM_W
        val lines = if (close.isEmpty()) 1 else (close.size + perLine - 1) / perLine
        val width = 2 * PAD + LABEL + families.size * CELL
        val img = BufferedImage(width, TOP + ROWS.size * ROW + 64 + lines * ITEM_H + PAD, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(PAPER)
        g.fillRect(0, 0, img.width, img.height)
        g.color = Color(INK)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 30)
        g.drawString(title, PAD, 52)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 17)
        g.color = Color(GREY)
        val tally = EYES.map { (eye, _) -> eye to close.count { it.eye == eye } }.filter { it.second > 0 }
        g.drawString("${families.size} colours · " + (if (close.isEmpty()) "every pair tells apart for every eye" else
            "${close.size} pairs too close: " + tally.joinToString { "${it.first} ${it.second}" }), PAD, 80)
        g.drawString(note, PAD, 104)
        for ((r, row) in ROWS.withIndex()) {
            val (eye, matrix) = EYES.first { it.first == row.eye }
            val y = TOP + r * ROW
            if (row.night) {
                g.color = Color(INK)
                g.fill(RoundRectangle2D.Float(PAD + LABEL - 14f, y - 10f, families.size * CELL + 14f, ROW - 22f, 24f, 24f))
            }
            g.color = Color(INK)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 17)
            g.drawString(row.label, PAD, y + 34)
            g.color = Color(GREY)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
            g.drawString(row.note, PAD, y + 54)
            gaps.firstOrNull { it.eye == eye }?.takeIf { !row.night }?.let { gap ->
                g.color = if (gap.close) Color(WARN) else Color(GREY)
                g.drawString("closest ${gap.a}/${gap.b} ${format(gap.distance)}", PAD, y + 72)
            }
            for ((i, f) in families.withIndex()) {
                val cx = PAD + LABEL + i * CELL + CELL / 2f
                pin(g, f.first, seen(f.second, matrix), cx, y + PIN / 2f + 6f, PIN)
                if (r == 0) {
                    g.color = Color(INK)
                    g.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
                    val last = label(g, nameOf(f.first), cx, y + PIN.toInt() + 30)
                    g.color = Color(GREY)
                    g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
                    label(g, hex(f.second), cx, last + 15)
                }
            }
        }
        val top = TOP + ROWS.size * ROW + 20
        g.color = Color(INK)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 17)
        g.drawString(if (close.isEmpty()) "No pair too close" else "Too close to tell apart alone", PAD, top + 6)
        val colour = families.toMap()
        for ((i, gap) in close.withIndex()) {
            val x = PAD + (i % perLine) * ITEM_W
            val y = top + 24 + (i / perLine) * ITEM_H
            val matrix = EYES.first { it.first == gap.eye }.second
            pin(g, gap.a, seen(colour.getValue(gap.a), matrix), x + 22f, y + 22f, 40f)
            pin(g, gap.b, seen(colour.getValue(gap.b), matrix), x + 70f, y + 22f, 40f)
            g.color = Color(INK)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
            g.drawString("${nameOf(gap.a)} / ${nameOf(gap.b)}", x + 102, y + 18)
            g.color = Color(WARN)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
            g.drawString("${gap.eye} · ${format(gap.distance)}, needs ${format(LEAST.getValue(gap.eye))}", x + 102, y + 36)
        }
        g.dispose()
        ImageIO.write(img, "png", file)
    }

    /** A map pin at 2×: a disc of [colour] in a white ring, the family's symbol in white. */
    private fun pin(g: Graphics2D, family: String, colour: Int, cx: Float, cy: Float, side: Float) {
        val ring = side / 15f
        g.color = Color.WHITE
        g.fill(Ellipse2D.Float(cx - side / 2 - ring, cy - side / 2 - ring, side + 2 * ring, side + 2 * ring))
        g.color = Color(colour)
        g.fill(Ellipse2D.Float(cx - side / 2, cy - side / 2, side, side))
        val glyph = side * 0.47f
        symbolOf(family)?.let(Symbols::icon)?.let { Java2DIcons.draw(g, it, cx - glyph / 2, cy - glyph / 2, glyph, Color.WHITE) }
    }

    /** [text] centred on [cx], on two lines where one is too wide for a cell; the last line's baseline. */
    private fun label(g: Graphics2D, text: String, cx: Float, baseline: Int): Int {
        val fm = g.fontMetrics
        val lines = if (fm.stringWidth(text) <= CELL - 8 || ' ' !in text) listOf(text) else {
            val cut = text.indices.filter { text[it] == ' ' }.minBy { kotlin.math.abs(it - text.length / 2) }
            listOf(text.substring(0, cut), text.substring(cut + 1))
        }
        lines.forEachIndexed { i, line -> g.drawString(line, cx - fm.stringWidth(line) / 2f, baseline + i * 15f) }
        return baseline + (lines.size - 1) * 15
    }

    private fun category(id: String) = Categories.byId(id)?.takeIf { it.isGroup } ?: Categories.shelf(id)
    private fun nameOf(id: String) = category(id)?.name("en") ?: id
    private fun symbolOf(id: String) = category(id)?.symbol

    /** [argb] as the [matrix]'s eye sees it, opaque. */
    private fun seen(argb: Int, matrix: Array<DoubleArray>?) = simulate(linear(argb), matrix).fold(0xFF) { acc, c ->
        val v = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
        (acc shl 8) or (v * 255).roundToInt().coerceIn(0, 255)
    }

    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)
    private fun format(d: Double) = String.format(Locale.ROOT, "%.3f", d)

    /** sRGB to linear light, channel by channel. */
    private fun linear(argb: Int) = DoubleArray(3) { i ->
        val c = ((argb shr (16 - 8 * i)) and 0xFF) / 255.0
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** Linear light as an eye of the [matrix]'s kind sees it; null for normal sight. */
    private fun simulate(rgb: DoubleArray, matrix: Array<DoubleArray>?) =
        if (matrix == null) rgb else DoubleArray(3) { i -> (matrix[i][0] * rgb[0] + matrix[i][1] * rgb[1] + matrix[i][2] * rgb[2]).coerceIn(0.0, 1.0) }

    /** Linear light in OKLab (Björn Ottosson, 2020). */
    private fun oklab(c: DoubleArray): DoubleArray {
        val l = cbrt(0.4122214708 * c[0] + 0.5363325363 * c[1] + 0.0514459929 * c[2])
        val m = cbrt(0.2119034982 * c[0] + 0.6806995451 * c[1] + 0.1073969566 * c[2])
        val s = cbrt(0.0883024619 * c[0] + 0.2817188376 * c[1] + 0.6299787005 * c[2])
        return doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    private fun distance(a: DoubleArray, b: DoubleArray) = sqrt((0..2).sumOf { (a[it] - b[it]).pow(2) })

    /** A row of the sheet: the pins as [eye] sees them, by night on the boards' ink. */
    private class Row(val eye: String, val label: String, val note: String, val night: Boolean = false)

    private companion object {
        val EYES: List<Pair<String, Array<DoubleArray>?>> = listOf(
            "normal" to null,
            "deutan" to arrayOf(
                doubleArrayOf(0.367322, 0.860646, -0.227968), doubleArrayOf(0.280085, 0.672501, 0.047413), doubleArrayOf(-0.011820, 0.042940, 0.968881),
            ),
            "protan" to arrayOf(
                doubleArrayOf(0.152286, 1.052583, -0.204868), doubleArrayOf(0.114503, 0.786281, 0.099216), doubleArrayOf(-0.003882, -0.048116, 1.051998),
            ),
        )

        /**
         * The least distance between two families' colours: three of the eye's side-by-side steps for normal sight, for
         * a pin seen alone; for colour-blind sight half of that, not one colour, as the symbols say the rest.
         */
        val LEAST = mapOf("normal" to 0.06, "deutan" to 0.03, "protan" to 0.03)

        /**
         * The pairs too close and kept so, none. The Main board's had six (its three greens; for colour-blind eyes fun's
         * violet and transport's blue, nature's green and health's red, culture's raspberry and the grey of the rest),
         * until the owner chose charge #059255, nature #2F6239, fun #8047E8, health #D44546 and the grey #505050.
         */
        val KNOWN = emptySet<String>()

        val ROWS = listOf(
            Row("normal", "Normal sight", "most eyes"),
            Row("deutan", "Deutan", "about 6 in 100 men"),
            Row("protan", "Protan", "about 2 in 100 men"),
            Row("normal", "By night", "on the night map", night = true),
        )

        const val PAPER = 0xF4F1EA
        const val INK = 0x14161B
        const val GREY = 0x5A5F6C
        const val WARN = 0xC0392B
        const val OPAQUE = 0xFF shl 24
        const val PAD = 24
        const val LABEL = 190
        const val CELL = 104
        const val TOP = 136
        const val ROW = 128
        const val PIN = 56f
        const val ITEM_W = 340
        const val ITEM_H = 56
    }
}
