package app.atlas.mapcore

import android.content.res.AssetManager
import android.view.Surface
import java.nio.ByteBuffer

/**
 * The only way into the core (plan-vector T9, Appendix D): Atlas's C++23 map engine, drawing with Vulkan. Thread-safe
 * unless noted; nothing allocates per frame. The core draws the app's map ([map], [packs], [moment]) or VK bench's
 * stand-in ([bench]).
 *
 * One core per process: [shared] makes it on first use (the first map screen starts it, T8), behind the crash guard,
 * and keeps it as the process's; it returns null when the phone cannot run the core (no usable Vulkan, or the guard
 * turned it off for this driver or system build after two crashes), and [lastError] and [facts] say why. Nothing else
 * makes a core: the one maker ([create], internal to this module) records each core it makes as the process's and
 * refuses while one is alive, so no second core ever runs beside it (the crash guard's marker is one file a process's
 * cores would share). [shared], [facts], [resetGuard] and [close] hold one lock, so a core's start, the facts without a
 * core and a core's close never overlap.
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

        /**
         * Makes the process's core and records it as the process's, or returns null with the reason in [lastError]
         * (T2's honest state, a library that did not load, or the process's core alive: one core runs in a process at a
         * time). [shared] is its one caller outside this module's tests, which pass their own [make] in the native
         * create's place (it returns the handle, 0 for a refusal). Under the lock of [shared], [facts], [resetGuard]
         * and [close].
         */
        @Synchronized
        internal fun create(config: CoreConfig, make: (CoreConfig) -> Long = ::nativeCreate): MapCore? {
            Native.load()?.let {
                refusal = it
                return null
            }
            if (process != null) {
                refusal = "the process's core is alive (MapCore.shared): one core runs in a process at a time"
                return null
            }
            val handle = make(config)
            if (handle == 0L) {
                refusal = Native.lastError()
                return null
            }
            refusal = ""
            return MapCore(handle, config).also { process = it }
        }

        private fun nativeCreate(config: CoreConfig): Long =
            Native.create(config.cacheDir, config.guardDir, config.system, config.plant, config.flags, config.exits(), config.processStartMs)

        /** The process's core, made once (null while the phone cannot run it: [lastError]). */
        @Synchronized
        fun shared(config: CoreConfig): MapCore? = process ?: create(config)

        /** The process's core if it was made. */
        fun sharedOrNull(): MapCore? = process

        /** Why the last start ([shared]) gave no core ("" when it did). */
        fun lastError(): String = refusal

        /**
         * Whether the core's library is in this process at all: the app's is (its APK carries it); the JVM's tests and
         * Compose's previews have none, and draw today's map alone.
         */
        fun present(): Boolean {
            Native.load()
            return Native.linked
        }

        /**
         * VkFacts without a core (Appendix D): a guarded start of its own, read and closed; when Vulkan is off, the
         * guard's line and the facts kept before. One "name: value" per line. Under the same lock as [shared],
         * [resetGuard] and [close], so a process never runs two cores at once: the crash guard's marker is one file,
         * which a second start would read as this process's, and delete, under the first.
         */
        @Synchronized
        fun facts(config: CoreConfig): List<String> {
            Native.load()?.let { return listOf("vulkan: the core did not load ($it)") }
            process?.let { return it.facts() }
            return Native.facts(0L, config.cacheDir, config.guardDir, config.system, config.flags, config.exits(), config.processStartMs)
                .lines().filter { it.isNotEmpty() }
        }

        /** The core's build: compiler, C++ level, flags, Slang. */
        fun buildInfo(): String = if (Native.load() == null) Native.buildInfo() else "not loaded"

        /** The core's numerics line ([Native.numericsLine]); off the main thread, its first call takes about 6 ms. */
        fun numericsLine(): String =
            Native.load()?.let { "pymath unavailable: the core did not load ($it)" } ?: Native.numericsLine()

        /**
         * The core's sun line ([Native.sunLine]) over the APK's bundled Westerkerk (kept for these lines: the map draws the
         * canal ring there); off the main thread, about 0.1 s.
         */
        fun sunLine(assets: AssetManager): String =
            Native.load()?.let { "sun unavailable: the core did not load ($it)" }
                ?: assets.openFd("tiles/amsterdam.atlas").use { Native.sunLine(it.parcelFileDescriptor.fd, it.startOffset, it.length) }

        /** The core's noise fields line ([Native.fieldsLine]); off the main thread, about 0.3 s of four phone cores. */
        fun fieldsLine(): String =
            Native.load()?.let { "fields unavailable: the core did not load ($it)" } ?: Native.fieldsLine()

        /** The core's ground line ([Native.groundLine]) over the APK's bundled Westerkerk; off the main thread, about 40 ms. */
        fun groundLine(assets: AssetManager): String =
            Native.load()?.let { "ground unavailable: the core did not load ($it)" }
                ?: assets.openFd("tiles/amsterdam.atlas").use { Native.groundLine(it.parcelFileDescriptor.fd, it.startOffset, it.length) }

        /**
         * VK facts' "Reset guard" (debug and bench builds, D5.10): forgets every crash, every driver the crash guard
         * turned off and everything it turned off for one (10 bits after VK bench's colour stage, the profiles check),
         * and the pipeline cache, so that the next start tries Vulkan, 10 bits and the profiles again. Only with no core alive: a live core writes its pipeline cache back (on
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
        core.call(null) { Native.facts(it, config.cacheDir, config.guardDir, config.system, config.flags, "", 0L) }
            ?.lines()?.filter { it.isNotEmpty() } ?: listOf("vulkan: the core is closed")

    /** The core's twin line ([Native.twinLine]) over the APK's bundled Westerkerk; off the main thread, about a second. */
    fun twinLine(assets: AssetManager): String = core.call("twin unavailable: the core is closed") { h ->
        assets.openFd("tiles/amsterdam.atlas").use { Native.twinLine(h, it.parcelFileDescriptor.fd, it.startOffset, it.length) }
    }

    /** The core's composite check line ([Native.compositeLine]); off the main thread, a few small frames. */
    fun compositeLine(): String = core.call("composite.check: not run (the core is closed)") { Native.compositeLine(it) }

    /** The core's noise fields line on its device ([Native.coreFieldsLine]); off the main thread, about a second. */
    fun fieldsLine(): String = core.call("fields unavailable: the core is closed") { Native.coreFieldsLine(it) }

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
     * only. Off ([on] false), whatever configuration it names, the frame goes and anything of 10 bits with it, and
     * nothing is made. A configuration the facts would not pick is a VK bench cell: a driver's crash in its work turns
     * that cell alone off, and the core refuses it from then on, `off (the driver crashed in it: vk guard cell …)`.
     * "" or why the device cannot.
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

    /** The panel's health lines ([Native.health]): they answer while a frame is stuck, as [stats] cannot. */
    fun health(): String = core.call("vk no core") { Native.health(it) }

    /** A debug build's frame pacing on or off ([Native.setPacing]). */
    fun setPacing(on: Boolean) = core.call(Unit) { Native.setPacing(it, on) }

    /**
     * The app went to the background or memory is short ([level], ComponentCallbacks2's): from TRIM_MEMORY_UI_HIDDEN
     * the frame loop stops until the next [requestFrame], so the crash guard's frames stage ends with it and VK bench's
     * colour stage is left (its 10-bit frame and output gone), and the pipeline cache is written back where pipelines
     * were made since its last write (else nothing is written): no marker on disk for a process the system may then
     * freeze and kill.
     */
    fun trimMemory(level: Int) = core.call(Unit) { Native.trimMemory(it, level) }

    /** VK bench's first-launch and warm pipeline creation ([Native.pipelines]); off the main thread. */
    fun pipelines(): String = core.call("vkbench pipelines: the core is closed") { Native.pipelines(it) }

    // ---- The app's map (K11): what it draws from, its moment, on and off.

    /**
     * One pack the map draws from (Appendix D's PackRef): its [id] (a region's), its [number] (positive; the camera
     * state names the pack drawn by it), its [kind] (BUNDLED, RENDER, …: the order the core looks tiles up in), and a
     * file's [path], or an open descriptor's range ([fd], [offset], [length]: an APK asset stored uncompressed, which
     * the core maps where it lies; it keeps its own duplicate of the descriptor, so the caller closes its own after
     * [packs] returns). With a [path], [offset] and [length] are the file's time and size as the app listed it: a file
     * built again at the same path (a window's) is new bytes, which the core opens anew.
     */
    class PackRef(val id: String, val number: Long, val kind: Int, val path: String = "", val fd: Int = -1, val offset: Long = 0, val length: Long = 0) {
        companion object {
            /** The APK's tiles (the Westerkerk, the canal ring). */
            const val BUNDLED = 0

            /** A downloaded render pack (K20a). */
            const val RENDER = 1

            /** A window the phone built (ENGINE.md step 3: [Window.build]); the core takes it into its frame cell's pack. */
            const val WINDOW = 2
        }
    }

    /**
     * The map on ([on]) or off: the core draws the app's map into the attached window from now on (the frame its facts
     * pick, at 8 bits), in VK bench's stand-in's place; [texture] says the host is a TextureView. Made before or after
     * [attach]: without a window it draws once one comes. "" or why it cannot draw. Off the main thread.
     */
    fun map(on: Boolean, texture: Boolean = false): String =
        core.call("the core is closed") { Native.map(it, if (!on) 0 else if (texture) 2 else 1) }

    /**
     * The packs the map draws from, replacing the last list (packs gone leave, new ones open, the same stay). A line
     * per pack: "<id> opened", "<id> listed" (opened when the map is made), "<id> kept" or "<id>: <why not>". Off the
     * main thread: a new pack is opened (mapped, its directory read) before this returns.
     */
    fun packs(packs: List<PackRef>): String = core.call("the core is closed") { h ->
        Native.packs(
            h,
            Array(packs.size) { packs[it].id }, LongArray(packs.size) { packs[it].number }, IntArray(packs.size) { packs[it].kind },
            Array(packs.size) { packs[it].path }, IntArray(packs.size) { packs[it].fd },
            LongArray(packs.size) { packs[it].offset }, LongArray(packs.size) { packs[it].length },
        )
    }

    /** The moment's theme for the map from now on (`state.ThemeBlock`'s block, about once a minute); "" or why refused. */
    fun moment(theme: ByteBuffer): String = core.call("the core is closed") { Native.moment(it, theme) }

    /**
     * The map's labels from now on, as `state.LabelBlock` writes them (each label's sprite, drawn once by the app at its
     * own size and at the band's top, with what the core places it by; an empty block, none), at [revision]: the core
     * places each where the board's painter would, keeps it there on the map and grows and shrinks it with the map
     * (grown, from the label drawn at the band's top, so it stays sharp), never smaller than [least] of its own size
     * (the readable floor). "" or why they were refused. Off the main thread.
     */
    fun labels(block: ByteBuffer, revision: Long, least: Float = 0f): String =
        core.call("the core is closed") { Native.labels(it, block, revision, least) }

    /**
     * The map's markers from now on, as `state.MarkerBlock` writes them (each look the app drew once, the clusters'
     * counts, and what the core places them by; an empty block, none), at [revision]: the core places them each frame
     * where the board's MarkerLayout would, in the same frame as the map, and its labels keep clear of them. "" or why
     * they were refused. Off the main thread.
     */
    fun markers(block: ByteBuffer, revision: Long): String =
        core.call("the core is closed") { Native.markers(it, block, revision) }

    /**
     * The map's routes and areas from now on, as `state.RouteBlock` writes them (each route's look, points and chip, and
     * the shaded circles; an empty block, none), at [revision]: the core draws them on the street each frame as the
     * board's painter would, in the same frame as the map, under its labels and markers. "" or why they were refused.
     * Off the main thread.
     */
    fun routes(block: ByteBuffer, revision: Long): String =
        core.call("the core is closed") { Native.routes(it, block, revision) }

    /**
     * The building under the view's device point ([x], [y]) as the map's last frame drew it (the first one a ray from the
     * eye through that point meets, among the tiles drawn): "way <id> <part> <lat> <lon>" or "relation …", where the ray
     * met it; "" for none (or no map). Off the main thread: it waits for the render thread.
     */
    fun pick(x: Float, y: Float): String = core.call("") { Native.pick(it, x, y) }

    /**
     * The building the app asks about from now on (FillIn's question): way or [relation] [id] (0: none), drawn plain in
     * its walls' flat colour [wall] (0xRRGGBB, the board's), hatched while the question is open ([hatched]). Off the main
     * thread.
     */
    fun ask(relation: Boolean, id: Long, wall: Int, hatched: Boolean) = core.call(Unit) { Native.ask(it, relation, id, wall, hatched) }

    /**
     * The screen's haze, vignette and paper grain from now on, each on (the renders' look, the default) or off: off,
     * the map's picture keeps its own colours there, and the composite does less work a pixel. Off the main thread.
     */
    fun screenEffects(haze: Boolean, vignette: Boolean, grain: Boolean) =
        core.call(Unit) { Native.screen(it, haze, vignette, grain) }

    /**
     * Where the fling under way lands: the camera at its end, which the map knows as the fling begins. The core builds
     * the tiles there after the frame's own, so the map lands whole (ENGINE.md gain 26). Any thread; it does not wait.
     */
    fun landing(latitude: Double, longitude: Double, bearing: Double, pitch: Double, distance: Double) =
        core.call(Unit) { Native.landing(it, true, latitude, longitude, bearing, pitch, distance) }

    /** The fling is over: no landing. */
    fun noLanding() = core.call(Unit) { Native.landing(it, false, 0.0, 0.0, 0.0, 0.0, 0.0) }

    /**
     * The painter switch from now on: [on], what stands up is drawn in the renderer's painter's order (whole buildings
     * by one point each, as the renders sort them, which makes some melt into each other), to compare with the core's
     * true depth; off, true depth (the default). Off the main thread.
     */
    fun painterOrder(on: Boolean) = core.call(Unit) { Native.painter(it, on) }

    /** A window of the map the phone builds (ENGINE.md step 3): one L14 tile, by its zoom, column and row. */
    data class Window(val z: Int, val x: Int, val y: Int) {
        /** Its pack's id among the map's packs. */
        val id: String get() = "window/$z/$x/$y"

        /** The box whose data it needs: south, west, north, east, degrees (its tile's grown by the core's margin). */
        fun box(): DoubleArray = Native.windowBox(z, x, y)

        /**
         * Builds it from Overpass's JSON for [box], in the file [json] (which the core maps: the data never passes
         * through the app's heap), and writes its pack to [file], which the map then lists as a pack of kind window: ""
         * or why not. Off the main thread: a few seconds and tens of MB.
         */
        fun build(json: java.io.File, file: java.io.File): String = Native.buildWindow(z, x, y, json.path, file.path)

        /**
         * Its kept data (the file [kept]: Overpass's JSON of its query, as fetched or merged before) brought up to the
         * date of Overpass's diff of that query since the data's date ([diff]: its XML, a direct buffer), written to
         * [out] to build it from and keep: true; false where nothing it holds changed. Throws IllegalStateException with
         * why where the diff cannot be merged exactly (the window is then fetched whole). Off the main thread.
         */
        fun merge(kept: java.io.File, diff: java.nio.ByteBuffer, out: java.io.File): Boolean =
            Native.mergeWindow(z, x, y, kept.path, diff, out.path)

        /**
         * Its data (the file [kept]: Overpass's JSON of its query, as kept) with the user's own edits waiting on the
         * phone ([edits]: [UserEdit.json]) written to [out] to build it from, never to keep: true; false where no edit
         * applies to its data. Throws IllegalStateException with why where they do not read (it is then built without
         * them). Off the main thread.
         */
        fun edit(kept: java.io.File, edits: java.nio.ByteBuffer, out: java.io.File): Boolean =
            Native.editWindow(z, x, y, kept.path, edits, out.path)

        companion object {
            /** The window of a key the core packed (`z shl 48 or x shl 24 or y`). */
            fun of(packed: Long): Window =
                Window((packed ushr 48).toInt(), ((packed ushr 24) and 0xFFFFFF).toInt(), (packed and 0xFFFFFF).toInt())

            /**
             * The version of a window built now: `<stage P's> <the style's>`, as a window's manifest names them
             * (`engine_version`, `style_version`). A window of another version (an earlier APK built it) is not listed,
             * so a frame cell's windows are of one version.
             */
            fun version(): String = current

            // the core's, once it is loaded (an initializer that throws is tried again on the next call)
            private val current: String by lazy { Native.windowVersion() }
        }
    }

    /**
     * The windows the map wants built now, nearest first: L14 places in view without a pack's tile (the core asks, the
     * app fetches their data). Empty before the map is made. Off the main thread.
     */
    fun wantedWindows(): List<Window> =
        core.call(emptyList()) { h -> Native.wantedWindows(h).map { Window.of(it) } }

    /**
     * The places in view the map draws from windows, nearest first: the windows whose data the app refreshes once it
     * has aged (ENGINE.md step 3: a window built again after a week). Empty before the map is made. Off the main thread.
     */
    fun windowsInView(): List<Window> =
        core.call(emptyList()) { h -> Native.windowsInView(h).map { Window.of(it) } }

    /**
     * Closes the core: no call starts after this begins, the calls under way finish first, then the core is freed
     * (its last pipeline cache write among it). Under the lock of [shared], [facts] and [resetGuard], and the process's
     * core is forgotten only once it is freed: no other core starts in this process while its close runs.
     * Never from the core's own upcalls ([FrameSource]) or from inside a call of it: it would wait for itself.
     */
    override fun close() = close { Native.close(it) }

    /** [close], freeing the handle with [free] (the façade's tests: the native free's place). */
    internal fun close(free: (Long) -> Unit) {
        synchronized(Companion) {
            try {
                core.close(free)
            } finally {
                if (process === this) process = null
            }
        }
    }
}
