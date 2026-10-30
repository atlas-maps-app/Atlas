// The sun's bits in set 1 (scene/SunGlobals.hpp).
#include "scene/SunGlobals.hpp"

#include "base/arena.hpp"
#include "base/log.hpp"
#include "base/pymath/PyMath.hpp"
#include "base/trace.hpp"
#include "frame/FrameBuilder.hpp"
#include "tiles/Error.hpp"

#include <algorithm>
#include <array>
#include <cmath>
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
    // Every lane's worker may run on the sun's threads: the active pack's lane asks for all of them, the others, less
    // urgent, for half as many (ask), so the threads follow the role as it moves between the lanes.
    const unsigned threads = std::max(1U, options_.threads);
    for (Lane& lane : lanes_) {
        lane.worker = std::make_unique<sun::Worker>(lane.bits, threads, cache_.get(), [this] { return now(); });
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
                         std::uint8_t coarser_level,
                         std::span<const std::shared_ptr<const tiles::StorePack>> neighbours,
                         std::span<const std::uint8_t> neighbour_levels) {
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
    // The frame the view's eye is in: the active pack's, which the world draws in.
    const std::optional<view::GroundFrame> eye_frame =
        pack && pack->pack.head() != nullptr ? std::optional(view::frame_of(pack->pack.head()->frame)) : std::nullopt;
    // The packs drawn, each in the lane that holds it already: the neighbours' order changes as the camera moves, and
    // the active pack and a neighbour swap roles as it crosses a frame cell's edge, and neither costs a lane its bits
    // (the role moves, not the bits). A pack new to the frame takes a lane no pack drawn holds, an empty one first.
    struct Want {
        std::shared_ptr<const tiles::StorePack> pack;
        std::uint8_t level = 0;
        LaneOf role = LaneOf::neighbour;
    };
    std::array<Want, 2 + max_neighbours> wants{};
    wants[0] = {.pack = pack, .level = level, .role = LaneOf::active};
    wants[1] = {.pack = coarser, .level = coarser_level, .role = LaneOf::coarser};
    for (std::size_t i = 0; i < max_neighbours && i < neighbours.size(); ++i) {
        wants.at(2 + i) = {.pack = neighbours[i],
                           .level = i < neighbour_levels.size() ? neighbour_levels[i] : std::uint8_t{0},
                           .role = LaneOf::neighbour};
    }
    const auto usable = [](const std::shared_ptr<const tiles::StorePack>& p) {
        return p && p->pack.is_render() && p->pack.head() != nullptr && !p->pack.head()->levels.empty();
    };
    std::array<std::optional<std::size_t>, 2 + max_neighbours> lane_of{}; // per want
    std::array<bool, 2 + max_neighbours> held{};                          // per lane
    for (std::size_t w = 0; w < wants.size(); ++w) {
        if (!usable(wants.at(w).pack)) continue;
        for (std::size_t k = 0; k < lanes_.size(); ++k) {
            if (!held.at(k) && lanes_.at(k).pack_id == wants.at(w).pack->id) {
                lane_of.at(w) = k;
                held.at(k) = true;
                break;
            }
        }
    }
    for (std::size_t w = 0; w < wants.size(); ++w) {
        if (!usable(wants.at(w).pack) || lane_of.at(w)) continue;
        std::optional<std::size_t> spare;
        for (std::size_t k = 0; k < lanes_.size(); ++k) {
            if (held.at(k)) continue;
            if (!spare || (!lanes_.at(*spare).pack_id.empty() && lanes_.at(k).pack_id.empty())) spare = k;
        }
        if (!spare) break;
        lane_of.at(w) = spare;
        held.at(*spare) = true;
    }
    if (lane_of[0]) active_lane_.store(*lane_of[0]);
    // Each lane's pack, role and level this frame, and its resident tiles at the pack's finest zoom (one look over the
    // residency for every lane).
    std::array<LaneInputs, 2 + max_neighbours> in{};
    for (std::size_t w = 0; w < wants.size(); ++w) {
        if (!lane_of.at(w)) continue;
        LaneInputs& li = in.at(*lane_of.at(w));
        li.pack = wants.at(w).pack;
        li.role = wants.at(w).role;
        li.level = wants.at(w).level;
    }
    for (const std::shared_ptr<const ResidentTile>& r : residency_.resident()) {
        for (LaneInputs& li : in) {
            if (!li.pack || r->pack != li.pack->id || r->key.z != li.pack->pack.head()->levels[0]) continue;
            li.wanted.push_back(r->key.packed());
            li.levels.emplace_back(r->key.packed(), r->level);
            break;
        }
    }
    for (LaneInputs& li : in) {
        std::ranges::sort(li.wanted);
        std::ranges::sort(li.levels);
    }
    // The other packs' solids each lane's scene holds, worked out again only where a lane's pack, role, level or
    // resident tiles changed.
    if (in != others_from_) {
        const auto started = std::chrono::steady_clock::now();
        for (std::size_t k = 0; k < lanes_.size(); ++k) lanes_.at(k).others = others_of(k, in);
        others_from_ = in;
        others_ms_ = ms_since(started);
    }
    for (std::size_t k = 0; k < lanes_.size(); ++k) {
        if (in.at(k).pack) lanes_.at(k).role = in.at(k).role;
        prepare_lane(lanes_.at(k), state, in.at(k), drawn, eye_frame);
    }

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

void SunGlobals::prepare_lane(Lane& lane, const frame::FrameState& state, const LaneInputs& in,
                              std::span<const CoverTile> drawn, const std::optional<view::GroundFrame>& eye_frame) {
    const std::shared_ptr<const tiles::StorePack>& pack = in.pack;
    const std::string id = pack ? pack->id : std::string();
    if (id != lane.pack_id) {
        // Another pack, or none: its own tiles from now on.
        for (const auto& t : lane.asked) lane.bits.forget(tiles::TileKey::unpack(t.first));
        lane.asked.clear();
        lane.wanted.clear();
        lane.asked_others.clear();
        lane.have_bucket = false;
        lane.pack_id = id;
    }
    if (!pack || !state.view) return;
    lane.fine = pack->pack.head()->levels[0];

    // The scene: the resident tiles of the finest zoom at their own levels, their halo at the cover's, and the other
    // packs' solids near them.
    const sun::Bucket bucket = sun::bucket_of(state.theme);
    bool scene_changed = false;
    if (in.wanted != lane.wanted || in.level != lane.level) {
        std::vector<std::pair<std::uint64_t, std::uint8_t>> asked;
        for (const tiles::TileKey& k : residency_.sun_tiles(pack->id)) {
            const auto it =
                std::ranges::lower_bound(in.levels, k.packed(), {}, &std::pair<std::uint64_t, std::uint8_t>::first);
            const bool resident = it != in.levels.end() && it->first == k.packed();
            asked.emplace_back(k.packed(), resident ? it->second : in.level);
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
        lane.wanted = in.wanted;
        lane.level = in.level;
    }
    if (lane.others != lane.asked_others) {
        lane.asked_others = lane.others;
        scene_changed = true;
    }
    if (!lane.asked.empty() && (scene_changed || !lane.have_bucket || bucket.key != lane.bucket)) {
        Build b;
        b.pack = pack;
        for (const auto& [key, lv] : lane.asked) b.tiles.emplace_back(tiles::TileKey::unpack(key), lv);
        b.others = lane.asked_others;
        for (const std::uint64_t key : lane.wanted) b.wanted.push_back(tiles::TileKey::unpack(key));
        for (const CoverTile& c : drawn) {
            if (c.drawn->pack == pack->id) b.visible.push_back(c.drawn->key);
        }
        b.bucket = bucket;
        // The eye in the lane's own pack's frame, its scene's metres (a neighbour's frame cell lies kilometres from
        // the active one's): its nearest tiles first, and their windows tested within the windows' reach of it.
        view::Vec3 eye = state.view->camera().eye();
        if (const view::GroundFrame own = view::frame_of(pack->pack.head()->frame); eye_frame && *eye_frame != own) {
            eye = view::convert(*eye_frame, own, eye);
        }
        b.cx = eye.x;
        b.cz = eye.z;
        const double win_d =
            state.placed ? state.placed->number("win_d").value_or(view::default_win_d) : view::default_win_d;
        b.window_reach = win_d * 1.35;
        b.active = lane.role == LaneOf::active;
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
            if (Lane& first = lanes_.at(active_lane_.load()); first.next) lane = &first;
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
        if (!scene || b.pack->id != lane->built_pack || tiles != lane->built || b.others != lane->built_others) {
            const auto started = std::chrono::steady_clock::now();
            {
                const trace::Section traced("atlas sun scene");
                scene = build(b);
            }
            const double ms = ms_since(started);
            const std::scoped_lock lock(mutex_);
            if (scene) {
                ++stats_.scenes;
                if (lane == &lanes_.at(active_lane_.load())) {
                    stats_.scene_ms = ms;
                    stats_.tiles = scene->tiles().size();
                    stats_.resident = b.wanted.size();
                    stats_.others = scene->tiles().size() - b.tiles.size();
                    stats_.solids = 0;
                    stats_.other_solids = 0;
                    for (std::size_t t = 0; t < scene->tiles().size(); ++t) {
                        const std::size_t count = scene->tiles()[t].solids.size();
                        stats_.solids += count;
                        if (t >= b.tiles.size()) stats_.other_solids += count;
                    }
                }
            } else {
                ++stats_.failed;
            }
            lane->scene = scene;
            lane->built = std::move(tiles);
            lane->built_others = b.others;
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
    // An other pack's tile (Other), decoded from its own pack: its points are grid points, which this pack's frame
    // places (a frame cell's edge lies between the two).
    auto decode_other = [&](const Other& o) -> std::optional<tiles::Tile> {
        const tiles::ChunkRef* chunk = o.pack->pack.chunks().find(o.key);
        if (chunk == nullptr) return std::nullopt;
        arena.reset();
        auto tile = store_.decode({.pack = o.pack, .chunk = chunk}, zstd, decoder, arena, raw);
        if (!tile) {
            log::warn("vk sun: {}/{}/{} of {}: {}", o.key.z, o.key.x, o.key.y, o.pack->id,
                      tiles::describe(tile.error()));
            return std::nullopt;
        }
        return *tile;
    };
    for (const auto& [key, level] : b.tiles) {
        if (const auto tile = decode(key)) sun::collect_decks(*tile, *head, level, decks);
    }
    for (const Other& o : b.others) {
        if (const auto tile = decode_other(o)) sun::collect_decks(*tile, head->frame, o.level, decks);
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
    // The other packs' tiles after the pack's own, their solids alone (they want no bits), the march reaching their
    // tallest too. A tile that does not decode is left out (said), the scene made without it.
    std::vector<const Other*> held;
    for (const Other& o : b.others) {
        const auto tile = decode_other(o);
        if (!tile) continue;
        sun::SunTile t;
        t.key = o.key;
        t.level = o.level;
        t.solids = sun::solids_of(*tile, head->frame, o.level).solids;
        for (const double h : t.solids.h) tallest = pymath::max(tallest, h);
        made.push_back(std::move(t));
        chunks.push_back(o.chunk);
        held.push_back(&o);
    }
    // What each of the pack's tiles is made against (sun::Scene::context, the disk cache's key): its chunk, and every
    // other tile of the scene within the march's reach of it with its level, its own pack's or another's, so its bits
    // are made again where a window joins near it or a neighbouring cell's tile comes into the scene.
    struct Near {
        tiles::GridBox reach;
        tiles::Sha256Digest chunk;
        std::uint8_t level = 0;
    };
    std::vector<Near> all;
    all.reserve(made.size());
    for (const auto& [key, level] : b.tiles) {
        const tiles::ChunkRef* c = p.chunks().find(key);
        all.push_back({.reach = c->reach.box, .chunk = c->sha256, .level = level});
    }
    for (const Other* o : held) {
        all.push_back({.reach = o->pack->pack.chunks().find(o->key)->reach.box, .chunk = o->chunk, .level = o->level});
    }
    const auto units = [](double k) { return static_cast<std::int32_t>(std::ceil((sun::reach / k) * 1e7)) + 1; };
    const std::int32_t dlat = units(head->frame.kz);
    const std::int32_t dlon = units(head->frame.kx);
    std::vector<std::pair<tiles::Sha256Digest, std::uint8_t>> near;
    for (std::size_t i = 0; i < b.tiles.size(); ++i) {
        const tiles::GridBox& r = all[i].reach;
        const tiles::Reach around{
            .box = {.south = r.south - dlat, .west = r.west - dlon, .north = r.north + dlat, .east = r.east + dlon}};
        near.clear();
        for (std::size_t j = 0; j < all.size(); ++j) {
            if (j != i && around.meets(all[j].reach)) near.emplace_back(all[j].chunk, all[j].level);
        }
        if (near.empty()) continue;
        std::ranges::sort(near);
        tiles::Sha256 h;
        h.update(all[i].chunk);
        for (const auto& [chunk, level] : near) {
            h.update(chunk);
            const std::array<std::byte, 1> lv{static_cast<std::byte>(level)};
            h.update(lv);
        }
        chunks[i] = h.finish();
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
    // Only the pack's own resident tiles (the other packs' come after them, solids alone), and only those whose bits
    // for the bucket, made against what the scene holds near them, are not there yet (windows included where asked).
    std::erase_if(jobs, [&](const sun::TileJob& j) {
        if (j.tile >= b.tiles.size()) return true;
        const tiles::TileKey key = scene->tiles()[j.tile].key;
        if (std::ranges::find(b.wanted, key) == b.wanted.end()) return true;
        const auto v = lane.bits.view(key, t);
        return v && v->now->bucket == b.bucket.key && (!j.windows || v->now->windows) &&
               v->now->context == scene->context(j.tile);
    });
    // the active pack's on all the sun's threads, the others on half as many
    const unsigned threads = std::max(1U, options_.threads);
    if (!jobs.empty())
        lane.worker->request(scene, b.bucket, std::move(jobs), b.active ? threads : std::max(1U, threads / 2));
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

const sun::BitTiles& SunGlobals::bits(LaneOf lane) const noexcept {
    if (lane == LaneOf::active) return lanes_.at(active_lane_.load()).bits;
    for (const Lane& l : lanes_) {
        if (!l.pack_id.empty() && l.role == lane) return l.bits;
    }
    return lanes_[0].bits;
}

std::vector<SunGlobals::Other> SunGlobals::others_of(std::size_t k, std::span<const LaneInputs> lanes) {
    std::vector<Other> out;
    const LaneInputs& mine = lanes[k];
    // The coarser pack lies under the active one (the canal ring around the Westerkerk): it takes no other pack's
    // solids, nor do the others take its.
    if (!mine.pack || mine.role == LaneOf::coarser || mine.wanted.empty()) return out;
    const tiles::Pack& p = mine.pack->pack;
    const tiles::Frame& f = p.head()->frame;
    // Each resident tile's reach widened by the march's (sun::halo_of's, in grid units of this pack's frame).
    const auto units = [](double k) { return static_cast<std::int32_t>(std::ceil((sun::reach / k) * 1e7)) + 1; };
    const std::int32_t dlat = units(f.kz);
    const std::int32_t dlon = units(f.kx);
    std::vector<tiles::GridBox> boxes;
    for (const std::uint64_t key : mine.wanted) {
        if (const tiles::ChunkRef* c = p.chunks().find(tiles::TileKey::unpack(key))) {
            const tiles::GridBox& b = c->reach.box;
            boxes.push_back(
                {.south = b.south - dlat, .west = b.west - dlon, .north = b.north + dlat, .east = b.east + dlon});
        }
    }
    for (std::size_t j = 0; j < lanes.size(); ++j) {
        const LaneInputs& other = lanes[j];
        if (j == k || !other.pack || other.role == LaneOf::coarser) continue;
        const tiles::Pack& q = other.pack->pack;
        for (const tiles::ChunkRef& c : q.chunks().level(q.head()->levels[0])) {
            // a place this pack holds is its own to cast from
            if (p.chunks().find(c.tile) != nullptr) continue;
            if (std::ranges::none_of(boxes, [&c](const tiles::GridBox& b) { return c.reach.meets(b); })) continue;
            // at the level it is drawn at where it is resident, else at its pack's level
            const auto it = std::ranges::lower_bound(other.levels, c.tile.packed(), {},
                                                     &std::pair<std::uint64_t, std::uint8_t>::first);
            const bool resident = it != other.levels.end() && it->first == c.tile.packed();
            out.push_back(
                {.pack = other.pack, .key = c.tile, .level = resident ? it->second : other.level, .chunk = c.sha256});
        }
    }
    std::ranges::sort(out, [](const Other& a, const Other& b) {
        return a.pack->id != b.pack->id ? a.pack->id < b.pack->id : a.key.packed() < b.key.packed();
    });
    return out;
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
    s.others_ms = others_ms_;
    return s;
}

} // namespace atlas::scene
