package app.atlas.ui.eat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.shadow.Shadow as BoxShadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.atlas.R
import java.time.LocalDateTime
import app.atlas.nav.Navigator
import app.atlas.ui.kit.SansFamily

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
private val SERIF_CJK = FontFamily.Serif

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
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                world.planks.forEachIndexed { i, p ->
                    val n = machine?.plankCounts?.get(p.id) ?: 0
                    PlankTab(p, n, on = i == index, enabled = n > 0) { chosenPlank = i; picked = null }
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
                Text(it, Modifier.fillMaxWidth().padding(16.dp), style = TextStyle(fontFamily = SansFamily, fontSize = 11.sp), color = Color(0xFF8A847A), textAlign = TextAlign.Center)
            }
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(if (picked != null) 150.dp else 24.dp))
        }
        Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, top = 18.dp)) {
            val back = stringResource(R.string.kit_back)
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0x29FFFFFF)).clickable { nav.back() }.semantics { contentDescription = back }, contentAlignment = Alignment.Center) {
                BoardIcon(EatGlyphs.backBold, Color.White, 20.dp)
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
                Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 50.dp, end = 50.dp, bottom = 24.dp),
                onPlace = { ctx.open(nav, shown.place.place) },
                onWalk = { ctx.walkTo(nav, shown.place) },
            )
        }
    }
}

/** The noren: a dark rod, three woven panels (the middle one a little lower), the brush ensō and the title. */
@Composable
private fun Noren(world: CuisineWorld, plank: Plank, count: Int?) {
    Box(Modifier.fillMaxWidth().height(186.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val k = density
            val gap = 4 * k
            val w = (size.width - 2 * gap) / 3f
            val panelH = 182 * k
            for (i in 0 until 3) {
                val left = i * (w + gap)
                val top = if (i == 1) 4 * k else 0f
                val shape = androidx.compose.ui.geometry.RoundRect(left, top, left + w, top + panelH, bottomLeftCornerRadius = cornerOf(4 * k), bottomRightCornerRadius = cornerOf(4 * k))
                val clip = Path().apply { addRoundRect(shape) }
                drawContext.canvas.save()
                drawContext.canvas.clipPath(clip)
                var x = left
                var s = 0
                while (x < left + w) {
                    val (c, px) = world.curtain[s % world.curtain.size]
                    drawRect(Color(c), Offset(x, top), Size(px * k, panelH))
                    x += px * k
                    s++
                }
                // The inset shadow at the hem: 0 -8 12 rgba(0,0,0,.25).
                drawRect(Brush.verticalGradient(listOf(Color(0x00000000), Color(0x40000000)), top + panelH - 20 * k, top + panelH), Offset(left, top + panelH - 20 * k), Size(w, 20 * k))
                drawContext.canvas.restore()
            }
            drawRect(Color(0x4D000000), Offset(0f, 14 * k), Size(size.width, 3 * k))
            drawRect(Color(0xFF3B2A1A), size = Size(size.width, 14 * k))
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
            Text(
                plank.title,
                Modifier.semantics { heading() },
                style = TextStyle(fontFamily = SERIF_CJK, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 34.sp, letterSpacing = 0.08.em, color = Color(world.title)),
                maxLines = 1,
            )
            Text(
                plank.latin + (count?.let { " · $it" } ?: ""),
                style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.3.em, color = Color(world.reading)),
            )
        }
    }
}

private fun cornerOf(r: Float) = androidx.compose.ui.geometry.CornerRadius(r, r)

/** A wooden plaque (86 × 52): the native word, the reading and the count; the chosen one is paper with a red edge, raised. */
@Composable
private fun PlankTab(p: Plank, count: Int, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    val alpha = if (enabled || on) 1f else 0.45f
    val ink = (if (on) SEAL else Color(0xFF2E2214)).copy(alpha = alpha)
    Column(
        Modifier
            .offset(y = if (on) (-3).dp else 0.dp)
            .size(86.dp, 52.dp)
            .boxShadow(shape, 1f, if (on) 6f else 2f, if (on) 10f else 4f, if (on) Color(0x403C280A) else Color(0x333C280A))
            .clip(shape)
            .drawBehind {
                if (on) {
                    drawRect(Color(0xFFFBF7EE))
                } else {
                    val k = density
                    var x = 0f
                    while (x < size.width) {
                        drawRect(Color(0xFFC79A5B), Offset(x, 0f), Size(6 * k, size.height))
                        drawRect(Color(0xFFBE9152), Offset(x + 6 * k, 0f), Size(1 * k, size.height))
                        drawRect(Color(0xFFCCA163), Offset(x + 7 * k, 0f), Size(6 * k, size.height))
                        x += 13 * k
                    }
                }
            }
            .border(if (on) 2.dp else 1.dp, if (on) SEAL else Color(0x595A3C14), shape)
            .selectable(on, enabled = enabled || on, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
    ) {
        Text(p.native, style = TextStyle(fontFamily = SERIF_CJK, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, lineHeight = 17.sp, color = ink), maxLines = 1)
        Text("${p.latin} $count", style = TextStyle(fontFamily = SansFamily, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 0.08.em, color = ink), maxLines = 1)
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
                .clip(CircleShape)
                .background(if (on) Color(0x0FC8322A) else Color.Transparent)
                .border(if (on) 2.5.dp else 1.5.dp, if (on) SEAL else Color(0xFFB9AE99).copy(alpha = alpha), CircleShape),
            contentAlignment = Alignment.Center,
        ) { BoardIcon(worldToggleGlyph(t), if (on) SEAL else Color(0xFF6B6255).copy(alpha = alpha), 20.dp) }
    }
}

/** The silver ticket machine: its LED panel, the buttons two by two, the coin return, coin and ticket slots. */
@Composable
private fun TicketMachine(m: Machine, ctx: EatContext, picked: String?, modifier: Modifier, onPick: (String) -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .boxShadow(shape, 1f, 14f, 28f, Color(0x4D282832))
            .clip(shape)
            .background(Brush.verticalGradient(0f to Color(0xFFD5D9DE), 0.6f to Color(0xFFAEB4BC), 1f to Color(0xFF9DA3AC)))
            .drawBehind {
                drawRect(Color(0xB3FFFFFF), size = Size(size.width, 2.dp.toPx()))
                drawRect(Color(0x26000000), Offset(0f, size.height - 3.dp.toPx()), Size(size.width, 3.dp.toPx()))
            }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0E1A12))
                .innerShadow(RoundedCornerShape(8.dp), BoxShadow(radius = 3.dp, color = Color(0x99000000), offset = DpOffset(0.dp, 2.dp)))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val led = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color(0xFF7CFF9E), shadow = Shadow(Color(0x997CFF9E), blurRadius = 12f))
            Text(m.world.ticket, style = led.copy(fontFamily = SERIF_CJK, fontWeight = FontWeight.ExtraBold))
            Spacer(Modifier.weight(1f))
            Text(if (m.countsOpen) stringResource(R.string.eat_world_open, m.open) else m.open.toString(), style = led)
            Text("|", style = led.copy(color = Color(0xFF3E7A4E), shadow = null))
            Text(ctx.at.format(HM_PADDED), style = led)
        }
        if (m.buttons.isEmpty()) {
            Text(
                stringResource(if (m.plankCounts[m.plank.id] == 0) R.string.eat_world_none_here else R.string.eat_world_none),
                Modifier.fillMaxWidth().padding(vertical = 26.dp),
                style = TextStyle(fontFamily = SansFamily, fontSize = 15.sp, color = Color(0xFF4A4C52)),
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
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(10.dp, 34.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF2B2F36))
                    .innerShadow(RoundedCornerShape(5.dp), BoxShadow(radius = 1.5.dp, color = Color(0x99000000), offset = DpOffset(0.dp, 2.dp))),
            )
            Box(
                Modifier.size(34.dp).clip(CircleShape)
                    .drawBehind { drawCircle(Brush.radialGradient(listOf(Color(0xFFE2E5E9), Color(0xFF9DA3AC)), Offset(size.width * 0.4f, size.height * 0.35f), size.width * 0.65f)) }
                    .border(2.dp, Color(0xFF8E949C), CircleShape),
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(200.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1C1F24))
                    .innerShadow(RoundedCornerShape(6.dp), BoxShadow(radius = 2.dp, color = Color(0xB3000000), offset = DpOffset(0.dp, 2.dp))),
            )
        }
    }
}

/** One button (64 px): the shop, walk, rating dots or marks, and its LED; the chosen one pressed in, ringed blue. */
@Composable
private fun MachineKey(b: MachineButton, world: CuisineWorld, at: LocalDateTime, lang: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val closed = b.openness == Openness.CLOSED
    val shape = RoundedCornerShape(10.dp)
    val ink = if (closed) Color(0xFF5E636B) else SUMI
    Box(
        modifier
            .height(64.dp)
            .then(
                when {
                    on -> Modifier
                    closed -> Modifier.dropKey(shape, 2f, 0f)
                    else -> Modifier.dropKey(shape, 3f, 6f)
                },
            )
            .clip(shape)
            .background(
                when {
                    on -> Brush.verticalGradient(listOf(Color(0xFFFFFDF7), Color(0xFFFFFDF7)))
                    closed -> Brush.verticalGradient(listOf(Color(0xFFC9CCD1), Color(0xFFC9CCD1)))
                    else -> Brush.verticalGradient(listOf(Color.White, Color(0xFFECE9E1)))
                },
            )
            .then(if (on) Modifier.innerShadow(shape, BoxShadow(radius = 2.dp, color = Color(0x40000000), offset = DpOffset(0.dp, 2.dp))).border(3.dp, KEY_BLUE, shape) else Modifier)
            .clickable(onClick = onClick)
            .semantics { contentDescription = b.place.name }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(Modifier.padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(b.place.name, style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (closed) 6.dp else 8.dp)) {
                val small = TextStyle(fontFamily = SansFamily, fontSize = 12.sp, color = if (closed) ink else META)
                if (closed) {
                    Text(world.preparing, style = small.copy(fontFamily = SERIF_CJK, fontWeight = FontWeight.ExtraBold))
                    b.opens?.let { Text(dayTime(it, at, lang, HM_PADDED), style = small) }
                } else {
                    Text("${b.place.walkMinutes}′", style = small)
                    b.dots?.let { Text("●".repeat(it) + "○".repeat(5 - it), style = small.copy(color = SEAL, letterSpacing = 1.sp)) }
                    if (b.vegan) BoardIcon(EatGlyphs.leafKey, Color(0xFF2F6B34), 12.dp, contentDescription = stringResource(R.string.eat_diet_vegan))
                    if (b.cashOnly) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            BoardIcon(EatGlyphs.bill, META, 13.dp)
                            Text(stringResource(R.string.eat_world_only), style = small)
                        }
                    }
                    if (b.dots == null && !b.vegan && !b.cashOnly && b.closes != null) Text("→ " + b.closes.format(HM_PADDED), style = small)
                }
            }
        }
        val led = when (b.openness) {
            Openness.OPEN -> LED_GREEN
            Openness.CLOSING_SOON -> LED_AMBER
            Openness.CLOSED -> LED_OFF
            Openness.UNKNOWN -> null
        }
        Canvas(Modifier.align(Alignment.TopEnd).offset(x = 1.dp, y = 9.dp).size(9.dp)) {
            val r = size.minDimension / 2f
            if (led == null) {
                drawCircle(Color(0xFFBDBEC2), r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
            } else {
                if (led != LED_OFF) drawCircle(Brush.radialGradient(listOf(led.copy(alpha = 0.6f), led.copy(alpha = 0f)), center, r * 2.4f), r * 2.4f)
                drawCircle(led, r)
            }
        }
    }
}

/** The machine key's raised edge: a hard 0 [edge] 0 #8E949C under it and a soft 0 4 [blur] shadow. */
private fun Modifier.dropKey(shape: androidx.compose.ui.graphics.Shape, edge: Float, blur: Float): Modifier {
    var m = this.boxShadow(shape, 1f, edge, 0f, Color(0xFF8E949C))
    if (blur > 0f) m = m.boxShadow(shape, 1f, 4f, blur, Color(0x33000000))
    return m
}

/** The printed meal ticket (312 × 112, turned −1.5°): number, shop, closing time, payment and the route stub. */
@Composable
private fun PaperTicket(t: MealTicket, world: CuisineWorld, at: LocalDateTime, lang: String, modifier: Modifier, onPlace: () -> Unit, onWalk: () -> Unit) {
    val shape = RoundedCornerShape(2.dp)
    val walkLabel = stringResource(R.string.eat_walk_desc, t.walkMinutes)
    Row(
        modifier
            .fillMaxWidth()
            .height(112.dp)
            .rotate(-1.5f)
            .boxShadow(shape, 1f, 10f, 20f, Color(0x40282832))
            .clip(shape)
            .background(Color.White),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onPlace).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(world.ticket, style = TextStyle(fontFamily = SERIF_CJK, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = INDIGO))
                Text("No. ${t.number}", style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.16.em, color = INDIGO))
            }
            Text(t.button.place.name, style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = SUMI), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val small = TextStyle(fontFamily = SansFamily, fontSize = 13.sp, color = INDIGO)
                val state = when {
                    t.button.place.alwaysOpen -> "24/7"
                    t.button.closes != null -> "→ " + t.button.closes.format(HM_PADDED)
                    t.button.opens != null -> world.preparing + " " + dayTime(t.button.opens, at, lang, HM_PADDED)
                    else -> null
                }
                state?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        BoardIcon(EatGlyphs.clockSmall, INDIGO, 13.dp)
                        Text(it, style = small)
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
                        BoardIcon(if (t.pays == MealTicket.Pays.CARD) EatGlyphs.card else EatGlyphs.bill, INDIGO, 13.dp)
                        Text(it, style = small)
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
            BoardIcon(EatGlyphs.turn, KEY_BLUE, 26.dp)
            Text("${t.walkMinutes}′", style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = KEY_BLUE))
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

private fun worldToggleGlyph(t: WorldToggle): BoardGlyph = when (t) {
    WorldToggle.LATE -> EatGlyphs.moonFilled
    WorldToggle.VEGAN -> EatGlyphs.leafStamp
    WorldToggle.TAKEAWAY -> EatGlyphs.bag
    WorldToggle.CARD -> EatGlyphs.cardStamp
    WorldToggle.STEP_FREE -> EatGlyphs.stepFreeStamp
}

private fun worldToggleName(t: WorldToggle): Int = when (t) {
    WorldToggle.LATE -> R.string.eat_world_late
    WorldToggle.VEGAN -> R.string.eat_diet_vegan
    WorldToggle.TAKEAWAY -> R.string.eat_fact_takeaway
    WorldToggle.CARD -> R.string.eat_order_card
    WorldToggle.STEP_FREE -> R.string.eat_order_step_free
}
