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
        val s = h.status(t("2026-10-12T10:00")) // a Monday
        assertTrue(s.isOpen)
        assertFalse(s.unknown)
        assertEquals(t("2026-10-12T18:00"), s.changesAt)
        assertEquals(ChangeKind.CLOSES, s.nextChangeKind)
        val sat = h.status(t("2026-10-17T10:00"))
        assertFalse(sat.isOpen)
        assertEquals(t("2026-10-19T08:00"), sat.changesAt)
        assertEquals(ChangeKind.OPENS, sat.nextChangeKind)
        assertEquals(false, h.isOpen(t("2026-10-12T18:00")))
        assertEquals(true, h.isOpen(t("2026-10-12T17:59")))
    }

    @Test
    fun alwaysOpen() {
        val h = oh("24/7")
        assertTrue(h.isAlwaysOpen)
        val s = h.status(t("2026-10-12T03:00"))
        assertTrue(s.isOpen)
        assertNull(s.changesAt)
        assertFalse(oh("24/7; PH off").isAlwaysOpen)
        assertEquals(false, open("24/7; PH off", "2026-12-25T12:00", nl))
    }

    @Test
    fun splitShiftsAndOverrides() {
        val h = oh("Mo-Fr 08:00-12:00,13:00-17:30; Sa 10:00-14:00")
        assertEquals(false, h.isOpen(t("2026-10-12T12:30")))
        assertEquals(t("2026-10-12T13:00"), h.status(t("2026-10-12T12:30")).changesAt)
        assertEquals(true, h.isOpen(t("2026-10-17T11:00")))
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
        val s = h.status(t("2026-10-16T01:30")) // Friday night
        assertTrue(s.isOpen)
        assertEquals(t("2026-10-16T03:00"), s.changesAt)
        assertEquals(true, open("Fr 22:00-26:00", "2026-10-17T01:00"))
        assertEquals(false, open("Fr 22:00-26:00", "2026-10-17T02:00"))
        // Sunday is closed, but Saturday night runs into it
        val bar = oh("Mo-Sa 18:00-02:00; Su off")
        assertEquals(true, bar.isOpen(t("2026-10-18T01:00")))
        assertEquals(false, bar.isOpen(t("2026-10-18T20:00")))
        assertEquals(false, bar.isOpen(t("2026-10-19T01:00")))
        assertEquals(listOf(OpenSpan(0, 120, OpenState.OPEN)), oh("Fr 22:00-02:00").spans(LocalDate.of(2026, 10, 17)))
        assertEquals(listOf(OpenSpan(1320, 1440, OpenState.OPEN)), oh("Fr 22:00-02:00").spans(LocalDate.of(2026, 10, 16)))
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
        assertEquals(t("2027-04-01T10:00"), season.status(t("2026-11-15T12:00")).changesAt)
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
        assertEquals(true, h.isOpen(t("2026-10-12T10:00")))
        val after = h.status(t("2026-10-12T14:00"))
        assertTrue(after.unknown)
        assertEquals("by appointment", after.comment)
        assertNull(h.isOpen(t("2026-10-17T10:00")))
    }

    @Test
    fun commentsAndUnknown() {
        val alone = oh("\"by appointment\"").status(t("2026-10-12T10:00"))
        assertTrue(alone.unknown)
        assertEquals("by appointment", alone.comment)
        val withTimes = oh("Mo-Fr 10:00-20:00 \"call ahead\"").status(t("2026-10-12T11:00"))
        assertTrue(withTimes.isOpen)
        assertFalse(withTimes.unknown)
        assertEquals("call ahead", withTimes.comment)
        val mixed = oh("Tu-Fr \"on request\"; Sa-Su 13:00-17:00")
        assertNull(mixed.isOpen(t("2026-10-13T10:00")))
        assertEquals(true, mixed.isOpen(t("2026-10-17T14:00")))
        assertNull(open("Mo-Fr 10:00-12:00 unknown", "2026-10-12T11:00"))
        val named = oh("Mo 09:00-13:00 open \"Brocante\"; Sa 09:00-16:00 open \"Organic market\"")
        assertEquals("Organic market", named.status(t("2026-10-17T10:00")).comment)
        assertEquals(OpenStatus.Unknown, OpeningHours.statusOf("not hours", t("2026-10-12T10:00")))
    }

    @Test
    fun openEnds() {
        val h = oh("Mo-Su 17:00+")
        val s = h.status(t("2026-10-12T18:00"))
        assertTrue(s.isOpen)
        assertTrue(s.openEnd)
        assertEquals(false, h.isOpen(t("2026-10-12T16:00")))
        val lastOrders = oh("Mo-Sa 18:00-22:00+").status(t("2026-10-12T19:00"))
        assertTrue(lastOrders.isOpen)
        assertTrue(lastOrders.openEnd)
        val plain = oh("Mo-Sa 18:00-22:00").status(t("2026-10-12T19:00"))
        assertFalse(plain.openEnd)
    }

    @Test
    fun weekTableAndSpans() {
        val h = oh("Mo-Th 09:00-17:00; Fr 09:00-12:00,22:00-02:00; PH off; Dec 25 off")
        val week = h.weekTable()
        assertEquals(7, week.size)
        assertEquals(listOf(OpenSpan(540, 1020, OpenState.OPEN)), week[0])
        assertEquals(listOf(OpenSpan(540, 720, OpenState.OPEN), OpenSpan(1320, 1560, OpenState.OPEN)), week[4])
        assertTrue(week[5].isEmpty() && week[6].isEmpty())
        assertEquals("02:00", week[4][1].endTime.toString())
        // a real week: Christmas on the Friday
        val xmas = h.weekTable(LocalDate.of(2026, 12, 23), nl)
        assertTrue(xmas[4].isEmpty())
        val dial = h.spans(LocalDate.of(2026, 10, 17))
        assertEquals(listOf(OpenSpan(0, 120, OpenState.OPEN)), dial)
        val unknown = oh("Mo-Fr 08:00-12:00 || \"by appointment\"").spans(LocalDate.of(2026, 10, 12))
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
            assertEquals(v, true, h!!.isOpen(t("2026-10-12T09:00")))
            assertEquals(v, false, h.isOpen(t("2026-10-12T19:00")))
            assertEquals(v, false, h.isOpen(t("2026-10-17T09:00")))
        }
        // a missing ";" between rules
        val missing = oh("Mo-Fr 08:30-17:00 Sa 09:00-12:00")
        assertEquals(true, missing.isOpen(t("2026-10-17T10:00")))
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
        assertEquals(true, post.isOpen(t("2026-10-12T17:00")))
        assertEquals(false, post.isOpen(t("2026-10-12T17:01")))
        assertEquals(t("2026-10-17T12:00"), post.status(t("2026-10-16T18:00")).changesAt)
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
            assertEquals(v, false, h!!.isOpen(t("2026-10-12T08:59")))
            assertEquals(v, true, h.isOpen(t("2026-10-12T09:00")))
            assertEquals(v, true, h.isOpen(t("2026-10-12T16:59")))
            assertEquals(v, false, h.isOpen(t("2026-10-12T17:00")))
        }
        // 12 pm is noon and 12 am midnight
        assertEquals(listOf(OpenSpan(720, 1440, OpenState.OPEN)), oh("Sa 12pm-12am").spans(LocalDate.of(2026, 10, 17)))
        assertEquals(listOf(OpenSpan(0, 360, OpenState.OPEN)), oh("Su 12am-6am").spans(LocalDate.of(2026, 10, 18)))
        assertNull(OpeningHours.parseOrNull("Mo 13pm-14pm"))
        assertNull(OpeningHours.parseOrNull("Mo 0am-5am"))
    }

    @Test
    fun allDayAfterOtherSelectors() {
        val summer = oh("Jun-Aug 24/7")
        assertFalse(summer.isAlwaysOpen)
        assertEquals(true, summer.isOpen(t("2026-07-03T03:00")))
        assertEquals(false, summer.isOpen(t("2026-10-13T12:00")))
        assertEquals(t("2027-06-01T00:00"), summer.status(t("2026-10-13T12:00")).changesAt)
        val weekdays = oh("Mo-Fr 24/7; Sa 10:00-16:00")
        assertEquals(true, weekdays.isOpen(t("2026-10-12T03:00")))
        assertEquals(false, weekdays.isOpen(t("2026-10-17T09:00")))
        assertEquals(true, weekdays.isOpen(t("2026-10-17T11:00")))
        assertEquals(true, open("Mo-Fr: 24/7", "2026-10-14T23:00"))
        assertTrue(oh("24/7").isAlwaysOpen)
    }

    @Test
    fun aWeeklyPatternIsSearchedOneWeekAhead() {
        // nothing ever changes: no next change (it used to walk 400 days to find out)
        assertNull(oh("24/7").status(t("2026-10-12T10:00")).changesAt)
        val off = oh("off").status(t("2026-10-12T10:00"))
        assertFalse(off.isOpen)
        assertNull(off.changesAt)
        // the furthest change a weekly pattern can have is just under a week away
        assertEquals(t("2026-10-25T10:00"), oh("Su 10:00-12:00").status(t("2026-10-18T12:30")).changesAt)
        assertEquals(t("2026-10-25T22:00"), oh("Su 22:00-02:00").status(t("2026-10-19T03:00")).changesAt)
        // dated rules still look up to a year ahead
        assertEquals(t("2027-12-25T10:00"), oh("Dec 25 10:00-12:00").status(t("2026-12-25T13:00")).changesAt)
    }
}
