// Simplification by keep codes (plan-vector D2, N2; rendertiles' KeepCodes.simplify). regions.py's `simplify` splits a
// ring where its farthest point lies, which does not depend on the tolerance, so a vertex kept at one tolerance is kept
// at every smaller one: its keep code counts the ladder's tolerances it survives. `simplify(ring, keep::tolerances[l])`
// is then the vertices whose code is at least l, in order, with simplify's two exceptions applied here: a ring of fewer
// than 6 points is never simplified, and a result of fewer than 3 points, or whose area is 2 m² or less, falls back to
// the whole ring. The area is the port's signed_area over the vertices' metres in the pack's frame, the same operations
// in the same order, so the core decides the fallback bit for bit as the port does.
//
// At a view's or band's tolerance (RenderHead::tolerance_of) the renders simplify buildings, parts and areas; land
// cover and the water are drawn whole there, their codes serving the coarser levels. A pack never simplified (the
// living map) carries no codes: its rings come back whole at every level.
#pragma once

#include "tiles/Payload.hpp"
#include "tiles/Sections.hpp"

#include <cstddef>
#include <cstdint>
#include <span>

namespace atlas::tiles {

/// What simplifying a ring gives: how many vertices, and whether that is the whole ring (a fallback, or nothing cut).
struct Simplified {
    std::size_t count = 0;
    bool whole = false;
};

/// Simplifies `ring` at the ladder's `level` (0 to 13): writes the kept vertices' indexes into `out` (at least
/// ring.size() long), in order. A ring without keep codes, level 0, or a small ring is whole.
[[nodiscard]] Simplified simplify(const RingView& ring, unsigned level, const Frame& frame,
                                  std::span<std::uint32_t> out) noexcept;

/// regions.py's signed_area (real.py:79) of the vertices `indexes` of `ring`, in metres in `frame`.
[[nodiscard]] double signed_area(const RingView& ring, std::span<const std::uint32_t> indexes,
                                 const Frame& frame) noexcept;

} // namespace atlas::tiles
