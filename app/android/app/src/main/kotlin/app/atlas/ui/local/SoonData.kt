package app.atlas.ui.local

import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.providers.Forecast
import app.atlas.ui.local.astro.PlanetNight
import app.atlas.ui.local.astro.Tonight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext

/** Everything the Soon, Moments, Sky and Local screens say about the next hours, gathered once. */
class SoonSnapshot(
    val forecast: Forecast?,
    val outlook: IssOutlook?,
    val planets: List<PlanetNight>,
    val moments: List<Moment>,
    val saved: List<Place>,
    val markets: List<Found>,
    /** True when the forecast could not be fetched and none is kept: weather lines are left out. */
    val noForecast: Boolean,
)

/**
 * Where [loadSoon] asks what may take the network, each [online] or not: the forecast (fetched, else the one kept on
 * the phone), the markets near (the place store, else Overpass) and the space station's passes (orbit data from
 * CelesTrak at most every two hours, else the kept or bundled set). The app's by default; tests give their own.
 */
internal class SoonSources(
    val forecast: suspend (online: Boolean) -> Forecast?,
    val markets: suspend (online: Boolean) -> List<Found>,
    val outlook: suspend (online: Boolean) -> IssOutlook,
) {
    companion object {
        fun of(services: Services, here: LatLon, now: Instant) = SoonSources(
            forecast = { online -> app.atlas.ui.home.forecastOrKept(services.weather, here, online) },
            markets = { online -> services.local.finder.find("amenity=marketplace", here, 6_000.0, limit = 12, online = online).places },
            outlook = { online -> services.local.iss(here, now, online = online) },
        )
    }
}

/** How long [loadSoon] waits for a source that asks the network before it takes what the phone has. */
internal const val SOON_DEADLINE_MS = 15_000L

/**
 * Reads the forecast (fetched, else the one kept on the phone, else none), the saved places, the markets, the station
 * and the planets. The forecast, the markets and the station are asked at once, side by side, each for no longer than
 * [deadlineMs]; past it, each is asked again of the phone alone: the forecast kept, the markets in the place store, the
 * passes of the orbit data kept or bundled. One after another, as until 30 October, a slow line kept the Local, Soon,
 * Moments and Sky screens empty for minutes (the user, 30 October: "are all sources reliable and respond fast?").
 */
internal suspend fun loadSoon(
    services: Services,
    here: LatLon,
    now: Instant,
    zone: ZoneId,
    sources: SoonSources = SoonSources.of(services, here, now),
    deadlineMs: Long = SOON_DEADLINE_MS,
    context: CoroutineContext = Dispatchers.Default,
): SoonSnapshot = withContext(context) {
    val online = services.local.online()
    val forecast = async { inTime(online, deadlineMs, sources.forecast) }
    val markets = async { inTime(online, deadlineMs, sources.markets) }
    // A broken orbit file or a sky that cannot be worked out costs its own lines, not the whole screen.
    val outlook = async {
        try {
            inTime(online, deadlineMs, sources.outlook)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            IssOutlook(null, emptyList())
        }
    }
    val saved = runCatching { services.saved.lists.value.flatMap { l -> l.places.map { it.place } }.distinctBy { it.key } }.getOrDefault(emptyList())
    val planets = runCatching { Tonight.planets(now, here.lat, here.lon, zone, NAKED_EYE) }.getOrDefault(emptyList())
    val inputs = MomentInputs(now, zone, here, forecast.await(), saved, markets.await(), outlook.await().nextVisible, planets)
    SoonSnapshot(forecast.await(), outlook.await(), planets, Moments.build(inputs), saved, markets.await(), noForecast = forecast.await() == null)
}

/** An answer, null or not, told apart from a deadline's null. */
private class Answer<T>(val value: T)

/**
 * [ask]'s answer [online], or, where that takes longer than [deadlineMs], its answer from the phone alone. Offline it is
 * asked of the phone at once.
 */
private suspend fun <T> inTime(online: Boolean, deadlineMs: Long, ask: suspend (online: Boolean) -> T): T {
    if (!online) return ask(false)
    return (withTimeoutOrNull(deadlineMs) { Answer(ask(true)) } ?: Answer(ask(false))).value
}
