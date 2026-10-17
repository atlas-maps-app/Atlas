package app.atlas.mapcorecheck.tiles

import app.atlas.render.Regions
import app.atlas.render.V2
import app.atlas.rendertiles.Format3Reader
import app.atlas.rendertiles.compile.KeepCodes
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.Geom
import app.atlas.rendertiles.model.Grid
import app.atlas.rendertiles.model.Keep
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.Schema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * L0 (b) for the tile reader (plan-vector D16, K3): every record the core decodes from the bundled render packs equals
 * the Kotlin writer's, through the canonical dump both sides write; the core opens a pack as the Kotlin reader does (its
 * chunks' reach, its views' tolerances and its bands among it) and loads a frame at the tolerance the Kotlin head gives
 * it; its rings at every ladder tolerance equal `simplify`; a damaged column byte is caught and named; and on damaged
 * tiles the core and the Kotlin reader agree, each decoding what the other decodes, the same, and refusing what it
 * refuses.
 */
class L0DecodeTest {
    private val readers get() = BundledPacks.readers

    @Test
    fun theCoreReadsTheColumnsKotlinWrites() {
        val core = TileHost.schema().lines()
        val layers = LinkedHashMap<Int, Pair<String, MutableList<String>>>()
        var current: MutableList<String>? = null
        for (l in core) {
            val p = l.split(' ')
            when (p[0]) {
                "layer" -> current = mutableListOf<String>().also { layers[p[1].toInt()] = p[2] to it }
                "column" -> current!!.add("${p[1]}:${p[2]}")
            }
        }
        for (layer in Layer.entries) {
            val kotlin = runCatching { Schema.columns(layer) }.getOrNull()
            if (kotlin == null) {
                assertTrue("${layer.label} has no columns in payload ${Format.PAYLOAD}: the core must not define it", layer.id !in layers)
                continue
            }
            val (label, columns) = layers[layer.id] ?: error("the core does not define the ${layer.label} layer")
            assertEquals("layer ${layer.id}", layer.label, label)
            assertEquals("${layer.label}'s columns", kotlin.map { "${it.name}:${it.codec.id}" }, columns)
        }
        val keep = core.first { it.startsWith("keep ") }.split(' ').drop(1)
        assertEquals("the keep ladder", Keep.TOLERANCES.map { "%016x".format(it.toRawBits()) }, keep)
        assertEquals("keep_all ${Keep.ALL} min_points ${Keep.MIN_POINTS} payload ${Format.PAYLOAD}", core.first { it.startsWith("keep_all") })
    }

    @Test
    fun everyTileEdgeAtZoomsZeroToSixteenIsGridsOwn() {
        var edges = 0
        for (z in 0..16) {
            val core = TileHost.edges(z) ?: error("no edges at zoom $z")
            val n = (1 shl z) + 1
            for (i in 0 until n) {
                if (core[i] != Grid.colEdge(z, i)) fail("column edge $z/$i: the core's ${core[i]}, Grid's ${Grid.colEdge(z, i)}")
                if (core[n + i] != Grid.rowEdge(z, i)) fail("row edge $z/$i: the core's ${core[n + i]}, Grid's ${Grid.rowEdge(z, i)}")
            }
            edges += 2 * n
        }
        println("tile edges: all $edges column and row edges of zooms 0 to 16 equal Grid's")
    }

    @Test
    fun aPackOpensAsTheKotlinReaderOpensIt() {
        for ((file, r) in readers) {
            val want = buildString {
                append("format ").append(r.version).append('\n')
                for (s in r.sections.values) append("section ${s.name} ${s.offset} ${s.stored} ${s.raw} ${"%08x".format(s.crc)} ${s.method}\n")
                for (c in r.chunks) {
                    append("chunk ${c.z}/${c.x}/${c.y} ${c.offset} ${c.stored} ${c.raw} ${c.count} ")
                    append(c.sha256.joinToString("") { "%02x".format(it.toInt() and 0xFF) }).append('\n')
                }
                for ((c, reach) in r.chunks.zip(r.reach)) {
                    append("reach ${c.z}/${c.x}/${c.y} ${reach.south} ${reach.west} ${reach.north} ${reach.east} ${reach.topCm}\n")
                }
                val h = r.head
                append("profile ${h.profile.code}\nlevels")
                for (z in h.levels) append(' ').append(z)
                fun bits(d: Double) = "%016x".format(d.toRawBits())
                append("\nframe ${bits(h.lat0)} ${bits(h.lon0)} ${bits(h.kx)} ${bits(h.kz)}\nregion ${h.region}\n")
                append("engine ${h.engineVersion}\nstyle_version ${h.styleVersion}\nsource ${h.source}\n")
                for (v in h.views) append("view ${v.name} ${v.tolerance} ").append(valueText(v.camera)).append('\n')
                for (b in h.bands) append("band ${b.tolerance} ${b.upTo?.let(::bits) ?: "last"}\n")
                for ((name, value) in r.style.tables) append("table $name ").append(valueText(value)).append('\n')
                append("verify ok\n")
            }
            assertEquals("${file.name} as the core opens it", want, TileHost.pack(file.path))
        }
    }

    @Test
    fun aFrameLoadsAtTheToleranceTheKotlinHeadGivesIt() {
        var checked = 0
        for ((file, r) in readers) {
            val h = r.head
            for (v in h.views) {
                assertEquals("${file.name} view ${v.name}", h.toleranceOf(v.name, 0.0), TileHost.tolerance(file.path, v.name, 0.0))
                checked++
            }
            assertEquals("${file.name}: a view it does not have", -1, TileHost.tolerance(file.path, "nowhere", 0.0))
            // every band's end and its neighbours, and distances from below zero to past the last band
            val ends = h.bands.mapNotNull { it.upTo }
            val distances = ends.flatMap { listOf(Math.nextDown(it), it, Math.nextUp(it)) } +
                listOf(-1.0, 0.0, 1.0, 50.0, 430.0, 2_500.0, 1e4, 1e9, Double.POSITIVE_INFINITY, Double.NaN)
            for (d in distances) {
                assertEquals("${file.name} at $d m", h.toleranceOf(null, d), TileHost.tolerance(file.path, null, d))
                checked++
            }
        }
        println("tolerances: $checked frames load at the ladder index the Kotlin head gives them")
    }

    @Test
    fun everyRecordOfBothBundledScenesDecodesAsTheKotlinWriterWroteIt() {
        val lines = ArrayList<String>()
        var features = 0L
        for ((file, r) in readers) {
            val name = file.nameWithoutExtension
            for (c in r.chunks) {
                val tile = r.decode(c)
                val kotlin = app.atlas.rendertiles.CanonicalDump.of(tile)
                val core = TileHost.dump(file.path, c.z, c.x, c.y)
                firstDifference(core, kotlin)?.let { fail("$name ${c.z}/${c.x}/${c.y}: $it") }
                val hash = sha256Hex(kotlin)
                assertEquals("$name ${c.z}/${c.x}/${c.y}: the core's hash", hash, TileHost.hash(file.path, c.z, c.x, c.y))
                lines += "tiles l0 $name ${c.z}/${c.x}/${c.y} $hash"
                features += tile.featureCount
            }
        }
        // the Kotlin writer's hashes, for the emulator's run of the core (atlas_tests on Bionic, ATLAS_TILES_EXPECT)
        System.getProperty("atlas.l0.out")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "tiles-l0.txt").writeText(lines.joinToString("\n", postfix = "\n"))
        }
        println("L0 (b): ${lines.size} tiles, $features records of both bundled scenes equal to the Kotlin writer's")
        assertEquals(readers.values.sumOf { it.chunks.size }, lines.size)
        assertTrue(features > 50_000)
    }

    @Test
    fun ringsAtEveryLadderToleranceEqualSimplify() {
        var rings = 0
        var rebuilds = 0
        var simplified = 0
        for ((file, r) in readers) {
            val h = r.head
            for (c in r.chunks) {
                val tile = r.decode(c)
                for (layer in listOf(Layer.BUILDING, Layer.PART, Layer.GROUND, Layer.WATER)) {
                    val features = tile.layers[layer] ?: continue
                    val geoms = features.map { it["ring"] as Geom? ?: Geom.EMPTY }
                    val metres = geoms.map { g -> List(g.size) { i -> V2(Grid.x(g.lon[i], h.lon0, h.kx), Grid.z(g.lat[i], h.lat0, h.kz)) } }
                    // whole rings with simplify's own codes (buildings, parts, areas, land cover) against simplify; the
                    // water's clipped pieces against their rule (clip points always kept); a ring without codes (a pack
                    // never simplified) is never simplified
                    val bySimplify = features.map { layer != Layer.WATER }
                    rings += features.size
                    for (level in 1 until Keep.TOLERANCES.size) {
                        val core = TileHost.simplify(file.path, c.z, c.x, c.y, layer.id, "ring", level)
                        assertNotNull("${file.name} ${c.z}/${c.x}/${c.y} ${layer.label} at level $level", core)
                        var at = 0
                        for ((i, g) in geoms.withIndex()) {
                            val n = core!![at++]
                            val got = List(n) { metres[i][core[at + it]] }
                            at += n
                            val want = when {
                                g.keep == null -> metres[i]
                                bySimplify[i] -> Regions.simplify(metres[i], Keep.TOLERANCES[level])
                                else -> KeepCodes.simplify(metres[i], g.keep!!, level)
                            }
                            if (got != want) {
                                fail("${file.name} ${c.z}/${c.x}/${c.y} ${layer.label} #${features[i]["index"]} at ${Keep.TOLERANCES[level]} m: " +
                                    "the core keeps ${got.size} of ${g.size} vertices, simplify ${want.size}")
                            }
                            rebuilds++
                            if (want.size < g.size) simplified++
                        }
                        assertEquals("every ring read", core!!.size, at)
                    }
                }
            }
        }
        println("simplify: $rings rings, $rebuilds rebuilds at the ladder's 13 tolerances, $simplified of them cut, all equal")
        assertTrue(rebuilds > 400_000)
    }

    @Test
    fun aDamagedColumnByteIsCaughtAndNamed() {
        // each column in the densest tile that has it (the living map carries no keep codes)
        val tiles = readers.flatMap { (file, r) -> r.chunks.map { c -> Triple(file, c, r.raw(c)) } }.sortedByDescending { it.third.size }
        val columnsOf = tiles.associate { (_, c, raw) -> c to TileHost.columns(direct(raw)).lines().filter { it.isNotBlank() }.map { it.split(' ') } }
        var checked = 0
        val required = listOf("building h", "building ring", "building ring.keep", "building wall_rgb", "building mat_draw",
            "thing pos", "road hw", "road marks_key", "quay edge", "ground kind")
        // columns the bundled scenes may leave out (every value its default): checked where a tile has them
        val optional = listOf("item x", "item z", "line layer", "thing building", "label ground_key")
        for (want in required + optional) {
            val (layer, column) = want.split(' ')
            val found = tiles.firstOrNull { (_, c, _) -> columnsOf.getValue(c).any { it[0] == layer && it[1] == column } }
            if (found == null && want in optional) {
                println("damaged columns: no tile of the bundled packs has $want")
                continue
            }
            val (file, c, raw) = found ?: throw AssertionError("no tile of the bundled packs has the column $want")
            val original = kotlinDump(raw)
            val col = columnsOf.getValue(c).first { it[0] == layer && it[1] == column }
            val offset = col[2].toInt()
            val length = col[3].toInt()
            val damaged = raw.copyOf()
            val at = offset + length / 2
            damaged[at] = (damaged[at].toInt() xor 0x5A).toByte()
            val core = TileHost.decode(direct(damaged))
            val message = firstDifference(core, original) ?: throw AssertionError("a damaged byte of $want went unnoticed")
            val named = if (core.startsWith("error=")) {
                core.contains(" layer=$layer ") && core.contains(" column=$column ")
            } else {
                message.contains("field $layer.${column.removeSuffix(".keep")}:")
            }
            assertTrue("a damaged byte of $want in ${file.name} ${c.z}/${c.x}/${c.y} must name $layer.$column: $message", named)
            checked++
        }
        println("damaged columns: $checked caught and named")
        assertTrue(checked >= required.size)
    }

    @Test
    fun onDamagedTilesTheCoreAgreesWithTheKotlinReader() {
        val damages = System.getProperty("atlas.l0.damages")?.toInt() ?: 1500
        val random = Random(20261016)
        val tiles = readers.values.flatMap { r -> r.chunks.map { r.raw(it) } }.sortedBy { it.size }
        val small = tiles.take(8)
        val tally = sortedMapOf<String, Int>()
        repeat(damages) { k ->
            val raw = (if (k % 10 == 9) tiles.random(random) else small.random(random)).copyOf()
            // half the damages in the header, directory, strings and cells, where structure lives
            val at = if (k % 2 == 0) random.nextInt(minOf(raw.size, 600)) else random.nextInt(raw.size)
            val was = raw[at]
            while (raw[at] == was) raw[at] = random.nextInt(256).toByte()
            val kotlin = try {
                Result.success(kotlinDump(raw))
            } catch (e: Exception) {
                Result.failure(e)
            }
            val core = TileHost.decode(direct(raw))
            val coreRefuses = core.startsWith("error=")
            val outcome = when {
                kotlin.isSuccess && !coreRefuses -> {
                    firstDifference(core, kotlin.getOrThrow())?.let { fail("damage $k at $at: both decode, differently: $it") }
                    "both decode, equal"
                }
                kotlin.isFailure && coreRefuses -> "both refuse"
                kotlin.isFailure -> "Kotlin refuses (${kind(kotlin.exceptionOrNull()?.message)}), the core reads"
                else -> "the core refuses (${kind(core.substringAfter("error=").substringBefore(" layer="))}), Kotlin reads"
            }
            tally.merge(outcome, 1, Int::plus)
        }
        println("$damages damaged tiles: $tally")
        val disagree = tally.filterKeys { it != "both decode, equal" && it != "both refuse" }
        assertTrue("the core and the Kotlin reader disagree on damaged tiles: $disagree", disagree.isEmpty())
    }

    /** A refusal's words without its numbers, so refusals of one kind count together. */
    private fun kind(message: String?): String = (message ?: "").replace(Regex("-?[0-9]+"), "#").take(80)

    @Test
    fun aDenseTileDecodesQuickly() {
        val (file, r) = readers.entries.last()
        val c = r.chunks.maxBy { it.raw }
        val ns = TileHost.decodeNanos(file.path, c.z, c.x, c.y, 5)
        assertTrue(ns > 0)
        println("the densest tile, ${file.name} ${c.z}/${c.x}/${c.y} (${c.raw} bytes, ${c.count} features): ${"%.2f".format(ns / 1e6)} ms in the JNI build")
        // §3's gate is 15 ms on one core for a dense tile: decompression, its SHA-256 and every column. The JNI build is a
        // debug one (-O1), slower than the release build the bench times, so the gate holds a fortiori.
        assertTrue("the densest tile takes ${ns / 1e6} ms, over 15", ns <= 15_000_000)
    }

    /** A typed value as the core's test entry points write one. */
    private fun valueText(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is Int, is Long -> v.toString()
        is Double -> "d" + java.lang.Long.toHexString(v.toRawBits())
        is String -> "\"$v\""
        is List<*> -> v.joinToString(",", "<", ">") { valueText(it) }
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, e) -> "\"$k\":${valueText(e)}" }
        else -> error("no text for ${v::class}")
    }
}
