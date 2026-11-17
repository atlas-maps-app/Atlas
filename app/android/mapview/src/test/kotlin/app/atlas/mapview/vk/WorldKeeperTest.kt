package app.atlas.mapview.vk

import app.atlas.mapcore.KeptWorld
import app.atlas.tiles.WorldFetch
import app.atlas.tiles.WorldIndex
import app.atlas.tiles.WorldKeepList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The world a kept country keeps (ENGINE.md step 11): which packs and tiles the core is asked to keep, and the ranges fetched. */
class WorldKeeperTest {
    private fun entry(z: Int, x: Int, y: Int, length: Long, seed: Int) =
        WorldIndex.Entry(z, x, y, if (z == 6) 9 else 6, if (z == 6) 11 else 8, 0, 0, length, 85, intArrayOf(1, 2, 3, 4), ByteArray(32) { (it + seed).toByte() })

    private val index = WorldIndex(
        Instant.parse("2026-11-02T21:21:26Z"), listOf(WorldIndex.Part("world-mid.000", 9_000)),
        listOf(entry(6, 32, 21, 4_000, 1), entry(6, 33, 21, 3_000, 2), entry(3, 4, 2, 2_000, 3)),
    )

    private fun pack(z: Int, x: Int, y: Int, head: Long, vararg tiles: WorldKeepList.Tile) = WorldKeepList.Pack(z, x, y, head, head + 100L * tiles.size, tiles.toList())

    private val list = WorldKeepList(
        "world-20261102", 100, "0".repeat(64),
        listOf(
            WorldKeepList.Country(
                "netherlands", "Netherlands", 3_000, 4,
                listOf(
                    pack(6, 32, 21, 1_390, WorldKeepList.Tile(9, 262, 168), WorldKeepList.Tile(11, 1050, 673)),
                    pack(3, 4, 2, 380, WorldKeepList.Tile(6, 32, 21), WorldKeepList.Tile(7, 65, 42)),
                ),
            ),
            WorldKeepList.Country(
                "belgium", "Belgium", 2_000, 3,
                listOf(
                    // a pack the Netherlands keeps too, with a tile of its own and one they share; one of its own; one the index lacks
                    pack(3, 4, 2, 380, WorldKeepList.Tile(7, 65, 42), WorldKeepList.Tile(7, 65, 43)),
                    pack(6, 33, 21, 700, WorldKeepList.Tile(10, 530, 340)),
                    pack(6, 40, 21, 500, WorldKeepList.Tile(10, 640, 340)),
                ),
            ),
        ),
    )

    @Test
    fun theKeptCountriesPacksEachOnceWithTheTilesOfAllOfThem() {
        val one = WorldKeeper.packsOf(list, index, setOf("netherlands"))
        assertEquals(listOf("6/32/21", "3/4/2"), one.map { "${it.entry.z}/${it.entry.x}/${it.entry.y}" })
        assertEquals(listOf(1_390L, 380L), one.map { it.head })
        assertEquals(listOf(KeptWorld.key(9, 262, 168), KeptWorld.key(11, 1050, 673)), one[0].kept.tiles.toList())
        assertEquals(4_000L, one[0].kept.length)
        assertTrue(index.find(6, 32, 21)!!.sha256.contentEquals(one[0].kept.identity))
        // both: the pack they share once, its tiles those of either; Belgium's own; the pack the index lacks left out
        val both = WorldKeeper.packsOf(list, index, setOf("netherlands", "belgium", "atlantis"))
        assertEquals(listOf("3/4/2", "6/33/21", "6/32/21"), both.map { "${it.entry.z}/${it.entry.x}/${it.entry.y}" })
        assertEquals(listOf(KeptWorld.key(7, 65, 42), KeptWorld.key(7, 65, 43), KeptWorld.key(6, 32, 21)), both[0].kept.tiles.toList())
        assertTrue(WorldKeeper.packsOf(list, index, emptySet()).isEmpty())
    }

    @Test
    fun theLackingChunksAreFetchedInRangesAsTheyLieInTheirPacks() {
        fun chunk(pack: Int, offset: Long, length: Long) = KeptWorld.Chunk(pack, KeptWorld.key(9, 0, 0), offset, length)
        val gap = WorldFetch.MERGE_GAP
        val runs = WorldKeeper.runsOf(
            listOf(
                chunk(0, 500_000, 1_000), chunk(0, 10_000, 2_000), chunk(0, 12_000 + gap, 500), // the last two a gap apart: one range
                chunk(0, 12_500 + 2 * gap + 1, 700), // more than a gap after them: its own
                chunk(1, 100, 300),
                chunk(1, 400, WorldFetch.MAX_RANGE), // with the one before it past the most a range holds: its own
            ),
        )
        assertEquals(
            listOf(
                Triple(0, 10_000L, 12_500L + gap), Triple(0, 12_500L + 2 * gap + 1, 13_200L + 2 * gap + 1), Triple(0, 500_000L, 501_000L),
                Triple(1, 100L, 400L), Triple(1, 400L, 400L + WorldFetch.MAX_RANGE),
            ),
            runs.map { Triple(it.pack, it.start, it.end) },
        )
        // a range's bytes are its chunks' (what lies between them comes too, and is kept by the cache as it may)
        assertEquals(listOf(2_500L, 700L, 1_000L, 300L, WorldFetch.MAX_RANGE), runs.map { it.bytes })
        assertTrue(WorldKeeper.runsOf(emptyList()).isEmpty())
    }
}
