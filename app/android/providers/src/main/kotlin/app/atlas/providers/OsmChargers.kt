package app.atlas.providers

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Shapes
import app.atlas.model.Place

/**
 * Chargers mapped in OpenStreetMap (`amenity=charging_station`, 207,000 worldwide), asked live through Overpass.
 * Offline packs carry the same data; this is for areas not downloaded. OSM has no live status, so [status] knows nothing.
 */
class OsmChargers(private val overpass: Overpass) : ChargerProvider {
    override val info = INFO

    override fun state() = ProviderState.READY

    override suspend fun near(center: LatLon, radiusM: Double, filters: ChargerFilters): List<Place> {
        val ql = OverpassQl.chargers(OverpassArea.Around(center, radiusM), socketsFor(filters))
        return overpass.places(ql).map(::withId).filter(filters::accepts)
            .sortedBy { Geo.distance(center, it.location) }.take(filters.maxResults)
    }

    override suspend fun along(points: List<LatLon>, corridorM: Double, filters: ChargerFilters): List<Place> {
        if (points.isEmpty()) return emptyList()
        val line = if (points.size > 2) Shapes.simplify(points, corridorM / 4) else points
        val ql = OverpassQl.chargers(OverpassArea.Along(line, corridorM), socketsFor(filters), timeoutSeconds = 120)
        return overpass.places(ql).map(::withId).filter(filters::accepts).take(filters.maxResults)
    }

    override suspend fun status(ids: List<String>): Map<String, ChargerStatus> = emptyMap()

    /** OSM socket keys worth asking Overpass for: the car's own and the ones that fit it. */
    private fun socketsFor(filters: ChargerFilters): Collection<String> =
        if (filters.connectors.isEmpty()) emptyList() else ALL_SOCKETS.filter { Sockets.fits(filters.connectors, it) }

    companion object {
        const val ID = "osm"

        val INFO = ProviderInfo(
            id = "osm-chargers", name = "OpenStreetMap chargers", licence = "ODbL", attribution = "© OpenStreetMap contributors",
            url = "https://www.openstreetmap.org/copyright",
            note = "Chargers mapped by OSM volunteers; live through Overpass where you have no download.",
        )

        private val ALL_SOCKETS = listOf(
            "type1", "type1_combo", "type2", "type2_cable", "type2_combo", "chademo", "chaoji", "nacs", "tesla_supercharger",
            "tesla_supercharger_ccs", "tesla_destination", "gb_ac", "gb_dc", "schuko", "typee", "cee_blue", "mcs",
        )

        /** An OSM charger with its `charger:id` ("osm:n123"), so it merges and is found like any other source's. */
        fun withId(p: Place): Place =
            if (p.id == null || p.extra["charger:id"] != null) p
            else p.copy(extra = p.extra + ("charger:id" to chargerId(ID, p.id.toString())))
    }
}
