package app.atlas.search

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.CategoryTree
import app.atlas.places.Presets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kinds that belong in two places, one home and more doors (the owner, 6 November: "Supermarket has often everything
 * the others sell? Doesnt sports somehow also belong in fun?", "A bakery might sell pastries", then "Yes, build it"):
 * a kind keeps its own family, colour and pin; a shelf holds the kinds of others that are what it is about too, after
 * its own; a kind's view names the kinds that also sell what it is named for.
 */
class DoorsTest {
    private val here = Fixtures.westerkerk
    private val saturday = Moment(Fixtures.saturday)
    private var next = 0L

    /** A node [north] metres north of the Westerkerk. */
    private fun place(north: Double, vararg tags: Pair<String, String>) =
        PlaceRecord.of(Place(OsmId(OsmType.NODE, ++next), LatLon(here.lat + north / 111_320.0, here.lon), mapOf(*tags)))

    private val playground = place(100.0, "leisure" to "playground")
    private val cinema = place(200.0, "amenity" to "cinema", "name" to "Kriterion")
    private val bouldering = place(300.0, "leisure" to "sports_centre", "sport" to "climbing", "name" to "Boulderhal")
    private val iceRink = place(400.0, "leisure" to "ice_rink", "name" to "Jaap Eden")
    private val waterPark = place(500.0, "leisure" to "water_park", "name" to "Tropicana")
    private val gym = place(600.0, "leisure" to "fitness_centre", "name" to "Sportschool")
    private val pitch = place(700.0, "leisure" to "pitch", "sport" to "soccer")
    private val skatepark = place(800.0, "leisure" to "skatepark")
    private val around = listOf(playground, cinema, bouldering, iceRink, waterPark, gym, pitch, skatepark)

    /** Fun holds the climbing walls, ice rinks and pools too, after its own kinds; Sport's pitches, gyms and skateparks stay Sport's. */
    @Test
    fun funHoldsTheDaysOutOfOtherShelvesAfterItsOwnKinds() {
        val outings = CategoryTree.shelf("fun")!!
        assertEquals(listOf("cat:climbing", "preset:leisure/ice_rink", "cat:swim"), outings.guests.map { it.id })
        assertEquals(listOf("shelf:sport"), outings.doors.map { it.id })
        assertEquals(listOf(playground, cinema, bouldering, iceRink, waterPark), around.filter { Browse.holds(outings, it) })
        // each keeps its own home, colour and pin: the climbing wall is Sport's still
        val sport = CategoryTree.shelf("sport")!!
        assertTrue(Browse.holds(sport, bouldering) && Browse.holds(sport, iceRink))
        assertEquals("sport", CategoryTree.of(bouldering.tags)!!.category.family)
        assertEquals(sport.colour, CategoryTree.category("climbing")!!.colour)
        // its row: its own kinds, then those it holds too; its results and its tile count them all
        val r = Browse.results(outings, around, here, saturday)
        val row = r.subcategories.filter { it.second > 0 }.map { it.first.id }
        assertEquals(setOf("cat:playground", "cat:cinema"), row.take(2).toSet())
        assertEquals(setOf("cat:climbing", "preset:leisure/ice_rink", "cat:swim"), row.drop(2).toSet())
        assertEquals(5, r.hits.size)
        assertEquals(5, Browse.pulses(listOf(outings), around, here, saturday).single().count)
        // its rule reads them from the store and online too
        assertTrue(outings.filter.matches(iceRink.tags) && outings.filter.matches(bouldering.tags))
        assertFalse(outings.filter.matches(gym.tags))
    }

    /**
     * The pools are Water's, and Fun's and Sport's too (the owner, 6 November: "And I think swim belongs in fun and
     * sport"): each shelf holds them and counts them, Sport's row ends with them after its own kinds, and their home,
     * colour and pin stay Water's.
     */
    @Test
    fun thePoolsAreWatersFunsAndSports() {
        val pool = place(550.0, "leisure" to "swimming_pool", "name" to "Zuiderbad")
        for (shelf in listOf("water", "fun", "sport")) assertTrue(shelf, Browse.holds(CategoryTree.shelf(shelf)!!, pool))
        val swim = CategoryTree.category("swim")!!
        assertEquals("water", CategoryTree.of(pool.tags)!!.category.family)
        assertTrue(CategoryTree.isUnder(CategoryTree.of(pool.tags)!!, swim))
        val sport = CategoryTree.shelf("sport")!!
        assertEquals(listOf("cat:swim"), sport.guests.map { it.id })
        val row = Browse.subcategories(sport, listOf(gym, pitch, pool, waterPark)).filter { it.second > 0 }
        assertEquals("cat:swim" to 2, row.last().first.id to row.last().second)
        assertEquals(swim.colour, row.last().first.colour)
        assertEquals(3, Browse.pulses(listOf(sport), listOf(gym, pitch, pool), here, saturday).single().count)
    }

    /** Stay opens on its hotels' kinds as before (the one category holding them is no step), the campsites after. */
    @Test
    fun stayListsItsOwnKindsThenTheCampsites() {
        val stay = CategoryTree.shelf("stay")!!
        val hotel = place(100.0, "tourism" to "hotel", "name" to "Hotel")
        val hostel = place(150.0, "tourism" to "hostel", "name" to "Hostel")
        val camp = place(200.0, "tourism" to "camp_site", "name" to "Zeeburg")
        val row = Browse.subcategories(stay, listOf(hotel, hostel, camp)).filter { it.second > 0 }.map { it.first.id }
        assertEquals(setOf("preset:tourism/hotel", "preset:tourism/hostel"), row.take(2).toSet())
        assertEquals("cat:camping", row.last())
        assertEquals("nature", CategoryTree.of(camp.tags)!!.category.family)
    }

    /**
     * A market is the groceries' and the shops' (the owner, 6 November: "What about markets?"): its stalls sell clothes
     * and flowers as well as cheese. Shops holds it after its own kinds and counts it; its home stays the groceries'.
     */
    @Test
    fun aMarketIsTheGroceriesAndTheShops() {
        val market = place(300.0, "amenity" to "marketplace", "name" to "Albert Cuypmarkt")
        val shoes = place(200.0, "shop" to "shoes", "name" to "Shoes")
        val clothes = place(250.0, "shop" to "clothes", "name" to "Clothes")
        val shops = CategoryTree.shelf("shops")!!
        assertEquals(listOf("preset:amenity/marketplace"), shops.guests.map { it.id })
        assertTrue(Browse.holds(shops, market) && Browse.holds(CategoryTree.shelf("groceries")!!, market))
        assertEquals("food_shops", CategoryTree.of(market.tags)!!.category.id)
        val row = Browse.subcategories(shops, listOf(market, shoes, clothes)).filter { it.second > 0 }.map { it.first.id }
        assertEquals(setOf("preset:shop/clothes", "preset:shop/shoes"), row.take(2).toSet())
        assertEquals(listOf("preset:amenity/marketplace"), row.drop(2))
        assertEquals(3, Browse.pulses(listOf(shops), listOf(market, shoes, clothes), here, saturday).single().count)
    }

    /**
     * Filed where they are, not shared: a ferry's terminal is a station, a bottle to take home the groceries', a game the
     * shops'; the bars stay the drinks', the marinas the water's.
     */
    @Test
    fun kindsFiledWhereTheyAre() {
        fun shelfOf(vararg tags: Pair<String, String>) = CategoryTree.shelves().first { Browse.holds(it, place(0.0, *tags)) }.id
        assertEquals("shelf:transport", shelfOf("amenity" to "ferry_terminal", "name" to "Buiksloterweg"))
        assertEquals("shelf:transport", shelfOf("public_transport" to "station", "ferry" to "yes", "amenity" to "ferry_terminal"))
        assertEquals("shelf:water", shelfOf("leisure" to "marina"))
        assertEquals("shelf:groceries", shelfOf("shop" to "alcohol", "name" to "Gall & Gall"))
        assertEquals("shelf:eat", shelfOf("amenity" to "pub"))
        assertEquals("shelf:shops", shelfOf("shop" to "video_games"))
        assertEquals("shelf:fun", shelfOf("leisure" to "bowling_alley"))
    }

    /**
     * What a kind is named for is also sold at the kinds that sell it likely too, the likeliest first: a pastry shop's
     * cakes at a bakery, then a supermarket; a butcher's meat at a supermarket, then the market; a frozen food shop's at a
     * supermarket (the owner, 6 November: "Supermarkets have frozen food"); a bakery is named for nothing of the
     * catalogue. Of a kind that surely sells several, at those that sell more than half of them: a supermarket's at a
     * convenience store, the chemist's detergent alone no reason; an organic supermarket's as a supermarket's; a hardware
     * store's at a variety store and a department store, not at a lighting shop for its bulbs.
     */
    @Test
    fun whatAKindIsNamedForIsAlsoSoldAt() {
        fun also(id: String) = GoodsCatalogue.alsoSold(Presets.byId(id)!!).map { it.preset?.id }
        assertEquals(listOf("shop/bakery", "shop/supermarket"), also("shop/pastry"))
        assertEquals(listOf("shop/supermarket", "amenity/marketplace"), also("shop/butcher"))
        assertEquals(listOf("shop/supermarket", "shop/convenience"), also("shop/frozen_food"))
        assertEquals(listOf("shop/convenience"), also("shop/supermarket"))
        assertEquals(also("shop/supermarket"), also("shop/supermarket/organic"))
        assertEquals(listOf("shop/variety_store", "shop/department_store"), also("shop/hardware"))
        assertTrue(also("shop/bakery").isEmpty())
        assertFalse("shop/florist" in also("shop/florist"))
        assertTrue("shop/supermarket" in also("shop/florist") && "shop/garden_centre" in also("shop/florist"))
    }
}
