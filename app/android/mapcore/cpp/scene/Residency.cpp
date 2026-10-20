// Residency (scene/Residency.hpp).
#include "scene/Residency.hpp"

#include "base/log.hpp"
#include "sun/Buckets.hpp"

#include <algorithm>
#include <chrono>
#include <format>

#if defined(__ANDROID__)
#include <sys/resource.h>
#endif

namespace atlas::scene {

namespace {

/// A worker's arena for one tile's decoding (grown for a dense tile, reset per tile).
constexpr std::size_t arena_bytes = std::size_t{4} << 20U;

[[nodiscard]] double ms_since(std::chrono::steady_clock::time_point t) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
}

/// The store's pack of id `id`, or null.
[[nodiscard]] std::shared_ptr<const tiles::StorePack> pack_of(const tiles::Store& store, std::string_view id) {
    for (std::shared_ptr<const tiles::StorePack>& p : store.packs()) {
        if (p->id == id) return p;
    }
    return nullptr;
}

/// Every heap range a resident tile's passes hold.
template <class F>
void each_range(const ResidentTile& t, const F& f) {
    for (const PassTile& p : t.passes) {
        for (const HeapRange& r : p.ranges) {
            if (r) f(r);
        }
    }
}

/// What a built tile holds in native memory: itself, its pack's id, its cells, its items and the rows they will take,
/// each pass's record, cell offsets and CPU-side data (as its builder counted it).
[[nodiscard]] std::size_t native_bytes_of(const ResidentTile& t) noexcept {
    std::size_t n = sizeof(ResidentTile) + t.pack.capacity() + (t.cells.capacity() * sizeof(tiles::CellInfo)) +
                    (t.items.capacity() * sizeof(ItemSpec)) + (t.items.size() * sizeof(std::uint32_t)) +
                    (t.passes.capacity() * sizeof(PassTile));
    for (const PassTile& p : t.passes) n += (p.cell_first.capacity() * sizeof(std::uint32_t)) + p.cpu_bytes;
    return n;
}

} // namespace

std::uint64_t Residency::key_of(tiles::TileKey key, std::string_view pack) noexcept {
    // The tile's zoom, column and row, mixed with the pack's id (FNV-1a): a key per tile of a pack.
    std::uint64_t h = 0xCBF2'9CE4'8422'2325ULL;
    for (const char c : pack) h = (h ^ static_cast<unsigned char>(c)) * 0x0000'0100'0000'01B3ULL;
    return h ^ (key.packed() * 0x9E37'79B9'7F4A'7C15ULL);
}

Residency::Residency(const tiles::Store& store, Heaps& heaps, std::span<const std::unique_ptr<frame::Pass>> passes,
                     unsigned workers, std::function<double()> clock)
    : store_(store), heaps_(heaps), passes_(passes), clock_(std::move(clock)) {
    workers = std::max(1U, workers);
    workers_.reserve(workers);
    for (unsigned i = 0; i < workers; ++i) {
        workers_.emplace_back([this](const std::stop_token& stop) { work(stop); });
    }
}

Residency::~Residency() {
    for (std::jthread& w : workers_) w.request_stop();
    wake_.notify_all();
    workers_.clear(); // joins: no worker touches the members below after this
    // What was published and never taken goes back to the heaps (the GPU never saw it).
    for (const std::shared_ptr<ResidentTile>& t : published_) {
        each_range(*t, [this](const HeapRange& r) { heaps_.retire(r, 0); });
    }
    for (const std::shared_ptr<const ResidentTile>& t : resident_) {
        each_range(*t, [this](const HeapRange& r) { heaps_.retire(r, 0); });
    }
}

void Residency::want(std::span<const Request> requests) {
    {
        const std::scoped_lock lock(mutex_);
        ++generation_;
        // What is queued and wanted no more is cancelled; what is wanted and neither resident nor under way is queued.
        std::vector<Job> queue;
        queue.reserve(requests.size());
        for (const Request& r : requests) {
            const std::uint64_t k = key_of(r.key, r.pack);
            if (index_.contains(k)) continue; // resident (the render thread's own map: want runs on it)
            const auto under = underway_.find(k);
            if (under != underway_.end() && under->second != 1) continue; // building, or published and not taken
            if (std::ranges::any_of(queue, [&](const Job& j) { return key_of(j.request.key, j.pack) == k; })) continue;
            queue.push_back({.request = r, .pack = std::string(r.pack), .generation = generation_});
            queue.back().request.pack = queue.back().pack;
        }
        for (const Job& j : queue_) {
            const std::uint64_t k = key_of(j.request.key, j.pack);
            const bool still =
                std::ranges::any_of(queue, [&](const Job& q) { return key_of(q.request.key, q.pack) == k; });
            if (!still) {
                underway_.erase(k);
                ++stats_.cancelled;
            }
        }
        for (Job& j : queue) {
            const std::uint64_t k = key_of(j.request.key, j.pack);
            if (!underway_.contains(k)) ++stats_.requested;
            underway_[k] = 1;
        }
        std::ranges::stable_sort(queue, [](const Job& a, const Job& b) {
            return a.request.lane != b.request.lane ? a.request.lane < b.request.lane
                                                    : a.request.priority > b.request.priority;
        });
        queue_ = std::move(queue);
        for (Job& j : queue_) j.request.pack = j.pack; // the views follow the strings into the new vector
    }
    wake_.notify_all();
}

void Residency::work(const std::stop_token& stop) {
#if defined(__ANDROID__)
    (void)setpriority(PRIO_PROCESS, 0, 10); // the workers' background priority (T8): this thread alone on Linux
#endif
    tiles::Zstd zstd;
    tiles::TileDecoder decoder;
    Arena arena(arena_bytes, Growth::grows, "a residency worker's tile");
    std::vector<std::byte> raw;
    for (;;) {
        Job job;
        {
            std::unique_lock lock(mutex_);
            if (!wake_.wait(lock, stop, [this] { return !queue_.empty(); })) return;
            job = std::move(queue_.front());
            queue_.erase(queue_.begin());
            job.request.pack = job.pack;
            underway_[key_of(job.request.key, job.pack)] = 2;
            ++busy_;
        }
        const auto started = std::chrono::steady_clock::now();
        arena.reset();
        std::shared_ptr<ResidentTile> tile = build(job, zstd, decoder, arena, raw);
        const double ms = ms_since(started);
        std::function<void()> published;
        {
            const std::scoped_lock lock(mutex_);
            const std::uint64_t k = key_of(job.request.key, job.pack);
            if (tile) {
                underway_[k] = 3;
                published_.push_back(std::move(tile));
                ++stats_.built;
                stats_.build_ms += ms;
                published = published_callback_;
            } else {
                underway_.erase(k);
                ++stats_.failed;
            }
        }
        // The wake, outside the lock; the worker counts as busy until it has run, so wait_idle sees it done.
        if (published) published();
        {
            const std::scoped_lock lock(mutex_);
            --busy_;
        }
        idle_.notify_all();
    }
}

std::shared_ptr<ResidentTile> Residency::build(const Job& job, tiles::Zstd& zstd, tiles::TileDecoder& decoder,
                                               Arena& arena, std::vector<std::byte>& raw) {
    const std::shared_ptr<const tiles::StorePack> pack = pack_of(store_, job.pack);
    if (!pack || !pack->pack.is_render() || pack->pack.head() == nullptr) return nullptr;
    const tiles::ChunkRef* chunk = pack->pack.chunks().find(job.request.key);
    if (chunk == nullptr) return nullptr;
    auto decoded = store_.decode({.pack = pack, .chunk = chunk}, zstd, decoder, arena, raw);
    if (!decoded) {
        log::warn("vk tiles: {}/{}/{} of {}: {}", job.request.key.z, job.request.key.x, job.request.key.y, job.pack,
                  tiles::describe(decoded.error()));
        return nullptr;
    }
    const tiles::Tile& tile = *decoded;
    const tiles::RenderHead& head = *pack->pack.head();
    auto out = std::make_shared<ResidentTile>();
    out->key = job.request.key;
    out->handle = next_handle_.fetch_add(1, std::memory_order_relaxed);
    out->pack = job.pack;
    out->frame = view::frame_of(head.frame);
    out->origin = {head.frame.x(tile.header.west), 0.0, head.frame.z(tile.header.south)};
    out->level = job.request.level;
    out->cells_per_side = tile.header.cells_per_side;
    out->cells.assign(tile.cells.begin(), tile.cells.end());
    out->reach = chunk->reach;
    out->passes.resize(passes_.size());
    for (std::uint32_t p = 0; p < passes_.size(); ++p) {
        TileBuild b(tile, head, pack->pack.style(), job.request.level, out->origin, heaps_, out->passes[p], out->items,
                    p);
        if (auto built = passes_[p]->build(b); !built) {
            log::warn("vk tiles: {}/{}/{} of {}: the {} pass: {}", job.request.key.z, job.request.key.x,
                      job.request.key.y, job.pack, passes_[p]->name(), built.error().text());
            each_range(*out, [this](const HeapRange& r) { heaps_.retire(r, 0); });
            return nullptr;
        }
    }
    // The tile's writes, visible to the GPU before the render thread can see the tile.
    for (std::size_t k = 0; k < heap_kinds; ++k) heaps_.flush(static_cast<HeapKind>(k));
    out->published = clock_ ? clock_() : 0.0;
    for (ItemSpec& item : out->items) item.tile = out->handle;
    out->native_bytes = native_bytes_of(*out);
    return out;
}

void Residency::take_published(ItemTable& items, std::vector<std::shared_ptr<const ResidentTile>>* taken) {
    std::vector<std::shared_ptr<ResidentTile>> ready;
    {
        const std::scoped_lock lock(mutex_);
        ready.swap(published_);
        for (const std::shared_ptr<ResidentTile>& t : ready) underway_.erase(key_of(t->key, t->pack));
    }
    for (std::shared_ptr<ResidentTile>& t : ready) {
        const std::uint64_t k = key_of(t->key, t->pack);
        if (index_.contains(k)) { // a second build of a tile already resident: the first stays
            each_range(*t, [this](const HeapRange& r) { heaps_.retire(r, 0); });
            continue;
        }
        t->rows.reserve(t->items.size());
        for (const ItemSpec& item : t->items) t->rows.push_back(items.add(item));
        index_[k] = resident_.size();
        native_.fetch_add(t->native_bytes, std::memory_order_relaxed);
        resident_.push_back(t);
        if (taken != nullptr) taken->push_back(t);
    }
}

void Residency::release(tiles::TileKey key, std::string_view pack, ItemTable& items, std::uint64_t frame) {
    const std::uint64_t k = key_of(key, pack);
    const auto it = index_.find(k);
    if (it == index_.end()) return;
    const std::size_t at = it->second;
    const std::shared_ptr<const ResidentTile> tile = resident_[at];
    for (const std::uint32_t row : tile->rows) items.remove(row);
    each_range(*tile, [&](const HeapRange& r) { heaps_.retire(r, frame); });
    native_.fetch_sub(tile->native_bytes, std::memory_order_relaxed);
    // Swap with the last, so the index stays dense.
    index_.erase(it);
    if (at + 1 != resident_.size()) {
        resident_[at] = std::move(resident_.back());
        index_[key_of(resident_[at]->key, resident_[at]->pack)] = at;
    }
    resident_.pop_back();
    const std::scoped_lock lock(mutex_);
    ++stats_.released;
}

const ResidentTile* Residency::find(tiles::TileKey key, std::string_view pack) const noexcept {
    const auto it = index_.find(key_of(key, pack));
    return it == index_.end() ? nullptr : resident_[it->second].get();
}

std::vector<tiles::TileKey> Residency::sun_tiles(std::string_view pack) const {
    const std::shared_ptr<const tiles::StorePack> p = pack_of(store_, pack);
    if (!p || p->pack.head() == nullptr || p->pack.head()->levels.empty()) return {};
    // The finest zoom's resident tiles alone: the sun's scene is made of them and their halo (a pack's coarser zoom,
    // resident farther off, has no sun bits).
    const std::uint8_t fine = p->pack.head()->levels[0];
    std::vector<tiles::TileKey> resident;
    for (const std::shared_ptr<const ResidentTile>& t : resident_) {
        if (t->pack == pack && t->key.z == fine) resident.push_back(t->key);
    }
    return sun::halo_of(p->pack, resident);
}

void Residency::set_published(std::function<void()> callback) {
    const std::scoped_lock lock(mutex_);
    published_callback_ = std::move(callback);
}

void Residency::wait_idle() {
    std::unique_lock lock(mutex_);
    idle_.wait(lock, [this] { return queue_.empty() && busy_ == 0; });
}

ResidencyStats Residency::stats() const {
    const std::scoped_lock lock(mutex_);
    ResidencyStats s = stats_;
    s.resident = resident_.size();
    s.native_bytes = native_.load(std::memory_order_relaxed);
    s.pending = queue_.size() + busy_ + published_.size();
    return s;
}

} // namespace atlas::scene
