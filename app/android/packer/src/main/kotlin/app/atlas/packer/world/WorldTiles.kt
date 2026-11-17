package app.atlas.packer.world

import app.atlas.packer.UsageException
import app.atlas.packer.pbf.PbfReader
import app.atlas.rendertiles.codec.Format3Writer
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.Grid
import app.atlas.tiles.WorldIndex
import com.onthegomap.planetiler.Planetiler
import com.onthegomap.planetiler.config.Arguments
import com.onthegomap.planetiler.mbtiles.Mbtiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore

/**
 * `packer world`: the world's mid-zoom base (ENGINE.md step 5, L6 to L11) of an OSM extract. Planetiler runs Atlas's
 * own profile ([WorldProfile]) over the extract into a scratch MBTiles; each of its tiles is cut again at its box and
 * cells on the grid ([WorldCut]) over the land of osmdata's split land polygons ([LandBase]), whose outside is the sea,
 * and written as format 3: a fine pack per L6 block (its L9 to L11 tiles) and a coarse pack per L3 block (its L6 to L8
 * tiles, those of the blocks built), end to end in `world-mid.000` and `world-coarse.000`, with their index
 * `world.idx` ([WorldIndex]). Open sea with nothing in it has no tile.
 */
object WorldTiles {
    const val USAGE = """packer world <in.osm.pbf> <out-dir> --land <land-polygons-split-4326.zip> [--blocks X0,Y0,X1,Y1 | --block-list X:Y,...]
                   [--threads N] [--scratch DIR] [--keep-mbtiles] [--parts NAME] [--coarse-tiles FILE] [--source NAME:HASH]
      The world's mid-zoom base (L6 to L11) of an extract: Planetiler (in-process, its heap the packer's) runs Atlas's own
      profile into <scratch>/world.mbtiles, each tile is cut again at its box and its cells over osmdata's land polygons
      (--land, the split ones in EPSG:4326, read from their zip), and written as format 3: a fine pack per L6 block (its
      L9 to L11 tiles) in <out-dir>/world-mid.000 and a coarse pack per L3 block (its L6 to L8 tiles) in world-coarse.000,
      with their index world.idx. --blocks names the L6 blocks (default: those the extract's box meets), --block-list
      names them one by one; --threads (3 unless given) Planetiler's and the cut's; --scratch defaults to
      <out-dir>/scratch, its world.mbtiles deleted after unless --keep-mbtiles, and one already there used again.
      A region of a world built in regions (world.yml; `packer world-assemble` puts them together): --parts names the fine
      packs' parts (<NAME>.000, …), --coarse-tiles writes the coarse tiles to FILE instead of their packs (the index then
      holds the fine packs alone), and --source names the extract the region was cut from and a hash of its bytes, which
      the packs say they were made from instead of the region's own file."""

    /** The fine and coarse packs' block levels and the levels they hold. */
    val FINE = 9..11
    val COARSE = 6..8

    fun run(args: List<String>, out: (String) -> Unit): Int {
        if (args.firstOrNull() != "world") throw UsageException("usage: $USAGE")
        val positional = ArrayList<String>()
        val options = LinkedHashMap<String, String?>()
        var i = 1
        while (i < args.size) {
            val s = args[i]
            when {
                s == "--keep-mbtiles" -> options[s] = null
                s.startsWith("--") -> {
                    if (s !in VALUED) throw UsageException("unknown option $s; usage: $USAGE")
                    if (i + 1 >= args.size) throw UsageException("$s needs a value")
                    options[s] = args[++i]
                }
                else -> positional += s
            }
            i++
        }
        if (positional.size != 2) throw UsageException("world takes the extract and the output folder; usage: $USAGE")
        val pbf = File(positional[0])
        if (!pbf.isFile) throw UsageException("no extract $pbf")
        val outDir = File(positional[1])
        val land = File(options["--land"] ?: throw UsageException("world needs osmdata's land polygons (--land); usage: $USAGE"))
        if (!land.isFile) throw UsageException("no land polygons $land")
        val threads = options["--threads"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..64 } ?: throw UsageException("bad --threads '$it'") } ?: 3
        if ("--blocks" in options && "--block-list" in options) throw UsageException("give --blocks or --block-list, not both")
        val blocks = options["--blocks"]?.let { b ->
            val v = b.split(',').map { it.trim().toIntOrNull() ?: throw UsageException("bad --blocks '$b'") }
            if (v.size != 4 || v[0] > v[2] || v[1] > v[3] || v.any { it !in 0..63 }) throw UsageException("bad --blocks '$b': want X0,Y0,X1,Y1 of L6")
            rectangle(v[0], v[1], v[2], v[3])
        } ?: options["--block-list"]?.let(::parseBlocks)
        val parts = options["--parts"] ?: MID
        if (!PART_NAME.matches(parts)) throw UsageException("bad --parts '$parts': letters, digits, '-' and '_'")
        val source = options["--source"]?.let { s ->
            val at = s.lastIndexOf(':')
            if (at <= 0 || at == s.length - 1) throw UsageException("bad --source '$s': want NAME:HASH")
            Source(s.substring(0, at), s.substring(at + 1))
        }
        val scratch = File(options["--scratch"] ?: File(outDir, "scratch").path)
        build(pbf, land, outDir, scratch, threads, Run(blocks, parts, options["--coarse-tiles"]?.let(::File), source), "--keep-mbtiles" in options, out)
        return 0
    }

    private val VALUED = setOf("--land", "--blocks", "--block-list", "--threads", "--scratch", "--parts", "--coarse-tiles", "--source")

    /** The fine packs' parts' name unless a run names its own, and the coarse packs'. */
    const val MID = "world-mid"
    const val COARSE_PARTS = "world-coarse"

    /** A part's name before its number: what a release asset's name may hold. */
    val PART_NAME = Regex("[A-Za-z0-9_-]{1,200}")

    /**
     * What a run builds beyond its inputs: the L6 blocks ([blocks]; those the extract's box meets when null), the fine
     * packs' parts' name ([parts]), the file the coarse tiles go to instead of their packs ([coarseTiles], a region of a
     * world built in regions, which `packer world-assemble` puts together) and the extract the packs say they were made
     * from ([source]; the input's own file when null).
     */
    class Run(val blocks: List<Pair<Int, Int>>? = null, val parts: String = MID, val coarseTiles: File? = null, val source: Source? = null)

    /** The extract a world's packs were made from: its file's name and a hash of its bytes (in the packs' source hash). */
    class Source(val name: String, val hash: String)

    /** The L6 blocks of columns [x0]..[x1] and rows [y0]..[y1], row by row from the north, each from the west. */
    fun rectangle(x0: Int, y0: Int, x1: Int, y1: Int): List<Pair<Int, Int>> = (y0..y1).flatMap { by -> (x0..x1).map { bx -> bx to by } }

    /** `X:Y,X:Y,…` as L6 blocks, each once. */
    fun parseBlocks(s: String): List<Pair<Int, Int>> {
        val out = s.split(',').map { b ->
            val v = b.trim().split(':').map { it.toIntOrNull() ?: throw UsageException("bad block '$b' in '$s': want X:Y") }
            if (v.size != 2 || v.any { it !in 0..63 }) throw UsageException("bad block '$b' in '$s': want X:Y of L6")
            v[0] to v[1]
        }
        if (out.toSet().size != out.size) throw UsageException("a block twice in '$s'")
        return out
    }

    /** [blocks] as `X:Y,X:Y,…`, as [parseBlocks] reads them. */
    fun blocksText(blocks: List<Pair<Int, Int>>): String = blocks.joinToString(",") { "${it.first}:${it.second}" }

    /** What a build made, for the report and the tests: the tiles written at a coarser tolerance among it ([fitted]). */
    class Built(val index: WorldIndex, val levels: Map<Int, LevelStats>, val worstCover: Double, val ringsOutside: Int, val seconds: Map<String, Double>, val coarser: List<String>)

    /** A tile cut and encoded ([fitted]): its cut, its bytes (null for none) and the tolerance it was simplified at. */
    class Fitted(val cut: WorldCut.Cut, val encoded: Format3Writer.Encoded?, val tolerance: Double)

    /**
     * Tile ([z], [x], [y]) cut from [items] and the land [land] gives at a tolerance, at [WorldProfile.TOLERANCE], and
     * encoded. A tile over the format's 4 MB decompressed ([Format.MAX_TILE_BYTES]) is written at the next tolerance
     * (ENGINE.md step 5's risks): cut again from its items and its land simplified at twice the tolerance, as often as it
     * takes.
     */
    fun fitted(cut: WorldCut, z: Int, x: Int, y: Int, items: List<WorldCut.Item>, land: (Double) -> Geometry?): Fitted {
        var tolerance = WorldProfile.TOLERANCE
        var c = cut.cut(z, x, y, items, land(tolerance))
        var e = c.tile?.let { Format3Writer.encoded(it) }
        while (e != null && e.raw.size > Format.MAX_TILE_BYTES) {
            tolerance *= 2
            val simpler = items.map { WorldCut.Item(it.layer, it.attrs, DouglasPeuckerSimplifier.simplify(it.geometry, tolerance)) }.filter { !it.geometry.isEmpty }
            c = cut.cut(z, x, y, simpler, land(tolerance))
            e = c.tile?.let { Format3Writer.encoded(it) }
        }
        return Fitted(c, e, tolerance)
    }

    /** One level's tiles: how many, their bytes as stored (zstd) and raw, their features. */
    class LevelStats(var tiles: Int = 0, var stored: Long = 0, var raw: Long = 0, var features: Long = 0)

    /** What every pack of a run holds beside its tiles: the data's date, its sources and the inputs' hash. */
    class Facts(val dataTime: String?, val sources: List<String>, val source: String)

    fun build(
        pbf: File,
        landZip: File,
        outDir: File,
        scratch: File,
        threads: Int,
        run: Run,
        keepMbtiles: Boolean,
        log: (String) -> Unit,
    ): Built {
        val seconds = LinkedHashMap<String, Double>()
        fun timed(what: String, start: Long) {
            seconds[what] = (System.nanoTime() - start) / 1e9
        }
        outDir.mkdirs()
        scratch.mkdirs()
        val header = PbfReader(pbf).header
        val blockList = run.blocks ?: header.bbox?.let { box ->
            rectangle(Grid.col(Grid.round(box.west), 6), Grid.row(Grid.round(box.north), 6), Grid.col(Grid.round(box.east), 6), Grid.row(Grid.round(box.south), 6))
        } ?: throw IllegalArgumentException("${pbf.name} gives no box")
        require(blockList.isNotEmpty()) { "no L6 block to build" }
        log("world: ${pbf.name} (${header.replicationTime ?: "no date"}), ${blockList.size} L6 blocks in columns ${blockList.minOf { it.first }}..${blockList.maxOf { it.first }}, rows ${blockList.minOf { it.second }}..${blockList.maxOf { it.second }}")

        // what every pack says it was made from
        val date = header.replicationTime
        val facts = Facts(
            date?.let { DateTimeFormatter.ISO_INSTANT.format(it) },
            listOf("OpenStreetMap (© OpenStreetMap contributors, ODbL 1.0): ${run.source?.name ?: pbf.name}", "osmdata.openstreetmap.de: ${landZip.name}"),
            WorldPack.sha256(listOf("osm ${run.source?.hash ?: WorldPack.sha256(pbf)}", "land ${WorldPack.sha256(landZip)}", "profile ${PROFILE_VERSION}")),
        )

        // Planetiler: the profile's features at L6 to L11
        val mbtiles = File(scratch, "world.mbtiles")
        if (!mbtiles.isFile) {
            val t0 = System.nanoTime()
            val bs = blockList.map { (bx, by) -> Grid.tileBox(6, bx, by) }
            val bounds = Envelope(Grid.deg(bs.minOf { it[1] }), Grid.deg(bs.maxOf { it[3] }), Grid.deg(bs.minOf { it[0] }), Grid.deg(bs.maxOf { it[2] }))
            planetiler(pbf, mbtiles, scratch, threads, bounds, WorldProfile(), WorldProfile.MAX_LEVEL)
            timed("planetiler", t0)
            log("planetiler: ${mbtiles.length() shr 20} MiB of tiles in %.0f s".format(seconds["planetiler"]))
        }

        // the land under the blocks
        val t1 = System.nanoTime()
        val env = blockList.map { (bx, by) -> Grid.tileBox(6, bx, by) }.let { bs ->
            Envelope(Grid.deg(bs.minOf { it[1] }) - 0.1, Grid.deg(bs.maxOf { it[3] }) + 0.1, Grid.deg(bs.minOf { it[0] }) - 0.1, Grid.deg(bs.maxOf { it[2] }) + 0.1)
        }
        val pieces = Shapefile.read(landZip, "land_polygons", WorldGeometry.FLOAT, env, attributes = false).mapNotNull { it.geometry }.map { WorldGeometry.worldOfDegrees(it) }
        val base = LandBase(pieces)
        timed("land", t1)
        log("land: ${pieces.size} pieces of osmdata's split land polygons in %.0f s".format(seconds["land"]))

        // each block's tiles, cut on [threads] threads, and its fine pack written once they are
        val t2 = System.nanoTime()
        val cut = WorldCut()
        val packDir = File(scratch, "packs").apply { mkdirs() }
        val finePacks = ArrayList<Pair<File, (Int, Long, Long, ByteArray) -> WorldIndex.Entry>>()
        val coarse = LinkedHashMap<Pair<Int, Int>, MutableList<Format3Writer.Encoded>>()
        val stats = java.util.TreeMap<Int, LevelStats>()
        var worst = 0.0
        var outside = 0
        val coarser = ArrayList<String>()
        Mbtiles.newReadOnlyDatabase(mbtiles.toPath()).use { db ->
            val pool = Executors.newFixedThreadPool(threads)
            try {
                for ((bx, by) in blockList.sortedWith(compareBy({ it.first }, { it.second }))) {
                    // the land of each tile of the block, down the quadtree, its tile cut on the pool (a few at a time,
                    // so the pieces in flight stay few)
                    val gate = Semaphore(threads * 4)
                    val results = ArrayList<Future<Pair<Triple<Int, Int, Int>, Fitted>>>()
                    base.walk(6, bx, by, WorldProfile.MAX_LEVEL) { z, x, y, p ->
                        gate.acquire()
                        results += pool.submit(Callable {
                            try {
                                val mvt = synchronized(db) { db.getTile(x, y, z) }
                                Triple(z, x, y) to fitted(cut, z, x, y, cut.items(mvt)) { tolerance -> base.land(z, x, y, p, tolerance) }
                            } finally {
                                gate.release()
                            }
                        })
                        true
                    }
                    val fine = ArrayList<Format3Writer.Encoded>()
                    for (f in results) {
                        val (key, done) = f.get()
                        val (z, x, y) = key
                        val c = done.cut
                        worst = maxOf(worst, c.cover.relative)
                        outside += c.cover.ringsOutside
                        val e = done.encoded ?: continue
                        if (done.tolerance > WorldProfile.TOLERANCE) {
                            coarser += "$z/$x/$y"
                            log("tile $z/$x/$y: written at %.0f times the tolerance, %d bytes".format(done.tolerance / WorldProfile.TOLERANCE, e.raw.size))
                        }
                        val s = stats.getOrPut(z) { LevelStats() }
                        s.tiles++
                        s.raw += e.raw.size
                        s.features += e.featureCount
                        if (z in FINE) fine += e
                        else coarse.getOrPut((x shr (z - 3)) to (y shr (z - 3))) { ArrayList() } += e
                    }
                    if (fine.isNotEmpty()) {
                        val id = "$MID/${WorldIndex.FINE_BLOCK}/$bx/$by"
                        val file = File(packDir, "$MID-${WorldIndex.FINE_BLOCK}-$bx-$by.atlas")
                        val written = WorldPack.write(fine, WorldPack.Spec(id, WorldIndex.FINE_BLOCK, bx, by, FINE, facts.dataTime, facts.sources, facts.source), file)
                        for (ch in written.chunks) stats.getValue(ch.z).stored += ch.stored
                        val box = Grid.tileBox(WorldIndex.FINE_BLOCK, bx, by)
                        val tiles = fine.size
                        finePacks += file to { part: Int, offset: Long, length: Long, sha: ByteArray ->
                            WorldIndex.Entry(WorldIndex.FINE_BLOCK, bx, by, FINE.first, FINE.last, part, offset, length, tiles, box, sha)
                        }
                    }
                    log("block 6/$bx/$by: ${results.size} tiles looked at, ${fine.size} of L9 to L11 with land or features")
                }
            } finally {
                pool.shutdown()
            }
        }
        timed("cut", t2)

        // the packs, end to end in their parts, and the index: the coarse tiles in their own file instead, for a region
        val t3 = System.nanoTime()
        val parts = ArrayList<WorldIndex.Part>()
        var entries = WorldPack.parts(outDir, run.parts, finePacks, 0, parts)
        if (run.coarseTiles != null) {
            CoarseTiles.write(run.coarseTiles, CoarseTiles.Region(facts, blockList, coarse.values.flatten()))
            log("coarse tiles: ${coarse.values.sumOf { it.size }} of ${coarse.size} L3 blocks in ${run.coarseTiles.path} (${run.coarseTiles.length()} bytes)")
        } else {
            val packs = coarsePacks(coarse, blockList, facts, packDir) { w -> for (ch in w.chunks) stats.getValue(ch.z).stored += ch.stored }
            entries = entries + WorldPack.parts(outDir, COARSE_PARTS, packs, parts.size, parts)
        }
        val index = WorldIndex(date ?: Instant.EPOCH, parts, entries)
        File(outDir, "world.idx").outputStream().use { index.write(it) }
        packDir.deleteRecursively()
        if (!keepMbtiles) mbtiles.delete()
        timed("packs", t3)

        for ((z, s) in stats) {
            val stored = if (run.coarseTiles != null && z in COARSE) "stored once put together" else "${s.stored} bytes stored"
            log("L$z: ${s.tiles} tiles, ${s.features} features, $stored (${s.raw} raw)")
        }
        log("world: ${entries.size} packs in ${parts.joinToString { "${it.name} (${it.bytes} bytes)" }}; the cells' ground within %.3g of their areas, %d rings across a cell's edge, %d of Planetiler's geometries mended, %d tiles at a coarser tolerance; %s"
            .format(worst, outside, WorldGeometry.mended.get(), coarser.size, seconds.entries.joinToString { "${it.key} %.0f s".format(it.value) }))
        val built = Built(index, stats, worst, outside, seconds, coarser)
        File(outDir, STATS).writeText(statsJson(built, blockList.size))
        return built
    }

    /** The file a build writes its numbers into beside its index ([statsJson]), which `world-assemble` adds up. */
    const val STATS = "world-stats.json"

    /** [built]'s numbers as JSON: each level's tiles, features and bytes, the seconds of each phase, the cut's checks. */
    fun statsJson(built: Built, blocks: Int): String {
        val o = buildJsonObject {
            put("blocks", blocks)
            putJsonObject("levels") {
                for ((z, s) in built.levels) putJsonObject("$z") { put("tiles", s.tiles); put("features", s.features); put("stored", s.stored); put("raw", s.raw) }
            }
            putJsonObject("seconds") { for ((k, v) in built.seconds) put(k, Math.round(v * 10) / 10.0) }
            put("worst_cover", built.worstCover)
            put("rings_outside", built.ringsOutside)
            put("mended", WorldGeometry.mended.get())
            putJsonArray("coarser") { for (c in built.coarser) add(c) }
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), o) + "\n"
    }

    /**
     * The coarse packs of [tiles] (each L3 block's L6 to L8 tiles, by block) written into [dir], each with its index
     * entry's facts, as one run of the L6 blocks [built] makes them: a pack's box is that of the blocks of [built] inside
     * its L3 block, where a tile it does not hold is open sea. [written] is told of each pack written.
     */
    fun coarsePacks(
        tiles: Map<Pair<Int, Int>, List<Format3Writer.Encoded>>,
        built: Collection<Pair<Int, Int>>,
        facts: Facts,
        dir: File,
        written: (Format3Writer.Written) -> Unit = {},
    ): List<Pair<File, (Int, Long, Long, ByteArray) -> WorldIndex.Entry>> {
        val z = WorldIndex.COARSE_BLOCK
        dir.mkdirs()
        return tiles.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).map { (k, list) ->
            val id = "$COARSE_PARTS/$z/${k.first}/${k.second}"
            val file = File(dir, "$COARSE_PARTS-$z-${k.first}-${k.second}.atlas")
            written(WorldPack.write(list, WorldPack.Spec(id, z, k.first, k.second, COARSE, facts.dataTime, facts.sources, facts.source), file))
            val inside = built.filter { (bx, by) -> bx shr (WorldIndex.FINE_BLOCK - z) == k.first && by shr (WorldIndex.FINE_BLOCK - z) == k.second }
            require(inside.isNotEmpty()) { "coarse tiles of L3 block ${k.first}/${k.second}, where no block was built" }
            val bs = inside.map { (bx, by) -> Grid.tileBox(WorldIndex.FINE_BLOCK, bx, by) }
            val box = intArrayOf(bs.minOf { it[0] }, bs.minOf { it[1] }, bs.maxOf { it[2] }, bs.maxOf { it[3] })
            val count = list.size
            file to { part: Int, offset: Long, length: Long, sha: ByteArray -> WorldIndex.Entry(z, k.first, k.second, COARSE.first, COARSE.last, part, offset, length, count, box, sha) }
        }
    }

    /**
     * Planetiler's run of [profile] over [pbf] into [mbtiles] from L6 to [maxLevel] within [bounds] (lon/lat), on
     * [threads] threads, its temporary files in [scratch]/tmp (deleted after). [mbtiles] goes to Planetiler as its file
     * URI: 0.7.0 parses an output path's string as a URI (overwriteOutput(Path) hands it to URI.create), and a path with
     * a space in it, as a checkout's can have, is no URI.
     */
    fun planetiler(pbf: File, mbtiles: File, scratch: File, threads: Int, bounds: Envelope, profile: WorldProfile, maxLevel: Int) {
        val arguments = Arguments.of(
            mapOf(
                "threads" to threads.toString(), "minzoom" to "${WorldProfile.MIN_LEVEL}", "maxzoom" to "$maxLevel",
                "tmpdir" to File(scratch, "tmp").path, "tile_compression" to "none", "madvise" to "false", "force" to "true",
                "bounds" to "${bounds.minX},${bounds.minY},${bounds.maxX},${bounds.maxY}", "output_layerstats" to "false", "loginterval" to "60s",
                "http_user_agent" to "Atlas/0.1 (+https://github.com/atlas-maps-app/Atlas)",
            ),
        )
        try {
            Planetiler.create(arguments).setProfile(profile).addOsmSource("osm", pbf.toPath()).overwriteOutput(mbtiles.toPath().toUri().toString()).run()
        } finally {
            File(scratch, "tmp").deleteRecursively()
        }
    }

    /**
     * The profile's decisions on the elements of [pbf] whose geometry's box meets one of [boxes] (lon/lat), with the
     * [tiles] (level, column, row) that hold each one's reference points ([WorldProfile.Audit]): Planetiler's run of the
     * profile over the whole extract, as `world` runs it, its tiles (L6 only, within the boxes) thrown away.
     */
    fun audit(pbf: File, scratch: File, threads: Int, boxes: List<Envelope>, tiles: List<IntArray>): Map<Pair<OsmType, Long>, WorldProfile.Audit.Audited> {
        val audit = WorldProfile.Audit(boxes, tiles)
        val bounds = Envelope().apply { for (b in boxes) expandToInclude(b) }
        scratch.mkdirs()
        val mbtiles = File(scratch, "audit.mbtiles")
        try {
            planetiler(pbf, mbtiles, scratch, threads, bounds, WorldProfile(audit = audit), WorldProfile.MIN_LEVEL)
        } finally {
            mbtiles.delete()
        }
        return audit.elements
    }

    /** Raised with any change to what the profile or the cut makes of the same data (the packs' source hash holds it). */
    const val PROFILE_VERSION = 1
}
