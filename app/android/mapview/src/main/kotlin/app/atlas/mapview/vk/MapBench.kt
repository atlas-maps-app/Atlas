package app.atlas.mapview.vk

import app.atlas.mapcore.state.CameraWriter
import app.atlas.mapview.HomeView
import app.atlas.mapview.MapPartGroup
import app.atlas.mapview.MapParts
import app.atlas.render.live.RegionFrame
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * VK bench's real map (the Architect's order of 16 November 2026, point 2, and point 6's budgets on the phone): one lap
 * over the map as the app draws it for the user, the same on every run, in three setups of the core's switches, and the
 * one table the phone sends back. Apart from the screen, so that the JVM tests hold it.
 *
 * **The lap** ([LAP], [cameraAt]): the map home's first look (the Main board's view of the canal ring, [HomeView]: 2.3
 * km at map pitch 46°), in to the Westerkerk at street level (the living map's home: 430 m at 52°), east along the
 * canals to the Dam, a full turn there with the horizon nearer (62°, 700 m), out to 120 km over the Netherlands, where
 * the world draws beyond the country's tiles, and back to the home's look. Why this one:
 * - it is what a person does with the map: it opens on the home's look, goes in, pans, turns and zooms out;
 * - it draws the densest city the published country has (the canal ring's 25,964 buildings: the walls' and roofs'
 *   heaviest frames), a turn at a steep pitch (the triangle governor's worst), and every level from the street to the
 *   world's packs;
 * - it lies inside the Netherlands, the one country published, so a phone fetches every tile it draws, and CI's canal
 *   ring holds its city part;
 * - its camera is a function of the time into the lap alone: every run, every setup and CI's emulator draw the same
 *   views; only how many frames a phone draws of them differs.
 *
 * **The setups** ([Setup]): the map as the app draws it ([Setup.FULL], with the Look settings' own parts and screen
 * effects); without the sun and the effects ([Setup.NO_SUN]: the Look settings' shadows with the sun, the water's light,
 * the weather and the night, and the screen's haze, vignette and grain); and flat ([Setup.FLAT]: pitch 0, the same parts
 * left out, and only the passes of a flat map recorded, through the core's pass switch, MapCore.passes).
 */
object MapBench {
    /** The core's passes, as its registry names them (`CP/frame/PassRegistry.cpp`), in its order. */
    val PASSES = listOf(
        "composite", "frosted", "ground", "buildings", "house", "trees", "things", "items", "doors", "shopfronts",
        "ground_text", "routes", "markers", "labels", "overlays", "world",
    )

    /**
     * A flat map's passes: the ground (with its names, the canal names, which lie on it), the world, the labels, the
     * routes, the markers and the location's puck; and the composite, which makes the picture (with none of its steps
     * but what the setup's parts leave it). No buildings, trees, things, items, doors or shopfronts, and no frosted glass
     * under the chrome: the pills keep their tint alone.
     */
    val FLAT_PASSES = listOf("composite", "ground", "ground_text", "routes", "markers", "labels", "overlays", "world")

    /** The pass that makes the picture: never left out by the split ([split]). */
    const val COMPOSITE = "composite"

    /**
     * The parts the sun and the effects are (camera state's bits): the Look settings' shadows group (the buildings' and
     * the parks' shadows, their blur, the sun's light and shade, the clouds' shadows), the water's light, the weather and
     * the night, as their switches turn them off ([MapPartGroup.parts]).
     */
    val SUN_AND_EFFECTS: Long = MapParts.cameraBits(
        MapParts.bits(MapPartGroup.SHADOWS.parts + MapPartGroup.WATER_LIGHT.parts + MapPartGroup.WEATHER.parts + MapPartGroup.NIGHT.parts),
    )

    /** One way of drawing the lap. [key] names its lines (`vkbench real <key> …`). */
    enum class Setup(val key: String, val label: String) {
        FULL("full", "full: as the app draws the map, Fast refresh on"),

        /** Full with the Look settings' Fast refresh off: 60 a second throughout, what the 120 Hz vote costs beside [FULL]. */
        FULL_60("full60", "full, Fast refresh off: 60 a second throughout"),
        NO_SUN("nosun", "without the sun and effects"),
        FLAT("flat", "flat: pitch 0, the ground, world, labels, routes and markers"),
        ;

        /**
         * The Fast refresh it runs with (MapCore.setFastRefresh): on, the app's default, but in [FULL_60]. On, the window
         * says its frames come at 120 a second while the map moves and 60 at rest; off, 60 throughout.
         */
        val fastRefresh: Boolean get() = this != FULL_60

        /** Whether the split by pass and stage F's parts are measured at the stops: not for [FULL_60], whose frames are [FULL]'s. */
        val split: Boolean get() = this != FULL_60

        private val full: Boolean get() = this == FULL || this == FULL_60

        /** The core's passes it records ([app.atlas.mapcore.MapCore.passes]: "" for every one). */
        val passes: String get() = if (this == FLAT) FLAT_PASSES.joinToString(",") else ""

        /** The passes it records, by name. */
        val passList: List<String> get() = if (this == FLAT) FLAT_PASSES else PASSES

        /** The camera state's parts it leaves out, besides the app's own ([appOff], MapEngine.leftOut's camera bits). */
        fun parts(appOff: Long): Long = MapParts.cameraBits(appOff) or if (full) 0L else SUN_AND_EFFECTS

        /** Whether the screen's haze, vignette and grain are drawn, as the Look settings have them ([look]) in full. */
        fun screen(look: Boolean): Boolean = full && look

        /** The map's pitch it draws a lap's camera at: flat at 0, the others as the lap has it. */
        fun pitch(lap: Double): Double = if (this == FLAT) 0.0 else lap
    }

    /** A point of the lap: at [t] (0 to 1 of it), the camera there, and the name of the part of the lap that ends here. */
    class Key(val t: Double, val part: String, val latitude: Double, val longitude: Double, val bearing: Double, val pitch: Double, val distance: Double)

    /** The Dam, by the National Monument. */
    private const val DAM_LATITUDE = 52.37310
    private const val DAM_LONGITUDE = 4.89240

    /** The far view's target, between Amsterdam and Utrecht, and how far out it stands (metres). */
    private const val FAR_LATITUDE = 52.25
    private const val FAR_LONGITUDE = 5.10
    const val FAR_DISTANCE = 120_000.0

    /**
     * The lap's keys, its bearings unwrapped (the turn at the Dam a whole one). The home's look is [HomeView]'s camera;
     * the Westerkerk's street view is the living map's home (10 m west and 30 m north of the Westerkerk, the engine's
     * references' home).
     */
    val LAP: List<Key> by lazy {
        val living = RegionFrame.living()
        val streetLat = living.lat(-10.0, -30.0)
        val streetLon = living.lon(-10.0, -30.0)
        val home = HomeView.TARGET
        listOf(
            Key(0.00, "home", home.lat, home.lon, HomeView.BEARING, HomeView.PITCH, HomeView.DISTANCE_M),
            Key(0.16, "in", streetLat, streetLon, 52.0, 52.0, 430.0),
            Key(0.40, "along", DAM_LATITUDE, DAM_LONGITUDE, 100.0, 52.0, 430.0),
            Key(0.58, "turn", DAM_LATITUDE, DAM_LONGITUDE, 460.0, 62.0, 700.0),
            Key(0.80, "out", FAR_LATITUDE, FAR_LONGITUDE, 360.0, 30.0, FAR_DISTANCE),
            Key(1.00, "back", home.lat, home.lon, 360.0 + HomeView.BEARING, HomeView.PITCH, HomeView.DISTANCE_M),
        )
    }

    /** The parts of the lap, in order: each ends at its key. */
    val PARTS: List<String> get() = LAP.drop(1).map { it.part }

    /** The lap's line in the table: what it draws, in words. */
    const val LAP_WORDS = "the home's look (2.3 km) → in to the Westerkerk at 430 m → east along the canals to the Dam → " +
        "a full turn there at 62° → out to 120 km over the Netherlands → back to the home's look"

    /** The part of the lap that [t] (0 to 1) lies in. */
    fun partAt(t: Double): String {
        val keys = LAP
        val u = t.coerceIn(0.0, 1.0)
        for (i in 1 until keys.size) if (u <= keys[i].t) return keys[i].part
        return keys.last().part
    }

    /** How far into its lap of [periodSeconds] a frame [seconds] after the lap began stands: 0 to 1, once round. */
    fun phase(seconds: Double, periodSeconds: Double): Double = if (periodSeconds <= 0) 0.0 else (seconds / periodSeconds).coerceIn(0.0, 1.0)

    /**
     * The lap's camera at [t] (0 to 1) in [setup], written into [out]. Between two keys the camera eases in and out (no
     * jump in its speed at a key); the distance moves evenly in its logarithm, as a pinch zooms, and where it changes by
     * more than twice the target follows the distance itself, so that it moves while the view is wide (as a person zooms
     * out, then pans, then zooms in), never sweeping the street at its own scale; the bearing as unwrapped, normalised.
     */
    fun cameraAt(t: Double, setup: Setup, out: VkBench.Camera) {
        val keys = LAP
        val u = t.coerceIn(0.0, 1.0)
        var i = 1
        while (i < keys.size - 1 && u > keys[i].t) i++
        val a = keys[i - 1]
        val b = keys[i]
        val s = if (b.t > a.t) ((u - a.t) / (b.t - a.t)).coerceIn(0.0, 1.0) else 1.0
        val e = s * s * (3 - 2 * s)
        val distance = exp(ln(a.distance) + (ln(b.distance) - ln(a.distance)) * e)
        val ratio = if (a.distance > b.distance) a.distance / b.distance else b.distance / a.distance
        val w = if (ratio > 2.0) (distance - a.distance) / (b.distance - a.distance) else e
        out.latitude = a.latitude + (b.latitude - a.latitude) * w
        out.longitude = a.longitude + (b.longitude - a.longitude) * w
        out.distance = distance
        out.pitch = setup.pitch(a.pitch + (b.pitch - a.pitch) * e)
        out.bearing = normalised(a.bearing + (b.bearing - a.bearing) * e)
    }

    /** The slight sway a still camera draws with, so that every frame is drawn (degrees of bearing at [seconds]). */
    fun sway(seconds: Double): Double = 2.0 * sin(2 * PI * seconds / 4.0)

    /** [bearing] in 0 to 360. */
    fun normalised(bearing: Double): Double = ((bearing % 360.0) + 360.0) % 360.0

    /** Where the split of the GPU's time by pass is measured: a still camera at [t] of the lap. */
    enum class Stop(val key: String, val t: Double, val words: String) {
        STREET("street", 0.16, "the Westerkerk at 430 m"),
        TURN("turn", 0.49, "the Dam mid-turn, about 550 m at 57°"),
        FAR("far", 0.80, "120 km over the Netherlands"),
    }

    /** How long each part of the real map runs on a bench of [VkBench.Length]. */
    class Timing(
        /** One lap's seconds, and how many rounds of the three setups (each setup's laps meet the phone's heat alike). */
        val lapSeconds: Double,
        val rounds: Int,
        /** At rest: the seconds the map may settle first (a fade, the names coming), then the seconds counted. */
        val settleSeconds: Double,
        val restSeconds: Double,
        /** Each pass's frames in the split: at least [splitSeconds] and [splitFrames] frames, at most [splitCapSeconds]. */
        val splitSeconds: Double,
        val splitFrames: Int,
        val splitCapSeconds: Double,
        val stops: List<Stop>,
        /** The lap's points waited for before the laps (their tiles fetched and built), 0 to 1 of it. */
        val warm: List<Double>,
        /** How long a wait for a complete picture may take at most, seconds (null: as long as the map makes progress). */
        val waitCapSeconds: Double?,
    ) {
        /** About how long it takes, minutes, besides the waits for tiles. */
        val minutes: Double get() = ((Setup.entries.size * rounds + 1) * (lapSeconds + 1.0) +
            Setup.entries.size * (settleSeconds + restSeconds + 2.0) +
            stops.size * (PASSES.size * 2 + FLAT_PASSES.size) * maxOf(splitSeconds, 0.2)) / 60.0
    }

    /** The real map's timing on a bench of [length]: CI's short, to prove it runs; the phone's for its numbers. */
    fun timing(length: VkBench.Length): Timing = when (length) {
        VkBench.Length.CI -> Timing(
            lapSeconds = 4.0, rounds = 1, settleSeconds = 2.0, restSeconds = 3.0,
            splitSeconds = 0.0, splitFrames = 2, splitCapSeconds = 6.0, stops = listOf(Stop.STREET),
            warm = listOf(0.0), waitCapSeconds = 30.0,
        )
        else -> Timing(
            lapSeconds = 45.0, rounds = 2, settleSeconds = 3.0, restSeconds = 10.0,
            splitSeconds = 0.6, splitFrames = 20, splitCapSeconds = 5.0, stops = Stop.entries,
            warm = (0..24).map { it / 24.0 } + Stop.entries.map { it.t }, waitCapSeconds = null,
        )
    }

    /**
     * The battery's power from its current ([currentNow], BatteryManager's CURRENT_NOW: µA, its sign the device's own)
     * and voltage ([voltageMv], ACTION_BATTERY_CHANGED's EXTRA_VOLTAGE), watts; NaN where either is missing. A device that
     * reports milliamps (a few do, against the documentation) gives a figure under 20,000: taken as mA, since a phone
     * with its screen on draws more than 20 mA.
     */
    fun watts(currentNow: Int, voltageMv: Int): Double {
        if (currentNow == Int.MIN_VALUE || currentNow == 0 || voltageMv <= 0) return Double.NaN
        val microAmps = abs(currentNow.toDouble()).let { if (it < 20_000.0) it * 1000.0 else it }
        return microAmps * 1e-6 * voltageMv * 1e-3
    }

    /** Power over a span: the mean of its samples, watts, or why there is none. */
    class Power {
        private var sum = 0.0
        var samples = 0
            private set

        /** Why there is no figure (plugged in: the battery is charged, not drawn on), or null. */
        var why: String? = null

        fun add(watts: Double) {
            if (watts.isNaN()) return
            sum += watts
            samples++
        }

        fun add(other: Power) {
            sum += other.sum
            samples += other.samples
            if (why == null) why = other.why
        }

        val mean: Double get() = if (samples == 0 || why != null) Double.NaN else sum / samples

        /** As the table prints it: `3.21 W`, or `—` with why. */
        fun said(): String = when {
            why != null -> "— ($why)"
            samples == 0 -> "— (no reading)"
            else -> "%.2f W".format(Locale.ROOT, mean)
        }
    }

    /** Stage F's parts as the core gives them (MapCore.stageF): the scene's update, the globals, the ranks, each pass's select and update. */
    val STAGE_F_PARTS: List<String> = listOf("scene", "globals", "ranks") + PASSES

    /** Frames' stage F by part ([STAGE_F_PARTS]), from the core's readings ([add]). */
    class StageF {
        val rows = ArrayList<DoubleArray>()

        /** A reading: the parts a frame, then each frame's (MapCore.stageF); one of another width than [STAGE_F_PARTS] is skipped. */
        fun add(raw: DoubleArray) {
            if (raw.isEmpty()) return
            val width = raw[0].toInt()
            if (width != STAGE_F_PARTS.size) return
            var at = 1
            while (at + width <= raw.size) {
                rows += raw.copyOfRange(at, at + width)
                at += width
            }
        }

        fun add(other: StageF) {
            rows += other.rows
        }

        /** Each part's median over the frames, ms, in [STAGE_F_PARTS]' order; null for none. */
        fun medians(): DoubleArray? {
            if (rows.isEmpty()) return null
            return DoubleArray(STAGE_F_PARTS.size) { k ->
                val v = DoubleArray(rows.size) { rows[it][k] }
                VkBench.percentile(v, v.size, 50.0)
            }
        }
    }

    /** A setup's laps, every round's together. */
    class Laps {
        val intervals = ArrayList<Double>()
        val byPart = PARTS.associateWith { ArrayList<Double>() }
        val gpu = ArrayList<Double>()
        val cpu = ArrayList<Double>()
        val record = ArrayList<Double>()
        val prepare = ArrayList<Double>()
        var frames = 0
        var presented = 0L
        var complete = 0L
        var draws = 0
        var trianglesK = 0
        var heapMb = Double.NaN
        var gpuMemMb = Double.NaN
        val power = Power()

        /** Where the intervals came from: the display's own times, or the vsyncs where it gives none (CI's emulator). */
        var presentedBy = "display-timing"

        /** The seconds lapped, the display's refresh rate as it said it during them (Hz, four times a second), stage F by part. */
        var seconds = 0.0
        val hz = ArrayList<Double>()
        val stageF = StageF()

        /** Frames presented a second over the laps. */
        val shownPerSecond: Double get() = if (seconds <= 0.0) Double.NaN else presented / seconds

        /** The display's least, median and most refresh rate during the laps, Hz: `60/120/120`, or `—`. */
        fun hzSaid(): String {
            if (hz.isEmpty()) return "—"
            val v = hz.toDoubleArray()
            fun f(x: Double) = "%.0f".format(Locale.ROOT, x)
            return "${f(v.min())}/${f(VkBench.percentile(v, v.size, 50.0))}/${f(v.max())}"
        }

        /** A presented interval of [ms] ending with a frame at [t] of the lap. */
        fun interval(t: Double, ms: Double) {
            intervals += ms
            byPart[partAt(t)]?.add(ms)
        }

        /** Another round's lap of the same setup, taken in. */
        fun add(o: Laps) {
            intervals += o.intervals
            for ((part, v) in o.byPart) byPart[part]?.addAll(v)
            gpu += o.gpu
            cpu += o.cpu
            record += o.record
            prepare += o.prepare
            frames += o.frames
            presented += o.presented
            complete += o.complete
            draws = o.draws
            trianglesK = o.trianglesK
            if (o.presentedBy != "display-timing") presentedBy = o.presentedBy
            seconds += o.seconds
            hz += o.hz
            stageF.add(o.stageF)
            memory(o.heapMb, o.gpuMemMb)
            power.add(o.power)
        }

        /** The memory as a lap ended: the highest of the rounds'. */
        fun memory(heap: Double, gpuMem: Double) {
            if (!heap.isNaN()) heapMb = if (heapMb.isNaN()) heap else maxOf(heapMb, heap)
            if (!gpuMem.isNaN()) gpuMemMb = if (gpuMemMb.isNaN()) gpuMem else maxOf(gpuMemMb, gpuMem)
        }

        fun summary(): VkBench.Summary {
            val i = intervals.toDoubleArray()
            val g = gpu.toDoubleArray()
            val c = cpu.toDoubleArray()
            return VkBench.summarise(
                i, i.size, presentedBy, g, g.size, c, record.toDoubleArray(), prepare.toDoubleArray(), c.size,
                DoubleArray(0), 0, 0L, 0, 0L, draws, trianglesK, TARGET_MS,
            )
        }

        /** p50 and p95 of the intervals of [part], ms. */
        fun part(part: String): Pair<Double, Double> {
            val v = byPart[part]?.toDoubleArray() ?: DoubleArray(0)
            return VkBench.percentile(v, v.size, 50.0) to VkBench.percentile(v, v.size, 95.0)
        }
    }

    /** The map at rest: frames presented over [seconds] once settled, and the power then. */
    class Rest(val frames: Long, val seconds: Double, val power: Power, val complete: Boolean)

    /** The split at a stop: the GPU's median with the setup's every pass, and what leaving each one out saves. */
    class Split(val stop: Stop, val every: Double, val saves: List<Pair<String, Double>>, val stageF: DoubleArray? = null)

    /** What a setup measured. */
    class Result(val setup: Setup) {
        val laps = Laps()
        var rest: Rest? = null
        val splits = ArrayList<Split>()
    }

    /** A frame's budget, ms (60 Hz): the drops are counted against it, as VK bench's other lines are. */
    const val TARGET_MS = 1000.0 / 60.0

    /** The budgets (the Architect's point 6, the plan's Checks 7): p50 and p95 of the presented intervals, ms. */
    const val BUDGET_P50 = 16.7
    const val BUDGET_P95 = 20.0

    /** The verdict on a lap's [p50] and [p95] against the budgets. */
    fun verdict(p50: Double, p95: Double): String {
        fun one(name: String, v: Double, budget: Double) = when {
            v.isNaN() -> "$name —"
            v <= budget -> "$name ok"
            else -> "$name over"
        }
        return one("p50", p50, BUDGET_P50) + ", " + one("p95", p95, BUDGET_P95)
    }

    /** At rest, the verdict: none drawn is the budget. */
    fun restVerdict(frames: Long): String = if (frames == 0L) "ok" else "over"

    /**
     * The passes the split measures at a stop for [setup]: of those it records, the ones the frame drew in ([counts],
     * MapCore.passCounts: draws and dispatches by pass), never the composite, in the registry's order.
     */
    fun split(setup: Setup, counts: Map<String, Int>): List<String> =
        setup.passList.filter { it != COMPOSITE && (counts[it] ?: 0) > 0 }

    /** The passes to record to leave [pass] out of [setup]'s: its own list without it, comma-separated. */
    fun without(setup: Setup, pass: String): String = setup.passList.filter { it != pass }.joinToString(",")

    /** What the phone and the run were, for the table's head. */
    class Head(
        val date: String,
        val device: String,
        val android: String,
        val gpu: String,
        val build: String,
        val timing: Timing,
        val tiles: String,
        val battery: String,
        val thermal: String,
        val look: String,
        /** The Fast refresh: the user's Look setting, what each setup ran with, the display's modes. */
        val refresh: String,
    )

    /** A setup's line of a lap's numbers, as CI keeps it: `vkbench real <key> lap: <VkBench.line's numbers> …`. */
    fun lapLabel(setup: Setup): String = "real ${setup.key} lap"

    /** A setup's line at rest. */
    fun restLine(setup: Setup, rest: Rest): String =
        "vkbench real ${setup.key} rest: frames ${rest.frames} in ${"%.0f".format(Locale.ROOT, rest.seconds)} s " +
            "(${restVerdict(rest.frames)}; the picture ${if (rest.complete) "complete" else "not complete"} first) power ${rest.power.said()}"

    /** A setup's split at a stop: `vkbench real <key> passes at <stop>: every <ms> ms; <pass> <ms>, …` (what each saves). */
    fun splitLine(setup: Setup, split: Split): String =
        "vkbench real ${setup.key} passes at ${split.stop.key}: every ${VkBench.ms(split.every)} ms" +
            if (split.saves.isEmpty()) "; none drew" else "; " + split.saves.joinToString(", ") { (p, ms) -> "$p ${signed(ms)}" }

    private fun signed(ms: Double): String = if (ms.isNaN()) "—" else "%.2f".format(Locale.ROOT, ms)

    private fun mb(v: Double): String = if (v.isNaN()) "—" else "%.0f".format(Locale.ROOT, v)

    private fun pad(s: String, n: Int) = s.padEnd(n)

    private fun left(s: String, n: Int) = s.padStart(n)

    /**
     * The one table the phone sends back (Share or Copy), the budgets beside it: the head (when, what phone, what
     * build, the lap and how its tiles came, the battery and the heat), a setup a row (p50, p95, drops, the GPU's p50
     * and p95, the render thread's p50, power, the native heap, the core's GPU memory, complete frames, the verdict),
     * the map at rest, each part of the lap's p50 and p95, and the GPU's time by pass at each stop.
     */
    fun table(head: Head, results: List<Result>): String = buildString {
        val t = head.timing
        appendLine("Atlas real-map bench · ${head.date} · ${head.device} · Android ${head.android} · ${head.gpu} · ${head.build}")
        appendLine(
            "Lap of ${"%.0f".format(Locale.ROOT, t.lapSeconds)} s, ${t.rounds} a setup (the rounds take turns), the same every run: $LAP_WORDS.",
        )
        appendLine("Tiles: ${head.tiles}. ${head.look}. Battery ${head.battery}. Thermal headroom ${head.thermal}.")
        appendLine("Refresh: ${head.refresh}.")
        appendLine("Budgets: p50 ≤ $BUDGET_P50 ms, p95 ≤ $BUDGET_P95 ms (presented intervals), 0 frames at rest.")
        appendLine()
        appendLine(pad("setup", 7) + left("p50", 7) + left("p95", 7) + left("drop", 6) + left("shown/s", 8) + left("gpu50", 7) +
            left("gpu95", 7) + left("cpu50", 7) + left("power", 9) + left("heap", 6) + left("gpuMB", 6) + left("complete", 10) + "  budget")
        for (r in results) {
            val s = r.laps.summary()
            val complete = if (r.laps.presented == 0L) "—" else "${r.laps.complete * 100 / r.laps.presented} %"
            appendLine(
                pad(r.setup.key, 7) + left(VkBench.ms(s.presentedP50), 7) + left(VkBench.ms(s.presentedP95), 7) + left("${s.dropped}", 6) +
                    left(r.laps.shownPerSecond.let { if (it.isNaN()) "—" else "%.1f".format(Locale.ROOT, it) }, 8) + left(VkBench.ms(s.gpuP50), 7) + left(VkBench.ms(s.gpuP95), 7) + left(VkBench.ms(s.cpuP50), 7) +
                    left(r.laps.power.said().substringBefore(" ("), 9) + left(mb(r.laps.heapMb), 6) + left(mb(r.laps.gpuMemMb), 6) +
                    left(complete, 10) + "  " + verdict(s.presentedP50, s.presentedP95),
            )
        }
        val why = results.firstNotNullOfOrNull { it.laps.power.why }
        if (why != null) appendLine("(power: $why)")
        appendLine("Display Hz during the laps, least/median/most: " + results.joinToString(" · ") { "${it.setup.key} ${it.laps.hzSaid()}" })
        appendLine()
        val rests = results.mapNotNull { r -> r.rest?.let { r.setup to it } }
        if (rests.isNotEmpty()) {
            val seconds = rests.first().second.seconds
            appendLine("At rest (${"%.0f".format(Locale.ROOT, seconds)} s at the home's look, once settled): " +
                rests.joinToString(" · ") { (s, rest) -> "${s.key} ${rest.frames} frames ${restVerdict(rest.frames)}, ${rest.power.said()}" })
            appendLine()
        }
        appendLine("p50/p95 ms by part of the lap (" + PARTS.joinToString(" · ") + "):")
        for (r in results) {
            appendLine(pad(r.setup.key, 7) + PARTS.joinToString(" · ") { p ->
                val (a, b) = r.laps.part(p)
                "${VkBench.ms(a)}/${VkBench.ms(b)}"
            })
        }
        val stops = results.flatMap { r -> r.splits.map { it.stop } }.distinct()
        if (stops.isNotEmpty()) {
            appendLine()
            appendLine("GPU ms by pass, what leaving it out saves (" + stops.joinToString(" · ") { "${it.key}: ${it.words}" } + "):")
            for (r in results) {
                val byStop = stops.map { s -> r.splits.firstOrNull { it.stop == s } }
                val passes = byStop.filterNotNull().flatMap { sp -> sp.saves.map { it.first } }.distinct()
                    .sortedBy { PASSES.indexOf(it) }
                val cells = mutableListOf("every " + byStop.joinToString(" · ") { VkBench.ms(it?.every ?: Double.NaN) })
                for (p in passes) {
                    cells += "$p " + byStop.joinToString(" · ") { sp -> signed(sp?.saves?.firstOrNull { it.first == p }?.second ?: Double.NaN) }
                }
                appendLine(pad(r.setup.key, 7) + cells.joinToString("; "))
            }
        }
        val stageF = results.filter { it.laps.stageF.rows.isNotEmpty() || it.splits.any { s -> s.stageF != null } }
        if (stageF.isNotEmpty()) {
            appendLine()
            appendLine("Stage F ms by part, the median a frame (lap" + stops.joinToString("") { " · ${it.key}" } +
                "; a pass left out still selects and updates):")
            for (r in stageF) {
                val columns = listOf(r.laps.stageF.medians()) + stops.map { s -> r.splits.firstOrNull { it.stop == s }?.stageF }
                val cells = STAGE_F_PARTS.indices.filter { k ->
                    k < 3 || columns.any { c -> c != null && c[k] >= STAGE_F_SHOWN_MS }
                }.map { k -> "${STAGE_F_PARTS[k]} " + columns.joinToString(" · ") { c -> VkBench.ms(c?.get(k) ?: Double.NaN) } }
                appendLine(pad(r.setup.key, 7) + cells.joinToString("; "))
            }
        }
    }.trimEnd()

    /**
     * The table's words for what the map still wanted after the lap's views were waited for ([missing]: windows, levels
     * and world tiles not there): that every one was there, or how many were not and the first of them, so that a
     * number never hides a missing map.
     */
    fun missingWords(missing: Collection<String>): String = when {
        missing.isEmpty() -> "every window, level and world tile the lap draws was there"
        missing.size <= MISSING_NAMED -> "${missing.size} not there: ${missing.joinToString(", ")}"
        else -> "${missing.size} not there: ${missing.take(MISSING_NAMED).joinToString(", ")} and ${missing.size - MISSING_NAMED} more"
    }

    /** How many of the tiles not there the table names. */
    const val MISSING_NAMED = 8

    /** The least median a pass's stage F part has, at a stop or over the lap, for the table to name it, ms. */
    const val STAGE_F_SHOWN_MS = 0.05

    /** The table's lines as VK bench logs them (`vkbench table: …`), so that CI keeps them with the rest. */
    fun tableLines(table: String): List<String> = table.lines().map { "vkbench table: $it" }

    /** The camera state's flags of a lap's frame: at rest, the world under the cover, the night's light by cascades. */
    fun flags(rest: Boolean, worldUnder: Boolean, lightCascades: Boolean): Int =
        (if (rest) CameraWriter.REST else 0) or (if (worldUnder) CameraWriter.WORLD_UNDER else 0) or
            (if (lightCascades) CameraWriter.LIGHT_CASCADES else 0)

    /** The core's GPU memory in its stats line (`… graphics <MB> MB`), MB; NaN where it says none. */
    fun gpuMemory(stats: String): Double = Regex("""graphics ([0-9.]+) MB""").find(stats)?.groupValues?.get(1)?.toDoubleOrNull() ?: Double.NaN
}
