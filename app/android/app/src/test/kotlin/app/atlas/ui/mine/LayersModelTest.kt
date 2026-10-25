package app.atlas.ui.mine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class LayersModelTest {
    private val zone = ZoneId.of("Europe/Amsterdam")

    @Test
    fun theTimeOfLightIsTheClocksOnTheDaysTheClocksChange() {
        // It was midnight plus the minutes: on 25 October 2026 the scrubber's 22:30 lit the map for 21:30, in March for 23:30.
        val back = LocalDate.of(2026, 10, 25)
        assertEquals(ZonedDateTime.of(back, LocalTime.of(22, 30), zone).toInstant(), LayersModel.at(back, 22 * 60 + 30, zone))
        for (m in 0 until 1440) assertEquals("$back, minute $m", m, LayersModel.minuteOf(LayersModel.at(back, m, zone), zone))
        val forward = LocalDate.of(2026, 3, 29)
        for (m in 0 until 1440) {
            // the clocks skip 02:00 to 02:59 that night: such a time is shown an hour later
            val shown = if (m in 120 until 180) m + 60 else m
            assertEquals("$forward, minute $m", shown, LayersModel.minuteOf(LayersModel.at(forward, m, zone), zone))
        }
        val plain = LocalDate.of(2026, 10, 26)
        for (m in 0 until 1440) assertEquals("$plain, minute $m", m, LayersModel.minuteOf(LayersModel.at(plain, m, zone), zone))
    }
}
