package app.atlas.geo

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the moon stands: compass azimuth, apparent altitude (parallax and refraction included), distance, and the
 * parallactic angle, which tilts the moon's disc as seen from here.
 */
data class MoonPosition(val azimuthDeg: Double, val altitudeDeg: Double, val distanceKm: Double, val parallacticAngleDeg: Double)

/**
 * The moon's face: [illumination] is the lit fraction 0..1; [phase] runs 0 new, 0.25 first quarter, 0.5 full,
 * 0.75 last quarter; [brightLimbDeg] is the position angle of the lit edge, from celestial north towards east.
 * Draw the lit side at `brightLimbDeg - parallacticAngleDeg` from the zenith, turning anticlockwise.
 */
data class MoonPhase(val illumination: Double, val phase: Double, val brightLimbDeg: Double) {
    val waxing: Boolean get() = phase < 0.5
}

/** Moonrise and moonset on a local day; either may be missing, or the moon may stay up or down all day. */
data class MoonTimes(val rise: ZonedDateTime?, val set: ZonedDateTime?, val alwaysUp: Boolean, val alwaysDown: Boolean)

/**
 * The moon by Meeus' truncated ELP-2000/82 series (Astronomical Algorithms, ch. 47–48): position to about
 * 10″ in longitude and 4″ in latitude, distance to a few kilometres, illumination to well under 1 %.
 */
object Moon {
    private const val RAD = PI / 180
    private const val EARTH_RADIUS_KM = 6378.14

    fun position(instant: Instant, lat: Double, lon: Double): MoonPosition {
        val e = equatorial(instant)
        val h = (siderealDegrees(instant) + lon - e.ra) * RAD
        val phi = lat * RAD
        val d = e.dec * RAD
        val altitude = asin((sin(phi) * sin(d) + cos(phi) * cos(d) * cos(h)).coerceIn(-1.0, 1.0)) / RAD
        val azimuth = Geo.normalize(atan2(sin(h), cos(h) * sin(phi) - tan(d) * cos(phi)) / RAD + 180)
        val parallax = asin(EARTH_RADIUS_KM / e.distance) / RAD
        val topocentric = altitude - parallax * cos(altitude * RAD)
        val q = atan2(sin(h), tan(phi) * cos(d) - sin(d) * cos(h)) / RAD
        return MoonPosition(azimuth, topocentric + Sun.refraction(topocentric), e.distance, q)
    }

    fun position(instant: Instant, at: LatLon): MoonPosition = position(instant, at.lat, at.lon)

    fun phase(instant: Instant): MoonPhase {
        val t = Sun.century(Sun.julianDay(instant))
        val a = Args(t)
        val i = 180 - a.d - 6.289 * sin(a.mp * RAD) + 2.100 * sin(a.m * RAD) - 1.274 * sin((2 * a.d - a.mp) * RAD) -
            0.658 * sin(2 * a.d * RAD) - 0.214 * sin(2 * a.mp * RAD) - 0.110 * sin(a.d * RAD)
        val illumination = (1 + cos(i * RAD)) / 2

        val moon = equatorial(instant)
        val sunLon = Sun.apparentLongitude(t)
        val phase = Geo.normalize(moon.lon - sunLon) / 360

        val eps = Sun.obliquity(t) * RAD
        val ls = sunLon * RAD
        val sunRa = atan2(cos(eps) * sin(ls), cos(ls))
        val sunDec = asin(sin(eps) * sin(ls))
        val ra = moon.ra * RAD
        val dec = moon.dec * RAD
        val chi = atan2(cos(sunDec) * sin(sunRa - ra), sin(sunDec) * cos(dec) - cos(sunDec) * sin(dec) * cos(sunRa - ra))
        return MoonPhase(illumination, phase, Geo.normalize(chi / RAD))
    }

    /** Moonrise and moonset during [date] in [zone], to within a few seconds of this model. */
    fun times(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): MoonTimes {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val step = 3600L
        var rise: Instant? = null
        var set: Instant? = null
        var t0 = start
        var f0 = aboveHorizon(t0, lat, lon)
        val firstAbove = f0 > 0
        while (t0 < end && (rise == null || set == null)) {
            val t1 = minOf(t0.plusSeconds(step), end)
            val f1 = aboveHorizon(t1, lat, lon)
            if (f0 <= 0 && f1 > 0 && rise == null) rise = bisect(t0, t1, lat, lon, true)
            if (f0 > 0 && f1 <= 0 && set == null) set = bisect(t0, t1, lat, lon, false)
            t0 = t1
            f0 = f1
        }
        val none = rise == null && set == null
        return MoonTimes(rise?.atZone(zone), set?.atZone(zone), none && firstAbove, none && !firstAbove)
    }

    fun times(date: LocalDate, at: LatLon, zone: ZoneId): MoonTimes = times(date, at.lat, at.lon, zone)

    /** Geocentric right ascension, declination, distance (km), ecliptic longitude and latitude, all in degrees but distance. */
    internal fun geocentric(instant: Instant): DoubleArray {
        val e = equatorial(instant)
        return doubleArrayOf(e.ra, e.dec, e.distance, e.lon, e.lat)
    }

    // Geocentric altitude above the moon's rising altitude (Meeus 15: 0.7275 × parallax − 34′).
    private fun aboveHorizon(instant: Instant, lat: Double, lon: Double): Double {
        val e = equatorial(instant)
        val h = (siderealDegrees(instant) + lon - e.ra) * RAD
        val phi = lat * RAD
        val d = e.dec * RAD
        val altitude = asin((sin(phi) * sin(d) + cos(phi) * cos(d) * cos(h)).coerceIn(-1.0, 1.0)) / RAD
        val parallax = asin(EARTH_RADIUS_KM / e.distance) / RAD
        return altitude - (0.7275 * parallax - 34.0 / 60)
    }

    private fun bisect(a0: Instant, b0: Instant, lat: Double, lon: Double, rising: Boolean): Instant {
        var a = a0
        var b = b0
        while (b.epochSecond - a.epochSecond > 2) {
            val mid = a.plusSeconds((b.epochSecond - a.epochSecond) / 2)
            val above = aboveHorizon(mid, lat, lon) > 0
            if (above == rising) b = mid else a = mid
        }
        return b
    }

    private class Equatorial(val ra: Double, val dec: Double, val distance: Double, val lon: Double, val lat: Double)

    private class Args(t: Double) {
        val lp = 218.3164477 + 481267.88123421 * t - 0.0015786 * t * t + t * t * t / 538841 - t * t * t * t / 65194000
        val d = 297.8501921 + 445267.1114034 * t - 0.0018819 * t * t + t * t * t / 545868 - t * t * t * t / 113065000
        val m = 357.5291092 + 35999.0502909 * t - 0.0001536 * t * t + t * t * t / 24490000
        val mp = 134.9633964 + 477198.8675055 * t + 0.0087414 * t * t + t * t * t / 69699 - t * t * t * t / 14712000
        val f = 93.2720950 + 483202.0175233 * t - 0.0036539 * t * t - t * t * t / 3526000 + t * t * t * t / 863310000
        val e = 1 - 0.002516 * t - 0.0000074 * t * t
    }

    private fun equatorial(instant: Instant): Equatorial {
        // The series runs on dynamical time: add ΔT to the clock's universal time.
        val t = Sun.century(Sun.julianDay(instant) + deltaT(instant) / 86400)
        val a = Args(t)
        var sl = 0.0
        var sr = 0.0
        for (k in LR_ARGS.indices step 4) {
            val mCount = LR_ARGS[k + 1]
            val arg = (LR_ARGS[k] * a.d + mCount * a.m + LR_ARGS[k + 2] * a.mp + LR_ARGS[k + 3] * a.f) * RAD
            val ef = when (mCount) { 0 -> 1.0; 1, -1 -> a.e; else -> a.e * a.e }
            sl += L_COEF[k / 4] * ef * sin(arg)
            sr += R_COEF[k / 4] * ef * cos(arg)
        }
        var sb = 0.0
        for (k in B_ARGS.indices step 4) {
            val mCount = B_ARGS[k + 1]
            val arg = (B_ARGS[k] * a.d + mCount * a.m + B_ARGS[k + 2] * a.mp + B_ARGS[k + 3] * a.f) * RAD
            val ef = when (mCount) { 0 -> 1.0; 1, -1 -> a.e; else -> a.e * a.e }
            sb += B_COEF[k / 4] * ef * sin(arg)
        }
        val a1 = (119.75 + 131.849 * t) * RAD
        val a2 = (53.09 + 479264.290 * t) * RAD
        val a3 = (313.45 + 481266.484 * t) * RAD
        sl += 3958 * sin(a1) + 1962 * sin((a.lp - a.f) * RAD) + 318 * sin(a2)
        sb += -2235 * sin(a.lp * RAD) + 382 * sin(a3) + 175 * sin(a1 - a.f * RAD) + 175 * sin(a1 + a.f * RAD) +
            127 * sin((a.lp - a.mp) * RAD) - 115 * sin((a.lp + a.mp) * RAD)

        val lambda = Geo.normalize(a.lp + sl / 1e6)
        val beta = sb / 1e6
        val distance = 385000.56 + sr / 1000

        val eps = Sun.obliquity(t) * RAD
        val l = lambda * RAD
        val b = beta * RAD
        val ra = atan2(sin(l) * cos(eps) - tan(b) * sin(eps), cos(l)) / RAD
        val dec = asin(sin(b) * cos(eps) + cos(b) * sin(eps) * sin(l)) / RAD
        return Equatorial(Geo.normalize(ra), dec, distance, lambda, beta)
    }

    /** ΔT, dynamical minus universal time, in seconds (Espenak and Meeus' polynomials). */
    internal fun deltaT(instant: Instant): Double {
        val y = 1970 + instant.epochSecond / 31_556_952.0
        val t = y - 2000
        return when {
            y < 1986 -> -20 + 32 * ((y - 1820) / 100).let { it * it }
            y < 2005 -> 63.86 + t * (0.3345 + t * (-0.060374 + t * (0.0017275 + t * (0.000651814 + t * 0.00002373599))))
            y < 2050 -> 62.92 + t * (0.32217 + t * 0.005589)
            y < 2150 -> -20 + 32 * ((y - 1820) / 100).let { it * it } - 0.5628 * (2150 - y)
            else -> -20 + 32 * ((y - 1820) / 100).let { it * it }
        }
    }

    // Greenwich mean sidereal time in degrees (Meeus 12.4).
    private fun siderealDegrees(instant: Instant): Double {
        val jd = Sun.julianDay(instant)
        val t = Sun.century(jd)
        return Geo.normalize(280.46061837 + 360.98564736629 * (jd - 2451545.0) + 0.000387933 * t * t - t * t * t / 38710000)
    }

    // Meeus table 47.A: multiples of D, M, M', F per term, with longitude (1e-6°) and distance (1e-3 km) coefficients.
    private val LR_ARGS = intArrayOf(
        0, 0, 1, 0, 2, 0, -1, 0, 2, 0, 0, 0, 0, 0, 2, 0,
        0, 1, 0, 0, 0, 0, 0, 2, 2, 0, -2, 0, 2, -1, -1, 0,
        2, 0, 1, 0, 2, -1, 0, 0, 0, 1, -1, 0, 1, 0, 0, 0,
        0, 1, 1, 0, 2, 0, 0, -2, 0, 0, 1, 2, 0, 0, 1, -2,
        4, 0, -1, 0, 0, 0, 3, 0, 4, 0, -2, 0, 2, 1, -1, 0,
        2, 1, 0, 0, 1, 0, -1, 0, 1, 1, 0, 0, 2, -1, 1, 0,
        2, 0, 2, 0, 4, 0, 0, 0, 2, 0, -3, 0, 0, 1, -2, 0,
        2, 0, -1, 2, 2, -1, -2, 0, 1, 0, 1, 0, 2, -2, 0, 0,
        0, 1, 2, 0, 0, 2, 0, 0, 2, -2, -1, 0, 2, 0, 1, -2,
        2, 0, 0, 2, 4, -1, -1, 0, 0, 0, 2, 2, 3, 0, -1, 0,
        2, 1, 1, 0, 4, -1, -2, 0, 0, 2, -1, 0, 2, 2, -1, 0,
        2, 1, -2, 0, 2, -1, 0, -2, 4, 0, 1, 0, 0, 0, 4, 0,
        4, -1, 0, 0, 1, 0, -2, 0, 2, 1, 0, -2, 0, 0, 2, -2,
        1, 1, 1, 0, 3, 0, -2, 0, 4, 0, -3, 0, 2, -1, 2, 0,
        0, 2, 1, 0, 1, 1, -1, 0, 2, 0, 3, 0, 2, 0, -1, -2,
    )
    private val L_COEF = doubleArrayOf(
        6288774.0, 1274027.0, 658314.0, 213618.0, -185116.0, -114332.0, 58793.0, 57066.0,
        53322.0, 45758.0, -40923.0, -34720.0, -30383.0, 15327.0, -12528.0, 10980.0,
        10675.0, 10034.0, 8548.0, -7888.0, -6766.0, -5163.0, 4987.0, 4036.0,
        3994.0, 3861.0, 3665.0, -2689.0, -2602.0, 2390.0, -2348.0, 2236.0,
        -2120.0, -2069.0, 2048.0, -1773.0, -1595.0, 1215.0, -1110.0, -892.0,
        -810.0, 759.0, -713.0, -700.0, 691.0, 596.0, 549.0, 537.0,
        520.0, -487.0, -399.0, -381.0, 351.0, -340.0, 330.0, 327.0,
        -323.0, 299.0, 294.0, 0.0,
    )
    private val R_COEF = doubleArrayOf(
        -20905355.0, -3699111.0, -2955968.0, -569925.0, 48888.0, -3149.0, 246158.0, -152138.0,
        -170733.0, -204586.0, -129620.0, 108743.0, 104755.0, 10321.0, 0.0, 79661.0,
        -34782.0, -23210.0, -21636.0, 24208.0, 30824.0, -8379.0, -16675.0, -12831.0,
        -10445.0, -11650.0, 14403.0, -7003.0, 0.0, 10056.0, 6322.0, -9884.0,
        5751.0, 0.0, -4950.0, 4130.0, 0.0, -3958.0, 0.0, 3258.0,
        2616.0, -1897.0, -2117.0, 2354.0, 0.0, 0.0, -1423.0, -1117.0,
        -1571.0, -1739.0, 0.0, -4421.0, 0.0, 0.0, 0.0, 0.0,
        1165.0, 0.0, 0.0, 8752.0,
    )

    // Meeus table 47.B: multiples of D, M, M', F per term, with latitude coefficients (1e-6°).
    private val B_ARGS = intArrayOf(
        0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, -1, 2, 0, 0, -1,
        2, 0, -1, 1, 2, 0, -1, -1, 2, 0, 0, 1, 0, 0, 2, 1,
        2, 0, 1, -1, 0, 0, 2, -1, 2, -1, 0, -1, 2, 0, -2, -1,
        2, 0, 1, 1, 2, 1, 0, -1, 2, -1, -1, 1, 2, -1, 0, 1,
        2, -1, -1, -1, 0, 1, -1, -1, 4, 0, -1, -1, 0, 1, 0, 1,
        0, 0, 0, 3, 0, 1, -1, 1, 1, 0, 0, 1, 0, 1, 1, 1,
        0, 1, 1, -1, 0, 1, 0, -1, 1, 0, 0, -1, 0, 0, 3, 1,
        4, 0, 0, -1, 4, 0, -1, 1, 0, 0, 1, -3, 4, 0, -2, 1,
        2, 0, 0, -3, 2, 0, 2, -1, 2, -1, 1, -1, 2, 0, -2, 1,
        0, 0, 3, -1, 2, 0, 2, 1, 2, 0, -3, -1, 2, 1, -1, 1,
        2, 1, 0, 1, 4, 0, 0, 1, 2, -1, 1, 1, 2, -2, 0, -1,
        0, 0, 1, 3, 2, 1, 1, -1, 1, 1, 0, -1, 1, 1, 0, 1,
        0, 1, -2, -1, 2, 1, -1, -1, 1, 0, 1, 1, 2, -1, -2, -1,
        0, 1, 2, 1, 4, 0, -2, -1, 4, -1, -1, -1, 1, 0, 1, -1,
        4, 0, 1, -1, 1, 0, -1, -1, 4, -1, 0, -1, 2, -2, 0, 1,
    )
    private val B_COEF = doubleArrayOf(
        5128122.0, 280602.0, 277693.0, 173237.0, 55413.0, 46271.0, 32573.0, 17198.0,
        9266.0, 8822.0, 8216.0, 4324.0, 4200.0, -3359.0, 2463.0, 2211.0,
        2065.0, -1870.0, 1828.0, -1794.0, -1749.0, -1565.0, -1491.0, -1475.0,
        -1410.0, -1344.0, -1335.0, 1107.0, 1021.0, 833.0, 777.0, 671.0,
        607.0, 596.0, 491.0, -451.0, 439.0, 422.0, 421.0, -366.0,
        -351.0, 331.0, 315.0, 302.0, -283.0, -229.0, 223.0, 223.0,
        -220.0, -220.0, -185.0, 181.0, -177.0, 176.0, 166.0, -164.0,
        132.0, -119.0, 115.0, 107.0,
    )
}
