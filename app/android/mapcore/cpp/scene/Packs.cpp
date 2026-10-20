// The packs' own stage T (scene/Packs.hpp).
#include "scene/Packs.hpp"

#include "base/arena.hpp"
#include "base/log.hpp"
#include "tiles/Error.hpp"

#include <algorithm>
#include <chrono>
#include <format>

namespace atlas::scene {

gpu::Result<void> PackBuild::tiles(const std::function<void(const tiles::Tile&, const tiles::ChunkRef&)>& each,
                                   std::optional<std::uint8_t> z) {
    const tiles::RenderHead& h = head();
    if (h.levels.empty()) return {};
    const std::uint8_t level = z.value_or(h.levels[0]);
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

void PackStage::want(const std::shared_ptr<const tiles::StorePack>& pack) {
    if (!pack || !pack->pack.is_render() || pack->pack.head() == nullptr) return;
    {
        const std::scoped_lock lock(mutex_);
        // The same pack by ownership, never by address: a pack let go of and another opened where it was are two.
        auto same = [&pack](const std::weak_ptr<const tiles::StorePack>& k) {
            return !k.owner_before(pack) && !pack.owner_before(k);
        };
        if (std::ranges::any_of(known_, same)) return;
        std::erase_if(known_, [](const std::weak_ptr<const tiles::StorePack>& k) { return k.expired(); });
        known_.emplace_back(pack);
        queue_.push_back(pack);
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
    for (;;) {
        std::shared_ptr<const tiles::StorePack> pack;
        {
            std::unique_lock lock(mutex_);
            if (!wake_.wait(lock, stop, [this] { return !queue_.empty(); })) return;
            pack = queue_.front();
            queue_.erase(queue_.begin());
            busy_ = true;
        }
        const auto started = std::chrono::steady_clock::now();
        auto built = std::make_shared<ResidentPack>();
        built->id = pack->id;
        built->source = pack;
        built->passes.resize(passes_.size());
        for (std::uint32_t p = 0; p < passes_.size(); ++p) {
            PackBuild b(store_, pack, built->passes[p], p);
            if (auto made = passes_[p]->build_pack(b); !made) {
                log::warn("vk packs: {}: the {} pass: {}", pack->id, passes_[p]->name(), made.error().text());
                built->passes[p] = {};
            }
        }
        built->build_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
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
