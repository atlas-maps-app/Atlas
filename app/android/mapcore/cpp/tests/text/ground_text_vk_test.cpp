// The ground's text drawn by the frame (plan-vector D13, K13): over the bundled Westerkerk at its home view, at rest,
// the world's tiles bring their canal candidates (stage T), the pass places the renders' names, shapes them, adds their
// glyphs to the shared data (set 1's, through GlyphGlobals) and draws them in the translucent slot; on every
// configuration and route the host's Vulkan has, every pixel the glyphs reach is the CPU's coverage (text/SlugBands'
// coverage at the pixel's centre, through the identity table) blended over the clear colour in the glyphs' order.
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "gpu/PipelineCache.hpp"
#include "passes/GroundText.hpp"
#include "scene/World.hpp"
#include "tests/base/require.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/text/text_support.hpp"
#include "tests/tiles/support.hpp"
#include "text/Fonts.hpp"
#include "text/SlugBands.hpp"
#include "twin/Twin.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <cmath>
#include <filesystem>
#include <format>
#include <memory>
#include <string>
#include <vector>

#if !defined(__ANDROID__)
#include "platform/host/Offscreen.hpp"

using namespace atlas;

namespace {

/// The configurations the host's device draws the map's frame in, each route (composite_vk_test's list).
std::vector<gpu::Configuration> configurations(const gpu::Device& device) {
    std::vector<gpu::Configuration> out;
    const bool dynamic = device.enabled().dynamic_rendering && device.enabled().local_read;
    for (const gpu::FrameConfig c :
         {gpu::FrameConfig::a, gpu::FrameConfig::b, gpu::FrameConfig::c, gpu::FrameConfig::x1}) {
        if (!device.facts().supports(c)) continue;
        const bool one = gpu::Configuration{.config = c}.samples() == vk::SampleCountFlagBits::e1;
        for (const gpu::Route route : {gpu::Route::renderpass, gpu::Route::dynamic}) {
            if (route == gpu::Route::dynamic && !dynamic) continue;
            out.push_back({.config = c,
                           .route = route,
                           .form = gpu::FrameForm::composite,
                           .target = one ? gpu::CompositeTarget::direct : gpu::CompositeTarget::resolve});
        }
    }
    return out;
}

/// The Westerkerk home at rest (the scene test's camera) on a 412 x 916 frame at density 1.
style::CameraState home() {
    style::CameraState c{};
    c.version = style::camera_state_version;
    const double kx = 111320 * pymath::cos(pymath::radians(52.3746));
    c.latitude = 52.3746 - (-30.0) / 110540.0;
    c.longitude = 4.8840 + (-10.0) / kx;
    c.bearing = 52.0;
    c.pitch = 52.0;
    c.distance = 430.0;
    c.principal_x = 206.0;
    c.principal_y = 440.0;
    c.density = 1.0;
    c.width_px = 412;
    c.height_px = 916;
    c.width_dp = 412;
    c.height_dp = 916;
    c.flags = style::camera_flag::rest;
    return c;
}

constexpr std::array<float, 4> clear{0.92F, 0.90F, 0.86F, 1.0F};

/// The frame the glyphs make, in float levels (0-255): the clear colour as the attachment holds it, then each glyph in
/// its order blended over at each pixel centre its dilated quad covers, as the GPU blends, rounded to levels each time.
std::vector<float> expected(std::span<const layouts::text_slug::GlyphDraw> draws, std::uint32_t width,
                            std::uint32_t height, std::size_t& reached) {
    std::vector<float> rgb(static_cast<std::size_t>(width) * height * 3);
    for (std::size_t i = 0; i < rgb.size(); ++i) {
        rgb[i] =
            static_cast<float>(std::round(static_cast<double>(clear.at(i % 3)) * 255.0)); // the float clear, exactly
    }
    std::vector<std::uint8_t> touched(static_cast<std::size_t>(width) * height, 0);
    text::slug::shared_glyphs().read([&](const text::slug::Atlas& atlas) {
        for (const auto& d : draws) {
            const auto a = static_cast<double>(d.row0[0]);
            const auto b = static_cast<double>(d.row0[1]);
            const auto c = static_cast<double>(d.row1[0]);
            const auto e = static_cast<double>(d.row1[1]);
            const auto tx = static_cast<double>(d.row0[2]);
            const auto ty = static_cast<double>(d.row1[2]);
            const double det = (a * e) - (b * c);
            // em = M^-1 (px - t); its derivatives per px are M^-1's columns
            const double i00 = e / det;
            const double i01 = -b / det;
            const double i10 = -c / det;
            const double i11 = a / det;
            const auto fwx = static_cast<float>(std::fabs(i00) + std::fabs(i01));
            const auto fwy = static_cast<float>(std::fabs(i10) + std::fabs(i11));
            text::slug::Glyph g{};
            g.box = {.x0 = d.box[0], .y0 = d.box[1], .x1 = d.box[2], .y1 = d.box[3]};
            g.band_x = d.location[0];
            g.band_y = d.location[1];
            g.vbands = d.location[2] + 1;
            g.hbands = d.location[3] + 1;
            g.scale_x = d.bands[0];
            g.scale_y = d.bands[1];
            g.offset_x = d.bands[2];
            g.offset_y = d.bands[3];
            // the quad's pixels: the em box's corners mapped, a pixel around them
            double x0 = 1e9;
            double y0 = 1e9;
            double x1 = -1e9;
            double y1 = -1e9;
            for (const auto& [u, v] : {std::pair{d.box[0], d.box[1]}, std::pair{d.box[2], d.box[1]},
                                       std::pair{d.box[0], d.box[3]}, std::pair{d.box[2], d.box[3]}}) {
                const double px = (a * static_cast<double>(u)) + (b * static_cast<double>(v)) + tx;
                const double py = (c * static_cast<double>(u)) + (e * static_cast<double>(v)) + ty;
                x0 = std::min(x0, px);
                y0 = std::min(y0, py);
                x1 = std::max(x1, px);
                y1 = std::max(y1, py);
            }
            const int ix0 = std::max(0, static_cast<int>(std::floor(x0)) - 1);
            const int iy0 = std::max(0, static_cast<int>(std::floor(y0)) - 1);
            const int ix1 = std::min(static_cast<int>(width) - 1, static_cast<int>(std::ceil(x1)) + 1);
            const int iy1 = std::min(static_cast<int>(height) - 1, static_cast<int>(std::ceil(y1)) + 1);
            for (int y = iy0; y <= iy1; ++y) {
                for (int x = ix0; x <= ix1; ++x) {
                    const double sx = (x + 0.5) - tx;
                    const double sy = (y + 0.5) - ty;
                    const auto ex = static_cast<float>((i00 * sx) + (i01 * sy));
                    const auto ey = static_cast<float>((i10 * sx) + (i11 * sy));
                    const float cover = text::slug::coverage(atlas, g, ex, ey, 1.0F / fwx, 1.0F / fwy);
                    if (cover <= 0.0F) continue;
                    const std::size_t p = (static_cast<std::size_t>(y) * width) + static_cast<std::size_t>(x);
                    touched[p] = 1;
                    for (std::size_t k = 0; k < 3; ++k) {
                        const float src = d.colour.at(k) * cover;
                        const float alpha = d.colour[3] * cover;
                        const float dst = rgb[(3 * p) + k] / 255.0F;
                        rgb[(3 * p) + k] = std::round((src + (dst * (1.0F - alpha))) * 255.0F);
                    }
                }
            }
        }
    });
    reached = static_cast<std::size_t>(std::ranges::count(touched, 1));
    return rgb;
}

} // namespace

TEST_CASE("the canal names drawn: the glyphs' pixels are the CPU's coverage, on every configuration") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the canal names are not drawn here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    ATLAS_REQUIRE(text::shared_fonts().load_folder(ATLAS_FONTS_DIR).has_value());
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const std::array<std::string_view, 1> composite{"composite"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(composite);
    auto owned = std::make_unique<passes::GroundText>();
    const passes::GroundText* text = owned.get();
    passes.push_back(std::move(owned));
    auto builder = frame::FrameBuilder::create(device, *cache, std::move(passes), {.clear = clear, .steps = 0});
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    double now = 0;
    auto world = scene::World::create(device, (*builder)->passes(),
                                      {.workers = 3, .sun = {.enabled = false}, .clock = [&now] { return now; }});
    ATLAS_REQUIRE(world.has_value(), world.error().text());
    scene::World& w = **world; // the pass brings set 1's glyphs (GroundText::providers)
    auto added = w.store().add({.id = "amsterdam",
                                .kind = tiles::PackKind::bundled,
                                .path = (std::filesystem::path(dir) / "amsterdam.atlas").string()});
    ATLAS_REQUIRE(added.has_value(), tiles::describe(added.error()));
    (*builder)->set_inputs({.camera = home(), .seconds = now, .scene = &w});
    // The world's tiles for the view: the first frames ask, the workers build.
    for (std::uint64_t n = 1; n <= 40; ++n) {
        now = static_cast<double>(n) * 0.016;
        ATLAS_REQUIRE((*builder)->derive(n, 0, nullptr));
        w.update((*builder)->state());
        w.residency().wait_idle();
        if (w.cover() != nullptr && !w.cover()->tiles().empty() && w.cover()->missing() == 0) break;
    }
    ATLAS_REQUIRE(w.cover() != nullptr && !w.cover()->tiles().empty());

    const auto configs = configurations(device);
    ATLAS_REQUIRE(!configs.empty());
    std::size_t compared = 0;
    for (const gpu::Configuration& config : configs) {
        auto output = platform::Offscreen::create(device, {.width = 412, .height = 916}, 2, gpu::Depth::eight);
        ATLAS_REQUIRE(output.has_value(), output.error().text());
        auto renderer = gpu::Renderer::create(device, **output, **builder, config);
        ATLAS_REQUIRE(renderer.has_value(), gpu::name_of(config) << ": " << renderer.error().text());
        for (int f = 0; f < 3; ++f) {
            now += 0.016;
            (*builder)->set_inputs({.camera = home(), .seconds = now, .scene = &w});
            auto report = (*renderer)->draw(0);
            ATLAS_REQUIRE(report.has_value(), gpu::name_of(config) << ": " << report.error().text());
        }
        (*renderer)->drain();
        auto pixels = (*output)->read((*output)->latest());
        ATLAS_REQUIRE(pixels.has_value(), pixels.error().text());
        std::size_t reached = 0;
        const std::vector<float> want = expected(text->draws(), 412, 916, reached);
        // Within 2 levels; 3 with several samples, where a dilated quad's edge splits a pixel's samples.
        const float allowed = config.samples() == vk::SampleCountFlagBits::e1 ? 2.0F : 3.0F;
        std::size_t differ = 0;
        float worst = 0.0F;
        double sum = 0.0;
        std::size_t inked = 0;
        for (std::size_t p = 0; p < std::size_t{412} * 916; ++p) {
            for (std::size_t k = 0; k < 3; ++k) {
                const float got = pixels->rgba[(4 * p) + k];
                const float d = std::fabs(got - want[(3 * p) + k]);
                worst = std::max(worst, d);
                sum += static_cast<double>(d);
                differ += d > allowed ? 1U : 0U;
            }
            // the names' #EAF2FA over the clear shows in the blue channel
            inked += std::fabs(static_cast<float>(pixels->rgba[(4 * p) + 2]) - want[2]) > 8.0F ? 1U : 0U;
        }
        MESSAGE(
            std::format("{}: {} names, {} glyphs; {} pixels the glyphs reach, {} inked; the largest difference from "
                        "the CPU {} levels, the mean {:.4f}",
                        gpu::name_of(config), text->names().size(), text->draws().size(), reached, inked, worst,
                        sum / (3.0 * 412 * 916)));
        CHECK(text->names().size() == 8);
        CHECK(text->draws().size() > 50);
        CHECK(inked > 300);
        CHECK(differ == 0);
        if (compared == 0) {
            // The twin: the names as the port writes its <text> (the renders' runs, unturned), at their printed
            // matrices.
            twin::Stream stream;
            text->emit((*builder)->state(), stream, 0);
            const twin::Decoded d = twin::decode(stream.bytes());
            ATLAS_REQUIRE(d.error.empty(), d.error);
            ATLAS_REQUIRE(d.document.elements.size() == text->names().size());
            for (std::size_t i = 0; i < d.document.elements.size(); ++i) {
                const twin::Element& e = d.document.elements[i];
                const text::TextRun& run = text->names()[i].rule;
                CHECK(e.kind == twin::Op::text);
                CHECK(e.layer == twin::Layer::ground);
                CHECK(e.text == run.text);
                CHECK(e.node.transformed);
                CHECK(e.node.transform == run.snapped_matrix());
                CHECK(e.node.opacity == 0.85F);
                CHECK(e.family == "Geist, Helvetica, sans-serif");
                CHECK(e.font.size == 3.2F);
                CHECK(e.font.weight == 700);
                CHECK(e.font.letter_spacing == 0.3F);
                CHECK(e.font.anchor == twin::TextAnchor::middle);
                CHECK(e.style.fill.argb == 0xFFEAF2FAU);
            }
        }
        ++compared;
        renderer->reset();
    }
    CHECK(compared == configs.size());
    const auto messages = session->take_messages();
    for (const auto& m : messages) MESSAGE("validation: " << m);
    CHECK(messages.empty());
}
#endif

#if !defined(__ANDROID__)
TEST_CASE("the names from the pack whole: at the Main board the pass's names are the renders' rule over the pack's") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    const std::filesystem::path grachten = std::filesystem::path(dir) / "grachten.atlas";
    if (dir.empty() || !std::filesystem::exists(grachten)) {
        MESSAGE("ATLAS_TILES_DIR names no canal ring's pack: the Main board's names are not checked here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const std::array<std::string_view, 1> composite{"composite"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(composite);
    auto owned = std::make_unique<passes::GroundText>();
    passes::GroundText* text = owned.get();
    passes.push_back(std::move(owned));
    auto builder = frame::FrameBuilder::create(device, *cache, std::move(passes), {.clear = clear, .steps = 0});
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    double now = 0;
    auto world = scene::World::create(device, (*builder)->passes(),
                                      {.workers = 3, .sun = {.enabled = false}, .clock = [&now] { return now; }});
    ATLAS_REQUIRE(world.has_value(), world.error().text());
    scene::World& w = **world;
    auto added = w.store().add({.id = "grachten", .kind = tiles::PackKind::bundled, .path = grachten.string()});
    ATLAS_REQUIRE(added.has_value(), tiles::describe(added.error()));
    // The Main board's knot as the map's camera writes it (EngineVersion.knotView), 412 x 916 at density 1, at rest.
    auto pack = test::open_pack(grachten.string());
    ATLAS_REQUIRE(pack.has_value());
    const auto knot = pack->head()->view("grachten_home");
    ATLAS_REQUIRE(knot.has_value());
    const tiles::Frame& f = pack->head()->frame;
    const auto number = [&](std::string_view key, double fallback) {
        const auto v = knot->camera.find(key);
        return v ? v->number().value_or(fallback) : fallback;
    };
    const auto target = knot->camera.find("target");
    ATLAS_REQUIRE(target.has_value() && target->size() == 3);
    const double tx = target->child(0).number().value_or(0);
    const double tz = target->child(2).number().value_or(0);
    style::CameraState c{};
    c.version = style::camera_state_version;
    c.latitude = f.lat0 - (tz / f.kz);
    c.longitude = f.lon0 + (tx / f.kx);
    c.bearing = view::bearing_of(number("yaw", 0));
    c.pitch = 90.0 - number("pitch", 90);
    c.distance = number("dist", 400);
    c.principal_x = 206.0;
    c.principal_y = number("cy", 458.0);
    c.density = 1.0;
    c.width_px = 412;
    c.height_px = 916;
    c.width_dp = 412;
    c.height_dp = 916;
    c.flags = style::camera_flag::rest | style::camera_flag::engine_mode;
    (*builder)->set_inputs({.camera = c, .seconds = now, .scene = &w});
    for (std::uint64_t n = 1; n <= 60; ++n) {
        now = static_cast<double>(n) * 0.016;
        ATLAS_REQUIRE((*builder)->derive(n, 0, nullptr));
        w.update((*builder)->state());
        w.residency().wait_idle();
        if (w.cover() != nullptr && !w.cover()->tiles().empty() && w.cover()->missing() == 0) break;
    }
    twin::Stream stream;
    now += 1.0;
    (*builder)->set_inputs({.camera = c, .seconds = now, .scene = &w});
    (*builder)->emit(stream, 100);
    const frame::FrameState& state = (*builder)->state();
    ATLAS_REQUIRE(state.view.has_value() && state.placed.has_value());
    const double radius = state.placed.has_value() ? state.placed->number("radius").value_or(1e9) : 1e9;
    std::vector<std::string> from_cover;
    for (const auto& p : text->names()) {
        if (!p.leaving) from_cover.push_back(p.rule.svg());
    }
    // The renders' rule over the pack's names whole, at the frame's own camera and radius.
    auto canals = text::pack_canals(*pack);
    ATLAS_REQUIRE(canals.has_value());
    const view::Camera& cam = state.view->camera();
    const text::CanalView view{.camera = &cam,
                               .target_x = cam.spec().target.x,
                               .target_z = cam.spec().target.z,
                               .radius = radius,
                               .pitch = cam.spec().pitch,
                               .night = false,
                               .weather = false,
                               .water_level = canals->water_level,
                               .rest = true,
                               .seconds = now};
    std::vector<std::string> rule;
    for (const text::RuleName& r : text::canal_rule(canals->candidates, view)) rule.push_back(r.run.svg());
    // The pass reading the pack whole draws the rule's names.
    text->use_pack(std::make_shared<const text::PackCanals>(std::move(*canals)));
    now += 1.0;
    (*builder)->set_inputs({.camera = c, .seconds = now, .scene = &w});
    (*builder)->emit(stream, 101);
    std::vector<std::string> from_pack;
    std::size_t leaving = 0;
    for (const auto& p : text->names()) {
        if (p.leaving) {
            ++leaving; // the way a name left as the pack's names came, fading out
        } else {
            from_pack.push_back(p.rule.svg());
        }
    }
    std::size_t differ = 0;
    for (const std::string& s : from_cover) differ += std::ranges::find(rule, s) == rule.end() ? 1U : 0U;
    MESSAGE(
        std::format("the Main board: the rule draws {} names over the pack; from the cover's {} tiles the pass drew "
                    "{}, {} of them not the rule's; from the pack whole {}, and {} way(s) the names left fade out",
                    rule.size(), w.cover()->tiles().size(), from_cover.size(), differ, from_pack.size(), leaving));
    CHECK(from_pack == rule);
    CHECK(rule.size() > 50);
}
#endif
