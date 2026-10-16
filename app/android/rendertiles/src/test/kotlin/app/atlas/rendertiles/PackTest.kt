package app.atlas.rendertiles

import app.atlas.render.Amsterdam
import app.atlas.render.Regions
import app.atlas.render.live.EngineVersion
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.Lod
import app.atlas.render.live.Moment
import app.atlas.rendertiles.codec.TileFormatException
import app.atlas.rendertiles.compile.SceneToTiles
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.Keep
import app.atlas.rendertiles.model.Profile
import app.atlas.rendertiles.model.Reach
import app.atlas.rendertiles.model.RenderHead
import app.atlas.rendertiles.model.StyleVersion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The bundled packs as files: two builds byte-identical; their sizes; the `rhead` and `style` sections against the
 * port; the manifest; damage found; and loading a scene for the packer leaves what the engine renders as it was.
 */
class PackTest {
    private fun sets(): List<Pair<String, File>> = listOf("westerkerk" to Packs.westerkerkPack, "grachten" to Packs.grachtenPack).filter { it.second.isFile }

    @Test
    fun `two builds are byte-identical`() {
        assumeTrue(sets().isNotEmpty())
        for ((name, file) in sets()) {
            val first = Packs.build(file, name)
            val again = File(Packs.work, "$name-again.atlas")
            SceneToTiles.pack(file, again, File(Packs.work, "engine"))
            assertArrayEquals("$name: a second build", first.file.readBytes(), again.readBytes())
            println("PACKTEST $name: two builds byte-identical (${again.length()} bytes)")
            again.delete()
        }
    }

    @Test
    fun `the head and the style are the port's`() {
        for ((name, file) in sets()) {
            val b = Packs.build(file, name)
            val r = b.reader
            val region = Packs.region(file)
            val scene = Packs.scene(region)
            scene.state.restore()
            val h = r.head
            assertEquals(if (name == "westerkerk") Profile.LIVING else Profile.REGION, h.profile)
            assertEquals(region.id, h.region)
            assertEquals(Amsterdam.LAT0.toRawBits(), h.lat0.toRawBits()); assertEquals(Amsterdam.LON0.toRawBits(), h.lon0.toRawBits())
            assertEquals(Amsterdam.KX.toRawBits(), h.kx.toRawBits()); assertEquals(Amsterdam.KZ.toRawBits(), h.kz.toRawBits())
            assertEquals(region.frame.kx.toRawBits(), h.kx.toRawBits())
            assertEquals(region.ladder.knots.map { it.name }, h.views.map { it.name })
            val living = region.kind == LiveRegion.Kind.LIVING
            for ((k, v) in region.ladder.knots.zip(h.views)) {
                assertEquals(k.cam, v.camera)
                // each view's tolerance, as regions.load takes it for that view (GENERALISE); the living map's is 0
                assertEquals("${k.name}'s tolerance", (if (living) 0.0 else Lod.ofView(region.id, k.name)) as Any, Keep.TOLERANCES[v.tolerance] as Any)
            }
            // the bands, against the port's Lod: the tolerance the engine loads at, for any camera distance
            if (living) assertEquals(listOf(RenderHead.Band(0, null)), h.bands)
            else assertEquals(Lod.BANDS.indices.map { RenderHead.Band(Keep.indexOf(Lod.BANDS[it]), Lod.UPTO.getOrNull(it)) }, h.bands)
            val distances = (0..4000).map { it * 1.0 } + Lod.UPTO.flatMap { listOf(Math.nextDown(it), it, Math.nextUp(it)) } + listOf(1e9)
            for (d in distances) {
                assertEquals("band at $d m", (if (living) 0.0 else Lod.band(d)) as Any, Keep.TOLERANCES[h.toleranceOf(null, d)] as Any)
                for (v in h.views) assertEquals("${v.name} at $d m", (if (living) 0.0 else Lod.tolerance(region.id, v.name, d)) as Any, Keep.TOLERANCES[h.toleranceOf(v.name, d)] as Any)
            }
            assertEquals(EngineVersion.ID, h.engineVersion)
            assertEquals(StyleVersion.ID, h.styleVersion)
            assertEquals(SceneToTiles.sha256(file), h.source)
            assertArrayEquals(SceneToTiles.levelsOf(region), h.levels)
            val s = r.style
            assertEquals(Keep.TOLERANCES.toList(), s["keep"])
            assertEquals(Regions.LAND.entries.associate { (k, v) -> k to listOf(v.first, v.second) }, s["land"])
            assertEquals(Amsterdam.SURF, s["surf"]); assertEquals(Amsterdam.MATERIAL, s["material"])
            @Suppress("UNCHECKED_CAST")
            val state = s["state"] as Map<String, Any?>
            assertEquals(Amsterdam.WATER_Y, state["water_y"]); assertEquals(Amsterdam.QUAY_COL, state["quay_col"]); assertEquals(Amsterdam.TONE, state["tone"])
            @Suppress("UNCHECKED_CAST")
            assertEquals(Amsterdam.BRICK_OLD.toList(), (s["palette"] as Map<String, Any?>)["brick_old"])
            // the manifest, for Kotlin and the tools
            val m = r.manifest
            assertEquals(Format.VERSION, m.version)
            assertEquals(Format.LAYOUT, m.json["layout"]!!.jsonPrimitive.content)
            assertEquals(h.levels.toList(), (m.json["levels"] as JsonArray).map { (it as JsonPrimitive).content.toInt() })
            assertEquals(r.chunks.size, m.json["chunks"]!!.jsonPrimitive.content.toInt())
            assertEquals(b.written.contentSha256, m.json["content_sha256"]!!.jsonPrimitive.content)
            // the sections, in the directory's order, and a reach for every chunk
            assertEquals(listOf(Format.SECTION_TILES, Format.SECTION_REACH, Format.SECTION_CHUNKS, Format.SECTION_HEAD, Format.SECTION_STYLE), r.sections.keys.toList())
            assertEquals(r.chunks.size, r.reach.size)
            assertEquals(Reach.HEADER_BYTES + r.chunks.size * Reach.ENTRY_BYTES.toLong(), r.sections.getValue(Format.SECTION_REACH).stored)
            println("PACKTEST $name: head (${h.views.size} views at ladder ${h.views.map { it.tolerance }}, bands ${h.bands}, ${h.engineVersion}, ${h.styleVersion}), reach (${r.reach.size}) and style (${s.tables.size} tables) are the port's")
        }
    }

    @Test
    fun `a damaged pack is found out`() {
        assumeTrue(Packs.westerkerkPack.isFile)
        val b = Packs.build(Packs.westerkerkPack, "westerkerk")
        val bytes = b.file.readBytes()
        val c = b.reader.chunks.maxBy { it.stored }
        // a flipped byte in a chunk: its section's CRC, or its SHA-256 for a reader by ranges, which cannot check the CRC
        val bad = bytes.copyOf()
        bad[(c.offset + c.stored / 2).toInt()] = (bad[(c.offset + c.stored / 2).toInt()].toInt() xor 0x40).toByte()
        for (crc in listOf(true, false)) try {
            val r = Format3Reader(bad, checkChunksCrc = crc)
            r.raw(r.chunks.first { it.offset == c.offset })
            throw AssertionError("a damaged chunk read (chunks' CRC checked: $crc)")
        } catch (e: TileFormatException) {
        } catch (e: com.github.luben.zstd.ZstdException) {
        }
        // a pack cut short
        try {
            Format3Reader(bytes.copyOf(bytes.size - 100))
            throw AssertionError("a short pack read")
        } catch (e: TileFormatException) {
        } catch (e: app.atlas.render.data.PackFormatException) {
        }
    }

    /** Loading a scene for the packer changes nothing the engine renders: the fixed renders keep their md5s. */
    @Test
    fun `loading scenes leaves the engine's renders as they were`() {
        assumeTrue(Packs.westerkerkPack.isFile)
        val before = LiveEngine.workDir
        LiveEngine.workDir = File(Packs.work, "engine")
        try {
            val region = Packs.region(Packs.westerkerkPack)
            val fixed = EngineVersion.FIXED.first { it.name == "westerkerk-sunny" }
            fun md5() = EngineVersion.md5(LiveEngine.render(region, fixed.liveView(region), Moment.Named(fixed.moment))!!)
            LiveEngine.forget()
            val a = md5()
            assertEquals("the engine draws the Westerkerk as EngineVersion says", EngineVersion.OUTPUTS[fixed.name], a)
            Packs.scene(region)
            if (Packs.grachtenPack.isFile) {
                val g = Packs.region(Packs.grachtenPack)
                Packs.scene(g)
                Packs.scene(g, null, 0.7)
            }
            assertEquals("after loadScene, the kept scene", a, md5())
            LiveEngine.forget()
            assertEquals("after loadScene, a fresh load", a, md5())
        } finally {
            LiveEngine.forget()
            LiveEngine.workDir = before
        }
    }

}
