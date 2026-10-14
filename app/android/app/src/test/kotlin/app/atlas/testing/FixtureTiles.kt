package app.atlas.testing

import app.atlas.geo.TileId
import app.atlas.tiles.TileSource
import java.io.File

/**
 * The real OpenFreeMap tiles around the Westerkerk in `testdata/omt/{z}/{x}/{y}.pbf` (uncompressed MVT): z0, 6, 10, 12,
 * 13 and the z14 block x 8413–8415, y 5383–5385. Missing tiles read as absent, as a pack without them would.
 */
class FixtureTiles(private val dir: File = File(System.getProperty("atlas.testdata") ?: "testdata", "omt")) : TileSource {
    override val minZoom: Int get() = 0
    override val maxZoom: Int get() = 14
    override val attribution: String get() = "© OpenMapTiles © OpenStreetMap contributors"

    override suspend fun tile(id: TileId): ByteArray? = File(dir, "${id.z}/${id.x}/${id.y}.pbf").takeIf { it.isFile }?.readBytes()
}
