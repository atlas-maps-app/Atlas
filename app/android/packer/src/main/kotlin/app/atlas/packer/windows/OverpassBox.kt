package app.atlas.packer.windows

/**
 * A window's box as Overpass's bbox query takes it (`node(S,W,N,E)`, `way(S,W,N,E)`, `relation(S,W,N,E)`), with its
 * rules ported from Overpass's own source (drolbr/Overpass-API, master of October 2026), so a window cut from an extract
 * holds what a public Overpass server answers for its box:
 *  - a node is in the box by integers (data/bbox_filter.cc's filter_by_bbox): its stored latitude `lat_e7 + 910000000`
 *    and longitude `lon_e7` against the box's edges as index_computations.h's ilat_ and ilon_ round them;
 *  - a way by doubles (bbox_filter.cc's Bbox_Filter::matches): a way of one node when [contains] holds its point, any
 *    other when one pair of its consecutive points [intersects] the box;
 *  - a relation when a member node's point is in the box by [contains], or a member way is by the way's rule
 *    (filter_relations_expensive).
 * The doubles are the box's as the phone prints them and Overpass's atof reads them back (the same doubles), and a
 * point's as index_computations.h's lat and lon compute them from the stored integers ([lat], [lon]).
 */
class OverpassBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    private val iSouth = ilat(south)
    private val iNorth = ilat(north)
    private val iWest = ilon(west)
    private val iEast = ilon(east)

    /** filter_by_bbox: the node at ([latE7], [lonE7]) lies in the box, by Overpass's integers. */
    fun holdsNode(latE7: Int, lonE7: Int): Boolean {
        val lat = latE7 + LAT_OFFSET
        val lon = lonE7.toLong()
        return lat >= iSouth && lat <= iNorth && ((lon >= iWest && lon <= iEast) || (iEast < iWest && (lon >= iWest || lon <= iEast)))
    }

    /** geometry.cc's Bbox_Double::contains. */
    fun contains(lat: Double, lon: Double): Boolean {
        if (lat < south || lat > north) return false
        if (east >= west) return lon >= west && lon <= east
        return lon >= west || lon <= east
    }

    /** geometry.cc's Bbox_Double::intersects, line for line: whether the segment from one point to the other meets the box. */
    fun intersects(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Boolean {
        var fromLonAt = fromLon
        var toLonAt = toLon
        if (fromLon < 0.0 && toLon - fromLon > 180.0) fromLonAt += 360.0
        if (toLon < 0.0 && fromLon - toLon > 180.0) toLonAt += 360.0

        var deltaFrom = 0.0
        if (fromLat < south) {
            if (toLat < south) return false
            deltaFrom = (toLonAt - fromLonAt) * (south - fromLat) / (toLat - fromLat)
        } else if (fromLat > north) {
            if (toLat > north) return false
            deltaFrom = (toLonAt - fromLonAt) * (north - fromLat) / (toLat - fromLat)
        }

        if (toLat < south) toLonAt += (fromLonAt - toLonAt) * (south - toLat) / (fromLat - toLat)
        else if (toLat > north) toLonAt += (fromLonAt - toLonAt) * (north - toLat) / (fromLat - toLat)
        fromLonAt += deltaFrom

        // both ends now lie between south and north: whether the segment touches the box east to west
        val minLon = stdMin(fromLonAt, toLonAt)
        val maxLon = stdMax(fromLonAt, toLonAt)
        if (west <= east) {
            if (maxLon < 180.0) return minLon <= east && maxLon >= west
            else if (minLon > 180.0) return minLon - 360.0 <= east && maxLon - 360.0 >= west
            return minLon <= east && maxLon - 360.0 >= west
        }
        if (maxLon < 180.0) return minLon <= east || maxLon >= west
        else if (minLon > 180.0) return minLon - 360.0 <= east || maxLon - 360.0 >= west
        return true
    }

    override fun toString(): String = "($south,$west,$north,$east)"

    companion object {
        /** Overpass's stored latitude is the latitude in 1e-7 degrees plus this (91° in 1e-7 degrees). */
        const val LAT_OFFSET = 910_000_000L

        /** index_computations.h's ilat_: a latitude's stored integer, rounded half up, converted by truncation. */
        fun ilat(lat: Double): Long = ((lat + 91.0) * 10_000_000.0 + 0.5).toLong()

        /** index_computations.h's ilon_: a longitude's stored integer, rounded half away from zero, converted by truncation. */
        fun ilon(lon: Double): Long = (lon * 10_000_000.0 + (if (lon > 0) 0.5 else -0.5)).toLong()

        /** index_computations.h's lat: the double of a stored latitude, given in 1e-7 degrees. */
        fun lat(latE7: Int): Double = (latE7 + LAT_OFFSET).toDouble() / 10_000_000.0 - 91.0

        /** index_computations.h's lon: the double of a stored longitude, given in 1e-7 degrees. */
        fun lon(lonE7: Int): Double = lonE7.toDouble() / 10_000_000.0

        /** std::min of two doubles: the first unless the second is smaller. */
        fun stdMin(a: Double, b: Double): Double = if (b < a) b else a

        /** std::max of two doubles: the first unless it is smaller than the second. */
        fun stdMax(a: Double, b: Double): Double = if (a < b) b else a
    }
}

/**
 * A box around points as Overpass's `out geom` prints it ("bounds"): geometry.cc's calc_bounds over a way's points
 * ([ofPoints]) or over a relation's members ([ofParts]), the antimeridian's cases included. [valid] is Bbox_Double's.
 */
class OverpassBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    val valid: Boolean get() = south >= -90.0 && south <= north && north <= 90.0 && east >= -180.0 && east <= 180.0 && west >= -180.0 && west <= 180.0

    companion object {
        /** Bbox_Double::invalid. */
        val INVALID = OverpassBounds(100.0, 200.0, 100.0, 200.0)

        /** calc_bounds of a way's [n] points (lat and lon by index), all of them valid. */
        inline fun ofPoints(n: Int, lat: (Int) -> Double, lon: (Int) -> Double): OverpassBounds {
            var south = 100.0
            var west = 200.0
            var north = -100.0
            var east = -200.0
            for (i in 0 until n) {
                val la = lat(i)
                val lo = lon(i)
                if (la < 100.0) {
                    south = OverpassBox.stdMin(south, la)
                    west = OverpassBox.stdMin(west, lo)
                    north = OverpassBox.stdMax(north, la)
                    east = OverpassBox.stdMax(east, lo)
                }
            }
            if (north == -100.0) return INVALID
            if (east - west > 180.0) {
                // whether the box should rather cross the date line
                var wrappedWest = 180.0
                var wrappedEast = -180.0
                for (i in 0 until n) {
                    val la = lat(i)
                    val lo = lon(i)
                    if (la < 100.0) {
                        if (lo > 0) wrappedWest = OverpassBox.stdMin(wrappedWest, lo)
                        else wrappedEast = OverpassBox.stdMax(wrappedEast, lo)
                    }
                }
                return if (wrappedWest - wrappedEast > 180.0) OverpassBounds(south, wrappedWest, north, wrappedEast)
                // the points go around the world
                else OverpassBounds(south, -180.0, north, 180.0)
            }
            return OverpassBounds(south, west, north, east)
        }

        /** calc_bounds of a relation's members' boxes ([n] of them; a member without one, a relation, is left out by the caller). */
        fun ofParts(n: Int, part: (Int) -> OverpassBounds): OverpassBounds {
            var south = 100.0
            var west = 200.0
            var north = -100.0
            var east = -200.0
            var wrapped = false
            for (i in 0 until n) {
                val b = part(i)
                south = OverpassBox.stdMin(south, b.south)
                north = OverpassBox.stdMax(north, b.north)
                west = OverpassBox.stdMin(west, b.west)
                east = OverpassBox.stdMax(east, b.east)
                wrapped = wrapped or (b.east < b.west)
            }
            if (north == -100.0) return INVALID
            if (!wrapped && east - west <= 180.0) return OverpassBounds(south, west, north, east)
            west = 200.0
            east = -200.0
            for (i in 0 until n) {
                val b = part(i)
                if (b.east <= 0) {
                    east = OverpassBox.stdMax(east, b.east)
                    if (b.west >= 0) west = OverpassBox.stdMin(west, b.west)
                } else if (b.west >= 0) {
                    west = OverpassBox.stdMin(west, b.west)
                } else {
                    // the members are too wildly distributed
                    return OverpassBounds(south, -180.0, north, 180.0)
                }
            }
            if (west - east > 180.0) return OverpassBounds(south, west, north, east)
            return OverpassBounds(south, -180.0, north, 180.0)
        }
    }
}
