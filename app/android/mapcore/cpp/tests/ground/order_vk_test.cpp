// The ground's order across scenes (passes/Ground.hpp's GroundFrame::mixed; ENGINE.md step 3): where the frame draws a
// window's tiles beside another pack's, every drawn fill and stroke takes its rank in its group by its element across
// them (geometry::OrderKey, then its pack, then its scene's order), as one scene holding them all would order them;
// a frame of one scene's tiles, or of packs without windows, keeps each element at its scene's order.
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "gpu/PipelineCache.hpp"
#include "load/Window.hpp"
#include "osm/Overpass.hpp"
#include "passes/Ground.hpp"
#include "scene/World.hpp"
#include "tests/base/require.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"
#include "tests/load/query.hpp"
#include "tiles/Grid.hpp"

#include <doctest/doctest.h>

#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <map>
#include <set>
#include <string>
#include <tuple>
#include <vector>

using namespace atlas;

namespace {

[[nodiscard]] std::string environment(const char* name) {
    const char* v = std::getenv(name); // NOLINT(concurrency-mt-unsafe): read before threads start
    return v == nullptr ? std::string() : std::string(v);
}

} // namespace

TEST_CASE("ground: a window's tiles beside another pack's are ordered by their elements across the scenes") {
    const std::string dir = environment("ATLAS_TILES_DIR");
    const std::string regions = environment("ATLAS_REGIONS_DIR");
    const std::filesystem::path westerkerk = std::filesystem::path(dir) / "amsterdam.atlas";
    if (dir.empty() || regions.empty() || !std::filesystem::exists(westerkerk)) {
        MESSAGE("ATLAS_TILES_DIR or ATLAS_REGIONS_DIR is not set: no windows are drawn here");
        return;
    }
    // the nine windows around the Westerkerk's, built anywhere's way from the canal ring's raw.json
    std::ifstream in(std::filesystem::path(regions) / "grachten" / "raw.json", std::ios::binary);
    const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    ATLAS_REQUIRE(!text.empty());
    const auto doc = osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
    ATLAS_REQUIRE(doc.has_value());
    const tiles::TileKey w0 = tiles::tile_of(14, 523'746'000, 48'840'000);
    const std::filesystem::path folder = std::filesystem::path(ATLAS_TEST_SCRATCH) / "order-windows";
    std::filesystem::create_directories(folder);
    std::vector<std::pair<std::string, std::filesystem::path>> windows;
    for (std::uint32_t dy = 0; dy < 3; ++dy) {
        for (std::uint32_t dx = 0; dx < 3; ++dx) {
            const tiles::TileKey k(14, w0.x - 1 + dx, w0.y - 1 + dy);
            const auto built =
                load::build_window(test::query(*doc, load::window_box(k)), tiles::Sha256Digest{}, k, load::place_of(k));
            ATLAS_REQUIRE(built.has_value(), built.error().what);
            const std::filesystem::path file = folder / (std::to_string(k.x) + "-" + std::to_string(k.y) + ".atlas");
            std::ofstream out(file, std::ios::binary | std::ios::trunc);
            out.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
                      static_cast<std::streamsize>(built->pack.size()));
            windows.emplace_back("window/" + load::place_of(k).name, file);
        }
    }
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes();
    const passes::Ground* ground = test::ground_of(passes);
    ATLAS_REQUIRE(ground != nullptr);
    auto builder = frame::FrameBuilder::create(*session->device, *cache, std::move(passes), {.steps = 0});
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    double now = 0;
    auto world = scene::World::create(*session->device, (*builder)->passes(),
                                      {.workers = 2, .sun = {.enabled = false}, .clock = [&now] { return now; }});
    ATLAS_REQUIRE(world.has_value(), world.error().text());
    scene::World& w = **world;
    ATLAS_REQUIRE(w.store().add({.id = "amsterdam", .kind = tiles::PackKind::bundled, .path = westerkerk.string()}));
    for (const auto& [id, file] : windows) {
        const auto added = w.store().add({.id = id, .kind = tiles::PackKind::window, .path = file.string()});
        ATLAS_REQUIRE(added.has_value(), tiles::describe(added.error()));
    }
    // from higher up, both packs' tiles in view, drawn at S1's size in dp
    style::CameraState camera = test::home_camera();
    camera.distance = 1600.0;
    camera.density = 1.0;
    camera.width_px = camera.width_dp;
    camera.height_px = camera.height_dp;
    (*builder)->set_inputs({.camera = camera, .theme = test::sunny(), .seconds = 0, .scene = &w});
    auto output = gpu::Offscreen::create(
        *session->device,
        {.width = static_cast<std::uint32_t>(camera.width_px), .height = static_cast<std::uint32_t>(camera.height_px)},
        2);
    ATLAS_REQUIRE(output.has_value(), output.error().text());
    const gpu::Configuration config{.config = gpu::FrameConfig::c,
                                    .route = gpu::Route::renderpass,
                                    .form = gpu::FrameForm::composite,
                                    .target = gpu::CompositeTarget::resolve};
    auto renderer = gpu::Renderer::create(*session->device, **output, **builder, config);
    ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
    const auto frames = [&](int count) {
        for (int k = 0; k < count; ++k) {
            now += 0.05;
            auto report = (*renderer)->draw(0);
            ATLAS_REQUIRE(report.has_value(), report.error().text());
            w.residency().wait_idle();
        }
    };
    frames(16);
    const passes::GroundFrame& f = ground->decided();
    ATLAS_REQUIRE(w.neighbour() != nullptr);
    CHECK(f.mixed);
    // every drawn fill of a group, by its rank: its element, pack and scene order never go back
    std::map<std::uint16_t, std::vector<std::tuple<std::uint32_t, geometry::OrderKey, std::string, std::uint32_t>>>
        groups;
    std::size_t keyed = 0;
    for (const passes::DrawnTile& t : f.tiles) {
        const geometry::GroundTile& g = *t.ground;
        for (std::uint32_t i = 0; i < g.fills.size(); ++i) {
            const geometry::Fill& fill = g.fills[i];
            if (fill.kind == geometry::FillKind::water || fill.kind == geometry::FillKind::land ||
                fill.kind == geometry::FillKind::canopy ||
                (f.drawn[t.decisions + i] & passes::drawn_bit::street) == 0) {
                continue;
            }
            CHECK(ground->fill_order(t, i) == f.fill_rank[t.decisions + i]);
            groups[fill.group].emplace_back(f.fill_rank[t.decisions + i], fill.key, t.tile->pack, fill.order);
            if (t.tile->kind == tiles::PackKind::window && fill.kind == geometry::FillKind::area) {
                keyed += fill.key.element != 0 ? 1U : 0U;
            }
        }
    }
    std::size_t mixed_groups = 0;
    std::size_t checked = 0;
    for (auto& [group, fills] : groups) {
        std::ranges::sort(fills, [](const auto& a, const auto& b) { return std::get<0>(a) < std::get<0>(b); });
        std::set<std::string> packs;
        for (std::size_t k = 0; k < fills.size(); ++k) {
            packs.insert(std::get<2>(fills[k]));
            if (k == 0) continue;
            const auto& [ra, ka, pa, oa] = fills[k - 1];
            const auto& [rb, kb, pb, ob] = fills[k];
            CHECK(std::tie(ka, pa, oa) <= std::tie(kb, pb, ob));
            ++checked;
        }
        mixed_groups += packs.size() > 1 ? 1U : 0U;
    }
    MESSAGE(f.tiles.size() << " tiles drawn; " << groups.size() << " groups, " << mixed_groups
                           << " of them drawn from both packs; " << checked << " neighbours in rank checked; " << keyed
                           << " of the windows' areas keyed by their element");
    CHECK(mixed_groups > 0);
    CHECK(keyed > 0);
    // the Westerkerk alone (its own pack, no window drawn): every element at its scene's order
    for (const auto& [id, file] : windows) CHECK(w.store().remove(id));
    frames(6);
    CHECK_FALSE(ground->decided().mixed);
    (*renderer)->drain();
    (void)session->take_messages();
}
