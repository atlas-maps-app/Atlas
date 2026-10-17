package app.atlas.mapcore

import android.view.Surface
import java.nio.ByteBuffer

/**
 * The only way into the core (plan-vector T9, Appendix D): Atlas's C++23 map engine, drawing with Vulkan. Thread-safe
 * unless noted; nothing allocates per frame. In this alpha the core draws VK bench's stand-in; the map's own frame
 * comes with K7.
 *
 * One core per process: [shared] makes it on first use (the first map screen starts it, T8), behind the crash guard,
 * and keeps it. [create] returns null when the phone cannot run the core (no usable Vulkan, or the guard turned it off
 * for this driver after two crashes); [lastError] and [facts] say why.
 */
class MapCore private constructor(private val handle: Long, private val config: CoreConfig) : AutoCloseable {
    companion object {
        /** The façade's ABI, checked against the library's at load. */
        const val ABI = Native.ABI

        @Volatile
        private var process: MapCore? = null

        @Volatile
        private var refusal: String = ""

        /** A new core, or null with the reason in [lastError] (T2's honest state, or a library that did not load). */
        fun create(config: CoreConfig): MapCore? {
            Native.load()?.let {
                refusal = it
                return null
            }
            val handle = Native.create(config.cacheDir, config.guardDir, config.system, config.plant, config.flags)
            if (handle == 0L) {
                refusal = Native.lastError()
                return null
            }
            refusal = ""
            return MapCore(handle, config)
        }

        /** The process's core, made once (null while the phone cannot run it: [lastError]). */
        @Synchronized
        fun shared(config: CoreConfig): MapCore? = process ?: create(config)?.also { process = it }

        /** The process's core if it was made. */
        fun sharedOrNull(): MapCore? = process

        /** Why the last [create] gave no core ("" when it did). */
        fun lastError(): String = refusal

        /**
         * VkFacts without a core (Appendix D): a guarded start of its own, read and closed; when Vulkan is off, the
         * guard's line and the facts kept before. One "name: value" per line.
         */
        fun facts(config: CoreConfig): List<String> {
            Native.load()?.let { return listOf("vulkan: the core did not load ($it)") }
            process?.let { return it.facts() }
            return Native.facts(0L, config.cacheDir, config.guardDir, config.system, config.flags).lines().filter { it.isNotEmpty() }
        }

        /** The core's build: compiler, C++ level, flags, Slang. */
        fun buildInfo(): String = if (Native.load() == null) Native.buildInfo() else "not loaded"

        /** The core's numerics line ([Native.numericsLine]); off the main thread, its first call takes about 6 ms. */
        fun numericsLine(): String =
            Native.load()?.let { "pymath unavailable: the core did not load ($it)" } ?: Native.numericsLine()

        /**
         * VK facts' "Reset guard" (debug and bench builds, D5.10): forgets every crash and every driver the crash guard
         * turned off, and the pipeline cache, so that the next start (or [shared], while no core is made) tries Vulkan
         * again. One line saying what it forgot. Off the main thread.
         */
        fun resetGuard(config: CoreConfig): String =
            Native.load()?.let { "vk guard reset: the core did not load ($it)" } ?: Native.resetGuard(config.cacheDir, config.guardDir)
    }

    @Volatile
    private var closed = false

    /** This core's facts, the surface's included once one is attached. */
    fun facts(): List<String> = Native.facts(handle, config.cacheDir, config.guardDir, config.system, config.flags).lines().filter { it.isNotEmpty() }

    /**
     * Draws into [surface]'s window ([widthPx] × [heightPx]); the core asks [source] for the camera at each paced vsync
     * after [requestFrame]. False with the reason in [lastAttachError].
     */
    fun attach(surface: Surface, widthPx: Int, heightPx: Int, source: FrameSource, camera: CameraState): Boolean =
        Native.attach(handle, surface, widthPx, heightPx, source, camera.buffer)

    /** Why the last [attach] failed. */
    fun lastAttachError(): String = Native.lastError()

    fun resize(widthPx: Int, heightPx: Int) = Native.resize(handle, widthPx, heightPx)

    /** Returns after the core let go of the surface. */
    fun detach() = Native.detach(handle)

    /** Something changed: the core will ask the source for the camera at its next paced vsync. */
    fun requestFrame() = Native.requestFrame(handle)

    /**
     * VK bench (bench and debug builds): the stand-in drawn in [config] by [route] as [form] into [target]; [pills]
     * draws the frosted pills in the core (the SurfaceView host); [texture] says the host is a TextureView. "" or why
     * the device cannot.
     */
    fun bench(on: Boolean, config: Int, route: Int, form: Int, target: Int, pills: Boolean, texture: Boolean): String =
        Native.bench(handle, if (on) 1 else 0, config, route, form, target, (if (pills) 1 else 0) or (if (texture) 2 else 0))

    /** Copies the frame records not read yet into [out] ([FrameRecords.allocate]); how many. */
    fun records(out: ByteBuffer): Int = Native.records(handle, out)

    /** The panel's line. */
    fun stats(): String = Native.stats(handle)

    /** The app went to the background or memory is short: the pipeline cache is written back. */
    fun trimMemory(level: Int) = Native.trimMemory(handle, level)

    /** VK bench's first-launch and warm pipeline creation ([Native.pipelines]); off the main thread. */
    fun pipelines(): String = Native.pipelines(handle)

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        if (process === this) process = null
        Native.close(handle)
    }
}
