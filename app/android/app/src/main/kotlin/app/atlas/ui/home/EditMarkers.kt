package app.atlas.ui.home

import app.atlas.geo.LatLon
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerStyle
import app.atlas.services.EditQueue

/**
 * The user's edits to OpenStreetMap the map does not draw as mapped, as chips at their buildings: an amber clock while
 * an edit waits to be sent ("waiting"), a red warning where it could not go ("not sent"). The map draws an edit as
 * mapped only once OpenStreetMap took it (MapData's windowEdits); a chip opens the account, where the edits are listed.
 */
internal object EditMarkers {
    private const val PREFIX = "osm-edit:"
    private const val AMBER: Long = 0xFFE8922A
    private const val RED: Long = 0xFFC8322A

    /** Above the area's highlights, under a focus or an opened place. */
    private const val PRIORITY = 500

    /** One chip an element of [edits] not yet taken: not sent where one of its edits could not go, else waiting. */
    fun of(edits: List<EditQueue.Queued>, waiting: String, notSent: String): List<Marker> =
        edits.filter { it.state != EditQueue.State.SENT }
            .groupBy { it.edit.type to it.edit.id }
            .map { (key, qs) ->
                val q = qs.last()
                val at = LatLon(q.lat, q.lon)
                val id = PREFIX + key.first.api + key.second
                if (qs.any { it.state != EditQueue.State.PENDING }) Marker(id, at, "ui-alert-triangle", RED, MarkerStyle.CHIP, value = notSent, priority = PRIORITY)
                else Marker(id, at, "ui-clock", AMBER, MarkerStyle.CHIP, value = waiting, priority = PRIORITY)
            }

    /** Whether [m] is an edit's chip. */
    fun isEdit(m: Marker): Boolean = m.id.startsWith(PREFIX)
}
