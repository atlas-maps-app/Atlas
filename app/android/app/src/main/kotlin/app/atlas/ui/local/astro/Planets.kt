package app.atlas.ui.local.astro

import app.atlas.geo.Sun
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The planets in the order of the Sun, Earth included (the "EM Bary" row of JPL's table). */
enum class Planet(val key: String, private val e: DoubleArray) {
    MERCURY("mercury", doubleArrayOf(0.38709927, 0.20563593, 7.00497902, 252.25032350, 77.45779628, 48.33076593, 0.00000037, 0.00001906, -0.00594749, 149472.67411175, 0.16047689, -0.12534081)),
    VENUS("venus", doubleArrayOf(0.72333566, 0.00677672, 3.39467605, 181.97909950, 131.60246718, 76.67984255, 0.00000390, -0.00004107, -0.00078890, 58517.81538729, 0.00268329, -0.27769418)),
    EARTH("earth", doubleArrayOf(1.00000261, 0.01671123, -0.00001531, 100.46457166, 102.93768193, 0.0, 0.00000562, -0.00004392, -0.01294668, 35999.37244981, 0.32327364, 0.0)),
    MARS("mars", doubleArrayOf(1.52371034, 0.09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891, 0.00001847, 0.00007882, -0.00813131, 19140.30268499, 0.44441088, -0.29257343)),
    JUPITER("jupiter", doubleArrayOf(5.20288700, 0.04838624, 1.30439695, 34.39644051, 14.72847983, 100.47390909, -0.00011607, -0.00013253, -0.00183714, 3034.74612775, 0.21252668, 0.20469106)),
    SATURN("saturn", doubleArrayOf(9.53667594, 0.05386179, 2.48599187, 49.95424423, 92.59887831, 113.66242448, -0.00125060, -0.00050991, 0.00193609, 1222.49362201, -0.41897216, -0.28867794)),
    URANUS("uranus", doubleArrayOf(19.18916464, 0.04725744, 0.77263783, 313.23810451, 170.95427630, 74.01692503, -0.00196176, -0.00004397, -0.00242939, 428.48202785, 0.40805281, 0.04240589)),
    NEPTUNE("neptune", doubleArrayOf(30.06992276, 0.00859048, 1.77004347, -55.12002969, 44.96476227, 131.78422574, 0.00026291, 0.00005105, 0.00035372, 218.45945325, -0.32241464, -0.00508664));

    /** Semi-major axis in AU at J2000. */
    val semiMajorAxis: Double get() = e[0]

    /** The six elements (a, e, I, L, long. peri., long. node) at [t] centuries after J2000, and their rates. */
    internal fun element(i: Int, t: Double) = e[i] + e[i + 6] * t

    /** The planets seen from Earth, in the order of the sky. */
    companion object {
        val visibleFromEarth: List<Planet> = listOf(MERCURY, VENUS, MARS, JUPITER, SATURN, URANUS, NEPTUNE)
    }
}

/**
 * The planets from JPL's approximate Keplerian elements for 1800 to 2050 (E. M. Standish, "Keplerian Elements for
 * Approximate Positions of the Major Planets", public domain): heliocentric positions in the J2000 ecliptic good to
 * a few arcminutes (Jupiter and Saturn to a few tenths of a degree), which is what a sky chart and a "which planets
 * are up tonight" line need. Computed on the phone, offline.
 */
object Planets {
    /** The heliocentric ecliptic (J2000) position in AU at the Julian date [jdTdb]. */
    fun heliocentric(p: Planet, jdTdb: Double): Vec3 {
        val t = (jdTdb - 2451545.0) / 36525.0
        val a = p.element(0, t)
        val e = p.element(1, t)
        val inc = p.element(2, t) * Astro.DEG
        val l = p.element(3, t)
        val peri = p.element(4, t)
        val node = p.element(5, t)
        val m = Astro.norm180(l - peri) * Astro.DEG
        var ea = m + e * sin(m)
        repeat(30) {
            val d = (m - (ea - e * sin(ea))) / (1 - e * cos(ea))
            ea += d
            if (abs(d) < 1e-12) return@repeat
        }
        val xp = a * (cos(ea) - e)
        val yp = a * sqrt(1 - e * e) * sin(ea)
        val w = (peri - node) * Astro.DEG
        val om = node * Astro.DEG
        val cw = cos(w); val sw = sin(w); val co = cos(om); val so = sin(om); val ci = cos(inc); val si = sin(inc)
        return Vec3(
            (cw * co - sw * so * ci) * xp + (-sw * co - cw * so * ci) * yp,
            (cw * so + sw * co * ci) * xp + (-sw * so + cw * co * ci) * yp,
            (sw * si) * xp + (cw * si) * yp,
        )
    }

    fun heliocentric(p: Planet, t: Instant): Vec3 = heliocentric(p, Astro.julianDayTdb(t))

    /** Heliocentric ecliptic longitude in degrees, for drawing the orbit diagram. */
    fun longitude(p: Planet, t: Instant): Double {
        val v = heliocentric(p, t)
        return Astro.norm360(Math.toDegrees(Math.atan2(v.y, v.x)))
    }

    /** Geocentric right ascension and declination in degrees, of the mean equator and equinox of date. */
    fun geocentric(p: Planet, t: Instant): RaDec {
        val jd = Astro.julianDayTdb(t)
        val v = Astro.eclipticToEquatorial(heliocentric(p, jd) - heliocentric(Planet.EARTH, jd))
        return Astro.raDec(Astro.precess(v, jd))
    }

    /** Geocentric right ascension and declination in degrees in the J2000 frame (what Horizons calls ICRF), for checks. */
    fun geocentricJ2000(p: Planet, t: Instant): RaDec {
        val jd = Astro.julianDayTdb(t)
        return Astro.raDec(Astro.eclipticToEquatorial(heliocentric(p, jd) - heliocentric(Planet.EARTH, jd)))
    }

    /** Where [p] stands in the sky of an observer at [t]. */
    fun horizontal(p: Planet, t: Instant, latDeg: Double, lonDeg: Double): AltAz {
        val r = geocentric(p, t)
        return Astro.horizontal(r.raDeg, r.decDeg, Astro.gmst(t), latDeg, lonDeg)
    }
}

/** How a planet shows itself tonight. */
enum class NightView { ALL_NIGHT, EVENING, BEFORE_DAWN, LATE, NOT_UP }

/** One planet's night: when it is up in the dark, and its highest point. */
data class PlanetNight(val planet: Planet, val view: NightView, val from: Instant?, val to: Instant?, val bestAltitudeDeg: Double, val bestAzimuthDeg: Double) {
    /** Low in the sky (under 25 degrees at its best). */
    val low: Boolean get() = bestAltitudeDeg < 25.0
}

/** Which planets are up in the dark tonight, and where. */
object Tonight {
    /** Altitude from which a planet counts as up. */
    const val MIN_ALTITUDE = 3.0

    /** The night counts from sunset: the bright planets show in the afterglow. */
    const val DARK_SUN = -0.9

    /**
     * Samples the night that begins at the next sunset after [now] (or the one in progress) every ten minutes. A planet is
     * "all night" when it is up for most of the dark, "evening" when it sets in the first half, "before dawn" when it
     * rises in the second.
     */
    fun planets(now: Instant, latDeg: Double, lonDeg: Double, zone: ZoneId, list: List<Planet> = Planet.visibleFromEarth): List<PlanetNight> {
        val (start, end) = night(now, latDeg, lonDeg, zone) ?: return list.map { PlanetNight(it, NightView.NOT_UP, null, null, -90.0, 0.0) }
        val step = Duration.ofMinutes(10)
        val times = generateSequence(start) { it.plus(step) }.takeWhile { !it.isAfter(end) }.toList()
        val sun = times.map { Sun.position(it, latDeg, lonDeg).altitudeDeg }
        val dark = times.indices.filter { sun[it] < DARK_SUN }.ifEmpty { times.indices.toList() }
        val out = ArrayList<PlanetNight>()
        for (p in list) {
            val alts = times.map { Planets.horizontal(p, it, latDeg, lonDeg) }
            val up = dark.filter { alts[it].altitudeDeg >= MIN_ALTITUDE }
            if (up.isEmpty()) {
                val best = dark.maxByOrNull { alts[it].altitudeDeg } ?: 0
                out += PlanetNight(p, NightView.NOT_UP, null, null, alts[best].altitudeDeg, alts[best].azimuthDeg)
                continue
            }
            val best = up.maxByOrNull { alts[it].altitudeDeg }!!
            val first = up.first()
            val last = up.last()
            val firstDark = dark.first()
            val lastDark = dark.last()
            val mid = (firstDark + lastDark) / 2
            val view = when {
                up.size >= dark.size * 0.75 -> NightView.ALL_NIGHT
                first - firstDark <= 2 && last < mid + (lastDark - mid) / 2 -> NightView.EVENING
                lastDark - last <= 2 && first >= mid -> NightView.BEFORE_DAWN
                first - firstDark <= 2 -> NightView.EVENING
                lastDark - last <= 2 -> NightView.BEFORE_DAWN
                else -> NightView.LATE
            }
            out += PlanetNight(p, view, times[first], times[last], alts[best].altitudeDeg, alts[best].azimuthDeg)
        }
        return out
    }

    /** From sunset to the next sunrise: the night that is on now or comes next. */
    fun night(now: Instant, latDeg: Double, lonDeg: Double, zone: ZoneId): Pair<Instant, Instant>? {
        val today = LocalDate.ofInstant(now, zone)
        val tomorrow = today.plusDays(1)
        val t0 = Sun.times(today, latDeg, lonDeg, zone)
        val t1 = Sun.times(tomorrow, latDeg, lonDeg, zone)
        val setToday = t0.sunset?.toInstant()
        val riseToday = t0.sunrise?.toInstant()
        val setTomorrow = t1.sunset?.toInstant()
        val riseTomorrow = t1.sunrise?.toInstant()
        // Before today's sunrise the night began yesterday evening.
        if (riseToday != null && now.isBefore(riseToday)) {
            val y = Sun.times(today.minusDays(1), latDeg, lonDeg, zone).sunset?.toInstant() ?: return null
            return y to riseToday
        }
        if (setToday != null && now.isBefore(setToday)) return riseTomorrow?.let { setToday to it }
        // After sunset: the night in progress, or tomorrow's when it is over.
        if (setToday != null && riseTomorrow != null && now.isBefore(riseTomorrow)) return setToday to riseTomorrow
        // Tomorrow's night: from tomorrow's sunset to the sunrise after it (none in the polar summer and winter).
        if (setTomorrow == null) return null
        val after = Sun.times(tomorrow.plusDays(1), latDeg, lonDeg, zone).sunrise?.toInstant() ?: return null
        return setTomorrow to after
    }
}
