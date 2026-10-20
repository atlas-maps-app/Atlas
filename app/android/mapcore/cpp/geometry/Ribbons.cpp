// The renders' line geometry on the ground (geometry/Ribbons.hpp): city.py and regions.py line for line, in the port's
// order (R/City.kt ribbon, R/Regions.kt offsetLine, dashes and drawOver's near, R/Real.kt signedArea and centroid).
#include "geometry/Ribbons.hpp"

#include "base/pymath/PyMath.hpp"

#include <array>
#include <cmath>

namespace atlas::geometry {

namespace pm = atlas::pymath;

void ribbon(std::span<const P2> pts, double w, std::vector<P2>& out) {
    const std::size_t n = pts.size();
    const std::size_t at = out.size();
    out.resize(at + (2 * n));
    for (std::size_t i = 0; i < n; ++i) {
        double dx = 0;
        double dz = 0;
        if (i == 0) {
            dx = pts[1].x - pts[0].x;
            dz = pts[1].z - pts[0].z;
        } else if (i == n - 1) {
            dx = pts[n - 1].x - pts[n - 2].x;
            dz = pts[n - 1].z - pts[n - 2].z;
        } else {
            dx = pts[i + 1].x - pts[i - 1].x;
            dz = pts[i + 1].z - pts[i - 1].z;
        }
        double l = pm::hypot(dx, dz);
        if (l == 0.0) l = 1.0; // `math.hypot(*d) or 1`
        const double nx = -dz / l;
        const double nz = dx / l;
        // pts[i] + nx * w / 2: the product, then the half, then the sum
        out[at + i] = {.x = pts[i].x + ((nx * w) / 2), .z = pts[i].z + ((nz * w) / 2)};
        out[at + (2 * n) - 1 - i] = {.x = pts[i].x - ((nx * w) / 2), .z = pts[i].z - ((nz * w) / 2)};
    }
}

void offset_line(std::span<const P2> pts, double off, std::vector<P2>& out) {
    const std::size_t n = pts.size();
    for (std::size_t i = 0; i < n; ++i) {
        const P2& p = pts[i];
        const P2& a = pts[i == 0 ? 0 : i - 1];
        const P2& b = pts[i + 1 < n ? i + 1 : n - 1];
        const double dx = b.x - a.x;
        const double dz = b.z - a.z;
        double l = pm::hypot(dx, dz);
        if (l == 0.0) l = 1.0; // `math.hypot(dx, dz) or 1`
        // p[0] - dz / L * off: the quotient, then the product, then the difference
        out.push_back({p.x - ((dz / l) * off), p.z + ((dx / l) * off)});
    }
}

void dashes(std::span<const P2> pts, double on, double off, std::vector<Dash>& out) {
    double s0 = 0.0;
    const double period = on + off;
    for (std::size_t i = 0; i + 1 < pts.size(); ++i) {
        const P2& a = pts[i];
        const P2& b = pts[i + 1];
        const std::array<double, 2> pa{a.x, a.z};
        const std::array<double, 2> pb{b.x, b.z};
        const double l = pm::dist(pa, pb);
        if (l < 1e-6) continue;
        double k = pm::floor(s0 / period) * period; // dash starts at multiples of the period, in line distance
        while (k < s0 + l) {
            const double d0 = pm::max(k, s0);
            const double d1 = pm::min(k + on, s0 + l);
            if (d1 > d0) {
                const double f0 = (d0 - s0) / l;
                const double f1 = (d1 - s0) / l;
                out.push_back({.a = {.x = a.x + ((b.x - a.x) * f0), .z = a.z + ((b.z - a.z) * f0)},
                               .b = {.x = a.x + ((b.x - a.x) * f1), .z = a.z + ((b.z - a.z) * f1)}});
            }
            k += period;
        }
        s0 += l;
    }
}

bool near_radius(std::span<const P2> pts, double tx, double tz, double radius) noexcept {
    const std::size_t n = pts.size();
    if (n == 0) return false;
    const std::size_t step = n / 8 > 1 ? n / 8 : 1;
    auto within = [&](const P2& p) { return !(pm::hypot(p.x - tx, p.z - tz) > radius); };
    for (std::size_t i = 0; i < n; i += step) {
        if (within(pts[i])) return true;
    }
    return within(pts[n - 1]);
}

std::array<double, 4> near_box(std::span<const P2> pts) noexcept {
    const std::size_t n = pts.size();
    if (n == 0) return {0, -1, 0, 0};
    const std::size_t step = n / 8 > 1 ? n / 8 : 1;
    std::array<double, 4> b{pts[0].x, pts[0].x, pts[0].z, pts[0].z};
    auto add = [&](const P2& p) {
        b = {pm::min(b[0], p.x), pm::max(b[1], p.x), pm::min(b[2], p.z), pm::max(b[3], p.z)};
    };
    for (std::size_t i = 0; i < n; i += step) add(pts[i]);
    add(pts[n - 1]);
    return b;
}

double signed_area(std::span<const P2> ring) noexcept {
    double s = 0.0; // `s = 0; s += ...`: plain float addition in order, whatever the Python
    const std::size_t n = ring.size();
    for (std::size_t i = 0; i < n; ++i) {
        const P2& p = ring[i];
        const P2& q = ring[(i + 1) % n];
        s += (p.x * q.z) - (q.x * p.z);
    }
    return s / 2;
}

P2 centroid(std::span<const P2> ring) noexcept {
    pm::Summer sx;
    pm::Summer sz;
    for (const P2& p : ring) {
        sx.add(p.x);
        sz.add(p.z);
    }
    const auto n = static_cast<double>(ring.size());
    return {.x = sx.value() / n, .z = sz.value() / n};
}

} // namespace atlas::geometry
