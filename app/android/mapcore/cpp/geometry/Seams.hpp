// A building's seams (load/Clean.hpp): the cleaning joins a footprint's courtyards and pieces into its one ring by
// seams, an edge run there and back, which draw nothing. Whatever stands on a footprint's edges (its walls, their
// windows and doors, its sun samples, its eaves) leaves a seam out. A roof is laid over each piece by itself
// (pieces_of): a piece without a courtyard takes the building's pitched roof, one with a courtyard is drawn flat,
// until the roofs are built from any footprint. The Rust library behind load/CleanAbi.h finds the seams.
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

/// A ring with seams, piece by piece: for each piece the ring's edges that are its own, in the ring's order (edge i
/// runs from vertex i to i + 1), which follow each other round it. They are its outer ring's and, where it has
/// courtyards, theirs and the seams that join them to it (a seam whose middle lies in the footprint); the seams
/// between two pieces (their middle outside it) are in none. So a piece without a courtyard holds no seam, and its
/// edges' first points are a simple ring; a piece with one is a ring with seams of its own. `seam` is seams(ring).
/// One piece of every edge where the ring has no seam, or its seams do not pair up there and back (no ring the
/// cleaning made).
[[nodiscard]] std::vector<std::vector<std::uint32_t>> pieces_of(std::span<const V2> ring,
                                                                std::span<const std::uint8_t> seam);

/// The same for a ring given as x, z pairs one after another.
[[nodiscard]] std::vector<std::vector<std::uint32_t>> pieces_xz(std::span<const double> xz,
                                                                std::span<const std::uint8_t> seam);

/// Whether a piece (one of pieces_of's) has a courtyard: one of its edges is a seam.
[[nodiscard]] bool has_courtyard(std::span<const std::uint32_t> piece, std::span<const std::uint8_t> seam) noexcept;

/// The roof shapes stage T draws pitched (geometry/Roofs.hpp), a quadruple saltbox as hipped.
inline constexpr std::array<std::string_view, 6> pitched_shapes = {"gabled",   "pyramidal", "hipped",
                                                                   "skillion", "mansard",   "quadruple_saltbox"};

/// Whether stage T draws a roof of `shape` and height `roof_h` pitched over a piece of a footprint: a shape it draws,
/// with a height, where the piece has no courtyard (`courtyard`: has_courtyard; a ring without seams has none).
/// Anything else it draws flat, the walls reaching the top.
[[nodiscard]] constexpr bool drawn_pitched(std::optional<std::string_view> shape, double roof_h,
                                           bool courtyard) noexcept {
    return shape.has_value() && std::ranges::find(pitched_shapes, *shape) != pitched_shapes.end() && roof_h != 0.0 &&
           !courtyard;
}

} // namespace atlas::geometry
