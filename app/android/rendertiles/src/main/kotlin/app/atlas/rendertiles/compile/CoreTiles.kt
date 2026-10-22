package app.atlas.rendertiles.compile

import app.atlas.render.live.LiveRegion
import app.atlas.rendertiles.codec.Format3Writer
import app.atlas.rendertiles.model.Keep
import app.atlas.rendertiles.model.Reach
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stage P in the core (ENGINE.md step 3: the packer runs the core's stage P): a region's or the living map's render
 * tiles loaded and cut by the core's tool (`atlas_stagep`, mapcore/cpp/stagep), the code the phone builds its windows
 * with, from the raw.json the port loads. [SceneToTiles.pack] writes them into its pack beside the head and the style
 * it writes from the port's scene, which also refuses what the tiles cannot carry yet, as it always has.
 *
 * The core's tiles are the port's byte for byte (the core's cut_test and CutParityTest, tile by tile; mapcorecheck's
 * StagePPackerTest, whole packs).
 */
object CoreTiles {
    /** The tool's exit code where the region holds what the tiles cannot carry yet (the packer's render_gap). */
    const val NOT_CARRIED = 3

    /** The format of the tool's tiles file this reads. */
    const val FORMAT = 1

    /**
     * The job the tool reads for [region]'s pack at [levels]: its profile, its id, the levels, its frame's doubles as
     * their bits (the port's own, kx from CrMath.cos), the keep ladder's indices its variants load at
     * ([SceneToTiles.variantTolerances]), and a region's region.json, which the core reads its box and tradition from.
     */
    fun job(region: LiveRegion, levels: IntArray): String {
        val living = region.kind == LiveRegion.Kind.LIVING
        val f = region.frame
        return buildJsonObject {
            put("profile", if (living) "living" else "region")
            put("region", region.id)
            putJsonArray("levels") { for (z in levels) add(z) }
            putJsonArray("frame") { for (d in listOf(f.lat0, f.lon0, f.kx, f.kz)) add(JsonPrimitive("%016x".format(d.toRawBits()))) }
            putJsonArray("tolerances") { for (t in SceneToTiles.variantTolerances(region)) add(Keep.indexOf(t)) }
            if (!living) put("region_json", File(region.dir, "region.json").path)
        }.toString()
    }

    /**
     * [region]'s tiles at [levels], loaded and cut by the core's [tool] from the region's raw.json, its job and tiles
     * file in [work] (deleted after). A region whose data holds what the tiles cannot carry yet throws the
     * UnsupportedOperationException the packer's own cut throws for it (render_gap); any other failure, an
     * IllegalStateException with the tool's reason.
     */
    fun tiles(tool: File, region: LiveRegion, levels: IntArray, work: File, log: (String) -> Unit = {}): List<Format3Writer.Encoded> {
        work.mkdirs()
        val job = File.createTempFile("stagep-", ".json", work)
        val out = File(work, "${job.nameWithoutExtension}.tiles")
        try {
            job.writeText(job(region, levels))
            val process = ProcessBuilder(tool.path, "tiles", File(region.dir, "raw.json").path, job.path, out.path)
                .redirectErrorStream(true)
                .start()
            val said = process.inputStream.bufferedReader().readText().trim()
            when (val code = process.waitFor()) {
                0 -> log("${region.id}: the core's stage P: $said")
                NOT_CARRIED -> throw UnsupportedOperationException("${region.id}: render tiles cannot carry what it holds yet (the core's stage P: $said)")
                else -> throw IllegalStateException("${region.id}: the core's stage P failed with exit code $code: $said")
            }
            return read(out.readBytes())
        } finally {
            job.delete()
            out.delete()
        }
    }

    /** The tiles of the tool's tiles file (its layout is in mapcore/cpp/stagep/main.cpp), in its order. */
    fun read(bytes: ByteArray): List<Format3Writer.Encoded> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        try {
            val magic = ByteArray(4).also { b.get(it) }
            if (String(magic, Charsets.US_ASCII) != "ATSP") throw IOException("not the core's tiles file")
            val format = b.int
            if (format != FORMAT) throw IOException("the core's tiles file of format $format, not $FORMAT")
            val count = b.int
            if (count < 0) throw IOException("a tiles file of $count tiles")
            return List(count) {
                val level = b.get().toInt() and 0xFF
                val x = b.int
                val y = b.int
                val features = b.int
                val south = b.int
                val west = b.int
                val north = b.int
                val east = b.int
                val top = b.int
                val layers = LinkedHashMap<String, Int>()
                repeat(b.short.toInt() and 0xFFFF) {
                    val label = ByteArray(b.get().toInt() and 0xFF).also { b.get(it) }.toString(Charsets.UTF_8)
                    layers[label] = b.int
                }
                val raw = ByteArray(b.int).also { b.get(it) }
                Format3Writer.Encoded(level, x, y, raw, features, Reach(south, west, north, east, top), layers)
            }.also { if (b.hasRemaining()) throw IOException("${b.remaining()} bytes after the core's tiles") }
        } catch (e: BufferUnderflowException) {
            throw IOException("the core's tiles file ends early", e)
        } catch (e: NegativeArraySizeException) {
            throw IOException("the core's tiles file holds a negative length", e)
        }
    }
}
