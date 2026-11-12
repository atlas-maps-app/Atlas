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

        /** A SHA-256's bytes (world.idx's pack identities). */
        private const val SHA256 = 32

        /** The numbers of one of [worldWants]' wants (Native.worldWants). */
        private const val WANT = 8

        /**
         * Whether the world's cache in [dir] holds each pack's head (ENGINE.md step 6), by [identities] (their SHA-256s):
         * all false where the core does not load or the cache does not open. Off the main thread: the cache's first use
         * in the process lists its entries.
         */
        fun worldHeld(dir: String, budget: Long, identities: List<ByteArray>): BooleanArray {
            if (identities.isEmpty() || Native.load() != null) return BooleanArray(identities.size)
            val all = ByteArray(identities.size * SHA256)
            identities.forEachIndexed { i, id -> id.copyInto(all, i * SHA256) }
            return Native.worldHeld(dir, budget, all)?.takeIf { it.size == identities.size } ?: BooleanArray(identities.size)
        }

        /**
         * A world pack's head the app fetched into the world's cache in [dir] (ENGINE.md step 6): the pack of [kind]
         * ([PackRef.WORLD_MID] or [PackRef.WORLD_BASE]) and [length] bytes whose SHA-256 is [identity], its bytes but its
         * `chunks` section as [ranges] (each its offset in the pack and its bytes), kept once the pack opens from them
         * alone. "" or why not. Off the main thread.
         */
        fun worldHead(dir: String, budget: Long, kind: Int, length: Long, identity: ByteArray, ranges: List<Pair<Long, ByteArray>>): String {
            Native.load()?.let { return it.ifEmpty { "the core did not load" } }
            return Native.worldHead(dir, budget, kind, length, identity, LongArray(ranges.size) { ranges[it].first }, Array(ranges.size) { ranges[it].second })
        }

        /**
         * Empties the world's cache in [dir] of every head and chunk (ENGINE.md step 6: a new version of the world drops
         * the old one's), within [budget] bytes from now on. "" or why not. Off the main thread.
         */
        fun worldDrop(dir: String, budget: Long): String {
            Native.load()?.let { return it.ifEmpty { "the core did not load" } }
            return Native.worldDrop(dir, budget)
        }

        /**
         * Pins in the world's cache in [dir] everything [packs] keep (ENGINE.md step 11: the world's tiles a kept
         * country draws offline), in place of what was pinned there, so the cache's LRU never takes it and its
         * [budget] does not count it: every pack's head, and each tile's chunk of the packs whose heads the cache
         * holds. Says what is lacking: the app fetches it, hands it over ([worldHead], [worldKeptRange]) and asks
         * again. No packs: nothing is pinned. Null where the cache does not open or the pins cannot be written. Off
         * the main thread.
         */
        fun worldKept(dir: String, budget: Long, packs: List<KeptPack>): Kept? {
            if (Native.load() != null) return null
            val identities = ByteArray(32 * packs.size)
            packs.forEachIndexed { i, p -> p.identity.copyInto(identities, 32 * i, 0, 32) }
            val tiles = LongArray(packs.sumOf { it.tiles.size })
            var at = 0
            for (p in packs) for (t in p.tiles) tiles[at++] = t.packed
            val r = Native.worldKept(dir, budget, LongArray(packs.size) { packs[it].length }, identities, IntArray(packs.size) { packs[it].tiles.size }, tiles)
                ?: return null
            val heads = r[3].toInt()
            val first = 4 + heads
            return Kept(
                heads = List(heads) { r[4 + it].toInt() },
                chunks = List((r.size - first) / 4) { i -> KeptChunk(r[first + 4 * i].toInt(), Window.of(r[first + 4 * i + 1]), r[first + 4 * i + 2], r[first + 4 * i + 3]) },
                unknown = r[0].toInt(),
                held = r[1].toInt(),
                heldBytes = r[2],
            )
        }

        /**
         * A range of the bytes of kept world pack [pack] from [offset] the app fetched, into the world's cache in
         * [dir]: its whole chunks checked against their SHA-256 and kept. Null where the cache holds no head that
         * opens the pack. Off the main thread.
         */
        fun worldKeptRange(dir: String, budget: Long, pack: KeptPack, offset: Long, bytes: ByteArray): WorldKept? {
            if (Native.load() != null) return null
            return Native.worldKeptRange(dir, budget, pack.length, pack.identity, offset, bytes)?.let { r -> WorldKept(r[0].toInt(), List(r.size - 1) { Window.of(r[it + 1]) }) }
        }

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
         * The core's sun line ([Native.sunLine]) over the APK's bundled Westerkerk (kept for these lines alone: no map
         * lists it); off the main thread, about 0.1 s.
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

    /** The core's blur check line ([Native.blurLine]); off the main thread, a few small blurs. */
    fun blurLine(): String = core.call("shadow.blur: not run (the core is closed)") { Native.blurLine(it) }

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

    /** The Map look's switch of the screen's compression ([Native.setCompression]): off the main thread. */
    fun setCompression(on: Boolean) = core.call(Unit) { Native.setCompression(it, on) }

    /** Whether the driver obeys [setCompression]'s off ([Native.compressionSwitch]). */
    val compressionSwitch: Boolean by lazy { core.call(false) { Native.compressionSwitch(it) } }

    /** The Map look's Fast refresh ([Native.setFastRefresh]): off the main thread. */
    fun setFastRefresh(on: Boolean) = core.call(Unit) { Native.setFastRefresh(it, on) }

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
    class PackRef(
        val id: String,
        val number: Long,
        val kind: Int,
        val path: String = "",
        val fd: Int = -1,
        val offset: Long = 0,
        val length: Long = 0,
        /**
         * A remote world pack's SHA-256 (world.idx's; ENGINE.md step 6), with [length] its size and no [path]: the core
         * opens it from its head in the world's cache ([worldHead]) and loads each chunk the cache holds ([worldKeep]).
         */
        val identity: ByteArray? = null,
    ) {
        companion object {
            /** The APK's tiles (the Westerkerk, the canal ring). */
            const val BUNDLED = 0

            /** A downloaded render pack (K20a). */
            const val RENDER = 1

            /** A window the phone built (ENGINE.md step 3: [Window.build]); the core takes it into its frame cell's pack. */
            const val WINDOW = 2

            /**
             * A pack of the world's mid-zoom base (ENGINE.md steps 5 and 6): a remote pack by its [identity] and [length]
             * (world.idx's), opened from its head in the world's cache, drawn beyond 18 km under every country's tiles.
             */
            const val WORLD_MID = 3

            /** The world's globe (ENGINE.md step 5: world-globe.atlas, L0 to L5), drawn where no world pack reaches. */
            const val WORLD_BASE = 4
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
            Array(packs.size) { packs[it].identity },
        )
    }

    /** The moment's theme for the map from now on (`state.ThemeBlock`'s block, about once a minute); "" or why refused. */
    fun moment(theme: ByteBuffer): String = core.call("the core is closed") { Native.moment(it, theme) }

    /** The user's palette for the map from now on (`state.PaletteBlock`'s block), drawn at once; "" or why refused. */
    fun palette(block: ByteBuffer): String = core.call("the core is closed") { Native.palette(it, block) }

    /**
     * The map's labels from now on, as `state.LabelBlock` writes them (each label's sprite, drawn once by the app at its
     * own size and at the band's top, with what the core places it by; an empty block, none), at [revision]: the core
     * places each where the board's painter would, keeps it there on the map and grows and shrinks it with the map
     * (grown, from the label drawn at the band's top, so it stays sharp), never smaller than [least] of its own size
     * (the readable floor). "" or why they were refused. Off the main thread.
     */
    fun labels(block: ByteBuffer, revision: Long, least: Float = 0f): String =
        core.call("the core is closed") { Native.labels(it, block, revision, least) }

    /** A name of the world the map shows and has no sprite of: the core's [id] of it, its [look] and its [text]. */
    class NameWant(val id: Int, val look: Int, val text: String) {
        companion object {
            /** The looks, as the core numbers them (`scene::NameStyle`). */
            const val CITY = 0
            const val TOWN = 1
            const val VILLAGE = 2
            const val GLOBE = 3

            /** A water's or a river's name: the Main board's water label (the IJ's). */
            const val WATER = 4
        }
    }

    /**
     * The world's names the map's frames show without a sprite, by rank (ENGINE.md step 5, "Names"): the app draws
     * each as the boards' labels are drawn and hands them back ([names]). Each is asked for once in two seconds. Off
     * the main thread.
     */
    fun namesWanted(): List<NameWant> = core.call(emptyList()) { h ->
        val b = ByteBuffer.wrap(Native.namesWanted(h)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (b.remaining() < 4) return@call emptyList()
        List(b.getInt()) {
            val id = b.getInt()
            val look = b.getInt()
            val text = ByteArray(b.getInt())
            b.get(text)
            NameWant(id, look, String(text, Charsets.UTF_8))
        }
    }

    /**
     * The sprites of names the core asked for ([namesWanted]), as `state.LabelBlock` writes them, each under the id
     * the core gave and without points (the core knows its place); one with an empty image is a name the device has
     * no glyphs for, which the core does not ask for again. Their text is drawn no smaller than [floorDp] (the
     * readable floor: 12 sp times Android's font scale). "" or why they were refused. Off the main thread.
     */
    fun names(block: ByteBuffer, floorDp: Float): String = core.call("the core is closed") { Native.names(it, block, floorDp) }

    /**
     * A name for [testNames]: its [kind] (`TestName.COUNTRY` and on, the core's `scene::NameKind`), its place, its
     * people (a water's area in m², 0 for none known; a river's length in m) and its [text].
     */
    class TestName(val kind: Int, val lat: Double, val lon: Double, val people: Double, val text: String) {
        companion object {
            const val COUNTRY = 0
            const val CITY = 1
            const val TOWN = 2
            const val VILLAGE = 3
            const val SUBURB = 4
            const val OCEAN = 5
            const val SEA = 6
            const val BAY = 7
            const val STRAIT = 8
            const val LAKE = 9
            const val RESERVOIR = 10
            const val RIVER = 11
        }
    }

    /**
     * Names no tile holds, which the map picks as a tile's from now on (none: an empty list): the tests' (smoke's
     * eight scripts). "" or why they were refused. Off the main thread.
     */
    fun testNames(names: List<TestName>): String = core.call("the core is closed") { h ->
        val texts = names.map { it.text.toByteArray(Charsets.UTF_8) }
        val b = ByteBuffer.allocate(4 + names.size * 32 + texts.sumOf { it.size }).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.putInt(names.size)
        for ((i, n) in names.withIndex()) {
            b.putInt(n.kind).putDouble(n.lat).putDouble(n.lon).putDouble(n.people).putInt(texts[i].size).put(texts[i])
        }
        Native.testNames(h, b.array())
    }

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
     * What the view's device point ([x], [y]) shows of the ground as the map's last frame drew it, from that frame's own
     * decisions (a building or an item may stand before it: ask [pick] first): "<plate|fill|water|quay> <slot> <lat>
     * <lon>", what the ray met, the slot of the user's palette that paints it (0: none does) and where; "" for none (or
     * no map). Off the main thread: it waits for the render thread.
     */
    fun pickGround(x: Float, y: Float): String = core.call("") { Native.pickGround(it, x, y) }

    /**
     * The building the app asks about from now on (FillIn's question): way or [relation] [id] (0: none), drawn plain in
     * its walls' flat colour [wall] (0xRRGGBB, the board's; negative: their colour is not asked about, and they keep
     * their own), hatched while the question is open ([hatched]); and, where its roof's shape is asked about, in the roof
     * [roof] (0 its own, 1 flat, 2 gabled, 3 hipped) as the board's house. Off the main thread.
     */
    fun ask(relation: Boolean, id: Long, wall: Int, hatched: Boolean, roof: Int = 0) =
        core.call(Unit) { Native.ask(it, relation, id, wall, hatched, roof) }

    /**
     * The screen's haze, vignette and paper grain from now on, each on (the renders' look, the default) or off: off,
     * the map's picture keeps its own colours there, and the composite does less work a pixel. Off the main thread.
     */
    fun screenEffects(haze: Boolean, vignette: Boolean, grain: Boolean) =
        core.call(Unit) { Native.screen(it, haze, vignette, grain) }

    /**
     * The kept walls' and near cells' shape records from now on, [on] (the default) or off (ENGINE.md gain 1b: each kept
     * wall then decided at each of its points), for VK bench's comparison of the two at the map's far views: "" where
     * the map's buildings draw from them, else why they cannot, or not yet (no frame drawn since the map or its window
     * was made); "vk no core" without a core. Off the main thread: it waits for the render thread.
     */
    fun shapeRecords(on: Boolean): String = core.call("vk no core") { Native.shapeRecords(it, on) }

    /**
     * Where the fling under way lands: the camera at its end, which the map knows as the fling begins. The core builds
     * the tiles there after the frame's own, so the map lands whole (ENGINE.md gain 26). Any thread; it does not wait.
     */
    fun landing(latitude: Double, longitude: Double, bearing: Double, pitch: Double, distance: Double) =
        core.call(Unit) { Native.landing(it, true, latitude, longitude, bearing, pitch, distance) }

    /** The fling is over: no landing. */
    fun noLanding() = core.call(Unit) { Native.landing(it, false, 0.0, 0.0, 0.0, 0.0, 0.0) }

    /**
     * A window of the map the phone builds (ENGINE.md step 3): one L14 tile, by its zoom, column and row; or a frame
     * cell's level's tile (L13 or L12, step 7's), which the phone takes from its country's tiles and never builds.
     */
    data class Window(val z: Int, val x: Int, val y: Int) {
        /** Its pack's id among the map's packs. */
        val id: String get() = "window/$z/$x/$y"

        /** Its key packed as the core's (z << 48 | x << 24 | y). */
        val packed: Long get() = (z.toLong() shl 48) or (x.toLong() shl 24) or y.toLong()

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
     * The frame cells' levels' tiles the map wants now (ENGINE.md one pack per country, step 7): the L12 tiles in view,
     * then the L13 tiles within the coarse level's reach, each by its share of the screen, then nearest first, that no
     * pack on the phone holds. The app takes them from the country's tiles alone (the core derives them from a cell's
     * windows; the phone does not). Empty before the map is made. Off the main thread.
     */
    fun wantedLevels(): List<Window> =
        core.call(emptyList()) { h -> Native.wantedLevels(h).map { Window.of(it) } }

    /**
     * The frame cells' levels' tiles the map draws now, nearest first, then those it keeps under the places in view
     * (each place's coarsest: ENGINE.md one pack per country, step 7): tiles the phone holds, whose files the app keeps
     * while they are in use, as [windowsInView]' windows. Empty before the map is made. Off the main thread.
     */
    fun levelsInView(): List<Window> =
        core.call(emptyList()) { h -> Native.levelsInView(h).map { Window.of(it) } }

    /**
     * The map's world keeps its remote packs' heads and chunks in the world's cache in [dir] within [budget] bytes from
     * now on (ENGINE.md step 6: the world's own LRU, one for the process); "" or why not. Off the main thread.
     */
    fun worldCache(dir: String, budget: Long): String = core.call("the core is closed") { Native.worldCache(it, dir, budget) }

    /**
     * A world tile the map wants whose chunk is not on the phone: its pack's [block] and [identity] (its SHA-256), its
     * [tile], the chunk's [offset] in the pack and its [length].
     */
    class WorldWant(val block: Window, val identity: ByteArray, val tile: Window, val offset: Long, val length: Long)

    /**
     * The world's tiles the map wants now whose chunks are not on the phone (ENGINE.md step 6), nearest first: the app
     * fetches their ranges and hands them over ([worldKeep]). Empty before the world draws. Off the main thread.
     */
    fun worldWants(): List<WorldWant> = core.call(emptyList()) { h ->
        val w = Native.worldWants(h)
        List(w.size / WANT) { i ->
            val at = WANT * i
            val identity = java.nio.ByteBuffer.allocate(32).putLong(w[at + 4]).putLong(w[at + 5]).putLong(w[at + 6]).putLong(w[at + 7]).array()
            WorldWant(Window.of(w[at]), identity, Window.of(w[at + 1]), w[at + 2], w[at + 3])
        }
    }

    /**
     * The world's blocks the map's cover reaches without an open pack (ENGINE.md step 6), nearest first: an L3 block's
     * coarse pack, an L6 block's fine one; the app fetches the head of each its index has. Off the main thread.
     */
    fun worldBlocks(): List<Window> = core.call(emptyList()) { h -> Native.worldBlocks(h).map { Window.of(it) } }

    /** A world pack of a keep: its [length] and [identity] (its SHA-256) as world.idx has them, and its [tiles] to keep. */
    class KeptPack(val length: Long, val identity: ByteArray, val tiles: List<Window>)

    /** A chunk a keep lacks: its pack's place among those asked about, its [tile], its [offset] in the pack and its [length]. */
    class KeptChunk(val pack: Int, val tile: Window, val offset: Long, val length: Long)

    /**
     * Where a keep stands: the packs whose [heads] the cache lacks (their places; their chunks are not known yet),
     * the [chunks] it lacks of the others, the tiles asked for that no pack holds ([unknown]), and the chunks it
     * holds ([held], [heldBytes] stored bytes).
     */
    class Kept(val heads: List<Int>, val chunks: List<KeptChunk>, val unknown: Int, val held: Int, val heldBytes: Long)

    /** What [worldKeep] made of a range: the chunks it kept, and the tiles whose bytes were refused. */
    class WorldKept(val kept: Int, val refused: List<Window>)

    /**
     * A range of the bytes of the world pack of [block] and [identity] from [offset] (ENGINE.md step 6): its whole
     * chunks checked against their SHA-256 and kept in the world's cache, and the map's next frame builds them. Null
     * where the map's world holds no such pack (another version's) or no cache. Off the main thread.
     */
    fun worldKeep(block: Window, identity: ByteArray, offset: Long, bytes: ByteArray): WorldKept? = core.call(null) { h ->
        Native.worldKeep(h, block.packed, identity, offset, bytes)?.let { r -> WorldKept(r[0].toInt(), List(r.size - 1) { Window.of(r[it + 1]) }) }
    }

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
