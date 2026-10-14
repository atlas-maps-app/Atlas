// render/poi_style.py: how a place looks in Atlas, from its tags: a category (colour), a glyph, a kind label, and for
// chains a brand style. Brand colours are Atlas's own style table, keyed by the mapped brand:wikidata. Ported line by line.
package app.atlas.render

import app.atlas.render.py.truthy

/** The place styles of render/poi_style.py. */
object PoiStyle {
    /** A category: its label and colour. */
    data class Cat(val label: String, val col: String)

    /** What a tag value means: category, glyph and kind label. */
    data class Hit(val cat: String, val glyph: String, val kind: String)

    /** The style of one place: `dict(cat, col, glyph, kind, brand, bg, fg)`. */
    data class Style(val cat: String, val col: String, val glyph: String, val kind: String, val brand: String?, val bg: String?, val fg: String?)

    // render/poi_style.py:4 CATS
    val CATS: Map<String, Cat> = linkedMapOf(
        "food" to Cat("Food & drink", "#E07A1F"),
        "grocery" to Cat("Groceries", "#2E9E5B"),
        "shop" to Cat("Shops", "#8E5BD0"),
        "beauty" to Cat("Beauty & hair", "#D2508F"),
        "culture" to Cat("Culture", "#B8326A"),
        "stay" to Cat("Stay", "#2E6FD8"),
        "health" to Cat("Health", "#D23C3C"),
        "service" to Cat("Services", "#5A6478"),
        "sport" to Cat("Sport", "#1D8FA0"),
    )

    // render/poi_style.py:16 GLYPH (24 x 24 stroke glyphs)
    val GLYPH: Map<String, String> = linkedMapOf(
        "fork" to "<path d=\"M7 3v8a2 2 0 0 0 4 0V3M9 13v8M16 3c-2 1-2.5 4-2.5 6.5H17V21\"/>",
        "cup" to "<path d=\"M5 9h11v5a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5z\"/><path d=\"M16 11h2a2 2 0 0 1 0 4h-2M9 3c0 2 2 2 2 4\"/>",
        "beer" to "<path d=\"M6 7h10v12a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2zM16 10h2a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-2M6 7a3 3 0 0 1 3-3 3 3 0 0 1 5 0 3 3 0 0 1 2 3\"/>",
        "cocktail" to "<path d=\"M4 4h16l-8 9zM12 13v7M8 20h8\"/>",
        "burger" to "<path d=\"M4 10a8 5 0 0 1 16 0zM4 14h16M5 17h14a1 1 0 0 1 0 0 3 3 0 0 1-3 3H8a3 3 0 0 1-3-3z\"/>",
        "icecream" to "<path d=\"M8 11a4 4 0 1 1 8 0zM8 11l4 10 4-10\"/>",
        "bag" to "<path d=\"M5 8h14l-1 13H6zM9 8V6a3 3 0 0 1 6 0v2\"/>",
        "shirt" to "<path d=\"M8 3l-5 3 2 4 3-1v12h8V9l3 1 2-4-5-3a4 4 0 0 1-8 0z\"/>",
        "gift" to "<path d=\"M4 9h16v4H4zM6 13h12v8H6zM12 9v12M12 9C10 5 7 5 7 7s3 2 5 2c2 0 5 0 5-2s-3-2-5 2\"/>",
        "ring" to "<circle cx=\"12\" cy=\"14\" r=\"6\"/><path d=\"M9 4h6l-3 4z\"/>",
        "scissors" to "<circle cx=\"6\" cy=\"18\" r=\"3\"/><circle cx=\"18\" cy=\"18\" r=\"3\"/><path d=\"M8 16L19 4M16 16L5 4\"/>",
        "shoe" to "<path d=\"M3 16V8h5l3 4 7 2a3 3 0 0 1 3 3v1H3z\"/>",
        "bread" to "<path d=\"M5 11a7 5 0 0 1 14 0v8H5z\"/><path d=\"M9 9l-1 3M13 9l-1 3M17 10l-1 3\"/>",
        "cake" to "<path d=\"M4 13h16v7H4zM6 13V9h12v4M12 9V6M12 4v.5\"/>",
        "cart" to "<path d=\"M3 4h2l2 11h11l2-8H6\"/><circle cx=\"9\" cy=\"19\" r=\"1.5\"/><circle cx=\"17\" cy=\"19\" r=\"1.5\"/>",
        "cheese" to "<path d=\"M3 17l9-11 9 6v5zM3 17h18\"/><circle cx=\"10\" cy=\"14\" r=\"1\"/><circle cx=\"15\" cy=\"15\" r=\"1\"/>",
        "bottle" to "<path d=\"M10 2h4v4l2 3v12H8V9l2-3z\"/><path d=\"M8 13h8\"/>",
        "leaf" to "<path d=\"M12 21V9M12 13c-4-1-7-4-7-9 4 1 7 4 7 9 0-5 3-8 7-9 0 5-3 8-7 9\"/>",
        "ticket" to "<path d=\"M3 8a2 2 0 0 0 2-2h14a2 2 0 0 0 2 2v8a2 2 0 0 0-2 2H5a2 2 0 0 0-2-2z\"/><path d=\"M14 6v12\"/>",
        "sofa" to "<path d=\"M4 11V8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v3M3 11h18v6H3zM5 17v2M19 17v2\"/>",
        "glasses" to "<circle cx=\"7\" cy=\"14\" r=\"3.5\"/><circle cx=\"17\" cy=\"14\" r=\"3.5\"/><path d=\"M10.5 14h3M3.5 13L5 7M20.5 13L19 7\"/>",
        "bike" to "<circle cx=\"6\" cy=\"16\" r=\"3.5\"/><circle cx=\"18\" cy=\"16\" r=\"3.5\"/><path d=\"M6 16l4-8h6l2 8M10 8L8.5 5H7M16 8l-3.5 8\"/>",
        "book" to "<path d=\"M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2zM4 19V5M19 19v2H6\"/>",
        "note" to "<path d=\"M9 18V5l11-2v13\"/><circle cx=\"6.5\" cy=\"18\" r=\"2.5\"/><circle cx=\"17.5\" cy=\"16\" r=\"2.5\"/>",
        "palette" to "<path d=\"M12 3a9 9 0 1 0 0 18c1 0 1.5-.8 1.5-1.6 0-1.2-1-1.4-1-2.4 0-1 .8-1.5 2-1.5H17a4 4 0 0 0 4-4c0-4.5-4-8.5-9-8.5z\"/><circle cx=\"7.5\" cy=\"11\" r=\"1\"/><circle cx=\"10\" cy=\"7\" r=\"1\"/><circle cx=\"15\" cy=\"7\" r=\"1\"/>",
        "bed" to "<path d=\"M3 18V7M3 13h18v5M21 13a3 3 0 0 0-3-3h-7v3M7 11a1.5 1.5 0 1 0 0-.01\"/>",
        "columns" to "<path d=\"M3 9l9-5 9 5M5 9v9M9.5 9v9M14.5 9v9M19 9v9M3 21h18\"/>",
        "frame" to "<path d=\"M4 4h16v16H4zM7 16l3-4 2 2 3-4 2 6z\"/>",
        "mask" to "<path d=\"M4 5h16v6a8 8 0 0 1-16 0z\"/><path d=\"M8 9h2M14 9h2M9 14c1.5 1.5 4.5 1.5 6 0\"/>",
        "cross" to "<path d=\"M9 3h6v6h6v6h-6v6H9v-6H3V9h6z\"/>",
        "tooth" to "<path d=\"M7 3c-3 0-4 3-3 6 1 2 1 5 2 9 .5 2 2 2 2.5 0L10 14h4l1.5 4c.5 2 2 2 2.5 0 1-4 1-7 2-9 1-3 0-6-3-6-2 0-3 1-5 1S9 3 7 3z\"/>",
        "cash" to "<path d=\"M3 7h18v10H3z\"/><circle cx=\"12\" cy=\"12\" r=\"2.5\"/><path d=\"M6 10v4M18 10v4\"/>",
        "mail" to "<path d=\"M3 6h18v12H3zM3 7l9 6 9-6\"/>",
        "dumbbell" to "<path d=\"M6 7v10M3 9v6M18 7v10M21 9v6M6 12h12\"/>",
        "briefcase" to "<path d=\"M3 8h18v11H3zM9 8V5h6v3M3 13h18\"/>",
        "star" to "<path d=\"M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z\"/>",
        "flower" to "<circle cx=\"12\" cy=\"9\" r=\"2.5\"/><path d=\"M12 6.5a3 3 0 1 1 0-.01M14.5 9a3 3 0 1 1 0 .01M9.5 9a3 3 0 1 1 0 .01M12 11.5a3 3 0 1 1 0 .01M12 14v7M12 18c-2 0-4-1-4-3\"/>",
        "spark" to "<path d=\"M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5L18 18M6 18l2.5-2.5M15.5 8.5L18 6\"/>",
        "tea" to "<path d=\"M5 10h12v4a6 6 0 0 1-12 0zM17 11h1.5a2 2 0 0 1 0 4H17M8 6l1-3M12 6l1-3\"/>",
        "pin" to "<circle cx=\"12\" cy=\"10\" r=\"3\"/><path d=\"M12 21s-7-6-7-11a7 7 0 0 1 14 0c0 5-7 11-7 11z\"/>",
    )

    private fun h(cat: String, glyph: String, kind: String) = Hit(cat, glyph, kind)

    // render/poi_style.py:58 SHOP (shop=* -> category, glyph, kind label)
    val SHOP: Map<String, Hit> = linkedMapOf(
        "clothes" to h("shop", "shirt", "Clothes"), "fashion_accessories" to h("shop", "bag", "Accessories"), "bag" to h("shop", "bag", "Bags"), "shoes" to h("shop", "shoe", "Shoes"),
        "gift" to h("shop", "gift", "Gifts"), "jewelry" to h("shop", "ring", "Jewellery"), "interior_decoration" to h("shop", "sofa", "Interiors"), "furniture" to h("shop", "sofa", "Furniture"),
        "antiques" to h("shop", "sofa", "Antiques"), "art" to h("culture", "palette", "Art"), "music" to h("shop", "note", "Records"), "musical_instrument" to h("shop", "note", "Instruments"),
        "books" to h("shop", "book", "Books"), "toys" to h("shop", "gift", "Toys"), "games" to h("shop", "gift", "Games"), "party" to h("shop", "gift", "Party"), "ticket" to h("service", "ticket", "Tickets"),
        "optician" to h("health", "glasses", "Optician"), "bicycle" to h("shop", "bike", "Bikes"), "houseware" to h("shop", "sofa", "Homeware"), "doityourself" to h("shop", "bag", "DIY"),
        "copyshop" to h("service", "book", "Copy shop"), "tailor" to h("service", "scissors", "Tailor"), "fabric" to h("shop", "scissors", "Fabric"), "craft" to h("shop", "palette", "Craft"),
        "hairdresser" to h("beauty", "scissors", "Hair"), "beauty" to h("beauty", "spark", "Beauty"), "cosmetics" to h("beauty", "spark", "Cosmetics"), "massage" to h("beauty", "spark", "Massage"), "tattoo" to h("beauty", "spark", "Tattoo"),
        "chemist" to h("health", "cross", "Chemist"),
        "supermarket" to h("grocery", "cart", "Supermarket"), "convenience" to h("grocery", "cart", "Corner shop"), "bakery" to h("grocery", "bread", "Bakery"), "pastry" to h("grocery", "cake", "Pâtisserie"),
        "confectionery" to h("grocery", "cake", "Sweets"), "cheese" to h("grocery", "cheese", "Cheese"), "alcohol" to h("grocery", "bottle", "Wine & spirits"), "wine" to h("grocery", "bottle", "Wine"),
        "tea" to h("grocery", "tea", "Tea"), "florist" to h("shop", "flower", "Florist"), "newsagent" to h("shop", "book", "Newsagent"), "kiosk" to h("shop", "book", "Kiosk"),
        "cannabis" to h("shop", "leaf", "Coffeeshop"), "rental" to h("service", "bike", "Rental"), "vacant" to h("service", "pin", "Empty shop"),
    )

    // render/poi_style.py:71 AMENITY
    val AMENITY: Map<String, Hit> = linkedMapOf(
        "restaurant" to h("food", "fork", "Restaurant"), "cafe" to h("food", "cup", "Café"), "pub" to h("food", "beer", "Brown café"), "bar" to h("food", "cocktail", "Bar"),
        "fast_food" to h("food", "burger", "Takeaway"), "ice_cream" to h("food", "icecream", "Ice cream"), "nightclub" to h("food", "cocktail", "Club"),
        "pharmacy" to h("health", "cross", "Pharmacy"), "doctors" to h("health", "cross", "Doctor"), "dentist" to h("health", "tooth", "Dentist"),
        "theatre" to h("culture", "mask", "Theatre"), "arts_centre" to h("culture", "palette", "Arts centre"), "art_gallery" to h("culture", "frame", "Gallery"), "events_venue" to h("culture", "star", "Venue"),
        "atm" to h("service", "cash", "Cash machine"), "post_box" to h("service", "mail", "Post box"), "bicycle_rental" to h("service", "bike", "Bike rental"), "public_bookcase" to h("culture", "book", "Book swap"),
    )

    // render/poi_style.py:78 TOURISM
    val TOURISM: Map<String, Hit> = linkedMapOf(
        "hotel" to h("stay", "bed", "Hotel"), "guest_house" to h("stay", "bed", "Guest house"), "apartment" to h("stay", "bed", "Apartment"), "museum" to h("culture", "columns", "Museum"),
        "gallery" to h("culture", "frame", "Gallery"), "attraction" to h("culture", "star", "Attraction"),
    )

    // render/poi_style.py:80 LEISURE
    val LEISURE: Map<String, Hit> = linkedMapOf("fitness_centre" to h("sport", "dumbbell", "Gym"), "sports_centre" to h("sport", "dumbbell", "Sports"))

    // render/poi_style.py:83 BRAND (keyed by brand:wikidata: sign colour, text colour)
    val BRAND: Map<String, Pair<String, String>> = linkedMapOf(
        "Q1653985" to ("#00A0E2" to "#FFFFFF"), // Albert Heijn
        "Q13639185" to ("#9E1B32" to "#FFFFFF"), // Gall & Gall
        "Q2609459" to ("#E30613" to "#FFFFFF"), // Etos
        "Q33903645" to ("#E3000F" to "#FFFFFF"), // Blokker
        "Q582245" to ("#FFD200" to "#C8102E"), // FEBO
        "Q62562792" to ("#7AB929" to "#FFFFFF"), // BENU
        "Q74051230" to ("#FFB800" to "#1E1E1E"), // Geldmaat
        "Q127962" to ("#C41230" to "#FFFFFF"), // Levi's
        "Q1126126" to ("#1A1A1A" to "#F6D21E"), // Dr. Martens
        "Q6702957" to ("#D31F40" to "#FFFFFF"), // Lululemon
        "Q60772401" to ("#1A1A1A" to "#FFFFFF"), // COS
        "Q648458" to ("#1E3765" to "#FFFFFF"), // Birkenstock
        "Q2220070" to ("#23412F" to "#E9D9A6"), // Simon Lévelt
        "Q13433690" to ("#5B3A29" to "#F2E6CF"), // Bagels & Beans
        "Q3572115" to ("#FFE600" to "#1E1E1E"), // YellowKorner
        "Q934096" to ("#9B1B1F" to "#FFFFFF"), // Red Wing
        "Q527877" to ("#2B2A28" to "#E89A2B"), // Carhartt
        "Q24039229" to ("#1E1E1E" to "#FFFFFF"), // The Hoxton
        "Q7958488" to ("#1E1E1E" to "#FFFFFF"), // W Hotels
        "Q123655125" to ("#F4C6CF" to "#3A2A2E"), // My Jewellery
    )

    private fun <V> Map<String, V>.at(v: Any?): V? = if (v is String) this[v] else null

    /** Python's `str.capitalize()`: the first character upper case, the rest lower case. */
    private fun capitalize(s: String): String {
        if (s.isEmpty()) return s
        val first = s.codePointAt(0); val n = Character.charCount(first)
        return String(Character.toChars(Character.toTitleCase(first))) + s.substring(n).lowercase()
    }

    // render/poi_style.py:106 style
    /** The style of a place (category, glyph, kind label, brand name or null, sign colour, text colour), or null if it is not a place. */
    fun style(t: Map<String, Any?>): Style? {
        var hit: Hit? = null
        if (AMENITY.at(t["amenity"]) != null) hit = AMENITY.at(t["amenity"])
        else if (SHOP.at(t["shop"]) != null) hit = SHOP.at(t["shop"])
        else if ("shop" in t) hit = Hit("shop", "bag", capitalize((t["shop"] as String).replace("_", " ")))
        else if (TOURISM.at(t["tourism"]) != null) hit = TOURISM.at(t["tourism"])
        else if (LEISURE.at(t["leisure"]) != null) hit = LEISURE.at(t["leisure"])
        else if ("office" in t) hit = Hit("service", "briefcase", "Office")
        else if (truthy(t["healthcare"])) hit = Hit("health", "cross", "Health")
        if (hit == null) return null
        val brand = t["brand"] as String?
        var (bg, fg) = (t["brand:wikidata"] as? String)?.let { BRAND[it] } ?: (null to null)
        if (truthy(brand) && !truthy(bg)) {
            bg = "#26282C"; fg = "#FFFFFF"
        }
        return Style(hit.cat, CATS.getValue(hit.cat).col, hit.glyph, hit.kind, brand, bg, fg)
    }
}
