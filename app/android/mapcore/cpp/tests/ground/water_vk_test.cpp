// The water's clips on the host's Vulkan (plan-vector K9, D10 "Water: surface"): the renders' clip paths are nonzero
// unions of every ring (render/amsterdam.py:457: water areas turned one way, islands the other), which the ground
// counts in the stencil (front faces up, back faces down, wrapping in three bits, passes/Water.cpp). A synthetic render
// pack holds rings that overlap turning the same way and the opposite way, a ring that crosses itself, and a water ring
// with an island; the frame drawn straight down through the world and the ground pass on lavapipe is held, pixel by
// pixel, to a CPU raster of the same rings by the nonzero rule at the standard 4x sample positions: without weather
// (every ring's own fill, islands undrawn) and with it (w0 at the street, wclip at the water level, clipped by w0, the
// island land).
#if !defined(__ANDROID__)

#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "geometry/Water.hpp"
#include "gpu/Frames.hpp"
#include "gpu/PipelineCache.hpp"
#include "passes/Ground.hpp"
#include "platform/host/Offscreen.hpp"
#include "scene/World.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/layers/Ground.hpp"
#include "tiles/layers/Water.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <vector>

using namespace atlas;

namespace {

namespace geo = atlas::geometry;

/// The synthetic pack's frame (support.hpp's head_bytes: the Westerkerk's).
constexpr double lat0 = 52.3746;
constexpr double lon0 = 4.884;
constexpr double kx = 67954.56338551514;
constexpr double kz = 110540.0;

/// A ring in metres (x east, z south) and whether it is an island.
struct TestRing {
    std::vector<geo::P2> pts;
    bool hole = false;
};

/// The rings: A and B overlapping, turning opposite ways; C crossing itself (a bow tie); D a water area with an
/// island E; F and G overlapping, turning the same way.
std::vector<TestRing> rings() {
    return {
        {{{-90, -90}, {-30, -90}, {-30, -30}, {-90, -30}}, false}, // A
        {{{-60, -60}, {-60, 0}, {0, 0}, {0, -60}}, false},         // B: the other way
        {{{20, -80}, {80, -20}, {80, -80}, {20, -40}}, false}, // C: a bow tie (lobes unequal: a symmetric one has no
                                                               // area, which the renders' area test drops)
        {{{-85, 20}, {85, 20}, {85, 88}, {-85, 88}}, false},   // D
        {{{-30, 40}, {30, 40}, {30, 70}, {-30, 70}}, true},    // E: D's island
        {{{40, -10}, {75, -10}, {75, 12}, {40, 12}}, false},   // F
        {{{60, 0}, {88, 0}, {88, 15}, {60, 15}}, false},       // G: overlapping F, the same way
    };
}

/// The rings on OSM's grid.
std::pair<std::int32_t, std::int32_t> grid_of(const geo::P2& p, double origin_lon = lon0) {
    const double lat = lat0 - (p.z / kz);
    const double lon = origin_lon + (p.x / kx);
    return {static_cast<std::int32_t>(std::lround(lat * 1e7)), static_cast<std::int32_t>(std::lround(lon * 1e7))};
}

/// A piece of a water ring in a tile: its points, whether an island, its ring's index (its pieces share it).
struct TestPiece {
    std::vector<geo::P2> pts;
    bool hole = false;
    std::uint32_t index = 0;
};

/// A tile of the synthetic pack and its water pieces.
struct TestTile {
    tiles::TileKey key;
    std::vector<TestPiece> pieces;
};

/// support.hpp's rhead (the Westerkerk's ladder, its home view) with the frame's origin at (lat0, origin_lon): a pack
/// without its region's views places the camera at its own target, which the cut scene puts at the tiles' edge.
std::vector<std::byte> head_at(double origin_lon) {
    test::Sink h;
    h.u32(tiles::head_magic);
    h.u16(tiles::payload_version);
    h.u8(static_cast<std::uint8_t>(tiles::Profile::living));
    h.u8(1);
    h.u8(14);
    h.f64(lat0);
    h.f64(origin_lon);
    h.f64(kx);
    h.f64(kz);
    h.utf8("amsterdam");
    h.utf8("a1-test");
    h.utf8("s1.1-test");
    h.utf8("0123456789abcdef");
    h.varu(1);
    h.utf8("home");
    h.u8(8); // a map of one entry: dist = 430.0
    h.varu(1);
    h.utf8("dist");
    h.u8(5);
    h.f64(430.0);
    h.u8(0);
    h.u8(1);
    h.u8(0);
    return h.bytes;
}

/// Writes a synthetic pack of `tiles` (zoom 14), their pieces in each one's water layer, as `name`, its frame's origin
/// at (lat0, origin_lon).
std::filesystem::path write_pack(const std::filesystem::path& dir, const std::string& name,
                                 const std::vector<TestTile>& tiles, double origin_lon = lon0) {
    std::vector<std::vector<std::byte>> raws;
    std::vector<test::ChunkBytes> chunks;
    for (const TestTile& tile : tiles) {
        test::TileBytes t;
        t.level = tile.key.z;
        t.x = tile.key.x;
        t.y = tile.key.y;
        std::vector<test::Ring> geometry;
        test::Sink index;
        test::Sink hole;
        test::Sink keys;
        std::int64_t last = 0;
        for (const TestPiece& r : tile.pieces) {
            test::Ring g;
            for (const geo::P2& p : r.pts) {
                const auto [la, lo] = grid_of(p, origin_lon);
                g.lat.push_back(la);
                g.lon.push_back(lo);
            }
            geometry.push_back(g);
            index.varz(static_cast<std::int64_t>(r.index) - last); // delta
            last = r.index;
            hole.u8(r.hole ? 2 : 1);
            keys.varu((std::uint64_t{tiles::ground_key::water} << 28U) | (std::uint64_t{r.hole ? 1U : 0U} << 22U) |
                      r.index);
        }
        test::LayerBytes water{.id = static_cast<std::uint8_t>(tiles::LayerId::water)};
        water.cells.assign(geometry.size(), 0);
        water.columns.push_back({"index", tiles::Codec::delta, index.bytes});
        water.columns.push_back({"hole", tiles::Codec::boolean, hole.bytes});
        water.columns.push_back({"ring", tiles::Codec::geom, test::columns::geom(geometry)});
        water.columns.push_back({"ground_key", tiles::Codec::varu, keys.bytes});
        t.layers.push_back(std::move(water));
        raws.push_back(t.encode());
        chunks.push_back({.tile = tile.key,
                          .raw = raws.back(),
                          .count = static_cast<std::uint32_t>(tile.pieces.size()),
                          .reach = t.reach()});
    }
    const std::array<std::uint8_t, 1> levels{14};
    const std::vector<std::byte> pack =
        test::pack_bytes(chunks, levels, tiles::format_render, {.head = head_at(origin_lon)});
    std::filesystem::create_directories(dir);
    const std::filesystem::path path = dir / name;
    std::ofstream(path, std::ios::binary)
        .write(reinterpret_cast<const char*>(pack.data()), // NOLINT
               static_cast<std::streamsize>(pack.size()));
    return path;
}

/// The overlap scene: every ring whole in one tile.
std::vector<TestTile> overlap_tiles() {
    TestTile t{.key = {14, 8414, 5384}, .pieces = {}};
    std::uint32_t i = 0;
    for (const TestRing& r : rings()) t.pieces.push_back({.pts = r.pts, .hole = r.hole, .index = i++});
    return {t};
}

/// The cut scene, in a frame whose origin is the edge between tiles 8414 and 8415: a water ring and its island across
/// it, each cut there as the packer cuts it, one piece per tile.
constexpr double lon_edge = (8415.0 / 16384.0 * 360.0) - 180.0;

std::vector<TestRing> cut_rings() {
    // edges off the sample positions (an edge exactly on a sample lands either side in float)
    return {{{{-70.21, -44.71}, {61.37, -44.71}, {61.37, 40.29}, {-70.21, 40.29}}, false},
            {{{-25.13, -15.41}, {20.33, -15.41}, {20.33, 18.27}, {-25.13, 18.27}}, true}};
}

std::vector<TestTile> cut_tiles() {
    // the edge on OSM's grid: both pieces share its points exactly
    const double e = ((static_cast<double>(grid_of({.x = 0, .z = 0}, lon_edge).second) / 1e7) - lon_edge) * kx;
    std::vector<TestTile> out{{.key = {14, 8414, 5384}, .pieces = {}}, {.key = {14, 8415, 5384}, .pieces = {}}};
    for (const TestRing& r : cut_rings()) {
        const double x0 = r.pts[0].x;
        const double x1 = r.pts[1].x;
        const double z0 = r.pts[0].z;
        const double z1 = r.pts[2].z;
        out[0].pieces.push_back({.pts = {{x0, z0}, {e, z0}, {e, z1}, {x0, z1}}, .hole = r.hole, .index = 0});
        out[1].pieces.push_back({.pts = {{e, z0}, {x1, z0}, {x1, z1}, {e, z1}}, .hole = r.hole, .index = 0});
    }
    return out;
}

/// The winding number of ring `r` (turned as the stage turns it) about (x, z).
int winding(const TestRing& r, double x, double z) {
    std::vector<geo::P2> pts = r.pts;
    geo::orient_water(pts, r.hole);
    int w = 0;
    const std::size_t n = pts.size();
    for (std::size_t i = 0; i < n; ++i) {
        const geo::P2& a = pts[i];
        const geo::P2& b = pts[(i + 1) % n];
        const double cross = ((b.x - a.x) * (z - a.z)) - ((x - a.x) * (b.z - a.z));
        if (a.z <= z) {
            if (b.z > z && cross > 0) ++w;
        } else if (b.z <= z && cross < 0) {
            --w;
        }
    }
    return w;
}

/// Where the ray through CSS point (sx, sy) of `cam` meets the plane y = `y`.
geo::P2 ground_at(const view::Camera& cam, double sx, double sy, double y) {
    const double ax = (sx - cam.cx()) / cam.focal();
    const double ay = (cam.cy() - sy) / cam.focal();
    const view::Vec3& f = cam.forward();
    const view::Vec3& r = cam.right();
    const view::Vec3& u = cam.up();
    const double dx = f.x + (ax * r.x) + (ay * u.x);
    const double dy = f.y + (ax * r.y) + (ay * u.y);
    const double dz = f.z + (ax * r.z) + (ay * u.z);
    const double t = (y - cam.eye().y) / dy;
    return {.x = cam.eye().x + (dx * t), .z = cam.eye().z + (dz * t)};
}

/// The colour of a sample at CSS (sx, sy) over the whole rings `rs`: inside the water (as the clips go) or on the
/// plate.
std::array<double, 3> sample_colour(const view::Camera& cam, const std::vector<TestRing>& rs, double sx, double sy,
                                    bool weather, const std::array<double, 3>& water,
                                    const std::array<double, 3>& plate) {
    const geo::P2 p0 = ground_at(cam, sx, sy, 0.0);
    int w0 = 0;
    for (const TestRing& r : rs) {
        if (!weather && r.hole) continue;
        w0 += winding(r, p0.x, p0.z);
    }
    bool in = (w0 % 8) != 0;
    if (weather && in) {
        const geo::P2 p1 = ground_at(cam, sx, sy, -1.5);
        int w1 = 0;
        for (const TestRing& r : rs) w1 += winding(r, p1.x, p1.z);
        in = (w1 % 8) != 0;
    }
    return in ? water : plate;
}

/// Draws the pack at `path` straight down from 297.3 m over (lat0, lon), 192 px, and holds every pixel to the nonzero
/// raster of the whole rings `whole` at the standard 4x samples, without weather and with it; `name` names the frames
/// kept.
void check_scene(test::Session& session, const std::filesystem::path& path, const std::vector<TestRing>& whole,
                 double lon, const std::string& name) {
    const gpu::Device& device = *session.device;
    constexpr std::uint32_t size = 192;
    style::CameraState camera = test::s1_camera(lat0, lon, 0.0, 0.0, 297.3, size / 2.0);
    camera.density = 1.0;
    camera.width_px = camera.height_px = size;
    camera.width_dp = camera.height_dp = size;
    camera.principal_x = size / 2.0;
    for (const bool weather : {false, true}) {
        style::ThemeBlock th = test::sunny();
        th.ground = {.r = 0xD6, .g = 0xCF, .b = 0xC2, .a = 255};
        th.water = {.r = 0, .g = 0, .b = 255, .a = 255};
        th.water_top = {.r = 200, .g = 30, .b = 30, .a = 255};
        th.water_bottom = {.r = 200, .g = 30, .b = 30, .a = 255};
        if (!weather) th.flags = style::theme_flag::water_gradient; // a fixed moment: no weather
        auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
        ATLAS_REQUIRE(cache.has_value(), cache.error().text());
        std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes();
        const passes::Ground* ground = test::ground_of(passes);
        auto builder = frame::FrameBuilder::create(device, *cache, std::move(passes), {.steps = 0});
        ATLAS_REQUIRE(builder.has_value(), builder.error().text());
        double now = 0;
        auto world = scene::World::create(device, (*builder)->passes(),
                                          {.workers = 1, .sun = {.enabled = false}, .clock = [&now] { return now; }});
        ATLAS_REQUIRE(world.has_value(), world.error().text());
        ATLAS_REQUIRE((*world)->store().add({.id = "rings", .kind = tiles::PackKind::bundled, .path = path.string()}),
                      "the synthetic pack opens");
        (*world)->set_active("rings");
        auto output = platform::Offscreen::create(device, {.width = size, .height = size}, 2);
        ATLAS_REQUIRE(output.has_value(), output.error().text());
        (*builder)->set_inputs({.camera = camera, .theme = th, .seconds = 0, .scene = world->get()});
        const gpu::Configuration config{.config = gpu::FrameConfig::c,
                                        .route = gpu::Route::renderpass,
                                        .form = gpu::FrameForm::composite,
                                        .target = gpu::CompositeTarget::resolve};
        auto renderer = gpu::Renderer::create(device, **output, **builder, config);
        ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
        for (int f = 0; f < 12; ++f) {
            now = static_cast<double>(f) * 0.05;
            auto report = (*renderer)->draw(0);
            ATLAS_REQUIRE(report.has_value(), report.error().text());
            (*world)->residency().wait_idle();
        }
        (*renderer)->drain();
        auto packed = (*output)->read_packed((*output)->latest());
        ATLAS_REQUIRE(packed.has_value(), packed.error().text());
        const platform::Pixels px = platform::to_rgba8(*packed);
        const auto& framed = (*builder)->state().view;
        ATLAS_REQUIRE(framed.has_value(), "the frame has a view");
        const view::Camera& cam = framed->camera();
        CHECK(ground->decided().tiles.size() == (name == "cut" ? 2U : 1U));
        // the water's colour inside: without weather th['water']; with it wgrad's (flat here) under the tint at .12
        const std::array<double, 3> plate{0xD6, 0xCF, 0xC2};
        const std::array<double, 3> water =
            weather ? std::array<double, 3>{(200 * 0.88), (30 * 0.88), (30 * 0.88) + (255 * 0.12)}
                    : std::array<double, 3>{0, 0, 255};
        constexpr std::array<std::array<double, 2>, 4> samples{
            {{0.375, 0.125}, {0.875, 0.375}, {0.125, 0.625}, {0.625, 0.875}}};
        std::size_t wrong = 0;
        std::size_t water_pixels = 0;
        std::vector<std::uint8_t> expect(std::size_t{size} * size * 3);
        for (std::uint32_t y = 0; y < size; ++y) {
            for (std::uint32_t x = 0; x < size; ++x) {
                std::array<double, 3> want{};
                bool any = false;
                for (const auto& sm : samples) {
                    const auto c = sample_colour(cam, whole, x + sm[0], y + sm[1], weather, water, plate);
                    any = any || c != plate;
                    for (std::size_t k = 0; k < 3; ++k) want[k] += c[k] / 4;
                }
                water_pixels += any ? 1 : 0;
                const std::size_t i = (static_cast<std::size_t>(y) * size + x) * 4;
                for (std::size_t k = 0; k < 3; ++k) {
                    expect[((static_cast<std::size_t>(y) * size + x) * 3) + k] =
                        static_cast<std::uint8_t>(std::lround(want[k]));
                }
                for (std::size_t k = 0; k < 3; ++k) {
                    if (std::abs(static_cast<double>(px.rgba[i + k]) - want[k]) > 2.5) {
                        ++wrong;
                        break;
                    }
                }
            }
        }
        {
            const auto dir = std::filesystem::path(ATLAS_TEST_SCRATCH) / "ground";
            const std::string moment = weather ? "-wx" : "";
            std::ofstream a(dir / (name + moment + "-gpu.ppm"), std::ios::binary);
            a << "P6\n" << size << " " << size << "\n255\n";
            for (std::size_t i = 0; i < std::size_t{size} * size; ++i)
                a.write(reinterpret_cast<const char*>(&px.rgba[i * 4]), 3); // NOLINT
            std::ofstream b(dir / (name + moment + "-want.ppm"), std::ios::binary);
            b << "P6\n" << size << " " << size << "\n255\n";
            b.write(reinterpret_cast<const char*>(expect.data()),
                    static_cast<std::streamsize>(expect.size())); // NOLINT
        }
        const double share = static_cast<double>(wrong) / (size * size);
        MESSAGE(name << std::string(weather ? " with weather" : " without weather") << ": " << water_pixels
                     << " pixels of water, " << wrong << " of " << size * size
                     << " pixels off the nonzero raster by more than 2.5 levels");
        CHECK(water_pixels > size * size / 5);
        CHECK(share < 0.002); // a sample on a ring's very edge may land either side in float
        for (const std::string& m : session.take_messages()) CHECK_MESSAGE(false, m);
        renderer->reset();
    }
}

} // namespace

TEST_CASE(
    "ground: the water's clips are the nonzero unions of its rings, overlapping, crossing and turning both ways") {
    auto session = test::open_session();
    if (!session) return;
    if (!session->device->facts().supports(gpu::FrameConfig::c)) {
        MESSAGE("no 4x configuration here");
        return;
    }
    const auto dir = std::filesystem::path(ATLAS_TEST_SCRATCH) / "ground";
    check_scene(*session, write_pack(dir, "water-rings.atlas", overlap_tiles()), rings(), lon0, "rings");
}

TEST_CASE("ground: a water ring and its island cut at a tile's edge draw as the whole rings, without a seam") {
    // render packs cut the water at the tiles' edges, a piece per tile (tiles/layers/Water.hpp): the pieces' union is
    // the ring, so the frame draws the port's ring and island, at the street and at the water's level
    auto session = test::open_session();
    if (!session) return;
    if (!session->device->facts().supports(gpu::FrameConfig::c)) {
        MESSAGE("no 4x configuration here");
        return;
    }
    const auto dir = std::filesystem::path(ATLAS_TEST_SCRATCH) / "ground";
    check_scene(*session, write_pack(dir, "water-cut.atlas", cut_tiles(), lon_edge), cut_rings(), lon_edge, "cut");
}

#endif
