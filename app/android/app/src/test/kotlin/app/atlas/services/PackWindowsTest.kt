package app.atlas.services

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.mapview.vk.Windows
import app.atlas.render.data.ElementType
import app.atlas.render.data.ListElementSource
import app.atlas.render.data.OsmElement
import app.atlas.render.data.PackReader
import app.atlas.render.data.PackSpec
import app.atlas.render.data.PackWriter
import app.atlas.render.data.Points
import app.atlas.testing.BundledPack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.zip.GZIPInputStream

/**
 * A window's data from the element packs on the phone (the windows' local lane, ENGINE.md step 3), from the Westerkerk
 * pack the app bundles: Overpass's answer for the window's box as the pack holds it, each element once, in Overpass's
 * order, without the points the pack left null (the core's reader refuses them), dated by the pack's data; whole as of
 * that date where a pack holds the window's whole box, never fetched whole where the window reaches beyond its packs
 * or a pack says no date for its data (it is dated by its file then); a closed way whose start the pack left out
 * still a ring; none for a box no pack meets.
 */
class PackWindowsTest {
    /** A window's box within the Westerkerk's pack (west, south, east, north). */
    private val box = BBox(4.880, 52.372, 4.885, 52.375)

    private fun text(p: Windows.Packed): String = GZIPInputStream(p.gz.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun elements(text: String): List<JsonObject> = Json.parseToJsonElement(text).jsonObject.getValue("elements").jsonArray.map { it.jsonObject }

    private fun points(e: JsonObject): Int = (e["geometry"] as? JsonArray)?.size ?: 0

    /** What the pack holds of [box], each element once. */
    private fun held(): Pair<String?, List<OsmElement>> = PackReader.open(BundledPack.file).use { r ->
        r.manifest.dataTime to r.query(box).distinctBy { it.type to it.id }
    }

    @Test
    fun aBoxThePackHoldsIsAnsweredAsOverpassWouldDatedByThePacksDataAndWholeAsOfIt() {
        val got = PackWindows.answer(listOf(BundledPack.file), box)!!
        val (dataTime, pack) = held()
        val date = Instant.ofEpochSecond(Instant.parse(dataTime!!).epochSecond)
        assertEquals(date, got.date)
        assertEquals("amsterdam", got.from)
        val text = text(got)
        val head = Windows.Head.of(text.toByteArray())!!
        assertEquals(date, head.base)
        // the pack holds the whole box: its data is whole as of its date, said where the core's merge reads it
        assertEquals(date, head.whole)
        val doc = Json.parseToJsonElement(text).jsonObject
        assertEquals(date.toString(), doc.getValue("atlas").jsonObject.getValue("whole").jsonPrimitive.content)
        assertNull(doc["whole"])
        // every element the pack holds of the box, once, by type and then by id
        val es = elements(text)
        val keys = es.map { ElementType.of(it.getValue("type").jsonPrimitive.content).ordinal to it.getValue("id").jsonPrimitive.long }
        assertEquals(pack.size, es.size)
        assertEquals(keys.sortedWith(compareBy<Pair<Int, Long>> { it.first }.thenBy { it.second }), keys)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(pack.map { it.type.ordinal to it.id }.toSet(), keys.toSet())
        // no point is null, in a way's geometry or a member's; each keeps the pack's other points
        for (e in es) {
            assertTrue((e["geometry"] as? JsonArray).orEmpty().none { it is JsonNull })
            for (m in (e["members"] as? JsonArray).orEmpty()) assertTrue((m.jsonObject["geometry"] as? JsonArray).orEmpty().none { it is JsonNull })
        }
        val byKey = es.associateBy { ElementType.of(it.getValue("type").jsonPrimitive.content) to it.getValue("id").jsonPrimitive.long }
        for (e in pack) {
            val g = e.geometry ?: continue
            assertEquals("${e.type} ${e.id}", (0 until g.size).count { !g.isNull(it) }, points(byKey.getValue(e.type to e.id)))
        }
    }

    @Test
    fun anElementTwoPacksHoldComesOnce() {
        val once = elements(text(PackWindows.answer(listOf(BundledPack.file), box)!!))
        val twice = elements(text(PackWindows.answer(listOf(BundledPack.file, BundledPack.file), box)!!))
        assertEquals(once, twice)
    }

    @Test
    fun theAnswerNamesThePartOfTheBoxItsPacksHoldWhereThatIsNotAllOfIt() {
        fun grid(degrees: Double) = Math.round(degrees * 1e7)
        val pack = PackReader.open(BundledPack.file).use { it.manifest.bbox }
        // a box the pack holds whole names no part: its data is the box's
        assertTrue(pack.west < box.west && pack.south < box.south && pack.east > box.east && pack.north > box.north)
        assertTrue(PackWindows.holds(pack, box))
        val whole = Json.parseToJsonElement(text(PackWindows.answer(listOf(BundledPack.file), box)!!)).jsonObject
        assertNull(whole.getValue("atlas").jsonObject["box"])
        // a window's box that reaches beyond the pack's east and north: the part within the pack, on the grid
        val wide = BBox(box.west, box.south, pack.east + 0.02, pack.north + 0.02)
        assertFalse(PackWindows.holds(pack, wide))
        val part = Json.parseToJsonElement(text(PackWindows.answer(listOf(BundledPack.file), wide)!!)).jsonObject
        assertEquals(
            "${grid(wide.south)},${grid(wide.west)},${grid(pack.north)},${grid(pack.east)}",
            part.getValue("atlas").jsonObject.getValue("box").jsonPrimitive.content,
        )
        // still Overpass's answer, but not the whole of its box's: never fetched whole
        assertEquals("1970-01-01T00:00:00Z", part.getValue("whole").jsonPrimitive.content)
        assertNull(part.getValue("atlas").jsonObject["whole"])
        assertTrue(Windows.Head.of(text(PackWindows.answer(listOf(BundledPack.file), wide)!!).toByteArray())!!.never)
        assertTrue(elements(text(PackWindows.answer(listOf(BundledPack.file), wide)!!)).isNotEmpty())
        // one pack over the box's south-west; two packs: the bounds of what each holds of it, here all of it, so no part
        // is named; nor where a pack holds it all, or none of it
        val window = BBox(4.0, 52.0, 4.1, 52.1)
        val west = BBox(3.9, 51.9, 4.04, 52.05)
        val east = BBox(4.06, 52.02, 4.2, 52.2)
        assertEquals("520000000,40000000,520500000,40400000", PackWindows.held(listOf(west), window))
        assertEquals("520200000,40600000,521000000,41000000", PackWindows.held(listOf(east), window))
        assertNull(PackWindows.held(listOf(west, east), window))
        assertNull(PackWindows.held(listOf(west, BBox(3.9, 51.9, 4.2, 52.2)), window))
        assertNull(PackWindows.held(listOf(BBox(5.0, 53.0, 5.1, 53.1)), window))
        assertNull(PackWindows.held(emptyList(), window))
    }

    /** A pack of [box]'s surroundings holding [elements], its data dated [dataTime] or not at all, written to [file]. */
    private fun pack(file: File, elements: List<OsmElement>, dataTime: String?, bbox: BBox = BBox(4.87, 52.36, 4.90, 52.38)): File {
        PackWriter.write(PackSpec("test", "A test's pack", bbox, LatLon(52.37, 4.88), null, null, JsonObject(emptyMap()), ListElementSource(elements), dataTime = dataTime), file)
        return file
    }

    private fun way(id: Long, nodes: LongArray, vararg points: Pair<Double, Double>?): OsmElement = OsmElement(
        ElementType.WAY, id, tags = mapOf("building" to "yes"), nodes = nodes,
        geometry = Points(DoubleArray(points.size) { points[it]?.first ?: Double.NaN }, DoubleArray(points.size) { points[it]?.second ?: Double.NaN }),
    )

    @Test
    fun aPackThatSaysNoDateGivesItsWindowDatedByItsFileAndNeverWhole() {
        val dir = Files.createTempDirectory("atlas-packwindows").toFile()
        try {
            val house = way(7, longArrayOf(1, 2, 3, 1), 52.3730 to 4.8810, 52.3731 to 4.8812, 52.3732 to 4.8810, 52.3730 to 4.8810)
            // no date in its manifest nor its extract's head: its file's time, to the second, and never whole though
            // the pack holds the whole box (changes since an unknown date cannot be asked for)
            val undated = pack(File(dir, "undated.atlas"), listOf(house), null)
            val written = Instant.parse("2026-10-01T09:30:15Z")
            assertTrue(undated.setLastModified(written.toEpochMilli() + 400))
            PackReader.open(undated).use { r -> assertNull(r.manifest.dataTime) }
            val got = PackWindows.answer(listOf(undated), box)!!
            assertEquals(written, got.date)
            val head = Windows.Head.of(text(got).toByteArray())!!
            assertEquals(written, head.base)
            assertTrue(head.never)
            assertEquals(listOf(7L), elements(text(got)).map { it.getValue("id").jsonPrimitive.long })
            // a pack with its data's date, holding the box: whole as of it
            val dated = pack(File(dir, "dated.atlas"), listOf(house), "2026-10-20T08:00:00Z")
            val whole = Windows.Head.of(text(PackWindows.answer(listOf(dated), box)!!).toByteArray())!!
            assertEquals(Instant.parse("2026-10-20T08:00:00Z"), whole.whole)
            // the two together: the oldest date, and never whole, as one of them does not say its data's
            val both = Windows.Head.of(text(PackWindows.answer(listOf(dated, undated), box)!!).toByteArray())!!
            assertEquals(written, both.base)
            assertTrue(both.never)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aClosedWayWhoseStartThePackLeftOutIsStillARing() {
        val a = 52.3730 to 4.8810
        val b = 52.3731 to 4.8812
        val c = 52.3732 to 4.8810
        val d = 52.3731 to 4.8808
        fun points(e: OsmElement): List<Pair<Double, Double>> = e.geometry!!.let { g -> (0 until g.size).map { g.lat[it] to g.lon[it] } }
        // closed, its first node (and so its last) beyond the pack's box: the first point left again at its end
        assertEquals(listOf(b, c, d, b), points(PackWindows.solid(way(1, longArrayOf(10, 11, 12, 13, 10), null, b, c, d, null))))
        // closed, a point in its middle left out: it closes as it did, no point added
        assertEquals(listOf(a, b, d, a), points(PackWindows.solid(way(2, longArrayOf(10, 11, 12, 13, 10), a, b, null, d, a))))
        // an open way whose ends lie beyond the box stays open (a street is no ring)
        assertEquals(listOf(b, c, d), points(PackWindows.solid(way(3, longArrayOf(10, 11, 12, 13, 14), null, b, c, d, null))))
        // two points left of a closed way are no ring
        assertEquals(listOf(b, c), points(PackWindows.solid(way(4, longArrayOf(10, 11, 12, 13, 10), null, b, c, null, null))))
        // through a pack and the answer: the core reads a ring (its first point again at its end)
        val dir = Files.createTempDirectory("atlas-packwindows").toFile()
        try {
            val cut = pack(File(dir, "cut.atlas"), listOf(way(1, longArrayOf(10, 11, 12, 13, 10), null, b, c, d, null)), "2026-10-20T08:00:00Z")
            val e = elements(text(PackWindows.answer(listOf(cut), box)!!)).single()
            val g = e.getValue("geometry").jsonArray
            assertEquals(4, g.size)
            assertEquals(g.first(), g.last())
            assertEquals(5, e.getValue("nodes").jsonArray.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aBoxNoPackMeetsHasNoAnswer() {
        assertNull(PackWindows.answer(listOf(BundledPack.file), BBox(5.0, 53.0, 5.01, 53.01)))
        assertNull(PackWindows.answer(emptyList(), box))
    }
}
