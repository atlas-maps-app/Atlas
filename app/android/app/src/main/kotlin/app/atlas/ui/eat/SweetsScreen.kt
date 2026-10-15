package app.atlas.ui.eat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.shadow.Shadow as BoxShadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.InterFamily
import kotlin.math.hypot

/* The Sweets board's pinks (`canvas/project/Sweets.dc.html`). */
private val RASPBERRY = Color(0xFFB2345A)
private val WINE = Color(0xFF8F2A4A)
private val PLUM = Color(0xFF4A2034)
private val CREAM = Color(0xFFFFF7F2)
private val AWNING = Color(0xFFF59CB5)
private val AWNING_LIGHT = Color(0xFFFFF3EE)

/**
 * Sweets, as on the Sweets board: a striped awning, a frosted gelato case whose tubs count ice cream, bakeries,
 * pastry, chocolate, donuts, crêpes, frozen yoghurt and sweets nearby, pills for vegan, gluten-free, late, seats and
 * kids, and the places with a scoop of their colour.
 */
@Composable
fun SweetsScreen(nav: Navigator) {
    val ctx = rememberEatContext()
    var tub by rememberSaveable { mutableStateOf<Tub?>(null) }
    var pills by rememberSaveable { mutableStateOf(emptySet<SweetPill>()) }
    val pad by ctx.repo.shortlist.items.collectAsStateWithLifecycle()
    val worked = rememberWorked(ctx.area, ctx.at, tub, pills) { SweetsModel.case(ctx.area, ctx.at, tub, pills) }
    val case = worked.value

    Box(Modifier.fillMaxSize().background(CREAM)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = boardTop(132.dp)), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom) {
                    Text(
                        stringResource(R.string.eat_sweets),
                        Modifier.semantics { heading() }.alignByBaseline(),
                        style = TextStyle(fontFamily = EatType.fredoka, fontWeight = FontWeight.Bold, fontSize = 46.sp, lineHeight = 46.sp, color = RASPBERRY),
                    )
                    Text(
                        case?.open?.toString() ?: "…",
                        Modifier.alignByBaseline(),
                        style = TextStyle(fontFamily = EatType.fredoka, fontWeight = FontWeight.Medium, fontSize = 18.sp, color = WINE),
                    )
                }
            }
            item { GelatoCase(case?.tubs.orEmpty(), tub, Modifier.padding(top = 18.dp).bleed(2.dp)) { t -> tub = if (tub == t) null else t } }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (s in SweetPill.entries) {
                        val n = case?.pills?.get(s) ?: 0
                        SweetPillButton(s, stringResource(sweetPillName(s)), on = s in pills, enabled = n > 0) { pills = if (s in pills) pills - s else pills + s }
                    }
                }
            }
            items(case?.spots.orEmpty(), key = { it.place.key }) { s ->
                SweetRow(s, ctx, Modifier.padding(top = 10.dp)) { ctx.open(nav, s.place.place) }
            }
            item {
                if (case != null && case.spots.isEmpty() && !ctx.area.loading) {
                    Text(
                        stringResource(if (ctx.area.places.isEmpty()) R.string.eat_none_here else R.string.eat_sweets_none),
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        style = TextStyle(fontFamily = EatType.fredoka, fontSize = 15.sp, color = PLUM.copy(alpha = 0.7f)),
                        textAlign = TextAlign.Center,
                    )
                }
                if (ctx.area.waiting || !worked.fresh) Looking(PLUM.copy(alpha = 0.6f))
                if (ctx.area.error != null && ctx.area.places.isEmpty()) EatProblem(ctx.area)
                sourceLine(ctx.area)?.let {
                    Text(it, Modifier.fillMaxWidth().padding(top = 16.dp), style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp), color = PLUM.copy(alpha = 0.55f), textAlign = TextAlign.Center)
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(boardTop(104.dp) + 18.dp)) { drawAwning(18.dp.toPx()) }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = boardTop(44.dp))) {
            PaleRound(EatGlyphs.backRound, stringResource(R.string.kit_back), 20) { nav.back() }
            Spacer(Modifier.weight(1f))
            Box {
                PaleRound(EatGlyphs.padRound, stringResource(R.string.eat_pad), 19) { nav.go(Dest.Eat("order")) }
                if (pad.isNotEmpty()) {
                    Box(Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(Color(0xFFE8622C)), contentAlignment = Alignment.Center) {
                        Text(pad.size.toString(), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.White))
                    }
                }
            }
        }
    }
}

/** A white round button of the awning (44 px, 88 % white, a pink shadow). */
@Composable
private fun PaleRound(glyph: BoardGlyph, description: String, sizeDp: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .boxShadow(CircleShape, 1f, 3f, 10f, Color(0x2E781E3C))
            .clip(CircleShape)
            .background(Color(0xE0FFFFFF))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { BoardIcon(glyph, RASPBERRY, sizeDp.dp) }
}

/**
 * The awning: pink and white stripes 34 px wide down to [hem] above the canvas foot, darkening at the hem, then the
 * board's scalloped edge (teeth 34 px wide, 14 deep).
 */
private fun DrawScope.drawAwning(hem: Float) {
    val k = density
    val body = size.height - hem
    var x = 0f
    var i = 0
    while (x < size.width) {
        drawRect(if (i % 2 == 0) AWNING else AWNING_LIGHT, Offset(x, 0f), Size(34 * k, body))
        x += 34 * k
        i++
    }
    drawRect(Brush.verticalGradient(listOf(Color(0x00781E3C), Color(0x1F781E3C)), body - 16 * k, body), Offset(0f, body - 16 * k), Size(size.width, 16 * k))
    val top = body - 4 * k
    val p = Path().apply {
        moveTo(0f, top)
        lineTo(size.width, top)
        lineTo(size.width, top + 4 * k)
        var px = size.width
        while (px > 0f) {
            cubicTo(px - 8.5f * k, top + 4 * k, px - 17 * k, top + 18 * k, px - 17 * k, top + 18 * k)
            cubicTo(px - 17 * k, top + 18 * k, px - 25.5f * k, top + 4 * k, px - 34 * k, top + 4 * k)
            px -= 34 * k
        }
        close()
    }
    drawPath(p, AWNING)
}

/** The frosted display case (330 px): eight steel tubs in two rows, each with its sweet and a label "Gelato 9". */
@Composable
private fun GelatoCase(counts: Map<Tub, Int>, chosen: Tub?, modifier: Modifier, onTub: (Tub) -> Unit) {
    val outer = RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp, bottomStart = 14.dp, bottomEnd = 14.dp)
    val inner = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp, bottomStart = 8.dp, bottomEnd = 8.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(330.dp)
            .boxShadow(outer, 1f, 14f, 28f, Color(0x2E3C283C))
            .clip(outer)
            .background(Brush.verticalGradient(listOf(Color(0xFFE4ECEF), Color(0xFFCBD6DB))))
            .padding(12.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .clip(inner)
                .background(Color(0xFFF2FAF7))
                .innerShadow(inner, BoxShadow(radius = 8.dp, color = Color(0x1F3C5A5A), offset = DpOffset(0.dp, 6.dp)))
                .padding(start = 10.dp, end = 10.dp, top = 30.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (row in Tub.entries.chunked(4)) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in row) TubCell(t, counts[t] ?: 0, on = chosen == t, modifier = Modifier.weight(1f).fillMaxSize()) { onTub(t) }
                }
            }
        }
        // The glass: a soft band of light across the case, turned 24°.
        Canvas(Modifier.fillMaxSize().clip(inner)) {
            rotate(24f, Offset(35.dp.toPx(), 170.dp.toPx())) {
                drawRect(
                    Brush.horizontalGradient(listOf(Color(0x00FFFFFF), Color(0x73FFFFFF), Color(0x00FFFFFF)), -20.dp.toPx(), 90.dp.toPx()),
                    Offset(-20.dp.toPx(), -40.dp.toPx()), Size(110.dp.toPx(), 420.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun TubCell(t: Tub, n: Int, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val name = stringResource(tubName(t))
    Box(
        modifier.selectable(on, enabled = n > 0 || on, role = Role.Tab, onClick = onClick).semantics { contentDescription = "$name $n" },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(Modifier.fillMaxWidth().height(if (on) 100.dp else 64.dp).alpha(if (n > 0 || on) 1f else 0.5f)) { drawTub(t, on) }
        Text(
            "$name $n",
            Modifier
                .align(if (on) Alignment.TopStart else Alignment.BottomCenter)
                .padding(bottom = if (on) 0.dp else 50.dp, top = if (on) 0.dp else 0.dp)
                .then(if (on) Modifier else Modifier.boxShadow(CircleShape, 1f, 1f, 3f, Color(0x1F000000)))
                .clip(CircleShape)
                .background(if (on) RASPBERRY else Color.White)
                .padding(horizontal = 7.dp, vertical = 3.dp),
            style = TextStyle(fontFamily = EatType.fredoka, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = if (on) Color.White else PLUM.copy(alpha = if (n > 0) 1f else 0.45f)),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * A steel tub with its sweet heaped above the rim, the board's drawing on an 84 × 70 box; the chosen tub has a scoop
 * lifted out of it on a spoon (the board's 84 × 110 drawing).
 */
private fun DrawScope.drawTub(t: Tub, chosen: Boolean) {
    val vbH = if (chosen) 110f else 70f
    val s = minOf(size.width / 84f, size.height / vbH)
    val dx = (size.width - 84f * s) / 2f
    val dy = size.height - vbH * s
    val lift = if (chosen) 40f else 0f
    withTransform({ translate(dx, dy); scale(s, s, Offset.Zero) }) {
        if (chosen) {
            drawCircle(Color(t.fill), 13f, Offset(46f, 18f))
            drawPath(Path().apply { moveTo(36f, 14f); cubicTo(40f, 10f, 48f, 10f, 52f, 16f) }, Color.White.copy(alpha = 0.6f), style = Stroke(2f))
            drawLine(Color(0xFFAEB6BE), Offset(56f, 30f), Offset(68f, 10f), 3f, StrokeCap.Round)
        }
        drawPath(Path().apply { moveTo(6f, 24f + lift); lineTo(78f, 24f + lift); lineTo(73f, 66f + lift); lineTo(11f, 66f + lift); close() }, Color(0xFFC9D0D6))
        val heap = Path().apply {
            moveTo(6f, 24f + lift)
            cubicTo(12f, 10f + lift, 22f, 18f + lift, 28f, 12f + lift)
            cubicTo(34f, 6f + lift, 40f, 18f + lift, 46f, 12f + lift)
            cubicTo(52f, 6f + lift, 60f, 4f + lift, 66f, 12f + lift)
            cubicTo(72f, 20f + lift, 78f, 16f + lift, 78f, 24f + lift)
            close()
        }
        drawPath(heap, Color(t.fill))
        fun stroke(c: Long, w: Float, vararg seg: Float) {
            var i = 0
            while (i + 3 < seg.size) {
                drawLine(Color(c), Offset(seg[i], seg[i + 1] + lift), Offset(seg[i + 2], seg[i + 3] + lift), w, StrokeCap.Round)
                i += 4
            }
        }
        when (t) {
            Tub.GELATO -> {
                drawPath(Path().apply { moveTo(16f, 18f + lift); cubicTo(22f, 14f + lift, 26f, 18f + lift, 30f, 15f + lift); moveTo(50f, 16f + lift); cubicTo(55f, 13f + lift, 59f, 16f + lift, 62f, 14f + lift) }, Color(0xFF9CC06D), style = Stroke(2f))
            }
            Tub.BAKERY -> drawPath(Path().apply { moveTo(20f, 18f + lift); cubicTo(24f, 15f + lift, 28f, 17f + lift, 30f, 15f + lift); moveTo(48f, 16f + lift); cubicTo(52f, 14f + lift, 56f, 16f + lift, 58f, 14f + lift) }, Color(0xFFB7803D), style = Stroke(2f))
            Tub.PASTRY -> { drawCircle(Color(0xFFE35C7A), 3f, Offset(30f, 16f + lift)); drawCircle(Color(0xFFE35C7A), 3f, Offset(52f, 14f + lift)) }
            Tub.CHOCO -> stroke(0xFFA86A45, 2.5f, 18f, 18f, 24f, 14f, 44f, 16f, 50f, 12f, 60f, 18f, 64f, 15f)
            Tub.DONUTS -> {
                stroke(0xFFFFFFFF, 2.4f, 16f, 18f, 19f, 19f, 28f, 12f, 30f, 14f, 40f, 16f, 43f, 15f, 54f, 12f, 55f, 15f, 64f, 18f, 67f, 17f)
                stroke(0xFF6EC6E8, 2.4f, 22f, 20f, 24f, 18f, 48f, 18f, 50f, 20f)
            }
            Tub.CREPES -> drawPath(Path().apply { moveTo(16f, 22f + lift); cubicTo(26f, 16f + lift, 36f, 16f + lift, 46f, 22f + lift); cubicTo(56f, 28f + lift, 64f, 26f + lift, 72f, 20f + lift) }, Color(0xFFC99A3A), style = Stroke(2f))
            Tub.FROYO -> {
                drawCircle(Color(0xFF7B4DFF), 3.2f, Offset(26f, 16f + lift))
                drawCircle(Color(0xFFD93A5A), 3.2f, Offset(46f, 12f + lift))
                drawCircle(Color(0xFF7B4DFF), 3f, Offset(60f, 18f + lift))
            }
            Tub.CANDY -> {
                stroke(0xFFF2A71B, 2.4f, 14f, 20f, 17f, 17f, 22f, 14f, 25f, 15f, 32f, 18f, 34f, 15f, 42f, 12f, 45f, 14f, 52f, 16f, 54f, 19f, 60f, 12f, 63f, 13f, 68f, 18f, 70f, 16f)
                stroke(0xFF2E5BFF, 2.4f, 18f, 14f, 20f, 16f, 36f, 14f, 39f, 13f, 48f, 18f, 46f, 20f, 64f, 16f, 66f, 13f)
            }
        }
    }
}

/** A round pill under the case (60 × 44): green and ringed when on, pink when off. */
@Composable
private fun SweetPillButton(s: SweetPill, description: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val ink = (if (on) Color(0xFF2F6B34) else WINE).copy(alpha = if (enabled || on) 1f else 0.4f)
    Box(
        Modifier
            .size(width = 60.dp, height = 44.dp)
            .clip(CircleShape)
            .background(if (on) Color(0xFFD6F0DC) else Color(0xFFFBEBEF))
            .then(if (on) Modifier.border(2.dp, Color(0xFF7CC08A), CircleShape) else Modifier)
            .toggleable(on, enabled = enabled || on, role = Role.Switch) { onClick() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when (s) {
            SweetPill.VEGAN -> BoardIcon(EatGlyphs.leafPill, ink, 20.dp)
            SweetPill.GLUTEN_FREE -> Text("GF", style = TextStyle(fontFamily = EatType.fredoka, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = ink))
            SweetPill.LATE -> BoardIcon(EatGlyphs.moonFilled, ink, 19.dp)
            SweetPill.SEATS -> BoardIcon(EatGlyphs.bench, ink, 20.dp)
            SweetPill.KIDS -> BoardIcon(EatGlyphs.child, ink, 20.dp)
        }
    }
}

/** A place with a scoop of its tub's colour (76 px): name, "3′ → 23:00 · vegan" and its rating if any. */
@Composable
private fun SweetRow(s: SweetSpot, ctx: EatContext, modifier: Modifier, onClick: () -> Unit) {
    val closed = s.openness == Openness.CLOSED
    val shape = RoundedCornerShape(38.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(76.dp)
            .alpha(if (closed) 0.7f else 1f)
            .boxShadow(shape, 1f, 4f, 14f, Color(0x1A781E3C))
            .clip(shape)
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(Modifier.size(56.dp)) {
            val (light, mid, dark) = s.tub.scoop
            val c = Offset(size.width * 0.35f, size.height * 0.3f)
            drawCircle(Brush.radialGradient(0f to Color(light), 0.6f to Color(mid), 1f to Color(dark), center = c, radius = hypot(size.width * 0.65f, size.height * 0.7f)))
            drawArc(Color(0x14000000), 20f, 140f, false, Offset(2.dp.toPx(), 0f), Size(size.width - 4.dp.toPx(), size.height - 2.dp.toPx()), style = Stroke(4.dp.toPx()))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.place.name, style = TextStyle(fontFamily = EatType.fredoka, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = PLUM), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val meta = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, color = WINE)
                Text("${s.place.walkMinutes}′", style = meta)
                when {
                    s.place.alwaysOpen -> Text("24/7", style = meta)
                    s.closes != null -> Text("→ " + s.closes.format(HM_PADDED), style = meta)
                    closed -> s.place.opensAt(ctx.at, ctx.area.hours)?.let { Text(stringResource(R.string.eat_opens_at, opensText(OpenSay.Opens(it, it.toLocalDate() == ctx.at.toLocalDate()), ctx.lang)), style = meta) }
                }
                if (s.vegan) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        BoardIcon(EatGlyphs.leafKey, Color(0xFF2F6B34), 12.dp, contentDescription = stringResource(R.string.eat_diet_vegan))
                        if (s.allVegan) Text(stringResource(R.string.eat_sweets_all_vegan), style = meta)
                    }
                }
            }
        }
        ctx.ratings.stars(s.place.key)?.let { Text(starsText(it), style = TextStyle(fontFamily = EatType.fredoka, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = PLUM)) }
    }
}

private fun tubName(t: Tub): Int = when (t) {
    Tub.GELATO -> R.string.eat_tub_gelato
    Tub.BAKERY -> R.string.eat_cafe_bakery
    Tub.PASTRY -> R.string.eat_tub_pastry
    Tub.CHOCO -> R.string.eat_tub_choco
    Tub.DONUTS -> R.string.eat_tub_donuts
    Tub.CREPES -> R.string.eat_tub_crepes
    Tub.FROYO -> R.string.eat_tub_froyo
    Tub.CANDY -> R.string.eat_tub_candy
}

private fun sweetPillName(s: SweetPill): Int = when (s) {
    SweetPill.VEGAN -> R.string.eat_diet_vegan
    SweetPill.GLUTEN_FREE -> R.string.eat_diet_gluten_free
    SweetPill.LATE -> R.string.eat_sweets_late
    SweetPill.SEATS -> R.string.eat_sweets_seats
    SweetPill.KIDS -> R.string.eat_wish_kids
}
