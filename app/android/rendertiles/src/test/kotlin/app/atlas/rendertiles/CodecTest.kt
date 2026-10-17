package app.atlas.rendertiles

import app.atlas.render.Regions
import app.atlas.render.V2
import app.atlas.rendertiles.codec.Columns
import app.atlas.rendertiles.codec.Format3Writer
import app.atlas.rendertiles.codec.LeSink
import app.atlas.rendertiles.codec.LeSource
import app.atlas.rendertiles.codec.StringTable
import app.atlas.rendertiles.codec.TileFormatException
import app.atlas.rendertiles.codec.Values
import app.atlas.rendertiles.compile.Cells
import app.atlas.rendertiles.compile.Clip
import app.atlas.rendertiles.compile.KeepCodes
import app.atlas.rendertiles.model.Codec
import app.atlas.rendertiles.model.Feature
import app.atlas.rendertiles.model.Geom
import app.atlas.rendertiles.model.Grid
import app.atlas.rendertiles.model.GridPoint
import app.atlas.rendertiles.model.Keep
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.Profile
import app.atlas.rendertiles.model.Reach
import app.atlas.rendertiles.model.RenderHead
import app.atlas.rendertiles.model.RenderTile
import app.atlas.rendertiles.model.Schema
import app.atlas.rendertiles.model.StyleTables
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * The payload's codecs, the clipper, the grid and the keep codes on cases chosen to break them; the one rule for what a
 * reader does not know, the bounds it checks, the `reach` and `rhead` sections.
 */
class CodecTest {
    private fun roundTrip(codec: Codec, values: List<Any?>): List<Any?> {
        val strings = StringTable()
        val bytes = Columns.encode(codec, values, strings)
        return Columns.decode(codec, LeSource(bytes), values.size, strings.strings)
    }

    @Test
    fun `scalar codecs give back what they were given`() {
        val longs = listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 127L, 128L, -128L, 1L shl 40)
        for (c in listOf(Codec.VARZ, Codec.DELTA)) assertEquals(c.name, longs, roundTrip(c, longs))
        assertEquals(listOf(0L, 5L, Long.MAX_VALUE), roundTrip(Codec.VARU, listOf(0L, 5L, Long.MAX_VALUE)))
        assertEquals(listOf(0, 255, 7), roundTrip(Codec.U8, listOf(0, 255, 7)))
        val nl = listOf(null, 0L, -5L, Long.MIN_VALUE + 1, Long.MAX_VALUE, null, 3L)
        assertEquals(nl, roundTrip(Codec.NVARZ, nl))
        assertEquals(listOf(null, 0L, -5L, Long.MIN_VALUE / 2, Long.MAX_VALUE / 2, null, 3L), roundTrip(Codec.NDELTA, listOf(null, 0L, -5L, Long.MIN_VALUE / 2, Long.MAX_VALUE / 2, null, 3L)))
        for (c in listOf(Codec.NVARZ, Codec.NDELTA)) try {
            roundTrip(c, listOf(Long.MIN_VALUE))
            fail("${c.name} took Long.MIN_VALUE")
        } catch (e: IllegalArgumentException) {
        }
        val strs = listOf(null, "", "Prinsengracht", "Ünïcödé ✓", null, "Prinsengracht")
        assertEquals(strs, roundTrip(Codec.STR, strs))
        val nums = listOf("1", "263", "0", "007", "12A", "", null, "101-1", "999999999999999", "1234567890123456", "-3", "٣")
        assertEquals(nums, roundTrip(Codec.STRN, nums))
        assertEquals(listOf(null, false, true), roundTrip(Codec.BOOL, listOf(null, false, true)))
        val ids = listOf(emptyList(), listOf(5L, 3L, Long.MAX_VALUE), listOf(-1L))
        assertEquals(ids, roundTrip(Codec.IDS, ids))
        val sl = listOf(null, emptyList(), listOf("a", "", "a"))
        assertEquals(sl, roundTrip(Codec.STRS, sl))
        val doubles = listOf(0.0, 1.5, 12.35, -0.0, Double.NaN, 1e300, -7.125, 3.0000000001, Double.MIN_VALUE, 9e13, 9.1e13)
        val back = roundTrip(Codec.F64, doubles)
        for (i in doubles.indices) assertEquals(doubles[i].toRawBits(), (back[i] as Double).toRawBits())
    }

    @Test
    fun `centi keeps every double to the bit, and null`() {
        val vs = listOf(null, 0.0, -0.0, 12.35, 15.4, 0.1, 0.07, 1e-9, Double.NaN, Double.POSITIVE_INFINITY, -2.5, 123456.78, 9e13, 9.000000001e13, 1.0 / 3)
        val back = roundTrip(Codec.CENTI, vs)
        for (i in vs.indices) assertEquals("$i: ${vs[i]}", vs[i]?.toRawBits(), (back[i] as Double?)?.toRawBits())
        assertTrue(Columns.isCenti(12.35))
        assertTrue(!Columns.isCenti(-0.0))
        assertTrue(!Columns.isCenti(1.0 / 3))
        val r = Random(7)
        val many = List(20000) { if (it % 7 == 0) null else Math.round(r.nextDouble(-500.0, 500.0) * 100) / 100.0 }
        val mb = roundTrip(Codec.CENTI, many)
        for (i in many.indices) assertEquals(many[i]?.toRawBits(), (mb[i] as Double?)?.toRawBits())
    }

    @Test
    fun `geometry with shared vertices round trips`() {
        val r = Random(11)
        val geoms = ArrayList<Geom>()
        val pool = ArrayList<Pair<Int, Int>>()
        repeat(500) {
            val n = r.nextInt(0, 14)
            val lat = IntArray(n)
            val lon = IntArray(n)
            for (i in 0 until n) {
                // a third of the vertices repeat an earlier one, as party walls do; some far apart, some huge jumps
                if (pool.isNotEmpty() && r.nextInt(3) == 0) {
                    val p = pool[r.nextInt(pool.size)]
                    lat[i] = p.first; lon[i] = p.second
                } else {
                    lat[i] = 523_700_000 + r.nextInt(-200_000, 200_000) + if (r.nextInt(50) == 0) r.nextInt(-90_000_000, 90_000_000) else 0
                    lon[i] = 48_800_000 + r.nextInt(-300_000, 300_000) + if (r.nextInt(50) == 0) Int.MAX_VALUE / 2 else 0
                    pool += lat[i] to lon[i]
                }
            }
            geoms += Geom(lat, lon, null)
        }
        geoms += Geom(intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE, 0), intArrayOf(Int.MAX_VALUE, Int.MIN_VALUE, 0), null)
        @Suppress("UNCHECKED_CAST")
        assertEquals(geoms, roundTrip(Codec.GEOM, geoms) as List<Geom>)
    }

    @Test
    fun `keep codes are written only for rings simplify touches`() {
        val small = Geom(intArrayOf(1, 2, 3, 4), intArrayOf(1, 2, 3, 4), ByteArray(4) { Keep.ALL.toByte() })
        val big = Geom(IntArray(7) { it }, IntArray(7) { it * 2 }, byteArrayOf(13, 0, 4, 9, 2, 13, 1))
        val strings = StringTable()
        val g = Columns.encode(Codec.GEOM, listOf(small, big), strings)
        val k = Columns.encode(Codec.KEEP, listOf(small, big), strings)
        assertEquals(7, k.size)
        @Suppress("UNCHECKED_CAST")
        val geoms = Columns.decode(Codec.GEOM, LeSource(g), 2, strings.strings) as List<Geom>
        assertEquals(listOf(small, big), Columns.decode(Codec.KEEP, LeSource(k), 2, strings.strings, geoms))
        try {
            Columns.encode(Codec.KEEP, listOf(Geom(intArrayOf(1, 2, 3), intArrayOf(1, 2, 3), byteArrayOf(13, 0, 13))), StringTable())
            fail("a small ring with a code below ALL")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun `typed values keep their types`() {
        val v = linkedMapOf<String, Any?>(
            "int" to 3, "long" to 3L, "neg" to -7, "double" to 3.0, "nz" to -0.0, "nan" to Double.NaN, "s" to "x", "t" to true, "f" to false, "n" to null,
            "list" to listOf(1, 2.5, "a", null, listOf(listOf<Any?>())), "map" to linkedMapOf("b" to 2, "a" to 1),
        )
        for (strings in listOf(StringTable(), null)) {
            val out = LeSink()
            Values.write(out, v, strings)
            val back = Values.read(LeSource(out.toByteArray()), strings?.strings)
            assertEquals(v.toString(), back.toString())
            @Suppress("UNCHECKED_CAST")
            val m = back as Map<String, Any?>
            assertTrue(m["int"] is Int); assertTrue(m["long"] is Long); assertTrue(m["double"] is Double)
            assertEquals((-0.0).toRawBits(), (m["nz"] as Double).toRawBits())
            assertEquals(listOf("map"), m.keys.filter { it == "map" })
            assertEquals(listOf("b", "a"), (m["map"] as Map<*, *>).keys.toList())
        }
    }

    @Test
    fun `damaged columns fail as format errors, never otherwise`() {
        val r = Random(3)
        val geoms = List(40) { Geom(IntArray(8) { r.nextInt() }, IntArray(8) { r.nextInt() }, null) }
        val strings = StringTable()
        val good = Columns.encode(Codec.GEOM, geoms, strings)
        var caught = 0
        repeat(3000) {
            val b = good.copyOf(if (r.nextInt(3) == 0) r.nextInt(good.size) else good.size)
            if (b.isNotEmpty()) b[r.nextInt(b.size)] = r.nextInt(256).toByte()
            try {
                Columns.decode(Codec.GEOM, LeSource(b), geoms.size, strings.strings)
            } catch (e: TileFormatException) {
                caught++
            }
        }
        assertTrue(caught > 0)
    }

    @Test
    fun `the grid inverts the frame exactly, or not at all`() {
        val lat0 = 52.372; val lon0 = 4.891; val kx = 67964.45305223769; val kz = 110540.0
        val r = Random(5)
        repeat(20000) {
            val qa = 523_600_000 + r.nextInt(400_000)
            val qo = 48_700_000 + r.nextInt(600_000)
            val x = Grid.x(qo, lon0, kx)
            val z = Grid.z(qa, lat0, kz)
            assertEquals(qa, Grid.latOf(z, lat0, kz))
            assertEquals(qo, Grid.lonOf(x, lon0, kx))
            assertEquals((qo / 1e7 - lon0) * kx, x, 0.0)
        }
        assertNull(Grid.latOf(Math.nextUp(Grid.z(523_700_000, lat0, kz)), lat0, kz))
    }

    @Test
    fun `tiles hold their grid edges and cells their points`() {
        for (z in intArrayOf(12, 14)) for ((x, y) in listOf(8414 to 5384, 0 to 0, (1 shl z) - 1 to (1 shl z) - 1)) {
            val xx = x shr (14 - z)
            val yy = y shr (14 - z)
            val b = Grid.tileBox(z, xx, yy)
            assertTrue(b[0] < b[2] && b[1] < b[3])
            assertEquals(xx, Grid.col(b[1], z)); assertEquals(yy, Grid.row(b[0], z))
            if (xx > 0) assertEquals(xx - 1, Grid.col(b[1] - 1, z))
            if (yy > 0) assertEquals(yy - 1, Grid.row(b[2], z))
            // neighbours share their edges
            if (xx + 1 < (1 shl z)) assertEquals(b[3], Grid.tileBox(z, xx + 1, yy)[1])
            val key = Cells.TileKey(z, xx, yy)
            assertEquals(0, Cells.cellOf(key, b[2], b[1]))
            val n = if (z >= 14) 8 else 4
            assertEquals(n * n - 1, Cells.cellOf(key, b[0], b[3]))
        }
    }

    /** The exact area of a ring on the grid, twice, as a double. */
    private fun area2(lat: IntArray, lon: IntArray): Double {
        var s = java.math.BigInteger.ZERO
        for (i in lat.indices) {
            val j = (i + 1) % lat.size
            s = s.add(java.math.BigInteger.valueOf(lon[i].toLong() * lat[j].toLong() - lon[j].toLong() * lat[i].toLong()))
        }
        return s.toDouble()
    }

    @Test
    fun `clipping cuts rings into pieces that fill them`() {
        val box = intArrayOf(0, 0, 1000, 1000)
        // a U that leaves the box and comes back: one piece with a bridge along the edge
        val uLat = intArrayOf(-500, -500, 1500, 1500, 600, 600, 1500, 1500)
        val uLon = intArrayOf(100, 900, 900, 700, 700, 300, 300, 100)
        val p = Clip.ring(uLat, uLon, box)!!
        for (i in 0 until p.size) assertTrue(p.lat[i] in 0..1000 && p.lon[i] in 0..1000)
        // tile by tile, the pieces add up to the whole
        val r = Random(9)
        repeat(300) {
            val n = r.nextInt(3, 30)
            val cx = r.nextInt(-2000, 2000); val cy = r.nextInt(-2000, 2000)
            // a star-shaped ring (simple), around a random centre
            val angles = (0 until n).map { r.nextDouble(0.0, 2 * Math.PI) }.sorted()
            val lat = IntArray(n) { (cy + Math.sin(angles[it]) * r.nextInt(100, 3000)).toInt() }
            val lon = IntArray(n) { (cx + Math.cos(angles[it]) * r.nextInt(100, 3000)).toInt() }
            var sum = 0.0
            for (tx in -5..4) for (ty in -5..4) {
                val b = intArrayOf(ty * 1000, tx * 1000, ty * 1000 + 1000, tx * 1000 + 1000)
                val piece = Clip.ring(lat, lon, b) ?: continue
                for (i in 0 until piece.size) assertTrue(piece.lat[i] in b[0]..b[2] && piece.lon[i] in b[1]..b[3])
                sum += area2(piece.lat, piece.lon)
            }
            val whole = area2(lat, lon)
            assertEquals(whole, sum, n * 2 * 6000.0 + Math.abs(whole) * 1e-12)
        }
        assertNull(Clip.ring(intArrayOf(5000, 5000, 6000), intArrayOf(5000, 6000, 5000), box))
        assertTrue(Clip.inside(intArrayOf(1, 2, 3), intArrayOf(1, 2, 3), box))
    }

    @Test
    fun `keep codes rebuild simplify on hard rings`() {
        val r = Random(13)
        val rings = ArrayList<List<V2>>()
        // collinear runs, repeated points, tiny and huge rings, a ring whose simplification has no area
        rings += listOf(V2(0.0, 0.0), V2(1.0, 0.0), V2(2.0, 0.0), V2(3.0, 0.0), V2(3.0, 1.0), V2(0.0, 1.0))
        rings += listOf(V2(0.0, 0.0), V2(0.0, 0.0), V2(5.0, 0.0), V2(5.0, 0.0), V2(5.0, 5.0), V2(0.0, 5.0), V2(0.0, 5.0))
        rings += List(8) { V2(1.0, 1.0) }
        rings += listOf(V2(0.0, 0.0), V2(10.0, 0.0), V2(10.0, 0.1), V2(0.0, 0.1), V2(0.0, 0.05), V2(5.0, 0.06), V2(0.0, 0.07))
        rings += listOf(V2(0.0, 0.0), V2(4.0, 0.0), V2(4.0, 4.0), V2(0.0, 4.0))
        repeat(2000) {
            val n = r.nextInt(3, 40)
            val noise = r.nextDouble(0.0, 2.0)
            rings += (0 until n).map { k ->
                val a = 2 * Math.PI * k / n
                V2(Math.round((Math.cos(a) * 20 + r.nextDouble(-noise, noise)) * 1e4) / 1e4, Math.round((Math.sin(a) * 12 + r.nextDouble(-noise, noise)) * 1e4) / 1e4)
            }
        }
        for (ring in rings) {
            val codes = KeepCodes.of(ring)
            for (i in 1 until Keep.TOLERANCES.size) assertEquals("$ring at ${Keep.TOLERANCES[i]}", Regions.simplify(ring, Keep.TOLERANCES[i]), KeepCodes.simplify(ring, codes, i))
        }
    }

    @Test
    fun `a tile encodes and decodes with its cells`() {
        val box = Grid.tileBox(14, 8414, 5384)
        fun f(i: Int, cell: Int) = Feature(
            Layer.ROAD,
            Schema.canonical(Layer.ROAD, mapOf("index" to i, "id" to 100L + i, "line" to Geom(intArrayOf(box[0] + i, box[0] + 2 * i), intArrayOf(box[1] + 3, box[1] + i), null), "w" to 5.5, "hw" to "residential", "ground_key" to 7L)),
            cell, intArrayOf(box[0] + i, box[1] + 3, box[0] + 2 * i, box[1] + i + 3), 0, i.toLong(),
        )
        val t = RenderTile.of(14, 8414, 5384, listOf(f(3, 9), f(1, 0), f(2, 9)))
        val back = Format3Reader.decodeTile(Format3Writer.encodeTile(t))
        assertEquals(t.layers, back.layers)
        assertEquals(t.cells, back.cells)
        assertEquals(listOf(1L, 2L, 3L), back.layers.getValue(Layer.ROAD).map { it["index"] })
        val bytes = Format3Writer.encodeTile(t)
        // a byte flipped anywhere: a clean decode or a format error
        var errors = 0
        for (i in bytes.indices) for (bit in 0 until 8) {
            val b = bytes.copyOf()
            b[i] = (b[i].toInt() xor (1 shl bit)).toByte()
            try {
                Format3Reader.decodeTile(b)
            } catch (e: TileFormatException) {
                errors++
            } catch (e: Exception) {
                fail("byte $i bit $bit: ${e::class.simpleName} ${e.message}")
            }
        }
        assertTrue(errors > 0)
        assertArrayEquals(bytes, Format3Writer.encodeTile(t))
        assertTrue(Format3Reader.decodeTile(bytes).layers.getValue(Layer.ROAD).all { it["line"] is Geom && it["index"] is Long })
        assertEquals(GridPoint(1, 2), GridPoint(1, 2))
    }

    /** A one-layer tile of roads, whose strings hold "bridge" (a column the roads leave out) as a tag's value. */
    private fun roadTile(): RenderTile {
        val box = Grid.tileBox(14, 8414, 5384)
        val fs = (1..3).map { i ->
            Feature(
                Layer.ROAD,
                Schema.canonical(Layer.ROAD, mapOf("index" to i, "id" to 100L + i, "line" to Geom(intArrayOf(box[0] + i, box[0] + 2 * i), intArrayOf(box[1] + 3, box[1] + i), null), "w" to 5.5, "hw" to "residential", "tag:surface" to "bridge")),
                0, intArrayOf(box[0] + i, box[1] + 3, box[0] + 2 * i, box[1] + i + 3), 0, i.toLong(),
            )
        }
        return RenderTile.of(14, 8414, 5384, fs)
    }

    /** [tile]'s bytes with one more column appended to its only layer: [name] (a string of its table), [codec] and [bytes]. */
    private fun withColumn(tile: ByteArray, name: String, codec: Int, bytes: ByteArray): ByteArray {
        val h = LeSource(tile, 0, tile.size)
        h.pos = 32
        val sAt = h.u32().toInt(); val sLen = h.u32().toInt()
        val strings = StringTable.read(LeSource(tile, sAt, sAt + sLen))
        val ix = strings.indexOf(name).also { require(it >= 0) { "no string $name" } }
        val col = LeSink()
        col.varU(ix.toLong()); col.u8(codec); col.varU(bytes.size.toLong()); col.bytes(bytes)
        val e = Format.HEADER_BYTES
        h.pos = e + 4
        val count = h.u32()
        h.pos = e + 8
        val at = h.u32().toInt(); val len = h.u32().toInt()
        require(at + len == tile.size) { "the layer is not the tile's last block" }
        val out = LeSink(tile.size + col.size)
        out.bytes(tile)
        out.bytes(col.toByteArray())
        val cols = (tile[e + 2].toInt() and 0xFF) or ((tile[e + 3].toInt() and 0xFF) shl 8)
        out.putU16(e + 2, cols + 1)
        out.putU32(e + 12, (len + col.size).toLong())
        require(count == 3L)
        return out.toByteArray()
    }

    @Test
    fun `a reader skips columns it does not know and refuses known ones in another codec`() {
        val t = roadTile()
        val bytes = Format3Writer.encodeTile(t)
        val want = Format3Reader.decodeTile(bytes).layers
        // an unknown name ("residential" is a value, not a column), with a known codec and with a codec no reader knows
        assertEquals(want, Format3Reader.decodeTile(withColumn(bytes, "residential", Codec.U8.id, byteArrayOf(1, 2, 3))).layers)
        assertEquals(want, Format3Reader.decodeTile(withColumn(bytes, "residential", 200, byteArrayOf(9, 9, 9, 9, 9))).layers)
        // a known name in its own codec reads; in another codec, or one no reader knows, it is an error
        val bridged = Format3Reader.decodeTile(withColumn(bytes, "bridge", Codec.BOOL.id, byteArrayOf(2, 0, 1))).layers.getValue(Layer.ROAD)
        assertEquals(listOf(true, null, false), bridged.map { it["bridge"] })
        for (codec in listOf(Codec.U8.id, 200)) try {
            Format3Reader.decodeTile(withColumn(bytes, "bridge", codec, byteArrayOf(2, 0, 1)))
            fail("bridge read in codec $codec")
        } catch (e: TileFormatException) {
        }
        // a known column twice
        try {
            Format3Reader.decodeTile(withColumn(bytes, "hw", Codec.STR.id, byteArrayOf(1, 1, 1)))
            fail("a column twice")
        } catch (e: TileFormatException) {
        }
    }

    @Test
    fun `a reader checks the tile's bounds before it reads`() {
        val good = Format3Writer.encodeTile(roadTile())
        Format3Reader.decodeTile(good)
        fun bad(what: String, edit: (LeSink) -> Unit) {
            val b = LeSink(good.size).also { it.bytes(good) }
            edit(b)
            try {
                Format3Reader.decodeTile(b.toByteArray())
                fail(what)
            } catch (e: TileFormatException) {
            }
        }
        bad("level 25") { it.unsafeArray()[6] = 25 }
        bad("4 cells a side at L14") { it.unsafeArray()[7] = 4 }
        bad("a column past its level") { it.putU32(8, 1L shl 14) }
        bad("a row past its level") { it.putU32(12, 0xFFFFFFFFL) }
        bad("another payload") { it.putU16(4, Format.PAYLOAD + 1) }
        // the chunk table's bounds
        Format3Reader.checkTile(24, (1L shl 24) - 1, 0)
        for ((z, x, y) in listOf(Triple(25, 0L, 0L), Triple(14, 1L shl 14, 0L), Triple(3, 0L, -1L), Triple(-1, 0L, 0L))) try {
            Format3Reader.checkTile(z, x, y)
            fail("tile $z/$x/$y")
        } catch (e: TileFormatException) {
        }
    }

    @Test
    fun `a reader refuses a varint longer than its shortest form`() {
        // what the writer writes reads back, from 0 to the 10-byte maximum
        for (v in listOf(0L, 1L, 127L, 128L, 16_383L, 16_384L, Long.MAX_VALUE, -1L)) {
            val b = LeSink(16).also { it.varU(v) }.toByteArray()
            assertEquals(v, LeSource(b).varU())
        }
        // 0 and 1 in two bytes, 127 in three, 2^56 - 1 in ten: each has a shorter form
        val overlong = listOf(
            byteArrayOf(0x80.toByte(), 0x00), byteArrayOf(0x81.toByte(), 0x00), byteArrayOf(0xFF.toByte(), 0x80.toByte(), 0x00),
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0x80.toByte(), 0x00),
        )
        for (b in overlong) try {
            LeSource(b).varU()
            fail("read ${b.joinToString(" ") { "%02x".format(it) }}")
        } catch (e: TileFormatException) {
            assertTrue(e.message!!, e.message!!.contains("overlong") || e.message!!.contains("too long"))
        }
        // a string table whose count is written in two bytes where one is enough is refused
        val table = LeSink(16).also { it.bytes(byteArrayOf(0x81.toByte(), 0x00)); it.utf8("hw") }.toByteArray()
        try {
            StringTable.read(LeSource(table))
            fail("a string table with an overlong count read")
        } catch (e: TileFormatException) {
            assertTrue(e.message!!, e.message!!.contains("overlong"))
        }
        assertEquals(listOf("hw"), StringTable.read(LeSource(LeSink(16).also { it.varU(1); it.utf8("hw") }.toByteArray())))
    }

    @Test
    fun `a reader refuses a string that is not UTF-8`() {
        // a lone continuation byte, an overlong '/', a surrogate, past U+10FFFF, a sequence cut short
        val bad = listOf(
            byteArrayOf(0x80.toByte()), byteArrayOf(0xC0.toByte(), 0xAF.toByte()), byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()), byteArrayOf(0x41, 0xE2.toByte(), 0x82.toByte()),
        )
        for (b in bad) {
            val sink = LeSink(16).also { it.varU(b.size.toLong()); it.bytes(b) }
            try {
                LeSource(sink.toByteArray()).utf8()
                fail("read ${b.joinToString(" ") { "%02x".format(it) }}")
            } catch (e: TileFormatException) {
                assertTrue(e.message!!, e.message!!.contains("UTF-8"))
            }
        }
        val good = LeSink(64).also { it.utf8("Prinsengracht · € \uD834\uDD1E") }
        assertEquals("Prinsengracht · € \uD834\uDD1E", LeSource(good.toByteArray()).utf8())
        // a damaged string in a tile's string table is refused, not read with U+FFFD in its place
        val tile = Format3Writer.encodeTile(roadTile())
        val table = LeSource(tile, LeSource(tile, 32).u32().toInt())
        table.varCount()
        val first = table.varCount()
        assertTrue(first > 0)
        val damaged = tile.copyOf().also { it[table.pos] = 0xFF.toByte() }
        try {
            Format3Reader.decodeTile(damaged)
            fail("a tile with a damaged string read")
        } catch (e: TileFormatException) {
            assertTrue(e.message!!, e.message!!.contains("UTF-8"))
        }
    }

    @Test
    fun `the reach section reads back, newer entries skipped, damage refused`() {
        val rs = listOf(Reach(-5, -6, 7, 8, 120), Reach(523_700_000, 48_800_000, 523_800_000, 48_900_000, 0))
        val b = Format3Writer.reachBytes(rs)
        assertEquals(Reach.HEADER_BYTES + 2 * Reach.ENTRY_BYTES, b.size)
        assertEquals(rs, Format3Reader.readReach(b, 2))
        // a newer payload's longer entries: the known fields read, the rest skipped
        val wide = LeSink()
        wide.u32(Format.REACH_MAGIC.toLong()); wide.u16(Format.PAYLOAD); wide.u16(Reach.ENTRY_BYTES + 4); wide.u32(2)
        for (r in rs) { wide.i32(r.south); wide.i32(r.west); wide.i32(r.north); wide.i32(r.east); wide.i32(r.topCm); wide.i32(-1) }
        assertEquals(rs, Format3Reader.readReach(wide.toByteArray(), 2))
        val cases = listOf(
            "another count" to { Format3Reader.readReach(b, 3) },
            "short entries" to { Format3Reader.readReach(b.copyOf().also { it[6] = 16 }, 2) },
            "not a reach" to { Format3Reader.readReach(b.copyOf().also { it[0] = 0 }, 2) },
            "cut short" to { Format3Reader.readReach(b.copyOf(b.size - 1), 2) },
            "an empty box" to { Format3Reader.readReach(Format3Writer.reachBytes(listOf(Reach(10, 0, 5, 0, 0))), 1) },
        )
        for ((what, f) in cases) try {
            f()
            fail(what)
        } catch (e: TileFormatException) {
        }
        // a tile's reach is the union of its cells' boxes and their highest top
        val t = roadTile()
        val cells = t.cells.mapNotNull { it.box }
        assertEquals(Reach(cells.minOf { it[0] }, cells.minOf { it[1] }, cells.maxOf { it[2] }, cells.maxOf { it[3] }, t.cells.maxOf { it.maxHeightCm }), Reach.of(t))
    }

    @Test
    fun `the head carries each view's tolerance and the bands`() {
        val views = listOf(RenderHead.View("a", linkedMapOf("dist" to 470, "pitch" to 52.5), 7), RenderHead.View("b", linkedMapOf("dist" to 900), 8))
        val bands = listOf(RenderHead.Band(0, 250.0), RenderHead.Band(1, 400.0), RenderHead.Band(3, 700.0), RenderHead.Band(6, 2000.0), RenderHead.Band(8, null))
        val h = RenderHead(Profile.REGION, "grachten", intArrayOf(14, 12), 52.37, 4.89, 67964.45305223769, 110540.0, "e", "s", "src", views, bands)
        val back = RenderHead.fromBytes(h.toBytes())
        assertEquals(h, back)
        assertEquals(7, back.toleranceOf("a", 1e9))
        assertEquals(0, back.toleranceOf(null, 0.0)); assertEquals(1, back.toleranceOf(null, 250.0)); assertEquals(3, back.toleranceOf(null, 699.999))
        assertEquals(6, back.toleranceOf(null, 700.0)); assertEquals(8, back.toleranceOf(null, 2000.0)); assertEquals(8, back.toleranceOf(null, 1e12))
        // damage: no bands, a ladder index off the ladder
        val bytes = h.toBytes()
        val noBands = bytes.copyOf(bytes.size - 5 - 4 * 8).also { it[it.size - 1] = 0 }
        for ((what, b) in listOf("no bands" to noBands, "an index off the ladder" to bytes.copyOf().also { it[it.size - 4 * 8 - 1] = 14 })) try {
            RenderHead.fromBytes(b)
            fail(what)
        } catch (e: TileFormatException) {
        }
        assertTrue(StyleTables(linkedMapOf("keep" to Keep.TOLERANCES.toList())).toBytes().isNotEmpty())
    }
}
