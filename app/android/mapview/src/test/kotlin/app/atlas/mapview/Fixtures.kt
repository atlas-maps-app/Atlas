package app.atlas.mapview

import app.atlas.geo.LatLon
import app.atlas.geo.TileId
import app.atlas.tiles.TileSource
import java.io.File
import java.time.Instant

/**
 * The real OpenFreeMap tiles around the Westerkerk in `testdata/omt/{z}/{x}/{y}.pbf` (uncompressed MVT): z0, 6, 10, 12,
 * 13 and the z14 block x 8413–8415, y 5383–5385. Missing tiles read as absent. Counts its reads.
 */
class FixtureTiles(private val dir: File = File(Fixtures.testdata, "omt")) : TileSource {
    override val minZoom: Int get() = 0
    override val maxZoom: Int get() = 14
    override val attribution: String get() = "© OpenMapTiles © OpenStreetMap contributors"

    @Volatile
    var reads = 0

    override suspend fun tile(id: TileId): ByteArray? {
        reads++
        return File(dir, "${id.z}/${id.x}/${id.y}.pbf").takeIf { it.isFile }?.readBytes()
    }
}

object Fixtures {
    val testdata: File get() = File(System.getProperty("atlas.testdata") ?: "../testdata")
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
