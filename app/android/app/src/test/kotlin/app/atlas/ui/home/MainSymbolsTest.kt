package app.atlas.ui.home

import app.atlas.places.PathSink
import app.atlas.ui.kit.AppSymbols
import app.atlas.ui.kit.MainSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Main board's own symbols, as the kit holds them: every path parses and stays on its grid, as on the board. */
class MainSymbolsTest {
    /** The box of everything a path draws, in its viewBox units. */
    private fun bounds(id: String): FloatArray {
        val icon = MainSymbols.icon(id)!!
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (p in icon.paths) p.commands.replay(object : PathSink {
            fun at(x: Float, y: Float) {
                b[0] = minOf(b[0], x); b[1] = minOf(b[1], y); b[2] = maxOf(b[2], x); b[3] = maxOf(b[3], y)
            }
            override fun moveTo(x: Float, y: Float) = at(x, y)
            override fun lineTo(x: Float, y: Float) = at(x, y)
            override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = at(x, y)
            override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = at(x, y)
            override fun close() {}
        })
        return b
    }

    @Test
    fun everySymbolParsesInsideItsViewBox() {
        assertTrue(MainSymbols.ids.size >= 25)
        for (id in MainSymbols.ids) {
            val icon = MainSymbols.icon(id)!!
            val b = bounds(id)
            val vb = icon.viewBox
            assertTrue("$id: ${b.toList()}", b[0] >= vb.x - 0.01f && b[1] >= vb.y - 0.01f && b[2] <= vb.x + vb.width + 0.01f && b[3] <= vb.y + vb.height + 0.01f)
        }
    }

    @Test
    fun circlesAndRectsKeepTheBoardsGeometry() {
        // The search lens: a circle r 7 at 11, 11 and the handle to 20, 20.
        assertEquals(listOf(4f, 4f, 20f, 20f), bounds("main.search").map { Math.round(it * 100) / 100f })
        // The mic: a 6 × 11 capsule from 9, 3, and the stand down to 21.
        val mic = bounds("main.mic")
        assertEquals(5f, mic[0], 0.01f)
        assertEquals(3f, mic[1], 0.01f)
        assertEquals(19f, mic[2], 0.01f)
        assertEquals(21f, mic[3], 0.01f)
        // A near B: the dashed ring is seven arcs, as a browser dashes 2.2 on, 2 off round a 4.5 ring.
        val dashed = MainSymbols.icon("main.near")!!.paths[0].d
        assertEquals(7, dashed.count { it == 'M' })
    }

    @Test
    fun theAppsTabAndOfflineIdsDrawTheBoards() {
        for ((app, board) in listOf("ui-map" to "main.tab-map", "ui-corner-up-right" to "main.tab-go", "ui-calendar-days" to "main.tab-soon",
            "ui-book-open" to "main.tab-local", "ui-cloud-check" to "main.cloud-check", "ui-near" to "main.near")) {
            assertEquals(app, MainSymbols.icon(board)!!.paths, AppSymbols.icon(app)!!.paths)
        }
        assertTrue(AppSymbols.has("main.sun") && AppSymbols.icon("main.sun") != null)
    }
}
