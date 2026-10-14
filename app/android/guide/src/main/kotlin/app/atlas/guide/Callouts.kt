package app.atlas.guide

import app.atlas.model.Hazard
import app.atlas.model.ManeuverType
import app.atlas.model.Mode
import app.atlas.model.Vehicle

/**
 * A warning about something ahead, [along] metres from the start of the route. It is spoken once when the traveller is
 * within [lead] of it, and, for a [second] lead, once more close by (only conflicts that matter get one).
 * [text] is rendered when spoken, from the distance left.
 */
class Callout(
    val id: String,
    val along: Double,
    val kind: String,
    val priority: Priority,
    val lead: Lead,
    val second: Lead? = null,
    val pan: Float = 0f,
    /** Only after dusk (unlit paths). */
    val whenDark: Boolean = false,
    val text: (distance: Double) -> String,
) {
    override fun toString() = "Callout($id @ ${along.toInt()} m)"
}

/**
 * Builds the warnings for a route and a traveller from [app.atlas.model.Route.hazards] and spans.
 *
 * Hazard kinds understood (case-insensitive; they match the `:route` module's `HazardKind`, and the app may add any):
 * - slopes: climb, descent (value: grade %, tag `length` m);
 * - surfaces and bike things: cobbles, unpaved (tag `length`), steps (tags `step_count`, `incline`), ferry, tram_tracks,
 *   dismount, cycleway_end, cycleway_start, ford;
 * - roads: speed_camera (tag `maxspeed`), speed_change / maxspeed (tag `to` or `maxspeed`: the new limit as signed,
 *   else value in km/h), toll, vignette,
 *   low_emission_zone, tunnel, level_crossing, fuel and charging (tag `name`, for "last fuel for 140 km");
 * - limits: maxheight, maxweight, maxwidth, maxlength, maxaxleload (value: the limit; tag `avoided=yes` when the route
 *   goes round it), height_restrictor, hazmat, hgv, restrictions_unchecked;
 * - road reports: jam (value: delay s), road_works, accident, stopped_vehicle, pothole, ice, fog, hazard, road_closed;
 * - water: bridge (value or tag `clearance`: clearance m; tags `bridge:movable`, `opening_hours`), lock (tag
 *   `vhf_channel`), weir, buoy / beacon / mark (seamark tags; the side from tag `keep` or the geometry), shallow and
 *   maxdraught (value: depth m), waterway_speed (value: limit), open_water;
 * - walking: crossing (tags `crossing`, `signals`), unlit (only after dusk);
 * - electric vehicles: battery (tag `arrive_soc` 0..1 for the charge on arrival, else where it runs low);
 * - speed_bump (chatty only). The router's give_way and off_network are known and deliberately left unsaid.
 *
 * Spans add: surface (cobbles, unpaved), maxspeed changes, highway=cycleway ends, steps, toll, tunnel, lit=no.
 */
object Callouts {

    fun build(index: RouteIndex, settings: GuideSettings, book: Phrasebook, profile: ModeProfile): List<Callout> {
        val b = Builder(index, settings, book, profile)
        b.hazards()
        b.spans()
        b.grades()
        b.lastStops()
        return distinct(b.out.sortedBy { it.along })
    }

    /** Two reports of one thing (a crossing mapped on both rails, a hazard on the way and its node) are one warning. */
    private const val SAME_THING_M = 30.0

    /**
     * Drops a warning that repeats the one just before it (same kind and words within [SAME_THING_M]), and gives a later
     * pass by the same place its own id ("lock@…#2"): a round trip through a lock warns on the way out and on the way back.
     */
    private fun distinct(sorted: List<Callout>): List<Callout> {
        val out = ArrayList<Callout>(sorted.size)
        val texts = HashMap<Callout, String>()
        fun words(c: Callout) = texts.getOrPut(c) { c.text(0.0) }
        val passes = HashMap<String, Int>()
        for (c in sorted) {
            var dup = false
            for (k in out.indices.reversed()) {
                val o = out[k]
                if (c.along - o.along > SAME_THING_M) break
                if (o.kind == c.kind && o.priority >= c.priority && words(o) == words(c)) { dup = true; break }
            }
            if (dup) continue
            val n = (passes[c.id] ?: 0) + 1
            passes[c.id] = n
            out += if (n == 1) c else Callout("${c.id}#$n", c.along, c.kind, c.priority, c.lead, c.second, c.pan, c.whenDark, c.text)
        }
        return out
    }

    private class Builder(val index: RouteIndex, val settings: GuideSettings, val book: Phrasebook, val profile: ModeProfile) {
        val out = ArrayList<Callout>()
        val group = settings.mode.group
        val v: Vehicle = settings.vehicle
        val kinds = HashSet<String>()
        val scale = profile.calloutScale
        val land = group != ModeGroup.BOAT
        val motor = group == ModeGroup.CAR || group == ModeGroup.TRUCK
        val dimensions = group == ModeGroup.TRUCK || settings.mode == Mode.CAMPER ||
            (motor && (v.heightM != null || v.weightT != null || v.widthM != null || v.lengthM != null))

        fun lead(seconds: Double, minM: Double, maxM: Double) = Lead(seconds * scale, minM * scale, maxM * scale)

        fun add(h: Hazard?, along: Double, kind: String, lead: Lead, priority: Priority = Priority.NORMAL, second: Lead? = null,
                pan: Float = 0f, whenDark: Boolean = false, text: (Double) -> String) {
            // Named by place, not by distance along, so a new route past the same hazard knows it was told.
            val at = h?.location ?: index.line.pointAt(along)
            val id = "$kind@${Math.round(at.lat * 1e4)},${Math.round(at.lon * 1e4)}"
            out += Callout(id, along, kind, priority, lead, second, pan, whenDark, text)
            kinds += kind
        }

        fun hazards() {
            for (h in index.route.hazards) hazard(h, index.alongOf(h.pointIndex), h.kind.lowercase().replace('-', '_').replace(' ', '_'))
        }

        private fun lengthOf(h: Hazard): Double? = (h.tags["length"] ?: h.tags["length_m"] ?: h.tags["distance"])?.toDoubleOrNull()

        private fun avoided(h: Hazard, kind: String) =
            kind.startsWith("avoided") || h.tags["avoided"]?.lowercase() in setOf("yes", "true", "1") || h.tags["atlas:avoided"] == "yes"

        private fun hazard(h: Hazard, along: Double, kind: String) {
            when (kind.removePrefix("avoided_")) {
                "climb", "ascent", "steep_climb", "uphill" -> if (group == ModeGroup.BIKE || group == ModeGroup.WALK && settings.mode == Mode.WHEELCHAIR) {
                    val grade = h.value ?: return
                    climbOrDescent(h, along, "climb", grade, lengthOf(h))
                }
                "descent", "steep_descent", "downhill" -> {
                    val grade = h.value ?: return
                    if (group == ModeGroup.BIKE) climbOrDescent(h, along, "descent", grade, lengthOf(h))
                    if (group == ModeGroup.TRUCK && kotlin.math.abs(grade) >= 5) engineBrake(h, along, kotlin.math.abs(grade), lengthOf(h))
                }
                "cobbles", "cobblestone", "sett" -> if (group == ModeGroup.BIKE) add(h, along, "cobbles", lead(12.0, 40.0, 120.0)) { book.phrase("cobbles") }
                "unpaved", "gravel", "dirt" -> if (group == ModeGroup.BIKE || settings.mode == Mode.WHEELCHAIR) {
                    val len = lengthOf(h)
                    add(h, along, "unpaved", lead(12.0, 40.0, 120.0)) {
                        if (len != null) book.phrase("unpaved", "length" to Instr(book.distance(len, DistanceForm.FOR))) else book.phrase("unpaved_short")
                    }
                }
                "steps", "stairs" -> steps(h, along, h.tags)
                "ferry" -> if (land && !hasFerryStepNear(along)) add(h, along, "ferry", lead(40.0, 150.0, 1500.0)) { d -> book.phrase("ferry", "distance" to Instr(book.distance(d, DistanceForm.IN))) }
                "tram_tracks", "tram", "railway_tram" -> if (group == ModeGroup.BIKE) add(h, along, "tram_tracks", lead(8.0, 25.0, 60.0)) { book.phrase("tram_tracks") }
                "dismount", "bicycle_dismount" -> if (group == ModeGroup.BIKE) add(h, along, "dismount", lead(10.0, 30.0, 80.0)) { book.phrase("dismount") }
                "cycleway_end" -> if (group == ModeGroup.BIKE) add(h, along, "cycleway_end", lead(8.0, 25.0, 80.0)) { book.phrase("cycleway_ends") }
                "cycleway_start" -> if (group == ModeGroup.BIKE && settings.verbosity == Verbosity.CHATTY) add(h, along, "cycleway_start", lead(8.0, 25.0, 80.0)) { book.phrase("cycleway_starts") }
                "speed_camera", "camera", "enforcement" -> if (motor && settings.speedCameras) {
                    val limit = roadLimit(h)
                    add(h, along, "speed_camera", lead(15.0, 200.0, 800.0), Priority.HIGH) {
                        if (limit != null) book.phrase("speed_camera_limit", "speed" to Instr(limit.toLong().toString())) else book.phrase("speed_camera")
                    }
                }
                "maxspeed", "speed_change", "speed_limit" -> if (motor) {
                    val limit = roadLimit(h) ?: return
                    add(h, along, "maxspeed", lead(4.0, 30.0, 150.0), Priority.LOW) { book.phrase("speed_limit", "speed" to Instr(limit.toLong().toString())) }
                }
                "toll", "toll_booth" -> if (motor) add(h, along, "toll", lead(60.0, 800.0, 2500.0)) { book.phrase("toll") }
                "vignette" -> if (motor) add(h, along, "vignette", lead(60.0, 800.0, 2500.0)) { book.phrase("vignette") }
                "low_emission_zone", "clean_air_zone", "environmental_zone", "lez", "zone" ->
                    if (motor) add(h, along, "low_emission_zone", lead(60.0, 800.0, 2500.0)) { book.phrase("low_emission_zone") }
                "tunnel" -> {
                    if (group == ModeGroup.TRUCK && v.hazmat && (h.tags["hazmat"] == "no" || h.tags["tunnel:category"] != null || h.tags["hazmat:category"] != null)) {
                        hazmat(h, along, kind)
                    } else if (motor) add(h, along, "tunnel", lead(12.0, 150.0, 600.0)) { book.phrase("tunnel") }
                }
                "hazmat" -> if (group == ModeGroup.TRUCK && v.hazmat) hazmat(h, along, kind)
                "speed_bump", "traffic_calming", "bump", "hump" -> if (motor && settings.verbosity == Verbosity.CHATTY) {
                    add(h, along, "speed_bump", lead(5.0, 40.0, 150.0), Priority.LOW) { book.phrase("speed_bump") }
                }
                "battery", "battery_low" -> if (land && group != ModeGroup.WALK) battery(h, along)
                "level_crossing", "railway_crossing" -> if (motor || group == ModeGroup.BIKE) add(h, along, "level_crossing", lead(10.0, 60.0, 400.0)) { book.phrase("level_crossing") }
                "maxheight", "height", "height_restrictor", "low_bridge" -> if (dimensions) {
                    val l = h.value ?: h.tags["maxheight"]?.toDoubleOrNull()
                    if (l != null) limit(h, along, kind, "height", l, v.heightM)
                    // A barrier whose height is not mapped: still worth a warning for anything tall.
                    else if (!avoided(h, kind)) add(h, along, "height_barrier", lead(30.0, 300.0, 1000.0), Priority.HIGH) { book.phrase("height_barrier") }
                }
                "maxweight", "weight" -> if (dimensions) limit(h, along, kind, "weight", h.value ?: h.tags["maxweight"]?.toDoubleOrNull(), v.weightT)
                "maxwidth", "width" -> if (dimensions) limit(h, along, kind, "width", h.value ?: h.tags["maxwidth"]?.toDoubleOrNull(), v.widthM)
                "maxlength", "length" -> if (dimensions) limit(h, along, kind, "length", h.value ?: h.tags["maxlength"]?.toDoubleOrNull(), v.lengthM)
                "maxaxleload", "axleload" -> if (dimensions) limit(h, along, kind, "axleload", h.value ?: h.tags["maxaxleload"]?.toDoubleOrNull(), v.axleLoadT)
                "hgv", "no_trucks" -> if (group == ModeGroup.TRUCK && !avoided(h, kind) && h.tags["hgv"] in setOf(null, "no", "destination", "delivery")) {
                    add(h, along, "hgv", lead(80.0, 1500.0, 4000.0), Priority.CRITICAL, second = lead(20.0, 300.0, 800.0)) { book.phrase("hgv_conflict") }
                }
                "restrictions_unchecked" -> if (dimensions || group == ModeGroup.BOAT) add(h, along, "unchecked", Lead(0.0, 1e9, 1e9), Priority.LOW) { book.phrase("restrictions_unchecked") }
                "ford" -> if (land && group != ModeGroup.WALK) add(h, along, "ford", lead(15.0, 40.0, 300.0), Priority.HIGH) { book.phrase("ford") }
                "weir" -> if (group == ModeGroup.BOAT) add(h, along, "weir", lead(120.0, 150.0, 900.0), Priority.HIGH) { d -> book.phrase("weir", "distance" to Instr(book.distance(d, DistanceForm.IN))) }
                "bridge" -> when {
                    group == ModeGroup.BOAT -> boatBridge(h, along)
                    dimensions && h.value != null -> limit(h, along, kind, "height", h.value, v.heightM)
                }
                "lock" -> if (group == ModeGroup.BOAT) lock(h, along)
                "buoy", "beacon", "mark", "seamark", "buoy_lateral", "beacon_lateral" -> if (group == ModeGroup.BOAT) mark(h, along)
                "shallow", "depth", "maxdraught" -> if (group == ModeGroup.BOAT) shallow(h, along)
                "waterway_speed", "waterway_maxspeed" -> if (group == ModeGroup.BOAT) {
                    val limit = h.value ?: return
                    add(h, along, "waterway_speed", lead(60.0, 100.0, 600.0)) { book.phrase("speed_limit", "speed" to Instr(book.speed(limit, h.tags["unit"] ?: "kmh"))) }
                }
                "jam", "traffic_jam", "road_works", "construction", "accident", "crash", "stopped_vehicle", "broken_down_vehicle",
                "pothole", "ice", "slippery", "fog", "hazard", "road_closed", "closed" -> if (land && group != ModeGroup.WALK) report(h, along, kind)
                "open_water" -> if (group == ModeGroup.BOAT) add(h, along, "open_water", lead(120.0, 200.0, 1000.0)) { book.phrase("open_water") }
                "crossing", "traffic_signals" -> if (group == ModeGroup.WALK) crossing(h, along, kind)
                "unlit" -> if (group == ModeGroup.WALK || group == ModeGroup.BIKE) {
                    val len = lengthOf(h)
                    add(h, along, "unlit", lead(20.0, 20.0, 80.0), whenDark = true) { unlitText(len) }
                }
            }
        }

        /**
         * The battery of an electric vehicle: the charge left on arrival when it is low, or where it runs low when no
         * charger is in reach. Both are told at the start, while there is still time to plan; running low again near it.
         */
        private fun battery(h: Hazard, along: Double) {
            val atStart = Lead(0.0, along + 1000.0, along + 1000.0)
            val soc = h.tags["arrive_soc"]?.toDoubleOrNull()
            if (soc != null) {
                val pct = Math.round(soc * 100).toInt().coerceAtLeast(0)
                add(h, along, "battery", atStart, Priority.HIGH) { book.phrase("battery_low_arrival", "percent" to Instr(book.percent(pct))) }
            } else {
                add(h, along, "battery", atStart, Priority.HIGH, second = lead(60.0, 2000.0, 5000.0)) { d ->
                    book.phrase("battery_runs_low", "distance" to Instr(book.distance(d, DistanceForm.IN)))
                }
            }
        }

        /** The new limit as the road's own sign says it: "30 mph" is 30, though the router gives the value in km/h. */
        private fun roadLimit(h: Hazard): Double? =
            (h.tags["to"] ?: h.tags["maxspeed"])?.let { parseSpeed(it) } ?: h.value

        /** A road report from other drivers or a traffic feed (board "Road ahead"): jams, works, crashes, stopped cars. */
        private fun report(h: Hazard, along: Double, kind: String) {
            val key = when (kind) {
                "jam", "traffic_jam" -> "jam"
                "road_works", "construction" -> "road_works"
                "accident", "crash" -> "accident"
                "stopped_vehicle", "broken_down_vehicle" -> "stopped_vehicle"
                "ice", "slippery" -> "ice"
                "road_closed", "closed" -> "closed"
                else -> kind
            }
            val urgent = key == "accident" || key == "stopped_vehicle" || key == "closed" || key == "ice"
            val far = if (motor) lead(60.0, 500.0, 2000.0) else lead(30.0, 100.0, 400.0)
            val near = if (!urgent) null else if (motor) lead(10.0, 150.0, 400.0) else lead(8.0, 30.0, 80.0)
            val delay = if (key == "jam") h.value?.takeIf { it >= 60 } else null
            add(h, along, "report_$key", far, if (urgent) Priority.HIGH else Priority.NORMAL, second = near) { d ->
                val dist = Instr(book.distance(d, DistanceForm.IN))
                when {
                    delay != null -> book.phrase("report.jam_delay", "distance" to dist, "duration" to Instr(book.duration(delay)))
                    else -> book.phrase("report.$key", "distance" to dist)
                }
            }
        }

        private fun unlitText(len: Double?) =
            if (len != null) book.phrase("unlit", "length" to Instr(book.distance(len, DistanceForm.FOR))) else book.phrase("unlit_short")

        private fun hasFerryStepNear(along: Double): Boolean =
            index.steps.indices.any { index.steps[it].maneuver.type == ManeuverType.FERRY_BOARD && kotlin.math.abs(index.stepAlong[it] - along) < 200 }

        private fun climbOrDescent(h: Hazard?, along: Double, kind: String, grade: Double, length: Double?) {
            val g = kotlin.math.abs(grade).let { Math.round(it).toInt() }
            if (g < 3) return
            add(h, along, kind, if (kind == "climb") lead(30.0, 100.0, 300.0) else lead(25.0, 80.0, 250.0)) {
                if (length != null) book.phrase(kind, "length" to Instr(book.distance(length)), "grade" to g.toString())
                else book.phrase("${kind}_grade", "grade" to g.toString())
            }
        }

        private fun engineBrake(h: Hazard?, along: Double, grade: Double, length: Double?) {
            val len = length ?: return
            if (len < 500) return
            val g = Math.round(grade).toInt()
            add(h, along, "engine_brake", lead(40.0, 500.0, 1500.0), Priority.HIGH) {
                book.phrase("engine_brake", "length" to Instr(book.distance(len)), "grade" to g.toString())
            }
        }

        private fun steps(h: Hazard?, along: Double, tags: Map<String, String>) {
            when (group) {
                ModeGroup.BIKE -> add(h, along, "steps", lead(12.0, 40.0, 100.0), Priority.HIGH) { book.phrase("steps_bike") }
                ModeGroup.WALK -> {
                    val count = tags["step_count"]?.toIntOrNull()
                    val dir = when (tags["incline"]) { "up" -> "up"; "down" -> "down"; else -> null }
                    add(h, along, "steps", lead(15.0, 15.0, 40.0)) {
                        if (count != null && dir != null) book.phrase("steps_count", "count" to count.toString(), "updown" to Instr(book.phrase(dir)))
                        else book.phrase("steps")
                    }
                }
                else -> {}
            }
        }

        private fun hazmat(h: Hazard, along: Double, kind: String) {
            if (avoided(h, kind)) add(h, along, "hazmat", lead(80.0, 1500.0, 4000.0)) { book.phrase("hazmat_avoided") }
            else add(h, along, "hazmat", lead(80.0, 1500.0, 4000.0), Priority.CRITICAL, second = lead(20.0, 300.0, 800.0)) { book.phrase("hazmat_warning") }
        }

        private fun vehicleWord() = book.phrase(
            when {
                group == ModeGroup.BOAT -> "vehicle.boat"
                settings.mode == Mode.TRUCK -> "vehicle.truck"
                settings.mode == Mode.CAMPER -> "vehicle.camper"
                else -> "vehicle.car"
            },
        )

        private fun limitValue(dim: String, x: Double) = if (dim == "weight" || dim == "axleload") book.tonnes(x) else book.height(x)

        /** A height, weight, width or length limit against the vehicle: avoided, a conflict, tight, or merely there. */
        private fun limit(h: Hazard, along: Double, kind: String, dim: String, limit: Double?, ownSetting: Double?) {
            val l = limit ?: return
            // The router says what it compared with (tags["vehicle"]) when the guide was not told the vehicle's size.
            val own = ownSetting ?: h.tags["vehicle"]?.toDoubleOrNull()
            val noun = Instr(book.phrase("limit.$dim"))
            val value = limitValue(dim, l)
            when {
                avoided(h, kind) -> if (settings.verbosity != Verbosity.MINIMAL) {
                    add(h, along, "limit_$dim", lead(80.0, 1500.0, 4000.0)) { book.phrase("limit_avoided", "limit" to noun, "value" to Instr(value)) }
                }
                own != null && own > l -> add(h, along, "limit_$dim", lead(80.0, 1500.0, 4000.0), Priority.CRITICAL, second = lead(20.0, 300.0, 800.0)) {
                    book.phrase("conflict.$dim", "value" to Instr(value), "vehicle" to Instr(vehicleWord()), "own" to Instr(limitValue(dim, own)))
                }
                own != null && (l - own < tightMargin(dim) || settings.verbosity == Verbosity.CHATTY) ->
                    add(h, along, "limit_$dim", lead(60.0, 800.0, 3000.0)) { book.phrase("limit_clear", "limit" to noun, "value" to Instr(value)) }
                own == null && settings.verbosity == Verbosity.CHATTY ->
                    add(h, along, "limit_$dim", lead(60.0, 800.0, 3000.0)) { book.phrase("limit_info", "limit" to noun, "value" to Instr(value)) }
            }
        }

        private fun tightMargin(dim: String) = when (dim) { "weight" -> 2.0; "axleload" -> 1.0; "length" -> 1.0; else -> 0.3 }

        private fun boatBridge(h: Hazard, along: Double) {
            val clearance = h.value ?: (h.tags["clearance"] ?: h.tags["seamark:bridge:clearance_height"] ?: h.tags["maxheight"])?.toDoubleOrNull()
            val movable = h.tags["bridge:movable"]?.let { it != "no" } == true || h.tags["seamark:bridge:category"]?.contains("opening") == true ||
                h.tags["seamark:bridge:category"] in setOf("bascule", "swing", "lifting", "draw")
            val air = v.airDraftM
            val bridgeLead = lead(300.0, 400.0, 1852.0)
            if (avoided(h, "bridge")) {
                val cl = clearance ?: return
                if (settings.verbosity != Verbosity.MINIMAL) add(h, along, "bridge", bridgeLead) { book.phrase("bridge_avoided", "clearance" to Instr(book.height(cl))) }
                return
            }
            if (movable && (clearance == null || air == null || air > clearance)) {
                val hours = h.tags["opening_hours"]
                add(h, along, "bridge", bridgeLead) { d ->
                    val dist = Instr(book.distance(d, DistanceForm.IN))
                    if (hours != null) book.phrase("bridge_movable_hours", "distance" to dist, "hours" to hours) else book.phrase("bridge_movable", "distance" to dist)
                }
                return
            }
            if (clearance == null) return
            val cl = Instr(book.height(clearance))
            when {
                air == null -> add(h, along, "bridge", bridgeLead) { d -> book.phrase("bridge_info", "distance" to Instr(book.distance(d, DistanceForm.IN)), "clearance" to cl) }
                air > clearance -> add(h, along, "bridge", bridgeLead, Priority.CRITICAL, second = lead(90.0, 150.0, 500.0)) { d ->
                    book.phrase("bridge_conflict", "distance" to Instr(book.distance(d, DistanceForm.IN)), "clearance" to cl, "own" to Instr(book.height(air)))
                }
                clearance - air < 0.5 -> add(h, along, "bridge", bridgeLead, Priority.HIGH) { d ->
                    book.phrase("bridge_tight", "distance" to Instr(book.distance(d, DistanceForm.IN)), "clearance" to cl, "margin" to Instr(book.margin(clearance - air)))
                }
                else -> add(h, along, "bridge", bridgeLead) { d -> book.phrase("bridge_clear", "distance" to Instr(book.distance(d, DistanceForm.IN)), "clearance" to cl) }
            }
        }

        private fun lock(h: Hazard, along: Double) {
            val vhf = h.tags["vhf_channel"] ?: h.tags["vhf"] ?: h.tags["seamark:communication:vhf_channel"] ?: h.tags["communication:vhf"]
            add(h, along, "lock", lead(300.0, 500.0, 1852.0)) { d ->
                val dist = Instr(book.distance(d, DistanceForm.IN))
                if (vhf != null) book.phrase("lock_vhf", "distance" to dist, "vhf" to vhf) else book.phrase("lock", "distance" to dist)
            }
        }

        private fun mark(h: Hazard, along: Double) {
            // The side to keep it on is where it lies: a mark right of the track is kept to starboard.
            val snap = index.line.snap(h.location, (h.pointIndex - 3).coerceAtLeast(0), h.pointIndex + 3)
            val explicit = h.tags["keep"]?.lowercase()
            val starboard = when (explicit) { "starboard" -> true; "port" -> false; else -> (snap?.side ?: 1) > 0 }
            val name = book.markName(h.tags, h.text)
            add(h, along, "mark", lead(60.0, 80.0, 400.0), pan = if (starboard) 0.6f else -0.6f) {
                book.phrase("keep_mark", "mark" to Instr(name, startsWithName = true), "side" to Instr(book.phrase(if (starboard) "starboard" else "port")))
            }
        }

        private fun shallow(h: Hazard, along: Double) {
            val depth = h.value ?: return
            val draft = v.draftM
            val d = Instr(book.height(depth))
            if (draft != null && draft + 0.3 > depth) {
                add(h, along, "shallow", lead(120.0, 150.0, 900.0), Priority.CRITICAL, second = lead(45.0, 80.0, 300.0)) {
                    book.phrase("shallow_conflict", "depth" to d, "own" to Instr(book.height(draft)))
                }
            } else if (draft == null || settings.verbosity == Verbosity.CHATTY) {
                add(h, along, "shallow", lead(120.0, 150.0, 900.0)) { book.phrase("shallow", "depth" to d) }
            }
        }

        private fun crossing(h: Hazard, along: Double, kind: String) {
            val c = h.tags["crossing"]
            val key = when {
                kind == "traffic_signals" || c == "traffic_signals" || h.tags["crossing:signals"] == "yes" || h.tags["signals"] == "yes" -> "crossing_signals"
                c == "zebra" || h.tags["crossing_ref"] == "zebra" || c == "marked" && h.tags["crossing:markings"] == "zebra" -> "crossing_zebra"
                else -> "crossing_plain"
            }
            add(h, along, "crossing", lead(15.0, 15.0, 40.0)) { book.phrase(key) }
        }

        /** Surface, speed-limit, cycle-path, steps, toll, tunnel and lighting changes from the spans. */
        fun spans() {
            if (group == ModeGroup.BIKE && "cobbles" !in kinds && "unpaved" !in kinds) {
                runs("surface") { classifySurface(it) }.forEach { (kind, from, to) ->
                    val along = index.alongOf(from)
                    val len = index.alongOf(to) - along
                    if (len < 50) return@forEach
                    if (kind == "cobbles") add(null, along, "cobbles", lead(12.0, 40.0, 120.0)) { book.phrase("cobbles") }
                    else add(null, along, "unpaved", lead(12.0, 40.0, 120.0)) { book.phrase("unpaved", "length" to Instr(book.distance(len, DistanceForm.FOR))) }
                }
            }
            if (motor && "maxspeed" !in kinds) {
                var prev: Double? = null
                var first = true
                for (s in index.spans("maxspeed").sortedBy { it.from }) {
                    val limit = parseSpeed(s.value) ?: continue
                    if (!first && limit != prev) add(null, index.alongOf(s.from), "maxspeed", lead(4.0, 30.0, 150.0), Priority.LOW) {
                        book.phrase("speed_limit", "speed" to Instr(limit.toLong().toString()))
                    }
                    prev = limit
                    first = false
                }
            }
            if (group == ModeGroup.BIKE && "cycleway_end" !in kinds) cyclewayChanges()
            if ((group == ModeGroup.BIKE || group == ModeGroup.WALK) && "steps" !in kinds) {
                for (s in index.spans("highway")) if (s.value == "steps") steps(null, index.alongOf(s.from), emptyMap())
            }
            if (motor && "toll" !in kinds) starts("toll") { it == "yes" }.forEach { a -> add(null, a, "toll", lead(60.0, 800.0, 2500.0)) { book.phrase("toll") } }
            if (motor && "tunnel" !in kinds) starts("tunnel") { it == "yes" }.forEach { a ->
                add(null, a, "tunnel", lead(12.0, 150.0, 600.0)) { book.phrase("tunnel") }
            }
            if (group == ModeGroup.WALK && "unlit" !in kinds) {
                for (s in index.spans("lit")) if (s.value == "no") {
                    val a = index.alongOf(s.from)
                    val len = index.alongOf(s.to) - a
                    if (len >= 100) add(null, a, "unlit", lead(20.0, 20.0, 80.0), whenDark = true) { unlitText(len) }
                }
            }
            if (group == ModeGroup.BOAT && "waterway_speed" !in kinds) {
                var prev: Double? = null
                for (s in index.spans("maxspeed").sortedBy { it.from }) {
                    val limit = parseSpeed(s.value) ?: continue
                    if (limit != prev) add(null, index.alongOf(s.from), "waterway_speed", lead(60.0, 100.0, 600.0)) {
                        book.phrase("speed_limit", "speed" to Instr(book.speed(limit, if (s.value.contains("knot")) "kn" else if (s.value.contains("mph")) "mph" else "kmh")))
                    }
                    prev = limit
                }
            }
        }

        private fun cyclewayChanges() {
            val hw = index.spans("highway").sortedBy { it.from }
            for (i in 1 until hw.size) {
                val before = isCycleway(hw[i - 1].value, index.fact("bicycle", hw[i - 1].from))
                val after = isCycleway(hw[i].value, index.fact("bicycle", hw[i].from))
                val road = hw[i].value in ROADS
                if (before && !after && road && hw[i].from == hw[i - 1].to) {
                    add(null, index.alongOf(hw[i].from), "cycleway_end", lead(8.0, 25.0, 80.0)) { book.phrase("cycleway_ends") }
                }
            }
        }

        private fun isCycleway(highway: String, bicycle: String?) = highway == "cycleway" || (highway == "path" && bicycle == "designated")

        private fun starts(key: String, pred: (String) -> Boolean): List<Double> {
            val res = ArrayList<Double>()
            var lastTo = -1
            for (s in index.spans(key).sortedBy { it.from }) {
                if (pred(s.value)) {
                    if (s.from != lastTo) res += index.alongOf(s.from)
                    lastTo = s.to
                }
            }
            return res
        }

        /** Merged runs of one span key's classified value: (class, fromPoint, toPoint). */
        private fun runs(key: String, classify: (String) -> String?): List<Triple<String, Int, Int>> {
            val res = ArrayList<Triple<String, Int, Int>>()
            var cur: Triple<String, Int, Int>? = null
            for (s in index.spans(key).sortedBy { it.from }) {
                val c = classify(s.value)
                cur = if (c != null && cur != null && cur.first == c && s.from <= cur.third) Triple(c, cur.second, s.to)
                else {
                    cur?.let { res += it }
                    if (c != null) Triple(c, s.from, s.to) else null
                }
            }
            cur?.let { res += it }
            return res
        }

        /** Climbs and descents from the route's elevations, when the router gave none as hazards. */
        fun grades() {
            val elev = index.route.elevations
            if (elev.size != index.line.points.size || elev.size < 2) return
            val cum = index.line.cumulative
            if (group == ModeGroup.BIKE && "climb" !in kinds && "descent" !in kinds) {
                for (g in Grades.find(cum, elev, minPercent = 4.0, minLength = 200.0)) {
                    if (g.rise > 0) climbOrDescent(null, g.fromAlong, "climb", g.percent, g.length)
                    else if (g.percent <= -5) climbOrDescent(null, g.fromAlong, "descent", g.percent, g.length)
                }
            }
            if (group == ModeGroup.TRUCK && "engine_brake" !in kinds) {
                for (g in Grades.find(cum, elev, minPercent = 5.0, minLength = 1000.0)) if (g.rise < 0) engineBrake(null, g.fromAlong, -g.percent, g.length)
            }
        }

        /** "Last fuel for 140 km": a fuel or charging stop followed by a long stretch without one. */
        fun lastStops() {
            if (!motor) return
            val ev = v.ev != null
            val kindsWanted = if (ev) setOf("charging", "charger", "charging_station") else setOf("fuel", "fuel_station")
            val stops = index.route.hazards.filter { it.kind.lowercase() in kindsWanted }.map { index.alongOf(it.pointIndex) to it }.sortedBy { it.first }
            if (stops.isEmpty()) return
            val gapMin = if (group == ModeGroup.TRUCK) 150_000.0 else 100_000.0
            for ((i, pair) in stops.withIndex()) {
                val (a, h) = pair
                val nextAlong = stops.getOrNull(i + 1)?.first ?: index.length
                val gap = nextAlong - a
                if (gap < gapMin) continue
                val name = h.text ?: h.tags["name"] ?: h.tags["brand"] ?: book.phrase(if (ev) "charging_station" else "fuel_station")
                add(h, a, if (ev) "last_charger" else "last_fuel", lead(60.0, 1000.0, 3000.0), Priority.HIGH) { d ->
                    book.phrase(if (ev) "last_charger" else "last_fuel", "range" to Instr(book.distance(gap, DistanceForm.FOR)), "name" to name,
                        "distance" to Instr(book.distance(d, DistanceForm.IN)))
                }
            }
        }
    }

    private val ROADS = setOf("residential", "tertiary", "secondary", "primary", "unclassified", "service", "living_street", "tertiary_link", "secondary_link")

    /** "cobbles", "unpaved" or null for an OSM surface value. */
    fun classifySurface(s: String): String? = when (s) {
        "cobblestone", "sett", "unhewn_cobblestone", "cobblestone:flattened" -> "cobbles"
        "unpaved", "gravel", "fine_gravel", "dirt", "ground", "earth", "compacted", "sand", "grass", "mud", "pebblestone", "rock", "woodchips" -> "unpaved"
        else -> null
    }

    /** A maxspeed value as a number in its own unit ("50", "30 mph", "5 knots"); null for none/signals/walk. */
    fun parseSpeed(s: String): Double? = SPEED.find(s)?.groupValues?.get(1)?.toDoubleOrNull()

    private val SPEED = Regex("""^\s*(\d+(?:\.\d+)?)""")
}
