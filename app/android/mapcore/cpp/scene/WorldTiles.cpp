// The world's tiles beyond the countries' (scene/WorldTiles.hpp).
#include "scene/WorldTiles.hpp"

#include "base/arena.hpp"
#include "base/log.hpp"
#include "base/trace.hpp"
#include "gpu/Frames.hpp"
#include "tiles/Grid.hpp"
#include "tiles/layers/Ground.hpp"
#include "tiles/layers/Lines.hpp"
#include "tiles/layers/Water.hpp"

#include <mapbox/earcut.hpp>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <format>
#include <map>
#include <numbers>
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

    /// The colour of a land fill of `kind`: its LAND kind's, a lawn's, or the land's where the style has none.
    [[nodiscard]] std::uint8_t land_palette(std::string_view kind) const noexcept {
        if (style_ == nullptr) return world_palette::land;
        const std::optional<std::uint16_t> k = style_->land_kind(kind);
        if (!k || *k + std::uint32_t{world_palette::kinds} >= world_palette::size) return world_palette::land;
        return static_cast<std::uint8_t>(world_palette::kinds + *k);
    }

    [[nodiscard]] WorldGeometry finish() {
        WorldGeometry g;
        g.triangles = static_cast<std::uint32_t>(triangles_.size());
        g.segments = static_cast<std::uint32_t>(segments_.size() / 3);
        g.words.reserve(vertices_.size() + triangles_.size() + segments_.size());
        g.words.insert(g.words.end(), vertices_.begin(), vertices_.end());
        g.words.insert(g.words.end(), triangles_.begin(), triangles_.end());
        g.words.insert(g.words.end(), segments_.begin(), segments_.end());
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
                          std::cos(lat * (std::numbers::pi / 180.0));
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
    // the areas over the water (the globe's ice shelves: drawn as the land until the style has their colour)
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
    return w.finish();
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
        const WorldGeometry g = world_cap_geometry(c == 0 ? world_palette::water : world_palette::south_cap);
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

void WorldTiles::take_style() {
    const std::vector<std::shared_ptr<const tl::StorePack>> open = store_.packs();
    if (open == seen_packs_) return;
    seen_packs_ = open;
    style_pack_.reset();
    for (const std::shared_ptr<const tl::StorePack>& p : open) {
        if (is_world(*p) && p->pack.style() != nullptr) {
            style_pack_ = p;
            break;
        }
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
        bool no_room = false;
        std::unique_ptr<WorldTile> tile;
        {
            const trace::Section traced("atlas world tile");
            tile = build(key, scratch, no_room);
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

std::unique_ptr<WorldTile> WorldTiles::build(const tl::TileKey& key, Scratch& scratch, bool& no_room) {
    const std::optional<tl::StoreHit> hit = world_hit(store_, key);
    if (!hit) return nullptr;
    scratch.arena.reset();
    const auto tile = store_.decode(*hit, scratch.zstd, scratch.decoder, scratch.arena, scratch.raw);
    if (!tile) {
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
    out->box = {tile->header.south, tile->header.west, tile->header.north, tile->header.east};
    out->triangles = g.triangles;
    out->segments = g.segments;
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
                        double seconds, double reach) {
    const view::Camera& camera = view.camera();
    heaps_->bound(number);
    heaps_->collect(number >= gpu::Frames::in_flight ? number - gpu::Frames::in_flight : 0);
    take_style();
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
    }
    drawing_ = false;
    frame_.draws.clear();
    frame_.wanted = 0;
    frame_.fading = false;
    frame_.bare = 0;
    frame_.bend = world_bend(view, frame);
    if (style_pack_ == nullptr && std::ranges::none_of(seen_packs_, [](const auto& p) { return is_world(*p); })) return;
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
    if (distance > world_from) {
        drawing_ = true;
        const WorldBend& bend = frame_.bend;
        const double far = world_far(distance, eye_height(bend));
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
        std::map<std::uint64_t, std::uint64_t> cells; // by packed key: coarse to fine, then by column and row
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
        // nearest coarser) drawn under it, so where tiles of two levels meet their sub-pixel cracks show its ground
        const bool floored = bend.drop > 0.0;
        for (const WorldLeaf& leaf : leaves) {
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
            // and while the drawer fades in, the nearest resident ancestor under it, down to one faded in or the globe
            for (WorldTile* t = drawer; t != nullptr; t = fade_of(*t) < 1.0 ? ancestor(t->key, t->key.z) : nullptr) {
                const auto gap = static_cast<std::uint32_t>(leaf.key.z - t->key.z);
                cells[t->key.packed()] |= cells_over(world_cells_per_side, gap, leaf.key.x, leaf.key.y);
            }
            if (floored && drawer->key.z > world_floor) {
                if (const WorldTile* f = ancestor(leaf.key, world_floor + 1); f != nullptr) {
                    const auto gap = static_cast<std::uint32_t>(leaf.key.z - f->key.z);
                    cells[f->key.packed()] |= cells_over(world_cells_per_side, gap, leaf.key.x, leaf.key.y);
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
                frame_.draws.push_back({.tile = &t, .cells = bits, .fade = static_cast<float>(fade)});
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
                std::ranges::any_of(queue_, [p](const tl::TileKey& q) { return q.packed() == p; })) {
                continue;
            }
            queue_.push_back(k);
        }
        queued = !queue_.empty();
    }
    if (queued) wake_worker_.notify_one();
    frame_.buffer = heaps_->buffers().low;
    frame_.buffer_bytes = heaps_->capacity();
    frame_.frame = frame;
    frame_.style = style_pack_ ? style_pack_->pack.style() : nullptr;
    if (drawing_ && frame_.draws.empty() && frame_.wanted == 0) drawing_ = false;
}

} // namespace atlas::scene
