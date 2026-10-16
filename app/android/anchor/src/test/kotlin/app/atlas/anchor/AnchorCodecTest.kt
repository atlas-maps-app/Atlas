package app.atlas.anchor

import app.atlas.mapview.svg.SvgFe
import app.atlas.render.live.Moment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The `.anchor` file (plan-gpu Appendix A): lossless, deterministic, and refused when damaged. */
class AnchorCodecTest {
    private fun westerkerk(moment: String = "sunny"): AnchorCases.Made {
        val region = AnchorCases.westerkerk()
        return AnchorCases.render(region, AnchorCases.homeView(region), Moment.Named(moment))
    }

    /** [a] and [b] hold the same anchor, field by field (floats and doubles bit for bit). */
    private fun assertSame(a: Anchor, b: Anchor) {
        val ha = a.head; val hb = b.head
        assertEquals(ha.regionId, hb.regionId); assertEquals(ha.kind, hb.kind); assertEquals(ha.moment, hb.moment); assertEquals(ha.themeKey, hb.themeKey)
        assertEquals(ha.time, hb.time); assertEquals(ha.view, hb.view)
        for ((x, y) in listOf(ha.lat0 to hb.lat0, ha.lon0 to hb.lon0, ha.kx to hb.kx, ha.kz to hb.kz, ha.radius to hb.radius, ha.radiusX to hb.radiusX,
            ha.radiusZ to hb.radiusZ, ha.groundR to hb.groundR, ha.bandTolerance to hb.bandTolerance)) assertEquals(x.toRawBits(), y.toRawBits())
        val ca = ha.camera; val cb = hb.camera
        for ((x, y) in listOf(ca.targetX to cb.targetX, ca.targetY to cb.targetY, ca.targetZ to cb.targetZ, ca.yaw to cb.yaw, ca.pitch to cb.pitch,
            ca.dist to cb.dist, ca.fov to cb.fov, ca.width to cb.width, ca.height to cb.height, ca.cx to cb.cx, ca.cy to cb.cy, ca.near to cb.near,
            ca.eyeX to cb.eyeX, ca.eyeY to cb.eyeY, ca.eyeZ to cb.eyeZ, ca.focal to cb.focal)) assertEquals(x.toRawBits(), y.toRawBits())
        assertArrayEquals(ca.axes, cb.axes, 0.0)
        assertArrayEquals(ha.complete, hb.complete, 0.0); assertArrayEquals(ha.liftCounts, hb.liftCounts); assertArrayEquals(ha.primCounts, hb.primCounts)
        val pa = a.points; val pb = b.points
        assertArrayEquals(pa.xyz.map { it.toRawBits() }.toIntArray(), pb.xyz.map { it.toRawBits() }.toIntArray())
        assertArrayEquals(pa.residual, pb.residual); assertArrayEquals(pa.lift, pb.lift)
        assertArrayEquals(pa.billboards, pb.billboards); assertArrayEquals(pa.hangs, pb.hangs); assertArrayEquals(pa.offsets, pb.offsets, 0f)
        val ra = a.prims; val rb = b.prims
        assertEquals(ra.drawn, rb.drawn)
        assertArrayEquals(ra.kind, rb.kind); assertArrayEquals(ra.flags, rb.flags); assertArrayEquals(ra.paint, rb.paint); assertArrayEquals(ra.item, rb.item)
        assertArrayEquals(ra.start, rb.start); assertArrayEquals(ra.data, rb.data)
        assertArrayEquals(a.triangles, b.triangles)
        assertArrayEquals(a.paints.kind, b.paints.kind); assertArrayEquals(a.paints.argb, b.paints.argb); assertArrayEquals(a.paints.ref, b.paints.ref)
        assertArrayEquals(a.paints.opacity, b.paints.opacity, 0f); assertArrayEquals(a.paints.transform, b.paints.transform, 0f)
        assertEquals(a.gradients.size, b.gradients.size)
        for ((g, h) in a.gradients.zip(b.gradients)) {
            assertEquals(g.id, h.id); assertEquals(g.radial, h.radial); assertEquals(g.units, h.units); assertEquals(g.spread, h.spread)
            assertArrayEquals(g.coords, h.coords, 0f); assertArrayEquals(g.offsets, h.offsets, 0f); assertArrayEquals(g.colors, h.colors)
            assertEquals(g.transform?.toList(), h.transform?.toList())
        }
        assertEquals(a.plan.nodes.size, b.plan.nodes.size)
        for ((m, n) in a.plan.nodes.zip(b.plan.nodes)) {
            assertEquals(m.javaClass, n.javaClass); assertEquals(m.end, n.end)
            when (m) {
                is PlanNode.Layer -> assertEquals(m.layer, (n as PlanNode.Layer).layer)
                is PlanNode.Run -> { n as PlanNode.Run; assertEquals(m.first, n.first); assertEquals(m.last, n.last) }
                is PlanNode.Group -> {
                    n as PlanNode.Group
                    assertEquals(m.opacity, n.opacity, 0f); assertEquals(m.blend, n.blend); assertEquals(m.filter, n.filter); assertEquals(m.clip, n.clip)
                    assertEquals(m.sprite, n.sprite); assertEquals(m.screenFixed, n.screenFixed); assertEquals(m.painter, n.painter)
                    assertEquals(m.filterBox?.toList(), n.filterBox?.toList())
                }
            }
        }
        assertEquals(a.plan.clips.map { listOf(it.id, it.units, it.first, it.last, it.parent) }, b.plan.clips.map { listOf(it.id, it.units, it.first, it.last, it.parent) })
        assertEquals(a.filters.size, b.filters.size)
        for ((f, g) in a.filters.zip(b.filters)) {
            assertEquals(f.generatorOnly, g.generatorOnly); assertEquals(f.filter.id, g.filter.id); assertEquals(f.filter.units, g.filter.units)
            assertArrayEquals(f.filter.region, g.filter.region, 0f); assertEquals(f.filter.linearRgb, g.filter.linearRgb)
            assertEquals(f.filter.primitives.size, g.filter.primitives.size)
            for ((p, q) in f.filter.primitives.zip(g.filter.primitives)) {
                assertEquals(p.javaClass, q.javaClass); assertEquals(p.input, q.input); assertEquals(p.input2, q.input2); assertEquals(p.result, q.result)
                assertEquals(p.linearRgb, q.linearRgb)
                if (p is SvgFe.ColorMatrix) assertArrayEquals(p.matrix, (q as SvgFe.ColorMatrix).matrix, 0f)
                if (p is SvgFe.Turbulence) { q as SvgFe.Turbulence; assertEquals(p.baseX, q.baseX, 0f); assertEquals(p.seed, q.seed, 0f); assertEquals(p.octaves, q.octaves) }
            }
        }
        val ia = a.items; val ib = b.items
        assertArrayEquals(ia.first, ib.first); assertArrayEquals(ia.last, ib.last); assertArrayEquals(ia.ownerType, ib.ownerType); assertArrayEquals(ia.ownerId, ib.ownerId)
        assertArrayEquals(ia.kind, ib.kind); assertEquals(ia.kinds, ib.kinds)
        assertArrayEquals(ia.far.map { it.toRawBits() }.toLongArray(), ib.far.map { it.toRawBits() }.toLongArray())
        assertArrayEquals(ia.sort.map { it.toRawBits() }.toLongArray(), ib.sort.map { it.toRawBits() }.toLongArray())
        assertArrayEquals(ia.sortOffset, ib.sortOffset, 0.0); assertArrayEquals(ia.depth, ib.depth, 0.0)
        assertEquals(a.texts.size, b.texts.size)
        for ((t, u) in a.texts.zip(b.texts)) {
            assertEquals(listOf(t.text, t.family, t.weight, t.italic, t.anchor, t.paint), listOf(u.text, u.family, u.weight, u.italic, u.anchor, u.paint))
            assertEquals(listOf(t.size, t.letterSpacing, t.x, t.y, t.opacity, t.groundHeight), listOf(u.size, u.letterSpacing, u.x, u.y, u.opacity, u.groundHeight))
            assertArrayEquals(t.transform, u.transform, 0f)
        }
        assertEquals(a.patterns.map { it.id to it.svg }, b.patterns.map { it.id to it.svg })
        assertEquals(a.image?.ext, b.image?.ext); assertEquals(a.image?.clip, b.image?.clip); assertArrayEquals(a.image?.data, b.image?.data)
        assertEquals(a.screen.horizon.toRawBits(), b.screen.horizon.toRawBits()); assertEquals(a.screen.svg, b.screen.svg); assertEquals(a.screen.spec, b.screen.spec)
        assertEquals(a.engineVersion, b.engineVersion); assertArrayEquals(a.sourceHash, b.sourceHash)
    }

    @Test
    fun `an anchor reads back bit for bit, and writes the same bytes again`() {
        for (m in listOf("sunny", "night")) {
            val a = AnchorCases.anchor(westerkerk(m))
            val bytes = AnchorCodec.encode(a)
            val b = AnchorCodec.decode(bytes)
            assertSame(a, b)
            assertArrayEquals("re-encoding gives the same bytes", bytes, AnchorCodec.encode(b))
            println("Westerkerk $m: ${bytes.size} bytes on disk, ${a.bytes()} in memory")
        }
    }

    @Test
    fun `the same render compiles to the same bytes, and a fresh render of the same view too`() {
        val made = westerkerk()
        val one = AnchorCodec.encode(AnchorCases.anchor(made))
        val two = AnchorCodec.encode(AnchorCases.anchor(made))
        assertArrayEquals("one render compiled twice", one, two)
        val again = AnchorCodec.encode(AnchorCases.anchor(westerkerk()))
        assertArrayEquals("a fresh render of the same view", one, again)
    }

    @Test
    fun `the image, patterns and every section survive, and the header says what it is`() {
        val a = AnchorCases.anchor(westerkerk())
        // a relief raster and a pattern of the anchor's own, so every section carries something
        val img = AnchorImage("jpg", ByteArray(5000) { (it * 31 % 251).toByte() }, "<path d=\"M0 0L10 0L10 10Z\"/>")
        val b = Anchor(
            a.head, a.points, a.prims, a.triangles, a.paints, a.gradients, a.plan, a.filters, a.items, a.texts,
            a.patterns + AnchorPattern("p", "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"9\" height=\"9\"><path d=\"M0 0L9 9\" stroke=\"#000\"/></svg>"),
            img, a.screen, "engine-7", ByteArray(32) { it.toByte() },
        )
        val bytes = AnchorCodec.encode(b)
        assertEquals("ATLA", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(AnchorCodec.VERSION, (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8))
        assertSame(b, AnchorCodec.decode(bytes))
    }

    @Test
    fun `a damaged file is refused`() {
        val bytes = AnchorCodec.encode(AnchorCases.anchor(westerkerk()))
        for (at in listOf(0, 5, bytes.size / 2, bytes.size - 3)) {
            val bad = bytes.copyOf()
            bad[at] = (bad[at].toInt() xor 0x5A).toByte()
            try {
                AnchorCodec.decode(bad)
                fail("a flipped byte at $at was not noticed")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message != null)
            }
        }
        try {
            AnchorCodec.decode(bytes.copyOf(bytes.size - 10))
            fail("a cut file was read")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message != null)
        }
    }
}
