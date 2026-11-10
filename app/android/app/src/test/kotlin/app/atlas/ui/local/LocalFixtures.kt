package app.atlas.ui.local

import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Real OpenStreetMap places around the Westerkerk (an Overpass answer of 15 October 2026, trimmed), for the nature screens. */
object LocalFixtures {
    private fun resource(name: String) = LocalFixtures::class.java.getResourceAsStream("/local/$name")!!.bufferedReader().readText()

    /** The places of `nature_ams.json`: markets, parks, pools, saunas, playgrounds, dog parks, picnic tables and more. */
    val amsterdam: List<Place> by lazy {
        (Json.parseToJsonElement(resource("nature_ams.json")) as JsonArray).map { e ->
            val o = e as JsonObject
            val type = when ((o["t"] as JsonPrimitive).content) { "n" -> OsmType.NODE; "w" -> OsmType.WAY; else -> OsmType.RELATION }
            val tags = (o["tags"] as JsonObject).mapValues { (it.value as JsonPrimitive).content }
            Place(OsmId(type, (o["i"] as JsonPrimitive).longOrNull ?: 0L), LatLon((o["lat"] as JsonPrimitive).doubleOrNull!!, (o["lon"] as JsonPrimitive).doubleOrNull!!), tags)
        }
    }

    /** Makes [services]' finder answer from [places] (nothing else), as the place store or Overpass would. */
    fun answerWith(services: Services, places: List<Place> = amsterdam) {
        services.local.finder.fake = { filter, _, _ -> places.filter { filter.matches(it.tags) } }
    }

    /** Wikidata as it answered on 16 October 2026 for Rembrandtplein (Q1579642) and Rembrandt (Q5598), trimmed. */
    val etymology: Etymology by lazy {
        val all = resource("wikidata_rembrandtplein.json")
        Etymology { url ->
            when {
                "wbsearchentities" in url -> """{"search":[{"id":"Q19464658"},{"id":"Q1579642"}]}"""
                // The items asked for, from the one canned answer.
                else -> {
                    val wanted = Regex("ids=([^&]+)").find(url)!!.groupValues[1].split("%7C", "|")
                    val root = Json.parseToJsonElement(all) as JsonObject
                    val entities = (root["entities"] as JsonObject).filterKeys { it in wanted }
                    JsonObject(mapOf("entities" to JsonObject(entities))).toString()
                }
            }
        }
    }

    /** The street Rembrandtplein as the map has it, without any etymology tag. */
    val rembrandtplein = Place(OsmId(OsmType.WAY, 4_001L), LatLon(52.36605, 4.89664), mapOf("highway" to "pedestrian", "name" to "Rembrandtplein"))

    /** Wikidata as it answered on 16 October 2026 for the Westerkerk (Q1130722), its architect, style and designation, trimmed. */
    val westerkerk: Etymology by lazy {
        val all = Json.parseToJsonElement(resource("wikidata_westerkerk.json")) as JsonObject
        Etymology { url ->
            val wanted = Regex("ids=([^&]+)").find(url)!!.groupValues[1].split("%7C", "|")
            JsonObject(mapOf("entities" to JsonObject((all["entities"] as JsonObject).filterKeys { it in wanted }))).toString()
        }
    }

    /**
     * A marine forecast for the clock's evening whose sea level follows a twelve-and-a-half-hour tide of a metre and a half and whose
     * water is 16 degrees: invented, to look at the tide clock (Amsterdam has no tides in the model).
     */
    fun tideAt(now: java.time.Instant): app.atlas.providers.MarineForecast {
        val start = now.minusSeconds(6 * 3600)
        fun state(t: java.time.Instant, level: Double) = app.atlas.providers.SeaState(t, 0.4, 270.0, 4.0, 0.2, 0.3, 270.0, 8.0, 0.0, 0.0, 16.0, level)
        val hours = (0..36).map { h ->
            val t = start.plusSeconds(h * 3600L)
            val phase = h / 12.42 * 2 * Math.PI
            state(t, 0.75 * Math.cos(phase - 2.0))
        }
        return app.atlas.providers.MarineForecast(LatLon(52.37, 4.89), hours.first { !it.time.isBefore(now) }, hours, now)
    }

    /** Real OpenStreetMap elements of Westerpark, Amsterdam, with its paths and everything marked inside it (Overpass, 16 October 2026). */
    val westerparkElements: List<app.atlas.providers.OverpassElement> by lazy {
        app.atlas.providers.OverpassParser.parse(java.io.StringReader(resource("park_westerpark.json"))).elements
    }

    /** The park itself, as the place store would hold it: tags and box, no outline. */
    val westerpark: Place by lazy {
        val e = westerparkElements.first { it.tags["name"] == "Westerpark" }
        e.toPlace()!!
    }

    /** Makes the screens see Westerpark: the park, its POIs by filter, and its outline from the elements. */
    fun answerWithWesterpark(services: Services) {
        val pois = westerparkElements.filter { it.tags["leisure"] != null || it.tags["amenity"] != null || it.tags["natural"] == "tree" }.mapNotNull { it.toPlace() }
        services.local.finder.fake = { filter, _, _ -> (pois + westerpark).filter { filter.matches(it.tags) } }
        services.local.parkShape = { park, _ -> app.atlas.ui.local.Parkland.shapeOf(westerparkElements, park.id!!) }
    }

    /**
     * Stonewood National Park: an invented national park some 10 km north-east of the Westerkerk, about 6 by 4 km with a
     * lake left out of it, made up for the park page's wild sign (no such park exists, so its numbers are nobody's).
     */
    private val stonewoodRing = listOf(
        LatLon(52.4330, 4.9600), LatLon(52.4318, 4.9790), LatLon(52.4342, 5.0010), LatLon(52.4322, 5.0230), LatLon(52.4368, 5.0420),
        LatLon(52.4478, 5.0448), LatLon(52.4592, 5.0382), LatLon(52.4676, 5.0248), LatLon(52.4661, 5.0052), LatLon(52.4686, 4.9852),
        LatLon(52.4631, 4.9641), LatLon(52.4540, 4.9558), LatLon(52.4428, 4.9579),
    )
    private val stonewoodLake = listOf(LatLon(52.4490, 5.0060), LatLon(52.4484, 5.0172), LatLon(52.4531, 5.0218), LatLon(52.4572, 5.0151), LatLon(52.4561, 5.0058), LatLon(52.4521, 5.0029))

    val stonewood: Place by lazy {
        val box = app.atlas.geo.BBox(4.9558, 52.4318, 5.0448, 52.4686)
        Place(
            OsmId(OsmType.RELATION, 990_001), box.center,
            mapOf("boundary" to "national_park", "protect_class" to "2", "name" to "Stonewood National Park", "dog" to "leashed", "drone" to "no", "openfire" to "designated"),
            box,
        )
    }

    /** What is marked in Stonewood: where to ask and to sleep, toilets, water, tables, a café, three trails, an old oak, a hide and a fen. */
    val stonewoodPlaces: List<Place> by lazy {
        var id = 990_100L
        fun at(lat: Double, lon: Double, vararg tags: Pair<String, String>, type: OsmType = OsmType.NODE) = Place(OsmId(type, ++id), LatLon(lat, lon), tags.toMap())
        listOf(
            at(52.4402, 4.9712, "tourism" to "information", "information" to "visitor_centre", "name" to "Stonewood Visitor Centre", "opening_hours" to "Tu-Su 10:00-17:00", "wheelchair" to "yes"),
            at(52.4601, 5.0302, "tourism" to "camp_site", "name" to "Heron's Rest", "fee" to "yes"),
            at(52.4632, 4.9752, "tourism" to "wilderness_hut", "name" to "Bog Hut"),
            at(52.4404, 4.9716, "amenity" to "toilets"),
            at(52.4603, 5.0306, "amenity" to "toilets"),
            at(52.4400, 4.9708, "amenity" to "drinking_water"),
            at(52.4479, 5.0102, "leisure" to "picnic_table"),
            at(52.4582, 5.0122, "leisure" to "picnic_table"),
            at(52.4470, 5.0198, "leisure" to "picnic_table"),
            at(52.4477, 5.0150, "amenity" to "cafe", "name" to "The Boathouse", "opening_hours" to "Sa-Su 10:00-18:00"),
            at(52.4500, 5.0000, "type" to "route", "route" to "hiking", "name" to "Stonewood Loop", "roundtrip" to "yes", "distance" to "12.5", type = OsmType.RELATION),
            at(52.4560, 4.9800, "type" to "route", "route" to "hiking", "name" to "Heath Trail", "distance" to "7", type = OsmType.RELATION),
            at(52.4510, 5.0260, "type" to "route", "route" to "hiking", "name" to "Lakeshore Walk", type = OsmType.RELATION),
            at(52.4421, 4.9902, "natural" to "tree", "name" to "Stonewood Oak", "species" to "Quercus robur", "denotation" to "natural_monument", "start_date" to "1650", "height" to "24", "circumference" to "520"),
            at(52.4430, 4.9910, "natural" to "tree", "leaf_type" to "broadleaved"),
            at(52.4556, 5.0232, "leisure" to "bird_hide", "name" to "Reedbed Hide"),
            at(52.4645, 4.9905, "natural" to "wetland", "wetland" to "reedbed", "name" to "Stonewood Fen", type = OsmType.WAY),
        )
    }

    /** Makes the screens see Stonewood: the park and its places by filter, and its outline with the lake. */
    fun answerWithStonewood(services: Services) {
        services.local.finder.fake = { filter, _, _ -> (stonewoodPlaces + stonewood).filter { filter.matches(it.tags) } }
        val rings = listOf(stonewoodRing, stonewoodLake)
        val holes = listOf(false, true)
        services.local.parkShape = { park, _ -> if (park.key == stonewood.key) ParkShape(rings, emptyList(), emptyList(), outlined = true, holes = holes, areaM2 = ParkShape.areaOf(rings, holes)) else null }
    }

    private fun overpass(name: String) = app.atlas.providers.OverpassParser.parse(java.io.StringReader(resource(name))).places()

    /** The buildings within 300 m of the Westerkerk as OpenStreetMap had them on 16 October 2026 (an Overpass answer, tags trimmed), with their boxes. */
    val buildings: List<Place> by lazy { overpass("buildings_westerkerk.json") }

    /** The named shops, cafes, museums and churches within 260 m of the Westerkerk, the same day. */
    val shops: List<Place> by lazy { overpass("pois_westerkerk.json") }

    /** Makes the screens see the buildings and the shops round the Westerkerk. */
    fun answerWithWesterkerk(services: Services) {
        services.local.finder.fake = { filter, _, _ -> (buildings + shops).filter { filter.matches(it.tags) } }
    }

    /**
     * Panoramax's nearest picture to the Westerkerk as it answered on 16 October 2026 (the search answer, trimmed, and the standard-definition
     * picture of the 360 degree panorama, reduced to 1024 pixels wide): the City of Amsterdam's, 6 February 2023, CC-BY-SA.
     */
    val streetPhoto: StreetPhotoSource by lazy {
        val photo = StreetPhotos.parse(resource("panoramax_westerkerk.json"))!!
        val bytes = LocalFixtures::class.java.getResourceAsStream("/local/pano_westerkerk.jpg")!!.readBytes()
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        val image = StreetPhotoImage(photo, px, bmp.width, bmp.height)
        object : StreetPhotoSource { override suspend fun near(at: app.atlas.geo.LatLon) = image }
    }

    /** Nothing: no street photo anywhere. */
    val noStreetPhoto: StreetPhotoSource = object : StreetPhotoSource { override suspend fun near(at: app.atlas.geo.LatLon): StreetPhotoImage? = null }

    /** The 481 named peaks within 22 km of Innsbruck as OpenStreetMap had them on 16 October 2026 (an Overpass answer: name, height, position). */
    val innsbruckPeaks: List<Place> by lazy { overpass("peaks_innsbruck.json") }

    /** Makes the screens see the peaks round Innsbruck, and the point's height as 574 m (the town's, as the weather model gives it). */
    fun answerWithInnsbruck(services: Services) {
        services.local.finder.fake = { filter, _, _ -> innsbruckPeaks.filter { filter.matches(it.tags) } }
        services.local.elevationOverride = { 574.0 }
    }
}
