// The renders' tables stage P derives from and every pack's `style` carries (rendertiles' Style: `material`, `clay`,
// `road_order`, `aero_order`): amsterdam.py's MATERIAL and CLAY, its ROAD_ORDER, and regions.py's draw_over order of
// the aeroways. The cut (load/Cut.hpp) derives with them and the pack's style (load/PackStyle.hpp) writes them, so a
// pack carries what its tiles were derived with. :rendertiles' RenderTablesTest holds the packer's copies to the
// port's, and the core's style of the bundled packs is theirs byte for byte (pack_style_test).
//
// Then the tag tables stage P's loaders choose a feature's kind and class by (amsterdam.py's and regions.py's): the
// land cover's kinds and their draw order, the road classes and their widths, the big roads, railways, aeroways and
// water the regions take, and the tunnels they leave out. The world's profile (the packer's `world`, ENGINE.md step 5)
// reads them as data from mapcore/style/tags.json (load/Tags.hpp writes it, tags_test holds the file to these), so a
// field is the same kind on both sides of a country's edge.
#pragma once

#include <array>
#include <cstdint>
#include <string_view>

namespace atlas::load {

/// A building material and its colour.
struct Material {
    std::string_view name;
    std::string_view col;
};

/// amsterdam.py's MATERIAL, in its order.
inline constexpr std::array<Material, 11> materials = {{
    {.name = "brick", .col = "#9C5A45"},
    {.name = "stone", .col = "#CFC6B4"},
    {.name = "copper", .col = "#5E9E86"},
    {.name = "glass", .col = "#A9C2D6"},
    {.name = "concrete", .col = "#BDBAB2"},
    {.name = "wood", .col = "#9C7A55"},
    {.name = "roof_tiles", .col = "#A5553C"},
    {.name = "slate", .col = "#56606A"},
    {.name = "metal", .col = "#9AA3AB"},
    {.name = "plaster", .col = "#E6E0D4"},
    {.name = "sandstone", .col = "#D8C49A"},
}};

/// amsterdam.py's CLAY: a wall or roof with nothing mapped or drawn, and hx(CLAY).
inline constexpr std::string_view clay = "#E4E0D8";
inline constexpr std::uint32_t clay_rgb = 0xE4E0D8;

/// amsterdam.py's ROAD_ORDER: the road classes in drawing order.
inline constexpr std::array<std::string_view, 12> road_order = {
    "path",          "footway",      "steps",       "cycleway", "service",  "pedestrian",
    "living_street", "unclassified", "residential", "busway",   "tertiary", "secondary"};

/// regions.py's draw_over: runways, taxiways and stands by this order (GroundKeys.AERO_ORDER), in its order.
struct AeroOrder {
    std::string_view kind;
    int order;
};
inline constexpr std::array<AeroOrder, 5> aero_order = {{
    {.kind = "parking_position", .order = 3},
    {.kind = "taxilane", .order = 1},
    {.kind = "taxiway", .order = 1},
    {.kind = "stopway", .order = 0},
    {.kind = "runway", .order = 2},
}};

/// regions.py's LAND: each kind of land cover and its draw order (the colours are the style's), in its order.
struct LandKind {
    std::string_view kind;
    int order;
};
inline constexpr std::array<LandKind, 43> land_kinds = {{
    {.kind = "farmland", .order = 0},
    {.kind = "residential", .order = 0},
    {.kind = "industrial", .order = 0},
    {.kind = "port", .order = 1},
    {.kind = "commercial", .order = 0},
    {.kind = "retail", .order = 0},
    {.kind = "construction", .order = 0},
    {.kind = "railway", .order = 0},
    {.kind = "military", .order = 0},
    {.kind = "brownfield", .order = 0},
    {.kind = "greenfield", .order = 0},
    {.kind = "education", .order = 0},
    {.kind = "static_caravan", .order = 1},
    {.kind = "farmyard", .order = 1},
    {.kind = "meadow", .order = 1},
    {.kind = "grass", .order = 2},
    {.kind = "grassland", .order = 2},
    {.kind = "village_green", .order = 2},
    {.kind = "recreation_ground", .order = 2},
    {.kind = "park", .order = 2},
    {.kind = "garden", .order = 2},
    {.kind = "cemetery", .order = 2},
    {.kind = "allotments", .order = 2},
    {.kind = "orchard", .order = 2},
    {.kind = "golf_course", .order = 2},
    {.kind = "heath", .order = 2},
    {.kind = "scrub", .order = 3},
    {.kind = "forest", .order = 3},
    {.kind = "wood", .order = 3},
    {.kind = "wetland", .order = 3},
    {.kind = "sand", .order = 4},
    {.kind = "beach", .order = 4},
    {.kind = "dune", .order = 4},
    {.kind = "bare_rock", .order = 4},
    {.kind = "stone", .order = 4},
    {.kind = "scree", .order = 4},
    {.kind = "apron", .order = 5},
    {.kind = "parking", .order = 5},
    {.kind = "pedestrian", .order = 5},
    {.kind = "playground", .order = 5},
    {.kind = "pitch", .order = 5},
    {.kind = "track", .order = 5},
    {.kind = "flowerbed", .order = 5},
}};

/// regions.py's `land_kind(t)`: the leisure values that are land cover of their own kind.
inline constexpr std::array<std::string_view, 6> land_leisure = {
    "pitch", "park", "garden", "golf_course", "recreation_ground", "track"};

/// A road class amsterdam.py draws, and its width (its `W`), in its order.
struct RoadClass {
    std::string_view hw;
    double w;
};
inline constexpr std::array<RoadClass, 12> road_classes = {{
    {.hw = "tertiary", .w = 7.5},
    {.hw = "secondary", .w = 9.0},
    {.hw = "residential", .w = 5.5},
    {.hw = "unclassified", .w = 5.0},
    {.hw = "living_street", .w = 4.5},
    {.hw = "pedestrian", .w = 5.0},
    {.hw = "busway", .w = 6.0},
    {.hw = "service", .w = 3.5},
    {.hw = "cycleway", .w = 2.4},
    {.hw = "footway", .w = 2.0},
    {.hw = "path", .w = 1.6},
    {.hw = "steps", .w = 2.0},
}};

/// regions.py's big roads (`S['big']`): the highway classes the regions draw over the ground, in its order.
inline constexpr std::array<std::string_view, 7> big_roads = {
    "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary_link"};

/// regions.py's railways (`S['rail']`, beside the crane rails), in its order.
inline constexpr std::array<std::string_view, 4> rail_kinds = {"rail", "light_rail", "subway", "narrow_gauge"};

/// regions.py's aeroways drawn over the ground (`S['aero']`), in its order (aero_order sorts them).
inline constexpr std::array<std::string_view, 5> aero_kinds = {"runway", "taxiway", "taxilane", "parking_position",
                                                               "stopway"};

/// amsterdam.py's water besides natural=water: the waterway values mapped as areas, in its order.
inline constexpr std::array<std::string_view, 3> water_ways = {"canal", "riverbank", "dock"};

/// regions.py's `underground`: the tunnel values (besides a layer below 0) that leave a big road or a railway out.
inline constexpr std::array<std::string_view, 2> tunnels = {"yes", "building_passage"};

} // namespace atlas::load
