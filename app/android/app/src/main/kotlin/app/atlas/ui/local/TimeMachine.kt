package app.atlas.ui.local

import androidx.compose.ui.graphics.Color

/** What the time machine knows: today's buildings and the years the map gives them. */
object TimeMachine {
    const val FROM = 1500
    const val TO = 2030

    /** The eras the buildings are coloured by. */
    enum class Era(val colour: Color, val from: Int, val to: Int) {
        OLD(Color(0xFF8B3A2B), 0, 1699),
        BRICK(Color(0xFFB8643E), 1700, 1849),
        OCHRE(Color(0xFFC99A4B), 1850, 1944),
        POSTWAR(Color(0xFF8C93A0), 1945, 1989),
        NEW(Color(0xFF4F8FA3), 1990, 9999),
        UNKNOWN(Color(0xFFD9D2C0), -1, -1),
    }

    fun era(year: Int?): Era = if (year == null) Era.UNKNOWN else Era.entries.first { it != Era.UNKNOWN && year in it.from..it.to }

    /** How many stood in [year] (a known year at or before it), how many there are, how many have no year. */
    class Slice(val stood: Int, val total: Int, val unknown: Int)

    fun slice(all: List<Footprint>, year: Int): Slice =
        Slice(all.count { f -> f.year?.let { it <= year } == true }, all.size, all.count { it.year == null })

    /** A building stood in [year] when its year is known and not after it. */
    fun stood(f: Footprint, year: Int): Boolean = f.year?.let { it <= year } == true

    /** The notable buildings of the list the cards show: the last built by [year], the first built after it, and what is being built; each null when none. */
    class Cards(val before: List<Footprint>, val after: Footprint?, val planned: Footprint?)

    fun cards(all: List<Footprint>, year: Int): Cards {
        val dated = all.filter { it.year != null && !it.underConstruction }
        // Notable first; a named building with a year when no heritage building is that old.
        val notable = dated.filter { it.notable }.ifEmpty { dated.filter { it.name != null } }
        val before = notable.filter { it.year!! <= year }.sortedByDescending { it.year }.take(2)
        val after = notable.filter { it.year!! > year }.minByOrNull { it.year!! }
        val planned = all.firstOrNull { it.underConstruction && it.name != null } ?: all.firstOrNull { it.underConstruction }
        return Cards(before, after, planned)
    }
}
