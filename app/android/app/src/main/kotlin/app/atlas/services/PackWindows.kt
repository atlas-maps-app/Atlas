package app.atlas.services

import app.atlas.geo.BBox
import app.atlas.mapview.vk.Windows
import app.atlas.render.data.ElementType
import app.atlas.render.data.Member
import app.atlas.render.data.OsmElement
import app.atlas.render.data.PackReader
import app.atlas.render.data.Points
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.GZIPOutputStream

/**
 * A window's data from the element packs on the phone (ENGINE.md step 3, the windows' local lane: MapEngine.windowLocal),
 * for a window the phone has no data of: Overpass's answer for the window's box as the packs that meet it hold it, the
 * newest pack's copy of an element two of them share, in Overpass's order (by type, then id), gzipped, dated by the
 * oldest pack's data. Until 5 November today's map drew those packs itself, offline too; the core draws them now, as
 * windows.
 *
 * A pack's query is the windows' own ([app.atlas.render.data.OverpassClient.query]), with the box over the whole query:
 * Overpass writes a point beyond it as null, which the core's reader refuses, so each null point is left out, as the
 * renders' loaders leave out `None` (a way's nodes are kept whole). The answer says it was never fetched whole
 * (`"whole"` 1970), so the windows ask for the window's own whole answer as soon as the network gives one.
 *
 * Where the packs' boxes hold part of the window's box alone, the answer says which part (`"atlas": {"box":
 * "south,west,north,east"}` on OpenStreetMap's grid of 1e-7°, the bounds of what they hold of it): the core lays the
 * window's plate under that part alone, so that offline, with the world on the phone, the packs' patch does not stand
 * in a bare box of its windows' tiles within the world's map (Bergen's sea, 11 November).
 */
object PackWindows {
    /** What the answer says of a whole fetch: never ([Windows.Head]'s `whole`). */
    private const val NEVER_WHOLE = "1970-01-01T00:00:00Z"

    /** The answer for [box] from the packs in [files] (newest data first wins); null where none holds any of it. */
    fun answer(files: List<File>, box: BBox): Windows.Packed? {
        val readers = files.mapNotNull { f -> runCatching { PackReader.open(f) }.getOrNull() }
        try {
            val meeting = readers.filter { r -> runCatching { r.manifest.bbox.intersects(box) }.getOrDefault(false) }
                .sortedByDescending { it.manifest.dataTime ?: "" }
            if (meeting.isEmpty()) return null
            val seen = HashMap<Pair<ElementType, Long>, OsmElement>()
            for (r in meeting) for (e in r.query(box)) seen.putIfAbsent(e.type to e.id, e)
            if (seen.isEmpty()) return null
            val elements = seen.values.sortedWith(compareBy<OsmElement> { it.type.ordinal }.thenBy { it.id })
            val date = meeting.mapNotNull { dateOf(it) }.minOrNull() ?: return null
            val text = StringBuilder(elements.size * 160)
            text.append("{\"version\":0.6,\"generator\":\"Atlas: the element packs on the phone\",")
                .append("\"osm3s\":{\"timestamp_osm_base\":\"").append(date).append("\",")
                .append("\"copyright\":\"The data included in this document is from www.openstreetmap.org. The data is made available under ODbL.\"},")
                .append("\"whole\":\"").append(NEVER_WHOLE).append("\",")
            held(meeting.map { it.manifest.bbox }, box)?.let { text.append("\"atlas\":{\"box\":\"").append(it).append("\"},") }
            text.append("\"elements\":[\n")
            for ((i, e) in elements.withIndex()) {
                if (i > 0) text.append(",\n")
                text.append(solid(e).toJson())
            }
            text.append("\n]}\n")
            val gz = ByteArrayOutputStream(text.length / 6)
            GZIPOutputStream(gz, 1 shl 16).use { it.write(text.toString().toByteArray(Charsets.UTF_8)) }
            return Windows.Packed(gz.toByteArray(), date, meeting.joinToString(" and ") { it.manifest.id })
        } finally {
            readers.forEach { runCatching { it.close() } }
        }
    }

    /**
     * The part of [box] the packs' boxes [packs] hold, where it is not all of it: the bounds of what each holds of it,
     * "south,west,north,east" on OpenStreetMap's grid (1e-7°); null where they hold all of it, or none.
     */
    internal fun held(packs: List<BBox>, box: BBox): String? {
        var south = Double.POSITIVE_INFINITY
        var west = Double.POSITIVE_INFINITY
        var north = Double.NEGATIVE_INFINITY
        var east = Double.NEGATIVE_INFINITY
        for (p in packs) {
            if (!p.intersects(box)) continue
            south = minOf(south, maxOf(p.south, box.south))
            west = minOf(west, maxOf(p.west, box.west))
            north = maxOf(north, minOf(p.north, box.north))
            east = maxOf(east, minOf(p.east, box.east))
        }
        if (!(south < north) || !(west < east)) return null
        if (south <= box.south && west <= box.west && north >= box.north && east >= box.east) return null
        fun grid(degrees: Double) = Math.round(degrees * 1e7)
        return "${grid(south)},${grid(west)},${grid(north)},${grid(east)}"
    }

    /** The date of a pack's data: its manifest's, else its extract's header's (`osm3s.timestamp_osm_base`), to the second. */
    private fun dateOf(r: PackReader): Instant? {
        val text = r.manifest.dataTime ?: ((r.manifest.header["osm3s"] as? JsonObject)?.get("timestamp_osm_base") as? JsonPrimitive)?.content
        return text?.let { runCatching { Instant.parse(it) }.getOrNull() }?.let { Instant.ofEpochSecond(it.epochSecond) }
    }

    /** [e] without the points its pack left null: a way's and each member's runs of points close up over them. */
    private fun solid(e: OsmElement): OsmElement {
        val g = e.geometry
        val ms = e.members
        if ((g == null || !g.hasNulls) && (ms == null || ms.none { it.geometry?.hasNulls == true })) return e
        return OsmElement(
            e.type, e.id, e.lat, e.lon, e.tags, e.bounds, e.nodes, g?.let(::solid),
            ms?.map { m -> m.geometry?.takeIf { it.hasNulls }?.let { mg -> Member(m.type, m.ref, m.role, m.lat, m.lon, solid(mg)) } ?: m },
        )
    }

    private fun solid(p: Points): Points {
        val keep = (0 until p.size).filter { !p.isNull(it) }
        return Points(DoubleArray(keep.size) { p.lat[keep[it]] }, DoubleArray(keep.size) { p.lon[keep[it]] })
    }
}
