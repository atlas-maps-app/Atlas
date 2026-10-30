package app.atlas.providers

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.Place
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/** An online geocoder: the fallback when the offline store has too little for a query. Results are core [Place]s. */
interface Geocoder : Provider {
    /** True if the service allows a query per keystroke; Nominatim's policy forbids it, Photon is built for it. */
    val typeahead: Boolean

    /** Places matching [text], ranked by the service and biased towards [near] when given. */
    suspend fun search(text: String, near: LatLon? = null, lang: String? = null, limit: Int = 10, box: BBox? = null): List<Place>

    /** The address or place at [at], or null when the service knows nothing there. */
    suspend fun reverse(at: LatLon, lang: String? = null): Place?
}

/**
 * [first] with a second way in for the address at a point: [second] answers where [first] cannot (down, busy, or an
 * answer that does not read), while [secondOn] (the user may switch it off). Searches stay with [first] alone: the
 * search box asks one per keystroke, which Photon is built for and Nominatim's policy forbids, while an address is
 * asked once, when the user asks what is at a place.
 */
class FailoverGeocoder(
    private val first: Geocoder,
    private val second: Geocoder,
    private val secondOn: suspend () -> Boolean = { true },
) : Geocoder by first {
    override suspend fun reverse(at: LatLon, lang: String?): Place? = try {
        first.reverse(at, lang)
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        if (!secondOn()) throw e
        try {
            second.reverse(at, lang)
        } catch (e2: IOException) {
            e2.addSuppressed(e)
            throw e2
        }
    }
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
