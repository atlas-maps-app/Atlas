package app.atlas.packer

import app.atlas.geo.BBox
import app.atlas.packer.pbf.PbfReader
import app.atlas.packer.windows.Answer
import app.atlas.packer.windows.ClipPolygon
import app.atlas.packer.windows.Extract
import app.atlas.packer.windows.LeftOut
import app.atlas.packer.windows.Window
import app.atlas.packer.windows.WindowCut
import app.atlas.tiles.WindowsIndex
import java.io.File
import java.io.IOException
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.EnumMap
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.Deflater

/**
 * `packer windows`: a region's windows' data, Atlas's own packs of the data the phone builds its windows from (ENGINE.md
 * step 3, the sources for many users): for every L14 window of a Geofabrik extract, exactly the JSON a public Overpass
 * server answers to the phone's window query (`OverpassClient.windowQuery`) for the window's box (`load::window_box`,
 * which the core's stage P tool prints), as of the extract's date, gzipped as the phone keeps it, back to back in
 * `<id>.windows` with their index `<id>.windows.idx` ([WindowsIndex]). The phone takes a window's data from them before it
 * asks Overpass, and asks Overpass only for the changes since the pack's date.
 *
 * The windows listed are those whose box, with a kilometre around it, lies inside the extract's polygon (Geofabrik's
 * `.poly`, which Osmium cut the extract with: every node inside, every way with a node inside whole), and whose answer
 * the extract holds whole: every node of every way in it, every member of every relation in it. A relation across the
 * border may lack members (in Noord-Holland's extract of 26 October 2026, the protected areas over the sea and the
 * IJsselmeer, multipolygons among them): its windows are left out, and the phone asks Overpass there. A window with
 * nothing in it is listed too, so the phone never asks for the open sea.
 */
object WindowPacks {
    const val USAGE = """packer windows <in.osm.pbf> <out-dir> --poly <region.poly> --core <atlas_stagep> [--id ID] [--bbox W,S,E,N] [--threads N] [--level 1-9]
      The windows' data of a Geofabrik extract: for every L14 window whose box (the core's load::window_box, which the
      core's stage P tool --core prints) lies, with a kilometre around it, inside the extract's polygon (--poly, its
      .poly beside it), exactly the JSON a public Overpass server answers to the phone's window query for that box as
      of the extract's date, gzipped (level 9 unless --level), back to back in <out-dir>/<id>.windows, with their index
      <id>.windows.idx (WindowsIndex). A window whose answer the extract does not hold whole is left out, counted by why.
      --bbox keeps the windows whose box meets it (a region cut from a bigger extract). --id defaults to the file's name
      less -latest.osm.pbf or .osm.pbf; --threads to the machine's cores."""

    /** The windows' level: load::window_level. */
    const val LEVEL = 14

    /** How far beyond a window's box the extract's polygon must reach, in metres. */
    const val MARGIN = 1000.0

    /** What [build] made, and what it measured. */
    class Result(
        val data: File,
        val index: File,
        val date: Instant,
        val listed: Int,
        val empty: Int,
        val leftOut: Map<LeftOut, Int>,
        val dataBytes: Long,
        val jsonBytes: Long,
        val biggest: Biggest?,
        val extract: String,
        val millis: Long,
        val readMillis: Long,
        val cutMillis: Long,
        val heapPeak: Long?,
        /** The elements that left the most windows out: their description and how many. */
        val by: List<Pair<String, Int>> = emptyList(),
    )

    /** The biggest window listed: its tile, its answer's bytes and gzip member's, its elements. */
    class Biggest(val x: Int, val y: Int, val jsonBytes: Int, val gzipBytes: Int, val elements: Int)

    /** `packer windows …` ([args] start with the command word); [out] gets the report lines. Returns the exit code. */
    fun run(args: List<String>, out: (String) -> Unit): Int {
        if (args.firstOrNull() != "windows") throw UsageException("usage: $USAGE")
        val positional = ArrayList<String>()
        val options = LinkedHashMap<String, String>()
        var i = 1
        while (i < args.size) {
            val s = args[i]
            if (s.startsWith("--")) {
                if (s !in VALUED) throw UsageException("unknown option $s; usage: $USAGE")
                if (i + 1 >= args.size) throw UsageException("$s needs a value")
                options[s] = args[++i]
            } else positional += s
            i++
        }
        if (positional.size != 2) throw UsageException("windows takes the extract and the output folder; usage: $USAGE")
        val pbf = File(positional[0])
        if (!pbf.isFile) throw UsageException("no extract $pbf")
        val poly = File(options["--poly"] ?: throw UsageException("windows needs the extract's polygon (--poly); usage: $USAGE"))
        if (!poly.isFile) throw UsageException("no polygon $poly")
        val core = File(options["--core"] ?: throw UsageException("windows needs the core's stage P tool (--core); usage: $USAGE"))
        if (!core.canExecute()) throw UsageException("--core names no tool at $core")
        val id = options["--id"] ?: pbf.name.removeSuffix(".osm.pbf").removeSuffix("-latest").removeSuffix(".pbf")
        if (!ID.matches(id)) throw UsageException("bad --id '$id': lower-case letters, digits and dashes")
        val threads = options["--threads"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..64 } ?: throw UsageException("bad --threads '$it': want 1 to 64") }
            ?: Runtime.getRuntime().availableProcessors()
        val level = options["--level"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..9 } ?: throw UsageException("bad --level '$it': want 1 to 9") }
            ?: Deflater.BEST_COMPRESSION
        val clip = options["--bbox"]?.let(::parseBox)
        val r = build(
            pbf, File(positional[1]), ClipPolygon.read(poly), id, threads, level, clip,
            boxes = { x0, y0, x1, y1 -> coreBoxes(core, x0, y0, x1, y1) }, log = out,
        )
        report(r, out)
        return 0
    }

    /** The report's lines for [r]. */
    fun report(r: Result, out: (String) -> Unit) {
        val left = r.leftOut.values.sum()
        out("${r.data.name}: ${r.listed} windows (${r.empty} of them empty), ${r.dataBytes} bytes gzipped from ${r.jsonBytes} bytes of JSON; data of ${r.date}")
        out("${r.index.name}: ${r.index.length()} bytes")
        out("left out: $left" + if (left == 0) "" else " (" + r.leftOut.entries.filter { it.value > 0 }.joinToString("; ") { "${it.value}: ${it.key.why}" } + ")")
        for ((what, n) in r.by) out("  $n left out by $what")
        r.biggest?.let { b -> out("the biggest window: 14/${b.x}/${b.y}, ${b.jsonBytes} bytes of JSON, ${b.gzipBytes} gzipped, ${b.elements} elements") }
        out("the extract: ${r.extract}")
        out("time: ${r.millis / 1000} s (reading ${r.readMillis / 1000} s, cutting ${r.cutMillis / 1000} s)" + (r.heapPeak?.let { ", heap peak ${it shr 20} MiB" } ?: ""))
    }

    /**
     * The windows' data of [pbf] cut by [polygon] into [outDir]: `<id>.windows` and `<id>.windows.idx`, each through a
     * file beside it renamed once whole. [boxes] gives the windows of a range of L14 tiles (x0, y0, x1, y1, inclusive)
     * with their boxes, in order of x, then y: the core's tool's ([coreBoxes]). With [clip], only the windows whose box
     * meets it.
     */
    fun build(
        pbf: File,
        outDir: File,
        polygon: ClipPolygon,
        id: String,
        threads: Int = Runtime.getRuntime().availableProcessors(),
        level: Int = Deflater.BEST_COMPRESSION,
        clip: BBox? = null,
        boxes: (Int, Int, Int, Int) -> List<Window>,
        log: (String) -> Unit = {},
    ): Result {
        val started = System.nanoTime()
        fun ms() = (System.nanoTime() - started) / 1_000_000
        val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP && it.isValid }
        for (p in pools) runCatching { p.resetPeakUsage() }
        val header = PbfReader(pbf).header
        val date = header.replicationTime
            ?: throw IOException("${pbf.name} gives no replication time (osmosis_replication_timestamp): its windows' data would have no date")
        val dateText = DATE.format(date)

        // the windows: the core's boxes of the tiles around the polygon, those inside it with the margin
        val (x0, y0, x1, y1) = tileRange(polygon, clip)
        val all = boxes(x0, y0, x1, y1).filter { w -> clip == null || (w.west <= clip.east && w.east >= clip.west && w.south <= clip.north && w.north >= clip.south) }
        val leftOut = EnumMap<LeftOut, Int>(LeftOut::class.java).apply { for (k in LeftOut.values()) put(k, 0) }
        val windows = all.filter { w ->
            val inside = polygon.holdsBox(w.south - DLAT, w.west - dlon(w), w.north + DLAT, w.east + dlon(w))
            if (!inside) leftOut.merge(LeftOut.BEYOND, 1, Int::plus)
            inside
        }.sortedWith(compareBy({ it.x }, { it.y }))
        log("windows: ${windows.size} of the ${all.size} in tiles $x0..$x1 by $y0..$y1${if (clip == null) "" else " meeting $clip"} lie inside the polygon with ${MARGIN.toInt()} m around them")

        val extract = Extract.read(pbf, minOf(threads, 8))
        val read = ms()
        val held = "${extract.nodeCount} nodes (${extract.taggedCount} tagged), ${extract.wayCount} ways (${extract.brokenWays} lacking nodes), " +
            "${extract.relationCount} relations asked for (${extract.brokenRelations} lacking members), ${extract.twice} with a key twice; " +
            "${extract.bytes shr 20} MiB held (${extract.nodeBytes shr 20} the nodes' positions, ${extract.jsonBytes shr 20} the JSON of tagged nodes and tags)"
        log("read in ${read / 1000} s: $held")

        val cut = WindowCut(extract, windows, dateText, level)
        log("cells: ${cut.cells.rows} by ${cut.cells.cols}, ${cut.indexBytes shr 20} MiB of index")
        outDir.mkdirs()
        val data = File(outDir, id + Manifest.WINDOWS_SUFFIX)
        val index = File(outDir, id + Manifest.WINDOWS_INDEX_SUFFIX)
        val dataPart = File(data.path + ".part")
        val entries = ArrayList<WindowsIndex.Entry>(windows.size)
        var offset = 0L
        var jsonBytes = 0L
        var empty = 0
        var biggest: Biggest? = null
        val by = HashMap<Pair<LeftOut, Long>, Int>()
        val cutStart = ms()
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "window-cut").apply { isDaemon = true } }
        val works = ThreadLocal.withInitial { cut.work() }
        try {
            dataPart.outputStream().buffered(1 shl 20).use { file ->
                fun take(a: Answer) {
                    val why = a.leftOut
                    if (why != null) {
                        leftOut.merge(why, 1, Int::plus)
                        by.merge(why to a.by, 1, Int::plus)
                        return
                    }
                    val gz = a.gzip!!
                    file.write(gz)
                    entries += WindowsIndex.Entry(a.window.x, a.window.y, offset, gz.size, a.sha256!!)
                    offset += gz.size
                    jsonBytes += a.jsonBytes
                    if (a.elements == 0) empty++
                    if (biggest == null || a.jsonBytes > biggest!!.jsonBytes) biggest = Biggest(a.window.x, a.window.y, a.jsonBytes, gz.size, a.elements)
                    if (entries.size % 5000 == 0) log("  ${entries.size} windows, ${offset shr 20} MiB (${ms() / 1000} s)")
                }
                val ahead = ArrayDeque<Future<Answer>>()
                for (w in windows) {
                    ahead.addLast(pool.submit(Callable { cut.answer(w, works.get()) }))
                    if (ahead.size >= threads * 4) take(result(ahead.removeFirst()))
                }
                while (ahead.isNotEmpty()) take(result(ahead.removeFirst()))
            }
        } catch (x: Throwable) {
            dataPart.delete()
            throw x
        } finally {
            pool.shutdownNow()
        }
        val cutMillis = ms() - cutStart
        move(dataPart, data)
        val indexPart = File(index.path + ".part")
        indexPart.outputStream().buffered().use { WindowsIndex.of(LEVEL, WindowsIndex.QUERY, date, offset, entries).write(it) }
        move(indexPart, index)
        val peak = pools.sumOf { runCatching { it.peakUsage.used }.getOrDefault(0L) }.takeIf { it > 0 }
        val culprits = by.entries.sortedByDescending { it.value }.take(10).map { (k, n) ->
            val (why, id) = k
            (if (why == LeftOut.RELATION) extract.describeRelation(id) else "${why.name.lowercase()} $id") to n
        }
        return Result(data, index, date, entries.size, empty, leftOut, offset, jsonBytes, biggest, held, ms(), read, cutMillis, peak, culprits)
    }

    /** The L14 tiles around [polygon]'s box (within [clip]), one more each side: x0, y0, x1, y1 (y from the north). */
    fun tileRange(polygon: ClipPolygon, clip: BBox? = null): List<Int> {
        val p = polygon.bbox
        val b = if (clip == null) p else doubleArrayOf(maxOf(p[0], clip.west), maxOf(p[1], clip.south), minOf(p[2], clip.east), minOf(p[3], clip.north))
        val n = 1 shl LEVEL
        fun x(lon: Double) = Math.floor((lon + 180.0) / 360.0 * n).toInt()
        fun y(lat: Double): Int {
            val la = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
            return Math.floor((1.0 - Math.log(Math.tan(la) + 1.0 / Math.cos(la)) / Math.PI) / 2.0 * n).toInt()
        }
        return listOf((x(b[0]) - 1).coerceIn(0, n - 1), (y(b[3]) - 1).coerceIn(0, n - 1), (x(b[2]) + 1).coerceIn(0, n - 1), (y(b[1]) + 1).coerceIn(0, n - 1))
    }

    /**
     * The windows of tiles [x0] to [x1] by [y0] to [y1] with their boxes, as the core's stage P tool [core] prints them
     * (`atlas_stagep window-boxes`: a line `x y south west north east` per window, each double as it reads back exactly),
     * in order of x, then y.
     */
    fun coreBoxes(core: File, x0: Int, y0: Int, x1: Int, y1: Int): List<Window> {
        val p = ProcessBuilder(core.path, "window-boxes", "$x0", "$y0", "$x1", "$y1")
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        p.outputStream.close()
        val windows = ArrayList<Window>(((x1 - x0 + 1).toLong() * (y1 - y0 + 1)).coerceAtMost(1L shl 24).toInt())
        p.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                val v = line.split(' ')
                val w = if (v.size == 6) runCatching {
                    Window(v[0].toInt(), v[1].toInt(), v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble())
                }.getOrNull() else null
                w ?: throw IOException("${core.name} window-boxes printed '$line', not x y south west north east")
                windows += w
            }
        }
        val code = p.waitFor()
        if (code != 0) throw IOException("${core.name} window-boxes $x0 $y0 $x1 $y1 failed (exit $code)")
        var k = 0
        for (x in x0..x1) for (y in y0..y1) {
            val w = windows.getOrNull(k++)
            if (w == null || w.x != x || w.y != y) throw IOException("${core.name} window-boxes gave ${w?.let { "${it.x}/${it.y}" } ?: "nothing"} where window $x/$y belongs")
        }
        if (k != windows.size) throw IOException("${core.name} window-boxes gave ${windows.size} windows for $k tiles")
        return windows
    }

    private fun result(f: Future<Answer>): Answer = try {
        f.get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    private fun move(from: File, to: File) {
        if (!from.renameTo(to)) {
            to.delete()
            if (!from.renameTo(to)) throw IOException("cannot move $from to $to")
        }
    }

    /** The margin in degrees of latitude (regions.py's 110540 m a degree, a little under the true length). */
    private const val DLAT = MARGIN / 110_540.0

    /** The margin in degrees of longitude along the parallel of the window's box farthest from the equator. */
    private fun dlon(w: Window): Double {
        val widest = minOf(89.0, maxOf(Math.abs(w.south), Math.abs(w.north)) + DLAT)
        return MARGIN / (111_320.0 * Math.cos(Math.toRadians(widest)))
    }

    private const val MAX_LAT = 85.0511287798066
    private val VALUED = setOf("--poly", "--core", "--id", "--bbox", "--threads", "--level")
    private val ID = Regex("[a-z0-9][a-z0-9-]*")

    /** A date as Overpass writes its data's: `YYYY-MM-DDTHH:MM:SSZ`. */
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
}
