package app.atlas.ui.search

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.atlas.R
import app.atlas.geo.BBox
import app.atlas.mapview.AreaOverlay
import app.atlas.mapview.AtlasMap
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
import app.atlas.services.SettingsKeys
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Hairline
import app.atlas.ui.kit.Kicker
import app.atlas.ui.kit.PrimaryButton
import app.atlas.ui.kit.Sheet
import app.atlas.ui.kit.SheetStop
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.SymbolButton
import app.atlas.ui.kit.WalkTime
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberSheetState
import app.atlas.ui.kit.softShadow
import app.atlas.ui.kit.walkMinutes
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/** The colours of the ↳ rows, in order: blue, green, purple, amber (as the Query board's circles). */
internal val CLAUSE_COLOURS = longArrayOf(0xFF2E5BFF, 0xFF1F9D55, 0xFF7B4FD6, 0xFFE29A12)

/** The placeholder flag "alert me" sets: queries to watch, kept in settings. */
internal val ALERTS = stringSetPreferencesKey("search.alerts")

/**
 * The query builder, "A near B", as on the Query board: the sentence read into chips (what, and ↳ near what, within a
 * distance you can dial), the map with the circles around the "B"s and the ranked "A" pins, and the sheet with how many
 * there are, how fast the phone found them, save as a layer, alert me, download, and `{} QL`.
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
    val c = Atlas.colors
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

    BoxWithConstraints(Modifier.fillMaxSize().background(c.paper)) {
        AtlasMap(
            state = map,
            tiles = tiles,
            modifier = Modifier.fillMaxSize(),
            overlays = MapOverlays(markers = markers, areas = areas, location = UserLocation(here, 15.0)),
            contentPadding = PaddingValues(top = 260.dp, bottom = maxHeight * 0.42f),
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

        // The sentence and its chips, in a card over the map.
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxWidth()
                .softShadow(RoundedCornerShape(28.dp), c.shadow, 14.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(c.card)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SymbolButton("ui-chevron-left", stringResource(R.string.kit_back), { nav.back() }, size = 44.dp, elevation = 0.dp, background = c.cardMuted)
                Symbol("ui-sparkles", tint = c.ask, size = 20.dp)
                BasicTextField(
                    field, { field = it; vm.onText(it) }, Modifier.weight(1f),
                    textStyle = Atlas.type.bodyStrong.copy(color = c.ink, fontSize = 16.sp), cursorBrush = SolidColor(c.go), maxLines = 2,
                    decorationBox = { inner ->
                        if (field.isEmpty()) Text(stringResource(R.string.query_hint), style = Atlas.type.body, color = c.inkFaint)
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

        Sheet(sheet, peekHeight = 120.dp, halfFraction = 0.42f, fullTopGap = 150.dp) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val total = state.result?.total
                Text(total?.toString() ?: "–", style = Atlas.type.numeral, color = c.ink)
                parsed?.let { QueryEdit.mainChips(it).firstOrNull { ch -> ch.kind == ChipKind.KIND } }?.let { Symbol(it.symbol, tint = c.ink, size = 22.dp) }
                state.result?.let { r ->
                    val secs = String.format(Locale.ROOT, "%.2f s", r.micros / 1_000_000.0)
                    val where = stringResource(if (state.online) R.string.query_online else R.string.query_on_phone)
                    Row(
                        Modifier.clip(CircleShape).background(c.openSoft).padding(horizontal = 10.dp, vertical = 5.dp)
                            .semantics(mergeDescendants = true) { contentDescription = "$where $secs" },
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Symbol(if (state.online) "ui-cloud" else "ui-smartphone", tint = c.open, size = 14.dp)
                        Text(secs, style = Atlas.type.label, color = c.open)
                    }
                }
                Spacer(Modifier.weight(1f))
                val text = state.text.trim()
                val kept = layers.any { it.text == text }
                val alerting = text in alerts
                SymbolButton("ui-layers", stringResource(if (kept) R.string.query_layer_kept else R.string.query_layer), {
                    if (text.isNotEmpty()) services.scope.launch(Dispatchers.IO) { runCatching { if (kept) services.saved.removeLayer(text) else services.saved.keepLayer(text, alerting) } }
                }, size = 44.dp, elevation = 0.dp, background = c.cardMuted, selected = kept)
                SymbolButton("ui-bell", stringResource(if (alerting) R.string.query_alert_on else R.string.query_alert), {
                    if (text.isNotEmpty()) services.scope.launch {
                        services.settings.edit { p -> val now = p[ALERTS].orEmpty(); p[ALERTS] = if (alerting) now - text else now + text }
                    }
                }, size = 44.dp, elevation = 0.dp, background = c.cardMuted, selected = alerting)
                SymbolButton("ui-download", stringResource(R.string.query_download), {
                    val b = BBox.of(hits.map { it.record.location })
                    val centre = b?.center ?: here
                    nav.go(Dest.RegionPicker(centre.lat, centre.lon))
                }, size = 44.dp, elevation = 0.dp, background = c.cardMuted)
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

/** The chips of the query: the main row, then a ↳ row per "near" with its distance dial and what it is near. */
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
    val c = Atlas.colors
    val rows = remember(parsed) { QueryEdit.rows(parsed) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.cardMuted).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                QueryEdit.mainChips(parsed).forEachIndexed { i, chip -> TokenChip(chip, dark = i == 0 && chip.kind == ChipKind.KIND, onClick = { onRemove(chip) }) }
                val addLabel = stringResource(R.string.query_add)
                Canvas(Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onAdd).semantics { contentDescription = addLabel }) {
                    dashedCircle(c.inkFaint, 1.5.dp.toPx())
                    val s = 6.dp.toPx()
                    drawLine(c.inkSoft, Offset(center.x - s, center.y), Offset(center.x + s, center.y), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(c.inkSoft, Offset(center.x, center.y - s), Offset(center.x, center.y + s), 2.dp.toPx(), StrokeCap.Round)
                }
            }
            QlPill(onQl)
        }
        rows.forEachIndexed { i, r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("↳", style = Atlas.type.headline, color = c.inkFaint)
                val metres = state.distances[r.row] ?: r.metres
                val col = Color(CLAUSE_COLOURS[i % CLAUSE_COLOURS.size])
                val dialLabel = stringResource(if (r.negated) R.string.query_dial_away else R.string.query_dial, metres.toInt())
                Row(
                    Modifier.heightIn(min = 40.dp).clip(CircleShape).background(col.copy(alpha = 0.14f)).clickable(role = Role.Button, onClickLabel = dialLabel) { onDial(r) }
                        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DialGlyph(metres, col)
                    Text((if (r.negated) "≥ " else "") + "${metres.toInt()} m", style = Atlas.type.bodyStrong, color = col)
                }
                QueryEdit.subjectOf(parsed, r.row)?.let { TokenChip(it, dark = false, onClick = { onRemove(it) }) }
            }
        }
        if (parsed.unknown.isNotEmpty()) {
            Text(stringResource(R.string.query_unknown, parsed.unknown.joinToString(", ")), style = Atlas.type.caption, color = c.caution)
        }
    }
}

/** A chip of the query: dark for what is sought, white with a symbol for the rest; tap takes it out. */
@Composable
private fun TokenChip(chip: Chip, dark: Boolean, onClick: () -> Unit) {
    val c = Atlas.colors
    val bg = if (dark) c.pill else c.card
    val fg = if (dark) c.onPill else c.ink
    val iconOnly = chip.kind == ChipKind.PROPERTY || chip.kind == ChipKind.OPEN || chip.kind == ChipKind.PREFERENCE
    val remove = stringResource(R.string.query_remove, chip.label)
    Row(
        Modifier.heightIn(min = 40.dp).clip(CircleShape).background(bg).then(if (!dark) Modifier.border(1.dp, c.line, CircleShape) else Modifier)
            .clickable(role = Role.Button, onClickLabel = remove, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = chip.label }
            .padding(horizontal = if (iconOnly) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Symbol(chip.symbol, tint = if (chip.kind == ChipKind.OPEN) c.open else fg, size = 18.dp)
        if (!iconOnly) Text(chip.label, style = Atlas.type.bodyStrong, color = fg, maxLines = 1)
    }
}

@Composable
private fun QlPill(onClick: () -> Unit) {
    val c = Atlas.colors
    val d = stringResource(R.string.query_ql)
    Row(
        Modifier.heightIn(min = 40.dp).clip(CircleShape).background(c.pill).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = d }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("{ } QL", style = Atlas.type.mono.copy(fontWeight = FontWeight.Bold), color = c.onPill, maxLines = 1, softWrap = false)
    }
}

/** The little dial of a distance chip: a ring filled to the distance on a 1 km scale. */
@Composable
private fun DialGlyph(metres: Double, colour: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = 2.dp.toPx()
        val r = size.minDimension / 2 - w
        drawCircle(colour.copy(alpha = 0.3f), r, style = Stroke(w))
        drawArc(colour, -90f, (metres / 1000.0 * 360.0).toFloat().coerceIn(12f, 360f), false, Offset(center.x - r, center.y - r), Size(2 * r, 2 * r), style = Stroke(w, cap = StrokeCap.Round))
        drawCircle(colour, w * 0.8f, center)
    }
}

/** The ranked answers: number, name, the per-clause distances ("🚲 60 m · 🌲 30 m") and walk minutes. */
@Composable
private fun QueryResults(state: QueryState, parsed: ParsedQuery?, here: app.atlas.geo.LatLon, lang: String, vm: QueryViewModel, onPlace: (app.atlas.model.Place) -> Unit) {
    val c = Atlas.colors
    val r = state.result
    when {
        state.text.isBlank() || parsed == null -> StateMessage("ui-near", stringResource(R.string.query_title), text = stringResource(R.string.query_text), tint = c.ask)
        parsed.steps.isEmpty() -> StateMessage("ui-help", stringResource(R.string.query_not_read), text = stringResource(R.string.query_text), tint = c.caution)
        state.error != null && r == null -> StateMessage("ui-alert-triangle", stringResource(R.string.query_failed), text = state.error, tint = c.alert)
        r == null -> Text(stringResource(R.string.kit_loading), Modifier.padding(20.dp), style = Atlas.type.caption, color = c.inkSoft)
        r.hits.isEmpty() -> StateMessage(
            "ui-search", stringResource(R.string.query_none), text = stringResource(R.string.query_none_text), tint = c.inkSoft,
            action = if (vm.online()) ({ PrimaryButton(stringResource(R.string.query_run_online), onClick = vm::runOnline, symbol = "ui-cloud") }) else null,
        )
        else -> {
            val rows = remember(parsed) { QueryEdit.rows(parsed).filter { !it.negated } }
            LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                itemsIndexed(r.hits, key = { _, h -> h.place.key }) { i, h ->
                    val symbols = h.near.map { n -> rows.getOrNull(n.clause)?.let { QueryEdit.subjectOf(parsed, it.row)?.symbol } ?: n.record.symbol }
                    RankedRow(i + 1, h, symbols, lang, onClick = { onPlace(h.place) })
                    if (i < r.hits.lastIndex) Hairline()
                }
            }
        }
    }
}

/**
 * A ranked answer, as on the Query board: its number on a square (blue for the best), the name, and under it the
 * distance to what each clause asked for in the clause's colour ("🚲 60 m · 🌲 30 m"); walk minutes at the end.
 */
@Composable
private fun RankedRow(rank: Int, h: Hit, symbols: List<String>, lang: String, onClick: () -> Unit) {
    val c = Atlas.colors
    val near = h.near.joinToString(", ") { shortMetres(it.distance) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) { if (near.isNotEmpty()) contentDescription = near },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RankTile(rank, 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(h.record.name(lang) ?: kindOf(h.place, lang) ?: h.place.key, style = Atlas.type.name, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (h.near.isEmpty()) {
                listOfNotNull(kindOf(h.place, lang), addressOf(h.place)).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = Atlas.type.caption, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    h.near.forEachIndexed { k, n ->
                        val col = Color(CLAUSE_COLOURS[n.clause % CLAUSE_COLOURS.size])
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Symbol(symbols[k], tint = col, size = 15.dp)
                            Text(shortMetres(n.distance), style = Atlas.type.label, color = col)
                        }
                    }
                }
            }
        }
        h.distance?.let { Text(walkOrDistance(it), style = Atlas.type.numeralSmall, color = c.ink) }
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
