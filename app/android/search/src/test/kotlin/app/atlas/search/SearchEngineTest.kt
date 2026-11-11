package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.places.OpenState
import app.atlas.places.OpeningHours
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class SearchEngineTest {
    private val engine by lazy { SearchEngine(Fixtures.store) }
    private val ctx = SearchContext(location = Fixtures.westerkerk, lang = "en", now = Fixtures.saturday)

    private fun search(text: String, c: SearchContext = ctx) = runBlocking { engine.search(text, c) }

    private val westerkerk = OsmId(OsmType.WAY, 99205257)
    private val benu = OsmId(OsmType.NODE, 2721828852)

    @Test
    fun pharmaciesInThreeLanguages() {
        for ((text, lang) in listOf("pharmacy" to "en", "apotheek" to "nl", "Apotheke" to "de", "apotheek" to "en", "pharm" to "en")) {
            val r = search(text, ctx.copy(lang = lang))
            val first = r.places.first()
            assertEquals("$text/$lang", benu, first.hit.place.id)
            assertEquals(MatchKind.KIND, first.match)
            val kind = r.items.filterIsInstance<SearchItem.Kind>().first()
            assertEquals(1, kind.count)
            assertEquals("temaki-pharmacy", kind.symbol)
            assertTrue(first.hit.distance!! in 150.0..300.0)
        }
    }

    @Test
    fun aKindIsFoundByWhatItSellsAndByItsOwnName() {
        // "Clothes" is what Atlas calls the kind (places KindNames); "clothing store" still finds it, and says it so
        for ((text, lang, name) in listOf(
            Triple("clothes", "en", "Clothes"), Triple("clothing store", "en", "Clothes"),
            Triple("kleding", "nl", "Kleding"), Triple("kledingzaak", "nl", "Kleding"), Triple("gift shop", "en", "Gifts"),
            Triple("gifts", "en", "Gifts"), Triple("cadeaus", "nl", "Cadeaus"),
        )) {
            val r = search(text, ctx.copy(lang = lang))
            assertTrue("$text/$lang: ${r.items.filterIsInstance<SearchItem.Kind>().map { it.label }}", r.items.filterIsInstance<SearchItem.Kind>().any { it.label == name })
            assertTrue("$text/$lang", r.places.take(5).any { it.hit.tags["shop"] in setOf("clothes", "gift") })
        }
    }

    @Test
    fun typosInNamesAndBrands() {
        val kerk = search("westerkrk")
        assertEquals(westerkerk, kerk.places.first().hit.place.id)
        assertEquals(MatchKind.NAME, kerk.places.first().match)
        val ah = search("albert hein")
        val brand = ah.items.filterIsInstance<SearchItem.BrandResult>().single()
        assertEquals(2, brand.count)
        assertEquals("Albert Heijn", brand.brand.brand)
        assertEquals("logo-albertheijn", brand.symbol)
        // the nearest branch first: Westermarkt 21, round the corner
        assertEquals(OsmId(OsmType.NODE, 34044750), ah.places.first().hit.place.id)
        assertEquals(2, ah.places.count { it.hit.tags["brand"] == "Albert Heijn" })
        val exact = search("Albert Heijn")
        assertEquals(OsmId(OsmType.NODE, 34044750), exact.places.first().hit.place.id)
        // its very name asks for the chain, which leads; a typo or a part of it lists it among the rest
        assertTrue((exact.items.first() as SearchItem.BrandResult).named)
        assertFalse(brand.named)
        assertTrue(search("appie").items.first().let { it is SearchItem.BrandResult && it.named && it.brand.brand == "Albert Heijn" })
        assertTrue(search("albert").items.none { it is SearchItem.BrandResult && it.named })
    }

    @Test
    fun coordinatesAndPlusCodes() {
        val c = search("52.3745, 4.8838")
        val coord = c.items.first() as SearchItem.Coordinate
        assertEquals(52.3745, coord.location.lat, 1e-9)
        assertEquals(4.8838, coord.location.lon, 1e-9)
        assertEquals("9F469VFM+RG", coord.plusCode)
        assertTrue(c.places.isNotEmpty() && c.places.all { it.match == MatchKind.NEARBY })
        // the brief's style of code: eight digits and the plus, the centre of its cell
        val generic = search("9C4WRV7M+").items.first() as SearchItem.Coordinate
        assertEquals(52.81375, generic.location.lat, 1e-6)
        assertEquals(-1.11625, generic.location.lon, 1e-6)
        val full = search("9F469VFM+RG").items.first() as SearchItem.Coordinate
        assertTrue(Geo.distance(full.location, Fixtures.westerkerk) < 15)
        // a short code is recovered near the searcher
        val short = search("9VFM+RG").items.first() as SearchItem.Coordinate
        assertTrue(Geo.distance(short.location, Fixtures.westerkerk) < 15)
    }

    @Test
    fun osmIdsAndLinks() {
        val box = search("n26973327").items.single() as SearchItem.Element
        assertEquals("post_box", box.hit.tags["amenity"])
        val kerk = search("https://www.openstreetmap.org/way/99205257").items.single() as SearchItem.Element
        assertEquals(westerkerk, kerk.hit.place.id)
        assertEquals(OsmId(OsmType.RELATION, 5), SearchEngine.osmRef("relation/5"))
        assertEquals(null, SearchEngine.osmRef("n26973327 cafe"))
        // a link whose number is too large for an id is no id (it threw, and the search box said so)
        assertEquals(null, SearchEngine.osmRef("https://www.openstreetmap.org/node/99999999999999999999"))
    }

    @Test
    fun queriesInWords() {
        val near = search("cafe near bicycle rental 200m")
        val q = near.items.first() as SearchItem.Structured
        assertEquals(listOf("cafe", "200m", "bicycle rental"), q.parsed.chips.map { it.label })
        assertTrue(near.places.size >= 5)
        assertTrue(near.places.all { it.hit.tags["amenity"] == "cafe" && it.hit.near.single().distance <= 200 })

        val vegan = search("vegan restaurant open now")
        assertTrue(vegan.places.size >= 5)
        assertTrue(vegan.places.all { it.hit.open?.isOpen == true && it.hit.tags["diet:vegan"] in setOf("yes", "only") })

        val toilets = search("toilets wheelchair")
        assertEquals(OsmId(OsmType.NODE, 5831919166), toilets.places.first().hit.place.id)

        // the A-near-B-near-C chain of the Query board, with the board's ↳ rows
        val chain = search("café 200 m from a bike dock and 400 m from a park")
        val s = chain.items.first() as SearchItem.Structured
        assertEquals(listOf(0, 1, 1, 2, 2), s.parsed.chips.map { it.row })
        assertTrue(chain.places.size >= 4)
        for (p in chain.places) {
            assertEquals(listOf("bicycle_rental", "park"), p.hit.near.map { it.record.tags["amenity"] ?: it.record.tags["leisure"] })
        }
    }

    @Test
    fun dutchPhrases() {
        val nl = ctx.copy(lang = "nl")
        val terras = search("café met terras bij een fietsverhuur", nl)
        assertTrue(terras.places.isNotEmpty())
        assertTrue(terras.places.all { it.hit.tags["outdoor_seating"] == "yes" && it.hit.near.isNotEmpty() })
        val open = search("supermarkt nu open", nl)
        assertTrue(open.places.isNotEmpty())
        assertTrue(open.places.all { it.hit.tags["shop"] == "supermarket" && it.hit.open?.isOpen == true })
        val buurt = search("vegetarisch restaurant in de buurt", nl)
        assertTrue(buurt.places.isNotEmpty() && buurt.places.all { it.hit.distance!! <= 1000 })
        val binnen = search("restaurant binnen 300 m van een park", nl)
        assertTrue(binnen.places.isNotEmpty() && binnen.places.all { it.hit.near.single().distance <= 300 })
    }

    @Test
    fun addressesAndStreets() {
        val addr = search("prinsengracht 263")
        val first = addr.places.first()
        assertEquals(MatchKind.ADDRESS, first.match)
        assertEquals("263", first.hit.tags["addr:housenumber"])
        assertEquals("Prinsengracht", first.hit.tags["addr:street"])
        val street = search("raadhuisstraat")
        val s = street.places.first()
        assertEquals(MatchKind.STREET, s.match)
        assertEquals("Raadhuisstraat", s.title)
        assertEquals(1, street.places.count { it.match == MatchKind.STREET })
        // a stop's platforms and poles are one line
        val stop = search("westermarkt")
        assertTrue(stop.places.count { it.title == "Westermarkt" } <= 4)
    }

    @Test
    fun powerSyntaxThroughTheBox() {
        val r = search("amenity=cafe and outdoor_seating=yes")
        val q = r.items.first() as SearchItem.Structured
        assertEquals(QuerySyntax.POWER, q.parsed.syntax)
        assertTrue(r.places.all { it.hit.tags["outdoor_seating"] == "yes" })
        val ql = OverpassCompiler.compile(q.parsed.query, BBox.around(Fixtures.westerkerk, 500.0))
        assertTrue(ql.contains("nwr[\"amenity\"=\"cafe\"][\"outdoor_seating\"=\"yes\"]"))
    }

    @Test
    fun refinementChipsForAKind() {
        // the Search board: "pharm" offers Open now, On duty tonight, Step-free; here the toilets, judged from the map
        val r = search("toilet")
        val chips = r.refinements
        assertTrue(chips.isNotEmpty() && chips.size <= 4)
        assertEquals("Open now", chips.first().chip.label)
        val toilets = Fixtures.store.byTag("amenity", "toilets").filter { it.distanceTo(Fixtures.westerkerk) <= 3_000 }
        val stepFree = chips.single { it.chip.label == "Step-free" }
        assertEquals(toilets.count { it.tags["wheelchair"] in setOf("yes", "designated") || it.tags["toilets:wheelchair"] == "yes" }, stepFree.count)
        // each chip's query is the kind narrowed; running it finds as many as the chip says (within the same area)
        val ran = OfflineEvaluator(Fixtures.store).run(Query.and(stepFree.query, Query.Within(null, 3_000.0)), Fixtures.context().copy(limit = 500))
        assertEquals(stepFree.count, ran.total)
        // every chip narrows: some, not all
        assertTrue(chips.all { it.count in 1 until toilets.size })
        // in Dutch
        assertEquals("Nu open", search("toilet", ctx.copy(lang = "nl")).refinements.first().chip.label)
        // a single pharmacy leaves nothing to narrow
        assertTrue(search("pharmacy").refinements.isEmpty())
    }

    @Test
    fun oneLinePerKindAndPerFeature() {
        // "Restroom" (the preset) and "Toilets" (the category) are the same places: one line
        assertEquals(1, search("toilet").items.count { it is SearchItem.Kind })
        // a canal mapped in pieces is one line, as a street is
        val canal = search("prinsengr").places.filter { it.title == "Prinsengracht" && it.match == MatchKind.NAME }
        assertEquals(1, canal.size)
        assertTrue(canal.single().parts > 1)
        // a quarter mapped as a node and as an outline too
        assertEquals(1, search("jordaan").places.count { it.title == "Jordaan" })
        // a short word with a typo does not reach for every name that begins nearly like it
        assertTrue(search("hema").places.none { it.title == "Headline" })
    }

    @Test
    fun hitsSayWhyTheyMatched() {
        val r = search("cafe with wifi")
        for (p in r.places) {
            assertTrue(p.hit.why.toString(), "amenity=cafe" in p.hit.why)
            // wifi is wanted: a café says it has it, or is shown unsure of it
            assertTrue(p.hit.why.toString(), p.hit.why.any { it.startsWith("internet_access") } || p.hit.unknown.any { it.startsWith("internet_access") })
        }
        val open = search("bakery open now")
        assertTrue(open.places.all { "open" in it.hit.why && "shop=bakery" in it.hit.why })
    }

    @Test
    fun recentSearchesAndAnEmptyBox() = runBlocking {
        val recents = RecentSearches { text, limit -> listOf(RecentSearch("Café Molen"), RecentSearch("9F4M+GQ"), RecentSearch("Westerkerk")).filter { text.isEmpty() || it.text.contains(text, true) }.take(limit) }
        val e = SearchEngine(Fixtures.store, recents = recents)
        val empty = e.search("", ctx)
        assertEquals(3, empty.items.size)
        assertTrue(empty.items.all { it is SearchItem.Recent })
        val kerk = e.search("wester", ctx)
        assertTrue(kerk.items.any { it is SearchItem.Recent && it.recent.text == "Westerkerk" })
    }

    @Test
    fun aNewSearchCancelsTheOneRunning() = runBlocking {
        val slow = object : PlaceSource by Fixtures.store {
            override fun text(text: String, near: LatLon?, box: BBox?, limit: Int): List<PlaceRecord> {
                Thread.sleep(400)
                return Fixtures.store.text(text, near, box, limit)
            }
        }
        val e = SearchEngine(slow)
        val first = async { runCatching { e.search("westerk", ctx) } }
        delay(100)
        val second = e.search("westerkerk", ctx)
        val firstResult = first.await()
        assertTrue(firstResult.exceptionOrNull() is CancellationException)
        assertNotNull(second.places.firstOrNull { it.hit.place.id == westerkerk })
    }

    @Test
    fun aShortRefThatIsNotAnElementHereIsSearchedAsAName() {
        // "N201" is a Dutch provincial road and "N47" a night bus, not node 201 or 47
        PlaceStore.inMemory().use { s ->
            s.importPlaces(listOf(
                app.atlas.model.Place(OsmId(OsmType.WAY, 7), LatLon(52.25, 4.80), mapOf("highway" to "primary", "ref" to "N201", "name" to "Provincialeweg"), BBox(4.79, 52.24, 4.81, 52.26)),
            ))
            val r = runBlocking { SearchEngine(s).search("N201", ctx) }
            assertEquals(OsmId(OsmType.WAY, 7), r.places.first().hit.place.id)
            // an id that is there is still the first thing found
            val e = runBlocking { SearchEngine(s).search("w7", ctx) }
            assertEquals(OsmId(OsmType.WAY, 7), (e.items.first() as SearchItem.Element).hit.place.id)
        }
        // links and "node/…" forms are ids only
        assertTrue(search("node/26973327").items.single() is SearchItem.Element)
    }

    @Test
    fun halfAQueryWhileTypingShowsTheKindItNames() {
        // on the way to "café near a bike dock": no name fits "café near", the cafés do
        val r = search("café near")
        val s = r.items.first() as SearchItem.Structured
        assertTrue(r.places.isNotEmpty() && r.places.all { it.hit.tags["amenity"] == "cafe" })
        assertEquals(ChipKind.KIND, s.parsed.chips.first().kind)
        // a name stays a name: the church, closed on a Saturday afternoon, before the "Westerkerk WC" that is open
        assertEquals(westerkerk, search("westerkerk").places.first().hit.place.id)
        assertEquals(westerkerk, search("Westerkerk").places.first().hit.place.id)
    }

    @Test
    fun questionsGetAnswersNotOnlyPlaces() {
        // "how many": the count first, all of them sure, of one kind, in the searcher's town; the places after
        val bins = search("how many trashcans in this town")
        val count = bins.items.first() as SearchItem.Count
        assertTrue(count.answer.total > 0)
        assertEquals(count.answer.total, count.answer.sure)
        assertEquals(null, count.area)
        assertEquals("Trash Can", count.answer.kinds.single().label)
        assertEquals(count.answer.total, (bins.items[1] as SearchItem.Structured).results.single().total)
        // asked in Dutch, the same count; asked with no area, the town all the same
        assertEquals(count.answer.total, (search("hoeveel prullenbakken in deze stad", ctx.copy(lang = "nl")).items.first() as SearchItem.Count).answer.total)
        assertEquals(count.answer.total, (search("how many trashcans").items.first() as SearchItem.Count).answer.total)
        // a want splits the count into the sure ones and the rest
        val loos = search("how many cafes with toilets").items.first() as SearchItem.Count
        assertTrue(loos.answer.sure in 1 until loos.answer.total)
        // asked for the open ones, a place open only by guessed hours counts, as open perhaps, not for sure
        val open = search("how many restaurants open now").items.first() as SearchItem.Count
        println("restaurants open now: ${open.answer.total}, ${open.answer.sure} of them for sure")
        assertTrue(open.answer.sure in 1 until open.answer.total)
        // "opening times": the week side by side, today hour by hour
        val hours = search("overview of bakery opening times").items.first() as SearchItem.Hours
        assertEquals(7, hours.answer.days.size)
        assertEquals(24, hours.answer.byHour.size)
        assertTrue(hours.answer.known > 0 && hours.answer.byHour[3] == 0 && hours.answer.byHour[12] > 0)
        val sat = hours.answer.days[DayOfWeek.SATURDAY.ordinal]
        assertTrue(sat.earliest!! <= sat.opensMiddle!! && sat.closesMiddle!! <= sat.latest!!)
    }

    @Test
    fun nothingAsAskedLoosensAndSaysSo() {
        // no bakery is open at 23:00: the ones that close latest, first
        val late = search("bakery that opens late")
        val l = (late.items.first() as SearchItem.Structured).loosened.single()
        assertTrue(l.from is Query.OpenAt && l.to == null)
        assertEquals(SortBy.CLOSES_LATEST, l.by)
        val closes = late.places.mapNotNull { p -> p.hit.tags["opening_hours"]?.let { closingSaturday(it) } }
        assertTrue(closes.size > 3)
        assertEquals(closes.max(), closes.first())
        // no Starbucks in a mall: the Starbucks, with what they say about toilets
        val mall = search("starbucks with toilets near inside a mall")
        assertTrue((mall.items.first() as SearchItem.Structured).loosened.single().from is Query.Inside)
        assertTrue(mall.places.isNotEmpty() && mall.places.all { it.hit.tags["brand"] == "Starbucks" })
        // at half past three in the morning: the supermarkets by when they open
        val night = search("supermarket open now", ctx.copy(now = Fixtures.saturday.withHour(3)))
        assertEquals(SortBy.OPENS_NEXT, (night.items.first() as SearchItem.Structured).loosened.single().by)
        assertTrue(night.places.isNotEmpty())
        // what is found as asked is not loosened
        assertTrue((search("bakery open now").items.first() as SearchItem.Structured).loosened.isEmpty())
    }

    @Test
    fun aWayAskedForFindsItsEnds() {
        val r = search("from Westerkerk to Anne Frank Huis")
        val way = r.items.first() as SearchItem.Route
        assertEquals(westerkerk, way.from!!.place!!.id)
        assertTrue(way.to.name!!.startsWith("Anne Frank"))
        // the nearest of a kind is an end too, and the end's places follow
        val pharmacy = search("route to the nearest pharmacy")
        assertEquals(benu, (pharmacy.items.first() as SearchItem.Route).to.place!!.id)
        assertEquals(benu, pharmacy.places.first().hit.place.id)
        // an end that leads nowhere is no way
        assertTrue(search("walk to qqqzzz").items.none { it is SearchItem.Route })
    }

    private fun closingSaturday(raw: String): Int? = OpeningHours.parseOrNull(raw)
        ?.weekTable(Fixtures.saturday.toLocalDate())?.get(DayOfWeek.SATURDAY.ordinal)?.filter { it.state == OpenState.OPEN }?.maxOfOrNull { it.end }
}
