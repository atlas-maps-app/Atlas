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
    const val ABI = 22

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
     * asset: the core keeps a duplicate of the descriptor, the caller closes its own). A line per pack: opened, listed,
     * kept, or why not.
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
    ): String

    /** The moment's theme for the map (`state.ThemeBlock`'s 256 bytes in a direct buffer); "" or why it was refused. */
    @JvmStatic external fun moment(handle: Long, theme: ByteBuffer): String

    /** The painter switch for the map ([on]: the renderer's painter's order for what stands up, else true depth). */
    @JvmStatic external fun painter(handle: Long, on: Boolean)

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
     * The building the app asks about (FillIn's question): way or [relation] [id] (0: none, the question closed), its
     * walls' flat colour [wall] (0xRRGGBB), drawn [hatched] while the question is open.
     */
    @JvmStatic external fun ask(handle: Long, relation: Boolean, id: Long, wall: Int, hatched: Boolean)

    /** The screen's haze, vignette and paper grain for the map, each on or off (the app's Look settings). */
    @JvmStatic external fun screen(handle: Long, haze: Boolean, vignette: Boolean, grain: Boolean)

    /**
     * Where the fling under way lands ([has]: none when false): the camera's target, bearing, pitch and distance at its
     * end. The core builds the tiles there after the frame's own (ENGINE.md gain 26). Returns at once.
     */
    @JvmStatic external fun landing(handle: Long, has: Boolean, latitude: Double, longitude: Double, bearing: Double, pitch: Double, distance: Double)
}
