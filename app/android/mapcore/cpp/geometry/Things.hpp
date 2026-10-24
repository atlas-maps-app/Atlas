// Stage T of the street furniture (ENGINE.md step 4, "Trees and things"): a render tile's street lamps, benches,
// bicycle racks, waste baskets and bollards, the S['nodes'] amsterdam.py's gather takes besides the trees
// (highway=street_lamp, else an amenity of FURNITURE, else a barrier of BOLLARDS), each an item as the gather makes it:
// its place in S['nodes'], sorted by the depth of (x, 1, z), from 2 m, the radius cut at it. What each draws
// (draw_lamp, draw_bench, draw_box, draw_bollard, draw_rack) the frame decides at its camera
// (shaders/tree/things.slangh).
#pragma once

#include "scene/ItemTable.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Sections.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <string_view>
#include <vector>

namespace atlas::geometry {

/// What a thing is: the gather's kinds.
enum class ThingKind : std::uint8_t { lamp = 0, bench = 1, basket = 2, bollard = 3, rack = 4 };

/// The painter slots a thing of each kind takes (the most it draws): a lamp's wet streak, mast, lantern, cap, glint
/// and night halo; a bench's two boxes, each its leg and its top; a basket's leg and top; a bollard's post and head;
/// a rack's arcs.
inline constexpr std::array<std::uint32_t, 5> thing_slots{6, 4, 2, 2, 1};

/// One thing.
struct Thing {
    std::int64_t id = 0;    ///< its OSM node
    std::uint32_t node = 0; ///< its place in S['nodes']
    double x = 0;           ///< where it stands (frame metres)
    double z = 0;
    double gy = 0; ///< the ground under it (terrain towns; 0 elsewhere)
    ThingKind kind = ThingKind::lamp;
};

/// A tile's things, in the tile's order, and their items.
struct TileThings {
    std::vector<Thing> things;
    std::vector<scene::ItemSpec> items;
};

/// What the things read: the tile, its pack's head (its frame), and the items' kind.
struct ThingInput {
    const tiles::Tile* tile = nullptr;
    const tiles::RenderHead* head = nullptr;
    std::uint16_t kind = 0;
};

/// The tile's things.
[[nodiscard]] TileThings build_things(const ThingInput& in);

/// The gather's kind of a node with these tags: a street lamp (highway=street_lamp), else a bench, a rack or a basket
/// (amenity bench, bicycle_parking or waste_basket), else a bollard (barrier bollard or block); else none.
[[nodiscard]] std::optional<ThingKind> thing_kind(std::optional<std::string_view> highway,
                                                  std::optional<std::string_view> amenity,
                                                  std::optional<std::string_view> barrier) noexcept;

} // namespace atlas::geometry
