// The port's plane geometry for buildings (geometry/RoofShapes.hpp).
#include "geometry/RoofShapes.hpp"

#include "base/pymath/PyMath.hpp"

#include <array>
#include <cmath>
#include <cstdlib>
#include <limits>

namespace atlas::geometry {

namespace pm = atlas::pymath;

V3 vsub(const V3& a, const V3& b) noexcept { return {a.x - b.x, a.y - b.y, a.z - b.z}; }

double vdot(const V3& a, const V3& b) noexcept { return (a.x * b.x) + (a.y * b.y) + (a.z * b.z); }

V3 vcross(const V3& a, const V3& b) noexcept {
    return {(a.y * b.z) - (a.z * b.y), (a.z * b.x) - (a.x * b.z), (a.x * b.y) - (a.y * b.x)};
}

V3 vnorm(const V3& a) noexcept {
    const double l = pm::sqrt(vdot(a, a));
    return {a.x / l, a.y / l, a.z / l};
}

V3 neg(const V3& v) noexcept { return {-v.x, -v.y, -v.z}; }

V3 cen(std::span<const V3> q) noexcept {
    pm::Summer sx;
    pm::Summer sy;
    pm::Summer sz;
    for (const V3& p : q) {
        sx.add(p.x);
        sy.add(p.y);
        sz.add(p.z);
    }
    const auto n = static_cast<double>(q.size());
    return {sx.value() / n, sy.value() / n, sz.value() / n};
}

V2 centroid(std::span<const V2> poly) noexcept {
    pm::Summer sx;
    pm::Summer sz;
    for (const V2& p : poly) {
        sx.add(p.x);
        sz.add(p.z);
    }
    const auto n = static_cast<double>(poly.size());
    return {sx.value() / n, sz.value() / n};
}

double dist(const V2& a, const V2& b) noexcept {
    const std::array<double, 2> p{a.x, a.z};
    const std::array<double, 2> q{b.x, b.z};
    return pm::dist(p, q);
}

double dist3(const V3& a, const V3& b) noexcept {
    const std::array<double, 3> p{a.x, a.y, a.z};
    const std::array<double, 3> q{b.x, b.y, b.z};
    return pm::dist(p, q);
}

double hypot(double x, double y) noexcept { return pm::hypot(x, y); }

bool pip(double x, double z, std::span<const V2> poly) noexcept {
    bool ins = false;
    const std::size_t n = poly.size();
    if (n == 0) return false;
    std::size_t j = n - 1;
    for (std::size_t i = 0; i < n; ++i) {
        const double xi = poly[i].x;
        const double zi = poly[i].z;
        const double xj = poly[j].x;
        const double zj = poly[j].z;
        if (((zi > z) != (zj > z)) && x < (((xj - xi) * (z - zi)) / ((zj - zi) + 1e-12)) + xi) ins = !ins;
        j = i;
    }
    return ins;
}

double signed_area(std::span<const V2> poly) noexcept {
    double s = 0.0;
    const std::size_t n = poly.size();
    for (std::size_t i = 0; i < n; ++i) {
        const V2& p = poly[i];
        const V2& q = poly[(i + 1) % n];
        s += (p.x * q.z) - (q.x * p.z);
    }
    return s / 2;
}

int kt_int(double v) noexcept {
    if (std::isnan(v)) return 0;
    if (v >= 2147483647.0) return std::numeric_limits<int>::max();
    if (v <= -2147483648.0) return std::numeric_limits<int>::min();
    return static_cast<int>(v);
}

std::size_t longest_first(std::span<const V2> poly) noexcept {
    const std::size_t n = poly.size();
    std::size_t best = 0;
    double bd = dist(poly[0], poly[1 % n]);
    for (std::size_t i = 1; i < n; ++i) {
        const double dd = dist(poly[i], poly[(i + 1) % n]);
        if (dd > bd) {
            bd = dd;
            best = i;
        }
    }
    return best;
}

std::size_t longest_last(std::span<const V2> poly) noexcept {
    const std::size_t n = poly.size();
    std::size_t best = 0;
    double bd = dist(poly[0], poly[1 % n]);
    for (std::size_t i = 1; i < n; ++i) {
        const double dd = dist(poly[i], poly[(i + 1) % n]);
        if (dd >= bd) {
            bd = dd;
            best = i;
        }
    }
    return best;
}

std::array<V3, 4> quad_at(const V2& a, const V2& b, double y0, double y1, double u0, double u1, double off,
                          const V3& nrm) noexcept {
    // Living.quadAt: ax = a[0] + (b[0] - a[0]) * u0 + nrm[0] * off, in that order
    const double ax = (a.x + ((b.x - a.x) * u0)) + (nrm.x * off);
    const double az = (a.z + ((b.z - a.z) * u0)) + (nrm.z * off);
    const double bx = (a.x + ((b.x - a.x) * u1)) + (nrm.x * off);
    const double bz = (a.z + ((b.z - a.z) * u1)) + (nrm.z * off);
    return {V3{ax, y0, az}, V3{bx, y0, bz}, V3{bx, y1, bz}, V3{ax, y1, az}};
}

namespace {

/// The mitred corners of a ring whose sides moved `dl` along their normals, inward (insetRing's) or outward
/// (offsetRing's), capped at 2.6 dl (Amsterdam.mitre).
[[nodiscard]] std::vector<V2> mitre(std::span<const V2> poly, double dl, bool inward) {
    const std::size_t n = poly.size();
    const std::size_t i0 = longest_first(poly);
    const V2 a0 = poly[i0];
    const V2 c0 = poly[(i0 + 1) % n];
    double l0 = dist(a0, c0);
    if (l0 == 0.0) l0 = 1.0;
    const double side_x = ((a0.x + c0.x) / 2) - (((c0.z - a0.z) / l0) * 0.05);
    const double side_z = ((a0.z + c0.z) / 2) + (((c0.x - a0.x) / l0) * 0.05);
    const int s = pip(side_x, side_z, poly) ? 1 : -1;
    struct Line {
        double px;
        double pz;
        double dx;
        double dz;
    };
    std::vector<Line> lines;
    lines.reserve(n);
    for (std::size_t i = 0; i < n; ++i) {
        const V2 a = poly[i];
        const V2 c = poly[(i + 1) % n];
        const double ex = c.x - a.x;
        const double ez = c.z - a.z;
        double le = pm::hypot(ex, ez);
        if (le == 0.0) le = 1.0;
        if (inward) {
            const double nx = (-ez / le) * s;
            const double nz = (ex / le) * s;
            lines.push_back({.px = a.x + (nx * dl), .pz = a.z + (nz * dl), .dx = ex / le, .dz = ez / le});
        } else {
            lines.push_back(
                {.px = a.x + (((ez / le) * s) * dl), .pz = a.z - (((ex / le) * s) * dl), .dx = ex / le, .dz = ez / le});
        }
    }
    std::vector<V2> out;
    out.reserve(n);
    for (std::size_t i = 0; i < n; ++i) {
        const Line& l1 = lines[(i + n - 1) % n];
        const Line& l2 = lines[i];
        const double den = (l1.dx * l2.dz) - (l1.dz * l2.dx);
        double x = 0.0;
        double z = 0.0;
        if (std::abs(den) < 1e-4) {
            x = l2.px;
            z = l2.pz;
        } else {
            const double t = (((l2.px - l1.px) * l2.dz) - ((l2.pz - l1.pz) * l2.dx)) / den;
            x = l1.px + (l1.dx * t);
            z = l1.pz + (l1.dz * t);
        }
        const double vx = x - poly[i].x;
        const double vz = z - poly[i].z;
        const double m = pm::hypot(vx, vz);
        if (m > 2.6 * dl) {
            x = poly[i].x + (((vx / m) * 2.6) * dl);
            z = poly[i].z + (((vz / m) * 2.6) * dl);
        }
        out.emplace_back(x, z);
    }
    return out;
}

/// Whether some side of `out` runs against its side in `poly` (Amsterdam.turned).
[[nodiscard]] bool turned(std::span<const V2> poly, std::span<const V2> out) noexcept {
    const std::size_t n = poly.size();
    for (std::size_t i = 0; i < n; ++i) {
        const double e0x = poly[(i + 1) % n].x - poly[i].x;
        const double e0z = poly[(i + 1) % n].z - poly[i].z;
        const double e1x = out[(i + 1) % n].x - out[i].x;
        const double e1z = out[(i + 1) % n].z - out[i].z;
        if ((e0x * e1x) + (e0z * e1z) < 0) return true;
    }
    return false;
}

/// Whether a code point is one PyRe.isSpace counts as white space.
[[nodiscard]] bool py_space(char32_t c) noexcept {
    switch (c) {
    case U'\t':
    case U'\n':
    case U'\v':
    case U'\f':
    case U'\r':
    case U' ':
    case U'\u0085':
    case U' ':
    case U' ':
    case U' ':
    case U' ':
    case U' ':
    case U' ':
    case U'　': return true;
    default: return (c >= U'\u001C' && c <= U'\u001F') || (c >= U' ' && c <= U' ');
    }
}

/// One code point of UTF-8 text at `i` (advancing it), or U+FFFD for a broken sequence.
[[nodiscard]] char32_t next_code_point(std::string_view s, std::size_t& i) noexcept {
    const auto b0 = static_cast<unsigned char>(s[i++]);
    if (b0 < 0x80U) return b0;
    const int extra = b0 >= 0xF0U ? 3 : b0 >= 0xE0U ? 2 : b0 >= 0xC0U ? 1 : -1;
    if (extra < 0) return U'�';
    char32_t c = b0 & (0x3FU >> static_cast<unsigned>(extra));
    for (int k = 0; k < extra; ++k) {
        if (i >= s.size()) return U'�';
        c = (c << 6U) | (static_cast<unsigned char>(s[i++]) & 0x3FU);
    }
    return c;
}

/// A decimal `-?D+(.D+)?` as the double nearest it, as Java's Double.parseDouble reads it: Clinger's fast path where
/// its digits fit 2^53 and its fraction 22 places (one rounding of exact operands), else the C library's strtod
/// (correctly rounded on Bionic, glibc and Apple's libc alike; the core's locale is C).
[[nodiscard]] double decimal(std::string_view text) {
    static constexpr std::array<double, 23> powers = {1e0,  1e1,  1e2,  1e3,  1e4,  1e5,  1e6,  1e7,
                                                      1e8,  1e9,  1e10, 1e11, 1e12, 1e13, 1e14, 1e15,
                                                      1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};
    const bool negative = !text.empty() && text[0] == '-';
    std::uint64_t mantissa = 0;
    std::size_t digits = 0;
    std::size_t fraction = 0;
    bool point = false;
    for (std::size_t i = negative ? 1 : 0; i < text.size(); ++i) {
        if (text[i] == '.') {
            point = true;
            continue;
        }
        if (digits == 0 && text[i] == '0' && !point) continue; // leading zeros carry nothing
        ++digits;
        if (point) ++fraction;
        if (digits <= 19) mantissa = (mantissa * 10) + static_cast<std::uint64_t>(text[i] - '0');
    }
    if (digits <= 19 && mantissa <= (std::uint64_t{1} << 53U) && fraction < powers.size()) {
        const double v = static_cast<double>(mantissa) / powers[fraction];
        return negative ? -v : v;
    }
    const std::string copy(text);
    return std::strtod(copy.c_str(), nullptr);
}

} // namespace

std::optional<std::vector<V2>> inset_ring(std::span<const V2> poly, double dl) {
    std::vector<V2> out = mitre(poly, dl, true);
    const double so = signed_area(out);
    const double sp = signed_area(poly);
    if (so * sp <= 0 || std::abs(so) < 0.2 * std::abs(sp)) return std::nullopt;
    if (turned(poly, out)) return std::nullopt;
    for (const V2& p : out) {
        if (!pip(p.x, p.z, poly)) return std::nullopt;
    }
    return out;
}

std::optional<std::vector<V2>> offset_ring(std::span<const V2> poly, double dl) {
    std::vector<V2> out = mitre(poly, dl, false);
    if (turned(poly, out)) return std::nullopt;
    return out;
}

Ridge ridge_of(std::span<const V2> poly, bool across) noexcept {
    // render/amsterdam.py's gabled branch: the ridge along the longest side unless roof:orientation=across
    const std::size_t n = poly.size();
    const std::size_t i0 = longest_last(poly);
    const V2 a0 = poly[i0];
    const V2 b0 = poly[(i0 + 1) % n];
    double ux = b0.x - a0.x;
    double uz = b0.z - a0.z;
    const double lg = pm::hypot(ux, uz);
    ux /= lg;
    uz /= lg;
    if (across) {
        const double t = ux;
        ux = -uz;
        uz = t;
    }
    const V2 ce = centroid(poly);
    Ridge r{.ux = ux, .uz = uz, .cx = ce.x, .cz = ce.z};
    // City.pyMinOf and pyMaxOf over (p - c) · u: Python's min and max of the list, the first of equal values
    r.s0 = ((poly[0].x - ce.x) * ux) + ((poly[0].z - ce.z) * uz);
    r.s1 = r.s0;
    for (std::size_t i = 1; i < n; ++i) {
        const double pr = ((poly[i].x - ce.x) * ux) + ((poly[i].z - ce.z) * uz);
        r.s0 = pm::min(r.s0, pr);
        r.s1 = pm::max(r.s1, pr);
    }
    return r;
}

V3 on_ridge(const Ridge& r, double inset, double h, const V3& p) noexcept {
    const double sv = pm::min(r.s1 - inset, pm::max(r.s0 + inset, ((p.x - r.cx) * r.ux) + ((p.z - r.cz) * r.uz)));
    return {r.cx + (r.ux * sv), h, r.cz + (r.uz * sv)};
}

std::string strip_upper(std::string_view v) {
    std::vector<char32_t> cps;
    for (std::size_t i = 0; i < v.size();) cps.push_back(next_code_point(v, i));
    std::size_t a = 0;
    std::size_t b = cps.size();
    while (a < b && py_space(cps[a])) ++a;
    while (b > a && py_space(cps[b - 1])) --b;
    std::string out;
    for (std::size_t k = a; k < b; ++k) {
        char32_t c = cps[k];
        if (c >= U'a' && c <= U'z') c = c - U'a' + U'A';
        if (c == U'ſ') c = U'S'; // the long s upper-cases into a compass letter
        if (c < 0x80U) {
            out.push_back(static_cast<char>(c));
        } else {
            out.push_back('\x01'); // stands for any other code point: it matches no key and no digit
        }
    }
    return out;
}

std::optional<double> compass(std::string_view v) noexcept {
    static constexpr std::array<std::string_view, 16> points = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                                                                "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
    for (std::size_t i = 0; i < points.size(); ++i) {
        if (points[i] == v) return static_cast<double>(i) * 22.5;
    }
    return std::nullopt;
}

std::optional<double> leading_number(std::string_view s) {
    std::size_t i = 0;
    const std::size_t start = i;
    if (i < s.size() && s[i] == '-') ++i;
    const std::size_t d0 = i;
    while (i < s.size() && s[i] >= '0' && s[i] <= '9') ++i;
    if (i == d0) return std::nullopt;
    if (i + 1 < s.size() && s[i] == '.' && s[i + 1] >= '0' && s[i + 1] <= '9') {
        ++i;
        while (i < s.size() && s[i] >= '0' && s[i] <= '9') ++i;
    }
    return decimal(s.substr(start, i - start));
}

V2 roof_dir(std::string_view direction, std::span<const V2> poly) {
    // Amsterdam.roofDir: v = PyRe.strip(tags['roof:direction'] or '').uppercase()
    const std::string d = strip_upper(direction);
    std::optional<double> deg = compass(d);
    if (!deg && !d.empty()) deg = leading_number(d);
    if (deg) {
        const double r = pm::radians(*deg);
        return {pm::sin(r), -pm::cos(r)};
    }
    const std::size_t n = poly.size();
    const std::size_t i = longest_last(poly);
    const V2 a = poly[i];
    const V2 c = poly[(i + 1) % n];
    const V2 ce = centroid(poly);
    double nx = c.z - a.z;
    double nz = -(c.x - a.x);
    double ln = pm::hypot(nx, nz);
    if (ln == 0.0) ln = 1.0;
    nx /= ln;
    nz /= ln;
    if ((nx * (((a.x + c.x) / 2) - ce.x)) + (nz * (((a.z + c.z) / 2) - ce.z)) < 0) {
        nx = -nx;
        nz = -nz;
    }
    return {nx, nz};
}

} // namespace atlas::geometry
