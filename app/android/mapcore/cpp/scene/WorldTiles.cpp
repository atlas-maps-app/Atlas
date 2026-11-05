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
#include <charconv>
#include <chrono>
#include <cmath>
#include <format>
#include <map>
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
constexpr double earth_radius = 6'371'000.0;
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
        : south_(header.south), west_(header.west), north_(header.north), east_(header.east), style_(style) {}

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
        for (std::size_t t = 0; t + 2 < indices_.size(); t += 3) {
            for (std::size_t k = 0; k < 3; ++k) {
                const std::uint32_t v = indices_[t + k];
                vertices_.push_back(quantised(ring.lat[v], ring.lon[v]));
            }
            triangles_.push_back(std::uint32_t{palette} | (cell << 8U));
        }
    }

    /// A line of the tile, `width` metres wide, in `palette`, in cell `cell`.
    void line(const tl::RingView& line, double width, std::uint8_t palette, std::uint32_t cell) {
        const std::size_t n = line.size();
        if (n < 2) return;
        const auto dm = static_cast<std::uint32_t>(std::clamp(std::lround(width * 10.0), 1L, 65'535L));
        const std::uint32_t look = std::uint32_t{palette} | (cell << 8U) | (dm << 16U);
        for (std::size_t i = 0; i + 1 < n; ++i) {
            segments_.push_back(quantised(line.lat[i], line.lon[i]));
            segments_.push_back(quantised(line.lat[i + 1], line.lon[i + 1]));
            segments_.push_back(look);
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
    std::vector<std::vector<std::array<double, 2>>> polygon_;
    std::vector<std::uint32_t> indices_;
    std::vector<std::uint32_t> vertices_;
    std::vector<std::uint32_t> triangles_;
    std::vector<std::uint32_t> segments_;
};

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

double world_far(double distance, double height) noexcept {
    const double k = distance / world_from;
    const double h = std::max(height, 0.0);
    return std::min(far_at_from * k * k, std::sqrt((2.0 * earth_radius * h) + (h * h)));
}

std::vector<WorldLeaf> walk_world(const Frustum& frustum, const view::GroundFrame& frame,
                                  const std::function<bool(const tl::TileKey&)>& held, double reach) {
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
        const Box box = box_of(grid_of(n.key), 0, frame);
        if (!frustum.sees(box)) continue;
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
    style_pack_.reset();
    for (const std::shared_ptr<const tl::StorePack>& p : open) {
        if (is_world(*p) && p->pack.style() != nullptr) {
            style_pack_ = p;
            break;
        }
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

void WorldTiles::update(const view::Camera& camera, const view::GroundFrame& frame, double distance,
                        std::uint64_t number, double seconds, double reach) {
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
    if (distance > world_from) {
        drawing_ = true;
        const double far = world_far(distance, camera.eye().y);
        const Frustum frustum(camera, 40.0, far);
        std::unordered_map<std::uint64_t, bool> held_cache;
        const auto held = [&](const tl::TileKey& key) {
            const auto [it, fresh] = held_cache.try_emplace(key.packed(), false);
            if (fresh) it->second = world_hit(store_, key).has_value();
            return it->second;
        };
        const std::vector<WorldLeaf> leaves = walk_world(frustum, frame, held, reach);
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
