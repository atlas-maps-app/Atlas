package app.atlas.mapcorecheck.tiles

import app.atlas.mapcore.Native
import app.atlas.render.V2
import app.atlas.rendertiles.Format3Reader
import app.atlas.rendertiles.compile.KeepCodes
import app.atlas.rendertiles.model.Geom
import app.atlas.rendertiles.model.Grid
import app.atlas.rendertiles.model.Keep
import app.atlas.rendertiles.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * A seam's marks ([Keep.SEAM_PIECE], [Keep.SEAM_COURT]: the edge from the corner to the next is a seam of its footprint)
 * from the core's writer through both readers. The core builds a window (`MapCore.Window.build`) from a document made by
 * hand, the one `CP/tests/load/marks_test.cpp` holds for the core alone: a block round a courtyard, an outline its part
 * cuts in two, and an outline whose part leaves it an annex of 4 m². The port's reader then reads the tile: each of the
 * three outlines carries its two marks, of its kind, a seam there and back on the same two grid points; the corners the
 * port keeps at each of the ladder's levels from the codes ([KeepCodes.simplify], a mark kept as [Keep.ALL] is) are the
 * corners the core's reader keeps; and at every level the marks are the same seams on the same grid points. A ring
 * without a seam carries no mark.
 */
class SeamMarksTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            val path = System.getProperty("atlas.mapcore.host")
            assumeTrue("no host build (-Datlas.mapcore.host)", path != null)
            assertNull(Native.load(path))
        }

        /** The Westerkerk's window, which holds the document. */
        const val X = 8414
        const val Y = 5384
        const val BLOCK = 20L
        const val CUT = 30L
        const val ANNEX = 40L
    }

    /** A closed way's geometry: its corners, metres east and north of the document's origin, and its first again. */
    private fun geometry(vararg corners: Pair<Double, Double>): String {
        fun point(c: Pair<Double, Double>) =
            String.format(Locale.ROOT, "{\"lat\": %.7f, \"lon\": %.7f}", 52.3680 + c.second / 111_320.0, 4.8890 + c.first / 67_950.0)
        return (corners.toList() + corners[0]).joinToString(", ", "[", "]") { point(it) }
    }

    private fun way(id: Long, tags: String, vararg corners: Pair<Double, Double>) =
        "{\"type\": \"way\", \"id\": $id, \"tags\": {$tags}, \"geometry\": ${geometry(*corners)}}"

    /** marks_test.cpp's document: each outline has corners a few decimetres off its walls' lines, for the ladder to drop. */
    private fun document(): String {
        val block = "{\"type\": \"relation\", \"id\": $BLOCK, \"tags\": {\"type\": \"multipolygon\", \"building\": \"apartments\", \"height\": \"15\"},\n" +
            " \"members\": [{\"type\": \"way\", \"ref\": 21, \"role\": \"outer\", \"geometry\": " +
            geometry(0.0 to 0.0, 20.0 to 0.2, 40.0 to -0.6, 60.0 to 0.0, 60.0 to 60.0, 30.0 to 62.5, 0.0 to 60.0) + "},\n" +
            "             {\"type\": \"way\", \"ref\": 22, \"role\": \"inner\", \"geometry\": " +
            geometry(20.0 to 20.0, 40.0 to 20.0, 40.0 to 40.0, 20.0 to 40.0) + "}]}"
        val elements = listOf(
            block,
            way(CUT, "\"building\": \"yes\", \"height\": \"12\"", 100.0 to 0.0, 115.0 to 0.25, 190.0 to 0.0, 190.0 to 30.0, 175.0 to 30.7, 100.0 to 30.0),
            way(31, "\"building:part\": \"yes\", \"height\": \"20\"", 130.0 to -2.0, 160.0 to -2.0, 160.0 to 32.0, 130.0 to 32.0),
            way(ANNEX, "\"building\": \"yes\", \"height\": \"9\"", 200.0 to 0.0, 220.0 to 0.3, 240.0 to 0.0, 240.0 to 5.0, 247.0 to 5.0,
                247.0 to 7.0, 240.0 to 7.0, 240.0 to 30.0, 200.0 to 30.0),
            way(41, "\"building:part\": \"yes\", \"height\": \"14\"", 238.0 to 3.0, 245.0 to 3.0, 245.0 to 9.0, 238.0 to 9.0),
        )
        return "{\"version\": 0.6, \"osm3s\": {\"timestamp_osm_base\": \"2026-10-22T18:00:00Z\"}, \"elements\": [\n" + elements.joinToString(",\n") + "]}"
    }

    /** A seam as its mark says it: its first corner's grid point, its second's, its kind. */
    private data class Mark(val lat: Int, val lon: Int, val toLat: Int, val toLon: Int, val kind: Int)

    /** The marks of [g] over its corners [kept], in order: the corner after a marked one is its seam's other end. */
    private fun marks(g: Geom, kept: IntArray): List<Mark> {
        val codes = g.keep ?: return emptyList()
        return kept.indices.filter { codes[kept[it]] > Keep.ALL }.map { i ->
            val a = kept[i]
            val b = kept[(i + 1) % kept.size]
            Mark(g.lat[a], g.lon[a], g.lat[b], g.lon[b], codes[a].toInt())
        }
    }

    @Test
    fun aWindowsSeamMarksComeBackFromBothReadersAtEveryLevel() {
        val dir = Files.createTempDirectory("atlas-seam-marks").toFile()
        try {
            val json = File(dir, "window.json").apply { writeText(document()) }
            val pack = File(dir, "window.atlas")
            assertEquals("the core refused the document", "", Native.buildWindow(14, X, Y, json.path, pack.path))
            val r = Format3Reader(pack)
            val h = r.head
            val tile = r.tile(14, X, Y) ?: error("the window's pack holds no tile 14/$X/$Y")
            val kinds = mapOf(BLOCK to Keep.SEAM_COURT, CUT to Keep.SEAM_PIECE, ANNEX to Keep.SEAM_PIECE)
            var marked = 0
            var rebuilds = 0
            var cut = 0
            for (layer in listOf(Layer.BUILDING, Layer.PART)) {
                val features = tile.layers[layer] ?: error("the tile has no ${layer.label}")
                val geoms = features.map { it["ring"] as Geom }
                val metres = geoms.map { g -> List(g.size) { i -> V2(Grid.x(g.lon[i], h.lon0, h.kx), Grid.z(g.lat[i], h.lat0, h.kz)) } }
                val whole = geoms.map { g -> marks(g, IntArray(g.size) { it }) }
                // as written: an outline's two marks, of its kind, there and back on the same two grid points
                for ((i, f) in features.withIndex()) {
                    val id = (f["id"] as Number).toLong()
                    val kind = if (layer == Layer.BUILDING) kinds[id] else null
                    if (kind == null) {
                        assertEquals("${layer.label} $id carries no mark", emptyList<Mark>(), whole[i])
                        continue
                    }
                    assertNotNull("${layer.label} $id has keep codes", geoms[i].keep)
                    assertEquals("${layer.label} $id: its marks' kinds", listOf(kind, kind), whole[i].map { it.kind })
                    val (there, back) = whole[i]
                    assertEquals("${layer.label} $id: the seam back is the seam there", Mark(there.toLat, there.toLon, there.lat, there.lon, kind), back)
                    marked++
                }
                for (level in 1 until Keep.TOLERANCES.size) {
                    val core = TileHost.simplify(pack.path, 14, X, Y, layer.id, "ring", level)
                    assertNotNull("${layer.label} at level $level", core)
                    var at = 0
                    for ((i, g) in geoms.withIndex()) {
                        val n = core!![at++]
                        val kept = IntArray(n) { core[at + it] }
                        at += n
                        // the port keeps, from the codes alone, the corners the core keeps: a mark is kept as ALL is
                        val want = g.keep?.let { KeepCodes.simplify(metres[i], it, level) } ?: metres[i]
                        assertEquals("${layer.label} #$i at ${Keep.TOLERANCES[level]} m", want, kept.map { metres[i][it] })
                        // and the seams there are the whole ring's, on the same grid points
                        assertEquals("${layer.label} #$i at ${Keep.TOLERANCES[level]} m: its marks", whole[i], marks(g, kept))
                        rebuilds++
                        if (n < g.size) cut++
                    }
                    assertEquals("every ring read", core!!.size, at)
                }
            }
            assertEquals("the three outlines with seams", 3, marked)
            assertTrue("the ladder cut no ring ($cut of $rebuilds)", cut > 0)
            println("seam marks: $marked rings marked, $rebuilds rebuilds at the ladder's 13 tolerances, $cut of them cut, the marks the same at each")
        } finally {
            dir.deleteRecursively()
        }
    }
}
