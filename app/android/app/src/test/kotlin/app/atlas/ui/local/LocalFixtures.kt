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
