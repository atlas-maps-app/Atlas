// The building, part and variant layers (rendertiles' Schema.BUILDING, BUILDING_ATTRIBUTES, VARIANT): S['buildings']
// and S['parts'] with their footprints (keep codes per vertex where the pack simplifies) and the loader's derived
// values, the wall's and roof's base colours as surface_colour resolves them (no lens) among them, and the variants of
// those whose looks the loader derives differently at another of the ladder's tolerances. The loader's notes and its
// colour and material fields that the renders draw only through surface_colour are not carried (Schema.DROPPED).
#pragma once

#include "tiles/Schema.hpp"

#include <array>
#include <cstdint>

namespace atlas::tiles {

namespace building_attributes {
/// What buildings and parts carry besides their ring, and a variant besides its key; then the tags the renders read.
inline constexpr std::array<ColumnSpec, 24> columns = {{
    {"h", Codec::centi},           // height above the ground, metres (guessed or mapped)
    {"min", Codec::centi},         // the walls' base, metres (min_height, or terrain)
    {"roof_h", Codec::centi},      // the roof's height, metres
    {"year", Codec::nvarz},        // the year start_date begins with
    {"levels", Codec::nvarz},      // storeys (mapped, or from the height)
    {"parent_id", Codec::nvarz},   // a part: the id of the outline it stands in
    {"roof", Codec::str},          // roof shape after regions.py's CLOSEST
    {"orient", Codec::str},        // roof:orientation (along, across)
    {"wall_rgb", Codec::varu},     // the wall's base colour 0xRRGGBB: surface_colour(b, None), before light
    {"roof_rgb", Codec::varu},     // the roof's base colour 0xRRGGBB: surface_colour(b, None, 'roof'), before light
    {"mat_draw", Codec::str},      // the wall material drawn (brick, plaster, ...: its pattern)
    {"rmat_draw", Codec::str},     // the roof material drawn (roof_tiles, metal, ...: its pattern)
    {"kind", Codec::str},          // the generic look's kind (home, office, ...)
    {"otype", Codec::str},         // relation for buildings mapped as multipolygons, else null (a way)
    {"hidden", Codec::boolean},    // an outline drawn by its parts instead
    {"no_win", Codec::boolean},    // no window rows (halls, tanks, glass walls)
    {"in_church", Codec::boolean}, // a part standing in a church
    {"guessed", Codec::strs},      // the tags the loader guessed
    {"places", Codec::ids},        // the ids of the nodes (shops, cafés) inside it, in the scene's order
    {"place_hours", Codec::strs},  // opening_hours of each place, "" where none (null without places)
    {"flags", Codec::varu},        // derived flags (building::flag_*)
    {"tag:building", Codec::str},  {"tag:building:part", Codec::str}, {"tag:roof:direction", Codec::str},
}};
} // namespace building_attributes

/// S['buildings'] and S['parts']: the same columns.
namespace building {
inline constexpr auto columns =
    detail::concat(std::array<ColumnSpec, 4>{{
                       {"index", Codec::delta}, // source order in S['buildings'] (or S['parts'])
                       {"id", Codec::delta},    // the OSM id (a way, or a relation by otype)
                       {"ring", Codec::geom},   // the footprint, the scene's ring at tolerance 0
                       {"ring.keep", Codec::keep},
                   }},
                   building_attributes::columns);

inline constexpr std::uint8_t index = slot_of(columns, "index");
inline constexpr std::uint8_t id = slot_of(columns, "id");
inline constexpr std::uint8_t ring = slot_of(columns, "ring");
inline constexpr std::uint8_t ring_keep = slot_of(columns, "ring.keep");
inline constexpr std::uint8_t h = slot_of(columns, "h");
inline constexpr std::uint8_t min = slot_of(columns, "min");
inline constexpr std::uint8_t roof_h = slot_of(columns, "roof_h");
inline constexpr std::uint8_t year = slot_of(columns, "year");
inline constexpr std::uint8_t levels = slot_of(columns, "levels");
inline constexpr std::uint8_t parent_id = slot_of(columns, "parent_id");
inline constexpr std::uint8_t roof = slot_of(columns, "roof");
inline constexpr std::uint8_t orient = slot_of(columns, "orient");
inline constexpr std::uint8_t wall_rgb = slot_of(columns, "wall_rgb");
inline constexpr std::uint8_t roof_rgb = slot_of(columns, "roof_rgb");
inline constexpr std::uint8_t mat_draw = slot_of(columns, "mat_draw");
inline constexpr std::uint8_t rmat_draw = slot_of(columns, "rmat_draw");
inline constexpr std::uint8_t kind = slot_of(columns, "kind");
inline constexpr std::uint8_t otype = slot_of(columns, "otype");
inline constexpr std::uint8_t hidden = slot_of(columns, "hidden");
inline constexpr std::uint8_t no_win = slot_of(columns, "no_win");
inline constexpr std::uint8_t places = slot_of(columns, "places");
inline constexpr std::uint8_t place_hours = slot_of(columns, "place_hours");
inline constexpr std::uint8_t flags = slot_of(columns, "flags");
inline constexpr std::uint8_t tag_building = slot_of(columns, "tag:building");
inline constexpr std::uint8_t tag_building_part = slot_of(columns, "tag:building:part");
inline constexpr std::uint8_t tag_roof_direction = slot_of(columns, "tag:roof:direction");

/// The derived flags (Schema.Flags), computed by the packer with the renders' own tests.
inline constexpr std::uint64_t flag_church = 1;       // a church, cathedral or chapel, or a part in one
inline constexpr std::uint64_t flag_warehouse = 2;    // industrial, warehouse or service
inline constexpr std::uint64_t flag_shop_floor = 4;   // places attached, or retail or commercial
inline constexpr std::uint64_t flag_construction = 8; // the scaffold pattern
inline constexpr std::uint64_t flag_near_water = 16;  // within 16 m of a quay edge: mirrored in the water
inline constexpr std::uint64_t flag_houseboat = 32;
inline constexpr std::uint64_t flag_modern = 64;      // built in 1960 or later and not a church
inline constexpr std::uint64_t flag_wall_drawn = 128; // the loader drew the wall's colour: board lines take wall_rgb
} // namespace building

/// Buildings and parts whose loaded values differ at another tolerance of the ladder: each holds every attribute of
/// its base feature, as the scene loaded at that tolerance has them.
namespace variant {
inline constexpr auto columns = detail::concat(std::array<ColumnSpec, 3>{{
                                                   {"layer", Codec::u8}, // the base feature's layer (building or part)
                                                   {"index", Codec::delta},  // the base feature's source order
                                                   {"tolerance", Codec::u8}, // the ladder index the scene was loaded at
                                               }},
                                               building_attributes::columns);

inline constexpr std::uint8_t layer = slot_of(columns, "layer");
inline constexpr std::uint8_t index = slot_of(columns, "index");
inline constexpr std::uint8_t tolerance = slot_of(columns, "tolerance");
} // namespace variant

} // namespace atlas::tiles
