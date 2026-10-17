package app.atlas.mapview.vk

import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.atlas.mapcore.CameraState
import app.atlas.mapcore.FrameSource
import app.atlas.mapcore.MapCore

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
 * when the window comes, changes size and goes.
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
    DisposableEffect(binding) { onDispose { binding.release() } }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            when (host) {
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

    private fun attach(surface: Surface, width: Int, height: Int) {
        if (attached) {
            core.resize(width, height)
            onState(VkSurfaceState(true, width, height))
            return
        }
        attached = core.attach(surface, width, height, source, camera)
        onState(VkSurfaceState(attached, width, height, if (attached) "" else core.lastAttachError()))
        if (attached) core.requestFrame()
    }

    fun release() {
        if (attached) core.detach()
        attached = false
        textureSurface?.release()
        textureSurface = null
    }

    // SurfaceView.
    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = attach(holder.surface, width, height)

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        release()
        onState(VkSurfaceState(false, 0, 0))
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) = core.requestFrame()

    // TextureView.
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val surface = Surface(texture)
        textureSurface = surface
        attach(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        textureSurface?.let { attach(it, width, height) }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        onState(VkSurfaceState(false, 0, 0))
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}
