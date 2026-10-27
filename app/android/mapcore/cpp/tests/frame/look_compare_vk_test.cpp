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
        }
        std::error_code ec;
        fs::remove_all(guard, ec);
    }
}

#endif
