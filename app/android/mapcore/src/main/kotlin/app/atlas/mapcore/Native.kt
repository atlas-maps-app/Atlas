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
    const val ABI = 27

    /** null: not tried yet; "": loaded; otherwise why the core did not load. */
    @Volatile
    private var state: String? = null

    /** Whether the library is in the process once [load] has run (loaded, whatever it said of the façade's ABI). */
    @Volatile
    var linked: Boolean = false
        private set

    /**
     * Loads the core once: libatlasmap.so from the APK, or the host build at [path] (the JVM tests). Returns null when
     * it is loaded, else the reason (the library is missing, or it refused this façade's ABI).
     */
    @Synchronized
    fun load(path: String? = null): String? {
        state?.let { return it.ifEmpty { null } }
        val outcome = try {
            if (path != null) System.load(path) else System.loadLibrary("atlasmap")
            linked = true
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

    // ---- K17a's natives: the noise fields' check (VK facts) ----

    /**
     * The core's noise fields check without a core (plan-vector K17a, `CP/fx/FieldCache.hpp`): the renders' eight
     * screen-wide fields at S1 (1080 × 2400 px at 1080/412 px a CSS px) made on four threads, each held to the port's
     * hashes (fields.inc): `fields 1080x2400 cpu <ms> gpu off (no Vulkan) hash <h> ok`, or `… FAIL <field> <h> expected
     * <h>`. About 0.4 s of four cores on a phone, seconds on the x86_64 emulator: only when VK facts asks, never on the
     * main thread. With a core, [coreFieldsLine].
     */
    @JvmStatic external fun fieldsLine(): String

    /**
     * The core's noise fields check on the core's device (`CP/fx/GpuFields.hpp`): [fieldsLine]'s, and where the GPU's
     * noise is on (a GPU, not lavapipe or SwiftShader, and the crash guard has not turned it off), the same fields made
     * on the GPU after its byte check, behind the guard's noise stage, timed and held to the CPU's bytes: `fields
     * 1080x2400 cpu <ms> gpu <ms> hash <h> ok`, `… gpu off (<why>) …`, or `… FAIL gpu <field> <h> expected <h>`. On the
     * core's render thread: never on the main thread.
     */
    @JvmStatic external fun coreFieldsLine(handle: Long): String

    // ---- end of K17a's natives ----

    // ---- K9's natives: the ground's check (VK facts) ----

    /**
     * The core's ground check (plan-vector K9, `CP/geometry/Ground.hpp`) over the bundled Westerkerk, given as an asset's
     * [fd], [offset] and [length]: every tile's ground built and packed for the GPU, hashed: `ground <hash> ok`, or
     * `ground <hash> FAIL expected <hash>`. About 40 ms of one core: never on the main thread.
     */
    @JvmStatic external fun groundLine(fd: Int, offset: Long, length: Long): String

    // ---- end of K9's natives ----

    /**
     * The core's twin check (`CP/frame/TwinCheck.hpp`) on the core's device: the app's frame over the bundled Westerkerk,
     * given as an asset's [fd], [offset] and [length], at the engine references' home on S1 and the sunny moment,
     * settled without drawing, its twin's stream hashed: `twin <hash> ok`, `twin <hash> FAIL expected <hash>`, or
     * `twin unavailable: <why>`. About a second of two phone cores, on the core's render thread: never on the main thread.
     */
    @JvmStatic external fun twinLine(handle: Long, fd: Int, offset: Long, length: Long): String

    /**
     * The core's composite check (`CP/frame/CompositeCheck.hpp`) on the core's device: the composite's resolve form
     * against its direct form, a few small frames offscreen behind the crash guard's checks stage (a crash there turns
     * the check off alone): `composite.check: …`. On the core's render thread: never on the main thread.
     */
    @JvmStatic external fun compositeLine(handle: Long): String

    /**
     * The core's blur check (`CP/passes/BlurCheck.hpp`) on the core's device: the soft shadows' blur of a known pattern,
     * uploaded, drawn through the frame's mask pass and drawn with its mask cleared first, held to the CPU's block by
     * block behind the crash guard's checks stage: `shadow.blur: …`. On the core's render thread: never on the main
     * thread.
     */
    @JvmStatic external fun blurLine(handle: Long): String

    // ---- K13's natives: the text's kerning rule, the device's glyph check (VK facts) and the bundled fonts ----

    /**
     * The platform's kerning rule for the core's text (plan-vector K13, `CP/text/Shaper.hpp`; the app measures it,
     * mapview's TextRounding): "floor" or "round" sets it, anything else leaves it. Returns the rule the core takes.
     */
    @JvmStatic external fun textRounding(rule: String): String

    /** The runs the device's glyph check shapes (K13): `<style> <weight> <size> <spacing> | <text>`, a run a line. */
    @JvmStatic external fun textCheckRuns(): String

    /**
     * The device's glyph check (K13): the bundled Geist's and Gelasio's bytes (direct buffers) and Android's shaping of
     * [textCheckRuns] as rows, compared bit for bit under the platform's rule: `text.glyphs: …`.
     */
    @JvmStatic external fun textCheck(geist: ByteBuffer, gelasio: ByteBuffer, android: String): String

    /**
     * Loads the bundled fonts (direct buffers of svgfonts/' Geist, Gelasio and Gelasio Italic) into the frame's text,
     * each face once: `text.fonts: ok`, or which file did not load.
     */
    @JvmStatic external fun textFonts(geist: ByteBuffer, gelasio: ByteBuffer, gelasioItalic: ByteBuffer): String

    // ---- end of K13's natives ----

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

    /**
     * The frame loop's health for the debug panel (`CP/standin/Session.hpp`'s health): how long the frame drawn now
     * has run ("vk drawing no" between frames), the frames that failed and the devices lost since the start, the calls
     * with nothing to draw and why, then the last failure's error on a line of its own. Any thread: it never waits for
     * the render thread, so it answers while a frame is stuck.
     */
    @JvmStatic external fun health(handle: Long): String

    /**
     * A debug build's switch: each frame presented at a timeline its recent work can make (`CP/frame/Pacing.hpp`, gone
     * from the map since alpha 16) instead of as soon as it is ready. Any thread; the next frame takes it.
     */
    @JvmStatic external fun setPacing(handle: Long, on: Boolean)

    /**
     * The Map look's switch of the screen's compression: on, the swapchain's images with the driver's own framebuffer
     * compression (its default); off, without it where the driver lets an app ask ([compressionSwitch]). The swapchain
     * is made again with it, and drawn. Any thread; it waits for the render thread.
     */
    @JvmStatic external fun setCompression(handle: Long, on: Boolean)

    /** Whether the driver lets the app make the screen's images without its compression ([setCompression]'s off). */
    @JvmStatic external fun compressionSwitch(handle: Long): Boolean

    /**
     * The Map look's Fast refresh: on (until it is set), the window's frames are said to come at 120 a second while the
     * map moves and at 60 at rest, with fixed-source compatibility; off, at 60 with default compatibility throughout,
     * as alphas up to 27 said. Said again at once; the swapchain is untouched. Any thread; it waits for the render
     * thread.
     */
    @JvmStatic external fun setFastRefresh(handle: Long, on: Boolean)

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

    // ---- The app's map (K11, `CP/frame/MapFrame.hpp`).

    /** The map on ([mode] 1: on a SurfaceView, 2: on a TextureView) or off (0); "" or why it cannot draw. */
    @JvmStatic external fun map(handle: Long, mode: Int): String

    /**
     * The packs the map draws from, replacing the last list, one per index: its id, its number (the camera state's
     * pack id), its kind (`CP/tiles/Store.hpp`'s PackKind), and a file's path or an open descriptor's range (an APK
     * asset: the core keeps a duplicate of the descriptor, the caller closes its own), or a remote world pack's SHA-256
     * in [identities] (null for every other pack; ENGINE.md step 6: its head and chunks in the world's cache alone). A
     * line per pack: opened, listed, kept, or why not.
     */
    @JvmStatic external fun packs(
        handle: Long,
        ids: Array<String>,
        numbers: LongArray,
        kinds: IntArray,
        paths: Array<String>,
        fds: IntArray,
        offsets: LongArray,
        lengths: LongArray,
        identities: Array<ByteArray?>,
    ): String

    /** The moment's theme for the map (`state.ThemeBlock`'s 256 bytes in a direct buffer); "" or why it was refused. */
    @JvmStatic external fun moment(handle: Long, theme: ByteBuffer): String

    /** The user's palette for the map (`state.PaletteBlock`'s 1,040 bytes in a direct buffer); "" or why it was refused. */
    @JvmStatic external fun palette(handle: Long, palette: ByteBuffer): String

    /** The windows the map wants built, nearest first, each its tile's key packed as `z shl 48 or x shl 24 or y`. */
    @JvmStatic external fun wantedWindows(handle: Long): LongArray

    /** The box whose data window ([z], [x], [y]) needs: south, west, north, east, degrees. */
    @JvmStatic external fun windowBox(z: Int, x: Int, y: Int): DoubleArray

    /** The version of a window built now: its stage P's and its style's (its manifest's engine and style versions). */
    @JvmStatic external fun windowVersion(): String

    /**
     * Builds window ([z], [x], [y]) from Overpass's JSON for its box, in the file at [json] (which the core maps), and
     * writes its pack to [path]: "" or why not. On the caller's thread, a background one: a few seconds and tens of MB.
     */
    @JvmStatic external fun buildWindow(z: Int, x: Int, y: Int, json: String, path: String): String

    /** The places in view the map draws from windows, nearest first, each its tile's key packed as [wantedWindows]'. */
    @JvmStatic external fun windowsInView(handle: Long): LongArray

    /**
     * The frame cells' levels' tiles the map wants (L12, then L13: ENGINE.md one pack per country, step 7), in its
     * order, each its tile's key packed as [wantedWindows]'.
     */
    @JvmStatic external fun wantedLevels(handle: Long): LongArray

    /**
     * The frame cells' levels' tiles the map draws now, nearest first, then those it keeps under the places in view
     * (ENGINE.md one pack per country, step 7), each its tile's key packed as [wantedWindows]'.
     */
    @JvmStatic external fun levelsInView(handle: Long): LongArray

    /**
     * The map's world keeps its remote packs' heads and chunks in the world's cache in [dir] within [budget] bytes from
     * now on (ENGINE.md step 6: one for the process, which [worldHeld] and [worldHead] share); "" or why not.
     */
    @JvmStatic external fun worldCache(handle: Long, dir: String, budget: Long): String

    /** Whether the world's cache in [dir] holds each pack's head, [identities] their SHA-256s end to end; null where it does not open. */
    @JvmStatic external fun worldHeld(dir: String, budget: Long, identities: ByteArray): BooleanArray?

    /**
     * A world pack's head into the world's cache in [dir]: the pack of [kind] and [length] bytes whose SHA-256 is
     * [identity], its bytes but its `chunks` section as [ranges], each from its offset in [offsets]; kept once the pack
     * opens from them alone. "" or why not.
     */
    @JvmStatic external fun worldHead(
        dir: String,
        budget: Long,
        kind: Int,
        length: Long,
        identity: ByteArray,
        offsets: LongArray,
        ranges: Array<ByteArray>,
    ): String

    /** Empties the world's cache in [dir] of every head and chunk (a new version of the world drops the old one's); "" or why not. */
    @JvmStatic external fun worldDrop(dir: String, budget: Long): String

    /**
     * The world's tiles the map wants whose chunks are not on the phone, nearest first: eight numbers each, its pack's
     * block and its tile (keys packed as [wantedWindows]'), the chunk's offset in the pack and its length, then the
     * pack's SHA-256 as four big-endian longs.
     */
    @JvmStatic external fun worldWants(handle: Long): LongArray

    /** The world's blocks the map's cover reaches without an open pack, nearest first, keys packed as [wantedWindows]'. */
    @JvmStatic external fun worldBlocks(handle: Long): LongArray

    /**
     * A range of the bytes of the world pack of [block] (packed) and [identity] (its SHA-256) from [offset]: its whole
     * chunks checked and kept in the map's world cache. The chunks kept, then the refused ones' tiles (packed); null
     * where the map's world holds no such pack or no cache.
     */
    @JvmStatic external fun worldKeep(handle: Long, block: Long, identity: ByteArray, offset: Long, bytes: ByteArray): LongArray?

    /**
     * Window ([z], [x], [y])'s kept data (Overpass's JSON of its query, in the file at [kept], which the core maps)
     * brought up to the date of Overpass's diff of the same query since then ([diff], its XML, direct), written to the
     * file at [out]: true; false where the diff changes nothing (no file written); an IllegalStateException says why
     * where it cannot be merged exactly. On a background thread.
     */
    @JvmStatic external fun mergeWindow(z: Int, x: Int, y: Int, kept: String, diff: ByteBuffer, out: String): Boolean

    /**
     * Window ([z], [x], [y])'s data (Overpass's JSON of its query as kept, in the file at [kept], which the core maps)
     * with the user's own edits waiting on the phone ([edits]: their JSON, UTF-8, direct; [UserEdit.json]), written
     * to the file at [out] to build the window from: true; false where no edit applies to the data (no file written); an
     * IllegalStateException says why where the data or the edits do not read. On a background thread.
     */
    @JvmStatic external fun editWindow(z: Int, x: Int, y: Int, kept: String, edits: ByteBuffer, out: String): Boolean

    /**
     * The map's labels (`state.LabelBlock`'s block in a direct buffer; an empty block takes them away) at [revision],
     * drawn no smaller than [least] of their own size; "" or why they were refused.
     */
    @JvmStatic external fun labels(handle: Long, block: ByteBuffer, revision: Long, least: Float): String

    /**
     * The world's names the map's frames show without a sprite, by rank (ENGINE.md step 5, "Names"), little-endian:
     * their count (u32), then each one's id (u32), its look (u32: 0 a city's, 1 a town's, 2 a village's, 3 the Globe
     * board's) and its text (u32 bytes, then UTF-8). Each is asked for once in two seconds.
     */
    @JvmStatic external fun namesWanted(handle: Long): ByteArray

    /**
     * The names' sprites (`state.LabelBlock`'s block in a direct buffer, each sprite without points; one without an
     * image is a name the device has no glyphs for), their text drawn no smaller than [floorDp]; "" or why they were
     * refused.
     */
    @JvmStatic external fun names(handle: Long, block: ByteBuffer, floorDp: Float): String

    /**
     * Names no tile holds, picked as a tile's are from now on (a test's), little-endian: their count (u32), then each
     * one's kind (u32: 0 a country, 1 a city, 2 a town, 3 a village, 4 a suburb), latitude, longitude and people (f64
     * each) and its text (u32 bytes, then UTF-8); "" or why they were refused.
     */
    @JvmStatic external fun testNames(handle: Long, block: ByteArray): String

    /**
     * The map's markers (`state.MarkerBlock`'s block in a direct buffer; an empty block takes them away) at [revision];
     * "" or why they were refused.
     */
    @JvmStatic external fun markers(handle: Long, block: ByteBuffer, revision: Long): String

    /**
     * The map's routes and areas (`state.RouteBlock`'s block in a direct buffer; an empty block takes them away) at
     * [revision]; "" or why they were refused.
     */
    @JvmStatic external fun routes(handle: Long, block: ByteBuffer, revision: Long): String

    /**
     * The building under the view's device point ([x], [y]) as the map's last frame drew it: "way <id> <part> <lat>
     * <lon>" (or "relation …"; the part 0 where the ray met the outline itself), where the ray met it; "" for none.
     */
    @JvmStatic external fun pick(handle: Long, x: Float, y: Float): String

    /**
     * What the view's device point ([x], [y]) shows of the ground as the map's last frame drew it: "<plate|fill|water|quay>
     * <slot> <lat> <lon>", what the ray met, the slot of the user's palette that paints it (0: none does) and where;
     * "" for none.
     */
    @JvmStatic external fun pickGround(handle: Long, x: Float, y: Float): String

    /**
     * The building the app asks about (FillIn's question): way or [relation] [id] (0: none, the question closed), its
     * walls' flat colour [wall] (0xRRGGBB), drawn [hatched] while the question is open.
     */
    @JvmStatic external fun ask(handle: Long, relation: Boolean, id: Long, wall: Int, hatched: Boolean)

    /** The screen's haze, vignette and paper grain for the map, each on or off (the app's Look settings). */
    @JvmStatic external fun screen(handle: Long, haze: Boolean, vignette: Boolean, grain: Boolean)

    /**
     * The kept walls' and near cells' shape records for the map, [on] (the default) or off, each kept wall then decided
     * at each of its points (VK bench's comparison of the two): "" where its buildings draw from them, else why they
     * cannot, or not yet (no frame drawn since the map or its window was made).
     */
    @JvmStatic external fun shapeRecords(handle: Long, on: Boolean): String

    /**
     * Where the fling under way lands ([has]: none when false): the camera's target, bearing, pitch and distance at its
     * end. The core builds the tiles there after the frame's own (ENGINE.md gain 26). Returns at once.
     */
    @JvmStatic external fun landing(handle: Long, has: Boolean, latitude: Double, longitude: Double, bearing: Double, pitch: Double, distance: Double)
}
