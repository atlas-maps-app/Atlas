package app.atlas.ui.search

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import app.atlas.ui.kit.drawSymbol

/*
 * Every shelf as an illustration in full colour (the owner, 4 November: "Some concepts had much more sophisticated
 * art for the categories."): a small scene of its own, flat with soft shading, in a few colours made from the card's
 * and an accent or two. Where concept 10 drew one (the pasta, the crate of fruit, the cross and capsule, the trees by
 * the pond, the gear and wrench, the lake, the transit lines) its paths are kept. A scene is drawn from the bottom
 * right corner in units of a hundredth of the card's height, as the motifs are, runs off the right and bottom edges
 * where it meets them, and leaves the left, where the words are, calm.
 */

// ------------------------------------------------------------------------------------------------------------- emblems

/**
 * [id], a kind's own symbol, as its picture, where a scene would stand: large at the right of a card, a name running over
 * it if it must (the owner, 9 November, of smaller ones kept clear of the words: "The larger ones, even if sitting behind
 * text, looked better aligned"), and whole, as a symbol cut off no longer says what it is. It is lit softly from behind,
 * with no circle ("your first illuminated try without the circle looked better"), and drawn in a tint of the card's
 * colour, so that words stay legible over it and it does not read as one more control beside a band's star; [clear]
 * pixels short of the end, where those controls are.
 */
internal fun DrawScope.emblem(id: String, base: Color, strength: Float = 1f, clear: Float = 0f) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    // a hundredth of the card's height, a scene's unit, so that it keeps its place on a card, a token or a band
    val u = minOf(h, 160.dp.toPx()) / 100f
    fun ink(c: Color) = tone(c, base, strength)
    clipRect {
        withTransform({ translate(w - clear, h); scale(u, u, Offset.Zero) }) {
            soft(ink(mix(base, Color.White, 0.55f)).copy(alpha = 0.3f), Offset(EMBLEM_X, EMBLEM_Y), 54f, solid = 0.1f)
            drawSymbol(id, Offset(EMBLEM_X - EMBLEM / 2f, EMBLEM_Y - EMBLEM / 2f), EMBLEM, ink(mix(base, Color.White, 0.66f)))
        }
    }
}

/** An emblem's side and centre, from the card's bottom right corner. */
private const val EMBLEM = 66f
private const val EMBLEM_X = -48f
private const val EMBLEM_Y = -54f

// -------------------------------------------------------------------------------------------------------------- scenes

/**
 * Draws [m]'s scene over a card of [base]'s colour, from its bottom right corner; at a [strength] below 1 (0.5 on the
 * cards faded or asleep) its colours grey and sink into the card's.
 */
internal fun DrawScope.scene(m: Motif, base: Color, strength: Float = 1f) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    // a unit: a hundredth of a card's height, so a scene keeps its proportions on a card, a token or a band
    val u = minOf(h, 160.dp.toPx()) / 100f
    // narrower than a card (a token, a closing card), the scene steps right, out of the words' way
    val nudge = ((132f - w / u) * 0.8f).coerceIn(0f, 16f) * u
    clipRect {
        withTransform({ translate(w + nudge, h); scale(u, u, Offset.Zero) }) {
            when (m) {
                Motif.PLATES -> plates(base, strength)
                Motif.PRODUCE -> produce(base, strength)
                Motif.ARCADE -> arcade(base, strength)
                Motif.NIGHT -> night(base, strength)
                Motif.CURRENT -> current(base, strength)
                Motif.HILLS -> hills(base, strength)
                Motif.BEAMS -> beams(base, strength)
                Motif.WAVES -> waves(base, strength)
                Motif.COLONNADE -> colonnade(base, strength)
                Motif.PITCH -> pitch(base, strength)
                Motif.PULSE -> pulse(base, strength)
                Motif.GEARS -> gears(base, strength)
                Motif.LINES -> lines(base, strength)
                Motif.CONTOURS -> contours(base, strength)
            }
        }
    }
}

/** Eat: a plate of pasta from above, in its sauce, with basil and two tomatoes (concept 10). */
private fun DrawScope.plates(base: Color, s: Float) {
    fun ink(c: Color) = tone(c, base, s)
    val shadow = ink(mix(base, Color.Black, 0.55f))
    val rim = ink(mix(base, Color.White, 0.97f))
    val plate = ink(mix(base, Color.White, 0.88f))
    val foot = ink(mix(mix(base, Color.White, 0.72f), Color.Black, 0.06f))
    val ring = ink(mix(mix(base, Color.White, 0.7f), Color.Black, 0.05f))
    val well = ink(mix(base, Color.White, 0.93f))
    val sauce = ink(mix(base, Color(0xFFC21E0E), 0.6f))
    val sheen = ink(mix(base, Color(0xFFF46A40), 0.6f))
    val pasta = ink(Color(0xFFF6D490))
    val pasta1 = ink(mix(Color(0xFFF6D490), base, 0.5f))
    val pasta2 = ink(mix(Color(0xFFF6D490), base, 0.3f))
    val pasta3 = ink(mix(Color(0xFFF6D490), base, 0.15f))
    val pasta4 = ink(mix(Color(0xFFF6D490), Color.White, 0.25f))
    val cheese = ink(mix(Color(0xFFF6D490), Color.White, 0.7f))
    val tomato = ink(Color(0xFFC92C18))
    val tomato2 = ink(Color(0xFFD33A1E))
    val basil = ink(Color(0xFF4E9A3E))
    val basil2 = ink(Color(0xFF62AC4B))
    val vein = ink(Color(0xFF2F6E28))
    val vein2 = ink(Color(0xFF3A7A2E))
    val white = ink(Color.White)
    art(-100f, -83.6f, 0.404f) {
        soft(shadow.copy(alpha = 0.42f), Offset(216f, 112f), 104f, 100f, solid = 0.72f)
        drawCircle(
            Brush.radialGradient(0f to rim, 0.78f to plate, 1f to foot, center = Offset(191.4f, 73f), radius = 129f),
            104f,
            Offset(208f, 98f),
        )
        drawCircle(white.copy(alpha = 0.8f), 103f, Offset(208f, 98f), style = ROUND_1_5)
        drawCircle(ring, 79f, Offset(208f, 98f), style = LINE_1_8)
        drawCircle(well, 77f, Offset(208f, 98f))
        drawPath(Pasta.sauce, sauce.copy(alpha = 0.92f))
        drawPath(Pasta.sheen, sheen.copy(alpha = 0.7f), style = ROUND_5)
        drawPath(Pasta.swirl, pasta1, style = ROUND_8)
        drawPath(Pasta.swirl2, pasta, style = ROUND_7)
        drawPath(Pasta.swirl3, pasta2, style = ROUND_6_5)
        drawPath(Pasta.strand, pasta3, style = ROUND_7)
        drawPath(Pasta.strand2, pasta4, style = ROUND_6)
        drawPath(Pasta.strand3, pasta4, style = ROUND_5)
        drawPath(Pasta.cheese, cheese)
        drawCircle(tomato, 11.5f, Offset(240f, 122f))
        drawCircle(tomato2, 9.5f, Offset(176f, 76f))
        drawPath(Pasta.shine, white.copy(alpha = 0.55f))
        drawPath(Pasta.leaf, basil)
        drawPath(Pasta.rib, vein, style = LINE_1_3)
        drawPath(Pasta.leaf2, basil2)
        drawPath(Pasta.rib2, vein2, style = LINE_1_2)
    }
}

/** The pasta and its plate, in concept 10's 300 by 300. */
private object Pasta {
    val sauce = svg("M150 104c-2 -34 28 -58 62 -54s52 32 46 60 -36 46 -64 40 -42 -18 -44 -46z")
    val sheen = svg("M156 96c4 -26 28 -42 54 -38")
    val swirl = svg("M164 102c2 -30 34 -46 58 -34s28 40 10 56 -46 18 -60 2")
    val swirl2 = svg("M174 106c-2 -22 20 -36 40 -30s26 28 14 42 -34 14 -44 0")
    val swirl3 = svg("M186 100c0 -14 14 -22 26 -18s14 18 6 26 -22 8 -26 -2")
    val strand = svg("M158 118c14 18 50 24 76 4")
    val strand2 = svg("M172 70c18 -12 48 -10 64 8")
    val strand3 = svg("M198 92c6 -4 14 -2 16 4")
    val cheese = svg(
        "M194.2 86a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0ZM220.4 102a1.6 1.6 0 1 0 3.2 0" +
        "a1.6 1.6 0 1 0 -3.2 0ZM208.2 118a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0ZM182.5 108" +
        "a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0ZM228.4 84a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0Z" +
        "M202.6 74a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0Z"
    )
    val shine = svg(
        "M232.6 118a3.4 3.4 0 1 0 6.8 0a3.4 3.4 0 1 0 -6.8 0ZM170.2 72.5a2.8 2.8 0 1 0 5.6 0" +
        "a2.8 2.8 0 1 0 -5.6 0Z"
    )
    val leaf = svg("M216 74c10 -15 30 -16 38 -7 -8 13 -28 15 -38 7z")
    val rib = svg("M216 74c11 -3 24 -5 38 -7")
    val leaf2 = svg("M190 132c-6 -15 2 -29 15 -32 3 15 -5 27 -15 32z")
    val rib2 = svg("M190 132c4 -10 8 -20 15 -32")
}

/** Groceries: a crate of fruit from above, oranges, lemons, limes and apples in their rows (concept 10). */
private fun DrawScope.produce(base: Color, s: Float) {
    fun ink(c: Color) = tone(c, base, s)
    val shadow = ink(mix(base, Color(0xFF3A2006), 0.7f))
    val wood = ink(mix(Color(0xFFC88B46), base, 0.08f))
    val grain = ink(mix(Color(0xFFE7B36F), base, 0.05f))
    val slat = ink(mix(Color(0xFFA06A2C), base, 0.08f))
    val hollow = ink(mix(Color(0xFF6E4219), base, 0.1f))
    val dark = ink(Color(0xFF2E1704))
    val or0 = ink(Color(0xFFFFBE6E))
    val or1 = ink(Color(0xFFF38B26))
    val or2 = ink(Color(0xFFD26510))
    val le0 = ink(Color(0xFFFFF7B0))
    val le1 = ink(Color(0xFFF7D53E))
    val le2 = ink(Color(0xFFD9AD18))
    val li0 = ink(Color(0xFFCFEE8E))
    val li1 = ink(Color(0xFF8AC146))
    val li2 = ink(Color(0xFF5A952A))
    val ap0 = ink(Color(0xFFFF907A))
    val ap1 = ink(Color(0xFFE2412E))
    val ap2 = ink(Color(0xFFB3261A))
    val leaf = ink(Color(0xFF4E9A3E))
    val leaf2 = ink(Color(0xFF5BAA48))
    val stem = ink(Color(0xFF5A3A12))
    val white = ink(Color.White)
    val orange = Brush.radialGradient(0f to or0, 0.55f to or1, 1f to or2, center = Offset(-7.2f, -9.6f), radius = 36f)
    val lemon = Brush.radialGradient(0f to le0, 0.55f to le1, 1f to le2, center = Offset(-7.5f, -8.8f), radius = 35f)
    val lime = Brush.radialGradient(0f to li0, 0.55f to li1, 1f to li2, center = Offset(-6.3f, -8.4f), radius = 31.5f)
    val apple = Brush.radialGradient(0f to ap0, 0.55f to ap1, 1f to ap2, center = Offset(-6.9f, -9.2f), radius = 34.5f)
    art(-101.7f, -84.8f, 0.44f) {
        rotate(-9f, Offset(220f, 90f)) {
            softRect(shadow.copy(alpha = 0.5f), Offset(108f, -24f), Size(252f, 208f), 14f, 8f)
            drawRoundRect(wood, Offset(104f, -36f), Size(252f, 208f), CornerRadius(14f))
            drawRoundRect(
                grain.copy(alpha = 0.7f),
                Offset(104f, -36f),
                Size(252f, 208f),
                CornerRadius(14f),
                style = LINE_2,
            )
            drawPath(Crate.slats, slat, style = LINE_2_4)
            drawRoundRect(hollow, Offset(117f, -23f), Size(226f, 182f), CornerRadius(7f))
            drawPath(Crate.under, dark.copy(alpha = 0.4f))
            translate(143f, 10f) {
                drawCircle(orange, 24f, Offset.Zero)
            }
            translate(195f, 8f) {
                drawOval(lemon, Offset(-25f, -22f), Size(50f, 44f))
            }
            translate(247f, 12f) {
                drawCircle(orange, 24f, Offset.Zero)
            }
            translate(141f, 64f) {
                drawCircle(apple, 23f, Offset.Zero)
            }
            translate(193f, 62f) {
                drawCircle(orange, 24f, Offset.Zero)
            }
            translate(245f, 60f) {
                drawCircle(lime, 21f, Offset.Zero)
            }
            translate(145f, 116f) {
                drawOval(lemon, Offset(-25f, -22f), Size(50f, 44f))
            }
            translate(195f, 114f) {
                drawCircle(lime, 21f, Offset.Zero)
            }
            translate(247f, 116f) {
                drawCircle(orange, 24f, Offset.Zero)
            }
            drawPath(Crate.shine, white.copy(alpha = 0.42f))
            drawPath(Crate.leaf, leaf)
            drawPath(Crate.leaf2, leaf2)
            drawCircle(stem, 2f, Offset(141f, 44f))
        }
    }
}

/** The crate's slats, shadows and shine, in concept 10's 300 by 300. */
private object Crate {
    val slats = svg("M104 40h12M104 104h12")
    val under = svg(
        "M122 14a24 24 0 1 0 48 0a24 24 0 1 0 -48 0ZM174 12a24 24 0 1 0 48 0a24 24 0 1 0 -48 0ZM226 16" +
        "a24 24 0 1 0 48 0a24 24 0 1 0 -48 0ZM120 68a24 24 0 1 0 48 0a24 24 0 1 0 -48 0ZM172 66" +
        "a24 24 0 1 0 48 0a24 24 0 1 0 -48 0ZM226 64a22 22 0 1 0 44 0a22 22 0 1 0 -44 0ZM125 120" +
        "a23 23 0 1 0 46 0a23 23 0 1 0 -46 0ZM176 118a22 22 0 1 0 44 0a22 22 0 1 0 -44 0ZM226 120" +
        "a24 24 0 1 0 48 0a24 24 0 1 0 -48 0Z"
    )
    val shine = svg(
        "M130 1a5 5 0 1 0 10 0a5 5 0 1 0 -10 0ZM182 0a5 5 0 1 0 10 0a5 5 0 1 0 -10 0ZM234 3a5 5 0 1 0 10 0" +
        "a5 5 0 1 0 -10 0ZM128 55a5 5 0 1 0 10 0a5 5 0 1 0 -10 0ZM180 53a5 5 0 1 0 10 0a5 5 0 1 0 -10 0Z" +
        "M233.5 52a4.5 4.5 0 1 0 9 0a4.5 4.5 0 1 0 -9 0ZM132 108a5 5 0 1 0 10 0a5 5 0 1 0 -10 0ZM183.5 106" +
        "a4.5 4.5 0 1 0 9 0a4.5 4.5 0 1 0 -9 0ZM234 107a5 5 0 1 0 10 0a5 5 0 1 0 -10 0Z"
    )
    val leaf = svg("M197 -14q10 -16 28 -14 -6 15 -28 14z")
    val leaf2 = svg("M247 92q12 -14 28 -10 -8 14 -28 10z")
}

/** Shops: a shopfront under a striped awning, its window lit and dressed, a planter under it, the door's glass lit. */
private fun DrawScope.arcade(base: Color, s: Float) {
    fun ink(c: Color) = tone(c, base, s)
    val wall = ink(mix(base, Color.Black, 0.2f))
    val wallLo = ink(mix(base, Color.Black, 0.34f))
    val fascia = ink(mix(base, Color(0xFFFFE7C7), 0.82f))
    val trim = ink(mix(base, Color.Black, 0.45f))
    val canvas = ink(mix(base, Color(0xFFFFF3E3), 0.92f))
    val stripe = ink(mix(base, Color(0xFFE8493F), 0.35f))
    val shade = ink(mix(base, Color.Black, 0.7f))
    val glow = ink(mix(Color(0xFFFFE9B8), base, 0.06f))
    val glowLo = ink(mix(Color(0xFFF2B562), base, 0.22f))
    val frame = ink(mix(base, Color.Black, 0.55f))
    val goods = ink(mix(base, Color.Black, 0.42f))
    val pot = ink(Color(0xFFC8643B))
    val green = ink(Color(0xFF4E9A3E))
    val green2 = ink(Color(0xFF6DB552))
    val vase = ink(Color(0xFF2F8F8A))
    val box = ink(mix(base, Color(0xFFFFE7C7), 0.6f))
    val wood = ink(mix(Color(0xFF8A5A3C), base, 0.2f))
    val door = ink(mix(base, Color.Black, 0.5f))
    val brass = ink(Color(0xFFF2C14E))
    val stone = ink(mix(base, Color(0xFFFFE7C7), 0.45f))
    val white = ink(Color.White)
    drawRect(
        Brush.linearGradient(listOf(wall, wallLo), start = Offset(-64f, -94f), end = Offset.Zero),
        Offset(-64f, -96f),
        Size(70f, 98f),
    )
    drawRect(trim, Offset(-66f, -98f), Size(72f, 3f))
    drawRect(fascia, Offset(-64f, -92f), Size(70f, 12f))
    drawRect(white.copy(alpha = 0.1f), Offset(-64f, -66f), Size(3f, 66f))
    drawRect(trim, Offset(-64f, -81.5f), Size(70f, 1.5f))
    drawPath(Shopfront.canvas, canvas)
    drawPath(Shopfront.stripes, stripe)
    drawRect(
        Brush.linearGradient(
            listOf(shade.copy(alpha = 0.22f), shade.copy(alpha = 0f)),
            start = Offset(0f, -80f),
            end = Offset(0f, -74f),
        ),
        Offset(-70f, -80f),
        Size(72f, 6f),
    )
    drawRect(
        Brush.linearGradient(listOf(glow, glowLo), start = Offset(0f, -56f), end = Offset(0f, -16f)),
        Offset(-58f, -56f),
        Size(32f, 40f),
    )
    drawRect(
        Brush.linearGradient(
            listOf(shade.copy(alpha = 0.3f), shade.copy(alpha = 0f)),
            start = Offset(0f, -56f),
            end = Offset(0f, -48f),
        ),
        Offset(-58f, -56f),
        Size(32f, 8f),
    )
    drawLine(frame, Offset(-42f, -56f), Offset(-42f, -49f), 0.7f)
    drawPath(Shopfront.lamp, goods)
    soft(white.copy(alpha = 0.55f), Offset(-42f, -45f), 9f, solid = 0.1f)
    drawRect(goods, Offset(-58f, -31f), Size(32f, 1.6f))
    drawPath(Shopfront.pot, pot)
    drawPath(Shopfront.plant, green)
    drawRoundRect(vase, Offset(-46.5f, -42f), Size(5f, 11f), CornerRadius(2.2f))
    drawRect(box, Offset(-38f, -35f), Size(9f, 4f))
    drawRect(box.copy(alpha = 0.8f), Offset(-36.5f, -39f), Size(6f, 4f))
    drawPath(Shopfront.glint, white.copy(alpha = 0.16f))
    drawRoundRect(frame, Offset(-58f, -56f), Size(32f, 40f), CornerRadius(1f), style = LINE_1_6)
    drawRect(stone, Offset(-60f, -16.5f), Size(36f, 2.2f))
    drawRect(wood, Offset(-59f, -14.3f), Size(34f, 7f))
    drawPath(Shopfront.leaves, green2)
    drawRect(door, Offset(-20f, -60f), Size(16f, 62f))
    drawRect(glow.copy(alpha = 0.85f), Offset(-17f, -57f), Size(10f, 22f))
    drawCircle(brass, 1.3f, Offset(-7f, -30f))
    drawRect(stone, Offset(-68f, -6f), Size(76f, 8f))
    drawRect(white.copy(alpha = 0.35f), Offset(-68f, -6f), Size(76f, 1.2f))
}

/** The shopfront's awning and what is in its window. */
private object Shopfront {
    val canvas = svg(
        "M-70 -80H2V-67A4.5 4.5 0 0 1 -7 -67A4.5 4.5 0 0 1 -16 -67A4.5 4.5 0 0 1 -25 -67" +
        "A4.5 4.5 0 0 1 -34 -67A4.5 4.5 0 0 1 -43 -67A4.5 4.5 0 0 1 -52 -67A4.5 4.5 0 0 1 -61 -67" +
        "A4.5 4.5 0 0 1 -70 -67Z"
    )
    val stripes = svg(
        "M-70 -80H-61V-67A4.5 4.5 0 0 1 -70 -67ZM-52 -80H-43V-67A4.5 4.5 0 0 1 -52 -67ZM-34 -80H-25V-67" +
        "A4.5 4.5 0 0 1 -34 -67ZM-16 -80H-7V-67A4.5 4.5 0 0 1 -16 -67Z"
    )
    val lamp = svg("M-46 -46.5a4 4 0 0 1 8 0z")
    val pot = svg("M-55.5 -37h6l-1 6h-4z")
    val plant = svg("M-52.5 -37c-4 -1 -5 -5 -4 -8 3 1 4 4 4 8zM-52.5 -37c0 -5 2 -8 5 -9 1 4 -1 8 -5 9z")
    val glint = svg("M-37 -56h5l-17 40h-5z")
    val leaves = svg(
        "M-58.7 -15a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0ZM-53.1 -16a3.6 3.6 0 1 0 7.2 0" +
        "a3.6 3.6 0 1 0 -7.2 0ZM-46.2 -15.2a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0ZM-40.6 -16" +
        "a3.6 3.6 0 1 0 7.2 0a3.6 3.6 0 1 0 -7.2 0ZM-33.5 -15a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z"
    )
}

/** Stay: a bed made up for the night, its quilt turned down and its pillow plumped, under a crescent moon. */
private fun DrawScope.night(base: Color, s: Float) {
    fun ink(c: Color) = tone(c, base, s)
    val moon = ink(Color(0xFFFFE3A1))
    val halo = ink(mix(Color(0xFFFFE3A1), base, 0.3f))
    val star = ink(mix(Color(0xFFFFF4D6), base, 0.1f))
    val wood = ink(mix(Color(0xFFA87452), base, 0.16f))
    val woodLo = ink(mix(Color(0xFF6E4632), base, 0.2f))
    val sheet = ink(mix(base, Color.White, 0.9f))
    val pillow = ink(mix(base, Color.White, 0.97f))
    val pillowLo = ink(mix(base, Color.White, 0.74f))
    val quilt = ink(mix(base, Color.White, 0.46f))
    val quiltLo = ink(mix(base, Color.White, 0.2f))
    val turn = ink(mix(base, Color.White, 0.84f))
    val floor = ink(mix(base, Color.Black, 0.6f))
    val white = ink(Color.White)
    soft(halo.copy(alpha = 0.32f), Offset(-42f, -78f), 26f, solid = 0.15f)
    drawPath(Bed.moon, moon)
    drawPath(Bed.stars, star)
    drawPath(Bed.specks, star.copy(alpha = 0.8f))
    withTransform({ scale(1.12f, 1.12f, Offset.Zero) }) {
        translate(7f, 0f) {
            soft(floor.copy(alpha = 0.5f), Offset(-38f, -1.2f), 40f, 4f, solid = 0.2f)
            drawPath(Bed.legs, woodLo)
            drawRoundRect(
                Brush.linearGradient(listOf(wood, woodLo), start = Offset(-23f, 0f), end = Offset(-13f, 0f)),
                Offset(-23f, -57f),
                Size(10f, 59f),
                CornerRadius(5f),
            )
            drawRoundRect(white.copy(alpha = 0.16f), Offset(-21.2f, -52f), Size(2.2f, 40f), CornerRadius(1.1f))
            drawRoundRect(
                Brush.linearGradient(listOf(wood, woodLo), start = Offset(-69f, 0f), end = Offset(-63f, 0f)),
                Offset(-69f, -33f),
                Size(6f, 33f),
                CornerRadius(3f),
            )
            drawRoundRect(woodLo, Offset(-65f, -17f), Size(46f, 8f), CornerRadius(2f))
            drawRoundRect(sheet, Offset(-64.5f, -26f), Size(45f, 10f), CornerRadius(3f))
            drawPath(
                Bed.pillow,
                Brush.linearGradient(listOf(pillow, pillowLo), start = Offset(0f, -38.6f), end = Offset(0f, -26f)),
            )
            drawPath(Bed.seam, pillowLo.copy(alpha = 0.8f), style = ROUND_0_7)
            drawPath(
                Bed.quilt,
                Brush.linearGradient(listOf(quilt, quiltLo), start = Offset(0f, -30f), end = Offset(0f, -10.5f)),
            )
            drawPath(Bed.turn, turn)
            drawPath(Bed.folds, turn.copy(alpha = 0.5f), style = ROUND_0_8)
        }
    }
}

/** The bed, its quilt and the night over it. */
private object Bed {
    val moon = svg("M-32.662 -74.423A10 10 0 1 1 -43.228 -87.924A8.6 8.6 0 0 0 -32.662 -74.423Z")
    val stars = svg(
        "M-72 -88.4Q-71.52 -86.48 -69.6 -86Q-71.52 -85.52 -72 -83.6Q-72.48 -85.52 -74.4 -86" +
        "Q-72.48 -86.48 -72 -88.4ZM-16 -90.2Q-15.56 -88.44 -13.8 -88Q-15.56 -87.56 -16 -85.8" +
        "Q-16.44 -87.56 -18.2 -88Q-16.44 -88.44 -16 -90.2ZM-24 -65.8Q-23.64 -64.36 -22.2 -64" +
        "Q-23.64 -63.64 -24 -62.2Q-24.36 -63.64 -25.8 -64Q-24.36 -64.36 -24 -65.8ZM-62 -63.6" +
        "Q-61.68 -62.32 -60.4 -62Q-61.68 -61.68 -62 -60.4Q-62.32 -61.68 -63.6 -62Q-62.32 -62.32 -62 -63.6Z"
    )
    val specks = svg(
        "M-82.8 -70a0.8 0.8 0 1 0 1.6 0a0.8 0.8 0 1 0 -1.6 0ZM-30.7 -96a0.7 0.7 0 1 0 1.4 0" +
        "a0.7 0.7 0 1 0 -1.4 0ZM-6.6 -76a0.6 0.6 0 1 0 1.2 0a0.6 0.6 0 1 0 -1.2 0ZM-54.6 -96" +
        "a0.6 0.6 0 1 0 1.2 0a0.6 0.6 0 1 0 -1.2 0Z"
    )
    val legs = svg("M-62 -10h2.6v10h-2.6ZM-25 -10h2.6v10h-2.6Z")
    val pillow = svg(
        "M-38.5 -30.5C-38.5 -35.6 -35 -38.6 -30 -38.6H-25C-22 -38.6 -20.5 -36 -20.5 -33V-30.5" +
        "C-20.5 -27.6 -22.5 -26 -25.5 -26H-34.5C-37 -26 -38.5 -27.6 -38.5 -30.5Z"
    )
    val seam = svg("M-35.5 -34.6C-32 -36 -27 -36.2 -23.5 -35")
    val quilt = svg(
        "M-65 -26.8C-58.6 -29.6 -48 -30.4 -40.6 -29C-36.6 -28.2 -34.2 -26.4 -33.6 -24L-33.2 -14.6" +
        "C-33 -12.4 -34.6 -11.2 -37 -11.6C-40 -12 -42.2 -10.4 -45.2 -10.6" +
        "C-48.2 -10.8 -50.2 -12.4 -53.2 -12C-56.2 -11.6 -58.2 -10.2 -61.2 -10.6" +
        "C-63.8 -11 -65 -12.4 -65 -15Z"
    )
    val turn = svg(
        "M-40.6 -29C-36.6 -28.2 -34.2 -26.4 -33.6 -24L-33.2 -14.6C-33 -12.4 -34.6 -11.2 -37 -11.6" +
        "C-38 -11.8 -38.6 -12 -39.2 -12.4C-38 -17.4 -38 -24 -40.6 -29Z"
    )
    val folds = svg("M-61 -23.6C-55 -24.6 -49 -24.6 -43.4 -23.4M-60 -18C-54.6 -19 -49.4 -19 -44.4 -17.8")
}

/**
 * Fuel: a gas pump before a charging post, on one island (the owner, 6 November: "Gas and charging belongs both into
 * fuel"). The pump stands in front, the drop of its fuel on its screen and its nozzle on its hook; the post, taller,
 * behind it, lit by its own current, its screen showing the bolt and its cable hanging from its holster. The two keep
 * right of a card's words, a long "till 22:00" too, where the post stood alone and its cable coiled toward them.
 */
private fun DrawScope.current(base: Color, s: Float) {
    fun ink(c: Color) = tone(c, base, s)
    val halo = ink(mix(base, Color.White, 0.55f))
    val body = ink(mix(base, Color.White, 0.95f))
    val bodyLo = ink(mix(base, Color.White, 0.74f))
    val post = ink(mix(base, Color.White, 0.82f))
    val postLo = ink(mix(base, Color.White, 0.6f))
    val door = ink(mix(base, Color.White, 0.58f))
    val cap = ink(mix(base, Color.Black, 0.38f))
    val screen = ink(mix(base, Color.Black, 0.66f))
    val spark = ink(Color(0xFFFFD84D))
    val cable = ink(mix(base, Color.Black, 0.55f))
    val plinth = ink(mix(base, Color.Black, 0.25f))
    val floor = ink(mix(base, Color.Black, 0.6f))
    val white = ink(Color.White)
    soft(halo.copy(alpha = 0.32f), Offset(-20f, -68f), 40f, solid = 0.05f)
    soft(floor.copy(alpha = 0.45f), Offset(-30f, -1f), 30f, 4f, solid = 0.2f)
    // the charging post, behind: a shade deeper than the pump, so the pump stands before it
    drawRoundRect(
        Brush.linearGradient(listOf(post, postLo), start = Offset(-31f, 0f), end = Offset(-9f, 0f)),
        Offset(-31f, -87f),
        Size(22f, 93f),
        CornerRadius(7f),
    )
    drawPath(Station.cable, cable, style = ROUND_3)
    drawRoundRect(cap, Offset(-18.5f, -52f), Size(7f, 14f), CornerRadius(2.5f))
    drawRoundRect(cap, Offset(-33f, -92f), Size(26f, 9f), CornerRadius(4.5f))
    drawRoundRect(screen, Offset(-26.5f, -80f), Size(13f, 17f), CornerRadius(3f))
    drawPath(Station.bolt, spark)
    // the pump, before it
    drawPath(Station.hose, cable, style = ROUND_2_4)
    drawRoundRect(
        Brush.linearGradient(listOf(body, bodyLo), start = Offset(-48f, 0f), end = Offset(-24f, 0f)),
        Offset(-48f, -55f),
        Size(24f, 61f),
        CornerRadius(5f),
    )
    drawRoundRect(white.copy(alpha = 0.6f), Offset(-45.5f, -51f), Size(2.5f, 40f), CornerRadius(1.25f))
    drawRoundRect(cap, Offset(-50f, -60f), Size(28f, 8f), CornerRadius(4f))
    drawRoundRect(screen, Offset(-43f, -48f), Size(14f, 13f), CornerRadius(2.5f))
    drawPath(Station.drop, body)
    drawRoundRect(door, Offset(-42.5f, -29f), Size(13f, 18f), CornerRadius(2f), style = LINE_1)
    drawRoundRect(cap, Offset(-52f, -39f), Size(4.5f, 8f), CornerRadius(1.5f))
    drawRoundRect(cap, Offset(-56.5f, -46f), Size(3.5f, 9f), CornerRadius(1.5f))
    drawPath(Station.spout, cap, style = ROUND_2)
    drawRoundRect(plinth, Offset(-55f, -6f), Size(53f, 7f), CornerRadius(2.5f))
    drawPath(Station.sparks, spark)
}

/** The island's pump and post: the pump's hose, nozzle spout and drop, the post's cable, bolt and sparks. */
private object Station {
    val hose = svg("M-48 -53C-55 -55 -59 -50 -55 -46")
    val spout = svg("M-54.5 -37L-51.5 -34.5")
    val drop = svg("M-36 -46.7C-37.7 -44.2 -39.3 -42.5 -39.3 -40.5A3.3 3.3 0 0 0 -32.7 -40.5C-32.7 -42.5 -34.3 -44.2 -36 -46.7Z")
    val cable = svg("M-15 -40C-15.5 -30 -21.5 -25 -20.5 -16C-19.8 -9.5 -14 -7 -9.5 -7")
    val bolt = svg("M-18.75 -77.74L-23.28 -70.64H-20.16L-21.25 -65.26L-16.57 -72.67H-19.69Z")
    val sparks = svg(
        "M-39 -85.4Q-38.52 -83.48 -36.6 -83Q-38.52 -82.52 -39 -80.6Q-39.48 -82.52 -41.4 -83Q-39.48 -83.48 -39 -85.4Z" +
        "M-42.5 -72.5Q-42.2 -71.3 -41 -71Q-42.2 -70.7 -42.5 -69.5Q-42.8 -70.7 -44 -71Q-42.8 -71.3 -42.5 -72.5Z"
    )
}

/** Nature: hills behind hills and a stand of trees by a pond under a low sun, the trees and pond after concept 10. */
private fun DrawScope.hills(base: Color, s: Float) {
    fun ink(c: Color) = tone(c, base, s)
    val sun = ink(Color(0xFFFFEBC0))
    val far = ink(mix(base, Color(0xFFD8F0A8), 0.28f))
    val near = ink(mix(base, Color.Black, 0.14f))
    val crown = ink(mix(base, Color(0xFF8FE07A), 0.38f))
    val crown2 = ink(mix(base, Color(0xFF2F7A3E), 0.45f))
    val crown3 = ink(mix(base, Color(0xFFC4F09A), 0.5f))
    val pine = ink(mix(base, Color(0xFF1E5A34), 0.5f))
    val trunk = ink(mix(Color(0xFF6B4A2E), base, 0.25f))
    val shadow = ink(mix(base, Color(0xFF0F3A22), 0.7f))
    val pond = ink(Color(0xFF86D4E0))
    val bird = ink(mix(base, Color.Black, 0.55f))
    val white = ink(Color.White)
    soft(sun.copy(alpha = 0.35f), Offset(-22f, -76f), 26f, solid = 0.1f)
    drawCircle(sun, 7f, Offset(-22f, -76f))
    drawPath(Meadow.birds, bird.copy(alpha = 0.7f), style = ROUND_0_8)
    drawPath(
        Meadow.far,
        Brush.linearGradient(listOf(far.copy(alpha = 0f), far), start = Offset(-210f, 0f), end = Offset(-110f, 0f)),
    )
    drawPath(
        Meadow.near,
        Brush.linearGradient(listOf(near.copy(alpha = 0f), near), start = Offset(-170f, 0f), end = Offset(-80f, 0f)),
    )
    drawPath(Meadow.pine, pine)
    drawPath(Meadow.trunks, trunk)
    soft(shadow.copy(alpha = 0.45f), Offset(-36.9f, -49.8f), 13.2f, solid = 0.6f)
    soft(shadow.copy(alpha = 0.45f), Offset(-18.3f, -45f), 12f, solid = 0.6f)
    drawCircle(crown, 13.2f, Offset(-38.7f, -52.2f))
    drawCircle(crown2, 12f, Offset(-20.1f, -47.4f))
    drawCircle(crown3, 10.2f, Offset(-27.9f, -62.4f))
    drawPath(Meadow.dapple, white.copy(alpha = 0.22f))
    drawOval(pond.copy(alpha = 0.92f), Offset(-45.9f, -25.5f), Size(42f, 15f))
    drawPath(Meadow.ripples, white.copy(alpha = 0.7f), style = ROUND_0_75)
}

/** The meadow's hills, trees and pond. */
private object Meadow {
    val birds = svg("M-52 -82q2 -2.2 4 0q2 -2.2 4 0M-43 -88q1.5 -1.6 3 0q1.5 -1.6 3 0")
    val far = svg("M-320 -34C-250 -40 -190 -38 -140 -44 -100 -49 -74 -60 -46 -59 -24 -58 -8 -52 8 -49V4H-320Z")
    val near = svg("M-200 -12C-150 -16 -108 -28 -74 -33 -46 -37 -22 -36 8 -30V4H-200Z")
    val pine = svg("M-56 -60L-49 -38H-63Z")
    val trunks = svg("M-57 -38.5h2v4.5h-2ZM-40 -42h2.6v8h-2.6ZM-21.5 -38h2.6v6h-2.6Z")
    val dapple = svg(
        "M-46.5 -57a3.6 3.6 0 1 0 7.2 0a3.6 3.6 0 1 0 -7.2 0ZM-26.7 -51.6a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z" +
        "M-34.2 -66a2.7 2.7 0 1 0 5.4 0a2.7 2.7 0 1 0 -5.4 0Z"
    )
    val ripples = svg("M-36.9 -18h12M-31.5 -15h10.2")
}

/** Fun: a big wheel at the fair, its cabins in two colours and its lights on, under the sweep of two searchlights. */
private fun DrawScope.beams(base: Color, s: Float) {
    fun ink(c: Color) = tone(c, base, s)
    val beam = ink(mix(base, Color(0xFFFFF1CF), 0.85f))
    val halo = ink(mix(base, Color.White, 0.5f))
    val rim = ink(mix(base, Color.White, 0.9f))
    val leg = ink(mix(base, Color.Black, 0.4f))
    val cabin = ink(Color(0xFFFF7A6B))
    val cabin2 = ink(Color(0xFFFFC84A))
    val light = ink(Color(0xFFFFE7A0))
    val hub = ink(mix(base, Color.Black, 0.45f))
    drawPath(
        Fair.beam,
        Brush.linearGradient(
            listOf(beam.copy(alpha = 0.32f), beam.copy(alpha = 0f)),
            start = Offset(0f, 2f),
            end = Offset(0f, -100f),
        ),
    )
    drawPath(
        Fair.beam2,
        Brush.linearGradient(
            listOf(beam.copy(alpha = 0.26f), beam.copy(alpha = 0f)),
            start = Offset(0f, 2f),
            end = Offset(0f, -100f),
        ),
    )
    soft(halo.copy(alpha = 0.22f), Offset(-32f, -56f), 40f, solid = 0.05f)
    drawPath(Fair.legs, leg, style = ROUND_3)
    drawCircle(rim.copy(alpha = 0.6f), 24f, Offset(-32f, -56f), style = ROUND_1)
    drawPath(Fair.spokes, rim.copy(alpha = 0.75f), style = ROUND_0_8)
    drawCircle(rim, 30f, Offset(-32f, -56f), style = ROUND_2_2)
    drawPath(Fair.lights, light)
    drawPath(Fair.cabins, cabin)
    drawPath(Fair.cabins2, cabin2)
    drawCircle(hub, 4.4f, Offset(-32f, -56f))
    drawCircle(rim, 1.9f, Offset(-32f, -56f))
    drawRoundRect(leg, Offset(-56f, -4f), Size(48f, 6f), CornerRadius(2f))
    drawPath(Fair.confetti, light)
}

/** The big wheel and the searchlights. */
private object Fair {
    val beam = svg("M-12 2L-78 -102H-60Z")
    val beam2 = svg("M-54 2L-14 -102H4Z")
    val legs = svg("M-32 -56L-49 -1M-32 -56L-15 -1")
    val spokes = svg(
        "M-28 -56L-2 -56M-28.536 -54L-6.019 -41M-30 -52.536L-17 -30.019M-32 -52L-32 -26M-34 -52.536" +
        "L-47 -30.019M-35.464 -54L-57.981 -41M-36 -56L-62 -56M-35.464 -58L-57.981 -71M-34 -59.464" +
        "L-47 -81.981M-32 -60L-32 -86M-30 -59.464L-17 -81.981M-28.536 -58L-6.019 -71"
    )
    val lights = svg(
        "M-3.25 -56a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0 -2.5 0ZM-12.037 -34.787a1.25 1.25 0 1 0 2.5 0" +
        "a1.25 1.25 0 1 0 -2.5 0ZM-33.25 -26a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0 -2.5 0Z" +
        "M-54.463 -34.787a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0 -2.5 0ZM-63.25 -56a1.25 1.25 0 1 0 2.5 0" +
        "a1.25 1.25 0 1 0 -2.5 0ZM-54.463 -77.213a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0 -2.5 0Z" +
        "M-33.25 -86a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0 -2.5 0ZM-12.037 -77.213a1.25 1.25 0 1 0 2.5 0" +
        "a1.25 1.25 0 1 0 -2.5 0Z"
    )
    val cabins = svg(
        "M-7.684 -43.919h6.8v2.6a3.4 3.4 0 0 1 -6.8 0ZM-6.684 -44.719h4.8M-46.881 -27.684h6.8v2.6" +
        "a3.4 3.4 0 0 1 -6.8 0ZM-45.881 -28.484h4.8M-63.116 -66.881h6.8v2.6a3.4 3.4 0 0 1 -6.8 0Z" +
        "M-62.116 -67.681h4.8M-23.919 -83.116h6.8v2.6a3.4 3.4 0 0 1 -6.8 0ZM-22.919 -83.916h4.8"
    )
    val cabins2 = svg(
        "M-23.919 -27.684h6.8v2.6a3.4 3.4 0 0 1 -6.8 0ZM-22.919 -28.484h4.8M-63.116 -43.919h6.8v2.6" +
        "a3.4 3.4 0 0 1 -6.8 0ZM-62.116 -44.719h4.8M-46.881 -83.116h6.8v2.6a3.4 3.4 0 0 1 -6.8 0Z" +
        "M-45.881 -83.916h4.8M-7.684 -66.881h6.8v2.6a3.4 3.4 0 0 1 -6.8 0ZM-6.684 -67.681h4.8"
    )
    val confetti = svg(
        "M-70 -84.2Q-69.56 -82.44 -67.8 -82Q-69.56 -81.56 -70 -79.8Q-70.44 -81.56 -72.2 -82" +
        "Q-70.44 -82.44 -70 -84.2ZM-6 -90Q-5.6 -88.4 -4 -88Q-5.6 -87.6 -6 -86Q-6.4 -87.6 -8 -88" +
        "Q-6.4 -88.4 -6 -90ZM-64 -31.5Q-63.7 -30.3 -62.5 -30Q-63.7 -29.7 -64 -28.5" +
        "Q-64.3 -29.7 -65.5 -30Q-64.3 -30.3 -64 -31.5ZM-5 -28.4Q-4.72 -27.28 -3.6 -27" +
        "Q-4.72 -26.72 -5 -25.6Q-5.28 -26.72 -6.4 -27Q-5.28 -27.28 -5 -28.4Z"
    )
}

/** Water: a lake at sundown after concept 10's, the sun's light on it, a swimmer and a jetty with its ladder. */
private fun DrawScope.waves(base: Color, s: Float) {
    fun ink(c: Color) = tone(c, base, s)
    val glow = ink(Color(0xFFFFD8A4))
    val sun = ink(Color(0xFFFFF6E2))
    val shore = ink(mix(base, Color(0xFF1F4A3E), 0.72f))
    val shore2 = ink(mix(base, Color(0xFF11302A), 0.78f))
    val water = ink(mix(base, Color.White, 0.08f))
    val deep = ink(mix(base, Color(0xFF06222E), 0.62f))
    val light = ink(Color(0xFFFFD9A4))
    val night = ink(mix(base, Color(0xFF0A1C22), 0.86f))
    val white = ink(Color.White)
    soft(glow.copy(alpha = 0.5f), Offset(-38f, -50f), 50f, 46f, solid = 0.04f)
    soft(glow.copy(alpha = 0.3f), Offset(-38f, -46.5f), 80f, 5f, solid = 0.2f)
    drawRect(
        Brush.linearGradient(listOf(water, deep), start = Offset(0f, -44.2f), end = Offset(0f, 2f)),
        Offset(-4000f, -44.2f),
        Size(4004f, 48f),
    )
    soft(glow.copy(alpha = 0.28f), Offset(-38f, -42f), 36f, 9f, solid = 0.2f)
    art(-136.7f, -112f, 0.48f) {
        soft(sun.copy(alpha = 0.45f), Offset(206f, 125f), 24f, solid = 0.3f)
        drawCircle(sun, 11.5f, Offset(206f, 125f))
        drawPath(Lake.shore, shore)
        drawPath(Lake.shore2, shore2)
        drawPath(Lake.reach, shore)
        drawRect(shore2, Offset(-9000f, 138.6f), Size(9001f, 6.4f))
        drawPath(
            Lake.bars,
            Brush.linearGradient(
                listOf(light.copy(alpha = 0.95f), light.copy(alpha = 0.12f)),
                start = Offset(0f, 145f),
                end = Offset(0f, 223f),
            ),
        )
        translate(206f, 188f) {
            drawPath(Lake.ripple, white.copy(alpha = 0.2f), style = ROUND_1_2)
            drawPath(Lake.ripple2, white.copy(alpha = 0.38f), style = ROUND_1_2)
            drawCircle(night, 4.4f, Offset(0f, -3.4f))
            drawPath(Lake.arm, night, style = ROUND_2_6)
        }
        translate(-26f, 0f) {
            drawPath(Lake.jetty, night)
            drawPath(Lake.posts, night)
            drawPath(Lake.ladder, night, style = ROUND_2_6)
            drawPath(Lake.edge, light.copy(alpha = 0.3f), style = LINE_1)
        }
    }
}

/** The lake's shore, light, swimmer and jetty, in concept 10's 400 by 240. */
private object Lake {
    val shore = svg(
        "M0 133c10 -4 18 -2 26 -5" +
        "s16 1 24 -2 14 2 22 -1 18 3 28 0 20 2 30 -1 22 3 34 0 24 2 36 -1 26 3 40 0 28 2 42 -1 26 2" +
        " 38 -1 30 3 48 0 24 -1 32 -1V144H0z"
    )
    val shore2 = svg("M0 138c40 -3 80 1 130 -2s120 3 180 -1 70 -2 90 -2V145H0z")
    val reach = svg("M0 133C-60 135 -150 136.6 -1200 137.2H-9000V145H0Z")
    val bars = svg(
        "M199 145H213A1 1 0 0 1 214 146V146A1 1 0 0 1 213 147H199A1 1 0 0 1 198 146V146A1 1 0 0 1 199 145Z" +
        "M194 150H218A1 1 0 0 1 219 151V151A1 1 0 0 1 218 152H194A1 1 0 0 1 193 151V151A1 1 0 0 1 194 150Z" +
        "M201 155H211A1 1 0 0 1 212 156V156A1 1 0 0 1 211 157H201A1 1 0 0 1 200 156V156A1 1 0 0 1 201 155Z" +
        "M189.1 161H220.9A1.1 1.1 0 0 1 222 162.1V162.1A1.1 1.1 0 0 1 220.9 163.2H189.1" +
        "A1.1 1.1 0 0 1 188 162.1V162.1A1.1 1.1 0 0 1 189.1 161ZM199.1 168H214.9A1.1 1.1 0 0 1 216 169.1" +
        "V169.1A1.1 1.1 0 0 1 214.9 170.2H199.1A1.1 1.1 0 0 1 198 169.1V169.1A1.1 1.1 0 0 1 199.1 168Z" +
        "M183.2 176H224.8A1.2 1.2 0 0 1 226 177.2V177.2A1.2 1.2 0 0 1 224.8 178.4H183.2" +
        "A1.2 1.2 0 0 1 182 177.2V177.2A1.2 1.2 0 0 1 183.2 176ZM197.2 185H216.8A1.2 1.2 0 0 1 218 186.2" +
        "V186.2A1.2 1.2 0 0 1 216.8 187.4H197.2A1.2 1.2 0 0 1 196 186.2V186.2A1.2 1.2 0 0 1 197.2 185Z" +
        "M177.3 195H230.7A1.3 1.3 0 0 1 232 196.3V196.3A1.3 1.3 0 0 1 230.7 197.6H177.3" +
        "A1.3 1.3 0 0 1 176 196.3V196.3A1.3 1.3 0 0 1 177.3 195ZM193.3 207H220.7A1.3 1.3 0 0 1 222 208.3" +
        "V208.3A1.3 1.3 0 0 1 220.7 209.6H193.3A1.3 1.3 0 0 1 192 208.3V208.3A1.3 1.3 0 0 1 193.3 207Z" +
        "M173.4 220H236.6A1.4 1.4 0 0 1 238 221.4V221.4A1.4 1.4 0 0 1 236.6 222.8H173.4" +
        "A1.4 1.4 0 0 1 172 221.4V221.4A1.4 1.4 0 0 1 173.4 220Z"
    )
    val ripple = svg("M-34 0a34 5.6 0 1 0 68 0a34 5.6 0 1 0 -68 0Z")
    val ripple2 = svg("M-20 0a20 3.4 0 1 0 40 0a20 3.4 0 1 0 -40 0Z")
    val arm = svg("M-11 0q4 -7 9.5 -3.4")
    val jetty = svg("M262 200l138 -8v14l-140 8z")
    val posts = svg("M268 212h4v30h-4ZM306 209h4v34h-4ZM346 206h4v36h-4Z")
    val ladder = svg("M284 186v32M296 185v32M284 192h12M284 200h12M284 208h12")
    val edge = svg("M262 214l138 -8")
}

/** Culture: a portico in warm stone, pediment, columns and steps, a banner hung inside and the doorway lit. */
private fun DrawScope.colonnade(base: Color, s: Float) {
    fun ink(c: Color) = tone(c, base, s)
    val halo = ink(mix(base, Color.White, 0.55f))
    val stone = ink(mix(Color(0xFFFBEBD6), base, 0.1f))
    val stoneLo = ink(mix(Color(0xFFE9CFB2), base, 0.22f))
    val tympanum = ink(mix(Color(0xFFE2C3A3), base, 0.35f))
    val inside = ink(mix(base, Color.Black, 0.52f))
    val insideLo = ink(mix(base, Color.Black, 0.36f))
    val door = ink(Color(0xFFFFD98A))
    val banner = ink(Color(0xFFF2C14E))
    val bannerLo = ink(Color(0xFFD99A2B))
    val shade = ink(mix(base, Color.Black, 0.7f))
    val white = ink(Color.White)
    soft(halo.copy(alpha = 0.24f), Offset(-30f, -66f), 50f, solid = 0.05f)
    drawRect(
        Brush.linearGradient(listOf(inside, insideLo), start = Offset(0f, -61f), end = Offset(0f, -14f)),
        Offset(-62f, -61f),
        Size(70f, 47f),
    )
    drawPath(Portico.door, door.copy(alpha = 0.9f))
    soft(door.copy(alpha = 0.3f), Offset(-33f, -28f), 10f, 14f, solid = 0.2f)
    drawPath(Portico.shafts, stone)
    drawPath(Portico.flutes, stoneLo)
    drawPath(Portico.capitals, stone)
    drawPath(
        Portico.banner,
        Brush.linearGradient(listOf(banner, bannerLo), start = Offset(-52.2f, 0f), end = Offset(-47.8f, 0f)),
    )
    drawRect(stone, Offset(-66f, -69f), Size(76f, 8f))
    drawRect(shade.copy(alpha = 0.22f), Offset(-66f, -63f), Size(76f, 1.6f))
    drawPath(Portico.dentils, stoneLo)
    drawPath(Portico.pediment, stone)
    drawPath(Portico.tympanum, tympanum)
    drawPath(Portico.cornice, white.copy(alpha = 0.55f), style = ROUND_1)
    drawPath(Portico.steps, stoneLo)
    drawPath(Portico.treads, white.copy(alpha = 0.6f))
}

/** The portico's stones. */
private object Portico {
    val door = svg("M-37.5 -14V-38a4.5 4.5 0 0 1 9 0V-14Z")
    val shafts = svg("M-60 -59h7v44h-7ZM-46 -59h7v44h-7ZM-32 -59h7v44h-7ZM-18 -59h7v44h-7ZM-4 -59h7v44h-7Z")
    val flutes = svg(
        "M-55.4 -59h2.4v44h-2.4ZM-41.4 -59h2.4v44h-2.4ZM-27.4 -59h2.4v44h-2.4ZM-13.4 -59h2.4v44h-2.4Z" +
        "M0.6 -59h2.4v44h-2.4Z"
    )
    val capitals = svg(
        "M-61.3 -61.5h9.6v2.6h-9.6ZM-47.3 -61.5h9.6v2.6h-9.6ZM-33.3 -61.5h9.6v2.6h-9.6ZM-19.3 -61.5h9.6" +
        "v2.6h-9.6ZM-5.3 -61.5h9.6v2.6h-9.6ZM-61.3 -17h9.6v2.6h-9.6ZM-47.3 -17h9.6v2.6h-9.6ZM-33.3 -17" +
        "h9.6v2.6h-9.6ZM-19.3 -17h9.6v2.6h-9.6ZM-5.3 -17h9.6v2.6h-9.6Z"
    )
    val banner = svg("M-52.2 -59H-47.8V-38L-50 -40.4L-52.2 -38Z")
    val dentils = svg(
        "M-64 -67.2h2v1.8h-2ZM-60 -67.2h2v1.8h-2ZM-56 -67.2h2v1.8h-2ZM-52 -67.2h2v1.8h-2ZM-48 -67.2h2" +
        "v1.8h-2ZM-44 -67.2h2v1.8h-2ZM-40 -67.2h2v1.8h-2ZM-36 -67.2h2v1.8h-2ZM-32 -67.2h2v1.8h-2Z" +
        "M-28 -67.2h2v1.8h-2ZM-24 -67.2h2v1.8h-2ZM-20 -67.2h2v1.8h-2ZM-16 -67.2h2v1.8h-2ZM-12 -67.2h2" +
        "v1.8h-2ZM-8 -67.2h2v1.8h-2ZM-4 -67.2h2v1.8h-2ZM0 -67.2h2v1.8h-2ZM4 -67.2h2v1.8h-2ZM8 -67.2h2" +
        "v1.8h-2Z"
    )
    val pediment = svg("M-69 -69L-30 -91L9 -69Z")
    val tympanum = svg("M-59 -71.2L-30 -87.4L-1 -71.2Z")
    val cornice = svg("M-69 -69L-30 -91L9 -69")
    val steps = svg("M-66 -14.4h76v4.8h-76ZM-69 -9.6h79v4.8h-79ZM-72 -4.8h82v6.8h-82Z")
    val treads = svg("M-66 -14.4h76v1.2h-76ZM-69 -9.6h79v1.2h-79ZM-72 -4.8h82v1.2h-82Z")
}

/** Sport: a football on a mown pitch before the goal and its net, in a floodlight's beam. */
private fun DrawScope.pitch(base: Color, s: Float) {
    fun ink(c: Color) = tone(c, base, s)
    val glow = ink(mix(base, Color(0xFFFFF4D6), 0.8f))
    val grass = ink(mix(base, Color(0xFF2E8B4A), 0.6f))
    val grass2 = ink(mix(base, Color(0xFF58C06C), 0.55f))
    val chalk = ink(Color.White)
    val mast = ink(mix(base, Color.Black, 0.45f))
    val lamp = ink(Color(0xFFFFF1C2))
    val shade = ink(mix(base, Color.White, 0.72f))
    val patch = ink(mix(base, Color.Black, 0.78f))
    val shadow = ink(mix(base, Color.Black, 0.6f))
    soft(glow.copy(alpha = 0.26f), Offset(-6f, -88f), 34f, solid = 0.06f)
    drawPath(
        Stadium.beam,
        Brush.linearGradient(
            listOf(glow.copy(alpha = 0.3f), glow.copy(alpha = 0f)),
            start = Offset(-6f, -86f),
            end = Offset(-60f, 4f),
        ),
    )
    // the pitch, mown in bands, coming in from the words' side
    for ((i, top) in Stadium.bands.withIndex()) {
        val green = if (i % 2 == 0) grass else grass2
        val foot = Stadium.bands.getOrElse(i + 1) { 4f }
        drawRect(
            Brush.linearGradient(listOf(green.copy(alpha = 0f), green), start = Offset(-190f, 0f), end = Offset(-100f, 0f)),
            Offset(-260f, top),
            Size(272f, foot - top),
        )
    }
    drawLine(
        Brush.linearGradient(
            listOf(chalk.copy(alpha = 0f), chalk.copy(alpha = 0.85f)),
            start = Offset(-170f, 0f),
            end = Offset(-100f, 0f),
        ),
        Offset(-260f, -36f),
        Offset(12f, -36f),
        strokeWidth = 1.2f,
    )
    // the box's corner comes down by the card's figure: its chalk fades there too
    drawPath(
        Stadium.box,
        Brush.linearGradient(listOf(chalk.copy(alpha = 0f), chalk.copy(alpha = 0.7f)), start = Offset(-112f, 0f), end = Offset(-84f, 0f)),
        style = ROUND_1_2,
    )
    // the net, its back and roof behind the posts
    drawPath(Stadium.net, chalk.copy(alpha = 0.32f), style = ROUND_0_7)
    drawPath(Stadium.back, chalk.copy(alpha = 0.55f), style = ROUND_0_8)
    drawPath(Stadium.posts, chalk, style = ROUND_2_4)
    // the floodlight
    drawLine(mast, Offset(-6f, -84f), Offset(-6f, 4f), strokeWidth = 2f)
    drawRoundRect(mast, Offset(-15f, -92f), Size(18f, 8f), CornerRadius(1.5f))
    drawPath(Stadium.lamps, lamp)
    // the ball, lit from the top left, on its shadow
    soft(shadow.copy(alpha = 0.45f), Offset(-43.5f, -3.8f), 9.5f, 2.6f, solid = 0.4f)
    drawCircle(
        Brush.radialGradient(0f to chalk, 0.55f to chalk, 1f to shade, center = Offset(-48.5f, -14.5f), radius = 11f),
        8.5f,
        Offset(-46f, -12f),
    )
    clipPath(Stadium.ball) { drawPath(Stadium.rim, patch) }
    drawPath(Stadium.patch, patch)
    drawPath(Stadium.seams, patch, style = ROUND_0_8)
}

/** The pitch's bands, chalk, goal and floodlight, and the ball's patches. */
private object Stadium {
    /** Where each band of mown grass starts, from the far one under the goal to the one at the card's foot. */
    val bands = listOf(-44f, -36f, -27f, -16f, -5f)
    val beam = svg("M-12 -86L-100 4H-44Z")
    val box = svg("M-101 -36L-107 -22H-3L-9 -36")
    val net = svg(
        "M-78.2 -68V-36M-72.4 -68V-36M-66.6 -68V-36M-60.8 -68V-36M-55 -68V-36M-49.2 -68V-36M-43.4 -68V-36" +
        "M-37.6 -68V-36M-31.8 -68V-36M-84 -62.667H-26M-84 -57.333H-26M-84 -52H-26M-84 -46.667H-26M-84 -41.333H-26"
    )
    val back = svg("M-80 -40V-72H-30V-40M-84 -68L-80 -72M-26 -68L-30 -72M-84 -36L-80 -40M-26 -36L-30 -40")
    val posts = svg("M-84 -36V-68H-26V-36")
    val lamps = svg("M-13.5 -90.5h4.5v5h-4.5ZM-8.25 -90.5h4.5v5h-4.5ZM-3 -90.5h4.5v5h-4.5Z")
    val ball = svg("M-37.5 -12a8.5 8.5 0 1 0 -17 0a8.5 8.5 0 1 0 17 0Z")
    val patch = svg("M-46 -15.2L-42.957 -12.989L-44.119 -9.411L-47.881 -9.411L-49.043 -12.989Z")
    val seams = svg(
        "M-46 -15.2L-46 -17.6M-42.957 -12.989L-40.674 -13.73M-44.119 -9.411L-42.708 -7.47M-47.881 -9.411" +
        "L-49.292 -7.47M-49.043 -12.989L-51.326 -13.73"
    )
    val rim = svg(
        "M-46 -17.7L-48.758 -19.704L-47.705 -22.946L-44.295 -22.946L-43.242 -19.704ZM-40.579 -13.761L-39.525 -17.004" +
        "L-36.116 -17.004L-35.063 -13.761L-37.821 -11.758ZM-42.65 -7.389L-39.24 -7.389L-38.187 -4.146L-40.945 -2.142" +
        "L-43.703 -4.146ZM-49.35 -7.389L-48.297 -4.146L-51.055 -2.142L-53.813 -4.146L-52.76 -7.389ZM-51.421 -13.761" +
        "L-54.179 -11.758L-56.937 -13.761L-55.884 -17.004L-52.475 -17.004Z"
    )
}

/** Health: the cross, raised off the card, a capsule across it and a heartbeat running in to it (concept 10). */
private fun DrawScope.pulse(base: Color, s: Float) {
    fun ink(c: Color) = tone(c, base, s)
    val beat = ink(mix(base, Color.White, 0.85f))
    val shadow = ink(mix(base, Color.Black, 0.62f))
    val cross = ink(mix(base, Color(0xFFFFC2B4), 0.62f))
    val crossLo = ink(mix(base, Color.Black, 0.04f))
    val edge = ink(mix(base, Color.White, 0.86f))
    val cap = ink(Color.White)
    val capLo = ink(mix(base, Color(0xFFE8EEF0), 0.86f))
    val capY = ink(Color(0xFFFFD66A))
    val capYLo = ink(Color(0xFFE2951A))
    val white = ink(Color.White)
    drawPath(
        Cross.beat,
        Brush.linearGradient(
            listOf(beat.copy(alpha = 0f), beat.copy(alpha = 0.5f)),
            start = Offset(-150f, 0f),
            end = Offset(-60f, 0f),
        ),
        style = ROUND_1_3,
    )
    art(-93.8f, -86.1f, 0.32f) {
        translate(6f, 14f) {
            softRect(shadow.copy(alpha = 0.42f), Offset(130f, 62f), Size(176f, 64f), 16f, 9f)
            softRect(shadow.copy(alpha = 0.42f), Offset(186f, 6f), Size(64f, 176f), 16f, 9f)
        }
        drawPath(
            Cross.cross,
            Brush.linearGradient(listOf(cross, crossLo), start = Offset(130f, 6f), end = Offset(306f, 182f)),
        )
        drawPath(Cross.edge, edge.copy(alpha = 0.8f), style = ROUND_2)
        translate(30f, -50f) {
            rotate(-30f, Offset(160f, 186f)) {
                softRect(shadow.copy(alpha = 0.35f), Offset(100f, 174f), Size(124f, 40f), 20f, 5f)
                drawPath(
                    Cross.half,
                    Brush.linearGradient(listOf(cap, capLo), start = Offset(0f, 166f), end = Offset(0f, 206f)),
                )
                drawPath(
                    Cross.half2,
                    Brush.linearGradient(listOf(capY, capYLo), start = Offset(0f, 166f), end = Offset(0f, 206f)),
                )
                drawPath(Cross.gleam, white.copy(alpha = 0.7f), style = ROUND_3_5)
            }
        }
    }
}

/** The cross, the capsule and the heartbeat, the first two in concept 10's 300 by 300. */
private object Cross {
    val beat = svg("M-150 -61H-86L-82 -67L-77 -50L-72 -73L-67 -61H-54")
    val cross = svg(
        "M202 6H234A16 16 0 0 1 250 22V62H290A16 16 0 0 1 306 78V110A16 16 0 0 1 290 126H250V166" +
        "A16 16 0 0 1 234 182H202A16 16 0 0 1 186 166V126H146A16 16 0 0 1 130 110V78A16 16 0 0 1 146 62" +
        "H186V22A16 16 0 0 1 202 6Z"
    )
    val edge = svg("M146 63h40V22a15 15 0 0 1 15 -15h34")
    val half = svg("M118 166h42v40h-42a20 20 0 0 1 0 -40z")
    val half2 = svg("M160 166h42a20 20 0 0 1 0 40h-42z")
    val gleam = svg("M118 173h80")
}

/** Services: a gear with a brass nut on its hub and a wrench across it, the steel catching the light (concept 10). */
private fun DrawScope.gears(base: Color, s: Float) {
    fun ink(c: Color) = tone(c, base, s)
    val shadow = ink(mix(base, Color.Black, 0.5f))
    val gear = ink(mix(base, Color.White, 0.66f))
    val gearLo = ink(mix(base, Color.White, 0.3f))
    val hub = ink(mix(base, Color.Black, 0.2f))
    val hole = ink(mix(base, Color.White, 0.1f))
    val steel = ink(mix(Color(0xFFF4F6F9), base, 0.04f))
    val steel2 = ink(mix(Color(0xFFD4DAE3), base, 0.08f))
    val steel3 = ink(mix(Color(0xFFB9C2CF), base, 0.12f))
    val brass = ink(Color(0xFFF2B544))
    val brassLo = ink(Color(0xFFC9842A))
    val white = ink(Color.White)
    art(-110.4f, -92f, 0.43f) {
        soft(shadow.copy(alpha = 0.42f), Offset(224f, 88f), 80f, solid = 0.7f)
        drawPath(
            Workshop.teeth,
            Brush.linearGradient(listOf(gear, gearLo), start = Offset(148f, 10f), end = Offset(288f, 150f)),
        )
        drawCircle(
            Brush.linearGradient(listOf(gear, gearLo), start = Offset(158f, 20f), end = Offset(278f, 140f)),
            60f,
            Offset(218f, 80f),
        )
        drawCircle(hub, 22f, Offset(218f, 80f))
        drawPath(Workshop.holes, hole)
        drawPath(
            Workshop.nut,
            Brush.linearGradient(listOf(brass, brassLo), start = Offset(203f, 63f), end = Offset(233f, 97f)),
        )
        drawCircle(hub, 6.5f, Offset(218f, 80f))
        rotate(-20f, Offset(170f, 60f)) {
            translate(2f, -110f) {
                softRect(shadow.copy(alpha = 0.4f), Offset(98f, 166f), Size(130f, 20f), 10f, 4f)
                drawRoundRect(
                    Brush.linearGradient(
                        listOf(steel, steel2, steel3),
                        start = Offset(84f, 0f),
                        end = Offset(226f, 0f),
                    ),
                    Offset(110f, 160f),
                    Size(116f, 20f),
                    CornerRadius(10f),
                )
                drawPath(
                    Workshop.jaw,
                    Brush.linearGradient(
                        listOf(steel, steel2, steel3),
                        start = Offset(84f, 0f),
                        end = Offset(226f, 0f),
                    ),
                )
                drawPath(Workshop.glint, white.copy(alpha = 0.6f), style = ROUND_2_4)
            }
        }
    }
}

/** The gear's teeth and holes, the wrench's jaw and the nut, in concept 10's 300 by 300. */
private object Workshop {
    val teeth = svg(
        "M212.009 24.321L209.335 -0.535A81 81 0 0 1 226.665 -0.535L223.991 24.321" +
        "A56 56 0 0 0 212.009 24.321ZM232.527 25.917L239.013 1.773A81 81 0 0 1 255.173 8.033L243.7 30.245" +
        "A56 56 0 0 0 232.527 25.917ZM251.083 34.817L265.852 14.646A81 81 0 0 1 278.66 26.322" +
        "L259.938 42.889A56 56 0 0 0 251.083 34.817ZM265.171 49.819L286.23 36.346" +
        "A81 81 0 0 1 293.955 51.859L270.512 60.545A56 56 0 0 0 265.171 49.819ZM272.888 68.897" +
        "L297.392 63.941A81 81 0 0 1 298.991 81.197L273.994 80.828A56 56 0 0 0 272.888 68.897Z" +
        "M273.193 89.475L297.832 93.705A81 81 0 0 1 293.089 110.374L269.914 100.999" +
        "A56 56 0 0 0 273.193 89.475ZM266.043 108.773L287.49 121.618A81 81 0 0 1 277.046 135.448" +
        "L258.822 118.335A56 56 0 0 0 266.043 108.773ZM252.405 124.185L267.764 143.911" +
        "A81 81 0 0 1 253.029 153.034L242.218 130.493A56 56 0 0 0 252.405 124.185ZM234.12 133.63" +
        "L241.316 157.572A81 81 0 0 1 224.281 160.756L222.342 135.831A56 56 0 0 0 234.12 133.63Z" +
        "M213.658 135.831L211.719 160.756A81 81 0 0 1 194.684 157.572L201.88 133.63" +
        "A56 56 0 0 0 213.658 135.831ZM193.782 130.493L182.971 153.034A81 81 0 0 1 168.236 143.911" +
        "L183.595 124.185A56 56 0 0 0 193.782 130.493ZM177.178 118.335L158.954 135.448" +
        "A81 81 0 0 1 148.51 121.618L169.957 108.773A56 56 0 0 0 177.178 118.335ZM166.086 100.999" +
        "L142.911 110.374A81 81 0 0 1 138.168 93.705L162.807 89.475A56 56 0 0 0 166.086 100.999Z" +
        "M162.006 80.828L137.009 81.197A81 81 0 0 1 138.608 63.941L163.112 68.897" +
        "A56 56 0 0 0 162.006 80.828ZM165.488 60.545L142.045 51.859A81 81 0 0 1 149.77 36.346" +
        "L170.829 49.819A56 56 0 0 0 165.488 60.545ZM176.062 42.889L157.34 26.322" +
        "A81 81 0 0 1 170.148 14.646L184.917 34.817A56 56 0 0 0 176.062 42.889ZM192.3 30.245L180.827 8.033" +
        "A81 81 0 0 1 196.987 1.773L203.473 25.917A56 56 0 0 0 192.3 30.245Z"
    )
    val holes = svg(
        "M209 38a9 9 0 1 0 18 0a9 9 0 1 0 -18 0ZM249 80a9 9 0 1 0 18 0a9 9 0 1 0 -18 0ZM209 122" +
        "a9 9 0 1 0 18 0a9 9 0 1 0 -18 0ZM169 80a9 9 0 1 0 18 0a9 9 0 1 0 -18 0Z"
    )
    val nut = svg("M203 71.5l15 -8.7 15 8.7v17l-15 8.7 -15 -8.7z")
    val jaw = svg("M110 148a26 26 0 1 0 0 44l-14 -14v-16z")
    val glint = svg("M122 164h92")
}

/** Getting around: three lines of a transit map, cased and lifted off the card, meeting at stations (concept 10). */
private fun DrawScope.lines(base: Color, s: Float) {
    fun ink(c: Color) = tone(c, base, s)
    val casing = ink(mix(base, Color.Black, 0.5f))
    val yellow = ink(Color(0xFFFFD34D))
    val coral = ink(Color(0xFFFF7563))
    val white = ink(Color.White)
    val blue = ink(mix(Color(0xFF8EC5FF), base, 0.06f))
    val ring = ink(mix(base, Color.Black, 0.38f))
    art(-104f, -88f, 0.4f) {
        translate(3f, 5f) {
            drawPath(Transit.yellow, casing.copy(alpha = 0.3f), style = ROUND_15)
            drawPath(Transit.coral, casing.copy(alpha = 0.3f), style = ROUND_15)
            drawPath(Transit.blue, casing.copy(alpha = 0.3f), style = ROUND_15)
        }
        drawPath(Transit.yellow, yellow, style = ROUND_11)
        drawPath(Transit.coral, coral, style = ROUND_11)
        drawPath(Transit.blue, blue, style = ROUND_11)
        drawPath(Transit.stops, white)
        drawPath(Transit.stops, ring, style = ROUND_4)
    }
}

/** The three lines and their stations, in concept 10's 300 by 300. */
private object Transit {
    val yellow = svg("M150 -10V50q0 30 30 30h140")
    val coral = svg("M130 300V150q0 -30 30 -30h160")
    val blue = svg("M230 -10V300")
    val stops = svg(
        "M221 80a9 9 0 1 0 18 0a9 9 0 1 0 -18 0ZM221 120a9 9 0 1 0 18 0a9 9 0 1 0 -18 0ZM143 40" +
        "a7 7 0 1 0 14 0a7 7 0 1 0 -14 0Z"
    )
}

/** Everything else: a folded map, its contours, river and park, a dashed way across it to the pin. */
private fun DrawScope.contours(base: Color, s: Float) {
    fun ink(c: Color) = tone(c, base, s)
    val shadow = ink(mix(base, Color.Black, 0.6f))
    val paper = ink(mix(Color(0xFFFFF6E6), base, 0.06f))
    val fold = ink(mix(Color(0xFFE8DCC8), base, 0.25f))
    val park = ink(Color(0xFF9BD08A))
    val river = ink(Color(0xFF8CC8EA))
    val contour = ink(mix(Color(0xFFC9A57A), base, 0.1f))
    val route = ink(Color(0xFFF0533A))
    val pin = ink(Color(0xFFFF6A4D))
    val pinLo = ink(Color(0xFFD63A24))
    val white = ink(Color.White)
    soft(shadow.copy(alpha = 0.45f), Offset(-24f, -4f), 40f, 5f, solid = 0.3f)
    drawPath(Chart.panel, paper)
    drawPath(Chart.panel2, fold)
    drawPath(Chart.panel3, paper)
    translate(4f, 0f) {
        drawPath(Chart.park, park.copy(alpha = 0.85f))
        drawPath(Chart.river, river.copy(alpha = 0.95f), style = ROUND_2_4)
        drawPath(Chart.contours, contour.copy(alpha = 0.8f), style = ROUND_0_8)
        drawPath(Chart.route, route, style = ROUND_1_3)
        soft(shadow.copy(alpha = 0.5f), Offset(-24f, -37.5f), 7f, 2.4f, solid = 0.3f)
        drawPath(
            Chart.pin,
            Brush.linearGradient(listOf(pin, pinLo), start = Offset(-35f, -62f), end = Offset(-17f, -40f)),
        )
        drawCircle(white, 3.4f, Offset(-26f, -52.5f))
        drawPath(Chart.gleam, white.copy(alpha = 0.55f), style = ROUND_1_3)
    }
}

/** The map, its folds and marks, and the pin. */
private object Chart {
    val panel = svg("M-54 -66L-34 -72L-34 -12L-54 -6Z")
    val panel2 = svg("M-34 -72L-14 -66L-14 -6L-34 -12Z")
    val panel3 = svg("M-14 -66L6 -72L6 -12L-14 -6Z")
    val park = svg(
        "M-54 -54c4 -4 11 -5 14 -1 2 4 -1 9 -6 10 -5 1 -10 -1 -10 -4 0 -2 1 -4 2 -5zM-14 -30" +
        "c4 -3 10 -2 12 1 1 4 -3 7 -7 7 -4 0 -7 -2 -7 -5 0 -1 1 -2 2 -3z"
    )
    val river = svg("M-58 -24C-50 -26 -44 -30 -38 -34C-30 -31 -24 -32 -18 -38C-10 -40 -4 -46 2 -50")
    val contours = svg(
        "M-12 -27c2 -3.4 6.6 -4 8.6 -1.4 2 2.8 0 6.6 -3.4 7.2 -3.4 0.6 -6 -2 -5.2 -5.8zM-14 -27.6" +
        "c2.8 -5.4 10 -6 13.4 -2 2.6 4 0 10 -5.4 10.6 -5.4 0.6 -9.4 -3.4 -8 -8.6zM-16 -28.4" +
        "c3.4 -7 13.4 -8 17.4 -2.6 3.4 5.4 0 13.4 -7.4 14 -7.4 0.6 -12.4 -4.6 -10 -11.4z"
    )
    val route = svg(
        "M-52 -16L-50.24 -17.32M-48.8 -18.4L-47.04 -19.72M-45.6 -20.8L-44 -22M-44 -22L-43.81 -22.063" +
        "M-42.103 -22.632L-40.016 -23.328M-38.308 -23.897L-38 -24M-38 -24L-36.674 -25.326M-35.401 -26.599" +
        "L-33.845 -28.155M-32.573 -29.427L-31.017 -30.983M-29.799 -32.301L-28.579 -34.131M-27.581 -35.629" +
        "L-26.36 -37.46"
    )
    val pin = svg("M-26 -38C-28.5 -42.5 -35 -46.5 -35 -52.5A9 9 0 1 1 -17 -52.5C-17 -46.5 -23.5 -42.5 -26 -38Z")
    val gleam = svg("M-31.5 -56.5a6.5 6.5 0 0 1 4 -3.6")
}

// ------------------------------------------------------------------------------------------------------------- helpers

/** [a] toward [b] by [t], channel by channel. */
private fun mix(a: Color, b: Color, t: Float): Color = Color(
    (a.red + (b.red - a.red) * t).coerceIn(0f, 1f),
    (a.green + (b.green - a.green) * t).coerceIn(0f, 1f),
    (a.blue + (b.blue - a.blue) * t).coerceIn(0f, 1f),
    (a.alpha + (b.alpha - a.alpha) * t).coerceIn(0f, 1f),
)

/** [c] at a scene's [strength]: below 1 it greys, and shows through as if [base], a shade darker, lay over it. */
private fun tone(c: Color, base: Color, strength: Float): Color {
    if (strength >= 1f) return c
    val s = strength.coerceIn(0f, 1f)
    val grey = 0.3f * c.red + 0.59f * c.green + 0.11f * c.blue
    val kept = 0.1f + 0.9f * s
    val shown = 0.4f + 0.6f * s
    return Color(
        sink(c.red, grey, kept, base.red, shown),
        sink(c.green, grey, kept, base.green, shown),
        sink(c.blue, grey, kept, base.blue, shown),
        c.alpha,
    )
}

/** One channel of [tone]: [v] with [kept] of its distance from [grey], [shown] of the way up from under [base]. */
private fun sink(v: Float, grey: Float, kept: Float, base: Float, shown: Float): Float {
    val under = base * 0.88f
    return (under + (grey + (v - grey) * kept - under) * shown).coerceIn(0f, 1f)
}

/** A soft glow or shadow of [c]: an oval about [at], [rx] by [ry], solid to [solid] of the way out and fading after. */
private fun DrawScope.soft(c: Color, at: Offset, rx: Float, ry: Float = rx, solid: Float = 0.35f) {
    val brush = Brush.radialGradient(0f to c, solid to c, 1f to c.copy(alpha = 0f), center = at, radius = rx)
    if (ry == rx) drawCircle(brush, rx, at) else scale(1f, ry / rx, at) { drawCircle(brush, rx, at) }
}

/** A soft shadow of [c] under a rounded rectangle: three of it, out to [spread] around it, each a third as dark. */
private fun DrawScope.softRect(c: Color, at: Offset, box: Size, r: Float, spread: Float) {
    val faint = c.copy(alpha = c.alpha / 3f)
    for (i in 0..2) {
        val e = spread * (2 - i) / 2f
        drawRoundRect(
            faint,
            Offset(at.x - e, at.y - e),
            Size(box.width + 2 * e, box.height + 2 * e),
            CornerRadius(r + e),
        )
    }
}

/** Draws [block] in a concept's own coordinates: its origin at ([x], [y]) in units, [k] units to one of its own. */
private inline fun DrawScope.art(x: Float, y: Float, k: Float, block: DrawScope.() -> Unit) =
    withTransform({ translate(x, y); scale(k, k, Offset.Zero) }, block)

/** A path from SVG path data. */
private fun svg(d: String): Path = PathParser().parsePathString(d).toPath()

// the strokes the scenes draw with, in their own units: ROUND_ with round ends and joins, LINE_ with plain ones
private val LINE_1 = Stroke(1f)
private val LINE_1_2 = Stroke(1.2f)
private val LINE_1_3 = Stroke(1.3f)
private val LINE_1_6 = Stroke(1.6f)
private val LINE_1_8 = Stroke(1.8f)
private val LINE_2 = Stroke(2f)
private val LINE_2_4 = Stroke(2.4f)
private val ROUND_0_7 = Stroke(0.7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_0_75 = Stroke(0.75f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_0_8 = Stroke(0.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_1 = Stroke(1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_1_2 = Stroke(1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_1_3 = Stroke(1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_1_5 = Stroke(1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_2 = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_2_2 = Stroke(2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_2_4 = Stroke(2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_2_6 = Stroke(2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_3 = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_3_5 = Stroke(3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_4 = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_5 = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_6 = Stroke(6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_6_5 = Stroke(6.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_7 = Stroke(7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_8 = Stroke(8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_11 = Stroke(11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ROUND_15 = Stroke(15f, cap = StrokeCap.Round, join = StrokeJoin.Round)

