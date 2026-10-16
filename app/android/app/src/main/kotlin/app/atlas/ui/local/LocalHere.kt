package app.atlas.ui.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.nav.LocalServices
import app.atlas.places.Localise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** What the local screens call a place: the neighbourhood from the offline place store, else the town. */
object HereName {
    private val RANK = listOf("neighbourhood", "quarter", "suburb", "borough", "city_block", "hamlet", "village", "town", "city")

    /** The name of the nearest mapped neighbourhood, suburb, village, town or city within a few km of [at]; null when none. */
    suspend fun of(services: Services, at: LatLon, lang: String? = Locale.getDefault().language): String? = withContext(Dispatchers.Default) {
        val store = services.placeStores
        if (store.size == 0) return@withContext null
        val found = store.byTag("place", null, BBox.around(at, 4_000.0), limit = 200)
            .filter { it.place.tags["place"] in RANK && Localise.name(it.place.tags, lang) != null }
        // The smallest kind within reach, nearest first: a neighbourhood beats the city around it.
        val best = found.minWithOrNull(
            compareBy({ RANK.indexOf(it.place.tags["place"]).let { r -> if (r < 0) 99 else r / 3 } }, { Geo.distance(at, it.place.location) }),
        ) ?: return@withContext null
        Localise.name(best.place.tags, lang)
    }
}

/** The place name for [at] as a state: null while it is looked up and when nothing is mapped. */
@Composable
fun rememberPlaceName(at: LatLon): String? {
    val services = LocalServices.current
    val name = produceState<String?>(null, at) { value = HereName.of(services, at) }
    return name.value
}

/** A point the way the Local board writes it: "40.71° N, 73.96° W". */
fun coordsLabel(p: LatLon): String {
    val lat = String.format(Locale.ROOT, "%.2f° %s", kotlin.math.abs(p.lat), if (p.lat >= 0) "N" else "S")
    val lon = String.format(Locale.ROOT, "%.2f° %s", kotlin.math.abs(p.lon), if (p.lon >= 0) "E" else "W")
    return "$lat, $lon"
}
