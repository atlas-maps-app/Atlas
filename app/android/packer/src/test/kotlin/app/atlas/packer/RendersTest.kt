package app.atlas.packer

import app.atlas.render.data.DirectoryRegion
import app.atlas.render.data.ElementType
import app.atlas.render.data.OverpassJsonFile
import app.atlas.render.data.PackReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The renders' own data as packs: what the Python reads from data/regions and data/ams comes back out of the pack. */
class RendersTest {
    @get:Rule val tmp = TemporaryFolder()

    private val repo = File(System.getProperty("atlas.repo") ?: error("atlas.repo is not set"))

    @Test
    fun places_py_is_read_as_json() {
        val places = Renders.places(repo)
        val sg = places["sangimignano"] as JsonObject
        assertEquals("San Gimignano", (sg["title"] as JsonPrimitive).content)
        assertEquals("it_tuscan", (sg["tradition"] as JsonPrimitive).content)
        assertEquals(4, (sg["bbox"] as JsonArray).size)
        assertNotNull(((sg["views"] as JsonObject)["close"] as JsonObject)["target"])
        assertTrue(places.size >= 11)
    }

    @Test
    fun a_region_pack_carries_its_config_dem_weather_and_elements() {
        val raw = File(repo, "data/regions/sangimignano/raw.json")
        assumeTrue(raw.isFile)
        val out = tmp.newFolder("packs")
        val r = Renders.build(repo, out, setOf("sangimignano")).single()
        assertEquals("sangimignano", r.id)
        PackReader.open(r.file).use { p ->
            assertTrue(p.verify().isEmpty())
            val places = (Renders.places(repo)["sangimignano"] as JsonObject)
            assertEquals(places["bbox"], p.config["bbox"])
            assertEquals(places["views"], p.config["views"])
            assertEquals("Europe/Rome", p.manifest.timezone)
            assertEquals("it_tuscan", p.manifest.tradition)
            // the files the Python reads next to raw.json
            val dir = File(repo, "data/regions/sangimignano")
            assertEquals(Json.parseToJsonElement(File(dir, "wx.json").readText()), p.aux("wx"))
            assertEquals(app.atlas.render.data.Dem.read(File(dir, "dem.json")), p.dem("dem"))
            // lossless
            assertEquals(OverpassJsonFile(raw).elements().toList(), p.elements().toList())
            assertEquals(OverpassJsonFile(raw).dataTime, p.manifest.dataTime)
            assertTrue(p.query(p.manifest.bbox).isNotEmpty())
            assertTrue(p.elements().any { it.type == ElementType.RELATION })
        }
    }

    @Test
    fun a_pack_reads_back_through_the_directory_loader_adapter_too() {
        val dir = File(repo, "data/regions/nottinghill")
        assumeTrue(File(dir, "raw.json").isFile)
        val d = DirectoryRegion(dir, "nottinghill", Renders.places(repo)["nottinghill"] as JsonObject)
        assertEquals("Notting Hill", (d.config["title"] as JsonPrimitive).content)
        assertEquals(5683, d.elements.elements().count())
    }

    @Test
    fun amsterdam_is_packed_from_data_ams() {
        val raw = File(repo, "data/ams/raw.json")
        assumeTrue(raw.isFile)
        val r = Renders.build(repo, tmp.newFolder("ams"), setOf("amsterdam")).single()
        PackReader.open(r.file).use { p ->
            assertEquals("amsterdam", p.id)
            assertEquals("nl_amsterdam", p.manifest.tradition)
            assertEquals(OverpassJsonFile(raw).elements().toList(), p.elements().toList())
            assertNotNull(p.aux("wx"))
        }
    }

    @Test
    fun a_pack_in_the_release_directory_becomes_a_packs_json_entry() {
        val raw = File(repo, "data/regions/nottinghill/raw.json")
        assumeTrue(raw.isFile)
        val out = tmp.newFolder("rel")
        Renders.build(repo, out, setOf("nottinghill"))
        val list = Manifest.build(out, "https://github.com/example/repo/releases/download/packs-renders")
        val pack = (list["packs"] as JsonArray).single() as JsonObject
        assertEquals("nottinghill", (pack["id"] as JsonPrimitive).content)
        val file = (pack["files"] as JsonArray).single() as JsonObject
        assertEquals("atlas", (file["kind"] as JsonPrimitive).content)
        assertEquals("https://github.com/example/repo/releases/download/packs-renders/nottinghill.atlas", (file["url"] as JsonPrimitive).content)
        assertNotNull(pack["osm_timestamp"])
        assertNotNull(pack["bbox"])
    }
}
