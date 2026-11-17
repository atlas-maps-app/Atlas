// A building's seams (load/Clean.hpp): the cleaning joins a footprint's courtyards and pieces into its one ring by
// seams, an edge run there and back, which draw nothing. Whatever stands on a footprint's edges (its walls, their
// windows and doors, its sun samples, its eaves) leaves a seam out; a pitched roof over a ring with seams is drawn
// flat, until the roofs are built from any footprint. The Rust library behind load/CleanAbi.h finds them.
#pragma once

#include "geometry/RoofShapes.hpp"
#include "tiles/Payload.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::geometry {

/// For each edge of the closed ring (from vertex i to i + 1), 1 where it is a seam (its reverse is an edge of the ring
/// too), else 0; empty where the ring has none.
[[nodiscard]] std::vector<std::uint8_t> seams(std::span<const V2> ring);

/// The same for a ring given as x, z pairs one after another.
[[nodiscard]] std::vector<std::uint8_t> seams_xz(std::span<const double> xz);

/// One piece of a building's footprint: its outer ring and its courtyards, on the grid, as the tiles hold rings (an
/// outer ring with a positive area in the frame's metres, a courtyard the other way).
struct RingPiece {
    std::vector<tiles::GridPoint> outer;
    std::vector<std::vector<tiles::GridPoint>> holes;
};

/// A building's ring as the tiles hold it, its seams cut out: its pieces and their courtyards (one piece, the ring
/// itself, where it has no seam); nothing where it is no ring the cleaning made. For whatever draws a footprint by its
/// rings (the roofs built from any footprint, a holes column).
[[nodiscard]] std::optional<std::vector<RingPiece>> rings_of(const tiles::RingView& ring);

/// The roof shapes stage T draws pitched (geometry/Roofs.hpp), a quadruple saltbox as hipped.
inline constexpr std::array<std::string_view, 6> pitched_shapes = {"gabled",   "pyramidal", "hipped",
                                                                   "skillion", "mansard",   "quadruple_saltbox"};

/// Whether stage T draws a roof of `shape` and height `roof_h` pitched: a shape it draws, with a height, over a ring
/// without seams. Anything else it draws flat, the walls reaching the top.
[[nodiscard]] constexpr bool drawn_pitched(std::optional<std::string_view> shape, double roof_h, bool seamed) noexcept {
    return shape.has_value() && std::ranges::find(pitched_shapes, *shape) != pitched_shapes.end() && roof_h != 0.0 &&
           !seamed;
}

} // namespace atlas::geometry
