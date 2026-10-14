package app.atlas.mapview.svg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The AGSL programs [SvgShaderProgram] makes for the engine's filters. Robolectric cannot compile AGSL, so this checks
 * what can be checked on the JVM: every filter of the renders compiles to a program (or to a plain blur), the program
 * keeps to the strict ES2 subset runtime effects accept, and the noise texture rebuilds the generator's gradients.
 */
class SvgShaderProgramTest {
    private fun probeFilters(): Map<String, SvgFilter> {
        val dir = File(javaClass.getResource("/svg/probes")!!.toURI())
        return dir.listFiles { f -> f.name.endsWith(".svg") }!!.associate { f -> f.name.removeSuffix(".svg") to SvgParser.parse(f).filters.values.single() }
    }

    @Test
    fun everyEngineFilterCompiles() {
        for ((name, f) in probeFilters()) {
            val p = SvgShaderProgram.of(f)
            assertNotNull(name, p)
            p!!
            val onlyBlur = f.primitives.size == 1 && f.primitives[0] is SvgFe.GaussianBlur
            if (onlyBlur) {
                assertNull(name, p.source)
                assertNotNull(name, p.blur)
                continue
            }
            val src = p.source!!
            assertEquals(name, f.primitives.count { it is SvgFe.Turbulence }, p.turbulences.size)
            // The strict ES2 subset: no integer remainder, no bit operations, no while loops; braces balance.
            for (bad in listOf("%", "<<", ">>", "while", "^", "~")) assertFalse("$name uses '$bad'", src.contains(bad))
            assertFalse("$name uses a bitwise and", Regex("(?<!&)&(?!&)").containsMatchIn(src))
            assertFalse("$name uses a bitwise or", Regex("(?<!\\|)\\|(?!\\|)").containsMatchIn(src))
            assertEquals(name, src.count { it == '{' }, src.count { it == '}' })
            assertEquals(name, src.count { it == '(' }, src.count { it == ')' })
            assertTrue(name, src.contains("half4 main(float2 fc)"))
            assertTrue(name, src.startsWith("uniform shader src;"))
            for (i in p.turbulences.indices) assertTrue(name, src.contains("uniform shader noise$i;"))
        }
    }

    @Test
    fun aBlurBeforeTheEndStaysOnTheCpu() {
        val doc = SvgParser.parse(
            """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><defs><filter id="f"><feGaussianBlur stdDeviation="2"/><feOffset dx="1"/></filter></defs></svg>""",
        )
        assertNull(SvgShaderProgram.of(doc.filters["f"]!!))
    }

    @Test
    fun theNoiseTableRebuildsTheGradients() {
        for (seed in listOf(2f, 3f, 5f, 13f, 31f)) {
            val t = Turbulence(seed)
            val table = SvgShaderProgram.noiseTable(t)
            val lattice = t.lattice
            for (x in 0 until 512) assertEquals(lattice[x], (table[x] shr 16) and 0xFF)
            for (c in 0 until 4) for (i in 0 until 256) {
                val p = table[(1 + c) * 512 + i]
                // Decoded as the shader decodes it.
                val hi = p and 0xFF
                val hy = hi / 2
                val hx = hi - 2 * hy
                val gx = ((p shr 16) and 0xFF) + 256 * hx - 256
                val gy = ((p shr 8) and 0xFF) + 256 * hy - 256
                assertEquals(t.rawGradients[(c * 256 + i) * 2], gx)
                assertEquals(t.rawGradients[(c * 256 + i) * 2 + 1], gy)
                assertTrue(gx != 0 || gy != 0)
                assertEquals(0xFF, p ushr 24)
            }
        }
    }

    @Test
    fun reachCoversDisplacementAndBlur() {
        val f = probeFilters()
        assertTrue(SvgShaderProgram.of(f["crown"]!!)!!.reach(2.0) >= 10)
        assertTrue(SvgShaderProgram.of(f["sblur"]!!)!!.reach(2.0) >= 15)
    }
}
