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

private val Cream = Color(0xFFF1E7CE)
private val Board = Color(0xFF1F4A28)

@Composable
private fun kindName(k: ParkKind): String = stringResource(
    when (k) {
        ParkKind.PLAYGROUND -> R.string.park_playground; ParkKind.DOGS -> R.string.park_dogs; ParkKind.CAFE -> R.string.park_cafe; ParkKind.TOILETS -> R.string.park_toilets
        ParkKind.WATER -> R.string.park_water; ParkKind.COURTS -> R.string.park_courts; ParkKind.FITNESS -> R.string.park_fitness; ParkKind.PICNIC -> R.string.park_picnic
    },
)

/** The picture of a park drawn on its notice board: 348 wide, as tall as the park's shape asks (between a wide and a tall plan). */
private const val PLAN_W = 348f

private fun planHeight(box: app.atlas.geo.BBox): Float {
    val w = Geo.distance(app.atlas.geo.LatLon(box.south, box.west), app.atlas.geo.LatLon(box.south, box.east))
    val h = Geo.distance(app.atlas.geo.LatLon(box.south, box.west), app.atlas.geo.LatLon(box.north, box.west))
    val aspect = (w / kotlin.math.max(h, 1.0)).coerceIn(0.8, 2.6)
    return (PLAN_W / aspect).toFloat()
}

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

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFFBFD6E6), 0.55f to Color(0xFFD8E6CC), 1f to Color(0xFFBCD3A8)))) {
        BoardPage(background = Color.Transparent) {
            Spacer(Modifier.height(18.dp))
            BackCircle({ nav.back() }, background = Color.White.copy(alpha = 0.85f), tint = Color(0xFF1C2A1E))
            Spacer(Modifier.height(12.dp))
            when {
                !state.first -> Text(stringResource(R.string.kit_loading), color = Color(0xFF1C2A1E), fontSize = 14.sp)
                park == null -> Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xBFFBFAF4))) {
                    StateMessage("place.park", stringResource(R.string.park_none), text = stringResource(if (services.local.online()) R.string.park_none_text else R.string.picnic_offline_text), tint = Color(0xFF4E5E50))
                }
                else -> {
                    val shape = RoundedCornerShape(10.dp)
                    Box {
                        // The two posts the board stands on.
                        for (side in listOf(Alignment.BottomStart, Alignment.BottomEnd)) Box(
                            Modifier.align(side).padding(horizontal = 28.dp).offset(y = 150.dp).size(16.dp, 330.dp).background(Brush.horizontalGradient(listOf(Color(0xFF4A3420), Color(0xFF6E4E30), Color(0xFF4A3420)))),
                        )
                        Column(Modifier.fillMaxWidth().clip(shape).background(Board).padding(4.dp).clip(RoundedCornerShape(7.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                                Text(
                                    (park.name ?: stringResource(R.string.park_unnamed)).uppercase(), Modifier.weight(1f, fill = false),
                                    style = TextStyle(fontFamily = SerifFamily, fontSize = 28.sp, lineHeight = 30.sp, letterSpacing = 2.sp, color = Cream), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Parkland.todaysSpan(park.hours, today)?.let { Text(it, color = Cream, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp), maxLines = 1) }
                            }
                            Plan(park, here, kind, picked, text, youLabel, onPick = { p -> picked = p; kind = p.kind })
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    val kinds = ParkKind.entries.filter { k -> park.pois.any { it.kind == k } }
                    if (kinds.isEmpty()) {
                        Text(stringResource(R.string.park_nothing_marked), color = Color(0xFF1C2A1E), fontSize = 13.sp)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (k in kinds) KindChip(k, k == kind, park.pois.count { it.kind == k }, park.pois.firstOrNull { it.kind == k }) { kind = k; picked = null }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    val current = picked ?: kind?.let { k -> park.pois.filter { it.kind == k }.minByOrNull { Geo.distance(here, it.place.location) } }
                    if (current != null) DetailCard(current, park, here, local, onGo = {
                        val l = current.place.location
                        nav.go(Dest.Route(l.lat, l.lon, app.atlas.places.Localise.name(current.place.tags, null), mode = "WALK"))
                    })
                }
            }
        }
    }
}

@Composable
private fun Plan(park: Park, here: app.atlas.geo.LatLon, kind: ParkKind?, picked: ParkPoi?, text: BoardText, youLabel: String, onPick: (ParkPoi) -> Unit) {
    val planH = remember(park) { planHeight(park.box) }
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
        Modifier.fillMaxWidth().aspectRatio(PLAN_W / planH).clip(RoundedCornerShape(6.dp)).semantics { contentDescription = desc }.pointerInput(park) {
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

@Composable
private fun KindChip(k: ParkKind, on: Boolean, count: Int, first: ParkPoi?, onClick: () -> Unit) {
    val name = kindName(k)
    val c = Color(k.colour.toInt())
    val label = if (on) (if (k == ParkKind.PLAYGROUND) first?.place?.tags?.let { ageRange(it) } else null) ?: count.toString() else null
    Row(
        Modifier.height(40.dp).clip(CircleShape).background(if (on) c else Color.White).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).semantics { contentDescription = "$name, $count"; selected = on }
            .padding(start = if (on) 8.dp else 0.dp, end = if (on) 12.dp else 0.dp).then(if (on) Modifier else Modifier.width(40.dp)),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (k.symbol == "WC") Text("WC", color = if (on) Color.White else c, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
        else Glyph(k.symbol, if (on) Color.White else c, 18.dp)
        if (label != null) Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun DetailCard(poi: ParkPoi, park: Park, here: app.atlas.geo.LatLon, now: LocalDateTime, onGo: () -> Unit) {
    val c = Color(poi.kind.colour.toInt())
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0xFFFBFAF4)).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
            if (poi.kind.symbol == "WC") Text("WC", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold) else Glyph(poi.kind.symbol, Color.White, 30.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, color = Color(0xFF1C2A1E), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = Color(0xFF4E5E50), fontSize = 13.sp, maxLines = 2)
            if (near.isNotEmpty()) Text(near.joinToString(" · "), color = Color(0xFF4E5E50), fontSize = 12.sp, maxLines = 1)
        }
        Box(Modifier.size(56.dp).clip(CircleShape).background(Board).clickable(onClick = onGo).semantics { contentDescription = walkLabel }, contentAlignment = Alignment.Center) {
            Text(walk, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
    }
}
