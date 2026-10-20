// The port's plane geometry for buildings (plan-vector D2 "What the core builds", K10): the vector helpers of
// render/city.py and render/real.py (R/City.kt, R/Real.kt) and the shape rules of render/amsterdam.py's
// _draw_building that do not depend on the camera: a footprint's longest side, a gable roof's ridge and its projection
// of the eaves, a mansard's inset ring, a skillion's downhill direction, Living.quadAt. Every value is computed with
// the port's operations in its order, contraction off: pymath's sqrt, hypot and dist, Python's sum (pymath::Summer),
// Python's min and max, Kotlin's Double.toInt(); so each point and normal is the port's bit for bit (K8's sun samples,
// sun/Samples.cpp, compute the same points with the same helpers, and the tests hold the two equal).
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::geometry {

/// A point of the ground, metres in the pack's frame: x east, z south (the port's V2 of x and z).
struct V2 {
    double x = 0.0;
    double z = 0.0;

    constexpr V2() noexcept = default;
    constexpr V2(double px, double pz) noexcept : x(px), z(pz) {}
    friend constexpr bool operator==(const V2&, const V2&) = default;
};

/// A point or a direction in space, metres: x east, y up, z south (the port's V3).
struct V3 {
    double x = 0.0;
    double y = 0.0;
    double z = 0.0;

    constexpr V3() noexcept = default;
    constexpr V3(double px, double py, double pz) noexcept : x(px), y(py), z(pz) {}
    friend constexpr bool operator==(const V3&, const V3&) = default;
};

/// City.vsub, vdot (summed left to right), vcross, vnorm (an IEEE square root), and the negation.
[[nodiscard]] V3 vsub(const V3& a, const V3& b) noexcept;
[[nodiscard]] double vdot(const V3& a, const V3& b) noexcept;
[[nodiscard]] V3 vcross(const V3& a, const V3& b) noexcept;
[[nodiscard]] V3 vnorm(const V3& a) noexcept;
[[nodiscard]] V3 neg(const V3& v) noexcept;

/// `tuple(sum(p[k] for p in q) / len(q) for k in range(3))`: each coordinate's Python sum, over the count.
[[nodiscard]] V3 cen(std::span<const V3> q) noexcept;

/// Real.centroid: the Python sums of x and of z over the ring, over its size.
[[nodiscard]] V2 centroid(std::span<const V2> poly) noexcept;

/// PyMath.dist of two points of the ground, and of two points in space.
[[nodiscard]] double dist(const V2& a, const V2& b) noexcept;
[[nodiscard]] double dist3(const V3& a, const V3& b) noexcept;

/// PyMath.hypot.
[[nodiscard]] double hypot(double x, double y) noexcept;

/// Real.pip: the even-odd rule over a ring, its arithmetic in its order.
[[nodiscard]] bool pip(double x, double z, std::span<const V2> poly) noexcept;

/// Real.signedArea: the shoelace sum over the ring, halved.
[[nodiscard]] double signed_area(std::span<const V2> poly) noexcept;

/// Kotlin's Double.toInt(): truncation toward zero, NaN to 0, saturating at Int's limits.
[[nodiscard]] int kt_int(double v) noexcept;

/// `max(range(n), key=lambda i: dist(poly[i], poly[i + 1]))`: the first longest side.
[[nodiscard]] std::size_t longest_first(std::span<const V2> poly) noexcept;

/// `max((dist(poly[i], poly[i + 1]), i) for i in range(n))[1]`: the longest side, the last of equals.
[[nodiscard]] std::size_t longest_last(std::span<const V2> poly) noexcept;

/// Living.quadAt: the wall's rectangle from u0 to u1 along a → b and y0 to y1, moved `off` along `nrm`; its corners in
/// the port's order (a at y0, b at y0, b at y1, a at y1).
[[nodiscard]] std::array<V3, 4> quad_at(const V2& a, const V2& b, double y0, double y1, double u0, double u1,
                                        double off = 0.0, const V3& nrm = {}) noexcept;

/// Amsterdam.insetRing: the ring moved `dl` inward (its sides along their normals, mitred, capped at 2.6 dl), or
/// nothing where it would be too thin or notched (a mansard's top, m2).
[[nodiscard]] std::optional<std::vector<V2>> inset_ring(std::span<const V2> poly, double dl);

/// A gable or hipped roof's ridge, as _draw_building lays it out without a tradition: along the longest side (the last
/// of equals), across it where roof:orientation says so; the centroid, and the footprint's extent along the ridge.
struct Ridge {
    double ux = 0.0; ///< the ridge's direction, a unit vector of the ground
    double uz = 0.0;
    double cx = 0.0; ///< the footprint's centroid
    double cz = 0.0;
    double s0 = 0.0; ///< the least and greatest projection of a footprint vertex on the ridge's line
    double s1 = 0.0;
};

/// The ridge of `poly`, its direction turned a quarter where `across` (roof:orientation=across).
[[nodiscard]] Ridge ridge_of(std::span<const V2> poly, bool across) noexcept;

/// `onRidge(p)`: the eave point p projected onto the ridge's line at height h, clamped `inset` within its ends.
[[nodiscard]] V3 on_ridge(const Ridge& r, double inset, double h, const V3& p) noexcept;

/// Amsterdam.roofDir: a skillion's downhill direction, roof:direction (`direction`, the tag's value or empty) when it
/// names a compass point or a number of degrees, else out through the longest wall (the last of equals).
[[nodiscard]] V2 roof_dir(std::string_view direction, std::span<const V2> poly);

/// `PyRe.strip(v).uppercase()` as far as roof:direction's compass and numbers can tell (sun/Samples.cpp's rule).
[[nodiscard]] std::string strip_upper(std::string_view v);

/// Amsterdam.COMPASS: the sixteen points of the compass, 22.5° apart from north; nothing for another value.
[[nodiscard]] std::optional<double> compass(std::string_view v) noexcept;

/// Real.num over an upper-cased, stripped value: `re.match(r'\s*(-?\d+(?:\.\d+)?)')` as a double, or nothing.
[[nodiscard]] std::optional<double> leading_number(std::string_view s);

} // namespace atlas::geometry
