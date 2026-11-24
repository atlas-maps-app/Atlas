// The least face of a level (load/Levels.hpp): what the renders leave out at the depth a level's tiles are first drawn
// at, a face under 0.12 px² of the phone's frame there. The levels' writer leaves a building under it out of the
// level's tile; stage T leaves out a piece under it of a ring it draws piece by piece (geometry/Seams.hpp's split).
#pragma once

#include <cstdint>

namespace atlas::tiles {

/// The depth from the eye the coarse level (L13) is first drawn at (the finest level's reach), metres, and the focal
/// length of the phone's frame there (916 dp tall: 4.7 km is zoom 14.8 at 52° N), dp.
inline constexpr double coarse_depth = 4'700.0;
inline constexpr double frame_focal = 1'420.0;
/// The far level (L12) is drawn from twice the coarse level's depth on.
inline constexpr double far_depth = 2.0 * coarse_depth;

/// The least face at `depth` metres from the eye, m²: 0.12 px² of the frame there.
[[nodiscard]] constexpr double least_face_at(double depth) noexcept {
    const double px = depth / frame_focal;
    return 0.12 * px * px;
}

/// The least face of a tile of level `z`: the coarse level's (13), the far level's (12 and below), none at a window's
/// level, where every face is drawn.
[[nodiscard]] constexpr double least_face(std::uint8_t z) noexcept {
    return z >= 14 ? 0.0 : least_face_at(z == 13 ? coarse_depth : far_depth);
}

} // namespace atlas::tiles
