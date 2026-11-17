package app.atlas.packer.world

import app.atlas.packer.UsageException

/**
 * What a continent's extract is cut to before its regions are cut from it (ENGINE.md step 5's world.yml, the filter
 * job): `osmium tags-filter`'s expressions that keep every element [WorldProfile.decide] can make a feature of, with
 * the elements they reference, from the same tables and levels the profile reads, so the two cannot drift apart. The
 * rest (buildings, the minor roads, the coastline, whose land is osmdata's, addresses and the like) never reaches
 * Planetiler.
 */
object WorldFilter {
    const val USAGE = """packer world-tags
      osmium tags-filter's expressions for the world's profile, one a line (osmium tags-filter -e <file>): the places, the
      road classes the world draws, land cover, water, rivers, runways and railways, and the seas, bays and straits
      the water's names name."""

    fun run(args: List<String>, out: (String) -> Unit): Int {
        if (args != listOf("world-tags")) throw UsageException("usage: $USAGE")
        for (e in expressions()) out(e)
        return 0
    }

    /** The expressions, each `<types>/<key>=<value>,…` with the element types it holds for (n, w, r). */
    fun expressions(tags: StagePTags = StagePTags.STAGE_P): List<String> {
        val highways = (tags.roads.keys + tags.bigRoads).filter { WorldProfile.roadLevel(it) != null }.distinct()
        return listOf(
            // place nodes
            "n/place=" + WorldProfile.PLACE_LEVELS.keys.joinToString(","),
            // amsterdam.py's roads and regions.py's big roads, those with a level
            "w/highway=" + highways.joinToString(","),
            // water, and land cover by its kind (regions.py's land_kind)
            "wr/natural=" + (listOf("water") + tags.land.keys).distinct().joinToString(","),
            "wr/landuse=" + tags.land.keys.joinToString(","),
            "wr/leisure=" + tags.landLeisure.joinToString(","),
            "wr/amenity=parking",
            "wr/industrial=port",
            "wr/aeroway=apron,runway",
            // water areas and the rivers' lines
            "wr/waterway=" + (tags.waterWays + "river").distinct().joinToString(","),
            "w/railway=rail",
            // the water's names: oceans and seas, bays and straits, as nodes or areas (lakes are natural=water's)
            "nwr/place=" + WATER_PLACES.joinToString(","),
            "nwr/natural=" + WATER_NATURALS.joinToString(","),
        )
    }

    /** The place=* of the water's names (the place nodes above are the places'). */
    val WATER_PLACES = listOf(WorldProfile.OCEAN, WorldProfile.SEA)

    /** The natural=* of the water's names besides natural=water's lakes. */
    val WATER_NATURALS = listOf(WorldProfile.BAY, WorldProfile.STRAIT)

    /** An expression read back: the types it holds for, its key and its values. */
    class Expression(val types: Set<Char>, val key: String, val values: Set<String>) {
        fun matches(type: Char, tags: (String) -> String?): Boolean = type in types && tags(key) in values
    }

    fun parse(e: String): Expression {
        val slash = e.indexOf('/')
        val eq = e.indexOf('=')
        require(slash in 1 until eq) { "not an expression: $e" }
        return Expression(e.substring(0, slash).toSet(), e.substring(slash + 1, eq), e.substring(eq + 1).split(',').toSet())
    }
}
