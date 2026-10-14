package app.atlas.ui.go

import app.atlas.geo.LatLon
import app.atlas.geo.PolylineCodec
import app.atlas.model.EvSpec
import app.atlas.model.Hazard
import app.atlas.model.Lane
import app.atlas.model.Landmark
import app.atlas.model.Maneuver
import app.atlas.model.ManeuverType
import app.atlas.model.Mode
import app.atlas.model.Route
import app.atlas.model.RoutePrefs
import app.atlas.model.Span
import app.atlas.model.Step
import app.atlas.model.Turn
import app.atlas.model.Vehicle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.LocalTime

/** A place the Go tab remembers: home, work, or one you went to. [arriveBy] is when you like to be there (home and work). */
data class SavedPlace(
    val name: String,
    val at: LatLon,
    val osmId: String? = null,
    val usedAt: Long = 0,
    val arriveBy: LocalTime? = null,
)

/**
 * The go package's small JSON forms, written by hand on kotlinx-serialization's tree (the app has no serialization
 * plugin): saved places, vehicles, route preferences, and whole routes (the demo fixtures). Readers are lenient: a
 * missing or broken field takes its default, and a broken document reads as null.
 */
object GoJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String?): JsonElement? = text?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }

    // ---- Saved places ----------------------------------------------------------------------------------------------

    fun place(p: SavedPlace): JsonObject = buildJsonObject {
        put("name", p.name)
        put("lat", p.at.lat)
        put("lon", p.at.lon)
        p.osmId?.let { put("id", it) }
        put("used", p.usedAt)
        p.arriveBy?.let { put("by", it.toString()) }
    }

    fun place(e: JsonElement?): SavedPlace? {
        val o = e as? JsonObject ?: return null
        val lat = o.double("lat") ?: return null
        val lon = o.double("lon") ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return SavedPlace(
            name = o.str("name") ?: return null,
            at = LatLon(lat, lon),
            osmId = o.str("id"),
            usedAt = (o["used"] as? JsonPrimitive)?.longOrNull ?: 0,
            arriveBy = o.str("by")?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
        )
    }

    fun places(list: List<SavedPlace>): String = JsonArray(list.map(::place)).toString()

    fun places(text: String?): List<SavedPlace> = (parse(text) as? JsonArray)?.mapNotNull(::place).orEmpty()

    // ---- Vehicles and preferences --------------------------------------------------------------------------------

    fun vehicle(v: Vehicle): JsonObject = buildJsonObject {
        v.heightM?.let { put("height", it) }
        v.widthM?.let { put("width", it) }
        v.lengthM?.let { put("length", it) }
        v.weightT?.let { put("weight", it) }
        v.axleLoadT?.let { put("axle", it) }
        if (v.trailer) put("trailer", true)
        if (v.hazmat) put("hazmat", true)
        v.airDraftM?.let { put("airDraft", it) }
        v.draftM?.let { put("draft", it) }
        v.beamM?.let { put("beam", it) }
        v.maxSpeedKmh?.let { put("maxSpeed", it) }
        v.ev?.let { ev ->
            put("ev", buildJsonObject {
                put("battery", ev.batteryKwh)
                put("consumption", ev.consumption)
                put("connectors", JsonArray(ev.connectors.sorted().map(::JsonPrimitive)))
                put("dc", ev.maxDcKw)
                put("ac", ev.maxAcKw)
                put("soc", ev.socNow)
                put("socMin", ev.socMin)
            })
        }
    }

    fun vehicle(e: JsonElement?): Vehicle? {
        val o = e as? JsonObject ?: return null
        val ev = (o["ev"] as? JsonObject)?.let { x ->
            val battery = x.positive("battery") ?: return@let null
            val consumption = x.positive("consumption") ?: return@let null
            EvSpec(
                batteryKwh = battery,
                consumption = consumption,
                connectors = (x["connectors"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()?.ifEmpty { null }
                    ?: setOf("type2_combo", "type2"),
                maxDcKw = x.positive("dc") ?: 100.0,
                maxAcKw = x.positive("ac") ?: 11.0,
                socNow = x.double("soc")?.coerceIn(0.0, 1.0) ?: 0.8,
                socMin = x.double("socMin")?.coerceIn(0.0, 1.0) ?: 0.1,
            )
        }
        return Vehicle(
            heightM = o.positive("height"), widthM = o.positive("width"), lengthM = o.positive("length"),
            weightT = o.positive("weight"), axleLoadT = o.positive("axle"),
            trailer = o.bool("trailer") ?: false, hazmat = o.bool("hazmat") ?: false,
            airDraftM = o.positive("airDraft"), draftM = o.positive("draft"), beamM = o.positive("beam"),
            maxSpeedKmh = o.positive("maxSpeed"), ev = ev,
        )
    }

    fun prefs(p: RoutePrefs): JsonObject = buildJsonObject {
        put("tolls", p.avoidTolls); put("motorways", p.avoidMotorways); put("ferries", p.avoidFerries)
        put("unpaved", p.avoidUnpaved); put("steps", p.avoidSteps)
        put("quiet", p.quiet); put("green", p.green); put("flat", p.flat); put("shade", p.shade); put("lit", p.lit); put("fast", p.fast)
    }

    fun prefs(e: JsonElement?): RoutePrefs? {
        val o = e as? JsonObject ?: return null
        fun w(k: String, d: Double) = o.double(k)?.coerceIn(0.0, 1.0) ?: d
        val d = RoutePrefs()
        return RoutePrefs(
            avoidTolls = o.bool("tolls") ?: false, avoidMotorways = o.bool("motorways") ?: false, avoidFerries = o.bool("ferries") ?: false,
            avoidUnpaved = o.bool("unpaved") ?: false, avoidSteps = o.bool("steps") ?: false,
            quiet = w("quiet", d.quiet), green = w("green", d.green), flat = w("flat", d.flat), shade = w("shade", d.shade),
            lit = w("lit", d.lit), fast = w("fast", d.fast),
        )
    }

    // ---- Routes ------------------------------------------------------------------------------------------------------

    /** A whole route: geometry as an encoded polyline (1e-6), heights to the decimetre, steps, spans and hazards. */
    fun route(r: Route): JsonObject = buildJsonObject {
        put("mode", r.mode.name)
        put("points", PolylineCodec.encode(r.points, 6))
        put("elevations", JsonArray(r.elevations.map { JsonPrimitive(if (it.isNaN()) null else Math.round(it * 10) / 10.0) }))
        put("distance", r.distance)
        put("duration", r.duration)
        put("climb", r.climb)
        put("descent", r.descent)
        put("source", r.source)
        put("steps", buildJsonArray {
            for (s in r.steps) add(buildJsonObject {
                put("type", s.maneuver.type.name)
                put("turn", s.maneuver.turn.name)
                s.maneuver.exit?.let { put("exit", it) }
                s.maneuver.bearingBefore?.let { put("before", it) }
                s.maneuver.bearingAfter?.let { put("after", it) }
                put("i", s.pointIndex)
                put("distance", s.distance)
                put("duration", s.duration)
                s.street?.let { put("street", it) }
                s.ref?.let { put("ref", it) }
                s.destinations?.let { put("destinations", it) }
                if (s.lanes.isNotEmpty()) put("lanes", JsonArray(s.lanes.map { l ->
                    buildJsonObject { put("turns", JsonArray(l.turns.map { JsonPrimitive(it.name) })); put("valid", l.valid) }
                }))
                if (s.landmarks.isNotEmpty()) put("landmarks", JsonArray(s.landmarks.map { l ->
                    buildJsonObject {
                        put("kind", l.kind); l.name?.let { put("name", it) }; put("lat", l.location.lat); put("lon", l.location.lon); put("side", l.side)
                        put("tags", JsonObject(l.tags.mapValues { JsonPrimitive(it.value) }))
                    }
                }))
            })
        })
        put("spans", JsonArray(r.spans.map { s -> buildJsonObject { put("from", s.from); put("to", s.to); put("key", s.key); put("value", s.value) } }))
        put("hazards", JsonArray(r.hazards.map { h ->
            buildJsonObject {
                put("kind", h.kind); put("i", h.pointIndex); put("lat", h.location.lat); put("lon", h.location.lon)
                h.value?.let { put("value", it) }; h.text?.let { put("text", it) }
                put("tags", JsonObject(h.tags.mapValues { JsonPrimitive(it.value) }))
            }
        }))
    }

    fun route(e: JsonElement?): Route? = runCatching { routeOrThrow(e as JsonObject) }.getOrNull()

    private fun routeOrThrow(o: JsonObject): Route {
        val points = PolylineCodec.decode(o.str("points")!!, 6)
        val elevations = (o["elevations"] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull ?: Double.NaN }?.toDoubleArray() ?: DoubleArray(0)
        val steps = (o["steps"] as JsonArray).map { se ->
            val s = se as JsonObject
            val i = s.int("i")!!.coerceIn(0, points.size - 1)
            Step(
                maneuver = Maneuver(
                    ManeuverType.valueOf(s.str("type")!!), Turn.valueOf(s.str("turn") ?: "STRAIGHT"), s.int("exit"), s.double("before"), s.double("after"),
                ),
                pointIndex = i, location = points[i], distance = s.double("distance") ?: 0.0, duration = s.double("duration") ?: 0.0,
                street = s.str("street"), ref = s.str("ref"), destinations = s.str("destinations"),
                lanes = (s["lanes"] as? JsonArray)?.map { le ->
                    val l = le as JsonObject
                    Lane((l["turns"] as JsonArray).map { Turn.valueOf((it as JsonPrimitive).content) }.toSet(), l.bool("valid") ?: false)
                }.orEmpty(),
                landmarks = (s["landmarks"] as? JsonArray)?.map { le ->
                    val l = le as JsonObject
                    Landmark(l.str("kind")!!, l.str("name"), LatLon(l.double("lat")!!, l.double("lon")!!), l.int("side") ?: 0, l.tags())
                }.orEmpty(),
            )
        }
        val spans = (o["spans"] as? JsonArray)?.map { se ->
            val s = se as JsonObject
            Span(s.int("from")!!, s.int("to")!!, s.str("key")!!, s.str("value")!!)
        }.orEmpty()
        val hazards = (o["hazards"] as? JsonArray)?.map { he ->
            val h = he as JsonObject
            Hazard(h.str("kind")!!, h.int("i")!!.coerceIn(0, points.size - 1), LatLon(h.double("lat")!!, h.double("lon")!!), h.double("value"), h.str("text"), h.tags())
        }.orEmpty()
        return Route(
            mode = Mode.valueOf(o.str("mode")!!), points = points, elevations = elevations,
            distance = o.double("distance")!!, duration = o.double("duration")!!, steps = steps, spans = spans, hazards = hazards,
            climb = o.double("climb") ?: 0.0, descent = o.double("descent") ?: 0.0, source = o.str("source") ?: "",
        )
    }

    // ---- Lenient readers ---------------------------------------------------------------------------------------------

    private fun JsonObject.prim(k: String): JsonPrimitive? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonObject.str(k: String): String? = prim(k)?.contentOrNull
    private fun JsonObject.double(k: String): Double? = prim(k)?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonObject.positive(k: String): Double? = double(k)?.takeIf { it > 0 }
    private fun JsonObject.int(k: String): Int? = prim(k)?.intOrNull
    private fun JsonObject.bool(k: String): Boolean? = prim(k)?.booleanOrNull
    private fun JsonObject.tags(): Map<String, String> =
        (this["tags"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
}
