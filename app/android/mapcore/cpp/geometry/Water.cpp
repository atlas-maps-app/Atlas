// The water's pieces (geometry/Water.hpp).
#include "geometry/Water.hpp"

#include "base/pymath/PyMath.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

namespace atlas::geometry {

namespace pm = atlas::pymath;

void orient_water(std::span<P2> ring, bool hole) noexcept {
    const double a = signed_area(ring);
    if (a == 0.0) return;
    if ((a > 0) != hole) return;
    std::ranges::reverse(ring);
}

void fan(std::uint32_t first, std::uint32_t count, std::vector<std::uint32_t>& out) {
    for (std::uint32_t i = 1; i + 1 < count; ++i) {
        out.push_back(first);
        out.push_back(first + i);
        out.push_back(first + i + 1);
    }
}

namespace {

/// How near a side of the tile's box both ends of an edge along it lie, metres (the packer cuts the pieces on OSM's
/// grid there, as the box's sides lie).
constexpr double side_near = 1e-3;

/// An edge of the tile's pieces, from the box's lower corner: its ends, whether it lies along a side of the box, its
/// piece's fill.
struct Bank {
    P2 a;
    P2 b;
    bool side = false;
    std::uint32_t fill = 0;
};

/// Where a ray from an edge meets the nearest other: how far along it, whether that edge lies along a side, its
/// piece's fill; at infinity where none lies within thin_most.
struct Hit {
    double s = std::numeric_limits<double>::infinity();
    bool side = false;
    std::uint32_t fill = 0;
};

/// The banks by the cells of a grid of thin_most a side over the box, each in every cell its own box meets.
class Banks {
public:
    Banks(std::span<const Bank> banks, P2 size) : banks_(banks), nx_(cells(size.x)), nz_(cells(size.z)) {
        first_.assign((nx_ * nz_) + 1, 0);
        for (const Bank& b : banks_) {
            each(b.a, b.b, [&](std::size_t c) { ++first_[c + 1]; });
        }
        for (std::size_t c = 0; c + 1 < first_.size(); ++c) first_[c + 1] += first_[c];
        items_.resize(first_.back());
        std::vector<std::uint32_t> at(first_.begin(), first_.end() - 1);
        for (std::uint32_t i = 0; i < banks_.size(); ++i) {
            each(banks_[i].a, banks_[i].b, [&](std::size_t c) { items_[at[c]++] = i; });
        }
    }

    /// The nearest bank but `self` that the ray from p along unit direction n meets within thin_most.
    [[nodiscard]] Hit cast(P2 p, P2 n, std::uint32_t self) const {
        const P2 q{.x = p.x + (n.x * thin_most), .z = p.z + (n.z * thin_most)};
        Hit best;
        each(p, q, [&](std::size_t c) {
            for (std::uint32_t k = first_[c]; k < first_[c + 1]; ++k) {
                const std::uint32_t i = items_[k];
                if (i == self) continue;
                const Bank& b = banks_[i];
                const P2 e{.x = b.b.x - b.a.x, .z = b.b.z - b.a.z};
                const double d = (n.x * e.z) - (n.z * e.x);
                if (d == 0.0) continue;
                const P2 w{.x = b.a.x - p.x, .z = b.a.z - p.z};
                const double s = ((w.x * e.z) - (w.z * e.x)) / d;
                const double u = ((w.x * n.z) - (w.z * n.x)) / d;
                if (!(s > 0.0) || s > thin_most || s >= best.s || u < 0.0 || u > 1.0) continue;
                best = {.s = s, .side = b.side, .fill = b.fill};
            }
        });
        return best;
    }

private:
    [[nodiscard]] static std::size_t cells(double extent) {
        return extent > 0.0 ? std::max<std::size_t>(1, static_cast<std::size_t>(std::ceil(extent / thin_most))) : 1;
    }

    [[nodiscard]] static std::size_t cell(double v, std::size_t n) {
        const double c = std::floor(v / thin_most);
        if (!(c > 0.0)) return 0;
        return std::min(n - 1, static_cast<std::size_t>(c));
    }

    /// Each cell the box of the segment from a to b meets.
    template <typename F>
    void each(P2 a, P2 b, const F& f) const {
        const std::size_t x0 = cell(pm::min(a.x, b.x), nx_);
        const std::size_t x1 = cell(pm::max(a.x, b.x), nx_);
        const std::size_t z0 = cell(pm::min(a.z, b.z), nz_);
        const std::size_t z1 = cell(pm::max(a.z, b.z), nz_);
        for (std::size_t z = z0; z <= z1; ++z) {
            for (std::size_t x = x0; x <= x1; ++x) f((z * nx_) + x);
        }
    }

    std::span<const Bank> banks_;
    std::size_t nx_ = 1;
    std::size_t nz_ = 1;
    std::vector<std::uint32_t> first_;
    std::vector<std::uint32_t> items_;
};

/// The stretch from a to b of bank `self` (from the box's lower corner `lo`): kept whole, or halved (thin_edges).
void stretch(const Banks& banks, P2 a, P2 b, std::uint32_t self, std::uint32_t fill, int halvings, P2 lo,
             std::vector<ThinEdge>& out) {
    const P2 e{.x = b.x - a.x, .z = b.z - a.z};
    const double l = std::sqrt((e.x * e.x) + (e.z * e.z));
    if (!(l > 0.0)) return;
    // into the water: the left of the edge's direction
    const P2 n{.x = -e.z / l, .z = e.x / l};
    const auto across = [&](double u) { return banks.cast({.x = a.x + (u * e.x), .z = a.z + (u * e.z)}, n, self); };
    const Hit h0 = across(0.1);
    const Hit h1 = across(0.5);
    const Hit h2 = across(0.9);
    const auto met = [&](const Hit& h) { return h.s <= thin_most && !h.side && h.fill == h0.fill; };
    if (met(h0) && met(h1) && met(h2)) {
        const double mean = 0.5 * (h0.s + h2.s);
        if (std::abs(h1.s - mean) <= pm::max(thin_slack, thin_slack_share * mean)) {
            out.push_back({.a = {.x = a.x + lo.x, .z = a.z + lo.z},
                           .b = {.x = b.x + lo.x, .z = b.z + lo.z},
                           .ta = h0.s,
                           .tb = h2.s,
                           .fill = fill,
                           .other = h0.fill});
            return;
        }
    }
    // wide water all along it (no bank within thin_most from any of the three) is not halved
    const auto near = [](const Hit& h) { return h.s <= thin_most; };
    if (halvings == 0 || l < 2 * thin_least || !(near(h0) || near(h1) || near(h2))) return;
    const P2 m{.x = a.x + (0.5 * e.x), .z = a.z + (0.5 * e.z)};
    stretch(banks, a, m, self, fill, halvings - 1, lo, out);
    stretch(banks, m, b, self, fill, halvings - 1, lo, out);
}

} // namespace

std::vector<ThinEdge> thin_edges(std::span<const P2> points, std::span<const ThinRing> rings, P2 lo, P2 hi) {
    const P2 size{.x = hi.x - lo.x, .z = hi.z - lo.z};
    const bool boxed = size.x > 0.0 && size.z > 0.0;
    const auto along = [](double a, double b, double side) {
        return std::abs(a - side) <= side_near && std::abs(b - side) <= side_near;
    };
    // the pieces' edges from the box's lower corner, those along its sides marked
    std::vector<Bank> banks;
    for (const ThinRing& r : rings) {
        if (r.count < 3) continue;
        for (std::uint32_t k = 0; k < r.count; ++k) {
            const P2& p = points[r.first + k];
            const P2& q = points[r.first + ((k + 1) % r.count)];
            const P2 a{.x = p.x - lo.x, .z = p.z - lo.z};
            const P2 b{.x = q.x - lo.x, .z = q.z - lo.z};
            if (a == b) continue;
            const bool side = boxed && (along(a.x, b.x, 0.0) || along(a.x, b.x, size.x) || along(a.z, b.z, 0.0) ||
                                        along(a.z, b.z, size.z));
            banks.push_back({.a = a, .b = b, .side = side, .fill = r.fill});
        }
    }
    const Banks grid(banks, size);
    std::vector<ThinEdge> out;
    for (std::uint32_t i = 0; i < banks.size(); ++i) {
        const Bank& b = banks[i];
        if (!b.side) stretch(grid, b.a, b.b, i, b.fill, thin_halvings, lo, out);
    }
    return out;
}

} // namespace atlas::geometry
