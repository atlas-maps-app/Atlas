package app.atlas.packer

import app.atlas.packer.country.WindowPack
import app.atlas.rendertiles.codec.Format3Writer
import app.atlas.rendertiles.model.Format
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
 * frame and its country's looks), and its format-3 pack is stored with its chunks and its style compressed at zstd's
 * level 19, where the core writes them as raw blocks (it vendors zstd's decompression alone), and every other byte as
 * the core wrote it ([WindowPack.recompressed]): its tiles are the phone's byte for byte. The packs lie back to back in
 * `<id>.tiles`, with their index `<id>.tiles.idx` ([TilesIndex]: each window's range and SHA-256, the windows' data's
 * date, the core's window version). The phone downloads the index whole and a window's pack by an HTTP range, and
 * builds only the windows the pack lacks; a window whose pack holds no tile (the open sea) is listed with no bytes.
 *
 * The windows go to the tool in batches, their answers unzipped beside the output and deleted once built, the batch's
 * packs once stored (a country's answers are many times its windows' data: Noord-Holland's 0.6 GB gzipped are 4.6 GB of
 * JSON). A batch's list gives the tool its biggest answers first, so its threads finish together. In the tiles
 * file the windows lie frame cell by frame cell (load::frame_cell_level's L10 tile, whose frame they are drawn in), each
 * cell's in order of x, then y, so a cell's packs are one range.
 */
object CountryTiles {
    const val USAGE = """packer country <region.windows> <region.windows.idx> <out-dir> --core <atlas_stagep> [--id ID] [--threads N] [--batch N]
      A country's tiles from its windows' data (packer windows): every window built by the core's stage P tool (--core)
      as the phone builds it (atlas_stagep windows), each window's pack with its chunks and its style compressed at
      zstd 19 and every other byte the core's, checked to decode to the core's own tiles and style, back to back in
      <out-dir>/<id>.tiles, with their index <id>.tiles.idx (TilesIndex); a window whose pack holds no tile is listed
      with no bytes. The answers are unzipped --batch windows at a time (400 unless given) into a folder in <out-dir>,
      deleted once built. --id defaults to the data's name less .windows; --threads, the tool's and the compression's,
      to the machine's cores. Any window the tool cannot build fails the run, named."""

    /** The windows' level: load::window_level, the windows' data's. */
    const val LEVEL = WindowPacks.LEVEL

    /** The frame cells' level (load::frame_cell_level): the tiles file holds a cell's windows together. */
    const val CELL_LEVEL = 10

    /** The windows unzipped and built at a time unless --batch says: 0.8 GB of Noord-Holland's answers on average, Amsterdam's 1.8 GB. */
    const val BATCH = 400

    /** zstd's level for the chunks and the style: the packer's own render packs' (Format3Writer's). */
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
        /** Their styles' bytes, and as stored. */
        val styleRaw: Long,
        val styleBytes: Long,
        /** The answers unzipped: all of them, and the most at a time (a batch's). */
        val jsonBytes: Long,
        val batchJsonBytes: Long,
        val millis: Long,
        val unzipMillis: Long,
        val buildMillis: Long,
        val packMillis: Long,
    ) {
        val empty: Int get() = windows - drawn
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
        val r = build(data, index, File(positional[2]), id, core, threads, batch, log = out)
        report(r, out)
        return 0
    }

    /** The report's four lines for [r]. */
    fun report(r: Result, out: (String) -> Unit) {
        out(
            "${r.tiles.name}: ${r.windows} windows (${r.drawn} with a tile, ${r.empty} empty, listed with no bytes), ${r.tilesBytes} bytes " +
                "from the ${r.coreBytes} the core wrote (${percent(r.tilesBytes, r.coreBytes)}); ${r.features} features; data of ${r.date}",
        )
        out(
            "  the chunks ${r.chunkBytes} bytes from ${r.chunkRaw} of tiles, the styles ${r.styleBytes} (${percent(r.styleBytes, r.tilesBytes)} of the file) " +
                "from ${r.styleRaw}, the rest ${r.tilesBytes - r.chunkBytes - r.styleBytes}",
        )
        out("${r.index.name}: ${r.index.length()} bytes, the core's windows of version ${r.version}")
        out(
            "time: ${r.millis / 1000} s (unzipping ${r.unzipMillis / 1000} s, building ${r.buildMillis / 1000} s, compressing ${r.packMillis / 1000} s); " +
                "${r.jsonBytes shr 20} MiB of answers, at most ${r.batchJsonBytes shr 20} MiB at a time",
        )
    }

    /**
     * The tiles of the windows' [data] (its index [index]) in [outDir]: `<id>.tiles` and `<id>.tiles.idx`, each through
     * a file beside it renamed once whole. The core's tool [core] builds [batch] windows at a time on [threads] threads,
     * and as many compress its packs. Throws, naming them, where any window does not build; the tool's other failures,
     * a pack that is not one the core writes or does not read back the same, and packs of two versions throw at once.
     */
    fun build(
        data: File,
        index: File,
        outDir: File,
        id: String,
        core: File,
        threads: Int = Runtime.getRuntime().availableProcessors(),
        batch: Int = BATCH,
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
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "country").apply { isDaemon = true } }
        val entries = ArrayList<TilesIndex.Entry>(windows.size)
        val failed = ArrayList<String>()
        var version: String? = null
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
        try {
            FileChannel.open(data.toPath(), StandardOpenOption.READ).use { channel ->
                part.outputStream().buffered(1 shl 20).use { file ->
                    fun take(p: Packed) {
                        if (version == null) version = p.version
                        if (p.version != version) throw IOException("window $LEVEL/${p.x}/${p.y} was built by the core's version ${p.version}, the windows before it by $version")
                        features += p.features
                        val stored = p.stored
                        if (stored == null) {
                            entries += TilesIndex.Entry(p.x, p.y, 0, 0, ByteArray(32))
                            return
                        }
                        file.write(stored)
                        entries += TilesIndex.Entry(p.x, p.y, offset, stored.size, p.sha256!!)
                        offset += stored.size
                        drawn++
                        coreBytes += p.coreBytes
                        chunkRaw += p.chunkRaw
                        chunkBytes += p.chunkBytes
                        styleRaw += p.styleRaw
                        styleBytes += p.styleBytes
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
                            ahead.addLast(pool.submit(Callable { pack(File(packs, "$LEVEL-${e.x}-${e.y}.atlas"), e, built) }))
                            if (ahead.size >= threads * 4) take(result(ahead.removeFirst()))
                        }
                        while (ahead.isNotEmpty()) take(result(ahead.removeFirst()))
                        packs.deleteRecursively()
                        val t3 = ms()
                        unzipMillis += t1 - t0
                        buildMillis += t2 - t1
                        packMillis += t3 - t2
                        val bad = b.count { said.getValue(key(it.x, it.y)).failed != null }
                        log("  ${entries.size + failed.size} of ${windows.size} windows, ${offset shr 20} MiB of tiles (${ms() / 1000} s)" + if (bad == 0) "" else "; $bad not built")
                    }
                }
            }
            if (failed.isNotEmpty()) {
                throw IOException(
                    "the core's tool could not build ${failed.size} of the ${windows.size} windows: " + failed.take(20).joinToString("; ") +
                        if (failed.size > 20) "; and ${failed.size - 20} more" else "",
                )
            }
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
        indexPart.outputStream().buffered().use { TilesIndex.of(LEVEL, windows.date, offset, version!!, entries).write(it) }
        move(indexPart, tilesIndex)
        return Result(
            tiles, tilesIndex, windows.date, version!!, windows.size, drawn, features, coreBytes, offset, chunkRaw, chunkBytes, styleRaw, styleBytes,
            jsonBytes, batchJsonBytes, ms(), unzipMillis, buildMillis, packMillis,
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

    /** One window's pack as the tiles file stores it, and what the core wrote. */
    private class Packed(
        val x: Int,
        val y: Int,
        val version: String,
        val features: Long,
        val coreBytes: Int,
        /** Null where the pack holds no tile. */
        val stored: ByteArray?,
        val sha256: ByteArray?,
        val chunkRaw: Long,
        val chunkBytes: Long,
        val styleRaw: Long,
        val styleBytes: Long,
    )

    /** Window [e]'s pack in [file], as the core's tool wrote it ([built]), read, checked and recompressed; the file goes once read. */
    private fun pack(file: File, e: WindowsIndex.Entry, built: Built): Packed = try {
        val bytes = file.readBytes()
        file.delete()
        if (bytes.size.toLong() != built.bytes) throw IOException("the tool said ${built.bytes} bytes and wrote ${bytes.size}")
        val core = WindowPack.read(bytes)
        if (core.window != "$LEVEL/${e.x}/${e.y}") throw IOException("its pack is window ${core.window}'s")
        if (core.features != built.features) throw IOException("the tool said ${built.features} features, its pack holds ${core.features}")
        if (core.chunks.isEmpty()) {
            Packed(e.x, e.y, core.version, 0, bytes.size, null, null, 0, 0, 0, 0)
        } else {
            // the frames say their sizes and carry no checksum (each tile's SHA-256 is in the chunk table)
            val stored = ZstdCompressCtx().use { zstd -> core.recompressed(zstd.setLevel(ZSTD_LEVEL).setContentSize(true).setChecksum(false)) }
            val again = WindowPack.read(stored)
            Packed(
                e.x, e.y, core.version, core.features, bytes.size, stored, MessageDigest.getInstance("SHA-256").digest(stored),
                core.chunks.sumOf { it.raw.toLong() }, again.section(Format.SECTION_CHUNKS).stored.toLong(),
                core.section(Format.SECTION_STYLE).raw, again.section(Format.SECTION_STYLE).stored.toLong(),
            )
        }
    } catch (x: Exception) {
        throw IOException("window $LEVEL/${e.x}/${e.y}: ${x.message ?: x}", x)
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

    private val VALUED = setOf("--core", "--id", "--threads", "--batch")
    private val ID = Regex("[a-z0-9][a-z0-9-]*")
}
