#include "load/Cut.hpp"

#include "base/pymath/PyMath.hpp"
#include "geometry/RoofShapes.hpp"
#include "load/Real.hpp"
#include "text/Shaper.hpp"
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
constexpr double tree_symbol = 3.0;
constexpr double crown_reach = 0.8;

/// The flags' near_water: a quay edge within 16 m.
constexpr double near_water_reach = 16.0;

struct Material {
    std::string_view name;
    std::string_view col;
};
// amsterdam.py's MATERIAL and CLAY (the style file's `material` and `clay`, which EngineStyleTest holds to them)
constexpr std::array<Material, 11> materials = {{
    {.name = "brick", .col = "#9C5A45"},
    {.name = "stone", .col = "#CFC6B4"},
    {.name = "copper", .col = "#5E9E86"},
    {.name = "glass", .col = "#A9C2D6"},
    {.name = "concrete", .col = "#BDBAB2"},
    {.name = "wood", .col = "#9C7A55"},
    {.name = "roof_tiles", .col = "#A5553C"},
    {.name = "slate", .col = "#56606A"},
    {.name = "metal", .col = "#9AA3AB"},
    {.name = "plaster", .col = "#E6E0D4"},
    {.name = "sandstone", .col = "#D8C49A"},
}};
constexpr std::string_view clay = "#E4E0D8";
constexpr std::uint32_t clay_rgb = 0xE4E0D8; // hx(CLAY)

// amsterdam.py's CHURCHES, WAREHOUSES, SHOP_FLOORS, FURNITURE, BOLLARDS and ROAD_ORDER (SceneToTiles' and GroundKeys'
// copies, which RenderTablesTest holds to the port's)
constexpr std::array<std::string_view, 3> churches = {"church", "cathedral", "chapel"};
constexpr std::array<std::string_view, 3> warehouses = {"industrial", "warehouse", "service"};
constexpr std::array<std::string_view, 2> shop_floors = {"retail", "commercial"};
constexpr std::array<std::string_view, 3> furniture = {"bench", "bicycle_parking", "waste_basket"};
constexpr std::array<std::string_view, 2> bollards = {"bollard", "block"};
constexpr std::array<std::string_view, 12> road_order = {"path",        "footway",    "steps",         "cycleway",
                                                         "service",     "pedestrian", "living_street", "unclassified",
                                                         "residential", "busway",     "tertiary",      "secondary"};

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

/// SceneToTiles' widen: `box` widened by `metres` on every side, outward on the grid.
[[nodiscard]] Box4 widen(const Box4& b, double metres, const tl::Frame& f) noexcept {
    if (!(metres > 0)) return b;
    const std::int64_t d_lat = kt_long(std::ceil(metres / f.kz * 1e7)) + 1;
    const std::int64_t d_lon = kt_long(std::ceil(metres / f.kx * 1e7)) + 1;
    const auto c = [](std::int64_t v) {
        return static_cast<std::int32_t>(std::clamp<std::int64_t>(v, std::numeric_limits<std::int32_t>::min(),
                                                                  std::numeric_limits<std::int32_t>::max()));
    };
    return {c(b[0] - d_lat), c(b[1] - d_lon), c(b[2] + d_lat), c(b[3] + d_lon)};
}

[[nodiscard]] Box4 point_box(tl::GridPoint p) noexcept { return {p.lat, p.lon, p.lat, p.lon}; }

/// A ring or line on the grid, and its box.
struct GridLine {
    std::vector<std::int32_t> lat;
    std::vector<std::int32_t> lon;

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

/// surface_colour(b, None, 'wall'): the mapped colour, else the material's, else the clay (the living map's loader
/// draws no colour of its own).
[[nodiscard]] std::uint32_t wall_rgb(const Building& b) {
    const std::string_view col = b.col.value_or("");
    if (col.starts_with('#')) return colour(col);
    const std::string_view mat = b.mat.value_or("");
    if (!mat.empty()) return colour(material_or_clay(mat));
    return clay_rgb;
}

/// surface_colour(b, None, 'roof'): the roof's colour or material, else the wall's.
[[nodiscard]] std::uint32_t roof_rgb(const Building& b) {
    const std::string_view rcol = b.rcol.value_or("");
    if (!rcol.empty()) return colour(rcol.starts_with('#') ? rcol : material_or_clay(rcol));
    const std::string_view rmat = b.rmat.value_or("");
    if (!rmat.empty()) return colour(material_or_clay(rmat));
    return wall_rgb(b);
}

// ---------------------------------------------------------------- the quays

/// One of water_edges' edges: its ring, its ends (the ring's vertices) and whether its normal into the water is the
/// opposite of (ez, -ex) / L.
struct Edge {
    std::uint32_t ring = 0;
    V2 a;
    V2 b;
    bool flip = false;
};

/// water_edges(S) of a scene without islands or natural shores: every edge of a water ring at least 0.4 m long, its
/// normal turned into the water.
[[nodiscard]] std::vector<Edge> water_edges(const Scene& s) {
    std::vector<Edge> out;
    for (std::uint32_t k = 0; k < s.water.size(); ++k) {
        const std::vector<V2>& r = s.water[k];
        const std::size_t n = r.size();
        if (n == 0) continue;
        const std::size_t last = r[0].x == r[n - 1].x && r[0].z == r[n - 1].z ? n - 1 : n;
        for (std::size_t i = 0; i < last; ++i) {
            const V2 a = r[i];
            const V2 b = r[(i + 1) % n];
            const double ex = b.x - a.x;
            const double ez = b.z - a.z;
            const double lg = pymath::hypot(ex, ez);
            if (lg < 0.4) continue;
            const double nx = ez / lg;
            const double nz = -ex / lg;
            const double mx = (a.x + b.x) / 2;
            const double mz = (a.z + b.z) / 2;
            out.push_back({.ring = k, .a = a, .b = b, .flip = !geometry::pip(mx + nx * 0.4, mz + nz * 0.4, r)});
        }
    }
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
        return {.lat = along(a.lat, b.lat, c - a.lon, b.lon - a.lon), .lon = c};
    return {.lat = c, .lon = along(a.lon, b.lon, c - a.lat, b.lat - a.lat)};
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

/// Clip.ring: the ring clipped to the box (inclusive), or nothing when nothing of it with an area is left.
[[nodiscard]] std::optional<GridLine> clip_ring(const GridLine& ring, const Box4& box) {
    std::vector<ClipPoint> pts = points_of(ring);
    pts = clip_side(pts, Side::west, box[1]);
    pts = clip_side(pts, Side::east, box[3]);
    pts = clip_side(pts, Side::south, box[0]);
    pts = clip_side(pts, Side::north, box[2]);
    std::vector<ClipPoint> out;
    out.reserve(pts.size());
    for (const ClipPoint& p : pts) {
        if (out.empty() || out.back().lat != p.lat || out.back().lon != p.lon) out.push_back(p);
    }
    while (out.size() > 1 && out.front().lat == out.back().lat && out.front().lon == out.back().lon) out.pop_back();
    if (out.size() < 3 || area2(out) == 0) return std::nullopt;
    GridLine g;
    for (const ClipPoint& p : out) {
        g.lat.push_back(static_cast<std::int32_t>(p.lat));
        g.lon.push_back(static_cast<std::int32_t>(p.lon));
    }
    return g;
}

[[nodiscard]] bool ring_inside(const GridLine& ring, const Box4& box) noexcept {
    for (std::size_t i = 0; i < ring.lat.size(); ++i) {
        if (ring.lat[i] < box[0] || ring.lat[i] > box[2] || ring.lon[i] < box[1] || ring.lon[i] > box[3]) return false;
    }
    return true;
}

// ---------------------------------------------------------------- the cut

/// Which list of the scene a feature comes from.
enum class Source : std::uint8_t { building, part, quay, tree, thing, road, rail, area, canal, place, water };

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
    std::uint32_t piece = 0; ///< the water's: its piece
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

class Cutter {
public:
    Cutter(const Scene& s, const tl::Frame& f) : s_(s), f_(f), edges_(water_edges(s)), grid_(edges_) {}

    [[nodiscard]] std::expected<std::vector<tl::TileFill>, CutError> run(std::uint8_t level) {
        z_ = level;
        rings_of(s_.buildings, buildings_, "building");
        rings_of(s_.parts, parts_, "part");
        for (std::uint32_t i = 0; i < s_.water.size(); ++i) {
            water_.push_back(grid_line(s_.water[i], [&] { return std::format("water {}", i); }));
        }
        place_buildings(Source::building, s_.buildings, buildings_, tl::LayerId::building);
        place_buildings(Source::part, s_.parts, parts_, tl::LayerId::part);
        place_quays();
        place_trees();
        place_things();
        place_roads();
        place_rails();
        place_areas();
        place_labels();
        place_water();
        if (error_) return std::unexpected(*error_);
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

    void fail(std::string what) {
        if (!error_) error_ = CutError{std::move(what)};
    }

    void rings_of(const std::vector<Building>& list, std::vector<GridLine>& out, std::string_view label) {
        out.reserve(list.size());
        for (std::uint32_t i = 0; i < list.size(); ++i) {
            out.push_back(grid_line(list[i].poly, [&] { return std::format("{} {} ({})", label, i, list[i].id); }));
        }
    }

    // ------------------------------------------------ placing

    /// Files a feature in the tile and cell of its reference point.
    void put(Pending p, tl::GridPoint ref) {
        const tl::TileKey key = tl::tile_of(z_, ref.lat, ref.lon);
        p.cell = tl::cell_of(key, ref.lat, ref.lon);
        add(key, p);
    }

    void add(const tl::TileKey& key, const Pending& p) {
        auto [it, fresh] = tiles_.try_emplace(key.packed());
        if (fresh) order_.push_back(key);
        it->second.push_back(p);
    }

    /// A line's feature: in the tile of its middle vertex, its box widened by `pad` metres, its top `top` metres up.
    void put_line(Pending p, const GridLine& g, double pad, double top) {
        const std::size_t m = g.lat.size() / 2;
        p.box = widen(g.box(), pad, f_);
        p.top_cm = cm_up(top);
        put(p, {g.lat[m], g.lon[m]});
    }

    void place_buildings(Source source, const std::vector<Building>& list, const std::vector<GridLine>& rings,
                         tl::LayerId layer) {
        for (std::uint32_t i = 0; i < list.size(); ++i) {
            const Building& b = list[i];
            const V2 c = geometry::centroid(b.poly);
            put({.layer = layer,
                 .source = source,
                 .index = i,
                 .box = rings[i].box(),
                 .top_cm = cm_up(b.h + chimney),
                 .order = i},
                tl::nearest_grid(f_, c.x, c.z));
        }
    }

    void place_quays() {
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
        const bool door = n.tags.truthy("addr:housenumber") || (n.style && n.style->cat != "service");
        if (!door) return std::nullopt;
        for (const auto& [i, bb] : facades_) {
            if (bb.x0 <= n.x && n.x <= bb.x1 && bb.z0 <= n.z && n.z <= bb.z1 &&
                geometry::pip(n.x, n.z, s_.buildings[i].poly)) {
                return i;
            }
        }
        return std::nullopt;
    }

    void place_things() {
        facade_buildings();
        poi_.assign(s_.nodes.size(), false);
        for (const std::uint32_t p : s_.pois) poi_[p] = true;
        doors_.assign(s_.nodes.size(), std::nullopt);
        for (std::uint32_t i = 0; i < s_.nodes.size(); ++i) {
            if (tree_nodes_[i]) continue;
            const Node& n = s_.nodes[i];
            doors_[i] = door_building(n);
            const bool thing = n.tags.is("highway", "street_lamp") || in(n.tags.get("amenity"), furniture) ||
                               in(n.tags.get("barrier"), bollards) || poi_[i] || doors_[i].has_value();
            if (!thing) continue;
            const tl::GridPoint p = point({n.x, n.z}, [&] { return std::format("node {} ({})", i, n.id); });
            put({.layer = tl::LayerId::thing,
                 .source = Source::thing,
                 .index = i,
                 .box = widen(point_box(p), thing_reach, f_),
                 .top_cm = cm_up(thing_reach),
                 .order = i},
                p);
        }
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
        lines_.reserve(s_.roads.size());
        for (std::uint32_t i = 0; i < s_.roads.size(); ++i) {
            const Road& r = s_.roads[i];
            lines_.push_back(grid_line(r.pts, [&] { return std::format("road {} ({})", i, r.id); }));
            put_line({.layer = tl::LayerId::road, .source = Source::road, .index = i, .order = i}, lines_.back(),
                     r.w / 2 + road_side, r.bridge ? bridge_top : road_top);
        }
    }

    void place_rails() {
        for (std::uint32_t i = 0; i < s_.rails.size(); ++i) {
            rails_.push_back(grid_line(s_.rails[i], [&] { return std::format("tram track {}", i); }));
            put_line({.layer = tl::LayerId::line, .source = Source::rail, .index = i, .order = i}, rails_.back(),
                     tram_reach, road_top);
        }
    }

    void place_areas() {
        for (std::uint32_t i = 0; i < s_.areas.size(); ++i) {
            areas_.push_back(grid_line(s_.areas[i].poly, [&] { return std::format("area {}", i); }));
            const Box4 box = areas_.back().box();
            put({.layer = tl::LayerId::ground,
                 .source = Source::area,
                 .index = i,
                 .box = box,
                 .top_cm = cm_up(area_top),
                 .order = i},
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

    void place_water() {
        for (std::uint32_t i = 0; i < water_.size(); ++i) {
            const GridLine& ring = water_[i];
            if (ring.lat.empty()) continue;
            const Box4 box = ring.box();
            const int turn = sign(area2(points_of(ring)));
            const tl::TileKey t0 = tl::tile_of(z_, box[2], box[1]);
            const tl::TileKey t1 = tl::tile_of(z_, box[0], box[3]);
            for (std::uint32_t x = t0.x; x <= t1.x; ++x) {
                for (std::uint32_t y = t0.y; y <= t1.y; ++y) {
                    const tl::TileKey key(z_, x, y);
                    const Box4 tb = tl::tile_box(key);
                    std::optional<GridLine> piece = ring_inside(ring, tb) ? ring : clip_ring(ring, tb);
                    if (!piece) continue;
                    if (sign(area2(points_of(*piece))) != turn) continue; // a ring that crosses itself
                    const Box4 pb = piece->box();
                    const tl::GridPoint c = tl::centre_of(pb);
                    pieces_.push_back(std::move(*piece));
                    add(key, {.layer = tl::LayerId::water,
                              .source = Source::water,
                              .index = i,
                              .cell = tl::cell_of(key, c.lat, c.lon),
                              .box = pb,
                              .order = i,
                              .piece = static_cast<std::uint32_t>(pieces_.size() - 1)});
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

    void building(tl::LayerFill& lf, std::uint32_t f, const Pending& p) {
        namespace c = tl::building;
        const bool part = p.source == Source::part;
        const Building& b = part ? s_.parts[p.index] : s_.buildings[p.index];
        const GridLine& ring = part ? parts_[p.index] : buildings_[p.index];
        lf.integer(c::index, f, p.index);
        lf.integer(c::id, f, b.id);
        lf.ring(c::ring, f, ring.lat, ring.lon);
        lf.decimal(c::h, f, b.h);
        lf.decimal(c::min, f, b.min);
        lf.decimal(c::roof_h, f, b.roof_h);
        lf.nullable_integer(c::year, f, b.year);
        lf.nullable_integer(c::levels, f, b.levels);
        lf.string(c::roof, f, b.roof);
        lf.string(c::orient, f, b.orient);
        lf.string(c::mat_draw, f, b.mat_draw);
        if (!part) lf.boolean(c::hidden, f, b.hidden);
        std::vector<std::string_view> guessed;
        for (const Guess g : b.guessed.keys()) guessed.push_back(key_of(g));
        lf.string_list(tl::slot_of(c::columns, "guessed"), f, std::span<const std::string_view>(guessed));
        if (!b.places.empty()) {
            std::vector<std::int64_t> ids;
            std::vector<std::string_view> hours;
            for (const std::uint32_t k : b.places) {
                ids.push_back(s_.nodes[k].id);
                hours.push_back(s_.nodes[k].tags.get("opening_hours").value_or(""));
            }
            lf.ids(c::places, f, ids);
            lf.string_list(c::place_hours, f, std::span<const std::string_view>(hours));
        }
        lf.integer(c::wall_rgb, f, wall_rgb(b));
        lf.integer(c::roof_rgb, f, roof_rgb(b));
        // the derived flags, with the renders' own tests (amsterdam.py's _draw_building)
        const auto btype = b.tags.get("building");
        std::uint64_t flags = 0;
        if (in(btype, churches) || b.tags.is("building:part", "church")) flags |= c::flag_church;
        if (in(btype, warehouses)) flags |= c::flag_warehouse;
        if (!b.places.empty() || in(btype, shop_floors)) flags |= c::flag_shop_floor;
        if (b.tags.is("building", "construction")) flags |= c::flag_construction;
        if (grid_.near_water(b.poly, near_water_reach)) flags |= c::flag_near_water;
        if (b.tags.is("building", "houseboat")) flags |= c::flag_houseboat;
        if (b.year.value_or(0) >= 1960 && !in(btype, churches)) flags |= c::flag_modern;
        lf.integer(c::flags, f, static_cast<std::int64_t>(flags));
        tags(lf, f, b.tags);
    }

    void quay(tl::LayerFill& lf, std::uint32_t f, const Pending& p) {
        namespace c = tl::quay;
        const Edge& e = edges_[p.index];
        const auto& [a, b] = quay_ends_[p.index];
        lf.integer(c::index, f, p.index);
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
            const PlaceStyle& st = *n.style;
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
        tags(lf, f, n.tags);
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

    void rail(tl::LayerFill& lf, std::uint32_t f, const Pending& p) {
        namespace c = tl::line;
        const GridLine& g = rails_[p.index];
        lf.integer(c::source, f, 0);
        lf.integer(c::index, f, p.index);
        lf.ring(c::line, f, g.lat, g.lon);
        lf.integer(c::ground_key, f, ground_key(gk::marks, gk::marks_tram, p.index));
    }

    void area(tl::LayerFill& lf, std::uint32_t f, const Pending& p) {
        namespace c = tl::ground;
        const Area& a = s_.areas[p.index];
        const GridLine& g = areas_[p.index];
        lf.integer(c::source, f, 0);
        lf.integer(c::index, f, p.index);
        lf.ring(c::ring, f, g.lat, g.lon);
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
        const GridLine& g = pieces_[p.piece];
        lf.integer(c::index, f, p.index);
        lf.ring(c::ring, f, g.lat, g.lon);
        lf.integer(c::ground_key, f, ground_key(gk::water, 0, p.index));
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
            case Source::quay: quay(lf, f, p); break;
            case Source::tree: tree(lf, f, p); break;
            case Source::thing: thing(lf, f, p); break;
            case Source::road: road(lf, f, p); break;
            case Source::rail: rail(lf, f, p); break;
            case Source::area: area(lf, f, p); break;
            case Source::canal: canal(lf, f, p); break;
            case Source::place: place(lf, f, p); break;
            case Source::water: water(lf, f, p); break;
            }
        }
        return tile;
    }

    const Scene& s_;
    tl::Frame f_;
    std::vector<Edge> edges_;
    EdgeGrid grid_;
    std::uint8_t z_ = 14;
    std::vector<GridLine> buildings_;
    std::vector<GridLine> parts_;
    std::vector<GridLine> water_;
    std::vector<GridLine> lines_;
    std::vector<GridLine> rails_;
    std::vector<GridLine> areas_;
    std::vector<GridLine> pieces_;
    std::vector<std::pair<tl::GridPoint, tl::GridPoint>> quay_ends_;
    std::vector<std::pair<tl::GridPoint, tl::GridPoint>> label_points_;
    std::vector<bool> tree_nodes_;
    std::vector<bool> poi_;
    std::vector<std::optional<std::uint32_t>> doors_;
    std::vector<std::pair<std::uint32_t, Box>> facades_;
    std::vector<std::int64_t> marks_;
    std::unordered_map<std::uint64_t, std::vector<Pending>> tiles_; ///< by TileKey::packed
    std::vector<tl::TileKey> order_;
    std::optional<CutError> error_;
};

} // namespace

std::expected<std::vector<tiles::TileFill>, CutError> cut_living(const Scene& scene, const tiles::Frame& frame) {
    Cutter cutter(scene, frame);
    return cutter.run(14);
}

} // namespace atlas::load
