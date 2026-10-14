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
    fun banksAreLightWhereTheLookHasThemAndStreaksLieOnOpenWater() {
        // The calm grey look keeps a light band along the banks, on the water, never on land.
        val gray = Look(Theme.GRAY)
        val edge = Theme.GRAY.waterEdge!!
        val banded = SceneBuilder.build(canal, child(15), gray, source = source).ground
        assertTrue("bank band at z15", banded.colours.count { it == edge } >= 4)
        for (v in 0 until banded.vertexCount) if (banded.colours[v] == edge) assertEquals(GroundBuilder.WATER_Z, banded.z(v), 0.05f)
        // The boards' daylight has none: its quays meet the water. Its water is coloured by the screen (keyed), and its
        // wind streaks are seeds on the water, from zoom 13 in.
        assertEquals(null, Theme.DAY.waterEdge)
        val day = SceneBuilder.build(canal, child(16), Look.DAY, source = source)
        assertEquals(0, day.ground.colours.count { it == edge })
        assertTrue("keyed water", day.ground.colours.count { it == ScreenWater.key(Theme.DAY.water) } >= 3)
        assertTrue("streaks at z16", day.glints.count >= 8)
        for (i in 0 until day.glints.count) assertEquals(GroundBuilder.WATER_Z, day.glints.z, 0.05f)
        val far = SceneBuilder.build(canal, TileId(12, source.x / 4, source.y / 4), Look.DAY, source = TileId(12, source.x / 4, source.y / 4))
        assertEquals("no streaks below zoom 13", 0, far.glints.count)
        // Not on a chart: its water shades by depth instead.
        val chart = SceneBuilder.build(canal, child(16), Look.CHART, source = source)
        assertEquals(0, chart.glints.count)
        assertEquals(0, chart.ground.colours.count { (it ushr 24) == ScreenWater.KEY_ALPHA })
    }

    @Test
    fun streaksKeepClearOfTheBanks() {
        val s = SceneBuilder.build(canal, child(17), Look.DAY, source = source)
        val g = s.glints
        val k = TileFrame(s.id, source).scale(4096)
        val top = (1500 - TileFrame(s.id, source).transform.top * 4096) * k
        val bottom = (1560 - TileFrame(s.id, source).transform.top * 4096) * k
        assertTrue(g.count > 0)
        for (i in 0 until g.count) {
            // A streak reaches no nearer than a metre to either bank, however it turns on the screen.
            assertTrue("a streak at y ${g.y[i]} reaching ${g.reach[i]} outside $top..$bottom", g.y[i] - g.reach[i] >= top + 0.99 && g.y[i] + g.reach[i] <= bottom - 0.99)
        }
    }

    @Test
    fun streaksKeepClearOfBridges() {
        // A street on a bridge across the canal: no streak may reach onto its deck.
        val vt = MiniMvt()
            .layer("water") { polygon(rect(-64, 1500, 4160, 1560), props = mapOf("class" to "river")) }
            .layer("transportation") { line(intArrayOf(2000, 1400, 2000, 1660), props = mapOf("class" to "minor", "brunnel" to "bridge")) }
            .build()
        val s = SceneBuilder.build(vt, child(17), Look.DAY, source = source)
        val f = TileFrame(s.id, source)
        val k = f.scale(4096)
        val bridgeX = (2000 - f.transform.left * 4096) * k
        assertTrue(s.glints.count > 0)
        for (i in 0 until s.glints.count) assertTrue(kotlin.math.abs(s.glints.x[i] - bridgeX) - s.glints.reach[i] >= 2.75 + 0.99)
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
        // The light band (in a look that has one) lies along the bank only: within a few metres of it.
        val gray = SceneBuilder.build(vt, scene, Look(Theme.GRAY), source = source).ground
        val edge = Theme.GRAY.waterEdge!!
        var band = 0
        for (v in 0 until gray.vertexCount) if (gray.colours[v] == edge) { band++; assertEquals(bank, gray.y(v), 0.01f) }
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
