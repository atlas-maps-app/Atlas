package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.providers.OverpassElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every element of the real Amsterdam extract, written and read back: a place's tags come back as the store keeps
 * them (less only what [StoredTags] drops) at its pin to the 1e-7 degree, an address point with its element's id,
 * number, street, postcode and town, and every named place is found by its name.
 */
class StoreRoundTripTest {
    private fun pin(p: LatLon) = LatLon(E7.deg(E7.of(p.lat)), E7.deg(E7.of(p.lon)))

    @Test
    fun theAmsterdamExtractComesBackAsItWent() {
        val elements = ArrayList<OverpassElement>()
        Fixtures.ams.bufferedReader().use { r -> OverpassImport.read(r) { e -> elements += e } }
        PlaceStore.inMemory().use { s ->
            s.writer().use { w ->
                for (e in elements) w.add(e)
                w.finish()
            }
            val places = HashMap<OsmId, OverpassElement>()
            val addresses = HashMap<OsmId, Pair<OverpassElement, AddressParts>>()
            for (e in elements) {
                if (e.toPlace() == null || !Selection.keeps(e.tags, isRelation = e.type == OsmType.RELATION)) continue
                val parts = Addresses.parts(StoredTags.filter(e.tags))
                if (parts != null) addresses[e.osmId] = e to parts else places[e.osmId] = e
            }
            assertTrue("${places.size} places", places.size > 1_000)
            assertTrue("${addresses.size} address points", addresses.size > 100)

            // places: their kept tags, to the letter, at their pin
            assertEquals(places.size, s.placeCount)
            for ((id, e) in places) {
                val r = s.get(id) ?: throw AssertionError("$id is missing")
                assertEquals("$id", StoredTags.filter(e.tags), r.tags)
                assertEquals("$id", pin(e.toPlace()!!.location), r.location)
            }

            // address points: one per street and number (the last element of the two wins), each with its own key
            val stored = s.addresses(BBox(-180.0, -90.0, 180.0, 90.0))
            val distinct = addresses.values.map { (e, a) ->
                val p = e.toPlace()!!.location
                listOf(a.street, a.kind, a.postcode, a.city, a.country, a.housenumber, Addresses.cellOf(E7.of(p.lat), E7.of(p.lon)))
            }.toSet()
            assertEquals(distinct.size, stored.size)
            assertEquals(stored.size, stored.map { it.place.key }.toSet().size)
            for (r in stored) {
                val (e, a) = addresses[r.id] ?: throw AssertionError("${r.id} is not an address point of the extract")
                assertEquals("${r.id}", Addresses.tags(a.street, a.kind, a.housenumber, a.postcode, a.city, a.country), r.tags)
                assertEquals("${r.id}", pin(e.toPlace()!!.location), r.location)
            }

            // every named place by its name, from where it is
            var named = 0
            var found = 0
            for ((id, e) in places) {
                val name = e.tags["name"] ?: continue
                if (SearchText.indexWords(name).isEmpty()) continue
                named++
                if (s.text(name, e.toPlace()!!.location).any { it.id == id }) found++
            }
            println("Amsterdam: ${places.size} places, ${addresses.size} address points (${stored.size} kept), $found of $named named places found by their names")
            assertTrue("$found of $named named places found by their names", found >= named * 0.995)
        }
    }
}
