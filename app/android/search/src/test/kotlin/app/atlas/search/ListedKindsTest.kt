package app.atlas.search

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.CategoryTree
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A place whose value lists several kinds (`cuisine=italian;pizza`) is each of them, as the kinds' lists read it (the
 * reviewer, 17 November, finding 11: its kind was decided by whole values, its lists by parts, so the kinds around
 * missed the Italian restaurants and the pizzerias that only such places are).
 */
class ListedKindsTest {
    private val here = Fixtures.westerkerk
    private val saturday = Moment(Fixtures.saturday, hours = Nowhere)

    private fun eatery(id: Long, cuisine: String) =
        PlaceRecord.of(Place(OsmId(OsmType.NODE, id), LatLon(here.lat + id * 0.0002, here.lon), mapOf("amenity" to "restaurant", "cuisine" to cuisine, "name" to "R$id")), country = null)

    @Test
    fun eachKindAListNamesIsAKindAroundCountingWhatItsListHolds() {
        val pool = listOf(eatery(1, "italian;pizza"), eatery(2, "italian;pizza"), eatery(3, "pizza;burger"))
        val kinds = EveryKind.around(pool, here, saturday).associateBy { it.node.id }
        val italian = CategoryTree.of(mapOf("amenity" to "restaurant", "cuisine" to "italian"))!!
        val pizza = CategoryTree.of(mapOf("amenity" to "restaurant", "cuisine" to "pizza"))!!
        assertEquals("the Italian restaurants: ${kinds.keys}", 2, kinds[italian.id]?.count)
        assertEquals("the pizzerias: ${kinds.keys}", 3, kinds[pizza.id]?.count)
        assertEquals(pool.count { Browse.holds(pizza, it) }, kinds[pizza.id]?.count)
        assertEquals("a place's own kind is its first", italian.id, CategoryTree.of(pool[0].tags)?.id)
    }
}
