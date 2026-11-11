// The app's map in the core (frame/MapFrame.hpp).
#include "frame/MapFrame.hpp"

#include "base/log.hpp"
#include "frame/PassRegistry.hpp"
#include "fx/FieldCache.hpp"
#include "load/Countries.hpp"
#include "load/PackStyle.hpp"
#include "load/Window.hpp"
#include "passes/Ground.hpp"
#include "passes/Walls.hpp"

#include <algorithm>
#include <format>
#include <span>
#include <string>
#include <unistd.h>
#include <unordered_map>
#include <utility>
#include <vector>

namespace atlas::frame {

namespace {

/// The mode the map draws in: full, every effect the core draws (`mat`'s rule, the grain once the noise fields are
/// bound, the soft shadows), as the engine's references draw the app's frames (likeness/engine.json's mode).
constexpr Mode map_mode = Mode::full;

/// Whether two listings of a pack are the same bytes: the same id, kind, file or range, and identity.
[[nodiscard]] bool same_source(const tiles::PackRef& a, const tiles::PackRef& b) noexcept {
    return a.id == b.id && a.kind == b.kind && a.path == b.path && a.offset == b.offset && a.length == b.length &&
           a.identity == b.identity && (a.fd < 0) == (b.fd < 0);
}

/// Closes a descriptor the map owns (an APK asset's, given by the app).
void close_owned(tiles::PackRef& ref) noexcept {
    if (ref.fd >= 0) ::close(ref.fd);
    ref.fd = -1;
}

/// A listing's places by its packs' ids, each id's in the listing's order: a pack is looked for among the listings of
/// its own id alone (same_source asks the id first), so matching a listing of thousands of windows against the last
/// one takes as many lookups, not their product (each a frame's time on the render thread as each window landed).
[[nodiscard]] std::unordered_map<std::string, std::vector<std::size_t>> places_by_id(std::span<const MapPack> packs) {
    std::unordered_map<std::string, std::vector<std::size_t>> out;
    out.reserve(packs.size());
    for (std::size_t i = 0; i < packs.size(); ++i) out[packs[i].ref.id].push_back(i);
    return out;
}

} // namespace

style::ThemeBlock with_profile(style::ThemeBlock theme, tiles::Profile profile) noexcept {
    // the user's Tone switched off (a strength of 0, the Map look's setting) lights every pack without it
    const bool off = theme.has(style::theme_flag::tone_strength) && theme.tone <= 0.0F;
    if (profile == tiles::Profile::living || off) {
        theme.flags &= ~style::theme_flag::tone;
    } else {
        theme.flags |= style::theme_flag::tone;
    }
    return theme;
}

style::ThemeBlock with_pack(style::ThemeBlock theme, const tiles::StorePack& pack) noexcept {
    const tiles::RenderHead* head = pack.pack.head();
    if (head == nullptr) return theme;
    // Where the windows draw the New looks, every pack there lights alike, so the light does not change as the map
    // zooms out from the Westerkerk (the user, 28 October: "The new alpha still has that orange buildings look when
    // zoomed out"): with TONE, the living Westerkerk's too, which the user picked over the living map's light once the
    // New looks had taken the orange away (30 October: "I think it was tone what I liked").
    if (load::draws_new_looks(load::country_of(head->frame.lat0, head->frame.lon0))) {
        return with_profile(theme, tiles::Profile::region);
    }
    return with_profile(theme, head->profile);
}

MapFrame::MapFrame(unsigned workers, std::function<void()> wake, MapOptions options)
    : workers_(std::max(1U, workers)), wake_(std::move(wake)), options_(std::move(options)),
      epoch_(std::chrono::steady_clock::now()) {}

MapFrame::~MapFrame() {
    // The world goes before the descriptors of the packs it mapped (its mappings outlive them anyway).
    world_.reset();
    builder_.reset();
    for (MapPack& p : packs_) close_owned(p.ref);
}

double MapFrame::now() const noexcept {
    return std::chrono::duration<double>(std::chrono::steady_clock::now() - epoch_).count();
}

std::string MapFrame::open(const MapPack& pack) {
    if (!world_) return {};
    auto added = world_->store().add(pack.ref);
    if (!added) return tiles::describe(added.error());
    return {};
}

std::string MapFrame::set_packs(std::vector<MapPack> packs) {
    std::string out;
    std::vector<MapPack> kept;
    kept.reserve(packs.size());
    // The listing's windows join and leave their frame cells, and each cell they change is composed and opened once,
    // at its end (tiles::Store::hold), however many of its windows it brings.
    if (world_) world_->store().hold();
    // The packs no longer listed, or listed with other bytes, leave the world first.
    {
        const auto listed = places_by_id(packs);
        for (MapPack& old : packs_) {
            const auto at = listed.find(old.ref.id);
            if (at != listed.end() &&
                std::ranges::any_of(at->second, [&](std::size_t i) { return same_source(packs[i].ref, old.ref); })) {
                continue;
            }
            if (world_) (void)world_->store().remove(old.ref.id);
            close_owned(old.ref);
        }
    }
    const auto open_before = places_by_id(packs_);
    std::vector<bool> taken(packs_.size(), false); // an earlier listing's pack kept already, once for one listed now
    for (MapPack& p : packs) {
        std::size_t before = packs_.size(); // none
        if (const auto at = open_before.find(p.ref.id); at != open_before.end()) {
            for (const std::size_t i : at->second) {
                if (taken[i] || !same_source(packs_[i].ref, p.ref)) continue;
                before = i;
                break;
            }
        }
        if (before < packs_.size()) {
            // The same bytes: the pack open in the world stays, with the descriptor it was opened from (the new
            // listing's copy is closed), under the number the app gives it now.
            taken[before] = true;
            close_owned(p.ref);
            MapPack same = std::move(packs_[before]);
            packs_[before].ref.fd = -1;
            same.number = p.number;
            kept.push_back(std::move(same));
            out += std::format("{} kept\n", kept.back().ref.id);
            continue;
        }
        const std::string why = open(p);
        if (!why.empty()) {
            // Not opened: not kept (its descriptor closed), so the next listing tries it again.
            out += std::format("{}: {}\n", p.ref.id, why);
            log::warn("vk packs: {}: {}", p.ref.id, why);
            close_owned(p.ref);
            continue;
        }
        out += std::format("{} {}\n", p.ref.id, world_ ? "opened" : "listed");
        kept.push_back(std::move(p));
    }
    if (world_) world_->store().release();
    packs_ = std::move(kept);
    // A number the app no longer lists leaves the choice to the world.
    if (active_number_ != 0 &&
        std::ranges::none_of(packs_, [this](const MapPack& p) { return p.number == active_number_; })) {
        active_number_ = 0;
        active_id_.clear();
        if (world_) world_->set_active({});
    }
    return out;
}

std::string MapFrame::make(const gpu::Device& device, const gpu::PipelineCache& cache, gpu::PipelineWatch* watch) {
    if (made()) return {};
    FrameOptions frame_options{.steps = steps_,
                               .grain = grain_,
                               .grounded = true,
                               .passes = passes_,
                               .render_threads = options_.render_threads};
    if (options_.clear) frame_options.clear = *options_.clear;
    auto builder = FrameBuilder::create(device, cache, make_passes(), frame_options, watch);
    if (!builder) return std::format("the map's frame: {}", builder.error().text());
    // The world's clock is the map's own: its workers stamp each tile's publication with it, the cover fades by it.
    auto clock = [epoch = epoch_] {
        return std::chrono::duration<double>(std::chrono::steady_clock::now() - epoch).count();
    };
    // The passes' own providers of set 1's globals come with the world, made with the map's resources.
    // The heaps' two buffers, each as large as the device binds (scene::heap_budget), and the resident tiles' native
    // bytes alike
    scene::HeapBudget heaps = scene::heap_budget(device);
    if (options_.heaps_total != 0) heaps.total = options_.heaps_total;
    log::info("vk heaps: {} MB budget, {} MB a buffer at most (the device binds {} MB of a storage buffer)",
              heaps.total >> 20U, std::min(heaps.binding, heaps.total) >> 20U,
              device.facts().limits.maxStorageBufferRange >> 20U);
    // the triangle governor's budget, by the heaps' (ENGINE.md step 5)
    const std::uint64_t triangles =
        options_.triangle_budget != 0 ? options_.triangle_budget : scene::triangle_budget(heaps.total);
    log::info("vk governor: {:.1f} M triangles a frame before the levels come nearer",
              static_cast<double>(triangles) / 1.0e6);
    auto world = scene::World::create(
        device, (*builder)->passes(),
        {.workers = workers_,
         .levels_far = options_.levels_far != 0 ? options_.levels_far : scene::WorldOptions{}.levels_far,
         .fade_seconds = options_.fade_seconds != 0 ? options_.fade_seconds : scene::WorldOptions{}.fade_seconds,
         .triangle_budget = triangles,
         .heaps = heaps,
         .native_budget = static_cast<std::size_t>(heaps.total),
         .resources = options_.resources,
         .clock = std::move(clock),
         .hints = options_.worker_hints ? options_.worker_hints() : nullptr,
         .world_style = load::world_style()});
    if (!world) {
        const gpu::Announced named =
            gpu::announce_release({.kind = "frame", .detail = "(the map's frame builder)"}, watch);
        builder->reset();
        return std::format("the map's world: {}", world.error().text());
    }
    builder_ = std::move(*builder);
    world_ = std::move(*world);
    world_->store().set_cache(world_cache_); // before the packs open in it: a remote pack's head is kept there
    (void)apply_shape_records();
    if (wake_) {
        world_->set_wake(wake_);
        // a new moment's light, done ahead off the render thread, wakes the frame that takes it (gain 5)
        builder_->set_wake(wake_);
    }
    world_->set_landing(landing_);
    // The coarser pack around the one drawn: the smallest listed pack that holds it (the canal ring around the
    // Westerkerk), D3.
    world_->set_coarser_enclosing(true);
    // The noise fields and the byte tables (K17a), kept under the app's cache: without them the effects that read them
    // (the grain) stay stubbed and the rest of the frame draws as before, so their failure only says so. Where the
    // session has the GPU's noise on, a size the disk does not hold is made on the GPU before its first frame
    // (noise_due, make_noise).
    auto fields = fx::FieldCache::create(
        device, {.dir = options_.cache_dir.empty() ? std::filesystem::path() : options_.cache_dir / "fields",
                 .gpu = gpu_noise_,
                 .pipelines = &cache,
                 .watch = watch});
    fields_ = nullptr;
    if (!fields) {
        log::warn("vk map: the noise fields: {}", fields.error().text());
    } else {
        if (wake_) (*fields)->set_wake(wake_);
        fx::FieldCache* held = fields->get();
        if (auto added = world_->add_globals(std::shared_ptr<GlobalProvider>(std::move(*fields))); !added) {
            log::warn("vk map: the noise fields: {}", added.error().text());
        } else {
            fields_ = held;
        }
    }
    // The packs listed so far open in the new world, each frame cell composed once; one that no longer opens is
    // dropped, saying why.
    world_->store().hold();
    std::erase_if(packs_, [this](MapPack& p) {
        const std::string why = open(p);
        if (why.empty()) return false;
        log::warn("vk packs: {}: {}", p.ref.id, why);
        close_owned(p.ref);
        return true;
    });
    world_->store().release();
    world_->set_active(active_id_);
    return {};
}

void MapFrame::set_screen(bool haze, bool vignette, bool grain) noexcept {
    steps_ = ~0U;
    if (!haze) steps_ &= ~shaders::COMPOSITE_HAZE;
    if (!vignette) steps_ &= ~shaders::COMPOSITE_VIGNETTE;
    if (!grain) steps_ &= ~shaders::COMPOSITE_GRAIN;
    grain_ = grain;
    if (builder_) {
        builder_->set_steps(steps_);
        builder_->set_grain(grain_);
    }
}

std::string MapFrame::set_shape_records(bool on) {
    shape_records_ = on;
    return apply_shape_records();
}

std::string MapFrame::apply_shape_records() {
    if (!builder_) return "no map";
    for (const std::unique_ptr<Pass>& p : builder_->passes()) {
        if (p->name() != "buildings") continue;
        // NOLINTNEXTLINE(cppcoreguidelines-pro-type-static-cast-downcast): "buildings" is passes::Buildings
        auto& buildings = static_cast<passes::Buildings&>(*p);
        buildings.most_shape_chunks(shape_records_ ? std::nullopt : std::optional<std::uint32_t>{0});
        // what the device made is known once the passes are prepared, for the frames' output: by their first frame
        if (!builder_->holds_prepared()) return "no frame drawn yet (the passes not prepared)";
        return std::string(buildings.records_why());
    }
    return "no buildings pass";
}

void MapFrame::release(gpu::PipelineWatch* watch) noexcept {
    if (!made()) return;
    {
        // Its workers stop, its tiles' heaps go.
        const gpu::Announced named = gpu::announce_release({.kind = "frame", .detail = "(the map's world)"}, watch);
        fields_ = nullptr;
        world_.reset();
    }
    const gpu::Announced named = gpu::announce_release({.kind = "frame", .detail = "(the map's frame builder)"}, watch);
    builder_.reset();
}

void MapFrame::next(const style::CameraState& camera, double seconds) {
    if (!made() || !theme_.has_value()) return; // not ready()
    seconds_ = seconds;
    // The pack the app names for this camera, else the world's own choice.
    if (camera.pack_id != active_number_) {
        active_number_ = camera.pack_id;
        const auto named =
            std::ranges::find_if(packs_, [&camera](const MapPack& p) { return p.number == camera.pack_id; });
        active_id_ = named != packs_.end() ? named->ref.id : std::string();
        world_->set_active(active_id_);
    }
    // The world's choice for this camera, so the theme carries the profile of the pack drawn (the builder asks again,
    // with the same answer).
    (void)world_->ladder(camera);
    style::ThemeBlock theme = *theme_;
    if (const tiles::StorePack* pack = world_->active(); pack != nullptr) theme = with_pack(theme, *pack);
    builder_->set_inputs({.camera = camera,
                          .theme = theme,
                          .seconds = seconds,
                          .mode = map_mode,
                          .scene = world_.get(),
                          .labels = labels_.get(),
                          .names = &names_,
                          .extra_names = extra_names_,
                          .markers = markers_.get(),
                          .routes = routes_.get(),
                          .asked = asked_,
                          .palette = &palette_});
}

std::optional<scene::Picked> MapFrame::pick(double x, double y) {
    if (!made() || !world_) return std::nullopt;
    const FrameState& state = builder_->state();
    if (!state.view.has_value() || !state.ground_frame.has_value()) return std::nullopt;
    // the packs the frame drew from, at the zoom of their chunks it drew
    std::vector<scene::PickPack> packs;
    for (const auto& tile : world_->residency().resident()) {
        if (tile == nullptr) continue;
        const bool known = std::ranges::any_of(
            packs, [&](const scene::PickPack& p) { return p.id == tile->pack && p.z == tile->key.z; });
        if (!known) packs.push_back({.id = tile->pack, .z = tile->key.z});
    }
    // a guessed roof's shape as the frame draws it: the plain box in its place where the palette has the guesses off
    const bool plain_roofs = state.palette != nullptr && state.palette->has(style::palette_flag::no_guesses);
    return scene::pick_building(world_->store(), packs, *state.ground_frame, state.view->camera().eye(),
                                scene::ray_of(*state.view, x, y), plain_roofs);
}

std::optional<scene::PickedGround> MapFrame::pick_ground(double x, double y) {
    if (!made() || !world_) return std::nullopt;
    for (const std::unique_ptr<Pass>& p : builder_->passes()) {
        if (p->name() != "ground") continue;
        // NOLINTNEXTLINE(cppcoreguidelines-pro-type-static-cast-downcast): "ground" is passes::Ground
        const auto& ground = static_cast<const passes::Ground&>(*p);
        return ground.pick(builder_->state(), x, y);
    }
    return std::nullopt;
}

bool MapFrame::noise_due(const style::CameraState& camera) const {
    if (fields_ == nullptr || camera.width_px == 0 || camera.height_px == 0 || !(camera.density > 0.0)) return false;
    return fields_->gpu_due(fx::size_of(camera.width_px, camera.height_px, camera.density));
}

bool MapFrame::make_noise(const style::CameraState& camera) {
    if (!noise_due(camera)) return true;
    return fields_->make_on_gpu(fx::size_of(camera.width_px, camera.height_px, camera.density));
}

standin::MapCounts MapFrame::counts() const noexcept {
    if (!made()) return {};
    const Count c = builder_->total();
    return {.stage_f_ms = builder_->stage_f_ms(),
            .record_ms = builder_->record_ms(),
            .draws = c.draws,
            .triangles = c.triangles};
}

bool MapFrame::complete() const noexcept {
    if (!made()) return false;
    // beyond the countries' reach, the world's picture (ENGINE.md step 5): final once every tile in view draws itself
    // and has faded in (no frame waits for one meanwhile: its ancestors draw, down to the globe's resident levels)
    const scene::WorldFrame* w = world_->world_frame();
    const bool world_final = w == nullptr || (w->wanted == 0 && !w->fading);
    const bool world_draws = w != nullptr && !w->draws.empty();
    if (world_->active() == nullptr && world_->incoming() == nullptr) return world_draws && world_final;
    // a switch waiting for the named pack's tiles (scene::World::incoming) is not the camera's final picture yet
    if (world_->active() == nullptr || world_->incoming() != nullptr || !world_final) return false;
    // a cover of no tile is final only where it wants none and the world draws the camera: beyond the countries' reach
    // (100 km over the canal ring, whose pack still holds the target) the world's picture is the whole one
    // (and a part come back on is drawn once its top-ups are taken and faded in: Cover::lacking, Cover::fading)
    const scene::Cover& cover = *world_->cover();
    if ((cover.tiles().empty() && !world_draws) || cover.missing() > 0 || cover.lacking() > 0 || cover.fading())
        return false;
    return world_->frames().weight(now()) >= 1.0;
}

std::string_view MapFrame::drawn_pack() const noexcept {
    const tiles::StorePack* pack = made() ? world_->active() : nullptr;
    return pack != nullptr ? std::string_view(pack->id) : std::string_view();
}

std::string MapFrame::tiles_line() const {
    return made() ? world_->tiles_line() : std::string("vk tiles 0 resident 0.0 build 0.0");
}

std::vector<tiles::TileKey> MapFrame::wanted_windows() const {
    return world_ ? world_->wanted_windows() : std::vector<tiles::TileKey>{};
}

std::vector<tiles::TileKey> MapFrame::windows_in_view() const {
    return world_ ? world_->windows_in_view() : std::vector<tiles::TileKey>{};
}

std::vector<tiles::TileKey> MapFrame::wanted_levels() const {
    return world_ ? world_->wanted_levels() : std::vector<tiles::TileKey>{};
}

std::vector<tiles::TileKey> MapFrame::levels_in_view() const {
    return world_ ? world_->levels_in_view() : std::vector<tiles::TileKey>{};
}

void MapFrame::set_world_cache(std::shared_ptr<tiles::ExpandedCache> cache) {
    world_cache_ = std::move(cache);
    if (world_) world_->store().set_cache(world_cache_);
}

std::vector<scene::WorldWant> MapFrame::world_wants() const {
    const scene::WorldTiles* tiles = world_ ? world_->world_tiles() : nullptr;
    return tiles != nullptr ? tiles->wants() : std::vector<scene::WorldWant>{};
}

std::vector<tiles::TileKey> MapFrame::world_blocks() const {
    const scene::WorldTiles* tiles = world_ ? world_->world_tiles() : nullptr;
    return tiles != nullptr ? tiles->blocks() : std::vector<tiles::TileKey>{};
}

std::shared_ptr<const tiles::StorePack> MapFrame::world_pack(std::string_view id) const {
    if (!world_) return nullptr;
    for (const auto& p : world_->store().packs()) {
        if (p->id == id) return p;
    }
    return nullptr;
}

} // namespace atlas::frame
