package app.atlas.anchor

import app.atlas.mapview.svg.SvgBlend
import app.atlas.mapview.svg.SvgFe
import app.atlas.mapview.svg.SvgFilter
import app.atlas.mapview.svg.SvgGradient
import app.atlas.mapview.svg.SvgUnits
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The `.anchor` file (plan-gpu Appendix A): little-endian; the magic `ATLA`, the format version (u16), the engine
 * version (u16 length and UTF-8) and the source hash (32 bytes); then the sections in a fixed order, each
 * `tag u32 · raw length u32 · stored length u32 · crc32 u32 · deflated data`. The CRC is the raw data's, so two files
 * deflated by different zlib builds still compare by content.
 *
 * Geometry is delta-coded before it is deflated: point coordinates XOR-ed with the previous one's bits and split into
 * byte planes, point and triangle indices as zigzag varints of their difference from the last. The output is
 * deterministic, and decoding is lossless: [decode] of [encode] equals the anchor bit for bit.
 */
object AnchorCodec {
    const val VERSION = 1
    private const val MAGIC = 0x414C5441 // "ATLA" little-endian
    private val ORDER = listOf("HEAD", "PNTS", "RESI", "LIFT", "PRIM", "TIDX", "PANT", "GRAD", "LAYR", "FILT", "ITEM", "TEXT", "PATT", "IMAG", "SCRN")

    private fun tag(s: String): Int = (s[0].code) or (s[1].code shl 8) or (s[2].code shl 16) or (s[3].code shl 24)

    /** The anchor as a `.anchor` file. */
    fun encode(a: Anchor, level: Int = 6): ByteArray {
        val out = Sink(1 shl 16)
        out.i32(MAGIC); out.u16(VERSION); out.str16(a.engineVersion); out.bytes(a.sourceHash)
        val sections = linkedMapOf(
            "HEAD" to head(a), "PNTS" to points(a), "RESI" to residuals(a), "LIFT" to lift(a), "PRIM" to prims(a), "TIDX" to triangles(a),
            "PANT" to paints(a), "GRAD" to gradients(a), "LAYR" to plan(a), "FILT" to filters(a), "ITEM" to items(a), "TEXT" to texts(a),
            "PATT" to patterns(a), "IMAG" to image(a), "SCRN" to screen(a),
        )
        val deflater = Deflater(level, true)
        try {
            for (name in ORDER) {
                val raw = sections.getValue(name)
                deflater.reset()
                deflater.setInput(raw)
                deflater.finish()
                val buf = ByteArrayOutputStream(raw.size / 2 + 64)
                val chunk = ByteArray(1 shl 16)
                while (!deflater.finished()) {
                    val n = deflater.deflate(chunk)
                    buf.write(chunk, 0, n)
                }
                val stored = buf.toByteArray()
                val crc = CRC32().also { it.update(raw) }
                out.i32(tag(name)); out.i32(raw.size); out.i32(stored.size); out.i32(crc.value.toInt())
                out.bytes(stored)
            }
        } finally {
            deflater.end()
        }
        return out.toByteArray()
    }

    /** The anchor of a `.anchor` file; fails on a wrong magic, version, length or CRC. */
    fun decode(bytes: ByteArray): Anchor {
        val inp = Source(bytes)
        require(inp.i32() == MAGIC) { "not an anchor file" }
        val version = inp.u16()
        require(version == VERSION) { "anchor format $version, not $VERSION" }
        val engine = inp.str16()
        val hash = inp.bytes(32)
        val raw = HashMap<String, ByteArray>()
        val inflater = Inflater(true)
        try {
            for (name in ORDER) {
                val t = inp.i32()
                require(t == tag(name)) { "section $name expected" }
                val rawLen = inp.i32(); val storedLen = inp.i32(); val crc = inp.i32()
                val stored = inp.bytes(storedLen)
                inflater.reset()
                inflater.setInput(stored)
                val data = ByteArray(rawLen)
                var n = 0
                while (n < rawLen) {
                    val k = try {
                        inflater.inflate(data, n, rawLen - n)
                    } catch (e: java.util.zip.DataFormatException) {
                        throw IllegalArgumentException("section $name: ${e.message}", e)
                    }
                    if (k == 0 && (inflater.finished() || inflater.needsInput())) break
                    n += k
                }
                require(n == rawLen) { "section $name: $n of $rawLen bytes" }
                require(CRC32().also { it.update(data) }.value.toInt() == crc) { "section $name: bad CRC" }
                raw[name] = data
            }
        } finally {
            inflater.end()
        }
        val head = readHead(raw.getValue("HEAD"))
        val points = readPoints(raw.getValue("PNTS"), raw.getValue("RESI"), raw.getValue("LIFT"))
        val (prims, tris) = readPrims(raw.getValue("PRIM")) to readTriangles(raw.getValue("TIDX"))
        return Anchor(
            head, points, prims, tris, readPaints(raw.getValue("PANT")), readGradients(raw.getValue("GRAD")), readPlan(raw.getValue("LAYR")),
            readFilters(raw.getValue("FILT")), readItems(raw.getValue("ITEM")), readTexts(raw.getValue("TEXT")), readPatterns(raw.getValue("PATT")),
            readImage(raw.getValue("IMAG")), readScreen(raw.getValue("SCRN")), engine, hash,
        )
    }

    // ------------------------------------------------------------------ HEAD

    private fun head(a: Anchor): ByteArray {
        val h = a.head; val c = h.camera
        val m = linkedMapOf<String, Any?>(
            "region" to h.regionId,
            "frame" to listOf(h.lat0, h.lon0, h.kx, h.kz),
            "kind" to h.kind.name.lowercase(),
            "camera" to linkedMapOf(
                "target" to listOf(c.targetX, c.targetY, c.targetZ), "yaw" to c.yaw, "pitch" to c.pitch, "dist" to c.dist, "fov" to c.fov,
                "W" to c.width, "H" to c.height, "cx" to c.cx, "cy" to c.cy, "near" to c.near,
                "eye" to listOf(c.eyeX, c.eyeY, c.eyeZ), "axes" to c.axes.toList(), "focal" to c.focal,
            ),
            "complete" to h.complete.toList(),
            "RAD" to h.radius, "TX" to h.radiusX, "TZ" to h.radiusZ, "ground_r" to h.groundR,
            "moment" to h.moment, "theme" to h.themeKey, "time" to h.time,
            "band" to h.bandTolerance, "view" to h.view,
            "lift" to h.liftCounts.toList(), "prims" to h.primCounts.toList(),
        )
        return Json.write(m).toByteArray(Charsets.UTF_8)
    }

    @Suppress("UNCHECKED_CAST")
    private fun readHead(b: ByteArray): AnchorHead {
        val m = Json.read(String(b, Charsets.UTF_8)) as Map<String, Any?>
        fun d(v: Any?): Double = (v as Number).toDouble()
        fun dl(v: Any?): DoubleArray = (v as List<Any?>).map { d(it) }.toDoubleArray()
        val f = dl(m["frame"])
        val c = m["camera"] as Map<String, Any?>
        val t = dl(c["target"]); val eye = dl(c["eye"])
        val cam = AnchorCamera(
            t[0], t[1], t[2], d(c["yaw"]), d(c["pitch"]), d(c["dist"]), d(c["fov"]), d(c["W"]), d(c["H"]), d(c["cx"]), d(c["cy"]), d(c["near"]),
            eye[0], eye[1], eye[2], dl(c["axes"]), d(c["focal"]),
        )
        return AnchorHead(
            m["region"] as String, f[0], f[1], f[2], f[3], AnchorKind.valueOf((m["kind"] as String).uppercase()), cam, dl(m["complete"]),
            d(m["RAD"]), d(m["TX"]), d(m["TZ"]), d(m["ground_r"]), m["moment"] as String, m["theme"] as String, m["time"] as String?,
            d(m["band"]), m["view"] as String?, (m["lift"] as List<Any?>).map { (it as Number).toInt() }.toIntArray(),
            (m["prims"] as List<Any?>).map { (it as Number).toInt() }.toIntArray(),
        )
    }

    // ------------------------------------------------------------------ points

    private fun points(a: Anchor): ByteArray {
        val p = a.points
        val n = p.size
        val s = Sink(n * 12 + 16)
        s.i32(n)
        // per coordinate, the bits XOR-ed with the previous point's, in four byte planes
        val bits = IntArray(n)
        for (k in 0 until 3) {
            var prev = 0
            for (i in 0 until n) { val v = java.lang.Float.floatToRawIntBits(p.xyz[i * 3 + k]); bits[i] = v xor prev; prev = v }
            for (plane in 0 until 4) for (i in 0 until n) s.u8(bits[i] ushr (plane * 8))
        }
        return s.toByteArray()
    }

    private fun residuals(a: Anchor): ByteArray {
        val r = a.points.residual
        val s = Sink(r.size + 16)
        s.i32(r.size / 2)
        for (v in r) s.zig(v.toLong())
        return s.toByteArray()
    }

    private fun lift(a: Anchor): ByteArray {
        val p = a.points
        val s = Sink(p.size + p.billboards.size * 12 + 16)
        s.i32(p.size)
        s.bytes(p.lift)
        s.var32(p.billboards.size)
        var last = 0
        for (k in p.billboards.indices) {
            s.var32(p.billboards[k] - last); last = p.billboards[k]
            s.zig((p.hangs[k] - p.billboards[k]).toLong())
            s.f32(p.offsets[k * 2]); s.f32(p.offsets[k * 2 + 1])
        }
        return s.toByteArray()
    }

    private fun readPoints(pb: ByteArray, rb: ByteArray, lb: ByteArray): AnchorPoints {
        val ps = Source(pb)
        val n = ps.i32()
        val xyz = FloatArray(n * 3)
        val bits = IntArray(n)
        for (k in 0 until 3) {
            bits.fill(0)
            for (plane in 0 until 4) for (i in 0 until n) bits[i] = bits[i] or (ps.u8() shl (plane * 8))
            var prev = 0
            for (i in 0 until n) { val v = bits[i] xor prev; xyz[i * 3 + k] = java.lang.Float.intBitsToFloat(v); prev = v }
        }
        val rs = Source(rb)
        val rn = rs.i32()
        val res = ShortArray(rn * 2) { rs.unzig().toInt().toShort() }
        val ls = Source(lb)
        val ln = ls.i32()
        val lift = ls.bytes(ln)
        val m = ls.var32()
        val bp = IntArray(m); val bh = IntArray(m); val bo = FloatArray(m * 2)
        var last = 0
        for (k in 0 until m) {
            last += ls.var32(); bp[k] = last
            bh[k] = (bp[k] + ls.unzig()).toInt()
            bo[k * 2] = ls.f32(); bo[k * 2 + 1] = ls.f32()
        }
        return AnchorPoints(xyz, res, lift, bp, bh, bo)
    }

    // ------------------------------------------------------------------ primitives

    private class Refs {
        var last = 0
        fun put(s: Sink, p: Int) {
            val v = p + 1
            s.zig((v - last).toLong())
            if (p >= 0) last = v
        }

        fun get(s: Source): Int {
            val v = last + s.unzig().toInt()
            val p = v - 1
            if (p >= 0) last = v
            return p
        }
    }

    private fun prims(a: Anchor): ByteArray {
        val p = a.prims
        val n = p.size
        val s = Sink(n * 16 + 16)
        s.i32(n); s.i32(p.drawn)
        s.bytes(p.kind); s.bytes(p.flags)
        var last = 0
        for (i in 0 until n) { s.zig((p.paint[i] - last).toLong()); last = p.paint[i] }
        last = 0
        for (i in 0 until n) { s.zig((p.item[i] - last).toLong()); last = p.item[i] }
        val refs = Refs()
        var lastTri = 0
        for (i in 0 until n) {
            val o = p.start[i]
            val d = p.data
            when (p.kind[i]) {
                PrimKind.FACE -> { val c = d[o]; s.u8(c); s.u8(d[o + 1]); for (k in 2..c + 1) refs.put(s, d[o + k]) }
                PrimKind.TRI, PrimKind.STENCIL -> { s.zig((d[o] - lastTri).toLong()); lastTri = d[o] + d[o + 1]; s.var32(d[o + 1]) }
                PrimKind.SEG -> { for (k in 0 until 4) refs.put(s, d[o + k]); s.i32(d[o + 4]); s.u8(d[o + 5]); s.i32(d[o + 6]) }
                PrimKind.JOIN -> { refs.put(s, d[o]); s.i32(d[o + 1]) }
                PrimKind.ELL -> { refs.put(s, d[o]); for (k in 1..4) s.i32(d[o + k]) }
                PrimKind.TILE -> { s.var32(d[o]); for (k in 1..4) refs.put(s, d[o + k]) }
                else -> error("primitive kind ${p.kind[i]}")
            }
        }
        return s.toByteArray()
    }

    private fun readPrims(b: ByteArray): AnchorPrims {
        val s = Source(b)
        val n = s.i32(); val drawn = s.i32()
        val kind = s.bytes(n); val flags = s.bytes(n)
        val paint = IntArray(n); val item = IntArray(n)
        var last = 0
        for (i in 0 until n) { last += s.unzig().toInt(); paint[i] = last }
        last = 0
        for (i in 0 until n) { last += s.unzig().toInt(); item[i] = last }
        val start = IntArray(n + 1)
        val data = IntList(n * 5)
        val refs = Refs()
        var lastTri = 0
        for (i in 0 until n) {
            start[i] = data.size
            when (kind[i]) {
                PrimKind.FACE -> { val c = s.u8(); data.add(c); data.add(s.u8()); for (k in 1..c) data.add(refs.get(s)) }
                PrimKind.TRI, PrimKind.STENCIL -> { val f = lastTri + s.unzig().toInt(); val c = s.var32(); data.add(f); data.add(c); lastTri = f + c }
                PrimKind.SEG -> { for (k in 0 until 4) data.add(refs.get(s)); data.add(s.i32()); data.add(s.u8()); data.add(s.i32()) }
                PrimKind.JOIN -> { data.add(refs.get(s)); data.add(s.i32()) }
                PrimKind.ELL -> { data.add(refs.get(s)); for (k in 1..4) data.add(s.i32()) }
                PrimKind.TILE -> { data.add(s.var32()); for (k in 1..4) data.add(refs.get(s)) }
                else -> error("primitive kind ${kind[i]}")
            }
        }
        start[n] = data.size
        return AnchorPrims(kind, flags, paint, item, start, data.toArray(), drawn)
    }

    private fun triangles(a: Anchor): ByteArray {
        val t = a.triangles
        val s = Sink(t.size * 2 + 16)
        s.i32(t.size)
        var last = 0
        for (v in t) { s.zig((v - last).toLong()); last = v }
        return s.toByteArray()
    }

    private fun readTriangles(b: ByteArray): IntArray {
        val s = Source(b)
        val n = s.i32()
        var last = 0
        return IntArray(n) { last += s.unzig().toInt(); last }
    }

    // ------------------------------------------------------------------ paints and gradients

    private fun paints(a: Anchor): ByteArray {
        val p = a.paints
        val s = Sink(p.size * 40 + 16)
        s.i32(p.size)
        for (i in 0 until p.size) {
            s.u8(p.kind[i].toInt()); rgba(s, p.argb[i]); s.var32(p.ref[i] + 1); s.f32(p.opacity[i])
            for (k in 0 until 6) s.f32(p.transform[i * 6 + k])
        }
        return s.toByteArray()
    }

    private fun readPaints(b: ByteArray): AnchorPaints {
        val s = Source(b)
        val n = s.i32()
        val kind = ByteArray(n); val argb = IntArray(n); val ref = IntArray(n); val op = FloatArray(n); val t = FloatArray(n * 6)
        for (i in 0 until n) {
            kind[i] = s.u8().toByte(); argb[i] = readRgba(s); ref[i] = s.var32() - 1; op[i] = s.f32()
            for (k in 0 until 6) t[i * 6 + k] = s.f32()
        }
        return AnchorPaints(kind, argb, ref, op, t)
    }

    /** An ARGB int as the bytes R, G, B, A. */
    private fun rgba(s: Sink, c: Int) { s.u8(c ushr 16); s.u8(c ushr 8); s.u8(c); s.u8(c ushr 24) }

    private fun readRgba(s: Source): Int { val r = s.u8(); val g = s.u8(); val b = s.u8(); val a = s.u8(); return (a shl 24) or (r shl 16) or (g shl 8) or b }

    private fun gradients(a: Anchor): ByteArray {
        val s = Sink(1024)
        s.var32(a.gradients.size)
        for (g in a.gradients) {
            s.str(g.id); s.u8(if (g.radial) 1 else 0); s.u8(g.units.ordinal); s.u8(g.spread.ordinal)
            s.var32(g.coords.size); for (v in g.coords) s.f32(v)
            affine(s, g.transform)
            s.var32(g.offsets.size)
            for (k in g.offsets.indices) { s.f32(g.offsets[k]); rgba(s, g.colors[k]) }
        }
        return s.toByteArray()
    }

    private fun readGradients(b: ByteArray): List<SvgGradient> {
        val s = Source(b)
        return List(s.var32()) {
            val id = s.str(); val radial = s.u8() == 1; val units = SvgUnits.entries[s.u8()]; val spread = SvgGradient.Spread.entries[s.u8()]
            val coords = FloatArray(s.var32()) { s.f32() }
            val t = readAffine(s)
            val n = s.var32()
            val off = FloatArray(n); val col = IntArray(n)
            for (k in 0 until n) { off[k] = s.f32(); col[k] = readRgba(s) }
            SvgGradient(id, radial, coords, units, t, spread, off, col)
        }
    }

    private fun affine(s: Sink, t: FloatArray?) {
        if (t == null) { s.u8(0); return }
        s.u8(1); for (v in t) s.f32(v)
    }

    private fun readAffine(s: Source): FloatArray? = if (s.u8() == 0) null else FloatArray(6) { s.f32() }

    // ------------------------------------------------------------------ the plan and filters

    private fun plan(a: Anchor): ByteArray {
        val s = Sink(4096)
        s.var32(a.plan.nodes.size)
        for (node in a.plan.nodes) {
            when (node) {
                is PlanNode.Layer -> { s.u8(0); s.u8(node.layer.ordinal); s.var32(node.end) }
                is PlanNode.Group -> {
                    s.u8(1); s.f32(node.opacity); s.u8(node.blend.ordinal); s.var32(node.filter + 1)
                    val box = node.filterBox
                    if (box == null) s.u8(0) else { s.u8(1); for (v in box) s.f64(v) }
                    s.var32(node.clip + 1); s.u8(if (node.sprite) 1 else 0); s.u8(if (node.screenFixed) 1 else 0); s.var32(node.painter); s.var32(node.end)
                }
                is PlanNode.Run -> { s.u8(2); s.var32(node.first); s.var32(node.last); s.var32(node.end) }
            }
        }
        s.var32(a.plan.clips.size)
        for (c in a.plan.clips) { s.str(c.id); s.u8(c.units.ordinal); s.var32(c.first); s.var32(c.last); s.var32(c.parent + 1) }
        return s.toByteArray()
    }

    private fun readPlan(b: ByteArray): AnchorPlan {
        val s = Source(b)
        val nodes = List(s.var32()) {
            when (s.u8()) {
                0 -> PlanNode.Layer(LayerKind.entries[s.u8()], s.var32())
                1 -> {
                    val op = s.f32(); val blend = SvgBlend.entries[s.u8()]; val filter = s.var32() - 1
                    val box = if (s.u8() == 1) DoubleArray(4) { s.f64() } else null
                    val clip = s.var32() - 1; val sprite = s.u8() == 1; val screen = s.u8() == 1; val painter = s.var32(); val end = s.var32()
                    PlanNode.Group(op, blend, filter, box, clip, sprite, screen, painter, end)
                }
                else -> PlanNode.Run(s.var32(), s.var32(), s.var32())
            }
        }
        val clips = List(s.var32()) { AnchorClip(s.str(), SvgUnits.entries[s.u8()], s.var32(), s.var32(), s.var32() - 1) }
        return AnchorPlan(nodes, clips)
    }

    private fun filters(a: Anchor): ByteArray {
        val s = Sink(1024)
        s.var32(a.filters.size)
        for (af in a.filters) {
            val f = af.filter
            s.u8(if (af.generatorOnly) 1 else 0)
            s.str(f.id); s.u8(f.units.ordinal); for (v in f.region) s.f32(v); s.u8(if (f.linearRgb) 1 else 0)
            s.var32(f.primitives.size)
            for (p in f.primitives) {
                s.optStr(p.input); s.optStr(p.input2); s.optStr(p.result); s.u8(if (p.linearRgb) 1 else 0)
                when (p) {
                    is SvgFe.Turbulence -> { s.u8(0); s.f32(p.baseX); s.f32(p.baseY); s.var32(p.octaves); s.f32(p.seed); s.u8(if (p.fractal) 1 else 0); s.u8(if (p.stitch) 1 else 0) }
                    is SvgFe.DisplacementMap -> { s.u8(1); s.f32(p.scale); s.u8(p.xChannel); s.u8(p.yChannel) }
                    is SvgFe.ColorMatrix -> { s.u8(2); s.var32(p.matrix.size); for (v in p.matrix) s.f32(v) }
                    is SvgFe.Composite -> { s.u8(3); s.u8(p.operator.ordinal); s.f32(p.k1); s.f32(p.k2); s.f32(p.k3); s.f32(p.k4) }
                    is SvgFe.GaussianBlur -> { s.u8(4); s.f32(p.deviationX); s.f32(p.deviationY) }
                    is SvgFe.Offset -> { s.u8(5); s.f32(p.dx); s.f32(p.dy) }
                    is SvgFe.Flood -> { s.u8(6); s.i32(p.argb) }
                    is SvgFe.Merge -> { s.u8(7); s.var32(p.inputs.size); for (i in p.inputs) s.optStr(i) }
                }
            }
        }
        return s.toByteArray()
    }

    private fun readFilters(b: ByteArray): List<AnchorFilter> {
        val s = Source(b)
        return List(s.var32()) {
            val gen = s.u8() == 1
            val id = s.str(); val units = SvgUnits.entries[s.u8()]; val region = FloatArray(4) { s.f32() }; val lin = s.u8() == 1
            val prims = List(s.var32()) {
                val input = s.optStr(); val input2 = s.optStr(); val result = s.optStr(); val plin = s.u8() == 1
                val p: SvgFe = when (s.u8()) {
                    0 -> SvgFe.Turbulence(s.f32(), s.f32(), s.var32(), s.f32(), s.u8() == 1, s.u8() == 1)
                    1 -> SvgFe.DisplacementMap(s.f32(), s.u8(), s.u8())
                    2 -> SvgFe.ColorMatrix(FloatArray(s.var32()) { s.f32() })
                    3 -> SvgFe.Composite(SvgFe.Composite.Operator.entries[s.u8()], s.f32(), s.f32(), s.f32(), s.f32())
                    4 -> SvgFe.GaussianBlur(s.f32(), s.f32())
                    5 -> SvgFe.Offset(s.f32(), s.f32())
                    6 -> SvgFe.Flood(s.i32())
                    else -> SvgFe.Merge(List(s.var32()) { s.optStr() })
                }
                p.input = input; p.input2 = input2; p.result = result; p.linearRgb = plin
                p
            }
            AnchorFilter(SvgFilter(id, units, region, prims, lin), gen)
        }
    }

    // ------------------------------------------------------------------ items, text, patterns, image, screen

    private fun items(a: Anchor): ByteArray {
        val it = a.items
        val n = it.size
        val s = Sink(n * 90 + 64)
        s.i32(n)
        s.var32(it.kinds.size); for (k in it.kinds) s.str(k)
        var last = 0
        for (i in 0 until n) {
            s.zig((it.first[i] - last).toLong()); s.var32(it.last[i] - it.first[i]); last = it.last[i]
            s.u8(it.ownerType[i].toInt()); s.zig(it.ownerId[i]); s.var32(it.kind[i])
            for (k in 0 until 4) s.f64(it.far[i * 4 + k])
            for (k in 0 until 3) s.f64(it.sort[i * 3 + k])
            s.f64(it.sortOffset[i]); s.f64(it.depth[i])
        }
        return s.toByteArray()
    }

    private fun readItems(b: ByteArray): AnchorItems {
        val s = Source(b)
        val n = s.i32()
        val kinds = List(s.var32()) { s.str() }
        val first = IntArray(n); val lastA = IntArray(n); val ot = ByteArray(n); val oid = LongArray(n); val kind = IntArray(n)
        val far = DoubleArray(n * 4); val sort = DoubleArray(n * 3); val off = DoubleArray(n); val depth = DoubleArray(n)
        var last = 0
        for (i in 0 until n) {
            first[i] = last + s.unzig().toInt(); lastA[i] = first[i] + s.var32(); last = lastA[i]
            ot[i] = s.u8().toByte(); oid[i] = s.unzig(); kind[i] = s.var32()
            for (k in 0 until 4) far[i * 4 + k] = s.f64()
            for (k in 0 until 3) sort[i * 3 + k] = s.f64()
            off[i] = s.f64(); depth[i] = s.f64()
        }
        return AnchorItems(first, lastA, ot, oid, kind, kinds, far, sort, off, depth)
    }

    private fun texts(a: Anchor): ByteArray {
        val s = Sink(1024)
        s.var32(a.texts.size)
        for (t in a.texts) {
            s.str(t.text); s.str(t.family); s.f32(t.size); s.var32(t.weight); s.u8(if (t.italic) 1 else 0); s.f32(t.letterSpacing); s.u8(t.anchor)
            s.f32(t.x); s.f32(t.y); s.var32(t.paint + 1); s.f32(t.opacity); for (v in t.transform) s.f32(v); s.f32(t.groundHeight)
        }
        return s.toByteArray()
    }

    private fun readTexts(b: ByteArray): List<AnchorText> {
        val s = Source(b)
        return List(s.var32()) {
            AnchorText(
                s.str(), s.str(), s.f32(), s.var32(), s.u8() == 1, s.f32(), s.u8(), s.f32(), s.f32(), s.var32() - 1, s.f32(),
                FloatArray(6) { s.f32() }, s.f32(),
            )
        }
    }

    private fun patterns(a: Anchor): ByteArray {
        val s = Sink(1024)
        s.var32(a.patterns.size)
        for (p in a.patterns) { s.str(p.id); s.str(p.svg) }
        return s.toByteArray()
    }

    private fun readPatterns(b: ByteArray): List<AnchorPattern> {
        val s = Source(b)
        return List(s.var32()) { AnchorPattern(s.str(), s.str()) }
    }

    private fun image(a: Anchor): ByteArray {
        val im = a.image
        val s = Sink((im?.data?.size ?: 0) + 64)
        if (im == null) { s.u8(0); return s.toByteArray() }
        s.u8(1); s.str(im.ext); s.var32(im.data.size); s.bytes(im.data); s.optStr(im.clip)
        return s.toByteArray()
    }

    private fun readImage(b: ByteArray): AnchorImage? {
        val s = Source(b)
        if (s.u8() == 0) return null
        val ext = s.str(); val data = s.bytes(s.var32())
        return AnchorImage(ext, data, s.optStr())
    }

    private fun screen(a: Anchor): ByteArray {
        val s = Sink(a.screen.svg.length + a.screen.spec.length + 64)
        s.f64(a.screen.horizon); s.str(a.screen.svg); s.str(a.screen.spec)
        return s.toByteArray()
    }

    private fun readScreen(b: ByteArray): AnchorScreen {
        val s = Source(b)
        return AnchorScreen(s.f64(), s.str(), s.str())
    }
}

/** A growing little-endian byte buffer. */
internal class Sink(capacity: Int) {
    private var b = ByteArray(maxOf(16, capacity))
    private var n = 0

    private fun room(k: Int) { if (n + k > b.size) b = b.copyOf(maxOf(b.size * 2, n + k)) }

    fun u8(v: Int) { room(1); b[n++] = v.toByte() }
    fun u16(v: Int) { room(2); b[n++] = v.toByte(); b[n++] = (v ushr 8).toByte() }
    fun i32(v: Int) { room(4); b[n++] = v.toByte(); b[n++] = (v ushr 8).toByte(); b[n++] = (v ushr 16).toByte(); b[n++] = (v ushr 24).toByte() }
    fun i64(v: Long) { i32(v.toInt()); i32((v ushr 32).toInt()) }
    fun f32(v: Float) = i32(java.lang.Float.floatToRawIntBits(v))
    fun f64(v: Double) = i64(java.lang.Double.doubleToRawLongBits(v))
    fun bytes(v: ByteArray) { room(v.size); System.arraycopy(v, 0, b, n, v.size); n += v.size }

    fun var32(v: Int) = var64(v.toLong() and 0xFFFFFFFFL)

    fun var64(v0: Long) {
        var v = v0
        room(10)
        while (v and 0x7FL.inv() != 0L) { b[n++] = ((v and 0x7F) or 0x80).toByte(); v = v ushr 7 }
        b[n++] = v.toByte()
    }

    /** A signed value as a zigzag varint. */
    fun zig(v: Long) = var64((v shl 1) xor (v shr 63))

    fun str(s: String) { val u = s.toByteArray(Charsets.UTF_8); var32(u.size); bytes(u) }
    fun str16(s: String) { val u = s.toByteArray(Charsets.UTF_8); require(u.size < 65536); u16(u.size); bytes(u) }
    fun optStr(s: String?) { if (s == null) u8(0) else { u8(1); str(s) } }

    fun toByteArray(): ByteArray = b.copyOf(n)
}

/** Reads what [Sink] writes. */
internal class Source(private val b: ByteArray) {
    private var n = 0

    private fun need(k: Int) { if (n + k > b.size) throw IllegalArgumentException("anchor data ends early") }

    fun u8(): Int { need(1); return b[n++].toInt() and 0xFF }
    fun u16(): Int { need(2); val v = (b[n].toInt() and 0xFF) or ((b[n + 1].toInt() and 0xFF) shl 8); n += 2; return v }
    fun i32(): Int {
        need(4)
        val v = (b[n].toInt() and 0xFF) or ((b[n + 1].toInt() and 0xFF) shl 8) or ((b[n + 2].toInt() and 0xFF) shl 16) or ((b[n + 3].toInt() and 0xFF) shl 24)
        n += 4
        return v
    }
    fun i64(): Long { val lo = i32().toLong() and 0xFFFFFFFFL; val hi = i32().toLong(); return lo or (hi shl 32) }
    fun f32(): Float = java.lang.Float.intBitsToFloat(i32())
    fun f64(): Double = java.lang.Double.longBitsToDouble(i64())
    fun bytes(k: Int): ByteArray { need(k); val v = b.copyOfRange(n, n + k); n += k; return v }

    fun var64(): Long {
        var v = 0L; var shift = 0
        while (true) {
            val c = u8()
            v = v or ((c and 0x7F).toLong() shl shift)
            if (c and 0x80 == 0) return v
            shift += 7
            require(shift < 70) { "bad varint" }
        }
    }

    fun var32(): Int = var64().toInt()
    fun unzig(): Long { val v = var64(); return (v ushr 1) xor -(v and 1) }
    fun str(): String { val k = var32(); need(k); val s = String(b, n, k, Charsets.UTF_8); n += k; return s }
    fun str16(): String { val k = u16(); need(k); val s = String(b, n, k, Charsets.UTF_8); n += k; return s }
    fun optStr(): String? = if (u8() == 0) null else str()
}

/**
 * A small JSON writer and reader for the anchor's header and screen spec: maps (in their own order), lists, strings,
 * numbers (doubles written in the shortest form that reads back to the same double; non-finite ones as strings),
 * booleans and null.
 */
internal object Json {
    fun write(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> string(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float -> number(sb, v.toDouble())
            is Double -> number(sb, v)
            is Number -> number(sb, v.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    string(sb, k.toString()); sb.append(':'); write(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) { if (!first) sb.append(','); first = false; write(sb, x) }
                sb.append(']')
            }
            is Array<*> -> write(sb, v.toList())
            is DoubleArray -> write(sb, v.toList())
            is IntArray -> write(sb, v.toList())
            else -> string(sb, v.toString())
        }
    }

    private fun number(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) { string(sb, d.toString()); return }
        sb.append(d.toString())
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    fun read(s: String): Any? {
        val r = Reader(s)
        val v = r.value()
        r.ws()
        require(r.i == s.length) { "trailing JSON at ${r.i}" }
        return v
    }

    private class Reader(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            ws()
            require(i < s.length) { "JSON ends early" }
            return when (val c = s[i]) {
                '{' -> {
                    i++
                    val m = LinkedHashMap<String, Any?>()
                    ws()
                    if (s[i] == '}') { i++; return m }
                    while (true) {
                        ws(); val k = str(); ws(); require(s[i] == ':'); i++
                        m[k] = value(); ws()
                        if (s[i] == ',') { i++; continue }
                        require(s[i] == '}') { "bad JSON object at $i" }; i++
                        return m
                    }
                    @Suppress("UNREACHABLE_CODE") m
                }
                '[' -> {
                    i++
                    val l = ArrayList<Any?>()
                    ws()
                    if (s[i] == ']') { i++; return l }
                    while (true) {
                        l.add(value()); ws()
                        if (s[i] == ',') { i++; continue }
                        require(s[i] == ']') { "bad JSON array at $i" }; i++
                        return l
                    }
                    @Suppress("UNREACHABLE_CODE") l
                }
                '"' -> {
                    val v = str()
                    // non-finite numbers are written as their names
                    when (v) { "NaN" -> Double.NaN; "Infinity" -> Double.POSITIVE_INFINITY; "-Infinity" -> Double.NEGATIVE_INFINITY; else -> v }
                }
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> {
                    val st = i
                    while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
                    val t = s.substring(st, i)
                    require(t.isNotEmpty()) { "bad JSON at $st: '$c'" }
                    if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else t.toLong()
                }
            }
        }

        fun str(): String {
            require(s[i] == '"') { "JSON string expected at $i" }
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
    }
}
