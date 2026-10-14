package app.atlas.places

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Receives path commands; Android's `Path` and Java2D's `Path2D` both fit behind it with one line per method. */
interface PathSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun quadTo(x1: Float, y1: Float, x: Float, y: Float)
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float)
    fun close()
}

/**
 * A parsed path in absolute coordinates: one verb per command and its points packed in [coords]
 * (move and line 2 floats, quad 4, cubic 6, close none). Arcs are already cubics.
 */
class PathCommands(val verbs: ByteArray, val coords: FloatArray) {
    val size: Int get() = verbs.size

    /** Sends every command to [sink], scaled by [scale] and then moved by ([dx], [dy]). */
    fun replay(sink: PathSink, scale: Float = 1f, dx: Float = 0f, dy: Float = 0f) {
        var i = 0
        val c = coords
        for (v in verbs) {
            when (v) {
                MOVE -> { sink.moveTo(c[i] * scale + dx, c[i + 1] * scale + dy); i += 2 }
                LINE -> { sink.lineTo(c[i] * scale + dx, c[i + 1] * scale + dy); i += 2 }
                QUAD -> { sink.quadTo(c[i] * scale + dx, c[i + 1] * scale + dy, c[i + 2] * scale + dx, c[i + 3] * scale + dy); i += 4 }
                CUBIC -> {
                    sink.cubicTo(
                        c[i] * scale + dx, c[i + 1] * scale + dy, c[i + 2] * scale + dx, c[i + 3] * scale + dy,
                        c[i + 4] * scale + dx, c[i + 5] * scale + dy,
                    ); i += 6
                }
                else -> sink.close()
            }
        }
    }

    /** The box of every point, control points included: (minX, minY, maxX, maxY), or null when empty. */
    fun bounds(): FloatArray? {
        if (coords.isEmpty()) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var i = 0
        while (i < coords.size) {
            val x = coords[i]; val y = coords[i + 1]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            i += 2
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    companion object {
        const val MOVE: Byte = 0
        const val LINE: Byte = 1
        const val QUAD: Byte = 2
        const val CUBIC: Byte = 3
        const val CLOSE: Byte = 4
    }
}

/** Thrown for path data that is not valid SVG; [index] is where reading stopped. */
class SvgPathException(message: String, val index: Int) : IllegalArgumentException(message)

/** The SVG path data parser: every command, absolute and relative, implicit repeats, smooth curves and arcs. */
object SvgPath {
    fun parse(d: String): PathCommands = Parser(d).run()

    fun parseOrNull(d: String): PathCommands? = try { parse(d) } catch (e: SvgPathException) { null }

    private class Parser(val s: String) {
        var i = 0
        var verbs = ByteArray(32)
        var nv = 0
        var coords = FloatArray(128)
        var nc = 0

        // current point, subpath start, last control points for S and T
        var cx = 0.0; var cy = 0.0; var sx = 0.0; var sy = 0.0
        var lcx = 0.0; var lcy = 0.0; var lastCubic = false
        var lqx = 0.0; var lqy = 0.0; var lastQuad = false
        var open = false

        fun run(): PathCommands {
            skipSpace()
            var cmd = ' '
            var first = true
            while (i < s.length) {
                val ch = s[i]
                if (ch.isLetter()) {
                    cmd = ch
                    i++
                    if (first && cmd != 'M' && cmd != 'm') throw SvgPathException("path must start with a moveto", i - 1)
                    first = false
                    if (cmd == 'Z' || cmd == 'z') { closePath(); skipSpace(); continue }
                } else if (cmd == ' ') {
                    throw SvgPathException("data before the first command", i)
                } else if (cmd == 'Z' || cmd == 'z') {
                    throw SvgPathException("numbers after closepath", i)
                }
                skipSpace()
                command(cmd)
                skipSeparator()
                // after the first pair of a moveto, further pairs are linetos
                if (cmd == 'M') cmd = 'L' else if (cmd == 'm') cmd = 'l'
            }
            return PathCommands(verbs.copyOf(nv), coords.copyOf(nc))
        }

        fun command(cmd: Char) {
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0.0
            val oy = if (rel) cy else 0.0
            when (cmd.uppercaseChar()) {
                'M' -> {
                    val x = ox + number(); val y = oy + number()
                    emit(PathCommands.MOVE, x, y); cx = x; cy = y; sx = x; sy = y; open = true
                    lastCubic = false; lastQuad = false
                }
                'L' -> { val x = ox + number(); val y = oy + number(); line(x, y) }
                'H' -> line(ox + number(), cy)
                'V' -> line(cx, oy + number())
                'C' -> {
                    val x1 = ox + number(); val y1 = oy + number(); val x2 = ox + number(); val y2 = oy + number()
                    val x = ox + number(); val y = oy + number()
                    cubic(x1, y1, x2, y2, x, y)
                }
                'S' -> {
                    val x1 = if (lastCubic) 2 * cx - lcx else cx
                    val y1 = if (lastCubic) 2 * cy - lcy else cy
                    val x2 = ox + number(); val y2 = oy + number(); val x = ox + number(); val y = oy + number()
                    cubic(x1, y1, x2, y2, x, y)
                }
                'Q' -> {
                    val x1 = ox + number(); val y1 = oy + number(); val x = ox + number(); val y = oy + number()
                    quad(x1, y1, x, y)
                }
                'T' -> {
                    val x1 = if (lastQuad) 2 * cx - lqx else cx
                    val y1 = if (lastQuad) 2 * cy - lqy else cy
                    val x = ox + number(); val y = oy + number()
                    quad(x1, y1, x, y)
                }
                'A' -> {
                    val rx = abs(number()); val ry = abs(number()); val rot = number()
                    val large = flag(); val sweep = flag()
                    val x = ox + number(); val y = oy + number()
                    arc(rx, ry, rot, large, sweep, x, y)
                }
                else -> throw SvgPathException("unknown command '$cmd'", i - 1)
            }
        }

        fun ensureOpen() {
            if (!open) { emit(PathCommands.MOVE, cx, cy); open = true }
        }

        fun line(x: Double, y: Double) {
            ensureOpen()
            emit(PathCommands.LINE, x, y); cx = x; cy = y; lastCubic = false; lastQuad = false
        }

        fun cubic(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) {
            ensureOpen()
            emit6(x1, y1, x2, y2, x, y)
            lcx = x2; lcy = y2; lastCubic = true; lastQuad = false; cx = x; cy = y
        }

        fun quad(x1: Double, y1: Double, x: Double, y: Double) {
            ensureOpen()
            grow(1, 4)
            verbs[nv++] = PathCommands.QUAD
            coords[nc++] = x1.toFloat(); coords[nc++] = y1.toFloat(); coords[nc++] = x.toFloat(); coords[nc++] = y.toFloat()
            lqx = x1; lqy = y1; lastQuad = true; lastCubic = false; cx = x; cy = y
        }

        fun closePath() {
            if (open) {
                grow(1, 0)
                verbs[nv++] = PathCommands.CLOSE
            }
            cx = sx; cy = sy; open = false; lastCubic = false; lastQuad = false
        }

        /** SVG implementation notes F.6: endpoint to centre parameterisation, then at most 90° per cubic. */
        fun arc(rxIn: Double, ryIn: Double, rotDeg: Double, large: Boolean, sweep: Boolean, x: Double, y: Double) {
            val x0 = cx; val y0 = cy
            if (x0 == x && y0 == y) { lastCubic = false; lastQuad = false; return }
            if (rxIn == 0.0 || ryIn == 0.0) { line(x, y); return }
            var rx = rxIn; var ry = ryIn
            val phi = rotDeg * PI / 180
            val cosP = cos(phi); val sinP = sin(phi)
            val dx2 = (x0 - x) / 2; val dy2 = (y0 - y) / 2
            val x1p = cosP * dx2 + sinP * dy2
            val y1p = -sinP * dx2 + cosP * dy2
            val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
            if (lambda > 1) { val k = sqrt(lambda); rx *= k; ry *= k }
            val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
            val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
            var coef = if (den == 0.0) 0.0 else sqrt((num / den).coerceAtLeast(0.0))
            if (large == sweep) coef = -coef
            val cxp = coef * rx * y1p / ry
            val cyp = -coef * ry * x1p / rx
            val ccx = cosP * cxp - sinP * cyp + (x0 + x) / 2
            val ccy = sinP * cxp + cosP * cyp + (y0 + y) / 2
            val ux = (x1p - cxp) / rx; val uy = (y1p - cyp) / ry
            val vx = (-x1p - cxp) / rx; val vy = (-y1p - cyp) / ry
            val theta1 = atan2(uy, ux)
            var delta = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
            if (!sweep && delta > 0) delta -= 2 * PI else if (sweep && delta < 0) delta += 2 * PI
            val segments = ceil(abs(delta) / (PI / 2) - 1e-9).toInt().coerceAtLeast(1)
            val step = delta / segments
            val t = 4.0 / 3.0 * tan(step / 4)
            var a = theta1
            ensureOpen()
            for (k in 0 until segments) {
                val b = a + step
                val cosA = cos(a); val sinA = sin(a); val cosB = cos(b); val sinB = sin(b)
                // the unit-circle cubic, scaled by the radii, rotated and moved to the centre
                val p1x = cosA - t * sinA; val p1y = sinA + t * cosA
                val p2x = cosB + t * sinB; val p2y = sinB - t * cosB
                val ex = if (k == segments - 1) x else ccx + rx * cosB * cosP - ry * sinB * sinP
                val ey = if (k == segments - 1) y else ccy + rx * cosB * sinP + ry * sinB * cosP
                emit6(
                    ccx + rx * p1x * cosP - ry * p1y * sinP, ccy + rx * p1x * sinP + ry * p1y * cosP,
                    ccx + rx * p2x * cosP - ry * p2y * sinP, ccy + rx * p2x * sinP + ry * p2y * cosP,
                    ex, ey,
                )
                a = b
            }
            cx = x; cy = y; lastCubic = false; lastQuad = false
        }

        fun emit(v: Byte, x: Double, y: Double) {
            grow(1, 2)
            verbs[nv++] = v
            coords[nc++] = x.toFloat(); coords[nc++] = y.toFloat()
        }

        fun emit6(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) {
            grow(1, 6)
            verbs[nv++] = PathCommands.CUBIC
            coords[nc++] = x1.toFloat(); coords[nc++] = y1.toFloat(); coords[nc++] = x2.toFloat()
            coords[nc++] = y2.toFloat(); coords[nc++] = x.toFloat(); coords[nc++] = y.toFloat()
        }

        fun grow(v: Int, c: Int) {
            if (nv + v > verbs.size) verbs = verbs.copyOf(verbs.size * 2)
            if (nc + c > coords.size) coords = coords.copyOf(maxOf(coords.size * 2, nc + c))
        }

        fun skipSpace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r' || s[i] == '\u000C')) i++
        }

        fun skipSeparator() {
            skipSpace()
            if (i < s.length && s[i] == ',') { i++; skipSpace() }
        }

        fun flag(): Boolean {
            skipSeparatorBefore()
            if (i >= s.length) throw SvgPathException("arc flag expected", i)
            val c = s[i]
            if (c != '0' && c != '1') throw SvgPathException("arc flag must be 0 or 1", i)
            i++
            return c == '1'
        }

        fun skipSeparatorBefore() {
            skipSpace()
            if (i < s.length && s[i] == ',') { i++; skipSpace() }
        }

        /** One SVG number: sign, digits, one decimal point, exponent. "1.5.5" reads as 1.5 then .5, "1-2" as 1 then -2. */
        fun number(): Double {
            skipSeparatorBefore()
            val start = i
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            var digits = 0
            while (i < s.length && s[i] in '0'..'9') { i++; digits++ }
            if (i < s.length && s[i] == '.') {
                i++
                while (i < s.length && s[i] in '0'..'9') { i++; digits++ }
            }
            if (digits == 0) throw SvgPathException("number expected", start)
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                val save = i
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i < s.length && s[i] in '0'..'9') {
                    while (i < s.length && s[i] in '0'..'9') i++
                } else {
                    i = save
                }
            }
            return s.substring(start, i).toDouble()
        }
    }
}
