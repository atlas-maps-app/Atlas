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
 * camera paths over the stand-in city, the matrix of frame configurations, forms, routes and hosts, the statistics of
 * the frame records (percentiles, drops, the thermal slope) and the report lines `smoke.sh` and the owner's paste read.
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

    /** The difference of two GPU medians, as the ONEPASS decision reads it (D5.4: build ONEPASS above 1 ms). */
    fun onepassVerdict(composite: Double, onepass: Double): String {
        if (composite.isNaN() || onepass.isNaN()) return "unknown (no GPU times)"
        val d = composite - onepass
        return "composite ${ms(composite)} ms against one subpass ${ms(onepass)} ms: ${if (d > 1.0) "+%.2f ms, over 1 ms: ONEPASS is to be built".format(java.util.Locale.ROOT, d) else "%+.2f ms, within 1 ms: no ONEPASS".format(java.util.Locale.ROOT, d)}"
    }

    /** Whether two frame times differ by less than half a refresh (on time). */
    fun onTime(actualNs: Long, desiredNs: Long, refreshNs: Long): Boolean = desiredNs > 0 && abs(actualNs - desiredNs) < refreshNs / 2

    /** The root-mean-square of the first [n] values. */
    fun rms(v: DoubleArray, n: Int): Double {
        if (n == 0) return Double.NaN
        var s = 0.0
        for (i in 0 until n) s += v[i] * v[i]
        return sqrt(s / n)
    }
}
