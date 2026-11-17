package app.atlas.mapcorecheck.load

import app.atlas.anchor.AnchorCases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stage P's cut of the canal ring holds nothing that cannot be there (the user, 16 Nov 2026): its raw.json, loaded by the
 * core as a region (whole, and at each tolerance its render pack's views and the live map's bands load it at, for the
 * variants) and cut into render tiles by the core (`CP/load/Cut.hpp`), cuts every tile the bundled pack (`-Datlas.tiles`,
 * the port's, of the same element pack AnchorCases unpacks) holds, with its variants, every layer the buildings' cleaning
 * does not touch still the port's line for line ([LoadHost.TOUCHED]); and its validity counts
 * (`CP/load/Clean.hpp`, Scene::counts) may only fall: no two drawn footprints overlapping in plan and in height, no outline
 * left uncovered, and no more pitched roofs drawn flat than [FLAT_ROOFS]. The fix's own counts are printed with them.
 */
class CutValidityTest {
    @Test
    fun theCanalRingHoldsNothingThatCannotBeThere() {
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
        assertTrue("the core does not cut ${grachten.id}: $summary", summary.startsWith("tiles="))
        val said = summary.split(' ').mapNotNull { w -> w.split('=', limit = 2).takeIf { it.size == 2 } }.associate { it[0] to it[1] }
        fun count(key: String) = said[key]?.toLongOrNull() ?: error("the cut says no $key: $summary")
        assertEquals("the pack's tiles the core cut none of: $summary", 0L, count("missing"))
        assertEquals("layers the buildings' cleaning does not touch that are not the port's: $summary", emptyList<String>(), LoadHost.untouchedThatDiffer(summary))
        assertTrue("the canal ring's cut has no variants: $summary", count("variants") > 0)
        assertEquals("drawn footprints overlapping in plan and in height: $summary", 0L, count("pairs"))
        assertEquals("outlines left uncovered: $summary", 0L, count("uncovered"))
        assertTrue("more pitched roofs drawn flat than the $FLAT_ROOFS recorded: $summary", count("flat") <= FLAT_ROOFS)
        assertEquals("cleanings that did not settle: $summary", 0L, count("unsettled"))
        assertEquals("outlines whose uncovered area could not be measured: $summary", 0L, count("unmeasured"))
    }

    companion object {
        /**
         * The canal ring's mapped pitched roofs the core draws flat, recorded 17 Nov 2026 (core-p14): shapes it does not
         * build (double_saltbox, many), and 168 of them over a piece of the footprint that has a courtyard, until the
         * roofs are built from any footprint (a piece without one takes the building's roof by itself). Lower it as
         * they are built; never raise it.
         */
        const val FLAT_ROOFS = 836L
    }
}
