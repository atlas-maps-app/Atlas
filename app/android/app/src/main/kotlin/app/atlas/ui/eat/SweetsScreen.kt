package app.atlas.ui.eat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.cssRadial
import app.atlas.ui.kit.cssRepeatingLinear

/* The Sweets board's pinks (`canvas/project/Sweets.dc.html`). */
private val RASPBERRY = Color(0xFFB2345A)
private val WINE = Color(0xFF8F2A4A)
private val PLUM = Color(0xFF4A2034)
private val CREAM = Color(0xFFFFF7F2)
private val AWNING = Color(0xFFF59CB5)
private val AWNING_LIGHT = Color(0xFFFFF3EE)

/* The board's type: Fredoka (400 to 700), Geist for the figures, and Arial on the tub labels (the board's buttons keep the browser's own face). */
private val TITLE = cssText("Fredoka", 46f, 700, colour = 0xFFB2345A, lineHeight = 46f)
private val TITLE_COUNT = cssText("Fredoka", 18f, 500, colour = 0xFF8F2A4A, lineHeight = 21.5f, tnum = true)
private val TUB_LABEL = cssText("Arial", 12f, 600, lineHeight = 13.5f, tnum = true)
private val PILL_TEXT = cssText("Fredoka", 14f, 600, lineHeight = 17f)
private val ROW_NAME = cssText("Fredoka", 19f, 600, colour = 0xFF4A2034, lineHeight = 23f)
private val ROW_META = cssText("Geist", 13f, colour = 0xFF8F2A4A, lineHeight = 17f, tnum = true)
private val ROW_STARS = cssText("Fredoka", 18f, 600, colour = 0xFF4A2034, lineHeight = 21.5f, tnum = true)

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
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomRoom(40.dp))) {
            item {
                // "Sweets" (46 on 46) at 132 px and its count 10 px on, on one baseline (the count's line 22.5 px down).
                Row(Modifier.fillMaxWidth().padding(top = boardTop(132.dp)), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                    CssLine(stringResource(R.string.eat_sweets), TITLE, Modifier.semantics { heading() })
                    CssLine(case?.open?.toString() ?: "…", TITLE_COUNT, Modifier.padding(top = 22.5.dp))
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
                        style = cssText("Fredoka", 15f, colour = 0xB34A2034, lineHeight = 18f),
                        textAlign = TextAlign.Center,
                    )
                }
                if (ctx.area.waiting || !worked.fresh) Looking(PLUM.copy(alpha = 0.6f))
                if (ctx.area.error != null && ctx.area.places.isEmpty()) EatProblem(ctx.area)
                sourceLine(ctx.area)?.let {
                    Text(it, Modifier.fillMaxWidth().padding(top = 16.dp), style = cssText("Geist", 11f, colour = 0x8C4A2034, lineHeight = 14f, align = TextAlign.Center))
                }
            }
        }
        // The awning: stripes down to 104 px (its hem shaded, inset 0 -10 16), then the scalloped edge from 100 px.
        val awning = boardTop(104.dp)
        Box(
            Modifier.fillMaxWidth().height(awning).cssBox(
                grad = listOf(cssRepeatingLinear(90f, 68f, 0f to 0xFFF59CB5, 34f to 0xFFF59CB5, 34f to 0xFFFFF3EE, 68f to 0xFFFFF3EE)),
                shadows = listOf(CssShadow(0f, -10f, 16f, 0f, 0x1F781E3C, inset = true)),
            ),
        )
        Canvas(Modifier.padding(top = awning - 4.dp).fillMaxWidth().height(22.dp)) { drawScallops() }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = boardTop(44.dp))) {
            PaleRound("ui.back", stringResource(R.string.kit_back), 20) { nav.back() }
            Spacer(Modifier.weight(1f))
            Box {
                PaleRound("ui.order", stringResource(R.string.eat_pad), 19) { nav.go(Dest.Eat("order")) }
                if (pad.isNotEmpty()) {
                    Box(Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(Color(0xFFE8622C)), contentAlignment = Alignment.Center) {
                        CssLine(pad.size.toString(), cssText("Geist", 10f, 700, colour = 0xFFFFFFFF, lineHeight = 13f))
                    }
                }
            }
        }
    }
}

/** A white round button of the awning (44 px, 88 % white, 0 3 10 shadow of raspberry at 18 %). */
@Composable
private fun PaleRound(symbol: String, description: String, sizeDp: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .cssBox(bg = 0xE0FFFFFF, r = round(44f), shadows = listOf(CssShadow(0f, 3f, 10f, 0f, 0x2E781E3C)))
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Symbol(symbol, tint = RASPBERRY, size = sizeDp.dp) }
}

/** The board's scalloped edge (412 × 22): a 4 px band, then teeth 34 px wide and 14 deep, drawn from the right. */
private fun DrawScope.drawScallops() {
    val k = size.width / 412f
    val p = Path().apply {
        moveTo(0f, 0f)
        lineTo(size.width, 0f)
        lineTo(size.width, 4 * k)
        var px = size.width
        while (px > 0f) {
            cubicTo(px - 8.5f * k, 4 * k, px - 17 * k, 18 * k, px - 17 * k, 18 * k)
            cubicTo(px - 17 * k, 18 * k, px - 25.5f * k, 4 * k, px - 34 * k, 4 * k)
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
            .cssBox(grad = listOf(cssLinear(180f, 0f to 0xFFE4ECEF, 1f to 0xFFCBD6DB)), r = CssCorners(40f, 40f, 14f, 14f), shadows = listOf(CssShadow(0f, 14f, 28f, 0f, 0x2E3C283C)))
            .padding(12.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .cssBox(bg = 0xFFF2FAF7, r = CssCorners(30f, 30f, 8f, 8f), shadows = listOf(CssShadow(0f, 6f, 16f, 0f, 0x1F3C5A5A, inset = true)))
                .clip(inner)
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
        // The label: 22 px, 7 px either side; the chosen one raspberry at (6, 36), the others white, 50 px over the cell's foot.
        Box(
            Modifier
                .align(if (on) Alignment.TopStart else Alignment.BottomCenter)
                .padding(start = if (on) 6.dp else 0.dp, top = if (on) 36.dp else 0.dp, bottom = if (on) 0.dp else 50.dp)
                .height(22.dp)
                .then(
                    if (on) Modifier.cssBox(bg = 0xFFB2345A, r = round(22f))
                    else Modifier.cssBox(bg = 0xFFFFFFFF, r = round(22f), shadows = listOf(CssShadow(0f, 1f, 3f, 0f, 0x1F000000))),
                )
                .padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            CssLine("$name $n", TUB_LABEL, color = if (on) Color.White else PLUM.copy(alpha = if (n > 0) 1f else 0.45f))
        }
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
            .then(
                if (on) Modifier.cssBox(bg = 0xFFD6F0DC, r = round(44f), shadows = listOf(CssShadow(0f, 0f, 0f, 2f, 0xFF7CC08A, inset = true)))
                else Modifier.cssBox(bg = 0xFFFBEBEF, r = round(44f)),
            )
            .clip(CircleShape)
            .toggleable(on, enabled = enabled || on, role = Role.Switch) { onClick() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when (s) {
            SweetPill.VEGAN -> Symbol("fact.vegan", tint = ink, size = 20.dp)
            SweetPill.GLUTEN_FREE -> CssLine("GF", PILL_TEXT, color = ink)
            SweetPill.LATE -> Symbol("ui.moon", tint = ink, size = 19.dp)
            SweetPill.SEATS -> Symbol("place.bench", tint = ink, size = 20.dp)
            SweetPill.KIDS -> Symbol("fact.kids", tint = ink, size = 20.dp)
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
            .cssBox(bg = 0xFFFFFFFF, r = round(76f), shadows = listOf(CssShadow(0f, 4f, 14f, 0f, 0x1A781E3C)))
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The scoop: 56 px, lit at 35 % 30 %, shaded inset 0 -4 0 at 8 %.
        val (light, mid, dark) = s.tub.scoop
        Box(
            Modifier.size(56.dp).cssBox(
                grad = listOf(cssRadial(true, 19.6f, 16.8f, 0f to light, 0.6f to mid, 1f to dark)), r = round(56f),
                shadows = listOf(CssShadow(0f, -4f, 0f, 0f, 0x14000000, inset = true)),
            ),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CssLine(s.place.name, ROW_NAME)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CssLine("${s.place.walkMinutes}′", ROW_META)
                when {
                    s.place.alwaysOpen -> CssLine("24/7", ROW_META)
                    s.closes != null -> CssLine("→ " + s.closes.format(HM_PADDED), ROW_META)
                    closed -> s.place.opensAt(ctx.at, ctx.area.hours)?.let { CssLine(stringResource(R.string.eat_opens_at, opensText(OpenSay.Opens(it, it.toLocalDate() == ctx.at.toLocalDate()), ctx.lang)), ROW_META) }
                }
                if (s.vegan) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Symbol("fact.vegan.2", tint = Color(0xFF2F6B34), size = 12.dp, contentDescription = stringResource(R.string.eat_diet_vegan))
                        if (s.allVegan) CssLine(stringResource(R.string.eat_sweets_all_vegan), ROW_META)
                    }
                }
            }
        }
        ctx.ratings.stars(s.place.key)?.let { CssLine(starsText(it), ROW_STARS) }
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
