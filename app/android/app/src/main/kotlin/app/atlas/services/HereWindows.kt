package app.atlas.services

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.render.data.ElementType
import app.atlas.render.data.MemoryBoxes
import app.atlas.render.data.OsmElement
import app.atlas.render.data.OverpassJson
import app.atlas.render.data.PackFeatures
import app.atlas.tiles.WindowPacks
import app.atlas.tiles.WorldFetch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.ref.SoftReference
import java.util.zip.GZIPInputStream

/**
 * The map's data around a point for "What's here" where no store or pack on the phone holds it: Overpass's answer for
 * the window holding the point (ENGINE.md step 3: its L14 tile and 200 m around, every tagged node, way and
 * multipolygon), from the copy the map keeps beside a window it built from data ([kept], offline), else from the
 * window's data in Atlas's own packs ([packed]: one range of the country's windows file, as the map's windows read it),
 * not from Overpass, whose one query at a time the map's windows and searches share (the user, 1 November: "Sometimes it
 * says looking forever and then claims theres nothing mapped within 150m").
 *
 * Only the elements near the point are held, and the place names of the window: a dense window's answer is tens of MB.
 * The last window read from the packs is held, so a press beside the last one reads no bytes again.
 */
class HereWindows(
    private val kept: (x: Int, y: Int) -> File?,
    /** Window ([x], [y])'s data in Atlas's packs, gzipped; null where no pack holds it. Blocks; throws IOException. */
    private val packed: (x: Int, y: Int) -> ByteArray?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** The last window read from the packs, by its key; the heap may take it back. */
    @Volatile
    private var last: Pair<Long, SoftReference<ByteArray>>? = null

    /**
     * The window's elements within [radiusM] (and [MARGIN_M]) of [at], and its place names; null where neither copy is
     * to be had. A read from the packs that does not finish within [withinMs] throws IOException: it goes on alone, and
     * keeps its bytes for the next press.
     */
    suspend fun around(at: LatLon, radiusM: Double, withinMs: Long): PackFeatures? {
        val (x, y) = WorldFetch.tileOf(at.lat, at.lon, WindowPacks.LEVEL)
        val key = x.toLong() shl 32 or y.toLong()
        val file = kept(x, y)?.takeIf { it.isFile && it.length() > 0 }
        val input: InputStream = when {
            file != null -> file.inputStream()
            else -> {
                val bytes = last?.takeIf { it.first == key }?.second?.get() ?: run {
                    val read = scope.async { packed(x, y)?.also { last = key to SoftReference(it) } }
                    withTimeoutOrNull(withinMs) { read.await() ?: NONE } ?: throw IOException("the window's data did not come within ${withinMs / 1000} s")
                }
                if (bytes === NONE) return null
                ByteArrayInputStream(bytes)
            }
        }
        val keep = BBox.around(at, radiusM + MARGIN_M)
        val elements = input.use { near(it, keep) }
        return PackFeatures(listOf(MemoryBoxes(elements, keep)))
    }

    /** The elements of Overpass's gzipped JSON [input] that meet [keep], and its place names. */
    private fun near(input: InputStream, keep: BBox): List<OsmElement> {
        val out = ArrayList<OsmElement>()
        val p = OverpassJson.pull(GZIPInputStream(input, 1 shl 16).buffered(1 shl 16))
        p.beginObject()
        while (p.hasNext()) {
            if (p.nextName() != "elements") {
                p.skipValue()
                continue
            }
            p.beginArray()
            while (p.hasNext()) {
                val e = OverpassJson.element(p)
                val place = e.type == ElementType.NODE && e.tag("place") != null && e.tag("name") != null
                if (place || PackFeatures.boxOf(e)?.intersects(keep) == true) out += e
            }
            p.endArray()
        }
        return out
    }

    companion object {
        /** Beyond the places' radius: the streets and outlines an address and the spot are read from. */
        const val MARGIN_M = 60.0

        /** No pack holds the window. */
        private val NONE = ByteArray(0)
    }
}
