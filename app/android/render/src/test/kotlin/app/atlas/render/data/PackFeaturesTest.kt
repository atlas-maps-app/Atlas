package app.atlas.render.data

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.LocalFrame
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [PackFeatures] on the bundled Westerkerk pack (`amsterdam.atlas`) and on the extract it was made from
 * (`data/ams/raw.json`): both answer alike, and the answers are the OpenStreetMap ones, checked against brute force.
 */
class PackFeaturesTest {
    companion object {
        private val repo = File(System.getProperty("atlas.repo") ?: "../../..")
        private val packFile = File(repo, "app/android/app/src/main/assets/packs/amsterdam.atlas")
        private val rawFile = File(repo, "data/ams/raw.json")

        val raw: List<OsmElement> by lazy {
            val out = ArrayList<OsmElement>()
            rawFile.inputStream().buffered().use { OverpassJson.read(OverpassJson.pull(it)) { e -> out += e } }
            out
        }
        val pack: PackFeatures by lazy { PackFeatures(listOf(PackBoxes(PackReader.open(packFile)))) }
        val memory: PackFeatures by lazy { PackFeatures(listOf(MemoryBoxes(raw))) }

        val WESTERKERK = LatLon(52.37452, 4.88377)
    }

    @Test
    fun thePackAndItsExtractAnswerAlike() {
        for (box in listOf(BBox.around(WESTERKERK, 150.0), BBox.around(LatLon(52.3762, 4.8889), 80.0), BBox.around(LatLon(52.3722, 4.8800), 300.0))) {
            val a = pack.elements(box)
            val b = memory.elements(box)
            assertTrue("${a.size}", a.isNotEmpty())
            assertEquals(b.map { it.toString() }, a.map { it.toString() })
            assertEquals(b, a)
        }
    }

    @Test
    fun aTiledPackAnswersAsTheExtractPackDoes() {
        // Format 2 (the tile pyramid) of the same extract: the same elements, streets, outlines, addresses and bridges.
        val v1 = PackReader.open(packFile)
        val spec = PackSpec("ams", "ams", v1.manifest.bbox, v1.manifest.origin, "Europe/Amsterdam", "nl_amsterdam", v1.manifest.config, ListElementSource(raw))
        val v2 = PackReader.open(PackWriter.tiledBytes(spec))
        assertTrue(v2.isTiled)
        val tiled = PackFeatures(listOf(PackBoxes(v2)))
        for (box in listOf(BBox.around(WESTERKERK, 150.0), BBox.around(LatLon(52.3722, 4.8800), 300.0))) {
            assertEquals(pack.elements(box).map { it.toString() }, tiled.elements(box).map { it.toString() })
        }
        val corner = LatLon(52.37580, 4.88423)
        assertEquals(pack.streetName(corner, 118.0), tiled.streetName(corner, 118.0))
        assertEquals(pack.containing(WESTERKERK).map { it.element.toString() }, tiled.containing(WESTERKERK).map { it.element.toString() })
        assertEquals(pack.addressAt(WESTERKERK)!!.element, tiled.addressAt(WESTERKERK)!!.element)
        val line = listOf(LatLon(52.37780, 4.88520), LatLon(52.37590, 4.88420), LatLon(52.37470, 4.88360))
        assertEquals(pack.bridges(line).map { it.element.toString() to it.along }, tiled.bridges(line).map { it.element.toString() to it.along })
    }

    @Test
    fun elementsMeetTheBoxAsBruteForceSays() {
        val box = BBox.around(LatLon(52.3750, 4.8850), 120.0)
        val brute = raw.filter { e -> PackFeatures.boxOf(e)?.intersects(box) == true }.sortedWith(OsmElement.CANONICAL)
        assertEquals(brute.map { it.toString() }, pack.elements(box).map { it.toString() })
        val shops = pack.elements(box) { it.tag("shop") != null }
        assertEquals(brute.count { it.tag("shop") != null }, shops.size)
    }

    @Test
    fun twoSourcesGiveEachElementOnce() {
        val both = PackFeatures(listOf(PackBoxes(PackReader.open(packFile)), MemoryBoxes(raw)))
        val box = BBox.around(WESTERKERK, 100.0)
        assertEquals(pack.elements(box).map { it.toString() }, both.elements(box).map { it.toString() })
    }

    @Test
    fun coverageIsTheManifestsBox() {
        assertTrue(pack.covers(WESTERKERK))
        assertTrue(pack.covers(BBox.around(WESTERKERK, 300.0)))
        assertFalse(pack.covers(BBox.around(WESTERKERK, 3_000.0)))
        assertFalse(pack.covers(LatLon(48.8584, 2.2945)))
        assertTrue(pack.elements(BBox.around(LatLon(48.8584, 2.2945), 500.0)).isEmpty())
    }

    @Test
    fun namesTheStreetAlongTheWayOfTravel() {
        // The street namer's cases on the tiles, now on OpenStreetMap's own ways.
        assertEquals("Prinsengracht", pack.streetName(LatLon(52.37530, 4.88405), 15.0))
        assertEquals("Raadhuisstraat", pack.streetName(LatLon(52.3738, 4.8860), 105.0))
        // At the corner of the Prinsengracht and the Leliegracht: heading along each names each.
        val corner = LatLon(52.37580, 4.88423)
        assertEquals("Leliegracht", pack.streetName(corner, 118.0))
        assertEquals("Prinsengracht", pack.streetName(corner, 25.0))
        assertNull(pack.streetName(LatLon(48.8584, 2.2945), 0.0))
        assertEquals(memory.streetName(corner, 118.0), pack.streetName(corner, 118.0))
    }

    @Test
    fun withoutABearingTheNearestStreetOrCanal() {
        val name = pack.streetName(LatLon(52.37530, 4.88405), null, 120.0)
        assertEquals("Prinsengracht", name)
        // Canals are named for boats: the waterway runs in the middle of the canal, between the quays.
        val canal = pack.streets(BBox.around(LatLon(52.3755, 4.8837), 60.0)).filter { it.water }
        assertTrue(canal.any { it.name == "Prinsengracht" && it.element.tag("waterway") == "canal" })
    }

    @Test
    fun theOutlinesHoldingTheWesterkerkSmallestFirst() {
        val held = pack.containing(WESTERKERK)
        val ids = held.map { PackFeatures.osmId(it.element) }
        // The church's parts over the point (the nave among them), the church, then its block, the quarter and the
        // heritage area.
        val parts = listOf(749268115L, 749268117L, 749268118L).map { OsmId(OsmType.WAY, it) }.toSet()
        assertEquals(parts, ids.take(3).toSet())
        assertEquals(OsmId(OsmType.WAY, 99205257), ids[3])
        assertTrue(ids.contains(OsmId(OsmType.RELATION, 17927799)))
        assertTrue(held.zipWithNext().all { (a, b) -> a.areaM2 <= b.areaM2 })
        val church = held.first { it.element.id == 99205257L }
        assertTrue("${church.areaM2}", church.areaM2 in 900.0..3_000.0)
        assertTrue(church.contains(church.label()))
        val quarter = held.first { it.element.tag("place") == "quarter" }
        assertEquals("Grachtengordel", quarter.element.tag("name"))
        assertEquals(ids, memory.containing(WESTERKERK).map { PackFeatures.osmId(it.element) })
    }

    @Test
    fun aMultipolygonHoldsWhatItsRingsHold() {
        val jordaan = raw.first { it.type == ElementType.RELATION && it.id == 12011952L }
        val o = PackFeatures.outlineOf(jordaan)
        assertNotNull(o)
        // The Jordaan lies west of the Prinsengracht; the Westerkerk east of it.
        assertTrue(o!!.contains(LatLon(52.3755, 4.8812)))
        assertFalse(o.contains(WESTERKERK))
        assertTrue(o.areaM2 > 500_000)
    }

    @Test
    fun ringsCloseFromWaysInAnyDirection() {
        // A 0.002° square from two open ways, the second drawn backwards, with a square hole of 0.0005°.
        fun pts(vararg p: Double) = Points(DoubleArray(p.size / 2) { p[2 * it] }, DoubleArray(p.size / 2) { p[2 * it + 1] })
        val a = pts(52.0, 4.0, 52.0, 4.002, 52.002, 4.002)
        val b = pts(52.0, 4.0, 52.002, 4.0, 52.002, 4.002)
        val hole = pts(52.00075, 4.00075, 52.00075, 4.00125, 52.00125, 4.00125, 52.00125, 4.00075, 52.00075, 4.00075)
        val rel = OsmElement(
            ElementType.RELATION, 1, tags = mapOf("type" to "multipolygon", "landuse" to "grass"),
            members = listOf(Member(ElementType.WAY, 1, "outer", geometry = a), Member(ElementType.WAY, 2, "outer", geometry = b), Member(ElementType.WAY, 3, "inner", geometry = hole)),
        )
        val o = PackFeatures.outlineOf(rel)!!
        assertTrue(o.contains(LatLon(52.0003, 4.0003)))
        assertFalse(o.contains(LatLon(52.001, 4.001)))
        assertFalse(o.contains(LatLon(52.003, 4.001)))
        val side = Geo.distance(52.001, 4.0, 52.001, 4.002)
        val tall = Geo.distance(52.0, 4.001, 52.002, 4.001)
        assertEquals(side * tall * (1 - 0.0625), o.areaM2, side * tall * 0.01)
        // A member the extract cut off leaves no outline rather than a wrong one.
        val cut = OsmElement(ElementType.RELATION, 2, tags = rel.tags, members = listOf(Member(ElementType.WAY, 1, "outer", geometry = a)))
        assertNull(PackFeatures.outlineOf(cut))
        // A closed road is a line, unless it is an area.
        val ring = pts(52.0, 4.0, 52.0, 4.002, 52.002, 4.002, 52.0, 4.0)
        assertNull(PackFeatures.outlineOf(OsmElement(ElementType.WAY, 3, tags = mapOf("highway" to "residential"), geometry = ring)))
        assertNotNull(PackFeatures.outlineOf(OsmElement(ElementType.WAY, 4, tags = mapOf("highway" to "pedestrian", "area" to "yes"), geometry = ring)))
    }

    @Test
    fun addressesNearestFirstAndZeroInside() {
        val at = LatLon(52.37530, 4.88350)
        val found = pack.addressesNear(at, 5)
        assertTrue(found.isNotEmpty())
        assertTrue(found.zipWithNext().all { (a, b) -> a.distance <= b.distance })
        assertTrue(found.all { it.street != null && it.housenumber != null })
        // The brute force: every addressed element's distance, the nearest the same.
        val brute = raw.filter(PackFeatures::isAddress).map { it to PackFeatures.distance(it, at) }.minBy { it.second }
        assertEquals(brute.second, found.first().distance, 1e-9)
        val inside = found.filter { it.distance == 0.0 }
        for (a in inside) assertTrue(a.outline!!.contains(at))
        for (a in found) assertTrue(Geo.distance(at, a.at) < 60.0)
    }

    @Test
    fun theAddressOfTheBuildingOneIsIn() {
        // Inside the church: the nearest of the address points inside its outline (BAG's, on the Westermarkt), not the
        // house next door whose point is nearer.
        val a = pack.addressAt(WESTERKERK)!!
        val church = pack.containing(WESTERKERK) { it.id == 99205257L }.single()
        assertEquals(0.0, a.distance, 0.0)
        assertTrue(church.contains(a.at))
        assertEquals("Westermarkt 60A", "${a.street} ${a.housenumber}")
        val nextDoor = pack.addressesNear(WESTERKERK, 1).first()
        assertFalse(church.contains(nextDoor.at))
        assertTrue(Geo.distance(WESTERKERK, nextDoor.at) < Geo.distance(WESTERKERK, a.at))
        // On the canal, in no building: the nearest address.
        val canal = LatLon(52.37500, 4.88360)
        assertTrue(pack.containing(canal) { it.tag("building") != null }.isEmpty())
        assertEquals(pack.addressesNear(canal, 1).first().element, pack.addressAt(canal)!!.element)
        assertNull(pack.addressAt(LatLon(48.8584, 2.2945)))
    }

    @Test
    fun theBridgesOverThePrinsengracht() {
        // A boat down the Prinsengracht from the Brouwersgracht end towards the Reestraat, on the canal's own line.
        val line = listOf(LatLon(52.37780, 4.88520), LatLon(52.37590, 4.88420), LatLon(52.37470, 4.88360), LatLon(52.37380, 4.88340), LatLon(52.37235, 4.88350))
        val found = pack.bridges(line)
        assertTrue("${found.size}", found.size >= 3)
        assertTrue(found.zipWithNext().all { (a, b) -> a.along <= b.along })
        // Brute force over every element of the extract: each bridge way crossing a segment of the line.
        val frame = LocalFrame(line[0])
        var expected = 0
        for (e in raw) {
            if (!PackFeatures.isBridge(e) || e.tag("man_made") == "bridge") continue
            for (g in PackFeatures.linesOf(e)) for (k in 0 until g.size - 1) for (j in 0 until line.size - 1) {
                if (g.isNull(k) || g.isNull(k + 1)) continue
                val t = PackFeatures.intersect(
                    frame.x(line[j].lon), frame.y(line[j].lat), frame.x(line[j + 1].lon), frame.y(line[j + 1].lat),
                    frame.x(g.lon[k]), frame.y(g.lat[k]), frame.x(g.lon[k + 1]), frame.y(g.lat[k + 1]),
                )
                if (t >= 0) expected++
            }
        }
        assertEquals(expected, found.count { it.element.tag("man_made") != "bridge" })
        for (b in found) assertTrue(Geo.distance(b.at, line[0]) < 800.0)
        assertEquals(found.map { it.element.toString() to it.along }, memory.bridges(line).map { it.element.toString() to it.along })
    }

    @Test
    fun placesAsTheStoreImportsThem() {
        val church = raw.first { it.type == ElementType.WAY && it.id == 99205257L }
        val p = PackFeatures.place(church)!!
        assertEquals(OsmId(OsmType.WAY, 99205257), p.id)
        assertEquals(p.bbox!!.center, p.location)
        assertEquals("Westerkerk", p.tags["name"])
        val node = raw.first { it.type == ElementType.NODE && it.tags != null }
        assertEquals(LatLon(node.lat, node.lon), PackFeatures.place(node)!!.location)
        assertNull(PackFeatures.place(raw.first { it.type == ElementType.NODE && it.tags == null }))
    }

    @Test
    fun memoryBoxesQueryLikeBruteForce() {
        val m = MemoryBoxes(raw)
        val box = BBox.around(LatLon(52.3740, 4.8870), 90.0)
        val brute = raw.filter { PackFeatures.boxOf(it)?.intersects(box) == true }.map { it.toString() }.toSet()
        assertEquals(brute, m.query(box).map { it.toString() }.toSet())
        assertTrue(MemoryBoxes(emptyList()).query(box).isEmpty())
    }
}
