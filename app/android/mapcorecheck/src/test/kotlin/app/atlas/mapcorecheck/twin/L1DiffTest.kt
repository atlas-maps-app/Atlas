package app.atlas.mapcorecheck.twin

import app.atlas.geo.Shapes
import app.atlas.mapcorecheck.twin.L1Diff.L1Element
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L1Diff's rules on elements made by hand (the view 0 0 412 916, as the renders' S1 frame): what lies outside the view
 * is neither compared nor missing, an element one side lacks shifts nothing, a fill drawn in pieces counts as one
 * element only when the pieces cover it, a path's subpaths compare as a set, and a match out of its place is out of
 * order only when it passes an element of another key that it overlaps.
 */
class L1DiffTest {
    private val view = floatArrayOf(0f, 0f, 412f, 916f)

    private fun key(colour: String, evenOdd: Boolean = false) =
        "path; fill $colour 1.000${if (evenOdd) " evenodd" else ""}; stroke none; o 1.000 b 0 f - c - t -; o 1.000 b 0 f - c vb t -"

    /** A fill of [rings] (each closed), its coordinates as given. */
    private fun fill(colour: String, vararg rings: DoubleArray): L1Element {
        val pts = ArrayList<Float>()
        val shape = ArrayList<String>()
        for (r in rings) {
            for (v in r) pts += v.toFloat()
            shape += "${pts.size / 2}:c"
        }
        return L1Element(key(colour), pts.toFloatArray(), shape.joinToString(" "), "path", fills = true)
    }

    private fun square(x: Double, y: Double, s: Double) = doubleArrayOf(x, y, x + s, y, x + s, y + s, x, y + s)

    @Test
    fun anElementTheTwinLacksShiftsNothing() {
        val port = List(10) { fill("#ff102030", square(20.0 + 30 * it, 100.0, 10.0)) }
        val twin = port.filterIndexed { i, _ -> i != 3 }
        val r = L1Diff.diff("lacks", port, twin, view)
        assertEquals(9, r.exact)
        assertEquals(0, r.differs)
        assertEquals(1, r.missing.values.sum())
        assertEquals(0, r.extra.values.sum())
    }

    @Test
    fun whatLiesOutsideTheViewIsNotMissing() {
        val inside = square(100.0, 100.0, 20.0)
        val beyond = square(600.0, 100.0, 20.0)
        val port = listOf(
            fill("#ff102030", square(500.0, 300.0, 20.0)), // wholly outside: the twin's cover need not reach it
            fill("#ff405060", inside, beyond), // one ring in the view, one beyond it
            fill("#ff708090", square(-40.0, -40.0, 40.2)), // its corner reaches into the view
        )
        val twin = listOf(fill("#ff405060", inside), fill("#ff708090", square(-40.0, -40.0, 40.2)))
        val r = L1Diff.diff("outside", port, twin, view)
        assertEquals(1, r.outside)
        assertEquals(2, r.exact)
        assertEquals(0, r.missing.values.sum())
        // The corner's 0.2 px in the view is within an outline's rounding: had the twin lacked it, it would not show.
        val lacking = L1Diff.diff("outside", port, twin.take(1), view)
        assertEquals(2, lacking.outside)
        assertEquals(0, lacking.missing.values.sum())
    }

    /** An irregular, concave ring around (200, 400). */
    private val pond = doubleArrayOf(120.0, 330.0, 260.0, 310.0, 300.0, 380.0, 240.0, 400.0, 290.0, 470.0, 150.0, 490.0, 170.0, 420.0, 110.0, 400.0)

    /** [pond] clipped at the tile edges x = 210 and y = 405, the four pieces in another order, their coordinates as floats print. */
    private fun pieces(): List<DoubleArray> {
        val boxes = listOf(doubleArrayOf(210.0, 405.0, 1e4, 1e4), doubleArrayOf(-1e4, -1e4, 210.0, 405.0), doubleArrayOf(210.0, -1e4, 1e4, 405.0),
            doubleArrayOf(-1e4, 405.0, 210.0, 1e4))
        return boxes.map { b -> Shapes.clipRing(pond, b[0], b[1], b[2], b[3]).map { it + 0.03 } .toDoubleArray() }
    }

    @Test
    fun aFillInPiecesIsOneElementWhenThePiecesCoverIt() {
        val port = listOf(fill("#ff0b1422", pond), fill("#ff445566", square(10.0, 10.0, 5.0)))
        val twin = pieces().map { fill("#ff0b1422", it) } + fill("#ff445566", square(10.0, 10.0, 5.0))
        val r = L1Diff.diff("pieces", port, twin, view)
        assertEquals(1, r.inPieces)
        assertEquals(4, r.pieces)
        assertEquals(2, r.matched)
        assertEquals(0, r.missing.values.sum() + r.extra.values.sum() + r.differs)
        assertEquals(0, r.outOfOrder)

        // A piece missing: the rest are no longer the port's element.
        val lacking = L1Diff.diff("a piece lacking", port, twin.drop(1), view)
        assertEquals(0, lacking.inPieces)
        assertEquals(1, lacking.missing.values.sum() + lacking.differs)

        // A piece a pixel off: neither.
        val shifted = pieces().toMutableList()
        shifted[2] = shifted[2].mapIndexed { i, v -> if (i % 2 == 0) v + 1.0 else v }.toDoubleArray()
        val off = L1Diff.diff("a piece shifted", port, shifted.map { fill("#ff0b1422", it) }, view)
        assertEquals(0, off.inPieces)
    }

    @Test
    fun aPathsSubpathsCompareAsASet() {
        val a = square(10.0, 10.0, 5.0)
        val b = square(50.0, 10.0, 5.0)
        val c = square(90.0, 10.0, 5.0)
        val r = L1Diff.diff("subpaths", listOf(fill("#ff000000", a, b, c)), listOf(fill("#ff000000", c, a, b)), view)
        assertEquals(1, r.exact)
        assertEquals(1, r.subpathsReordered)
        val step = L1Diff.diff("one step", listOf(fill("#ff000000", a)), listOf(fill("#ff000000", a.mapIndexed { i, v -> if (i == 0) v + 0.1 else v }.toDoubleArray())), view)
        assertEquals(1, step.oneStep)
        val far = L1Diff.diff("differs", listOf(fill("#ff000000", a)), listOf(fill("#ff000000", a.mapIndexed { i, v -> if (i == 0) v + 3 else v }.toDoubleArray())), view)
        assertEquals(1, far.differs)
    }

    @Test
    fun aMatchOutOfPlaceIsOutOfOrderOnlyOverAnOverlappingElement() {
        val water = fill("#ff0b1422", square(100.0, 100.0, 50.0))
        val apart = fill("#ff223344", square(300.0, 300.0, 50.0))
        val over = fill("#ff223344", square(120.0, 120.0, 50.0))
        val swapped = L1Diff.diff("apart", listOf(water, apart), listOf(apart, water), view)
        assertEquals(2, swapped.exact)
        assertEquals(1, swapped.reordered)
        assertEquals(0, swapped.outOfOrder)
        val crossing = L1Diff.diff("overlapping", listOf(water, over), listOf(over, water), view)
        assertEquals(1, crossing.outOfOrder)
        // Elements of one key are interchangeable.
        val same = fill("#ff0b1422", square(120.0, 120.0, 50.0))
        val shuffled = L1Diff.diff("one key", listOf(water, same), listOf(same, water), view)
        assertEquals(0, shuffled.outOfOrder)
        assertEquals(1, shuffled.reordered)
    }
}
