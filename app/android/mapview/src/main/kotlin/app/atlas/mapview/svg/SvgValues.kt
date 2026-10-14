package app.atlas.mapview.svg

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Reads numbers from a span of a char array, as SVG writes them: `-1.5e3`, `.7`, `0.5-.2`, separated by spaces or commas. */
internal class SvgNumbers(private val s: CharArray) {
    var pos = 0
    var end = 0

    fun reset(start: Int, stop: Int): SvgNumbers {
        pos = start
        end = stop
        return this
    }

    fun skipSeparators() {
        while (pos < end) {
            val c = s[pos]
            if (c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r') pos++ else break
        }
    }

    /** True when a number starts at the cursor, after separators. */
    fun atNumber(): Boolean {
        skipSeparators()
        if (pos >= end) return false
        val c = s[pos]
        return (c in '0'..'9') || c == '-' || c == '+' || c == '.'
    }

    fun next(): Float = nextDouble().toFloat()

    fun nextDouble(): Double {
        skipSeparators()
        if (pos >= end) throw SvgFormatException("number expected at $pos")
        var negative = false
        var c = s[pos]
        if (c == '-' || c == '+') {
            negative = c == '-'
            pos++
        }
        var mantissa = 0L
        var digits = 0
        var scale = 0
        var any = false
        while (pos < end) {
            c = s[pos]
            if (c !in '0'..'9') break
            any = true
            if (digits < 18) {
                mantissa = mantissa * 10 + (c - '0')
                if (mantissa != 0L) digits++
            } else {
                scale++
            }
            pos++
        }
        if (pos < end && s[pos] == '.') {
            pos++
            while (pos < end) {
                c = s[pos]
                if (c !in '0'..'9') break
                any = true
                if (digits < 18) {
                    mantissa = mantissa * 10 + (c - '0')
                    if (mantissa != 0L) digits++
                    scale--
                }
                pos++
            }
        }
        if (!any) throw SvgFormatException("bad number at $pos")
        if (pos < end && (s[pos] == 'e' || s[pos] == 'E')) {
            val save = pos
            pos++
            var expNegative = false
            if (pos < end && (s[pos] == '-' || s[pos] == '+')) {
                expNegative = s[pos] == '-'
                pos++
            }
            if (pos < end && s[pos] in '0'..'9') {
                var e = 0
                while (pos < end && s[pos] in '0'..'9') {
                    if (e < 10000) e = e * 10 + (s[pos] - '0')
                    pos++
                }
                scale += if (expNegative) -e else e
            } else {
                pos = save
            }
        }
        val v = scaled(mantissa, scale)
        return if (negative) -v else v
    }

    /** Reads a single path flag (`0` or `1`), which may touch the next number. */
    fun nextFlag(): Boolean {
        skipSeparators()
        if (pos >= end) throw SvgFormatException("flag expected at $pos")
        val c = s[pos++]
        return when (c) {
            '0' -> false
            '1' -> true
            else -> throw SvgFormatException("bad flag '$c' at ${pos - 1}")
        }
    }

    companion object {
        private val POW10 = DoubleArray(23).also { p ->
            p[0] = 1.0
            for (i in 1 until p.size) p[i] = p[i - 1] * 10
        }

        /** mantissa × 10^scale, correctly rounded when both fit the exact double range. */
        fun scaled(mantissa: Long, scale: Int): Double {
            if (mantissa == 0L) return 0.0
            val m = mantissa.toDouble()
            return when {
                scale == 0 -> m
                scale in 1..22 -> m * POW10[scale]
                scale in -22..-1 -> m / POW10[-scale]
                else -> m * Math.pow(10.0, scale.toDouble())
            }
        }
    }
}

/** Parses path data, transforms, colours and lengths. */
internal object SvgValues {
    /**
     * Appends the path [s] (from [start] to [end]) to [out]. The engine writes only straight segments (`M L H V Z`, either
     * case); any curve or arc fails with [SvgFormatException]. Malformed data (a missing or broken number, as some
     * minified board copies have) ends the path where it breaks, as browsers do: everything before it is drawn.
     */
    fun parsePath(s: CharArray, start: Int, end: Int, out: PathBuilder, nums: SvgNumbers) {
        try {
            parsePathStrict(s, start, end, out, nums)
        } catch (e: MalformedPath) {
            // Drawn up to the last complete segment.
        }
    }

    /** A path whose data breaks off; what came before still counts. */
    private class MalformedPath : RuntimeException(null, null, false, false)

    private fun number(nums: SvgNumbers): Float = try {
        nums.next()
    } catch (e: SvgFormatException) {
        throw MalformedPath()
    }

    private fun parsePathStrict(s: CharArray, start: Int, end: Int, out: PathBuilder, nums: SvgNumbers) {
        nums.reset(start, end)
        var cmd = ' '
        var x = 0f
        var y = 0f
        var sx = 0f
        var sy = 0f
        while (true) {
            nums.skipSeparators()
            if (nums.pos >= end) break
            val c = s[nums.pos]
            if (c.isLetter()) {
                cmd = c
                nums.pos++
                if (cmd == 'Z' || cmd == 'z') {
                    out.close()
                    x = sx
                    y = sy
                    continue
                }
            } else if (cmd == ' ' || cmd == 'Z' || cmd == 'z') {
                // Data before any command, or a number after a close: the path ends here.
                throw MalformedPath()
            }
            when (cmd) {
                'M', 'm' -> {
                    val nx = number(nums)
                    val ny = number(nums)
                    if (cmd == 'm') {
                        x += nx
                        y += ny
                    } else {
                        x = nx
                        y = ny
                    }
                    out.moveTo(x, y)
                    sx = x
                    sy = y
                    cmd = if (cmd == 'm') 'l' else 'L'
                }
                'L' -> {
                    x = number(nums)
                    y = number(nums)
                    out.lineTo(x, y)
                }
                'l' -> {
                    x += number(nums)
                    y += number(nums)
                    out.lineTo(x, y)
                }
                'H' -> {
                    x = number(nums)
                    out.lineTo(x, y)
                }
                'h' -> {
                    x += number(nums)
                    out.lineTo(x, y)
                }
                'V' -> {
                    y = number(nums)
                    out.lineTo(x, y)
                }
                'v' -> {
                    y += number(nums)
                    out.lineTo(x, y)
                }
                else -> throw SvgFormatException("path command '$cmd' is not written by the engine")
            }
        }
    }

    /** Growing arrays of path verbs and coordinates. */
    class PathBuilder {
        var verbs = ByteArray(1 shl 14)
        var verbCount = 0
        var coords = FloatArray(1 shl 15)
        var coordCount = 0
        private var open = false

        fun moveTo(x: Float, y: Float) {
            verb(SvgPathData.MOVE)
            coord(x, y)
            open = true
        }

        fun lineTo(x: Float, y: Float) {
            verb(SvgPathData.LINE)
            coord(x, y)
        }

        fun close() {
            if (open) verb(SvgPathData.CLOSE)
        }

        private fun verb(v: Byte) {
            if (verbCount == verbs.size) verbs = verbs.copyOf(verbs.size * 2)
            verbs[verbCount++] = v
        }

        private fun coord(x: Float, y: Float) {
            if (coordCount + 2 > coords.size) coords = coords.copyOf(coords.size * 2)
            coords[coordCount++] = x
            coords[coordCount++] = y
        }

        fun build() = SvgPathData(verbs.copyOf(verbCount), coords.copyOf(coordCount))
    }

    /** Parses a transform list into `a b c d e f`, or null for identity. */
    fun parseTransform(t: String): FloatArray? {
        val chars = t.toCharArray()
        val nums = SvgNumbers(chars)
        var m = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        var i = 0
        val args = DoubleArray(6)
        var any = false
        while (i < chars.size) {
            while (i < chars.size && (SvgXml.isSpace(chars[i]) || chars[i] == ',')) i++
            if (i >= chars.size) break
            val nameStart = i
            while (i < chars.size && chars[i].isLetter()) i++
            val name = String(chars, nameStart, i - nameStart)
            while (i < chars.size && SvgXml.isSpace(chars[i])) i++
            if (i >= chars.size || chars[i] != '(') throw SvgFormatException("bad transform '$t'")
            val close = t.indexOf(')', i)
            if (close < 0) throw SvgFormatException("bad transform '$t'")
            nums.reset(i + 1, close)
            var n = 0
            while (nums.atNumber()) {
                if (n == 6) throw SvgFormatException("too many arguments in '$t'")
                args[n++] = nums.nextDouble()
            }
            i = close + 1
            val f: DoubleArray = when (name) {
                "matrix" -> {
                    need(n == 6, t)
                    args.copyOf()
                }
                "translate" -> {
                    need(n == 1 || n == 2, t)
                    doubleArrayOf(1.0, 0.0, 0.0, 1.0, args[0], if (n == 2) args[1] else 0.0)
                }
                "scale" -> {
                    need(n == 1 || n == 2, t)
                    doubleArrayOf(args[0], 0.0, 0.0, if (n == 2) args[1] else args[0], 0.0, 0.0)
                }
                "rotate" -> {
                    need(n == 1 || n == 3, t)
                    val r = args[0] * PI / 180
                    val c = cos(r)
                    val s = sin(r)
                    val rot = doubleArrayOf(c, s, -s, c, 0.0, 0.0)
                    if (n == 3) {
                        val cx = args[1]
                        val cy = args[2]
                        multiply(multiply(doubleArrayOf(1.0, 0.0, 0.0, 1.0, cx, cy), rot), doubleArrayOf(1.0, 0.0, 0.0, 1.0, -cx, -cy))
                    } else {
                        rot
                    }
                }
                "skewX" -> {
                    need(n == 1, t)
                    doubleArrayOf(1.0, 0.0, tan(args[0] * PI / 180), 1.0, 0.0, 0.0)
                }
                "skewY" -> {
                    need(n == 1, t)
                    doubleArrayOf(1.0, tan(args[0] * PI / 180), 0.0, 1.0, 0.0, 0.0)
                }
                else -> throw SvgFormatException("transform '$name' is not supported")
            }
            m = multiply(m, f)
            any = true
        }
        if (!any) return null
        return FloatArray(6) { m[it].toFloat() }
    }

    private fun need(ok: Boolean, t: String) {
        if (!ok) throw SvgFormatException("bad transform '$t'")
    }

    /** The product a × b of two affine matrices `a b c d e f` (b applies first). */
    fun multiply(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[0] * b[0] + a[2] * b[1],
        a[1] * b[0] + a[3] * b[1],
        a[0] * b[2] + a[2] * b[3],
        a[1] * b[2] + a[3] * b[3],
        a[0] * b[4] + a[2] * b[5] + a[4],
        a[1] * b[4] + a[3] * b[5] + a[5],
    )

    /** Parses a colour into opaque-or-not ARGB, or null for `none`. */
    fun parseColor(v: String): Int? {
        val t = v.trim()
        if (t == "none") return null
        if (t.startsWith("#")) {
            val h = t.substring(1)
            fun hex(i: Int) = Character.digit(h[i], 16).also { if (it < 0) throw SvgFormatException("bad colour '$v'") }
            return when (h.length) {
                3 -> argb(255, hex(0) * 17, hex(1) * 17, hex(2) * 17)
                4 -> argb(hex(3) * 17, hex(0) * 17, hex(1) * 17, hex(2) * 17)
                6 -> argb(255, hex(0) * 16 + hex(1), hex(2) * 16 + hex(3), hex(4) * 16 + hex(5))
                8 -> argb(hex(6) * 16 + hex(7), hex(0) * 16 + hex(1), hex(2) * 16 + hex(3), hex(4) * 16 + hex(5))
                else -> throw SvgFormatException("bad colour '$v'")
            }
        }
        if (t.startsWith("rgb")) {
            val open = t.indexOf('(')
            val close = t.lastIndexOf(')')
            if (open < 0 || close < open) throw SvgFormatException("bad colour '$v'")
            val parts = t.substring(open + 1, close).split(',', ' ', '/').filter { it.isNotBlank() }
            if (parts.size != 3 && parts.size != 4) throw SvgFormatException("bad colour '$v'")
            fun channel(p: String): Int {
                val n = if (p.endsWith("%")) p.dropLast(1).toFloat() * 2.55f else p.toFloat()
                return Math.round(n.coerceIn(0f, 255f))
            }
            val alpha = if (parts.size == 4) {
                val p = parts[3]
                val a = if (p.endsWith("%")) p.dropLast(1).toFloat() / 100f else p.toFloat()
                Math.round(a.coerceIn(0f, 1f) * 255)
            } else {
                255
            }
            return argb(alpha, channel(parts[0]), channel(parts[1]), channel(parts[2]))
        }
        return when (t.lowercase()) {
            "black" -> argb(255, 0, 0, 0)
            "white" -> argb(255, 255, 255, 255)
            "transparent" -> 0
            else -> throw SvgFormatException("colour '$v' is not written by the engine")
        }
    }

    fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** A length or number; a percentage returns its fraction with [percent] set. */
    fun parseLength(v: String, percent: BooleanArray? = null): Float {
        val t = v.trim()
        if (t.endsWith("%")) {
            percent?.set(0, true)
            return t.dropLast(1).trim().toFloat() / 100f
        }
        percent?.set(0, false)
        val n = if (t.endsWith("px")) t.dropLast(2) else t
        return n.trim().toFloatOrNull() ?: throw SvgFormatException("bad length '$v'")
    }

    /** Parses a list of numbers. */
    fun parseNumbers(v: String): FloatArray {
        val chars = v.toCharArray()
        val nums = SvgNumbers(chars).reset(0, chars.size)
        val out = ArrayList<Float>()
        while (nums.atNumber()) out.add(nums.next())
        return out.toFloatArray()
    }
}
