package app.atlas.ui.go

import app.atlas.geo.LatLon
import app.atlas.model.Hazard
import app.atlas.model.Mode
import app.atlas.model.Route
import app.atlas.route.HazardKind
import app.atlas.search.PlaceSource
import app.atlas.search.within

/** The one fact a mode's row shows besides its time, as on the Route board. */
sealed interface KeyFact {
    /** Car-like modes: parking places mapped within reach of the destination, and how many are free of charge. */
    data class Parking(val count: Int, val free: Int) : KeyFact

    /** Bikes: the climb, and the share of the way on paths and streets away from cars. */
    data class Ride(val climbM: Double, val carFreeShare: Double) : KeyFact

    /** On foot: the distance. */
    data class Walk(val metres: Double) : KeyFact

    /** Boats: the locks and bridges to pass. */
    data class Water(val locks: Int, val bridges: Int) : KeyFact
}

/** Works out what a route's row and the comparison say, from the route's own spans and hazards and the place store. */
object RouteFacts {
    /** Ways that keep a bike or a walker away from cars. */
    private val CAR_FREE = setOf("cycleway", "path", "footway", "pedestrian", "track", "bridleway", "living_street", "steps")

    /** The share (0..1) of [route]'s length on ways without motor traffic (cycleways, paths, pedestrian streets). */
    fun carFreeShare(route: Route): Double {
        val n = route.points.size
        if (n < 2 || route.distance <= 0) return 0.0
        val cum = route.line.cumulative
        var free = 0.0
        for (s in route.spans) {
            if (s.key != "highway" || s.value !in CAR_FREE) continue
            val a = s.from.coerceIn(0, n - 1)
            val b = s.to.coerceIn(0, n - 1)
            free += (cum[b] - cum[a]).coerceAtLeast(0.0)
        }
        // A separate cycle track beside a road ("cycleway=track") counts too.
        for (s in route.spans) {
            if (s.key != "cycleway" || s.value != "track" && s.value != "separate") continue
            val a = s.from.coerceIn(0, n - 1)
            val b = s.to.coerceIn(0, n - 1)
            if (route.spans.none { it.key == "highway" && it.value in CAR_FREE && it.from <= a && it.to >= b }) free += (cum[b] - cum[a]).coerceAtLeast(0.0)
        }
        return (free / cum.last()).coerceIn(0.0, 1.0)
    }

    /** The key fact for [mode]'s [route]; [places] (the offline store) answers parking near [destination]. */
    fun of(mode: Mode, route: Route, destination: LatLon, places: PlaceSource?): KeyFact? = when {
        mode.water -> KeyFact.Water(route.hazards.count { it.kind == HazardKind.LOCK }, route.hazards.count { it.kind == HazardKind.BRIDGE })
        mode.cycling -> KeyFact.Ride(route.climb, carFreeShare(route))
        mode.motor -> places?.let { parkingNear(it, destination) }
        else -> KeyFact.Walk(route.distance)
    }

    /** Parking within [radiusM] of [at]: car parks and parking buildings, not private ones. */
    fun parkingNear(places: PlaceSource, at: LatLon, radiusM: Double = 400.0): KeyFact.Parking? {
        if (places.size == 0) return null
        val found = places.within(at, radiusM) { r ->
            r.tags["amenity"] == "parking" && r.tags["access"] !in setOf("private", "no", "customers") && r.tags["parking"] != "street_side"
        }
        return KeyFact.Parking(found.size, found.count { it.tags["fee"] == "no" })
    }

    /** The next hazard of one of [kinds] ahead of [along] metres, with its distance from there. */
    fun nextHazard(route: Route, along: Double, kinds: Set<String>): Pair<Hazard, Double>? {
        val cum = route.line.cumulative
        for (h in route.hazards) {
            if (h.kind !in kinds) continue
            val at = cum[h.pointIndex.coerceIn(0, cum.size - 1)]
            if (at >= along - 5) return h to (at - along).coerceAtLeast(0.0)
        }
        return null
    }

    /** The steepest climb in percent over [windowM] metres starting within [aheadM] of [along], from the heights. */
    fun climbAhead(route: Route, along: Double, aheadM: Double = 1_000.0, windowM: Double = 100.0): Double? {
        val e = route.elevations
        if (e.size != route.points.size || e.size < 2) return null
        val cum = route.line.cumulative
        var best = Double.NaN
        var j = 0
        for (i in e.indices) {
            if (cum[i] < along) continue
            if (cum[i] > along + aheadM) break
            if (e[i].isNaN()) continue
            if (j < i) j = i
            while (j < e.size - 1 && cum[j] - cum[i] < windowM) j++
            val d = cum[j] - cum[i]
            if (d < windowM * 0.5 || e[j].isNaN()) continue
            val grade = (e[j] - e[i]) / d * 100
            if (best.isNaN() || grade > best) best = grade
        }
        return best.takeIf { !it.isNaN() && it > 0.5 }
    }
}
