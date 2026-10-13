package app.atlas.tiles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decoding hand-made tiles that exercise each rule of the spec. */
class MvtSyntheticTest {
    private fun one(type: Int, geometry: IntArray, props: Map<String, Any> = emptyMap(), id: Long? = 1): TileFeature =
        Mvt.decode(MvtBuilder().layer("l") { feature(type, geometry, props, id) }.build())["l"]!!.features.single()

    @Test
    fun polygonWithHoleAndASecondPolygon() {
        val g = one(3, Geom.rings(Geom.square(0, 0, 100), Geom.square(10, 10, 20, reverse = true), Geom.square(200, 200, 50))).geometry
        assertEquals(GeomType.POLYGON, g.type)
        assertEquals(3, g.partCount)
        assertEquals(2, g.polygonCount)
        assertArrayEquals(intArrayOf(0, 2, 3), g.polygons)
        assertTrue(g.isExterior(0))
        assertFalse(g.isExterior(1))
        assertTrue(g.isExterior(2))
        assertEquals(5, g.partSize(0))
        assertEquals(20_000L, g.ringArea2(0))
        assertEquals(-800L, g.ringArea2(1))
        assertEquals(10_000.0 - 400.0, g.polygonArea(0), 0.0)
        assertEquals(2_500.0, g.polygonArea(1), 0.0)
        assertArrayEquals(intArrayOf(0, 0, 250, 250), g.bounds())
    }

    @Test
    fun reversedWindingOfVersionOneTilesStillGroups() {
        // Exterior wound negative, hole positive: the first ring's sign decides.
        val g = one(3, Geom.rings(Geom.square(0, 0, 100, reverse = true), Geom.square(10, 10, 20), Geom.square(300, 0, 10, reverse = true))).geometry
        assertEquals(2, g.polygonCount)
        assertArrayEquals(intArrayOf(0, 2, 3), g.polygons)
    }

    @Test
    fun ringsWithoutAreaAreDropped() {
        val flat = intArrayOf(0, 0, 10, 0, 20, 0)
        val g = one(3, Geom.rings(flat, Geom.square(0, 0, 10))).geometry
        assertEquals(1, g.partCount)
        assertEquals(1, g.polygonCount)
        assertEquals(200L, g.ringArea2(0))
    }

    @Test
    fun ringsLeftOpenAreClosed() {
        // MoveTo, LineTo ×3 and no ClosePath, twice.
        val open = Geom.lines(Geom.square(0, 0, 10), Geom.square(20, 20, 10))
        val g = one(3, open).geometry
        assertEquals(2, g.polygonCount)
        g.forEachPart { _, s, e ->
            assertEquals(5, e - s)
            assertEquals(g.x(s), g.x(e - 1)); assertEquals(g.y(s), g.y(e - 1))
        }
    }

    @Test
    fun linesAndMultiLines() {
        val g = one(2, Geom.lines(intArrayOf(0, 0, 10, 10, 20, 0), intArrayOf(-5, -5, 4100, 4100))).geometry
        assertEquals(GeomType.LINE, g.type)
        assertEquals(2, g.partCount)
        assertEquals(0, g.polygonCount)
        assertArrayEquals(intArrayOf(0, 3, 5), g.parts)
        assertArrayEquals(intArrayOf(0, 0, 10, 10, 20, 0, -5, -5, 4100, 4100), g.coords)
        // A degenerate one-point line is dropped.
        assertEquals(1, one(2, Geom.lines(intArrayOf(1, 1, 2, 2)) + Geom.points(7, 7)).geometry.partCount)
    }

    @Test
    fun pointsAndMultiPoints() {
        val g = one(1, Geom.points(5, 6, 4095, 0, 100, 100)).geometry
        assertEquals(3, g.pointCount)
        assertEquals(1, g.partCount)
        assertEquals(4095, g.x(1))
        val seen = ArrayList<Int>()
        g.forEachPoint { x, y -> seen += x; seen += y }
        assertEquals(listOf(5, 6, 4095, 0, 100, 100), seen)
    }

    @Test
    fun everyValueType() {
        val f = one(
            1, Geom.points(1, 1),
            linkedMapOf("s" to "Prinsengracht", "f" to 1.5f, "d" to 2.25, "i" to 42, "u" to 5_000_000_000L, "n" to -17L, "b" to true),
            id = 1234567890123L,
        )
        assertEquals(1234567890123L, f.id)
        assertTrue(f.hasId)
        assertEquals("Prinsengracht", f.string("s"))
        assertEquals(1.5, f.double("f")!!, 0.0)
        assertEquals(2.25, f["d"])
        assertEquals(42L, f["i"])
        assertEquals(5_000_000_000L, f.long("u"))
        assertEquals(-17L, f["n"])
        assertEquals(true, f.bool("b"))
        assertNull(f["missing"])
        assertEquals(7, f.props.size)
        assertEquals(setOf("s", "f", "d", "i", "u", "n", "b"), f.props.keys)
        assertEquals("Prinsengracht", f.props.string("s"))
        assertEquals(42L, f.props.long("i"))
    }

    @Test
    fun featureWithoutIdAndBadTagsAndUnknownType() {
        val bytes = MvtBuilder().layer("l") {
            feature(1, Geom.points(1, 1), mapOf("a" to "x"), id = null, extraTags = intArrayOf(9, 0, 0, 77))
            feature(0, Geom.points(1, 1))
            feature(2, Geom.lines(intArrayOf(0, 0, 1, 1)))
        }.build()
        val layer = Mvt.decode(bytes)["l"]!!
        assertEquals(2, layer.size)
        val f = layer[0]
        assertFalse(f.hasId)
        assertEquals(0L, f.id)
        assertArrayEquals(intArrayOf(0, 0), f.tags)
        assertEquals("x", f["a"])
        assertEquals(GeomType.LINE, layer[1].type)
    }

    @Test
    fun layersExtentsAndDuplicates() {
        val bytes = MvtBuilder()
            .layer("a", extent = 512) { feature(1, Geom.points(1, 2)) }
            .layer("b") { }
            .layer("a") { feature(1, Geom.points(3, 4)); feature(1, Geom.points(5, 6)) }
            .build()
        val t = Mvt.decode(bytes)
        assertEquals(listOf("a", "b"), t.layerNames.toList())
        assertEquals(512, t["a"]!!.extent)
        assertEquals(1, t["a"]!!.size)
        assertEquals(0, t["b"]!!.size)
        assertEquals(1, t.featureCount)
        assertEquals(VectorTile.EMPTY.layers, Mvt.decode(ByteArray(0)).layers)
    }

    @Test
    fun malformedGeometryFails() {
        val lineToFirst = intArrayOf((1 shl 3) or 2, 2, 2)
        assertThrows(TileFormatException::class.java) { one(2, lineToFirst).geometry }
        assertThrows(TileFormatException::class.java) { one(2, intArrayOf((1 shl 3) or 5, 0, 0)).geometry }
        assertThrows(TileFormatException::class.java) { one(2, intArrayOf((1 shl 3) or 1, 0)).geometry }
        assertThrows(TileFormatException::class.java) { Mvt.decode(byteArrayOf(0x1a, 0x05, 0x01)) }
    }

    @Test
    fun propsAreAProperMapEvenWithRepeatedKeys() {
        // Key "a" twice: the first wins, in get and in the map alike.
        val bytes = MvtBuilder().layer("l") {
            feature(1, Geom.points(1, 1), linkedMapOf("a" to "x", "b" to "y"), extraTags = intArrayOf(0, 1))
        }.build()
        val f = Mvt.decode(bytes)["l"]!![0]
        assertEquals("x", f["a"])
        assertEquals(mapOf("a" to "x", "b" to "y"), f.props)
        assertEquals(2, f.props.size)
        assertEquals(f.props.entries.size, f.props.size)
        assertEquals(setOf("a", "b"), f.props.keys)
    }

    @Test
    fun aMalformedValueIsLeftOutOfTheProps() {
        // Value 0 has no type at all; the feature's tag pointing at it is dropped, so the map stays consistent.
        val feature = ProtoWriter().uint(1, 1).packed(2, intArrayOf(0, 0, 1, 1)).uint(3, 1).packed(4, Geom.points(1, 1))
        val layer = ProtoWriter().uint(15, 2).string(1, "l").message(2, feature).string(3, "a").string(3, "b")
            .message(4, ProtoWriter()).message(4, ProtoWriter().string(1, "y")).uint(5, 4096)
        val f = Mvt.decode(ProtoWriter().message(3, layer).toByteArray())["l"]!![0]
        assertNull(f["a"])
        assertArrayEquals(intArrayOf(1, 1), f.tags)
        assertEquals(mapOf("b" to "y"), f.props)
        assertEquals(1, f.props.size)
        assertFalse(f.props.containsKey("a"))
        assertEquals(listOf("y"), f.props.values.toList())
    }

    @Test
    fun geometryIsKeptOnceDecoded() {
        val f = one(1, Geom.points(1, 1))
        assertTrue(f.geometry === f.geometry)
        assertTrue(f.props === f.props)
    }
}
