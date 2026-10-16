package app.atlas.ui.eat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.PlaceOrigin
import app.atlas.ui.kit.rememberHere
import java.time.LocalDateTime
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What every eat & drink screen reasons with: the places around, the moment and party of the table, and the ratings
 * known so far. [at] is the table's time (now, unless one was chosen).
 */
@Stable
class EatContext(
    val services: Services,
    val repo: EatRepository,
    val area: EatArea,
    val table: Table,
    val at: LocalDateTime,
    val ratings: AreaRatings,
    val lang: String,
) {
    /** Opens [place]'s own screen. */
    fun open(nav: Navigator, place: Place) {
        services.remember(place)
        nav.go(Dest.Place.of(place))
    }

    /** Walking directions to [place]. */
    fun walkTo(nav: Navigator, place: EatPlace) =
        nav.go(Dest.Route(place.location.lat, place.location.lon, place.name, mode = "WALK"))
}

/** The eat context of the screen, around [centre] (here by default); it follows the table and new answers. */
@Composable
fun rememberEatContext(centre: LatLon = rememberHere()): EatContext {
    val services = LocalServices.current
    val repo = remember(services) { EatRepository.of(services) }
    // A region downloaded meanwhile brings its place store: the area is asked again.
    val regions by services.regionsChanged.collectAsStateWithLifecycle()
    val areaFlow = remember(repo, centre, regions) { repo.area(centre, regions) }
    val area by areaFlow.collectAsStateWithLifecycle()
    // The network came back after the phone was offline: what the tiles or nothing gave is asked for again (once per
    // return, never in a loop after a failure; the first load decides for itself).
    val online by services.connectivity.online.collectAsStateWithLifecycle()
    LaunchedEffect(online) {
        val a = areaFlow.value
        if (online && !services.config.offline && !a.loading && a.origin != PlaceOrigin.STORE && a.origin != PlaceOrigin.ONLINE) repo.refresh(centre)
    }
    val table by repo.table.table.collectAsStateWithLifecycle()
    val ratingsFlow = remember(repo, area) { repo.ratings.of(area) }
    val ratings by ratingsFlow.collectAsStateWithLifecycle()
    val lang = remember { Locale.getDefault().language }
    val at = remember(table, services) { table.at(services.clock) }
    return remember(area, table, at, ratings) { EatContext(services, repo, area, table, at, ratings, lang) }
}

/** A value worked out off the main thread: the last answer ([value], null before the first) and whether it is current. */
@Immutable
data class Worked<T>(val value: T?, val fresh: Boolean)

/**
 * [compute] run off the main thread whenever one of [keys] changes: the last answer stays shown while the next is
 * worked out, marked not [Worked.fresh] (screens say "Looking…" then).
 */
@Composable
fun <T> rememberWorked(vararg keys: Any?, compute: () -> T): Worked<T> {
    val state = produceState(Worked<T>(null, false), *keys) {
        value = Worked(value.value, false)
        value = Worked(withContext(Dispatchers.Default) { compute() }, true)
    }
    return state.value
}
