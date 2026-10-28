// The looks side by side as the phone draws them (a tool, run only with ATLAS_LOOK_OUT set): the windows of the canal
// ring around a place, built anywhere's way from its raw.json (ATLAS_REGIONS_DIR) once with the Dutch kit (the windows'
// look until 27 October) and once with the renders' New looks (WindowPlace::new_looks), each drawn by the app's own map
// frame through the session at the sunny moment, written as <out>/<name>_kit.ppm and <out>/<name>_new.ppm.
#include "base/pymath/PyMath.hpp"
#include "frame/MapFrame.hpp"
#include "gpu/Guard.hpp"
#include "gpu/Offscreen.hpp"
#include "load/Countries.hpp"
#include "load/Window.hpp"
#include "osm/Overpass.hpp"
#include "standin/Session.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/gpu/session_fixture.hpp"
#include "tests/load/query.hpp"
#include "tiles/Grid.hpp"

#include <doctest/doctest.h>

#include <chrono>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <fstream>
#include <memory>
#include <string>
#include <unistd.h>
#include <vector>

#if !defined(__ANDROID__)

using namespace atlas;

namespace {

namespace fs = std::filesystem;

[[nodiscard]] std::string environment(const char* name) {
    const char* v = std::getenv(name); // NOLINT(concurrency-mt-unsafe): read before the test's threads
    return v == nullptr ? std::string() : std::string(v);
}

/// An offscreen window that keeps its images, to read the frames back.
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

/// amsterdam.py's sunny moment of 1 October 2026, 16:00 (tests/frame/map_frame_vk_test.cpp's).
[[nodiscard]] style::ThemeBlock sunny() {
    style::ThemeBlock b{};
    b.version = 1;
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

constexpr vk::Extent2D size{.width = 720, .height = 1600};
constexpr double density = 1.75;

/// The camera over (`lat`, `lon`): the map's bearing and pitch (degrees from straight down), `distance` metres.
[[nodiscard]] style::CameraState camera(double lat, double lon, double bearing, double pitch, double distance) {
    style::CameraState c{};
    c.version = style::camera_state_version;
    c.latitude = lat;
    c.longitude = lon;
    c.bearing = bearing;
    c.pitch = pitch;
    c.distance = distance;
    c.principal_x = size.width / density / 2.0;
    c.principal_y = size.height / density * 0.48;
    c.density = density;
    c.width_px = size.width;
    c.height_px = size.height;
    c.width_dp = static_cast<std::uint32_t>(std::lround(size.width / density));
    c.height_dp = static_cast<std::uint32_t>(std::lround(size.height / density));
    c.flags = style::camera_flag::rest;
    c.pack_id = 0;
    return c;
}

} // namespace

TEST_CASE("looks: the canal ring's windows in the Dutch kit and in the New looks, as the app draws them") {
    const std::string out = environment("ATLAS_LOOK_OUT");
    const std::string regions = environment("ATLAS_REGIONS_DIR");
    if (out.empty() || regions.empty()) {
        MESSAGE("ATLAS_LOOK_OUT or ATLAS_REGIONS_DIR is not set: the looks are not drawn here");
        return;
    }
    const std::string name = environment("ATLAS_LOOK_NAME").empty() ? "damrak" : environment("ATLAS_LOOK_NAME");
    const double lat = environment("ATLAS_LOOK_LAT").empty() ? 52.3752 : std::stod(environment("ATLAS_LOOK_LAT"));
    const double lon = environment("ATLAS_LOOK_LON").empty() ? 4.8955 : std::stod(environment("ATLAS_LOOK_LON"));
    std::ifstream in(fs::path(regions) / "grachten" / "raw.json", std::ios::binary);
    const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    ATLAS_REQUIRE(!text.empty());
    const auto doc = osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
    ATLAS_REQUIRE(doc.has_value());
    const tiles::TileKey centre = tiles::tile_of(14, static_cast<std::int32_t>(std::lround(lat * 1e7)),
                                                 static_cast<std::int32_t>(std::lround(lon * 1e7)));
    if (!test::open_session()) return;
    for (const bool kit : {true, false}) {
        const fs::path folder = fs::path(out) / std::format("windows-{}", kit ? "kit" : "new");
        fs::create_directories(folder);
        std::vector<frame::MapPack> packs;
        std::uint64_t number = 0;
        for (std::uint32_t dy = 0; dy < 3; ++dy) {
            for (std::uint32_t dx = 0; dx < 3; ++dx) {
                const tiles::TileKey k(14, centre.x - 1 + dx, centre.y - 1 + dy);
                load::WindowPlace place = load::place_of(k);
                if (kit) {
                    place.new_looks = false;
                    place.tradition = load::tradition_of(place.country);
                }
                const auto built =
                    load::build_window(test::query(*doc, load::window_box(k)), tiles::Sha256Digest{}, k, place);
                ATLAS_REQUIRE(built.has_value(), built.error().what);
                const fs::path file = folder / (std::to_string(k.x) + "-" + std::to_string(k.y) + ".atlas");
                std::ofstream o(file, std::ios::binary | std::ios::trunc);
                o.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
                        static_cast<std::streamsize>(built->pack.size()));
                frame::MapPack p;
                p.number = ++number;
                p.ref.id = "window/" + place.name;
                p.ref.kind = tiles::PackKind::window;
                p.ref.path = file.string();
                packs.push_back(std::move(p));
            }
        }
        const fs::path guard = fs::temp_directory_path() / std::format("atlas-look-{}", ::getpid());
        fs::create_directories(guard);
        auto started = gpu::start_guarded({.guard_dir = guard,
                                           .cache_file = guard / "pipelines.bin",
                                           .system = "host-test-system",
                                           .build = "look-test",
                                           .present = true,
                                           .spirv = 42});
        ATLAS_REQUIRE(started.has_value(), started.error().reason);
        {
            frame::MapFrame map(2, [] {}, {.cache_dir = guard});
            standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
            (void)map.set_packs(packs);
            CHECK(session.map(&map).empty());
            auto window = std::make_unique<KeptWindow>();
            KeptWindow* kept = window.get();
            std::string why;
            ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
            map.set_theme(sunny());
            const style::CameraState cam = camera(lat, lon, 22.0, 48.0, 700.0);
            const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(240);
            int frames = 0;
            while (std::chrono::steady_clock::now() < until) {
                const standin::Drawn d = session.draw(cam, {});
                if (d.stale) session.remake();
                frames += d.drawn ? 1 : 0;
                if (map.complete() && frames > 4) break;
                if (!map.animating() && map.world() != nullptr) map.world()->residency().wait_idle();
            }
            MESSAGE(std::format("{}: {} frames, complete {}, pack {}", kit ? "kit" : "new", frames, map.complete(),
                                map.drawn_pack()));
            ATLAS_REQUIRE(kept != nullptr && kept->kept != nullptr);
            auto pixels = kept->kept->read(kept->kept->latest());
            ATLAS_REQUIRE(pixels.has_value(), pixels.error().text());
            CHECK(gpu::write_ppm(*pixels,
                                 (fs::path(out) / std::format("{}_{}.ppm", name, kit ? "kit" : "new")).string()));
            // ATLAS_LOOK_COST: each look's frame at rest from 700 m in to 90 m (draws, triangles, fragments, the GPU's
            // time), what the phone's GPU is asked as the map zooms in; the nearest written as <name>_<look>_near.ppm
            if (!environment("ATLAS_LOOK_COST").empty()) {
                for (const double d : {700.0, 450.0, 300.0, 200.0, 130.0, 90.0}) {
                    const style::CameraState at = camera(lat, lon, 22.0, 48.0, d);
                    const auto by = std::chrono::steady_clock::now() + std::chrono::seconds(240);
                    int n = 0;
                    while (std::chrono::steady_clock::now() < by) {
                        const standin::Drawn s = session.draw(at, {});
                        if (s.stale) session.remake();
                        n += s.drawn ? 1 : 0;
                        if (map.complete() && n > 4) break;
                        if (!map.animating() && map.world() != nullptr) map.world()->residency().wait_idle();
                    }
                    // the GPU's times are an earlier frame's: three more at rest, the last one's read
                    standin::FrameRecord r{};
                    for (int k = 0; k < 3; ++k) {
                        const standin::Drawn s = session.draw(at, {});
                        if (s.drawn) r = s.record;
                    }
                    MESSAGE(std::format("cost {} {:>4.0f} m: {} draws, {}k triangles, {}k fragments, gpu {:.1f} ms "
                                        "(main {:.1f}, offscreen {:.1f}, after {:.1f}), record {:.1f} ms",
                                        kit ? "kit" : "new", d, r.draws, r.triangles_k, r.fragments_k, r.gpu_ms,
                                        r.gpu_main_ms, r.gpu_offscreen_ms, r.gpu_after_ms, r.record_ms));
                }
                auto near = kept->kept->read(kept->kept->latest());
                ATLAS_REQUIRE(near.has_value(), near.error().text());
                CHECK(gpu::write_ppm(
                    *near, (fs::path(out) / std::format("{}_{}_near.ppm", name, kit ? "kit" : "new")).string()));
            }
        }
        std::error_code ec;
        fs::remove_all(guard, ec);
    }
}

TEST_CASE("looks: the canal ring zoomed out, as the app draws its bundled pack") {
    // the bundled canal ring (ATLAS_TILES_DIR's grachten, named as the app names it) from 1.5, 3 and 6 km, written as
    // <out>/ring_<distance>.ppm with the frame's light in the log: what the map shows zoomed out over Amsterdam
    const std::string out = environment("ATLAS_LOOK_OUT");
    const std::string tiles_dir = environment("ATLAS_TILES_DIR");
    if (out.empty() || tiles_dir.empty()) {
        MESSAGE("ATLAS_LOOK_OUT or ATLAS_TILES_DIR is not set: the canal ring is not drawn here");
        return;
    }
    if (!test::open_session()) return;
    std::vector<frame::MapPack> packs;
    std::uint64_t number = 0;
    for (const char* id : {"amsterdam", "grachten"}) {
        frame::MapPack p;
        p.number = ++number;
        p.ref.id = id;
        p.ref.kind = tiles::PackKind::bundled;
        p.ref.path = (fs::path(tiles_dir) / (std::string(id) + ".atlas")).string();
        packs.push_back(std::move(p));
    }
    const fs::path guard = fs::temp_directory_path() / std::format("atlas-ring-{}", ::getpid());
    fs::create_directories(guard);
    auto started = gpu::start_guarded({.guard_dir = guard,
                                       .cache_file = guard / "pipelines.bin",
                                       .system = "host-test-system",
                                       .build = "look-test",
                                       .present = true,
                                       .spirv = 42});
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    {
        frame::MapFrame map(2, [] {}, {.cache_dir = guard});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        (void)map.set_packs(packs);
        CHECK(session.map(&map).empty());
        auto window = std::make_unique<KeptWindow>();
        KeptWindow* kept = window.get();
        std::string why;
        ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
        map.set_theme(sunny());
        for (const double d : {1500.0, 3000.0, 6000.0}) {
            style::CameraState cam = camera(52.3760, 4.8920, 22.0, 40.0, d);
            cam.pack_id = 2;
            const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(240);
            int frames = 0;
            while (std::chrono::steady_clock::now() < until) {
                const standin::Drawn s = session.draw(cam, {});
                if (s.stale) session.remake();
                frames += s.drawn ? 1 : 0;
                if (map.complete() && frames > 4) break;
                if (!map.animating() && map.world() != nullptr) map.world()->residency().wait_idle();
            }
            MESSAGE(std::format("ring {:.0f} m: {} frames, complete {}, pack {}, tone {}", d, frames, map.complete(),
                                map.drawn_pack(), (map.builder()->state().theme.flags & style::theme_flag::tone) != 0));
            ATLAS_REQUIRE(kept->kept != nullptr);
            auto pixels = kept->kept->read(kept->kept->latest());
            ATLAS_REQUIRE(pixels.has_value(), pixels.error().text());
            CHECK(gpu::write_ppm(*pixels, (fs::path(out) / std::format("ring_{:.0f}.ppm", d)).string()));
        }
        // ATLAS_LOOK_COST: the bundled Westerkerk (pack 1) at the camera the windows' table takes, from 700 m in to
        // 90 m, each frame at rest as that table prints it
        if (!environment("ATLAS_LOOK_COST").empty()) {
            for (const double d : {700.0, 450.0, 300.0, 200.0, 130.0, 90.0}) {
                style::CameraState at = camera(52.3746, 4.8840, 22.0, 48.0, d);
                at.pack_id = 1;
                const auto by = std::chrono::steady_clock::now() + std::chrono::seconds(240);
                int n = 0;
                while (std::chrono::steady_clock::now() < by) {
                    const standin::Drawn s = session.draw(at, {});
                    if (s.stale) session.remake();
                    n += s.drawn ? 1 : 0;
                    if (map.complete() && n > 4) break;
                    if (!map.animating() && map.world() != nullptr) map.world()->residency().wait_idle();
                }
                standin::FrameRecord r{};
                for (int k = 0; k < 3; ++k) {
                    const standin::Drawn s = session.draw(at, {});
                    if (s.drawn) r = s.record;
                }
                MESSAGE(std::format("cost {} {:>4.0f} m: {} draws, {}k triangles, {}k fragments, gpu {:.1f} ms "
                                    "(main {:.1f}, offscreen {:.1f}, after {:.1f}), record {:.1f} ms",
                                    map.drawn_pack(), d, r.draws, r.triangles_k, r.fragments_k, r.gpu_ms, r.gpu_main_ms,
                                    r.gpu_offscreen_ms, r.gpu_after_ms, r.record_ms));
            }
            auto near = kept->kept->read(kept->kept->latest());
            ATLAS_REQUIRE(near.has_value(), near.error().text());
            CHECK(gpu::write_ppm(*near, (fs::path(out) / "westerkerk_bundled_near.ppm").string()));
        }
    }
    std::error_code ec;
    fs::remove_all(guard, ec);
}

TEST_CASE("looks: a pinch in from the canal ring to the street, as the app names each frame's pack") {
    // ATLAS_LOOK_ZOOM: the bundled packs (ATLAS_TILES_DIR) and the New looks' windows the first case built (in
    // ATLAS_LOOK_OUT/windows-new), the camera going from 3 km in to 100 m over 90 frames without rest, as a pinch does,
    // each frame's pack named as the app's PackList names it (of the packs whose box holds the target, the one whose
    // views fit the distance best, the smaller box on a tie), then at rest; each frame's pack, completeness and cost
    // logged, and every sixth frame written as zoom_<n>.ppm
    const std::string out = environment("ATLAS_LOOK_OUT");
    const std::string tiles_dir = environment("ATLAS_TILES_DIR");
    if (out.empty() || tiles_dir.empty() || environment("ATLAS_LOOK_ZOOM").empty()) {
        MESSAGE("ATLAS_LOOK_ZOOM, ATLAS_LOOK_OUT or ATLAS_TILES_DIR is not set: no pinch drawn here");
        return;
    }
    if (!test::open_session()) return;
    struct Listed {
        frame::MapPack pack;
        double south, west, north, east;
        std::vector<double> distances;
    };
    std::vector<Listed> listed;
    std::uint64_t number = 0;
    // amsterdam: the living map (its view 430 m); grachten: its views' distances; a window: no views, the living one's
    const auto add = [&](std::string id, tiles::PackKind kind, const fs::path& file, std::array<double, 4> box,
                         std::vector<double> distances) {
        frame::MapPack p;
        p.number = ++number;
        p.ref.id = std::move(id);
        p.ref.kind = kind;
        p.ref.path = file.string();
        listed.push_back({.pack = std::move(p),
                          .south = box[0],
                          .west = box[1],
                          .north = box[2],
                          .east = box[3],
                          .distances = std::move(distances)});
    };
    add("amsterdam", tiles::PackKind::bundled, fs::path(tiles_dir) / "amsterdam.atlas", {52.371, 4.878, 52.378, 4.891},
        {430.0});
    add("grachten", tiles::PackKind::bundled, fs::path(tiles_dir) / "grachten.atlas", {52.36, 4.872, 52.393, 4.922},
        {2700.0, 2300.0, 1600.0});
    for (const auto& e : fs::directory_iterator(fs::path(out) / "windows-new")) {
        if (e.path().extension() != ".atlas") continue;
        const auto [x, y] = [&] {
            const std::string stem = e.path().stem().string();
            const auto dash = stem.find('-');
            return std::pair{static_cast<std::uint32_t>(std::stoul(stem.substr(0, dash))),
                             static_cast<std::uint32_t>(std::stoul(stem.substr(dash + 1)))};
        }();
        const std::array<std::int32_t, 4> b = tiles::tile_box(tiles::TileKey(14, x, y));
        add("window/" + e.path().stem().string(), tiles::PackKind::window, e.path(),
            {b[0] / 1e7, b[1] / 1e7, b[2] / 1e7, b[3] / 1e7}, {430.0});
    }
    const auto choose = [&](double lat, double lon, double distance) -> std::uint64_t {
        const Listed* best = nullptr;
        double best_fit = 1e300;
        for (const Listed& l : listed) {
            if (lat < l.south || lat > l.north || lon < l.west || lon > l.east) continue;
            double fit = 1e300;
            for (const double v : l.distances) {
                fit = std::min(fit, std::fabs(pymath::strict::log(std::max(distance, 1.0) / v)));
            }
            const bool tie = std::fabs(fit - best_fit) <= 1e-9;
            const auto area = [](const Listed& a) { return (a.north - a.south) * (a.east - a.west); };
            if (best == nullptr || (!tie && fit < best_fit) || (tie && area(l) < area(*best))) {
                best = &l;
                best_fit = std::min(fit, best_fit);
            }
        }
        return best == nullptr ? 0 : best->pack.number;
    };
    std::vector<frame::MapPack> packs;
    packs.reserve(listed.size());
    for (const Listed& l : listed) packs.push_back(l.pack);
    const fs::path guard = fs::temp_directory_path() / std::format("atlas-zoom-{}", ::getpid());
    fs::create_directories(guard);
    auto started = gpu::start_guarded({.guard_dir = guard,
                                       .cache_file = guard / "pipelines.bin",
                                       .system = "host-test-system",
                                       .build = "look-test",
                                       .present = true,
                                       .spirv = 42});
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    {
        frame::MapFrame map(2, [] {}, {.cache_dir = guard});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        MESSAGE(map.set_packs(packs));
        CHECK(session.map(&map).empty());
        auto window = std::make_unique<KeptWindow>();
        KeptWindow* kept = window.get();
        std::string why;
        ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
        map.set_theme(sunny());
        // west of the bundled Westerkerk, among the windows: 52.3735 N, 4.8755 E
        const double lat = 52.3735;
        const double lon = 4.8755;
        // the start at rest, complete
        {
            style::CameraState c = camera(lat, lon, 22.0, 48.0, 3000.0);
            c.pack_id = choose(lat, lon, 3000.0);
            const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(240);
            int n = 0;
            while (std::chrono::steady_clock::now() < until) {
                const standin::Drawn s = session.draw(c, {});
                if (s.stale) session.remake();
                n += s.drawn ? 1 : 0;
                if (map.complete() && n > 4) break;
                if (!map.animating() && map.world() != nullptr) map.world()->residency().wait_idle();
            }
        }
        constexpr int steps = 90;
        for (int i = 0; i <= steps + 30; ++i) {
            const double t = std::min(1.0, static_cast<double>(i) / steps);
            const double d = 3000.0 * pymath::strict::pow(100.0 / 3000.0, t);
            style::CameraState c = camera(lat, lon, 22.0, 48.0, d);
            c.pack_id = choose(lat, lon, d);
            if (i < steps) c.flags &= ~style::camera_flag::rest;
            const auto at = std::chrono::steady_clock::now();
            const standin::Drawn s = session.draw(c, {});
            if (s.stale) session.remake();
            const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - at).count();
            MESSAGE(std::format("zoom {:3d} {:6.0f} m: named {} drew {}, complete {}, {}k triangles, frame {:.0f} ms",
                                i, d, c.pack_id, map.drawn_pack(), map.complete(), s.record.triangles_k, ms));
            if (i % 6 == 0 || i == steps + 30) {
                auto pixels = kept->kept->read(kept->kept->latest());
                ATLAS_REQUIRE(pixels.has_value(), pixels.error().text());
                CHECK(gpu::write_ppm(*pixels, (fs::path(out) / std::format("zoom_{:03d}.ppm", i)).string()));
            }
        }
    }
    std::error_code ec;
    fs::remove_all(guard, ec);
}

#endif
