package app.atlas.services

import app.atlas.providers.OsmEdits
import app.atlas.providers.OsmSignedOutException
import app.atlas.providers.OsmType
import app.atlas.providers.ProviderException
import app.atlas.providers.TagEdit

/**
 * The development server's round trip, to try the sign-in and the sending before an edit goes to the map everyone uses
 * (debug and bench builds that carry the test server's client id, BuildConfig.OSM_DEV_CLIENT_ID). Signed in to
 * master.apis.dev.openstreetmap.org with an account of its own ([account]), [run] creates a node there and edits its tags
 * as an edit of the queue goes ([OsmEdits.send]: read as it is now, merged, uploaded in a changeset, closed). Nothing here
 * reaches the live map: [osm] is the test server's, and its node creation refuses the live API.
 */
class OsmDevCheck(val account: OsmAccount, private val osm: OsmEdits) {
    /** A round trip done: the [node] created and the [changeset] that edited it. */
    data class Trip(val node: Long, val changeset: Long)

    /** Creates a node at [lat] [lon] on the test server and edits it; throws where a step failed. */
    suspend fun run(lat: Double, lon: Double): Trip {
        val token = account.token() ?: throw OsmSignedOutException()
        val node = osm.createNode(token, lat, lon, mapOf("note" to "Atlas round trip"), "Atlas round trip: a node to edit")
        val edit = TagEdit(OsmType.NODE, node, mapOf("description" to "Edited as Atlas sends an edit"), mapOf("description" to null))
        val sent = osm.send(token, listOf(edit), "Atlas round trip: the node's tags edited as an edit is sent")
        return Trip(node, sent.changeset ?: throw ProviderException(OsmEdits.ID, "The test server took no edit: its node changed meanwhile"))
    }
}
