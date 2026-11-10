package app.atlas.ui.local

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.Localise
import app.atlas.places.Presets
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.drawSymbol
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.shortDistance
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

private val Cream = Color(0xFFF1E7CE)
private val Board = Color(0xFF1F4A28)

/** The Wild board's sheet and its inks: the cream, the dark green of its titles, the slate of its lines, the green of its rules. */
private const val SHEET = 0xFFF4F1E8
private const val INK = 0xFF1F3A2A
private const val SLATE = 0xFF5A6478
private const val RULE_INK = 0xFF3A5A44

@Composable
private fun kindName(k: ParkKind): String = stringResource(
    when (k) {
        ParkKind.PLAYGROUND -> R.string.park_playground; ParkKind.DOGS -> R.string.park_dogs; ParkKind.CAFE -> R.string.park_cafe; ParkKind.TOILETS -> R.string.park_toilets
        ParkKind.WATER -> R.string.park_water; ParkKind.COURTS -> R.string.park_courts; ParkKind.FITNESS -> R.string.park_fitness; ParkKind.PICNIC -> R.string.park_picnic
        ParkKind.VISITOR -> R.string.park_visitor; ParkKind.SLEEP -> R.string.park_sleep
    },
)

/** The picture of a park on its notice board: 348 wide, as high as the park's own shape asks between 200 and 440. */
private const val PLAN_W = 348f
private const val PLAN_PAD = 16f
private const val PLAN_LOW = 200f
private const val PLAN_HIGH = 440f

/** How much of the posts shows under the board before the sheet covers their feet. */
private val POSTS_SHOWN = 56.dp

/**
 * A park's own page, from the Parkland board's notice board and the Wild board's national park (the owner, 10 November:
 * "Can national parks get a designated places page? Improve a places page?"): the park's plan on the green sign at its
 * gate, drawn from the map on the phone and fitted to the park's shape, with a round marker for each playground, dog
 * run, café, toilet, tap, court, fitness station and picnic table, and in the wilds the visitor centre and where to sleep;
 * a national park's shield, a reserve's and a national park's size. Under it, on the cream sheet: the kinds to choose and
 * the chosen thing with the walk there, the park's rules as mapped, its trails, what the year brings it now, and its
 * notable trees and bird places as field notes. Its place page is behind the details button.
 */
@Composable
fun ParklandScreen(args: Dest.Parkland, nav: Navigator) {
    val services = LocalServices.current
    val here = args.at ?: rememberHere()
    val clock = appClock()
    val today = remember(clock) { LocalDate.now(clock) }
    // The nearest park follows you; a park opened by name stays.
    val follow = if (args.osmId == null && args.key == null) here else null
    val state by produceState<Pair<Boolean, Park?>>(false to null, follow, args.osmId, args.key) {
        value = try {
            val place = Parkland.pick(services, here, args.osmId, args.key)
            true to place?.let { Parkland.load(services, it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            true to null
        }
    }
    val park = state.second
    var kind by remember(park) { mutableStateOf(park?.let { p -> ParkKind.order(p.kind).firstOrNull { k -> p.pois.any { it.kind == k } } }) }
    var picked by remember(park) { mutableStateOf<ParkPoi?>(null) }

    Box(Modifier.fillMaxSize().boardBox(grad = listOf(cssLinear(180f, 0f to 0xFFBFD6E6, 0.55f to 0xFFD8E6CC, 1f to 0xFFBCD3A8)), clip = false)) {
        Column(Modifier.fillMaxSize().padding(top = boardShift()).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(98.dp))
            when {
                !state.first -> BoardLine(stringResource(R.string.kit_loading), boardStyle(14f, 400, 0xFF1C2A1E, 18f), Modifier.padding(horizontal = 16.dp))
                park == null -> Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().boardBox(0xBFFBFAF4, corners(24f))) {
                    StateMessage("place.park", stringResource(R.string.park_none), text = stringResource(if (services.local.online()) R.string.park_none_text else R.string.picnic_offline_text), tint = Color(0xFF4E5E50))
                }
                else -> {
                    Sign(park, here, today, kind, picked, onPick = { p -> picked = p; kind = p.kind })
                    Sheet(park, here, today, kind, picked, nav, onKind = { k -> kind = k; picked = null })
                }
            }
        }
        // The back button and the place page, over the sky.
        BackCircle({ nav.back() }, Modifier.padding(start = 16.dp, top = 42.dp + boardShift()), background = Color(0xD9FFFFFF), tint = Color(0xFF1C2A1E))
        if (park != null) {
            val details = stringResource(R.string.park_details)
            Box(
                Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 42.dp + boardShift()).size(44.dp).boardBox(0xD9FFFFFF, corners(22f))
                    .clickable(role = Role.Button) { services.remember(park.place); nav.go(Dest.Place.of(park.place)) }.semantics { contentDescription = details },
                contentAlignment = Alignment.Center,
            ) { Symbol("ui.info", tint = Color(0xFF1C2A1E), size = 20.dp) }
        }
    }
}

/** The name's size on the sign: the board's 30 for a short name, smaller as it grows, never more than two lines. */
private fun nameSize(name: String): Float = when {
    name.length <= 12 -> 30f
    name.length <= 18 -> 25f
    else -> 21f
}

/**
 * The green notice board on its two posts: the name in the board's caps (a national park's after its shield), what it is
 * and how big under it, today's hours at the right, and the plan; the posts stand on into the sheet below.
 */
@Composable
private fun Sign(park: Park, here: LatLon, today: LocalDate, kind: ParkKind?, picked: ParkPoi?, onPick: (ParkPoi) -> Unit) {
    val lang = remember { Locale.getDefault().language }
    val name = park.name?.let { Parkland.shortName(it, park.kind) } ?: stringResource(R.string.park_unnamed)
    val what = if (park.kind == GreenKind.PARK) null else Presets.match(park.place.tags)?.name(lang)?.uppercase(Locale.getDefault())
    val area = park.shape.areaM2?.takeIf { park.shape.outlined }?.let { Parkland.areaText(it) }
    val sub = listOfNotNull(what, area).joinToString(" · ").ifEmpty { null }
    val hours = Parkland.todaysSpan(park.hours, today)
    val big = nameSize(name)
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // The posts: 16 wide, 44 px in from each side, from behind the board's foot to under the sheet's edge.
        Canvas(Modifier.matchParentSize()) {
            val w = 16.dp.toPx()
            val top = size.height - POSTS_SHOWN.toPx() - 40.dp.toPx()
            val foot = size.height + 40.dp.toPx()
            for (left in listOf(28.dp.toPx(), 336.dp.toPx())) {
                drawRect(Brush.horizontalGradient(listOf(Color(0xFF4A3420), Color(0xFF6E4E30), Color(0xFF4A3420)), left, left + w), Offset(left, top), Size(w, foot - top))
            }
        }
        Column {
            // The board: 372 wide, radius 10, its 4 px inner rim and shadow, 12 px in, 10 apart.
            Column(
                Modifier.padding(horizontal = 4.dp).fillMaxWidth()
                    .boardBox(Board.css(), corners(10f), listOf(CssShadow(0f, 0f, 0f, 4f, 0xFF173A1F, inset = true), CssShadow(0f, 18f, 30f, 0f, 0x59142814)), clip = false)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (park.kind == GreenKind.NATIONAL) Glyph("wild.badge", Color.Unspecified, 40.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        BoardPara(name.uppercase(Locale.getDefault()), boardStyle(big, 400, Cream.css(), big, SERIF, spacing = 2.4f * big / 30f), Modifier.semantics { heading() }, lines = 2)
                        sub?.let { BoardLine(it, boardStyle(11f, 800, 0xD9F1E7CE, 14f, spacing = 1.3f, tnum = true)) }
                    }
                    hours?.let { BoardLine(it, boardStyle(13f, 700, Cream.css(), 17f, tnum = true)) }
                }
                Plan(park, here, kind, picked, onPick)
            }
            Spacer(Modifier.height(POSTS_SHOWN))
        }
    }
}

@Composable
private fun Plan(park: Park, here: LatLon, kind: ParkKind?, picked: ParkPoi?, onPick: (ParkPoi) -> Unit) {
    val planH = remember(park) { ParkProjection.heightFor(park.box, PLAN_W, PLAN_PAD, PLAN_LOW, PLAN_HIGH) }
    val proj = remember(park) { ParkProjection(park.box, PLAN_W, planH, PLAN_PAD) }
    val measurer = rememberTextMeasurer()
    val text = remember(measurer) { BoardText(measurer, SansFamily) }
    val youLabel = stringResource(R.string.park_you)
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
    // A wide park's paths thinner, so its lawns still read between them.
    val across = remember(park) { Geo.distance(LatLon(park.box.south, park.box.west), LatLon(park.box.north, park.box.east)) }
    val pathWidth = if (across > 1_500) 2.5f else 4f
    val inside = park.shape.contains(here)
    val far = remember(park, here) { if (inside) 0.0 else park.shape.edgeDistance(here) }
    val farText = shortDistance(far)
    val desc = stringResource(R.string.park_plan_desc, park.name ?: "", park.pois.size)
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(PLAN_W / planH).clip(RoundedCornerShape(6.dp)).semantics { contentDescription = desc }.pointerInput(park, drawn) {
            detectTapGestures { tap ->
                val s = size.width / PLAN_W
                drawn.minByOrNull { hypot(it.second.x * s - tap.x, it.second.y * s - tap.y) }?.takeIf { hypot(it.second.x * s - tap.x, it.second.y * s - tap.y) < 26f * s }?.let { onPick(it.first) }
            }
        },
    ) {
        val s = size.width / PLAN_W
        scale(s, s, Offset.Zero) {
            drawRect(Cream, size = Size(PLAN_W, planH))
            // The lawn: the outline as the map draws it, a lake or a yard left out of it as a hole.
            val lawn = Path().apply { fillType = PathFillType.EvenOdd }
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
                    drawPath(p, Cream, style = Stroke(pathWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                // Trees, as the soft round crowns of the painted plan.
                for (t in park.shape.trees.take(120)) drawCircle(Color(0xFF6E9E5A), 6f, Offset(proj.x(t.lon), proj.y(t.lat)))
            }
            // The markers: the chosen kind full size, the others a little smaller.
            for ((poi, at, n) in drawn.sortedBy { if (it.first.kind == kind) 1 else 0 }) {
                val on = poi.kind == kind || poi === picked
                val c = Color(poi.kind.colour.toInt())
                if (!on) { drawCircle(Color.White, 5.5f, at); drawCircle(c, 4f, at); continue }
                val r = 14f
                if (poi === picked) drawCircle(Color.White, r + 3f, at)
                drawCircle(c, r, at)
                if (poi.kind.symbol == "WC") text.draw(this, "WC", at.x, at.y + 3.8f, 10.5f, Color.White, bold = true, anchor = 0.5f)
                else drawGlyphIn(poi.kind.symbol, at, r * 1.15f)
                if (n > 1) {
                    drawCircle(Color.White, 6.5f, Offset(at.x + r * 0.8f, at.y - r * 0.8f))
                    text.draw(this, n.toString(), at.x + r * 0.8f, at.y - r * 0.8f + 3f, 8.5f, c, bold = true, anchor = 0.5f)
                }
            }
            drawYou(proj, here, planH, inside, text, youLabel, farText)
        }
    }
}

/**
 * You, in red: a dot where you stand in the picture; from outside it, an arrow at its edge pointing your way with how far
 * the park's edge is.
 */
private fun DrawScope.drawYou(proj: ParkProjection, here: LatLon, planH: Float, inside: Boolean, text: BoardText, label: String, farText: String) {
    val red = Color(0xFFC8322A)
    val px = proj.x(here.lon)
    val py = proj.y(here.lat)
    if (px in 10f..(PLAN_W - 10f) && py in 22f..(planH - 10f)) {
        drawCircle(Color.White, 9f, Offset(px, py))
        drawCircle(red, 7f, Offset(px, py))
        text.draw(this, label, px, py - 14f, 10f, Color(0xFF8E1C16), bold = true, anchor = 0.5f)
        return
    }
    // Where the line from the middle of the picture towards you leaves it.
    val cx = PLAN_W / 2
    val cy = planH / 2
    val dx = px - cx
    val dy = py - cy
    val t = min(if (dx != 0f) (cx - 14f) / abs(dx) else Float.MAX_VALUE, if (dy != 0f) (cy - 14f) / abs(dy) else Float.MAX_VALUE)
    val ex = cx + dx * t
    val ey = cy + dy * t
    val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    rotate(angle, Offset(ex, ey)) {
        val arrow = Path().apply { moveTo(ex + 10f, ey); lineTo(ex - 7f, ey - 8f); lineTo(ex - 3f, ey); lineTo(ex - 7f, ey + 8f); close() }
        drawPath(arrow, Color.White, style = Stroke(5f, join = StrokeJoin.Round))
        drawPath(arrow, red, style = Fill)
    }
    // The label inside the picture, a little back from the arrow towards the middle.
    val len = hypot(dx, dy).coerceAtLeast(1f)
    val lx = (ex - dx / len * 30f).coerceIn(40f, PLAN_W - 40f)
    val ly = (ey - dy / len * 22f + 3.5f).coerceIn(12f, planH - 6f)
    text.draw(this, "$label · $farText", lx, ly, 10f, Color(0xFF8E1C16), bold = true, anchor = 0.5f)
}

private fun DrawScope.drawGlyphIn(symbol: String, at: Offset, side: Float) {
    drawSymbol(symbol, Offset(at.x - side / 2, at.y - side / 2), side, Color.White, weight = 1.1f)
}

/**
 * The cream sheet under the sign: the kinds to choose and the chosen thing, the park's rules, its trails and what the year
 * brings it now in the Wild board's rows, its field notes, and where it all comes from.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Sheet(park: Park, here: LatLon, today: LocalDate, kind: ParkKind?, picked: ParkPoi?, nav: Navigator, onKind: (ParkKind) -> Unit) {
    val kinds = ParkKind.order(park.kind).filter { k -> park.pois.any { it.kind == k } }
    val current = picked ?: kind?.let { k -> park.pois.filter { it.kind == k }.minByOrNull { Geo.distance(here, it.place.location) } }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 600.dp).boardBox(SHEET, CssCorners(30f, 30f, 0f, 0f))
            .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 28.dp).windowInsetsPadding(WindowInsets.navigationBars),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (kinds.isEmpty()) {
            BoardLine(stringResource(R.string.park_nothing_marked), boardStyle(13f, 700, INK, 17f))
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in kinds) KindChip(k, k == kind, park.pois.count { it.kind == k }, park.pois.firstOrNull { it.kind == k }) { onKind(k) }
            }
        }
        current?.let { c ->
            DetailCard(c, park, here, today, onGo = {
                val l = c.place.location
                nav.go(Dest.Route(l.lat, l.lon, Localise.name(c.place.tags, null), mode = "WALK"))
            })
        }
        val rules = remember(park) { rulesOf(park.place.tags) }
        if (rules.isNotEmpty()) RulesRow(rules)
        TrailsRow(park, here, nav)
        NowRow(park, today, nav)
        FieldNotes(park, here, today, nav)
        BoardPara(stringResource(R.string.park_sources), boardStyle(10f, 400, 0xFF7A8A7E, 13f).copy(textAlign = TextAlign.Center), Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

/** One of the park's rules as mapped: its symbol (struck through for a "no"), and the words under it. */
private class Rule(val symbol: String, val words: Int, val struck: Boolean = false)

/** The rules the park's own tags give: dogs, fires, drones, an entry fee, step-free paths; none when none are mapped. */
private fun rulesOf(t: Map<String, String>): List<Rule> = buildList {
    when (t["dog"]) {
        "leashed" -> add(Rule("wild.dogs-on-lead", R.string.park_rule_dogs_lead))
        "no" -> add(Rule("fact.dog", R.string.park_rule_no_dogs, struck = true))
        "yes", "unleashed" -> add(Rule("fact.dog", R.string.park_rule_dogs))
    }
    if (t["drone"] == "no") add(Rule("wild.no-drones", R.string.park_rule_no_drones))
    when (t["openfire"]) {
        "no" -> add(Rule("fact.trending", R.string.park_rule_no_fires, struck = true))
        "designated" -> add(Rule("fact.trending", R.string.park_rule_fire_rings))
    }
    if (t["fee"] == "yes") add(Rule("ui.coins", R.string.park_rule_fee))
    if (t["wheelchair"] == "yes") add(Rule("fact.wheelchair", R.string.picnic_step_free))
}

/** The Wild board's rules: 22 px symbols in its dark green, the words in 10 px at 800 under each, side by side. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RulesRow(rules: List<Rule>) {
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 4) {
        for (r in rules) {
            val words = stringResource(r.words)
            Column(Modifier.width(88.dp).semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Glyph(r.symbol, Color(INK.toInt()), 22.dp)
                    if (r.struck) Canvas(Modifier.size(24.dp)) {
                        drawLine(Color(SHEET.toInt()), Offset(2f * density, 22f * density), Offset(22f * density, 2f * density), 5f * density, StrokeCap.Round)
                        drawLine(Color(INK.toInt()), Offset(3f * density, 21f * density), Offset(21f * density, 3f * density), 2.2f * density, StrokeCap.Round)
                    }
                }
                BoardPara(words, boardStyle(10f, 800, RULE_INK, 13f).copy(textAlign = TextAlign.Center), lines = 2)
            }
        }
    }
}

/**
 * A row of the Wild board: 66 px, white, radius 18; the 42 px tile in its colour with a white symbol, the title and a line
 * under it, and a figure or an arrow at the end.
 */
@Composable
private fun WildRow(tile: Long, symbol: String, title: String, line: String?, end: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 66.dp).boardBox(0xFFFFFFFF, corners(18f)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).boardBox(tile, corners(12f)), contentAlignment = Alignment.Center) { Glyph(symbol, Color.White, 22.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BoardLine(title, boardStyle(15f, 800, INK, 19.5f))
            line?.let { BoardLine(it, boardStyle(12f, 400, SLATE, 15.5f, tnum = true)) }
        }
        if (end != null) BoardLine(end, boardStyle(14f, 900, INK, 18f, tnum = true)) else Glyph("ui.arrow-right", Color(INK.toInt()), 16.dp)
    }
}

/** "9 km" from a route's `distance` (kilometres unless it says miles); null when it gives none. */
internal fun trailKm(tags: Map<String, String>): Double? {
    val m = Regex("""^\s*([0-9]+(?:[.,][0-9]+)?)\s*(km|mi)?""").find(tags["distance"].orEmpty()) ?: return null
    val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    return if (m.groupValues[2] == "mi") n * 1.609 else n
}

/** "12.5 km": a trail's length as its signpost says it, a tenth to a hundred kilometres, whole beyond, in [locale]'s digits. */
internal fun kmText(km: Double, locale: Locale = Locale.getDefault()): String =
    java.text.NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = if (km >= 100) 0 else 1 }.format(km) + " km"

/** The trail a park's row names: a loop first, then one with its length mapped, then the nearest to you. */
internal fun featuredTrail(trails: List<Place>, here: LatLon): Place? =
    trails.sortedWith(compareByDescending<Place> { it.tags["roundtrip"] == "yes" }.thenByDescending { trailKm(it.tags) != null }.thenBy { Geo.distance(here, it.location) }).firstOrNull()

/** The park's named trails: how many, and one of them by name and length; it opens that trail. */
@Composable
private fun TrailsRow(park: Park, here: LatLon, nav: Navigator) {
    val services = LocalServices.current
    val best = remember(park, here) { featuredTrail(park.trails, here) } ?: return
    val lang = remember { Locale.getDefault().language }
    val km = trailKm(best.tags)?.let { kmText(it) }
    val line = listOfNotNull(Localise.name(best.tags, lang), km).joinToString(" · ")
    WildRow(0xFF6E4A2A, "place.signpost", pluralStringResource(R.plurals.park_trails, park.trails.size, park.trails.size), line, null) {
        services.remember(best)
        nav.go(Dest.Place.page(best))
    }
}

/** The one thing of the year worth coming for now: a park's leaves, blossom or birds; a reserve's and a national park's more. */
internal fun natureNow(park: Park, today: LocalDate): ActivityNow? {
    val wanted = when (park.kind) {
        GreenKind.PARK -> buildList {
            if (park.shape.trees.isNotEmpty() || park.trees.isNotEmpty()) { add("foliage"); add("blossom") }
            if (park.birds.isNotEmpty()) add("birds")
        }
        GreenKind.RESERVE -> listOf("birds", "foliage", "mushrooms", "blossom")
        GreenKind.NATIONAL -> listOf("birds", "foliage", "mushrooms", "stars", "blossom")
    }
    val at = park.place.location
    val rank = { p: Phase? -> when (p) { Phase.LAST -> 0; Phase.PEAK -> 1; Phase.ON -> 2; else -> 9 } }
    return YearCalendar.now(FoodRegion.of(at), today, at.lat < 0)
        .filter { it.activity.id in wanted && rank(it.phase) < 9 }
        .sortedWith(compareBy({ rank(it.phase) }, { wanted.indexOf(it.activity.id) }))
        .firstOrNull()
}

/** The row for what the year brings the park now, opening its notes there, the season's markets, or the sky. */
@Composable
private fun NowRow(park: Park, today: LocalDate, nav: Navigator) {
    val a = remember(park, today) { natureNow(park, today) } ?: return
    val end = Month.of(a.span.end).getDisplayName(TextStyle.FULL, Locale.getDefault())
    val line = when (a.phase) {
        Phase.LAST -> stringResource(R.string.year_phase_last, end)
        Phase.PEAK -> stringResource(R.string.year_phase_peak, end)
        else -> stringResource(R.string.year_phase_on, end)
    }
    val symbol = when (a.activity.id) { "foliage" -> "nature.season"; "birds" -> "nature.heron"; "mushrooms" -> "fun.mushroom"; "stars" -> "ui.star"; else -> "nature.flower" }
    val at = park.place.location
    WildRow(a.activity.colour, symbol, stringResource(R.string.park_now, activityName(a.activity.id)), line, null) {
        nav.go(
            when (a.activity.id) {
                "birds" -> Dest.Nature("birds", at.lat, at.lon)
                "foliage", "blossom" -> Dest.Nature("trees", at.lat, at.lon)
                else -> a.activity.dest ?: Dest.Year
            },
        )
    }
}

/** The park's field notes: its notable trees and bird places as the notebook writes them, and the notebook there. */
@Composable
private fun FieldNotes(park: Park, here: LatLon, today: LocalDate, nav: Navigator) {
    val services = LocalServices.current
    val clock = appClock()
    val notes = remember(park, here, today) { NatureNotes.parkNotes(park, here, today) }
    if (notes.isEmpty()) return
    val at = park.place.location
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BoardLine(stringResource(R.string.nature_title), boardStyle(28f, 700, 0xFF2F4A2A, 30f, CAVEAT), Modifier.weight(1f).semantics { heading() }, overflow = TextOverflow.Visible)
            BoardLine(
                stringResource(R.string.park_notes_more), boardStyle(12f, 800, 0xFF2F5A2A, 15.5f),
                Modifier.clickable(role = Role.Button) { nav.go(Dest.Nature(notes.first().kind.id, at.lat, at.lon)) }.padding(vertical = 6.dp, horizontal = 2.dp),
            )
        }
        for ((i, n) in notes.withIndex()) NoteEntry(n, i, clock.instant(), clock.zone, onClick = { services.remember(n.place); nav.go(Dest.Place.page(n.place)) })
    }
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
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = "$name, $count"; selected = on }
            .padding(start = if (wc) 12.dp else if (on) 8.dp else 0.dp, end = if (on || wc) 12.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (wc) BoardLine("WC", boardStyle(13f, 800, if (on) 0xFFFFFFFF else k.colour, 17f))
        else Glyph(k.symbol, if (on) Color.White else c, if (k == ParkKind.WATER) 17.dp else 18.dp)
        if (label != null) BoardLine(label, boardStyle(13f, 700, 0xFFFFFFFF, 17f, tnum = true))
    }
}

/**
 * The card for the chosen thing: radius 26, 14 by 16 px in, 14 apart: its 56 px disc, the name (a camp site's or a visitor
 * centre's kind when it has none), its facts 8 apart (today's hours first) and what is near, and the walk on the dark disc.
 */
@Composable
private fun DetailCard(poi: ParkPoi, park: Park, here: LatLon, today: LocalDate, onGo: () -> Unit) {
    val t = poi.place.tags
    val lang = remember { Locale.getDefault().language }
    val own = Localise.name(t, lang)
    val wild = poi.kind == ParkKind.SLEEP || poi.kind == ParkKind.VISITOR
    val name = own ?: (if (wild) Presets.match(t)?.name(lang) else null) ?: kindName(poi.kind)
    val facts = buildList {
        if (own != null && poi.kind == ParkKind.SLEEP) Presets.match(t)?.name(lang)?.let { add(it) }
        Parkland.todaysSpan(t["opening_hours"], today)?.let { add(it) }
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
        Modifier.fillMaxWidth().heightIn(min = 96.dp).boardBox(0xFFFFFFFF, corners(26f)).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).boardBox(poi.kind.colour, corners(28f)), contentAlignment = Alignment.Center) {
            if (poi.kind.symbol == "WC") BoardLine("WC", boardStyle(17f, 800, 0xFFFFFFFF, 22f)) else Glyph(poi.kind.symbol, Color.White, 30.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BoardPara(name, boardStyle(17f, 800, 0xFF1C2A1E, 22f), lines = 2)
            if (facts.isNotEmpty()) GapLine(8f) { for (f in facts) BoardLine(f, boardStyle(13f, 400, 0xFF4E5E50, 17f, tnum = true), overflow = TextOverflow.Clip) }
            if (near.isNotEmpty()) BoardLine(near.joinToString(" · "), boardStyle(12f, 400, 0xFF4E5E50, 15.5f))
        }
        Box(Modifier.size(56.dp).boardBox(Board.css(), corners(28f)).clickable(role = Role.Button, onClick = onGo).semantics { contentDescription = walkLabel }, contentAlignment = Alignment.Center) {
            BoardLine(walk, boardStyle(15f, 800, 0xFFFFFFFF, 19.5f, tnum = true))
        }
    }
}
