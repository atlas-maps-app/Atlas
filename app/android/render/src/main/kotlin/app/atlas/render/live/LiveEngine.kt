package app.atlas.render.live

import app.atlas.geo.BBox
import app.atlas.render.Amsterdam
import app.atlas.render.Raster
import app.atlas.render.Regions
import app.atlas.render.RenderOptions
import app.atlas.render.Terrain
import app.atlas.render.data.PackReader
import app.atlas.render.data.RegionConfig
import app.atlas.render.data.RegionData
import app.atlas.render.data.RegionFiles
import app.atlas.render.py.Dict
import app.atlas.render.py.PyJson
import java.io.File
import java.time.ZoneId
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.hypot

/**
 * The engine for the live map: Atlas's 1:1 port of `render/` (`Regions`, `Amsterdam`) driven by any camera, moment and
 * region pack.
 *
 * The port keeps Python's module state (the place, its weather, the palette, the moments) in singletons, so the engine
 * renders one frame at a time per process, under [lock]: each scene keeps a snapshot of that state from its load
 * ([EngineState]) and puts it back before it renders, so scenes of different regions and bands can take turns.
 *
 * Scenes (`S`) are loaded per region and tolerance band ([Lod]) and kept, at most [MAX_SCENES] of them, the least
 * recently used dropped first.
 */
object LiveEngine {
    /** At most this many scenes stay in memory. */
    const val MAX_SCENES = 2

    /** One frame at a time per process: the port's module state is shared. */
    val lock = ReentrantLock()

    private val scenes = ArrayList<LiveScene>()

    /** The largest scene each region has loaded, bytes: the next load's expected size. */
    private val sizes = HashMap<String, Long>()

    /** The share of the heap the kept scenes may take together. */
    @Volatile
    var memoryShare = 0.55

    private fun budget(): Long = (Runtime.getRuntime().maxMemory() * memoryShare).toLong()

    /** Where regions are unpacked to the files the engine reads (`<work>/regions/<id>/`, `<work>/park/<park>/pf/`). */
    @Volatile
    var workDir: File = File(System.getProperty("java.io.tmpdir"), "atlas-engine")

    /** Bytes the scenes in memory took when they loaded (the heap's growth), for reports. */
    val sceneBytes: Long get() = lock.withLock { scenes.sumOf { it.bytes } }

    val sceneCount: Int get() = lock.withLock { scenes.size }

    /**
     * Opens [data] for the live map: unpacks it to the engine's files (once per [stamp], the pack's identity) and reads
     * its configuration. [fallbackWx] is the weather record of a region that has none (its fixed moments need one).
     */
    fun open(data: RegionData, stamp: String, fallbackWx: (() -> String)? = null): LiveRegion {
        val roots = roots()
        val unpacked = RegionFiles.unpack(data, roots.first, roots.second, stamp, fallbackWx ?: { defaultWx() })
        val config = PyJson.loads(data.config.toString()) as Dict
        @Suppress("UNCHECKED_CAST")
        val views = config["views"] as Map<String, Dict>?
        val bbox = RegionConfig.bboxOf(data.config) ?: (data as? PackReader)?.manifest?.bbox
        val zone = runCatching { ZoneId.of((config["tz"] as String?) ?: "Europe/Amsterdam") }.getOrDefault(ZoneId.of("Europe/Amsterdam"))
        return if (views.isNullOrEmpty()) {
            LiveRegion(data.id, bbox, LiveRegion.Kind.LIVING, RegionFrame.living(), ViewLadder(data.id, mapOf("" to ViewLadder.livingView()), living = true), unpacked.dir, zone, false, unpacked.millis)
        } else {
            val origin = RegionConfig.originOf(data.config) ?: bbox?.center ?: error("region ${data.id} has no origin")
            LiveRegion(
                data.id, bbox, LiveRegion.Kind.REGION, RegionFrame.region(origin), ViewLadder(data.id, views), unpacked.dir, zone,
                File(unpacked.dir, "dem.json").isFile, unpacked.millis,
            )
        }
    }

    /** The engine's camera for [view] over [region] (no rendering). */
    fun place(region: LiveRegion, view: LiveView): ViewLadder.Placed = region.ladder.place(region.frame, view)

    /**
     * Renders [view] of [region] at [moment]: loads the scene for its band if needed (reporting [progress]), then the
     * frame. [cancelled] is asked between the steps; a cancelled request returns null.
     */
    fun render(
        region: LiveRegion,
        view: LiveView,
        moment: Moment,
        opts: RenderOptions = RenderOptions(),
        progress: (LiveProgress) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): LiveRender? = lock.withLock {
        if (cancelled()) return null
        var placed = region.ladder.place(region.frame, view)
        val tolerance = if (region.kind == LiveRegion.Kind.LIVING) 0.0 else Lod.tolerance(region.id, placed.view, placed.dist)
        val t0 = System.nanoTime()
        val scene = scene(region, placed, tolerance, opts, progress)
        val loadMillis = (System.nanoTime() - t0) / 1_000_000
        if (cancelled()) return null
        scene.state.restore()
        setRoots(region)
        // the ground under the target: terrain regions stand the camera on it (a region's own views carry it)
        if (placed.view == null) {
            val dem = scene.S["dem"] as? Terrain.DEM
            if (dem != null) placed = region.ladder.place(region.frame, view) { x, z -> dem.h(x, z).takeIf { !it.isNaN() } }
        }
        val cfg = placed.cfg
        if (placed.view == null && region.kind == LiveRegion.Kind.REGION) {
            // relief regions shade their ground for the view the scene names (regions.py: cfg_for(S['view'])): this camera
            val key = "${region.id}_live"
            Regions.CAMS[key] = LinkedHashMap(cfg).also { it.remove("W"); it.remove("H") }
            scene.S["view"] = key
        } else if (region.kind == LiveRegion.Kind.REGION) {
            scene.S["view"] = scene.loadView
        }
        progress(LiveProgress(LiveProgress.Stage.RENDERING, region.id, -1.0))
        fun live(at: java.time.Instant, hours: List<WxHour>?): String {
            val name = LiveMoment.install(at, region.zone, hours, opts.daylight)
            @Suppress("UNCHECKED_CAST")
            (scene.S["_tide"] as? MutableMap<String, Double>)?.let { t -> if (t.isNotEmpty() && LiveMoment.KEY !in t) t[LiveMoment.KEY] = t[name] ?: t.values.first() }
            return LiveMoment.KEY
        }
        val thn = when (moment) {
            is Moment.Named -> {
                val th = Amsterdam.THEMES[moment.name] ?: throw IllegalArgumentException("the engine has no moment '${moment.name}'")
                if (region.kind == LiveRegion.Kind.REGION && th["wx"] == null) {
                    // regions.py reads every moment's weather (its turbines turn in the wind); amsterdam.py's own fixed
                    // moments (night, golden, noon) carry none, and regions.py never draws them. Such a moment is drawn
                    // as the live moment at its own date and hour, with an overcast record: the same light, and a wind.
                    @Suppress("UNCHECKED_CAST")
                    val d = (th["date"] as List<Number>).map { it.toInt() }
                    @Suppress("UNCHECKED_CAST")
                    val hm = (th["hhmm"] as List<Number>).map { it.toInt() }
                    live(java.time.LocalDateTime.of(d[0], d[1], d[2], hm[0], hm[1]).atZone(region.zone).toInstant(), null)
                } else {
                    moment.name
                }
            }
            is Moment.Live -> live(moment.at, moment.hours)
        }
        val t1 = System.nanoTime()
        val out = if (region.kind == LiveRegion.Kind.REGION) {
            val r = Regions.render(scene.S, thn, cfg, opts)
            LiveRender(cfg, placed.view, thn, (Amsterdam.THEMES[thn]?.get("name") as String?) ?: thn, r.g, r.o, r.o2, r.t, tolerance, loadMillis, 0, region.frame)
        } else {
            val r = Amsterdam.render(scene.S, thn, cfg, null, layer = "split", opts = opts)
            val objs = r.objs!!
            LiveRender(cfg, placed.view, thn, (Amsterdam.THEMES[thn]?.get("name") as String?) ?: thn, r.svg, objs[0], objs.getOrNull(1), r.info["raster"] as Raster?, tolerance, loadMillis, 0, region.frame)
        }
        out.renderMillis = (System.nanoTime() - t1) / 1_000_000
        out
    }

    /** The scene of [region] at [tolerance], loaded if no kept one fits [placed]. */
    private fun scene(region: LiveRegion, placed: ViewLadder.Placed, tolerance: Double, opts: RenderOptions, progress: (LiveProgress) -> Unit): LiveScene {
        val target = placed.cfg["target"] as List<*>
        val tx = (target[0] as Number).toDouble()
        val tz = (target[2] as Number).toDouble()
        val radius = (placed.cfg["radius"] as Number?)?.toDouble() ?: 1e9
        val loadView = when {
            region.kind == LiveRegion.Kind.LIVING -> region.id
            placed.view != null -> placed.view
            else -> Lod.bandView(region.id, tolerance)
        }
        val i = scenes.indexOfFirst { s ->
            s.region === region && s.loadView == loadView && s.opts.copy(ao = false) == opts.copy(ao = false) &&
                (!region.viewDependent || hypot(s.anchorX - tx, s.anchorZ - tz) <= s.radius / 2)
        }
        if (i >= 0) {
            val s = scenes.removeAt(i)
            scenes.add(s)
            return s
        }
        // make room first: at most MAX_SCENES, and within the memory budget (a canal ring's scene is ~280 MB, a phone's
        // large heap 512 MB): what this region's scenes took before is what the next one will
        val expect = sizes[region.id] ?: 0L
        while (scenes.size >= MAX_SCENES || scenes.isNotEmpty() && scenes.sumOf { it.bytes } + expect > budget()) scenes.removeAt(0)
        progress(LiveProgress(LiveProgress.Stage.LOADING, region.id, -1.0))
        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        setRoots(region)
        val S: Dict = if (region.kind == LiveRegion.Kind.LIVING) {
            Regions.resetPlace(opts.daylight)
            Amsterdam.load(File(region.dir, "raw.json"), opts)
        } else {
            for (k in region.ladder.knots) if (k.name !in Regions.CAMS) Regions.CAMS[k.name] = LinkedHashMap(k.cam)
            if (placed.view == null) {
                @Suppress("UNCHECKED_CAST")
                (Regions.GENERALISE as MutableMap<String, Double>)[loadView] = tolerance
                Regions.CAMS[loadView] = LinkedHashMap(placed.cfg).also { it.remove("W"); it.remove("H") }
            }
            Regions.load(loadView, opts)
        }
        val millis = (System.nanoTime() - t0) / 1_000_000
        System.gc()
        val bytes = (rt.totalMemory() - rt.freeMemory() - before).coerceAtLeast(0)
        val s = LiveScene(region, loadView, tolerance, S, EngineState.capture(), opts, tx, tz, radius, millis, bytes)
        sizes[region.id] = maxOf(bytes, sizes[region.id] ?: 0L)
        scenes.add(s)
        return s
    }

    /**
     * Loads the scene of [region] without rendering it: what [render] draws from, with the port's module state its load
     * left ([LiveScene.state]), for the vector engine's stage P (the packer's render tiles, plan-vector K2).
     *
     * [view] names one of the region's own views: the scene then loads as that view's renders load it (its tolerance, and
     * the values regions.py measures around its camera). Null loads the region whole, at [tolerance] (0 by default): every
     * value that depends on a view (water levels from the banks, the relief's extent, a park's woods) measured around the
     * centre of the region's box with a radius reaching its corners. The living map has one view and always loads at
     * tolerance 0.
     *
     * The scene is not kept: [render]'s scenes are left as they were, and so is what renders draw.
     */
    fun loadScene(region: LiveRegion, view: String? = null, opts: RenderOptions = RenderOptions(), tolerance: Double = 0.0): LiveScene = lock.withLock {
        val rt = Runtime.getRuntime()
        val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        setRoots(region)
        val loadView: String
        val loaded: Double
        val anchor: List<*>
        val radius: Double
        val S: Dict
        if (region.kind == LiveRegion.Kind.LIVING) {
            require(view == null || view == region.id || view == region.ladder.region) { "the living map ${region.id} has one view, not $view" }
            require(tolerance == 0.0) { "the living map loads at tolerance 0, not $tolerance" }
            loadView = region.id
            loaded = 0.0
            val cam = region.ladder.knots.first().cam
            anchor = cam["target"] as List<*>
            radius = (cam["radius"] as Number?)?.toDouble() ?: 1e9
            Regions.resetPlace(opts.daylight)
            S = Amsterdam.load(File(region.dir, "raw.json"), opts)
        } else {
            for (k in region.ladder.knots) if (k.name !in Regions.CAMS) Regions.CAMS[k.name] = LinkedHashMap(k.cam)
            val cam: Dict
            if (view != null) {
                val knot = region.ladder.knots.firstOrNull { it.name == view } ?: throw IllegalArgumentException("region ${region.id} has no view $view")
                require(tolerance == 0.0) { "a view loads at its own tolerance" }
                loadView = knot.name
                loaded = Lod.ofView(region.id, knot.name)
                cam = Regions.cfgFor(knot.name)
            } else {
                require(tolerance >= 0.0) { "a tolerance of $tolerance" }
                loadView = "${region.id}_$WHOLE" + if (tolerance == 0.0) "" else "${Math.round(tolerance * 1000)}"
                loaded = tolerance
                cam = wholeCamera(region)
                @Suppress("UNCHECKED_CAST")
                (Regions.GENERALISE as MutableMap<String, Double>)[loadView] = tolerance
                Regions.CAMS[loadView] = LinkedHashMap(cam)
            }
            anchor = cam["target"] as List<*>
            radius = (cam["radius"] as Number?)?.toDouble() ?: 1e9
            S = Regions.load(loadView, opts)
        }
        val millis = (System.nanoTime() - t0) / 1_000_000
        val bytes = (rt.totalMemory() - rt.freeMemory() - before).coerceAtLeast(0)
        LiveScene(region, loadView, loaded, S, EngineState.capture(), opts, (anchor[0] as Number).toDouble(), (anchor[2] as Number).toDouble(), radius, millis, bytes)
    }

    /** The view name a region loads under when it is loaded whole ([loadScene] without a view): `<region>_whole`, and the tolerance in millimetres after it when not 0. */
    const val WHOLE = "whole"

    /**
     * The camera a whole region is loaded for: the region's overview, aimed at the centre of the region's box (in its
     * frame), with a radius and a ground reaching the box's corners.
     */
    private fun wholeCamera(region: LiveRegion): Dict {
        val box = region.bbox ?: throw IllegalStateException("region ${region.id} has no box to load whole")
        val f = region.frame
        val x0 = f.x(box.south, box.west)
        val x1 = f.x(box.north, box.east)
        val z0 = f.z(box.north, box.west)
        val z1 = f.z(box.south, box.east)
        val cx = (x0 + x1) / 2
        val cz = (z0 + z1) / 2
        val reach = hypot(x1 - x0, z1 - z0) / 2
        val c: Dict = LinkedHashMap(region.ladder.knots.last().cam)
        c["target"] = listOf(cx, 0.0, cz)
        c["radius"] = reach
        c["ground_r"] = reach
        return c
    }

    /** Drops every kept scene (memory pressure, or the region's data changed). */
    fun forget(region: LiveRegion? = null) = lock.withLock {
        if (region == null) scenes.clear() else scenes.removeAll { it.region.id == region.id }
    }

    private fun roots(): Pair<File, File> = File(workDir, "regions") to File(workDir, "park")

    private fun setRoots(region: LiveRegion) {
        Regions.root = region.dir.parentFile
        Regions.parkRoot = roots().second
        Regions.streetCfgDir = File(workDir, "cameras")
    }

    /** The living map's recorded weather (the engine's resource), for a region built without one. */
    fun defaultWx(): String = Amsterdam::class.java.getResourceAsStream("/wx/ams.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
}

/** A region opened for the live map: what it is, its ground frame, its views, and where its files are. */
class LiveRegion internal constructor(
    val id: String,
    val bbox: BBox?,
    val kind: Kind,
    val frame: RegionFrame,
    val ladder: ViewLadder,
    /** The unpacked files (`raw.json`, `region.json`, …). */
    val dir: File,
    /** The region's time zone, for the live moment. */
    val zone: ZoneId,
    /** Its scene depends on the camera it was loaded for (terrain and relief measure around the view's target). */
    val viewDependent: Boolean,
    /** How long the unpacking took (0 when the files were there). */
    val unpackMillis: Long,
) {
    /** LIVING: the Westerkerk's living map (`amsterdam.load`); REGION: any other region (`regions.load`). */
    enum class Kind { LIVING, REGION }

    override fun toString() = "LiveRegion($id, $kind)"
}

/** A loaded scene: the engine's `S` for one region and band, with the module state its load left. */
class LiveScene internal constructor(
    val region: LiveRegion,
    val loadView: String,
    val tolerance: Double,
    /** The scene: the dict `render` draws from. */
    val S: Dict,
    /** The port's module state the load left (the place, its weather, palette and moments), put back before rendering it. */
    val state: EngineState,
    val opts: RenderOptions,
    val anchorX: Double,
    val anchorZ: Double,
    val radius: Double,
    val loadMillis: Long,
    /** The heap's growth over the load, bytes. */
    val bytes: Long,
)

/** One rendered frame: the layers as regions.py writes them (ground, objects, the second objects image, a raster). */
class LiveRender(
    val cfg: Dict,
    /** The region view the camera matched, or null between views. */
    val view: String?,
    /** The `THEMES` key rendered. */
    val themeKey: String,
    /** The moment's own name (sunny, grey, wet, night, …). */
    val moment: String,
    val g: String,
    val o: String,
    val o2: String?,
    val raster: Raster?,
    val tolerance: Double,
    val loadMillis: Long,
    var renderMillis: Long,
    val frame: RegionFrame,
) {
    /** The layers drawn in order: the ground, then what stands on it. */
    val layers: List<String> get() = listOfNotNull(g, o, o2)
}

/** What the engine is doing for a request, for the map's honest states. */
data class LiveProgress(val stage: Stage, val region: String, val fraction: Double) {
    enum class Stage { DOWNLOADING, UNPACKING, LOADING, RENDERING }
}

/** The port's module state a scene's load leaves (the place, its weather, palette and moments), to put back before rendering it. */
class EngineState private constructor(
    private val lat0: Double, private val lon0: Double, private val kx: Double, private val kz: Double, private val tzH: Int,
    private val brickOld: List<String>, private val plaster: List<String>, private val brickNew: List<String>,
    private val waterY: Double, private val quayCol: String, private val quayCourse: Double?, private val tone: Boolean, private val winStyle: String?,
    private val wxd: Map<String, Any?>, private val themes: LinkedHashMap<String, Dict>, private val airHaze: Boolean, private val cloudsLit: Boolean,
) {
    /** Puts the state back into the port's modules. */
    fun restore() {
        val a = Amsterdam
        a.LAT0 = lat0; a.LON0 = lon0; a.KX = kx; a.KZ = kz; a.TZ_H = tzH
        a.BRICK_OLD.clear(); a.BRICK_OLD.addAll(brickOld)
        a.PLASTER.clear(); a.PLASTER.addAll(plaster)
        a.BRICK_NEW.clear(); a.BRICK_NEW.addAll(brickNew)
        a.WATER_Y = waterY; a.QUAY_COL = quayCol; a.QUAY_COURSE = quayCourse; a.TONE = tone; a.WIN_STYLE = winStyle
        a.WXD = wxd
        a.THEMES.clear(); a.THEMES.putAll(themes)
        a.AIR_HAZE = airHaze; a.CLOUDS_LIT = cloudsLit
    }

    companion object {
        /** The module state as it is now. */
        fun capture(): EngineState {
            val a = Amsterdam
            return EngineState(
                a.LAT0, a.LON0, a.KX, a.KZ, a.TZ_H, a.BRICK_OLD.toList(), a.PLASTER.toList(), a.BRICK_NEW.toList(),
                a.WATER_Y, a.QUAY_COL, a.QUAY_COURSE, a.TONE, a.WIN_STYLE, a.WXD, LinkedHashMap(a.THEMES), a.AIR_HAZE, a.CLOUDS_LIT,
            )
        }
    }
}
