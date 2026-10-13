package app.atlas.route

import app.atlas.geo.LatLon
import app.atlas.geo.LocalFrame
import app.atlas.model.EvSpec
import app.atlas.model.Mode
import app.atlas.model.Place
import app.atlas.model.RouteRequest
import app.atlas.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The EV pieces without BRouter: power, charging curves, energy and the stop search on a synthetic motorway. */
class EvTest {
    private val car = EvSpec(batteryKwh = 60.0, consumption = 18.0, maxDcKw = 100.0, maxAcKw = 11.0, socNow = 0.5, socMin = 0.1)

    private fun charger(at: LatLon, vararg tags: Pair<String, String>) = Place(null, at, mapOf("amenity" to "charging_station") + tags, source = "test")

    @Test
    fun readsChargerPower() {
        assertEquals(150.0, ChargerPower.kw("150 kW")!!, 0.0)
        assertEquals(22.0, ChargerPower.kw("22")!!, 0.0)
        assertEquals(7.4, ChargerPower.kw("7400 W")!!, 1e-9)
        assertEquals(22.0, ChargerPower.kw("11 kW;22 kW")!!, 0.0)
        assertNull(ChargerPower.kw(null))
        val here = LatLon(52.0, 4.0)
        assertEquals(100.0, ChargerPower.usableKw(charger(here, "socket:type2_combo" to "4", "socket:type2_combo:output" to "300 kW"), car)!!, 0.0)
        assertEquals(11.0, ChargerPower.usableKw(charger(here, "socket:type2" to "2", "socket:type2:output" to "22 kW"), car)!!, 0.0)
        assertNull(ChargerPower.usableKw(charger(here, "socket:chademo" to "1", "socket:chademo:output" to "50 kW"), car))
        assertNull(ChargerPower.usableKw(charger(here, "socket:type2_combo" to "0"), car))
        assertEquals(50.0, ChargerPower.usableKw(charger(here, "atlas:power_kw" to "50"), car)!!, 0.0)
        // the best compatible socket wins
        assertEquals(100.0, ChargerPower.usableKw(charger(here, "socket:type2" to "2", "socket:type2_combo" to "1", "socket:type2_combo:output" to "120"), car)!!, 0.0)
    }

    @Test
    fun chargingFollowsTheCurve() {
        // flat at 100 kW to 80 %: 10→80 % is 42 kWh at 92 % efficiency
        assertEquals(42.0 / (100 * 0.92) * 3600, ChargeCurve.seconds(car, 150.0, 0.1, 0.8), 5.0)
        // the taper above 80 % makes the last 20 % slower than the 20 % before
        assertTrue(ChargeCurve.seconds(car, 150.0, 0.8, 1.0) > 1.5 * ChargeCurve.seconds(car, 150.0, 0.6, 0.8))
        // a 50 kW charger caps the car
        assertEquals(42.0 / (50 * 0.92) * 3600, ChargeCurve.seconds(car, 50.0, 0.1, 0.8), 5.0)
        assertEquals(0.0, ChargeCurve.seconds(car, 50.0, 0.8, 0.5), 0.0)
        // the car's own curve when given
        val curved = car.copy(curve = listOf(0.0 to 120.0, 0.5 to 120.0, 1.0 to 20.0))
        assertEquals(120.0, ChargeCurve.powerKw(curved, 150.0, 0.3), 0.0)
        assertEquals(70.0, ChargeCurve.powerKw(curved, 150.0, 0.75), 1e-9)
        // AC charging
        assertEquals(11.0, ChargeCurve.powerKw(car, 11.0, 0.5), 0.0)
        // a curve given out of order means the same
        val shuffled = car.copy(curve = listOf(1.0 to 20.0, 0.0 to 120.0, 0.5 to 120.0))
        assertEquals(70.0, ChargeCurve.powerKw(shuffled, 150.0, 0.75), 1e-9)
        assertEquals(ChargeCurve.seconds(curved, 150.0, 0.1, 0.9), ChargeCurve.seconds(shuffled, 150.0, 0.1, 0.9), 1e-9)
    }

    /** A straight 300 km motorway at 110 km/h with the energy of 18 kWh/100 km. */
    private val frame = LocalFrame(LatLon(52.0, 4.0))
    private val motorway: List<LatLon> = List(301) { frame.toLatLon(it * 1000.0, 0.0) }
    private val energy = DoubleArray(301) { it * 0.18 }
    private val time = DoubleArray(301) { it * 1000 / (110 / 3.6) }

    @Test
    fun noStopWhenTheBatteryIsEnough() {
        val full = car.copy(socNow = 1.0, socMin = 0.08)
        // 300 km need 54 kWh = 90 %: from 100 % that leaves 10 %, above the 8 % reserve
        assertEquals(listOf<ChargingSearch.Choice>(), ChargingSearch().search(emptyList(), energy, time, full))
    }

    @Test
    fun findsTheFastestStops() {
        val places = listOf(
            charger(frame.toLatLon(60_000.0, 200.0), "socket:type2_combo" to "2", "socket:type2_combo:output" to "50 kW"),
            charger(frame.toLatLon(100_000.0, 300.0), "socket:type2_combo" to "8", "socket:type2_combo:output" to "300 kW"),
            charger(frame.toLatLon(140_000.0, 100.0), "socket:type2_combo" to "4", "socket:type2_combo:output" to "150 kW"),
            charger(frame.toLatLon(200_000.0, 9_000.0), "socket:type2_combo" to "4", "socket:type2_combo:output" to "150 kW"), // too far off
            charger(frame.toLatLon(120_000.0, 50.0), "socket:chademo" to "2", "socket:chademo:output" to "50 kW"), // wrong plug
            charger(frame.toLatLon(150_000.0, 50.0), "socket:type2" to "2", "socket:type2:output" to "11 kW"), // too slow
        )
        val search = ChargingSearch(EvOptions(corridorMetres = 5_000.0))
        val cands = search.candidates(places, car, motorway)
        assertEquals(listOf(60_000.0, 100_000.0, 140_000.0), cands.map { it.along }.map { Math.round(it / 1000.0) * 1000.0 })
        val plan = search.search(cands, energy, time, car)
        assertNotNull(plan)
        // 50 % of 60 kWh reaches 133 km above the reserve: the fast charger at 100 km, charged just enough for the rest
        assertEquals(1, plan!!.size)
        assertEquals(100_000.0, plan[0].candidate.along, 500.0)
        val needed = car.socMin + (energy.last() - energy[100]) / car.batteryKwh
        assertTrue("leaves with ${plan[0].departSoc}", plan[0].departSoc >= needed && plan[0].departSoc <= needed + 0.06)
    }

    @Test
    fun takesSeveralStopsAndNeverRunsDry() {
        val small = car.copy(batteryKwh = 30.0, socNow = 0.9)
        val places = (1..14).map { k -> charger(frame.toLatLon(k * 20_000.0, 100.0), "socket:type2_combo" to "2", "socket:type2_combo:output" to "100 kW") }
        val search = ChargingSearch()
        val plan = search.search(search.candidates(places, small, motorway), energy, time, small)!!
        assertTrue(plan.size >= 2)
        // replay: the charge never drops below the reserve
        var soc = small.socNow
        var last = 0
        for (c in plan) {
            soc -= (energy[c.candidate.pointIndex] - energy[last]) / small.batteryKwh
            assertTrue("arrives at ${c.candidate.along} with $soc", soc >= small.socMin - 0.01)
            soc = c.departSoc
            last = c.candidate.pointIndex
        }
        soc -= (energy.last() - energy[last]) / small.batteryKwh
        assertTrue(soc >= small.socMin - 1e-6)
    }

    @Test
    fun noPlanWithoutChargers() {
        assertNull(ChargingSearch().search(emptyList(), energy, time, car))
    }

    @Test
    fun energyWithoutAKinematicModel() {
        // 10 km flat at 100 km/h, then 100 m up
        val n = 11
        val lat = DoubleArray(n) { frame.lat(0.0) }
        val lon = DoubleArray(n) { frame.lon(it * 1000.0) }
        val ele = DoubleArray(n) { if (it < 10) 0.0 else 100.0 }
        val time = DoubleArray(n) { it * 36.0 }
        val raw = RawTrack(lat, lon, ele, time, DoubleArray(n), arrayOfNulls(n), arrayOfNulls(n), emptyList())
        val req = RouteRequest(Mode.CAR, listOf(motorway[0], motorway[10]), vehicle = Vehicle(weightT = 2.0, ev = car))
        val kwh = EnergyModel.cumulativeKwh(raw, req, car)
        assertEquals(0.18 * 9, kwh[9], 1e-6)
        assertEquals(0.18 * 10 + 2000 * 9.81 * 100 / 3.6e6 / 0.9, kwh[10], 1e-6)
    }

    @Test
    fun kinematicEnergyIsCalibratedToTheCar() {
        // BRouter's model for an electric car of 2 t: 505.8 N on the flat at 100 km/h
        val n = 3
        val joules = 505.83 * 100_000
        val raw = RawTrack(
            DoubleArray(n) { 52.0 }, DoubleArray(n) { 4.0 + it * 0.7 }, DoubleArray(n) { Double.NaN }, DoubleArray(n) { it * 1800.0 },
            DoubleArray(n) { it * joules / 2 }, arrayOfNulls(n), arrayOfNulls(n), emptyList(),
        )
        val req = RouteRequest(Mode.CAR, listOf(motorway[0], motorway[1]), vehicle = Vehicle(ev = car))
        assertEquals(18.0, EnergyModel.cumulativeKwh(raw, req, car).last(), 0.05)
    }
}
