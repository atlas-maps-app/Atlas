// The ground's order across scenes (passes/Ground.hpp's GroundFrame::mixed; ENGINE.md step 3): where the frame draws
// tiles of more than one scene with a window among them (a window's tiles beside another pack's, or two windows: a
// frame cell's windows are one pack, each window a scene of its own), every drawn fill and stroke takes its rank in its
// group by its element across them (geometry::OrderKey, then its pack, then its scene's order), and the land kinds
// theirs by their first ring's, as one scene holding them all would order them; a frame of one scene's tiles (one
// window, or packs without windows) keeps each element at its scene's order.
#if !defined(__ANDROID__)

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
#include <optional>
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

/// The nine windows around the Westerkerk's (one frame cell's), built anywhere's way from the canal ring's raw.json in
/// `regions` into `folder`: each one's pack id and file; none where the data is not there.
[[nodiscard]] std::vector<std::pair<std::string, std::filesystem::path>>
nine_windows(const std::string& regions, const std::filesystem::path& folder) {
    std::vector<std::pair<std::string, std::filesystem::path>> windows;
    std::ifstream in(std::filesystem::path(regions) / "grachten" / "raw.json", std::ios::binary);
    const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    const auto doc = osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
    if (text.empty() || !doc) return windows;
    const tiles::TileKey w0 = tiles::tile_of(14, 523'746'000, 48'840'000);
    std::filesystem::create_directories(folder);
    for (std::uint32_t dy = 0; dy < 3; ++dy) {
        for (std::uint32_t dx = 0; dx < 3; ++dx) {
            const tiles::TileKey k(14, w0.x - 1 + dx, w0.y - 1 + dy);
            const auto built =
                load::build_window(test::query(*doc, load::window_box(k)), tiles::Sha256Digest{}, k, load::place_of(k));
            if (!built) return {};
            const std::filesystem::path file = folder / (std::to_string(k.x) + "-" + std::to_string(k.y) + ".atlas");
            std::ofstream out(file, std::ios::binary | std::ios::trunc);
            out.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
                      static_cast<std::streamsize>(built->pack.size()));
            windows.emplace_back("window/" + load::place_of(k).name, file);
        }
    }
    return windows;
}

/// What the ranks of a frame's ground hold: every drawn fill of a group (land, canopy and water aside), by its rank,
/// never goes back in (its element, its pack, its scene's order); the land kinds' ranks follow LAND's draw order.
struct Ranks {
    std::size_t groups = 0;
    std::size_t across = 0; ///< groups drawn from more than one scene: a pack's, or a window's
    std::size_t checked = 0;
    std::size_t keyed = 0;        ///< the windows' areas keyed by their element
    std::size_t kinds = 0;        ///< land kinds drawn
    std::size_t kinds_across = 0; ///< of them, drawn from more than one scene
};

[[nodiscard]] Ranks check_ranks(const passes::Ground& ground) {
    const passes::GroundFrame& f = ground.decided();
    Ranks r;
    // a tile's scene: its pack's, or its own where it is a window
    const auto scene_of = [](const passes::DrawnTile& t) {
        return t.tile->kind == tiles::PackKind::window ? t.tile->pack + "#" + std::to_string(t.tile->handle)
                                                       : t.tile->pack;
    };
    // each drawn fill's rank, element, pack, scene's order and scene
    std::map<std::uint16_t,
             std::vector<std::tuple<std::uint32_t, geometry::OrderKey, std::string, std::uint32_t, std::string>>>
        groups;
    std::map<std::int32_t, std::pair<std::uint64_t, std::set<std::string>>>
        kinds; // a kind's rank: its draw order, scenes
    for (const passes::DrawnTile& t : f.tiles) {
        const geometry::GroundTile& g = *t.ground;
        for (std::uint32_t i = 0; i < g.fills.size(); ++i) {
            const geometry::Fill& fill = g.fills[i];
            if ((f.drawn[t.decisions + i] & passes::drawn_bit::street) == 0) continue;
            if (fill.kind == geometry::FillKind::land) {
                const std::int32_t rank = f.kind_rank[fill.group - geometry::group::land];
                auto& [order, scenes] = kinds[rank];
                order = fill.key.sort;
                scenes.insert(scene_of(t));
                CHECK(order == fill.key.sort); // every ring of a kind at its kind's draw order
                continue;
            }
            if (fill.kind == geometry::FillKind::water || fill.kind == geometry::FillKind::canopy) continue;
            CHECK(ground.fill_order(t, i) == f.fill_rank[t.decisions + i]);
            groups[fill.group].emplace_back(f.fill_rank[t.decisions + i], fill.key, t.tile->pack, fill.order,
                                            scene_of(t));
            if (t.tile->kind == tiles::PackKind::window && fill.kind == geometry::FillKind::area) {
                r.keyed += fill.key.element != 0 ? 1U : 0U;
            }
        }
    }
    for (auto& [group, fills] : groups) {
        std::ranges::sort(fills, [](const auto& a, const auto& b) { return std::get<0>(a) < std::get<0>(b); });
        std::set<std::string> scenes;
        for (std::size_t k = 0; k < fills.size(); ++k) {
            scenes.insert(std::get<4>(fills[k]));
            if (k == 0) continue;
            const auto& [ra, ka, pa, oa, sa] = fills[k - 1];
            const auto& [rb, kb, pb, ob, sb] = fills[k];
            CHECK(std::tie(ka, pa, oa) <= std::tie(kb, pb, ob));
            ++r.checked;
        }
        ++r.groups;
        r.across += scenes.size() > 1 ? 1U : 0U;
    }
    // the land kinds by their ranks: their draw orders never go back
    std::uint64_t before = 0;
    for (const auto& [rank, kind] : kinds) {
        CHECK(rank >= 0);
        CHECK(kind.first >= before);
        before = kind.first;
        ++r.kinds;
        r.kinds_across += kind.second.size() > 1 ? 1U : 0U;
    }
    return r;
}

} // namespace

namespace {

/// A frame of `world`'s tiles drawn by `builder`'s passes on a session's device, from higher up than the home (1600 m),
/// at S1's size in dp, as often as asked.
class Drawing {
public:
    static std::optional<Drawing> open(test::Session& session, frame::FrameBuilder& builder, scene::World& world,
                                       double& now) {
        style::CameraState camera = test::home_camera();
        camera.distance = 1600.0;
        camera.density = 1.0;
        camera.width_px = camera.width_dp;
        camera.height_px = camera.height_dp;
        builder.set_inputs({.camera = camera, .theme = test::sunny(), .seconds = 0, .scene = &world});
        auto output = gpu::Offscreen::create(*session.device,
                                             {.width = static_cast<std::uint32_t>(camera.width_px),
                                              .height = static_cast<std::uint32_t>(camera.height_px)},
                                             2);
        if (!output) return std::nullopt;
        const gpu::Configuration config{.config = gpu::FrameConfig::c,
                                        .route = gpu::Route::renderpass,
                                        .form = gpu::FrameForm::composite,
                                        .target = gpu::CompositeTarget::resolve};
        auto renderer = gpu::Renderer::create(*session.device, **output, builder, config);
        if (!renderer) return std::nullopt;
        return Drawing(std::move(*output), std::move(*renderer), world, now);
    }

    /// `count` frames, the world's tiles settling between them; false where one fails.
    bool draw(int count) {
        for (int k = 0; k < count; ++k) {
            *now_ += 0.05;
            if (!renderer_->draw(0)) return false;
            world_->residency().wait_idle();
        }
        return true;
    }
    void drain() { renderer_->drain(); }

private:
    Drawing(std::unique_ptr<gpu::Offscreen> output, std::unique_ptr<gpu::Renderer> renderer, scene::World& world,
            double& now)
        : output_(std::move(output)), renderer_(std::move(renderer)), world_(&world), now_(&now) {}

    std::unique_ptr<gpu::Offscreen> output_;
    std::unique_ptr<gpu::Renderer> renderer_;
    scene::World* world_;
    double* now_;
};

} // namespace

TEST_CASE("ground: a window's tiles beside another pack's are ordered by their elements across the scenes") {
    const std::string dir = environment("ATLAS_TILES_DIR");
    const std::string regions = environment("ATLAS_REGIONS_DIR");
    const std::filesystem::path westerkerk = std::filesystem::path(dir) / "amsterdam.atlas";
    if (dir.empty() || regions.empty() || !std::filesystem::exists(westerkerk)) {
        MESSAGE("ATLAS_TILES_DIR or ATLAS_REGIONS_DIR is not set: no windows are drawn here");
        return;
    }
    const auto windows = nine_windows(regions, std::filesystem::path(ATLAS_TEST_SCRATCH) / "order-windows");
    ATLAS_REQUIRE(windows.size() == 9);
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
    // from higher up, both packs' tiles in view
    auto frames = Drawing::open(*session, **builder, w, now);
    ATLAS_REQUIRE(frames.has_value());
    ATLAS_REQUIRE(frames->draw(16));
    ATLAS_REQUIRE(w.neighbour() != nullptr);
    ATLAS_REQUIRE(ground->decided().mixed);
    const Ranks r = check_ranks(*ground);
    MESSAGE(ground->decided().tiles.size()
            << " tiles drawn; " << r.groups << " groups, " << r.across << " of them drawn from more than one scene; "
            << r.checked << " neighbours in rank checked; " << r.keyed
            << " of the windows' areas keyed by their element; " << r.kinds << " land kinds, " << r.kinds_across
            << " from more than one scene");
    CHECK(r.across > 0);
    CHECK(r.keyed > 0);
    // the Westerkerk alone (its own pack, no window drawn): every element at its scene's order
    for (const auto& [id, file] : windows) CHECK(w.store().remove(id));
    ATLAS_REQUIRE(frames->draw(6));
    CHECK_FALSE(ground->decided().mixed);
    frames->drain();
    (void)session->take_messages();
}

TEST_CASE("ground: a frame cell's windows alone are ordered by their elements across them, each window a scene") {
    const std::string regions = environment("ATLAS_REGIONS_DIR");
    if (regions.empty()) {
        MESSAGE("ATLAS_REGIONS_DIR is not set: no windows are drawn here");
        return;
    }
    // one frame cell's nine windows, and nothing else: one pack, nine scenes (anywhere outside the bundled packs)
    const auto windows = nine_windows(regions, std::filesystem::path(ATLAS_TEST_SCRATCH) / "order-windows-alone");
    ATLAS_REQUIRE(windows.size() == 9);
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
    for (const auto& [id, file] : windows) {
        const auto added = w.store().add({.id = id, .kind = tiles::PackKind::window, .path = file.string()});
        ATLAS_REQUIRE(added.has_value(), tiles::describe(added.error()));
    }
    ATLAS_REQUIRE(w.store().packs().size() == 1);
    auto frames = Drawing::open(*session, **builder, w, now);
    ATLAS_REQUIRE(frames.has_value());
    ATLAS_REQUIRE(frames->draw(16));
    ATLAS_REQUIRE(w.active() != nullptr);
    CHECK(w.active()->kind == tiles::PackKind::window);
    std::set<std::uint32_t> drawn;
    for (const passes::DrawnTile& t : ground->decided().tiles) {
        if (t.tile->kind == tiles::PackKind::window) drawn.insert(t.tile->handle);
    }
    MESSAGE(drawn.size(), " windows drawn, of one pack; the plate's half side ", ground->decided().ground_r, " m");
    ATLAS_REQUIRE(drawn.size() > 1);
    // framed at the camera (a bare ladder: the pack has no views), with a region's settings (no fin), and the plate
    // reaching every window drawn (the cell's origin, its centre, lies kilometres off)
    ATLAS_REQUIRE((*builder)->state().placed.has_value());
    CHECK((*builder)->state().placed->camera().target.x > 9000);
    ATLAS_REQUIRE((*builder)->state().layers.has_value());
    CHECK_FALSE((*builder)->state().layers->draws_fin);
    CHECK(ground->decided().plate);
    CHECK(ground->decided().ground_r > view::default_ground_r);
    ATLAS_REQUIRE(ground->decided().mixed);
    const Ranks r = check_ranks(*ground);
    MESSAGE(r.groups << " groups, " << r.across << " of them drawn from more than one window; " << r.checked
                     << " neighbours in rank checked; " << r.keyed << " areas keyed by their element; " << r.kinds
                     << " land kinds, " << r.kinds_across << " drawn from more than one window");
    CHECK(r.across > 0);
    CHECK(r.keyed > 0);
    CHECK(r.kinds_across > 0);
    // the app names the window under the camera (its own id: MapCore.PackRef), and its frame cell's pack is drawn
    w.set_active(windows[4].first);
    ATLAS_REQUIRE(frames->draw(4));
    ATLAS_REQUIRE(w.active() != nullptr);
    CHECK(w.active()->id == w.store().holder_of(windows[4].first));
    CHECK(w.active()->id.starts_with("cells/10/"));
    CHECK(ground->decided().tiles.size() > 1);
    w.set_active({});
    // one window alone is one scene: every element at its order
    for (std::size_t i = 1; i < windows.size(); ++i) CHECK(w.store().remove(windows[i].first));
    ATLAS_REQUIRE(frames->draw(6));
    CHECK_FALSE(ground->decided().mixed);
    frames->drain();
    (void)session->take_messages();
}

#endif
