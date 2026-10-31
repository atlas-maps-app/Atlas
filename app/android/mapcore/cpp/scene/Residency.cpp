// Residency (scene/Residency.hpp).
#include "scene/Residency.hpp"

#include "base/log.hpp"
#include "base/trace.hpp"
#include "sun/Buckets.hpp"

#include <algorithm>
#include <chrono>
#include <format>
#include <limits>
#include <optional>
#include <utility>

#if defined(__ANDROID__)
#include <sys/resource.h>
#endif

namespace atlas::scene {

namespace {

/// A tile's arena for its decoding (grown for a dense tile, reset per tile).
constexpr std::size_t arena_bytes = std::size_t{4} << 20U;

[[nodiscard]] double ms_since(std::chrono::steady_clock::time_point t) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
}

/// The steady clock's nanoseconds (CLOCK_MONOTONIC on Android: the hint session's clock).
[[nodiscard]] std::int64_t nanoseconds_of(std::chrono::steady_clock::time_point t) {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(t.time_since_epoch()).count();
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

Residency::Scratch::Scratch() : arena(arena_bytes, Growth::grows, "a tile under way") {}

Residency::Residency(const tiles::Store& store, Heaps& heaps, std::span<const std::unique_ptr<frame::Pass>> passes,
                     WorkerOptions workers, std::function<double()> clock)
    : store_(store), heaps_(heaps), passes_(passes), clock_(std::move(clock)), hints_(std::move(workers.hints)),
      workers_count_(std::max(1U, workers.count)), share_(workers.share) {
    for (const std::unique_ptr<frame::Pass>& p : passes_)
        stage_t_names_.push_back(std::format("atlas stage T {}", p->name()));
    pass_ms_.assign(passes_.size(), 0.0);
    pass_runs_.assign(passes_.size(), 0);
    workers_.reserve(workers_count_);
    for (unsigned i = 0; i < workers_count_; ++i) {
        workers_.emplace_back([this, i](const std::stop_token& stop) { work(stop, i); });
    }
}

Residency::~Residency() {
    stop(); // no worker touches the members below after this
    // What was published and never taken goes back to the heaps (the GPU never saw it), and what the passes of a tile
    // the stop left under way made.
    for (const std::shared_ptr<ResidentTile>& t : published_) {
        each_range(*t, [this](const HeapRange& r) { heaps_.retire(r, 0); });
    }
    for (const std::unique_ptr<Underway>& u : building_) {
        if (u->out) each_range(*u->out, [this](const HeapRange& r) { heaps_.retire(r, 0); });
        if (u->reserved.range && !u->reserved.taken.load()) heaps_.give_back(u->reserved.range);
    }
    for (const Job& j : queue_) {
        if (j.reserved) heaps_.give_back(j.reserved);
    }
    for (const std::shared_ptr<const ResidentTile>& t : resident_) {
        each_range(*t, [this](const HeapRange& r) { heaps_.retire(r, 0); });
    }
}

void Residency::stop() {
    for (std::jthread& w : workers_) w.request_stop();
    wake_.notify_all();
    workers_.clear(); // joins
}

void Residency::want(std::span<const Request> requests) {
    // The hints hear whether the frame now waits before a worker can take a tile of these wants (under the lock that
    // queues them): a tile in view never begins at the workers' background priority, nor is a piece of it reported
    // before the frame waits on it.
    std::unique_lock<std::mutex> hinting;
    if (hints_) hinting = std::unique_lock(hint_mutex_);
    {
        const std::scoped_lock lock(mutex_);
        ++generation_;
        // A failed tile no longer wanted is forgotten: asked for again later (its pack added again), it is built again.
        std::erase_if(failed_, [&](const auto& f) {
            return std::ranges::none_of(requests, [&](const Request& r) { return key_of(r.key, r.pack) == f.first; });
        });
        // What is queued and wanted no more is cancelled; what is wanted and neither resident nor under way is queued.
        // A failed tile is tried again once the heaps have the bytes it asked for free, less those the failed tiles
        // tried again before it asked for (the requests' order: the first wanted takes the room first), and a piece
        // for the largest of its ranges that found none (its bytes may be free in pieces too small for it): once on
        // them, then each time a range has gone back to the heaps since it last found none. That piece is held for it
        // (Heaps::reserve) until its build takes it, so a build beside it cannot take it first.
        const std::uint64_t frees = heaps_.frees();
        std::uint64_t room = failed_.empty() ? 0 : heaps_.free_bytes();
        std::uint64_t piece = failed_.empty() ? 0 : heaps_.largest_free();
        std::vector<Job> queue;
        queue.reserve(requests.size());
        for (const Request& r : requests) {
            const std::uint64_t k = key_of(r.key, r.pack);
            if (r.held || index_.contains(k)) continue; // held back; resident (the render thread's own map)
            bool again = false;
            std::optional<Failed> retried;
            if (const auto f = failed_.find(k); f != failed_.end()) {
                if ((f->second.tried && f->second.frees == frees) || f->second.need > room || f->second.refused > piece)
                    continue;
                room -= f->second.need;
                retried = f->second;
                failed_.erase(f);
                again = true;
            }
            const auto under = underway_.find(k);
            if (under != underway_.end() && under->second != 1) continue; // building, or published and not taken
            if (std::ranges::any_of(queue, [&](const Job& j) { return key_of(j.request.key, j.pack) == k; })) continue;
            HeapRange reserved;
            if (retried && retried->refused > 0) {
                const HeapEnd from = r.key.z < 14 ? HeapEnd::high : HeapEnd::low;
                if (const std::optional<HeapRange> held =
                        heaps_.reserve(retried->kind, retried->refused, retried->alignment, from)) {
                    reserved = *held;
                }
                piece = heaps_.largest_free();
            }
            // queued already: it stays a tile tried again, with the piece held for it
            for (Job& j : queue_) {
                if (key_of(j.request.key, j.pack) != k) continue;
                again = again || j.again;
                if (!reserved) reserved = std::exchange(j.reserved, HeapRange{});
            }
            queue.push_back({.request = r,
                             .pack = std::string(r.pack),
                             .generation = generation_,
                             .again = again,
                             .reserved = reserved});
            queue.back().request.pack = queue.back().pack;
        }
        for (const Job& j : queue_) {
            const std::uint64_t k = key_of(j.request.key, j.pack);
            const bool still =
                std::ranges::any_of(queue, [&](const Job& q) { return key_of(q.request.key, q.pack) == k; });
            if (j.reserved) heaps_.give_back(j.reserved);
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
        if (hints_) tell(urgent_locked());
    }
    wake_.notify_all();
}

bool Residency::urgent_locked() const noexcept {
    // What the frame shows waits: a tile in view queued or under way (an ancestor only stands in while one is).
    return std::ranges::any_of(queue_, [](const Job& j) { return j.request.lane == Lane::visible; }) ||
           std::ranges::any_of(building_,
                               [](const std::unique_ptr<Underway>& u) { return u->job.request.lane == Lane::visible; });
}

void Residency::hint() {
    if (!hints_) return;
    const std::scoped_lock hinting(hint_mutex_);
    bool urgent = false;
    {
        const std::scoped_lock lock(mutex_);
        urgent = urgent_locked();
    }
    tell(urgent);
}

void Residency::tell(bool urgent) {
    if (urgent == urgent_) return;
    urgent_ = urgent;
    hints_->urgent(urgent);
}

Residency::Piece Residency::take(std::unique_lock<std::mutex>& lock, const std::stop_token& stop, unsigned worker) {
    for (;;) {
        if (stop.stop_requested()) return {};
        // Of the best lane: a pass of a tile this worker took (its chain goes on, as before the gain); then a piece of
        // a pass's shared work; then the queue's next tile; then a pass of another worker's tile under way, the
        // earliest taken. So every tile wanted starts as soon as a worker is free, and a tile's work is shared once
        // nothing more waits to start.
        Split* split = nullptr;
        for (Split* s : splits_) {
            if (s->handed >= s->count) continue;
            if (split == nullptr || s->lane < split->lane) split = s;
        }
        // a tile under way with a pass for this worker: its own, or any where the workers share
        const auto open = [this, worker](const Underway& u) {
            return !u.failed && u.handed < u.order.size() && (share_ || u.worker == worker);
        };
        Underway* own = nullptr;
        Underway* other = nullptr;
        for (const std::unique_ptr<Underway>& u : building_) {
            if (!open(*u)) continue;
            Underway*& best = u->worker == worker ? own : other;
            if (best == nullptr || u->job.request.lane < best->job.request.lane) best = u.get();
        }
        const auto lane_of = [](const Underway* u) { return u != nullptr ? u->job.request.lane : Lane::prefetch; };
        const Lane queued = queue_.empty() ? Lane::prefetch : queue_.front().request.lane;
        const bool waiting = !queue_.empty();
        const auto pass_of = [this, worker](Underway& u) {
            const std::uint32_t p = u.order[u.handed++];
            if (u.worker != worker) ++stats_.shared;
            return Piece{.tile = &u, .pass = static_cast<std::int32_t>(p)};
        };
        if (own != nullptr && (split == nullptr || own->job.request.lane <= split->lane) &&
            (!waiting || own->job.request.lane <= queued) && own->job.request.lane <= lane_of(other)) {
            return pass_of(*own);
        }
        if (split != nullptr && (!waiting || split->lane <= queued) && split->lane <= lane_of(other)) {
            ++stats_.pieces;
            return {.split = split, .index = split->handed++};
        }
        if (other != nullptr && (!waiting || other->job.request.lane < queued)) return pass_of(*other);
        if (waiting) {
            auto u = std::make_unique<Underway>();
            u->job = std::move(queue_.front());
            queue_.erase(queue_.begin());
            u->job.request.pack = u->job.pack;
            u->reserved.range = std::exchange(u->job.reserved, HeapRange{});
            u->key = key_of(u->job.request.key, u->job.pack);
            u->worker = worker;
            underway_[u->key] = 2;
            Underway* taken = u.get();
            building_.push_back(std::move(u));
            return {.tile = taken, .pass = -1};
        }
        wake_.wait(lock, stop, [&] {
            return !queue_.empty() ||
                   std::ranges::any_of(splits_, [](const Split* s) { return s->handed < s->count; }) ||
                   std::ranges::any_of(building_, [&open](const std::unique_ptr<Underway>& u) { return open(*u); });
        });
    }
}

void Residency::work(const std::stop_token& stop, unsigned worker) {
    if (hints_) {
        hints_->joined();
    } else {
#if defined(__ANDROID__)
        (void)setpriority(PRIO_PROCESS, 0, 10); // the workers' background priority (T8): this thread alone on Linux
#endif
    }
    tiles::Zstd zstd;
    tiles::TileDecoder decoder;
    std::unique_lock lock(mutex_);
    for (;;) {
        const Piece piece = take(lock, stop, worker);
        if (piece.split != nullptr) {
            // a piece of another worker's pass, which waits for it
            Split& split = *piece.split;
            ++busy_;
            lock.unlock();
            const auto started = std::chrono::steady_clock::now();
            {
                const trace::Section traced("atlas stage T piece");
                (*split.piece)(piece.index);
            }
            if (hints_ && split.lane != Lane::prefetch)
                hints_->report(nanoseconds_of(started), static_cast<std::int64_t>(ms_since(started) * 1e6));
            lock.lock();
            if (++split.done == split.count) split_done_.notify_all();
            --busy_;
            idle_.notify_all();
            continue;
        }
        if (piece.tile == nullptr) return;
        Underway& u = *piece.tile;
        ++busy_;
        lock.unlock();
        const auto started = std::chrono::steady_clock::now();
        bool ok = false;
        {
            const trace::Section traced("atlas tile build");
            ok = piece.pass < 0 ? start(u, zstd, decoder) : run(u, static_cast<std::uint32_t>(piece.pass));
        }
        const double ms = ms_since(started);
        // the frame's work, a tile in view or an ancestor standing in for one, told to the workers' session
        if (hints_ && u.job.request.lane != Lane::prefetch)
            hints_->report(nanoseconds_of(started), static_cast<std::int64_t>(ms * 1e6));
        lock.lock();
        u.ms += ms;
        bool last = false;
        if (piece.pass < 0) {
            if (ok) {
                // its passes, the longest first (their mean so far; one not yet timed before them, in the registry's
                // order), handed out from now on: this worker takes the first
                u.order.resize(passes_.size());
                for (std::uint32_t p = 0; p < u.order.size(); ++p) u.order[p] = p;
                const auto mean = [this](std::uint32_t p) {
                    return pass_runs_[p] == 0 ? std::numeric_limits<double>::infinity()
                                              : pass_ms_[p] / static_cast<double>(pass_runs_[p]);
                };
                std::ranges::stable_sort(u.order, [&](std::uint32_t a, std::uint32_t b) { return mean(a) > mean(b); });
                last = u.order.empty();
                if (u.order.size() > 1) wake_.notify_all();
            } else {
                u.failed = true;
                last = true;
            }
        } else {
            const auto p = static_cast<std::uint32_t>(piece.pass);
            ++u.done;
            if (ok) {
                pass_ms_[p] += ms;
                ++pass_runs_[p];
            } else {
                u.failed = true;
            }
            last = u.done == u.handed && (u.failed || u.handed == u.order.size());
        }
        if (last) {
            lock.unlock();
            publish(u);
            hint();
            lock.lock();
        }
        --busy_;
        idle_.notify_all();
    }
}

bool Residency::start(Underway& u, tiles::Zstd& zstd, tiles::TileDecoder& decoder) {
    u.frees = heaps_.frees();
    {
        const std::scoped_lock lock(mutex_);
        if (!scratch_.empty()) {
            u.scratch = std::move(scratch_.back());
            scratch_.pop_back();
        }
    }
    if (!u.scratch) u.scratch = std::make_unique<Scratch>();
    u.scratch->arena.reset();
    const Job& job = u.job;
    u.pack = pack_of(store_, job.pack);
    if (!u.pack || !u.pack->pack.is_render() || u.pack->pack.head() == nullptr || u.pack->pack.style() == nullptr)
        return false;
    const tiles::ChunkRef* chunk = u.pack->pack.chunks().find(job.request.key);
    if (chunk == nullptr) return false;
    auto decoded = [&] {
        const trace::Section traced("atlas tile decode");
        return store_.decode({.pack = u.pack, .chunk = chunk}, zstd, decoder, u.scratch->arena, u.scratch->raw);
    }();
    if (!decoded) {
        log::warn("vk tiles: {}/{}/{} of {}: {}", job.request.key.z, job.request.key.x, job.request.key.y, job.pack,
                  tiles::describe(decoded.error()));
        return false;
    }
    u.tile = *decoded;
    const tiles::RenderHead& head = *u.pack->pack.head();
    auto out = std::make_shared<ResidentTile>();
    out->key = job.request.key;
    out->handle = next_handle_.fetch_add(1, std::memory_order_relaxed);
    out->pack = job.pack;
    out->kind = u.pack->kind;
    out->frame = view::frame_of(head.frame);
    out->origin = {head.frame.x(u.tile.header.west), 0.0, head.frame.z(u.tile.header.south)};
    out->level = job.request.level;
    out->cells_per_side = u.tile.header.cells_per_side;
    out->cells.assign(u.tile.cells.begin(), u.tile.cells.end());
    out->reach = chunk->reach;
    out->passes.resize(passes_.size());
    u.out = std::move(out);
    u.items.resize(passes_.size());
    return true;
}

/// The workers that may take pieces of one pass of a tile under way: its lane's.
class Residency::Helpers final : public Sharer {
public:
    Helpers(Residency& residency, const Underway& tile) noexcept : residency_(residency), tile_(tile) {}
    Helpers(const Helpers&) = delete;
    Helpers& operator=(const Helpers&) = delete;
    Helpers(Helpers&&) = delete;
    Helpers& operator=(Helpers&&) = delete;
    ~Helpers() override = default;

    [[nodiscard]] unsigned idle() const override { return residency_.idle(); }
    void share(std::size_t pieces, const std::function<void(std::size_t)>& piece) override {
        residency_.share(tile_, pieces, piece);
    }

private:
    Residency& residency_;
    const Underway& tile_;
};

unsigned Residency::idle() const {
    const std::scoped_lock lock(mutex_);
    return workers_count_ > busy_ ? workers_count_ - busy_ : 0U;
}

bool Residency::run(Underway& u, std::uint32_t p) {
    const tiles::StorePack& pack = *u.pack;
    // the pass's own work in pieces on the idle workers too, where the workers share
    Helpers helpers(*this, u);
    TileBuild b(u.tile, *pack.pack.head(), *pack.pack.style(), u.job.request.level, u.out->origin, heaps_,
                u.out->passes[p], u.items[p], p, share_ ? &helpers : nullptr, &u.asked, &u.refused, &u.reserved);
    const trace::Section traced(stage_t_names_[p].c_str());
    if (auto built = passes_[p]->build(b); !built) {
        const tiles::TileKey& k = u.job.request.key;
        log::warn("vk tiles: {}/{}/{} of {}: the {} pass: {}", k.z, k.x, k.y, u.job.pack, passes_[p]->name(),
                  built.error().text());
        return false;
    }
    return true;
}

void Residency::share(const Underway& u, std::size_t count, const std::function<void(std::size_t)>& piece) {
    // Handed out to the idle workers, this one running the rest; then it waits for those still under way elsewhere.
    Split split{.piece = &piece, .count = count, .lane = u.job.request.lane};
    std::unique_lock lock(mutex_);
    splits_.push_back(&split);
    wake_.notify_all();
    while (split.handed < split.count) {
        const std::size_t i = split.handed++;
        lock.unlock();
        piece(i);
        lock.lock();
        ++split.done;
    }
    split_done_.wait(lock, [&split] { return split.done == split.count; });
    std::erase(splits_, &split);
}

void Residency::publish(Underway& u) {
    // Every pass is done (or one failed and the others it was handed are): this worker alone sees the tile now.
    const auto started = std::chrono::steady_clock::now();
    // the piece held for it that none of its ranges took (its build failed before, or its ranges changed)
    if (u.reserved.range && !u.reserved.taken.load()) heaps_.give_back(u.reserved.range);
    if (u.failed) {
        // never drawn: back to the heaps at once, not as a free that would try the tile again
        if (u.out) each_range(*u.out, [this](const HeapRange& r) { heaps_.give_back(r); });
    } else {
        const trace::Section traced("atlas tile publish");
        ResidentTile& out = *u.out;
        // The tile's writes, visible to the GPU before the render thread can see the tile.
        heaps_.flush();
        out.published = clock_ ? clock_() : 0.0;
        // The items in the passes' order, as one thread building them in turn adds them.
        std::size_t n = 0;
        for (const std::vector<ItemSpec>& items : u.items) n += items.size();
        out.items.reserve(n);
        for (const std::vector<ItemSpec>& items : u.items)
            out.items.insert(out.items.end(), items.begin(), items.end());
        for (ItemSpec& item : out.items) item.tile = out.handle;
        out.native_bytes = native_bytes_of(out);
    }
    const double ms = ms_since(started);
    std::unique_ptr<Underway> gone;
    std::unique_ptr<Scratch> spare;
    std::function<void()> published;
    {
        const std::scoped_lock lock(mutex_);
        const auto it =
            std::ranges::find_if(building_, [&u](const std::unique_ptr<Underway>& b) { return b.get() == &u; });
        gone = std::move(*it);
        building_.erase(it);
        if (u.scratch) {
            if (scratch_.size() < workers_count_) {
                scratch_.push_back(std::move(u.scratch));
            } else {
                spare = std::move(u.scratch);
            }
        }
        if (!u.failed) {
            underway_[u.key] = 3;
            published_.push_back(std::move(u.out));
            ++stats_.built;
            stats_.build_ms += u.ms + ms;
            published = published_callback_;
        } else {
            underway_.erase(u.key);
            const std::scoped_lock refused(u.refused.lock);
            failed_[u.key] = {.frees = u.frees,
                              .need = u.asked.load(std::memory_order_relaxed),
                              .refused = u.refused.bytes,
                              .kind = u.refused.kind,
                              .alignment = u.refused.alignment,
                              .tried = u.job.again};
            ++stats_.failed;
        }
    }
    // The wake, outside the lock; the worker counts as busy until it has run, so wait_idle sees it done.
    if (published) published();
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

std::vector<Residency::Wait> Residency::waits(std::span<const Request> requests) const {
    std::vector<Wait> out(requests.size());
    const std::scoped_lock lock(mutex_);
    std::optional<std::uint64_t> piece; // the heaps' largest free piece, read once a request's tile has failed
    for (std::size_t i = 0; i < requests.size(); ++i) {
        const std::uint64_t k = key_of(requests[i].key, requests[i].pack);
        if (const auto f = failed_.find(k); f != failed_.end()) {
            if (!piece) piece = heaps_.largest_free();
            out[i].need = std::max<std::uint64_t>(f->second.need, 1);
            out[i].refused = f->second.refused;
            out[i].stuck = f->second.refused > *piece || (f->second.tried && f->second.frees == heaps_.frees());
        }
        const auto under = underway_.find(k);
        if (under == underway_.end() || under->second == 1) continue; // neither under way nor published
        for (const std::unique_ptr<Underway>& u : building_) {
            if (u->key == k) out[i].placed = u->asked.load(std::memory_order_relaxed);
        }
        for (const std::shared_ptr<ResidentTile>& t : published_) {
            if (key_of(t->key, t->pack) != k) continue;
            std::uint64_t bytes = 0;
            each_range(*t, [&bytes](const HeapRange& r) { bytes += r.size; });
            out[i].placed = bytes;
        }
    }
    return out;
}

void Residency::wait_idle() {
    std::unique_lock lock(mutex_);
    idle_.wait(lock, [this] { return queue_.empty() && building_.empty() && busy_ == 0; });
}

ResidencyStats Residency::stats() const {
    const std::scoped_lock lock(mutex_);
    ResidencyStats s = stats_;
    s.resident = resident_.size();
    s.native_bytes = native_.load(std::memory_order_relaxed);
    s.pending = queue_.size() + building_.size() + published_.size();
    s.waiting = failed_.size();
    return s;
}

} // namespace atlas::scene
