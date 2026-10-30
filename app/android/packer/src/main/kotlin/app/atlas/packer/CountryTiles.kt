package app.atlas.packer

import app.atlas.packer.country.WindowPack
import app.atlas.rendertiles.codec.Format3Writer
import app.atlas.rendertiles.model.Format
import app.atlas.tiles.CountryStyle
import app.atlas.tiles.TilesIndex
import app.atlas.tiles.WindowsIndex
import com.github.luben.zstd.ZstdCompressCtx
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * `packer country`: a country's tiles, Atlas's own pack of its map (ENGINE.md step 3, one pack per country). Every
 * window of the country's windows' data (`packer windows`) is built by the core's stage P tool exactly as the phone
 * builds it from that data (`atlas_stagep windows`: load::build_window with load::place_of, the window's frame cell's
 * frame and its country's looks), and its format-3 pack is stored with its chunks compressed at zstd's level 19, where
 * the core writes them as raw blocks (it vendors zstd's decompression alone), its style as what differs from the
 * country's ([CountryStyle]: the first window's in the file, which the index holds), and every other byte as the core
 * wrote it ([WindowPack.recompressed]): its tiles are the phone's byte for byte. The packs lie back to back in
 * `<id>.tiles`, with their index `<id>.tiles.idx` ([TilesIndex]: each window's range and SHA-256, the windows' data's
 * date, the core's window version, the country's style). The phone downloads the index whole and a window's pack by an
 * HTTP range, writes its style back whole, and builds only the windows the pack lacks; a window whose pack holds no tile
 * (the open sea) is listed with no bytes.
 *
 * The windows go to the tool in batches, their answers unzipped beside the output and deleted once built, the batch's
 * packs once stored (a country's answers are many times its windows' data: Noord-Holland's 0.6 GB gzipped are 4.6 GB of
 * JSON). A batch's list gives the tool its biggest answers first, so its threads finish together. In the tiles
 * file the windows lie frame cell by frame cell (load::frame_cell_level's L10 tile, whose frame they are drawn in), each
 * cell's in order of x, then y, so a cell's packs are one range.
 *
 * Each frame cell's levels follow its windows once the cell's last window is built (ENGINE.md one pack per country,
 * step 7): its L13 and L12 tiles, which the core derives from the cell's windows' packs as it wrote them (`atlas_stagep
 * levels`, load::build_levels), each a pack of one tile stored as a window's is and listed in the index by its level
 * ([TilesIndex]). A cell whose levels the tool cannot build is named on a line beginning "levels not built" and left
 * without them: the phone draws its windows to their own reach there.
 */
object CountryTiles {
    const val USAGE = """packer country <region.windows> <region.windows.idx> <out-dir> --core <atlas_stagep> [--id ID] [--threads N] [--batch N] [--allow-failed N]
      A country's tiles from its windows' data (packer windows): every window built by the core's stage P tool (--core)
      as the phone builds it (atlas_stagep windows), each window's pack with its chunks compressed at zstd 19, its
      style as what differs from the country's (the first window's, held by the index) and every other byte the core's,
      checked to decode to the core's own tiles and style, back to back in <out-dir>/<id>.tiles, with their index
      <id>.tiles.idx (TilesIndex); a window whose pack holds no tile is listed with no bytes. Each frame cell's levels
      (its L13 and L12 tiles, atlas_stagep levels) follow its windows. The answers are unzipped --batch windows at a time (400 unless given) into a folder in <out-dir>,
      deleted once built. --id defaults to the data's name less .windows; --threads, the tool's and the compression's,
      to the machine's cores. Any window the tool cannot build fails the run, named, unless --allow-failed lets that
      many through: they are left out of the index (the phone builds them from their data, with the same core) and
      named, each on a line of its own beginning "not built"."""

    /** The windows' level: load::window_level, the windows' data's. */
    const val LEVEL = WindowPacks.LEVEL

    /** The frame cells' level (load::frame_cell_level): the tiles file holds a cell's windows together. */
    const val CELL_LEVEL = 10

    /** The windows unzipped and built at a time unless --batch says: 0.8 GB of Noord-Holland's answers on average, Amsterdam's 1.8 GB. */
    const val BATCH = 400

    /** zstd's level for the chunks: the packer's own render packs' (Format3Writer's). */
    const val ZSTD_LEVEL = Format3Writer.ZSTD_LEVEL

    /** What [build] made, and what it measured. */
    class Result(
        val tiles: File,
        val index: File,
        /** The windows' data's date, the index's. */
        val date: Instant,
        /** The core's window version every pack was built with. */
        val version: String,
        val windows: Int,
        /** The windows with a tile, stored; the others are listed with no bytes. */
        val drawn: Int,
        val features: Long,
        /** The stored windows' packs as the core wrote them, and as the tiles file holds them. */
        val coreBytes: Long,
        val tilesBytes: Long,
        /** Their tiles' bytes, and their chunks' as stored. */
        val chunkRaw: Long,
        val chunkBytes: Long,
        /** Their styles' bytes, and as stored: what differs from the country's, which the index holds. */
        val styleRaw: Long,
        val styleBytes: Long,
        /** The answers unzipped: all of them, and the most at a time (a batch's). */
        val jsonBytes: Long,
        val batchJsonBytes: Long,
        val millis: Long,
        val unzipMillis: Long,
        val buildMillis: Long,
        val packMillis: Long,
        /** The windows the core's tool could not build, with why ("14/x/y: why"), left out of the index; --allow-failed's. */
        val failed: List<String> = emptyList(),
        /** The frame cells' levels' tiles stored, their bytes in the tiles file, and the time their tool took. */
        val levels: Int = 0,
        val levelBytes: Long = 0,
        val levelMillis: Long = 0,
        /** The cells whose levels the tool could not build, with why ("10/x/y: why"), left without them. */
        val levelsFailed: List<String> = emptyList(),
    ) {
        val empty: Int get() = windows - drawn - failed.size
    }

    /** `packer country …` ([args] start with the command word); [out] gets the report lines. Returns the exit code. */
    fun run(args: List<String>, out: (String) -> Unit): Int {
        if (args.firstOrNull() != "country") throw UsageException("usage: $USAGE")
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
        if (positional.size != 3) throw UsageException("country takes the windows' data, its index and the output folder; usage: $USAGE")
        val data = File(positional[0])
        if (!data.isFile) throw UsageException("no windows' data $data")
        val index = File(positional[1])
        if (!index.isFile) throw UsageException("no windows' index $index")
        val core = File(options["--core"] ?: throw UsageException("country needs the core's stage P tool (--core); usage: $USAGE"))
        if (!core.canExecute()) throw UsageException("--core names no tool at $core")
        val id = options["--id"] ?: data.name.removeSuffix(Manifest.WINDOWS_SUFFIX)
        if (!ID.matches(id)) throw UsageException("bad --id '$id': lower-case letters, digits and dashes")
        val threads = options["--threads"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..64 } ?: throw UsageException("bad --threads '$it': want 1 to 64") }
            ?: Runtime.getRuntime().availableProcessors()
        val batch = options["--batch"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..100_000 } ?: throw UsageException("bad --batch '$it': want 1 to 100000") }
            ?: BATCH
        val allowFailed = options["--allow-failed"]?.let {
            it.toIntOrNull()?.takeIf { n -> n in 0..100_000 } ?: throw UsageException("bad --allow-failed '$it': want 0 to 100000")
        } ?: 0
        val r = build(data, index, File(positional[2]), id, core, threads, batch, allowFailed, log = out)
        report(r, out)
        return 0
    }

    /** The report's four lines for [r]. */
    fun report(r: Result, out: (String) -> Unit) {
        out(
            "${r.tiles.name}: ${r.windows} windows (${r.drawn} with a tile, ${r.empty} empty, listed with no bytes" +
                (if (r.failed.isEmpty()) "" else ", ${r.failed.size} not built, left out") + "), ${r.tilesBytes} bytes " +
                "from the ${r.coreBytes} the core wrote (${percent(r.tilesBytes, r.coreBytes)}); ${r.features} features; data of ${r.date}",
        )
        out(
            "  the chunks ${r.chunkBytes} bytes from ${r.chunkRaw} of tiles, the styles ${r.styleBytes} (${percent(r.styleBytes, r.tilesBytes)} of the file) " +
                "from ${r.styleRaw}, the rest ${r.tilesBytes - r.chunkBytes - r.styleBytes}",
        )
        out(
            "  the levels: ${r.levels} tiles of the frame cells' L13 and L12, ${r.levelBytes} bytes (${percent(r.levelBytes, r.tilesBytes)} of the file), " +
                "built in ${r.levelMillis / 1000} s" + if (r.levelsFailed.isEmpty()) "" else "; ${r.levelsFailed.size} cells without them",
        )
        out("${r.index.name}: ${r.index.length()} bytes with the country's style, the core's windows of version ${r.version}")
        out(
            "time: ${r.millis / 1000} s (unzipping ${r.unzipMillis / 1000} s, building ${r.buildMillis / 1000} s, compressing ${r.packMillis / 1000} s); " +
                "${r.jsonBytes shr 20} MiB of answers, at most ${r.batchJsonBytes shr 20} MiB at a time",
        )
    }

    /**
     * The tiles of the windows' [data] (its index [index]) in [outDir]: `<id>.tiles` and `<id>.tiles.idx`, each through
     * a file beside it renamed once whole. The core's tool [core] builds [batch] windows at a time on [threads] threads,
     * and as many compress its packs. Throws, naming them, where more windows than [allowFailed] do not build; those it
     * lets through are left out of the index and [log]ged, each on a line beginning "not built". The tool's other
     * failures, a pack that is not one the core writes or does not read back the same, and packs of two versions throw
     * at once.
     */
    fun build(
        data: File,
        index: File,
        outDir: File,
        id: String,
        core: File,
        threads: Int = Runtime.getRuntime().availableProcessors(),
        batch: Int = BATCH,
        allowFailed: Int = 0,
        log: (String) -> Unit = {},
    ): Result {
        val started = System.nanoTime()
        fun ms() = (System.nanoTime() - started) / 1_000_000
        val windows = WindowsIndex.read(index.readBytes())
        if (windows.level != LEVEL) throw IOException("${index.name} lists windows of level ${windows.level}, not $LEVEL")
        if (data.length() != windows.dataBytes) throw IOException("${data.name} is ${data.length()} bytes, its index says ${windows.dataBytes}")
        if (windows.size == 0) throw IOException("${index.name} lists no window")
        val shift = LEVEL - CELL_LEVEL
        val order = (0 until windows.size).map { windows[it] }.sortedWith(compareBy({ it.x ushr shift }, { it.y ushr shift }, { it.x }, { it.y }))
        // each frame cell's windows, so its levels follow its last one
        fun cellOf(e: WindowsIndex.Entry) = key(e.x ushr shift, e.y ushr shift)
        val cellTotals = order.groupingBy(::cellOf).eachCount()
        val cellsDone = HashMap<Long, Int>()
        log("windows: ${windows.size}, data of ${windows.date}; ${(order.size + batch - 1) / batch} batches of up to $batch on $threads threads")

        outDir.mkdirs()
        val tiles = File(outDir, id + Manifest.TILES_SUFFIX)
        val tilesIndex = File(outDir, id + Manifest.TILES_INDEX_SUFFIX)
        val part = File(tiles.path + ".part")
        // beside the output, on its disk; named so that no listing of the region's files takes it
        val work = Files.createTempDirectory(outDir.toPath(), ".country-").toFile()
        val answers = File(work, "answers")
        val packs = File(work, "packs")
        val list = File(work, "list.txt")
        // the windows' packs as the core wrote them, by frame cell, until the cell's levels are built
        val cells = File(work, "cells")
        fun cellDir(cell: Long) = File(cells, "${cell ushr 32}-${cell and 0xFFFFFFFFL}")
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "country").apply { isDaemon = true } }
        val entries = ArrayList<TilesIndex.Entry>(windows.size)
        val failed = ArrayList<String>()
        var version: String? = null
        // the country's style: the first window's in the file
        var template: ByteArray? = null
        var offset = 0L
        var drawn = 0
        var features = 0L
        var coreBytes = 0L
        var chunkRaw = 0L
        var chunkBytes = 0L
        var styleRaw = 0L
        var styleBytes = 0L
        var jsonBytes = 0L
        var batchJsonBytes = 0L
        var unzipMillis = 0L
        var buildMillis = 0L
        var packMillis = 0L
        var levels = 0
        var levelBytes = 0L
        var levelMillis = 0L
        val levelsFailed = ArrayList<String>()
        try {
            FileChannel.open(data.toPath(), StandardOpenOption.READ).use { channel ->
                part.outputStream().buffered(1 shl 20).use { file ->
                    fun take(p: Packed) {
                        if (version == null) version = p.version
                        if (p.version != version) throw IOException("tile ${p.z}/${p.x}/${p.y} was built by the core's version ${p.version}, the windows before it by $version")
                        val pack = p.stored
                        if (pack == null) {
                            features += p.features
                            entries += TilesIndex.Entry(p.x, p.y, 0, 0, ByteArray(32), p.z)
                            return
                        }
                        val style = p.style!!
                        val country = template ?: style.also { template = it }
                        val stored = try {
                            stored(pack, style, country)
                        } catch (x: Exception) {
                            throw IOException("tile ${p.z}/${p.x}/${p.y}: ${x.message ?: x}", x)
                        }
                        file.write(stored)
                        entries += TilesIndex.Entry(p.x, p.y, offset, stored.size, MessageDigest.getInstance("SHA-256").digest(stored), p.z)
                        offset += stored.size
                        if (p.z != LEVEL) {
                            levels++
                            levelBytes += stored.size
                            return
                        }
                        features += p.features
                        drawn++
                        coreBytes += p.coreBytes
                        chunkRaw += p.chunkRaw
                        chunkBytes += p.chunkBytes
                        styleRaw += style.size
                        // the difference: the stored pack less what it shares with the compressed one (all but its style)
                        styleBytes += stored.size - (pack.size - p.styleBytes)
                    }
                    for (b in order.chunked(batch)) {
                        val t0 = ms()
                        answers.mkdirs()
                        packs.mkdirs()
                        fun answer(e: WindowsIndex.Entry) = File(answers, "${e.x}-${e.y}.json")
                        val sizes = b.map { e -> pool.submit(Callable { unzip(channel, e, answer(e)) }) }.map(::result)
                        val json = sizes.sum()
                        jsonBytes += json
                        batchJsonBytes = maxOf(batchJsonBytes, json)
                        // the biggest answers first, so the tool's threads finish together
                        list.writeText(b.indices.sortedByDescending { sizes[it] }.joinToString("") { "${b[it].x} ${b[it].y} ${answer(b[it]).path}\n" })
                        val t1 = ms()
                        val said = buildBatch(core, list, packs, threads, b)
                        answers.deleteRecursively()
                        val t2 = ms()
                        val ahead = ArrayDeque<Future<Packed>>()
                        for (e in b) {
                            val built = said.getValue(key(e.x, e.y))
                            if (built.failed != null) {
                                failed += "$LEVEL/${e.x}/${e.y}: ${built.failed}"
                                continue
                            }
                            val keep = cellDir(cellOf(e))
                            ahead.addLast(pool.submit(Callable { pack(File(packs, "$LEVEL-${e.x}-${e.y}.atlas"), e, built, keep) }))
                            if (ahead.size >= threads * 4) take(result(ahead.removeFirst()))
                        }
                        while (ahead.isNotEmpty()) take(result(ahead.removeFirst()))
                        packs.deleteRecursively()
                        val t3 = ms()
                        unzipMillis += t1 - t0
                        buildMillis += t2 - t1
                        packMillis += t3 - t2
                        // each frame cell whose last window this batch built: its levels, after its windows
                        for (e in b) cellsDone.merge(cellOf(e), 1, Int::plus)
                        for (cell in b.map(::cellOf).distinct().filter { cellsDone[it] == cellTotals[it] }) {
                            val dir = cellDir(cell)
                            val kept = dir.listFiles { f -> f.name.endsWith(".atlas") }.orEmpty().sortedBy { it.name }
                            val name = "$CELL_LEVEL/${cell ushr 32}/${cell and 0xFFFFFFFFL}"
                            if (kept.isNotEmpty()) {
                                val t4 = ms()
                                val made = File(work, "levels")
                                made.mkdirs()
                                val tiles = buildLevels(core, kept, File(work, "levels.txt"), made, threads)
                                levelMillis += ms() - t4
                                if (tiles.failed != null) {
                                    levelsFailed += "$name: ${tiles.failed}"
                                    log("levels not built: $name: ${tiles.failed}")
                                } else {
                                    for (t in tiles.tiles) {
                                        ahead.addLast(pool.submit(Callable { packLevel(File(made, "${t.z}-${t.x}-${t.y}.atlas"), t) }))
                                        if (ahead.size >= threads * 4) take(result(ahead.removeFirst()))
                                    }
                                    while (ahead.isNotEmpty()) take(result(ahead.removeFirst()))
                                }
                                made.deleteRecursively()
                            }
                            dir.deleteRecursively()
                        }
                        val bad = b.count { said.getValue(key(it.x, it.y)).failed != null }
                        log("  ${entries.size + failed.size} of ${windows.size} windows, ${offset shr 20} MiB of tiles (${ms() / 1000} s)" + if (bad == 0) "" else "; $bad not built")
                    }
                }
            }
            if (failed.size > allowFailed) {
                throw IOException(
                    "the core's tool could not build ${failed.size} of the ${windows.size} windows: " + failed.take(20).joinToString("; ") +
                        (if (failed.size > 20) "; and ${failed.size - 20} more" else "") +
                        if (allowFailed > 0) " (--allow-failed lets $allowFailed through)" else "",
                )
            }
            // left out of the index: the phone builds them from their data, and its core (the same) refuses them as this one did
            for (f in failed) log("not built, left out: $f")
            if (version == null) throw IOException("the core's tool built none of the ${windows.size} windows")
        } catch (x: Throwable) {
            part.delete()
            throw x
        } finally {
            // the workers still unzipping or compressing finish before their folder goes
            pool.shutdownNow()
            pool.awaitTermination(1, TimeUnit.MINUTES)
            work.deleteRecursively()
        }
        move(part, tiles)
        val indexPart = File(tilesIndex.path + ".part")
        indexPart.outputStream().buffered().use { TilesIndex.of(LEVEL, windows.date, offset, version!!, template ?: ByteArray(0), entries).write(it) }
        move(indexPart, tilesIndex)
        return Result(
            tiles, tilesIndex, windows.date, version!!, windows.size, drawn, features, coreBytes, offset, chunkRaw, chunkBytes, styleRaw, styleBytes,
            jsonBytes, batchJsonBytes, ms(), unzipMillis, buildMillis, packMillis, failed.toList(),
            levels, levelBytes, levelMillis, levelsFailed.toList(),
        )
    }

    /** Window [e]'s answer from the windows' data into [to], its bytes held to the index's SHA-256. Returns the answer's length. */
    private fun unzip(channel: FileChannel, e: WindowsIndex.Entry, to: File): Long {
        val member = ByteArray(e.length)
        val b = ByteBuffer.wrap(member)
        var at = e.offset
        while (b.hasRemaining()) {
            val n = channel.read(b, at)
            if (n < 0) throw EOFException("the windows' data ends before window $LEVEL/${e.x}/${e.y}'s bytes")
            at += n
        }
        if (!MessageDigest.getInstance("SHA-256").digest(member).contentEquals(e.sha256)) throw IOException("window $LEVEL/${e.x}/${e.y}: its data is not its index's (SHA-256)")
        GZIPInputStream(member.inputStream(), 1 shl 16).use { input -> to.outputStream().use { out -> input.copyTo(out, 1 shl 16) } }
        return to.length()
    }

    /** What the core's tool said of one window: its features and its pack's bytes, or why it could not build it. */
    private class Built(val features: Long, val bytes: Long, val failed: String?)

    /** A line's words after the window's column and row (`<features> <bytes> <ms>` or `failed <why>`), or null. */
    private fun built(said: String): Built? {
        if (said.startsWith("failed ")) return Built(0, 0, said.removePrefix("failed "))
        val n = said.split(' ')
        val features = n.getOrNull(0)?.toLongOrNull()
        val bytes = n.getOrNull(1)?.toLongOrNull()
        return if (n.size == 3 && features != null && bytes != null && n[2].toDoubleOrNull() != null) Built(features, bytes, null) else null
    }

    /**
     * The windows of [batch] that [list] names built by the core's tool into [packs] on [threads] threads: what it said
     * of each (`x y <features> <bytes> <ms>` or `x y failed <why>`), by [key]. Throws where it failed otherwise or said
     * nothing of a window.
     */
    private fun buildBatch(core: File, list: File, packs: File, threads: Int, batch: List<WindowsIndex.Entry>): Map<Long, Built> {
        val p = ProcessBuilder(core.path, "windows", list.path, packs.path, "$threads").redirectError(ProcessBuilder.Redirect.INHERIT).start()
        p.outputStream.close()
        val lines = p.inputStream.bufferedReader().readLines()
        val code = p.waitFor()
        if (code != 0 && code != 1) throw IOException("${core.name} windows failed (exit $code)")
        val said = HashMap<Long, Built>()
        for (line in lines) {
            val v = line.split(' ', limit = 3)
            val x = v.getOrNull(0)?.toIntOrNull()
            val y = v.getOrNull(1)?.toIntOrNull()
            val built = v.getOrNull(2)?.let(::built)
            if (x == null || y == null || built == null) throw IOException("${core.name} windows printed '$line', not x y features bytes ms or x y failed why")
            if (said.put(key(x, y), built) != null) throw IOException("${core.name} windows spoke of window $x/$y twice")
        }
        val missing = batch.filter { key(it.x, it.y) !in said }
        if (missing.isNotEmpty()) throw IOException("${core.name} windows (exit $code) said nothing of ${missing.joinToString { "$LEVEL/${it.x}/${it.y}" }}")
        if (said.size != batch.size) throw IOException("${core.name} windows spoke of ${said.size - batch.size} windows it was not given")
        if ((code == 0) != said.values.none { it.failed != null }) throw IOException("${core.name} windows exited $code for windows that " + if (code == 0) "failed" else "all built")
        return said
    }

    /** One tile's pack (a window's, or a level's: [z]) with its chunks compressed, its style as the core wrote it, and what the core wrote. */
    private class Packed(
        val x: Int,
        val y: Int,
        val version: String,
        val features: Long,
        val coreBytes: Int,
        /** Null where the pack holds no tile. */
        val stored: ByteArray?,
        /** The style's raw bytes; null where the pack holds no tile. */
        val style: ByteArray?,
        val chunkRaw: Long,
        val chunkBytes: Long,
        /** The style's bytes compressed, as [stored] holds it. */
        val styleBytes: Long,
        val z: Int = LEVEL,
    )

    /**
     * [pack] (its chunks and its style compressed, its style's raw bytes [style]) as the tiles file stores it, its style
     * as what differs from the country's [country]; checked to be, once the phone writes its style back whole
     * (CountryStyle.unpack), [pack] with its style stored: the same manifest, sections and style.
     */
    private fun stored(pack: ByteArray, style: ByteArray, country: ByteArray): ByteArray {
        val stored = CountryStyle.store(pack, style, country)
        val core = WindowPack.read(pack)
        val phone = WindowPack.read(CountryStyle.unpack(stored, country), styleMethod = Format.METHOD_STORED)
        check(phone.manifest.contentEquals(core.manifest)) { "the phone's pack's manifest is not the core's" }
        check(phone.sections.map { it.name } == core.sections.map { it.name }) { "the phone's pack's sections are not the core's" }
        for (s in core.sections) {
            if (s.name != Format.SECTION_STYLE) check(phone.stored(s.name).contentEquals(core.stored(s.name))) { "the phone's pack's ${s.name} is not the core's" }
        }
        check(phone.style().contentEquals(style)) { "the phone's pack's style is not the core's" }
        return stored
    }

    /**
     * Window [e]'s pack in [file], as the core's tool wrote it ([built]), read, checked and recompressed; the file goes
     * once read, into [keep] (its frame cell's, for the cell's levels).
     */
    private fun pack(file: File, e: WindowsIndex.Entry, built: Built, keep: File): Packed = try {
        val bytes = file.readBytes()
        keep.mkdirs()
        if (!file.renameTo(File(keep, file.name))) throw IOException("its pack could not be kept for its cell's levels")
        if (bytes.size.toLong() != built.bytes) throw IOException("the tool said ${built.bytes} bytes and wrote ${bytes.size}")
        val core = WindowPack.read(bytes)
        if (core.window != "$LEVEL/${e.x}/${e.y}") throw IOException("its pack is window ${core.window}'s")
        if (core.features != built.features) throw IOException("the tool said ${built.features} features, its pack holds ${core.features}")
        recompressed(core, e.x, e.y, LEVEL, bytes.size)
    } catch (x: Exception) {
        throw IOException("window $LEVEL/${e.x}/${e.y}: ${x.message ?: x}", x)
    }

    /** [core]'s pack (tile [z]/[x]/[y], [size] bytes as the core wrote it) with its chunks compressed. */
    private fun recompressed(core: WindowPack, x: Int, y: Int, z: Int, size: Int): Packed = if (core.chunks.isEmpty()) {
        Packed(x, y, core.version, 0, size, null, null, 0, 0, 0, z)
    } else {
        // the frames say their sizes and carry no checksum (each tile's SHA-256 is in the chunk table)
        val stored = ZstdCompressCtx().use { zstd -> core.recompressed(zstd.setLevel(ZSTD_LEVEL).setContentSize(true).setChecksum(false)) }
        val again = WindowPack.read(stored)
        Packed(
            x, y, core.version, core.features, size, stored, core.style(),
            core.chunks.sumOf { it.raw.toLong() }, again.section(Format.SECTION_CHUNKS).stored.toLong(),
            again.section(Format.SECTION_STYLE).stored.toLong(), z,
        )
    }

    /** A level's tile the core's tool said it built: its key, its features and its pack's bytes. */
    private class LevelTile(val z: Int, val x: Int, val y: Int, val features: Long, val bytes: Long)

    /** What the core's tool said of a frame cell's levels: the tiles it built, or why it could not. */
    private class Levels(val tiles: List<LevelTile>, val failed: String?)

    /**
     * The levels of the frame cell whose windows' packs (as the core wrote them) are [packs], built by the core's tool
     * into [out] on [threads] threads through the list [list]: what it said (`z x y <features> <bytes> <raw> <buildings>
     * <trees>` a tile, then `cell x y <tiles> <ms>` or `cell x y failed <why>`). Throws where it failed otherwise.
     */
    private fun buildLevels(core: File, packs: List<File>, list: File, out: File, threads: Int): Levels {
        list.writeText(packs.joinToString("") { f -> f.name.removeSuffix(".atlas").split('-').let { "${it[1]} ${it[2]} ${f.path}\n" } })
        val p = ProcessBuilder(core.path, "levels", list.path, out.path, "$threads").redirectError(ProcessBuilder.Redirect.INHERIT).start()
        p.outputStream.close()
        val lines = p.inputStream.bufferedReader().readLines()
        val code = p.waitFor()
        if (code != 0 && code != 1) throw IOException("${core.name} levels failed (exit $code)")
        val tiles = ArrayList<LevelTile>()
        var failed: String? = null
        var ended = false
        for (line in lines) {
            val v = line.split(' ')
            if (v.firstOrNull() == "cell") {
                ended = true
                if (v.getOrNull(3) == "failed") failed = line.split(' ', limit = 5).getOrElse(4) { "" }
                continue
            }
            val n = v.map { it.toLongOrNull() }
            if (v.size != 8 || n.any { it == null } || n[0]!! !in TilesIndex.LEVELS.map { it.toLong() }) {
                throw IOException("${core.name} levels printed '$line', not z x y features bytes raw buildings trees")
            }
            tiles += LevelTile(n[0]!!.toInt(), n[1]!!.toInt(), n[2]!!.toInt(), n[3]!!, n[4]!!)
        }
        if (!ended) throw IOException("${core.name} levels (exit $code) said nothing of its cell")
        if ((code == 0) != (failed == null)) throw IOException("${core.name} levels exited $code for a cell that " + if (code == 0) "failed" else "was built")
        return Levels(if (failed == null) tiles else emptyList(), failed)
    }

    /** Level tile [t]'s pack in [file], as the core's tool wrote it, read, checked and recompressed; the file goes once read. */
    private fun packLevel(file: File, t: LevelTile): Packed = try {
        val bytes = file.readBytes()
        file.delete()
        if (bytes.size.toLong() != t.bytes) throw IOException("the tool said ${t.bytes} bytes and wrote ${bytes.size}")
        val core = WindowPack.read(bytes)
        if (core.window != "${t.z}/${t.x}/${t.y}") throw IOException("its pack is tile ${core.window}'s")
        if (core.features != t.features) throw IOException("the tool said ${t.features} features, its pack holds ${core.features}")
        recompressed(core, t.x, t.y, t.z, bytes.size)
    } catch (x: Exception) {
        throw IOException("tile ${t.z}/${t.x}/${t.y}: ${x.message ?: x}", x)
    }

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    private fun percent(part: Long, whole: Long): String = if (whole <= 0) "-" else "%.1f %%".format(Locale.ROOT, 100.0 * part / whole)

    private fun <T> result(f: Future<T>): T = try {
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

    private val VALUED = setOf("--core", "--id", "--threads", "--batch", "--allow-failed")
    private val ID = Regex("[a-z0-9][a-z0-9-]*")
}
