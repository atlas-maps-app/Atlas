package app.atlas.features

import app.atlas.geo.LatLon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Today's answers of the features, recorded from the tile stack (OpenMapTiles through `TileStack` on the fixture tiles
 * around the Westerkerk) before any caller moves to [app.atlas.services.LocalData]: the bar LocalData must reach or
 * pass. Kept in `app/src/test/resources/features/goldens.json`; [GoldenRecorderTest] writes it when it is missing and
 * otherwise proves today's code still gives it.
 */
data class Goldens(
    val source: String,
    /** The 20 fixed points: the address and the street names the tiles give there. */
    val points: List<GoldenPoint>,
    /** Cafés within 300 m of the Westerkerk. */
    val cafes: GoldenPlaces,
    /** Every named point of interest within 300 m of the Westerkerk. */
    val pois: GoldenPlaces,
    /** The Albert Heijn branches around the Westerkerk. */
    val brand: GoldenBrand,
    /** The chains around the Westerkerk, each with its branches. */
    val chains: GoldenChains,
    /** The quests around the Westerkerk (today from the place store: the tiles cannot say what is mapped). */
    val quests: GoldenQuests,
    /** Routes recorded through BRouter, with the street names asked at each step and the bridges crossed. */
    val routes: List<GoldenRoute>,
) {
    fun toJson(): String = PRETTY.encodeToString(JsonElement.serializer(), json())

    private fun json(): JsonObject = obj(
        "source" to JsonPrimitive(source),
        "points" to JsonArray(points.map { it.json() }),
        "cafes" to cafes.json(),
        "pois" to pois.json(),
        "brand" to brand.json(),
        "chains" to chains.json(),
        "quests" to quests.json(),
        "routes" to JsonArray(routes.map { it.json() }),
    )

    companion object {
        private val PRETTY = Json { prettyPrint = true }

        /** Where the goldens live in the repository (`atlas.repo`). */
        val file: File get() = File(System.getProperty("atlas.repo") ?: "../..", "app/android/app/src/test/resources/features/goldens.json")

        fun read(file: File = this.file): Goldens = parse(file.readText())

        fun parse(text: String): Goldens {
            val o = Json.parseToJsonElement(text).jsonObject
            return Goldens(
                o.str("source")!!,
                o.arr("points").map { GoldenPoint.of(it.jsonObject) },
                GoldenPlaces.of(o["cafes"]!!.jsonObject),
                GoldenPlaces.of(o["pois"]!!.jsonObject),
                GoldenBrand.of(o["brand"]!!.jsonObject),
                GoldenChains.of(o["chains"]!!.jsonObject),
                GoldenQuests.of(o["quests"]!!.jsonObject),
                o.arr("routes").map { GoldenRoute.of(it.jsonObject) },
            )
        }
    }
}

/** A fixed point: where, which way one faces, the street meant, and what the tiles answered there. */
data class GoldenPoint(
    val name: String,
    val at: LatLon,
    val bearing: Double,
    /** The street the point was put on. */
    val street: String,
    /** `TileWayNamer.name(at, bearing)`. */
    val way: String?,
    /** `TileWayNamer.nearest(at)`: the nearest named street or canal within 120 m, whichever way it runs. */
    val nearest: String?,
    /** `TileAddress.at(at)`. */
    val address: GoldenAddress?,
) {
    fun json() = obj(
        "name" to JsonPrimitive(name), "at" to at.json(), "bearing" to JsonPrimitive(bearing), "street" to JsonPrimitive(street),
        "way" to way.json(), "nearest" to nearest.json(), "address" to (address?.json() ?: JsonNull),
    )

    companion object {
        fun of(o: JsonObject) = GoldenPoint(
            o.str("name")!!, o.latLon("at")!!, o.num("bearing")!!, o.str("street")!!, o.str("way"), o.str("nearest"),
            (o["address"] as? JsonObject)?.let { GoldenAddress.of(it) },
        )
    }
}

/** The tiles' address of a point, and where the house number they chose stands. */
data class GoldenAddress(val street: String?, val housenumber: String?, val quarter: String?, val town: String?, val houseAt: LatLon?) {
    fun json() = obj(
        "street" to street.json(), "housenumber" to housenumber.json(), "quarter" to quarter.json(), "town" to town.json(),
        "house_at" to (houseAt?.json() ?: JsonNull),
    )

    companion object {
        fun of(o: JsonObject) = GoldenAddress(o.str("street"), o.str("housenumber"), o.str("quarter"), o.str("town"), o.latLon("house_at"))
    }
}

/** One place an answer listed: its OSM id (as "n123"), name, where and the tags the tiles gave it. */
data class GoldenPlace(val id: String?, val name: String?, val at: LatLon, val tags: Map<String, String>) {
    fun json() = obj("id" to id.json(), "name" to name.json(), "at" to at.json(), "tags" to JsonObject(tags.mapValues { JsonPrimitive(it.value) }))

    companion object {
        fun of(o: JsonObject) = GoldenPlace(
            o.str("id"), o.str("name"), o.latLon("at")!!,
            (o["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
        )
    }
}

data class GoldenPlaces(val centre: LatLon, val radius: Double, val filter: String, val places: List<GoldenPlace>) {
    fun json() = obj("centre" to centre.json(), "radius" to JsonPrimitive(radius), "filter" to JsonPrimitive(filter), "places" to JsonArray(places.map { it.json() }))

    companion object {
        fun of(o: JsonObject) = GoldenPlaces(o.latLon("centre")!!, o.num("radius")!!, o.str("filter")!!, o.arr("places").map { GoldenPlace.of(it.jsonObject) })
    }
}

data class GoldenBrand(val id: String, val centre: LatLon, val radius: Double, val branches: List<GoldenPlace>) {
    fun json() = obj("id" to JsonPrimitive(id), "centre" to centre.json(), "radius" to JsonPrimitive(radius), "branches" to JsonArray(branches.map { it.json() }))

    companion object {
        fun of(o: JsonObject) = GoldenBrand(o.str("id")!!, o.latLon("centre")!!, o.num("radius")!!, o.arr("branches").map { GoldenPlace.of(it.jsonObject) })
    }
}

/** `BrandsAround.split` of the tiles' places: each chain's brand id and its branches. */
data class GoldenChains(val centre: LatLon, val radius: Double, val chains: Map<String, List<GoldenPlace>>) {
    fun json() = obj(
        "centre" to centre.json(), "radius" to JsonPrimitive(radius),
        "chains" to JsonObject(chains.mapValues { (_, v) -> JsonArray(v.map { it.json() }) }),
    )

    companion object {
        fun of(o: JsonObject) = GoldenChains(
            o.latLon("centre")!!, o.num("radius")!!,
            o["chains"]!!.jsonObject.mapValues { (_, v) -> v.jsonArray.map { GoldenPlace.of(it.jsonObject) } },
        )
    }
}

/** One quest: the place's key ("n123"), its name, the question's key and how far the place is. */
data class GoldenQuest(val place: String, val name: String?, val key: String, val distance: Double) {
    fun json() = obj("place" to JsonPrimitive(place), "name" to name.json(), "key" to JsonPrimitive(key), "distance" to JsonPrimitive(distance))

    companion object {
        fun of(o: JsonObject) = GoldenQuest(o.str("place")!!, o.str("name"), o.str("key")!!, o.num("distance")!!)
    }
}

data class GoldenQuests(val centre: LatLon, val radius: Double, val now: String, val quests: List<GoldenQuest>) {
    fun json() = obj("centre" to centre.json(), "radius" to JsonPrimitive(radius), "now" to JsonPrimitive(now), "quests" to JsonArray(quests.map { it.json() }))

    companion object {
        fun of(o: JsonObject) = GoldenQuests(o.latLon("centre")!!, o.num("radius")!!, o.str("now")!!, o.arr("quests").map { GoldenQuest.of(it.jsonObject) })
    }
}

/** One street-name question the router asked, and the tiles' answer. */
data class GoldenStep(val at: LatLon, val bearing: Double, val street: String?) {
    fun json() = obj("at" to at.json(), "bearing" to JsonPrimitive(bearing), "street" to street.json())

    companion object {
        fun of(o: JsonObject) = GoldenStep(o.latLon("at")!!, o.num("bearing")!!, o.str("street"))
    }
}

/** A bridge the tiles draw across a route: metres along it, and where. */
data class GoldenBridge(val along: Double, val at: LatLon) {
    fun json() = obj("along" to JsonPrimitive(along), "at" to at.json())

    companion object {
        fun of(o: JsonObject) = GoldenBridge(o.num("along")!!, o.latLon("at")!!)
    }
}

/** A route as BRouter made it: its line, every street-name question its steps asked, and the bridges over it. */
data class GoldenRoute(val name: String, val mode: String, val points: List<LatLon>, val steps: List<GoldenStep>, val bridges: List<GoldenBridge>) {
    fun json() = obj(
        "name" to JsonPrimitive(name), "mode" to JsonPrimitive(mode),
        "points" to JsonArray(points.map { it.json() }),
        "steps" to JsonArray(steps.map { it.json() }),
        "bridges" to JsonArray(bridges.map { it.json() }),
    )

    companion object {
        fun of(o: JsonObject) = GoldenRoute(
            o.str("name")!!, o.str("mode")!!, o.arr("points").map { latLon(it.jsonArray) },
            o.arr("steps").map { GoldenStep.of(it.jsonObject) }, o.arr("bridges").map { GoldenBridge.of(it.jsonObject) },
        )
    }
}

private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))

private fun String?.json(): JsonElement = this?.let { JsonPrimitive(it) } ?: JsonNull

private fun LatLon.json() = JsonArray(listOf(JsonPrimitive(lat), JsonPrimitive(lon)))

private fun latLon(a: JsonArray) = LatLon(a[0].jsonPrimitive.double, a[1].jsonPrimitive.double)

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.double

private fun JsonObject.latLon(k: String): LatLon? = (this[k] as? JsonArray)?.let { latLon(it) }

private fun JsonObject.arr(k: String): JsonArray = this[k]?.jsonArray ?: JsonArray(emptyList())
