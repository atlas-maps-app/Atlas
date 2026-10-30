package app.atlas.ui.mine.charge

import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.providers.ChargerFilters
import app.atlas.providers.ChargerMerge
import app.atlas.providers.ProviderState
import app.atlas.services.PlaceOrigin
import app.atlas.services.SettingsKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/** Chargers around a point and where they came from: the providers when online, the phone's own data when not. */
data class ChargeResult(val places: List<Place>, val online: Boolean, val sources: List<String>, val error: String? = null)

/**
 * Finds the charging sites around a point. Online it asks every charger source that is on and ready (OpenStreetMap
 * through Overpass, Open Charge Map), merges the answers by place and fills in live status for the sites that have an id
 * at a source that gives it. Offline, or when the network fails, it falls back to what the phone has: the place stores of
 * the downloaded regions and the map's own points of interest.
 */
class ChargeRepository(private val services: Services) {
    suspend fun load(centre: LatLon, radiusM: Double = 5_000.0): ChargeResult = withContext(Dispatchers.IO) {
        val off = runCatching { services.settings.flow(SettingsKeys.PROVIDERS_OFF, emptySet()).first() }.getOrDefault(emptySet())
        val online = !services.config.offline && services.connectivity.online.value
        var failure: String? = null
        if (online) {
            val ready = services.chargers.filter { it.state() == ProviderState.READY && it.id !in off && "${it.id}-chargers" !in off }
            try {
                // each source by its deadline: the OSM chargers queue behind the app's other Overpass queries, and the
                // screen spun while the Dutch register's answer was ready (ChargerMerge.gather)
                val found = ChargerMerge.gather(ready, SOURCE_DEADLINE) { p -> p.near(centre, radiusM, ChargerFilters(maxResults = 120)) }
                val merged = ChargerMerge.merge(found)
                if (merged.isNotEmpty()) return@withContext ChargeResult(withStatus(ready.firstOrNull { it.id == "ocm" }, merged), true, ready.map { it.id })
            } catch (e: IOException) {
                failure = e.message
            }
        }
        val near = runCatching { services.places.nearby("charge", centre, radiusM, 120).first { it.places.isNotEmpty() || it.error != null || it.source == PlaceOrigin.STORE } }.getOrNull()
        ChargeResult(near?.places.orEmpty(), online, listOf(near?.source?.name?.lowercase() ?: "none"), failure ?: near?.error)
    }

    /** Puts live status from [provider] on the sites that it knows (by `charger:id`); the others stay as they are. */
    private suspend fun withStatus(provider: app.atlas.providers.ChargerProvider?, places: List<Place>): List<Place> {
        if (provider == null) return places
        val ids = places.mapNotNull { it.extra["charger:id"]?.takeIf { id -> id.startsWith("${provider.id}:") } }
        if (ids.isEmpty()) return places
        val status = runCatching { provider.status(ids.take(200)) }.getOrDefault(emptyMap())
        if (status.isEmpty()) return places
        return places.map { p -> status[p.extra["charger:id"]]?.let { s -> p.copy(extra = p.extra + s.toExtra()) } ?: p }
    }

    companion object {
        /** The longest the screen waits for one source: the others' chargers show without it after that. */
        val SOURCE_DEADLINE = 30.seconds
    }
}
