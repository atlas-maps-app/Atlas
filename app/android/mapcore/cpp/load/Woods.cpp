#include "load/Woods.hpp"

#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"
#include "geometry/RoofShapes.hpp"
#include "load/Amsterdam.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <ranges>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::load {

namespace {

namespace pm = atlas::pymath;

/// woods.py's CLEAR: the land inside a wood no tree is planted on.
constexpr std::array<std::string_view, 13> clear{"grass",   "garden",    "playground", "pitch", "pedestrian",
                                                 "parking", "flowerbed", "sand",       "park",  "track",
                                                 "meadow",  "grassland", "bare_rock"};

/// woods.py's Grid: a 2 m occupancy grid over a square, its cells marked by what is mapped, where no tree is planted.
class Grid {
public:
    Grid(double x0, double x1, double z0, double z1, double cell = 2.0)
        : x0_(x0), z0_(z0), c_(cell), nx_(pm::to_int((x1 - x0) / cell) + 1), nz_(pm::to_int((z1 - z0) / cell) + 1),
          b_(static_cast<std::size_t>(nx_ * nz_), std::uint8_t{0}) {}

    void mark_disc(double x, double z, double r) {
        const double c = c_;
        const std::int64_t i0 = pm::to_int((x - r - x0_) / c);
        const std::int64_t i1 = pm::to_int((x + r - x0_) / c);
        const std::int64_t j0 = pm::to_int((z - r - z0_) / c);
        const std::int64_t j1 = pm::to_int((z + r - z0_) / c);
        for (std::int64_t j = std::max<std::int64_t>(0, j0); j < std::min(nz_, j1 + 1); ++j) {
            for (std::int64_t i = std::max<std::int64_t>(0, i0); i < std::min(nx_, i1 + 1); ++i) {
                const double dx = (x0_ + (static_cast<double>(i) * c)) - x;
                const double dz = (z0_ + (static_cast<double>(j) * c)) - z;
                if (pm::pow(dx, 2) + pm::pow(dz, 2) <= r * r) b_[static_cast<std::size_t>((j * nx_) + i)] = 1;
            }
        }
    }

    void mark_line(std::span<const V2> pts, double r) {
        for (std::size_t k = 0; k + 1 < pts.size(); ++k) {
            const V2& a = pts[k];
            const V2& b = pts[k + 1];
            const std::int64_t n = std::max<std::int64_t>(1, pm::to_int(geometry::dist(a, b) / (c_ * 0.8)));
            for (std::int64_t t = 0; t <= n; ++t) {
                const double f = static_cast<double>(t) / static_cast<double>(n);
                mark_disc(a.x + ((b.x - a.x) * f), a.z + ((b.z - a.z) * f), r);
            }
        }
    }

    void mark_poly(std::span<const V2> poly, double grow = 0.0) {
        if (poly.empty()) return;
        double zmin = poly[0].z;
        double zmax = poly[0].z;
        for (const V2& p : poly) {
            zmin = std::min(zmin, p.z);
            zmax = std::max(zmax, p.z);
        }
        const double c = c_;
        std::vector<double> xs;
        for (std::int64_t j = std::max<std::int64_t>(0, pm::to_int((zmin - z0_) / c));
             j < std::min(nz_, pm::to_int((zmax - z0_) / c) + 1); ++j) {
            const double z = z0_ + (static_cast<double>(j) * c);
            xs.clear();
            for (std::size_t k = 0; k < poly.size(); ++k) {
                const V2& a = poly[k];
                const V2& b = poly[(k + 1) % poly.size()];
                if ((a.z <= z && z < b.z) || (b.z <= z && z < a.z))
                    xs.push_back(a.x + ((z - a.z) * (b.x - a.x) / (b.z - a.z)));
            }
            std::ranges::sort(xs);
            for (std::size_t q = 0; q + 1 < xs.size(); q += 2) {
                for (std::int64_t i = std::max<std::int64_t>(0, pm::to_int((xs[q] - grow - x0_) / c));
                     i < std::min(nx_, pm::to_int((xs[q + 1] + grow - x0_) / c) + 1); ++i) {
                    b_[static_cast<std::size_t>((j * nx_) + i)] = 1;
                }
            }
        }
        if (grow > 0) {
            std::vector<V2> ring(poly.begin(), poly.end());
            ring.push_back(poly[0]);
            mark_line(ring, grow);
        }
    }

    [[nodiscard]] bool free(double x, double z) const {
        const std::int64_t i = pm::round((x - x0_) / c_);
        const std::int64_t j = pm::round((z - z0_) / c_);
        return i >= 0 && i < nx_ && j >= 0 && j < nz_ && b_[static_cast<std::size_t>((j * nx_) + i)] == 0;
    }

private:
    double x0_;
    double z0_;
    double c_;
    std::int64_t nx_;
    std::int64_t nz_;
    std::vector<std::uint8_t> b_;
};

} // namespace

double woods_radius(const tiles::Frame& frame, const std::array<double, 4>& bbox) noexcept {
    double far = 0.0;
    for (const double lat : {bbox[0], bbox[2]}) {
        for (const double lon : {bbox[1], bbox[3]}) {
            const V2 p = load::xz(frame, lat, lon);
            far = std::max(far, pm::hypot(p.x, p.z));
        }
    }
    return 2.0 * std::ceil((far + 40.0) / 2.0);
}

std::vector<WoodTree> plant_woods(const Scene& s, double cx, double cz, double rad) {
    constexpr double spacing = 8.5;
    Grid g(cx - rad, cx + rad, cz - rad, cz + rad);
    // near: any of every twelfth point and the last within rad + 40 of the centre, either way
    const auto near = [&](std::span<const V2> pts) {
        if (pts.empty()) return false;
        const std::size_t step = std::max<std::size_t>(1, pts.size() / 12);
        const auto in = [&](const V2& p) { return std::abs(p.x - cx) < rad + 40 && std::abs(p.z - cz) < rad + 40; };
        for (std::size_t i = 0; i < pts.size(); i += step) {
            if (in(pts[i])) return true;
        }
        return in(pts.back());
    };
    for (const std::vector<Building>* list : {&s.buildings, &s.parts}) {
        for (const Building& b : *list) {
            if (near(b.poly)) g.mark_poly(b.poly, 3.0);
        }
    }
    for (const Road& r : s.roads) {
        if (near(r.pts)) g.mark_line(r.pts, ((r.w != 0.0 ? r.w : 2.0) / 2) + 1.6);
    }
    for (const std::vector<V2>& w : s.water) {
        if (near(w)) g.mark_poly(w, 1.5);
    }
    for (const Railway& r : s.rail) {
        if (near(r.pts)) g.mark_line(r.pts, 2.6);
    }
    for (const CoasterTrack& t : s.coasters) {
        if (near(t.pts)) g.mark_line(t.pts, 3.2);
    }
    for (const Land& a : s.land) {
        if (std::ranges::find(clear, a.kind) != clear.end() && near(a.poly)) g.mark_poly(a.poly);
    }
    pm::PyRandom rnd(7);
    std::vector<WoodTree> trees;
    const double dz = spacing * pm::sqrt(3.0) / 2;
    for (const Land& a : s.land) {
        if ((a.kind != "forest" && a.kind != "wood") || !near(a.poly)) continue;
        const auto [x0, x1] = std::ranges::minmax(a.poly | std::views::transform(&V2::x));
        const auto [z0, z1] = std::ranges::minmax(a.poly | std::views::transform(&V2::z));
        std::int64_t j = 0;
        double z = pm::max(z0, cz - rad);
        while (z <= pm::min(z1, cz + rad)) {
            double x = pm::max(x0, cx - rad) + ((j % 2) != 0 ? spacing / 2 : 0.0);
            while (x <= pm::min(x1, cx + rad)) {
                const double px = x + rnd.uniform(-2.6, 2.6);
                const double pz = z + rnd.uniform(-2.2, 2.2);
                if (pm::hypot(px - cx, pz - cz) <= rad && g.free(px, pz) && geometry::pip(px, pz, a.poly)) {
                    const double h = rnd.uniform(13, 21);
                    const double crown = rnd.uniform(6.0, 9.0);
                    trees.push_back({.x = px, .z = pz, .h = h, .crown = crown, .id = rnd.randrange(1 << 30)});
                }
                x += spacing;
            }
            z += dz;
            ++j;
        }
    }
    return trees;
}

} // namespace atlas::load
