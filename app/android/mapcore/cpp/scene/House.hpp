// The house the app asks about, with the roof it asks about (ENGINE.md step 6, the user's own edits drawn at once:
// FillIn's roof question). While FillIn asks what shape a building's roof is, the map draws the building as the FillIn
// board's house: a plain box until the answer, then the box under the roof picked. No tile holds that shape (a tile
// holds its building's own roof, mapped or guessed), so the core builds the one building again from its tile's chunk,
// with the roof asked for (geometry::build_house), into triangles the house's pass draws in the building's place
// (passes/House.hpp), and the ranks leave the tile's own building out meanwhile (frame::RankOptions::replaced).
#pragma once

#include "tiles/Chunks.hpp"

#include <array>
#include <cstdint>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::tiles {
class Store; // tiles/Store.hpp
} // namespace atlas::tiles

namespace atlas::scene {

struct ResidentTile; // scene/Residency.hpp

/// What a face of the house is, as the board colours it: a wall (a gable's triangle among them), in the walls' flat
/// colour and hatched before the colour's answer; a pitched roof's slope or hip, the board's grey in the moment's
/// light; a flat top, the board's white.
enum class HousePart : std::uint8_t { wall = 0, slope = 1, top = 2 };

/// One face: its part, its unit normal (frame axes: x east, y up, z south), and its triangles' corners in
/// House::points, three a triangle.
struct HouseFace {
    HousePart part = HousePart::wall;
    std::array<double, 3> normal{};
    std::uint32_t first = 0;
    std::uint32_t count = 0;
};

/// The house: who it is, the tile it was read from, and its triangles.
struct House {
    std::int64_t id = 0;
    bool relation = false;
    std::string_view roof; ///< one of geometry::house_roofs
    /// Its walls' colour as its tile has it, 0xRRGGBB before light (the tile's wall_rgb): the house's walls' where the
    /// app does not ask about their colour.
    std::uint32_t wall = 0;
    /// The resident tile it was read from: its key and its pack (the store's id). The house is drawn while the cover
    /// draws that tile, at its place.
    tiles::TileKey key;
    std::string pack;
    /// Its triangles' corners, metres from that tile's south-west corner at street level in its pack's frame (x east, y
    /// up, z south), as the tile's own geometry is.
    std::vector<std::array<float, 3>> points;
    std::vector<HouseFace> faces;
    /// The flat top's outline (a "flat" house's: the board strokes it), its corners in order, closed by its first.
    std::vector<std::array<float, 3>> rim;
};

/// The roofs a house may be asked in, by the code the app sends (MapCore.ask's roof): 0 none (the building's own roof,
/// no house), 1 flat, 2 gabled, 3 hipped; "" for 0 or a code it does not know.
[[nodiscard]] std::string_view house_roof(std::uint32_t code);

/// The finest of the tiles in `resident` that draws building `id` (its items' building key; a level's tile draws it
/// too, farther off), or null.
[[nodiscard]] const ResidentTile* house_tile(std::span<const std::shared_ptr<const ResidentTile>> resident,
                                             std::int64_t id) noexcept;

/// The house of building `id` (a relation's where `relation`) with roof `roof` (one of geometry::house_roofs), read
/// from resident tile `from`'s chunk through `store`; null where its chunk does not read, or the building is not drawn
/// as itself there (drawn by its parts, a houseboat: geometry::build_house).
[[nodiscard]] std::shared_ptr<const House> house_of(const tiles::Store& store, const ResidentTile& from,
                                                    std::int64_t id, bool relation, std::string_view roof);

} // namespace atlas::scene
