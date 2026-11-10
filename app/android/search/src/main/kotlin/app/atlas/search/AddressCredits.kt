package app.atlas.search

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** An OpenAddresses source a store's address points came from: its name, licence, the credit it asks for, its points. */
@Serializable
data class AddressCredit(val source: String, val licence: String, val credit: String, val points: Int = 0)

/**
 * The OpenAddresses sources of a store's address points, in the order the points name them: the store's
 * "openaddresses" metadata, a JSON list ([AddressFill] appends to it). A point from the source at place i of the list
 * holds −2 − i in its `osm` column, where OpenStreetMap's points hold their element's packed id (or nothing, −1 as read).
 */
internal object AddressCredits {
    const val META = "openaddresses"

    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(AddressCredit.serializer())

    /** The `osm` column of a point from the source at [index]. */
    fun osmOf(index: Int): Long = -2L - index

    /** The place in the list of the source a point's `osm` column names; null for OpenStreetMap's points. */
    fun sourceOf(osm: Long): Int? = if (osm <= -2L) (-2L - osm).toInt() else null

    /** The store's sources (under its lock), none when it has no such metadata or one Atlas cannot read. */
    fun read(store: PlaceStore): List<AddressCredit> = store.meta(META)?.let(::decode).orEmpty()

    fun decode(text: String): List<AddressCredit> = runCatching { json.decodeFromString(list, text) }.getOrDefault(emptyList())

    fun encode(credits: List<AddressCredit>): String = json.encodeToString(list, credits)
}
