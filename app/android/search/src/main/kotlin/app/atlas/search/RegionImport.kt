package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.providers.Overpass
import app.atlas.providers.OverpassQl
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.Locale
import kotlin.math.ceil

/**
 * Builds a region's [PlaceStore] from Overpass when the user downloads a region: every point of interest and named
 * feature with full tags, stops and stations, addresses, and trees (see [Selection]). A big region is fetched in tiles
 * of about [tileDegrees], one transaction each, so a failed tile is retried alone and progress can be shown.
 */
class RegionImport(
    private val overpass: Overpass,
    private val tileDegrees: Double = 0.05,
    private val retries: Int = 2,
    /** Also fetch when each element was last edited, so "newer:" works offline (a slightly bigger answer). */
    private val withEditTimes: Boolean = false,
) {
    /** How far an import has got: tiles done of all, elements read and places kept so far. */
    data class Progress(val tilesDone: Int, val tiles: Int, val elements: Long, val places: Int) {
        val fraction: Double get() = if (tiles == 0) 1.0 else tilesDone.toDouble() / tiles
    }

    /** Fills [store] with everything in [region]; returns what it did. Cancel the coroutine to stop between tiles. */
    suspend fun import(
        region: BBox,
        store: PlaceStore,
        name: String? = null,
        langs: List<String> = PlaceStore.DEFAULT_LANGS,
        progress: (Progress) -> Unit = {},
    ): ImportStats {
        val tiles = tiles(region, tileDegrees)
        val writer = store.writer(langs, name)
        writer.use { w ->
            progress(Progress(0, tiles.size, 0, 0))
            for ((i, tile) in tiles.withIndex()) {
                currentCoroutineContext().ensureActive()
                var attempt = 0
                while (true) {
                    try {
                        overpass.stream(ql(tile, withEditTimes = withEditTimes)) { w.add(it) }
                        w.commit()
                        break
                    } catch (e: IOException) {
                        w.rollback()
                        if (++attempt > retries) throw e
                    }
                }
                progress(Progress(i + 1, tiles.size, w.elements, w.places))
            }
            val stats = w.finish()
            // pages written tile by tile are part full: rewritten whole, the store is smaller and faster
            store.compact()
            return stats
        }
    }

    companion object {
        /**
         * The Overpass QL for a region's places in [box]: points of interest by their keys, the natural features and
         * railway stops worth finding, bus stops, addresses and anything named; with full tags and outline boxes.
         */
        fun ql(box: BBox, timeoutSeconds: Int = 180, withEditTimes: Boolean = false): String = buildString {
            append("[out:json][timeout:").append(timeoutSeconds).append("][bbox:")
            append(f(box.south)).append(',').append(f(box.west)).append(',').append(f(box.north)).append(',').append(f(box.east))
            append("];\n(\n")
            for (k in Selection.POI_KEYS) append("  nwr").append(OverpassQl.tag(k)).append(";\n")
            append("  nwr").append(OverpassQl.oneOf("natural", Selection.NATURAL)).append(";\n")
            append("  nwr").append(OverpassQl.oneOf("railway", Selection.RAILWAY)).append(";\n")
            append("  node").append(OverpassQl.tag("highway", "bus_stop")).append(";\n")
            append("  nwr").append(OverpassQl.tag("addr:housenumber")).append(";\n")
            append("  nwr").append(OverpassQl.tag("name")).append(";\n")
            append(if (withEditTimes) ");\nout meta bb qt;" else ");\nout tags bb qt;")
        }

        /** [box] cut into tiles of about [degrees] a side (one tile when it is small enough). */
        fun tiles(box: BBox, degrees: Double = 0.05): List<BBox> {
            require(degrees > 0) { "Tiles need a positive size" }
            // a hair under the size, so 0.1° in 0.05° tiles is two tiles despite floating point
            val cols = ceil(box.width / degrees - 1e-9).toInt().coerceAtLeast(1)
            val rows = ceil(box.height / degrees - 1e-9).toInt().coerceAtLeast(1)
            val w = box.width / cols
            val h = box.height / rows
            val out = ArrayList<BBox>(cols * rows)
            for (r in 0 until rows) for (c in 0 until cols) {
                out += BBox(box.west + c * w, box.south + r * h, if (c == cols - 1) box.east else box.west + (c + 1) * w, if (r == rows - 1) box.north else box.south + (r + 1) * h)
            }
            return out
        }

        private fun f(v: Double) = String.format(Locale.ROOT, "%.6f", v)
    }
}
