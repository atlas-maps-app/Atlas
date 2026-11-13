// The relief's light on the app's map (ENGINE.md part 6, terrain, step 2; passes/Relief.hpp, scene/Relief.hpp), drawn
// on the host's Vulkan through the session, as the phone draws: a terrain pack listed with the map's packs lights the
// ground where its rasters are, each pixel the frame without it times the twin's light at its ground point (the
// shader's own text on the CPU, fx/Relief.hpp, over the baked texels between the samples); a tile of a finer raster
// and its neighbours of a coarser one's parts; with the terrain switched off the frame is byte for byte the frame
// before the pack was listed; the sky-view's switch; what the relief says of itself (its blocks, its sources, nothing
// wanted of the app for a pack on the phone).
//
// With ATLAS_RELIEF_PACKS (a folder of terrain packs, *.atlas) and ATLAS_RELIEF_OUT (a folder), a last case draws the
// boards' views of the dunes and San Gimignano over them, with the terrain off, on, and on without the sky-view, and
// writes each frame as a PPM: the pictures the look is judged by.
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/MapFrame.hpp"
#include "fx/Relief.hpp"
#include "gpu/Guard.hpp"
#include "gpu/Offscreen.hpp"
#include "passes/Relief.hpp"
#include "scene/Relief.hpp"
#include "scene/World.hpp"
#include "standin/Session.hpp"
#include "tests/base/require.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/gpu/session_fixture.hpp"
#include "tests/tiles/terrain_support.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <format>
#include <fstream>
#include <memory>
#include <numbers>
#include <optional>
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

/// A fresh folder, removed when the test case ends.
class Folder {
public:
    explicit Folder(std::string_view name)
        : path_(fs::temp_directory_path() / std::format("atlas-relief-{}-{}", name, ::getpid())) {
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
            .build = "relief-test",
            .validation = true,
            .require_validation = test::vulkan_required(),
            .present = true,
            .spirv = 42};
}

[[nodiscard]] bool can_start(const fs::path& dir) {
    const auto probe = gpu::start_guarded(start_in(dir));
    if (probe) return true;
    if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, probe.error().reason);
    return false;
}

/// A clear day's moment (the Dunes board's: 1 October 2026, 16:00) or an overcast one, without the screen's weather.
[[nodiscard]] style::ThemeBlock moment(bool overcast) {
    style::ThemeBlock b{};
    b.version = style::theme_block_version;
    b.flags = flag::water_gradient | (overcast ? flag::overcast : 0U);
    b.year = 2026;
    b.month = 10;
    b.day = 1;
    b.hour = 16;
    b.minute = 0;
    b.moment = overcast ? style::MomentName::grey : style::MomentName::sunny;
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
    b.sun_k = overcast ? 0.14 : 1.0;
    b.amb_k = overcast ? 0.84 : 0.6;
    b.sky_k = overcast ? 1.7 : 1.0;
    b.shadow_opacity = 0.32;
    // the board's sun: 26 degrees up from azimuth 222
    const double az = pymath::radians(222.0);
    const double el = pymath::radians(26.0);
    b.sun_vector = {std::sin(az) * std::cos(el), std::sin(el), -std::cos(az) * std::cos(el)};
    b.sun_azimuth = 222.0;
    b.sun_elevation = 26.0;
    b.visibility = 24000.0;
    return b;
}

constexpr vk::Extent2D size{.width = 240, .height = 320};

/// A camera looking at (`lat`, `lon`) from `distance` metres, `bearing` degrees from north and `pitch` degrees from
/// straight down, on a window of `extent` px at density 1, naming no pack.
[[nodiscard]] style::CameraState camera_at(double lat, double lon, double bearing, double pitch, double distance,
                                           vk::Extent2D extent = size) {
    style::CameraState c{};
    c.version = style::camera_state_version;
    c.latitude = lat;
    c.longitude = lon;
    c.bearing = bearing;
    c.pitch = pitch;
    c.distance = distance;
    c.principal_x = extent.width / 2.0;
    c.principal_y = extent.height / 2.0;
    c.density = 1.0;
    c.width_px = extent.width;
    c.height_px = extent.height;
    c.width_dp = extent.width;
    c.height_dp = extent.height;
    c.flags = style::camera_flag::rest;
    // the clouds' shadows are the screen's own: the frames compared differ by the relief alone
    c.parts = style::camera_part::cloud_shadows;
    return c;
}

/// A window whose frames the test reads back: offscreen images it keeps hold of.
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

/// The map's relief pass.
[[nodiscard]] const passes::Relief* relief_pass(const frame::MapFrame& map) {
    const frame::FrameBuilder* b = map.builder();
    if (b == nullptr) return nullptr;
    for (const std::unique_ptr<frame::Pass>& p : b->passes()) {
        // NOLINTNEXTLINE(cppcoreguidelines-pro-type-static-cast-downcast): "relief" is passes::Relief
        if (p->name() == "relief") return static_cast<const passes::Relief*>(p.get());
    }
    return nullptr;
}

/// Draws `camera` until the relief wants nothing more and nothing fades (every raster in view baked, sent up and
/// faded in), at most `seconds`; the frame then drawn, read back, or nothing.
[[nodiscard]] std::optional<gpu::Pixels> draw_lit(standin::Session& session, frame::MapFrame& map, KeptWindow& window,
                                                  const style::CameraState& camera, double seconds = 240.0) {
    const auto until = std::chrono::steady_clock::now() + std::chrono::duration<double>(seconds);
    int still = 0;
    while (std::chrono::steady_clock::now() < until) {
        const standin::Drawn d = session.draw(camera, {});
        if (d.stale) session.remake();
        scene::ReliefTiles* tiles = map.world() != nullptr ? map.world()->relief_tiles() : nullptr;
        if (tiles != nullptr) tiles->wait_idle();
        const bool wanting = tiles != nullptr && tiles->frame().wanted != 0;
        still = d.drawn && !wanting && !map.animating() ? still + 1 : 0;
        if (still < 2 || window.kept == nullptr) continue;
        auto pixels = window.kept->read(window.kept->latest());
        if (!pixels) return std::nullopt;
        return std::move(*pixels);
    }
    return std::nullopt;
}

/// Writes `pixels` as a binary PPM.
void write_ppm(const fs::path& path, const gpu::Pixels& pixels) {
    std::ofstream out(path, std::ios::binary);
    out << "P6\n" << pixels.width << ' ' << pixels.height << "\n255\n";
    for (std::size_t i = 0; i + 3 < pixels.rgba.size(); i += 4) {
        out.write(reinterpret_cast<const char*>(&pixels.rgba[i]), 3); // NOLINT: the pixel's three bytes
    }
}

/// The pyramid's column and row (fractions) of a place at level `z`.
[[nodiscard]] std::array<double, 2> pyramid_of(double lat, double lon, std::uint8_t z) {
    const double n = std::ldexp(1.0, z);
    const double phi = lat * std::numbers::pi / 180.0;
    return {((lon + 180.0) / 360.0) * n, ((1.0 - (std::asinh(std::tan(phi)) / std::numbers::pi)) / 2.0) * n};
}

/// The test's hills: a surface over the lattice of level 13's samples (256 a tile), in steps of 0.25 m; a point of
/// level 12's lattice is every second one's.
[[nodiscard]] std::int64_t hills(std::int64_t row13, std::int64_t column13) {
    const double h = 60.0 + (40.0 * std::sin(row13 * 0.049) * std::cos(column13 * 0.061)) +
                     (12.0 * std::sin((row13 + column13) * 0.17));
    return std::llround(h / 0.25);
}

/// The ground (y 0) under device point (x, y) of camera `c` at density 1; none at the horizon or above.
[[nodiscard]] std::optional<view::Vec3> ground_under(const view::Camera& c, double x, double y) {
    const double u = (x - c.cx()) / c.focal();
    const double v = (y - c.cy()) / c.focal();
    const view::Vec3& f = c.forward();
    const view::Vec3& r = c.right();
    const view::Vec3& up = c.up();
    const view::Vec3 d{f.x + (r.x * u) - (up.x * v), f.y + (r.y * u) - (up.y * v), f.z + (r.z * u) - (up.z * v)};
    if (!(d.y < 0)) return std::nullopt;
    const double t = -c.eye().y / d.y;
    return view::Vec3{c.eye().x + (t * d.x), 0.0, c.eye().z + (t * d.z)};
}

/// The lanes of raster part `part` at the share (`u`, `v`) of its tile, between its samples as a sampler reads them.
[[nodiscard]] shaders::float3 lanes_at(const scene::ReliefPart& part, double u, double v) {
    const fx::ReliefBake& bake = part.tile->bake;
    const double span = bake.side - 1.0;
    const double x = std::clamp((part.x + (u * part.extent)) * span, 0.0, span);
    const double y = std::clamp((part.y + (v * part.extent)) * span, 0.0, span);
    const auto x0 = static_cast<std::uint32_t>(std::min(std::floor(x), span - 1.0));
    const auto y0 = static_cast<std::uint32_t>(std::min(std::floor(y), span - 1.0));
    const auto tx = static_cast<float>(x - x0);
    const auto ty = static_cast<float>(y - y0);
    const auto texel = [&bake](std::uint32_t r, std::uint32_t c) {
        return fx::relief_lanes(bake.texels[(std::size_t{r} * bake.side) + c]);
    };
    const shaders::float3 north = (texel(y0, x0) * (1.0F - tx)) + (texel(y0, x0 + 1) * tx);
    const shaders::float3 south = (texel(y0 + 1, x0) * (1.0F - tx)) + (texel(y0 + 1, x0 + 1) * tx);
    return (north * (1.0F - ty)) + (south * ty);
}

} // namespace

TEST_CASE("the app's map lights the ground by its terrain pack's heights: each pixel the flat frame's times the twin's "
          "light; the terrain off, the frame before the pack byte for byte") {
    if (!test::open_session()) return; // no Vulkan here
    const Folder folder("light");
    const fs::path& dir = folder.path();
    if (!can_start(dir)) return;

    // A terrain pack about the dunes' place: one level 12 raster, and a level 13 raster of its north-west quarter
    // alone, both of one surface (a level 12 sample is every second level 13 sample).
    const double lat = 52.40;
    const double lon = 4.58;
    const std::array<double, 2> at12 = pyramid_of(lat, lon, 12);
    const auto x12 = static_cast<std::uint32_t>(at12[0]);
    const auto y12 = static_cast<std::uint32_t>(at12[1]);
    namespace terrain = test::terrain;
    terrain::Raster coarse =
        terrain::raster_of(257, 8, [](std::int64_t r, std::int64_t c) { return hills(2 * r, 2 * c); });
    terrain::Raster fine = terrain::raster_of(257, 8, [](std::int64_t r, std::int64_t c) { return hills(r, c); });
    coarse.step = 0.25;
    fine.step = 0.25;
    std::vector<test::TileBytes> tiles;
    tiles.push_back(terrain::terrain_tile(12, x12, y12, coarse));
    tiles.push_back(terrain::terrain_tile(13, x12 * 2, y12 * 2, fine));
    constexpr std::array<std::uint8_t, 2> levels{13, 12};
    const std::vector<std::byte> pack = terrain::terrain_pack(std::move(tiles), levels);
    const fs::path pack_path = dir / "dunes-terrain.atlas";
    {
        std::ofstream out(pack_path, std::ios::binary);
        out.write(reinterpret_cast<const char*>(pack.data()), // NOLINT: the pack's bytes
                  static_cast<std::streamsize>(pack.size()));
    }
    const auto listed = [&pack_path] {
        frame::MapPack p;
        p.number = 7;
        p.ref.id = "terrain/test/9/0/0";
        p.ref.kind = tiles::PackKind::terrain_country;
        p.ref.path = pack_path.string();
        return std::vector<frame::MapPack>{p};
    };

    auto started = gpu::start_guarded(start_in(dir));
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    frame::MapFrame map(2, nullptr, {.cache_dir = dir});
    standin::Session session(*started, {.cache_file = dir / "pipelines.bin", .spirv = 42});
    // the relief alone over the scene: no haze, vignette or grain after it
    map.set_screen(false, false, false);
    CHECK(session.map(&map).empty());
    auto window = std::make_unique<KeptWindow>();
    KeptWindow* kept = window.get();
    std::string why;
    ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
    map.set_theme(moment(false));

    // looking north-east over the middle of the level 12 tile, where its four quarters meet, from 2.5 km
    const double mid_lon = (((x12 + 0.5) / 4096.0) * 360.0) - 180.0;
    const double mid_lat =
        std::atan(std::sinh(std::numbers::pi * (1.0 - ((2.0 * (y12 + 0.5)) / 4096.0)))) * 180.0 / std::numbers::pi;
    const style::CameraState camera = camera_at(mid_lat, mid_lon, 40.0, 48.0, 2'500.0);

    // before the pack: the flat frame
    const std::optional<gpu::Pixels> before = draw_lit(session, map, *kept, camera);
    ATLAS_REQUIRE(before.has_value(), "no frame was read back");
    CHECK(map.terrain_sources().empty());
    CHECK(map.terrain_wants().empty());
    // the block the view lies in is asked for whether or not a pack is open for it
    const std::vector<tiles::TileKey> blocks = map.terrain_blocks();
    REQUIRE(!blocks.empty());
    CHECK(blocks.front() == tiles::TileKey(9, x12 >> 3U, y12 >> 3U));

    // the pack listed with the terrain off: the same frame, byte for byte
    map.set_terrain(false);
    CHECK(map.set_packs(listed()) == "terrain/test/9/0/0 opened\n");
    const std::optional<gpu::Pixels> off = draw_lit(session, map, *kept, camera);
    ATLAS_REQUIRE(off.has_value(), "no frame was read back");
    CHECK(*off == *before);
    CHECK(map.terrain_blocks().empty());
    const passes::Relief* pass = relief_pass(map);
    REQUIRE(pass != nullptr);
    CHECK(pass->uploads() == 0);

    // the terrain on
    map.set_terrain(true);
    const std::optional<gpu::Pixels> lit = draw_lit(session, map, *kept, camera);
    ATLAS_REQUIRE(lit.has_value(), "no frame was read back");
    CHECK(*lit != *before);
    CHECK(map.terrain_sources() == "ahn4,glo30");
    CHECK(map.terrain_wants().empty()); // a pack on the phone: nothing to fetch
    CHECK(pass->uploads() == 2);
    const scene::ReliefTiles* relief = map.world()->relief_tiles();
    REQUIRE(relief != nullptr);
    // the level 13 raster over its own tile, the level 12 raster's parts over the other three quarters
    const scene::ReliefFrame& frame = relief->frame();
    std::size_t own13 = 0;
    std::size_t parts12 = 0;
    for (const scene::ReliefDraw& d : frame.draws) {
        REQUIRE(d.part.tile != nullptr);
        CHECK(d.own);
        if (d.key.z == 13 && d.part.tile->key.z == 13) ++own13;
        if (d.key.z == 13 && d.part.tile->key.z == 12) {
            ++parts12;
            CHECK(d.part.extent == 0.5F);
        }
    }
    CHECK(own13 == 1);
    CHECK(parts12 == 3);
    CHECK(frame.wanted == 0);

    // each pixel of the ground: the flat frame's times the twin's light there
    const frame::FrameState& state = map.builder()->state();
    REQUIRE(state.view.has_value());
    const view::Camera& cam = state.view->camera();
    const auto compare = [&](const gpu::Pixels& flat, const gpu::Pixels& drawn, bool sky_view) {
        shaders::ReliefLight light{};
        const auto& push = pass->push(state.slot);
        std::memcpy(&light, &push.light, sizeof(light));
        CHECK((light.range.z != 0.0F) == sky_view);
        std::size_t compared = 0;
        std::size_t close = 0;
        std::size_t shaped = 0; // where the light moves the colour by 5 % or more
        for (std::uint32_t y = 0; y < size.height; y += 3) {
            for (std::uint32_t x = 0; x < size.width; x += 3) {
                const auto g = ground_under(cam, x + 0.5, y + 0.5);
                if (!g) continue;
                const scene::ReliefDraw* over = nullptr;
                for (const scene::ReliefDraw& d : frame.draws) {
                    // a pixel well inside a tile: at an edge the rasteriser's choice of tile is its own
                    const double u = (g->x - d.corner.x) / d.east;
                    const double v = (g->z - d.corner.z) / d.south;
                    if (u > 0.002 && u < 0.998 && v > 0.002 && v < 0.998) over = &d;
                }
                if (over == nullptr) continue;
                const double depth = cam.depth(*g);
                const double near = std::clamp(1.0 - ((depth - push.far[0]) * push.far[1]), 0.0, 1.0);
                const double u = (g->x - over->corner.x) / over->east;
                const double v = (g->z - over->corner.z) / over->south;
                const shaders::float3 c =
                    shaders::relief_at(light, lanes_at(over->part, u, v), static_cast<float>(near));
                const std::size_t at = ((std::size_t{y} * drawn.width) + x) * 4;
                bool ok = true;
                for (std::size_t k = 0; k < 3; ++k) {
                    const double expected = std::min(255.0, flat.rgba[at + k] * static_cast<double>(c[k]));
                    if (std::abs(expected - drawn.rgba[at + k]) > 3.0) ok = false;
                }
                ++compared;
                if (ok) ++close;
                if (std::abs(c.y - 1.0F) >= 0.05F) ++shaped;
            }
        }
        MESSAGE(std::format("{} ground pixels compared, {} within 3 levels of the twin, {} where the light moves the "
                            "colour by 5 % or more",
                            compared, close, shaped));
        CHECK(compared > 3000);
        CHECK(shaped > compared / 4);
        CHECK(close >= (compared * 97) / 100);
    };
    compare(*before, *lit, true);

    // without the sky-view: another frame, the twin's without it
    map.set_sky_view(false);
    const std::optional<gpu::Pixels> plain = draw_lit(session, map, *kept, camera);
    ATLAS_REQUIRE(plain.has_value(), "no frame was read back");
    CHECK(*plain != *lit);
    compare(*before, *plain, false);
    map.set_sky_view(true);

    // off again: the flat frame again, and nothing asked of the app
    map.set_terrain(false);
    const std::optional<gpu::Pixels> again = draw_lit(session, map, *kept, camera);
    ATLAS_REQUIRE(again.has_value(), "no frame was read back");
    CHECK(*again == *before);

    if (const std::string out = environment("ATLAS_RELIEF_OUT"); !out.empty()) {
        fs::create_directories(out);
        write_ppm(fs::path(out) / "hills-flat.ppm", *before);
        write_ppm(fs::path(out) / "hills-relief.ppm", *lit);
        write_ppm(fs::path(out) / "hills-relief-no-sky-view.ppm", *plain);
    }
    session.close();
}

TEST_CASE("the boards' views of the dunes and San Gimignano over their terrain packs, for the look's pictures") {
    const std::string packs_dir = environment("ATLAS_RELIEF_PACKS");
    const std::string out_dir = environment("ATLAS_RELIEF_OUT");
    if (packs_dir.empty() || out_dir.empty()) {
        MESSAGE("ATLAS_RELIEF_PACKS and ATLAS_RELIEF_OUT are not set: the boards' views are not drawn here");
        return;
    }
    if (!test::open_session()) return;
    const Folder folder("views");
    const fs::path& dir = folder.path();
    if (!can_start(dir)) return;
    auto started = gpu::start_guarded(start_in(dir));
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    frame::MapFrame map(2, nullptr, {.cache_dir = dir});
    standin::Session session(*started, {.cache_file = dir / "pipelines.bin", .spirv = 42});
    CHECK(session.map(&map).empty());
    constexpr vk::Extent2D board{.width = 540, .height = 1200};
    auto window = std::make_unique<KeptWindow>();
    KeptWindow* kept = window.get();
    std::string why;
    ATLAS_REQUIRE(session.attach(std::move(window), board, why), why);
    std::vector<frame::MapPack> packs;
    std::vector<fs::path> files;
    for (const fs::directory_entry& e : fs::recursive_directory_iterator(packs_dir)) {
        if (e.is_regular_file() && e.path().extension() == ".atlas") files.push_back(e.path());
    }
    std::ranges::sort(files);
    for (const fs::path& f : files) {
        frame::MapPack p;
        p.number = packs.size() + 1;
        p.ref.id = "terrain/" + f.stem().string();
        p.ref.kind = tiles::PackKind::terrain_country;
        p.ref.path = f.string();
        packs.push_back(std::move(p));
    }
    MESSAGE(map.set_packs(std::move(packs)));
    fs::create_directories(out_dir);
    // data/regions/places.py's boards: a camera's target east and south of its origin, its yaw and pitch (degrees
    // below the horizon), its distance
    struct View {
        const char* name;
        double lat, lon, east, south, yaw, pitch, distance;
        bool overcast;
    };
    for (const View& v : {View{"dunes", 52.4000, 4.5800, -1150.0, 250.0, 262.0, 30.0, 3000.0, false},
                          View{"sangimignano", 43.4677, 11.0432, -24.0, 22.0, 45.0, 34.0, 430.0, true},
                          View{"sangimignano-far", 43.4677, 11.0432, -24.0, 22.0, 45.0, 34.0, 2400.0, true}}) {
        const double kx = 111320.0 * pymath::cos(pymath::radians(v.lat));
        const style::CameraState camera = camera_at(v.lat - (v.south / 110540.0), v.lon + (v.east / kx),
                                                    view::bearing_of(v.yaw), 90.0 - v.pitch, v.distance, board);
        for (const bool overcast : {v.overcast, !v.overcast}) {
            map.set_theme(moment(overcast));
            const char* sky = overcast ? "overcast" : "sunny";
            map.set_terrain(false);
            if (const auto flat = draw_lit(session, map, *kept, camera)) {
                write_ppm(fs::path(out_dir) / std::format("{}-{}-flat.ppm", v.name, sky), *flat);
            }
            map.set_terrain(true);
            map.set_sky_view(true);
            if (const auto lit = draw_lit(session, map, *kept, camera)) {
                write_ppm(fs::path(out_dir) / std::format("{}-{}-relief.ppm", v.name, sky), *lit);
            }
            map.set_sky_view(false);
            if (const auto plain = draw_lit(session, map, *kept, camera)) {
                write_ppm(fs::path(out_dir) / std::format("{}-{}-relief-no-sky-view.ppm", v.name, sky), *plain);
            }
            map.set_sky_view(true);
        }
        const scene::ReliefStats stats = map.world()->relief_tiles()->stats();
        MESSAGE(std::format("{}: {} rasters baked in {:.0f} ms all told, sources {}", v.name, stats.baked,
                            stats.bake_ms, map.terrain_sources()));
    }
    session.close();
}

#endif // !__ANDROID__
