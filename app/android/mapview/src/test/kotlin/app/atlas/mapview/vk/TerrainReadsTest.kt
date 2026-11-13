package app.atlas.mapview.vk

import app.atlas.mapcore.MapCore
import app.atlas.tiles.TerrainFetch
import app.atlas.tiles.TerrainList
import app.atlas.tiles.WorldIndex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The terrain's ranges as TerrainReads asks for them (ENGINE.md part 6, step 2): the core's wants of a pack its region's
 * index holds at their block and identity, merged as the world's are (WorldReadsTest), each range with its pack, so a
 * block two regions hold has each region's ranges from its own index; another release's wants, those of a region whose
 * index has not come, and refused tiles left out.
 */
class TerrainReadsTest {
    private val nl = ByteArray(32) { 1 }
    private val de = ByteArray(32) { 2 }
    private val block = MapCore.Window(9, 262, 168)

    private fun region(id: String) = TerrainList.Region(id, 50.0, 3.0, 54.0, 8.0, TerrainList.Index("$id-terrain.idx", 100, "00".repeat(32)))

    private val packs = listOf(
        TerrainFetch.Pack(region("netherlands"), WorldIndex.Entry(9, 262, 168, 12, 14, 0, 0, 1_000_000, 21, IntArray(4), nl)),
        TerrainFetch.Pack(region("germany"), WorldIndex.Entry(9, 262, 168, 12, 14, 0, 0, 1_000_000, 21, IntArray(4), de)),
    )

    private fun want(identity: ByteArray, x: Int, offset: Long, length: Long, at: MapCore.Window = block) =
        MapCore.WorldWant(at, identity, MapCore.Window(14, x, 0), offset, length)

    /** Each range as its pack's id, its bytes, its tiles and its rank. */
    private fun runs(wants: List<MapCore.WorldWant>, refused: Set<String> = emptySet()) =
        TerrainReads.runsOf(wants, packs, refused).map { (r, p) -> "${p.id} ${r.start}-${r.end} ${r.tiles} ${r.rank}" }

    @Test
    fun eachWantGoesToThePackOfItsBlockAndIdentity() {
        assertEquals(
            listOf("terrain/netherlands-terrain/9/262/168 1000-3000 2 0", "terrain/germany-terrain/9/262/168 1000-2000 1 1"),
            runs(listOf(want(nl, 1, 2000, 1000), want(de, 2, 1000, 1000), want(nl, 3, 1000, 1000))),
        )
    }

    @Test
    fun anotherReleasesAnUnheldRegionsAndRefusedWantsAreLeftOut() {
        val refused = setOf(WorldReads.refusedKey(nl, MapCore.Window(14, 2, 0)))
        assertEquals(
            listOf("terrain/netherlands-terrain/9/262/168 0-100 1 0"),
            runs(
                listOf(
                    want(nl, 1, 0, 100),
                    want(ByteArray(32) { 9 }, 5, 200, 100), // another release's pack
                    want(nl, 2, 300, 100), // its bytes refused before
                    want(nl, 3, 0, 100, at = MapCore.Window(9, 300, 168)), // a block no index on the phone has
                    want(nl, 4, 0, 100, at = MapCore.Window(6, 262, 168)), // not a terrain block
                ),
                refused,
            ),
        )
    }
}
