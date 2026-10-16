package app.atlas.ui.mine.charge

import app.atlas.model.Place
import app.atlas.model.Route

/** One planned charging stop as the screen lists it. */
data class StopView(
    val place: Place,
    val name: String,
    val powerKw: Double?,
    val arriveSoc: Double?,
    val departSoc: Double?,
    val chargeMinutes: Double?,
    /** Metres along the route. */
    val along: Double,
)

/** A trip with charging: the route, where it stops to charge, and what that costs in time. */
data class ChargingTrip(val route: Route, val stops: List<StopView>) {
    val chargeMinutes: Double get() = stops.sumOf { it.chargeMinutes ?: 0.0 }

    /** Seconds of driving (the route's duration includes the charging). */
    val driveSeconds: Double get() = (route.duration - chargeMinutes * 60.0).coerceAtLeast(0.0)

    companion object {
        /** The stops of [route] with their facts out of the planner's tags, in order along the route. */
        fun of(route: Route): ChargingTrip {
            val stops = route.stops.map { p ->
                fun num(k: String) = (p.extra[k] ?: p.tags[k])?.toDoubleOrNull()
                StopView(
                    place = p,
                    name = p.name() ?: p.tags["operator"] ?: p.tags["network"] ?: "",
                    powerKw = num("atlas:power_kw"),
                    arriveSoc = num("atlas:arrive_soc"),
                    departSoc = num("atlas:depart_soc"),
                    chargeMinutes = num("atlas:charge_minutes"),
                    along = route.line.snap(p.location)?.along ?: 0.0,
                )
            }.sortedBy { it.along }
            return ChargingTrip(route, stops)
        }
    }
}
