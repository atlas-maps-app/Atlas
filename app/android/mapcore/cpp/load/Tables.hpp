// The renders' tables stage P derives from and every pack's `style` carries (rendertiles' Style: `material`, `clay`,
// `road_order`, `aero_order`): amsterdam.py's MATERIAL and CLAY, its ROAD_ORDER, and regions.py's draw_over order of
// the aeroways. The cut (load/Cut.hpp) derives with them and the pack's style (load/PackStyle.hpp) writes them, so a
// pack carries what its tiles were derived with. :rendertiles' RenderTablesTest holds the packer's copies to the
// port's, and the core's style of the bundled packs is theirs byte for byte (pack_style_test).
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

} // namespace atlas::load
