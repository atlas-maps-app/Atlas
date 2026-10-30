package app.atlas.ui.local.astro

import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Where the space station's orbit data came from. */
enum class OrbitSource { FRESH, CACHED, BUNDLED }

/** The orbit data in hand, how old it is and where it came from. */
class OrbitData(val tle: Tle, val source: OrbitSource, val ageDays: Double) {
    /** True when the data is old enough that a pass time is a guess of a minute or more. */
    val stale: Boolean get() = ageDays > STALE_DAYS

    /** Too old to say anything about passes at all. */
    val useless: Boolean get() = ageDays > USELESS_DAYS

    companion object {
        const val STALE_DAYS = 10.0
        const val USELESS_DAYS = 40.0
    }
}

/**
 * The space station's two-line element set: the one on the phone (fetched from CelesTrak, else from [second], kept in
 * [file]), else the one bundled with the app ([bundled]). CelesTrak asks to be fetched at most every two hours; the data
 * is good for days.
 */
class TleStore(
    private val file: File,
    private val bundled: () -> String?,
    private val fetch: suspend () -> String?,
    private val clock: Clock = Clock.systemUTC(),
    private val minInterval: Duration = Duration.ofHours(2),
    /** The second way in, asked where [fetch] gives nothing that reads: ARISS's copy of the same set ([SECOND_URL]). */
    private val second: (suspend () -> String?)? = null,
) {
    @Volatile
    private var lastTry: Instant? = null

    /** The best orbit data in hand; fetches a fresh set first when [online] and the kept one is older than two hours. */
    suspend fun orbit(online: Boolean): OrbitData? {
        val now = clock.instant()
        var kept = readKept()
        val keptAt = if (file.isFile) Instant.ofEpochMilli(file.lastModified()) else null
        var source = OrbitSource.CACHED
        // A failed fetch is not repeated before [minInterval] is up either: CelesTrak blocks addresses that ask too often.
        val tried = lastTry
        if (online && (keptAt == null || Duration.between(keptAt, now) > minInterval) && (tried == null || Duration.between(tried, now) > minInterval)) {
            lastTry = now
            val got = listOfNotNull(fetch, second).firstNotNullOfOrNull { ask ->
                val text = try { ask() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
                text?.let { t -> Tle.parse(t)?.let { t to it } }
            }
            if (got != null) {
                // A full or read-only disk loses only the kept copy, never the set that came: until 30 October the write's
                // failure went up through the Globe's passes, which nothing caught, and closed the app.
                try {
                    file.parentFile?.mkdirs()
                    file.writeText(got.first)
                } catch (_: java.io.IOException) {
                }
                kept = got.second
                source = OrbitSource.FRESH
            }
        }
        val best = kept ?: bundled()?.let { Tle.parse(it) }?.also { source = OrbitSource.BUNDLED } ?: return null
        // A newer bundled set beats an older kept one (an app update after a long time offline).
        val other = bundled()?.let { Tle.parse(it) }
        val chosen = if (other != null && other.epochSeconds > best.epochSeconds) other.also { source = OrbitSource.BUNDLED } else best
        val age = (now.epochSecond - chosen.epochSeconds) / 86_400.0
        return OrbitData(chosen, source, age)
    }

    private fun readKept(): Tle? = if (file.isFile) runCatching { Tle.parse(file.readText()) }.getOrNull() else null

    companion object {
        const val URL = "https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE"

        /** ARISS's copy of the station's set: the same as CelesTrak's on 30 October 2026. */
        const val SECOND_URL = "https://live.ariss.org/iss.txt"
    }
}
