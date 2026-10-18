package app.atlas.mapcore

import java.nio.ByteBuffer

/**
 * The core's JNI entry points (plan-vector T9, Appendix D): static natives that the library registers in its
 * `JNI_OnLoad` (`CP/jni/Registry.cpp`) once [ABI] equals its own, so an APK built against another core fails at load,
 * never at draw. Pure Kotlin, without Android types, so `:mapcorecheck`'s JVM tests load the host build through this
 * same class. The app goes through [MapCore].
 */
object Native {
    /** The façade's ABI; the core refuses to load against a class with another (it reads this field). */
    const val ABI = 3

    /** null: not tried yet; "": loaded; otherwise why the core did not load. */
    @Volatile
    private var state: String? = null

    /**
     * Loads the core once: libatlasmap.so from the APK, or the host build at [path] (the JVM tests). Returns null when
     * it is loaded, else the reason (the library is missing, or it refused this façade's ABI).
     */
    @Synchronized
    fun load(path: String? = null): String? {
        state?.let { return it.ifEmpty { null } }
        val outcome = try {
            if (path != null) System.load(path) else System.loadLibrary("atlasmap")
            val core = abi()
            if (core != ABI) "the core is ABI $core, the app's façade ABI $ABI" else ""
        } catch (e: UnsatisfiedLinkError) {
            "the core did not load: ${e.message}"
        }
        state = outcome
        return outcome.ifEmpty { null }
    }

    /** The library's ABI. */
    @JvmStatic external fun abi(): Int

    /** The shared layouts' offset tables (`CP/jni/Layouts.cpp`): the camera state and the frame records. */
    @JvmStatic external fun layouts(): String

    /** The compiler, the language level, the flags and Slang's version the core was built with. */
    @JvmStatic external fun buildInfo(): String

    /**
     * The core's numerics check (plan-vector K3b, `CP/check/NumericsCheck.hpp`): `pymath <hash> ok`, or
     * `pymath <hash> FAIL expected <hash>`. Its first call runs the check, about 6 ms of one core: never on the main thread.
     */
    @JvmStatic external fun numericsLine(): String

    /**
     * The core's sun check (plan-vector K8, `CP/sun/Buckets.hpp`) over the bundled Westerkerk, given as an asset's [fd],
     * [offset] and [length]: `sun <hash> ok`, or `sun <hash> FAIL expected <hash>`. About 0.1 s of four cores: never on
     * the main thread.
     */
    @JvmStatic external fun sunLine(fd: Int, offset: Long, length: Long): String

    /** The last failure of [create] or [attach] on this thread, in words. */
    @JvmStatic external fun lastError(): String

    /**
     * VkFacts, one "name: value" per line: of the core [handle], or (0) of a guarded start of their own, which needs no
     * core ([exits] and [processStart] as [create] takes them). When the crash guard has Vulkan off, the guard's line and
     * the facts kept before. Android's profiles are read for them (the guard's profiles stage), once a device.
     */
    @JvmStatic external fun facts(handle: Long, cacheDir: String, guardDir: String, system: String, flags: Int, exits: String, processStart: Long): String

    /**
     * Makes the process's core behind the crash guard; 0 when it refuses (the reason in [lastError]). [exits] is how the
     * app's last processes ended ([ProcessExits.text]: "" where none are known), [processStart] when this one started
     * (ms since the epoch, 0 for unknown): the guard reads a marker by them, and one a stopped process left counts for
     * nothing.
     */
    @JvmStatic external fun create(cacheDir: String, guardDir: String, system: String, plant: String, flags: Int, exits: String, processStart: Long): Long

    @JvmStatic external fun close(handle: Long)

    /** Takes a Surface's window (an android.view.Surface) for the frames; false with the reason in [lastError]. */
    @JvmStatic external fun attach(handle: Long, surface: Any, widthPx: Int, heightPx: Int, source: FrameSource, camera: ByteBuffer): Boolean

    @JvmStatic external fun resize(handle: Long, widthPx: Int, heightPx: Int)

    /** Returns after the core let go of the window. */
    @JvmStatic external fun detach(handle: Long)

    @JvmStatic external fun requestFrame(handle: Long)

    /**
     * VK bench: the stand-in on (mode 1) or off (0) in a configuration; [flags] 1 the core's frosted pills, 2 a
     * TextureView host, 4 VK bench's colour stage: 10 bits a channel where the surface offers them, behind the crash
     * guard's colour stage (else the frame's 8 bits, its colour in this alpha). Mode 0 lets go of the frame and of
     * anything of 10 bits whatever configuration it names, and makes nothing; a VK bench cell the crash guard turned
     * off is refused, `off (the driver crashed in it: vk guard cell …)`. "" or why not.
     */
    @JvmStatic external fun bench(handle: Long, mode: Int, config: Int, route: Int, form: Int, target: Int, flags: Int): String

    /** Copies the frame records not read yet into [out] (a direct buffer of [FrameRecords.SIZE]-byte records). */
    @JvmStatic external fun records(handle: Long, out: ByteBuffer): Int

    @JvmStatic external fun stats(handle: Long): String

    @JvmStatic external fun trimMemory(handle: Long, level: Int)

    /**
     * VK bench's pipeline creation: this launch's first pipelines, then the drawn configuration's made again from an
     * empty cache and from the cache file (`vkbench pipelines: …`). Blocks while the render thread makes them.
     */
    @JvmStatic external fun pipelines(handle: Long): String

    /**
     * Forgets every crash, every driver the crash guard turned off and everything it turned off for one (10 bits, the
     * profiles check) in [guardDir], and the pipeline cache under [cacheDir], as a fresh install would: the next start
     * tries Vulkan, 10 bits and the profiles again.
     * Refused (in words) while a core is alive in the process, which would write its pipeline cache back: close it
     * first. What it forgot, in words.
     */
    @JvmStatic external fun resetGuard(cacheDir: String, guardDir: String): String
}
