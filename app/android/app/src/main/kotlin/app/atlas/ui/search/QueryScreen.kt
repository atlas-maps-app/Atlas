package app.atlas.ui.search

import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.drawBehind
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.LocalBackdrop
import app.atlas.ui.kit.SheetLook
import app.atlas.ui.kit.backdropSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.atlas.R
import app.atlas.geo.BBox
import app.atlas.mapview.AreaOverlay
import app.atlas.mapview.StageMap
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.MapHit
import app.atlas.mapview.MapOverlays
import app.atlas.mapview.MapState
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerStyle
import app.atlas.mapview.UserLocation
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.search.Chip
import app.atlas.search.ChipKind
import app.atlas.search.Hit
import app.atlas.search.ParsedQuery
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Kicker
import app.atlas.ui.kit.PrimaryButton
import app.atlas.ui.kit.Sheet
import app.atlas.ui.kit.SheetStop
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberSheetState
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/** The colours of the ↳ rows, in order: blue, green, purple, amber (as the Query board's circles and chips). */
internal val CLAUSE_COLOURS = longArrayOf(0xFF2E5BFF, 0xFF178A4E, 0xFF7B4DFF, 0xFFE29A12)

/**
 * A ↳ row's chip colours as the Query board pairs them: the strong colour (the subject's symbol), the ink of the
 * distance, the pale fill of the distance chip and the ring of the subject chip.
 */
private class ClauseLook(val strong: Long, val ink: Long, val fill: Long, val ring: Long)

private val CLAUSE_LOOKS = listOf(
    ClauseLook(0xFF2E5BFF, 0xFF1F3FC7, 0xFFE4EAFF, 0xFFC9D3FF),
    ClauseLook(0xFF178A4E, 0xFF12663A, 0xFFDDF1E5, 0xFFBFE3CD),
    ClauseLook(0xFF7B4DFF, 0xFF5A32D6, 0xFFECE6FF, 0xFFD6CCFF),
    ClauseLook(0xFFE29A12, 0xFF8A5A00, 0xFFFFF7E0, 0xFFF3E2B0),
)

private fun clauseLook(i: Int) = CLAUSE_LOOKS[i % CLAUSE_LOOKS.size]

/** The Query board's ink, greys, warm buttons and the sheet's paper. */
private const val INK = 0xFF14161B
private const val GREY = 0xFF464C5A
private const val WARM = 0xFFF1EEE8
private const val SHEET = 0xFFFBFAF7

/** The placeholder flag "alert me" sets: queries to watch, kept in settings. */
internal val ALERTS = stringSetPreferencesKey("search.alerts")

/** The Query board's sheet: #FBFAF7, corners 30, its shadow 0 −10 30 of the night ink at 14 %, a #D8D4CC grabber. */
private val QUERY_SHEET = SheetLook(
    Modifier.boardBox(SHEET, CssCorners(30f, 30f, 0f, 0f), listOf(CssShadow(0f, -10f, 30f, 0f, 0x24181C30))),
    Color(0xFFD8D4CC),
)

/** The share of the screen the Query board's sheet takes: 290 of 915 px. */
private const val SHEET_HALF = 290f / 915f

/**
 * The query builder, "A near B", as on the Query board: the sentence read into chips (what, and ↳ near what, within a
 * distance you can dial) in a glass card at the top, the map with the circles around the "B"s and the ranked "A" pins,
 * and the sheet with how many there are, how fast the phone found them, save as a layer, alert me, download, and `{} QL`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QueryScreen(args: Dest.Query, nav: Navigator) {
    val services = LocalServices.current
    val lang = remember { Locale.getDefault().language }
    val here = rememberHere()
    val vm = viewModel(key = "query") { QueryViewModel(services, args.text, { here }, lang) }
    val state by vm.state.collectAsStateWithLifecycle()
    var field by rememberSaveable { mutableStateOf(args.text) }
    var dial by remember { mutableStateOf<QueryEdit.NearRow?>(null) }
    var ql by remember { mutableStateOf(false) }
    var add by remember { mutableStateOf(false) }
    val tiles by services.tiles.collectAsStateWithLifecycle()
    val layers by services.saved.layers.collectAsStateWithLifecycle()
    val alerts by remember { services.settings.flow(ALERTS, emptySet()) }.collectAsStateWithLifecycle(emptySet())
    val map = remember { MapState(CameraPosition(here, 16.0)) }
    val sheet = rememberSheetState(SheetStop.HALF, setOf(SheetStop.PEEK, SheetStop.HALF, SheetStop.FULL))
    val parsed = state.parsed
    val hits = state.result?.hits.orEmpty()

    val markers = remember(hits, state.circles) { markersOf(hits, state.circles) }
    val areas = remember(state.circles) {
        state.circles.map { n ->
            val col = CLAUSE_COLOURS[n.clause % CLAUSE_COLOURS.size]
            AreaOverlay(n.key, n.at, n.radius, fill = (col and 0x00FFFFFF) or 0x30000000, stroke = col)
        }
    }
    LaunchedEffect(hits) {
        snapshotFlow { map.visibleBounds }.first { it != null }
        val pts = hits.take(20).map { it.record.location } + state.circles.map { it.at }
        BBox.of(pts)?.let { b -> runCatching { map.fit(b.buffer(80.0), 40) } }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        StageMap(
            state = map,
            tiles = tiles,
            modifier = Modifier.fillMaxSize(),
            background = Color(0xFFEFE5DA),
            hostModifier = Modifier.backdropSource(LocalBackdrop.current),
            overlays = MapOverlays(markers = markers, areas = areas, location = UserLocation(here, 15.0)),
            contentPadding = PaddingValues(top = 262.dp, bottom = maxHeight * SHEET_HALF),
            onTap = { hit ->
                val p = when (hit) {
                    is MapHit.OnMarker -> hit.marker.place
                    is MapHit.OnFeature -> hit.place
                    is MapHit.Empty -> null
                }
                p?.let { services.remember(it); nav.go(Dest.Place.of(it)) }
            },
            onLongPress = { at -> nav.go(Dest.WhatsHere(at.lat, at.lon)) },
        )

        // The sentence and its chips, in the board's glass card over the map: 12 px from the sides at 44, 12 px in.
        Column(
            Modifier
                .padding(top = boardShift())
                .padding(start = 12.dp, end = 12.dp, top = 44.dp)
                .fillMaxWidth()
                .boardBox(0xEBFFFFFF, round(28f), listOf(CssShadow(0f, 10f, 30f, 0f, 0x29181C30)), blur = 24f, saturate = 1.5f)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BoardRoundButton("ui.back", stringResource(R.string.kit_back), { nav.back() }, side = 40f, bg = WARM, tint = INK)
                BoardSymbol("ui.sparkles", 18f, 0xFF7B4DFF)
                BasicTextField(
                    field, { field = it; vm.onText(it) }, Modifier.weight(1f),
                    textStyle = boardText(15f, 500, 19.5f).copy(color = Color(INK)), cursorBrush = SolidColor(Color(INK)), maxLines = 2,
                    decorationBox = { inner ->
                        if (field.isEmpty()) BoardLine(stringResource(R.string.query_hint), boardText(15f, 500, 19.5f), 0xFF9AA0AC)
                        inner()
                    },
                )
            }
            if (parsed != null && parsed.steps.isNotEmpty()) {
                ChipBoard(
                    parsed, state, lang,
                    onRemove = { chip -> QueryEdit.without(field, chip)?.let { t -> field = t; vm.onText(t) } },
                    onAdd = { add = true },
                    onDial = { dial = it },
                    onQl = { ql = true },
                )
            }
        }

        Sheet(sheet, peekHeight = 120.dp, halfFraction = SHEET_HALF, fullTopGap = 150.dp, look = QUERY_SHEET) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp).height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val total = state.result?.total
                BoardLine(total?.toString() ?: "–", boardText(28f, 700, 28f, tnum = true), INK)
                parsed?.let { QueryEdit.mainChips(it).firstOrNull { ch -> ch.kind == ChipKind.KIND } }?.let { BoardSymbol(it.symbol, 18f, GREY) }
                state.result?.let { r ->
                    val secs = String.format(Locale.ROOT, "%.2f s", r.micros / 1_000_000.0)
                    val where = stringResource(if (state.online) R.string.query_online else R.string.query_on_phone)
                    Row(
                        Modifier.height(24.dp).boardBox(0xFFE7F4EC, round(12f)).padding(horizontal = 8.dp)
                            .semantics(mergeDescendants = true) { contentDescription = "$where $secs" },
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BoardSymbol(if (state.online) "ui-cloud" else "ui.offline", 12f, 0xFF12663A)
                        BoardLine(secs, boardText(12f, 600, 15.5f), 0xFF12663A)
                    }
                }
                Spacer(Modifier.weight(1f))
                val text = state.text.trim()
                val kept = layers.any { it.text == text }
                val alerting = text in alerts
                BoardRoundButton("ui.layer-add", stringResource(if (kept) R.string.query_layer_kept else R.string.query_layer), {
                    if (text.isNotEmpty()) services.scope.launch(Dispatchers.IO) { runCatching { if (kept) services.saved.removeLayer(text) else services.saved.keepLayer(text, alerting) } }
                }, glyph = 19f, bg = if (kept) INK else WARM, tint = if (kept) 0xFFFFFFFF else INK)
                BoardRoundButton("ui.bell", stringResource(if (alerting) R.string.query_alert_on else R.string.query_alert), {
                    if (text.isNotEmpty()) services.scope.launch {
                        services.settings.edit { p -> val now = p[ALERTS].orEmpty(); p[ALERTS] = if (alerting) now - text else now + text }
                    }
                }, glyph = 19f, bg = if (alerting) INK else WARM, tint = if (alerting) 0xFFFFFFFF else INK)
                BoardRoundButton("ui.download", stringResource(R.string.query_download), {
                    val b = BBox.of(hits.map { it.record.location })
                    val centre = b?.center ?: here
                    nav.go(Dest.RegionPicker(centre.lat, centre.lon))
                }, glyph = 19f, bg = WARM, tint = INK)
            }
            QueryResults(state, parsed, here, lang, vm, onPlace = { p -> services.remember(p); nav.go(Dest.Place.of(p)) })
        }
    }

    dial?.let { row ->
        DistanceDialog(
            subject = parsed?.let { QueryEdit.subjectOf(it, row.row) },
            metres = state.distances[row.row] ?: row.metres,
            onSet = { vm.setDistance(row.row, it) },
            onDismiss = { dial = null },
        )
    }
    if (ql) QlDialog(vm.ql().orEmpty(), vm.online(), onRun = { vm.runOnline() }, onDismiss = { ql = false })
    if (add) AddDialog(onAdd = { words -> field = (field.trim() + " " + words).trim(); vm.onText(field) }, onDismiss = { add = false })
}

/** The pins: each answer (the best one selected), and the far-side features in their clause's colour. */
private fun markersOf(hits: List<Hit>, circles: List<NearCircle>): List<Marker> = buildList {
    for (n in circles) {
        // What the answers are near: above all but the best three answers, so the circles show their centres.
        add(Marker("b:" + n.key, n.at, n.symbol, CLAUSE_COLOURS[n.clause % CLAUSE_COLOURS.size], MarkerStyle.DOT, priority = 950))
    }
    hits.forEachIndexed { i, h ->
        add(
            Marker(
                h.place.key, h.record.location, h.symbol, 0xFF14161B, MarkerStyle.PIN,
                value = if (i == 0) "1" else null, selected = i == 0, priority = if (i < 3) 1_000 - i else 900 - i, place = h.place,
            ),
        )
    }
}

/**
 * The chips of the query as the board's chip board (#F6F4EF, corners 20, 10 px in, rows 8 px apart, chips 36 px and
 * 6 px apart): the main row, then a ↳ row per "near" with its distance dial and what it is near, and `{} QL` at the end
 * of the last row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipBoard(
    parsed: ParsedQuery,
    state: QueryState,
    lang: String,
    onRemove: (Chip) -> Unit,
    onAdd: () -> Unit,
    onDial: (QueryEdit.NearRow) -> Unit,
    onQl: () -> Unit,
) {
    val rows = remember(parsed) { QueryEdit.rows(parsed) }
    Column(
        Modifier.fillMaxWidth().boardBox(0xFFF6F4EF, round(20f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                QueryEdit.mainChips(parsed).forEachIndexed { i, chip -> TokenChip(chip, dark = i == 0 && chip.kind == ChipKind.KIND, onClick = { onRemove(chip) }) }
                val addLabel = stringResource(R.string.query_add)
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onAdd).semantics { contentDescription = addLabel }
                        .drawBehind { dashedCircle(Color(0xFFB5B0A5), density) },
                    contentAlignment = Alignment.Center,
                ) { BoardSymbol("ui.plus", 16f, GREY) }
            }
            if (rows.isEmpty()) QlPill(onQl)
        }
        rows.forEachIndexed { i, r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) { BoardSymbol("ui.subitem", 14f, 0xFF9AA0AC) }
                val metres = state.distances[r.row] ?: r.metres
                val look = clauseLook(i)
                val dialLabel = stringResource(if (r.negated) R.string.query_dial_away else R.string.query_dial, metres.toInt())
                Row(
                    Modifier.height(36.dp).boardBox(look.fill, round(18f)).clickable(role = Role.Button, onClickLabel = dialLabel) { onDial(r) }
                        .padding(start = 9.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BoardSymbol("ui.radius", 17f, look.ink)
                    BoardLine((if (r.negated) "≥ " else "") + "${metres.toInt()} m", boardText(14f, 600, 18f, tnum = true), look.ink)
                }
                QueryEdit.subjectOf(parsed, r.row)?.let { TokenChip(it, dark = false, ring = look.ring, tint = look.strong, onClick = { onRemove(it) }) }
                if (i == rows.lastIndex) {
                    Spacer(Modifier.weight(1f))
                    QlPill(onQl)
                }
            }
        }
        if (parsed.unknown.isNotEmpty()) {
            BoardLine(stringResource(R.string.query_unknown, parsed.unknown.joinToString(", ")), boardText(12f, 400, 15.5f), 0xFF8A5A00, lines = 2)
        }
    }
}

/**
 * A chip of the query, as the board's: dark (#14161B, a warm symbol, white words) for what is sought, a white 36 px
 * disc with only its symbol for a property, else white with a symbol and words; rings of #E3DED4 or the clause's own.
 * A tap takes it out.
 */
@Composable
private fun TokenChip(chip: Chip, dark: Boolean, onClick: () -> Unit, ring: Long = 0xFFE3DED4, tint: Long = INK) {
    val iconOnly = chip.kind == ChipKind.PROPERTY || chip.kind == ChipKind.OPEN || chip.kind == ChipKind.PREFERENCE
    val remove = stringResource(R.string.query_remove, chip.label)
    Row(
        Modifier.height(36.dp).then(if (iconOnly) Modifier.width(36.dp) else Modifier)
            .boardBox(if (dark) INK else 0xFFFFFFFF, round(18f), border = if (dark) null else ring(1f, ring))
            .clickable(role = Role.Button, onClickLabel = remove, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = chip.label }
            .then(if (iconOnly) Modifier else Modifier.padding(start = 8.dp, end = 12.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (iconOnly) Arrangement.Center else Arrangement.spacedBy(6.dp),
    ) {
        val symbolTint = when {
            dark -> 0xFFF0C38E
            chip.kind == ChipKind.OPEN -> 0xFF178A4E
            else -> tint
        }
        BoardSymbol(chip.symbol, if (iconOnly) 17f else 18f, symbolTint)
        if (!iconOnly) BoardLine(chip.label, boardText(14f, 600, 18f), if (dark) 0xFFFFFFFF else INK)
    }
}

/** The board's `{} QL`: 36 px, ink, the code symbol and "QL" in Geist Mono 12 / 500. */
@Composable
private fun QlPill(onClick: () -> Unit) {
    val d = stringResource(R.string.query_ql)
    Row(
        Modifier.height(36.dp).boardBox(INK, round(18f)).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = d }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        BoardSymbol("ui.code", 15f, 0xFFFFFFFF)
        BoardLine("QL", boardText(12f, 500, 15.5f, family = GEIST_MONO), 0xFFFFFFFF)
    }
}

/** The ranked answers as the board's rows, 2 px apart: number, name, the per-clause distances and walk minutes. */
@Composable
private fun QueryResults(state: QueryState, parsed: ParsedQuery?, here: app.atlas.geo.LatLon, lang: String, vm: QueryViewModel, onPlace: (app.atlas.model.Place) -> Unit) {
    val r = state.result
    val part = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
    when {
        state.text.isBlank() || parsed == null -> StateMessage("ui-near", stringResource(R.string.query_title), part, text = stringResource(R.string.query_text), tint = Color(0xFF7B4DFF))
        parsed.steps.isEmpty() -> StateMessage("ui-help", stringResource(R.string.query_not_read), part, text = stringResource(R.string.query_text), tint = Color(0xFF8A5A00))
        state.error != null && r == null -> StateMessage("ui-alert-triangle", stringResource(R.string.query_failed), part, text = state.error, tint = Color(0xFFC8322A))
        r == null -> BoardLine(stringResource(R.string.kit_loading), boardText(12f, 400, 15.5f), GREY, part)
        r.hits.isEmpty() -> StateMessage(
            "ui-search", stringResource(R.string.query_none), part, text = stringResource(R.string.query_none_text), tint = Color(GREY),
            action = if (vm.online()) ({ PrimaryButton(stringResource(R.string.query_run_online), onClick = vm::runOnline, symbol = "ui-cloud") }) else null,
        )
        else -> {
            val rows = remember(parsed) { QueryEdit.rows(parsed).filter { !it.negated } }
            LazyColumn(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(r.hits, key = { _, h -> h.place.key }) { i, h ->
                    val symbols = h.near.map { n -> rows.getOrNull(n.clause)?.let { QueryEdit.subjectOf(parsed, it.row)?.symbol } ?: n.record.symbol }
                    RankedRow(i + 1, h, symbols, lang, onClick = { onPlace(h.place) })
                }
            }
        }
    }
}

/**
 * A ranked answer, as on the Query board: 60 px over a #ECE8E1 hairline, its number on a 40 px square (blue for the
 * best), the name in 16 / 600, and under it the distance to what each clause asked for in the clause's colour
 * (13 px symbol, 12 px figures, 10 apart); walk minutes at the end in 14 / 700.
 */
@Composable
private fun RankedRow(rank: Int, h: Hit, symbols: List<String>, lang: String, onClick: () -> Unit) {
    val near = h.near.joinToString(", ") { shortMetres(it.distance) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 61.dp)
            .drawBehind { drawRect(Color(0xFFECE8E1), topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - density), size = androidx.compose.ui.geometry.Size(size.width, density)) }
            .clickable(role = Role.Button, onClick = onClick).padding(bottom = 1.dp)
            .semantics(mergeDescendants = true) { if (near.isNotEmpty()) contentDescription = near },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RankTile(rank)
        Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BoardLine(h.record.name(lang) ?: kindOf(h.place, lang) ?: h.place.key, boardText(16f, 600, 20.5f), INK)
            if (h.near.isEmpty()) {
                listOfNotNull(kindOf(h.place, lang), addressOf(h.place)).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    BoardLine(it, boardText(12f, 400, 15.5f), GREY)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    h.near.forEachIndexed { k, n ->
                        val ink = clauseLook(n.clause).ink
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            BoardSymbol(symbols[k], 13f, ink)
                            BoardLine(shortMetres(n.distance), boardText(12f, 400, 15.5f, tnum = true), ink)
                        }
                    }
                }
            }
        }
        h.distance?.let { BoardLine(walkOrDistance(it), boardText(14f, 700, 18f, tnum = true), INK) }
    }
}

/** Metres to the nearest 10 below a kilometre: "60 m", "1.2 km". */
internal fun shortMetres(m: Double): String = if (m < 1000) "${(Math.round(m / 10.0) * 10).toInt()} m" else String.format(Locale.ROOT, "%.1f km", m / 1000)

/** The distance dial: a ring that fills to the distance, a slider, and the usual distances to tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DistanceDialog(subject: Chip?, metres: Double, onSet: (Double) -> Unit, onDismiss: () -> Unit) {
    val c = Atlas.colors
    var value by remember { mutableFloatStateOf(metres.toFloat()) }
    // A sentence may ask for more than the dial's usual kilometre ("within 2 km"): the dial stretches to it.
    val top = maxOf(1000f, metres.toFloat())
    SearchDialog(stringResource(R.string.query_dial_title, subject?.label ?: ""), onDismiss) {
        Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(160.dp)) {
                val w = 14.dp.toPx()
                val r = size.minDimension / 2 - w
                drawCircle(c.goSoft, r, style = Stroke(w))
                drawArc(c.go, -90f, (value / top * 360f).coerceIn(4f, 360f), false, Offset(center.x - r, center.y - r), Size(2 * r, 2 * r), style = Stroke(w, cap = StrokeCap.Round))
                for (k in 0 until 10) {
                    val a = Math.toRadians(k * 36.0 - 90.0)
                    val o = Offset(center.x + (r - w) * kotlin.math.cos(a).toFloat(), center.y + (r - w) * kotlin.math.sin(a).toFloat())
                    drawCircle(c.inkFaint, 1.5.dp.toPx(), o)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${value.toInt()} m", style = Atlas.type.numeral, color = c.ink)
                subject?.let { Symbol(it.symbol, tint = c.go, size = 22.dp) }
            }
        }
        Slider(
            value = value, onValueChange = { value = (Math.round(it / 25f) * 25f).coerceIn(25f, top) }, valueRange = 25f..top,
            colors = SliderDefaults.colors(thumbColor = c.go, activeTrackColor = c.go, inactiveTrackColor = c.goSoft),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in listOf(50, 100, 200, 300, 500, 1000)) app.atlas.ui.kit.ToggleChip("$m m", value.toInt() == m, { value = m.toFloat() })
        }
        PrimaryButton(stringResource(R.string.query_dial_set), onClick = { onSet(value.toDouble()); onDismiss() }, modifier = Modifier.fillMaxWidth())
    }
}

/** `{} QL`: the Overpass QL of the query in monospace, to copy or to run online. */
@Composable
private fun QlDialog(ql: String, online: Boolean, onRun: () -> Unit, onDismiss: () -> Unit) {
    val c = Atlas.colors
    val context = LocalContext.current
    SearchDialog("{ } Overpass QL", onDismiss) {
        Text(
            ql, Modifier.fillMaxWidth().heightIn(max = 380.dp).clip(RoundedCornerShape(14.dp)).background(c.pill)
                .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(14.dp),
            style = Atlas.type.mono.copy(fontSize = 12.sp), color = c.onPill, softWrap = false,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            app.atlas.ui.kit.Chip(stringResource(R.string.place_copy), symbol = "ui-receipt", onClick = { copy(context, "Overpass QL", ql) })
            app.atlas.ui.kit.Chip(
                stringResource(if (online) R.string.query_run_online else R.string.query_run_offline), symbol = if (online) "ui-cloud" else "ui-cloud-off",
                onClick = if (online) ({ onRun(); onDismiss() }) else null,
            )
        }
        Text(stringResource(R.string.query_ql_note), style = Atlas.type.caption, color = c.inkSoft)
    }
}

/** "+": properties and nearby things to add to the sentence, in words the reader understands. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(
        "ui-clock" to R.string.query_add_open, "ui-wifi" to R.string.query_add_wifi, "ui-plug" to R.string.query_add_outlet,
        "ui-umbrella" to R.string.query_add_terrace, "ui-wheelchair" to R.string.query_add_stepfree, "ui-leaf" to R.string.query_add_vegetarian,
        "maki-park" to R.string.query_add_park, "maki-bicycle" to R.string.query_add_bikes, "maki-bus" to R.string.query_add_stop,
    )
    SearchDialog(stringResource(R.string.query_add), onDismiss) {
        Kicker(stringResource(R.string.query_add_kicker))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((symbol, words) in options) {
                val w = stringResource(words)
                app.atlas.ui.kit.Chip(w, symbol = symbol, onClick = { onAdd(w); onDismiss() })
            }
        }
    }
}
