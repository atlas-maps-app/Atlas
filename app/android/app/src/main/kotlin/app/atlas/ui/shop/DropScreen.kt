package app.atlas.ui.shop

import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.argb
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
import androidx.compose.foundation.layout.Spacer
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
import app.atlas.mapview.StageMap
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

    Box(Modifier.fillMaxSize()) {
        val markers = buildList {
            streetOnes.take(12).forEachIndexed { i, t ->
                add(Marker("d${t.key}", t.place.location, StreamArts.getValue(sel).symbol, sel.colour or 0xFF000000, if (i == 0) MarkerStyle.PIN else MarkerStyle.DOT, priority = 100 - i, selected = i == 0))
            }
            all.filter { Stream.RUBBISH in it.streams && sel != Stream.RUBBISH }.take(6).forEach { t ->
                add(Marker("t${t.key}", t.place.location, "bin.trash", Stream.RUBBISH.colour or 0xFF000000, MarkerStyle.DOT, priority = 10))
            }
        }
        StageMap(
            state = map, tiles = tiles, modifier = Modifier.fillMaxSize(), background = Color(0xFFEFE9DE),
            overlays = MapOverlays(markers = markers, location = (fix?.let { UserLocation(it.location, it.accuracy, it.bearing, it.speed) } ?: UserLocation(here, 10.0))),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 120.dp, bottom = 360.dp),
        )
        // The board's top row at y 42: back (44, 92 % white) and the chooser of materials (52 high, 6 px in, spread out).
        Row(
            Modifier.fillMaxWidth().padding(top = boardTop()).padding(start = 16.dp, end = 16.dp, top = 42.dp).height(52.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).boardBox(0xEBFFFFFF, round(22f)).tap(stringResource(R.string.shop_bins_back)) { nav.back() }, contentAlignment = Alignment.Center) {
                Sym("ui.back", 20f, 20f, INK_D)
            }
            Row(
                Modifier.weight(1f).height(52.dp).boardBox(0xF0FFFFFF, round(26f), listOf(CssShadow(0f, 6f, 18f, 0f, 0x24181C30)), clip = false).padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                for (m in Materials) {
                    val on = m == sel
                    val label = stringResource(StreamArts.getValue(m).label)
                    Box(
                        Modifier.size(if (on) 40.dp else 36.dp, 40.dp).then(if (on) Modifier.boardBox(m.colour, round(20f)) else Modifier)
                            .tap(label) { picked = m.name }.semantics { selected = on },
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            m == Stream.BATTERIES -> if (on) Sym("drop.battery.2", 18f, 20f, 0xFFFFFFFF) else Sym("drop.battery", 12f, 16f, m.colour)
                            on -> Box(Modifier.size(14.dp, 20.dp).boardBox(0xFFFFFFFF, CssCorners(3f, 3f, 4f, 4f)))
                            m == Stream.HAZARDOUS -> Sym("bin.hazardous", 22f, 22f, 0xFF000000)
                            else -> Box(Modifier.size(if (m == Stream.OIL) 18.dp else 20.dp, 26.dp).boardBox(m.colour, Swatch.getValue(m)))
                        }
                    }
                }
            }
        }
        // The sheet: the bottom 350 px, 30 px top corners, its grabber, the material's line and the nearest drop-offs.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(350.dp + boardLift())
                .boardBox(0xFFFBFBF9, CssCorners(30f, 30f, 0f, 0f), listOf(CssShadow(0f, -10f, 30f, 0f, 0x24181C30)), clip = false)
                .padding(start = 16.dp, end = 16.dp, top = 10.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).boardBox(0xFFD8D4CC, round(3f)))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(30.dp).boardBox(sel.colour, round(15f)), contentAlignment = Alignment.Center) {
                    if (sel == Stream.BATTERIES) Sym("drop.battery", 12f, 16f, 0xFFFFFFFF) else Box(Modifier.size(10.dp, 14.dp).boardBox(0xFFFFFFFF, round(2f)))
                }
                Line(stringResource(StreamArts.getValue(sel).label), bt(18f, 800, 23.5f), INK_D)
                if (!area.waiting) Line(stringResource(R.string.shop_drop_nearby, mine.size), bt(13f, 400, 17f, tnum = true), 0xFF676D7C)
            }
            Spacer(Modifier.height(10.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = boardLift()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    area.waiting -> Line(stringResource(R.string.shop_loading), bt(13f, 400, 17f), SOFT)
                    area.places.isEmpty() && area.error != null -> StateMessage("ui-cloud-off", stringResource(R.string.shop_problem), text = area.error, tint = Color(0xFFD23C3C))
                    streetOnes.isEmpty() && centres.isEmpty() -> StateMessage("bin.recycling", stringResource(R.string.shop_drop_none), text = stringResource(R.string.shop_drop_none_text), tint = argb(SOFT))
                    else -> {
                        streetOnes.take(4).forEach { t -> DropRow(t, area, now, false) { open(nav, services, t) } }
                        centres.firstOrNull { it !in streetOnes }?.let { c -> DropRow(c, area, now, true) { open(nav, services, c) } }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private const val INK_D: Long = 0xFF15181C
private const val SOFT: Long = 0xFF464C5A

/** The chooser's little bins: rounded at the foot, square for clothes and electronics, a can for oil. */
private val Swatch: Map<Stream, CssCorners> = Stream.entries.associateWith {
    when (it) {
        Stream.CLOTHES, Stream.ELECTRONICS -> round(3f)
        Stream.OIL -> CssCorners(9f, 9f, 4f, 4f)
        else -> CssCorners(3f, 3f, 5f, 5f)
    }
}

private const val DROP_RADIUS_M = 1_500.0

private fun open(nav: Navigator, services: app.atlas.Services, t: BinTarget) {
    services.remember(t.place.place)
    nav.go(Dest.Place.of(t.place.place))
}

/** A drop-off (74 high, 20 px corners): its name, little bins for what it takes and until when, and how far; amber for a special waste site. */
@Composable
private fun DropRow(t: BinTarget, area: ShopArea, now: java.time.LocalDateTime, special: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(74.dp)
            .boardBox(
                if (special) 0xFFFFF6DC else 0xFFFFFFFF, round(20f),
                listOf(if (special) CssShadow(0f, 0f, 0f, 1.5f, 0xFFF2D27A, inset = true) else CssShadow(0f, 2f, 10f, 0f, 0x0F181C30)),
            )
            .tap(t.place.name, onClick).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (special) Sym("sign.warning", 30f, 30f, INK_D)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (special) 3.dp else 6.dp)) {
            Line(if (special) stringResource(R.string.shop_drop_special) else t.place.name, if (special) bt(15f, 800, 19.5f) else bt(16f, 800, 20.5f), INK_D)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val st = bt(12f, 400, 15.5f, tnum = true)
                if (!special) for (s in t.streams.filter { it != Stream.RUBBISH }.take(6)) Box(Modifier.size(12.dp, 16.dp).boardBox(s.colour, round(2f)))
                val time = openLine(t.place, area, now)?.first
                if (special) {
                    val takes = t.streams.filter { it != Stream.RUBBISH }.take(3).map { stringResource(StreamArts.getValue(it).label).lowercase() }
                    Line(listOfNotNull(time).plus(takes).joinToString(" · "), st, 0xFF5A4A20, Modifier.weight(1f, fill = false))
                } else if (time != null) Line(time, st, SOFT)
            }
        }
        Line(shortDistance(t.distanceM), bt(17f, 900, 22f, tnum = true), INK_D)
    }
}
