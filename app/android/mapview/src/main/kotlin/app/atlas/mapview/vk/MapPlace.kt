package app.atlas.mapview.vk

import app.atlas.mapview.CountryTiles

/**
 * What the core has at a camera's place (ENGINE.md step 3: the core draws every place): its tiles, or the world's
 * ([DRAWN]); none yet, the windows on their way ([WINDOWS]: within the cover's reach, and the levels' in a country whose
 * tiles hold them); none within the view's reach, the map nearer ([NEAR]); none beyond the levels' reach, the world not
 * on the phone ([WORLD]). Where it has none it draws its own cover (the windows it has around the place, the plate),
 * and the map says why (MapController's line). Until 5 November the transitional rule (Transition.pathOf) handed such a
 * place outside the countries, and beyond their reach, to today's map.
 */
internal enum class MapPlace {
    DRAWN, WINDOWS, NEAR, WORLD;

    companion object {
        /** The place of a camera on [lat], [lon], [distance] metres from it, with the packs [packs] and the countries [countryAt] gives. */
        fun of(packs: PackList, countryAt: (Double, Double) -> CountryTiles?, lat: Double, lon: Double, distance: Double): MapPlace {
            if (packs.covers(lat, lon, distance) || packs.worldDraws(distance)) return DRAWN
            val reach = if (countryAt(lat, lon)?.levels == true) PackList.LEVELS_REACH else PackList.CORE_REACH
            return when {
                distance <= reach -> WINDOWS
                distance <= PackList.LEVELS_REACH -> NEAR
                else -> WORLD
            }
        }
    }
}
