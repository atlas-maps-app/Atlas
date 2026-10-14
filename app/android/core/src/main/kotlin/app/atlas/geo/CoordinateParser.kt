package app.atlas.geo

import java.net.URLDecoder
import kotlin.math.abs

/**
 * Reads a position from whatever people paste or type: decimal pairs in either order, hemispheres, degrees with
 * minutes and seconds, `geo:` URIs, map links, Plus Codes, UTM and MGRS. Returns null for anything else, so a search
 * box can try it first and fall through to place search.
 */
object CoordinateParser {
    /** Parses [text]; [near] recovers short Plus Codes and settles a lon/lat pair typed the wrong way round. */
    fun parse(text: String, near: LatLon? = null): LatLon? {
        val t = normalise(text)
        if (t.isEmpty() || t.length > 2048) return null
        val lower = t.lowercase()
        return when {
            lower.startsWith("geo:") -> geoUri(t)
            looksLikeUrl(lower) -> url(t)
            else -> plusCode(t, near)
                ?: Mgrs.parse(t)
                ?: utm(t)
                ?: labelled(t)
                ?: pair(t, near)
        }
    }

    // Unicode primes, quotes, degree look-alikes, minus signs and odd spaces to their plain forms.
    private fun normalise(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text.trim()) {
            sb.append(
                when (c) {
                    '′', '’', '‘', '´', '`', 'ʹ', 'ˈ' -> '\''
                    '″', '”', '“', 'ʺ', '˝' -> '"'
                    'º', '˚', '∘', '⁰' -> '°'
                    '−', '–', '‒' -> '-'
                    ' ', ' ', ' ', '\t', '\n', '\r' -> ' '
                    else -> c
                },
            )
        }
        return sb.toString().replace("''", "\"").trim()
    }

    private fun looksLikeUrl(lower: String) =
        lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.") ||
            lower.startsWith("osm.org") || lower.startsWith("openstreetmap.org") || lower.contains("://")

    // geo:lat,lon[,alt][;params][?q=lat,lon(label)&z=…] (RFC 5870 and Android's form).
    private fun geoUri(t: String): LatLon? {
        val m = GEO.find(t) ?: return null
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        val query = m.groupValues[3]
        if (lat == 0.0 && lon == 0.0 && query.isNotEmpty()) {
            val q = queryParam(query, "q")?.let(::percentDecode) ?: return null
            return Q_COORDS.find(q)?.let { valid(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
        }
        return valid(lat, lon)
    }

    // Map links: openstreetmap.org markers and views, osm.org short links, and the common query and path forms.
    private fun url(t: String): LatLon? {
        val decoded = percentDecode(t)
        val lower = decoded.lowercase()

        // A marker beats the view's centre.
        val mlat = queryParam(decoded, "mlat")?.toDoubleOrNull()
        val mlon = queryParam(decoded, "mlon")?.toDoubleOrNull()
        if (mlat != null && mlon != null) return valid(mlat, mlon)

        if (lower.contains("osm.org/go/") || lower.contains("openstreetmap.org/go/")) {
            val code = decoded.substringAfter("/go/").takeWhile { it.isLetterOrDigit() || it == '_' || it == '~' || it == '-' || it == '@' }
            OsmShortLink.decode(code)?.let { return it }
        }

        MAP_HASH.find(decoded)?.let { return valid(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
        AT_FORM.find(decoded)?.let { return valid(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }

        for (name in listOf("ll", "q", "query", "center", "destination", "daddr", "sll", "cp", "pos", "point")) {
            val v = queryParam(decoded, name) ?: continue
            Q_COORDS.find(v)?.let { m -> valid(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())?.let { return it } }
        }
        val lat = (queryParam(decoded, "lat") ?: queryParam(decoded, "latitude"))?.toDoubleOrNull()
        val lon = (queryParam(decoded, "lon") ?: queryParam(decoded, "lng") ?: queryParam(decoded, "longitude"))?.toDoubleOrNull()
        if (lat != null && lon != null) return valid(lat, lon)

        // A bare #zoom/lat/lon hash, as many map pages keep their view.
        ZOOM_HASH.find(decoded)?.let { return valid(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
        return null
    }

    // %XX escapes decoded; '+' kept as it is, since it can be a sign or a Plus Code's separator.
    private fun percentDecode(t: String): String =
        try { URLDecoder.decode(t.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { t }

    private fun queryParam(text: String, name: String): String? {
        val m = Regex("""(?:^|[?&#;])${Regex.escape(name)}=([^&#;]*)""", RegexOption.IGNORE_CASE).find(text) ?: return null
        return m.groupValues[1]
    }

    // A full code, or a short one with a reference; a locality after the code is ignored.
    private fun plusCode(t: String, near: LatLon?): LatLon? {
        val token = t.substringBefore(' ').substringBefore(',')
        if ('+' !in token || !PlusCode.isValid(token)) return null
        return when {
            PlusCode.isFull(token) -> PlusCode.decode(token).center
            near != null -> PlusCode.decode(PlusCode.recoverNearest(token, near.lat, near.lon)).center
            else -> null
        }
    }

    // "31U 628472 5803466", also with E/N suffixes or a hemisphere letter in place of the band.
    private fun utm(t: String): LatLon? {
        val m = UTM.matchEntire(t.uppercase()) ?: return null
        val zone = m.groupValues[1].toInt()
        if (zone !in 1..60) return null
        val letter = m.groupValues[2][0]
        val easting = m.groupValues[3].toDouble()
        val northing = m.groupValues[4].toDouble()
        if (easting < 100_000 || easting > 900_000 || northing < 0 || northing > 10_000_000) return null
        val north = when (letter) {
            'N' -> true
            // S is both the band 32–40°N and "south": take the band only if the point lands in it.
            'S' -> Utm.toLatLon(zone, true, easting, northing).lat.let { it >= 32 && it < 40 }
            else -> letter > 'N'
        }
        val p = Utm.toLatLon(zone, north, easting, northing)
        return valid(p.lat, p.lon)
    }

    // "lat 52.37 lon 4.88", "Latitude: 52.37, Longitude: 4.88" and the reverse order.
    private fun labelled(t: String): LatLon? {
        LABELLED.find(t)?.let { return valid(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
        LABELLED_REVERSED.find(t)?.let { return valid(it.groupValues[2].toDouble(), it.groupValues[1].toDouble()) }
        return null
    }

    // Two coordinates: decimal, degrees-minutes or degrees-minutes-seconds, with or without hemispheres.
    private fun pair(t: String, near: LatLon?): LatLon? {
        // A decimal comma ("52,3745 4,8838") when nothing else uses a point.
        if ('.' !in t && DECIMAL_COMMA.containsMatchIn(t)) {
            pairOf(t.replace(DECIMAL_COMMA, "."), near)?.let { return it }
        }
        return pairOf(t, near)
    }

    private fun pairOf(t: String, near: LatLon?): LatLon? {
        val tokens = tokenize(t) ?: return null
        val groups = split(tokens) ?: return null
        val a = readGroup(groups.first) ?: return null
        val b = readGroup(groups.second) ?: return null
        // Two bare integers ("12 34") are more likely a house number than a place.
        val bare = tokens.none { it.kind != Kind.NUM } && tokens.all { it.integer }
        if (bare) return null
        return assemble(a, b, near)
    }

    private enum class Kind { NUM, DEG, MIN, SEC, HEMI, SEP }

    /** One lexical piece; [spaced] when whitespace separates it from the piece before. */
    private class Token(
        val kind: Kind,
        val value: Double = 0.0,
        val integer: Boolean = false,
        val letter: Char = ' ',
        val letterStyle: Boolean = false,
        val spaced: Boolean = false,
    )

    private fun tokenize(s: String): List<Token>? {
        val out = ArrayList<Token>()
        var i = 0
        val u = s.uppercase()
        var spaced = false
        while (i < u.length) {
            val c = u[i]
            if (c == ' ') { spaced = true; i++; continue }
            when {
                c == ':' -> i++
                c.isDigit() || c == '.' || ((c == '-' || c == '+') && i + 1 < u.length && (u[i + 1].isDigit() || u[i + 1] == '.')) -> {
                    val start = i
                    if (c == '-' || c == '+') i++
                    var dots = 0
                    while (i < u.length && (u[i].isDigit() || u[i] == '.')) { if (u[i] == '.') dots++; i++ }
                    if (dots > 1) return null
                    val v = u.substring(start, i).toDoubleOrNull() ?: return null
                    out += Token(Kind.NUM, v, integer = dots == 0, spaced = spaced)
                }
                c == '°' -> { out += Token(Kind.DEG, spaced = spaced); i++ }
                c == '\'' -> { out += Token(Kind.MIN, spaced = spaced); i++ }
                c == '"' -> { out += Token(Kind.SEC, spaced = spaced); i++ }
                c == ',' || c == ';' || c == '/' || c == '|' -> { out += Token(Kind.SEP, spaced = spaced); i++ }
                c.isLetter() -> {
                    val start = i
                    while (i < u.length && u[i].isLetter()) i++
                    val word = u.substring(start, i)
                    val prevNum = out.lastOrNull()?.kind == Kind.NUM
                    val beforeNum = if (out.size >= 2) out[out.size - 2] else null
                    out += when {
                        prevNum && (word == "D" || word == "DEG") -> Token(Kind.DEG, spaced = spaced)
                        prevNum && (word == "M" || word == "MIN") -> Token(Kind.MIN, letterStyle = true, spaced = spaced)
                        prevNum && word == "SEC" -> Token(Kind.SEC, spaced = spaced)
                        // "28.3s" after letter-style minutes is seconds; otherwise S is south.
                        prevNum && word == "S" && beforeNum?.kind == Kind.MIN && beforeNum.letterStyle -> Token(Kind.SEC, spaced = spaced)
                        word == "N" || word == "NORTH" -> Token(Kind.HEMI, letter = 'N', spaced = spaced)
                        word == "S" || word == "SOUTH" -> Token(Kind.HEMI, letter = 'S', spaced = spaced)
                        word == "E" || word == "EAST" -> Token(Kind.HEMI, letter = 'E', spaced = spaced)
                        word == "W" || word == "WEST" -> Token(Kind.HEMI, letter = 'W', spaced = spaced)
                        else -> return null
                    }
                }
                else -> return null
            }
            spaced = false
        }
        // Separators at either end are noise.
        while (out.isNotEmpty() && out.first().kind == Kind.SEP) out.removeAt(0)
        while (out.isNotEmpty() && out.last().kind == Kind.SEP) out.removeAt(out.size - 1)
        return out
    }

    // Splits the tokens into the two coordinates.
    private fun split(tokens: List<Token>): Pair<List<Token>, List<Token>>? {
        val seps = tokens.indices.filter { tokens[it].kind == Kind.SEP }
        if (seps.size == 1) return tokens.subList(0, seps[0]) to tokens.subList(seps[0] + 1, tokens.size)
        if (seps.size > 1) return null

        val hemis = tokens.indices.filter { tokens[it].kind == Kind.HEMI }
        if (hemis.size == 2) {
            if (hemis[0] == 0 && hemis[1] != tokens.size - 1) return tokens.subList(0, hemis[1]) to tokens.subList(hemis[1], tokens.size)
            if (hemis[1] == tokens.size - 1 && hemis[0] != 0) return tokens.subList(0, hemis[0] + 1) to tokens.subList(hemis[0] + 1, tokens.size)
            // "52.37N E4.88": one trailing, one leading.
            if (hemis[1] == hemis[0] + 1) return tokens.subList(0, hemis[1]) to tokens.subList(hemis[1], tokens.size)
            return null
        }
        if (hemis.size > 2) return null

        // Degree marks start each coordinate.
        val degs = tokens.indices.filter { tokens[it].kind == Kind.DEG }
        if (degs.size == 2) {
            var cut = degs[1] - 1
            if (cut > 0 && tokens[cut - 1].kind == Kind.HEMI && startsSecond(tokens, cut - 1)) cut--
            if (cut <= 0) return null
            return tokens.subList(0, cut) to tokens.subList(cut, tokens.size)
        }
        if (degs.size > 2) return null

        // Otherwise split the numbers in half: 2 decimals, 4 for degrees-minutes, 6 for degrees-minutes-seconds.
        val nums = tokens.indices.filter { tokens[it].kind == Kind.NUM }
        if (nums.size % 2 != 0 || nums.isEmpty() || nums.size > 6) return null
        var cut = nums[nums.size / 2]
        if (cut > 0 && tokens[cut - 1].kind == Kind.HEMI && startsSecond(tokens, cut - 1)) cut--
        return tokens.subList(0, cut) to tokens.subList(cut, tokens.size)
    }

    /**
     * Whether the lone hemisphere letter at [h], between the two coordinates, leads the second rather than ends the
     * first: it goes with the number it touches ("52.37N 4.88", "52.37 E4.88"); spaced on both sides or touching
     * both, it takes the reading that keeps latitude first (N and S end the first, E and W lead the second).
     */
    private fun startsSecond(tokens: List<Token>, h: Int): Boolean {
        val touchesPrevious = !tokens[h].spaced
        val touchesNext = h + 1 < tokens.size && !tokens[h + 1].spaced
        if (touchesPrevious != touchesNext) return touchesNext
        return tokens[h].letter == 'E' || tokens[h].letter == 'W'
    }

    private class Coordinate(val value: Double, val axis: Char?)

    // One coordinate: [hemisphere] degrees [minutes [seconds]] [hemisphere], with or without marks.
    private fun readGroup(g: List<Token>): Coordinate? {
        if (g.isEmpty()) return null
        var hemi: Char? = null
        val parts = ArrayList<Token>()
        val marks = ArrayList<Kind?>()
        for ((k, tok) in g.withIndex()) {
            when (tok.kind) {
                Kind.HEMI -> {
                    if (hemi != null || (k != 0 && k != g.size - 1)) return null
                    hemi = tok.letter
                }
                Kind.NUM -> { parts += tok; marks += null }
                Kind.DEG, Kind.MIN, Kind.SEC -> {
                    if (marks.isEmpty() || marks[marks.size - 1] != null) return null
                    marks[marks.size - 1] = tok.kind
                }
                Kind.SEP -> return null
            }
        }
        if (parts.isEmpty() || parts.size > 3) return null

        // Which of degrees, minutes, seconds each number is: by its mark, else by position.
        val slot = IntArray(parts.size)
        for (k in parts.indices) {
            slot[k] = when (marks[k]) {
                Kind.DEG -> 0
                Kind.MIN -> 1
                Kind.SEC -> 2
                else -> if (k == 0) 0 else slot[k - 1] + 1
            }
            if (k > 0 && slot[k] <= slot[k - 1]) return null
            if (slot[k] > 2) return null
        }
        if (slot[0] != 0) return null
        // Only the last number may have a fraction; minutes and seconds stay below 60 and carry no sign.
        var value = 0.0
        var negative = false
        for (k in parts.indices) {
            val v = parts[k].value
            if (k < parts.size - 1 && !parts[k].integer) return null
            if (k > 0 && (v < 0 || v >= 60)) return null
            when (slot[k]) {
                0 -> { negative = v < 0 || 1 / v < 0; value += abs(v) }
                1 -> value += v / 60
                else -> value += v / 3600
            }
        }
        if (hemi == 'S' || hemi == 'W') negative = true
        val axis = when (hemi) { 'N', 'S' -> 'Y'; 'E', 'W' -> 'X'; else -> null }
        return Coordinate(if (negative) -value else value, axis)
    }

    private fun assemble(a: Coordinate, b: Coordinate, near: LatLon?): LatLon? {
        if (a.axis != null && b.axis != null) {
            if (a.axis == b.axis) return null
            return if (a.axis == 'Y') valid(a.value, b.value) else valid(b.value, a.value)
        }
        if (a.axis == 'X' || b.axis == 'Y') return valid(b.value, a.value)
        if (a.axis == 'Y' || b.axis == 'X') return valid(a.value, b.value)

        // No hemispheres: latitude first, unless only the other order fits.
        val straight = valid(a.value, b.value)
        val swapped = valid(b.value, a.value)
        if (straight == null) return swapped
        if (swapped != null && near != null) {
            // A lon/lat pair typed near here: take the swap only when it is much nearer.
            val ds = Geo.distance(near, straight)
            val dw = Geo.distance(near, swapped)
            if (dw * 20 < ds && dw < 1_000_000) return swapped
        }
        return straight
    }

    private fun valid(lat: Double, lon: Double): LatLon? =
        if (lat.isFinite() && lon.isFinite() && abs(lat) <= 90 && abs(lon) <= 180) LatLon(lat, lon) else null

    private const val NUM = """[-+]?\d+(?:\.\d+)?"""
    private val GEO = Regex("""^geo:\s*($NUM)\s*,\s*($NUM)(?:\s*,\s*$NUM)?(?:;[^?]*)?(?:\?(.*))?$""", RegexOption.IGNORE_CASE)
    private val Q_COORDS = Regex("""^\s*($NUM)\s*[, ~]\s*($NUM)""")
    private val MAP_HASH = Regex("""map=\d+(?:\.\d+)?/($NUM)/($NUM)""")
    private val ZOOM_HASH = Regex("""#\d+(?:\.\d+)?/($NUM)/($NUM)""")
    private val AT_FORM = Regex("""@($NUM),($NUM)""")
    private val UTM = Regex("""^(\d{1,2})\s*([C-HJ-NP-X])\s+(\d{5,7}(?:\.\d+)?)\s*M?E?\s*[, ]?\s*(\d{1,8}(?:\.\d+)?)\s*M?N?$""")
    private val LABELLED = Regex(
        """(?i)\blat(?:itude)?\b\s*[:=]?\s*($NUM)\s*[,;&]?\s*\b(?:lon|lng|long|longitude)\b\s*[:=]?\s*($NUM)""",
    )
    private val LABELLED_REVERSED = Regex(
        """(?i)\b(?:lon|lng|long|longitude)\b\s*[:=]?\s*($NUM)\s*[,;&]?\s*\blat(?:itude)?\b\s*[:=]?\s*($NUM)""",
    )
    private val DECIMAL_COMMA = Regex("""(?<=\d),(?=\d)""")
}

/**
 * OpenStreetMap's short links (osm.org/go/…). Longitude and latitude become 32-bit fractions of their ranges whose
 * bits interleave (x first) into one 64-bit Morton code; each character carries the next six bits, and each '-'
 * at the end lowers the zoom by one.
 */
object OsmShortLink {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_~"
    private const val SPAN = 4294967296.0 // 2^32

    /** The centre of the cell a short code names, or null if it holds no digits or too many. */
    fun decode(code: String): LatLon? {
        var morton = 0L
        var bits = 0
        for (c in code) {
            val digit = ALPHABET.indexOf(if (c == '@') '~' else c) // '@' is the old spelling of '~'
            if (digit < 0) continue // '-' only lowers the zoom
            if (bits == 60) return null
            morton = (morton shl 6) or digit.toLong()
            bits += 6
        }
        if (bits == 0) return null
        val perAxis = bits / 2
        val x = compact(morton ushr 1)
        val y = compact(morton)
        // Scale the cell's corner to the full 32 bits, then step to its middle.
        val cell = (1L shl (32 - perAxis)).toDouble()
        val lon = ((x shl (32 - perAxis)) + cell / 2) * 360.0 / SPAN - 180.0
        val lat = ((y shl (32 - perAxis)) + cell / 2) * 180.0 / SPAN - 90.0
        return LatLon(lat, lon)
    }

    /** The short code for a view centred on [p] at [zoom], as openstreetmap.org writes it. */
    fun encode(p: LatLon, zoom: Int): String {
        val x = Math.round((p.lon + 180.0) * (SPAN / 360.0)).coerceIn(0L, 0xFFFFFFFFL)
        val y = Math.round((p.lat + 90.0) * (SPAN / 180.0)).coerceIn(0L, 0xFFFFFFFFL)
        val morton = (spread(x) shl 1) or spread(y)
        val digits = Math.ceil((zoom + 8) / 3.0).toInt().coerceIn(1, 10)
        val sb = StringBuilder(digits + 2)
        for (i in 0 until digits) sb.append(ALPHABET[((morton ushr (58 - 6 * i)) and 0x3f).toInt()])
        repeat(Math.floorMod(zoom + 8, 3)) { sb.append('-') }
        return sb.toString()
    }

    // The 32 bits of v moved to the even bit positions of a long.
    private fun spread(v: Long): Long {
        var x = v and 0xFFFFFFFFL
        x = (x or (x shl 16)) and 0x0000FFFF0000FFFFL
        x = (x or (x shl 8)) and 0x00FF00FF00FF00FFL
        x = (x or (x shl 4)) and 0x0F0F0F0F0F0F0F0FL
        x = (x or (x shl 2)) and 0x3333333333333333L
        x = (x or (x shl 1)) and 0x5555555555555555L
        return x
    }

    // The inverse of spread: the even bits of v packed together.
    private fun compact(v: Long): Long {
        var x = v and 0x5555555555555555L
        x = (x or (x ushr 1)) and 0x3333333333333333L
        x = (x or (x ushr 2)) and 0x0F0F0F0F0F0F0F0FL
        x = (x or (x ushr 4)) and 0x00FF00FF00FF00FFL
        x = (x or (x ushr 8)) and 0x0000FFFF0000FFFFL
        x = (x or (x ushr 16)) and 0x00000000FFFFFFFFL
        return x
    }
}
