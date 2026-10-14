package app.atlas.ui.go

import androidx.annotation.StringRes
import app.atlas.R
import app.atlas.model.Mode

/** The modes the Go tab offers first, with their symbol and name. */
internal enum class GoMode(val mode: Mode, val symbol: String, @StringRes val label: Int) {
    WALK(Mode.WALK, "ui-walk", R.string.go_mode_walk),
    BIKE(Mode.BIKE, "ui-bike", R.string.go_mode_bike),
    CAR(Mode.CAR, "ui-car", R.string.go_mode_car),
    TRANSIT(Mode.TRANSIT, "ui-train", R.string.go_mode_transit),
    TRUCK(Mode.TRUCK, "ui-truck", R.string.go_mode_truck),
    BOAT(Mode.BOAT, "ui-sailboat", R.string.go_mode_boat),
}
