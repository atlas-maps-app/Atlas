package app.atlas.search

import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.places.TagFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class QueryParserTest {
    private fun parse(text: String, lang: String = "en") = QueryParser.parse(text, lang, Fixtures.saturday)

    /** Every tag filter in the query, as text. */
    private fun filters(q: Query): List<String> {
        val out = ArrayList<String>()
        q.walk { if (it is Query.Tags) out += it.filter.toText() }
        return out
    }

    private inline fun <reified T : Query> find(q: Query): List<T> {
        val out = ArrayList<T>()
        q.walk { if (it is T) out += it }
        return out
    }

    @Test
    fun boardExamples() {
        val plan = parse("quiet café with an outlet, then dinner")
        assertEquals(2, plan.steps.size)
        assertTrue(plan.isPlan)
        assertTrue(filters(plan.steps[0]).contains("amenity=cafe"))
        assertEquals(2, find<Query.Prefer>(plan.steps[0]).size) // quiet and outlet rank, they do not filter
        assertEquals(listOf("amenity=restaurant"), filters(plan.steps[1]))
        assertEquals(listOf("café", "quiet", "outlet", "dinner"), plan.chips.map { it.label })
        assertEquals(listOf(0, 0, 0, 1), plan.chips.map { it.step })

        val dock = parse("café 200 m from a bike dock")
        val near = dock.query as Query.Near
        assertEquals(200.0, near.maxDistance, 0.0)
        assertEquals(listOf("amenity=bicycle_rental"), filters(near.other))
        assertEquals(listOf(ChipKind.KIND, ChipKind.DISTANCE, ChipKind.KIND), dock.chips.map { it.kind })
        assertEquals(listOf(0, 1, 1), dock.chips.map { it.row })

        val toilet = parse("step-free toilet, open now")
        assertEquals(1, toilet.steps.size)
        assertTrue(filters(toilet.query).any { "wheelchair" in it })
        assertEquals(OpenTime.Now, find<Query.OpenAt>(toilet.query).single().time)
        assertTrue(toilet.structured)
    }

    @Test
    fun queryBoardChain() {
        val p = parse("wifi café near a bike dock & a park")
        val outer = p.query as Query.Near
        val inner = outer.subject as Query.Near
        assertEquals(listOf("leisure=park"), filters(outer.other))
        assertEquals(listOf("amenity=bicycle_rental"), filters(inner.other))
        assertTrue(filters(inner.subject).any { it.startsWith("internet_access") })
        // ☕ café + wifi, then ↳ 200 m 🚲 dock, ↳ 200 m 🌲 park
        assertEquals(listOf(0, 0, 1, 1, 2, 2), p.chips.map { it.row })
        assertEquals("maki-cafe", p.chips[0].symbol)
        assertEquals("ui-wifi", p.chips[1].symbol)
        assertEquals("maki-park", p.chips.last().symbol)
    }

    @Test
    fun distancesAfterTheNearThingAndWithin() {
        val p = parse("cafe near bicycle rental 200m")
        assertEquals(200.0, (p.query as Query.Near).maxDistance, 0.0)
        val within = parse("cafe within 500 m")
        assertEquals(500.0, find<Query.Within>(within.query).single().radius, 0.0)
        val walk = parse("restaurant 5 min walk")
        assertEquals(400.0, find<Query.Within>(walk.query).single().radius, 0.0)
        val bike = parse("supermarket within 10 minutes by bike")
        assertEquals(2500.0, find<Query.Within>(bike.query).single().radius, 0.0)
        val far = parse("café not near a bar")
        assertTrue(far.query is Query.NotNear)
    }

    @Test
    fun propertiesAndOpeningTimes() {
        val vegan = parse("vegan restaurant open now")
        assertEquals(listOf("amenity=restaurant", "diet:vegan~^(yes|only)$"), filters(vegan.query))
        assertEquals(OpenTime.Now, find<Query.OpenAt>(vegan.query).single().time)
        assertTrue(filters(parse("toilets wheelchair").query).any { it.startsWith("wheelchair") })
        assertEquals(OpenTime.OnDay(DayOfWeek.SUNDAY), find<Query.OpenAt>(parse("supermarket open sunday").query).single().time)
        assertEquals(OpenTime.Today(LocalTime.of(23, 0)), find<Query.OpenAt>(parse("bar open at 23:00").query).single().time)
        assertEquals(OpenTime.Today(LocalTime.of(22, 0)), find<Query.OpenAt>(parse("restaurant open 10pm").query).single().time)
        assertEquals(OpenTime.Tonight, find<Query.OpenAt>(parse("pizza tonight").query).single().time)
        // tomorrow is Sunday when today is Saturday
        assertEquals(OpenTime.OnDay(DayOfWeek.SUNDAY), find<Query.OpenAt>(parse("bakery open tomorrow").query).single().time)
        val pizza = parse("pizza")
        assertTrue(filters(pizza.query).contains("cuisine=pizza"))
        // without: a wish that it says no, so those that do not say stay, unsure
        val notTerrace = parse("café without terrace")
        assertEquals(TagFilter.parse("outdoor_seating=no"), (find<Query.Want>(notTerrace.query).single().part as Query.Tags).filter)
        val sorted = parse("3 bakeries nearest")
        assertTrue(sorted.query is Query.Limit)
        assertEquals(SortBy.DISTANCE, ((sorted.query as Query.Limit).query as Query.Sort).by)
    }

    @Test
    fun dutch() {
        assertEquals(listOf("amenity=pharmacy"), filters(parse("apotheek", "nl").query))
        val open = parse("apotheek nu open", "nl")
        assertEquals("nl", open.language)
        assertEquals(OpenTime.Now, find<Query.OpenAt>(open.query).single().time)
        val p = parse("café met wifi en terras bij een fietsverhuur", "nl")
        val near = p.query as Query.Near
        assertEquals(listOf("amenity=bicycle_rental"), filters(near.other))
        assertTrue(filters(near.subject).containsAll(listOf("amenity=cafe", "outdoor_seating=yes")))
        val within = parse("restaurant binnen 200 m van een park", "nl").query as Query.Near
        assertEquals(200.0, within.maxDistance, 0.0)
        val buurt = parse("vegetarisch restaurant in de buurt", "nl")
        assertEquals(1000.0, find<Query.Within>(buurt.query).single().radius, 0.0)
        assertTrue(buurt.unknown.isEmpty())
        val toilet = parse("rolstoeltoegankelijk toilet", "nl")
        assertTrue(filters(toilet.query).any { it.startsWith("wheelchair") })
    }

    @Test
    fun germanAndFrench() {
        assertEquals(listOf("amenity=pharmacy"), filters(parse("Apotheke", "de").query))
        val de = parse("Restaurant mit Terrasse in der Nähe", "de")
        assertEquals("de", de.language)
        assertTrue(filters(de.query).contains("outdoor_seating=yes"))
        assertEquals(1, find<Query.Within>(de.query).size)
        val nahe = parse("Café mit WLAN nahe Fahrradverleih", "de")
        assertTrue(nahe.query is Query.Near)
        val fr = parse("café avec wifi près d'une location de vélos", "fr")
        assertEquals("fr", fr.language)
        assertTrue(fr.query is Query.Near)
        val ouvert = parse("pharmacie ouverte", "fr")
        assertEquals(OpenTime.Now, find<Query.OpenAt>(ouvert.query).single().time)
    }

    @Test
    fun askBoardSentence() {
        val p = parse("A quiet café with an outlet, charge the car nearby, then the 20:00 film with cheap parking and a late ramen.")
        assertEquals(4, p.steps.size)
        assertTrue(filters(p.steps[0]).contains("amenity=cafe"))
        // the outlet is a want: few cafés map their sockets
        assertTrue(find<Query.Want>(p.steps[0]).any { w -> filters(w.part).any { it.startsWith("power_supply") } })
        assertTrue(filters(p.steps[1]).contains("amenity=charging_station"))
        assertEquals(OpenTime.Today(LocalTime.of(20, 0)), find<Query.OpenAt>(p.steps[2]).single().time)
        assertTrue(p.steps[2] is Query.Near)
        assertTrue(filters(p.steps[3]).contains("cuisine=ramen"))
        assertEquals(OpenTime.Late, find<Query.OpenAt>(p.steps[3]).single().time)
        assertTrue(p.unknown.isEmpty())
    }

    @Test
    fun chipsKnowWhereTheirWordsAre() {
        // the Ask board highlights the words in place: "A ▢quiet▢ ▢café▢ with an ▢outlet▢, …"
        val text = "A quiet café with an outlet, charge the car nearby, then the 20:00 film with cheap parking and a late ramen."
        val p = parse(text)
        val said = p.chips.filter { it.span != null }.associate { it.label to text.substring(it.span!!.first, it.span!!.last + 1) }
        assertEquals("café", said["café"])
        assertEquals("quiet", said["quiet"])
        assertEquals("outlet", said["outlet"])
        assertEquals("charge the car", said["charge the car"])
        assertEquals("nearby", said["nearby"])
        assertEquals("20:00", said["20:00"])
        assertEquals("film", said["film"])
        assertEquals("cheap", said["cheap"])
        assertEquals("late", said["late"])
        assertEquals("ramen", said["ramen"])
        // spans keep the user's case and accents; labels are lower case
        val nl = parse("Apotheek nu open in de Jordaan", "nl")
        val area = nl.chips.single { it.kind == ChipKind.AREA }
        assertEquals("Jordaan", area.label)
        assertEquals("Jordaan", nl.text.substring(area.start, area.end))
        val kind = nl.chips.first { it.kind == ChipKind.KIND }
        assertEquals("Apotheek", nl.text.substring(kind.start, kind.end))
        val open = nl.chips.single { it.kind == ChipKind.OPEN }
        assertEquals("nu open", nl.text.substring(open.start, open.end))
        // a distance said before the kind, and one made up (the default 200 m) that has no words
        val dock = parse("café 200 m from a bike dock")
        val d = dock.chips.single { it.kind == ChipKind.DISTANCE }
        assertEquals("200 m", dock.text.substring(d.start, d.end))
        val plain = parse("café near a park").chips.single { it.kind == ChipKind.DISTANCE }
        assertEquals(null, plain.span)
        // power syntax chips stand for filters, not for words in place
        assertTrue(parse("amenity=toilets wheelchair=yes open now").chips.all { it.span == null })
    }

    @Test
    fun powerSyntaxPropertiesReadAsProperties() {
        val p = parse("amenity=toilets wheelchair=yes")
        assertEquals(listOf(ChipKind.KIND, ChipKind.PROPERTY), p.chips.map { it.kind })
        assertEquals("ui-wheelchair", p.chips[1].symbol)
        val c = parse("amenity=cafe and outdoor_seating=yes")
        assertEquals(listOf(ChipKind.KIND, ChipKind.PROPERTY), c.chips.map { it.kind })
    }

    @Test
    fun anInstantReadsOnLocalClocks() {
        val t = OpenTime.at(java.time.Instant.parse("2026-10-10T20:00:00Z"), Fixtures.amsterdam)
        assertEquals(OpenTime.At(java.time.LocalDateTime.of(2026, 10, 10, 22, 0)), t)
    }

    @Test
    fun brandsAreasAndUnknownWords() {
        val ah = parse("albert heijn")
        assertTrue(ah.query is Query.Brand)
        assertEquals("logo-albertheijn", ah.chips.single().symbol)
        val inAms = parse("pizza in Amsterdam")
        assertEquals("Amsterdam", find<Query.InArea>(inAms.query).single().name)
        val jordaan = parse("bakery in de Jordaan", "nl")
        assertEquals("Jordaan", find<Query.InArea>(jordaan.query).single().name)
        val name = parse("westerkerk")
        assertTrue(name.steps.isEmpty())
        assertEquals(listOf("westerkerk"), name.unknown)
        assertEquals(0.0, name.confidence, 0.0)
        assertFalse(name.structured)
        // two kinds side by side could be a name ("Café Molen"): read, but not surely a query
        val half = parse("café molen")
        assertFalse(half.structured)
        // a word after a kind may be where it is ("boulangerie amsterdam"); read again without, when no such place is known
        assertEquals("zwqx", parse("café zwqx").guessedArea)
        val unknownWord = QueryParser.parse("café zwqx", "en", guessAreas = false)
        assertEquals(listOf("zwqx"), unknownWord.unknown)
        assertTrue(unknownWord.confidence < 0.6)
    }

    @Test
    fun powerSyntax() {
        val a = parse("amenity=cafe and outdoor_seating=yes")
        assertEquals(QuerySyntax.POWER, a.syntax)
        assertEquals(Query.Tags(TagFilter.parse("amenity=cafe and outdoor_seating=yes")), a.query)
        assertEquals(listOf("maki-cafe", "ui-sun"), a.chips.map { it.symbol })
        assertEquals(Query.Tags(TagFilter.Matches("cuisine", "pizza")), parse("cuisine~pizza").query)
        val near = parse("shop=bicycle near amenity=bicycle_rental 200m").query as Query.Near
        assertEquals(200.0, near.maxDistance, 0.0)
        assertEquals(Query.tags("amenity=bicycle_rental"), near.other)
        val toilets = parse("amenity=toilets wheelchair=yes open now").query
        assertEquals(OpenTime.Now, find<Query.OpenAt>(toilets).single().time)
        val socket = parse("\"socket:type2_combo:output\">=150kW").query as Query.Tags
        assertTrue(socket.filter is TagFilter.Compare)
        assertEquals(2000.0, find<Query.Within>(parse("amenity=charging_station within 2km").query).single().radius, 0.0)
        val of = find<Query.Within>(parse("amenity=cafe within 300 m of 52.3745, 4.8838").query).single()
        assertNotNull(of.center)
        assertEquals("Amsterdam", find<Query.InArea>(parse("amenity=cafe in Amsterdam").query).single().name)
        assertEquals(1000.0, find<Query.Within>(parse("amenity=cafe around me").query).single().radius, 0.0)
        assertEquals(OsmType.NODE, find<Query.Type>(parse("type:node amenity=bench").query).single().type)
        assertEquals(OsmId(OsmType.NODE, 123), (parse("id:n123").query as Query.Id).id)
        assertEquals(1, find<Query.Newer>(parse("amenity=cafe newer:2026-01-01").query).size)
        val chain = parse("amenity=cafe near park 150m not near amenity=bar 50m")
        val notNear = chain.query as Query.NotNear
        assertEquals(50.0, notNear.minDistance, 0.0)
        val park = notNear.subject as Query.Near
        assertEquals(150.0, park.maxDistance, 0.0)
        assertEquals(Query.tags("leisure=park"), park.other)
        val limited = parse("amenity=cafe sort:distance limit:5").query
        assertEquals(5, (limited as Query.Limit).count)
    }

    @Test
    fun anIdTooLargeForOpenStreetMapIsNoId() {
        // OsmId.parse threw on it, which ended the Query screen as the digits were typed
        assertTrue(find<Query.Id>(parse("id:n99999999999999999999").query).isEmpty())
    }

    @Test
    fun roundTheClockIsNotMidnight() {
        for ((text, lang) in listOf("pharmacy 24h" to "en", "apotheek 24 uur" to "nl", "pharmacie 24h/24" to "fr", "pharmacie 24 heures" to "fr", "pharmacy 24/7" to "en")) {
            val p = parse(text, lang)
            // round the clock: "24/7", or every day from midnight to midnight
            assertTrue(text, filters(p.query).any { it.startsWith("opening_hours~") && "24/7" in it })
            assertTrue(text, find<Query.OpenAt>(p.query).isEmpty())
            assertTrue(text, p.unknown.isEmpty())
        }
        // a time is still a time
        assertEquals(OpenTime.Today(LocalTime.of(22, 0)), find<Query.OpenAt>(parse("bar open 22 uur", "nl").query).single().time)
        assertEquals(OpenTime.Today(LocalTime.of(0, 0)), find<Query.OpenAt>(parse("bar open at 24:00").query).single().time)
    }

    @Test
    fun tomorrowWithoutAClockIsReadOnThePhonesClock() {
        val tomorrow = java.time.ZonedDateTime.now().dayOfWeek.plus(1)
        val p = QueryParser.parse("bakery open tomorrow")
        val open = find<Query.OpenAt>(p.query).single().time
        // a run straddling midnight may see the next day
        assertTrue("$open", open == OpenTime.OnDay(tomorrow) || open == OpenTime.OnDay(tomorrow.plus(1)))
    }

    @Test
    fun somethingToDoThereIsAnIntent() {
        // a lake to swim: the lakes, wanting bathing allowed (likely with a bathing place on the shore)
        val lake = QueryParser.parse("lake to swim")
        assertEquals("swim", lake.intent)
        val want = (lake.query as Query.And).parts.filterIsInstance<Query.Want>().single()
        assertEquals(TagFilter.parse("swimming=yes or bathing=yes"), (want.part as Query.Tags).filter)
        assertEquals(ChipKind.AIM, lake.chips.last().kind)
        // the same in Dutch, German (one word) and French
        for ((text, lang) in listOf("meer om te zwemmen" to "nl", "Badesee" to "de", "lac pour se baigner" to "fr")) {
            val p = QueryParser.parse(text, lang)
            assertEquals(text, "swim", p.intent)
            assertEquals(text, lake.query, p.query)
        }
        // on its own, the places that serve it
        val alone = QueryParser.parse("where can I swim")
        assertEquals("swim", alone.intent)
        assertEquals(Intents.byId("swim")!!.filter, (alone.query as Query.Tags).filter)
        assertTrue(alone.structured)
        // a park to play: a playground in it, likely
        assertEquals("play", QueryParser.parse("park to play").intent)
    }

    @Test
    fun aWayFromOnePlaceToAnother() {
        fun route(text: String, lang: String = "en") = QueryParser.parse(text, lang).route
        assertEquals(RouteAsk("Westerkerk", "Anne Frank Huis", null, "from"), route("from Westerkerk to Anne Frank Huis"))
        // "van" may begin the start's name: kept apart for the start to be looked up with it too
        assertEquals(RouteAsk("gogh museum", "centraal station", null, "van"), route("van gogh museum naar centraal station", "nl"))
        assertEquals(RouteAsk(null, "Bahnhof", null, null), route("wie komme ich zum Bahnhof", "de"))
        assertEquals(RouteAsk("la gare", "la tour eiffel", null, "de"), route("de la gare a la tour eiffel", "fr"))
        assertEquals(RouteAsk(null, "the nearest pharmacy", null, null), route("route to the nearest pharmacy"))
        assertEquals(RouteAsk(null, "Westerkerk", "BIKE", null), route("directions to Westerkerk by bike"))
        assertEquals(RouteAsk(null, "Albert Heijn", "WALK", null), route("walk to Albert Heijn"))
        assertEquals(RouteAsk("Amsterdam", "Utrecht", null, null), route("Amsterdam to Utrecht"))
        // not ways: a kind or something to do on either side, a time
        for (t in listOf("lake to swim", "park to walk the dog", "bike to rent", "open from 9 to 5", "cafe to work")) assertEquals(t, null, route(t))
    }

    @Test
    fun aKindWithFiltersIsThatKindsView() {
        val now = java.time.ZonedDateTime.of(2026, 11, 3, 15, 0, 0, 0, java.time.ZoneId.of("Europe/Amsterdam"))
        fun view(text: String) = KindViews.of(QueryParser.parse(text, "en", now), now)
        val vegan = view("vegan restaurant open now")!!
        assertEquals("preset:amenity/restaurant", vegan.node.id)
        assertEquals(setOf("diet:vegan=~yes", "diet:vegan=~only", "@open=now"), vegan.selection.ids().toSet())
        // a time asked about, a circle, an order
        val late = view("restaurant open after 22:00 with vegan options within 10 min walk")!!
        assertEquals(java.time.LocalDateTime.of(2026, 11, 3, 22, 0), late.at)
        assertEquals(800.0, late.radius!!, 0.0)
        assertEquals(Order.NEAREST, view("nearest pharmacy")!!.order)
        // a cuisine is the restaurants' cuisine; one key's values are their kind's narrowest node with them chosen
        assertEquals(setOf("cuisine=pizza"), view("pizza")!!.selection.ids().toSet())
        val bars = view("bar open tonight")!!
        assertEquals("cat:drinks", bars.node.id)
        assertTrue("amenity=pub" in bars.selection.ids() && "@open=tonight" in bars.selection.ids())
        // without: those that say no and those that say nothing
        assertEquals(setOf("wheelchair=~no", "wheelchair=!"), view("toilets not wheelchair accessible")!!.selection.ids().toSet())
        // more than a view holds stays a query
        for (t in listOf("restaurant near a park", "charger 150 kW", "how many benches", "cinema after dinner")) assertEquals(t, null, view(t))
    }
}
