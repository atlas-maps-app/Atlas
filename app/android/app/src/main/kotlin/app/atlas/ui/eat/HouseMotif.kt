package app.atlas.ui.eat

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.floor
import kotlin.math.max

/** The Eat card's view of a motif: the HouseCards board's `viewBox="0 20 352 150"`. */
internal val CARD_VIEW = Rect(0f, 20f, 352f, 170f)

/**
 * Draws [house]'s motif as the Cuisines board does, in its 352 × 190 units: [view] of it scaled to cover this canvas
 * and centred (the boards' `xMidYMid slice`). Paths are the board's own; its patterns repeat from the motif's corner,
 * each tile clipped to itself, as SVG's do.
 */
internal fun DrawScope.houseMotif(house: CuisineHouse, view: Rect = CARD_VIEW) {
    val s = max(size.width / view.width, size.height / view.height)
    clipRect {
        withTransform({
            translate((size.width - view.width * s) / 2f - view.left * s, (size.height - view.height * s) / 2f - view.top * s)
            scale(s, s, Offset.Zero)
        }) {
            when (house) {
                CuisineHouse.ITALIANA -> italiana()
                CuisineHouse.JAPANESE -> japanese()
                CuisineHouse.MEXICANA -> mexicana()
                CuisineHouse.INDIAN -> indian()
                CuisineHouse.CHINESE -> chinese()
                CuisineHouse.KOREAN -> korean()
                CuisineHouse.THAI -> thai()
                CuisineHouse.VIET -> viet()
                CuisineHouse.LEVANTINE -> levantine()
                CuisineHouse.ETHIOPIAN -> ethiopian()
                CuisineHouse.BISTROT -> bistrot()
                CuisineHouse.DINER -> diner()
            }
        }
    }
}

private const val W = 352f
private const val H = 190f

/** The board's path data, parsed once. */
private object MotifPaths {
    private fun p(d: String): Path = PathParser().parsePathString(d).toPath()

    val branch by lazy { p("M30 160C110 130 210 90 330 34") }
    val bunting by lazy { p("M0 24C60 34 120 38 176 34s116-10 176-4") }
    val flag by lazy { p("M0 0h96v70l-8 10-8-10-8 10-8-10-8 10-8-10-8 10-8-10-8 10-8-10-8 10-8-10z") }
    val pinkCuts by lazy { p("M44 76l6-6 6 6-6 6zM68 76l6-6 6 6-6 6z") }
    val tealCuts by lazy { p("M150 56l8-8 8 8-8 8zM186 56l8-8 8 8-8 8z") }
    val yellowCuts by lazy { p("M272 78l6-6 6 6-6 6zM296 78l6-6 6 6-6 6z") }
    val lattice by lazy { p("M0 0h48v48H0z M24 6l18 18-18 18L6 24z M0 24h6M42 24h6M24 0v6M24 42v6") }
    val diamond by lazy { p("M0 0l20-26 20 26-20 26z") }
    val lotus by lazy {
        listOf(
            "M176 40c14 18 14 44 0 66-14-22-14-48 0-66z", "M176 106c-10-26-32-42-56-40 4 26 26 42 56 40z",
            "M176 106c10-26 32-42 56-40-4 26-26 42-56 40z", "M176 106c-22-8-48-6-66 10 20 12 46 10 66-10z",
            "M176 106c22-8 48-6 66 10-20 12-46 10-66-10z", "M110 138h132",
        ).map(::p)
    }
    val lanternString by lazy { p("M0 18C80 30 270 30 352 18") }
    val lanternCords by lazy { p("M70 24v18M176 30v22M282 24v18") }
    val ribsLeft by lazy { p("M50 60c6 10 6 22 0 32M90 60c-6 10-6 22 0 32M70 46v60") }
    val ribsMiddle by lazy { p("M154 72c6 12 6 24 0 36M198 72c-6 12-6 24 0 36M176 56v68") }
    val ribsRight by lazy { p("M262 60c6 10 6 22 0 32M302 60c-6 10-6 22 0 32M282 46v60") }
    val star by lazy { p("M28 10l5 13 13 5-13 5-5 13-5-13-13-5 13-5z") }
    val tileCorners by lazy { p("M0 0l10 10M56 0L46 10M0 56l10-10M56 56L46 46") }
    val archOuter by lazy { p("M60 190V90c0-40 50-70 116-70s116 30 116 70v100") }
    val archInner by lazy { p("M78 190V96c0-32 44-56 98-56s98 24 98 56v94") }
    val vine by lazy { p("M120 150c20-40 60-10 40-50s50-40 60-10-40 30-10 60") }
}

private fun DrawScope.box(colour: Long, x: Float = 0f, y: Float = 0f, w: Float = W, h: Float = H) =
    drawRect(Color(colour), Offset(x, y), Size(w, h))

private fun DrawScope.disc(colour: Long, cx: Float, cy: Float, r: Float) = drawCircle(Color(colour), r, Offset(cx, cy))

private fun DrawScope.ring(colour: Long, cx: Float, cy: Float, r: Float, width: Float, dash: FloatArray? = null) =
    drawCircle(Color(colour), r, Offset(cx, cy), style = Stroke(width, pathEffect = dash?.let { PathEffect.dashPathEffect(it) }))

private fun DrawScope.fill(path: Path, colour: Long) = drawPath(path, Color(colour))

private fun DrawScope.line(
    path: Path, colour: Long, width: Float, dash: FloatArray? = null, cap: StrokeCap = StrokeCap.Butt, join: StrokeJoin = StrokeJoin.Miter,
) = drawPath(path, Color(colour), style = Stroke(width, cap = cap, join = join, pathEffect = dash?.let { PathEffect.dashPathEffect(it) }))

/** A straight rule across the motif at [y]. */
private fun DrawScope.rule(y: Float, colour: Long, width: Float, dash: FloatArray) =
    drawLine(Color(colour), Offset(0f, y), Offset(W, y), width, pathEffect = PathEffect.dashPathEffect(dash))

/** An SVG pattern: [tile] ([w] × [h]) repeated from the motif's corner over [area], each tile clipped to itself. */
private inline fun DrawScope.pattern(w: Float, h: Float, area: Rect = Rect(0f, 0f, W, H), tile: DrawScope.() -> Unit) {
    clipRect(area.left, area.top, area.right, area.bottom) {
        var y = floor(area.top / h) * h
        while (y < area.bottom) {
            var x = floor(area.left / w) * w
            while (x < area.right) {
                translate(x, y) { clipRect(0f, 0f, w, h) { tile() } }
                x += w
            }
            y += h
        }
    }
}

/** An olive branch on terracotta: its leaves and three olives. */
private fun DrawScope.italiana() {
    box(0xFFB5532E)
    line(MotifPaths.branch, 0xFFF4E3C8, 2.5f)
    // cx, cy, rx, ry, turn
    val leaves = floatArrayOf(
        80f, 130f, 18f, 6f, -50f, 96f, 150f, 18f, 6f, 10f, 140f, 112f, 18f, 6f, -55f, 158f, 130f, 18f, 6f, 5f,
        200f, 88f, 18f, 6f, -60f, 220f, 104f, 18f, 6f, 0f, 262f, 62f, 16f, 5.5f, -65f, 282f, 76f, 16f, 5.5f, -5f,
    )
    for (i in leaves.indices step 5) {
        val cx = leaves[i]
        val cy = leaves[i + 1]
        val rx = leaves[i + 2]
        val ry = leaves[i + 3]
        rotate(leaves[i + 4], Offset(cx, cy)) { drawOval(Color(0xFFF4E3C8), Offset(cx - rx, cy - ry), Size(2 * rx, 2 * ry)) }
    }
    disc(0xFF5E6B26, 118f, 132f, 7f)
    disc(0xFF5E6B26, 182f, 108f, 7f)
    disc(0xFF5E6B26, 244f, 84f, 6f)
}

/** Seigaiha waves in indigo under a red sun. */
private fun DrawScope.japanese() {
    pattern(40f, 20f) {
        box(0xFF1E2F5A, w = 40f, h = 20f)
        for ((cx, cy) in listOf(0f to 20f, 40f to 20f, 20f to 10f)) {
            for (r in floatArrayOf(19f, 13f, 7f)) {
                disc(0xFF1E2F5A, cx, cy, r)
                ring(0xFF6F86BE, cx, cy, r, 1.4f)
            }
        }
    }
    disc(0xFFC8322A, 286f, 60f, 30f)
}

/** Papel picado: three cut-paper flags on a string, and a dashed hem. */
private fun DrawScope.mexicana() {
    box(0xFFFFF1D6)
    line(MotifPaths.bunting, 0xFF6B4A2A, 1.5f)
    translate(14f, 30f) { fill(MotifPaths.flag, 0xFFE4337A) }
    for (x in floatArrayOf(38f, 62f, 86f)) disc(0xFFFFF1D6, x, 54f, 7f)
    fill(MotifPaths.pinkCuts, 0xFFFFF1D6)
    translate(128f, 36f) { fill(MotifPaths.flag, 0xFF12A9A6) }
    fill(MotifPaths.tealCuts, 0xFFFFF1D6)
    disc(0xFFFFF1D6, 176f, 82f, 8f)
    translate(242f, 32f) { fill(MotifPaths.flag, 0xFFF2A71B) }
    for (x in floatArrayOf(266f, 290f, 314f)) disc(0xFFFFF1D6, x, 56f, 7f)
    fill(MotifPaths.yellowCuts, 0xFFFFF1D6)
    rule(170f, 0xFFE4337A, 6f, floatArrayOf(14f, 8f))
}

/** A block print of four-dot flowers on saffron, over a red border. */
private fun DrawScope.indian() {
    pattern(44f, 44f) {
        box(0xFFD98E1F, w = 44f, h = 44f)
        for ((x, y) in listOf(22f to 16f, 16f to 22f, 28f to 22f, 22f to 28f)) disc(0xFF2B2F7A, x, y, 3.5f)
        disc(0xFFFDF6E8, 22f, 22f, 2.6f)
        for ((x, y) in listOf(0f to 0f, 44f to 44f, 44f to 0f, 0f to 44f)) disc(0xFF8E2B24, x, y, 2f)
    }
    box(0xFF8E2B24, y = 168f, h = 22f)
    rule(168f, 0xFFFDF6E8, 2f, floatArrayOf(6f, 4f))
}

/** A gold window lattice on red around a round medallion. */
private fun DrawScope.chinese() {
    pattern(48f, 48f) {
        box(0xFF9E2B25, w = 48f, h = 48f)
        line(MotifPaths.lattice, 0xFFE2B65C, 2f)
        drawRect(Color(0xFFE2B65C), Offset(18f, 18f), Size(12f, 12f), style = Stroke(1.5f))
    }
    disc(0xFF9E2B25, 176f, 95f, 54f)
    ring(0xFFE2B65C, 176f, 95f, 54f, 3f)
    ring(0xFFE2B65C, 176f, 95f, 44f, 1.2f)
}

/** Dancheong bands: blue and red stripes around a row of gold diamonds on green. */
private fun DrawScope.korean() {
    box(0xFF2E7D5B)
    box(0xFF1E4E9E, h = 26f)
    box(0xFFC8322A, y = 26f, h = 8f)
    box(0xFFC8322A, y = 140f, h = 10f)
    box(0xFF1E4E9E, y = 150f, h = 40f)
    for (x in floatArrayOf(44f, 132f, 220f, 308f)) translate(x, 87f) {
        fill(MotifPaths.diamond, 0xFFF2B705)
        line(MotifPaths.diamond, 0xFFC8322A, 2f)
    }
    for (x in floatArrayOf(64f, 152f, 240f, 328f)) disc(0xFFF4F6F1, x, 87f, 6f)
    rule(44f, 0xFFF4F6F1, 2f, floatArrayOf(10f, 6f))
    rule(130f, 0xFFF4F6F1, 2f, floatArrayOf(10f, 6f))
}

/** A lotus drawn in gold on deep green. */
private fun DrawScope.thai() {
    box(0xFF10614A)
    for (petal in MotifPaths.lotus) line(petal, 0xFFE2B65C, 2.2f, join = StrokeJoin.Round)
    rule(164f, 0xFFE2B65C, 1f, floatArrayOf(3f, 5f))
}

/** Three silk lanterns, red, yellow and red, hung on a string. */
private fun DrawScope.viet() {
    box(0xFF3A8C75)
    line(MotifPaths.lanternString, 0xFF1F4F42, 1.5f)
    line(MotifPaths.lanternCords, 0xFF1F4F42, 1.5f)
    fun DrawScope.cap(x: Float, y: Float) = drawRoundRect(Color(0xFF6B3A10), Offset(x, y), Size(24f, 6f), CornerRadius(2f))
    cap(58f, 42f)
    disc(0xFFE24B3A, 70f, 76f, 30f)
    line(MotifPaths.ribsLeft, 0xFFB83426, 1.5f)
    cap(58f, 104f)
    cap(164f, 52f)
    disc(0xFFF2B705, 176f, 90f, 34f)
    line(MotifPaths.ribsMiddle, 0xFFC78F00, 1.5f)
    cap(164f, 122f)
    cap(270f, 42f)
    disc(0xFFE24B3A, 282f, 76f, 30f)
    line(MotifPaths.ribsRight, 0xFFB83426, 1.5f)
    cap(270f, 104f)
}

/** Zellige: eight-pointed stars on blue tiles. */
private fun DrawScope.levantine() {
    pattern(56f, 56f) {
        box(0xFF1B4F8A, w = 56f, h = 56f)
        fill(MotifPaths.star, 0xFFF1F5F8)
        rotate(45f, Offset(28f, 28f)) { drawRect(Color(0xFF2BA6A0), Offset(19f, 19f), Size(18f, 18f)) }
        disc(0xFFF1F5F8, 28f, 28f, 4f)
        line(MotifPaths.tileCorners, 0xFF2BA6A0, 3f)
    }
}

/** A mesob basket's woven rings, seen from above. */
private fun DrawScope.ethiopian() {
    box(0xFFC9A26B)
    val rings = listOf(80f to 0xFF8E2B24, 68f to 0xFFF2B705, 56f to 0xFF2E7D5B, 44f to 0xFFF4EBDD, 32f to 0xFF8E2B24, 20f to 0xFFF2B705)
    for ((r, colour) in rings) ring(colour, 176f, 95f, r, 9f)
    disc(0xFF2E7D5B, 176f, 95f, 10f)
    ring(0xFFF4EBDD, 176f, 95f, 62f, 2f, floatArrayOf(4f, 5f))
}

/** A Métro arch in brass on bottle green, a vine of light through it. */
private fun DrawScope.bistrot() {
    box(0xFF1F4A3A)
    line(MotifPaths.archOuter, 0xFFC9A45C, 3f)
    line(MotifPaths.archInner, 0xFFC9A45C, 1.2f)
    line(MotifPaths.vine, 0xFFE9C977, 2.2f, cap = StrokeCap.Round)
    disc(0xFFE9C977, 210f, 140f, 5f)
    disc(0xFFE9C977, 118f, 152f, 4f)
}

/** A diner's red wall, chrome trim, checkered floor and a mint clock. */
private fun DrawScope.diner() {
    fun chrome(top: Float, bottom: Float) =
        Brush.verticalGradient(0f to Color.White, 0.5f to Color(0xFF9AA3AE), 1f to Color(0xFFE6EAEE), startY = top, endY = bottom)
    box(0xFFC8322A, h = 120f)
    pattern(28f, 28f, Rect(0f, 120f, W, H)) {
        box(0xFFF7F4F2, w = 28f, h = 28f)
        box(0xFF14161B, w = 14f, h = 14f)
        box(0xFF14161B, 14f, 14f, 14f, 14f)
    }
    for ((y, h) in listOf(110f to 14f, 40f to 6f, 54f to 3f)) drawRect(chrome(y, y + h), Offset(0f, y), Size(W, h))
    disc(0xFF9FE3CF, 290f, 70f, 28f)
    drawCircle(chrome(42f, 98f), 28f, Offset(290f, 70f), style = Stroke(5f))
}
