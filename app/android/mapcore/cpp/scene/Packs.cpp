// The packs' own stage T (scene/Packs.hpp).
#include "scene/Packs.hpp"

#include "base/arena.hpp"
#include "base/log.hpp"
#include "tiles/Error.hpp"
#include "tiles/Store.hpp"

#include <algorithm>
#include <chrono>
#include <format>

#if defined(__ANDROID__)
#include <sys/resource.h>
#endif

namespace atlas::scene {

gpu::Result<void> PackBuild::tiles(const std::function<void(const tiles::Tile&, const tiles::ChunkRef&)>& each,
                                   std::optional<std::uint8_t> z) {
    const tiles::RenderHead& h = head();
    if (h.levels.empty()) return {};
    const std::uint8_t level = z.value_or(h.levels[0]);
    // a frame cell holding its levels alone: nothing of its places' own (the levels' tiles keep no labels)
    if (!z && tiles::Store::derived(level)) return {};
    tiles::Zstd zstd;
    tiles::TileDecoder decoder;
    Arena arena(std::size_t{4} << 20U, Growth::grows, "a pack's stage T");
    std::vector<std::byte> raw;
    for (const tiles::ChunkRef& c : pack_->pack.chunks().level(level)) {
        arena.reset();
        auto tile = store_.decode({.pack = pack_, .chunk = &c}, zstd, decoder, arena, raw);
        if (!tile) {
            return gpu::fail(std::format("{}/{}/{} of {}: {}", c.tile.z, c.tile.x, c.tile.y, pack_->id,
                                         tiles::describe(tile.error())));
        }
        each(*tile, c);
    }
    return {};
}

PackStage::PackStage(const tiles::Store& store, std::span<const std::unique_ptr<frame::Pass>> passes)
    : store_(store), passes_(passes), thread_([this](const std::stop_token& stop) { work(stop); }) {}

PackStage::~PackStage() = default; // the thread stops first (the last member)

void PackStage::stop() { thread_ = {}; }

void PackStage::want(const std::shared_ptr<const tiles::StorePack>& pack, std::uint64_t passes) {
    if (!pack || !pack->pack.is_render() || pack->pack.head() == nullptr) return;
    {
        const std::scoped_lock lock(mutex_);
        // The same pack by ownership, never by address: a pack let go of and another opened where it was are two.
        auto same = [&pack](const Known& k) { return !k.pack.owner_before(pack) && !pack.owner_before(k.pack); };
        std::uint64_t lacks = passes;
        if (const auto known = std::ranges::find_if(known_, same); known != known_.end()) {
            lacks = passes & ~known->passes;
            if (lacks == 0U) return;
            known->passes |= lacks;
        } else {
            std::erase_if(known_, [](const Known& k) { return k.pack.expired(); });
            known_.push_back({.pack = pack, .passes = passes});
        }
        queue_.push_back({.pack = pack, .passes = lacks});
    }
    wake_.notify_one();
}

void PackStage::take(std::vector<std::shared_ptr<const ResidentPack>>& out) {
    const std::scoped_lock lock(mutex_);
    for (std::shared_ptr<const ResidentPack>& p : published_) out.push_back(std::move(p));
    published_.clear();
}

void PackStage::set_published(std::function<void()> callback) {
    const std::scoped_lock lock(mutex_);
    published_callback_ = std::move(callback);
}

void PackStage::wait_idle() {
    std::unique_lock lock(mutex_);
    idle_.wait(lock, [this] { return queue_.empty() && !busy_; });
}

void PackStage::work(const std::stop_token& stop) {
#if defined(__ANDROID__)
    // Background priority, as the residency's workers and the sun's threads have (ENGINE.md gain 8): made on the render
    // thread, this thread would keep its display priority, and a pack's stage T decodes every tile of the pack (the
    // canal names of a frame cell's windows, of the active pack and of up to three neighbours where four cells meet),
    // which must not take the frames' time. This thread alone on Linux.
    (void)setpriority(PRIO_PROCESS, 0, 10);
#endif
    for (;;) {
        Job job;
        {
            std::unique_lock lock(mutex_);
            if (!wake_.wait(lock, stop, [this] { return !queue_.empty(); })) return;
            job = std::move(queue_.front());
            queue_.erase(queue_.begin());
            busy_ = true;
        }
        const std::shared_ptr<const tiles::StorePack>& pack = job.pack;
        const auto started = std::chrono::steady_clock::now();
        auto built = std::make_shared<ResidentPack>();
        built->id = pack->id;
        built->source = pack;
        built->passes.resize(passes_.size());
        for (std::uint32_t p = 0; p < passes_.size(); ++p) {
            // a pass whose parts are all left out: nothing (Residency::among: a pass past the 64th always)
            if (p < 64 && (job.passes & (std::uint64_t{1} << p)) == 0U) continue;
            built->built |= p < 64 ? std::uint64_t{1} << p : 0U;
            PackBuild b(store_, pack, built->passes[p], p);
            if (auto made = passes_[p]->build_pack(b); !made) {
                log::warn("vk packs: {}: the {} pass: {}", pack->id, passes_[p]->name(), made.error().text());
                built->passes[p] = {};
            }
        }
        built->build_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
        // what a pack's own stage T cost (the canal ring's names decode every window of its cell), and of which passes
        log::info("vk packs: {} built in {:.1f} ms, passes {:#x}", pack->id, built->build_ms, built->built);
        std::function<void()> published;
        {
            const std::scoped_lock lock(mutex_);
            published_.push_back(std::move(built));
            published = published_callback_;
        }
        if (published) published();
        {
            const std::scoped_lock lock(mutex_);
            busy_ = false;
        }
        idle_.notify_all();
    }
}

} // namespace atlas::scene
