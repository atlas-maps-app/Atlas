package app.atlas.nav

import app.atlas.geo.CoordinateParser
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.ui.mine.MapLookModel
import java.net.URLDecoder

/**
 * Reads what other apps hand Atlas into a back stack: `geo:` URIs (a point, a labelled point, or a search),
 * openstreetmap.org links (a node, way or relation; a marker; a map view; a short link), a palette's QR code
 * (`app.atlas:AT1-…`), and shared text holding a palette's code, coordinates, a Plus Code or a map link (anything else
 * shared becomes a search).
 *
 * A stack is the Map home, at the link's point where it has one, with the link's screen over it (a map view has none):
 * the app opens on it, and while the app runs it lands on top of the screens open ([Navigator.land]).
 */
object Links {
    private val OSM_ELEMENT = Regex("""(?i)(?:openstreetmap|osm)\.org/(node|way|relation)/(\d+)""")
    private val MAP_VIEW = Regex("""#map=(\d+(?:\.\d+)?)/(-?\d+(?:\.\d+)?)/(-?\d+(?:\.\d+)?)""")
    private val GEO_ZOOM = Regex("""[?&;]z=(\d+(?:\.\d+)?)""")
    private val GEO_LABEL = Regex("""\(([^)]*)\)\s*$""")

    /** The stack for a VIEW intent's [uri], or null when Atlas cannot read it. */
    fun forUri(uri: String): List<Dest>? {
        val u = uri.trim()
        return when {
            u.startsWith(MapLookModel.LINK, ignoreCase = true) -> palette(u)
            u.startsWith("geo:", ignoreCase = true) -> geo(u)
            u.contains("openstreetmap.org", ignoreCase = true) || u.contains("osm.org", ignoreCase = true) -> osm(u)
            else -> CoordinateParser.parse(u)?.let { point(it, null, null) }
        }
    }

    /** The stack for shared [text]: a point when it holds one, a place for an OSM link, else a search for it. */
    fun forText(text: String): List<Dest>? {
        val t = text.trim()
        if (t.isEmpty()) return null
        // a palette shared as its name and code: the Map look, which adds it
        palette(t)?.let { return it }
        Regex("""(?i)(geo:\S+|https?://\S+)""").find(t)?.let { m -> forUri(m.value)?.let { return it } }
        CoordinateParser.parse(t)?.let { return point(it, null, null) }
        return listOf(Dest.Home(), Dest.Search(t.take(200)))
    }

    /** A palette's code in [text] (the QR's `app.atlas:` link, a shared message): the Map look, handed the text to add. */
    private fun palette(text: String): List<Dest>? =
        MapLookModel.codeIn(text)?.let { listOf(Dest.Home(), Dest.MapLook(text.take(MAX_SHARED))) }

    private const val MAX_SHARED = 400

    private fun geo(u: String): List<Dest>? {
        val zoom = GEO_ZOOM.find(u)?.groupValues?.get(1)?.toDoubleOrNull()
        val query = Regex("""[?&]q=([^&]*)""").find(u)?.groupValues?.get(1)?.let { decode(it) }
        val p = CoordinateParser.parse(u)
        if (p != null) {
            val label = query?.let { GEO_LABEL.find(it)?.groupValues?.get(1) }?.takeIf { it.isNotBlank() }
            // geo:lat,lon?q=words (no coordinates in q): a search for the words near that point.
            val words = query?.trim()?.takeIf { label == null && it.isNotEmpty() && CoordinateParser.parse(it) == null }
            if (words != null) return listOf(Dest.Home(p.lat, p.lon, zoom ?: 17.0), Dest.Search(words.take(200)))
            return point(p, zoom, label)
        }
        // geo:0,0?q=some place: a search, near where the map is.
        return query?.takeIf { it.isNotBlank() }?.let { listOf(Dest.Home(), Dest.Search(it)) }
    }

    private fun osm(u: String): List<Dest>? {
        OSM_ELEMENT.find(u)?.let { m ->
            val id = OsmId.parse("${m.groupValues[1]}/${m.groupValues[2]}") ?: return@let
            val view = MAP_VIEW.find(u)
            val lat = view?.groupValues?.get(2)?.toDoubleOrNull()
            val lon = view?.groupValues?.get(3)?.toDoubleOrNull()
            return listOf(Dest.Home(lat, lon, view?.groupValues?.get(1)?.toDoubleOrNull()), Dest.Place(lat, lon, id.toString()))
        }
        val marker = Regex("""[?&]mlat=""").containsMatchIn(u)
        val p = CoordinateParser.parse(u) ?: return null
        val zoom = MAP_VIEW.find(u)?.groupValues?.get(1)?.toDoubleOrNull()
        // A marker is a point to look at; a bare map view only moves the map.
        return if (marker) point(p, zoom, null) else listOf(Dest.Home(p.lat, p.lon, zoom))
    }

    /** The map centred on [p], with what is there on top (or the place, when it has a label). */
    private fun point(p: LatLon, zoom: Double?, label: String?): List<Dest> {
        val home = Dest.Home(p.lat, p.lon, zoom ?: 17.0)
        return if (label != null) listOf(home, Dest.Place(p.lat, p.lon, name = label)) else listOf(home, Dest.WhatsHere(p.lat, p.lon, go = true))
    }

    /** Percent-decodes a query value; '+' is a space, except inside a Plus Code. */
    private fun decode(s: String): String {
        val keepPlus = PLUS_CODE.containsMatchIn(s)
        return runCatching { URLDecoder.decode(if (keepPlus) s.replace("+", "%2B") else s, Charsets.UTF_8) }.getOrDefault(s)
    }

    private val PLUS_CODE = Regex("""(?i)(^|[^A-Z0-9])[23456789CFGHJMPQRVWX]{2,8}\+[23456789CFGHJMPQRVWX]{0,3}""")
}
