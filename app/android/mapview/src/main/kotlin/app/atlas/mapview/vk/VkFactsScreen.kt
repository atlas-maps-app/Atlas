package app.atlas.mapview.vk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.mapcore.CameraState
import app.atlas.mapcore.CoreConfig
import app.atlas.mapcore.FrameSource
import app.atlas.mapcore.MapCore
import app.atlas.mapview.DeviceFacts
import app.atlas.mapview.EngineFrames
import app.atlas.mapview.GlCaps
import app.atlas.mapview.VkFactsLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/** What a phone shows where its Vulkan driver cannot draw the map (plan-vector T2), with the facts and a Copy button. */
const val VK_HONEST = "This phone's graphics driver can't draw the Atlas map."

/**
 * VK facts (plan-vector K1, A0; `atlas.screen=vkfacts`, or the debug and bench builds' "VK facts" shortcut): the phone
 * (model, SoC, Android, memory, thermal state), Android's Vulkan flags, the core's VkFacts read from the driver behind
 * the crash guard (GPU, driver and version, tiler, lazily allocated memory, Vulkan version, Android's Vulkan profiles,
 * memory types, sample counts, extensions, the frame configuration and route the core would pick, the surface's
 * capabilities from a small SurfaceView), the GLES probe the survey still reads, the core's numerics check (K3b's
 * `pymath <hash> ok`) and its sun check over the bundled Westerkerk (K8's `sun <hash> ok`). Every line also goes to
 * logcat as `vkfacts <line>`, the numerics as `pymath …` and the sun as `sun …`, then `vkfacts done`. Copy puts the
 * whole report on the clipboard for the paste back.
 *
 * The crash guard's state (D5.10) leads the report: what this start found (`guard:`, the `vk guard` line naming the
 * stage and the pipeline or shader module a driver crashed in), the core's build ID and every driver turned off, or
 * turned 10 bits off for (VK bench's colour stage crashed: the frame keeps its 8, and `colour:` says so), or turned a
 * VK bench cell off for (the driver crashed in a configuration the facts would not pick: VK bench skips it), for which
 * build. In the debug and bench builds ([guardReset]) "Reset guard" closes the core (a live one would write its
 * pipeline cache back), forgets those crashes and the pipeline cache, and reads the facts again with a new core, so
 * the owner can retry Vulkan, 10 bits and the cells by hand.
 */
@Composable
fun VkFactsScreen(config: CoreConfig, modifier: Modifier = Modifier, guardReset: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("Reading the device…") }
    var core by remember { mutableStateOf<MapCore?>(null) }
    var surfaceDone by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf("") }
    var reset by remember { mutableStateOf("") }
    // A new reading after a reset: the core is asked for again (made now if the guard had Vulkan off) and the facts
    // are read again.
    var reading by remember { mutableIntStateOf(0) }

    LaunchedEffect(reading) {
        core = withContext(Dispatchers.Default) { MapCore.shared(config) }
        if (core == null) {
            refused = MapCore.lastError()
            surfaceDone = true
        }
    }
    LaunchedEffect(surfaceDone, reading) {
        if (!surfaceDone) return@LaunchedEffect
        report = withContext(Dispatchers.Default) {
            val lines = buildList {
                add("atlas.vkfacts: 1")
                addAll(DeviceFacts.lines(context))
                addAll(VkFactsLines.platform(context))
                val c = core
                if (c != null) {
                    addAll(c.facts())
                } else {
                    add("vulkan.state: $VK_HONEST")
                    add("vulkan.refused: $refused")
                    addAll(MapCore.facts(config))
                }
                add("core.build: ${MapCore.buildInfo()}")
                addAll(GlCaps.probe().lines())
            }
            for (l in lines) Log.i(EngineFrames.TAG, "vkfacts $l")
            // The core's numerics (K3b): `pymath <hash> ok`, which smoke.sh holds to the host build's hash. Logged before
            // "vkfacts done", which smoke.sh waits for; it needs no Vulkan, so the honest state has it too.
            val pymath = runCatching { MapCore.numericsLine() }.getOrElse { "pymath unavailable: $it" }
            Log.i(EngineFrames.TAG, pymath)
            // The core's sun bits on the bundled Westerkerk (K8): `sun <hash> ok`, held by smoke.sh to the host build's.
            val sun = runCatching { MapCore.sunLine(context.assets) }.getOrElse { "sun unavailable: $it" }
            Log.i(EngineFrames.TAG, sun)
            // The core's noise fields at S1 (K17a): `fields 1080x2400 cpu <ms> gpu off hash <h> ok`, each of the five
            // equal to the port's (the hashes the host build's tests hold); smoke.sh fails on any other ending.
            val fields = runCatching { MapCore.fieldsLine() }.getOrElse { "fields unavailable: $it" }
            Log.i(EngineFrames.TAG, fields)
            Log.i(EngineFrames.TAG, "vkfacts done")
            lines + pymath + sun + fields
        }
        status = if (core == null) VK_HONEST else "Done: ${report.size} lines."
    }

    Column(modifier.fillMaxSize().background(Color.White).padding(12.dp)) {
        BasicText("VK facts: device, Vulkan and OpenGL ES", style = TextStyle(fontSize = 15.sp))
        BasicText(status, style = TextStyle(fontSize = 12.sp, color = if (core == null && surfaceDone) Color(0xFFB00020) else Color.Black))
        if (report.isNotEmpty()) {
            Row(Modifier.padding(vertical = 8.dp)) {
                Button("Copy") { copy(context, "Atlas VK facts", report.joinToString("\n")) }
                Button("Share") { share(context, "Atlas: VK facts", report.joinToString("\n")) }
                if (guardReset) {
                    Button("Reset guard") {
                        scope.launch {
                            // The reset closes the process's core first: this screen lets go of it (and of its small
                            // window) before, and the reading after it makes a new one.
                            report = emptyList()
                            status = "Resetting the guard…"
                            surfaceDone = false
                            core = null
                            reset = withContext(Dispatchers.Default) { MapCore.resetGuard(config) }
                            Log.i(EngineFrames.TAG, "vkfacts $reset")
                            status = "Reading the device again…"
                            reading++
                        }
                    }
                }
            }
            // The crash guard's state, first: what this start found, the build, the drivers turned off.
            val guard = VkFactsLines.guard(report)
            if (guard.isNotEmpty() || reset.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().background(Color(0xFFF4F1E8)).padding(8.dp)) {
                    if (reset.isNotEmpty()) BasicText(reset, style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace))
                    for (l in guard) BasicText(l, style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace))
                }
            }
        }
        // A small window for the surface's facts (formats, present modes, transform, the usage the swapchain may have).
        val c = core
        if (c != null && !surfaceDone) {
            val camera = remember { CameraState() }
            Box(Modifier.size(96.dp)) {
                VkSurface(c, VkHost.SURFACE, IdleSource, camera, Modifier.fillMaxSize()) { s ->
                    if (s.attached || s.error.isNotEmpty()) {
                        if (s.error.isNotEmpty()) Log.w(EngineFrames.TAG, "vkfacts surface: ${s.error}")
                        surfaceDone = true
                    }
                }
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            for (l in report) BasicText(l, style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace))
        }
    }
}

/** A frame source with nothing to draw (the facts screen's window only lends its surface). */
internal object IdleSource : FrameSource {
    override fun camera(vsyncNanos: Long, out: ByteBuffer): Boolean = false
    override fun presented(frame: Long, nanos: Long, complete: Boolean) = Unit
}

@Composable
internal fun Button(label: String, onClick: () -> Unit) {
    BasicText(
        label,
        Modifier.padding(end = 16.dp).background(Color(0xFFE8EEF8)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        style = TextStyle(fontSize = 14.sp, color = Color(0xFF1A5FB4)),
    )
}

internal fun copy(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun share(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
