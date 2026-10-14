package app.atlas.ui.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
            services.places.nearby(categoryId, centre, radiusM, limit).collect { r ->
                val prev = state
                state = if (r.error != null && prev != null && prev.places.isNotEmpty()) prev.copy(error = r.error) else r
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else failed = it.message ?: it.toString() }
        if (state == null && failed == null) state = NearbyPlaces(emptyList(), PlaceOrigin.TILES)
    }
    val lang = remember { Locale.getDefault().language }
    val now = rememberNow(state)
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
                val sum = PlaceSummary.of(p, centre, lang, now)
                val open = openLine(sum, now)
                PlaceRow(
                    name = sum.name,
                    symbol = sum.symbol,
                    colour = Color(sum.colour),
                    meta = listOfNotNull(open?.first, sum.kind, sum.distanceM?.let { shortDistance(it) }).joinToString(" · ").ifEmpty { null },
                    metaColour = open?.second ?: c.inkSoft,
                    trailing = sum.walk,
                    onClick = { services.remember(p); onPlace(p) },
                )
                Hairline()
            }
        }
    }
}
