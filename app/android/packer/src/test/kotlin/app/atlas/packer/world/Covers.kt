package app.atlas.packer.world

import app.atlas.rendertiles.model.Feature
import app.atlas.rendertiles.model.Geom
import app.atlas.rendertiles.model.GridPoint
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.RenderTile
import app.atlas.rendertiles.CanonicalDump
import app.atlas.rendertiles.Format3Reader
import app.atlas.rendertiles.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.io.RandomAccessFile
import java.math.BigInteger

/** What the world's tests hold every world tile to: its cells filled exactly by land and sea, nothing across their edges. */
object Covers {
    /** Twice the area of ring [g] (closed, its first point not repeated) on the grid, exactly. */
    fun twice(g: Geom): BigInteger {
        var s = BigInteger.ZERO
        val n = g.lat.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            s += BigInteger.valueOf(g.lon[i].toLong() * g.lat[j] - g.lon[j].toLong() * g.lat[i])
        }
        return s.abs()
    }

    private fun points(f: Feature): List<Pair<Int, Int>> {
        val g = (f["ring"] ?: f["line"]) as Geom?
        if (g != null) return g.lat.indices.map { g.lat[it] to g.lon[it] }
        val p = f["pos"] as GridPoint? ?: return emptyList()
        return listOf(p.lat to p.lon)
    }

    /** [f]'s source: a decoded feature leaves out a column at its default, source 0. */
    fun source(f: Feature): Int = (f["source"] as Number?)?.toInt() ?: 0

    /** The core's stage P tool (-Datlas.stagep, as CI's unit-rest job gives it), or null. */
    private val tool: File? = System.getProperty("atlas.stagep")?.let(::File)?.takeIf { it.canExecute() }

    /**
     * With the core's stage P tool, asserts the core reads the pack of [length] bytes from [offset] of [file] as its
     * store does (`atlas_stagep check`): verified, its head the world's with [levels], and every tile decoded to the
     * canonical hash of the tile the packer wrote. Without the tool it says so and checks nothing.
     */
    fun assertCoreReads(file: File, offset: Long, length: Long, levels: List<Int>) {
        if (tool == null) {
            println("the core's stage P tool (-Datlas.stagep) is not here: the core's decode of ${file.name} is not checked")
            return
        }
        val p = ProcessBuilder(tool.path, "check", file.path, "$offset", "$length").redirectErrorStream(true).start()
        p.outputStream.close()
        val said = p.inputStream.bufferedReader().readText()
        assertEquals(said, 0, p.waitFor())
        val lines = said.lines().filter { it.isNotBlank() }
        assertEquals("head ${Profile.WORLD_BASE.code} ${levels.joinToString(",")}", lines.first())
        val bytes = ByteArray(length.toInt()).also { b -> RandomAccessFile(file, "r").use { it.seek(offset); it.readFully(b) } }
        val packer = Format3Reader(bytes).tiles().map { "tile ${it.level} ${it.x} ${it.y} ${it.featureCount} ${CanonicalDump.hash(it)}" }
        assertEquals(packer, lines.drop(1))
    }

    /**
     * The vertices of [tile]'s rings on neither its row's lattice ([WorldGeometry.lattice]: both coordinates multiples
     * of its steps) nor their cell's edges (where the cut makes them, on the grid). Lines are not counted: where a class's
     * lines cross, the cut's overlay notes the crossing as a vertex of each, on the grid.
     */
    fun offLattice(tile: RenderTile): Int = offLatticeByLayer(tile).values.sum()

    /** [offLattice] by layer. */
    fun offLatticeByLayer(tile: RenderTile): Map<Layer, Int> {
        val (qLat, qLon) = WorldGeometry.lattice(tile.level, tile.y).let { it[0] to it[1] }
        val cells = WorldGeometry.cellBoxes(tile.level, tile.x, tile.y)
        val out = HashMap<Layer, Int>()
        for ((layer, list) in tile.layers) {
            var off = 0
            for (feature in list) {
                val g = feature["ring"] as Geom? ?: continue
                val b = cells[feature.cell]
                for (k in g.lat.indices) {
                    val lat = g.lat[k]
                    val lon = g.lon[k]
                    val edge = lat == b[0] || lat == b[2] || lon == b[1] || lon == b[3]
                    if (!edge && (Math.floorMod(lat, qLat) != 0 || Math.floorMod(lon, qLon) != 0)) off++
                }
            }
            if (off > 0) out[layer] = off
        }
        return out
    }

    /**
     * Asserts every cell of [tile] filled exactly by its land under everything (GROUND source [WorldCut.LAND_BASE]) and
     * its sea (WATER's tidal rings), and every ring, line and point in its cell's box.
     */
    fun assertCovers(tile: RenderTile) {
        val cells = WorldGeometry.cellBoxes(tile.level, tile.x, tile.y)
        val sums = Array(cells.size) { BigInteger.ZERO }
        for ((layer, list) in tile.layers) {
            for (feature in list) {
                val b = cells[feature.cell]
                for ((lat, lon) in points(feature)) {
                    assertTrue("$tile: $feature at $lat, $lon outside cell ${feature.cell}", lat in b[0]..b[2] && lon in b[1]..b[3])
                }
                val r = feature["ring"] as Geom? ?: continue
                val base = layer == Layer.GROUND && source(feature) == WorldCut.LAND_BASE
                if (base || (layer == Layer.WATER && feature["tidal"] == true)) sums[feature.cell] += twice(r)
            }
        }
        for ((c, b) in cells.withIndex()) {
            val area = BigInteger.valueOf(2L * (b[2] - b[0]).toLong() * (b[3] - b[1]).toLong())
            assertEquals("$tile, cell $c: twice its land and sea", area, sums[c])
        }
    }
}
