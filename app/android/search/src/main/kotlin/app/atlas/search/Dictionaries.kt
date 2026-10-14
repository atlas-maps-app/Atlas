package app.atlas.search

/**
 * The store's two dictionaries, cached in memory: tag keys (a few thousand, all held) and repeated tag values
 * (kinds, operators, cities, preset and symbol ids; held as they are met). Ids start with the commonest keys and
 * values of OSM so they take one byte in a blob. Used under the store's lock.
 */
internal class Dictionaries(private val store: PlaceStore) {
    private val keyIds = HashMap<String, Int>()
    private var keyNames = arrayOfNulls<String>(256)
    private val valueIds = HashMap<String, Int>()
    private var valueNames = arrayOfNulls<String>(1024)
    private var loaded = false

    fun key(id: Int): String {
        load()
        return keyNames.getOrNull(id) ?: throw IllegalStateException("tag key $id is not in the dictionary")
    }

    /** The id of [key], or -1 when no place has it. */
    fun findKey(key: String): Int {
        load()
        return keyIds[key] ?: -1
    }

    fun internKey(key: String): Int {
        load()
        keyIds[key]?.let { return it }
        val id = store.stmt("INSERT INTO tagkey(k) VALUES (?1) RETURNING id") { st -> st.bindText(1, key); st.step(); st.getLong(0).toInt() }
        putKey(id, key)
        return id
    }

    fun value(id: Int): String {
        if (id < valueNames.size) valueNames[id]?.let { return it }
        val v = store.stmt("SELECT v FROM tagvalue WHERE id = ?1") { st ->
            st.bindLong(1, id.toLong())
            if (st.step()) st.getText(0) else throw IllegalStateException("tag value $id is not in the dictionary")
        }
        cacheName(id, v)
        return v
    }

    /** The id of [value], or -1 when it is not in the dictionary. */
    fun findValue(value: String): Int {
        valueIds[value]?.let { return it }
        val id = store.stmt("SELECT id FROM tagvalue WHERE v = ?1") { st -> st.bindText(1, value); if (st.step()) st.getLong(0).toInt() else -1 }
        if (id >= 0) putValue(id, value)
        return id
    }

    fun internValue(value: String): Int {
        val found = findValue(value)
        if (found >= 0) return found
        val id = store.stmt("INSERT INTO tagvalue(v) VALUES (?1) RETURNING id") { st -> st.bindText(1, value); st.step(); st.getLong(0).toInt() }
        putValue(id, value)
        return id
    }

    /** Gives OSM's commonest keys and values the first ids (one byte in a blob) when the dictionaries are empty. */
    fun seed() {
        if (store.long("SELECT EXISTS (SELECT 1 FROM tagkey)") == 0L) for (k in COMMON_KEYS) internKey(k)
        if (store.long("SELECT EXISTS (SELECT 1 FROM tagvalue)") == 0L) for (v in COMMON_VALUES) internValue(v)
    }

    /** Forgets what is cached (after a rollback or a clear): it is read again from the tables. */
    fun forget() {
        keyIds.clear()
        keyNames = arrayOfNulls(256)
        valueIds.clear()
        valueNames = arrayOfNulls(1024)
        loaded = false
    }

    private fun load() {
        if (loaded) return
        store.stmt("SELECT id, k FROM tagkey") { st -> while (st.step()) putKey(st.getLong(0).toInt(), st.getText(1)) }
        loaded = true
    }

    private fun putKey(id: Int, key: String) {
        if (id >= keyNames.size) keyNames = keyNames.copyOf(maxOf(keyNames.size * 2, id + 1))
        keyNames[id] = key
        keyIds[key] = id
    }

    private fun cacheName(id: Int, value: String) {
        if (id >= valueNames.size) valueNames = valueNames.copyOf(maxOf(valueNames.size * 2, id + 1))
        valueNames[id] = value
    }

    // values read back are cached by id only; the reverse map holds those looked up or written
    private fun putValue(id: Int, value: String) {
        cacheName(id, value)
        valueIds[value] = id
    }

    companion object {
        /** OSM's commonest keys on the places a store keeps, in order: ids 1 to 127 take one byte. */
        val COMMON_KEYS = listOf(
            "name", "amenity", "addr:housenumber", "addr:street", "addr:postcode", "addr:city", "addr:country", "building",
            "highway", "shop", "opening_hours", "website", "phone", "operator", "brand", "brand:wikidata", "wheelchair",
            "cuisine", "tourism", "leisure", "natural", "public_transport", "bus", "shelter", "bench", "check_date", "ref",
            "wikidata", "wikipedia", "historic", "man_made", "office", "craft", "healthcare", "emergency", "sport",
            "railway", "place", "landuse", "waterway", "surface", "lit", "access", "fee", "capacity", "parking",
            "level", "layer", "covered", "backrest", "material", "colour", "seats", "email", "contact:phone",
            "contact:website", "contact:email", "outdoor_seating", "internet_access", "takeaway", "delivery",
            "diet:vegetarian", "diet:vegan", "payment:cash", "payment:credit_cards", "payment:debit_cards",
            "toilets:wheelchair", "religion", "denomination", "information", "tactile_paving", "network", "route_ref",
            "local_ref", "bin", "start_date", "height", "maxspeed", "lanes", "oneway", "sidewalk", "bicycle", "foot",
            "hgv", "smoothness", "name:en", "name:fr", "name:de", "name:nl", "name:lb", "alt_name", "old_name",
            "official_name", "short_name", "description", "image", "wikimedia_commons", "operator:wikidata",
            "network:wikidata", "brand:en", "drive_through", "smoking", "dog", "organic", "vending", "recycling_type",
            "atm", "fuel:diesel", "socket:type2", "socket:type2_combo", "socket:chademo", "charging_station:output",
            "authentication:app", "addr:place", "addr:unit", "addr:housename", "building:levels", "entrance",
            "playground", "artwork_type", "memorial", "tower:type", "board_type", "departures_board",
        )

        /** OSM's commonest values: ids 1 to 63 take one byte. */
        val COMMON_VALUES = listOf(
            "yes", "no", "bench", "waste_basket", "bicycle_parking", "parking", "restaurant", "cafe", "bus_stop",
            "platform", "residential", "service", "house", "apartments", "designated", "limited", "only", "public",
            "private", "customers", "asphalt", "paving_stones", "wood", "metal", "concrete", "fast_food", "bar", "pub",
            "pharmacy", "supermarket", "convenience", "bakery", "hairdresser", "clothes", "toilets", "drinking_water",
            "recycling", "post_box", "atm", "bank", "school", "kindergarten", "place_of_worship", "christian",
            "catholic", "protestant", "tree", "park", "playground", "pitch", "hotel", "museum", "artwork", "viewpoint",
            "information", "board", "guidepost", "charging_station", "fuel", "vending_machine", "unclassified", "tertiary",
        )
    }
}
