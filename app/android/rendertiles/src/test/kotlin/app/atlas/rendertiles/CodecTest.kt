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
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.RenderTile
import app.atlas.rendertiles.model.Schema
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** The payload's codecs, the clipper, the grid and the keep codes on cases chosen to break them. */
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
}
