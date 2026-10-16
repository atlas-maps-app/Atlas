package app.atlas.ui.local

import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.TagFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A place by its OpenStreetMap id: the downloaded regions first, else Overpass when the phone is online; null when neither knows it. */
internal suspend fun placeById(services: Services, id: OsmId): Place? = withContext(Dispatchers.IO) {
    runCatching { services.placeStores.get(id)?.place }.getOrNull()?.let { return@withContext it }
    if (!services.local.online()) return@withContext null
    val type = when (id.type) { OsmType.NODE -> "node"; OsmType.WAY -> "way"; OsmType.RELATION -> "relation" }
    try {
        withTimeoutOrNull(30_000) { services.overpass.places("[out:json][timeout:25];$type(${id.id});out tags bb center;").firstOrNull() }?.let { it.copy(id = it.id ?: id) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/** The mapped building at [at]: the one with [osmId] when given, else the nearest `building` within 40 m. */
internal suspend fun buildingAt(services: Services, at: LatLon, osmId: String?): Place? {
    osmId?.let(OsmId::parse)?.let { id -> placeById(services, id)?.let { return it } }
    val near = services.local.finder.find(TagFilter.parse("building"), at, 40.0, limit = 5).places
    return near.firstOrNull { it.place.tags["start_date"] != null || it.place.tags["name"] != null }?.place ?: near.firstOrNull()?.place
}
