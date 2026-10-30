// The app's map through the core's session on the host's Vulkan (plan-vector K11; frame/MapFrame.hpp,
// standin/Session.hpp's map): the packs the app lists (the bundled render packs, ATLAS_TILES_DIR, as the scene's tests
// take them) opened in the world when it is made and kept across a device's loss; the moment's theme; the pack the
// camera state names drawn, with its profile's rules (TONE off for the living map, on for the regions'); the picture
// complete once every tile the cover wants is resident and drawn and every fade is done, and nothing more due at the
// same camera after that; the world's wake called as tiles land; the map off and on again; VK bench taking the window
// and giving it back; and after the close, no marker of the crash guard on disk.
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBlock.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/MapFrame.hpp"
#include "fx/FieldCache.hpp"
#include "fx/GpuFields.hpp"
#include "gpu/Guard.hpp"
#include "gpu/Offscreen.hpp"
#include "passes/Labels.hpp"
#include "scene/Cover.hpp"
#include "scene/Residency.hpp"
#include "standin/Session.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/gpu/session_fixture.hpp"
#include "text/Fonts.hpp"
#include "view/Camera.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <fstream>
#include <memory>
#include <optional>
#include <span>
#include <sstream>
#include <string>
#include <string_view>
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

/// The tiles the map's last frame drew: each one's handle, its cells and whether its fade is done.
[[nodiscard]] std::vector<std::uint64_t> drawn_tiles(frame::MapFrame& map) {
    std::vector<std::uint64_t> out;
    for (const scene::CoverTile& t : map.world()->cover()->tiles()) {
        out.push_back(t.drawn != nullptr ? t.drawn->handle : 0U);
        out.push_back(t.cells);
        out.push_back(t.fade >= 1.0F ? 1U : 0U);
    }
    return out;
}

/// Draws `camera` until the map's picture is complete (tiles built by the workers, their fades done) and stays so, at
/// most `seconds`; how many frames went out, or -1 when it never completed. A complete picture waits on the named
/// pack's tiles alone, and the packs drawn under it or beside it may take theirs in after (on a slow build, as
/// TSan's, a frame or more after): the workers finish, and the next frame draws the same tiles. The bound catches a
/// picture that never completes, not a slow one (timing gates hold only with ATLAS_TIMED=1, on a quiet machine): 240 s,
/// as look_compare_vk_test's and pinch_audit_vk_test's own loops wait. 30 s was too short for a case in a process of
/// its own on CI's runners, beside other tests (ci/flow, 30 October: the clouds' case under ASan, the Prinsengracht
/// on a wet night under TSan).
int draw_until_complete(standin::Session& session, frame::MapFrame& map, const style::CameraState& camera,
                        double seconds = 240.0) {
    const auto until = std::chrono::steady_clock::now() + std::chrono::duration<double>(seconds);
    int drawn = 0;
    std::optional<std::vector<std::uint64_t>> complete; // the tiles of the last frame, when it was complete
    while (std::chrono::steady_clock::now() < until) {
        const standin::Drawn d = session.draw(camera, {});
        if (d.stale) session.remake();
        drawn += d.drawn ? 1 : 0;
        if (!map.complete()) {
            complete.reset();
            // Nothing fading: the cover waits on the workers' tiles.
            if (!map.animating() && map.world() != nullptr) map.world()->residency().wait_idle();
            continue;
        }
        std::vector<std::uint64_t> tiles = drawn_tiles(map);
        if (complete == tiles) return drawn;
        complete = std::move(tiles);
        map.world()->residency().wait_idle();
    }
    return -1;
}

/// Draws `camera` until the last frame leaves nothing mid-transition (MapFrame::animating: the world's fades, the
/// frame's blend and the passes' own, a label's among them), at most `seconds`; whether it came to rest. A complete
/// picture may still hold a label fading where the camera left it (250 ms of the steady clock), which a fast build
/// draws inside its fade. The bound catches a map that never comes to rest, as draw_until_complete's does: a frame
/// under TSan beside other tests on CI's runners takes seconds, and a fade's end may wait for the frame after it.
bool draw_until_still(standin::Session& session, frame::MapFrame& map, const style::CameraState& camera,
                      double seconds = 240.0) {
    const auto until = std::chrono::steady_clock::now() + std::chrono::duration<double>(seconds);
    while (map.animating()) {
        if (std::chrono::steady_clock::now() >= until) return false;
        if (session.draw(camera, {}).stale) session.remake();
    }
    return true;
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
        frame::MapFrame map(2, [&wakes] { wakes.fetch_add(1); }, {.cache_dir = dir});
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
        // the attach left the map's pipelines to its first frame: it waits on the app's main thread
        CHECK_FALSE(session.renderer_made());
        map.set_theme(sunny());
        CHECK(session.drawing());

        // The Westerkerk home, the living map named: complete once its tiles are built and faded in.
        const int first = draw_until_complete(session, map, home(1));
        CHECK(first > 0);
        CHECK(session.renderer_made());
        CHECK(map.drawn_pack() == "amsterdam");
        CHECK((drawn_flags(map) & flag::tone) == 0);
        CHECK(wakes.load() > 0);
        CHECK_FALSE(map.animating()); // the picture is final: nothing more at this camera
        // The world holds K17a's noise fields, kept under the app's cache once made for the frame's size.
        {
            const std::span<frame::GlobalProvider* const> globals = map.world()->globals();
            const auto fields =
                std::ranges::find_if(globals, [](frame::GlobalProvider* g) { return g->name() == "fields"; });
            ATLAS_REQUIRE(fields != globals.end(), "the map's world has no noise fields");
            // The provider named "fields" is the map's FieldCache (no RTTI in the core's builds).
            auto* cache =
                static_cast<fx::FieldCache*>(*fields); // NOLINT(cppcoreguidelines-pro-type-static-cast-downcast)
            cache->worker().wait_idle();
            CHECK(fs::exists(dir / "fields"));
        }
        MESSAGE("the Westerkerk complete after " << first << " frames, " << wakes.load() << " wakes; "
                                                 << map.tiles_line());

        // The canal ring named for the same camera: its tiles, lit as the living map around it (frame::with_pack: in
        // the Netherlands, where the windows draw the New looks, no pack takes its profile's TONE).
        const int ring = draw_until_complete(session, map, home(2));
        CHECK(ring > 0);
        CHECK(map.drawn_pack() == "grachten");
        CHECK((drawn_flags(map) & flag::tone) == 0);

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

TEST_CASE("the app's map makes a size the disk does not hold on the GPU before its first frame, which draws with its "
          "fields, where the session has the GPU's noise on; on lavapipe the session takes the CPU's") {
    if (!test::open_session()) return; // no Vulkan here
    const Folder folder("noise");
    const fs::path& dir = folder.path();
    if (!can_start(dir)) return;
    const style::CameraState camera = home(0); // no pack: the frame's fields alone
    const fx::FieldSize s = fx::size_of(camera.width_px, camera.height_px, camera.density);
    {
        // On a CPU implementation only where the session is told to (the hosts' tests): the phone's GPU's path.
        auto started = gpu::start_guarded(start_in(dir));
        ATLAS_REQUIRE(started.has_value(), started.error().reason);
        frame::MapFrame map(2, nullptr, {.cache_dir = dir});
        standin::Session session(*started, {.cache_file = dir / "pipelines.bin", .spirv = 42, .noise_on_cpu = true});
        CHECK(session.map(&map).empty());
        ATLAS_REQUIRE(map.fields() != nullptr, "the map's world has no noise fields");
        std::string why;
        ATLAS_REQUIRE(session.attach(std::make_unique<test::OffscreenWindow>(), size, why), why);
        map.set_theme(sunny());
        CHECK(map.noise_due(camera));
        CHECK(session.draw(camera, {}).drawn);
        // The first frame drew with them: made on the GPU, behind the noise stage, before it.
        CHECK((map.builder()->state().block.info.x & shaders::FRAME_FIELDS) != 0U);
        CHECK(map.fields()->bound_for(s));
        CHECK(map.fields()->gpu_off().empty());
        CHECK_FALSE(map.noise_due(camera));
        map.fields()->worker().wait_idle();
        CHECK(fx::FieldDisk(dir / "fields").holds(s));
        // VK facts' fields line on the session's device, the GPU's fields among it.
        const std::string line = session.noise([](const gpu::Started& st, std::string_view off) {
            return off.empty()
                       ? fx::fields_line(fx::size_of(96, 64, 2.0), 2, *st.device, *st.cache, st.guard.get(), off)
                       : std::string(off);
        });
        CHECK(line.starts_with("fields 96x64 cpu "));
        CHECK(line.ends_with(" unchecked"));
        CHECK_FALSE(line.contains(" gpu off"));
        session.close();
    }
    {
        // As the emulator's lavapipe and SwiftShader: the CPU's, the GPU making nothing.
        auto started = gpu::start_guarded(start_in(dir));
        ATLAS_REQUIRE(started.has_value(), started.error().reason);
        frame::MapFrame map(2, nullptr);
        standin::Session session(*started, {.cache_file = dir / "pipelines.bin", .spirv = 42});
        CHECK(session.map(&map).empty());
        std::string why;
        ATLAS_REQUIRE(session.attach(std::make_unique<test::OffscreenWindow>(), size, why), why);
        map.set_theme(sunny());
        CHECK_FALSE(map.noise_due(camera));
        CHECK(session.draw(camera, {}).drawn);
        CHECK(session.noise([](const gpu::Started&, std::string_view off) { return std::string(off); }) ==
              (started->device->facts().hardware ? "" : "a CPU implementation"));
        session.close();
    }
    CHECK_FALSE(fs::exists(dir / "guard"));
}

namespace {

namespace part = style::camera_part;

/// A camera of the map over the bundled packs (home()'s window and pack 1), its target at (`lat`, `lon`).
[[nodiscard]] style::CameraState camera_at(double lat, double lon, double bearing, double pitch, double distance,
                                           std::uint64_t pack = 1) {
    style::CameraState c = home(pack);
    c.latitude = lat;
    c.longitude = lon;
    c.bearing = bearing;
    c.pitch = pitch;
    c.distance = distance;
    return c;
}

/// The hour's weather on a wet night (the night's tones, the sun below the horizon, rain in the hour): the lit
/// streets and their wet cores, the lamps' pools, halos and wet streaks, the streaks on the water, the drizzle's rings
/// and the rain.
[[nodiscard]] style::ThemeBlock wet_night() {
    style::ThemeBlock b = sunny();
    b.flags = (b.flags | flag::night | flag::wet | flag::wet_set) & ~(flag::shadow_blur | flag::cloud_shadows);
    b.moment = style::MomentName::night;
    b.weather_code = 61;
    b.sky_top = {.r = 0x05, .g = 0x08, .b = 0x14, .a = 255};
    b.sky_horizon = {.r = 0x1C, .g = 0x23, .b = 0x40, .a = 255};
    b.haze = {.r = 0x16, .g = 0x1D, .b = 0x36, .a = 255};
    b.ground = {.r = 0x12, .g = 0x15, .b = 0x1F, .a = 255};
    b.road = {.r = 0x1B, .g = 0x1F, .b = 0x2C, .a = 255};
    b.water = {.r = 0x0B, .g = 0x14, .b = 0x22, .a = 255};
    b.water_top = {.r = 0x13, .g = 0x1B, .b = 0x2A, .a = 255};
    b.water_bottom = {.r = 0x03, .g = 0x06, .b = 0x0B, .a = 255};
    b.shadow_opacity = 0.0;
    b.shadow_blur = 0.0;
    b.cloud_shadows = 0.0;
    b.sun_k = 0.0;
    b.sun_vector = {0.0, -1.0, 0.0};
    b.sun_elevation = -21.4;
    b.rain = 1.2;
    return b;
}

/// The golden moment: the wash over the frame.
[[nodiscard]] style::ThemeBlock golden() {
    style::ThemeBlock b = sunny();
    b.flags |= flag::golden;
    b.moment = style::MomentName::golden;
    return b;
}

/// One part of the map's picture as the frame's counters show it: the kinds it alone draws or dispatches (none with it
/// left out), the kinds it shares with other parts (fewer triangles or dispatches with it left out), the composite's
/// step or the frame's mode bit it takes, the parts it is drawn with (left out with any of them), and those whose
/// switch draws it another way (its own draws then others).
struct Part {
    const char* name;
    std::uint64_t bit;
    std::vector<std::string_view> own;
    std::vector<std::string_view> shared;
    std::uint32_t step = 0;     // its composite step, off with it left out
    std::uint32_t mode_bit = 0; // a mode bit the frame takes with it left out
    std::uint64_t needs = 0;
    std::uint64_t changes = 0;
};

/// A part's work in the last frame: its own kinds' draws and dispatches, its shared kinds' triangles and dispatches,
/// whether its step ran or its mode bit is clear.
struct Work {
    std::uint64_t own = 0;
    std::uint64_t shared = 0;
    bool step = false;
    [[nodiscard]] bool any() const noexcept { return own > 0 || shared > 0 || step; }
};

[[nodiscard]] Work work_of(const frame::FrameBuilder& b, const Part& p) {
    Work w;
    for (const frame::PassCounters& c : b.counters()) {
        for (const frame::KindCount& k : c.kinds()) {
            if (std::ranges::find(p.own, k.kind) != p.own.end()) w.own += k.count.draws + k.count.dispatches;
            if (std::ranges::find(p.shared, k.kind) != p.shared.end())
                w.shared += k.count.triangles + k.count.dispatches;
        }
    }
    const shaders::FrameBlock& block = b.state().block;
    if (p.step != 0U) w.step = (block.layers.steps.x & p.step) != 0U;
    if (p.mode_bit != 0U) w.step = (block.info.x & p.mode_bit) == 0U;
    return w;
}

/// The parts the map's switches leave out, as the frame's counters name their work.
const std::vector<Part>& every_part() {
    static const std::vector<Part> parts{
        {.name = "land and roads", .bit = part::ground, .own = {"ground above", "ground land", "ground plate"}},
        {.name = "water", .bit = part::water, .own = {"ground water"}},
        {.name = "canopy", .bit = part::canopy, .own = {"ground canopy"}},
        // (a big road's lines go with its asphalt)
        {.name = "ground lines",
         .bit = part::ground_lines,
         .own = {"ground over lines"},
         .shared = {"ground marks"},
         .needs = part::ground},
        {.name = "cobblestones", .bit = part::setts, .shared = {"ground marks"}, .needs = part::ground},
        {.name = "canal names", .bit = part::ground_text, .own = {"canal names", "runway designators"}},
        {.name = "quay walls", .bit = part::quays, .own = {"ground quays"}},
        {.name = "sky",
         .bit = part::sky,
         .own = {"ground sky", "ground sun glow", "ground sun disc", "ground city glow"}},
        // (drawn sharp without their blur: a draw a tile)
        {.name = "building shadows",
         .bit = part::building_shadows,
         .own = {"shadow mask", "shadow grid", "shadow box", "shadow blur", "ground shadows"},
         .changes = part::shadow_blur},
        // (drawn sharp without it: the stencil's union)
        {.name = "soft shadows",
         .bit = part::shadow_blur,
         .own = {"shadow mask", "shadow grid", "shadow box", "shadow blur"},
         .needs = part::building_shadows},
        {.name = "park shadows", .bit = part::park_shadows, .own = {"ground woods' shadows"}},
        {.name = "cloud shadows", .bit = part::cloud_shadows, .step = shaders::COMPOSITE_CLOUD_SHADOWS},
        // (drawn as they are without the ripple)
        {.name = "reflections",
         .bit = part::reflections,
         .own = {"ground reflections", "ground ripple", "ripple layer", "ripple fused"},
         .needs = part::water,
         .changes = part::ripple},
        {.name = "ripple",
         .bit = part::ripple,
         .own = {"ground ripple", "ripple layer", "ripple fused"},
         .needs = part::water | part::reflections | part::water_streaks},
        {.name = "sun glitter", .bit = part::glitter, .own = {"ground glitter"}, .needs = part::water},
        {.name = "drizzle rings", .bit = part::rings, .own = {"ground rings"}, .needs = part::water},
        {.name = "wind glints", .bit = part::glints, .own = {"ground glints"}, .needs = part::water},
        // (drawn in the reflections' draws: told apart where the reflections are left out)
        {.name = "water streaks",
         .bit = part::water_streaks,
         .shared = {"ground reflections", "ground ripple", "ripple layer", "ripple fused"},
         .needs = part::water},
        {.name = "glowing streets",
         .bit = part::lit_streets,
         .own = {"lit glow layer", "lit glow box", "ground lit glow"}},
        {.name = "wet street shine", .bit = part::wet_cores, .own = {"lit cores layer", "ground lit cores"}},
        {.name = "lamp pools", .bit = part::lamp_pools, .own = {"ground lamp pools"}},
        {.name = "lamp halos", .bit = part::lamp_halos, .shared = {"thing glints"}, .needs = part::furniture},
        {.name = "lamp streaks", .bit = part::lamp_streaks, .shared = {"thing glints"}, .needs = part::furniture},
        {.name = "rain", .bit = part::rain, .step = shaders::COMPOSITE_RAIN},
        {.name = "golden wash", .bit = part::golden_wash, .step = shaders::COMPOSITE_WASH},
        // (drawn as their runs without the GPU's cull)
        {.name = "walls",
         .bit = part::walls,
         .own = {"walls", "near cells"},
         .changes = part::near_cells | part::kept_cull},
        {.name = "close-up walls",
         .bit = part::near_cells,
         .own = {"near cells"},
         .needs = part::walls,
         .changes = part::kept_cull},
        {.name = "roofs", .bit = part::roofs, .own = {"roofs"}, .changes = part::kept_cull},
        {.name = "GPU cull", .bit = part::kept_cull, .own = {"kept cull", "kept scan", "kept scatter"}},
        {.name = "roof edges", .bit = part::roof_edges, .own = {"roof strokes"}},
        {.name = "windows", .bit = part::windows, .own = {"windows"}, .shared = {"window frames", "window glass"}},
        {.name = "window depth", .bit = part::window_depth, .own = {"window parts"}, .needs = part::windows},
        {.name = "shopfronts and plinths",
         .bit = part::flat_detail,
         .own = {"window facades", "facades"},
         .shared = {"window frames", "window glass"}},
        {.name = "church windows", .bit = part::church_windows, .own = {"window churches", "church windows"}},
        {.name = "wall materials",
         .bit = part::material_faces,
         .own = {"window materials", "material fills", "material lines"}},
        {.name = "hoist beams", .bit = part::hoist_beams, .own = {"hoist beams"}},
        {.name = "tradition details", .bit = part::kit_details, .own = {"kit lines", "kit shadows", "roof lines"}},
        {.name = "doors",
         .bit = part::doors,
         .own = {"door faces", "door fills", "door outlines", "door numbers"},
         .changes = part::house_numbers},
        {.name = "house numbers", .bit = part::house_numbers, .own = {"door numbers"}, .needs = part::doors},
        {.name = "trees", .bit = part::trees, .own = {"trees", "tree fills", "tree sides"}},
        {.name = "leaf filters", .bit = part::leaf_filters, .mode_bit = shaders::FRAME_NO_CROWNS, .needs = part::trees},
        // (its translucent records the lamps' halos and wet streaks at night)
        {.name = "street furniture",
         .bit = part::furniture,
         .own = {"things", "thing fills", "thing glints"},
         .changes = part::lamp_halos | part::lamp_streaks},
        {.name = "towers and cranes",
         .bit = part::region_items,
         .own = {"items", "item faces", "item fills", "item parts", "item sides"}},
        {.name = "labels", .bit = part::labels, .own = {"label sprites"}},
        {.name = "routes", .bit = part::routes, .own = {"routes"}},
        {.name = "markers", .bit = part::markers, .own = {"markers"}},
        {.name = "location", .bit = part::location, .own = {"location"}},
        {.name = "frosted glass", .bit = part::frosted, .own = {"boxes", "glass"}},
    };
    return parts;
}

/// A label sprite of `width` x `height` px at one point (markers_vk_test.cpp's).
[[nodiscard]] labels::LabelSprite label_at(std::uint32_t width, std::uint32_t height, double lat, double lon) {
    labels::LabelSprite s;
    s.id = 1;
    s.priority = 1;
    s.measures = {.half = (static_cast<float>(width) / 2.0F) + 8.0F,
                  .pad = 8.0F,
                  .text_height = static_cast<float>(height),
                  .baseline = -4.0F};
    s.points = {{lat, lon}};
    s.own = {.width = width,
             .height = height,
             .pixels = std::vector<std::uint32_t>(std::size_t{width} * height, 0xFF00'00FFU),
             .anchor_x = static_cast<std::int32_t>(width / 2),
             .anchor_y = static_cast<std::int32_t>(height / 2)};
    return s;
}

} // namespace

TEST_CASE("a label's fade keeps the map's frames due at the same camera until it ends, from its first frame on, and a "
          "frame that leaves the labels out ends it: none stops part-faded when a gesture ends on its first frame") {
    const std::string tiles_dir = environment("ATLAS_TILES_DIR");
    if (tiles_dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the map is not drawn over the bundled packs here");
        return;
    }
    if (!test::open_session()) return; // no Vulkan here
    const Folder folder("label-fade");
    const fs::path& dir = folder.path();
    if (!can_start(dir)) return;
    auto started = gpu::start_guarded(start_in(dir));
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    frame::MapFrame map(2, nullptr, {.cache_dir = dir});
    standin::Session session(*started, {.cache_file = dir / "pipelines.bin", .spirv = 42});
    CHECK(map.set_packs(bundled(tiles_dir)) == "amsterdam listed\ngrachten listed\n");
    CHECK(session.map(&map).empty());
    std::string why;
    ATLAS_REQUIRE(session.attach(std::make_unique<test::OffscreenWindow>(), size, why), why);
    map.set_theme(sunny());
    const style::CameraState at_home = home(1);
    const auto labelled =
        labels::LabelSet::make({label_at(31, 13, at_home.latitude, at_home.longitude)}, 1, 0.0F, 0.0F);
    ATLAS_REQUIRE(labelled.has_value(), labels::describe(labelled.error()));
    map.set_labels(*labelled);
    // the labels' pass of the frames (the canal names may fade beside it: its own fade is what is held here)
    const auto labels_pass = [&]() -> const passes::Labels& {
        const auto all = map.builder()->passes();
        const auto it = std::ranges::find(all, std::string_view("labels"), [](const auto& p) { return p->name(); });
        REQUIRE(it != all.end());
        return static_cast<const passes::Labels&>(**it); // NOLINT(cppcoreguidelines-pro-type-static-cast-downcast)
    };
    const auto mid_fade = [&] {
        return std::ranges::count_if(labels_pass().drawn(),
                                     [](const labels::Drawn& d) { return d.alpha > 0.0F && d.alpha < 1.0F; });
    };

    // At rest at the home: the label placed whole, nothing due.
    ATLAS_REQUIRE(draw_until_complete(session, map, at_home) > 0, "the home never completed");
    ATLAS_REQUIRE(draw_until_still(session, map, at_home), "the home never came to rest");
    CHECK_FALSE(labels_pass().fading());
    CHECK(mid_fade() == 0);
    // Looking away (the parts case's row of doors): the label fades out over 250 ms where it stood, and from that first
    // frame on the frames stay due, whatever the tiles do; at rest it is gone whole.
    const double kx = 111320 * pymath::cos(pymath::radians(52.3746));
    const style::CameraState away = camera_at(52.3746 - (-192.11612399270084 / 110540.0),
                                              4.8840 + (82.21656426574002 / kx), 119.50786615381202, 60.0, 50.0);
    CHECK(session.draw(away, {}).drawn);
    CHECK(labels_pass().fading());
    CHECK(map.animating());
    ATLAS_REQUIRE(draw_until_still(session, map, away), "the row of doors never came to rest");
    CHECK_FALSE(labels_pass().fading());
    CHECK(mid_fade() == 0);
    // Home again: the label comes back in from none, due from its first frame on; and a frame that leaves the labels
    // out ends their fade at once (no frames are due for labels nobody sees).
    CHECK(session.draw(at_home, {}).drawn);
    CHECK(labels_pass().fading());
    CHECK(map.animating());
    style::CameraState without = at_home;
    without.parts |= part::labels;
    CHECK(session.draw(without, {}).drawn);
    CHECK_FALSE(labels_pass().fading());
}

TEST_CASE("the map leaves out each part its camera's parts name, every layer and effect, and draws them again: each "
          "part's work there by default and none with its switch, the rest drawn; and a group's switch") {
    const std::string tiles_dir = environment("ATLAS_TILES_DIR");
    if (tiles_dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the map is not drawn over the bundled packs here");
        return;
    }
    if (!test::open_session()) return; // no Vulkan here
    const Folder folder("parts");
    const fs::path& dir = folder.path();
    if (!can_start(dir)) return;
    auto started = gpu::start_guarded(start_in(dir));
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    frame::MapFrame map(2, nullptr, {.cache_dir = dir});
    standin::Session session(*started, {.cache_file = dir / "pipelines.bin", .spirv = 42});
    CHECK(map.set_packs(bundled(tiles_dir)) == "amsterdam listed\ngrachten listed\n");
    CHECK(session.map(&map).empty());
    std::string why;
    ATLAS_REQUIRE(session.attach(std::make_unique<test::OffscreenWindow>(), size, why), why);
    // the fonts of the names on the ground and the house numbers
    ATLAS_REQUIRE(text::shared_fonts().load_folder(ATLAS_FONTS_DIR).has_value());

    // What lies over the map: a label, a marker and a route about the home's target, where the user is, and a frosted
    // pill of the chrome.
    const style::CameraState at_home = home(1);
    const double kx = 111320 * pymath::cos(pymath::radians(52.3746));
    const auto labelled =
        labels::LabelSet::make({label_at(31, 13, at_home.latitude, at_home.longitude)}, 1, 0.0F, 0.0F);
    ATLAS_REQUIRE(labelled.has_value(), labels::describe(labelled.error()));
    map.set_labels(*labelled);
    const std::uint32_t white = 0xFFFF'FFFFU;
    const std::vector<markers::Marker> pins{{.latitude = at_home.latitude,
                                             .longitude = at_home.longitude + (30.0 / kx),
                                             .priority = 5,
                                             .style = markers::Style::pin,
                                             .look = 0,
                                             .look_left = 0}};
    const markers::Image look{.width = 12,
                              .height = 12,
                              .pixels = std::vector<std::uint32_t>(144, 0xFF20'40E0U),
                              .anchor_x = 6,
                              .anchor_y = 12};
    const auto marked = markers::MarkerSet::make(pins, {look}, {}, 1.0F, 1);
    ATLAS_REQUIRE(marked.has_value(), markers::describe(marked.error()));
    map.set_markers(*marked);
    routes::Route way{.width = 6.0F, .colour = 0xFFE0'6020U, .faded = 0xFFE0'6020U, .casing = white};
    for (int k = -5; k <= 5; ++k) {
        way.points.push_back({at_home.latitude, at_home.longitude + ((10.0 * k) / kx), (10.0 * k) + 50.0});
    }
    const auto routed = routes::RouteSet::make({}, {way}, {}, 1.0F, 1);
    ATLAS_REQUIRE(routed.has_value(), routes::describe(routed.error()));
    map.set_routes(*routed);
    const auto over = [&](style::CameraState c) {
        c.flags |= style::camera_flag::location;
        c.location = {.latitude = at_home.latitude, .longitude = at_home.longitude, .accuracy = 12.0F};
        c.keep_out_count = 1;
        c.keep_out[0] = style::KeepOut{.left = 16.0F,
                                       .top = 40.0F,
                                       .right = 164.0F,
                                       .bottom = 80.0F,
                                       .radius = 20.0F,
                                       .blur16 = 24 * 16,
                                       .saturation = 180,
                                       .flags = 1};
        return c;
    };

    // The scenes: each draws its parts by default (with `base` left out throughout: the water's streaks are told from
    // the reflections they draw with at night).
    struct Scene {
        const char* name;
        style::CameraState camera;
        style::ThemeBlock theme;
        std::uint64_t base = 0;
    };
    std::vector<Scene> scenes{
        {.name = "the Westerkerk home on a sunny day", .camera = over(at_home), .theme = sunny()},
        // the Westerkerk's densest row of doors from 50 m (doors_vk_test.cpp's): doors, their numbers, deep windows
        {.name = "the row of doors",
         .camera = camera_at(52.3746 - (-192.11612399270084 / 110540.0), 4.8840 + (82.21656426574002 / kx),
                             119.50786615381202, 60.0, 50.0),
         .theme = sunny()},
        // over the Prinsengracht toward the sun (glitter_vk_test.cpp's)
        {.name = "the Prinsengracht toward the sun",
         .camera = camera_at(52.3756, 4.8846 + (40.0 / kx), 213.5, 70.0, 260.0),
         .theme = sunny()},
        {.name = "the home on a wet night", .camera = at_home, .theme = wet_night(), .base = part::reflections},
        // the street lamps near enough to be drawn (from 1.6 px of focal length over their depth): their halos and
        // their wet streaks
        {.name = "the Prinsengracht on a wet night",
         .camera = camera_at(52.3756, 4.8846 + (40.0 / kx), 213.5, 70.0, 260.0),
         .theme = wet_night(),
         .base = part::reflections},
        {.name = "the home at the golden moment", .camera = at_home, .theme = golden()},
        // the canal ring's Main board (its towers, masts and power lines)
        {.name = "the canal ring's Main board",
         .camera = camera_at(52.3746, 4.884, 52.0, 46.0, 2300.0, 2),
         .theme = sunny()},
    };
    const std::vector<Part>& parts = every_part();
    std::vector<bool> drawn(parts.size(), false); // drawn by default in some scene
    const auto draw = [&](const Scene& scene, std::uint64_t off) {
        style::CameraState c = scene.camera;
        c.parts = scene.base | off;
        return session.draw(c, {}).drawn;
    };
    // toward the sun: the first bearing whose picture holds the point of the water that mirrors it (glitter_vk_test's)
    map.set_theme(sunny());
    const Part& glitter = *std::ranges::find(parts, part::glitter, &Part::bit);
    for (const double bearing : {213.5, 146.5, 33.5, 326.5}) {
        scenes[2].camera.bearing = bearing;
        ATLAS_REQUIRE(draw_until_complete(session, map, scenes[2].camera) > 0, "the Prinsengracht never completed");
        if (work_of(*map.builder(), glitter).own > 0) break;
    }
    for (const Scene& scene : scenes) {
        const std::string_view scene_name = scene.name;
        CAPTURE(scene_name);
        map.set_theme(scene.theme);
        style::CameraState c = scene.camera;
        c.parts = scene.base;
        ATLAS_REQUIRE(draw_until_complete(session, map, c) > 0, std::format("{} never completed", scene.name));
        // at rest before the reference frame: the scene before may have left a label fading out where it stood
        ATLAS_REQUIRE(draw_until_still(session, map, c), std::format("{} never came to rest", scene.name));
        CHECK(draw(scene, 0));
        std::vector<Work> on;
        on.reserve(parts.size());
        for (const Part& p : parts) on.push_back(work_of(*map.builder(), p));
        // each part left out in turn: every part's work then, and in the frame right after it, with everything drawn
        // again (a stage that keeps its result while its inputs stand changes the counts from one frame to another,
        // but not between these two), and the tiles each of the two drew (the cover may take one in or let one go at
        // rest, a frame's fills and lines more or fewer)
        std::vector<std::vector<Work>> with_off(parts.size());
        std::vector<std::vector<Work>> again(parts.size());
        std::vector<bool> same_tiles(parts.size(), false);
        for (std::size_t i = 0; i < parts.size(); ++i) {
            if ((parts[i].bit & scene.base) != 0U || !on[i].any()) continue;
            CHECK(draw(scene, parts[i].bit));
            with_off[i].reserve(parts.size());
            for (const Part& q : parts) with_off[i].push_back(work_of(*map.builder(), q));
            const std::vector<std::uint64_t> tiles = drawn_tiles(map);
            CHECK(draw(scene, 0));
            again[i].reserve(parts.size());
            for (const Part& q : parts) again[i].push_back(work_of(*map.builder(), q));
            same_tiles[i] = tiles == drawn_tiles(map);
        }
        // drawn here: work of its own, its step, or less of what it shares once it is left out
        const auto present = [&](std::size_t i) {
            return !with_off[i].empty() && (on[i].own > 0 || on[i].step || with_off[i][i].shared < on[i].shared);
        };
        std::string lines;
        std::size_t cover_moved = 0; // the switches whose two frames drew other tiles
        for (std::size_t i = 0; i < parts.size(); ++i) {
            if (!present(i)) continue;
            const Part& p = parts[i];
            drawn[i] = true;
            const std::string_view part_name = p.name;
            CAPTURE(part_name);
            // left out: none of its own work, none more of what it shares, its step off; and drawn again
            const Work& off = with_off[i][i];
            CHECK(off.own == 0U);
            CHECK(off.step == false);
            CHECK(off.shared <= on[i].shared);
            CHECK(again[i][i].any());
            // every other part drawn here drawn as with it (its own draws and dispatches, its step), but those drawn
            // with it
            for (std::size_t j = 0; j < parts.size(); ++j) {
                const Part& q = parts[j];
                if (j == i || !present(j) || (q.needs & p.bit) != 0U) continue;
                const std::string_view other = q.name;
                CAPTURE(other);
                CHECK(with_off[i][j].any());
                if (same_tiles[i] && (q.changes & p.bit) == 0U) CHECK(with_off[i][j].own == again[i][j].own);
                CHECK(with_off[i][j].step == again[i][j].step);
            }
            if (!same_tiles[i]) ++cover_moved;
            lines += std::format("\n  {}: {} draws and dispatches, {} shared{}; with its switch {}, {}", p.name,
                                 on[i].own, on[i].shared, on[i].step ? ", its step" : "", off.own, off.shared);
        }
        MESSAGE(std::format("{} ({} of its switches' two frames drew other tiles):{}", scene.name, cover_moved, lines));
    }
    // Every part the bundled packs show is drawn in one of the scenes; the rest are not in them (no theme park's woods
    // or tracks, no tradition's kit in the Netherlands, no glass or metal wall within reach of these views).
    std::string none;
    for (std::size_t i = 0; i < parts.size(); ++i) {
        const std::uint64_t b = parts[i].bit;
        const bool elsewhere = b == part::park_shadows || b == part::kit_details || b == part::material_faces ||
                               b == part::canopy || b == part::ground_text;
        if (!drawn[i] && !elsewhere) {
            const std::string_view part_name = parts[i].name;
            CAPTURE(part_name);
            CHECK(drawn[i]);
        }
        if (!drawn[i]) none += std::format(" {};", parts[i].name);
    }
    MESSAGE(std::format("not drawn in these scenes:{}", none));

    // A group's switch: the facades' detail and the water's light together, and all of the buildings; the trees, the
    // ground and the water the scene draws drawn throughout.
    map.set_theme(sunny());
    const Scene& doors = scenes[1];
    // what the scene draws of them with nothing left out (no tree stands in the row of doors' view: the ranks keep only
    // what the frame can see, so none behind the camera draws)
    CHECK(draw(doors, 0));
    std::vector<std::uint64_t> drawn_there;
    for (const std::uint64_t kept : {part::trees, part::ground, part::water}) {
        if (work_of(*map.builder(), *std::ranges::find(parts, kept, &Part::bit)).any()) drawn_there.push_back(kept);
    }
    CHECK(drawn_there.size() >= 2);
    for (const std::uint64_t group : {part::facade_detail | part::water_light, part::buildings | part::doors}) {
        CHECK(draw(doors, group));
        for (const Part& p : parts) {
            if ((p.bit & group) == 0U) continue;
            const std::string_view part_name = p.name;
            CAPTURE(part_name);
            CHECK(work_of(*map.builder(), p).own == 0U);
        }
        for (const std::uint64_t kept : drawn_there) {
            const auto it = std::ranges::find(parts, kept, &Part::bit);
            const std::string_view kept_name = it->name;
            CAPTURE(kept_name);
            CHECK(work_of(*map.builder(), *it).any());
        }
    }
    // every building left out: their items take no rank (the frame's ranks hold no building), and come back
    CHECK(draw(doors, part::buildings));
    const std::size_t ranked_without = map.builder()->state().ranks->order().size();
    CHECK(draw(doors, 0));
    const std::size_t ranked_with = map.builder()->state().ranks->order().size();
    CHECK(ranked_without < ranked_with);
    MESSAGE(
        std::format("the row of doors' ranks: {} items, {} with the buildings left out", ranked_with, ranked_without));
    session.close();
}

namespace {

/// A window whose frames the test reads back (the look tool's KeptWindow): offscreen images it keeps hold of.
class KeptWindow final : public standin::SessionWindow {
public:
    [[nodiscard]] gpu::Result<vk::raii::SurfaceKHR> surface(const gpu::Instance& /*instance*/) override {
        return vk::raii::SurfaceKHR{nullptr};
    }
    [[nodiscard]] gpu::Result<std::unique_ptr<gpu::Output>> output(const gpu::Device& device,
                                                                   vk::SurfaceKHR /*surface*/, vk::Extent2D wanted,
                                                                   const gpu::Output* /*old*/,
                                                                   gpu::Depth depth) override {
        auto images = gpu::Offscreen::create(device, wanted, 3, depth);
        if (!images) return std::unexpected(images.error());
        kept = images->get();
        return std::unique_ptr<gpu::Output>(std::move(*images));
    }
    gpu::Offscreen* kept = nullptr;
};

/// The ground (y 0) under upright device point (x, y) of camera `c` at `density`, as passes::water_map's ray finds it;
/// none at the horizon or above.
[[nodiscard]] std::optional<view::Vec3> ground_under(const view::Camera& c, double density, double x, double y) {
    const double u = ((x / density) - c.cx()) / c.focal();
    const double v = ((y / density) - c.cy()) / c.focal();
    const view::Vec3& f = c.forward();
    const view::Vec3& r = c.right();
    const view::Vec3& up = c.up();
    const view::Vec3 d{f.x + (r.x * u) - (up.x * v), f.y + (r.y * u) - (up.y * v), f.z + (r.z * u) - (up.z * v)};
    if (!(d.y < 0)) return std::nullopt;
    const double t = -c.eye().y / d.y;
    return view::Vec3{c.eye().x + (t * d.x), 0.0, c.eye().z + (t * d.z)};
}

/// The upright device point where camera `c` at `density` sees `p`; none behind it.
[[nodiscard]] std::optional<std::array<double, 2>> device_point(const view::Camera& c, double density,
                                                                const view::Vec3& p) {
    const view::Vec3 w{p.x - c.eye().x, p.y - c.eye().y, p.z - c.eye().z};
    const auto dot = [&w](const view::Vec3& a) { return (w.x * a.x) + (w.y * a.y) + (w.z * a.z); };
    const double z = dot(c.forward());
    if (!(z > 0)) return std::nullopt;
    return std::array<double, 2>{(c.cx() + (c.focal() * dot(c.right()) / z)) * density,
                                 (c.cy() - (c.focal() * dot(c.up()) / z)) * density};
}

} // namespace

TEST_CASE("the clouds' shadows of the app's map keep their places on the ground while it pans: where the panned frame "
          "shows a point of the ground, the same shadow lies over it") {
    const std::string tiles_dir = environment("ATLAS_TILES_DIR");
    if (tiles_dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the map is not drawn over the bundled packs here");
        return;
    }
    if (!test::open_session()) return; // no Vulkan here
    const Folder folder("clouds");
    const fs::path& dir = folder.path();
    if (!can_start(dir)) return;
    auto started = gpu::start_guarded(start_in(dir));
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    frame::MapFrame map(2, nullptr, {.cache_dir = dir});
    standin::Session session(*started, {.cache_file = dir / "pipelines.bin", .spirv = 42});
    CHECK(map.set_packs(bundled(tiles_dir)) == "amsterdam listed\ngrachten listed\n");
    // the clouds' step alone over the scene: no haze, vignette or grain after it
    map.set_screen(false, false, false);
    CHECK(session.map(&map).empty());
    auto window = std::make_unique<KeptWindow>();
    KeptWindow* kept = window.get();
    std::string why;
    ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
    map.set_theme(sunny());

    // A camera's frame with its clouds' shadows and without them, the camera the frame was drawn with.
    struct Shot {
        gpu::Pixels with;
        gpu::Pixels without;
        std::optional<view::Camera> camera;
    };
    const auto shot = [&](const style::CameraState& c) -> Shot {
        Shot out;
        CHECK(draw_until_complete(session, map, c) >= 0);
        for (const bool clouds : {true, false}) {
            style::CameraState k = c;
            if (!clouds) k.parts |= style::camera_part::cloud_shadows;
            CHECK(session.draw(k, {}).drawn);
            // the app's map reads its clouds on the ground, where it draws them
            CHECK(((map.builder()->state().block.layers.steps.x & shaders::COMPOSITE_CLOUD_GROUND) != 0U) == clouds);
            if (kept->kept == nullptr) return out;
            auto pixels = kept->kept->read(kept->kept->latest());
            CHECK(pixels.has_value());
            if (!pixels) return out;
            (clouds ? out.with : out.without) = std::move(*pixels);
        }
        out.camera = map.builder()->state().view->camera();
        return out;
    };
    const style::CameraState a = home(1);
    style::CameraState b = a;
    // 40 m north and 25 m east of the home's target, as a pan carries the map
    b.latitude += 40.0 / 110540.0;
    b.longitude += 25.0 / (111320 * pymath::cos(pymath::radians(52.3746)));
    const Shot sa = shot(a);
    const Shot sb = shot(b);
    ATLAS_REQUIRE(sa.camera && sb.camera && !sa.without.rgba.empty() && !sb.without.rgba.empty(),
                  "a frame was not read back");
    // the shadow over a pixel: what the clouds' step left of its brightest channel
    const auto shadow = [](const Shot& s, std::uint32_t x, std::uint32_t y) -> std::optional<double> {
        const std::size_t at = ((std::size_t{y} * s.with.width) + x) * 4;
        double lit = 0;
        double shaded = 0;
        for (std::size_t k = 0; k < 3; ++k) {
            if (s.without.rgba[at + k] > lit) {
                lit = s.without.rgba[at + k];
                shaded = s.with.rgba[at + k];
            }
        }
        if (lit < 60) return std::nullopt; // too dark to tell
        return shaded / lit;
    };
    const double density = 1.0;
    std::size_t compared = 0;
    std::size_t kept_place = 0;
    std::size_t shaded = 0;
    for (std::uint32_t y = 0; y < size.height; y += 5) {
        for (std::uint32_t x = 0; x < size.width; x += 5) {
            const auto g = ground_under(*sa.camera, density, x + 0.5, y + 0.5);
            if (!g) continue;
            const auto p = device_point(*sb.camera, density, *g);
            if (!p || (*p)[0] < 0 || (*p)[1] < 0 || (*p)[0] >= size.width || (*p)[1] >= size.height) continue;
            const auto there = shadow(sb, static_cast<std::uint32_t>((*p)[0]), static_cast<std::uint32_t>((*p)[1]));
            const auto here = shadow(sa, x, y);
            if (!here || !there) continue;
            ++compared;
            if (*here < 0.9) ++shaded;
            if (std::abs(*here - *there) < 0.06) ++kept_place;
        }
    }
    MESSAGE(std::format("{} points of the ground in both frames, {} under a shadow; {} with the same shadow after the "
                        "pan",
                        compared, shaded, kept_place));
    CHECK(compared > 400);
    CHECK(shaded > compared / 20); // the clouds do shade the home
    CHECK(kept_place >= compared * 9 / 10);
    session.close();
}

#endif
