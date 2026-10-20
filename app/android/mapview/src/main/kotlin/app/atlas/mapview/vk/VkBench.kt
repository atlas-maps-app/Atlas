package app.atlas.mapview.vk

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * VK bench's plan and arithmetic (plan-vector K1, A0), apart from the screen so the JVM tests hold it: the scripted
 * camera paths over the stand-in city, the matrix of frame configurations, forms, routes and hosts, the colour stage
 * (10 bits a channel, after every 8-bit run), the statistics of the frame records (percentiles, drops, the thermal
 * slope), the report lines `smoke.sh` and the owner's paste read, and the report as it is recorded while the bench
 * runs.
 */
object VkBench {
    /**
     * How long each part runs: the plan's full bench, a quick one, CI's on the emulator, and the pills alone (20 s of
     * them moving over the SurfaceView, for the phone's own screen recorder to film frame by frame).
     */
    enum class Length(
        val label: String,
        val cellSeconds: Double,
        val pathSeconds: Double,
        val pillSeconds: Double,
        val soakSeconds: Double,
    ) {
        FULL("full (about 20 minutes, the last 10 a soak)", 4.0, 30.0, 10.0, 600.0),
        QUICK("quick (about 4 minutes, a 1-minute soak)", 2.0, 6.0, 5.0, 60.0),
        CI("CI (about 1 minute, no soak)", 0.8, 1.5, 1.0, 0.0),
        PILLS("pills (20 s of the pills moving, to film with the screen recorder)", 0.0, 0.0, 20.0, 0.0),
    }

    /** The two views of A0: the Main board's (2.3 km at map pitch 46°) and the map's steepest pitch (70°). */
    enum class View(val label: String, val pitch: Double, val distance: Double, val bearing: Double) {
        BOARD("board", 46.0, 2300.0, 18.0),
        STEEP("pitch70", 70.0, 2300.0, 18.0),
    }

    /** The scripted motions (30 s each in the full bench), and a still camera for the configurations' matrix. */
    enum class Path(val label: String) { ORBIT("orbit"), PAN("pan"), PINCH("pinch"), TILT("tilt"), STILL("still") }

    /** The stand-in's origin, the Westerkerk (`CP/standin/View.hpp`). */
    const val ORIGIN_LATITUDE = 52.3746
    const val ORIGIN_LONGITUDE = 4.8840

    /** A camera of the bench, as doubles (no allocation: the render thread's upcall writes it). */
    class Camera {
        var latitude = ORIGIN_LATITUDE
        var longitude = ORIGIN_LONGITUDE
        var bearing = 0.0
        var pitch = 46.0
        var distance = 2300.0
    }

    /** The camera of [path] at [view], [t] seconds into a run of [period] seconds, written into [out]. */
    fun cameraAt(path: Path, view: View, t: Double, period: Double, out: Camera) {
        val phase = if (period > 0) t / period else 0.0
        out.latitude = ORIGIN_LATITUDE
        out.longitude = ORIGIN_LONGITUDE
        out.bearing = view.bearing
        out.pitch = view.pitch
        out.distance = view.distance
        when (path) {
            // A full turn about the target.
            Path.ORBIT -> out.bearing = view.bearing + 360.0 * phase
            // 600 m along the view and back.
            Path.PAN -> {
                val along = 600.0 * sin(2 * PI * phase)
                val b = view.bearing * PI / 180.0
                out.latitude = ORIGIN_LATITUDE - along * cos(b) / 110574.0
                out.longitude = ORIGIN_LONGITUDE + along * sin(b) / (111320.0 * cos(ORIGIN_LATITUDE * PI / 180.0))
            }
            // 600 m to 6 km and back, evenly in the logarithm (as a pinch zooms).
            Path.PINCH -> out.distance = exp(ln(600.0) + (ln(6000.0) - ln(600.0)) * (1 - cos(2 * PI * phase)) / 2)
            // 20° to 70° and back.
            Path.TILT -> out.pitch = 20.0 + 50.0 * (1 - cos(2 * PI * phase)) / 2
            Path.STILL -> out.bearing = view.bearing + 2.0 * sin(2 * PI * phase) // a slight sway, so every frame is drawn
        }
    }

    /** One way of drawing the frame (`CP/gpu/Facts.hpp`'s Configuration, as `Native.bench` takes it). */
    data class Config(val config: Int, val route: Int, val form: Int, val target: Int) {
        val name: String get() = "${CONFIG_NAMES[config]} ${if (route == 1) "dynamic" else "renderpass"} ${if (form == 1) "onepass" else "composite"} ${if (target == 1) "direct" else "resolve"}"
    }

    /** FrameConfig's names, by its code (A, B, C, D, 1x). */
    val CONFIG_NAMES = listOf("A", "B", "C", "D", "1x")

    /**
     * How the core refuses a cell the crash guard turned off for this driver and build (`CP/standin/Session.hpp`'s
     * bench): the driver crashed in its work, a configuration the facts would not pick, so that cell alone is off.
     */
    const val CELL_OFF = "off (the driver crashed in it: "

    /**
     * The matrix's line for [cell] when the core would not draw it ([why]): `vkbench <cell>: off (the driver crashed in
     * it: vk guard …)` for a cell the crash guard turned off (the run skips it and goes on; Vulkan, the pick and every
     * other cell stay on), else `vkbench <cell>: unavailable (…)`.
     */
    fun skippedLine(cell: String, why: String): String =
        if (why.startsWith(CELL_OFF)) "vkbench $cell: $why" else "vkbench $cell: unavailable ($why)"

    /** The matrix of configurations a device's facts allow: per configuration, both forms through each route. */
    fun matrix(available: Set<Int>, dynamic: Boolean, tiler: Boolean): List<Config> = buildList {
        for (c in listOf(0, 1, 2, 4)) {
            if (c !in available) continue
            val one = c == 4
            for (route in if (dynamic) listOf(0, 1) else listOf(0)) {
                add(Config(c, route, 1, 0))
                if (one) {
                    add(Config(c, route, 0, 1))
                } else {
                    add(Config(c, route, 0, 0))
                    // The 1x output written straight from the samples: the render pass's, and where it matters (not a tiler).
                    if (route == 0 && !tiler) add(Config(c, route, 0, 1))
                }
            }
        }
    }

    /** The p-th percentile (0-100) of the first [n] values (nearest rank); NaN for none. */
    fun percentile(values: DoubleArray, n: Int, p: Double): Double {
        if (n <= 0) return Double.NaN
        val sorted = values.copyOf(n).also { it.sort() }
        val rank = ((p / 100.0) * n).let { kotlin.math.ceil(it).toInt() }.coerceIn(1, n)
        return sorted[rank - 1]
    }

    /** The least-squares slope of y on x; NaN with fewer than two points. */
    fun slope(xs: DoubleArray, ys: DoubleArray, n: Int): Double {
        if (n < 2) return Double.NaN
        var mx = 0.0
        var my = 0.0
        for (i in 0 until n) {
            mx += xs[i]
            my += ys[i]
        }
        mx /= n
        my /= n
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            sxy += (xs[i] - mx) * (ys[i] - my)
            sxx += (xs[i] - mx) * (xs[i] - mx)
        }
        return if (sxx == 0.0) Double.NaN else sxy / sxx
    }

    /** What a run of frames measured. */
    data class Summary(
        val frames: Int,
        val presentedP50: Double,
        val presentedP95: Double,
        val presentedMax: Double,
        val dropped: Int,
        val presentedSource: String,
        val gpuP50: Double,
        val gpuP95: Double,
        val cpuP50: Double,
        val cpuP95: Double,
        val recordP50: Double,
        val prepareP50: Double,
        val hwuiGpuP50: Double,
        val fse: Double,
        val draws: Int,
        val trianglesK: Int,
    )

    /**
     * Summarises frame numbers: [presented] intervals between frames on screen (the display's own times where it
     * reports them), the GPU's and the render thread's milliseconds, HWUI's GPU milliseconds of the window's frames,
     * fragments per frame as full-screen equivalents of [pixels]. A drop is an interval over 1.5 of [refreshMs]
     * times the frames it should take (two vsyncs at 120 Hz).
     */
    fun summarise(
        presented: DoubleArray, presentedCount: Int, presentedSource: String,
        gpu: DoubleArray, gpuCount: Int,
        cpu: DoubleArray, record: DoubleArray, prepare: DoubleArray, cpuCount: Int,
        hwui: DoubleArray, hwuiCount: Int,
        fragmentsK: Long, fragmentFrames: Int, pixels: Long,
        draws: Int, trianglesK: Int, targetMs: Double,
    ): Summary {
        var dropped = 0
        for (i in 0 until presentedCount) if (presented[i] > targetMs * 1.5) dropped += max(1, (presented[i] / targetMs + 0.5).toInt() - 1)
        var maxInterval = 0.0
        for (i in 0 until presentedCount) maxInterval = max(maxInterval, presented[i])
        return Summary(
            frames = cpuCount,
            presentedP50 = percentile(presented, presentedCount, 50.0),
            presentedP95 = percentile(presented, presentedCount, 95.0),
            presentedMax = if (presentedCount == 0) Double.NaN else maxInterval,
            dropped = dropped,
            presentedSource = presentedSource,
            gpuP50 = percentile(gpu, gpuCount, 50.0),
            gpuP95 = percentile(gpu, gpuCount, 95.0),
            cpuP50 = percentile(cpu, cpuCount, 50.0),
            cpuP95 = percentile(cpu, cpuCount, 95.0),
            recordP50 = percentile(record, cpuCount, 50.0),
            prepareP50 = percentile(prepare, cpuCount, 50.0),
            hwuiGpuP50 = percentile(hwui, hwuiCount, 50.0),
            fse = if (fragmentFrames == 0 || pixels == 0L) Double.NaN else fragmentsK * 1000.0 / fragmentFrames / pixels,
            draws = draws,
            trianglesK = trianglesK,
        )
    }

    /** A millisecond figure as the report prints it ("—" for unknown). */
    fun ms(v: Double): String = if (v.isNaN()) "—" else "%.2f".format(java.util.Locale.ROOT, v)

    /** Appendix C's line: `vkbench <path>: p50 … p95 … max … dropped … gpu … hwui … render … graphics … native …`. */
    fun line(path: String, s: Summary, graphicsMb: Double, nativeMb: Double, extra: String = ""): String =
        "vkbench $path: p50 ${ms(s.presentedP50)} p95 ${ms(s.presentedP95)} max ${ms(s.presentedMax)} dropped ${s.dropped} " +
            "gpu ${ms(s.gpuP50)} (p95 ${ms(s.gpuP95)}) hwui ${ms(s.hwuiGpuP50)} render ${ms(s.cpuP50)} (p95 ${ms(s.cpuP95)}, record " +
            "${ms(s.recordP50)}, prepare ${ms(s.prepareP50)}) graphics ${"%.1f".format(java.util.Locale.ROOT, graphicsMb)} native " +
            "${"%.1f".format(java.util.Locale.ROOT, nativeMb)} frames ${s.frames} draws ${s.draws} triangles ${s.trianglesK}k " +
            "fse ${if (s.fse.isNaN()) "—" else "%.2f".format(java.util.Locale.ROOT, s.fse)} presented-by ${s.presentedSource}" +
            if (extra.isEmpty()) "" else " $extra"

    /** The soak's thermal and battery line: headroom's slope (per minute) over minutes 5-10 and its last value. */
    fun soakLine(minutes: DoubleArray, headroom: DoubleArray, n: Int, chargeUsedMah: Double, currentMa: Double, plugged: Boolean, p50AtEnd: Double): String {
        var from = 0
        while (from < n && minutes[from] < 5.0) from++
        val late = if (n - from >= 2) slope(minutes.copyOfRange(from, n), headroom.copyOfRange(from, n), n - from) else slope(minutes, headroom, n)
        val last = if (n > 0) headroom[n - 1] else Double.NaN
        return "vkbench soak: thermal slope ${if (late.isNaN()) "—" else "%.4f".format(java.util.Locale.ROOT, late)}/min (minutes 5-10) " +
            "headroom ${if (last.isNaN()) "—" else "%.2f".format(java.util.Locale.ROOT, last)} p50 at the end ${ms(p50AtEnd)} " +
            "charge ${if (plugged) "plugged in (not measured)" else "%.1f mAh".format(java.util.Locale.ROOT, chargeUsedMah)} " +
            "current ${if (currentMa.isNaN()) "—" else "%.0f mA".format(java.util.Locale.ROOT, currentMa)}"
    }

    /**
     * The colour line (plan-vector D5, "Colour depth"): the GPU's median and p95 for the frame of [cell] in the colour
     * stage's 10-bit colour ([deepName], rgb10a2 or bgr10a2) against the same frame at 8 bits beside it, and what the
     * 10 bits cost.
     */
    fun colourLine(cell: String, deepName: String, deep: Summary, eight: Summary): String {
        val d = deep.gpuP50 - eight.gpuP50
        val cost = if (d.isNaN()) "—" else "%+.2f ms".format(java.util.Locale.ROOT, d)
        return "vkbench colour $cell: $deepName ${ms(deep.gpuP50)} ms, rgba8 ${ms(eight.gpuP50)} ms GPU " +
            "(p95 ${ms(deep.gpuP95)} and ${ms(eight.gpuP95)}; $deepName $cost)"
    }

    /**
     * The colour stage as the facts' `colour.stage:` value gives it (`CP/gpu/Facts.hpp`'s colour_stage): the 10-bit
     * colour it draws ([format], rgb10a2 or bgr10a2), or why it does not run: [off] where the crash guard turned 10 bits
     * off for this driver and build after a crash in the stage, else none to be had; [why] in the core's words.
     */
    data class ColourStage(val format: String?, val off: Boolean, val why: String)

    /** [ColourStage] of a `colour.stage:` value ("rgb10a2 (A2B10G10R10UnormPack32 in …)", "off (…)", "none (…)"). */
    fun colourStage(fact: String): ColourStage {
        val word = fact.substringBefore(" ")
        val why = fact.substringAfter(" (", "").removeSuffix(")").ifEmpty { fact }
        return when (word) {
            "rgb10a2", "bgr10a2" -> ColourStage(word, off = false, why = why)
            "off" -> ColourStage(null, off = true, why = why)
            else -> ColourStage(null, off = false, why = why)
        }
    }

    /** The report's line where the colour stage does not run: skipped after a crash in it, or no 10 bits to be had. */
    fun colourSkipped(stage: ColourStage): String =
        if (stage.off) "vkbench colour: skipped (${stage.why})" else "vkbench colour: rgba8 only (${stage.why})"

    /**
     * The colour stage's first line: what it draws and why. Where the surface offers no 10 bits the stage still runs,
     * each configuration at 8 bits alone, saying so: its flow (the window going and coming back, the waits) is the same
     * on every phone and on CI's emulator, whatever their surfaces offer.
     */
    fun colourStageLine(stage: ColourStage): String =
        if (stage.format != null) {
            "vkbench colour stage: ${stage.format} (${stage.why}); each configuration at the board's view at 10 bits and at 8 beside it"
        } else {
            "vkbench colour stage: rgba8 only (${stage.why}): no 10 bits here, so each configuration at the board's view at 8 bits alone"
        }

    /** The colour stage's line for [cell] where there are no 10 bits: its 8-bit frame alone, and why. */
    fun colourEightLine(cell: String, eight: Summary, why: String): String =
        "vkbench colour $cell: rgba8 ${ms(eight.gpuP50)} ms GPU (p95 ${ms(eight.gpuP95)}; no 10 bits here: $why)"

    /** One measurement of the colour stage: [config] at 10 bits a channel, or at 8 beside it. */
    data class ColourStep(val config: Config, val tenBit: Boolean)

    /**
     * The colour stage's measurements, in order: each of [cells] at 10 bits and again at 8 beside it, the same warmth
     * (the stage runs after the soak), the order turning with each cell (10 then 8, 8 then 10, …), so that the depth
     * changes once a cell and each cell's pipelines are made once at each depth. Without [ten] bits, each at 8 alone.
     */
    fun colourSteps(cells: List<Config>, ten: Boolean = true): List<ColourStep> =
        if (!ten) {
            cells.map { ColourStep(it, false) }
        } else {
            cells.flatMapIndexed { i, c ->
                if (i % 2 == 0) listOf(ColourStep(c, true), ColourStep(c, false)) else listOf(ColourStep(c, false), ColourStep(c, true))
            }
        }

    /**
     * The longest VK bench waits for a window to come (a host switch) or come back (gone with an app switch or the
     * screen off, in the colour stage): the wait always ends, the step saying why where none came.
     */
    const val WINDOW_WAIT_SECONDS = 60

    /** Why a step has no window after waiting for one. */
    fun windowTimedOut(host: String, seconds: Int = WINDOW_WAIT_SECONDS): String = "no $host window came within $seconds s"

    /**
     * The colour stage's line when the app went to the background before [cell]: the core left the stage as it went
     * (nothing of 10 bits alive, no crash-guard marker on disk), and the stage waits for the app as for a window.
     */
    fun colourAppAway(cell: String, seconds: Int = WINDOW_WAIT_SECONDS): String =
        "vkbench colour: the app went to the background before $cell; the colour stage was left, and waits for the app (at most $seconds s)"

    /** The colour stage's line when the app came back from the background. */
    fun colourAppBack(): String = "vkbench colour: the app came back"

    /** The colour stage's line when the app did not come back within the wait: the stage ends. */
    fun colourAppGone(seconds: Int = WINDOW_WAIT_SECONDS): String =
        "vkbench colour: the app did not come back within $seconds s; the colour stage ends here"

    /** The colour stage's line for a [cell] at [colour] that the app's going to the background cut short. */
    fun colourCutShort(cell: String, colour: String): String =
        "vkbench colour $cell: $colour cut short (the app went to the background); measured again"

    /**
     * The run's line when the app went to the background before [step] (outside the colour stage, whose lines are its
     * own): nothing of the run reaches the core while the app is away, and the run waits for it, however long.
     */
    fun appAway(step: String): String = "vkbench: the app went to the background before $step; the run waits for it"

    /** The run's line when the app came back (outside the colour stage). */
    fun appBack(): String = "vkbench: the app came back"

    /**
     * The line for [what] (a cell, a host's path, the pills, a soak sample, the pipelines' timing) that the app's going
     * to the background cut short: measured again once the app is back.
     */
    fun cutShort(what: String): String = "vkbench $what: cut short (the app went to the background); measured again"

    /** What the soak's line adds when the app was away [seconds] of it ("" for none): the slope takes the pause in. */
    fun soakAway(seconds: Double): String =
        if (seconds <= 0.0) "" else "; the app was away ${"%.0f".format(java.util.Locale.ROOT, seconds)} s of it (the slope takes the pause in)"

    /**
     * The A0 line for the colour depth: the colour stage's 10-bit frame in the picked configuration against the 8-bit
     * one measured beside it ([ten] and [eight], null where not measured), or why there is none.
     */
    fun a0Colour(stage: ColourStage, ten: Summary?, eight: Summary?): String =
        if (stage.format == null) {
            "a0 colour: rgba8 (${stage.why})"
        } else {
            "a0 colour: ${stage.format} ${ms(ten?.gpuP50 ?: Double.NaN)} ms against rgba8 ${ms(eight?.gpuP50 ?: Double.NaN)} ms GPU " +
                "in the picked configuration at the board's view, side by side in the colour stage"
        }

    /** The step a recorded report ends with once its run finished. */
    const val COMPLETE = "complete"

    /** The first line of a recorded report, before the step its run had reached. */
    const val REPORT_HEAD = "atlas vkbench report, recorded at: "

    /**
     * The report as VkBenchScreen records it on disk while the bench runs, the step it has reached ("the colour stage",
     * …, [COMPLETE]) first: a native crash ends the process, the screen's lines with it, and the next start finds
     * what was measured before it here ([unfinished]).
     */
    fun recorded(lines: List<String>, step: String): String = REPORT_HEAD + step + "\n" + lines.joinToString("\n")

    /** The step an unfinished recorded report's run ended in, and its lines; null for a finished one, or for none. */
    fun unfinished(text: String): Pair<String, List<String>>? {
        val head = text.substringBefore("\n")
        if (!head.startsWith(REPORT_HEAD)) return null
        val step = head.removePrefix(REPORT_HEAD)
        if (step == COMPLETE) return null
        return step to text.substringAfter("\n", "").lines().filter { it.isNotEmpty() }
    }

    /** An unfinished run's [lines], between two lines saying whose they are, as the next start's report keeps them. */
    fun keptSection(step: String, lines: List<String>): List<String> =
        listOf("vkbench kept: the last run ended in $step; the results it recorded before that follow") + lines + "vkbench kept: end"

    /** The difference of two GPU medians, as the ONEPASS decision reads it (D5.4: build ONEPASS above 1 ms). */
    fun onepassVerdict(composite: Double, onepass: Double): String {
        if (composite.isNaN() || onepass.isNaN()) return "unknown (no GPU times)"
        val d = composite - onepass
        return "composite ${ms(composite)} ms against one subpass ${ms(onepass)} ms: ${if (d > 1.0) "+%.2f ms, over 1 ms: ONEPASS is to be built".format(java.util.Locale.ROOT, d) else "%+.2f ms, within 1 ms: no ONEPASS".format(java.util.Locale.ROOT, d)}"
    }

    /** Whether two frame times differ by less than half a refresh (on time). */
    fun onTime(actualNs: Long, desiredNs: Long, refreshNs: Long): Boolean = desiredNs > 0 && abs(actualNs - desiredNs) < refreshNs / 2

    /**
     * Whether the window drew a frame for the vsync of [vsyncNs] (one of [vsyncs], within 2 ms) and it was not late
     * ([late], the same frames'): the window's half of a core frame and its pill sharing their frame.
     */
    fun drawnOnTime(vsyncs: LongArray, late: BooleanArray, vsyncNs: Long): Boolean {
        for (i in vsyncs.indices) {
            if (abs(vsyncs[i] - vsyncNs) < 2_000_000L) return !late[i]
        }
        return false
    }

    /**
     * How long the Pills run holds the pills still, frosted, then with their tint alone, then under the fade probe's
     * layer (ms each).
     */
    const val PILL_STILL_MS = 3000L

    /** The root-mean-square of the first [n] values. */
    fun rms(v: DoubleArray, n: Int): Double {
        if (n == 0) return Double.NaN
        var s = 0.0
        for (i in 0 until n) s += v[i] * v[i]
        return sqrt(s / n)
    }
}
