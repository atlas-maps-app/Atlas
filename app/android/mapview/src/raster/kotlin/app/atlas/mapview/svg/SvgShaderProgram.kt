package app.atlas.mapview.svg

import kotlin.math.abs
import kotlin.math.ceil

/**
 * An [SvgFilter] compiled to one AGSL program, for [SvgShaderFilters]. Plain Kotlin, so it is generated and checked on
 * the JVM.
 *
 * The program mirrors [SvgFilterRaster] node for node: every primitive becomes a function of the integer filter pixel,
 * returning unpremultiplied colour in its own colour space; turbulence reads its lattice and gradients from a small
 * texture ([noiseTable]) and rebuilds the spec's gradients exactly; displacement offsets are truncated like WebKit's.
 * The program keeps to the strict ES2 subset runtime effects accept: constant loops, no bitwise operators, no integer `%`.
 *
 * A Gaussian blur can only come last ([blur]); it then runs as a blur [android.graphics.RenderEffect] after the program.
 * Filters with a blur anywhere else are not compiled ([of] returns null) and stay on the CPU.
 */
class SvgShaderProgram private constructor(
    /** The AGSL source, or null when the filter is only a blur. */
    val source: String?,
    /** The turbulence primitives, in the order of their `noise<i>` child shaders. */
    val turbulences: List<SvgFe.Turbulence>,
    /** The final blur, if any. */
    val blur: SvgFe.GaussianBlur?,
    /** How far the program reads from the pixel it writes, in user units (displacements and offsets). */
    private val reachUser: Float,
) {
    /** The reach in filter pixels at scale [s], blur included. */
    fun reach(s: Double): Int {
        val b = blur?.let { 3.0 * maxOf(it.deviationX, it.deviationY) * s } ?: 0.0
        return ceil(reachUser * s + b).toInt() + 2
    }

    companion object {
        /** Compiles [f], or returns null when it has a blur before its last primitive or an input shaders cannot read. */
        fun of(f: SvgFilter): SvgShaderProgram? {
            val prims = f.primitives
            if (prims.isEmpty()) return null
            val last = prims.last()
            val blur = last as? SvgFe.GaussianBlur
            val body = if (blur != null) prims.dropLast(1) else prims
            if (body.any { it is SvgFe.GaussianBlur }) return null
            if (blur != null) {
                // The blur must read the result just before it: the program's output.
                val prev = body.lastOrNull()
                val ok = when (blur.input) {
                    null -> true
                    SvgFe.SOURCE_GRAPHIC -> body.isEmpty()
                    else -> prev != null && prev.result == blur.input
                }
                if (!ok) return null
                if (body.isEmpty()) return SvgShaderProgram(null, emptyList(), blur, 0f)
            }
            return Gen(body).build(blur)
        }

        /**
         * The texture of one turbulence seed: 512 × 5 opaque pixels. Row 0 holds the lattice selector in red; row
         * 1 + c holds channel c's gradients: red and green the low bytes of `gx · 256 + 256` and `gy · 256 + 256`, blue
         * their ninth bits (x in bit 0, y in bit 1). Returned as ARGB ints, row-major.
         */
        fun noiseTable(t: Turbulence): IntArray {
            val out = IntArray(512 * 5)
            val lat = t.lattice
            for (x in 0 until 512) out[x] = (0xFF shl 24) or (lat[x] shl 16)
            val g = t.rawGradients
            for (c in 0 until 4) for (i in 0 until 256) {
                val ux = g[(c * 256 + i) * 2] + 256
                val uy = g[(c * 256 + i) * 2 + 1] + 256
                val hi = (ux shr 8) or ((uy shr 8) shl 1)
                out[(1 + c) * 512 + i] = (0xFF shl 24) or ((ux and 0xFF) shl 16) or ((uy and 0xFF) shl 8) or hi
            }
            return out
        }

        private fun lit(v: Float): String {
            val s = v.toString()
            return if (s.contains('E')) "%.9f".format(java.util.Locale.ROOT, v) else s
        }

        private fun lit(v: Double): String = "%.12f".format(java.util.Locale.ROOT, v)
    }

    private class Gen(val prims: List<SvgFe>) {
        val sb = StringBuilder()
        val turbs = ArrayList<SvgFe.Turbulence>()

        /** Each node's function name and colour space; index 0 and 1 are SourceGraphic and SourceAlpha. */
        val names = ArrayList<String>()
        val linear = ArrayList<Boolean>()
        var reach = 0f

        fun build(blur: SvgFe.GaussianBlur?): SvgShaderProgram? {
            sb.append(HEADER)
            names.add("sourceGraphic")
            linear.add(false)
            names.add("sourceAlpha")
            linear.add(false)
            val named = HashMap<String, Int>()
            var previous = 0
            for ((i, fe) in prims.withIndex()) {
                fun input(name: String?): Int? = when (name) {
                    null -> previous
                    SvgFe.SOURCE_GRAPHIC -> 0
                    SvgFe.SOURCE_ALPHA -> 1
                    else -> named[name] ?: if (name in STANDARD) null else previous
                }
                val fn = "f$i"
                val ok = when (fe) {
                    is SvgFe.Turbulence -> turbulence(fn, fe)
                    is SvgFe.DisplacementMap -> displacement(fn, fe, input(fe.input) ?: return null, input(fe.input2) ?: return null)
                    is SvgFe.ColorMatrix -> matrix(fn, fe, input(fe.input) ?: return null)
                    is SvgFe.Composite -> composite(fn, fe, input(fe.input) ?: return null, input(fe.input2) ?: return null)
                    is SvgFe.Offset -> offset(fn, fe, input(fe.input) ?: return null)
                    is SvgFe.Flood -> flood(fn, fe)
                    is SvgFe.Merge -> merge(fn, fe, fe.inputs.map { input(it) ?: return null })
                    is SvgFe.GaussianBlur -> false
                }
                if (!ok) return null
                val idx = names.size - 1
                fe.result?.let { named[it] = idx }
                previous = idx
            }
            val out = names[previous]
            val outLinear = linear[previous]
            sb.append("half4 main(float2 fc) {\n")
            sb.append("  float2 p = floor(fc) + origin;\n")
            sb.append("  float4 v = $out(p);\n")
            sb.append("  if (v.a <= 0.0) return half4(0.0);\n")
            if (outLinear) sb.append("  v.rgb = toSrgb3(v.rgb);\n")
            sb.append("  float a = q8(v.a) / 255.0;\n")
            sb.append("  return half4(float3(q8(v.r), q8(v.g), q8(v.b)) / 255.0 * a, a);\n}\n")
            val children = StringBuilder("uniform shader src;\n")
            for (t in turbs.indices) children.append("uniform shader noise$t;\n")
            return SvgShaderProgram(children.toString() + sb.toString(), turbs, blur, reach)
        }

        /** An expression reading node [n] at [p] in colour space [toLinear]. */
        fun read(n: Int, p: String, toLinear: Boolean): String {
            val f = "${names[n]}($p)"
            return when {
                linear[n] == toLinear -> f
                toLinear -> "toLinear4($f)"
                else -> "toSrgb4($f)"
            }
        }

        fun add(name: String, lin: Boolean) {
            names.add(name)
            linear.add(lin)
        }

        fun turbulence(fn: String, fe: SvgFe.Turbulence): Boolean {
            if (fe.stitch && fe.baseX > 0f && fe.baseY > 0f) return false
            if (fe.octaves < 1 || fe.octaves > 8) return false
            val t = turbs.size
            turbs.add(fe)
            sb.append(
                """
                float lat$t(float x) { return floor(noise$t.eval(float2(x + 0.5, 0.5)).r * 255.0 + 0.5); }
                float2 grad$t(float c, float b) {
                  half4 e = noise$t.eval(float2(b + 0.5, c + 1.5));
                  float lo = floor(e.r * 255.0 + 0.5);
                  float lo2 = floor(e.g * 255.0 + 0.5);
                  float hi = floor(e.b * 255.0 + 0.5);
                  float hy = floor(hi / 2.0);
                  float hx = hi - 2.0 * hy;
                  float gx = (lo + 256.0 * hx - 256.0) / 256.0;
                  float gy = (lo2 + 256.0 * hy - 256.0) / 256.0;
                  return float2(gx, gy) / sqrt(gx * gx + gy * gy);
                }
                float pnoise$t(float c, float2 v) {
                  float2 fl = floor(v);
                  float2 r0 = v - fl;
                  float2 r1 = r0 - 1.0;
                  float bx0 = mod(fl.x + 4096.0, 256.0);
                  float bx1 = mod(bx0 + 1.0, 256.0);
                  float by0 = mod(fl.y + 4096.0, 256.0);
                  float by1 = mod(by0 + 1.0, 256.0);
                  float i = lat$t(bx0);
                  float j = lat$t(bx1);
                  float sx = r0.x * r0.x * (3.0 - 2.0 * r0.x);
                  float sy = r0.y * r0.y * (3.0 - 2.0 * r0.y);
                  float2 q = grad$t(c, lat$t(i + by0));
                  float u = r0.x * q.x + r0.y * q.y;
                  q = grad$t(c, lat$t(j + by0));
                  float w = r1.x * q.x + r0.y * q.y;
                  float a = u + sx * (w - u);
                  q = grad$t(c, lat$t(i + by1));
                  u = r0.x * q.x + r1.y * q.y;
                  q = grad$t(c, lat$t(j + by1));
                  w = r1.x * q.x + r1.y * q.y;
                  float b = u + sx * (w - u);
                  return a + sy * (b - a);
                }
                float turb$t(float c, float2 u) {
                  float2 v = u * float2(${lit(fe.baseX.toDouble())}, ${lit(fe.baseY.toDouble())});
                  float sum = 0.0;
                  float ratio = 1.0;
                  for (int o = 0; o < ${fe.octaves}; o++) {
                    float n = pnoise$t(c, v);
                    sum += ${if (fe.fractal) "n" else "abs(n)"} / ratio;
                    v *= 2.0;
                    ratio *= 2.0;
                  }
                  float k = ${if (fe.fractal) "sum * 0.5 + 0.5" else "sum"};
                  return clamp(floor(k * 255.0), 0.0, 255.0) / 255.0;
                }
                float4 $fn(float2 p) {
                  float2 u = (p + 1.0) / scale;
                  return float4(turb$t(0.0, u), turb$t(1.0, u), turb$t(2.0, u), turb$t(3.0, u));
                }

                """.trimIndent(),
            )
            add(fn, fe.linearRgb)
            return true
        }

        fun displacement(fn: String, fe: SvgFe.DisplacementMap, input: Int, map: Int): Boolean {
            val ch = "rgba"
            reach += abs(fe.scale) / 2 + 1
            sb.append(
                """
                float4 $fn(float2 p) {
                  float4 m = ${read(map, "p", fe.linearRgb)};
                  float2 k = ${lit(fe.scale)} * scale;
                  float dx = trunc0(k.x / 255.0 * q8(m.${ch[fe.xChannel]}) + (0.5 - k.x * 0.5));
                  float dy = trunc0(k.y / 255.0 * q8(m.${ch[fe.yChannel]}) + (0.5 - k.y * 0.5));
                  float2 q = p + float2(dx, dy);
                  if (!inRegion(q)) return float4(0.0);
                  return ${names[input]}(q);
                }

                """.trimIndent(),
            )
            add(fn, linear[input])
            return true
        }

        fun matrix(fn: String, fe: SvgFe.ColorMatrix, input: Int): Boolean {
            val m = fe.matrix
            fun row(r: Int) = (0 until 4).joinToString(" + ") { c -> "${lit(m[r * 5 + c])} * i.${"rgba"[c]}" } + " + ${lit(m[r * 5 + 4])}"
            sb.append("float4 $fn(float2 p) {\n  float4 i = ${read(input, "p", fe.linearRgb)};\n")
            sb.append("  return clamp(float4(${row(0)}, ${row(1)}, ${row(2)}, ${row(3)}), 0.0, 1.0);\n}\n")
            add(fn, fe.linearRgb)
            return true
        }

        fun composite(fn: String, fe: SvgFe.Composite, a: Int, b: Int): Boolean {
            sb.append("float4 $fn(float2 p) {\n")
            sb.append("  float4 x = ${read(a, "p", fe.linearRgb)};\n  float4 y = ${read(b, "p", fe.linearRgb)};\n")
            if (fe.operator == SvgFe.Composite.Operator.ARITHMETIC) {
                val k1 = lit(fe.k1)
                val k2 = lit(fe.k2)
                val k3 = lit(fe.k3)
                val k4 = lit(fe.k4)
                sb.append("  float ra = clamp($k1 * x.a * y.a + $k2 * x.a + $k3 * y.a + $k4, 0.0, 1.0);\n")
                sb.append("  if (ra <= 0.0) return float4(0.0);\n")
                sb.append("  float3 pa = x.rgb * x.a;\n  float3 pb = y.rgb * y.a;\n")
                sb.append("  float3 c = clamp($k1 * pa * pb + $k2 * pa + $k3 * pb + $k4, 0.0, ra);\n")
                sb.append("  return float4(c / ra, ra);\n}\n")
            } else {
                val (fa, fb) = when (fe.operator) {
                    SvgFe.Composite.Operator.OVER -> "1.0" to "1.0 - x.a"
                    SvgFe.Composite.Operator.IN -> "y.a" to "0.0"
                    SvgFe.Composite.Operator.OUT -> "1.0 - y.a" to "0.0"
                    SvgFe.Composite.Operator.ATOP -> "y.a" to "1.0 - x.a"
                    else -> "1.0 - y.a" to "1.0 - x.a"
                }
                sb.append("  float fa = $fa;\n  float fb = $fb;\n")
                sb.append("  float ra = x.a * fa + y.a * fb;\n  if (ra <= 0.0) return float4(0.0);\n")
                sb.append("  return float4((x.rgb * x.a * fa + y.rgb * y.a * fb) / ra, ra);\n}\n")
            }
            add(fn, fe.linearRgb)
            return true
        }

        fun offset(fn: String, fe: SvgFe.Offset, input: Int): Boolean {
            reach += maxOf(abs(fe.dx), abs(fe.dy)) + 1
            val inp = names[input]
            sb.append(
                """
                float4 $fn(float2 p) {
                  float2 d = float2(${lit(fe.dx)}, ${lit(fe.dy)}) * scale;
                  float2 id = floor(d);
                  float2 fr = d - id;
                  if (fr.x == 0.0 && fr.y == 0.0) {
                    float2 q = p - id;
                    if (!inRegion(q)) return float4(0.0);
                    return $inp(q);
                  }
                  float4 acc = float4(0.0);
                  for (int k = 0; k < 4; k++) {
                    float ox = mod(float(k), 2.0);
                    float oy = floor(float(k) / 2.0);
                    float w = (ox == 0.0 ? 1.0 - fr.x : fr.x) * (oy == 0.0 ? 1.0 - fr.y : fr.y);
                    float2 q = p - id - float2(ox, oy);
                    if (w != 0.0 && inRegion(q)) {
                      float4 v = $inp(q);
                      acc += float4(v.rgb * v.a * w, v.a * w);
                    }
                  }
                  if (acc.a <= 0.0) return float4(0.0);
                  return float4(acc.rgb / acc.a, acc.a);
                }

                """.trimIndent(),
            )
            add(fn, linear[input])
            return true
        }

        fun flood(fn: String, fe: SvgFe.Flood): Boolean {
            val c = fe.argb
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val v = "float3(${lit(r)}, ${lit(g)}, ${lit(b)})"
            sb.append("float4 $fn(float2 p) { return float4(${if (fe.linearRgb) "toLinear3($v)" else v}, ${lit((c ushr 24) / 255f)}); }\n")
            add(fn, fe.linearRgb)
            return true
        }

        fun merge(fn: String, fe: SvgFe.Merge, inputs: List<Int>): Boolean {
            sb.append("float4 $fn(float2 p) {\n  float3 c = float3(0.0);\n  float a = 0.0;\n  float4 t;\n")
            for (i in inputs) {
                sb.append("  t = ${read(i, "p", fe.linearRgb)};\n")
                sb.append("  c = t.rgb * t.a + c * (1.0 - t.a);\n  a = t.a + a * (1.0 - t.a);\n")
            }
            sb.append("  if (a <= 0.0) return float4(0.0);\n  return float4(c / a, a);\n}\n")
            add(fn, fe.linearRgb)
            return true
        }

        companion object {
            val STANDARD = setOf("BackgroundImage", "BackgroundAlpha", "FillPaint", "StrokePaint")

            val HEADER = """
                uniform float2 origin;
                uniform float2 scale;
                uniform float4 region;

                float q8(float v) { return clamp(floor(v * 255.0 + 0.5), 0.0, 255.0); }
                float trunc0(float v) { return v < 0.0 ? ceil(v) : floor(v); }
                bool inRegion(float2 q) { return q.x >= region.x && q.y >= region.y && q.x < region.z && q.y < region.w; }
                float toLinear1(float c) { return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4); }
                float toSrgb1(float c) { return c <= 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055; }
                float3 toLinear3(float3 c) { return float3(toLinear1(c.r), toLinear1(c.g), toLinear1(c.b)); }
                float3 toSrgb3(float3 c) { return float3(toSrgb1(clamp(c.r, 0.0, 1.0)), toSrgb1(clamp(c.g, 0.0, 1.0)), toSrgb1(clamp(c.b, 0.0, 1.0))); }
                float4 toLinear4(float4 c) { return float4(toLinear3(c.rgb), c.a); }
                float4 toSrgb4(float4 c) { return float4(toSrgb3(c.rgb), c.a); }
                float4 sourceGraphic(float2 p) {
                  half4 c = src.eval(p - origin + 0.5);
                  if (c.a <= 0.0) return float4(0.0);
                  return float4(float3(c.rgb) / float(c.a), float(c.a));
                }
                float4 sourceAlpha(float2 p) { return float4(0.0, 0.0, 0.0, float(src.eval(p - origin + 0.5).a)); }

            """.trimIndent() + "\n"
        }
    }
}
