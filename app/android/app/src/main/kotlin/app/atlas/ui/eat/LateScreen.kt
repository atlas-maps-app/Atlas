package app.atlas.ui.eat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssRadial

/* The Street board's night (`canvas/project/Street.dc.html`). */
private val NIGHT = Color(0xFF0E0C14)
private val PALE = Color(0xFFF4F1FF)
private val CHIP_OFF = Color(0xFFBDB6CF)
private val CARD_INK = Color(0xFF14111C)
private val CARD_META = Color(0xFF4A4458)
private val CARD_TINTS = listOf(Color(0xFFFFFFFF), Color(0xFFFFF6C9), Color(0xFFD9FAFF))
private val CARD_TILTS = floatArrayOf(-1.5f, 1f, -0.5f)

/* The board's type: Tilt Neon for the tubes, Geist up to 800 for the cards. */
private val PILL = cssText("Geist", 13f, 600, lineHeight = 17f, tnum = true)
private val CARD_NAME = cssText("Geist", 19f, 800, colour = 0xFF14111C, lineHeight = 24f, spacing = -0.19f)
private val CARD_META_TEXT = cssText("Geist", 13f, 600, colour = 0xFF4A4458, lineHeight = 17f, tnum = true)
private val CARD_STARS = cssText("Geist", 20f, 800, colour = 0xFF14111C, lineHeight = 25f, tnum = true)

/**
 * Late bites, as on the Street board: neon on a dark brick wall. The count and "till 3:00" come from the opening
 * hours of kitchens open past midnight; the board's nine neon tiles count them by bite; chips narrow them; the cards
 * are tinted like light from the tubes.
 */
@Composable
fun LateScreen(nav: Navigator) {
    val ctx = rememberEatContext()
    LightStatusIcons()
    var bite by rememberSaveable { mutableStateOf<Bite?>(null) }
    var chips by rememberSaveable { mutableStateOf(emptySet<LateChip>()) }
    var sort by rememberSaveable { mutableStateOf(LateSort.NEAREST) }
    val pad by ctx.repo.shortlist.items.collectAsStateWithLifecycle()
    val worked = rememberWorked(ctx.area, ctx.at, bite, chips, sort) { LateModel.wall(ctx.area, ctx.at, bite, chips, sort) }
    val wall = worked.value

    Box(Modifier.fillMaxSize().background(NIGHT).drawBehind { drawBrickWall() }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomRoom(110.dp))) {
            item {
                // The sign at 92 px: "Late Bites" (62 on 62), 6 px, the count (20 on 25.5).
                Column(Modifier.fillMaxWidth().padding(top = boardTop(92.dp)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NeonText(
                        stringResource(R.string.eat_late_title), 62f, Color(0xFFFFE3F7),
                        listOf(4f to Color(0xFFFF4FD8), 12f to Color(0xFFFF4FD8), 28f to Color(0xFFC2189B), 48f to Color(0x99C2189B)), 1f,
                        Modifier.semantics { heading() },
                    )
                    val line = wall?.let { w ->
                        when {
                            !w.hoursKnown -> stringResource(R.string.eat_late_no_hours)
                            w.till != null -> stringResource(R.string.eat_late_open_till, w.count, w.till.format(HM))
                            else -> stringResource(R.string.eat_late_open, w.count)
                        }
                    } ?: stringResource(R.string.kit_loading)
                    NeonText(line, 20f, Color(0xFFE2FCFF), listOf(4f to Color(0xFF36E3FF), 12f to Color(0xFF0FB8E0)), 1f, lineHeight = 25.5f, tnum = true)
                }
            }
            // The wall's nine tiles at 200 px: three columns, 8 px apart, 88 px high.
            item {
                Column(Modifier.fillMaxWidth().padding(top = 14.5.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (row in wall?.tiles.orEmpty().chunked(3)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((b, n) in row) NeonTile(b, n, on = bite == b, modifier = Modifier.weight(1f)) { bite = if (bite == b) null else b }
                        }
                    }
                }
            }
            // The chips at 496 px, spread across.
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    val two = LateChip.TWO in chips
                    NightPill(
                        on = true, enabled = true, description = if (two) "2:00+" else stringResource(R.string.eat_late_midnight), start = 10.dp,
                        onClick = { chips = if (two) chips - LateChip.TWO else chips + LateChip.TWO },
                    ) {
                        Symbol("ui.moon", tint = Color(0xFFE2FCFF), size = 15.dp)
                        CssLine(if (two) "2:00+" else stringResource(R.string.eat_late_midnight), PILL, color = Color(0xFFE2FCFF))
                    }
                    for (c in listOf(LateChip.ALWAYS, LateChip.DRIVE_THROUGH, LateChip.DELIVERY, LateChip.CASH)) {
                        val n = wall?.chipCounts?.get(c) ?: 0
                        val on = c in chips
                        val colour = if (on) Color(0xFFE2FCFF) else CHIP_OFF.copy(alpha = if (n > 0) 1f else 0.4f)
                        NightPill(on = on, enabled = n > 0, description = lateChipText(c), round = c != LateChip.ALWAYS, onClick = { chips = if (on) chips - c else chips + c }) {
                            when (c) {
                                LateChip.ALWAYS -> CssLine("24/7", PILL, color = colour)
                                LateChip.DRIVE_THROUGH -> Symbol("mode.car.2", tint = colour, size = 18.dp)
                                LateChip.DELIVERY -> Symbol("fact.delivery", tint = colour, size = 18.dp)
                                LateChip.CASH -> Symbol("fact.cash", tint = colour, size = 18.dp)
                                LateChip.TWO -> Unit
                            }
                        }
                    }
                    val sortName = stringResource(if (sort == LateSort.NEAREST) R.string.eat_sort_nearest else R.string.eat_late_sort_latest)
                    Box(
                        Modifier.height(40.dp).cssBox(bg = 0xFFFF4FD8, r = round(40f)).clip(CircleShape)
                            .clickable { sort = if (sort == LateSort.NEAREST) LateSort.LATEST else LateSort.NEAREST }
                            .padding(horizontal = 10.dp)
                            .semantics { contentDescription = sortName },
                        contentAlignment = Alignment.Center,
                    ) { Symbol("ui.sort", tint = Color(0xFF1A0716), size = 14.dp) }
                }
            }
            itemsIndexed(wall?.spots.orEmpty(), key = { _, s -> s.place.key }) { i, s ->
                // The cards from 558 px, 20 px in, 10 apart.
                NeonCard(s, i, ctx, Modifier.padding(top = if (i == 0) 22.dp else 10.dp, start = 4.dp, end = 4.dp)) { ctx.open(nav, s.place.place) }
            }
            item {
                if (wall != null && wall.spots.isEmpty() && !ctx.area.loading) {
                    Text(
                        stringResource(if (ctx.area.places.isEmpty()) R.string.eat_none_here else R.string.eat_late_none),
                        Modifier.fillMaxWidth().padding(vertical = 26.dp),
                        style = cssText("Tilt Neon", 17f, colour = 0xFFE2FCFF, lineHeight = 21.5f, shadow = CssShadow(0f, 0f, 10f, 0f, 0xFF36E3FF)),
                        textAlign = TextAlign.Center,
                    )
                }
                if (ctx.area.waiting || !worked.fresh) Looking(CHIP_OFF)
                if (ctx.area.error != null && ctx.area.places.isEmpty()) EatProblem(ctx.area)
                sourceLine(ctx.area)?.let {
                    Text(it, Modifier.fillMaxWidth().padding(top = 16.dp), style = cssText("Geist", 11f, colour = 0xFF8C8598, lineHeight = 14f, align = TextAlign.Center))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = boardTop(42.dp))) {
            val back = stringResource(R.string.kit_back)
            Box(Modifier.size(44.dp).cssBox(bg = 0x14FFFFFF, r = round(44f)).clip(CircleShape).clickable { nav.back() }.semantics { contentDescription = back }, contentAlignment = Alignment.Center) {
                Symbol("ui.back", tint = PALE, size = 20.dp)
            }
            Spacer(Modifier.weight(1f))
            val padLabel = stringResource(R.string.eat_pad)
            Box {
                Box(Modifier.size(44.dp).cssBox(bg = 0x14FFFFFF, r = round(44f)).clip(CircleShape).clickable { nav.go(Dest.Eat("order")) }.semantics { contentDescription = padLabel }, contentAlignment = Alignment.Center) {
                    Symbol("ui.order", tint = PALE, size = 19.dp)
                }
                if (pad.isNotEmpty()) {
                    Box(Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(Color(0xFFE8622C)), contentAlignment = Alignment.Center) {
                        CssLine(pad.size.toString(), cssText("Geist", 10f, 700, colour = 0xFFFFFFFF, lineHeight = 13f))
                    }
                }
            }
        }
        // The cards slide under the Map pill: the night fades in below them so the pill stays readable on a lit card.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottomRoom(128.dp))
                .background(Brush.verticalGradient(listOf(NIGHT.copy(alpha = 0f), NIGHT.copy(alpha = 0.92f), NIGHT))),
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 30.dp)
                .size(120.dp, 52.dp)
                .cssBox(bg = 0x1AFFFFFF, r = round(52f), shadows = listOf(CssShadow(0f, 0f, 0f, 1f, 0x33FFFFFF, inset = true)))
                .clip(CircleShape)
                .clickable { nav.go(Dest.Eat("map")) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Symbol("tab.map", tint = PALE, size = 18.dp)
            CssLine(stringResource(R.string.eat_map), cssText("Geist", 15f, 600, colour = 0xFFF4F1FF, lineHeight = 19.5f))
        }
    }
}

/** The board's symbol for a bite, with its own drawing box (the SVG's width and height). */
private fun biteArt(b: Bite): Triple<String, Float, Float> = when (b) {
    Bite.TACOS -> Triple("street.taco", 34f, 26f)
    Bite.SLICE -> Triple("food.pizza", 30f, 28f)
    Bite.BURGER -> Triple("food.burger", 30f, 28f)
    Bite.KEBAB -> Triple("street.kebab", 26f, 30f)
    Bite.FALAFEL -> Triple("street.falafel", 30f, 28f)
    Bite.DUMPLING -> Triple("street.dumpling", 32f, 26f)
    Bite.HOT_DOG -> Triple("street.hot-dog", 34f, 22f)
    Bite.CHICKEN -> Triple("street.chicken", 30f, 28f)
    Bite.TRUCKS -> Triple("street.food-truck", 34f, 26f)
}

/** A bite's symbol as a lit tube: a blurred glow of [glow] (CSS drop-shadow 0 0 3 px) under the tube in [tube], in its SVG's box. */
@Composable
private fun NeonGlyph(b: Bite, tube: Color, glow: Color, scale: Float = 1f, modifier: Modifier = Modifier) {
    val (id, w, h) = biteArt(b)
    Box(modifier.size((w * scale).dp, (h * scale).dp), contentAlignment = Alignment.Center) {
        if (glow.alpha > 0f) BoxSymbol(id, w * scale, h * scale, glow, Modifier.blur((3 * 0.866f).dp, BlurredEdgeTreatment.Unbounded))
        BoxSymbol(id, w * scale, h * scale, tube)
    }
}

/** A tile of the wall (88 px): the bite's tube and "Tacos 9"; the chosen one ringed and glowing pink. */
@Composable
private fun NeonTile(b: Bite, n: Int, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tube = Color(b.tube).copy(alpha = if (n > 0 || on) 1f else 0.35f)
    val glow = Color(b.glow).copy(alpha = if (n > 0 || on) 1f else 0.25f)
    val shape = RoundedCornerShape(14.dp)
    val name = stringResource(biteName(b))
    Column(
        modifier
            .height(88.dp)
            .then(
                if (on) Modifier.cssBox(bg = 0xFF150F1A, r = corners(14f), shadows = listOf(CssShadow(0f, 0f, 0f, 1.5f, 0xCCFF4FD8, inset = true), CssShadow(0f, 0f, 20f, 0f, 0x59FF4FD8)))
                else Modifier.cssBox(bg = 0xFF120F18, r = corners(14f), shadows = listOf(CssShadow(0f, 0f, 0f, 1f, 0x14FFFFFF, inset = true))),
            )
            .clip(shape)
            .selectable(on, enabled = n > 0 || on, role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$name $n" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        NeonGlyph(b, tube, glow)
        CssLine("$name $n", cssText("Tilt Neon", 15f, lineHeight = 19f).copy(color = tube, shadow = Shadow(glow, blurRadius = if (on) 6f else 5f)))
    }
}

/** A chip under the wall (40 px): an outlined pill, lit cyan when on. */
@Composable
private fun NightPill(on: Boolean, enabled: Boolean, description: String, round: Boolean = false, start: Dp = 12.dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier
            .height(40.dp)
            .then(if (round) Modifier.width(40.dp) else Modifier)
            .then(
                if (on) Modifier.cssBox(bg = 0x1F36E3FF, r = round(40f), shadows = listOf(CssShadow(0f, 0f, 0f, 1.5f, 0xFF36E3FF, inset = true), CssShadow(0f, 0f, 12f, 0f, 0x5936E3FF)))
                else Modifier.cssBox(r = round(40f), shadows = listOf(CssShadow(0f, 0f, 0f, 1f, 0x2EFFFFFF, inset = true))),
            )
            .clip(CircleShape)
            .toggleable(on, enabled = enabled || on, role = Role.Checkbox) { onClick() }
            .semantics { contentDescription = description }
            .padding(start = if (round) 0.dp else start, end = if (round) 0.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
    ) { content() }
}

/** A late place on a card tinted like neon light (76 px): its bite, name, "→ 3:00 · 4′ · $ · cash" and rating if any. */
@Composable
private fun NeonCard(s: LateSpot, index: Int, ctx: EatContext, modifier: Modifier, onClick: () -> Unit) {
    val tint = CARD_TINTS[index % CARD_TINTS.size]
    val shape = RoundedCornerShape(20.dp)
    val bite = s.bites.firstOrNull()
    Row(
        modifier
            .fillMaxWidth()
            .height(76.dp)
            .rotate(CARD_TILTS[index % CARD_TILTS.size])
            .cssBox(
                bg = tint.css(), r = corners(20f),
                shadows = listOf(CssShadow(0f, 8f, 20f, 0f, 0x80000000)) + if (index == 0) listOf(CssShadow(0f, 0f, 0f, 3f, 0xFFFF4FD8, inset = true)) else emptyList(),
            )
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(CARD_INK), contentAlignment = Alignment.Center) {
            if (bite != null) NeonGlyph(bite, Color(0xFFFFD6F2), Color(0x00000000), scale = 0.88f)
            // A place that is none of the nine bites is not a taco place: the board's cutlery stands for it.
            else Symbol("cat.eat", tint = Color(0xFFFFD6F2), size = 24.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            CssLine(s.place.name, CARD_NAME)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    s.place.alwaysOpen -> CssLine("24/7", CARD_META_TEXT, color = Color(0xFF0A7A93))
                    s.until != null -> CssLine("→ " + s.until.format(HM), CARD_META_TEXT, color = Color(0xFFB0147E))
                }
                CssLine("${s.place.walkMinutes}′", CARD_META_TEXT)
                priceText(ctx.ratings[s.place.key]?.priceLevel)?.let { CssLine(it, CARD_META_TEXT) }
                if (s.place.cashOnly) Symbol("fact.cash", tint = CARD_META, size = 12.dp, contentDescription = stringResource(R.string.eat_world_cash_only))
                if (s.place.yes("drive_through")) Symbol("mode.car.2", tint = CARD_META, size = 13.dp, contentDescription = stringResource(R.string.eat_late_drive_through))
            }
        }
        ctx.ratings.stars(s.place.key)?.let { CssLine(starsText(it), CARD_STARS) }
    }
}

/**
 * The wall: brick from the top down to 520 px (mortar every 22 px across and 46 px down over #2A1A1E darkening to
 * #120E14, the whole at 90 %), fading into the night from 360 px, with the pink haze of the sign over it (an ellipse at
 * 50 % 18 % of the wall).
 */
private fun DrawScope.drawBrickWall() {
    val k = density
    val wallH = minOf(520 * k, size.height)
    val wall = Size(size.width, wallH)
    drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, wall), Paint().apply { alpha = 0.9f }) }
    drawRect(Brush.verticalGradient(listOf(Color(0xFF2A1A1E), Color(0xFF120E14)), 0f, wallH), size = wall)
    var x = 0f
    while (x < size.width) {
        drawRect(Color(0x73000000), Offset(x, 0f), Size(2 * k, wallH))
        x += 46 * k
    }
    var y = 0f
    while (y < wallH) {
        drawRect(Color(0x8C000000), Offset(0f, y), Size(size.width, 2 * k))
        y += 22 * k
    }
    drawIntoCanvas { it.restore() }
    val fadeTop = 360 * k
    drawRect(Brush.verticalGradient(0f to NIGHT.copy(alpha = 0f), 0.3f to NIGHT, 1f to Color(0xFF0B0A10), startY = fadeTop, endY = size.height), Offset(0f, fadeTop), Size(size.width, size.height - fadeTop))
    drawRect(cssRadial(false, 206f * size.width / k / 412f, 93.6f * wallH / k / 520f, 0f to 0x38FF4FD8, 0.5f to 0x00FF4FD8).brush(wall, density), size = wall)
}

@Composable
private fun lateChipText(c: LateChip): String = when (c) {
    LateChip.TWO -> "2:00+"
    LateChip.ALWAYS -> "24/7"
    LateChip.DRIVE_THROUGH -> stringResource(R.string.eat_late_drive_through)
    LateChip.DELIVERY -> stringResource(R.string.eat_late_delivery)
    LateChip.CASH -> stringResource(R.string.eat_world_cash)
}

private fun biteName(b: Bite): Int = when (b) {
    Bite.TACOS -> R.string.eat_bite_tacos
    Bite.SLICE -> R.string.eat_bite_slice
    Bite.BURGER -> R.string.eat_bite_burger
    Bite.KEBAB -> R.string.eat_bite_kebab
    Bite.FALAFEL -> R.string.eat_bite_falafel
    Bite.DUMPLING -> R.string.eat_bite_dumpling
    Bite.HOT_DOG -> R.string.eat_bite_hot_dog
    Bite.CHICKEN -> R.string.eat_bite_chicken
    Bite.TRUCKS -> R.string.eat_bite_trucks
}
