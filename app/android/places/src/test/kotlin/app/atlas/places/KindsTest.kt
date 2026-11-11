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
import javax.imageio.ImageIO

/**
 * Keeps the symbols database ([Kinds], kinds.tsv) whole and true: a line for every kind Atlas lists, every line a kind
 * whose symbol draws. Writes into the test task's folder the file as it should be (kept lines as they are, a new kind
 * with its preset's icon) and a sheet per family of every line in its family's colour, to choose what to refine next:
 * marked on it are the kinds that share a drawing (×n) and those whose symbols read alike at a pin's size (≈n,
 * [LookAlikes]), with the pairs that do listed under them. Every such pair is in kinds-alike.tsv too, the closest first.
 */
class KindsTest {
    private val out = File(System.getProperty("atlas.out") ?: System.getProperty("java.io.tmpdir"))
    private val raw = Resources.text(Kinds.FILE).orEmpty()

    @Test
    fun everyKindAtlasListsHasALine() {
        val whole = File(out, Kinds.FILE).apply { writeText(whole()) }
        val missing = Kinds.listed().filter { Kinds.line(it.id) == null }.map { it.id }
        assertTrue("${missing.size} kinds have no line in ${Kinds.FILE}; the whole file is $whole: ${missing.take(30)}", missing.isEmpty())
    }

    @Test
    fun everyLineIsAListedKindThatDraws() {
        val listed = Kinds.listed().mapTo(HashSet()) { it.id }
        val seen = HashSet<String>()
        val problems = ArrayList<String>()
        for ((i, line) in raw.lines().withIndex()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val c = line.split('\t')
            val at = "line ${i + 1}, ${c[0]}"
            if (!seen.add(c[0])) problems += "$at: twice"
            if (c[0] !in listed) problems += "$at: not a kind Atlas lists"
            val symbol = c.getOrNull(1).orEmpty()
            if (!Symbols.has(symbol)) problems += "$at: no set has $symbol"
            val word = c.getOrNull(2).orEmpty()
            if (Kinds.State.entries.none { it.word == word }) problems += "$at: '$word' is not ok, redo or drawn"
            if (word == Kinds.State.DRAWN.word && !Symbols.isBoard(symbol)) problems += "$at: drawn, but $symbol is not the boards'"
            if (c.size > 4) problems += "$at: ${c.size} columns"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun aKindDrawsItsLine() {
        val line = Kinds.lines().first()
        val preset = Presets.byId(line.kind)!!
        assertTrue(Kinds.symbolOf(preset) == line.symbol)
    }

    @Test
    fun sheets() {
        val byFamily = Kinds.lines().groupBy { line -> Presets.byId(line.kind)?.let(::familyOf) }
        val alike = lookAlikes(byFamily)
        // every pair that reads alike, once, the closest first: kind, its family, the other, its family, the score
        File(out, "kinds-alike.tsv").writeText(alike.entries
            .flatMap { (kind, others) -> others.filter { kind < it.line.kind }.map { kind to it } }
            .sortedByDescending { it.second.score }
            .joinToString("") { (kind, o) ->
                "%s\t%s\t%s\t%s\t%.2f\n".format(kind, Presets.byId(kind)?.let(::familyOf)?.id, o.line.kind, o.family.id, o.score)
            })
        val shared = ArrayList<String>()
        for ((family, lines) in byFamily) {
            if (family == null) continue
            // each pair of drawings that read alike once, this family's first: the kinds of each that do
            val pairs = lines.flatMap { line -> alike[line.kind].orEmpty().map { line to it } }
                .groupBy { (line, other) -> Triple(drawing(line), drawing(other.line), other.family) }
                .filterKeys { (ours, theirs, f) -> f != family || ours < theirs }
                .values.sortedByDescending { it[0].second.score }
            val rows = (lines.size + COLS - 1) / COLS
            val listed = if (pairs.isEmpty()) 0 else PAIRS_TOP + (pairs.size + PAIR_COLS - 1) / PAIR_COLS * PAIR_H
            val img = BufferedImage(COLS * CELL_W + 2 * PAD, TOP + rows * CELL_H + listed + PAD, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = PAPER
            g.fillRect(0, 0, img.width, img.height)
            g.color = INK
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 30)
            g.drawString(nameOf(family), PAD, 52)
            val tally = Kinds.State.entries.mapNotNull { s -> lines.count { it.state == s }.takeIf { it > 0 }?.let { "$it ${s.word.ifEmpty { "not looked at" }}" } }
            // a kind is told from the others of its family by its symbol: a drawing two kinds share is marked on both,
            // and so is a symbol that reads like another's at a pin's size, there or in a family whose places stand beside
            val uses = lines.groupingBy(::drawing).eachCount()
            val sharing = lines.count { uses.getValue(drawing(it)) > 1 }
            val alikes = lines.count { it.kind in alike }
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 17)
            g.color = Color(0x5A5F6C)
            g.drawString("${lines.size} kinds · ${uses.size} symbols · $sharing share one · $alikes look alike · " + tally.joinToString(" · "), PAD, 80)
            shared += "${nameOf(family)}: $sharing of ${lines.size} share a symbol, $alikes look alike; " +
                (uses.entries.filter { it.value > 1 }.sortedByDescending { it.value }.take(4).map { "${it.key} ×${it.value}" } +
                    pairs.take(4).map { it[0].let { (a, b) -> "${a.kind} ≈ ${b.line.kind} %.2f".format(b.score) } }).joinToString()
            for ((i, line) in lines.withIndex()) {
                val x = PAD + (i % COLS) * CELL_W
                val y = TOP + (i / COLS) * CELL_H
                val tx = x + (CELL_W - TILE) / 2f
                g.color = Color(family.colour)
                g.fill(RoundRectangle2D.Float(tx, y.toFloat(), TILE, TILE, 20f, 20f))
                Symbols.icon(line.symbol)?.let { Java2DIcons.draw(g, it, tx + 13f, y + 13f, TILE - 26f, Color.WHITE) }
                uses.getValue(drawing(line)).takeIf { it > 1 }?.let { n -> badge(g, "×$n", tx - 5f, y + TILE - 11f, INK) }
                val looksLike = alike[line.kind].orEmpty().distinctBy { drawing(it.line) to it.family }
                if (looksLike.isNotEmpty()) badge(g, "≈${looksLike.size}", tx + TILE + 5f, y + TILE - 11f, ALIKE_INK, right = true)
                STATE_DOT[line.state]?.let { c ->
                    g.color = PAPER
                    g.fill(Ellipse2D.Float(tx + TILE - 13f, y - 5f, 18f, 18f))
                    g.color = c
                    g.fill(Ellipse2D.Float(tx + TILE - 10f, y - 2f, 12f, 12f))
                }
                g.color = INK
                g.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
                centred(g, Presets.byId(line.kind)!!.name("en"), x, y + TILE.toInt() + 20)
                g.color = Color(0x8A8478)
                g.font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
                centred(g, line.kind, x, y + TILE.toInt() + 36)
                if (looksLike.isNotEmpty()) {
                    g.color = ALIKE_INK
                    centred(g, "≈ " + Presets.byId(looksLike[0].line.kind)!!.name("en") + if (looksLike.size > 1) " +${looksLike.size - 1}" else "", x, y + TILE.toInt() + 50)
                }
            }
            if (pairs.isNotEmpty()) {
                val top = TOP + rows * CELL_H
                g.color = INK
                g.font = Font(Font.SANS_SERIF, Font.BOLD, 20)
                g.drawString("≈ Read alike at a pin's size: ${pairs.size} ${if (pairs.size == 1) "pair" else "pairs"} of symbols, from a score of ${LookAlikes.ALIKE}", PAD, top + 34)
                for ((i, pair) in pairs.withIndex()) {
                    val ours = pair.map { it.first }.distinct()
                    val theirs = pair.map { it.second }.distinctBy { it.line.kind }
                    val other = theirs[0]
                    val x = PAD + (i % PAIR_COLS) * PAIR_W
                    val y = top + PAIRS_TOP + (i / PAIR_COLS) * PAIR_H
                    rowTile(g, ours[0].symbol, family, x, y)
                    rowTile(g, other.line.symbol, other.family, x + 46, y)
                    pin(g, ours[0].symbol, family, x + 96, y + 5)
                    pin(g, other.line.symbol, other.family, x + 130, y + 5)
                    g.color = INK
                    g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
                    fitted(g, ours.joinToString { Presets.byId(it.kind)!!.name("en") }, x + 172, y + 13)
                    fitted(g, "≈ " + theirs.joinToString { Presets.byId(it.line.kind)!!.name("en") }, x + 172, y + 28)
                    g.color = ALIKE_INK
                    g.font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
                    fitted(g, "%.2f".format(other.score) + if (other.family != family) " · ${nameOf(other.family)}" else "", x + 172, y + 42)
                }
            }
            g.dispose()
            ImageIO.write(img, "png", File(out, "kinds-${family.id}.png"))
        }
        println("symbols database: ${Kinds.lines().size} kinds, sheets in $out/kinds-*.png\n" + shared.joinToString("\n"))
        assertTrue(byFamily.keys.filterNotNull().isNotEmpty())
    }

    /** A kind whose symbol reads like another's at a pin's size: [line]'s, of [family], by [score] ([LookAlikes]). */
    private class Alike(val line: Kinds.Line, val family: PlaceFamily, val score: Float)

    /**
     * The kinds whose symbols read alike at a pin's size ([LookAlikes]), each with the others it reads like, closest
     * first: two of one family, or of two families whose places stand side by side ([NEIGHBOURS]). A drawing two kinds
     * of one family use is a share (×n), not a look-alike; across two families it is one.
     */
    private fun lookAlikes(byFamily: Map<PlaceFamily?, List<Kinds.Line>>): Map<String, List<Alike>> {
        val looks = HashMap<String, LookAlikes.Look?>()
        fun look(line: Kinds.Line) = looks.getOrPut(drawing(line)) { Symbols.icon(line.symbol)?.let(LookAlikes::look) }
        val scores = HashMap<String, Float>()
        val out = HashMap<String, MutableList<Alike>>()
        val families = byFamily.keys.filterNotNull()
        for (f in families) for (h in families) {
            if (f.ordinal > h.ordinal || f != h && setOf(f, h) !in NEIGHBOURS) continue
            val ours = byFamily.getValue(f)
            val theirs = byFamily.getValue(h)
            for ((i, a) in ours.withIndex()) for ((j, b) in theirs.withIndex()) {
                val da = drawing(a)
                val db = drawing(b)
                if (f == h && (j <= i || da == db)) continue
                val score = scores.getOrPut(if (da < db) "$da|$db" else "$db|$da") {
                    val x = look(a)
                    val y = look(b)
                    if (x == null || y == null) 0f else if (da == db) 1f else LookAlikes.score(x, y)
                }
                if (score < LookAlikes.ALIKE) continue
                out.getOrPut(a.kind) { ArrayList() } += Alike(b, h, score)
                out.getOrPut(b.kind) { ArrayList() } += Alike(a, f, score)
            }
        }
        return out.mapValues { (_, others) -> others.sortedByDescending { it.score } }
    }

    /** The drawing [line]'s symbol is: the boards' id for one they draw (two ids may stand for one drawing), else the symbol. */
    private fun drawing(line: Kinds.Line): String = Symbols.boardId(line.symbol) ?: line.symbol

    /** [label] in a [colour] pill ringed in the paper, its left edge at [x] (its right, when [right]) and its top at [y]. */
    private fun badge(g: Graphics2D, label: String, x: Float, y: Float, colour: Color, right: Boolean = false) {
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 11)
        val w = g.fontMetrics.stringWidth(label) + 10f
        val left = if (right) x - w else x
        g.color = PAPER
        g.fill(RoundRectangle2D.Float(left - 2f, y - 2f, w + 4f, 20f, 20f, 20f))
        g.color = colour
        g.fill(RoundRectangle2D.Float(left, y, w, 16f, 16f, 16f))
        g.color = PAPER
        g.drawString(label, left + 5f, y + 12f)
    }

    /** [symbol] as a result row shows it: white, 22 px on a 40 px tile of [family]'s colour. */
    private fun rowTile(g: Graphics2D, symbol: String, family: PlaceFamily, x: Int, y: Int) {
        g.color = Color(family.colour)
        g.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), 40f, 40f, 24f, 24f))
        Symbols.icon(symbol)?.let { Java2DIcons.draw(g, it, x + 9f, y + 9f, 22f, Color.WHITE) }
    }

    /** [symbol] as a map pin shows it: white, 14 px on a 26 px disc of [family]'s colour in a 2 px white ring. */
    private fun pin(g: Graphics2D, symbol: String, family: PlaceFamily, x: Int, y: Int) {
        g.color = Color.WHITE
        g.fill(Ellipse2D.Float(x.toFloat(), y.toFloat(), 30f, 30f))
        g.color = Color(family.colour)
        g.fill(Ellipse2D.Float(x + 2f, y + 2f, 26f, 26f))
        Symbols.icon(symbol)?.let { Java2DIcons.draw(g, it, x + 8f, y + 8f, 14f, Color.WHITE) }
    }

    /** [text] centred in the cell at [x], cut to the cell's width. */
    private fun centred(g: Graphics2D, text: String, x: Int, baseline: Int) {
        val fm = g.fontMetrics
        var t = text
        while (t.length > 1 && fm.stringWidth(t) > CELL_W - 10) t = t.dropLast(2) + "…"
        g.drawString(t, x + (CELL_W - fm.stringWidth(t)) / 2, baseline)
    }

    /** [text] from [x], cut to a listed pair's width. */
    private fun fitted(g: Graphics2D, text: String, x: Int, baseline: Int) {
        val fm = g.fontMetrics
        var t = text
        while (t.length > 1 && fm.stringWidth(t) > PAIR_W - 180) t = t.dropLast(2) + "…"
        g.drawString(t, x, baseline)
    }

    /** The file with a line for every kind listed: by family in the row's order, then category, then id; kept lines as they are. */
    private fun whole(): String {
        val sb = StringBuilder(HEADER)
        val categories = Categories.all().withIndex().associate { (i, c) -> c.id to i }
        val byFamily = Kinds.listed().groupBy(::familyOf)
        for (family in PlaceFamily.entries) {
            val kinds = byFamily[family].orEmpty().sortedWith(compareBy({ categories[Categories.of(Kinds.sample(it))?.id] ?: 0 }, { it.id }))
            if (kinds.isEmpty()) continue
            sb.append("\n# ").append(nameOf(family)).append('\n')
            for (p in kinds) {
                val kept = Kinds.line(p.id)
                val symbol = kept?.symbol ?: Presets.iconOf(p)?.takeIf { Symbols.has(it) } ?: Categories.of(Kinds.sample(p))?.symbol ?: Symbols.MARKER
                val state = kept?.state ?: if (Symbols.isBoard(symbol)) Kinds.State.DRAWN else Kinds.State.NEW
                sb.append(p.id).append('\t').append(symbol).append('\t').append(state.word)
                kept?.note?.takeIf { it.isNotEmpty() }?.let { sb.append('\t').append(it) }
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    private fun familyOf(p: Preset): PlaceFamily? = Categories.of(Kinds.sample(p))?.let(Symbols::familyOf)

    private fun nameOf(f: PlaceFamily): String = (Categories.byId(f.id)?.takeIf { it.isGroup } ?: Categories.shelf(f.id))?.name("en") ?: f.id

    private companion object {
        const val COLS = 12
        const val CELL_W = 150
        const val CELL_H = 128
        const val TILE = 64f
        const val PAD = 24
        const val TOP = 108
        const val PAIR_COLS = 3
        const val PAIR_W = COLS * CELL_W / PAIR_COLS
        const val PAIR_H = 56
        const val PAIRS_TOP = 56
        val PAPER = Color(0xF4F1EA)
        val INK = Color(0x14161B)
        val ALIKE_INK = Color(0xB4530F)
        val STATE_DOT = mapOf(Kinds.State.REDO to Color(0xC8322A), Kinds.State.OK to Color(0x178A4E), Kinds.State.DRAWN to INK)

        /**
         * Families whose places stand side by side on the map, so that a symbol of one reading like the other's misleads
         * as one within a family does: days out and water (the owner, 6 November: the water park's slide and the
         * playground's), nature and sport, shops and groceries; and since sport left days out (5 November), days out and
         * sport (minigolf by the golf course), water and sport (the pool by the sports centre's); and nature and water
         * (the bay by the beach).
         */
        val NEIGHBOURS = setOf(
            setOf(PlaceFamily.FUN, PlaceFamily.WATER),
            setOf(PlaceFamily.NATURE, PlaceFamily.SPORT),
            setOf(PlaceFamily.SHOP, PlaceFamily.GROCERY),
            setOf(PlaceFamily.FUN, PlaceFamily.SPORT),
            setOf(PlaceFamily.WATER, PlaceFamily.SPORT),
            setOf(PlaceFamily.NATURE, PlaceFamily.WATER),
        )

        val HEADER = """
            |# The symbols database: the symbol Atlas draws for each kind of place it lists, a line each, so each can be refined
            |# on its own (the owner, 5 November). Symbols.symbolFor and the search's category tree draw from it. A place wears its
            |# family's colour (its category's, categories.json); its kind is told by this symbol.
            |#
            |# kind    an id-tagging-schema preset: every kind Atlas lists has its line, under its family
            |# symbol  ours where the kind had one: the boards' drawing or the interface set's (ui-), as the app drew them (the
            |#         owner, 10 November: "The simple style of our icons looked good"); else a filled one, Temaki's first
            |#         (temaki-), then Maki's or Pinhead's (maki-, pinhead-), else Atlas's own (atlas-); places/tools builds the sets
            |# state   blank: not looked at yet · ok: looked at, and right · redo: wants a better one · drawn: the boards' own
            |# note    why, for whoever refines it next
            |#
            |# To refine one: change its symbol, set its state, say why. KindsTest keeps the file whole and true, writes it anew
            |# when a kind comes or goes (kept lines as they are) and draws every line in its family's colour, a sheet a family:
            |# places/build/tmp/test/kinds.tsv and kinds-<family>.png. A sheet marks ×n on a drawing n kinds of the family share
            |# and ≈n on a symbol that reads like n others at a pin's size, of the family or of one whose places stand beside it.
            |""".trimMargin()
    }
}
