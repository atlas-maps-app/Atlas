// The world the map draws (scene/World.hpp).
#include "scene/World.hpp"

#include "tiles/TileBox.hpp"

#include <algorithm>
#include <array>
#include <format>

namespace atlas::scene {

namespace {

/// The geometry heaps' provider of set 1: their buffers bound where they grew, retired ranges freed after the frames.
class HeapGlobals final : public frame::GlobalProvider {
public:
    explicit HeapGlobals(Heaps& heaps) noexcept : heaps_(heaps) {}
    [[nodiscard]] std::string_view name() const noexcept override { return "heaps"; }
    [[nodiscard]] std::span<const frame::Global> bindings() const noexcept override { return heap_bindings; }
    void update(const frame::FrameState& state, frame::Globals& globals) override {
        // Every frame: a binding that has not changed costs a comparison, and each frame builder drawing the world
        // (its own layout) gets the heaps' buffers.
        for (std::size_t k = 0; k < heap_kinds; ++k) {
            globals.buffer(static_cast<frame::Global>(k), heaps_.buffer(static_cast<HeapKind>(k)));
        }
        heaps_.bound(state.number);
        heaps_.collect(state.number >= gpu::Frames::in_flight ? state.number - gpu::Frames::in_flight : 0);
    }

private:
    static constexpr std::array<frame::Global, heap_kinds> heap_bindings{
        frame::Global::points, frame::Global::walls,     frame::Global::ground,
        frame::Global::lines,  frame::Global::instances, frame::Global::labels};
    Heaps& heaps_;
};

} // namespace

gpu::Result<std::unique_ptr<World>>
World::create(const gpu::Device& device, std::span<const std::unique_ptr<frame::Pass>> passes, WorldOptions options) {
    std::unique_ptr<World> w(new World(std::move(options))); // NOLINT(cppcoreguidelines-owning-memory)
    ATLAS_GPU_LET(heaps, Heaps::create(device, w->options_.heaps));
    w->heaps_ = std::move(heaps);
    std::function<double()> clock = w->options_.clock ? w->options_.clock : std::function<double()>([] { return 0.0; });
    w->residency_ = std::make_unique<Residency>(w->store_, *w->heaps_, passes, w->options_.workers, std::move(clock));
    w->heap_globals_ = std::make_unique<HeapGlobals>(*w->heaps_);
    w->globals_.push_back(w->heap_globals_.get());
    if (w->options_.sun.enabled) {
        ATLAS_GPU_LET(sun, SunGlobals::create(device, w->store_, *w->residency_, w->options_.sun, w->options_.clock));
        w->sun_ = std::move(sun);
        w->globals_.push_back(w->sun_.get());
    }
    return w;
}

World::~World() {
    sun_.reset();       // its threads stop first
    residency_.reset(); // then the residency's workers, before the heaps and the store go
}

gpu::Result<void> World::add_globals(std::shared_ptr<frame::GlobalProvider> provider) {
    if (!provider) return gpu::fail("no provider");
    for (const frame::GlobalProvider* g : globals_) {
        for (const frame::Global b : provider->bindings()) {
            if (std::ranges::find(g->bindings(), b) != g->bindings().end()) {
                return gpu::fail(std::format("the {} provider would fill set 1's {}, which the {} provider fills",
                                             provider->name(), frame::name_of(b), g->name()));
            }
        }
    }
    globals_.push_back(provider.get());
    added_.push_back(std::move(provider));
    return {};
}

void World::set_wake(std::function<void()> wake) {
    if (sun_) sun_->set_wake(wake);
    residency_->set_published(std::move(wake));
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

void World::set_coarser(std::string id, std::optional<tiles::GridBox> mask) {
    coarser_wanted_ = std::move(id);
    mask_wanted_ = mask;
}

void World::choose_coarser() {
    coarser_.reset();
    mask_.reset();
    if (coarser_wanted_.empty() || !active_ || coarser_wanted_ == active_->id) return;
    for (const std::shared_ptr<const tiles::StorePack>& p : store_.packs()) {
        if (p->id == coarser_wanted_ && p->pack.is_render() && p->pack.head() != nullptr) coarser_ = p;
    }
    if (!coarser_) return;
    if (mask_wanted_) {
        mask_ = mask_wanted_;
        return;
    }
    // The active pack's box: its finest level's reach.
    const tiles::RenderHead& head = *active_->pack.head();
    if (head.levels.empty()) return;
    std::optional<tiles::GridBox> box;
    for (const tiles::ChunkRef& c : active_->pack.chunks().level(head.levels[0])) {
        const tiles::GridBox& b = c.reach.box;
        if (!box) {
            box = b;
        } else {
            box->south = std::min(box->south, b.south);
            box->west = std::min(box->west, b.west);
            box->north = std::max(box->north, b.north);
            box->east = std::max(box->east, b.east);
        }
    }
    mask_ = box;
}

void World::place_items(const ResidentTile& tile) {
    const std::optional<view::GroundFrame>& drawn = frames_.current();
    const bool convert = drawn && *drawn != tile.frame;
    const bool coarse = coarser_ && tile.pack == coarser_->id && mask_;
    // The mask in the tile's own frame (its items' reference points are its pack frame's metres).
    double x0 = 0;
    double x1 = 0;
    double z0 = 0;
    double z1 = 0;
    if (coarse) {
        const tiles::Frame& f = coarser_->pack.head()->frame;
        x0 = f.x(mask_->west);
        x1 = f.x(mask_->east);
        z0 = f.z(mask_->north);
        z1 = f.z(mask_->south);
    }
    for (std::size_t i = 0; i < tile.rows.size() && i < tile.items.size(); ++i) {
        const ItemSpec& spec = tile.items[i];
        ItemSpec placed = spec;
        if (convert) {
            const view::Vec3 p = view::convert(tile.frame, *drawn, {spec.x, spec.y, spec.z});
            const view::Vec3 cut = view::convert(tile.frame, *drawn, {spec.cut_x, 0.0, spec.cut_z});
            placed.x = p.x;
            placed.y = p.y;
            placed.z = p.z;
            placed.cut_x = cut.x;
            placed.cut_z = cut.z;
        }
        const bool inside = coarse && spec.cut_x >= x0 && spec.cut_x <= x1 && spec.cut_z >= z0 && spec.cut_z <= z1;
        placed.flags =
            static_cast<std::uint16_t>(inside ? (spec.flags | item_flag::masked) : (spec.flags & ~item_flag::masked));
        items_.update(tile.rows[i], placed);
    }
}

const view::Ladder* World::ladder(const style::CameraState& camera) {
    choose(camera);
    return ladder_ ? &*ladder_ : nullptr;
}

void World::release_idle(double seconds, std::uint64_t frame) {
    const HeapStats stats = heaps_->stats();
    const std::size_t native = residency_->stats().native_bytes;
    const bool full =
        static_cast<double>(stats.total_used()) > options_.keep_fraction * static_cast<double>(options_.heaps.total) ||
        static_cast<double>(native) > options_.keep_fraction * static_cast<double>(options_.native_budget);
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
    taken_.clear();
    residency_->take_published(items_, &taken_);
    if (!active_ || !state.view) {
        cover_.update(state.view ? *state.view : view::View({}, {}), frames_, {}, *residency_, seconds);
        if (sun_) sun_->prepare(state, nullptr, 0, cover_);
        residency_->want({});
        release_idle(seconds, state.number);
        animating_ = frames_.weight(seconds) < 1.0;
        return;
    }
    const tiles::RenderHead& head = *active_->pack.head();
    frames_.set(view::frame_of(head.frame), seconds);
    // The items in the frame drawn, the coarser pack's masked: every resident tile's when the frame, the coarser pack
    // or the mask changed, else the tiles just taken.
    choose_coarser();
    const std::string coarser_id = coarser_ ? coarser_->id : std::string();
    if (placed_frame_ != frames_.current() || placed_coarser_ != coarser_id || placed_mask_ != mask_) {
        for (const std::shared_ptr<const ResidentTile>& t : residency_->resident()) place_items(*t);
        placed_frame_ = frames_.current();
        placed_coarser_ = coarser_id;
        placed_mask_ = mask_;
    } else {
        for (const std::shared_ptr<const ResidentTile>& t : taken_) place_items(*t);
    }
    const std::optional<std::string_view> snapped = state.placed ? state.placed->view() : std::nullopt;
    const double dist = state.placed ? state.placed->dist() : state.camera.distance;
    const CoverInputs in{.pack = active_.get(),
                         .pinned = snapped.has_value(),
                         .tolerance = head.tolerance_of(snapped, dist).value_or(0),
                         .fine_reach = options_.fine_reach,
                         .far = options_.far,
                         .margin_px = 40.0,
                         .fade_seconds = 0.18,
                         .coarser = coarser_.get(),
                         .coarser_tolerance =
                             coarser_
                                 ? coarser_->pack.head()->tolerance_of(std::nullopt, dist).value_or(std::uint8_t{0})
                                 : std::uint8_t{0},
                         .mask = mask_};
    cover_.update(*state.view, frames_, in, *residency_, seconds);
    if (sun_) sun_->prepare(state, active_, in.tolerance, cover_);
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
    animating_ = cover_.fading() || frames_.weight(seconds) < 1.0 || (sun_ && sun_->fading());
}

std::string World::tiles_line() const {
    const ResidencyStats r = residency_->stats();
    const HeapStats h = heaps_->stats();
    return std::format("vk tiles {} resident {:.1f} build {:.1f}", r.resident,
                       static_cast<double>(h.total_used()) / 1.0e6,
                       r.built > 0 ? r.build_ms / static_cast<double>(r.built) : 0.0);
}

} // namespace atlas::scene
