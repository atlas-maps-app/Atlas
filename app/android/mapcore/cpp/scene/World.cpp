// The world the map draws (scene/World.hpp).
#include "scene/World.hpp"

#include "tiles/TileBox.hpp"

#include <algorithm>
#include <format>

namespace atlas::scene {

gpu::Result<std::unique_ptr<World>>
World::create(const gpu::Device& device, std::span<const std::unique_ptr<frame::Pass>> passes, WorldOptions options) {
    std::unique_ptr<World> w(new World(std::move(options))); // NOLINT(cppcoreguidelines-owning-memory)
    ATLAS_GPU_LET(heaps, Heaps::create(device, w->options_.heaps));
    w->heaps_ = std::move(heaps);
    std::function<double()> clock = w->options_.clock ? w->options_.clock : std::function<double()>([] { return 0.0; });
    w->residency_ = std::make_unique<Residency>(w->store_, *w->heaps_, passes, w->options_.workers, std::move(clock));
    return w;
}

World::~World() {
    residency_.reset(); // its workers stop before the heaps and the store go
}

void World::choose(const style::CameraState& camera) {
    const tiles::GridPoint target{tiles::grid_round(camera.latitude), tiles::grid_round(camera.longitude)};
    auto holds = [&target](const tiles::StorePack& p) {
        const tiles::RenderHead* head = p.pack.head();
        if (!p.pack.is_render() || head == nullptr || head->levels.empty()) return false;
        for (const tiles::ChunkRef& c : p.pack.chunks().level(head->levels[0])) {
            const tiles::GridBox& b = c.reach.box;
            if (b.south <= target.lat && target.lat <= b.north && b.west <= target.lon && target.lon <= b.east) {
                return true;
            }
        }
        return false;
    };
    std::shared_ptr<const tiles::StorePack> pick;
    if (active_ && (forced_.empty() ? holds(*active_) : active_->id == forced_)) {
        pick = active_;
    } else {
        for (const std::shared_ptr<const tiles::StorePack>& p : store_.packs()) {
            if (!forced_.empty() ? p->id == forced_ && p->pack.is_render() && p->pack.head() != nullptr : holds(*p)) {
                pick = p;
                break;
            }
        }
    }
    if (pick != active_) {
        active_ = pick;
        ladder_.reset();
        if (active_) ladder_ = view::Ladder::of(*active_->pack.head());
    }
}

const view::Ladder* World::ladder(const style::CameraState& camera) {
    choose(camera);
    return ladder_ ? &*ladder_ : nullptr;
}

void World::release_idle(double seconds, std::uint64_t frame) {
    const HeapStats stats = heaps_->stats();
    const bool full =
        static_cast<double>(stats.total_used()) > options_.keep_fraction * static_cast<double>(options_.heaps.total);
    std::vector<std::pair<tiles::TileKey, std::string>> going;
    for (const std::shared_ptr<const ResidentTile>& t : residency_->resident()) {
        const auto it = std::ranges::find_if(last_drawn_, [&](const auto& p) { return p.first == t->handle; });
        if (it == last_drawn_.end()) {
            last_drawn_.emplace_back(t->handle, seconds); // just taken: its idle time starts now
            continue;
        }
        const double idle = seconds - it->second;
        if ((full && idle > options_.idle_seconds) || idle > options_.forget_seconds)
            going.emplace_back(t->key, t->pack);
    }
    for (const auto& [key, pack] : going) {
        if (const ResidentTile* t = residency_->find(key, pack)) {
            const std::uint32_t handle = t->handle;
            std::erase_if(last_drawn_, [handle](const auto& p) { return p.first == handle; });
        }
        residency_->release(key, pack, items_, frame);
    }
}

void World::update(const frame::FrameState& state) {
    const double seconds = options_.clock ? options_.clock() : state.seconds;
    residency_->take_published(items_);
    if (!active_ || !state.view) {
        cover_.update(state.view ? *state.view : view::View({}, {}), frames_, {}, *residency_, seconds);
        residency_->want({});
        release_idle(seconds, state.number);
        return;
    }
    const tiles::RenderHead& head = *active_->pack.head();
    frames_.set(view::frame_of(head.frame), seconds);
    const std::optional<std::string_view> snapped = state.placed ? state.placed->view() : std::nullopt;
    const double dist = state.placed ? state.placed->dist() : state.camera.distance;
    const CoverInputs in{.pack = active_.get(),
                         .pinned = snapped.has_value(),
                         .tolerance = head.tolerance_of(snapped, dist).value_or(0),
                         .fine_reach = options_.fine_reach,
                         .far = options_.far,
                         .margin_px = 40.0,
                         .fade_seconds = 0.18};
    cover_.update(*state.view, frames_, in, *residency_, seconds);
    residency_->want(cover_.requests());
    for (const CoverTile& t : cover_.tiles()) {
        const std::uint32_t handle = t.drawn->handle;
        const auto it = std::ranges::find_if(last_drawn_, [handle](const auto& p) { return p.first == handle; });
        if (it != last_drawn_.end()) {
            it->second = seconds;
        } else {
            last_drawn_.emplace_back(handle, seconds);
        }
    }
    release_idle(seconds, state.number);
}

std::string World::tiles_line() const {
    const ResidencyStats r = residency_->stats();
    const HeapStats h = heaps_->stats();
    return std::format("vk tiles {} resident {:.1f} build {:.1f}", r.resident,
                       static_cast<double>(h.total_used()) / 1.0e6,
                       r.built > 0 ? r.build_ms / static_cast<double>(r.built) : 0.0);
}

} // namespace atlas::scene
