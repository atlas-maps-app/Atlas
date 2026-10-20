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
    // The providers the passes contribute (their glyphs, their sprites), with the world's resources.
    for (const std::unique_ptr<frame::Pass>& p : passes) {
        for (std::shared_ptr<frame::GlobalProvider>& g : p->providers(device, w->options_.resources)) {
            if (auto added = w->add_globals(std::move(g)); !added) {
                return gpu::fail(std::format("the {} pass's provider: {}", p->name(), added.error().text()));
            }
        }
    }
    w->pack_stage_ = std::make_unique<PackStage>(w->store_, passes);
    return w;
}

World::~World() {
    pack_stage_.reset(); // the packs' thread stops first
    sun_.reset();        // then the sun's
    residency_.reset();  // then the residency's workers, before the heaps and the store go
}

const ResidentPack* World::built(const std::shared_ptr<const tiles::StorePack>& pack) const noexcept {
    if (!pack) return nullptr;
    for (const std::shared_ptr<const ResidentPack>& p : packs_) {
        if (p->source == pack) return p.get();
    }
    return nullptr;
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
    pack_stage_->set_published(wake);
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
    // The pack drawn stays while the store still holds it (a pack let go of, or added again, is another) and it still
    // fits; else the first of the store's that does.
    const std::vector<std::shared_ptr<const tiles::StorePack>> open = store_.packs();
    std::shared_ptr<const tiles::StorePack> pick;
    if (active_ && std::ranges::find(open, active_) != open.end() &&
        (forced_.empty() ? holds(*active_) : active_->id == forced_)) {
        pick = active_;
    } else {
        for (const std::shared_ptr<const tiles::StorePack>& p : open) {
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

namespace {

/// A render pack's box: its finest level's reach (none for a pack without one).
[[nodiscard]] std::optional<tiles::GridBox> box_of(const tiles::StorePack& pack) {
    const tiles::RenderHead* head = pack.pack.head();
    if (!pack.pack.is_render() || head == nullptr || head->levels.empty()) return std::nullopt;
    std::optional<tiles::GridBox> box;
    for (const tiles::ChunkRef& c : pack.pack.chunks().level(head->levels[0])) {
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
    return box;
}

} // namespace

std::string World::enclosing_pack() {
    const std::vector<std::shared_ptr<const tiles::StorePack>> open = store_.packs();
    if (active_ == enclosing_for_ && open == enclosing_among_) return enclosing_id_;
    enclosing_for_ = active_;
    enclosing_among_ = open;
    enclosing_id_.clear();
    const std::optional<tiles::GridBox> inner = active_ ? box_of(*active_) : std::nullopt;
    if (!inner) return enclosing_id_;
    double smallest = 0;
    for (const std::shared_ptr<const tiles::StorePack>& p : open) {
        if (p == active_ || p->id == active_->id) continue;
        const std::optional<tiles::GridBox> b = box_of(*p);
        if (!b || b->south > inner->south || b->west > inner->west || b->north < inner->north ||
            b->east < inner->east) {
            continue;
        }
        const double area = static_cast<double>(b->north - b->south) * static_cast<double>(b->east - b->west);
        if (enclosing_id_.empty() || area < smallest) {
            enclosing_id_ = p->id;
            smallest = area;
        }
    }
    return enclosing_id_;
}

void World::choose_coarser() {
    coarser_.reset();
    mask_.reset();
    if (!active_) return;
    const std::string wanted = !coarser_wanted_.empty() ? coarser_wanted_
                               : coarser_enclosing_     ? enclosing_pack()
                                                        : std::string();
    if (wanted.empty() || wanted == active_->id) return;
    for (const std::shared_ptr<const tiles::StorePack>& p : store_.packs()) {
        if (p->id == wanted && p->pack.is_render() && p->pack.head() != nullptr) coarser_ = p;
    }
    if (!coarser_) return;
    if (mask_wanted_ && !coarser_wanted_.empty()) {
        mask_ = mask_wanted_;
        return;
    }
    // The active pack's box: its finest level's reach.
    mask_ = box_of(*active_);
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

void World::take_packs() {
    // The packs' own stage T: asked once per pack drawn, taken as it is built; what was built of a pack the store no
    // longer holds (let go of, or added again) goes when the packs drawn change or a pack arrives.
    pack_stage_->want(active_);
    if (coarser_) pack_stage_->want(coarser_);
    pack_stage_->take(arrived_);
    if (arrived_.empty() && active_ == last_active_ && coarser_ == last_coarser_) return;
    last_active_ = active_;
    last_coarser_ = coarser_;
    for (std::shared_ptr<const ResidentPack>& b : arrived_) packs_.push_back(std::move(b));
    arrived_.clear();
    const std::vector<std::shared_ptr<const tiles::StorePack>> open = store_.packs();
    std::erase_if(packs_, [&open](const std::shared_ptr<const ResidentPack>& p) {
        return std::ranges::find(open, p->source) == open.end();
    });
}

void World::update(const frame::FrameState& state) {
    const double seconds = options_.clock ? options_.clock() : state.seconds;
    taken_.clear();
    residency_->take_published(items_, &taken_);
    if (!active_ || !state.view) {
        // nothing drawn: no coarser pack, and nothing kept of the packs' stage T but what the drawn ones would need
        choose_coarser();
        take_packs();
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
    take_packs();
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
    // The sun of the pack drawn and of the coarser pack around it, each in its own lane.
    if (sun_) sun_->prepare(state, active_, in.tolerance, cover_, coarser_, in.coarser_tolerance);
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
