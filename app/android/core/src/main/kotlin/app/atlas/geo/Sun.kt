package app.atlas.geo

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Where the sun stands: compass azimuth (clockwise from north) and apparent altitude above the horizon. */
data class SunPosition(val azimuthDeg: Double, val altitudeDeg: Double) {
    /** Whether any of the sun's disc shows above a flat horizon. */
    val up: Boolean get() = altitudeDeg > -Sun.SEMI_DIAMETER
}

/** Ground offset of a shadow per metre of height: [east] and [north] in metres. */
data class ShadowVector(val east: Double, val north: Double) {
    val length: Double get() = sqrt(east * east + north * north)
}

/**
 * One day's sun in a zone. Times are null when the event does not happen that day, as in polar day or night.
 * Golden hour runs from 4° below to 6° above the horizon; blue hour from 6° below to 4° below.
 */
data class SunTimes(
    val date: LocalDate,
    val solarNoon: ZonedDateTime,
    val sunrise: ZonedDateTime?,
    val sunset: ZonedDateTime?,
    val civilDawn: ZonedDateTime?,
    val civilDusk: ZonedDateTime?,
    val nauticalDawn: ZonedDateTime?,
    val nauticalDusk: ZonedDateTime?,
    val astronomicalDawn: ZonedDateTime?,
    val astronomicalDusk: ZonedDateTime?,
    val goldenHourMorningEnd: ZonedDateTime?,
    val goldenHourEveningStart: ZonedDateTime?,
    val blueHourMorningEnd: ZonedDateTime?,
    val blueHourEveningStart: ZonedDateTime?,
    val noonAltitudeDeg: Double,
    val polar: Polar,
) {
    enum class Polar { NONE, DAY, NIGHT }

    /** Daylight from sunrise to sunset: 24 h in polar day, 0 in polar night. */
    val dayLength: Duration
        get() = when {
            sunrise != null && sunset != null -> Duration.between(sunrise, sunset)
            polar == Polar.DAY -> Duration.ofHours(24)
            else -> Duration.ZERO
        }
}

/**
 * The sun by the NOAA Solar Calculator (Meeus' algorithms): position within a fraction of a degree and event times
 * within a minute between ±72° latitude, with standard refraction.
 */
object Sun {
    /** The centre's true altitude when the upper limb meets the horizon, with standard refraction. */
    const val SUNRISE_ALTITUDE = -0.833
    /** The sun's apparent radius in degrees. */
    const val SEMI_DIAMETER = 0.2667
    const val CIVIL = -6.0
    const val NAUTICAL = -12.0
    const val ASTRONOMICAL = -18.0
    const val GOLDEN_HIGH = 6.0
    const val GOLDEN_LOW = -4.0

    private const val RAD = PI / 180

    fun position(instant: Instant, lat: Double, lon: Double): SunPosition {
        val geometric = horizontal(instant, lat, lon)
        return SunPosition(geometric.azimuthDeg, geometric.altitudeDeg + refraction(geometric.altitudeDeg))
    }

    // Azimuth and true (unrefracted) elevation of the sun's centre.
    private fun horizontal(instant: Instant, lat: Double, lon: Double): SunPosition {
        val jd = julianDay(instant)
        val t = century(jd)
        val decl = declination(t)
        val eqt = equationOfTime(t)
        val minutesUtc = ((instant.epochSecond % 86400 + 86400) % 86400 + instant.nano / 1e9) / 60.0
        val trueSolarTime = ((minutesUtc + eqt + 4 * lon) % 1440 + 1440) % 1440
        val hourAngle = trueSolarTime / 4 - 180

        val phi = lat * RAD
        val d = decl * RAD
        val h = hourAngle * RAD
        val cosZenith = (sin(phi) * sin(d) + cos(phi) * cos(d) * cos(h)).coerceIn(-1.0, 1.0)
        val elevation = 90 - acos(cosZenith) / RAD
        val azimuth = Geo.normalize(atan2(sin(h), cos(h) * sin(phi) - tan(d) * cos(phi)) / RAD + 180)
        return SunPosition(azimuth, elevation)
    }

    fun position(instant: Instant, at: LatLon): SunPosition = position(instant, at.lat, at.lon)

    /**
     * The shadow a vertical stick of 1 m casts: pointing away from the sun, 1 / tan(altitude) long.
     * Null when the sun is down.
     */
    fun shadowVector(altitudeDeg: Double, azimuthDeg: Double): ShadowVector? {
        if (altitudeDeg <= 0) return null
        val length = 1 / tan(altitudeDeg * RAD)
        val away = (azimuthDeg + 180) * RAD
        return ShadowVector(length * sin(away), length * cos(away))
    }

    fun shadowVector(p: SunPosition): ShadowVector? = shadowVector(p.altitudeDeg, p.azimuthDeg)

    /** Sunrise, sunset, the twilights, golden and blue hour for [date] at a place, in [zone]. */
    fun times(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): SunTimes {
        val noon = solarNoon(date, lat, lon, zone)
        val noonUtc = noon.toInstant()
        val noonAltitude = horizontal(noonUtc, lat, lon).altitudeDeg
        val midnightAltitude = horizontal(noonUtc.plusSeconds(43200), lat, lon).altitudeDeg
        fun at(alt: Double, rising: Boolean) = timeOfAltitude(noonUtc, lat, lon, alt, rising)?.atZone(zone)
        val rise = at(SUNRISE_ALTITUDE, true)
        val set = at(SUNRISE_ALTITUDE, false)
        val polar = when {
            rise != null || set != null -> SunTimes.Polar.NONE
            noonAltitude > SUNRISE_ALTITUDE && midnightAltitude > SUNRISE_ALTITUDE -> SunTimes.Polar.DAY
            noonAltitude <= SUNRISE_ALTITUDE -> SunTimes.Polar.NIGHT
            else -> SunTimes.Polar.NONE
        }
        return SunTimes(
            date = date,
            solarNoon = noon,
            sunrise = rise,
            sunset = set,
            civilDawn = at(CIVIL, true),
            civilDusk = at(CIVIL, false),
            nauticalDawn = at(NAUTICAL, true),
            nauticalDusk = at(NAUTICAL, false),
            astronomicalDawn = at(ASTRONOMICAL, true),
            astronomicalDusk = at(ASTRONOMICAL, false),
            goldenHourMorningEnd = at(GOLDEN_HIGH, true),
            goldenHourEveningStart = at(GOLDEN_HIGH, false),
            blueHourMorningEnd = at(GOLDEN_LOW, true),
            blueHourEveningStart = at(GOLDEN_LOW, false),
            noonAltitudeDeg = noonAltitude + refraction(noonAltitude),
            polar = polar,
        )
    }

    fun times(date: LocalDate, at: LatLon, zone: ZoneId): SunTimes = times(date, at.lat, at.lon, zone)

    /** The moment of local solar noon on [date] in [zone]. */
    fun solarNoon(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): ZonedDateTime {
        val reference = date.atTime(LocalTime.NOON).atZone(zone).toInstant()
        val utcDate = reference.atOffset(ZoneOffset.UTC).toLocalDate()
        val base = utcDate.toEpochDay() * 86400.0
        var minutes = 720 - 4 * lon
        repeat(3) { minutes = 720 - 4 * lon - equationOfTime(century(julianDayOfEpochSeconds(base + minutes * 60))) }
        var seconds = base + minutes * 60
        // Take the transit nearest the zone's clock noon, whatever the zone's offset from solar time.
        val ref = reference.epochSecond.toDouble()
        while (seconds - ref > 43200) seconds -= 86400
        while (ref - seconds > 43200) seconds += 86400
        return Instant.ofEpochMilli((seconds * 1000).roundToLong()).atZone(zone)
    }

    /**
     * When the sun's centre crosses [altitudeDeg], rising (morning) or setting (evening), around the transit at
     * [noon]. Null if it does not cross that altitude that day.
     */
    fun timeOfAltitude(noon: Instant, lat: Double, lon: Double, altitudeDeg: Double, rising: Boolean): Instant? {
        val noonSeconds = noon.epochSecond + noon.nano / 1e9
        val dayStart = floor(noonSeconds / 86400) * 86400
        var eventSeconds = noonSeconds
        var converged = true
        for (i in 0 until 4) {
            val t = century(julianDayOfEpochSeconds(eventSeconds))
            val hourAngle = hourAngle(lat, declination(t), altitudeDeg)
            if (hourAngle == null) { converged = false; break }
            val transitMinutes = 720 - 4 * lon - equationOfTime(t)
            // The transit this day belongs to, then the offset either side of it.
            var transit = dayStart + transitMinutes * 60
            while (transit - noonSeconds > 43200) transit -= 86400
            while (noonSeconds - transit > 43200) transit += 86400
            eventSeconds = transit + (if (rising) -1 else 1) * hourAngle * 240
        }
        if (converged) return Instant.ofEpochMilli((eventSeconds * 1000).roundToLong())
        // The sun only just reaches this altitude, near midnight, on the days polar day or night begins or ends: the
        // declination at noon says it never does, but by midnight it has moved enough. Between the transit and the
        // lower culmination on the event's side the altitude only falls (or, before noon, only rises): bisect there.
        val far = noonSeconds + (if (rising) -43200 else 43200)
        val atNoon = elevationAt(noonSeconds, lat, lon) - altitudeDeg
        val atFar = elevationAt(far, lat, lon) - altitudeDeg
        if ((atNoon > 0) == (atFar > 0)) return null
        var a = noonSeconds
        var b = far
        repeat(40) {
            val mid = (a + b) / 2
            if ((elevationAt(mid, lat, lon) - altitudeDeg > 0) == (atNoon > 0)) a = mid else b = mid
        }
        return Instant.ofEpochMilli(((a + b) / 2 * 1000).roundToLong())
    }

    private fun elevationAt(epochSeconds: Double, lat: Double, lon: Double): Double {
        val whole = floor(epochSeconds)
        return horizontal(Instant.ofEpochSecond(whole.toLong(), ((epochSeconds - whole) * 1e9).toLong()), lat, lon).altitudeDeg
    }

    /** Solar declination in degrees at [instant]. */
    fun declination(instant: Instant): Double = declination(century(julianDay(instant)))

    /** The equation of time in minutes at [instant]: apparent minus mean solar time. */
    fun equationOfTime(instant: Instant): Double = equationOfTime(century(julianDay(instant)))

    /** The sun's apparent ecliptic longitude in degrees, for the moon's phase. */
    internal fun apparentLongitude(t: Double): Double {
        val omega = 125.04 - 1934.136 * t
        return trueLongitude(t) - 0.00569 - 0.00478 * sin(omega * RAD)
    }

    internal fun obliquity(t: Double): Double {
        val e0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
        return e0 + 0.00256 * cos((125.04 - 1934.136 * t) * RAD)
    }

    internal fun julianDay(instant: Instant): Double = julianDayOfEpochSeconds(instant.epochSecond + instant.nano / 1e9)

    internal fun julianDayOfEpochSeconds(s: Double): Double = s / 86400.0 + 2440587.5

    internal fun century(jd: Double): Double = (jd - 2451545.0) / 36525.0

    private fun meanLongitude(t: Double): Double = ((280.46646 + t * (36000.76983 + t * 0.0003032)) % 360 + 360) % 360

    private fun meanAnomaly(t: Double): Double = 357.52911 + t * (35999.05029 - 0.0001537 * t)

    private fun eccentricity(t: Double): Double = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

    private fun equationOfCentre(t: Double): Double {
        val m = meanAnomaly(t) * RAD
        return sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * m) * (0.019993 - 0.000101 * t) +
            sin(3 * m) * 0.000289
    }

    private fun trueLongitude(t: Double): Double = meanLongitude(t) + equationOfCentre(t)

    internal fun declination(t: Double): Double =
        asin(sin(obliquity(t) * RAD) * sin(apparentLongitude(t) * RAD)) / RAD

    internal fun equationOfTime(t: Double): Double {
        val eps = obliquity(t) * RAD
        val l0 = meanLongitude(t) * RAD
        val e = eccentricity(t)
        val m = meanAnomaly(t) * RAD
        val y = tan(eps / 2).let { it * it }
        val v = y * sin(2 * l0) - 2 * e * sin(m) + 4 * e * y * sin(m) * cos(2 * l0) -
            0.5 * y * y * sin(4 * l0) - 1.25 * e * e * sin(2 * m)
        return 4 * v / RAD
    }

    // Hour angle in degrees at which the sun reaches altitude h, or null if it never does that day.
    private fun hourAngle(lat: Double, decl: Double, h: Double): Double? {
        val phi = lat * RAD
        val d = decl * RAD
        val c = (sin(h * RAD) - sin(phi) * sin(d)) / (cos(phi) * cos(d))
        if (c > 1 || c < -1 || c.isNaN()) return null
        return acos(c) / RAD
    }

    // NOAA's atmospheric refraction in degrees for a true elevation.
    internal fun refraction(elevation: Double): Double {
        if (elevation > 85) return 0.0
        val te = tan(elevation * RAD)
        val arcsec = when {
            elevation > 5 -> 58.1 / te - 0.07 / (te * te * te) + 0.000086 / (te * te * te * te * te)
            elevation > -0.575 -> 1735 + elevation * (-518.2 + elevation * (103.4 + elevation * (-12.79 + elevation * 0.711)))
            else -> -20.774 / te
        }
        return arcsec / 3600
    }
}
