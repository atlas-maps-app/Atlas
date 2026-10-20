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

#include <doctest/doctest.h>

#if defined(__APPLE__)
#include <pthread/qos.h>
#endif

#include <algorithm>
#include <array>
#include <filesystem>
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

#endif
