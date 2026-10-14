package app.atlas.render

/**
 * "The same up to the platform's libm": CPython takes sin, cos, tan, exp, log and pow from the C library, the port
 * from fdlibm, and they differ in the last bit now and then. Values computed from them may differ by that much,
 * relatively, and no more. Emitted SVG text is never compared this way: it must be equal.
 */
object Close {
    private const val REL = 1e-12
    private const val ABS = 1e-9

    /** Equal, or apart by no more than libm noise. */
    fun close(a: Double, b: Double): Boolean =
        a == b || (a.isNaN() && b.isNaN()) || Math.abs(a - b) <= REL * Math.max(Math.abs(a), Math.abs(b)) + ABS

    /** Two texts equal but for numbers apart by no more than libm noise. */
    fun closeText(a: String, b: String): Boolean {
        if (a == b) return true
        val re = Regex("-?\\d+(?:\\.\\d+)?(?:e[-+]?\\d+)?")
        if (re.replace(a, "#") != re.replace(b, "#")) return false
        val xs = re.findAll(a).map { it.value.toDouble() }.toList()
        val ys = re.findAll(b).map { it.value.toDouble() }.toList()
        return xs.size == ys.size && xs.indices.all { close(xs[it], ys[it]) }
    }
}
