package app.atlas.search

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.CategoryTree
import app.atlas.providers.TagEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AnswersTest {
    private val here = LatLon(52.37, 4.88)
    private val saturday = Moment(Fixtures.saturday)
    private val today: LocalDate = Fixtures.saturday.toLocalDate()

    private fun place(id: Long, vararg tags: Pair<String, String>, source: String = "osm") =
        PlaceRecord.of(Place(OsmId(OsmType.NODE, id), LatLon(52.37 + id * 0.0001, 4.88), tags.toMap(), source = source))

    private fun toilets(): List<PlaceRecord> =
        (1L..6L).map { place(it, "amenity" to "toilets", "fee" to "no", "wheelchair" to "yes") } +
            (7L..8L).map { place(it, "amenity" to "toilets", "fee" to "yes") } +
            place(9, "amenity" to "toilets", "name" to "Westermarkt")

    @Test
    fun aToiletThatDoesNotSayItsFeeIsAskedAsTheToiletsAroundAnswer() {
        val node = CategoryTree.category("toilets")!!
        val results = Browse.results(node, toilets(), here, saturday)
        val hit = results.hits.single { it.record.name() == "Westermarkt" }
        val missing = Answers.missing(hit, results, "en")
        val fee = missing.first { it.key == "fee" }
        assertEquals(TellKey.FEE, fee.tell)
        // free first: six of the eight around are, two are paid
        assertEquals(listOf("no" to 6, "yes" to 2), fee.answers.map { it.value to it.count })
        assertEquals("Free", fee.answers.first().label)
        assertEquals(1.0 * 8 / 9, fee.share, 1e-9)
        // step-free as six say, a changing table asked plainly where none says
        assertEquals("yes", missing.first { it.key == "wheelchair" }.answers.first().value)
        val changing = missing.first { it.key == "changing_table" }
        assertEquals(listOf("yes", "no"), changing.answers.map { it.value })
        assertTrue(changing.answers.all { it.from == AnswerFrom.ASKED })
        // what a toilet already says is not asked
        val known = results.hits.first { it.record.tags["fee"] == "no" }
        assertTrue(Answers.missing(known, results, "en").none { it.key == "fee" || it.key == "wheelchair" })
        // one tap adds it, with what the user saw
        val edit = TagEdits.answer(hit.record, fee.answers.first(), today)!!
        assertEquals(TagEdit(app.atlas.providers.OsmType.NODE, 9, mapOf("fee" to "no"), mapOf("fee" to null)), edit)
    }

    @Test
    fun aBranchWithoutHoursTakesItsBrandsWithOneTap() {
        val ah = arrayOf("shop" to "supermarket", "brand" to "Albert Heijn", "brand:wikidata" to "Q1653985", "name" to "Albert Heijn")
        val week = "Mo-Sa 08:00-22:00; Su 10:00-22:00"
        val pool = (1L..3L).map { place(it, *ah, "opening_hours" to week) } + place(4, *ah)
        val hours = HoursBook(pool)
        val node = CategoryTree.category("food_shops")!!
        val results = Browse.results(node, pool, here, saturday, hours = hours)
        val hit = results.hits.single { "opening_hours" !in it.record.tags }
        val first = Answers.missing(hit, results, "nl", hours).first()
        assertEquals(Answers.HOURS, first.key)
        val answer = first.answers.single()
        assertEquals(AnswerFrom.BRAND, answer.from)
        assertEquals(3, answer.count)
        assertEquals(week, answer.value)
        // added, the hours are new: no check date
        val edit = TagEdits.answer(hit.record, answer, today)!!
        assertEquals(mapOf("opening_hours" to week), edit.set)
    }

    @Test
    fun aValueIsChangedConfirmedOrRemovedWithWhatTheUserSaw() {
        val cafe = place(5, "amenity" to "cafe", "opening_hours" to "Mo-Fr 08:00-18:00", "check_date:opening_hours" to "2021-03-01")
        // changed: the new hours, dated today, over the old
        val changed = TagEdits.set(cafe, "opening_hours", "Mo-Sa 08:00-18:00", today)!!
        assertEquals(mapOf("opening_hours" to "Mo-Sa 08:00-18:00", "check_date:opening_hours" to today.toString()), changed.set)
        assertEquals(mapOf("opening_hours" to "Mo-Fr 08:00-18:00", "check_date:opening_hours" to "2021-03-01"), changed.seen)
        // the same again: still right, dated today
        val same = TagEdits.set(cafe, "opening_hours", "Mo-Fr 08:00-18:00", today)!!
        assertEquals(mapOf("check_date:opening_hours" to today.toString()), same.set)
        assertEquals(same, TagEdits.confirm(cafe, "opening_hours", today))
        // removed, with its date
        assertEquals(mapOf("opening_hours" to null, "check_date:opening_hours" to null), TagEdits.set(cafe, "opening_hours", " ", today)!!.set)
        // hours that do not read are not sent; nor is anything of a place not of OpenStreetMap
        assertNull(TagEdits.set(cafe, "opening_hours", "whenever", today))
        assertNull(TagEdits.confirm(cafe, "wheelchair", today))
        val charger = place(6, "amenity" to "charging_station", source = "ocm")
        assertNull(TagEdits.set(charger, "fee", "no", today))
        assertNotNull(TagEdits.label(cafe, "opening_hours", "en"))
    }
}
