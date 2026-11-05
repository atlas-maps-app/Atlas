// The world drawn beyond the countries' reach (ENGINE.md step 5's step 2, "The world pass and its cover, flat"): with
// ATLAS_WORLD_DIR (a folder of `packer world`'s world.idx, its parts and `packer globe`'s world-globe.atlas), a camera
// over the Netherlands from 18 to 1,000 km from its target draws the world's land, water and roads, and the map's
// picture is complete with no country's pack: the app's path beyond 18 km, where the map said "Zoom in to see the map".
// With ATLAS_LOOK_OUT, each view is written as <out>/world_<distance>.ppm. The world by ranges (ENGINE.md step 6) is
// held without ATLAS_WORLD_DIR, by a block and a globe written here: the blocks and tiles a view wants are asked of the
// app, and drawn once their heads and chunks are kept in the world's cache.
#include "base/pymath/PyMath.hpp"
#include "frame/Counters.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/MapFrame.hpp"
#include "gpu/Guard.hpp"
#include "gpu/Offscreen.hpp"
#include "load/PackStyle.hpp"
#include "passes/Ground.hpp"
#include "passes/World.hpp"
#include "scene/Cover.hpp"
#include "scene/Residency.hpp"
#include "scene/SunGlobals.hpp"
#include "scene/World.hpp"
#include "scene/WorldTiles.hpp"
#include "standin/Session.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/gpu/session_fixture.hpp"
#include "tests/tiles/world_support.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Container.hpp"
#include "tiles/ExpandedCache.hpp"
#include "tiles/Source.hpp"
#include "tiles/Store.hpp"
#include "tiles/TileBox.hpp"
#include "tiles/WorldIndex.hpp"
#include "view/Camera.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <fstream>
#include <iterator>
#include <memory>
#include <optional>
#include <set>
#include <span>
#include <string>
#include <string_view>
#include <unistd.h>
#include <utility>
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
                                                                   const gpu::Output* /*old*/, gpu::Depth depth,
                                                                   gpu::Compression /*compression*/) override {
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

/// The canal ring's bundled render pack of ATLAS_TILES_DIR as the app lists it, pack 1.
[[nodiscard]] frame::MapPack canal_ring(const fs::path& dir) {
    frame::MapPack p;
    p.number = 1;
    p.ref.id = "grachten";
    p.ref.kind = tiles::PackKind::bundled;
    p.ref.path = (dir / "grachten.atlas").string();
    return p;
}

/// The world's test paint (passes::World::paint_for_tests): magenta, which no map colour is near.
constexpr std::uint32_t magenta = 0xFFFF00FFU;
constexpr style::Rgb8 magenta_rgb{.r = 255, .g = 0, .b = 255, .a = 255};

/// A convex quad on the screen (device pixels), and whether a point lies in it at least `inset` pixels from each edge.
struct ScreenQuad {
    std::array<view::Vec2, 4> p{};

    [[nodiscard]] bool holds(double x, double y, double inset) const noexcept {
        // the quad's turn: the corners as projected, either way round
        double area = 0;
        for (std::size_t i = 0; i < 4; ++i) {
            const view::Vec2& a = p.at(i);
            const view::Vec2& b = p.at((i + 1) % 4);
            area += (a.x * b.y) - (b.x * a.y);
        }
        const double turn = area >= 0 ? 1.0 : -1.0;
        for (std::size_t i = 0; i < 4; ++i) {
            const view::Vec2& a = p.at(i);
            const view::Vec2& b = p.at((i + 1) % 4);
            const double ex = b.x - a.x;
            const double ey = b.y - a.y;
            const double len = std::sqrt((ex * ex) + (ey * ey));
            if (len == 0) return false;
            if (turn * ((ex * (y - a.y)) - (ey * (x - a.x))) / len < inset) return false;
        }
        return true;
    }
};

/// The screen quads of the wanted tiles the countries' cover drew in the map's last frame: each wanted tile's box on
/// OSM's grid placed by the tile drawing it, as the ground's tile plates are (none with a corner behind the eye).
[[nodiscard]] std::vector<ScreenQuad> drawn_quads(frame::MapFrame& map) {
    std::vector<ScreenQuad> out;
    const std::optional<view::View>& view = map.builder()->state().view;
    if (!view || map.world()->cover() == nullptr) return out;
    const view::Vec3& eye = view->camera().eye();
    for (const scene::CoverTile& c : map.world()->cover()->tiles()) {
        if (c.drawn == nullptr) continue;
        const tiles::TileKey k = c.key;
        const view::GroundFrame& f = c.drawn->frame;
        const double west = tiles::Frame::degrees(tiles::column_edge(k.z, k.x));
        const double east = tiles::Frame::degrees(tiles::column_edge(k.z, k.x + 1));
        const double north = tiles::Frame::degrees(tiles::row_edge(k.z, k.y));
        const double south = tiles::Frame::degrees(tiles::row_edge(k.z, k.y + 1));
        const auto scale = static_cast<double>(c.place.scale.x);
        const view::Vec3 offset{static_cast<double>(c.place.offset.x), static_cast<double>(c.place.offset.y),
                                static_cast<double>(c.place.offset.z)};
        ScreenQuad q;
        bool seen = true;
        std::size_t i = 0;
        for (const auto& [lat, lon] :
             {std::pair{north, west}, std::pair{north, east}, std::pair{south, east}, std::pair{south, west}}) {
            const view::Vec3 at{
                eye.x + offset.x + (scale * (f.x(lat, lon) - c.drawn->origin.x)),
                eye.y + offset.y,
                eye.z + offset.z + (scale * (f.z(lat, lon) - c.drawn->origin.z)),
            };
            const std::optional<view::Vec2> s = view->device_proj(at);
            if (!s) {
                seen = false;
                break;
            }
            q.p.at(i++) = *s;
        }
        if (seen) out.push_back(q);
    }
    return out;
}

/// The triangles the map's last frame drew of the counters' kind `kind`, across its passes.
[[nodiscard]] std::uint64_t drawn_triangles(const frame::MapFrame& map, std::string_view kind) {
    std::uint64_t n = 0;
    for (const frame::PassCounters& c : map.builder()->counters()) {
        for (const frame::KindCount& k : c.kinds()) {
            if (k.kind == kind) n += k.count.triangles;
        }
    }
    return n;
}

/// Draws `cam` until the map's picture is complete and the world wants no tile (at most four minutes).
void draw_complete(standin::Session& session, frame::MapFrame& map, const style::CameraState& cam) {
    const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(240);
    int frames = 0;
    while (std::chrono::steady_clock::now() < until) {
        const standin::Drawn s = session.draw(cam, {});
        if (s.stale) session.remake();
        frames += s.drawn ? 1 : 0;
        scene::WorldTiles* tiles = map.world() != nullptr ? map.world()->world_tiles() : nullptr;
        const scene::WorldFrame* f = tiles != nullptr ? tiles->frame() : nullptr;
        if (map.complete() && f != nullptr && f->wanted == 0 && frames > 2) return;
        if (tiles != nullptr) tiles->wait_idle();
    }
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
    if (!started) {
        // a Vulkan without a window system (CI's arm64 lavapipe) skips, as the map's other sessions do
        if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, started.error().reason);
        return;
    }
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
            // the rest's `vk tiles` line ends with the world's, as CI's world phase reads it (ci/smoke.sh)
            const std::string line = map.tiles_line();
            CHECK_MESSAGE(line.ends_with(std::format(" world {} drawn finest {} wanted 0 bare 0", f->draws.size(),
                                                     *levels.rbegin())),
                          line);
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

TEST_CASE("the hand-over: the canal ring's tiles at 25 km pitched 60 degrees over the world, which shows beyond them "
          "and never inside one") {
    const std::string world_dir = environment("ATLAS_WORLD_DIR");
    const std::string tiles_dir = environment("ATLAS_TILES_DIR");
    if (world_dir.empty() || tiles_dir.empty()) {
        MESSAGE("ATLAS_WORLD_DIR or ATLAS_TILES_DIR is not set: the hand-over is not drawn here");
        return;
    }
    if (!test::open_session()) return;
    const std::string out = environment("ATLAS_LOOK_OUT");
    std::vector<frame::MapPack> packs = world_packs(world_dir);
    ATLAS_REQUIRE(packs.size() > 1);
    packs.push_back(canal_ring(tiles_dir));
    const fs::path guard = fs::temp_directory_path() / std::format("atlas-seam-{}", ::getpid());
    fs::create_directories(guard);
    auto started = gpu::start_guarded({.guard_dir = guard,
                                       .cache_file = guard / "pipelines.bin",
                                       .system = "host-test-system",
                                       .build = "world-test",
                                       .present = true,
                                       .spirv = 42});
    if (!started) {
        // a Vulkan without a window system (CI's arm64 lavapipe) skips, as the map's other sessions do
        if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, started.error().reason);
        return;
    }
    passes::World::paint_for_tests(magenta);
    {
        frame::MapFrame map(2, [] {}, {.cache_dir = guard});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        const std::string listed = map.set_packs(std::move(packs));
        CHECK(listed.find("grachten listed") != std::string::npos);
        CHECK(session.map(&map).empty());
        auto window = std::make_unique<KeptWindow>();
        KeptWindow* kept = window.get();
        std::string why;
        ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
        map.set_theme(sunny());
        for (const double bearing : {0.0, 45.0}) {
            style::CameraState cam = camera(52.3765, 4.897, bearing, 60.0, 25'000.0);
            cam.pack_id = 1;
            draw_complete(session, map, cam);
            CHECK(map.complete());
            const std::vector<ScreenQuad> quads = drawn_quads(map);
            ATLAS_REQUIRE(kept->kept != nullptr);
            auto pixels = kept->kept->read(kept->kept->latest());
            ATLAS_REQUIRE(pixels.has_value(), pixels.error().text());
            // every pixel 2 px inside the drawn tiles' boxes together is the countries' (where two tiles meet too), and
            // the world's lie beyond them
            const auto in = [&quads](double x, double y) {
                return std::ranges::any_of(quads, [&](const ScreenQuad& q) { return q.holds(x, y, 0); });
            };
            const auto around = [&in](double x, double y, double r) {
                int n = 0;
                for (const double dx : {-r, 0.0, r}) {
                    for (const double dy : {-r, 0.0, r}) n += in(x + dx, y + dy) ? 1 : 0;
                }
                return n;
            };
            // and the ring 2 to 6 px beyond them is the world's: no plate reaches past a tile's edge
            std::size_t ring = 0;
            std::size_t world_ring = 0;
            std::size_t inside = 0;
            std::size_t world_inside = 0;
            std::size_t world_beyond = 0;
            const auto near = [](std::uint8_t a, std::uint8_t b) { return std::abs(int{a} - int{b}) <= 60; };
            for (std::uint32_t y = 0; y < pixels->height; ++y) {
                for (std::uint32_t x = 0; x < pixels->width; ++x) {
                    const std::size_t i = 4 * ((std::size_t{y} * pixels->width) + x);
                    const bool world = near(pixels->rgba[i], magenta_rgb.r) &&
                                       near(pixels->rgba[i + 1], magenta_rgb.g) &&
                                       near(pixels->rgba[i + 2], magenta_rgb.b);
                    const double px = x + 0.5;
                    const double py = y + 0.5;
                    const bool held = around(px, py, 2) == 9;
                    const bool ringed = around(px, py, 2) == 0 && around(px, py, 6) > 0;
                    ring += ringed ? 1 : 0;
                    world_ring += ringed && world ? 1 : 0;
                    inside += held ? 1 : 0;
                    world_inside += held && world ? 1 : 0;
                    world_beyond += !in(px, py) && world ? 1 : 0;
                }
            }
            const std::size_t all = std::size_t{pixels->width} * pixels->height;
            MESSAGE(std::format("bearing {:.0f}: {} tiles drawn, {} px inside them ({} the world's), {} px of the ring "
                                "beyond them ({} the world's), {} the world's beyond of {}; {}",
                                bearing, quads.size(), inside, world_inside, ring, world_ring, world_beyond, all,
                                map.tiles_line()));
            CHECK(quads.size() >= 4); // the canal ring's coarse level from 25 km: 4 tiles
            CHECK(inside > all / 100);
            CHECK(world_inside == 0);
            CHECK(ring > 0);
            CHECK(world_ring * 100 >= ring * 99);
            CHECK(world_beyond > all / 10);
            if (!out.empty()) {
                CHECK(gpu::write_ppm(*pixels, (fs::path(out) / std::format("seam_{:.0f}.ppm", bearing)).string()));
            }
        }
    }
    passes::World::paint_for_tests(0);
    std::error_code ec;
    fs::remove_all(guard, ec);
}

namespace {

/// The tests' clear colour: green, which no map colour is near, where nothing draws.
constexpr std::array<float, 4> green_clear{0.0F, 1.0F, 0.0F, 1.0F};

/// The pixels of `p` the clear colour shows in, alone or through what lies faded over it: green by at least 50 levels
/// over red and blue (the map's greenest, the parks' #A9C38A, by 26).
[[nodiscard]] std::size_t count_clear(const gpu::Pixels& p) {
    std::size_t n = 0;
    for (std::size_t i = 0; i + 3 < p.rgba.size(); i += 4) {
        const int r = p.rgba[i];
        const int g = p.rgba[i + 1];
        const int b = p.rgba[i + 2];
        if (g > r + 50 && g > b + 50) ++n;
    }
    return n;
}

/// A session's start for the world's tests in folder `guard`.
[[nodiscard]] auto start_world(const fs::path& guard) {
    fs::create_directories(guard);
    return gpu::start_guarded({.guard_dir = guard,
                               .cache_file = guard / "pipelines.bin",
                               .system = "host-test-system",
                               .build = "world-test",
                               .present = true,
                               .spirv = 42});
}

} // namespace

TEST_CASE("the hand-over: a zoom from 1 to 100 km over the canal ring draws each frame at once, and from 18 km on no "
          "cell lacks the countries' ground or the world's") {
    const std::string world_dir = environment("ATLAS_WORLD_DIR");
    const std::string tiles_dir = environment("ATLAS_TILES_DIR");
    if (world_dir.empty() || tiles_dir.empty()) {
        MESSAGE("ATLAS_WORLD_DIR or ATLAS_TILES_DIR is not set: the zoom is not drawn here");
        return;
    }
    if (!test::open_session()) return;
    std::vector<frame::MapPack> packs = world_packs(world_dir);
    ATLAS_REQUIRE(packs.size() > 1);
    packs.push_back(canal_ring(tiles_dir));
    const std::string out = environment("ATLAS_LOOK_OUT");
    const fs::path guard = fs::temp_directory_path() / std::format("atlas-zoom-{}", ::getpid());
    auto started = start_world(guard);
    if (!started) {
        // a Vulkan without a window system (CI's arm64 lavapipe) skips, as the map's other sessions do
        if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, started.error().reason);
        return;
    }
    {
        frame::MapFrame map(2, [] {}, {.cache_dir = guard, .clear = green_clear});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        CHECK(map.set_packs(std::move(packs)).find("grachten listed") != std::string::npos);
        CHECK(session.map(&map).empty());
        auto window = std::make_unique<KeptWindow>();
        KeptWindow* kept = window.get();
        std::string why;
        ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
        map.set_theme(sunny());
        // at rest over the canal ring from 1 km first, then out to 100 km in 60 frames, one frame a step: nothing waits
        style::CameraState cam = camera(52.3765, 4.897, 0.0, 30.0, 1'000.0);
        cam.pack_id = 1;
        draw_complete(session, map, cam);
        CHECK(map.complete());
        cam.flags = 0; // moving
        constexpr int steps = 60;
        int drawn = 0;
        int beyond = 0;
        double slowest_ms = 0;
        std::size_t clear_within =
            0; // up to 18 km: today's cover (the plate about the frame's origin, the pack's tiles)
        std::size_t clear_beyond = 0;
        std::size_t bare = 0;
        for (int i = 0; i <= steps; ++i) {
            cam.distance = 1'000.0 * pymath::pow(100.0, static_cast<double>(i) / steps);
            const auto start = std::chrono::steady_clock::now();
            const standin::Drawn s = session.draw(cam, {});
            const double ms =
                std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
            if (s.stale) session.remake();
            drawn += s.drawn ? 1 : 0;
            slowest_ms = std::max(slowest_ms, ms);
            ATLAS_REQUIRE(kept->kept != nullptr);
            auto pixels = kept->kept->read(kept->kept->latest());
            ATLAS_REQUIRE(pixels.has_value(), pixels.error().text());
            const std::size_t clear = count_clear(*pixels);
            const scene::WorldTiles* tiles = map.world()->world_tiles();
            const scene::WorldFrame* f = tiles != nullptr ? tiles->frame() : nullptr;
            if (cam.distance > scene::world_from) {
                ++beyond;
                clear_beyond += clear;
                bare += f != nullptr ? f->bare : 1;
                CHECK(f != nullptr);
            } else {
                clear_within += clear;
            }
            if (i % 10 == 0 && !out.empty()) {
                CHECK(gpu::write_ppm(*pixels, (fs::path(out) / std::format("zoom_{:.0f}.ppm", cam.distance)).string()));
            }
            if (i % 10 == 0) {
                MESSAGE(std::format("{:.0f} m: drawn {}, {} px of the clear colour, the world {} draws, {} wanted, {} "
                                    "bare, fading {}; {:.1f} ms",
                                    cam.distance, s.drawn, clear, f != nullptr ? f->draws.size() : 0U,
                                    f != nullptr ? f->wanted : 0U, f != nullptr ? f->bare : 0U,
                                    f != nullptr && f->fading, ms));
            }
        }
        MESSAGE(std::format("{} of {} frames drawn, the slowest in {:.1f} ms; up to 18 km {} px of the clear colour in "
                            "all, beyond {} in {} frames, {} bare cells",
                            drawn, steps + 1, slowest_ms, clear_within, clear_beyond, beyond, bare));
        CHECK(drawn == steps + 1);
        CHECK(beyond > 20);
        CHECK(clear_beyond == 0);
        CHECK(bare == 0);
        // at rest at 100 km the canal ring's pack still holds the target, its cover no tile: the world's picture is
        // the camera's final one (CI's world phase found the map loading there for ten minutes, 1 November)
        cam.flags = style::camera_flag::rest;
        draw_complete(session, map, cam);
        CHECK(map.drawn_pack() == "grachten");
        CHECK(map.world()->cover()->tiles().empty());
        CHECK(map.complete());
    }
    std::error_code ec;
    fs::remove_all(guard, ec);
}

TEST_CASE("the hand-over: a world tile fades in over 0.18 s over its ancestor's cells, and no frame waits for it") {
    const std::string world_dir = environment("ATLAS_WORLD_DIR");
    if (world_dir.empty()) {
        MESSAGE("ATLAS_WORLD_DIR is not set: the fades are not drawn here");
        return;
    }
    if (!test::open_session()) return;
    std::vector<frame::MapPack> packs = world_packs(world_dir);
    ATLAS_REQUIRE(packs.size() > 1);
    const fs::path guard = fs::temp_directory_path() / std::format("atlas-fade-{}", ::getpid());
    auto started = start_world(guard);
    if (!started) {
        // a Vulkan without a window system (CI's arm64 lavapipe) skips, as the map's other sessions do
        if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, started.error().reason);
        return;
    }
    {
        frame::MapFrame map(2, [] {}, {.cache_dir = guard, .clear = green_clear});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        (void)map.set_packs(std::move(packs));
        CHECK(session.map(&map).empty());
        auto window = std::make_unique<KeptWindow>();
        KeptWindow* kept = window.get();
        std::string why;
        ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
        map.set_theme(sunny());
        // the L8 tiles from 200 km, faded in
        style::CameraState cam = camera(52.37, 4.89, 0.0, 0.0, 200'000.0);
        draw_complete(session, map, cam);
        CHECK(map.complete());
        // then 30 km, whose L11 tiles are not resident: the first frame draws their ancestors' cells, at once
        cam.distance = 30'000.0;
        CHECK(session.draw(cam, {}).drawn);
        scene::WorldTiles* tiles = map.world()->world_tiles();
        ATLAS_REQUIRE(tiles != nullptr);
        const scene::WorldFrame* f = tiles->frame();
        ATLAS_REQUIRE(f != nullptr);
        CHECK(f->wanted > 0);
        CHECK(f->bare == 0);
        CHECK_FALSE(map.complete());
        // once they are built, the frame that takes them in draws each fading over its ancestor's cells
        tiles->wait_idle();
        CHECK(session.draw(cam, {}).drawn);
        const auto taken = std::chrono::steady_clock::now();
        f = tiles->frame();
        ATLAS_REQUIRE(f != nullptr);
        CHECK(f->fading);
        CHECK(map.animating());
        CHECK_FALSE(map.complete());
        std::size_t fading = 0;
        std::size_t under = 0;
        for (const scene::WorldDraw& d : f->draws) {
            if (d.fade >= 1.0F) continue;
            ++fading;
            const tiles::TileKey k = d.tile->key;
            const bool held = std::ranges::any_of(f->draws, [&](const scene::WorldDraw& a) {
                const tiles::TileKey ak = a.tile->key;
                if (ak.z >= k.z) return false;
                const auto gap = static_cast<std::uint32_t>(k.z - ak.z);
                if ((k.x >> gap) != ak.x || (k.y >> gap) != ak.y) return false;
                const std::uint64_t over = scene::cells_over(scene::world_cells_per_side, gap, k.x, k.y);
                return (a.cells & over) == over;
            });
            under += held ? 1 : 0;
        }
        {
            ATLAS_REQUIRE(kept->kept != nullptr);
            auto pixels = kept->kept->read(kept->kept->latest());
            ATLAS_REQUIRE(pixels.has_value(), pixels.error().text());
            CHECK(count_clear(*pixels) == 0); // the ancestors under the fading tiles: no clear colour
        }
        MESSAGE(
            std::format("{} draws, {} fading, {} of them over an ancestor's cells", f->draws.size(), fading, under));
        CHECK(fading > 0);
        CHECK(under == fading);
        // each frame drawn meanwhile; after 0.18 s every tile drawn whole and alone
        int frames = 0;
        while (std::chrono::steady_clock::now() - taken < std::chrono::milliseconds(250)) {
            CHECK(session.draw(cam, {}).drawn);
            ++frames;
        }
        CHECK(session.draw(cam, {}).drawn);
        f = tiles->frame();
        ATLAS_REQUIRE(f != nullptr);
        MESSAGE(std::format("{} frames during the fade; then {} draws, fading {}", frames, f->draws.size(), f->fading));
        CHECK_FALSE(f->fading);
        CHECK(std::ranges::all_of(f->draws, [](const scene::WorldDraw& d) { return d.fade >= 1.0F; }));
        CHECK(std::ranges::all_of(f->draws, [](const scene::WorldDraw& d) { return d.tile->key.z == 11; }));
        CHECK(map.complete());
        CHECK_FALSE(map.animating());
    }
    std::error_code ec;
    fs::remove_all(guard, ec);
}

TEST_CASE("the countries' fades: a tile fades in over 0.18 s over what the frame before drew there, its ground by "
          "alpha, and no frame waits for it") {
    const std::string tiles_dir = environment("ATLAS_TILES_DIR");
    if (tiles_dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the countries' fades are not drawn here");
        return;
    }
    if (!test::open_session()) return;
    const fs::path guard = fs::temp_directory_path() / std::format("atlas-tile-fade-{}", ::getpid());
    auto started = start_world(guard);
    if (!started) {
        // a Vulkan without a window system (CI's arm64 lavapipe) skips, as the map's other sessions do
        if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, started.error().reason);
        return;
    }
    {
        frame::MapFrame map(2, [] {}, {.cache_dir = guard, .clear = green_clear});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        std::vector<frame::MapPack> packs;
        packs.push_back(canal_ring(tiles_dir));
        CHECK(map.set_packs(std::move(packs)).find("grachten listed") != std::string::npos);
        CHECK(session.map(&map).empty());
        auto window = std::make_unique<KeptWindow>();
        KeptWindow* kept = window.get();
        std::string why;
        ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
        map.set_theme(sunny());
        // the ground alone: what stands up, the shadows and the water's effects left out
        style::CameraState cam = camera(52.3765, 4.897, 0.0, 0.0, 9'000.0);
        cam.pack_id = 1;
        for (unsigned bit = 8; bit <= 40; ++bit) cam.parts |= std::uint64_t{1} << bit;
        cam.parts |= style::camera_part::canopy | style::camera_part::setts | style::camera_part::ground_text |
                     style::camera_part::quays;
        // at rest from 9 km over the canal ring's L12 tiles, faded in
        draw_complete(session, map, cam);
        CHECK(map.complete());
        const scene::Cover& cover = *map.world()->cover();
        CHECK(std::ranges::all_of(cover.tiles(), [](const scene::CoverTile& c) { return c.drawn->key.z == 12; }));
        CHECK(cover.with_under().size() == cover.tiles().size());
        const auto read = [&] {
            auto pixels = kept->kept->read(kept->kept->latest());
            REQUIRE(pixels.has_value());
            return *std::move(pixels);
        };
        const auto mad = [](const gpu::Pixels& a, const gpu::Pixels& b) {
            double sum = 0;
            for (std::size_t i = 0; i + 3 < a.rgba.size() && i + 3 < b.rgba.size(); i += 4) {
                for (std::size_t k = 0; k < 3; ++k) sum += std::abs(int{a.rgba[i + k]} - int{b.rgba[i + k]});
            }
            const std::size_t pixels = a.rgba.size() / 4;
            return sum / static_cast<double>(pixels);
        };
        // then 1.2 km, whose L14 tiles are not resident: the L12 stand in, and each L14 fades in over them as it lands
        cam.distance = 1'200.0;
        int drawn = 0;
        int frames = 0;
        int fading_frames = 0;
        std::size_t fading = 0;
        std::size_t held = 0;
        std::size_t under = 0;
        std::uint64_t fading_streets = 0; // the triangles of the fading tiles' streets, drawn in their own run
        std::optional<gpu::Pixels> before;
        std::optional<gpu::Pixels> first;
        std::vector<double> path; // each fading frame's distance from the stand-ins' picture
        gpu::Pixels last = read();
        float least = 1.0F;
        const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(240);
        while (std::chrono::steady_clock::now() < until) {
            const standin::Drawn s = session.draw(cam, {});
            if (s.stale) session.remake();
            ++frames;
            drawn += s.drawn ? 1 : 0;
            const gpu::Pixels now = read();
            bool fades = false;   // a tile whose fade the picture waits for
            bool blended = false; // a tile drawn at its fade
            for (const scene::CoverTile& t : cover.tiles()) {
                if (t.fade >= 1.0F) continue;
                fades = true;
                if (t.place.scale.w >= 1.0F) continue; // drawn whole: it took over from nothing
                blended = true;
                ++fading;
                least = std::min(least, t.place.scale.w);
                const tiles::TileKey k = t.drawn->key;
                // its ancestor drawn (a stand-in drawing whole) or kept under it, its cells over it
                held += std::ranges::any_of(cover.with_under(),
                                            [&](const scene::CoverTile& a) {
                                                const tiles::TileKey ak = a.drawn->key;
                                                if (ak.z >= k.z || a.drawn->pack != t.drawn->pack) return false;
                                                const auto gap = static_cast<std::uint32_t>(k.z - ak.z);
                                                if ((k.x >> gap) != ak.x || (k.y >> gap) != ak.y) return false;
                                                const std::uint64_t over =
                                                    scene::cells_over(a.drawn->cells_per_side, gap, k.x, k.y);
                                                return (!a.under && a.key == ak) || (a.cells & over) == over;
                                            })
                            ? 1
                            : 0;
            }
            under += cover.with_under().size() - cover.tiles().size();
            if (fades) {
                CHECK_FALSE(map.complete());
                CHECK(map.animating());
            }
            if (blended) {
                ++fading_frames;
                fading_streets += drawn_triangles(map, "ground fading streets");
                if (!first) {
                    before = last; // the stand-ins' picture
                    first = now;
                }
                if (before) path.push_back(mad(now, *before));
            }
            last = now;
            // complete and at rest: the tiles' fades over, and their first sun bits faded in from by day (300 ms from
            // their publication, which may come after the tiles' fades)
            if (map.complete() && !map.animating() && frames > 2) break;
        }
        MESSAGE(std::format("{} of {} frames drawn, {} of them fading ({} tiles fading, the least fade {:.3f}, {} of "
                            "them over an ancestor; {} kept under them)",
                            drawn, frames, fading_frames, fading, least, held, under));
        CHECK(drawn == frames);
        CHECK(fading_frames > 0);
        CHECK(held == fading);
        CHECK(under > 0);
        // the app's map's streets off a bridge fade in with the rest of their tile, kept off the water as at rest
        MESSAGE(std::format("{} triangles of the fading tiles' streets drawn in their own run", fading_streets));
        CHECK(fading_streets > 0);
        // once every fade has ended: the L14 alone, nothing kept under them, the picture complete and at rest
        CHECK(map.complete());
        CHECK_FALSE(map.animating());
        CHECK(cover.with_under().size() == cover.tiles().size());
        CHECK(std::ranges::all_of(cover.tiles(), [](const scene::CoverTile& c) { return c.fade >= 1.0F; }));
        // each fade starts from the picture before it, the stand-ins', and ends at the tiles' own
        if (first && before) {
            const double start = mad(*first, *before);
            const double end = mad(last, *before);
            std::string steps;
            for (const double d : path) steps += std::format(" {:.3f}", d);
            MESSAGE(std::format("the first fading frame {:.3f} from the stand-ins' picture, the last frame {:.3f}; "
                                "the fading frames{}",
                                start, end, steps));
            CHECK(start < end / 2);
            // and on through the fade toward the tiles' own, frame by frame
            for (std::size_t i = 1; i < path.size(); ++i) CHECK(path[i] >= path[i - 1] - 0.05);
            CHECK(std::ranges::all_of(path, [end](double d) { return d < end; }));
        }
        const std::string out = environment("ATLAS_LOOK_OUT");
        if (!out.empty() && first && before) {
            CHECK(gpu::write_ppm(*before, (fs::path(out) / "tile_fade_before.ppm").string()));
            CHECK(gpu::write_ppm(*first, (fs::path(out) / "tile_fade_first.ppm").string()));
            CHECK(gpu::write_ppm(last, (fs::path(out) / "tile_fade_last.ppm").string()));
        }
    }
    std::error_code ec;
    fs::remove_all(guard, ec);
}

TEST_CASE("the countries' fades: a tile kept under one fading in draws no reflections, and the fading tile draws its "
          "own at once") {
    const std::string tiles_dir = environment("ATLAS_TILES_DIR");
    if (tiles_dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the countries' fades are not drawn here");
        return;
    }
    if (!test::open_session()) return;
    const fs::path guard = fs::temp_directory_path() / std::format("atlas-tile-fade-mirrors-{}", ::getpid());
    auto started = start_world(guard);
    if (!started) {
        // a Vulkan without a window system (CI's arm64 lavapipe) skips, as the map's other sessions do
        if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, started.error().reason);
        return;
    }
    {
        frame::MapFrame map(2, [] {}, {.cache_dir = guard, .clear = green_clear});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        std::vector<frame::MapPack> packs;
        packs.push_back(canal_ring(tiles_dir));
        CHECK(map.set_packs(std::move(packs)).find("grachten listed") != std::string::npos);
        CHECK(session.map(&map).empty());
        std::string why;
        ATLAS_REQUIRE(session.attach(std::make_unique<KeptWindow>(), size, why), why);
        map.set_theme(sunny());
        // the ground and the walls' and trees' reflections in the water: what stands up, the shadows and the water's
        // other effects left out; pitched, so the walls along the canals face the eye in the water
        style::CameraState cam = camera(52.3765, 4.897, 30.0, 45.0, 9'000.0);
        cam.pack_id = 1;
        for (unsigned bit = 8; bit <= 40; ++bit) cam.parts |= std::uint64_t{1} << bit;
        cam.parts &= ~style::camera_part::reflections;
        cam.parts |= style::camera_part::canopy | style::camera_part::setts | style::camera_part::ground_text;
        // at rest from 9 km over the canal ring's L12 tiles, faded in
        draw_complete(session, map, cam);
        CHECK(map.complete());
        const passes::Ground* ground = nullptr;
        for (const std::unique_ptr<frame::Pass>& p : map.builder()->passes()) {
            // NOLINTNEXTLINE(cppcoreguidelines-pro-type-static-cast-downcast): "ground" is passes::Ground
            if (p->name() == "ground") ground = static_cast<const passes::Ground*>(p.get());
        }
        ATLAS_REQUIRE(ground != nullptr, "the map has no ground pass");
        // Each frame until the picture is complete again: whether a fading tile's reflections are drawn, whether a tile
        // is kept under one fading in and holds mirrored walls, and the reflections of the tiles kept under.
        struct Leg {
            int frames = 0;
            int fading_drawn = 0;
            int kept = 0;
            int kept_mirrors = 0;
            std::size_t reflections = 0;
            std::size_t of_kept = 0;
        };
        const auto mirrors = [](const passes::DrawnTile& t) { return t.under && !t.ground->mirror_heads.empty(); };
        const auto zoom = [&](double distance) {
            Leg leg;
            cam.distance = distance;
            const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(240);
            while (std::chrono::steady_clock::now() < until) {
                const standin::Drawn s = session.draw(cam, {});
                if (s.stale) session.remake();
                ++leg.frames;
                const passes::GroundFrame& f = ground->decided();
                const auto of_kept = [&f](const passes::Reflection& e) { return f.tiles[e.tile].under; };
                const auto of_fading = [&f](const passes::Reflection& e) { return f.tiles[e.tile].fade < 1.0F; };
                leg.reflections += f.reflections.size();
                leg.of_kept += static_cast<std::size_t>(std::ranges::count_if(f.reflections, of_kept));
                if (std::ranges::any_of(f.reflections, of_fading)) ++leg.fading_drawn;
                if (std::ranges::any_of(f.tiles, &passes::DrawnTile::under)) {
                    ++leg.kept;
                    if (std::ranges::any_of(f.tiles, mirrors)) ++leg.kept_mirrors;
                }
                if (map.complete() && leg.frames > 2) break;
            }
            MESSAGE(std::format("to {} m: {} frames, {} drawing a fading tile's reflections, {} with a tile kept under "
                                "one fading in ({} of them one holding mirrored walls); {} reflections, {} of a tile "
                                "kept under",
                                distance, leg.frames, leg.fading_drawn, leg.kept, leg.kept_mirrors, leg.reflections,
                                leg.of_kept));
            CHECK(map.complete());
            return leg;
        };
        // then 1.2 km, whose L14 tiles are not resident: each fades in as it lands over the L12 that drew there (which
        // mirrors no wall), its own reflections drawn from its first frame, whether the L12 is kept under it or still
        // stands in beside it
        const Leg in = zoom(1'200.0);
        CHECK(in.kept > 0);
        CHECK(in.fading_drawn > 0);
        CHECK(in.of_kept == 0);
        CHECK_FALSE(ground->decided().reflections.empty()); // at rest over the L14
        // and out to 9 km again: the L12 fades in over the L14 kept under it, whose mirrored walls are not drawn
        const Leg out = zoom(9'000.0);
        CHECK(out.kept > 0);
        CHECK(out.kept_mirrors > 0);
        CHECK(out.of_kept == 0);
    }
    std::error_code ec;
    fs::remove_all(guard, ec);
}

TEST_CASE("the standing fades: what stands up of a tile fading in trades pixels with the tile it takes over from, each "
          "at its own depth, from the stand-ins' picture on to its own") {
    const std::string tiles_dir = environment("ATLAS_TILES_DIR");
    if (tiles_dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the standing fades are not drawn here");
        return;
    }
    if (!test::open_session()) return;
    const fs::path guard = fs::temp_directory_path() / std::format("atlas-standing-fade-{}", ::getpid());
    auto started = start_world(guard);
    if (!started) {
        // a Vulkan without a window system (CI's arm64 lavapipe) skips, as the map's other sessions do
        if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, started.error().reason);
        return;
    }
    {
        // each fade over 3 s, so that lavapipe's frames, which take a good part of a second here, see it at several of
        // its steps, as the phone's 0.18 s spans several of its frames
        frame::MapFrame map(2, [] {}, {.cache_dir = guard, .fade_seconds = 3.0, .clear = green_clear});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        std::vector<frame::MapPack> packs;
        packs.push_back(canal_ring(tiles_dir));
        CHECK(map.set_packs(std::move(packs)).find("grachten listed") != std::string::npos);
        CHECK(session.map(&map).empty());
        auto window = std::make_unique<KeptWindow>();
        KeptWindow* kept = window.get();
        std::string why;
        ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
        map.set_theme(sunny());
        // the ground and what stands up on it: the shadows, the water's light, the night's and the weather's effects
        // and the labels left out; pitched, so the walls face the eye
        style::CameraState cam = camera(52.3765, 4.897, 30.0, 45.0, 9'000.0);
        cam.pack_id = 1;
        for (unsigned bit = 8; bit <= 23; ++bit) cam.parts |= std::uint64_t{1} << bit;
        cam.parts |= style::camera_part::labels;
        // at rest from 9 km over the canal ring's L12 tiles, faded in: nothing dithers
        draw_complete(session, map, cam);
        CHECK(map.complete());
        const scene::Cover& cover = *map.world()->cover();
        CHECK_FALSE(cover.dithers());
        CHECK(cover.upright().size() == cover.tiles().size());
        const auto read = [&] {
            auto pixels = kept->kept->read(kept->kept->latest());
            REQUIRE(pixels.has_value());
            return *std::move(pixels);
        };
        const auto mad = [](const gpu::Pixels& a, const gpu::Pixels& b) {
            double sum = 0;
            for (std::size_t i = 0; i + 3 < a.rgba.size() && i + 3 < b.rgba.size(); i += 4) {
                for (std::size_t k = 0; k < 3; ++k) sum += std::abs(int{a.rgba[i + k]} - int{b.rgba[i + k]});
            }
            const std::size_t pixels = a.rgba.size() / 4;
            return sum / static_cast<double>(pixels);
        };
        // the mean of a picture's channels: how light it is
        const auto light = [](const gpu::Pixels& a) {
            double sum = 0;
            for (std::size_t i = 0; i + 3 < a.rgba.size(); i += 4) {
                sum += int{a.rgba[i]} + a.rgba[i + 1] + a.rgba[i + 2];
            }
            const std::size_t pixels = a.rgba.size() / 4;
            return sum / (3.0 * static_cast<double>(pixels));
        };
        // then 1.2 km: each L14 fades in as it lands over the L12 the frame before drew there (kept under it, or still
        // drawn for another place), the two tiles' walls, roofs, trees, things, items and doors drawn each on its own
        // range of the dither; its sun's bits held back until every fade has ended, as on a phone, whose 0.18 s ends
        // before a tile's bits come (here they come mid-fade or after, as lavapipe's frames go), so that the L14 fades
        // in before its bits throughout, drawn by day, as the L12 is
        scene::SunGlobals* sun = map.world()->sun();
        ATLAS_REQUIRE(sun != nullptr, "the map's world has no sun");
        sun->hold(true);
        cam.distance = 1'200.0;
        int frames = 0;
        int drawn = 0;
        int dithered = 0;
        std::size_t kept_entries = 0; // upright()'s entries of a tile under one fading in, over the dithered frames
        std::uint64_t walls = 0;      // the walls' triangles the dithered frames drew
        std::size_t whole = 0;        // entries of a tile fading in drawn whole, over every frame: none here
        std::optional<gpu::Pixels> before;
        std::optional<gpu::Pixels> first;
        std::vector<double> path; // each dithered frame's distance from the stand-ins' picture
        const std::string out = environment("ATLAS_LOOK_OUT");
        std::vector<gpu::Pixels> looks; // with ATLAS_LOOK_OUT, the dithered frames, for the middle one's picture
        gpu::Pixels last = read();
        const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(240);
        while (std::chrono::steady_clock::now() < until) {
            const standin::Drawn s = session.draw(cam, {});
            if (s.stale) session.remake();
            ++frames;
            drawn += s.drawn ? 1 : 0;
            const gpu::Pixels now = read();
            // every tile fading in here takes over from a tile the frame before drew, so none stands whole
            whole += static_cast<std::size_t>(std::ranges::count_if(cover.upright(), [](const scene::CoverTile& t) {
                return !t.under && t.fade < 1.0F && t.place.scale.w >= 1.0F;
            }));
            if (cover.dithers()) {
                ++dithered;
                kept_entries +=
                    static_cast<std::size_t>(std::ranges::count_if(cover.upright(), &scene::CoverTile::under));
                walls += drawn_triangles(map, "walls");
                if (!first) {
                    before = last; // the stand-ins' picture
                    first = now;
                }
                if (before) path.push_back(mad(now, *before));
                if (!out.empty()) looks.push_back(now);
            }
            last = now;
            if (map.complete() && frames > 2) break;
        }
        MESSAGE(std::format("{} of {} frames drawn, {} of them dithered ({} entries under a fading tile, {} walls' "
                            "triangles); {} entries of a fading tile drawn whole",
                            drawn, frames, dithered, kept_entries, walls, whole));
        CHECK(drawn == frames);
        CHECK(dithered > 0);
        CHECK(kept_entries > 0);
        CHECK(walls > 0);
        CHECK(whole == 0);
        // once every fade has ended: the L14 alone, drawn whole, nothing dithered
        CHECK(map.complete());
        CHECK_FALSE(cover.dithers());
        CHECK(cover.upright().size() == cover.tiles().size());
        // each fade starts from the stand-ins' picture, what stands up of the two tiles included, and ends at the
        // tiles' own: no frame shows the fading tile's buildings whole over its ground at its first fade, nor leaves
        // out the buildings of the tile under it (a dithered pixel shows the one picture or the other, the light the
        // same throughout)
        const gpu::Pixels day = last; // the L14 before its bits
        if (first && before) {
            const double start = mad(*first, *before);
            const double end = mad(day, *before);
            std::string steps;
            for (const double d : path) steps += std::format(" {:.3f}", d);
            MESSAGE(std::format("the first dithered frame {:.3f} from the stand-ins' picture, the last frame {:.3f}; "
                                "the dithered frames{}",
                                start, end, steps));
            CHECK(start < end / 2);
            for (std::size_t i = 1; i < path.size(); ++i) CHECK(path[i] >= path[i - 1] - 0.05);
            CHECK(std::ranges::all_of(path, [end](double d) { return d < end; }));
        }
        // Then its bits, made meanwhile: the first frame that draws them draws the L14 by day still, and their
        // shadows fall in over a swap's 0.3 s; before, the L14 was drawn in the shade until its bits came, darker than
        // with them, and they showed at once.
        sun->wait_idle();
        sun->hold(false);
        std::optional<gpu::Pixels> released;
        std::vector<double> lights; // each frame's distance from the picture by day, as the shadows fall in
        int lit_frames = 0;
        bool lit = false;
        const auto lit_until = std::chrono::steady_clock::now() + std::chrono::seconds(120);
        while (!lit && std::chrono::steady_clock::now() < lit_until) {
            const standin::Drawn s = session.draw(cam, {});
            if (s.stale) session.remake();
            ++lit_frames;
            last = read();
            if (!released) released = last;
            lights.push_back(mad(last, day));
            lit = !sun->fading() && std::ranges::all_of(cover.tiles(), [](const scene::CoverTile& t) {
                return t.sun.has_value() && t.sun_weight >= 1.0F;
            });
        }
        CHECK(lit);
        if (released) {
            std::string steps;
            for (const double d : lights) steps += std::format(" {:.3f}", d);
            const double shaded = mad(last, day);
            MESSAGE(
                std::format("the bits in {} frames, from the picture by day{}; the picture by day {:.2f} light, with "
                            "the bits {:.2f}",
                            lit_frames, steps, light(day), light(last)));
            CHECK(shaded > 1.0);                     // the shadows fell
            CHECK(mad(*released, day) < shaded / 4); // from by day, not at once
            CHECK(light(day) > light(last));         // by day before them, never in the shade
            for (std::size_t i = 1; i < lights.size(); ++i) CHECK(lights[i] >= lights[i - 1] - 0.05);
        }
        if (!out.empty() && first && before) {
            CHECK(gpu::write_ppm(*before, (fs::path(out) / "standing_fade_before.ppm").string()));
            CHECK(gpu::write_ppm(*first, (fs::path(out) / "standing_fade_first.ppm").string()));
            CHECK(gpu::write_ppm(looks[looks.size() / 2], (fs::path(out) / "standing_fade_middle.ppm").string()));
            CHECK(gpu::write_ppm(day, (fs::path(out) / "standing_fade_last.ppm").string()));
            if (released) CHECK(gpu::write_ppm(*released, (fs::path(out) / "standing_fade_released.ppm").string()));
            CHECK(gpu::write_ppm(last, (fs::path(out) / "standing_fade_lit.ppm").string()));
        }
    }
    std::error_code ec;
    fs::remove_all(guard, ec);
}

TEST_CASE("the world by ranges: the blocks and tiles a view wants are asked of the app, and drawn once their heads and "
          "chunks are kept in the world's cache") {
    if (!test::open_session()) return;
    const fs::path guard = fs::temp_directory_path() / std::format("atlas-world-ranges-{}", ::getpid());
    fs::create_directories(guard);
    // Amsterdam's L6 block (32, 21), its L9 to L11 tiles over the city, which the app brings by ranges; the globe's L0
    // and L5 tiles over it, a file on the phone
    constexpr std::array<std::uint8_t, 3> fine = {11, 10, 9};
    constexpr std::array<std::uint8_t, 6> coarse = {5, 4, 3, 2, 1, 0};
    const std::vector<std::byte> block = test::world_pack(
        {test::world_tile(9, 262, 168), test::world_tile(10, 525, 336), test::world_tile(11, 1051, 673)}, fine);
    const std::vector<std::byte> globe =
        test::world_pack({test::world_tile(0, 0, 0), test::world_tile(5, 16, 10)}, coarse);
    {
        std::ofstream out(guard / tiles::world_globe_file, std::ios::binary);
        out.write(reinterpret_cast<const char*>(globe.data()), static_cast<std::streamsize>(globe.size())); // NOLINT
    }
    auto opened = tiles::ExpandedCache::open((guard / "world-cache").string(), std::uint64_t{1} << 24U);
    ATLAS_REQUIRE(opened);
    const std::shared_ptr<tiles::ExpandedCache> cache(std::move(*opened));
    frame::MapPack remote;
    remote.number = 101;
    remote.ref.id = "world/6/32/21";
    remote.ref.kind = tiles::PackKind::world_mid;
    remote.ref.length = block.size();
    remote.ref.identity = tiles::Sha256::of(block);
    remote.ref.fetch = tiles::Store::absent();
    frame::MapPack base;
    base.number = 102;
    base.ref.id = std::string(tiles::world_globe_id);
    base.ref.kind = tiles::PackKind::world_base;
    base.ref.path = (guard / tiles::world_globe_file).string();
    const auto packs = [&] { return std::vector<frame::MapPack>{remote, base}; };
    // the head as WorldFetch.head brings it: the block's bytes before its chunks and after them
    const std::span<const std::byte> all(block);
    auto whole = tiles::Pack::open(std::make_unique<tiles::MemorySource>(all));
    ATLAS_REQUIRE(whole);
    const tiles::Section* chunks = tiles::find_section(whole->sections(), "chunks");
    ATLAS_REQUIRE(chunks != nullptr);
    const std::uint64_t chunks_end = chunks->offset + chunks->stored;
    const std::array<tiles::PackBytes, 2> head = {
        {{.offset = 0, .bytes = all.first(chunks->offset)}, {.offset = chunks_end, .bytes = all.subspan(chunks_end)}}};
    auto started = gpu::start_guarded({.guard_dir = guard,
                                       .cache_file = guard / "pipelines.bin",
                                       .system = "host-test-system",
                                       .build = "world-ranges-test",
                                       .present = true,
                                       .spirv = 42});
    if (!started) {
        if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, started.error().reason);
        return;
    }
    {
        frame::MapFrame map(2, [] {}, {.cache_dir = guard});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        map.set_world_cache(cache);
        CHECK(map.set_packs(packs()).find("world-globe listed") != std::string::npos);
        CHECK(session.map(&map).empty());
        std::string why;
        ATLAS_REQUIRE(session.attach(std::make_unique<KeptWindow>(), size, why), why);
        map.set_theme(sunny());
        const style::CameraState cam = camera(52.37, 4.89, 0.0, 0.0, 30'000.0);
        // draws the camera until `done` holds, at most a minute
        const auto draw_until = [&](const auto& done) {
            const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(60);
            while (std::chrono::steady_clock::now() < until) {
                const standin::Drawn s = session.draw(cam, {});
                if (s.stale) session.remake();
                if (done()) return true;
                scene::WorldTiles* tiles = map.world() != nullptr ? map.world()->world_tiles() : nullptr;
                if (tiles != nullptr) tiles->wait_idle();
            }
            return false;
        };
        const auto block_asked = [&] {
            const std::vector<tiles::TileKey> blocks = map.world_blocks();
            return std::ranges::find(blocks, tiles::TileKey(6, 32, 21)) != blocks.end();
        };
        // no head on the phone: the block does not open, and the view asks for it (the globe draws meanwhile)
        CHECK(draw_until(block_asked));
        CHECK(map.world_pack(remote.ref.id) == nullptr);
        CHECK(map.world_wants().empty());
        // its head kept, the packs handed again: the block opens, and is asked for no more
        ATLAS_REQUIRE(tiles::Store::keep_head(*cache, remote.ref, head, load::world_style()));
        MESSAGE(map.set_packs(packs()));
        const std::shared_ptr<const tiles::StorePack> pack = map.world_pack(remote.ref.id);
        ATLAS_REQUIRE(pack != nullptr);
        CHECK(pack->remote);
        // its tiles the view wants are asked for, each its chunk's range in the pack; the picture waits for them
        CHECK(draw_until([&] { return !map.world_wants().empty() && !block_asked(); }));
        const scene::WorldFrame* waiting = map.world()->world_tiles()->frame();
        ATLAS_REQUIRE(waiting != nullptr);
        CHECK(waiting->wanted > 0);
        // the app's part (WorldReads): each step keeps the chunks wanted, a range of the pack each
        tiles::Zstd zstd;
        std::set<std::uint64_t> asked;
        std::size_t kept = 0;
        const auto all_drawn = [&] {
            for (const scene::WorldWant& w : map.world_wants()) {
                CHECK(w.pack == remote.ref.id);
                CHECK(w.identity == remote.ref.identity);
                const auto table = pack->pack.chunks().chunks();
                const auto c = std::ranges::find_if(table, [&](const tiles::ChunkRef& r) { return r.tile == w.tile; });
                ATLAS_REQUIRE_OR_RETURN(false, c != table.end());
                CHECK(w.offset == c->offset);
                CHECK(w.length == c->stored);
                asked.insert(w.tile.packed());
                kept += tiles::Store::keep(*cache, *pack,
                                           {.offset = w.offset, .bytes = all.subspan(w.offset, w.length)}, zstd)
                            .kept.size();
            }
            const scene::WorldFrame* f = map.world()->world_tiles()->frame();
            return f != nullptr && f->wanted == 0 && !f->fading && map.world_wants().empty();
        };
        CHECK(draw_until(all_drawn));
        CHECK(!asked.empty());
        CHECK(kept >= asked.size());
        const scene::WorldTiles* tiles = map.world()->world_tiles();
        const scene::WorldFrame* f = tiles->frame();
        ATLAS_REQUIRE(f != nullptr);
        std::set<std::uint64_t> drawn;
        for (const scene::WorldDraw& d : f->draws) {
            if (d.tile->key.z >= 9) drawn.insert(d.tile->key.packed());
        }
        MESSAGE(std::format("{} of the block's tiles asked for, {} chunks kept, {} drawn; {} draws in all, {} bare",
                            asked.size(), kept, drawn.size(), f->draws.size(), f->bare));
        CHECK(std::ranges::includes(drawn, asked));
        CHECK(tiles->stats().failed == 0);
    }
    std::error_code ec;
    fs::remove_all(guard, ec);
}

TEST_CASE("the world's painter's order holds wherever the camera stands: from 45 km pitched 48 degrees, a camera moved "
          "a centimetre at a time draws the same picture, the water over the land and the lines over the fills") {
    const std::string world_dir = environment("ATLAS_WORLD_DIR");
    if (world_dir.empty()) {
        MESSAGE("ATLAS_WORLD_DIR is not set: the world is not drawn here");
        return;
    }
    if (!test::open_session()) return;
    std::vector<frame::MapPack> packs = world_packs(world_dir);
    ATLAS_REQUIRE(packs.size() > 1);
    const std::string out = environment("ATLAS_LOOK_OUT");
    const fs::path guard = fs::temp_directory_path() / std::format("atlas-order-{}", ::getpid());
    auto started = start_world(guard);
    if (!started) {
        // a Vulkan without a window system (CI's arm64 lavapipe) skips, as the map's other sessions do
        if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, started.error().reason);
        return;
    }
    {
        frame::MapFrame map(2, [] {}, {.cache_dir = guard});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        CHECK(map.set_packs(std::move(packs)).find("world-globe listed") != std::string::npos);
        CHECK(session.map(&map).empty());
        auto window = std::make_unique<KeptWindow>();
        KeptWindow* kept = window.get();
        std::string why;
        ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
        map.set_theme(sunny());
        // over the Westerkerk, the polders, the IJ and the railways west and north of it ahead, where the user saw the
        // far shimmer; the cloud shadows, which drift with the clock, left out
        style::CameraState cam = camera(52.3735, 4.8755, 22.0, 48.0, 45'000.0);
        cam.parts = style::camera_part::cloud_shadows;
        draw_complete(session, map, cam);
        CHECK(map.complete());
        const auto faded = std::chrono::steady_clock::now() + std::chrono::seconds(10);
        const scene::WorldFrame* f = map.world()->world_tiles()->frame();
        while (f != nullptr && f->fading && std::chrono::steady_clock::now() < faded) {
            (void)session.draw(cam, {});
            f = map.world()->world_tiles()->frame();
        }
        ATLAS_REQUIRE(f != nullptr);
        CHECK_FALSE(f->fading);
        ATLAS_REQUIRE(kept->kept != nullptr);
        // the target a centimetre north a step: an edge moves about a ten-thousandth of a pixel, while the points'
        // frame metres from the eye move a unit or two of their last place, and with them the rounding of the
        // fills' z·w / w; drawn twice, the second read
        const auto picture = [&](int step) {
            style::CameraState c = cam;
            c.latitude += static_cast<double>(step) * 0.01 / 111'320.0;
            (void)session.draw(c, {});
            (void)session.draw(c, {});
            auto pixels = kept->kept->read(kept->kept->latest());
            REQUIRE(pixels.has_value());
            return *std::move(pixels);
        };
        gpu::Pixels last = picture(0);
        const std::size_t all = std::size_t{last.width} * last.height;
        std::size_t most = 0;
        std::string steps;
        for (int step = 1; step <= 8; ++step) {
            gpu::Pixels p = picture(step);
            REQUIRE(p.rgba.size() == last.rgba.size());
            // the pixels changed from the picture a centimetre back: where an edge passed a pixel's centre, a few
            // hundred at most; with the fills' depths as the rasteriser rounds them about half the picture
            std::size_t changed = 0;
            for (std::size_t i = 0; i + 3 < p.rgba.size(); i += 4) {
                for (std::size_t c = 0; c < 3; ++c) {
                    if (std::abs(int{p.rgba[i + c]} - int{last.rgba[i + c]}) > 8) {
                        ++changed;
                        break;
                    }
                }
            }
            most = std::max(most, changed);
            steps += std::format(" {}", changed);
            if (!out.empty()) CHECK(gpu::write_ppm(p, (fs::path(out) / std::format("order_{}.ppm", step)).string()));
            last = std::move(p);
        }
        MESSAGE(std::format("{} draws; pixels changed from the picture a centimetre back:{} of {}", f->draws.size(),
                            steps, all));
        CHECK(most * 200 < all);
    }
    std::error_code ec;
    fs::remove_all(guard, ec);
}

} // namespace atlas
