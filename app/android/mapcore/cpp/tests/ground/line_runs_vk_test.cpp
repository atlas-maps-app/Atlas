// A frame of more lines than an instance word holds runs of (passes/Lines.cpp's write_lines: a segment's word is
// (run << 14) | segment, 18 bits of its run) draws the first 2^18 runs and leaves the rest out, never wrapping one onto
// another run's words: a window of 48 motorways of 64 lanes side by side, each 1.4 km east to west, some 345,000 lane
// dashes in its one tile, all of them drawn from 150 m straight above the middle of them in the app's map.
#if !defined(__ANDROID__)

#include "base/arena.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "geometry/Ground.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "gpu/PipelineCache.hpp"
#include "load/Window.hpp"
#include "passes/Ground.hpp"
#include "scene/World.hpp"
#include "tests/base/require.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Container.hpp"
#include "tiles/Grid.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <format>
#include <fstream>
#include <span>
#include <string>
#include <string_view>
#include <vector>

using namespace atlas;

namespace {

const tiles::TileKey window_tile = tiles::tile_of(14, 523'746'000, 48'840'000);
constexpr int roads = 48;
constexpr double first_lat = 52.3660;
constexpr double lat_step = 0.00005; // 5.5 m: each road's asphalt, 228 m wide, under the middle of them all
constexpr double middle_lon = 4.8890;

/// The window's pack, written to the tests' scratch folder.
[[nodiscard]] std::string lanes_window() {
    std::string data = R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-22T18:00:00Z"}, "elements": [)";
    for (int i = 0; i < roads; ++i) {
        const double lat = first_lat + (lat_step * i);
        data += std::format(R"({}{{"type": "way", "id": {}, "tags": {{"highway": "motorway", "lanes": "64"}}, )"
                            R"("geometry": [{{"lat": {:.5f}, "lon": 4.8790}}, {{"lat": {:.5f}, "lon": {:.4f}}}, )"
                            R"({{"lat": {:.5f}, "lon": 4.8990}}]}})",
                            i == 0 ? "" : ",", 100 + i, lat, lat, middle_lon, lat);
    }
    data += "]}";
    const auto built = load::build_window(std::as_bytes(std::span(data.data(), data.size())), window_tile,
                                          load::place_of(window_tile));
    ATLAS_REQUIRE_OR_RETURN(std::string(), built.has_value(), built.error().what);
    const std::filesystem::path folder = std::filesystem::path(ATLAS_TEST_SCRATCH) / "line-runs-vk";
    std::filesystem::create_directories(folder);
    const std::filesystem::path file = folder / "window.atlas";
    std::ofstream out(file, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
              static_cast<std::streamsize>(built->pack.size()));
    return file.string();
}

/// The polylines of the window's tile's strokes (on the CPU, as stage T builds it).
[[nodiscard]] std::size_t stroke_polylines(const std::string& path) {
    auto pack = test::open_pack(path);
    ATLAS_REQUIRE_OR_RETURN(0, pack.has_value(), tiles::describe(pack.error()));
    const tiles::RenderHead& head = *pack->head();
    tiles::Zstd zstd;
    tiles::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    Arena arena(4U << 20U, Growth::grows, "line runs test");
    const auto chunks = pack->chunks().level(head.levels.front());
    ATLAS_REQUIRE_OR_RETURN(0, !chunks.empty());
    auto tile = pack->decode(chunks.front(), zstd, decoder, arena, raw, scratch);
    ATLAS_REQUIRE_OR_RETURN(0, tile.has_value(), tiles::describe(tile.error()));
    const geometry::P2 origin{.x = head.frame.x(tile->header.west), .z = head.frame.z(tile->header.south)};
    const geometry::GroundTile g = geometry::build_ground(*tile, head, *pack->style(), 0, origin);
    std::size_t n = 0;
    for (const geometry::Stroke& s : g.strokes) n += s.count;
    return n;
}

/// The runs the ground's last frame drew lines of, as write_lines would take them with no bound: a polyline of 1 to
/// 16,384 segments of a drawn stroke each.
[[nodiscard]] std::size_t drawn_runs(const passes::GroundFrame& f) {
    std::size_t n = 0;
    for (const passes::DrawnTile& t : f.tiles) {
        const geometry::GroundTile& g = *t.ground;
        for (std::uint32_t i = 0; i < g.strokes.size(); ++i) {
            if (f.stroke_drawn[t.strokes + i] == 0) continue;
            const geometry::Stroke& s = g.strokes[i];
            for (std::uint32_t p = s.first; p < s.first + s.count; ++p) {
                const geometry::Polyline& pl = g.polylines[p];
                const std::uint32_t segments = pl.pairs ? pl.count / 2 : (pl.count < 2 ? 0 : pl.count - 1);
                if (segments > 0 && segments <= 0x4000) ++n;
            }
        }
    }
    return n;
}

} // namespace

TEST_CASE("ground: a frame of more lines than an instance word's runs draws the first 2^18 runs, wrapping none") {
    const std::string path = lanes_window();
    ATLAS_REQUIRE(!path.empty());
    // the tile holds more lines than the runs (on the host too)
    REQUIRE(stroke_polylines(path) > (std::size_t{1} << 18U) + 50'000);
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    constexpr std::array<std::string_view, 3> ground_only{"composite", "frosted", "ground"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(ground_only);
    passes::Ground* g = test::ground_of(passes);
    ATLAS_REQUIRE(g != nullptr);
    auto builder =
        frame::FrameBuilder::create(device, *cache, std::move(passes), {.steps = 0, .grain = false, .grounded = true});
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    double now = 0;
    auto world = scene::World::create(device, (*builder)->passes(),
                                      {.workers = 3, .sun = {.enabled = false}, .clock = [&now] { return now; }});
    ATLAS_REQUIRE(world.has_value(), world.error().text());
    const std::string id = "window/" + load::place_of(window_tile).name;
    ATLAS_REQUIRE((*world)->store().add({.id = id, .kind = tiles::PackKind::window, .path = path}));
    // the app's map, straight down from 150 m over the middle of the roads
    style::CameraState camera = test::s1_camera(first_lat + (lat_step * (roads - 1) / 2), middle_lon, 0.0, 0.0, 150.0);
    camera.flags &= static_cast<std::uint32_t>(~style::camera_flag::engine_mode);
    auto output = gpu::Offscreen::create(device, {.width = camera.width_px, .height = camera.height_px}, 2);
    ATLAS_REQUIRE(output.has_value(), output.error().text());
    const gpu::Configuration config{.config = gpu::FrameConfig::c,
                                    .route = gpu::Route::renderpass,
                                    .form = gpu::FrameForm::composite,
                                    .target = gpu::CompositeTarget::resolve};
    auto renderer = gpu::Renderer::create(device, **output, **builder, config);
    ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
    bool settled = false;
    for (int f = 0; f < 61 && !settled; ++f) {
        now = static_cast<double>(f) * 0.25;
        (*builder)->set_inputs({.camera = camera,
                                .theme = test::sunny(),
                                .seconds = now,
                                .mode = frame::Mode::structural,
                                .scene = world->get()});
        auto drawn = (*renderer)->draw(0);
        ATLAS_REQUIRE(drawn.has_value(), drawn.error().text());
        (*renderer)->drain();
        (*world)->residency().wait_idle();
        const scene::Cover& cover = *(*world)->cover();
        settled = f > 4 && cover.missing() == 0 && !cover.tiles().empty() &&
                  std::ranges::all_of(cover.tiles(), [](const scene::CoverTile& t) { return t.fade >= 1.0F; });
    }
    REQUIRE(settled);
    auto last = (*renderer)->draw(0);
    ATLAS_REQUIRE(last.has_value(), last.error().text());
    (*renderer)->drain();
    // more runs drawn than an instance word holds: the first 2^18 of them run (before: every one, the 2^18th on
    // wrapping onto run 0's words)
    CHECK(drawn_runs(g->decided()) > (std::size_t{1} << 18U));
    CHECK(g->line_runs() == (1U << 18U));
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    renderer->reset();
}

#endif
