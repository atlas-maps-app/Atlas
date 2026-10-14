package app.atlas.geo

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Writes a position in the forms people and other apps use. Decimal points are always '.', as the forms define. */
object CoordinateFormat {
    enum class Style { DECIMAL, DMS, DM, UTM, MGRS, PLUS_CODE }

    /** [p] in [style]; the grid styles fall back to decimal near the poles, where UTM does not reach. */
    fun format(p: LatLon, style: Style): String = when (style) {
        Style.DECIMAL -> decimal(p)
        Style.DMS -> dms(p)
        Style.DM -> dm(p)
        Style.UTM -> utm(p) ?: decimal(p)
        Style.MGRS -> mgrs(p) ?: decimal(p)
        Style.PLUS_CODE -> plusCode(p)
    }

    /** "52.37452, 4.88377": [digits] decimals (5 is about a metre). */
    fun decimal(p: LatLon, digits: Int = 5): String =
        String.format(Locale.ROOT, "%.${digits}f, %.${digits}f", p.lat, p.lon)

    /** "52°22′28.3″N 4°53′01.6″E": degrees, minutes and seconds with [secondDigits] decimals. */
    fun dms(p: LatLon, secondDigits: Int = 1): String =
        "${dmsPart(p.lat, secondDigits, 'N', 'S')} ${dmsPart(p.lon, secondDigits, 'E', 'W')}"

    /** "52°22.471′N 4°53.026′E": degrees and decimal minutes, the nautical form. */
    fun dm(p: LatLon, minuteDigits: Int = 3): String =
        "${dmPart(p.lat, minuteDigits, 'N', 'S')} ${dmPart(p.lon, minuteDigits, 'E', 'W')}"

    /** "31U 628472 5803466", rounded to the metre; null outside 80°S..84°N. */
    fun utm(p: LatLon): String? {
        val u = Utm.fromLatLon(p) ?: return null
        return "${u.zone}${u.band} ${u.easting.roundToLong()} ${u.northing.roundToLong()}"
    }

    /** "31U FU 28472 03466" with [digits] per axis; null outside 80°S..84°N. */
    fun mgrs(p: LatLon, digits: Int = 5): String? = Mgrs.format(p, digits)

    /** "9F469VFP+R4" ([length] 10 is about 14 m; 11 about 3 m). */
    fun plusCode(p: LatLon, length: Int = PlusCode.NORMAL_LENGTH): String = PlusCode.encode(p, length)

    /** The plus code shortened against a nearby [reference], e.g. "VFP+R4"; the full code if it cannot be shortened. */
    fun plusCodeNear(p: LatLon, reference: LatLon): String {
        val full = PlusCode.encode(p)
        return try { PlusCode.shorten(full, reference.lat, reference.lon) } catch (_: IllegalArgumentException) { full }
    }

    /** "geo:52.37452,4.88377" (RFC 5870), for sharing with other apps. */
    fun geoUri(p: LatLon, digits: Int = 6): String =
        String.format(Locale.ROOT, "geo:%.${digits}f,%.${digits}f", p.lat, p.lon)

    private fun dmsPart(value: Double, digits: Int, pos: Char, neg: Char): String {
        // Round once, in units of the last digit, so 59.95″ carries into the minutes.
        var scale = 1L
        repeat(digits) { scale *= 10 }
        val total = (abs(value) * 3600 * scale).roundToLong()
        val deg = total / (3600 * scale)
        val min = total / (60 * scale) % 60
        val sec = total % (60 * scale)
        val secText = if (digits == 0) sec.toString().padStart(2, '0')
        else "${(sec / scale).toString().padStart(2, '0')}.${(sec % scale).toString().padStart(digits, '0')}"
        return "$deg°${min.toString().padStart(2, '0')}′$secText″${if (value < 0 && total > 0) neg else pos}"
    }

    private fun dmPart(value: Double, digits: Int, pos: Char, neg: Char): String {
        var scale = 1L
        repeat(digits) { scale *= 10 }
        val total = (abs(value) * 60 * scale).roundToLong()
        val deg = total / (60 * scale)
        val min = total % (60 * scale)
        val minText = if (digits == 0) min.toString().padStart(2, '0')
        else "${(min / scale).toString().padStart(2, '0')}.${(min % scale).toString().padStart(digits, '0')}"
        return "$deg°$minText′${if (value < 0 && total > 0) neg else pos}"
    }
}
