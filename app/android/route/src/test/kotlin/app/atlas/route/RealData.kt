package app.atlas.route

import app.atlas.geo.LatLon
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files

/** The real BRouter data the tests use, when it is there (the tests skip otherwise). */
object RealData {
    val segments: File = File(System.getProperty("atlas.segments") ?: "../../cache/brouter/segments4")
    val amsterdam: File get() = File(segments, "E0_N50.rd5")

    val WESTERKERK = LatLon(52.37452, 4.88377)
    val CENTRAAL = LatLon(52.37880, 4.90010)
    val HAARLEM = LatLon(52.38785, 4.63830)
    val PRINSENGRACHT = LatLon(52.3740, 4.8846)
    val IJ = LatLon(52.3810, 4.9000)

    fun assumeAmsterdam() {
        assumeTrue("E0_N50.rd5 not in ${segments.absolutePath}", amsterdam.isFile)
    }

    /** A profiles folder for one test class. */
    fun profileDir(): File = Files.createTempDirectory("atlas-profiles").toFile().also { it.deleteOnExit() }

    private val shared: BRouterRouter by lazy { BRouterRouter(segments, profileDir(), memoryClassMb = 64) }

    fun router(): BRouterRouter = shared
}
