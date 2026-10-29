package app.atlas.ui.mine

import app.atlas.Services
import app.atlas.testing.keepFixtureForecast
import app.atlas.geo.BBox
import app.atlas.services.RegionLayer
import app.atlas.tiles.PackDownloader
import app.atlas.ui.mine.offline.LayerRecord
import app.atlas.ui.mine.offline.MapKind
import app.atlas.ui.mine.offline.RegionDeps
import app.atlas.ui.mine.offline.RegionMeta
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

/** Regions for the mine screens' tests: real files of the sizes a download would leave (sparse), and fakes for the network. */
object MineFixtures {
    /** The canal ring of Amsterdam, around the Westerkerk. */
    val AMSTERDAM = BBox(4.80, 52.33, 4.99, 52.42)
    val HAARLEM = BBox(4.55, 52.33, 4.70, 52.43)
    val LISBON = BBox(-9.23, 38.69, -9.09, 38.80)

    fun sparse(file: File, bytes: Long): File {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(bytes) }
        return file
    }

    /** A dependency set that never touches the network: no packs, nothing to import. */
    fun offlineDeps(services: Services, now: Instant) = RegionDeps(
        packs = { emptyList() },
        packDownloader = PackDownloader(),
        importPlaces = { _, _, _, _ -> error("offline") },
        segments = services.segments,
        clock = { now.toEpochMilli() },
    )

    private fun packFile(kind: String, name: String, mb: Long) = app.atlas.tiles.PackFile(kind, name, mb * 1_000_000, null, listOf(app.atlas.tiles.PackPart("https://example.test/$name", mb * 1_000_000, null)))

    /** What Atlas's published list could say for three countries (test data: the sizes are of the order of the real packs). */
    fun packs(): List<app.atlas.tiles.Pack> = listOf(
        app.atlas.tiles.Pack("netherlands", "Netherlands", "NL", BBox(3.2, 50.7, 7.3, 53.6), null, "packs-test", listOf(packFile("atlas", "netherlands.atlas", 640), packFile("places", "netherlands.places", 210))),
        app.atlas.tiles.Pack("belgium", "Belgium", "BE", BBox(2.5, 49.5, 6.4, 51.5), null, "packs-test", listOf(packFile("atlas", "belgium.atlas", 520), packFile("places", "belgium.places", 150))),
        app.atlas.tiles.Pack("portugal", "Portugal", "PT", BBox(-9.6, 36.9, -6.1, 42.2), null, "packs-test", listOf(packFile("atlas", "portugal.atlas", 480), packFile("places", "portugal.places", 130))),
    )

    /** Like [offlineDeps], with the published list of [packs] and a map builder that would build small areas on the phone. */
    fun packDeps(services: Services, now: Instant) = RegionDeps(
        packs = { packs() },
        packDownloader = PackDownloader(),
        importPlaces = { _, _, _, _ -> error("offline") },
        segments = services.segments,
        mapBuilder = { _, _, _, _, _ -> error("offline") },
        clock = { now.toEpochMilli() },
    )

    /** Writes a finished region with its layers' files: [mapMb] of atlas pack, [placesMb] of places, a routing tile of [routeMb]. */
    fun region(
        services: Services,
        name: String,
        box: BBox,
        mapMb: Long,
        placesMb: Long = 0,
        routeMb: Long = 0,
        at: Instant,
        voices: List<String> = emptyList(),
    ): RegionMeta {
        val files = services.mine.files
        val id = app.atlas.ui.mine.offline.RegionEngine.idFor(name)
        val layers = LinkedHashMap<RegionLayer, LayerRecord>()
        if (mapMb > 0) { sparse(files.atlas(id), mapMb * 1_000_000); layers[RegionLayer.MAP] = LayerRecord(MapKind.ATLAS.id, mapMb * 1_000_000, at.toEpochMilli(), "sha-old") }
        if (placesMb > 0) { sparse(files.places(id), placesMb * 1_000_000); layers[RegionLayer.PLACES] = LayerRecord("pack", placesMb * 1_000_000, at.toEpochMilli(), "p-old") }
        val tiles = if (routeMb > 0) listOf("E0_N50.rd5") else emptyList()
        if (routeMb > 0) { sparse(services.segments.file("E0_N50.rd5"), routeMb * 1_000_000); layers[RegionLayer.ROUTING] = LayerRecord("brouter", routeMb * 1_000_000, at.toEpochMilli()) }
        if (voices.isNotEmpty()) layers[RegionLayer.VOICE] = LayerRecord("tts", 0, at.toEpochMilli())
        val meta = RegionMeta(id, name, box, at.toEpochMilli(), at.toEpochMilli(), packId = id, layers = layers, segments = tiles, voices = voices, wanted = layers.keys.toSet())
        files.save(meta)
        return meta
    }

    /**
     * A drive from the Westerkerk by Haarlem and The Hague to Rotterdam, as straight stretches sampled every few hundred
     * metres (test data: the roads are the geometry, not the router's), with its departure, two turns and arrival.
     */
    fun trip(): app.atlas.model.Route {
        val way = listOf(app.atlas.geo.LatLon(52.3733, 4.8840), app.atlas.geo.LatLon(52.3874, 4.6462), app.atlas.geo.LatLon(52.0705, 4.3007), app.atlas.geo.LatLon(51.9225, 4.4792))
        val points = ArrayList<app.atlas.geo.LatLon>()
        val at = ArrayList<Int>()
        for (i in 0 until way.size - 1) {
            val n = (app.atlas.geo.Geo.distance(way[i], way[i + 1]) / 400.0).toInt().coerceAtLeast(1)
            at += points.size
            for (k in 0 until n) points += app.atlas.geo.LatLon(way[i].lat + (way[i + 1].lat - way[i].lat) * k / n, way[i].lon + (way[i + 1].lon - way[i].lon) * k / n)
        }
        points += way.last()
        at += points.size - 1
        fun m(t: app.atlas.model.ManeuverType, turn: app.atlas.model.Turn = app.atlas.model.Turn.STRAIGHT) = app.atlas.model.Maneuver(t, turn)
        val kinds = listOf(m(app.atlas.model.ManeuverType.DEPART), m(app.atlas.model.ManeuverType.TURN, app.atlas.model.Turn.LEFT), m(app.atlas.model.ManeuverType.TURN, app.atlas.model.Turn.RIGHT), m(app.atlas.model.ManeuverType.ARRIVE))
        val steps = kinds.mapIndexed { i, k ->
            val next = at.getOrNull(i + 1) ?: at[i]
            val d = (at[i] until next).sumOf { app.atlas.geo.Geo.distance(points[it], points[it + 1]) }
            app.atlas.model.Step(k, at[i], points[at[i]], d, d / 24.0)
        }
        val distance = steps.sumOf { it.distance }
        return app.atlas.model.Route(app.atlas.model.Mode.CAR, points, distance = distance, duration = distance / 24.0, steps = steps, source = "test")
    }

    /** Starts guiding [trip] on the test phone, so screens that follow the active route have one. */
    fun startTrip(services: Services, context: android.content.Context, name: String = "Rotterdam") {
        val route = trip()
        val req = app.atlas.model.RouteRequest(app.atlas.model.Mode.CAR, listOf(route.points.first(), route.points.last()), vehicle = app.atlas.ui.go.GoStore.defaultVehicle(app.atlas.model.Mode.CAR))
        val settings = kotlinx.coroutines.runBlocking { services.go.guideSettings(app.atlas.model.Mode.CAR, req.vehicle) }
        services.go.startNav(context, req, route, name, settings, demo = false)
    }

    /** A sky as a phone in Amsterdam might report it: ten GPS, seven Galileo, six GLONASS and six BeiDou satellites, most used. */
    fun sky(): app.atlas.services.GnssInfo {
        val rnd = java.util.Random(7)
        val list = ArrayList<app.atlas.services.Satellite>()
        fun add(constellation: Int, count: Int, first: Int) {
            for (i in 0 until count) list += app.atlas.services.Satellite(first + i * 3, constellation, 24f + rnd.nextFloat() * 24f, rnd.nextFloat() * 360f, 8f + rnd.nextFloat() * 80f, rnd.nextFloat() > 0.18f)
        }
        add(1, 10, 2); add(6, 7, 4); add(3, 6, 3); add(5, 6, 22)
        return app.atlas.services.GnssInfo(list, java.time.Instant.parse("2026-10-13T17:29:55Z"))
    }

    /** A location source with a fixed fix and a sky, for screens that read the satellites. */
    class GnssPhone(at: app.atlas.geo.LatLon, sky: app.atlas.services.GnssInfo) : app.atlas.services.LocationSource {
        private val fix = app.atlas.model.Fix(at, java.time.Instant.parse("2026-10-13T17:29:58Z"), 2.8)
        override fun fixes(quality: app.atlas.services.LocationQuality) = kotlinx.coroutines.flow.flowOf(fix)
        override val last = kotlinx.coroutines.flow.MutableStateFlow<app.atlas.model.Fix?>(fix)
        override val gnss = kotlinx.coroutines.flow.MutableStateFlow<app.atlas.services.GnssInfo?>(sky)
        override val permitted = kotlinx.coroutines.flow.MutableStateFlow(true)
        override fun refreshPermission() = Unit
    }

    /** Keeps a real Open-Meteo answer for five models (fetched for the Westerkerk) on the test phone, as if fetched now. */
    fun keepModels(services: Services) {
        val text = MineFixtures::class.java.getResourceAsStream("/mine/models_amsterdam.json")!!.bufferedReader().use { it.readText() }
        val dir = File(services.dirs.cache, "providers").apply { mkdirs() }
        File(dir, "models_52.37_4.88.json").writeText("${services.clock.instant().epochSecond}\n$text")
    }

    private fun node(id: Long, lat: Double, lon: Double, tags: Map<String, String>, extra: Map<String, String> = emptyMap()) =
        app.atlas.model.Place(app.atlas.model.OsmId(app.atlas.model.OsmType.NODE, id), app.atlas.geo.LatLon(lat, lon), tags, extra = extra)

    /**
     * Things by the road near the Westerkerk, written as OpenStreetMap writes them (test data, not shipped): eight charging
     * sites with sockets, powers, prices and, for some, live state; four petrol stations with their fuels; a spring, a tap
     * and a well. Used by the charge, pump and water screens' tests.
     */
    fun roadside(): app.atlas.search.MemoryPlaces {
        val w = Services.WESTERKERK
        fun charger(id: Long, dLat: Double, dLon: Double, operator: String, sockets: Map<String, Pair<Int, Int>>, kw: Map<String, Int>, price: String?, free: Pair<Int, Int>?, perSocket: Map<String, String> = emptyMap()): app.atlas.model.Place {
            val tags = LinkedHashMap<String, String>()
            tags["amenity"] = "charging_station"
            tags["operator"] = operator
            tags["capacity"] = sockets.values.sumOf { it.first }.toString()
            for ((t, c) in sockets) { tags["socket:$t"] = c.first.toString(); tags["socket:$t:output"] = "${kw.getValue(t)} kW" }
            if (price != null) { tags["fee"] = "yes"; tags["charge"] = price }
            val extra = LinkedHashMap<String, String>()
            if (free != null) { extra["status"] = if (free.first > 0) "available" else "occupied"; extra["status:available"] = free.first.toString(); extra["status:total"] = free.second.toString(); extra["charger:id"] = "ocm:$id" }
            extra.putAll(perSocket)
            return node(id, w.lat + dLat, w.lon + dLon, tags, extra)
        }
        val places = listOf(
            charger(9_000_001, 0.0019, -0.0012, "Voltra Hub", mapOf("type2_combo" to (4 to 150), "type2" to (2 to 22)), mapOf("type2_combo" to 150, "type2" to 22), "0.49 EUR/kWh", 4 to 6, mapOf("status:type2_combo" to "3/4", "status:type2" to "1/2")),
            charger(9_000_002, 0.0050, 0.0021, "Kent Ave kerbside", mapOf("type2" to (4 to 22)), mapOf("type2" to 22), "0.39 EUR/kWh", 2 to 4),
            charger(9_000_003, -0.0042, 0.0016, "Ridge Supercharge", mapOf("type2_combo" to (4 to 300)), mapOf("type2_combo" to 300), "0.59 EUR/kWh", 0 to 4),
            charger(9_000_004, 0.0021, 0.0058, "Linden Garage", mapOf("type2" to (8 to 22)), mapOf("type2" to 22), "0.39 EUR/kWh", 6 to 8),
            charger(9_000_005, -0.0011, -0.0063, "Fresko car park", mapOf("type2_combo" to (2 to 50), "chademo" to (1 to 50)), mapOf("type2_combo" to 50, "chademo" to 50), "0.45 EUR/kWh", 1 to 3),
            charger(9_000_006, 0.0063, -0.0034, "Dock 9 Power", mapOf("type2_combo" to (4 to 300)), mapOf("type2_combo" to 300), "0.55 EUR/kWh", 3 to 4),
            charger(9_000_007, 0.0079, -0.0045, "Harbor Hub", mapOf("type2_combo" to (4 to 150)), mapOf("type2_combo" to 150), "0.52 EUR/kWh", 2 to 4),
            charger(9_000_008, -0.0058, -0.0031, "Mill St", mapOf("type2_combo" to (2 to 50)), mapOf("type2_combo" to 50), "0.44 EUR/kWh", 2 to 2),
        )
        fun fuel(id: Long, dLat: Double, dLon: Double, brand: String, street: String, vararg fuels: String): app.atlas.model.Place {
            val tags = linkedMapOf("amenity" to "fuel", "brand" to brand, "name" to brand, "addr:street" to street, "opening_hours" to "24/7", "shop" to "convenience".takeIf { brand != "Freeway" }.orEmpty())
            tags.remove("shop", ""); if (brand != "Freeway") tags["shop"] = "convenience"
            for (f in fuels) tags["fuel:$f"] = "yes"
            if (brand == "Nordoil") { tags["car_wash"] = "yes"; tags["compressed_air"] = "yes" }
            return node(id, w.lat + dLat, w.lon + dLon, tags)
        }
        val fuels = listOf(
            fuel(9_100_001, 0.0070, 0.0050, "Nordoil", "Kent Ave", "e10", "octane_95", "octane_98", "diesel", "adblue"),
            fuel(9_100_002, -0.0090, 0.0080, "Freeway", "Harbor Rd", "octane_95", "diesel", "lpg", "adblue"),
            fuel(9_100_003, 0.0030, -0.0100, "Voltrol", "Linden St", "e10", "octane_95", "diesel"),
            fuel(9_100_004, -0.0030, 0.0040, "Pumpkin", "Mill St", "octane_95", "octane_98", "diesel"),
        )
        val water = listOf(
            node(9_200_001, w.lat + 0.0010, w.lon + 0.0010, mapOf("natural" to "spring", "name" to "Beech spring", "drinking_water" to "yes")),
            node(9_200_002, w.lat - 0.0020, w.lon + 0.0030, mapOf("amenity" to "drinking_water", "name" to "Ridge hut tap", "drinking_water" to "yes")),
            node(9_200_003, w.lat + 0.0040, w.lon - 0.0030, mapOf("man_made" to "water_well", "name" to "Old hand pump", "seasonal" to "yes", "pump" to "manual")),
            node(9_200_004, w.lat - 0.0040, w.lon - 0.0040, mapOf("amenity" to "drinking_water", "drinking_water" to "no", "name" to "Fountain")),
        )
        val power = listOf(
            node(9_300_001, w.lat - 0.0003, w.lon + 0.0007, mapOf("amenity" to "device_charging_station", "name" to "Power-bank locker", "socket:usb" to "4", "fee" to "yes", "charge" to "2 EUR/hour")),
            node(9_300_002, w.lat + 0.0020, w.lon - 0.0010, mapOf("amenity" to "cafe", "name" to "Café Molen", "power_supply" to "yes", "socket:schuko" to "6", "socket:usb" to "6")),
            node(9_300_003, w.lat + 0.0035, w.lon + 0.0030, mapOf("public_transport" to "station", "name" to "Station pillar", "power_supply" to "yes", "socket:usb" to "2", "fee" to "no")),
            node(9_300_004, w.lat - 0.0040, w.lon - 0.0010, mapOf("amenity" to "library", "name" to "Library study desks", "power_supply" to "yes", "fee" to "no")),
            node(9_300_005, w.lat + 0.0080, w.lon + 0.0100, mapOf("tourism" to "camp_site", "name" to "Stonewood", "power_supply" to "yes")),
        )
        return app.atlas.search.MemoryPlaces(places + fuels + water + power)
    }

    /** A phone at the Westerkerk with the Amsterdam extract and the [roadside] fixtures. */
    fun phoneWithRoadside(context: android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()): Services = Services(
        context,
        app.atlas.ServicesConfig(
            root = java.nio.file.Files.createTempDirectory("atlas-mine").toFile(),
            demoLocation = Services.WESTERKERK,
            offline = true,
            mapEngine = false,
            placeStores = listOf(app.atlas.testing.Fixtures.amsStore, roadside()),
            clock = app.atlas.testing.Fixtures.evening,
        ),
    ).also { it.keepFixtureForecast() }
}
