package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class OpeningHoursTest {
    private fun t(s: String): LocalDateTime = LocalDateTime.parse(s)
    private fun oh(s: String) = OpeningHours.parse(s)
    private fun open(hours: String, at: String, ctx: OpeningHoursContext = OpeningHoursContext.Default) = oh(hours).isOpen(t(at), ctx)
    private val nl = OpeningHoursContext(publicHolidays = PublicHolidays.forCountry("NL"))

    @Test
    fun weekdaysAndTimes() {
        val h = oh("Mo-Fr 08:00-18:00")
        val s = h.status(t("2026-10-12T10:00"), context = Nowhere) // a Monday
        assertTrue(s.isOpen)
        assertFalse(s.unknown)
        assertEquals(t("2026-10-12T18:00"), s.changesAt)
        assertEquals(ChangeKind.CLOSES, s.nextChangeKind)
        val sat = h.status(t("2026-10-17T10:00"), context = Nowhere)
        assertFalse(sat.isOpen)
        assertEquals(t("2026-10-19T08:00"), sat.changesAt)
        assertEquals(ChangeKind.OPENS, sat.nextChangeKind)
        assertEquals(false, h.isOpen(t("2026-10-12T18:00"), context = Nowhere))
        assertEquals(true, h.isOpen(t("2026-10-12T17:59"), context = Nowhere))
    }

    @Test
    fun alwaysOpen() {
        val h = oh("24/7")
        assertTrue(h.isAlwaysOpen)
        val s = h.status(t("2026-10-12T03:00"), context = Nowhere)
        assertTrue(s.isOpen)
        assertNull(s.changesAt)
        assertFalse(oh("24/7; PH off").isAlwaysOpen)
        assertEquals(false, open("24/7; PH off", "2026-12-25T12:00", nl))
    }

    @Test
    fun splitShiftsAndOverrides() {
        val h = oh("Mo-Fr 08:00-12:00,13:00-17:30; Sa 10:00-14:00")
        assertEquals(false, h.isOpen(t("2026-10-12T12:30"), context = Nowhere))
        assertEquals(t("2026-10-12T13:00"), h.status(t("2026-10-12T12:30"), context = Nowhere).changesAt)
        assertEquals(true, h.isOpen(t("2026-10-17T11:00"), context = Nowhere))
        // a later rule replaces an earlier one for its days
        assertEquals(false, open("Mo-Fr 10:00-20:00; We 10:00-16:00", "2026-10-14T17:00"))
        assertEquals(true, open("Mo-Fr 10:00-20:00; We 10:00-16:00", "2026-10-13T17:00"))
        assertEquals(false, open("Mo 10:00-12:00; Mo 14:00-16:00", "2026-10-12T11:00"))
        // "," adds
        assertEquals(true, open("Mo 10:00-12:00, Mo 14:00-16:00", "2026-10-12T11:00"))
        assertEquals(true, open("Mo 10:00-12:00, Mo 14:00-16:00", "2026-10-12T15:00"))
    }

    @Test
    fun offClosesOnlyWhatItSelects() {
        for (h in listOf("Mo-Fr 08:00-18:00; We 12:00-14:00 off", "Mo-Fr 08:00-18:00, We 12:00-14:00 off")) {
            assertEquals(h, true, open(h, "2026-10-14T10:00"))
            assertEquals(h, false, open(h, "2026-10-14T13:00"))
            assertEquals(h, true, open(h, "2026-10-14T15:00"))
        }
        assertEquals(false, open("Mo-Fr 10:00-18:00; We closed", "2026-10-14T12:00"))
        assertEquals(false, open("Mo-Fr 10:00-18:00; We Closed", "2026-10-14T12:00"))
    }

    @Test
    fun pastMidnightBelongsToTheDayItStarts() {
        val h = oh("Th 11:00-03:00")
        val s = h.status(t("2026-10-16T01:30"), context = Nowhere) // Friday night
        assertTrue(s.isOpen)
        assertEquals(t("2026-10-16T03:00"), s.changesAt)
        assertEquals(true, open("Fr 22:00-26:00", "2026-10-17T01:00"))
        assertEquals(false, open("Fr 22:00-26:00", "2026-10-17T02:00"))
        // Sunday is closed, but Saturday night runs into it
        val bar = oh("Mo-Sa 18:00-02:00; Su off")
        assertEquals(true, bar.isOpen(t("2026-10-18T01:00"), context = Nowhere))
        assertEquals(false, bar.isOpen(t("2026-10-18T20:00"), context = Nowhere))
        assertEquals(false, bar.isOpen(t("2026-10-19T01:00"), context = Nowhere))
        assertEquals(listOf(OpenSpan(0, 120, OpenState.OPEN)), oh("Fr 22:00-02:00").spans(LocalDate.of(2026, 10, 17), context = Nowhere))
        assertEquals(listOf(OpenSpan(1320, 1440, OpenState.OPEN)), oh("Fr 22:00-02:00").spans(LocalDate.of(2026, 10, 16), context = Nowhere))
        assertEquals(true, open("Mo-Su 00:00-24:00", "2026-10-12T23:59"))
    }

    @Test
    fun publicAndSchoolHolidays() {
        val h = "Mo-Fr 09:00-17:00; PH off"
        assertEquals(false, open(h, "2027-04-27T12:00", nl)) // King's Day, a Tuesday
        assertEquals(true, open(h, "2026-10-13T12:00", nl))
        assertEquals("without a calendar PH never applies", true, open(h, "2027-04-27T12:00"))
        val ph = "Mo-Sa 10:00-18:00; PH 12:00-16:00"
        assertEquals(false, open(ph, "2026-04-06T11:00", nl)) // Easter Monday
        assertEquals(true, open(ph, "2026-04-06T13:00", nl))
        assertEquals(true, open("Mo-Fr 10:00-12:00; PH,Su 14:00-16:00", "2026-12-25T15:00", nl))
        val school = HolidayCalendar.of((12..16).map { Holiday(LocalDate.of(2026, 10, it), "Autumn holiday") })
        val ctx = OpeningHoursContext(schoolHolidays = school)
        val sh = "Mo-Fr 08:00-18:00; SH Mo-Fr 10:00-14:00"
        assertEquals(false, open(sh, "2026-10-13T09:00", ctx))
        assertEquals(true, open(sh, "2026-10-13T11:00", ctx))
        assertEquals(true, open(sh, "2026-10-13T09:00"))
        assertEquals(true, open(sh, "2026-10-20T09:00", ctx))
    }

    @Test
    fun monthsDatesAndEaster() {
        val seasons = "Apr-Oct Mo-Su 10:00-18:00; Nov-Mar Mo-Su 10:00-16:00"
        assertEquals(true, open(seasons, "2026-10-13T17:00"))
        assertEquals(false, open(seasons, "2026-11-03T17:00"))
        assertEquals(true, open(seasons, "2026-11-03T15:00"))
        val xmas = "Mo-Fr 10:00-18:00; Dec 24-26 off"
        assertEquals(false, open(xmas, "2026-12-24T11:00"))
        assertEquals(false, open(xmas, "2026-12-25T11:00"))
        assertEquals(true, open(xmas, "2026-12-28T11:00"))
        val wrap = "Mo-Su 10:00-18:00; Dec 24-Jan 02 off"
        assertEquals(false, open(wrap, "2027-01-01T12:00"))
        assertEquals(false, open(wrap, "2026-12-31T12:00"))
        assertEquals(true, open(wrap, "2027-01-03T12:00"))
        val list = "Mo-Su 10:00-18:00; Dec 25,26 off; Jan 01 off"
        assertEquals(false, open(list, "2026-12-26T12:00"))
        assertEquals(false, open(list, "2027-01-01T12:00"))
        assertEquals(true, open(list, "2026-12-27T12:00"))
        val dated = "Mo-Su 10:00-18:00; 2026 Dec 24 off"
        assertEquals(false, open(dated, "2026-12-24T12:00"))
        assertEquals(true, open(dated, "2027-12-24T12:00"))
        val easter = "Mo-Su 10:00-18:00; easter off; easter +1 day off"
        assertEquals(false, open(easter, "2026-04-05T12:00"))
        assertEquals(false, open(easter, "2026-04-06T12:00"))
        assertEquals(true, open(easter, "2026-04-07T12:00"))
        val season = oh("Apr-Oct 10:00-18:00")
        assertEquals(t("2027-04-01T10:00"), season.status(t("2026-11-15T12:00"), context = Nowhere).changesAt)
        assertEquals(true, open("Mar 01+ Mo-Su 09:00-17:00", "2026-03-02T12:00"))
        assertEquals(false, open("Mar 01+ Mo-Su 09:00-17:00", "2026-02-27T12:00"))
        assertEquals(true, open("2026-2028 Mo 10:00-12:00", "2026-10-12T11:00"))
        assertEquals(false, open("2027+ Mo 10:00-12:00", "2026-10-12T11:00"))
        assertEquals(true, open("Dec 1-Mar 1 Mo-Fr 10:00-19:00", "2027-02-03T18:30"))
    }

    @Test
    fun weeksAndNthWeekdays() {
        assertEquals(true, open("Su[1] 10:00-12:00", "2026-10-04T11:00"))
        assertEquals(false, open("Su[1] 10:00-12:00", "2026-10-11T11:00"))
        assertEquals(true, open("Su[-1] 10:00-12:00", "2026-10-25T11:00"))
        assertEquals(false, open("Su[-1] 10:00-12:00", "2026-10-18T11:00"))
        assertEquals(true, open("Mo[1,3] 10:00-12:00", "2026-10-19T11:00"))
        assertEquals(false, open("Mo[1,3] 10:00-12:00", "2026-10-12T11:00"))
        assertEquals(true, open("Mo[2-3] 10:00-12:00", "2026-10-12T11:00"))
        // the day after the last Saturday of October 2026 (the 31st) is 1 November
        assertEquals(true, open("Sa[-1] +1 day 10:00-12:00", "2026-11-01T11:00"))
        assertEquals(false, open("Sa[-1] +1 day 10:00-12:00", "2026-10-25T11:00"))
        assertEquals(true, open("Dec Su[-1] 10:00-12:00", "2026-12-27T11:00"))
        assertEquals(false, open("Dec Su[-1] 10:00-12:00", "2026-11-29T11:00"))
        assertEquals(true, open("week 42 Mo 10:00-12:00", "2026-10-12T11:00"))
        assertEquals(false, open("week 42 Mo 10:00-12:00", "2026-10-19T11:00"))
        assertEquals(true, open("week 01-53/2 Mo 10:00-12:00", "2026-10-05T11:00")) // week 41
        assertEquals(false, open("week 01-53/2 Mo 10:00-12:00", "2026-10-12T11:00"))
        assertEquals(true, open("week 1,42 Mo 10:00-12:00", "2026-10-12T11:00"))
    }

    @Test
    fun sunTimes() {
        // the fixed clock: dawn 05:30, sunrise 06:00, sunset 18:00, dusk 18:30
        assertEquals(true, open("sunrise-sunset", "2026-10-12T07:00"))
        assertEquals(false, open("sunrise-sunset", "2026-10-12T19:00"))
        assertEquals(true, open("dawn-dusk", "2026-10-12T18:15"))
        val shifted = "(sunrise+01:00)-(sunset-01:00)"
        assertEquals(false, open(shifted, "2026-10-12T06:30"))
        assertEquals(true, open(shifted, "2026-10-12T07:30"))
        assertEquals(false, open(shifted, "2026-10-12T17:30"))
        // the real sun over Amsterdam: sunset in mid-October is around 18:45 local time
        val ams = OpeningHoursContext(sun = SunClock.at(52.3746, 4.8840, java.time.ZoneId.of("Europe/Amsterdam")))
        val s = oh("sunrise-sunset").status(t("2026-10-13T12:00"), ams)
        assertTrue(s.isOpen)
        val sunset = s.changesAt!!
        assertTrue("sunset $sunset", sunset.hour == 18 && sunset.minute in 30..59)
    }

    @Test
    fun fallbackRulesFillOnlyTheGaps() {
        val h = oh("Mo-Fr 08:00-12:00 || \"by appointment\"")
        assertEquals(true, h.isOpen(t("2026-10-12T10:00"), context = Nowhere))
        val after = h.status(t("2026-10-12T14:00"), context = Nowhere)
        assertTrue(after.unknown)
        assertEquals("by appointment", after.comment)
        assertNull(h.isOpen(t("2026-10-17T10:00"), context = Nowhere))
    }

    @Test
    fun commentsAndUnknown() {
        val alone = oh("\"by appointment\"").status(t("2026-10-12T10:00"), context = Nowhere)
        assertTrue(alone.unknown)
        assertEquals("by appointment", alone.comment)
        val withTimes = oh("Mo-Fr 10:00-20:00 \"call ahead\"").status(t("2026-10-12T11:00"), context = Nowhere)
        assertTrue(withTimes.isOpen)
        assertFalse(withTimes.unknown)
        assertEquals("call ahead", withTimes.comment)
        val mixed = oh("Tu-Fr \"on request\"; Sa-Su 13:00-17:00")
        assertNull(mixed.isOpen(t("2026-10-13T10:00"), context = Nowhere))
        assertEquals(true, mixed.isOpen(t("2026-10-17T14:00"), context = Nowhere))
        assertNull(open("Mo-Fr 10:00-12:00 unknown", "2026-10-12T11:00"))
        val named = oh("Mo 09:00-13:00 open \"Brocante\"; Sa 09:00-16:00 open \"Organic market\"")
        assertEquals("Organic market", named.status(t("2026-10-17T10:00"), context = Nowhere).comment)
        assertEquals(OpenStatus.Unknown, OpeningHours.statusOf("not hours", t("2026-10-12T10:00"), context = Nowhere))
    }

    @Test
    fun openEnds() {
        val h = oh("Mo-Su 17:00+")
        val s = h.status(t("2026-10-12T18:00"), context = Nowhere)
        assertTrue(s.isOpen)
        assertTrue(s.openEnd)
        assertEquals(false, h.isOpen(t("2026-10-12T16:00"), context = Nowhere))
        val lastOrders = oh("Mo-Sa 18:00-22:00+").status(t("2026-10-12T19:00"), context = Nowhere)
        assertTrue(lastOrders.isOpen)
        assertTrue(lastOrders.openEnd)
        val plain = oh("Mo-Sa 18:00-22:00").status(t("2026-10-12T19:00"), context = Nowhere)
        assertFalse(plain.openEnd)
    }

    /**
     * An open end after a stated end lasts two hours (the app maintainer's rule, 18 November 2026), so a bar's hours are
     * no longer open around the clock, nor on its closed day. Inside the guessed tail the place is open, with no closing
     * time it knows (the reviewer, 17 November 2026: an end after midnight was taken for one by day, and its tail ran to
     * the next midnight).
     */
    @Test
    fun anOpenEndAfterMidnightEndsInTheNight() {
        // the reviewer's three, on a Monday at noon (16 November 2026)
        for ((hours, opens) in listOf(
            "Mo-Su 18:00-24:00+" to "2026-11-16T18:00",
            "Mo-Su 17:00-01:00+" to "2026-11-16T17:00",
            "Tu-Su 16:00-02:00+" to "2026-11-17T16:00",
        )) {
            val s = oh(hours).status(t("2026-11-16T12:00"), context = Nowhere)
            assertFalse("$hours at noon", s.isOpen)
            assertEquals(hours, ChangeKind.OPENS, s.nextChangeKind)
            assertEquals(hours, t(opens), s.changesAt)
        }
        // the night after: open, an open end, two hours past the stated end
        val night = oh("Mo-Su 18:00-24:00+").status(t("2026-11-17T01:00"), context = Nowhere)
        assertTrue(night.isOpen)
        assertTrue(night.openEnd)
        assertEquals(t("2026-11-17T02:00"), night.changesAt)
        assertEquals(false, oh("Mo-Su 18:00-24:00+").isOpen(t("2026-11-17T03:00"), context = Nowhere))
        // Sunday's night ends on Monday morning, the closed day
        val sunday = oh("Tu-Su 16:00-02:00+")
        assertTrue(sunday.status(t("2026-11-16T03:00"), context = Nowhere).let { it.isOpen && it.openEnd })
        assertEquals(false, sunday.isOpen(t("2026-11-16T04:00"), context = Nowhere))
        assertEquals(listOf(OpenSpan(0, 120, OpenState.OPEN), OpenSpan(120, 240, OpenState.OPEN, openEnd = true)), sunday.spans(LocalDate.of(2026, 11, 16), context = Nowhere))
        // a start alone, by its hour: night, before four, early, evening, day
        assertEquals(listOf(480, 360, 240, 1140, 300, 600), listOf(22 * 60, 24 * 60, 26 * 60, 5 * 60, 17 * 60, 14 * 60).map { TimeSpan.openEndGuess(it) })
        assertEquals(TimeSpan.STATED_TAIL, 120)
        // as before for an open end with no stated end
        assertEquals(false, oh("Mo-Su 17:00+").isOpen(t("2026-11-16T22:00"), context = Nowhere))
        assertEquals(true, oh("Mo-Su 22:00+").isOpen(t("2026-11-17T05:59"), context = Nowhere))
    }

    @Test
    fun anOpenEndStatesNoClosingTime() {
        val bar = oh("Tu-Su 16:00-02:00+")
        // inside the stated hours and in the tail alike, the closing time is a guess, and none is stated
        for (at in listOf("2026-11-17T20:00", "2026-11-18T03:00")) {
            val s = bar.status(t(at), context = Nowhere)
            assertTrue(at, s.isOpen && s.openEnd && s.changesAt != null)
            assertNull(at, s.statedChangeAt)
        }
        // closed, or open to a stated end: the change as before
        assertEquals(t("2026-11-17T16:00"), bar.status(t("2026-11-16T12:00"), context = Nowhere).statedChangeAt)
        assertEquals(t("2026-11-16T18:00"), oh("Mo-Su 09:00-18:00").status(t("2026-11-16T12:00"), context = Nowhere).statedChangeAt)
        // a day's spans as stated: the tail joined to the stretch it follows, or alone with no end
        assertEquals(listOf(StatedSpan(960, 1560, OpenState.OPEN, openEnd = true)), statedSpans(bar.weekTable(weekOf = null, context = Nowhere)[6]))
        assertEquals(listOf(StatedSpan(0, 120, OpenState.OPEN, openEnd = true)), statedSpans(bar.spans(LocalDate.of(2026, 11, 16), context = Nowhere)))
        assertEquals(listOf(StatedSpan(600, 840, OpenState.OPEN), StatedSpan(1020, null, OpenState.OPEN, openEnd = true)), statedSpans(oh("Mo 10:00-14:00,17:00+").weekTable(weekOf = null, context = Nowhere)[0]))
        assertEquals(listOf(StatedSpan(540, 1020, OpenState.OPEN)), statedSpans(oh("Mo-Fr 09:00-17:00").weekTable(weekOf = null, context = Nowhere)[0]))
    }

    /**
     * How long an open end lasts, as the app maintainer decided (18 November 2026, batch 4, A1): a start alone by its
     * hour (before 04:00 to six, from 04:00 to midnight, from 17:00 five hours, from 22:00 to six the next morning),
     * and after a stated end two hours, whatever the hour. An early start was open for minutes (`Mo-Sa 05:30+` till
     * six), and an end after six filled the day (`Fr,Sa 23:00-07:00+` open all Saturday).
     */
    /**
     * A start by sunrise or dawn is a morning's, whatever its minute (the app maintainer, 18 November 2026, batch 5 A7):
     * open to midnight, and all day where the sun does not set or the light does not go. Before 04:00 (Stockholm,
     * Helsinki and Oslo in June) it closed at six as a written early start does, and in polar day it was open from
     * midnight to six. A written `03:30+` and the evening's starts stay as decided.
     */
    @Test
    fun aStartBySunriseOrDawnIsOpenTillMidnight() {
        fun sun(lat: Double, lon: Double, zone: String) = OpeningHoursContext(sun = SunClock.at(lat, lon, java.time.ZoneId.of(zone)))
        // 21 June 2026: Stockholm's dawn 01:58, sunrise 03:30; Helsinki's 02:01 and 03:54; Oslo's 02:09 and 03:53
        val stockholm = sun(59.3293, 18.0686, "Europe/Stockholm")
        val helsinki = sun(60.1699, 24.9384, "Europe/Helsinki")
        val oslo = sun(59.9139, 10.7522, "Europe/Oslo")
        for (ctx in listOf(stockholm, helsinki, oslo)) for (h in listOf("sunrise+", "dawn+", "Mo-Su sunrise+", "(sunrise-00:30)+")) {
            assertEquals(h, true, oh(h).isOpen(t("2026-06-21T12:00"), ctx))
            assertEquals(h, true, oh(h).isOpen(t("2026-06-21T23:59"), ctx))
            // to midnight, not past it
            assertEquals(h, false, oh(h).isOpen(t("2026-06-22T00:30"), ctx))
        }
        val s = oh("sunrise+").status(t("2026-06-21T12:00"), stockholm)
        assertTrue(s.isOpen && s.openEnd)
        assertNull(s.statedChangeAt)
        assertEquals(t("2026-06-22T00:00"), s.changesAt)
        // where the sun does not set (Tromsø's polar day) or the light does not go (Trondheim's white night): all day
        val tromso = sun(69.6492, 18.9553, "Europe/Oslo")
        val trondheim = sun(63.4305, 10.3951, "Europe/Oslo")
        assertEquals(true, oh("sunrise+").isOpen(t("2026-06-21T12:00"), tromso))
        assertEquals(true, oh("sunrise+").isOpen(t("2026-06-21T23:59"), tromso))
        assertEquals(true, oh("dawn+").isOpen(t("2026-06-21T12:00"), tromso))
        assertEquals(true, oh("dawn+").isOpen(t("2026-06-21T12:00"), trondheim))
        // as decided before: a written early start, the evening's start, polar night, a sunset that never comes
        assertEquals(true, oh("03:30+").isOpen(t("2026-06-21T05:59"), stockholm))
        assertEquals(false, oh("03:30+").isOpen(t("2026-06-21T06:00"), stockholm))
        assertEquals(true, oh("sunset+").isOpen(t("2026-06-22T05:59"), stockholm))
        assertEquals(false, oh("sunset+").isOpen(t("2026-06-22T06:00"), stockholm))
        assertEquals(false, oh("sunrise+").isOpen(t("2026-12-22T12:00"), tromso))
        assertEquals(false, oh("sunset+").isOpen(t("2026-06-22T00:30"), tromso))
        // a bare sunrise or dawn, with no +, is a point in time, as 17:00 is: not the morning (19 November 2026,
        // batch 6 B9: read as one, open till midnight, it passed every test)
        for (h in listOf("sunrise", "dawn", "Mo-Su sunrise")) assertEquals(h, false, oh(h).isOpen(t("2026-06-21T12:00"), stockholm))
    }

    @Test
    fun anOpenEndLastsAsDecided() {
        // a start alone: early, by day, in the evening, at night (Monday 16 November 2026)
        val early = oh("Mo-Sa 05:30+")
        assertEquals(true, early.isOpen(t("2026-11-16T12:00"), context = Nowhere))
        assertEquals(true, early.isOpen(t("2026-11-16T23:59"), context = Nowhere))
        assertEquals(false, early.isOpen(t("2026-11-17T00:00"), context = Nowhere))
        assertEquals(true, oh("Mo-Su 03:00+").isOpen(t("2026-11-16T05:59"), context = Nowhere))
        assertEquals(false, oh("Mo-Su 03:00+").isOpen(t("2026-11-16T06:00"), context = Nowhere))
        assertEquals(true, oh("Mo-Su 18:00+").isOpen(t("2026-11-16T22:59"), context = Nowhere))
        assertEquals(false, oh("Mo-Su 18:00+").isOpen(t("2026-11-16T23:00"), context = Nowhere))
        assertEquals(true, oh("Mo-Su 22:00+").isOpen(t("2026-11-17T05:59"), context = Nowhere))
        assertEquals(false, oh("Mo-Su 22:00+").isOpen(t("2026-11-17T06:00"), context = Nowhere))
        // the sun rises before six in an Amsterdam June: open till midnight
        val amsterdam = OpeningHoursContext(sun = SunClock.at(52.3731, 4.8926, java.time.ZoneId.of("Europe/Amsterdam")))
        assertEquals(true, oh("sunrise+").isOpen(t("2026-06-21T12:00"), amsterdam))
        assertEquals(true, oh("sunrise+").isOpen(t("2026-06-21T23:00"), amsterdam))
        // after a stated end, two hours, whatever the hour: open with no stated end till then, closed from then
        for ((hours, ends) in listOf(
            "Mo-Su 18:00-22:00+" to "2026-11-17T00:00",
            "Mo-Su 20:00-02:00+" to "2026-11-17T04:00",
            "Mo-Su 18:00-24:00+" to "2026-11-17T02:00",
            "Fr,Sa 23:00-07:00+" to "2026-11-21T09:00",
            "Mo-Su 22:00-06:00+" to "2026-11-17T08:00",
            "Mo-Fr 09:00-17:00+" to "2026-11-16T19:00",
        )) {
            val end = t(ends)
            val tail = oh(hours).status(end.minusMinutes(1), context = Nowhere)
            assertTrue("$hours just before $ends", tail.isOpen && tail.openEnd)
            assertEquals(hours, end, tail.changesAt)
            assertEquals("$hours at $ends", false, oh(hours).isOpen(end, context = Nowhere))
        }
        // neither all Saturday nor around the clock
        assertEquals(false, oh("Fr,Sa 23:00-07:00+").isOpen(t("2026-11-21T12:00"), context = Nowhere))
        assertEquals(false, oh("Mo-Su 22:00-06:00+").isOpen(t("2026-11-17T12:00"), context = Nowhere))
    }

    @Test
    fun weekTableAndSpans() {
        val h = oh("Mo-Th 09:00-17:00; Fr 09:00-12:00,22:00-02:00; PH off; Dec 25 off")
        val week = h.weekTable(weekOf = null, context = Nowhere)
        assertEquals(7, week.size)
        assertEquals(listOf(OpenSpan(540, 1020, OpenState.OPEN)), week[0])
        assertEquals(listOf(OpenSpan(540, 720, OpenState.OPEN), OpenSpan(1320, 1560, OpenState.OPEN)), week[4])
        assertTrue(week[5].isEmpty() && week[6].isEmpty())
        assertEquals("02:00", week[4][1].endTime.toString())
        // a real week: Christmas on the Friday
        val xmas = h.weekTable(LocalDate.of(2026, 12, 23), nl)
        assertTrue(xmas[4].isEmpty())
        val dial = h.spans(LocalDate.of(2026, 10, 17), context = Nowhere)
        assertEquals(listOf(OpenSpan(0, 120, OpenState.OPEN)), dial)
        val unknown = oh("Mo-Fr 08:00-12:00 || \"by appointment\"").spans(LocalDate.of(2026, 10, 12), context = Nowhere)
        assertEquals(3, unknown.size)
        assertEquals(OpenState.UNKNOWN, unknown[0].state)
        assertEquals("by appointment", unknown[0].comment)
    }

    @Test
    fun forgivingSyntax() {
        val variants = listOf(
            "mo-fr 08:00-18:00", "Mo - Fr 08:00 - 18:00", "Mon-Fri 08:00-18:00", "Mo-Fr: 08:00-18:00", "Mo-Fr 08.00-18.00",
            "Mo-Fr 8h-18h", "Mo-Fr 8:00-18:00", "Mo–Fr 08:00–18:00", "Mo-Fr 08:00-18:00;", "Ma-Vr 08:00-18:00",
            "Mo-Fr 08:00-18:00h", "Mo-Fr 08:00 - 12:00 12:00-18:00", "Mo-Fr 08:00-12:00, 12:00-18:00",
        )
        for (v in variants) {
            val h = OpeningHours.parseOrNull(v)
            assertNotNull(v, h)
            assertEquals(v, true, h!!.isOpen(t("2026-10-12T09:00"), context = Nowhere))
            assertEquals(v, false, h.isOpen(t("2026-10-12T19:00"), context = Nowhere))
            assertEquals(v, false, h.isOpen(t("2026-10-17T09:00"), context = Nowhere))
        }
        // a missing ";" between rules
        val missing = oh("Mo-Fr 08:30-17:00 Sa 09:00-12:00")
        assertEquals(true, missing.isOpen(t("2026-10-17T10:00"), context = Nowhere))
        assertEquals(true, open("Mo Closed; Tu-We 11:00-22:00 ; Th-Sa 11:00-24:00", "2026-10-13T12:00"))
        assertEquals(true, open("Su, Th 18:00-01:00", "2026-10-16T00:30"))
        assertEquals(true, open("Mo-Su,PH 00:00-24:00+", "2026-10-12T05:00"))
        assertEquals(true, open("Su-Th 11:00-01:00, Fr-Sa 11:00-27:00", "2026-10-18T02:30"))
        assertEquals(true, open("We-Su", "2026-10-14T12:00"))
        assertEquals(false, open("We-Su", "2026-10-13T12:00"))
    }

    @Test
    fun commonShapesFromAroundTheWorld() {
        val shapes = listOf(
            "Mo-Fr 09:00-12:00,14:00-18:00; Sa 09:00-12:00; PH off", "Mo-Fr 08:00-20:00; Sa 08:00-18:00; Su,PH off",
            "Mo-Su 09:00-21:00; Dec 25 off; Jan 1 off", "Mo-Fr 07:30-12:00, 13:00-17:00", "Mo-Su sunrise-sunset", "dawn-dusk",
            "Mo-Fr 09:00-17:00; Sa,Su closed", "Mo-Fr 08:00-17:00; \"closed in school holidays\"", "Jan-Dec Mo-Fr 08:00-17:00",
            "May-Sep: Mo-Su 10:00-18:00", "Apr 01-Oct 31 Mo-Su 09:00-19:00; Nov 01-Mar 31 Mo-Su 10:00-17:00",
            "Mo-Fr 06:00-22:00; Sa 08:00-22:00; Su 09:00-22:00 || \"call ahead\"", "Mo,We,Fr 09:00-12:00",
            "Tu-Su 10:00-17:00; Mo off; easter,Dec 25 off", "week 01-52/2 Fr 18:00-22:00", "24/7 \"vending machine\"", "off", "closed",
            "Mo-Fr 08:00-12:00 \"by appointment\", Mo-Fr 13:00-17:00", "Mo-Fr 08:00-17:00 open; Sa-Su off", "PH", "SH off",
            "Mo-Fr: 9:00-18:00; Sa: 10:00-16:00", "Mo.-Fr. 9:00-18:00", "Mo-Fr 9.00-18.00 Uhr", "Mo-Fr 09:00-18:00, Sa 10:00-14:00.",
            "Mo-Sa 10:00-19:00; Su,PH 11:00-17:00; Dec 25,26 off; Jan 01 off", "Mo-Fr 08:00-18:00; Mo-Fr 12:00-13:00 off",
            "Mo-Fr (sunrise+00:30)-(sunset-00:30)", "Su[2,4] 10:00-14:00", "2026 Jan 01-2026 Mar 31 off", "Mo-Fr 07:00-19:00; PH +1 day off",
            "Mo-Fr 10:00-16:00/01:00", "Mo-Fr 08:00-16:00; Jul-Aug Mo-Fr 08:00-12:00", "Mo-Fr 10:00-18:00; Dec 24 10:00-14:00",
        )
        val broken = shapes.filter { OpeningHours.parseOrNull(it) == null }
        assertTrue("not read: $broken", broken.isEmpty())
        assertEquals(false, open("off", "2026-10-12T12:00"))
        assertEquals(true, open("Mo.-Fr. 9:00-18:00", "2026-10-12T12:00"))
        assertEquals(true, open("Mo-Fr 10:00-18:00; Dec 24 10:00-14:00", "2026-12-24T13:00"))
        assertEquals(false, open("Mo-Fr 10:00-18:00; Dec 24 10:00-14:00", "2026-12-24T15:00"))
    }

    @Test
    fun pointsInTime() {
        val post = oh("Mo-Fr 17:00; Sa 12:00")
        assertEquals(true, post.isOpen(t("2026-10-12T17:00"), context = Nowhere))
        assertEquals(false, post.isOpen(t("2026-10-12T17:01"), context = Nowhere))
        assertEquals(t("2026-10-17T12:00"), post.status(t("2026-10-16T18:00"), context = Nowhere).changesAt)
    }

    @Test
    fun errorsSayWhere() {
        for (bad in listOf("", "Mo-Fr 25:00-26:00", "Mo-Fr 08:00-", "Xy 10:00-12:00", "Th-Sa 18:00-0:700", "Mo-Fr 08:61-09:00", "Mo[7] 10:00-12:00", "\"unclosed")) {
            assertNull(bad, OpeningHours.parseOrNull(bad))
        }
        try {
            OpeningHours.parse("Mo-Fr 08:00-12:00; Sa 10:00-1x:00")
            fail()
        } catch (e: OpeningHoursException) {
            assertTrue(e.message, e.index > 20)
        }
    }

    @Test
    fun aWeekdayAndAHolidayWithoutAComma() {
        // "Su PH" is "Su,PH": it used to read as "Su" open all day, then a separate PH rule
        val h = oh("Su PH 10:00-14:00")
        assertEquals(false, h.isOpen(t("2026-10-18T09:00"), nl))
        assertEquals(true, h.isOpen(t("2026-10-18T11:00"), nl))
        assertEquals(true, h.isOpen(t("2026-12-25T11:00"), nl))
        assertEquals(false, h.isOpen(t("2026-10-19T11:00"), nl))
        // "Mo-Fr SH" is "SH Mo-Fr": both must hold
        val school = OpeningHoursContext(schoolHolidays = HolidayCalendar.of((12..16).map { Holiday(LocalDate.of(2026, 10, it), "Autumn") }))
        val sh = oh("Mo-Fr SH 10:00-14:00")
        assertEquals(true, sh.isOpen(t("2026-10-12T11:00"), school))
        assertEquals(false, sh.isOpen(t("2026-10-12T09:00"), school))
        assertEquals(false, sh.isOpen(t("2026-10-19T11:00"), school))
    }

    @Test
    fun twelveHourClock() {
        for (v in listOf("Mo-Fr 9am-5pm", "Mo-Fr 9:00 AM - 5:00 PM", "Mo-Fr 9 am - 5 pm", "Mo-Fr 09:00am-05:00pm")) {
            val h = OpeningHours.parseOrNull(v)
            assertNotNull(v, h)
            assertEquals(v, false, h!!.isOpen(t("2026-10-12T08:59"), context = Nowhere))
            assertEquals(v, true, h.isOpen(t("2026-10-12T09:00"), context = Nowhere))
            assertEquals(v, true, h.isOpen(t("2026-10-12T16:59"), context = Nowhere))
            assertEquals(v, false, h.isOpen(t("2026-10-12T17:00"), context = Nowhere))
        }
        // 12 pm is noon and 12 am midnight
        assertEquals(listOf(OpenSpan(720, 1440, OpenState.OPEN)), oh("Sa 12pm-12am").spans(LocalDate.of(2026, 10, 17), context = Nowhere))
        assertEquals(listOf(OpenSpan(0, 360, OpenState.OPEN)), oh("Su 12am-6am").spans(LocalDate.of(2026, 10, 18), context = Nowhere))
        assertNull(OpeningHours.parseOrNull("Mo 13pm-14pm"))
        assertNull(OpeningHours.parseOrNull("Mo 0am-5am"))
    }

    @Test
    fun allDayAfterOtherSelectors() {
        val summer = oh("Jun-Aug 24/7")
        assertFalse(summer.isAlwaysOpen)
        assertEquals(true, summer.isOpen(t("2026-07-03T03:00"), context = Nowhere))
        assertEquals(false, summer.isOpen(t("2026-10-13T12:00"), context = Nowhere))
        assertEquals(t("2027-06-01T00:00"), summer.status(t("2026-10-13T12:00"), context = Nowhere).changesAt)
        val weekdays = oh("Mo-Fr 24/7; Sa 10:00-16:00")
        assertEquals(true, weekdays.isOpen(t("2026-10-12T03:00"), context = Nowhere))
        assertEquals(false, weekdays.isOpen(t("2026-10-17T09:00"), context = Nowhere))
        assertEquals(true, weekdays.isOpen(t("2026-10-17T11:00"), context = Nowhere))
        assertEquals(true, open("Mo-Fr: 24/7", "2026-10-14T23:00"))
        assertTrue(oh("24/7").isAlwaysOpen)
    }

    @Test
    fun aWeeklyPatternIsSearchedOneWeekAhead() {
        // nothing ever changes: no next change (it used to walk 400 days to find out)
        assertNull(oh("24/7").status(t("2026-10-12T10:00"), context = Nowhere).changesAt)
        val off = oh("off").status(t("2026-10-12T10:00"), context = Nowhere)
        assertFalse(off.isOpen)
        assertNull(off.changesAt)
        // the furthest change a weekly pattern can have is just under a week away
        assertEquals(t("2026-10-25T10:00"), oh("Su 10:00-12:00").status(t("2026-10-18T12:30"), context = Nowhere).changesAt)
        assertEquals(t("2026-10-25T22:00"), oh("Su 22:00-02:00").status(t("2026-10-19T03:00"), context = Nowhere).changesAt)
        // dated rules still look up to a year ahead
        assertEquals(t("2027-12-25T10:00"), oh("Dec 25 10:00-12:00").status(t("2026-12-25T13:00"), context = Nowhere).changesAt)
    }

    @Test
    fun wholeYearSelectorsStillMakeAWeeklyPlaque() {
        // "Jan-Dec" and "week 01-53" select every day: the door-plaque table used to come out empty
        for (v in listOf("Jan-Dec Mo-Fr 08:00-17:00", "week 01-53 Mo-Fr 08:00-17:00", "Dec-Nov Mo-Fr 08:00-17:00")) {
            val week = oh(v).weekTable(weekOf = null, context = Nowhere)
            assertEquals(v, listOf(OpenSpan(480, 1020, OpenState.OPEN)), week[0])
            assertTrue(v, week[5].isEmpty())
        }
        // a season is still a dated rule
        assertTrue(oh("Apr-Oct Mo-Fr 08:00-17:00").weekTable(weekOf = null, context = Nowhere)[0].isEmpty())
    }

    @Test
    fun the29thOfFebruaryInACommonYear() {
        // a range ending on 29 February ends on the 28th in a common year; it used to drop the whole range
        val winter = "Mo-Su 10:00-18:00; Nov 01-Feb 29 off"
        assertEquals(false, open(winter, "2027-02-28T12:00"))
        assertEquals(false, open(winter, "2026-11-15T12:00"))
        assertEquals(true, open(winter, "2027-03-01T12:00"))
        assertEquals(false, open(winter, "2028-02-29T12:00"))
        // a range from 29 February starts on 1 March; the day alone does not exist
        assertEquals(true, open("Feb 29-Mar 05 10:00-12:00", "2027-03-01T11:00"))
        assertEquals(false, open("Feb 29 10:00-12:00", "2027-03-01T11:00"))
        assertEquals(false, open("Feb 29 10:00-12:00", "2027-02-28T11:00"))
        assertEquals(true, open("Feb 29 10:00-12:00", "2028-02-29T11:00"))
    }

    @Test
    fun aSunEventThatDoesNotHappen() {
        // the reviewer, 17 November, finding 11: an event the sun does not make that day shut the whole day
        val oslo = java.time.ZoneId.of("Europe/Oslo")
        // Trondheim at midsummer: the sun sets, but never 6° below the horizon; dawn and dusk do not come, light all night
        val trondheim = OpeningHoursContext(sun = SunClock.at(63.4305, 10.3951, oslo))
        assertEquals(true, oh("dawn-dusk").isOpen(t("2026-06-21T01:00"), trondheim))
        assertEquals(true, oh("dawn-dusk").isOpen(t("2026-06-21T12:00"), trondheim))
        assertEquals(false, oh("dusk-dawn").isOpen(t("2026-06-21T01:00"), trondheim))
        // its sunrise and sunset still happen
        assertEquals(false, oh("sunrise-sunset").isOpen(t("2026-06-21T01:00"), trondheim))
        assertEquals(true, oh("sunrise-sunset").isOpen(t("2026-06-21T12:00"), trondheim))
        // Tromsø: polar day in June, the sun up all day; polar night in December
        val tromso = OpeningHoursContext(sun = SunClock.at(69.6492, 18.9553, oslo))
        assertEquals(true, oh("sunrise-sunset").isOpen(t("2026-06-21T02:00"), tromso))
        assertEquals(true, oh("(sunrise+01:00)-(sunset-01:00)").isOpen(t("2026-06-21T00:30"), tromso))
        assertEquals(true, oh("10:00-sunset").isOpen(t("2026-06-21T23:30"), tromso))
        assertEquals(false, oh("sunset-sunrise").isOpen(t("2026-06-21T02:00"), tromso))
        assertEquals(false, oh("sunset-sunrise").isOpen(t("2026-06-21T12:00"), tromso))
        assertEquals(false, oh("sunrise-sunset").isOpen(t("2026-12-21T12:00"), tromso))
        assertEquals(true, oh("sunset-sunrise").isOpen(t("2026-12-21T12:00"), tromso))
        // open all day says no closing at midnight: the next day is the same
        val light = oh("dawn-dusk").status(t("2026-06-21T23:00"), trondheim)
        assertTrue(light.isOpen)
        assertTrue("changes ${light.changesAt}", light.changesAt == null || light.changesAt!!.isAfter(t("2026-06-28T00:00")))
        // where the sun keeps its times, nothing changes: Amsterdam in October
        val ams = OpeningHoursContext(sun = SunClock.at(52.3746, 4.8840, java.time.ZoneId.of("Europe/Amsterdam")))
        assertEquals(false, oh("dawn-dusk").isOpen(t("2026-10-13T03:00"), ams))
        assertEquals(true, oh("dawn-dusk").isOpen(t("2026-10-13T12:00"), ams))
    }

    /**
     * A start the sun does not make that day never opens (the app maintainer, 18 November 2026, batch 4 B19): `sunset+`
     * in Tromsø's polar day read the evening that never came as midnight and was open from 0:00 to 6:00; `sunrise+` in
     * its polar night likewise. Where the sun sets, it opens as before.
     */
    @Test
    fun aStartTheSunDoesNotMakeNeverOpens() {
        val tromso = OpeningHoursContext(sun = SunClock.at(69.6492, 18.9553, java.time.ZoneId.of("Europe/Oslo")))
        assertEquals(false, oh("sunset+").isOpen(t("2026-06-22T00:30"), tromso))
        assertEquals(false, oh("sunset+").isOpen(t("2026-06-22T05:00"), tromso))
        assertEquals(false, oh("Mo-Su sunset+").isOpen(t("2026-06-23T01:00"), tromso))
        assertEquals(false, oh("sunrise+").isOpen(t("2026-12-22T00:30"), tromso))
        val ams = OpeningHoursContext(sun = SunClock.at(52.3746, 4.8840, java.time.ZoneId.of("Europe/Amsterdam")))
        assertEquals(true, oh("sunset+").isOpen(t("2026-10-13T20:00"), ams))
    }

    /**
     * A start by sunset or dusk that does not happen that day never opens, with or without its `+` (the app maintainer,
     * 19 November 2026, batch 6 A7): Longyearbyen's polar night read `sunset+` and `dusk+` open from 00:00 to 06:00
     * every day, the missing evening as midnight, and a bare `sunset` open at 00:00; 38ddafa1 had them shut. The starts
     * by sunrise and dawn stay as batch 5 A7 left them, and the spans as they are.
     */
    @Test
    fun aSunsetOrDuskThatDoesNotHappenNeverOpens() {
        val lyb = OpeningHoursContext(sun = SunClock.at(78.2232, 15.6267, java.time.ZoneId.of("Europe/Oslo")))
        val night = LocalDate.of(2026, 12, 22)
        for (h in listOf("sunset+", "dusk+", "sunset", "Mo-Su sunset+", "(sunset-01:00)+")) {
            for (at in listOf("00:00", "00:30", "03:00", "05:59", "12:00", "23:59")) assertEquals("$h at $at", false, oh(h).isOpen(t("2026-12-22T$at"), lyb))
            assertEquals(h, emptyList<OpenSpan>(), oh(h).spans(night, lyb))
        }
        // the midsummer evening that never comes, as batch 4 B19 left it
        assertEquals(false, oh("sunset+").isOpen(t("2026-06-21T00:30"), lyb))
        assertEquals(emptyList<OpenSpan>(), oh("sunset+").spans(LocalDate.of(2026, 6, 21), lyb))
        // neighbours as they were: a rising's start (batch 5 A7, batch 4 B19), a span (finding 11), a dusk that comes
        assertEquals(true, oh("sunrise+").isOpen(t("2026-06-21T12:00"), lyb))
        assertEquals(false, oh("sunrise+").isOpen(t("2026-12-22T12:00"), lyb))
        assertEquals(true, oh("sunset-sunrise").isOpen(t("2026-12-22T12:00"), lyb))
        val tromso = OpeningHoursContext(sun = SunClock.at(69.6492, 18.9553, java.time.ZoneId.of("Europe/Oslo")))
        val dusk = tromso.sun.times(night)?.dusk
        assertNotNull("Tromsø's December dusk", dusk)
        assertEquals(true, oh("dusk+").isOpen(LocalDateTime.of(night, dusk!!.plusMinutes(60)), tromso))
    }

    @Test
    fun allDayAndTimesWithoutTheirColons() {
        // the reviewer, 17 November, finding 11: "24h" read as the moment 24:00, "closed, opens 0:00"; "Mo-Fr 1900-2200"
        // as the years 1900 to 2200, open around the clock
        val allDay = oh("24h")
        assertEquals(true, allDay.isOpen(t("2026-10-12T03:00"), context = Nowhere))
        assertEquals(true, allDay.isOpen(t("2026-10-17T23:59"), context = Nowhere))
        assertEquals(true, open("Mo-Fr 24h", "2026-10-14T02:00"))
        assertEquals(false, open("Mo-Fr 24h", "2026-10-17T12:00"))
        assertEquals(true, open("Mo-Fr: 24h; Sa 10:00-16:00", "2026-10-17T11:00"))
        assertEquals(false, open("Mo-Fr 24h; PH off", "2026-12-25T12:00", nl))
        // "18h-24h" stays a span to midnight
        assertEquals(true, open("Mo-Su 18h-24h", "2026-10-12T23:00"))
        assertEquals(false, open("Mo-Su 18h-24h", "2026-10-12T17:00"))
        val evenings = oh("Mo-Fr 1900-2200")
        assertEquals(true, evenings.isOpen(t("2026-10-13T20:00"), context = Nowhere))
        assertEquals(false, evenings.isOpen(t("2026-10-13T12:00"), context = Nowhere))
        assertEquals(false, evenings.isOpen(t("2026-10-17T20:00"), context = Nowhere))
        assertEquals(t("2026-10-13T22:00"), evenings.status(t("2026-10-13T20:00"), context = Nowhere).changesAt)
        assertEquals(true, open("Mo-Fr 0830-1200,1300-1730", "2026-10-12T13:30"))
        assertEquals(false, open("Mo-Fr 0830-1200 1300-1730", "2026-10-12T12:30"))
        assertEquals(true, open("Mo-Fr 0830-1200 1300-1730", "2026-10-12T17:00"))
        // a year before the days is still a year
        assertEquals(true, open("2026 Mo-Fr 08:00-12:00", "2026-10-12T09:00"))
        assertEquals(false, open("2025 Mo-Fr 08:00-12:00", "2026-10-12T09:00"))
    }

    /**
     * Times with and without their colons in one rule are read in that rule (the app maintainer, 18 November 2026, batch
     * 4 A8): "Mo-Fr 0900-1200, 13:00-17:00" opened Saturday and Sunday 13:00 to 17:00, the rest read as a rule of no days
     * of its own; before the colonless times were read it was shown as written. The colon times first, then colonless
     * ones, are refused as before: never open at the weekend.
     */
    @Test
    fun timesWithAndWithoutColonsInOneRule() {
        val mixed = oh("Mo-Fr 0900-1200, 13:00-17:00")
        assertEquals(true, mixed.isOpen(t("2026-10-12T10:00"), context = Nowhere))
        assertEquals(true, mixed.isOpen(t("2026-10-12T14:00"), context = Nowhere))
        assertEquals(false, mixed.isOpen(t("2026-10-12T12:30"), context = Nowhere))
        assertEquals(false, mixed.isOpen(t("2026-10-17T14:00"), context = Nowhere))
        assertEquals(false, mixed.isOpen(t("2026-10-18T14:00"), context = Nowhere))
        assertEquals(t("2026-10-12T17:00"), mixed.status(t("2026-10-12T14:00"), context = Nowhere).changesAt)
        assertEquals(true, open("Mo-Fr 0900-1200 13:00-17:00", "2026-10-13T16:00"))
        assertEquals(false, open("Mo-Fr 0900-1200 13:00-17:00", "2026-10-17T16:00"))
        assertEquals(true, open("Mo-Fr 0900-1200, 13:00-15:00, 1600-1800", "2026-10-14T17:00"))
        assertEquals(false, open("Mo-Fr 0900-1200, 13:00-15:00, 1600-1800", "2026-10-14T15:30"))
        val colonsFirst = OpeningHours.parseOrNull("Mo-Fr 09:00-12:00, 1300-1700")
        assertTrue(colonsFirst == null || colonsFirst.isOpen(t("2026-10-17T14:00"), context = Nowhere) != true)
        // a rule of its own after a semicolon stays its own
        assertEquals(true, open("Mo-Fr 0900-1200; Sa 10:00-14:00", "2026-10-17T11:00"))
    }
}
