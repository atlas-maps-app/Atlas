package app.atlas.rendertiles.compile

/**
 * The user's palette's slots a building's walls and roof go with (the tiles' wall_slot and roof_slot), as the core's
 * cut finds them (load/Cut.cpp's wall_slot and roof_slot, style::nearest_slot): a guessed colour's nearest slot to the
 * colour it was guessed as, before the looks' jitter; a mapped colour's or material's nearest with [MAPPED]; none for
 * the clay; a roof in its walls' colour goes with them. A houseboat's colours are a boat's: its guessed paint goes with
 * no slot ([GUESSED]), its mapped colour with none. Integers alone, so both find the same.
 */
object BuildingSlots {
    /** The bit of a slot whose colour OpenStreetMap maps (style::slot_mapped). */
    const val MAPPED = 256L

    /** The bit of a colour Atlas guessed that goes with no slot, a houseboat's paint (style::slot_guessed). */
    const val GUESSED = 512L

    /** The walls' slots by code and their colours today (style::wall_slots; mapview's Slot). */
    val WALLS: List<Pair<Long, Long>> = listOf(
        48L to 0x6E3B2EL, 49L to 0x7A4434L, 50L to 0x5C3A30L, 51L to 0x8A5040L, 52L to 0x6A4A3CL, 53L to 0x7E5242L,
        54L to 0xE8E2D4L, 55L to 0xEFE9DCL, 56L to 0xD9D2C2L, 57L to 0x9C6A52L, 58L to 0x8F7F74L, 59L to 0xB08A6AL,
        60L to 0x8C8A86L, 61L to 0xA9876EL, 62L to 0xD8CCB2L, 63L to 0xBEBAB1L,
    )

    /** The roofs' slots by code and their colours today (style::roof_slots; mapview's Slot). */
    val ROOFS: List<Pair<Long, Long>> = listOf(
        80L to 0xAE573FL, 81L to 0x4A3E3AL, 82L to 0x56504CL, 83L to 0x3E4248L, 84L to 0x7E7A74L, 85L to 0x8C8882L,
        86L to 0x6E6A66L, 87L to 0x56585CL, 88L to 0x9C988FL, 89L to 0x535C66L, 90L to 0x737E88L, 91L to 0x7A8187L,
    )

    /** The code of the slot of [slots] nearest [rgb]: the least sum of the channels' squared differences, the first on a tie. */
    fun nearest(slots: List<Pair<Long, Long>>, rgb: Long): Long {
        var best = 0L
        var least = Long.MAX_VALUE
        for ((code, today) in slots) {
            var d = 0L
            for (shift in intArrayOf(16, 8, 0)) {
                val k = ((rgb shr shift) and 0xFF) - ((today shr shift) and 0xFF)
                d += k * k
            }
            if (d < least) {
                least = d
                best = code
            }
        }
        return best
    }

    /** The walls' slot of building [b] (its [tags]), whose wall is drawn in [drawn]. */
    fun wall(b: Map<String, Any?>, tags: Map<String, Any?>, drawn: Long): Long {
        val boat = tags["building"] == "houseboat"
        return when {
            guessed(b, "building:colour") -> if (boat) GUESSED else nearest(WALLS, guessedAs(b["col"], drawn))
            !boat && (truthy(b["col"]) || truthy(b["mat"])) -> MAPPED or nearest(WALLS, drawn)
            else -> 0L
        }
    }

    /** The roof's slot of building [b], whose roof is drawn in [drawn] and whose walls go with [wall]. */
    fun roof(b: Map<String, Any?>, tags: Map<String, Any?>, drawn: Long, wall: Long): Long {
        val boat = tags["building"] == "houseboat"
        return when {
            guessed(b, "roof:colour") -> if (boat) GUESSED else nearest(ROOFS, guessedAs(b["rcol"], drawn))
            truthy(b["rcol"]) || truthy(b["rmat"]) -> if (boat) 0L else MAPPED or nearest(ROOFS, drawn)
            else -> wall
        }
    }

    private fun guessed(b: Map<String, Any?>, key: String): Boolean = (b["guessed"] as? Map<*, *>)?.get(key) == true

    private fun truthy(v: Any?): Boolean = v is String && v.isNotEmpty()

    /** A colour as it was guessed: its #RRGGBB, else the colour drawn. */
    private fun guessedAs(c: Any?, drawn: Long): Long {
        if (c !is String || c.length != 7 || c[0] != '#') return drawn
        var v = 0L
        for (ch in c.substring(1)) {
            val d = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'a'..'f' -> ch - 'a' + 10
                in 'A'..'F' -> ch - 'A' + 10
                else -> return drawn
            }
            v = (v shl 4) or d.toLong()
        }
        return v
    }
}
