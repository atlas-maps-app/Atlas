package app.atlas.ui.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.mapview.AtlasMap
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.MapOverlays
import app.atlas.mapview.MapState
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerStyle
import app.atlas.mapview.UserLocation
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.shortDistance

private val Ink = Color(0xFF15181C)
private val Soft = Color(0xFF464C5A)

/** The materials of the chooser, in the board's order. */
private val Materials = listOf(Stream.MIXED, Stream.PAPER, Stream.ORGANIC, Stream.BATTERIES, Stream.CLOTHES, Stream.ELECTRONICS, Stream.OIL, Stream.HAZARDOUS)

/**
 * Drop-off, as on the Drop board: the map around you with the places that take one material in that material's colour,
 * a chooser of the eight materials along the top, and a sheet listing the nearest with what else each takes and until
 * when, and the special waste site (a recycling centre) in amber at the end. Which material starts selected is the
 * route's `stream`; places and what they take come from OpenStreetMap's `recycling:*` tags.
 */
@Composable
fun DropScreen(stream: String?, nav: Navigator) {
    val services = LocalServices.current
    val repo = remember(services) { ShopRepository.of(services) }
    val here = rememberHere()
    val now = rememberLiveNow()
    var picked by rememberSaveable { mutableStateOf((Stream.of(stream) ?: Stream.BATTERIES).name) }
    val sel = Stream.valueOf(picked)
    val area by produceState(ShopArea.empty(here, DROP_RADIUS_M), here) { repo.area("bins", Stream.all, here, DROP_RADIUS_M, nameless = true).collect { value = it } }
    val all = remember(area, here) { BinsFinder.targets(area, here) }
    val mine = remember(all, sel) { all.filter { sel in it.streams } }
    val streetOnes = mine.filterNot { it.centre }
    val centres = remember(all) { all.filter { it.centre } }
    val tiles by services.tiles.collectAsState()
    val fix by services.location.last.collectAsStateWithLifecycle()
    val map = remember { MapState(CameraPosition(here, 16.4, 0.0, 45.0)) }
    val colour = Color(sel.colour)

    Box(Modifier.fillMaxSize().background(Color(0xFFEFE9DE))) {
        val markers = buildList {
            streetOnes.take(12).forEachIndexed { i, t ->
                add(Marker("d${t.key}", t.place.location, StreamArts.getValue(sel).symbol, sel.colour or 0xFF000000, if (i == 0) MarkerStyle.PIN else MarkerStyle.DOT, priority = 100 - i, selected = i == 0))
            }
            all.filter { Stream.RUBBISH in it.streams && sel != Stream.RUBBISH }.take(6).forEach { t ->
                add(Marker("t${t.key}", t.place.location, "bin.trash", Stream.RUBBISH.colour or 0xFF000000, MarkerStyle.DOT, priority = 10))
            }
        }
        AtlasMap(
            state = map, tiles = tiles, modifier = Modifier.fillMaxSize(),
            overlays = MapOverlays(markers = markers, location = (fix?.let { UserLocation(it.location, it.accuracy, it.bearing, it.speed) } ?: UserLocation(here, 10.0))),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 120.dp, bottom = 360.dp),
        )
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundSymbolButton("ui.back", stringResource(R.string.shop_bins_back), { nav.back() }, size = 44.dp, fill = Color.White.copy(alpha = 0.92f), tint = Ink, iconSize = 20.dp)
            Row(
                Modifier.weight(1f).height(52.dp)
                    .cssShadow(RoundedCornerShape(26.dp), y = 6.dp, blur = 18.dp, colour = Color(0x24181C30))
                    .background(Color.White.copy(alpha = 0.94f), RoundedCornerShape(26.dp)).padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                for (m in Materials) {
                    val on = m == sel
                    val label = stringResource(StreamArts.getValue(m).label)
                    Box(
                        Modifier.size(if (on) 40.dp else 36.dp, 40.dp).then(if (on) Modifier.background(Color(m.colour), CircleShape) else Modifier)
                            .tap(label) { picked = m.name }.semantics { selected = on },
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            m == Stream.BATTERIES -> Symbol("bin.battery", tint = if (on) Color.White else Color(m.colour), size = 18.dp)
                            on -> Box(Modifier.size(14.dp, 20.dp).background(Color.White, RoundedCornerShape(3.dp, 3.dp, 4.dp, 4.dp)))
                            m == Stream.HAZARDOUS -> Symbol("sign.warning", size = 22.dp)
                            else -> Box(Modifier.size(if (m == Stream.OIL) 18.dp else 20.dp, 26.dp).background(Color(m.colour), RoundedCornerShape(3.dp, 3.dp, 5.dp, 5.dp)))
                        }
                    }
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(350.dp)
                .cssShadow(RoundedCornerShape(30.dp, 30.dp, 0.dp, 0.dp), y = (-10).dp, blur = 30.dp, colour = Color(0x24181C30))
                .background(Color(0xFFFBFBF9), RoundedCornerShape(30.dp, 30.dp, 0.dp, 0.dp)).padding(start = 16.dp, end = 16.dp, top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).background(Color(0xFFD8D4CC), RoundedCornerShape(3.dp)))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(30.dp).background(colour, CircleShape), contentAlignment = Alignment.Center) {
                    if (sel == Stream.BATTERIES) Symbol("bin.battery", tint = Color.White, size = 14.dp) else Box(Modifier.size(10.dp, 14.dp).background(Color.White, RoundedCornerShape(2.dp)))
                }
                Text(stringResource(StreamArts.getValue(sel).label), style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp), color = Ink)
                if (!area.waiting) Text(stringResource(R.string.shop_drop_nearby, mine.size), style = TextStyle(fontFamily = SansFamily, fontSize = 13.sp, fontFeatureSettings = "tnum"), color = Color(0xFF676D7C))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    area.waiting -> Text(stringResource(R.string.shop_loading), style = TextStyle(fontFamily = SansFamily, fontSize = 14.sp), color = Soft)
                    area.places.isEmpty() && area.error != null -> StateMessage("ui-cloud-off", stringResource(R.string.shop_problem), text = area.error, tint = Color(0xFFD23C3C))
                    streetOnes.isEmpty() && centres.isEmpty() -> StateMessage("bin.recycling", stringResource(R.string.shop_drop_none), text = stringResource(R.string.shop_drop_none_text), tint = Soft)
                    else -> {
                        streetOnes.take(4).forEach { t -> DropRow(t, area, now, false) { open(nav, services, t) } }
                        centres.firstOrNull { it !in streetOnes }?.let { c -> DropRow(c, area, now, true) { open(nav, services, c) } }
                    }
                }
            }
        }
    }
}

private const val DROP_RADIUS_M = 1_500.0

private fun open(nav: Navigator, services: app.atlas.Services, t: BinTarget) {
    services.remember(t.place.place)
    nav.go(Dest.Place.of(t.place.place))
}

/** A drop-off: its name, little bins for what it takes and until when, and how far; amber for a special waste site. */
@Composable
private fun DropRow(t: BinTarget, area: ShopArea, now: java.time.LocalDateTime, special: Boolean, onClick: () -> Unit) {
    val bg = if (special) Color(0xFFFFF4D6) else Color.White
    Row(
        Modifier.fillMaxWidth().height(74.dp).clip(RoundedCornerShape(20.dp)).background(bg)
            .then(if (special) Modifier.border(1.5.dp, Color(0xFFF2B705), RoundedCornerShape(20.dp)) else Modifier).tap(t.place.name, onClick).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (special) Symbol("sign.warning", size = 30.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (special) stringResource(R.string.shop_drop_special) else t.place.name,
                style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (s in t.streams.filter { it != Stream.RUBBISH }.take(6)) {
                    Box(Modifier.size(12.dp, 16.dp).background(Color(s.colour), RoundedCornerShape(2.dp)))
                }
                val st = TextStyle(fontFamily = SansFamily, fontSize = 12.sp, fontFeatureSettings = "tnum")
                openLine(t.place, area, now)?.let { (text, _) -> Text(text, style = st, color = Soft, maxLines = 1) }
                val takes = t.streams.filter { it != Stream.RUBBISH }.take(3).map { stringResource(StreamArts.getValue(it).label).lowercase() }.joinToString(" · ")
                if (special) Text(takes, Modifier.weight(1f, fill = false), style = st, color = Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(shortDistance(t.distanceM), style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Black, fontSize = 17.sp, fontFeatureSettings = "tnum"), color = Ink)
    }
}
