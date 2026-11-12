package app.atlas.packer.heights

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.packer.UsageException
import app.atlas.packer.world.WorldPack
import app.atlas.rendertiles.codec.Format3Writer
import app.atlas.rendertiles.codec.HeightsCodec
import app.atlas.rendertiles.model.Grid
import app.atlas.rendertiles.model.HeightRaster
import app.atlas.rendertiles.model.Heights
import app.atlas.rendertiles.model.Profile
import app.atlas.rendertiles.model.Reach
import app.atlas.rendertiles.model.RenderHead
import app.atlas.rendertiles.model.StyleTables
import app.atlas.tiles.WorldIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TreeMap
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * `packer heights`: a region's terrain (ENGINE.md part 6, step 1): the height rasters of its tiles from the registry's
 * sources ([TerrainSources]), in packs of an L[BLOCK] tile each, end to end in parts under GitHub's size limit, with an
 * index in the world's own format ([WorldIndex]) that names every pack's block, levels, range and SHA-256. The phone's
 * store holds each as a `terrain_country` pack and finds a tile's raster by level, column and row, of its own level or
 * the nearest coarser one.
 *
 * A level's samples lie on one lattice ([Lattice]). The build cuts it into windows of [CHUNK] tiles a side, computes
 * each window's nodes once from its sources ([Composite]), each grown by as far as a fill and a feather reach so its
 * nodes do not depend on where the window's edges fall, and then writes the tiles from the nodes of the windows that
 * own them: a tile's edge and apron samples are its neighbours' own, to the bit.
 */
object HeightsBuild {
    const val USAGE = """packer heights <region> <out-dir> [--registry FILE] [--regions FILE] [--repo DIR] [--work DIR] [--gdal DIR]
                 [--threads N] [--levels 12,13] [--box W,S,E,N]
      A region's terrain packs (ENGINE.md part 6): the height rasters the registry (--registry, default
      app/android/packs/terrain-sources.json under --repo) builds for <region> of packs/regions.json (--regions), read from
      their hosts into --work (default <out-dir>/work, deleted when done) and warped by GDAL's tools (--gdal: their folder;
      else the PATH's). Writes <region>-terrain.000, …, <region>-terrain.idx and <region>-terrain.stats.json into <out-dir>.
      --levels builds those levels alone, --box that box alone (a trial)."""

    /** The packer's derivation of heights from their sources: bumped when a tile's samples would come out otherwise. */
    const val VERSION = "h1"

    /** A pack holds the rasters of one tile of this level, as a world pack holds an L6 tile's L9 to L11. */
    const val BLOCK = 9

    /** Tiles a window side. */
    const val CHUNK = 4

    private val VALUED = setOf("--registry", "--regions", "--repo", "--work", "--gdal", "--threads", "--levels", "--box")

    fun run(args: List<String>, out: (String) -> Unit): Int {
        if (args.firstOrNull() != "heights") throw UsageException("usage: $USAGE")
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
        if (positional.size != 2) throw UsageException("heights takes the region and the output folder; usage: $USAGE")
        val repo = File(options["--repo"] ?: ".").absoluteFile
        val registry = TerrainSources.read(options["--registry"]?.let(::File) ?: File(repo, "app/android/packs/terrain-sources.json"))
        val regions = options["--regions"]?.let(::File) ?: File(repo, "app/android/packs/regions.json")
        val outDir = File(positional[1])
        val work = options["--work"]?.let(::File) ?: File(outDir, "work")
        val threads = options["--threads"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..64 } ?: throw UsageException("bad --threads '$it'") } ?: Runtime.getRuntime().availableProcessors()
        val levels = options["--levels"]?.split(',')?.map { it.trim().toIntOrNull() ?: throw UsageException("bad --levels '${options["--levels"]}'") }?.toSet()
        val box = options["--box"]?.let { app.atlas.packer.parseBox(it) }?.let { doubleArrayOf(it.west, it.south, it.east, it.north) }
        val region = registry.region(positional[0])
        val plan = plan(registry, region, regionBox(regions, region.id), repo, levels, box)
        val gdal = Gdal(options["--gdal"]?.let(::File))
        val fetcher = Fetcher(Fetcher.USER_AGENT, out)
        val t0 = System.nanoTime()
        val sources = read(registry, region, plan, File(work, "sources"), gdal, fetcher, out)
        val built = build(region.id, plan, sources, outDir, File(work, "build"), threads, out)
        for (line in fetcher.report()) out(line)
        File(outDir, "${region.id}-terrain.stats.json").writeText(
            Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), stats(region.id, built, fetcher, sources, (System.nanoTime() - t0) / 1e9)) + "\n",
        )
        if (options["--work"] == null) work.deleteRecursively()
        return 0
    }

    /** A level and the boxes (west, south, east, north, degrees) its tiles are built over. */
    class Level(val level: Int, val boxes: List<DoubleArray>)

    /** What a build makes: its levels, coarsest first. */
    class Plan(val levels: List<Level>)

    /** The box (west, south, east, north) of region [id] in packs/regions.json. */
    fun regionBox(regions: File, id: String): DoubleArray {
        val r = Json.parseToJsonElement(regions.readText()).jsonObject.getValue("regions").jsonArray.map { it.jsonObject }
            .firstOrNull { it.getValue("id").jsonPrimitive.content == id } ?: throw UsageException("no region '$id' in $regions")
        return r.getValue("bbox").jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
    }

    /** The box of the renders' region [view] (data/regions/<view>/region.json: south, west, north, east) grown by [km], as west, south, east, north. */
    fun viewBox(repo: File, view: String, km: Double): DoubleArray {
        val f = File(repo, "data/regions/$view/region.json")
        if (!f.isFile) throw UsageException("no $f: the registry names the renders' region '$view'")
        val b = Json.parseToJsonElement(f.readText()).jsonObject.getValue("bbox").jsonArray.map { it.jsonPrimitive.double }
        val dLat = km / 110.574
        val dLon = km / (111.320 * Math.cos(Math.toRadians((b[0] + b[2]) / 2)))
        return doubleArrayOf(b[1] - dLon, b[0] - dLat, b[3] + dLon, b[2] + dLat)
    }

    fun plan(registry: TerrainSources, region: TerrainSources.Region, regionBox: DoubleArray, repo: File, only: Set<Int>?, box: DoubleArray?): Plan {
        val byLevel = TreeMap<Int, MutableList<DoubleArray>>()
        for (l in region.levels) {
            val boxes = when {
                box != null -> listOf(box)
                l.whole -> listOf(regionBox)
                else -> l.views.map { viewBox(repo, it, l.growKm) }
            }.mapNotNull { b ->
                // a region's terrain stays inside its box
                val c = doubleArrayOf(maxOf(b[0], regionBox[0]), maxOf(b[1], regionBox[1]), minOf(b[2], regionBox[2]), minOf(b[3], regionBox[3]))
                c.takeIf { it[0] < it[2] && it[1] < it[3] }
            }
            // each box grown to the edges of the level's tiles it meets: those are the tiles built, and what the sources are
            // read over, so a tile at a box's edge holds its source's samples to its own edge
            for (z in l.levels) if (only == null || z in only) byLevel.getOrPut(z) { ArrayList() } += boxes.map { b -> snapped(b, z) }
        }
        if (byLevel.isEmpty() || byLevel.values.all { it.isEmpty() }) throw UsageException("nothing to build for ${region.id}")
        require(registry.sources.isNotEmpty())
        return Plan(byLevel.map { (z, b) -> Level(z, b) })
    }

    /** [box] (west, south, east, north) grown to the edges of the tiles of [level] it meets. */
    fun snapped(box: DoubleArray, level: Int): DoubleArray {
        val t = Lattice(level, 2)
        val n = (1L shl level).toDouble()
        return doubleArrayOf(
            t.lon(Math.floor(t.x(box[0])).coerceIn(0.0, n)), t.lat(Math.ceil(t.y(box[1])).coerceIn(0.0, n)),
            t.lon(Math.ceil(t.x(box[2])).coerceIn(0.0, n)), t.lat(Math.floor(t.y(box[3])).coerceIn(0.0, n)),
        )
    }

    /** A source as a build reads it: its registry entry, its heights over a window, and for a national model the boxes (degrees) its rasters lie in. */
    class Read(val source: TerrainSources.Source, val node: NodeSource, val coverage: List<DoubleArray>?) {
        /** A national model, which a tile must hold a sample of to be built; a world model only fills. */
        val national: Boolean get() = source.area.isNotEmpty()
    }

    /** [region]'s sources read for [plan]: the national models fetched over its boxes into [dir] and laid as mosaics, the world models cell by cell as windows need them. */
    fun read(registry: TerrainSources, region: TerrainSources.Region, plan: Plan, dir: File, gdal: Gdal, fetcher: Fetcher, log: (String) -> Unit): List<Read> {
        val boxes = plan.levels.flatMap { it.boxes }.distinctBy { it.toList() }
        return region.sources.map { id ->
            val s = registry.source(id)
            if (s.format == TerrainSources.Format.COG_TILES) return@map Read(s, CogTiles(s, File(dir, s.id), fetcher), null)
            // the windows reach past the build's boxes by a fill and a feather
            val pad = (s.fill + s.feather + 500.0) / 111_000.0
            val wanted = boxes.map { b ->
                val wide = pad / Math.cos(Math.toRadians(maxOf(Math.abs(b[1]), Math.abs(b[3])).coerceAtMost(85.0)))
                gdal.transformBox(doubleArrayOf(b[0] - wide, b[1] - pad, b[2] + wide, b[3] + pad), "OGC:CRS84", s.crs)
            }
            val fetched = Sources.fetch(s, wanted, File(dir, s.id), fetcher, log)
            if (fetched.files.isEmpty()) throw IllegalStateException("${s.id} has nothing over ${region.id}'s boxes")
            log("${s.id}: ${fetched.files.size} rasters, ${fetched.files.sumOf { it.length() }} bytes on disk, read in ${"%.0f".format(fetched.seconds)} s")
            val vrt = File(dir, "${s.id}.vrt")
            gdal.buildVrt(fetched.files, vrt, s.crs)
            // each raster's box in degrees, from its corners: where a window can hold a sample of the source
            val corners = fetched.boxes.flatMap { b -> listOf(doubleArrayOf(b[0], b[1]), doubleArrayOf(b[0], b[3]), doubleArrayOf(b[2], b[1]), doubleArrayOf(b[2], b[3])) }
            val degrees = gdal.transform(corners, s.crs, "OGC:CRS84")
            val coverage = fetched.boxes.indices.map { i ->
                val c = degrees.subList(i * 4, i * 4 + 4)
                doubleArrayOf(c.minOf { it[0] } - 0.01, c.minOf { it[1] } - 0.01, c.maxOf { it[0] } + 0.01, c.maxOf { it[1] } + 0.01)
            }
            File(dir, "scratch-${s.id}").mkdirs()
            Read(s, GdalSource(gdal, vrt, File(dir, "scratch-${s.id}")), coverage)
        }
    }

    /** One written tile: its key, where its bytes wait, its top, and what each source gave its own samples. */
    class TileInfo(val level: Int, val x: Int, val y: Int, val file: File, val topCm: Int, val km2: Double, val own: LongArray, val low: Double, val high: Double) {
        @Volatile var stored: Int = 0
    }

    /** What [build] made. */
    class Built(val region: String, val index: WorldIndex, val tiles: List<TileInfo>, val sources: List<Read>, val skipped: List<String>)

    private class Chunk(val cx: Long, val cy: Long, val file: File)

    /** A window's stored nodes: its own and [Heights.APRON] beyond, the far edge's too. */
    private class Stored(val x0: Long, val y0: Long, val width: Int, val heights: FloatArray, val source: ByteArray) {
        fun has(x: Long, y: Long): Boolean = x - x0 in 0 until width && y - y0 in 0 until width
        fun at(x: Long, y: Long): Int = ((y - y0) * width + (x - x0)).toInt()
    }

    /**
     * [plan]'s tiles of region [region] from [sources] (in the order of their priority) into [outDir]: the parts, the
     * index and what was built. [work] holds the windows and the tiles meanwhile.
     */
    fun build(region: String, plan: Plan, sources: List<Read>, outDir: File, work: File, threads: Int, log: (String) -> Unit): Built {
        require(sources.isNotEmpty() && sources.size < Composite.FILLED)
        outDir.mkdirs()
        work.deleteRecursively()
        work.mkdirs()
        val national = sources.filter { it.national }
        require(national.isNotEmpty()) { "$region has no national source: a world model alone is the world's terrain, not a country's" }
        val finest = national.minOf { it.source.read }
        val tiles = java.util.Collections.synchronizedList(ArrayList<TileInfo>())
        val skipped = ArrayList<String>()
        val pool = Executors.newFixedThreadPool(threads)
        try {
            for ((n, l) in plan.levels.withIndex()) {
                if (l.boxes.isEmpty()) continue
                val midLat = l.boxes.map { (it[1] + it[3]) / 2 }.average()
                val row = Lattice(l.level, 2).y(midLat).toInt()
                val side = Heights.sideFor(l.level, row, finest)
                // a raster coarser than the format's finest only repeats the level above it: the tile reads that one
                if (side == null || (side < Heights.SIDES[0] && n > 0)) {
                    skipped += "L${l.level}: its sources' $finest m are no finer than L${l.level - 1}'s samples; its tiles read the coarser rasters"
                    log(skipped.last())
                    continue
                }
                level(l, Lattice(l.level, side), sources, File(work, "L${l.level}"), pool, tiles, log)
            }
        } finally {
            pool.shutdownNow()
        }
        if (tiles.isEmpty()) throw IllegalStateException("$region: no tile holds a sample of ${national.joinToString { it.source.id }}")
        val index = packs(region, tiles, sources, outDir, File(work, "packs"), log)
        work.deleteRecursively()
        return Built(region, index, tiles.sortedWith(compareBy({ it.level }, { it.x }, { it.y })), sources, skipped)
    }

    private fun level(l: Level, lattice: Lattice, sources: List<Read>, dir: File, pool: java.util.concurrent.ExecutorService, tiles: MutableList<TileInfo>, log: (String) -> Unit) {
        dir.mkdirs()
        val per = lattice.per
        val span = CHUNK * per
        val n = 1L shl l.level
        // the tiles each box meets, and the windows that hold them
        // a box's east and south edges are its last tiles' own, not the next tiles' (the plan's boxes end on tiles' edges)
        fun tileRange(b: DoubleArray): LongArray = longArrayOf(
            Math.floor(lattice.x(b[0]) / per + 1e-9).toLong().coerceIn(0, n - 1), Math.floor(lattice.y(b[3]) / per + 1e-9).toLong().coerceIn(0, n - 1),
            (Math.ceil(lattice.x(b[2]) / per - 1e-9).toLong() - 1).coerceIn(0, n - 1), (Math.ceil(lattice.y(b[1]) / per - 1e-9).toLong() - 1).coerceIn(0, n - 1),
        )
        val ranges = l.boxes.map(::tileRange)
        fun wanted(tx: Long, ty: Long): Boolean = ranges.any { tx in it[0]..it[2] && ty in it[1]..it[3] }
        val keys = LinkedHashSet<Pair<Long, Long>>()
        for (r in ranges) for (cy in r[1] / CHUNK..r[3] / CHUNK) for (cx in r[0] / CHUNK..r[2] / CHUNK) keys += cx to cy
        val apron = Heights.APRON
        val chunks = ConcurrentHashMap<Pair<Long, Long>, Chunk>()
        val t0 = System.nanoTime()
        pool.invokeAll(keys.map { (cx, cy) ->
            Callable {
                val own = Window(lattice, cx * span, cy * span, span, span)
                val spacing = lattice.spacing(own.y0 + span / 2.0)
                val reach = sources.maxOf { Math.ceil((it.source.fill + it.source.feather) / spacing).toInt() }
                val grown = own.grown(reach + apron + 2)
                val box = doubleArrayOf(lattice.lon(grown.x0.toDouble()), lattice.lat((grown.y0 + grown.height).toDouble()), lattice.lon((grown.x0 + grown.width).toDouble()), lattice.lat(grown.y0.toDouble()))
                // the national models first: a window none of them reaches holds no tile to build
                val rasters = arrayOfNulls<FloatArray>(sources.size)
                var any = false
                for ((k, s) in sources.withIndex()) {
                    if (!s.national) continue
                    if (s.coverage != null && s.coverage.none { Sources.meets(it, box) }) continue
                    rasters[k] = s.node.heights(grown)
                    if (rasters[k] != null) any = true
                }
                if (!any) return@Callable
                val holes = (0 until grown.size).any { i -> sources.indices.none { k -> rasters[k]?.get(i)?.isNaN() == false } }
                if (holes) for ((k, s) in sources.withIndex()) if (!s.national) rasters[k] = s.node.heights(grown)
                var merged = Composite.merge(null, null, null, grown.width, grown.height, 0, 0, 0)
                for (k in sources.indices.reversed()) {
                    val s = sources[k].source
                    merged = Composite.merge(
                        rasters[k], merged.heights, merged.source, grown.width, grown.height,
                        Math.ceil(s.fill / spacing).toInt(), Math.ceil(s.feather / spacing).toInt(), k,
                    )
                }
                // kept: the window's own nodes and the apron's beyond them
                val w = span + 2 * apron + 1
                val off = reach + 2
                val out = ByteBuffer.allocate(w * w * 5).order(ByteOrder.LITTLE_ENDIAN)
                for (r in 0 until w) for (c in 0 until w) out.putFloat(merged.heights[(r + off) * grown.width + c + off])
                for (r in 0 until w) out.put(merged.source, (r + off) * grown.width + off, w)
                val file = File(dir, "w_${cx}_$cy.bin")
                file.writeBytes(out.array())
                chunks[cx to cy] = Chunk(cx, cy, file)
            }
        }).forEach { it.get() }
        log("L${l.level}: ${chunks.size} of ${keys.size} windows hold samples (${lattice.side} samples a tile side), ${"%.0f".format((System.nanoTime() - t0) / 1e9)} s")

        fun load(c: Chunk): Stored {
            val w = span + 2 * apron + 1
            val b = ByteBuffer.wrap(c.file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val h = FloatArray(w * w)
            b.asFloatBuffer().get(h)
            val s = ByteArray(w * w)
            b.position(w * w * 4)
            b.get(s)
            return Stored(c.cx * span - apron, c.cy * span - apron, w, h, s)
        }
        val nationals = BooleanArray(sources.size) { sources[it].national }
        val t1 = System.nanoTime()
        pool.invokeAll(chunks.values.map { chunk ->
            Callable {
                val mine = load(chunk)
                val around = HashMap<Pair<Long, Long>, Stored>()
                for (dy in -1L..1L) for (dx in -1L..1L) {
                    if (dx == 0L && dy == 0L) continue
                    chunks[(chunk.cx + dx) to (chunk.cy + dy)]?.let { around[it.cx to it.cy] = load(it) }
                }
                for (ty in chunk.cy * CHUNK until (chunk.cy + 1) * CHUNK) for (tx in chunk.cx * CHUNK until (chunk.cx + 1) * CHUNK) {
                    if (tx >= n || ty >= n || !wanted(tx, ty)) continue
                    val width = lattice.side + 2 * apron
                    val heights = FloatArray(width * width)
                    val shares = LongArray(sources.size)
                    val own = LongArray(sources.size)
                    var flags = 0
                    var held = false
                    for (r in 0 until width) for (c in 0 until width) {
                        val x = tx * per - apron + c
                        val y = ty * per - apron + r
                        // the node from the window that owns it; from this one where no window does
                        val owner = Math.floorDiv(x, span.toLong()) to Math.floorDiv(y, span.toLong())
                        val from = if (owner.first == chunk.cx && owner.second == chunk.cy) mine else around[owner] ?: mine
                        val i = from.at(x, y)
                        heights[r * width + c] = from.heights[i]
                        val src = from.source[i]
                        val k = Composite.index(src)
                        if (k < 0) flags = flags or Heights.FLAG_NODATA else shares[k]++
                        if (k >= 0 && (src.toInt() and Composite.FILLED) != 0) flags = flags or Heights.FLAG_FILLED
                        if (r in apron until width - apron && c in apron until width - apron && k >= 0) {
                            own[k]++
                            if (nationals[k]) held = true
                        }
                    }
                    if (!held) continue
                    val spacing = lattice.spacing(ty * per + per / 2.0)
                    val used = sources.indices.filter { shares[it] > 0 }
                    val raster = HeightRaster.of(heights, lattice.side, apron, Heights.step(spacing), flags, used.map { sources[it].source.id }, used.map { shares[it] })
                    val file = File(dir, "t_${tx}_$ty.tile")
                    file.writeBytes(HeightsCodec.encodeTile(l.level, tx.toInt(), ty.toInt(), raster))
                    val km = per * spacing / 1000.0
                    tiles += TileInfo(l.level, tx.toInt(), ty.toInt(), file, HeightsCodec.topCm(raster), km * km, own, raster.step * raster.base, raster.step * (raster.base + raster.max))
                }
            }
        }).forEach { it.get() }
        for (c in chunks.values) c.file.delete()
        log("L${l.level}: ${tiles.count { it.level == l.level }} tiles, ${"%.0f".format((System.nanoTime() - t1) / 1e9)} s")
    }

    /** The tiles in packs of an L[BLOCK] tile each, end to end in [outDir]'s parts, and their index. */
    private fun packs(region: String, tiles: List<TileInfo>, sources: List<Read>, outDir: File, scratch: File, log: (String) -> Unit): WorldIndex {
        scratch.mkdirs()
        val blocks = TreeMap<Pair<Int, Int>, MutableList<TileInfo>>(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))
        for (t in tiles) blocks.getOrPut((t.x shr (t.level - BLOCK)) to (t.y shr (t.level - BLOCK))) { ArrayList() } += t
        val credits = sources.map { "${it.source.name}: ${it.source.credit}" }
        val inputs = WorldPack.sha256(listOf(VERSION) + sources.map { "${it.source.id} ${it.source.date} ${it.source.read}" })
        val date = sources.filter { it.national }.maxOf { LocalDate.parse(it.source.date) }.atStartOfDay().toInstant(ZoneOffset.UTC)
        val written = ArrayList<Pair<File, (Int, Long, Long, ByteArray) -> WorldIndex.Entry>>()
        for ((block, list) in blocks) {
            val levels = list.map { it.level }.distinct().sortedDescending().toIntArray()
            val encoded = list.map { t ->
                val box = Grid.tileBox(t.level, t.x, t.y)
                Format3Writer.Encoded(t.level, t.x, t.y, t.file.readBytes(), 1, Reach(box[0], box[1], box[2], box[3], t.topCm), mapOf(app.atlas.rendertiles.model.Layer.HEIGHTS.label to 1))
            }
            val file = File(scratch, "${BLOCK}_${block.first}_${block.second}.atlas")
            val w = write(encoded, "$region-terrain/$BLOCK/${block.first}/${block.second}", block.first, block.second, levels, date, credits, inputs, file)
            val byKey = list.associateBy { Triple(it.level, it.x, it.y) }
            for (c in w.chunks) byKey.getValue(Triple(c.z, c.x, c.y)).stored = c.stored
            for (t in list) t.file.delete()
            val box = Grid.tileBox(BLOCK, block.first, block.second)
            written += file to { part: Int, offset: Long, length: Long, sha: ByteArray ->
                WorldIndex.Entry(BLOCK, block.first, block.second, levels.min(), levels.max(), part, offset, length, list.size, box, sha)
            }
        }
        val parts = ArrayList<WorldIndex.Part>()
        val entries = WorldPack.parts(outDir, "$region-terrain", written, 0, parts)
        require(parts.size <= 256) { "${parts.size} parts, more than an index entry's part (u8) can name" }
        val index = WorldIndex(date, parts, entries)
        File(outDir, "$region-terrain.idx").outputStream().use { index.write(it) }
        scratch.deleteRecursively()
        log("$region: ${tiles.size} tiles in ${entries.size} packs of ${parts.size} parts, ${parts.sumOf { it.bytes }} bytes")
        return index
    }

    /**
     * A terrain pack of block ([x], [y]) at L[BLOCK]: format 3 with the terrain's profile, the frame at the block's
     * centre as a world pack's, a style without tables, and the sources' credits in its manifest.
     */
    fun write(tiles: List<Format3Writer.Encoded>, id: String, x: Int, y: Int, levels: IntArray, date: Instant, credits: List<String>, inputs: String, file: File): Format3Writer.Written {
        val f = WorldPack.frame(BLOCK, x, y)
        val head = RenderHead(Profile.TERRAIN, id, levels, f[0], f[1], f[2], f[3], "terrain", VERSION, inputs, emptyList(), listOf(RenderHead.Band(0, null)))
        val b = Grid.tileBox(BLOCK, x, y)
        val info = Format3Writer.Info(
            id, id, BBox(Grid.deg(b[1]), Grid.deg(b[0]), Grid.deg(b[3]), Grid.deg(b[2])), LatLon(f[0], f[1]), null, null, date.toString(),
            JsonObject(mapOf("block" to JsonPrimitive("$BLOCK/$x/$y"))), JsonObject(emptyMap()), credits,
        )
        return Format3Writer.writeEncoded(tiles, head, StyleTables(emptyMap()), info, file)
    }

    /** The build's numbers: each level's tiles and bytes, each source's megabytes per 1,000 km² and what was read of it. */
    fun stats(region: String, built: Built, fetcher: Fetcher?, sources: List<Read>, seconds: Double): JsonObject = buildJsonObject {
        put("region", region)
        put("version", VERSION)
        put("seconds", Math.round(seconds))
        put("tiles", built.tiles.size)
        put("bytes", built.index.parts.sumOf { it.bytes })
        put("packs", built.index.entries.size)
        put("lowest_m", built.tiles.minOf { it.low })
        put("highest_m", built.tiles.maxOf { it.high })
        putJsonObject("levels") {
            for ((z, list) in built.tiles.groupBy { it.level }.toSortedMap()) putJsonObject("$z") {
                put("tiles", list.size)
                put("stored", list.sumOf { it.stored.toLong() })
                put("km2", list.sumOf { it.km2 })
                put("mb_per_1000_km2", list.sumOf { it.stored.toLong() } / 1e6 / list.sumOf { it.km2 } * 1000)
            }
        }
        putJsonObject("sources") {
            for ((k, s) in sources.withIndex()) {
                // a tile's bytes and ground go to its sources by what each gave its own samples; a source's rate is
                // its levels' rates added up, so a level built over less ground counts as it would over all of it
                var bytes = 0.0
                var rate = 0.0
                var km2 = 0.0
                for ((_, list) in built.tiles.groupBy { it.level }) {
                    var b = 0.0
                    var a = 0.0
                    for (t in list) {
                        val all = t.own.sum().toDouble()
                        if (all == 0.0) continue
                        b += t.stored * t.own[k] / all
                        a += t.km2 * t.own[k] / all
                    }
                    bytes += b
                    km2 = maxOf(km2, a)
                    if (a > 0) rate += b / 1e6 / a * 1000
                }
                putJsonObject(s.source.id) {
                    put("bytes", Math.round(bytes))
                    put("km2", km2)
                    put("mb_per_1000_km2", rate)
                    fetcher?.stats?.get(s.source.id)?.let { f ->
                        put("fetch_requests", f.requests.get())
                        put("fetch_bytes", f.bytes.get())
                        put("fetch_seconds", f.nanos.get() / 1e9)
                    }
                }
            }
        }
        if (built.skipped.isNotEmpty()) put("skipped", built.skipped.joinToString("; "))
    }
}
