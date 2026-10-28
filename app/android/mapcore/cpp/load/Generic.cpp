#include "load/Generic.hpp"

#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"
#include "geometry/RoofShapes.hpp"
#include "load/Amsterdam.hpp"
#include "load/Real.hpp"
#include "text/Case.hpp"
#include "text/Utf8.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <format>
#include <initializer_list>
#include <span>
#include <string>
#include <unordered_map>
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
/// New looks: the share of homes' guessed gabled roofs in the canal ring pack's red tiles (tiles_red, as its generic
/// looks drew them): about one canal house in four has red tiles in the summer aerial photo (28 October 2026).
constexpr double red_tiles = 0.25;
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

// Paris stone is not one tone: an unmapped limestone front's guessed building:colour is one of these, per building
constexpr std::array<std::string_view, 8> stone_fr = {"#DCD0B6", "#D3C4A6", "#E2D8C3", "#CDC1A8",
                                                      "#D8C8AA", "#C8BCA3", "#E0D2B5", "#D6CCB9"};
constexpr std::array<std::string_view, 12> industrial_like = {"industrial", "hangar",     "tank",    "canopy",
                                                              "parking",    "greenhouse", "bunker",  "caravan",
                                                              "kiosk",      "terminal",   "utility", "sports"};
constexpr std::array<std::string_view, 5> says_kind = {"home", "office", "hotel", "retail", "school"};

/// `(v or '').strip().lower().replace(' ', '').replace('_', '')`, or nothing for a word beyond ASCII, which matches
/// neither a hex form nor a name.
[[nodiscard]] std::optional<std::string> colour_word(std::optional<std::string_view> v) {
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
    return word;
}

/// `x or 0` of an optional float: 0 where None or zero.
[[nodiscard]] double or0(std::optional<double> v) noexcept { return v.has_value() && *v != 0.0 ? *v : 0.0; }

/// One cell of a grid of `cell`-metre squares, as `(int(x // cell), int(z // cell))`.
[[nodiscard]] std::uint64_t cell_key(std::int64_t i, std::int64_t j) noexcept {
    return (static_cast<std::uint64_t>(static_cast<std::uint32_t>(i)) << 32U) | static_cast<std::uint32_t>(j);
}

[[nodiscard]] std::int64_t cell_of(double v, double cell) { return pymath::to_int(pymath::floor_div(v, cell)); }

/// generic.py's `pick(options, seed)`: one option by weight, from random.Random(seed).
template <class T>
[[nodiscard]] const T& pick(const std::vector<T>& options, std::int64_t seed) {
    std::vector<double> weights;
    weights.reserve(options.size());
    for (const T& o : options) weights.push_back(o.weight);
    const double r = pymath::PyRandom(seed).uniform(0.0, pymath::sum(weights));
    double acc = 0.0;
    for (const T& o : options) {
        acc += o.weight;
        if (r <= acc) return o;
    }
    return options.back();
}

/// generic.py's `trad_entry(table, btype, kind, modern, yr)`: the first of `modern`, the building type, the kind and
/// `default` the table has an entry for that is not None in the building's era.
template <class T>
[[nodiscard]] const T* trad_entry(const EraTable<T>& table, std::string_view btype, std::string_view kind, bool modern,
                                  std::optional<std::int64_t> yr) {
    std::array<std::string_view, 4> keys{};
    std::size_t n = 0;
    if (modern) keys.at(n++) = "modern";
    keys.at(n++) = btype;
    keys.at(n++) = kind;
    keys.at(n++) = "default";
    for (const std::string_view k : std::span(keys).first(n)) {
        if (const Era<T>* e = Tradition::entry(table, k)) {
            if (const std::optional<T>& v = e->at(yr)) return &*v;
        }
    }
    return nullptr;
}

[[nodiscard]] bool listed(const std::vector<std::string_view>& list, std::string_view v) noexcept {
    return std::ranges::find(list, v) != list.end();
}

/// generic.py's `fits(when, b, kind, md, short)`: does a rule's `when` hold for this building?
[[nodiscard]] bool fits(const RuleWhen& w, const Building& b, std::string_view kind, std::string_view md, double shrt) {
    const std::string_view bt = either(b.tags.get("building"), "yes").value_or("yes");
    if (w.kinds && !listed(*w.kinds, kind)) return false;
    if (w.buildings && !listed(*w.buildings, bt)) return false;
    if (w.materials && !listed(*w.materials, md)) return false;
    if (w.year) {
        if (!b.year) {
            if (!w.year_unknown) return false;
        } else if (static_cast<double>(*b.year) < w.year_from || static_cast<double>(*b.year) >= w.year_to) {
            return false;
        }
    }
    const auto lv = static_cast<double>(b.levels);
    if (w.levels && (lv < w.levels->first || lv > w.levels->second)) return false;
    const double ar = std::fabs(geometry::signed_area(b.poly));
    if (w.area && (ar < w.area->first || ar > w.area->second)) return false;
    if (w.narrow && shrt >= *w.narrow) return false;
    if (w.min_h && or0(b.h) <= *w.min_h) return false;
    return true;
}

/// The land use a building stands in (generic.py's `landuse_at`): the first ring of the list that holds its centroid
/// (regions.py's tests the ring's box first; amsterdam.py's the ring alone).
class LandUses {
public:
    LandUses(const Scene& s, LandUseFrom from) : boxed_(from == LandUseFrom::land) {
        if (from == LandUseFrom::land) {
            for (const Land& a : s.land) {
                if (at(landuse_kinds, a.kind)) uses_.push_back({.kind = a.kind, .poly = &a.poly, .bb = box_of(a.poly)});
            }
            return;
        }
        // amsterdam.py's: the areas tagged with a land use generic.py knows, of more than two points
        for (const Area& a : s.areas) {
            const auto lu = a.tags.get("landuse");
            if (lu && at(landuse_kinds, lu) && a.poly.size() > 2) {
                uses_.push_back({.kind = *lu, .poly = &a.poly, .bb = box_of(a.poly)});
            }
        }
    }

    [[nodiscard]] std::optional<std::string_view> at_building(const Building& b) const {
        const V2 c = geometry::centroid(b.poly);
        for (const Use& u : uses_) {
            const bool in_box = !boxed_ || (u.bb.x0 <= c.x && c.x <= u.bb.x1 && u.bb.z0 <= c.z && c.z <= u.bb.z1);
            if (in_box && geometry::pip(c.x, c.z, *u.poly)) return u.kind;
        }
        return std::nullopt;
    }

private:
    struct Use {
        std::string_view kind;
        const std::vector<V2>* poly;
        Box bb;
    };
    std::vector<Use> uses_;
    bool boxed_;
};

/// generic.py's `paris_roof(b, kind, wall, g)`: a Paris stone front of four to nine floors, built before 1940 or of
/// unknown age, with no roof shape mapped, gets a mansard (guessed): one roof storey of zinc above its levels.
bool paris_roof(Building& b, std::string_view kind, std::string_view wall, Guessed& g) {
    const Tags& t = b.tags;
    const auto yr = b.year;
    if (t.has("building:part") || !g.has(Guess::roof_shape) || (b.roof && *b.roof != "flat")) return false;
    if (!in(kind, {"home", "office", "hotel", "retail", "hospital", "school", "monument"}) ||
        (wall != "limestone" && wall != "plaster")) {
        return false;
    }
    if (yr && *yr != 0 && (*yr < 1760 || *yr >= 1940)) return false;
    if (b.levels < 4 || b.levels > 9 || std::fabs(geometry::signed_area(b.poly)) < 80) return false;
    const auto rl = num(t.get("roof:levels"));
    b.roof = "mansard";
    b.roof_h = rl && *rl != 0.0 ? *rl * 3.0 : 3.4;
    g.add(Guess::roof_height);
    if (b.src == HeightSource::height) {
        if (!t.truthy("building:levels")) {
            b.levels = static_cast<std::int32_t>(std::max<std::int64_t>(1, pymath::round((b.h - *b.roof_h) / 3.1)));
        }
    } else {
        b.h = (b.levels * 3.0) + *b.roof_h;
    }
    return true;
}

/// The mapped colour, material or the guessed value of a wall, as generic.py sets `col` and `col_draw` (the tradition's
/// path paints a colour word; the generic one takes it exact).
void wall_colours(Building& b, Guessed& g, std::string_view wm, std::string_view wc, bool tradition) {
    const Tags& t = b.tags;
    const auto mat = t.get("building:material");
    const auto col = t.get("building:colour");
    if (truthy(col)) { // a mapped colour is drawn as mapped
        b.col = col;
        const auto ch = tradition ? paint(col) : colour_hex(col);
        b.col_draw =
            ch ? *ch : std::string(either(at(mat_draw, mat), at(mat_col, either(mat, wm)).value_or(wc)).value_or(""));
    } else if (truthy(mat)) { // a mapped material, colour not mapped: as the renderer always drew it
        b.col = either(at(mat_draw, mat), at(mat_col, mat).value_or(wc));
        b.col_draw = std::string(b.col.value_or(""));
        g.add(Guess::building_colour);
    } else {
        b.col = wc;
        b.col_draw = jitter(wc, b.id, tradition ? 0.035 : 0.05);
        g.add(Guess::building_colour);
    }
}

/// The roof's: as wall_colours, with the roof's tables.
void roof_colours(Building& b, Guessed& g, std::string_view rm, std::string_view rc, double k) {
    const Tags& t = b.tags;
    const auto rmat = t.get("roof:material");
    const auto rcol = t.get("roof:colour");
    b.rmat_draw = either(rmat, rm);
    if (!truthy(rmat)) g.add(Guess::roof_material);
    if (truthy(rcol)) {
        b.rcol = rcol;
        const auto ch = colour_hex(rcol);
        b.rcol_draw =
            ch ? *ch
               : std::string(either(at(mat_draw, rmat), at(rmat_col, either(rmat, rm)).value_or(rc)).value_or(""));
    } else if (truthy(rmat)) {
        b.rcol = either(at(mat_draw, rmat), at(rmat_col, rmat).value_or(rc));
        b.rcol_draw = std::string(b.rcol.value_or(""));
        g.add(Guess::roof_colour);
    } else {
        b.rcol = rc;
        b.rcol_draw = jitter(rc, b.id + 7, k);
        g.add(Guess::roof_colour);
    }
}

/// generic.py's `trad_look(b, P, kind, why, g, region)`: the tradition's look for one building whose walls or roof are
/// not mapped; false where the tradition says nothing about its kind.
bool trad_look(Building& b, const Tradition& p, std::string_view kind, const std::string& why, Guessed& g) {
    const Tags& t = b.tags;
    const std::string_view bt = either(either(t.get("building"), t.get("building:part")), "yes").value_or("yes");
    const auto yr = b.year;
    const bool modern = yr && *yr != 0 && static_cast<double>(*yr) >= p.modern_from;
    const std::int64_t seed = b.parent_id.value_or(b.id);
    const std::vector<WallOption>* const wall_options = trad_entry(p.walls, bt, kind, modern, yr);
    if (wall_options == nullptr || (in(kind, industrial_like) && Tradition::entry(p.walls, kind) == nullptr &&
                                    Tradition::entry(p.walls, bt) == nullptr)) {
        return false;
    }
    const bool part = t.has("building:part");
    const std::vector<V2>& poly = b.poly;
    double longest = 0.0;
    for (std::size_t i = 0; i < poly.size(); ++i) {
        const double e = geometry::dist(poly[i], poly[(i + 1) % poly.size()]);
        if (i == 0 || e > longest) longest = e;
    }
    const double area = std::fabs(geometry::signed_area(poly));
    // the footprint's width across its longest side
    const double shrt = pymath::min(longest, pymath::max(3.0, area / pymath::max(longest, 1.0)));
    // height: the tradition's usual floors, where neither height nor levels is mapped and no mapped neighbours say
    // otherwise
    if (g.has(Guess::height) && !b.lv_why && !part) {
        const auto height = std::ranges::find(p.heights, kind, &std::pair<std::string_view, double>::first);
        if (height != p.heights.end() && (kind != "tower" || area <= 160)) { // a tower: its usual height here
            b.h = height->second;
            b.levels = static_cast<std::int32_t>(std::max<std::int64_t>(1, pymath::to_int(b.h / p.floor_h)));
            b.lv_why = "its tradition";
        } else {
            const auto level = [&](std::string_view key) {
                const auto it = std::ranges::find(p.levels, key, &std::pair<std::string_view, std::int64_t>::first);
                return it != p.levels.end() ? std::optional(it->second) : std::nullopt;
            };
            const std::int64_t fallback = level("default").value_or(0); // read_tradition requires it
            const std::int64_t lv = level(bt).value_or(level(kind).value_or(fallback));
            b.levels = static_cast<std::int32_t>(lv);
            b.h = (static_cast<double>(lv) * p.floor_h) + or0(b.roof_h);
            g.add(Guess::building_levels);
            b.lv_why = "its tradition";
        }
    }
    const auto mat = t.get("building:material");
    const WallOption& w = pick(*wall_options, (seed * 13) + 3);
    const std::string_view md = either(mat, w.material).value_or("");
    // roof: a rule of the tradition (a Paris mansard, a canal house's gable), else a shape from its roofs, with its
    // pitch
    const RoofEntry* rf = trad_entry(p.roof, bt, kind, modern, yr);
    if (rf == nullptr) {
        // `default`, which read_tradition proved there and giving a roof at every age
        const Era<RoofEntry>* const fallback = Tradition::entry(p.roof, "default");
        if (fallback == nullptr) return false;
        const std::optional<RoofEntry>& entry = fallback->at(yr);
        if (!entry) return false;
        rf = &*entry;
    }
    const RuleRoof* rule = nullptr;
    std::optional<double> pitch = rf->pitch;
    std::string_view rm = rf->material;
    const std::vector<std::string_view>* colours = &rf->colours;
    if (g.has(Guess::roof_shape) && !part) {
        for (const Rule& r : p.rules) {
            if (fits(r.when, b, kind, md, shrt)) {
                rule = &r.roof;
                break;
            }
        }
        if (rule != nullptr) {
            if (rule->material) rm = *rule->material;
            if (rule->colours) colours = &*rule->colours;
            if (rule->pitch) pitch = rule->pitch;
        }
        const std::string_view shape = rule != nullptr ? rule->shape : pick(rf->shapes, (seed * 7) + 1).shape;
        const double old_rh = or0(b.roof_h);
        b.roof = shape;
        if (shape == "gabled" || shape == "hipped") {
            const auto [lo, hi] = rule != nullptr && rule->roof_h_range ? *rule->roof_h_range : std::pair(1.4, 7.0);
            b.roof_h = pymath::round(pymath::max(lo, pymath::min(hi, pitch.value_or(0.5) * shrt / 2)), 1);
            g.add(Guess::roof_height);
            if (rule != nullptr && rule->orient) {
                b.orient = *rule->orient;
            } else if (rf->terrace_across && shrt < 8 && longest > 1.4 * shrt) {
                b.orient = "across"; // the ridge along the street
                g.add(Guess::roof_orientation);
            } else {
                b.orient = either(b.orient, "along");
            }
        } else if (shape == "skillion") {
            b.roof_h = pymath::round(pymath::max(0.8, pymath::min(3.0, pitch.value_or(0.3) * shrt * 0.5)), 1);
            g.add(Guess::roof_height);
            g.add(Guess::roof_direction);
        } else if (shape == "mansard") {
            const auto rl = num(t.get("roof:levels"));
            b.roof_h = rl && *rl != 0.0 ? *rl * p.floor_h : (rule != nullptr ? rule->roof_h.value_or(3.4) : 3.4);
            g.add(Guess::roof_height);
        } else {
            b.roof_h = std::nullopt;
        }
        const double rh = or0(b.roof_h);
        if (b.src == HeightSource::height) {
            if (!t.truthy("building:levels")) {
                b.levels = static_cast<std::int32_t>(std::max<std::int64_t>(1, pymath::round((b.h - rh) / p.floor_h)));
            }
        } else if (g.has(Guess::height) || b.src == HeightSource::levels) {
            b.h = (b.levels * p.floor_h) + rh;
        } else {
            b.h = (b.h - old_rh) + rh;
        }
    }
    pymath::PyRandom roof_colour((seed * 17) + 5);
    const std::string_view rc = roof_colour.choice(std::span(*colours));
    b.look = LookPath::tradition;
    b.kind = kind;
    b.mat_draw = md;
    if (!truthy(mat)) g.add(Guess::building_material);
    wall_colours(b, g, w.material, w.colour, true);
    roof_colours(b, g, rm, rc, 0.05);
    // the tradition's details, generic (no tag gives them): windows, shutters, eaves, boards, a stucco ground floor, a
    // cornice, chimneys
    pymath::PyRandom chance((seed * 31) + 11);
    const bool homeish = in(kind, says_kind) ||
                         in(bt, {"apartments", "house", "terrace", "residential", "detached", "semidetached_house"});
    TradDetails& d = b.trad;
    const bool window_kind =
        listed(p.window_kinds, kind) || listed(p.window_kinds, bt) || listed(p.window_kinds, "default");
    if (!p.windows.is_null() && !modern && window_kind && !in(kind, industrial_like) &&
        (!p.window_materials || listed(*p.window_materials, md))) {
        d.win = p.windows;
    }
    if (p.shutters && !modern && listed(p.shutters->kinds, kind) && chance.random() < p.shutters->share) {
        d.shutter = chance.choice(std::span(p.shutters->colours));
    }
    d.eaves = b.roof == std::optional<std::string_view>("gabled") || b.roof == std::optional<std::string_view>("hipped")
                  ? p.eaves
                  : 0.0;
    if (!p.boards.is_null() && listed(p.board_materials, md)) d.boards = p.boards;
    if (!p.base_band.is_null() && listed(p.band_materials, md) && !modern && homeish) d.band = p.base_band;
    d.cornice = p.cornice.holds(yr) && !modern && homeish;
    d.hoist = p.hoist.holds(yr) && b.roof == std::optional<std::string_view>("gabled");
    if (!p.chimneys.is_null() && !part && homeish && !modern && chance.random() < p.chimney_share)
        d.chimney = p.chimneys;
    d.crenel = p.towers_crenellated && kind == "tower";
    b.no_win = in(kind, no_win) || md == "glass";
    std::string label;
    if (homeish) {
        const auto by = std::ranges::find(p.says_by, md, &std::pair<std::string_view, std::string_view>::first);
        label = by != p.says_by.end() ? by->second : p.says;
    } else {
        label = std::format("{} in the {} tradition", kind_label(kind), p.name);
    }
    const bool guessed = g.has(Guess::building_material) || g.has(Guess::building_colour) ||
                         g.has(Guess::roof_material) || g.has(Guess::roof_colour) || g.has(Guess::roof_shape);
    b.look_why = guessed ? std::optional<std::string>(std::format("{} · {}", label, why)) : std::nullopt;
    if (rule != nullptr && b.roof == std::optional<std::string_view>("mansard")) b.roof_why = "mansard";
    return true;
}

/// The generic look by kind and age (generic.py's apply, after a tradition said nothing).
void generic_look(Building& b, std::string_view region, std::optional<std::string_view> landuse) {
    Guessed& g = b.guessed;
    const Look lk = look(b, region, landuse);
    const Keyed& w = keyed(walls, lk.wall);
    const Keyed* r = &keyed(roofs, lk.roof);
    std::string_view wc = w.colour;
    if (region == "paris") {
        if (lk.wall == "limestone") {
            wc = stone_fr.at(static_cast<std::size_t>(pymath::mod(b.parent_id.value_or(b.id), std::int64_t{8})));
        }
        if (paris_roof(b, lk.kind, lk.wall, g)) r = &keyed(roofs, "zinc");
    }
    const auto mat = b.tags.get("building:material");
    b.look = LookPath::generic;
    b.kind = lk.kind;
    b.mat_draw = either(mat, w.material);
    if (!truthy(mat)) g.add(Guess::building_material);
    wall_colours(b, g, w.material, wc, false);
    roof_colours(b, g, r->material, r->colour, 0.06);
    // halls, tanks and sheds show no window rows; glass walls are drawn as curtain walls
    b.no_win = in(lk.kind, no_win) || b.mat_draw == std::optional<std::string_view>("glass");
    const bool guessed = g.has(Guess::building_material) || g.has(Guess::building_colour) ||
                         g.has(Guess::roof_material) || g.has(Guess::roof_colour);
    b.look_why =
        guessed ? std::optional<std::string>(std::format("{} · {}", kind_label(lk.kind), lk.why)) : std::nullopt;
}

} // namespace

std::optional<std::string> colour_hex(std::optional<std::string_view> v) {
    std::optional<std::string> word = colour_word(v);
    if (!word) return std::nullopt;
    const auto hex = [](char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'); };
    const auto upper = [](char c) { return c >= 'a' && c <= 'z' ? static_cast<char>(c - 32) : c; };
    if (word->size() == 7 && (*word)[0] == '#' && std::ranges::all_of(word->substr(1), hex)) {
        std::ranges::transform(*word, word->begin(), upper);
        return word;
    }
    if (word->size() == 4 && (*word)[0] == '#' && std::ranges::all_of(word->substr(1), hex)) {
        std::string out = "#";
        for (const char c : word->substr(1)) out.append(2, upper(c));
        return out;
    }
    if (const auto n = at(named, std::optional<std::string_view>(*word))) return std::string(*n);
    return std::nullopt;
}

std::optional<std::string> paint(std::optional<std::string_view> v, double k) {
    std::optional<std::string> h = colour_hex(v);
    if (!h) return h;
    const std::optional<std::string> w = colour_word(v); // a word colour_hex understood is ASCII
    if (!w || w->starts_with('#') || *w == "white" || *w == "black") return h;
    const std::array<int, 3> c{hex_pair(h->substr(1, 2)), hex_pair(h->substr(3, 2)), hex_pair(h->substr(5, 2))};
    constexpr std::array<int, 3> warm{244, 240, 232};
    std::array<std::int64_t, 3> o{};
    for (std::size_t i = 0; i < 3; ++i) {
        o.at(i) = pymath::to_int(c.at(i) + (static_cast<double>(warm.at(i) - c.at(i)) * k));
    }
    return std::format("#{:02X}{:02X}{:02X}", o[0], o[1], o[2]);
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

std::size_t neighbour_levels(Scene& s, double r) {
    struct Mapped {
        double x;
        double z;
        std::int32_t lv;
    };
    std::unordered_map<std::uint64_t, std::vector<Mapped>> grid;
    for (const Building& b : s.buildings) {
        const Tags& t = b.tags;
        if (t.is("man_made", "tower") || in(t.get("building"), {"tower", "church", "cathedral", "chapel", "bell_tower",
                                                                "campanile", "transformer_tower"})) {
            continue; // not like a house
        }
        if (b.src != HeightSource::none && b.levels >= 1) {
            const V2 c = geometry::centroid(b.poly);
            grid[cell_key(cell_of(c.x, r), cell_of(c.z, r))].push_back({.x = c.x, .z = c.z, .lv = b.levels});
        }
    }
    std::size_t n = 0;
    std::vector<std::int32_t> near;
    for (Building& b : s.buildings) {
        if (b.src != HeightSource::none || !b.guessed.has(Guess::height) ||
            in(b.tags.get("building"), {"roof", "kiosk", "shed", "garage", "hut", "houseboat"})) {
            continue;
        }
        const V2 c = geometry::centroid(b.poly);
        const std::int64_t i = cell_of(c.x, r);
        const std::int64_t j = cell_of(c.z, r);
        near.clear();
        for (std::int64_t di = -1; di <= 1; ++di) {
            for (std::int64_t dj = -1; dj <= 1; ++dj) {
                const auto it = grid.find(cell_key(i + di, j + dj));
                if (it == grid.end()) continue;
                for (const Mapped& m : it->second) {
                    if (pymath::hypot(m.x - c.x, m.z - c.z) < r) near.push_back(m.lv);
                }
            }
        }
        if (near.size() < 3) continue;
        std::ranges::sort(near);
        const std::int32_t lv = near[near.size() / 2];
        b.levels = lv;
        b.h = (lv * 3.0) + or0(b.roof_h);
        b.guessed.add(Guess::building_levels);
        b.lv_why = "the buildings around it";
        ++n;
    }
    return n;
}

void party_walls(Scene& s, double tol) {
    constexpr double cell = 12.0;
    struct Edge {
        std::uint32_t k; ///< its building, among `bs`
        V2 a;
        V2 c;
    };
    std::vector<Building*> bs;
    for (Building& b : s.buildings) {
        if (!b.hidden) bs.push_back(&b);
    }
    std::unordered_map<std::uint64_t, std::vector<Edge>> grid;
    for (std::size_t k = 0; k < bs.size(); ++k) {
        const std::vector<V2>& p = bs[k]->poly;
        for (std::size_t i = 0; i < p.size(); ++i) {
            const V2 a = p[i];
            const V2 c = p[(i + 1) % p.size()];
            if (geometry::dist(a, c) < 1.5) continue;
            const double mx = (a.x + c.x) / 2;
            const double mz = (a.z + c.z) / 2;
            grid[cell_key(cell_of(mx, cell), cell_of(mz, cell))].push_back(
                {.k = static_cast<std::uint32_t>(k), .a = a, .c = c});
        }
    }
    for (std::size_t k = 0; k < bs.size(); ++k) {
        const std::vector<V2>& p = bs[k]->poly;
        std::vector<std::array<V2, 2>> out;
        for (std::size_t i = 0; i < p.size(); ++i) {
            const V2 a = p[i];
            const V2 c = p[(i + 1) % p.size()];
            const double len = geometry::dist(a, c);
            if (len < 1.5) continue;
            const double mx = (a.x + c.x) / 2;
            const double mz = (a.z + c.z) / 2;
            const double ux = (c.x - a.x) / len;
            const double uz = (c.z - a.z) / len;
            const std::int64_t gx = cell_of(mx, cell);
            const std::int64_t gz = cell_of(mz, cell);
            bool hit = false;
            for (std::int64_t dx = -1; dx <= 1 && !hit; ++dx) {
                for (std::int64_t dz = -1; dz <= 1 && !hit; ++dz) {
                    const auto it = grid.find(cell_key(gx + dx, gz + dz));
                    if (it == grid.end()) continue;
                    for (const Edge& e : it->second) {
                        if (e.k == k) continue;
                        const double len2 = geometry::dist(e.a, e.c);
                        const double vx = (e.c.x - e.a.x) / len2;
                        const double vz = (e.c.z - e.a.z) / len2;
                        if (std::fabs((ux * vz) - (uz * vx)) > 0.15) continue;
                        const double tt = ((mx - e.a.x) * vx) + ((mz - e.a.z) * vz);
                        if (-0.5 < tt && tt < len2 + 0.5 &&
                            std::fabs(((mx - e.a.x) * vz) - ((mz - e.a.z) * vx)) < tol) {
                            hit = true;
                            break;
                        }
                    }
                }
            }
            if (hit) out.push_back({a, c});
        }
        bs[k]->party = std::move(out);
    }
}

void apply_looks(Scene& s, const Looks& looks) {
    s.tradition = looks.tradition;
    const Tradition* const p = looks.tradition.get();
    if (looks.region == "paris" || p != nullptr) neighbour_levels(s);
    if (p != nullptr && p->party_chimneys) party_walls(s);
    const LandUses landuse(s, looks.landuse);
    for (auto* list : {&s.buildings, &s.parts}) {
        for (Building& b : *list) {
            const Tags& t = b.tags;
            Guessed& g = b.guessed;
            if (p != nullptr && t.is("building", "houseboat")) continue; // a houseboat keeps its own look (a boat)
            if (looks.keep_homes && !t.truthy("building:material") && !t.truthy("building:colour")) {
                const double area = std::fabs(geometry::signed_area(b.poly));
                auto [kind, why] = kind_of(t, landuse.at_building(b), looks.region, area, b.h);
                const bool townhouse = in(kind, {"office", "retail", "hotel", "school"}) &&
                                       ((b.year && *b.year != 0 ? *b.year : 9999) < 1900 || area < 300);
                // a canal house used as a shop or office keeps the canal houses' brick
                if (townhouse) why = std::format("an old town house, {}", why);
                if (kind == "home" || townhouse) {
                    b.look = LookPath::home;
                    b.kind = kind;
                    if (!t.truthy("roof:material")) {
                        b.rmat_draw = in(b.roof, sloped_roofs) ? "roof_tiles" : "tar_paper";
                        g.add(Guess::roof_material);
                    } else {
                        b.rmat_draw = t.get("roof:material");
                    }
                    b.look_why = std::format("{} · {}", kind_label(kind), why);
                    if (b.roof == "gabled" && g.has(Guess::roof_colour) &&
                        pymath::PyRandom((b.id * 31) + 5).random() < red_tiles) {
                        b.rcol = keyed(roofs, "tiles_red").colour; // the canal ring pack's red
                        b.rcol_draw = jitter(*b.rcol, b.id + 7, 0.06);
                    }
                    continue;
                }
            }
            // this replaces the region-wide palette's guess
            for (const Guess k : {Guess::building_colour, Guess::building_material, Guess::roof_colour}) g.remove(k);
            const std::optional<std::string_view> lu = landuse.at_building(b);
            if (p != nullptr) {
                const auto [kind, why] = kind_of(t, lu, looks.region, std::fabs(geometry::signed_area(b.poly)), b.h);
                if (trad_look(b, *p, kind, why, g)) continue;
            }
            generic_look(b, looks.region, lu);
        }
    }
}

} // namespace atlas::load
