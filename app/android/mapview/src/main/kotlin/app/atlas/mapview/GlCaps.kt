package app.atlas.mapview

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the phone's OpenGL ES offers, read once through a 1 × 1 off-screen context: the version, the vendor and renderer,
 * whether fragment shaders have high precision, the limits, and the extensions a GPU renderer for motion frames would
 * want. Nothing in Atlas draws with GL yet; this decides whether a GL renderer is possible at all (the plan's gate G1),
 * so the facts from the owner's phone are in hand first. [lines] is the report, as the glcaps screen and logcat print it.
 */
data class GlCaps(
    val version: String = "",
    val glslVersion: String = "",
    val vendor: String = "",
    val renderer: String = "",
    val extensions: Set<String> = emptySet(),
    /** The precision bits of a highp float in a fragment shader (0 = not supported). */
    val fragmentHighpBits: Int = 0,
    val maxTextureSize: Int = 0,
    val maxRenderbufferSize: Int = 0,
    val maxSamples: Int = 0,
    val maxColorAttachments: Int = 0,
    /** Set when no GL context could be made (the reason), so the report still says something. */
    val error: String? = null,
) {
    /** Whether a context was made and read. */
    val available: Boolean get() = error == null && version.isNotEmpty()

    /** The context's ES version, such as 3.2; 0.0 when unknown. */
    val esVersion: Double
        get() = Regex("""OpenGL ES (\d+)\.(\d+)""").find(version)?.let { it.groupValues[1].toDouble() + it.groupValues[2].toDouble() / 10 } ?: 0.0

    /** Fragment shaders can use highp floats (the plan's lighting and noise need them). */
    val highp: Boolean get() = fragmentHighpBits > 0

    /** Multisampled rendering straight into a texture without a resolve (cheap anti-aliasing for tiled GPUs). */
    val multisampledRenderToTexture: Boolean get() = has("GL_EXT_multisampled_render_to_texture")

    /** Blend modes (multiply, overlay, screen and so on) as fixed-function blending. */
    val blendEquationAdvanced: Boolean get() = has("GL_KHR_blend_equation_advanced")

    /** A fragment shader may read the pixel already in the framebuffer (blend modes and filters without a copy). */
    val shaderFramebufferFetch: Boolean get() = has("GL_EXT_shader_framebuffer_fetch")

    /** Whether the context lists [name]. */
    fun has(name: String): Boolean = name in extensions

    /** The report, one fact per line, `name: value`. */
    fun lines(): List<String> = buildList {
        if (error != null) add("gl: unavailable ($error)")
        add("gl.version: $version")
        add("gl.es: $esVersion")
        add("gl.glsl: $glslVersion")
        add("gl.vendor: $vendor")
        add("gl.renderer: $renderer")
        add("gl.highp.fragment: ${if (highp) "yes ($fragmentHighpBits bits)" else "no"}")
        add("gl.max.texture: $maxTextureSize")
        add("gl.max.renderbuffer: $maxRenderbufferSize")
        add("gl.max.samples: $maxSamples")
        add("gl.max.colour.attachments: $maxColorAttachments")
        add("gl.EXT_multisampled_render_to_texture: ${yesNo(multisampledRenderToTexture)}")
        add("gl.KHR_blend_equation_advanced: ${yesNo(blendEquationAdvanced)}")
        add("gl.EXT_shader_framebuffer_fetch: ${yesNo(shaderFramebufferFetch)}")
        for (n in RELATED) add("gl.$n: ${yesNo(has(n))}")
        add("gl.extensions: ${extensions.size}")
    }

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    companion object {
        /** Extensions next to the three that matter, worth a line each. */
        val RELATED = listOf(
            "GL_EXT_multisampled_render_to_texture2",
            "GL_EXT_shader_framebuffer_fetch_non_coherent",
            "GL_ARM_shader_framebuffer_fetch",
            "GL_EXT_color_buffer_float",
            "GL_EXT_color_buffer_half_float",
            "GL_OES_texture_float_linear",
            "GL_EXT_texture_filter_anisotropic",
            "GL_OES_EGL_image_external",
        )

        /** [extensions] is the space-separated list `glGetString(GL_EXTENSIONS)` returns. */
        fun of(
            version: String,
            glslVersion: String,
            vendor: String,
            renderer: String,
            extensions: String,
            fragmentHighpBits: Int,
            maxTextureSize: Int,
            maxRenderbufferSize: Int,
            maxSamples: Int,
            maxColorAttachments: Int,
        ) = GlCaps(
            version, glslVersion, vendor, renderer, extensions.split(' ').filter { it.isNotEmpty() }.toSet(),
            fragmentHighpBits, maxTextureSize, maxRenderbufferSize, maxSamples, maxColorAttachments,
        )

        /** Makes a 1 × 1 off-screen ES 3 (else 2) context on the calling thread, reads it and tears it down. Never throws. */
        fun probe(): GlCaps = try {
            read()
        } catch (e: Throwable) {
            GlCaps(error = "${e::class.java.simpleName}: ${e.message}")
        }

        private fun read(): GlCaps {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return GlCaps(error = "no EGL display")
            val v = IntArray(2)
            if (!EGL14.eglInitialize(display, v, 0, v, 1)) return GlCaps(error = "eglInitialize failed")
            var context: EGLContext = EGL14.EGL_NO_CONTEXT
            var surface: EGLSurface = EGL14.EGL_NO_SURFACE
            try {
                for (es in intArrayOf(3, 2)) {
                    val bit = if (es == 3) EGL_OPENGL_ES3_BIT else EGL14.EGL_OPENGL_ES2_BIT
                    val config = chooseConfig(display, bit) ?: continue
                    context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, es, EGL14.EGL_NONE), 0)
                    if (context == EGL14.EGL_NO_CONTEXT) continue
                    surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                    if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                        surface = EGL14.EGL_NO_SURFACE
                        EGL14.eglDestroyContext(display, context)
                        context = EGL14.EGL_NO_CONTEXT
                        continue
                    }
                    return readCurrent(es)
                }
                return GlCaps(error = "no ES 3 or ES 2 context (EGL error ${EGL14.eglGetError()})")
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }

        private fun chooseConfig(display: EGLDisplay, renderableBit: Int): EGLConfig? {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, renderableBit,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            return if (EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) configs[0] else null
        }

        private fun readCurrent(es: Int): GlCaps {
            fun int(name: Int): Int = IntArray(1).also { GLES20.glGetIntegerv(name, it, 0) }[0]
            val range = IntArray(2)
            val precision = IntArray(1)
            GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT, range, 0, precision, 0)
            return of(
                version = GLES20.glGetString(GLES20.GL_VERSION) ?: "",
                glslVersion = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION) ?: "",
                vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "",
                renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "",
                extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: "",
                fragmentHighpBits = precision[0],
                maxTextureSize = int(GLES20.GL_MAX_TEXTURE_SIZE),
                maxRenderbufferSize = int(GLES20.GL_MAX_RENDERBUFFER_SIZE),
                maxSamples = if (es >= 3) int(GLES30.GL_MAX_SAMPLES) else 0,
                maxColorAttachments = if (es >= 3) int(GLES30.GL_MAX_COLOR_ATTACHMENTS) else 0,
            )
        }

        /** `EGL_OPENGL_ES3_BIT` (EGL14 only names the ES 2 bit). */
        private const val EGL_OPENGL_ES3_BIT = 0x0040
    }
}

/**
 * The phone itself: model, Android, the CPU's cores and clocks, memory class and heap limits, the Vulkan level, how hot
 * it runs, and whether this build is debuggable. The numbers the phone factor needs.
 */
object DeviceFacts {
    /** The report, `device.name: value` (and `app.name: value` for this build). */
    fun lines(context: Context): List<String> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pm = context.packageManager
        val vulkan = pm.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
            ?.let { vulkanVersion(it.version) } ?: "no"
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val headroom = if (Build.VERSION.SDK_INT >= 30) runCatching { power.getThermalHeadroom(0) }.getOrDefault(Float.NaN) else Float.NaN
        val app = context.applicationInfo
        val cores = Runtime.getRuntime().availableProcessors()
        return listOf(
            "device.model: ${Build.MANUFACTURER} ${Build.MODEL}",
            "device.android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "device.soc: $soc",
            "device.abis: ${Build.SUPPORTED_ABIS.joinToString(",")}",
            "device.cores: $cores",
            "device.cpu.max.mhz: ${cpuClocks(cores)}",
            "device.performance.class: ${if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0}",
            "device.ram.total.mb: ${info.totalMem / 1_000_000}",
            "device.heap.class.mb: ${am.memoryClass}",
            "device.heap.large.mb: ${am.largeMemoryClass}",
            "device.heap.max.mb: ${Runtime.getRuntime().maxMemory() / 1_000_000}",
            "device.low.ram: ${am.isLowRamDevice}",
            "device.vulkan: $vulkan",
            "device.gles.declared: 0x${Integer.toHexString(am.deviceConfigurationInfo.reqGlEsVersion)}",
            "device.thermal.status: ${thermalName(power.currentThermalStatus)}",
            "device.thermal.headroom: ${if (headroom.isNaN()) "unknown" else "%.2f".format(java.util.Locale.ROOT, headroom)}",
            "app.debuggable: ${app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0}",
            "app.profileable: ${app.isProfileableByShell}",
        )
    }

    /** A Vulkan feature version (VK_MAKE_VERSION) as major.minor, such as 1.3. */
    fun vulkanVersion(v: Int): String = "${v ushr 22}.${(v ushr 12) and 0x3ff}"

    /** The cores' top clocks, grouped by cluster: "4×1800, 3×2400, 1×3000" (MHz), or "unknown" where the system hides them. */
    fun cpuClocks(cores: Int): String {
        val mhz = (0 until cores).map { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000 }.getOrNull()
        }
        return clusters(mhz)
    }

    /** [mhz] per core (null = unreadable) as "count×clock" groups in rising order of clock. */
    fun clusters(mhz: List<Long?>): String {
        if (mhz.isEmpty() || mhz.all { it == null }) return "unknown"
        val known = mhz.filterNotNull().groupingBy { it }.eachCount().toSortedMap()
        val missing = mhz.count { it == null }
        return (known.map { (clock, n) -> "$n×$clock" } + if (missing > 0) listOf("$missing×?") else emptyList()).joinToString(", ")
    }

    /** PowerManager's thermal status, by name. */
    fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown ($status)"
    }
}

/**
 * The Vulkan side of the phone's facts (plan-vector D18: VkFacts replace the GL capabilities; [DeviceFacts] stays, and
 * the GLES probe above stays until the survey decides whether a GLES fallback is ever built, T2): what Android itself
 * says about Vulkan (the feature flags, before any driver is opened), then the core's VkFacts, read from the driver
 * behind the crash guard. Each line is `name: value`; the VK facts screen and logcat print them as `vkfacts name: value`.
 */
object VkFactsLines {
    /** Android's Vulkan feature flags: hardware level and version, compute, and the dEQP level the driver passed. */
    fun platform(context: Context): List<String> {
        val features = context.packageManager.systemAvailableFeatures
        fun feature(name: String) = features.firstOrNull { it.name == name }
        val version = feature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)?.let { DeviceFacts.vulkanVersion(it.version) } ?: "none"
        val level = feature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)?.version?.toString() ?: "none"
        val compute = feature(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE)?.version?.toString() ?: "none"
        val deqp = if (Build.VERSION.SDK_INT >= 30) feature(PackageManager.FEATURE_VULKAN_DEQP_LEVEL)?.version?.let { dEqpDate(it) } ?: "none" else "unknown (API ${Build.VERSION.SDK_INT})"
        return listOf(
            "android.vulkan.version: $version",
            "android.vulkan.level: $level",
            "android.vulkan.compute: $compute",
            "android.vulkan.deqp: $deqp",
        )
    }

    /** A dEQP level (year << 16 | month << 8 | day) as a date, such as 2024-03-01. */
    fun dEqpDate(v: Int): String = "%04d-%02d-%02d".format(java.util.Locale.ROOT, v ushr 16, (v ushr 8) and 0xff, v and 0xff)

    /**
     * The crash guard's lines of a VK facts report (plan-vector D5.10): `guard:` (what this start found: `clear`, or the
     * `vk guard` line naming the stage and the pipeline a driver crashed in), then `guard.build`, `guard.driver` and a
     * `guard.off` line per driver turned off, in the report's order.
     */
    fun guard(report: List<String>): List<String> = report.filter { it.startsWith("guard:") || it.startsWith("guard.") }
}

/**
 * A debug screen (`atlas.screen=glcaps`, or the debug and bench builds' "GL facts" launcher shortcut): the phone's facts
 * and the GL capabilities, printed and also sent to logcat (tag `AtlasMap`, each line starting with `glcaps `), so the
 * emulator run and the owner's phone both report them. "Share" sends the report as text, for a phone without adb.
 */
@Composable
fun GlCapsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.Default) {
            val lines = DeviceFacts.lines(context) + GlCaps.probe().lines()
            for (l in lines) Log.i(EngineFrames.TAG, "glcaps $l")
            Log.i(EngineFrames.TAG, "glcaps done")
            lines
        }
    }
    Column(modifier.fillMaxSize().background(Color.White).padding(12.dp).verticalScroll(rememberScrollState())) {
        BasicText("Device and OpenGL ES", style = TextStyle(fontSize = 15.sp))
        if (report.isEmpty()) {
            BasicText("Reading…", style = TextStyle(fontSize = 12.sp))
        } else {
            BasicText(
                "Share",
                Modifier.padding(vertical = 8.dp).clickable {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Atlas: device and GL facts")
                        .putExtra(Intent.EXTRA_TEXT, report.joinToString("\n"))
                    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
                style = TextStyle(fontSize = 14.sp, color = Color(0xFF1A5FB4)),
            )
        }
        for (l in report) BasicText(l, style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace))
    }
}
