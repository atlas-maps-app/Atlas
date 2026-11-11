package app.atlas.ui.search

import app.atlas.places.CategoryNode
import app.atlas.places.CategoryTree
import app.atlas.places.NodeKind
import app.atlas.ui.kit.hasSymbol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each shelf, category and kind shows on its card and band: what it is, never another's picture. */
class CategoryArtTest {
    private fun node(id: String): CategoryNode = checkNotNull(CategoryTree.byId(id)) { id }

    @Test
    fun everyShelfKeepsItsScene() {
        for (s in CategoryTree.shelves()) assertTrue(s.id, artOf(s) is Scene)
    }

    @Test
    fun drinksAndNightlifeHaveNoPasta() {
        val drinks = node("cat:drinks")
        val under = CategoryTree.all().filter { CategoryTree.isUnder(it, drinks) }
        assertTrue(under.size > 3)
        for (n in under) assertTrue(n.id, artOf(n) != Scene(Motif.PLATES))
        assertEquals(Emblem("food.beer"), artOf(node("preset:amenity/pub")))
    }

    @Test
    fun theScenesStayWhereTheyFit() {
        val pictured = mapOf(
            "cat:restaurants" to Motif.PLATES,
            "preset:amenity/restaurant" to Motif.PLATES,
            "cat:food_shops" to Motif.PRODUCE,
            "preset:shop/greengrocer" to Motif.PRODUCE,
            "preset:leisure/park" to Motif.HILLS,
            "preset:leisure/garden/botanical" to Motif.HILLS,  // its glasshouse, drawn 10 November, keeps the garden's hills
            "preset:amenity/hospital" to Motif.PULSE,
            "preset:amenity/pharmacy" to Motif.PULSE,
            "preset:shop/car_repair" to Motif.GEARS,
            "preset:leisure/pitch/soccer" to Motif.PITCH,
            "cat:worship" to Motif.COLONNADE,
        )
        for ((id, motif) in pictured) assertEquals(id, Scene(motif), artOf(node(id)))
    }

    @Test
    fun aSceneThatFitsAWholeCategoryStaysOnEveryKindOfIt() {
        for ((id, motif) in mapOf("cat:hotels" to Motif.NIGHT, "cat:transit" to Motif.LINES, "cat:beach" to Motif.WAVES)) {
            val category = node(id)
            val kinds = CategoryTree.all().filter { CategoryTree.isUnder(it, category) }
            assertTrue(id, kinds.size >= 4)
            for (k in kinds) assertEquals(k.id, Scene(motif), artOf(k))
        }
    }

    @Test
    fun aKindTheSceneIsNotOfWearsItsOwnSymbol() {
        val own = listOf(
            "cat:toilets", "cat:laundry", "cat:bike_parking", "cat:charging", "preset:amenity/cafe", "preset:shop/bakery",
            "preset:amenity/restaurant/sushi", "preset:amenity/place_of_worship/christian", "preset:natural/volcano",
            "preset:leisure/dog_park", "preset:amenity/dentist", "preset:leisure/pitch/tennis",
            // a cuisine with a symbol of its own wears it, as the sushi does: the Italian's spaghetti, drawn 10 November
            "preset:amenity/restaurant/italian",
        )
        for (id in own) node(id).let { assertEquals(id, Emblem(it.symbol), artOf(it)) }
    }

    @Test
    fun whatEveryNodeShowsCanBeDrawn() {
        val kinds = CategoryTree.all().filter { it.kind != NodeKind.SHELF }
        assertTrue(kinds.size > 800)
        for (n in kinds) (artOf(n) as? Emblem)?.let { assertTrue("${n.id} ${it.symbol}", hasSymbol(it.symbol)) }
    }
}
