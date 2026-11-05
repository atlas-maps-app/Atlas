// The world's bend on the CPU (scene/WorldBend.hpp).
#include "scene/WorldBend.hpp"

#include "base/pymath/PyMath.hpp"

#include <algorithm>
#include <array>
#include <cmath>

namespace atlas::scene {

namespace {

constexpr double r_earth = view::earth_radius;

/// A lattice of a grid box's points a side (4 intervals).
constexpr std::size_t lattice = 5;

/// The sines and cosines of a lattice's latitudes and of its longitudes less the target's.
struct Lattice {
    std::array<double, lattice> lat{};
    std::array<double, lattice> lon{};
    std::array<double, lattice> sin_lat{};
    std::array<double, lattice> cos_lat{};
    std::array<double, lattice> sin_lon{};
    std::array<double, lattice> cos_lon{};
};

/// Below the street, as box_of's boxes reach (scene/Cells.cpp).
constexpr double below_street = -6.0;

[[nodiscard]] Lattice lattice_of(const WorldBend& b, const DegreeBox& g) noexcept {
    Lattice l;
    for (std::size_t i = 0; i < lattice; ++i) {
        const double t = static_cast<double>(i) / static_cast<double>(lattice - 1);
        l.lat.at(i) = g.north + ((g.south - g.north) * t);
        l.lon.at(i) = g.west + ((g.east - g.west) * t);
        const double phi = pymath::radians(l.lat.at(i));
        const double lam = pymath::radians(l.lon.at(i) - b.lon);
        l.sin_lat.at(i) = pymath::sin(phi);
        l.cos_lat.at(i) = pymath::cos(phi);
        l.sin_lon.at(i) = pymath::sin(lam);
        l.cos_lon.at(i) = pymath::cos(lam);
    }
    return l;
}

/// The sphere's point of latitude (sin, cos) and longitude less the target's (sin, cos), relative to the target.
[[nodiscard]] view::Vec3 offset_of(const WorldBend& b, double sp, double cp, double sl, double cl) noexcept {
    return {r_earth * cp * sl, r_earth * ((((cp * b.cos_lat) * cl) + (sp * b.sin_lat)) - 1.0),
            r_earth * (((b.sin_lat * cp) * cl) - (sp * b.cos_lat))};
}

/// The flat point of (lat, lon) degrees in `f`, dropped.
[[nodiscard]] view::Vec3 flat_of(const WorldBend& b, const view::GroundFrame& f, double lat, double lon) noexcept {
    const double x = f.x(lat, lon);
    const double z = f.z(lat, lon);
    const double dx = x - b.target.x;
    const double dz = z - b.target.z;
    return {x, -(b.drop * ((dx * dx) + (dz * dz))), z};
}

[[nodiscard]] view::Vec3 blend(const view::Vec3& a, const view::Vec3& s, double w) noexcept {
    if (w >= 1.0) return s;
    return {a.x + ((s.x - a.x) * w), a.y + ((s.y - a.y) * w), a.z + ((s.z - a.z) * w)};
}

} // namespace

WorldBend world_bend(const view::View& view, const view::GroundFrame& frame) noexcept {
    WorldBend b;
    const view::Camera& c = view.camera();
    b.drop = view.drop();
    b.sphere = view::sphere_weight(c.spec().dist);
    b.target = c.spec().target;
    b.eye = c.eye();
    b.lat = frame.kz != 0.0 ? frame.lat0 - (b.target.z / frame.kz) : frame.lat0;
    b.lon = frame.kx != 0.0 ? frame.lon0 + (b.target.x / frame.kx) : frame.lon0;
    b.sin_lat = pymath::sin(pymath::radians(b.lat));
    b.cos_lat = pymath::cos(pymath::radians(b.lat));
    return b;
}

double wrap_shift(const WorldBend& b, double lon) noexcept {
    return -360.0 * std::floor(((lon - b.lon) + 180.0) / 360.0);
}

view::Vec3 sphere_offset(const WorldBend& b, double lat, double lon) noexcept {
    const double phi = pymath::radians(lat);
    const double lam = pymath::radians(lon - b.lon);
    return offset_of(b, pymath::sin(phi), pymath::cos(phi), pymath::sin(lam), pymath::cos(lam));
}

view::Vec3 world_point(const WorldBend& b, const view::GroundFrame& f, double lat, double lon, double shift) noexcept {
    const view::Vec3 flat = flat_of(b, f, lat, lon + shift);
    if (!(b.sphere > 0.0)) return flat;
    const view::Vec3 o = sphere_offset(b, lat, lon);
    return blend(flat, {b.target.x + o.x, b.target.y + o.y, b.target.z + o.z}, b.sphere);
}

DegreeBox degrees_of(const tiles::GridBox& grid) noexcept {
    return {.north = tiles::Frame::degrees(grid.north),
            .south = tiles::Frame::degrees(grid.south),
            .west = tiles::Frame::degrees(grid.west),
            .east = tiles::Frame::degrees(grid.east)};
}

Box world_box(const WorldBend& b, const view::GroundFrame& f, const DegreeBox& g) noexcept {
    const double shift = wrap_shift(b, (g.west + g.east) / 2.0);
    const double x0 = f.x(0, g.west + shift);
    const double x1 = f.x(0, g.east + shift);
    const double z0 = f.z(g.north, 0);
    const double z1 = f.z(g.south, 0);
    if (!(b.sphere > 0.0)) {
        // an interval's nearest and farthest distance from the target, along one axis
        const auto near = [](double lo, double hi, double at) { return at < lo ? lo - at : (at > hi ? at - hi : 0.0); };
        const auto far = [](double lo, double hi, double at) { return std::max(std::abs(lo - at), std::abs(hi - at)); };
        const double nx = near(x0, x1, b.target.x);
        const double nz = near(z0, z1, b.target.z);
        const double fx = far(x0, x1, b.target.x);
        const double fz = far(z0, z1, b.target.z);
        return {.x0 = x0,
                .x1 = x1,
                .y0 = below_street - (b.drop * ((fx * fx) + (fz * fz))),
                .y1 = 0.0 - (b.drop * ((nx * nx) + (nz * nz))),
                .z0 = z0,
                .z1 = z1};
    }
    const Lattice l = lattice_of(b, g);
    Box out{.x0 = 1e300, .x1 = -1e300, .y0 = 1e300, .y1 = -1e300, .z0 = 1e300, .z1 = -1e300};
    for (std::size_t i = 0; i < lattice; ++i) {
        for (std::size_t j = 0; j < lattice; ++j) {
            const view::Vec3 flat = flat_of(b, f, l.lat.at(i), l.lon.at(j) + shift);
            const view::Vec3 o = offset_of(b, l.sin_lat.at(i), l.cos_lat.at(i), l.sin_lon.at(j), l.cos_lon.at(j));
            const view::Vec3 p = blend(flat, {b.target.x + o.x, b.target.y + o.y, b.target.z + o.z}, b.sphere);
            out.x0 = std::min(out.x0, p.x);
            out.x1 = std::max(out.x1, p.x);
            out.y0 = std::min(out.y0, p.y);
            out.y1 = std::max(out.y1, p.y);
            out.z0 = std::min(out.z0, p.z);
            out.z1 = std::max(out.z1, p.z);
        }
    }
    // between the lattice's points the sphere bulges from their chords by R(1 − cos(a/2)), a the largest angle between
    // neighbours, and the dropped ground from a straight line by the drop times a quarter of their distance squared
    const double step_lat = pymath::radians(std::abs(g.north - g.south)) / static_cast<double>(lattice - 1);
    const double step_lon = pymath::radians(std::abs(g.east - g.west)) / static_cast<double>(lattice - 1);
    const double a = std::hypot(step_lat, step_lon);
    const double bulge = r_earth * (1.0 - pymath::cos(std::min(a, 3.0) / 2.0));
    const double sx = std::abs(x1 - x0) / static_cast<double>(lattice - 1);
    const double sz = std::abs(z1 - z0) / static_cast<double>(lattice - 1);
    const double sag = b.drop * (((sx * sx) + (sz * sz)) / 4.0);
    const double pad = (b.sphere * bulge) + ((1.0 - std::min(b.sphere, 1.0)) * sag) - below_street;
    return {.x0 = out.x0 - pad,
            .x1 = out.x1 + pad,
            .y0 = out.y0 - pad,
            .y1 = out.y1 + pad,
            .z0 = out.z0 - pad,
            .z1 = out.z1 + pad};
}

bool before_horizon(const WorldBend& b, const DegreeBox& g) noexcept {
    if (b.sphere < 1.0) return true;
    // the eye from the sphere's centre, in the target's axes (the centre R below the target)
    const view::Vec3 e{b.eye.x - b.target.x, (b.eye.y - b.target.y) + r_earth, b.eye.z - b.target.z};
    const double d = std::sqrt((e.x * e.x) + (e.y * e.y) + (e.z * e.z));
    if (!(d > r_earth)) return true;
    // the box's directions from the centre on its lattice, each widened by half the largest angle between neighbours:
    // the box lies behind the horizon when each of them lies farther than the horizon's angle from the eye's direction
    const Lattice l = lattice_of(b, g);
    const double step_lat = pymath::radians(std::abs(g.north - g.south)) / static_cast<double>(lattice - 1);
    const double step_lon = pymath::radians(std::abs(g.east - g.west)) / static_cast<double>(lattice - 1);
    const double pad = std::hypot(step_lat, step_lon) / 2.0;
    const double horizon = std::acos(r_earth / d);
    const double reach = std::cos(std::min(horizon + pad, 3.14159));
    for (std::size_t i = 0; i < lattice; ++i) {
        for (std::size_t j = 0; j < lattice; ++j) {
            const view::Vec3 o = offset_of(b, l.sin_lat.at(i), l.cos_lat.at(i), l.sin_lon.at(j), l.cos_lon.at(j));
            const double cos_to_eye = ((o.x * e.x) + ((o.y + r_earth) * e.y) + (o.z * e.z)) / (r_earth * d);
            if (cos_to_eye >= reach) return true;
        }
    }
    return false;
}

} // namespace atlas::scene
