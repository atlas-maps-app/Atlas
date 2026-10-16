package app.atlas.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import app.atlas.ui.kit.Chip
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
import app.atlas.ui.kit.OpenWords
import app.atlas.ui.kit.openLine
import app.atlas.ui.kit.TxLine
import app.atlas.ui.kit.txStyle
import androidx.compose.foundation.layout.PaddingValues
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
    val category = Categories.byId(results.categoryId)
    val colour = categoryColour(results.categoryId)
    val now = rememberNow(results)
    val summaries = remember(results, from) { results.places.map { PlaceSummary.of(it, from ?: results.centre, lang, now) } }
    Sheet(state, peekHeight = 168.dp) {
        // The Design board's sheet: its content 16 px in, the heading row in its place-row type.
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SymbolTile(category?.symbol ?: "maki-marker", colour)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TxLine(category?.name(lang) ?: results.categoryId, txStyle(20f, 800, 26f), INK)
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
                    TxLine(line, txStyle(13f, 400, 17f, tnum = true), SUB, lines = 2)
                    if (!results.loading && results.places.isNotEmpty()) {
                        Symbol(if (offlineSource) "ui.smartphone" else "weather.cloud", tint = Color(if (offlineSource) OFFLINE else ONLINE), size = 14.dp)
                    }
                }
            }
            if (results.places.isNotEmpty()) Chip(stringResource(R.string.home_results_all), onClick = onAll, outlined = true)
            SymbolButton("ui.close", stringResource(R.string.home_close_results), onClose, size = 40.dp, elevation = 0.dp, background = Color(0xFFF1EEE8), symbolSize = 18.dp)
        }
        if (!results.loading && results.places.isEmpty()) {
            StateMessage(
                symbol = if (results.error != null) "ui-cloud-off" else (category?.symbol ?: "maki-marker"),
                title = stringResource(R.string.home_results_none),
                tint = if (results.error != null) Color(0xFFB03A3A) else colour,
            )
        }
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(summaries, key = { it.place.key }) { s -> PlaceRowFor(s, colour, now, onPlace) }
        }
    }
}

/** The Design board's ink and grey, and the Main board's offline green and route blue. */
private val INK = 0xFF14161BL
private val SUB = 0xFF464C5AL
private const val OFFLINE = 0xFF178A4E
private const val ONLINE = 0xFF2E5BFF

@Composable
private fun PlaceRowFor(s: PlaceSummary, colour: Color, now: java.time.LocalDateTime, onPlace: (Place) -> Unit) {
    val open = openLine(s, now)
    val meta = listOfNotNull(open?.first, s.kind, s.distanceM?.let { shortDistance(it) }).joinToString(" · ")
    PlaceRow(
        name = s.name,
        symbol = s.symbol,
        colour = Color(s.colour).takeIf { s.colour != 0 } ?: colour,
        meta = meta.ifEmpty { null },
        metaColour = Color(
            when (OpenWords.of(s, now)) {
                OpenWords.Always, OpenWords.Open, is OpenWords.OpenUntil -> 0xFF12663A
                is OpenWords.ClosesIn -> 0xFF8A5A00
                else -> 0xFF5A5F6C
            },
        ),
        trailing = s.walk,
        onClick = { onPlace(s.place) },
    )
}
