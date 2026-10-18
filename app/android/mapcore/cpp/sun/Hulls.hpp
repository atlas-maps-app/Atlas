// Ground shadows (plan-vector D8, K8): per sun bucket and per solid, the polygon its shadow covers on the ground, and
// per tree a 16-gon disc, as the port's groundMarks computes them (render/amsterdam.py:742–771): the convex hull of a
// prism's footprint and its top moved by −L.xz / L.y · h (City.hull2, render/city.py:80), a raised part's top outline
// alone, and a tree's crown circle moved by the shadow of 0.7 of its height. Points are on the ground's plane (x, z);
// the renders lift them to 0.1 m (a tree's to its ground + 0.1 m). Every operation is the port's, in its order:
// hull2 rounds to millimetres with Python's round, so the hulls equal hull2 bit for bit.
#pragma once

#include "sun/Samples.hpp"
#include "sun/Shadows.hpp"

#include <cstdint>
#include <span>
#include <vector>

namespace atlas::sun {

/// City.hull2: the convex hull of points (x[i], z[i]), each rounded to 3 decimals and the first of equal points kept,
/// counter-clockwise from the lowest x (then z), collinear points dropped. Fewer than 3 distinct points come back
/// sorted. Appends to out_x and out_z.
void hull2(std::span<const double> x, std::span<const double> z, std::vector<double>& out_x,
           std::vector<double>& out_z);

/// What a shadow polygon is.
enum class ShadowKind : std::uint8_t {
    prism = 0,  ///< a solid on the ground: the hull of its footprint and its top moved toward the shadow
    raised = 1, ///< a solid standing above the ground (min > 0): its top's outline moved, unhulled
    tree = 2,   ///< a tree's crown disc, 16 points
};

/// A tile's ground shadows for one sun: polygons of points (x, z) on the ground.
struct TileShadows {
    std::vector<double> x;
    std::vector<double> z;
    std::vector<std::uint32_t> first{0}; ///< where each polygon begins (one more than polygons)
    std::vector<ShadowKind> kind;
    std::vector<std::uint32_t> owner; ///< the solid's place in the tile's solids, or the tree's in its trees
    std::vector<double> y;            ///< the height the renders draw it at (0.1, or a tree's ground + 0.1)

    [[nodiscard]] std::uint32_t size() const noexcept { return static_cast<std::uint32_t>(kind.size()); }
    [[nodiscard]] std::span<const double> xs(std::uint32_t p) const noexcept {
        return std::span<const double>(x).subspan(first[p], first[p + 1] - first[p]);
    }
    [[nodiscard]] std::span<const double> zs(std::uint32_t p) const noexcept {
        return std::span<const double>(z).subspan(first[p], first[p + 1] - first[p]);
    }
};

/// The ground shadows of `tile`'s solids and trees for `sun`: none while the sun is down or the region draws none.
[[nodiscard]] TileShadows shadows_of(const SunTile& tile, const Sun& sun, const Region& region);

} // namespace atlas::sun
