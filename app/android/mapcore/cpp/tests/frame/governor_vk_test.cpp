// The triangle governor in the app's map (scene/Governor.hpp, ENGINE.md step 5), on the bundled render packs
// (ATLAS_TILES_DIR): at the device's budget the bias is 0 at every view of the references and VK bench over the canal
// ring (the Westerkerk home, the Main board and its pitch of 70°, the bench's pinch out to 8 km), so the cover is as
// without the governor; over a budget no view meets, the bias rises by 0.1 level a frame to 1.5, the places beyond the
// finest level's nearer reach take their coarser level, the frame draws fewer triangles, and the picture is complete
// with no place in view a hole.
#include "base/pymath/PyMath.hpp"
#include "frame/MapFrame.hpp"
#include "gpu/Guard.hpp"
#include "scene/Cover.hpp"
#include "scene/Governor.hpp"
#include "scene/Residency.hpp"
#include "scene/World.hpp"
#include "standin/Session.hpp"
#include "tests/base/require.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/gpu/session_fixture.hpp"
#include "tests/ground/ground_support.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <unistd.h>
#include <vector>

using namespace atlas;

#if !defined(__ANDROID__)

namespace {

namespace fs = std::filesystem;

[[nodiscard]] std::string environment(const char* name) {
    const char* v = std::getenv(name); // NOLINT(concurrency-mt-unsafe): read before the test's threads
    return v == nullptr ? std::string() : std::string(v);
}

constexpr vk::Extent2D size{.width = 180, .height = 400};

/// A guarded start in `dir`, as the façade starts (it presents).
[[nodiscard]] gpu::StartOptions start_in(const fs::path& dir) {
    return {.guard_dir = dir,
            .cache_file = dir / "pipelines.bin",
            .system = "host-test-system",
            .build = "governor-test",
            .present = true,
            .spirv = 42};
}

/// A camera over (lat, lon) on a 180 × 400 px window at density 1, at rest, naming pack `pack`.
[[nodiscard]] style::CameraState camera_at(double lat, double lon, double bearing, double pitch, double distance,
                                           std::uint64_t pack) {
    style::CameraState c{};
    c.version = style::camera_state_version;
    c.latitude = lat;
    c.longitude = lon;
    c.bearing = bearing;
    c.pitch = pitch;
    c.distance = distance;
    c.principal_x = 90.0;
    c.principal_y = 192.0;
    c.density = 1.0;
    c.width_px = size.width;
    c.height_px = size.height;
    c.width_dp = size.width;
    c.height_dp = size.height;
    c.flags = style::camera_flag::rest;
    c.pack_id = pack;
    return c;
}

/// The bundled render packs of ATLAS_TILES_DIR as the app lists them: the Westerkerk (1), the canal ring (2).
[[nodiscard]] std::vector<frame::MapPack> bundled(const fs::path& dir) {
    std::vector<frame::MapPack> out;
    std::uint64_t number = 0;
    for (const char* id : {"amsterdam", "grachten"}) {
        frame::MapPack p;
        p.number = ++number;
        p.ref.id = id;
        p.ref.kind = tiles::PackKind::bundled;
        p.ref.path = (dir / (std::string(id) + ".atlas")).string();
        out.push_back(std::move(p));
    }
    return out;
}

/// Draws `camera` until the picture is complete and nothing is mid-transition (the fades, a bias that moved), at
/// most 240 s (draw_until_complete's bound in map_frame_vk_test); the frames drawn, or -1.
int draw_until_still(standin::Session& session, frame::MapFrame& map, const style::CameraState& camera) {
    const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(240);
    int drawn = 0;
    while (std::chrono::steady_clock::now() < until) {
        const standin::Drawn d = session.draw(camera, {});
        if (d.stale) session.remake();
        drawn += d.drawn ? 1 : 0;
        if (map.complete() && !map.animating() && drawn > 2) return drawn;
        if (!map.animating()) map.world()->residency().wait_idle();
    }
    return -1;
}

/// The places the last frame's cover drew from each level: the cells of its tiles by their level.
[[nodiscard]] std::map<int, std::size_t> by_level(frame::MapFrame& map) {
    std::map<int, std::size_t> out;
    for (const scene::CoverTile& t : map.world()->cover()->tiles()) out[t.drawn->key.z] += 1;
    return out;
}

[[nodiscard]] std::string levels_text(const std::map<int, std::size_t>& levels) {
    std::string s;
    for (const auto& [z, n] : levels) s += std::format(" L{} {}", z, n);
    return s;
}

/// The Westerkerk home (amsterdam.py's main camera) and the canal ring's Main board and its pitch of 70° (VK bench's
/// map views), and the bench's pinch out from the home to 8 km.
struct View {
    std::string name;
    style::CameraState camera;
};

[[nodiscard]] std::vector<View> reference_views() {
    const double kx = 111320 * pymath::cos(pymath::radians(52.3746));
    const double lat = 52.3746 + (30.0 / 110540.0);
    const double lon = 4.8840 - (10.0 / kx);
    std::vector<View> views{
        {.name = "the Westerkerk home", .camera = camera_at(lat, lon, 52.0, 52.0, 430.0, 1)},
        {.name = "the Main board", .camera = camera_at(52.3746, 4.884, 52.0, 46.0, 2300.0, 2)},
        {.name = "the Main board at 70°", .camera = camera_at(52.3746, 4.884, 52.0, 70.0, 2300.0, 2)},
    };
    for (const double d : {1000.0, 2000.0, 4000.0, 8000.0}) {
        views.push_back(
            {.name = std::format("the home's pinch at {:.0f} m", d), .camera = camera_at(lat, lon, 52.0, 52.0, d, 2)});
    }
    return views;
}

} // namespace

TEST_CASE("the governor: at the device's budget the bias is 0 at every reference and VK bench view of the canal "
          "ring") {
    const std::string tiles_dir = environment("ATLAS_TILES_DIR");
    if (tiles_dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the governor is not drawn here");
        return;
    }
    if (!test::open_session()) return;
    const fs::path guard = fs::temp_directory_path() / std::format("atlas-governor-{}", ::getpid());
    fs::create_directories(guard);
    auto started = gpu::start_guarded(start_in(guard));
    if (!started) {
        // a Vulkan without a window system (CI's arm64 lavapipe) skips, as the map's other sessions do
        if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, started.error().reason);
        return;
    }
    {
        frame::MapFrame map(2, [] {}, {.cache_dir = guard});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        CHECK(map.set_packs(bundled(tiles_dir)) == "amsterdam listed\ngrachten listed\n");
        CHECK(session.map(&map).empty());
        std::string why;
        ATLAS_REQUIRE(session.attach(std::make_unique<test::OffscreenWindow>(), size, why), why);
        map.set_theme(test::sunny());
        for (const View& v : reference_views()) {
            CAPTURE(v.name);
            ATLAS_REQUIRE(draw_until_still(session, map, v.camera) > 0, std::format("{} never completed", v.name));
            const scene::World& w = *map.world();
            MESSAGE(std::format("{}: {:.2f} M triangles planned of the budget's {:.1f} M, bias {:.2f};{}", v.name,
                                static_cast<double>(w.planned_triangles()) / 1.0e6,
                                static_cast<double>(w.triangle_budget()) / 1.0e6, w.frame_bias(),
                                levels_text(by_level(map))));
            CHECK(w.planned_triangles() > 0);
            CHECK(w.planned_triangles() <= w.triangle_budget());
            CHECK(w.frame_bias() == 0.0);
        }
        // and no frame of them all had a bias
        CHECK(map.world()->most_bias() == 0.0);
        session.close();
    }
    std::error_code ec;
    fs::remove_all(guard, ec);
}

TEST_CASE("the governor: over a budget no view meets, the bias rises by 0.1 level a frame to 1.5 and the places "
          "beyond the finest level's nearer reach take their coarser level, no place a hole") {
    const std::string tiles_dir = environment("ATLAS_TILES_DIR");
    if (tiles_dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the governor is not drawn here");
        return;
    }
    if (!test::open_session()) return;
    const fs::path guard = fs::temp_directory_path() / std::format("atlas-governed-{}", ::getpid());
    fs::create_directories(guard);
    // the home's pinch at 3 km (the eye 1.85 km up, every place within the finest level's 4.7 km) at the device's
    // budget, then over a budget of one triangle
    const double kx = 111320 * pymath::cos(pymath::radians(52.3746));
    const style::CameraState far = camera_at(52.3746 + (30.0 / 110540.0), 4.8840 - (10.0 / kx), 52.0, 52.0, 3000.0, 2);
    std::map<int, std::size_t> free_levels;
    std::uint64_t free_planned = 0;
    std::uint32_t free_drawn_k = 0;
    for (const std::uint64_t budget : {std::uint64_t{0}, std::uint64_t{1}}) {
        CAPTURE(budget);
        // a start of its own for each (a session's close lets go of the device)
        auto started = gpu::start_guarded(start_in(guard));
        if (!started) {
            if (auto instance = test::open_instance({}); instance) {
                test::cannot_present(*instance, started.error().reason);
            }
            return;
        }
        frame::MapFrame map(2, [] {}, {.cache_dir = guard, .triangle_budget = budget});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        (void)map.set_packs(bundled(tiles_dir));
        CHECK(session.map(&map).empty());
        std::string why;
        ATLAS_REQUIRE(session.attach(std::make_unique<test::OffscreenWindow>(), size, why), why);
        map.set_theme(test::sunny());
        if (budget == 1) {
            // from the first frame its cover planned (the pack chosen), a step of 0.1 level each frame drawn, to 1.5
            // in fifteen frames, and no further
            std::vector<double> biases;
            for (int i = 0; i < 200 && biases.size() < 20; ++i) {
                const standin::Drawn d = session.draw(far, {});
                if (d.stale) session.remake();
                if (d.drawn && map.world() != nullptr) biases.push_back(map.world()->frame_bias());
            }
            ATLAS_REQUIRE(biases.size() == 20);
            std::size_t first = 0;
            while (first < biases.size() && biases[first] == 0.0) ++first;
            CHECK(first >= 1);
            CHECK(first <= 3);
            for (std::size_t i = first; i < biases.size(); ++i) {
                CHECK(biases[i] == doctest::Approx(std::min(0.1 * static_cast<double>(i + 1 - first), 1.5)));
            }
        }
        ATLAS_REQUIRE(draw_until_still(session, map, far) > 0, "the view from 3 km never completed");
        const standin::Drawn last = session.draw(far, {});
        const scene::World& w = *map.world();
        const std::map<int, std::size_t> levels = by_level(map);
        MESSAGE(std::format("budget {}: bias {:.2f}, {:.2f} M triangles planned, {}k drawn;{}; holes {}",
                            budget == 0 ? std::string("the device's") : std::to_string(budget), w.frame_bias(),
                            static_cast<double>(w.planned_triangles()) / 1.0e6, last.record.triangles_k,
                            levels_text(levels), w.cover()->holes()));
        CHECK(map.complete());
        CHECK(w.cover()->holes() == 0);
        CHECK(w.cover()->missing() == 0);
        if (budget == 0) {
            CHECK(w.frame_bias() == 0.0);
            free_levels = levels;
            free_planned = w.planned_triangles();
            free_drawn_k = last.record.triangles_k;
        } else {
            CHECK(w.frame_bias() == doctest::Approx(1.5));
            // the finest level's reach 1.66 km, nearer than the eye's height: the places it held are drawn from their
            // L12, fewer triangles planned and drawn
            CHECK(levels.contains(12));
            CHECK(levels.at(12) > (free_levels.contains(12) ? free_levels.at(12) : 0U));
            CHECK((levels.contains(14) ? levels.at(14) : 0U) < (free_levels.contains(14) ? free_levels.at(14) : 0U));
            CHECK(w.planned_triangles() < free_planned);
            CHECK(last.record.triangles_k < free_drawn_k);
        }
        session.close();
    }
    std::error_code ec;
    fs::remove_all(guard, ec);
}

#endif
