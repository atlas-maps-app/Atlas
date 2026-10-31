// The ground's GPU tests' frame (plan-vector K9): the registry's passes and the ground in a frame builder over the
// world of the bundled render packs (ATLAS_TILES_DIR), drawn offscreen on the host's Vulkan in configuration C until
// every tile the cover wants is built and faded in; the frame's pixels, the ground's decisions and counts, and a look
// at the pass and the frame's state before the world goes (`after`).
#pragma once

#if !defined(__ANDROID__)

#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "gpu/PipelineCache.hpp"
#include "passes/Ground.hpp"
#include "scene/World.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <array>
#include <filesystem>
#include <fstream>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace atlas::test {

/// A frame the ground drew.
struct GroundDraw {
    gpu::Pixels pixels;
    passes::GroundFrame decided;
    frame::Count count;
    std::uint32_t kept = 0; ///< frames that kept the last frame's decisions, of `frames`
    gpu::Packed packed;     ///< the frame as the image holds it (10 bits a channel where the device draws them)
};

/// The registry's ground pass among `passes`, or one added when the registry has none.
inline passes::Ground* ground_of(std::vector<std::unique_ptr<frame::Pass>>& passes) {
    for (const std::unique_ptr<frame::Pass>& p : passes) {
        // NOLINTNEXTLINE(cppcoreguidelines-pro-type-static-cast-downcast): the pass named "ground" is passes::Ground
        if (p->name() == "ground") return static_cast<passes::Ground*>(p.get());
    }
    auto ground = std::make_unique<passes::Ground>();
    passes::Ground* g = ground.get();
    passes.push_back(std::move(ground));
    return g;
}

/// Writes a frame as a binary PPM.
inline void write_ppm(const gpu::Pixels& px, const std::filesystem::path& path) {
    std::filesystem::create_directories(path.parent_path());
    std::ofstream out(path, std::ios::binary);
    out << "P6\n" << px.width << " " << px.height << "\n255\n";
    for (std::size_t i = 0; i < std::size_t{px.width} * px.height; ++i) {
        out.write(reinterpret_cast<const char*>(&px.rgba[i * 4]),
                  3); // NOLINT(cppcoreguidelines-pro-type-reinterpret-cast)
    }
}

/// Draws `camera` with `theme` through the registry's passes and the ground over the bundled packs in `dir`, `pack`
/// the active one, `frames` frames 50 ms apart, in `mode`; `before` sees the pass before the frame builder prepares
/// it, `after` sees it and the last frame's state before the world goes. `globals` (may be null) provides some of set
/// 1's bindings to the world (the noise fields), and `settle` (may be empty) runs after each frame (their worker's
/// wait); `options` are the frame builder's (no composite step by default). With `second_buffer`, the heaps are two
/// buffers of 64 MB and the first is filled before any tile is built, so the tiles' ground and lines lie in the second.
inline std::optional<GroundDraw>
draw_ground(const Session& session, const style::CameraState& camera, const style::ThemeBlock& theme,
            const std::string& dir, const std::string& pack,
            const std::function<void(passes::Ground&, const frame::FrameState&)>& after = {}, int frames = 40,
            const std::function<void(passes::Ground&)>& before = {}, frame::Mode mode = frame::Mode::structural,
            const std::shared_ptr<frame::GlobalProvider>& globals = nullptr, const std::function<void()>& settle = {},
            const frame::FrameOptions& options = {.steps = 0}, bool second_buffer = false) {
    const gpu::Device& device = *session.device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    if (!cache) return std::nullopt;
    // The ground's own passes: the registry's buildings and text would stand over the ground these tests check.
    constexpr std::array<std::string_view, 3> ground_only{"composite", "frosted", "ground"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(ground_only);
    passes::Ground* g = ground_of(passes);
    if (before) before(*g);
    auto builder = frame::FrameBuilder::create(device, *cache, std::move(passes), options);
    if (!builder) return std::nullopt;
    double now = 0;
    // the ground reads no sun bits: the world's sun stays off
    scene::WorldOptions world_options{.workers = 3, .sun = {.enabled = false}, .clock = [&now] { return now; }};
    constexpr vk::DeviceSize part = vk::DeviceSize{64} << 20U;
    if (second_buffer) world_options.heaps = {.total = 2 * part, .binding = part};
    auto world = scene::World::create(device, (*builder)->passes(), world_options);
    if (!world) return std::nullopt;
    if (second_buffer && !(*world)->heaps()->allocate(scene::HeapKind::walls, static_cast<std::uint32_t>(part), 16)) {
        return std::nullopt;
    }
    if (globals && !(*world)->add_globals(globals)) return std::nullopt;
    for (const char* name : {"amsterdam", "grachten"}) {
        const std::filesystem::path path = std::filesystem::path(dir) / (std::string(name) + ".atlas");
        if (!std::filesystem::exists(path)) continue;
        if (!(*world)->store().add({.id = name, .kind = tiles::PackKind::bundled, .path = path.string()})) {
            return std::nullopt;
        }
    }
    (*world)->set_active(pack);
    const vk::Extent2D extent{.width = camera.width_px, .height = camera.height_px};
    auto output = gpu::Offscreen::create(device, extent, 2);
    if (!output) return std::nullopt;
    (*builder)->set_inputs({.camera = camera, .theme = theme, .seconds = 0, .mode = mode, .scene = world->get()});
    const gpu::Configuration config{.config = gpu::FrameConfig::c,
                                    .route = gpu::Route::renderpass,
                                    .form = gpu::FrameForm::composite,
                                    .target = gpu::CompositeTarget::resolve};
    auto renderer = gpu::Renderer::create(device, **output, **builder, config);
    if (!renderer) {
        MESSAGE("renderer: " << renderer.error().text());
        return std::nullopt;
    }
    // frames until every tile the cover wants is built and its fade is over
    std::uint32_t kept = 0;
    for (int f = 0; f < frames; ++f) {
        now = static_cast<double>(f) * 0.05;
        auto report = (*renderer)->draw(0);
        if (!report) {
            MESSAGE("draw: " << report.error().text());
            return std::nullopt;
        }
        if (g->decided().kept) ++kept;
        (*world)->residency().wait_idle();
        if (settle) settle();
    }
    (*renderer)->drain();
    auto packed = (*output)->read_packed((*output)->latest());
    if (!packed) return std::nullopt;
    GroundDraw d{.pixels = gpu::to_rgba8(*packed),
                 .decided = g->decided(),
                 .count = (*builder)->total(),
                 .kept = kept,
                 .packed = std::move(*packed)};
    if (after) after(*g, (*builder)->state());
    renderer->reset();
    return d;
}

} // namespace atlas::test

#endif
