package app.atlas.ui.go

import androidx.annotation.StringRes
import app.atlas.R
import app.atlas.model.Mode

/** Which guidance screen a mode gets: the bike's ring, the car's road, the truck's road and tachograph, the walker's arrow, the boat's chart. */
enum class Family { RIDE, DRIVE, TRUCK, WALK, SAIL }

/** The family of guidance screen for [mode]. */
fun familyOf(mode: Mode): Family = when {
    mode == Mode.TRUCK -> Family.TRUCK
    mode.water -> Family.SAIL
    mode.cycling -> Family.RIDE
    mode.motor -> Family.DRIVE
    else -> Family.WALK
}

/** The vehicles the Go tab and the planner offer, in the boards' order, each with its glyph and name. */
enum class GoMode(
    val mode: Mode,
    @StringRes val label: Int,
    /** The Go board's symbol for the mode, and the stroke width that symbol is drawn with (the boards set 1.8 or 2.2). */
    val board: String,
    val stroke: Float,
) {
    WALK(Mode.WALK, R.string.go_mode_walk, "mode.walk.2", 2.2f),
    BIKE(Mode.BIKE, R.string.go_mode_bike, "park.bike", 2f),
    EBIKE(Mode.EBIKE, R.string.go_mode_ebike, "mode.ebike", 2f),
    CARGO(Mode.CARGO_BIKE, R.string.go_mode_cargo, "mode.cargo-bike", 1.9f),
    MOTO(Mode.MOTORCYCLE, R.string.go_mode_moto, "mode.moto", 1.9f),
    CAR(Mode.CAR, R.string.go_mode_car, "mode.car", 1.9f),
    CAMPER(Mode.CAMPER, R.string.go_mode_camper, "mode.camper", 1.9f),
    TRUCK(Mode.TRUCK, R.string.go_mode_truck, "mode.truck", 1.9f),
    BOAT(Mode.BOAT, R.string.go_mode_boat, "mode.boat", 1.9f);

    companion object {
        /** The entry for [mode], or the nearest one offered (a hike walks, a road bike bikes, a sailing boat is a boat). */
        fun of(mode: Mode): GoMode = entries.firstOrNull { it.mode == mode } ?: when {
            mode.water -> BOAT
            mode == Mode.MOPED -> MOTO
            mode.cycling -> BIKE
            mode.motor -> CAR
            else -> WALK
        }

        /** The entry named [name] (a [Mode] name), or null for an unknown one. */
        fun named(name: String?): GoMode? = name?.let { n -> runCatching { of(Mode.valueOf(n)) }.getOrNull() }

        /** The modes the planner compares at once, as on the Route board: car, bike, walk, then the others on demand. */
        val compared: List<GoMode> = listOf(CAR, BIKE, WALK)
    }
}

/** The board symbol for any [Mode], for the notification and rows. */
fun modeSymbol(mode: Mode): String = GoMode.of(mode).board
