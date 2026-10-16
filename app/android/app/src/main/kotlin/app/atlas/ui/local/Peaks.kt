package app.atlas.ui.local

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Sun
import app.atlas.model.Place
import app.atlas.places.Localise
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.tan

/** A named peak from the map: where it is from the viewer, and its mapped height (`ele`), if any. */
class Peak(val place: Place, val name: String, val eleM: Double?, val distanceM: Double, val bearing: Double)

/** "What's that peak?": the peaks round a point as angles above the horizon, the way the compass tape lays them out. */
object Peaks {
    /** Peaks farther than this are not asked for, metres. */
    const val RADIUS_M = 25_000.0

    /** Horizontal field of view of the board's tape and picture, degrees. */
    const val FOV = 66.5

    /** The Earth's radius, metres, and the share of its curve that refraction gives back. */
    private const val EARTH_R = 6_371_000.0
    private const val REFRACTION = 0.13

    /** The height in a tag: "2052", "1,234.5", "712 m", "2.052 m;2050"; null when it is not one number. */
    fun parseEle(text: String?): Double? {
        val t = text?.trim()?.substringBefore(';')?.removeSuffix("m")?.removeSuffix("M")?.trim() ?: return null
        if (t.isEmpty()) return null
        // "1,234.5" is a thousands comma; "1234,5" a decimal one.
        val normal = if (',' in t && '.' in t) t.replace(",", "") else if (Regex("""^\d+,\d{1,2}$""").matches(t)) t.replace(',', '.') else t.replace(",", "")
        return normal.toDoubleOrNull()?.takeIf { it > -500 && it < 9000 }
    }

    /** The named peaks of [places] seen from [at], nearest first. */
    fun of(at: LatLon, places: List<Place>, lang: String?): List<Peak> =
        places.filter { it.tags["natural"] == "peak" }.mapNotNull { p ->
            val name = Localise.name(p.tags, lang) ?: return@mapNotNull null
            Peak(p, name, parseEle(p.tags["ele"]), Geo.distance(at, p.location), Geo.bearing(at, p.location))
        }.distinctBy { it.place.key }.sortedBy { it.distanceM }

    /**
     * How far above the horizon [p]'s top stands for an eye at [eyeM] metres, degrees: the height difference over the distance, less
     * what the curve of the Earth takes off (net of refraction); null when the peak has no mapped height.
     */
    fun angle(p: Peak, eyeM: Double): Double? {
        val e = p.eleM ?: return null
        val drop = p.distanceM * p.distanceM / (2 * EARTH_R) * (1 - REFRACTION * 2)
        return Math.toDegrees(atan2(e - eyeM - drop, p.distanceM))
    }

    /** Where, from the left of a [width] wide picture, a bearing falls when the picture faces [heading]; null outside it. */
    fun x(bearing: Double, heading: Double, width: Float): Float? {
        val rel = Geo.turnAngle(heading, bearing)
        if (abs(rel) > FOV / 2) return null
        return width / 2 + (width / FOV * rel).toFloat()
    }

    /** The peaks in view along [heading] that have a height, the most striking (the highest angle) first. */
    fun inView(peaks: List<Peak>, heading: Double, eyeM: Double): List<Pair<Peak, Double>> =
        peaks.mapNotNull { p -> angle(p, eyeM)?.takeIf { it > -2.0 && Geo.bearingDelta(heading, p.bearing) <= FOV / 2 }?.let { p to it } }.sortedByDescending { it.second }

    /** Tilting the phone up: the degrees that put the striking peaks (the fifth highest in view) where the board puts them, 0 to 35. */
    fun pitch(inView: List<Pair<Peak, Double>>, focal: Float, horizonY: Float, wantY: Float): Double {
        if (inView.isEmpty()) return 0.0
        val reference = inView[minOf(4, inView.size - 1)].second.coerceAtLeast(0.0)
        val atWant = Math.toDegrees(atan2((horizonY - wantY).toDouble(), focal.toDouble()))
        return (reference - atWant).coerceIn(0.0, 35.0)
    }

    /** The screen row of an angle above the horizon with the camera tilted up by [pitch]. */
    fun y(angle: Double, pitch: Double, focal: Float, horizonY: Float): Float =
        horizonY - focal * (tan(Math.toRadians(angle)) - tan(Math.toRadians(pitch))).toFloat()

    /** The sun going behind a peak: which, and when the sun's edge drops behind it. */
    class SunBehind(val peak: Peak, val at: java.time.ZonedDateTime)

    /**
     * The first peak the evening sun sets behind: the one whose bearing is within [tolerance] degrees of where the sun stands when it
     * comes down to that peak's top. Looks at today's sunset, else tomorrow's when it has gone.
     */
    fun sunBehind(at: LatLon, now: Instant, zone: ZoneId, peaks: List<Peak>, eyeM: Double, tolerance: Double = 2.5): SunBehind? {
        val day = LocalDate.ofInstant(now, zone)
        var times = Sun.times(day, at, zone)
        if (times.sunset?.toInstant()?.let { it < now } != false) times = Sun.times(day.plusDays(1), at, zone)
        val noon = times.solarNoon.toInstant()
        val set = times.sunset?.toInstant() ?: return null
        var best: SunBehind? = null
        var bestGap = Double.MAX_VALUE
        for (p in peaks) {
            val angle = angle(p, eyeM) ?: continue
            if (angle < 0.4 || angle > 60) continue
            // The instant the descending sun's altitude is [angle], by bisection between noon and the sunset.
            var lo = noon
            var hi = set
            if (Sun.position(noon, at).altitudeDeg < angle) continue
            repeat(28) {
                val mid = Instant.ofEpochSecond((lo.epochSecond + hi.epochSecond) / 2)
                if (Sun.position(mid, at).altitudeDeg > angle) lo = mid else hi = mid
            }
            val az = Sun.position(hi, at).azimuthDeg
            val gap = Geo.bearingDelta(az, p.bearing)
            if (gap <= tolerance && gap < bestGap) { bestGap = gap; best = SunBehind(p, hi.atZone(zone)) }
        }
        return best
    }

    /** Minutes to walk to a peak by Naismith's rule: 5 km/h along a path a third longer than the crow flies, and 10 minutes per 100 m of climb. */
    fun hikeMinutes(distanceM: Double, climbM: Double): Int = ((distanceM * 1.3 / 5000.0) * 60 + climbM.coerceAtLeast(0.0) / 100.0 * 10).roundToInt()
}
