package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class BranchesTest {
    @Test
    fun theBranchesSayTheHoursMostOfThemKeep() {
        val h = BrandHours.infer(listOf("Mo-Sa 09:00-17:00", "Mo-Sa 09:00-17:00", "Mo-Sa 09:00-17:00", "Mo-Su 10:00-22:00"))!!
        assertEquals("Mo-Sa 09:00-17:00", h.hours.raw)
        assertEquals(4, h.from)
        assertEquals(0.75, h.agreement, 1e-9)
        // the lower median: a time some branch keeps, never an average of two
        assertEquals("Mo-Su 08:00-21:00", BrandHours.infer(listOf("Mo-Su 08:00-22:00", "Mo-Su 07:00-21:00", "Mo-Su 08:00-20:00", "Mo-Su 09:00-21:00"))!!.hours.raw)
        // a lunch break is left out, a night past midnight kept
        assertEquals("Mo-Fr 09:00-18:00", BrandHours.infer(List(3) { "Mo-Fr 09:00-12:30,13:30-18:00" })!!.hours.raw)
        assertEquals("Mo-Su 11:00-03:00", BrandHours.infer(List(3) { "Mo-Su 11:00-03:00" })!!.hours.raw)
        assertEquals("24/7", BrandHours.infer(listOf("24/7", "24/7", "Mo-Su 00:00-24:00"))!!.hours.raw)
        // one other branch is enough, a chain keeping one plan; two must both open a day for it to be open
        assertEquals("Mo-Sa 10:00-18:00", BrandHours.infer(listOf("Mo-Sa 10:00-18:00"))!!.hours.raw)
        assertEquals("Mo-Fr 10:00-17:00", BrandHours.infer(listOf("Mo-Sa 10:00-18:00", "Mo-Fr 12:00-17:00"))!!.hours.raw)
        assertEquals("Mo-Fr 09:00-17:00", BrandHours.infer(listOf("by appointment", "ask", "Mo-Fr 09:00-17:00"))!!.hours.raw)
        // none readable says nothing, nor do fewer than three of a kind's places
        assertNull(BrandHours.infer(listOf("by appointment", "ask")))
        assertNull(BrandHours.infer(listOf("Mo-Fr 09:00-17:00", "Mo-Fr 09:00-17:00"), min = BrandHours.MIN_BRANCHES))
    }

    @Test
    fun theBranchesTakeTheClosingTheirHoursState() {
        // "16:00-02:00+" closes at 2:00 as far as its hours say, and then perhaps later: never at the guess of how long
        assertEquals(List(7) { WeekDay(16 * 60, 26 * 60, openEnd = true) }, BrandHours.weekOf(OpeningHours.parse("Mo-Su 16:00-02:00+")))
        assertEquals("Mo-Su 16:00-02:00+", BrandHours.infer(listOf("Mo-Su 16:00-02:00+"))!!.hours.raw)
        // an open end where most of the branches give one, as a day is closed where most close it
        assertEquals("Mo-Su 16:00-02:00+", BrandHours.infer(listOf("Mo-Su 16:00-02:00+", "Mo-Su 16:00-01:00", "Mo-Su 16:00-02:00+"))!!.hours.raw)
        assertEquals("Mo-Su 16:00-02:00", BrandHours.infer(listOf("Mo-Su 16:00-02:00+", "Mo-Su 16:00-02:00", "Mo-Su 16:00-03:00"))!!.hours.raw)
        // "17:00+" states no closing: it says nothing of a branch's week
        assertNull(BrandHours.weekOf(OpeningHours.parse("Mo-Su 17:00+")))
        assertNull(BrandHours.infer(listOf("Mo-Su 17:00+")))
        // borrowed, an open end reads as the branch's own: open in the tail after its stated end, and no closing time said
        val night = BrandHours.infer(listOf("Mo-Su 12:00-22:00+"))!!.hours.status(LocalDateTime.of(2026, 11, 16, 23, 30), OpeningHoursContext.Default)
        assertTrue(night.isOpen && night.openEnd)
        assertNull(night.statedChangeAt)
    }

    @Test
    fun realBranchesInAmsterdam() {
        val byBrand = (Fixtures.pois("ams") + Fixtures.pois("eat")).map { it.place }.distinctBy { it.id }
            .filter { it.tags["brand"] != null }.groupBy { it.tags["brand"]!! }
        // Jamin: five of six branches keep Mo-Sa 09:00-17:00
        val jamin = BrandHours.infer(byBrand.getValue("Jamin").mapNotNull { it.tags["opening_hours"] })!!
        assertEquals("Mo-Sa 09:00-17:00", jamin.hours.raw)
        assertTrue(jamin.agreement > 0.8)
        var inferred = 0
        for ((brand, branches) in byBrand) {
            val h = BrandHours.infer(branches.mapNotNull { it.tags["opening_hours"] }) ?: continue
            inferred++
            // what it says is open on a Saturday afternoon somewhere, and reads back
            assertNotNull(brand, OpeningHours.parseOrNull(h.hours.raw))
            println("$brand: ${h.hours.raw} from ${h.from}, ${"%.0f".format(h.agreement * 100)} % agree")
        }
        assertTrue(inferred >= 8)
        val sat = LocalDateTime.of(2026, 10, 10, 14, 30)
        assertTrue(jamin.hours.isOpen(sat, context = Nowhere) == true)
    }

    @Test
    fun aBranchWithoutAWebsiteShowsTheBrands() {
        fun site(vararg urls: String) = urls.map { mapOf("website" to it) }
        assertEquals("https://jamin.nl", BrandSite.of(site("https://jamin.nl/winkel/amsterdam-nieuwendijk/", "https://jamin.nl/winkel/amsterdam-damrak/"), "NL"))
        assertEquals("https://www.febo.nl", BrandSite.of(site("https://www.febodelekkerste.nl", "https://bestellen.febo.nl/", "https://www.febo.nl/"), "NL"))
        // the country's own site first
        val starbucks = site(
            "https://www.starbucks.co.uk/store-locator/15661-154449/amsterdam-leidsestraat",
            "https://www.starbucks.com/store/87056/nl/utrechtsestraat-9-the-bank",
            "https://www.starbucks.com/store/1234/nl/kalverstraat",
            "https://www.starbucks.nl",
        )
        assertEquals("https://www.starbucks.nl", BrandSite.of(starbucks, "NL"))
        assertEquals("https://www.starbucks.com", BrandSite.of(starbucks, null))
        // a brand:website mapped on a branch wins; social pages say nothing; one page alone is not a brand's
        assertEquals("https://www.ah.nl", BrandSite.of(listOf(mapOf("brand:website" to "https://www.ah.nl/"), mapOf("website" to "https://ah.nl/winkel/1")), "NL"))
        assertNull(BrandSite.of(site("https://www.facebook.com/a", "https://www.facebook.com/b"), "NL"))
        assertNull(BrandSite.of(site("https://pizzeria-one.nl/"), "NL"))
        assertNull(BrandSite.of(site("https://a.nl", "https://b.nl", "https://c.nl"), "NL"))
    }

    /**
     * One branch's page under the country's domain is not the brand's site (the reviewer, 17 November, finding 11: a
     * shopping centre's .nl page became the site of every branch without one); Britain's domain is .uk, not .gb.
     */
    @Test
    fun aCountrysDomainIsTheBrandsOnlyWhereItIsTheBrands() {
        fun site(vararg urls: String) = urls.map { mapOf("website" to it) }
        val inACentre = site("https://www.kalvertoren.nl/winkels/hunkemoller", "https://www.hunkemoller.com/nl/store/1", "https://www.hunkemoller.com/nl/store/2")
        assertEquals("https://www.hunkemoller.com", BrandSite.of(inACentre, "NL"))
        val britain = site(
            "https://www.starbucks.co.uk/store/1", "https://www.starbucks.co.uk/store/2",
            "https://www.starbucks.com/store/3", "https://www.starbucks.com/store/4", "https://www.starbucks.com/store/5",
        )
        assertEquals("https://www.starbucks.co.uk", BrandSite.of(britain, "GB"))
    }

    /**
     * The country's domain is the brand's where it is the brand's own name under it, or what most of the branches that
     * link a site link (the app maintainer, 18 November 2026, batch 4 B24): two branches on one shopping centre's .nl
     * pages beat forty that link the brand's .com.
     */
    @Test
    fun twoPagesOfOneCentreAreNotTheBrandsSite() {
        val centre = listOf(mapOf("website" to "https://www.kalvertoren.nl/winkels/hunkemoller"), mapOf("website" to "https://www.kalvertoren.nl/winkels/hunkemoller-2")) +
            (1..40).map { mapOf("website" to "https://www.hunkemoller.com/nl/store/$it") }
        assertEquals("https://www.hunkemoller.com", BrandSite.of(centre, "NL"))
        // most of them linking a site under the country's domain makes it the brand's there, its name or not
        val local = (1..3).map { mapOf("website" to "https://www.hunkemollerwinkels.nl/$it") } + mapOf("website" to "https://www.hunkemoller.com/nl/store/1")
        assertEquals("https://www.hunkemollerwinkels.nl", BrandSite.of(local, "NL"))
    }

    @Test
    fun freshnessByCheckDates() {
        val today = LocalDate.of(2026, 11, 3)
        val tags = mapOf("opening_hours" to "Mo-Fr 09:00-17:00", "check_date" to "2023-04-26", "check_date:opening_hours" to "2026-08-21", "wheelchair" to "yes")
        val hours = Freshness.of(tags, "opening_hours", today)
        assertEquals(CheckedBy.CHECKED, hours.by)
        assertEquals(LocalDate.of(2026, 8, 21), hours.date)
        assertTrue(!hours.stale)
        // wheelchair access lasts: the place's 2023 check is fresh for it, and would be stale for hours
        assertTrue(!Freshness.of(tags, "wheelchair", today).stale)
        assertTrue(Freshness.of(tags - "check_date:opening_hours", "opening_hours", today).stale)
        // hours never checked nor edited are doubted; a lake's name is not
        assertTrue(Freshness.of(mapOf("opening_hours" to "24/7"), "opening_hours", today).stale)
        assertTrue(!Freshness.of(mapOf("name" to "Sloterplas"), "name", today).stale)
        val edited = Freshness.of(mapOf("opening_hours" to "24/7"), "opening_hours", today, edited = LocalDate.of(2026, 1, 5))
        assertEquals(CheckedBy.EDITED, edited.by)
        assertTrue(!edited.stale)
        // source:date is the source's, not a check; a year or a month reads as its first day
        assertEquals(CheckedBy.NEVER, Freshness.of(mapOf("source:date" to "2014-02-11"), null, today).by)
        assertEquals(LocalDate.of(2017, 1, 1), Freshness.date("2017"))
        assertEquals(LocalDate.of(2025, 3, 1), Freshness.date("2025-03"))
        assertNull(Freshness.date("last spring"))
    }
}
