package app.atlas.packer.world

import app.atlas.rendertiles.Format3Reader
import app.atlas.rendertiles.model.Feature
import app.atlas.rendertiles.model.GridPoint
import app.atlas.rendertiles.model.Layer
import app.atlas.tiles.WorldIndex
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * A survey of a world's names on real packs (world-names-format.md's numbers), skipped without
 * `-Datlas.world.names=<folder>`: a folder holding a world's index and its parts (a release's world.idx, world-mid-*,
 * world-coarse.*), or packs cut by `packer world-refs` (world-<z>-<x>-<y>.atlas), and the globe beside them. Every
 * tile's LABEL rows by level and source (1 a place, 2 a water, 3 a river), the waters' and the rivers' kinds, and the
 * first of each level's waters and rivers by rank; a water's name stands in one tile of a level, and a river's once in a
 * tile.
 */
class WorldNamesSurveyTest {
    @Test
    fun theWorldsNamesByLevelSourceAndKind() {
        val dir = System.getProperty("atlas.world.names")?.let(::File)
        assumeTrue("a world's folder to survey (-Datlas.world.names=<folder>)", dir?.isDirectory == true)
        // each pack read when its turn comes, so that a country's world fits the tests' heap
        val packs = ArrayList<Pair<String, () -> ByteArray>>()
        val idx = File(dir!!, "world.idx")
        if (idx.isFile) {
            val index = WorldIndex.read(idx.readBytes())
            for (e in index.entries) {
                val part = File(dir, index.parts[e.part].name)
                if (!part.isFile) continue
                packs += "${e.z}/${e.x}/${e.y}" to {
                    val b = ByteArray(Math.toIntExact(e.length))
                    RandomAccessFile(part, "r").use { f ->
                        f.seek(e.offset)
                        f.readFully(b)
                    }
                    b
                }
            }
        }
        for (f in dir.listFiles { f -> f.name.endsWith(".atlas") }.orEmpty().sortedBy { it.name }) packs += f.name to { f.readBytes() }
        assumeTrue("no packs in $dir", packs.isNotEmpty())

        class Level(var tiles: Int = 0, val rows: IntArray = IntArray(4), val kinds: java.util.TreeMap<String, Int> = java.util.TreeMap())
        val levels = java.util.TreeMap<Int, Level>()
        val firsts = java.util.TreeMap<Int, MutableList<Pair<Long, String>>>()
        val waterTiles = HashMap<Pair<Int, String>, Int>()
        var riverTwice = 0
        for ((_, read) in packs) {
            val reader = Format3Reader(read())
            for (c in reader.chunks) {
                val t = reader.decode(c)
                val l = levels.getOrPut(t.level) { Level() }
                l.tiles++
                val rivers = HashSet<String>()
                for (f: Feature in t.layers[Layer.LABEL].orEmpty()) {
                    val source = Covers.source(f)
                    if (source in 0..3) l.rows[source]++
                    if (source != 2 && source != 3) continue
                    val kind = f["kind"].toString()
                    l.kinds.merge(if (source == 2) kind else "river", 1, Int::plus)
                    val text = f["name"].toString()
                    // a column's default (a rank or a count of 0) is left out of the row
                    val index = (f["index"] as Number?)?.toLong() ?: 0L
                    val count = (f["count"] as Number?)?.toLong() ?: 0L
                    if (source == 2) {
                        val at = f["pos"] as GridPoint
                        // a water by its id where it has one, else its name and point
                        val id = (f["id"] as Number?)?.toLong()?.toString() ?: "$text@${at.lat},${at.lon}"
                        waterTiles.merge(t.level to id, 1, Int::plus)
                    } else if (!rivers.add(text)) {
                        riverTwice++
                    }
                    if (index == 0L) firsts.getOrPut(t.level) { ArrayList() } += count to "$text ($kind, $count)"
                }
            }
        }
        println("world-names: ${packs.size} packs of $dir (${packs.first().first} to ${packs.last().first})")
        for ((z, l) in levels) {
            val first = firsts[z].orEmpty().sortedByDescending { it.first }.take(6).joinToString { it.second }
            println(
                "world-names: L$z: ${l.tiles} tiles; canals ${l.rows[0]}, places ${l.rows[1]}, waters ${l.rows[2]}, rivers ${l.rows[3]}; " +
                    "${l.kinds.entries.joinToString { "${it.key} ${it.value}" }}; the largest firsts: $first",
            )
        }
        val again = waterTiles.filterValues { it > 1 }
        println("world-names: ${waterTiles.size} waters' names a level, ${again.size} of them in more than one tile; $riverTwice rivers twice in a tile")
        assertEquals("a water's name stands in one tile of a level", emptyMap<Pair<Int, String>, Int>(), again)
        assertEquals("a river's name stands once in a tile", 0, riverTwice)
    }
}
