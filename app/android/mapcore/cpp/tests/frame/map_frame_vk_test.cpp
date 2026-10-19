// The app's map through the core's session on the host's Vulkan (plan-vector K11; frame/MapFrame.hpp,
// standin/Session.hpp's map): the packs the app lists (the bundled render packs, ATLAS_TILES_DIR, as the scene's tests
// take them) opened in the world when it is made and kept across a device's loss; the moment's theme; the pack the
// camera state names drawn, with its profile's rules (TONE off for the living map, on for the regions'); the picture
// complete once every tile the cover wants is resident and drawn and every fade is done, and nothing more due at the
// same camera after that; the world's wake called as tiles land; the map off and on again; VK bench taking the window
// and giving it back; and after the close, no marker of the crash guard on disk.
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/MapFrame.hpp"
#include "gpu/Guard.hpp"
#include "standin/Session.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/gpu/session_fixture.hpp"

#include <doctest/doctest.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <fstream>
#include <memory>
#include <sstream>
#include <string>
#include <unistd.h>
#include <vector>

using namespace atlas;

#if !defined(__ANDROID__)

namespace {

namespace fs = std::filesystem;
namespace flag = style::theme_flag;

[[nodiscard]] std::string environment(const char* name) {
    const char* v = std::getenv(name); // NOLINT(concurrency-mt-unsafe): read before the test's threads
    return v == nullptr ? std::string() : std::string(v);
}

[[nodiscard]] std::string read(const fs::path& p) {
    std::ifstream in(p);
    std::ostringstream out;
    out << in.rdbuf();
    return out.str();
}

/// The stage the guard's marker on disk names ("" for none).
[[nodiscard]] std::string marked(const fs::path& dir) {
    const std::string text = read(dir / "guard");
    if (!text.starts_with("stage ")) return {};
    return text.substr(6, text.find('\n') - 6);
}

/// A fresh guard folder, removed when the test case ends.
class Folder {
public:
    explicit Folder(std::string_view name)
        : path_(fs::temp_directory_path() / std::format("atlas-map-{}-{}", name, ::getpid())) {
        std::error_code ec;
        fs::remove_all(path_, ec);
        fs::create_directories(path_);
    }
    Folder(const Folder&) = delete;
    Folder& operator=(const Folder&) = delete;
    Folder(Folder&&) = delete;
    Folder& operator=(Folder&&) = delete;
    ~Folder() {
        std::error_code ec;
        fs::remove_all(path_, ec);
    }
    [[nodiscard]] const fs::path& path() const noexcept { return path_; }

private:
    fs::path path_;
};

/// A guarded start in `dir`, as the façade starts (it presents).
[[nodiscard]] gpu::StartOptions start_in(const fs::path& dir) {
    return {.guard_dir = dir,
            .cache_file = dir / "pipelines.bin",
            .system = "host-test-system",
            .build = "map-test",
            .validation = true,
            .require_validation = test::vulkan_required(),
            .present = true,
            .spirv = 42};
}

/// Whether the host's Vulkan opens the façade's start (a device that presents); else the test skips, saying why.
[[nodiscard]] bool can_start(const fs::path& dir) {
    const auto probe = gpu::start_guarded(start_in(dir));
    if (probe) return true;
    if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, probe.error().reason);
    return false;
}

/// amsterdam.py's sunny moment of 1 October 2026, 16:00, as Kotlin writes it (tests/style/ThemeTest.cpp's).
[[nodiscard]] style::ThemeBlock sunny() {
    style::ThemeBlock b{};
    b.version = 1;
    b.flags = flag::weather | flag::shadow_blur | flag::cloud_shadows | flag::water_gradient | flag::wet_set;
    b.year = 2026;
    b.month = 10;
    b.day = 1;
    b.hour = 16;
    b.minute = 0;
    b.moment = style::MomentName::sunny;
    b.weather_code = 2;
    b.sky_top = {.r = 0x7F, .g = 0xA6, .b = 0xDA, .a = 255};
    b.sky_horizon = {.r = 0xDC, .g = 0xE7, .b = 0xF2, .a = 255};
    b.haze = {.r = 0xDF, .g = 0xE6, .b = 0xEE, .a = 255};
    b.sun = {.r = 0xFF, .g = 0xEB, .b = 0xCB, .a = 255};
    b.ambient = {.r = 0x93, .g = 0xA6, .b = 0xCC, .a = 255};
    b.ground = {.r = 0xD6, .g = 0xCF, .b = 0xC2, .a = 255};
    b.road = {.r = 0xC9, .g = 0xC1, .b = 0xB4, .a = 255};
    b.water = {.r = 0x4F, .g = 0x75, .b = 0x90, .a = 255};
    b.water_top = {.r = 0xA6, .g = 0xBF, .b = 0xD4, .a = 255};
    b.water_bottom = {.r = 0x2D, .g = 0x50, .b = 0x6C, .a = 255};
    b.shadow = {.r = 0x27, .g = 0x30, .b = 0x5A, .a = 255};
    b.haze_max = 0.22 * 0.85;
    b.sun_k = 1.0;
    b.amb_k = 0.6;
    b.sky_k = 1.0;
    b.shadow_opacity = 0.32;
    b.shadow_blur = 0.6;
    b.cloud_shadows = 0.42;
    const double len = std::sqrt((0.5307 * 0.5307) + (0.2779 * 0.2779) + (0.8006 * 0.8006));
    b.sun_vector = {0.5307 / len, 0.2779 / len, 0.8006 / len};
    b.sun_azimuth = 213.5;
    b.sun_elevation = 16.1;
    b.cloud = 0.42;
    b.cloud_low = 0.1;
    b.wind = 14.0;
    b.gust = 27.0;
    b.wind_direction = 225.0;
    b.temperature = 14.2;
    b.pressure = 1012.5;
    b.visibility = 24000.0;
    b.humidity = 72.0;
    return b;
}

constexpr vk::Extent2D size{.width = 180, .height = 400};

/// The Westerkerk home (amsterdam.py's main camera, tests/frame/scene_vk_test.cpp's) on a 180 × 400 px window at
/// density 1, naming pack `pack`.
[[nodiscard]] style::CameraState home(std::uint64_t pack) {
    style::CameraState c{};
    c.version = style::camera_state_version;
    const double kx = 111320 * pymath::cos(pymath::radians(52.3746));
    c.latitude = 52.3746 + 30.0 / 110540.0;
    c.longitude = 4.8840 - 10.0 / kx;
    c.bearing = 52.0;
    c.pitch = 52.0;
    c.distance = 430.0;
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

/// Draws `camera` until the map's picture is complete (tiles built by the workers, their fades done), at most
/// `seconds`; how many frames went out, or -1 when it never completed.
int draw_until_complete(standin::Session& session, frame::MapFrame& map, const style::CameraState& camera,
                        double seconds = 30.0) {
    const auto until = std::chrono::steady_clock::now() + std::chrono::duration<double>(seconds);
    int drawn = 0;
    while (std::chrono::steady_clock::now() < until) {
        const standin::Drawn d = session.draw(camera, {});
        if (d.stale) session.remake();
        drawn += d.drawn ? 1 : 0;
        if (map.complete()) return drawn;
        // Nothing fading: the cover waits on the workers' tiles.
        if (!map.animating() && map.world() != nullptr) map.world()->residency().wait_idle();
    }
    return -1;
}

/// The theme the last frame drew with.
[[nodiscard]] std::uint32_t drawn_flags(const frame::MapFrame& map) { return map.builder()->state().theme.flags; }

} // namespace

TEST_CASE("the theme takes the profile's rules: TONE off for the living map, on for every other profile") {
    const style::ThemeBlock t = sunny();
    CHECK((frame::with_profile(t, tiles::Profile::living).flags & flag::tone) == 0);
    CHECK((frame::with_profile(t, tiles::Profile::region).flags & flag::tone) != 0);
    CHECK((frame::with_profile(t, tiles::Profile::world_base).flags & flag::tone) != 0);
    style::ThemeBlock toned = t;
    toned.flags |= flag::tone;
    CHECK((frame::with_profile(toned, tiles::Profile::living).flags & flag::tone) == 0);
    // Nothing else changes.
    CHECK((frame::with_profile(toned, tiles::Profile::living).flags | flag::tone) == toned.flags);
}

TEST_CASE("the app's map lists its packs before its world: listed, kept, dropped") {
    frame::MapFrame map(2, nullptr);
    std::vector<frame::MapPack> packs = bundled("/nowhere");
    CHECK(map.set_packs(packs) == "amsterdam listed\ngrachten listed\n");
    CHECK(map.set_packs(bundled("/nowhere")) == "amsterdam kept\ngrachten kept\n");
    std::vector<frame::MapPack> one = bundled("/nowhere");
    one.pop_back();
    CHECK(map.set_packs(std::move(one)) == "amsterdam kept\n");
    // Another file under the same id: a new pack, not the same bytes.
    std::vector<frame::MapPack> moved = bundled("/elsewhere");
    moved.pop_back();
    CHECK(map.set_packs(std::move(moved)) == "amsterdam listed\n");
    CHECK_FALSE(map.made());
    CHECK_FALSE(map.ready());
    CHECK_FALSE(map.complete());
    CHECK(map.drawn_pack().empty());
}

TEST_CASE("the app's map draws through the session: the pack the camera names, complete pictures, rest, a device's "
          "loss, VK bench between, the map off and on, and the close") {
    const std::string tiles_dir = environment("ATLAS_TILES_DIR");
    if (tiles_dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the map is not drawn over the bundled packs here");
        return;
    }
    if (!test::open_session()) return; // no Vulkan here
    const Folder folder("draw");
    const fs::path& dir = folder.path();
    if (!can_start(dir)) return;
    auto started = gpu::start_guarded(start_in(dir));
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    {
        // The map outlives the session that draws it (the bridge's Core closes its session first): declared before it.
        std::atomic<int> wakes{0};
        frame::MapFrame map(2, [&wakes] { wakes.fetch_add(1); });
        standin::Session session(*started, {.cache_file = dir / "pipelines.bin", .spirv = 42});
        CHECK(map.set_packs(bundled(tiles_dir)) == "amsterdam listed\ngrachten listed\n");

        // On before a window and before its moment: made, nothing to draw yet.
        CHECK(session.map(&map).empty());
        CHECK(map.made());
        CHECK_FALSE(map.ready());
        CHECK(marked(dir).empty());
        std::string why;
        ATLAS_REQUIRE(session.attach(std::make_unique<test::OffscreenWindow>(), size, why), why);
        CHECK_FALSE(session.drawing()); // no moment yet
        map.set_theme(sunny());
        CHECK(session.drawing());

        // The Westerkerk home, the living map named: complete once its tiles are built and faded in.
        const int first = draw_until_complete(session, map, home(1));
        CHECK(first > 0);
        CHECK(map.drawn_pack() == "amsterdam");
        CHECK((drawn_flags(map) & flag::tone) == 0);
        CHECK(wakes.load() > 0);
        CHECK_FALSE(map.animating()); // the picture is final: nothing more at this camera
        MESSAGE("the Westerkerk complete after " << first << " frames, " << wakes.load() << " wakes; "
                                                 << map.tiles_line());

        // The canal ring named for the same camera: its tiles, its profile's TONE.
        const int ring = draw_until_complete(session, map, home(2));
        CHECK(ring > 0);
        CHECK(map.drawn_pack() == "grachten");
        CHECK((drawn_flags(map) & flag::tone) != 0);

        // Past the first 120 frames the frames stage is closed; nothing is named between calls.
        for (int i = 0; i < 130; ++i) (void)session.draw(home(2), {});
        CHECK(marked(dir).empty());

        // Nowhere (mid-Atlantic), no pack named: none drawn, never complete.
        style::CameraState away = home(0);
        away.latitude = 40.0;
        away.longitude = -30.0;
        CHECK(session.draw(away, {}).drawn);
        CHECK(map.drawn_pack().empty());
        CHECK_FALSE(map.complete());

        // A device's loss: the map made again on the new device with its packs, and complete again.
        session.lose_device();
        CHECK(map.made());
        CHECK(session.drawing());
        CHECK(draw_until_complete(session, map, home(1)) > 0);
        CHECK(map.drawn_pack() == "amsterdam");

        // A listing whose bytes are not a pack: refused, saying why, and not kept, so the next listing opens the real
        // one again.
        {
            std::ofstream(dir / "junk.atlas") << "not a pack";
            std::vector<frame::MapPack> junk = bundled(tiles_dir);
            junk[1].ref.path = (dir / "junk.atlas").string();
            const std::string said = map.set_packs(std::move(junk));
            CHECK(said.starts_with("amsterdam kept\ngrachten: "));
            CHECK(map.set_packs(bundled(tiles_dir)) == "amsterdam kept\ngrachten opened\n");
        }
        // The same packs listed again stay open; the canal ring dropped, its number leaves the choice to the world.
        CHECK(map.set_packs(bundled(tiles_dir)) == "amsterdam kept\ngrachten kept\n");
        std::vector<frame::MapPack> one = bundled(tiles_dir);
        one.pop_back();
        CHECK(map.set_packs(std::move(one)) == "amsterdam kept\n");
        CHECK(draw_until_complete(session, map, home(2)) > 0);
        CHECK(map.drawn_pack() == "amsterdam"); // number 2 is gone: the world's own choice holds the target

        // VK bench takes the window, then gives it back to the map.
        const gpu::Configuration pick = started->device->facts().choice;
        CHECK(session.bench(1, pick, 0).empty());
        CHECK(session.draw(home(1), {}).drawn); // the stand-in
        CHECK(session.bench(0, pick, 0).empty());
        CHECK(session.map(&map).empty());
        CHECK(draw_until_complete(session, map, home(1)) > 0);
        // VK bench's mode 0 (its end, its pause) is not the map's to switch off.
        CHECK(session.bench(0, pick, 0).empty());
        CHECK(session.drawing());
        CHECK(session.draw(home(1), {}).drawn);

        // Off: nothing drawn, and what it drew with gone; on again: made again, drawing.
        CHECK(session.map(nullptr).empty());
        CHECK_FALSE(session.drawing());
        CHECK_FALSE(map.made());
        CHECK(session.map(&map).empty());
        CHECK(session.drawing());
        CHECK(draw_until_complete(session, map, home(1)) > 0);
        session.rest();
        CHECK(marked(dir).empty());
    }
    // The session's close let go of the map's world and frame behind the close stage: nothing on disk.
    CHECK(marked(dir).empty());
    CHECK_FALSE(fs::exists(dir / "guard"));
}

#endif
