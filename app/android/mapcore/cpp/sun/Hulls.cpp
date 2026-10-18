#include "sun/Hulls.hpp"

#include "base/pymath/PyMath.hpp"

#include <algorithm>
#include <ranges>

namespace atlas::sun {

namespace {

namespace pm = atlas::pymath;

struct Point {
    double x;
    double z;
    std::uint32_t order; ///< its place among the points given (the first of equal points stays)
};

/// hull2's cross product, each operation in its order.
[[nodiscard]] double cross(const Point& o, const Point& a, const Point& b) noexcept {
    return ((a.x - o.x) * (b.z - o.z)) - ((a.z - o.z) * (b.x - o.x));
}

} // namespace

void hull2(std::span<const double> x, std::span<const double> z, std::vector<double>& out_x,
           std::vector<double>& out_z) {
    std::vector<Point> pts;
    pts.reserve(x.size());
    for (std::size_t i = 0; i < x.size(); ++i) {
        pts.push_back({.x = pm::round(x[i], 3), .z = pm::round(z[i], 3), .order = static_cast<std::uint32_t>(i)});
    }
    // Python's tuple order (x, then z; 0.0 and -0.0 equal), the first of equal points first: equal keys then lie
    // together, and only the first of each is kept, as the port's set and its stable sort leave them
    std::ranges::sort(pts, [](const Point& a, const Point& b) {
        if (a.x != b.x) return a.x < b.x;
        if (a.z != b.z) return a.z < b.z;
        return a.order < b.order;
    });
    std::vector<Point> kept;
    kept.reserve(pts.size());
    for (const Point& p : pts) {
        // the set's key is the point with 0.0 for -0.0 (`q.x + 0.0`): equal values, so equal points lie together
        if (!kept.empty() && kept.back().x == p.x && kept.back().z == p.z) continue;
        kept.push_back(p);
    }
    if (kept.size() <= 2) {
        for (const Point& p : kept) {
            out_x.push_back(p.x);
            out_z.push_back(p.z);
        }
        return;
    }
    std::vector<Point> lo;
    for (const Point& p : kept) {
        while (lo.size() >= 2 && cross(lo[lo.size() - 2], lo[lo.size() - 1], p) <= 0) lo.pop_back();
        lo.push_back(p);
    }
    std::vector<Point> up;
    for (const Point& p : std::ranges::reverse_view(kept)) {
        while (up.size() >= 2 && cross(up[up.size() - 2], up[up.size() - 1], p) <= 0) up.pop_back();
        up.push_back(p);
    }
    for (std::size_t i = 0; i + 1 < lo.size(); ++i) {
        out_x.push_back(lo[i].x);
        out_z.push_back(lo[i].z);
    }
    for (std::size_t i = 0; i + 1 < up.size(); ++i) {
        out_x.push_back(up[i].x);
        out_z.push_back(up[i].z);
    }
}

TileShadows shadows_of(const SunTile& tile, const Sun& sun, const Region& region) {
    return shadows_of(tile.solids, tile.trees, sun, region);
}

TileShadows shadows_of(const TileSolids& solids, const TileTrees& trees, const Sun& sun, const Region& region) {
    TileShadows out;
    if (!(sun.y > 0) || region.no_shadows) return out;
    // o(y): the shadow's offset of a point y metres up, (-L.x / L.y * y, -L.z / L.y * y)
    const double kx = -sun.x / sun.y;
    const double kz = -sun.z / sun.y;
    std::vector<double> px;
    std::vector<double> pz;
    const TileSolids& s = solids;
    for (std::uint32_t k = 0; k < s.size(); ++k) {
        const auto xs = s.xs(k);
        const auto zs = s.zs(k);
        const double top = s.h[k];
        const double ox = kx * top;
        const double oz = kz * top;
        if (s.min[k] > 0) { // a raised part casts its top's outline only
            for (std::size_t i = 0; i < xs.size(); ++i) {
                out.x.push_back(xs[i] + ox);
                out.z.push_back(zs[i] + oz);
            }
            out.kind.push_back(ShadowKind::raised);
        } else {
            px.assign(xs.begin(), xs.end());
            pz.assign(zs.begin(), zs.end());
            for (std::size_t i = 0; i < xs.size(); ++i) {
                px.push_back(xs[i] + ox);
                pz.push_back(zs[i] + oz);
            }
            hull2(px, pz, out.x, out.z);
            out.kind.push_back(ShadowKind::prism);
        }
        out.first.push_back(static_cast<std::uint32_t>(out.x.size()));
        out.owner.push_back(k);
        out.y.push_back(0.1);
    }
    const TileTrees& t = trees;
    for (std::size_t n = 0; n < t.x.size(); ++n) {
        const double off = t.h[n] * 0.7;
        const double ox = kx * off;
        const double oz = kz * off;
        const double r = t.crown[n] / 2;
        for (int k = 0; k < 16; ++k) {
            const double a = (k * pm::pi) / 8;
            out.x.push_back((t.x[n] + ox) + (r * pm::cos(a)));
            out.z.push_back((t.z[n] + oz) + (r * pm::sin(a)));
        }
        out.first.push_back(static_cast<std::uint32_t>(out.x.size()));
        out.kind.push_back(ShadowKind::tree);
        out.owner.push_back(static_cast<std::uint32_t>(n));
        out.y.push_back(t.gy[n] + 0.1);
    }
    return out;
}

} // namespace atlas::sun
