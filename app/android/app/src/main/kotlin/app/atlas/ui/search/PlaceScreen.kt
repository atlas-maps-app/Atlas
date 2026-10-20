package app.atlas.ui.search

import android.content.Intent
import androidx.compose.ui.graphics.Color
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.LocalBackdrop
import app.atlas.ui.kit.SheetLook
import app.atlas.ui.kit.backdropSource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.atlas.R
import app.atlas.geo.CoordinateFormat
import app.atlas.mapview.StageMap
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.MapOverlays
import app.atlas.mapview.MapState
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerStyle
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.SheetStop
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberSheetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One place, as on the Place board: the map zoomed to it with its pin (and its level), share and save; the sheet with
 * its logo or symbol tile and serif name, its facts as symbols, the route button with walk minutes, call and web, the
 * 24-hour opening clock, its ratings, the quests nobody has answered, its raw tags, its Wikidata story and where all
 * of it comes from.
 */
@Composable
fun PlaceScreen(args: Dest.Place, nav: Navigator) {
    val services = LocalServices.current
    val lang = remember { Locale.getDefault().language }
    val vm = viewModel(key = "place:" + args.route) { PlaceViewModel(services, args.key, args.osmId, args.at, args.name, lang) }
    val state by vm.state.collectAsStateWithLifecycle()
    val place = state.place
    if (place == null) {
        AtlasScreen(args.name ?: "", onBack = { nav.back() }) {
            StateMessage(
                if (state.looking) "ui-search" else "ui-cloud-off",
                stringResource(if (state.looking) R.string.kit_loading else R.string.place_not_found),
                tint = if (state.looking) Atlas.colors.inkSoft else Atlas.colors.alert,
            )
        }
        return
    }
    PlaceView(place, state, vm, nav, lang)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceView(place: Place, state: PlaceState, vm: PlaceViewModel, nav: Navigator, lang: String) {
    val services = LocalServices.current
    val context = LocalContext.current
    val here = rememberHere()
    val sheet = rememberSheetState(SheetStop.HALF, setOf(SheetStop.HALF, SheetStop.FULL))
    val lists by services.saved.lists.collectAsStateWithLifecycle()
    val saved = remember(lists, place.key) { lists.any { l -> l.places.any { it.place.key == place.key } } }
    var saveTo by remember { mutableStateOf(false) }
    val (symbol, colour) = remember(place) { symbolOf(place) }
    val map = remember(place.key) { MapState(CameraPosition(place.location, HERO_ZOOM, 0.0, HERO_PITCH)) }
    val shareText = shareTextOf(place, lang)
    val shareLabel = stringResource(R.string.place_share)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val halfDp = maxHeight * SHEET_HALF
        StageMap(
            state = map,
            modifier = Modifier.fillMaxSize(),
            background = Color(SHEET_PAPER),
            hostModifier = Modifier.backdropSource(LocalBackdrop.current),
            overlays = MapOverlays(
                markers = listOf(Marker(place.key, place.location, symbol, colour.toLong() and 0xFFFFFFFFL, MarkerStyle.PIN, selected = true, priority = 1_000, place = place)),
                measure = false,
            ),
            contentPadding = PaddingValues(top = 80.dp, bottom = halfDp),
            onLongPress = { at -> nav.go(Dest.WhatsHere(at.lat, at.lon)) },
        )

        // Over the map, 16 px above the sheet: the level, as the board's dark glass pill (40 px, 12 px in).
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .offset { IntOffset(0, -sheet.visibleHeightPx.roundToInt()) }
                .padding(start = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            levelOf(place.tags)?.let { level ->
                val d = stringResource(R.string.place_level, level)
                Row(
                    Modifier.height(40.dp).boardBox(0xD114161B, round(20f), blur = 16f)
                        .semantics(mergeDescendants = true) { contentDescription = d }.padding(start = 10.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BoardSymbol("ui.layers", 17f, 0xFFFFFFFF)
                    BoardLine("L$level", boardText(13f, 600, 17f), 0xFFFFFFFF)
                }
            }
        }

        // Top: back, share and save, the board's 48 px glass discs at 44.
        val glass = listOf(CssShadow(0f, 6f, 20f, 0f, 0x24181C30))
        Row(
            Modifier.fillMaxWidth().padding(top = boardShift()).padding(start = 16.dp, end = 16.dp, top = 44.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BoardRoundButton("ui.back", stringResource(R.string.kit_back), { nav.back() }, side = 48f, glyph = 22f, bg = 0xDBFFFFFF, shadows = glass, blur = 20f)
            Spacer(Modifier.weight(1f))
            BoardRoundButton("ui.share", shareLabel, {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                runCatching { context.startActivity(Intent.createChooser(send, shareLabel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, side = 48f, bg = 0xDBFFFFFF, shadows = glass, blur = 20f)
            val saveLabel = stringResource(if (saved) R.string.place_saved else R.string.place_save)
            Box(
                Modifier.size(48.dp).boardBox(if (saved) 0xFF14161B else 0xDBFFFFFF, round(24f), glass, blur = if (saved) 0f else 20f)
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = saveLabel,
                        onLongClick = { saveTo = true },
                        onClick = { services.scope.launch(Dispatchers.IO) { runCatching { services.saved.toggle(place) } } },
                    )
                    .semantics { contentDescription = saveLabel },
                contentAlignment = Alignment.Center,
            ) {
                BoardSymbol("ui.bookmark", 20f, if (saved) 0xFFFFFFFF else 0xFF14161B)
            }
        }

        app.atlas.ui.kit.Sheet(sheet, peekHeight = 200.dp, halfFraction = SHEET_HALF, fullTopGap = 96.dp, look = PLACE_SHEET) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PlaceSheetContent(place, state, vm, nav, lang, here)
                Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(24.dp))
            }
        }
    }
    if (saveTo) {
        SaveToDialog(
            lists = lists,
            holding = remember(lists, place.key) { services.saved.listsHolding(place.key) },
            onToggle = { id -> services.scope.launch(Dispatchers.IO) { runCatching { services.saved.toggle(place, id) } } },
            onNew = { name -> services.scope.launch(Dispatchers.IO) { runCatching { services.saved.save(place, services.saved.createList(name)) } } },
            onDismiss = { saveTo = false },
        )
    }
}

/** What sharing a place hands on: its name and address, a geo: link and its OpenStreetMap link. */
@Composable
private fun shareTextOf(place: Place, lang: String): String = remember(place, lang) {
    listOfNotNull(
        app.atlas.places.Localise.name(place.tags, lang),
        addressOf(place),
        CoordinateFormat.geoUri(place.location) + place.name()?.let { "?q=" + java.net.URLEncoder.encode("${CoordinateFormat.decimal(place.location).replace(" ", "")}($it)", Charsets.UTF_8) }.orEmpty(),
        place.id?.url,
    ).joinToString("\n")
}

private const val HERO_ZOOM = 17.6
private const val HERO_PITCH = 48.0
/** The share of the screen the Place board's sheet takes: from 440 to the foot of 915 px. */
private const val SHEET_HALF = 475f / 915f

/** The Place board's sheet paper. */
internal const val SHEET_PAPER = 0xFFFBFAF7

/** The Place board's sheet: its paper, corners 30, its shadow 0 −10 30 of the night ink at 12 %, a #D8D4CC grabber. */
private val PLACE_SHEET = SheetLook(
    Modifier.boardBox(SHEET_PAPER, CssCorners(30f, 30f, 0f, 0f), listOf(CssShadow(0f, -10f, 30f, 0f, 0x1F181C30))),
    Color(0xFFD8D4CC),
)
