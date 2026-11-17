package app.atlas.mapcorecheck.load

import app.atlas.anchor.AnchorCases
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stage P's cut held to the packer (ENGINE.md step 3): the canal ring's raw.json, loaded by the core as a region (whole, and at
 * each tolerance its render pack's views and the live map's bands load it at, for the variants) and cut into render tiles by
 * the core (`CP/load/Cut.hpp`), gives every tile of the packer's render pack of it, byte for byte, both levels (L14 and L12),
 * keep codes, variants, land cover, lines, items, quays and islands among them. The packer's pack is the bundled tiles'
 * (`-Datlas.tiles`), made from the same element pack AnchorCases unpacks.
 */
class CutParityTest {
    @Test
    fun theCoreCutsTheCanalRingAsThePackerDoes() {
        val grachten = AnchorCases.grachten()
        if (grachten == null) {
            println("cut: the canal ring's pack is not on this machine")
            return
        }
        val tiles = System.getProperty("atlas.tiles") ?: error("atlas.tiles names no bundled render packs")
        val pack = File(tiles, "grachten.atlas")
        assertTrue("no render pack of the canal ring at ${pack.path}", pack.isFile)
        val summary = LoadHost.cutRegion(File(grachten.dir, "raw.json").path, File(grachten.dir, "region.json").path, pack.path, grachten.id)
        println("cut ${grachten.id}: $summary")
        assertTrue("the core's cut of ${grachten.id} is not the packer's: $summary", summary.startsWith("tiles=") && " wrong=0 " in summary)
        val variants = Regex("variants=(\\d+)").find(summary)?.groupValues?.get(1)?.toInt() ?: 0
        assertTrue("the canal ring's cut has no variants: $summary", variants > 0)
    }
}
