// The doors' pass on the host's Vulkan (ENGINE.md step 4, the facades; lavapipe): the bundled Westerkerk
// (ATLAS_TILES_DIR) close over its densest row of doors, by day and on the wet night, drawn through the frame builder
// and the world with that pass alone beside the composite (the world given the glyphs' globals, the fonts loaded), at
// rest and moving, at 1x and 4x, at true depth and under the painter switch, against the exact raster of the records
// the pass gives the GPU (tests/item/item_raster.hpp: the doors' and the stoops' faces, the plates' rounded rects, the
// outlines' segments), then the numbers' glyphs blended over it in their order on the samples their dilated quads cover
// and their depth passes, each at the CPU's Slug coverage at the pixel's centre (text/SlugBands, through the identity
// table), two levels apart at most there. The twin's elements are held to the port's by L1; here the GPU draws the
// pass's records.
#if !defined(__ANDROID__)

#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "gpu/PipelineCache.hpp"
#include "passes/Doors.hpp"
#include "passes/GroundText.hpp"
#include "scene/World.hpp"
#include "style/Theme.hpp"
#include "tests/base/require.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/ground/ground_support.hpp"
#include "tests/item/item_raster.hpp"
#include "tests/tiles/support.hpp"
#include "text/Fonts.hpp"
#include "text/SlugBands.hpp"
#include "tiles/Source.hpp"
#include "twin/Twin.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <bit>
#include <cmath>
#include <format>
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
    passes::Doors* doors = nullptr;
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
    // the registry's composite and the doors' pass of its own (the test reads its records)
    constexpr std::array<std::string_view, 1> composite{"composite"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(composite);
    auto doors = std::make_unique<passes::Doors>();
    s.doors = doors.get();
    passes.push_back(std::move(doors));
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

/// The numbers' glyphs blended over raster `r` in their order: on each sample their dilated quad covers (the vertex
/// stage's dilation by half a device pixel) whose depth their own passes (GREATER), at the coverage the CPU's Slug
/// gives at the pixel's centre, rounded to levels as the GPU blends; such a sample may lie two levels off. The samples
/// blended over, and the pixels a glyph covers some of.
struct Blended {
    std::size_t samples = 0;
    std::size_t pixels = 0;
};
Blended blend_glyphs(test::items::Raster& r, std::span<const layouts::text_slug::GlyphDraw> glyphs,
                     const sh::FrameCamera& cam) {
    Blended out;
    const auto density = static_cast<double>(cam.frame.z);
    const std::size_t n = r.samples.size();
    std::vector<std::uint8_t> touched(std::size_t{r.w} * r.h, 0);
    text::slug::shared_glyphs().read([&](const text::slug::Atlas& atlas) {
        for (const auto& d : glyphs) {
            // em to device px: density · (M · em + t)
            const double a = static_cast<double>(d.row0[0]) * density;
            const double b = static_cast<double>(d.row0[1]) * density;
            const double c = static_cast<double>(d.row1[0]) * density;
            const double e = static_cast<double>(d.row1[1]) * density;
            const double tx = static_cast<double>(d.row0[2]) * density;
            const double ty = static_cast<double>(d.row1[2]) * density;
            const double det = (a * e) - (b * c);
            const double i00 = e / det;
            const double i01 = -b / det;
            const double i10 = -c / det;
            const double i11 = a / det;
            const auto fwx = static_cast<float>(std::fabs(i00) + std::fabs(i01));
            const auto fwy = static_cast<float>(std::fabs(i10) + std::fabs(i11));
            // the vertex stage's dilation, in em units (slug.slang's glyph_corner)
            const auto cdet = static_cast<double>((d.row0[0] * d.row1[1]) - (d.row0[1] * d.row1[0]));
            const double mx = 0.5 *
                              (std::fabs(static_cast<double>(d.row1[1])) + std::fabs(static_cast<double>(d.row0[1]))) /
                              (std::fabs(cdet) * density);
            const double my = 0.5 *
                              (std::fabs(static_cast<double>(d.row1[0])) + std::fabs(static_cast<double>(d.row0[0]))) /
                              (std::fabs(cdet) * density);
            const double bx0 = static_cast<double>(d.box[0]) - mx;
            const double by0 = static_cast<double>(d.box[1]) - my;
            const double bx1 = static_cast<double>(d.box[2]) + mx;
            const double by1 = static_cast<double>(d.box[3]) + my;
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
            const auto depth = static_cast<double>(std::bit_cast<float>(d.info[2]));
            double x0 = 1e9;
            double y0 = 1e9;
            double x1 = -1e9;
            double y1 = -1e9;
            for (const auto& [u, v] :
                 {std::pair{bx0, by0}, std::pair{bx1, by0}, std::pair{bx0, by1}, std::pair{bx1, by1}}) {
                x0 = std::min(x0, (a * u) + (b * v) + tx);
                y0 = std::min(y0, (c * u) + (e * v) + ty);
                x1 = std::max(x1, (a * u) + (b * v) + tx);
                y1 = std::max(y1, (c * u) + (e * v) + ty);
            }
            const int ix0 = std::max(0, static_cast<int>(std::floor(x0)) - 1);
            const int iy0 = std::max(0, static_cast<int>(std::floor(y0)) - 1);
            const int ix1 = std::min(static_cast<int>(r.w) - 1, static_cast<int>(std::ceil(x1)) + 1);
            const int iy1 = std::min(static_cast<int>(r.h) - 1, static_cast<int>(std::ceil(y1)) + 1);
            const auto em_at = [&](double px, double py) {
                const double sx = px - tx;
                const double sy = py - ty;
                return std::pair{(i00 * sx) + (i01 * sy), (i10 * sx) + (i11 * sy)};
            };
            for (int y = iy0; y <= iy1; ++y) {
                for (int x = ix0; x <= ix1; ++x) {
                    const auto [ex, ey] = em_at(x + 0.5, y + 0.5);
                    const float cover = text::slug::coverage(atlas, g, static_cast<float>(ex), static_cast<float>(ey),
                                                             1.0F / fwx, 1.0F / fwy);
                    if (cover <= 0.0F) continue;
                    const std::size_t p = (static_cast<std::size_t>(y) * r.w) + static_cast<std::size_t>(x);
                    for (std::size_t q = 0; q < n; ++q) {
                        const auto [sx, sy] =
                            em_at(x + static_cast<double>(r.samples[q].x), y + static_cast<double>(r.samples[q].y));
                        if (sx < bx0 || sx > bx1 || sy < by0 || sy > by1) continue;
                        const std::size_t k = (p * n) + q;
                        if (r.covered[k] != 0 && !(depth > r.depth[k])) continue;
                        touched[p] = 1;
                        hlsl::float3& under = r.colour[k];
                        const float alpha = d.colour[3] * cover;
                        const auto over = [&](float dst, float src) {
                            return std::round(((src * cover) + (dst * (1.0F - alpha))) * 255.0F) / 255.0F;
                        };
                        under = {over(under.x, d.colour[0]), over(under.y, d.colour[1]), over(under.z, d.colour[2])};
                        r.blended[k] = static_cast<std::uint8_t>(std::min(255, r.blended[k] + 2));
                        if (r.covered[k] == 0) r.covered[k] = 2;
                        ++out.samples;
                    }
                }
            }
        }
    });
    out.pixels = static_cast<std::size_t>(std::ranges::count(touched, 1));
    return out;
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

/// The Westerkerk's densest row of doors (115 within 40 m about its door at (70.16, -198.94) of the pack's frame, the
/// walls facing west-north-west), looked at squarely: the target (`x`, `z`) behind it, the map's `pitch`, `distance`
/// back (L1Test's doors cases).
[[nodiscard]] style::CameraState door_row(double x, double z, double pitch, double distance) {
    const double kx = 111320 * pymath::cos(pymath::radians(52.3746));
    return camera(52.3746 - (z / 110540.0), 4.8840 + (x / kx), 119.50786615381202, pitch, distance);
}

/// Draws `cam` at 1x and 4x, at rest and moving, at true depth and under the painter switch, at moment `th`, and holds
/// every pixel to the exact raster of the pass's records and its numbers' glyphs; at least `least` doors drawn, and
/// where `plates`, some with their plates.
void check_frames(const style::CameraState& cam, const std::string& view, const std::string& pack,
                  const style::ThemeBlock& th, std::uint32_t least, bool plates = true) {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
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
                twin::Stream stream;
                s.doors->emit(state, stream, passes::Doors::draw_phase);
                const twin::Decoded decoded = twin::decode(stream.bytes());
                ATLAS_REQUIRE(decoded.error.empty(), decoded.error);
                std::size_t rects = 0;
                std::size_t texts = 0;
                for (const twin::Element& e : decoded.document.elements) {
                    rects += e.kind == twin::Op::rect ? 1U : 0U;
                    texts += e.kind == twin::Op::text ? 1U : 0U;
                }
                const passes::Doors::Counts counts = s.doors->counts();
                test::items::Raster r = test::items::raster(s.doors->faces(), s.doors->parts(), {}, {}, state.block,
                                                            config, pixels->width, pixels->height, clear);
                const Blended b = blend_glyphs(r, s.doors->glyphs(), state.block.camera);
                const test::items::Compared got = test::items::compare(*pixels, r, config);
                const std::string name = std::format("{} {} {}{}", view, gpu::name_of(config),
                                                     rest ? "at rest" : "moving", painter ? " (painter's order)" : "");
                MESSAGE(name << ": " << counts.doors << " doors, " << rects << " plates and " << texts
                             << " numbers in the twin; " << counts.faces << " faces, " << counts.parts
                             << " outlines' segments, " << counts.glyphs << " glyphs (" << b.pixels << " pixels, "
                             << b.samples << " samples blended); " << got.pixels << " pixels of doors, " << got.agree
                             << " the exact raster's, " << got.boundary << " within 0.02 px of a shape's boundary, "
                             << got.tied << " where two colours tie in depth, " << got.seam
                             << " on the composite's seam, " << got.wrong << " otherwise");
                CHECK(counts.doors >= least);
                CHECK(rects == texts);
                if (plates) {
                    CHECK(rects > 0);
                    CHECK(counts.glyphs > 0);
                    CHECK(b.pixels > 0);
                }
                CHECK_MESSAGE(got.wrong == 0, name << ": " << got.first);
                CHECK(static_cast<double>(got.boundary + got.tied) <= 0.01 * static_cast<double>(got.pixels));
                for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, name << ": " << m);
                s.renderer.reset();
            }
        }
    }
}

} // namespace

TEST_CASE("doors: a row of doors, stoops, plates and numbers on the host's Vulkan, pixel for pixel the exact "
          "shapes of their records, by day and on the wet night (ATLAS_TILES_DIR)") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the bundled Westerkerk is not drawn here");
        return;
    }
    ATLAS_REQUIRE(text::shared_fonts().load_folder(ATLAS_FONTS_DIR).has_value());
    const std::string pack = dir + "/amsterdam.atlas";
    // from 50 m, 25 m up: the doors, their stoops, and their plates with their numbers
    const style::CameraState near = door_row(82.21656426574002, -192.11612399270084, 60.0, 50.0);
    check_frames(near, "the row of doors by day", pack, test::sunny(), 10);
    check_frames(near, "the row of doors on the wet night", pack, test::night_weather(), 10);
    // at the map's nearest zoom and the gestures' steepest tilt: the doors in the frame's lower part, their plates
    // (within 45 m) at its foot if at all
    check_frames(door_row(98.86787550282801, -182.6922473097533, 70.0, 91.0), "the row of doors from the map's nearest",
                 pack, test::sunny(), 5, false);
}

#endif
