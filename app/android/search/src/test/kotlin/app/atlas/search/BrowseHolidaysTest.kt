package app.atlas.search

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.CategoryTree
import app.atlas.places.OpeningHoursContext
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A kind's tile, its filters and its time picker read the places' hours where the searcher is, in the [Moment]'s context
 * (the reviewer, 17 November, finding 7): on Christmas Day in Amsterdam a bar shut on public holidays is shut, in the
 * tile's count, the filters' "open now" and the curve of the hours.
 */
class BrowseHolidaysTest {
    private val here = Fixtures.westerkerk
    private val amsterdam = ZoneId.of("Europe/Amsterdam")
    private val nl = OpeningHoursContext.forPlace("NL", amsterdam, here.lat, here.lon)

    /** Christmas Day 2026, a Friday, at 21:00 in Amsterdam; the 26th is a holiday too. */
    private val christmas = ZonedDateTime.of(2026, 12, 25, 21, 0, 0, 0, amsterdam)
    private val drinks = CategoryTree.category("drinks")!!
    private val bars = (1..3).map { i ->
        PlaceRecord.of(
            Place(OsmId(OsmType.NODE, 920_000L + i), LatLon(here.lat + i * 0.001, here.lon), mapOf("amenity" to "bar", "name" to "Bar $i", "opening_hours" to "Mo-Su 16:00-23:00; PH off")),
        )
    }

    @Test
    fun aTileKeepsChristmas() {
        val pulse = Browse.pulses(listOf(drinks), bars, here, Moment(christmas, hours = nl), HoursBook(bars)).single()
        assertEquals(3, pulse.count)
        assertEquals(0, pulse.open)
    }

    @Test
    fun theFiltersAndTheCurveKeepChristmas() {
        val r = Browse.results(drinks, bars, here, Moment(christmas, hours = nl))
        assertEquals(3, r.facets.kept.size)
        assertEquals(0, r.facets.byId(Facets.OPEN)?.value("now")?.count ?: 0)
        // from six on Christmas morning to five the next: none open at any hour
        assertEquals(List(24) { 0 }, r.byHour)
    }
}
