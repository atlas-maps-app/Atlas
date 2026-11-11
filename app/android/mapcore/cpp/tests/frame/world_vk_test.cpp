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
#include "labels/Names.hpp"
#include "load/PackStyle.hpp"
#include "load/Window.hpp"
#include "passes/Ground.hpp"
#include "passes/World.hpp"
#include "scene/Cells.hpp"
#include "scene/Cover.hpp"
#include "scene/Residency.hpp"
#include "scene/SunGlobals.hpp"
#include "scene/World.hpp"
#include "scene/WorldBend.hpp"
#include "scene/WorldNames.hpp"
#include "scene/WorldTiles.hpp"
#include "standin/Session.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/gpu/session_fixture.hpp"
#include "tests/tiles/world_support.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Container.hpp"
#include "tiles/ExpandedCache.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Source.hpp"
#include "tiles/Store.hpp"
#include "tiles/TileBox.hpp"
#include "tiles/WorldIndex.hpp"
#include "view/Bend.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"

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
#include <numbers>
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

/// sunny()'s moment with a magenta sky, so a pixel no world covers shows (the ground pass shades it with the sky).
[[nodiscard]] style::ThemeBlock magenta_sky() {
    style::ThemeBlock b = sunny();
    b.sky_top = {.r = 255, .g = 0, .b = 255, .a = 255};
    b.sky_horizon = b.sky_top;
    return b;
}

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

/// The world passes draw without the style ladder while one of these lives (passes::World::ladder_for_tests): a test of
/// the tiles' cover tells a pixel no tile draws by the sky's colour, which the ladder's space would lie over.
struct NoLadder {
    NoLadder() noexcept { passes::World::ladder_for_tests(false); }
    NoLadder(const NoLadder&) = delete;
    NoLadder& operator=(const NoLadder&) = delete;
    NoLadder(NoLadder&&) = delete;
    NoLadder& operator=(NoLadder&&) = delete;
    ~NoLadder() { passes::World::ladder_for_tests(true); }
};

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
                const std::uint64_t over =
                    scene::cells_overlapping(scene::world_cells_per_side, tiles::tile_box(ak), tiles::tile_box(k));
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
        // the L11 alone, over the floor's L3 where the ground bends (from 18 km: scene/WorldTiles.hpp)
        CHECK(std::ranges::all_of(f->draws, [](const scene::WorldDraw& d) {
            return d.tile->key.z == 11 || d.tile->key.z == scene::world_floor;
        }));
        CHECK(std::ranges::any_of(f->draws, [](const scene::WorldDraw& d) { return d.tile->key.z == 11; }));
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

namespace {

/// The frame's view of a camera state where no pack's ladder places it (frame/FrameBuilder.cpp's): its target at the
/// origin of the frame centred on it.
[[nodiscard]] view::View view_of(const style::CameraState& c) {
    const view::CameraSpec spec{.target = {},
                                .yaw = view::bearing_of(c.bearing),
                                .pitch = view::engine_pitch(c.pitch),
                                .dist = c.distance,
                                .fov = view::field_of_view,
                                .width = static_cast<double>(c.width_dp),
                                .height = static_cast<double>(c.height_dp),
                                .cx = std::nullopt,
                                .cy = c.principal_y,
                                .near = view::near_plane};
    return {spec, {.density = c.density, .width_px = c.width_px, .height_px = c.height_px, .quarter_turns = 0}};
}

/// scene/World.cpp's frame centred on a camera state's place.
[[nodiscard]] view::GroundFrame centred_on(const style::CameraState& c) {
    return {.lat0 = c.latitude,
            .lon0 = c.longitude,
            .kx = 111'320.0 * pymath::cos(pymath::radians(c.latitude)),
            .kz = 110'540.0};
}

/// The blank pixels within the disc of the sphere the ground bends to (its rays meet it more than 2 px inside its
/// limb): the clear colour's (green) or the sky's (magenta, magenta_sky's: the ground pass shades whatever no world
/// covers with the sky), and the disc's pixels.
struct DiscCount {
    std::size_t clear = 0;
    std::size_t disc = 0;
};

[[nodiscard]] DiscCount blank_in_disc(const gpu::Pixels& p, const view::View& v) {
    const view::Camera& c = v.camera();
    const view::Vec3& e = c.eye();
    const view::Vec3 to{-e.x, -view::earth_radius - e.y, -e.z}; // the sphere's centre from the eye
    const double d = std::sqrt((to.x * to.x) + (to.y * to.y) + (to.z * to.z));
    const double limb = d > view::earth_radius ? pymath::asin(view::earth_radius / d) : std::numbers::pi;
    const double margin = 2.0 / (c.focal() * v.device().density);
    DiscCount n;
    for (std::uint32_t y = 0; y < p.height; ++y) {
        for (std::uint32_t x = 0; x < p.width; ++x) {
            const double kx = ((((x + 0.5) / v.device().density) - c.cx()) / c.focal());
            const double ky = -((((y + 0.5) / v.device().density) - c.cy()) / c.focal());
            const view::Vec3 r{(c.right().x * kx) + (c.up().x * ky) + c.forward().x,
                               (c.right().y * kx) + (c.up().y * ky) + c.forward().y,
                               (c.right().z * kx) + (c.up().z * ky) + c.forward().z};
            const double len = std::sqrt((r.x * r.x) + (r.y * r.y) + (r.z * r.z));
            const double cosine = ((r.x * to.x) + (r.y * to.y) + (r.z * to.z)) / (len * d);
            if (pymath::acos(std::clamp(cosine, -1.0, 1.0)) >= limb - margin) continue;
            ++n.disc;
            const std::size_t i = 4 * ((std::size_t{y} * p.width) + x);
            const int r8 = p.rgba[i];
            const int g8 = p.rgba[i + 1];
            const int b8 = p.rgba[i + 2];
            if ((g8 > r8 + 50 && g8 > b8 + 50) || (r8 > g8 + 50 && b8 > g8 + 50)) ++n.clear;
        }
    }
    return n;
}

/// Draws `cam` until the map is complete and the world wants nothing more (at most four minutes).
void draw_world_complete(standin::Session& session, frame::MapFrame& map, const style::CameraState& cam) {
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

TEST_CASE("the bend: a sweep from 1 km to 60,000 km in 120 steps at Amsterdam, Tokyo, the open sea and 85 degrees "
          "south, a frame a step once the globe is resident, leaves no pixel of the bent ground blank") {
    const std::string world_dir = environment("ATLAS_WORLD_DIR");
    if (world_dir.empty()) {
        MESSAGE("ATLAS_WORLD_DIR is not set: the sweep is not drawn here");
        return;
    }
    if (!test::open_session()) return;
    const std::string out = environment("ATLAS_LOOK_OUT");
    std::vector<frame::MapPack> packs = world_packs(world_dir);
    ATLAS_REQUIRE(packs.size() > 1);
    const fs::path guard = fs::temp_directory_path() / std::format("atlas-sweep-{}", ::getpid());
    auto started = start_world(guard);
    if (!started) {
        // a Vulkan without a window system (CI's arm64 lavapipe) skips, as the map's other sessions do
        if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, started.error().reason);
        return;
    }
    {
        frame::MapFrame map(2, [] {}, {.cache_dir = guard, .clear = green_clear});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        CHECK(map.set_packs(std::move(packs)).find("world-globe listed") != std::string::npos);
        CHECK(session.map(&map).empty());
        auto window = std::make_unique<KeptWindow>();
        KeptWindow* kept = window.get();
        std::string why;
        ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
        // the tiles' cover alone: without the ladder a pixel no tile draws is the sky's, magenta
        const NoLadder cover_alone;
        map.set_theme(magenta_sky());
        struct Place {
            const char* name;
            double lat;
            double lon;
        };
        constexpr int steps = 120;
        for (const Place place :
             {Place{.name = "amsterdam", .lat = 52.37, .lon = 4.89},
              Place{.name = "tokyo", .lat = 35.68, .lon = 139.69}, Place{.name = "sea", .lat = 35.0, .lon = -40.0},
              Place{.name = "south", .lat = -85.0, .lon = 0.0}}) {
            // the globe resident and the place's tiles in at 20 km first, then out in a frame a step, moving
            style::CameraState cam = camera(place.lat, place.lon, 0.0, 0.0, 20'000.0);
            cam.pack_id = 0;
            draw_world_complete(session, map, cam);
            cam.flags = 0;
            std::size_t clear = 0;
            std::size_t disc = 0;
            double worst_at = 0;
            std::size_t worst = 0;
            std::string worst_draws;
            for (int i = 0; i <= steps; ++i) {
                cam.distance = 1'000.0 * pymath::pow(60'000.0, static_cast<double>(i) / steps);
                const standin::Drawn s = session.draw(cam, {});
                if (s.stale) session.remake();
                if (cam.distance <= scene::world_from) continue;
                ATLAS_REQUIRE(kept->kept != nullptr);
                auto pixels = kept->kept->read(kept->kept->latest());
                ATLAS_REQUIRE(pixels.has_value(), pixels.error().text());
                const DiscCount n = blank_in_disc(*pixels, view_of(cam));
                clear += n.clear;
                disc += n.disc;
                if (n.clear > worst) {
                    worst = n.clear;
                    worst_at = cam.distance;
                    // what the world drew in the worst frame, to tell why
                    const scene::WorldFrame* f = map.world()->world_tiles()->frame();
                    worst_draws.clear();
                    if (f != nullptr) {
                        for (const scene::WorldDraw& d : f->draws) {
                            worst_draws +=
                                std::format(" {}/{}/{}:{:x}{}{}", d.tile->key.z, d.tile->key.x, d.tile->key.y, d.cells,
                                            d.fade < 1.0F ? "~" : "", d.tile->cap != 0 ? "c" : "");
                        }
                        worst_draws += std::format(" (wanted {}, bare {})", f->wanted, f->bare);
                    }
                    if (!out.empty()) {
                        CHECK(gpu::write_ppm(*pixels,
                                             (fs::path(out) / std::format("sweep_{}_worst.ppm", place.name)).string()));
                    }
                }
                if (i % 12 == 0 && !out.empty()) {
                    CHECK(gpu::write_ppm(
                        *pixels,
                        (fs::path(out) / std::format("sweep_{}_{:.0f}.ppm", place.name, cam.distance)).string()));
                }
            }
            const scene::WorldStats st = map.world()->world_tiles()->stats();
            MESSAGE(std::format("{}: {} px of the bent ground's disc in the sweep's frames, {} of them blank (most {} "
                                "at {:.0f} m, drawing{}); {} tiles resident in {:.1f} MB",
                                place.name, disc, clear, worst, worst_at, worst_draws, st.resident,
                                static_cast<double>(st.bytes) / 1e6));
            CHECK(disc > 0);
            CHECK(clear == 0);
        }
    }
    std::error_code ec;
    fs::remove_all(guard, ec);
}

TEST_CASE(
    "the poles: from 2,500 km over 85 degrees north and south the caps beyond Mercator's 85.05 degrees are drawn, "
    "the sea in the north and Antarctica's land in the south, the pole itself in view") {
    const std::string world_dir = environment("ATLAS_WORLD_DIR");
    if (world_dir.empty()) {
        MESSAGE("ATLAS_WORLD_DIR is not set: the poles are not drawn here");
        return;
    }
    if (!test::open_session()) return;
    const std::string out = environment("ATLAS_LOOK_OUT");
    std::vector<frame::MapPack> packs = world_packs(world_dir);
    ATLAS_REQUIRE(packs.size() > 1);
    const fs::path guard = fs::temp_directory_path() / std::format("atlas-poles-{}", ::getpid());
    auto started = start_world(guard);
    if (!started) {
        // a Vulkan without a window system (CI's arm64 lavapipe) skips, as the map's other sessions do
        if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, started.error().reason);
        return;
    }
    {
        frame::MapFrame map(2, [] {}, {.cache_dir = guard, .clear = green_clear});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        CHECK(map.set_packs(std::move(packs)).find("world-globe listed") != std::string::npos);
        CHECK(session.map(&map).empty());
        auto window = std::make_unique<KeptWindow>();
        KeptWindow* kept = window.get();
        std::string why;
        ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
        // the tiles' cover alone, as the sweep's
        const NoLadder cover_alone;
        const style::ThemeBlock theme = magenta_sky();
        map.set_theme(theme);
        struct Pole {
            const char* name;
            double lat;
            double pitch;
            double bearing;
        };
        for (const Pole pole : {Pole{.name = "north", .lat = 85.0, .pitch = 0.0, .bearing = 0.0},
                                Pole{.name = "south", .lat = -85.0, .pitch = 0.0, .bearing = 0.0},
                                Pole{.name = "north_pitched", .lat = 85.0, .pitch = 45.0, .bearing = 0.0},
                                Pole{.name = "south_pitched", .lat = -85.0, .pitch = 45.0, .bearing = 180.0}}) {
            // 2,500 km: the pole, 5 degrees beyond the target, lies 12 degrees from the frame's middle (18 to its edge)
            style::CameraState cam = camera(pole.lat, 20.0, pole.bearing, pole.pitch, 2'500'000.0);
            cam.pack_id = 0;
            draw_world_complete(session, map, cam);
            CHECK(map.complete());
            ATLAS_REQUIRE(kept->kept != nullptr);
            auto pixels = kept->kept->read(kept->kept->latest());
            ATLAS_REQUIRE(pixels.has_value(), pixels.error().text());
            const view::View v = view_of(cam);
            const DiscCount n = blank_in_disc(*pixels, v);
            // the pole's own pixel: the cap's colour, the sea's in the north, the land's in the south (the style holds
            // no glacier yet: step 8's ladder gives it its colour)
            const scene::WorldBend bend = scene::world_bend(v, centred_on(cam));
            const view::Vec3 at = scene::world_point(bend, centred_on(cam), pole.lat > 0 ? 90.0 : -90.0, 20.0);
            const std::optional<view::Vec2> css = v.camera().proj(at); // on the bent ground already
            ATLAS_REQUIRE(css.has_value());
            const view::Vec2 s = v.device_point(*css);
            const auto px = static_cast<std::uint32_t>(std::clamp(s.x, 0.0, pixels->width - 1.0));
            const auto py = static_cast<std::uint32_t>(std::clamp(s.y, 0.0, pixels->height - 1.0));
            const std::size_t i = 4 * ((std::size_t{py} * pixels->width) + px);
            const style::Rgb8 want = pole.lat > 0 ? theme.water : theme.ground;
            const auto near = [](std::uint8_t a, std::uint8_t b) { return std::abs(int{a} - int{b}) <= 40; };
            const bool coloured =
                near(pixels->rgba[i], want.r) && near(pixels->rgba[i + 1], want.g) && near(pixels->rgba[i + 2], want.b);
            MESSAGE(std::format("{}: {} px of the disc, {} blank; the pole at ({:.0f}, {:.0f}) is #{:02X}{:02X}"
                                "{:02X}",
                                pole.name, n.disc, n.clear, s.x, s.y, pixels->rgba[i], pixels->rgba[i + 1],
                                pixels->rgba[i + 2]));
            CHECK(n.disc > 1000);
            CHECK(n.clear == 0);
            CHECK(coloured);
            if (!out.empty()) {
                CHECK(gpu::write_ppm(*pixels, (fs::path(out) / std::format("pole_{}.ppm", pole.name)).string()));
            }
        }
    }
    std::error_code ec;
    fs::remove_all(guard, ec);
}

namespace {

/// The globe's L0 tile as test::world_tile cuts it (cell c land west of (c % 3) / 2 of its width, sea east of it),
/// with two cities of twelve million in cell 6, at (25° N, 70° E) and (20° N, 10° E), in place of its village.
[[nodiscard]] test::TileBytes lit_globe_tile() {
    using namespace atlas::tiles;
    namespace col = test::columns;
    test::TileBytes tile = test::world_tile(0, 0, 0);
    test::LayerBytes label{.id = static_cast<std::uint8_t>(LayerId::label)};
    label.cells = {6, 6};
    const std::initializer_list<std::pair<std::int32_t, std::int32_t>> at = {{250'000'000, 700'000'000},
                                                                             {200'000'000, 100'000'000}};
    label.columns = {{"source", Codec::u8, col::u8({1, 1})},
                     {"index", Codec::delta, col::delta({0, 1})},
                     {"name", Codec::str, col::str(tile, {"East", "Middle"})},
                     {"pos", Codec::point, col::point(at)},
                     {"prev", Codec::point, col::point(at)},
                     {"count", Codec::varu, col::varu({1, 1})},
                     {"kind", Codec::str, col::str(tile, {"city", "city"})},
                     {"tag:population", Codec::str, col::str(tile, {"12000000", "12000000"})}};
    tile.layers.back() = label;
    return tile;
}

/// Whether (`lat`, `lon`) is land in test::world_tile's L0 tile: its cell's rows of equal latitude from Mercator's
/// 85.0511° N, its columns of 90°.
[[nodiscard]] bool test_land(double lat, double lon) {
    constexpr double top = 85.0511287798;
    const auto row = static_cast<int>(std::floor((top - lat) / (top / 2.0)));
    const auto column = static_cast<int>(std::floor((lon + 180.0) / 90.0));
    const int c = (std::clamp(row, 0, 3) * 4) + std::clamp(column, 0, 3);
    const double west = -180.0 + (90.0 * std::clamp(column, 0, 3));
    return lon < west + (90.0 * (c % 3) / 2.0);
}

/// A pixel's colour as 0-1 values.
[[nodiscard]] hlsl::float3 pixel_at(const gpu::Pixels& p, double x, double y) {
    const auto px = static_cast<std::size_t>(std::clamp(std::floor(x), 0.0, static_cast<double>(p.width - 1)));
    const auto py = static_cast<std::size_t>(std::clamp(std::floor(y), 0.0, static_cast<double>(p.height - 1)));
    const std::size_t i = 4 * ((py * p.width) + px);
    return {static_cast<float>(p.rgba[i]) / 255.0F, static_cast<float>(p.rgba[i + 1]) / 255.0F,
            static_cast<float>(p.rgba[i + 2]) / 255.0F};
}

/// The pixels of `p` that are the tests' clear colour itself, green (the board's land is too green for count_clear,
/// which also takes the clear colour through what lies faded over it: its #6E9E58 is green by 48 levels over its red).
[[nodiscard]] std::size_t count_bare(const gpu::Pixels& p) {
    std::size_t n = 0;
    for (std::size_t i = 0; i + 3 < p.rgba.size(); i += 4) {
        n += p.rgba[i + 1] >= 200 && p.rgba[i] <= 60 && p.rgba[i + 2] <= 60 ? 1U : 0U;
    }
    return n;
}

/// The most two colours part in a channel, in 8-bit levels.
[[nodiscard]] double levels_apart(hlsl::float3 a, hlsl::float3 b) {
    return 255.0 * static_cast<double>(std::max({std::abs(a.x - b.x), std::abs(a.y - b.y), std::abs(a.z - b.z)}));
}

} // namespace

TEST_CASE("the globe's look: from 54,000 km the engine draws the board's Earth, each pixel the ladder's colour of its "
          "place: the ramps by the surface's normal in the view, the night's caps by the sun's altitude there, the "
          "shine over them, a city's light in the night alone, and space, the ring and the rim about the disc") {
    if (!test::open_session()) return;
    const std::string out = environment("ATLAS_LOOK_OUT");
    const fs::path guard = fs::temp_directory_path() / std::format("atlas-globe-look-{}", ::getpid());
    auto started = start_world(guard);
    if (!started) {
        // a Vulkan without a window system (CI's arm64 lavapipe) skips, as the map's other sessions do
        if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, started.error().reason);
        return;
    }
    constexpr std::array<std::uint8_t, 6> coarse = {5, 4, 3, 2, 1, 0};
    const std::vector<std::byte> globe = test::world_pack({lit_globe_tile()}, coarse);
    {
        std::ofstream file(guard / tiles::world_globe_file, std::ios::binary);
        file.write(reinterpret_cast<const char*>(globe.data()), static_cast<std::streamsize>(globe.size())); // NOLINT
    }
    {
        frame::MapFrame map(2, [] {}, {.cache_dir = guard, .clear = green_clear});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        frame::MapPack base;
        base.number = 102;
        base.ref.id = std::string(tiles::world_globe_id);
        base.ref.kind = tiles::PackKind::world_base;
        base.ref.path = (guard / tiles::world_globe_file).string();
        std::vector<frame::MapPack> packs;
        packs.push_back(std::move(base));
        CHECK(map.set_packs(std::move(packs)).find("world-globe listed") != std::string::npos);
        CHECK(session.map(&map).empty());
        auto window = std::make_unique<KeptWindow>();
        KeptWindow* kept = window.get();
        std::string why;
        ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
        // a plain day without weather, so the frame is the world pass's colours alone; the sun low in the south-west
        // of the moment's place, the terminator across the disc
        style::ThemeBlock theme = magenta_sky();
        theme.flags = 0;
        theme.weather_code = 255;
        theme.cloud = theme.cloud_low = theme.wind = theme.gust = theme.wind_direction = 0.0;
        theme.temperature = theme.pressure = theme.visibility = theme.humidity = 0.0;
        theme.shadow_blur = theme.cloud_shadows = 0.0;
        map.set_theme(theme);
        map.set_screen(false, false, false);
        const style::CameraState cam = camera(20.0, 10.0, 0.0, 0.0, 54'000'000.0);
        draw_world_complete(session, map, cam);
        ATLAS_REQUIRE(kept->kept != nullptr);
        auto pixels = kept->kept->read(kept->kept->latest());
        ATLAS_REQUIRE(pixels.has_value(), pixels.error().text());
        if (!out.empty()) CHECK(gpu::write_ppm(*pixels, (fs::path(out) / "globe_look.ppm").string()));

        // the frame's look, as the world pass makes it of the world's style's ladder
        const view::View v = view_of(cam);
        const view::Camera& c = v.camera();
        const view::GroundFrame f = centred_on(cam);
        const scene::WorldBend bend = scene::world_bend(v, f);
        const tiles::Style* world_style = load::world_style();
        ATLAS_REQUIRE(world_style != nullptr);
        const std::optional<tiles::WorldLadder>& ladder = world_style->ladder;
        ATLAS_REQUIRE(ladder.has_value());
        const scene::WorldLook look =
            scene::world_look(*ladder, cam.distance, theme, bend, c.eye(), density, static_cast<double>(size.width),
                              static_cast<double>(size.height));
        const shaders::WorldLookBlock& k = look.block;
        CHECK(look.weights.caps == 1.0);
        CHECK(look.weights.ramps == 1.0);
        CHECK(look.weights.ring == 1.0);
        CHECK(look.weights.space == 1.0);

        const view::Vec3 sun = scene::direction_of(bend, look.sun.lat, look.sun.lon);
        // the colour the ladder gives the ground at a place, under the glass's shine: world.slang's colour_fs and
        // globe_fs on the CPU, away from the graticule's lines and the caps' edges
        const auto expected = [&](double lat, double lon) {
            const view::Vec3 n = scene::direction_of(bend, lat, lon);
            const hlsl::float2 on_disc = {
                static_cast<float>((n.x * c.right().x) + (n.y * c.right().y) + (n.z * c.right().z)),
                static_cast<float>((n.x * c.up().x) + (n.y * c.up().y) + (n.z * c.up().z))};
            hlsl::float3 d = shaders::globe_land(k, on_disc);
            if (!test_land(lat, lon)) {
                const hlsl::float2 from = on_disc - hlsl::float2{k.sea_at.x, k.sea_at.y};
                d = shaders::globe_sea(k, std::sqrt(hlsl::dot(from, from)));
            }
            const auto sine = static_cast<float>((n.x * sun.x) + (n.y * sun.y) + (n.z * sun.z));
            d = d + ((hlsl::float3{k.night.x, k.night.y, k.night.z} - d) * shaders::globe_night(k, sine, 1e-6F));
            const hlsl::float2 lit = on_disc - hlsl::float2{k.shine_at.x, k.shine_at.y};
            const float shine = hlsl::saturate(1.0F - (std::sqrt(hlsl::dot(lit, lit)) * k.shine_at.z)) * k.shine.w;
            return d + ((hlsl::float3{k.shine.x, k.shine.y, k.shine.z} - d) * shine);
        };
        const auto altitude = [&](double lat, double lon) {
            const view::Vec3 n = scene::direction_of(bend, lat, lon);
            return pymath::degrees(pymath::asin((n.x * sun.x) + (n.y * sun.y) + (n.z * sun.z)));
        };
        const auto on_screen = [&](double lat, double lon) {
            return c.pc(c.cam(scene::world_point(bend, f, lat, lon)));
        };
        struct Place {
            const char* name;
            double lat;
            double lon;
        };
        int lit_places = 0;
        int night_places = 0;
        for (const Place place : {Place{.name = "the middle's sea", .lat = 20.0, .lon = 14.0},
                                  Place{.name = "the north's land", .lat = 55.0, .lon = 20.0},
                                  Place{.name = "the west's land", .lat = 15.0, .lon = -40.0},
                                  Place{.name = "the south's land", .lat = -20.0, .lon = 20.0},
                                  Place{.name = "the south's sea", .lat = -20.0, .lon = 65.0},
                                  Place{.name = "the east's sea", .lat = 22.0, .lon = 66.0},
                                  Place{.name = "the north-east's land", .lat = 65.0, .lon = 75.0},
                                  Place{.name = "the far south's land", .lat = -45.0, .lon = 20.0}}) {
            const double alt = altitude(place.lat, place.lon);
            const view::Vec2 at = on_screen(place.lat, place.lon);
            const hlsl::float3 drawn = pixel_at(*pixels, at.x * density, at.y * density);
            const hlsl::float3 want = expected(place.lat, place.lon);
            MESSAGE(std::format(
                "{} ({:.0f}, {:.0f}): the sun {:.1f}° up, at ({:.1f}, {:.1f}) px, drawn #{:02X}{:02X}{:02X}, "
                "the ladder's #{:02X}{:02X}{:02X}",
                place.name, place.lat, place.lon, alt, at.x, at.y, std::lround(drawn.x * 255.0F),
                std::lround(drawn.y * 255.0F), std::lround(drawn.z * 255.0F), std::lround(want.x * 255.0F),
                std::lround(want.y * 255.0F), std::lround(want.z * 255.0F)));
            // an edge of a cap within a degree and a half is another pixel's
            bool edge = false;
            for (const double line : {0.0, -6.0, -12.0, -18.0}) edge = edge || std::abs(alt - line) < 1.5;
            if (edge) continue;
            CHECK_MESSAGE(levels_apart(drawn, want) <= 3.0, place.name);
            (alt > 0.0 ? lit_places : night_places) += 1;
        }
        CHECK(lit_places >= 2);
        CHECK(night_places >= 2);
        // the city in the night is lit, its light's colour at its place; the one by day is not
        const view::Vec2 east = on_screen(25.0, 70.0);
        const hlsl::float3 light = pixel_at(*pixels, east.x * density, east.y * density);
        const double east_alt = altitude(25.0, 70.0);
        MESSAGE(std::format("the city in the east: the sun {:.1f}° up, drawn #{:02X}{:02X}{:02X}", east_alt,
                            std::lround(light.x * 255.0F), std::lround(light.y * 255.0F),
                            std::lround(light.z * 255.0F)));
        REQUIRE(east_alt < -11.0);
        CHECK(levels_apart(light, hlsl::float3{k.light[0].x, k.light[0].y, k.light[0].z}) <= 3.0);
        const view::Vec2 middle = on_screen(20.0, 10.0);
        CHECK(levels_apart(pixel_at(*pixels, middle.x * density, middle.y * density), expected(20.0, 10.0)) <= 3.0);
        // about the disc: space and its glow in a corner, the ring at its brightest, the rim on the limb
        const double radius = c.focal() * view::earth_radius /
                              std::sqrt(((view::earth_radius + cam.distance) * (view::earth_radius + cam.distance)) -
                                        (view::earth_radius * view::earth_radius));
        const auto space_at = [&](double x, double y) {
            const hlsl::float2 from =
                hlsl::float2{static_cast<float>(x), static_cast<float>(y)} - hlsl::float2{k.space_at.x, k.space_at.y};
            const float glow = hlsl::saturate(std::sqrt(hlsl::dot(from, from)) * k.space_at.z / k.space_glow.w);
            const hlsl::float3 inner{k.space_glow.x, k.space_glow.y, k.space_glow.z};
            return inner + ((hlsl::float3{k.space.x, k.space.y, k.space.z} - inner) * glow);
        };
        CHECK(levels_apart(pixel_at(*pixels, 4.5, 4.5), space_at(4.5, 4.5)) <= 2.0);
        CHECK(levels_apart(pixel_at(*pixels, 355.5, 630.5), space_at(355.5, 630.5)) <= 2.0);
        const double ring_x = (c.cx() + (radius * static_cast<double>(k.air_at.y))) * density;
        const double ring_y = c.cy() * density;
        const hlsl::float3 behind = space_at(ring_x, ring_y);
        const hlsl::float3 ring = behind + ((hlsl::float3{k.air.x, k.air.y, k.air.z} - behind) * k.air.w);
        CHECK(levels_apart(pixel_at(*pixels, ring_x, ring_y), ring) <= 6.0);
        const hlsl::float3 rim = pixel_at(*pixels, (c.cx() - radius) * density, c.cy() * density);
        const hlsl::float3 inside = pixel_at(*pixels, (c.cx() - radius + 4.0) * density, c.cy() * density);
        const hlsl::float3 outside = pixel_at(*pixels, (c.cx() - radius - 4.0) * density, c.cy() * density);
        MESSAGE(std::format("the disc {:.1f} dp in radius; the rim's red {:.0f}, {:.0f} inside it and {:.0f} outside",
                            radius, rim.x * 255.0F, inside.x * 255.0F, outside.x * 255.0F));
        (void)inside;
        // the rim's #CFE0FF at .55 over whatever lies under it; the ring's blue just outside it
        CHECK(rim.x >= 110.0F / 255.0F);
        CHECK(rim.z >= 140.0F / 255.0F);
        CHECK(outside.x < 60.0F / 255.0F);
        CHECK(outside.z > outside.x + 0.05F);
        // no pixel of the frame is the clear colour's or the sky's (magenta)
        CHECK(count_bare(*pixels) == 0);
        std::size_t sky = 0;
        for (std::size_t i = 0; i + 3 < pixels->rgba.size(); i += 4) {
            const int r8 = pixels->rgba[i];
            const int g8 = pixels->rgba[i + 1];
            const int b8 = pixels->rgba[i + 2];
            sky += r8 > g8 + 50 && b8 > g8 + 50 ? 1U : 0U;
        }
        CHECK(sky == 0);
    }
    std::error_code ec;
    fs::remove_all(guard, ec);
}

TEST_CASE("the ladder: the world at its knots over the Netherlands, by day and at night, straight down and pitched, "
          "when ATLAS_WORLD_DIR names it") {
    const std::string world_dir = environment("ATLAS_WORLD_DIR");
    if (world_dir.empty()) {
        MESSAGE("ATLAS_WORLD_DIR is not set: the ladder's knots are not drawn here");
        return;
    }
    if (!test::open_session()) return;
    const std::string out = environment("ATLAS_LOOK_OUT");
    std::vector<frame::MapPack> packs = world_packs(world_dir);
    ATLAS_REQUIRE(packs.size() > 1);
    const fs::path guard = fs::temp_directory_path() / std::format("atlas-ladder-{}", ::getpid());
    auto started = start_world(guard);
    if (!started) {
        if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, started.error().reason);
        return;
    }
    {
        frame::MapFrame map(2, [] {}, {.cache_dir = guard, .clear = green_clear});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        CHECK(map.set_packs(std::move(packs)).find("world-globe listed") != std::string::npos);
        CHECK(session.map(&map).empty());
        auto window = std::make_unique<KeptWindow>();
        KeptWindow* kept = window.get();
        std::string why;
        ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
        // the day's moment, and a night's: the sun 30° under the moment's place's horizon in the north
        const style::ThemeBlock day = sunny();
        style::ThemeBlock night = sunny();
        night.flags = style::theme_flag::night;
        night.weather_code = 255;
        night.cloud = night.cloud_low = night.wind = night.gust = night.wind_direction = 0.0;
        night.temperature = night.pressure = night.visibility = night.humidity = 0.0;
        night.shadow_blur = night.cloud_shadows = 0.0;
        night.ground = {.r = 0x12, .g = 0x15, .b = 0x1F, .a = 255};
        night.road = {.r = 0x1B, .g = 0x1F, .b = 0x2C, .a = 255};
        night.water = {.r = 0x07, .g = 0x0C, .b = 0x14, .a = 255};
        night.sky_top = {.r = 0x0A, .g = 0x0E, .b = 0x1C, .a = 255};
        night.sky_horizon = {.r = 0x1A, .g = 0x22, .b = 0x3C, .a = 255};
        night.haze = {.r = 0x16, .g = 0x1D, .b = 0x36, .a = 255};
        night.sun_vector = {0.0, -1.0, 0.0};
        night.sun_azimuth = 350.0;
        night.sun_elevation = -30.0;
        night.shadow_opacity = 0.0;
        struct Knot {
            const char* name;
            double distance;
            double pitch;
        };
        for (const bool dark : {false, true}) {
            map.set_theme(dark ? night : day);
            for (const Knot knot : {Knot{.name = "18", .distance = 18'500.0, .pitch = 0.0},
                                    Knot{.name = "37", .distance = 37'000.0, .pitch = 0.0},
                                    Knot{.name = "75", .distance = 75'000.0, .pitch = 0.0},
                                    Knot{.name = "75p", .distance = 75'000.0, .pitch = 60.0},
                                    Knot{.name = "300p", .distance = 300'000.0, .pitch = 60.0},
                                    Knot{.name = "600", .distance = 600'000.0, .pitch = 0.0},
                                    Knot{.name = "1000p", .distance = 1'000'000.0, .pitch = 55.0},
                                    Knot{.name = "2000", .distance = 2'000'000.0, .pitch = 0.0},
                                    Knot{.name = "3500p", .distance = 3'500'000.0, .pitch = 40.0},
                                    Knot{.name = "6000", .distance = 6'000'000.0, .pitch = 0.0},
                                    Knot{.name = "20000", .distance = 20'000'000.0, .pitch = 0.0},
                                    Knot{.name = "54000", .distance = 54'000'000.0, .pitch = 0.0}}) {
                const style::CameraState cam = camera(52.37, 4.89, 0.0, knot.pitch, knot.distance);
                // until the world wants nothing more, or half a minute (a block's packs may lie beyond the folder's)
                const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(30);
                int frames = 0;
                while (std::chrono::steady_clock::now() < until) {
                    const standin::Drawn drawn = session.draw(cam, {});
                    if (drawn.stale) session.remake();
                    frames += drawn.drawn ? 1 : 0;
                    scene::WorldTiles* tiles = map.world() != nullptr ? map.world()->world_tiles() : nullptr;
                    const scene::WorldFrame* frame = tiles != nullptr ? tiles->frame() : nullptr;
                    if (map.complete() && frame != nullptr && frame->wanted == 0 && !frame->fading && frames > 2) break;
                    if (tiles != nullptr) tiles->wait_idle();
                }
                ATLAS_REQUIRE(kept->kept != nullptr);
                auto pixels = kept->kept->read(kept->kept->latest());
                ATLAS_REQUIRE(pixels.has_value(), pixels.error().text());
                const scene::WorldFrame* f = map.world()->world_tiles()->frame();
                ATLAS_REQUIRE(f != nullptr);
                std::uint64_t lights = 0;
                for (const scene::WorldDraw& d : f->draws) lights += d.light_cells != 0 ? d.tile->lights : 0U;
                MESSAGE(std::format("{} {} km{}: {} draws, {} lights' places, {} wanted, {} px the clear colour's",
                                    dark ? "night" : "day", knot.name, knot.pitch > 0 ? " pitched" : "",
                                    f->draws.size(), lights, f->wanted, count_bare(*pixels)));
                CHECK(count_bare(*pixels) == 0);
                if (!out.empty()) {
                    CHECK(gpu::write_ppm(
                        *pixels,
                        (fs::path(out) / std::format("ladder_{}_{}.ppm", dark ? "night" : "day", knot.name)).string()));
                }
            }
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

TEST_CASE("the world under the cover: where the camera's place has no country's levels its fine packs' tiles draw "
          "from every distance, the plain land where it has none, and a window's picture is final without them") {
    if (!test::open_session()) return;
    const fs::path guard = fs::temp_directory_path() / std::format("atlas-world-under-{}", ::getpid());
    fs::create_directories(guard);
    // Amsterdam's L6 block (32, 21) with its L9 to L11 tiles over the city, which the app brings by ranges, and the
    // globe's L0 and L5 tiles over it, a file on the phone (the world by ranges' packs)
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
    // a window in the L11 tile's middle, a hall of four floors 40 m square in its own middle: a place the phone drew
    // before
    const tiles::TileKey place = tiles::tile_of(14, 523'300'000, 48'300'000);
    const std::array<std::int32_t, 4> pb = tiles::tile_box(place);
    const double hall_lat = (pb[0] + pb[2]) / 2e7;
    const double hall_lon = (pb[1] + pb[3]) / 2e7;
    frame::MapPack window;
    {
        const double dlat = 40.0 / 110540.0;
        const double dlon = 40.0 / (111320.0 * pymath::cos(pymath::radians(hall_lat)));
        std::string geometry;
        for (const auto& [a, o] : {std::pair{0.0, 0.0}, {0.0, dlon}, {dlat, dlon}, {dlat, 0.0}, {0.0, 0.0}}) {
            geometry += std::format(R"({}{{"lat":{:.7f},"lon":{:.7f}}})", geometry.empty() ? "" : ",", hall_lat + a,
                                    hall_lon + o);
        }
        const std::string json = std::format(
            R"({{"version":0.6,"elements":[{{"type":"way","id":7,"nodes":[1,2,3,4,1],"tags":{{"building":"yes",)"
            R"("building:levels":"4"}},"geometry":[{}]}}]}})",
            geometry);
        auto built =
            load::build_window(std::as_bytes(std::span(json.data(), json.size())), place, load::place_of(place));
        ATLAS_REQUIRE(built.has_value(), built.error().what);
        const fs::path file = guard / "window.atlas";
        std::ofstream out(file, std::ios::binary);
        out.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
                  static_cast<std::streamsize>(built->pack.size()));
        window.number = 7;
        window.ref.id = std::format("window/14/{}/{}", place.x, place.y);
        window.ref.kind = tiles::PackKind::window;
        window.ref.path = file.string();
    }
    // the head as WorldFetch.head brings it: the block's bytes before its chunks and after them
    const std::span<const std::byte> all(block);
    auto whole = tiles::Pack::open(std::make_unique<tiles::MemorySource>(all));
    ATLAS_REQUIRE(whole);
    const tiles::Section* chunks = tiles::find_section(whole->sections(), "chunks");
    ATLAS_REQUIRE(chunks != nullptr);
    const std::uint64_t chunks_end = chunks->offset + chunks->stored;
    const std::array<tiles::PackBytes, 2> head = {
        {{.offset = 0, .bytes = all.first(chunks->offset)}, {.offset = chunks_end, .bytes = all.subspan(chunks_end)}}};
    auto started = start_world(guard);
    if (!started) {
        if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, started.error().reason);
        return;
    }
    {
        frame::MapFrame map(2, [] {}, {.cache_dir = guard, .clear = green_clear});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        map.set_world_cache(cache);
        ATLAS_REQUIRE(tiles::Store::keep_head(*cache, remote.ref, head, load::world_style()));
        const auto packs = [&](bool with_window) {
            std::vector<frame::MapPack> out{remote, base};
            if (with_window) out.push_back(window);
            return out;
        };
        CHECK(map.set_packs(packs(false)).find("world-globe listed") != std::string::npos);
        CHECK(session.map(&map).empty());
        auto kept_window = std::make_unique<KeptWindow>();
        KeptWindow* kept = kept_window.get();
        std::string why;
        ATLAS_REQUIRE(session.attach(std::move(kept_window), size, why), why);
        map.set_theme(sunny());
        const std::shared_ptr<const tiles::StorePack> pack = map.world_pack(remote.ref.id);
        ATLAS_REQUIRE(pack != nullptr);
        passes::World::paint_for_tests(magenta);
        const auto frame_of = [&]() -> const scene::WorldFrame* {
            const scene::WorldTiles* tiles = map.world() != nullptr ? map.world()->world_tiles() : nullptr;
            return tiles != nullptr ? tiles->frame() : nullptr;
        };
        // draws `cam` until `done` holds, at most a minute
        const auto draw_until = [&](const style::CameraState& cam, const auto& done) {
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
        const auto land_draws = [](const scene::WorldFrame& f) {
            return std::ranges::count_if(f.draws, [](const scene::WorldDraw& d) { return d.tile->blank; });
        };
        const auto finest = [](const scene::WorldFrame& f) {
            unsigned z = 0;
            for (const scene::WorldDraw& d : f.draws) z = std::max(z, unsigned{d.tile->key.z});
            return z;
        };
        // the last frame's pixels in the world's test paint (the moment's weather and grain lie over it), and in the
        // clear colour; with ATLAS_LOOK_OUT, the frame written as <out>/under_<name>.ppm
        const std::string look = environment("ATLAS_LOOK_OUT");
        const auto pixels_of = [&](const char* name) -> std::pair<std::size_t, std::size_t> {
            auto pixels = kept->kept->read(kept->kept->latest());
            ATLAS_REQUIRE_OR_RETURN((std::pair<std::size_t, std::size_t>{0, 0}), pixels.has_value(),
                                    pixels.error().text());
            if (!look.empty()) {
                CHECK(gpu::write_ppm(*pixels, (fs::path(look) / std::format("under_{}.ppm", name)).string()));
            }
            return {count_near(*pixels, magenta_rgb, 60), count_clear(*pixels)};
        };
        constexpr std::size_t frame_pixels = std::size_t{size.width} * size.height;
        // the app's part (WorldReads), where a case lets the tiles come: each chunk the view wants kept
        tiles::Zstd zstd;
        const auto keep_wanted = [&] {
            for (const scene::WorldWant& w : map.world_wants()) {
                (void)tiles::Store::keep(*cache, *pack, {.offset = w.offset, .bytes = all.subspan(w.offset, w.length)},
                                         zstd);
            }
        };
        // 500 m over the hall, straight down, with no pack but the world's. Without the flag the world draws nothing
        // up to 18 km, as before
        style::CameraState cam = camera(hall_lat, hall_lon, 0.0, 0.0, 500.0);
        for (int i = 0; i < 3; ++i) (void)session.draw(cam, {});
        CHECK(frame_of() == nullptr);
        CHECK(map.world_wants().empty());
        // with it, the L11 tile holding the place is asked for, and the plain land lies at its box meanwhile: no
        // coarser tile stands in (the globe's tiles do only beyond 18 km), and the picture waits
        cam.flags |= style::camera_flag::world_under;
        CHECK(draw_until(cam, [&] { return frame_of() != nullptr && !map.world_wants().empty(); }));
        {
            const scene::WorldFrame* f = frame_of();
            ATLAS_REQUIRE(f != nullptr);
            CHECK(f->under);
            CHECK(f->wanted > 0);
            CHECK(land_draws(*f) > 0);
            CHECK(std::cmp_equal(land_draws(*f), f->draws.size()));
            CHECK_FALSE(map.complete());
            const auto [world, clear] = pixels_of("land");
            MESSAGE(
                std::format("500 m over the hall with no tile of the world's yet: {} draws of the plain land, {} of "
                            "{} px in the world's paint, {} the clear colour",
                            land_draws(*f), world, frame_pixels, clear));
            CHECK(world > frame_pixels * 8 / 10); // the land, in the world's test paint
            CHECK(clear == 0);
        }
        // the window listed and named: its tiles are the picture, final though the world's tile has not come (a place
        // drawn before the world was fetched, offline), with the plate under the window alone
        MESSAGE(map.set_packs(packs(true)));
        cam.pack_id = window.number;
        CHECK(draw_until(cam, [&] { return map.complete(); }));
        {
            const scene::WorldFrame* f = frame_of();
            ATLAS_REQUIRE(f != nullptr);
            CHECK(f->under);
            CHECK(f->wanted > 0);
            CHECK_FALSE(map.world()->cover()->tiles().empty());
            const auto [world, clear] = pixels_of("window");
            MESSAGE(std::format("500 m over the hall, its window drawn and the world's tile absent: {} of {} px the "
                                "world's land, {} the clear colour",
                                world, frame_pixels, clear));
            CHECK(world == 0); // the view lies within the window's tile: its plate and its hall cover the land
            CHECK(clear == 0);
        }
        // the world's chunks come: the L11 tile fades in over the land and draws alone
        CHECK(draw_until(cam, [&] {
            keep_wanted();
            const scene::WorldFrame* f = frame_of();
            return f != nullptr && f->wanted == 0 && !f->fading && map.world_wants().empty();
        }));
        {
            const scene::WorldFrame* f = frame_of();
            ATLAS_REQUIRE(f != nullptr);
            CHECK(f->under);
            CHECK(finest(*f) == 11);
            CHECK(land_draws(*f) == 0);
            CHECK(map.complete());
            CHECK(map.world()->tiles_line().find(" under land 0") != std::string::npos);
        }
        // 3 km over the L9 tile's west edge (4.21875° east): east of it the block's L9 tile draws its cells, west of it
        // the block holds nothing (the globe's L5 does), so the plain land lies there, not the globe's coast
        cam = camera(52.33, 4.21875, 0.0, 0.0, 3'000.0);
        cam.flags |= style::camera_flag::world_under;
        CHECK(draw_until(cam, [&] {
            keep_wanted();
            const scene::WorldFrame* f = frame_of();
            return f != nullptr && f->wanted == 0 && !f->fading && map.world_wants().empty();
        }));
        {
            const scene::WorldFrame* f = frame_of();
            ATLAS_REQUIRE(f != nullptr);
            CHECK(f->under);
            CHECK(land_draws(*f) > 0);
            CHECK(finest(*f) == 9);
            CHECK(std::ranges::none_of(f->draws, [](const scene::WorldDraw& d) {
                return !d.tile->blank && d.tile->key.z < scene::world_fine_first;
            }));
            const auto [world, clear] = pixels_of("edge");
            CHECK(world > frame_pixels * 8 / 10);
            CHECK(clear == 0);
        }
        // 20 km further west no tile in view has a fine one: nothing is drawn up to 18 km, as without the flag
        cam = camera(52.33, 3.92, 0.0, 0.0, 3'000.0);
        cam.flags |= style::camera_flag::world_under;
        for (int i = 0; i < 3; ++i) (void)session.draw(cam, {});
        CHECK(frame_of() == nullptr);
        // and beyond 18 km the world draws as it does everywhere, whatever the flag
        cam = camera(hall_lat, hall_lon, 0.0, 0.0, 30'000.0);
        cam.flags |= style::camera_flag::world_under;
        CHECK(draw_until(cam, [&] {
            keep_wanted();
            const scene::WorldFrame* f = frame_of();
            return f != nullptr && f->wanted == 0 && !f->fading && map.world_wants().empty();
        }));
        {
            const scene::WorldFrame* f = frame_of();
            ATLAS_REQUIRE(f != nullptr);
            CHECK_FALSE(f->under);
            CHECK(land_draws(*f) == 0);
        }
        passes::World::paint_for_tests(0);
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

TEST_CASE("the world's names: the frame over the globe asks for its places' names by rank, in the look of its "
          "distance, and draws the sprites handed where the world pass draws their places") {
    if (!test::open_session()) return;
    const std::string out = environment("ATLAS_LOOK_OUT");
    const fs::path guard = fs::temp_directory_path() / std::format("atlas-world-names-{}", ::getpid());
    auto started = start_world(guard);
    if (!started) {
        if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, started.error().reason);
        return;
    }
    constexpr std::array<std::uint8_t, 6> coarse = {5, 4, 3, 2, 1, 0};
    const std::vector<std::byte> globe = test::world_pack({lit_globe_tile()}, coarse);
    {
        std::ofstream file(guard / tiles::world_globe_file, std::ios::binary);
        file.write(reinterpret_cast<const char*>(globe.data()), static_cast<std::streamsize>(globe.size())); // NOLINT
    }
    {
        frame::MapFrame map(2, [] {}, {.cache_dir = guard, .clear = green_clear});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        frame::MapPack base;
        base.number = 102;
        base.ref.id = std::string(tiles::world_globe_id);
        base.ref.kind = tiles::PackKind::world_base;
        base.ref.path = (guard / tiles::world_globe_file).string();
        std::vector<frame::MapPack> packs;
        packs.push_back(std::move(base));
        CHECK(map.set_packs(std::move(packs)).find("world-globe listed") != std::string::npos);
        CHECK(session.map(&map).empty());
        auto window = std::make_unique<KeptWindow>();
        KeptWindow* kept = window.get();
        std::string why;
        ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
        style::ThemeBlock theme = magenta_sky();
        theme.flags = 0;
        theme.weather_code = 255;
        theme.cloud = theme.cloud_low = theme.wind = theme.gust = theme.wind_direction = 0.0;
        theme.temperature = theme.pressure = theme.visibility = theme.humidity = 0.0;
        theme.shadow_blur = theme.cloud_shadows = 0.0;
        map.set_theme(theme);
        map.set_screen(false, false, false);
        // The tile's two cities, at (25 N, 70 E) and (20 N, 10 E), from over a place between them; each sprite a block
        // of one colour no pixel of the world has.
        const auto sprite = [](std::uint32_t id, std::uint32_t colour) {
            labels::LabelSprite made{
                .id = id, .measures = {.half = 20.0F + 8.0F, .pad = 8.0F, .text_height = 16.0F, .baseline = -4.0F}};
            made.own = {.width = 40,
                        .height = 16,
                        .pixels = std::vector<std::uint32_t>(std::size_t{40} * 16, colour),
                        .anchor_x = 20,
                        .anchor_y = 12};
            return made;
        };
        const auto count = [](const gpu::Pixels& p, std::uint8_t r, std::uint8_t g, std::uint8_t b) {
            std::size_t n = 0;
            for (std::size_t i = 0; i + 3 < p.rgba.size(); i += 4) {
                n += p.rgba[i] == r && p.rgba[i + 1] == g && p.rgba[i + 2] == b ? 1U : 0U;
            }
            return n;
        };
        struct Place {
            const char* name;
            double lat;
            double lon;
            std::uint32_t sprite; // its block's colour, premultiplied, R in the low byte
            std::array<std::uint8_t, 3> rgb;
        };
        const std::array<Place, 2> places{
            Place{.name = "East", .lat = 25.0, .lon = 70.0, .sprite = 0xFF'F0'10'F0U, .rgb = {0xF0, 0x10, 0xF0}},
            Place{.name = "Middle", .lat = 20.0, .lon = 10.0, .sprite = 0xFF'10'F0'F0U, .rgb = {0xF0, 0xF0, 0x10}}};
        struct Look {
            const char* name;
            style::CameraState camera;
            scene::NameStyle style;
            std::size_t named;
        };
        // From 5,000 km over the eastern city: it alone stands on the frame, in a city's look. From 18,000 km over a
        // place between the two, the frame's long side along the parallel: both, in the globe's look.
        const std::array<Look, 2> looks{Look{.name = "5000 km",
                                             .camera = camera(25.0, 70.0, 0.0, 0.0, 5'000'000.0),
                                             .style = scene::NameStyle::city,
                                             .named = 1},
                                        Look{.name = "18000 km",
                                             .camera = camera(22.0, 40.0, 90.0, 0.0, 18'000'000.0),
                                             .style = scene::NameStyle::globe,
                                             .named = 2}};
        std::uint32_t before = 0;
        double asked = 0.0;
        for (const Look& look : looks) {
            const style::CameraState& cam = look.camera;
            draw_world_complete(session, map, cam);
            // the places that stand on the frame, where the world pass draws them
            const view::View v = view_of(cam);
            const view::GroundFrame f = centred_on(cam);
            const scene::WorldBend bend = scene::world_bend(v, f);
            std::vector<std::pair<const Place*, view::Vec2>> standing;
            for (const Place& p : places) {
                const auto at = scene::name_point(bend, f, v, p.lat, p.lon, 0.1);
                if (at && at->x >= 0.0 && at->y >= 0.0 && at->x <= size.width && at->y <= size.height) {
                    standing.emplace_back(&p, *at);
                }
            }
            CHECK(standing.size() == look.named);
            // they are wanted, in the tile's rank (their people are the same), in the distance's look
            asked += 100.0;
            const std::vector<labels::NameWant> wants = map.names().ask(asked);
            ATLAS_REQUIRE(wants.size() == standing.size());
            ATLAS_REQUIRE(!wants.empty());
            std::vector<labels::LabelSprite> sprites;
            for (std::size_t i = 0; i < wants.size(); ++i) {
                CHECK(wants[i].text == standing[i].first->name);
                CHECK(wants[i].style == static_cast<std::uint8_t>(look.style));
                CHECK((wants[i].id & scene::name_id_bit) != 0);
                sprites.push_back(sprite(wants[i].id, standing[i].first->sprite));
            }
            CHECK(wants[0].id != before); // another look is another id
            before = wants[0].id;
            ATLAS_REQUIRE(map.names().give(std::move(sprites), 0.0F, 0.0F, map.seconds()).has_value());
            // drawn until the fades are over
            for (int n = 0; n < 400 && (n < 3 || map.animating()); ++n) (void)session.draw(cam, {});
            ATLAS_REQUIRE(kept->kept != nullptr);
            auto pixels = kept->kept->read(kept->kept->latest());
            ATLAS_REQUIRE(pixels.has_value(), pixels.error().text());
            if (!out.empty()) {
                CHECK(gpu::write_ppm(*pixels, (fs::path(out) / std::format("names_{}.ppm", look.name)).string()));
            }
            // each sprite whole, at its own size, on whole pixels, centred on its place's point
            for (const auto& [place, at] : standing) {
                const std::size_t block = count(*pixels, place->rgb[0], place->rgb[1], place->rgb[2]);
                MESSAGE(std::format("names from {}: {}'s sprite {} px of 640, its place at ({:.1f}, {:.1f})", look.name,
                                    place->name, block, at.x, at.y));
                CHECK(block == 640);
                const hlsl::float3 colour = {static_cast<float>(place->rgb[0]) / 255.0F,
                                             static_cast<float>(place->rgb[1]) / 255.0F,
                                             static_cast<float>(place->rgb[2]) / 255.0F};
                CHECK(levels_apart(pixel_at(*pixels, at.x, at.y), colour) < 1.0);
            }
            CHECK(map.names().ask(1e9).empty());
        }
    }
    std::error_code ec;
    fs::remove_all(guard, ec);
}

} // namespace atlas
