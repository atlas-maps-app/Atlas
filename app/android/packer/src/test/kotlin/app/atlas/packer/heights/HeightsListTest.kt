package app.atlas.packer.heights

import app.atlas.packer.UsageException
import app.atlas.tiles.TerrainList
import app.atlas.tiles.WorldIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * `packer heights-list` (ENGINE.md part 6, step 2): the list the app reads of a terrain release, from the indexes
 * `packer heights` wrote and the registry the repository carries. It names the release and where its files are, the
 * sources its regions draw from by their short names and credits, and each region's index by its bytes and SHA-256 with
 * the box its packs make together; the app's own reader takes it; an index of a region the registry builds no terrain
 * for, or a part beside an index of another length than it says, is refused.
 */
class HeightsListTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val registry get() = TerrainSources.read(File(System.getProperty("atlas.repo"), "app/android/packs/terrain-sources.json"))

    /** An index of [region] with packs of the L9 blocks [blocks] (south, west, north, east on the 1e-7° grid each), in one part of [part] bytes. */
    private fun index(dir: File, region: String, part: Long, vararg blocks: IntArray): File {
        val entries = blocks.mapIndexed { i, b -> WorldIndex.Entry(9, 260 + i, 168, 12, 14, 0, i * 100L, 100, 21, b, ByteArray(32) { i.toByte() }) }
        val f = File(dir, "$region-terrain.idx")
        f.outputStream().use { WorldIndex(Instant.parse("2026-11-12T00:00:00Z"), listOf(WorldIndex.Part("$region-terrain.000", part)), entries).write(it) }
        return f
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun theListNamesTheReleaseItsSourcesAndEachRegionsIndexAndBox() {
        val dir = tmp.newFolder("terrain")
        val nl = index(dir, "netherlands", 200, intArrayOf(520_000_000, 39_000_000, 525_000_000, 46_000_000), intArrayOf(518_000_000, 46_000_000, 524_000_000, 53_000_000))
        File(dir, "netherlands-terrain.000").writeBytes(ByteArray(200))
        val no = index(dir, "norway", 100, intArrayOf(600_000_000, 50_000_000, 605_000_000, 57_000_000))
        File(dir, "netherlands-terrain.stats.json").writeText("{}")
        val out = File(tmp.root, "out/terrain.json")
        val lines = ArrayList<String>()
        val code = app.atlas.packer.run(
            listOf("heights-list", dir.path, out.path, "--release", "terrain-20261113", "--url", "https://github.com/atlas-maps-app/Atlas/releases/download/terrain-20261113",
                "--repo", System.getProperty("atlas.repo")),
            lines::add,
        )
        assertEquals(0, code)
        assertTrue(lines.toString(), lines.last().endsWith("terrain.json: 2 regions of terrain-20261113"))
        // the app's reader takes it as written
        val list = TerrainList.read(out.readText())
        assertEquals("terrain-20261113", list.release)
        assertEquals("https://github.com/atlas-maps-app/Atlas/releases/download/terrain-20261113/", list.url)
        // the sources the two regions draw from, in the registry's order, each by its short name and with its credit
        assertEquals(listOf("glo30", "ahn4", "kartverket-dtm1"), list.sources.map { it.id })
        assertEquals(listOf("Copernicus", "AHN", "Kartverket"), list.sources.map { it.name })
        assertEquals(registry.source("ahn4").credit, list.source("ahn4")!!.credit)
        assertEquals(listOf("netherlands", "norway"), list.regions.map { it.id })
        val r = list.region("netherlands")!!
        assertEquals(listOf(51.8, 3.9, 52.5, 5.3), listOf(r.south, r.west, r.north, r.east))
        assertEquals("netherlands-terrain.idx", r.index.name)
        assertEquals(nl.length(), r.index.bytes)
        assertEquals(sha(nl), r.index.sha256)
        assertEquals(sha(no), list.region("norway")!!.index.sha256)
        assertTrue(out.readText().contains("\"format\": 1"))
    }

    @Test
    fun aRegionTheRegistryDoesNotBuildOrAPartOfAnotherLengthIsRefused() {
        val dir = tmp.newFolder("terrain")
        index(dir, "netherlands", 200, intArrayOf(520_000_000, 39_000_000, 525_000_000, 46_000_000))
        File(dir, "netherlands-terrain.000").writeBytes(ByteArray(199))
        assertThrows(IllegalArgumentException::class.java) { HeightsList.list(dir, registry, "terrain-20261113", "https://example.org/r/") }
        File(dir, "netherlands-terrain.000").delete()
        index(dir, "atlantis", 100, intArrayOf(0, 0, 10, 10))
        assertThrows(IllegalArgumentException::class.java) { HeightsList.list(dir, registry, "terrain-20261113", "https://example.org/r/") }
        assertThrows(UsageException::class.java) { HeightsList.list(tmp.newFolder("empty"), registry, "terrain-20261113", "https://example.org/r/") }
        assertThrows(UsageException::class.java) { app.atlas.packer.run(listOf("heights-list", dir.path, File(tmp.root, "t.json").path, "--release", "t"), {}) }
    }
}
