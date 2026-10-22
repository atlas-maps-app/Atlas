// The world the map draws (scene/World.hpp).
#include "scene/World.hpp"

#include "base/pymath/PyMath.hpp"
#include "tiles/Grid.hpp"
#include "tiles/TileBox.hpp"

#include <algorithm>
#include <array>
#include <cmath>
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
        // (its own layout) gets the heaps' buffer, at every kind's binding.
        const vk::Buffer buffer = heaps_.buffer();
        for (const frame::Global g : heap_bindings) globals.buffer(g, buffer);
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
    // The passes that build its tiles and packs, by name: a frame builder of other passes draws none of them.
    for (const std::unique_ptr<frame::Pass>& p : passes) w->pass_names_.push_back(p->name());
    return w;
}

World::~World() {
    // Every thread stops before anything they share goes (the packs' shared pointers among them): the packs' thread,
    // the sun's, then the residency's workers; then their data, the residency's before the heaps and the store.
    if (pack_stage_) pack_stage_->stop();
    if (sun_) sun_->stop();
    if (residency_) residency_->stop();
    pack_stage_.reset();
    sun_.reset();
    residency_.reset();
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
    // fits; else the first of the store's that does. A window the app names is drawn with its frame cell's pack.
    const std::vector<std::shared_ptr<const tiles::StorePack>> open = store_.packs();
    const std::string forced = forced_.empty() ? std::string() : store_.holder_of(forced_);
    std::shared_ptr<const tiles::StorePack> pick;
    if (active_ && std::ranges::find(open, active_) != open.end() &&
        (forced.empty() ? holds(*active_) : active_->id == forced)) {
        pick = active_;
    } else {
        for (const std::shared_ptr<const tiles::StorePack>& p : open) {
            if (!forced.empty() ? p->id == forced && p->pack.is_render() && p->pack.head() != nullptr : holds(*p)) {
                pick = p;
                break;
            }
        }
    }
    if (pick != active_) {
        active_ = pick;
        ladder_.reset();
        if (active_) {
            // a pack without views (a frame cell's windows) frames the live camera in its own frame
            const tiles::RenderHead& head = *active_->pack.head();
            ladder_ = head.views().empty() ? std::optional(view::Ladder::bare(view::frame_of(head.frame),
                                                                              head.profile == tiles::Profile::living))
                                           : view::Ladder::of(head);
        }
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
        if (p == active_ || p->id == active_->id || p->kind == tiles::PackKind::window) continue;
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

void World::choose_neighbour(const style::CameraState& camera) {
    neighbour_.reset();
    if (!active_) return;
    const tiles::GridPoint target{tiles::grid_round(camera.latitude), tiles::grid_round(camera.longitude)};
    const std::vector<std::shared_ptr<const tiles::StorePack>> open = store_.packs();
    auto usable = [&](const std::shared_ptr<const tiles::StorePack>& p) {
        return p != active_ && p != coarser_ && p->pack.is_render() && p->pack.head() != nullptr;
    };
    if (active_->kind != tiles::PackKind::window) {
        // around a render pack: the windows of the frame cell holding the target
        const tiles::TileKey fine = tiles::tile_of(14, target.lat, target.lon);
        const std::string cell =
            tiles::Store::cell_id(tiles::TileKey(tiles::Store::cell_level, fine.x >> (14U - tiles::Store::cell_level),
                                                 fine.y >> (14U - tiles::Store::cell_level)));
        for (const std::shared_ptr<const tiles::StorePack>& p : open) {
            if (p->id == cell && usable(p)) neighbour_ = p;
        }
        return;
    }
    // around a cell's windows: the render pack nearest the target within the cover's reach, else the nearest other
    // cell's windows (their boxes' distance from the target, metres along the meridian and the parallel)
    const double cos_lat = pymath::cos(pymath::radians(camera.latitude));
    auto distance = [&](const tiles::GridBox& b) {
        const double dlat =
            std::max({0.0, static_cast<double>(b.south - target.lat), static_cast<double>(target.lat - b.north)});
        const double dlon =
            std::max({0.0, static_cast<double>(b.west - target.lon), static_cast<double>(target.lon - b.east)});
        return pymath::hypot(dlat * 1e-7 * 110540.0, dlon * 1e-7 * 111320.0 * cos_lat);
    };
    double best = options_.far;
    bool render = false;
    for (const std::shared_ptr<const tiles::StorePack>& p : open) {
        if (!usable(p)) continue;
        const std::optional<tiles::GridBox> b = box_of(*p);
        if (!b) continue;
        const bool is_render = p->kind != tiles::PackKind::window;
        const double d = distance(*b);
        if (d > options_.far || (render && !is_render)) continue;
        if ((is_render && !render) || d < best) {
            neighbour_ = p;
            best = d;
            render = render || is_render;
        }
    }
}

std::vector<tiles::TileKey> World::wanted_windows() const {
    const std::scoped_lock lock(wanted_mutex_);
    return wanted_;
}

std::size_t World::windows_ahead() const {
    const std::scoped_lock lock(wanted_mutex_);
    return ahead_;
}

std::vector<tiles::TileKey> World::windows_in_view() const {
    const std::scoped_lock lock(wanted_mutex_);
    return in_view_;
}

void World::want_windows(const view::View& view, const style::CameraState& camera, const view::GroundFrame& frame,
                         double seconds) {
    if (wanted_at_ >= 0.0 && seconds - wanted_at_ < 0.5 && seconds >= wanted_at_) return;
    const double since = wanted_at_ >= 0.0 ? seconds - wanted_at_ : -1.0;
    wanted_at_ = seconds;
    const std::int32_t lat = tiles::grid_round(camera.latitude);
    const std::int32_t lon = tiles::grid_round(camera.longitude);
    const std::optional<std::array<std::int32_t, 2>> was = last_target_;
    last_target_ = {lat, lon};
    std::vector<std::pair<double, tiles::TileKey>> found;
    std::vector<std::pair<double, tiles::TileKey>> drawn; // places only windows hold
    std::vector<std::pair<double, tiles::TileKey>> ahead; // places the camera's motion is about to bring into view
    {
        const std::vector<std::shared_ptr<const tiles::StorePack>> open = store_.packs();
        std::vector<tiles::GridBox> held; // the bundled and render packs' boxes: their data's places
        for (const std::shared_ptr<const tiles::StorePack>& p : open) {
            if (p->kind == tiles::PackKind::window) continue;
            if (const std::optional<tiles::GridBox> b = box_of(*p)) held.push_back(*b);
        }
        const Frustum frustum(view.camera(), 40.0, options_.far);
        const tiles::TileKey centre = tiles::tile_of(14, lat, lon);
        // the places within the finest level's reach: an L14 tile is 40,075 km · cos(lat) / 2^14 wide
        const double width = 40'075'016.686 * pymath::cos(pymath::radians(camera.latitude)) / 16384.0;
        const auto reach = static_cast<std::int64_t>(std::ceil(options_.fine_reach / std::max(width, 1.0))) + 1;
        // Where the camera is heading: its target's motion since the last look, carried window_lead seconds on (in the
        // frame's metres); none on a first look, after a pause, at rest, or for a jump of more than a few windows (a
        // search's, not a pan's). A place is about to come into view where the frustum, moved by that much, sees it.
        std::optional<std::array<double, 2>> lead;
        tiles::TileKey heading = centre;
        if (was && since > 0.0 && since <= 2.0) {
            const auto point = [&frame](std::int32_t la, std::int32_t lo) {
                return box_of({.south = la, .west = lo, .north = la, .east = lo}, 0, frame);
            };
            const Box now = point(lat, lon);
            const Box then = point((*was)[0], (*was)[1]);
            const double mx = now.x0 - then.x0;
            const double mz = now.z0 - then.z0;
            const double moved = pymath::hypot(mx, mz);
            if (moved >= window_motion_min * since && moved <= width) {
                // the lead at most two windows on: a fling's first look runs far ahead of where it stops
                const double k = std::min(window_lead / since, 2.0 * width / moved);
                lead = {{mx * k, mz * k}};
                const auto on = [k](std::int32_t a, std::int32_t b, std::int64_t range) {
                    const auto v = static_cast<std::int64_t>(std::llround(static_cast<double>(a) + (k * (a - b))));
                    return static_cast<std::int32_t>(std::clamp<std::int64_t>(v, -range, range));
                };
                heading = tiles::tile_of(14, on(lat, (*was)[0], 850'000'000), on(lon, (*was)[1], 1'799'999'999));
            }
        }
        constexpr std::int64_t last = (std::int64_t{1} << 14U) - 1;
        const std::int64_t x0 = std::min<std::int64_t>(centre.x, heading.x) - reach;
        const std::int64_t x1 = std::max<std::int64_t>(centre.x, heading.x) + reach;
        const std::int64_t y0 = std::min<std::int64_t>(centre.y, heading.y) - reach;
        const std::int64_t y1 = std::max<std::int64_t>(centre.y, heading.y) + reach;
        for (std::int64_t y = y0; y <= y1; ++y) {
            for (std::int64_t x = x0; x <= x1; ++x) {
                if (x < 0 || y < 0 || x > last || y > last) continue;
                const tiles::TileKey key(14, static_cast<std::uint32_t>(x), static_cast<std::uint32_t>(y));
                const std::array<std::int32_t, 4> b = tiles::tile_box(key);
                const tiles::GridBox grid{.south = b[0], .west = b[1], .north = b[2], .east = b[3]};
                const Box box = box_of(grid, 3000, frame); // 30 m up: what stands there
                const double d = frustum.distance(box);
                if (!frustum.sees(box) || d > options_.fine_reach) {
                    if (!lead) continue;
                    // seen once the camera has moved on: the box moved back by the lead, as the frustum sees it now
                    Box shifted = box;
                    shifted.x0 -= (*lead)[0];
                    shifted.x1 -= (*lead)[0];
                    shifted.z0 -= (*lead)[1];
                    shifted.z1 -= (*lead)[1];
                    const double then = frustum.distance(shifted);
                    if (!frustum.sees(shifted) || then > options_.fine_reach || !store_.find(key).empty()) continue;
                    const bool inside = std::ranges::any_of(held, [&grid](const tiles::GridBox& h) {
                        return grid.south >= h.south && grid.north <= h.north && grid.west >= h.west &&
                               grid.east <= h.east;
                    });
                    if (!inside) ahead.emplace_back(then, key);
                    continue;
                }
                const std::vector<tiles::StoreHit> hits = store_.find(key);
                if (!hits.empty()) {
                    if (std::ranges::all_of(
                            hits, [](const tiles::StoreHit& h) { return h.pack->kind == tiles::PackKind::window; })) {
                        drawn.emplace_back(d, key);
                    }
                    continue;
                }
                const bool inside = std::ranges::any_of(held, [&grid](const tiles::GridBox& h) {
                    return grid.south >= h.south && grid.north <= h.north && grid.west >= h.west && grid.east <= h.east;
                });
                if (!inside) found.emplace_back(d, key);
            }
        }
    }
    // stable: places at one distance keep the scan's order, on every libc++
    const auto nearest = [](std::vector<std::pair<double, tiles::TileKey>>& places) {
        std::ranges::stable_sort(places, [](const auto& a, const auto& b) { return a.first < b.first; });
        std::vector<tiles::TileKey> keys;
        keys.reserve(places.size());
        for (const auto& [d, key] : places) keys.push_back(key);
        return keys;
    };
    std::vector<tiles::TileKey> wanted = nearest(found);
    const std::vector<tiles::TileKey> next = nearest(ahead);
    const std::size_t taken = std::min(next.size(), window_ahead_max);
    wanted.insert(wanted.end(), next.begin(), next.begin() + static_cast<std::ptrdiff_t>(taken));
    std::vector<tiles::TileKey> in_view = nearest(drawn);
    const std::scoped_lock lock(wanted_mutex_);
    wanted_ = std::move(wanted);
    ahead_ = taken;
    in_view_ = std::move(in_view);
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
    if (neighbour_) pack_stage_->want(neighbour_);
    pack_stage_->take(arrived_);
    if (arrived_.empty() && active_ == last_active_ && coarser_ == last_coarser_ && neighbour_ == last_neighbour_) {
        return;
    }
    last_active_ = active_;
    last_coarser_ = coarser_;
    last_neighbour_ = neighbour_;
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
        neighbour_.reset();
        take_packs();
        cover_.update(state.view ? *state.view : view::View({}, {}), frames_, {}, *residency_, seconds);
        // without a pack the view looks at its target from a frame centred on it: the windows wanted there
        if (state.view) {
            const double lat0 = state.camera.latitude;
            const tiles::Frame centred{.lat0 = lat0,
                                       .lon0 = state.camera.longitude,
                                       .kx = 111320.0 * pymath::cos(pymath::radians(lat0)),
                                       .kz = 110540.0};
            want_windows(*state.view, state.camera, view::frame_of(centred), seconds);
        }
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
    choose_neighbour(state.camera);
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
    const CoverInputs in{
        .pack = active_.get(),
        .pinned = snapped.has_value(),
        .tolerance = head.tolerance_of(snapped, dist).value_or(0),
        .fine_reach = options_.fine_reach,
        .far = options_.far,
        .margin_px = 40.0,
        .fade_seconds = 0.18,
        .coarser = coarser_.get(),
        .coarser_tolerance = coarser_
                                 ? coarser_->pack.head()->tolerance_of(std::nullopt, dist).value_or(std::uint8_t{0})
                                 : std::uint8_t{0},
        .mask = mask_,
        .neighbour = neighbour_.get(),
        .neighbour_tolerance = neighbour_
                                   ? neighbour_->pack.head()->tolerance_of(std::nullopt, dist).value_or(std::uint8_t{0})
                                   : std::uint8_t{0}};
    cover_.update(*state.view, frames_, in, *residency_, seconds);
    want_windows(*state.view, state.camera, frames_.current().value_or(view::frame_of(head.frame)), seconds);
    // The sun of the pack drawn, of the coarser pack around it and of the neighbour beside it, each in its own lane.
    if (sun_) {
        sun_->prepare(state, active_, in.tolerance, cover_, coarser_, in.coarser_tolerance, neighbour_,
                      in.neighbour_tolerance);
    }
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
