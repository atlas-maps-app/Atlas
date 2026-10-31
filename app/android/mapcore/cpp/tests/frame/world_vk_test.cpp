// The world drawn beyond the countries' reach (ENGINE.md step 5's step 2, "The world pass and its cover, flat"): with
// ATLAS_WORLD_DIR (a folder of `packer world`'s world.idx, its parts and `packer globe`'s world-globe.atlas), a camera
// over the Netherlands from 18 to 1,000 km from its target draws the world's land, water and roads, and the map's
// picture is complete with no country's pack: the app's path beyond 18 km, where the map said "Zoom in to see the map".
// With ATLAS_LOOK_OUT, each view is written as <out>/world_<distance>.ppm.
#include "base/pymath/PyMath.hpp"
#include "frame/MapFrame.hpp"
#include "gpu/Guard.hpp"
#include "gpu/Offscreen.hpp"
#include "passes/World.hpp"
#include "scene/WorldTiles.hpp"
#include "standin/Session.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/gpu/session_fixture.hpp"
#include "tiles/WorldIndex.hpp"

#include <doctest/doctest.h>

#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <fstream>
#include <iterator>
#include <memory>
#include <set>
#include <string>
#include <unistd.h>
#include <vector>

namespace atlas {

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

/// amsterdam.py's sunny moment of 1 October 2026, 16:00, with the hour's weather as Kotlin writes it
/// (tests/frame/map_frame_vk_test.cpp's): the phone's live moment, so the pinches run what it draws with it (the
/// facades' flat detail from 760 m, the deep and church windows, the material faces, the cloud shadows, the wet set).
[[nodiscard]] style::ThemeBlock sunny() {
    namespace flag = style::theme_flag;
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

constexpr vk::Extent2D size{.width = 360, .height = 640};
constexpr double density = 1.0;

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

/// The world of folder `dir` as the app lists it (PackList's world): each pack of world.idx a world_mid range of its
/// part by the part's path, and the globe.
[[nodiscard]] std::vector<frame::MapPack> world_packs(const fs::path& dir) {
    std::ifstream in(dir / "world.idx", std::ios::binary);
    const std::vector<char> raw((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    std::vector<std::byte> bytes(raw.size());
    for (std::size_t i = 0; i < raw.size(); ++i) bytes[i] = static_cast<std::byte>(raw[i]);
    const std::optional<tiles::WorldIndex> index = tiles::read_world_index(bytes);
    std::vector<frame::MapPack> out;
    if (!index) return out;
    std::uint64_t number = 100;
    for (const tiles::WorldEntry& e : index->entries) {
        frame::MapPack p;
        p.number = ++number;
        p.ref.id = tiles::world_pack_id(e);
        p.ref.kind = tiles::PackKind::world_mid;
        p.ref.path = (dir / index->parts.at(e.part).first).string();
        p.ref.offset = e.offset;
        p.ref.length = e.length;
        out.push_back(std::move(p));
    }
    frame::MapPack globe;
    globe.number = ++number;
    globe.ref.id = std::string(tiles::world_globe_id);
    globe.ref.kind = tiles::PackKind::world_base;
    globe.ref.path = (dir / tiles::world_globe_file).string();
    out.push_back(std::move(globe));
    return out;
}

/// The pixels of `p` within `tolerance` of `c` in each channel: the moment's grain and weather lie over the colours.
[[nodiscard]] std::size_t count_near(const gpu::Pixels& p, const style::Rgb8& c, int tolerance) {
    std::size_t n = 0;
    const auto near = [tolerance](std::uint8_t a, std::uint8_t b) { return std::abs(int{a} - int{b}) <= tolerance; };
    for (std::size_t i = 0; i + 3 < p.rgba.size(); i += 4) {
        if (near(p.rgba[i], c.r) && near(p.rgba[i + 1], c.g) && near(p.rgba[i + 2], c.b)) ++n;
    }
    return n;
}

} // namespace

TEST_CASE("the world: a camera over the Netherlands from 18 to 1,000 km draws its land, water and roads, complete "
          "with no country's pack") {
    const std::string world_dir = environment("ATLAS_WORLD_DIR");
    if (world_dir.empty()) {
        MESSAGE("ATLAS_WORLD_DIR is not set: the world is not drawn here");
        return;
    }
    if (!test::open_session()) return;
    const std::string out = environment("ATLAS_LOOK_OUT");
    std::vector<frame::MapPack> packs = world_packs(world_dir);
    ATLAS_REQUIRE(packs.size() > 1);
    const fs::path guard = fs::temp_directory_path() / std::format("atlas-world-{}", ::getpid());
    fs::create_directories(guard);
    auto started = gpu::start_guarded({.guard_dir = guard,
                                       .cache_file = guard / "pipelines.bin",
                                       .system = "host-test-system",
                                       .build = "world-test",
                                       .present = true,
                                       .spirv = 42});
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    {
        frame::MapFrame map(2, [] {}, {.cache_dir = guard});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        const std::string listed = map.set_packs(std::move(packs));
        CHECK(listed.find("world-globe listed") != std::string::npos);
        CHECK(session.map(&map).empty());
        auto window = std::make_unique<KeptWindow>();
        KeptWindow* kept = window.get();
        std::string why;
        ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
        const style::ThemeBlock theme = sunny();
        map.set_theme(theme);
        for (const double d : {18'500.0, 30'000.0, 60'000.0, 200'000.0, 1'000'000.0}) {
            style::CameraState cam = camera(52.37, 4.89, 0.0, 0.0, d);
            cam.pack_id = 0;
            const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(240);
            int frames = 0;
            bool complete = false;
            while (std::chrono::steady_clock::now() < until) {
                const standin::Drawn s = session.draw(cam, {});
                if (s.stale) session.remake();
                frames += s.drawn ? 1 : 0;
                scene::WorldTiles* tiles = map.world() != nullptr ? map.world()->world_tiles() : nullptr;
                const scene::WorldFrame* f = tiles != nullptr ? tiles->frame() : nullptr;
                complete = map.complete();
                if (complete && f != nullptr && f->wanted == 0 && frames > 2) break;
                if (tiles != nullptr) tiles->wait_idle();
            }
            const scene::WorldTiles* tiles = map.world()->world_tiles();
            ATLAS_REQUIRE(tiles != nullptr);
            const scene::WorldFrame* f = tiles->frame();
            ATLAS_REQUIRE(f != nullptr);
            std::uint64_t segments = 0;
            std::set<int> levels;
            for (const scene::WorldDraw& w : f->draws) {
                segments += w.tile->segments;
                levels.insert(w.tile->key.z);
            }
            const scene::WorldStats st = tiles->stats();
            MESSAGE(std::format("world {:.0f} m: {} frames, complete {}, {} draws (levels {}..{}), {} wanted, {} "
                                "segments, {} resident in {:.1f} MB, {} built in {:.0f} ms, {} failed",
                                d, frames, complete, f->draws.size(), *levels.begin(), *levels.rbegin(), f->wanted,
                                segments, st.resident, static_cast<double>(st.bytes) / 1e6, st.built, st.build_ms,
                                st.failed));
            CHECK(complete);
            CHECK(f->wanted == 0);
            CHECK(st.failed == 0);
            CHECK(segments > 0); // the roads, railways and rivers
            ATLAS_REQUIRE(kept->kept != nullptr);
            auto pixels = kept->kept->read(kept->kept->latest());
            ATLAS_REQUIRE(pixels.has_value(), pixels.error().text());
            const std::size_t all = std::size_t{pixels->width} * pixels->height;
            const std::size_t land = count_near(*pixels, theme.ground, 40);
            const std::size_t water = count_near(*pixels, theme.water, 40);
            MESSAGE(std::format("  land {} px, water {} px of {}", land, water, all));
            CHECK(land > all / 100);
            CHECK(water > all / 100);
            if (!out.empty())
                CHECK(gpu::write_ppm(*pixels, (fs::path(out) / std::format("world_{:.0f}.ppm", d)).string()));
        }
    }
    std::error_code ec;
    fs::remove_all(guard, ec);
}

} // namespace atlas
