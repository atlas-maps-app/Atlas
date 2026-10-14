// fetch/dem.py (imported by render/terrain.py and render/relief.py as `fetch_dem`): the pieces the renderer uses.
// Ported line by line. The fetching itself (PDOK, IGN) is the region data builder's, not the renderer's.
package app.atlas.render

import app.atlas.render.py.PyMath

/** The helpers of fetch/dem.py that the renderer imports. */
object FetchDem {
    // fetch/dem.py:8 rd
    /** WGS84 to Rijksdriehoek (EPSG:28992), the standard polynomial approximation (about a metre): (X, Y). */
    fun rd(lat: Double, lon: Double): V2 {
        val dp = 0.36 * (lat - 52.15517440); val dl = 0.36 * (lon - 5.38720621)
        val x = 155000 + 190094.945 * dl - 11832.228 * dp * dl - 114.221 * PyMath.pow(dp, 2) * dl - 32.391 * PyMath.pow(dl, 3) - 0.705 * dp -
            2.340 * PyMath.pow(dp, 3) * dl - 0.608 * dp * PyMath.pow(dl, 3) - 0.008 * PyMath.pow(dl, 2) + 0.148 * PyMath.pow(dp, 2) * PyMath.pow(dl, 3)
        val y = 463000 + 309056.544 * dp + 3638.893 * PyMath.pow(dl, 2) + 73.077 * PyMath.pow(dp, 2) - 157.984 * dp * PyMath.pow(dl, 2) +
            59.788 * PyMath.pow(dp, 3) + 0.433 * dl - 6.439 * PyMath.pow(dp, 2) * PyMath.pow(dl, 2) - 0.032 * dp * dl + 0.092 * PyMath.pow(dl, 4) -
            0.054 * dp * PyMath.pow(dl, 4)
        return V2(x, y)
    }
}
