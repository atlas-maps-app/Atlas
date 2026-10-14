package app.atlas.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RoofTest {
    private val look = Look.DAY

    private fun mesh(b: Building, zoom: Int = 16): FaceMesh {
        val caster = ShadowCaster(listOf(b), look.light)
        val mb = MeshBuilder()
        BuildingMesher(look.theme, look.shading, caster, zoom).mesh(b, 0, mb)
        return mb.build()
    }

    @Test
    fun gabledRectangle() {
        // A canal house: 8 m front (x), 20 m deep (y), 15 m to the ridge with a 4 m roof; ridge along y.
        val b = Synthetic.box(0.0, 0.0, 8.0, 20.0, 15.0, RoofShape.GABLED, 4.0, axisAlongX = false)
        val m = mesh(b)
        var maxZ = 0f
        for (v in 0 until m.vertexCount) maxZ = maxOf(maxZ, m.z(v))
        assertEquals(15f, maxZ, 1e-4f)
        // Ridge vertices run down the middle of the front: x = 4.
        for (v in 0 until m.vertexCount) if (abs(m.z(v) - 15f) < 1e-4) assertEquals(4f, m.x(v), 1e-3f)
        val roofs = (0 until m.faceCount).filter { m.faceGroup[it] == FaceMesh.GROUP_ROOF }
        val walls = (0 until m.faceCount).filter { m.faceGroup[it] == FaceMesh.GROUP_WALL }
        // Two slopes, four walls and two gable ends.
        assertEquals(2, roofs.size)
        assertEquals(6, walls.size)
        for (f in roofs) {
            val nx = m.faceNormal[3 * f]; val ny = m.faceNormal[3 * f + 1]; val nz = m.faceNormal[3 * f + 2]
            assertTrue(nz > 0f)
            // Slopes lean across the ridge (toward ±x), not along it.
            assertEquals(0f, ny, 1e-4f)
            // Pitch: rise 4 over half-width 4 → 45°.
            assertEquals(nz.toDouble(), abs(nx).toDouble(), 1e-3)
        }
        // Gable triangles reach the ridge; side walls stop at the eaves.
        val gables = walls.filter { f -> (m.faceVertexStart[f] until m.faceVertexStart[f + 1]).any { m.z(it) > 11.5f } }
        assertEquals(2, gables.size)
        for (f in gables) assertEquals(0f, m.faceNormal[3 * f], 1e-4f)
    }

    @Test
    fun hippedRoofHasFourSlopes() {
        val b = Synthetic.box(0.0, 0.0, 10.0, 16.0, 14.0, RoofShape.HIPPED, 3.0, axisAlongX = false)
        val m = mesh(b)
        val roofs = (0 until m.faceCount).filter { m.faceGroup[it] == FaceMesh.GROUP_ROOF }
        assertEquals(4, roofs.size)
        // The ridge is shortened by the roof height at both ends: from y = 3 to 13.
        val ridge = (0 until m.vertexCount).filter { abs(m.z(it) - 14f) < 1e-4 }.map { m.y(it) }
        assertEquals(3f, ridge.minOrNull()!!, 1e-3f)
        assertEquals(13f, ridge.maxOrNull()!!, 1e-3f)
        for (f in roofs) assertTrue(m.faceNormal[3 * f + 2] > 0.5f)
    }

    @Test
    fun flatPyramidAndMansard() {
        val flat = mesh(Synthetic.box(0.0, 0.0, 30.0, 30.0, 12.0))
        val top = (0 until flat.faceCount).filter { flat.faceGroup[it] == FaceMesh.GROUP_ROOF }
        assertEquals(1, top.size)
        assertEquals(1f, flat.faceNormal[3 * top[0] + 2], 0f)

        val pyr = mesh(Synthetic.box(0.0, 0.0, 6.0, 6.0, 40.0, RoofShape.PYRAMIDAL, 12.0))
        val apex = (0 until pyr.vertexCount).filter { abs(pyr.z(it) - 40f) < 1e-4 }
        assertTrue(apex.isNotEmpty())
        for (v in apex) { assertEquals(3f, pyr.x(v), 1e-3f); assertEquals(3f, pyr.y(v), 1e-3f) }

        val mansard = mesh(Synthetic.box(0.0, 0.0, 20.0, 14.0, 21.0, RoofShape.MANSARD, 3.4))
        val roofFaces = (0 until mansard.faceCount).filter { mansard.faceGroup[it] == FaceMesh.GROUP_ROOF }
        // Four steep lower slopes and the flat top over the inset ring.
        assertEquals(5, roofFaces.size)
        val inset = (0 until mansard.vertexCount).filter { abs(mansard.z(it) - 21f) < 1e-4 }
        for (v in inset) assertTrue(mansard.x(v) > 1.0f && mansard.x(v) < 19.0f)
    }

    @Test
    fun detailsComeCloseUp() {
        val b = Synthetic.box(0.0, 0.0, 8.0, 20.0, 15.0, RoofShape.GABLED, 4.0, axisAlongX = false)
        val withChimney = Building(
            b.rings, null, b.centroidX, b.centroidY, b.area, b.height, 0.0, b.roof, b.roofHeight, b.axisX, b.axisY,
            b.wallColour, b.roofColour, "brick", "roof_tiles", "home", 1700, 4, cornice = true, hoist = true,
            chimney = ChimneyStyle(1.0, "plain", Colours.parse("#6E4A3E"), null, null), windows = null, seed = 9, featureId = 92, guessed = emptySet(), why = "",
        )
        val far = mesh(withChimney, 15)
        val near = mesh(withChimney, 18)
        assertTrue(near.vertexCount > far.vertexCount * 2)
        // Something stands on the roof close up (chimney, lit ridge), nothing from far.
        assertTrue((0 until near.faceCount).any { near.faceGroup[it] == FaceMesh.GROUP_ON_ROOF })
        assertTrue((0 until far.faceCount).none { far.faceGroup[it] == FaceMesh.GROUP_ON_ROOF })
        // The chimney rises above the ridge.
        var maxZ = 0f
        for (v in 0 until near.vertexCount) maxZ = maxOf(maxZ, near.z(v))
        assertEquals(15.9f, maxZ, 0.01f)
    }

    @Test
    fun nightLightsTheSameWindowsCloseUpAsFromAfar() {
        // A 30 × 12 m house, four storeys. Up to zoom 17 lit windows side by side merge into one strip of light; at
        // zoom 18 each is its own quad. Crossing into zoom 18 must not switch other windows on.
        val b = Synthetic.box(0.0, 0.0, 30.0, 12.0, 15.0, id = 7)
        val night = Look.NIGHT
        val lit = Theme.NIGHT.windowLit.toSet()
        fun centres(zoom: Int): Map<String, Set<Long>> {
            val mb = MeshBuilder()
            BuildingMesher(night.theme, night.shading, ShadowCaster(listOf(b), night.light), zoom).mesh(b, 0, mb)
            val m = mb.build()
            val out = HashMap<String, MutableSet<Long>>()
            var v = 0
            while (v + 3 < m.vertexCount) {
                if (m.colours[v] !in lit) { v++; continue }
                // A quad of light along one wall: its row and its span along the wall.
                val alongX = abs(m.y(v) - m.y(v + 1)) < 1e-3f
                val a = if (alongX) m.x(v) else m.y(v)
                val c = if (alongX) m.x(v + 1) else m.y(v + 1)
                val wall = if (alongX) "y${Math.round(m.y(v))}" else "x${Math.round(m.x(v))}"
                val spacing = if (alongX) 30.0 / 14 else 12.0 / 5
                val lo = minOf(a, c).toDouble(); val hi = maxOf(a, c).toDouble()
                // The windows it covers, by their centres (a window is 0.44 of a bay wide).
                val count = Math.round((hi - lo - 0.44 * spacing) / spacing).toInt() + 1
                val set = out.getOrPut("$wall z${Math.round(m.z(v) * 100)}") { HashSet() }
                for (k in 0 until count) set += Math.round((lo + 0.22 * spacing + k * spacing) * 100)
                v += 4
            }
            return out
        }
        val far = centres(17)
        val near = centres(18)
        assertTrue("windows lit: ${near.values.sumOf { it.size }}", near.values.sumOf { it.size } > 20)
        assertEquals(near, far)
    }

    @Test
    fun wallsFaceOutward() {
        val b = Synthetic.box(0.0, 0.0, 10.0, 10.0, 9.0)
        val m = mesh(b)
        for (f in 0 until m.faceCount) {
            if (m.faceGroup[f] != FaceMesh.GROUP_WALL) continue
            val nx = m.faceNormal[3 * f]; val ny = m.faceNormal[3 * f + 1]
            val cx = m.faceCentre[3 * f] - 5f; val cy = m.faceCentre[3 * f + 1] - 5f
            assertTrue("wall $f faces outward", nx * cx + ny * cy > 0f)
        }
    }

    @Test
    fun parisBlocksGetMansards() {
        val guesser = BuildingGuesser(Traditions.get("fr_paris")!!, Theme.DAY)
        // A 20 × 15 m block without a height: six storeys of limestone under a zinc mansard.
        val b = guesser.resolve(RawBuilding(listOf(doubleArrayOf(0.0, 0.0, 20.0, 0.0, 20.0, 15.0, 0.0, 15.0)), null, null, null, null, 42, null, 5))!!
        assertEquals(RoofShape.MANSARD, b.roof)
        assertEquals(3.4, b.roofHeight, 1e-9)
        assertTrue(b.wallMaterial in setOf("limestone", "plaster"))
        assertTrue(b.levels in 5..7)
        assertEquals(b.levels * 3.0 + 3.4, b.height, 1e-9)
        assertTrue(b.windows != null)
    }

    @Test
    fun osmIdsFromTileFeatureIds() {
        assertEquals("n2737943314", TileFrame.osmId(27379433141L).toString())
        assertEquals("w123", TileFrame.osmId(1232L).toString())
        assertEquals("r45", TileFrame.osmId(453L).toString())
        assertEquals(null, TileFrame.osmId(2763284010L))
        assertEquals(null, TileFrame.osmId(0L))
    }

    @Test
    fun guessesFollowTheTradition() {
        val guesser = BuildingGuesser(Traditions.get("nl_amsterdam")!!, Theme.DAY)
        // A narrow old-looking front with a mapped height of 14 m: gabled, roof 3–5 m, along the long side.
        val narrow = RawBuilding(listOf(doubleArrayOf(0.0, 0.0, 7.0, 0.0, 7.0, 18.0, 0.0, 18.0)), null, 14.0, 0.0, null, 12, null, 77)
        val b = guesser.resolve(narrow)!!
        assertEquals(RoofShape.GABLED, b.roof)
        assertTrue(b.roofHeight in 3.0..5.0)
        assertEquals(14.0, b.height, 1e-9)
        assertEquals(0.0, abs(b.axisX), 1e-9)
        assertTrue("start_date" in b.guessed)
        // A large block stays flat; a height the tiles made up (5 m) is replaced by the tradition's storeys.
        val block = RawBuilding(listOf(doubleArrayOf(0.0, 0.0, 60.0, 0.0, 60.0, 40.0, 0.0, 40.0)), null, 5.0, 0.0, null, 22, null, 78)
        val bb = guesser.resolve(block)!!
        assertEquals(RoofShape.FLAT, bb.roof)
        assertTrue("height" in bb.guessed)
        assertTrue(bb.height > 6.0)
        // A mapped colour wins.
        val painted = guesser.resolve(RawBuilding(narrow.rings, null, 14.0, 0.0, "#bd8161", 12, null, 77))!!
        assertEquals(Colours.parse("#BD8161"), painted.wallColour)
        // The same building always looks the same.
        assertEquals(b.wallColour, guesser.resolve(narrow)!!.wallColour)
        // A church is gabled in slate when it fits a roof.
        val church = guesser.resolve(RawBuilding(listOf(doubleArrayOf(0.0, 0.0, 14.0, 0.0, 14.0, 40.0, 0.0, 40.0)), null, 28.0, 0.0, null, 32, "worship", 79))!!
        assertEquals("worship", church.kind)
        assertEquals(RoofShape.GABLED, church.roof)
    }
}
