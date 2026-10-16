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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.InterFamily
import kotlin.math.hypot

/* The Street board's night (`canvas/project/Street.dc.html`). */
private val NIGHT = Color(0xFF0E0C14)
private val PALE = Color(0xFFF4F1FF)
private val CHIP_OFF = Color(0xFFBDB6CF)
private val CARD_INK = Color(0xFF14111C)
private val CARD_META = Color(0xFF4A4458)
private val CARD_TINTS = listOf(Color(0xFFFFFFFF), Color(0xFFFFF6C9), Color(0xFFD9FAFF))
private val CARD_TILTS = floatArrayOf(-1.5f, 1f, -0.5f)

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
                    NeonText(line, 20f, Color(0xFFE2FCFF), listOf(4f to Color(0xFF36E3FF), 12f to Color(0xFF0FB8E0)), 1f)
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (row in wall?.tiles.orEmpty().chunked(3)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((b, n) in row) NeonTile(b, n, on = bite == b, modifier = Modifier.weight(1f)) { bite = if (bite == b) null else b }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    val two = LateChip.TWO in chips
                    NightPill(
                        on = true, enabled = true, description = if (two) "2:00+" else stringResource(R.string.eat_late_midnight),
                        onClick = { chips = if (two) chips - LateChip.TWO else chips + LateChip.TWO },
                    ) {
                        BoardIcon(EatGlyphs.moonFilled, Color(0xFFE2FCFF), 15.dp)
                        Text(if (two) "2:00+" else stringResource(R.string.eat_late_midnight), style = pillText(Color(0xFFE2FCFF)))
                    }
                    for (c in listOf(LateChip.ALWAYS, LateChip.DRIVE_THROUGH, LateChip.DELIVERY, LateChip.CASH)) {
                        val n = wall?.chipCounts?.get(c) ?: 0
                        val on = c in chips
                        val colour = if (on) Color(0xFFE2FCFF) else CHIP_OFF.copy(alpha = if (n > 0) 1f else 0.4f)
                        NightPill(on = on, enabled = n > 0, description = lateChipText(c), round = c != LateChip.ALWAYS, onClick = { chips = if (on) chips - c else chips + c }) {
                            when (c) {
                                LateChip.ALWAYS -> Text("24/7", style = pillText(colour))
                                LateChip.DRIVE_THROUGH -> BoardIcon(EatGlyphs.driveThrough, colour, 18.dp)
                                LateChip.DELIVERY -> BoardIcon(EatGlyphs.scooter, colour, 18.dp)
                                LateChip.CASH -> BoardIcon(EatGlyphs.cash, colour, 18.dp)
                                LateChip.TWO -> Unit
                            }
                        }
                    }
                    val sortName = stringResource(if (sort == LateSort.NEAREST) R.string.eat_sort_nearest else R.string.eat_late_sort_latest)
                    Box(
                        Modifier.height(40.dp).clip(CircleShape).background(Color(0xFFFF4FD8))
                            .clickable { sort = if (sort == LateSort.NEAREST) LateSort.LATEST else LateSort.NEAREST }
                            .padding(horizontal = 10.dp)
                            .semantics { contentDescription = sortName },
                        contentAlignment = Alignment.Center,
                    ) { BoardIcon(EatGlyphs.sort, Color(0xFF1A0716), 14.dp) }
                }
            }
            itemsIndexed(wall?.spots.orEmpty(), key = { _, s -> s.place.key }) { i, s ->
                NeonCard(s, i, ctx, Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp)) { ctx.open(nav, s.place.place) }
            }
            item {
                if (wall != null && wall.spots.isEmpty() && !ctx.area.loading) {
                    Text(
                        stringResource(if (ctx.area.places.isEmpty()) R.string.eat_none_here else R.string.eat_late_none),
                        Modifier.fillMaxWidth().padding(vertical = 26.dp),
                        style = TextStyle(fontFamily = EatType.tiltNeon, fontSize = 17.sp, color = Color(0xFFE2FCFF), shadow = Shadow(Color(0xFF36E3FF), blurRadius = 10f)),
                        textAlign = TextAlign.Center,
                    )
                }
                if (ctx.area.waiting || !worked.fresh) Looking(CHIP_OFF)
                if (ctx.area.error != null && ctx.area.places.isEmpty()) EatProblem(ctx.area)
                sourceLine(ctx.area)?.let {
                    Text(it, Modifier.fillMaxWidth().padding(top = 16.dp), style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp), color = Color(0xFF8C8598), textAlign = TextAlign.Center)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = boardTop(42.dp))) {
            val back = stringResource(R.string.kit_back)
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0x14FFFFFF)).clickable { nav.back() }.semantics { contentDescription = back }, contentAlignment = Alignment.Center) {
                BoardIcon(EatGlyphs.backBold, PALE, 20.dp)
            }
            Spacer(Modifier.weight(1f))
            val padLabel = stringResource(R.string.eat_pad)
            Box {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0x14FFFFFF)).clickable { nav.go(Dest.Eat("order")) }.semantics { contentDescription = padLabel }, contentAlignment = Alignment.Center) {
                    BoardIcon(EatGlyphs.pad, PALE, 19.dp)
                }
                if (pad.isNotEmpty()) {
                    Box(Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(Color(0xFFE8622C)), contentAlignment = Alignment.Center) {
                        Text(pad.size.toString(), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.White))
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
                .clip(CircleShape)
                .background(Color(0x1AFFFFFF))
                .border(1.dp, Color(0x33FFFFFF), CircleShape)
                .clickable { nav.go(Dest.Eat("map")) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            BoardIcon(EatGlyphs.map, PALE, 18.dp)
            Text(stringResource(R.string.eat_map), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = PALE))
        }
    }
}

private fun pillText(c: Color) = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = c)

/** The board's glyph for a bite, with its own drawing box (the SVG's width and height). */
private fun biteArt(b: Bite): Triple<BoardGlyph, Float, Float> = when (b) {
    Bite.TACOS -> Triple(EatGlyphs.tacos, 34f, 26f)
    Bite.SLICE -> Triple(EatGlyphs.slice, 30f, 28f)
    Bite.BURGER -> Triple(EatGlyphs.burger, 30f, 28f)
    Bite.KEBAB -> Triple(EatGlyphs.kebab, 26f, 30f)
    Bite.FALAFEL -> Triple(EatGlyphs.falafel, 30f, 28f)
    Bite.DUMPLING -> Triple(EatGlyphs.dumpling, 32f, 26f)
    Bite.HOT_DOG -> Triple(EatGlyphs.hotDog, 34f, 22f)
    Bite.CHICKEN -> Triple(EatGlyphs.chicken, 30f, 28f)
    Bite.TRUCKS -> Triple(EatGlyphs.truck, 34f, 26f)
}

/** The view box heights of the board's bite drawings (their widths are the glyphs' view boxes). */
private fun viewHeight(b: Bite): Float = when (b) {
    Bite.TACOS, Bite.DUMPLING -> 18f
    Bite.KEBAB -> 24f
    Bite.HOT_DOG -> 16f
    Bite.TRUCKS -> 20f
    else -> 24f
}

/** A bite's glyph as a lit tube: a blurred glow of [glow] under the tube in [tube], fitted as the SVG fits it. */
@Composable
private fun NeonGlyph(b: Bite, tube: Color, glow: Color, scale: Float = 1f, modifier: Modifier = Modifier) {
    val (glyph, w, h) = biteArt(b)
    val draw: DrawScope.(Color) -> Unit = { c ->
        val vh = viewHeight(b)
        val s = minOf(size.width / glyph.viewBox, size.height / vh)
        val side = glyph.viewBox * s
        drawBoardGlyph(glyph, c, Offset((size.width - side) / 2f, (size.height - vh * s) / 2f), side)
    }
    Box(modifier.size((w * scale).dp, (h * scale).dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize().blur(4.dp, BlurredEdgeTreatment.Unbounded)) { draw(glow) }
        Canvas(Modifier.matchParentSize()) { draw(tube) }
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
            .then(if (on) Modifier.boxShadow(shape, 1f, 0f, 20f, Color(0x59FF4FD8)) else Modifier)
            .clip(shape)
            .background(if (on) Color(0xFF150F1A) else Color(0xFF120F18))
            .border(if (on) 1.5.dp else 1.dp, if (on) Color(0xCCFF4FD8) else Color(0x14FFFFFF), shape)
            .selectable(on, enabled = n > 0 || on, role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$name $n" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        NeonGlyph(b, tube, glow)
        Text("$name $n", style = TextStyle(fontFamily = EatType.tiltNeon, fontSize = 15.sp, color = tube, shadow = Shadow(glow, blurRadius = 10f)), maxLines = 1)
    }
}

/** A chip under the wall (40 px): an outlined pill, lit cyan when on. */
@Composable
private fun NightPill(on: Boolean, enabled: Boolean, description: String, round: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier
            .height(40.dp)
            .then(if (round) Modifier.width(40.dp) else Modifier)
            .then(if (on) Modifier.boxShadow(CircleShape, 1f, 0f, 12f, Color(0x5936E3FF)) else Modifier)
            .clip(CircleShape)
            .background(if (on) Color(0x1F36E3FF) else Color.Transparent)
            .border(if (on) 1.5.dp else 1.dp, if (on) Color(0xFF36E3FF) else Color(0x2EFFFFFF), CircleShape)
            .toggleable(on, enabled = enabled || on, role = Role.Checkbox) { onClick() }
            .semantics { contentDescription = description }
            .padding(horizontal = if (round) 0.dp else 12.dp),
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
            .boxShadow(shape, 1f, 8f, 20f, Color(0x80000000))
            .clip(shape)
            .background(tint)
            .then(if (index == 0) Modifier.border(3.dp, Color(0xFFFF4FD8), shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(CARD_INK), contentAlignment = Alignment.Center) {
            if (bite != null) NeonGlyph(bite, Color(0xFFFFD6F2), Color(0x00000000), scale = 0.88f)
            // A place that is none of the nine bites is not a taco place: the board's cutlery stands for it.
            else BoardIcon(EatGlyphs.cutlery, Color(0xFFFFD6F2), 24.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(s.place.name, style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, letterSpacing = (-0.01).em, color = CARD_INK), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val meta = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = CARD_META)
                when {
                    s.place.alwaysOpen -> Text("24/7", style = meta.copy(color = Color(0xFF0A7A93)))
                    s.until != null -> Text("→ " + s.until.format(HM), style = meta.copy(color = Color(0xFFB0147E)))
                }
                Text("${s.place.walkMinutes}′", style = meta)
                priceText(ctx.ratings[s.place.key]?.priceLevel)?.let { Text(it, style = meta) }
                if (s.place.cashOnly) BoardIcon(EatGlyphs.bill, CARD_META, 12.dp, contentDescription = stringResource(R.string.eat_world_cash_only))
                if (s.place.yes("drive_through")) BoardIcon(EatGlyphs.driveThrough, CARD_META, 13.dp, contentDescription = stringResource(R.string.eat_late_drive_through))
            }
        }
        ctx.ratings.stars(s.place.key)?.let { Text(starsText(it), style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = CARD_INK)) }
    }
}

/**
 * The wall: brick from the top down to 520 px (#1A1216, mortar every 22 px across and 46 px down, darkening from
 * #2A1A1E to #120E14), fading into the night from 360 px, with the pink haze of the sign over it.
 */
private fun DrawScope.drawBrickWall() {
    val k = density
    val wallH = minOf(520 * k, size.height)
    drawRect(Brush.verticalGradient(listOf(Color(0xFF2A1A1E), Color(0xFF120E14)), 0f, wallH), size = Size(size.width, wallH), alpha = 0.9f)
    var y = 0f
    while (y < wallH) {
        drawRect(Color(0x8C000000), Offset(0f, y), Size(size.width, 2 * k), alpha = 0.9f)
        y += 22 * k
    }
    var x = 0f
    while (x < size.width) {
        drawRect(Color(0x73000000), Offset(x, 0f), Size(2 * k, wallH), alpha = 0.9f)
        x += 46 * k
    }
    val fadeTop = 360 * k
    drawRect(Brush.verticalGradient(0f to NIGHT.copy(alpha = 0f), 0.3f to NIGHT, 1f to Color(0xFF0B0A10), startY = fadeTop, endY = size.height), Offset(0f, fadeTop), Size(size.width, size.height - fadeTop))
    val c = Offset(size.width / 2f, wallH * 0.18f)
    drawRect(Brush.radialGradient(0f to Color(0x38FF4FD8), 0.5f to Color(0x00FF4FD8), center = c, radius = hypot(size.width / 2f, wallH * 0.82f)), size = Size(size.width, wallH))
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
