// A region's items' pass on the host's Vulkan (ENGINE.md step 4, "Trees and things"; lavapipe): a window built from
// Overpass's JSON with a tower and its cab, a turbine, a crane on a quay and one in a yard, a lighting mast, a gantry,
// a goods conveyor and a power line, by day and at night, near and where the power line fades, and the canal ring's
// towers and masts (ATLAS_TILES_DIR), drawn through the frame builder and the world with that pass alone beside the
// composite, at rest and moving, at 1x and 4x, at true depth and under the painter switch, against an exact CPU raster
// of the records the twin says items_cs writes: each face's fan by its edges and its stroke's edges sample by sample
// (items.slangh's own tests, in float, at the samples' CSS points), each segment's and circle's samples; each sample's
// depth as the rasteriser interpolates the vertex stage's corners over the triangle it lies in (item_segment_corner's
// quads for the strokes and segments); each sample ending with the record of the greatest depth (the first drawn where
// two tie, as GREATER keeps it), a sample where two of different colours lie within the rasteriser's rounding of each
// other counted apart, and a sample within 0.02 px of a shape's boundary; then the translucent records (the conductors
// leaving the samples their path's earlier segments cover, a glow at its gradient, a light) blended over it in the
// GPU's order at each one's opacity where its depth passes (within a level a blend: lavapipe blends in fixed point);
// the samples averaged as the composite averages them. The twin's elements are held to the port's by L1; here its
// records are the GPU's.
#if !defined(__ANDROID__)

#include "base/arena.hpp"
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "geometry/Items.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "gpu/PipelineCache.hpp"
#include "load/Amsterdam.hpp"
#include "load/Window.hpp"
#include "passes/Composite.hpp"
#include "passes/Items.hpp"
#include "scene/World.hpp"
#include "style/Theme.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/ground/ground_support.hpp"
#include "tests/item/item_raster.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Source.hpp"
#include "twin/Twin.hpp"
#include "twin/item/ItemShader.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <filesystem>
#include <format>
#include <fstream>
#include <memory>
#include <span>
#include <string>
#include <vector>

using namespace atlas;

namespace {

namespace sh = atlas::shaders;

struct PackFile {
    std::string id;
    tiles::PackKind kind = tiles::PackKind::bundled;
    std::string path;
};

struct Scene {
    std::unique_ptr<gpu::Offscreen> output;
    std::unique_ptr<frame::FrameBuilder> builder;
    std::unique_ptr<scene::World> world;
    std::unique_ptr<gpu::Renderer> renderer;
    passes::Items* items = nullptr;
    double now = 0;
    bool settled = false;
};

[[nodiscard]] std::optional<Scene> make_scene(const test::Session& session, const gpu::PipelineCache& cache,
                                              const gpu::Configuration& config, std::uint32_t w, std::uint32_t h,
                                              std::span<const PackFile> packs, std::uint32_t clear) {
    const gpu::Device& device = *session.device;
    Scene s;
    auto output = gpu::Offscreen::create(device, {.width = w, .height = h}, 2);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, output.has_value(), output.error().text());
    s.output = std::move(*output);
    // the registry's composite and the items' pass of its own (the test reads its twin)
    constexpr std::array<std::string_view, 1> composite{"composite"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(composite);
    auto items = std::make_unique<passes::Items>();
    s.items = items.get();
    passes.push_back(std::move(items));
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
    for (const PackFile& pack : packs) {
        auto added = s.world->store().add({.id = pack.id, .kind = pack.kind, .path = pack.path});
        ATLAS_REQUIRE_OR_RETURN(std::nullopt, added.has_value(), tiles::describe(added.error()));
    }
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

using test::items::compare;
using test::items::Compared;

/// Draws `cam` at 1x and 4x, at rest and moving, at true depth and under the painter switch, at moment `th`, and
/// holds every pixel to the exact raster of the twin's records; the twin emits at least `least` elements.
void check_frames(const style::CameraState& cam, const std::string& view, std::span<const PackFile> packs,
                  const style::ThemeBlock& th, std::size_t least) {
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
                auto made = make_scene(*session, *cache, config, c.width_px, c.height_px, packs, clear);
                ATLAS_REQUIRE(made.has_value());
                Scene& s = *made;
                s.builder->set_painter(painter);
                const auto pixels = draw(s, c, th);
                ATLAS_REQUIRE(pixels.has_value());
                CHECK_MESSAGE(s.settled, view << ": the world did not settle");
                twin::Stream stream;
                std::vector<sh::ItemRecord> faces;
                std::vector<sh::ItemRecord> parts;
                std::vector<sh::ItemRecord> sides;
                const frame::FrameState& state = s.builder->state();
                s.items->emit_records(state, stream, &faces, &parts, &sides);
                const twin::Decoded decoded = twin::decode(stream.bytes());
                ATLAS_REQUIRE(decoded.error.empty(), decoded.error);
                const passes::Items::Counts counts = s.items->counts();
                ATLAS_REQUIRE(state.active_pack != nullptr && state.active_pack->source);
                const tiles::Style* style = state.active_pack->source->pack.style();
                ATLAS_REQUIRE(style != nullptr);
                const passes::ItemTones tones = passes::item_tones(state.theme, state.block.theme, style->items);
                const Compared r = compare(*pixels, faces, parts, sides, tones, state.block, config, clear);
                const std::string name = std::format("{} {} {}{}", view, gpu::name_of(config),
                                                     rest ? "at rest" : "moving", painter ? " (painter's order)" : "");
                MESSAGE(name << ": " << stream.elements() << " elements of " << counts.jobs << " items, "
                             << counts.faces << " faces, " << counts.parts << " parts and " << counts.sides
                             << " translucent ones; " << r.pixels << " pixels of items, " << r.agree
                             << " the exact raster's, " << r.boundary << " within 0.02 px of a shape's boundary, "
                             << r.tied << " where two colours tie in depth, " << r.seam << " on the composite's seam, "
                             << r.wrong << " otherwise; " << frame::text_of(s.builder->total()));
                CHECK(stream.elements() >= least);
                CHECK(faces.size() == counts.faces);
                CHECK(parts.size() == counts.parts);
                CHECK(sides.size() == counts.sides);
                CHECK_MESSAGE(r.wrong == 0, name << ": " << r.first);
                CHECK(static_cast<double>(r.boundary + r.tied) <= 0.01 * static_cast<double>(r.pixels));
                for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, name << ": " << m);
                s.renderer.reset();
            }
        }
    }
}

/// A window about the Westerkerk with a region's items: a tower and its cab, a turbine, a crane on a quay (a bay's
/// edge: a window's water has natural shores) and one in a yard (its outline), a lighting mast, a gantry over a road, a
/// goods conveyor and a power line; its pack written to the tests' scratch folder.
[[nodiscard]] std::string items_window() {
    const tiles::TileKey k = tiles::tile_of(14, 523'746'000, 48'840'000);
    const std::string data = R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-22T18:00:00Z"}, "elements": [
{"type": "node", "id": 101, "lat": 52.3752, "lon": 4.8846,
    "tags": {"man_made": "tower", "tower:type": "observation", "height": "45"}},
{"type": "node", "id": 102, "lat": 52.3749, "lon": 4.8828,
    "tags": {"power": "generator", "generator:source": "wind", "height": "60", "rotor:diameter": "50"}},
{"type": "node", "id": 103, "lat": 52.3741, "lon": 4.8852, "tags": {"man_made": "mast", "tower:type": "lighting"}},
{"type": "way", "id": 201, "tags": {"man_made": "gantry"},
    "geometry": [{"lat": 52.3738, "lon": 4.8832}, {"lat": 52.3738, "lon": 4.8838}]},
{"type": "way", "id": 202, "tags": {"man_made": "goods_conveyor"},
    "geometry": [{"lat": 52.3735, "lon": 4.8826}, {"lat": 52.3744, "lon": 4.8860}]},
{"type": "way", "id": 203, "tags": {"power": "line"},
    "geometry": [{"lat": 52.3731, "lon": 4.8815}, {"lat": 52.3733, "lon": 4.8850}, {"lat": 52.3736, "lon": 4.8885}]},
{"type": "way", "id": 204, "tags": {"natural": "bay"},
    "geometry": [{"lat": 52.37555, "lon": 4.8832}, {"lat": 52.37555, "lon": 4.8852}, {"lat": 52.3762, "lon": 4.8852},
                 {"lat": 52.3762, "lon": 4.8832}, {"lat": 52.37555, "lon": 4.8832}]},
{"type": "node", "id": 104, "lat": 52.3754, "lon": 4.8840, "tags": {"man_made": "crane"}},
{"type": "way", "id": 205, "tags": {"man_made": "crane"},
    "geometry": [{"lat": 52.3740, "lon": 4.8843}, {"lat": 52.3740, "lon": 4.88459}, {"lat": 52.37411, "lon": 4.88459},
                 {"lat": 52.37411, "lon": 4.8843}, {"lat": 52.3740, "lon": 4.8843}]}
]})";
    const auto built = load::build_window(std::as_bytes(std::span(data.data(), data.size())), k, load::place_of(k));
    ATLAS_REQUIRE_OR_RETURN(std::string(), built.has_value(), built.error().what);
    const std::filesystem::path folder = std::filesystem::path(ATLAS_TEST_SCRATCH) / "items-window";
    std::filesystem::create_directories(folder);
    const std::filesystem::path file = folder / "window.atlas";
    std::ofstream out(file, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
              static_cast<std::streamsize>(built->pack.size()));
    return file.string();
}

/// A window about the Westerkerk as a theme park's (a test's: its rides' profiles and its woods): a steel coaster's
/// loop (a lift hill, a run at layer 2 and a brake run meeting it), a wooden coaster's run, a monorail, and a wood of
/// about a hundred trees; its pack written to the tests' scratch folder.
[[nodiscard]] std::string park_window() {
    const tiles::TileKey k = tiles::tile_of(14, 523'746'000, 48'840'000);
    const std::string data = R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-22T18:00:00Z"}, "elements": [
{"type": "node", "id": 1, "lat": 52.37455, "lon": 4.88385, "tags": {"attraction": "roller_coaster", "name": "Big One"}},
{"type": "node", "id": 2, "lat": 52.37405, "lon": 4.88445, "tags": {"attraction": "roller_coaster", "name": "Woodie"}},
{"type": "way", "id": 10, "tags": {"roller_coaster": "track", "roller_coaster:track": "lift_hill"}, "geometry": [
  {"lat": 52.3744, "lon": 4.8836}, {"lat": 52.3744, "lon": 4.8841}, {"lat": 52.3747, "lon": 4.8841}]},
{"type": "way", "id": 11, "tags": {"roller_coaster": "track", "layer": "2"}, "geometry": [
  {"lat": 52.3747, "lon": 4.8841}, {"lat": 52.37472, "lon": 4.88385}, {"lat": 52.3747, "lon": 4.8836}]},
{"type": "way", "id": 12, "tags": {"roller_coaster": "track", "roller_coaster:track": "brake_run"}, "geometry": [
  {"lat": 52.3747, "lon": 4.8836}, {"lat": 52.3744, "lon": 4.8836}]},
{"type": "way", "id": 13, "tags": {"roller_coaster": "track", "layer": "1"}, "geometry": [
  {"lat": 52.3740, "lon": 4.8842}, {"lat": 52.3741, "lon": 4.8847}, {"lat": 52.3740, "lon": 4.8852}]},
{"type": "way", "id": 14, "tags": {"railway": "monorail"}, "geometry": [
  {"lat": 52.3738, "lon": 4.8830}, {"lat": 52.3739, "lon": 4.8838}]},
{"type": "way", "id": 20, "tags": {"landuse": "forest"}, "geometry": [
  {"lat": 52.3748, "lon": 4.8843}, {"lat": 52.3748, "lon": 4.8852}, {"lat": 52.3753, "lon": 4.8852},
  {"lat": 52.3753, "lon": 4.8843}, {"lat": 52.3748, "lon": 4.8843}]}
]})";
    static const load::RideProfiles rides = [] {
        load::RideProfiles r;
        r.heights = {{"Big One", 30.0}, {"Woodie", 20.0}};
        r.wooden = {{"Woodie", true}};
        return r;
    }();
    load::WindowPlace place = load::place_of(k);
    place.park = &rides;
    // the woods about the window's tile, out to 1.2 km
    const load::V2 centre = load::xz(place.frame, 52.3745, 4.8842);
    place.woods = load::WoodsAnchor{.cx = centre.x, .cz = centre.z, .reach = 1200.0};
    const auto built = load::build_window(std::as_bytes(std::span(data.data(), data.size())), k, place);
    ATLAS_REQUIRE_OR_RETURN(std::string(), built.has_value(), built.error().what);
    const std::filesystem::path folder = std::filesystem::path(ATLAS_TEST_SCRATCH) / "items-park";
    std::filesystem::create_directories(folder);
    const std::filesystem::path file = folder / "park.atlas";
    std::ofstream out(file, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
              static_cast<std::streamsize>(built->pack.size()));
    return file.string();
}

/// A camera of the map at S1/2 (540 × 1200 px, 412 × 916 dp), looking at (lat, lon).
[[nodiscard]] style::CameraState camera(double lat, double lon, double bearing, double pitch, double distance) {
    style::CameraState c = test::s1_camera(lat, lon, bearing, pitch, distance);
    c.width_px = 540;
    c.height_px = 1200;
    c.density = 540.0 / 412.0;
    c.flags = style::camera_flag::engine_mode;
    return c;
}

} // namespace

TEST_CASE("items: a window's tower, turbine, cranes, mast, gantry, conveyor and power line on the host's Vulkan, "
          "pixel for pixel the exact shapes of their twin") {
    const std::string path = items_window();
    ATLAS_REQUIRE(!path.empty());
    const std::array<PackFile, 1> packs{
        PackFile{.id = "window/" + load::place_of(tiles::tile_of(14, 523'746'000, 48'840'000)).name,
                 .kind = tiles::PackKind::window,
                 .path = path}};
    // close by day: every kind, the turbine's blades turned to 225°'s wind
    check_frames(camera(52.3744, 4.8840, 30.0, 55.0, 420.0), "the window's items by day", packs, test::sunny(), 30);
    // at night: the lighting mast's glow and its core, the turbine's light
    check_frames(camera(52.3744, 4.8840, 200.0, 50.0, 380.0), "the window's items at night", packs, test::night(), 30);
    // far: the power line fading (1,200 to 2,000 m), its tower translucent
    check_frames(camera(52.3744, 4.8840, 120.0, 30.0, 1600.0), "the window's items far off", packs, test::sunny(), 3);
}

TEST_CASE("items: a park's coaster tracks and woods' trees on the host's Vulkan, pixel for pixel the exact shapes of "
          "their twin") {
    const std::string path = park_window();
    ATLAS_REQUIRE(!path.empty());
    const std::array<PackFile, 1> packs{
        PackFile{.id = "window/" + load::place_of(tiles::tile_of(14, 523'746'000, 48'840'000)).name,
                 .kind = tiles::PackKind::window,
                 .path = path}};
    // close by day: the tracks' posts and braces, the woods' clumps with their lit tops and glints
    check_frames(camera(52.3745, 4.8842, 30.0, 50.0, 260.0), "the park by day", packs, test::sunny(), 30);
    // at night: no lit tops
    check_frames(camera(52.3745, 4.8842, 200.0, 45.0, 240.0), "the park at night", packs, test::night(), 30);
}

TEST_CASE("items: the canal ring's towers and masts on the host's Vulkan, pixel for pixel the exact shapes of their "
          "twin") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the canal ring is not drawn here");
        return;
    }
    // the canal ring's tallest towers, looked at from 300 m
    auto pack = test::open_pack(dir + "/grachten.atlas");
    ATLAS_REQUIRE(pack.has_value(), tiles::describe(pack.error()));
    const tiles::RenderHead* head = pack->head();
    ATLAS_REQUIRE(head != nullptr);
    tiles::Zstd zstd;
    tiles::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    Arena arena(4U << 20U, Growth::grows, "items vk test");
    double best = 0;
    double x = 0;
    double z = 0;
    for (const tiles::ChunkRef& chunk : pack->chunks().level(head->levels[0])) {
        arena.reset();
        auto tile = pack->decode(chunk, zstd, decoder, arena, raw, scratch);
        ATLAS_REQUIRE(tile.has_value(), tiles::describe(tile.error()));
        for (const geometry::RegionItem& it : geometry::build_region_items({.tile = &*tile, .head = head}).items) {
            if (it.kind == geometry::RegionItemKind::tower && it.h > best) {
                best = it.h;
                x = it.x;
                z = it.z;
            }
        }
    }
    ATLAS_REQUIRE(best > 0);
    const tiles::Frame& f = head->frame;
    const double lat = f.lat0 - (z / f.kz);
    const double lon = f.lon0 + (x / f.kx);
    MESSAGE("the canal ring's tallest tower: " << best << " m");
    const std::array<PackFile, 1> packs{PackFile{.id = "grachten", .path = dir + "/grachten.atlas"}};
    check_frames(camera(lat, lon, 52.0, 50.0, 300.0), "the canal ring's tallest tower", packs, test::sunny(), 1);
}

#endif
