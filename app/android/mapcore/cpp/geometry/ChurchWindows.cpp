// A church's own windows (geometry/ChurchWindows.hpp), in render/closeup.py's arithmetic.
#include "geometry/ChurchWindows.hpp"

#include "base/pymath/PyMath.hpp"

#include <cstdint>
#include <utility>

namespace atlas::geometry {

std::vector<ChurchWindow> church_windows(V2 a, V2 b, double y0, double top, V2 nrm) {
    std::vector<ChurchWindow> out;
    const double ex = b.x - a.x;
    const double ez = b.z - a.z;
    const double elen = pymath::hypot(ex, ez);
    const double hgt = top - y0;
    if (hgt < 6 || elen < 3) return out;
    // nb = max(1, int(elen / 6.2)): a bay of about six metres each
    const auto bays = static_cast<std::int64_t>(elen / 6.2);
    const std::int64_t nb = bays > 1 ? bays : 1;
    const double bw = elen / static_cast<double>(nb);
    const double wh = pymath::min(1.1, bw * 0.2); // half the window's width
    const double ys = y0 + pymath::min(3.2, hgt * 0.22);
    const double ye = top - pymath::min(2.4, hgt * 0.14) - wh;
    if (ye - ys < 2) return out;
    const double ux = ex / elen;
    const double uz = ez / elen;
    const auto at = [&](double u, double y, double off) {
        return V3((a.x + (ux * u)) + (nrm.x * off), y, (a.z + (uz * u)) + (nrm.z * off));
    };
    const auto shape = [&](double uc, double off, double grow) {
        const double w = wh + grow;
        std::array<V3, arch_points> pts{};
        pts[0] = at(uc - w, ys - grow, off);
        pts[1] = at(uc + w, ys - grow, off);
        for (std::size_t k = 0; k < 9; ++k) {
            const double t = pymath::pi * static_cast<double>(k) / 8;
            pts[2 + k] = at(uc + (w * pymath::cos(t)), ye + (w * pymath::sin(t)), off);
        }
        return pts;
    };
    out.reserve(static_cast<std::size_t>(nb));
    for (std::int64_t k = 0; k < nb; ++k) {
        const double uc = (static_cast<double>(k) + 0.5) * bw;
        ChurchWindow window{
            .stone = shape(uc, 0.0, 0.22), .reveal = shape(uc, 0.0, 0.0), .glass = shape(uc, -0.38, 0.0)};
        window.lead.push_back({at(uc, ys, -0.37), at(uc, ye + wh, -0.37)});
        // the transoms as the renders step them: y += 1.5 from the sill while under the spring
        double y = ys + 1.5;
        while (y < ye) {
            window.lead.push_back({at(uc - wh, y, -0.37), at(uc + wh, y, -0.37)});
            y += 1.5;
        }
        out.push_back(std::move(window));
    }
    return out;
}

} // namespace atlas::geometry
