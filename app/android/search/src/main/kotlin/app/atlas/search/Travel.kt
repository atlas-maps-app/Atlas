package app.atlas.search

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How the searcher gets about, and so how long a place takes to reach: a result's main tell. [kmh] is the speed over
 * the way there, [detour] how much longer the way is than the line, [minutesExtra] what it costs besides moving
 * (unlocking a bike, parking a car, waiting for a tram), [symbol] its symbol.
 */
enum class TravelMode(val kmh: Double, val detour: Double, val minutesExtra: Double, val symbol: String) {
    WALK(4.8, 1.3, 0.0, "ui-walk"),
    BIKE(15.0, 1.25, 1.0, "ui-bike"),
    CAR(25.0, 1.4, 4.0, "ui-car"),
    TRANSIT(18.0, 1.3, 7.0, "ui-tram"),
}

/**
 * Minutes from here to a place by each way of getting about, estimated from the distance alone: the walk at the pace
 * of the app's walk time (4.8 km/h, the way 1.3 times the line), a car faster once out of town. A real route can say
 * more; this answers at once and offline.
 */
object Travel {
    fun minutes(metres: Double, mode: TravelMode): Int {
        val way = metres * mode.detour
        val hours = when (mode) {
            // town speed for the first 5 km, then the open road
            TravelMode.CAR -> if (way <= 5_000) way / 1000 / mode.kmh else 5.0 / mode.kmh + (way - 5_000) / 1000 / 60.0
            // a stop is a walk away: under a kilometre walking wins
            TravelMode.TRANSIT -> if (way < 1_000) way / 1000 / TravelMode.WALK.kmh else way / 1000 / mode.kmh
            else -> way / 1000 / mode.kmh
        }
        val extra = if (mode == TravelMode.TRANSIT && way < 1_000) 0.0 else mode.minutesExtra
        return max(1, ceil(hours * 60 + extra).toInt())
    }

    /** The way of getting about that reaches [metres] soonest of [modes], and in how many minutes. */
    fun fastest(metres: Double, modes: Collection<TravelMode>): Pair<TravelMode, Int> =
        modes.map { it to minutes(metres, it) }.minBy { it.second }

    /** The way a searcher who did not say goes: on foot up to 2 km, by bike up to 8 km, else by car. */
    fun likely(metres: Double): TravelMode = when {
        metres <= 2_000 -> TravelMode.WALK
        metres <= 8_000 -> TravelMode.BIKE
        else -> TravelMode.CAR
    }

    /** "6 min", "1 h 20", "3 h" for [minutes]. */
    fun format(minutes: Int): String = when {
        minutes < 60 -> "$minutes′"
        minutes % 60 == 0 || minutes >= 300 -> "${(minutes / 60.0).roundToInt()} h"
        else -> "${minutes / 60} h ${"%02d".format(minutes % 60)}"
    }
}
