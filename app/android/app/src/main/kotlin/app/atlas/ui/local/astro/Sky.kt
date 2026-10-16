package app.atlas.ui.local.astro

import java.time.Instant
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A point in space: three numbers, in whatever unit and frame the caller says. */
class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    val length: Double get() = sqrt(x * x + y * y + z * z)
}

/** Where something stands in the sky of a place: compass azimuth (clockwise from north) and altitude, in degrees. */
data class AltAz(val azimuthDeg: Double, val altitudeDeg: Double)

/** Right ascension and declination in degrees, with the distance in the unit of the source (AU for planets). */
data class RaDec(val raDeg: Double, val decDeg: Double, val distance: Double)

/** Times, sidereal time and the rotations between frames that the sky code shares. */
object Astro {
    const val DEG = PI / 180.0
    const val AU_KM = 149_597_870.7
    const val EARTH_RADIUS_KM = 6378.137
    private const val DELTA_T_SECONDS = 69.2

    /** Julian date (UT) of [t]. */
    fun julianDay(t: Instant): Double = (t.epochSecond + t.nano / 1e9) / 86400.0 + 2440587.5

    /** Julian date in barycentric dynamical time, which JPL's elements are in; the offset is 69 s in 2026. */
    fun julianDayTdb(t: Instant): Double = julianDay(t) + DELTA_T_SECONDS / 86400.0

    /** Greenwich mean sidereal time at [t], in radians. */
    fun gmst(t: Instant): Double = Sgp4.gmst(julianDay(t))

    /** Degrees brought into 0 until 360. */
    fun norm360(d: Double): Double = ((d % 360.0) + 360.0) % 360.0

    /** Degrees brought into minus 180 until 180. */
    fun norm180(d: Double): Double = norm360(d + 180.0) - 180.0

    /** Mean obliquity of the ecliptic at [jd], in degrees. */
    fun obliquity(jd: Double): Double {
        val t = (jd - 2451545.0) / 36525.0
        return 23.439291 - 0.0130042 * t - 1.64e-7 * t * t + 5.04e-7 * t * t * t
    }

    /** Ecliptic (J2000) to equatorial (J2000) coordinates. */
    fun eclipticToEquatorial(v: Vec3): Vec3 {
        val e = 23.43928 * DEG
        return Vec3(v.x, v.y * cos(e) - v.z * sin(e), v.y * sin(e) + v.z * cos(e))
    }

    /** J2000 equatorial coordinates carried to the mean equator and equinox of [jd] (IAU 1976 precession). */
    fun precess(v: Vec3, jd: Double): Vec3 {
        val t = (jd - 2451545.0) / 36525.0
        val arc = DEG / 3600.0
        val zeta = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) * arc
        val z = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) * arc
        val theta = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) * arc
        val cz = cos(zeta); val sz = sin(zeta); val cZ = cos(z); val sZ = sin(z); val ct = cos(theta); val st = sin(theta)
        val xx = cz * ct * cZ - sz * sZ
        val xy = -sz * ct * cZ - cz * sZ
        val xz = -st * cZ
        val yx = cz * ct * sZ + sz * cZ
        val yy = -sz * ct * sZ + cz * cZ
        val yz = -st * sZ
        val zx = cz * st
        val zy = -sz * st
        val zz = ct
        return Vec3(xx * v.x + xy * v.y + xz * v.z, yx * v.x + yy * v.y + yz * v.z, zx * v.x + zy * v.y + zz * v.z)
    }

    fun raDec(v: Vec3): RaDec {
        val r = v.length
        return RaDec(norm360(atan2(v.y, v.x) / DEG), asin(v.z / r) / DEG, r)
    }

    /** Altitude and azimuth of an equatorial direction (of date) for an observer, by sidereal time [gmstRad]. */
    fun horizontal(raDeg: Double, decDeg: Double, gmstRad: Double, latDeg: Double, lonDeg: Double): AltAz {
        val h = gmstRad + lonDeg * DEG - raDeg * DEG
        val phi = latDeg * DEG
        val d = decDeg * DEG
        val alt = asin((sin(phi) * sin(d) + cos(phi) * cos(d) * cos(h)).coerceIn(-1.0, 1.0)) / DEG
        val az = norm360(atan2(sin(h), cos(h) * sin(phi) - tan(d) * cos(phi)) / DEG + 180.0)
        return AltAz(az, alt)
    }

    /** The compass point for an azimuth: "N", "NE", "E" and so on. */
    fun compass8(azimuthDeg: Double): String =
        arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((norm360(azimuthDeg) + 22.5) / 45.0).toInt() % 8]

    /**
     * The Sun's geocentric position in the true equator and equinox of date, in km (Meeus, chapter 25: about 0.01 degree).
     * Good for eclipse shadows and the sub-solar point; the app's sunrise and sunset come from `:core` `Sun`.
     */
    fun sunEci(t: Instant): Vec3 {
        val n = julianDay(t) - 2451545.0
        val l = norm360(280.460 + 0.9856474 * n)
        val g = norm360(357.528 + 0.9856003 * n) * DEG
        val lambda = (l + 1.915 * sin(g) + 0.020 * sin(2 * g)) * DEG
        val r = (1.00014 - 0.01671 * cos(g) - 0.00014 * cos(2 * g)) * AU_KM
        val eps = (23.439 - 0.0000004 * n) * DEG
        return Vec3(r * cos(lambda), r * cos(eps) * sin(lambda), r * sin(eps) * sin(lambda))
    }

    /** Geodetic latitude and longitude (degrees) of a point of the Earth-fixed frame, in km. */
    fun geodetic(x: Double, y: Double, z: Double): Pair<Double, Double> {
        val a = EARTH_RADIUS_KM
        val f = 1 / 298.257223563
        val e2 = f * (2 - f)
        val p = hypot(x, y)
        var lat = atan2(z, p * (1 - e2))
        repeat(4) {
            val s = sin(lat)
            val n = a / sqrt(1 - e2 * s * s)
            lat = atan2(z + e2 * n * s, p)
        }
        return lat / DEG to atan2(y, x) / DEG
    }

    /** The Earth-fixed position (km) of a point of the TEME frame at sidereal time [gmstRad]. */
    fun temeToEcef(x: Double, y: Double, z: Double, gmstRad: Double): Vec3 {
        val c = cos(gmstRad)
        val s = sin(gmstRad)
        return Vec3(c * x + s * y, -s * x + c * y, z)
    }

    /** An observer on the ellipsoid, in the Earth-fixed frame (km). */
    fun observerEcef(latDeg: Double, lonDeg: Double, heightKm: Double = 0.0): Vec3 {
        val f = 1 / 298.257223563
        val e2 = f * (2 - f)
        val phi = latDeg * DEG
        val lam = lonDeg * DEG
        val n = EARTH_RADIUS_KM / sqrt(1 - e2 * sin(phi) * sin(phi))
        return Vec3((n + heightKm) * cos(phi) * cos(lam), (n + heightKm) * cos(phi) * sin(lam), (n * (1 - e2) + heightKm) * sin(phi))
    }

    /** Azimuth, elevation (degrees) and range (km) of an Earth-fixed [target] seen from an observer at [latDeg], [lonDeg]. */
    fun lookAngles(latDeg: Double, lonDeg: Double, observer: Vec3, target: Vec3): Triple<Double, Double, Double> {
        val d = target - observer
        val phi = latDeg * DEG
        val lam = lonDeg * DEG
        val east = -sin(lam) * d.x + cos(lam) * d.y
        val north = -sin(phi) * cos(lam) * d.x - sin(phi) * sin(lam) * d.y + cos(phi) * d.z
        val up = cos(phi) * cos(lam) * d.x + cos(phi) * sin(lam) * d.y + sin(phi) * d.z
        val range = d.length
        return Triple(norm360(atan2(east, north) / DEG), asin((up / range).coerceIn(-1.0, 1.0)) / DEG, range)
    }
}
