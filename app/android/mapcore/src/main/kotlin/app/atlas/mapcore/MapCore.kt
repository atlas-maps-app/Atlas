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
 *
 * Every call and [close] exclude each other ([CoreHandle]): a call on another thread (onTrimMemory's [trimMemory], a
 * view's [detach], VK bench) runs whole before close frees the core, or does nothing once close has begun, and close
 * waits for the calls under way. The per-frame calls ([requestFrame], [records]) stay lock-free and allocate nothing.
 */
class MapCore private constructor(handle: Long, private val config: CoreConfig) : AutoCloseable {
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
         * VK facts' "Reset guard" (debug and bench builds, D5.10): forgets every crash, every driver the crash guard
         * turned off and every 10 bits it turned off (VK bench's colour stage), and the pipeline cache, so that the next
         * start tries Vulkan and 10 bits again. Only with no core alive: a live core writes its pipeline cache back (on
         * trim, on close, after its first 120 frames), which would undo the reset, and the core refuses a reset while
         * one is. So the process's core is closed first, writing its cache one last time, and the next [shared] makes a
         * new one, which reads the guard afresh. One line saying what it forgot. Off the main thread.
         */
        @Synchronized
        fun resetGuard(config: CoreConfig): String {
            Native.load()?.let { return "vk guard reset: the core did not load ($it)" }
            val alive = process
            alive?.close()
            val said = Native.resetGuard(config.cacheDir, config.guardDir)
            return if (alive != null) "$said (the process's core was closed first)" else said
        }
    }

    /** The native handle, shared by the calls and freed by [close] once none is under way. */
    private val core = CoreHandle(handle)

    /** This core's facts, the surface's included once one is attached. */
    fun facts(): List<String> =
        core.call(null) { Native.facts(it, config.cacheDir, config.guardDir, config.system, config.flags) }
            ?.lines()?.filter { it.isNotEmpty() } ?: listOf("vulkan: the core is closed")

    /**
     * Draws into [surface]'s window ([widthPx] × [heightPx]); the core asks [source] for the camera at each paced vsync
     * after [requestFrame]. False with the reason in [lastAttachError].
     */
    fun attach(surface: Surface, widthPx: Int, heightPx: Int, source: FrameSource, camera: CameraState): Boolean =
        core.call(false) { Native.attach(it, surface, widthPx, heightPx, source, camera.buffer) }

    /** Why the last [attach] failed. */
    fun lastAttachError(): String = if (core.closed) "the core is closed" else Native.lastError()

    fun resize(widthPx: Int, heightPx: Int) = core.call(Unit) { Native.resize(it, widthPx, heightPx) }

    /** Returns after the core let go of the surface. */
    fun detach() = core.call(Unit) { Native.detach(it) }

    /** Something changed: the core will ask the source for the camera at its next paced vsync. */
    fun requestFrame() = core.call(Unit) { Native.requestFrame(it) }

    /**
     * VK bench (bench and debug builds): the stand-in drawn in [config] by [route] as [form] into [target]; [pills]
     * draws the frosted pills in the core (the SurfaceView host); [texture] says the host is a TextureView. The frame
     * is 8 bits a channel; [tenBit] is VK bench's colour stage, the frame at 10 where the surface offers them (the
     * facts' `colour.stage:`), behind the crash guard's own stage, so that a driver's crash there turns off 10 bits
     * only. "" or why the device cannot.
     */
    fun bench(on: Boolean, config: Int, route: Int, form: Int, target: Int, pills: Boolean, texture: Boolean, tenBit: Boolean = false): String =
        core.call("the core is closed") {
            Native.bench(
                it, if (on) 1 else 0, config, route, form, target,
                (if (pills) 1 else 0) or (if (texture) 2 else 0) or (if (tenBit) 4 else 0),
            )
        }

    /** Copies the frame records not read yet into [out] ([FrameRecords.allocate]); how many. */
    fun records(out: ByteBuffer): Int = core.call(0) { Native.records(it, out) }

    /** The panel's line. */
    fun stats(): String = core.call("vk no core") { Native.stats(it) }

    /** The app went to the background or memory is short: the pipeline cache is written back. */
    fun trimMemory(level: Int) = core.call(Unit) { Native.trimMemory(it, level) }

    /** VK bench's first-launch and warm pipeline creation ([Native.pipelines]); off the main thread. */
    fun pipelines(): String = core.call("vkbench pipelines: the core is closed") { Native.pipelines(it) }

    /**
     * Closes the core: no call starts after this begins, the calls under way finish first, then the core is freed
     * (its last pipeline cache write among it). Never from the core's own upcalls ([FrameSource]).
     */
    override fun close() {
        core.close {
            if (process === this) process = null
            Native.close(it)
        }
    }
}
