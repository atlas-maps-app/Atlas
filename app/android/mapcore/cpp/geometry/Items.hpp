// Stage T of a region's items (ENGINE.md step 4, "Trees and things"; render/regions.py's draw_items): a render tile's
// towers with a height (S['mtowers']), wind turbines, cranes (with the form stage P derived from the whole scene),
// masts, gantries and goods conveyors (each segment of their way), a theme park's coaster tracks (each 3 m piece, as
// coasters.py subdivides them) and the trees a park or a zoo plants in its woods, and power lines (each segment), each
// an item as draw_items makes it, in its order: the towers, the turbines, the cranes, the masts, the gantries' segments
// then the conveyors', the coasters' pieces (draw_over's items) then the woods' trees, the power lines' segments. Each
// its point (the depth draw_items sorts it by), from 5 m (a coaster's piece from 3, a tree from 4), the radius cut
// where draw_items applies it at one point (a segment's, at either end, and a track's, at every fourth of its points
// and its last, the pass decides). What each draws the frame decides at its camera (shaders/item/items.slangh).
#pragma once

#include "scene/ItemTable.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Sections.hpp"

#include <array>
#include <cstdint>
#include <vector>

namespace atlas::geometry {

/// What a region item is (shaders/item/items.slangh's ITEM_ kinds).
enum class RegionItemKind : std::uint8_t {
    tower = 0,
    turbine = 1,
    crane = 2,
    mast = 3,
    gantry = 4,
    conveyor = 5,
    power = 6,
    coaster = 7, ///< a 3 m piece of a coaster track
    wood = 8,    ///< a tree a park or a zoo plants in its woods
};

/// The region items' list in the renders' gather order (scene::order_of): after the buildings, parts and nodes.
inline constexpr std::uint32_t region_items_list = 3;

/// draw_items' groups, in its order: the towers, the turbines, the cranes, the masts, the gantries, the conveyors, the
/// coasters' pieces and the woods' trees (draw_over's items, the trees after every piece), the power lines.
inline constexpr std::array<std::uint32_t, 9> region_item_group{0, 1, 2, 3, 4, 5, 7, 6, 6};

/// A wood's tree's place in the coasters' group: after every track's pieces (its index past this).
inline constexpr std::uint32_t wood_index_base = 1U << 20U;

/// coasters.py's subdivide: a track's pieces every 3 m.
inline constexpr double coaster_step = 3.0;

/// A power line's towers: 38 m, their tops 2 m more (draw_items' `H = 38.0`).
inline constexpr double power_height = 38.0;

/// One region item: a tower, a turbine, a crane, a mast, or a segment of a gantry, a conveyor or a power line.
struct RegionItem {
    RegionItemKind kind = RegionItemKind::tower;
    std::int64_t id = 0;       ///< its OSM node, or its way: a crane's outline, a gantry, a conveyor, a power line
    std::uint32_t index = 0;   ///< its place in its list (S['mtowers'], S['turbines'], S['masts'], ...)
    std::uint32_t segment = 0; ///< a line's segment: from its point `segment` to the next
    double x = 0;              ///< where it stands, frame metres (a segment's first end)
    double z = 0;
    double bx = 0; ///< a segment's second end
    double bz = 0;
    /// Its height: a tower's; a turbine's (100 m unless mapped); a crane's (44 m on a quay, 26 m in a yard unless
    /// mapped); a mast's (30 m with floodlights, 25 m else); a gantry's (7 m) or a conveyor's (6 m) unless mapped; a
    /// power line's 38 m.
    double h = 0;
    double rotor = 0;       ///< a turbine's rotor's radius (a 100 m rotor unless mapped)
    std::uint32_t gaps = 0; ///< a conveyor's segment's gaps between its legs: max(1, int(Lg / 22))
    bool cab = false;       ///< a tower's glazed cab (aeroway=tower, tower:type observation or control)
    bool lighting = false;  ///< a mast's frame of floodlights (tower:type=lighting)
    bool legs_a = false;    ///< a gantry's legs at its segment's first end (its way's first segment)
    bool legs_b = false;    ///< and at its second (its way's last)
    bool way = false;       ///< a crane mapped as a way (its outline)
    bool yard = false;      ///< a crane in a yard, a portal gantry; else a ship-to-shore crane on a quay
    /// A crane's axes (frame metres, unit): a ship-to-shore crane's boom toward the water w and q = (-w[1], w[0]); a
    /// yard gantry's u and v.
    double ux = 0;
    double uz = 0;
    double vx = 0;
    double vz = 0;
    double hu = 0; ///< a ship-to-shore crane's distance to its quay edge; a yard gantry's half length along u
    double hv = 0; ///< a yard gantry's half width along v
    /// A coaster's piece: its heights at its ends (x, z and bx, bz), its track in the tile's `tracks`, and its kind (0
    /// steel, 1 wood, 2 mono: a monorail's beam).
    double ya = 0;
    double yb = 0;
    std::uint32_t track = 0;
    std::uint8_t coaster = 0;
    /// A wood's tree: its crown's diameter, and its four clumps as random.Random(its id) draws them (woods.draw): each
    /// its offset from the crown's centre (rad cos(ang), uniform(-0.25, 0.25) r, rad sin(ang)) and its radius over s
    /// (r uniform(0.5, 0.66)), in double. woods.draw skips a clump whose point lies nearer than the near plane, and its
    /// radius's draw with it; a drawn tree's centre lies 4 m away at least, its clumps within 2.2 m of it, the near
    /// plane at 0.6 m: none is skipped, so the draws are the tree's own.
    double crown = 0;
    std::array<float, 16> clumps{};
    /// A power line's segment: its conductors' heights at a quarter, a half and three quarters of its length
    /// (H - 4 sin(pi f) min(1, Lg / 300), in double as the port computes them).
    std::array<double, 3> sag{};
};

/// A coaster track's pieces in a tile's items, its kind and its gap between posts, and the points the radius cut tests
/// (`tr['pts'][::4] + [tr['pts'][-1]]`, frame metres).
struct CoasterTrackSpan {
    std::uint32_t first = 0;
    std::uint32_t count = 0;
    std::uint8_t kind = 0;
    std::vector<std::array<double, 2>> cut;
};

/// A tile's region items, in the tile's order, and their items; its coaster tracks.
struct TileRegionItems {
    std::vector<RegionItem> items;
    std::vector<scene::ItemSpec> specs;
    std::vector<CoasterTrackSpan> tracks;
};

/// What the region items read: the tile, its pack's head (its frame), and the items' kind.
struct RegionItemInput {
    const tiles::Tile* tile = nullptr;
    const tiles::RenderHead* head = nullptr;
    std::uint16_t kind = 0;
};

/// The tile's region items.
[[nodiscard]] TileRegionItems build_region_items(const RegionItemInput& in);

/// The painter slots item `item` takes (the most elements it draws, each its own): a tower's five bands; a turbine's
/// tower, blades, their stroke, hub and night light; a ship-to-shore crane's twelve boxes' five faces each and its
/// stays, a yard gantry's nine boxes'; a mast's two boxes' and its two night circles; a gantry's three boxes'; a
/// conveyor's legs' and gallery's; a coaster's piece's post, braces, underside and top; a wood's tree's four clumps,
/// their four lit tops and a glint; a power line's tower and conductors.
[[nodiscard]] std::uint32_t region_item_slots(const RegionItem& item) noexcept;

} // namespace atlas::geometry
