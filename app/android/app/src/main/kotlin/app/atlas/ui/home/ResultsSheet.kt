package app.atlas.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.Categories
import app.atlas.services.PlaceOrigin
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Chip
import app.atlas.ui.kit.Hairline
import app.atlas.ui.kit.PlaceRow
import app.atlas.ui.kit.PlaceSummary
import app.atlas.ui.kit.Sheet
import app.atlas.ui.kit.SheetState
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.SymbolButton
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.SymbolTile
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.atlas.ui.kit.openLine
import app.atlas.ui.kit.rememberNow
import app.atlas.ui.kit.shortDistance

/** The places of the chosen category, nearest first, in a sheet over the map. */
@Composable
fun ResultsSheet(
    results: CategoryResults,
    state: SheetState,
    from: LatLon?,
    lang: String,
    onPlace: (Place) -> Unit,
    onAll: () -> Unit,
    onClose: () -> Unit,
) {
    val c = Atlas.colors
    val category = Categories.byId(results.categoryId)
    val colour = categoryColour(results.categoryId)
    val now = rememberNow(results)
    val summaries = remember(results, from) { results.places.map { PlaceSummary.of(it, from ?: results.centre, lang, now) } }
    Sheet(state, peekHeight = 168.dp) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SymbolTile(category?.symbol ?: "maki-marker", colour)
            Column(Modifier.weight(1f)) {
                Text(category?.name(lang) ?: results.categoryId, style = Atlas.type.headline)
                val offlineSource = results.source != PlaceOrigin.ONLINE
                val sourceNote = stringResource(if (offlineSource) R.string.home_results_offline else R.string.home_results_online)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = sourceNote },
                ) {
                    val line = when {
                        results.loading -> stringResource(R.string.kit_loading)
                        results.error != null && results.places.isEmpty() -> stringResource(R.string.home_results_error, results.error)
                        else -> stringResource(R.string.home_results_count, results.places.size)
                    }
                    Text(line, style = Atlas.type.caption, color = c.inkSoft, maxLines = 2)
                    if (!results.loading && results.places.isNotEmpty()) {
                        Symbol(if (offlineSource) "ui-smartphone" else "ui-cloud", tint = if (offlineSource) c.open else c.go, size = 14.dp)
                    }
                }
            }
            if (results.places.isNotEmpty()) Chip(stringResource(R.string.home_results_all), onClick = onAll)
            SymbolButton("ui-x", stringResource(R.string.home_close_results), onClose, size = 40.dp, elevation = 0.dp, background = c.cardMuted)
        }
        if (!results.loading && results.places.isEmpty()) {
            StateMessage(
                symbol = if (results.error != null) "ui-cloud-off" else (category?.symbol ?: "maki-marker"),
                title = stringResource(R.string.home_results_none),
                tint = if (results.error != null) c.alert else colour,
            )
        }
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            items(summaries, key = { it.place.key }) { s ->
                Box {
                    PlaceRowFor(s, colour, now, onPlace)
                }
                Hairline()
            }
        }
    }
}

@Composable
private fun PlaceRowFor(s: PlaceSummary, colour: Color, now: java.time.LocalDateTime, onPlace: (Place) -> Unit) {
    val open = openLine(s, now)
    val meta = listOfNotNull(open?.first, s.kind, s.distanceM?.let { shortDistance(it) }).joinToString(" · ")
    PlaceRow(
        name = s.name,
        symbol = s.symbol,
        colour = Color(s.colour).takeIf { s.colour != 0 } ?: colour,
        meta = meta.ifEmpty { null },
        metaColour = open?.second ?: Atlas.colors.inkSoft,
        trailing = s.walk,
        onClick = { onPlace(s.place) },
    )
}
