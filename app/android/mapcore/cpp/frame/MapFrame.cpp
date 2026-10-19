// The app's map in the core (frame/MapFrame.hpp).
#include "frame/MapFrame.hpp"

#include "base/log.hpp"
#include "frame/PassRegistry.hpp"

#include <algorithm>
#include <format>
#include <unistd.h>
#include <utility>

namespace atlas::frame {

namespace {

/// The mode the map draws in until the composite's filters exist (K17a): D16's structural mode, every per-camera rule
/// of the renders with mat, the grain and the cloud shadows off.
constexpr Mode map_mode = Mode::structural;

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

} // namespace

style::ThemeBlock with_profile(style::ThemeBlock theme, tiles::Profile profile) noexcept {
    if (profile == tiles::Profile::living) {
        theme.flags &= ~style::theme_flag::tone;
    } else {
        theme.flags |= style::theme_flag::tone;
    }
    return theme;
}

MapFrame::MapFrame(unsigned workers, std::function<void()> wake)
    : workers_(std::max(1U, workers)), wake_(std::move(wake)), epoch_(std::chrono::steady_clock::now()) {}

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
    // The packs no longer listed, or listed with other bytes, leave the world first.
    for (MapPack& old : packs_) {
        const auto again =
            std::ranges::find_if(packs, [&old](const MapPack& p) { return same_source(p.ref, old.ref); });
        if (again != packs.end()) continue;
        if (world_) (void)world_->store().remove(old.ref.id);
        close_owned(old.ref);
    }
    for (MapPack& p : packs) {
        const auto before = std::ranges::find_if(packs_, [&p](const MapPack& o) { return same_source(o.ref, p.ref); });
        if (before != packs_.end()) {
            // The same bytes: the pack open in the world stays, with the descriptor it was opened from (the new
            // listing's copy is closed), under the number the app gives it now.
            close_owned(p.ref);
            MapPack same = std::move(*before);
            before->ref.fd = -1;
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
    auto builder = FrameBuilder::create(device, cache, make_passes(), FrameOptions{}, watch);
    if (!builder) return std::format("the map's frame: {}", builder.error().text());
    // The world's clock is the map's own: its workers stamp each tile's publication with it, the cover fades by it.
    auto clock = [epoch = epoch_] {
        return std::chrono::duration<double>(std::chrono::steady_clock::now() - epoch).count();
    };
    auto world = scene::World::create(device, (*builder)->passes(), {.workers = workers_, .clock = std::move(clock)});
    if (!world) {
        const gpu::Announced named =
            gpu::announce_release({.kind = "frame", .detail = "(the map's frame builder)"}, watch);
        builder->reset();
        return std::format("the map's world: {}", world.error().text());
    }
    builder_ = std::move(*builder);
    world_ = std::move(*world);
    if (wake_) world_->set_wake(wake_);
    // The packs listed so far open in the new world; one that no longer opens is dropped, saying why.
    std::erase_if(packs_, [this](MapPack& p) {
        const std::string why = open(p);
        if (why.empty()) return false;
        log::warn("vk packs: {}: {}", p.ref.id, why);
        close_owned(p.ref);
        return true;
    });
    world_->set_active(active_id_);
    return {};
}

void MapFrame::release(gpu::PipelineWatch* watch) noexcept {
    if (!made()) return;
    {
        // Its workers stop, its tiles' heaps go.
        const gpu::Announced named = gpu::announce_release({.kind = "frame", .detail = "(the map's world)"}, watch);
        world_.reset();
    }
    const gpu::Announced named = gpu::announce_release({.kind = "frame", .detail = "(the map's frame builder)"}, watch);
    builder_.reset();
}

void MapFrame::next(const style::CameraState& camera, double seconds) {
    if (!made() || !theme_.has_value()) return; // not ready()
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
    if (const tiles::StorePack* pack = world_->active(); pack != nullptr && pack->pack.head() != nullptr) {
        theme = with_profile(theme, pack->pack.head()->profile);
    }
    builder_->set_inputs(
        {.camera = camera, .theme = theme, .seconds = seconds, .mode = map_mode, .scene = world_.get()});
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
    if (!made() || world_->active() == nullptr) return false;
    const scene::Cover& cover = *world_->cover();
    if (cover.tiles().empty() || cover.missing() > 0 || cover.fading()) return false;
    return world_->frames().weight(now()) >= 1.0;
}

std::string_view MapFrame::drawn_pack() const noexcept {
    const tiles::StorePack* pack = made() ? world_->active() : nullptr;
    return pack != nullptr ? std::string_view(pack->id) : std::string_view();
}

std::string MapFrame::tiles_line() const {
    return made() ? world_->tiles_line() : std::string("vk tiles 0 resident 0.0 build 0.0");
}

} // namespace atlas::frame
