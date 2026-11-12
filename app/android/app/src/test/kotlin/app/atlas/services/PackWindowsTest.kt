package app.atlas.services

import app.atlas.geo.BBox
import app.atlas.mapview.vk.Windows
import app.atlas.render.data.ElementType
import app.atlas.render.data.OsmElement
import app.atlas.render.data.PackReader
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.zip.GZIPInputStream

/**
 * A window's data from the element packs on the phone (the windows' local lane, ENGINE.md step 3), from the Westerkerk
 * pack the app bundles: Overpass's answer for the window's box as the pack holds it, each element once, in Overpass's
 * order, without the points the pack left null (the core's reader refuses them), dated by the pack's data and saying it
 * was never fetched whole; none for a box no pack meets.
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
    fun aBoxThePackMeetsIsAnsweredAsOverpassWouldDatedByThePacksDataAndNeverFetchedWhole() {
        val got = PackWindows.answer(listOf(BundledPack.file), box)!!
        val (dataTime, pack) = held()
        val date = Instant.ofEpochSecond(Instant.parse(dataTime!!).epochSecond)
        assertEquals(date, got.date)
        assertEquals("amsterdam", got.from)
        val text = text(got)
        val head = Windows.Head.of(text.toByteArray())!!
        assertEquals(date, head.base)
        assertEquals(Instant.EPOCH, head.whole)
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
        val whole = Json.parseToJsonElement(text(PackWindows.answer(listOf(BundledPack.file), box)!!)).jsonObject
        assertNull(whole["atlas"])
        // a window's box that reaches beyond the pack's east and north: the part within the pack, on the grid
        val wide = BBox(box.west, box.south, pack.east + 0.02, pack.north + 0.02)
        val part = Json.parseToJsonElement(text(PackWindows.answer(listOf(BundledPack.file), wide)!!)).jsonObject
        assertEquals(
            "${grid(wide.south)},${grid(wide.west)},${grid(pack.north)},${grid(pack.east)}",
            part.getValue("atlas").jsonObject.getValue("box").jsonPrimitive.content,
        )
        // still Overpass's answer, never fetched whole
        assertEquals("1970-01-01T00:00:00Z", part.getValue("whole").jsonPrimitive.content)
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

    @Test
    fun aBoxNoPackMeetsHasNoAnswer() {
        assertNull(PackWindows.answer(listOf(BundledPack.file), BBox(5.0, 53.0, 5.01, 53.01)))
        assertNull(PackWindows.answer(emptyList(), box))
    }
}
