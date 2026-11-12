// The night's light on the ground by the tiles' maps on the host's Vulkan (passes/LightMap.hpp, behind the camera's
// flag light_cascades): the Westerkerk home at night, the ground drawn alone without the flag (the renders' pools),
// with it (the maps), and with no light at all. With the flag the tiles near the target draw their maps and none of
// their lamps' ellipses; the light is warm, and nowhere but near a lamp; at a lamp's foot in the open it is the
// renders' pool's, and the buildings take some of the renders' light away. Then the frames' times with the flag and
// without, at rest and with the camera turning, and (where ATLAS_LIGHT_DUMP names a folder) the pictures: the canal
// ring and a lamp-lit street with every pass drawn, and that street's ground alone, flag off and on, the open places'
// pools among the light (the theme's daylight).
#if !defined(__ANDROID__)

#include "passes/Ground.hpp"
#include "passes/LightMap.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <optional>
#include <string>
#include <vector>

using namespace atlas;

namespace {

/// What a draw gave: its frame, the maps' measures after the last frame, and the mean time of its last ten frames
/// (ms, each with the world's wait after it).
struct LightDraw {
    test::GroundDraw frame;
    passes::LightStats stats;
    double frame_ms = 0;
    std::vector<geometry::P2> lamps; ///< the drawn tiles' street lamps (frame metres)
};

/// Draws `camera` at night for `frames` frames, the bakes waited for after each; `turning`: from frame 30 on the
/// camera turns half a degree a frame, not at rest.
[[nodiscard]] std::optional<LightDraw> draw(const test::Session& session, const std::string& dir,
                                            style::CameraState camera, const style::ThemeBlock& theme,
                                            bool no_light = false, bool turning = false, bool every_pass = false,
                                            int frames = 40) {
    passes::Ground* ground = nullptr;
    passes::LightStats stats;
    std::vector<geometry::P2> lamps;
    std::vector<std::chrono::steady_clock::time_point> at;
    const double bearing = camera.bearing;
    auto drawn = test::draw_ground(
        session, camera, theme, dir, "amsterdam",
        [&](passes::Ground& g, const frame::FrameState&) {
            stats = g.light_stats();
            for (const passes::DrawnTile& t : g.decided().tiles) {
                for (const geometry::Lamp& lamp : t.ground->lamps) lamps.push_back(lamp.at);
            }
        },
        frames,
        [&](passes::Ground& g) {
            ground = &g;
            if (no_light) {
                g.draw_light(false);
                g.draw_pools(false);
            }
        },
        frame::Mode::structural, nullptr,
        [&] {
            if (ground != nullptr) ground->wait_for_light();
        },
        {.steps = 0}, false,
        [&](int f, frame::FrameInputs& inputs) {
            at.push_back(std::chrono::steady_clock::now());
            if (turning && f >= 30) {
                inputs.camera = test::moving(camera);
                inputs.camera.bearing = bearing + (0.5 * static_cast<double>(f - 29));
            }
        },
        every_pass);
    if (!drawn) return std::nullopt;
    LightDraw out{.frame = std::move(*drawn), .stats = stats, .lamps = std::move(lamps)};
    if (at.size() >= 12) {
        // the frames before the last (whose end no later frame marks)
        const std::size_t last = at.size() - 1;
        out.frame_ms = std::chrono::duration<double, std::milli>(at[last] - at[last - 10]).count() / 10.0;
    }
    return out;
}

[[nodiscard]] style::CameraState with_cascades(style::CameraState c) {
    c.flags |= style::camera_flag::light_cascades;
    return c;
}

} // namespace

TEST_CASE("ground: the night's light by the tiles' maps is the lamps' pools, cut by the buildings") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the light maps are not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    const style::CameraState camera = test::home_camera();
    const style::ThemeBlock night = test::night();
    const auto off = draw(*session, dir, camera, night);
    ATLAS_REQUIRE(off.has_value(), "the frame with the renders' pools was not drawn");
    const auto on = draw(*session, dir, with_cascades(camera), night);
    ATLAS_REQUIRE(on.has_value(), "the frame with the light maps was not drawn");
    const auto none = draw(*session, dir, with_cascades(camera), night, true);
    ATLAS_REQUIRE(none.has_value(), "the frame without the night's light was not drawn");

    // without the flag nothing of the maps: no bake, no map, the renders' ellipses
    CHECK(off->stats.bakes == 0);
    CHECK(off->frame.decided.light_tiles.empty());
    const std::vector<passes::LampPool>& pools = off->frame.decided.lamp_pools;
    CHECK(pools.size() > 20);
    // with it the tiles' maps, baked once each, and fewer ellipses (those of the tiles farther than the maps reach)
    const passes::LightStats& s = on->stats;
    CHECK(!on->frame.decided.light_tiles.empty());
    CHECK(s.bakes >= on->frame.decided.light_tiles.size());
    CHECK(s.pending == 0);
    CHECK(s.slots > 0);
    CHECK(s.slots <= passes::light_slots);
    CHECK(on->frame.decided.lamp_pools.size() < pools.size());
    CHECK(none->frame.decided.lamp_pools.empty());
    CHECK(none->frame.decided.light_tiles.empty());
    MESSAGE("the home at night: " << on->frame.decided.light_tiles.size() << " tiles' maps drawn, " << s.bakes
                                  << " bakes of " << s.ms << " ms in all on the baker's thread (the longest "
                                  << s.longest_ms << " ms), " << s.pieces << " pieces walked, " << s.lit
                                  << " blocks lit; " << s.slots << " of " << passes::light_slots << " slots taken, "
                                  << s.bytes << " bytes of maps kept; " << on->frame.decided.lamp_pools.size() << " of "
                                  << pools.size() << " lamps keep their ellipses");

    const gpu::Pixels& a = on->frame.pixels;
    const gpu::Pixels& b = off->frame.pixels;
    const gpu::Pixels& n = none->frame.pixels;
    REQUIRE((a.width == b.width && a.height == b.height && a.width == n.width && a.height == n.height));
    const double density = camera.density;
    // every pixel the maps light is warmer than the dark ground, and lies within a pool's reach of a lamp's foot on
    // the screen (twice the renders' ellipse, which is the pool's from straight above only)
    std::size_t lit = 0;
    std::size_t cold = 0;
    std::size_t astray = 0;
    double maps_light = 0;
    double pools_light = 0;
    for (std::uint32_t y = 0; y < a.height; ++y) {
        for (std::uint32_t x = 0; x < a.width; ++x) {
            const std::size_t i = ((static_cast<std::size_t>(y) * a.width) + x) * 4;
            maps_light += std::max(0, static_cast<int>(a.rgba[i]) - static_cast<int>(n.rgba[i]));
            pools_light += std::max(0, static_cast<int>(b.rgba[i]) - static_cast<int>(n.rgba[i]));
            if (a.rgba[i] == n.rgba[i] && a.rgba[i + 1] == n.rgba[i + 1] && a.rgba[i + 2] == n.rgba[i + 2]) continue;
            ++lit;
            // warm: red gains, and no less than blue does
            const int red = static_cast<int>(a.rgba[i]) - static_cast<int>(n.rgba[i]);
            const int blue = static_cast<int>(a.rgba[i + 2]) - static_cast<int>(n.rgba[i + 2]);
            if (red < 0 || blue > red) ++cold;
            const double ux = (static_cast<double>(x) + 0.5) / density;
            const double uy = (static_cast<double>(y) + 0.5) / density;
            bool near = false;
            for (const passes::LampPool& p : pools) {
                const double dx = (ux - p.cx) / (2.0 * std::max(p.rx, p.ry));
                const double dy = (uy - p.cy) / (2.0 * std::max(p.rx, p.ry));
                if ((dx * dx) + (dy * dy) <= 1.0) {
                    near = true;
                    break;
                }
            }
            if (!near) ++astray;
        }
    }
    // at each lamp's foot on the screen: the renders' pool's own light where the foot stands in the open
    std::size_t feet = 0;
    std::size_t as_pools = 0;
    std::size_t dark = 0;
    for (const passes::LampPool& p : pools) {
        const auto x = static_cast<std::int64_t>(std::floor(p.cx * density));
        const auto y = static_cast<std::int64_t>(std::floor(p.cy * density));
        if (x < 2 || y < 2 || x + 2 >= a.width || y + 2 >= a.height || p.ry * density < 3.0) continue;
        ++feet;
        const std::size_t i = ((static_cast<std::size_t>(y) * a.width) + static_cast<std::size_t>(x)) * 4;
        int apart = 0;
        int over = 0;
        for (std::size_t c = 0; c < 3; ++c) {
            apart = std::max(apart, std::abs(static_cast<int>(a.rgba[i + c]) - static_cast<int>(b.rgba[i + c])));
            over = std::max(over, std::abs(static_cast<int>(a.rgba[i + c]) - static_cast<int>(n.rgba[i + c])));
        }
        if (apart <= 10) ++as_pools;
        if (over <= 2) ++dark;
    }
    MESSAGE("the maps light " << lit << " pixels, " << cold << " of them not warm, " << astray
                              << " away from every lamp; their light is " << (maps_light / std::max(pools_light, 1.0))
                              << " of the renders' pools'; of " << feet << " lamps' feet on the screen " << as_pools
                              << " are lit as the renders' pool, " << dark << " are dark (a building stands there)");
    CHECK(lit > 5000);
    CHECK(cold == 0);
    CHECK(astray == 0);
    // (a map's pool is the 10 m circle on the ground whatever the bearing; the renders' ellipse is as deep as 10 m
    // along z look from the camera, less at a bearing: at the home's 52° about .6 of it)
    CHECK(maps_light < pools_light * 2.0);
    CHECK(maps_light > pools_light * 0.3);
    CHECK(feet > 3);
    CHECK(as_pools * 2 > feet);
    CHECK(as_pools + dark + (feet / 4) >= feet);
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);

    // the frames' times, the flag off and on, at rest and turning
    const auto off_turning = draw(*session, dir, camera, night, false, true);
    const auto on_turning = draw(*session, dir, with_cascades(camera), night, false, true);
    ATLAS_REQUIRE((off_turning.has_value() && on_turning.has_value()), "the turning frames were not drawn");
    MESSAGE("a frame at rest (ms, the mean of ten, on this device): "
            << off->frame_ms << " with the renders' pools, " << on->frame_ms
            << " with the maps; turning: " << off_turning->frame_ms << " and " << on_turning->frame_ms
            << "; turning asked for " << on_turning->stats.bakes << " bakes");
    // turning the camera bakes nothing again
    CHECK(on_turning->stats.bakes == s.bakes);

    // with the theme's daylight (the renders' DAYLIGHT: the open places' pools on the ground) at four in the
    // afternoon's hours, the places open then light the ground too, from before their walls
    style::ThemeBlock open_night = night;
    open_night.flags |= style::theme_flag::daylight;
    const auto open = draw(*session, dir, with_cascades(camera), open_night);
    ATLAS_REQUIRE(open.has_value(), "the frame with the open places' light was not drawn");
    std::size_t more = 0;
    std::size_t less = 0;
    for (std::size_t i = 0; i < a.rgba.size(); i += 4) {
        const int d = static_cast<int>(open->frame.pixels.rgba[i]) - static_cast<int>(a.rgba[i]);
        if (d > 0) ++more;
        if (d < 0) ++less;
    }
    MESSAGE("with the open places: " << open->stats.lights[fx::light_lamp] << " lamps and "
                                     << open->stats.lights[fx::light_open] << " open places baked, "
                                     << open->stats.lit << " blocks lit; " << more
                                     << " pixels lighter than with the lamps alone, " << less << " darker");
    CHECK(s.lights[fx::light_lamp] >= 22);
    CHECK(s.lights[fx::light_open] == 0);
    CHECK(open->stats.lights[fx::light_open] > 0);
    CHECK(open->stats.lights[fx::light_lamp] == s.lights[fx::light_lamp]);
    CHECK(more > 500);
    CHECK(less == 0);

    // the pictures, every pass drawn: the canal ring from the home, and a lamp-lit street from near
    const std::string dump = test::environment("ATLAS_LIGHT_DUMP");
    if (!dump.empty()) {
        std::string where;
        for (const geometry::P2& lamp : on->lamps) where += std::format(" ({:.0f}, {:.0f})", lamp.x, lamp.z);
        MESSAGE("the lamps (frame metres: east, south):" << where);
        // (the street: the seven lamps 40 m north of the Westerkerk, from 90 m)
        const double kx = 111320 * pymath::cos(pymath::radians(52.3746));
        const style::CameraState street =
            test::s1_camera(52.3746 + (40.0 / 110540.0), 4.8840 + (-10.0 / kx), 52.0, 45.0, 90.0);
        struct View {
            const char* name;
            style::CameraState camera;
            bool every_pass;
        };
        const std::array<View, 3> views{{{.name = "ring", .camera = camera, .every_pass = true},
                                         {.name = "street", .camera = street, .every_pass = true},
                                         {.name = "street-ground", .camera = street, .every_pass = false}}};
        for (const View& view : views) {
            for (const bool cascades : {false, true}) {
                const auto picture = draw(*session, dir, cascades ? with_cascades(view.camera) : view.camera,
                                          open_night, false, false, view.every_pass);
                ATLAS_REQUIRE(picture.has_value(), "a picture was not drawn");
                test::write_ppm(picture->frame.pixels,
                                std::filesystem::path(dump) /
                                    (std::string(view.name) + (cascades ? "-on.ppm" : "-off.ppm")));
            }
        }
        for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    }
}

#endif
