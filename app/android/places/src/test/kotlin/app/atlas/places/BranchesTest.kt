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
        assertTrue(jamin.hours.isOpen(sat) == true)
    }

    @Test
    fun aBranchWithoutAWebsiteShowsTheBrands() {
        fun site(vararg urls: String) = urls.map { mapOf("website" to it) }
        assertEquals("https://jamin.nl", BrandSite.of(site("https://jamin.nl/winkel/amsterdam-nieuwendijk/", "https://jamin.nl/winkel/amsterdam-damrak/")))
        assertEquals("https://www.febo.nl", BrandSite.of(site("https://www.febodelekkerste.nl", "https://bestellen.febo.nl/", "https://www.febo.nl/")))
        // the country's own site first
        val starbucks = site(
            "https://www.starbucks.co.uk/store-locator/15661-154449/amsterdam-leidsestraat",
            "https://www.starbucks.com/store/87056/nl/utrechtsestraat-9-the-bank",
            "https://www.starbucks.com/store/1234/nl/kalverstraat",
            "https://www.starbucks.nl",
        )
        assertEquals("https://www.starbucks.nl", BrandSite.of(starbucks, "NL"))
        assertEquals("https://www.starbucks.com", BrandSite.of(starbucks))
        // a brand:website mapped on a branch wins; social pages say nothing; one page alone is not a brand's
        assertEquals("https://www.ah.nl", BrandSite.of(listOf(mapOf("brand:website" to "https://www.ah.nl/"), mapOf("website" to "https://ah.nl/winkel/1"))))
        assertNull(BrandSite.of(site("https://www.facebook.com/a", "https://www.facebook.com/b")))
        assertNull(BrandSite.of(site("https://pizzeria-one.nl/")))
        assertNull(BrandSite.of(site("https://a.nl", "https://b.nl", "https://c.nl")))
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
