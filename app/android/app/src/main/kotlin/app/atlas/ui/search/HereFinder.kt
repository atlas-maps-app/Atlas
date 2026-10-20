package app.atlas.ui.search

import app.atlas.Services
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.services.LocalAddress
import app.atlas.services.LocalData
import app.atlas.services.PlaceOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** The address of a point: its street and number, the quarter and town, and where it was read. */
data class HereAddress(
    val street: String?,
    val housenumber: String?,
    val postcode: String?,
    val quarter: String?,
    val town: String?,
    val origin: PlaceOrigin,
) {
    /** "Westermarkt 13", or the street alone, or null. */
    val line: String? get() = street?.let { s -> housenumber?.let { "$s $it" } ?: s }

    /** "1016 DK · Jordaan · Amsterdam": what is known beyond the street. */
    val area: String? get() = listOfNotNull(postcode, quarter, town?.takeIf { it != quarter }).joinToString(" · ").ifEmpty { null }

    companion object {
        /** The address in [tags] (`addr:*`), or null when they have no street or place. */
        fun of(tags: Map<String, String>, origin: PlaceOrigin): HereAddress? {
            val street = tags["addr:street"] ?: tags["addr:place"] ?: return null
            return HereAddress(street, tags["addr:housenumber"], tags["addr:postcode"], null, tags["addr:city"], origin)
        }

        /** The address [LocalData] found. */
        fun of(a: LocalAddress): HereAddress = HereAddress(a.street, a.housenumber, a.postcode, a.quarter, a.town, a.origin)
    }
}

/** What is at a point: its address, the place it is in (a building, a church, a park), and the named things around. */
data class HereFacts(val address: HereAddress?, val spot: Place?, val nearby: List<Place>, val origin: PlaceOrigin?)

/**
 * Finds what is at a point for "What's here": from what the phone holds ([LocalData.whatsHere]: a downloaded region's
 * store, else the map's packs; the place whose outline holds the point, the nearest mapped address, the named places
 * measured to their outlines), with Overpass online for the places around where nothing on the phone covers; and,
 * online, the reverse geocoder for an address the phone does not know.
 */
object HereFinder {
    const val NEARBY_M = LocalData.NEARBY_M
    private const val ADDRESS_M = 40.0

    suspend fun find(services: Services, p: LatLon, lang: String, online: Boolean): HereFacts = withContext(Dispatchers.Default) {
        val here = services.localData.whatsHere(p, NEARBY_M)
        val facts = HereFacts(here.address?.let(HereAddress::of), here.spot, here.nearby, here.origin)
        if (facts.address != null || !online) return@withContext facts
        val reverse = try {
            services.geocoder.reverse(p, lang)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            null
        }
        val address = reverse?.takeIf { Geo.distance(it.location, p) <= ADDRESS_M * 2 }?.let { HereAddress.of(it.tags, PlaceOrigin.ONLINE) }
        facts.copy(address = address)
    }
}
