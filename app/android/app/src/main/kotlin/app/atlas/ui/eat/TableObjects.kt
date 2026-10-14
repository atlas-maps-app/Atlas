package app.atlas.ui.eat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow as BoxShadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.atlas.ui.kit.InterFamily
import kotlin.math.hypot

/*
 * The six objects on the table, ported from the Menus board (`canvas/project/Menus.dc.html`): each object's shape,
 * gradient, frame, shadows, type and icon as the board states them, in board pixels scaled by [u] (1 at 412 dp wide).
 */

/** A CSS box-shadow: offset ([x], [y]), [blur] and colour, in board px scaled by [u]. */
internal fun Modifier.boxShadow(shape: Shape, u: Float, y: Float, blur: Float, colour: Color, x: Float = 0f): Modifier =
    dropShadow(shape, BoxShadow(radius = (blur * u / 2f).dp, color = colour, offset = DpOffset((x * u).dp, (y * u).dp)))

/** One object lying on the table: tilted by [tilt] degrees and a button that says [description]. */
@Composable
internal fun TableObject(tilt: Float, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .graphicsLayer { rotationZ = tilt }
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
            },
    ) { content() }
}

/** The leather menu's outline: 6 px corners at the spine, 12 px at the fore-edge. */
internal fun leatherShape(u: Float) = RoundedCornerShape(topStart = (6 * u).dp, bottomStart = (6 * u).dp, topEnd = (12 * u).dp, bottomEnd = (12 * u).dp)

/** The burgundy stitched leather menu of the carte (176 × 250). */
@Composable
internal fun LeatherMenu(count: EatArea.Count?, label: String, u: Float) {
    val shape = leatherShape(u)
    Box(
        Modifier
            .fillMaxSize()
            .boxShadow(shape, u, 18f, 30f, Color(0x59321408))
            .boxShadow(shape, u, 3f, 6f, Color(0x4D321408))
            .clip(shape)
            .drawBehind {
                val c = Offset(size.width * 0.3f, size.height * 0.25f)
                val r = hypot(size.width * 0.7f, size.height * 0.75f)
                drawRect(Brush.radialGradient(0f to Color(0xFF7A2A28), 0.7f to Color(0xFF4E1817), center = c, radius = r))
                // The spine: inset 6 px #3A1010 over 7 px #6A2422.
                drawRect(Color(0xFF6A2422), size = Size(7 * u * density, size.height))
                drawRect(Color(0xFF3A1010), size = Size(6 * u * density, size.height))
                // The stitching: a 1.5 px dashed line inset 16/10/10/10.
                val l = 16 * u * density
                val t = 10 * u * density
                val w = 1.5f * u * density
                val rr = RoundRect(l, t, size.width - t, size.height - t, topLeftCornerRadius = cr(4 * u * density), bottomLeftCornerRadius = cr(4 * u * density), topRightCornerRadius = cr(8 * u * density), bottomRightCornerRadius = cr(8 * u * density))
                drawPath(Path().apply { addRoundRect(rr) }, Color(0x8CD6B060), style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * w, 2 * w))))
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((12 * u).dp)) {
            BoardIcon(EatGlyphs.cutlery, Color(0xFFD6B060), (34 * u).dp)
            Text(
                label,
                style = TextStyle(
                    fontFamily = EatType.cormorant, fontStyle = FontStyle.Italic, fontWeight = FontWeight.SemiBold, fontSize = (46 * u).sp, lineHeight = (46 * u).sp,
                    brush = Brush.verticalGradient(0f to Color(0xFFF6E2A8), 0.55f to Color(0xFFC9973E), 1f to Color(0xFFF1D38A)),
                ),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((5 * u).dp)) {
                count?.let { c ->
                    Canvas(Modifier.size((6 * u).dp)) {
                        if (c.open) drawCircle(Color(0xFF7EE0A4)) else drawCircle(Color(0xFF7EE0A4), style = Stroke(1.2f * density))
                    }
                }
                Text(count?.value?.toString() ?: "…", style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = (13 * u).sp, color = Color(0xFFE8CF8E)))
            }
        }
    }
}

private fun cr(r: Float) = androidx.compose.ui.geometry.CornerRadius(r, r)

/** The wood-framed slate of the café (176 × 200): a 9 px frame, the slate, a chalk cup, "Café" and the count. */
@Composable
internal fun Chalkboard(count: EatArea.Count?, label: String, u: Float) {
    val shape = RoundedCornerShape((6 * u).dp)
    Box(
        Modifier
            .fillMaxSize()
            .boxShadow(shape, u, 16f, 26f, Color(0x52321E0A))
            .boxShadow(shape, u, 3f, 6f, Color(0x40321E0A))
            .clip(shape)
            .background(Color(0xFF8A5A33))
            .drawBehind { drawRoundRect(Color(0xFF6E4524), cornerRadius = cr(6 * u * density), style = Stroke(4 * u * density)) }
            .padding((9 * u).dp),
    ) {
        val slate = RoundedCornerShape((3 * u).dp)
        Column(
            Modifier
                .fillMaxSize()
                .clip(slate)
                .drawBehind {
                    val c = Offset(size.width * 0.4f, size.height * 0.3f)
                    drawRect(Brush.radialGradient(0f to Color(0xFF3A403B), 0.75f to Color(0xFF262A27), center = c, radius = hypot(size.width * 0.6f, size.height * 0.7f)))
                }
                .innerShadow(slate, BoxShadow(radius = (6 * u).dp, color = Color(0x80000000))),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((6 * u).dp, Alignment.CenterVertically),
        ) {
            Canvas(Modifier.size((44 * u).dp, (40 * u).dp)) { drawBoardGlyph(EatGlyphs.chalkCup, Color(0xFFEDEBE2).copy(alpha = 0.85f), side = size.width) }
            Text(
                label,
                style = TextStyle(fontFamily = EatType.hand, fontWeight = FontWeight.Bold, fontSize = (42 * u).sp, lineHeight = (38 * u).sp, color = Color(0xFFF2F0E6).copy(alpha = 0.92f), shadow = Shadow(Color(0x80FFFFFF), blurRadius = 1f)),
            )
            Text(count?.value?.toString() ?: "…", style = TextStyle(fontFamily = EatType.hand, fontSize = (20 * u).sp, color = Color(0xFFCFE8C9)))
        }
    }
}

/** The neon "Late" sign on folded cardboard (176 × 170, the sign 148 × 112 turned 3°). */
@Composable
internal fun NeonLate(count: EatArea.Count?, until: String?, label: String, u: Float) {
    val shape = RoundedCornerShape((3 * u).dp)
    Box(
        Modifier
            .fillMaxSize()
            .boxShadow(shape, u, 14f, 24f, Color(0x4D321E0A))
            .clip(shape)
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFCFA66B), Color(0xFFBE9458))))
                drawRect(Color(0x405A3C14), style = Stroke(1 * u * density))
                drawLine(Color(0x405A3C14), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1 * u * density)
            },
        contentAlignment = Alignment.Center,
    ) {
        val sign = RoundedCornerShape((14 * u).dp)
        Column(
            Modifier
                .size((148 * u).dp, (112 * u).dp)
                .rotate(3f)
                .boxShadow(sign, u, 4f, 10f, Color(0x59000000))
                .clip(sign)
                .background(Color(0xFF120F18)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((2 * u).dp, Alignment.CenterVertically),
        ) {
            NeonText(label, (38 * u), Color(0xFFFFD6F2), listOf(4f to Color(0xFFFF4FD8), 12f to Color(0xFFFF4FD8), 24f to Color(0xFFC2189B)), u)
            val line = when {
                count == null -> "…"
                !count.open -> "?"
                until != null -> "${count.value} · $until"
                else -> count.value.toString()
            }
            NeonText(line, (18 * u), Color(0xFFD6FBFF), listOf(4f to Color(0xFF36E3FF), 10f to Color(0xFF0FB8E0)), u)
        }
    }
}

/** Tilt Neon text in [colour] with the board's stacked glows ([glows]: blur in board px to colour). */
@Composable
internal fun NeonText(text: String, sizeSp: Float, colour: Color, glows: List<Pair<Float, Color>>, u: Float, modifier: Modifier = Modifier) {
    val base = TextStyle(fontFamily = EatType.tiltNeon, fontSize = sizeSp.sp, lineHeight = sizeSp.sp)
    Box(modifier, contentAlignment = Alignment.Center) {
        // Each glow is a layer of its own, under the tube: the board stacks three text-shadows.
        for ((blur, c) in glows.reversed()) Text(text, style = base.copy(color = c, shadow = Shadow(c, blurRadius = blur * u * 2f)))
        Text(text, style = base.copy(color = colour))
    }
}

/** The Art Deco bar card (166 × 236): two gold frames with stepped corners, the fan, "BAR", a glass, the count. */
@Composable
internal fun DecoCard(count: EatArea.Count?, label: String, u: Float) {
    val shape = RoundedCornerShape((4 * u).dp)
    Box(
        Modifier
            .fillMaxSize()
            .boxShadow(shape, u, 18f, 30f, Color(0x66140A00))
            .boxShadow(shape, u, 3f, 6f, Color(0x4D140A00))
            .clip(shape)
            .background(Color(0xFF121110))
            .drawBehind {
                val k = u * density
                val o = Offset(8 * k, 8 * k)
                val s = Stroke(1 * k)
                val gold = Color(0xFFC9A45C)
                drawRect(gold, o + Offset(1 * k, 1 * k), Size(148 * k, 218 * k), style = s)
                drawRect(gold, o + Offset(5 * k, 5 * k), Size(140 * k, 210 * k), style = s)
                val p = Path()
                fun poly(vararg xy: Float) {
                    p.moveTo(o.x + xy[0] * k, o.y + xy[1] * k)
                    var i = 2
                    while (i < xy.size) { p.lineTo(o.x + xy[i] * k, o.y + xy[i + 1] * k); i += 2 }
                }
                poly(5f, 30f, 17f, 30f, 17f, 18f, 29f, 18f, 29f, 5f)
                poly(145f, 30f, 133f, 30f, 133f, 18f, 121f, 18f, 121f, 5f)
                poly(5f, 190f, 17f, 190f, 17f, 202f, 29f, 202f, 29f, 215f)
                poly(145f, 190f, 133f, 190f, 133f, 202f, 121f, 202f, 121f, 215f)
                for ((x, y) in listOf(57f to 38f, 67f to 42f, 75f to 44f, 83f to 42f, 93f to 38f)) poly(75f, 22f, x, y)
                drawPath(p, gold, style = s)
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((10 * u).dp)) {
            Text(
                label,
                style = TextStyle(
                    fontFamily = EatType.poiret, fontSize = (48 * u).sp, lineHeight = (48 * u).sp, letterSpacing = 0.06.em,
                    brush = Brush.verticalGradient(0f to Color(0xFFF6E2A8), 0.6f to Color(0xFFB98A36), 1f to Color(0xFFF1D38A)),
                ),
            )
            BoardIcon(EatGlyphs.martini, Color(0xFFC9A45C), (30 * u).dp)
            Text(count?.value?.toString() ?: "…", style = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = (13 * u).sp, color = Color(0xFFE8CF8E)))
        }
    }
}

/** The sweets ticket's outline (160 × 150): a card with its four corners scooped out, 10 px round. */
internal fun ticketShape(u: Float): Shape = GenericShape { size, _ ->
    val k = size.width / 160f
    val r = 10 * k
    moveTo(r, 0f)
    lineTo(size.width - r, 0f)
    arcTo(androidx.compose.ui.geometry.Rect(size.width - r, -r, size.width + r, r), 180f, -90f, false)
    lineTo(size.width, size.height - r)
    arcTo(androidx.compose.ui.geometry.Rect(size.width - r, size.height - r, size.width + r, size.height + r), 270f, -90f, false)
    lineTo(r, size.height)
    arcTo(androidx.compose.ui.geometry.Rect(-r, size.height - r, r, size.height + r), 0f, -90f, false)
    lineTo(0f, r)
    arcTo(androidx.compose.ui.geometry.Rect(-r, -r, r, r), 90f, -90f, false)
    close()
}

/** The pink ticket of sweets (160 × 150): dotted tear lines, a drawn ice cream, "Sweets" and the count. */
@Composable
internal fun TicketStub(count: EatArea.Count?, label: String, u: Float) {
    val shape = ticketShape(u)
    Box(
        Modifier
            .fillMaxSize()
            .boxShadow(shape, u, 12f, 16f, Color(0x47501E28))
            .clip(shape)
            .background(Color(0xFFF6D3DA))
            .drawBehind {
                val k = size.width / 160f
                val dots = PathEffect.dashPathEffect(floatArrayOf(1 * k, 7 * k))
                for (y in listOf(8f, 142f)) drawLine(Color(0xFFE9A9B8), Offset(14 * k, y * k), Offset(146 * k, y * k), 2 * k, StrokeCap.Round, dots)
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((4 * u).dp)) {
            Canvas(Modifier.size((34 * u).dp, (40 * u).dp)) {
                val k = size.width / 34f
                drawPath(Path().apply { moveTo(9 * k, 18 * k); lineTo(17 * k, 38 * k); lineTo(25 * k, 18 * k); close() }, Color(0xFFE0A96B))
                drawLine(Color(0xFFB9824A), Offset(11 * k, 22 * k), Offset(23 * k, 22 * k), 1.2f * k)
                drawLine(Color(0xFFB9824A), Offset(13 * k, 27 * k), Offset(21 * k, 27 * k), 1.2f * k)
                drawCircle(Color(0xFFFFF3F6), 9 * k, Offset(17 * k, 12 * k))
                drawCircle(Color(0xFFF59CB5), 6 * k, Offset(11 * k, 15 * k))
                drawCircle(Color(0xFFB8E3C9), 6 * k, Offset(23 * k, 15 * k))
                drawCircle(Color(0xFFD93A3A), 2.2f * k, Offset(20 * k, 4 * k))
            }
            Text(label, style = TextStyle(fontFamily = EatType.fredoka, fontWeight = FontWeight.SemiBold, fontSize = (32 * u).sp, lineHeight = (32 * u).sp, color = Color(0xFFB2345A)))
            Text(count?.value?.toString() ?: "…", style = TextStyle(fontFamily = EatType.fredoka, fontSize = (14 * u).sp, color = Color(0xFF8F2A4A)))
        }
    }
}

/** The kraft tag's outline (150 × 140): `polygon(24px 0, 100% 0, 100% 100%, 24px 100%, 0 70px)`. */
internal val TagShape: Shape = GenericShape { size, _ ->
    val k = size.width / 150f
    moveTo(24 * k, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height)
    lineTo(24 * k, size.height)
    lineTo(0f, 70 * k)
    close()
}

/** The kraft hang tag of the market (150 × 140): its hole, a stall, "MARKET" typed and the count. */
@Composable
internal fun KraftTag(count: EatArea.Count?, label: String, u: Float) {
    Box(
        Modifier
            .fillMaxSize()
            .clip(TagShape)
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFDDBE8D), Color(0xFFCFAE78))))
                val k = size.width / 150f
                val hole = Offset(20 * k, 70 * k)
                drawCircle(Color(0xFFEDE6DA), 6 * k, hole)
                drawCircle(Brush.verticalGradient(listOf(Color(0x59000000), Color(0x00000000)), hole.y - 6 * k, hole.y - 3 * k), 6 * k, hole)
            }
            .padding(start = (20 * u).dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((4 * u).dp)) {
            Canvas(Modifier.size((32 * u).dp, (30 * u).dp)) { drawBoardGlyph(EatGlyphs.stall, Color(0xFF3E2F1C), Offset((size.width - size.height) / 2f, 0f), size.height) }
            Text(label.uppercase(), style = TextStyle(fontFamily = EatType.specialElite, fontSize = (25 * u).sp, letterSpacing = 0.04.em, color = Color(0xFF2E2214)))
            Text(count?.value?.toString() ?: "…", style = TextStyle(fontFamily = EatType.specialElite, fontSize = (14 * u).sp, color = Color(0xFF5A4428)))
        }
    }
}

/** The tag's string, drawn under the tag from (-40, -30): `M2 4C30 10 50 30 58 46`, 2 px #9C7B4C. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.tagString(origin: Offset, k: Float) {
    val p = Path().apply {
        moveTo(origin.x + 2 * k, origin.y + 4 * k)
        cubicTo(origin.x + 30 * k, origin.y + 10 * k, origin.x + 50 * k, origin.y + 30 * k, origin.x + 58 * k, origin.y + 46 * k)
    }
    drawPath(p, Color(0xFF9C7B4C), style = Stroke(2 * k, cap = StrokeCap.Round))
}
