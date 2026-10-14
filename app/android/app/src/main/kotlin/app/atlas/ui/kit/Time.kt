package app.atlas.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.atlas.geo.LatLon
import app.atlas.nav.LocalServices
import java.time.Clock
import java.time.LocalDateTime

/**
 * "Now" by the app's clock (`Services.clock`), in its time zone, read when the screen first shows and again whenever
 * one of [keys] changes. Screens use it instead of `LocalDateTime.now()`, so tests and screenshots can fix the time.
 */
@Composable
fun rememberNow(vararg keys: Any?): LocalDateTime {
    val clock = LocalServices.current.clock
    return remember(clock, *keys) { LocalDateTime.now(clock) }
}

/** The app's clock, for screens that reason with instants and zones themselves. */
@Composable
fun appClock(): Clock = LocalServices.current.clock

/**
 * True when it is dark at [at] by the app's clock (past civil dusk): navigation then switches to the night look, black
 * and lime, whatever the system theme.
 */
@Composable
fun rememberAfterDark(at: LatLon): Boolean {
    val clock = LocalServices.current.clock
    return remember(clock, at) { isAfterDark(at, clock.instant()) }
}
