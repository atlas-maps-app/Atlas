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
#include <cstring>
#include <format>
#include <map>
#include <numbers>
#include <numeric>
#include <string_view>
#include <utility>

#if defined(__ANDROID__)
#include <pthread.h>
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
/// The bytes a piece holds past a tile's geometry, for its range's alignment (16): one that large holds it wherever
/// its offset lies.
constexpr vk::DeviceSize room_slack = 16;
/// A tile no piece holds whole lies in at most this many parts (each a draw of its fills and one of its lines: a
/// frame's draws stay few), each in a piece of part_least bytes or more, of which part_slack may stay free: its
/// offset's alignment and the end of a list's last word that does not fit (a triangle's 16 bytes, a segment's 12, a
/// light's 8).
constexpr std::size_t parts_most = 16;
constexpr vk::DeviceSize part_least = 256;
constexpr vk::DeviceSize part_slack = 32;
/// The widths of the lines the renders' terrain draws none of: a runway (its usual 45 m) and a river.
constexpr double runway_width = 45.0;
constexpr double river_width = 10.0;
/// The railways' width in the renders' terrain lines (regions.py: 3.0).
constexpr double rail_width = 3.0;

[[nodiscard]] double ms_since(std::chrono::steady_clock::time_point t) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
}

/// Whether the heap's pieces free (their sizes: Heaps::free_pieces, pieces_with) hold `bytes` of a tile's geometry as
/// WorldTiles::place places it: whole in one, else in parts in the largest parts_most of them, each less part_slack.
[[nodiscard]] bool holds(std::vector<vk::DeviceSize> pieces, std::uint32_t bytes) {
    std::ranges::sort(pieces, std::greater<>{});
    if (!pieces.empty() && pieces.front() >= vk::DeviceSize{bytes} + room_slack) return true;
    vk::DeviceSize room = 0;
    for (std::size_t i = 0; i < pieces.size() && i < parts_most && pieces[i] >= part_least + part_slack; ++i) {
        room += pieces[i] - part_slack;
    }
    return room >= bytes;
}

/// Whether the pieces hold tiles of `sizes` bytes one after another, the largest first, each as holds() judges it and
/// taking what place() would take of them (whole: a piece with its slack; in parts: the largest, each with its slack).
[[nodiscard]] bool holds_all(std::vector<vk::DeviceSize> pieces, std::vector<std::uint32_t> sizes) {
    std::ranges::sort(sizes, std::greater<>{});
    for (const std::uint32_t bytes : sizes) {
        std::ranges::sort(pieces, std::greater<>{});
        if (!holds(pieces, bytes)) return false;
        if (pieces.front() >= vk::DeviceSize{bytes} + room_slack) {
            pieces.front() -= vk::DeviceSize{bytes} + room_slack;
            continue;
        }
        vk::DeviceSize left = bytes;
        for (std::size_t i = 0; i < pieces.size() && i < parts_most && left > 0 && pieces[i] >= part_least + part_slack;
             ++i) {
            const vk::DeviceSize take = std::min(left, pieces[i] - part_slack);
            left -= take;
            pieces[i] -= take + part_slack;
        }
    }
    return true;
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

WaterMask world_water_mask(const WorldGeometry& geometry) noexcept {
    WaterMask mask{};
    // a cell's middle in a vertex word's units (0 to 65,535 over the box), doubled so it is whole
    static constexpr std::int64_t unit = 65'535;
    static constexpr std::int64_t side = water_mask_side;
    const std::size_t count = geometry.triangles;
    if (geometry.words.size() < count * 4) return mask;
    const std::span<const std::uint32_t> vertices(geometry.words.data(), count * 3);
    const std::span<const std::uint32_t> looks(geometry.words.data() + (count * 3), count);
    for (std::size_t t = 0; t < count; ++t) {
        std::array<std::int64_t, 3> x{};
        std::array<std::int64_t, 3> y{};
        for (std::size_t k = 0; k < 3; ++k) {
            const std::uint32_t word = vertices[(t * 3) + k];
            // scaled by 2·side: a cell's middle c lies at (2c + 1)·unit
            x[k] = std::int64_t{word & 0xFFFFU} * 2 * side;
            y[k] = std::int64_t{word >> 16U} * 2 * side;
        }
        const auto first = [](std::int64_t least) {
            // the first cell whose middle is at or beyond `least`: (2c + 1)·unit >= least
            const std::int64_t c = (least - unit + (2 * unit) - 1) / (2 * unit);
            return std::clamp<std::int64_t>(least <= unit ? 0 : c, 0, side);
        };
        const auto last = [](std::int64_t most) {
            // the last cell whose middle is at or before `most`
            return most < unit ? std::int64_t{-1} : std::min<std::int64_t>((most - unit) / (2 * unit), side - 1);
        };
        const std::int64_t c0 = first(std::min({x[0], x[1], x[2]}));
        const std::int64_t c1 = last(std::max({x[0], x[1], x[2]}));
        const std::int64_t r0 = first(std::min({y[0], y[1], y[2]}));
        const std::int64_t r1 = last(std::max({y[0], y[1], y[2]}));
        if (c0 > c1 || r0 > r1) continue;
        const bool water = (looks[t] & 0xFFU) == world_palette::water;
        const auto edge = [&](std::size_t a, std::size_t b, std::int64_t px, std::int64_t py) {
            return ((x[b] - x[a]) * (py - y[a])) - ((y[b] - y[a]) * (px - x[a]));
        };
        for (std::int64_t r = r0; r <= r1; ++r) {
            for (std::int64_t c = c0; c <= c1; ++c) {
                const std::int64_t px = ((2 * c) + 1) * unit;
                const std::int64_t py = ((2 * r) + 1) * unit;
                const std::int64_t e0 = edge(0, 1, px, py);
                const std::int64_t e1 = edge(1, 2, px, py);
                const std::int64_t e2 = edge(2, 0, px, py);
                const bool inside = (e0 >= 0 && e1 >= 0 && e2 >= 0) || (e0 <= 0 && e1 <= 0 && e2 <= 0);
                if (!inside) continue;
                const std::uint64_t bit = std::uint64_t{1} << static_cast<unsigned>(c);
                auto& row = mask.at(static_cast<std::size_t>(r));
                row = water ? (row | bit) : (row & ~bit);
            }
        }
    }
    return mask;
}

std::optional<bool> world_water_at(const WorldFrame& frame, double lat, double lon) noexcept {
    double l = lon;
    while (l >= 180.0) l -= 360.0;
    while (l < -180.0) l += 360.0;
    const double glat = lat * 1e7;
    const double glon = l * 1e7;
    const WorldTile* found = nullptr;
    double fu = 0.0;
    double fv = 0.0;
    for (const WorldDraw& draw : frame.draws) {
        const WorldTile* t = draw.tile;
        if (t == nullptr || t->cap != 0 || t->blank) continue;
        if (found != nullptr && t->key.z <= found->key.z) continue;
        const auto south = static_cast<double>(t->box[0]);
        const auto west = static_cast<double>(t->box[1]);
        const auto north = static_cast<double>(t->box[2]);
        const auto east = static_cast<double>(t->box[3]);
        if (glat < south || glat >= north || glon < west || glon >= east) continue;
        const double u = (glon - west) / (east - west);
        const double v = (north - glat) / (north - south);
        const auto per_side = static_cast<double>(world_cells_per_side);
        const auto cell =
            (static_cast<unsigned>(v * per_side) * world_cells_per_side) + static_cast<unsigned>(u * per_side);
        if (((draw.cells >> cell) & 1U) == 0) continue;
        found = t;
        fu = u;
        fv = v;
    }
    if (found == nullptr) return std::nullopt;
    const auto side = static_cast<double>(water_mask_side);
    const auto row = std::min<std::size_t>(static_cast<std::size_t>(fv * side), water_mask_side - 1);
    const auto column = std::min<unsigned>(static_cast<unsigned>(fu * side), water_mask_side - 1);
    return ((found->water.at(row) >> column) & 1U) != 0;
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
        const bool river = std::cmp_equal(source, tl::label::source_river);
        if (river || std::cmp_equal(source, tl::label::source_water)) {
            // a water at its name's point with its area, a river at the middle of its longest piece in the tile with
            // its length there
            const std::optional<NameKind> kind =
                river ? std::optional(NameKind::river) : water_kind(label->string(tl::label::kind, i).value_or(""));
            // a name of several languages' (OpenStreetMap's "North Sea / Nordsee / Noordzee" of a water no one
            // language names): its first part, until the packs hold each language's own (ENGINE.md step 5, "Names")
            std::string_view text = label->string(tl::label::name, i).value_or("");
            if (const std::size_t part = text.find(" / "); part != std::string_view::npos) text = text.substr(0, part);
            if (!kind || text.empty()) return;
            const tl::GridPoint at = label->point(tl::label::pos, i);
            ranks.push_back(((river ? 2 : 1) * group) + label->integer(tl::label::index, i));
            WorldName water{.kind = *kind,
                            .cell = static_cast<std::uint8_t>(c),
                            .lat = tl::Frame::degrees(at.lat),
                            .lon = tl::Frame::degrees(at.lon),
                            .text = std::string(text)};
            water.extent = static_cast<double>(label->integer(tl::label::count, i));
            names.push_back(std::move(water));
            return;
        }
        if (!std::cmp_equal(source, tl::label::source_place)) return;
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
                                                            vk::DeviceSize initial, vk::DeviceSize budget) {
    // one buffer of `initial` bytes, doubled up to the budget once a tile's range lies beyond it (Heaps::allocate on
    // the worker); a frame binds it as it is when the frame begins (update), the one it replaced kept until the
    // frames bound to it have finished. Each doubling within the device's budget too: one it refuses (its memory, or
    // the driver's budget where it reports one) ends the budget at the buffer, and the tiles that find no room then
    // wait for the room it has, held back where none comes (update)
    const vk::DeviceSize bytes = std::max<vk::DeviceSize>(budget & ~vk::DeviceSize{15}, 16);
    const vk::DeviceSize first = std::clamp<vk::DeviceSize>(initial & ~vk::DeviceSize{15}, 16, bytes);
    ATLAS_GPU_LET(
        heaps,
        Heaps::create(device, HeapBudget{.initial = first, .total = bytes, .binding = 0, .within_device = true}));
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
        cap->parts.push_back({.range = *range, .triangles = g.triangles});
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
            blank->parts.push_back({.range = *range, .triangles = g.triangles});
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
        const vk::DeviceSize bytes = t->bytes();
        s.parts += t->parts.size();
        s.bytes += bytes;
        s.triangles += t->triangles;
        if (t->drawn == number_) s.drawn_bytes += bytes;
        if (t->pinned) s.pinned_bytes += bytes;
    }
    s.capacity = heaps_->total();
    s.buffer = heaps_->capacity();
    s.free = heaps_->free_bytes();
    s.largest_free = heaps_->largest_free(HeapKind::ground);
    s.stand_in_bytes = stand_in_bytes_;
    s.wanted_bytes = wanted_bytes_;
    s.wanted_unsized = wanted_unsized_;
    return s;
}

void WorldTiles::wait_idle() {
    std::unique_lock lock(mutex_);
    idle_cv_.wait(lock, [this] { return queue_.empty() && building_ == ~std::uint64_t{0}; });
}

void WorldTiles::end_budget() { heaps_->end_budget(); }

void WorldTiles::fragment_for_tests(vk::DeviceSize piece) { heaps_->fragment_for_tests(HeapKind::ground, piece, 16); }

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
    // named: made on the render thread, it would show under that thread's name in a trace
    (void)::pthread_setname_np(::pthread_self(), "atlas-world");
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
        std::uint32_t bytes = 0;
        std::optional<WorldWant> absent;
        std::unique_ptr<WorldTile> tile;
        {
            const trace::Section traced("atlas world tile");
            tile = build(key, scratch, no_room, bytes, absent);
        }
        std::function<void()> wake;
        {
            const std::scoped_lock lock(mutex_);
            stats_.build_ms += ms_since(started);
            if (tile) {
                ++stats_.built;
                built_.push_back(std::move(tile));
            } else if (no_room) {
                ++stats_.no_room;
                stats_.no_room_bytes = bytes;
                roomless_[key.packed()] = bytes;
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
                                             std::uint32_t& bytes, std::optional<WorldWant>& absent) {
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
    out->water = world_water_mask(g);
    out->pinned = key.z <= pinned_last;
    if (g.words.empty()) return out;
    bytes = static_cast<std::uint32_t>(g.words.size() * sizeof(std::uint32_t));
    if (!place(g, *out)) {
        no_room = true;
        return nullptr;
    }
    heaps_->flush();
    return out;
}

bool WorldTiles::place(const WorldGeometry& g, WorldTile& t) {
    // whole, where a piece holds it (first fit, as before parts)
    const auto bytes = static_cast<std::uint32_t>(g.words.size() * sizeof(std::uint32_t));
    if (const auto range = heaps_->allocate(HeapKind::ground, bytes, 16, HeapEnd::low); range) {
        heaps_->write(*range, std::as_bytes(std::span(g.words)));
        t.parts.push_back(
            {.range = *range, .triangles = g.triangles, .segments = g.segments, .coast = g.coast, .lights = g.lights});
        return true;
    }
    // else in parts: each in the largest piece free now (the worker alone places the world's ranges, so it holds
    // the part), as much of the tile's lists as it holds whatever its offset, in their order: its triangles (their
    // vertices' three words and their own word), then its segments, then its lights, each whole. A growth the device
    // refuses ends the budget at the buffer, whose pieces are then looked at again
    const std::span<const std::uint32_t> words(g.words);
    const std::size_t at_words = std::size_t{3} * g.triangles;
    const std::size_t at_segments = std::size_t{4} * g.triangles;
    const std::size_t at_lights = at_segments + (std::size_t{3} * g.segments);
    const std::uint32_t coast_from = g.segments - g.coast;
    std::uint32_t triangles = 0;
    std::uint32_t segments = 0;
    std::uint32_t lights = 0;
    int refused = 0;
    while ((triangles < g.triangles || segments < g.segments || lights < g.lights) && t.parts.size() < parts_most) {
        const vk::DeviceSize largest = heaps_->largest_free(HeapKind::ground);
        if (largest < part_least + part_slack) break;
        vk::DeviceSize room = largest - (room_slack - 1);
        const auto take = [&room](std::uint32_t left, vk::DeviceSize each) {
            const auto n = static_cast<std::uint32_t>(std::min<vk::DeviceSize>(left, room / each));
            room -= n * each;
            return n;
        };
        const std::uint32_t t_n = take(g.triangles - triangles, 16);
        const std::uint32_t s_n = take(g.segments - segments, 12);
        const std::uint32_t l_n = take(g.lights - lights, 8);
        const std::uint32_t part_bytes = (16 * t_n) + (12 * s_n) + (8 * l_n);
        const auto range = heaps_->allocate(HeapKind::ground, part_bytes, 16, HeapEnd::low);
        if (!range) {
            if (++refused > 1) break;
            continue;
        }
        {
            const Heaps::Writer writer = heaps_->writer(*range);
            const std::span<std::byte> to = writer.bytes();
            std::size_t at = 0;
            const auto copy = [&](std::size_t from, std::size_t count) {
                const std::span<const std::byte> part = std::as_bytes(words.subspan(from, count));
                std::ranges::copy(part, to.subspan(at).begin());
                at += part.size();
            };
            copy(std::size_t{3} * triangles, std::size_t{3} * t_n); // its triangles' vertices
            copy(at_words + triangles, t_n);                        // their words
            copy(at_segments + (std::size_t{3} * segments), std::size_t{3} * s_n);
            copy(at_lights + (std::size_t{2} * lights), std::size_t{2} * l_n);
        }
        // its coast's segments: those of the tile's last g.coast among its own, the last of them
        const std::uint32_t end = segments + s_n;
        const std::uint32_t coast = end > std::max(segments, coast_from) ? end - std::max(segments, coast_from) : 0;
        t.parts.push_back({.range = *range, .triangles = t_n, .segments = s_n, .coast = coast, .lights = l_n});
        triangles += t_n;
        segments += s_n;
        lights += l_n;
    }
    if (triangles == g.triangles && segments == g.segments && lights == g.lights) return true;
    for (const WorldPart& p : t.parts) heaps_->give_back(p.range);
    t.parts.clear();
    return false;
}

void WorldTiles::let_go(const WorldTile& t) {
    for (const WorldPart& p : t.parts) {
        if (!p.range) continue;
        if (t.ever_drawn) {
            heaps_->retire(p.range, t.drawn); // once the last frame that drew it has finished
        } else {
            heaps_->give_back(p.range);
        }
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
        freed += t->bytes();
        let_go(*t);
        resident_.erase(t->key.packed());
    }
}

WorldTiles::Piece WorldTiles::make_piece(std::uint32_t bytes, std::uint64_t number) {
    if (holds(heaps_->free_pieces(HeapKind::ground), bytes)) return Piece::now;
    std::vector<HeapRange> back;
    if (holds(heaps_->pieces_with(back, HeapKind::ground), bytes)) return Piece::coming;
    // the candidates: the tiles the frame does not draw, but the globe's pinned levels, the least lately drawn first
    // (in the keys' order on a tie), as make_room lets them go: in parts a tile takes the room their going makes
    // wherever it lies
    std::vector<WorldTile*> idle;
    for (auto& [k, t] : resident_) {
        if (!t->pinned && t->drawn < number) idle.push_back(t.get());
    }
    std::ranges::sort(idle, [](const WorldTile* a, const WorldTile* b) {
        return a->drawn != b->drawn ? a->drawn < b->drawn : a->key.packed() < b->key.packed();
    });
    std::size_t chosen = 0;
    while (!holds(heaps_->pieces_with(back, HeapKind::ground), bytes)) {
        if (chosen == idle.size()) return Piece::none; // their going would not make it: none goes
        for (const WorldPart& p : idle[chosen]->parts) back.push_back(p.range);
        ++chosen;
    }
    for (std::size_t i = 0; i < chosen; ++i) {
        const std::uint64_t k = idle[i]->key.packed();
        let_go(*idle[i]);
        resident_.erase(k);
    }
    log::info("vk world: {} tiles let go for room of {} bytes, {} free in pieces of {} at most", chosen, bytes,
              heaps_->free_bytes(), heaps_->largest_free(HeapKind::ground));
    return Piece::made;
}

bool WorldTiles::trade(std::uint64_t key, const std::unordered_map<std::uint64_t, std::uint64_t>& stood_by,
                       const std::unordered_set<std::uint64_t>& kept,
                       const std::unordered_map<std::uint64_t, std::uint32_t>& roomless, std::uint64_t number) {
    const auto by = stood_by.find(key);
    if (by == stood_by.end() || kept.contains(by->second)) return false;
    const auto stand_in = resident_.find(by->second);
    if (stand_in == resident_.end() || stand_in->second->pinned) return false;
    // the tiles it stands in for, each one whose build found no room: one not built yet (or absent) has bytes unknown
    std::vector<std::uint32_t> sizes;
    for (const auto& [wanted, s] : stood_by) {
        if (s != by->second) continue;
        const auto r = roomless.find(wanted);
        if (r == roomless.end()) return false;
        sizes.push_back(r->second);
    }
    // the room its going makes, with that of the tiles the frame does not draw (make_piece lets those go as each
    // tile's turn comes), the ranges let go of already, and the pieces free
    std::vector<HeapRange> back;
    for (const WorldPart& p : stand_in->second->parts) back.push_back(p.range);
    for (const auto& [k, t] : resident_) {
        if (t->pinned || t->drawn >= number) continue;
        for (const WorldPart& p : t->parts) back.push_back(p.range);
    }
    if (!holds_all(heaps_->pieces_with(back, HeapKind::ground), sizes)) return false;
    const WorldTile& t = *stand_in->second;
    log::info("vk world: the stand-in {}/{}/{} of {} bytes let go of for the {} tiles it stood in for", t.key.z,
              t.key.x, t.key.y, t.bytes(), sizes.size());
    let_go(t);
    leaving_.push_back(std::move(stand_in->second));
    resident_.erase(stand_in);
    const std::scoped_lock lock(mutex_);
    ++stats_.traded;
    return true;
}

void WorldTiles::update(const view::View& view, const view::GroundFrame& frame, double distance, std::uint64_t number,
                        double seconds, double reach, double under_far) {
    const view::Camera& camera = view.camera();
    number_ = number;
    // the last frame's draws no longer name the stand-ins it traded
    leaving_.clear();
    stand_in_bytes_ = 0;
    wanted_bytes_ = 0;
    wanted_unsized_ = 0;
    heaps_->bound(number);
    heaps_->collect(number >= gpu::Frames::in_flight ? number - gpu::Frames::in_flight : 0);
    const bool world_changed = take_style();
    const std::shared_ptr<tl::ExpandedCache> cache = store_.cache();
    // what the worker built since the last frame, and what found no room, with the bytes each asked for
    std::unordered_map<std::uint64_t, std::uint32_t> roomless;
    {
        const std::scoped_lock lock(mutex_);
        for (std::unique_ptr<WorldTile>& t : built_) {
            const std::uint64_t k = t->key.packed();
            if (auto it = resident_.find(k); it != resident_.end()) let_go(*it->second);
            resident_[k] = std::move(t);
            roomless_.erase(k);
        }
        built_.clear();
        if (world_changed) {
            roomless_.clear();
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
        roomless = roomless_;
    }
    if (world_changed) {
        let_go_of_gone();
        if (std::ranges::none_of(resident_, [](const auto& r) { return r.second->pinned; })) pinned_asked_ = false;
    }
    drawing_ = false;
    frame_.draws.clear();
    frame_.wanted = 0;
    frame_.held = 0;
    frame_.fading = false;
    frame_.settling = false;
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
    // each wanted tile's stand-in, by the wanted tile's key, and the tiles the frame draws for a place of their own,
    // under a tile fading in or as a floor: a stand-in none of those is drawn for the tiles it stands in for alone,
    // which its room may hold (trade)
    std::unordered_map<std::uint64_t, std::uint64_t> stood_by;
    std::unordered_set<std::uint64_t> kept;
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
        // the resident ancestors drawn for tiles not resident (their stand-ins), but the pinned
        std::unordered_set<std::uint64_t> stand_ins;
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
                    kept.insert(h.packed());
                } else {
                    asked.emplace_back(leaf.distance, h);
                    t = ancestor(h, h.z);
                    if (t != nullptr && fine(t->key) && !t->pinned) {
                        stand_ins.insert(t->key.packed());
                        stood_by.try_emplace(h.packed(), t->key.packed());
                    }
                }
            }
            bool floor = false; // a tile faded in lies under the place
            bool own = true;    // the first: the place's own drawer
            for (; t != nullptr && fine(t->key) && !floor; t = ancestor(t->key, t->key.z)) {
                const std::uint64_t over = cells_overlapping(world_cells_per_side, tl::tile_box(t->key), leaf_box);
                cells[t->key.packed()] |= over;
                if (own) lit[t->key.packed()] |= over;
                if (!own) kept.insert(t->key.packed()); // under one fading in
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
                kept.insert(h.packed());
            } else {
                asked.emplace_back(leaf.distance, h);
                // meanwhile its nearest resident ancestor, its cells over the tile
                drawer = ancestor(h, h.z);
                if (drawer != nullptr && !drawer->pinned) {
                    stand_ins.insert(drawer->key.packed());
                    stood_by.try_emplace(h.packed(), drawer->key.packed());
                }
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
                if (t == drawer) {
                    lit[t->key.packed()] |= over;
                } else {
                    kept.insert(t->key.packed()); // under one fading in
                }
            }
            if (floored && drawer->key.z > 0) {
                const int level = std::min<int>(world_floor, drawer->key.z - 1);
                if (const WorldTile* f = ancestor(leaf.key, level + 1); f != nullptr) {
                    cells[f->key.packed()] |= cells_overlapping(world_cells_per_side, tl::tile_box(f->key), leaf_box);
                    kept.insert(f->key.packed());
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
        for (const std::uint64_t k : stand_ins) stand_in_bytes_ += resident_.at(k)->bytes();
    }
    // a tile not drawn by this frame fades in again when it is next drawn
    for (auto& [k, t] : resident_) {
        if (t->drawn != number) t->shown = -1;
    }
    // room for what is wanted: the least lately drawn go while the heap's buffer as it is now is fuller than its
    // share, so the tiles no frame draws go before it grows
    const vk::DeviceSize total = heaps_->capacity();
    vk::DeviceSize used = 0;
    for (const auto& [k, t] : resident_) used += t->bytes();
    const auto share = static_cast<vk::DeviceSize>(resident_share * static_cast<double>(total));
    if (used > share) make_room(used - share, number);
    // and a tile whose build found no room is built again once the heap's pieces hold it, whole or in parts, not
    // before: built again each frame, each build asking for the next frame, the world's pitched views from 60 to
    // 1,000 km over the Netherlands rebuilt their wanted tiles at rest (ENGINE.md step 11). Nearest first, the first
    // the pieces do not hold has the tiles the frame does not draw let go of for it (make_piece) and waits while their
    // bytes come back, the frames coming meanwhile, the others after it in their turn; where their going would not
    // make room, its stand-in's may: drawn for the tiles it stands in for alone, it is let go of where its room holds
    // them all (trade), so the stand-ins sharpen (den helder 500 km pitched, 13 November: 4.2 MB of stand-in for a
    // tile of 4.4 MB, the heap's 89.5 MB full of the view's tiles and the globe's pinned levels); else it is held
    // back, its place drawn by its nearest resident ancestor, final, until the heap's pieces change
    std::unordered_set<std::uint64_t> ready;
    const std::size_t view_count = frame_.wanted; // the view's of `wanted`, its first, those held back among them
    {
        const std::uint64_t frees = heaps_->frees();
        bool making = false;                      // a piece is coming for one: the others wait for theirs
        std::uint32_t unmade = ~std::uint32_t{0}; // the fewest bytes no piece could be made for: none for more
        std::vector<tl::TileKey> room_keys(wanted.begin(), wanted.begin() + static_cast<std::ptrdiff_t>(view_count));
        // and the globe's that found none, kept resident from the first frame on, asked for once: in the keys' order
        std::vector<tl::TileKey> globe;
        for (const auto& [p, bytes] : roomless) {
            const tl::TileKey k = tl::TileKey::unpack(p);
            if (k.z <= pinned_last) globe.push_back(k);
        }
        std::ranges::sort(globe, {}, &tl::TileKey::packed);
        room_keys.insert(room_keys.end(), globe.begin(), globe.end());
        for (const tl::TileKey& k : globe) {
            if (std::ranges::none_of(wanted, [&k](const tl::TileKey& w) { return w.packed() == k.packed(); }))
                wanted.push_back(k);
        }
        for (std::size_t i = 0; i < room_keys.size(); ++i) {
            const std::uint64_t p = room_keys[i].packed();
            const auto r = roomless.find(p);
            if (r == roomless.end()) continue; // never built, or built since: asked for as any
            const bool in_view = i < view_count;
            if (holds(heaps_->free_pieces(HeapKind::ground), r->second)) {
                ready.insert(p);
                room_waits_.erase(p);
                continue;
            }
            if (const auto w = room_waits_.find(p); w != room_waits_.end()) {
                if (w->second == frees) { // its bytes on their way back
                    frame_.settling = true;
                    making = true;
                    continue;
                }
                room_waits_.erase(w); // they came, and no piece holds it: one is made again, or it is held back
            }
            if (making) {
                frame_.settling = true;
                continue;
            }
            const Piece piece = r->second >= unmade ? Piece::none : make_piece(r->second, number);
            if (piece == Piece::now) {
                ready.insert(p);
            } else if (piece == Piece::none) {
                // its stand-in's going: it waits for those bytes as for the ones make_piece lets go of
                if (in_view && trade(p, stood_by, kept, roomless, number)) {
                    room_waits_[p] = frees;
                    frame_.settling = true;
                    making = true;
                    continue;
                }
                unmade = std::min(unmade, r->second);
                if (in_view) ++frame_.held;
            } else {
                room_waits_[p] = frees;
                frame_.settling = true;
                making = true;
            }
        }
        frame_.wanted -= frame_.held;
        std::erase_if(room_waits_, [&room_keys](const auto& w) {
            return std::ranges::none_of(room_keys, [&w](const tl::TileKey& k) { return k.packed() == w.first; });
        });
    }
    bool queued = false;
    {
        const std::scoped_lock lock(mutex_);
        drawing_asked_ = drawing_;
        // the queue anew: what the frame wants, nearest first, and the pinned levels, but what is resident, being
        // built, or failed (a damaged chunk is not asked for again)
        queue_.clear();
        for (const tl::TileKey& k : wanted) {
            const std::uint64_t p = k.packed();
            if (resident_.contains(p) || p == building_ || std::ranges::find(failed_keys_, p) != failed_keys_.end() ||
                absent_.contains(p) || (roomless_.contains(p) && !ready.contains(p)) ||
                std::ranges::any_of(queue_, [p](const tl::TileKey& q) { return q.packed() == p; })) {
                continue;
            }
            queue_.push_back(k);
        }
        // a tile no longer wanted is built again where it is wanted again (its bytes then the heap's to judge anew)
        std::erase_if(roomless_, [&wanted](const auto& r) {
            return std::ranges::none_of(wanted, [&r](const tl::TileKey& k) { return k.packed() == r.first; });
        });
        queued = !queue_.empty();
        // the bytes the frame's wanted tiles asked for where their builds found no room (the first of `wanted`, those
        // held back among them: the room the frame takes whole)
        for (std::size_t i = 0; i < view_count && i < wanted.size(); ++i) {
            const auto it = roomless_.find(wanted[i].packed());
            if (it != roomless_.end()) {
                wanted_bytes_ += it->second;
            } else {
                ++wanted_unsized_;
            }
        }
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
    // the buffer this frame binds, its bytes read first: a growth on the worker in between leaves them within it, and
    // every tile the frame draws was built, and its range placed, before they were read
    frame_.buffer_bytes = heaps_->capacity();
    frame_.buffer = heaps_->bind(number).low;
    frame_.frame = frame;
    frame_.style = style_pack_ ? style_pack_->pack.style() : nullptr;
    // measurement (branch worldsums, never lands): the frame's draws, the words they draw and its frame and bend
    {
        constexpr std::uint64_t prime = 0x100000001b3ULL;
        const auto mix = [](std::uint64_t& h, const void* data, std::size_t size) {
            const auto* p = static_cast<const unsigned char*>(data);
            for (std::size_t i = 0; i < size; ++i) h = (h ^ p[i]) * prime;
        };
        const auto mix_words = [](std::uint64_t& h, std::span<const std::byte> bytes) {
            const std::size_t n = bytes.size() / 4;
            for (std::size_t i = 0; i < n; ++i) {
                std::uint32_t w = 0;
                std::memcpy(&w, bytes.data() + (i * 4), 4);
                h = (h ^ w) * prime;
            }
        };
        std::uint64_t draws = 0xcbf29ce484222325ULL;
        std::uint64_t words = 0xcbf29ce484222325ULL;
        std::uint64_t placed = 0xcbf29ce484222325ULL;
        for (const WorldDraw& d : frame_.draws) {
            const std::uint64_t key = d.tile->key.packed();
            mix(draws, &key, sizeof key);
            mix(draws, &d.cells, sizeof d.cells);
            mix(draws, &d.light_cells, sizeof d.light_cells);
            mix(draws, &d.fade, sizeof d.fade);
            mix(draws, &d.box, sizeof d.box);
            mix(draws, &d.tile->cap, sizeof d.tile->cap);
            mix(draws, d.tile->box.data(), sizeof d.tile->box);
            for (const WorldPart& p : d.tile->parts) {
                const std::array<std::uint32_t, 4> counts{p.triangles, p.segments, p.coast, p.lights};
                mix(draws, counts.data(), sizeof counts);
                const Heaps::Writer w = heaps_->writer(p.range);
                mix_words(words, w.bytes());
            }
        }
        mix(placed, &frame_.frame, sizeof frame_.frame);
        const WorldBend& b = frame_.bend;
        const std::array<double, 12> bend{b.drop,  b.sphere, b.target.x, b.target.y, b.target.z, b.eye.x,
                                          b.eye.y, b.eye.z,  b.lat,      b.lon,      b.sin_lat,  b.cos_lat};
        mix(placed, bend.data(), sizeof bend);
        frame_.sum_draws = draws;
        frame_.sum_words = words;
        frame_.sum_view = placed;
    }
    if (drawing_ && frame_.draws.empty() && frame_.wanted == 0) drawing_ = false;
}

} // namespace atlas::scene
