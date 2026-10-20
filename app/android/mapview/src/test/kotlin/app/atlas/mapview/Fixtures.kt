package app.atlas.mapview

import app.atlas.geo.LatLon
import java.io.File
import java.time.Instant

object Fixtures {
    val shots: File get() = File(System.getProperty("atlas.shots") ?: "build/outputs/roborazzi").also { it.mkdirs() }

    val westermarkt = LatLon(52.3746, 4.8826)
    val westerkerk = LatLon(52.37452, 4.88389)

    /** An April afternoon whose sun over Amsterdam is the engine previews' light (azimuth 215°, elevation 38°). */
    val afternoon: Instant = Instant.parse("2026-04-03T13:30:00Z")

    /** A winter night. */
    val night: Instant = Instant.parse("2026-01-10T22:00:00Z")

    /** The Main board's camera, as the engine's preview frames it. */
    val main = CameraPosition(westerkerk, 15.5, 22.0, 54.0)
}
