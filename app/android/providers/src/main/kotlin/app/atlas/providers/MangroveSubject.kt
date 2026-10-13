package app.atlas.providers

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import java.math.BigDecimal
import java.net.URLDecoder

/**
 * A place as Mangrove names it: `geo:<lat>,<lon>?q=<name>&u=<metres>`, written as MapComplete and Mangrove's own
 * client write it (the name encoded like JavaScript's encodeURIComponent, coordinates as JavaScript prints numbers).
 */
data class GeoSubject(val at: LatLon, val name: String, val uncertainty: Int = UNCERTAINTY) {
    /** The subject URI. */
    override fun toString(): String = "geo:${coord(at.lat)},${coord(at.lon)}?q=${encodeComponent(name.trim().replace(SPACES, " "))}&u=$uncertainty"

    /**
     * True when two subjects are the same place by Mangrove's rule: the same name ignoring case, punctuation and accents,
     * and no further apart than the sum of their uncertainties.
     */
    fun sameAs(other: GeoSubject): Boolean =
        NameMatch.normalise(name) == NameMatch.normalise(other.name) && Geo.distance(at, other.at) <= uncertainty + other.uncertainty

    companion object {
        /** How far from the mapped point a review still counts for the place, in metres; Atlas writes 30 as MapComplete does. */
        const val UNCERTAINTY = 30

        /** The subject for a named place; null without a name. */
        fun of(place: Place, uncertainty: Int = UNCERTAINTY): GeoSubject? = place.name()?.takeIf { it.isNotBlank() }?.let { GeoSubject(place.location, it, uncertainty) }

        /** Parses a `geo:` subject; null for other schemes or malformed ones. */
        fun parse(sub: String): GeoSubject? {
            if (!sub.startsWith("geo:")) return null
            val body = sub.substring(4)
            val coords = body.substringBefore('?').split(',')
            if (coords.size < 2) return null
            val lat = coords[0].toDoubleOrNull() ?: return null
            val lon = coords[1].substringBefore(';').toDoubleOrNull() ?: return null
            val params = body.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
                it.substringBefore('=') to decode(it.substringAfter('=', ""))
            }
            val name = params["q"] ?: return null
            val u = params["u"]?.toDoubleOrNull()?.toInt() ?: UNCERTAINTY
            return GeoSubject(LatLon(lat, lon), name, u)
        }

        /** Up to 7 decimals with no trailing zeros, as JavaScript prints such a number. */
        internal fun coord(x: Double): String = BigDecimal(x).setScale(7, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

        /** JavaScript's encodeURIComponent: everything but A–Z a–z 0–9 - _ . ! ~ * ' ( ) is percent-encoded as UTF-8. */
        fun encodeComponent(s: String): String {
            val out = StringBuilder(s.length + 16)
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xff).toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-_.!~*'()") out.append(c)
                else out.append('%').append(HEX[(b.toInt() shr 4) and 0xf]).append(HEX[b.toInt() and 0xf])
            }
            return out.toString()
        }

        private fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

        private const val HEX = "0123456789ABCDEF"

        // Any run of Unicode white space, as Python's str.split() sees it: a no-break space in a name is a space.
        private val SPACES = Regex("""(?U)\s+""")
    }
}
