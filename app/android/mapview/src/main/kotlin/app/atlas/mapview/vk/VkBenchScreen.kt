package app.atlas.mapview.vk

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.atlas.mapcore.CameraState
import app.atlas.mapcore.CoreConfig
import app.atlas.mapcore.FrameRecords
import app.atlas.mapcore.FrameSource
import app.atlas.mapcore.MapCore
import app.atlas.mapcore.state.CameraWriter
import app.atlas.mapcore.state.ThemeBlock
import app.atlas.mapview.EngineFrames
import app.atlas.mapview.MapEngine
import app.atlas.render.live.Moment
import app.atlas.render.live.RegionFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * VK bench (plan-vector K1, A0; `atlas.screen=vkbench`, or the debug and bench builds' "VK bench" shortcut): the
 * stand-in, a city-sized frame drawn through the real Vulkan core, and the app's own map over the APK's bundled packs,
 * measured on the phone's GPU. In order, at the frame's 8 bits a channel:
 *  1. every frame configuration (A, B, C, and 1x for MSAA's own cost) by both forms (the composite, and one subpass with
 *     the effects per fragment), both routes (dynamic rendering with local reads, a render pass with two subpasses)
 *     and, off a tiler, both targets of the composite, at the Main board's view and at pitch 70°;
 *  2. both hosts (the SurfaceView, the TextureView) with the pills on, under the scripted pan, pinch, orbit and tilt:
 *     presented intervals, the core's GPU time and HWUI's (`FrameMetrics.GPU_DURATION`), the render thread's time,
 *     graphics and native memory;
 *  3. the app's map (ENGINE.md step 2: the real paths), in the configuration the facts pick, as the app draws it: the
 *     Westerkerk home still, turning, panning and pinched out to 8 km and back, then the Main board and its pitch of 70°,
 *     turning; the map's first picture, and how many frames of each path were complete pictures (BenchRun's mapStage);
 *  4. the pills moving over the SurfaceView, placed through [ChromeShapes] as the app's chrome is (Compose lays them out,
 *     the core draws their glass from the camera state), a second's move and a second's rest in each 2 s cycle: how
 *     many of the core's frames reach the display on their vsync, how many of the window's frames are late (a pill and
 *     its frosted glass drift apart when either is), how many of the core's frames while the pills move share their
 *     frame with the window's, and the frosted pass's GPU time;
 *  5. a soak: the thermal headroom every 10 s and its slope over minutes 5-10, the battery's charge used (unplugged);
 * then pipeline creation (this launch's first, and again from an empty cache and from the cache file) and the A0
 * decisions the numbers point to; then, once all of that is recorded,
 *  6. the colour stage (plan-vector D5, "Colour depth"): where the surface offers 10 bits a channel, each configuration
 *     at the board's view at 10 bits and again at 8 beside it, the `vkbench colour` lines (what the 10 bits cost), the
 *     10-bit frames behind the crash guard's colour stage; where it offers none, each at 8 bits alone, saying so, so
 *     the stage's flow runs on every surface. A driver that crashes there has 10 bits turned off for it and this build
 *     (VK facts' `colour:` says so) and nothing else: the next start skips the stage, saying so, and shows the crashed
 *     run's report as recorded before it (Copy keeps it). A window that goes during the stage is waited for, at most
 *     [VkBench.WINDOW_WAIT_SECONDS] seconds; then the stage ends, saying so.
 * The cells the facts would not pick (every configuration, route, form and target but the facts' own) are work only VK
 * bench does: a driver that crashes in one has that cell alone turned off for it and this build (VK facts'
 * `guard.off:` says so), and the next run skips it (`vkbench <cell>: off (the driver crashed in it: vk guard …)`) and
 * goes on; Vulkan stays on for the map. The picked configuration, which every step after the matrix draws, is the
 * facts' own (`config:`, `route:`, `form:`, `composite:`), so its crashes follow Vulkan's ladder.
 * The app sent to the background pauses the run wherever it is: the core leaves the colour stage (mode 0: its 10-bit
 * frame and output go, and nothing is made in their place) and draws nothing, and no new step of the run reaches the
 * core while the app is away (no configuration, host's window, facts or pipelines' timing; a call already under way,
 * the facts or the pipelines' timing, finishes), so no crash-guard marker stays on disk for a process the system may
 * freeze and kill. Each step waits for the app, however long it is away, and then for its window; a measurement it
 * cut short is made again, so nothing measured in the background is a result. In the colour stage the run waits for
 * the app at most [VkBench.WINDOW_WAIT_SECONDS] seconds, as for a window, then the stage ends.
 * Every result is a `vkbench …` line (logcat, the screen, Copy); the report is recorded on disk as each step begins, so
 * a native crash cannot cost what was measured before it. The Pills run is step 4 alone, 20 s long, for the phone's own
 * screen recorder to film the pills frame by frame (ci/pillcheck.py reads the film's frames), then the pills still for
 * 8 s frosted and 8 s with their tint alone (`vkbench pills still: frosted|plain tint`), which CI's screen captures
 * judge: the emulator's software Vulkan draws the stand-in far slower than its display, so it checks still pills
 * alone; the moving check is the phone's. Last, 8 s of the map and its pills under a layer of alpha 0.5 (`vkbench fade:`):
 * whether the SurfaceView follows a fading screen on this API level, which CI records.
 */
@Composable
fun VkBenchScreen(config: CoreConfig, length: VkBench.Length?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lines = remember { mutableStateListOf<String>() }
    var status by remember { mutableStateOf("Starting the core…") }
    var core by remember { mutableStateOf<MapCore?>(null) }
    var chosen by remember { mutableStateOf(length) }
    var host by remember { mutableStateOf(VkHost.SURFACE) }
    var pillsMoving by remember { mutableStateOf(false) }
    var pillsOn by remember { mutableStateOf(true) }
    var surfaceState by remember { mutableStateOf(VkSurfaceState(false, 0, 0)) }
    var attachWaiter by remember { mutableStateOf<CompletableDeferred<VkSurfaceState>?>(null) }
    val camera = remember { CameraState() }
    // The pills are chrome as the app's are: placed by Compose, written into each frame's camera state by the source.
    val shapes = remember { ChromeShapes() }
    val source = remember { BenchSource(camera, shapes) }
    var pillsPlain by remember { mutableStateOf(false) }
    // The alpha of a layer over the map and its pills (the fade probe: whether the SurfaceView follows it).
    var fadeLayer by remember { mutableFloatStateOf(1f) }
    val chromeAlpha = remember { { fadeLayer } }
    val metrics = remember { WindowMetrics() }
    var refused by remember { mutableStateOf("") }
    var pillPhaseNs by remember { mutableLongStateOf(0L) }
    // The last run's report as recorded on disk, when that run never finished (a native crash, in its colour stage
    // perhaps): shown, and kept in front of the next run's lines, so Copy always carries it.
    var kept by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        val unfinished = withContext(Dispatchers.IO) { BenchReport.unfinished(context) }
        if (unfinished != null) {
            kept = VkBench.keptSection(unfinished.first, unfinished.second)
            lines += kept
        }
        val c = withContext(Dispatchers.Default) { MapCore.shared(config) }
        core = c
        val keptNote = if (unfinished == null) "" else "The last run ended in ${unfinished.first}; what it recorded is below (Copy). "
        if (c == null) {
            refused = MapCore.lastError()
            status = keptNote + VK_HONEST
            val facts = withContext(Dispatchers.Default) { MapCore.facts(config) }
            val said = listOf("vkbench refused: $refused") + facts.map { "vkfacts $it" }
            lines += said
            for (l in said) Log.i(EngineFrames.TAG, l)
            Log.i(EngineFrames.TAG, "vkbench done")
        } else {
            status = keptNote + "Ready. Quick takes about 4 minutes, Full about 20 (keep the phone unplugged for the battery figure). " +
                "Pills: start the phone's screen recorder first; 20 s of moving pills to film."
        }
    }
    val activity = context.findActivity()
    DisposableEffect(activity) {
        activity?.window?.let { metrics.attach(it) }
        onDispose { metrics.detach() }
    }
    // The app in the background (the screen stopped): the run pauses, out of its colour stage, drawing nothing and
    // calling the core for no new step, so that no crash-guard marker stays on disk for a process the system may freeze
    // and kill as it trims cached ones (with the SurfaceView keeping its surface from Android 14, the core's own rest
    // and its trim at TRIM_MEMORY_UI_HIDDEN end them too); it goes on when the app comes back.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var activeRun by remember { mutableStateOf<BenchRun?>(null) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> activeRun?.pause()
                Lifecycle.Event.ON_START -> activeRun?.resume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // The run: a coroutine through the plan; the screen shows its host and its pills.
    LaunchedEffect(chosen, core) {
        val c = core ?: return@LaunchedEffect
        val plan = chosen ?: return@LaunchedEffect
        val run = BenchRun(context, c, source, metrics, plan) { line ->
            lines += line
            Log.i(EngineFrames.TAG, line)
        }
        activeRun = run
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) run.pause()
        run.record = { step ->
            val text = VkBench.recorded(lines.toList(), step)
            withContext(Dispatchers.IO) { BenchReport.write(context, text) }
        }
        run.surfaceNow = { surfaceState }
        run.hostSwitch = { h ->
            if (host == h && surfaceState.attached) {
                surfaceState
            } else {
                val waiter = CompletableDeferred<VkSurfaceState>()
                attachWaiter = waiter
                host = h
                // Never a hang: a window that does not come (a refused attach brings no callback again, a phone left on
                // another app) ends the wait, and the step says why. Meanwhile the new view asks for a layout pass
                // each second (VkSurface's WindowNudge).
                withTimeoutOrNull(VkBench.WINDOW_WAIT_SECONDS * 1000L) { waiter.await() }
                    ?: VkSurfaceState(false, 0, 0, VkBench.windowTimedOut(h.label)).also {
                        Log.w(EngineFrames.TAG, "vkbench ${h.label}: ${it.error}")
                    }
            }
        }
        run.pills = { on, moving ->
            pillsOn = on
            pillsMoving = moving
            pillPhaseNs = System.nanoTime()
            source.pillsStartNs = pillPhaseNs
            source.pillsMoving = moving
        }
        run.plainPills = { plain -> pillsPlain = plain }
        run.fade = { alpha -> fadeLayer = alpha }
        run.status = { status = it }
        lines.clear()
        lines += kept
        try {
            run.execute(surfaceState)
        } finally {
            // Left in the colour stage (the screen went away): back to the frame's 8 bits, its guard stage closed.
            run.leaveColour()
            activeRun = null
        }
        status = "Done. Copy the report and paste it back."
        Log.i(EngineFrames.TAG, "vkbench done")
    }

    Column(modifier.fillMaxSize().background(Color.White)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            BasicText("VK bench: the stand-in and the map on this phone's Vulkan", style = TextStyle(fontSize = 15.sp))
            BasicText(status, style = TextStyle(fontSize = 12.sp, color = if (core == null && refused.isNotEmpty()) Color(0xFFB00020) else Color.Black))
            Row(Modifier.padding(vertical = 6.dp)) {
                if (chosen == null && core != null) {
                    Button("Quick") { chosen = VkBench.Length.QUICK }
                    Button("Full") { chosen = VkBench.Length.FULL }
                    Button("Pills") { chosen = VkBench.Length.PILLS }
                }
                if (lines.isNotEmpty()) {
                    Button("Copy") { copy(context, "Atlas VK bench", lines.joinToString("\n")) }
                    Button("Share") { share(context, "Atlas: VK bench", lines.joinToString("\n")) }
                }
            }
        }
        val c = core
        if (c != null) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).graphicsLayer { alpha = fadeLayer }) {
                val density = LocalDensity.current
                source.pillsOn = pillsOn
                // The core draws the pills' glass on the SurfaceView (unless the run asks for their tint alone), at the
                // alpha of the layer over them, as the app's chrome under a fading screen.
                SideEffect { shapes.coreDraws = coreDrawsGlass(host, pillsPlain) }
                CompositionLocalProvider(LocalChromeAlpha provides chromeAlpha) {
                    androidx.compose.runtime.key(host) {
                        TextureBackdrop(host == VkHost.TEXTURE) { layerModifier ->
                            VkSurface(c, host, source, camera, layerModifier.fillMaxSize().chromeSurface(shapes)) { s ->
                                surfaceState = s
                                source.widthPx = s.widthPx
                                source.heightPx = s.heightPx
                                source.density = density.density.toDouble()
                                if (s.attached || s.error.isNotEmpty()) attachWaiter?.complete(s)
                            }
                        }
                    }
                    if (pillsOn) Pills(host, pillsMoving, pillPhaseNs, shapes)
                }
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            for (l in lines) BasicText(l, style = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace))
        }
    }
}

/**
 * Whether the core draws the pills' glass: on the SurfaceView host, unless the run asks for their tint alone (the pill
 * check's plain case); on the TextureView host HWUI frosts them from the recorded window.
 */
internal fun coreDrawsGlass(host: VkHost, plain: Boolean): Boolean = host == VkHost.SURFACE && !plain

/** The pills' boxes in dp (top and bottom pills, as the home's chrome), shifted by [dx]. */
internal fun pillBoxes(widthDp: Float, heightDp: Float, dx: Float): List<FloatArray> = listOf(
    floatArrayOf(16f + dx, 40f, widthDp - 16f + dx, 92f),
    floatArrayOf(16f + dx, heightDp - 100f, widthDp - 16f + dx, heightDp - 48f),
)

/**
 * The pills' sideways offset (dp) at [t] seconds of their animation: in each 2 s cycle a second's move of 80 dp at an even
 * pace, one way in one cycle and back in the next, then a second at rest, so the film shows pills that stop mid-way.
 */
internal fun pillShift(t: Double): Float {
    if (t <= 0.0) return -40f
    val cycle = floor(t / 2.0)
    val way = if (cycle.toLong() % 2L == 0L) 1f else -1f
    return -40f * way + 80f * way * min(t - cycle * 2.0, 1.0).toFloat()
}

/** Whether the pills move at [t] seconds of their animation (the first second of each 2 s cycle). */
internal fun pillMoving(t: Double): Boolean = t > 0.0 && t - floor(t / 2.0) * 2.0 < 1.0

/**
 * The pills Compose draws: on the SurfaceView their tint only (the core draws the frosted glass under them, D5.1, from
 * [shapes], where each pill is placed as it is laid out); on the TextureView, HWUI's frosted glass from the recorded
 * window ([TextureBackdrop]) and the tint.
 */
@Composable
private fun Pills(host: VkHost, moving: Boolean, startNs: Long, shapes: ChromeShapes) {
    var shift by remember { mutableStateOf(0f) }
    LaunchedEffect(moving, startNs) {
        if (!moving) {
            shift = 0f
            return@LaunchedEffect
        }
        while (true) withFrameNanos { now -> shift = pillShift((now - startNs) / 1e9) }
    }
    val density = LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxSize()) {
        for (b in pillBoxes(maxWidth.value, maxHeight.value, shift)) {
            val left = b[0] * density
            val top = b[1] * density
            Box(
                Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .width((b[2] - b[0]).dp).height((b[3] - b[1]).dp)
                    .chromeShape(shapes, RoundedCornerShape(26.dp), 20.dp, 1.8f, frosted = host == VkHost.SURFACE)
                    .clip(RoundedCornerShape(26.dp))
                    .then(if (host == VkHost.TEXTURE) Modifier.frostedFromBackdrop(left, top) else Modifier)
                    .background(Color(0x99FFFFFF)),
            )
        }
    }
}

/** The recorded TextureView (HWUI's frosted glass blurs it behind the pills), or nothing for the SurfaceView. */
private val backdropLayer = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.ui.graphics.layer.GraphicsLayer?> { null }

@Composable
private fun TextureBackdrop(record: Boolean, content: @Composable (Modifier) -> Unit) {
    if (!record) {
        content(Modifier)
        return
    }
    val layer = rememberGraphicsLayer()
    androidx.compose.runtime.CompositionLocalProvider(backdropLayer provides layer) {
        content(Modifier.drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        })
    }
}

/**
 * HWUI's frosting, as the app's Backdrop draws it: the recorded window behind the pill at ([leftPx], [topPx]), blurred
 * with σ 20 dp (Android's radius r is σ = 0.57735 r + 0.5) and drawn inside it, under the tint.
 */
@Composable
private fun Modifier.frostedFromBackdrop(leftPx: Float, topPx: Float): Modifier {
    val layer = backdropLayer.current ?: return this
    if (Build.VERSION.SDK_INT < 31) return this
    val glass = rememberGraphicsLayer()
    return this.drawWithContent {
        val sigma = 20.dp.toPx()
        val radius = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.01f)
        glass.renderEffect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP).asComposeRenderEffect()
        glass.record { translate(-leftPx, -topPx) { drawLayer(layer) } }
        drawLayer(glass)
        drawContent()
    }
}

/**
 * The core's camera source while the bench runs (on the render thread, allocating nothing): the scripted path's camera
 * at each vsync, over the stand-in or, in the map stage, about the app's map's view ([mapView]) with the pack the app
 * would draw there ([packs]' choice, as MapController names it); and the pills as the chrome's keep-out shapes
 * ([shapes], as Compose placed them; frosted by the core on the SurfaceView). It counts the frames presented and how
 * many of them were complete pictures (the map's: every tile its cover wants drawn).
 */
internal class BenchSource(private val state: CameraState, private val shapes: ChromeShapes) : FrameSource {
    @Volatile var running = false

    /**
     * The app is in the background: no frame (the core's frame loop rests: its crash guard's frames stage ends, and
     * VK bench's colour stage is left).
     */
    @Volatile var paused = false
    @Volatile var path = VkBench.Path.STILL
    @Volatile var view = VkBench.View.BOARD
    @Volatile var periodSeconds = 30.0
    @Volatile var startNs = 0L
    @Volatile var widthPx = 0
    @Volatile var heightPx = 0
    @Volatile var density = 1.0
    @Volatile var pillsOn = true
    @Volatile var pillsMoving = false
    @Volatile var pillsStartNs = 0L
    @Volatile var presentedFrames = 0L

    /** The map stage's view (its paths are drawn about it), or null for the stand-in. */
    @Volatile var mapView: VkBench.MapView? = null

    /** The packs the map stage handed the core: each frame names the one the app would draw for its camera. */
    @Volatile var packs: PackList? = null

    /**
     * Frames presented, and of them complete pictures; when the last complete one was presented (System.nanoTime), and
     * its place among the frames presented ([presentedCount] then).
     */
    @Volatile var presentedCount = 0L
    @Volatile var completeCount = 0L
    @Volatile var completeAtNs = 0L
    @Volatile var completeIndex = 0L
    private val cam = VkBench.Camera()

    override fun camera(vsyncNanos: Long, out: ByteBuffer): Boolean {
        val w = widthPx
        val h = heightPx
        if (!running || paused || w <= 0 || h <= 0) return false
        val d = density
        val t = (vsyncNanos - startNs) / 1e9
        val map = mapView
        if (map != null) VkBench.mapCameraAt(path, map, t, periodSeconds, cam) else VkBench.cameraAt(path, view, t, periodSeconds, cam)
        state.write(out, 0, cam.latitude, cam.longitude, cam.bearing, cam.pitch, cam.distance, w / d / 2, h / d * 0.48, w, h, d)
        if (map != null) {
            val pack = packs?.choose(cam.latitude, cam.longitude, cam.distance)
            CameraWriter.pack(out, pack?.number ?: 0L, pack?.profile ?: 0)
        }
        // The pills as Compose last placed them (none while they are off), carried on to this vsync while they move.
        if (pillsOn) shapes.write(out, vsyncNanos) else CameraWriter.keepOuts(out, 0)
        return true
    }

    override fun presented(frame: Long, nanos: Long, complete: Boolean) {
        presentedFrames = frame
        val n = presentedCount + 1
        presentedCount = n
        if (complete) {
            completeCount++
            completeAtNs = System.nanoTime()
            completeIndex = n
        }
    }
}

/** The window's frames as HWUI reports them (`FrameMetrics`): vsync times, GPU durations, lateness. */
internal class WindowMetrics {
    private val lock = Any()
    private val vsync = LongArray(4096)
    private val gpu = LongArray(4096)
    private val late = BooleanArray(4096)
    private var count = 0L
    private var window: Window? = null
    private var thread: HandlerThread? = null
    private val listener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
        val v = m.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
        val g = if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.GPU_DURATION) else -1L
        val total = m.getMetric(FrameMetrics.TOTAL_DURATION)
        val deadline = if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.DEADLINE) else 16_666_667L
        synchronized(lock) {
            val i = (count % vsync.size).toInt()
            vsync[i] = v
            gpu[i] = g
            late[i] = total > deadline
            count++
        }
    }

    fun attach(w: Window) {
        val t = HandlerThread("atlas-frame-metrics").also { it.start() }
        thread = t
        window = w
        w.addOnFrameMetricsAvailableListener(listener, Handler(t.looper))
    }

    fun detach() {
        runCatching { window?.removeOnFrameMetricsAvailableListener(listener) }
        thread?.quitSafely()
        window = null
    }

    /** The frames since [fromNs]: each one's vsync time (ns) and whether it was late, oldest first. */
    fun frames(fromNs: Long): Pair<LongArray, BooleanArray> = synchronized(lock) {
        val n = minOf(count, vsync.size.toLong()).toInt()
        val first = count - n
        val times = ArrayList<Long>()
        val lates = ArrayList<Boolean>()
        for (k in 0 until n) {
            val i = ((first + k) % vsync.size).toInt()
            if (vsync[i] < fromNs) continue
            times += vsync[i]
            lates += late[i]
        }
        Pair(times.toLongArray(), lates.toBooleanArray())
    }

    /** The frames since [fromNs]: their vsync intervals (ms), HWUI's GPU milliseconds, and how many were late. */
    fun since(fromNs: Long): Triple<DoubleArray, DoubleArray, Int> = synchronized(lock) {
        val n = minOf(count, vsync.size.toLong()).toInt()
        val first = count - n
        val times = ArrayList<Long>()
        val gpus = ArrayList<Double>()
        var lateFrames = 0
        for (k in 0 until n) {
            val i = ((first + k) % vsync.size).toInt()
            if (vsync[i] < fromNs) continue
            times += vsync[i]
            if (gpu[i] >= 0) gpus += gpu[i] / 1e6
            if (late[i]) lateFrames++
        }
        val intervals = DoubleArray(maxOf(0, times.size - 1)) { (times[it + 1] - times[it]) / 1e6 }
        Triple(intervals, gpus.toDoubleArray(), lateFrames)
    }
}

/** The bench's steps, run in order by [execute]. */
private class BenchRun(
    private val context: Context,
    private val core: MapCore,
    private val source: BenchSource,
    private val metrics: WindowMetrics,
    private val plan: VkBench.Length,
    private val emit: (String) -> Unit,
) {
    var hostSwitch: suspend (VkHost) -> VkSurfaceState = { VkSurfaceState(false, 0, 0) }

    /** The window as it is now (the screen's state of it), read at each step of the colour stage. */
    var surfaceNow: () -> VkSurfaceState = { VkSurfaceState(false, 0, 0) }
    var pills: (Boolean, Boolean) -> Unit = { _, _ -> }

    /** Draws the pills with their tint alone (the core draws no glass under them: the pill check's plain case). */
    var plainPills: (Boolean) -> Unit = {}

    /** Puts the map and its pills under a layer of this alpha (the fade probe; 1: none). */
    var fade: (Float) -> Unit = {}
    var status: (String) -> Unit = {}

    /** Records the report on disk as the bench reaches a step ([VkBench.recorded]), before anything of it is drawn. */
    var record: suspend (String) -> Unit = {}
    private val records = FrameRecords.allocate(512)
    private val targetMs = 1000.0 / 60.0
    private val gpuByCell = HashMap<String, Double>()

    /** The facts' own pick, which the core draws after the matrix (and leaves the colour stage in). */
    private var pickCode = 2
    private var pickRoute = 0
    private var pickForm = 0
    private var pickTarget = 0

    /** Whether the last bench call asked for 10 bits (the colour stage), so that [leaveColour] has something to undo. */
    private var inColour = false

    /** The app is in the background (its screen stopped): nothing of the run reaches the core until it is back. */
    private var paused = false

    /** How many times the app went to the background in this run: a measurement it cut short is made again. */
    private var stops = 0
    private var resumed: CompletableDeferred<Unit>? = null

    /** When the app last went to the background (System.nanoTime), and how long it was away in all (the soak's line). */
    private var pausedAtNs = 0L
    private var awayNs = 0L

    /**
     * The app went to the background (ON_STOP; main thread, as every step): the core leaves the colour stage if the run
     * is in it (mode 0: its 10-bit frame, output and cache go, the stage closes, and no swapchain is made in their place)
     * and draws nothing until the app comes back (the frame source refuses every frame, so the core's frame loop rests
     * and its frames stage ends), and the run's next call of the core waits for the app ([awaitApp]); a call already
     * under way (the facts, the pipelines' timing) finishes. No crash-guard marker then stays on disk for a process the
     * system may freeze and kill as it trims cached ones, and nothing measured while it is away counts.
     */
    fun pause() {
        if (paused) return
        paused = true
        ++stops
        pausedAtNs = System.nanoTime()
        source.paused = true
        leaveColour()
    }

    /** The app came back (ON_START): the run goes on where it was. */
    fun resume() {
        if (!paused) return
        paused = false
        awayNs += System.nanoTime() - pausedAtNs
        source.paused = false
        resumed?.complete(Unit)
        resumed = null
        core.requestFrame()
    }

    /** Waits for the app to come back, at most [VkBench.WINDOW_WAIT_SECONDS] (the colour stage's wait): whether it did. */
    private suspend fun awaitResumed(): Boolean {
        if (!paused) return true
        val waiter = CompletableDeferred<Unit>()
        resumed = waiter
        return withTimeoutOrNull(VkBench.WINDOW_WAIT_SECONDS * 1000L) { waiter.await() } != null
    }

    /**
     * Before [step] reaches the core (the facts, a configuration, a host's window, the pipelines' timing, a
     * measurement): while the app is in the background the run waits for it, however long it is away, saying so, so
     * that no new step of the run is made, drawn or measured there; once it is back, for [host]'s window, which a
     * stopped app's SurfaceView or TextureView may have let go of (at most [VkBench.WINDOW_WAIT_SECONDS]). The colour
     * stage waits at most [VkBench.WINDOW_WAIT_SECONDS] for the app instead, then ends there ([awaitResumed]).
     */
    private suspend fun awaitApp(step: String, host: VkHost? = null) {
        while (paused) {
            emit(VkBench.appAway(step))
            while (paused) {
                val waiter = CompletableDeferred<Unit>()
                resumed = waiter
                waiter.await()
            }
            emit(VkBench.appBack())
            if (host != null) hostSwitch(host)
        }
    }

    /** [MapCore.bench] for [step], once the app is in the foreground with [host]'s window, if any ([awaitApp]). */
    private suspend fun bench(
        step: String, host: VkHost?, on: Boolean, config: Int, route: Int, form: Int, target: Int, pills: Boolean, texture: Boolean,
    ): String {
        awaitApp(step, host)
        return core.bench(on, config, route, form, target, pills = pills, texture = texture)
    }

    /**
     * [h]'s window for [step], once the app is in the foreground ([awaitApp]); waited for once more when the app went to
     * the background meanwhile and none came (a stopped app's window cannot).
     */
    private suspend fun switchHost(h: VkHost, step: String): VkSurfaceState {
        awaitApp(step)
        val stopsBefore = stops
        val s = hostSwitch(h)
        if (s.attached || stops == stopsBefore) return s
        awaitApp(step)
        return hostSwitch(h)
    }

    suspend fun execute(initial: VkSurfaceState) {
        awaitApp("the start")
        val facts = withContext(Dispatchers.Default) { core.facts() }
        fun fact(name: String) = facts.firstOrNull { it.startsWith("$name: ") }?.substringAfter(": ") ?: "?"
        emit("vkbench start: ${plan.name.lowercase()} bench, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        for (k in listOf("gpu", "vendor", "api", "driver", "driver.version", "hardware", "tiler", "lazily.allocated.memory", "config", "route", "composite",
                         "configs.available", "depth.stencil", "samples", "feature.shaderFloat64", "profiles", "quirks", "guard", "pipeline.cache")) emit("vkfacts $k: ${fact(k)}")
        val available = buildSet {
            val line = fact("configs.available")
            if (line.contains("A yes")) add(0)
            if (line.contains("B yes")) add(1)
            if (line.contains("C yes")) add(2)
            if (line.contains("1x yes")) add(4)
        }
        val dynamic = fact("feature.dynamicRenderingLocalRead") == "yes" && fact("feature.dynamicRendering") == "yes"
        val tiler = fact("tiler").startsWith("yes")

        // The configuration the core picks, for everything after the matrix.
        val pick = facts.firstOrNull { it.startsWith("config: ") }?.substringAfter(": ")?.substringBefore(" ") ?: "C"
        pickCode = VkBench.CONFIG_NAMES.indexOf(pick).coerceAtLeast(0)
        pickRoute = if (fact("route").startsWith("dynamic")) 1 else 0
        pickForm = if (fact("form").startsWith("onepass")) 1 else 0
        pickTarget = if (fact("composite").startsWith("direct")) 1 else 0

        // 1. The matrix, on the SurfaceView with the pills on, at the frame's 8 bits.
        var surface = if (initial.attached && !paused) initial else switchHost(VkHost.SURFACE, "the matrix")
        pills(true, false)
        if (!surface.attached) {
            emit("vkbench surface: the SurfaceView did not attach (${surface.error})")
            return
        }
        // The frame's colour on this surface, and what the colour stage will draw (10 bits a channel where the surface
        // offers them in sRGB and the crash guard has not turned them off), now that it is attached.
        awaitApp("the matrix", VkHost.SURFACE)
        val surfaced = withContext(Dispatchers.Default) { core.facts() }
        fun surfaceFact(name: String) = surfaced.firstOrNull { it.startsWith("$name: ") }?.substringAfter(": ") ?: "?"
        for (k in listOf("colour", "colour.stage", "colour.formats", "surface.formats")) emit("vkfacts $k: ${surfaceFact(k)}")
        val stage = VkBench.colourStage(surfaceFact("colour.stage"))
        if (plan == VkBench.Length.PILLS) {
            // The pills alone, for the screen recorder: the film shows whether each pill and its frosted glass move in
            // the same frame. Then still, frosted and with their tint alone, for CI's screen captures.
            status("the pills moving for ${plan.pillSeconds.roundToInt()} s: film them with the screen recorder")
            pillPhase(filmed = true)
            stills()
            fadeProbe()
            source.running = false
            bench("the end", null, false, pickCode, pickRoute, pickForm, pickTarget, pills = false, texture = false)
            return
        }
        val matrix = VkBench.matrix(available, dynamic, tiler)
        status("1/6: ${matrix.size} configurations × 2 views")
        for (view in VkBench.View.entries) {
            for (cfg in matrix) {
                val cell = "${view.label} ${cfg.name}"
                val why = bench(cell, VkHost.SURFACE, true, cfg.config, cfg.route, cfg.form, cfg.target, pills = true, texture = false)
                if (why.isNotEmpty()) {
                    // A cell the crash guard turned off (the driver crashed in it) is skipped, saying so, as one the
                    // device cannot draw is.
                    emit(VkBench.skippedLine(cell, why))
                    continue
                }
                val s = measure(cell, VkBench.Path.STILL, view, plan.cellSeconds, VkHost.SURFACE, tenBit = false)
                gpuByCell[cell] = s.gpuP50
                emit(VkBench.line(cell, s, graphicsMb(), nativeMb()))
            }
        }

        // 2. Both hosts with the pills, under the scripted paths, in the configuration the core picks.
        record("the hosts")
        status("2/6: both hosts × 4 paths")
        val hostP95 = HashMap<VkHost, Double>()
        for (h in VkHost.entries) {
            surface = switchHost(h, "the ${h.label} host")
            if (!surface.attached) {
                emit("vkbench ${h.label}: did not attach (${surface.error})")
                continue
            }
            val why = bench("the ${h.label} host", h, true, pickCode, pickRoute, pickForm, pickTarget, pills = h == VkHost.SURFACE, texture = h == VkHost.TEXTURE)
            if (why.isNotEmpty()) {
                emit("vkbench ${h.label}: unavailable ($why)")
                continue
            }
            var worst = 0.0
            for (path in listOf(VkBench.Path.PAN, VkBench.Path.PINCH, VkBench.Path.ORBIT, VkBench.Path.TILT)) {
                val s = measure("${h.label} ${path.label}", path, VkBench.View.BOARD, plan.pathSeconds, h)
                worst = maxOf(worst, s.presentedP95.takeUnless { it.isNaN() } ?: 0.0)
                emit(VkBench.line("${h.label} ${path.label}", s, graphicsMb(), nativeMb()))
            }
            hostP95[h] = worst
        }

        // 3. The app's map at its real views, in the configuration the facts pick.
        record("the map")
        status("3/6: the map at its real views")
        mapStage()

        // 4. The pills moving over the SurfaceView.
        record("the pills")
        status("4/6: the pills over the SurfaceView")
        pillPhase(filmed = false)

        // 5. The soak, in the picked configuration on the SurfaceView.
        if (plan.soakSeconds > 0) {
            record("the soak")
            status("5/6: the soak (${(plan.soakSeconds / 60).roundToInt()} minutes)")
            soak()
        }

        // First-launch and warm pipeline creation, in the picked configuration on the SurfaceView: this launch's first
        // pipelines, then the same made again from an empty cache and from the cache file.
        record("the pipelines' timing")
        status("pipelines: from an empty cache and from the cache file")
        surface = switchHost(VkHost.SURFACE, "the pipelines' timing")
        if (surface.attached && bench("the pipelines' timing", VkHost.SURFACE, true, pickCode, pickRoute, pickForm, pickTarget, pills = true, texture = false).isEmpty()) {
            emit(timePipelines())
        } else {
            emit("vkbench pipelines: the SurfaceView did not attach (${surface.error})")
        }

        // The A0 decisions the numbers point to (T2's rule and D5.3, D5.4).
        val viewKey = VkBench.View.BOARD.label
        val composite = gpuByCell["$viewKey ${VkBench.Config(pickCode, pickRoute, 0, pickTarget).name}"] ?: Double.NaN
        val onepass = gpuByCell["$viewKey ${VkBench.Config(pickCode, pickRoute, 1, 0).name}"] ?: Double.NaN
        emit("a0 onepass: ${VkBench.onepassVerdict(composite, onepass)}")
        val best = gpuByCell.filterKeys { it.startsWith("pitch70 ") && !it.contains("onepass") && !it.startsWith("pitch70 1x") }
            .minByOrNull { it.value }
        emit("a0 configuration: the facts pick $pick; at pitch 70° the cheapest composite is ${best?.key?.removePrefix("pitch70 ") ?: "—"} (${VkBench.ms(best?.value ?: Double.NaN)} ms GPU)")
        val routes = listOf(0, 1).associateWith { r -> gpuByCell["$viewKey ${VkBench.Config(pickCode, r, 0, if (r == 1) 0 else pickTarget).name}"] }
        emit("a0 route: renderpass ${VkBench.ms(routes[0] ?: Double.NaN)} ms, dynamic ${VkBench.ms(routes[1] ?: Double.NaN)} ms GPU at the board's view")
        emit("a0 host: SurfaceView p95 ${VkBench.ms(hostP95[VkHost.SURFACE] ?: Double.NaN)} ms, TextureView p95 ${VkBench.ms(hostP95[VkHost.TEXTURE] ?: Double.NaN)} ms (worst path)")

        // 6. The colour stage: everything above is recorded first, so a driver's crash at 10 bits costs none of it. Each
        // configuration that drew at the board's view, at 10 bits and again at 8 beside it (the same warmth, after the
        // soak), on the SurfaceView; the core opens the crash guard's colour stage for the 10-bit frames.
        record("the colour stage")
        val pickCell = "$viewKey ${VkBench.Config(pickCode, pickRoute, pickForm, pickTarget).name}"
        val tenByCell = HashMap<String, VkBench.Summary>()
        val eightByCell = HashMap<String, VkBench.Summary>()
        val format = stage.format
        // Why the core refused the first 10-bit frame: it asks the device about 10 bits only as the stage begins, so
        // a device that cannot draw the frame in them says so here, not in the facts read before.
        var tenRefused: String? = null
        if (stage.off) {
            emit(VkBench.colourSkipped(stage))
        } else {
            // Where the surface offers no 10 bits the stage still runs, each configuration at 8 bits alone, saying so.
            val cells = matrix.filter { "$viewKey ${it.name}" in gpuByCell }
            status(
                if (format != null) "6/6: the colour stage, ${cells.size} configurations at 10 bits and at 8 beside them"
                else "6/6: the colour stage, ${cells.size} configurations at 8 bits (no 10 bits here)",
            )
            awaitApp("the colour stage") // the stage begins with the app in the foreground
            emit(VkBench.colourStageLine(stage))
            hostSwitch(VkHost.SURFACE)
            val unavailable = HashSet<String>()
            val steps = VkBench.colourSteps(cells, ten = format != null)
            var next = 0
            var again = -1 // the step measured again after the app's going away cut it short (once a step)
            while (next < steps.size) {
                val step = steps[next]
                val cell = "$viewKey ${step.config.name}"
                if (cell in unavailable) {
                    next++
                    continue
                }
                val c = step.config
                // The app in the background: the core left the colour stage as it went (pause), and the stage waits
                // for the app to come back, at most a minute, as for a window; one that does not ends the stage.
                if (paused) {
                    emit(VkBench.colourAppAway(cell))
                    if (!awaitResumed()) {
                        emit(VkBench.colourAppGone())
                        break
                    }
                    emit(VkBench.colourAppBack())
                }
                // The window as it is now, not as the stage found it: on Android 10 to 13 the SurfaceView's goes with
                // an app switch or the screen off, and the core has nothing to draw into until it comes back. The wait
                // ends: a window that does not come back ends the stage.
                if (!surfaceNow().attached) {
                    emit("vkbench colour: the window went away before $cell; the stage waits for it (at most ${VkBench.WINDOW_WAIT_SECONDS} s)")
                    surface = hostSwitch(VkHost.SURFACE)
                    if (!surface.attached) {
                        emit("vkbench colour: the window did not come back (${surface.error.ifEmpty { "the SurfaceView did not attach" }}); the colour stage ends here")
                        break
                    }
                    emit("vkbench colour: the window came back")
                    // The app gone to the background again while its window was awaited: the step waits for it first.
                    if (paused) continue
                }
                inColour = step.tenBit
                val why = core.bench(true, c.config, c.route, c.form, c.target, pills = true, texture = false, tenBit = step.tenBit)
                if (why.isNotEmpty()) {
                    emit("vkbench colour $cell: ${if (step.tenBit) format else "rgba8"} unavailable ($why)")
                    unavailable += cell
                    if (step.tenBit && tenRefused == null) tenRefused = why
                    next++
                    continue
                }
                val stopsBefore = stops
                val s = measureOnce(VkBench.Path.STILL, VkBench.View.BOARD, plan.cellSeconds, VkHost.SURFACE, tenBit = step.tenBit)
                if (stops != stopsBefore && again != next) {
                    // Cut short by the app's going away (the core left the stage then): measured again once it is back.
                    emit(VkBench.colourCutShort(cell, if (step.tenBit) format ?: "rgba8" else "rgba8"))
                    again = next
                    continue
                }
                (if (step.tenBit) tenByCell else eightByCell)[cell] = s
                val ten = tenByCell[cell]
                val eight = eightByCell[cell]
                if (format == null && eight != null) {
                    emit(VkBench.colourEightLine(cell, eight, stage.why))
                } else if (format != null && ten != null && eight != null) {
                    emit(VkBench.colourLine(cell, format, ten, eight))
                }
                next++
            }
            leaveColour()
        }
        val refused = tenRefused
        val drawn = if (tenByCell.isEmpty() && refused != null) VkBench.ColourStage(null, off = false, why = refused) else stage
        emit(VkBench.a0Colour(drawn, tenByCell[pickCell], eightByCell[pickCell]))
        source.running = false
        record(VkBench.COMPLETE)
        // Every result is recorded: the bench's frame goes, once the app is in the foreground, as every call of the run.
        bench("the end", null, false, pickCode, pickRoute, pickForm, pickTarget, pills = false, texture = false)
    }

    /**
     * Back to the frame's 8 bits after the colour stage (or a run cut short in it): mode 0, whose 10-bit frame and output
     * go and whose guard stage closes, nothing made in their place (the next step makes its output).
     */
    fun leaveColour() {
        if (!inColour) return
        inColour = false
        core.bench(false, pickCode, pickRoute, pickForm, pickTarget, pills = false, texture = false)
    }

    /**
     * The app's map (ENGINE.md step 2: VK bench runs the real paths): the core's map over the APK's bundled packs, drawn as
     * the app draws it (the facts' own configuration, the sunny moment, the haze and the vignette as the Look settings
     * have them, the painter's order as VK facts left it; no labels) under scripted paths at its real views
     * ([VkBench.mapViews]): the Westerkerk home still, turning, panning and pinched out to 8 km and back, then the Main
     * board and its pitch of 70°, turning. Before a view's first path the run waits, at most
     * [VkBench.MAP_WAIT_SECONDS], for its complete picture: the first, at the home just after the map was turned on, is
     * the map's first picture, its tiles built from the packs. Each path's line ends with how many of its frames were
     * complete pictures. The map goes off after (the next step's bench call takes the window back).
     */
    private suspend fun mapStage() {
        val surface = switchHost(VkHost.SURFACE, "the map")
        if (!surface.attached) {
            emit("vkbench map: the SurfaceView did not attach (${surface.error})")
            return
        }
        val packs = MapEngine.packs(context.assets)
        withContext(Dispatchers.IO) { runCatching { packs.load() } }
        // the canal ring draws the Westerkerk home too (the APK lists it alone): the app's home, in the living map's frame
        // (HomeView.westerkerk)
        val canal = packs.packs.firstOrNull { it.id == VkBench.BOARD_PACK }
        val views = VkBench.mapViews(canal?.let { RegionFrame.living() }, canal?.frame, canal?.views?.get(VkBench.BOARD_VIEW))
        if (views.isEmpty()) {
            emit("vkbench map: unavailable (no bundled packs in this build)")
            return
        }
        awaitApp("the map", VkHost.SURFACE)
        val moment = "sunny"
        val (said, lit) = withContext(Dispatchers.Default) {
            val (refs, open) = packs.refs()
            val opened = try {
                core.packs(refs)
            } finally {
                for (r in open) runCatching { r.close() }
            }
            val theme = ThemeBlock.allocate()
            val lit = runCatching {
                CoreMoment.write(theme, Moment.Named(moment), canal?.zone ?: ZoneId.of("Europe/Amsterdam"), MapEngine.options.daylight)
                core.moment(theme)
            }.getOrElse { "the moment could not be made: ${it.message ?: it}" }
            opened to lit
        }
        emit(VkBench.mapHead(said.lines().filter { it.isNotBlank() }.joinToString(", "), moment, MapEngine.haze, MapEngine.vignette, MapEngine.painter))
        if (lit.isNotEmpty()) {
            emit("vkbench map: unavailable ($lit)")
            return
        }
        core.painterOrder(MapEngine.painter)
        core.screenEffects(MapEngine.haze, MapEngine.vignette, MapEngine.grain)
        source.packs = packs
        val on = core.map(true, texture = false)
        try {
            if (on.isNotEmpty()) {
                emit("vkbench map: unavailable ($on)")
                return
            }
            for ((i, view) in views.withIndex()) {
                emit(VkBench.mapPictureLine(view.label, awaitPicture(view), first = i == 0))
                for (path in view.paths) {
                    val label = "map ${view.label} ${path.label}"
                    val presented = source.presentedCount
                    val complete = source.completeCount
                    val s = measure(label, path, VkBench.View.BOARD, plan.pathSeconds, VkHost.SURFACE, map = view)
                    val extra = "complete ${source.completeCount - complete} of ${source.presentedCount - presented}"
                    emit(VkBench.line(label, s, graphicsMb(), nativeMb(), extra))
                }
            }
        } finally {
            source.running = false
            source.mapView = null
            source.packs = null
            core.map(false)
        }
    }

    /**
     * The map standing at [view] (swaying slightly) until a complete picture of it is presented, at most
     * [VkBench.MAP_WAIT_SECONDS] (the app's going to the background meanwhile adds its time away): the seconds it took,
     * or NaN. A picture counts once more frames were presented than the core keeps in flight, so none drawn before (the
     * last view's, the stand-in's) is taken for it.
     */
    private suspend fun awaitPicture(view: VkBench.MapView): Double {
        awaitApp("the map's ${view.label}", VkHost.SURFACE)
        val base = source.presentedCount
        val from = System.nanoTime()
        source.mapView = view
        source.path = VkBench.Path.STILL
        source.periodSeconds = 4.0
        source.startNs = from
        source.running = true
        val away = awayNs
        while (System.nanoTime() - from - (awayNs - away) < VkBench.MAP_WAIT_SECONDS * 1_000_000_000L) {
            if (!paused) core.requestFrame()
            delay(50)
            if (source.completeIndex > base + VkBench.IN_FLIGHT && !paused) return (source.completeAtNs - from - (awayNs - away)) / 1e9
        }
        return Double.NaN
    }

    /**
     * The pills moving over the SurfaceView in the picked configuration: how many of the core's frames reach the
     * display on their vsync (display timing) and how many of the window's frames are late; a pill and its frosted
     * glass drift apart when either is. [filmed]: the run is the pills-only one, filmed by the phone's screen recorder.
     */
    private suspend fun pillPhase(filmed: Boolean) {
        val surface = switchHost(VkHost.SURFACE, "the pills")
        val why = if (surface.attached) bench("the pills", VkHost.SURFACE, true, pickCode, pickRoute, pickForm, pickTarget, pills = true, texture = false) else surface.error
        if (why.isNotEmpty()) {
            emit("vkbench pills: unavailable ($why)")
            return
        }
        pills(true, true)
        val startNs = source.pillsStartNs
        val s = measure("pills", VkBench.Path.STILL, VkBench.View.BOARD, plan.pillSeconds, VkHost.SURFACE, keep = true)
        val onTime = kept.count { it.presented && VkBench.onTime(it.presentedNs, it.desiredNs, 16_666_667L) }
        val presented = kept.count { it.presented }
        val (uiIntervals, _, lateUi) = metrics.since(measureStartNs)
        // While the pills move, a core frame shares its frame with its pill when it reached the display on its vsync and
        // the window's frame of that vsync was drawn on time too (the pill's tint moved in it).
        val (uiVsyncs, uiLate) = metrics.frames(measureStartNs)
        var moving = 0
        var same = 0
        for (r in kept) {
            if (!r.presented || !pillMoving((r.vsyncNs - startNs) / 1e9)) continue
            moving++
            if (VkBench.onTime(r.presentedNs, r.desiredNs, 16_666_667L) && VkBench.drawnOnTime(uiVsyncs, uiLate, r.vsyncNs)) same++
        }
        val frosted = kept.filter { it.gpuValid }.map { it.gpuAfterMs.toDouble() }.toDoubleArray()
        emit("vkbench pills: core frames on their vsync ${if (presented == 0) "— (no display timing)" else "$onTime of $presented"}, " +
            "window frames late $lateUi of ${uiIntervals.size + 1}; " +
            "same frame while moving ${if (moving == 0) "— (no display timing)" else "$same of $moving"} " +
            "(the moving check is the phone's: an emulator's software Vulkan draws far slower than its display, and CI " +
            "judges the still pills alone); frosted pass gpu ${VkBench.ms(VkBench.percentile(frosted, frosted.size, 50.0))} " +
            "(p95 ${VkBench.ms(VkBench.percentile(frosted, frosted.size, 95.0))}); p95 ${VkBench.ms(s.presentedP95)}; " +
            if (filmed) "filmed: by the screen recorder (step through the video frame by frame, or ci/pillcheck.py film)" else "filmed: in the Pills run")
        pills(true, false)
    }

    /**
     * The pills still for [VkBench.PILL_STILL_MS] frosted, then as long with their tint alone (no glass from the core), each announced
     * by a `vkbench pills still: …` line: CI captures the screen in each, and ci/pillcheck.py must pass the first and
     * fail the second.
     */
    private suspend fun stills() {
        if (!surfaceNow().attached) return
        pills(true, false)
        // The camera without the still path's sway: the captures seconds apart (frosted, plain, the fade probe) show the
        // same map, so the fade probe's fit compares like with like.
        source.path = VkBench.Path.STILL
        source.periodSeconds = 0.0
        for (plain in listOf(false, true)) {
            plainPills(plain)
            emit("vkbench pills still: ${if (plain) "plain tint" else "frosted"}")
            hold(VkBench.PILL_STILL_MS)
        }
        plainPills(false)
        emit("vkbench pills still: done")
    }

    /**
     * The still map and its frosted pills under a layer of alpha 0.5 for [VkBench.PILL_STILL_MS], announced by
     * `vkbench fade: …` lines: whether this Android draws the SurfaceView at the alpha of a layer over it, as a screen's
     * fade (NavDisplay's) asks of it. CI captures it and ci/pillcheck.py's fade judges it against the still frosted
     * capture (fade.txt, for the emulator's API level). The pills' glass takes the layer's alpha either way.
     */
    private suspend fun fadeProbe() {
        if (!surfaceNow().attached) return
        fade(0.5f)
        emit("vkbench fade: the map under a layer of alpha 0.5 (API ${Build.VERSION.SDK_INT})")
        hold(VkBench.PILL_STILL_MS)
        fade(1f)
        emit("vkbench fade: done")
    }

    /** Holds the screen still for [ms], a frame asked for every tenth of a second while the app is in front. */
    private suspend fun hold(ms: Long) {
        val until = System.nanoTime() + ms * 1_000_000L
        while (System.nanoTime() < until) {
            if (!paused) core.requestFrame()
            delay(100)
        }
    }

    /**
     * The pipelines' timing ([MapCore.pipelines]: the configuration's pipelines made again by stand-ins of their own,
     * from an empty cache and from the cache file), once the app is in the foreground; taken again once when the app
     * went to the background while it ran (a call already under way finishes in the background, and a process there
     * runs slower).
     */
    private suspend fun timePipelines(): String {
        awaitApp("the pipelines' timing", VkHost.SURFACE)
        val stopsBefore = stops
        val line = withContext(Dispatchers.Default) { core.pipelines() }
        if (stops == stopsBefore) return line
        emit(VkBench.cutShort("pipelines"))
        awaitApp("the pipelines' timing", VkHost.SURFACE)
        return withContext(Dispatchers.Default) { core.pipelines() }
    }

    private var kept: List<FrameRecords.Record> = emptyList()

    /** When the last measurement began, its warm-up included (System.nanoTime): the window's frames since then. */
    private var measureStartNs = 0L

    /**
     * [measureOnce], once the app is in the foreground with [host]'s window ([awaitApp]): [label] names it. Cut short by
     * the app's going to the background (nothing is drawn while it is away), it is measured again once, when the app
     * and the window are back, so that nothing measured in the background is a result.
     */
    private suspend fun measure(
        label: String, path: VkBench.Path, view: VkBench.View, seconds: Double, host: VkHost, keep: Boolean = false, tenBit: Boolean? = null,
        map: VkBench.MapView? = null,
    ): VkBench.Summary {
        awaitApp(label, host)
        val stopsBefore = stops
        val s = measureOnce(path, view, seconds, host, keep, tenBit, map)
        if (stops == stopsBefore) return s
        emit(VkBench.cutShort(label))
        awaitApp(label, host)
        hostSwitch(host) // its window back, should the app have let go of it
        return measureOnce(path, view, seconds, host, keep, tenBit, map)
    }

    /**
     * Runs [path] at [view] (about the app's map's [map] view in the map stage) for [seconds] (after half a second of
     * warm-up) and summarises its frames; with [tenBit], only the frames drawn at that colour depth.
     */
    private suspend fun measureOnce(
        path: VkBench.Path, view: VkBench.View, seconds: Double, host: VkHost, keep: Boolean = false, tenBit: Boolean? = null,
        map: VkBench.MapView? = null,
    ): VkBench.Summary {
        measureStartNs = System.nanoTime()
        source.mapView = map
        source.path = path
        source.view = view
        source.periodSeconds = seconds
        source.startNs = System.nanoTime()
        source.running = true
        if (!paused) core.requestFrame()
        delay(500)
        drain()
        val from = System.nanoTime()
        val got = ArrayList<FrameRecords.Record>()
        val endAt = from + (seconds * 1e9).toLong()
        while (System.nanoTime() < endAt) {
            delay(100)
            got += drain().filter { it.startNs >= from }
            if (!paused) core.requestFrame() // in the background nothing is drawn (pause)
        }
        delay(250) // the last records' presentation times
        got += drain().filter { it.startNs >= from }
        if (keep) kept = got
        val frames = got.filter { !it.skipped && (tenBit == null || it.tenBit == tenBit) }.sortedBy { it.frame }
        val presented = frames.filter { it.presented }
        val intervals: DoubleArray
        val presentedBy: String
        if (host == VkHost.TEXTURE) {
            intervals = metrics.since(from).first
            presentedBy = "frame-metrics"
        } else if (presented.size >= 2) {
            intervals = DoubleArray(presented.size - 1) { (presented[it + 1].presentedNs - presented[it].presentedNs) / 1e6 }
            presentedBy = "display-timing"
        } else {
            intervals = DoubleArray(maxOf(0, frames.size - 1)) { (frames[it + 1].vsyncNs - frames[it].vsyncNs) / 1e6 }
            presentedBy = "vsync"
        }
        val gpu = frames.filter { it.gpuValid }.map { it.gpuMs.toDouble() }.toDoubleArray()
        val cpu = frames.map { it.cpuMs }.toDoubleArray()
        val record = frames.map { it.recordMs.toDouble() }.toDoubleArray()
        val prepare = frames.map { it.prepareMs.toDouble() }.toDoubleArray()
        val hwui = metrics.since(from).second
        val fragments = frames.filter { it.fragmentsK > 0 }
        return VkBench.summarise(
            intervals, intervals.size, presentedBy,
            gpu, gpu.size, cpu, record, prepare, cpu.size, hwui, hwui.size,
            fragments.sumOf { it.fragmentsK.toLong() }, fragments.size, source.widthPx.toLong() * source.heightPx,
            frames.lastOrNull()?.draws ?: 0, frames.lastOrNull()?.trianglesK ?: 0, targetMs,
        )
    }

    private fun drain(): List<FrameRecords.Record> {
        val out = ArrayList<FrameRecords.Record>()
        while (true) {
            records.clear()
            val n = core.records(records)
            for (i in 0 until n) out += FrameRecords.read(records, i)
            if (n < 512) break
        }
        return out
    }

    private suspend fun soak() {
        val surface = switchHost(VkHost.SURFACE, "the soak")
        if (!surface.attached || bench("the soak", VkHost.SURFACE, true, pickCode, pickRoute, pickForm, pickTarget, pills = true, texture = false).isNotEmpty()) {
            emit("vkbench soak: the SurfaceView did not attach")
            return
        }
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.getSystemService(BatteryManager::class.java)
        val plugged = (context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val charge0 = battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Long.MIN_VALUE
        val samples = (plan.soakSeconds / 10).toInt() + 1
        val minutes = DoubleArray(samples)
        val headroom = DoubleArray(samples)
        var currents = 0.0
        var currentCount = 0
        val start = System.nanoTime()
        val away0 = awayNs
        var n = 0
        var lastP50 = Double.NaN
        val paths = listOf(VkBench.Path.PAN, VkBench.Path.PINCH, VkBench.Path.ORBIT, VkBench.Path.TILT)
        while (n < samples) {
            val path = paths[n % paths.size]
            val s = measure("soak sample ${n + 1}", path, VkBench.View.BOARD, 9.5, VkHost.SURFACE)
            lastP50 = s.presentedP50
            minutes[n] = (System.nanoTime() - start) / 60e9
            headroom[n] = if (Build.VERSION.SDK_INT >= 30) power?.getThermalHeadroom(10)?.toDouble() ?: Double.NaN else Double.NaN
            val current = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) ?: Int.MIN_VALUE
            if (current != Int.MIN_VALUE && current != 0) {
                currents += kotlin.math.abs(current) / 1000.0
                currentCount++
            }
            emit("vkbench soak ${"%.1f".format(java.util.Locale.ROOT, minutes[n])} min: headroom ${if (headroom[n].isNaN()) "—" else "%.3f".format(java.util.Locale.ROOT, headroom[n])} status ${power?.currentThermalStatus ?: -1} p50 ${VkBench.ms(s.presentedP50)} gpu ${VkBench.ms(s.gpuP50)}")
            n++
        }
        val charge1 = battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Long.MIN_VALUE
        val usedMah = if (charge0 == Long.MIN_VALUE || charge1 == Long.MIN_VALUE || charge0 <= 0) Double.NaN else (charge0 - charge1) / 1000.0
        emit(VkBench.soakLine(minutes, headroom, n, usedMah, if (currentCount == 0) Double.NaN else currents / currentCount, plugged, lastP50) +
            VkBench.soakAway((awayNs - away0) / 1e9))
    }

    /**
     * The process's graphics memory now (`Debug.MemoryInfo`'s summary, read from the process itself: ActivityManager's
     * copy is throttled from Android 10), as MB of 10^6 bytes; off the main thread, the read takes tens of ms.
     */
    private suspend fun graphicsMb(): Double = withContext(Dispatchers.Default) {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        (info.getMemoryStat("summary.graphics")?.toDoubleOrNull() ?: Double.NaN) * 1024.0 / 1e6 // KiB
    }

    private fun nativeMb(): Double = Debug.getNativeHeapAllocatedSize() / 1e6
}

/**
 * The bench's report on disk while it runs (`noBackupFilesDir/vkbench/report.txt`, [VkBench.recorded]): written whole
 * and synced as each step begins, so that a native crash (a driver's, in the colour stage perhaps) cannot cost what was
 * measured before it; the next VK bench shows an unfinished one.
 */
private object BenchReport {
    private fun file(context: Context) = File(context.noBackupFilesDir, "vkbench/report.txt")

    /** The step the last run ended in and its lines, when it never finished; null otherwise. */
    fun unfinished(context: Context): Pair<String, List<String>>? =
        runCatching { file(context).takeIf { it.isFile }?.readText()?.let { VkBench.unfinished(it) } }.getOrNull()

    /** Replaces the report by [text], through a temporary file synced and renamed into place. */
    fun write(context: Context, text: String) {
        runCatching {
            val f = file(context)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray())
                out.fd.sync()
            }
            if (!tmp.renameTo(f)) tmp.delete()
        }.onFailure { Log.w(EngineFrames.TAG, "vkbench report not recorded: $it") }
    }
}

/** The activity of a Compose context, if it has one. */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
