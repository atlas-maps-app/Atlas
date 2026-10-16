package app.atlas.ui.local

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.geo.Geo
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.SerifFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.drawSymbol
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.shortDistance
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.hypot
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.CssShadow

private val Cream = Color(0xFFF1E7CE)
private val Board = Color(0xFF1F4A28)

@Composable
private fun kindName(k: ParkKind): String = stringResource(
    when (k) {
        ParkKind.PLAYGROUND -> R.string.park_playground; ParkKind.DOGS -> R.string.park_dogs; ParkKind.CAFE -> R.string.park_cafe; ParkKind.TOILETS -> R.string.park_toilets
        ParkKind.WATER -> R.string.park_water; ParkKind.COURTS -> R.string.park_courts; ParkKind.FITNESS -> R.string.park_fitness; ParkKind.PICNIC -> R.string.park_picnic
    },
)

/** The picture of a park on its notice board: the board's 348 x 400, the park fitted inside it. */
private const val PLAN_W = 348f
private const val PLAN_H = 400f

/**
 * A park's own plan, on the green notice board at its gate: the outline from the map, its paths and trees, a round marker for
 * each playground, dog run, café, toilet, tap, court, fitness station and picnic table, and a red YOU. Choose a kind to see
 * its nearest marker described below, with the toilets and water nearby and the walk there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParklandScreen(args: Dest.Parkland, nav: Navigator) {
    val services = LocalServices.current
    val here = args.at ?: rememberHere()
    val clock = appClock()
    val local = remember(clock) { LocalDateTime.ofInstant(clock.instant(), clock.zone) }
    val today = remember(clock) { LocalDate.now(clock) }
    val state by produceState<Pair<Boolean, Park?>>(false to null, here, args.osmId) {
        value = try {
            val place = Parkland.pick(services, here, args.osmId)
            true to place?.let { Parkland.load(services, it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            true to null
        }
    }
    val park = state.second
    var kind by remember(park) { mutableStateOf(park?.let { p -> ParkKind.entries.firstOrNull { k -> p.pois.any { it.kind == k } } }) }
    var picked by remember(park) { mutableStateOf<ParkPoi?>(null) }
    val measurer = rememberTextMeasurer()
    val text = remember(measurer) { BoardText(measurer, SansFamily) }
    val youLabel = stringResource(R.string.park_you)

    Box(Modifier.fillMaxSize().boardBox(grad = listOf(cssLinear(180f, 0f to 0xFFBFD6E6, 0.55f to 0xFFD8E6CC, 1f to 0xFFBCD3A8)), clip = false)) {
        Box(Modifier.fillMaxSize().padding(top = boardShift()).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(98.dp))
                when {
                    !state.first -> BoardLine(stringResource(R.string.kit_loading), boardStyle(14f, 400, 0xFF1C2A1E, 18f))
                    park == null -> Box(Modifier.fillMaxWidth().boardBox(0xBFFBFAF4, corners(24f))) {
                        StateMessage("place.park", stringResource(R.string.park_none), text = stringResource(if (services.local.online()) R.string.park_none_text else R.string.picnic_offline_text), tint = Color(0xFF4E5E50))
                    }
                    else -> {
                        Box(Modifier.fillMaxWidth().height(480.dp)) {
                            // The two posts the board stands on: 16 x 330 from 500, 44 px in from each side.
                            for (left in listOf(28.dp, 336.dp)) Box(
                                Modifier.offset(x = left, y = 402.dp).size(16.dp, 330.dp).boardBox(grad = listOf(cssLinear(90f, 0f to 0xFF4A3420, 0.5f to 0xFF6E4E30, 1f to 0xFF4A3420))),
                            )
                            // The board: 372 x 480 from (20, 98), radius 10, its 4 px inner rim and shadow, 12 px in, 10 apart.
                            Column(
                                Modifier.padding(horizontal = 4.dp).fillMaxSize()
                                    .boardBox(Board.css(), corners(10f), listOf(CssShadow(0f, 0f, 0f, 4f, 0xFF173A1F, inset = true), CssShadow(0f, 18f, 30f, 0f, 0x59142814)), clip = false)
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    BoardLine(
                                        (park.name ?: stringResource(R.string.park_unnamed)).uppercase(), boardStyle(30f, 400, Cream.css(), 30f, SERIF, spacing = 2.4f),
                                        Modifier.weight(1f, fill = false).alignByBaseline(),
                                    )
                                    Parkland.todaysSpan(park.hours, today)?.let { BoardLine(it, boardStyle(13f, 700, Cream.css(), 17f, tnum = true), Modifier.padding(start = 8.dp).alignByBaseline()) }
                                }
                                Plan(park, here, kind, picked, text, youLabel, onPick = { p -> picked = p; kind = p.kind })
                            }
                        }
                        Spacer(Modifier.height(22.dp))
                        val kinds = ParkKind.entries.filter { k -> park.pois.any { it.kind == k } }
                        if (kinds.isEmpty()) {
                            BoardLine(stringResource(R.string.park_nothing_marked), boardStyle(13f, 700, 0xFF1C2A1E, 17f))
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (k in kinds) KindChip(k, k == kind, park.pois.count { it.kind == k }, park.pois.firstOrNull { it.kind == k }) { kind = k; picked = null }
                            }
                        }
                        Spacer(Modifier.height(150.dp))
                        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
                    }
                }
            }
        }
        // The back button, over the sky.
        BackCircle({ nav.back() }, Modifier.padding(start = 16.dp, top = 42.dp + boardShift()), background = Color(0xD9FFFFFF), tint = Color(0xFF1C2A1E))
        // The chosen thing, pinned 26 px from the foot as the board pins it.
        val p = park
        val current = p?.let { picked ?: kind?.let { k -> p.pois.filter { it.kind == k }.minByOrNull { Geo.distance(here, it.place.location) } } }
        if (p != null && current != null) DetailCard(
            current, p, here, local,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, bottom = 26.dp),
            onGo = {
                val l = current.place.location
                nav.go(Dest.Route(l.lat, l.lon, app.atlas.places.Localise.name(current.place.tags, null), mode = "WALK"))
            },
        )
    }
}

@Composable
private fun Plan(park: Park, here: app.atlas.geo.LatLon, kind: ParkKind?, picked: ParkPoi?, text: BoardText, youLabel: String, onPick: (ParkPoi) -> Unit) {
    val planH = PLAN_H
    val proj = remember(park) { ParkProjection(park.box, PLAN_W, planH, 16f) }
    val markers = remember(park) { park.pois.map { it to Offset(proj.x(it.place.location.lon), proj.y(it.place.location.lat)) } }
    // Markers of one kind that sit on top of each other are drawn once, with their number.
    val drawn = remember(park, kind) {
        val shown = markers.filter { it.first.kind != ParkKind.PICNIC || it.first.kind == kind }
        val out = ArrayList<Triple<ParkPoi, Offset, Int>>()
        for ((poi, at) in shown.sortedBy { Geo.distance(here, it.first.place.location) }) {
            val i = out.indexOfFirst { it.first.kind == poi.kind && hypot(it.second.x - at.x, it.second.y - at.y) < 22f }
            if (i >= 0) out[i] = Triple(out[i].first, out[i].second, out[i].third + 1) else out += Triple(poi, at, 1)
        }
        out
    }
    val desc = stringResource(R.string.park_plan_desc, park.name ?: "", park.pois.size)
    Canvas(
        Modifier.size(PLAN_W.dp, PLAN_H.dp).clip(RoundedCornerShape(6.dp)).semantics { contentDescription = desc }.pointerInput(park) {
            detectTapGestures { tap ->
                val s = size.width / PLAN_W
                drawn.minByOrNull { hypot(it.second.x * s - tap.x, it.second.y * s - tap.y) }?.takeIf { hypot(it.second.x * s - tap.x, it.second.y * s - tap.y) < 26f * s }?.let { onPick(it.first) }
            }
        },
    ) {
        val s = size.width / PLAN_W
        scale(s, s, Offset.Zero) {
            drawRect(Cream)
            // The lawn: the outline as the map draws it.
            val lawn = Path().apply { fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd }
            for (ring in park.shape.rings) {
                ring.forEachIndexed { i, p -> if (i == 0) lawn.moveTo(proj.x(p.lon), proj.y(p.lat)) else lawn.lineTo(proj.x(p.lon), proj.y(p.lat)) }
                lawn.close()
            }
            drawPath(lawn, Color(0xFFA9CC8C), style = Fill)
            drawPath(lawn, Color(0xFF7FA968), style = Stroke(2f, join = StrokeJoin.Round))
            withTransform({ clipPath(lawn) }) {
                // The paths, cream on the lawn.
                for (line in park.shape.paths) {
                    val p = Path()
                    line.forEachIndexed { i, q -> if (i == 0) p.moveTo(proj.x(q.lon), proj.y(q.lat)) else p.lineTo(proj.x(q.lon), proj.y(q.lat)) }
                    drawPath(p, Cream, style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                // Trees, as the soft round crowns of the painted plan.
                for (t in park.shape.trees.take(80)) drawCircle(Color(0xFF6E9E5A), 6f, Offset(proj.x(t.lon), proj.y(t.lat)))
            }
            // The markers: the chosen kind full size, the others a little smaller.
            for ((poi, at, n) in drawn.sortedBy { if (it.first.kind == kind) 1 else 0 }) {
                val on = poi.kind == kind || poi === picked
                val c = Color(poi.kind.colour.toInt())
                if (!on) { drawCircle(Color.White, 5.5f, at); drawCircle(c, 4f, at); continue }
                val r = 14f
                if (poi === picked) drawCircle(Color.White, r + 3f, at)
                drawCircle(c, r, at)
                if (poi.kind.symbol == "WC") text.draw(this, "WC", at.x, at.y + 3.8f, if (on) 10.5f else 8.5f, Color.White, bold = true, anchor = 0.5f)
                else drawGlyphIn(poi.kind.symbol, at, r * 1.15f)
                if (n > 1) {
                    drawCircle(Color.White, 6.5f, Offset(at.x + r * 0.8f, at.y - r * 0.8f))
                    text.draw(this, n.toString(), at.x + r * 0.8f, at.y - r * 0.8f + 3f, 8.5f, c, bold = true, anchor = 0.5f)
                }
            }
            // You, in red, with an arrow that points at the park when you are outside it.
            val you = Offset(proj.x(here.lon).coerceIn(10f, PLAN_W - 10f), proj.y(here.lat).coerceIn(26f, planH - 12f))
            drawCircle(Color.White, 9f, you)
            drawCircle(Color(0xFFC8322A), 7f, you)
            text.draw(this, youLabel, you.x, you.y - 14f, 10f, Color(0xFF8E1C16), bold = true, anchor = 0.5f)
        }
    }
}

private fun DrawScope.drawGlyphIn(symbol: String, at: Offset, side: Float) {
    drawSymbol(symbol, Offset(at.x - side / 2, at.y - side / 2), side, Color.White, weight = 1.1f)
}

/**
 * A chip of the board: 40 px, radius 20. The chosen kind in its colour with its 18 px symbol and a word (the ages, or the
 * count) 6 px after it, 8 px in before and 12 after; the others white, a symbol alone in a 40 px circle, WC in its violet.
 */
@Composable
private fun KindChip(k: ParkKind, on: Boolean, count: Int, first: ParkPoi?, onClick: () -> Unit) {
    val name = kindName(k)
    val c = Color(k.colour.toInt())
    val label = if (on) (if (k == ParkKind.PLAYGROUND) first?.place?.tags?.let { ageRange(it) } else null) ?: count.toString() else null
    val wc = k.symbol == "WC"
    Row(
        Modifier.height(40.dp).then(if (!on && !wc) Modifier.width(40.dp) else Modifier).boardBox(if (on) k.colour else 0xFFFFFFFF, corners(20f))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).semantics { contentDescription = "$name, $count"; selected = on }
            .padding(start = if (wc) 12.dp else if (on) 8.dp else 0.dp, end = if (on || wc) 12.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (wc) BoardLine("WC", boardStyle(13f, 800, if (on) 0xFFFFFFFF else k.colour, 17f))
        else Glyph(k.symbol, if (on) Color.White else c, if (k == ParkKind.WATER) 17.dp else 18.dp)
        if (label != null) BoardLine(label, boardStyle(13f, 700, 0xFFFFFFFF, 17f, tnum = true))
    }
}

/**
 * The board's card for the chosen thing: 108 px, radius 26, 14 by 16 px in, 14 apart: its 56 px disc, the name, its
 * facts 8 apart and what is near, and the walk on the dark green disc.
 */
@Composable
private fun DetailCard(poi: ParkPoi, park: Park, here: app.atlas.geo.LatLon, now: LocalDateTime, modifier: Modifier, onGo: () -> Unit) {
    val t = poi.place.tags
    val name = app.atlas.places.Localise.name(t, java.util.Locale.getDefault().language) ?: kindName(poi.kind)
    val facts = buildList {
        ageRange(t)?.let { add(stringResource(R.string.park_ages, it)) }
        if (fenced(t)) add(stringResource(R.string.picnic_fenced))
        if (t["wheelchair"] == "yes") add(stringResource(R.string.picnic_step_free))
        t["surface"]?.takeIf { poi.kind == ParkKind.PLAYGROUND }?.let { add(it.replace('_', ' ')) }
    }
    val wc = park.pois.filter { it.kind == ParkKind.TOILETS && it !== poi }.minByOrNull { Geo.distance(poi.place.location, it.place.location) }
    val water = park.pois.filter { it.kind == ParkKind.WATER && it !== poi }.minByOrNull { Geo.distance(poi.place.location, it.place.location) }
    val near = listOfNotNull(
        wc?.let { stringResource(R.string.park_wc_at, shortDistance(Geo.distance(poi.place.location, it.place.location))) },
        water?.let { stringResource(R.string.park_water_at, shortDistance(Geo.distance(poi.place.location, it.place.location))) },
    )
    val walk = walkOrDistance(Geo.distance(here, poi.place.location))
    val walkLabel = stringResource(R.string.parkland_walk_there, walk)
    Row(
        modifier.fillMaxWidth().height(108.dp).boardBox(0xFFFBFAF4, corners(26f)).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).boardBox(poi.kind.colour, corners(28f)), contentAlignment = Alignment.Center) {
            if (poi.kind.symbol == "WC") BoardLine("WC", boardStyle(17f, 800, 0xFFFFFFFF, 22f)) else Glyph(poi.kind.symbol, Color.White, 30.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BoardLine(name, boardStyle(17f, 800, 0xFF1C2A1E, 22f))
            if (facts.isNotEmpty()) GapLine(8f) { for (f in facts) BoardLine(f, boardStyle(13f, 400, 0xFF4E5E50, 17f, tnum = true), overflow = TextOverflow.Clip) }
            if (near.isNotEmpty()) BoardLine(near.joinToString(" · "), boardStyle(12f, 400, 0xFF4E5E50, 15.5f))
        }
        Box(Modifier.size(56.dp).boardBox(Board.css(), corners(28f)).clickable(onClick = onGo).semantics { contentDescription = walkLabel }, contentAlignment = Alignment.Center) {
            BoardLine(walk, boardStyle(15f, 800, 0xFFFFFFFF, 19.5f, tnum = true))
        }
    }
}
