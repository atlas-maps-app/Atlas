package app.atlas.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import app.atlas.geo.LatLon
import app.atlas.nav.LocalServices
import app.atlas.places.OpeningHoursContext
import app.atlas.ui.search.HereContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/**
 * The holidays and the sun at [at], which a screen reads the hours of the places there in ([HereContext.hours]: the
 * country there, never the phone's language, and the real sun), kept per point: those of [rememberCountryAt].
 */
@Composable
fun rememberHoursAt(at: LatLon): OpeningHoursContext {
    val zone = LocalServices.current.clock.zone
    val country = rememberCountryAt(at)
    return remember(country, at, zone) { OpeningHoursContext.forPlace(country, zone, at.lat, at.lon) }
}

/**
 * The country of the map at [at] ([HereContext.country]: the regions on the phone, else the map's own), which a place
 * naming none of its own is read in, its holidays and its brand; kept per point. The regions are asked off the main
 * thread, once a kilometre; until they answer, what is known without them ([HereContext.countryNow]).
 */
@Composable
fun rememberCountryAt(at: LatLon): String? {
    val services = LocalServices.current
    val country = remember(services, at) { mutableStateOf(HereContext.countryNow(services, at)) }
    LaunchedEffect(country) { country.value = withContext(Dispatchers.IO) { HereContext.country(services, at) } }
    return country.value
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
