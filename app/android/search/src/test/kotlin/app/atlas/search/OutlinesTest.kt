package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Shapes
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The green areas' outlines a region's store keeps beside their places, for a park's page offline. */
class OutlinesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val westerkerk = LatLon(52.3746, 4.8838)
    private val mPerDeg = Geo.EARTH_RADIUS * PI / 180

    /** A closed ring of (lat, lon) pairs in 1e-7 degree: a box [wM] by [hM] metres about [c], [perSide] points a side. */
    private fun box(c: LatLon, wM: Double, hM: Double, perSide: Int = 1): IntArray {
        val dLat = hM / 2 / mPerDeg
        val dLon = wM / 2 / (mPerDeg * cos(Math.toRadians(c.lat)))
        val corners = listOf(c.lat - dLat to c.lon - dLon, c.lat - dLat to c.lon + dLon, c.lat + dLat to c.lon + dLon, c.lat + dLat to c.lon - dLon)
        val out = ArrayList<Int>()
        for (side in 0 until 4) {
            val (a, b) = corners[side] to corners[(side + 1) % 4]
            for (k in 0 until perSide) {
                val t = k.toDouble() / perSide
                out += E7.of(a.first + (b.first - a.first) * t)
                out += E7.of(a.second + (b.second - a.second) * t)
            }
        }
        out += out[0]
        out += out[1]
        return out.toIntArray()
    }

    /** A wobbly round pond's ring: [n] points [rM] metres (give or take [wobbleM]) about [c], closed. */
    private fun round(c: LatLon, rM: Double, wobbleM: Double, n: Int): IntArray {
        val out = IntArray(2 * n + 2)
        for (i in 0 until n) {
            val a = 2 * PI * i / n
            val r = rM + wobbleM * sin(37 * a)
            out[2 * i] = E7.of(c.lat + r * sin(a) / mPerDeg)
            out[2 * i + 1] = E7.of(c.lon + r * cos(a) / (mPerDeg * cos(Math.toRadians(c.lat))))
        }
        out[2 * n] = out[0]
        out[2 * n + 1] = out[1]
        return out
    }

    /** Metres from (px, py) to the segment from (ax, ay) to (bx, by). */
    private fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val t = if (dx == 0.0 && dy == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun latLons(r: IntArray) = List(r.size / 2) { LatLon(E7.deg(r[2 * it]), E7.deg(r[2 * it + 1])) }

    private fun outline(rings: List<IntArray>, inner: List<Boolean>): PlaceOutline? = Outlines.encode(rings, inner)?.let(Outlines::decode)

    @Test
    fun anOutlineComesBackAsItWentIn() {
        val park = box(westerkerk, 200.0, 200.0)
        val pond = box(westerkerk, 50.0, 50.0)
        val o = outline(listOf(park, pond), listOf(false, true))!!
        assertEquals(listOf(false, true), o.inner)
        assertEquals(latLons(park).toString(), o.rings[0].toString())
        assertEquals(latLons(pond).toString(), o.rings[1].toString())
        // 200 by 200 m less the pond's 50 by 50
        assertEquals(37_500.0, o.areaM2, 37_500.0 * 0.01)
        // each point a few bytes: the first one whole, the rest as steps from the one before
        assertTrue("${Outlines.encode(listOf(park, pond), listOf(false, true))!!.size} bytes", Outlines.encode(listOf(park, pond), listOf(false, true))!!.size < 60)
    }

    @Test
    fun aStraightEdgeKeepsItsCornersOnly() {
        val o = outline(listOf(box(westerkerk, 300.0, 120.0, perSide = 50)), listOf(false))!!
        assertEquals(5, o.rings.single().size)
        assertEquals(latLons(box(westerkerk, 300.0, 120.0)).toString(), o.rings.single().toString())
    }

    @Test
    fun aSimplifiedRingStaysWithinAThousandthOfItsSize() {
        // a pond 2 km across with a shore that wobbles 3 m: a thousandth of its box's diagonal is 2.8 m
        val pond = round(westerkerk, 1_000.0, 3.0, 2_000)
        val o = outline(listOf(pond), listOf(false))!!
        val kept = o.rings.single()
        assertEquals(kept.first(), kept.last())
        assertTrue("${kept.size} points kept", kept.size < 400)
        val tolerance = hypot(2_000.0, 2_000.0) / 1000
        val kx = mPerDeg * cos(Math.toRadians(westerkerk.lat))
        fun x(p: LatLon) = (p.lon - westerkerk.lon) * kx
        fun y(p: LatLon) = (p.lat - westerkerk.lat) * mPerDeg
        var worst = 0.0
        for (p in latLons(pond)) {
            var nearest = Double.MAX_VALUE
            for (i in 0 until kept.size - 1) {
                nearest = min(nearest, segmentDistance(x(p), y(p), x(kept[i]), y(kept[i]), x(kept[i + 1]), y(kept[i + 1])))
            }
            worst = max(worst, nearest)
        }
        assertTrue("$worst m off", worst <= tolerance + 0.05)
        val raw = Shapes.geodesicArea(latLons(pond))
        assertEquals(raw, o.areaM2, raw * 0.002)
    }

    @Test
    fun aSpeckIsLeftOut() {
        // a hole of a metre in a park of 2 km, and an island of a metre off it: dots on its plan
        val park = box(westerkerk, 2_000.0, 2_000.0)
        val speck = box(westerkerk, 1.0, 1.0)
        val island = box(LatLon(westerkerk.lat + 0.02, westerkerk.lon), 1.0, 1.0)
        val o = outline(listOf(park, speck, island), listOf(false, true, false))!!
        assertEquals(listOf(false), o.inner)
        assertEquals(latLons(park).toString(), o.rings.single().toString())
        // a pocket park of 20 m is no speck: its ring is all there is
        assertNotNull(outline(listOf(box(westerkerk, 20.0, 20.0)), listOf(false)))
    }

    @Test
    fun nothingToDrawIsNoOutline() {
        val park = box(westerkerk, 200.0, 200.0)
        // a ring that does not close, holes alone, too few points, nothing
        assertNull(Outlines.encode(listOf(park.copyOf(park.size - 2)), listOf(false)))
        assertNull(Outlines.encode(listOf(park), listOf(true)))
        assertNull(Outlines.encode(listOf(intArrayOf(park[0], park[1], park[2], park[3], park[0], park[1])), listOf(false)))
        assertNull(Outlines.encode(emptyList(), emptyList()))
        // a format of a later Atlas is not read
        val blob = Outlines.encode(listOf(park), listOf(false))!!
        assertNull(Outlines.decode(blob.copyOf().also { it[0] = 2 }))
    }

    /** A path of [n] points from [from] to [to], straight, in 1e-7 degree pairs. */
    private fun path(from: LatLon, to: LatLon, n: Int = 2): IntArray {
        val out = IntArray(2 * n)
        for (k in 0 until n) {
            val t = k.toDouble() / (n - 1)
            out[2 * k] = E7.of(from.lat + (to.lat - from.lat) * t)
            out[2 * k + 1] = E7.of(from.lon + (to.lon - from.lon) * t)
        }
        return out
    }

    @Test
    fun aParksPathsComeBackSimplified() {
        // a straight path of 40 points keeps its ends; a bend of 30 m stays; a path's last point is kept
        val straight = path(LatLon(52.3740, 4.8830), LatLon(52.3750, 4.8850), 40)
        val bent = intArrayOf(E7.of(52.3740), E7.of(4.8830), E7.of(52.3745), E7.of(4.8845), E7.of(52.3740), E7.of(4.8860))
        val lines = Outlines.decodeLines(Outlines.encodeLines(listOf(straight, bent), 1.0))!!
        assertEquals(2, lines.size)
        assertEquals(listOf(latLons(straight).first(), latLons(straight).last()).toString(), lines[0].toString())
        assertEquals(latLons(bent).toString(), lines[1].toString())
        // a park with no paths keeps that it has none; a format of a later Atlas is not read
        assertEquals(emptyList<List<LatLon>>(), Outlines.decodeLines(Outlines.encodeLines(emptyList(), 1.0)))
        assertNull(Outlines.decodeLines(Outlines.encodeLines(listOf(straight), 1.0).copyOf().also { it[0] = 2 }))
    }

    @Test
    fun aParksPathsAreKeptUpToThreeKilometresAcross() {
        // a town park's plan draws its paths; a landscape's, some 4 km across, none
        assertTrue(Outlines.keepsPathsOf(listOf(box(westerkerk, 1_600.0, 700.0))))
        assertTrue(Outlines.keepsPathsOf(listOf(box(westerkerk, 2_000.0, 2_000.0))))
        assertFalse(Outlines.keepsPathsOf(listOf(box(westerkerk, 3_000.0, 3_000.0))))
        // they stray as far as the outline may: a thousandth of its size, 1 to 50 m
        assertEquals(1.0, Outlines.toleranceOf(listOf(box(westerkerk, 200.0, 200.0))), 1e-9)
        assertEquals(hypot(20_000.0, 20_000.0) / 1000, Outlines.toleranceOf(listOf(box(westerkerk, 20_000.0, 20_000.0))), 0.05)
        assertEquals(50.0, Outlines.toleranceOf(listOf(box(westerkerk, 100_000.0, 100_000.0))), 1e-9)
    }

    @Test
    fun aStoreKeepsAParksPathsBesideItsOutline() {
        val store = PlaceStore.inMemory()
        val id = vondelpark.id!!
        val pocket = OsmId(OsmType.WAY, 1_423_270_125)
        val landscape = OsmId(OsmType.RELATION, 2_000)
        val walk = path(LatLon(52.3586, 4.8640), LatLon(52.3586, 4.8770), 3)
        val stats = store.writer().use { w ->
            w.putOutline(id, listOf(vondelRing()), listOf(false))
            w.putPaths(id, listOf(walk, path(LatLon(52.3570, 4.8700), LatLon(52.3600, 4.8700))), Outlines.toleranceOf(listOf(vondelRing())))
            w.putOutline(pocket, listOf(box(westerkerk, 60.0, 90.0)), listOf(false))
            w.putPaths(pocket, emptyList(), 1.0)
            w.putOutline(landscape, listOf(box(westerkerk, 6_000.0, 4_000.0)), listOf(false))
            assertEquals(2, w.paths)
            w.finish()
        }
        assertEquals(2, stats.paths)
        val paths = store.outline(id)!!.paths!!
        assertEquals(2, paths.size)
        // the straight path keeps its ends
        assertEquals(listOf(latLons(walk).first(), latLons(walk).last()).toString(), paths[0].toString())
        // a pocket park with no paths says so; a landscape's are not kept
        assertEquals(emptyList<List<LatLon>>(), store.outline(pocket)!!.paths)
        assertNull(store.outline(landscape)!!.paths)
        assertEquals(2, Regions(listOf(PlaceStore.inMemory(), store)).outline(id)!!.paths!!.size)
        store.clear()
        assertNull(store.outline(id))
        store.writer().use { w ->
            w.putOutline(id, listOf(vondelRing()), listOf(false))
            w.finish()
        }
        assertNull(store.outline(id)!!.paths)
    }

    @Test
    fun theStoreKeepsWhatAParksPageDraws() {
        assertTrue(Outlines.keeps(mapOf("leisure" to "park")))
        assertTrue(Outlines.keeps(mapOf("leisure" to "nature_reserve", "name" to "Twiske")))
        assertTrue(Outlines.keeps(mapOf("boundary" to "national_park", "name" to "Zuid-Kennemerland")))
        assertTrue(Outlines.keeps(mapOf("boundary" to "protected_area", "protect_class" to "4")))
        assertTrue(Outlines.keeps(mapOf("boundary" to "protected_area", "protect_class" to "97", "name" to "Natura 2000")))
        assertTrue(Outlines.keeps(mapOf("boundary" to "protected_area")))
        // Amsterdam's canal ring is protected for its heritage, not for nature; a park in it is still a park
        val canalRing = mapOf("boundary" to "protected_area", "protect_class" to "22", "heritage" to "1", "historic" to "heritage", "name" to "Grachtengordel")
        assertFalse(Outlines.keeps(canalRing))
        assertTrue(Outlines.keeps(canalRing + ("leisure" to "park")))
        assertFalse(Outlines.keeps(mapOf("boundary" to "protected_area", "heritage" to "2")))
        assertFalse(Outlines.keeps(mapOf("boundary" to "protected_area", "protect_class" to "12")))
        assertFalse(Outlines.keeps(mapOf("leisure" to "garden", "name" to "Hortus")))
        assertFalse(Outlines.keeps(mapOf("landuse" to "forest")))
        assertFalse(Outlines.keeps(mapOf("boundary" to "administrative", "admin_level" to "8")))
    }

    private val vondelpark = Place(
        OsmId(OsmType.RELATION, 2_554_787), LatLon(52.3580, 4.8686), mapOf("leisure" to "park", "name" to "Vondelpark"),
        BBox(4.8584, 52.3553, 4.8826, 52.3620),
    )

    private fun vondelRing() = box(LatLon(52.3586, 4.8705), 1_600.0, 700.0)

    @Test
    fun aStoreKeepsOutlinesBesideItsPlaces() {
        val store = PlaceStore.inMemory()
        val id = vondelpark.id!!
        val stats = store.writer().use { w ->
            w.add(vondelpark)
            assertTrue(w.putOutline(id, listOf(vondelRing()), listOf(false)))
            // nothing to draw is not kept
            assertFalse(w.putOutline(OsmId(OsmType.WAY, 7), listOf(box(westerkerk, 1.0, 1.0)), listOf(true)))
            assertEquals(1, w.outlines)
            w.finish()
        }
        assertEquals(1, stats.outlines)
        val o = store.outline(id)!!
        assertEquals(latLons(vondelRing()).toString(), o.rings.single().toString())
        assertEquals(1_600.0 * 700.0, o.areaM2, 1_600.0 * 700.0 * 0.01)
        assertNull(store.outline(OsmId(OsmType.WAY, 2_554_787)))
        assertNull(store.outline(OsmId(OsmType.NODE, 2_554_787)))
        assertNull(store.outline(OsmId(OsmType.WAY, -1)))
        // every region's store is asked; places held in memory keep none
        assertNotNull(Regions(listOf(PlaceStore.inMemory(), store)).outline(id))
        assertNull(Regions(listOf(PlaceStore.inMemory())).outline(id))
        assertNull(MemoryPlaces(listOf(vondelpark)).outline(id))

        // the place again (a national park kept from Overpass, say) keeps its outline; an outline again replaces it
        store.writer().use { w ->
            w.add(vondelpark.copy(tags = vondelpark.tags + ("wikidata" to "Q649215")))
            w.finish()
        }
        assertNotNull(store.outline(id))
        store.writer().use { w ->
            w.putOutline(id, listOf(box(westerkerk, 100.0, 100.0)), listOf(false))
            w.finish()
        }
        assertEquals(10_000.0, store.outline(id)!!.areaM2, 100.0)
        store.clear()
        assertNull(store.outline(id))
    }

    @Test
    fun anOutlineRolledBackIsGone() {
        val store = PlaceStore.inMemory()
        val id = vondelpark.id!!
        store.writer().use { w ->
            w.putOutline(id, listOf(vondelRing()), listOf(false))
            w.rollback()
            assertEquals(0, w.outlines)
            assertNull(store.outline(id))
        }
    }

    @Test
    fun aStoreFromBeforeOutlinesOpensWithNone() {
        val f = tmp.newFile("before.places.db").also { it.delete() }
        val id = vondelpark.id!!
        PlaceStore.open(f).use { s ->
            s.importPlaces(listOf(vondelpark))
            s.locked { s.exec("DROP TABLE outline") }
        }
        PlaceStore.open(f).use { s ->
            // opened, it has the table, empty: the park is there without its outline, and one can be kept
            assertNotNull(s.get(id))
            assertNull(s.outline(id))
            s.writer().use { w ->
                w.putOutline(id, listOf(vondelRing()), listOf(false))
                w.finish()
            }
        }
        PlaceStore.open(f).use { s -> assertNotNull(s.outline(id)) }
    }
}
