package app.atlas.mapview.vk

import android.graphics.SurfaceTexture
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import app.atlas.mapcore.CameraState
import app.atlas.mapcore.FrameSource
import app.atlas.mapcore.MapCore
import app.atlas.mapview.EngineFrames

/**
 * The core's window in Compose (plan-vector D5.1): a SurfaceView by default (its buffers go to the display's composer
 * directly, may be a hardware overlay, and honour the frame rate the core asks for), or a TextureView, the measured
 * fallback (HWUI composites its pixels a second time, but they sit in the view tree, so HWUI's frosted glass can
 * blur them). VK bench measures both.
 */
enum class VkHost(val label: String) { SURFACE("surface"), TEXTURE("texture") }

/** The window's state, reported to the screen that holds it. */
data class VkSurfaceState(val attached: Boolean, val widthPx: Int, val heightPx: Int, val error: String = "")

/**
 * [core] draws into this composable's window, asking [source] for the camera written into [camera]; [onState] hears
 * when the window comes, changes size and goes. Until it comes, the view asks for a layout pass each second
 * ([WindowNudge]).
 */
@Composable
fun VkSurface(
    core: MapCore,
    host: VkHost,
    source: FrameSource,
    camera: CameraState,
    modifier: Modifier = Modifier,
    onState: (VkSurfaceState) -> Unit = {},
) {
    val binding = remember(core, host) { Binding(core, source, camera, onState) }
    val composeView = LocalView.current
    DisposableEffect(binding) { onDispose { binding.release() } }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val window = when (host) {
                VkHost.SURFACE -> SurfaceView(context).also { view ->
                    // The surface stays while the view is attached, so a screen change does not rebuild the swapchain.
                    if (Build.VERSION.SDK_INT >= 34) view.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
                    view.holder.addCallback(binding)
                }
                VkHost.TEXTURE -> TextureView(context).also { view ->
                    view.isOpaque = true
                    view.surfaceTextureListener = binding
                }
            } as View
            binding.nudge = WindowNudge(window, composeView, host.label)
            window
        },
    )
}

/** Hands the window to the core and takes it back, from either kind of view. */
private class Binding(
    private val core: MapCore,
    private val source: FrameSource,
    private val camera: CameraState,
    private val onState: (VkSurfaceState) -> Unit,
) : SurfaceHolder.Callback2, TextureView.SurfaceTextureListener {
    private var attached = false
    private var textureSurface: Surface? = null

    /** The view's nudge, told when its window comes and goes. */
    var nudge: WindowNudge? = null

    private fun attach(surface: Surface, width: Int, height: Int) {
        if (attached) {
            core.resize(width, height)
            onState(VkSurfaceState(true, width, height))
            return
        }
        attached = core.attach(surface, width, height, source, camera)
        if (attached) owner = this
        onState(VkSurfaceState(attached, width, height, if (attached) "" else core.lastAttachError()))
        if (attached) core.requestFrame()
    }

    fun release() {
        // The core has one window: only the binding that attached it last lets it go (a map screen that comes after
        // another attaches its own window before the one it covers is disposed).
        if (attached && owner === this) {
            core.detach()
            owner = null
        }
        attached = false
        textureSurface?.release()
        textureSurface = null
    }

    private companion object {
        /** The binding whose window the core draws into now. */
        @Volatile
        var owner: Binding? = null
    }

    // SurfaceView. Each window event is logged (`vk window: …`, a few a screen): where a window is late or never comes,
    // the log says which callback Android did not make.
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(EngineFrames.TAG, "vk window: surface created")
        nudge?.came()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i(EngineFrames.TAG, "vk window: surface changed $width x $height")
        attach(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(EngineFrames.TAG, "vk window: surface destroyed")
        nudge?.went()
        release()
        onState(VkSurfaceState(false, 0, 0))
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) = core.requestFrame()

    // TextureView.
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        Log.i(EngineFrames.TAG, "vk window: texture available $width x $height")
        nudge?.came()
        val surface = Surface(texture)
        textureSurface = surface
        attach(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        textureSurface?.let { attach(it, width, height) }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        Log.i(EngineFrames.TAG, "vk window: texture destroyed")
        nudge?.went()
        release()
        onState(VkSurfaceState(false, 0, 0))
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}

/**
 * Asks [root] (the Compose view that holds [view]) for a layout pass and a redraw each second while [view] sits in a
 * visible window with no window of its own, from each attach until it comes ([came]), with a `vk window: no <label>
 * window after <n> s` line at 1 s and every 10 s. A new SurfaceView makes its surface only in a layout pass, and on
 * CI's emulator a SurfaceView added after a TextureView got none for 60 s, until something else changed the screen
 * (Android run 39105874581); a TextureView makes its texture only as it draws.
 */
internal class WindowNudge(private val view: View, private val root: View, private val label: String) :
    View.OnAttachStateChangeListener, Runnable {
    private var hasWindow = false
    private var seconds = 0

    init {
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) start()
    }

    /** The window came: no more nudges until the view is attached again without one. */
    fun came() {
        hasWindow = true
        view.removeCallbacks(this)
    }

    /** The window went: the view's next attach waits for one again. */
    fun went() {
        hasWindow = false
    }

    override fun onViewAttachedToWindow(v: View) = start()

    override fun onViewDetachedFromWindow(v: View) {
        view.removeCallbacks(this)
    }

    private fun start() {
        view.removeCallbacks(this)
        if (hasWindow) return
        seconds = 0
        view.postDelayed(this, NUDGE_MS)
    }

    override fun run() {
        if (hasWindow || !view.isAttachedToWindow) return
        // A hidden window makes no surface whatever is asked: the seconds count only while it shows.
        if (view.windowVisibility == View.VISIBLE) {
            seconds++
            view.requestLayout()
            root.requestLayout()
            root.invalidate()
            if (seconds == 1 || seconds % 10 == 0) {
                Log.i(EngineFrames.TAG, "vk window: no $label window after $seconds s; a layout pass asked for")
            }
        }
        view.postDelayed(this, NUDGE_MS)
    }

    private companion object {
        const val NUDGE_MS = 1000L
    }
}
