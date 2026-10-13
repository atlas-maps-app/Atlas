package app.atlas.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/** A UTM position: zone 1..60, latitude band letter (C..X), easting and northing in metres. */
data class UtmCoord(val zone: Int, val band: Char, val easting: Double, val northing: Double) {
    val north: Boolean get() = band >= 'N'
}

/**
 * Universal Transverse Mercator on WGS84, with Karney's 6th-order Krüger series (accurate to a few nanometres
 * within a zone and still sub-millimetre well outside it). Covers 80°S to 84°N, with the Norway and Svalbard zones.
 */
object Utm {
    private const val A = 6_378_137.0
    private const val F = 1 / 298.257223563
    private const val K0 = 0.9996
    private const val FALSE_EASTING = 500_000.0
    private const val FALSE_NORTHING = 10_000_000.0
    const val BANDS = "CDEFGHJKLMNPQRSTUVWXX"

    private val e = sqrt(F * (2 - F))
    private val n = F / (2 - F)
    private val bigA: Double
    private val alpha: DoubleArray
    private val beta: DoubleArray

    init {
        val n2 = n * n; val n3 = n2 * n; val n4 = n3 * n; val n5 = n4 * n; val n6 = n5 * n
        bigA = A / (1 + n) * (1 + n2 / 4 + n4 / 64 + n6 / 256)
        alpha = doubleArrayOf(
            n / 2 - 2.0 / 3 * n2 + 5.0 / 16 * n3 + 41.0 / 180 * n4 - 127.0 / 288 * n5 + 7891.0 / 37800 * n6,
            13.0 / 48 * n2 - 3.0 / 5 * n3 + 557.0 / 1440 * n4 + 281.0 / 630 * n5 - 1983433.0 / 1935360 * n6,
            61.0 / 240 * n3 - 103.0 / 140 * n4 + 15061.0 / 26880 * n5 + 167603.0 / 181440 * n6,
            49561.0 / 161280 * n4 - 179.0 / 168 * n5 + 6601661.0 / 7257600 * n6,
            34729.0 / 80640 * n5 - 3418889.0 / 1995840 * n6,
            212378941.0 / 319334400 * n6,
        )
        beta = doubleArrayOf(
            n / 2 - 2.0 / 3 * n2 + 37.0 / 96 * n3 - 1.0 / 360 * n4 - 81.0 / 512 * n5 + 96199.0 / 604800 * n6,
            1.0 / 48 * n2 + 1.0 / 15 * n3 - 437.0 / 1440 * n4 + 46.0 / 105 * n5 - 1118711.0 / 3870720 * n6,
            17.0 / 480 * n3 - 37.0 / 840 * n4 - 209.0 / 4480 * n5 + 5569.0 / 90720 * n6,
            4397.0 / 161280 * n4 - 11.0 / 504 * n5 - 830251.0 / 7257600 * n6,
            4583.0 / 161280 * n5 - 108847.0 / 3991680 * n6,
            20648693.0 / 638668800 * n6,
        )
    }

    /** The latitude band letter for [lat], or null outside 80°S..84°N. */
    fun band(lat: Double): Char? {
        if (lat < -80 || lat > 84) return null
        return BANDS[floor(lat / 8 + 10).toInt().coerceIn(0, 20)]
    }

    /** The zone for a point, with the Norway (32V) and Svalbard (31X–37X) exceptions. */
    fun zone(lat: Double, lon: Double): Int {
        var z = floor((lon + 180) / 6).toInt() + 1
        if (z > 60) z -= 60
        if (z < 1) z += 60
        val mb = floor(lat / 8 + 10).toInt()
        if (mb == 17 && z == 31 && lon >= 3) z++ // band V: 32V extends west over Norway
        if (lat >= 72 && lat <= 84) {
            if (z == 32 && lon < 9) z--
            else if (z == 32) z++
            if (z == 34 && lon < 21) z--
            else if (z == 34) z++
            if (z == 36 && lon < 33) z--
            else if (z == 36) z++
        }
        return z
    }

    /** UTM in the point's own zone, or null outside 80°S..84°N (the polar regions use UPS). */
    fun fromLatLon(p: LatLon): UtmCoord? {
        val b = band(p.lat) ?: return null
        return fromLatLon(p, zone(p.lat, p.lon), b)
    }

    /** UTM forced into [zone], as when a grid continues across a zone edge. */
    fun fromLatLon(p: LatLon, zone: Int, band: Char = band(p.lat) ?: 'N'): UtmCoord {
        val lambda0 = ((zone - 1) * 6 - 180 + 3) * PI / 180
        val phi = p.lat * PI / 180
        val lambda = p.lon * PI / 180 - lambda0

        val cosL = cos(lambda); val sinL = sin(lambda)
        val tau = tan(phi)
        val sigma = sinh(e * atanh(e * tau / sqrt(1 + tau * tau)))
        val tauP = tau * sqrt(1 + sigma * sigma) - sigma * sqrt(1 + tau * tau)
        val xiP = atan2(tauP, cosL)
        val etaP = asinh(sinL / sqrt(tauP * tauP + cosL * cosL))

        var xi = xiP
        var eta = etaP
        for (j in 1..6) {
            xi += alpha[j - 1] * sin(2 * j * xiP) * cosh(2 * j * etaP)
            eta += alpha[j - 1] * cos(2 * j * xiP) * sinh(2 * j * etaP)
        }
        val x = K0 * bigA * eta
        var y = K0 * bigA * xi
        if (p.lat < 0) y += FALSE_NORTHING
        return UtmCoord(zone, band, x + FALSE_EASTING, y)
    }

    /** The point at a UTM position; [north] chooses the hemisphere. */
    fun toLatLon(zone: Int, north: Boolean, easting: Double, northing: Double): LatLon {
        val x = easting - FALSE_EASTING
        val y = if (north) northing else northing - FALSE_NORTHING
        val eta = x / (K0 * bigA)
        val xi = y / (K0 * bigA)

        var xiP = xi
        var etaP = eta
        for (j in 1..6) {
            xiP -= beta[j - 1] * sin(2 * j * xi) * cosh(2 * j * eta)
            etaP -= beta[j - 1] * cos(2 * j * xi) * sinh(2 * j * eta)
        }
        val sinhEtaP = sinh(etaP)
        val sinXiP = sin(xiP)
        val cosXiP = cos(xiP)
        val tauP = sinXiP / sqrt(sinhEtaP * sinhEtaP + cosXiP * cosXiP)

        // Newton–Raphson for tau from tau'.
        var tau = tauP
        var iterations = 0
        do {
            val sigma = sinh(e * atanh(e * tau / sqrt(1 + tau * tau)))
            val tauIP = tau * sqrt(1 + sigma * sigma) - sigma * sqrt(1 + tau * tau)
            val delta = (tauP - tauIP) / sqrt(1 + tauIP * tauIP) *
                (1 + (1 - e * e) * tau * tau) / ((1 - e * e) * sqrt(1 + tau * tau))
            tau += delta
        } while (abs(delta) > 1e-12 && ++iterations < 20)

        val phi = atan(tau)
        val lambda = atan2(sinhEtaP, cosXiP)
        val lambda0 = ((zone - 1) * 6 - 180 + 3) * PI / 180
        val lon = (lambda + lambda0) * 180 / PI
        return LatLon(phi * 180 / PI, ((lon + 540) % 360) - 180)
    }

    fun toLatLon(c: UtmCoord): LatLon = toLatLon(c.zone, c.north, c.easting, c.northing)
}

/**
 * The Military Grid Reference System: UTM with the 100 km square named by two letters, as in "31U FU 28472 03466".
 * References name the south-west corner of a square of 1 m to 100 km, depending on the digits.
 */
object Mgrs {
    private val columnSets = arrayOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
    private val rowSets = arrayOf("ABCDEFGHJKLMNPQRSTUV", "FGHJKLMNPQRSTUVABCDE")

    /** The MGRS reference with [digits] per axis (1..5; 5 is 1 m), or null outside UTM's latitudes. */
    fun format(p: LatLon, digits: Int = 5, spaced: Boolean = true): String? {
        require(digits in 0..5) { "MGRS digits must be 0..5" }
        val u = Utm.fromLatLon(p) ?: return null
        val col = floor(u.easting / 100_000).toInt()
        val row = floor(u.northing / 100_000).toInt() % 20
        val colLetter = columnSets[(u.zone - 1) % 3][col - 1]
        val rowLetter = rowSets[(u.zone - 1) % 2][row]
        // Truncate, not round: a grid reference names the square the point is in.
        var div = 1.0
        repeat(5 - digits) { div *= 10 }
        val e = floor((u.easting % 100_000) / div).toLong()
        val n = floor((u.northing % 100_000) / div).toLong()
        val eStr = e.toString().padStart(digits, '0').takeLast(digits)
        val nStr = n.toString().padStart(digits, '0').takeLast(digits)
        val zone = u.zone.toString().padStart(2, '0')
        return if (spaced) {
            "$zone${u.band} $colLetter$rowLetter${if (digits > 0) " $eStr $nStr" else ""}"
        } else {
            "$zone${u.band}$colLetter$rowLetter$eStr$nStr"
        }
    }

    /**
     * Parses "31U FU 28472 03466", "31UFU2847203466" and shorter references, returning the centre of the square
     * they name (so a 1 km reference lands mid-square). Null if it is not valid MGRS.
     */
    fun parse(text: String): LatLon? {
        val m = PATTERN.matchEntire(text.trim().uppercase()) ?: return null
        val zone = m.groupValues[1].toInt()
        if (zone !in 1..60) return null
        val band = m.groupValues[2][0]
        val colLetter = m.groupValues[3][0]
        val rowLetter = m.groupValues[3][1]
        val digits = (m.groupValues[4] + m.groupValues[5]).filter { it.isDigit() }
        if (digits.length % 2 != 0 || digits.length > 10) return null
        val half = digits.length / 2
        val scale = when (half) { 0 -> 100_000.0; 1 -> 10_000.0; 2 -> 1000.0; 3 -> 100.0; 4 -> 10.0; else -> 1.0 }
        val e = if (half == 0) 0.0 else digits.substring(0, half).toLong() * scale
        val n = if (half == 0) 0.0 else digits.substring(half).toLong() * scale

        val col = columnSets[(zone - 1) % 3].indexOf(colLetter)
        val row = rowSets[(zone - 1) % 2].indexOf(rowLetter)
        if (col < 0 || row < 0) return null
        val easting = (col + 1) * 100_000.0 + e + scale / 2
        var northing = row * 100_000.0 + n + scale / 2

        // The row letters repeat every 2000 km: take the cycle that falls in the band, allowing a little slack below
        // its southern edge for squares that straddle it.
        val bandIndex = Utm.BANDS.indexOf(band)
        if (bandIndex < 0) return null
        val north = band >= 'N'
        val bandSouth = -80.0 + bandIndex * 8
        val bandFloor = Utm.fromLatLon(LatLon(bandSouth, (zone - 1) * 6 - 180 + 3.0), zone, band).northing
        val lowest = bandFloor - 100_000
        while (northing < lowest) northing += 2_000_000
        while (northing - 2_000_000 >= lowest) northing -= 2_000_000
        return Utm.toLatLon(zone, north, easting, northing)
    }

    private val PATTERN = Regex("""^(\d{1,2})\s*([C-HJ-NP-X])\s*([A-HJ-NP-Z]{2})\s*(\d*)\s*(\d*)$""")
}
