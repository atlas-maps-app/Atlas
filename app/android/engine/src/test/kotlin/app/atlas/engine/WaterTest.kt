package app.atlas.engine

import app.atlas.geo.TileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The water's look and names on hand-made tiles: bank bands, glints, lakes named when they are big enough, parks. */
class WaterTest {
    private val source = TileId(14, 8414, 5384)

    /** A rectangle in tile units, clockwise on screen. */
    private fun rect(x0: Int, y0: Int, x1: Int, y1: Int) = intArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)

    /** A canal 60 units (22 m) wide across the source tile, and a pond. */
    private val canal = MiniMvt().layer("water") {
        polygon(rect(-64, 1500, 4160, 1560), props = mapOf("class" to "river"))
        polygon(rect(2500, 2500, 2530, 2530), props = mapOf("class" to "lake"))
    }.build()

    private fun child(z: Int): TileId {
        val k = 1 shl (z - 14)
        return TileId(z, source.x * k + k / 3, source.y * k + (1530 * k / 4096))
    }

    @Test
    fun banksAreLightAndGlintsComeCloseUp() {
        val z15 = SceneBuilder.build(canal, child(15), Look.DAY, source = source).ground
        val z16 = SceneBuilder.build(canal, child(16), Look.DAY, source = source).ground
        val edge = Theme.DAY.waterEdge!!
        val glint = Theme.DAY.glint!!
        assertTrue("bank band at z15", z15.colours.count { it == edge } >= 4)
        assertEquals("no glints at z15", 0, z15.colours.count { it == glint })
        assertTrue("glints at z16", z16.colours.count { it == glint } >= 8)
        // The band lies on the water, in the canal, never on land.
        for (v in 0 until z16.vertexCount) {
            if (z16.colours[v] != edge && z16.colours[v] != glint) continue
            assertEquals(GroundBuilder.WATER_Z, z16.z(v), 0.05f)
        }
        // Not on a chart: its water shades by depth instead.
        val chart = SceneBuilder.build(canal, child(16), Look.CHART, source = source).ground
        assertEquals(0, chart.colours.count { it == edge || it == glint })
    }

    @Test
    fun glintsKeepClearOfTheBanks() {
        val s = SceneBuilder.build(canal, child(17), Look.DAY, source = source)
        val g = s.ground
        val k = TileFrame(s.id, source).scale(4096)
        val top = (1500 - TileFrame(s.id, source).transform.top * 4096) * k
        val bottom = (1560 - TileFrame(s.id, source).transform.top * 4096) * k
        var n = 0
        for (v in 0 until g.vertexCount) {
            if (g.colours[v] != Theme.DAY.glint) continue
            n++
            assertTrue("a glint at y ${g.y(v)} outside the canal $top..$bottom", g.y(v) > top + 1.0 && g.y(v) < bottom - 1.0)
        }
        assertTrue(n > 0)
    }

    @Test
    fun aTileCutWithoutBufferHasBanksOnlyWhereTheWaterEnds() {
        // Some sources cut polygons exactly at the tile's edge, with no buffer: those edges are seams between tiles, not
        // banks. A lake over the north of the tile, its real bank at y = 1500, read by the zoom 15 scene in the corner.
        val vt = MiniMvt().layer("water") { polygon(rect(0, 0, 4096, 1500), props = mapOf("class" to "lake")) }.build()
        val scene = TileId(15, source.x * 2, source.y * 2)
        val s = SceneBuilder.build(vt, scene, Look.DAY, source = source)
        val bank = 1500 * TileFrame(scene, source).scale(4096).toFloat()
        val g = s.ground
        val quays = (0 until g.faceCount).filter { g.faceKind[it] == FaceMesh.TWO_SIDED }
        assertTrue(quays.isNotEmpty())
        for (f in quays) assertEquals("a quay at y ${g.faceCentre[3 * f + 1]}", bank, g.faceCentre[3 * f + 1], 0.01f)
        // The light band lies along the bank only: within a few metres of it.
        val edge = Theme.DAY.waterEdge!!
        var band = 0
        for (v in 0 until g.vertexCount) if (g.colours[v] == edge) { band++; assertEquals(bank, g.y(v), 0.01f) }
        assertTrue(band > 0)
    }

    @Test
    fun aPondIsNamedOnlyCloseUpALakeFromAfar() {
        val vt = MiniMvt()
            .layer("water") {
                polygon(rect(1000, 1000, 1030, 1030), props = mapOf("class" to "lake"))
                polygon(rect(2000, 2000, 3600, 3600), props = mapOf("class" to "lake"))
            }
            .layer("water_name") {
                point(1015, 1015, props = mapOf("class" to "lake", "name" to "Vijver"))
                point(2800, 2800, props = mapOf("class" to "lake", "name" to "Grote Meer"))
            }
            .build()
        val labels = SceneBuilder.build(vt, source, Look.DAY).labels.associateBy { it.text }
        val pond = labels["Vijver"]!!
        val lake = labels["Grote Meer"]!!
        // An 11 m pond needs its name to fit: zoom 18 or more. A 580 m lake shows from about zoom 13.7.
        assertTrue("pond from ${pond.minZoom}", pond.minZoom >= 18f)
        assertTrue("lake from ${lake.minZoom}", lake.minZoom in 12.5f..14f)
    }

    @Test
    fun lakeNamesFollowTheirLinesFromAfar() {
        val vt = MiniMvt().layer("water_name") {
            line(intArrayOf(500, 2000, 2000, 1800, 3500, 2100), props = mapOf("class" to "lake", "name" to "Markermeer"))
        }.build()
        val s = SceneBuilder.build(vt, TileId(10, 525, 336), Look.DAY)
        val l = s.labels.single { it.text == "Markermeer" }
        assertEquals(LabelKind.WATER, l.kind)
        assertTrue(l.line != null && l.line!!.size >= 6)
        assertTrue(l.minZoom <= 10f)
    }

    @Test
    fun onlyNaturesProtectedAreasTurnGreenAndOnlyFromAfar() {
        val vt = MiniMvt().layer("park") {
            polygon(rect(200, 200, 1800, 1800), props = mapOf("class" to "national_park", "name" to "Duinen"))
            polygon(rect(2200, 2200, 3800, 3800), props = mapOf("class" to "protected_area", "name" to "Grachtengordel"))
        }.build()
        val far = SceneBuilder.build(vt, TileId(12, 2103, 1346), Look.DAY).ground
        val park = Theme.DAY.park
        assertTrue(far.colours.count { it == park } >= 4)
        assertTrue("outlined", far.colours.count { it == Theme.DAY.parkEdge } >= 8)
        // The protected townscape is not tinted: every park-coloured vertex lies in the national park.
        val k = TileFrame(TileId(12, 2103, 1346), TileId(12, 2103, 1346)).scale(4096)
        for (v in 0 until far.vertexCount) if (far.colours[v] == park) assertTrue(far.x(v) <= 1800 * k + 0.01f)
        // Close up, the land cover speaks for the park.
        val near = SceneBuilder.build(vt, source, Look.DAY).ground
        assertEquals(0, near.colours.count { it == park || it == Theme.DAY.parkEdge })
    }
}
