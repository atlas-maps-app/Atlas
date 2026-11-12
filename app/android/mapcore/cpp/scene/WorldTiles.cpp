// The world's tiles beyond the countries' (scene/WorldTiles.hpp).
#include "scene/WorldTiles.hpp"

#include "base/arena.hpp"
#include "base/log.hpp"
#include "base/pymath/PyMath.hpp"
#include "base/trace.hpp"
#include "gpu/Frames.hpp"
#include "load/Real.hpp"
#include "tiles/Grid.hpp"
#include "tiles/layers/Ground.hpp"
#include "tiles/layers/Labels.hpp"
#include "tiles/layers/Lines.hpp"
#include "tiles/layers/Water.hpp"

#include <mapbox/earcut.hpp>

#include <algorithm>
#include <charconv>
#include <chrono>
#include <cmath>
#include <format>
#include <map>
#include <numbers>
#include <numeric>
#include <string_view>
#include <utility>

#if defined(__ANDROID__)
#include <sys/resource.h>
#endif

namespace atlas::scene {

namespace tl = atlas::tiles;

namespace {

/// The world's levels' starts: L11 from 18.8 km, each coarser level from twice its finer's.
constexpr double l11_start = 18'800.0;
/// The far plane at world_from, and the sphere's radius (ENGINE.md step 5, "the bend").
constexpr double far_at_from = 30'000.0;
constexpr double earth_radius = view::earth_radius;
/// How far a chord of L11's may sag from the bent ground, metres: half its pixel at its start (6 m at 18.8 km).
constexpr double bend_tolerance = 3.0;
/// The most times stage T halves an edge (an L0 triangle across an ocean, 20,000 km, halves 6 times).
constexpr int split_depth = 16;
/// The frame's metres a degree spans (view/Ladder.hpp's frame: kx at the equator, kz).
constexpr double metres_per_degree_x = 111'320.0;
constexpr double metres_per_degree_z = 110'540.0;
/// A pole's cap's grid: quads along a parallel and down a meridian.
constexpr std::uint32_t cap_columns = 128;
constexpr std::uint32_t cap_rows = 16;
/// The globe's levels kept resident from the first frame on (ENGINE.md step 5: its L0 to L3).
constexpr std::uint8_t pinned_last = 3;
/// The share of the heap the resident tiles may hold before the least lately drawn go.
constexpr double resident_share = 0.85;
/// The widths of the lines the renders' terrain draws none of: a runway (its usual 45 m) and a river.
constexpr double runway_width = 45.0;
constexpr double river_width = 10.0;
/// The railways' width in the renders' terrain lines (regions.py: 3.0).
constexpr double rail_width = 3.0;

[[nodiscard]] double ms_since(std::chrono::steady_clock::time_point t) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
}

[[nodiscard]] bool is_world(const tl::StorePack& p) noexcept {
    return p.kind == tl::PackKind::world_mid || p.kind == tl::PackKind::world_base;
}

/// The store's world hit for `key`, or none.
[[nodiscard]] std::optional<tl::StoreHit> world_hit(const tl::Store& store, const tl::TileKey& key) {
    for (const tl::StoreHit& h : store.find(key)) {
        if (h.pack != nullptr && is_world(*h.pack)) return h;
    }
    return std::nullopt;
}

/// The grid box of a tile key.
[[nodiscard]] tl::GridBox grid_of(const tl::TileKey& key) noexcept {
    const std::array<std::int32_t, 4> b = tl::tile_box(key);
    return {.south = b[0], .west = b[1], .north = b[2], .east = b[3]};
}

/// A tile's geometry, written as build_world_geometry lays it out.
class GeometryWriter {
public:
    GeometryWriter(const tl::TileHeader& header, const tl::Style* style) noexcept
        : south_(header.south), west_(header.west), north_(header.north), east_(header.east), style_(style) {
        const double limit = world_split_length(header.level);
        limit_squared_ = limit * limit;
    }

    /// A ring of the tile, filled in `palette`, in cell `cell`.
    void fill(const tl::RingView& ring, std::uint8_t palette, std::uint32_t cell) {
        const std::size_t n = ring.size();
        if (n < 3) return;
        polygon_.assign(1, {});
        polygon_[0].reserve(n);
        for (std::size_t i = 0; i < n; ++i) {
            polygon_[0].push_back({static_cast<double>(std::int64_t{ring.lon[i]} - west_),
                                   static_cast<double>(std::int64_t{ring.lat[i]} - south_)});
        }
        indices_ = mapbox::earcut<std::uint32_t>(polygon_);
        const std::uint32_t look = std::uint32_t{palette} | (cell << 8U);
        const auto at = [&ring](std::uint32_t v) { return Point{.lat = ring.lat[v], .lon = ring.lon[v]}; };
        for (std::size_t t = 0; t + 2 < indices_.size(); t += 3) {
            triangle(at(indices_[t]), at(indices_[t + 1]), at(indices_[t + 2]), look, split_depth);
        }
    }

    /// A line of the tile, `width` metres wide, in `palette`, in cell `cell`.
    void line(const tl::RingView& line, double width, std::uint8_t palette, std::uint32_t cell) {
        const std::size_t n = line.size();
        if (n < 2) return;
        const auto dm = static_cast<std::uint32_t>(std::clamp(std::lround(width * 10.0), 1L, 65'535L));
        const std::uint32_t look = std::uint32_t{palette} | (cell << 8U) | (dm << 16U);
        for (std::size_t i = 0; i + 1 < n; ++i) {
            segment({.lat = line.lat[i], .lon = line.lon[i]}, {.lat = line.lat[i + 1], .lon = line.lon[i + 1]}, look,
                    split_depth);
        }
    }

    /// The colour of a land fill of `kind`: its LAND kind's, a lawn's, or the land's where the style has none; the
    /// globe's ice (Natural Earth's glaciers, its Antarctic ice shelves) the ice's where the style has the ladder.
    [[nodiscard]] std::uint8_t land_palette(std::string_view kind) const noexcept {
        if (style_ == nullptr) return world_palette::land;
        if (style_->ladder && (kind == "glacier" || kind == "ice_shelf")) return world_palette::ice;
        const std::optional<std::uint16_t> k = style_->land_kind(kind);
        if (!k || *k + std::uint32_t{world_palette::kinds} >= world_palette::size) return world_palette::land;
        return static_cast<std::uint8_t>(world_palette::kinds + *k);
    }

    /// The coast along a ring of the tile's land in cell `cell`: each of its edges that does not lie along the cell's
    /// box, where the packer cut the land (WorldGeometry.cellBoxes: within the grid's rounding of an edge of it), nor
    /// along the world's own edge beyond 85°, where Mercator's square cuts Antarctica (its land's last latitude is the
    /// projection's, a vertex's rounding from the tile's).
    void coast(const tl::RingView& ring, std::uint32_t cell) {
        const std::size_t n = ring.size();
        if (n < 3) return;
        const std::int64_t per_side = world_cells_per_side;
        const std::int64_t r = cell / per_side;
        const std::int64_t k = cell % per_side;
        const std::int64_t height = std::int64_t{north_} - south_;
        const std::int64_t width = std::int64_t{east_} - west_;
        const auto row = [&](std::int64_t i) {
            return north_ - pymath::floor_div((i * height) + per_side - 1, per_side);
        };
        const auto column = [&](std::int64_t i) {
            return west_ + pymath::floor_div((i * width) + per_side - 1, per_side);
        };
        const std::array<std::int64_t, 2> lats = {row(r + 1), row(r)};
        const std::array<std::int64_t, 2> lons = {column(k), column(k + 1)};
        constexpr std::int64_t rounding = 16;
        const auto along = [](std::int64_t a, std::int64_t b, const std::array<std::int64_t, 2>& edges) {
            return std::ranges::any_of(
                edges, [&](std::int64_t e) { return std::abs(a - e) <= rounding && std::abs(b - e) <= rounding; });
        };
        const std::uint32_t look = std::uint32_t{world_palette::coast} | (cell << 8U);
        constexpr std::int32_t worlds_edge = 850'000'000; // 85°, the grid's 1e-7°
        for (std::size_t i = 0; i < n; ++i) {
            const std::size_t j = i + 1 < n ? i + 1 : 0;
            const Point a{.lat = ring.lat[i], .lon = ring.lon[i]};
            const Point b{.lat = ring.lat[j], .lon = ring.lon[j]};
            if ((a.lat == b.lat && a.lon == b.lon) || along(a.lat, b.lat, lats) || along(a.lon, b.lon, lons)) continue;
            if ((a.lat <= -worlds_edge && b.lat <= -worlds_edge) || (a.lat >= worlds_edge && b.lat >= worlds_edge)) {
                continue;
            }
            // into the coast's own words, which follow the tile's other lines
            std::swap(segments_, coasts_);
            segment(a, b, look, split_depth);
            std::swap(segments_, coasts_);
        }
    }

    /// A city's light at a point of the tile, of `size` (0 the largest), in cell `cell`.
    void light(tl::GridPoint at, std::uint32_t size, std::uint32_t cell) {
        lights_.push_back(quantised(at.lat, at.lon));
        lights_.push_back(size | (cell << 8U));
    }

    [[nodiscard]] WorldGeometry finish() {
        WorldGeometry g;
        g.triangles = static_cast<std::uint32_t>(triangles_.size());
        g.coast = static_cast<std::uint32_t>(coasts_.size() / 3);
        g.segments = static_cast<std::uint32_t>(segments_.size() / 3) + g.coast;
        g.lights = static_cast<std::uint32_t>(lights_.size() / 2);
        g.words.reserve(vertices_.size() + triangles_.size() + segments_.size() + coasts_.size() + lights_.size());
        g.words.insert(g.words.end(), vertices_.begin(), vertices_.end());
        g.words.insert(g.words.end(), triangles_.begin(), triangles_.end());
        g.words.insert(g.words.end(), segments_.begin(), segments_.end());
        g.words.insert(g.words.end(), coasts_.begin(), coasts_.end());
        g.words.insert(g.words.end(), lights_.begin(), lights_.end());
        return g;
    }

private:
    /// A point of the grid (1e-7°).
    struct Point {
        std::int32_t lat = 0;
        std::int32_t lon = 0;
    };

    /// The square of an edge's length on the ground (frame metres at its middle's latitude): the same for both its
    /// ends' orders, so the two triangles that share an edge, in this tile or the next of its level, cut it alike.
    [[nodiscard]] static double length_squared(Point a, Point b) noexcept {
        const double lat = (static_cast<double>(a.lat) + static_cast<double>(b.lat)) * 0.5e-7;
        const double dx = static_cast<double>(std::int64_t{b.lon} - a.lon) * 1e-7 * metres_per_degree_x *
                          pymath::cos(lat * (std::numbers::pi / 180.0));
        const double dz = static_cast<double>(std::int64_t{b.lat} - a.lat) * 1e-7 * metres_per_degree_z;
        return (dx * dx) + (dz * dz);
    }

    [[nodiscard]] bool too_long(Point a, Point b) const noexcept { return length_squared(a, b) > limit_squared_; }

    /// An edge's middle on the grid (rounded down, whichever end comes first).
    [[nodiscard]] static Point middle(Point a, Point b) noexcept {
        return {.lat = static_cast<std::int32_t>((std::int64_t{a.lat} + b.lat) >> 1),
                .lon = static_cast<std::int32_t>((std::int64_t{a.lon} + b.lon) >> 1)};
    }

    /// A triangle, cut while an edge is longer than the level keeps: at the middles of its long edges, into four, three
    /// or two triangles of its winding (a quad left by two cuts by its shorter diagonal).
    void triangle(Point a, Point b, Point c, std::uint32_t look, int depth) {
        const bool ab = depth > 0 && too_long(a, b);
        const bool bc = depth > 0 && too_long(b, c);
        const bool ca = depth > 0 && too_long(c, a);
        const int d = depth - 1;
        if (ab && bc && ca) {
            const Point mab = middle(a, b);
            const Point mbc = middle(b, c);
            const Point mca = middle(c, a);
            triangle(a, mab, mca, look, d);
            triangle(mab, b, mbc, look, d);
            triangle(mca, mbc, c, look, d);
            triangle(mab, mbc, mca, look, d);
        } else if (ab && bc) {
            two(a, b, c, look, d);
        } else if (bc && ca) {
            two(b, c, a, look, d);
        } else if (ca && ab) {
            two(c, a, b, look, d);
        } else if (ab) {
            const Point m = middle(a, b);
            triangle(a, m, c, look, d);
            triangle(m, b, c, look, d);
        } else if (bc) {
            const Point m = middle(b, c);
            triangle(b, m, a, look, d);
            triangle(m, c, a, look, d);
        } else if (ca) {
            const Point m = middle(c, a);
            triangle(c, m, b, look, d);
            triangle(m, a, b, look, d);
        } else {
            vertices_.push_back(quantised(a.lat, a.lon));
            vertices_.push_back(quantised(b.lat, b.lon));
            vertices_.push_back(quantised(c.lat, c.lon));
            triangles_.push_back(look);
        }
    }

    /// A triangle whose edges p–q and q–r are cut: the corner at q, then the quad p, m(pq), m(qr), r by its shorter
    /// diagonal.
    void two(Point p, Point q, Point r, std::uint32_t look, int depth) {
        const Point m0 = middle(p, q);
        const Point m1 = middle(q, r);
        triangle(m0, q, m1, look, depth);
        if (length_squared(p, m1) <= length_squared(m0, r)) {
            triangle(p, m0, m1, look, depth);
            triangle(p, m1, r, look, depth);
        } else {
            triangle(p, m0, r, look, depth);
            triangle(m0, m1, r, look, depth);
        }
    }

    /// A line's segment, halved while it is longer than the level keeps.
    void segment(Point a, Point b, std::uint32_t look, int depth) {
        if (depth > 0 && too_long(a, b)) {
            const Point m = middle(a, b);
            segment(a, m, look, depth - 1);
            segment(m, b, look, depth - 1);
            return;
        }
        segments_.push_back(quantised(a.lat, a.lon));
        segments_.push_back(quantised(b.lat, b.lon));
        segments_.push_back(look);
    }

    /// A point's fractions of the tile's box, u east from the west edge and v south from the north edge, 0 to 65,535.
    [[nodiscard]] std::uint32_t quantised(std::int32_t lat, std::int32_t lon) const noexcept {
        const auto frac = [](std::int64_t d, std::int64_t span) {
            if (span <= 0) return std::uint32_t{0};
            const auto q = ((std::clamp<std::int64_t>(d, 0, span) * 65'535) + (span / 2)) / span;
            return static_cast<std::uint32_t>(q);
        };
        const std::uint32_t u = frac(std::int64_t{lon} - west_, std::int64_t{east_} - west_);
        const std::uint32_t v = frac(std::int64_t{north_} - lat, std::int64_t{north_} - south_);
        return u | (v << 16U);
    }

    std::int32_t south_;
    std::int32_t west_;
    std::int32_t north_;
    std::int32_t east_;
    const tl::Style* style_;
    double limit_squared_ = 0;
    std::vector<std::vector<std::array<double, 2>>> polygon_;
    std::vector<std::uint32_t> indices_;
    std::vector<std::uint32_t> vertices_;
    std::vector<std::uint32_t> triangles_;
    std::vector<std::uint32_t> segments_;
    std::vector<std::uint32_t> coasts_;
    std::vector<std::uint32_t> lights_;
};

/// The eye's height over the sphere its target's ground bends to; over the flat ground where nothing bends.
[[nodiscard]] double eye_height(const WorldBend& b) noexcept {
    if (!(b.drop > 0.0)) return b.eye.y;
    const double dx = b.eye.x - b.target.x;
    const double dy = (b.eye.y - b.target.y) + earth_radius;
    const double dz = b.eye.z - b.target.z;
    return std::sqrt((dx * dx) + (dy * dy) + (dz * dz)) - earth_radius;
}

/// Each feature of `layer` with its cell, in the layer's order (cell by cell).
template <class F>
void each_row(const tl::Layer& layer, std::uint32_t cells, const F& f) {
    for (std::uint32_t c = 0; c < cells; ++c) {
        for (auto [i, end] = layer.cell(c); i < end; ++i) f(i, c);
    }
}

} // namespace

std::optional<tl::TileKey> world_block_of(std::string_view id) noexcept {
    constexpr std::string_view prefix = "world/";
    if (!id.starts_with(prefix)) return std::nullopt;
    id.remove_prefix(prefix.size());
    std::array<std::uint32_t, 3> v{};
    for (std::size_t i = 0; i < v.size(); ++i) {
        const auto [end, ec] = std::from_chars(id.data(), id.data() + id.size(), v.at(i));
        if (ec != std::errc{}) return std::nullopt;
        id.remove_prefix(static_cast<std::size_t>(end - id.data()));
        if (i + 1 < v.size()) {
            if (!id.starts_with('/')) return std::nullopt;
            id.remove_prefix(1);
        }
    }
    if (!id.empty() || v[0] > world_finest) return std::nullopt;
    return tl::TileKey(static_cast<std::uint8_t>(v[0]), v[1], v[2]);
}

double world_level_start(std::uint8_t z) noexcept { return std::ldexp(l11_start, int{world_finest} - int{z}); }

double world_split_length(std::uint8_t z) noexcept {
    return std::sqrt(8.0 * earth_radius * std::ldexp(bend_tolerance, int{world_finest} - int{z}));
}

DegreeBox world_cap_box(std::int8_t cap, double lon) noexcept {
    return {.north = cap > 0 ? 90.0 : -world_cap_from,
            .south = cap > 0 ? world_cap_from : -90.0,
            .west = lon - 180.0,
            .east = lon + 180.0};
}

WorldGeometry world_cap_geometry(std::uint8_t palette) {
    // the corners' words: u east from the west edge, v south from the north edge
    const auto word = [](std::uint32_t i, std::uint32_t j) {
        const std::uint32_t u = ((i * 65'535U) + (cap_columns / 2)) / cap_columns;
        const std::uint32_t v = ((j * 65'535U) + (cap_rows / 2)) / cap_rows;
        return u | (v << 16U);
    };
    WorldGeometry g;
    g.triangles = 2 * cap_columns * cap_rows;
    g.words.reserve(std::size_t{4} * g.triangles);
    for (std::uint32_t j = 0; j < cap_rows; ++j) {
        for (std::uint32_t i = 0; i < cap_columns; ++i) {
            const std::uint32_t nw = word(i, j);
            const std::uint32_t ne = word(i + 1, j);
            const std::uint32_t sw = word(i, j + 1);
            const std::uint32_t se = word(i + 1, j + 1);
            // counter-clockwise with north up, as earcut leaves the tiles' triangles
            g.words.insert(g.words.end(), {nw, sw, se, nw, se, ne});
        }
    }
    g.words.insert(g.words.end(), g.triangles, std::uint32_t{palette});
    return g;
}

WorldGeometry world_blank_geometry() {
    constexpr std::uint32_t nw = 0;
    constexpr std::uint32_t ne = 65'535U;
    constexpr std::uint32_t sw = 65'535U << 16U;
    constexpr std::uint32_t se = ne | sw;
    WorldGeometry g;
    g.triangles = 2;
    // counter-clockwise with north up, as earcut leaves the tiles' triangles
    g.words = {nw, sw, se, nw, se, ne, std::uint32_t{world_palette::land}, std::uint32_t{world_palette::land}};
    return g;
}

double world_far(double distance, double height) noexcept {
    const double k = distance / world_from;
    const double h = std::max(height, 0.0);
    return std::min(far_at_from * k * k, std::sqrt((2.0 * earth_radius * h) + (h * h)));
}

std::vector<WorldLeaf> walk_world(const Frustum& frustum, const view::GroundFrame& frame,
                                  const std::function<bool(const tl::TileKey&)>& held, const WorldBend& bend,
                                  double reach) {
    std::vector<WorldLeaf> out;
    struct Node {
        tl::TileKey key;
        tl::TileKey holder;
        bool held = false;
    };
    std::vector<Node> stack{{.key = tl::TileKey(0, 0, 0), .holder = {}, .held = false}};
    while (!stack.empty()) {
        Node n = stack.back();
        stack.pop_back();
        const DegreeBox degrees = degrees_of(grid_of(n.key));
        const Box box = world_box(bend, frame, degrees);
        if (!frustum.sees(box) || !before_horizon(bend, degrees)) continue;
        if (held(n.key)) {
            n.holder = n.key;
            n.held = true;
        }
        const double d = frustum.distance(box);
        if (n.key.z < world_finest && d < reach * world_level_start(n.key.z)) {
            // the children in reverse, so the walk takes them north-west first
            const auto z = static_cast<std::uint8_t>(n.key.z + 1);
            for (std::uint32_t k = 4; k-- > 0;) {
                stack.push_back({.key = tl::TileKey(z, (n.key.x * 2) + (k & 1U), (n.key.y * 2) + (k >> 1U)),
                                 .holder = n.holder,
                                 .held = n.held});
            }
            continue;
        }
        if (n.held) out.push_back({.key = n.key, .holder = n.holder, .distance = d});
    }
    return out;
}

double terrain_line_width(std::string_view hw, bool big) noexcept {
    if (hw.ends_with("_link")) hw.remove_suffix(5);
    if (hw == "motorway") return 12.0;
    if (hw == "trunk") return 10.0;
    if (hw == "primary") return 8.0;
    if (hw == "secondary") return 7.0;
    if (hw == "tertiary") return 6.0;
    return big ? 8.0 : 2.0;
}

bool world_lights(std::uint8_t z, std::string_view kind) noexcept {
    constexpr std::uint8_t globe_last = 5;
    constexpr std::uint8_t towns_from = 10;
    if (kind == "city") return true;
    return kind == "town" && z >= towns_from && z > globe_last;
}

WorldGeometry build_world_geometry(const tl::Tile& tile, const tl::Style* style) {
    GeometryWriter w(tile.header, style);
    const std::uint32_t cells = std::uint32_t{tile.header.cells_per_side} * tile.header.cells_per_side;
    const tl::Layer* ground = tile.layer(tl::LayerId::ground);
    const tl::Layer* water = tile.layer(tl::LayerId::water);
    const tl::Layer* road = tile.layer(tl::LayerId::road);
    const tl::Layer* line = tile.layer(tl::LayerId::line);
    // the land under everything, then the land cover (each cell's in the tile's order)
    if (ground != nullptr) {
        each_row(*ground, cells, [&](std::uint32_t i, std::uint32_t c) {
            const std::int64_t source = ground->integer(tl::ground::source, i);
            const auto is = [source](std::uint8_t s) { return std::cmp_equal(source, s); };
            if (is(tl::ground::source_land_base)) {
                w.fill(ground->ring(tl::ground::ring, i), world_palette::land, c);
            } else if (is(tl::ground::source_land)) {
                w.fill(ground->ring(tl::ground::ring, i),
                       w.land_palette(ground->string(tl::ground::kind, i).value_or("")), c);
            }
        });
    }
    // the water: the sea, then lakes, reservoirs and riverbanks
    if (water != nullptr) {
        each_row(*water, cells, [&](std::uint32_t i, std::uint32_t c) {
            w.fill(water->ring(tl::water::ring, i), world_palette::water, c);
        });
    }
    // the areas over the water (the globe's ice shelves, in the ice's colour)
    if (ground != nullptr) {
        each_row(*ground, cells, [&](std::uint32_t i, std::uint32_t c) {
            const std::int64_t source = ground->integer(tl::ground::source, i);
            const auto is = [source](std::uint8_t s) { return std::cmp_equal(source, s); };
            if (!is(tl::ground::source_land_base) && !is(tl::ground::source_land)) {
                w.fill(ground->ring(tl::ground::ring, i),
                       w.land_palette(ground->string(tl::ground::kind, i).value_or("")), c);
            }
        });
    }
    // a globe's tile's coast, the ladder's line along its land's edge, the last of its lines
    constexpr std::uint8_t globe_last = 5;
    if (ground != nullptr && style != nullptr && style->ladder && tile.header.level <= globe_last) {
        each_row(*ground, cells, [&](std::uint32_t i, std::uint32_t c) {
            if (std::cmp_equal(ground->integer(tl::ground::source, i), tl::ground::source_land_base)) {
                w.coast(ground->ring(tl::ground::ring, i), c);
            }
        });
    }
    // the rivers with the water, then the roads by class, the railways, the big roads and the runways
    constexpr std::int64_t rivers = 10;
    if (line != nullptr) {
        each_row(*line, cells, [&](std::uint32_t i, std::uint32_t c) {
            if (line->integer(tl::line::source, i) == rivers) {
                w.line(line->ring(tl::line::line, i), river_width, world_palette::water, c);
            }
        });
    }
    if (road != nullptr) {
        each_row(*road, cells, [&](std::uint32_t i, std::uint32_t c) {
            const std::string_view hw = road->string(tl::road::hw, i).value_or("");
            // the renders' terrain lines: a road's own width where it is mapped, its class's where it is a guess
            const std::optional<double> mapped = road->decimal(tl::road::w, i);
            constexpr std::uint8_t wguess = tl::slot_of(tl::road::columns, "wguess");
            const bool guess = road->boolean(wguess, i).value_or(true);
            const double width = !guess && mapped ? *mapped : terrain_line_width(hw, false);
            w.line(road->ring(tl::road::line, i), width, world_palette::road, c);
        });
    }
    if (line != nullptr) {
        each_row(*line, cells, [&](std::uint32_t i, std::uint32_t c) {
            const std::int64_t source = line->integer(tl::line::source, i);
            const tl::RingView l = line->ring(tl::line::line, i);
            if (source == 1) {
                w.line(l, rail_width, world_palette::rail, c);
            } else if (source == 2) {
                w.line(l, terrain_line_width(line->string(tl::line::hw, i).value_or(""), true), world_palette::asphalt,
                       c);
            } else if (source == 3) {
                w.line(l, runway_width, world_palette::runway, c);
            }
        });
    }
    // the lit places' lights, each sized by its people (the ladder's)
    const tl::Layer* label = tile.layer(tl::LayerId::label);
    if (label != nullptr && style != nullptr && style->ladder) {
        constexpr std::uint8_t population = tl::slot_of(tl::label::columns, "tag:population");
        const std::array<std::int64_t, 2>& sizes = style->ladder->light_sizes;
        each_row(*label, cells, [&](std::uint32_t i, std::uint32_t c) {
            if (label->integer(tl::label::source, i) != tl::label::source_place) return;
            if (!world_lights(tile.header.level, label->string(tl::label::kind, i).value_or(""))) return;
            // its people as the packer ranks its places by them: the number the tag's text starts with (real.py's num)
            const double people = load::num(label->string(population, i)).value_or(0.0);
            const std::uint32_t size =
                people >= static_cast<double>(sizes[0]) ? 0U : (people >= static_cast<double>(sizes[1]) ? 1U : 2U);
            w.light(label->point(tl::label::pos, i), size, c);
        });
    }
    return w.finish();
}

std::vector<WorldName> build_world_names(const tl::Tile& tile) {
    std::vector<WorldName> names;
    // each row's place in the tile's order: the places, then the waters, then the rivers, each by the packer's rank
    constexpr std::int64_t group = std::int64_t{1} << 40U;
    std::vector<std::int64_t> ranks;
    const tl::Layer* label = tile.layer(tl::LayerId::label);
    if (label == nullptr) return names;
    constexpr std::uint8_t population = tl::slot_of(tl::label::columns, "tag:population");
    constexpr std::uint8_t capital = tl::slot_of(tl::label::columns, "tag:capital");
    const std::uint32_t cells = std::uint32_t{tile.header.cells_per_side} * tile.header.cells_per_side;
    each_row(*label, cells, [&](std::uint32_t i, std::uint32_t c) {
        const std::int64_t source = label->integer(tl::label::source, i);
        if (source == tl::label::source_water || source == tl::label::source_river) {
            // a water at its name's point with its area, a river at the middle of its longest piece in the tile with
            // its length there
            const std::optional<NameKind> kind = source == tl::label::source_river
                                                     ? std::optional(NameKind::river)
                                                     : water_kind(label->string(tl::label::kind, i).value_or(""));
            // a name of several languages' (OpenStreetMap's "North Sea / Nordsee / Noordzee" of a water no one
            // language names): its first part, until the packs hold each language's own (ENGINE.md step 5, "Names")
            std::string_view text = label->string(tl::label::name, i).value_or("");
            if (const std::size_t part = text.find(" / "); part != std::string_view::npos) text = text.substr(0, part);
            if (!kind || text.empty()) return;
            const tl::GridPoint at = label->point(tl::label::pos, i);
            ranks.push_back(((source == tl::label::source_river ? 2 : 1) * group) +
                            label->integer(tl::label::index, i));
            WorldName water{.kind = *kind,
                            .cell = static_cast<std::uint8_t>(c),
                            .lat = tl::Frame::degrees(at.lat),
                            .lon = tl::Frame::degrees(at.lon),
                            .text = std::string(text)};
            water.extent = static_cast<double>(label->integer(tl::label::count, i));
            names.push_back(std::move(water));
            return;
        }
        if (source != tl::label::source_place) return;
        const std::string_view place = label->string(tl::label::kind, i).value_or("");
        const std::optional<NameKind> kind = name_kind(place);
        const std::string_view text = label->string(tl::label::name, i).value_or("");
        if (!kind || text.empty()) return;
        const tl::GridPoint at = label->point(tl::label::pos, i);
        ranks.push_back(label->integer(tl::label::index, i));
        names.push_back({.kind = *kind,
                         .capital = label->string(capital, i).value_or("") == "yes",
                         .cell = static_cast<std::uint8_t>(c),
                         .people = load::num(label->string(population, i)).value_or(0.0),
                         .lat = tl::Frame::degrees(at.lat),
                         .lon = tl::Frame::degrees(at.lon),
                         .text = std::string(text),
                         .lit = world_lights(tile.header.level, place)});
    });
    // the tile's order, the packer's rank, which each cell's rows are in: the cells' rows merged by it
    std::vector<std::uint32_t> order(names.size());
    std::ranges::iota(order, 0U);
    std::ranges::stable_sort(order, {}, [&ranks](std::uint32_t n) { return ranks[n]; });
    std::vector<WorldName> ranked;
    ranked.reserve(names.size());
    for (const std::uint32_t n : order) ranked.push_back(std::move(names[n]));
    return ranked;
}

WorldTiles::WorldTiles(const tl::Store& store, std::unique_ptr<Heaps> heaps) noexcept
    : store_(store), heaps_(std::move(heaps)) {}

gpu::Result<std::unique_ptr<WorldTiles>> WorldTiles::create(const gpu::Device& device, const tl::Store& store,
                                                            vk::DeviceSize budget) {
    // one buffer of the whole budget from the start: it never grows, so a frame binds the same buffer throughout
    const vk::DeviceSize bytes = std::max<vk::DeviceSize>(budget & ~vk::DeviceSize{15}, 1U << 20U);
    ATLAS_GPU_LET(heaps, Heaps::create(device, HeapBudget{.initial = bytes, .total = bytes, .binding = 0}));
    std::unique_ptr<WorldTiles> w(new WorldTiles(store, std::move(heaps))); // NOLINT(cppcoreguidelines-owning-memory)
    // the poles' caps, in the heap for good from the first frame on
    for (std::size_t c = 0; c < w->caps_.size(); ++c) {
        const WorldGeometry g = world_cap_geometry(c == 0 ? world_palette::water : world_palette::ice);
        const auto cap_bytes = static_cast<std::uint32_t>(g.words.size() * sizeof(std::uint32_t));
        const auto range = w->heaps_->allocate(HeapKind::ground, cap_bytes, 16, HeapEnd::low);
        if (!range) {
            log::warn("vk world: no room for the poles' caps");
            break;
        }
        w->heaps_->write(*range, std::as_bytes(std::span(g.words)));
        auto cap = std::make_unique<WorldTile>();
        cap->range = *range;
        cap->triangles = g.triangles;
        cap->pinned = true;
        cap->cap = c == 0 ? std::int8_t{1} : std::int8_t{-1};
        w->caps_.at(c) = std::move(cap);
    }
    // the plain land, in the heap for good as well
    {
        const WorldGeometry g = world_blank_geometry();
        const auto blank_bytes = static_cast<std::uint32_t>(g.words.size() * sizeof(std::uint32_t));
        if (const auto range = w->heaps_->allocate(HeapKind::ground, blank_bytes, 16, HeapEnd::low); range) {
            w->heaps_->write(*range, std::as_bytes(std::span(g.words)));
            auto blank = std::make_unique<WorldTile>();
            blank->range = *range;
            blank->triangles = g.triangles;
            blank->pinned = true;
            blank->blank = true;
            w->blank_ = std::move(blank);
        } else {
            log::warn("vk world: no room for the plain land");
        }
    }
    w->heaps_->flush();
    w->worker_ = std::jthread([raw = w.get()](const std::stop_token& stop) { raw->run(stop); });
    return w;
}

WorldTiles::~WorldTiles() {
    {
        const std::scoped_lock lock(mutex_);
        worker_.request_stop();
    }
    wake_worker_.notify_all();
    if (worker_.joinable()) worker_.join();
}

void WorldTiles::set_wake(std::function<void()> wake) {
    const std::scoped_lock lock(mutex_);
    wake_ = std::move(wake);
}

WorldStats WorldTiles::stats() const {
    const std::scoped_lock lock(mutex_);
    WorldStats s = stats_;
    s.resident = resident_.size();
    for (const auto& [key, t] : resident_) {
        s.bytes += t->range.size;
        s.triangles += t->triangles;
    }
    return s;
}

void WorldTiles::wait_idle() {
    std::unique_lock lock(mutex_);
    idle_cv_.wait(lock, [this] { return queue_.empty() && building_ == ~std::uint64_t{0}; });
}

std::vector<WorldWant> WorldTiles::wants() const {
    const std::scoped_lock lock(mutex_);
    return wants_;
}

std::vector<tl::TileKey> WorldTiles::blocks() const {
    const std::scoped_lock lock(mutex_);
    return blocks_;
}

bool WorldTiles::take_style() {
    const std::vector<std::shared_ptr<const tl::StorePack>> open = store_.packs();
    if (open == seen_packs_) return false;
    seen_packs_ = open;
    std::shared_ptr<const tl::StorePack> style;
    for (const std::shared_ptr<const tl::StorePack>& p : open) {
        if (is_world(*p) && p->pack.style() != nullptr) {
            style = p;
            break;
        }
    }
    {
        // the worker reads it for each tile it builds (build)
        const std::scoped_lock lock(mutex_);
        style_pack_.swap(style);
    }
    std::vector<const tl::StorePack*> world;
    for (const std::shared_ptr<const tl::StorePack>& p : open) {
        if (is_world(*p)) world.push_back(p.get());
    }
    if (world == seen_world_) return false;
    seen_world_ = std::move(world);
    open_blocks_.clear();
    for (const tl::StorePack* p : seen_world_) {
        if (const auto block = world_block_of(p->id)) open_blocks_.insert(block->packed());
    }
    return true;
}

void WorldTiles::let_go_of_gone() {
    for (auto it = resident_.begin(); it != resident_.end();) {
        const WorldTile& t = *it->second;
        if (t.pack == nullptr || std::ranges::find(seen_world_, t.pack.get()) != seen_world_.end()) {
            ++it;
            continue;
        }
        let_go(t);
        it = resident_.erase(it);
    }
}

void WorldTiles::run(const std::stop_token& stop) {
#if defined(__ANDROID__)
    (void)setpriority(PRIO_PROCESS, 0, 10); // the workers' background priority (T8): this thread alone on Linux
#endif
    Scratch scratch;
    while (true) {
        tl::TileKey key;
        {
            std::unique_lock lock(mutex_);
            wake_worker_.wait(lock, [&] { return stop.stop_requested() || !queue_.empty(); });
            if (stop.stop_requested()) return;
            key = queue_.front();
            queue_.erase(queue_.begin());
            building_ = key.packed();
        }
        const auto started = std::chrono::steady_clock::now();
        // the cache's writes as the build begins: a chunk kept meanwhile makes the tile's absence stale at once
        const std::shared_ptr<tl::ExpandedCache> cache = store_.cache();
        const std::uint64_t writes = cache != nullptr ? cache->writes() : 0;
        bool no_room = false;
        std::optional<WorldWant> absent;
        std::unique_ptr<WorldTile> tile;
        {
            const trace::Section traced("atlas world tile");
            tile = build(key, scratch, no_room, absent);
        }
        std::function<void()> wake;
        {
            const std::scoped_lock lock(mutex_);
            stats_.build_ms += ms_since(started);
            if (tile) {
                ++stats_.built;
                built_.push_back(std::move(tile));
            } else if (no_room) {
                no_room_.push_back(key);
            } else if (absent) {
                // its chunk not on the phone yet: wanted from the app, and built once the cache has changed
                absent_.insert_or_assign(key.packed(), Absent{.want = std::move(*absent), .writes = writes});
            } else {
                ++stats_.failed;
                failed_keys_.push_back(key.packed());
            }
            building_ = ~std::uint64_t{0};
            // a frame is asked for only where the world draws: a tile built for later (the globe's, kept from the
            // first frame on) changes no frame
            if (drawing_asked_) wake = wake_;
        }
        idle_cv_.notify_all();
        if (wake) wake();
    }
}

std::unique_ptr<WorldTile> WorldTiles::build(const tl::TileKey& key, Scratch& scratch, bool& no_room,
                                             std::optional<WorldWant>& absent) {
    const std::optional<tl::StoreHit> hit = world_hit(store_, key);
    if (!hit) return nullptr;
    scratch.arena.reset();
    const auto tile = store_.decode(*hit, scratch.zstd, scratch.decoder, scratch.arena, scratch.raw);
    if (!tile) {
        if (hit->pack->remote && tile.error().fault == tl::Fault::missing && tile.error().site == tl::Site::file) {
            absent = WorldWant{.tile = key,
                               .pack = hit->pack->id,
                               .identity = hit->pack->identity,
                               .offset = hit->chunk->offset,
                               .length = hit->chunk->stored};
            return nullptr;
        }
        log::warn("vk world: tile {}/{}/{}: {}", key.z, key.x, key.y, tl::describe(tile.error()));
        return nullptr;
    }
    std::shared_ptr<const tl::StorePack> style_pack;
    {
        const std::scoped_lock lock(mutex_);
        style_pack = style_pack_;
    }
    const WorldGeometry g = build_world_geometry(*tile, style_pack ? style_pack->pack.style() : nullptr);
    auto out = std::make_unique<WorldTile>();
    out->key = key;
    out->pack = hit->pack;
    out->box = {tile->header.south, tile->header.west, tile->header.north, tile->header.east};
    out->triangles = g.triangles;
    out->segments = g.segments;
    out->coast = g.coast;
    out->lights = g.lights;
    out->names = build_world_names(*tile);
    out->pinned = key.z <= pinned_last;
    if (g.words.empty()) return out;
    const auto bytes = static_cast<std::uint32_t>(g.words.size() * sizeof(std::uint32_t));
    auto range = heaps_->allocate(HeapKind::ground, bytes, 16, HeapEnd::low);
    if (!range) {
        no_room = true;
        return nullptr;
    }
    heaps_->write(*range, std::as_bytes(std::span(g.words)));
    heaps_->flush();
    out->range = *range;
    return out;
}

void WorldTiles::let_go(const WorldTile& t) {
    if (!t.range) return;
    if (t.ever_drawn) {
        heaps_->retire(t.range, t.drawn); // once the last frame that drew it has finished
    } else {
        heaps_->give_back(t.range);
    }
}

void WorldTiles::make_room(vk::DeviceSize need, std::uint64_t number) {
    std::vector<WorldTile*> idle;
    for (auto& [k, t] : resident_) {
        if (!t->pinned && t->drawn < number) idle.push_back(t.get());
    }
    std::ranges::sort(idle, [](const WorldTile* a, const WorldTile* b) {
        return a->drawn != b->drawn ? a->drawn < b->drawn : a->key.packed() < b->key.packed();
    });
    vk::DeviceSize freed = 0;
    for (WorldTile* t : idle) {
        if (freed >= need) break;
        freed += t->range.size;
        let_go(*t);
        resident_.erase(t->key.packed());
    }
}

void WorldTiles::update(const view::View& view, const view::GroundFrame& frame, double distance, std::uint64_t number,
                        double seconds, double reach, double under_far) {
    const view::Camera& camera = view.camera();
    heaps_->bound(number);
    heaps_->collect(number >= gpu::Frames::in_flight ? number - gpu::Frames::in_flight : 0);
    const bool world_changed = take_style();
    const std::shared_ptr<tl::ExpandedCache> cache = store_.cache();
    // what the worker built since the last frame, and what found no room
    std::vector<tl::TileKey> again;
    {
        const std::scoped_lock lock(mutex_);
        for (std::unique_ptr<WorldTile>& t : built_) {
            const std::uint64_t k = t->key.packed();
            if (auto it = resident_.find(k); it != resident_.end()) let_go(*it->second);
            resident_[k] = std::move(t);
        }
        built_.clear();
        again.swap(no_room_);
        if (world_changed) {
            // other world packs (a new version of the world, a block's head come): what failed or was absent is tried
            // again from them, and the globe's levels are asked for again where its pack changed
            failed_keys_.clear();
            absent_.clear();
        }
        if (cache != nullptr) {
            // chunks kept since a tile's build began: it is built again (from the cache where it now holds its chunk)
            const std::uint64_t writes = cache->writes();
            std::erase_if(absent_, [writes](const auto& a) { return a.second.writes != writes; });
        }
    }
    if (world_changed) {
        let_go_of_gone();
        if (std::ranges::none_of(resident_, [](const auto& r) { return r.second->pinned; })) pinned_asked_ = false;
    }
    drawing_ = false;
    frame_.draws.clear();
    frame_.wanted = 0;
    frame_.fading = false;
    frame_.bare = 0;
    frame_.under = false;
    frame_.bend = world_bend(view, frame);
    if (style_pack_ == nullptr && std::ranges::none_of(seen_packs_, [](const auto& p) { return is_world(*p); })) {
        const std::scoped_lock lock(mutex_);
        wants_.clear();
        blocks_.clear();
        return;
    }
    // The globe's coarse levels, kept resident from the first frame on: every tile the store holds there.
    std::vector<tl::TileKey> wanted;
    if (!pinned_asked_) {
        pinned_asked_ = true;
        for (std::uint8_t z = 0; z <= pinned_last; ++z) {
            const std::uint32_t n = 1U << z;
            for (std::uint32_t y = 0; y < n; ++y) {
                for (std::uint32_t x = 0; x < n; ++x) {
                    if (world_hit(store_, tl::TileKey(z, x, y))) wanted.emplace_back(z, x, y);
                }
            }
        }
    }
    std::vector<tl::TileKey> blocks;
    // up to world_from, under the cover where the camera's place has no country's levels (out to the cover's far plane)
    const bool under = distance <= world_from && under_far > 0.0 && blank_ != nullptr;
    if (distance > world_from || under) {
        drawing_ = true;
        frame_.under = under;
        const WorldBend& bend = frame_.bend;
        const double far = under ? under_far : world_far(distance, eye_height(bend));
        const Frustum frustum(camera, 40.0, far);
        std::unordered_map<std::uint64_t, bool> held_cache;
        const auto held = [&](const tl::TileKey& key) {
            const auto [it, fresh] = held_cache.try_emplace(key.packed(), false);
            if (fresh) it->second = world_hit(store_, key).has_value();
            return it->second;
        };
        const std::vector<WorldLeaf> leaves = walk_world(frustum, frame, held, bend, reach);
        // the poles' caps first, under every tile, where the view sees them
        for (const std::unique_ptr<WorldTile>& cap : caps_) {
            if (!cap) continue;
            const DegreeBox box = world_cap_box(cap->cap, bend.lon);
            if (!frustum.sees(world_box(bend, frame, box)) || !before_horizon(bend, box)) continue;
            cap->drawn = number;
            cap->ever_drawn = true;
            frame_.draws.push_back({.tile = cap.get(), .cells = 1, .fade = 1.0F});
        }
        // the blocks the cover reaches whose world pack is not open, nearest first: the app fetches their heads
        std::vector<std::pair<double, tl::TileKey>> reached;
        const auto reach_block = [&](const WorldLeaf& leaf, std::uint8_t level) {
            const auto up = static_cast<std::uint32_t>(leaf.key.z - level);
            const tl::TileKey b(level, leaf.key.x >> up, leaf.key.y >> up);
            if (!open_blocks_.contains(b.packed())) reached.emplace_back(leaf.distance, b);
        };
        for (const WorldLeaf& leaf : leaves) {
            if (leaf.key.z >= world_coarse_first) reach_block(leaf, world_coarse_block);
            if (leaf.key.z >= world_fine_first) reach_block(leaf, world_fine_block);
        }
        std::ranges::stable_sort(reached, [](const auto& a, const auto& b) { return a.first < b.first; });
        for (const auto& [d, b] : reached) {
            if (std::ranges::none_of(blocks, [&b](const tl::TileKey& k) { return k.packed() == b.packed(); }))
                blocks.push_back(b);
        }
        std::map<std::uint64_t, std::uint64_t> cells;         // by packed key: coarse to fine, then by column and row
        std::unordered_map<std::uint64_t, std::uint64_t> lit; // the cells each tile draws as a leaf's own drawer
        std::vector<std::pair<double, tl::TileKey>> asked;
        // a tile's fade: from the first of the frames drawing it without a break (one just built, or back in view); the
        // globe's resident levels are the floor every fade lies over, drawn whole from the first frame, so a zoom out
        // never shows the frame's clear colour
        const auto fade_of = [seconds](const WorldTile& t) {
            if (t.pinned) return 1.0;
            const double since = t.shown < 0 ? seconds : t.shown;
            return std::clamp((seconds - since) / world_fade_seconds, 0.0, 1.0);
        };
        // the nearest resident ancestor of `key` above level `below`
        const auto ancestor = [this](const tl::TileKey& key, int below) -> WorldTile* {
            for (int z = below - 1; z >= 0; --z) {
                const auto up = static_cast<std::uint32_t>(key.z - z);
                const tl::TileKey a(static_cast<std::uint8_t>(z), key.x >> up, key.y >> up);
                if (auto at = resident_.find(a.packed()); at != resident_.end()) return at->second.get();
            }
            return nullptr;
        };
        // where the ground bends, the floor: each leaf drawn finer than it has its resident ancestor there (or the
        // nearest coarser) drawn under it, so where tiles of two levels meet their sub-pixel cracks show its ground;
        // a leaf drawn at it or coarser has its drawer's parent level's under it (where L2 tiles meet L3's, from
        // 9,600 km out, their seam has an L2 tile under it, whose edge is its L2 neighbour's)
        const bool floored = bend.drop > 0.0;
        // under the cover, a place is drawn by the fine packs' tiles alone (the tile holding it, or while that comes
        // its nearest resident ancestor of theirs), and has the plain land at its box until one has faded in; the
        // first of them names its places, as a leaf's own drawer does beyond world_from (no light is drawn there: the
        // ladder's lights begin beyond world_from)
        std::vector<DegreeBox> blanks;
        const auto fine = [](const tl::TileKey& key) { return key.z >= world_fine_first; };
        for (const WorldLeaf& leaf : leaves) {
            if (!under) break;
            const tl::TileKey& h = leaf.holder;
            const std::array<std::int32_t, 4> leaf_box = tl::tile_box(leaf.key);
            WorldTile* t = nullptr;
            if (fine(h)) {
                if (auto it = resident_.find(h.packed()); it != resident_.end()) {
                    t = it->second.get();
                } else {
                    asked.emplace_back(leaf.distance, h);
                    t = ancestor(h, h.z);
                }
            }
            bool floor = false; // a tile faded in lies under the place
            bool own = true;    // the first: the place's own drawer
            for (; t != nullptr && fine(t->key) && !floor; t = ancestor(t->key, t->key.z)) {
                const std::uint64_t over = cells_overlapping(world_cells_per_side, tl::tile_box(t->key), leaf_box);
                cells[t->key.packed()] |= over;
                if (own) lit[t->key.packed()] |= over;
                own = false;
                floor = fade_of(*t) >= 1.0;
            }
            if (!floor) blanks.push_back(degrees_of(grid_of(leaf.key)));
        }
        // nothing fine in view, held or resident: the cover alone, as before
        if (under && (!cells.empty() || !asked.empty())) {
            blank_->drawn = number;
            blank_->ever_drawn = true;
            for (const DegreeBox& box : blanks) {
                frame_.draws.push_back({.tile = blank_.get(), .cells = 1, .fade = 1.0F, .box = box});
            }
        }
        for (const WorldLeaf& leaf : leaves) {
            if (under) break;
            const tl::TileKey& h = leaf.holder;
            WorldTile* drawer = nullptr;
            if (auto it = resident_.find(h.packed()); it != resident_.end()) {
                drawer = it->second.get();
            } else {
                asked.emplace_back(leaf.distance, h);
                // meanwhile its nearest resident ancestor, its cells over the tile
                drawer = ancestor(h, h.z);
            }
            if (drawer == nullptr) {
                ++frame_.bare;
                continue;
            }
            // and while the drawer fades in, the nearest resident ancestor under it, down to one faded in or the globe;
            // each an ancestor's cells over the leaf by their boxes, its rows being of equal latitude, not Mercator's
            const std::array<std::int32_t, 4> leaf_box = tl::tile_box(leaf.key);
            for (WorldTile* t = drawer; t != nullptr; t = fade_of(*t) < 1.0 ? ancestor(t->key, t->key.z) : nullptr) {
                const std::uint64_t over = cells_overlapping(world_cells_per_side, tl::tile_box(t->key), leaf_box);
                cells[t->key.packed()] |= over;
                // the leaf's own drawer lights its cities, once
                if (t == drawer) lit[t->key.packed()] |= over;
            }
            if (floored && drawer->key.z > 0) {
                const int level = std::min<int>(world_floor, drawer->key.z - 1);
                if (const WorldTile* f = ancestor(leaf.key, level + 1); f != nullptr) {
                    cells[f->key.packed()] |= cells_overlapping(world_cells_per_side, tl::tile_box(f->key), leaf_box);
                }
            }
        }
        for (const auto& [k, bits] : cells) {
            WorldTile& t = *resident_.at(k);
            t.drawn = number;
            t.ever_drawn = true;
            if (t.shown < 0) t.shown = seconds;
            const double fade = fade_of(t);
            frame_.fading = frame_.fading || fade < 1.0;
            if (t.triangles + t.segments > 0) {
                const auto own = lit.find(k);
                frame_.draws.push_back({.tile = &t,
                                        .cells = bits,
                                        .fade = static_cast<float>(fade),
                                        .light_cells = own != lit.end() ? own->second : 0});
            }
        }
        // the tiles wanted nearest first (stable: the walk's order on a tie), each once
        std::ranges::stable_sort(asked, [](const auto& a, const auto& b) { return a.first < b.first; });
        std::vector<std::uint64_t> seen;
        std::vector<tl::TileKey> view_wanted;
        for (const auto& [d, key] : asked) {
            if (std::ranges::find(seen, key.packed()) != seen.end()) continue;
            seen.push_back(key.packed());
            view_wanted.push_back(key);
        }
        frame_.wanted = view_wanted.size();
        wanted.insert(wanted.begin(), view_wanted.begin(), view_wanted.end());
    }
    // a tile not drawn by this frame fades in again when it is next drawn
    for (auto& [k, t] : resident_) {
        if (t->drawn != number) t->shown = -1;
    }
    // room for what is wanted: the least lately drawn go while the heap is fuller than its share, or a build found none
    const vk::DeviceSize total = heaps_->budget().total;
    vk::DeviceSize used = 0;
    for (const auto& [k, t] : resident_) used += t->range.size;
    const auto share = static_cast<vk::DeviceSize>(resident_share * static_cast<double>(total));
    if (!again.empty() || used > share) make_room(used > share ? used - share : total / 8, number);
    bool queued = false;
    {
        const std::scoped_lock lock(mutex_);
        drawing_asked_ = drawing_;
        // the queue anew: what the frame wants, nearest first, and the pinned levels, but what is resident, being
        // built, or failed (a damaged chunk is not asked for again)
        queue_.clear();
        for (const tl::TileKey& k : again) wanted.push_back(k);
        for (const tl::TileKey& k : wanted) {
            const std::uint64_t p = k.packed();
            if (resident_.contains(p) || p == building_ || std::ranges::find(failed_keys_, p) != failed_keys_.end() ||
                absent_.contains(p) ||
                std::ranges::any_of(queue_, [p](const tl::TileKey& q) { return q.packed() == p; })) {
                continue;
            }
            queue_.push_back(k);
        }
        queued = !queue_.empty();
        // what is wanted of the app: the chunks absent, in the frame's order, and the blocks reached without a pack
        wants_.clear();
        std::unordered_set<std::uint64_t> listed;
        for (const tl::TileKey& k : wanted) {
            const auto it = absent_.find(k.packed());
            if (it != absent_.end() && !resident_.contains(k.packed()) && listed.insert(k.packed()).second)
                wants_.push_back(it->second.want);
        }
        blocks_ = std::move(blocks);
    }
    if (queued) wake_worker_.notify_one();
    frame_.buffer = heaps_->buffers().low;
    frame_.buffer_bytes = heaps_->capacity();
    frame_.frame = frame;
    frame_.style = style_pack_ ? style_pack_->pack.style() : nullptr;
    if (drawing_ && frame_.draws.empty() && frame_.wanted == 0) drawing_ = false;
}

} // namespace atlas::scene
