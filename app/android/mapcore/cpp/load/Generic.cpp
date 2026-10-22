#include "load/Generic.hpp"

#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"
#include "geometry/RoofShapes.hpp"
#include "load/Amsterdam.hpp"
#include "load/Real.hpp"
#include "text/Case.hpp"
#include "text/Shaper.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <format>
#include <initializer_list>
#include <span>
#include <string>
#include <utility>
#include <vector>

namespace atlas::load {

namespace {

struct Keyed {
    std::string_view key;
    std::string_view material;
    std::string_view colour;
};

struct Pair {
    std::string_view key;
    std::string_view value;
};

// render/generic.py's tables, in its order (transcribed from the module)
constexpr std::array<Keyed, 15> walls = {{
    {.key = "brick_old", .material = "brick", .colour = "#74402F"},
    {.key = "brick", .material = "brick", .colour = "#8E5A46"},
    {.key = "brick_light", .material = "brick", .colour = "#A27A62"},
    {.key = "brick_yellow", .material = "brick", .colour = "#C2A274"},
    {.key = "limestone", .material = "limestone", .colour = "#D8CCB2"},
    {.key = "plaster", .material = "plaster", .colour = "#E6DFD1"},
    {.key = "concrete", .material = "concrete", .colour = "#BEBAB1"},
    {.key = "concrete_dark", .material = "concrete", .colour = "#A3A09A"},
    {.key = "glass", .material = "glass", .colour = "#93ABBC"},
    {.key = "metal_silver", .material = "metal", .colour = "#C3C7CA"},
    {.key = "metal_grey", .material = "metal", .colour = "#9AA1A7"},
    {.key = "metal_white", .material = "metal", .colour = "#E1E2DF"},
    {.key = "metal_anthracite", .material = "metal", .colour = "#5B6066"},
    {.key = "wood", .material = "wood", .colour = "#7A624C"},
    {.key = "metal_green", .material = "metal", .colour = "#4C5E52"},
}};
constexpr std::array<Keyed, 11> roofs = {{
    {.key = "tiles_red", .material = "roof_tiles", .colour = "#9E5038"},
    {.key = "tiles_dark", .material = "roof_tiles", .colour = "#3F4246"},
    {.key = "slate", .material = "slate", .colour = "#535C66"},
    {.key = "zinc", .material = "metal", .colour = "#737E88"},
    {.key = "lead", .material = "metal", .colour = "#7A8187"},
    {.key = "metal_light", .material = "metal", .colour = "#A4A9AD"},
    {.key = "metal_grey", .material = "metal", .colour = "#8F969B"},
    {.key = "bitumen", .material = "tar_paper", .colour = "#56585C"},
    {.key = "gravel", .material = "gravel", .colour = "#9C988F"},
    {.key = "concrete", .material = "concrete", .colour = "#A7A49E"},
    {.key = "glass", .material = "glass", .colour = "#C3D3DA"},
}};
constexpr std::array<Pair, 11> mat_draw = {{
    {.key = "brick", .value = "#9C5A45"},
    {.key = "stone", .value = "#CFC6B4"},
    {.key = "copper", .value = "#5E9E86"},
    {.key = "glass", .value = "#A9C2D6"},
    {.key = "concrete", .value = "#BDBAB2"},
    {.key = "wood", .value = "#9C7A55"},
    {.key = "roof_tiles", .value = "#A5553C"},
    {.key = "slate", .value = "#56606A"},
    {.key = "metal", .value = "#9AA3AB"},
    {.key = "plaster", .value = "#E6E0D4"},
    {.key = "sandstone", .value = "#D8C49A"},
}};
constexpr std::array<Pair, 13> mat_col = {{
    {.key = "brick", .value = "#8E5A46"},
    {.key = "limestone", .value = "#D8CCB2"},
    {.key = "sandstone", .value = "#D2BE94"},
    {.key = "stone", .value = "#C9C0AE"},
    {.key = "plaster", .value = "#E6DFD1"},
    {.key = "concrete", .value = "#BEBAB1"},
    {.key = "glass", .value = "#93ABBC"},
    {.key = "mirrored_glass", .value = "#8EA6B8"},
    {.key = "metal", .value = "#A9AFB4"},
    {.key = "steel", .value = "#A9AFB4"},
    {.key = "wood", .value = "#7A624C"},
    {.key = "timber_framing", .value = "#D9D0BE"},
    {.key = "cement_block", .value = "#B3AFA6"},
}};
constexpr std::array<Pair, 14> rmat_col = {{
    {.key = "roof_tiles", .value = "#9E5038"},
    {.key = "slate", .value = "#535C66"},
    {.key = "metal", .value = "#8F969B"},
    {.key = "tar_paper", .value = "#56585C"},
    {.key = "gravel", .value = "#A9A59D"},
    {.key = "concrete", .value = "#A7A49E"},
    {.key = "glass", .value = "#C3D3DA"},
    {.key = "copper", .value = "#5E9E86"},
    {.key = "grass", .value = "#7C9A5A"},
    {.key = "plants", .value = "#7C9A5A"},
    {.key = "thatch", .value = "#8E7A52"},
    {.key = "eternit", .value = "#8C8A86"},
    {.key = "zinc", .value = "#8C969E"},
    {.key = "lead", .value = "#7A8187"},
}};
constexpr std::array<Pair, 60> named = {{
    {.key = "white", .value = "#FFFFFF"},         {.key = "black", .value = "#2A2B2E"},
    {.key = "grey", .value = "#808080"},          {.key = "gray", .value = "#808080"},
    {.key = "lightgrey", .value = "#D3D3D3"},     {.key = "lightgray", .value = "#D3D3D3"},
    {.key = "darkgrey", .value = "#A9A9A9"},      {.key = "darkgray", .value = "#A9A9A9"},
    {.key = "dimgray", .value = "#696969"},       {.key = "dimgrey", .value = "#696969"},
    {.key = "silver", .value = "#C0C0C0"},        {.key = "gainsboro", .value = "#DCDCDC"},
    {.key = "whitesmoke", .value = "#F5F5F5"},    {.key = "slategray", .value = "#708090"},
    {.key = "slategrey", .value = "#708090"},     {.key = "red", .value = "#B03A2E"},
    {.key = "darkred", .value = "#8B0000"},       {.key = "firebrick", .value = "#B22222"},
    {.key = "indianred", .value = "#CD5C5C"},     {.key = "brown", .value = "#8B5A3C"},
    {.key = "maroon", .value = "#800000"},        {.key = "saddlebrown", .value = "#8B4513"},
    {.key = "sienna", .value = "#A0522D"},        {.key = "peru", .value = "#CD853F"},
    {.key = "sandybrown", .value = "#F4A460"},    {.key = "burlywood", .value = "#DEB887"},
    {.key = "tan", .value = "#D2B48C"},           {.key = "wheat", .value = "#F5DEB3"},
    {.key = "beige", .value = "#E8E0C8"},         {.key = "ivory", .value = "#FFFFF0"},
    {.key = "linen", .value = "#FAF0E6"},         {.key = "antiquewhite", .value = "#FAEBD7"},
    {.key = "cream", .value = "#F3EBD3"},         {.key = "khaki", .value = "#C3B091"},
    {.key = "yellow", .value = "#E6C840"},        {.key = "gold", .value = "#D4AF37"},
    {.key = "orange", .value = "#E07B39"},        {.key = "coral", .value = "#FF7F50"},
    {.key = "salmon", .value = "#FA8072"},        {.key = "tomato", .value = "#E5533C"},
    {.key = "pink", .value = "#E8B4BE"},          {.key = "rosybrown", .value = "#BC8F8F"},
    {.key = "green", .value = "#4F7A3A"},         {.key = "darkgreen", .value = "#2F5233"},
    {.key = "olive", .value = "#808000"},         {.key = "blue", .value = "#3A5F9A"},
    {.key = "navy", .value = "#1F2E5A"},          {.key = "lightblue", .value = "#ADD8E6"},
    {.key = "skyblue", .value = "#87CEEB"},       {.key = "teal", .value = "#2F7F7F"},
    {.key = "purple", .value = "#6A4A8A"},        {.key = "darkslategray", .value = "#2F4F4F"},
    {.key = "darkslategrey", .value = "#2F4F4F"}, {.key = "mahogany", .value = "#7B3A2A"},
    {.key = "burgundy", .value = "#7A2533"},      {.key = "ochre", .value = "#C8913F"},
    {.key = "lightyellow", .value = "#F2E8B6"},   {.key = "lightgreen", .value = "#B5D3A8"},
    {.key = "lightpink", .value = "#EBCBCB"},     {.key = "terracotta", .value = "#B5643F"},
}};
constexpr std::array<Pair, 11> landuse_kinds = {{
    {.key = "industrial", .value = "industrial"},
    {.key = "port", .value = "industrial"},
    {.key = "railway", .value = "industrial"},
    {.key = "commercial", .value = "office"},
    {.key = "retail", .value = "retail"},
    {.key = "residential", .value = "home"},
    {.key = "farmyard", .value = "shed"},
    {.key = "education", .value = "school"},
    {.key = "religious", .value = "worship"},
    {.key = "military", .value = "bunker"},
    {.key = "static_caravan", .value = "caravan"},
}};
constexpr std::array<std::string_view, 11> no_win = {"bunker", "canopy",     "caravan", "greenhouse",
                                                     "hangar", "industrial", "kiosk",   "parking",
                                                     "shed",   "tank",       "terminal"};
constexpr std::array<std::string_view, 11> sloped_roofs = {"gabled",  "hipped",  "pyramidal",   "skillion",
                                                           "gambrel", "mansard", "half-hipped", "round",
                                                           "dome",    "onion",   "saltbox"};
constexpr std::array<Pair, 22> kind_labels = {{
    {.key = "home", .value = "a home"},
    {.key = "worship", .value = "a place of worship"},
    {.key = "monument", .value = "a historic building"},
    {.key = "office", .value = "an office or public building"},
    {.key = "retail", .value = "a shop building"},
    {.key = "school", .value = "a school"},
    {.key = "hospital", .value = "a hospital"},
    {.key = "hotel", .value = "a hotel"},
    {.key = "industrial", .value = "an industrial hall"},
    {.key = "hangar", .value = "a hangar"},
    {.key = "terminal", .value = "a terminal"},
    {.key = "tower", .value = "a tower"},
    {.key = "tank", .value = "a storage tank"},
    {.key = "parking", .value = "a parking garage"},
    {.key = "greenhouse", .value = "a greenhouse"},
    {.key = "bunker", .value = "a bunker"},
    {.key = "canopy", .value = "a canopy"},
    {.key = "kiosk", .value = "a kiosk"},
    {.key = "caravan", .value = "a static caravan"},
    {.key = "shed", .value = "a shed or garage"},
    {.key = "utility", .value = "a service building"},
    {.key = "sports", .value = "a sports hall"},
}};

template <std::size_t N>
[[nodiscard]] std::optional<std::string_view> at(const std::array<Pair, N>& table,
                                                 std::optional<std::string_view> key) noexcept {
    if (!key) return std::nullopt;
    const auto* const it = std::ranges::find(table, *key, &Pair::key);
    if (it == table.end()) return std::nullopt;
    return it->value;
}

template <std::size_t N>
[[nodiscard]] const Keyed& keyed(const std::array<Keyed, N>& table, std::string_view key) noexcept {
    return *std::ranges::find(table, key, &Keyed::key);
}

template <std::size_t N>
[[nodiscard]] bool in(std::optional<std::string_view> v, const std::array<std::string_view, N>& set) noexcept {
    return v.has_value() && std::ranges::find(set, *v) != set.end();
}

[[nodiscard]] bool in(std::optional<std::string_view> v, std::initializer_list<std::string_view> set) noexcept {
    return v.has_value() && std::ranges::find(set, *v) != set.end();
}

/// Python's `a or b` of strings.
[[nodiscard]] std::optional<std::string_view> either(std::optional<std::string_view> a,
                                                     std::optional<std::string_view> b) noexcept {
    return truthy(a) ? a : b;
}

/// A kind of building: its building=* values and its other tag test (generic.py's KINDS, in its order).
struct Kind {
    std::string_view kind;
    std::initializer_list<std::string_view> values;
    bool (*test)(const Tags&);
};

const std::array<Kind, 22> kinds = {{
    {.kind = "worship",
     .values = {"church", "chapel", "cathedral", "mosque", "synagogue", "temple", "religious", "shrine"},
     .test = [](const Tags& t) { return t.is("amenity", "place_of_worship"); }},
    {.kind = "terminal",
     .values = {"terminal", "airport_terminal", "transportation", "train_station"},
     .test = [](const Tags& t) { return t.is("aeroway", "terminal") || t.is("public_transport", "station"); }},
    {.kind = "tower",
     .values = {"tower"},
     .test = [](const Tags& t) { return t.is("man_made", "tower") || t.is("aeroway", "control_tower"); }},
    {.kind = "tank",
     .values = {"storage_tank", "silo"},
     .test = [](const Tags& t) { return in(t.get("man_made"), {"storage_tank", "silo"}); }},
    {.kind = "hangar", .values = {"hangar"}, .test = [](const Tags&) { return false; }},
    {.kind = "greenhouse", .values = {"greenhouse", "glasshouse"}, .test = [](const Tags&) { return false; }},
    {.kind = "parking", .values = {"parking"}, .test = [](const Tags& t) { return t.is("amenity", "parking"); }},
    {.kind = "bunker", .values = {"bunker"}, .test = [](const Tags& t) { return t.is("military", "bunker"); }},
    {.kind = "canopy", .values = {"roof", "carport"}, .test = [](const Tags&) { return false; }},
    {.kind = "kiosk", .values = {"kiosk"}, .test = [](const Tags&) { return false; }},
    {.kind = "caravan", .values = {"static_caravan"}, .test = [](const Tags&) { return false; }},
    {.kind = "shed",
     .values = {"shed", "garage", "garages", "hut", "cabin", "farm_auxiliary", "barn", "stable", "cowshed",
                "boathouse"},
     .test = [](const Tags&) { return false; }},
    {.kind = "utility",
     .values = {"service", "transformer_tower", "substation", "pumping_station"},
     .test = [](const Tags& t) { return t.is("power", "substation"); }},
    {.kind = "industrial",
     .values = {"industrial", "warehouse", "factory", "manufacture", "storage", "depot"},
     .test = [](const Tags& t) { return t.truthy("industrial"); }},
    {.kind = "school",
     .values = {"school", "university", "college", "kindergarten"},
     .test = [](const Tags& t) { return in(t.get("amenity"), {"school", "university", "college", "kindergarten"}); }},
    {.kind = "hospital",
     .values = {"hospital"},
     .test = [](const Tags& t) { return in(t.get("amenity"), {"hospital", "clinic"}); }},
    {.kind = "hotel", .values = {"hotel"}, .test = [](const Tags& t) { return t.is("tourism", "hotel"); }},
    {.kind = "sports",
     .values = {"sports_hall", "sports_centre", "stadium", "grandstand", "riding_hall"},
     .test = [](const Tags& t) { return in(t.get("leisure"), {"sports_centre", "sports_hall", "stadium"}); }},
    {.kind = "retail",
     .values = {"retail", "supermarket", "commercial_retail"},
     .test = [](const Tags& t) { return t.truthy("shop"); }},
    {.kind = "office",
     .values = {"office", "commercial", "government", "civic", "public", "townhall", "bank", "courthouse", "police",
                "fire_station"},
     .test = [](const Tags& t) { return t.truthy("office"); }},
    {.kind = "monument",
     .values = {"castle", "palace", "museum"},
     .test =
         [](const Tags& t) {
             return t.is("tourism", "museum") || in(t.get("historic"), {"castle", "palace", "building", "monument"});
         }},
    {.kind = "home",
     .values = {"house", "detached", "semidetached_house", "terrace", "bungalow", "residential", "apartments",
                "dormitory", "farm"},
     .test = [](const Tags&) { return false; }},
}};

constexpr std::array<std::string_view, 12> reason_keys = {"amenity", "aeroway", "man_made",         "tourism",
                                                          "office",  "shop",    "historic",         "industrial",
                                                          "power",   "leisure", "public_transport", "military"};

/// generic.py's `kind_of(t, landuse, region, foot, h)`: (kind, reason).
[[nodiscard]] std::pair<std::string_view, std::string> kind_of(const Tags& t, std::optional<std::string_view> landuse,
                                                               std::string_view region, double foot, double h) {
    const std::string_view bt = either(either(t.get("building"), t.get("building:part")), "yes").value_or("yes");
    for (const Kind& k : kinds) {
        const bool listed = std::ranges::find(k.values, bt) != k.values.end();
        if (!listed && !k.test(t)) continue;
        if (listed) return {k.kind, std::format("building={}", bt)};
        for (const std::string_view key : reason_keys) {
            if (t.truthy(key)) return {k.kind, std::format("{}={}", key, t.get(key).value_or(""))};
        }
        return {k.kind, "its tags"};
    }
    if (const auto lk = at(landuse_kinds, landuse)) {
        return {*lk, std::format("landuse={} around it", landuse.value_or(""))};
    }
    if (foot > 2500 && (h != 0.0 ? h : 0.0) < 20) return {"industrial", "its large, low footprint"};
    std::string_view fallback = "home";
    if (region == "port") fallback = "industrial";
    if (region == "schiphol") fallback = "office";
    return {fallback, "what most buildings here are"};
}

struct Look {
    std::string_view wall;
    std::string_view roof;
    std::string_view kind;
    std::string why;
};

/// generic.py's `look(b, region, landuse)`: (wall key, roof key, kind, reason); Paris's stone, elsewhere the Dutch.
[[nodiscard]] Look look(const Building& b, std::string_view region, std::optional<std::string_view> landuse) {
    const Tags& t = b.tags;
    const std::optional<std::int64_t> yr = b.year;
    const bool yr_t = yr.has_value() && *yr != 0;
    const double h = b.h != 0.0 ? b.h : 0.0;
    const auto lv_tag = num(t.get("building:levels"));
    const double lv = lv_tag.has_value() && *lv_tag != 0.0 ? *lv_tag : (h != 0.0 ? h / 3.1 : 2.0);
    auto [kind, why] = kind_of(t, landuse, region, std::fabs(geometry::signed_area(b.poly)), b.h);
    if (kind == "tower" && (in(t.get("tower:type"), {"bell_tower", "church"}) || t.truthy("historic") ||
                            t.truthy("heritage") || (yr_t && *yr < 1900))) {
        kind = t.is("tower:type", "bell_tower") || t.is("building:part", "church") ? "worship" : "monument";
    }
    const bool sloped = in(either(b.roof, t.get("roof:shape")), sloped_roofs);
    const auto shape = t.get("roof:shape");
    const bool tall = h >= 28 || lv >= 9;
    std::string_view wall;
    std::string_view roof;
    if (region == "paris") {
        if (kind == "worship" || kind == "monument") {
            wall = "limestone";
            roof = kind == "worship" ? "lead" : "slate";
        } else if (kind == "home") {
            wall = yr_t && *yr >= 1950 ? "concrete" : (yr_t && *yr < 1830 ? "plaster" : "limestone");
            roof = !yr_t || *yr < 1950 ? "zinc" : "gravel";
        } else if (kind == "office" || kind == "hotel" || kind == "hospital") {
            wall = yr_t && *yr >= 1965 ? "glass" : "limestone";
            roof = wall == "limestone" ? "zinc" : "gravel";
        } else if (kind == "retail") {
            wall = (yr_t && *yr >= 1960) || lv <= 2 ? "glass" : "limestone";
            roof = lv <= 2 ? "glass" : "zinc";
        } else if (kind == "school") {
            wall = "brick_light";
            roof = "slate";
        } else if (kind == "kiosk") {
            wall = "metal_green";
            roof = "zinc";
        } else if (kind == "canopy") {
            wall = "metal_grey";
            roof = "glass";
        } else if (kind == "industrial" || kind == "utility" || kind == "shed") {
            wall = "plaster";
            roof = "zinc";
        } else {
            wall = "limestone";
            roof = "zinc";
        }
        if (in(shape, {"gabled", "hipped"}) && roof == "zinc" && yr_t && *yr < 1800) roof = "slate";
        return {.wall = wall, .roof = roof, .kind = kind, .why = std::move(why)};
    }
    const bool old = yr.has_value() && *yr < 1940;
    const bool post = yr.has_value() && *yr >= 1975;
    if (kind == "home") {
        if (t.is("building", "apartments") && (lv >= 5 || (yr_t && *yr >= 1955 && *yr < 1985))) {
            wall = !post ? "concrete" : "brick_light";
        } else {
            wall = old ? "brick_old" : (post ? "brick_light" : "brick");
        }
        roof = sloped ? (old || !post ? "tiles_red" : "tiles_dark") : "bitumen";
    } else if (kind == "worship") {
        wall = "brick_old";
        roof = "slate";
    } else if (kind == "monument") {
        wall = "brick_old";
        roof = sloped ? "slate" : "bitumen";
    } else if (kind == "office" || kind == "hotel" || kind == "hospital") {
        if (yr_t && *yr < 1960) {
            wall = old ? "brick_old" : "brick"; // an office of its era, before curtain walls
        } else if (tall || (post && lv >= 4) || (!yr.has_value() && lv >= 6) || (region == "schiphol" && h >= 12)) {
            wall = "glass";
        } else {
            wall = !post && region != "schiphol" ? "brick" : "concrete";
        }
        roof = !sloped ? "gravel" : "tiles_dark";
    } else if (kind == "retail") {
        wall = !old ? "concrete" : "brick";
        roof = "bitumen";
    } else if (kind == "school") {
        wall = !old ? "brick_light" : "brick";
        roof = !sloped ? "bitumen" : "tiles_red";
    } else if (kind == "terminal") {
        wall = "glass";
        roof = "metal_light";
    } else if (kind == "hangar") {
        wall = "metal_silver";
        roof = "metal_light";
    } else if (kind == "industrial") {
        wall = !old ? (pymath::mod(b.id, std::int64_t{3}) != 0 ? "metal_grey" : "metal_anthracite") : "brick";
        roof = !old ? "metal_light" : "tiles_red";
    } else if (kind == "tank" || kind == "caravan") {
        wall = "metal_white";
        roof = "metal_light";
    } else if (kind == "tower") {
        wall = "concrete";
        roof = "concrete";
    } else if (kind == "parking" || kind == "bunker") {
        wall = "concrete_dark";
        roof = "concrete";
    } else if (kind == "greenhouse") {
        wall = "glass";
        roof = "glass";
    } else if (kind == "kiosk") {
        wall = "wood";
        roof = "bitumen";
    } else if (kind == "canopy") {
        wall = "metal_grey";
        roof = "metal_grey";
    } else if (kind == "shed") {
        wall = "wood";
        roof = sloped ? "tiles_dark" : "bitumen";
    } else if (kind == "utility") {
        wall = "brick";
        roof = !sloped ? "bitumen" : "tiles_red";
    } else if (kind == "sports") {
        wall = "metal_grey";
        roof = "metal_light";
    } else {
        wall = "brick";
        roof = "bitumen";
    }
    return {.wall = wall, .roof = roof, .kind = kind, .why = std::move(why)};
}

[[nodiscard]] std::string_view kind_label(std::string_view kind) noexcept {
    return at(kind_labels, kind).value_or("a building");
}

[[nodiscard]] int hex_pair(std::string_view s) noexcept {
    int v = 0;
    for (const char c : s) {
        v *= 16;
        if (c >= '0' && c <= '9') v += c - '0';
        if (c >= 'a' && c <= 'f') v += c - 'a' + 10;
        if (c >= 'A' && c <= 'F') v += c - 'A' + 10;
    }
    return v;
}

} // namespace

std::optional<std::string> colour_hex(std::optional<std::string_view> v) {
    // (v or '').strip().lower().replace(' ', '').replace('_', ''): a word beyond ASCII matches neither form nor a name
    const std::u32string cps = text::code_points(v.value_or(""));
    std::size_t a = 0;
    std::size_t z = cps.size();
    while (a < z && is_space(cps[a])) ++a;
    while (z > a && is_space(cps[z - 1])) --z;
    std::string stripped;
    for (const char32_t c : std::span(cps).subspan(a, z - a)) {
        if (c >= 0x80) return std::nullopt;
        stripped.push_back(static_cast<char>(c));
    }
    std::string word;
    for (const char c : text::lowercase(stripped)) {
        if (c != ' ' && c != '_') word.push_back(c);
    }
    const auto hex = [](char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'); };
    const auto upper = [](char c) { return c >= 'a' && c <= 'z' ? static_cast<char>(c - 32) : c; };
    if (word.size() == 7 && word[0] == '#' && std::ranges::all_of(word.substr(1), hex)) {
        std::ranges::transform(word, word.begin(), upper);
        return word;
    }
    if (word.size() == 4 && word[0] == '#' && std::ranges::all_of(word.substr(1), hex)) {
        std::string out = "#";
        for (const char c : word.substr(1)) out.append(2, upper(c));
        return out;
    }
    if (const auto n = at(named, std::optional<std::string_view>(word))) return std::string(*n);
    return std::nullopt;
}

std::string jitter(std::string_view hexcol, std::int64_t seed, double k) {
    pymath::PyRandom r(seed);
    const double f = 1 + r.uniform(-k, k);
    const double w = r.uniform(-k, k) * 0.4;
    const std::array<int, 3> c{hex_pair(hexcol.substr(1, 2)), hex_pair(hexcol.substr(3, 2)),
                               hex_pair(hexcol.substr(5, 2))};
    std::array<std::int64_t, 3> o{};
    for (std::size_t i = 0; i < 3; ++i) {
        const double x = c[i] * f + (i == 0 ? 12 * w : (i == 2 ? -8 * w : 0.0));
        o[i] = std::min<std::int64_t>(255, std::max<std::int64_t>(0, pymath::to_int(x)));
    }
    return std::format("#{:02X}{:02X}{:02X}", o[0], o[1], o[2]);
}

void apply_looks(Scene& s, std::string_view region) {
    struct LandUse {
        std::string_view kind;
        const std::vector<V2>* poly;
        Box bb;
    };
    std::vector<LandUse> lu;
    for (const Land& a : s.land) {
        if (at(landuse_kinds, a.kind)) lu.push_back({.kind = a.kind, .poly = &a.poly, .bb = box_of(a.poly)});
    }
    const auto landuse_at = [&](const Building& b) -> std::optional<std::string_view> {
        const V2 c = geometry::centroid(b.poly);
        for (const LandUse& l : lu) {
            if (l.bb.x0 <= c.x && c.x <= l.bb.x1 && l.bb.z0 <= c.z && c.z <= l.bb.z1 &&
                geometry::pip(c.x, c.z, *l.poly)) {
                return l.kind;
            }
        }
        return std::nullopt;
    };
    for (auto* list : {&s.buildings, &s.parts}) {
        for (Building& b : *list) {
            const Tags& t = b.tags;
            Guessed& g = b.guessed;
            // this replaces the region-wide palette's guess
            for (const Guess k : {Guess::building_colour, Guess::building_material, Guess::roof_colour}) g.remove(k);
            const Look lk = look(b, region, landuse_at(b));
            const Keyed& w = keyed(walls, lk.wall);
            const Keyed& r = keyed(roofs, lk.roof);
            const auto mat = t.get("building:material");
            const auto col = t.get("building:colour");
            const auto rmat = t.get("roof:material");
            const auto rcol = t.get("roof:colour");
            b.looked = true;
            b.kind = lk.kind;
            b.mat_draw = either(mat, w.material);
            if (!truthy(mat)) g.add(Guess::building_material);
            if (truthy(col)) { // a mapped colour is drawn exactly as mapped
                b.col = col;
                const auto ch = colour_hex(col);
                b.col_draw =
                    ch ? *ch
                       : std::string(either(at(mat_draw, mat), at(mat_col, either(mat, w.material)).value_or(w.colour))
                                         .value_or(""));
            } else if (truthy(mat)) { // a mapped material, colour not mapped: as the renderer always drew it
                b.col = either(at(mat_draw, mat), at(mat_col, mat).value_or(w.colour));
                b.col_draw = std::string(b.col.value_or(""));
                g.add(Guess::building_colour);
            } else {
                b.col = w.colour;
                b.col_draw = jitter(w.colour, b.id);
                g.add(Guess::building_colour);
            }
            b.rmat_draw = either(rmat, r.material);
            if (!truthy(rmat)) g.add(Guess::roof_material);
            if (truthy(rcol)) {
                b.rcol = rcol;
                const auto ch = colour_hex(rcol);
                b.rcol_draw = ch ? *ch
                                 : std::string(either(at(mat_draw, rmat),
                                                      at(rmat_col, either(rmat, r.material)).value_or(r.colour))
                                                   .value_or(""));
            } else if (truthy(rmat)) {
                b.rcol = either(at(mat_draw, rmat), at(rmat_col, rmat).value_or(r.colour));
                b.rcol_draw = std::string(b.rcol.value_or(""));
                g.add(Guess::roof_colour);
            } else {
                b.rcol = r.colour;
                b.rcol_draw = jitter(r.colour, b.id + 7, 0.06);
                g.add(Guess::roof_colour);
            }
            b.no_win = in(lk.kind, no_win) || b.mat_draw == std::optional<std::string_view>("glass");
            const bool guessed = g.has(Guess::building_material) || g.has(Guess::building_colour) ||
                                 g.has(Guess::roof_material) || g.has(Guess::roof_colour);
            b.look_why = guessed ? std::optional<std::string>(std::format("{} · {}", kind_label(lk.kind), lk.why))
                                 : std::nullopt;
        }
    }
}

} // namespace atlas::load
