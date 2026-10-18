#include "sun/Samples.hpp"

#include "base/assert.hpp"
#include "base/pymath/PyMath.hpp"
#include "tiles/Simplify.hpp"
#include "tiles/layers/Buildings.hpp"
#include "tiles/layers/Ground.hpp"
#include "tiles/layers/Lines.hpp"
#include "tiles/layers/Points.hpp"
#include "tiles/layers/Water.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <limits>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>

namespace atlas::sun {

namespace {

namespace pm = atlas::pymath;
namespace tl = atlas::tiles;

// ---------------------------------------------------------------- the port's vectors (render/city.py, real.py)

/// A point of the ground (the port's V2 of x and z).
struct V2 {
    double x = 0.0;
    double z = 0.0;

    constexpr V2() noexcept = default;
    constexpr V2(double px, double pz) noexcept : x(px), z(pz) {}
};

/// A point or a direction in space (the port's V3).
struct V3 {
    double x = 0.0;
    double y = 0.0;
    double z = 0.0;

    constexpr V3() noexcept = default;
    constexpr V3(double px, double py, double pz) noexcept : x(px), y(py), z(pz) {}
};

[[nodiscard]] V3 vsub(const V3& a, const V3& b) noexcept { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
[[nodiscard]] double vdot(const V3& a, const V3& b) noexcept { return (a.x * b.x) + (a.y * b.y) + (a.z * b.z); }
[[nodiscard]] V3 vcross(const V3& a, const V3& b) noexcept {
    return {(a.y * b.z) - (a.z * b.y), (a.z * b.x) - (a.x * b.z), (a.x * b.y) - (a.y * b.x)};
}
[[nodiscard]] V3 vnorm(const V3& a) noexcept {
    const double l = pm::sqrt(vdot(a, a));
    return {a.x / l, a.y / l, a.z / l};
}
[[nodiscard]] V3 neg(const V3& v) noexcept { return {-v.x, -v.y, -v.z}; }

/// `cen(q)`: each coordinate's Python sum over the points, divided by their count.
[[nodiscard]] V3 cen(std::span<const V3> q) noexcept {
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

/// Real.centroid: the Python sums of x and of z over the ring, divided by its size.
[[nodiscard]] V2 centroid(std::span<const V2> poly) noexcept {
    pm::Summer sx;
    pm::Summer sz;
    for (const V2& p : poly) {
        sx.add(p.x);
        sz.add(p.z);
    }
    const auto n = static_cast<double>(poly.size());
    return {sx.value() / n, sz.value() / n};
}

/// PyMath.dist of two points of the ground.
[[nodiscard]] double dist(const V2& a, const V2& b) noexcept {
    const std::array<double, 2> p{a.x, a.z};
    const std::array<double, 2> q{b.x, b.z};
    return pm::dist(p, q);
}

/// PyMath.dist of two points in space.
[[nodiscard]] double dist3(const V3& a, const V3& b) noexcept {
    const std::array<double, 3> p{a.x, a.y, a.z};
    const std::array<double, 3> q{b.x, b.y, b.z};
    return pm::dist(p, q);
}

/// Real.pip: the even-odd rule over a ring, its arithmetic in its order.
[[nodiscard]] bool pip(double x, double z, std::span<const V2> poly) noexcept {
    bool ins = false;
    const std::size_t n = poly.size();
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

/// Real.signedArea.
[[nodiscard]] double signed_area(std::span<const V2> poly) noexcept {
    double s = 0.0;
    const std::size_t n = poly.size();
    for (std::size_t i = 0; i < n; ++i) {
        const V2& p = poly[i];
        const V2& q = poly[(i + 1) % n];
        s += (p.x * q.z) - (q.x * p.z);
    }
    return s / 2;
}

/// Kotlin's Double.toInt(): truncation toward zero, NaN to 0, saturating at Int's limits.
[[nodiscard]] int kt_int(double v) noexcept {
    if (std::isnan(v)) return 0;
    if (v >= 2147483647.0) return std::numeric_limits<int>::max();
    if (v <= -2147483648.0) return std::numeric_limits<int>::min();
    return static_cast<int>(v);
}

/// `max(range(n), key=lambda i: dist(poly[i], poly[i + 1]))`: the first longest side.
[[nodiscard]] std::size_t longest_first(std::span<const V2> poly) noexcept {
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

/// `max((dist(poly[i], poly[i + 1]), i) for i in range(n))[1]`: the longest side, the last of equals.
[[nodiscard]] std::size_t longest_last(std::span<const V2> poly) noexcept {
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

/// The mitred corners of a ring whose sides moved `dl` along their normals, capped at 2.6 dl (Amsterdam.mitre, the
/// inward case: inset_ring's).
[[nodiscard]] std::vector<V2> mitre_inward(std::span<const V2> poly, double dl) {
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
        const double nx = (-ez / le) * s;
        const double nz = (ex / le) * s;
        lines.push_back({.px = a.x + (nx * dl), .pz = a.z + (nz * dl), .dx = ex / le, .dz = ez / le});
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

/// Amsterdam.insetRing: the ring moved `dl` inward, or nothing where it is too thin or too notched.
[[nodiscard]] std::optional<std::vector<V2>> inset_ring(std::span<const V2> poly, double dl) {
    std::vector<V2> out = mitre_inward(poly, dl);
    const double so = signed_area(out);
    const double sp = signed_area(poly);
    if (so * sp <= 0 || std::abs(so) < 0.2 * std::abs(sp)) return std::nullopt;
    if (turned(poly, out)) return std::nullopt;
    for (const V2& p : out) {
        if (!pip(p.x, p.z, poly)) return std::nullopt;
    }
    return out;
}

// ---------------------------------------------------------------- tags as the port reads them

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
    case U'\u00A0':
    case U'\u1680':
    case U'\u2028':
    case U'\u2029':
    case U'\u202F':
    case U'\u205F':
    case U'\u3000': return true;
    default: return (c >= U'\u001C' && c <= U'\u001F') || (c >= U'\u2000' && c <= U'\u200A');
    }
}

/// One code point of UTF-8 text at `i` (advancing it), or U+FFFD for a broken sequence.
[[nodiscard]] char32_t next_code_point(std::string_view s, std::size_t& i) noexcept {
    const auto b0 = static_cast<unsigned char>(s[i++]);
    if (b0 < 0x80U) return b0;
    const int extra = b0 >= 0xF0U ? 3 : b0 >= 0xE0U ? 2 : b0 >= 0xC0U ? 1 : -1;
    if (extra < 0) return U'\uFFFD';
    char32_t c = b0 & (0x3FU >> static_cast<unsigned>(extra));
    for (int k = 0; k < extra; ++k) {
        if (i >= s.size()) return U'\uFFFD';
        c = (c << 6U) | (static_cast<unsigned char>(s[i++]) & 0x3FU);
    }
    return c;
}

/// `PyRe.strip(v).uppercase()` as far as COMPASS and Real.num can tell: Python's white space stripped, ASCII letters
/// upper-cased, the long s (U+017F) as S (the one letter outside ASCII that upper-cases into a compass letter), and
/// every other code point as one byte that is no key's and no digit (the port would read a digit of another script as a
/// number: no roof:direction holds one).
[[nodiscard]] std::string strip_upper(std::string_view v) {
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
        if (c == U'\u017F') c = U'S';
        if (c < 0x80U) {
            out.push_back(static_cast<char>(c));
        } else {
            out.push_back('\x01'); // stands for any other code point: it matches no key and no digit
        }
    }
    return out;
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

/// Real.num over an upper-cased, stripped value: `re.match(r'\s*(-?\d+(?:\.\d+)?)')` as a double, or nothing.
[[nodiscard]] std::optional<double> leading_number(std::string_view s) noexcept {
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

/// Amsterdam.COMPASS: the sixteen points of the compass, 22.5° apart from north.
[[nodiscard]] std::optional<double> compass(std::string_view v) noexcept {
    static constexpr std::array<std::string_view, 16> points = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                                                                "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
    for (std::size_t i = 0; i < points.size(); ++i) {
        if (points[i] == v) return static_cast<double>(i) * 22.5;
    }
    return std::nullopt;
}

// ---------------------------------------------------------------- a building's values at a level

/// The slots of a building's attributes in a layer (the building and part layers, or the variant layer).
struct Slots {
    std::uint8_t h;
    std::uint8_t min;
    std::uint8_t roof_h;
    std::uint8_t levels;
    std::uint8_t roof;
    std::uint8_t orient;
    std::uint8_t mat_draw;
    std::uint8_t hidden;
    std::uint8_t no_win;
    std::uint8_t flags;
    std::uint8_t tag_building;
    std::uint8_t tag_part;
    std::uint8_t tag_direction;
};

template <class Columns>
consteval Slots slots_in(const Columns& c) {
    return {tl::slot_of(c, "h"),
            tl::slot_of(c, "min"),
            tl::slot_of(c, "roof_h"),
            tl::slot_of(c, "levels"),
            tl::slot_of(c, "roof"),
            tl::slot_of(c, "orient"),
            tl::slot_of(c, "mat_draw"),
            tl::slot_of(c, "hidden"),
            tl::slot_of(c, "no_win"),
            tl::slot_of(c, "flags"),
            tl::slot_of(c, "tag:building"),
            tl::slot_of(c, "tag:building:part"),
            tl::slot_of(c, "tag:roof:direction")};
}

constexpr Slots record_slots = slots_in(tl::building::columns);
constexpr Slots variant_slots = slots_in(tl::variant::columns);

/// A building's or part's values as the scene at the level holds them. A null height or roof height reads 0, so its
/// Python truth is `!= 0` (NaN true, as Python's).
struct Values {
    bool has_h = false; ///< draw_building reads h: a building without one is never drawn
    double h = 0.0;
    double min = 0.0;
    double roof_h = 0.0;
    bool has_levels = false;
    std::int64_t levels = 0;
    std::optional<std::string_view> roof;
    std::optional<std::string_view> orient;
    std::optional<std::string_view> mat_draw;
    std::optional<std::string_view> btype;
    bool part_tag = false;
    std::optional<std::string_view> direction;
    bool hidden = false;
    bool no_win = false;
    std::uint64_t flags = 0;
};

[[nodiscard]] Values values_of(const tl::Layer& l, const Slots& s, std::size_t i) noexcept {
    Values v;
    const std::optional<double> h = l.decimal(s.h, i);
    v.has_h = h.has_value();
    v.h = h.value_or(0.0);
    v.min = l.decimal(s.min, i).value_or(0.0);
    v.roof_h = l.decimal(s.roof_h, i).value_or(0.0);
    const std::optional<std::int64_t> levels = l.nullable_integer(s.levels, i);
    v.has_levels = levels.has_value();
    v.levels = levels.value_or(0);
    v.roof = l.string(s.roof, i);
    v.orient = l.string(s.orient, i);
    v.mat_draw = l.string(s.mat_draw, i);
    v.btype = l.string(s.tag_building, i);
    v.part_tag = l.string(s.tag_part, i).has_value();
    v.direction = l.string(s.tag_direction, i);
    v.hidden = l.boolean(s.hidden, i).value_or(false);
    v.no_win = l.boolean(s.no_win, i).value_or(false);
    v.flags = static_cast<std::uint64_t>(l.integer(s.flags, i));
    return v;
}

[[nodiscard]] bool is(const std::optional<std::string_view>& v, std::string_view s) noexcept {
    return v.has_value() && *v == s;
}

/// A ring at the level, in metres in the pack's frame.
void ring_at(const tl::RingView& ring, std::uint8_t level, const tl::Frame& frame, std::vector<std::uint32_t>& kept,
             std::vector<V2>& out) {
    kept.resize(ring.size());
    const tl::Simplified s = tl::simplify(ring, level, frame, kept);
    out.clear();
    for (std::size_t k = 0; k < s.count; ++k) out.emplace_back(frame.x(ring.lon[kept[k]]), frame.z(ring.lat[kept[k]]));
}

// ---------------------------------------------------------------- the samples of one building

constexpr std::array<std::string_view, 5> pitched = {"gabled", "pyramidal", "hipped", "skillion", "mansard"};
constexpr std::array<std::string_view, 5> reflected = {"gabled", "hipped", "skillion", "quadruple_saltbox",
                                                       "pyramidal"};
constexpr std::array<std::string_view, 4> paris_fronts = {"limestone", "plaster", "stone", "sandstone"};

template <std::size_t N>
[[nodiscard]] bool in(const std::optional<std::string_view>& v, const std::array<std::string_view, N>& set) noexcept {
    return v.has_value() && std::ranges::find(set, *v) != set.end();
}

/// Writes one building's (or part's) samples into a tile's lists.
class BuildingSamples {
public:
    BuildingSamples(TileSamples& out, const Region& region, Source source, std::uint32_t record,
                    std::uint32_t solid) noexcept
        : out_(out), region_(region), source_(source), record_(record), solid_(solid) {}

    /// Everything the renders can draw of the building with `v` over footprint `poly`.
    void all(const Values& v, std::span<const V2> poly) {
        if (v.hidden && source_ == Source::building) return; // an outline drawn by its parts: never drawn
        if (!v.has_h || poly.empty()) return; // draw_building reads h: a building without one is not drawn
        if ((v.flags & tl::building::flag_houseboat) != 0) houseboat(v, poly);
        building(v, poly);
        if ((v.flags & tl::building::flag_near_water) != 0 && !v.hidden && v.h != 0.0) reflection(v, poly);
    }

    /// The window group's samples, written apart and appended after the tile's other samples.
    TileSamples windows;

private:
    void add(Kind k, std::uint32_t edge, std::uint32_t part, const V3& p, std::uint32_t face) {
        out_.add(k, source_, record_, edge, part, p.x, p.y, p.z, face, solid_);
    }

    /// _draw_building's walls, windows and roof (the lens off).
    void building(const Values& v, std::span<const V2> poly) {
        const double h = v.h;
        const double y0 = v.min;
        std::optional<std::string_view> roof = v.roof;
        if (is(roof, "quadruple_saltbox")) roof = "hipped"; // closest shape this renderer draws
        const double rh = in(roof, pitched) && v.roof_h != 0.0 ? v.roof_h : 0.0;
        const double wall_top = h - rh;
        const bool modern = (v.flags & tl::building::flag_modern) != 0;
        const bool church = (v.flags & tl::building::flag_church) != 0;
        const bool part = v.part_tag && !church;
        // skillion: the downhill direction and the slope's span along it
        V2 dirv{0.0, 0.0};
        double p0 = 0.0;
        double span = 1.0;
        const bool skillion = is(roof, "skillion") && rh != 0.0;
        if (skillion) {
            dirv = roof_dir(v, poly);
            double lo = (poly[0].x * dirv.x) + (poly[0].z * dirv.z);
            double hi = lo;
            for (std::size_t i = 1; i < poly.size(); ++i) {
                const double pr = (poly[i].x * dirv.x) + (poly[i].z * dirv.z);
                lo = pm::min(lo, pr);
                hi = pm::max(hi, pr);
            }
            p0 = lo;
            span = pm::max(0.5, hi - p0);
        }
        const auto sk_top = [&](const V2& p) { return h - ((rh * (((p.x * dirv.x) + (p.z * dirv.z)) - p0)) / span); };
        const std::size_t n = poly.size();
        const bool windowed = !part && !is(v.btype, "houseboat") && !v.no_win && v.has_levels;
        for (std::size_t i = 0; i < n; ++i) {
            const V2 a = poly[i];
            const V2 bb = poly[(i + 1) % n];
            const double ex = bb.x - a.x;
            const double ez = bb.z - a.z;
            const double elen = pm::hypot(ex, ez);
            if (elen < 0.3) continue;
            const V3 nrm = vnorm({ez, 0.0, -ex});
            const std::uint32_t face = out_.add_face(nrm.x, nrm.y, nrm.z);
            const auto e = static_cast<std::uint32_t>(i);
            const auto cell = [&](Kind k, int r, int nrow, int c, int ncol) {
                const double ya = y0 + (((wall_top - y0) * r) / nrow);
                const double yb = y0 + (((wall_top - y0) * (r + 1)) / nrow);
                const double u0 = static_cast<double>(c) / ncol;
                const double u1 = static_cast<double>(c + 1) / ncol;
                add(k, e, static_cast<std::uint32_t>((r * ncol) + c),
                    {(a.x + ((ex * (u0 + u1)) / 2)) + (nrm.x * 0.15), (ya + yb) / 2,
                     (a.z + ((ez * (u0 + u1)) / 2)) + (nrm.z * 0.15)},
                    face);
            };
            // the wall as one cell (460 m away and beyond), then as ncol × nrow cells (nearer), when they differ
            cell(Kind::wall, 0, 1, 0, 1);
            const int ncol = std::max(1, kt_int(elen / 9));
            const int nrow = std::max(1, std::min(3, kt_int((wall_top - y0) / 7)));
            if (ncol != 1 || nrow != 1) {
                for (int r = 0; r < nrow; ++r) {
                    for (int c = 0; c < ncol; ++c) cell(Kind::cell, r, nrow, c, ncol);
                }
            }
            if (skillion) {
                const double ta = sk_top(a);
                const double tb = sk_top(bb);
                if (pm::max(ta, tb) > wall_top + 0.05) {
                    add(Kind::skillion_wall, e, 0,
                        {((a.x + bb.x) / 2) + (nrm.x * .15), (wall_top + ((ta + tb) / 2)) / 2,
                         ((a.z + bb.z) / 2) + (nrm.z * .15)},
                        face);
                }
            }
            if (windowed && elen > 2.2) wall_windows(v, a, ex, ez, elen, nrm, e, y0, wall_top, modern);
        }
        roofs(v, poly, roof, h, rh, wall_top, skillion, dirv, span);
    }

    /// The windows of one wall: a row per level (the shop floor's row too), bays by the wall's length.
    void wall_windows(const Values& v, const V2& a, double ex, double ez, double elen, const V3& nrm, std::uint32_t e,
                      double y0, double wall_top, bool modern) {
        const int lv = static_cast<int>(v.levels); // Kotlin's Long.toInt()
        const double fh = (wall_top - y0) / std::max(1, lv);
        const bool paris = region_.paris && !modern && in(v.mat_draw, paris_fronts);
        const int wcols = std::max(1, kt_int(elen / (paris ? 2.9 : modern ? 3.4 : 2.1)));
        const std::uint32_t wall = windows.add_face(nrm.x, nrm.y, nrm.z); // the wall's face, in the window group's list
        std::uint32_t k = 0;
        for (int r = 0; r < lv; ++r) {
            double ya = (y0 + (r * fh)) + (fh * (r != 0 ? 0.22 : 0.3));
            double yb = ya + (fh * (r != 0 ? (modern ? 0.62 : 0.58) : 0.5));
            if (paris) {
                ya = (y0 + (r * fh)) + (fh * 0.13);
                yb = ya + (fh * 0.73);
            }
            for (int c = 0; c < wcols; ++c) {
                const double u0 = (c + (modern ? 0.1 : paris ? 0.31 : 0.28)) / wcols;
                const double u1 = (c + (modern ? 0.9 : paris ? 0.69 : 0.72)) / wcols;
                windows.add(Kind::window, source_, record_, e, k++, (a.x + ((ex * (u0 + u1)) / 2)) + (nrm.x * 0.2),
                            (ya + yb) / 2, (a.z + ((ez * (u0 + u1)) / 2)) + (nrm.z * 0.2), wall, solid_);
            }
        }
    }

    /// Amsterdam.roofDir: a skillion's downhill direction, roof:direction when mapped, else out through the longest
    /// wall.
    [[nodiscard]] static V2 roof_dir(const Values& v, std::span<const V2> poly) {
        const std::string d = strip_upper(v.direction.value_or(std::string_view{}));
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

    /// The roof by its shape, as _draw_building chooses it (no tradition: render tiles carry none).
    void roofs(const Values& v, std::span<const V2> poly, const std::optional<std::string_view>& roof, double h,
               double rh, double wall_top, bool skillion, const V2& dirv, double span) {
        const std::size_t n = poly.size();
        if (is(roof, "pyramidal") && rh != 0.0) {
            const V2 ce = centroid(poly);
            const V3 apex{ce.x, h, ce.z};
            for (std::size_t i = 0; i < n; ++i) {
                const V2 a = poly[i];
                const V2 bb = poly[(i + 1) % n];
                const std::array<V3, 3> tri{V3{a.x, wall_top, a.z}, V3{bb.x, wall_top, bb.z}, apex};
                V3 nr = vnorm(vcross(vsub(tri[1], tri[0]), vsub(tri[2], tri[0])));
                if (nr.y < 0) nr = neg(nr);
                add(Kind::pyramid, static_cast<std::uint32_t>(i), 0, cen(tri), out_.add_face(nr.x, nr.y, nr.z));
            }
        } else if ((is(roof, "gabled") || is(roof, "hipped")) && rh != 0.0) {
            gable_roof(v, poly, is(roof, "hipped"), h, rh, wall_top);
        } else if (skillion) {
            const double s = rh / span;
            const V3 nr = vnorm({dirv.x * s, 1.0, dirv.z * s});
            const V2 ce = centroid(poly);
            add(Kind::skillion, 0, 0, {ce.x, (h - (rh / 2)) + 0.1, ce.z}, out_.add_face(nr.x, nr.y, nr.z));
        } else if (is(roof, "mansard") && rh != 0.0) {
            mansard(poly, h, rh, wall_top);
        } else {
            const V2 ce = centroid(poly); // the top as drawn when the eye is above it: tested whatever the sun
            add(Kind::top, 0, 0, {ce.x, h + 0.05, ce.z}, no_face);
        }
    }

    /// Amsterdam.gableRoof: the ridge along the longest side (across it where roof:orientation says), pulled in at
    /// both ends on a hipped roof; each side gives a slope, a hip's triangle or a gable's wall.
    void gable_roof(const Values& v, std::span<const V2> poly, bool hipped, double h, double rh, double wall_top) {
        const std::size_t n = poly.size();
        const std::size_t i0 = longest_last(poly);
        const V2 a0 = poly[i0];
        const V2 b0 = poly[(i0 + 1) % n];
        double ux = b0.x - a0.x;
        double uz = b0.z - a0.z;
        const double lg = pm::hypot(ux, uz);
        ux /= lg;
        uz /= lg;
        if (is(v.orient, "across")) {
            const double t = ux;
            ux = -uz;
            uz = t;
        }
        const V2 ce = centroid(poly);
        const double cx = ce.x;
        const double cz = ce.z;
        double s0 = ((poly[0].x - cx) * ux) + ((poly[0].z - cz) * uz);
        double s1 = s0;
        for (std::size_t i = 1; i < n; ++i) {
            const double pr = ((poly[i].x - cx) * ux) + ((poly[i].z - cz) * uz);
            s0 = pm::min(s0, pr);
            s1 = pm::max(s1, pr);
        }
        const double inset = hipped ? pm::min(rh, (s1 - s0) / 2) : 0.0;
        const auto on_ridge = [&](const V3& p) {
            const double sv = pm::min(s1 - inset, pm::max(s0 + inset, ((p.x - cx) * ux) + ((p.z - cz) * uz)));
            return V3{cx + (ux * sv), h, cz + (uz * sv)};
        };
        for (std::size_t i = 0; i < n; ++i) {
            const V2 a = poly[i];
            const V2 bb = poly[(i + 1) % n];
            const V3 pa{a.x, wall_top, a.z};
            const V3 pb{bb.x, wall_top, bb.z};
            const V3 qa = on_ridge(pa);
            const V3 qb = on_ridge(pb);
            const auto e = static_cast<std::uint32_t>(i);
            if (dist3(qa, qb) < 0.3 && hipped) {
                const std::array<V3, 3> tri{pa, pb, qa};
                V3 nr = vnorm(vcross(vsub(tri[1], tri[0]), vsub(tri[2], tri[0])));
                if (nr.y < 0) nr = neg(nr);
                add(Kind::hip, e, 0, cen(tri), out_.add_face(nr.x, nr.y, nr.z));
                continue;
            }
            if (dist3(qa, qb) < 0.3) {
                const std::array<V3, 3> tri{pa, pb, V3{(qa.x + qb.x) / 2, h, (qa.z + qb.z) / 2}};
                const V3 nr = vnorm({bb.z - a.z, 0.0, -(bb.x - a.x)});
                const V3 c = cen(tri);
                add(Kind::gable, e, 0, {c.x + (nr.x * .2), c.y, c.z + (nr.z * .2)}, out_.add_face(nr.x, nr.y, nr.z));
                continue;
            }
            const std::array<V3, 4> q{pa, pb, qb, qa};
            V3 nr = vnorm(vcross(vsub(q[1], q[0]), vsub(q[3], q[0])));
            if (nr.y < 0) nr = neg(nr);
            add(Kind::slope, e, 0, cen(q), out_.add_face(nr.x, nr.y, nr.z));
        }
    }

    /// Amsterdam.mansard: the steep faces up to the inset top, the dormers in them, and the top.
    void mansard(std::span<const V2> poly, double h, double rh, double wall_top) {
        const std::size_t n = poly.size();
        std::optional<std::vector<V2>> found;
        for (const double dl : {pm::min(1.4, rh * 0.36), pm::min(0.8, rh * 0.22)}) {
            found = inset_ring(poly, dl);
            if (found) break;
        }
        const std::vector<V2> ins = found ? std::move(*found) : std::vector<V2>(poly.begin(), poly.end());
        for (std::size_t i = 0; i < n; ++i) {
            const V2 a = poly[i];
            const V2 bb = poly[(i + 1) % n];
            const V2 ia = ins[i];
            const V2 ib = ins[(i + 1) % n];
            const double elen = dist(a, bb);
            if (elen < 0.3) continue;
            const std::array<V3, 4> q{V3{a.x, wall_top, a.z}, V3{bb.x, wall_top, bb.z}, V3{ib.x, h, ib.z},
                                      V3{ia.x, h, ia.z}};
            V3 nr = vnorm(vcross(vsub(q[1], q[0]), vsub(q[3], q[0])));
            if (nr.y < 0) nr = neg(nr);
            if (std::abs(nr.y) < 1e-3) { // upright: face it outward
                nr = vnorm({bb.z - a.z, 0.0, -(bb.x - a.x)});
                if (pip(((a.x + bb.x) / 2) + (nr.x * 0.05), ((a.z + bb.z) / 2) + (nr.z * 0.05), poly)) nr = neg(nr);
            }
            const V3 c = cen(q);
            const auto e = static_cast<std::uint32_t>(i);
            add(Kind::mansard, e, 0, {c.x + (nr.x * .25), c.y + (nr.y * .25), c.z + (nr.z * .25)},
                out_.add_face(nr.x, nr.y, nr.z));
            if (elen < 2.4 || rh < 2.4) continue;
            const V3 wn = std::abs(nr.y) < 0.999 ? vnorm({nr.x, 0.0, nr.z}) : V3{0.0, 0.0, 1.0};
            const double f = 0.3;
            const double yt = wall_top + pm::min(rh - 0.3, (f * rh) + 1.75);
            add(Kind::dormer, e, 0, {c.x + (wn.x * .4), yt, c.z + (wn.z * .4)}, out_.add_face(wn.x, wn.y, wn.z));
        }
        const V2 ce = centroid(ins);
        add(Kind::mansard_top, 0, 0, {ce.x, h + 0.05, ce.z}, no_face);
    }

    /// Amsterdam.drawHouseboat's walls: the hull at the waterline and the set-back cabin.
    void houseboat(const Values& v, std::span<const V2> poly) {
        const double wy = region_.water_y;
        const V2 ce = centroid(poly);
        const double lo = wy - 0.15;
        const double deck = wy + 0.7;
        const double top = pm::max(deck + 1.6, (wy + 0.3) + v.h);
        std::vector<V2> cab;
        cab.reserve(poly.size());
        for (const V2& p : poly) cab.emplace_back(ce.x + ((p.x - ce.x) * 0.84), ce.z + ((p.z - ce.z) * 0.8));
        boat_walls(Kind::hull, poly, lo, deck);
        boat_walls(Kind::cabin, cab, deck, top);
    }

    void boat_walls(Kind kind, std::span<const V2> pp, double y0, double y1) {
        const std::size_t m = pp.size();
        for (std::size_t i = 0; i < m; ++i) {
            const V2 a = pp[i];
            const V2 c = pp[(i + 1) % m];
            const double ex = c.x - a.x;
            const double ez = c.z - a.z;
            const double elen = pm::hypot(ex, ez);
            if (elen < 0.2) continue;
            const V3 nrm = vnorm({ez, 0.0, -ex});
            add(kind, static_cast<std::uint32_t>(i), 0,
                {((a.x + c.x) / 2) + (nrm.x * .2), (y0 + y1) / 2, ((a.z + c.z) / 2) + (nrm.z * .2)},
                out_.add_face(nrm.x, nrm.y, nrm.z));
        }
    }

    /// Amsterdam.reflections: each wall of a building within 16 m of a quay, lit by the sample at the real wall's
    /// middle.
    void reflection(const Values& v, std::span<const V2> poly) {
        const double wy = region_.water_y;
        const bool boat = is(v.btype, "houseboat");
        const double y0 = boat ? wy + 0.3 : v.min;
        const double h = boat ? (v.h + wy) + 0.3 : v.h;
        const double rh = in(v.roof, reflected) && v.roof_h != 0.0 ? v.roof_h : 0.0;
        const double top = h - rh;
        const std::size_t n = poly.size();
        for (std::size_t i = 0; i < n; ++i) {
            const V2 a = poly[i];
            const V2 bb = poly[(i + 1) % n];
            const double ex = bb.x - a.x;
            const double ez = bb.z - a.z;
            const double elen = pm::hypot(ex, ez);
            if (elen < 0.3) continue;
            const V3 nrm = vnorm({ez, 0.0, -ex});
            add(Kind::reflection, static_cast<std::uint32_t>(i), 0,
                {((a.x + bb.x) / 2) + (nrm.x * .2), (y0 + top) / 2, ((a.z + bb.z) / 2) + (nrm.z * .2)},
                out_.add_face(nrm.x, nrm.y, nrm.z));
        }
    }

    TileSamples& out_;
    const Region& region_;
    Source source_;
    std::uint32_t record_;
    std::uint32_t solid_;
};

// ---------------------------------------------------------------- quay walls (Amsterdam.waterLayer)

/// Whether (x, z) lies under a bridge's deck (waterLayer's underDeck).
[[nodiscard]] bool under_deck(double x, double z, std::span<const Deck> decks) {
    std::vector<V2> ring;
    for (const Deck& dk : decks) {
        const auto& bb = dk.box;
        if (!dk.road) {
            if (bb[0] <= x && x <= bb[1] && bb[2] <= z && z <= bb[3]) {
                ring.clear();
                for (std::size_t i = 0; i < dk.x.size(); ++i) ring.emplace_back(dk.x[i], dk.z[i]);
                if (pip(x, z, ring)) return true;
            }
            continue;
        }
        const bool in_box = bb[0] <= x && x <= bb[1] && bb[2] <= z && z <= bb[3]; // false for a NaN, as the port's
        if (!in_box) continue;
        for (std::size_t i = 0; i + 1 < dk.x.size(); ++i) {
            const double ax = dk.x[i];
            const double az = dk.z[i];
            const double ux = dk.x[i + 1] - ax;
            const double uz = dk.z[i + 1] - az;
            double l2 = (ux * ux) + (uz * uz);
            if (l2 == 0.0) l2 = 1.0;
            const double t = pm::max(0.0, pm::min(1.0, (((x - ax) * ux) + ((z - az) * uz)) / l2));
            if (pm::hypot((ax + (ux * t)) - x, (az + (uz * t)) - z) < dk.hw) return true;
        }
    }
    return false;
}

/// One quay edge's samples: its pieces outside bridge decks, each at half the water's depth, and a tall wall's
/// bands where the region draws them.
void quay_samples(TileSamples& out, const Region& region, std::uint32_t record, const V2& a, const V2& b, double nx,
                  double nz, double wy, std::span<const Deck> decks) {
    struct Piece {
        V2 a;
        V2 b;
    };
    std::vector<Piece> pieces;
    const double mx = (a.x + b.x) / 2;
    const double mz = (a.z + b.z) / 2;
    const double el = dist(a, b);
    const bool near_deck = std::ranges::any_of(decks, [&](const Deck& dk) {
        const auto& bb = dk.box;
        return bb[0] - el <= mx && mx <= bb[1] + el && bb[2] - el <= mz && mz <= bb[3] + el;
    });
    if (near_deck) {
        const int k = std::max(1, kt_int(el / 2.0));
        std::optional<V2> run;
        for (int j = 0; j < k; ++j) {
            const double u0 = static_cast<double>(j) / k;
            const double u1 = static_cast<double>(j + 1) / k;
            if (under_deck(a.x + (((b.x - a.x) * (u0 + u1)) / 2), a.z + (((b.z - a.z) * (u0 + u1)) / 2), decks)) {
                if (run) {
                    pieces.push_back({.a = *run, .b = V2(a.x + ((b.x - a.x) * u0), a.z + ((b.z - a.z) * u0))});
                    run.reset();
                }
            } else if (!run) {
                run = V2{a.x + ((b.x - a.x) * u0), a.z + ((b.z - a.z) * u0)};
            }
        }
        if (run) pieces.push_back({.a = *run, .b = b});
    } else {
        pieces.push_back({.a = a, .b = b});
    }
    const std::uint32_t face = out.add_face(nx, 0.0, nz);
    std::uint32_t part = 0;
    for (const Piece& p : pieces) {
        const double px = (p.a.x + p.b.x) / 2;
        const double pz = (p.a.z + p.b.z) / 2;
        out.add(Kind::quay, Source::quay, record, 0, part++, px + (nx * .3), wy / 2, pz + (nz * .3), face, no_solid);
        const bool bands = region.tone && wy < -2;
        if (!bands) continue;
        // a tall quay wall in sun: in pieces of about 5 m, three bands high
        const double pel = dist(p.a, p.b);
        const int k = static_cast<int>(std::max<std::int64_t>(1, pm::ceil_int(pel / 5.0)));
        constexpr int nb = 3;
        for (int j = 0; j < k; ++j) {
            const V2 pa{p.a.x + (((p.b.x - p.a.x) * j) / k), p.a.z + (((p.b.z - p.a.z) * j) / k)};
            const V2 pb{p.a.x + (((p.b.x - p.a.x) * (j + 1)) / k), p.a.z + (((p.b.z - p.a.z) * (j + 1)) / k)};
            for (int m = 0; m < nb; ++m) {
                const double ya = (wy + 0.35) - (((wy + 0.35) * m) / nb);
                const double yb = (wy + 0.35) - (((wy + 0.35) * (m + 1)) / nb);
                out.add(Kind::quay_band, Source::quay, record, 0, part++, ((pa.x + pb.x) / 2) + (nx * .3),
                        (ya + yb) / 2, ((pa.z + pb.z) / 2) + (nz * .3), face, no_solid);
            }
        }
    }
}

/// Appends `w`'s samples to `out`, their faces renumbered.
void append(TileSamples& out, const TileSamples& w) {
    const auto faces = static_cast<std::uint32_t>(out.nx.size());
    out.nx.insert(out.nx.end(), w.nx.begin(), w.nx.end());
    out.ny.insert(out.ny.end(), w.ny.begin(), w.ny.end());
    out.nz.insert(out.nz.end(), w.nz.begin(), w.nz.end());
    for (std::uint32_t i = 0; i < w.size(); ++i) {
        out.add(w.kind[i], w.source[i], w.owner[i], w.edge[i], w.part[i], w.x[i], w.y[i], w.z[i],
                w.face[i] == no_face ? no_face : w.face[i] + faces, w.skip[i]);
    }
}

} // namespace

const char* name_of(Kind kind) noexcept {
    switch (kind) {
    case Kind::wall: return "wall";
    case Kind::cell: return "cell";
    case Kind::skillion_wall: return "skillion_wall";
    case Kind::window: return "window";
    case Kind::pyramid: return "pyramid";
    case Kind::hip: return "hip";
    case Kind::gable: return "gable";
    case Kind::slope: return "slope";
    case Kind::skillion: return "skillion";
    case Kind::top: return "top";
    case Kind::mansard: return "mansard";
    case Kind::dormer: return "dormer";
    case Kind::mansard_top: return "mansard_top";
    case Kind::hull: return "hull";
    case Kind::cabin: return "cabin";
    case Kind::reflection: return "reflection";
    case Kind::quay: return "quay";
    case Kind::quay_band: return "quay_band";
    case Kind::crown: return "crown";
    }
    return "?";
}

std::uint32_t TileSamples::add_face(double fx, double fy, double fz) {
    nx.push_back(fx);
    ny.push_back(fy);
    nz.push_back(fz);
    return static_cast<std::uint32_t>(nx.size() - 1);
}

void TileSamples::add(Kind k, Source src, std::uint32_t own, std::uint32_t e, std::uint32_t p, double px, double py,
                      double pz, std::uint32_t f, std::uint32_t s) {
    x.push_back(px);
    y.push_back(py);
    z.push_back(pz);
    face.push_back(f);
    skip.push_back(s);
    kind.push_back(k);
    source.push_back(src);
    owner.push_back(own);
    edge.push_back(static_cast<std::uint16_t>(std::min<std::uint32_t>(e, 0xFFFFU)));
    part.push_back(static_cast<std::uint16_t>(std::min<std::uint32_t>(p, 0xFFFFU)));
}

Region region_of(const tiles::StyleTables& style) noexcept {
    Region r;
    if (const auto state = style.table("state")) {
        if (const auto w = state->find("water_y")) r.water_y = w->number().value_or(r.water_y);
        if (const auto t = state->find("tone")) r.tone = t->boolean().value_or(false);
        if (const auto ws = state->find("win_style")) r.paris = ws->string().value_or(std::string_view{}) == "paris";
    }
    if (const auto scene = style.table("scene")) {
        if (const auto ns = scene->find("no_shadows")) {
            r.no_shadows = ns->boolean().value_or(false) || ns->number().value_or(0.0) != 0.0; // Python's truth
        }
    }
    return r;
}

void collect_decks(const tiles::Tile& tile, const tiles::RenderHead& head, std::uint8_t level, std::vector<Deck>& out) {
    const tl::Frame& frame = head.frame;
    if (const tl::Layer* roads = tile.layer(tl::LayerId::road)) {
        for (std::uint32_t i = 0; i < roads->count; ++i) {
            if (!roads->boolean(tl::road::bridge, i).value_or(false)) continue;
            const tl::RingView line = roads->ring(tl::road::line, i);
            if (line.size() == 0) continue;
            const double rw = roads->decimal(tl::road::w, i).value_or(0.0);
            Deck d;
            d.road = true;
            d.hw = (rw / 2) + 0.8;
            for (std::size_t k = 0; k < line.size(); ++k) {
                d.x.push_back(frame.x(line.lon[k]));
                d.z.push_back(frame.z(line.lat[k]));
            }
            double x0 = d.x[0];
            double x1 = d.x[0];
            double z0 = d.z[0];
            double z1 = d.z[0];
            for (std::size_t k = 1; k < d.x.size(); ++k) {
                x0 = pm::min(x0, d.x[k]);
                x1 = pm::max(x1, d.x[k]);
                z0 = pm::min(z0, d.z[k]);
                z1 = pm::max(z1, d.z[k]);
            }
            d.box[0] = x0 - rw;
            d.box[1] = x1 + rw;
            d.box[2] = z0 - rw;
            d.box[3] = z1 + rw;
            out.push_back(std::move(d));
        }
    }
    if (const tl::Layer* ground = tile.layer(tl::LayerId::ground)) {
        const std::uint8_t tag_bridge = ground->schema->slot("tag:bridge");
        const std::uint8_t tag_made = ground->schema->slot("tag:man_made");
        std::vector<std::uint32_t> kept;
        std::vector<V2> ring;
        for (std::uint32_t i = 0; i < ground->count; ++i) {
            if (ground->integer(tl::ground::source, i) != tl::ground::source_area) continue;
            const auto bridge = ground->string(tag_bridge, i);
            const bool deck = is(ground->string(tl::ground::kind, i), "bridge") ||
                              is(ground->string(tag_made, i), "bridge") || (bridge.has_value() && *bridge != "no");
            if (!deck) continue;
            ring_at(ground->ring(tl::ground::ring, i), level, frame, kept, ring);
            if (ring.empty()) continue;
            Deck d;
            double x0 = ring[0].x;
            double x1 = ring[0].x;
            double z0 = ring[0].z;
            double z1 = ring[0].z;
            for (const V2& p : ring) {
                d.x.push_back(p.x);
                d.z.push_back(p.z);
                x0 = pm::min(x0, p.x);
                x1 = pm::max(x1, p.x);
                z0 = pm::min(z0, p.z);
                z1 = pm::max(z1, p.z);
            }
            d.box[0] = x0 - 1;
            d.box[1] = x1 + 1;
            d.box[2] = z0 - 1;
            d.box[3] = z1 + 1;
            out.push_back(std::move(d));
        }
    }
}

SunTile make_tile(const tiles::Tile& tile, const tiles::RenderHead& head, const Region& region, std::uint8_t level,
                  std::span<const Deck> decks) {
    SunTile st;
    st.key = {tile.header.level, tile.header.x, tile.header.y};
    st.level = level;
    const tl::Frame& frame = head.frame;
    // the variants at this level, by their base feature's layer and source order
    std::unordered_map<std::uint64_t, std::uint32_t> variants;
    const tl::Layer* var = tile.layer(tl::LayerId::variant);
    if (var != nullptr && level > 0) {
        for (std::uint32_t i = 0; i < var->count; ++i) {
            if (var->integer(tl::variant::tolerance, i) != level) continue;
            const auto layer = static_cast<std::uint64_t>(var->integer(tl::variant::layer, i));
            const auto index = static_cast<std::uint64_t>(var->integer(tl::variant::index, i));
            variants[(layer << 56U) | index] = i;
        }
    }
    std::vector<std::uint32_t> kept;
    std::vector<V2> poly;
    TileSamples windows;
    for (const auto [layer_id, source] :
         {std::pair{tl::LayerId::building, Source::building}, std::pair{tl::LayerId::part, Source::part}}) {
        const tl::Layer* l = tile.layer(layer_id);
        if (l == nullptr) continue;
        for (std::uint32_t i = 0; i < l->count; ++i) {
            const auto index = static_cast<std::uint64_t>(l->integer(tl::building::index, i));
            const auto it = variants.find((static_cast<std::uint64_t>(layer_id) << 56U) | index);
            const Values v =
                it != variants.end() ? values_of(*var, variant_slots, it->second) : values_of(*l, record_slots, i);
            ring_at(l->ring(tl::building::ring, i), level, frame, kept, poly);
            // a solid: a building not hidden with a height, a part with a height (render's `solids`)
            std::uint32_t solid = no_solid;
            if ((source == Source::part || !v.hidden) && v.h != 0.0 && !poly.empty()) {
                solid = st.solids.size();
                for (const V2& p : poly) {
                    st.solids.x.push_back(p.x);
                    st.solids.z.push_back(p.z);
                }
                st.solids.first.push_back(static_cast<std::uint32_t>(st.solids.x.size()));
                st.solids.h.push_back(v.h);
                st.solids.min.push_back(v.min);
                st.solids.source.push_back(source);
                st.solids.record.push_back(i);
            }
            BuildingSamples b(st.samples, region, source, i, solid);
            b.all(v, poly);
            append(windows, b.windows);
        }
    }
    // the trees' crowns: 3/4 up, moved toward the sun by the crown's radius when the bucket is known
    if (const tl::Layer* trees = tile.layer(tl::LayerId::tree)) {
        st.samples.crowns_first = st.samples.size();
        for (std::uint32_t i = 0; i < trees->count; ++i) {
            const tl::GridPoint p = trees->point(tl::tree::pos, i);
            const double x = frame.x(p.lon);
            const double z = frame.z(p.lat);
            const double h = trees->decimal(tl::tree::h, i).value_or(0.0);
            const double crown = trees->decimal(tl::tree::crown, i).value_or(0.0);
            st.trees.x.push_back(x);
            st.trees.z.push_back(z);
            st.trees.h.push_back(h);
            st.trees.crown.push_back(crown);
            st.trees.gy.push_back(trees->decimal(tl::tree::gy, i).value_or(0.0));
            st.samples.add(Kind::crown, Source::tree, i, 0, 0, x, h * 0.75, z, no_face, no_solid);
            st.samples.crown_r.push_back(crown / 2);
        }
        st.samples.crowns_end = st.samples.size();
    } else {
        st.samples.crowns_first = st.samples.crowns_end = st.samples.size();
    }
    // the quay walls: every water edge with a wall, in pieces outside the bridges' decks
    if (const tl::Layer* quays = tile.layer(tl::LayerId::quay)) {
        for (std::uint32_t i = 0; i < quays->count; ++i) {
            const tl::RingView e = quays->ring(tl::quay::edge, i);
            if (e.size() < 2) continue;
            const V2 a{frame.x(e.lon[0]), frame.z(e.lat[0])};
            const V2 b{frame.x(e.lon[1]), frame.z(e.lat[1])};
            const double ex = b.x - a.x;
            const double ez = b.z - a.z;
            const double lg = pm::hypot(ex, ez);
            double nx = ez / lg;
            double nz = -ex / lg;
            if (quays->boolean(tl::quay::flip, i).value_or(false)) {
                nx = -nx;
                nz = -nz;
            }
            const double wy = quays->decimal(tl::quay::level, i).value_or(region.water_y);
            quay_samples(st.samples, region, i, a, b, nx, nz, wy, decks);
        }
    }
    st.samples.windows_first = st.samples.size();
    append(st.samples, windows);
    return st;
}

tiles::Result<double> scene_maxh(const tiles::Pack& pack, std::uint8_t level) {
    const tl::RenderHead* head = pack.head();
    if (head == nullptr || head->levels.empty()) return 0.0;
    const std::uint8_t z = head->levels.front();
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    Arena arena(1U << 20U, Growth::grows, "sun maxh");
    bool any = false;
    double maxh = 0.0;
    for (const tl::ChunkRef& c : pack.chunks().level(z)) {
        arena.reset();
        auto tile = pack.decode(c, zstd, decoder, arena, raw, scratch);
        if (!tile) return std::unexpected(tile.error());
        std::unordered_map<std::uint64_t, std::uint32_t> variants;
        const tl::Layer* var = tile->layer(tl::LayerId::variant);
        if (var != nullptr && level > 0) {
            for (std::uint32_t i = 0; i < var->count; ++i) {
                if (var->integer(tl::variant::tolerance, i) != level) continue;
                const auto layer = static_cast<std::uint64_t>(var->integer(tl::variant::layer, i));
                variants[(layer << 56U) | static_cast<std::uint64_t>(var->integer(tl::variant::index, i))] = i;
            }
        }
        for (const auto layer_id : {tl::LayerId::building, tl::LayerId::part}) {
            const tl::Layer* l = tile->layer(layer_id);
            if (l == nullptr) continue;
            for (std::uint32_t i = 0; i < l->count; ++i) {
                const auto index = static_cast<std::uint64_t>(l->integer(tl::building::index, i));
                const auto it = variants.find((static_cast<std::uint64_t>(layer_id) << 56U) | index);
                const Values v =
                    it != variants.end() ? values_of(*var, variant_slots, it->second) : values_of(*l, record_slots, i);
                if ((layer_id == tl::LayerId::part || !v.hidden) && v.h != 0.0) {
                    maxh = any ? pm::max(maxh, v.h) : v.h;
                    any = true;
                }
            }
        }
    }
    return maxh;
}

} // namespace atlas::sun
