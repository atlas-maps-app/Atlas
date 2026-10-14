package app.atlas.search

import app.atlas.geo.LatLon
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime

/** The real extracts around the Westerkerk, imported once per test run into a temporary store. */
object Fixtures {
    val repo = File(System.getProperty("atlas.repo") ?: "../../..")
    val ams = File(repo, "data/ams/raw.json")
    val eat = File(repo, "data/eat/raw.json")
    val errands = File(repo, "data/errands/raw.json")

    /** The Westerkerk's tower, the centre of the extracts. */
    val westerkerk = LatLon(52.3745, 4.8838)

    val amsterdam: ZoneId = ZoneId.of("Europe/Amsterdam")

    /** A Saturday afternoon in Amsterdam, for open-now tests. */
    val saturday: ZonedDateTime = ZonedDateTime.of(2026, 10, 10, 14, 30, 0, 0, amsterdam)

    val dir: File by lazy { Files.createTempDirectory("atlas-search").toFile().also { it.deleteOnExit() } }

    /** Import timings, by file, filled when [store] is built. */
    val importStats = LinkedHashMap<String, ImportStats>()

    /** One store with all three extracts. */
    val store: PlaceStore by lazy {
        val s = PlaceStore.open(File(dir, "amsterdam.db"))
        for (f in listOf(ams, eat, errands)) importStats[f.parentFile.name] = s.importOverpass(f, regionName = "Amsterdam")
        s
    }

    fun context(now: ZonedDateTime = saturday, lang: String = "en") =
        EvalContext(location = westerkerk, now = now, lang = lang)
}
