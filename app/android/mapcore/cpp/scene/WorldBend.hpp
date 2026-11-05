// The world's bend on the CPU (ENGINE.md step 5, "The bend"; view/Bend.hpp): where the world pass
// (shaders/world/world.slang) draws a ground point, in double, for the cover's walk (a tile's box on the bent ground,
// the horizon) and the tests.
//
// A point's flat place is the frame's (x and z; a tile's longitudes taken within 180° of the target's, so the
// antimeridian is no seam in view), dropped by the view's drop (w₁·|Δ|²/2R); its place on the sphere is that of the
// sphere of 6,371 km tangent to the ground at the camera's target, relative to the target in the frame's axes (x east,
// y up, z south), and the world pass blends the flat place to it by w₂. Up to 150 km w₂ is 0 and the world is flat
// and dropped; from 600 km it is the sphere, where what lies behind the horizon is culled.
#pragma once

#include "scene/Cells.hpp"
#include "tiles/Sections.hpp"
#include "view/Bend.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"

namespace atlas::scene {

/// The bend of a frame's world: the view's drop and target, and the sphere the world blends to.
struct WorldBend {
    double drop = 0;   ///< w₁/2R (view::drop_of)
    double sphere = 0; ///< w₂ (view::sphere_weight)
    view::Vec3 target; ///< the camera's target, where the sphere touches the ground, in the frame's metres
    view::Vec3 eye;    ///< the eye, in the frame's metres
    double lat = 0;    ///< the target's latitude and longitude, degrees
    double lon = 0;
    double sin_lat = 0;
    double cos_lat = 1;
};

/// The bend of `view`'s ground in `frame` (its target's degrees through the frame).
[[nodiscard]] WorldBend world_bend(const view::View& view, const view::GroundFrame& frame) noexcept;

/// The shift (degrees, a multiple of 360) that takes longitude `lon` within 180° of the target's.
[[nodiscard]] double wrap_shift(const WorldBend& b, double lon) noexcept;

/// The point (`lat`, `lon` degrees) of the sphere relative to the target, frame metres: world.slang's on_sphere, in
/// double.
[[nodiscard]] view::Vec3 sphere_offset(const WorldBend& b, double lat, double lon) noexcept;

/// Where the world pass draws ground point (`lat`, `lon` degrees; `shift` added to its longitude flat, wrap_shift's
/// of its tile), in the frame's metres: flat and dropped, blended to the sphere by w₂.
[[nodiscard]] view::Vec3 world_point(const WorldBend& b, const view::GroundFrame& f, double lat, double lon,
                                     double shift = 0.0) noexcept;

/// A box of the ground in degrees: its edges' latitudes and longitudes (west under east, which may pass ±180°).
struct DegreeBox {
    double north = 0;
    double south = 0;
    double west = 0;
    double east = 0;
};

/// A grid box in degrees.
[[nodiscard]] DegreeBox degrees_of(const tiles::GridBox& grid) noexcept;

/// The box in the frame's metres of a ground box as the world pass draws it (its longitudes within 180° of the
/// target's): flat and dropped up to 150 km, as box_of's with no top, down by the drop at its farthest point from the
/// target and at its nearest; then its points on a 5 × 5 lattice placed by world_point and padded by how far the
/// ground between them can bulge.
[[nodiscard]] Box world_box(const WorldBend& b, const view::GroundFrame& f, const DegreeBox& box) noexcept;

/// Whether any of a ground box can lie in front of the sphere's horizon: false only for one wholly behind it, and true
/// wherever the world is not the sphere yet (w₂ under 1).
[[nodiscard]] bool before_horizon(const WorldBend& b, const DegreeBox& box) noexcept;

} // namespace atlas::scene
