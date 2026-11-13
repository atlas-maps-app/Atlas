package app.atlas.services

import android.util.Log
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
 * renders' loaders leave out `None` (a way's nodes are kept whole). A closed way whose first point lay beyond the box
 * would no longer close, and the core takes a building or a water only from a ring that does (load/Amsterdam.cpp's
 * `closed`): it is closed again over the points left ([solid]).
 *
 * Where one pack's box holds the window's whole box, and every pack's date is its data's own, the answer is whole as
 * of that date (`"atlas": {"whole": …}`): the window stays on the pack's data as one taken from a country's tiles
 * does, asked for Overpass's changes a week on and whole again only once the data is four weeks old, so a kept
 * region's first connection asks Overpass nothing. Where the window's box reaches beyond every pack's box, or a
 * pack's date is not known (below), its data is not whole there: the answer says it was never fetched whole
 * (`"whole"` 1970), and the windows ask for the window's own whole answer as soon as the network gives one.
 *
 * Where the packs' boxes hold part of the window's box alone, the answer says which part (`"atlas": {"box":
 * "south,west,north,east"}` on OpenStreetMap's grid of 1e-7°, the bounds of what they hold of it): the core lays the
 * window's plate under that part alone, so that offline, with the world on the phone, the packs' patch does not stand
 * in a bare box of its windows' tiles within the world's map (Bergen's sea, 11 November).
 */
object PackWindows {
    /** What the answer says of a whole fetch: never ([Windows.Head]'s `whole`). */
    private const val NEVER_WHOLE = "1970-01-01T00:00:00Z"

    /** A pack that meets a window's box: its reader, its data's date and whether that is the data's own ([dated]). */
    private class Meeting(val reader: PackReader, val date: Instant, val dated: Boolean)

    /** The answer for [box] from the packs in [files] (newest data first wins); null where none holds any of it. */
    fun answer(files: List<File>, box: BBox): Windows.Packed? {
        val readers = files.mapNotNull { f -> runCatching { PackReader.open(f) }.getOrNull()?.let { it to f } }
        try {
            val meeting = readers.filter { (r, _) -> runCatching { r.manifest.bbox.intersects(box) }.getOrDefault(false) }
                .mapNotNull { (r, f) -> met(r, f) }
                .sortedByDescending { it.date }
            if (meeting.isEmpty()) return null
            val seen = HashMap<Pair<ElementType, Long>, OsmElement>()
            for (m in meeting) for (e in m.reader.query(box)) seen.putIfAbsent(e.type to e.id, e)
            if (seen.isEmpty()) return null
            val elements = seen.values.sortedWith(compareBy<OsmElement> { it.type.ordinal }.thenBy { it.id })
            val date = meeting.minOf { it.date }
            val boxes = meeting.map { it.reader.manifest.bbox }
            // whole where one pack holds the whole box and every date is its data's own: changes since can be asked for
            val whole = meeting.all { it.dated } && boxes.any { holds(it, box) }
            val text = StringBuilder(elements.size * 160)
            text.append("{\"version\":0.6,\"generator\":\"Atlas: the element packs on the phone\",")
                .append("\"osm3s\":{\"timestamp_osm_base\":\"").append(date).append("\",")
                .append("\"copyright\":\"The data included in this document is from www.openstreetmap.org. The data is made available under ODbL.\"},")
            if (whole) {
                text.append("\"atlas\":{\"whole\":\"").append(date).append("\"},")
            } else {
                text.append("\"whole\":\"").append(NEVER_WHOLE).append("\",")
                held(boxes, box)?.let { text.append("\"atlas\":{\"box\":\"").append(it).append("\"},") }
            }
            text.append("\"elements\":[\n")
            for ((i, e) in elements.withIndex()) {
                if (i > 0) text.append(",\n")
                text.append(solid(e).toJson())
            }
            text.append("\n]}\n")
            val gz = ByteArrayOutputStream(text.length / 6)
            GZIPOutputStream(gz, 1 shl 16).use { it.write(text.toString().toByteArray(Charsets.UTF_8)) }
            return Windows.Packed(gz.toByteArray(), date, meeting.joinToString(" and ") { it.reader.manifest.id })
        } finally {
            readers.forEach { (r, _) -> runCatching { r.close() } }
        }
    }

    /** Whether [pack] holds the whole of [box]. */
    internal fun holds(pack: BBox, box: BBox): Boolean = pack.west <= box.west && pack.south <= box.south && pack.east >= box.east && pack.north >= box.north

    /**
     * Pack [r] of [file] as one that meets a window's box: dated by its data ([dateOf]), else, where neither its
     * manifest nor its extract's header says a date (a pack built from an extract without a timestamp, an older
     * pack), by when its file was written, saying so: the pack still gives its window, offline too, but changes since
     * that time cannot be asked for, so its windows are never whole. Null where the file says no time either.
     */
    private fun met(r: PackReader, file: File): Meeting? {
        dateOf(r)?.let { return Meeting(r, it, dated = true) }
        val written = file.lastModified()
        if (written <= 0L) {
            Log.w(TAG, "the pack ${r.manifest.id} (${file.name}) says no date for its data and its file no time: it gives no window")
            return null
        }
        val at = Instant.ofEpochSecond(written / 1000)
        Log.w(TAG, "the pack ${r.manifest.id} (${file.name}) says no date for its data: its windows are dated by its file, $at")
        return Meeting(r, at, dated = false)
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

    /**
     * [e] without the points its pack left null: a way's and each member's runs of points close up over them. A way
     * that was closed (its nodes end where they begin) stays closed: where its first point was left out, the first
     * point left is repeated at its end, so a building or a water whose outline starts beyond the pack's box is still
     * a ring to the core, cut along the box by the chord between the points on either side. A member's run has no
     * nodes to say whether it closed, and stays as its points leave it.
     */
    internal fun solid(e: OsmElement): OsmElement {
        val g = e.geometry
        val ms = e.members
        if ((g == null || !g.hasNulls) && (ms == null || ms.none { it.geometry?.hasNulls == true })) return e
        val closed = e.nodes?.let { it.size > MIN_RING && it.first() == it.last() } == true
        return OsmElement(
            e.type, e.id, e.lat, e.lon, e.tags, e.bounds, e.nodes, g?.let { solid(it, closed) },
            ms?.map { m -> m.geometry?.takeIf { it.hasNulls }?.let { mg -> Member(m.type, m.ref, m.role, m.lat, m.lon, solid(mg, false)) } ?: m },
        )
    }

    /** [p] without its null points; with [closed], ending where it begins where three points or more are left. */
    private fun solid(p: Points, closed: Boolean): Points {
        val keep = (0 until p.size).filterTo(ArrayList()) { !p.isNull(it) }
        if (closed && keep.size >= MIN_RING) {
            val first = keep.first()
            val last = keep.last()
            if (p.lat[first] != p.lat[last] || p.lon[first] != p.lon[last]) keep += first
        }
        return Points(DoubleArray(keep.size) { p.lat[keep[it]] }, DoubleArray(keep.size) { p.lon[keep[it]] })
    }

    /** The fewest points of a ring before its first is repeated at its end (a closed way lists one node more). */
    private const val MIN_RING = 3

    private const val TAG = "AtlasMap"
}
