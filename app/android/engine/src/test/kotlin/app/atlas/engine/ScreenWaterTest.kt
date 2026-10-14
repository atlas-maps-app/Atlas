package app.atlas.engine

import app.atlas.geo.TileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The boards' sky-lit water: coloured down the screen by the renderer, with wind streaks lying across it. */
class ScreenWaterTest {
    private val id = TileId(14, 8414, 5384)

    /** A lake over the whole tile. */
    private val lake = MiniMvt().layer("water") {
        polygon(intArrayOf(-64, -64, 4160, -64, 4160, 4160, -64, 4160), props = mapOf("class" to "lake"))
    }.build()

    private class Vertex(val x: Float, val y: Float, val colour: Int)

    private fun vertices(frame: Frame): List<Vertex> {
        val out = ArrayList<Vertex>()
        for (b in frame.batches) {
            val t = b as Batch.Triangles
            if (t.layer != 0) continue
            for (i in 0 until t.indexCount) {
                val v = t.indices[i].toInt() and 0xFFFF
                out += Vertex(t.positions[2 * v], t.positions[2 * v + 1], t.colours[v])
            }
        }
        return out
    }

    private fun camera(s: TileScene, pitch: Double) = Camera(s.latLon(s.size / 2, s.size / 2), 15.0, 0.0, pitch, 400, 800, 2f)

    @Test
    fun waterDarkensDownTheScreenAsTheBoardsDoes() {
        val s = SceneBuilder.build(lake, id, Look.DAY)
        val noStreaks = Theme.DAY.copy(glint = null)
        val plain = SceneBuilder.build(lake, id, Look(noStreaks))
        val camera = camera(plain, 0.0)
        val frame = Renderer().frame(camera, listOf(plain), 0L)
        val vs = vertices(frame).filter { it.y in 0f..800f }
        assertTrue(vs.size > 20)
        // No key reaches the canvas: every colour drawn is opaque.
        for (v in vs) assertEquals(0xFF, v.colour ushr 24)
        // Seen from straight above there is no haze: each vertex is exactly the ramp at its height, the veil mixed in.
        val top = Theme.DAY.waterTop!!
        val bottom = Theme.DAY.waterBottom!!
        for (v in vs) {
            val t = v.y / 800f
            val ramp = Colours.mix(Colours.mix(top, bottom, t), Theme.DAY.water, Theme.DAY.waterVeil)
            for (ch in intArrayOf(16, 8, 0)) {
                assertEquals("at y ${v.y}", ((ramp shr ch) and 0xFF).toDouble(), ((v.colour shr ch) and 0xFF).toDouble(), 2.0)
            }
        }
        // The streaks add their own soft shapes to the same lake.
        val streaked = Renderer().frame(camera(s, 0.0), listOf(s), 0L)
        assertTrue(s.glints.count > 0)
        assertTrue(vertices(streaked).size > vertices(frame).size)
        val lighter = vertices(streaked).count { v -> v.y in 0f..800f && Colours.luma(v.colour) > Colours.luma(rampAt(v.y / 800f)) + 8f }
        assertTrue("lit streak cores: $lighter", lighter > 0)
    }

    private fun rampAt(t: Float) = Colours.mix(Colours.mix(Theme.DAY.waterTop!!, Theme.DAY.waterBottom!!, t), Theme.DAY.water, Theme.DAY.waterVeil)

    @Test
    fun aPitchedViewIsLighterFarAway() {
        val s = SceneBuilder.build(lake, id, Look(Theme.DAY.copy(glint = null)))
        val vs = vertices(Renderer().frame(camera(s, 45.0), listOf(s), 0L)).filter { it.y in 0f..800f }
        val upper = vs.filter { it.y < 200f }.map { Colours.luma(it.colour) }.average()
        val lower = vs.filter { it.y > 600f }.map { Colours.luma(it.colour) }.average()
        assertTrue("upper $upper, lower $lower", upper > lower + 30)
    }

    @Test
    fun otherLooksKeepTheirOwnFlatWater() {
        val s = SceneBuilder.build(lake, id, Look(Theme.GRAY))
        val vs = vertices(Renderer().frame(camera(s, 0.0), listOf(s), 0L))
        assertTrue(vs.isNotEmpty())
        assertTrue(vs.count { it.colour == Theme.GRAY.water } > vs.size / 2)
    }
}
