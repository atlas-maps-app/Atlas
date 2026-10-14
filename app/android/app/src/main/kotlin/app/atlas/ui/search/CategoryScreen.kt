package app.atlas.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerStyle
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.Brands
import app.atlas.places.Categories
import app.atlas.places.Category
import app.atlas.places.OpeningHours
import app.atlas.places.Symbols
import app.atlas.services.NearbyPlaces
import app.atlas.services.PlaceOrigin
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Hairline
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.SymbolTile
import app.atlas.ui.kit.ToggleChip
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberNow
import kotlinx.coroutines.CancellationException
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.Locale

/** How far around you "every X on the map" looks, and how many it shows at most. */
internal const val CATEGORY_RADIUS_M = 2_000.0
internal const val CATEGORY_LIMIT = 300

/** The places of one category around, worked out for the list and the pins: each with its line, open first. */
@Immutable
internal data class CategoryPlaces(val lines: List<PlaceLine>, val origin: PlaceOrigin, val error: String?) {
    val open: Int get() = lines.count { it.tone == Tone.OPEN || it.tone == Tone.SOON }

    companion object {
        /** The lines of [found] seen from [here] at [now], with the hours read with the holidays of [services]' country. */
        fun of(services: Services, found: NearbyPlaces, here: LatLon, lang: String, now: LocalDateTime): CategoryPlaces {
            val ctx = HereContext.hours(services, here, ZonedDateTime.now(services.clock).zone)
            val lines = found.places.map { p ->
                PlaceLine.of(p, here, lang, now, open = OpeningHours.parseOrNull(p.tags["opening_hours"])?.status(now, ctx))
            }
            return CategoryPlaces(lines, found.source, found.error)
        }
    }
}

/**
 * Every place of a category around you on the map, as "Every pharmacy on the map · all 23" opens it: a pin for each
 * (in the category's colour), and the list in the sheet with the open ones in colour, nearest first; "Open now" keeps
 * only those. From the region on the phone, else the map's labels, then online with every tag.
 */
@Composable
fun CategoryScreen(args: Dest.Category, nav: Navigator) {
    val services = LocalServices.current
    val lang = remember { Locale.getDefault().language }
    val here = rememberHere()
    val category = remember(args.id) { Categories.byId(args.id) }
    if (category == null) {
        app.atlas.ui.kit.AtlasScreen(stringResource(R.string.category_title), onBack = { nav.back() }) {
            StateMessage("ui-help", args.id, text = stringResource(R.string.category_unknown))
        }
        return
    }
    val now = rememberNow()
    var found by remember(args.id, here) { mutableStateOf<CategoryPlaces?>(null) }
    var failed by remember(args.id, here) { mutableStateOf<String?>(null) }
    var openOnly by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(args.id, here) {
        try {
            services.places.nearby(args.id, here, CATEGORY_RADIUS_M, CATEGORY_LIMIT).collect { r ->
                val next = CategoryPlaces.of(services, r, here, lang, now)
                val prev = found
                // A failed online answer keeps what the phone had, with the reason.
                found = if (r.places.isEmpty() && prev != null && prev.lines.isNotEmpty()) prev.copy(error = r.error) else next
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = e.message ?: e.toString()
        }
        if (found == null && failed == null) found = CategoryPlaces(emptyList(), PlaceOrigin.TILES, null)
    }
    val shown = remember(found, openOnly) { found?.lines.orEmpty().filter { !openOnly || it.tone == Tone.OPEN || it.tone == Tone.SOON } }
    val colour = category.colour.toLong() and 0xFFFFFFFFL
    val hidden by services.saved.hiddenBrands.collectAsStateWithLifecycle()
    // A hidden brand keeps its row but loses its pin.
    val markers = remember(shown, hidden) {
        shown.filter { l -> hidden.isEmpty() || Brands.forTags(l.place.tags)?.id !in hidden }.mapIndexed { i, l ->
            Marker(l.place.key, l.place.location, Symbols.symbolFor(l.place.tags), colour, if (i < PINS) MarkerStyle.PIN else MarkerStyle.DOT, priority = 1_000 - i, place = l.place)
        }
    }
    val openPlace: (Place) -> Unit = { p -> services.remember(p); nav.go(Dest.Place.of(p)) }

    OnTheMap(
        here = here,
        markers = markers,
        fit = remember(shown) { shown.take(FIT).map { it.place.location } },
        onBack = { nav.back() },
        onPlace = openPlace,
        onLongPress = { at -> nav.go(Dest.WhatsHere(at.lat, at.lon)) },
        title = {
            SymbolTile(category.symbol, Color(category.colour), size = 36.dp)
            Text(category.name(lang), style = Atlas.type.name, color = Atlas.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    ) {
        CategorySheet(category, lang, found, failed, shown, openOnly, { openOnly = it }, now, openPlace)
    }
}

@Composable
private fun CategorySheet(
    category: Category,
    lang: String,
    found: CategoryPlaces?,
    failed: String?,
    shown: List<PlaceLine>,
    openOnly: Boolean,
    onOpenOnly: (Boolean) -> Unit,
    now: LocalDateTime,
    onPlace: (Place) -> Unit,
) {
    val c = Atlas.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(found?.lines?.size?.toString() ?: "–", style = Atlas.type.numeral, color = c.ink)
            Column(Modifier.weight(1f)) {
                Text(category.name(lang), style = Atlas.type.name, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                found?.let { f ->
                    Text(
                        pluralStringResource(R.plurals.category_open, f.open, f.open, shortRadius()),
                        style = Atlas.type.caption, color = if (f.open > 0) c.open else c.inkSoft,
                    )
                }
            }
            ToggleChip(stringResource(R.string.category_open_now), openOnly, onOpenOnly, symbol = "ui-clock")
        }
        found?.let { OriginLine(it.origin, it.error) }
    }
    when {
        found == null && failed == null -> Text(stringResource(R.string.kit_loading), Modifier.padding(20.dp), style = Atlas.type.caption, color = c.inkSoft)
        failed != null && found == null -> StateMessage("ui-alert-triangle", stringResource(R.string.search_failed), text = failed, tint = c.alert)
        shown.isEmpty() -> StateMessage(
            if (found?.error != null) "ui-cloud-off" else category.symbol,
            stringResource(if (openOnly) R.string.category_none_open else R.string.category_none),
            text = found?.error, tint = if (found?.error != null) c.alert else c.inkSoft,
        )
        else -> LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            itemsIndexed(shown, key = { _, l -> l.place.key }) { i, l ->
                PlaceResultRow(l, now, lang, onClick = { onPlace(l.place) })
                if (i < shown.lastIndex) Hairline()
            }
        }
    }
}

/** Where a list came from, said in one grey line: the region on the phone, the map's labels, or online. */
@Composable
internal fun OriginLine(origin: PlaceOrigin, error: String?) {
    val c = Atlas.colors
    val (symbol, text) = when (origin) {
        PlaceOrigin.STORE -> "ui-smartphone" to stringResource(R.string.origin_store)
        PlaceOrigin.TILES -> "ui-layers" to stringResource(R.string.origin_tiles)
        PlaceOrigin.ONLINE -> "ui-cloud" to stringResource(R.string.origin_online)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        app.atlas.ui.kit.Symbol(if (error != null) "ui-cloud-off" else symbol, tint = if (error != null) c.caution else c.inkFaint, size = 14.dp)
        Text(if (error != null) stringResource(R.string.origin_failed, text) else text, style = Atlas.type.caption, color = c.inkFaint, maxLines = 2)
    }
}

/** "2 km": the radius the lists look within. */
private fun shortRadius(): String = app.atlas.ui.kit.shortDistance(CATEGORY_RADIUS_M)

/** How many nearest places get a full pin (the rest are dots), and how many the camera fits. */
private const val PINS = 12
private const val FIT = 25
