package app.atlas.render.data

import app.atlas.geo.BBox
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** The Overpass client with a scripted server: the query of fetch/regions.py, retries, halves merged by id. */
class OverpassClientTest {
    private fun node(id: Long, lat: Double, lon: Double) = """{"type":"node","id":$id,"lat":$lat,"lon":$lon,"tags":{"amenity":"cafe"}}"""

    private fun answer(vararg elements: String, remark: String? = null, time: String = "2026-10-02T16:02:40Z"): InputStream =
        ByteArrayInputStream(
            ("""{"version":0.6,"generator":"Overpass API","osm3s":{"timestamp_osm_base":"$time","copyright":"ODbL"},""" +
                (if (remark != null) """"remark":"$remark",""" else "") + """"elements":[${elements.joinToString(",")}]}""").toByteArray(),
        )

    private fun boxOf(query: String): BBox {
        val m = Regex("""\[bbox:([-0-9.]+),([-0-9.]+),([-0-9.]+),([-0-9.]+)]""").find(query) ?: error("no bbox in $query")
        val (s, w, n, e) = m.destructured
        return BBox(w.toDouble(), s.toDouble(), e.toDouble(), n.toDouble())
    }

    @Test
    fun theQueryIsTheOneFetchRegionsMakesForEveryRegion() {
        val places = Repo.places()
        val client = OverpassClient()
        for ((name, entry) in places) {
            val b = ((entry as JsonObject)["bbox"] as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content.toDouble() }
            val box = BBox(b[1], b[0], b[3], b[2])
            val py = Repo.py("overpass_query.py", Repo.root.path, name)
            assertEquals(name, py, client.query(box))
        }
    }

    @Test
    fun aBoxIsRequestedWholeAndTheAnswerKeepsOverpassOrder() {
        val sent = ArrayList<String>()
        val client = OverpassClient(http = { url, form, ua ->
            sent += url
            assertTrue(ua.startsWith("Atlas/"))
            answer(node(5, 52.0, 4.0), node(3, 52.0, 4.1), """{"type":"way","id":9,"bounds":{"minlat":1.0,"minlon":2.0,"maxlat":1.0,"maxlon":2.0},"nodes":[3],"geometry":[{"lat":1.0,"lon":2.0}],"tags":{"highway":"path"}}""")
        })
        val r = client.fetch(BBox(4.0, 52.0, 4.01, 52.01))
        assertEquals(listOf("https://overpass-api.de/api/interpreter"), sent)
        assertEquals(listOf(3L, 5L, 9L), r.elements.map { it.id })
        assertEquals("2026-10-02T16:02:40Z", r.source().dataTime)
    }

    @Test
    fun aFailingHostIsFollowedByTheNextAfterABackoff() {
        val tried = ArrayList<String>()
        val pauses = ArrayList<Long>()
        val client = OverpassClient(
            http = { url, _, _ ->
                tried += url
                if (tried.size < 3) throw IOException("overloaded") else answer(node(1, 52.0, 4.0))
            },
            pause = { pauses += it },
            backoffSeconds = 15,
        )
        val r = client.fetch(BBox(4.0, 52.0, 4.01, 52.01))
        assertEquals(1, r.elements.size)
        assertEquals(3, tried.size)
        assertEquals(listOf(15_000L, 30_000L), pauses)
        assertEquals(OverpassClient.HOSTS.take(3).map { "$it/api/interpreter" }, tried)
    }

    @Test
    fun aRuntimeErrorRemarkCountsAsAFailure() {
        var calls = 0
        val client = OverpassClient(
            http = { _, _, _ -> if (calls++ == 0) answer(node(1, 52.0, 4.0), remark = "runtime error: Query timed out") else answer(node(1, 52.0, 4.0), node(2, 52.0, 4.0)) },
            pause = {},
        )
        assertEquals(2, client.fetch(BBox(4.0, 52.0, 4.01, 52.01)).elements.size)
        assertEquals(2, calls)
    }

    @Test
    fun aBigBoxIsSplitAndTheHalvesAreMergedById() {
        val boxes = ArrayList<BBox>()
        val client = OverpassClient(
            http = { _, form, _ ->
                val b = boxOf(form.getValue("data"))
                boxes += b
                // a node on the seam, in both halves; a node in each half; a relation seen whole in one half and bare in the other
                val seam = node(7, 52.005, 4.005)
                val own = if (b.east <= 4.005 + 1e-9) node(1, 52.001, 4.001) else node(2, 52.001, 4.009)
                val rel = if (b.east <= 4.005 + 1e-9) """{"type":"relation","id":4,"bounds":{"minlat":52.0,"minlon":4.0,"maxlat":52.0,"maxlon":4.0},"members":[{"type":"way","ref":8,"role":"outer","geometry":[{"lat":52.0,"lon":4.0}]},{"type":"way","ref":9,"role":"outer"}],"tags":{"type":"multipolygon"}}"""
                else """{"type":"relation","id":4,"bounds":{"minlat":52.0,"minlon":4.0,"maxlat":52.0,"maxlon":4.01},"members":[{"type":"way","ref":8,"role":"outer"},{"type":"way","ref":9,"role":"outer","geometry":[{"lat":52.0,"lon":4.01}]}],"tags":{"type":"multipolygon"}}"""
                answer(seam, own, rel)
            },
            maxArea = 0.00005,
        )
        val r = client.fetch(BBox(4.0, 52.0, 4.01, 52.01))
        assertTrue("the box was split (${boxes.size} requests)", boxes.size >= 2)
        // seen twice, kept once; nodes before relations; ascending ids
        assertEquals(listOf(1L, 2L, 7L, 4L), r.elements.map { it.id })
        val rel = r.elements.last()
        assertEquals(ElementType.RELATION, rel.type)
        assertNotNull("the geometry from the first half", rel.members!![0].geometry)
        assertNotNull("the geometry from the second half", rel.members!![1].geometry)
        assertEquals(4.0, rel.bounds!!.minLon, 0.0)
        assertEquals(4.01, rel.bounds!!.maxLon, 0.0)
    }

    @Test
    fun aBoxOverpassCannotAnswerWholeIsAskedAgainInHalves() {
        val asked = ArrayList<BBox>()
        val client = OverpassClient(
            http = { _, form, _ ->
                val b = boxOf(form.getValue("data"))
                asked += b
                if (b.width > 0.01) throw IOException("timeout") else answer(node(if (b.west < 4.0) 1 else 2, 52.0, b.west + 0.001))
            },
            pause = {},
            maxArea = 1.0,
        )
        val r = client.fetch(BBox(3.99, 52.0, 4.01, 52.02))
        assertEquals(listOf(1L, 2L), r.elements.map { it.id })
        assertTrue(asked.size > 4)
    }

    @Test
    fun aBoxThatFailsEverywhereAndIsTinyGivesAnError() {
        val client = OverpassClient(http = { _, _, _ -> throw IOException("down") }, pause = {})
        try {
            client.fetch(BBox(4.0, 52.0, 4.001, 52.001))
            throw AssertionError("expected an error")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("down"))
        }
    }

    @Test
    fun anActualBuildFromOverpassMatchesADirectQuery() {
        assumeTrue("needs -Datlas.online=true", System.getProperty("atlas.online") == "true")
        // a few streets of Amsterdam's Jordaan, about 150 m across
        val box = BBox(4.8830, 52.3745, 4.8850, 52.3755)
        val client = OverpassClient(log = { println("OVERPASS $it") })
        val built = RegionBuilder.fromOverpass(client, "tiny", "Tiny", box, "NL")
        val direct = client.fetch(box)
        val a = built.elements.elements().toList()
        assertTrue("some elements", a.size > 20)
        // two fetches a moment apart: the same box, almost always the same elements
        val ids = a.map { it.type to it.id }.toSet()
        val same = direct.elements.count { (it.type to it.id) in ids }
        println("OVERPASS online: ${a.size} elements built, ${direct.elements.size} direct, $same in common")
        assertTrue(same >= direct.elements.size * 0.99)
        // and the pack made from it reads back
        val bytes = PackWriter.toBytes(built)
        assertEquals(a, PackReader.open(bytes).elements().toList())
    }
}
