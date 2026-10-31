package app.atlas.packer.world

import app.atlas.packer.WindowPacks
import app.atlas.packer.pbf.PbfReader
import app.atlas.packer.windows.ClipPolygon
import app.atlas.rendertiles.Format3Reader
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.Grid
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.RenderTile
import app.atlas.tiles.WindowsIndex
import com.github.luben.zstd.Zstd
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.locationtech.jts.geom.Envelope
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.TreeMap
import java.util.zip.GZIPInputStream

/**
 * The world's profile against stage P (ENGINE.md step 5: "Each kind, class and name the profile writes matches what
 * stage P writes for the same features from the same extract"): windows built by the core's stage P tool (-Datlas.stagep)
 * from an extract's windows' data, as a country's are, and the profile's decisions on the same extract (Planetiler's
 * run of it over the whole extract, [WorldTiles.audit]), element by element, both ways:
 * - every land cover, road, big road, railway, runway and place stage P writes in a window is the world's too, of the
 *   same kind or class (and a place of the same name), unless the world leaves its class out by design (minor roads,
 *   railways other than main lines, aeroways other than runways, places below suburbs);
 * - every element the world draws as one of those whose reference point (load/Cut.hpp: a ring's box centre, a line's
 *   middle vertex, a node) lies in the window's tile is in stage P's tile, as the same layer, but for three causes,
 *   counted and named: an element not in the window's data (Overpass takes a way where a segment meets the box, so a
 *   ring that passes round the window is not there), a ring at stage P's floor of 4 m² (Planetiler's node positions,
 *   about 1 cm, put a sliver on either side of it) and a reference point on the tile's edge (filed either side by
 *   rounding).
 * Stage P's water carries no id, and the world's rivers and sea are its own, so neither is compared here.
 *
 * Monaco's extract (the test's resources) runs on CI, its windows cut here by `packer windows`; a region's windows'
 * data runs with -Datlas.world.parity.windows=<region.windows> (its .idx beside it) and
 * -Datlas.world.parity.pbf=<the extract they were cut from>, a sample of its windows (-Datlas.world.parity.sample, 60
 * unless given, spread over the index, and the windows of -Datlas.world.parity.at=lat,lon/lat,lon...).
 */
class WorldParityTest {
    @get:Rule val tmp = TemporaryFolder()

    private val tool = System.getProperty("atlas.stagep")?.let(::File)

    private fun needTool() = assumeTrue("the core's stage P tool (-Datlas.stagep)", tool != null && tool.canExecute())

    @Test
    fun monacosWindowsDrawWhatTheWorldDrawsAsTheSameKinds() {
        needTool()
        val pbf = File(javaClass.getResource("/monaco-2026-10-13.osm.pbf")!!.toURI())
        val box = PbfReader(pbf).header.bbox!!
        val poly = File(tmp.root, "monaco.poly")
        val (w, s, e, n) = listOf(box.west - 0.03, box.south - 0.03, box.east + 0.03, box.north + 0.03)
        poly.writeText("monaco\n1\n   $w $s\n   $e $s\n   $e $n\n   $w $n\n   $w $s\nEND\nEND\n")
        val data = WindowPacks.build(pbf, tmp.newFolder("windows"), ClipPolygon.read(poly), "monaco", threads = 2, boxes = { x0, y0, x1, y1 -> WindowPacks.coreBoxes(tool!!, x0, y0, x1, y1) }, log = ::println)
        println("windows: ${data.listed} listed, ${data.empty} empty, left out ${data.leftOut}")
        val index = WindowsIndex.read(data.index.readBytes())
        val sample = (0 until index.size).map { index[it] }.filter { it.length > 0 }
        assertTrue("${sample.size} windows with data", sample.size >= 4)
        val report = compare(pbf, data.data, sample)
        println(report.text())
        assertTrue(report.text(), report.compared >= 200)
        for (l in listOf(WorldProfile.LAND, WorldProfile.ROAD, WorldProfile.BIG, WorldProfile.PLACE)) assertTrue("$l: ${report.text()}", (report.byLayer[l]?.get(1) ?: 0) >= 1)
        assertEquals(report.text(), emptyList<String>(), report.mismatches)
        assertEquals(report.text(), emptyList<String>(), report.missing)
        assertEquals(report.text(), emptyMap<String, Int>(), report.explained)
    }

    @Test
    fun aRegionsWindowsDrawWhatTheWorldDrawsAsTheSameKinds() {
        needTool()
        val windows = System.getProperty("atlas.world.parity.windows")?.let(::File)
        val pbf = System.getProperty("atlas.world.parity.pbf")?.let(::File)
        assumeTrue("a region's windows' data and its extract (-Datlas.world.parity.windows, -Datlas.world.parity.pbf)", windows?.isFile == true && pbf?.isFile == true)
        val index = WindowsIndex.read(File(windows!!.path + ".idx").readBytes())
        // the same extract on both sides
        assertEquals(index.date, PbfReader(pbf!!).header.replicationTime)
        val drawn = (0 until index.size).map { index[it] }.filter { it.length > 0 }
        val want = System.getProperty("atlas.world.parity.sample")?.toInt() ?: 60
        val step = maxOf(1, drawn.size / want)
        val at = System.getProperty("atlas.world.parity.at")?.split('/')?.map { p ->
            val (lat, lon) = p.split(',').map { it.trim().toDouble() }
            Grid.col(Grid.round(lon), 14) to Grid.row(Grid.round(lat), 14)
        }.orEmpty().toSet()
        val sample = drawn.filterIndexed { i, e -> i % step == step / 2 || (e.x to e.y) in at }
        val report = compare(pbf, windows, sample)
        println(report.text())
        System.getProperty("atlas.world.parity.report")?.let { File(it).writeText(report.text()) }
        assertEquals(report.text(), emptyList<String>(), report.mismatches)
        assertEquals(report.text(), emptyList<String>(), report.missing)
    }

    /** What stage P wrote of an element in a window, named as the profile names it. */
    private data class Written(val layer: String, val kind: String, val name: String?)

    /** The comparison's counts and findings. */
    private class Report(val windows: Int) {
        val byLayer = TreeMap<String, IntArray>() // compared, matched, by design, no id
        val byDesign = TreeMap<String, Int>()
        val notCompared = TreeMap<String, Int>()
        val mismatches = ArrayList<String>()
        val missing = ArrayList<String>()
        val explained = TreeMap<String, Int>()
        val examples = ArrayList<String>()
        var elements = 0
        val compared: Int get() = byLayer.values.sumOf { it[0] }

        fun count(layer: String, what: Int) {
            byLayer.getOrPut(layer) { IntArray(4) }[what]++
        }

        fun text(): String = buildString {
            append("parity over $windows windows, $elements elements the world decided on near them\n")
            for ((l, c) in byLayer) append("  $l: ${c[0]} written by stage P, ${c[1]} the same in the world, ${c[2]} left out by design, ${c[3]} without an id\n")
            append("  left out by design: $byDesign\n")
            append("  not compared: $notCompared\n")
            append("  stage P's that the world writes otherwise or not at all: ${mismatches.size}\n")
            for (m in mismatches.take(60)) append("    $m\n")
            append("  the world's that stage P's tile lacks, unexplained: ${missing.size}\n")
            for (m in missing.take(60)) append("    $m\n")
            append("  the world's that stage P's tile lacks, explained: $explained\n")
            for (m in examples.take(20)) append("    $m\n")
        }
    }

    /** Stage P's windows [sample] of [data] built by the tool, and the profile's decisions on [pbf], compared. */
    private fun compare(pbf: File, data: File, sample: List<WindowsIndex.Entry>): Report {
        // the answers, as the phone holds them, and stage P's windows of them
        val answers = tmp.newFolder("answers")
        val out = tmp.newFolder("built")
        val list = File(tmp.root, "list.txt")
        val tags = HashMap<Pair<OsmType, Long>, Map<String, String>>()
        val inAnswer = sample.map { HashSet<Pair<OsmType, Long>>() }
        RandomAccessFile(data, "r").use { f ->
            list.bufferedWriter().use { w ->
                for ((i, e) in sample.withIndex()) {
                    val member = ByteArray(e.length).also { f.seek(e.offset); f.readFully(it) }
                    val json = GZIPInputStream(member.inputStream()).readBytes()
                    val file = File(answers, "14-${e.x}-${e.y}.json").apply { writeBytes(json) }
                    w.write("${e.x} ${e.y} ${file.path}\n")
                    for (el in Json.parseToJsonElement(json.decodeToString()).jsonObject["elements"]!!.jsonArray) {
                        val o = el.jsonObject
                        val type = OsmType.valueOf(o["type"]!!.jsonPrimitive.content.uppercase())
                        tags[type to o["id"]!!.jsonPrimitive.long] = (o["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
                        inAnswer[i] += type to o["id"]!!.jsonPrimitive.long
                    }
                }
            }
        }
        val p = ProcessBuilder(tool!!.path, "windows", list.path, out.path, "3").redirectErrorStream(true).start()
        p.outputStream.close()
        val said = p.inputStream.bufferedReader().readText()
        assertEquals(said, 0, p.waitFor())
        val tiles = sample.map { e -> File(out, "14-${e.x}-${e.y}.atlas").takeIf { it.isFile }?.let { coreTiles(it.readBytes()).singleOrNull() } }

        // the profile's decisions on the same extract near the windows
        val windowBoxes = WindowPacks.coreBoxes(tool, sample.minOf { it.x }, sample.minOf { it.y }, sample.maxOf { it.x }, sample.maxOf { it.y }).associateBy { it.x to it.y }
        val boxes = sample.map { e -> windowBoxes.getValue(e.x to e.y).let { Envelope(it.west, it.east, it.south, it.north) } }
        val audit = WorldTiles.audit(pbf, tmp.newFolder("scratch"), 3, boxes, sample.map { intArrayOf(14, it.x, it.y) })

        val report = Report(sample.size)
        report.elements = audit.size
        val stagePs = sample.indices.map { HashSet<Pair<Pair<OsmType, Long>, String>>() }
        for ((i, tile) in tiles.withIndex()) {
            val e = sample[i]
            for ((key, w) in written(tile ?: continue, report)) {
                report.count(w.layer, 0)
                if (key == null) {
                    report.count(w.layer, 3)
                    continue
                }
                stagePs[i] += key to w.layer
                val same = audit[key]?.decisions?.filter { it.layer == w.layer }.orEmpty()
                when {
                    same.any { it.kind == w.kind && (w.layer != WorldProfile.PLACE || it.name == w.name) } -> report.count(w.layer, 1)
                    same.isEmpty() && byDesign(w, tags[key].orEmpty()) -> {
                        report.count(w.layer, 2)
                        report.byDesign.merge("${w.layer} ${w.kind}", 1, Int::plus)
                    }
                    else -> report.mismatches += "14/${e.x}/${e.y} ${key.first.name.lowercase()} ${key.second}: stage P $w, the world ${audit[key]?.decisions ?: "nothing"} (${tags[key]})"
                }
            }
        }
        val compared = setOf(WorldProfile.LAND, WorldProfile.ROAD, WorldProfile.BIG, WorldProfile.RAIL, WorldProfile.AERO, WorldProfile.PLACE)
        for ((key, a) in audit) {
            for ((k, d) in a.decisions.withIndex()) {
                if (d.layer !in compared) continue
                for (i in a.meets[k]) {
                    if (tiles[i] == null || (key to d.layer) in stagePs[i]) continue
                    val elsewhere = sample.indices.filter { (key to d.layer) in stagePs[it] }.map { "14/${sample[it].x}/${sample[it].y}" }
                    val what = "14/${sample[i].x}/${sample[i].y} ${key.first.name.lowercase()} ${key.second}: the world $d, stage P nothing${if (elsewhere.isEmpty()) "" else " (it is in $elsewhere)"} " +
                        "(${tags[key]}; its box ${a.box}, the window's ${boxes[i]}, its rings ${a.landAreas.map { "%.2f m²".format(it) }})"
                    val tb = Grid.tileBox(14, sample[i].x, sample[i].y)
                    val why = when {
                        // Overpass takes a way where a segment meets the window's box: one whose ring passes round it is
                        // not in the window's data, so stage P never sees it there
                        key !in inAnswer[i] -> "not in the window's data"
                        // regions.py's floor of 4 m² a ring, which Planetiler's node positions (31 bits a side of the
                        // world: about 1 cm) can put a sliver on either side of
                        d.layer == WorldProfile.LAND && a.landAreas.any { it > 4.0 && it <= 5.0 } -> "at 4 m², stage P's floor for a ring"
                        // a reference point within 10 grid units (about 1 m) of the tile's edge, which rounding files
                        // either side
                        a.points[k].any { pt ->
                            val lat = Grid.round(pt.y)
                            val lon = Grid.round(pt.x)
                            minOf(Math.abs(lat - tb[0]), Math.abs(lat - tb[2]), Math.abs(lon - tb[1]), Math.abs(lon - tb[3])) <= 10
                        } -> "its reference point on the tile's edge"
                        else -> null
                    }
                    if (why == null) {
                        report.missing += what
                    } else {
                        report.explained.merge(why, 1, Int::plus)
                        report.examples += "$why: $what"
                    }
                }
            }
        }
        return report
    }

    /** What stage P wrote in [tile] that the world compares, by element (null without an id). */
    private fun written(tile: RenderTile, report: Report): List<Pair<Pair<OsmType, Long>?, Written>> {
        val out = ArrayList<Pair<Pair<OsmType, Long>?, Written>>()
        fun key(type: OsmType, f: app.atlas.rendertiles.model.Feature): Pair<OsmType, Long>? =
            (f["id"] as Long?)?.let { (if (f["otype"] == "relation") OsmType.RELATION else type) to it }
        for ((layer, features) in tile.layers) {
            for (f in features) {
                val source = (f["source"] as Int?) ?: 0
                when {
                    layer == Layer.GROUND && source == 1 -> out += key(OsmType.WAY, f) to Written(WorldProfile.LAND, f["kind"] as String, null)
                    layer == Layer.ROAD -> out += key(OsmType.WAY, f) to Written(WorldProfile.ROAD, f["hw"] as String, null)
                    layer == Layer.LINE && source == 1 -> out += key(OsmType.WAY, f) to Written(WorldProfile.RAIL, "rail", null)
                    layer == Layer.LINE && source == 2 -> out += key(OsmType.WAY, f) to Written(WorldProfile.BIG, f["hw"] as String, null)
                    layer == Layer.LINE && source == 3 -> out += key(OsmType.WAY, f) to Written(WorldProfile.AERO, f["kind"] as String, null)
                    layer == Layer.LABEL && source == 1 -> out += key(OsmType.NODE, f) to Written(WorldProfile.PLACE, f["kind"] as String, f["name"] as String?)
                    else -> report.notCompared.merge(if (layer == Layer.GROUND || layer == Layer.LINE || layer == Layer.LABEL) "${layer.label} $source" else layer.label, 1, Int::plus)
                }
            }
        }
        return out
    }

    /**
     * The tiles of a pack the core wrote (a window's, whose manifest is the core's own): its chunk table read by the
     * container's rules, each chunk's zstd frame decoded by [Format3Reader.decodeTile].
     */
    private fun coreTiles(bytes: ByteArray): List<RenderTile> {
        val b = ByteBuffer.wrap(bytes)
        assertEquals("ATLASPCK", String(bytes, 0, 8, Charsets.US_ASCII))
        val ml = b.getInt(12)
        val dl = b.getInt(16)
        var at = 20 + ml
        fun varint(): Long {
            var v = 0L
            var shift = 0
            while (true) {
                val c = bytes[at++].toInt() and 0xFF
                v = v or ((c and 0x7F).toLong() shl shift)
                if (c < 0x80) return v
                shift += 7
            }
        }
        val sections = HashMap<String, Long>()
        while (at < 20 + ml + dl) {
            val length = varint().toInt()
            val name = String(bytes, at, length, Charsets.UTF_8)
            at += length
            sections[name] = b.getLong(at)
            at += 29
        }
        at = sections.getValue(Format.SECTION_TILES).toInt()
        var frame = sections.getValue(Format.SECTION_CHUNKS).toInt()
        return (0 until varint().toInt()).map {
            at++ // the level
            varint()
            varint()
            val stored = varint().toInt()
            val raw = varint().toInt()
            varint()
            at += 32
            val tile = Zstd.decompress(bytes.copyOfRange(frame, frame + stored), raw)
            frame += stored
            Format3Reader.decodeTile(tile)
        }
    }

    /** Whether the world leaves [w] out by its own choice of what its levels draw, the element's tags being [t]. */
    private fun byDesign(w: Written, t: Map<String, String>): Boolean = when (w.layer) {
        WorldProfile.ROAD, WorldProfile.BIG -> WorldProfile.roadLevel(w.kind) == null
        WorldProfile.RAIL -> t["railway"] != "rail" || "service" in t
        WorldProfile.AERO -> w.kind != "runway"
        WorldProfile.PLACE -> w.kind !in WorldProfile.PLACE_LEVELS
        else -> false
    }
}
