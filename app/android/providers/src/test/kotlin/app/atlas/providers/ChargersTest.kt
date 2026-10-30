package app.atlas.providers

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.PolylineCodec
import app.atlas.model.Place
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.milliseconds

class ChargersTest {
    private val westerkerk = LatLon(52.3745, 4.8838)

    private fun json(name: String) = ProviderJson.parseToJsonElement(Fixtures.text(name))

    @Test
    fun socketsReadOsmTagging() {
        val tags = mapOf(
            "amenity" to "charging_station",
            "socket:type2_combo" to "6;3", "socket:type2_combo:output" to "350 kW;90 kW",
            "socket:type2" to "2", "socket:type2:output" to "22",
            "socket:chademo" to "yes",
        )
        val s = Sockets.of(tags)
        assertEquals(listOf(Socket("type2_combo", 6, 350.0), Socket("type2_combo", 3, 90.0), Socket("type2", 2, 22.0), Socket("chademo", 1, null)), s)
        assertEquals(350.0, Sockets.maxKw(tags)!!, 0.0)
        assertTrue(Sockets.hasDc(tags))
        assertEquals(11.0, Sockets.parseKw("11000 W")!!, 0.0)
        assertEquals(3.7, Sockets.parseKw("3,7 kW")!!, 1e-9)
        assertEquals(1000.0, Sockets.parseKw("1 MW")!!, 0.0)
        assertNull(Sockets.parseKw("fast"))
        // Without a unit OSM means kW, but no charger gives megawatts unlabelled: "22000" is watts written bare.
        assertEquals(22.0, Sockets.parseKw("22")!!, 0.0)
        assertEquals(350.0, Sockets.parseKw("350")!!, 0.0)
        assertEquals(22.0, Sockets.parseKw("22000")!!, 0.0)
        assertEquals(3700.0, Sockets.parseKw("3.7 MW")!!, 1e-9)
        assertTrue(Sockets.accepts(tags, setOf("type2_combo"), 150.0))
        assertFalse(Sockets.accepts(tags, setOf("type1_combo"), 0.0))
        assertTrue(Sockets.fits(setOf("type2"), "type2_cable"))
        assertTrue(Sockets.fits(setOf("type2_combo"), "tesla_supercharger_ccs"))
        val written = Sockets.tags(listOf(Socket("type2", 1, 22.0), Socket("type2", 1, 22.0), Socket("type2_combo", 2, 50.0)))
        assertEquals(mapOf("socket:type2" to "2", "socket:type2:output" to "22 kW", "socket:type2_combo" to "2", "socket:type2_combo:output" to "50 kW", Sockets.POWER_KW to "50"), written)
        assertEquals("type2_combo", Sockets.bnetza("DC Fahrzeugkupplung Typ Combo 2 (CCS)"))
        assertEquals("type2_cable", Sockets.bnetza("AC Typ 2 Fahrzeugkupplung"))
        assertEquals("type2", Sockets.bnetza("AC Typ 2 Steckdose"))
        assertEquals("schuko", Sockets.bnetza("AC Schuko"))
        assertEquals("type2_combo", Sockets.ocpi("IEC_62196_T2_COMBO"))
        assertEquals("type2_cable", Sockets.ocpi("IEC_62196_T2", cable = true))
    }

    @Test
    fun aSocketWithoutItsOwnPowerIsJudgedByItsKind() {
        val mixed = mapOf("socket:type2_combo" to "2", "socket:type2_combo:output" to "150 kW", "socket:type2" to "2")
        // The type 2 socket says nothing; it does not borrow the CCS socket's 150 kW: no AC socket gives 50 kW.
        assertFalse(Sockets.accepts(mixed, setOf("type2"), 50.0))
        assertTrue(Sockets.accepts(mixed, setOf("type2"), 11.0))
        assertTrue(Sockets.accepts(mixed, setOf("type2_combo"), 150.0))
        // Most OSM chargers carry no output at all: an unlabelled CCS socket counts as an ordinary fast charger
        // (50 kW), not as nothing and not as the fastest there is.
        val plain = mapOf("amenity" to "charging_station", "socket:type2_combo" to "2")
        assertTrue(Sockets.accepts(plain, setOf("type2_combo"), 50.0))
        assertFalse(Sockets.accepts(plain, setOf("type2_combo"), 150.0))
        // A station's own output stands for its unlabelled sockets, as far as their kind allows.
        val station = mapOf("socket:type2" to "4", "socket:type2_combo" to "1", "charging_station:output" to "300 kW")
        assertTrue(Sockets.accepts(station, setOf("type2_combo"), 150.0))
        assertFalse(Sockets.accepts(station, setOf("type2"), 50.0))
        assertEquals(22.0, Sockets.unlabelledKw("type2", 22.0), 0.0)
        assertEquals(43.0, Sockets.unlabelledKw("type2", 300.0), 0.0)
    }

    @Test
    fun openChargeMapKeepsOnlyLicensedData() {
        val pois = json("ocm_poi.json").asArray()!!.mapNotNull { it.asObject() }
        assertEquals(16, pois.size)
        val places = pois.mapNotNull(OpenChargeMap::toPlace)
        assertEquals(16, places.size)
        // Oplaadpalen.nl's POIs (data provider 26) are CC BY-NC-SA: dropped. (The recorded ones were taken out of the
        // fixture for the same reason; this one is a licensed POI relabelled.)
        val nonCommercial = kotlinx.serialization.json.JsonObject(pois.first() + ("DataProviderID" to kotlinx.serialization.json.JsonPrimitive(26)))
        assertNull(OpenChargeMap.toPlace(nonCommercial))
        // A provider Atlas has never checked is dropped too, rather than assumed open.
        assertNull(OpenChargeMap.toPlace(kotlinx.serialization.json.JsonObject(pois.first() + ("DataProviderID" to kotlinx.serialization.json.JsonPrimitive(9999)))))
        val elsgeest = places.first { it["name"] == "Fastned Elsgeest" }
        assertEquals("FastNed", elsgeest["operator"])
        assertEquals("2", elsgeest["socket:chademo"])
        assertEquals("50 kW", elsgeest["socket:chademo:output"])
        assertEquals("2", elsgeest["socket:type2_combo"])
        assertEquals("43 kW", elsgeest["socket:type2:output"])
        assertEquals("ocm:24636", elsgeest.extra["charger:id"])
        assertEquals("Open Charge Map · Open Charge Map contributors (CC BY 4.0)", elsgeest.extra["attribution"])
        val klein = places.first { it["name"] == "Parkeerterrein Klein Amerika" }
        assertEquals("no", klein["fee"])
        val powergo = places.first { it.extra["charger:id"] == "ocm:456481" }
        assertEquals("12", powergo["socket:type2_cable"])
        assertEquals("CC0", powergo.extra["licence"])
        assertEquals("yes", powergo["authentication:membership_card"])
        val tesla = places.first { it["name"] == "Tesla Supercharger Heerlen" }
        assertEquals("250", tesla[Sockets.POWER_KW])
    }

    @Test
    fun openChargeMapCostsSayWhetherChargingIsFree() {
        fun fee(cost: String) = OpenChargeMap.toPlace(
            ProviderJson.parseToJsonElement("""{"ID":9,"DataProviderID":1,"AddressInfo":{"Latitude":52.0,"Longitude":4.0},"UsageCost":"$cost"}""").asObject()!!,
        )!!["fee"]
        assertEquals("no", fee("Free"))
        assertEquals("no", fee("Gratis (alleen klanten)"))
        // Free parking is not free charging.
        assertEquals("yes", fee("€0,39/kWh, free parking"))
        assertEquals("yes", fee("Free parking; 0.45 EUR per kWh"))
        assertEquals("yes", fee("0,69"))
    }

    @Test
    fun openChargeMapLiveStatusPerConnection() {
        fun poi(vararg statuses: Int) = ProviderJson.parseToJsonElement(
            """{"ID":7,"DataProviderID":1,"StatusTypeID":50,"AddressInfo":{"Latitude":52.0,"Longitude":4.0},"Connections":[""" +
                statuses.joinToString(",") { """{"ConnectionTypeID":33,"PowerKW":150,"Quantity":1,"StatusTypeID":$it}""" } + "]}",
        ).asObject()!!
        assertEquals(ChargerStatus.State.AVAILABLE, OpenChargeMap.statusOf(poi(10, 20))!!.state)
        assertEquals(1, OpenChargeMap.statusOf(poi(10, 20))!!.available)
        assertEquals(ChargerStatus.State.OCCUPIED, OpenChargeMap.statusOf(poi(20, 30))!!.state)
        assertEquals(ChargerStatus.State.OUT_OF_ORDER, OpenChargeMap.statusOf(poi(30, 100))!!.state)
        assertEquals(ChargerStatus.State.OPERATIONAL, OpenChargeMap.statusOf(poi(50, 50))!!.state)
        assertNull(OpenChargeMap.statusOf(poi(50))!!.available)
        // One broken connector among working ones leaves the charger working, and nothing live is invented.
        val partly = OpenChargeMap.statusOf(poi(50, 100))!!
        assertEquals(ChargerStatus.State.OPERATIONAL, partly.state)
        assertNull(partly.available)
        assertTrue(ChargerFilters().accepts(Place(null, westerkerk, mapOf("amenity" to "charging_station"), extra = partly.toExtra())))
        // Live and broken together: the live one counts, the broken one is in the total.
        val mixed = OpenChargeMap.statusOf(poi(10, 100))!!
        assertEquals(ChargerStatus.State.AVAILABLE, mixed.state)
        assertEquals(1 to 2, mixed.sockets["type2_combo"])
        // A POI without a status of its own is as good as its working connections.
        val noOwn = ProviderJson.parseToJsonElement(
            """{"ID":8,"AddressInfo":{"Latitude":52.0,"Longitude":4.0},"Connections":[{"ConnectionTypeID":25,"StatusTypeID":50},{"ConnectionTypeID":25,"StatusTypeID":30}]}""",
        ).asObject()!!
        assertEquals(ChargerStatus.State.OPERATIONAL, OpenChargeMap.statusOf(noOwn)!!.state)
    }

    @Test
    fun aLongCorridorIsAskedInPiecesThatFitARequestLine() = runBlocking {
        Mock().use { m ->
            m.dispatch { response(Fixtures.text("ocm_poi.json")) }
            // 3,000 points zigzagging 300 m across a 1 km corridor, south past Hoofddorp and Klein Amerika: simplification
            // keeps nearly all of them.
            val line = List(3_000) { i -> Geo.destination(LatLon(52.37 - i * 0.0002, 4.7165), 90.0, if (i % 2 == 0) 0.0 else 300.0) }
            val simplified = app.atlas.geo.Shapes.simplify(line, 100.0)
            assertTrue(simplified.size > 2_900)
            val found = OpenChargeMap(testHttp(), Keys.of("ocm" to "k"), base = m.base).along(line, 1_000.0, ChargerFilters(maxResults = 5_000))
            val pieces = (0 until m.count).map { PolylineCodec.decode(m.take().url.queryParameter("polyline")!!) }
            assertTrue("pieces: ${pieces.size}", pieces.size > 1)
            assertTrue(pieces.all { PolylineCodec.encode(it).length <= OpenChargeMap.MAX_POLYLINE })
            // The pieces join end to end and cover the whole line.
            assertTrue(pieces.zipWithNext().all { (a, b) -> a.last() == b.first() })
            val joined = pieces.first() + pieces.drop(1).flatMap { it.drop(1) }
            assertEquals(simplified.size, joined.size)
            assertTrue(joined.zip(simplified).all { (a, b) -> Geo.distance(a, b) < 1.5 })
            // Every piece answered all 16 chargers; only the two in the corridor come back, once each, in order along it.
            assertEquals(listOf("ocm:456481", "ocm:19672"), found.map { it.extra["charger:id"] })
        }
    }

    @Test
    fun openChargeMapNearUsesTheKeyAndFilters() = runBlocking {
        Mock().use { m ->
            m.fixture("ocm_poi.json")
            val ocm = OpenChargeMap(testHttp(), Keys.NONE, appKey = "atlas-app-key", base = m.base)
            val near = ocm.near(LatLon(52.1, 5.1), 200_000.0, ChargerFilters(minKw = 100.0, connectors = setOf("type2_combo")))
            val req = m.take()
            assertEquals("atlas-app-key", req.headers["X-API-Key"])
            assertEquals("/poi", req.url.encodedPath)
            assertEquals("200", req.url.queryParameter("distance"))
            assertEquals("km", req.url.queryParameter("distanceunit"))
            assertEquals("100", req.url.queryParameter("minpowerkw"))
            assertEquals(listOf("33"), req.url.queryParameter("connectiontypeid")!!.split(','))
            assertTrue(near.isNotEmpty())
            assertTrue(near.all { (Sockets.maxKw(it.tags) ?: 0.0) >= 100 })
            val d = near.map { Geo.distance(LatLon(52.1, 5.1), it.location) }
            assertEquals(d.sorted(), d)
        }
    }

    @Test
    fun openChargeMapCorridorSendsAnEncodedLine() = runBlocking {
        Mock().use { m ->
            m.fixture("ocm_poi.json")
            val line = listOf(LatLon(52.37, 4.89), LatLon(52.09, 5.12), LatLon(51.44, 5.47))
            OpenChargeMap(testHttp(), Keys.of("ocm" to "mine"), base = m.base).along(line, 5_000.0)
            val req = m.take()
            assertEquals("mine", req.headers["X-API-Key"])
            assertEquals(line, PolylineCodec.decode(req.url.queryParameter("polyline")!!))
            assertEquals("5", req.url.queryParameter("distance"))
        }
    }

    @Test
    fun aSimplifiedCorridorIsWidenedAndHeldToTheRoute() = runBlocking {
        Mock().use { m ->
            m.dispatch { response(Fixtures.text("ocm_poi.json")) }
            // Fastned Elsgeest, Klein Amerika, Nieuwerkerk: a route that wobbles 50 m either side between them.
            val stops = listOf(LatLon(52.2002, 4.4716), LatLon(52.0117, 4.7135), LatLon(51.9784, 4.6229))
            val route = stops.zipWithNext().flatMap { (a, b) ->
                List(40) { i -> Geo.destination(Geo.interpolate(a, b, i / 40.0), Geo.bearing(a, b) + 90, if (i % 2 == 0) 50.0 else -50.0) }
            } + stops.last()
            val found = OpenChargeMap(testHttp(), Keys.of("ocm" to "k"), base = m.base).along(route, 3_000.0)
            val req = m.take()
            val sent = PolylineCodec.decode(req.url.queryParameter("polyline")!!)
            assertTrue(sent.size < route.size)
            // The wobble was simplified away, so the corridor asked for is wider by the tolerance: 3 km + 300 m.
            assertEquals("3.3", req.url.queryParameter("distance"))
            val sentLine = app.atlas.geo.Polyline(sent)
            assertTrue(route.all { sentLine.snap(it)!!.offset + 3_000.0 <= 3_300.0 })
            // Of the 16 chargers the server sent, the three by the route come back, in the order the route passes them.
            assertEquals(listOf("ocm:24636", "ocm:19672", "ocm:313052"), found.map { it.extra["charger:id"] })
        }
    }

    @Test
    fun openChargeMapWithoutAnyKeyNeedsOne() = runBlocking {
        val ocm = OpenChargeMap(testHttp(), Keys.NONE, appKey = null, base = "http://127.0.0.1:9")
        assertEquals(ProviderState.NEEDS_KEY, ocm.state())
        try {
            ocm.near(westerkerk, 1000.0)
            fail()
        } catch (_: NeedsKeyException) {
        }
    }

    @Test
    fun ndwLocationsBecomeChargersWithLiveStatus() {
        val places = ArrayList<Place>()
        NdwChargers.parse(ByteArrayInputStream(Fixtures.bytes("ndw_sample.json.gz")), null) { places.add(it) }
        // 101 locations, three of them marked "publish": false, which OCPI says must not be shown.
        assertEquals(98, places.size)
        assertTrue(places.all { it["amenity"] == "charging_station" && it.source == "ndw" })
        val total = places.filter { it["operator"] == "TotalEnergies" }
        assertTrue(total.size >= 20)
        val p = total.first()
        assertEquals("NL", p["addr:country"])
        assertNotNull(p.extra["status"])
        assertTrue(p.extra["charger:id"]!!.startsWith("ndw:"))
        val dc = places.filter { Sockets.hasDc(it.tags) }
        assertTrue(dc.size >= 3)
        assertTrue(dc.all { (Sockets.maxKw(it.tags) ?: 0.0) >= 20 })
        assertTrue(places.any { it.extra["status"] == "available" && it.extra["status:available"]!!.toInt() > 0 })
        // A region keeps memory small on a phone.
        var inside = 0
        NdwChargers.parse(ByteArrayInputStream(Fixtures.bytes("ndw_sample.json.gz")), app.atlas.geo.BBox.around(westerkerk, 500.0)) { inside++ }
        assertTrue(inside in 1 until 98)
    }

    @Test
    fun ocpiEvsesSetStatusAndPower() {
        val loc = ProviderJson.parseToJsonElement(
            """{"id":"L1","publish":true,"name":"Test","address":"Westermarkt 1","city":"Amsterdam","postal_code":"1016","country":"NLD",
            "coordinates":{"latitude":"52.3745","longitude":"4.8838"},"operator":{"name":"Fastned"},"opening_times":{"twentyfourseven":true},
            "energy_mix":{"is_green_energy":true},"parking_type":"PARKING_GARAGE",
            "evses":[{"uid":"1","status":"CHARGING","last_updated":"2026-10-13T10:00:00Z","connectors":[{"standard":"IEC_62196_T2_COMBO","format":"CABLE","power_type":"DC","max_electric_power":300000}]},
                     {"uid":"2","status":"AVAILABLE","last_updated":"2026-10-13T11:00:00Z","connectors":[{"standard":"IEC_62196_T2_COMBO","format":"CABLE","power_type":"DC","max_electric_power":300000},{"standard":"CHADEMO","format":"CABLE","power_type":"DC","max_voltage":500,"max_amperage":100}]},
                     {"uid":"3","status":"REMOVED","connectors":[{"standard":"IEC_62196_T2","format":"SOCKET","power_type":"AC_3_PHASE","max_voltage":230,"max_amperage":32}]}]}""",
        ).asObject()!!
        val p = Ocpi.toPlace(loc, "x")!!
        assertEquals("2", p["socket:type2_combo"])
        assertEquals("300 kW", p["socket:type2_combo:output"])
        assertEquals("50 kW", p["socket:chademo:output"])
        assertNull(p["socket:type2"])
        assertEquals("24/7", p["opening_hours"])
        assertEquals("yes", p["covered"])
        assertEquals("NL", p["addr:country"])
        assertEquals("yes", p.extra["energy:green"])
        assertEquals("available", p.extra["status"])
        assertEquals("1", p.extra["status:available"])
        assertEquals("1/2", p.extra["status:type2_combo"])
        assertEquals("2026-10-13T11:00:00Z", p.extra["status:updated"])
        assertNull(Ocpi.toPlace(ProviderJson.parseToJsonElement("""{"id":"L2","publish":false,"coordinates":{"latitude":1,"longitude":2}}""").asObject()!!, "x"))
    }

    @Test
    fun aLocationWhoseEveryEvseWasRemovedIsGone() {
        val loc = ProviderJson.parseToJsonElement(
            """{"id":"L3","coordinates":{"latitude":52.3745,"longitude":4.8838},"operator":{"name":"Vattenfall InCharge"},
            "evses":[{"uid":"1","status":"REMOVED","connectors":[{"standard":"IEC_62196_T2","format":"SOCKET","power_type":"AC_3_PHASE","max_voltage":230,"max_amperage":32}]},
                     {"uid":"2","status":"REMOVED","connectors":[{"standard":"IEC_62196_T2","format":"SOCKET","power_type":"AC_3_PHASE","max_voltage":230,"max_amperage":32}]}]}""",
        ).asObject()!!
        val p = Ocpi.toPlace(loc, "ndw")!!
        assertEquals("removed", p.extra["status"])
        assertFalse(ChargerFilters().accepts(p))
        assertTrue(ChargerFilters(includeNotWorking = true).accepts(p))
    }

    @Test
    fun filtersWithABlankOperatorFilterNothingOut() {
        val unnamed = Place(null, westerkerk, mapOf("amenity" to "charging_station", "socket:type2" to "2"))
        assertTrue(ChargerFilters(operator = " ").accepts(unnamed))
        assertFalse(ChargerFilters(operator = "Fastned").accepts(unnamed))
        assertTrue(ChargerFilters(operator = "fastned").accepts(unnamed.copy(tags = unnamed.tags + ("operator" to "Fastned B.V."))))
    }

    @Test
    fun openChargeMapThreePhasePowerFromAmpsAndVolts() {
        fun kw(volts: Int) = Sockets.maxKw(
            OpenChargeMap.toPlace(
                ProviderJson.parseToJsonElement(
                    """{"ID":10,"DataProviderID":1,"AddressInfo":{"Latitude":52.0,"Longitude":4.0},"Connections":[{"ConnectionTypeID":25,"CurrentTypeID":20,"Amps":32,"Voltage":$volts}]}""",
                ).asObject()!!,
            )!!.tags,
        )!!
        // 32 A on three phases is 22 kW, whether the record gives the line voltage (400 V) or the phase voltage (230 V).
        assertEquals(22.0, kw(400), 0.3)
        assertEquals(22.0, kw(230), 0.3)
    }

    @Test
    fun theRegisterParsesWithItsPreambleAndDecimalCommas() {
        val places = ArrayList<Place>()
        BnetzaChargers.parse(ByteArrayInputStream(Fixtures.bytes("bnetza_head.csv")), null) { places.add(it) }
        assertEquals(35, places.size)
        val first = places.first()
        assertEquals("bnetza:1010338", first.extra["charger:id"])
        assertEquals("Albwerk Elektro- und Kommunikationstechnik GmbH", first["operator"])
        assertEquals(48.442398, first.location.lat, 1e-9)
        assertEquals(9.659075, first.location.lon, 1e-9)
        assertEquals("2", first["socket:type2"])
        assertEquals("22 kW", first["socket:type2:output"])
        assertEquals("2", first["capacity"])
        assertEquals("2020-01-11", first["start_date"])
        assertEquals("24/7", first["opening_hours"])
        assertEquals("DEAEWE002501;DEAEWE002502", first["ref:EU:EVSE"])
        assertEquals("yes", first["authentication:membership_card"])
        assertEquals("yes", first["authentication:app"])
        assertEquals("operational", first.extra["status"])
        assertEquals("smopi", places[1]["brand"])
        assertTrue(places.any { it["socket:chademo"] != null })
        assertTrue(places.any { it["socket:schuko"] != null })
        assertTrue(places.any { (Sockets.maxKw(it.tags) ?: 0.0) >= 50 && Sockets.hasDc(it.tags) })
        // A public key field runs over several lines inside its quotes; the record still parses whole.
        val mvv = places.first { it.extra["charger:id"] == "bnetza:1157322" }
        assertEquals("MVV Energie AG", mvv["operator"])
        assertEquals("300 kW", mvv["socket:type2_combo:output"])
    }

    @Test
    fun registerOpeningHoursBecomeOsmRules() {
        assertEquals("24/7", BnetzaChargers.openingHours("247", null, null))
        val days = "Montag; Dienstag; Mittwoch; Donnerstag; Freitag; Samstag; Sonntag"
        assertEquals("24/7", BnetzaChargers.openingHours("Keine Angabe", days, List(7) { "00:00-23:59" }.joinToString("; ")))
        assertEquals("Mo-Fr 07:00-20:00; Sa 08:00-14:00", BnetzaChargers.openingHours("Eingeschränkt", "Montag; Dienstag; Mittwoch; Donnerstag; Freitag; Samstag", "07:00-20:00; 07:00-20:00; 07:00-20:00; 07:00-20:00; 07:00-20:00; 08:00-14:00"))
        assertNull(BnetzaChargers.openingHours("Keine Angabe", null, null))
        assertEquals(
            "https://data.bundesnetzagentur.de/Bundesnetzagentur/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Ladesaeulenregister_BNetzA_2026-09-01.csv",
            BnetzaChargers.csvLink(Fixtures.text("bnetza_page.html")),
        )
        assertEquals(listOf("a", "b;c", "d\"e", ""), Csv.split("a;\"b;c\";\"d\"\"e\";"))
    }

    @Test
    fun afdcStationsMapToOsmTerms() {
        val stations = (json("afdc_nearest.json").asObject()!!.arr("fuel_stations") + json("afdc_dc.json").asObject()!!.arr("fuel_stations")).mapNotNull { it.asObject() }
        val places = stations.mapNotNull(Afdc::toPlace)
        assertEquals(9, places.size)
        val evgo = places.first { it["name"] == "CityCenterDC" }
        assertEquals("eVgo Network", evgo["network"])
        assertEquals("200", evgo[Sockets.POWER_KW])
        assertTrue(evgo["socket:type1_combo"] != null && evgo["socket:chademo"] != null)
        assertEquals("24/7", evgo["opening_hours"])
        val ea = places.first { it["name"]!!.startsWith("Jamestown") }
        assertEquals("6", ea["socket:type1_combo"])
        assertEquals("350 kW", ea["socket:type1_combo:output"])
        val treasury = places.first { it["name"] == "US Dept of the Treasury/HQ" }
        assertEquals("private", treasury["access"])
        assertNull(treasury["network"])
        assertEquals("1", treasury["socket:nema_14_50"])
        assertEquals("operational", treasury.extra["status"])
    }

    @Test
    fun afdcNearAsksInMilesWithTheKey() = runBlocking {
        Mock().use { m ->
            m.fixture("afdc_dc.json")
            val afdc = Afdc(testHttp(), Keys.NONE, appKey = "DEMO_KEY", base = m.base)
            val near = afdc.near(LatLon(38.8977, -77.0365), 8_046.72, ChargerFilters(minKw = 50.0))
            val req = m.take()
            assertEquals("/nearest.json", req.url.encodedPath)
            assertEquals("DEMO_KEY", req.url.queryParameter("api_key"))
            assertEquals("5", req.url.queryParameter("radius"))
            assertEquals("dc_fast", req.url.queryParameter("ev_charging_level"))
            assertEquals(3, near.size)
        }
    }

    @Test
    fun chargemapIsHonestAboutHavingNoApi() = runBlocking {
        val cm = Chargemap(testHttp())
        assertEquals(ProviderState.UNAVAILABLE, cm.state())
        try {
            cm.near(westerkerk, 1000.0)
            fail()
        } catch (e: ProviderUnavailableException) {
            assertTrue(e.message!!.contains("no public API"))
        }
        assertEquals(KeyNeed.PARTNER, cm.info.keyNeed)
        assertFalse(cm.info.onByDefault)
    }

    @Test
    fun aPartnerOcpiFeedIsPagedWithItsToken() = runBlocking {
        Mock().use { m ->
            val locations = ArrayList<String>()
            java.util.zip.GZIPInputStream(ByteArrayInputStream(Fixtures.bytes("ndw_sample.json.gz"))).reader().use { r ->
                val arr = ProviderJson.parseToJsonElement(r.readText()).asArray()!!
                arr.take(6).forEach { locations.add(it.toString()) }
            }
            m.enqueue("""{"data":[${locations.take(4).joinToString(",")}],"status_code":1000}""", 200, "Link" to "<${m.url("/ocpi/locations?offset=4&limit=4")}>; rel=\"next\"")
            m.enqueue("""{"data":[${locations.drop(4).joinToString(",")}],"status_code":1000}""")
            val cm = Chargemap(testHttp(), Keys.of("chargemap" to "partner-token"), partnerEndpoint = m.url("/ocpi/locations"))
            assertEquals(ProviderState.READY, cm.state())
            val near = cm.near(westerkerk, 2_000.0, ChargerFilters())
            assertEquals(6, near.size)
            assertEquals("Token partner-token", m.take().headers["Authorization"])
            assertEquals("4", m.take().url.queryParameter("offset"))
            assertTrue(near.all { it.source == "chargemap" })
        }
    }

    @Test
    fun osmChargersComeThroughOverpass() = runBlocking {
        Mock().use { m ->
            m.fixture("overpass_chargers.json")
            val osm = OsmChargers(Overpass(testHttp(), listOf(m.url("/i"))))
            val near = osm.near(westerkerk, 1200.0, ChargerFilters())
            assertEquals(106, near.size)
            assertTrue(near.all { it.extra["charger:id"]!!.startsWith("osm:") })
            assertTrue(osm.status(near.map { it.extra["charger:id"]!! }).isEmpty())
            val d = near.map { Geo.distance(westerkerk, it.location) }
            assertEquals(d.sorted(), d)
        }
    }

    @Test
    fun chargerIndexAnswersNearAndSurvivesARoundTrip() = runBlocking {
        val places = ArrayList<Place>()
        NdwChargers.parse(ByteArrayInputStream(Fixtures.bytes("ndw_sample.json.gz")), null) { places.add(it) }
        val index = ChargerIndex(places, NdwChargers.INFO)
        assertEquals(98, index.size)
        val near = index.nearNow(westerkerk, 300.0)
        assertTrue(near.isNotEmpty())
        assertTrue(near.all { Geo.distance(westerkerk, it.location) <= 300 })
        val out = ByteArrayOutputStream()
        index.write(out)
        val back = ChargerIndex.read(ByteArrayInputStream(out.toByteArray()), NdwChargers.INFO)
        assertEquals(index.all(), back.all())
        val id = near.first().extra["charger:id"]!!
        assertEquals(near.first(), back[id])
        val status = back.status(listOf(id, "ndw:nope"))
        assertEquals(setOf(id), status.keys)
        assertEquals(ChargerStatus.State.entries.first { it.tag == near.first().extra["status"] }, status[id]!!.state)
        val dcOnly = index.nearNow(westerkerk, 5_000.0, ChargerFilters(minKw = 50.0))
        assertTrue(dcOnly.all { Sockets.hasDc(it.tags) })
    }

    @Test
    fun theDefaultCorridorSearchKeepsToTheCorridor() = runBlocking {
        val places = ArrayList<Place>()
        NdwChargers.parse(ByteArrayInputStream(Fixtures.bytes("ndw_sample.json.gz")), null) { places.add(it) }
        val index = ChargerIndex(places, NdwChargers.INFO)
        // Raadhuisstraat and Westermarkt, past the Westerkerk: a corridor of 150 m either side.
        val line = listOf(LatLon(52.3732, 4.8930), LatLon(52.3745, 4.8860), LatLon(52.3752, 4.8790))
        val corridor = 150.0
        val found = index.along(line, corridor)
        val polyline = app.atlas.geo.Polyline(line)
        val inside = index.all().filter { polyline.snap(it.location)!!.offset <= corridor }
        assertTrue(inside.isNotEmpty())
        assertEquals(inside.map { it.extra["charger:id"] }.toSet(), found.map { it.extra["charger:id"] }.toSet())
        // In the order the line passes them, as the interface promises.
        val passed = found.map { polyline.snap(it.location)!!.along }
        assertEquals(passed.sorted(), passed)
        // One point is a circle.
        assertTrue(index.along(listOf(westerkerk), 300.0).all { Geo.distance(westerkerk, it.location) <= 300.0 })
    }

    @Test
    fun aCorridorKeepsChargersAllTheWayAlongNotJustTheFirstFew() = runBlocking {
        // The EV planner asks with the default filters: a cap meant for one search around a point must not cut a route
        // short, leaving its second half without a single charger.
        val places = ArrayList<Place>()
        NdwChargers.parse(ByteArrayInputStream(Fixtures.bytes("ndw_sample.json.gz")), null) { places.add(it) }
        val index = ChargerIndex(places, NdwChargers.INFO)
        val line = listOf(LatLon(52.3732, 4.8930), LatLon(52.3745, 4.8860), LatLon(52.3752, 4.8790))
        val all = index.along(line, 400.0, ChargerFilters(maxResults = 1_000))
        val capped = index.along(line, 400.0, ChargerFilters(maxResults = 10))
        assertTrue(all.size > 10)
        assertEquals(all, capped)
        Mock().use { m ->
            m.fixture("overpass_chargers.json")
            val osm = OsmChargers(Overpass(testHttp(), listOf(m.url("/i")))).along(line, 1_200.0, ChargerFilters(maxResults = 10))
            assertTrue(osm.size > 10)
        }
    }

    @Test
    fun corridorFilteringIsExactAndQuickOnALongRoute() {
        // A 400 km route of 20,000 points winding east and west, and chargers scattered up to 6 km either side of it.
        val route = List(20_000) { i -> LatLon(50.0 + i * 0.00018, 5.0 + 0.05 * kotlin.math.sin(i / 500.0)) }
        val rnd = java.util.Random(7)
        fun scatter(n: Int) = List(n) { k ->
            val on = route[rnd.nextInt(route.size)]
            Place(null, LatLon(on.lat + (rnd.nextDouble() - 0.5) * 0.02, on.lon + (rnd.nextDouble() - 0.5) * 0.17), mapOf("amenity" to "charging_station"), source = "t", extra = mapOf("charger:id" to "t:$k"))
        }
        val line = app.atlas.geo.Polyline(route)
        val few = scatter(200)
        val expected = few.filter { line.snap(it.location)!!.offset <= 2_000.0 }.sortedBy { line.snap(it.location)!!.along }
        assertTrue(expected.size in 20..180)
        assertEquals(expected, inCorridor(route, 2_000.0, few))
        val many = scatter(5_000)
        val start = System.nanoTime()
        val kept = inCorridor(route, 2_000.0, many)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(kept.isNotEmpty())
        assertTrue("5,000 chargers against 20,000 points took $ms ms", ms < 2_000)
    }

    @Test
    fun aPartnerFeedGivesLiveStatusByLocation() = runBlocking {
        Mock().use { m ->
            val first = java.util.zip.GZIPInputStream(ByteArrayInputStream(Fixtures.bytes("ndw_sample.json.gz"))).reader().use { r ->
                ProviderJson.parseToJsonElement(r.readText()).asArray()!!.first { it.asObject()!!.bool("publish") != false }.asObject()!!
            }
            val id = first.str("id")!!
            m.enqueue("""{"data":$first,"status_code":1000}""")
            val feed = OcpiLocations(testHttp(), m.url("/ocpi/locations"), "token", "partner")
            val status = feed.status(listOf("partner:$id", "other:1"))
            assertEquals(setOf("partner:$id"), status.keys)
            assertEquals(1, m.count)
            assertEquals("/ocpi/locations/$id", m.take().url.encodedPath)
        }
    }

    @Test
    fun mergingOsmAndNdwJoinsTheSameChargers() {
        val osm = OverpassParser.parse(java.io.StringReader(Fixtures.text("overpass_chargers.json"))).places().map(OsmChargers::withId)
        val ndw = ArrayList<Place>()
        NdwChargers.parse(ByteArrayInputStream(Fixtures.bytes("ndw_sample.json.gz")), null) { ndw.add(it) }
        val merged = ChargerMerge.merge(listOf(ndw, osm))
        val joined = merged.filter { it.extra["sources"] == "osm,ndw" }
        println("MERGE ${osm.size} OSM + ${ndw.size} NDW chargers within 1.2 km of the Westerkerk -> ${merged.size}; ${joined.size} joined")
        assertTrue("joined ${joined.size}", joined.size >= 10)
        assertTrue(merged.size < osm.size + ndw.size)
        // An OSM charger leads: its position, its id; NDW adds live status and its own id.
        val j = joined.first()
        assertNotNull(j.id)
        assertEquals("ndw", j.extra["status:source"])
        assertTrue(j.extra["charger:ids"]!!.contains("ndw:"))
        // Different operators 10 m apart stay apart.
        val a = Place(null, westerkerk, mapOf("amenity" to "charging_station", "operator" to "Fastned"), source = "ocm")
        val b = Place(null, Geo.destination(westerkerk, 90.0, 10.0), mapOf("amenity" to "charging_station", "operator" to "Shell Recharge"), source = "ndw")
        assertEquals(2, ChargerMerge.merge(listOf(listOf(a), listOf(b))).size)
        val c = b.copy(tags = b.tags + ("operator" to "Fastned B.V."))
        assertEquals(1, ChargerMerge.merge(listOf(listOf(a), listOf(c))).size)
        // Distances are true ones wherever the chargers are: here the first record lies near the North Cape, which a
        // flat frame centred there would use to shrink east–west distances at the equator to a third.
        val north = Place(null, LatLon(70.98, 25.97), mapOf("amenity" to "charging_station", "operator" to "Mer"), source = "osm")
        val e1 = Place(null, LatLon(0.0, 30.0), mapOf("amenity" to "charging_station", "operator" to "Fastned"), source = "ocm")
        val e2 = Place(null, Geo.destination(e1.location, 90.0, 60.0), mapOf("amenity" to "charging_station", "operator" to "Fastned"), source = "ndw")
        assertEquals(3, ChargerMerge.merge(listOf(listOf(north), listOf(e1), listOf(e2))).size)
        val e3 = Place(null, Geo.destination(e1.location, 90.0, 20.0), e2.tags, source = "ndw")
        assertEquals(2, ChargerMerge.merge(listOf(listOf(north), listOf(e1), listOf(e3))).size)
        // Without an operator on one side only very close records join.
        val far = Place(null, Geo.destination(westerkerk, 0.0, 25.0), mapOf("amenity" to "charging_station"), source = "bnetza")
        assertEquals(2, ChargerMerge.merge(listOf(listOf(a), listOf(far))).size)
    }

    @Test
    fun aNearbySearchSendsItsCentreToAboutAHundredMetres() = runBlocking {
        Mock().use { m ->
            m.enqueue("[]")
            OpenChargeMap(testHttp(), Keys.of("ocm" to "k"), base = m.base).near(LatLon(52.374512, 4.883987), 5_000.0)
            val req = m.take()
            assertEquals("52.375", req.url.queryParameter("latitude"))
            assertEquals("4.884", req.url.queryParameter("longitude"))
        }
    }

    @Test
    fun aSlowOrFailingChargerSourceNeitherHoldsNorFailsTheOthers() = runBlocking {
        val ndw = listOf(Place(null, westerkerk, mapOf("amenity" to "charging_station"), source = "ndw"))
        val t0 = System.nanoTime()
        // the OSM chargers behind a stuck Overpass server, a register whose answer does not parse, the Dutch register
        val got = ChargerMerge.gather(listOf("osm", "broken", "ndw"), 300.milliseconds) { id ->
            when (id) {
                "osm" -> { delay(60_000); ndw }
                "broken" -> throw IllegalStateException("not JSON")
                else -> ndw
            }
        }
        assertTrue("took ${(System.nanoTime() - t0) / 1_000_000} ms", System.nanoTime() - t0 < 5_000_000_000L)
        assertEquals(listOf(emptyList(), emptyList(), ndw), got)
    }
}
