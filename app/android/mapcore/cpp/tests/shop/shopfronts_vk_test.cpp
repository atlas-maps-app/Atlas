// The shopfronts' pass on the host's Vulkan (ENGINE.md step 4, the facades and the night; lavapipe): the bundled
// Westerkerk (ATLAS_TILES_DIR) from 70 m over two walls of shopfronts that show between them every state of the user's
// rule (4 November) by day and on the wet night, and along a street of 29 from 120 m, drawn through the frame builder
// and the world with that pass alone beside the composite (the world given the glyphs' globals, the fonts loaded), in
// every configuration and route of the host's device (composite_vk_test's list: on lavapipe 1x and C, each by the
// render pass and by dynamic rendering), at rest and moving, at true depth and under the painter switch, against the
// exact raster of the records the pass gives the GPU (tests/item/item_raster.hpp: the fronts' and the terraces' faces,
// the glass by day its two stops, the outlines and the lines), then the signs' glyphs (tests/item/glyph_raster.hpp),
// the awnings' stripes and the night's spill, and at night the open places' glows blended over it in the GPU's order
// (with the daylight, ATLAS_DAYLIGHT, larger and first their pools on the ground, under every object).
// Each frame's shopfronts are held to their opening hours at its moment: a food place with outdoor seating sets its
// terrace out while it is open, and by day where its hours say nothing; none while it is closed; its glass lit while it
// is open. Each switch leaves out its own part alone. The twin's elements are held to the port's by L1; here the GPU
// draws the pass's records.
#if !defined(__ANDROID__)

#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "gpu/PipelineCache.hpp"
#include "passes/GroundText.hpp"
#include "passes/Shopfronts.hpp"
#include "scene/Cover.hpp"
#include "scene/Packs.hpp"
#include "scene/Residency.hpp"
#include "scene/World.hpp"
#include "style/OpeningHours.hpp"
#include "style/Theme.hpp"
#include "tests/base/require.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/ground/ground_support.hpp"
#include "tests/item/glyph_raster.hpp"
#include "tests/item/item_raster.hpp"
#include "tests/tiles/support.hpp"
#include "text/Fonts.hpp"
#include "tiles/Source.hpp"
#include "twin/Twin.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <format>
#include <map>
#include <memory>
#include <span>
#include <string>
#include <vector>

using namespace atlas;

namespace {

namespace sh = atlas::shaders;

struct Scene {
    std::unique_ptr<gpu::Offscreen> output;
    std::unique_ptr<frame::FrameBuilder> builder;
    std::unique_ptr<scene::World> world;
    std::unique_ptr<gpu::Renderer> renderer;
    passes::Shopfronts* shops = nullptr;
    double now = 0;
    bool settled = false;
};

[[nodiscard]] std::optional<Scene> make_scene(const test::Session& session, const gpu::PipelineCache& cache,
                                              const gpu::Configuration& config, std::uint32_t w, std::uint32_t h,
                                              const std::string& pack, std::uint32_t clear) {
    const gpu::Device& device = *session.device;
    Scene s;
    auto output = gpu::Offscreen::create(device, {.width = w, .height = h}, 2);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, output.has_value(), output.error().text());
    s.output = std::move(*output);
    // the registry's composite and the shopfronts' pass of its own (the test reads its records)
    constexpr std::array<std::string_view, 1> composite{"composite"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(composite);
    auto shops = std::make_unique<passes::Shopfronts>();
    s.shops = shops.get();
    passes.push_back(std::move(shops));
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
    // the glyphs' globals, which the ground's text pass brings where it is drawn
    auto glyphs = s.world->add_globals(std::make_shared<passes::GlyphGlobals>(device));
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, glyphs.has_value(), glyphs.error().text());
    auto added = s.world->store().add({.id = "bundled", .kind = tiles::PackKind::bundled, .path = pack});
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, added.has_value(), tiles::describe(added.error()));
    auto renderer = gpu::Renderer::create(device, *s.output, *s.builder, config);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, renderer.has_value(), renderer.error().text());
    s.renderer = std::move(*renderer);
    return s;
}

/// Draws `cam` until the world has built every tile the cover wants and their fades are over; the last frame's pixels.
[[nodiscard]] std::optional<gpu::Pixels> draw(Scene& s, const style::CameraState& cam, const style::ThemeBlock& th) {
    for (int n = 0; n < 61; ++n) {
        s.now += 0.25;
        s.builder->set_inputs(
            {.camera = cam, .theme = th, .seconds = s.now, .mode = frame::Mode::structural, .scene = s.world.get()});
        auto report = s.renderer->draw(0);
        if (!report) {
            FAIL("a frame failed: " << report.error().text());
            return std::nullopt;
        }
        s.renderer->drain();
        if (s.settled) break; // this frame drawn with every tile taken
        s.world->residency().wait_idle();
        const scene::Cover& cover = *s.world->cover();
        s.settled = n > 4 && cover.missing() == 0 && !cover.tiles().empty() &&
                    std::ranges::all_of(cover.tiles(), [](const scene::CoverTile& t) { return t.fade >= 1.0F; });
    }
    auto pixels = s.output->read(s.output->latest());
    if (!pixels) {
        FAIL("the frame could not be read: " << pixels.error().text());
        return std::nullopt;
    }
    return std::move(*pixels);
}

/// A camera state over the bundled Westerkerk, at 540 × 1200 px (412 dp wide), in engine mode.
[[nodiscard]] style::CameraState camera(double lat, double lon, double bearing, double pitch, double distance) {
    style::CameraState c = test::s1_camera(lat, lon, bearing, pitch, distance);
    c.width_px = 540;
    c.height_px = 1200;
    c.density = 540.0 / 412.0;
    c.flags = style::camera_flag::engine_mode;
    return c;
}

/// A row of shopfronts looked at squarely: the point (`qx`, `qz`) of the pack's frame on their street wall, its
/// direction (`ux`, `uz`; the wall facing (uz, -ux), as the pass's test asks), the target `back` metres behind it, the
/// map's `pitch`, `distance` back from the target.
[[nodiscard]] style::CameraState shop_row(double qx, double qz, double ux, double uz, double back, double pitch,
                                          double distance) {
    const double kx = 111320 * pymath::cos(pymath::radians(52.3746));
    // looking along (-uz, ux), a bearing clockwise from north (-z)
    const double x = qx - (uz * back);
    const double z = qz + (ux * back);
    const double bearing = pymath::degrees(pymath::atan2(-uz, -ux));
    return camera(52.3746 - (z / 110540.0), 4.8840 + (x / kx), bearing < 0 ? bearing + 360 : bearing, pitch, distance);
}

/// The live map's night at the renders' `wet` moment, 30 September 2026 at 21:30 (amsterdam.py's THEMES).
[[nodiscard]] style::ThemeBlock wet_night() {
    style::ThemeBlock b = test::night_weather();
    b.year = 2026;
    b.month = 9;
    b.day = 30;
    b.hour = 21;
    b.minute = 30;
    return b;
}

/// The same night with the daylight (RenderOptions.daylight, amsterdam.py's DAYLIGHT): the open places' glows larger,
/// each over its pool on the ground.
[[nodiscard]] style::ThemeBlock wet_daylight_night() {
    style::ThemeBlock b = wet_night();
    b.flags |= style::theme_flag::daylight;
    return b;
}

/// What the frame's shopfronts say against their opening hours at its moment: how many are open, closed and without
/// hours, how many of each with outdoor seating draw a terrace, and how many break the user's rule.
struct Rule {
    std::array<std::size_t, 3> shops{};    // open, closed, none
    std::array<std::size_t, 3> seated{};   // of those with outdoor seating
    std::array<std::size_t, 3> terraces{}; // of those, drawing tables
    std::size_t broken = 0;
    std::size_t lit_wrong = 0;
};

[[nodiscard]] Rule rule_of(const frame::FrameState& state, const passes::Shopfronts& pass,
                           const passes::ShopFrame& frame) {
    Rule out;
    // each shopfront's seating and hours by its OSM node, from the tiles the cover draws
    struct Kept {
        bool seating = false;
        std::string hours;
    };
    std::map<std::int64_t, Kept> by_id;
    for (const scene::CoverTile& t : state.cover->upright()) {
        if (t.drawn == nullptr || pass.index() >= t.drawn->passes.size()) continue;
        const auto* kept = t.drawn->passes[pass.index()].cpu_as<passes::ShopTile>();
        if (kept == nullptr) continue;
        for (const geometry::Shopfront& s : kept->shops) {
            by_id[s.id] = {.seating = s.seating, .hours = s.hours < kept->hours.size() ? kept->hours[s.hours] : ""};
        }
    }
    const style::ThemeBlock& th = state.theme;
    const bool night = th.has(style::theme_flag::night);
    const style::Date date{.year = th.year, .month = th.month, .day = th.day};
    const auto day = style::weekday_of(date);
    for (const passes::ShopShape& s : frame.shops) {
        const auto it = by_id.find(s.id);
        REQUIRE(it != by_id.end());
        std::optional<bool> now;
        if (!it->second.hours.empty() && day) {
            now = style::open_at(it->second.hours, *day, {.hour = th.hour, .minute = th.minute}, date);
        }
        const std::size_t k = !now ? 2 : *now ? 0 : 1;
        ++out.shops.at(k);
        if (s.open != (now == true)) ++out.lit_wrong;
        if (s.board && !night && s.board->glass_lit != (now == true)) ++out.lit_wrong;
        if (!it->second.seating) {
            if (!s.tables.empty()) ++out.broken;
            continue;
        }
        ++out.seated.at(k);
        const bool terrace = now == true || (!now && !night);
        if (!s.tables.empty()) ++out.terraces.at(k);
        if (!terrace && !s.tables.empty()) ++out.broken;
    }
    return out;
}

/// What a frame must show: at least `shops` shopfronts, some glyphs, the states (open, closed, none) whose shopfronts
/// with outdoor seating are seen (`seated`) and draw a terrace (`terraces`), and at night where `glows`, some glows.
struct Wants {
    std::uint32_t shops = 1;
    std::array<bool, 3> seated{};
    std::array<bool, 3> terraces{};
    bool glows = false;
};

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

/// Draws `cam` in every configuration and route of the host's device, at rest and moving, at true depth and under the
/// painter switch, at moment `th`, and holds every pixel to the exact raster of the pass's records, its pools, its
/// signs' glyphs, its tints and its glows.
void check_frames(const style::CameraState& cam, const std::string& view, const std::string& pack,
                  const style::ThemeBlock& th, const Wants& wants) {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    constexpr std::uint32_t clear = 0x808080;
    const bool night = th.has(style::theme_flag::night);
    const bool daylight = th.has(style::theme_flag::daylight);
    const std::vector<gpu::Configuration> configs = configurations(device);
    REQUIRE(!configs.empty());
    for (const gpu::Configuration config : configs) {
        for (const bool painter : {false, true}) {
            for (const bool rest : {true, false}) {
                style::CameraState c = cam;
                c.flags = rest ? (c.flags | style::camera_flag::rest) : (c.flags & ~style::camera_flag::rest);
                auto made = make_scene(*session, *cache, config, c.width_px, c.height_px, pack, clear);
                ATLAS_REQUIRE(made.has_value());
                Scene& s = *made;
                s.builder->set_painter(painter);
                const auto pixels = draw(s, c, th);
                ATLAS_REQUIRE(pixels.has_value());
                CHECK_MESSAGE(s.settled, view << ": the world did not settle");
                const frame::FrameState& state = s.builder->state();
                const passes::Shopfronts::Counts n = s.shops->counts();
                const std::span<const sh::ItemRecord> faces = s.shops->faces();
                const std::size_t opaque = std::size_t{n.front_faces} + n.terrace_faces;
                REQUIRE(faces.size() == opaque + n.tints);
                test::items::Raster r = test::items::raster(faces.first(opaque), s.shops->parts(), {}, {}, state.block,
                                                            config, pixels->width, pixels->height, clear);
                test::items::blend_radials(r, s.shops->pools(), state.block);
                const test::items::Blended b = test::items::blend_glyphs(r, s.shops->glyphs(), state.block.camera);
                test::items::blend_faces(r, faces.subspan(opaque), state.block);
                test::items::blend_radials(r, s.shops->glows(), state.block);
                const test::items::Compared got = test::items::compare(*pixels, r, config);
                // the frame's shopfronts against their hours (the pass's own decisions, made again)
                const tiles::Style* style = state.active_pack->source->pack.style();
                REQUIRE(style != nullptr);
                passes::OpenHours hours;
                const passes::ShopFrame frame =
                    passes::shop_shapes(state, s.shops->index(), s.shops->shop_kinds(), style->shopfronts, hours);
                const Rule rule = rule_of(state, *s.shops, frame);
                // the glass by day its two stops, at night its colour
                std::size_t vertical = 0;
                for (const sh::ItemRecord& f : faces) vertical += (f.a.x & sh::ITEM_VERTICAL) != 0U ? 1U : 0U;
                twin::Stream stream;
                s.shops->emit(state, stream, passes::Shopfronts::draw_phase);
                const twin::Decoded decoded = twin::decode(stream.bytes());
                ATLAS_REQUIRE(decoded.error.empty(), decoded.error);
                std::size_t texts = 0;
                std::size_t circles = 0;
                std::size_t ellipses = 0;
                for (const twin::Element& e : decoded.document.elements) {
                    texts += e.kind == twin::Op::text ? 1U : 0U;
                    circles += e.kind == twin::Op::circle ? 1U : 0U;
                    ellipses += e.kind == twin::Op::ellipse && e.layer == twin::Layer::ground ? 1U : 0U;
                }
                const std::string name = std::format("{} {} {}{}", view, gpu::name_of(config),
                                                     rest ? "at rest" : "moving", painter ? " (painter's order)" : "");
                const double update_ms = s.builder->stage_f_parts().pass_ms.at(s.shops->index());
                MESSAGE(name << ": " << n.shops << " shopfronts (open, closed, without hours: " << rule.shops[0] << ", "
                             << rule.shops[1] << ", " << rule.shops[2] << "; seated " << rule.seated[0] << ", "
                             << rule.seated[1] << ", " << rule.seated[2] << "; with a terrace " << rule.terraces[0]
                             << ", " << rule.terraces[1] << ", " << rule.terraces[2] << "), " << n.tables << " tables, "
                             << n.glows << " glows, " << n.pools << " pools; " << texts << " texts, " << circles
                             << " glows and " << ellipses << " pools in the twin; " << n.front_faces << " + "
                             << n.terrace_faces << " faces (" << vertical << " two-stop), " << n.tints << " tints, "
                             << n.front_parts << " + " << n.terrace_parts << " lines, " << n.glyphs << " glyphs ("
                             << b.pixels << " pixels, " << b.samples << " samples blended); " << got.pixels
                             << " pixels drawn, " << got.agree << " the exact raster's, " << got.boundary
                             << " within 0.02 px of a shape's boundary, " << got.tied
                             << " where two colours tie in depth, " << got.seam << " on the composite's seam, "
                             << got.wrong << " otherwise; the pass's stage F " << update_ms << " ms");
                CHECK(n.shops >= wants.shops);
                CHECK(frame.shops.size() == n.shops);
                CHECK(frame.glows.size() == n.glows);
                CHECK(frame.pools.size() == n.pools);
                CHECK(rule.broken == 0);
                CHECK(rule.lit_wrong == 0);
                for (std::size_t k = 0; k < 3; ++k) {
                    if (wants.terraces.at(k)) CHECK(rule.terraces.at(k) > 0);
                    if (wants.seated.at(k)) CHECK(rule.seated.at(k) > 0);
                }
                CHECK(circles == n.glows);
                CHECK(ellipses == n.pools);
                if (night) {
                    if (wants.glows) CHECK(n.glows > 0);
                    CHECK(vertical == 0);
                    // with the daylight each glow over its pool, max(4, 8 f / z) px
                    CHECK(n.pools == (daylight ? n.glows : 0U));
                    if (daylight) {
                        for (const passes::GlowShape& g : frame.glows) CHECK(g.r >= 4.0);
                    }
                } else {
                    CHECK(n.glows == 0);
                    CHECK(n.pools == 0);
                    CHECK(vertical > 0);
                }
                CHECK(texts > 0);
                CHECK(n.glyphs > 0);
                CHECK(b.pixels > 0);
                CHECK_MESSAGE(got.wrong == 0, name << ": " << got.first);
                CHECK(static_cast<double>(got.boundary + got.tied) <= 0.01 * static_cast<double>(got.pixels));
                for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, name << ": " << m);
                if (config.config == gpu::FrameConfig::x1 && config.route == gpu::Route::renderpass && rest &&
                    !painter) {
                    // each switch alone: the fronts left out keep their terraces, the terraces left out their fronts,
                    // the glows left out none
                    const auto with = [&](std::uint64_t off) {
                        style::CameraState o = c;
                        o.parts = off;
                        const auto drawn = draw(s, o, th);
                        REQUIRE(drawn.has_value());
                        return s.shops->counts();
                    };
                    const passes::Shopfronts::Counts no_fronts = with(style::camera_part::shopfronts);
                    CHECK(no_fronts.front_faces == 0);
                    CHECK(no_fronts.front_parts == 0);
                    CHECK(no_fronts.glyphs == 0);
                    CHECK(no_fronts.tables == n.tables);
                    CHECK(no_fronts.glows == n.glows);
                    const passes::Shopfronts::Counts no_terraces = with(style::camera_part::terraces);
                    CHECK(no_terraces.tables == 0);
                    CHECK(no_terraces.terrace_faces == 0);
                    CHECK(no_terraces.front_faces == n.front_faces);
                    const passes::Shopfronts::Counts no_glows = with(style::camera_part::open_glows);
                    CHECK(no_glows.glows == 0);
                    CHECK(no_glows.pools == 0);
                    CHECK(no_glows.shops == n.shops);
                    const passes::Shopfronts::Counts facades = with(style::camera_part::facade_detail);
                    CHECK(facades.shops == 0);
                    CHECK(facades.glows == n.glows);
                }
                s.renderer.reset();
            }
        }
    }
}

} // namespace

TEST_CASE("shopfronts: a row of shopfronts, their signs and terraces, and at night the open places' glows on the "
          "host's Vulkan, pixel for pixel the exact shapes of their records (ATLAS_TILES_DIR)") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the bundled Westerkerk is not drawn here");
        return;
    }
    ATLAS_REQUIRE(text::shared_fonts().load_folder(ATLAS_FONTS_DIR).has_value());
    const std::string pack = dir + "/amsterdam.atlas";
    // the wall facing east at x 435 of the pack's frame, from 70 m (35 m up): by day a brown café open with its terrace
    // out and a café closed without, at night both open with theirs, and the open places' glows
    const style::CameraState east = shop_row(435.44818, 297.36354, 0.013287, 0.999912, 10.0, 60.0, 70.0);
    check_frames(east, "the wall facing east by day", pack, test::sunny(),
                 {.shops = 4, .seated = {true, true, false}, .terraces = {true, false, false}});
    check_frames(east, "the wall facing east on the wet night", pack, wet_night(),
                 {.shops = 4, .seated = {true, false, false}, .terraces = {true, false, false}, .glows = true});
    // and with the daylight the glows larger over their pools on the ground
    check_frames(east, "the wall facing east on the wet night with the daylight", pack, wet_daylight_night(),
                 {.shops = 4, .seated = {true, false, false}, .terraces = {true, false, false}, .glows = true});
    // the row facing south about (-330, 160), from 70 m: by day a restaurant without opening hours with its terrace, at
    // night it without and the two places closed then without theirs
    const style::CameraState south = shop_row(-330.23809, 160.29078, -0.925907, 0.377766, 10.0, 60.0, 70.0);
    check_frames(south, "the row facing south by day", pack, test::sunny(),
                 {.shops = 8, .seated = {true, false, true}, .terraces = {false, false, true}});
    check_frames(south, "the row facing south on the wet night", pack, wet_night(),
                 {.shops = 8, .seated = {false, true, true}});
    // along the street about (-124, -214), its wall's way (0.334388, 0.942436), from 120 m and 70 degrees down: 29
    // shopfronts, the blades seen edge on with their text down them; by day two places open with their terraces, at
    // night those two open still, and the open places' glows
    const double kx = 111320 * pymath::cos(pymath::radians(52.3746));
    const double bearing = pymath::degrees(pymath::atan2(0.334388, -0.942436));
    const style::CameraState along = camera(52.3746 - (-214.05 / 110540.0), 4.8840 + (-124.28 / kx),
                                            bearing < 0 ? bearing + 360 : bearing, 70.0, 120.0);
    check_frames(along, "the street of shopfronts by day", pack, test::sunny(),
                 {.shops = 29, .seated = {true, false, false}, .terraces = {true, false, false}});
    check_frames(along, "the street of shopfronts on the wet night", pack, wet_night(),
                 {.shops = 29, .seated = {true, false, false}, .terraces = {true, false, false}, .glows = true});
}

#endif
