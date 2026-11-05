package app.atlas.mapview.vk

import app.atlas.mapcore.MapCore
import app.atlas.tiles.WorldIndex
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * The world's ranges as WorldReads asks for them (ENGINE.md step 6, "Fetch and keep"): the core's wants of a pack the
 * index holds at their identity, neighbours in the pack's order merged into one range within MERGE_GAP of each other
 * up to MAX_RANGE, each range ranked by its nearest want; another version's wants, refused tiles and ranges outside a
 * pack left out.
 */
class WorldReadsTest {
    private val a = ByteArray(32) { 1 }
    private val b = ByteArray(32) { 2 }
    private val blockA = MapCore.Window(6, 32, 21)
    private val blockB = MapCore.Window(6, 33, 21)
    private val index = WorldIndex(
        Instant.EPOCH,
        listOf(WorldIndex.Part("world-mid.000", 100_000_000)),
        listOf(
            WorldIndex.Entry(6, 32, 21, 9, 11, 0, 0, 50_000_000, 85, IntArray(4), a),
            WorldIndex.Entry(6, 33, 21, 9, 11, 0, 50_000_000, 50_000_000, 85, IntArray(4), b),
        ),
    )

    private fun want(block: MapCore.Window, identity: ByteArray, x: Int, offset: Long, length: Long) =
        MapCore.WorldWant(block, identity, MapCore.Window(11, x, 0), offset, length)

    /** Each range as its pack's block x, its bytes, its tiles and its rank. */
    private fun runs(wants: List<MapCore.WorldWant>, refused: Set<String> = emptySet()) =
        WorldReads.runsOf(wants, index, refused).map { "${it.block.x} ${it.start}-${it.end} ${it.tiles} ${it.rank}" }

    @Test
    fun neighboursInAPacksOrderAreOneRangeRankedByItsNearestWant() {
        // the core's order is nearest first, a pack's its offsets: the first and last wants are neighbours in A
        val gap = WorldReads.MERGE_GAP
        assertEquals(
            listOf("32 1000-3000 2 0", "33 5000-6000 1 1", "32 ${3001 + gap}-${4001 + gap} 1 2"),
            runs(listOf(want(blockA, a, 1, 2000, 1000), want(blockB, b, 2, 5000, 1000), want(blockA, a, 3, 3001 + gap, 1000), want(blockA, a, 4, 1000, 1000))),
        )
        // a gap of MERGE_GAP is fetched with them
        assertEquals(listOf("32 0-${2000 + gap} 2 0"), runs(listOf(want(blockA, a, 1, 0, 1000), want(blockA, a, 2, 1000 + gap, 1000))))
        // no range longer than MAX_RANGE
        val max = WorldReads.MAX_RANGE
        assertEquals(
            listOf("32 0-$max 2 0", "32 $max-${max + 1000} 1 2"),
            runs(listOf(want(blockA, a, 1, 0, max / 2), want(blockA, a, 2, max / 2, max / 2), want(blockA, a, 3, max, 1000))),
        )
    }

    @Test
    fun anotherVersionsRefusedOrOutOfPlaceWantsAreLeftOut() {
        val refused = setOf(WorldReads.refusedKey(a, MapCore.Window(11, 2, 0)))
        assertEquals(
            listOf("32 0-100 1 0"),
            runs(
                listOf(
                    want(blockA, a, 1, 0, 100),
                    want(blockA, ByteArray(32) { 9 }, 5, 200, 100), // another version's pack
                    want(blockA, a, 2, 300, 100), // its bytes refused before
                    want(blockA, a, 3, 50_000_000 - 10, 100), // past its pack
                    want(blockA, a, 4, -1, 10), // before it
                    want(blockA, a, 6, 500, 0), // empty
                    want(MapCore.Window(6, 40, 21), a, 7, 0, 100), // a block the index has not
                ),
                refused,
            ),
        )
        // the same tile of another version is not the refused one
        assertEquals(listOf("33 300-400 1 0"), runs(listOf(want(blockB, b, 2, 300, 100)), refused))
    }
}
