// The ground's team (passes/Team.hpp) decides nothing of its own: the home and the canal ring's board drawn with the
// tiles and their mirrors decided on three helpers beside the render thread and on the render thread alone, by day
// with weather (the reflections) and at night with the hour's weather (the streaks), are the same picture, and a
// moving frame's decisions over the same tiles (each fill's, stroke's and shadow's, the strokes' widths, the ranks
// across scenes, the reflections' elements with their faces, circles and streaks) the same to the bit.
#if !defined(__ANDROID__)

#include "frame/Selectors.hpp"
#include "passes/Ground.hpp"
#include "tests/base/require.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"

#include <doctest/doctest.h>

#include <array>
#include <bit>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace atlas::test {

namespace {

/// A frame's decisions over its drawn tiles, FNV-1a: each fill's, stroke's and shadow's, the strokes' widths, the
/// ranks across scenes, the slots, and the reflections' elements with their faces, circles and streaks.
[[nodiscard]] std::uint64_t decisions_hash(const passes::GroundFrame& f) {
    std::uint64_t h = 0xCBF2'9CE4'8422'2325ULL;
    const auto put = [&h](std::uint64_t v) {
        for (int i = 0; i < 8; ++i) {
            h ^= (v >> (8 * i)) & 0xFFU;
            h *= 0x100'0000'01B3ULL;
        }
    };
    const auto put_d = [&put](double v) { put(std::bit_cast<std::uint64_t>(v)); };
    put(f.tiles.size());
    for (const std::uint8_t b : f.drawn) put(b);
    for (const std::uint8_t b : f.stroke_drawn) put(b);
    for (const double w : f.stroke_width) put_d(w);
    for (const std::uint8_t b : f.shadow_drawn) put(b);
    for (const std::uint32_t r : f.fill_rank) put(r);
    for (const std::uint32_t r : f.stroke_rank) put(r);
    put(f.slots);
    put(f.reflections.size());
    for (const passes::Reflection& e : f.reflections) {
        put(static_cast<std::uint64_t>(e.kind));
        put(e.tile);
        put(e.first);
        put(e.count);
        put(e.colour);
        put(e.lit);
        put(e.unlit);
        put(e.sample);
        put(e.sampled ? 1U : 0U);
    }
    for (const passes::ReflectionFace& q : f.reflection_faces) {
        put(q.n);
        for (const view::Vec3& c : q.q) {
            put_d(c.x);
            put_d(c.y);
            put_d(c.z);
        }
    }
    for (const passes::ReflectionCircle& c : f.reflection_circles) {
        for (const double v : {c.cx, c.cy, c.r}) put_d(v);
    }
    for (const passes::ReflectionStreak& s : f.reflection_streaks) {
        for (const double v : {s.cx, s.cy, s.rx, s.ry, s.opacity}) put_d(v);
    }
    return h;
}

} // namespace

TEST_CASE("ground: its team decides the tiles and their mirrors as the render thread alone does") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the ground is not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    struct Case {
        std::string name;
        std::string pack;
        style::CameraState camera;
        style::ThemeBlock theme;
    };
    const std::vector<Case> cases{
        {"home by day", "amsterdam", test::home_camera(), test::sunny()},
        {"home at night", "amsterdam", test::home_camera(), test::night_weather()},
        {"board by day", "grachten", test::view_camera(dir, "grachten", "grachten_home"), test::sunny()},
    };
    for (const Case& c : cases) {
        // the team's first (three helpers), then the render thread's alone
        std::array<GroundDraw, 2> drawn;
        std::array<std::uint64_t, 2> moving{};
        std::array<std::size_t, 2> mirrored{};
        for (std::size_t k = 0; k < 2; ++k) {
            const unsigned helpers = k == 0 ? 3U : 0U;
            auto d = test::draw_ground(
                *session, c.camera, c.theme, dir, c.pack,
                [&](passes::Ground& g, const frame::FrameState& state) {
                    frame::Selectors selectors;
                    frame::FrameState m = state;
                    m.rest = false;
                    g.select(m, selectors);
                    moving.at(k) = decisions_hash(g.decided());
                    mirrored.at(k) = g.decided().reflections.size();
                    g.select(state, selectors); // the frame's own decisions again
                },
                40, [helpers](passes::Ground& g) { g.decide_on(helpers); });
            ATLAS_REQUIRE(d.has_value(), c.name << ": the frame was not drawn");
            drawn.at(k) = std::move(*d);
        }
        const GroundDraw& team = drawn[0];
        const GroundDraw& alone = drawn[1];
        MESSAGE(c.name << ": " << team.decided.tiles.size() << " tiles, " << team.decided.reflections.size()
                       << " reflections at rest, " << mirrored[0] << " moving");
        CHECK(team.decided.tiles.size() > 1);
        CHECK(decisions_hash(team.decided) == decisions_hash(alone.decided));
        CHECK(moving[0] == moving[1]);
        CHECK(mirrored[0] == mirrored[1]);
        REQUIRE(team.pixels.rgba.size() == alone.pixels.rgba.size());
        std::size_t differ = 0;
        for (std::size_t i = 0; i < team.pixels.rgba.size(); ++i) {
            if (team.pixels.rgba[i] != alone.pixels.rgba[i]) ++differ;
        }
        CHECK_MESSAGE(differ == 0, c.name << ": " << differ << " bytes of the picture differ");
        for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, c.name << ": " << m);
    }
}

} // namespace atlas::test

#endif
