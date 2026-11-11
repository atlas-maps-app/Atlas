package app.atlas.ui.local

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.places.TagFilter
import app.atlas.providers.Sky
import app.atlas.providers.WeatherDay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** The data models of the local package, without a screen: seasons, the year, waste, building years, names, notes, tides, moments, parks. */
class LocalModelsTest {
    private fun resource(name: String) = LocalModelsTest::class.java.getResourceAsStream("/local/$name")!!.bufferedReader().readText()

    // ---- the seasons ----------------------------------------------------------------------------------------------------

    @Test
    fun aSpanThatWrapsTheYearKnowsItsMonths() {
        val s = Span(11, 2, 12, 1)
        assertTrue(s.inSeason(12) && s.inSeason(1) && s.inSeason(11) && s.inSeason(2))
        assertFalse(s.inSeason(5))
        assertTrue(s.isNew(11))
        assertTrue(s.isLast(2))
        assertTrue(s.atPeak(1) && !s.atPeak(11))
        assertEquals(0, s.monthsUntilStart(12))
        assertEquals(2, s.monthsUntilStart(9))
    }

    @Test
    fun spansReadTheirTextAndFlipForTheSouth() {
        assertEquals(Span(5, 7, 6, 6), Span.parse("5-7/6-6"))
        assertNull(Span.parse("junk"))
        assertEquals(Span(12, 3, 1, 2), Span(6, 9, 7, 8).flipped())
    }

    @Test
    fun theRegionIsTheCountryTheCalendarIsWrittenFor() {
        assertEquals(FoodRegion.NL, FoodRegion.of(LatLon(52.37, 4.89)))
        assertEquals(FoodRegion.DE, FoodRegion.of(LatLon(52.52, 13.40)))
        assertEquals(FoodRegion.FR, FoodRegion.of(LatLon(48.85, 2.35)))
        assertEquals(FoodRegion.GB, FoodRegion.of(LatLon(51.5, -0.12)))
        assertEquals(FoodRegion.US, FoodRegion.of(LatLon(40.7, -74.0)))
        assertEquals(FoodRegion.OTHER, FoodRegion.of(LatLon(35.7, 139.7)))
    }

    @Test
    fun theFoodCalendarHoldsAboutSixtyFoodsAndSortsThemByTheMonth() {
        for (region in listOf(FoodRegion.NL, FoodRegion.DE, FoodRegion.FR, FoodRegion.GB, FoodRegion.US)) {
            val foods = FoodCalendar.foods(region)
            assertTrue("${region.name}: ${foods.size}", foods.size in 55..70)
        }
        val now = FoodCalendar.now(FoodRegion.NL, LocalDate.of(2026, 6, 15))
        assertTrue(now.best.isNotEmpty())
        assertTrue(now.best.all { it.span.inSeason(6) })
        assertTrue(now.last.all { it.span.inSeason(6) && it.span.isLast(6) })
        assertTrue(now.coming.none { it.span.inSeason(6) })
        assertTrue(now.coming.all { it.span.monthsUntilStart(6) in 1..2 })
    }

    @Test
    fun theNextSeasonStartsOnTheFirstOfItsMonthAfterToday() {
        val s = Span(5, 7, 6, 6)
        assertEquals(LocalDate.of(2026, 5, 1), FoodCalendar.nextStart(s, LocalDate.of(2026, 4, 10)))
        assertEquals(LocalDate.of(2027, 5, 1), FoodCalendar.nextStart(s, LocalDate.of(2026, 6, 15)))
    }

    @Test
    fun theYearWheelPutsWhatEndsFirstAndNeverShowsMoreThanFour() {
        val now = YearCalendar.now(FoodRegion.NL, LocalDate.of(2026, 10, 13))
        val foliage = now.first { it.activity.id == "foliage" }
        assertEquals(Phase.PEAK, foliage.phase)
        assertEquals(Phase.SOON, now.first { it.activity.id == "ski" }.phase)
        assertTrue(YearCalendar.highlights(now).size <= 4)
        assertEquals(0, YearCalendar.third(LocalDate.of(2026, 10, 1)))
        assertEquals(2, YearCalendar.third(LocalDate.of(2026, 10, 31)))
    }

    // ---- waste ----------------------------------------------------------------------------------------------------------

    @Test
    fun aWasteCalendarUnrollsItsRepeatsFromTheIcsFile() {
        val cal = WasteCalendar.parse(resource("collect_sample.ics"))!!
        assertEquals("Singel 120", cal.name)
        assertEquals(6, cal.size)
        val week = cal.pickups(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18))
        assertEquals(listOf(12, 14, 15, 18), week.map { it.date.dayOfMonth })
        assertEquals(listOf(Stream.REST), week[0].streams)
        assertEquals(setOf(Stream.PAPER, Stream.PACKAGING), week[1].streams.toSet())
        assertEquals(setOf(Stream.REST, Stream.ORGANIC), week[2].streams.toSet())
        assertTrue(week[3].streams.all { it.special })
        // The first Tuesday of the month.
        assertEquals(LocalDate.of(2026, 10, 6), cal.pickups(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10)).first { Stream.GLASS in it.streams }.date)
        assertEquals(LocalDate.of(2026, 10, 12), cal.next(LocalDate.of(2026, 10, 12))!!.date)
    }

    @Test
    fun theCalendarsOwnWordsSortIntoStreamsInFourLanguages() {
        assertEquals(Stream.REST, WasteCalendar.classify("Restafval"))
        assertEquals(Stream.PAPER, WasteCalendar.classify("Papier en karton"))
        assertEquals(Stream.PACKAGING, WasteCalendar.classify("Verpakkingen (PMD)"))
        assertEquals(Stream.PACKAGING, WasteCalendar.classify("Gelbe Tonne"))
        assertEquals(Stream.GLASS, WasteCalendar.classify("Glas"))
        assertEquals(Stream.ORGANIC, WasteCalendar.classify("GFT"))
        assertEquals(Stream.BULKY, WasteCalendar.classify("Sperrmüll"))
        assertEquals(Stream.HAZARDOUS, WasteCalendar.classify("Chemokar"))
        assertEquals(Stream.OTHER, WasteCalendar.classify("Something else"))
    }

    @Test
    fun anEmptyOrBrokenCalendarIsNotACalendar() {
        assertNull(WasteCalendar.parse("not a calendar"))
    }

    // ---- buildings ------------------------------------------------------------------------------------------------------

    @Test
    fun aBuildingYearIsReadFromStartDateAndTheRegistersUnknownIsUnknown() {
        assertEquals(BuiltYear.Year(1620, false), BuildingStory.year("1620"))
        assertEquals(BuiltYear.Year(1620, false), BuildingStory.year("1620-05-01"))
        assertEquals(BuiltYear.Year(1850, true), BuildingStory.year("~1850"))
        assertEquals(BuiltYear.Year(1850, true), BuildingStory.year("1850..1860"))
        assertEquals(BuiltYear.Year(1900, true), BuildingStory.year("before 1900"))
        assertEquals(BuiltYear.Century(17), BuildingStory.year("C17"))
        assertEquals(BuiltYear.Unknown, BuildingStory.year("1005"))
        assertEquals(BuiltYear.Unknown, BuildingStory.year("1005-01-01"))
        assertEquals(BuiltYear.Unknown, BuildingStory.year("99"))
        assertEquals(BuiltYear.Unknown, BuildingStory.year(""))
        assertEquals(BuiltYear.Unknown, BuildingStory.year(null))
    }

    @Test
    fun wikidataCorrectsARegisterYearThatIsAnotherBuilding() {
        val place = Place(OsmId(OsmType.WAY, 1), LatLon(52.37, 4.88), mapOf("building" to "church", "name" to "Westerkerk", "start_date" to "1990", "wikidata" to "Q1130722"))
        val facts = BuildingStory.of(place, "en", Etymology.BuildingItem(1631, "Hendrick de Keyser", null, null, null))
        assertEquals(BuiltYear.Year(1631, false), facts.built)
        assertEquals(1990, facts.mappedYear)
        assertEquals("Hendrick de Keyser", facts.architect)
        // Close years leave the map's own.
        assertEquals(BuiltYear.Year(1990, false), BuildingStory.of(place, "en", Etymology.BuildingItem(1995, null, null, null, null)).built)
    }

    @Test
    fun heightsComeFromHeightOrLevels() {
        assertEquals(18.5, Footprints.height(mapOf("height" to "18.5"))!!, 1e-9)
        assertEquals(12.0, Footprints.height(mapOf("height" to "12 m"))!!, 1e-9)
        assertEquals(7.5, Footprints.height(mapOf("height" to "7,5"))!!, 1e-9)
        assertEquals(12.0, Footprints.height(mapOf("building:levels" to "4"))!!, 1e-9)
        assertEquals(10.5, Footprints.height(mapOf("building:levels" to "3", "roof:levels" to "1"))!!, 1e-9)
        assertNull(Footprints.height(mapOf("building" to "yes")))
    }

    @Test
    fun theTimeMachineColoursByEraAndCountsWhatStood() {
        val e = TimeMachine.Era.entries
        assertEquals(TimeMachine.Era.OLD, TimeMachine.era(1699))
        assertEquals(TimeMachine.Era.BRICK, TimeMachine.era(1700))
        assertEquals(TimeMachine.Era.BRICK, TimeMachine.era(1849))
        assertEquals(TimeMachine.Era.OCHRE, TimeMachine.era(1850))
        assertEquals(TimeMachine.Era.POSTWAR, TimeMachine.era(1945))
        assertEquals(TimeMachine.Era.NEW, TimeMachine.era(1990))
        assertEquals(TimeMachine.Era.UNKNOWN, TimeMachine.era(null))
        assertEquals(6, e.size)
        val all = LocalFixtures.buildings.mapNotNull { Footprints.of(it, "en") }
        assertTrue(all.size > 900)
        val a = TimeMachine.slice(all, 1700)
        val b = TimeMachine.slice(all, 1900)
        val c = TimeMachine.slice(all, 2030)
        assertTrue(a.stood < b.stood && b.stood < c.stood)
        assertEquals(a.total, c.total)
        assertTrue(c.unknown > 0)
        assertEquals(c.total - c.unknown, c.stood)
        // The 1005 of the Dutch register is no year.
        assertTrue(all.filter { it.place.tags["start_date"] == "1005" }.all { it.year == null })
    }

    @Test
    fun theCardsMarkTheYearWithTheNotableBuildingsOnEitherSide() {
        val all = LocalFixtures.buildings.mapNotNull { Footprints.of(it, "en") }
        val cards = TimeMachine.cards(all, 1700)
        assertTrue(cards.before.isNotEmpty() && cards.before.all { it.year!! <= 1700 })
        assertTrue(cards.before.zipWithNext().all { (x, y) -> x.year!! >= y.year!! })
        assertNotNull(cards.after)
        assertTrue(cards.after!!.year!! > 1700)
        // Before anything on the map, there is no card before.
        assertTrue(TimeMachine.cards(all, 1500).before.isEmpty())
    }

    // ---- named after ----------------------------------------------------------------------------------------------------

    @Test
    fun aStreetIsNamedAfterWhatWikidataSays() = runBlocking {
        val found = LocalFixtures.etymology.lookup(LatLon(52.36605, 4.89664), listOf(LocalFixtures.rembrandtplein), "Rembrandtplein", listOf("en"), online = true)
        assertEquals(NamedAfter.State.FOUND, found.state)
        val rembrandt = found.namesakes.first()
        assertTrue(rembrandt.label, rembrandt.label.contains("Rembrandt"))
        assertEquals(1606, rembrandt.born)
        assertEquals(17, rembrandt.century)
        assertEquals(NamesakeKind.PERSON, rembrandt.kind)
        val offline = LocalFixtures.etymology.lookup(LatLon(52.36605, 4.89664), listOf(LocalFixtures.rembrandtplein), "Rembrandtplein", listOf("en"), online = false)
        assertEquals(NamedAfter.State.OFFLINE, offline.state)
        assertEquals(NamedAfter.State.NO_STREET, LocalFixtures.etymology.lookup(LatLon(52.0, 4.0), emptyList(), null, listOf("en"), online = true).state)
    }

    // ---- nature ---------------------------------------------------------------------------------------------------------

    @Test
    fun treesAreKnownByGenusAndScoredByNotability() {
        assertEquals("Platanus", Trees.genusOf(mapOf("species" to "Platanus x hispanica")))
        assertEquals("Tilia", Trees.genusOf(mapOf("genus" to "tilia")))
        assertNull(Trees.genusOf(mapOf("natural" to "tree")))
        assertEquals("plane tree", Trees.nameOf("platanus")!!.en)
        assertEquals("plataan", Trees.nameOf("Platanus")!!.nl)
        val today = LocalDate.of(2026, 10, 13)
        val plain = NatureNotes.score(mapOf("natural" to "tree"), today)
        val monument = NatureNotes.score(mapOf("natural" to "tree", "denotation" to "natural_monument", "name" to "Old Elm", "start_date" to "1750"), today)
        assertEquals(0, plain)
        assertTrue(monument >= 110)
        assertEquals(1850, NatureNotes.planted(mapOf("start_date" to "~1850")))
        assertNull(NatureNotes.planted(mapOf("start_date" to "C17")))
    }

    @Test
    fun theNotebookPicksTheNotableTreesAndSummarisesTheRest() {
        val today = LocalDate.of(2026, 10, 13)
        val trees = (1..5).map { i -> Found(Place(OsmId(OsmType.NODE, i.toLong()), LatLon(52.37 + i * 1e-4, 4.89), mapOf("natural" to "tree", "genus" to "Platanus")), 10.0 * i) } +
            Found(Place(OsmId(OsmType.NODE, 99), LatLon(52.3702, 4.8905), mapOf("natural" to "tree", "name" to "Old Elm", "denotation" to "natural_monument")), 40.0)
        val notes = NatureNotes.treeNotes(trees, today)
        assertTrue(notes.isNotEmpty())
        assertEquals("Old Elm", notes.first().title)
        assertTrue(notes.any { it.count >= 5 || it.what == "trees" })
        assertTrue(NatureNotes.treeNotes(emptyList(), today).isEmpty())
    }

    @Test
    fun playgroundAgesAreReadFromMinAndMaxAge() {
        assertEquals("2–12", ageRange(mapOf("min_age" to "2", "max_age" to "12")))
        assertEquals("3+", ageRange(mapOf("min_age" to "3")))
        assertEquals("≤6", ageRange(mapOf("max_age" to "6")))
        assertNull(ageRange(emptyMap()))
        assertTrue(takesAge(mapOf("min_age" to "2", "max_age" to "12"), 5))
        assertFalse(takesAge(mapOf("min_age" to "2", "max_age" to "12"), 1))
        assertFalse(takesAge(mapOf("max_age" to "6"), 9))
        assertTrue(takesAge(emptyMap(), 40))
        assertTrue(fenced(mapOf("barrier" to "fence")))
        assertFalse(fenced(mapOf("leisure" to "playground")))
    }

    @Test
    fun theTideClockFindsHighsAndLowsInTheHourlyLevel() {
        val now = Instant.parse("2026-10-13T17:30:00Z")
        val tide = TideNow.of(LocalFixtures.tideAt(now), now)!!
        assertEquals(1.5, tide.rangeM, 0.1)
        assertTrue(tide.nextHigh!!.isAfter(now) && tide.nextLow!!.isAfter(now))
        val gap = kotlin.math.abs(java.time.Duration.between(tide.nextHigh, tide.nextLow).toMinutes())
        assertTrue("$gap", gap in 330..420)
        assertTrue(tide.phase(now) in 0.0..1.0)
        assertNull(TideNow.of(null, now))
        // A sea that hardly moves has no tide.
        val flat = LocalFixtures.tideAt(now).let { f -> app.atlas.providers.MarineForecast(f.location, f.now, f.hours.map { it.copy(seaLevel = 0.0) }, f.fetchedAt) }
        assertNull(TideNow.of(flat, now))
    }

    @Test
    fun theSoundLevelIsDecibelsFromTheMicrophonesSamples() {
        assertNull(MicSoundMeter.level(0.0, 0))
        assertEquals(25.0, MicSoundMeter.level(0.0, 100)!!, 1e-9)
        assertEquals(90.0, MicSoundMeter.level(32768.0 * 32768.0 * 10, 10)!!, 1e-6)
        assertEquals(70.0, MicSoundMeter.level(3276.8 * 3276.8 * 10, 10)!!, 1e-6)
        assertEquals(25.0, MicSoundMeter.level(1.0, 1_000_000)!!, 1e-9)
    }

    // ---- the soon tab ---------------------------------------------------------------------------------------------------

    private fun day(sky: Sky, max: Double, prob: Double, mm: Double, wind: Double = 10.0) =
        WeatherDay(LocalDate.of(2026, 10, 13), sky, null, max, max - 6, mm, prob, 0.0, wind, wind * 1.5, 270.0, 3.0, null, null, 36_000.0)

    @Test
    fun aDaysForecastBecomesOneWord() {
        assertEquals(Moments.DayNote.STORM, Moments.note(day(Sky.THUNDERSTORM, 20.0, 80.0, 10.0)))
        assertEquals(Moments.DayNote.SNOW, Moments.note(day(Sky.SNOW, 0.0, 80.0, 4.0)))
        assertEquals(Moments.DayNote.WET, Moments.note(day(Sky.RAIN, 12.0, 90.0, 8.0)))
        assertEquals(Moments.DayNote.SHOWERS, Moments.note(day(Sky.SHOWERS, 12.0, 50.0, 1.0)))
        assertEquals(Moments.DayNote.COLD, Moments.note(day(Sky.CLEAR, 3.0, 0.0, 0.0)))
        assertEquals(Moments.DayNote.DRY_WARM, Moments.note(day(Sky.CLEAR, 22.0, 0.0, 0.0)))
        assertEquals(Moments.DayNote.DRY, Moments.note(day(Sky.OVERCAST, 12.0, 10.0, 0.0)))
    }

    @Test
    fun theBestDayIsDryAndMild() {
        val days = listOf(day(Sky.RAIN, 14.0, 90.0, 9.0), day(Sky.CLEAR, 21.0, 0.0, 0.0), day(Sky.CLEAR, 30.0, 0.0, 0.0), day(Sky.CLEAR, 3.0, 0.0, 0.0))
        assertEquals(1, Moments.bestDay(days))
        assertEquals(0, Moments.bestDay(emptyList()))
    }

    // ---- parks ----------------------------------------------------------------------------------------------------------

    @Test
    fun openSegmentsJoinIntoRingsAndClosedOnesStand() {
        val a = LatLon(0.0, 0.0); val b = LatLon(0.0, 1.0); val c = LatLon(1.0, 1.0); val d = LatLon(1.0, 0.0)
        val rings = Parkland.joinRings(listOf(listOf(a, b, c), listOf(c, d, a)))
        assertEquals(1, rings.size)
        assertEquals(rings[0].first(), rings[0].last())
        assertEquals(5, rings[0].size)
        val closed = listOf(a, b, c, a)
        assertEquals(listOf(closed), Parkland.joinRings(listOf(closed)))
    }

    @Test
    fun aParksOutlineAndPathsComeFromItsElements() {
        val shape = Parkland.shapeOf(LocalFixtures.westerparkElements, LocalFixtures.westerpark.id!!)!!
        assertTrue(shape.outlined)
        assertTrue(shape.rings.isNotEmpty() && shape.rings.all { it.size >= 3 })
        assertTrue(shape.paths.size > 5)
        assertNull(Parkland.shapeOf(LocalFixtures.westerparkElements, OsmId(OsmType.WAY, 1)))
    }

    @Test
    fun aParksHoursReadAsTodaysSpan() {
        val day = LocalDate.of(2026, 10, 13)
        assertEquals("6:00–23:00", Parkland.todaysSpan("Mo-Su 06:00-23:00", day))
        assertEquals("24/7", Parkland.todaysSpan("24/7", day))
        assertNull(Parkland.todaysSpan(null, day))
        assertNull(Parkland.todaysSpan("by appointment", day))
    }

    @Test
    fun theParkFitsItsDrawingKeepingItsShape() {
        val box = BBox(4.86, 52.38, 4.88, 52.39)
        val p = ParkProjection(box, 348f, 200f, 16f)
        assertTrue(p.x(4.86) >= 15f && p.x(4.88) <= 348f - 15f)
        assertTrue(p.y(52.39) >= 15f && p.y(52.38) <= 200f - 15f)
        assertTrue(p.x(4.86) < p.x(4.88) && p.y(52.39) < p.y(52.38))
    }

    /** Amsterdam's canal ring as OpenStreetMap has it: a world heritage site, protected for its culture (class 22). */
    private val canalRing = mapOf(
        "boundary" to "protected_area", "protect_class" to "22", "heritage" to "1", "historic" to "heritage", "name" to "Grachtengordel van Amsterdam",
    )

    @Test
    fun aParkAReserveAndANationalParkHaveTheirPageAndTheCanalRingNone() {
        assertEquals(GreenKind.NATIONAL, GreenKind.of(mapOf("boundary" to "national_park")))
        assertEquals(GreenKind.RESERVE, GreenKind.of(mapOf("leisure" to "nature_reserve")))
        assertEquals(GreenKind.RESERVE, GreenKind.of(mapOf("boundary" to "protected_area", "protect_class" to "4")))
        assertEquals(GreenKind.RESERVE, GreenKind.of(mapOf("boundary" to "protected_area", "protect_class" to "1b")))
        assertEquals(GreenKind.RESERVE, GreenKind.of(mapOf("boundary" to "protected_area")))
        assertEquals(GreenKind.PARK, GreenKind.of(mapOf("leisure" to "park")))
        assertNull(GreenKind.of(mapOf("leisure" to "garden")))
        // the canal ring, the same with no class given, and a water catchment (12) protect no nature
        assertNull(GreenKind.of(canalRing))
        assertNull(GreenKind.of(canalRing - "protect_class"))
        assertNull(GreenKind.of(mapOf("boundary" to "protected_area", "protect_class" to "12")))
        // the wild notes' filter and the reserves' words agree
        val protectedNature = TagFilter.parse(GreenKind.NATURE_PROTECTED)
        val wild = TagFilter.parse(NatureKind.WILD.filter)
        assertFalse(protectedNature.matches(canalRing) || wild.matches(canalRing) || wild.matches(canalRing - "protect_class"))
        assertTrue(protectedNature.matches(mapOf("boundary" to "protected_area", "protect_class" to "2")))
        assertTrue(wild.matches(mapOf("boundary" to "protected_area")))
        // what has a page is what a downloaded country's store keeps the outline of, for the page offline
        val kinds = listOf(
            mapOf("boundary" to "national_park"), mapOf("leisure" to "nature_reserve"), mapOf("leisure" to "park"),
            mapOf("boundary" to "protected_area"), mapOf("boundary" to "protected_area", "protect_class" to "97"),
            canalRing, canalRing - "protect_class", canalRing + ("leisure" to "park"), mapOf("leisure" to "garden"),
            mapOf("boundary" to "protected_area", "protect_class" to "12"), mapOf("landuse" to "forest", "name" to "Amsterdamse Bos"),
        )
        for (t in kinds) assertEquals("$t", GreenKind.of(t) != null, app.atlas.search.Outlines.keeps(t))
    }

    @Test
    fun theWildNotesTakeNationalParksAndDrawWhatEachPlaceIs() {
        assertTrue(TagFilter.parse(NatureKind.WILD.filter).matches(mapOf("boundary" to "national_park", "name" to "De Hoge Veluwe")))
        fun found(type: OsmType, id: Long, vararg tags: Pair<String, String>) = Found(Place(OsmId(type, id), LatLon(52.4, 4.9), tags.toMap()), 0.0)
        val notes = NatureNotes.placeNotes(
            NatureKind.WILD,
            listOf(
                found(OsmType.RELATION, 1, "boundary" to "national_park", "name" to "De Hoge Veluwe", "protect_class" to "2"),
                found(OsmType.WAY, 2, "leisure" to "nature_reserve", "name" to "Ilperveld"),
                found(OsmType.NODE, 3, "natural" to "peak", "name" to "Vaalserberg"),
                found(OsmType.WAY, 4, "natural" to "wood", "name" to "Kraaijenbergse Bossen"),
            ),
        )
        assertEquals(listOf("place.national-park", "pinhead-leaf", "nature.peak", "nature.tree"), notes.map { it.art })
    }

    @Test
    fun aNationalParksSignSaysItsOwnName() {
        val n = GreenKind.NATIONAL
        assertEquals("De Hoge Veluwe", Parkland.shortName("Nationaal Park De Hoge Veluwe", n))
        assertEquals("Bayerischer Wald", Parkland.shortName("Nationalpark Bayerischer Wald", n))
        assertEquals("Écrins", Parkland.shortName("Parc national des Écrins", n))
        assertEquals("Vanoise", Parkland.shortName("Parc national de la Vanoise", n))
        assertEquals("Picos de Europa", Parkland.shortName("Parque Nacional de los Picos de Europa", n))
        assertEquals("Sila", Parkland.shortName("Parco Nazionale della Sila", n))
        assertEquals("Arcipelago di La Maddalena", Parkland.shortName("Parco Nazionale dell'Arcipelago di La Maddalena", n))
        assertEquals("Yosemite", Parkland.shortName("Yosemite National Park", n))
        // an article only as a whole word; too little left, or a town's park: the name as it is
        assertEquals("Delta", Parkland.shortName("Parco Nazionale Delta", n))
        assertEquals("Nationaal Park", Parkland.shortName("Nationaal Park", n))
        assertEquals("Nationaal Park Zuid-Kennemerland", Parkland.shortName("Nationaal Park Zuid-Kennemerland", GreenKind.PARK))
    }

    @Test
    fun aParksSizeReadsAsPeopleSayIt() {
        // a pocket park as small as it is, never rounded up to a hectare
        assertEquals("450 m²", Parkland.areaText(452.0, Locale.ENGLISH))
        assertEquals("0.4 ha", Parkland.areaText(4_000.0, Locale.ENGLISH))
        assertEquals("0,4 ha", Parkland.areaText(4_000.0, Locale.forLanguageTag("nl")))
        assertEquals("45 ha", Parkland.areaText(452_000.0, Locale.ENGLISH))
        assertEquals("2 km²", Parkland.areaText(2_000_000.0, Locale.ENGLISH))
        assertEquals("3.1 km²", Parkland.areaText(3_140_000.0, Locale.ENGLISH))
        assertEquals("3,1 km²", Parkland.areaText(3_140_000.0, Locale.forLanguageTag("nl")))
        assertEquals("55 km²", Parkland.areaText(54_600_000.0, Locale.ENGLISH))
    }

    private fun square(south: Double, west: Double, side: Double) =
        listOf(LatLon(south, west), LatLon(south, west + side), LatLon(south + side, west + side), LatLon(south + side, west))

    @Test
    fun aParksShapeKnowsItsAreaItsLakeAndItsEdge() {
        // 0.01 degrees square at 52.4 N, a lake of a quarter of it left out
        val shape = ParkShape(listOf(square(52.40, 4.90, 0.01), square(52.4025, 4.9025, 0.005)), emptyList(), emptyList(), outlined = true, holes = listOf(false, true))
        val whole = Geo.distance(LatLon(52.405, 4.90), LatLon(52.405, 4.91)) * Geo.distance(LatLon(52.40, 4.905), LatLon(52.41, 4.905))
        assertEquals(whole * 0.75, ParkShape.areaOf(shape.rings, shape.holes), whole * 0.01)
        assertTrue(shape.contains(LatLon(52.401, 4.901)))
        assertFalse(shape.contains(LatLon(52.405, 4.905)))
        assertFalse(shape.contains(LatLon(52.42, 4.905)))
        // a thousandth of a degree south of it is some 111 m from its edge: in reach of a gate's margin of 150, not of 50
        assertEquals(111.3, shape.edgeDistance(LatLon(52.399, 4.905)), 1.0)
        assertTrue(shape.reaches(LatLon(52.399, 4.905), 150.0))
        assertFalse(shape.reaches(LatLon(52.399, 4.905), 50.0))
    }

    @Test
    fun aParksPlanIsAsTallAsItsShape() {
        // at the equator a degree is as wide as it is tall: a square park, a square plan
        assertEquals(348f, ParkProjection.heightFor(BBox(0.0, -0.005, 0.01, 0.005), 348f, 16f, 200f, 440f), 0.5f)
        // a long park (Vondelpark's box) a low sign, a tall one (Postzegelpark's) no taller than the screen allows
        assertEquals(200f, ParkProjection.heightFor(BBox(4.8555, 52.3550, 4.8815, 52.3610), 348f, 16f, 200f, 440f), 0f)
        assertEquals(440f, ParkProjection.heightFor(BBox(4.890113, 52.3706534, 4.8908224, 52.371604), 348f, 16f, 200f, 440f), 0f)
    }

    @Test
    fun aParksTrailIsALoopFirstThenOneWithItsLength() {
        assertEquals(12.5, trailKm(mapOf("distance" to "12.5"))!!, 1e-9)
        assertEquals(7.2, trailKm(mapOf("distance" to "7,2 km"))!!, 1e-9)
        assertEquals(8 * 1.609, trailKm(mapOf("distance" to "8 mi"))!!, 1e-9)
        assertNull(trailKm(mapOf("distance" to "about an hour")))
        assertNull(trailKm(emptyMap()))
        // its length as its signpost says it
        assertEquals("12.5 km", kmText(12.5, Locale.ENGLISH))
        assertEquals("12,5 km", kmText(12.5, Locale.forLanguageTag("nl")))
        assertEquals("7 km", kmText(7.0, Locale.ENGLISH))
        assertEquals("112 km", kmText(112.4, Locale.ENGLISH))
        val here = LatLon(52.37, 4.88)
        fun trail(id: Long, name: String, lat: Double, vararg tags: Pair<String, String>) =
            Place(OsmId(OsmType.RELATION, id), LatLon(lat, 4.88), mapOf("route" to "hiking", "name" to name) + tags)
        val near = trail(1, "Near", 52.371)
        val measured = trail(2, "Measured", 52.38, "distance" to "9")
        val loop = trail(3, "The Loop", 52.40, "roundtrip" to "yes")
        assertEquals("The Loop", featuredTrail(listOf(near, measured, loop), here)?.tags?.get("name"))
        assertEquals("Measured", featuredTrail(listOf(near, measured), here)?.tags?.get("name"))
        assertEquals("Near", featuredTrail(listOf(trail(4, "Far", 52.45), near), here)?.tags?.get("name"))
        assertNull(featuredTrail(emptyList(), here))
    }

    private val wildBox = BBox(4.95, 52.43, 5.05, 52.47)

    private fun park(kind: GreenKind, trees: List<Found> = emptyList(), birds: List<Found> = emptyList(), treeCrowns: Boolean = false) = Park(
        Place(OsmId(OsmType.RELATION, 1), wildBox.center, mapOf("name" to "P"), wildBox), kind, "P", null, wildBox,
        ParkShape(emptyList(), emptyList(), if (treeCrowns) listOf(wildBox.center) else emptyList(), outlined = false), emptyList(), trees = trees, birds = birds,
    )

    private val hide = Found(Place(OsmId(OsmType.NODE, 13), LatLon(52.455, 5.02), mapOf("leisure" to "bird_hide", "name" to "Reedbed Hide")), 0.0)

    @Test
    fun whatTheYearBringsAParkNow() {
        val october = LocalDate.of(2026, 10, 13)
        // in October a national park's birds pass at their peak; in March its winter stars have their last month
        val birds = natureNow(park(GreenKind.NATIONAL), october)!!
        assertEquals("birds", birds.activity.id)
        assertEquals(Phase.PEAK, birds.phase)
        val stars = natureNow(park(GreenKind.NATIONAL), LocalDate.of(2026, 3, 10))!!
        assertEquals("stars", stars.activity.id)
        assertEquals(Phase.LAST, stars.phase)
        // a town park's leaves when it has trees, its birds when it has bird places, nothing with neither or out of season
        assertEquals("foliage", natureNow(park(GreenKind.PARK, birds = listOf(hide), treeCrowns = true), october)?.activity?.id)
        assertEquals("birds", natureNow(park(GreenKind.PARK, birds = listOf(hide)), october)?.activity?.id)
        assertNull(natureNow(park(GreenKind.PARK), october))
        assertNull(natureNow(park(GreenKind.PARK, treeCrowns = true), LocalDate.of(2026, 7, 10)))
    }

    @Test
    fun aParksNotesPutItsTreesFirstAndAWildParksBirds() {
        val oak = Found(Place(OsmId(OsmType.NODE, 11), LatLon(52.44, 4.99), mapOf("natural" to "tree", "name" to "Stonewood Oak", "species" to "Quercus robur", "denotation" to "natural_monument")), 0.0)
        val plain = Found(Place(OsmId(OsmType.NODE, 12), LatLon(52.441, 4.99), mapOf("natural" to "tree")), 0.0)
        val here = LatLon(52.3745, 4.884)
        val today = LocalDate.of(2026, 10, 13)
        assertEquals(listOf("Stonewood Oak", "Reedbed Hide"), NatureNotes.parkNotes(park(GreenKind.PARK, listOf(oak, plain), listOf(hide)), here, today).map { it.title })
        assertEquals(listOf("Reedbed Hide", "Stonewood Oak"), NatureNotes.parkNotes(park(GreenKind.NATIONAL, listOf(oak, plain), listOf(hide)), here, today).map { it.title })
        // measured from you, not from the search's centre
        assertEquals(Geo.distance(here, hide.place.location), NatureNotes.parkNotes(park(GreenKind.NATIONAL, birds = listOf(hide)), here, today).single().distanceM, 1.0)
        // unremarkable trees make no "trees mapped" note on a park's page
        assertTrue(NatureNotes.parkNotes(park(GreenKind.PARK, listOf(plain)), here, today).isEmpty())
    }

    @Test
    fun aGreensRowOpensItsParkPage() {
        val box = BBox(4.8600, 52.3835, 4.8790, 52.3885)
        val park = Place(OsmId(OsmType.WAY, 7), box.center, mapOf("leisure" to "park", "name" to "Westerpark"), box)
        assertEquals(Dest.Parkland(osmId = "w7", key = "w7"), Parkland.destOf(park))
        assertEquals(Dest.Parkland(osmId = "w7", key = "w7"), Dest.Place.page(park))
        assertEquals(Dest.Parkland(osmId = "r8", key = "r8"), Dest.Place.page(park.copy(id = OsmId(OsmType.RELATION, 8), tags = mapOf("boundary" to "national_park"))))
        // a park mapped as a point or without its box has no outline to draw; a café and the canal ring have a place page
        assertTrue(Dest.Place.page(park.copy(id = OsmId(OsmType.NODE, 9))) is Dest.Place)
        assertNull(Parkland.destOf(park.copy(bbox = null)))
        assertTrue(Dest.Place.page(park.copy(tags = mapOf("amenity" to "cafe"))) is Dest.Place)
        assertTrue(Dest.Place.page(park.copy(tags = canalRing)) is Dest.Place)
    }
}
