package app.atlas.rendertiles.compile

import app.atlas.render.live.EngineVersion
import app.atlas.render.live.LiveRegion
import app.atlas.rendertiles.codec.Format3Writer
import app.atlas.rendertiles.model.Reach
import app.atlas.rendertiles.model.RenderHead
import app.atlas.rendertiles.model.StyleTables
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
 * with, from the raw.json the port loads, and its pack's head and style as the core writes them (load/PackHead.hpp):
 * the views and their tolerances from the region's region.json, the bands, the palette, the module values, the scene's
 * scalars and the tradition. [SceneToTiles.pack] writes them into its pack. The core also refuses what the tiles
 * cannot carry yet, as the port's scene would.
 *
 * The core's tiles are the port's byte for byte (the core's cut_test and CutParityTest, tile by tile), and so are its
 * heads and styles (pack_style_test); mapcorecheck's StagePPackerTest holds whole packs.
 */
object CoreTiles {
    /** The tool's exit code where the region holds what the tiles cannot carry yet (the packer's render_gap). */
    const val NOT_CARRIED = 3

    /** The format of the tool's tiles file this reads. */
    const val FORMAT = 2

    /** What the tool made: the tiles in its cut's order, and the pack's head and style. */
    class Made(val tiles: List<Format3Writer.Encoded>, val head: RenderHead, val style: StyleTables)

    /** The tool's tiles file as read: the tiles, and the head's and the style's bytes (the style before compression). */
    class Read(val tiles: List<Format3Writer.Encoded>, val head: ByteArray, val style: ByteArray)

    /**
     * The job the tool reads for [region]'s pack at [levels]: its profile, its id, the levels, its frame's doubles as
     * their bits (the port's own, kx from CrMath.cos), the port's engine version, [source] (the element pack's
     * SHA-256) and a region's region.json, as the port's region unpacked it (its views among it), which the core reads
     * the rest from.
     */
    fun job(region: LiveRegion, levels: IntArray, source: String): String {
        val living = region.kind == LiveRegion.Kind.LIVING
        val f = region.frame
        return buildJsonObject {
            put("profile", if (living) "living" else "region")
            put("region", region.id)
            putJsonArray("levels") { for (z in levels) add(z) }
            putJsonArray("frame") { for (d in listOf(f.lat0, f.lon0, f.kx, f.kz)) add(JsonPrimitive("%016x".format(d.toRawBits()))) }
            put("engine_version", EngineVersion.ID)
            put("source", source)
            if (!living) put("region_json", File(region.dir, "region.json").path)
        }.toString()
    }

    /**
     * [region]'s pack at [levels], loaded, cut and headed by the core's [tool] from the region's raw.json, its job and
     * tiles file in [work] (deleted after). A region whose data holds what the tiles cannot carry yet throws the
     * UnsupportedOperationException the packer's own cut throws for it (render_gap); any other failure, an
     * IllegalStateException with the tool's reason. The head and the style are written as the core wrote them: each
     * must read back to its own bytes.
     */
    fun pack(tool: File, region: LiveRegion, levels: IntArray, source: String, work: File, log: (String) -> Unit = {}): Made {
        work.mkdirs()
        val job = File.createTempFile("stagep-", ".json", work)
        val out = File(work, "${job.nameWithoutExtension}.tiles")
        try {
            job.writeText(job(region, levels, source))
            val process = ProcessBuilder(tool.path, "tiles", File(region.dir, "raw.json").path, job.path, out.path)
                .redirectErrorStream(true)
                .start()
            val said = process.inputStream.bufferedReader().readText().trim()
            when (val code = process.waitFor()) {
                0 -> log("${region.id}: the core's stage P: $said")
                NOT_CARRIED -> throw UnsupportedOperationException("${region.id}: render tiles cannot carry what it holds yet (the core's stage P: $said)")
                else -> throw IllegalStateException("${region.id}: the core's stage P failed with exit code $code: $said")
            }
            val read = read(out.readBytes())
            val head = RenderHead.fromBytes(read.head)
            check(head.toBytes().contentEquals(read.head)) { "${region.id}: the core's head does not read back to its own bytes" }
            val style = StyleTables.fromBytes(read.style)
            check(style.toBytes().contentEquals(read.style)) { "${region.id}: the core's style does not read back to its own bytes" }
            return Made(read.tiles, head, style)
        } finally {
            job.delete()
            out.delete()
        }
    }

    /** The tool's tiles file (its layout is in mapcore/cpp/stagep/main.cpp): the tiles in its order, the head, the style. */
    fun read(bytes: ByteArray): Read {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        try {
            val magic = ByteArray(4).also { b.get(it) }
            if (String(magic, Charsets.US_ASCII) != "ATSP") throw IOException("not the core's tiles file")
            val format = b.int
            if (format != FORMAT) throw IOException("the core's tiles file of format $format, not $FORMAT")
            val count = b.int
            if (count < 0) throw IOException("a tiles file of $count tiles")
            val tiles = List(count) {
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
            }
            val head = ByteArray(b.int).also { b.get(it) }
            val style = ByteArray(b.int).also { b.get(it) }
            if (b.hasRemaining()) throw IOException("${b.remaining()} bytes after the core's style")
            return Read(tiles, head, style)
        } catch (e: BufferUnderflowException) {
            throw IOException("the core's tiles file ends early", e)
        } catch (e: NegativeArraySizeException) {
            throw IOException("the core's tiles file holds a negative length", e)
        }
    }
}
