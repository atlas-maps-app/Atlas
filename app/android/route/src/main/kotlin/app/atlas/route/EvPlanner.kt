package app.atlas.route

import app.atlas.geo.LatLon
import app.atlas.geo.Polyline
import app.atlas.model.EvSpec
import app.atlas.model.Hazard
import app.atlas.model.Mode
import app.atlas.model.Place
import app.atlas.model.Route
import app.atlas.model.RouteRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where chargers come from: OSM, Open Charge Map, the providers module… Places carry `socket:*`, `capacity`, `atlas:power_kw`. */
fun interface ChargerSource {
    suspend fun chargersNear(corridor: Corridor, minKw: Double, connectors: Set<String>): List<Place>
}

/** Tuning of the charging-stop search. */
data class EvOptions(
    /** How far from the route a charger may be. */
    val corridorMetres: Double = 5_000.0,
    val minChargerKw: Double = 22.0,
    /** Parking, plugging in and paying, per stop. */
    val stopOverheadSeconds: Double = 300.0,
    val maxStops: Int = 8,
    /** Departure charge levels are tried in steps of this. */
    val socStep: Double = 0.05,
    /** Road distance to a charger off the route, as a multiple of the straight line. */
    val detourFactor: Double = 1.3,
    val detourSpeedKmh: Double = 40.0,
    /** Share of the energy that reaches the battery while charging. */
    val chargeEfficiency: Double = 0.92,
)

/** One planned charging stop. */
data class EvStop(
    val place: Place,
    val powerKw: Double,
    val arriveSoc: Double,
    val departSoc: Double,
    val chargeSeconds: Double,
)

/** A planned trip: the route through the chargers, the stops and the charge left on arrival. */
data class EvPlan(val route: Route, val stops: List<EvStop>, val arriveSoc: Double, val feasible: Boolean)

/**
 * Plans charging stops for an electric vehicle ([app.atlas.model.Vehicle.ev]): routes the trip, works out the energy of every
 * stretch (BRouter's kinematic model calibrated to the car's consumption, else a physics estimate from speed and climb), then a
 * label-setting search over the chargers along the corridor ([ChargingSearch]) finds the stops that minimise driving plus
 * charging time while the charge never drops below `socMin`. The trip is then routed again through the chosen chargers.
 * The route's duration includes the charging; its steps hold the driving only.
 */
class EvPlanner(
    private val router: BRouterRouter,
    private val chargers: ChargerSource,
    private val options: EvOptions = EvOptions(),
    /** Where the energy model and the stop search run (not the caller's thread). */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Router {

    override suspend fun route(req: RouteRequest): RouteResult {
        val ev = req.vehicle.ev ?: return router.route(req)
        val started = System.currentTimeMillis()
        return when (val p = plan(req, ev)) {
            is PlanResult.Ok -> RouteResult.Found(listOf(p.plan.route), System.currentTimeMillis() - started)
            is PlanResult.Failed -> p.failure
        }
    }

    sealed interface PlanResult {
        data class Ok(val plan: EvPlan) : PlanResult
        data class Failed(val failure: RouteResult.Failure) : PlanResult
    }

    /** Plans [req] for [ev]; when no charger gets the car there, the plan says feasible = false and marks where the battery runs low. */
    suspend fun plan(req: RouteRequest, ev: EvSpec = req.vehicle.ev ?: throw IllegalArgumentException("no EV in the request")): PlanResult {
        val since = router.engine.cancelEpoch
        return withContext(dispatcher) { planHere(req, ev, since) }
    }

    private suspend fun planHere(req: RouteRequest, ev: EvSpec, since: Long): PlanResult {
        val base = when (val t = router.trace(req.copy(alternatives = 0))) {
            is BRouterRouter.Traced.Failed -> return PlanResult.Failed(t.failure)
            is BRouterRouter.Traced.Ok -> t.routes.first()
        }
        val energy = EnergyModel.cumulativeKwh(base.raw, req, ev)
        val battery = ev.batteryKwh
        // enough charge already?
        if (ChargingSearch.lowestSoc(energy, 0, energy.size - 1, ev.socNow, battery) >= ev.socMin) {
            return PlanResult.Ok(EvPlan(base.route, emptyList(), ev.socNow - energy.last() / battery, true))
        }
        val line = Polyline(base.route.points)
        val places = chargers.chargersNear(Corridor(base.route.points, options.corridorMetres), options.minChargerKw, ev.connectors)
        val search = ChargingSearch(options)
        val candidates = search.candidates(places, ev, base.route.points, line)
        val chosen = search.search(candidates, energy, base.raw.time, ev)
            ?: return PlanResult.Ok(EvPlan(withEmptyHazard(base.route, energy, ev), emptyList(), ev.socNow - energy.last() / battery, false))

        if (router.engine.cancelEpoch != since) return PlanResult.Failed(RouteResult.Cancelled)
        // route again through the chargers, keeping the user's via points in order
        val vias = req.waypoints.drop(1).dropLast(1).map { Triple(it, line.snap(it)?.along ?: 0.0, -1) }
        val stopsAlong = chosen.mapIndexed { k, c -> Triple(c.candidate.place.location, c.candidate.along, k) }
        val middle = (vias + stopsAlong).sortedBy { it.second }
        val waypoints = listOf(req.waypoints.first()) + middle.map { it.first } + req.waypoints.last()
        val final = when (val t = router.trace(req.copy(waypoints = waypoints, alternatives = 0))) {
            is BRouterRouter.Traced.Failed -> return PlanResult.Failed(t.failure)
            is BRouterRouter.Traced.Ok -> t.routes.first()
        }
        // where each charger lies on the final route: its via index, else the nearest point after the previous one
        val points = final.route.points
        val viaIdx = final.raw.waypointIndices
        val stopIndex = IntArray(chosen.size)
        var after = 0
        for ((k, m) in middle.withIndex()) {
            if (m.third < 0) continue
            val idx = if (viaIdx.size == middle.size + 1) viaIdx[k]
            else (after until points.size).minByOrNull { app.atlas.geo.Geo.distance(points[it], m.first) } ?: after
            stopIndex[m.third] = idx
            after = idx
        }
        // replay the charge along the final route with the chosen departure levels
        val finalEnergy = EnergyModel.cumulativeKwh(final.raw, req, ev)
        val stops = ArrayList<EvStop>()
        var soc = ev.socNow
        var lastIdx = 0
        var chargeTime = 0.0
        for ((k, c) in chosen.withIndex()) {
            val idx = stopIndex[k]
            soc -= (finalEnergy[idx] - finalEnergy[lastIdx]) / battery
            val arrive = soc
            val depart = max(arrive, c.departSoc)
            val seconds = ChargeCurve.seconds(ev, c.candidate.powerKw, arrive, depart, options.chargeEfficiency) + options.stopOverheadSeconds
            chargeTime += seconds
            stops += EvStop(c.candidate.place, c.candidate.powerKw, arrive, depart, seconds)
            soc = depart
            lastIdx = idx
        }
        val arriveSoc = soc - (finalEnergy.last() - finalEnergy[lastIdx]) / battery
        val stopPlaces = stops.map { s ->
            s.place.copy(
                extra = s.place.extra + mapOf(
                    "atlas:charge_minutes" to fmt(s.chargeSeconds / 60),
                    "atlas:arrive_soc" to fmt(s.arriveSoc),
                    "atlas:depart_soc" to fmt(s.departSoc),
                    "atlas:power_kw" to fmt(s.powerKw),
                ),
            )
        }
        val hazards = final.route.hazards.toMutableList()
        if (arriveSoc < ev.socMin + 0.05) {
            val last = points.size - 1
            hazards += Hazard(HazardKind.BATTERY, last, points[last], arriveSoc * 100, null, mapOf("arrive_soc" to fmt(arriveSoc)))
        }
        val route = final.route.copy(stops = stopPlaces, duration = final.route.duration + chargeTime, hazards = hazards)
        return PlanResult.Ok(EvPlan(route, stops, arriveSoc, true))
    }

    private fun withEmptyHazard(route: Route, energy: DoubleArray, ev: EvSpec): Route {
        val firstEmpty = energy.indices.firstOrNull { ev.socNow - energy[it] / ev.batteryKwh < ev.socMin } ?: return route
        return route.copy(
            hazards = route.hazards + Hazard(
                HazardKind.BATTERY, firstEmpty, route.points[firstEmpty], ev.socMin * 100, "no reachable charger",
                mapOf("reason" to "no_chargers"),
            ),
        )
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.2f", v)
}

/**
 * The charging-stop search: label-setting over (charger, departure charge). From each label every later charger or the
 * destination is tried; at a charger the car leaves with one of several charge levels (steps of [EvOptions.socStep], or just
 * enough for the rest of the trip). Labels are settled in order of time, so the first to reach the destination is the
 * fastest plan; labels beaten on both time and charge are dropped.
 */
class ChargingSearch(private val options: EvOptions = EvOptions()) {

    /** A charger as a node of the search: where it joins the route and how far off it lies. */
    data class Candidate(val place: Place, val powerKw: Double, val along: Double, val pointIndex: Int, val offsetMetres: Double)

    /** A chosen stop and the charge to leave it with. */
    data class Choice(val candidate: Candidate, val departSoc: Double)

    /** The usable chargers within the corridor, ordered along the route. */
    fun candidates(places: List<Place>, ev: EvSpec, route: List<LatLon>, line: Polyline = Polyline(route)): List<Candidate> {
        val index = SegmentIndex(route, cell = 500.0)
        val out = ArrayList<Candidate>()
        for (p in places) {
            val power = ChargerPower.usableKw(p, ev) ?: continue
            if (power < options.minChargerKw) continue
            val near = index.nearest(p.location, options.corridorMetres) ?: continue
            val c = line.cumulative
            val along = c[near.segment] + near.t * (c[near.segment + 1] - c[near.segment])
            val pi = if (near.t < 0.5) near.segment else near.segment + 1
            out += Candidate(p, power, along, pi, near.distance)
        }
        out.sortBy { it.along }
        return out
    }

    private class Label(val node: Int, val time: Double, val soc: Double, val stops: Int, val prev: Label?)

    /**
     * The fastest set of stops, or null when the destination cannot be reached. [energy] is kWh from the start per route
     * point and [time] seconds from the start.
     */
    fun search(cands: List<Candidate>, energy: DoubleArray, time: DoubleArray, ev: EvSpec): List<Choice>? {
        val battery = ev.batteryKwh
        val reserve = ev.socMin
        val k = cands.size
        val dest = k + 1
        val lastPoint = energy.size - 1
        fun pointOf(node: Int) = when (node) {
            0 -> 0
            dest -> lastPoint
            else -> cands[node - 1].pointIndex
        }
        fun offset(node: Int) = if (node in 1..k) cands[node - 1].offsetMetres * options.detourFactor else 0.0
        fun detourKwh(metres: Double) = metres / 100_000.0 * ev.consumption * 0.9
        fun detourSeconds(metres: Double) = metres / (options.detourSpeedKmh / 3.6)

        val queue = PriorityQueue<Label>(compareBy { it.time })
        val settled = Array(k + 2) { ArrayList<Label>() }
        queue += Label(0, 0.0, ev.socNow, 0, null)
        while (queue.isNotEmpty()) {
            val l = queue.poll()
            if (settled[l.node].any { it.soc >= l.soc - 1e-9 }) continue
            settled[l.node] += l
            if (l.node == dest) return unwind(l, cands)
            val from = pointOf(l.node)
            val leave = offset(l.node)
            val startSoc = l.soc - detourKwh(leave) / battery
            var scanned = from
            var maxUse = 0.0
            for (j in (l.node + 1)..dest) {
                val to = pointOf(j)
                if (to < from) continue
                while (scanned < to) {
                    scanned++
                    maxUse = max(maxUse, energy[scanned] - energy[from])
                }
                val reach = offset(j)
                val need = energy[to] - energy[from] + detourKwh(leave) + detourKwh(reach)
                val arrive = l.soc - need / battery
                // the charge must hold everywhere on the way, not only on arrival
                if (arrive < reserve || startSoc - maxUse / battery < reserve) {
                    if (maxUse > (l.soc - reserve) * battery * 1.5) break // every later node is further still
                    continue
                }
                val drive = (time[to] - time[from]).coerceAtLeast(0.0) + detourSeconds(leave) + detourSeconds(reach)
                if (j == dest) {
                    queue += Label(dest, l.time + drive, arrive, l.stops, l)
                    continue
                }
                if (l.stops >= options.maxStops) continue
                val c = cands[j - 1]
                for (target in departureLevels(arrive, energy, to, reach, ev)) {
                    if (settled[j].any { it.soc >= target - 1e-9 }) continue // reached sooner with as much charge
                    val charge = ChargeCurve.seconds(ev, c.powerKw, arrive, target, options.chargeEfficiency) + options.stopOverheadSeconds
                    queue += Label(j, l.time + drive + charge, target, l.stops + 1, l)
                }
            }
        }
        return null
    }

    /** Charge levels worth leaving a charger with: steps up to full, and just enough for the rest of the trip. */
    private fun departureLevels(arrive: Double, energy: DoubleArray, at: Int, offsetMetres: Double, ev: EvSpec): List<Double> {
        val out = ArrayList<Double>()
        var s = kotlin.math.ceil((arrive + 1e-6) / options.socStep) * options.socStep
        while (s <= 1.0 + 1e-9) {
            out += min(1.0, s); s += options.socStep
        }
        val back = offsetMetres / 100_000.0 * ev.consumption * 0.9
        val toEnd = ev.socMin + (energy.last() - energy[at] + back) / ev.batteryKwh + 0.02
        if (toEnd > arrive && toEnd <= 1.0) out += toEnd
        return out.distinct()
    }

    private fun unwind(end: Label, cands: List<Candidate>): List<Choice> {
        val out = ArrayList<Choice>()
        var l: Label? = end.prev
        while (l != null && l.node != 0) {
            out += Choice(cands[l.node - 1], l.soc)
            l = l.prev
        }
        return out.reversed()
    }

    companion object {
        /** The lowest charge between points [from] and [to] when leaving [from] with [soc]. */
        fun lowestSoc(energy: DoubleArray, from: Int, to: Int, soc: Double, battery: Double): Double {
            var maxUse = 0.0
            val base = energy[from]
            for (i in from..to) maxUse = max(maxUse, energy[i] - base)
            return soc - maxUse / battery
        }
    }
}

/** Energy along a raw track, in kWh from the start, at the battery. */
object EnergyModel {
    private const val G = 9.81

    /** The mass to lift when the vehicle's weight is not given. */
    private fun defaultTonnes(mode: Mode) = when (mode) {
        Mode.TRUCK -> 40.0
        Mode.CAMPER -> 3.5
        Mode.MOPED, Mode.MOTORCYCLE -> 0.3
        else -> 2.0
    }

    /**
     * BRouter's kinematic energy (car profiles) scaled so that a flat 100 km at 100 km/h uses [EvSpec.consumption]; tracks
     * without it get a physics estimate: consumption scaled for speed (air drag grows with its square) plus climbing, with
     * part of the descent regained.
     */
    fun cumulativeKwh(raw: RawTrack, req: RouteRequest, ev: EvSpec): DoubleArray {
        val n = raw.size
        val out = DoubleArray(n)
        val kinematic = n > 1 && raw.energy[n - 1] > 0
        if (kinematic && req.mode == Mode.CAR) {
            val kv = ProfileOptions.keyValues(req.mode, req.prefs, req.vehicle)
            val fRoll = kv["f_roll"]?.toDoubleOrNull() ?: 232.0
            val fAir = kv["f_air"]?.toDoubleOrNull() ?: 0.4
            val standby = kv["p_standby"]?.toDoubleOrNull() ?: 250.0
            val v = 100 / 3.6
            val flatKwhPer100 = (fRoll + fAir * v * v + standby / v) * 100_000 / 3.6e6
            val scale = ev.consumption / flatKwhPer100
            for (i in 0 until n) out[i] = raw.energy[i] / 3.6e6 * scale
            return out
        }
        val mass = (req.vehicle.weightT ?: defaultTonnes(req.mode)) * 1000
        val cum = raw.cumulative()
        val ele = TrackConverter.filledElevations(raw.ele, cum)
        for (i in 1 until n) {
            val d = cum[i] - cum[i - 1]
            val dt = raw.time[i] - raw.time[i - 1]
            val v = if (dt > 0) d / dt else 100 / 3.6
            val speedFactor = 0.45 + 0.55 * (v / (100 / 3.6)).let { it * it }
            var e = ev.consumption * d / 100_000 * speedFactor
            if (ele != null) {
                val dh = ele[i] - ele[i - 1]
                val climb = mass * G * dh / 3.6e6
                e += if (dh > 0) climb / 0.9 else climb * 0.65
            }
            out[i] = out[i - 1] + e
        }
        return out
    }
}

/** How long charging takes, from the car's curve (or a flat curve tapering above 80 %) capped by the charger. */
object ChargeCurve {
    /** Power the car accepts at [soc] (0..1) on a charger of [chargerKw]. */
    fun powerKw(ev: EvSpec, chargerKw: Double, soc: Double): Double = power(ev, sorted(ev.curve), chargerKw, soc)

    /** Seconds to charge from [from] to [to] (0..1). */
    fun seconds(ev: EvSpec, chargerKw: Double, from: Double, to: Double, efficiency: Double = 0.92): Double {
        if (to <= from) return 0.0
        // the search asks this for every label and level: sort the curve once per call, not once per step
        val curve = sorted(ev.curve)
        val steps = max(1, ((to - from) / 0.005).roundToInt())
        val ds = (to - from) / steps
        var t = 0.0
        for (k in 0 until steps) {
            val s = from + (k + 0.5) * ds
            t += ev.batteryKwh * ds / (power(ev, curve, chargerKw, s) * efficiency) * 3600
        }
        return t
    }

    private fun power(ev: EvSpec, curve: List<Pair<Double, Double>>, chargerKw: Double, soc: Double): Double {
        val dc = chargerKw > ev.maxAcKw
        val carMax = if (dc) ev.maxDcKw else ev.maxAcKw
        val car = if (dc && curve.isNotEmpty()) interpolate(curve, soc) else taper(carMax, soc, if (dc) 0.8 else 0.95)
        return max(1.0, min(car, chargerKw))
    }

    /** The curve by charge level; the list itself when it already is (the usual case), so nothing is allocated. */
    private fun sorted(curve: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        for (i in 1 until curve.size) if (curve[i].first < curve[i - 1].first) return curve.sortedBy { it.first }
        return curve
    }

    private fun taper(max: Double, soc: Double, knee: Double): Double =
        if (soc <= knee) max else max * (1 - 0.8 * ((soc - knee) / (1 - knee)).coerceIn(0.0, 1.0))

    /** Linear interpolation in [pts], sorted by charge level. */
    private fun interpolate(pts: List<Pair<Double, Double>>, soc: Double): Double {
        if (soc <= pts.first().first) return pts.first().second
        if (soc >= pts.last().first) return pts.last().second
        for (i in 1 until pts.size) {
            val (s1, p1) = pts[i]
            val (s0, p0) = pts[i - 1]
            if (soc <= s1) return if (s1 == s0) p1 else p0 + (p1 - p0) * (soc - s0) / (s1 - s0)
        }
        return pts.last().second
    }
}

/** What a charger can give a car: the best compatible socket's power, capped by the car. */
object ChargerPower {
    private val POWER = Regex("""(\d+(?:[.,]\d+)?)\s*(kw|w|mw)?""", RegexOption.IGNORE_CASE)
    private val DC = setOf("type2_combo", "type1_combo", "chademo", "tesla_supercharger", "nacs", "mcs", "tesla_supercharger_ccs")

    /** Usable kW at [place] for [ev], or null when no socket fits. */
    fun usableKw(place: Place, ev: EvSpec): Double? {
        val sockets = place.tags.keys.filter { it.startsWith("socket:") && it.count { c -> c == ':' } == 1 }
        if (sockets.isEmpty()) {
            val kw = kw(place.tags["atlas:power_kw"]) ?: kw(place.tags["charging_station:output"]) ?: return null
            return min(kw, if (kw > ev.maxAcKw) ev.maxDcKw else ev.maxAcKw)
        }
        var best: Double? = null
        for (key in sockets) {
            val type = key.removePrefix("socket:")
            if (type !in ev.connectors) continue
            val count = place.tags[key]?.toIntOrNull()
            if (count != null && count <= 0) continue
            val kw = kw(place.tags["$key:output"]) ?: kw(place.tags["atlas:power_kw"]) ?: if (type in DC) 50.0 else 11.0
            val usable = min(kw, if (type in DC) ev.maxDcKw else ev.maxAcKw)
            if (best == null || usable > best) best = usable
        }
        return best
    }

    /** kW from "150 kW", "22", "11kW", "7400 W"; the highest when several are listed. */
    fun kw(value: String?): Double? {
        val v = value ?: return null
        return POWER.findAll(v).mapNotNull { m ->
            val num = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            when (m.groupValues[2].lowercase(Locale.ROOT)) {
                "w" -> num / 1000
                "mw" -> num * 1000
                else -> num
            }
        }.maxOrNull()?.takeIf { abs(it) > 0 }
    }
}
