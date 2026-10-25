package app.atlas.mapcorecheck.load

import app.atlas.anchor.AnchorCases
import app.atlas.render.Amsterdam
import app.atlas.render.Regions
import app.atlas.render.py.truthy
import app.atlas.render.data.PackReader
import app.atlas.render.live.LiveEngine
import app.atlas.render.py.Dict
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stage P held to the port (ENGINE.md step 3): a region's load in the core (`CP/load/Regions.hpp`) gives every record and
 * field the port's `Regions.load` gives: the buildings and parts mapped as relations with generic.py's looks (a region's
 * tradition read from the same file as the port's, its details and party walls among them), land cover, runways, big
 * roads, railways, power lines and towers, marine works, cranes, masts, turbines, national parks, the water mapped as
 * relations and its islands, the sea from the coastline and the natural shores, a theme park's coaster tracks (their
 * rides' profiles as the element pack holds them) and a park's or a zoo's woods (as the engine plants them, once for the region:
 * rendertiles' PlantedWoods, the port's own planting about the frame's origin), in the port's order and to the bit.
 *
 * Each region is loaded as the packer loads it ([LiveEngine.loadScene]: whole, at tolerance 0): the canal ring from its
 * bundled pack, and the element packs `-Datlas.load.packs` names (a list split by the path separator: the regions'
 * packs on the Mac, cache/packs/regions). A survey's water levels and relief add keys these lists leave alone; the
 * regions drawn on a survey's ground (the dunes, San Gimignano) wait for the terrain.
 */
class RegionsParityTest {
    /** Holds the core's load of [raw] to [S], the scene of region [name] the port loaded, in the frame the port set. */
    private fun compare(name: String, raw: File, S: Dict, portMs: Long, tolerance: Double) {
        @Suppress("UNCHECKED_CAST")
        val R = S["R"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val bbox = (R["bbox"] as List<Any?>).map { (it as Number).toDouble() }
        val lat0 = Amsterdam.LAT0
        val lon0 = Amsterdam.LON0
        val kx = Amsterdam.KX
        val kz = Amsterdam.KZ
        // a theme park's rides' profiles, the folder the port read them from (unpacked from the element pack); a park's
        // or a zoo's woods
        val rides = (R["park"] as String?)?.takeIf { it.isNotEmpty() }?.let { File(File(Regions.parkRoot, it), "pf").path }
        val woods = truthy(R["park"]) || truthy(R["zoo"])
        SceneLines.compare(name, raw, S, portMs, region = true) { out ->
            LoadHost.dumpRegion(raw.path, out, S["region"] as String, lat0, lon0, kx, kz, bbox[0], bbox[1], bbox[2], bbox[3], LoadHost.traditionBytes(S["tradition"] as String?), rides, woods, tolerance)
        }
    }

    @Test
    fun theCoreLoadsTheCanalRingAsThePortDoes() {
        val grachten = AnchorCases.grachten()
        if (grachten == null) {
            println("regions: the canal ring's pack is not on this machine")
            return
        }
        val t0 = System.nanoTime()
        val scene = LiveEngine.loadScene(grachten)
        val ms = (System.nanoTime() - t0) / 1_000_000
        scene.state.restore()
        compare(grachten.id, File(grachten.dir, "raw.json"), scene.S, ms, 0.0)
    }

    @Test
    fun theCoreLoadsOtherRegionsAsThePortDoes() {
        val packs = System.getProperty("atlas.load.packs")?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }?.map(::File) ?: emptyList()
        if (packs.isEmpty()) {
            println("regions: atlas.load.packs names no other region's pack")
            return
        }
        for (pack in packs) {
            assertTrue("atlas.load.packs names ${pack.path}, which is not a file", pack.isFile)
            val region = PackReader.open(pack).use { LiveEngine.open(it, "${pack.length()}") }
            val t0 = System.nanoTime()
            val scene = LiveEngine.loadScene(region)
            val ms = (System.nanoTime() - t0) / 1_000_000
            scene.state.restore()
            compare(region.id, File(region.dir, "raw.json"), scene.S, ms, 0.0)
        }
    }
}
