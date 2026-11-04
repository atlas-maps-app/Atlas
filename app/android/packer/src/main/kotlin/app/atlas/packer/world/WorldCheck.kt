package app.atlas.packer.world

import app.atlas.packer.UsageException
import app.atlas.tiles.WorldIndex
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * `packer world-check`: a world's index against its parts as the phone will fetch them (ENGINE.md step 5's world.yml,
 * the assemble job, over the release's own assets): every part there at its length and made of its packs end to end,
 * every pack's bytes of the SHA-256 the index names and, with the core's stage P tool, every pack read as the phone's
 * store reads it, each tile decoded against its chunk (`atlas_stagep check`), as many as the index says, each of the
 * pack's own block and levels.
 */
object WorldCheck {
    const val USAGE = """packer world-check <world.idx> <parts-dir> [--core <atlas_stagep>] [--threads N]
      A world's index checked against its parts in <parts-dir>: each part at the length the index gives and made of its
      packs end to end, each pack of the SHA-256 the index names; with --core, each pack read by the core's stage P tool
      (`atlas_stagep check`) as the phone's store reads it, every tile decoded, as many as the index says, each of the
      pack's block and levels. --threads (4 unless given) checks that many packs at once."""

    fun run(args: List<String>, out: (String) -> Unit): Int {
        if (args.firstOrNull() != "world-check") throw UsageException("usage: $USAGE")
        val positional = ArrayList<String>()
        val options = LinkedHashMap<String, String>()
        var i = 1
        while (i < args.size) {
            val s = args[i]
            if (s.startsWith("--")) {
                if (s !in VALUED) throw UsageException("unknown option $s; usage: $USAGE")
                if (i + 1 >= args.size) throw UsageException("$s needs a value")
                options[s] = args[++i]
            } else {
                positional += s
            }
            i++
        }
        if (positional.size != 2) throw UsageException("world-check takes the index and the parts' folder; usage: $USAGE")
        val idx = File(positional[0])
        if (!idx.isFile) throw UsageException("no index $idx")
        val dir = File(positional[1])
        if (!dir.isDirectory) throw UsageException("no folder $dir")
        val core = options["--core"]?.let(::File)
        if (core != null && !core.canExecute()) throw UsageException("no stage P tool $core")
        val threads = options["--threads"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..64 } ?: throw UsageException("bad --threads '$it'") } ?: 4
        val problems = check(WorldIndex.read(idx.readBytes()), dir, core, threads, out)
        for (p in problems) out("not as the index says: $p")
        return if (problems.isEmpty()) 0 else 1
    }

    private val VALUED = setOf("--core", "--threads")

    /** What is wrong with [index]'s parts in [dir], none when all is as it says; [core], when given, decodes every pack. */
    fun check(index: WorldIndex, dir: File, core: File?, threads: Int, log: (String) -> Unit): List<String> {
        val problems = ArrayList<String>()
        val byPart = index.entries.groupBy { it.part }
        for ((p, part) in index.parts.withIndex()) {
            val f = File(dir, part.name)
            if (!f.isFile) {
                problems += "${part.name}: not there"
                continue
            }
            if (f.length() != part.bytes) {
                problems += "${part.name}: ${f.length()} bytes, the index gives ${part.bytes}"
                continue
            }
            var at = 0L
            for (e in byPart[p].orEmpty().sortedBy { it.offset }) {
                if (e.offset != at) problems += "${part.name}: ${name(e)} at ${e.offset}, where the packs before it end at $at"
                at = e.offset + e.length
            }
            if (at != part.bytes) problems += "${part.name}: its packs end at $at of its ${part.bytes} bytes"
        }
        if (problems.isNotEmpty()) return problems
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val checks = index.entries.map { e -> pool.submit(Callable { pack(e, File(dir, index.parts[e.part].name), core) }) }
            for (c in checks) problems += c.get()
        } finally {
            pool.shutdown()
        }
        val decoded = if (core != null) ", every tile decoded by the core" else ""
        log(
            "world-check: ${index.entries.size} packs of ${index.entries.sumOf { it.tiles }} tiles in ${index.parts.size} parts " +
                "(${index.parts.sumOf { it.bytes }} bytes)$decoded: ${if (problems.isEmpty()) "as the index says" else "${problems.size} not as it says"}",
        )
        return problems
    }

    private fun name(e: WorldIndex.Entry) = "pack ${e.z}/${e.x}/${e.y}"

    /** What is wrong with pack [e] in [part]: its SHA-256 and, with [core], its tiles. */
    private fun pack(e: WorldIndex.Entry, part: File, core: File?): List<String> {
        val md = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(part, "r").use { f ->
            f.seek(e.offset)
            val buf = ByteArray(1 shl 20)
            var left = e.length
            while (left > 0) {
                val n = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                check(n > 0) { "${part.name} ends inside ${name(e)}" }
                md.update(buf, 0, n)
                left -= n
            }
        }
        if (!md.digest().contentEquals(e.sha256)) return listOf("${name(e)}: its bytes are not of the SHA-256 the index names")
        if (core == null) return emptyList()
        val p = ProcessBuilder(core.path, "check", part.path, "${e.offset}", "${e.length}").redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines()
        val code = p.waitFor()
        if (code != 0) return listOf("${name(e)}: the core cannot read it (${lines.lastOrNull() ?: "exit $code"})")
        // `tile <level> <x> <y> <features> <hash>`, one a tile
        val tiles = lines.filter { it.startsWith("tile ") }.map { it.split(' ') }
        if (tiles.size != e.tiles) return listOf("${name(e)}: the core decoded ${tiles.size} tiles, the index says ${e.tiles}")
        for (t in tiles) {
            val z = t[1].toInt()
            val x = t[2].toInt()
            val y = t[3].toInt()
            if (z !in e.firstLevel..e.lastLevel || z < e.z || x shr (z - e.z) != e.x || y shr (z - e.z) != e.y) {
                return listOf("${name(e)}: tile $z/$x/$y is not of its block and levels")
            }
        }
        return emptyList()
    }
}
