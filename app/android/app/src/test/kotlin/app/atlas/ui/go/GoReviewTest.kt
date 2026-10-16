package app.atlas.ui.go

import app.atlas.services.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime
import java.util.Locale

/** What the review of the go package found: limits as OSM writes them, steps of a small battery, times of day. */
class GoReviewTest {
    @Test
    fun speedLimitsAsOsmWritesThem() {
        assertEquals(50, speedNumber("50"))
        assertEquals(50, speedNumber(" 50 "))
        assertEquals(48, speedNumber("30 mph"))
        assertEquals(48, speedNumber("30MPH"))
        assertEquals(30, speedNumber("30 km/h"))
        assertEquals(30, speedNumber("30kmh"))
        assertNull(speedNumber("NL:urban"))
        assertNull(speedNumber("none"))
        assertNull(speedNumber("walk"))
        assertNull(speedNumber(null))
    }

    @Test
    fun anEBikeBatteryStepsInTenthsACarsInWholeKilowattHours() {
        assertEquals(0.6, VehicleField.BATTERY.stepped(0.5, up = true, start = 0.5), 1e-9)
        assertEquals(0.4, VehicleField.BATTERY.stepped(0.5, up = false, start = 0.5), 1e-9)
        assertEquals(0.2, VehicleField.BATTERY.stepped(0.2, up = false, start = 0.5), 1e-9)
        assertEquals(2.0, VehicleField.BATTERY.stepped(1.9, up = true, start = 0.5), 1e-9)
        // Past two kilowatt-hours it is a car's battery: whole steps, and down from 2.0 it goes back into tenths.
        assertEquals(3.0, VehicleField.BATTERY.stepped(2.0, up = true, start = 60.0), 1e-9)
        assertEquals(1.9, VehicleField.BATTERY.stepped(2.0, up = false, start = 60.0), 1e-9)
        assertEquals(61.0, VehicleField.BATTERY.stepped(60.0, up = true, start = 60.0), 1e-9)
        assertEquals(59.0, VehicleField.BATTERY.stepped(60.0, up = false, start = 60.0), 1e-9)
        // Other fields are untouched.
        assertEquals(40.5, VehicleField.WEIGHT.stepped(40.0, up = true, start = 40.0), 1e-9)
    }

    @Test
    fun aTimeOfDayIsWrittenInTheLocalWay() {
        assertEquals("8:15", GoFormat.time(LocalTime.of(8, 15), Locale.forLanguageTag("nl-NL")))
        assertEquals("20:05", GoFormat.time(LocalTime.of(20, 5), Locale.forLanguageTag("nl-NL")))
        assertEquals("8:15 PM", GoFormat.time(LocalTime.of(20, 15), Locale.US))
        assertEquals("8:15 AM", GoFormat.time(LocalTime.of(8, 15), Locale.US))
    }

    @Test
    fun heightsOfClimbFollowTheUnits() {
        assertEquals("12 m", GoFormat.height(12.2, Units.METRIC).toString())
        assertEquals("39 ft", GoFormat.height(12.0, Units.IMPERIAL).toString())
    }
}
