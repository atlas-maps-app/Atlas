package app.atlas.services

import app.atlas.model.Place

/**
 * The places screens have handed each other recently, by [Place.key]: a marker tapped on the map opens the Place
 * screen with everything already known, while the destination itself carries only the key, id and point.
 */
class PlaceCache(private val capacity: Int = 128) {
    private val map = object : LinkedHashMap<String, Place>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Place>?) = size > capacity
    }

    /** Keeps [place] and returns its key. */
    @Synchronized
    fun put(place: Place): String {
        map[place.key] = place
        return place.key
    }

    @Synchronized
    operator fun get(key: String): Place? = map[key]
}
