package app.atlas.render.py

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

/**
 * Python's number and string formatting, character for character: `%` formatting, `format()` / f-string specs,
 * `repr()` and `str()` of floats (the shortest round-trip digits), all rounding the exact binary value half to even.
 *
 * Kotlin values stand for Python ones as in [PyHash]: Int, Long and BigInteger are int, Double is float,
 * Boolean is bool, null is None, String is str, a List is a list.
 */
object PyFmt {
    private val POW10 = DoubleArray(23).also { var p = 1.0; for (i in it.indices) { it[i] = p; p *= 10.0 } }
    private val POW10L = LongArray(16).also { var p = 1L; for (i in it.indices) { it[i] = p; p *= 10L } }
    private const val TWO52 = 4503599627370496.0
    private const val SPLIT = 134217729.0 // 2^27 + 1, Veltkamp's splitter
    private const val HALF_ULP = 1.1102230246251565e-16 // 2^-53: half an ulp, relative

    // ---------------------------------------------------------------- floats

    /** `'%.{prec}f' % x`. */
    fun fixed(x: Double, prec: Int): String {
        if (prec !in 0..15 || !x.isFinite()) return fixedExact(x, prec)
        val a = Math.abs(x)
        val s = a * POW10[prec]
        if (s >= TWO52) return fixedExact(x, prec)
        // the digits of the rounded value, written from the last one back, then the sign
        var k = scaledRound(a, POW10[prec], s)
        val buf = CharArray(prec + 21)
        var i = buf.size
        if (prec > 0) {
            for (n in 0 until prec) {
                val q = k / 10
                buf[--i] = ('0'.code + (k - q * 10).toInt()).toChar(); k = q
            }
            buf[--i] = '.'
        }
        do {
            val q = k / 10
            buf[--i] = ('0'.code + (k - q * 10).toInt()).toChar(); k = q
        } while (k != 0L)
        if (x < 0 || (x == 0.0 && 1.0 / x < 0)) buf[--i] = '-'
        return String(buf, i, buf.size - i)
    }

    /**
     * Appends `'%.{prec}f' % x` to [sb]: the exact binary value of [x] rounded half to even, as CPython does. Values whose scaled
     * magnitude reaches 2^52 (and precisions over 15) take the exact decimal path; the rest are decided in doubles without error.
     */
    fun appendFixed(sb: StringBuilder, x: Double, prec: Int): StringBuilder {
        if (prec !in 0..15 || !x.isFinite()) return sb.append(fixedExact(x, prec))
        val m = POW10[prec]
        val a = Math.abs(x)
        val s = a * m
        if (s >= TWO52) return sb.append(fixedExact(x, prec))
        val k = scaledRound(a, m, s)
        if (x < 0 || (x == 0.0 && 1.0 / x < 0)) sb.append('-')
        if (prec == 0) return sb.append(k)
        val p = POW10L[prec]
        sb.append(k / p).append('.')
        val frac = k % p
        var d = p / 10
        while (d > 0) {
            sb.append(('0'.code + ((frac / d) % 10).toInt()).toChar())
            d /= 10
        }
        return sb
    }

    /**
     * `a * m` (both positive and finite, `s` its double product, below 2^52) rounded to an integer half to even, from its exact
     * value: Python's `'%.Nf'` of `a` with `m = 10^N`, as digits.
     */
    private fun scaledRound(a: Double, m: Double, s: Double): Long {
        val r = Math.floor(s)
        // s - r is exact (s < 2^52), and so is t = f - 0.5 once f >= 0.25; below that t stays clearly negative.
        val t = (s - r) - 0.5
        val k = r.toLong()
        // a * m = s + err exactly, |err| <= ulp(s) / 2 <= s * 2^-53: a t beyond that already has the exact sum's sign. Nearer a
        // half, err comes from Dekker's product (which needs no fma: ART computes Math.fma through BigDecimal), and t + err,
        // rounded to nearest, has the sign of the exact sum, and is zero only when the sum is.
        val u = if (Math.abs(t) > s * HALF_ULP) t else t + productError(a, m, s)
        return if (u > 0 || (u == 0.0 && (k and 1L) == 1L)) k + 1 else k
    }

    /** The rounding error of `s = a * m` (both positive, finite, the product below 2^52): `a * m - s`, exactly. */
    private fun productError(a: Double, m: Double, s: Double): Double {
        val ca = SPLIT * a; val ah = ca - (ca - a); val al = a - ah
        val cm = SPLIT * m; val mh = cm - (cm - m); val ml = m - mh
        return ((ah * mh - s) + ah * ml + al * mh) + al * ml
    }

    /** `'%.{prec}f' % x` through the exact decimal value. */
    private fun fixedExact(x: Double, prec: Int): String {
        if (!x.isFinite()) return nonFinite(x)
        val neg = x < 0 || (x == 0.0 && 1.0 / x < 0)
        val body = BigDecimal(Math.abs(x)).setScale(prec, RoundingMode.HALF_EVEN).toPlainString()
        return if (neg) "-$body" else body
    }

    private fun nonFinite(x: Double): String = if (x.isNaN()) "nan" else if (x > 0) "inf" else "-inf"

    /** `repr(x)` and `str(x)` of a float. */
    fun repr(x: Double): String {
        if (!x.isFinite()) return nonFinite(x)
        if (x == 0.0) return if (1.0 / x < 0) "-0.0" else "0.0"
        val neg = x < 0
        val a = Math.abs(x)
        // Integral values below 2**53 print as their digits with ".0".
        if (a < 1e16 && a == Math.floor(a)) {
            val s = a.toLong().toString() + ".0"
            return if (neg) "-$s" else s
        }
        val (digits, decpt) = shortest(a)
        val sb = StringBuilder(24)
        if (neg) sb.append('-')
        if (decpt > -4 && decpt <= 16) {
            if (decpt <= 0) {
                sb.append("0.")
                for (i in 0 until -decpt) sb.append('0')
                sb.append(digits)
            } else if (decpt >= digits.length) {
                sb.append(digits)
                for (i in 0 until decpt - digits.length) sb.append('0')
                sb.append(".0")
            } else {
                sb.append(digits, 0, decpt).append('.').append(digits, decpt, digits.length)
            }
        } else {
            sb.append(digits[0])
            if (digits.length > 1) sb.append('.').append(digits, 1, digits.length)
            appendExponent(sb, decpt - 1)
        }
        return sb.toString()
    }

    private fun appendExponent(sb: StringBuilder, e: Int) {
        sb.append('e').append(if (e < 0) '-' else '+')
        val ae = Math.abs(e)
        if (ae < 10) sb.append('0')
        sb.append(ae)
    }

    /**
     * The shortest decimal digits that read back as [a] (a positive finite double), nearest to [a] among
     * equally short ones, as CPython's dtoa mode 0. Returns the digits and the decimal point position.
     */
    fun shortest(a: Double): Pair<String, Int> {
        val exact = BigDecimal(a)
        for (p in 1..17) {
            val r = exact.round(MathContext(p, RoundingMode.HALF_EVEN))
            if (reads(r, a)) return split(r)
            // Near a power of two the round-trip interval is lopsided: the other neighbour may read back.
            val lead = r.precision() - r.scale() - 1
            val unit = BigDecimal.ONE.scaleByPowerOfTen(lead - p + 1)
            val up = r.add(unit)
            val down = r.subtract(unit)
            val okUp = reads(up, a)
            val okDown = down.signum() > 0 && reads(down, a)
            if (okUp && okDown) {
                return split(if (up.subtract(exact).abs() <= exact.subtract(down).abs()) up else down)
            }
            if (okUp) return split(up)
            if (okDown) return split(down)
        }
        return split(exact.round(MathContext(17, RoundingMode.HALF_EVEN)))
    }

    private fun reads(d: BigDecimal, a: Double): Boolean = java.lang.Double.parseDouble(d.toString()) == a

    private fun split(r: BigDecimal): Pair<String, Int> {
        val s = r.stripTrailingZeros()
        val digits = s.unscaledValue().toString()
        return digits to (digits.length - s.scale())
    }

    /** `'%.{prec}e' % x`. */
    fun exp(x: Double, prec: Int, upper: Boolean = false, alt: Boolean = false): String {
        if (!x.isFinite()) return nonFinite(x).let { if (upper) it.uppercase() else it }
        val neg = x < 0 || (x == 0.0 && 1.0 / x < 0)
        val a = Math.abs(x)
        val sb = StringBuilder()
        if (neg) sb.append('-')
        var digits: String
        var e: Int
        if (a == 0.0) {
            digits = "0".repeat(prec + 1); e = 0
        } else {
            val r = BigDecimal(a).round(MathContext(prec + 1, RoundingMode.HALF_EVEN))
            digits = r.unscaledValue().toString()
            e = digits.length - r.scale() - 1
            if (digits.length < prec + 1) digits += "0".repeat(prec + 1 - digits.length)
            else if (digits.length > prec + 1) digits = digits.substring(0, prec + 1)
        }
        sb.append(digits[0])
        if (prec > 0 || alt) sb.append('.')
        sb.append(digits, 1, digits.length)
        appendExponent(sb, e)
        val s = sb.toString()
        return if (upper) s.uppercase() else s
    }

    /**
     * `'%.{prec}g' % x`. With [addDot0] (`format()` with a precision and no type) the switch to exponent
     * notation comes one digit earlier, and an integral fixed result gets ".0".
     */
    fun general(x: Double, prec: Int = 6, upper: Boolean = false, alt: Boolean = false, addDot0: Boolean = false): String {
        if (!x.isFinite()) return nonFinite(x).let { if (upper) it.uppercase() else it }
        val p = if (prec == 0) 1 else prec
        val neg = x < 0 || (x == 0.0 && 1.0 / x < 0)
        val a = Math.abs(x)
        val sb = StringBuilder()
        if (neg) sb.append('-')
        val r: BigDecimal
        val xe: Int
        if (a == 0.0) {
            r = BigDecimal.ZERO; xe = 0
        } else {
            r = BigDecimal(a).round(MathContext(p, RoundingMode.HALF_EVEN))
            xe = r.precision() - r.scale() - 1
        }
        var body: String
        if (xe >= -4 && xe < (if (addDot0) p - 1 else p)) {
            body = r.setScale(p - 1 - xe, RoundingMode.UNNECESSARY).toPlainString()
            if (!alt && body.contains('.')) body = body.trimEnd('0').trimEnd('.')
            if (alt && !body.contains('.')) body += "."
            if (addDot0 && !body.contains('.')) body += ".0"
            sb.append(body)
        } else {
            var ds = r.unscaledValue().toString()
            if (ds.length < p) ds += "0".repeat(p - ds.length)
            if (!alt) ds = ds.trimEnd('0').ifEmpty { "0" }
            sb.append(ds[0])
            if (ds.length > 1 || alt) sb.append('.')
            sb.append(ds, 1, ds.length)
            appendExponent(sb, xe)
        }
        val s = sb.toString()
        return if (upper) s.uppercase() else s
    }

    // ---------------------------------------------------------------- str() and repr() of values

    /** Python `str(v)`. */
    fun str(v: Any?): String = when (v) {
        null -> "None"
        is String -> v
        is Boolean -> if (v) "True" else "False"
        is Double -> repr(v)
        is Float -> repr(v.toDouble())
        is Int, is Long, is BigInteger -> v.toString()
        is List<*> -> v.joinToString(", ", "[", "]") { repr(it) }
        is Map<*, *> -> v.entries.joinToString(", ", "{", "}") { repr(it.key) + ": " + repr(it.value) }
        else -> v.toString()
    }

    /** Python `repr(v)`. */
    fun repr(v: Any?): String = when (v) {
        is String -> reprStr(v)
        is Double -> repr(v)
        else -> str(v)
    }

    /** Python `repr()` of a tuple. */
    fun reprTuple(items: List<*>): String =
        if (items.size == 1) "(" + repr(items[0]) + ",)" else items.joinToString(", ", "(", ")") { repr(it) }

    private fun reprStr(s: String): String {
        val q = if (s.contains('\'') && !s.contains('"')) '"' else '\''
        val sb = StringBuilder(s.length + 2).append(q)
        for (ch in s) {
            when {
                ch == q || ch == '\\' -> sb.append('\\').append(ch)
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch.code < 0x20 || ch.code == 0x7f -> sb.append(String.format("\\x%02x", ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append(q).toString()
    }

    // ---------------------------------------------------------------- % formatting

    private class Spec(
        val minus: Boolean, val plus: Boolean, val space: Boolean, val zero: Boolean, val alt: Boolean,
        val width: Int, val prec: Int, val type: Char,
    )

    private val cache = ConcurrentHashMap<String, List<Any>>()

    private fun parse(fmt: String): List<Any> {
        val out = ArrayList<Any>()
        val lit = StringBuilder()
        var i = 0
        while (i < fmt.length) {
            val c = fmt[i]
            if (c != '%') {
                lit.append(c); i++; continue
            }
            i++
            require(i < fmt.length) { "incomplete format" }
            if (fmt[i] == '%') {
                lit.append('%'); i++; continue
            }
            if (lit.isNotEmpty()) {
                out.add(lit.toString()); lit.setLength(0)
            }
            var minus = false; var plus = false; var space = false; var zero = false; var alt = false
            loop@ while (i < fmt.length) {
                when (fmt[i]) {
                    '-' -> minus = true
                    '+' -> plus = true
                    ' ' -> space = true
                    '0' -> zero = true
                    '#' -> alt = true
                    else -> break@loop
                }
                i++
            }
            var width = 0
            while (i < fmt.length && fmt[i].isDigit()) width = width * 10 + (fmt[i++] - '0')
            var prec = -1
            if (i < fmt.length && fmt[i] == '.') {
                i++; prec = 0
                while (i < fmt.length && fmt[i].isDigit()) prec = prec * 10 + (fmt[i++] - '0')
            }
            while (i < fmt.length && (fmt[i] == 'l' || fmt[i] == 'h' || fmt[i] == 'L')) i++
            require(i < fmt.length) { "incomplete format" }
            val type = fmt[i++]
            require(type in "diouxXeEfFgGcrsa") { "unsupported format character '$type'" }
            out.add(Spec(minus, plus, space, zero, alt, width, prec, type))
        }
        if (lit.isNotEmpty()) out.add(lit.toString())
        return out
    }

    /** Python `fmt % (args…)`. */
    fun pf(fmt: String, vararg args: Any?): String {
        val parts = cache.getOrPut(fmt) { parse(fmt) }
        val sb = StringBuilder(fmt.length + 16 * args.size)
        var k = 0
        for (p in parts) {
            if (p is String) {
                sb.append(p); continue
            }
            require(k < args.size) { "not enough arguments for format string" }
            conv(sb, p as Spec, args[k++])
        }
        require(k == args.size) { "not all arguments converted during string formatting" }
        return sb.toString()
    }

    private fun toDouble(v: Any?): Double = when (v) {
        is Double -> v
        is Float -> v.toDouble()
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is BigInteger -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        else -> throw IllegalArgumentException("must be real number, not ${v?.let { it::class.simpleName }}")
    }

    private fun toInteger(v: Any?): BigInteger = when (v) {
        is Int -> BigInteger.valueOf(v.toLong())
        is Long -> BigInteger.valueOf(v)
        is BigInteger -> v
        is Boolean -> if (v) BigInteger.ONE else BigInteger.ZERO
        is Double -> {
            if (!v.isFinite()) throw ArithmeticException("cannot convert float to integer")
            if (Math.abs(v) < 9e18) BigInteger.valueOf(v.toLong()) else BigDecimal(v).toBigInteger()
        }
        is Float -> toInteger(v.toDouble())
        else -> throw IllegalArgumentException("%d format: a real number is required, not ${v?.let { it::class.simpleName }}")
    }

    private fun codePoint(n: BigInteger): Int {
        if (n.signum() < 0 || n > BigInteger.valueOf(0x10ffff)) throw ArithmeticException("%c arg not in range(0x110000)")
        return n.toInt()
    }

    private fun conv(sb: StringBuilder, s: Spec, v: Any?) {
        // the common cases, straight into the output: '%.Nf' of a float and '%d' of an int, unpadded and unsigned by flags
        if (s.width == 0 && !s.plus && !s.space) {
            if (s.type == 'f' && v is Double && (!s.alt || s.prec != 0)) {
                appendFixed(sb, v, if (s.prec < 0) 6 else s.prec); return
            }
            if (s.type == 'd' && s.prec < 0 && (v is Int || v is Long)) {
                sb.append((v as Number).toLong()); return
            }
        }
        var sign = ""
        var body: String
        var numeric = true
        when (s.type) {
            'd', 'i', 'u' -> {
                val n = toInteger(v)
                if (n.signum() < 0) sign = "-"
                body = n.abs().toString()
                if (s.prec > body.length) body = "0".repeat(s.prec - body.length) + body
            }
            'x', 'X', 'o' -> {
                val n = toInteger(v)
                if (n.signum() < 0) sign = "-"
                body = n.abs().toString(if (s.type == 'o') 8 else 16)
                if (s.type == 'X') body = body.uppercase()
                if (s.prec > body.length) body = "0".repeat(s.prec - body.length) + body
                if (s.alt) body = (if (s.type == 'o') "0o" else if (s.type == 'X') "0X" else "0x") + body
            }
            'e', 'E', 'f', 'F', 'g', 'G' -> {
                val x = toDouble(v)
                val prec = if (s.prec < 0) 6 else s.prec
                var t = when (s.type) {
                    'f', 'F' -> if (s.alt && prec == 0 && x.isFinite()) fixed(x, 0) + "." else fixed(x, prec)
                    'e', 'E' -> exp(x, prec, s.type == 'E', s.alt)
                    else -> general(x, prec, s.type == 'G', s.alt)
                }
                if (s.type == 'F') t = t.uppercase()
                if (t.startsWith("-")) {
                    sign = "-"; t = t.substring(1)
                }
                body = t
                if (!x.isFinite()) numeric = false
            }
            'c' -> {
                numeric = false
                body = when (v) {
                    is String -> v
                    else -> String(Character.toChars(codePoint(toInteger(v))))
                }
            }
            'r', 'a' -> {
                numeric = false; body = repr(v)
                if (s.prec >= 0 && body.length > s.prec) body = body.substring(0, s.prec)
            }
            else -> {
                numeric = false; body = str(v)
                if (s.prec >= 0 && body.length > s.prec) body = body.substring(0, s.prec)
            }
        }
        if (numeric && sign.isEmpty()) {
            if (s.plus) sign = "+" else if (s.space) sign = " "
        }
        val len = sign.length + body.length
        if (len >= s.width) {
            sb.append(sign).append(body); return
        }
        val pad = s.width - len
        if (s.minus) {
            sb.append(sign).append(body); repeat(pad) { sb.append(' ') }
        } else if (s.zero && numeric) {
            sb.append(sign)
            // the 0x prefix stays in front of the zeros
            if (body.startsWith("0x") || body.startsWith("0X") || body.startsWith("0o")) {
                sb.append(body, 0, 2); repeat(pad) { sb.append('0') }; sb.append(body, 2, body.length)
            } else {
                repeat(pad) { sb.append('0') }; sb.append(body)
            }
        } else {
            repeat(pad) { sb.append(' ') }; sb.append(sign).append(body)
        }
    }

    // ---------------------------------------------------------------- format() and f-strings

    /** Python `format(v, spec)`, the same as `f'{v:spec}'`. */
    fun format(v: Any?, spec: String): String {
        var i = 0
        var fill = ' '
        var align = 0.toChar()
        if (spec.length >= 2 && spec[1] in "<>=^") {
            fill = spec[0]; align = spec[1]; i = 2
        } else if (spec.isNotEmpty() && spec[0] in "<>=^") {
            align = spec[0]; i = 1
        }
        var sign = '-'
        if (i < spec.length && spec[i] in "+- ") sign = spec[i++]
        var zNeg = false
        if (i < spec.length && spec[i] == 'z') {
            require(PyCompat.minor >= 11) { "Invalid format specifier '$spec'" }
            zNeg = true; i++
        }
        var alt = false
        if (i < spec.length && spec[i] == '#') {
            alt = true; i++
        }
        if (i < spec.length && spec[i] == '0') {
            if (align == 0.toChar()) {
                fill = '0'; align = '='
            }
            i++
        }
        var width = 0
        while (i < spec.length && spec[i].isDigit()) width = width * 10 + (spec[i++] - '0')
        var group = 0.toChar()
        if (i < spec.length && (spec[i] == ',' || spec[i] == '_')) group = spec[i++]
        var prec = -1
        if (i < spec.length && spec[i] == '.') {
            i++; prec = 0
            while (i < spec.length && spec[i].isDigit()) prec = prec * 10 + (spec[i++] - '0')
        }
        val type = if (i < spec.length) spec[i++] else 0.toChar()
        require(i == spec.length) { "Invalid format specifier '$spec'" }

        var signStr = ""
        var body: String
        val isNum = v is Number || v is Boolean
        if (!isNum || type == 's') {
            body = str(v)
            if (prec >= 0 && body.length > prec) body = body.substring(0, prec)
            return pad("", body, width, fill, if (align == 0.toChar()) '<' else align)
        }
        val isInt = v is Int || v is Long || v is BigInteger || v is Boolean
        if (isInt && (type == 0.toChar() || type in "dxXobcn")) {
            val n = toInteger(v)
            if (n.signum() < 0) signStr = "-"
            val a = n.abs()
            body = when (type) {
                'x' -> (if (alt) "0x" else "") + a.toString(16)
                'X' -> (if (alt) "0X" else "") + a.toString(16).uppercase()
                'o' -> (if (alt) "0o" else "") + a.toString(8)
                'b' -> (if (alt) "0b" else "") + a.toString(2)
                'c' -> String(Character.toChars(codePoint(n)))
                else -> group(a.toString(), group)
            }
        } else {
            val x = toDouble(v)
            var t: String = when (type) {
                'f', 'F' -> fixed(x, if (prec < 0) 6 else prec).let { if (type == 'F') it.uppercase() else it }
                'e', 'E' -> exp(x, if (prec < 0) 6 else prec, type == 'E', alt)
                'g', 'G', 'n' -> general(x, if (prec < 0) 6 else prec, type == 'G', alt)
                '%' -> fixed(x * 100, if (prec < 0) 6 else prec) + "%"
                else -> if (prec < 0) {
                    if (isInt) toInteger(v).toString() else repr(x)
                } else {
                    // no type with a precision: like 'g', but always at least one digit after the point
                    general(x, prec, false, alt, addDot0 = true)
                }
            }
            if (zNeg && t.startsWith("-") && t.substring(1).all { it == '0' || it == '.' }) t = t.substring(1)
            if (t.startsWith("-")) {
                signStr = "-"; t = t.substring(1)
            }
            if (group != 0.toChar() && x.isFinite()) {
                val dot = t.indexOfFirst { it == '.' || it == 'e' || it == '%' }.let { if (it < 0) t.length else it }
                t = group(t.substring(0, dot), group) + t.substring(dot)
            }
            body = t
        }
        if (signStr.isEmpty()) {
            if (sign == '+') signStr = "+" else if (sign == ' ') signStr = " "
        }
        return pad(signStr, body, width, fill, if (align == 0.toChar()) '>' else align)
    }

    private fun group(digits: String, sep: Char): String {
        if (sep == 0.toChar() || digits.length <= 3) return digits
        val sb = StringBuilder()
        val first = digits.length % 3
        if (first > 0) sb.append(digits, 0, first)
        var i = first
        while (i < digits.length) {
            if (sb.isNotEmpty()) sb.append(sep)
            sb.append(digits, i, i + 3); i += 3
        }
        return sb.toString()
    }

    private fun pad(sign: String, body: String, width: Int, fill: Char, align: Char): String {
        val len = sign.length + body.length
        if (len >= width) return sign + body
        val n = width - len
        val f = fill.toString()
        return when (align) {
            '<' -> sign + body + f.repeat(n)
            '^' -> f.repeat(n / 2) + sign + body + f.repeat(n - n / 2)
            '=' -> sign + f.repeat(n) + body
            else -> f.repeat(n) + sign + body
        }
    }
}
