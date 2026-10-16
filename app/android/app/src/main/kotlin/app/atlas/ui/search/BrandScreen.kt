package app.atlas.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerStyle
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.Brands
import app.atlas.places.OpeningHours
import app.atlas.places.Symbols
import app.atlas.services.PlaceOrigin
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.TxLine
import app.atlas.ui.kit.txStyle
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/** How far around you a brand's branches are looked for. */
internal const val BRAND_RADIUS_M = 3_000.0

/**
 * Every branch of one brand on the map, "All 8 on the map": its logo as each pin, and the sheet with the logo, "8
 * branches · 6 open", the bell and hide, and the branches by walk time with "→ 21:00" or "opens 10:00".
 */
@Composable
fun BrandScreen(args: Dest.Brand, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    val brand = remember(args.id) { Brands.byId(args.id) }
    if (brand == null) {
        AtlasScreen(args.id, onBack = { nav.back() }) { StateMessage("ui-store", stringResource(R.string.brand_unknown), text = args.id) }
        return
    }
    val found by produceState<Pair<List<Place>, PlaceOrigin?>?>(null, args.id, here) {
        value = BrandsAround.branches(services, args.id, here, BRAND_RADIUS_M)
    }
    val hidden by services.saved.hiddenBrands.collectAsStateWithLifecycle()
    val alerts by remember { services.settings.flow(BRAND_ALERTS, emptySet()) }.collectAsStateWithLifecycle(emptySet())
    val branches = found?.first.orEmpty()
    val now = rememberNow(found)
    val ctx = remember(here) { HereContext.hours(services, here, ZonedDateTime.now(services.clock).zone) }
    val open = remember(branches, now) { branches.count { OpeningHours.parseOrNull(it.tags["opening_hours"])?.isOpen(now, ctx) == true } }
    val markers = remember(branches) {
        branches.mapIndexed { i, p ->
            Marker(p.key, p.location, Symbols.symbolFor(p.tags), (brand.colour ?: 0xFF14161B.toInt()).toLong() and 0xFFFFFFFFL, MarkerStyle.BRAND, logo = brand.id, priority = 1_000 - i, place = p)
        }
    }
    val openPlace: (Place) -> Unit = { p -> services.remember(p); nav.go(Dest.Place.of(p)) }
    val isHidden = brand.id in hidden
    val bell = brand.id in alerts

    OnTheMap(
        here = here,
        markers = markers,
        fit = remember(branches) { branches.take(12).map { it.location } },
        onBack = { nav.back() },
        onPlace = openPlace,
        onLongPress = { at -> nav.go(Dest.WhatsHere(at.lat, at.lon)) },
        title = {
            BrandLogo(brand, 36.dp, corner = 10.dp)
            TxLine(brandName(brand), txStyle(16f, 700, 20.5f), 0xFF14161B)
        },
    ) {
        // The Brands board's sheet head and rows, 18 px in.
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BrandHead(
                brand,
                if (found == null) stringResource(R.string.kit_loading)
                else pluralStringResource(R.plurals.brand_branches_open, branches.size, branches.size, open),
                bell, isHidden,
                onBell = {
                    services.scope.launch { services.settings.edit { p -> val s = p[BRAND_ALERTS].orEmpty(); p[BRAND_ALERTS] = if (bell) s - brand.id else s + brand.id } }
                },
                onHide = { services.scope.launch(Dispatchers.IO) { runCatching { services.saved.hideBrand(brand.id, !isHidden) } } },
            )
            found?.second?.let { OriginLine(it, null) }
        }
        when {
            found == null -> Unit
            branches.isEmpty() -> StateMessage("ui-store", stringResource(R.string.brands_none))
            else -> LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 24.dp)) {
                itemsIndexed(branches, key = { _, p -> p.key }) { i, p ->
                    BranchRow(p, here, now, last = i == branches.lastIndex) { openPlace(p) }
                }
            }
        }
    }
}
