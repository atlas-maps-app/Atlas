// The street wall a door or a shopfront opens onto (render/amsterdam.py:1727 street_wall), for one wall of its
// building: stage P chooses the wall (load/Cut.cpp: near the node, with a mapped street 3.5 m outside it, scored as the
// port scores), the tiles carry its first corner (the thing layer's `wall`), and the frame takes the point on it and
// its direction from the building's ring again with this same arithmetic, so both are the port's to the bit.
#pragma once

#include "base/pymath/PyMath.hpp"
#include "geometry/RoofShapes.hpp"

#include <optional>

namespace atlas::geometry {

/// street_wall's values for one wall and one node.
struct StreetWall {
    double ux = 0; ///< the wall's run from its first corner to the next (street_wall's ux and uz before dividing by L)
    double uz = 0;
    double length = 0; ///< L
    double qx = 0;     ///< the point on the wall: the node's projection onto it, at 0.15 to 0.85 of its length
    double qz = 0;
};

/// The wall from corner `a` to corner `c` of a ring for a node at (x, z); none for a wall under 1.5 m² (street_wall's
/// `if L2 < 1.5: continue`).
[[nodiscard]] inline std::optional<StreetWall> street_wall_at(V2 a, V2 c, double x, double z) noexcept {
    const double ux = c.x - a.x;
    const double uz = c.z - a.z;
    const double l2 = (ux * ux) + (uz * uz);
    if (l2 < 1.5) return std::nullopt;
    const double tt = pymath::max(0.15, pymath::min(0.85, (((x - a.x) * ux) + ((z - a.z) * uz)) / l2));
    return StreetWall{.ux = ux, .uz = uz, .length = pymath::sqrt(l2), .qx = a.x + (ux * tt), .qz = a.z + (uz * tt)};
}

} // namespace atlas::geometry
