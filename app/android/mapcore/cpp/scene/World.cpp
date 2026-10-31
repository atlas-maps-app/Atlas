// The world the map draws (scene/World.hpp).
#include "scene/World.hpp"

#include "base/log.hpp"
#include "base/pymath/PyMath.hpp"
#include "tiles/Grid.hpp"
#include "tiles/TileBox.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <format>
#include <iterator>
#include <numeric>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <tuple>
#include <utility>
#include <vector>

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
        // (its own layout) gets the heaps' buffers, the first at every kind's binding and the second at heap_high's;
        // taken with the marking of what a growth replaced, and without waiting for a growth's copy (gain 16).
        const HeapBuffers buffers = heaps_.bind(state.number);
        for (const frame::Global g : heap_bindings) {
            globals.buffer(g, g == frame::Global::heap_high ? buffers.high : buffers.low);
        }
        heaps_.collect(state.number >= gpu::Frames::in_flight ? state.number - gpu::Frames::in_flight : 0);
    }

private:
    static constexpr std::array<frame::Global, heap_kinds> heap_bindings{
        frame::Global::points, frame::Global::walls,     frame::Global::ground,
        frame::Global::lines,  frame::Global::heap_high, frame::Global::labels};
    Heaps& heaps_;
};

} // namespace

gpu::Result<std::unique_ptr<World>>
World::create(const gpu::Device& device, std::span<const std::unique_ptr<frame::Pass>> passes, WorldOptions options) {
    std::unique_ptr<World> w(new World(std::move(options))); // NOLINT(cppcoreguidelines-owning-memory)
    ATLAS_GPU_LET(heaps, Heaps::create(device, w->options_.heaps));
    w->heaps_ = std::move(heaps);
    w->options_.heaps.total = w->heaps_->budget().total; // within its two buffers' parts
    std::function<double()> clock = w->options_.clock ? w->options_.clock : std::function<double()>([] { return 0.0; });
    w->residency_ = std::make_unique<Residency>(
        w->store_, *w->heaps_, passes,
        WorkerOptions{.count = w->options_.workers, .share = w->options_.share, .hints = w->options_.hints},
        std::move(clock));
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

namespace {

/// The levels whose tiles say where a pack's data lies: a render pack's finest; every level of a frame cell's, whose
/// coarse tiles reach where none of its places' own tiles are on the phone yet.
[[nodiscard]] std::span<const std::uint8_t> data_levels(const tiles::StorePack& p) {
    const tiles::RenderHead& head = *p.pack.head();
    return p.kind == tiles::PackKind::window ? std::span<const std::uint8_t>(head.levels)
                                             : std::span<const std::uint8_t>(head.levels).first(1);
}

} // namespace

void World::choose(const style::CameraState& camera) {
    const tiles::GridPoint target{tiles::grid_round(camera.latitude), tiles::grid_round(camera.longitude)};
    auto holds = [&target](const tiles::StorePack& p) {
        const tiles::RenderHead* head = p.pack.head();
        if (!p.pack.is_render() || head == nullptr || head->levels.empty()) return false;
        for (const std::uint8_t z : data_levels(p)) {
            for (const tiles::ChunkRef& c : p.pack.chunks().level(z)) {
                const tiles::GridBox& b = c.reach.box;
                if (b.south <= target.lat && target.lat <= b.north && b.west <= target.lon && target.lon <= b.east) {
                    return true;
                }
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
    // A switch from a pack that still holds the target waits for the named one's tiles in view (update: the incoming
    // pack's cover), so the map never draws a frame of nothing in between: the first frames of a pack whose tiles are
    // not resident yet drew none, and the canal ring's switch to the windows' around 830 m showed as the map bouncing
    // (the user, 28 October).
    if (pick != nullptr && pick != active_ && active_ && std::ranges::find(open, active_) != open.end() &&
        holds(*active_)) {
        if (pick != incoming_) {
            incoming_ = pick;
            incoming_since_ = -1.0;
            incoming_ready_ = false;
        }
        if (!incoming_ready_) return;
    }
    incoming_.reset();
    incoming_ready_ = false;
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

/// A render pack's box: its data levels' reach (data_levels: a render pack's finest level, a frame cell's every level;
/// none for a pack without one).
[[nodiscard]] std::optional<tiles::GridBox> box_of(const tiles::StorePack& pack) {
    const tiles::RenderHead* head = pack.pack.head();
    if (!pack.pack.is_render() || head == nullptr || head->levels.empty()) return std::nullopt;
    std::optional<tiles::GridBox> box;
    for (const std::uint8_t z : data_levels(pack)) {
        for (const tiles::ChunkRef& c : pack.pack.chunks().level(z)) {
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

std::array<const tiles::StorePack*, max_neighbours> World::neighbours() const noexcept {
    std::array<const tiles::StorePack*, max_neighbours> out{};
    for (std::size_t i = 0; i < max_neighbours; ++i) out.at(i) = neighbours_.at(i).get();
    return out;
}

void World::choose_neighbours(const style::CameraState& camera) {
    neighbours_ = {};
    if (!active_) return;
    const tiles::GridPoint target{tiles::grid_round(camera.latitude), tiles::grid_round(camera.longitude)};
    const std::vector<std::shared_ptr<const tiles::StorePack>> open = store_.packs();
    // Packs pair across kinds alone: a render pack is never drawn beside another (the canal ring around the Westerkerk
    // is the coarser pack's, or nothing), so beside a render pack only cells' windows.
    const bool around_render = active_->kind != tiles::PackKind::window;
    auto usable = [&](const std::shared_ptr<const tiles::StorePack>& p) {
        return p != active_ && p->id != active_->id && p != coarser_ && p->pack.is_render() &&
               p->pack.head() != nullptr && (!around_render || p->kind == tiles::PackKind::window);
    };
    // around a render pack, the windows of the frame cell holding the target come first, wherever they reach
    std::string cell;
    if (around_render) {
        const tiles::TileKey fine = tiles::tile_of(14, target.lat, target.lon);
        cell =
            tiles::Store::cell_id(tiles::TileKey(tiles::Store::cell_level, fine.x >> (14U - tiles::Store::cell_level),
                                                 fine.y >> (14U - tiles::Store::cell_level)));
    }
    // then the others within the cover's reach of the target, each nearest first (their boxes' distance from the
    // target, metres along the meridian and the parallel): around a render pack the other cells' windows; around a
    // cell's windows the render packs, then the other cells' windows. Where four frame cells meet, the three around
    // the active one.
    const double cos_lat = pymath::cos(pymath::radians(camera.latitude));
    auto distance = [&](const tiles::GridBox& b) {
        const double dlat =
            std::max({0.0, static_cast<double>(b.south - target.lat), static_cast<double>(target.lat - b.north)});
        const double dlon =
            std::max({0.0, static_cast<double>(b.west - target.lon), static_cast<double>(target.lon - b.east)});
        return pymath::hypot(dlat * 1e-7 * 110540.0, dlon * 1e-7 * 111320.0 * cos_lat);
    };
    struct Near {
        std::shared_ptr<const tiles::StorePack> pack;
        int rank = 0; // 0: the target's cell's windows beside a render pack, or a render pack beside a cell's windows
        double distance = 0;
    };
    std::vector<Near> near;
    for (const std::shared_ptr<const tiles::StorePack>& p : open) {
        if (!usable(p)) continue;
        if (around_render && p->id == cell) {
            near.push_back({.pack = p, .rank = 0, .distance = 0.0});
            continue;
        }
        const std::optional<tiles::GridBox> b = box_of(*p);
        if (!b) continue;
        const double d = distance(*b);
        if (d > far_) continue;
        near.push_back({.pack = p, .rank = p->kind == tiles::PackKind::window ? 1 : 0, .distance = d});
    }
    // stable: packs at one distance keep the store's order
    std::ranges::stable_sort(near, [](const Near& a, const Near& b) {
        return a.rank != b.rank ? a.rank < b.rank : a.distance < b.distance;
    });
    for (std::size_t i = 0; i < near.size() && i < max_neighbours; ++i) neighbours_.at(i) = std::move(near[i].pack);
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
    std::vector<tiles::TileKey> levels = want_levels(view, camera, frame);
    // the frame cells' levels' tiles drawn, nearest first, then those kept
    std::vector<std::pair<double, tiles::TileKey>> near;
    const auto level = [](const ResidentTile& t) {
        return t.kind == tiles::PackKind::window &&
               (t.key.z == tiles::Store::far_level || t.key.z == tiles::Store::coarse_level);
    };
    for (const CoverTile& t : cover_.tiles()) {
        if (level(*t.drawn)) near.emplace_back(t.distance, t.drawn->key);
    }
    std::vector<tiles::TileKey> levels_drawn = nearest(near);
    for (const std::uint32_t handle : cover_.kept()) {
        for (const std::shared_ptr<const ResidentTile>& t : residency_->resident()) {
            if (t->handle == handle && level(*t)) levels_drawn.push_back(t->key);
        }
    }
    std::vector<tiles::TileKey> unique;
    for (const tiles::TileKey& k : levels_drawn) {
        if (std::ranges::find(unique, k) == unique.end()) unique.push_back(k);
    }
    const std::scoped_lock lock(wanted_mutex_);
    wanted_ = std::move(wanted);
    ahead_ = taken;
    in_view_ = std::move(in_view);
    levels_ = std::move(levels);
    levels_drawn_ = std::move(unique);
}

std::vector<tiles::TileKey> World::want_levels(const view::View& view, const style::CameraState& camera,
                                               const view::GroundFrame& frame) const {
    const Frustum frustum(view.camera(), 40.0, far_);
    const std::int32_t lat = tiles::grid_round(camera.latitude);
    const std::int32_t lon = tiles::grid_round(camera.longitude);
    std::vector<tiles::TileKey> keys;
    // The far level's tiles in view first (the whole view drawn soon: its ground and its large buildings, and the
    // coarse level's stand-in), then the coarse level's within its reach with a place beyond the finest level's.
    for (const std::uint8_t z : {tiles::Store::far_level, tiles::Store::coarse_level}) {
        const tiles::TileKey centre = tiles::tile_of(z, lat, lon);
        // an L13 tile is 40,075 km · cos(lat) / 2^13 wide, an L12 tile twice that
        const double width =
            40'075'016.686 * pymath::cos(pymath::radians(camera.latitude)) / static_cast<double>(1U << z);
        const auto reach = static_cast<std::int64_t>(std::ceil(far_ / std::max(width, 1.0))) + 1;
        const std::int64_t last = (std::int64_t{1} << z) - 1;
        struct Seen {
            double share = 0;
            double distance = 0;
            tiles::TileKey key;
        };
        std::vector<Seen> seen;
        for (std::int64_t y = centre.y - reach; y <= centre.y + reach; ++y) {
            for (std::int64_t x = centre.x - reach; x <= centre.x + reach; ++x) {
                if (x < 0 || y < 0 || x > last || y > last) continue;
                const tiles::TileKey key(z, static_cast<std::uint32_t>(x), static_cast<std::uint32_t>(y));
                const std::array<std::int32_t, 4> b = tiles::tile_box(key);
                const Box box = box_of({.south = b[0], .west = b[1], .north = b[2], .east = b[3]}, 3000, frame);
                if (!frustum.sees(box) || !store_.find(key).empty()) continue;
                const double d = frustum.distance(box);
                if (z == tiles::Store::coarse_level &&
                    (d > options_.coarse_reach || d + (width * 1.4143) <= options_.fine_reach)) {
                    continue;
                }
                seen.push_back({.share = frustum.share(box), .distance = d, .key = key});
            }
        }
        // by their share of the screen, then nearest first (stable: the scan's order on a tie, on every libc++)
        std::ranges::stable_sort(seen, [](const Seen& a, const Seen& b) {
            return a.share != b.share ? a.share > b.share : a.distance < b.distance;
        });
        for (const Seen& s : seen) keys.push_back(s.key);
    }
    return keys;
}

std::vector<tiles::TileKey> World::wanted_levels() const {
    const std::scoped_lock lock(wanted_mutex_);
    return levels_;
}

std::vector<tiles::TileKey> World::levels_in_view() const {
    const std::scoped_lock lock(wanted_mutex_);
    return levels_drawn_;
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
    for (const std::shared_ptr<const tiles::StorePack>& n : neighbours_) {
        if (n) pack_stage_->want(n);
    }
    if (incoming_) pack_stage_->want(incoming_);
    pack_stage_->take(arrived_);
    if (arrived_.empty() && active_ == last_active_ && coarser_ == last_coarser_ && neighbours_ == last_neighbours_) {
        return;
    }
    last_active_ = active_;
    last_coarser_ = coarser_;
    last_neighbours_ = neighbours_;
    for (std::shared_ptr<const ResidentPack>& b : arrived_) packs_.push_back(std::move(b));
    arrived_.clear();
    const std::vector<std::shared_ptr<const tiles::StorePack>> open = store_.packs();
    std::erase_if(packs_, [&open](const std::shared_ptr<const ResidentPack>& p) {
        return std::ranges::find(open, p->source) == open.end();
    });
}

void World::take_neighbour_packs() noexcept {
    for (std::size_t i = 0; i < max_neighbours; ++i) neighbour_packs_.at(i) = built(neighbours_.at(i));
}

void World::update(const frame::FrameState& state) {
    const double seconds = options_.clock ? options_.clock() : state.seconds;
    // what the last frame's make_room let go of, before any cover of this frame can draw it
    for (const auto& [key, pack] : going_) {
        if (const ResidentTile* t = residency_->find(key, pack)) {
            const std::uint32_t handle = t->handle;
            std::erase_if(last_drawn_, [handle](const auto& p) { return p.first == handle; });
            residency_->release(key, pack, items_, state.number);
        }
    }
    going_.clear();
    taken_.clear();
    residency_->take_published(items_, &taken_);
    if (!active_ || !state.view) {
        // nothing drawn: no coarser pack, and nothing kept of the packs' stage T but what the drawn ones would need
        choose_coarser();
        neighbours_ = {};
        take_packs();
        take_neighbour_packs();
        cover_.update(state.view ? *state.view : view::View({}, {}), frames_, {}, *residency_, seconds);
        // without a pack the view looks at its target from a frame centred on it: the windows wanted there
        far_ = far_of(state.camera.distance);
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
    far_ = far_of(state.placed ? state.placed->dist() : state.camera.distance);
    // The items in the frame drawn, the coarser pack's masked: every resident tile's when the frame, the coarser pack
    // or the mask changed, else the tiles just taken.
    choose_coarser();
    choose_neighbours(state.camera);
    take_packs();
    take_neighbour_packs();
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
    const auto plan_budget =
        static_cast<std::uint64_t>(options_.plan_fraction * static_cast<double>(options_.heaps.total));
    std::array<std::uint8_t, max_neighbours> neighbour_tolerances{};
    for (std::size_t i = 0; i < max_neighbours; ++i) {
        if (const std::shared_ptr<const tiles::StorePack>& n = neighbours_.at(i)) {
            neighbour_tolerances.at(i) = n->pack.head()->tolerance_of(std::nullopt, dist).value_or(std::uint8_t{0});
        }
    }
    const CoverInputs in{
        .pack = active_.get(),
        .pinned = snapped.has_value(),
        .tolerance = head.tolerance_of(snapped, dist).value_or(0),
        .fine_reach = options_.fine_reach,
        .coarse_reach = options_.coarse_reach,
        .far = far_,
        .fine_far = options_.far,
        .margin_px = 40.0,
        .fade_seconds = 0.18,
        .coarser = coarser_.get(),
        .coarser_tolerance = coarser_
                                 ? coarser_->pack.head()->tolerance_of(std::nullopt, dist).value_or(std::uint8_t{0})
                                 : std::uint8_t{0},
        .mask = mask_,
        .neighbours = neighbours(),
        .neighbour_tolerances = neighbour_tolerances,
        .budget = plan_budget,
    };
    cover_.update(*state.view, frames_, in, *residency_, seconds);
    want_windows(*state.view, state.camera, frames_.current().value_or(view::frame_of(head.frame)), seconds);
    // The sun of the pack drawn, of the coarser pack around it and of the neighbours beside it, each in its own lane.
    if (sun_) {
        sun_->prepare(state, active_, in.tolerance, cover_, coarser_, in.coarser_tolerance, neighbours_,
                      in.neighbour_tolerances);
    }
    asked_.assign(cover_.requests().begin(), cover_.requests().end());
    // A switch waiting (choose): the named pack's tiles in view wanted after the frame's own; once they are resident
    // and its own stage T is built, or after incoming_hold seconds, the next frame draws it.
    if (incoming_) {
        if (incoming_since_ < 0) incoming_since_ = seconds;
        const tiles::RenderHead* incoming_head = incoming_->pack.head();
        if (incoming_head != nullptr) {
            const CoverInputs incoming_in{.pack = incoming_.get(),
                                          .tolerance =
                                              incoming_head->tolerance_of(std::nullopt, dist).value_or(std::uint8_t{0}),
                                          .fine_reach = options_.fine_reach,
                                          .coarse_reach = options_.coarse_reach,
                                          .far = far_,
                                          .fine_far = options_.far,
                                          .margin_px = in.margin_px,
                                          .fade_seconds = in.fade_seconds};
            incoming_cover_.update(*state.view, frames_, incoming_in, *residency_, seconds);
            const std::size_t own = asked_.size();
            for (const Request& r : incoming_cover_.requests()) {
                const auto first = asked_.begin();
                const auto last = asked_.begin() + static_cast<std::ptrdiff_t>(own);
                if (std::any_of(first, last, [&r](const Request& w) { return w.key == r.key && w.pack == r.pack; }))
                    continue;
                asked_.push_back(r);
            }
        }
        incoming_ready_ = incoming_head == nullptr || (incoming_cover_.missing() == 0 && built(incoming_) != nullptr) ||
                          seconds - incoming_since_ >= incoming_hold;
    }
    // The fling's landing (gain 26): its view as the frame would draw it there, and its visible tiles wanted after the
    // frame's own, in the prefetch lane ahead of any other prefetch.
    if (landing_ && ladder_) {
        style::CameraState there = state.camera;
        there.latitude = landing_->latitude;
        there.longitude = landing_->longitude;
        there.bearing = landing_->bearing;
        there.pitch = landing_->pitch;
        there.distance = landing_->distance;
        const view::Placed placed = ladder_->place(view::live_view(there));
        CoverInputs landing_in = in;
        landing_in.pinned = placed.view().has_value();
        landing_in.tolerance = head.tolerance_of(placed.view(), placed.dist()).value_or(0);
        landing_in.far = far_of(placed.dist());
        landing_cover_.update(view::View(placed.camera(), state.view->device()), frames_, landing_in, *residency_,
                              seconds);
        const std::size_t own = asked_.size();
        for (Request r : landing_cover_.requests()) {
            if (r.lane != Lane::visible) continue;
            const auto first = asked_.begin();
            const auto last = asked_.begin() + static_cast<std::ptrdiff_t>(own);
            if (std::any_of(first, last, [&r](const Request& w) { return w.key == r.key && w.pack == r.pack; }))
                continue;
            r.lane = Lane::prefetch;
            r.priority += 2.0; // above a frustum share (at most 1) and the cover's other terms
            asked_.push_back(r);
        }
    }
    make_room();
    residency_->want(asked_);
    // the tiles drawn, those kept under the places in view (each place's coarsest), and a waiting switch's resident
    // ones, are not idle
    const auto touch = [this, seconds](std::uint32_t handle) {
        const auto it = std::ranges::find_if(last_drawn_, [handle](const auto& p) { return p.first == handle; });
        if (it != last_drawn_.end()) {
            it->second = seconds;
        } else {
            last_drawn_.emplace_back(handle, seconds);
        }
    };
    for (const CoverTile& t : cover_.tiles()) touch(t.drawn->handle);
    for (const std::uint32_t handle : cover_.kept()) touch(handle);
    if (incoming_) {
        for (const CoverTile& t : incoming_cover_.tiles()) touch(t.drawn->handle);
    }
    release_idle(seconds, state.number);
    // a waiting switch keeps the frames coming until it is drawn (at most incoming_hold seconds)
    animating_ = cover_.fading() || frames_.weight(seconds) < 1.0 || (sun_ && sun_->fading()) || incoming_ != nullptr;
}

void World::make_room() {
    // The cover's own requests (a waiting switch's and the landing's follow them), in the order the residency builds
    // them: by lane, then priority (Residency::want).
    const std::size_t own = cover_.requests().size();
    if (own == 0 || own > asked_.size()) return;
    const std::span<Request> mine = std::span(asked_).first(own);
    std::vector<std::size_t> order(own);
    std::ranges::iota(order, std::size_t{0});
    std::ranges::stable_sort(order, [&mine](std::size_t a, std::size_t b) {
        return mine[a].lane != mine[b].lane ? mine[a].lane < mine[b].lane : mine[a].priority > mine[b].priority;
    });
    const std::vector<Residency::Wait> waits = residency_->waits(mine);
    // A tile resident already takes no more room: the cover asks for a place's coarser level while the place's own
    // comes, resident or not (Residency::want passes over it). Counted, the Westerkerk's L12 under its places' L13
    // took the room first from 12 km and held back every other tile, while the residency built nothing.
    std::vector<bool> resident(own, false);
    for (std::size_t i = 0; i < own; ++i) resident[i] = residency_->find(mine[i].key, mine[i].pack) != nullptr;
    const auto left = [&](std::size_t i) -> std::uint64_t {
        if (resident[i]) return 0;
        return std::max(mine[i].bytes > waits[i].placed ? mine[i].bytes - waits[i].placed : 0, waits[i].need);
    };
    // Each in turn while the heaps' free bytes hold what the cover expects of it and of those before it (less what a
    // build of it under way has placed; a tile that found no room, the bytes it asked for); h: the first that does not
    // fit, or waits for a range to go back to the heaps whatever they hold free.
    const std::uint64_t free = heaps_->free_bytes();
    std::uint64_t taken = 0;
    std::size_t h = own;
    for (std::size_t j = 0; j < own; ++j) {
        if (!waits[order[j]].stuck && taken + left(order[j]) <= free) {
            taken += left(order[j]);
            continue;
        }
        h = j;
        break;
    }
    if (h == own) return;
    // The one the room goes to first: the coarsest of those left (ties: the residency's order), whose tile stands in
    // at the most places once resident (zooming out, a frame cell's L12 over its sixteen places' windows, which may
    // then go without a hole).
    std::size_t first = h;
    for (std::size_t j = h + 1; j < own; ++j) {
        if (!resident[order[j]] && mine[order[j]].key.z < mine[order[first]].key.z) first = j;
    }
    const Request& wanted = mine[order[first]];
    const std::uint64_t need = left(order[first]);
    // stuck, it waits for ranges to go back: the bytes free hold no piece for it
    const bool stuck = waits[order[first]].stuck;
    const std::span<const Cover::StandIn> stand_ins = cover_.stand_ins();
    // the stand-ins' tiles, each with its places
    struct Standing {
        tiles::TileKey key;
        std::string_view pack;
        std::uint64_t bytes = 0;
        std::vector<std::size_t> at; // its stand_ins' entries
        bool coming = false;         // goes once the tiles asked for before h come, as they stand in at no place then
        bool goes = false;
    };
    std::vector<Standing> standing;
    for (std::size_t i = 0; i < stand_ins.size(); ++i) {
        const Cover::StandIn& s = stand_ins[i];
        auto it =
            std::ranges::find_if(standing, [&s](const Standing& t) { return t.key == s.key && t.pack == s.pack; });
        if (it == standing.end()) {
            standing.push_back({.key = s.key, .pack = s.pack, .bytes = s.bytes});
            it = std::prev(standing.end());
        }
        it->at.push_back(i);
    }
    const auto asked_before = [&](const tiles::TileKey& k, std::string_view pack) {
        return std::any_of(order.begin(), order.begin() + static_cast<std::ptrdiff_t>(h),
                           [&](std::size_t i) { return mine[i].key == k && mine[i].pack == pack; });
    };
    const auto backs = [&stand_ins](const Standing& t) {
        return std::ranges::any_of(stand_ins,
                                   [&t](const Cover::StandIn& s) { return s.backup == t.key && s.pack == t.pack; });
    };
    std::uint64_t arriving = 0;
    for (Standing& t : standing) {
        t.coming = !backs(t) && std::ranges::all_of(t.at, [&](std::size_t i) {
            return asked_before(stand_ins[i].wanted, stand_ins[i].pack);
        });
        if (t.coming) arriving += t.bytes;
    }
    // What goes, as the next frame begins: the tiles of the places in view that nothing draws, stands in or would.
    std::uint64_t going = 0;
    for (const Cover::StandIn& t : cover_.spare()) {
        going_.emplace_back(t.key, std::string(t.pack));
        going += t.bytes;
    }
    // A tile whose largest range finds no piece though the heaps hold its bytes free: what goes back joins a piece for
    // it only where it lies beside one. The tiles nothing draws, keeps or asks for go, and the stand-ins whose places
    // all keep another resident level, those that join the largest piece first, until a piece holds it
    // (Heaps::joined_with); and it is asked for once that piece has come, those after it held back meanwhile. Where
    // they cannot make one, the stand-ins go as for a tile short of bytes (below). (Back at 5 km after a pinch out to
    // 12 km, alpha 24's Alkmaar's window 14/8411/5362, 6.05 MB of walls, found no piece with 37.7 MB free and waited
    // for good: the levels had left the pieces between the windows.)
    if (stuck && waits[order[first]].refused > heaps_->largest_free(waits[order[first]].kind)) {
        std::vector<std::pair<tiles::TileKey, std::string_view>> backed;
        for (const Standing& t : standing) {
            if (std::ranges::all_of(t.at, [&stand_ins](std::size_t i) { return stand_ins[i].backup.has_value(); }))
                backed.emplace_back(t.key, t.pack);
        }
        if (piece_for(waits[order[first]].refused, waits[order[first]].kind, backed)) {
            for (std::size_t j = h; j < own; ++j) mine[order[j]].held = true;
            for (std::size_t i = own; i < asked_.size(); ++i) asked_[i].held = true;
            return;
        }
    }
    const HeapStats heaps = heaps_->stats();
    const auto coming = [&]() -> std::uint64_t {
        if (stuck) return heaps.retired_bytes + going + arriving;
        const std::uint64_t all = free + heaps.retired_bytes + going + arriving;
        return all > taken ? all - taken : 0;
    };
    // Then, while the room coming is short of it, stand-ins one by one: those whose places keep a backup first (at
    // its places first, whose own tile comes next; then elsewhere), then those at its places, the fewest holes first
    // (zooming in, a level's tile over places whose windows wait for room too); the least seen first in each.
    const auto within = [&wanted](const Cover::StandIn& s) {
        if (s.pack != wanted.pack || s.place.z < wanted.key.z) return false;
        const auto gap = static_cast<std::uint32_t>(s.place.z - wanted.key.z);
        return (s.place.x >> gap) == wanted.key.x && (s.place.y >> gap) == wanted.key.y;
    };
    const auto kept = [&standing](const tiles::TileKey& k, std::string_view pack) {
        const auto it = std::ranges::find_if(standing, [&](const Standing& t) { return t.key == k && t.pack == pack; });
        return it == standing.end() || !it->goes;
    };
    const std::size_t stand_ins_from = going_.size();
    std::vector<bool> backed; // whether each stand-in let go of has another resident level at its places, at first's
    bool later = false;       // a stand-in left for the tiles asked for before it, whose coming fills its other places
    while (coming() < need) {
        Standing* best = nullptr;
        std::tuple<int, std::size_t, double> best_rank{};
        for (Standing& t : standing) {
            if (t.coming || t.goes) continue;
            std::size_t holes = 0;
            bool at = false;
            bool holes_within = true;
            bool soon = false;
            double share = 0;
            for (const std::size_t i : t.at) {
                const Cover::StandIn& s = stand_ins[i];
                const bool hole = !s.backup || !kept(*s.backup, s.pack);
                holes += hole ? 1 : 0;
                at = at || within(s);
                if (hole && !within(s)) {
                    holes_within = false;
                    soon = soon || asked_before(s.wanted, s.pack);
                }
                share = std::max(share, s.share);
            }
            if (holes > 0 && !at) continue; // its holes would wait for tiles not asked for yet
            if (!holes_within && soon) {    // it goes once the tiles asked for before it have come
                later = true;
                continue;
            }
            const int kind = holes == 0 ? (at ? 0 : 1) : (holes_within ? 2 : 3);
            const std::tuple<int, std::size_t, double> rank{kind, holes, share};
            if (best == nullptr || rank < best_rank) {
                best = &t;
                best_rank = rank;
            }
        }
        if (best == nullptr) break;
        best->goes = true;
        going_.emplace_back(best->key, std::string(best->pack));
        backed.push_back(std::get<0>(best_rank) == 0);
        going += best->bytes;
    }
    if (coming() < need) {
        // what can go is short of it: the stand-ins that would leave holes stay (they would leave them for nothing),
        // those at its places with another resident level there go (the place draws that level, which may go in its
        // turn: zooming in, an L13 standing in for a window, then the L12 behind it), the spare tiles go, and it waits
        // for the tiles asked for before it where their coming lets a stand-in go; else every tile is asked for as
        // before (it may yet find a piece of the heaps; else it waits for a free)
        std::size_t kept_going = stand_ins_from;
        for (std::size_t i = 0; i < backed.size(); ++i) {
            if (backed[i]) going_[kept_going++] = std::move(going_[stand_ins_from + i]);
        }
        going_.resize(kept_going);
        if (!later) return;
    }
    // It is asked for now where it fits, else once the room has come; those after h wait for the room it leaves (and
    // a waiting switch's and the landing's, after the frame's own).
    const bool fits = !stuck && taken + need <= free;
    for (std::size_t j = h; j < own; ++j) {
        if (j != first || !fits) mine[order[j]].held = true;
    }
    for (std::size_t i = own; i < asked_.size(); ++i) asked_[i].held = true;
}

bool World::piece_for(std::uint32_t bytes, HeapKind kind,
                      std::span<const std::pair<tiles::TileKey, std::string_view>> stand_ins) {
    const auto ranges_of = [](const ResidentTile& t, std::vector<HeapRange>& out) {
        std::uint64_t n = 0;
        for (const PassTile& p : t.passes) {
            for (const HeapRange& r : p.ranges) {
                if (!r) continue;
                out.push_back(r);
                n += r.size;
            }
        }
        return n;
    };
    const auto goes = [this](const tiles::TileKey& k, std::string_view pack) {
        return std::ranges::any_of(going_, [&](const auto& g) { return g.first == k && g.second == pack; });
    };
    // what goes already (the spare tiles, as the next frame begins)
    std::vector<HeapRange> back;
    for (const auto& [key, pack] : going_) {
        if (const ResidentTile* t = residency_->find(key, pack)) (void)ranges_of(*t, back);
    }
    if (heaps_->largest_with(back, kind) >= bytes) return true;
    struct Candidate {
        tiles::TileKey key;
        std::string_view pack;
        std::vector<HeapRange> ranges;
        std::uint64_t bytes = 0;
    };
    std::vector<Candidate> candidates;
    // the tiles nothing draws, keeps or asks for (each place's coarsest is kept; a switch's incoming tiles are drawn)
    const auto drawn = [this](std::uint32_t handle) {
        const auto of = [handle](const CoverTile& t) { return t.drawn != nullptr && t.drawn->handle == handle; };
        return std::ranges::any_of(cover_.tiles(), of) ||
               std::ranges::find(cover_.kept(), handle) != cover_.kept().end() ||
               (incoming_ != nullptr && std::ranges::any_of(incoming_cover_.tiles(), of));
    };
    for (const std::shared_ptr<const ResidentTile>& t : residency_->resident()) {
        if (goes(t->key, t->pack) || drawn(t->handle)) continue;
        if (std::ranges::any_of(asked_, [&t](const Request& r) { return r.key == t->key && r.pack == t->pack; }))
            continue;
        Candidate c{.key = t->key, .pack = t->pack};
        c.bytes = ranges_of(*t, c.ranges);
        candidates.push_back(std::move(c));
    }
    // the stand-ins whose places all keep another resident level, which they draw instead
    for (const auto& [key, pack] : stand_ins) {
        if (goes(key, pack)) continue;
        if (const ResidentTile* t = residency_->find(key, pack)) {
            Candidate c{.key = key, .pack = pack};
            c.bytes = ranges_of(*t, c.ranges);
            candidates.push_back(std::move(c));
        }
    }
    // Each in turn the one that joins the largest piece (the fewest bytes of those as good), until a piece holds it.
    std::vector<bool> chosen(candidates.size(), false);
    std::vector<HeapRange> trial;
    while (heaps_->largest_with(back, kind) < bytes) {
        std::optional<std::size_t> best;
        vk::DeviceSize best_piece = 0;
        for (std::size_t c = 0; c < candidates.size(); ++c) {
            if (chosen[c]) continue;
            trial = back;
            trial.insert(trial.end(), candidates[c].ranges.begin(), candidates[c].ranges.end());
            const vk::DeviceSize piece = heaps_->joined_with(trial, kind);
            if (!best || piece > best_piece || (piece == best_piece && candidates[c].bytes < candidates[*best].bytes)) {
                best = c;
                best_piece = piece;
            }
        }
        if (!best) return false; // they cannot make one: none goes
        chosen[*best] = true;
        back.insert(back.end(), candidates[*best].ranges.begin(), candidates[*best].ranges.end());
    }
    std::size_t let_go = 0;
    for (std::size_t c = 0; c < candidates.size(); ++c) {
        if (!chosen[c]) continue;
        going_.emplace_back(candidates[c].key, std::string(candidates[c].pack));
        ++let_go;
    }
    log::info("vk tiles: {} let go for a piece of {} bytes, {} free in pieces of {} at most", let_go, bytes,
              heaps_->free_bytes(), heaps_->largest_free(kind));
    return true;
}

std::string World::tiles_line() const {
    const ResidencyStats r = residency_->stats();
    const HeapStats h = heaps_->stats();
    // the heaps' bytes in use (MB) of their budget, the tiles that found no room and wait for some to go, what the
    // tiles the cover asks for are planned to take (MB; the plan's budget is plan_fraction of the heaps') and the
    // places it gave a coarser level than their distance asks for
    return std::format("vk tiles {} resident {:.1f} of {} build {:.1f} waiting {} planned {:.1f} coarser {} holes {} "
                       "built {} failed {}",
                       r.resident, static_cast<double>(h.total_used()) / 1.0e6, options_.heaps.total >> 20U,
                       r.built > 0 ? r.build_ms / static_cast<double>(r.built) : 0.0, r.waiting,
                       static_cast<double>(cover_.planned()) / 1.0e6, cover_.coarsened(), cover_.holes(), r.built,
                       r.failed);
}

} // namespace atlas::scene
