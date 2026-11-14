package app.atlas.packer

import app.atlas.tiles.WindowsIndex
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * `packer windows-join`: a run's shards ([WindowPacks] `--shard I/N`, each in a folder of its own) joined into the
 * region's windows' data, byte for byte the files the run whole writes: the shards' data back to back, and one index of
 * their entries, each moved on by the data before it. The shards must be one run's, which every check is made of before
 * a byte is written: the same plan and date, shard I in the I-th folder, each listed window in its shard's columns and
 * after the one before it, and the shards' windows adding up to the run's.
 */
object WindowsJoin {
    const val USAGE = """packer windows-join <out-dir> <shard-dir>... --id ID [--remove]
      Joins the shards of a windows run (packer windows --shard I/N; shard I's folder the I-th) into <out-dir>/<id>.windows
      and <id>.windows.idx, byte for byte the files the run whole writes. --remove deletes each shard's files as it is
      joined (once every check has passed), so the disk holds the data about once."""

    /** What [join] wrote. */
    class Joined(val data: File, val index: File, val windows: Int, val shards: Int, val dataBytes: Long, val date: Instant)

    /** `packer windows-join …` ([args] start with the command word); [out] gets the report lines. Returns the exit code. */
    fun run(args: List<String>, out: (String) -> Unit): Int {
        if (args.firstOrNull() != "windows-join") throw UsageException("usage: $USAGE")
        val positional = ArrayList<String>()
        var id: String? = null
        var remove = false
        var i = 1
        while (i < args.size) {
            when (val s = args[i]) {
                "--id" -> id = args.getOrNull(++i) ?: throw UsageException("--id needs a value")
                "--remove" -> remove = true
                else -> if (s.startsWith("--")) throw UsageException("unknown option $s; usage: $USAGE") else positional += s
            }
            i++
        }
        if (positional.size < 2) throw UsageException("windows-join takes the output folder and the shards' folders; usage: $USAGE")
        val name = id ?: throw UsageException("windows-join needs the region's --id; usage: $USAGE")
        if (!WindowPacks.ID.matches(name)) throw UsageException("bad --id '$name': lower-case letters, digits and dashes")
        val r = join(File(positional[0]), positional.drop(1).map(::File), name, remove)
        out("${r.data.name}: ${r.windows} windows from ${r.shards} shards, ${r.dataBytes} bytes; data of ${r.date}")
        out("${r.index.name}: ${r.index.length()} bytes")
        return 0
    }

    /** Joins the shards in [dirs] (shard I the I-th) of region [id] into [outDir]; with [remove], deletes them as it goes. */
    fun join(outDir: File, dirs: List<File>, id: String, remove: Boolean = false): Joined {
        class Part(val dir: File, val shard: WindowPacks.Shard, val index: WindowsIndex, val data: File, val files: List<File>)
        if (dirs.isEmpty()) throw IOException("no shards to join")
        val parts = dirs.map { dir ->
            val side = File(dir, id + WindowPacks.SHARD_SUFFIX)
            val idx = File(dir, id + Manifest.WINDOWS_INDEX_SUFFIX)
            val data = File(dir, id + Manifest.WINDOWS_SUFFIX)
            for (f in listOf(side, idx, data)) if (!f.isFile) throw IOException("no $f: $dir holds no shard of $id")
            val index = WindowsIndex.read(idx.readBytes())
            if (data.length() != index.dataBytes) throw IOException("$data holds ${data.length()} bytes, its index ${index.dataBytes}")
            Part(dir, WindowPacks.Shard.parse(side.readText(), side), index, data, listOf(data, idx, side))
        }
        val first = parts[0]
        if (parts.size != first.shard.count) throw IOException("${parts.size} folders for the ${first.shard.count} shards of ${first.dir}'s run")
        var windows = 0
        var lastX = -1
        var lastY = -1
        for ((k, p) in parts.withIndex()) {
            val s = p.shard
            if (s.index != k + 1) throw IOException("${p.dir} holds shard ${s.index} of ${s.count}, where shard ${k + 1} belongs")
            if (s.count != first.shard.count || s.plan != first.shard.plan || s.of != first.shard.of) {
                throw IOException("${p.dir}'s shard is of another run than ${first.dir}'s: another plan or count of windows")
            }
            if (p.index.level != first.index.level || p.index.query != first.index.query || p.index.date != first.index.date) {
                throw IOException("${p.dir}'s windows are of another level, query or date than ${first.dir}'s")
            }
            windows += s.windows
            var at = 0L
            for (j in 0 until p.index.size) {
                val e = p.index[j]
                if ((e.x shr 4) !in s.columns) throw IOException("window ${e.x}/${e.y} of ${p.dir} lies outside its shard's columns ${WindowPacks.Shard.range(s.columns)}")
                if (e.x < lastX || (e.x == lastX && e.y <= lastY)) throw IOException("window ${e.x}/${e.y} of ${p.dir} comes after $lastX/$lastY")
                if (e.offset != at) throw IOException("window ${e.x}/${e.y} of ${p.dir} lies at ${e.offset}, not $at")
                at += e.length
                lastX = e.x
                lastY = e.y
            }
            if (at != p.index.dataBytes) throw IOException("${p.dir}'s windows hold $at of its ${p.index.dataBytes} bytes")
        }
        if (windows != first.shard.of) throw IOException("the shards hold $windows of their run's ${first.shard.of} windows")

        outDir.mkdirs()
        val data = File(outDir, id + Manifest.WINDOWS_SUFFIX)
        val index = File(outDir, id + Manifest.WINDOWS_INDEX_SUFFIX)
        val dataPart = File(data.path + ".part")
        val entries = ArrayList<WindowsIndex.Entry>(parts.sumOf { it.index.size })
        var offset = 0L
        try {
            dataPart.outputStream().use { o ->
                for (p in parts) {
                    p.data.inputStream().use { it.copyTo(o, 1 shl 20) }
                    for (j in 0 until p.index.size) {
                        val e = p.index[j]
                        entries += WindowsIndex.Entry(e.x, e.y, offset + e.offset, e.length, e.sha256)
                    }
                    offset += p.index.dataBytes
                    if (remove) p.data.delete()
                }
            }
        } catch (x: Throwable) {
            dataPart.delete()
            throw x
        }
        if (dataPart.length() != offset) throw IOException("$dataPart holds ${dataPart.length()} bytes of the shards' $offset")
        WindowPacks.move(dataPart, data)
        val indexPart = File(index.path + ".part")
        indexPart.outputStream().buffered().use { WindowsIndex.of(first.index.level, first.index.query, first.index.date, offset, entries).write(it) }
        WindowPacks.move(indexPart, index)
        if (remove) for (p in parts) {
            for (f in p.files) f.delete()
            p.dir.delete()
        }
        return Joined(data, index, entries.size, parts.size, offset, first.index.date)
    }
}
