package app.atlas.mapview

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.atlas.geo.LatLon
import app.atlas.mapview.svg.SvgFonts
import app.atlas.render.JpegEncoder
import app.atlas.render.Relief
import app.atlas.render.RenderOptions
import app.atlas.render.live.LiveRegions
import app.atlas.render.live.WxHour
import java.io.ByteArrayOutputStream
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
