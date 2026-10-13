/*
 * Open Location Code (Plus Codes), following the specification and reference implementations at
 * https://github.com/google/open-location-code. The algorithm is used under the Apache License 2.0:
 * Copyright 2014 Google Inc. Licensed under the Apache License, Version 2.0; you may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0.
 */
package app.atlas.geo

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Plus Codes: a place as a short code such as "9F469VFP+R4" (about 14 m square), or "VFP+R4" near a reference point.
 * Encodes with integer arithmetic, as the reference implementations do, so codes match them exactly.
 */
object PlusCode {
    /** A decoded code: the cell's edges in degrees and the number of digits that made it. */
    data class Area(val south: Double, val west: Double, val north: Double, val east: Double, val length: Int) {
        val center: LatLon get() = LatLon((south + north) / 2, (west + east) / 2)
        val bbox: BBox get() = BBox(west, south, east, north)
    }

    const val ALPHABET = "23456789CFGHJMPQRVWX"
    const val SEPARATOR = '+'
    const val PADDING = '0'
    /** About 14 × 14 m: the length people share. */
    const val NORMAL_LENGTH = 10

    private const val SEPARATOR_POSITION = 8
    private const val MIN_DIGITS = 2
    private const val MAX_DIGITS = 15
    private const val PAIR_LENGTH = 10
    private const val GRID_LENGTH = MAX_DIGITS - PAIR_LENGTH
    private const val BASE = 20
    private const val LAT_MAX = 90L
    private const val LON_MAX = 180L
    private const val GRID_COLUMNS = 4
    private const val GRID_ROWS = 5
    private const val LAT_MULTIPLIER = 8000L * 3125
    private const val LON_MULTIPLIER = 8000L * 1024
    private const val LAT_MSP = LAT_MULTIPLIER * BASE * BASE
    private const val LON_MSP = LON_MULTIPLIER * BASE * BASE
    private const val FLOOR_NUDGE = 1e-6

    fun encode(p: LatLon, length: Int = NORMAL_LENGTH): String = encode(p.lat, p.lon, length)

    /** The code of the cell holding (lat, lon), with [length] digits: 2, 4, 6, 8, or 10 up to 15. */
    fun encode(lat: Double, lon: Double, length: Int = NORMAL_LENGTH): String {
        val ints = degreesToIntegers(lat, lon)
        return encodeIntegers(ints[0], ints[1], length)
    }

    /** Decodes a full code into its cell; throws [IllegalArgumentException] if it is not a valid full code. */
    fun decode(code: String): Area {
        require(isFull(code)) { "Not a valid full Plus Code: $code" }
        val clean = code.uppercase().replace(SEPARATOR.toString(), "").replace(PADDING.toString(), "")
        var latVal = -LAT_MAX * LAT_MULTIPLIER
        var lonVal = -LON_MAX * LON_MULTIPLIER
        var latPlace = LAT_MSP
        var lonPlace = LON_MSP
        var i = 0
        while (i < min(clean.length, PAIR_LENGTH)) {
            latPlace /= BASE
            lonPlace /= BASE
            latVal += ALPHABET.indexOf(clean[i]) * latPlace
            lonVal += ALPHABET.indexOf(clean[i + 1]) * lonPlace
            i += 2
        }
        for (k in PAIR_LENGTH until min(clean.length, MAX_DIGITS)) {
            latPlace /= GRID_ROWS
            lonPlace /= GRID_COLUMNS
            val digit = ALPHABET.indexOf(clean[k])
            latVal += (digit / GRID_COLUMNS) * latPlace
            lonVal += (digit % GRID_COLUMNS) * lonPlace
        }
        return Area(
            latVal.toDouble() / LAT_MULTIPLIER,
            lonVal.toDouble() / LON_MULTIPLIER,
            (latVal + latPlace).toDouble() / LAT_MULTIPLIER,
            (lonVal + lonPlace).toDouble() / LON_MULTIPLIER,
            min(clean.length, MAX_DIGITS),
        )
    }

    fun decodeOrNull(code: String): Area? = if (isFull(code)) decode(code) else null

    /** Whether [code] is a valid full or short code (case does not matter). */
    fun isValid(code: String?): Boolean {
        if (code == null || code.length < 2) return false
        val c = code.uppercase()
        val sep = c.indexOf(SEPARATOR)
        if (sep == -1 || sep != c.lastIndexOf(SEPARATOR)) return false
        // An even number of at most eight characters before the separator.
        if (sep % 2 != 0 || sep > SEPARATOR_POSITION) return false
        if (sep == SEPARATOR_POSITION) {
            // The first latitude digit takes only 9 values, the first longitude digit 18.
            if (ALPHABET.indexOf(c[0]) > 8) return false
            if (ALPHABET.indexOf(c[1]) > 17) return false
        }
        var paddingStarted = false
        for (i in 0 until sep) {
            val ch = c[i]
            if (ALPHABET.indexOf(ch) == -1 && ch != PADDING) return false
            if (paddingStarted) {
                if (ch != PADDING) return false
            } else if (ch == PADDING) {
                paddingStarted = true
                if (sep < SEPARATOR_POSITION) return false // short codes cannot be padded
                if (i != 2 && i != 4 && i != 6) return false
            }
        }
        if (c.length > sep + 1) {
            if (paddingStarted) return false
            if (c.length == sep + 2) return false // a single digit after the separator
            for (i in sep + 1 until c.length) if (ALPHABET.indexOf(c[i]) == -1) return false
        }
        return true
    }

    fun isFull(code: String?): Boolean = isValid(code) && code!!.indexOf(SEPARATOR) == SEPARATOR_POSITION

    fun isShort(code: String?): Boolean = isValid(code) && code!!.indexOf(SEPARATOR) < SEPARATOR_POSITION

    /**
     * Drops as many leading digits (2 to 8) as the reference point allows: "9F469VFP+R4" near the Westerkerk becomes
     * "VFP+R4". Throws if the code is not full, is padded, or the reference is too far from it.
     */
    fun shorten(code: String, refLat: Double, refLon: Double): String {
        require(isFull(code)) { "shorten() needs a full code: $code" }
        require(code.indexOf(PADDING) < 0) { "shorten() cannot shorten a padded code: $code" }
        val c = code.uppercase()
        val area = decode(c)
        val range = max(abs(refLat - area.center.lat), abs(refLon - area.center.lon))
        // Remove 8, 6, 4 or 2 digits if the reference is well within half that precision (0.3 for safety).
        for (i in 4 downTo 1) {
            if (range < latitudePrecision(i * 2) * 0.3) return c.substring(i * 2)
        }
        throw IllegalArgumentException("Reference location is too far from the Plus Code's centre")
    }

    /**
     * The full code nearest to a reference point that ends with the short [code]. A full code comes back unchanged
     * (upper-cased). Throws if [code] is not a valid code.
     */
    fun recoverNearest(code: String, refLat: Double, refLon: Double): String {
        require(isValid(code)) { "Not a valid Plus Code: $code" }
        val c = code.uppercase()
        if (isFull(c)) return c
        val lat = clipLatitude(refLat)
        val lon = normalizeLongitude(refLon)
        val digitsToRecover = SEPARATOR_POSITION - c.indexOf(SEPARATOR)
        // The size of the missing prefix's cell in degrees.
        val prefixPrecision = BASE.toDouble().pow(2 - digitsToRecover / 2)
        val prefix = encode(lat, lon).substring(0, digitsToRecover)
        val recovered = prefix + c
        val area = decode(recovered)
        var recLat = area.center.lat
        var recLon = area.center.lon

        // Move by one cell if the recovered area is more than half a cell from the reference.
        val latDiff = recLat - lat
        if (latDiff > prefixPrecision / 2 && recLat - prefixPrecision > -LAT_MAX) {
            recLat -= prefixPrecision
        } else if (latDiff < -prefixPrecision / 2 && recLat + prefixPrecision < LAT_MAX) {
            recLat += prefixPrecision
        }
        val lonDiff = area.center.lon - lon
        if (lonDiff > prefixPrecision / 2) {
            recLon -= prefixPrecision
        } else if (lonDiff < -prefixPrecision / 2) {
            recLon += prefixPrecision
        }
        return encode(recLat, recLon, recovered.length - 1)
    }

    /** Clipped, normalised integer latitude and longitude at full precision, as the specification defines them. */
    internal fun degreesToIntegers(latitude: Double, longitude: Double): LongArray {
        // A decimal such as 41.87 is stored a hair below itself, so its product with the multiplier can land just under
        // a whole number and floor one cell low. The nudge (a millionth of a 4 mm unit, above the product's rounding
        // error of about half that) makes every row of the specification's encoding.csv exact, where the reference
        // implementations' plain floor misses about 5% of them.
        var lat = floor(latitude * LAT_MULTIPLIER + FLOOR_NUDGE).toLong()
        var lon = floor(longitude * LON_MULTIPLIER + FLOOR_NUDGE).toLong()
        lat += LAT_MAX * LAT_MULTIPLIER
        if (lat < 0) {
            lat = 0
        } else if (lat >= 2 * LAT_MAX * LAT_MULTIPLIER) {
            lat = 2 * LAT_MAX * LAT_MULTIPLIER - 1
        }
        lon += LON_MAX * LON_MULTIPLIER
        // Wrap into 0 until the span. (The reference code adds the span after the remainder, which leaves exactly
        // one span, an invalid code, for longitudes such as −540°.)
        val lonSpan = 2 * LON_MAX * LON_MULTIPLIER
        lon = Math.floorMod(lon, lonSpan)
        return longArrayOf(lat, lon)
    }

    internal fun encodeIntegers(latIn: Long, lonIn: Long, lengthIn: Int): String {
        var lat = latIn
        var lon = lonIn
        val length = min(lengthIn, MAX_DIGITS)
        require(!(length < PAIR_LENGTH && length % 2 == 1 || length < MIN_DIGITS)) { "Illegal code length $lengthIn" }
        val rev = StringBuilder(MAX_DIGITS + 1)
        if (length > PAIR_LENGTH) {
            for (i in 0 until GRID_LENGTH) {
                val latDigit = lat % GRID_ROWS
                val lonDigit = lon % GRID_COLUMNS
                rev.append(ALPHABET[(latDigit * GRID_COLUMNS + lonDigit).toInt()])
                lat /= GRID_ROWS
                lon /= GRID_COLUMNS
            }
        } else {
            lat = (lat / GRID_ROWS.toDouble().pow(GRID_LENGTH)).toLong()
            lon = (lon / GRID_COLUMNS.toDouble().pow(GRID_LENGTH)).toLong()
        }
        for (i in 0 until PAIR_LENGTH / 2) {
            rev.append(ALPHABET[(lon % BASE).toInt()])
            rev.append(ALPHABET[(lat % BASE).toInt()])
            lat /= BASE
            lon /= BASE
            if (i == 0) rev.append(SEPARATOR)
        }
        val code = rev.reverse()
        if (length < SEPARATOR_POSITION) {
            for (i in length until SEPARATOR_POSITION) code.setCharAt(i, PADDING)
        }
        return code.substring(0, max(SEPARATOR_POSITION + 1, length + 1))
    }

    // Lengths up to 10 have equal latitude and longitude precision; the grid digits split cells 5 × 4.
    private fun latitudePrecision(length: Int): Double =
        if (length <= NORMAL_LENGTH) BASE.toDouble().pow(length / -2 + 2)
        else BASE.toDouble().pow(-3) / GRID_ROWS.toDouble().pow(length - PAIR_LENGTH)

    private fun clipLatitude(lat: Double) = lat.coerceIn(-90.0, 90.0)

    private fun normalizeLongitude(lon: Double): Double {
        if (lon >= -180 && lon < 180) return lon
        return ((lon % 360 + 360 + 180) % 360) - 180
    }
}
