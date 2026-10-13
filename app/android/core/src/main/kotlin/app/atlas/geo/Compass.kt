package app.atlas.geo

/** The sixteen compass points, each with its bearing: for compass roses, wind and "head north-west". */
enum class Compass(val bearing: Double) {
    N(0.0), NNE(22.5), NE(45.0), ENE(67.5), E(90.0), ESE(112.5), SE(135.0), SSE(157.5),
    S(180.0), SSW(202.5), SW(225.0), WSW(247.5), W(270.0), WNW(292.5), NW(315.0), NNW(337.5);

    companion object {
        /** The nearest of [points] (4, 8 or 16) compass points to [bearing]. */
        fun of(bearing: Double, points: Int = 16): Compass {
            require(points == 4 || points == 8 || points == 16) { "points must be 4, 8 or 16" }
            val sector = 360.0 / points
            val i = (Math.floorMod(Math.round(Geo.normalize(bearing) / sector), points.toLong())).toInt()
            return entries[i * (16 / points)]
        }
    }
}
