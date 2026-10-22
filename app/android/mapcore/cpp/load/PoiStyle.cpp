#include "load/PoiStyle.hpp"

#include "text/Case.hpp"

#include <algorithm>
#include <array>
#include <string>
#include <string_view>

namespace atlas::load {

namespace {

struct Category {
    std::string_view name;
    std::string_view col;
};

struct Hit {
    std::string_view value; ///< the tag's value
    std::string_view cat;
    std::string_view glyph;
    std::string_view kind;
};

struct Brand {
    std::string_view wikidata;
    std::string_view bg;
    std::string_view fg;
};

// render/poi_style.py's CATS, SHOP, AMENITY, TOURISM, LEISURE and BRAND, in its order.
constexpr std::array<Category, 9> categories = {{
    {.name = "food", .col = "#E07A1F"},
    {.name = "grocery", .col = "#2E9E5B"},
    {.name = "shop", .col = "#8E5BD0"},
    {.name = "beauty", .col = "#D2508F"},
    {.name = "culture", .col = "#B8326A"},
    {.name = "stay", .col = "#2E6FD8"},
    {.name = "health", .col = "#D23C3C"},
    {.name = "service", .col = "#5A6478"},
    {.name = "sport", .col = "#1D8FA0"},
}};
constexpr std::array<Hit, 46> shops = {{
    {.value = "clothes", .cat = "shop", .glyph = "shirt", .kind = "Clothes"},
    {.value = "fashion_accessories", .cat = "shop", .glyph = "bag", .kind = "Accessories"},
    {.value = "bag", .cat = "shop", .glyph = "bag", .kind = "Bags"},
    {.value = "shoes", .cat = "shop", .glyph = "shoe", .kind = "Shoes"},
    {.value = "gift", .cat = "shop", .glyph = "gift", .kind = "Gifts"},
    {.value = "jewelry", .cat = "shop", .glyph = "ring", .kind = "Jewellery"},
    {.value = "interior_decoration", .cat = "shop", .glyph = "sofa", .kind = "Interiors"},
    {.value = "furniture", .cat = "shop", .glyph = "sofa", .kind = "Furniture"},
    {.value = "antiques", .cat = "shop", .glyph = "sofa", .kind = "Antiques"},
    {.value = "art", .cat = "culture", .glyph = "palette", .kind = "Art"},
    {.value = "music", .cat = "shop", .glyph = "note", .kind = "Records"},
    {.value = "musical_instrument", .cat = "shop", .glyph = "note", .kind = "Instruments"},
    {.value = "books", .cat = "shop", .glyph = "book", .kind = "Books"},
    {.value = "toys", .cat = "shop", .glyph = "gift", .kind = "Toys"},
    {.value = "games", .cat = "shop", .glyph = "gift", .kind = "Games"},
    {.value = "party", .cat = "shop", .glyph = "gift", .kind = "Party"},
    {.value = "ticket", .cat = "service", .glyph = "ticket", .kind = "Tickets"},
    {.value = "optician", .cat = "health", .glyph = "glasses", .kind = "Optician"},
    {.value = "bicycle", .cat = "shop", .glyph = "bike", .kind = "Bikes"},
    {.value = "houseware", .cat = "shop", .glyph = "sofa", .kind = "Homeware"},
    {.value = "doityourself", .cat = "shop", .glyph = "bag", .kind = "DIY"},
    {.value = "copyshop", .cat = "service", .glyph = "book", .kind = "Copy shop"},
    {.value = "tailor", .cat = "service", .glyph = "scissors", .kind = "Tailor"},
    {.value = "fabric", .cat = "shop", .glyph = "scissors", .kind = "Fabric"},
    {.value = "craft", .cat = "shop", .glyph = "palette", .kind = "Craft"},
    {.value = "hairdresser", .cat = "beauty", .glyph = "scissors", .kind = "Hair"},
    {.value = "beauty", .cat = "beauty", .glyph = "spark", .kind = "Beauty"},
    {.value = "cosmetics", .cat = "beauty", .glyph = "spark", .kind = "Cosmetics"},
    {.value = "massage", .cat = "beauty", .glyph = "spark", .kind = "Massage"},
    {.value = "tattoo", .cat = "beauty", .glyph = "spark", .kind = "Tattoo"},
    {.value = "chemist", .cat = "health", .glyph = "cross", .kind = "Chemist"},
    {.value = "supermarket", .cat = "grocery", .glyph = "cart", .kind = "Supermarket"},
    {.value = "convenience", .cat = "grocery", .glyph = "cart", .kind = "Corner shop"},
    {.value = "bakery", .cat = "grocery", .glyph = "bread", .kind = "Bakery"},
    {.value = "pastry", .cat = "grocery", .glyph = "cake", .kind = "Pâtisserie"},
    {.value = "confectionery", .cat = "grocery", .glyph = "cake", .kind = "Sweets"},
    {.value = "cheese", .cat = "grocery", .glyph = "cheese", .kind = "Cheese"},
    {.value = "alcohol", .cat = "grocery", .glyph = "bottle", .kind = "Wine & spirits"},
    {.value = "wine", .cat = "grocery", .glyph = "bottle", .kind = "Wine"},
    {.value = "tea", .cat = "grocery", .glyph = "tea", .kind = "Tea"},
    {.value = "florist", .cat = "shop", .glyph = "flower", .kind = "Florist"},
    {.value = "newsagent", .cat = "shop", .glyph = "book", .kind = "Newsagent"},
    {.value = "kiosk", .cat = "shop", .glyph = "book", .kind = "Kiosk"},
    {.value = "cannabis", .cat = "shop", .glyph = "leaf", .kind = "Coffeeshop"},
    {.value = "rental", .cat = "service", .glyph = "bike", .kind = "Rental"},
    {.value = "vacant", .cat = "service", .glyph = "pin", .kind = "Empty shop"},
}};
constexpr std::array<Hit, 18> amenities = {{
    {.value = "restaurant", .cat = "food", .glyph = "fork", .kind = "Restaurant"},
    {.value = "cafe", .cat = "food", .glyph = "cup", .kind = "Café"},
    {.value = "pub", .cat = "food", .glyph = "beer", .kind = "Brown café"},
    {.value = "bar", .cat = "food", .glyph = "cocktail", .kind = "Bar"},
    {.value = "fast_food", .cat = "food", .glyph = "burger", .kind = "Takeaway"},
    {.value = "ice_cream", .cat = "food", .glyph = "icecream", .kind = "Ice cream"},
    {.value = "nightclub", .cat = "food", .glyph = "cocktail", .kind = "Club"},
    {.value = "pharmacy", .cat = "health", .glyph = "cross", .kind = "Pharmacy"},
    {.value = "doctors", .cat = "health", .glyph = "cross", .kind = "Doctor"},
    {.value = "dentist", .cat = "health", .glyph = "tooth", .kind = "Dentist"},
    {.value = "theatre", .cat = "culture", .glyph = "mask", .kind = "Theatre"},
    {.value = "arts_centre", .cat = "culture", .glyph = "palette", .kind = "Arts centre"},
    {.value = "art_gallery", .cat = "culture", .glyph = "frame", .kind = "Gallery"},
    {.value = "events_venue", .cat = "culture", .glyph = "star", .kind = "Venue"},
    {.value = "atm", .cat = "service", .glyph = "cash", .kind = "Cash machine"},
    {.value = "post_box", .cat = "service", .glyph = "mail", .kind = "Post box"},
    {.value = "bicycle_rental", .cat = "service", .glyph = "bike", .kind = "Bike rental"},
    {.value = "public_bookcase", .cat = "culture", .glyph = "book", .kind = "Book swap"},
}};
constexpr std::array<Hit, 6> tourism = {{
    {.value = "hotel", .cat = "stay", .glyph = "bed", .kind = "Hotel"},
    {.value = "guest_house", .cat = "stay", .glyph = "bed", .kind = "Guest house"},
    {.value = "apartment", .cat = "stay", .glyph = "bed", .kind = "Apartment"},
    {.value = "museum", .cat = "culture", .glyph = "columns", .kind = "Museum"},
    {.value = "gallery", .cat = "culture", .glyph = "frame", .kind = "Gallery"},
    {.value = "attraction", .cat = "culture", .glyph = "star", .kind = "Attraction"},
}};
constexpr std::array<Hit, 2> leisure = {{
    {.value = "fitness_centre", .cat = "sport", .glyph = "dumbbell", .kind = "Gym"},
    {.value = "sports_centre", .cat = "sport", .glyph = "dumbbell", .kind = "Sports"},
}};
constexpr std::array<Brand, 20> brands = {{
    {.wikidata = "Q1653985", .bg = "#00A0E2", .fg = "#FFFFFF"},
    {.wikidata = "Q13639185", .bg = "#9E1B32", .fg = "#FFFFFF"},
    {.wikidata = "Q2609459", .bg = "#E30613", .fg = "#FFFFFF"},
    {.wikidata = "Q33903645", .bg = "#E3000F", .fg = "#FFFFFF"},
    {.wikidata = "Q582245", .bg = "#FFD200", .fg = "#C8102E"},
    {.wikidata = "Q62562792", .bg = "#7AB929", .fg = "#FFFFFF"},
    {.wikidata = "Q74051230", .bg = "#FFB800", .fg = "#1E1E1E"},
    {.wikidata = "Q127962", .bg = "#C41230", .fg = "#FFFFFF"},
    {.wikidata = "Q1126126", .bg = "#1A1A1A", .fg = "#F6D21E"},
    {.wikidata = "Q6702957", .bg = "#D31F40", .fg = "#FFFFFF"},
    {.wikidata = "Q60772401", .bg = "#1A1A1A", .fg = "#FFFFFF"},
    {.wikidata = "Q648458", .bg = "#1E3765", .fg = "#FFFFFF"},
    {.wikidata = "Q2220070", .bg = "#23412F", .fg = "#E9D9A6"},
    {.wikidata = "Q13433690", .bg = "#5B3A29", .fg = "#F2E6CF"},
    {.wikidata = "Q3572115", .bg = "#FFE600", .fg = "#1E1E1E"},
    {.wikidata = "Q934096", .bg = "#9B1B1F", .fg = "#FFFFFF"},
    {.wikidata = "Q527877", .bg = "#2B2A28", .fg = "#E89A2B"},
    {.wikidata = "Q24039229", .bg = "#1E1E1E", .fg = "#FFFFFF"},
    {.wikidata = "Q7958488", .bg = "#1E1E1E", .fg = "#FFFFFF"},
    {.wikidata = "Q123655125", .bg = "#F4C6CF", .fg = "#3A2A2E"},
}};

/// The hit a table holds for a tag's value, or nothing (no value, or one it does not list).
template <std::size_t N>
[[nodiscard]] const Hit* hit_in(const std::array<Hit, N>& table, std::optional<std::string_view> value) noexcept {
    if (!value) return nullptr;
    const auto* const it = std::ranges::find(table, *value, &Hit::value);
    return it != table.end() ? it : nullptr;
}

} // namespace

std::optional<PlaceStyle> place_style(const Tags& t) {
    std::string_view cat;
    std::string_view glyph;
    std::string kind;
    const auto take = [&](const Hit& h) {
        cat = h.cat;
        glyph = h.glyph;
        kind = h.kind;
    };
    if (const Hit* h = hit_in(amenities, t.get("amenity"))) {
        take(*h);
    } else if (const Hit* s = hit_in(shops, t.get("shop"))) {
        take(*s);
    } else if (const auto shop = t.get("shop")) {
        std::string label(*shop);
        std::ranges::replace(label, '_', ' ');
        cat = "shop";
        glyph = "bag";
        kind = text::capitalize(label);
    } else if (const Hit* r = hit_in(tourism, t.get("tourism"))) {
        take(*r);
    } else if (const Hit* l = hit_in(leisure, t.get("leisure"))) {
        take(*l);
    } else if (t.has("office")) {
        cat = "service";
        glyph = "briefcase";
        kind = "Office";
    } else if (t.truthy("healthcare")) {
        cat = "health";
        glyph = "cross";
        kind = "Health";
    } else {
        return std::nullopt;
    }
    PlaceStyle style{.cat = cat, .col = {}, .glyph = glyph, .kind = std::move(kind), .brand = t.get("brand")};
    style.col = std::ranges::find(categories, cat, &Category::name)->col;
    if (const auto wikidata = t.get("brand:wikidata")) {
        const auto* const b = std::ranges::find(brands, *wikidata, &Brand::wikidata);
        if (b != brands.end()) {
            style.bg = b->bg;
            style.fg = b->fg;
        }
    }
    if (truthy(style.brand) && !truthy(style.bg)) {
        style.bg = "#26282C";
        style.fg = "#FFFFFF";
    }
    return style;
}

} // namespace atlas::load
