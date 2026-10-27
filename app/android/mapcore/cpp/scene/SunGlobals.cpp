// The sun's bits in set 1 (scene/SunGlobals.hpp).
#include "scene/SunGlobals.hpp"

#include "base/arena.hpp"
#include "base/log.hpp"
#include "base/pymath/PyMath.hpp"
#include "base/trace.hpp"
#include "frame/FrameBuilder.hpp"
#include "tiles/Error.hpp"

#include <algorithm>
#include <cstddef>
#include <cstring>

#if defined(__ANDROID__)
#include <sys/resource.h>
#endif

namespace atlas::scene {

namespace {

/// The first texels per half of the buffer (8 bytes each): grown by doubling.
constexpr std::size_t first_capacity = 16'384;

/// Bytes of a texel (R32G32_UINT).
constexpr std::size_t texel_bytes = 8;

[[nodiscard]] double ms_since(std::chrono::steady_clock::time_point t) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
}

} // namespace

gpu::Result<std::unique_ptr<SunGlobals>> SunGlobals::create(const gpu::Device& device, const tiles::Store& store,
                                                            const Residency& residency, SunOptions options,
                                                            std::function<double()> clock) {
    std::unique_ptr<SunGlobals> s( // NOLINT(cppcoreguidelines-owning-memory): the constructor is private
        new SunGlobals(device, store, residency, std::move(options), std::move(clock)));
    return s;
}

SunGlobals::SunGlobals(const gpu::Device& device, const tiles::Store& store, const Residency& residency,
                       SunOptions options, std::function<double()> clock)
    : device_(device), store_(store), residency_(residency), options_(std::move(options)), clock_(std::move(clock)) {
    if (!options_.cache_dir.empty()) {
        cache_ = std::make_unique<sun::DiskCache>(options_.cache_dir, options_.cache_budget);
    }
    // The active pack's lane on the sun's threads; the coarser pack's and the neighbour's, less urgent, on half as
    // many.
    const unsigned threads = std::max(1U, options_.threads);
    lanes_[0].worker = std::make_unique<sun::Worker>(lanes_[0].bits, threads, cache_.get(), [this] { return now(); });
    for (std::size_t i = 1; i < lanes_.size(); ++i) {
        lanes_[i].worker = std::make_unique<sun::Worker>(lanes_[i].bits, std::max(1U, threads / 2), cache_.get(),
                                                         [this] { return now(); });
    }
    thread_ = std::jthread([this](const std::stop_token& stop) { work(stop); });
}

SunGlobals::~SunGlobals() { stop(); }

void SunGlobals::stop() {
    thread_ = {};                            // the builder stops first: it asks the workers
    for (Lane& l : lanes_) l.worker.reset(); // then the workers, which publish into the bits
}

double SunGlobals::now() const {
    if (clock_) return clock_();
    return std::chrono::duration<double>(std::chrono::steady_clock::now() - started_).count();
}

void SunGlobals::prepare(const frame::FrameState& state, const std::shared_ptr<const tiles::StorePack>& pack,
                         std::uint8_t level, Cover& cover, const std::shared_ptr<const tiles::StorePack>& coarser,
                         std::uint8_t coarser_level, const std::shared_ptr<const tiles::StorePack>& neighbour,
                         std::uint8_t neighbour_level) {
    // Old buffers go once the frames that read them have finished.
    std::erase_if(retired_, [&](const auto& r) { return state.number >= r.second + gpu::Frames::in_flight; });
    texels_.clear();
    fading_ = false;
    const std::span<CoverTile> drawn = cover.tiles();
    for (CoverTile& c : drawn) {
        c.sun.reset();
        c.sun_base = no_sun;
        c.sun_weight = 1.0F;
    }
    if (!state.view) return;
    prepare_lane(lanes_[0], state, pack, level, drawn);
    prepare_lane(lanes_[1], state, coarser, coarser_level, drawn);
    prepare_lane(lanes_[2], state, neighbour, neighbour_level, drawn);

    // Every drawn tile's bits, its own pack's lane's: on the CPU, and its texels in the frame slot's half of the
    // buffer.
    const double t = now();
    std::vector<std::uint32_t> local(drawn.size(), no_sun);
    for (std::size_t i = 0; i < drawn.size(); ++i) {
        CoverTile& c = drawn[i];
        Lane* lane = nullptr;
        for (Lane& l : lanes_) {
            if (!l.pack_id.empty() && c.drawn->pack == l.pack_id && c.drawn->key.z == l.fine) lane = &l;
        }
        if (lane == nullptr) continue; // a stand-in ancestor, or a pack without a lane: not in a scene
        c.sun = lane->bits.view(c.drawn->key, t);
        if (!c.sun) continue;
        const auto base = static_cast<std::uint32_t>(texels_.size() / 2);
        if (const auto w = lane->bits.texels(c.drawn->key, t, texels_)) {
            local[i] = base;
            c.sun_weight = static_cast<float>(*w);
            if (*w < 1.0) fading_ = true;
        }
    }
    const std::size_t needed = texels_.size() / 2;
    last_texels_ = needed;
    if (needed == 0) return;
    if (needed > capacity_) {
        const std::size_t capacity = std::max({needed, 2 * capacity_, first_capacity});
        auto made = device_.memory().buffer(static_cast<vk::DeviceSize>(capacity) * 2 * texel_bytes,
                                            vk::BufferUsageFlagBits::eStorageBuffer, gpu::MemoryUse::upload);
        if (!made) {
            log::warn("vk sun: no buffer for {} texels: {}", capacity, made.error().text());
            for (CoverTile& c : drawn) c.sun_base = no_sun;
            return;
        }
        if (buffer_) retired_.emplace_back(std::move(buffer_), state.number);
        buffer_ = std::move(*made);
        capacity_ = capacity;
    }
    const std::size_t half = (state.slot % 2) * capacity_;
    for (std::size_t i = 0; i < drawn.size(); ++i) {
        if (local[i] != no_sun) drawn[i].sun_base = static_cast<std::uint32_t>(half + local[i]);
    }
    const std::span<std::byte> bytes = buffer_.bytes();
    std::memcpy(bytes.data() + (half * texel_bytes), texels_.data(), texels_.size() * sizeof(std::uint32_t));
    buffer_.flush();
}

void SunGlobals::prepare_lane(Lane& lane, const frame::FrameState& state,
                              const std::shared_ptr<const tiles::StorePack>& pack, std::uint8_t level,
                              std::span<const CoverTile> drawn) {
    const bool usable =
        pack && pack->pack.is_render() && pack->pack.head() != nullptr && !pack->pack.head()->levels.empty();
    const std::string id = usable ? pack->id : std::string();
    if (id != lane.pack_id) {
        // Another pack, or none: its own tiles from now on.
        for (const auto& t : lane.asked) lane.bits.forget(tiles::TileKey::unpack(t.first));
        lane.asked.clear();
        lane.wanted.clear();
        lane.have_bucket = false;
        lane.pack_id = id;
    }
    if (!usable || !state.view) return;
    lane.fine = pack->pack.head()->levels[0];

    // The scene: the resident tiles of the finest zoom at their own levels, their halo at the cover's.
    std::vector<std::uint64_t> wanted;
    std::vector<std::pair<std::uint64_t, std::uint8_t>> levels;
    for (const std::shared_ptr<const ResidentTile>& r : residency_.resident()) {
        if (r->pack != pack->id || r->key.z != lane.fine) continue;
        wanted.push_back(r->key.packed());
        levels.emplace_back(r->key.packed(), r->level);
    }
    std::ranges::sort(wanted);
    std::ranges::sort(levels);
    const sun::Bucket bucket = sun::bucket_of(state.theme);
    bool scene_changed = false;
    if (wanted != lane.wanted || level != lane.level) {
        std::vector<std::pair<std::uint64_t, std::uint8_t>> asked;
        for (const tiles::TileKey& k : residency_.sun_tiles(pack->id)) {
            const auto it =
                std::ranges::lower_bound(levels, k.packed(), {}, &std::pair<std::uint64_t, std::uint8_t>::first);
            const bool resident = it != levels.end() && it->first == k.packed();
            asked.emplace_back(k.packed(), resident ? it->second : level);
        }
        std::ranges::sort(asked);
        // A tile that left the scene, or is made at another level, loses the bits of its old samples.
        for (const auto& old : lane.asked) {
            const auto it =
                std::ranges::lower_bound(asked, old.first, {}, &std::pair<std::uint64_t, std::uint8_t>::first);
            if (it == asked.end() || it->first != old.first || it->second != old.second) {
                lane.bits.forget(tiles::TileKey::unpack(old.first));
            }
        }
        scene_changed = asked != lane.asked;
        lane.asked = std::move(asked);
        lane.wanted = std::move(wanted);
        lane.level = level;
    }
    if (!lane.asked.empty() && (scene_changed || !lane.have_bucket || bucket.key != lane.bucket)) {
        Build b;
        b.pack = pack;
        for (const auto& [key, lv] : lane.asked) b.tiles.emplace_back(tiles::TileKey::unpack(key), lv);
        for (const std::uint64_t key : lane.wanted) b.wanted.push_back(tiles::TileKey::unpack(key));
        for (const CoverTile& c : drawn) {
            if (c.drawn->pack == pack->id) b.visible.push_back(c.drawn->key);
        }
        b.bucket = bucket;
        const view::Vec3& eye = state.view->camera().eye();
        b.cx = eye.x;
        b.cz = eye.z;
        const double win_d =
            state.placed ? state.placed->number("win_d").value_or(view::default_win_d) : view::default_win_d;
        b.window_reach = win_d * 1.35;
        {
            const std::scoped_lock lock(mutex_);
            lane.next = std::move(b);
        }
        wake_.notify_one();
        lane.bucket = bucket.key;
        lane.have_bucket = true;
    }
}

void SunGlobals::update(const frame::FrameState& state, frame::Globals& globals) {
    (void)state;
    // Every frame (an unchanged binding costs a comparison): each frame builder drawing the world gets the buffer.
    if (buffer_) globals.buffer(frame::Global::sun_bits, buffer_.handle());
}

void SunGlobals::work(const std::stop_token& stop) {
#if defined(__ANDROID__)
    // Background priority (ENGINE.md gain 8): made on the render thread, this thread would keep its display priority.
    (void)setpriority(PRIO_PROCESS, 0, 10);
#endif
    for (;;) {
        Lane* lane = nullptr;
        Build b;
        {
            std::unique_lock lock(mutex_);
            const auto asked = [this] {
                return std::ranges::any_of(lanes_, [](const Lane& l) { return l.next.has_value(); });
            };
            if (!wake_.wait(lock, stop, asked)) return;
            // The active pack's lane first.
            for (Lane& l : lanes_) {
                if (lane == nullptr && l.next) lane = &l;
            }
            if (lane == nullptr || !lane->next) continue;
            b = std::move(*lane->next);
            lane->next.reset();
            lane->building = true;
        }
        std::vector<std::pair<std::uint64_t, std::uint8_t>> tiles;
        tiles.reserve(b.tiles.size());
        for (const auto& [key, level] : b.tiles) tiles.emplace_back(key.packed(), level);
        std::shared_ptr<const sun::Scene> scene;
        {
            const std::scoped_lock lock(mutex_);
            scene = lane->scene;
        }
        if (!scene || b.pack->id != lane->built_pack || tiles != lane->built) {
            const auto started = std::chrono::steady_clock::now();
            {
                const trace::Section traced("atlas sun scene");
                scene = build(b);
            }
            const double ms = ms_since(started);
            const std::scoped_lock lock(mutex_);
            if (scene) {
                ++stats_.scenes;
                if (lane == lanes_.data()) {
                    stats_.scene_ms = ms;
                    stats_.tiles = scene->tiles().size();
                    stats_.resident = b.wanted.size();
                }
            } else {
                ++stats_.failed;
            }
            lane->scene = scene;
            lane->built = std::move(tiles);
            lane->built_pack = b.pack->id;
        }
        if (scene) {
            const trace::Section traced("atlas sun test");
            ask(*lane, scene, b);
        }
        {
            const std::scoped_lock lock(mutex_);
            lane->building = false;
        }
        idle_.notify_all();
    }
}

std::optional<double> SunGlobals::maxh(const std::shared_ptr<const tiles::StorePack>& pack, std::uint8_t level) {
    // The same pack by ownership, never by its identity (the APK's packs have none) or address (a pack let go of and
    // another opened where it was are two).
    const auto same = [&pack](const Tallest& t) { return !t.pack.owner_before(pack) && !pack.owner_before(t.pack); };
    {
        const std::scoped_lock lock(mutex_);
        std::erase_if(maxh_, [](const Tallest& t) { return t.pack.expired(); });
        for (const Tallest& t : maxh_) {
            if (t.level == level && same(t)) return t.h;
        }
    }
    const auto h = sun::scene_maxh(pack->pack, level);
    if (!h) {
        log::warn("vk sun: the tallest solid of {} at level {}: {}", pack->id, level, tiles::describe(h.error()));
        return std::nullopt;
    }
    const std::scoped_lock lock(mutex_);
    maxh_.push_back({.pack = pack, .level = level, .h = *h});
    return *h;
}

std::shared_ptr<const sun::Scene> SunGlobals::build(const Build& b) {
    const tiles::Pack& p = b.pack->pack;
    const tiles::RenderHead* head = p.head();
    const tiles::Style* style = p.style();
    if (head == nullptr || style == nullptr) return nullptr;
    const sun::Region region = sun::region_of(*style);
    tiles::Zstd zstd;
    tiles::TileDecoder decoder;
    Arena arena(std::size_t{4} << 20U, Growth::grows, "the sun's scene");
    std::vector<std::byte> raw;
    // Every bridge of the scene's tiles first (the quays' decks), then each tile's samples and solids.
    std::vector<sun::Deck> decks;
    auto decode = [&](tiles::TileKey key) -> std::optional<tiles::Tile> {
        const tiles::ChunkRef* chunk = p.chunks().find(key);
        if (chunk == nullptr) return std::nullopt;
        arena.reset();
        auto tile = store_.decode({.pack = b.pack, .chunk = chunk}, zstd, decoder, arena, raw);
        if (!tile) {
            log::warn("vk sun: {}/{}/{} of {}: {}", key.z, key.x, key.y, b.pack->id, tiles::describe(tile.error()));
            return std::nullopt;
        }
        return *tile;
    };
    for (const auto& [key, level] : b.tiles) {
        if (const auto tile = decode(key)) sun::collect_decks(*tile, *head, level, decks);
    }
    std::vector<sun::SunTile> made;
    std::vector<tiles::Sha256Digest> chunks;
    double tallest = 0.0;
    std::vector<std::uint8_t> levels;
    for (const auto& [key, level] : b.tiles) {
        const auto tile = decode(key);
        if (!tile) return nullptr;
        made.push_back(sun::make_tile(*tile, *head, region, level, decks));
        chunks.push_back(p.chunks().find(key)->sha256);
        if (std::ranges::find(levels, level) == levels.end()) levels.push_back(level);
    }
    // The march ends at the scene's tallest solid, as the port's Index takes it: of every level the scene holds.
    for (const std::uint8_t level : levels) {
        const auto h = maxh(b.pack, level);
        if (!h) return nullptr;
        tallest = pymath::max(tallest, *h);
    }
    return std::make_shared<const sun::Scene>(std::move(made), tallest, std::move(chunks));
}

void SunGlobals::ask(Lane& lane, const std::shared_ptr<const sun::Scene>& scene, const Build& b) {
    const tiles::RenderHead& head = *b.pack->pack.head();
    std::vector<std::uint8_t> visible(scene->tiles().size(), 0);
    for (const tiles::TileKey& k : b.visible) {
        if (const auto i = scene->find(k)) visible[*i] = 1;
    }
    std::vector<sun::TileJob> jobs = sun::nearest_first(*scene, head.frame, b.cx, b.cz, visible, b.window_reach);
    const double t = now();
    // Only the resident tiles, and only those whose bits for the bucket are not there yet (windows included where
    // asked).
    std::erase_if(jobs, [&](const sun::TileJob& j) {
        const tiles::TileKey key = scene->tiles()[j.tile].key;
        if (std::ranges::find(b.wanted, key) == b.wanted.end()) return true;
        const auto v = lane.bits.view(key, t);
        return v && v->now->bucket == b.bucket.key && (!j.windows || v->now->windows);
    });
    if (!jobs.empty()) lane.worker->request(scene, b.bucket, std::move(jobs));
}

void SunGlobals::wait_idle() {
    {
        std::unique_lock lock(mutex_);
        idle_.wait(lock, [this] {
            return std::ranges::none_of(lanes_, [](const Lane& l) { return l.next.has_value() || l.building; });
        });
    }
    for (Lane& l : lanes_) l.worker->wait_idle();
}

SunStats SunGlobals::stats() const {
    SunStats s;
    {
        const std::scoped_lock lock(mutex_);
        s = stats_;
    }
    for (const Lane& l : lanes_) {
        s.runs += l.worker->runs();
        s.cached += l.worker->cached();
    }
    s.texels = last_texels_;
    return s;
}

} // namespace atlas::scene
