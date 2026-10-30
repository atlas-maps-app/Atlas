package app.atlas.ui.mine

import app.atlas.geo.LatLon
import app.atlas.geo.Sun
import app.atlas.model.Mode
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.model.Route
import app.atlas.providers.EvModels
import app.atlas.providers.Forecast
import app.atlas.providers.Precipitation15
import app.atlas.providers.Sky
import app.atlas.providers.WeatherDay
import app.atlas.providers.WeatherHour
import app.atlas.search.MemoryPlaces
import app.atlas.ui.mine.charge.ChargeModel
import app.atlas.ui.mine.charge.PowerBand
import app.atlas.ui.mine.power.PowerModel
import app.atlas.ui.mine.pump.Fuel
import app.atlas.ui.mine.pump.PumpModel
import app.atlas.ui.mine.pump.Tankerkoenig
import app.atlas.ui.mine.water.WaterModel
import app.atlas.ui.mine.water.WaterSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val W = LatLon(52.3733, 4.8840)
private fun node(id: Long, dLat: Double, dLon: Double, vararg tags: Pair<String, String>, extra: Map<String, String> = emptyMap()) =
    Place(OsmId(OsmType.NODE, id), LatLon(W.lat + dLat, W.lon + dLon), tags.toMap(), extra = extra)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChargeModelTest {
    @Test
    fun theBandIsChosenByPowerAndUnknownIsItsOwn() {
        assertEquals(PowerBand.UNKNOWN, PowerBand.of(null))
        assertEquals(PowerBand.UNKNOWN, PowerBand.of(0.0))
        assertEquals(PowerBand.UNKNOWN, PowerBand.of(Double.NaN))
        assertEquals(PowerBand.AC22, PowerBand.of(11.0))
        assertEquals(PowerBand.AC22, PowerBand.of(22.5))
        assertEquals(PowerBand.KW50, PowerBand.of(50.0))
        assertEquals(PowerBand.KW50, PowerBand.of(100.0))
        assertEquals(PowerBand.KW150, PowerBand.of(150.0))
        assertEquals(PowerBand.KW300, PowerBand.of(250.0))
    }

    @Test
    fun aPricePerKilowattHourIsReadFromTheChargeTagInItsWritings() {
        assertEquals(0.49 to "EUR", ChargeModel.price(mapOf("charge" to "0.49 EUR/kWh")))
        assertEquals(0.49 to "EUR", ChargeModel.price(mapOf("charge" to "€0,49/kWh")))
        assertEquals(0.30 to "EUR", ChargeModel.price(mapOf("charge" to "0.30 EUR/kWh;1.00 EUR/session")))
        assertEquals(0.35 to "GBP", ChargeModel.price(mapOf("charge" to "£0.35 / kWh")))
        assertNull(ChargeModel.price(mapOf("charge" to "1.00 EUR/session")))
        assertNull(ChargeModel.price(emptyMap()))
    }

    private fun site(id: Long, kw: Int, free: Pair<Int, Int>?, price: String?, dLat: Double = 0.001, socket: String = "type2_combo"): Place {
        val tags = linkedMapOf("amenity" to "charging_station", "operator" to "Test $id", "socket:$socket" to "2", "socket:$socket:output" to "$kw kW")
        price?.let { tags["charge"] = it; tags["fee"] = "yes" }
        val extra = free?.let { mapOf("status:available" to it.first.toString(), "status:total" to it.second.toString()) }.orEmpty()
        return Place(OsmId(OsmType.NODE, id), LatLon(W.lat + dLat, W.lon), tags, extra = extra)
    }

    @Test
    fun aSiteIsReadOutOfItsTags() {
        val c = ChargeModel.of(site(1, 150, 2 to 4, "0.49 EUR/kWh"), W)
        assertEquals(150.0, c.kw!!, 0.0)
        assertEquals(PowerBand.KW150, c.band)
        assertEquals(2, c.free)
        assertEquals(4, c.points)
        assertTrue(c.live && !c.busy)
        assertEquals(0.49, c.pricePerKwh!!, 0.0)
        assertEquals(111.0, c.distanceM!!, 3.0)
        assertTrue("type2_combo" in c.types)
        assertTrue(ChargeModel.of(site(2, 50, 0 to 3, null), null).busy)
        assertFalse(ChargeModel.of(site(3, 22, null, null), W).live)
    }

    @Test
    fun theFilterKeepsThePlugsPowerAndFreeChargersAskedFor() {
        val all = listOf(
            ChargeModel.of(site(1, 150, 2 to 4, "0.49 EUR/kWh"), W),
            ChargeModel.of(site(2, 22, 0 to 2, null, socket = "type2"), W),
            ChargeModel.of(site(3, 50, null, null), W),
        )
        assertEquals(3, ChargeModel.filter(all, emptySet(), 0, false).size)
        assertEquals(listOf(1L, 3L), ChargeModel.filter(all, setOf("type2_combo"), 0, false).map { it.place.id!!.id })
        assertEquals(listOf(1L, 3L), ChargeModel.filter(all, emptySet(), 50, false).map { it.place.id!!.id })
        assertEquals(listOf(1L), ChargeModel.filter(all, emptySet(), 100, false).map { it.place.id!!.id })
        // Free only: a site with no live data stays (it might be free), a busy one goes.
        assertEquals(listOf(1L, 3L), ChargeModel.filter(all, emptySet(), 0, true).map { it.place.id!!.id })
    }

    @Test
    fun theSummaryCountsSitesFreePointsAndTheCheapest() {
        val list = listOf(
            ChargeModel.of(site(1, 150, 2 to 4, "0.49 EUR/kWh"), W),
            ChargeModel.of(site(2, 22, 1 to 2, "0.39 EUR/kWh", socket = "type2"), W),
            ChargeModel.of(site(3, 50, null, null), W),
        )
        val s = ChargeModel.summary(list)
        assertEquals(3, s.sites); assertEquals(3, s.free); assertEquals(0.39, s.cheapest!!, 0.0); assertEquals("EUR", s.currency)
        assertNull(ChargeModel.summary(list.takeLast(1)).free)
        assertEquals(listOf(PowerBand.AC22 to 1, PowerBand.KW50 to 1, PowerBand.KW150 to 1), ChargeModel.mix(list))
    }

    @Test
    fun theCarsPortsLightTheirPlugTilesAndBack() {
        val ids = ChargeModel.plugIdsOf(setOf("type2_combo", "type2"))
        assertEquals(setOf("ccs2", "type2"), ids)
        assertEquals(setOf("type2_combo", "type2", "type2_cable"), ChargeModel.portsOf(ids))
        assertTrue(ChargeModel.plugIdsOf(emptySet()).isEmpty())
    }

    @Test
    fun aFastChargerIsFasterButNeverBeyondTheCarAndAnEmptyJobTakesNoTime() {
        val car = EvModels.bundled().all.first { it.make == "Tesla" && it.model.contains("Model 3") }
        val slow = ChargeModel.chargeMinutes(car, 0.2, 0.8, 50.0, dc = true)!!
        val fast = ChargeModel.chargeMinutes(car, 0.2, 0.8, 150.0, dc = true)!!
        val huge = ChargeModel.chargeMinutes(car, 0.2, 0.8, 350.0, dc = true)!!
        assertTrue("$slow > $fast", slow > fast)
        assertTrue("a 350 kW charger cannot beat a 150 kW one by much more than the car can take: $fast vs $huge", huge <= fast)
        assertTrue(ChargeModel.chargeMinutes(car, 0.2, 0.8, 11.0, dc = false)!! > slow)
        assertEquals(0.0, ChargeModel.chargeMinutes(car, 0.8, 0.5, 150.0, dc = true)!!, 0.0)
        // The taper above 80 % makes the last 10 points slower than the ten before.
        val a = ChargeModel.chargeMinutes(car, 0.7, 0.8, 250.0, dc = true)!!
        val b = ChargeModel.chargeMinutes(car, 0.8, 0.9, 250.0, dc = true)!!
        assertTrue("$a < $b", a < b)
    }

    @Test
    fun rangeIsTheChargeOverTheConsumption() {
        val car = EvModels.bundled().all.first { it.make == "Tesla" && it.model.contains("Model 3") }
        assertEquals(car.batteryKwh / (car.consumption / 100.0), ChargeModel.rangeKm(car, 1.0), 1e-6)
        assertEquals(ChargeModel.rangeKm(car, 1.0) / 2, ChargeModel.rangeKm(car, 0.5), 1e-6)
        assertEquals(0.0, ChargeModel.rangeKm(car, -1.0), 0.0)
        assertEquals(ChargeModel.rangeKm(car, 1.0), ChargeModel.rangeKm(car, 2.0), 1e-9)
    }

    @Test
    fun minutesAreWrittenAsThePlanBarWritesThem() {
        assertEquals("20′", ChargeModel.minutes(20.0))
        assertEquals("1:05", ChargeModel.minutes(65.0))
    }
}

class PumpModelTest {
    private fun fuel(id: Long, dLat: Double, brand: String, vararg fuels: String, hours: String? = null): Place {
        val tags = linkedMapOf("amenity" to "fuel", "brand" to brand, "name" to brand, "addr:street" to "Main St", "addr:housenumber" to "5")
        hours?.let { tags["opening_hours"] = it }
        for (f in fuels) tags["fuel:$f"] = "yes"
        return Place(OsmId(OsmType.NODE, id), LatLon(W.lat + dLat, W.lon), tags)
    }

    @Test
    fun aStationIsReadOutOfItsFuelTags() {
        val s = PumpModel.station(fuel(1, 0.001, "Nordoil", "e10", "octane_95", "diesel", "adblue", hours = "24/7"), W)
        assertEquals(setOf(Fuel.E10, Fuel.S95, Fuel.DIESEL, Fuel.ADBLUE), s.fuels)
        assertTrue(s.open24h)
        assertEquals("Main St 5", s.street)
        assertEquals("Nordoil", s.brand)
        assertTrue(Station24(s))
        assertTrue(s.prices.isEmpty())
    }

    private fun Station24(s: app.atlas.ui.mine.pump.Station) = app.atlas.ui.mine.pump.Station.Amenity.OPEN_24H in s.amenities

    @Test
    fun withoutPricesTheNearestComesFirstAndWithPricesTheCheapest() {
        val near = fuel(1, 0.001, "Near", "diesel", "e10")
        val far = fuel(2, 0.01, "Far", "diesel")
        val plain = listOf(PumpModel.station(far, W), PumpModel.station(near, W))
        assertEquals(listOf("Near", "Far"), PumpModel.ranked(plain, Fuel.DIESEL).map { it.name })
        assertEquals(listOf("Near"), PumpModel.ranked(plain, Fuel.E10).map { it.name })
        val priced = listOf(PumpModel.station(far, W, prices = mapOf(Fuel.DIESEL to 1.59)), PumpModel.station(near, W, prices = mapOf(Fuel.DIESEL to 1.69)))
        assertEquals(listOf("Far", "Near"), PumpModel.ranked(priced, Fuel.DIESEL).map { it.name })
    }

    @Test
    fun countsAreOfStationsSellingEachFuel() {
        val list = listOf(PumpModel.station(fuel(1, 0.0, "A", "diesel", "e10"), W), PumpModel.station(fuel(2, 0.0, "B", "diesel"), W))
        val counts = PumpModel.counts(list)
        assertEquals(2, counts[Fuel.DIESEL]); assertEquals(1, counts[Fuel.E10]); assertEquals(0, counts[Fuel.LPG])
    }

    @Test
    fun theSavingIsAgainstTheAverageAndNeedsTwoPrices() {
        val a = PumpModel.station(fuel(1, 0.0, "A", "diesel"), W, prices = mapOf(Fuel.DIESEL to 1.60))
        val b = PumpModel.station(fuel(2, 0.0, "B", "diesel"), W, prices = mapOf(Fuel.DIESEL to 1.80))
        val (avg, saved) = PumpModel.saving(a, listOf(a, b), Fuel.DIESEL, 40.0)!!
        assertEquals(1.70, avg, 1e-9)
        assertEquals(4.0, saved, 1e-9)
        assertNull(PumpModel.saving(a, listOf(a), Fuel.DIESEL, 40.0))
        assertNull(PumpModel.saving(PumpModel.station(fuel(3, 0.0, "C", "diesel"), W), listOf(a, b), Fuel.DIESEL, 40.0))
    }

    @Test
    fun theTankAndThePumpsDisplay() {
        assertEquals(30.0, PumpModel.litresToFill(50, 0.4f), 1e-4)
        assertEquals(0.0, PumpModel.litresToFill(50, 1.5f), 0.0)
        assertEquals("empty", PumpModel.tankWord(0.0f)); assertEquals("¼", PumpModel.tankWord(0.25f)); assertEquals("½", PumpModel.tankWord(0.5f)); assertEquals("¾", PumpModel.tankWord(0.75f)); assertEquals("full", PumpModel.tankWord(0.95f))
        assertEquals("1.67" to "9", PumpModel.lcd(1.679))
        assertEquals("2.05", PumpModel.money(2.049))
    }
}

class TankerkoenigTest {
    private val answer = """{"ok":true,"license":"CC BY 4.0","data":"MTS-K","status":"ok","stations":[
        {"id":"abc-1","name":"Aral Tankstelle","brand":"ARAL","street":"Hauptstr.","houseNumber":"12","lat":52.5201,"lng":13.4049,"dist":0.4,"diesel":1.659,"e5":1.789,"e10":1.729,"isOpen":true},
        {"id":"abc-2","name":"Freie Tankstelle","brand":"","street":"Nebenweg","lat":52.5230,"lng":13.4100,"dist":0.9,"diesel":null,"e5":false,"e10":1.699,"isOpen":false},
        {"name":"no id","lat":1,"lng":2}]}"""

    @Test
    fun theAnswerIsReadIntoStationsWithPricesAndTheOnesWithoutIdAreDropped() {
        val list = Tankerkoenig.parse(answer)
        assertEquals(2, list.size)
        val a = list[0]
        assertEquals("Aral Tankstelle", a.name); assertEquals("ARAL", a.brand); assertEquals("Hauptstr. 12", a.street)
        assertEquals(mapOf(Fuel.DIESEL to 1.659, Fuel.S95 to 1.789, Fuel.E10 to 1.729), a.prices)
        assertTrue(a.open)
        val b = list[1]
        assertEquals(mapOf(Fuel.E10 to 1.699), b.prices)
        assertNull(b.brand); assertFalse(b.open)
    }

    @Test
    fun aRefusalBecomesAnError() {
        val e = runCatching { Tankerkoenig.parse("""{"ok":false,"message":"apikey nicht angegeben"}""") }.exceptionOrNull()
        assertNotNull(e); assertTrue(e!!.message!!.contains("apikey"))
    }

    @Test
    fun pricesLandOnTheOsmStationNextToThemAndTheRestAreAddedAsTheirOwn() {
        val priced = Tankerkoenig.parse(answer)
        val osm = Place(OsmId(OsmType.NODE, 7), LatLon(52.52012, 13.40493), mapOf("amenity" to "fuel", "brand" to "Aral", "fuel:diesel" to "yes", "fuel:lpg" to "yes"))
        val elsewhere = Place(OsmId(OsmType.NODE, 8), LatLon(52.6, 13.5), mapOf("amenity" to "fuel", "brand" to "Shell", "fuel:diesel" to "yes"))
        val out = Tankerkoenig.merge(listOf(osm, elsewhere), priced, LatLon(52.52, 13.405))
        assertEquals(3, out.size)
        val s = out.first { it.place.id?.id == 7L }
        assertEquals(1.659, s.price(Fuel.DIESEL)!!, 0.0)
        assertTrue(Fuel.LPG in s.fuels)
        assertTrue(out.first { it.place.id?.id == 8L }.prices.isEmpty())
        assertEquals(1, out.count { it.name == "Freie Tankstelle" && it.price(Fuel.E10) == 1.699 })
    }

    @Test
    fun theSpotAskedAboutGoesToAboutAHundredMetres() {
        val u = Tankerkoenig.listUrl("https://t.example", LatLon(52.520812, 13.409418), 5.0, "k")
        assertTrue(u, u.startsWith("https://t.example/json/list.php?lat=52.521&lng=13.409&rad=5.0&"))
    }

    @Test
    fun aKeyCanHelpOnlyInGermany() {
        assertTrue(Tankerkoenig.inGermany(LatLon(52.52, 13.4)))
        assertFalse(Tankerkoenig.inGermany(LatLon(52.37, 4.88)))
    }
}

class PowerModelTest {
    private val source = MemoryPlaces(
        listOf(
            node(1, -0.0003, 0.0007, "amenity" to "device_charging_station", "name" to "Locker", "socket:usb" to "4", "fee" to "yes", "charge" to "2 EUR/hour"),
            node(2, 0.0020, -0.0010, "amenity" to "cafe", "name" to "Café", "power_supply" to "yes", "socket:schuko" to "6", "socket:usb" to "6"),
            node(3, 0.0035, 0.0030, "public_transport" to "station", "name" to "Pillar", "power_supply" to "yes", "socket:usb" to "2", "fee" to "no"),
            node(4, 0.0040, 0.0000, "amenity" to "charging_station", "name" to "Car charger", "socket:schuko" to "2"),
            node(5, 0.0500, 0.0000, "amenity" to "library", "name" to "Far library", "power_supply" to "yes"),
            node(6, 0.0800, 0.1000, "tourism" to "camp_site", "name" to "Stonewood", "power_supply" to "yes"),
            node(7, 0.0100, 0.0100, "tourism" to "camp_site", "name" to "No hookup"),
        ),
    )

    @Test
    fun theSpotsAreThePlacesToChargeAPhoneNearestFirstAndNotCarChargers() {
        val spots = PowerModel.spots(source, W)
        assertEquals(listOf("Locker", "Café", "Pillar"), spots.map { it.name })
        assertEquals(PowerModel.spots(source, W).map { it.distanceM }.sorted(), spots.map { it.distanceM })
        assertEquals(listOf(app.atlas.ui.mine.power.PowerSpot.Kind.LOCKER, app.atlas.ui.mine.power.PowerSpot.Kind.CAFE, app.atlas.ui.mine.power.PowerSpot.Kind.PILLAR), spots.map { it.kind })
    }

    @Test
    fun theDetailSaysSocketsAndFee() {
        assertEquals("USB · fee: 2 EUR/hour", PowerModel.detail(node(1, 0.0, 0.0, "socket:usb" to "4", "fee" to "yes", "charge" to "2 EUR/hour")))
        assertEquals("USB & plugs", PowerModel.detail(node(2, 0.0, 0.0, "power_supply" to "yes", "socket:schuko" to "6", "socket:usb" to "6")))
        assertEquals("outlets · free", PowerModel.detail(node(3, 0.0, 0.0, "power_supply" to "yes", "fee" to "no")))
        assertEquals("", PowerModel.detail(node(4, 0.0, 0.0, "amenity" to "bench")))
    }

    @Test
    fun theHookupIsTheNearestCampsiteThatSaysItHasPower() {
        assertNull(PowerModel.hookup(source, W, radiusM = 5_000.0))
        val h = PowerModel.hookup(source, W, radiusM = 20_000.0)!!
        assertEquals("Stonewood", h.name)
        assertTrue(h.distanceM in 10_000.0..13_000.0)
    }

    @Test
    fun aPanelMakesNothingAtNightAndMostAtNoonAndCloudsThinIt() {
        val zone = ZoneId.of("Europe/Amsterdam")
        val day = LocalDate.of(2026, 6, 21)
        val clear = PowerModel.solarCurve(W, zone, day, null)
        assertEquals(96, clear.size)
        assertEquals(0.0, clear[0], 0.0)
        assertEquals(0.0, clear[95], 0.0)
        val noon = clear[13 * 4]
        assertTrue("noon $noon", noon > 50.0 && noon <= 80.0)
        val wh = PowerModel.wattHours(clear)
        assertTrue("$wh Wh from a 100 W panel on midsummer day", wh in 450.0..750.0)
        val grey = forecast(from = Instant.parse("2026-06-20T22:00:00Z")) { t -> hour(t, cloud = 100.0) }
        val dull = PowerModel.wattHours(PowerModel.solarCurve(W, zone, day, grey))
        assertTrue("$dull < $wh", dull < wh * 0.4)
        assertTrue(PowerModel.wattHours(PowerModel.solarCurve(W, zone, LocalDate.of(2026, 12, 21), null)) < wh / 3)
    }

    @Test
    fun theCurveAndTheCloudsGoByTheClockOnTheDaysTheClocksChange() {
        val zone = ZoneId.of("Europe/Amsterdam")
        // The quarters were midnight plus q quarters: after the change the curve peaked an hour from solar noon by the clock.
        for (day in listOf(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 26))) {
            val curve = PowerModel.solarCurve(W, zone, day, null)
            val peak = curve.indices.maxByOrNull { curve[it] }!! * 15 + 7
            val noon = Sun.times(day, W, zone).solarNoon.let { it.hour * 60 + it.minute }
            assertTrue("$day: peak at minute $peak, solar noon at $noon", kotlin.math.abs(peak - noon) <= 10)
        }
        // After nine o'clock: 10:00 on 25 October 2026 (09:00 UTC), not nine hours after midnight (09:00).
        val day = LocalDate.of(2026, 10, 25)
        val grey = forecast(from = Instant.parse("2026-10-24T22:00:00Z")) { t -> hour(t, cloud = 90.0) }
        assertEquals(Instant.parse("2026-10-25T09:00:00Z"), PowerModel.cloudsAt(grey, zone, day))
    }

    @Test
    fun aBankIsWorthItsWattHoursLessLossesOverAPhoneAndALaptop() {
        val (phones, laptops) = PowerModel.bankWorth(20_000)
        assertEquals(20_000 * 3.7 / 1000 * 0.75 / 17.0, phones, 1e-9)
        assertEquals(20_000 * 3.7 / 1000 * 0.75 / 50.0, laptops, 1e-9)
        assertTrue(phones in 3.0..3.5)
    }

    @Test
    fun durationsAreWrittenShort() {
        assertEquals("40 min", PowerModel.duration(0.66))
        assertEquals("1 min", PowerModel.duration(0.001))
        assertEquals("3 h", PowerModel.duration(3.0))
        assertEquals("2.5 h", PowerModel.duration(2.5))
        assertEquals("14 h", PowerModel.duration(14.2))
    }
}

private fun hour(t: Instant, temp: Double = 10.0, precip: Double = 0.0, gust: Double = 20.0, uv: Double = 1.0, day: Boolean = true, sky: Sky = Sky.CLEAR, cloud: Double = 0.0, vis: Double = 20_000.0) =
    WeatherHour(t, temp, temp, 0.0, precip, sky, null, cloud, 10.0, 270.0, gust, uv, day, 1013.0, vis)

private fun day(date: LocalDate, max: Double) = WeatherDay(date, Sky.CLEAR, null, max, 5.0, 0.0, 0.0, 0.0, 10.0, 20.0, 270.0, 3.0, null, null, 50_000.0)

private val START = Instant.parse("2026-10-14T06:00:00Z")

private fun forecast(quarters: List<Precipitation15> = emptyList(), days: List<WeatherDay> = emptyList(), hours: Int = 48, from: Instant = START, make: (Instant) -> WeatherHour): Forecast =
    Forecast(W, 0.0, "Europe/Amsterdam", 7200, null, quarters, (0 until hours).map { make(from.plus(Duration.ofHours(it.toLong()))) }, days, from)

class WeatherAdviceTest {
    private val now = START.plus(Duration.ofHours(2))

    @Test
    fun aDryDayIsSaidWhenNoRainIsForecast() {
        val a = WeatherAdvice.of(forecast { t -> hour(t) }, now)
        assertEquals(Advice.Kind.DRY_DAY, a.first().kind)
    }

    @Test
    fun rainFallingNowSaysWhenItStops() {
        val f = forecast { t -> hour(t, precip = if (t < now.plus(Duration.ofHours(2))) 1.0 else 0.0, sky = Sky.RAIN) }
        val first = WeatherAdvice.of(f, now).first()
        assertEquals(Advice.Kind.RAIN_NOW, first.kind)
        assertEquals(now.plus(Duration.ofHours(2)), first.until)
    }

    @Test
    fun rainComingWithinTwelveHoursIsSoonButRainTomorrowIsNot() {
        val soon = forecast { t -> hour(t, precip = if (t == now.plus(Duration.ofHours(5))) 2.0 else 0.0) }
        assertEquals(Advice.Kind.RAIN_SOON, WeatherAdvice.of(soon, now).first().kind)
        assertEquals(now.plus(Duration.ofHours(5)), WeatherAdvice.of(soon, now).first().at)
        val later = forecast { t -> hour(t, precip = if (t == now.plus(Duration.ofHours(20))) 2.0 else 0.0) }
        assertTrue(WeatherAdvice.of(later, now).none { it.kind == Advice.Kind.RAIN_SOON || it.kind == Advice.Kind.DRY_DAY })
    }

    @Test
    fun gustsFrostFogAndSunAreSaidOnlyAboveTheirLimits() {
        val calm = WeatherAdvice.of(forecast { t -> hour(t) }, now).map { it.kind }
        assertFalse(Advice.Kind.WINDY in calm || Advice.Kind.FROST in calm || Advice.Kind.FOG in calm || Advice.Kind.UV in calm)
        val rough = WeatherAdvice.of(
            forecast { t -> hour(t, temp = if (t == now.plus(Duration.ofHours(10))) -1.5 else 8.0, gust = if (t == now.plus(Duration.ofHours(4))) 72.0 else 20.0, uv = if (t == now.plus(Duration.ofHours(3))) 7.0 else 1.0, vis = if (t == now.plus(Duration.ofHours(6))) 400.0 else 20_000.0) },
            now, limit = 9,
        )
        val kinds = rough.map { it.kind }
        assertTrue(Advice.Kind.WINDY in kinds && Advice.Kind.FROST in kinds && Advice.Kind.FOG in kinds && Advice.Kind.UV in kinds)
        assertEquals(72.0, rough.first { it.kind == Advice.Kind.WINDY }.value!!, 0.0)
        assertEquals(-1.5, rough.first { it.kind == Advice.Kind.FROST }.value!!, 0.0)
    }

    @Test
    fun aHotDayIsInTheDailyNumbersAndTheListIsCapped() {
        val d = day(LocalDate.of(2026, 10, 14), 28.0)
        val kinds = WeatherAdvice.of(forecast(days = listOf(d)) { t -> hour(t) }, now).map { it.kind }
        assertTrue(Advice.Kind.HOT in kinds)
        val many = forecast(days = listOf(d)) { t -> hour(t, temp = -2.0, gust = 80.0, uv = 8.0, sky = Sky.SNOW, vis = 300.0, precip = if (t.epochSecond % 7 == 0L) 1.0 else 0.0) }
        assertTrue(WeatherAdvice.of(many, now).size <= 4)
        assertEquals(2, WeatherAdvice.of(many, now, limit = 2).size)
    }

    @Test
    fun theWindIsWrittenWithItsDirectionItBlowsFrom() {
        assertEquals("N 18", WeatherAdvice.wind(18.2, 0.0))
        assertEquals("N 18", WeatherAdvice.wind(18.2, 359.0))
        assertEquals("E 5", WeatherAdvice.wind(5.0, 90.0))
        assertEquals("SW 30", WeatherAdvice.wind(30.0, 225.0))
        assertEquals("W 18", WeatherAdvice.wind(18.0, -90.0))
    }
}

class WaterModelTest {
    private val source = MemoryPlaces(
        listOf(
            node(1, 0.0010, 0.0010, "natural" to "spring", "name" to "Beech spring", "drinking_water" to "yes"),
            node(2, -0.0020, 0.0030, "amenity" to "drinking_water", "name" to "Hut tap", "drinking_water" to "yes"),
            node(3, 0.0040, -0.0030, "man_made" to "water_well", "name" to "Old pump", "seasonal" to "yes", "pump" to "manual"),
            node(4, -0.0040, -0.0040, "amenity" to "drinking_water", "drinking_water" to "no", "name" to "Fountain"),
            node(5, 0.2000, 0.0000, "natural" to "spring", "name" to "Far spring"),
            node(6, 0.0020, 0.0020, "man_made" to "water_well", "name" to "Plain well"),
            node(7, 0.0030, 0.0030, "amenity" to "fountain", "name" to "Decorative"),
            node(8, 0.0030, 0.0040, "amenity" to "fountain", "name" to "Drinking fountain", "drinking_water" to "yes"),
        ),
    )

    @Test
    fun theWaterAroundIsTheSpringsTapsAndWellsWithinTheRadiusNearestFirst() {
        val list = WaterModel.sources(source, W)
        assertEquals(listOf("Beech spring", "Plain well", "Hut tap", "Drinking fountain", "Old pump", "Fountain"), list.map { it.name })
        assertFalse(list.any { it.name == "Far spring" || it.name == "Decorative" })
        assertEquals(list.map { it.distanceM }.sorted(), list.map { it.distanceM })
    }

    @Test
    fun theTagsSayWhatKindAndWhetherYouMayDrinkIt() {
        val by = WaterModel.sources(source, W).associateBy { it.name }
        assertEquals(WaterSource.Kind.SPRING, by.getValue("Beech spring").kind)
        assertEquals(WaterSource.Kind.POINT, by.getValue("Hut tap").kind)
        assertEquals(WaterSource.Kind.WELL, by.getValue("Old pump").kind)
        assertEquals(WaterSource.Kind.FOUNTAIN, by.getValue("Drinking fountain").kind)
        assertEquals(WaterSource.Status.POTABLE, by.getValue("Beech spring").status)
        assertEquals(WaterSource.Status.SEASONAL, by.getValue("Old pump").status)
        assertEquals(WaterSource.Status.NOT_DRINKING, by.getValue("Fountain").status)
        assertEquals(WaterSource.Status.UNSAID, by.getValue("Plain well").status)
        assertTrue(WaterModel.usable(by.getValue("Beech spring")) && WaterModel.usable(by.getValue("Plain well")))
        assertFalse(WaterModel.usable(by.getValue("Old pump")) || WaterModel.usable(by.getValue("Fountain")))
    }

    private fun eastward(lengthM: Double): Route {
        val pts = (0..40).map { LatLon(W.lat, W.lon + it * lengthM / 40 / 67_800.0) }
        return Route(Mode.WALK, pts, distance = lengthM, duration = lengthM / 1.25, steps = emptyList())
    }

    @Test
    fun alongARouteOnlyWhatIsNearItIsListedInTheOrderYouMeetIt() {
        val route = eastward(1_000.0)
        val list = WaterModel.sources(source, W, route = route, offRouteM = 300.0)
        assertTrue(list.isNotEmpty())
        assertEquals(list.map { it.alongM }.sortedBy { it }, list.map { it.alongM })
        assertTrue(list.all { it.alongM != null })
        assertFalse(list.any { it.name == "Far spring" })
        val tight = WaterModel.sources(source, W, route = route, offRouteM = 20.0)
        assertTrue(tight.size < list.size)
    }

    @Test
    fun youDrinkMoreWhenItIsWarmWithinLimits() {
        assertEquals(0.4, WaterModel.rate(15.0), 1e-9)
        assertEquals(0.6, WaterModel.rate(25.0), 1e-9)
        assertEquals(0.25, WaterModel.rate(-30.0), 1e-9)
        assertEquals(1.2, WaterModel.rate(60.0), 1e-9)
    }

    @Test
    fun aLitreAtMildHeatLastsTwoAndAHalfHoursAndElevenKilometres() {
        val s = WaterModel.supply(1.0, 15.0)
        assertEquals(150.0, s.minutes, 1e-9)
        assertEquals(11.25, s.rangeKm, 1e-9)
        assertEquals(0.0, WaterModel.supply(0.0, 15.0).rangeKm, 0.0)
        assertTrue(WaterModel.supply(1.0, 30.0).rangeKm < s.rangeKm)
    }

    private fun src(km: Double, status: WaterSource.Status = WaterSource.Status.POTABLE) =
        WaterSource(node(1, 0.0, 0.0), WaterSource.Kind.TAP, "x", status, km * 1000.0, null)

    @Test
    fun theVerdictIsFineWithAFifthToSpareTightWhenJustEnoughAndLowWhenNot() {
        val s = WaterModel.supply(0.4, 15.0) // 4.5 km
        assertEquals(WaterModel.Verdict.FINE, WaterModel.verdict(s, src(3.0)))
        assertEquals(WaterModel.Verdict.TIGHT, WaterModel.verdict(s, src(4.0)))
        assertEquals(WaterModel.Verdict.LOW, WaterModel.verdict(s, src(6.0)))
        assertEquals(WaterModel.Verdict.NO_SOURCE, WaterModel.verdict(s, null))
    }

    @Test
    fun theBottleFillsBetweenEmptyAndFullAndWalkingTakesTime() {
        assertEquals(0.4f, WaterModel.fill(0.4, 1.0), 1e-6f)
        assertEquals(1f, WaterModel.fill(2.0, 1.0), 0f)
        assertEquals(0f, WaterModel.fill(-1.0, 1.0), 0f)
        assertEquals(0f, WaterModel.fill(1.0, 0.0), 0f)
        assertEquals(40.0, WaterModel.walkMinutes(3_000.0), 1e-9)
    }

    private fun along(m: Double) = src(0.0).copy(alongM = m)

    @Test
    fun onATripTheMarksCountFromWhereYouAreAndWhatIsBehindYouIsLeftOut() {
        val all = listOf(along(1_000.0), along(5_000.0), along(8_000.0))
        // 4.9 km into the trip: the first spring is behind, the others are 100 m and 3.1 km ahead.
        val ahead = WaterModel.ahead(all, trip = true, travelled = 4_900.0)
        assertEquals(listOf(100.0, 3_100.0), ahead.map { it.alongM!! })
        // A source 30 m back is still reachable and counts as here.
        assertEquals(listOf(0.0), WaterModel.ahead(listOf(along(4_870.0)), trip = true, travelled = 4_900.0).map { it.alongM!! })
        // With no trip the list is as it was: distances from you.
        assertEquals(all, WaterModel.ahead(all, trip = false, travelled = 4_900.0))
        // The verdict uses what is ahead: 3.1 km against a 4.5 km bottle is fine.
        assertEquals(WaterModel.Verdict.FINE, WaterModel.verdict(WaterModel.supply(0.4, 15.0), ahead.last()))
    }
}
