package app.atlas.render.live

import app.atlas.render.Amsterdam
import app.atlas.render.City.Camera
import app.atlas.render.City.clamp
import app.atlas.render.Closeup
import app.atlas.render.V2
import app.atlas.render.V3
import app.atlas.render.py.PyFmt.pf
import app.atlas.render.py.PyMath
import app.atlas.render.py.PyRandom
import app.atlas.render.py.pyNum
import app.atlas.render.py.truthy
import app.atlas.render.v3

/**
 * The screen layers of a render (`render/amsterdam.py:615 render`): what it draws fixed to the screen rather than on the
 * ground. The sky's gradient, its clouds and its glows (`sky_layer`); the haze by ground depth in 17 stops, or at eye
 * level the soft band on the horizon; the golden wash; `wfx`, the clouds' shadows and the rain; and `fin`, the paper
 * grain and the vignette.
 *
 * Each is a pure function of the camera, a snapshot of the moment's theme (the `THEMES` entry with its sun vector `_L`)
 * and the camera's settings ([of]). A [Frame] holds the numbers, which the core computes again for every frame and holds
 * bit-equal to these (`CP/view/ScreenLayers.cpp`), and writes the render's own SVG text from them; the render places
 * each piece where it always has, so its output is byte for byte what it was.
 */
object ScreenLayers {
    /** The ground depth below which the haze is clear, and over which it thickens to its maximum, when a camera names none. */
    const val HAZE0 = 380.0
    const val HAZE1 = 800.0

    /** The haze's opacity curve: `haze_max · clamp((depth − haze0) / haze1) ^ 1.3`. */
    const val HAZE_POWER = 1.3

    /** The sky gradient runs from this many px above the horizon down to it. */
    const val SKY_RISE = 300.0

    /** The city's glow on the night cloud rises this many px above the horizon. */
    const val CITY_GLOW_RISE = 220.0

    /** The sun's glow is drawn when its point lies within these margins of the frame, px (left, right and top; below the horizon). */
    const val SUN_MARGIN = 260.0
    const val SUN_BELOW_HORIZON = 60.0

    /**
     * The clouds of the sky layer (`cloudf`): fractal noise turned into colour ([red], [green], [blue]) with alpha
     * `k · (n − t)`; the shadows under them (`cloudsh2`) are the same noise 5 px lower, darker, from `t + 0.04`.
     */
    class Clouds(val k: Double, val t: Double, val red: Double, val green: Double, val blue: Double)

    /**
     * The haze: a vertical gradient of the theme's haze colour from row [y0] to row [y1], with [offsets] along it and the
     * opacity [alphas] at each, as the render prints them. [band] is the eye-level band on the horizon (25 stops);
     * otherwise 17 stops from the horizon (or the top of the frame) to the bottom, one per sixteenth of the way.
     */
    class Haze(val band: Boolean, val y0: Double, val y1: Double, val offsets: DoubleArray, val alphas: DoubleArray)

    /** The numbers of one render's screen layers, and their SVG text. */
    class Frame internal constructor(
        /** The frame, CSS px (`cfg['W']`, `cfg['H']`). */
        val width: Double,
        val height: Double,
        /** The horizon's row (`Camera.horizon_y`); above the frame at every map pitch. */
        val horizon: Double,
        /** The moment has weather (`th['wx']`): the sky layer, `wfx` and `fin` exist only then. */
        val weather: Boolean,
        val night: Boolean,
        /** Overcast (`th['sky_k']`): the clouds' deck and the sun's glow are the grey ones. */
        val overcast: Boolean,
        /** At eye level (weather and an engine pitch under 10°). */
        val eye: Boolean,
        /** The render's layer: `front` leaves the sky (and the rain) out. */
        val layer: String?,
        /** The sky gradient's colours, top and at the horizon. */
        val skyTop: String,
        val skyHorizon: String,
        /** The sky layer's clouds, when it is drawn (weather, and the horizon more than 4 px below the top); else null. */
        val clouds: Clouds?,
        /** The sky layer's bottom row: two px under the horizon, at most the frame's height. */
        val skyBottom: Double,
        /** Where the sun's glow is drawn, when the sky layer shows it; else null. */
        val sun: V2?,
        /** The theme's haze colour, and the haze over the frame. */
        val hazeColour: String,
        val haze: Haze,
        /** The golden wash over everything (the `golden` moment only). */
        val wash: Boolean,
        /** The cloud shadows' alpha offset (`-7 · (0.62 − 0.3 · cover)`), when they are drawn; else NaN. */
        val cloudShadowCut: Double,
        /** The rain's angle, degrees (`int(wind · 0.8)`), when it rains; else null. */
        val rainAngle: Long?,
        /** The grain and the vignette are drawn (`fin`). */
        val drawsFin: Boolean,
    ) {
        /** The rows of the sky gradient: [SKY_RISE] px above the horizon, and the horizon. */
        val skyRows: Pair<Double, Double> get() = (horizon - SKY_RISE) to horizon

        /** The cloud shadows are drawn. */
        val cloudShadows: Boolean get() = !cloudShadowCut.isNaN()

        /** The grain's opacity, as the render prints it. */
        val grainOpacity: String get() = if (!night) ".09" else ".12"

        /** The vignette's opacity at the corners, as the render prints it. */
        val vignetteOpacity: String get() = if (night) ".38" else ".2"

        // render/amsterdam.py:638 render: the grain filter and the vignette
        /** The filter and gradient `fin` draws with (`grain`, `vig`), which a render with weather defines whether or not it draws them. */
        fun finDefs(): List<String> = listOf(
            pf("<filter id=\"grain\" filterUnits=\"userSpaceOnUse\" x=\"0\" y=\"0\" width=\"%d\" height=\"%d\"><feTurbulence type=\"fractalNoise\" baseFrequency=\".9\" numOctaves=\"1\" seed=\"8\"/><feColorMatrix type=\"saturate\" values=\"0\"/></filter>", width, height),
            pf("<radialGradient id=\"vig\" cx=\".5\" cy=\".46\" r=\".75\"><stop offset=\".55\" stop-color=\"#000\" stop-opacity=\"0\"/><stop offset=\"1\" stop-color=\"#000\" stop-opacity=\"%s\"/></radialGradient>", vignetteOpacity),
        )

        // render/amsterdam.py:644 render: the sky gradient
        /** The sky gradient's definition. */
        fun skyDef(): String = ScreenLayers.skyDef(horizon, skyTop, skyHorizon)

        /** The sky over the whole frame, or nothing for the `front` layer. */
        fun skyRect(): String = if (layer != "front") ScreenLayers.skyRect(width, height) else ""

        // render/amsterdam.py:284 sky_layer
        /**
         * The sky layer (`sky_layer`): the clouds and their shadows, the sun's glow, and at night the city's glow on the
         * cloud. Adds its definitions to [defs] and returns its elements, or "" when no sky layer is drawn. At eye level
         * with lit clouds ([Amsterdam.CLOUDS_LIT]) the clouds are `Closeup.sky_clouds`' at height, for [cam] and [th].
         */
        fun skyLayer(cam: Camera, th: MutableMap<String, Any?>, defs: MutableList<String>, cloudsLit: Boolean = Amsterdam.CLOUDS_LIT): String {
            val c = clouds ?: return ""
            val W = width; val hb = skyBottom; val out = ArrayList<String>()
            defs.add(pf("<filter id=\"cloudf\" filterUnits=\"userSpaceOnUse\" x=\"0\" y=\"0\" width=\"%d\" height=\"%d\"><feTurbulence type=\"fractalNoise\" baseFrequency=\"0.0055 0.017\" numOctaves=\"4\" seed=\"31\"/><feColorMatrix type=\"matrix\" values=\"0 0 0 0 %.2f  0 0 0 0 %.2f  0 0 0 0 %.2f  %.1f 0 0 0 %.2f\"/></filter>",
                W, hb, c.red, c.green, c.blue, c.k, -c.k * c.t))
            defs.add(pf("<filter id=\"cloudsh2\" filterUnits=\"userSpaceOnUse\" x=\"0\" y=\"0\" width=\"%d\" height=\"%d\"><feTurbulence type=\"fractalNoise\" baseFrequency=\"0.0055 0.017\" numOctaves=\"4\" seed=\"31\"/><feOffset dy=\"%d\"/><feColorMatrix type=\"matrix\" values=\"0 0 0 0 %.2f  0 0 0 0 %.2f  0 0 0 0 %.2f  %.1f 0 0 0 %.2f\"/></filter>",
                W, hb, 5, c.red * 0.62, c.green * 0.64, c.blue * 0.7, c.k, -c.k * (c.t + 0.04)))
            val sxy = sun
            if (sxy != null) {
                defs.add(pf("<radialGradient id=\"sunglow\"><stop offset=\"0\" stop-color=\"#FFF8E6\" stop-opacity=\"%s\"/><stop offset=\".25\" stop-color=\"#FFE9C2\" stop-opacity=\"%s\"/><stop offset=\"1\" stop-color=\"#FFE0B0\" stop-opacity=\"0\"/></radialGradient>",
                    if (overcast) ".75" else "1", if (overcast) ".25" else ".45"))
                th["_sunxy"] = sxy
            }
            if (eye && cloudsLit) {
                // the sun first, so the clouds can pass in front of it; then a little of its glare back over them
                if (sxy != null) {
                    out.add(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%d\" fill=\"url(#sunglow)\"/>", sxy.x, sxy.y, if (overcast) 230 else 170))
                    if (!overcast) out.add(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"9\" fill=\"#FFFDF4\"/>", sxy.x, sxy.y))
                }
                if (layer != "front") out.add(Closeup.skyClouds(cam, th, W, horizon, defs, cam.C[1]))
                if (sxy != null) out.add(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%d\" fill=\"url(#sunglow)\" opacity=\".55\" style=\"mix-blend-mode:screen\"/>", sxy.x, sxy.y, if (overcast) 150 else 120))
            } else {
                out.add(pf("<rect width=\"%d\" height=\"%.1f\" filter=\"url(#cloudsh2)\"/><rect width=\"%d\" height=\"%.1f\" filter=\"url(#cloudf)\"/>", W, hb, W, hb))
                if (sxy != null) {
                    out.add(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%d\" fill=\"url(#sunglow)\"/>", sxy.x, sxy.y, if (overcast) 230 else 170))
                    if (!overcast) out.add(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"9\" fill=\"#FFFDF4\"/>", sxy.x, sxy.y))
                }
            }
            if (night && layer != "front") { // the city lights the underside of the low cloud
                defs.add(ScreenLayers.cityGlowDef(horizon))
                out.add(pf("<rect width=\"%d\" height=\"%.1f\" fill=\"url(#cityglow)\"/>", W, hb))
            }
            return out.joinToString("")
        }

        // render/amsterdam.py:899 render: the haze by ground depth (a soft band on the horizon at eye level)
        /** The haze's gradient definition. */
        fun hazeDef(): String {
            val h = haze
            val stops = StringBuilder()
            for (i in h.offsets.indices) stops.append(pf("<stop offset=\"%.3f\" stop-color=\"%s\" stop-opacity=\"%.3f\"/>", h.offsets[i], hazeColour, h.alphas[i]))
            return if (h.band) pf("<linearGradient id=\"haze\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"%.1f\" x2=\"0\" y2=\"%.1f\">%s</linearGradient>", h.y0, h.y1, stops.toString())
            else pf("<linearGradient id=\"haze\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"%.1f\" x2=\"0\" y2=\"%d\">%s</linearGradient>", h.y0, h.y1, stops.toString())
        }

        /** The haze's rectangle. */
        fun hazeRect(): String = pf("<rect y=\"%.1f\" width=\"%d\" height=\"%.1f\" fill=\"url(#haze)\"/>", haze.y0, width, haze.y1 - haze.y0)

        // render/amsterdam.py:921 render: the golden wash
        /** The golden wash's gradient definition (drawn only when [wash]). */
        fun washDef(): String = "<linearGradient id=\"wash\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\".35\"><stop offset=\"0\" stop-color=\"#FFB25E\" stop-opacity=\".14\"/><stop offset=\".45\" stop-color=\"#FFC98A\" stop-opacity=\".05\"/><stop offset=\"1\" stop-color=\"#FFC98A\" stop-opacity=\"0\"/></linearGradient>"

        /** The golden wash's rectangle. */
        fun washRect(): String = pf("<rect width=\"%d\" height=\"%d\" fill=\"url(#wash)\"/>", width, height)

        /**
         * The haze and, at the golden moment, the wash over it, as the render writes them after the objects: adds their
         * definitions to [defs] and returns their elements.
         */
        fun hazeAndWash(defs: MutableList<String>): String {
            defs.add(hazeDef())
            var s = hazeRect()
            if (wash) {
                defs.add(washDef())
                s += washRect()
            }
            return s
        }

        // render/amsterdam.py:943 render: wfx, the cloud shadows and the drizzle
        /**
         * `wfx`: the clouds' shadows drifting over the ground (by `multiply`) and the rain's streaks, leaning with the wind.
         * Adds their definitions to [defs] and returns their elements.
         */
        fun wfx(defs: MutableList<String>): String {
            var wfx = ""
            if (!weather) return wfx
            if (cloudShadows) { // at eye level the ground is too foreshortened for cloud shadows to read
                defs.add(pf("<filter id=\"cloudsh\" x=\"0\" y=\"0\" width=\"100%%\" height=\"100%%\"><feTurbulence type=\"fractalNoise\" baseFrequency=\"0.0026 0.0058\" numOctaves=\"3\" seed=\"23\"/><feColorMatrix type=\"matrix\" values=\"0 0 0 0 0.16  0 0 0 0 0.19  0 0 0 0 0.32  7 0 0 0 %.2f\"/></filter>", cloudShadowCut))
                val top = PyMath.max(0.0, horizon)
                wfx += pf("<rect y=\"%.1f\" width=\"%d\" height=\"%.1f\" filter=\"url(#cloudsh)\" opacity=\".42\" style=\"mix-blend-mode:multiply\"/>", top, width, height - top)
            }
            val angle = rainAngle
            if (angle != null) {
                defs.add(pf("<pattern id=\"rain\" width=\"127\" height=\"211\" patternUnits=\"userSpaceOnUse\" patternTransform=\"rotate(%d)\">%s</pattern>", angle, rainStreaks()))
                if (layer != "front") wfx += pf("<rect width=\"%d\" height=\"%d\" fill=\"url(#rain)\" opacity=\".45\"/>", width, height)
            }
            return wfx
        }

        // render/amsterdam.py:964 render: fin
        /** `fin`: the paper grain (by `overlay`) and the vignette over everything, or "" where the render draws none. */
        fun fin(): String = if (drawsFin) pf("<rect width=\"%d\" height=\"%d\" filter=\"url(#grain)\" opacity=\"%s\" style=\"mix-blend-mode:overlay\"/><rect width=\"%d\" height=\"%d\" fill=\"url(#vig)\"/>",
            width, height, grainOpacity, width, height) else ""
    }

    /**
     * The screen layers of a render with camera [cam], theme snapshot [th] (a `THEMES` entry with its sun vector `_L`),
     * the camera's settings [cfg] (`W`, `H`, `target`, `pitch`, `haze0`, `haze1`, `no_fin`), the `THEMES` key [themeKey]
     * and the render's [layer]. Projects the sun's point with [cam] only where the sky layer needs it, as the render does.
     */
    fun of(cam: Camera, th: Map<String, Any?>, cfg: Map<String, Any?>, themeKey: String, layer: String? = null): Frame {
        val W = pyNum(cfg["W"]); val H = pyNum(cfg["H"])
        val weather = truthy(th["wx"])
        val night = th["night"] == true
        val overcast = truthy(th["sky_k"])
        val pitch = if ("pitch" in cfg) pyNum(cfg["pitch"]) else 90.0
        val eye = weather && pitch < 10
        val hy = cam.horizonY()
        var clouds: Clouds? = null
        var sun: V2? = null
        if (weather && hy > 4) {
            @Suppress("UNCHECKED_CAST")
            val w = th["wx"] as Map<String, Any?>
            clouds = when {
                night -> Clouds(1.5, -0.1, 0.17, 0.15, 0.19)
                overcast -> Clouds(2.2, 0.18, 0.66, 0.68, 0.72)
                else -> Clouds(9.0, 0.62 - pyNum(w["cloud"]) * 0.25, 0.98, 0.98, 0.99)
            }
            val L = v3(th["_L"])
            if (!night && L[1] > 0) sun = sunPoint(cam, L, W, hy)
        }
        val haze = haze(cam, pyNum(th["haze_max"]), num(cfg, "haze0", HAZE0), num(cfg, "haze1", HAZE1), v3(cfg["target"])[1], eye, hy, H)
        var cut = Double.NaN
        if (weather && truthy(th["cloud_shadows"]) && !eye) cut = -7 * (0.62 - pyNum(th["cloud_shadows"]) * 0.3)
        var rain: Long? = null
        if (weather && truthy(th["wet"])) {
            @Suppress("UNCHECKED_CAST")
            rain = PyMath.int(pyNum((th["wx"] as Map<String, Any?>)["wind"]) * 0.8)
        }
        val fin = weather && layer != "front" && !eye && !truthy(cfg["no_fin"])
        return Frame(
            W, H, hy, weather, night, overcast, eye, layer, th["sky_top"] as String, th["sky_hor"] as String, clouds, PyMath.min(H, hy + 2), sun,
            th["haze"] as String, haze, themeKey == "golden", cut, rain, fin,
        )
    }

    // render/amsterdam.py:899 render: the 17 stops, and the eye-level band
    /**
     * The haze over a frame [height] px tall whose horizon is at row [hy]: 17 stops from the horizon (or the frame's top)
     * down, each `haze_max · clamp((d − haze0) / haze1) ^ 1.3` for the ground depth d under its row (`haze_max` where the
     * row sees no ground); at eye level the 25-stop band around the horizon instead, at most 18 % of `haze_max`.
     */
    fun haze(cam: Camera, hazeMax: Double, haze0: Double, haze1: Double, groundY: Double, eye: Boolean, hy: Double, height: Double): Haze {
        val H = height
        fun alpha(gd: Double?): Double = if (gd == null) hazeMax else hazeMax * PyMath.pow(clamp((gd - haze0) / haze1), HAZE_POWER)
        if (eye && hy > 0) {
            // at eye level, far things sit on the horizon line from both sides: a soft band, never an edge
            val ys = DoubleArray(25); val as_ = DoubleArray(25)
            for (k in -12..12) {
                val dy = (H - hy) * PyMath.pow(Math.abs(k) / 12.0, 2.0) * 0.5
                val gd = cam.groundDepth(hy + PyMath.max(dy, 0.01), groundY)
                ys[k + 12] = hy + (if (k > 0) dy else -dy)
                as_[k + 12] = PyMath.min(alpha(gd), hazeMax * 0.18)
            }
            val y0 = ys[0]; val y1 = ys[24]
            return Haze(true, y0, y1, DoubleArray(25) { (ys[it] - y0) / (y1 - y0) }, DoubleArray(25) { as_[it] * 0.8 })
        }
        val top = PyMath.max(0.0, hy)
        val offsets = DoubleArray(17); val alphas = DoubleArray(17)
        for (k in 0 until 17) {
            val sy = top + (H - top) * k / 16
            alphas[k] = alpha(cam.groundDepth(sy + 0.01, groundY))
            offsets[k] = k / 16.0
        }
        return Haze(false, top, H, offsets, alphas)
    }

    // render/amsterdam.py:297 sky_layer: the sun's glow
    /** The sun's point on the screen, 4 km out along the sun vector [L] from the eye, when its glow reaches the frame; else null. */
    fun sunPoint(cam: Camera, L: V3, width: Double, hy: Double): V2? {
        val sp = cam.proj(V3(cam.C[0] + L[0] * 4000, cam.C[1] + L[1] * 4000, cam.C[2] + L[2] * 4000))
        return if (sp != null && -SUN_MARGIN < sp[0] && sp[0] < width + SUN_MARGIN && -SUN_MARGIN < sp[1] && sp[1] < hy + SUN_BELOW_HORIZON) sp else null
    }

    /** The sky gradient's definition for a horizon at row [hy]: [top] above, [horizon] at the horizon. */
    fun skyDef(hy: Double, top: Any?, horizon: Any?): String =
        pf("<linearGradient id=\"sky\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"%.0f\" x2=\"0\" y2=\"%.0f\"><stop offset=\"0\" stop-color=\"%s\"/><stop offset=\"1\" stop-color=\"%s\"/></linearGradient>", hy - SKY_RISE, hy, top, horizon)

    /** The sky over a [width] × [height] frame. */
    fun skyRect(width: Double, height: Double): String = pf("<rect width=\"%d\" height=\"%d\" fill=\"url(#sky)\"/>", width, height)

    /** The city's glow on the night cloud, for a horizon at row [hy]. */
    fun cityGlowDef(hy: Double): String =
        pf("<linearGradient id=\"cityglow\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"%.0f\" x2=\"0\" y2=\"%.0f\"><stop offset=\"0\" stop-color=\"#FF9E5A\" stop-opacity=\"0\"/><stop offset=\"1\" stop-color=\"#FF9E5A\" stop-opacity=\".28\"/></linearGradient>", hy - CITY_GLOW_RISE, hy)

    // render/amsterdam.py:948 render: the drizzle's streaks
    /** The rain's tile: 26 short faint streaks scattered by Python's `random.Random(4)`, the same at every moment. */
    fun rainStreaks(): String {
        val rnd = PyRandom(4) // drizzle: short faint streaks scattered at random, leaning with the wind
        val st = StringBuilder()
        repeat(26) {
            val x = rnd.uniform(0.0, 127.0); val y = rnd.uniform(0.0, 211.0); val l = rnd.uniform(3.0, 8.0); val sw = rnd.uniform(0.3, 0.55); val op = rnd.uniform(0.35, 0.9)
            st.append(pf("<path d=\"M%.1f %.1fv%.1f\" stroke=\"#C3CEE8\" stroke-width=\"%.2f\" opacity=\"%.2f\" stroke-linecap=\"round\"/>", x, y, l, sw, op))
        }
        return st.toString()
    }

    private fun num(cfg: Map<String, Any?>, key: String, default: Double): Double = if (key in cfg) pyNum(cfg[key]) else default
}
