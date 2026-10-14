package app.atlas.ui.go

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Mode
import app.atlas.model.Place

/** The kinds of two-wheeler parking the Twowheel board counts, each with its board icon and colour. */
enum class ParkKind(val symbol: String, val colour: Long) {
    STANDS("go-stand", 0xFF15251C),
    COVERED("go-covered", 0xFF15251C),
    LOCKERS("go-locker", 0xFF15251C),
    GARAGE("go-garage", 0xFF2E6BFF),
    CARGO("go-cargo", 0xFFE8922A),
    MOTORBIKE("go-moto", 0xFFC8322A),
    SCOOTER("go-scooter", 0xFF8E3CF7),
    CHARGE("ui-bolt", 0xFF1E9E5A),
    REPAIR("ui-wrench", 0xFF7A4BD6),
}

/** One place to leave a two-wheeler: the place, how far, and what it offers by its tags. */
data class ParkSpot(
    val place: Place,
    val metres: Double,
    val kinds: Set<ParkKind>,
    val capacity: Int?,
    val covered: Boolean,
    val supervised: Boolean,
    val lit: Boolean,
    val fee: Boolean?,
)

/** What is around for two wheels: the counts by kind, the spots for the chosen vehicle (nearest first), the nearest repair. */
data class TwowheelUi(val counts: Map<ParkKind, Int>, val spots: List<ParkSpot>, val repair: ParkSpot? = null)

/** Reads two-wheeler parking from OpenStreetMap tags (`amenity=bicycle_parking`, `motorcycle_parking`, chargers, repair). */
object Twowheel {
    /** The kinds [tags] offer; empty for anything that is not two-wheeler parking, a charger for one, or a repair stand. */
    fun kinds(tags: Map<String, String>): Set<ParkKind> {
        val out = HashSet<ParkKind>()
        val amenity = tags["amenity"]
        val type = tags["bicycle_parking"]
        if (amenity == "bicycle_parking") {
            when (type) {
                "lockers" -> out += ParkKind.LOCKERS
                "building" -> out += ParkKind.GARAGE
                "shed" -> out += ParkKind.COVERED
                else -> out += ParkKind.STANDS
            }
            if (tags["covered"] == "yes") out += ParkKind.COVERED
            if (tags["supervised"] == "yes" && type != "lockers") out += ParkKind.GARAGE
            if (tags["cargo_bike"] in setOf("yes", "designated") || (tags["capacity:cargo_bike"]?.toIntOrNull() ?: 0) > 0) out += ParkKind.CARGO
            if (tags["motorcycle"] in setOf("yes", "designated")) out += ParkKind.MOTORBIKE
        }
        if (amenity == "motorcycle_parking" || (amenity == "parking" && tags["motorcycle"] == "designated")) out += ParkKind.MOTORBIKE
        if (amenity == "kick-scooter_parking" || amenity == "small_electric_vehicle_parking" ||
            (amenity in setOf("bicycle_parking", "parking") && (tags["small_electric_vehicle"] == "designated" || tags["kick-scooter"] == "designated"))
        ) out += ParkKind.SCOOTER
        if (amenity == "locker" && tags["bicycle"] in setOf("yes", "designated")) out += ParkKind.LOCKERS
        if (amenity == "charging_station" && (tags["bicycle"] in setOf("yes", "designated") || tags["motorcycle"] in setOf("yes", "designated") ||
                tags.keys.any { it.startsWith("socket:schuko") })
        ) out += ParkKind.CHARGE
        if (amenity == "bicycle_repair_station" || tags["shop"] == "bicycle" && tags["service:bicycle:repair"] != "no") out += ParkKind.REPAIR
        return out
    }

    /** The kinds that suit [mode]: a motorbike wants bays, a cargo bike room for its length; a bike takes any stand. */
    fun suits(mode: Mode, kinds: Set<ParkKind>): Boolean = when (mode) {
        Mode.MOTORCYCLE, Mode.MOPED -> ParkKind.MOTORBIKE in kinds
        Mode.CARGO_BIKE -> ParkKind.CARGO in kinds
        else -> kinds.any { it in setOf(ParkKind.STANDS, ParkKind.COVERED, ParkKind.LOCKERS, ParkKind.GARAGE, ParkKind.CARGO) }
    }

    /** Everything for two wheels among [places] around [at]: counts by kind, and the spots for [mode], nearest first. */
    fun around(places: List<Place>, at: LatLon, mode: Mode): TwowheelUi {
        val counts = HashMap<ParkKind, Int>()
        val spots = ArrayList<ParkSpot>()
        var repair: ParkSpot? = null
        for (p in places) {
            val k = kinds(p.tags)
            if (k.isEmpty()) continue
            for (kind in k) counts[kind] = (counts[kind] ?: 0) + 1
            if (ParkKind.REPAIR in k && mode != Mode.MOTORCYCLE) {
                val d = Geo.distance(at, p.location)
                if (repair == null || d < repair.metres) repair = ParkSpot(p, d, k, null, false, false, false, null)
            }
            val parking = k.any { it != ParkKind.CHARGE && it != ParkKind.REPAIR }
            if (!parking || !suits(mode, k)) continue
            val t = p.tags
            spots += ParkSpot(
                place = p,
                metres = Geo.distance(at, p.location),
                kinds = k,
                capacity = (if (mode == Mode.CARGO_BIKE) t["capacity:cargo_bike"]?.toIntOrNull() else null) ?: t["capacity"]?.toIntOrNull(),
                covered = t["covered"] == "yes" || t["bicycle_parking"] in setOf("shed", "building", "lockers"),
                supervised = t["supervised"] == "yes",
                lit = t["lit"] == "yes",
                fee = when (t["fee"]) { "yes" -> true; "no" -> false; else -> null },
            )
        }
        return TwowheelUi(counts, spots.sortedBy { it.metres }, repair)
    }
}
