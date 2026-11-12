package app.atlas.packer.heights

import app.atlas.packer.UsageException
import app.atlas.packer.world.WorldCheck
import app.atlas.render.data.ByteSource
import app.atlas.rendertiles.codec.HeightsCodec
import app.atlas.rendertiles.codec.TileFormatException
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.HeightRaster
import app.atlas.rendertiles.model.Heights
import app.atlas.rendertiles.model.Profile
import app.atlas.rendertiles.model.RenderHead
import app.atlas.tiles.WorldIndex
import com.github.luben.zstd.Zstd
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** A terrain pack read back: its head and its chunk table, each tile's raster decoded with every check ([Heights]). */
class TerrainPackReader(private val bytes: ByteArray) {
    class Chunk(val z: Int, val x: Int, val y: Int, val offset: Int, val stored: Int, val raw: Int, val sha256: ByteArray)

    val head: RenderHead
    val chunks: List<Chunk>

    init {
        if (bytes.size < 20 || String(bytes, 0, 8, Charsets.US_ASCII) != "ATLASPCK") throw TileFormatException("not an Atlas pack")
        val h = ByteSource(bytes, 8, 20)
        h.byte()
        val version = h.byte()
        if (version != Format.VERSION) throw TileFormatException("format $version, not ${Format.VERSION}")
        h.byte(); h.byte()
        val manifest = h.u32()
        val directory = h.u32()
        if (20 + manifest + directory > bytes.size) throw TileFormatException("the manifest and directory run past the pack")
        val dir = ByteSource(bytes, (20 + manifest).toInt(), (20 + manifest + directory).toInt())
        class Section(val offset: Long, val stored: Long, val raw: Long, val method: Int)
        val sections = HashMap<String, Section>()
        while (dir.remaining > 0) {
            val name = dir.string()
            val s = Section(dir.u64(), dir.u64(), dir.u64(), dir.u32().let { dir.byte() })
            if (s.offset < 0 || s.stored < 0 || s.offset + s.stored > bytes.size) throw TileFormatException("section $name runs past the pack")
            sections[name] = s
        }
        fun section(name: String) = sections[name] ?: throw TileFormatException("no section $name")
        val hs = section(Format.SECTION_HEAD)
        head = RenderHead.fromBytes(bytes.copyOfRange(hs.offset.toInt(), (hs.offset + hs.stored).toInt()))
        if (head.profile != Profile.TERRAIN) throw TileFormatException("a pack of profile ${head.profile.label}, not terrain")
        val ts = section(Format.SECTION_TILES)
        val cs = section(Format.SECTION_CHUNKS)
        val t = ByteSource(bytes, ts.offset.toInt(), (ts.offset + ts.stored).toInt())
        val n = t.varInt()
        if (n < 0 || n > t.remaining / 38) throw TileFormatException("$n chunks in a table of ${t.remaining} bytes")
        var at = cs.offset
        val list = ArrayList<Chunk>(n)
        repeat(n) {
            val c = Chunk(t.byte(), t.varInt(), t.varInt(), at.toInt(), t.varInt(), t.varInt(), t.varInt().let { t.bytes(32) })
            if (c.stored < 0 || c.raw < 0 || c.raw > Format.MAX_TILE_BYTES || c.z !in head.levels) throw TileFormatException("chunk ${c.z}/${c.x}/${c.y}: ${c.stored} bytes stored, ${c.raw} raw")
            at += c.stored
            list += c
        }
        if (t.remaining != 0 || at != cs.offset + cs.stored) throw TileFormatException("the chunks do not fill their section")
        chunks = list
    }

    /** [c]'s tile, decompressed, held to its SHA-256, decoded, and held to its place in the table. */
    fun decode(c: Chunk): HeightsCodec.Decoded {
        val raw = Zstd.decompress(bytes.copyOfRange(c.offset, c.offset + c.stored), c.raw)
        if (raw.size != c.raw || !MessageDigest.getInstance("SHA-256").digest(raw).contentEquals(c.sha256)) throw TileFormatException("chunk ${c.z}/${c.x}/${c.y} is damaged")
        val d = HeightsCodec.decodeTile(raw)
        if (d.level != c.z || d.x != c.x || d.y != c.y) throw TileFormatException("chunk ${c.z}/${c.x}/${c.y} holds tile ${d.level}/${d.x}/${d.y}")
        return d
    }
}

/**
 * `packer heights-check`: a region's terrain as `packer heights` wrote it, checked (ENGINE.md part 6, step 1's Done):
 * the index against its parts ([WorldCheck]); every tile decoded by the packer's reader and, with `--core`, by the
 * core's host build (`atlas_stagep check`), the two agreeing on every raster's numbers and on the hash of its samples;
 * every apron and edge sample equal to the neighbour's own; and, with `--points`, that many samples over the boards'
 * views read afresh from their source's host and averaged as the packer averages, each within a step of the pack's.
 */
object HeightsCheck {
    const val USAGE = """packer heights-check <region> <dir> [--core <atlas_stagep>] [--points N] [--registry FILE] [--repo DIR] [--gdal DIR] [--work DIR]
      A region's terrain in <dir> (<region>-terrain.idx and its parts) checked: the index against its parts, every tile
      decoded, every apron against its neighbours' samples; with --core, every tile decoded by the core's host build too
      and its rasters' hashes compared; with --points, N samples over the boards' views (the registry's check_views)
      read afresh from their sources and averaged as the packer averages (GDAL's tools: --gdal their folder), each to be
      within a step of the pack's."""

    private val VALUED = setOf("--core", "--points", "--registry", "--repo", "--gdal", "--work")

    fun run(args: List<String>, out: (String) -> Unit): Int {
        if (args.firstOrNull() != "heights-check") throw UsageException("usage: $USAGE")
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
        if (positional.size != 2) throw UsageException("heights-check takes the region and its terrain's folder; usage: $USAGE")
        val region = positional[0]
        val dir = File(positional[1])
        val idx = File(dir, "$region-terrain.idx")
        if (!idx.isFile) throw UsageException("no index $idx")
        val core = options["--core"]?.let(::File)
        if (core != null && !core.canExecute()) throw UsageException("no stage P tool $core")
        val index = WorldIndex.read(idx.readBytes())
        val problems = ArrayList<String>()
        problems += WorldCheck.check(index, dir, null, 4, out)
        val terrain = if (problems.isEmpty()) Terrain(index, dir) else null
        if (terrain != null) {
            problems += tiles(terrain, core, out)
            if (problems.isEmpty()) problems += aprons(terrain, out)
            val points = options["--points"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..1000 } ?: throw UsageException("bad --points '$it'") }
            if (points != null && problems.isEmpty()) {
                val repo = File(options["--repo"] ?: ".").absoluteFile
                val registry = TerrainSources.read(options["--registry"]?.let(::File) ?: File(repo, "app/android/packs/terrain-sources.json"))
                val work = options["--work"]?.let(::File) ?: File(dir, "check-work")
                problems += points(terrain, registry, registry.region(region), repo, points, Gdal(options["--gdal"]?.let(::File)), Fetcher(Fetcher.USER_AGENT, out), work, out)
                work.deleteRecursively()
            }
        }
        for (p in problems.take(40)) out("not as it should be: $p")
        if (problems.size > 40) out("… and ${problems.size - 40} more")
        return if (problems.isEmpty()) 0 else 1
    }

    /** A region's terrain on disk: its index, and every tile's place in its packs. */
    class Terrain(val index: WorldIndex, val dir: File) {
        class Place(val entry: WorldIndex.Entry, val chunk: TerrainPackReader.Chunk)

        val places = HashMap<Long, Place>()
        private val packs = object : LinkedHashMap<WorldIndex.Entry, TerrainPackReader>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<WorldIndex.Entry, TerrainPackReader>?): Boolean = size > 6
        }
        private val rasters = object : LinkedHashMap<Long, HeightRaster>(512, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, HeightRaster>?): Boolean = size > 300
        }

        fun key(z: Int, x: Int, y: Int): Long = (z.toLong() shl 48) or (x.toLong() shl 24) or y.toLong()

        fun pack(e: WorldIndex.Entry): TerrainPackReader = packs.getOrPut(e) {
            RandomAccessFile(File(dir, index.parts[e.part].name), "r").use { f ->
                val b = ByteArray(Math.toIntExact(e.length))
                f.seek(e.offset)
                f.readFully(b)
                TerrainPackReader(b)
            }
        }

        init {
            for (e in index.entries) for (c in pack(e).chunks) {
                require(places.put(key(c.z, c.x, c.y), Place(e, c)) == null) { "tile ${c.z}/${c.x}/${c.y} in two packs" }
            }
        }

        /** Tile ([z], [x], [y])'s raster, or null where the terrain holds none. */
        fun raster(z: Int, x: Int, y: Int): HeightRaster? {
            val k = key(z, x, y)
            rasters[k]?.let { return it }
            val p = places[k] ?: return null
            return pack(p.entry).decode(p.chunk).raster.also { rasters[k] = it }
        }
    }

    /** The raster's hash as the core computes it (tiles/Heights.hpp's heights_hash). */
    fun hash(r: HeightRaster): String {
        val b = ByteBuffer.allocate(20 + r.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(r.side.toShort()).putShort(r.apron.toShort()).putLong(r.step.toRawBits()).putLong(r.base)
        for (s in r.samples) b.putInt(s)
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** Every tile decoded here and, with [core], by the core's host build: what does not agree. */
    fun tiles(terrain: Terrain, core: File?, log: (String) -> Unit): List<String> {
        val problems = ArrayList<String>()
        var count = 0
        var low = Double.MAX_VALUE
        var high = -Double.MAX_VALUE
        for (e in terrain.index.entries) {
            val pack = try {
                terrain.pack(e)
            } catch (x: RuntimeException) {
                problems += "pack ${e.z}/${e.x}/${e.y}: ${x.message}"
                continue
            }
            if (pack.chunks.size != e.tiles) problems += "pack ${e.z}/${e.x}/${e.y} holds ${pack.chunks.size} tiles, the index says ${e.tiles}"
            val mine = HashMap<String, String>()
            for (c in pack.chunks) {
                if (c.z !in e.firstLevel..e.lastLevel || c.x shr (c.z - e.z) != e.x || c.y shr (c.z - e.z) != e.y) problems += "tile ${c.z}/${c.x}/${c.y} is not of pack ${e.z}/${e.x}/${e.y}'s block and levels"
                try {
                    val r = pack.decode(c).raster
                    count++
                    low = minOf(low, r.step * r.base)
                    high = maxOf(high, r.step * (r.base + r.max))
                    mine["${c.z} ${c.x} ${c.y}"] = "${r.side} ${r.apron} ${"%016x".format(r.step.toRawBits())} ${r.base} ${r.max} ${r.flags} ${hash(r)}"
                } catch (x: RuntimeException) {
                    problems += "tile ${c.z}/${c.x}/${c.y}: ${x.message}"
                }
            }
            if (core != null) {
                val part = File(terrain.dir, terrain.index.parts[e.part].name)
                val p = ProcessBuilder(core.path, "check", part.path, "${e.offset}", "${e.length}").redirectErrorStream(true).start()
                val lines = p.inputStream.bufferedReader().readLines()
                if (p.waitFor() != 0) {
                    problems += "pack ${e.z}/${e.x}/${e.y}: the core cannot read it (${lines.lastOrNull() ?: "no word"})"
                    continue
                }
                val theirs = lines.filter { it.startsWith("heights ") }.associate { l -> l.split(' ').let { "${it[1]} ${it[2]} ${it[3]}" to it.drop(4).joinToString(" ") } }
                if (theirs.keys != mine.keys) problems += "pack ${e.z}/${e.x}/${e.y}: the core read ${theirs.size} rasters, the packer ${mine.size}"
                for ((k, v) in mine) if (theirs[k] != null && theirs[k] != v) problems += "tile ${k.replace(' ', '/')}: the core reads ${theirs[k]}, the packer $v"
            }
        }
        log("heights-check: $count tiles decoded${if (core != null) ", each by the core's host build too, hash for hash" else ""}; heights from ${"%.2f".format(low)} to ${"%.2f".format(high)} m; ${problems.size} problems")
        return problems
    }

    /**
     * Every tile's apron and edge samples against the neighbour that owns them: equal to the bit where the two share
     * their step, within half the coarser step where a latitude band's step changes between them.
     */
    fun aprons(terrain: Terrain, log: (String) -> Unit): List<String> {
        val problems = ArrayList<String>()
        var compared = 0L
        var exact = 0L
        var pairs = 0
        for (p in terrain.places.values.sortedWith(compareBy({ it.entry.x }, { it.entry.y }, { -it.chunk.z }, { it.chunk.x }, { it.chunk.y }))) {
            val z = p.chunk.z
            val x = p.chunk.x
            val y = p.chunk.y
            val t = terrain.raster(z, x, y) ?: continue
            val per = t.side - 1
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = Math.floorMod(x + dx, 1 shl z)
                val ny = y + dy
                if (ny < 0 || ny >= (1 shl z)) continue
                val n = terrain.raster(z, nx, ny) ?: continue
                if (n.side != t.side) {
                    problems += "tiles $z/$x/$y and $z/$nx/$ny have ${t.side} and ${n.side} samples a side"
                    continue
                }
                pairs++
                var bad = 0
                // the neighbour's own samples (its edges among them) that lie in this tile's window
                for (r in -t.apron until t.side + t.apron) {
                    val nr = r - dy * per
                    if (nr < 0 || nr > per) continue
                    for (c in -t.apron until t.side + t.apron) {
                        val nc = c - dx * per
                        if (nc < 0 || nc > per) continue
                        compared++
                        if (t.step == n.step) {
                            if (t.steps(r, c) == n.steps(nr, nc)) exact++ else bad++
                        } else if (Math.abs(t.metres(r, c) - n.metres(nr, nc)) > maxOf(t.step, n.step) / 2 + 1e-9) {
                            bad++
                        }
                    }
                }
                if (bad > 0) problems += "tile $z/$x/$y: $bad samples differ from its neighbour $z/$nx/$ny's"
            }
        }
        log("heights-check: $compared apron and edge samples of $pairs pairs of neighbours compared, $exact equal to the bit, ${compared - exact} across a change of step and within half of it; ${problems.size} problems")
        return problems
    }

    /**
     * [count] samples over the boards' views, each read afresh: the node's surroundings cut from the source's host by
     * GDAL itself (a sheet's overview by ranges) or asked of its coverage service, and warped onto the node as the
     * packer warps a window. A node its source has no sample at (water, a building: the pack's is filled) is passed
     * over for the next.
     */
    fun points(terrain: Terrain, registry: TerrainSources, region: TerrainSources.Region, repo: File, count: Int, gdal: Gdal, fetcher: Fetcher, work: File, log: (String) -> Unit): List<String> {
        work.mkdirs()
        val source = region.sources.map(registry::source).first { it.area.isNotEmpty() }
        val views = region.checkViews.ifEmpty { region.levels.flatMap { it.views } }
        if (views.isEmpty()) return listOf("the registry names no views to check ${region.id} at")
        val level = terrain.places.values.maxOf { it.chunk.z }
        val sheets = if (source.format == TerrainSources.Format.COG_SHEETS) Sources.sheets(source, fetcher.get(source.id, source.index!!.url)) else emptyList()
        val problems = ArrayList<String>()
        var seed = 0x2545F4914F6CDD1DL
        fun next(): Double {
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 7); seed = seed xor (seed shl 17)
            return (seed ushr 11) / (1L shl 53).toDouble()
        }
        var done = 0
        var worst = 0.0
        var sum = 0.0
        var tried = 0
        val lines = ArrayList<String>()
        for ((v, view) in views.withIndex()) {
            val want = count / views.size + (if (v < count % views.size) 1 else 0)
            val box = HeightsBuild.viewBox(repo, view, 0.0)
            var got = 0
            var tries = 0
            while (got < want && tries < want * 12) {
                tries++
                tried++
                val lon = box[0] + (box[2] - box[0]) * next()
                val lat = box[1] + (box[3] - box[1]) * next()
                // the tile and the node nearest the point
                val tile = Lattice(level, 2)
                val tx = Math.floor(tile.x(lon)).toInt()
                val ty = Math.floor(tile.y(lat)).toInt()
                val raster = terrain.raster(level, tx, ty) ?: continue
                val lattice = Lattice(level, raster.side)
                val nx = Math.rint(lattice.x(lon)).toLong()
                val ny = Math.rint(lattice.y(lat)).toLong()
                val c = (nx - tx.toLong() * lattice.per).toInt()
                val r = (ny - ty.toLong() * lattice.per).toInt()
                if (c !in 0..lattice.per || r !in 0..lattice.per) continue
                // the node's surroundings in the source's own grid: the window's nodes and the cells under their squares
                val window = Window(lattice, nx - 2, ny - 2, 5, 5)
                val b = window.bounds()
                val at = gdal.transformBox(b, "EPSG:3857", source.crs, 2)
                val margin = 4 * source.read + lattice.step
                val cut = doubleArrayOf(at[0] - margin, at[1] - margin, at[2] + margin, at[3] + margin)
                val file = File(work, "point-$tried.tif")
                try {
                    when (source.format) {
                        TerrainSources.Format.COG_SHEETS -> {
                            // a window wholly inside one sheet: the next point where it lies across two
                            val sheet = sheets.firstOrNull { s -> cut[0] >= s.box[0] && cut[1] >= s.box[1] && cut[2] <= s.box[2] && cut[3] <= s.box[3] } ?: continue
                            gdal.cut("/vsicurl/${sheet.url}", source.overview, cut, file)
                        }
                        TerrainSources.Format.WCS -> {
                            val w = source.wcs!!
                            val g = source.read
                            val url = source.hosts[0] + "?service=WCS&version=2.0.1&request=GetCoverage&coverageId=${w.coverage}&format=image/tiff" + (w.extra?.let { "&$it" } ?: "") +
                                "&subset=${w.axes[0]}(${Math.floor(cut[0] / g) * g},${Math.ceil(cut[2] / g) * g})&subset=${w.axes[1]}(${Math.floor(cut[1] / g) * g},${Math.ceil(cut[3] / g) * g})"
                            file.writeBytes(fetcher.get("${source.id} (check)", url))
                        }
                        TerrainSources.Format.COG_TILES -> return listOf("${source.id} is no national source to check points against")
                    }
                    val fresh = gdal.warp(file, window, File(work, "point-$tried.bil"))?.get(2 * 5 + 2) ?: Float.NaN
                    if (fresh.isNaN()) continue
                    val pack = raster.metres(r, c)
                    val error = Math.abs(pack - fresh)
                    got++
                    done++
                    sum += error
                    worst = maxOf(worst, error)
                    lines += "point $done $view ${"%.6f".format(lat)} ${"%.6f".format(lon)}: the pack ${"%.3f".format(pack)} m, ${source.id} ${"%.3f".format(fresh)} m, apart ${"%.3f".format(error)} m of a step of ${raster.step} m"
                    if (error > raster.step) problems += "$view ${"%.6f".format(lat)}, ${"%.6f".format(lon)}: the pack's ${"%.3f".format(pack)} m is ${"%.3f".format(error)} m from ${source.id}'s ${"%.3f".format(fresh)} m, more than its step of ${raster.step} m"
                } finally {
                    file.delete()
                }
            }
            if (got < want) problems += "$view: only $got of $want points had a sample of ${source.id} in $tries tries"
        }
        for (l in lines) log(l)
        log("heights-check: $done points over ${views.joinToString()} read afresh from ${source.id} at L$level: mean ${"%.4f".format(if (done > 0) sum / done else 0.0)} m apart, worst ${"%.4f".format(worst)} m; ${problems.size} problems")
        return problems
    }
}
