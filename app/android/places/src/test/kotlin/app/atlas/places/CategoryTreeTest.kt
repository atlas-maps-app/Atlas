package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryTreeTest {
    @Test
    fun theShelvesAreTheRowThenTheFamilies() {
        assertEquals(
            listOf("eat", "groceries", "shops", "stay", "charge", "nature", "fun", "water", "culture", "sport", "health", "service", "transport", "osm"),
            CategoryTree.shelves().map { it.category.family },
        )
        for (s in Categories.shelves()) for (lang in Langs.bundled) assertTrue("${s.id} $lang", s.name(lang).isNotBlank())
        assertEquals("Onderweg", CategoryTree.shelf("transport")!!.name("nl"))
        assertEquals(listOf("skate", "climbing", "sports", "stadium", "gym"), CategoryTree.shelf("sport")!!.children.map { it.category.id })
        // a family's shelf and a leaf may share a name: each keeps its own
        assertEquals(NodeKind.SHELF, CategoryTree.shelf("health")!!.kind)
        assertEquals(NodeKind.CATEGORY, CategoryTree.category("health")!!.kind)
        assertEquals("shelf:health", CategoryTree.category("health")!!.parentId)
        assertNull(Categories.groups().firstOrNull { it.family == "culture" })
    }

    /**
     * Filling up is one family (the owner, 6 November: "We only have a charge category, where is the fuel category? Gas
     * and charging belongs both into fuel and what other kinds of fuel there are"): the gas stations and the chargers,
     * the bottled gas and heating fuel shops and the marinas' pumps its guests; Getting around holds none of them.
     */
    @Test
    fun fuelHoldsTheGasStationsAndTheChargers() {
        val fuel = CategoryTree.shelf("charge")!!
        assertEquals("Fuel", fuel.name("en"))
        assertEquals("Tanken & laden", fuel.name("nl"))
        assertEquals(listOf("cat:fuel", "cat:charging"), fuel.children.map { it.id })
        assertEquals(listOf("preset:shop/gas", "preset:shop/fuel", "preset:waterway/fuel"), fuel.guests.map { it.id })
        assertEquals("Gas stations", CategoryTree.category("fuel")!!.name("en"))
        assertTrue(CategoryTree.shelf("transport")!!.children.none { it.id == "cat:fuel" })
    }

    /**
     * The kinds a shelf holds too and the shelves it leads into (categories.py ALSO) are nodes of the tree, none named in
     * vain; a kind it holds too is another shelf's, and its places pass the shelf's rule.
     */
    @Test
    fun aShelfsGuestsAndDoorsAreNodes() {
        for (shelf in CategoryTree.shelves()) {
            assertEquals(shelf.id, shelf.category.also.size, shelf.guests.size + shelf.doors.size)
            for (g in shelf.guests) {
                assertFalse("${shelf.id} holds its own ${g.id}", CategoryTree.isUnder(g, shelf))
                g.preset?.let { p -> assertTrue("${shelf.id} takes ${g.id}", shelf.filter.matches(p.tags)) }
                assertTrue(CategoryTree.leadsTo(shelf, g))
            }
        }
        val outings = CategoryTree.shelf("fun")!!
        // through Sport's door, its kinds too; not a kind of no door
        assertTrue(CategoryTree.leadsTo(outings, CategoryTree.category("gym")!!))
        assertFalse(CategoryTree.leadsTo(outings, CategoryTree.category("restaurants")!!))
        assertEquals(listOf("cat:camping"), CategoryTree.shelf("stay")!!.guests.map { it.id })
    }

    @Test
    fun everyCategoryStandsOnItsShelf() {
        for (leaf in Categories.leaves()) {
            val n = CategoryTree.category(leaf.id)
            assertNotNull(leaf.id, n)
            assertSame(leaf, n!!.category)
            assertEquals(leaf.id, "shelf:" + leaf.shelf!!.family, n.parentId)
            assertEquals(leaf.id, 1, n.depth)
        }
        assertEquals(Categories.leaves().size, CategoryTree.all().count { it.kind == NodeKind.CATEGORY })
    }

    @Test
    fun presetsNestUnderTheirCategoryAndParent() {
        val restaurants = CategoryTree.category("restaurants")!!
        val kids = restaurants.children.map { it.id }
        for (p in listOf("amenity/restaurant", "amenity/cafe", "amenity/fast_food", "amenity/ice_cream", "amenity/food_court")) {
            assertTrue(p, "preset:$p" in kids)
        }
        val pizza = CategoryTree.preset("amenity/restaurant/pizza")!!
        assertEquals("preset:amenity/restaurant", pizza.parentId)
        assertEquals(listOf("shelf:eat", "cat:restaurants", "preset:amenity/restaurant", "preset:amenity/restaurant/pizza"), CategoryTree.pathOf(pizza.id).map { it.id })
        assertEquals(3, pizza.depth)
        assertTrue(pizza.name("de").startsWith("Pizz"))
        assertTrue(pizza.filter.matches(mapOf("amenity" to "restaurant", "cuisine" to "italian;pizza")))
        assertTrue(CategoryTree.isUnder(pizza, restaurants))
        // a wildcard narrows nothing: it names its key's node
        assertNull(CategoryTree.preset("amenity"))
        val presets = CategoryTree.all().count { it.kind == NodeKind.PRESET }
        println("category tree: ${CategoryTree.all().size} nodes, $presets presets, ${CategoryTree.all().count { it.kind == NodeKind.KEY }} keys")
        assertTrue(presets > 1_400)
    }

    /**
     * The kinds people come to a category for most (the owner, 6 November: "thats what people primarily search for when
     * looking for groceries"): the groceries' supermarkets and convenience stores, an organic supermarket among them and a
     * bakery not; its shelf has them too, a kind none. Each is a kind of its category (categories.py CORE).
     */
    @Test
    fun whatPeopleComeForMostIsNamedPerCategory() {
        val groceries = CategoryTree.category("food_shops")!!
        assertEquals(setOf("shop/supermarket", "shop/convenience"), groceries.core)
        assertEquals(groceries.core, CategoryTree.shelf("groceries")!!.core)
        for (p in listOf("shop/supermarket", "shop/supermarket/organic", "shop/convenience")) assertTrue(p, groceries.isCore(Presets.byId(p)))
        for (p in listOf("shop/bakery", "shop/butcher", "shop/greengrocer", "amenity/marketplace")) assertFalse(p, groceries.isCore(Presets.byId(p)))
        assertFalse(groceries.isCore(null))
        assertTrue(CategoryTree.category("restaurants")!!.core.isEmpty())
        assertTrue(CategoryTree.preset("shop/supermarket")!!.core.isEmpty())
        for (c in CategoryTree.all().filter { it.kind == NodeKind.CATEGORY }) for (id in c.core) {
            val n = CategoryTree.preset(id)
            assertNotNull("${c.id} $id", n)
            assertTrue("${c.id} $id", CategoryTree.isUnder(n!!, c))
        }
    }

    @Test
    fun everythingElseStandsUnderItsKey() {
        val lake = CategoryTree.preset("natural/water/lake")!!
        assertEquals(listOf("shelf:osm", "key:natural", "preset:natural/water", "preset:natural/water/lake"), CategoryTree.pathOf(lake.id).map { it.id })
        assertEquals("Natural Feature", CategoryTree.key("natural")!!.name("en"))
        assertEquals(lake, CategoryTree.of(mapOf("natural" to "water", "water" to "lake"), Geometry.AREA))
        assertEquals("key:natural", CategoryTree.of(mapOf("natural" to "tree"))!!.parentId)
        // no place to go, still a kind of thing on the map
        assertEquals("key:shop", CategoryTree.preset("shop/vacant")!!.parentId)
        assertEquals("key:highway", CategoryTree.pathOf(CategoryTree.preset("highway/cycleway")!!.id)[1].id)
        assertEquals("Alles op de kaart", CategoryTree.shelf(CategoryTree.OSM)!!.name("nl"))
        // every searchable preset with tags of its own has a node, in a category or under its key
        val missing = Presets.all.filter { it.searchable && it.tags.isNotEmpty() && it.tags.values.none { v -> v == "*" } && CategoryTree.preset(it.id) == null }
        assertEquals(emptyList<String>(), missing.map { it.id })
    }

    @Test
    fun aPlaceFindsItsDeepestNode() {
        assertEquals("preset:amenity/restaurant/pizza", CategoryTree.of(mapOf("amenity" to "restaurant", "cuisine" to "pizza"))?.id)
        assertEquals("preset:amenity/pharmacy", CategoryTree.of(mapOf("amenity" to "pharmacy", "healthcare" to "pharmacy"))?.id)
        assertEquals("cat:health", CategoryTree.pathOf(CategoryTree.of(mapOf("amenity" to "pharmacy"))!!.id)[1].id)
        assertNull(CategoryTree.of(mapOf("building" to "yes")))
        // an old building is a sight by its name (its preset, any building, has no node of its own); nameless, it stands
        // under its key with everything else on the map
        assertEquals("cat:sights", CategoryTree.of(mapOf("historic" to "building", "name" to "Huis Bartolotti"), Geometry.AREA)?.id)
        assertEquals("key:historic", CategoryTree.of(mapOf("historic" to "building"), Geometry.AREA)?.id)
        assertTrue(Kinds.listed().any { it.id == "historic/building" })
        // a key's node holds what the tree puts under it: Prinsengracht 263, of 1635, an old house of no name
        val house = mapOf("building" to "yes", "historic" to "yes", "start_date" to "1635")
        val key = CategoryTree.of(house, Geometry.AREA)!!
        assertEquals(NodeKind.KEY, key.kind)
        assertTrue(key.filter.matches(house))
    }

    @Test
    fun theWesterkerkExtractFitsTheTree() {
        var placed = 0
        for (e in Fixtures.pois("ams") + Fixtures.pois("eat")) {
            val tags = e.place.tags
            val category = Categories.of(tags) ?: continue
            val node = CategoryTree.of(tags, e.geometry)
            assertNotNull(e.place.id.toString(), node)
            // the node holds the place, and lies under the place's own category
            assertTrue("${e.place.id} ${node!!.id}", node.kind == NodeKind.CATEGORY || node.filter.matches(tags))
            assertTrue("${e.place.id} ${node.id}", CategoryTree.isUnder(node, CategoryTree.category(category.id)!!))
            if (node.kind == NodeKind.PRESET) placed++
        }
        println("Westerkerk and eat extracts: $placed places under a preset's node")
        assertTrue(placed > 500)
    }

    /**
     * Kinds that belong to each other though each stands where it is (the owner, 6 November: "Miniature golf is in fun.
     * Where is golf? Link kinds that belong to each other."): each link is a kind of the tree, the minigolf in Fun and
     * the golf courses in Sport name each other, a kind under a linked one has its links too, and none names itself, a
     * shelf, or a kind above or under it.
     */
    @Test
    fun kindsThatBelongToEachOtherNameEachOther() {
        assertTrue(Categories.links().size >= 10)
        for (id in Categories.links().flatten()) assertTrue(id, CategoryTree.byId(id)?.kind.let { it != null && it != NodeKind.SHELF })
        val minigolf = CategoryTree.preset("leisure/miniature_golf")!!
        val golf = CategoryTree.preset("leisure/golf_course")!!
        assertEquals("fun", minigolf.category.group?.id)
        assertEquals("sport", golf.category.family)
        assertEquals(listOf("preset:leisure/golf_course", "preset:leisure/disc_golf_course"), minigolf.links.map { it.id })
        assertTrue(minigolf in golf.links)
        // a water park is one of the pools: theirs are its too, the pools themselves not
        val waterPark = CategoryTree.preset("leisure/water_park")!!
        assertTrue(CategoryTree.isUnder(waterPark, CategoryTree.category("swim")!!))
        assertEquals(listOf("cat:beach", "preset:leisure/sports_centre/swimming"), waterPark.links.map { it.id })
        // a kind in two groups names both: the playground its park and its petting zoo
        assertEquals(listOf("preset:leisure/park", "preset:tourism/zoo/petting"), CategoryTree.category("playground")!!.links.map { it.id })
        var linked = 0
        for (n in CategoryTree.all()) {
            val links = n.links
            if (links.isNotEmpty()) linked++
            assertEquals(n.id, links.size, links.distinctBy { it.id }.size)
            assertTrue(n.id, links.none { it.kind == NodeKind.SHELF || CategoryTree.isUnder(it, n) || CategoryTree.isUnder(n, it) })
        }
        assertTrue(CategoryTree.shelves().all { it.links.isEmpty() })
        println("kinds that name others: $linked")
    }

    /**
     * A row goes by how many of each kind are near (the owner, 6 November, of theme parks and zoos leading Fun: "I think
     * they shouldnt"), a category's core kinds first and the specialists it names last after the rest: Fun's kinds all
     * alike; the groceries' supermarkets, then convenience stores, an organic supermarket where the supermarkets stand, the
     * frozen food shops last before the bottled water ("Supermarkets have frozen food"); a kind's own narrower kinds alike.
     * Every kind named last is one of its node's (categories.py LAST).
     */
    @Test
    fun aRowGoesByHowManyTheSpecialistsLast() {
        val fun_ = CategoryTree.shelf("fun")!!
        fun at(n: CategoryNode, id: String) = n.standing(CategoryTree.byId(id)!!)
        for (k in fun_.children + fun_.guests) assertEquals(k.id, 0, fun_.standing(k))
        val groceries = CategoryTree.category("food_shops")!!
        assertTrue(at(groceries, "preset:shop/supermarket") < at(groceries, "preset:shop/convenience"))
        assertTrue(at(groceries, "preset:shop/convenience") < 0)
        assertEquals(at(groceries, "preset:shop/supermarket"), at(groceries, "preset:shop/supermarket/organic"))
        for (k in listOf("amenity/marketplace", "shop/bakery", "shop/spices")) assertEquals(k, 0, at(groceries, "preset:$k"))
        assertTrue(at(groceries, "preset:shop/frozen_food") in 1 until at(groceries, "preset:shop/water"))
        assertTrue(at(CategoryTree.shelf("service")!!, "cat:street_furniture") > 0)
        assertEquals(0, at(CategoryTree.preset("shop/supermarket")!!, "preset:shop/supermarket/organic"))
        for (n in CategoryTree.all()) for (id in n.category.last) {
            if (n.kind != NodeKind.SHELF && n.kind != NodeKind.CATEGORY) continue
            val k = CategoryTree.byId(id)
            assertNotNull("${n.id} $id", k)
            assertTrue("${n.id} $id", k!!.id != n.id && CategoryTree.isUnder(k, n))
            assertTrue("${n.id} $id", n.standing(k) > 0)
        }
    }

    /**
     * A narrower kind under its broader goes by what it adds (the owner, 6 November: "Show "Italian" instead of "Italian
     * Restaurant"?"): a cuisine by its own name in each language, the rest with the broader's word taken out; not so
     * under Eat, which holds a pizza restaurant and a pizza takeaway both; whole where it is not under the broader.
     */
    @Test
    fun aNarrowerKindGoesByWhatItAdds() {
        fun p(id: String) = CategoryTree.preset(id)!!
        val langs = listOf("en", "nl", "de", "fr")
        assertEquals(listOf("Italian", "Italiaans", "Italienisch", "Italienne"), langs.map { p("amenity/restaurant/italian").nameUnder(p("amenity/restaurant"), it) })
        assertEquals(listOf("Organic", "Biologische", "Bio", "Bio"), langs.map { p("shop/supermarket/organic").nameUnder(p("shop/supermarket"), it) })
        assertEquals(listOf("Second-Hand", "Tweedehands", "Second-Hand", "D'occasion"), langs.map { p("shop/clothes/second_hand").nameUnder(p("shop/clothes"), it) })
        assertEquals("Pizza", p("amenity/fast_food/pizza").nameUnder(p("amenity/fast_food")))
        val eat = CategoryTree.category("restaurants")!!
        assertEquals("Pizza Restaurant", p("amenity/restaurant/pizza").nameUnder(eat))
        assertEquals("Pizza Fast Food", p("amenity/fast_food/pizza").nameUnder(eat))
        // not under it, or itself, or under a broader that names none of it: its whole name
        assertEquals("Italian Restaurant", p("amenity/restaurant/italian").nameUnder(p("shop/supermarket")))
        assertEquals("Supermarket", p("shop/supermarket").nameUnder(p("shop/supermarket")))
        assertEquals("Organic Supermarket", p("shop/supermarket/organic").nameUnder(CategoryTree.shelf("groceries")!!))
    }

    /** A kind stands under its nearest broader one, past an id between that is no kind: the flush toilets under the toilets. */
    @Test
    fun aKindStandsUnderItsNearestBroaderOne() {
        for (id in listOf("amenity/toilets/disposal/flush", "amenity/toilets/disposal/pitlatrine")) {
            assertEquals(id, "amenity/toilets", Presets.byId(id)!!.broader!!.id)
            assertEquals(id, "preset:amenity/toilets", CategoryTree.preset(id)!!.parentId)
        }
        assertEquals("shop/supermarket", Presets.byId("shop/supermarket/organic")!!.broader!!.id)
    }
}
