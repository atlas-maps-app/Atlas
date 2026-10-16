package app.atlas.mapview

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.atlas.geo.LatLon
import app.atlas.mapview.svg.SvgFonts
import app.atlas.mapview.svg.TurbulenceCache
import app.atlas.render.JpegEncoder
import app.atlas.render.Relief
import app.atlas.render.RenderOptions
import app.atlas.render.live.LiveRegions
import app.atlas.render.live.WxHour
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** The forecast the live moment is lit by, for a place: what is to hand now, without waiting for the network. */
fun interface MapWeather {
    fun hours(at: LatLon): List<WxHour>?
}

/**
 * The map engine's process-wide wiring, set once by the app: where region data comes from ([regions]), the forecast
 * for the live moment ([weather]), the engine's flags ([options]). Every [AtlasMap] draws through it. Changes reach the
 * maps on screen through [invalidate].
 */
object MapEngine {
    /** The regions the maps draw; null draws "no map data" (tests that only exercise gestures). */
    @Volatile
    var regions: LiveRegions? = null
        set(value) {
            field = value
            invalidate()
        }

    @Volatile
    var weather: MapWeather? = null

    /** The engine's flags (amsterdam.py's ATLAS_AO, ATLAS_DAYLIGHT, …): the renders' defaults. */
    @Volatile
    var options: RenderOptions = RenderOptions()

    /** Largest raster scale (device pixels per dp) for exact frames; the screen's density when lower. */
    @Volatile
    var maxRasterScale: Float = 2f

    @Volatile
    private var fonts: SvgFonts? = null

    /** Where the engine keeps what it computes once for many frames and launches (the filters' noise, in `noise/`); null keeps nothing. */
    @Volatile
    var cacheDir: File? = null
        private set

    /**
     * Keeps the CPU filters' noise in [dir]`/noise` from now on ([TurbulenceCache]: mapped files, at most about 60 MB
     * held and 160 MB on disk), and warms it up in the background for a full-screen map on this device ([warmUp] with the
     * display's size). The app calls it once at start; the same directory again does nothing.
     */
    fun useCacheDir(dir: File, warm: Boolean = true) {
        synchronized(this) {
            if (cacheDir == dir) return
            cacheDir = dir
            // the cache before (if any) is let go, not closed: a frame being made may still read it
            TurbulenceCache.shared = TurbulenceCache(File(dir, "noise"))
        }
        if (!warm) return
        val m = runCatching { android.content.res.Resources.getSystem().displayMetrics }.getOrNull() ?: return
        if (m.density <= 0f || m.widthPixels <= 0 || m.heightPixels <= 0) return
        // the map's frame is the view's size in whole dp (MapController.liveView)
        warmUp(Math.round(m.widthPixels / m.density).toFloat(), Math.round(m.heightPixels / m.density).toFloat(), m.density)
    }

    /** Keeps nothing again (tests: the next test class must not write into a deleted directory). */
    internal fun forgetCacheDir() = synchronized(this) {
        cacheDir = null
        TurbulenceCache.shared = null
    }

    /**
     * Readies the noise of the screen-wide filters (paper grain, material grain, cloud shadows, water) for frames of
     * [widthDp] × [heightDp] at [density], on a background thread: the fields kept from earlier launches are mapped and
     * their tiles checked before the first frame needs them. A first launch has nothing kept yet; its first frame makes
     * the noise and keeps it. Returns the thread (null without a cache).
     */
    fun warmUp(widthDp: Float, heightDp: Float, density: Float): Thread? {
        val cache = TurbulenceCache.shared ?: return null
        val scale = minOf(density, maxRasterScale)
        val s = EngineFrame.filterScale(widthDp, heightDp, scale)
        val w = EngineFrame.pixels(widthDp, scale)
        val h = EngineFrame.pixels(heightDp, scale)
        val t = Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            try {
                cache.warmUp(w, h, s, s)
            } catch (e: Exception) {
                // a damaged cache is no reason to stop: frames make their noise as they go
                android.util.Log.w(EngineFrames.TAG, "noise warm-up failed: $e")
            }
        }, "atlas-noise-warmup")
        t.isDaemon = true
        t.start()
        return t
    }

    private val maps = CopyOnWriteArrayList<() -> Unit>()

    init {
        // relief.py writes the lit ground as a JPEG through macOS's sips; on the phone, Android's encoder at the same quality
        Relief.jpeg = JpegEncoder { png ->
            val b = BitmapFactory.decodeByteArray(png, 0, png.size) ?: error("the relief image did not decode")
            ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 80, it); b.recycle() }.toByteArray()
        }
    }

    /** The engine's typefaces, from this library's assets. */
    fun fonts(assets: AssetManager): SvgFonts = fonts ?: synchronized(this) { fonts ?: SvgFonts.fromAssets(assets).also { fonts = it } }

    /** Uses [f] for the engine's text (tests read the fonts from the source tree). */
    internal fun useFonts(f: SvgFonts) {
        fonts = f
    }

    /** Asks every map on screen for a new frame: region data, the forecast or the options changed. */
    fun invalidate() {
        for (m in maps) m()
    }

    internal fun register(onChange: () -> Unit) {
        maps += onChange
    }

    internal fun unregister(onChange: () -> Unit) {
        maps -= onChange
    }
}
