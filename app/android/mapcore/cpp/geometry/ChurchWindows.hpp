// A church's own windows (render/closeup.py's church_windows; ENGINE.md step 4's facades): building=church draws, with
// weather and within 520 m, tall round-arched windows on each wall instead of its rows of plain ones, one per bay of
// about six metres (a generic detail of the type, not a mapped tag). Each window is three arches of the same form, a
// rectangle closed by a half circle: the stone around it (grown by 0.22 m) and the reveal on the wall's plane, the
// glass 0.38 m into the wall; and its leading, a mullion and a transom every metre and a half, 0.37 m in. What the
// frame then draws of them (the stone of at least 4 px², the leading of glass over 60 px², the colours) is the
// caller's.
#pragma once

#include "geometry/RoofShapes.hpp"

#include <array>
#include <cstddef>
#include <vector>

namespace atlas::geometry {

/// An arch's points: its foot's two corners, then its half circle from the right through the top to the left (nine
/// points, at angles pi k / 8), as the renders list them.
inline constexpr std::size_t arch_points = 11;

/// One church window.
struct ChurchWindow {
    std::array<V3, arch_points> stone{};  ///< the stone around it, on the wall's plane
    std::array<V3, arch_points> reveal{}; ///< the window's outline, on the wall's plane
    std::array<V3, arch_points> glass{};  ///< the glass, 0.38 m into the wall
    /// The leading: the mullion first, then the transoms upward, each its two ends, 0.37 m into the wall.
    std::vector<std::array<V3, 2>> lead;
};

/// The church windows of the wall from `a` to `b` (its foot's corners, frame metres), standing from `y0` to `top`,
/// `nrm` its unit normal out of the building: none where the wall stands under 6 m tall or 3 m long, or where its
/// windows would have under 2 m between their sill and the spring of their arch.
[[nodiscard]] std::vector<ChurchWindow> church_windows(V2 a, V2 b, double y0, double top, V2 nrm);

} // namespace atlas::geometry
