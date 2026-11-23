// The routes pass's draws on the CPU (passes/Routes.hpp), without a device: a route of more pieces on the view than a
// frame draws (Routes::max_draws) is cut at the cap, and no draw meets one that was not written (route.slang reads its
// neighbour's draw where a piece meets it).
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "passes/Routes.hpp"
#include "routes/Routes.hpp"
#include "standin/View.hpp"
#include "tests/base/require.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"

#include <doctest/doctest.h>

#include <cstddef>
#include <cstdint>

using namespace atlas;

namespace {

/// The frame's camera as FrameBuilder::derive makes it without a ladder (routes_vk_test's).
view::View view_of(const style::CameraState& cam) {
    const view::CameraSpec spec{.target = {},
                                .yaw = view::bearing_of(cam.bearing),
                                .pitch = view::engine_pitch(cam.pitch),
                                .dist = cam.distance,
                                .fov = view::field_of_view,
                                .width = static_cast<double>(cam.width_dp),
                                .height = static_cast<double>(cam.height_dp),
                                .cx = std::nullopt,
                                .cy = cam.principal_y,
                                .near = view::near_plane};
    return {spec, {.density = cam.density, .width_px = cam.width_px, .height_px = cam.height_px}};
}

} // namespace

TEST_CASE("routes: a route of more pieces than a frame draws is cut at the cap, each join to a draw written") {
    const double lat0 = standin::origin_latitude;
    const double lon0 = standin::origin_longitude;
    const view::GroundFrame ground{
        .lat0 = lat0, .lon0 = lon0, .kx = 111320.0 * pymath::cos(pymath::radians(lat0)), .kz = 110540.0};
    // straight down from 300 m: a route west to east through the target, 9,000 segments of 2 cm, every one on the
    // view, so its casing's pieces and then its fill's pass the cap
    style::CameraState camera = standin::board_view(400, 300, 2.0, 0.0, 0.0, 300.0);
    camera.flags = style::camera_flag::rest;
    routes::Route a{.width = 6.0F, .colour = 0xFF2E'5BFFU, .faded = 0xFF8A'9BD9U, .casing = 0xFFFF'FFFFU};
    constexpr int segments = 9000;
    for (int k = 0; k <= segments; ++k) {
        const double x = (0.02 * k) - 90.0;
        a.points.push_back({lat0, lon0 + (x / ground.kx), 0.02 * k});
    }
    auto set = routes::RouteSet::make({}, {a}, {}, 2.0F, 1);
    ATLAS_REQUIRE(set.has_value(), routes::describe(set.error()));
    frame::FrameState state;
    state.camera = camera;
    state.view = view_of(camera);
    state.ground_frame = ground;
    state.routes = set->get();
    passes::Routes pass;
    pass.update(state);
    const auto draws = pass.draws();
    REQUIRE(draws.size() == passes::Routes::max_draws);
    for (std::size_t i = 0; i < draws.size(); ++i) {
        const std::uint32_t joins = draws[i].look[2];
        if ((joins & passes::Routes::joins_next) != 0U) {
            INFO("draw " << i);
            CHECK(i + 1 < draws.size());
        }
        if ((joins & passes::Routes::joins_prev) != 0U) {
            INFO("draw " << i);
            CHECK(i > 0);
        }
    }
    // the cut fill's pieces meet each other up to the last one drawn
    CHECK((draws[draws.size() - 2].look[2] & passes::Routes::joins_next) != 0U);
    CHECK((draws.back().look[2] & passes::Routes::joins_prev) != 0U);
}
