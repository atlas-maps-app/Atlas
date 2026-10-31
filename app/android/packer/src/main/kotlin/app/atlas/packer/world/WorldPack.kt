package app.atlas.packer.world

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.render.Amsterdam
import app.atlas.render.live.EngineVersion
import app.atlas.render.py.PyMath
import app.atlas.rendertiles.codec.Format3Writer
import app.atlas.rendertiles.compile.EngineStyle
import app.atlas.rendertiles.compile.GroundKeys
import app.atlas.rendertiles.model.Grid
import app.atlas.rendertiles.model.GroundKey
import app.atlas.rendertiles.model.Keep
import app.atlas.rendertiles.model.Profile
import app.atlas.rendertiles.model.RenderHead
import app.atlas.rendertiles.model.Schema
import app.atlas.rendertiles.model.StyleTables
import app.atlas.rendertiles.model.StyleVersion
import app.atlas.tiles.WorldIndex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * A world pack (ENGINE.md step 5): format 3 with the world's profile ([Profile.WORLD_BASE]), the frame at its block's
 * centre (as the core's frame_of makes a frame cell's), and a style with every table the core reads, from the engine's
 * style file and the format, without a scene: the world's tiles carry no buildings, so the port's module values are
 * its own defaults (the core's ModuleState's), its looks Amsterdam's, its time zone UTC.
 */
object WorldPack {
    /** The style section of every world pack. */
    val style: StyleTables by lazy {
        val t = LinkedHashMap<String, Any?>()
        t["keep"] = Keep.TOLERANCES.toList()
        t["render_tags"] = Schema.RENDER_TAGS.entries.associate { (k, v) -> k.label to v }
        for ((name, table) in EngineStyle.tables) t[name] = table
        t["material"] = Amsterdam.MATERIAL
        t["clay"] = Amsterdam.CLAY
        t["road_order"] = GroundKeys.ROAD_ORDER
        t["aero_order"] = GroundKeys.AERO_ORDER
        t["ground_stages"] = linkedMapOf("land" to GroundKey.LAND, "water" to GroundKey.WATER, "area" to GroundKey.AREA, "road" to GroundKey.ROAD, "over" to GroundKey.OVER, "marks" to GroundKey.MARKS)
        t["marks_classes"] = linkedMapOf("road" to GroundKey.MARKS_ROAD, "tram" to GroundKey.MARKS_TRAM, "canal" to GroundKey.MARKS_CANAL)
        t["building_flags"] = linkedMapOf(
            "church" to Schema.Flags.CHURCH, "warehouse" to Schema.Flags.WAREHOUSE, "shop_floor" to Schema.Flags.SHOP_FLOOR, "construction" to Schema.Flags.CONSTRUCTION,
            "near_water" to Schema.Flags.NEAR_WATER, "houseboat" to Schema.Flags.HOUSEBOAT, "modern" to Schema.Flags.MODERN, "wall_drawn" to Schema.Flags.WALL_DRAWN,
        )
        // amsterdam.py's own looks and module values (load/Amsterdam.cpp's and PackStyle.hpp's ModuleState), as no
        // region's load has changed them
        t["palette"] = linkedMapOf(
            "brick_old" to listOf("#6E3B2E", "#7A4434", "#5C3A30", "#8A5040", "#6A4A3C", "#7E5242"),
            "plaster" to listOf("#E8E2D4", "#EFE9DC", "#D9D2C2"),
            "brick_new" to listOf("#9C6A52", "#8F7F74", "#B08A6A", "#8C8A86", "#A9876E"),
        )
        t["state"] = linkedMapOf(
            "water_y" to -1.5, "quay_col" to "#6E5546", "quay_course" to 0.0625, "tone" to false,
            "win_style" to null, "tz_h" to 0, "air_haze" to true, "clouds_lit" to false,
        )
        t["scene"] = LinkedHashMap<String, Any?>()
        StyleTables(t)
    }

    /** The frame of block ([z], [x], [y]): its centre to four decimals, kx = 111,320 m·cos(lat0), kz = 110,540 m. */
    fun frame(z: Int, x: Int, y: Int): DoubleArray {
        val b = Grid.tileBox(z, x, y)
        val lat = Math.floorDiv(b[0].toLong() + b[2], 2L) / Grid.SCALE
        val lon = Math.floorDiv(b[1].toLong() + b[3], 2L) / Grid.SCALE
        val lat0 = Math.floor(lat * 1e4 + 0.5) / 1e4
        val lon0 = Math.floor(lon * 1e4 + 0.5) / 1e4
        return doubleArrayOf(lat0, lon0, 111320 * PyMath.cos(PyMath.radians(lat0)), 110540.0)
    }

    /**
     * What a pack holds besides its tiles: its id, its block, its levels, the data's date and sources, the inputs' hash.
     * The head names the levels from fine to coarse, as the core reads a head.
     */
    class Spec(val id: String, val z: Int, val x: Int, val y: Int, levels: IntRange, val dataTime: String?, val sources: List<String>, val source: String) {
        val levels: IntArray = levels.reversed().toList().toIntArray()
    }

    /** Writes [tiles] as [spec]'s pack into [file]. */
    fun write(tiles: List<Format3Writer.Encoded>, spec: Spec, file: File): Format3Writer.Written {
        val f = frame(spec.z, spec.x, spec.y)
        val head = RenderHead(
            Profile.WORLD_BASE, spec.id, spec.levels, f[0], f[1], f[2], f[3], EngineVersion.ID, StyleVersion.ID, spec.source,
            emptyList(), listOf(RenderHead.Band(0, null)),
        )
        val b = Grid.tileBox(spec.z, spec.x, spec.y)
        val box = BBox(Grid.deg(b[1]), Grid.deg(b[0]), Grid.deg(b[3]), Grid.deg(b[2]))
        val info = Format3Writer.Info(
            spec.id, spec.id, box, LatLon(f[0], f[1]), null, null, spec.dataTime,
            JsonObject(mapOf("block" to JsonPrimitive("${spec.z}/${spec.x}/${spec.y}"))), JsonObject(emptyMap()), spec.sources,
        )
        return Format3Writer.writeEncoded(tiles, head, style, info, file)
    }

    /** The parts' size limit: under GitHub's 2 GiB for a release asset. */
    const val PART_BYTES = 1_900_000_000L

    /**
     * [packs] (each a written pack and its index entry's facts) end to end in parts `<name>.000`, `.001`, … in [dir],
     * each under [PART_BYTES]; the entries with their parts, offsets, lengths and SHA-256s.
     */
    fun parts(dir: File, name: String, packs: List<Pair<File, (part: Int, offset: Long, length: Long, sha: ByteArray) -> WorldIndex.Entry>>, firstPart: Int, partsOut: MutableList<WorldIndex.Part>): List<WorldIndex.Entry> {
        val entries = ArrayList<WorldIndex.Entry>()
        var part = -1
        var at = 0L
        var out: java.io.OutputStream? = null
        var partFile: File? = null
        fun close() {
            out?.close()
            partFile?.let { partsOut += WorldIndex.Part(it.name, it.length()) }
            out = null
        }
        for ((file, entry) in packs) {
            val bytes = file.readBytes()
            if (out == null || at + bytes.size > PART_BYTES) {
                close()
                part++
                partFile = File(dir, "%s.%03d".format(name, part))
                out = partFile!!.outputStream().buffered(1 shl 20)
                at = 0
            }
            out!!.write(bytes)
            entries += entry(firstPart + part, at, bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes))
            at += bytes.size
        }
        close()
        return entries
    }

    /** The SHA-256 of [file] in hex. */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { s ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** The SHA-256 in hex of [parts] joined by newlines. */
    fun sha256(parts: List<String>): String =
        MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
