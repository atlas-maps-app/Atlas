package app.atlas.mapview

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import app.atlas.geo.Geo
import app.atlas.geo.Quantity
import app.atlas.geo.UnitSystem
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/** A measuring tape between two screen points, as the view shows it while two fingers rest on the map. */
class Tape(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val alpha: Float = 1f)

/**
 * Draws the overlays over a frame, in screen space, each frame: areas and routes projected onto the ground (routes as
 * ribbons with a white casing, alternatives quieter and under, the travelled part faded, walking legs dashed, a label
 * chip each), the markers collided by priority, the location puck (accuracy halo on the ground, heading cone, blue
 * dot) and the measuring tape. Everything is projected with the camera of the frame it is drawn over.
 */
class OverlayPainter(val density: Float) {
    val markers = MarkerPainter(density)
    val layout = MarkerLayout()

    /** The map's labels, drawn under the markers and clear of them. */
    internal val labels = LabelPainter(density)
    private var keepOut = FloatArray(16)
    private val ribbons = RouteRibbons()
    private val triangles = TriangleBuffer()
    private val areas = AreaPaths()
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 13f * density
    }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val fm = Paint.FontMetrics()
    private val pt = FloatArray(2)
    private var xs = FloatArray(64)
    private var ys = FloatArray(64)
    private var visible = BooleanArray(64)
    private var chipRoutes = arrayOfNulls<RouteLine>(4)
    private var chipX = FloatArray(4)
    private var chipY = FloatArray(4)
    private var chipCount = 0
    private var coneShader: SweepGradient? = null
    private var coneDensity = 0f

    /** The dot's blue glow: CSS blur 8 px is a Gaussian of σ 4, Android's blur radius r gives σ = 0.57735 r + 0.5. */
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter((4f * density - 0.5f) / 0.57735f, BlurMaskFilter.Blur.NORMAL) }

    private var blocked = FloatArray(64)
    private var blockedN = 0

    /**
     * What the markers cover this frame, 4 floats a box (their own boxes, and the names beside pins), for keeping the
     * engine's own symbols out from under them; [markerCount] boxes.
     */
    val markerBoxes: FloatArray get() = blocked
    val markerCount: Int get() = blockedN

    /** The markers of the last [layoutMarkers], for hit tests. */
    var lastMarkers: List<Marker> = emptyList()
        private set

    /** Vertices the routes took this frame. */
    var routeVertices = 0
        private set

    /** Projects and places the markers for [g]: call before drawing the frame's symbols, so they can keep clear. */
    fun layoutMarkers(g: GroundView, list: List<Marker>) {
        val n = list.size
        if (xs.size < n) { xs = FloatArray(n); ys = FloatArray(n); visible = BooleanArray(n) }
        val margin = 60f * density
        for (i in 0 until n) {
            val ok = g.project(list[i].at, pt)
            visible[i] = ok && pt[0] >= -margin && pt[0] <= g.width + margin && pt[1] >= -margin && pt[1] <= g.height + margin
            xs[i] = pt[0]; ys[i] = pt[1]
        }
        markers.viewWidth = g.width
        layout.layout(list, xs, ys, visible, markers.metrics, g.width, g.height, markers.chipWidth)
        lastMarkers = list
        val placed = layout.placedCount
        if (blocked.size < 8 * placed) blocked = FloatArray(8 * placed)
        var k = 0
        for (j in 0 until placed) {
            for (q in 0 until 4) blocked[4 * k + q] = layout.boxes[4 * j + q]
            k++
            val m = list[layout.placed[j]]
            if (m.style == MarkerStyle.PIN && m.label != null && layout.clusterCount[j] == 0) {
                markers.labelBox(m, xs[layout.placed[j]], ys[layout.placed[j]], blocked, 4 * k)
                k++
            }
        }
        blockedN = k
    }

    /** Areas and routes: what lies on the ground, under the symbols. */
    fun drawGround(canvas: Canvas, g: GroundView, overlays: MapOverlays) {
        for (a in overlays.areas) area(canvas, g, a)
        routeVertices = 0
        if (overlays.routes.isEmpty()) return
        ribbons.beginFrame()
        val mpdp = g.camera.metresPerPixel * density
        triangles.begin(canvas)
        if (chipRoutes.size < overlays.routes.size) chipRoutes = arrayOfNulls(overlays.routes.size)
        // Alternatives first and quieter, then the main routes; each casing under its fill.
        for (pass in 0..1) {
            val alt = pass == 0
            for (r in overlays.routes) {
                if (r.alternative != alt) continue
                val fillColour = fillOf(r)
                val faded = fadedOf(fillColour)
                val width = widthOf(r)
                val hw = width / 2.0 * mpdp
                val casing = (r.casing.toInt() or (0xFF shl 24))
                val casingM = CASING_DP * mpdp
                val period = (if (r.dashed) width * 2.6 else 0.0) * mpdp
                val dash = width * 1.3 * mpdp
                val minHalf = 1.25 * density
                // The casing's wider round caps already reach past each dash at both ends.
                ribbons.draw(triangles, g, r, hw + casingM, minHalf + CASING_DP * density * 0.6, casing, casing, period, dash, false)
                ribbons.draw(triangles, g, r, hw, minHalf, fillColour, faded, period, dash, r.label != null)
                if (r.label != null && ribbons.chipPoint(pt, 24f * density)) {
                    if (chipCount == chipX.size) { chipX = chipX.copyOf(chipCount * 2); chipY = chipY.copyOf(chipCount * 2) }
                    chipRoutes[chipCount] = r
                    chipX[chipCount] = pt[0]; chipY[chipCount] = pt[1]
                    chipCount++
                }
            }
        }
        triangles.end()
        routeVertices = triangles.drawn
        // Chips over every ribbon.
        for (k in 0 until chipCount) {
            val r = chipRoutes[k] ?: continue
            routeChip(canvas, r.label!!, chipX[k], chipY[k], fillOf(r), r.alternative)
        }
        java.util.Arrays.fill(chipRoutes, 0, chipCount, null)
        chipCount = 0
    }

    /** The markers placed by [layoutMarkers]. */
    /**
     * Draws [overlays]' labels, clear of the chrome ([MapOverlays.labelKeepOut], dp) and of the markers this frame laid
     * out.
     */
    fun drawLabels(canvas: Canvas, g: GroundView, overlays: MapOverlays) {
        val k = overlays.labelKeepOut
        if (keepOut.size < 4 * k.size) keepOut = FloatArray(4 * k.size)
        for (i in k.indices) {
            val r = k[i]
            keepOut[4 * i] = r.left * density; keepOut[4 * i + 1] = r.top * density
            keepOut[4 * i + 2] = r.right * density; keepOut[4 * i + 3] = r.bottom * density
        }
        labels.draw(canvas, g, overlays.labels, keepOut, k.size, blocked, blockedN)
    }

    fun drawMarkers(canvas: Canvas) {
        val list = lastMarkers
        for (j in 0 until layout.placedCount) {
            val i = layout.placed[j]
            markers.draw(canvas, list[i], xs[i], ys[i], layout.clusterCount[j])
        }
    }

    /** The marker under ([x], [y]) from the last layout, or null. */
    fun markerAt(x: Float, y: Float): Marker? {
        val i = layout.hit(x, y, markers.metrics)
        return if (i >= 0) lastMarkers.getOrNull(i) else null
    }

    private fun area(canvas: Canvas, g: GroundView, a: AreaOverlay) {
        if (areas.circle(g, a.centre, a.radiusM, path) <= 0f) return
        fill.color = a.fill.toInt()
        canvas.drawPath(path, fill)
        stroke.color = a.stroke.toInt()
        stroke.strokeWidth = 1.5f * density
        canvas.drawPath(path, stroke)
    }

    /** The width of a route chip's text [label], px. */
    fun labelWidth(label: String): Float = text.measureText(label)

    /**
     * A route's chip ([label], "6′") centred on ([x], [y]): a 24 px pill of the route's [colour] (white for an
     * alternative, [quiet]) under a soft shadow, its text 13 px medium. The core draws it from a sprite of this.
     */
    fun routeChip(canvas: Canvas, label: String, x: Float, y: Float, colour: Int, quiet: Boolean) {
        val tw = text.measureText(label)
        val h = 24f * density
        val w = tw + 20f * density
        rect.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
        fill.color = if (quiet) Color.WHITE else colour
        fill.setShadowLayer(3f * density, 0f, 1f * density, 0x38000000)
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        fill.clearShadowLayer()
        text.color = if (quiet) MarkerPainter.INK else MarkerPainter.onColour(colour)
        text.textAlign = Paint.Align.CENTER
        text.getFontMetrics(fm)
        canvas.drawText(label, x, y - (fm.ascent + fm.descent) / 2, text)
        text.textAlign = Paint.Align.LEFT
    }

    /**
     * You, as the Main board draws you (`aria-label="You, …"`, a 64 px box on the point): the heading cone, a 36 × 40 dome
     * shaded by a conic gradient of #2E5BFF from 40 % to nothing; a 40 px halo of #2E5BFF at 16 %; a 20 px dot, #2E5BFF in
     * a 3 px white ring, with a blue glow (`box-shadow: 0 2px 8px rgba(46,91,255,0.55)`). The accuracy shows on the ground
     * only where it reaches past the halo.
     */
    fun drawLocation(canvas: Canvas, g: GroundView, loc: UserLocation) {
        if (!g.project(loc.at, pt)) return
        val x = pt[0]
        val y = pt[1]
        val d = density
        val accuracy = areas.circle(g, loc.at, loc.accuracyM, path)
        if (accuracy > 20f * d) {
            fill.color = Colours.withAlpha(PUCK, 0.13f)
            canvas.drawPath(path, fill)
            stroke.color = Colours.withAlpha(PUCK, 0.35f)
            stroke.strokeWidth = 1f * d
            canvas.drawPath(path, stroke)
        }
        if (!g.onScreen(x - 40 * d, y - 40 * d, x + 40 * d, y + 40 * d)) return
        loc.heading?.let { h ->
            // The heading's direction on screen: project a point a few metres ahead.
            val ahead = Geo.destination(loc.at, h, 15.0)
            if (g.project(ahead, pt)) {
                val a = Math.toDegrees(atan2((pt[1] - y).toDouble(), (pt[0] - x).toDouble())).toFloat()
                cone(canvas, x, y, a + 90f)
            }
        }
        fill.color = Colours.withAlpha(PUCK, 0.16f)
        canvas.drawCircle(x, y, 20f * d, fill)
        glow.color = Colours.withAlpha(PUCK, 0.55f)
        canvas.drawCircle(x, y + 2f * d, 10f * d, glow)
        fill.color = Color.WHITE
        canvas.drawCircle(x, y, 10f * d, fill)
        fill.color = PUCK
        canvas.drawCircle(x, y, 7f * d, fill)
    }

    /**
     * The board's heading cone, turned [up] degrees clockwise from straight up: a 36 × 40 box whose top corners are round
     * (`border-radius: 40px 40px 0 0`, which CSS scales to 18), its bottom 2 px under the dot's centre and the turn about
     * that bottom middle; filled with `conic-gradient(from -25deg at 50% 100%, 40 % at 0°, 0 at 50°, 0 at 310°, 40 % at 360°)`.
     */
    private fun cone(canvas: Canvas, x: Float, y: Float, up: Float) {
        val d = density
        canvas.save()
        canvas.translate(x, y + 2f * d)
        canvas.rotate(up)
        if (coneShader == null || coneDensity != d) {
            // CSS measures from straight up, clockwise; Android's sweep from 3 o'clock: from -25deg is -115°.
            coneShader = SweepGradient(
                0f, 0f,
                intArrayOf(Colours.withAlpha(PUCK, 0.4f), Colours.withAlpha(PUCK, 0f), Colours.withAlpha(PUCK, 0f), Colours.withAlpha(PUCK, 0.4f)),
                floatArrayOf(0f, 50f / 360f, 310f / 360f, 1f),
            ).apply { setLocalMatrix(Matrix().apply { setRotate(-115f) }) }
            coneDensity = d
        }
        path.rewind()
        rect.set(-18f * d, -40f * d, 18f * d, 0f)
        path.addRoundRect(rect, floatArrayOf(18f * d, 18f * d, 18f * d, 18f * d, 0f, 0f, 0f, 0f), Path.Direction.CW)
        // The shader carries the colour; the paint's own alpha would dim it.
        fill.color = Color.WHITE
        fill.shader = coneShader
        canvas.drawPath(path, fill)
        fill.shader = null
        canvas.restore()
    }

    /**
     * The measuring tape between the two points of [tape] on the ground seen by [g]: a yellow tape with ticks at round
     * distances (placed in perspective), a handle at each finger, and a chip with the distance and the walking time.
     */
    fun drawTape(canvas: Canvas, g: GroundView, tape: Tape, units: UnitSystem, locale: Locale) {
        val a = g.camera.unproject(tape.x0.toDouble(), tape.y0.toDouble()) ?: return
        val b = g.camera.unproject(tape.x1.toDouble(), tape.y1.toDouble()) ?: return
        val metres = Geo.distance(a, b)
        val alpha = tape.alpha.coerceIn(0f, 1f)
        val ax = tape.x0; val ay = tape.y0; val bx = tape.x1; val by = tape.y1
        val len = hypot(bx - ax, by - ay)
        if (len < 1f) return
        val ux = (bx - ax) / len; val uy = (by - ay) / len
        val nx = -uy; val ny = ux
        val half = 8f * density
        // The tape.
        path.rewind()
        path.moveTo(ax + nx * half, ay + ny * half)
        path.lineTo(bx + nx * half, by + ny * half)
        path.lineTo(bx - nx * half, by - ny * half)
        path.lineTo(ax - nx * half, ay - ny * half)
        path.close()
        fill.color = Colours.withAlpha(TAPE, 0.94f * alpha)
        fill.setShadowLayer(3f * density, 0f, 1f * density, Colours.withAlpha(Color.BLACK, 0.25f * alpha))
        canvas.drawPath(path, fill)
        fill.clearShadowLayer()
        stroke.color = Colours.withAlpha(TAPE_EDGE, alpha)
        stroke.strokeWidth = 1f * density
        canvas.drawPath(path, stroke)
        // Ticks at round distances, each where its ground point lands on screen.
        val step = tickStep(metres, len / (7f * density))
        if (step > 0.0 && metres > 0.0) {
            stroke.color = Colours.withAlpha(MarkerPainter.INK, 0.85f * alpha)
            val count = (metres / step).toInt()
            for (k in 1..count) {
                val f = k * step / metres
                if (f >= 1.0) break
                val q = Geo.interpolate(a, b, f)
                if (!g.project(q, pt)) continue
                val long = k % 5 == 0
                stroke.strokeWidth = (if (long) 1.4f else 1f) * density
                val t = (if (long) 0.8f else 0.42f) * half
                canvas.drawLine(pt[0] + nx * half, pt[1] + ny * half, pt[0] + nx * (half - 2 * t), pt[1] + ny * (half - 2 * t), stroke)
            }
        }
        // Handles under the fingers.
        for (end in 0..1) {
            val hx = if (end == 0) ax else bx
            val hy = if (end == 0) ay else by
            fill.color = Colours.withAlpha(Color.WHITE, alpha)
            canvas.drawCircle(hx, hy, 11f * density, fill)
            fill.color = Colours.withAlpha(MarkerPainter.INK, alpha)
            canvas.drawCircle(hx, hy, 5f * density, fill)
        }
        // The chip: distance, then the walk.
        val distance = Quantity.distance(metres, units).format(locale)
        val walk = walkLabel(metres)
        val tw = text.measureText(distance)
        val ww = text.measureText(walk)
        val iconSize = 15f * density
        val h = 30f * density
        val w = 14f * density + tw + 12f * density + iconSize + 3f * density + ww + 14f * density
        // Beside the tape's middle, on its upper side, clear of the tape whatever its angle.
        val side = if (ny < 0) 1f else -1f
        val reach = abs(nx) * w / 2 + abs(ny) * h / 2 + half + 10f * density
        var cx = (ax + bx) / 2 + nx * side * reach
        var cy = (ay + by) / 2 + ny * side * reach
        cx = cx.coerceIn(w / 2 + 8f * density, g.width - w / 2 - 8f * density)
        cy = cy.coerceIn(h / 2 + 8f * density, g.height - h / 2 - 8f * density)
        rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        fill.color = Colours.withAlpha(MarkerPainter.INK, 0.94f * alpha)
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        text.color = Colours.withAlpha(Color.WHITE, alpha)
        text.getFontMetrics(fm)
        val base = cy - (fm.ascent + fm.descent) / 2
        var x = rect.left + 14f * density
        canvas.drawText(distance, x, base, text)
        x += tw + 12f * density
        Icons.loadedShape(WALK)?.let {
            icon.color = Colours.withAlpha(Color.WHITE, alpha)
            it.draw(canvas, x + iconSize / 2, cy, iconSize, icon)
        }
        x += iconSize + 3f * density
        canvas.drawText(walk, x, base, text)
    }

    companion object {
        const val CASING_DP = 1.6

        /** A route's fill as the painter draws it: its colour, an alternative's quieter. */
        fun fillOf(r: RouteLine): Int {
            val colour = r.colour.toInt() or (0xFF shl 24)
            return if (r.alternative) Colours.mix(colour, QUIET, 0.72f) else colour
        }

        /** The fill of a route's part already travelled. */
        fun fadedOf(fill: Int): Int = Colours.mix(fill, TRAVELLED, 0.7f)

        /** A route's width as the painter draws it, dp: an alternative's narrower. */
        fun widthOf(r: RouteLine): Float = if (r.alternative) r.widthDp * 0.82f else r.widthDp

        /** The walking symbol of the tape's chip. */
        const val WALK = "ui-walk"
        val PUCK = 0xFF2E5BFF.toInt()
        val QUIET = 0xFFA9B1C2.toInt()
        val TRAVELLED = 0xFFC3C8D1.toInt()
        val TAPE = 0xFFF5C518.toInt()
        val TAPE_EDGE = 0xFFC79A0E.toInt()

        /** Walking speed for the tape's time, metres per second (4.8 km/h). */
        const val WALKING = 1.33

        /** "4′", "1 h 05′": minutes on foot for [metres], at least one. */
        fun walkLabel(metres: Double): String {
            val minutes = max(1, ceil(metres / WALKING / 60.0 - 0.25).toInt())
            return if (minutes < 60) "$minutes′" else "${minutes / 60} h ${"%02d".format(Locale.ROOT, minutes % 60)}′"
        }

        /**
         * The tick spacing in metres for a tape of [metres] that has room for at most [maxTicks] ticks: 1, 2 or 5 times
         * a power of ten.
         */
        fun tickStep(metres: Double, maxTicks: Float): Double {
            if (metres <= 0.0 || maxTicks < 1f) return 0.0
            var power = 0.01
            while (true) {
                for (m in TICK_MANTISSAS) {
                    val step = m * power
                    if (metres / step <= maxTicks) return step
                }
                power *= 10
            }
        }

        private val TICK_MANTISSAS = doubleArrayOf(1.0, 2.0, 5.0)
    }
}
