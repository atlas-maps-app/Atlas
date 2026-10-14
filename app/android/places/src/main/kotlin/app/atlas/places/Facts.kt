package app.atlas.places

import app.atlas.model.Place
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** What a fact says: yes, no, in part, only (vegan only, takeaway only, booking required), or not known. */
enum class FactState { YES, NO, LIMITED, ONLY, UNKNOWN }

/**
 * What a fact is about, with the id-tagging-schema field that names it, its default symbol and how prominent it is
 * ([priority]: higher shows first).
 */
enum class FactKind(val field: String?, val symbol: String, val priority: Int) {
    OPENING_HOURS("opening_hours", "ui-clock", 100),
    WHEELCHAIR("wheelchair", "ui-wheelchair", 95),
    SOCKET(null, "ui-plug-zap", 93),
    FEE("fee", "ui-coins", 90),
    FUEL("fuel/fuel_multi", "ui-fuel", 88),
    ACCESS("access", "ui-lock", 87),
    CUISINE("cuisine", Symbols.DEFAULT_FOOD, 85),
    DIET("diet_multi", "ui-leaf", 80),
    OUTDOOR_SEATING("outdoor_seating", "ui-umbrella", 75),
    TAKEAWAY("takeaway", "ui-shopping-bag", 72),
    CHARGE(null, "ui-receipt", 70),
    STARS("stars", "ui-star", 68),
    SPORT("sport", "ui-dumbbell", 66),
    RECYCLING("recycling_accepts", "ui-recycle", 64),
    RESERVATION("reservation", "ui-calendar", 63),
    TOILETS("toilets", "ui-toilet", 62),
    INTERNET("internet_access", "ui-wifi", 60),
    TOILETS_WHEELCHAIR("toilets/wheelchair", "ui-wheelchair", 59),
    PAYMENT("payment_multi", "ui-card", 58),
    BOTTLE_REFILL("drinking_water", "ui-bottle", 56),
    DRIVE_THROUGH("drive_through", "ui-car", 55),
    CHANGING_TABLE("changing_table", "ui-baby", 52),
    SURFACE("surface", "fact.cobbles", 51),
    DELIVERY("delivery", "ui-package", 50),
    LIT("lit", "ui-lightbulb", 49),
    DOG("dog", "ui-dog", 48),
    ORGANIC("organic", "ui-sprout", 46),
    SMOKING("smoking", "ui-smoking", 45),
    SECOND_HAND("second_hand", "fact.second-hand", 44),
    CAPACITY("capacity", "ui-users", 42),
    INDOOR_SEATING("indoor_seating", "ui-sofa", 40),
    INCLINE("incline", "ui-trending-up", 39),
    SELF_SERVICE("self_service", "ui-hand", 38),
    ROOMS("rooms", "ui-bed", 36),
    AIR_CONDITIONING("air_conditioning", "ui-air-vent", 35),
    LEVEL("level", "ui-layers", 30),
    PHONE("phone", "ui-phone", 20),
    WEBSITE("website", "ui-web", 18),
    WIKIPEDIA("wikipedia", "ui-book", 12),
    WIKIDATA("wikidata", "ui-info", 10),
}

/**
 * One thing worth knowing about a place, shown as a symbol: [symbol] is a [Symbols] id, [value] a short text for
 * beside it ("22 kW", "L1", "23:00"), [label] its name in the requested language ("Vegan", "Type 2", "Wheelchair
 * Access") and [valueLabel] the mapped value in that language where the label alone does not say it ("Limited",
 * "Customers Only"). [key] is the tag it reads, or for an unmapped fact ([mapped] false, state UNKNOWN) the tag a
 * quest should ask for.
 */
data class Fact(
    val kind: FactKind,
    val key: String,
    val symbol: String,
    val state: FactState,
    val value: String? = null,
    val label: String? = null,
    val valueLabel: String? = null,
    val priority: Int = kind.priority,
    val mapped: Boolean = true,
)

/**
 * Turns a place's tags into symbol facts: access, food and diets, payment, Wi-Fi, toilets, charging sockets, fuels,
 * recycling, paths, contacts and more, with the hours as an open-now fact. Facts the place's preset deems important
 * but nobody has mapped (wheelchair access on a restaurant) come back as UNKNOWN, for the Tag-it quests.
 */
object Facts {
    /**
     * The facts of [place] in [lang], most prominent first, mapped before unmapped. The hours are read at [now] in
     * [context] (by default the holidays of `addr:country`, else of [country], and the sun at the place).
     */
    fun of(
        place: Place,
        lang: String? = "en",
        now: LocalDateTime = LocalDateTime.now(),
        context: OpeningHoursContext? = null,
        country: String? = null,
        geometry: Geometry = Geometry.POINT,
    ): List<Fact> {
        val ctx = context ?: OpeningHoursContext.forPlace(
            place.tags["addr:country"] ?: country, ZoneId.systemDefault(), place.location.lat, place.location.lon,
        )
        return of(place.tags, lang, now, ctx, geometry)
    }

    /** The facts of a bare tag map; see the overload for places. */
    fun of(
        tags: Map<String, String>,
        lang: String? = "en",
        now: LocalDateTime = LocalDateTime.now(),
        context: OpeningHoursContext = OpeningHoursContext.Default,
        geometry: Geometry = Geometry.POINT,
    ): List<Fact> {
        val b = Builder(tags, lang)
        b.hours(now, context)
        b.access()
        b.food()
        b.services()
        b.amenities()
        b.vehicles()
        b.outdoors()
        b.contact()
        b.unknowns(geometry)
        return b.out.sortedWith(compareByDescending<Fact> { it.mapped }.thenByDescending { it.priority }.thenBy { it.key })
    }

    /** "yes", "designated" -> YES; "no" -> NO; "limited", "partial" -> LIMITED; "only", "required" -> ONLY; else null. */
    fun stateOf(value: String?): FactState? = when (value?.trim()?.lowercase()) {
        null, "" -> null
        "yes", "designated", "true", "1", "permissive", "public", "official" -> FactState.YES
        "no", "false", "0", "none", "private" -> FactState.NO
        "limited", "partial", "partially", "some", "bad", "customers", "seasonal" -> FactState.LIMITED
        "only", "required", "dedicated", "exclusively" -> FactState.ONLY
        else -> null
    }

    private val DIETS = listOf(
        "vegan" to "ui-vegan", "vegetarian" to "ui-leaf", "halal" to "ui-moon-star", "kosher" to "ui-hexagram",
        "gluten_free" to "ui-wheat-off", "lactose_free" to "ui-milk-off", "pescetarian" to "ui-fish",
    )

    private val CARDS = listOf(
        "cards", "credit_cards", "debit_cards", "visa", "mastercard", "maestro", "american_express", "v_pay", "girocard",
        "visa_debit", "visa_electron", "diners_club", "discover_card", "jcb", "unionpay",
    )
    private val APPS = listOf("app", "apple_pay", "google_pay", "samsung_pay", "alipay", "wechat", "paypal", "twint", "payconiq")

    /** Socket types as people know them; others are made readable. */
    private val SOCKETS = mapOf(
        "type2" to "Type 2", "type2_combo" to "CCS", "type2_cable" to "Type 2 cable", "chademo" to "CHAdeMO",
        "type1" to "Type 1", "type1_combo" to "CCS1", "tesla_supercharger" to "Supercharger", "tesla_standard" to "Tesla",
        "tesla_destination" to "Tesla destination", "nacs" to "NACS", "schuko" to "Schuko", "cee_blue" to "CEE blue",
        "cee_red_16a" to "CEE red 16 A", "cee_red_32a" to "CEE red 32 A", "type3c" to "Type 3C", "gb_dc" to "GB/T DC",
        "gb_ac" to "GB/T AC", "chaoji" to "ChaoJi", "mcs" to "MCS", "typee" to "Type E", "typef" to "Type F",
        "uk" to "UK 3-pin", "nema_5_15" to "NEMA 5-15", "nema_14_50" to "NEMA 14-50", "bs1363" to "UK 3-pin",
    )

    private val PAVED = setOf("paved", "asphalt", "concrete", "paving_stones", "sett", "concrete:plates", "concrete:lanes", "metal", "wood", "chipseal", "bricks", "brick", "rubber", "tartan", "acrylic", "artificial_turf")
    private val ROUGH = setOf("compacted", "fine_gravel", "gravel", "unpaved", "pebblestone", "cobblestone", "unhewn_cobblestone", "grass_paver", "rock", "stepping_stones")

    /** Asks for these when the preset lists them first and they are not mapped. */
    private val ASKABLE = mapOf(
        "wheelchair" to (FactKind.WHEELCHAIR to "wheelchair"), "outdoor_seating" to (FactKind.OUTDOOR_SEATING to "outdoor_seating"),
        "internet_access" to (FactKind.INTERNET to "internet_access"), "opening_hours" to (FactKind.OPENING_HOURS to "opening_hours"),
        "cuisine" to (FactKind.CUISINE to "cuisine"), "diet_multi" to (FactKind.DIET to "diet:vegetarian"),
        "fee" to (FactKind.FEE to "fee"), "changing_table" to (FactKind.CHANGING_TABLE to "changing_table"),
        "surface" to (FactKind.SURFACE to "surface"), "lit" to (FactKind.LIT to "lit"), "takeaway" to (FactKind.TAKEAWAY to "takeaway"),
        "smoking" to (FactKind.SMOKING to "smoking"), "toilets" to (FactKind.TOILETS to "toilets"),
        "access_simple" to (FactKind.ACCESS to "access"), "toilets/wheelchair" to (FactKind.TOILETS_WHEELCHAIR to "toilets:wheelchair"),
        "self_service" to (FactKind.SELF_SERVICE to "self_service"), "drive_through" to (FactKind.DRIVE_THROUGH to "drive_through"),
        "reservation" to (FactKind.RESERVATION to "reservation"),
    )

    private class Builder(val tags: Map<String, String>, val lang: String?) {
        val out = ArrayList<Fact>()
        private val kinds = HashSet<FactKind>()

        fun add(
            kind: FactKind, key: String, state: FactState, value: String? = null, label: String? = null, symbol: String = kind.symbol,
            valueLabel: String? = null,
        ) {
            out += Fact(kind, key, symbol, state, value, label ?: fieldLabel(kind), valueLabel)
            kinds += kind
        }

        fun fieldLabel(kind: FactKind): String? = kind.field?.let { I18n.fieldLabel(it, lang) }
        fun option(field: String, value: String): String? = I18n.fieldOption(field, value, lang)
        fun t(k: String): String? = tags[k]?.trim()?.takeIf { it.isNotEmpty() }

        /** A yes/no tag as a fact; other values count as yes and travel in [Fact.value]. */
        fun flag(kind: FactKind, key: String, symbolNo: String? = null) {
            val v = t(key) ?: return
            val s = stateOf(v) ?: FactState.YES
            val shown = if (stateOf(v) == null) v.lowercase() else null
            add(kind, key, s, shown, symbol = if (s == FactState.NO && symbolNo != null) symbolNo else kind.symbol)
        }

        fun hours(now: LocalDateTime, context: OpeningHoursContext) {
            val raw = t("opening_hours") ?: return
            val oh = OpeningHours.parseOrNull(raw)
            if (oh == null) {
                add(FactKind.OPENING_HOURS, "opening_hours", FactState.UNKNOWN, raw)
                return
            }
            if (oh.isAlwaysOpen) {
                add(FactKind.OPENING_HOURS, "opening_hours", FactState.YES, "24/7")
                return
            }
            val st = oh.status(now, context)
            val state = when {
                st.unknown -> FactState.UNKNOWN
                st.isOpen -> FactState.YES
                else -> FactState.NO
            }
            // when it next opens or closes: "18:00" today or tomorrow, "Mon 07:00" within the week, "1 Jun 07:00" later
            // (a weekday alone would read as the coming one); for hours that do not say, their comment ("by appointment")
            val at = st.changesAt?.takeIf { !st.openEnd }
            val locale = Locale.forLanguageTag(Langs.resolve(lang))
            val value = when {
                st.unknown && st.comment != null -> st.comment
                at == null -> st.comment
                at.toLocalDate().isAfter(now.toLocalDate().plusDays(6)) ->
                    DateTimeFormatter.ofPattern("d MMM", locale).format(at) + " " + clock(at)
                at.toLocalDate().isAfter(now.toLocalDate().plusDays(1)) ->
                    at.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) + " " + clock(at)
                else -> clock(at)
            }
            add(FactKind.OPENING_HOURS, "opening_hours", state, value)
        }

        fun access() {
            t("wheelchair")?.let { v ->
                add(FactKind.WHEELCHAIR, "wheelchair", stateOf(v) ?: FactState.UNKNOWN, valueLabel = option("wheelchair", v.lowercase()))
            }
            t("toilets:wheelchair")?.let { v -> add(FactKind.TOILETS_WHEELCHAIR, "toilets:wheelchair", stateOf(v) ?: FactState.UNKNOWN) }
            t("access")?.let { v ->
                val s = when (v.lowercase()) {
                    "yes", "public", "permissive", "designated" -> FactState.YES
                    "no", "private" -> FactState.NO
                    else -> FactState.LIMITED
                }
                add(FactKind.ACCESS, "access", s, v.lowercase(), symbol = if (s == FactState.YES) "ui-unlock" else "ui-lock", valueLabel = option("access", v.lowercase()))
            }
            t("fee")?.let { v ->
                val s = stateOf(v) ?: FactState.YES
                add(FactKind.FEE, "fee", if (s == FactState.LIMITED) FactState.LIMITED else s, if (stateOf(v) == null) v else null)
            }
            t("charge")?.let { add(FactKind.CHARGE, "charge", FactState.YES, it) }
        }

        fun food() {
            for (c in Cuisine.values(tags)) add(FactKind.CUISINE, "cuisine", FactState.YES, c, Cuisine.name(c, lang), Cuisine.symbol(c))
            for ((diet, symbol) in DIETS) {
                val v = t("diet:$diet") ?: continue
                add(FactKind.DIET, "diet:$diet", stateOf(v) ?: FactState.YES, diet, option("diet_multi", diet), symbol)
            }
            flag(FactKind.OUTDOOR_SEATING, "outdoor_seating")
            flag(FactKind.INDOOR_SEATING, "indoor_seating")
            flag(FactKind.TAKEAWAY, "takeaway")
            flag(FactKind.DELIVERY, "delivery")
            flag(FactKind.DRIVE_THROUGH, "drive_through")
            t("reservation")?.let { v ->
                val s = when (v.lowercase()) {
                    "required", "members_only" -> FactState.ONLY
                    "recommended" -> FactState.YES
                    else -> stateOf(v) ?: FactState.YES
                }
                add(FactKind.RESERVATION, "reservation", s, v.lowercase().takeIf { it == "recommended" || it == "required" || it == "members_only" })
            }
            flag(FactKind.ORGANIC, "organic")
            flag(FactKind.SECOND_HAND, "second_hand")
            flag(FactKind.SELF_SERVICE, "self_service")
        }

        fun services() {
            t("internet_access")?.let { v ->
                val s = when (v.lowercase()) {
                    "no" -> FactState.NO
                    "wlan", "yes", "wifi", "wired", "terminal", "public" -> when (t("internet_access:fee")?.lowercase()) {
                        "customers" -> FactState.LIMITED
                        else -> FactState.YES
                    }
                    else -> FactState.YES
                }
                val fee = when (t("internet_access:fee")?.lowercase()) {
                    "no" -> "free"
                    "yes" -> "fee"
                    "customers" -> "customers"
                    else -> null
                }
                add(FactKind.INTERNET, "internet_access", s, fee, symbol = if (s == FactState.NO) "ui-wifi-off" else "ui-wifi")
            }
            // payment: cash, cards, contactless and apps, each one symbol
            t("payment:cash")?.let { v ->
                add(FactKind.PAYMENT, "payment:cash", stateOf(v) ?: FactState.YES, "cash", option("payment_multi", "cash"), "ui-cash")
            }
            val cards = CARDS.mapNotNull { k -> t("payment:$k")?.let { k to (stateOf(it) ?: FactState.YES) } }
            if (cards.isNotEmpty()) {
                val s = if (cards.any { it.second == FactState.YES }) FactState.YES else if (cards.all { it.second == FactState.NO }) FactState.NO else FactState.LIMITED
                add(FactKind.PAYMENT, "payment:" + (cards.firstOrNull { it.second == FactState.YES } ?: cards.first()).first, s, "cards", option("payment_multi", "cards"), "ui-card")
            }
            t("payment:contactless")?.let { v ->
                add(FactKind.PAYMENT, "payment:contactless", stateOf(v) ?: FactState.YES, "contactless", option("payment_multi", "contactless"), "ui-contactless")
            }
            val apps = APPS.mapNotNull { k -> t("payment:$k")?.let { k to (stateOf(it) ?: FactState.YES) } }
            if (apps.isNotEmpty()) {
                val s = if (apps.any { it.second == FactState.YES }) FactState.YES else FactState.NO
                add(FactKind.PAYMENT, "payment:" + apps.first().first, s, "app", option("payment_multi", "app"), "ui-smartphone")
            }
            t("smoking")?.let { v ->
                val s = when (v.lowercase()) {
                    "no" -> FactState.NO
                    "yes", "dedicated" -> FactState.YES
                    "outside", "separated", "isolated", "designated" -> FactState.LIMITED
                    else -> stateOf(v) ?: FactState.LIMITED
                }
                add(FactKind.SMOKING, "smoking", s, v.lowercase(), symbol = if (s == FactState.NO) "ui-smoke-off" else "ui-smoking", valueLabel = option("smoking", v.lowercase()))
            }
            if (tags["amenity"] != "toilets") flag(FactKind.TOILETS, "toilets")
            flag(FactKind.CHANGING_TABLE, "changing_table")
            t("dog")?.let { v ->
                val s = when (v.lowercase()) {
                    "yes", "unleashed" -> FactState.YES
                    "leashed", "outside" -> FactState.LIMITED
                    "no" -> FactState.NO
                    else -> stateOf(v) ?: FactState.YES
                }
                add(FactKind.DOG, "dog", s, v.lowercase().takeIf { it == "leashed" || it == "unleashed" || it == "outside" }, valueLabel = option("dog", v.lowercase()))
            }
            flag(FactKind.AIR_CONDITIONING, "air_conditioning")
            t("level")?.let { add(FactKind.LEVEL, "level", FactState.YES, "L" + it.substringBefore(';')) }
            (t("drinking_water:refill") ?: t("bottle"))?.let { v ->
                add(FactKind.BOTTLE_REFILL, if (t("drinking_water:refill") != null) "drinking_water:refill" else "bottle", stateOf(v) ?: FactState.YES)
            }
        }

        fun amenities() {
            t("capacity")?.let { v ->
                val symbol = when {
                    tags["amenity"] in setOf("parking", "bicycle_parking", "motorcycle_parking") -> "ui-parking"
                    tags["amenity"] == "charging_station" -> "ui-plug"
                    else -> FactKind.CAPACITY.symbol
                }
                add(FactKind.CAPACITY, "capacity", FactState.YES, v, symbol = symbol)
            }
            t("stars")?.let { add(FactKind.STARS, "stars", FactState.YES, it) }
            t("rooms")?.let { add(FactKind.ROOMS, "rooms", FactState.YES, it) }
            for (s in t("sport")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()) {
                val preset = Presets.match(mapOf("leisure" to "pitch", "sport" to s))
                val icon = preset?.takeIf { it.id.endsWith("/$s") }?.let { Presets.iconOf(it) }?.takeIf { Symbols.has(it) } ?: FactKind.SPORT.symbol
                add(FactKind.SPORT, "sport", FactState.YES, s, option("sport", s) ?: readable(s), icon)
            }
            for ((k, v) in tags) {
                if (!k.startsWith("recycling:")) continue
                val what = k.removePrefix("recycling:")
                if (':' in what) continue
                val s = stateOf(v) ?: continue
                if (s == FactState.NO) continue
                add(FactKind.RECYCLING, k, s, what, option("recycling_accepts", what) ?: readable(what))
            }
        }

        fun vehicles() {
            // charging sockets: count and the highest output per type
            val types = tags.keys.filter { it.startsWith("socket:") && it.count { c -> c == ':' } == 1 }.sorted()
            for (k in types) {
                val type = k.removePrefix("socket:")
                val v = t(k) ?: continue
                if (stateOf(v) == FactState.NO) continue
                val count = v.toIntOrNull()
                val kw = t("$k:output")?.let { o -> Units.parseAll(o).filter { it.dimension == Dimension.POWER || it.dimension == Dimension.NONE }.maxOfOrNull { it.value } }
                val value = listOfNotNull(count?.let { "$it ×" }, kw?.let { formatKw(it) }).joinToString(" ").ifEmpty { null }
                add(FactKind.SOCKET, k, FactState.YES, value, SOCKETS[type] ?: readable(type))
            }
            for ((k, v) in tags) {
                if (!k.startsWith("fuel:")) continue
                val fuel = k.removePrefix("fuel:")
                if (':' in fuel || stateOf(v) != FactState.YES) continue
                add(FactKind.FUEL, k, FactState.YES, fuel, option("fuel/fuel_multi", fuel) ?: readable(fuel))
            }
        }

        fun outdoors() {
            val path = "highway" in tags || tags["leisure"] in setOf("pitch", "track") || tags["piste:type"] != null
            if (!path) return
            t("surface")?.let { v ->
                val s = v.lowercase()
                val state = when {
                    s in PAVED -> FactState.YES
                    s in ROUGH -> FactState.LIMITED
                    else -> FactState.NO
                }
                add(FactKind.SURFACE, "surface", state, s, valueLabel = option("surface", s) ?: readable(s))
            }
            t("lit")?.let { v ->
                val s = when (v.lowercase()) {
                    "no", "disused" -> FactState.NO
                    "limited", "interval", "automatic", "sunset-sunrise" -> if (v == "sunset-sunrise") FactState.YES else FactState.LIMITED
                    else -> stateOf(v) ?: FactState.YES
                }
                add(FactKind.LIT, "lit", s)
            }
            t("incline")?.let { v ->
                val flat = v == "0" || v == "0%" || v == "0°"
                add(FactKind.INCLINE, "incline", if (flat) FactState.NO else FactState.YES, v)
            }
        }

        fun contact() {
            (t("phone") ?: t("contact:phone"))?.let { add(FactKind.PHONE, if (t("phone") != null) "phone" else "contact:phone", FactState.YES, it.substringBefore(';').trim()) }
            (t("website") ?: t("contact:website") ?: t("url"))?.let {
                add(FactKind.WEBSITE, if (t("website") != null) "website" else if (t("contact:website") != null) "contact:website" else "url", FactState.YES, it.substringBefore(';').trim())
            }
            t("wikipedia")?.let { add(FactKind.WIKIPEDIA, "wikipedia", FactState.YES, it) }
            t("wikidata")?.let { add(FactKind.WIKIDATA, "wikidata", FactState.YES, it) }
        }

        /** Facts the preset shows first but nobody mapped, and wheelchair access for every place people visit. */
        fun unknowns(geometry: Geometry) {
            val preset = Presets.match(tags, geometry) ?: return
            val isPlace = Categories.of(tags) != null
            val asks = preset.fields.toMutableList()
            if (isPlace && "wheelchair" in preset.moreFields) asks += "wheelchair"
            for (f in asks) {
                val (kind, key) = ASKABLE[f] ?: continue
                if (kind in kinds) continue
                if (kind == FactKind.ACCESS && !isPlace) continue
                // the facts that read several keys count as mapped when any of their keys is there
                if (kind == FactKind.DIET && tags.keys.any { it.startsWith("diet:") }) continue
                if (key in tags) continue
                out += Fact(kind, key, kind.symbol, FactState.UNKNOWN, label = fieldLabel(kind), mapped = false)
                kinds += kind
            }
        }

        private fun clock(t: LocalDateTime) = "%02d:%02d".format(Locale.ROOT, t.hour, t.minute)

        private fun readable(v: String) = v.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

        private fun formatKw(kw: Double): String =
            if (kw == Math.rint(kw)) "${kw.toLong()} kW" else "%.1f kW".format(Locale.ROOT, kw)
    }
}
