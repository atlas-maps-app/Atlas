package app.atlas.search

import app.atlas.geo.LatLon
import app.atlas.model.OsmType
import app.atlas.providers.Overpass
import app.atlas.providers.OverpassElement
import java.time.Instant
import java.util.Locale

/**
 * The national parks of the country a region lies in, kept in the region's own store, so a country's largest greens
 * are on the phone however small the region downloaded (the user, 9 November: "Is it somewhere possible to see the
 * largest green areas in your country or around you?", then "Why only online?"). A country has a few dozen at most
 * (the Netherlands about twenty), their tags and outline boxes a few kilobytes: they come with every download, and a
 * store downloaded before takes them once the phone is next online.
 *
 * Ways and relations only: a park drawn as a point has no size to be listed by, and a point of a store's own counts as
 * the store holding the ground around it ([covers]). They are added as places are, with no store finish: the store's
 * statistics, bounds and typo vocabulary stay those of its region.
 */
class NationalParks(private val overpass: Overpass) {
    /** Adds the national parks of the country holding [at] to [store]; how many it kept. Throws where Overpass fails. */
    suspend fun keep(at: LatLon, store: PlaceStore): Int {
        val found = ArrayList<OverpassElement>()
        overpass.stream(ql(at)) { e -> if (e.type != OsmType.NODE) found += e }
        val langs = store.meta("langs")?.split(',')?.filter { it.isNotBlank() }?.ifEmpty { null } ?: PlaceStore.DEFAULT_LANGS
        return store.writer(langs).use { w ->
            for (e in found) w.add(e)
            w.putMeta(KEPT, Instant.now().toString())
            w.commit()
            store.locked { store.invalidate() }
            w.places
        }
    }

    companion object {
        /** The store's meta key that says when its country's national parks were kept. */
        const val KEPT = "national_parks"

        /** The Overpass QL for the national parks of the country (admin level 2) holding [at], with tags and outline boxes. */
        fun ql(at: LatLon, timeoutSeconds: Int = 90): String = buildString {
            append("[out:json][timeout:").append(timeoutSeconds).append("];\n")
            append("is_in(").append(f(at.lat)).append(',').append(f(at.lon)).append(")->.a;\n")
            append("area.a[\"ISO3166-1\"][\"admin_level\"=\"2\"]->.c;\n")
            append("wr[\"boundary\"=\"national_park\"](area.c);\n")
            append("out tags bb qt;")
        }

        private fun f(v: Double) = String.format(Locale.ROOT, "%.6f", v)
    }
}
