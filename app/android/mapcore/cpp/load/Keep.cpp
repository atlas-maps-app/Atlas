// Keep codes of a ring (load/Keep.hpp).
#include "load/Keep.hpp"

#include "tiles/Schema.hpp"

#include <cmath>
#include <cstddef>
#include <limits>

namespace atlas::load {

namespace {

/// One segment of the split tree still to walk: idx[a..b], under the smallest chord distance above it.
struct Segment {
    std::size_t a = 0;
    std::size_t b = 0;
    double bound = 0.0;
};

/// KeepCodes.split over the indexes `idx`: each inner vertex the tree splits at marked with the smallest chord
/// distance on its way down.
void split(std::span<const geometry::V2> pts, std::span<const std::size_t> idx, std::vector<double>& m,
           std::vector<Segment>& stack) {
    stack.clear();
    stack.push_back({.a = 0, .b = idx.size() - 1, .bound = std::numeric_limits<double>::infinity()});
    while (!stack.empty()) {
        const Segment s = stack.back();
        stack.pop_back();
        if (s.b - s.a + 1 < 3) continue;
        const geometry::V2 p0 = pts[idx[s.a]];
        const geometry::V2 p1 = pts[idx[s.b]];
        const double dx = p1.x - p0.x;
        const double dz = p1.z - p0.z;
        double l = geometry::hypot(dx, dz);
        if (l == 0.0) l = 1e-9;
        double dmax = 0.0;
        std::size_t k = s.a;
        for (std::size_t i = s.a + 1; i < s.b; ++i) {
            const geometry::V2 p = pts[idx[i]];
            const double dd = std::fabs(((p.x - p0.x) * dz) - ((p.z - p0.z) * dx)) / l;
            if (dd > dmax) {
                dmax = dd;
                k = i;
            }
        }
        // simplify keeps only the segment's first vertex where dmax <= tol: no split at a tolerance of dmax or more
        if (dmax <= 0.0) continue;
        const double t = dmax < s.bound ? dmax : s.bound;
        m[idx[k]] = t;
        stack.push_back({.a = s.a, .b = k, .bound = t});
        stack.push_back({.a = k, .b = s.b, .bound = t});
    }
}

} // namespace

std::vector<double> keep_thresholds(std::span<const geometry::V2> pts) {
    const std::size_t n = pts.size();
    std::vector<double> m(n, 0.0);
    if (n == 0) return m;
    std::size_t far = 0;
    double best = geometry::dist(pts[0], pts[0]);
    for (std::size_t i = 0; i < n; ++i) {
        const double dd = geometry::dist(pts[0], pts[i]);
        if (dd > best) {
            best = dd;
            far = i;
        }
    }
    m[0] = std::numeric_limits<double>::infinity();
    m[far] = std::numeric_limits<double>::infinity();
    // the two halves as simplify cuts them: pts[0..far], and pts[far..n) followed by pts[0]
    std::vector<std::size_t> first(far + 1);
    for (std::size_t i = 0; i <= far; ++i) first[i] = i;
    std::vector<std::size_t> second(n - far + 1);
    for (std::size_t i = 0; i < n - far; ++i) second[i] = far + i;
    second.back() = 0;
    std::vector<Segment> stack;
    split(pts, first, m, stack);
    split(pts, second, m, stack);
    return m;
}

std::vector<std::uint8_t> keep_codes(std::span<const geometry::V2> pts) {
    if (pts.size() < tiles::keep::min_points) {
        std::vector<std::uint8_t> whole(pts.size(), tiles::keep::all); // not braces: a list of two
        return whole;
    }
    const std::vector<double> m = keep_thresholds(pts);
    std::vector<std::uint8_t> codes(pts.size());
    for (std::size_t i = 0; i < pts.size(); ++i) {
        std::uint8_t c = 0;
        for (std::size_t j = 1; j < tiles::keep::tolerances.size(); ++j) {
            if (tiles::keep::tolerances[j] < m[i]) ++c;
        }
        codes[i] = c;
    }
    return codes;
}

} // namespace atlas::load
