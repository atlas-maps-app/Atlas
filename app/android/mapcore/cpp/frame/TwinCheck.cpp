// The twin's check (frame/TwinCheck.hpp).
#include "frame/TwinCheck.hpp"

#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/MapFrame.hpp"
#include "frame/PassRegistry.hpp"
#include "passes/Walls.hpp"
#include "scene/World.hpp"
#include "sun/Buckets.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Error.hpp"
#include "twin/Twin.hpp"

#include <atomic>
#include <cmath>
#include <format>
#include <memory>
#include <utility>

namespace atlas::frame {

namespace {

/// The frames of stage F the world may take to settle (host_frame::draw's).
constexpr int max_settle_frames = 300;

} // namespace

style::CameraState twin_check_camera() noexcept {
    constexpr std::uint32_t width = 1080;
    constexpr std::uint32_t height = 2400;
    constexpr double density = 1080.0 / 412.0;
    style::CameraState c{};
    c.version = style::camera_state_version;
    c.flags = style::camera_flag::rest;
    // 10 m west and 30 m north of the Westerkerk, yaw 52°, 38° down, 430 m
    const double kx = 111320 * pymath::cos(pymath::radians(52.3746));
    c.latitude = 52.3746 + (30.0 / 110540.0);
    c.longitude = 4.8840 - (10.0 / kx);
    c.bearing = 52.0;
    c.pitch = 52.0;
    c.distance = 430.0;
    c.density = density;
    c.width_px = width;
    c.height_px = height;
    c.width_dp = static_cast<std::uint32_t>(std::lround(width / density));
    c.height_dp = static_cast<std::uint32_t>(std::lround(height / density));
    c.principal_x = c.width_dp / 2.0;
    c.principal_y = c.height_dp * 0.48;
    return c;
}

style::ThemeBlock twin_check_theme() noexcept {
    namespace flag = style::theme_flag;
    style::ThemeBlock b{};
    b.version = 1;
    b.flags = flag::weather | flag::shadow_blur | flag::cloud_shadows | flag::water_gradient | flag::wet_set;
    b.year = 2026;
    b.month = 10;
    b.day = 1;
    b.hour = 16;
    b.minute = 0;
    b.moment = style::MomentName::sunny;
    b.weather_code = 2;
    b.sky_top = {.r = 0x7F, .g = 0xA6, .b = 0xDA, .a = 255};
    b.sky_horizon = {.r = 0xDC, .g = 0xE7, .b = 0xF2, .a = 255};
    b.haze = {.r = 0xDF, .g = 0xE6, .b = 0xEE, .a = 255};
    b.sun = {.r = 0xFF, .g = 0xEB, .b = 0xCB, .a = 255};
    b.ambient = {.r = 0x93, .g = 0xA6, .b = 0xCC, .a = 255};
    b.ground = {.r = 0xD6, .g = 0xCF, .b = 0xC2, .a = 255};
    b.road = {.r = 0xC9, .g = 0xC1, .b = 0xB4, .a = 255};
    b.water = {.r = 0x4F, .g = 0x75, .b = 0x90, .a = 255};
    b.water_top = {.r = 0xA6, .g = 0xBF, .b = 0xD4, .a = 255};
    b.water_bottom = {.r = 0x2D, .g = 0x50, .b = 0x6C, .a = 255};
    b.shadow = {.r = 0x27, .g = 0x30, .b = 0x5A, .a = 255};
    b.haze_max = 0.22 * 0.85;
    b.sun_k = 1.0;
    b.amb_k = 0.6;
    b.sky_k = 1.0;
    b.shadow_opacity = 0.32;
    b.shadow_blur = 0.6;
    b.cloud_shadows = 0.42;
    b.sun_vector = {sun::bundled_sun.x, sun::bundled_sun.y, sun::bundled_sun.z};
    b.sun_azimuth = 213.5;
    b.sun_elevation = 16.1;
    b.cloud = 0.42;
    b.cloud_low = 0.1;
    b.wind = 14.0;
    b.gust = 27.0;
    b.wind_direction = 225.0;
    b.temperature = 14.2;
    b.pressure = 1012.5;
    b.visibility = 24000.0;
    b.humidity = 72.0;
    return b;
}

gpu::Result<TwinCheck> twin_check(const gpu::Device& device, const gpu::PipelineCache& cache,
                                  tiles::PackRef westerkerk) {
    ATLAS_GPU_LET(builder, FrameBuilder::create(device, cache, make_passes(twin_check_passes)));
    // The world's clock is the frames' time: its workers stamp each tile's publication with it, the cover fades by it.
    std::atomic<double> now{0.0};
    ATLAS_GPU_LET(
        world, scene::World::create(device, builder->passes(), {.workers = 2, .clock = [&now] { return now.load(); }}));
    world->set_coarser_enclosing(true);
    if (auto added = world->store().add(westerkerk); !added) {
        return gpu::fail(std::format("the pack {}: {}", westerkerk.id, tiles::describe(added.error())));
    }
    const style::CameraState camera = twin_check_camera();
    const style::ThemeBlock theme = twin_check_theme();
    FrameInputs inputs{.camera = camera, .theme = theme, .seconds = 0, .mode = Mode::full, .scene = world.get()};
    twin::Stream stream;
    std::uint64_t number = 0;
    // Stage F at `t` seconds, emitted, the drawn pack's profile on the theme (as MapFrame::next lights the map).
    const auto emit_at = [&](double t) {
        now.store(t);
        inputs.seconds = t;
        (void)world->ladder(camera);
        const tiles::StorePack* pack = world->active();
        inputs.theme =
            pack != nullptr && pack->pack.head() != nullptr ? with_profile(theme, pack->pack.head()->profile) : theme;
        builder->set_inputs(inputs);
        builder->emit(stream, ++number);
    };
    // Frames until the world settles (host_frame::draw's rule): the cover's tiles built and drawn, the sun's bits in;
    // or nothing under way, what is missing waiting for room in the heaps and every resident tile drawn, a few frames
    // running.
    int frames = 0;
    int waiting = 0;
    for (; frames < max_settle_frames; ++frames) {
        emit_at(0.02 * frames);
        world->residency().wait_idle();
        if (world->sun() != nullptr) world->sun()->wait_idle();
        world->pack_stage().wait_idle();
        const scene::ResidencyStats residency = world->residency().stats();
        if (frames >= 2 && residency.pending == 0 && world->cover()->missing() == 0) break;
        const bool stuck =
            residency.pending == 0 && residency.waiting > 0 && residency.resident <= world->cover()->tiles().size();
        waiting = stuck ? waiting + 1 : 0;
        if (waiting >= 3) break;
    }
    // Then a second on, past every fade, twice (the frames' two slots); the last frame's stream is the check's.
    const double settled = (0.02 * frames) + 1.0;
    emit_at(settled);
    emit_at(settled + 0.02);
    if (stream.bytes().empty()) return gpu::fail("the frame emitted nothing");
    TwinCheck out;
    const tiles::Sha256Digest d = tiles::Sha256::of(stream.bytes());
    for (std::size_t k = 0; k < 8; ++k) out.hash = (out.hash << 8U) | static_cast<std::uint64_t>(d[k]);
    out.bytes = stream.bytes().size();
    out.frames = static_cast<std::uint32_t>(frames) + 3;
    out.tiles = world->cover()->tiles().size();
    for (const std::unique_ptr<Pass>& p : builder->passes()) {
        if (p->name() != "buildings") continue;
        // NOLINTNEXTLINE(cppcoreguidelines-pro-type-static-cast-downcast): the pass named "buildings" is passes::Buildings
        const passes::Buildings::Emitted emitted = static_cast<const passes::Buildings&>(*p).emitted();
        out.elements = emitted.elements;
        out.sunlit = emitted.sunlit;
    }
    return out;
}

std::string twin_line(const gpu::Device& device, const gpu::PipelineCache& cache, tiles::PackRef westerkerk) {
    const auto check = twin_check(device, cache, std::move(westerkerk));
    if (!check) return "twin unavailable: " + check.error().text();
    if (check->hash == twin_check_expected) return std::format("twin {:016x} ok", check->hash);
    return std::format("twin {:016x} FAIL expected {:016x}", check->hash, twin_check_expected);
}

} // namespace atlas::frame
