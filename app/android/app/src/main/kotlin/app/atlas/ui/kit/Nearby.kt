package app.atlas.ui.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.nav.LocalServices
import app.atlas.services.LayeredPlaceFinder
import app.atlas.services.NearbyPlaces
import app.atlas.services.PlaceOrigin
import java.util.Locale

/** Where "here" is for a screen: the last fix, else where the map last looked, else the Westerkerk. */
@Composable
fun rememberHere(): LatLon {
    val services = LocalServices.current
    return remember(services) {
        services.location.last.value?.location
            ?: services.startCameraNow()?.target
            ?: Services.WESTERKERK
    }
}

/**
 * The places of a category around [centre], nearest first, as list rows: what the phone has at once, then the online
 * answer; a loading line, an honest empty state, and the error when the network failed and nothing is known.
 */
@Composable
fun NearbyList(
    categoryId: String,
    centre: LatLon,
    onPlace: (Place) -> Unit,
    modifier: Modifier = Modifier,
    radiusM: Double = 1_000.0,
    limit: Int = 40,
    emptySymbol: String = "maki-marker",
) {
    val services = LocalServices.current
    var state by remember(categoryId, centre) { mutableStateOf<NearbyPlaces?>(null) }
    var failed by remember(categoryId, centre) { mutableStateOf<String?>(null) }
    LaunchedEffect(categoryId, centre) {
        runCatching {
            services.places.nearby(categoryId, centre, radiusM, limit).collect { r -> state = shownAfter(state, r, centre, limit) }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else failed = it.message ?: it.toString() }
        if (state == null && failed == null) state = NearbyPlaces(emptyList(), PlaceOrigin.PACK)
    }
    val lang = remember { Locale.getDefault().language }
    val now = rememberNow(state)
    val hours = rememberHoursAt(centre)
    val c = Atlas.colors
    Column(modifier.fillMaxWidth()) {
        val s = state
        when {
            s == null && failed == null -> Text(stringResource(R.string.kit_loading), style = Atlas.type.caption, color = c.inkSoft)
            s == null || s.places.isEmpty() -> StateMessage(
                symbol = if (s?.error != null || failed != null) "ui-cloud-off" else emptySymbol,
                title = s?.error ?: failed ?: stringResource(R.string.home_results_none),
                tint = if (s?.error != null || failed != null) c.alert else c.inkSoft,
            )
            else -> for (p in s.places) {
                val sum = PlaceSummary.of(p, centre, lang, now, hours)
                val open = openLine(sum, now)
                PlaceRow(
                    name = sum.name,
                    symbol = sum.symbol,
                    colour = Color(sum.colour),
                    meta = listOfNotNull(open?.first, sum.kind, sum.distanceM?.let { shortDistance(it) }).joinToString(" · ").ifEmpty { null },
                    metaColour = open?.second ?: Color(0xFF5A5F6C),
                    trailing = sum.walk,
                    onClick = { services.remember(p); onPlace(p) },
                )
                // The Design board's place rows are cards, 8 apart.
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * What [NearbyList] shows after the answer [r], having shown [shown] around [centre]: an answer given whole, by Overpass
 * or a region's store, as it is; a part from the packs, or a failure, with every place shown before it, nearest first,
 * at most [limit], and what went wrong (the app maintainer, 18 November 2026, batch 5 B13: a failed answer that held a
 * place was shown as it was, though it could hold fewer than what was shown: the windows read again after another
 * lookup had let some of them go, or the packs' part alone where the windows did not read). So what is shown never
 * shrinks but for an answer given whole; a failure that holds no place keeps what was shown, as before.
 */
internal fun shownAfter(shown: NearbyPlaces?, r: NearbyPlaces, centre: LatLon, limit: Int): NearbyPlaces {
    if (shown == null || shown.places.isEmpty() || r.error == null && r.source != PlaceOrigin.PACK) return r
    val keys = r.places.mapTo(HashSet()) { it.key }
    val kept = shown.places.filter { it.key !in keys }
    return when {
        kept.isEmpty() -> r
        r.places.isEmpty() -> shown.copy(error = r.error)
        else -> r.copy(places = LayeredPlaceFinder.nearest(r.places + kept, centre, limit))
    }
}
