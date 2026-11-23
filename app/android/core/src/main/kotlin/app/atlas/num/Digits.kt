package app.atlas.num

/**
 * Digits as Atlas reads numbers in text (the reviewer, 17 November, finding 12). Android's regular expressions are
 * ICU's, whose `\d` is any script's decimal digit, where the JVM's is 0 to 9 alone; `toDouble` reads 0 to 9 alone on
 * both. So a number typed on an Arabic, Persian or Devanagari keyboard ("capacity>٥", "٥٢٫٣٧, ٤٫٨٩") passed a
 * pattern on the phone and then threw, and the same text read as no number on the JVM. Text is read with its digits
 * made plain first: the same on both, and as its writer meant it.
 */
object Digits {
    /** The Arabic decimal separator ("٥٫٥"), read as a point. */
    private const val ARABIC_DECIMAL = 0x066B

    /** [s] with every decimal digit of any script, in the supplementary planes too, as 0 to 9, and "٫" as ".". */
    fun ascii(s: String): String {
        if (s.all { it < '\u0080' }) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            when {
                cp < 0x80 -> out.append(cp.toChar())
                Character.isDigit(cp) -> out.append('0' + Character.digit(cp, 10))
                cp == ARABIC_DECIMAL -> out.append('.')
                else -> out.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return out.toString()
    }
}
