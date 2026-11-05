// Stage T of the shopfronts (ENGINE.md step 4, the facades; render/amsterdam.py's facade_details and shopfront): a
// render tile's mapped shops, cafés and hotels that open onto a street wall, each a shopfront the frame may draw. Where
// facades are drawn (the living map: regions set no_facades), stage P gives a node with a place style its building (the
// thing layer's `building`, facade_details' `b`) and that building's street wall (`wall`), and files it in its
// building's tile, as it does a door (geometry/Doors.hpp). A shopfront is a business (`biz`: a place style other than
// a service's): its node's place in S['nodes'], its id and position; its building's id; the point on its wall and the
// wall's direction (geometry/StreetWall.hpp); its place style (poi_style's category, colour, kind, brand, sign and text
// colours), its opening hours and whether outdoor_seating sets out a terrace. Its items, as facade_details sorts what
// shopfront draws:
//   - its board (the frame, glass, mullions, awning, sign and the night's spill): sorted as a door is (its building's
//     centroid at half its height, less 0.03);
//   - its blade, the sign out over the pavement: at its own point, P3(W2 + 0.35, 3.15, 0.6);
//   - for a food place with outdoor seating, its three tables: each at its own point, its top's centre 0.75 m up.
// Each has its radius cut at its node and its node's reach (230 m), and they follow one another in the gather's order
// (facade_details appends each node's in turn, every door after them). Which draw, with what, the frame decides at its
// camera and moment (passes/Shopfronts.hpp). The tile also keeps its water's pieces (its rings that are no islands):
// a table on the water is not set out (on_water), and its point may lie in any tile. And its places of S['pois'] that
// map opening hours (the night's glows, amsterdam.py:854: an item each, its point 1.5 m up, its radius cut at its
// node), each tile's distinct opening hours once, which a shopfront and a place refer to.
#pragma once

#include "geometry/RoofShapes.hpp"
#include "scene/ItemTable.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Sections.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace atlas::geometry {

/// The painter slots of a shopfront's board: its frame, its glass, its mullions, its awning and its outline, the
/// awning's stripes, its sign board and its outline, the sign's text, and the night's spill on the pavement.
inline constexpr std::uint32_t board_slots = 10;
/// Of its blade: the bracket, the blade and its outline, its text.
inline constexpr std::uint32_t blade_slots = 4;
/// Of a table: its leg; each chair's leg, seat and back; its top and the top's outline; the parasol's pole and its
/// twelve panels.
inline constexpr std::uint32_t table_slots = 22;

/// Of an open place's glow: its circle.
inline constexpr std::uint32_t glow_slots = 1;

/// The glows' items' list (scene::order_of): the renders draw them after every object, unsorted, so after every list.
inline constexpr std::uint32_t glows_list = 6;

/// No opening hours mapped.
inline constexpr std::uint32_t no_hours = 0xFFFF'FFFFU;

/// A place style's category as the shopfront reads it (poi_style's CATS): food, grocery and stay take an awning, food
/// its stripes and a terrace.
enum class ShopCategory : std::uint8_t { other = 0, food = 1, grocery = 2, stay = 3 };

/// facade_details' W2: half a shopfront's width (metres).
inline constexpr double shop_half_width = 1.9;

/// shopfront's terrace: each table's place along the wall and out from it (du, off).
inline constexpr std::array<std::array<double, 2>, 3> table_places{{{-1.1, 2.0}, {1.1, 2.0}, {0.0, 3.4}}};

/// One shopfront: a business on a street wall, as facade_details and shopfront read it.
struct Shopfront {
    std::uint32_t node = 0; ///< its place in S['nodes']
    std::int64_t id = 0;    ///< its OSM node
    double x = 0;           ///< the node (frame metres)
    double z = 0;
    std::int64_t building = 0; ///< its building's OSM id
    double qx = 0;             ///< the point on its street wall
    double qz = 0;
    double ux = 0; ///< the wall's direction, unit
    double uz = 0;
    ShopCategory category = ShopCategory::other;
    std::uint32_t colour = 0;          ///< its category's colour (st['col'], 0xRRGGBB)
    std::optional<std::uint32_t> sign; ///< its brand's sign colour (st['bg']), where it has one
    std::optional<std::uint32_t> text; ///< its brand's text colour (st['fg'])
    std::string kind;                  ///< st['kind']
    std::string brand;                 ///< st['brand'], empty for none (Python's falsy)
    std::uint32_t hours = no_hours;    ///< its opening_hours' place in its tile's list, no_hours for none
    /// A food place whose outdoor_seating sets out a terrace (yes, sidewalk, pedestrian_zone, limited).
    bool seating = false;
};

/// A place of S['pois'] with its opening hours mapped (its glow at night while they say it is open).
struct OpenPlace {
    std::uint32_t node = 0; ///< its place in S['nodes']
    std::int64_t id = 0;    ///< its OSM node
    double x = 0;           ///< the node (frame metres)
    double z = 0;
    std::uint32_t hours = no_hours; ///< its opening_hours' place in its tile's list
    std::uint8_t cell = 0;          ///< its cell in the tile
};

/// A water ring of the tile (one of its pieces of S['water'], no island) in frame metres, with its box.
struct WaterPiece {
    std::vector<V2> ring;
    double x0 = 0;
    double z0 = 0;
    double x1 = 0;
    double z1 = 0;
};

/// A tile's shopfronts in the tile's order (S['nodes']' within each cell), its places with opening hours, their items
/// (each shopfront's board, its blade, then its tables' where it has a terrace, in that order; then each place's
/// glow), its water's pieces and its distinct opening hours.
struct TileShopfronts {
    std::vector<Shopfront> shops;
    std::vector<OpenPlace> places;
    std::vector<scene::ItemSpec> items;
    std::vector<WaterPiece> water;
    std::vector<std::string> hours;
};

/// What the shopfronts read: the tile, its pack's head (its frame), and the items' kinds.
struct ShopfrontInput {
    const tiles::Tile* tile = nullptr;
    const tiles::RenderHead* head = nullptr;
    std::uint16_t board = 0;
    std::uint16_t blade = 0;
    std::uint16_t table = 0;
    std::uint16_t glow = 0;
};

/// facade_details' quad corners: shopfront's P3(u, y, off), along the wall `u` from its point, `y` up and `off` out
/// along its normal (vnorm((uz, 0, -ux))).
struct ShopFrame {
    double qx = 0;
    double qz = 0;
    double ux = 0;
    double uz = 0;
    double nx = 0;
    double nz = 0;
};
[[nodiscard]] ShopFrame shop_frame(const Shopfront& s) noexcept;
/// P3(u, y, off), x and z (the port's qx + ux * u + nrm[0] * off).
[[nodiscard]] V2 shop_point(const ShopFrame& f, double u, double off) noexcept;

/// The tile's shopfronts.
[[nodiscard]] TileShopfronts build_shopfronts(const ShopfrontInput& in);

/// A colour as poi_style writes it ("#RRGGBB"), 0xRRGGBB; none where it is not one.
[[nodiscard]] std::optional<std::uint32_t> style_colour(std::string_view hex) noexcept;

} // namespace atlas::geometry
