package app.atlas.ui.local

import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.providers.Forecast
import app.atlas.ui.local.astro.PlanetNight
import app.atlas.ui.local.astro.Tonight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

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

/** Reads the forecast (fetched, else the one kept on the phone, else none), the saved places, the markets, the station and the planets. */
internal suspend fun loadSoon(services: Services, here: LatLon, now: Instant, zone: ZoneId): SoonSnapshot = withContext(Dispatchers.Default) {
    val online = services.local.online()
    val forecast = app.atlas.ui.home.forecastOrKept(services.weather, here, online)
    val saved = runCatching { services.saved.lists.value.flatMap { l -> l.places.map { it.place } }.distinctBy { it.key } }.getOrDefault(emptyList())
    val markets = services.local.finder.find("amenity=marketplace", here, 6_000.0, limit = 12).places
    // A broken orbit file or a sky that cannot be worked out costs its own lines, not the whole screen.
    val outlook = try {
        services.local.iss(here, now)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        IssOutlook(null, emptyList())
    }
    val planets = runCatching { Tonight.planets(now, here.lat, here.lon, zone, NAKED_EYE) }.getOrDefault(emptyList())
    val inputs = MomentInputs(now, zone, here, forecast, saved, markets, outlook.nextVisible, planets)
    SoonSnapshot(forecast, outlook, planets, Moments.build(inputs), saved, markets, noForecast = forecast == null)
}
