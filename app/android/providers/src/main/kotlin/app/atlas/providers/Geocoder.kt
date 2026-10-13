package app.atlas.providers

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.Place

/** An online geocoder: the fallback when the offline store has too little for a query. Results are core [Place]s. */
interface Geocoder : Provider {
    /** True if the service allows a query per keystroke; Nominatim's policy forbids it, Photon is built for it. */
    val typeahead: Boolean

    /** Places matching [text], ranked by the service and biased towards [near] when given. */
    suspend fun search(text: String, near: LatLon? = null, lang: String? = null, limit: Int = 10, box: BBox? = null): List<Place>

    /** The address or place at [at], or null when the service knows nothing there. */
    suspend fun reverse(at: LatLon, lang: String? = null): Place?
}

/** Turns a geocoder's address fields into OSM `addr:*` tags. */
internal object AddressTags {
    fun put(tags: MutableMap<String, String>, street: String?, housenumber: String?, postcode: String?, city: String?, country: String?) {
        street?.let { tags.putIfAbsent("addr:street", it) }
        housenumber?.let { tags.putIfAbsent("addr:housenumber", it) }
        postcode?.let { tags.putIfAbsent("addr:postcode", it) }
        city?.let { tags.putIfAbsent("addr:city", it) }
        country?.let { tags.putIfAbsent("addr:country", it.uppercase()) }
    }
}
