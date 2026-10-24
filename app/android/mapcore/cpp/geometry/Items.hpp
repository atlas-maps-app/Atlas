// Stage T of a region's items (ENGINE.md step 4, "Trees and things"; render/regions.py's draw_items): a render tile's
// towers with a height (S['mtowers']), wind turbines, masts, gantries and goods conveyors (each segment of their way)
// and power lines (each segment), each an item as draw_items makes it, in its order: the towers, the turbines, (the
// cranes, to come with their form from stage P), the masts, the gantries' segments then the conveyors', (the coasters,
// to come with Efteling), the power lines' segments. Each its point (the depth draw_items sorts it by), from 5 m, the
// radius cut where draw_items applies it at one point (a segment's, at either end, the pass decides). What each draws
// the frame decides at its camera (shaders/item/items.slangh).
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
};

/// The region items' list in the renders' gather order (scene::order_of): after the buildings, parts and nodes.
inline constexpr std::uint32_t region_items_list = 3;

/// draw_items' groups, in its order: the towers, the turbines, the cranes, the masts, the gantries, the conveyors, the
/// coasters, the power lines.
inline constexpr std::array<std::uint32_t, 7> region_item_group{0, 1, 2, 3, 4, 5, 7};

/// A power line's towers: 38 m, their tops 2 m more (draw_items' `H = 38.0`).
inline constexpr double power_height = 38.0;

/// One region item: a tower, a turbine, a mast, or a segment of a gantry, a conveyor or a power line.
struct RegionItem {
    RegionItemKind kind = RegionItemKind::tower;
    std::int64_t id = 0;       ///< its OSM element: a node, or a gantry's, conveyor's or power line's way
    std::uint32_t index = 0;   ///< its place in its list (S['mtowers'], S['turbines'], S['masts'], ...)
    std::uint32_t segment = 0; ///< a line's segment: from its point `segment` to the next
    double x = 0;              ///< where it stands, frame metres (a segment's first end)
    double z = 0;
    double bx = 0; ///< a segment's second end
    double bz = 0;
    /// Its height: a tower's; a turbine's (100 m unless mapped); a mast's (30 m with floodlights, 25 m else); a
    /// gantry's (7 m) or a conveyor's (6 m) unless mapped; a power line's 38 m.
    double h = 0;
    double rotor = 0;       ///< a turbine's rotor's radius (a 100 m rotor unless mapped)
    std::uint32_t gaps = 0; ///< a conveyor's segment's gaps between its legs: max(1, int(Lg / 22))
    bool cab = false;       ///< a tower's glazed cab (aeroway=tower, tower:type observation or control)
    bool lighting = false;  ///< a mast's frame of floodlights (tower:type=lighting)
    bool legs_a = false;    ///< a gantry's legs at its segment's first end (its way's first segment)
    bool legs_b = false;    ///< and at its second (its way's last)
    /// A power line's segment: its conductors' heights at a quarter, a half and three quarters of its length
    /// (H - 4 sin(pi f) min(1, Lg / 300), in double as the port computes them).
    std::array<double, 3> sag{};
};

/// A tile's region items, in the tile's order, and their items.
struct TileRegionItems {
    std::vector<RegionItem> items;
    std::vector<scene::ItemSpec> specs;
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
/// tower, blades, their stroke, hub and night light; a mast's two boxes' five faces each and its two night circles; a
/// gantry's three boxes'; a conveyor's legs' and gallery's; a power line's tower and conductors.
[[nodiscard]] std::uint32_t region_item_slots(const RegionItem& item) noexcept;

} // namespace atlas::geometry
