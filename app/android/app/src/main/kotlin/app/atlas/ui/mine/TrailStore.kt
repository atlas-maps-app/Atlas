package app.atlas.ui.mine

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.math.floor

/**
 * Where you have been, kept on this phone and nowhere else: a plain text file of one line per fix (`seconds,lat,lon`),
 * written only while the setting is on, thinned to a point every [MIN_METRES] or every [MIN_SECONDS], and emptied by
 * [clear]. Nothing here reads the network; the file is not backed up.
 */
class TrailStore(private val file: File) {
    private val lock = Any()
    private var last: Triple<Long, Double, Double>? = null

    /** Records [at] at [time] unless it is too close in space and time to the last point kept. Returns whether it was kept. */
    fun record(at: LatLon, time: Instant): Boolean = synchronized(lock) {
        val seconds = time.epochSecond
        val before = last ?: readLast()
        if (before != null) {
            val near = Geo.distance(LatLon(before.second, before.third), at) < MIN_METRES
            if (near && seconds - before.first < MIN_SECONDS) return false
        }
        try {
            file.parentFile?.mkdirs()
            file.appendText("$seconds,${"%.5f".format(java.util.Locale.ROOT, at.lat)},${"%.5f".format(java.util.Locale.ROOT, at.lon)}\n")
        } catch (e: IOException) {
            return false
        }
        last = Triple(seconds, at.lat, at.lon)
        true
    }

    /** Every point kept, oldest first. */
    fun points(): List<TrailPoint> = synchronized(lock) {
        if (!file.isFile) return emptyList()
        file.useLines { lines -> lines.mapNotNull(::parse).toList() }
    }

    /** How many points are kept. */
    fun count(): Int = synchronized(lock) { if (!file.isFile) 0 else file.useLines { l -> l.count { it.isNotBlank() } } }

    /** Bytes the file takes. */
    fun bytes(): Long = synchronized(lock) { if (file.isFile) file.length() else 0L }

    /**
     * How many different places you have been to since [since]: the points that fall in different squares of about 250 m,
     * so standing still is one place and a walk across town is several.
     */
    fun places(since: Instant = Instant.EPOCH): Int {
        val cells = HashSet<Long>()
        for (p in points()) if (p.time >= since) cells += cell(p.at)
        return cells.size
    }

    /** Forgets every point. */
    fun clear() = synchronized(lock) {
        last = null
        file.delete()
        Unit
    }

    /** Drops points older than [before] (the file is rewritten only when there are some). */
    fun prune(before: Instant) = synchronized(lock) {
        if (!file.isFile) return@synchronized
        val kept = file.useLines { lines -> lines.filter { l -> parse(l)?.let { it.time >= before } ?: false }.toList() }
        if (kept.size == count()) return@synchronized
        val tmp = File(file.path + ".tmp")
        tmp.writeText(kept.joinToString("") { it + "\n" })
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        last = null
    }

    private fun readLast(): Triple<Long, Double, Double>? {
        if (!file.isFile) return null
        var line: String? = null
        file.useLines { lines -> for (l in lines) if (l.isNotBlank()) line = l }
        return line?.let(::parse)?.let { Triple(it.time.epochSecond, it.at.lat, it.at.lon) }
    }

    private fun cell(p: LatLon): Long {
        val lat = floor(p.lat / CELL_DEGREES).toLong()
        val lon = floor(p.lon / (CELL_DEGREES / kotlin.math.cos(Math.toRadians(p.lat)).coerceAtLeast(0.2))).toLong()
        return lat * 4_000_000L + lon
    }

    private fun parse(line: String): TrailPoint? {
        val f = line.split(',')
        if (f.size < 3) return null
        return TrailPoint(Instant.ofEpochSecond(f[0].toLongOrNull() ?: return null), LatLon(f[1].toDoubleOrNull() ?: return null, f[2].toDoubleOrNull() ?: return null))
    }

    companion object {
        const val MIN_METRES = 40.0
        const val MIN_SECONDS = 600L
        private const val CELL_DEGREES = 0.00225
    }
}

/** One point of where you have been. */
data class TrailPoint(val time: Instant, val at: LatLon)
