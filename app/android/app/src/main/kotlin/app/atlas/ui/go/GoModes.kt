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
enum class GoMode(val mode: Mode, val symbol: String, @StringRes val label: Int) {
    WALK(Mode.WALK, "go-walk", R.string.go_mode_walk),
    BIKE(Mode.BIKE, "go-bike", R.string.go_mode_bike),
    EBIKE(Mode.EBIKE, "go-ebike", R.string.go_mode_ebike),
    CARGO(Mode.CARGO_BIKE, "go-cargo", R.string.go_mode_cargo),
    MOTO(Mode.MOTORCYCLE, "go-moto", R.string.go_mode_moto),
    CAR(Mode.CAR, "go-car", R.string.go_mode_car),
    CAMPER(Mode.CAMPER, "go-camper", R.string.go_mode_camper),
    TRUCK(Mode.TRUCK, "go-truck", R.string.go_mode_truck),
    BOAT(Mode.BOAT, "go-boat", R.string.go_mode_boat);

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

/** The glyph for any [Mode], for the notification and rows. */
fun modeSymbol(mode: Mode): String = GoMode.of(mode).symbol
