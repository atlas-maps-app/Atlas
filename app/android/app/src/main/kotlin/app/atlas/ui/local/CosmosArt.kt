package app.atlas.ui.local

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.atlas.geo.LatLon
import app.atlas.ui.local.astro.Astro
import app.atlas.ui.local.astro.GeoPoint
import app.atlas.ui.local.astro.OrthoView
import app.atlas.ui.local.astro.Planet
import app.atlas.ui.local.astro.Planets
import app.atlas.ui.local.astro.Terminator
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The frame the Globe board is drawn in: 412 x 915, with the pictures centred on (206, 420). */
internal object CosmosFrame {
    const val W = 412f
    const val H = 915f
    const val CX = 206f
    const val CY = 420f
}

/** Draws [block] in the board's 412 x 915 frame, scaled to fit the canvas and centred. */
internal inline fun DrawScope.inBoardFrame(block: DrawScope.() -> Unit) {
    val s = min(size.width / CosmosFrame.W, size.height / CosmosFrame.H)
    translate((size.width - CosmosFrame.W * s) / 2f, (size.height - CosmosFrame.H * s) / 2f) {
        scale(s, s, Offset.Zero) { block() }
    }
}

/** The station and where it is, for the Earth picture. */
class IssFrame(val here: GeoPoint?, val track: List<GeoPoint>, val pass: List<GeoPoint>)

/** One frame of the Earth picture. */
class EarthFrame(
    val view: OrthoView,
    val now: Instant,
    val you: LatLon,
    val iss: IssFrame?,
    /** The glow of "you" pulses between 0 and 1. */
    val pulse: Float,
)

private fun argb(c: Long) = Color(c.toInt())

/**
 * Text in the board's pixels: SVG `text` at (x, y) with `y` the baseline, `anchor` 0 for start, 0.5 for middle. Laid out
 * with [measurer] in the board's face at the SVG's weight (bold 700, else medium 500), and drawn inside [inBoardFrame] so it scales with the picture.
 */
internal class BoardText(private val measurer: TextMeasurer, private val family: FontFamily) {
    fun draw(scope: DrawScope, text: String, x: Float, y: Float, size: Float, colour: Color, bold: Boolean = false, anchor: Float = 0f, weight: Int = if (bold) 700 else 500) {
        val style = TextStyle(color = colour, fontSize = with(scope) { size.toSp() }, fontFamily = family, fontWeight = FontWeight(weight))
        val laid = measurer.measure(text, style, softWrap = false, maxLines = 1)
        scope.drawText(laid, topLeft = Offset(x - laid.size.width * anchor, y - laid.firstBaseline))
    }
}

/** The stars behind everything, the Milky Way's faint band across the sky. */
internal fun DrawScope.drawBackdrop(data: CosmosData) = inBoardFrame {
    val band = Path().apply {
        moveTo(-40f, 260f); cubicTo(120f, 330f, 260f, 520f, 460f, 700f); lineTo(460f, 820f); cubicTo(260f, 640f, 120f, 450f, -40f, 380f); close()
    }
    drawPath(band, Brush.linearGradient(listOf(Color(0x008FA8FF), Color(0x12B9A8FF), Color(0x008FA8FF)), Offset(-40f, 260f), Offset(460f, 820f)))
    for (s in data.backdrop) drawCircle(Color(s.argb), s.r, Offset(s.x, s.y))
}

// ---- Earth ---------------------------------------------------------------------------------------------------------

private const val R = 150f

private fun ringPath(ring: FloatArray, into: Path) {
    into.moveTo(CosmosFrame.CX + R * ring[0], CosmosFrame.CY + R * ring[1])
    var i = 2
    while (i < ring.size) { into.lineTo(CosmosFrame.CX + R * ring[i], CosmosFrame.CY + R * ring[i + 1]); i += 2 }
    into.close()
}

/** The land of [view] as one path, rings cut at the limb. */
internal fun landPath(data: CosmosData, view: OrthoView): Path {
    val path = Path()
    for (ring in data.land) view.ring(ring, ccw = null)?.let { ringPath(it, path) }
    return path
}

/** Earth turning under the sun: the sea, the land, the graticule, the real day and night with twilight, the lights of the cities, you and the station. */
internal fun DrawScope.drawEarth(data: CosmosData, f: EarthFrame) = inBoardFrame {
    val center = Offset(CosmosFrame.CX, CosmosFrame.CY)
    val view = f.view
    // Atmosphere glow.
    drawCircle(Brush.radialGradient(0.86f to Color(0x006FB6FF), 0.93f to Color(0x596FB6FF), 1f to Color(0x006FB6FF), center = center, radius = 172f), 172f, center)
    // The dashed outline just behind the disc.
    drawCircle(Color(0x47CFE0FF), R, center, style = Stroke(1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))))
    // The sea.
    drawCircle(
        Brush.radialGradient(0f to Color(0xFF3A86C8), 0.6f to Color(0xFF1C5A9A), 1f to Color(0xFF0B2A55), center = Offset(center.x - 0.12f * 2 * R, center.y - 0.16f * 2 * R), radius = 0.75f * 2 * R),
        R, center,
    )
    val disc = Path().apply { addOval(Rect(center, R)) }
    clipPath(disc) {
        // Graticule.
        val grid = Path()
        for (line in view.graticule()) {
            grid.moveTo(CosmosFrame.CX + R * line[0], CosmosFrame.CY + R * line[1])
            var i = 2
            while (i < line.size) { grid.lineTo(CosmosFrame.CX + R * line[i], CosmosFrame.CY + R * line[i + 1]); i += 2 }
        }
        drawPath(grid, Color(0x389CC8F0), style = Stroke(0.8f))
        // Land.
        val land = landPath(data, view)
        drawPath(
            land,
            Brush.verticalGradient(
                0f to Color(0xFFC9D8B4), 0.3f to Color(0xFF6E9E58), 0.55f to Color(0xFF8FA35A), 0.75f to Color(0xFF6E9E58), 1f to Color(0xFFE8EEF2),
                startY = center.y - R, endY = center.y + R,
            ),
        )
        drawPath(land, Color(0x592F5A2A), style = Stroke(0.6f))
        // Night: the area where the sun is below the horizon, and its twilight bands, from the real sun.
        val sub = Terminator.subSolar(f.now)
        val antiLat = -sub.latDeg
        val antiLon = Astro.norm180(sub.lonDeg + 180.0)
        for ((alt, alpha) in listOf(0.0 to 0.50f, -6.0 to 0.20f, -12.0 to 0.15f, -18.0 to 0.12f)) {
            view.cap(antiLat, antiLon, 90.0 + alt)?.let { ring ->
                val p = Path()
                ringPath(ring, p)
                drawPath(p, Color(0xFF030712).copy(alpha = alpha), style = Fill)
            }
        }
        // A highlight from the upper left, as light on a glass globe.
        drawRect(
            Brush.radialGradient(0f to Color(0x47FFFFFF), 1f to Color(0x00FFFFFF), center = Offset(center.x - 0.4f * R, center.y - 0.52f * R), radius = 0.55f * 300f),
            Offset(56f, 270f), Size(300f, 300f),
        )
        drawLights(data, f)
    }
    // The rim.
    drawCircle(Color(0x8CCFE0FF), R, center, style = Stroke(1.2f))
    drawIss(f)
    drawYou(f)
}

private fun DrawScope.drawLights(data: CosmosData, f: EarthFrame) {
    val sub = Terminator.subSolar(f.now)
    val sl = sub.latDeg * Astro.DEG
    val v = DoubleArray(3)
    for (c in data.cities) {
        f.view.project(c.lat, c.lon, v)
        if (v[2] < 0.08) continue
        // The sun's altitude at the city, from the angle between the city and the sub-solar point.
        val cl = c.lat * Astro.DEG
        val cosd = sin(cl) * sin(sl) + cos(cl) * cos(sl) * cos((c.lon - sub.lonDeg) * Astro.DEG)
        val alt = 90.0 - Math.toDegrees(kotlin.math.acos(cosd.coerceIn(-1.0, 1.0)))
        val night = ((-alt - 1.0) / 10.0).coerceIn(0.0, 1.0) * min(1.0, v[2] * 3)
        if (night < 0.05) continue
        val r = when (c.size) { 1 -> 2.6f; 2 -> 1.9f; else -> 1.3f }
        val at = Offset(CosmosFrame.CX + R * v[0].toFloat(), CosmosFrame.CY - R * v[1].toFloat())
        val core = if (night > 0.66) Color(0xFFFFE2A0) else if (night > 0.33) Color(0x8CFFD27A) else Color(0x40FFC45C)
        drawCircle(core.copy(alpha = core.alpha * 0.35f), r * 2.2f, at)
        drawCircle(core, r, at)
    }
}

private fun DrawScope.drawIss(f: EarthFrame) {
    val iss = f.iss ?: return
    val v = DoubleArray(3)
    fun at(p: GeoPoint, lift: Double): Pair<Offset, Boolean> {
        f.view.project(p.latDeg, p.lonDeg, v)
        val x = v[0] * lift
        val y = v[1] * lift
        val z = v[2] * lift
        val hidden = z < 0 && x * x + y * y < 1.0
        return Offset(CosmosFrame.CX + R * x.toFloat(), CosmosFrame.CY - R * y.toFloat()) to hidden
    }
    fun polyline(points: List<GeoPoint>, colour: Color, width: Float, dash: Boolean) {
        var path = Path()
        var open = false
        for (p in points) {
            val (o, hidden) = at(p, 1.1)
            if (hidden) { if (open) { drawPath(path, colour, style = Stroke(width, cap = StrokeCap.Round, pathEffect = if (dash) PathEffect.dashPathEffect(floatArrayOf(1.5f, 4f)) else null)); path = Path(); open = false }; continue }
            if (!open) { path.moveTo(o.x, o.y); open = true } else path.lineTo(o.x, o.y)
        }
        if (open) drawPath(path, colour, style = Stroke(width, cap = StrokeCap.Round, pathEffect = if (dash) PathEffect.dashPathEffect(floatArrayOf(1.5f, 4f)) else null))
    }
    polyline(iss.track, Color(0x66CFE0FF), 1.2f, dash = true)
    polyline(iss.pass, Color(0xFFFFD24A), 2.2f, dash = false)
    val here = iss.here ?: return
    val (o, hidden) = at(here, 1.1)
    if (hidden) return
    translate(o.x, o.y) {
        drawRect(Color(0xFF8FB4E8), Offset(-8f, -2.5f), Size(5f, 5f))
        drawRect(Color(0xFF8FB4E8), Offset(3f, -2.5f), Size(5f, 5f))
        drawRect(Color.White, Offset(-2.5f, -1.5f), Size(5f, 3f))
    }
}

/** The pin that says where you are, with a pulsing ring; returns nothing, draws only when you face the viewer. */
private fun DrawScope.drawYou(f: EarthFrame) {
    val v = DoubleArray(3)
    f.view.project(f.you.lat, f.you.lon, v)
    if (v[2] <= 0.05) return
    val o = Offset(CosmosFrame.CX + R * v[0].toFloat(), CosmosFrame.CY - R * v[1].toFloat())
    val ring = 5f + 11f * f.pulse
    drawCircle(Color(0xFFFFD24A).copy(alpha = 1f - f.pulse), ring, o, style = Stroke(2f))
    drawCircle(Color(0xFF1A1A1A), 4.5f + 0.75f, o)
    drawCircle(Color(0xFFFFD24A), 4.5f - 0.75f, o)
}

/** Where on the board's 412 x 915 frame the "you" pin sits, or null when you are on the far side. */
internal fun youOnBoard(view: OrthoView, you: LatLon): Offset? {
    val s = view.screen(you.lat, you.lon) ?: return null
    val v = DoubleArray(3)
    view.project(you.lat, you.lon, v)
    if (v[2] <= 0.05) return null
    return Offset(CosmosFrame.CX + R * s.first.toFloat(), CosmosFrame.CY + R * s.second.toFloat())
}

// ---- Earth and Moon -------------------------------------------------------------------------------------------------

/** The Moon beside the Earth, 384,400 km apart, lit from the Sun on the left, at its real phase. */
internal fun DrawScope.drawMoonScene(data: CosmosData, now: Instant, illumination: Double, phase: Double, label: String, earthLabel: String, sunlight: String, text: BoardText) = inBoardFrame {
    val center = Offset(CosmosFrame.CX, CosmosFrame.CY)
    drawRect(Brush.radialGradient(0f to Color(0x8CFFE9A8), 0.4f to Color(0x1FFFB84D), 1f to Color(0x00FFB84D), center = Offset(0f, 420f), radius = 0.6f * 600f), Offset(0f, 120f), Size(412f, 600f))
    val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 5f))
    drawPath(Path().apply { moveTo(22f, 420f); relativeLineTo(40f, 0f); moveTo(22f, 360f); relativeLineTo(28f, 0f); moveTo(22f, 480f); relativeLineTo(28f, 0f) }, Color(0x80FFD98A), style = Stroke(1.5f, pathEffect = dash))
    text.draw(this, sunlight, 24f, 348f, 11f, Color(0xCCFFD98A), bold = true)
    // The Moon's orbit, seen at a slant.
    drawOval(Color(0x38FFFFFF), Offset(center.x - 160f, center.y - 56f), Size(320f, 112f), style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 6f))))
    drawCircle(Color(0x1F6FB6FF), 54f, center)
    drawCircle(Brush.radialGradient(0f to Color(0xFF3A86C8), 1f to Color(0xFF0B2A55), center = Offset(center.x - 0.12f * 92f, center.y - 0.16f * 92f), radius = 0.75f * 92f), 46f, center)
    val disc = Path().apply { addOval(Rect(center, 46f)) }
    clipPath(disc) {
        // The land, turned to the Moon's side, at the board's small scale.
        val view = OrthoView(centreLonDeg = 5.0, tiltDeg = 20.0)
        val p = Path()
        for (ring in data.land) view.ring(ring, ccw = null)?.let { r ->
            p.moveTo(center.x + 46f * r[0], center.y + 46f * r[1])
            var i = 2
            while (i < r.size) { p.lineTo(center.x + 46f * r[i], center.y + 46f * r[i + 1]); i += 2 }
            p.close()
        }
        drawPath(p, Color(0xFF7FA866))
        drawRect(Brush.horizontalGradient(0.5f to Color(0x00030712), 0.62f to Color(0xD9030712), startX = 160f, endX = 252f), Offset(160f, 374f), Size(92f, 92f))
    }
    // The Moon on its orbit: new Moon between us and the sun (left), full Moon opposite (right).
    val angle = 2.0 * PI * phase
    val mx = center.x - 160f * cos(angle).toFloat()
    val my = center.y - 56f * sin(angle).toFloat()
    translate(mx, my) { drawMoonDisc(13f, illumination, waxing = phase < 0.5) }
    text.draw(this, label, mx, my - 20f, 12f, Color.White, bold = true, anchor = 0.5f)
    text.draw(this, earthLabel, center.x, 496f, 12f, Color(0xFF9CC8F0), bold = true, anchor = 0.5f)
}

/** A Moon disc of radius [r] with the lit part [illumination], the lit limb toward the Sun on the left of the picture. */
internal fun DrawScope.drawMoonDisc(r: Float, illumination: Double, waxing: Boolean) {
    val dark = Color(0xFF3A3A40)
    val lit = Color(0xFFE9E6DD)
    drawCircle(dark, r, Offset.Zero)
    val k = illumination.coerceIn(0.0, 1.0).toFloat()
    // Lit region: the half toward the Sun plus (gibbous) or minus (crescent) an ellipse of half-width r * |1 - 2k|.
    val ew = r * kotlin.math.abs(1f - 2f * k)
    val clip = Path().apply { addOval(Rect(Offset.Zero, r)) }
    clipPath(clip) {
        // Sunward half (left).
        drawPath(Path().apply { moveTo(0f, -r); arcTo(Rect(Offset.Zero, r), -90f, -180f, false); close() }, lit)
        if (k >= 0.5f) {
            drawOval(lit, Offset(-ew, -r), Size(2 * ew, 2 * r))
        } else {
            drawOval(dark, Offset(-ew, -r), Size(2 * ew, 2 * r))
        }
    }
    drawCircle(Color(0x40FFFFFF), r, Offset.Zero, style = Stroke(1f))
    drawCircle(Color(0xB3BDB8AC), 2.2f, Offset(-4f, -3f))
    drawCircle(Color(0x99BDB8AC), 1.6f, Offset(2f, 4f))
}

// ---- The planets ---------------------------------------------------------------------------------------------------

/** The board's squashed orbit radii in px, Mercury to Neptune. */
private val ORBIT_PX = mapOf(
    Planet.MERCURY to 30f, Planet.VENUS to 46f, Planet.EARTH to 62f, Planet.MARS to 80f,
    Planet.JUPITER to 110f, Planet.SATURN to 138f, Planet.URANUS to 163f, Planet.NEPTUNE to 186f,
)

/** The position of a planet on the board's tilted orbit diagram. */
internal fun planetOnBoard(p: Planet, longitudeDeg: Double): Offset {
    val l = longitudeDeg * Astro.DEG
    val r = ORBIT_PX.getValue(p)
    return Offset(CosmosFrame.CX + r * cos(l).toFloat(), CosmosFrame.CY - r * 0.5f * sin(l).toFloat())
}

/** The Sun and the eight planets on [at], true to the angle, distances squashed to fit, as the Planets board. */
internal fun DrawScope.drawSolarSystem(at: Instant, labels: Map<Planet, String>, youLabel: String, text: BoardText) = inBoardFrame {
    val center = Offset(CosmosFrame.CX, CosmosFrame.CY)
    val dash = PathEffect.dashPathEffect(floatArrayOf(2f, 5f))
    for ((p, r) in ORBIT_PX) {
        val earth = p == Planet.EARTH
        drawOval(
            if (earth) Color(0x806FB6FF) else Color(0x29FFFFFF), Offset(center.x - r, center.y - r / 2f), Size(2 * r, r),
            style = Stroke(1f, pathEffect = if (earth) null else dash),
        )
    }
    drawCircle(Brush.radialGradient(0f to Color(0xFFFFF6D6), 0.18f to Color(0xFFFFD36B), 0.4f to Color(0x59FF9E3D), 1f to Color(0x00FF9E3D), center = center, radius = 70f), 70f, center)
    drawCircle(Color(0xFFFFF3C4), 13f, center)
    for (p in Planet.entries) {
        val o = planetOnBoard(p, Planets.longitude(p, at))
        translate(o.x, o.y) { drawPlanetBody(p) }
    }
    for (p in Planet.entries) {
        val o = planetOnBoard(p, Planets.longitude(p, at))
        val word = if (p == Planet.EARTH) youLabel else labels[p] ?: continue
        val lx = o.x.coerceIn(28f, 384f)
        val ly = o.y - when (p) { Planet.JUPITER -> 14f; Planet.SATURN -> 12f; else -> 10f }
        text.draw(this, word, lx, ly, 11f, if (p == Planet.EARTH) Color(0xFFFFE7A3) else Color.White, bold = true, anchor = 0.5f)
    }
}

private fun DrawScope.drawPlanetBody(p: Planet) {
    when (p) {
        Planet.MERCURY -> drawCircle(Color(0xFFA8A39A), 2.6f, Offset.Zero)
        Planet.VENUS -> drawCircle(Color(0xFFEFD08A), 4f, Offset.Zero)
        Planet.EARTH -> {
            drawCircle(Color(0xFFFFD24A), 10f, Offset.Zero, style = Stroke(1.5f))
            drawCircle(Brush.radialGradient(0f to Color(0xFF7FC3FF), 0.6f to Color(0xFF2E7BD6), 1f to Color(0xFF1F8A4C), center = Offset(-1.5f, -1.5f), radius = 6.1f), 4.4f, Offset.Zero)
        }
        Planet.MARS -> drawCircle(Color(0xFFD0572A), 3.4f, Offset.Zero)
        Planet.JUPITER -> drawCircle(
            Brush.verticalGradient(0f to Color(0xFFE9C99A), 0.35f to Color(0xFFC98F5A), 0.5f to Color(0xFFF0D9B5), 0.7f to Color(0xFFB8784A), 1f to Color(0xFFE9C99A), startY = -8.5f, endY = 8.5f),
            8.5f, Offset.Zero,
        )
        Planet.SATURN -> {
            rotate(-18f, Offset.Zero) { drawOval(Color(0xFFD9C08A), Offset(-15f, -4.5f), Size(30f, 9f), style = Stroke(2f)) }
            drawCircle(Color(0xFFE6C987), 7f, Offset.Zero)
        }
        Planet.URANUS -> drawCircle(Color(0xFF8FDDE6), 5f, Offset.Zero)
        Planet.NEPTUNE -> drawCircle(Color(0xFF4F6FE0), 5f, Offset.Zero)
    }
}

/** The colour a planet is drawn in, for chips. */
internal fun planetColour(p: Planet): Color = when (p) {
    Planet.MERCURY -> Color(0xFFA8A39A); Planet.VENUS -> Color(0xFFEFD08A); Planet.EARTH -> Color(0xFF2E7BD6)
    Planet.MARS -> Color(0xFFD0572A); Planet.JUPITER -> Color(0xFFC98F5A); Planet.SATURN -> Color(0xFFE6C987)
    Planet.URANUS -> Color(0xFF8FDDE6); Planet.NEPTUNE -> Color(0xFF4F6FE0)
}

// ---- The galaxy ----------------------------------------------------------------------------------------------------

/** The Milky Way seen from above: disc glow, blurred arms, dust lanes, bulge and stars, with you in the Orion Spur. */
internal fun DrawScope.drawGalaxy(art: GalaxyArt, pulse: Float, youLabel: String, scaleLabel: String, text: BoardText) = inBoardFrame {
    val center = Offset(CosmosFrame.CX, CosmosFrame.CY)
    drawCircle(Brush.radialGradient(0f to Color(0x8CFFE9C0), 0.25f to Color(0x29C9B8FF), 1f to Color(0x008FA8FF), center = center, radius = 196f), 196f, center)
    drawBlurredPaths(art.arms, blurSigma = 9f)
    drawBlurredPaths(art.lanes, blurSigma = 2.2f)
    rotate(-28f, center) {
        drawOval(Brush.radialGradient(0f to Color(0xFFFFF6DE), 0.35f to Color(0xD9FFD98F), 1f to Color(0x00FFB85C), center = center, radius = 52f), Offset(center.x - 52f, center.y - 22f), Size(104f, 44f))
    }
    drawCircle(Brush.radialGradient(0f to Color(0xFFFFF6DE), 0.35f to Color(0xD9FFD98F), 1f to Color(0x00FFB85C), center = center, radius = 26f), 26f, center)
    for (s in art.stars) drawCircle(Color(s.argb), s.r, Offset(s.x, s.y))
    val here = Offset(art.hereX, art.hereY)
    drawCircle(Color(0xFFFFD24A).copy(alpha = 1f - 0.7f * pulse), 9f + 6f * pulse, here, style = Stroke(1.6f))
    drawCircle(Color(0xFF1A1A1A), 3.2f + 0.5f, here)
    drawCircle(Color(0xFFFFD24A), 3.2f - 0.5f, here)
    drawPath(Path().apply { moveTo(here.x + 6f, here.y + 4f); lineTo(here.x + 34f, here.y + 30f); relativeLineTo(26f, 0f) }, Color(0xB3FFE7A3), style = Stroke(1f))
    drawPath(Path().apply { moveTo(40f, 640f); relativeLineTo(52f, 0f); moveTo(40f, 636f); relativeLineTo(0f, 8f); moveTo(92f, 636f); relativeLineTo(0f, 8f) }, Color(0xFF9FAAC2), style = Stroke(2f))
    text.draw(this, youLabel, here.x + 64f, here.y + 34f, 12f, Color(0xFFFFE7A3), bold = true)
    text.draw(this, scaleLabel, 40f, 660f, 11f, Color(0xFF9FAAC2), bold = true)
}

private fun DrawScope.drawBlurredPaths(paths: List<GalaxyPath>, blurSigma: Float) {
    val radius = max(0.5f, (blurSigma - 0.5f) / 0.57735f)
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
        for (p in paths) {
            val path = android.graphics.Path()
            path.moveTo(p.xy[0] + p.dx, p.xy[1] + p.dy)
            var i = 2
            while (i + 1 < p.xy.size) { path.lineTo(p.xy[i] + p.dx, p.xy[i + 1] + p.dy); i += 2 }
            paint.strokeWidth = p.width
            paint.color = Color(p.colour).copy(alpha = p.opacity).toArgb()
            nc.drawPath(path, paint)
        }
    }
}
