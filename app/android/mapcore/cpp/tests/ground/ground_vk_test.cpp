// The ground on the host's Vulkan (plan-vector K9): the ground pass in the frame builder over the world of the bundled
// render packs (ATLAS_TILES_DIR), drawn at the boards' views on lavapipe with the validation layer and its
// synchronization validation silent; its frames kept in the build's scratch folder (ground-<view>.ppm) to look at.
#if !defined(__ANDROID__)

#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/Selectors.hpp"
#include "passes/Ground.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"
#include "tests/load/corner.hpp"
#include "twin/Twin.hpp"
#include "view/Frames.hpp"

#include <doctest/doctest.h>

#if defined(__APPLE__)
#include <pthread/qos.h>
#endif

#include <algorithm>
#include <array>
#include <cmath>
#include <filesystem>
#include <format>
#include <span>
#include <string>
#include <vector>

using namespace atlas;

namespace {

/// What select costs over the last frame's state, the median of many runs (milliseconds of the thread's CPU time).
struct Costs {
    double moving = 0; ///< deciding while the camera moves (every frame in motion)
    double exact = 0;  ///< deciding at rest (once for a camera)
    double kept = 0;   ///< keeping the decisions at rest (every later frame at that camera)
};

[[nodiscard]] double median(std::vector<double> v) {
    std::ranges::sort(v);
    return v.empty() ? 0.0 : v[v.size() / 2];
}

/// select's costs over `state`: the pass decides as a moving camera does, as a camera coming to rest does, and keeps.
/// On the render thread's footing: a thread the system serves first (on a Mac, a performance core's).
Costs costs_of(passes::Ground& ground, const frame::FrameState& state) {
#if defined(__APPLE__)
    (void)pthread_set_qos_class_self_np(QOS_CLASS_USER_INTERACTIVE, 0);
#endif
    frame::Selectors selectors;
    frame::FrameState moving = state;
    moving.rest = false;
    frame::FrameState rest = state;
    rest.rest = true;
    std::vector<double> m;
    std::vector<double> e;
    std::vector<double> k;
    for (int i = 0; i < 21; ++i) {
        ground.select(moving, selectors);
        m.push_back(ground.select_ms());
        ground.select(rest, selectors);
        e.push_back(ground.select_ms());
        ground.select(rest, selectors);
        k.push_back(ground.select_ms());
    }
    ground.select(state, selectors); // the frame's own decisions again
    return {.moving = median(m), .exact = median(e), .kept = median(k)};
}

/// draw_over's radius cut by the samples' boxes (near_line) against near_radius over the samples, for every line of
/// the frame's tiles, at the frame's target and radius and at half, twice and a tenth of it: the cases that differ.
std::size_t near_differ(const passes::GroundFrame& frame) {
    std::size_t differ = 0;
    passes::GroundFrame f = frame;
    const double radius = f.radius > 0 ? f.radius : 1000.0;
    for (const double k : {1.0, 0.5, 2.0, 0.1}) {
        f.radius = radius * k;
        for (const passes::DrawnTile& t : f.tiles) {
            const geometry::GroundTile& g = *t.ground;
            auto check = [&](std::uint32_t line, std::uint32_t count, const std::array<double, 4>& near) {
                if (count == 0) return;
                const auto pts = std::span(g.points).subspan(line, count);
                if (passes::near_line(f, pts, near) != geometry::near_radius(pts, f.target_x, f.target_z, f.radius)) {
                    ++differ;
                }
            };
            for (const geometry::Fill& fill : g.fills) check(fill.line, fill.line_count, fill.near);
            for (const geometry::Stroke& stroke : g.strokes) check(stroke.line, stroke.line_count, stroke.near);
        }
    }
    return differ;
}

} // namespace

TEST_CASE("ground: the boards' views draw on the host's Vulkan, the validation layer silent") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the ground is not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    const auto scratch = std::filesystem::path(ATLAS_TEST_SCRATCH) / "ground";
    const bool timed = test::environment("ATLAS_TIMED") == "1";
    struct Case {
        std::string name;
        std::string pack;
        style::CameraState camera;
        style::ThemeBlock theme;
    };
    const std::vector<Case> cases{
        {"home-sunny", "amsterdam", test::home_camera(), test::sunny()},
        {"home-night", "amsterdam", test::home_camera(), test::night()},
        {"board-sunny", "grachten", test::view_camera(dir, "grachten", "grachten_home"), test::sunny()},
        {"board-night", "grachten", test::view_camera(dir, "grachten", "grachten_home"), test::night()},
        {"board-moving", "grachten", test::moving(test::view_camera(dir, "grachten", "grachten_home")), test::sunny()},
        // the horizon in view (8° down, the eye's height): the sky's band above it, wgrad from its row
        {"horizon-sunny", "amsterdam", test::s1_camera(52.3746, 4.8840, 52.0, 82.0, 200.0), test::sunny()},
        {"horizon-night", "amsterdam", test::s1_camera(52.3746, 4.8840, 52.0, 82.0, 200.0), test::night()},
        // ground.json's close cases: the Prinsengracht's banks, the rails and tram tracks at Centraal
        {"canal-sunny", "amsterdam", test::s1_camera(52.3747, 4.8852, 52.0, 60.0, 120.0), test::sunny()},
        {"canal-night", "amsterdam", test::s1_camera(52.3747, 4.8852, 52.0, 60.0, 120.0), test::night()},
        {"rails-sunny", "grachten", test::s1_camera(52.3789, 4.9003, 0.0, 45.0, 600.0), test::sunny()},
    };
    for (const Case& c : cases) {
        Costs costs;
        const auto d = test::draw_ground(*session, c.camera, c.theme, dir, c.pack,
                                         [&](passes::Ground& g, const frame::FrameState& state) {
                                             costs = costs_of(g, state);
                                             CHECK(near_differ(g.decided()) == 0);
                                         });
        ATLAS_REQUIRE(d.has_value(), c.name << ": the frame was not drawn");
        test::write_ppm(d->pixels, scratch / (c.name + ".ppm"));
        // something is drawn: the frame is not one colour
        std::size_t differ = 0;
        for (std::size_t i = 4; i < d->pixels.rgba.size(); i += 4) {
            if (std::memcmp(&d->pixels.rgba[i], &d->pixels.rgba[0], 3) != 0) ++differ;
        }
        CHECK_MESSAGE(differ > d->pixels.rgba.size() / 40, c.name << ": the frame is nearly one colour");
        std::size_t drawn = 0;
        for (const std::uint8_t b : d->decided.drawn) drawn += b != 0 ? 1 : 0;
        MESSAGE(c.name << ": " << d->decided.tiles.size() << " tiles, " << drawn << " of " << d->decided.drawn.size()
                       << " fills drawn, " << d->decided.slots << " ground slots; " << frame::text_of(d->count)
                       << "; select " << costs.moving << " ms moving, " << costs.exact << " ms coming to rest, "
                       << costs.kept << " ms at rest (kept in " << d->kept << " of 40 frames); the horizon at row "
                       << d->decided.horizon);
        if (c.name.starts_with("horizon")) {
            CHECK(d->decided.horizon > 0);
            CHECK(d->decided.horizon < c.camera.height_dp);
        }
        // stage F's budget for the ground's decisions on one core: every frame in motion and every frame at rest after
        // the first (that one decides once for its camera); gated on a quiet machine
        if (timed) {
            CHECK_MESSAGE(costs.moving <= 0.6, c.name << ": deciding in motion takes " << costs.moving << " ms");
            CHECK_MESSAGE(costs.kept <= 0.6, c.name << ": a frame at rest takes " << costs.kept << " ms");
        }
        for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, c.name << ": " << m);
    }
}

TEST_CASE("ground: a neighbouring frame cell's tiles are decided in their own frame, at their own depths") {
    // A primary road (its asphalt, edge and lane lines) 100 m north of a corner of four frame cells, in the north-east
    // cell's window (tests/load/corner.hpp), the camera's target a metre inside the south-east one, looking north from
    // 400 m. The north-east tile's points are its own frame's metres, about an origin 24 km off the frame drawn's: each
    // drawn line's width is regions.py's px at its point's depth from the eye (max(0.25, w · focal / depth)), the point
    // taken into the frame drawn. Decided with the frame drawn's camera, the lines were judged from 24 km off: a far
    // view's big roads 253 px wide from a point behind the eye, 0.7 G fragments a frame at Alkmaar.
    const auto made = test::corner_windows(
        std::filesystem::path(ATLAS_TEST_SCRATCH) / "ground-corner",
        [](std::size_t window, std::int64_t& id, const test::CornerWindows& c) -> std::string {
            if (window != 1) return {};
            const std::array<std::array<double, 2>, 3> road{{{20.0, 100.0}, {210.0, 100.0}, {400.0, 100.0}}};
            const double x = 120.0;
            const double z = 220.0;
            return test::made_way(id, R"("highway":"primary","lanes":"2")", road, c) + "," +
                   test::made_building(id, R"("building":"yes","building:levels":"4")", x - 15.0, z - 15.0, x + 15.0,
                                       z + 15.0, c);
        });
    ATLAS_REQUIRE(made.has_value(), made.error());
    const test::CornerWindows& m = *made;
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    constexpr std::array<std::string_view, 3> ground_only{"composite", "frosted", "ground"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(ground_only);
    passes::Ground* g = test::ground_of(passes);
    auto builder = frame::FrameBuilder::create(device, *cache, std::move(passes), {.steps = 0});
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    double now = 0;
    auto world = scene::World::create(device, (*builder)->passes(),
                                      {.workers = 3, .sun = {.enabled = false}, .clock = [&now] { return now; }});
    ATLAS_REQUIRE(world.has_value(), world.error().text());
    scene::World& w = **world;
    for (const auto& [name, file] : m.windows) {
        const auto joined = w.store().add({.id = name, .kind = tiles::PackKind::window, .path = file.string()});
        ATLAS_REQUIRE(joined.has_value(), tiles::describe(joined.error()));
    }
    w.set_coarser_enclosing(true);
    w.set_active(m.windows.back().first);
    style::CameraState c = test::s1_camera(m.at(1.0, -1.0)[0], m.at(1.0, -1.0)[1], 0.0, 60.0, 400.0);
    std::uint64_t n = 0;
    twin::Stream stream;
    const auto frames = [&](int count) {
        for (int k = 0; k < count; ++k) {
            now += 0.016;
            (*builder)->set_inputs({.camera = c, .theme = test::sunny(), .seconds = now, .scene = &w});
            ATLAS_REQUIRE((*builder)->derive(++n, 0, nullptr));
            w.update((*builder)->state());
            w.residency().wait_idle();
        }
        now += 1.0; // past every fade
        (*builder)->set_inputs({.camera = c, .theme = test::sunny(), .seconds = now, .scene = &w});
        (*builder)->emit(stream, ++n);
    };
    const auto check = [&](bool rest) {
        c.flags = (rest ? style::camera_flag::rest : 0U) | style::camera_flag::engine_mode;
        frames(30);
        const frame::FrameState& state = (*builder)->state();
        ATLAS_REQUIRE(state.ground_frame.has_value());
        const passes::GroundFrame& f = g->decided();
        const view::Camera& cam = state.view->camera();
        std::size_t lines = 0;
        for (const passes::DrawnTile& t : f.tiles) {
            ATLAS_REQUIRE(t.cam != nullptr);
            if (t.tile->frame == *state.ground_frame) continue;
            for (std::uint32_t i = 0; i < t.ground->strokes.size(); ++i) {
                const geometry::Stroke& s = t.ground->strokes[i];
                if (s.kind != geometry::StrokeKind::edges || f.stroke_drawn[t.strokes + i] == 0) continue;
                ++lines;
                const view::Vec3 at =
                    view::convert(t.tile->frame, *state.ground_frame, {s.width.at.x, 0.0, s.width.at.z});
                const double depth = cam.cam(at).z;
                const double px = std::max(0.25, s.width.metres * cam.focal() / std::max(depth, 1.0));
                const double width = f.stroke_width[t.strokes + i];
                CHECK_MESSAGE(std::abs(width - px) <= 0.01 + (0.01 * px),
                              std::format("{} the road's lines {:.3f} px wide, {:.3f} at {:.0f} m",
                                          rest ? "at rest" : "moving", width, px, depth));
            }
        }
        // the road's lines drawn, from the north-east cell's tile
        CHECK(lines > 0);
    };
    check(true);
    check(false);
    for (const std::string& message : session->take_messages()) CHECK_MESSAGE(false, message);
}

#endif
