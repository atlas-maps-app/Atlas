package app.atlas.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.Localise
import app.atlas.places.OpeningHours
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.Chip
import app.atlas.ui.kit.ClockDial
import app.atlas.ui.kit.PlaceSummary
import app.atlas.ui.kit.PrimaryButton
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.SymbolTile
import app.atlas.ui.kit.openLine
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * One place: its symbol and serif name, the route button with walking minutes, the 24-hour opening clock, the address
 * and its tags. Placeholder for the search package's full Place sheet.
 */
@Composable
fun PlaceScreen(args: Dest.Place, nav: Navigator) {
    val services = LocalServices.current
    val lang = remember { Locale.getDefault().language }
    var place by remember(args) { mutableStateOf(args.key?.let { services.placeCache[it] }) }
    var looking by remember(args) { mutableStateOf(place == null && args.osmId != null) }
    LaunchedEffect(args) {
        if (place != null || args.osmId == null) return@LaunchedEffect
        val id = OsmId.parse(args.osmId)
        // The downloaded regions first (offline, every tag), then Overpass when online.
        if (id != null) place = withContext(Dispatchers.IO) { runCatching { services.placeStores.get(id)?.place }.getOrNull() }
        if (place == null && id != null && !services.config.offline && services.connectivity.online.value) {
            place = withContext(Dispatchers.IO) {
                runCatching { services.overpass.places("[out:json][timeout:25];${id.type.name.lowercase()}(${id.id});out tags center;").firstOrNull() }.getOrNull()
            }?.let { if (it.id == null) it.copy(id = id) else it }
        }
        looking = false
    }
    val here = rememberHere()
    val now = rememberNow()
    val shown: Place? = place ?: args.at?.let { Place(args.osmId?.let(OsmId::parse), it, args.name?.let { n -> mapOf("name" to n) } ?: emptyMap()) }
    val summary = shown?.let { PlaceSummary.of(it, here, lang, now) }
    AtlasScreen(summary?.name ?: args.name ?: "", onBack = { nav.back() }) {
        if (summary == null) {
            StateMessage(if (looking) "ui-search" else "ui-cloud-off", stringResource(if (looking) R.string.place_loading else R.string.place_not_found))
            return@AtlasScreen
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SymbolTile(summary.symbol, Color(summary.colour), size = 56.dp)
            Column(Modifier.weight(1f)) {
                summary.kind?.let { Text(it, style = Atlas.type.bodyStrong) }
                Localise.address(summary.place.tags)?.let { Text(it, style = Atlas.type.caption, color = Atlas.colors.inkSoft) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                summary.walk ?: stringResource(R.string.place_route),
                symbol = "ui-corner-up-right",
                onClick = { nav.go(Dest.Route(summary.place.location.lat, summary.place.location.lon, summary.name)) },
            )
            summary.place.id?.let { Chip(stringResource(R.string.place_osm), symbol = "ui-map", outlined = true) }
        }
        val hours = remember(summary.place) { OpeningHours.parseOrNull(summary.place.tags["opening_hours"]) }
        if (hours != null) {
            Card {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val open = openLine(summary, now)
                    ClockDial(hours.spans(now.toLocalDate()), now.toLocalTime(), size = 120.dp, contentDescription = open?.first)
                    open?.let { (text, colour) -> Text(text, style = Atlas.type.name, color = colour) }
                }
            }
        }
        if (summary.place.tags.isNotEmpty()) Chip("{ } " + stringResource(R.string.place_tags, summary.place.tags.size), outlined = true)
    }
}
