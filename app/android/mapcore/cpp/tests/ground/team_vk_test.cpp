// The ground's team (passes/Team.hpp) decides nothing of its own: the home and the canal ring's board drawn with the
// tiles and their mirrors decided on three helpers beside the render thread and on the render thread alone, by day
// with weather (the reflections) and at night with the hour's weather (the streaks), are the same picture, and a
// moving frame's decisions over the same tiles (each fill's, stroke's and shadow's, the strokes' widths, the ranks
// across scenes, the reflections' elements with their faces, circles and streaks) the same to the bit. The frame tells
// the platform of the team's threads once a team, the render thread first, as the render thread's hint session takes
// them in (frame/RenderThreads.hpp; the session itself is Android's).
#if !defined(__ANDROID__)

#include "frame/RenderThreads.hpp"
#include "frame/Selectors.hpp"
#include "passes/Ground.hpp"
#include "passes/Team.hpp"
#include "tests/base/require.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <memory>
#include <optional>
#include <set>
#include <span>
#include <string>
#include <thread>
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

/// The threads the frame says its work runs on, each list as told: what the render thread's hint session would take
/// in (platform::HintSession::threads, Android's).
class Told final : public frame::RenderThreads {
public:
    void took(std::span<const std::int32_t> tids) override { lists.emplace_back(tids.begin(), tids.end()); }
    std::vector<std::vector<std::int32_t>> lists;
};

/// Whether `list` is the render thread `me` and then `helpers` threads beside it, none twice, each one of this
/// process's (where the host lists them).
void check_threads(const std::vector<std::int32_t>& list, std::int32_t me, std::size_t helpers) {
    ATLAS_REQUIRE(list.size() == helpers + 1, list.size() << " threads told");
    CHECK(list[0] == me);
    CHECK(std::set<std::int32_t>(list.begin(), list.end()).size() == list.size());
#if defined(__linux__)
    for (const std::int32_t tid : list) {
        CHECK_MESSAGE(std::filesystem::exists("/proc/self/task/" + std::to_string(tid)), tid);
    }
#endif
}

} // namespace

TEST_CASE("ground: the platform is told of its team's threads once a team, the render thread first") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the ground is not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    auto told = std::make_shared<Told>();
    frame::FrameOptions options{.steps = 0};
    options.render_threads = told;
    const std::int32_t me = passes::thread_id();
    const std::size_t first = std::clamp(std::thread::hardware_concurrency(), 1U, 4U) - 1;
    std::vector<std::vector<std::int32_t>> over_frames;
    std::size_t moving_told = 0;
    auto d = test::draw_ground(
        *session, test::home_camera(), test::sunny(), dir, "amsterdam",
        [&](passes::Ground& g, const frame::FrameState& state) {
            // the team made as the first tiles were decided, told once over the frames (while its threads live)
            over_frames = told->lists;
            ATLAS_REQUIRE(over_frames.size() == 1, over_frames.size() << " lists told over the frames");
            check_threads(over_frames[0], me, first);
            frame::Selectors selectors;
            frame::FrameState m = state;
            m.rest = false; // every tile decided again, on the team
            g.select(m, selectors);
            moving_told = told->lists.size();
            // made anew: told at its first use, and not again at the next
            g.decide_on(2);
            g.select(m, selectors);
            g.select(m, selectors);
            ATLAS_REQUIRE(told->lists.size() == 2, told->lists.size() << " lists told");
            check_threads(told->lists[1], me, 2);
            // a team of none: the render thread alone
            g.decide_on(0);
            g.select(m, selectors);
            ATLAS_REQUIRE(told->lists.size() == 3, told->lists.size() << " lists told");
            check_threads(told->lists[2], me, 0);
        },
        8, {}, frame::Mode::structural, nullptr, {}, options);
    ATLAS_REQUIRE(d.has_value(), "the home was not drawn");
    CHECK(d->decided.tiles.size() > 1);
    CHECK(moving_told == 1);
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
}

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
