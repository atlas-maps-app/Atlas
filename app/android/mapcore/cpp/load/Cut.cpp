#include "load/Cut.hpp"

#include "base/pymath/PyMath.hpp"
#include "geometry/RoofShapes.hpp"
#include "geometry/StreetWall.hpp"
#include "load/Keep.hpp"
#include "load/Real.hpp"
#include "load/Tables.hpp"
#include "text/Utf8.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Schema.hpp"
#include "tiles/layers/Buildings.hpp"
#include "tiles/layers/Ground.hpp"
#include "tiles/layers/Labels.hpp"
#include "tiles/layers/Lines.hpp"
#include "tiles/layers/Points.hpp"
#include "tiles/layers/Water.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <format>
#include <limits>
#include <optional>
#include <span>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace atlas::load {

namespace {

namespace tl = atlas::tiles;
namespace gk = atlas::tiles::ground_key;

/// A grid box: [south, west, north, east].
using Box4 = std::array<std::int32_t, 4>;

__extension__ using Int128 = __int128;

// rendertiles' Drawn: how far the renders draw beyond a feature's vertices and how high, metres.
constexpr double chimney = 1.6;     // chimney stacks and pots above a roof
constexpr double thing_reach = 5.0; // a lamp's mast, a bench, a door or a shopfront beside its node
constexpr double road_side = 3.0;   // beyond a road's half width: railings, pavements
constexpr double road_top = 0.1;
constexpr double bridge_top = 2.0;
constexpr double tram_reach = 1.5; // two rails 0.72 m either side
constexpr double area_top = 0.02;
constexpr double land_top = 0.01;
constexpr double tree_symbol = 3.0;
constexpr double crown_reach = 0.8;

/// The flags' near_water: a quay edge within 16 m.
constexpr double near_water_reach = 16.0;

/// The most tiles a water ring's box may reach at a level, in a region's or the living map's cut: a region's sea,
/// closed along a box well beyond the region, reaches a few hundred of L14. A ring beyond it (a vertex flung across the
/// world, as the fuzzer makes) refuses the cut, which would otherwise make a piece of it for every tile; a window looks
/// at its own tile alone.
constexpr std::uint64_t ring_reach_max = 4096;

// amsterdam.py's CHURCHES, WAREHOUSES, SHOP_FLOORS, FURNITURE and BOLLARDS (SceneToTiles' copies, which
// RenderTablesTest holds to the port's); MATERIAL, CLAY and ROAD_ORDER are load/Tables.hpp's
constexpr std::array<std::string_view, 3> churches = {"church", "cathedral", "chapel"};
constexpr std::array<std::string_view, 3> warehouses = {"industrial", "warehouse", "service"};
constexpr std::array<std::string_view, 2> shop_floors = {"retail", "commercial"};
constexpr std::array<std::string_view, 3> furniture = {"bench", "bicycle_parking", "waste_basket"};
constexpr std::array<std::string_view, 2> bollards = {"bollard", "block"};

[[nodiscard]] bool in(std::optional<std::string_view> v, std::span<const std::string_view> set) noexcept {
    return v.has_value() && std::ranges::find(set, *v) != set.end();
}

/// GroundKey.of: stage << 28 | class << 22 | order.
[[nodiscard]] std::int64_t ground_key(std::uint32_t stage, std::uint32_t klass, std::size_t order) noexcept {
    return (std::int64_t{stage} << 28U) | (std::int64_t{klass} << 22U) | static_cast<std::int64_t>(order);
}

/// Kotlin's Double.toLong(): truncation, NaN 0, saturating.
[[nodiscard]] std::int64_t kt_long(double v) noexcept {
    if (std::isnan(v)) return 0;
    if (v >= 9.223372036854775807e18) return std::numeric_limits<std::int64_t>::max();
    if (v <= -9.223372036854775808e18) return std::numeric_limits<std::int64_t>::min();
    return static_cast<std::int64_t>(v);
}

/// SceneToTiles' cmUp: metres in whole centimetres, rounded up, within an Int (NaN 0).
[[nodiscard]] std::int32_t cm_up(double m) noexcept {
    const double c = std::ceil(m * 100);
    if (std::isnan(c)) return 0;
    return static_cast<std::int32_t>(std::clamp(c, -2147483648.0, 2147483647.0));
}

/// Kotlin's Long + and -: two's complement, wrapping where they overflow.
[[nodiscard]] constexpr std::int64_t kt_plus(std::int64_t a, std::int64_t b) noexcept {
    return static_cast<std::int64_t>(static_cast<std::uint64_t>(a) + static_cast<std::uint64_t>(b));
}
[[nodiscard]] constexpr std::int64_t kt_minus(std::int64_t a, std::int64_t b) noexcept {
    return static_cast<std::int64_t>(static_cast<std::uint64_t>(a) - static_cast<std::uint64_t>(b));
}

/// SceneToTiles' widen: `box` widened by `metres` on every side, outward on the grid; its Long arithmetic wraps where a
/// width beyond any grid's saturates the conversion (as the packer's does).
[[nodiscard]] Box4 widen(const Box4& b, double metres, const tl::Frame& f) noexcept {
    if (!(metres > 0)) return b;
    const std::int64_t d_lat = kt_plus(kt_long(std::ceil(metres / f.kz * 1e7)), 1);
    const std::int64_t d_lon = kt_plus(kt_long(std::ceil(metres / f.kx * 1e7)), 1);
    const auto c = [](std::int64_t v) {
        return static_cast<std::int32_t>(std::clamp<std::int64_t>(v, std::numeric_limits<std::int32_t>::min(),
                                                                  std::numeric_limits<std::int32_t>::max()));
    };
    return {c(kt_minus(b[0], d_lat)), c(kt_minus(b[1], d_lon)), c(kt_plus(b[2], d_lat)), c(kt_plus(b[3], d_lon))};
}

[[nodiscard]] Box4 point_box(tl::GridPoint p) noexcept { return {p.lat, p.lon, p.lat, p.lon}; }

/// A ring or line on the grid, its keep codes where it carries them, and its box.
struct GridLine {
    std::vector<std::int32_t> lat;
    std::vector<std::int32_t> lon;
    std::vector<std::uint8_t> keep;

    [[nodiscard]] Box4 box() const noexcept {
        Box4 b{std::numeric_limits<std::int32_t>::max(), std::numeric_limits<std::int32_t>::max(),
               std::numeric_limits<std::int32_t>::min(), std::numeric_limits<std::int32_t>::min()};
        for (std::size_t i = 0; i < lat.size(); ++i) {
            b[0] = std::min(b[0], lat[i]);
            b[1] = std::min(b[1], lon[i]);
            b[2] = std::max(b[2], lat[i]);
            b[3] = std::max(b[3], lon[i]);
        }
        return b;
    }
};

// ---------------------------------------------------------------- colours

/// Java's Character.digit(c, 16) for a UTF-16 unit: ASCII and fullwidth hex letters, and the plane's decimal digits.
[[nodiscard]] std::optional<int> hex_digit(char32_t c) noexcept {
    if (c >= U'a' && c <= U'f') return static_cast<int>(c - U'a') + 10;
    if (c >= U'A' && c <= U'F') return static_cast<int>(c - U'A') + 10;
    if (c >= 0xFF41 && c <= 0xFF46) return static_cast<int>(c - 0xFF41) + 10;
    if (c >= 0xFF21 && c <= 0xFF26) return static_cast<int>(c - 0xFF21) + 10;
    return digit_of(c);
}

/// Integer.parseInt of two UTF-16 units, radix 16: a sign and one digit, or two digits.
[[nodiscard]] std::optional<int> parse_pair(char32_t a, char32_t b) noexcept {
    if (a == U'+' || a == U'-') {
        const auto d = hex_digit(b);
        if (!d) return std::nullopt;
        return a == U'-' ? -*d : *d;
    }
    const auto hi = hex_digit(a);
    const auto lo = hex_digit(b);
    if (!hi || !lo) return std::nullopt;
    return *hi * 16 + *lo;
}

/// City.hx as the port reads a colour, packed 0xRRGGBB: the leading '#'s stripped, three pairs of UTF-16 units each
/// read by Integer.parseInt in radix 16, each 0 to 255 (SceneToTiles' rgb); nothing where the port throws.
[[nodiscard]] std::optional<std::uint32_t> hx(std::string_view s) {
    std::size_t i = 0;
    while (i < s.size() && s[i] == '#') ++i;
    std::array<char32_t, 6> units{};
    std::size_t n = 0;
    for (const char32_t c : text::code_points(s.substr(i))) {
        if (n == units.size()) break;
        if (c >= 0x10000) { // two surrogates, neither a digit
            units[n++] = 0xD800;
            if (n < units.size()) units[n++] = 0xDC00;
        } else {
            units[n++] = c;
        }
    }
    if (n < units.size()) return std::nullopt;
    std::uint32_t v = 0;
    for (std::size_t k = 0; k < 3; ++k) {
        const auto ch = parse_pair(units[2 * k], units[(2 * k) + 1]);
        if (!ch || *ch < 0 || *ch > 255) return std::nullopt;
        v = (v << 8U) | static_cast<std::uint32_t>(*ch);
    }
    return v;
}

[[nodiscard]] std::uint32_t colour(std::string_view s) { return hx(s).value_or(clay_rgb); }

[[nodiscard]] std::string_view material_or_clay(std::string_view name) noexcept {
    const auto* const m = std::ranges::find(materials, name, &Material::name);
    return m != materials.end() ? m->col : clay;
}

/// surface_colour(b, None, 'wall'): the colour the looks drew, else the mapped colour, else the material's, else the
/// clay.
[[nodiscard]] std::uint32_t wall_rgb(const Building& b) {
    if (b.col_draw && !b.col_draw->empty()) return colour(*b.col_draw);
    const std::string_view col = b.col.value_or("");
    if (col.starts_with('#')) return colour(col);
    const std::string_view mat = b.mat.value_or("");
    if (!mat.empty()) return colour(material_or_clay(mat));
    return clay_rgb;
}

/// surface_colour(b, None, 'roof'): the roof colour the looks drew, else the roof's colour or material, else the
/// wall's.
[[nodiscard]] std::uint32_t roof_rgb(const Building& b) {
    if (b.rcol_draw && !b.rcol_draw->empty()) return colour(*b.rcol_draw);
    const std::string_view rcol = b.rcol.value_or("");
    if (!rcol.empty()) return colour(rcol.starts_with('#') ? rcol : material_or_clay(rcol));
    const std::string_view rmat = b.rmat.value_or("");
    if (!rmat.empty()) return colour(material_or_clay(rmat));
    return wall_rgb(b);
}

// ---------------------------------------------------------------- the quays

/// One of water_edges' edges: its ring (a water ring, or an island), its ends (the ring's vertices) and whether its
/// normal into the water is the opposite of (ez, -ex) / L.
struct Edge {
    std::uint32_t ring = 0;
    bool hole = false;
    V2 a;
    V2 b;
    bool flip = false;
};

/// A ring's z range: pip crosses none of its edges at a z outside it, so a point there is outside, exactly.
struct ZRange {
    double z0 = 0.0;
    double z1 = 0.0;
};

[[nodiscard]] ZRange z_range(std::span<const V2> r) noexcept {
    ZRange out{.z0 = std::numeric_limits<double>::infinity(), .z1 = -std::numeric_limits<double>::infinity()};
    for (const V2& p : r) {
        out.z0 = std::min(out.z0, p.z);
        out.z1 = std::max(out.z1, p.z);
    }
    return out;
}

/// pip(x, z, r): false outside the ring's z range (no edge's `(zi > z) != (zj > z)` holds there), else pip's own
/// answer.
[[nodiscard]] bool pip_in(double x, double z, std::span<const V2> r, const ZRange& zr) noexcept {
    if (z < zr.z0 || z >= zr.z1) return false;
    return geometry::pip(x, z, r);
}

/// water_edges(S): every edge at least 0.4 m long of each water ring and island, its normal turned into the water (out
/// of an island). A region knows its shores (the living map does not): a natural shore's ring has no quay, nor does an
/// edge with water on both sides; an island's edge whose land side lies in other water has none.
[[nodiscard]] std::vector<Edge> water_edges(const Scene& s) {
    std::vector<Edge> out;
    const bool region = !s.natural_shore.empty();
    std::vector<ZRange> zr;
    zr.reserve(s.water.size());
    for (const std::vector<V2>& r : s.water) zr.push_back(z_range(r));
    const auto edges_of = [&](const std::vector<V2>& r, std::uint32_t k, bool hole) {
        const std::size_t n = r.size();
        if (n == 0) return;
        const std::size_t last = r[0] == r[n - 1] ? n - 1 : n;
        for (std::size_t i = 0; i < last; ++i) {
            const V2 a = r[i];
            const V2 b = r[(i + 1) % n];
            const double ex = b.x - a.x;
            const double ez = b.z - a.z;
            const double lg = pymath::hypot(ex, ez);
            if (lg < 0.4) continue;
            double nx = ez / lg;
            double nz = -ex / lg;
            const double mx = (a.x + b.x) / 2;
            const double mz = (a.z + b.z) / 2;
            // into the water: inside a river, outside an island
            const bool flip = geometry::pip(mx + nx * 0.4, mz + nz * 0.4, r) == hole;
            if (flip) {
                nx = -nx;
                nz = -nz;
            }
            const double px = mx - nx * 1.5;
            const double pz = mz - nz * 1.5;
            if (hole) {
                if (!geometry::pip(px, pz, r)) {
                    bool other = false;
                    for (std::size_t o = 0; o < s.water.size() && !other; ++o) {
                        other = !s.water[o].empty() && std::fabs(s.water[o][0].x - mx) < 4000 &&
                                pip_in(px, pz, s.water[o], zr[o]);
                    }
                    if (other) continue;
                }
                out.push_back({.ring = k, .hole = true, .a = a, .b = b, .flip = flip});
                continue;
            }
            if (region) {
                bool other = false;
                for (std::size_t o = 0; o < s.water.size() && !other; ++o) {
                    other = o != k && pip_in(px, pz, s.water[o], zr[o]);
                }
                if (other) continue; // water on both sides: no wall
            }
            out.push_back({.ring = k, .a = a, .b = b, .flip = flip});
        }
    };
    for (std::uint32_t k = 0; k < s.water.size(); ++k) {
        if (region && s.natural_shore[k]) continue; // beaches and lake shores: no quay wall
        edges_of(s.water[k], k, false);
    }
    for (std::uint32_t k = 0; k < s.water_holes.size(); ++k) edges_of(s.water_holes[k], k, true);
    return out;
}

/// near_water's test of one edge: its box widened by `dd` meets the points' box, and a point is nearer than `dd`.
[[nodiscard]] bool near(const Edge& e, std::span<const V2> pts, double dd, const Box& pb) noexcept {
    const V2 a = e.a;
    const V2 b = e.b;
    if (pymath::min(a.x, b.x) - dd > pb.x1 || pymath::max(a.x, b.x) + dd < pb.x0) return false;
    if (pymath::min(a.z, b.z) - dd > pb.z1 || pymath::max(a.z, b.z) + dd < pb.z0) return false;
    for (const V2& p : pts) {
        const double ux = b.x - a.x;
        const double uz = b.z - a.z;
        double l2 = ux * ux + uz * uz;
        if (l2 == 0.0) l2 = 1.0;
        const double t = pymath::max(0.0, pymath::min(1.0, ((p.x - a.x) * ux + (p.z - a.z) * uz) / l2));
        if (pymath::hypot(a.x + ux * t - p.x, a.z + uz * t - p.z) < dd) return true;
    }
    return false;
}

/// The edges filed by the cells of a grid their boxes reach, so near_water reads only those near its points (an edge
/// over more than `max_cells` cells is kept aside and always read). Every edge the box test could let through is read,
/// and `any` does not depend on the order.
class EdgeGrid {
public:
    explicit EdgeGrid(const std::vector<Edge>& edges) : edges_(&edges) {
        for (std::uint32_t k = 0; k < edges.size(); ++k) {
            const Edge& e = edges[k];
            const std::int64_t i0 = cell(std::min(e.a.x, e.b.x));
            const std::int64_t i1 = cell(std::max(e.a.x, e.b.x));
            const std::int64_t j0 = cell(std::min(e.a.z, e.b.z));
            const std::int64_t j1 = cell(std::max(e.a.z, e.b.z));
            if ((i1 - i0 + 1) * (j1 - j0 + 1) > max_cells) {
                big_.push_back(k);
                continue;
            }
            for (std::int64_t i = i0; i <= i1; ++i) {
                for (std::int64_t j = j0; j <= j1; ++j) cells_[key(i, j)].push_back(k);
            }
        }
    }

    /// near_water(edges, pts, dd).
    [[nodiscard]] bool near_water(std::span<const V2> pts, double dd) const {
        Box pb{.x0 = pts[0].x, .x1 = pts[0].x, .z0 = pts[0].z, .z1 = pts[0].z};
        for (const V2& p : pts) {
            pb.x1 = pymath::max(pb.x1, p.x); // the port's `if (p.x > px1) px1 = p.x`: Python's max
            pb.x0 = pymath::min(pb.x0, p.x);
            pb.z1 = pymath::max(pb.z1, p.z);
            pb.z0 = pymath::min(pb.z0, p.z);
        }
        for (const std::uint32_t k : big_) {
            if (near((*edges_)[k], pts, dd, pb)) return true;
        }
        const std::int64_t i0 = cell(pb.x0 - dd) - 1;
        const std::int64_t i1 = cell(pb.x1 + dd) + 1;
        const std::int64_t j0 = cell(pb.z0 - dd) - 1;
        const std::int64_t j1 = cell(pb.z1 + dd) + 1;
        if ((i1 - i0 + 1) * (j1 - j0 + 1) >
            static_cast<std::int64_t>(std::max<std::size_t>(max_cells, edges_->size()))) {
            return std::ranges::any_of(*edges_, [&](const Edge& e) { return near(e, pts, dd, pb); });
        }
        for (std::int64_t i = i0; i <= i1; ++i) {
            for (std::int64_t j = j0; j <= j1; ++j) {
                const auto it = cells_.find(key(i, j));
                if (it == cells_.end()) continue;
                for (const std::uint32_t k : it->second) {
                    if (near((*edges_)[k], pts, dd, pb)) return true;
                }
            }
        }
        return false;
    }

private:
    static constexpr double size = 64.0;
    static constexpr std::int64_t max_cells = 4096;

    [[nodiscard]] static std::int64_t cell(double v) noexcept {
        return static_cast<std::int64_t>(std::floor(std::clamp(v / size, -1e15, 1e15)));
    }
    [[nodiscard]] static std::uint64_t key(std::int64_t i, std::int64_t j) noexcept {
        return (static_cast<std::uint64_t>(i) << 32U) ^ (static_cast<std::uint64_t>(j) & 0xFFFF'FFFFULL);
    }

    const std::vector<Edge>* edges_;
    std::unordered_map<std::uint64_t, std::vector<std::uint32_t>> cells_;
    std::vector<std::uint32_t> big_;
};

// ---------------------------------------------------------------- the water's pieces (rendertiles' Clip)

struct ClipPoint {
    std::int64_t lat = 0;
    std::int64_t lon = 0;
    bool cut = false; ///< a crossing the cut made
};

enum class Side : std::uint8_t { west, east, south, north };

[[nodiscard]] bool inside(const ClipPoint& p, Side side, std::int64_t c) noexcept {
    switch (side) {
    case Side::west: return p.lon >= c;
    case Side::east: return p.lon <= c;
    case Side::south: return p.lat >= c;
    case Side::north: return p.lat <= c;
    }
    return false;
}

/// `a + (b - a) * num / den` rounded to the nearest integer, ties upward, exactly.
[[nodiscard]] std::int64_t along(std::int64_t a, std::int64_t b, std::int64_t num, std::int64_t den) noexcept {
    Int128 n = static_cast<Int128>(b - a) * num;
    Int128 d = den;
    if (d < 0) {
        n = -n;
        d = -d;
    }
    const Int128 t = 2 * n + d;
    Int128 q = t / (2 * d);
    if (t < 0 && t % (2 * d) != 0) --q;
    return a + static_cast<std::int64_t>(q);
}

[[nodiscard]] ClipPoint cross(const ClipPoint& a, const ClipPoint& b, Side side, std::int64_t c) noexcept {
    if (side == Side::west || side == Side::east)
        return {.lat = along(a.lat, b.lat, c - a.lon, b.lon - a.lon), .lon = c, .cut = true};
    return {.lat = c, .lon = along(a.lon, b.lon, c - a.lat, b.lat - a.lat), .cut = true};
}

[[nodiscard]] std::vector<ClipPoint> clip_side(const std::vector<ClipPoint>& pts, Side side, std::int64_t c) {
    std::vector<ClipPoint> out;
    out.reserve(pts.size() + 4);
    if (pts.empty()) return out;
    ClipPoint prev = pts.back();
    bool prev_in = inside(prev, side, c);
    for (const ClipPoint& cur : pts) {
        const bool cur_in = inside(cur, side, c);
        if (cur_in) {
            if (!prev_in) out.push_back(cross(prev, cur, side, c));
            out.push_back(cur);
        } else if (prev_in) {
            out.push_back(cross(prev, cur, side, c));
        }
        prev = cur;
        prev_in = cur_in;
    }
    return out;
}

/// Twice the ring's signed area on the grid (longitude as x, latitude as y), exactly.
[[nodiscard]] Int128 area2(std::span<const ClipPoint> p) noexcept {
    Int128 s = 0;
    for (std::size_t i = 0; i < p.size(); ++i) {
        const ClipPoint& a = p[i];
        const ClipPoint& b = p[(i + 1) % p.size()];
        s += static_cast<Int128>(a.lon) * b.lat - static_cast<Int128>(b.lon) * a.lat;
    }
    return s;
}

[[nodiscard]] int sign(Int128 v) noexcept { return v > 0 ? 1 : (v < 0 ? -1 : 0); }

[[nodiscard]] std::vector<ClipPoint> points_of(const GridLine& g) {
    std::vector<ClipPoint> out(g.lat.size());
    for (std::size_t i = 0; i < g.lat.size(); ++i) out[i] = {.lat = g.lat[i], .lon = g.lon[i]};
    return out;
}

/// A ring clipped at a tile's edges: its vertices, and which are crossings the cut made.
struct Piece {
    GridLine g;
    std::vector<bool> cut;
};

/// Clip.ring: the ring clipped to the box (inclusive), or nothing when nothing of it with an area is left. A crossing
/// that meets a vertex (or the closing repeat) marks the vertex kept.
[[nodiscard]] std::optional<Piece> clip_ring(const GridLine& ring, const Box4& box) {
    std::vector<ClipPoint> pts = points_of(ring);
    pts = clip_side(pts, Side::west, box[1]);
    pts = clip_side(pts, Side::east, box[3]);
    pts = clip_side(pts, Side::south, box[0]);
    pts = clip_side(pts, Side::north, box[2]);
    std::vector<ClipPoint> out;
    out.reserve(pts.size());
    for (const ClipPoint& p : pts) {
        if (out.empty() || out.back().lat != p.lat || out.back().lon != p.lon) {
            out.push_back(p);
        } else if (p.cut) {
            out.back().cut = true;
        }
    }
    while (out.size() > 1 && out.front().lat == out.back().lat && out.front().lon == out.back().lon) {
        const bool cut = out.back().cut;
        out.pop_back();
        if (cut) out.front().cut = true;
    }
    if (out.size() < 3 || area2(out) == 0) return std::nullopt;
    Piece piece;
    for (const ClipPoint& p : out) {
        piece.g.lat.push_back(static_cast<std::int32_t>(p.lat));
        piece.g.lon.push_back(static_cast<std::int32_t>(p.lon));
        piece.cut.push_back(p.cut);
    }
    return piece;
}

[[nodiscard]] bool ring_inside(const GridLine& ring, const Box4& box) noexcept {
    for (std::size_t i = 0; i < ring.lat.size(); ++i) {
        if (ring.lat[i] < box[0] || ring.lat[i] > box[2] || ring.lon[i] < box[1] || ring.lon[i] > box[3]) return false;
    }
    return true;
}

/// `pts` as simplify leaves it at ladder `level` (KeepCodes.simplify over its codes; 0: unchanged), with simplify's
/// exceptions: a small ring, or a result of fewer than 3 points or 2 m² or less, stays whole.
[[nodiscard]] std::vector<V2> generalised(std::span<const V2> pts, std::size_t level) {
    if (level == 0 || pts.size() < tl::keep::min_points) return {pts.begin(), pts.end()};
    const std::vector<std::uint8_t> codes = keep_codes(pts);
    std::vector<V2> ring;
    for (std::size_t i = 0; i < pts.size(); ++i) {
        if (codes[i] >= level) ring.push_back(pts[i]);
    }
    if (ring.size() >= 3 && std::fabs(geometry::signed_area(ring)) > 2) return ring;
    return {pts.begin(), pts.end()};
}

// ---------------------------------------------------------------- a building's attributes

/// What a building or part carries besides its ring (Schema.BUILDING_ATTRIBUTES), as the packer derives it: what a
/// variant compares and holds. A field the scene's dict lacks is empty.
struct Attributes {
    double h = 0.0;
    double min = 0.0;
    std::optional<double> roof_h;
    std::optional<std::int64_t> year;
    std::int64_t levels = 0;
    std::optional<std::int64_t> parent_id;
    std::optional<std::string_view> roof;
    std::optional<std::string_view> orient;
    std::uint32_t wall_rgb = 0;
    std::uint32_t roof_rgb = 0;
    std::optional<std::string_view> mat_draw;
    std::optional<std::string_view> rmat_draw;
    std::optional<std::string_view> kind;
    bool relation = false;
    std::optional<bool> hidden;
    std::optional<bool> no_win;
    bool in_church = false;
    std::vector<std::string_view> guessed;
    std::vector<std::int64_t> places;
    std::vector<std::string_view> hours;
    std::uint64_t flags = 0;
    std::optional<std::string_view> shutter;             ///< trad['shutter']
    std::vector<std::int64_t> party;                     ///< the ring's corners that start a party wall, in its order
    std::array<std::optional<std::string_view>, 3> tags; ///< tag:building, tag:building:part, tag:roof:direction
};

/// Two doubles alike as Kotlin's Double.equals: by their bits.
[[nodiscard]] bool same(double a, double b) noexcept {
    return std::bit_cast<std::uint64_t>(a) == std::bit_cast<std::uint64_t>(b);
}

/// Schema.canonical's equality of two attribute sets.
[[nodiscard]] bool same(const Attributes& a, const Attributes& b) noexcept {
    const auto same_opt = [](const std::optional<double>& x, const std::optional<double>& y) {
        return x.has_value() == y.has_value() && (!x || same(*x, *y));
    };
    return same(a.h, b.h) && same(a.min, b.min) && same_opt(a.roof_h, b.roof_h) && a.year == b.year &&
           a.levels == b.levels && a.parent_id == b.parent_id && a.roof == b.roof && a.orient == b.orient &&
           a.wall_rgb == b.wall_rgb && a.roof_rgb == b.roof_rgb && a.mat_draw == b.mat_draw &&
           a.rmat_draw == b.rmat_draw && a.kind == b.kind && a.relation == b.relation && a.hidden == b.hidden &&
           a.no_win == b.no_win && a.in_church == b.in_church && a.guessed == b.guessed && a.places == b.places &&
           a.hours == b.hours && a.flags == b.flags && a.shutter == b.shutter && a.party == b.party && a.tags == b.tags;
}

// ---------------------------------------------------------------- the cut

/// Which list of the scene a feature comes from.
enum class Source : std::uint8_t {
    building,
    part,
    variant,
    quay,
    tree,
    thing,
    item,
    road,
    rail,
    line,
    area,
    land,
    canal,
    place,
    water,
};

/// A feature before it is written: its layer, its record, its tile and cell, its box and top, and its order among its
/// layer's features of its cell.
struct Pending {
    tl::LayerId layer = tl::LayerId::none;
    Source source = Source::building;
    std::uint32_t index = 0;
    std::uint16_t cell = 0;
    Box4 box{};
    std::int32_t top_cm = 0;
    std::int64_t order = 0;
    std::uint32_t geom = 0; ///< an area's, a land cover's or the water's: its ring at the level (rings_)
};

/// UTF-16 units of UTF-8 text (Kotlin's String.length).
[[nodiscard]] std::size_t utf16_length(std::string_view s) {
    std::size_t n = 0;
    for (const char32_t c : text::code_points(s)) n += c >= 0x10000 ? 2 : 1;
    return n;
}

/// `re.match(r'\d+', s)` read as Java does (Character.isDigit on each UTF-16 unit, BigInteger's low 64 bits).
[[nodiscard]] std::optional<std::int64_t> leading_number(std::string_view s) {
    std::uint64_t v = 0;
    bool any = false;
    for (const char32_t c : text::code_points(s)) {
        const auto d = digit_of(c);
        if (!d) break;
        v = v * 10 + static_cast<std::uint64_t>(*d);
        any = true;
    }
    if (!any) return std::nullopt;
    return static_cast<std::int64_t>(v);
}

/// One of regions.py's lines (rendertiles' LINE sources 1 to 6): which list, its index, its line on the grid, its key
/// among the ground's (0: none), and how far and how high the renders draw it.
struct RegionLine {
    std::uint8_t source = 0;
    std::uint32_t index = 0;
    GridLine g;
    std::int64_t ground_key = 0;
};

/// One of regions.py's items (rendertiles' ITEM sources 0 to 4).
struct ItemFeature {
    std::uint8_t source = 0;
    std::uint32_t index = 0;
    std::int64_t id = 0;
    tl::GridPoint pos;
    bool exact = true; ///< pos is the item's own point (else the nearest grid point, and x and z are kept)
    double x = 0.0;
    double z = 0.0;
    std::optional<GridLine> poly;
    std::optional<double> h;
    std::optional<double> rotor;
    std::optional<double> crown; ///< a wood's tree's crown diameter
    const Tags* tags = nullptr;
    /// A crane's form (rendertiles' Cranes): 1 ship-to-shore, 2 a yard gantry; its axes and half sizes (a ship-to-shore
    /// crane's hu its distance to the water, its hv none).
    std::uint8_t crane = 0;
    double ux = 0.0;
    double uz = 0.0;
    double vx = 0.0;
    double vz = 0.0;
    double hu = 0.0;
    std::optional<double> hv;
};

/// A variant: a building's or part's attributes in the scene loaded at another level of the ladder.
struct VariantFeature {
    bool part = false;
    std::uint32_t index = 0;
    std::uint8_t level = 0;
    Attributes attributes;
};

/// How a scene is cut: the levels, whether rings carry keep codes, whether facades (doors, shopfronts) are drawn, the
/// one tile kept (a window's; none: every tile), and whether what the tiles cannot carry yet (a tradition's details)
/// refuses the cut (the packer's) or is left out (a window's).
struct CutPlan {
    std::span<const std::uint8_t> levels;
    bool keep = false;
    bool facades = true;
    std::optional<tl::TileKey> only;
    /// Whether the ground's and the lines' features carry their element (id, otype, part: load/Scene.hpp's Origin), by
    /// which the frame orders the ground across windows; the packer's tiles do not.
    bool identity = false;
};

/// The building's or part's attributes in `scene`, `grid` its water edges'. They view the scene's document, its
/// tradition and the core's own tables, never the scene itself, so they outlive it.
[[nodiscard]] Attributes attributes_of(const Scene& scene, const Building& b, bool part, const EdgeGrid& grid) {
    Attributes a;
    a.h = b.h;
    a.min = b.min;
    a.roof_h = b.roof_h;
    a.year = b.year;
    a.levels = b.levels;
    a.parent_id = b.parent_id;
    a.roof = b.roof;
    a.orient = b.orient;
    a.wall_rgb = wall_rgb(b);
    a.roof_rgb = roof_rgb(b);
    a.mat_draw = b.mat_draw;
    if (b.look != LookPath::none) {
        a.rmat_draw = b.rmat_draw;
        a.kind = b.kind;
    }
    a.relation = b.relation;
    if (!part) a.hidden = b.hidden;
    if (b.look == LookPath::generic || b.look == LookPath::tradition) a.no_win = b.no_win;
    a.in_church = b.in_church;
    for (const Guess g : b.guessed.keys()) a.guessed.push_back(key_of(g));
    for (const std::uint32_t k : b.places) {
        a.places.push_back(scene.nodes[k].id);
        a.hours.push_back(scene.nodes[k].tags.get("opening_hours").value_or(""));
    }
    // the derived flags, with the renders' own tests (amsterdam.py's _draw_building)
    namespace c = tl::building;
    const auto btype = b.tags.get("building");
    if (in(btype, churches) || b.tags.is("building:part", "church") || b.in_church) a.flags |= c::flag_church;
    if (in(btype, warehouses)) a.flags |= c::flag_warehouse;
    if (!b.places.empty() || in(btype, shop_floors)) a.flags |= c::flag_shop_floor;
    if (b.tags.is("building", "construction")) a.flags |= c::flag_construction;
    if (grid.near_water(b.poly, near_water_reach)) a.flags |= c::flag_near_water;
    if (b.tags.is("building", "houseboat")) a.flags |= c::flag_houseboat;
    if (b.year.value_or(0) >= 1960 && !in(btype, churches)) a.flags |= c::flag_modern;
    if (b.col_draw && !b.col_draw->empty()) a.flags |= c::flag_wall_drawn;
    // a tradition's kit and the details it gave the building (its values are the tradition's, which the pack's style
    // carries whole), its shutters' colour, and the corners its party walls start at
    if (b.look == LookPath::tradition) {
        const TradDetails& d = b.trad;
        a.flags |= c::flag_kit;
        if (!d.win.is_null()) a.flags |= c::flag_kit_windows;
        if (!d.band.is_null()) a.flags |= c::flag_kit_band;
        if (!d.boards.is_null()) a.flags |= c::flag_kit_boards;
        if (d.cornice) a.flags |= c::flag_kit_cornice;
        if (d.hoist) a.flags |= c::flag_kit_hoist;
        if (!d.chimney.is_null()) a.flags |= c::flag_kit_chimney;
        if (d.crenel) a.flags |= c::flag_kit_crenel;
        a.shutter = d.shutter;
    }
    if (b.party) {
        const std::size_t n = b.poly.size();
        std::size_t at = 0;
        for (const std::array<V2, 2>& e : *b.party) {
            while (at < n && !(b.poly[at] == e[0] && b.poly[(at + 1) % n] == e[1])) ++at;
            if (at < n) a.party.push_back(static_cast<std::int64_t>(at++));
        }
    }
    a.tags = {b.tags.get("building"), b.tags.get("building:part"), b.tags.get("roof:direction")};
    return a;
}

class Cutter {
public:
    Cutter(const Scene& s, const tl::Frame& f, const CutPlan& plan)
        : s_(s), f_(f), plan_(plan), edges_(water_edges(s)), grid_(edges_) {}

    [[nodiscard]] const EdgeGrid& grid() const noexcept { return grid_; }

    void add_variant(VariantFeature v) { variants_.push_back(std::move(v)); }

    [[nodiscard]] std::expected<std::vector<tl::TileFill>, CutError> run() {
        rings_of(s_.buildings, buildings_, "building");
        rings_of(s_.parts, parts_, "part");
        for (const std::vector<V2>& r : s_.water) water_.push_back(water_line(r));
        for (const std::vector<V2>& r : s_.water_holes) holes_.push_back(water_line(r));
        // refused already (what the tiles cannot carry yet, a vertex off the grid, which would be placed at latitude 0
        // and longitude 0): nothing is placed
        if (error_) return std::unexpected(*error_);
        for (const std::uint8_t level : plan_.levels) {
            z_ = level;
            const bool full = level >= 14;
            if (full) {
                place_buildings(Source::building, s_.buildings, buildings_, tl::LayerId::building);
                place_buildings(Source::part, s_.parts, parts_, tl::LayerId::part);
                place_variants();
                place_quays();
                place_trees();
                place_things();
                place_items();
                place_roads();
                place_rails();
                place_lines();
            }
            // a coarse level takes the ground at the ladder's far tolerance
            const auto coarse = static_cast<std::size_t>(full ? 0 : tl::keep::level_of(4.0));
            place_ground(coarse);
            place_labels();
            place_land(coarse);
            place_water(coarse);
            if (error_) return std::unexpected(*error_);
        }
        std::vector<tl::TileFill> out;
        out.reserve(order_.size());
        for (const tl::TileKey& key : order_) out.push_back(write(key, tiles_[key.packed()]));
        if (error_) return std::unexpected(*error_);
        return out;
    }

private:
    // ------------------------------------------------ the grid

    template <class What>
    [[nodiscard]] std::int32_t lat(double z, What what) {
        if (const auto q = tl::grid_lat(f_, z)) return *q;
        fail(std::format("{}: z = {} is not on the grid of the frame", what(), z));
        return 0;
    }
    template <class What>
    [[nodiscard]] std::int32_t lon(double x, What what) {
        if (const auto q = tl::grid_lon(f_, x)) return *q;
        fail(std::format("{}: x = {} is not on the grid of the frame", what(), x));
        return 0;
    }
    template <class What>
    [[nodiscard]] tl::GridPoint point(V2 p, What what) {
        return {lat(p.z, what), lon(p.x, what)};
    }
    template <class What>
    [[nodiscard]] GridLine grid_line(std::span<const V2> pts, What what) {
        GridLine g;
        g.lat.reserve(pts.size());
        g.lon.reserve(pts.size());
        for (const V2& p : pts) {
            g.lat.push_back(lat(p.z, what));
            g.lon.push_back(lon(p.x, what));
        }
        return g;
    }
    /// A water ring on the grid (SceneToTiles' waterGeom): its mapped vertices exactly, and each coordinate that lies
    /// on no grid point at its nearest one. Only the sea regions.py closes from a coastline has such points
    /// (sea_from_coast computes them along a box 0.06° to 0.1° beyond the region, from the coastline's ends), so its
    /// box's edges move by at most half a grid step (6 mm) there.
    [[nodiscard]] GridLine water_line(std::span<const V2> pts) const {
        GridLine g;
        g.lat.reserve(pts.size());
        g.lon.reserve(pts.size());
        for (const V2& p : pts) {
            const tl::GridPoint near = tl::nearest_grid(f_, p.x, p.z);
            g.lat.push_back(tl::grid_lat(f_, p.z).value_or(near.lat));
            g.lon.push_back(tl::grid_lon(f_, p.x).value_or(near.lon));
        }
        return g;
    }
    /// A ring on the grid with its keep codes, where the plan's rings carry them.
    template <class What>
    [[nodiscard]] GridLine keyed_ring(std::span<const V2> pts, What what) {
        GridLine g = grid_line(pts, what);
        if (plan_.keep) g.keep = keep_codes(pts);
        return g;
    }

    /// The cut's first failure.
    void fail(std::string what) {
        if (!error_) error_ = CutError{.what = std::move(what)};
    }

    void rings_of(const std::vector<Building>& list, std::vector<GridLine>& out, std::string_view label) {
        out.reserve(list.size());
        for (std::uint32_t i = 0; i < list.size(); ++i) {
            out.push_back(keyed_ring(list[i].poly, [&] { return std::format("{} {} ({})", label, i, list[i].id); }));
        }
    }

    // ------------------------------------------------ placing

    /// Files a feature in the tile and cell of its reference point.
    void put(Pending p, tl::GridPoint ref) {
        const tl::TileKey key = tl::tile_of(z_, ref.lat, ref.lon);
        p.cell = tl::cell_of(key, ref.lat, ref.lon);
        add(key, p);
    }

    /// Whether the cut keeps tile `key` (a window keeps its own alone).
    [[nodiscard]] bool wanted(const tl::TileKey& key) const noexcept {
        return !plan_.only || key.packed() == plan_.only->packed();
    }

    void add(const tl::TileKey& key, const Pending& p) {
        if (!wanted(key)) return;
        auto [it, fresh] = tiles_.try_emplace(key.packed());
        if (fresh) order_.push_back(key);
        it->second.push_back(p);
    }

    /// A line's feature: in the tile of its middle vertex, its box widened by `pad` metres, its top `top` metres up. A
    /// line without a point has no middle vertex: it is filed in no tile (the packer, whose linePending indexes its
    /// middle vertex, cannot cut one at all; fuzz_load gave a tram way of an empty geometry, 26 October).
    void put_line(Pending p, const GridLine& g, double pad, double top) {
        if (g.lat.empty()) return;
        const std::size_t m = g.lat.size() / 2;
        p.box = widen(g.box(), pad, f_);
        p.top_cm = cm_up(top);
        put(p, {g.lat[m], g.lon[m]});
    }

    void place_buildings(Source source, const std::vector<Building>& list, const std::vector<GridLine>& rings,
                         tl::LayerId layer) {
        auto& refs = source == Source::part ? part_refs_ : building_refs_;
        refs.assign(list.size(), {});
        for (std::uint32_t i = 0; i < list.size(); ++i) {
            const Building& b = list[i];
            const V2 c = geometry::centroid(b.poly);
            refs[i] = tl::nearest_grid(f_, c.x, c.z);
            put({.layer = layer,
                 .source = source,
                 .index = i,
                 .box = rings[i].box(),
                 .top_cm = cm_up(b.h + chimney),
                 .order = i},
                refs[i]);
        }
    }

    /// The variants, placed where their base feature is.
    void place_variants() {
        for (std::uint32_t k = 0; k < variants_.size(); ++k) {
            const VariantFeature& v = variants_[k];
            const auto layer = v.part ? tl::LayerId::part : tl::LayerId::building;
            const tl::GridPoint ref = (v.part ? part_refs_ : building_refs_)[v.index];
            put({.layer = tl::LayerId::variant,
                 .source = Source::variant,
                 .index = k,
                 .box = point_box(ref),
                 .top_cm = 0,
                 .order = (std::int64_t{v.index} << 8U) | (static_cast<std::int64_t>(layer) << 40U) | v.level},
                ref);
        }
    }

    void place_quays() {
        quay_ends_.clear();
        for (std::uint32_t k = 0; k < edges_.size(); ++k) {
            const Edge& e = edges_[k];
            const auto what = [&] { return std::format("quay edge {}", k); };
            const tl::GridPoint a = point(e.a, what);
            const tl::GridPoint b = point(e.b, what);
            const Box4 box{std::min(a.lat, b.lat), std::min(a.lon, b.lon), std::max(a.lat, b.lat),
                           std::max(a.lon, b.lon)};
            quay_ends_.emplace_back(a, b);
            put({.layer = tl::LayerId::quay,
                 .source = Source::quay,
                 .index = k,
                 .box = widen(box, 1.0, f_),
                 .top_cm = 0,
                 .order = k},
                tl::centre_of(box));
        }
    }

    void place_trees() {
        tree_nodes_.assign(s_.nodes.size(), false);
        for (std::uint32_t i = 0; i < s_.trees.size(); ++i) {
            const Tree& t = s_.trees[i];
            tree_nodes_[t.node] = true;
            const Node& n = s_.nodes[t.node];
            const tl::GridPoint p = point({n.x, n.z}, [&] { return std::format("tree {} ({})", i, n.id); });
            put({.layer = tl::LayerId::tree,
                 .source = Source::tree,
                 .index = i,
                 .box = widen(point_box(p), pymath::java::max(tree_symbol, crown_reach * t.crown), f_),
                 .top_cm = cm_up(t.h + t.crown / 2),
                 .order = i},
                p);
        }
    }

    /// The buildings facades are drawn on (`facade_details`' `blds`: shown, with a height, not a houseboat) and their
    /// boxes, x0, x1, z0, z1.
    void facade_buildings() {
        facades_.clear();
        for (std::uint32_t i = 0; i < s_.buildings.size(); ++i) {
            const Building& b = s_.buildings[i];
            if (b.hidden || b.h == 0.0 || b.tags.is("building", "houseboat")) continue;
            Box bb{.x0 = b.poly[0].x, .x1 = b.poly[0].x, .z0 = b.poly[0].z, .z1 = b.poly[0].z};
            for (const V2& q : b.poly) {
                bb.x0 = pymath::min(bb.x0, q.x); // `if (q.x < x0) x0 = q.x`: Python's min
                bb.x1 = pymath::max(bb.x1, q.x);
                bb.z0 = pymath::min(bb.z0, q.z);
                bb.z1 = pymath::max(bb.z1, q.z);
            }
            facades_.emplace_back(i, bb);
        }
    }

    /// The building whose facade carries the node's door or shopfront: a node with a house number or a place style
    /// other than a service, in the first facade building whose box and ring hold it.
    [[nodiscard]] std::optional<std::uint32_t> door_building(const Node& n) const {
        const bool door = n.tags.truthy("addr:housenumber") || (n.style && s_.styles[*n.style].cat != "service");
        if (!door) return std::nullopt;
        for (const auto& [i, bb] : facades_) {
            if (bb.x0 <= n.x && n.x <= bb.x1 && bb.z0 <= n.z && n.z <= bb.z1 &&
                geometry::pip(n.x, n.z, s_.buildings[i].poly)) {
                return i;
            }
        }
        return std::nullopt;
    }

    /// A cell of road_grid.
    [[nodiscard]] static std::uint64_t road_cell(std::int64_t gx, std::int64_t gz) noexcept {
        return (static_cast<std::uint64_t>(gx) << 32U) ^ (static_cast<std::uint64_t>(gz) & 0xFFFF'FFFFU);
    }

    /// render/amsterdam.py:1709 road_grid: every segment of S['roads'] in each 20 m cell of its box and the cells
    /// around it, in the roads' order; made once. A segment whose box would fill more than max_segment_cells, or the
    /// grid past max_grid_cells (a road kilometres long, or a fuzzed one far out: CI's fuzz_load ran out of memory
    /// here), is kept apart in long_roads_ with its cells' box, which road_dist checks as the grid would list it: the
    /// nearest distance is the same in any order.
    [[nodiscard]] const std::unordered_map<std::uint64_t, std::vector<std::array<V2, 2>>>& road_grid() {
        if (road_grid_made_) return road_grid_;
        road_grid_made_ = true;
        const auto cell = [](double v) { return pymath::to_int(pymath::floor_div(v, 20.0)); };
        double filled = 0;
        for (const Road& r : s_.roads) {
            for (std::size_t i = 0; i + 1 < r.pts.size(); ++i) {
                const V2 a = r.pts[i];
                const V2 b = r.pts[i + 1];
                const LongRoad box{.a = a,
                                   .b = b,
                                   .gx0 = cell(pymath::min(a.x, b.x)) - 1,
                                   .gz0 = cell(pymath::min(a.z, b.z)) - 1,
                                   .gx1 = cell(pymath::max(a.x, b.x)) + 2,
                                   .gz1 = cell(pymath::max(a.z, b.z)) + 2};
                const double cells = static_cast<double>(box.gx1 - box.gx0) * static_cast<double>(box.gz1 - box.gz0);
                if (cells > max_segment_cells || filled + cells > max_grid_cells) {
                    long_roads_.push_back(box);
                    continue;
                }
                filled += cells;
                for (std::int64_t gx = box.gx0; gx < box.gx1; ++gx) {
                    for (std::int64_t gz = box.gz0; gz < box.gz1; ++gz) road_grid_[road_cell(gx, gz)].push_back({a, b});
                }
            }
        }
        return road_grid_;
    }

    /// render/amsterdam.py:1719 road_dist: how far (x, z) lies from the nearest road segment its 20 m cell lists (99
    /// where it lists none).
    [[nodiscard]] double road_dist(double x, double z) {
        double best = 99.0;
        const auto near = [&](const V2& a, const V2& b) {
            const double ux = b.x - a.x;
            const double uz = b.z - a.z;
            double l2 = (ux * ux) + (uz * uz);
            if (l2 == 0.0) l2 = 1.0; // `or 1`
            const double t = pymath::max(0.0, pymath::min(1.0, (((x - a.x) * ux) + ((z - a.z) * uz)) / l2));
            best = pymath::min(best, pymath::hypot(a.x + (ux * t) - x, a.z + (uz * t) - z));
        };
        const std::int64_t gx = pymath::to_int(pymath::floor_div(x, 20.0));
        const std::int64_t gz = pymath::to_int(pymath::floor_div(z, 20.0));
        const auto& grid = road_grid();
        if (const auto it = grid.find(road_cell(gx, gz)); it != grid.end()) {
            for (const auto& [a, b] : it->second) near(a, b);
        }
        for (const LongRoad& r : long_roads_) {
            if (gx >= r.gx0 && gx < r.gx1 && gz >= r.gz0 && gz < r.gz1) near(r.a, r.b);
        }
        return best;
    }

    /// render/amsterdam.py:1727 street_wall: the wall of building `b` a door or shopfront at (x, z) opens onto, near
    /// the point and with a mapped street just outside it (the party wall to a neighbour is often the nearest), as its
    /// first corner's index in the ring; the first of the least score.
    [[nodiscard]] std::optional<std::uint32_t> street_wall(const Building& b, double x, double z) {
        const std::vector<V2>& poly = b.poly;
        const V2 centre = geometry::centroid(poly);
        std::optional<std::uint32_t> best;
        double least = 0.0;
        const std::size_t n = poly.size();
        for (std::size_t i = 0; i < n; ++i) {
            const V2 a = poly[i];
            const V2 c = poly[(i + 1) % n];
            const std::optional<geometry::StreetWall> w = geometry::street_wall_at(a, c, x, z);
            if (!w) continue;
            // its normal outward, by the centroid
            double nx = w->uz / w->length;
            double nz = -w->ux / w->length;
            const double mx = (a.x + c.x) / 2;
            const double mz = (a.z + c.z) / 2;
            if ((nx * (mx - centre.x)) + (nz * (mz - centre.z)) < 0) {
                nx = -nx;
                nz = -nz;
            }
            const double out_d = road_dist(w->qx + (nx * 3.5), w->qz + (nz * 3.5));
            const double score =
                pymath::hypot(w->qx - x, w->qz - z) + (1.5 * pymath::min(out_d, 25.0)) + (w->length < 2.5 ? 40.0 : 0.0);
            if (!best || score < least) {
                best = static_cast<std::uint32_t>(i);
                least = score;
            }
        }
        return best;
    }

    /// A street lamp, a bench, a rack or a basket, a bollard, a place; and where facades are drawn (the living map;
    /// regions set no_facades), every door and shopfront on one, with its street wall, filed in its building's tile
    /// (where stage T finds the building's ring, from which the frame takes the wall and the door's order).
    void place_things() {
        if (plan_.facades) facade_buildings();
        poi_.assign(s_.nodes.size(), false);
        for (const std::uint32_t p : s_.pois) poi_[p] = true;
        doors_.assign(s_.nodes.size(), std::nullopt);
        walls_.assign(s_.nodes.size(), std::nullopt);
        for (std::uint32_t i = 0; i < s_.nodes.size(); ++i) {
            if (tree_nodes_[i]) continue;
            const Node& n = s_.nodes[i];
            const std::optional<std::uint32_t> door = plan_.facades ? door_building(n) : std::nullopt;
            doors_[i] = door;
            if (door) walls_[i] = street_wall(s_.buildings[*door], n.x, n.z);
            const bool thing = n.tags.is("highway", "street_lamp") || in(n.tags.get("amenity"), furniture) ||
                               in(n.tags.get("barrier"), bollards) || poi_[i] || door.has_value();
            if (!thing) continue;
            const tl::GridPoint p = point({n.x, n.z}, [&] { return std::format("node {} ({})", i, n.id); });
            put({.layer = tl::LayerId::thing,
                 .source = Source::thing,
                 .index = i,
                 .box = widen(point_box(p), thing_reach, f_),
                 .top_cm = cm_up(thing_reach),
                 .order = i},
                door ? building_refs_[*door] : p);
        }
    }

    /// rendertiles' Drawn.item: (reach from its position, top) of an item of `source`.
    [[nodiscard]] static std::pair<double, double> item_drawn(const ItemFeature& it) {
        // Python's `x or default`: a height of 0 is not mapped either
        const std::optional<double> h = it.h && *it.h != 0.0 ? it.h : std::nullopt;
        switch (it.source) {
        case 0: { // a crane: on a quay its boom out over the water, in a yard a portal within its outline
            const double height = num(it.tags->get("height")).value_or(44.0);
            return {it.poly ? 5.0 : 115.0, height + 20.0};
        }
        case 1: return {2.0, h.value_or(30.0) + 1.3}; // a mast, and a lighting mast's frame of floodlights
        case 2: return {pymath::java::max(8.7, h.value_or(0.0) / 30), h.value_or(0.0)}; // a tower and its cab
        case 3: return {0.0, h.value_or(0.0)}; // a pylon: the power lines draw their own towers
        case 4: {                              // a turbine: its hub 2 m upwind, its blades
            const double r = (it.rotor && *it.rotor != 0.0 ? *it.rotor : 100.0) / 2;
            return {r + 3.0, h.value_or(100.0) + r};
        }
        case 5: { // a wood's tree: its clumps within a crown of it, up to 0.7 of its height and a crown
            const double crown = it.crown.value_or(0.0);
            return {crown, (it.h.value_or(0.0) * 0.7) + crown};
        }
        default: return {0.0, h.value_or(0.0)};
        }
    }

    void place_items() {
        items_.clear();
        const auto item = [&](std::uint8_t source, std::uint32_t i, double x, double z, std::int64_t id,
                              std::optional<double> h, std::optional<double> rotor,
                              const std::optional<std::vector<V2>>& poly, const Tags* tags,
                              std::optional<double> crown) {
            const auto what = [&] { return std::format("item {} of source {}", i, source); };
            ItemFeature it{.source = source,
                           .index = i,
                           .id = id,
                           .x = x,
                           .z = z,
                           .h = h,
                           .rotor = rotor,
                           .crown = crown,
                           .tags = tags};
            const auto qlat = tl::grid_lat(f_, z);
            const auto qlon = tl::grid_lon(f_, x);
            it.exact = qlat.has_value() && qlon.has_value();
            it.pos = it.exact ? tl::GridPoint{*qlat, *qlon} : tl::nearest_grid(f_, x, z);
            if (poly) it.poly = grid_line(*poly, what);
            Box4 box = point_box(it.pos);
            if (it.poly) {
                const Box4 b = it.poly->box();
                box = {std::min(box[0], b[0]), std::min(box[1], b[1]), std::max(box[2], b[2]), std::max(box[3], b[3])};
            }
            const auto [reach, top] = item_drawn(it);
            items_.push_back(std::move(it));
            const ItemFeature& placed = items_.back();
            put({.layer = tl::LayerId::item,
                 .source = Source::item,
                 .index = static_cast<std::uint32_t>(items_.size() - 1),
                 .box = widen(box, reach, f_),
                 .top_cm = cm_up(top),
                 .order = (std::int64_t{source} << 40U) | i},
                placed.pos);
        };
        const std::array<std::pair<std::uint8_t, const std::vector<Item>*>, 4> lists{
            {{0, &s_.cranes}, {1, &s_.masts}, {2, &s_.mtowers}, {4, &s_.turbines}}};
        for (const auto& [source, list] : lists) {
            for (std::uint32_t i = 0; i < list->size(); ++i) {
                const Item& d = (*list)[i];
                item(source, i, d.x, d.z, d.id, d.h, d.rotor, d.poly, &d.tags, std::nullopt);
                if (source == 0) crane_form(d, items_.back());
            }
        }
        static const Tags none;
        for (std::uint32_t i = 0; i < s_.towers.size(); ++i) {
            const Tower& t = s_.towers[i];
            item(3, i, t.at.x, t.at.z, t.id, t.h, std::nullopt, std::nullopt, &none, std::nullopt);
        }
        // the trees a park or a zoo plants in its woods
        for (std::uint32_t i = 0; i < s_.woods.size(); ++i) {
            const WoodTree& t = s_.woods[i];
            item(5, i, t.x, t.z, t.id, t.h, std::nullopt, std::nullopt, &none, t.crown);
        }
    }

    /// A crane's form as regions.py's draw_items derives it from the whole scene (rendertiles' Cranes): on a quay (a
    /// crane node within 60 m of its nearest quay edge, the first of two as near) the ship-to-shore shape, its boom
    /// along the edge's normal, turned where the other side's point dw + 25 m out lies on water and this side's does
    /// not; in a yard a portal along its outline's longest edge (its projections' half extents, at least 4 and 3 m) or
    /// its nearest crane rail's segment (by the segment's middle; 9 and 14 m), an outline over 2.5 times longer than
    /// wide spanned by one portal 4 m deep. Every value the port's double, by its arithmetic.
    void crane_form(const Item& cr, ItemFeature& out) const {
        const double x = cr.x;
        const double z = cr.z;
        double dw = 1e9;
        std::optional<V2> wn;
        for (const Edge& e : edges_) {
            const double ux = e.b.x - e.a.x;
            const double uz = e.b.z - e.a.z;
            double l2 = (ux * ux) + (uz * uz);
            if (l2 == 0.0) l2 = 1.0;
            const double t = pymath::max(0.0, pymath::min(1.0, (((x - e.a.x) * ux) + ((z - e.a.z) * uz)) / l2));
            const double d = pymath::hypot(e.a.x + (ux * t) - x, e.a.z + (uz * t) - z);
            if (d < dw) {
                dw = d;
                // water_edges' normal: (ez / Lg, -ex / Lg), into the water
                const double lg = pymath::hypot(ux, uz);
                const double sign = e.flip ? -1.0 : 1.0;
                wn = V2(sign * (uz / lg), sign * (-ux / lg));
            }
        }
        const auto on_water = [&](double px, double pz) {
            return std::ranges::any_of(s_.water, [&](const std::vector<V2>& r) { return geometry::pip(px, pz, r); });
        };
        if (!cr.poly && dw < 60 && wn) {
            V2 w = *wn;
            if (!on_water(x + (w.x * (dw + 25)), z + (w.z * (dw + 25))) &&
                on_water(x - (w.x * (dw + 25)), z - (w.z * (dw + 25)))) {
                w = V2(-w.x, -w.z); // the boom reaches over the side where the water is
            }
            out.crane = 1;
            out.ux = w.x;
            out.uz = w.z;
            out.vx = -w.z;
            out.vz = w.x;
            out.hu = dw;
            return;
        }
        double ux = 1.0;
        double uz = 0.0;
        double hu = 9.0;
        double hv = 14.0;
        const auto dist = [](V2 a, V2 b) {
            const std::array<double, 2> p{a.x, a.z};
            const std::array<double, 2> q{b.x, b.z};
            return pymath::dist(p, q);
        };
        if (cr.poly && cr.poly->size() >= 4) {
            const std::vector<V2>& poly = *cr.poly;
            const std::size_t n = poly.front() == poly.back() ? poly.size() - 1 : poly.size();
            std::size_t e0 = 0;
            double bd = dist(poly[0], poly[1 % n]);
            for (std::size_t i = 0; i < n; ++i) {
                const double d = dist(poly[i], poly[(i + 1) % n]);
                if (d > bd) {
                    bd = d;
                    e0 = i;
                }
            }
            const V2 a = poly[e0];
            const V2 b = poly[(e0 + 1) % n];
            ux = b.x - a.x;
            uz = b.z - a.z;
            double lg = pymath::hypot(ux, uz);
            if (lg == 0.0) lg = 1.0;
            ux /= lg;
            uz /= lg;
            // the first of the largest and of the smallest of the projections, as Python's max and min
            double u0 = 0;
            double u1 = 0;
            double v0 = 0;
            double v1 = 0;
            for (std::size_t i = 0; i < n; ++i) {
                const double pu = ((poly[i].x - x) * ux) + ((poly[i].z - z) * uz);
                const double pv = (-(poly[i].x - x) * uz) + ((poly[i].z - z) * ux);
                if (i == 0 || pu < u0) u0 = pu;
                if (i == 0 || pu > u1) u1 = pu;
                if (i == 0 || pv < v0) v0 = pv;
                if (i == 0 || pv > v1) v1 = pv;
            }
            hu = pymath::max(4.0, (u1 - u0) / 2);
            hv = pymath::max(3.0, (v1 - v0) / 2);
        } else {
            double best = 1e9;
            for (const Railway& r : s_.rail) {
                if (!r.crane) continue;
                for (std::size_t i = 0; i + 1 < r.pts.size(); ++i) {
                    const V2 p = r.pts[i];
                    const V2 q = r.pts[i + 1];
                    const double d = pymath::hypot(((p.x + q.x) / 2) - x, ((p.z + q.z) / 2) - z);
                    if (d < best) {
                        best = d;
                        double l = dist(p, q);
                        if (l == 0.0) l = 1.0;
                        ux = (q.x - p.x) / l;
                        uz = (q.z - p.z) / l;
                    }
                }
            }
        }
        double vx = -uz;
        double vz = ux;
        if (hu > 2.5 * hv) {
            // an outline much longer than wide is the crane's runway along its rails: one portal spans it, 4 m deep
            std::swap(ux, vx);
            std::swap(uz, vz);
            hu = hv;
            hv = 4.0;
        }
        out.crane = 2;
        out.ux = ux;
        out.uz = uz;
        out.vx = vx;
        out.vz = vz;
        out.hu = hu;
        out.hv = hv;
    }

    void place_roads() {
        // the road loop's marks: a bridge's railing lines, a sett road's courses, ranked in the roads' drawing order
        std::vector<std::uint32_t> order(s_.roads.size());
        for (std::uint32_t i = 0; i < order.size(); ++i) order[i] = i;
        const auto rank = [&](std::uint32_t i) {
            return std::ranges::find(road_order, s_.roads[i].hw) - road_order.begin();
        };
        std::ranges::stable_sort(order, {}, rank);
        marks_.assign(s_.roads.size(), 0);
        for (std::size_t r = 0; r < order.size(); ++r) {
            const Road& road = s_.roads[order[r]];
            if (road.bridge || road.tags.is("surface", "sett")) {
                marks_[order[r]] = ground_key(gk::marks, gk::marks_road, r);
            }
        }
        lines_.clear();
        lines_.reserve(s_.roads.size());
        for (std::uint32_t i = 0; i < s_.roads.size(); ++i) {
            const Road& r = s_.roads[i];
            lines_.push_back(grid_line(r.pts, [&] { return std::format("road {} ({})", i, r.id); }));
            put_line({.layer = tl::LayerId::road, .source = Source::road, .index = i, .order = i}, lines_.back(),
                     r.w / 2 + road_side, r.bridge ? bridge_top : road_top);
        }
    }

    void place_rails() {
        rails_.clear();
        for (std::uint32_t i = 0; i < s_.rails.size(); ++i) {
            rails_.push_back(grid_line(s_.rails[i], [&] { return std::format("tram track {}", i); }));
            put_line({.layer = tl::LayerId::line, .source = Source::rail, .index = i, .order = i}, rails_.back(),
                     tram_reach, road_top);
        }
    }

    /// rendertiles' Drawn.line for a big road: its asphalt by lanes, the widest the renders draw where its width
    /// cannot be read.
    [[nodiscard]] static double big_pad(const BigRoad& r) {
        constexpr double unknown = 30.0;
        const double lanes_f = num(r.tags.get("lanes")).value_or(r.hw.ends_with("link") ? 1.0 : 2.0);
        const auto lanes_l = pymath::checked::to_int(lanes_f); // PyMath.int: no value past the doubles' integers
        if (!lanes_l) return unknown;
        const auto lanes = static_cast<std::int32_t>(static_cast<std::uint32_t>(*lanes_l)); // Long.toInt()
        const double w = num(r.tags.get("width")).value_or((lanes * 3.5) + (r.hw == "motorway" ? 3.0 : 1.0));
        return std::isfinite(w) ? (w / 2) + 0.5 : unknown;
    }

    /// regions.py's lines: railways, big roads, runways and taxiways, power lines, marine works, a park's edge.
    void place_lines() {
        region_lines_.clear();
        // the big roads in the stable order of their layer, the aeroways in AERO_ORDER's
        std::vector<std::uint32_t> big(s_.big.size());
        for (std::uint32_t i = 0; i < big.size(); ++i) big[i] = i;
        std::ranges::stable_sort(
            big, [&](std::uint32_t a, std::uint32_t b) { return s_.big[a].layer + 0.0 < s_.big[b].layer + 0.0; });
        std::vector<std::int64_t> big_keys(s_.big.size());
        for (std::size_t r = 0; r < big.size(); ++r) big_keys[big[r]] = ground_key(gk::over, 1, r);
        std::vector<std::uint32_t> aero(s_.aero.size());
        std::vector<int> aero_rank(s_.aero.size());
        for (std::uint32_t i = 0; i < aero.size(); ++i) {
            aero[i] = i;
            const auto* o = std::ranges::find(aero_order, s_.aero[i].kind, &AeroOrder::kind);
            if (o == aero_order.end()) {
                fail(std::format("aeroway {} has no order", s_.aero[i].kind));
                return;
            }
            aero_rank[i] = o->order;
        }
        std::ranges::stable_sort(aero, [&](std::uint32_t a, std::uint32_t b) { return aero_rank[a] < aero_rank[b]; });
        std::vector<std::int64_t> aero_keys(s_.aero.size());
        for (std::size_t r = 0; r < aero.size(); ++r) aero_keys[aero[r]] = ground_key(gk::over, 2, r);

        const auto line = [&](std::uint8_t source, std::uint32_t i, std::span<const V2> pts, std::int64_t key,
                              double pad, double top) {
            RegionLine rl{.source = source,
                          .index = i,
                          .g = grid_line(pts, [&] { return std::format("line {} of source {}", i, source); }),
                          .ground_key = key};
            region_lines_.push_back(std::move(rl));
            put_line({.layer = tl::LayerId::line,
                      .source = Source::line,
                      .index = static_cast<std::uint32_t>(region_lines_.size() - 1),
                      .order = (std::int64_t{source} << 40U) | i},
                     region_lines_.back().g, pad, top);
        };
        for (std::uint32_t i = 0; i < s_.rail.size(); ++i) {
            line(1, i, s_.rail[i].pts, ground_key(gk::over, 0, i), 2.0, road_top); // the ballast 1.6 m either side
        }
        for (std::uint32_t i = 0; i < s_.big.size(); ++i) {
            const BigRoad& r = s_.big[i];
            line(2, i, r.pts, big_keys[i], big_pad(r), 0.07 + (pymath::java::max(0.0, r.layer) * 0.5));
        }
        for (std::uint32_t i = 0; i < s_.aero.size(); ++i) {
            const Aero& a = s_.aero[i];
            double w = 0.0;
            if (a.kind == "runway") w = num(a.tags.get("width")).value_or(45.0);
            if (a.kind == "taxiway") w = num(a.tags.get("width")).value_or(23.0);
            if (a.kind == "taxilane" || a.kind == "stopway") w = num(a.tags.get("width")).value_or(15.0);
            line(3, i, a.pts, aero_keys[i], std::isfinite(w) ? (w / 2) + 1.0 : 30.0, road_top);
        }
        // power lines: three conductors 6 m apart, towers 38 m high and their tops 2 m more
        for (std::uint32_t i = 0; i < s_.power.size(); ++i) line(4, i, s_.power[i].pts, 0, 7.0, 40.0);
        for (std::uint32_t i = 0; i < s_.marine.size(); ++i) {
            const Marine& m = s_.marine[i];
            line(5, i, m.pts, ground_key(gk::over, 3, i), m.closed ? 0.5 : 5.0, 0.3);
        }
        for (std::uint32_t i = 0; i < s_.park.size(); ++i) {
            line(6, i, s_.park[i].ring, ground_key(gk::over, 5, i), 1.0, 0.15); // a dashed line on the ground
        }
        // gantries over a road (legs 0.35 m about its ends, its beam 0.45 m either side, 7 m unless mapped) and goods
        // conveyors (legs 1.3 m and the gallery 1.4 m either side, 0.4 m past the ends, 6 m unless mapped); Python's
        // `x or default`: a height of 0 is not mapped either
        const auto height = [](const Gantry& g, double otherwise) { return g.h && *g.h != 0.0 ? *g.h : otherwise; };
        for (std::uint32_t i = 0; i < s_.gantries.size(); ++i) {
            line(7, i, s_.gantries[i].pts, 0, 1.0, height(s_.gantries[i], 7.0));
        }
        for (std::uint32_t i = 0; i < s_.conveyors.size(); ++i) {
            line(8, i, s_.conveyors[i].pts, 0, 2.0, height(s_.conveyors[i], 6.0));
        }
        // a theme park's coaster tracks: their strokes about a metre either side, their posts down to the ground, a
        // metre above their highest point
        for (std::uint32_t i = 0; i < s_.coasters.size(); ++i) {
            const CoasterTrack& tr = s_.coasters[i];
            double top = tr.ys.empty() ? 0.0 : tr.ys.front();
            for (const double y : tr.ys) top = std::max(top, y);
            line(9, i, tr.pts, 0, 1.0, top + 1.0);
        }
    }

    /// The areas at a level's tolerance (`coarse`, 0: as the scene holds them).
    void place_ground(std::size_t coarse) {
        for (std::uint32_t i = 0; i < s_.areas.size(); ++i) {
            const std::vector<V2> poly = generalised(s_.areas[i].poly, coarse);
            rings_.push_back(keyed_ring(poly, [&] { return std::format("area {}", i); }));
            const Box4 box = rings_.back().box();
            put({.layer = tl::LayerId::ground,
                 .source = Source::area,
                 .index = i,
                 .box = box,
                 .top_cm = cm_up(area_top),
                 .order = i,
                 .geom = static_cast<std::uint32_t>(rings_.size() - 1)},
                tl::centre_of(box));
        }
    }

    /// Land cover (regions.py's S['land'], in its sorted order) at a level's tolerance.
    void place_land(std::size_t coarse) {
        for (std::uint32_t i = 0; i < s_.land.size(); ++i) {
            const std::vector<V2> poly = generalised(s_.land[i].poly, coarse);
            rings_.push_back(keyed_ring(poly, [&] { return std::format("land {}", i); }));
            const Box4 box = rings_.back().box();
            put({.layer = tl::LayerId::ground,
                 .source = Source::land,
                 .index = i,
                 .box = box,
                 .top_cm = cm_up(land_top),
                 .order = (std::int64_t{1} << 40U) | i,
                 .geom = static_cast<std::uint32_t>(rings_.size() - 1)},
                tl::centre_of(box));
        }
    }

    void place_labels() {
        label_points_.assign(s_.labels.size(), {});
        for (std::uint32_t i = 0; i < s_.labels.size(); ++i) {
            const Label& lb = s_.labels[i];
            if (lb.kind != "canal" || lb.pts.size() < 2) continue;
            const auto what = [&] { return std::format("canal label {}", i); };
            const std::size_t m = lb.pts.size() / 2;
            const tl::GridPoint mid = point(lb.pts[m], what);
            label_points_[i] = {mid, point(lb.pts[m - 1], what)};
            const double reach = 1.6 * static_cast<double>(utf16_length(lb.name)) + 3.2;
            put({.layer = tl::LayerId::label,
                 .source = Source::canal,
                 .index = i,
                 .box = widen(point_box(mid), reach, f_),
                 .order = i},
                mid);
        }
        for (std::uint32_t i = 0; i < s_.nodes.size(); ++i) {
            const Node& n = s_.nodes[i];
            if (!n.tags.has("place")) continue;
            const tl::GridPoint p = point({n.x, n.z}, [&] { return std::format("place node {}", i); });
            put({.layer = tl::LayerId::label,
                 .source = Source::place,
                 .index = i,
                 .box = point_box(p),
                 .order = (std::int64_t{1} << 40U) | i},
                p);
        }
    }

    /// The water and its islands at a level's tolerance, one piece per ring and tile it reaches, each with its keep
    /// codes from its own vertices (a crossing the cut made kept at every tolerance). A window's cut looks at its own
    /// tile alone; any other refuses a ring that reaches more than ring_reach_max tiles.
    void place_water(std::size_t coarse) {
        for (const bool hole : {false, true}) {
            const std::vector<std::vector<V2>>& list = hole ? s_.water_holes : s_.water;
            for (std::uint32_t i = 0; i < list.size(); ++i) {
                const auto what = [&] { return std::format("{} {}", hole ? "island" : "water", i); };
                const GridLine ring =
                    coarse == 0 ? (hole ? holes_ : water_)[i] : water_line(generalised(list[i], coarse));
                if (ring.lat.empty()) continue;
                const Box4 box = ring.box();
                const int turn = sign(area2(points_of(ring)));
                tl::TileKey t0 = tl::tile_of(z_, box[2], box[1]);
                tl::TileKey t1 = tl::tile_of(z_, box[0], box[3]);
                if (plan_.only) {
                    const tl::TileKey o = *plan_.only;
                    if (o.z != z_ || o.x < t0.x || o.x > t1.x || o.y < t0.y || o.y > t1.y) continue;
                    t0 = o;
                    t1 = o;
                } else if (const std::uint64_t reach = std::uint64_t{t1.x - t0.x + 1U} * (t1.y - t0.y + 1U);
                           reach > ring_reach_max) {
                    fail(std::format("{} reaches {} tiles of L{}, more than a ring may ({})", what(), reach, z_,
                                     ring_reach_max));
                    return;
                }
                for (std::uint32_t x = t0.x; x <= t1.x; ++x) {
                    for (std::uint32_t y = t0.y; y <= t1.y; ++y) {
                        const tl::TileKey key(z_, x, y);
                        if (!wanted(key)) continue;
                        const Box4 tb = tl::tile_box(key);
                        std::optional<Piece> piece;
                        if (ring_inside(ring, tb)) {
                            piece = Piece{.g = ring, .cut = std::vector<bool>(ring.lat.size(), false)};
                        } else {
                            piece = clip_ring(ring, tb);
                        }
                        if (!piece) continue;
                        if (sign(area2(points_of(piece->g))) != turn) continue; // a ring that crosses itself
                        if (plan_.keep) {
                            std::vector<V2> pts(piece->g.lat.size());
                            for (std::size_t k = 0; k < pts.size(); ++k) {
                                pts[k] = {f_.x(piece->g.lon[k]), f_.z(piece->g.lat[k])};
                            }
                            piece->g.keep = keep_codes(pts);
                            for (std::size_t k = 0; k < pts.size(); ++k) {
                                if (piece->cut[k]) piece->g.keep[k] = tl::keep::all;
                            }
                        }
                        const Box4 pb = piece->g.box();
                        const tl::GridPoint c = tl::centre_of(pb);
                        rings_.push_back(std::move(piece->g));
                        add(key, {.layer = tl::LayerId::water,
                                  .source = Source::water,
                                  .index = i | (hole ? hole_bit : 0U),
                                  .cell = tl::cell_of(key, c.lat, c.lon),
                                  .box = pb,
                                  .order = (hole ? std::int64_t{1} << 40U : 0) | i,
                                  .geom = static_cast<std::uint32_t>(rings_.size() - 1)});
                    }
                }
            }
        }
    }

    // ------------------------------------------------ writing

    /// The tags of a record the renders read for the layer: each `tag:<key>` column of its schema.
    static void tags(tl::LayerFill& lf, std::uint32_t f, const Tags& t) {
        const auto& columns = lf.schema().columns;
        for (std::size_t slot = 0; slot < columns.size(); ++slot) {
            const std::string_view name = columns[slot].name;
            if (!name.starts_with("tag:")) continue;
            const auto v = t.get(name.substr(4));
            if (!v) continue;
            if (columns[slot].codec == tl::Codec::strn) {
                lf.string_or_number(static_cast<std::uint8_t>(slot), f, v);
            } else {
                lf.string(static_cast<std::uint8_t>(slot), f, v);
            }
        }
    }

    /// Each attribute's place within the attribute columns (building_attributes::columns).
    struct AttributeSlots {
        std::uint8_t h;
        std::uint8_t min;
        std::uint8_t roof_h;
        std::uint8_t year;
        std::uint8_t levels;
        std::uint8_t parent_id;
        std::uint8_t roof;
        std::uint8_t orient;
        std::uint8_t wall_rgb;
        std::uint8_t roof_rgb;
        std::uint8_t mat_draw;
        std::uint8_t rmat_draw;
        std::uint8_t kind;
        std::uint8_t otype;
        std::uint8_t hidden;
        std::uint8_t no_win;
        std::uint8_t in_church;
        std::uint8_t guessed;
        std::uint8_t places;
        std::uint8_t place_hours;
        std::uint8_t flags;
        std::uint8_t shutter;
        std::uint8_t party;
        std::uint8_t tag_building;
        std::uint8_t tag_building_part;
        std::uint8_t tag_roof_direction;
    };
    static constexpr AttributeSlots attribute_slots{
        .h = tl::slot_of(tl::building_attributes::columns, "h"),
        .min = tl::slot_of(tl::building_attributes::columns, "min"),
        .roof_h = tl::slot_of(tl::building_attributes::columns, "roof_h"),
        .year = tl::slot_of(tl::building_attributes::columns, "year"),
        .levels = tl::slot_of(tl::building_attributes::columns, "levels"),
        .parent_id = tl::slot_of(tl::building_attributes::columns, "parent_id"),
        .roof = tl::slot_of(tl::building_attributes::columns, "roof"),
        .orient = tl::slot_of(tl::building_attributes::columns, "orient"),
        .wall_rgb = tl::slot_of(tl::building_attributes::columns, "wall_rgb"),
        .roof_rgb = tl::slot_of(tl::building_attributes::columns, "roof_rgb"),
        .mat_draw = tl::slot_of(tl::building_attributes::columns, "mat_draw"),
        .rmat_draw = tl::slot_of(tl::building_attributes::columns, "rmat_draw"),
        .kind = tl::slot_of(tl::building_attributes::columns, "kind"),
        .otype = tl::slot_of(tl::building_attributes::columns, "otype"),
        .hidden = tl::slot_of(tl::building_attributes::columns, "hidden"),
        .no_win = tl::slot_of(tl::building_attributes::columns, "no_win"),
        .in_church = tl::slot_of(tl::building_attributes::columns, "in_church"),
        .guessed = tl::slot_of(tl::building_attributes::columns, "guessed"),
        .places = tl::slot_of(tl::building_attributes::columns, "places"),
        .place_hours = tl::slot_of(tl::building_attributes::columns, "place_hours"),
        .flags = tl::slot_of(tl::building_attributes::columns, "flags"),
        .shutter = tl::slot_of(tl::building_attributes::columns, "shutter"),
        .party = tl::slot_of(tl::building_attributes::columns, "party"),
        .tag_building = tl::slot_of(tl::building_attributes::columns, "tag:building"),
        .tag_building_part = tl::slot_of(tl::building_attributes::columns, "tag:building:part"),
        .tag_roof_direction = tl::slot_of(tl::building_attributes::columns, "tag:roof:direction"),
    };

    /// The attribute columns of a building or variant, from `base` (the slot of the first, h).
    static void write_attributes(tl::LayerFill& lf, std::uint32_t f, std::uint8_t base, const Attributes& a) {
        constexpr const AttributeSlots& o = attribute_slots;
        const auto slot = [&](std::uint8_t at) { return static_cast<std::uint8_t>(base + at); };
        lf.decimal(slot(o.h), f, a.h);
        lf.decimal(slot(o.min), f, a.min);
        lf.decimal(slot(o.roof_h), f, a.roof_h);
        lf.nullable_integer(slot(o.year), f, a.year);
        lf.nullable_integer(slot(o.levels), f, a.levels);
        lf.nullable_integer(slot(o.parent_id), f, a.parent_id);
        lf.string(slot(o.roof), f, a.roof);
        lf.string(slot(o.orient), f, a.orient);
        lf.integer(slot(o.wall_rgb), f, a.wall_rgb);
        lf.integer(slot(o.roof_rgb), f, a.roof_rgb);
        lf.string(slot(o.mat_draw), f, a.mat_draw);
        lf.string(slot(o.rmat_draw), f, a.rmat_draw);
        lf.string(slot(o.kind), f, a.kind);
        if (a.relation) lf.string(slot(o.otype), f, "relation");
        if (a.hidden) lf.boolean(slot(o.hidden), f, a.hidden);
        if (a.no_win) lf.boolean(slot(o.no_win), f, a.no_win);
        if (a.in_church) lf.boolean(slot(o.in_church), f, true);
        lf.string_list(slot(o.guessed), f, std::span<const std::string_view>(a.guessed));
        if (!a.places.empty()) {
            lf.ids(slot(o.places), f, a.places);
            lf.string_list(slot(o.place_hours), f, std::span<const std::string_view>(a.hours));
        }
        lf.integer(slot(o.flags), f, static_cast<std::int64_t>(a.flags));
        lf.string(slot(o.shutter), f, a.shutter);
        if (!a.party.empty()) lf.ids(slot(o.party), f, a.party);
        lf.string(slot(o.tag_building), f, a.tags[0]);
        lf.string(slot(o.tag_building_part), f, a.tags[1]);
        lf.string(slot(o.tag_roof_direction), f, a.tags[2]);
    }

    void building(tl::LayerFill& lf, std::uint32_t f, const Pending& p) {
        namespace c = tl::building;
        const bool part = p.source == Source::part;
        const Building& b = part ? s_.parts[p.index] : s_.buildings[p.index];
        const GridLine& ring = part ? parts_[p.index] : buildings_[p.index];
        lf.integer(c::index, f, p.index);
        lf.integer(c::id, f, b.id);
        if (plan_.keep) {
            lf.ring(c::ring, f, ring.lat, ring.lon, std::span<const std::uint8_t>(ring.keep));
        } else {
            lf.ring(c::ring, f, ring.lat, ring.lon);
        }
        write_attributes(lf, f, c::h, attributes_of(s_, b, part, grid_));
    }

    void variant(tl::LayerFill& lf, std::uint32_t f, const Pending& p) {
        namespace c = tl::variant;
        const VariantFeature& v = variants_[p.index];
        lf.integer(c::layer, f, static_cast<std::int64_t>(v.part ? tl::LayerId::part : tl::LayerId::building));
        lf.integer(c::index, f, v.index);
        lf.integer(c::tolerance, f, v.level);
        write_attributes(lf, f, static_cast<std::uint8_t>(c::tolerance + 1), v.attributes);
    }

    void quay(tl::LayerFill& lf, std::uint32_t f, const Pending& p) {
        namespace c = tl::quay;
        const Edge& e = edges_[p.index];
        const auto& [a, b] = quay_ends_[p.index];
        lf.integer(c::index, f, p.index);
        if (e.hole) lf.boolean(tl::slot_of(c::columns, "hole"), f, true);
        lf.integer(c::ring, f, e.ring);
        const std::array<std::int32_t, 2> lat{a.lat, b.lat};
        const std::array<std::int32_t, 2> lon{a.lon, b.lon};
        lf.ring(c::edge, f, lat, lon);
        if (e.flip) lf.boolean(c::flip, f, true);
    }

    void tree(tl::LayerFill& lf, std::uint32_t f, const Pending& p) {
        namespace c = tl::tree;
        const Tree& t = s_.trees[p.index];
        const Node& n = s_.nodes[t.node];
        lf.integer(c::index, f, p.index);
        lf.integer(c::node, f, t.node);
        lf.integer(c::id, f, n.id);
        lf.point(c::pos, f, point({n.x, n.z}, [] { return std::string("a tree"); }));
        lf.decimal(c::h, f, t.h);
        lf.decimal(c::crown, f, t.crown);
        std::vector<std::string_view> guessed;
        if (t.guessed.height) guessed.emplace_back("height");
        if (t.guessed.diameter_crown) guessed.emplace_back("diameter_crown");
        if (t.guessed.leaf_type) guessed.emplace_back("leaf_type");
        lf.string_list(tl::slot_of(c::columns, "guessed"), f, std::span<const std::string_view>(guessed));
        tags(lf, f, n.tags);
    }

    void thing(tl::LayerFill& lf, std::uint32_t f, const Pending& p) {
        namespace c = tl::thing;
        const Node& n = s_.nodes[p.index];
        lf.integer(c::index, f, p.index);
        lf.integer(c::id, f, n.id);
        lf.point(c::pos, f, point({n.x, n.z}, [] { return std::string("a thing"); }));
        if (poi_[p.index]) lf.boolean(c::poi, f, true);
        if (n.style) {
            const PlaceStyle& st = s_.styles[*n.style];
            // the glyph is the app's icon: the renders never read it
            lf.string(tl::slot_of(c::columns, "style.cat"), f, st.cat);
            lf.string(tl::slot_of(c::columns, "style.col"), f, st.col);
            lf.string(tl::slot_of(c::columns, "style.kind"), f, st.kind);
            lf.string(tl::slot_of(c::columns, "style.brand"), f, st.brand);
            lf.string(tl::slot_of(c::columns, "style.bg"), f, st.bg);
            lf.string(tl::slot_of(c::columns, "style.fg"), f, st.fg);
        }
        if (const auto number = n.tags.get("addr:housenumber"); number && !number->empty()) {
            lf.boolean(c::door, f, true);
            lf.nullable_integer(c::door_number, f, leading_number(*number));
        }
        if (const auto door = doors_[p.index]) lf.nullable_integer(c::building, f, *door);
        if (const auto wall = walls_[p.index]) lf.nullable_integer(c::wall, f, *wall);
        tags(lf, f, n.tags);
    }

    void item(tl::LayerFill& lf, std::uint32_t f, const Pending& p) {
        namespace c = tl::item;
        const ItemFeature& it = items_[p.index];
        lf.integer(c::source, f, it.source);
        lf.integer(c::index, f, it.index);
        lf.nullable_integer(c::id, f, it.id);
        lf.point(c::pos, f, it.pos);
        if (!it.exact) {
            lf.decimal(c::x, f, it.x);
            lf.decimal(c::z, f, it.z);
        }
        if (it.poly) lf.ring(c::poly, f, it.poly->lat, it.poly->lon);
        lf.decimal(c::h, f, it.h);
        lf.decimal(c::rotor, f, it.rotor);
        lf.decimal(c::crown, f, it.crown);
        std::vector<std::pair<std::string_view, std::string_view>> all;
        for (const osm::Tag t : it.tags->all()) all.emplace_back(t.key, t.value);
        lf.tags(c::tags, f, all);
        if (it.crane != 0) {
            lf.integer(c::crane, f, it.crane);
            lf.decimal(c::ux, f, it.ux);
            lf.decimal(c::uz, f, it.uz);
            lf.decimal(c::vx, f, it.vx);
            lf.decimal(c::vz, f, it.vz);
            lf.decimal(c::hu, f, it.hu);
            lf.decimal(c::hv, f, it.hv);
        }
    }

    void road(tl::LayerFill& lf, std::uint32_t f, const Pending& p) {
        namespace c = tl::road;
        const Road& r = s_.roads[p.index];
        const GridLine& g = lines_[p.index];
        lf.integer(c::index, f, p.index);
        lf.integer(c::id, f, r.id);
        lf.ring(c::line, f, g.lat, g.lon);
        lf.decimal(c::w, f, r.w);
        lf.string(c::hw, f, r.hw);
        lf.boolean(c::bridge, f, r.bridge);
        lf.decimal(c::layer, f, r.layer);
        lf.boolean(tl::slot_of(c::columns, "wguess"), f, r.wguess);
        const auto k = std::ranges::find(road_order, r.hw) - road_order.begin();
        lf.integer(c::ground_key, f, ground_key(gk::road, static_cast<std::uint32_t>(k), p.index));
        lf.integer(c::marks_key, f, marks_[p.index]);
        tags(lf, f, r.tags);
    }

    /// A feature's element, where the plan carries it (CutPlan::identity): its id, its type where a relation, its
    /// ring's place among the element's.
    void origin(tl::LayerFill& lf, std::uint32_t f, std::uint8_t id, std::uint8_t otype, std::uint8_t part,
                const Origin& o) const {
        if (!plan_.identity) return;
        lf.nullable_integer(id, f, o.id);
        if (o.kind == osm::Kind::relation) lf.string(otype, f, std::string_view("relation"));
        if (o.part != 0) lf.integer(part, f, o.part);
    }

    void rail(tl::LayerFill& lf, std::uint32_t f, const Pending& p) {
        namespace c = tl::line;
        const GridLine& g = rails_[p.index];
        lf.integer(c::source, f, 0);
        lf.integer(c::index, f, p.index);
        lf.ring(c::line, f, g.lat, g.lon);
        lf.integer(c::ground_key, f, ground_key(gk::marks, gk::marks_tram, p.index));
        if (p.index < s_.rails_from.size()) origin(lf, f, c::id, c::otype, c::part, s_.rails_from[p.index]);
    }

    void line(tl::LayerFill& lf, std::uint32_t f, const Pending& p) {
        namespace c = tl::line;
        const RegionLine& rl = region_lines_[p.index];
        lf.integer(c::source, f, rl.source);
        lf.integer(c::index, f, rl.index);
        lf.ring(c::line, f, rl.g.lat, rl.g.lon);
        const Tags* t = nullptr;
        switch (rl.source) {
        case 1: {
            const Railway& r = s_.rail[rl.index];
            lf.boolean(tl::slot_of(c::columns, "crane"), f, r.crane);
            t = &r.tags;
            break;
        }
        case 2: {
            const BigRoad& r = s_.big[rl.index];
            lf.nullable_integer(c::id, f, r.id);
            lf.string(c::hw, f, r.hw);
            lf.decimal(c::layer, f, r.layer);
            t = &r.tags;
            break;
        }
        case 3: {
            const Aero& a = s_.aero[rl.index];
            lf.nullable_integer(c::id, f, a.id);
            lf.string(c::kind, f, a.kind);
            t = &a.tags;
            break;
        }
        case 4: t = &s_.power[rl.index].tags; break;
        case 5: {
            const Marine& m = s_.marine[rl.index];
            lf.string(c::kind, f, m.kind);
            lf.boolean(tl::slot_of(c::columns, "closed"), f, m.closed);
            t = &m.tags;
            break;
        }
        case 6: t = &s_.park[rl.index].tags; break;
        case 7:
        case 8: {
            const Gantry& g = (rl.source == 7 ? s_.gantries : s_.conveyors)[rl.index];
            lf.nullable_integer(c::id, f, g.id);
            lf.decimal(c::h, f, g.h);
            t = &g.tags;
            break;
        }
        case 9: {
            // its kind, and its heights as their doubles' bits
            const CoasterTrack& tr = s_.coasters[rl.index];
            lf.string(c::kind, f, tr.kind);
            std::vector<std::int64_t> bits(tr.ys.size());
            std::ranges::transform(tr.ys, bits.begin(), [](double y) { return std::bit_cast<std::int64_t>(y); });
            lf.ids(c::ys, f, bits);
            break;
        }
        default: break;
        }
        const Origin* from = nullptr;
        switch (rl.source) {
        case 1: from = &s_.rail[rl.index].from; break;
        case 2: from = &s_.big[rl.index].from; break;
        case 3: from = &s_.aero[rl.index].from; break;
        case 4: from = &s_.power[rl.index].from; break;
        case 5: from = &s_.marine[rl.index].from; break;
        case 6: from = &s_.park[rl.index].from; break;
        default: break;
        }
        if (from != nullptr) origin(lf, f, c::id, c::otype, c::part, *from);
        if (rl.ground_key != 0) lf.integer(c::ground_key, f, rl.ground_key);
        if (t != nullptr) tags(lf, f, *t);
    }

    void ground(tl::LayerFill& lf, std::uint32_t f, const Pending& p) {
        namespace c = tl::ground;
        const GridLine& g = rings_[p.geom];
        const bool land = p.source == Source::land;
        lf.integer(c::source, f, land ? 1 : 0);
        lf.integer(c::index, f, p.index);
        if (plan_.keep) {
            lf.ring(c::ring, f, g.lat, g.lon, std::span<const std::uint8_t>(g.keep));
        } else {
            lf.ring(c::ring, f, g.lat, g.lon);
        }
        if (land) {
            const Land& a = s_.land[p.index];
            lf.string(c::kind, f, a.kind);
            lf.nullable_integer(c::id, f, a.id);
            origin(lf, f, c::id, c::otype, c::part, a.from);
            lf.integer(c::ground_key, f, ground_key(gk::land, 0, p.index));
            tags(lf, f, a.tags);
            return;
        }
        const Area& a = s_.areas[p.index];
        origin(lf, f, c::id, c::otype, c::part, a.from);
        lf.string(c::kind, f, a.kind);
        lf.integer(c::ground_key, f, ground_key(gk::area, 0, p.index));
        tags(lf, f, a.tags);
    }

    void canal(tl::LayerFill& lf, std::uint32_t f, const Pending& p) {
        namespace c = tl::label;
        const Label& lb = s_.labels[p.index];
        const auto& [mid, prev] = label_points_[p.index];
        lf.integer(c::source, f, 0);
        lf.integer(c::index, f, p.index);
        lf.string(c::name, f, lb.name);
        lf.point(c::pos, f, mid);
        lf.point(c::prev, f, prev);
        lf.integer(c::count, f, static_cast<std::int64_t>(lb.pts.size()));
        lf.integer(c::ground_key, f, ground_key(gk::marks, gk::marks_canal, p.index));
    }

    void place(tl::LayerFill& lf, std::uint32_t f, const Pending& p) {
        namespace c = tl::label;
        const Node& n = s_.nodes[p.index];
        const tl::GridPoint pos = point({n.x, n.z}, [] { return std::string("a place"); });
        lf.integer(c::source, f, 1);
        lf.integer(c::index, f, p.index);
        lf.nullable_integer(c::id, f, n.id);
        lf.string(c::name, f, n.tags.get("name"));
        lf.point(c::pos, f, pos);
        lf.point(c::prev, f, pos);
        lf.integer(c::count, f, 1);
        lf.string(c::kind, f, n.tags.get("place"));
        tags(lf, f, n.tags);
    }

    void water(tl::LayerFill& lf, std::uint32_t f, const Pending& p) {
        namespace c = tl::water;
        const GridLine& g = rings_[p.geom];
        const bool hole = (p.index & hole_bit) != 0;
        const std::uint32_t i = p.index & ~hole_bit;
        lf.integer(c::index, f, i);
        if (hole) lf.boolean(c::hole, f, true);
        if (plan_.keep) {
            lf.ring(c::ring, f, g.lat, g.lon, std::span<const std::uint8_t>(g.keep));
        } else {
            lf.ring(c::ring, f, g.lat, g.lon);
        }
        if (!hole && i < s_.natural_shore.size() && s_.natural_shore[i]) lf.boolean(c::shore, f, true);
        lf.integer(c::ground_key, f, ground_key(gk::water, hole ? 1 : 0, i));
    }

    /// RenderTile.of and the writer's fill: the layers by id, each one's features by cell and order, and the cell
    /// table from their boxes and tops.
    [[nodiscard]] tl::TileFill write(const tl::TileKey& key, std::vector<Pending>& features) {
        std::ranges::stable_sort(features, [](const Pending& a, const Pending& b) {
            if (a.layer != b.layer) return a.layer < b.layer;
            if (a.cell != b.cell) return a.cell < b.cell;
            return a.order < b.order;
        });
        tl::TileFill tile(key.z, key.x, key.y);
        std::vector<bool> filled(tile.cells().size(), false);
        for (const Pending& p : features) {
            tl::CellFill& cell = tile.cells()[p.cell];
            if (!filled[p.cell]) {
                cell = {.south = p.box[0], .west = p.box[1], .north = p.box[2], .east = p.box[3], .top_cm = 0};
                filled[p.cell] = true;
            } else {
                cell.south = std::min(cell.south, p.box[0]);
                cell.west = std::min(cell.west, p.box[1]);
                cell.north = std::max(cell.north, p.box[2]);
                cell.east = std::max(cell.east, p.box[3]);
            }
            cell.top_cm = std::max(cell.top_cm, p.top_cm);
            tl::LayerFill& lf = tile.layer(p.layer);
            const std::uint32_t f = lf.add(p.cell);
            switch (p.source) {
            case Source::building:
            case Source::part: building(lf, f, p); break;
            case Source::variant: variant(lf, f, p); break;
            case Source::quay: quay(lf, f, p); break;
            case Source::tree: tree(lf, f, p); break;
            case Source::thing: thing(lf, f, p); break;
            case Source::item: item(lf, f, p); break;
            case Source::road: road(lf, f, p); break;
            case Source::rail: rail(lf, f, p); break;
            case Source::line: line(lf, f, p); break;
            case Source::area:
            case Source::land: ground(lf, f, p); break;
            case Source::canal: canal(lf, f, p); break;
            case Source::place: place(lf, f, p); break;
            case Source::water: water(lf, f, p); break;
            }
        }
        return tile;
    }

    static constexpr std::uint32_t hole_bit = 1U << 31U;

    const Scene& s_;
    tl::Frame f_;
    CutPlan plan_;
    std::vector<Edge> edges_;
    EdgeGrid grid_;
    std::uint8_t z_ = 14;
    std::vector<GridLine> buildings_;
    std::vector<GridLine> parts_;
    std::vector<tl::GridPoint> building_refs_;
    std::vector<tl::GridPoint> part_refs_;
    std::vector<VariantFeature> variants_;
    std::vector<GridLine> water_;
    std::vector<GridLine> holes_;
    std::vector<GridLine> lines_;
    std::vector<GridLine> rails_;
    std::vector<RegionLine> region_lines_;
    std::vector<ItemFeature> items_;
    std::vector<GridLine> rings_; ///< areas, land covers and the water's pieces, at their level
    std::vector<std::pair<tl::GridPoint, tl::GridPoint>> quay_ends_;
    std::vector<std::pair<tl::GridPoint, tl::GridPoint>> label_points_;
    std::vector<bool> tree_nodes_;
    std::vector<bool> poi_;
    std::vector<std::optional<std::uint32_t>> doors_;
    std::vector<std::optional<std::uint32_t>> walls_; ///< per node: its street wall's first corner (doors_' building)
    std::vector<std::pair<std::uint32_t, Box>> facades_;
    std::unordered_map<std::uint64_t, std::vector<std::array<V2, 2>>> road_grid_; ///< by road_cell
    /// A road segment kept out of road_grid_, with the cells it would fill there: [gx0, gx1) by [gz0, gz1).
    struct LongRoad {
        V2 a;
        V2 b;
        std::int64_t gx0 = 0;
        std::int64_t gz0 = 0;
        std::int64_t gx1 = 0;
        std::int64_t gz1 = 0;
    };
    std::vector<LongRoad> long_roads_;
    /// The most cells one segment fills in road_grid_ (a box of 1.28 km), and the most all of them do.
    static constexpr double max_segment_cells = 4096;
    static constexpr double max_grid_cells = 4'000'000;
    bool road_grid_made_ = false;
    std::vector<std::int64_t> marks_;
    std::unordered_map<std::uint64_t, std::vector<Pending>> tiles_; ///< by TileKey::packed
    std::vector<tl::TileKey> order_;
    std::optional<CutError> error_;
};

constexpr std::array<std::uint8_t, 1> living_levels{14};
constexpr std::array<std::uint8_t, 2> region_levels{14, 12};

} // namespace

std::expected<std::vector<tiles::TileFill>, CutError> cut_living(const Scene& scene, const tiles::Frame& frame) {
    Cutter cutter(scene, frame, {.levels = living_levels, .keep = false, .facades = true});
    return cutter.run();
}

namespace {

/// What a variant is compared with: the ids of the base scene's buildings and parts (each tolerance's scene lists the
/// same, in the same order), the attributes of those the cut keeps (a window's, in its tile), and the water.
struct BaseAttributes {
    std::array<std::vector<std::int64_t>, 2> ids;                     ///< buildings, parts
    std::array<std::vector<std::optional<Attributes>>, 2> attributes; ///< buildings, parts: where kept
    std::vector<std::vector<V2>> water;
};

/// Whether the cut by `plan` keeps building `b`: every one, or a window's whose reference point lies in its tile.
[[nodiscard]] bool kept_by(const CutPlan& plan, const tiles::Frame& frame, const Building& b) {
    if (!plan.only) return true;
    const V2 c = geometry::centroid(b.poly);
    const tiles::GridPoint ref = tiles::nearest_grid(frame, c.x, c.z);
    return tiles::tile_of(plan.only->z, ref.lat, ref.lon).packed() == plan.only->packed();
}

/// The base scene's attributes, as each variant compares them.
[[nodiscard]] BaseAttributes base_attributes(const CutPlan& plan, const Scene& scene, const tiles::Frame& frame) {
    BaseAttributes base;
    const std::vector<Edge> edges = water_edges(scene);
    const EdgeGrid grid(edges);
    for (const bool part : {false, true}) {
        const std::vector<Building>& mine = part ? scene.parts : scene.buildings;
        std::vector<std::int64_t>& ids = base.ids.at(part ? 1 : 0);
        std::vector<std::optional<Attributes>>& attributes = base.attributes.at(part ? 1 : 0);
        ids.reserve(mine.size());
        attributes.reserve(mine.size());
        for (const Building& b : mine) {
            ids.push_back(b.id);
            attributes.push_back(kept_by(plan, frame, b) ? std::optional(attributes_of(scene, b, part, grid))
                                                         : std::nullopt);
        }
    }
    base.water = scene.water;
    return base;
}

/// The variants of `other`, the region loaded at `tolerance`: a building's or part's attributes there where they differ
/// from the base's, appended to `out` (buildings, then parts, each in order).
[[nodiscard]] std::expected<void, CutError> variants_of(const BaseAttributes& base, const Scene& other,
                                                        double tolerance, std::vector<VariantFeature>& out) {
    const int level = tiles::keep::level_of(tolerance);
    if (level <= 0) {
        return std::unexpected(
            CutError{.what = std::format("a variant at {} m, which is not on the keep ladder", tolerance)});
    }
    if (other.water != base.water) {
        return std::unexpected(
            CutError{.what = std::format("at {} m the water differs from tolerance 0's", tolerance)});
    }
    const std::vector<Edge> edges = water_edges(other);
    const EdgeGrid grid(edges);
    for (const bool part : {false, true}) {
        const std::vector<std::int64_t>& ids = base.ids.at(part ? 1 : 0);
        const std::vector<std::optional<Attributes>>& attributes = base.attributes.at(part ? 1 : 0);
        const std::vector<Building>& theirs = part ? other.parts : other.buildings;
        if (ids.size() != theirs.size()) {
            return std::unexpected(CutError{.what = std::format("at {} m: {} {}, not {}", tolerance, theirs.size(),
                                                                part ? "parts" : "buildings", ids.size())});
        }
        for (std::uint32_t i = 0; i < ids.size(); ++i) {
            if (ids[i] != theirs[i].id) {
                return std::unexpected(
                    CutError{.what = std::format("at {} m: {} {} is {}, not {}", tolerance, part ? "part" : "building",
                                                 i, theirs[i].id, ids[i])});
            }
            const std::optional<Attributes>& mine = attributes[i];
            if (!mine) continue;
            Attributes b = attributes_of(other, theirs[i], part, grid);
            if (!same(*mine, b)) {
                out.push_back(
                    {.part = part, .index = i, .level = static_cast<std::uint8_t>(level), .attributes = std::move(b)});
            }
        }
    }
    return {};
}

/// A region's cut by `plan`, with its variants: a building's or part's attributes at each variant's tolerance, carried
/// where they differ from tolerance 0's (for a window, only of the features in its tile).
std::expected<std::vector<tiles::TileFill>, CutError>
cut_with(const CutPlan& plan, const Scene& scene, const tiles::Frame& frame, std::span<const CutVariant> variants) {
    std::vector<VariantFeature> found;
    if (!variants.empty()) {
        const BaseAttributes base = base_attributes(plan, scene, frame);
        for (const CutVariant& v : variants) {
            if (v.scene == nullptr) {
                return std::unexpected(
                    CutError{.what = std::format("a variant at {} m without its scene", v.tolerance)});
            }
            if (auto done = variants_of(base, *v.scene, v.tolerance, found); !done) {
                return std::unexpected(done.error());
            }
        }
    }
    Cutter cutter(scene, frame, plan);
    for (VariantFeature& v : found) cutter.add_variant(std::move(v));
    return cutter.run();
}

constexpr std::array<std::uint8_t, 1> window_levels{14};

/// A window's cut: L14 alone, the features in its tile, keep codes, no facades, the ground's elements carried.
[[nodiscard]] CutPlan window_plan(const tiles::TileKey& tile) {
    return {.levels = window_levels, .keep = true, .facades = false, .only = tile, .identity = true};
}

} // namespace

std::expected<std::vector<tiles::TileFill>, CutError> cut_region(const Scene& scene, const tiles::Frame& frame,
                                                                 std::span<const CutVariant> variants) {
    return cut_with({.levels = region_levels, .keep = true, .facades = false}, scene, frame, variants);
}

std::expected<std::vector<tiles::TileFill>, CutError> cut_window(const Scene& scene, const tiles::Frame& frame,
                                                                 std::span<const CutVariant> variants,
                                                                 const tiles::TileKey& tile) {
    if (tile.z != window_levels[0]) {
        return std::unexpected(
            CutError{.what = std::format("a window is a tile of L{}, not of L{}", window_levels[0], tile.z)});
    }
    return cut_with(window_plan(tile), scene, frame, variants);
}

struct WindowCut::State {
    tiles::Frame frame;
    tiles::TileKey tile;
    std::optional<BaseAttributes> base;
    std::vector<VariantFeature> variants;
};

WindowCut::WindowCut(const tiles::Frame& frame, const tiles::TileKey& tile)
    : state_(std::make_unique<State>(State{.frame = frame, .tile = tile, .base = std::nullopt, .variants = {}})) {}

WindowCut::WindowCut(WindowCut&&) noexcept = default;
WindowCut& WindowCut::operator=(WindowCut&&) noexcept = default;
WindowCut::~WindowCut() = default;

std::expected<void, CutError> WindowCut::base(const Scene& scene) {
    if (state_->tile.z != window_levels[0]) {
        return std::unexpected(
            CutError{.what = std::format("a window is a tile of L{}, not of L{}", window_levels[0], state_->tile.z)});
    }
    state_->base = base_attributes(window_plan(state_->tile), scene, state_->frame);
    state_->variants.clear();
    return {};
}

std::expected<void, CutError> WindowCut::compare(const Scene& scene, double tolerance) {
    if (!state_->base) return std::unexpected(CutError{.what = "a variant compared before the base"});
    return variants_of(*state_->base, scene, tolerance, state_->variants);
}

std::expected<std::vector<tiles::TileFill>, CutError> WindowCut::cut(const Scene& scene) {
    if (!state_->base) return std::unexpected(CutError{.what = "a window cut before its base"});
    // the scene at tolerance 0 again: the one the base was taken from
    const BaseAttributes& base = *state_->base;
    if (scene.water != base.water || scene.buildings.size() != base.ids[0].size() ||
        scene.parts.size() != base.ids[1].size()) {
        return std::unexpected(CutError{.what = "the window's scene is not the one its base was taken from"});
    }
    for (std::size_t i = 0; i < scene.buildings.size(); ++i) {
        if (scene.buildings[i].id != base.ids[0][i]) {
            return std::unexpected(CutError{.what = "the window's scene is not the one its base was taken from"});
        }
    }
    Cutter cutter(scene, state_->frame, window_plan(state_->tile));
    for (VariantFeature& v : state_->variants) cutter.add_variant(std::move(v));
    state_->variants.clear();
    return cutter.run();
}

} // namespace atlas::load
