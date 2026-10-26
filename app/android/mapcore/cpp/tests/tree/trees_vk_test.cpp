// The trees' and the street furniture's passes on the host's Vulkan (ENGINE.md step 4, "Trees and things"; lavapipe):
// the Westerkerk home and the Main board from the bundled packs (ATLAS_TILES_DIR), and two close-ups of a bundled tree,
// drawn through the frame builder and the world with those two passes alone beside the composite, at rest and moving,
// at 1x and 4x, at true depth and under the painter switch, against an exact CPU raster of the records their twins say
// trees_cs and things_cs write: each circle's and segment's samples as trees_fs keeps them (tree.slangh's own tests, in
// float, at the samples' CSS points: a segment's ends round or butt), each quad's by its edges, a sample within 0.02 px
// of a shape's boundary counted apart (the GPU's float may fuse its multiply-adds, its rasteriser rounds); each sample
// ending with the record of the greatest depth (the first drawn where two tie, as GREATER keeps it), then the
// translucent records (the trunks' sunlit sides, the lamps' glints, the bollards' heads) blended over it in the GPU's
// order at each one's opacity where its depth passes (within a level: lavapipe blends in fixed point); the samples
// averaged as the composite averages them. The twins' elements are held to the port's by L1; here their records are
// the GPU's.
#if !defined(__ANDROID__)

#include "base/arena.hpp"
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "fx/ByteTables.hpp"
#include "fx/FieldCache.hpp"
#include "fx/Fields.hpp"
#include "fx/Mat.hpp"
#include "fx/Ripple.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "gpu/PipelineCache.hpp"
#include "passes/Composite.hpp"
#include "passes/Things.hpp"
#include "passes/Trees.hpp"
#include "scene/SunGlobals.hpp"
#include "scene/World.hpp"
#include "style/Theme.hpp"
#include "sun/Buckets.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tiles/Container.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Source.hpp"
#include "tiles/layers/Lines.hpp"
#include "tiles/layers/Points.hpp"
#include "twin/Twin.hpp"
#include "twin/tree/TreeTwin.hpp"
#include "view/Ladder.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <memory>
#include <ranges>
#include <span>
#include <string>
#include <vector>

using namespace atlas;

namespace {

[[nodiscard]] std::string environment(const char* name) {
    const char* v = std::getenv(name); // NOLINT(concurrency-mt-unsafe): read before threads start
    return v == nullptr ? std::string() : std::string(v);
}

/// amsterdam.py's sunny moment of 1 October 2026, 16:00 (tests/building/buildings_vk_test.cpp's).
[[nodiscard]] style::ThemeBlock sunny() {
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

/// The sunny moment without weather (the generic tree's).
[[nodiscard]] style::ThemeBlock dry() {
    style::ThemeBlock b = sunny();
    b.flags &= ~(style::theme_flag::weather | style::theme_flag::water_gradient);
    return b;
}

/// The hour's weather after dark (the night's tones, the sun below the horizon): the lamps' halos.
[[nodiscard]] style::ThemeBlock night() {
    style::ThemeBlock b = sunny();
    b.flags =
        (b.flags | style::theme_flag::night) & ~(style::theme_flag::shadow_blur | style::theme_flag::cloud_shadows);
    b.moment = style::MomentName::night;
    b.sun_vector = {0.0, -1.0, 0.0};
    b.sun_elevation = -21.4;
    b.shadow_opacity = 0.0;
    b.cloud_shadows = 0.0;
    return b;
}

/// A lamp's halo's colour and opacity at `t` of its radius (tree_sides_fs's halo_at, in the same floats).
[[nodiscard]] hlsl::float4 halo_at(float t) {
    const hlsl::float4 s0 = shaders::tree_rgba(shaders::TREE_HALO_STOP0);
    const hlsl::float4 s1 = shaders::tree_rgba(shaders::TREE_HALO_STOP1);
    const hlsl::float4 s2 = shaders::tree_rgba(shaders::TREE_HALO_STOP2);
    if (t <= 0.35F) return s0 + ((s1 - s0) * (t / 0.35F));
    return s1 + ((s2 - s1) * std::clamp((t - 0.35F) / 0.65F, 0.0F, 1.0F));
}

/// A camera of the map (buildings_vk_test.cpp's).
[[nodiscard]] style::CameraState camera(double lat, double lon, double bearing, double pitch, double distance,
                                        std::uint32_t w, std::uint32_t h, double d) {
    style::CameraState c{};
    c.version = style::camera_state_version;
    c.latitude = lat;
    c.longitude = lon;
    c.bearing = bearing;
    c.pitch = pitch;
    c.distance = distance;
    c.density = d;
    c.width_px = w;
    c.height_px = h;
    c.width_dp = static_cast<std::uint32_t>(std::lround(w / d));
    c.height_dp = static_cast<std::uint32_t>(std::lround(h / d));
    c.principal_x = c.width_dp / 2.0;
    c.principal_y = c.height_dp * 0.48;
    c.flags = style::camera_flag::engine_mode;
    return c;
}

/// The Westerkerk home (amsterdam.py's main camera).
[[nodiscard]] style::CameraState home(std::uint32_t w, std::uint32_t h, double d) {
    const double kx = 111320 * pymath::cos(pymath::radians(52.3746));
    return camera(52.3746 - (-30.0) / 110540.0, 4.8840 + (-10.0) / kx, 52.0, 52.0, 430.0, w, h, d);
}

/// The camera state that snaps onto the grachten pack's view `name`; nothing without the pack.
[[nodiscard]] std::optional<style::CameraState> region_view(const std::string& dir, std::string_view name,
                                                            std::uint32_t w, std::uint32_t h, double d) {
    auto source = tiles::MappedSource::open(dir + "/grachten.atlas");
    if (!source) return std::nullopt;
    auto pack = tiles::Pack::open(std::move(*source));
    if (!pack || pack->head() == nullptr) return std::nullopt;
    const auto ladder = view::Ladder::of(*pack->head());
    if (!ladder) return std::nullopt;
    for (const view::Ladder::Knot& k : ladder->knots()) {
        if (k.name != name) continue;
        const view::GroundFrame& f = ladder->frame();
        const double lat = f.lat0 - (k.target[2] / f.kz);
        const double lon = f.lon0 + (k.target[0] / f.kx);
        style::CameraState c = camera(lat, lon, view::bearing_of(k.yaw), 90.0 - k.pitch, k.dist, w, h, d);
        c.principal_y = std::isnan(k.cy) ? c.height_dp / 2.0 : k.cy;
        return c;
    }
    return std::nullopt;
}

struct PackFile {
    std::string id;
    std::string path;
};

struct Scene {
    std::unique_ptr<gpu::Offscreen> output;
    std::unique_ptr<frame::FrameBuilder> builder;
    std::unique_ptr<scene::World> world;
    std::unique_ptr<gpu::Renderer> renderer;
    passes::Trees* trees = nullptr;
    passes::Things* things = nullptr;
    std::shared_ptr<fx::FieldCache> fields; ///< the screen's fields, in full mode
    double now = 0;
    bool settled = false;
    bool lit = false;
};

[[nodiscard]] std::optional<Scene> make_scene(const test::Session& session, const gpu::PipelineCache& cache,
                                              const gpu::Configuration& config, std::uint32_t w, std::uint32_t h,
                                              std::span<const PackFile> packs, std::uint32_t clear,
                                              std::shared_ptr<fx::FieldCache> fields = nullptr) {
    const gpu::Device& device = *session.device;
    Scene s;
    auto output = gpu::Offscreen::create(device, {.width = w, .height = h}, 2);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, output.has_value(), output.error().text());
    s.output = std::move(*output);
    // the registry's composite, and the trees' and the street furniture's passes of their own (the test reads their
    // twins), in the registry's order
    constexpr std::array<std::string_view, 1> composite{"composite"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(composite);
    auto trees = std::make_unique<passes::Trees>();
    s.trees = trees.get();
    passes.push_back(std::move(trees));
    auto things = std::make_unique<passes::Things>();
    s.things = things.get();
    passes.push_back(std::move(things));
    const std::array<float, 4> c{static_cast<float>((clear >> 16U) & 0xFFU) / 255.0F,
                                 static_cast<float>((clear >> 8U) & 0xFFU) / 255.0F,
                                 static_cast<float>(clear & 0xFFU) / 255.0F, 1.0F};
    auto builder =
        frame::FrameBuilder::create(device, cache, std::move(passes), {.overdraw = false, .clear = c, .steps = 0});
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, builder.has_value(), builder.error().text());
    s.builder = std::move(*builder);
    auto world = scene::World::create(device, s.builder->passes(), {.workers = 3, .clock = {}});
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, world.has_value(), world.error().text());
    s.world = std::move(*world);
    if (fields) {
        ATLAS_REQUIRE_OR_RETURN(std::nullopt, s.world->add_globals(fields), "the fields' provider was not added");
        s.fields = std::move(fields);
    }
    for (const PackFile& pack : packs) {
        auto added = s.world->store().add({.id = pack.id, .kind = tiles::PackKind::bundled, .path = pack.path});
        ATLAS_REQUIRE_OR_RETURN(std::nullopt, added.has_value(), tiles::describe(added.error()));
    }
    auto renderer = gpu::Renderer::create(device, *s.output, *s.builder, config);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, renderer.has_value(), renderer.error().text());
    s.renderer = std::move(*renderer);
    return s;
}

/// Draws `cam` in `mode` until the world has built every tile the cover wants, their fades and sun bits' fades over
/// (and the scene's fields bound, where it has them); the last frame's pixels.
[[nodiscard]] std::optional<gpu::Pixels> draw(Scene& s, const style::CameraState& cam, const style::ThemeBlock& th,
                                              frame::Mode mode = frame::Mode::structural) {
    int waited = 0;
    for (int n = 0; n < 81; ++n) {
        s.now += 0.25;
        s.builder->set_inputs({.camera = cam, .theme = th, .seconds = s.now, .mode = mode, .scene = s.world.get()});
        auto report = s.renderer->draw(0);
        if (!report) {
            FAIL("a frame failed: " << report.error().text());
            return std::nullopt;
        }
        s.renderer->drain();
        if (s.settled && s.lit) break; // this frame drawn with every tile taken and lit
        s.world->residency().wait_idle();
        const scene::Cover& cover = *s.world->cover();
        if (s.fields) s.fields->worker().wait_idle();
        s.settled = n > 4 && cover.missing() == 0 && !cover.tiles().empty() &&
                    std::ranges::all_of(cover.tiles(), [](const scene::CoverTile& t) { return t.fade >= 1.0F; }) &&
                    (!s.fields || s.fields->bound_for(fx::size_of(cam.width_px, cam.height_px, cam.density)));
        s.lit = std::ranges::all_of(cover.tiles(), [](const scene::CoverTile& t) {
            return t.sun_base != scene::no_sun && t.sun.has_value() && t.sun_weight >= 1.0F;
        });
        if (s.settled && !s.lit && ++waited > 20) {
            MESSAGE("drawn without every tile's sun bits (the sun's worker gave none)");
            s.lit = true; // draw once more as it is
        }
        if (s.settled && s.world->sun() != nullptr) s.world->sun()->wait_idle();
    }
    auto pixels = s.output->read(s.output->latest());
    if (!pixels) {
        FAIL("the frame could not be read: " << pixels.error().text());
        return std::nullopt;
    }
    return std::move(*pixels);
}

[[nodiscard]] std::vector<hlsl::float2> sample_positions(vk::SampleCountFlagBits samples) {
    switch (samples) {
        using F = hlsl::float2;
    case vk::SampleCountFlagBits::e4:
        return {F{0.375F, 0.125F}, F{0.875F, 0.375F}, F{0.125F, 0.625F}, F{0.625F, 0.875F}};
    case vk::SampleCountFlagBits::e2: return {F{0.75F, 0.75F}, F{0.25F, 0.25F}};
    default: return {F{0.5F, 0.5F}};
    }
}

/// Whether pixel (x, y) of a w × h frame lies on the composite's seam (buildings_vk_test.cpp's on_seam).
[[nodiscard]] bool on_seam(std::size_t x, std::size_t y, std::size_t w, std::size_t h) noexcept {
    const std::uint64_t above = std::uint64_t{y} * w;
    const std::uint64_t below = above + w;
    const std::uint64_t left = std::uint64_t{x} * h;
    const std::uint64_t right = left + h;
    return above < right && below > left;
}

/// Where a sample lies against a record: inside it or not, and whether within the tolerance of its boundary.
struct Hit {
    bool inside = false;
    bool near = false;
};

/// A point in double.
struct Pt {
    double x;
    double y;
};

[[nodiscard]] Hit quad_hit(const shaders::TreeRecord& r, hlsl::float2 sample, double tolerance) {
    const auto f = [](std::uint32_t v) { return static_cast<double>(std::bit_cast<float>(v)); };
    const std::array<Pt, 4> q{Pt{.x = f(r.b.x), .y = f(r.b.y)}, Pt{.x = f(r.b.z), .y = f(r.b.w)},
                              Pt{.x = f(r.c.x), .y = f(r.c.y)}, Pt{.x = f(r.c.z), .y = f(r.c.w)}};
    const Pt s{.x = static_cast<double>(sample.x), .y = static_cast<double>(sample.y)};
    // the two triangles the vertex stage draws (0 1 2, 0 2 3), each tested by its edges in double
    const auto in_triangle = [&](Pt a, Pt b, Pt c, double& nearest) {
        const double area = ((b.x - a.x) * (c.y - a.y)) - ((b.y - a.y) * (c.x - a.x));
        if (area == 0) return false;
        bool inside = true;
        const std::array<Pt, 3> t{a, b, c};
        for (std::size_t i = 0; i < 3; ++i) {
            const Pt p = t[i];
            const Pt e = t[(i + 1) % 3];
            const double ex = e.x - p.x;
            const double ey = e.y - p.y;
            const double len = std::sqrt((ex * ex) + (ey * ey));
            const double cross = (ex * (s.y - p.y)) - (ey * (s.x - p.x));
            const double d = (area > 0 ? cross : -cross) / (len > 0 ? len : 1);
            nearest = std::min(nearest, std::abs(d));
            if (d < 0) inside = false;
        }
        return inside;
    };
    double nearest = 1e300;
    const bool inside = in_triangle(q[0], q[1], q[2], nearest) || in_triangle(q[0], q[2], q[3], nearest);
    return {.inside = inside, .near = nearest <= tolerance};
}

/// What a frame's comparison with the exact raster found.
struct Compared {
    std::size_t pixels = 0;   ///< pixels some record covers in the reference
    std::size_t agree = 0;    ///< pixels equal to the reference
    std::size_t boundary = 0; ///< pixels that differ, a sample within 0.02 px of a shape's boundary
    std::size_t seam = 0;     ///< pixels that differ on the composite's seam
    std::size_t wrong = 0;
    std::string first;
};

/// What full mode draws on top of the records' own shapes and colours (the screen's fields bound, with weather): `mat`
/// on every part at its pixel's grey, and the crown filter on the crowns' circles (render/closeup.py's crown_filter):
/// each sample's place moved by whole device px by the crowns' field at its pixel (`offsets`: the displacement's 256),
/// kept where the moved place lies in the circle and the pixel and the moved pixel in the tree's filter region, in the
/// filter's colour at the crown tone's byte there (fx::crown_table).
struct Full {
    const fx::ScreenFields* fields = nullptr;
    std::array<std::int32_t, 256> offsets{};
    std::int32_t reach = 0;
};

/// A colour (0 to 1, at 8-bit levels) through a table of 256 rows by its level, at column `column`.
[[nodiscard]] hlsl::float3 through(std::span<const std::uint8_t> table, hlsl::float3 c, std::uint32_t column) {
    const auto at = [&](float v) {
        const auto level = static_cast<std::uint32_t>(std::floor((v * 255.0F) + 0.5F));
        return static_cast<float>(table[(std::size_t{level} << 8U) | column]) / 255.0F;
    };
    return {at(c.x), at(c.y), at(c.z)};
}

/// The GPU's frame `got` against the exact raster of the opaque `records` and then the `translucent` ones, each in the
/// GPU's order; with `full`, in full mode.
[[nodiscard]] Compared compare(const gpu::Pixels& got, std::span<const shaders::TreeRecord> records,
                               std::span<const shaders::TreeRecord> translucent, const shaders::FrameBlock& block,
                               const gpu::Configuration& config, std::uint32_t clear, const Full* full = nullptr) {
    const shaders::FrameCamera& cam = block.camera;
    const std::vector<hlsl::float2> samples = sample_positions(config.samples());
    const std::uint32_t w = got.width;
    const std::uint32_t h = got.height;
    const std::size_t n = samples.size();
    constexpr double tolerance = 0.02;
    // each sample's colour (0 to 1), whether a record covers it, whether it lies on a shape's boundary, its blends
    std::vector<hlsl::float3> colour(std::size_t{w} * h * n);
    std::vector<std::uint8_t> covered(colour.size(), 0);
    std::vector<std::uint8_t> edge(colour.size(), 0);
    std::vector<std::uint8_t> blended(colour.size(), 0);
    std::vector<float> depth(colour.size(), 0.0F);
    const hlsl::float3 background{static_cast<float>((clear >> 16U) & 0xFFU) / 255.0F,
                                  static_cast<float>((clear >> 8U) & 0xFFU) / 255.0F,
                                  static_cast<float>(clear & 0xFFU) / 255.0F};
    std::ranges::fill(colour, background);
    // a record's box of device pixels (its corners, a CSS px and more beyond a circle or a limb)
    const auto box = [&](const shaders::TreeRecord& r) {
        std::array<float, 4> b{1e30F, 1e30F, -1e30F, -1e30F};
        const auto add = [&](float x, float y, float e) {
            b[0] = std::min(b[0], x - e);
            b[1] = std::min(b[1], y - e);
            b[2] = std::max(b[2], x + e);
            b[3] = std::max(b[3], y + e);
        };
        const auto f = [](std::uint32_t v) { return std::bit_cast<float>(v); };
        const std::uint32_t kind = r.a.x & shaders::TREE_KIND;
        if (kind == shaders::TREE_CIRCLE) {
            const bool crown = full != nullptr && (r.a.x & shaders::TREE_CROWN) != 0U;
            add(f(r.b.x), f(r.b.y), f(r.b.z) + 1.0F + (crown ? static_cast<float>(full->reach) / cam.frame.z : 0.0F));
        } else if (kind == shaders::TREE_SEGMENT) {
            add(f(r.b.x), f(r.b.y), f(r.c.x) + 1.0F);
            add(f(r.b.z), f(r.b.w), f(r.c.x) + 1.0F);
        } else {
            add(f(r.b.x), f(r.b.y), 0.0F);
            add(f(r.b.z), f(r.b.w), 0.0F);
            add(f(r.c.x), f(r.c.y), 0.0F);
            add(f(r.c.z), f(r.c.w), 0.0F);
        }
        const float d = cam.frame.z;
        return std::array<std::int64_t, 4>{
            std::max<std::int64_t>(0, static_cast<std::int64_t>(std::floor(b[0] * d)) - 1),
            std::max<std::int64_t>(0, static_cast<std::int64_t>(std::floor(b[1] * d)) - 1),
            std::min<std::int64_t>(w - 1, static_cast<std::int64_t>(std::ceil(b[2] * d)) + 1),
            std::min<std::int64_t>(h - 1, static_cast<std::int64_t>(std::ceil(b[3] * d)) + 1)};
    };
    const auto each = [&](const shaders::TreeRecord& r, auto&& at) {
        const std::uint32_t kind = r.a.x & shaders::TREE_KIND;
        if (kind == shaders::TREE_NONE) return;
        const std::array<std::int64_t, 4> b = box(r);
        for (std::int64_t y = b[1]; y <= b[3]; ++y) {
            for (std::int64_t x = b[0]; x <= b[2]; ++x) {
                for (std::size_t q = 0; q < n; ++q) {
                    const hlsl::float2 px{static_cast<float>(x) + samples[q].x, static_cast<float>(y) + samples[q].y};
                    const hlsl::float2 s = shaders::css_of_output(cam, px);
                    Hit hit;
                    const hlsl::float2 a{std::bit_cast<float>(r.b.x), std::bit_cast<float>(r.b.y)};
                    const double tol = tolerance / static_cast<double>(cam.frame.z);
                    if (kind == shaders::TREE_CIRCLE && full != nullptr && (r.a.x & shaders::TREE_CROWN) != 0U) {
                        // the crown filter: the pixel moved by the crowns' field there, both within the tree's region
                        const auto ux = static_cast<std::uint32_t>(x);
                        const auto uy = static_cast<std::uint32_t>(y);
                        const std::array<std::uint8_t, 2> rg = full->fields->at(fx::Field::crown, ux, uy);
                        const std::int32_t ox = full->offsets[rg[0]];
                        const std::int32_t oy = full->offsets[rg[1]];
                        const std::int64_t sx = x + ox;
                        const std::int64_t sy = y + oy;
                        const auto region = [&](std::uint32_t v) { return std::bit_cast<std::int32_t>(v); };
                        const auto within = [&](std::int64_t u, std::int64_t v) {
                            return u >= region(r.c.x) && v >= region(r.c.y) && u < region(r.c.z) && v < region(r.c.w);
                        };
                        const bool in = within(x, y) && within(sx, sy);
                        // the GPU's region may round a box's edge the other way (its float may fuse)
                        const auto edge = [&](std::int64_t u, std::int64_t v) {
                            return std::abs(u - region(r.c.x)) <= 1 || std::abs(v - region(r.c.y)) <= 1 ||
                                   std::abs(u - region(r.c.z)) <= 1 || std::abs(v - region(r.c.w)) <= 1;
                        };
                        const bool region_edge = edge(x, y) || edge(sx, sy);
                        const hlsl::float2 shift = {static_cast<float>(ox) / cam.frame.z,
                                                    static_cast<float>(oy) / cam.frame.z};
                        const hlsl::float2 m = s + shift;
                        hit.inside = in && shaders::tree_circle_covers(m, a, std::bit_cast<float>(r.b.z));
                        const double d = pymath::hypot(static_cast<double>(m.x) - static_cast<double>(a.x),
                                                       static_cast<double>(m.y) - static_cast<double>(a.y));
                        hit.near = (in && std::abs(d - static_cast<double>(std::bit_cast<float>(r.b.z))) <= tol) ||
                                   region_edge;
                    } else if (kind == shaders::TREE_CIRCLE) {
                        hit.inside = shaders::tree_circle_covers(s, a, std::bit_cast<float>(r.b.z));
                        // within the tolerance of its boundary the GPU's float (which may fuse its multiply-adds)
                        // may decide otherwise
                        const double d = pymath::hypot(static_cast<double>(s.x) - static_cast<double>(a.x),
                                                       static_cast<double>(s.y) - static_cast<double>(a.y));
                        hit.near = std::abs(d - static_cast<double>(std::bit_cast<float>(r.b.z))) <= tol;
                    } else if (kind == shaders::TREE_SEGMENT) {
                        const hlsl::float2 e{std::bit_cast<float>(r.b.z), std::bit_cast<float>(r.b.w)};
                        hit.inside = shaders::tree_segment_covers(s, a, e, std::bit_cast<float>(r.c.x), r.a.x);
                        const auto ax = static_cast<double>(a.x);
                        const auto ay = static_cast<double>(a.y);
                        const double ex = static_cast<double>(e.x) - ax;
                        const double ey = static_cast<double>(e.y) - ay;
                        const double dx = static_cast<double>(s.x) - ax;
                        const double dy = static_cast<double>(s.y) - ay;
                        const double ee = (ex * ex) + (ey * ey);
                        const double along = ee > 0 ? ((dx * ex) + (dy * ey)) / ee : 0.0;
                        const double t = std::clamp(along, 0.0, 1.0);
                        const double d = pymath::hypot(dx - (ex * t), dy - (ey * t));
                        hit.near = std::abs(d - static_cast<double>(std::bit_cast<float>(r.c.x))) <= tol;
                        // a butt end: the line across the segment's end
                        const double len = std::sqrt(ee);
                        const bool butt_start = (r.a.x & shaders::TREE_ROUND_START) == 0U;
                        const bool butt_end = (r.a.x & shaders::TREE_ROUND_END) == 0U;
                        if (len > 0 && ((butt_start && std::abs(along) * len <= tol) ||
                                        (butt_end && std::abs(along - 1.0) * len <= tol))) {
                            hit.near = true;
                        }
                    } else {
                        hit = quad_hit(r, s, tol);
                    }
                    at((((static_cast<std::size_t>(y) * w) + static_cast<std::size_t>(x)) * n) + q, hit);
                }
            }
        }
    };
    // full mode's colour of a sample of record `r` at sample index k: the crown filter's, then `mat`'s
    const auto in_full = [&](const shaders::TreeRecord& r, std::size_t k, hlsl::float3 c) {
        if (full == nullptr) return c;
        const auto x = static_cast<std::uint32_t>((k / n) % w);
        const auto y = static_cast<std::uint32_t>((k / n) / w);
        if ((r.a.x & shaders::TREE_KIND) == shaders::TREE_CIRCLE && (r.a.x & shaders::TREE_CROWN) != 0U) {
            c = through(fx::crown_table(), c, full->fields->at(fx::Field::crown_tone, x, y)[0]);
        }
        return through(fx::mat_table(), c, full->fields->at(fx::Field::mat, x, y)[0]);
    };
    for (const shaders::TreeRecord& r : records) {
        const auto d = std::bit_cast<float>(r.a.w);
        each(r, [&](std::size_t k, Hit hit) {
            if (hit.near) edge[k] = 1;
            if (!hit.inside || !(d > depth[k])) return;
            depth[k] = d;
            colour[k] = in_full(r, k, shaders::tree_rgb(r.a.y));
            covered[k] = 1;
        });
    }
    // the translucent ones: the trees' sunlit sides, then the furniture's glints and heads
    for (const shaders::TreeRecord& r : translucent) {
        const auto d = std::bit_cast<float>(r.a.w);
        const auto opacity = std::bit_cast<float>(r.a.z);
        each(r, [&](std::size_t k, Hit hit) {
            if (hit.near) edge[k] = 1;
            if (!hit.inside || !(d > depth[k])) return;
            hlsl::float3 rgb = shaders::tree_rgb(r.a.y);
            float alpha = opacity;
            if ((r.a.x & shaders::TREE_HALO) != 0U) {
                // a lamp's halo: its gradient at the pixel's centre
                const std::size_t pixel = k / n; // the sample's pixel, its column and row
                const std::size_t column = pixel % w;
                const std::size_t row = pixel / w;
                const hlsl::float2 centre{static_cast<float>(column) + 0.5F, static_cast<float>(row) + 0.5F};
                const hlsl::float2 s = shaders::css_of_output(cam, centre);
                const float dx = s.x - std::bit_cast<float>(r.b.x);
                const float dy = s.y - std::bit_cast<float>(r.b.y);
                const hlsl::float4 g = halo_at(std::sqrt((dx * dx) + (dy * dy)) / std::bit_cast<float>(r.b.z));
                rgb = g.xyz;
                alpha = g.w;
            }
            const hlsl::float3 c = in_full(r, k, rgb);
            const hlsl::float3 under = colour[k];
            const hlsl::float3 over = (c * alpha) + (under * (1.0F - alpha));
            colour[k] = {std::round(over.x * 255.0F) / 255.0F, std::round(over.y * 255.0F) / 255.0F,
                         std::round(over.z * 255.0F) / 255.0F};
            covered[k] = 1;
            ++blended[k];
        });
    }
    Compared c;
    for (std::size_t i = 0; i < std::size_t{w} * h; ++i) {
        bool any = false;
        bool near_edge = false;
        std::uint32_t blends = 0;
        for (std::size_t q = 0; q < n; ++q) {
            any = any || covered[(i * n) + q] != 0 || edge[(i * n) + q] != 0;
            near_edge = near_edge || edge[(i * n) + q] != 0;
            blends = std::max<std::uint32_t>(blends, blended[(i * n) + q]);
        }
        if (!any) continue;
        ++c.pixels;
        // the composite's mean of the samples' levels, rounded half to even
        shaders::FrameLayers stubbed{};
        stubbed.steps = {0U, 255U, 0U, 0U};
        const hlsl::float3 p =
            passes::composite_pixel(stubbed, std::span(colour.data() + (i * n), n), {0.5F, 0.5F}, {1.0F, 1.0F});
        const std::array<std::uint32_t, 3> want{static_cast<std::uint32_t>(p.x), static_cast<std::uint32_t>(p.y),
                                                static_cast<std::uint32_t>(p.z)};
        const std::array<std::uint32_t, 3> have{got.rgba[i * 4], got.rgba[(i * 4) + 1], got.rgba[(i * 4) + 2]};
        const std::uint32_t levels = blends + (blends > 0 && n > 1 ? 1U : 0U);
        bool same = true;
        for (std::size_t k = 0; k < 3; ++k) {
            if ((want[k] > have[k] ? want[k] - have[k] : have[k] - want[k]) > levels) same = false;
        }
        if (same) {
            ++c.agree;
        } else if (near_edge) {
            ++c.boundary;
        } else if (config.target == gpu::CompositeTarget::resolve && on_seam(i % w, i / w, w, h)) {
            ++c.seam;
        } else {
            if (c.wrong == 0) {
                c.first =
                    std::format("pixel ({}, {}): {:02x}{:02x}{:02x} where the exact raster has {:02x}{:02x}{:02x}",
                                i % w, i / w, have[0], have[1], have[2], want[0], want[1], want[2]);
            }
            ++c.wrong;
        }
    }
    return c;
}

/// Draws `cam` at 1x and 4x, at rest and moving, at true depth and (with `painter`) under the painter switch, at
/// moment `th`, and holds every pixel to the exact raster of the twin's records; the twin emits at least `least`
/// elements.
void check_frames(const style::CameraState& cam, const std::string& view, std::span<const PackFile> packs,
                  const style::ThemeBlock& th, std::size_t least, frame::Mode mode = frame::Mode::structural) {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    // full mode: the screen's fields bound (made here on the CPU too, for the raster), the crowns' displacement
    const bool full = mode == frame::Mode::full;
    std::shared_ptr<const fx::ScreenFields> cpu_fields;
    Full filter;
    if (full) {
        cpu_fields = fx::make_fields(fx::size_of(cam.width_px, cam.height_px, cam.density), {.threads = 3});
        filter.fields = cpu_fields.get();
        const fx::RippleDisplacement d = fx::ripple_displacement(10.0F, static_cast<float>(cam.density));
        filter.reach = d.reach;
        for (std::uint32_t c = 0; c < 256; ++c) {
            filter.offsets[c] = static_cast<std::int32_t>(std::fma(d.factor, static_cast<float>(c), d.offset));
        }
    }
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    constexpr std::uint32_t clear = 0x808080;
    for (const gpu::Configuration config : {gpu::Configuration{.config = gpu::FrameConfig::x1,
                                                               .route = gpu::Route::renderpass,
                                                               .form = gpu::FrameForm::composite,
                                                               .target = gpu::CompositeTarget::direct},
                                            gpu::Configuration{.config = gpu::FrameConfig::c,
                                                               .route = gpu::Route::renderpass,
                                                               .form = gpu::FrameForm::composite,
                                                               .target = gpu::CompositeTarget::resolve}}) {
        if (!device.facts().supports(config.config)) continue;
        for (const bool painter : {false, true}) {
            for (const bool rest : {true, false}) {
                if (full && (painter || !rest)) continue; // full mode at true depth, at rest
                style::CameraState c = cam;
                c.flags = rest ? (c.flags | style::camera_flag::rest) : (c.flags & ~style::camera_flag::rest);
                std::shared_ptr<fx::FieldCache> fields;
                if (full) {
                    auto f = fx::FieldCache::create(device, {.threads = 3});
                    ATLAS_REQUIRE(f.has_value(), f.error().text());
                    fields = std::move(*f);
                }
                auto made = make_scene(*session, *cache, config, c.width_px, c.height_px, packs, clear, fields);
                ATLAS_REQUIRE(made.has_value());
                Scene& s = *made;
                s.builder->set_painter(painter);
                const auto pixels = draw(s, c, th, mode);
                ATLAS_REQUIRE(pixels.has_value());
                CHECK_MESSAGE(s.settled, view << ": the world did not settle");
                twin::Stream stream;
                std::vector<shaders::TreeRecord> records;
                std::vector<shaders::TreeRecord> sides;
                std::vector<shaders::TreeRecord> glints;
                s.trees->emit_records(s.builder->state(), stream, &records, &sides);
                const std::size_t tree_records = records.size();
                s.things->emit_records(s.builder->state(), stream, &records, &glints);
                const twin::Decoded decoded = twin::decode(stream.bytes());
                ATLAS_REQUIRE(decoded.error.empty(), decoded.error);
                const passes::Trees::Counts counts = s.trees->counts();
                const passes::Things::Counts thing_counts = s.things->counts();
                // the translucent lists as the GPU draws them, each far to near (the twins keep them nearest first)
                std::vector<shaders::TreeRecord> translucent(sides.rbegin(), sides.rend());
                translucent.insert(translucent.end(), glints.rbegin(), glints.rend());
                const Compared r = compare(*pixels, records, translucent, s.builder->state().block, config, clear,
                                           full ? &filter : nullptr);
                if (full) {
                    // the frame drew its crowns under their filter: the fields bound, with weather
                    CHECK((s.builder->state().block.info.x & shaders::FRAME_FIELDS) != 0U);
                    const auto crowns = std::ranges::count_if(
                        records, [](const shaders::TreeRecord& t) { return (t.a.x & shaders::TREE_CROWN) != 0U; });
                    MESSAGE(view << ": " << crowns << " crowns' circles under the crown filter");
                    CHECK(crowns > 100);
                }
                const std::string name = std::format("{} {} {}{}", view, gpu::name_of(config),
                                                     rest ? "at rest" : "moving", painter ? " (painter's order)" : "");
                MESSAGE(name << ": " << stream.elements() << " elements of " << counts.jobs << " trees, "
                             << counts.records << " records and " << counts.sides << " sunlit sides, "
                             << thing_counts.jobs << " things, " << thing_counts.records << " records and "
                             << thing_counts.sides << " translucent ones; " << r.pixels
                             << " pixels of trees and things, " << r.agree << " the exact raster's, " << r.boundary
                             << " within 0.02 px of a shape's boundary, " << r.seam << " on the composite's seam, "
                             << r.wrong << " otherwise; " << frame::text_of(s.builder->total()));
                CHECK(stream.elements() >= least);
                CHECK(tree_records == counts.records);
                CHECK(records.size() - tree_records == thing_counts.records);
                CHECK(sides.size() == counts.sides);
                CHECK(glints.size() == thing_counts.sides);
                CHECK_MESSAGE(r.wrong == 0, name << ": " << r.first);
                CHECK(static_cast<double>(r.boundary) <= 0.01 * static_cast<double>(r.pixels));
                for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, name << ": " << m);
                if (const std::string out = environment("ATLAS_TREES_PPM"); !out.empty()) {
                    CHECK(gpu::write_ppm(*pixels, std::format("{}/{}.ppm", out, name)));
                }
                s.renderer.reset();
            }
        }
    }
}

} // namespace

TEST_CASE("trees: the Westerkerk home drawn on the host's Vulkan, pixel for pixel the exact shapes of its twin") {
    const std::string dir = environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the Westerkerk home is not drawn here");
        return;
    }
    std::vector<PackFile> packs;
    for (const char* name : {"amsterdam", "grachten"}) {
        const std::filesystem::path path = std::filesystem::path(dir) / (std::string(name) + ".atlas");
        if (std::filesystem::exists(path)) packs.push_back({.id = name, .path = path.string()});
    }
    check_frames(home(1080, 2400, 1080.0 / 412.0), "home", packs, sunny(), 50);
    check_frames(home(1080, 2400, 1080.0 / 412.0), "home without weather", packs, dry(), 50);
    // after dark: the night's tones, and the lamps' halos (translucent, each its gradient at a pixel's centre)
    check_frames(home(1080, 2400, 1080.0 / 412.0), "home at night", packs, night(), 50);
}

TEST_CASE(
    "trees: the crowns under their filter in full mode at the Westerkerk home, pixel for pixel the exact raster's") {
    // render/closeup.py's crown_filter where the fields are bound in full mode with weather: each crown's samples moved
    // by the crowns' field, in its tone, and `mat` on every part, as the exact raster computes them from the CPU's
    // fields
    const std::string dir = environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the Westerkerk home is not drawn here");
        return;
    }
    std::vector<PackFile> packs;
    for (const char* name : {"amsterdam", "grachten"}) {
        const std::filesystem::path path = std::filesystem::path(dir) / (std::string(name) + ".atlas");
        if (std::filesystem::exists(path)) packs.push_back({.id = name, .path = path.string()});
    }
    check_frames(home(1080, 2400, 1080.0 / 412.0), "home in full mode", packs, sunny(), 50, frame::Mode::full);
}

TEST_CASE("trees: the Main board drawn on the host's Vulkan, pixel for pixel the exact shapes of its twin") {
    const std::string dir = environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the Main board is not drawn here");
        return;
    }
    const auto cam = region_view(dir, "grachten_home", 1080, 2400, 1080.0 / 412.0);
    ATLAS_REQUIRE(cam.has_value(), "the grachten pack has no grachten_home view");
    const std::array<PackFile, 1> packs{PackFile{.id = "grachten", .path = dir + "/grachten.atlas"}};
    check_frames(*cam, "board", packs, sunny(), 200);
}

TEST_CASE("trees: close up, every clump of a tree drawn as its twin decides it") {
    const std::string dir = environment("ATLAS_TILES_DIR");
    if (dir.empty()) return;
    const std::filesystem::path path = std::filesystem::path(dir) / "amsterdam.atlas";
    if (!std::filesystem::exists(path)) return;
    // the bundled pack's first tree, looked at from 45 m and from 25 m: tree_close's 26 and 36 clumps, its limbs and
    // its trunk's sunlit side, and its neighbours'
    auto source = tiles::MappedSource::open(path.string());
    ATLAS_REQUIRE(source.has_value());
    auto pack = tiles::Pack::open(std::move(*source));
    ATLAS_REQUIRE(pack.has_value() && pack->head() != nullptr);
    const auto ladder = view::Ladder::of(*pack->head());
    ATLAS_REQUIRE(ladder.has_value());
    std::optional<std::pair<double, double>> tree; // its x and z, frame metres
    tiles::Zstd zstd;
    tiles::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    Arena arena(4U << 20U, Growth::grows, "trees close");
    for (const tiles::ChunkRef& chunk : pack->chunks().level(pack->head()->levels[0])) {
        arena.reset();
        auto tile = pack->decode(chunk, zstd, decoder, arena, raw, scratch);
        ATLAS_REQUIRE(tile.has_value());
        const tiles::Layer* layer = tile->layer(tiles::LayerId::tree);
        if (layer == nullptr || layer->count == 0) continue;
        const tiles::GridPoint p = layer->point(tiles::tree::pos, 0);
        tree = std::pair{pack->head()->frame.x(p.lon), pack->head()->frame.z(p.lat)};
        break;
    }
    ATLAS_REQUIRE(tree.has_value(), "the bundled pack has no tree");
    const view::GroundFrame& f = ladder->frame();
    const double lat = f.lat0 - (tree->second / f.kz);
    const double lon = f.lon0 + (tree->first / f.kx);
    const std::array<PackFile, 1> packs{PackFile{.id = "amsterdam", .path = path.string()}};
    check_frames(camera(lat, lon, 30.0, 60.0, 45.0, 1080, 2400, 1080.0 / 412.0), "close", packs, sunny(), 20);
    check_frames(camera(lat, lon, 200.0, 50.0, 25.0, 1080, 2400, 1080.0 / 412.0), "closer", packs, sunny(), 20);
}

TEST_CASE("things: a bridge's railings close up, drawn as their twin decides them") {
    const std::string dir = environment("ATLAS_TILES_DIR");
    if (dir.empty()) return;
    const std::filesystem::path path = std::filesystem::path(dir) / "amsterdam.atlas";
    if (!std::filesystem::exists(path)) return;
    // the bundled pack's first bridge, looked at from 40 m: its railings' rails and posts, with weather and without
    auto source = tiles::MappedSource::open(path.string());
    ATLAS_REQUIRE(source.has_value());
    auto pack = tiles::Pack::open(std::move(*source));
    ATLAS_REQUIRE(pack.has_value() && pack->head() != nullptr);
    const auto ladder = view::Ladder::of(*pack->head());
    ATLAS_REQUIRE(ladder.has_value());
    std::optional<std::pair<double, double>> bridge; // a point of it, frame metres
    tiles::Zstd zstd;
    tiles::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    Arena arena(4U << 20U, Growth::grows, "railings close");
    for (const tiles::ChunkRef& chunk : pack->chunks().level(pack->head()->levels[0])) {
        arena.reset();
        auto tile = pack->decode(chunk, zstd, decoder, arena, raw, scratch);
        ATLAS_REQUIRE(tile.has_value());
        const tiles::Layer* roads = tile->layer(tiles::LayerId::road);
        if (roads == nullptr) continue;
        for (std::uint32_t i = 0; i < roads->count && !bridge; ++i) {
            if (!roads->boolean(tiles::road::bridge, i).value_or(false)) continue;
            const tiles::RingView r = roads->ring(tiles::road::line, i);
            if (r.size() < 2) continue;
            bridge = std::pair{pack->head()->frame.x(r.lon[0]), pack->head()->frame.z(r.lat[0])};
        }
        if (bridge) break;
    }
    ATLAS_REQUIRE(bridge.has_value(), "the bundled pack has no bridge");
    const view::GroundFrame& f = ladder->frame();
    const double lat = f.lat0 - (bridge->second / f.kz);
    const double lon = f.lon0 + (bridge->first / f.kx);
    const std::array<PackFile, 1> packs{PackFile{.id = "amsterdam", .path = path.string()}};
    check_frames(camera(lat, lon, 120.0, 60.0, 40.0, 1080, 2400, 1080.0 / 412.0), "bridge", packs, sunny(), 10);
    check_frames(camera(lat, lon, 300.0, 65.0, 30.0, 1080, 2400, 1080.0 / 412.0), "bridge without weather", packs,
                 dry(), 1);
}

#endif
