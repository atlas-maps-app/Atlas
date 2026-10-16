package app.atlas.packer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** `packer render-tiles`: its command line, the canal ring's fetch, and the bundled set as Gradle makes it. */
class RenderTilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val repo = File(System.getProperty("atlas.repo") ?: error("atlas.repo is not set"))

    @Test
    fun `a bad command line is a usage error`() {
        for (args in listOf(listOf("render-tiles"), listOf("render-tiles", "--bundled"), listOf("render-tiles", "--bundled", "a", "b"), listOf("render-tiles", "--nope", "x"), listOf("render-tiles", "--renders", "nope.atlas", "out.atlas"))) {
            try {
                RenderTiles.run(args) {}
                fail("$args ran")
            } catch (e: UsageException) {
            }
        }
    }

    @Test
    fun `the canal ring is taken from the cache only when its hash is the published one`() {
        val dir = tmp.newFolder("packs")
        val src = RenderTiles.Source("grachten", "https://127.0.0.1:1/grachten.atlas", "00".repeat(32), 5)
        File(dir, "grachten.atlas").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        try {
            RenderTiles.fetch(src, dir)
            fail("a cached file of another hash was used")
        } catch (e: IOException) {
        }
        val cached = File(repo, "cache/packs/regions/grachten.atlas")
        assumeTrue(cached.isFile)
        assertEquals(cached.canonicalFile, RenderTiles.fetch(RenderTiles.CANAL_RING, cached.parentFile).canonicalFile)
        // the pinned hash is packs.json's
        val packs = File(repo, "cache/packs/regions/packs.json")
        if (packs.isFile) {
            val entry = (Json.parseToJsonElement(packs.readText()) as JsonObject)["packs"] as JsonArray
            val g = entry.map { it as JsonObject }.first { it["id"]!!.jsonPrimitive.content == "grachten" }
            val f = (g["files"] as JsonArray).first() as JsonObject
            assertEquals(f["sha256"]!!.jsonPrimitive.content, RenderTiles.CANAL_RING.sha256)
            assertEquals(f["url"]!!.jsonPrimitive.content, RenderTiles.CANAL_RING.url)
            assertEquals(f["bytes"]!!.jsonPrimitive.content.toLong(), RenderTiles.CANAL_RING.bytes)
        }
    }

    @Test
    fun `the bundled set is made with its index`() {
        assumeTrue(File(repo, "cache/packs/regions/grachten.atlas").isFile)
        val out = tmp.newFolder("tiles")
        val lines = ArrayList<String>()
        assertEquals(0, RenderTiles.run(listOf("render-tiles", "--bundled", out.path, "--repo", repo.path, "--work", tmp.newFolder("work").path), lines::add))
        val index = Json.parseToJsonElement(File(out, RenderTiles.INDEX).readText()) as JsonObject
        val packs = (index["packs"] as JsonArray).map { it as JsonObject }
        assertEquals(listOf("amsterdam", "grachten"), packs.map { it["id"]!!.jsonPrimitive.content })
        for (p in packs) {
            val f = File(out, p["file"]!!.jsonPrimitive.content)
            assertEquals(f.length(), p["bytes"]!!.jsonPrimitive.content.toLong())
            assertEquals(RenderTiles.sha256(f), p["sha256"]!!.jsonPrimitive.content)
            val head = f.readBytes().copyOf(10)
            assertEquals("ATLASPCK", String(head, 0, 8, Charsets.US_ASCII))
            assertEquals(3, head[9].toInt())
        }
        assertEquals(setOf("amsterdam.atlas", "grachten.atlas", RenderTiles.INDEX), out.list()!!.toSet())
        println("RENDERTILES ${lines.joinToString(" | ")}")
        assertTrue(packs.first { it["id"]!!.jsonPrimitive.content == "grachten" }["profile"]!!.jsonPrimitive.content == "region")
    }
}
