// The terrain's relief tiles (scene/Relief.hpp).
#include "scene/Relief.hpp"

#include "base/log.hpp"
#include "base/pymath/PyMath.hpp"
#include "base/trace.hpp"
#include "tiles/Heights.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <numbers>
#include <set>
#include <sys/resource.h>
#include <utility>

namespace atlas::scene {

namespace {

namespace tl = atlas::tiles;

[[nodiscard]] double ms_since(std::chrono::steady_clock::time_point t) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
}

/// The longitude of the pyramid's column edge `x` at level `z`, and the latitude of its row edge `y` (Web Mercator's),
/// degrees.
[[nodiscard]] double longitude_of(std::uint8_t z, std::uint32_t x) noexcept {
    return ((static_cast<double>(x) / std::ldexp(1.0, z)) * 360.0) - 180.0;
}
[[nodiscard]] double latitude_of(std::uint8_t z, std::uint32_t y) noexcept {
    const double t = std::numbers::pi * (1.0 - ((2.0 * static_cast<double>(y)) / std::ldexp(1.0, z)));
    return pymath::atan(pymath::sinh(t)) * (180.0 / std::numbers::pi);
}

/// A tile's box on the ground of `frame`, at street level.
[[nodiscard]] Box box_of(const tl::TileKey& k, const view::GroundFrame& frame) noexcept {
    const double north = latitude_of(k.z, k.y);
    const double south = latitude_of(k.z, k.y + 1);
    const double west = longitude_of(k.z, k.x);
    const double east = longitude_of(k.z, k.x + 1);
    return {.x0 = frame.x(north, west),
            .x1 = frame.x(north, east),
            .y0 = 0.0,
            .y1 = 0.0,
            .z0 = frame.z(north, west),
            .z1 = frame.z(south, west)};
}

/// `k`'s ancestor at level `z` (at most its own).
[[nodiscard]] tl::TileKey ancestor(const tl::TileKey& k, std::uint8_t z) noexcept {
    const auto gap = static_cast<std::uint32_t>(k.z - z);
    return {z, k.x >> gap, k.y >> gap};
}

/// The part of raster `tile` over `leaf` (the raster's own tile, or a descendant of it).
[[nodiscard]] ReliefPart part_of(const ReliefTile& tile, const tl::TileKey& leaf) noexcept {
    const auto gap = static_cast<std::uint32_t>(leaf.z - tile.key.z);
    const auto side = static_cast<float>(std::uint32_t{1} << gap);
    return {.tile = &tile,
            .x = static_cast<float>(leaf.x - (tile.key.x << gap)) / side,
            .y = static_cast<float>(leaf.y - (tile.key.y << gap)) / side,
            .extent = 1.0F / side};
}

} // namespace

ReliefTiles::ReliefTiles(const tiles::Store& store)
    : store_(store), worker_([this](const std::stop_token& stop) { run(stop); }) {}

ReliefTiles::~ReliefTiles() {
    {
        const std::scoped_lock lock(mutex_);
        worker_.request_stop();
    }
    wake_worker_.notify_all();
    if (worker_.joinable()) worker_.join();
}

void ReliefTiles::set_wake(std::function<void()> wake) {
    const std::scoped_lock lock(mutex_);
    wake_ = std::move(wake);
}

std::vector<WorldWant> ReliefTiles::wants() const {
    const std::scoped_lock lock(mutex_);
    return wants_;
}

std::vector<tl::TileKey> ReliefTiles::blocks() const {
    const std::scoped_lock lock(mutex_);
    return blocks_;
}

std::string ReliefTiles::sources() const {
    const std::scoped_lock lock(mutex_);
    return sources_;
}

ReliefStats ReliefTiles::stats() const {
    const std::scoped_lock lock(mutex_);
    ReliefStats s = stats_;
    s.resident = resident_.size();
    return s;
}

void ReliefTiles::wait_idle() {
    std::unique_lock lock(mutex_);
    idle_cv_.wait(lock, [this] { return queue_.empty() && building_ == ~std::uint64_t{0}; });
}

const ReliefTile* ReliefTiles::resident(const tl::TileKey& key) const noexcept {
    const auto it = resident_.find(key.packed());
    return it != resident_.end() ? it->second.get() : nullptr;
}

const ReliefTiles::Hit& ReliefTiles::hit_of(const tl::TileKey& key) {
    const auto found = hits_.find(key.packed());
    if (found != hits_.end()) return found->second;
    Hit hit;
    if (std::optional<tl::StoreHit> h = store_.heights(key); h && h->chunk != nullptr) {
        hit = {.pack = std::move(h->pack), .chunk = h->chunk};
    }
    return hits_.emplace(key.packed(), std::move(hit)).first->second;
}

bool ReliefTiles::take_packs() {
    std::vector<std::shared_ptr<const tl::StorePack>> open = store_.terrain_packs();
    if (open == packs_) return false;
    packs_ = std::move(open);
    hits_.clear();
    std::erase_if(resident_,
                  [this](const auto& r) { return std::ranges::find(packs_, r.second->pack) == packs_.end(); });
    const std::scoped_lock lock(mutex_);
    absent_.clear();
    failed_.clear();
    return true;
}

void ReliefTiles::rest() {
    frame_.draws.clear();
    frame_.wanted = 0;
    frame_.left = 0;
    const std::scoped_lock lock(mutex_);
    queue_.clear();
    wants_.clear();
    blocks_.clear();
    sources_.clear();
}

void ReliefTiles::update(const view::View& view, const view::GroundFrame& frame, const Reaches& reaches,
                         std::uint64_t number) {
    take_packs();
    if (hits_.size() > 8192) hits_.clear(); // the answers of places long left
    // what the worker baked since the last frame
    {
        std::vector<std::unique_ptr<ReliefTile>> built;
        {
            const std::scoped_lock lock(mutex_);
            built.swap(built_);
        }
        for (std::unique_ptr<ReliefTile>& t : built) {
            if (std::ranges::find(packs_, t->pack) == packs_.end()) continue; // its pack went meanwhile
            t->serial = ++serial_;
            t->drawn = number;
            resident_.insert_or_assign(t->key.packed(), std::move(t));
        }
    }
    frame_.frame = frame;
    frame_.far = reaches.far;
    frame_.draws.clear();
    frame_.wanted = 0;
    frame_.left = 0;

    // the walk: every tile the frustum sees, to the level its distance asks for
    struct Leaf {
        tl::TileKey key;
        Box box;
        double distance = 0;
    };
    std::vector<Leaf> leaves;
    {
        const Frustum frustum(view.camera(), reaches.margin_px, reaches.far, view.drop());
        std::vector<tl::TileKey> stack{tl::TileKey(0, 0, 0)};
        while (!stack.empty()) {
            const tl::TileKey k = stack.back();
            stack.pop_back();
            const Box box = box_of(k, frame);
            if (!frustum.sees(box)) continue;
            const double d = frustum.distance(box);
            bool split = k.z < relief_coarsest;
            if (!split && k.z < relief_finest && d < (k.z == relief_coarsest ? reaches.coarse : reaches.fine)) {
                // no finer than the packs hold rasters there: a child whose raster is this tile's own, or a coarser
                // one's, is lit by it as well as this tile is
                for (std::uint32_t c = 0; c < 4 && !split; ++c) {
                    const Hit& h =
                        hit_of({static_cast<std::uint8_t>(k.z + 1), (k.x * 2) + (c & 1U), (k.y * 2) + (c >> 1U)});
                    split = h.chunk != nullptr && h.chunk->tile.z > k.z;
                }
            }
            if (split) {
                const auto z = static_cast<std::uint8_t>(k.z + 1);
                for (std::uint32_t c = 4; c-- > 0;) stack.emplace_back(z, (k.x * 2) + (c & 1U), (k.y * 2) + (c >> 1U));
                continue;
            }
            leaves.push_back({.key = k, .box = box, .distance = d});
        }
    }
    std::ranges::stable_sort(leaves, {}, &Leaf::distance);

    std::vector<tl::TileKey> blocks;
    std::vector<tl::TileKey> wanted; // the rasters not baked, nearest first
    std::set<std::uint64_t> taken;   // the rasters the frame takes, baked or wanted
    std::set<std::string> sources;
    for (const Leaf& leaf : leaves) {
        const tl::TileKey block = ancestor(leaf.key, relief_block);
        if (std::ranges::find(blocks, block) == blocks.end()) blocks.push_back(block);
        const Hit& hit = hit_of(leaf.key);
        if (hit.chunk == nullptr) continue; // no heights there: flat
        const tl::TileKey raster = hit.chunk->tile;
        if (raster.z > leaf.key.z || ancestor(leaf.key, raster.z) != raster) continue; // not the store's promise
        if (!taken.contains(raster.packed()) && taken.size() >= max_tiles) {
            ++frame_.left;
            continue;
        }
        taken.insert(raster.packed());
        ReliefDraw draw{.key = leaf.key,
                        .corner = {leaf.box.x0, 0.0, leaf.box.z0},
                        .east = leaf.box.x1 - leaf.box.x0,
                        .south = leaf.box.z1 - leaf.box.z0,
                        .distance = leaf.distance};
        const ReliefTile* own = resident(raster);
        if (own != nullptr) {
            draw.part = part_of(*own, leaf.key);
            draw.own = true;
        } else if (std::ranges::find(wanted, raster) == wanted.end()) {
            wanted.push_back(raster);
        }
        // the nearest coarser raster baked: the tile's light until its own is baked, and under it until it is drawn
        for (std::uint8_t z = raster.z; z-- > relief_coarsest;) {
            const ReliefTile* coarser = resident(ancestor(leaf.key, z));
            if (coarser == nullptr) continue;
            (own != nullptr ? draw.under : draw.part) = part_of(*coarser, leaf.key);
            break;
        }
        if (draw.part.tile == nullptr) continue;
        for (const ReliefPart* p : {&draw.part, &draw.under}) {
            if (p->tile == nullptr) continue;
            resident_.at(p->tile->key.packed())->drawn = number;
            for (const std::string& s : p->tile->sources) sources.insert(s);
        }
        frame_.draws.push_back(draw);
    }
    frame_.wanted = wanted.size();
    // room for what comes: the least lately drawn go, but what this frame draws
    while (resident_.size() > max_tiles) {
        auto oldest = resident_.end();
        for (auto it = resident_.begin(); it != resident_.end(); ++it) {
            if (it->second->drawn == number) continue;
            if (oldest == resident_.end() || it->second->drawn < oldest->second->drawn) oldest = it;
        }
        if (oldest == resident_.end()) break;
        resident_.erase(oldest);
    }

    std::string named;
    for (const std::string& s : sources) {
        if (!named.empty()) named += ',';
        named += s;
    }
    const std::shared_ptr<tl::ExpandedCache> cache = store_.cache();
    const std::uint64_t writes = cache != nullptr ? cache->writes() : 0;
    {
        const std::scoped_lock lock(mutex_);
        // a chunk kept since a raster was found absent: it is tried again
        std::erase_if(absent_, [writes](const auto& a) { return a.second.writes != writes; });
        queue_.clear();
        wants_.clear();
        for (const tl::TileKey& k : wanted) {
            if (k.packed() == building_ || std::ranges::find(failed_, k.packed()) != failed_.end()) continue;
            if (const auto a = absent_.find(k.packed()); a != absent_.end()) {
                wants_.push_back(a->second.want);
                continue;
            }
            queue_.push_back(k);
        }
        blocks_ = std::move(blocks);
        sources_ = std::move(named);
    }
    wake_worker_.notify_one();
}

void ReliefTiles::run(const std::stop_token& stop) {
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
        // the cache's writes as the bake begins: a chunk kept meanwhile makes the raster's absence stale at once
        const std::shared_ptr<tl::ExpandedCache> cache = store_.cache();
        const std::uint64_t writes = cache != nullptr ? cache->writes() : 0;
        std::optional<WorldWant> absent;
        std::unique_ptr<ReliefTile> tile;
        {
            const trace::Section traced("atlas relief tile");
            tile = build(key, scratch, absent);
        }
        std::function<void()> wake;
        {
            const std::scoped_lock lock(mutex_);
            if (tile) {
                ++stats_.baked;
                stats_.bake_ms += ms_since(started);
                built_.push_back(std::move(tile));
            } else if (absent) {
                absent_.insert_or_assign(key.packed(), Absent{.want = std::move(*absent), .writes = writes});
            } else {
                ++stats_.failed;
                failed_.push_back(key.packed());
            }
            building_ = ~std::uint64_t{0};
            wake = wake_;
        }
        idle_cv_.notify_all();
        if (wake) wake();
    }
}

std::unique_ptr<ReliefTile> ReliefTiles::build(const tl::TileKey& key, Scratch& scratch,
                                               std::optional<WorldWant>& absent) const {
    const std::optional<tl::StoreHit> hit = store_.heights(key);
    if (!hit || hit->chunk == nullptr || hit->chunk->tile != key) return nullptr;
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
        log::warn("vk relief: tile {}/{}/{}: {}", key.z, key.x, key.y, tl::describe(tile.error()));
        return nullptr;
    }
    const auto heights = tl::read_heights(*tile, scratch.arena);
    if (!heights || heights->tile != key) {
        log::warn("vk relief: tile {}/{}/{}: {}", key.z, key.x, key.y,
                  heights ? std::string("another tile's raster") : tl::describe(heights.error()));
        return nullptr;
    }
    auto out = std::make_unique<ReliefTile>();
    out->key = key;
    out->pack = hit->pack;
    out->bake = fx::bake_relief(*heights, fx::relief_style());
    for (const tl::HeightSource& s : heights->sources) out->sources.emplace_back(s.id);
    if (out->bake.texels.empty()) return nullptr;
    return out;
}

} // namespace atlas::scene
