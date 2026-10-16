package app.atlas.ui.eat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import java.time.LocalDateTime
import app.atlas.nav.Navigator
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.cssRadial
import app.atlas.ui.kit.cssRepeatingLinear

/* The Ramen board's paper, inks and metals (`canvas/project/Ramen.dc.html`). */
private val WASHI = Color(0xFFF3EEE3)
private val SUMI = Color(0xFF1B1A17)
private val SEAL = Color(0xFFC8322A)
private val META = Color(0xFF5A5347)
private val INDIGO = Color(0xFF1E2F5A)
private val LED_GREEN = Color(0xFF3BE06E)
private val LED_AMBER = Color(0xFFFFB020)
private val LED_OFF = Color(0xFF7C8189)
private val KEY_BLUE = Color(0xFF2E5BFF)

/* The board's type: Geist, Geist Mono for the LEDs, the world's own face (Shippori Mincho 800) for its words, and Arial on the planks (the board's buttons keep the browser's own face). */
private val READING = cssText("Geist", 12f, 600, lineHeight = 15.5f, spacing = 3.6f)
private val PLANK_LATIN = cssText("Arial", 10f, 600, lineHeight = 11.5f, spacing = 0.8f)
private val LED = cssText("Geist Mono", 14f, 500, colour = 0xFF7CFF9E, lineHeight = 18f, tnum = true, shadow = CssShadow(0f, 0f, 6f, 0f, 0x997CFF9E))
private val KEY_NAME = cssText("Geist", 14f, 700, lineHeight = 18f)
private val KEY_META = cssText("Geist", 12f, lineHeight = 15.5f, tnum = true)
private val TICKET_NO = cssText("Geist", 11f, 700, colour = 0xFF1E2F5A, lineHeight = 15f, spacing = 1.76f)
private val TICKET_NAME = cssText("Geist", 20f, 700, colour = 0xFF1B1A17, lineHeight = 25.5f)
private val TICKET_META = cssText("Geist", 13f, colour = 0xFF1E2F5A, lineHeight = 17f, tnum = true)
private val TICKET_WALK = cssText("Geist", 18f, 700, colour = 0xFF2E5BFF, lineHeight = 23f, tnum = true)

/** The world's face for its native words at [size] and [weight] on a [lineHeight] line. */
private fun native(world: CuisineWorld, size: Float, lineHeight: Float, weight: Int = 800, spacing: Float = 0f) =
    cssText(world.face, size, weight, lineHeight = lineHeight, spacing = spacing)

/**
 * A cuisine as its own world, as on the Ramen board: a noren curtain over the door, wooden planks that choose a
 * style, and a ticket machine with one button per shop (name, walk, rating dots if any, an LED for open, closing soon
 * or closed with when it opens). Choosing a shop prints a meal ticket with its walk on the stub. The skin is the
 * [world]'s data, so another cuisine is a new [CuisineWorld], not new drawing.
 */
@Composable
fun WorldScreen(world: CuisineWorld, plank: Int, nav: Navigator) {
    val ctx = rememberEatContext()
    LightStatusIcons()
    var chosenPlank by rememberSaveable(world.id) { mutableIntStateOf(plank) }
    var toggles by rememberSaveable { mutableStateOf(emptySet<WorldToggle>()) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val index = WorldModel.plankIndex(world, ctx.area, chosenPlank)
    val worked = rememberWorked(ctx.area, ctx.at, index, toggles, ctx.ratings) { WorldModel.machine(world, index, ctx.area, ctx.at, toggles, ctx.ratings) }
    val machine = worked.value
    val scroll = rememberScrollState()

    Box(Modifier.fillMaxSize().drawBehind { drawWashi() }) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Noren(world, world.planks[index], machine?.plankCounts?.get(world.planks[index].id))
            // The planks at 196 px, 86 × 52, spread across as the board's four, scrolling when a world has more.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(if (world.planks.size <= 4) 12.dp else 8.dp),
            ) {
                world.planks.forEachIndexed { i, p ->
                    val n = machine?.plankCounts?.get(p.id) ?: 0
                    PlankTab(p, world, n, on = i == index, enabled = n > 0) { chosenPlank = i; picked = null }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                WorldToggle.entries.forEachIndexed { i, t ->
                    val n = machine?.toggleCounts?.get(t) ?: 0
                    StampToggle(t, stringResource(worldToggleName(t)), on = t in toggles, enabled = n > 0, tilt = if (i % 2 == 0) -8f else 6f) {
                        toggles = if (t in toggles) toggles - t else toggles + t
                    }
                }
            }
            machine?.let { m ->
                TicketMachine(m, ctx, picked, Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) { key -> picked = if (picked == key) null else key }
            }
            if (ctx.area.waiting || !worked.fresh) Looking(META)
            if (ctx.area.error != null && ctx.area.places.isEmpty()) EatProblem(ctx.area)
            sourceLine(ctx.area)?.let {
                Text(it, Modifier.fillMaxWidth().padding(16.dp), style = cssText("Geist", 11f, colour = 0xFF8A847A, lineHeight = 14f, align = TextAlign.Center))
            }
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(if (picked != null) 150.dp else 24.dp))
        }
        Box(Modifier.padding(start = 16.dp, top = boardTop(40.dp))) {
            val back = stringResource(R.string.kit_back)
            Box(Modifier.size(44.dp).cssBox(bg = 0x29FFFFFF, r = round(44f)).clip(CircleShape).clickable { nav.back() }.semantics { contentDescription = back }, contentAlignment = Alignment.Center) {
                Symbol("ui.back", tint = Color.White, size = 20.dp)
            }
        }
        // The printed ticket, slightly askew, comes up at the foot of the screen.
        val button = machine?.buttons?.firstOrNull { it.place.key == picked }
        AnimatedVisibility(
            button != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            val shown = button ?: return@AnimatedVisibility
            PaperTicket(
                WorldModel.ticket(shown, ctx.at), world, ctx.at, ctx.lang,
                // Where the board prints it: 60 px in from the left, 312 wide, its foot 77 px off the screen's.
                Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 60.dp, end = 40.dp, bottom = 77.dp),
                onPlace = { ctx.open(nav, shown.place.place) },
                onWalk = { ctx.walkTo(nav, shown.place) },
            )
        }
    }
}

/**
 * The noren: three woven panels (flex thirds 4 px apart, the middle one 4 px lower, 182 px with 4 px feet, inset
 * 0 -8 12 at 25 %), the dark rod (14 px, 0 2 4), the brush ensō (140 px at 136, 34) and the title at 78 px.
 */
@Composable
private fun Noren(world: CuisineWorld, plank: Plank, count: Int?) {
    val period = world.curtain.sumOf { it.second.toDouble() }.toFloat()
    val stripes = remember(world) {
        var x = 0f
        world.curtain.flatMap { (c, w) -> listOf(x to c, (x + w).also { x = it } to c) }.toTypedArray()
    }
    Box(Modifier.fillMaxWidth().height(186.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0 until 3) {
                Box(
                    Modifier
                        .weight(1f)
                        .padding(top = if (i == 1) 4.dp else 0.dp)
                        .height(182.dp)
                        .cssBox(grad = listOf(cssRepeatingLinear(90f, period, *stripes)), r = CssCorners(0f, 0f, 4f, 4f), shadows = listOf(CssShadow(0f, -8f, 12f, 0f, 0x40000000, inset = true))),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(14.dp).cssBox(bg = 0xFF3B2A1A, shadows = listOf(CssShadow(0f, 2f, 4f, 0f, 0x4C000000))))
        Canvas(Modifier.fillMaxSize()) {
            val k = density
            // The ensō, the board's own stroke on a 140 px box at (136, 34).
            val ox = size.width / 2f - 70 * k
            val oy = 34 * k
            val enso = Path().apply {
                moveTo(ox + 112 * k, oy + 36 * k)
                cubicTo(ox + 98 * k, oy + 16 * k, ox + 66 * k, oy + 10 * k, ox + 44 * k, oy + 24 * k)
                cubicTo(ox + 22 * k, oy + 38 * k, ox + 14 * k, oy + 72 * k, ox + 30 * k, oy + 98 * k)
                cubicTo(ox + 46 * k, oy + 124 * k, ox + 88 * k, oy + 134 * k, ox + 112 * k, oy + 114 * k)
                cubicTo(ox + 126 * k, oy + 102 * k, ox + 130 * k, oy + 84 * k, ox + 126 * k, oy + 68 * k)
            }
            drawPath(enso, Color(world.mark).copy(alpha = 0.9f), style = Stroke(9 * k, cap = StrokeCap.Round))
            val tail = Path().apply { moveTo(ox + 126 * k, oy + 66 * k); cubicTo(ox + 128 * k, oy + 72 * k, ox + 127 * k, oy + 78 * k, ox + 125 * k, oy + 83 * k) }
            drawPath(tail, Color(world.mark).copy(alpha = 0.6f), style = Stroke(4 * k, cap = StrokeCap.Round))
        }
        Column(Modifier.fillMaxWidth().padding(top = 78.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CssLine(plank.title, native(world, 34f, 34f, spacing = 2.72f), Modifier.semantics { heading() }, color = Color(world.title))
            CssLine(plank.latin + (count?.let { " · $it" } ?: ""), READING, color = Color(world.reading))
        }
    }
}

private fun cornerOf(r: Float) = androidx.compose.ui.geometry.CornerRadius(r, r)

/**
 * A wooden plaque (86 × 52, inset 1 px at 35 %, 0 2 4): the native word (17 on 17), 1 px, the reading and the count;
 * the chosen one is paper ringed 2 px of red, raised 3 px on a 0 6 10 shadow.
 */
@Composable
private fun PlankTab(p: Plank, world: CuisineWorld, count: Int, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled || on) 1f else 0.45f
    val ink = (if (on) SEAL else Color(0xFF2E2214)).copy(alpha = alpha)
    Column(
        Modifier
            .offset(y = if (on) (-3).dp else 0.dp)
            .size(86.dp, 52.dp)
            .then(
                if (on) Modifier.cssBox(bg = 0xFFFBF7EE, r = corners(4f), shadows = listOf(CssShadow(0f, 0f, 0f, 2f, 0xFFC8322A, inset = true), CssShadow(0f, 6f, 10f, 0f, 0x403C280A)))
                else Modifier.cssBox(
                    grad = listOf(cssRepeatingLinear(90f, 13f, 0f to 0xFFC79A5B, 6f to 0xFFC79A5B, 6f to 0xFFBE9152, 7f to 0xFFBE9152, 7f to 0xFFCCA163, 13f to 0xFFCCA163)),
                    r = corners(4f), shadows = listOf(CssShadow(0f, 0f, 0f, 1f, 0x595A3C14, inset = true), CssShadow(0f, 2f, 4f, 0f, 0x333C280A)),
                ),
            )
            .clip(RoundedCornerShape(4.dp))
            .selectable(on, enabled = enabled || on, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
    ) {
        CssLine(p.native, native(world, 17f, 17f), color = ink)
        CssLine("${p.latin} $count", PLANK_LATIN.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold), color = ink)
    }
}

/** A round toggle as a red seal when on (2.5 px, turned a little), a pale ring when off. */
@Composable
private fun StampToggle(t: WorldToggle, description: String, on: Boolean, enabled: Boolean, tilt: Float, onClick: () -> Unit) {
    val alpha = if (enabled || on) 1f else 0.4f
    Box(
        Modifier.size(60.dp, 50.dp).toggleable(on, enabled = enabled || on, role = Role.Switch) { onClick() }.semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .rotate(if (on) tilt else 0f)
                .then(
                    if (on) Modifier.cssBox(bg = 0x0FC8322A, r = round(46f), shadows = listOf(CssShadow(0f, 0f, 0f, 2.5f, 0xFFC8322A, inset = true)))
                    else Modifier.cssBox(r = round(46f), shadows = listOf(CssShadow(0f, 0f, 0f, 1.5f, Color(0xFFB9AE99).copy(alpha = alpha).css(), inset = true))),
                ),
            contentAlignment = Alignment.Center,
        ) { Symbol(worldToggleSymbol(t), tint = if (on) SEAL else Color(0xFF6B6255).copy(alpha = alpha), size = 20.dp) }
    }
}

/** The silver ticket machine: its LED panel, the buttons two by two, the coin return, coin and ticket slots. */
@Composable
private fun TicketMachine(m: Machine, ctx: EatContext, picked: String?, modifier: Modifier, onPick: (String) -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .cssBox(
                grad = listOf(cssLinear(180f, 0f to 0xFFD5D9DE, 0.6f to 0xFFAEB4BC, 1f to 0xFF9DA3AC)), r = corners(22f),
                shadows = listOf(CssShadow(0f, 2f, 0f, 0f, 0xB3FFFFFF, inset = true), CssShadow(0f, -3f, 0f, 0f, 0x26000000, inset = true), CssShadow(0f, 14f, 28f, 0f, 0x4D282832)),
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The LED panel (38 px, inset 0 2 6): the ticket word, the open count and the time, glowing green.
        Row(
            Modifier.fillMaxWidth().height(38.dp)
                .cssBox(bg = 0xFF0E1A12, r = corners(8f), shadows = listOf(CssShadow(0f, 2f, 6f, 0f, 0x99000000, inset = true)))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val glow = LED.shadow
            CssLine(m.world.ticket, native(m.world, 14f, 20f).copy(color = Color(0xFF7CFF9E), shadow = glow))
            Spacer(Modifier.weight(1f))
            CssLine(if (m.countsOpen) stringResource(R.string.eat_world_open, m.open) else m.open.toString(), LED)
            CssLine("|", LED, color = Color(0xFF3E7A4E))
            CssLine(ctx.at.format(HM_PADDED), LED)
        }
        if (m.buttons.isEmpty()) {
            Text(
                stringResource(if (m.plankCounts[m.plank.id] == 0) R.string.eat_world_none_here else R.string.eat_world_none),
                Modifier.fillMaxWidth().padding(vertical = 26.dp),
                style = cssText("Geist", 15f, colour = 0xFF4A4C52, lineHeight = 19f),
                textAlign = TextAlign.Center,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in m.buttons.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (b in row) MachineKey(b, m.world, ctx.at, ctx.lang, on = b.place.key == picked, modifier = Modifier.weight(1f)) { onPick(b.place.key) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        // The foot: the coin return, a coin and the ticket slot.
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp, 34.dp).cssBox(bg = 0xFF2B2F36, r = corners(5f), shadows = listOf(CssShadow(0f, 2f, 3f, 0f, 0x99000000, inset = true), CssShadow(0f, 1f, 0f, 0f, 0x99FFFFFF))))
            Box(Modifier.size(34.dp).cssBox(grad = listOf(cssRadial(true, 13.6f, 11.9f, 0f to 0xFFE2E5E9, 1f to 0xFF9DA3AC)), r = round(34f), shadows = listOf(CssShadow(0f, 0f, 0f, 2f, 0xFF8E949C, inset = true))))
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(200.dp, 12.dp).cssBox(bg = 0xFF1C1F24, r = corners(6f), shadows = listOf(CssShadow(0f, 2f, 4f, 0f, 0xB3000000, inset = true), CssShadow(0f, 1f, 0f, 0f, 0x99FFFFFF))))
        }
    }
}

/**
 * One button (64 px, 10 px in, 4 px between its lines): the shop, walk, rating dots or marks, and its LED (9 px, 9 px
 * from the top right, glowing 0 0 6); raised on a hard 0 3 0 and a soft 0 4 6, the chosen one pressed in (inset
 * 0 2 4) and ringed 3 px of blue outside.
 */
@Composable
private fun MachineKey(b: MachineButton, world: CuisineWorld, at: LocalDateTime, lang: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val closed = b.openness == Openness.CLOSED
    val ink = if (closed) Color(0xFF5E636B) else SUMI
    Box(
        modifier
            .height(64.dp)
            .then(
                when {
                    on -> Modifier.cssBox(bg = 0xFFFFFDF7, r = corners(10f), shadows = listOf(CssShadow(0f, 2f, 4f, 0f, 0x40000000, inset = true), CssShadow(0f, 0f, 0f, 3f, 0xFF2E5BFF)))
                    closed -> Modifier.cssBox(bg = 0xFFC9CCD1, r = corners(10f), shadows = listOf(CssShadow(0f, 2f, 0f, 0f, 0xFF8E949C)))
                    else -> Modifier.cssBox(grad = listOf(cssLinear(180f, 0f to 0xFFFFFFFF, 1f to 0xFFECE9E1)), r = corners(10f), shadows = listOf(CssShadow(0f, 3f, 0f, 0f, 0xFF8E949C), CssShadow(0f, 4f, 6f, 0f, 0x33000000)))
                },
            )
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = b.place.name }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(Modifier.padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CssLine(b.place.name, KEY_NAME, color = ink)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (closed) 6.dp else 8.dp)) {
                val small = if (closed) ink else META
                if (closed) {
                    CssLine(world.preparing, native(world, 12f, 17.5f), color = ink)
                    b.opens?.let { CssLine(dayTime(it, at, lang, HM_PADDED), KEY_META, color = small) }
                } else {
                    CssLine("${b.place.walkMinutes}′", KEY_META, color = small)
                    b.dots?.let { CssLine("●".repeat(it) + "○".repeat(5 - it), KEY_META.copy(letterSpacing = 1.sp), color = SEAL) }
                    if (b.vegan) Symbol("fact.vegan.2", tint = Color(0xFF2F6B34), size = 12.dp, contentDescription = stringResource(R.string.eat_diet_vegan))
                    if (b.cashOnly) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Symbol("fact.cash", tint = META, size = 13.dp)
                            CssLine(stringResource(R.string.eat_world_only), KEY_META, color = small)
                        }
                    }
                    if (b.dots == null && !b.vegan && !b.cashOnly && b.closes != null) CssLine("→ " + b.closes.format(HM_PADDED), KEY_META, color = small)
                }
            }
        }
        val led = when (b.openness) {
            Openness.OPEN -> LED_GREEN
            Openness.CLOSING_SOON -> LED_AMBER
            Openness.CLOSED -> LED_OFF
            Openness.UNKNOWN -> null
        }
        val ledBox = Modifier.align(Alignment.TopEnd).offset(x = 1.dp, y = 9.dp).size(9.dp)
        when {
            // Hours not mapped: an unlit ring rather than a colour that would claim something.
            led == null -> Canvas(ledBox) { drawCircle(Color(0xFFBDBEC2), size.minDimension / 2f - 0.5.dp.toPx(), style = Stroke(1.dp.toPx())) }
            led == LED_OFF -> Box(ledBox.cssBox(bg = led.css(), r = corners(5f)))
            else -> Box(ledBox.cssBox(bg = led.css(), r = corners(5f), shadows = listOf(CssShadow(0f, 0f, 6f, 0f, led.css()))))
        }
    }
}

/** The printed meal ticket (312 × 112, turned −1.5°): number, shop, closing time, payment and the route stub. */
@Composable
private fun PaperTicket(t: MealTicket, world: CuisineWorld, at: LocalDateTime, lang: String, modifier: Modifier, onPlace: () -> Unit, onWalk: () -> Unit) {
    val walkLabel = stringResource(R.string.eat_walk_desc, t.walkMinutes)
    Row(
        modifier
            .fillMaxWidth()
            .height(112.dp)
            .rotate(-1.5f)
            .cssBox(bg = 0xFFFFFFFF, r = corners(2f), shadows = listOf(CssShadow(0f, 10f, 20f, 0f, 0x40282832)))
            .clip(RoundedCornerShape(2.dp)),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onPlace).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CssLine(world.ticket, native(world, 14f, 20f, weight = 700), color = INDIGO)
                CssLine("No. ${t.number}", TICKET_NO)
            }
            CssLine(t.button.place.name, TICKET_NAME)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val state = when {
                    t.button.place.alwaysOpen -> "24/7"
                    t.button.closes != null -> "→ " + t.button.closes.format(HM_PADDED)
                    t.button.opens != null -> world.preparing + " " + dayTime(t.button.opens, at, lang, HM_PADDED)
                    else -> null
                }
                state?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Symbol("ui.clock", tint = INDIGO, size = 13.dp)
                        CssLine(it, TICKET_META)
                    }
                }
                val pays = when (t.pays) {
                    MealTicket.Pays.CASH_ONLY -> stringResource(R.string.eat_world_cash_only)
                    MealTicket.Pays.CARD -> stringResource(R.string.eat_order_card)
                    MealTicket.Pays.CASH -> stringResource(R.string.eat_world_cash)
                    MealTicket.Pays.UNKNOWN -> null
                }
                pays?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Symbol(if (t.pays == MealTicket.Pays.CARD) "fact.card" else "fact.cash", tint = INDIGO, size = 13.dp)
                        CssLine(it, TICKET_META)
                    }
                }
            }
        }
        Canvas(Modifier.width(2.dp).fillMaxHeight()) {
            drawLine(Color(0xFFD4CFC4), Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
        }
        Column(
            Modifier.width(84.dp).fillMaxHeight().clickable(onClick = onWalk).semantics(mergeDescendants = true) { contentDescription = walkLabel },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Symbol("tab.go", tint = KEY_BLUE, size = 26.dp)
            CssLine("${t.walkMinutes}′", TICKET_WALK)
        }
    }
}

/** Washi: #F3EEE3 with the board's two faint dot grids (7 × 9 and 11 × 5 px). */
private fun DrawScope.drawWashi() {
    drawRect(WASHI)
    fun dots(dx: Float, dy: Float, c: Color) {
        val r = 1.dp.toPx()
        var y = dy * density / 2f
        while (y < size.height) {
            var x = dx * density / 2f
            while (x < size.width) {
                drawCircle(c, r, Offset(x, y))
                x += dx * density
            }
            y += dy * density
        }
    }
    dots(7f, 9f, Color(0x1A968264))
    dots(11f, 5f, Color(0x12968264))
}

private fun worldToggleSymbol(t: WorldToggle): String = when (t) {
    WorldToggle.LATE -> "ui.moon"
    WorldToggle.VEGAN -> "fact.vegan"
    WorldToggle.TAKEAWAY -> "cat.shops"
    WorldToggle.CARD -> "fact.card.2"
    WorldToggle.STEP_FREE -> "fact.wheelchair"
}

private fun worldToggleName(t: WorldToggle): Int = when (t) {
    WorldToggle.LATE -> R.string.eat_world_late
    WorldToggle.VEGAN -> R.string.eat_diet_vegan
    WorldToggle.TAKEAWAY -> R.string.eat_fact_takeaway
    WorldToggle.CARD -> R.string.eat_order_card
    WorldToggle.STEP_FREE -> R.string.eat_order_step_free
}
