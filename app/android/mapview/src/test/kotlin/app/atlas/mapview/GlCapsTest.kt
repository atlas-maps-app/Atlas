package app.atlas.mapview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The GL capability report: the three extensions the plan asks about, highp, and a probe that never throws. */
class GlCapsTest {
    private val caps = GlCaps.of(
        version = "OpenGL ES 3.2 V@0502.0",
        glslVersion = "OpenGL ES GLSL ES 3.20",
        vendor = "Qualcomm",
        renderer = "Adreno (TM) 740",
        extensions = "GL_EXT_multisampled_render_to_texture GL_KHR_blend_equation_advanced  GL_OES_EGL_image_external GL_EXT_color_buffer_float",
        fragmentHighpBits = 23,
        maxTextureSize = 16384,
        maxRenderbufferSize = 16384,
        maxSamples = 4,
        maxColorAttachments = 8,
    )

    @Test
    fun readsTheThreeExtensionsAndHighp() {
        assertTrue(caps.available)
        assertEquals(3.2, caps.esVersion, 1e-9)
        assertTrue(caps.highp)
        assertTrue(caps.multisampledRenderToTexture)
        assertTrue(caps.blendEquationAdvanced)
        assertFalse(caps.shaderFramebufferFetch)
        assertEquals(4, caps.extensions.size)
    }

    @Test
    fun theReportNamesEveryFactOnItsOwnLine() {
        val lines = caps.lines()
        assertTrue(lines.contains("gl.version: OpenGL ES 3.2 V@0502.0"))
        assertTrue(lines.contains("gl.highp.fragment: yes (23 bits)"))
        assertTrue(lines.contains("gl.EXT_multisampled_render_to_texture: yes"))
        assertTrue(lines.contains("gl.KHR_blend_equation_advanced: yes"))
        assertTrue(lines.contains("gl.EXT_shader_framebuffer_fetch: no"))
        assertTrue(lines.contains("gl.GL_EXT_color_buffer_float: yes"))
        assertTrue(lines.all { ": " in it })
    }

    @Test
    fun anUnavailableContextStillReports() {
        val none = GlCaps(error = "no EGL display")
        assertFalse(none.available)
        assertFalse(none.highp)
        assertEquals(0.0, none.esVersion, 0.0)
        assertTrue(none.lines().first().startsWith("gl: unavailable (no EGL display)"))
    }

    @Test
    fun theDeviceFactsReadTheirCodes() {
        // VK_MAKE_VERSION(1, 3, 0) and (1, 1, 128)
        assertEquals("1.3", DeviceFacts.vulkanVersion((1 shl 22) or (3 shl 12)))
        assertEquals("1.1", DeviceFacts.vulkanVersion((1 shl 22) or (1 shl 12) or 128))
        // a phone's clusters, in rising order of clock, and cores the system hides
        assertEquals("4×1800, 3×2400, 1×3000", DeviceFacts.clusters(listOf(1800L, 1800L, 1800L, 1800L, 3000L, 2400L, 2400L, 2400L)))
        assertEquals("2×2000, 2×?", DeviceFacts.clusters(listOf(2000L, null, 2000L, null)))
        assertEquals("unknown", DeviceFacts.clusters(listOf(null, null)))
        assertEquals("unknown", DeviceFacts.clusters(emptyList()))
        assertEquals("none", DeviceFacts.thermalName(android.os.PowerManager.THERMAL_STATUS_NONE))
        assertEquals("severe", DeviceFacts.thermalName(android.os.PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals("unknown (42)", DeviceFacts.thermalName(42))
    }

    @Test
    fun theProbeNeverThrowsWhereThereIsNoGpu() {
        // On the JVM (no EGL) it answers with the reason; on a device it answers with the context. Either way a value.
        val c = GlCaps.probe()
        assertNotNull(c)
        assertTrue(c.lines().isNotEmpty())
    }
}
